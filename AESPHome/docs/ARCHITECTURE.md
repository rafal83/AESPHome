# AESPHome — Architecture Audit

This document is the mandatory pre-implementation audit of the AESPHome repository
(`https://github.com/ChuckMash/AESPHome`), written before any feature work began. It
describes the state of the project as cloned, so later changes can be measured against it.

## 1. What the app is

A single-module Android app (`com.aesphome`) that runs a **hand-rolled, plaintext
ESPHome Native API server** inside a foreground `Service`. Home Assistant's official
ESPHome integration connects to it exactly as it would to a real ESP32/ESP8266 running
ESPHome firmware:

```
Android device  →  ESPHome Native API (TCP 6053, plaintext protobuf)  →  Home Assistant
```

There is no ESPHome YAML, no microcontroller, and no generated protobuf code — the wire
protocol is implemented by hand in `protobuf.kt` (a minimal encoder/decoder) and
`esphome.kt` (message framing, dispatch, and every entity's wire representation).

## 2. Toolchain (as cloned)

| | Value |
|---|---|
| AGP | 8.5.0 |
| Kotlin plugin | 1.9.24 |
| Gradle | none committed — **no `gradlew` in the repo** |
| compileSdk / targetSdk | 34 |
| minSdk | 22 |
| Java/Kotlin target | 17 |
| Module name | `app` (root project `AESPHome`) |
| Dependencies | `org.videolan.android:libvlc-all:3.7.0` only |

The repo shipped without a Gradle wrapper, so it could not be built without a
pre-existing global Gradle matching the AGP requirement. A wrapper (Gradle 8.13) was
generated as the first, non-functional change (see Modified files in
`docs/IMPLEMENTATION_REPORT.md`). `packageDebug` also OOMs on the default Gradle daemon
heap while compressing `libvlc-all`'s bundled native libraries for every ABI — fixed via
`org.gradle.jvmargs=-Xmx4g` in `gradle.properties`. Baseline build (before any feature
work) was confirmed with `./gradlew assembleDebug` → `app-debug.apk` (~94 MB, dominated by
libVLC's multi-ABI native libraries).

There was also no `.gitignore` — added to keep `local.properties`, `build/`, and `.gradle/`
out of version control.

## 3. Source layout

```
app/src/main/java/com/aesphome/
  MainActivity.kt        Activity (settings UI) + AESPHomeService (foreground Service) + BootReceiver
  esphome.kt              AESPHome class: TCP server, message framing, entity wire encoding/dispatch
  protobuf.kt             Hand-written protobuf varint/length-delimited encoder + generic field decoder
  settings.kt             SharedPreferences-backed persistence for every Toggleable/Setting/SelectSetting
  utils.kt                Device identity helpers (hostname, MAC-from-Android-ID, Wi-Fi IP)
  sensors/Sensor.kt        The entity/registry model (see §4) — everything else plugs into this
  sensors/*.kt             One file per feature: battery, camera, bluetooth, media player, screen state,
                           touch, movement, orientation, light, decibel meter, wifi RSSI, mDNS, system volume,
                           identify button
```

No XML layouts exist anywhere in the app (only launcher icon mipmaps and one
accessibility-service XML). `MainActivity`'s entire settings screen is built
programmatically from the entity registry described below.

## 4. The entity/registry architecture (why this app is easy to extend)

Everything Home Assistant can see is modeled through a small set of interfaces in
`sensors/Sensor.kt`:

- **`Toggleable`** — base contract shared by everything: `id`, `label`, `icon`,
  `entityCategory`, an app-level enable flag, and optional `settings`/`selectSettings`.
- **`Sensor`** (`Toggleable` + `key` + `kind()`) — a HA `sensor`/`binary_sensor`. Read either
  by OS event (`EventSensor`, `Startable`) or by poll (`ReadSensor`, called every 60s from
  `AESPHome.diagnosticsLoop()`).
- **`Button`** — press-only HA `button`.
- **`SwitchEntity`** (`Toggleable` + `Startable`) — a genuine two-way HA `switch`: HA can
  flip it (`setOn`), and it can also report a state change that happened on the device on
  its own (e.g. Bluetooth toggled from Android's quick settings).
- **`Service`** (`Toggleable` + `Startable`) — a background feature with **no** HA entity of
  its own (mDNS, the media player's playback engine, the camera's capture loop).
- **`Setting`** / **`SelectSetting`** — a HA `number`/`select` entity that any
  `Toggleable` can own, persisted generically by id in `settings.kt`. `deviceUi` and
  `homeAssistant` are independent flags, so the same setting can appear in the app only, in
  HA only, both, or neither.

All of these are collected in `object Sensors` (`eventSensors`, `readSensors`, `services`,
`buttons`, `switches`, plus the derived `all`/`toggleables`). **Nothing else in the app
names a specific sensor.** `MainActivity` builds its whole settings screen by iterating
`Sensors.toggleables`; `AESPHome`'s `ListEntitiesRequest`/`SubscribeStatesRequest` handlers
iterate the same lists to decide what to advertise/report. Adding a new entity of an
already-supported kind (`Sensor`, `Button`, `SwitchEntity`, `Service`, or a `Setting`/
`SelectSetting` on an existing one) requires **no changes to `esphome.kt` or
`MainActivity.kt`** — only a new object added to one of the lists in `Sensors`.

This is why the feature work in this branch stays additive: every new capability that fits
an existing entity kind is a new file plus one line in `Sensors`, not a change to the
dispatch code.

## 5. ESPHome Native API — what was already implemented

`esphome.kt` implements the plaintext framing (`[0x00][varint length][varint msg type][payload]`,
matching `aioesphomeapi`'s plaintext transport) and these message types, confirmed against
upstream ESPHome's `api.proto` (message ids match exactly):

| Message | Id | Direction | Status |
|---|---|---|---|
| Hello | 1/2 | both | done |
| DeviceInfo | 9/10 | both | done |
| ListEntities* / Done | 11/…/19 | both | done for binary_sensor(12), sensor(16), switch(17), number(49), select(52), button(61), camera(43), media_player(63) |
| SubscribeStates / State | 20/64 | both | done |
| Ping | 7/8 | both | done |
| Disconnect | 5/6 | both | done (also used unprompted, to force HA to re-run discovery after a toggle) |
| BinarySensorState | 21 | server | done |
| SensorState | 25 | server | done |
| SwitchState / Command | 26/33 | both | done |
| CameraImage / Request | 44/45 | both | done (chunked, 65000B chunks, single + continuous stream) |
| Number State / Command | 50/51 | both | done |
| Select State / Command | 53/54 | both (incl. one-shot "command dropdown" variant for Bluetooth connect/disconnect) | done |
| Button Command | 62 | client | done |
| MediaPlayer List/State/Command | 63/64/65 | both | done (libVLC-backed, play/pause/stop/volume/mute/announcement-ducking) |

**Not implemented before this branch:** `text_sensor` (id 18/27), `climate`, `cover`,
`fan`, `light`, `lock`, `siren`, any Bluetooth proxy message, and **Noise/encrypted
transport** — the server only speaks the plaintext preamble (`0x00`); it does not
implement the `0x01` Noise frame type at all, so it is unauthenticated except for the
HA-integration's "confirm at pairing time" plaintext-server warning. See the security
section of `docs/IMPLEMENTATION_REPORT.md` for how this branch addresses (and does not
fully address) that.

## 6. Feature areas relevant to this task, as found

- **Foreground service**: `AESPHomeService` (`MainActivity.kt`). Starts every enabled
  `Toggleable` on a background thread, runs `AESPHome.start()` (blocking accept loop) on
  its own thread. Manifest declares `foregroundServiceType="mediaPlayback"` unconditionally
  — not scoped to what's actually enabled (camera/microphone/Bluetooth use nothing more
  specific). `BootReceiver` already exists and unconditionally calls
  `startForegroundService` on `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` — there was no user
  control over this before this branch.
- **Camera**: `sensors/camera.kt`. Camera2-based (not CameraX, deliberately — needed for the
  hardware JPEG `ImageReader`). Owns lens/rotation/resolution/effect/JPEG-quality settings,
  an idle single-shot loop, and a stream state machine shared between HA's camera entity and
  (after this branch) the new MJPEG server. No change was made to how frames are captured —
  new consumers subscribe to the same pipeline rather than opening the camera a second time.
- **Media player**: `sensors/media_player.kt`. libVLC-backed, already full-featured
  (play/pause/stop/volume/mute/announcement ducking with resume).
- **Bluetooth**: `sensors/bluetooth_switch.kt` (radio on/off) and
  `sensors/bluetooth_commands.kt` (connect/disconnect a paired A2DP/HSP device via a select
  "command dropdown"). **No scanning, no BLE proxy** of any kind existed before this branch.
- **Android sensors already exposed**: battery percent/charging/temperature, Wi-Fi RSSI,
  ambient light (twice — a real light sensor and a camera-metadata lux estimate), device
  movement (linear-acceleration threshold), device/screen orientation, screen on/off, screen
  touch (via an `AccessibilityService`), decibel meter (`AudioRecord` burst sampling), system
  volume.
- **Persisted configuration**: flat `SharedPreferences` (`aesphome_settings`), generic by
  `id` for enable-flags, `Setting` floats, and `SelectSetting` strings (`settings.kt`).
  There was no generic boolean-flag store independent of the enable-flag before this
  branch — needed for switches whose HA-visible on/off state isn't just "is this feature
  enabled" (keep-screen-on, start-at-boot, the Bluetooth proxy's scan state).
- **Permissions**: declared eagerly in the manifest (`CAMERA`, `RECORD_AUDIO`,
  `BLUETOOTH_CONNECT`, …) and checked ad-hoc at the point of use (e.g. `CameraService`,
  `DecibelMeterSensor`). There was no screen showing their status, and no `WRITE_SETTINGS`,
  `BLUETOOTH_SCAN`, or device-admin handling at all.

## 7. What this audit changed before any feature code

1. Generated and committed the Gradle wrapper (8.13) — the project could not be built at
   all without it.
2. Raised the Gradle daemon heap (`org.gradle.jvmargs=-Xmx4g`) — `packageDebug` OOMs on
   default settings while repackaging libVLC's native libraries; unrelated to any app code.
3. Added `.gitignore`.
4. Confirmed `./gradlew assembleDebug` succeeds on the unmodified source (baseline
   `app-debug.apk` built and inspected before any feature commit).

No application source file was modified in this step. Feature work starts from this
verified-buildable baseline.
