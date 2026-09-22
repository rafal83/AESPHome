package com.aesphome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.metadata.MetadataExtractor


/*

  Sound Classification
    binary_sensor.* (dog_barking, baby_crying, screaming, glass_breaking, smoke_alarm, siren,
    doorbell, knocking, gunshot) / text_sensor.detected_sound — a small on-device audio event
    classifier (bundled YAMNet model, assets/yamnet.tflite, ~4.1MB, CPU-only, 521 AudioSet
    classes) run continuously against the microphone.

    Deliberately NOT tensorflow-lite-task-audio (the Task Library's high-level AudioClassifier
    convenience API, the same family person_detector.kt's ObjectDetector belongs to) — its
    native init (initJniWithModelFdAndOptions, libtask_audio_jni.so) segfaulted loading this
    exact, officially-published model on real hardware (confirmed live: SIGABRT, "invalid
    address passed to free" — a real, known-flaky native library; see
    github.com/tensorflow/tensorflow/issues/96401 for a related libtask_audio_jni.so
    instability report). This drives the plain org.tensorflow.lite.Interpreter runtime
    directly instead — the same native core the Task Library wraps, but without going through
    its buggy audio-specific JNI layer — plus MetadataExtractor (a pure-Java flatbuffer
    reader, unrelated native surface) just to read the label list the model already bundles.
    YAMNet's input/output are both plain flat float32 tensors (see the model's own metadata
    description), simple enough to drive by hand once the Task Library's convenience wrapper
    is off the table.

    Unlike decibel_meter.kt's periodic burst-then-close sampling (fine for a slow-changing
    ambient noise LEVEL), a transient event like a scream or a knock can easily fall entirely
    inside decibel_meter's "off" window between bursts — catching it reliably needs the mic
    genuinely listening the whole time. So this opens exactly one AudioRecord when the service
    starts and keeps it open for as long as the service runs, reading contiguous, back-to-back
    windows of live audio (each classify() call blocks until a full window has arrived, so
    there's no gap between one window ending and the next starting) — the same "pay the
    hardware-open cost once, not per detection" lesson person_detector.kt's camera stream
    applies; see its file header comment for the fuller story of why that mattered on this
    project's hardware.

    Only a curated subset of YAMNet's 521 labels gets a dedicated binary sensor (the ones a
    home-monitoring setup is plausibly built around); text_sensor.detected_sound separately
    reports whatever the single highest-scoring class was on the last check, so an automation
    can react to a label that isn't one of the curated ones without this needing to add a
    sensor for every possibility. Every label string below was checked against yamnet.tflite's
    own bundled metadata, not guessed.

*/


private const val MODEL_ASSET = "yamnet.tflite"
private const val SAMPLE_RATE_HZ = 16000

private const val LABEL_BARK          = "Bark"
private const val LABEL_BABY_CRY      = "Baby cry, infant cry"
private const val LABEL_SCREAMING     = "Screaming"
private const val LABEL_GLASS_SHATTER = "Shatter"
private const val LABEL_SMOKE_ALARM   = "Smoke detector, smoke alarm"
private const val LABEL_SIREN         = "Siren"
private const val LABEL_DOORBELL      = "Doorbell"
private const val LABEL_KNOCK         = "Knock"
private const val LABEL_GUNSHOT       = "Gunshot, gunfire"

object SoundClassifierService : Service {
  override val id                  = "sound_classifier"
  override val label               = "Sound Classification"
  override val description         = "Requires Microphone Permission — detects specific sound types (bark, scream, alarm, etc.) using an on-device model"
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:ear-hearing"

  val confidenceSetting = Setting(
      id = "sound_classifier_confidence", label = "Sound Detection Confidence (%)",
      default = 50f, min = 10f, max = 90f, step = 5f,
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false, icon = "mdi:tune")

  override val settings: List<Setting> = listOf(confidenceSetting)

  private val curatedLabelSensors: Map<String, EventSensor> = mapOf(
      LABEL_BARK to DogBarkingSensor,
      LABEL_BABY_CRY to BabyCryingSensor,
      LABEL_SCREAMING to ScreamingSensor,
      LABEL_GLASS_SHATTER to GlassBreakingSensor,
      LABEL_SMOKE_ALARM to SmokeAlarmSensor,
      LABEL_SIREN to SirenSensor,
      LABEL_DOORBELL to DoorbellSensor,
      LABEL_KNOCK to KnockingSensor,
      LABEL_GUNSHOT to GunshotSensor,
  )

  private var interpreter: Interpreter? = null
  private var audioRecord: AudioRecord? = null
  private var labels: List<String> = emptyList()
  // Resolved once at load time (label string -> output tensor index), not looked up by name
  // on every single inference — curatedLabelSensors' keys are checked against this model's
  // own bundled label list rather than assumed to exist at some fixed index.
  private var curatedIndexSensors: List<Pair<Int, EventSensor>> = emptyList()
  private var inputBuffer: ByteBuffer? = null
  private var outputBuffer: ByteBuffer? = null
  private var inputSampleCount = 0
  private var numClasses = 0
  @Volatile private var running = false
  private var thread: Thread? = null
  private var appContext: Context? = null

  override fun start(context: Context) {
    appContext = context
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "Sound classifier: microphone permission not granted")
      return
    }

    val loaded = try {
      val modelBuffer = FileUtil.loadMappedFile(context, MODEL_ASSET)
      val interp = Interpreter(modelBuffer)
      interpreter = interp

      val inputTensor = interp.getInputTensor(0)
      val outputTensor = interp.getOutputTensor(0)
      if (inputTensor.dataType() != DataType.FLOAT32 || outputTensor.dataType() != DataType.FLOAT32) {
        Log.e(TAG, "Sound classifier: expected float32 input/output, got ${inputTensor.dataType()}/${outputTensor.dataType()}")
        false
      } else {
        inputSampleCount = inputTensor.numElements()
        numClasses = outputTensor.numElements()
        inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).order(ByteOrder.nativeOrder())

        val extractedLabels = MetadataExtractor(modelBuffer).getAssociatedFile("yamnet_label_list.txt")
            ?.bufferedReader()?.readLines() ?: emptyList()
        labels = extractedLabels
        val labelIndex = extractedLabels.withIndex().associate { (i, l) -> l to i }
        curatedIndexSensors = curatedLabelSensors.mapNotNull { (label, sensor) -> labelIndex[label]?.let { it to sensor } }
        if (curatedIndexSensors.size != curatedLabelSensors.size) {
          Log.e(TAG, "Sound classifier: ${curatedLabelSensors.size - curatedIndexSensors.size} curated label(s) not found in the model's own label list")
        }
        true
      }
    } catch (e: Exception) {
      Log.e(TAG, "Sound classifier: failed to load $MODEL_ASSET", e)
      false
    }
    if (!loaded) { stop(context); return }

    val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBufferSize <= 0) { Log.e(TAG, "Sound classifier: AudioRecord.getMinBufferSize failed"); stop(context); return }
    val record = try {
      // A few windows' worth of headroom so a brief scheduling delay on the reader side can't
      // overflow AudioRecord's own internal buffer and force it to drop samples.
      AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT, maxOf(minBufferSize, inputSampleCount * 2 * 4))
    } catch (e: Exception) { Log.e(TAG, "Sound classifier: AudioRecord init failed", e); null }
    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
      record?.release()
      Log.e(TAG, "Sound classifier: AudioRecord not initialized")
      stop(context)
      return
    }
    audioRecord = record

    try {
      record.startRecording()
    } catch (e: Exception) {
      Log.e(TAG, "Sound classifier: failed to start recording", e)
      stop(context)
      return
    }

    running = true
    thread = Thread({ loop() }, "AESPHomeSoundClassifier").apply { start() }
  }

  override fun stop(context: Context) {
    running = false
    thread?.interrupt()
    thread = null
    try { audioRecord?.stop() } catch (e: Exception) {}
    audioRecord?.release()
    audioRecord = null
    interpreter?.close()
    interpreter = null
    inputBuffer = null
    outputBuffer = null
    curatedIndexSensors = emptyList()
    labels = emptyList()
    // unavailable, not "nothing detected" — the classifier isn't running at all
    for (sensor in curatedLabelSensors.values) AESPHomeService.instance?.reportSensor(sensor, null)
    AESPHomeService.instance?.reportTextSensor(DetectedSoundSensor, null)
  }

  // Blocks until a full, contiguous window of live audio has arrived (no fixed poll interval
  // needed — AudioRecord.read() itself paces this to real time), classifies it, then starts
  // reading the next window immediately, so consecutive windows cover the input stream with
  // no gap.
  private fun loop() {
    val record = audioRecord ?: return
    val pcm = ShortArray(inputSampleCount)
    while (running) {
      var offset = 0
      while (running && offset < pcm.size) {
        val read = record.read(pcm, offset, pcm.size - offset)
        if (read <= 0) break
        offset += read
      }
      if (offset == pcm.size) {
        try { classify(pcm) } catch (e: Exception) { Log.e(TAG, "Sound classifier: inference failed", e) }
      }
    }
  }

  private fun classify(pcm: ShortArray) {
    val interp = interpreter ?: return
    val input = inputBuffer ?: return
    val output = outputBuffer ?: return
    val context = appContext

    input.rewind()
    val inFloats = input.asFloatBuffer()
    for (i in pcm.indices) inFloats.put(i, pcm[i] / 32768f)
    output.rewind()

    interp.run(input, output)

    output.rewind()
    val scores = FloatArray(numClasses)
    output.asFloatBuffer().get(scores)

    val threshold = if (context != null) getSetting(context, confidenceSetting) / 100f else 0.5f
    for ((index, sensor) in curatedIndexSensors) {
      AESPHomeService.instance?.reportSensor(sensor, scores[index] >= threshold)
    }

    val topIndex = scores.indices.maxByOrNull { scores[it] }
    AESPHomeService.instance?.reportTextSensor(DetectedSoundSensor, topIndex?.let { labels.getOrNull(it) })
  }
}

// Same "one shared class, several parameterized instances" idiom accelerometer.kt/
// gyroscope.kt/magnetic_field.kt already use for their X/Y/Z trios — these 8 sensors are
// structurally identical (binary_sensor, device_class "sound", no start/stop behavior of
// their own since SoundClassifierService drives every report by name) and differ only in
// id/label/icon.
private class SoundBinarySensor(
    override val id: String,
    override val label: String,
    override val icon: String,
) : EventSensor {
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override fun kind(context: Context) = SensorKind.Binary(deviceClass = "sound")
  override fun isAvailable(context: Context): Boolean = isEnabled(context, SoundClassifierService)
  override fun start(context: Context) {}
  override fun stop(context: Context) {}
}

val DogBarkingSensor: EventSensor = SoundBinarySensor("dog_barking", "Dog Barking", "mdi:dog")
val BabyCryingSensor: EventSensor = SoundBinarySensor("baby_crying", "Baby Crying", "mdi:baby-face-outline")
val ScreamingSensor: EventSensor = SoundBinarySensor("screaming", "Screaming", "mdi:account-alert")
val GlassBreakingSensor: EventSensor = SoundBinarySensor("glass_breaking", "Glass Breaking", "mdi:glass-fragile")
val SirenSensor: EventSensor = SoundBinarySensor("siren", "Siren", "mdi:alarm-light")
val DoorbellSensor: EventSensor = SoundBinarySensor("doorbell", "Doorbell", "mdi:bell-ring")
val KnockingSensor: EventSensor = SoundBinarySensor("knocking", "Knocking", "mdi:hand-back-right")
val GunshotSensor: EventSensor = SoundBinarySensor("gunshot", "Gunshot", "mdi:pistol")

// Smoke Alarm gets its own definition rather than soundBinarySensor() — "smoke" is a real,
// more specific HA binary_sensor device class (dedicated smoke-detector semantics/UI), unlike
// the rest above which have no better match than the generic "sound" class.
object SmokeAlarmSensor : EventSensor {
  override val id                     = "smoke_alarm"
  override val label                  = "Smoke Alarm"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val icon                   = "mdi:smoke-detector"
  override fun kind(context: Context) = SensorKind.Binary(deviceClass = "smoke")
  override fun isAvailable(context: Context): Boolean = isEnabled(context, SoundClassifierService)
  override fun start(context: Context) {}
  override fun stop(context: Context) {}
}

object DetectedSoundSensor : TextSensor {
  override val id                  = "detected_sound"
  override val label               = "Detected Sound"
  override val description         = "Highest-confidence sound classification from the last check — not limited to the curated sensors above"
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = false // lower-signal/noisier than the curated binary sensors above — opt-in
  override val icon                = "mdi:ear-hearing"
  override fun isAvailable(context: Context): Boolean = isEnabled(context, SoundClassifierService)
}
