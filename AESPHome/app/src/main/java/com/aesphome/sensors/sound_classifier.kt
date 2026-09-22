package com.aesphome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.util.Log
import org.tensorflow.lite.support.audio.TensorAudio
import org.tensorflow.lite.task.audio.classifier.AudioClassifier


/*

  Sound Classification
    binary_sensor.* (dog_barking, baby_crying, screaming, glass_breaking, smoke_alarm, siren,
    doorbell, knocking, gunshot) / text_sensor.detected_sound — a small on-device audio event
    classifier (TFLite Task Library + a bundled YAMNet model, assets/yamnet.tflite, ~4.1MB,
    CPU-only, 521 AudioSet classes) run continuously against the microphone.

    Unlike decibel_meter.kt's periodic burst-then-close sampling (fine for a slow-changing
    ambient noise LEVEL), a transient event like a scream or a knock can easily fall entirely
    inside decibel_meter's "off" window between bursts — catching it reliably needs the mic
    genuinely listening the whole time. So this opens exactly one AudioRecord (via the Task
    Library's own createAudioRecord(), sized to what the model needs) when the service starts
    and keeps it open for as long as the service runs — the same "pay the hardware-open cost
    once, not per detection" lesson person_detector.kt's camera stream applies; see its file
    header comment for the fuller story of why that mattered on this project's hardware.

    Only a curated subset of YAMNet's 521 labels gets a dedicated binary sensor (the ones a
    home-monitoring setup is plausibly built around); text_sensor.detected_sound separately
    reports whatever the single highest-scoring class was on the last check, so an automation
    can react to a label that isn't one of the curated ones without this needing to add a
    sensor for every possibility. Every label string below was checked against yamnet.tflite's
    own bundled metadata, not guessed.

*/


private const val MODEL_ASSET = "yamnet.tflite"
private const val POLL_MS = 500L
private const val MAX_RESULTS = 10 // headroom above the curated label count, so more than one can score above threshold at once

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

  private var classifier: AudioClassifier? = null
  private var audioRecord: AudioRecord? = null
  private var tensorAudio: TensorAudio? = null
  @Volatile private var running = false
  private var thread: Thread? = null

  override fun start(context: Context) {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "Sound classifier: microphone permission not granted")
      return
    }

    val loaded = try {
      val options = AudioClassifier.AudioClassifierOptions.builder()
        .setScoreThreshold(getSetting(context, confidenceSetting) / 100f)
        .setMaxResults(MAX_RESULTS)
        .build()
      val c = AudioClassifier.createFromFileAndOptions(context, MODEL_ASSET, options)
      classifier = c
      tensorAudio = c.createInputTensorAudio()
      audioRecord = c.createAudioRecord()
      true
    } catch (e: Exception) {
      Log.e(TAG, "Sound classifier: failed to load $MODEL_ASSET", e)
      false
    }
    if (!loaded) { stop(context); return }

    try {
      audioRecord?.startRecording()
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
    tensorAudio = null
    classifier?.close()
    classifier = null
    // unavailable, not "nothing detected" — the classifier isn't running at all
    for (sensor in curatedLabelSensors.values) AESPHomeService.instance?.reportSensor(sensor, null)
    AESPHomeService.instance?.reportTextSensor(DetectedSoundSensor, null)
  }

  private fun loop() {
    while (running) {
      try { classify() } catch (e: Exception) { Log.e(TAG, "Sound classifier: inference failed", e) }
      try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) {}
    }
  }

  private fun classify() {
    val record = audioRecord ?: return
    val tensor = tensorAudio ?: return
    val c = classifier ?: return

    tensor.load(record)
    val categories = c.classify(tensor).firstOrNull()?.categories ?: emptyList()
    val byLabel = categories.associateBy { it.label }

    for ((label, sensor) in curatedLabelSensors) {
      AESPHomeService.instance?.reportSensor(sensor, byLabel.containsKey(label))
    }

    val top = categories.maxByOrNull { it.score }
    AESPHomeService.instance?.reportTextSensor(DetectedSoundSensor, top?.label)
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
