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
    already produced. Same "no second camera open" principle as the MJPEG server: this
    doesn't run its own capture loop — it periodically asks CameraService for a one-shot frame
    (onImageRequest(stream=false), the same call the idle loop and a one-shot CameraImageRequest
    use), and separately reacts to whatever frame a live HA/MJPEG viewer is already pulling.

    Inference is genuinely slow relative to a camera frame arriving (tens to a few hundred ms
    on the low-end/older hardware this project targets), so it's kept off CameraService's own
    capture thread entirely — the frame listener callback below just hands the JPEG to a
    dedicated single-thread executor and returns immediately; a frame that arrives while the
    previous one is still being processed is simply skipped (never queued), so this can never
    build an unbounded backlog or fall further and further behind.

*/


private const val MODEL_ASSET = "efficientdet_lite0.tflite"
private const val LABEL_PERSON = "person"

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

  // 30s, not the original 5s default: each trigger is a full camera open -> AE/AF
  // calibrate -> capture -> close cycle (see CameraService.capture()), not just a cheap
  // frame read — at 5s that's ~700 full camera power-cycles/hour, run forever, which is
  // heavy enough to matter for a device that's supposed to run indefinitely on battery or a
  // modest charger. Occupancy also doesn't need sub-30s granularity in the first place.
  val intervalSetting = Setting(
      id = "person_detector_interval", label = "Person Detection Interval (s)",
      default = 30f, min = 1f, max = 300f, step = 1f,
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false, icon = "mdi:timer-outline")

  override val settings: List<Setting> = listOf(confidenceSetting, intervalSetting)

  private var detector: ObjectDetector? = null
  private var executor: ExecutorService? = null
  @Volatile private var busy = false
  @Volatile private var running = false
  private var timerThread: Thread? = null
  private var appContext: Context? = null

  private val frameListener: (ByteArray) -> Unit = { jpeg ->
    if (!busy) {
      busy = true
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

  // Own periodic trigger — asks CameraService for a fresh one-shot frame at
  // intervalSetting's cadence, same mechanism its own idle loop uses. Doesn't force a
  // continuous stream (that would keep the camera open constantly); a live HA/MJPEG viewer
  // already streaming supplies frames far more often than this loop would ask for anyway,
  // and the frame listener above reacts to those the same way.
  private fun triggerLoop() {
    val context = appContext ?: return
    while (running) {
      try { CameraService.onImageRequest(context, stream = false) } catch (e: Exception) {}
      val intervalMs = (getSetting(context, intervalSetting) * 1000).toLong()
      try { Thread.sleep(intervalMs) } catch (_: InterruptedException) {}
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
