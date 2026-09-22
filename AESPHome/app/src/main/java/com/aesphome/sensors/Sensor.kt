package com.aesphome

import android.content.Context

/*
The metadata every settings-toggleable component carries so nothing else in the app
needs a hardcoded reference to a specific one — the UI, settings, and AESPHome's
entity dispatch all just iterate Sensors.toggleables / .eventSensors / .readSensors
/ .services.
*/

// Matches EntityCategory in api.proto. NONE lets HA place the entity by its own type
// (sensor/binary_sensor -> Sensors, number/media_player/camera/switch -> Controls) —
// CONFIG and DIAGNOSTIC override that into their own dedicated sections.
enum class EntityCategory(val wireValue: Int) {
  NONE(0),
  CONFIG(1),
  DIAGNOSTIC(2),
}

// Anything with a persisted on/off setting and a switch in the UI. Sensor and
// Service both carry an ESPHome entity or background feature on top of this.
interface Toggleable {
  val id: String            // stable id: settings key (and HA object_id, for Sensor)
  val label: String         // UI switch text (and HA entity name, for Sensor)
  val description: String get() = "" // optional one-line blurb shown under the switch in the app; empty shows nothing
  val enabledByDefaultApp: Boolean   // whether this app enables/reports it by default
  val enabledByDefaultHa: Boolean get() = true // whether HA's registry starts it enabled by default
  val entityCategory: EntityCategory get() = EntityCategory.NONE
  val icon: String get() = "" // MDI icon override, e.g. "mdi:battery"; empty lets HA pick its own default
  val settings: List<Setting> get() = emptyList()
  val selectSettings: List<SelectSetting> get() = emptyList()

  // Whether the underlying hardware/OS feature this Toggleable depends on actually exists
  // on this device — checked in addition to (not instead of) the user's own enable flag,
  // so a sensor with no matching hardware is never advertised to HA with a permanently
  // dead/fabricated value. Defaults to true (every pre-existing Toggleable is unaffected);
  // only entities tied to optional hardware (a specific SensorManager sensor type, etc.)
  // need to override this.
  fun isAvailable(context: Context): Boolean = true
}

sealed class SensorKind {
  // Maps to ListEntitiesBinarySensorResponse/BinarySensorStateResponse.
  data class Binary(val deviceClass: String? = null) : SensorKind()

  // Maps to ListEntitiesSensorResponse/SensorStateResponse.
  data class Numeric(val unit: String, val deviceClass: String) : SensorKind()
}

// A configurable numeric value belonging to a Sensor, exposed as a real ESPHome Number
// entity when homeAssistant is true (id/key/label/min/max/step map straight onto
// ListEntitiesNumberResponse). deviceUi and homeAssistant are independent: a setting can
// show in the app only, HA only, both, or neither (code-only, changed by editing `default`).
class Setting(
    val id: String,   // persistence key, and HA object_id when exposed
    val label: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val step: Float,
    val deviceUi: Boolean,
    val homeAssistant: Boolean,
    val entityCategory: EntityCategory = EntityCategory.NONE,
    val enabledByDefaultHa: Boolean = true,
    val icon: String = "", // MDI icon override, e.g. "mdi:volume-high"; empty lets HA pick its own default
    val onChanged: ((Context) -> Unit)? = null, // fires after every persisted change, from setSetting()
    // Purely a device-UI display hint (never sent to HA, which has no matching concept) —
    // settings/selectSettings sharing the same non-null group render together under one
    // sub-header within their owning component's row in MainActivity, instead of one flat
    // list. Null (the default) renders ungrouped, exactly as before this existed.
    val group: String? = null,
) {
  val key: Int = id.hashCode() // ESPHome wire-protocol entity key — derived so it never needs manual tracking
}

// A fixed-choice setting belonging to a Sensor/Service, exposed as a real ESPHome Select
// entity when homeAssistant is true (id/key/label/options map onto
// ListEntitiesSelectResponse) — HA shows it as an actual dropdown, unlike Setting's Number
// entity. deviceUi and homeAssistant are independent, same as Setting.
class SelectSetting(
    val id: String,   // persistence key, and HA object_id when exposed
    val label: String,
    options: List<String>,
    val default: String,
    val deviceUi: Boolean,
    val homeAssistant: Boolean,
    val entityCategory: EntityCategory = EntityCategory.NONE,
    val enabledByDefaultHa: Boolean = true,
    val icon: String = "", // MDI icon override, e.g. "mdi:camera-switch"; empty lets HA pick its own default
    // If set, this is a command dropdown rather than a persisted setting (e.g. "Connect
    // <device>"): picking an option fires this instead of being saved, and the entity is
    // immediately reported back as `default` so it resets in HA after every use.
    val onCommand: ((Context, String) -> Unit)? = null,
    // If set, called after a new value is persisted (device UI or HA command alike) — e.g.
    // CameraService's lens/rotation/resolution dropdowns use this to invalidate a running
    // stream. Not called on the onCommand path above, since nothing is persisted there.
    val onChanged: ((Context) -> Unit)? = null,
    // Same device-UI-only grouping hint as Setting.group — see its doc comment.
    val group: String? = null,
) {
  init { require(default in options) { "SelectSetting '$id': default '$default' is not one of $options" } }

  // Mutable: most SelectSettings have a fixed option list for life, but some (e.g. CameraService's
  // lens picker) only know their real choices once a Context is available, and refresh it in place.
  var options: List<String> = options
  val key: Int = id.hashCode() // ESPHome wire-protocol entity key — derived so it never needs manual tracking
}

// A Toggleable that shows up to Home Assistant as an ESPHome entity.
interface Sensor : Toggleable {
  val key: Int               // ESPHome wire-protocol entity key — must be unique per device
  fun kind(context: Context): SensorKind // a function (not a fixed val) so it can depend on a live Setting/SelectSetting, e.g. a unit choice
}

// A stateless, press-only action exposed to HA as an ESPHome Button entity — unlike
// Sensor, it has no ongoing value to report, just a one-shot action run when HA
// sends a ButtonCommandRequest for it.
interface Button : Toggleable {
  val key: Int               // ESPHome wire-protocol entity key — must be unique per device
  fun press(context: Context)
}

// An on/off control exposed to HA as a real ESPHome Switch entity — unlike Setting's
// Number or SelectSetting's dropdown, it's a two-way boolean: HA flipping it calls
// setOn(), and start()/stop() (Startable) let it listen for the underlying state
// changing on its own (e.g. Bluetooth toggled from Android's quick settings) and
// report that back, the same way EventSensor does for a Sensor. Named SwitchEntity,
// not Switch, since MainActivity already uses android.widget.Switch unqualified.
interface SwitchEntity : Toggleable, Startable {
  val key: Int               // ESPHome wire-protocol entity key — must be unique per device
  fun isOn(context: Context): Boolean
  fun setOn(context: Context, on: Boolean)
}

// An ESPHome `update` entity — the same "update available" card with an Install button real
// ESPHome firmware's own OTA flow shows in Home Assistant. Two-way like SwitchEntity: HA sends
// an UpdateCommandRequest (CHECK or UPDATE) via onCommand(), and the implementation reports its
// own state back through AESPHome.pushUpdateState() whenever it changes (a check completes, an
// install starts/progresses/finishes) — there's no polling from esphome.kt's side.
interface UpdateEntity : Toggleable {
  val key: Int // ESPHome wire-protocol entity key — must be unique per device
  fun onCheckCommand(context: Context)
  fun onUpdateCommand(context: Context)
}

// The state an UpdateEntity reports through AESPHome.pushUpdateState() (esphome.kt). Home
// Assistant shows "up to date" when latestVersion == currentVersion, and an "Update available"
// card with an Install button otherwise — progress == null renders as an indeterminate
// spinner while inProgress is true; a 0..100 value renders a determinate progress bar.
data class UpdateState(
    val currentVersion: String,
    val latestVersion: String,
    val inProgress: Boolean = false,
    val progress: Float? = null,
    val releaseSummary: String = "",
    val releaseUrl: String = "",
)

// A component with a start()/stop() lifecycle, independent of what it toggles (an
// ESPHome entity, for EventSensor; nothing, for Service). Lets callers like MainActivity
// treat both the same way instead of branching on each type separately.
interface Startable {
  fun start(context: Context)
  fun stop(context: Context)
}

// A sensor whose value only changes on a real OS event (touch, movement, screen on/off).
// start()/stop() control whether it's actively listening at all.
interface EventSensor : Sensor, Startable

// A sensor with no natural event — polled on a timer by AESPHome instead.
// Returns null when the value genuinely isn't available right now (e.g. Wi-Fi link info
// while disconnected) — reported to HA as missing_state rather than a fabricated number.
interface ReadSensor : Sensor {
  fun read(context: Context): Float?
}

// A Toggleable background feature with no ESPHome entity of its own — nothing for
// Home Assistant to see, just a setting plus a start()/stop() lifecycle. mDNS
// advertisement and the media player's playback lifecycle are Services, not Sensors.
interface Service : Toggleable, Startable

// A Toggleable that shows up to Home Assistant as an ESPHome text_sensor — same shape as
// Sensor, minus `kind()` (text_sensor has no unit/device-class/state-class to pick between).
interface TextSensor : Toggleable {
  val key: Int // ESPHome wire-protocol entity key — must be unique per device
}

// A TextSensor with no natural event — polled on a timer by AESPHome instead, same as
// ReadSensor. Returns null when genuinely unavailable, reported as missing_state.
interface ReadTextSensor : TextSensor {
  fun read(context: Context): String?
}

object Sensors {

  val eventSensors: List<EventSensor> = listOf(
    BatteryPercent,
    TouchSensor,
    DeviceMovementSensor,
    ScreenStateSensor,
    BatteryCharging,
    CameraLuxSensor,
    LightSensor,
    DecibelMeterSensor,
    DeviceOrientationSensor,
    ProximitySensor,
    PressureSensor,
    HumiditySensor,
    AmbientTemperatureSensor,
    AccelerometerXSensor, AccelerometerYSensor, AccelerometerZSensor,
    GyroscopeXSensor, GyroscopeYSensor, GyroscopeZSensor,
    MagneticFieldXSensor, MagneticFieldYSensor, MagneticFieldZSensor,
    MjpegServerRunningSensor,
    PersonDetectedSensor, PersonCountSensor,
    RtspServerRunningSensor,
    DogBarkingSensor, BabyCryingSensor, ScreamingSensor, GlassBreakingSensor,
    SmokeAlarmSensor, SirenSensor, DoorbellSensor, KnockingSensor, GunshotSensor
  )

  val readSensors: List<ReadSensor> = listOf(
    WifiRssiSensor,
    BatteryTemperatureC,
    UptimeSensor,
    FreeMemorySensor,
    TotalMemorySensor,
    FreeStorageSensor,
    TotalStorageSensor,
    WifiFrequencySensor,
    WifiLinkSpeedSensor,
    BatteryVoltageSensor,
    BatteryCurrentSensor,
    BatteryPowerSensor
  )

  val readTextSensors: List<ReadTextSensor> = listOf(
    AndroidVersionSensor,
    DeviceModelSensor,
    AppVersionSensor,
    IpAddressSensor,
    WifiSsidSensor,
    WifiBssidSensor,
    ChargingSourceSensor,
    ForegroundAppSensor,
    MjpegUrlSensor,
    RtspUrlSensor
  )
  // DetectedSoundSensor isn't a ReadTextSensor — it's pushed by sound_classifier.kt whenever
  // a new classification result arrives, not polled on a timer, so it's added only here
  // (dispatch/registration) and not to readTextSensors (which diagnosticsLoop polls).
  val textSensors: List<TextSensor> = readTextSensors + listOf(DetectedSoundSensor)

  // update.* — see UpdateEntity's doc comment. Just AutoUpdateService itself: it's both the
  // Service driving the periodic check (settings, start/stop) and the one Update entity HA
  // sees, rather than a separate object for each.
  val updates: List<UpdateEntity> = listOf(AutoUpdateService)

  val services: List<Service> = listOf(
    MdnsService,
    MediaPlayerService,
    CameraService,
    SystemVolumeService,
    BluetoothCommandService,
    ScreenBrightnessService,
    ScreenOrientationService,
    MjpegServerService,
    AppLauncherService,
    PersonDetectorService,
    RtspServerService,
    SoundClassifierService,
    // NOT AutoUpdateService here — it's already in `updates` below, and toggleables (built
    // from every list in this object, including both of these) would otherwise contain it
    // twice, rendering two duplicate "AESPHome Firmware" rows (two switches, two copies of
    // its settings, two "Check for Updates Now" buttons) in MainActivity.
  )


  val buttons: List<Button> = listOf(
    IdentifyButton, ScreenWakeButton, ScreenSleepButton, CheckForUpdateButton
  )
  val switches: List<SwitchEntity> = listOf(
    BluetoothSwitch,
    KeepScreenOnSwitch,
    StartAtBootSwitch,
    BluetoothProxySwitch
  )
  val all: List<Sensor> = eventSensors + readSensors
  val toggleables: List<Toggleable> = all + textSensors + services + buttons + switches + updates
}

// Which group of the settings screen a Toggleable's row belongs to (MainActivity.kt) — purely
// a UI grouping, no effect on HA. Kept as one central id->section map here rather than a
// property on every Toggleable (which would mean touching 25+ files to add or move one entry)
// so the whole grouping is visible and reviewable in a single place. An id missing from this
// map falls back to OTHER instead of failing to compile or crashing — safe by construction,
// so a newly-added entity that's forgotten here just shows up in "Other" rather than breaking
// the screen.
enum class UiSection(val label: String) {
  SCREEN("Screen"),
  CAMERA("Camera & Streaming"),
  BLUETOOTH("Bluetooth"),
  MEDIA("Media"),
  SOUND("Sound"),
  SENSORS("Sensors"),
  DIAGNOSTICS("Diagnostics"),
  APP_CONTROL("App Control"),
  OTHER("Other"),
}

// internal (not private) so a unit test can check its coverage directly, without needing to
// construct Sensors.toggleables — which, unlike this map, pulls in real Android objects
// (e.g. TouchSensor's Handler(Looper.getMainLooper())) that a plain JVM test can't load.
internal val UI_SECTION_BY_ID: Map<String, UiSection> = buildMap {
  for (id in listOf("screen_on", "screen_brightness", "screen_orientation", "screen_wake",
      "screen_sleep", "keep_screen_on", "touch")) put(id, UiSection.SCREEN)

  for (id in listOf("camera", "camera_lux", "mjpeg_server", "mjpeg_server_running", "mjpeg_url",
      "rtsp_server", "rtsp_server_running", "rtsp_url",
      "person_detector", "person_detected", "person_count")) put(id, UiSection.CAMERA)

  for (id in listOf("bluetooth_switch", "bluetooth_commands", "bluetooth_proxy")) put(id, UiSection.BLUETOOTH)

  for (id in listOf("media_player", "system_volume")) put(id, UiSection.MEDIA)

  for (id in listOf("decibel_meter", "sound_classifier", "dog_barking", "baby_crying",
      "screaming", "glass_breaking", "smoke_alarm", "siren", "doorbell", "knocking",
      "gunshot", "detected_sound")) put(id, UiSection.SOUND)

  for (id in listOf("battery_percent", "battery_charging", "battery_temperature_c",
      "device_movement", "device_orientation", "light_sensor",
      "proximity", "pressure", "relative_humidity", "ambient_temperature", "wifi_rssi",
      "accelerometer_x", "accelerometer_y", "accelerometer_z",
      "gyroscope_x", "gyroscope_y", "gyroscope_z",
      "magnetic_field_x", "magnetic_field_y", "magnetic_field_z")) put(id, UiSection.SENSORS)

  for (id in listOf("android_version", "device_model", "app_version", "ip_address",
      "wifi_ssid", "wifi_bssid", "charging_source", "foreground_app", "uptime",
      "free_memory", "total_memory", "free_storage", "total_storage",
      "wifi_frequency", "wifi_link_speed",
      "battery_voltage", "battery_current", "battery_power")) put(id, UiSection.DIAGNOSTICS)

  for (id in listOf("mdns", "identify", "start_at_boot", "app_launcher", "auto_update", "check_for_update")) put(id, UiSection.APP_CONTROL)
}

val Toggleable.uiSection: UiSection get() = UI_SECTION_BY_ID[id] ?: UiSection.OTHER
