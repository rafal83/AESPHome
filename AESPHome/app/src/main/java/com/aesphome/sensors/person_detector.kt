package com.aesphome

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector


/*

  Person Detection
    binary_sensor.person_detected / sensor.person_count — a small on-device object detector
    (TFLite Task Library + a bundled EfficientDet-Lite0 model, assets/efficientdet_lite0.tflite,
    ~4.3MB, CPU-only) run against whatever frame CameraService's existing capture pipeline
    already produced. Same "no second camera open" principle as the MJPEG server: this never
    opens its own camera session — it keeps CameraService's shared stream alive with the same
    keepalive re-request MJPEG's own live view uses (onImageRequest(stream=true), re-sent well
    inside CAMERA_STREAM_TIMEOUT_MS), and reacts to whichever frames arrive, from its own
    keepalive or a live HA/MJPEG viewer's.

    This used to instead poll with its own one-shot capture on a timer (stream=false) — a
    full camera open -> AE/AF calibrate -> capture -> close cycle every single trigger, not a
    cheap read. At a fast interval that's genuinely heavy (confirmed live: near-continuous
    camera power-cycling, enough on its own to outpace a modest charger). Keeping one session
    open the whole time this service runs pays that open/calibrate cost once instead of on
    every trigger, which is what makes a fast interval affordable here.

    Inference is genuinely slow relative to a camera frame arriving (tens to a few hundred ms
    on the low-end/older hardware this project targets), so it's kept off CameraService's own
    capture thread entirely — the frame listener callback below just hands the JPEG to a
    dedicated single-thread executor and returns immediately; a frame that arrives while the
    previous one is still being processed, or before intervalSetting has elapsed since the
    last one accepted, is simply skipped (never queued), so this can never build an unbounded
    backlog or fall further and further behind, and inference itself stays bounded to roughly
    once per interval even though frames arrive continuously.

*/


private const val MODEL_ASSET = "efficientdet_lite0.tflite"
private const val LABEL_PERSON = "person"
private const val STREAM_KEEPALIVE_MS = 2000L // comfortably under CameraService's CAMERA_STREAM_TIMEOUT_MS

object PersonDetectorService : Service {
  override val id                  = "person_detector"
  override val label               = "Person Detection"
  override val description         = "Requires Camera enabled — runs a small on-device model against the camera feed"
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:account-search"

  val confidenceSetting = Setting(
      id = "person_detector_confidence", label = "Person Detection Confidence (%)",
      default = 50f, min = 10f, max = 90f, step = 5f,
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false, icon = "mdi:tune")

  // Now just an inference-rate throttle against a continuously-open camera stream (see the
  // file header comment), not something that gates a full camera power-cycle — so a low
  // value here is cheap, unlike before this switched to streaming. 2s default: fast enough
  // that a screen woken on presence feels responsive, without running the detector model
  // flat-out on every frame the stream produces.
  val intervalSetting = Setting(
      id = "person_detector_interval", label = "Person Detection Interval (s)",
      default = 2f, min = 1f, max = 300f, step = 1f,
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false, icon = "mdi:timer-outline")

  override val settings: List<Setting> = listOf(confidenceSetting, intervalSetting)

  private var detector: ObjectDetector? = null
  private var executor: ExecutorService? = null
  @Volatile private var busy = false
  @Volatile private var running = false
  @Volatile private var lastAcceptedAtMs = 0L
  private var timerThread: Thread? = null
  private var appContext: Context? = null

  private val frameListener: (ByteArray) -> Unit = { jpeg ->
    val context = appContext
    val minIntervalMs = if (context != null) (getSetting(context, intervalSetting) * 1000).toLong() else 1000L
    val now = System.currentTimeMillis()
    if (!busy && now - lastAcceptedAtMs >= minIntervalMs) {
      busy = true
      lastAcceptedAtMs = now
      executor?.submit {
        try { runInference(jpeg) } catch (e: Exception) { Log.e(TAG, "Person detector: inference failed", e) }
        finally { busy = false }
      }
    }
  }

  override fun start(context: Context) {
    appContext = context
    val loaded = try {
      val options = ObjectDetector.ObjectDetectorOptions.builder()
        .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
        .setMaxResults(5)
        .setScoreThreshold(getSetting(context, confidenceSetting) / 100f)
        .build()
      detector = ObjectDetector.createFromFileAndOptions(context, MODEL_ASSET, options)
      true
    } catch (e: Exception) {
      Log.e(TAG, "Person detector: failed to load $MODEL_ASSET", e)
      false
    }
    if (!loaded) return

    executor = Executors.newSingleThreadExecutor()
    lastAcceptedAtMs = 0L
    CameraService.addFrameListener(frameListener)
    running = true
    timerThread = Thread({ triggerLoop() }, "AESPHomePersonDetectTimer").apply { start() }
  }

  override fun stop(context: Context) {
    running = false
    timerThread?.interrupt()
    timerThread = null
    CameraService.removeFrameListener(frameListener)
    executor?.shutdownNow()
    executor = null
    detector?.close()
    detector = null
    AESPHomeService.instance?.reportSensor(PersonDetectedSensor, null) // unavailable, not "no person" — the detector isn't running at all
    AESPHomeService.instance?.reportSensor(PersonCountSensor, null)
  }

  // Keeps CameraService's shared stream alive for as long as this service runs — the same
  // keepalive re-request MJPEG's live view uses (onImageRequest(stream=true) re-sent well
  // inside CAMERA_STREAM_TIMEOUT_MS). This pays the camera's open/AE-calibrate cost once,
  // not once per detection — actual detection cadence is the frame listener's own
  // intervalSetting throttle above, decoupled from how often the camera hardware itself
  // gets power-cycled.
  private fun triggerLoop() {
    val context = appContext ?: return
    while (running) {
      try { CameraService.onImageRequest(context, stream = true) } catch (e: Exception) {}
      try { Thread.sleep(STREAM_KEEPALIVE_MS) } catch (_: InterruptedException) {}
    }
  }

  private fun runInference(jpeg: ByteArray) {
    val det = detector ?: return
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
    try {
      val results = det.detect(TensorImage.fromBitmap(bitmap))
      val personDetections = results.count { result -> result.categories.any { it.label.equals(LABEL_PERSON, ignoreCase = true) } }
      AESPHomeService.instance?.reportSensor(PersonDetectedSensor, personDetections > 0)
      AESPHomeService.instance?.reportSensor(PersonCountSensor, personDetections.toFloat())
    } finally {
      bitmap.recycle()
    }
  }
}

object PersonDetectedSensor : EventSensor {
  override val id                     = "person_detected"
  override val label                  = "Person Detected"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val icon                   = "mdi:account"
  override fun kind(context: Context) = SensorKind.Binary(deviceClass = "occupancy")
  override fun isAvailable(context: Context): Boolean = isEnabled(context, PersonDetectorService)
  override fun start(context: Context) {}
  override fun stop(context: Context) {}
}

object PersonCountSensor : EventSensor {
  override val id                     = "person_count"
  override val label                  = "Person Count"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val icon                   = "mdi:account-multiple"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "", deviceClass = "")
  override fun isAvailable(context: Context): Boolean = isEnabled(context, PersonDetectorService)
  override fun start(context: Context) {}
  override fun stop(context: Context) {}
}
