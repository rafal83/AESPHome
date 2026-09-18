# Implementation Report

Work done on top of the audited baseline in `docs/ARCHITECTURE.md`. Every feature below
reuses the existing `Sensor`/`Button`/`SwitchEntity`/`Service`/`Setting`/`SelectSetting`
registry (`sensors/Sensor.kt`) — `esphome.kt`'s dispatch code needed changes only for the two
genuinely new ESPHome message families (`text_sensor`, the Bluetooth LE raw-advertisement
proxy messages); every other feature is a new file plus one line in `Sensors`.

# Implemented

- **Screen controls**: `number.screen_brightness`, `select.screen_orientation` (both via
  `Settings.System` + `WRITE_SETTINGS`, with a documented app-window-only fallback),
  `button.screen_wake`, `button.screen_sleep` (Device Admin `lockNow()`, with a documented
  dim-only fallback when Device Admin isn't enabled), `switch.keep_screen_on`,
  `switch.start_at_boot` (existing `BootReceiver` is now conditional on it, default on to
  preserve the prior always-on-boot behavior).
- **Foreground service hardening**: the type passed to `startForeground()` is now computed
  from actually-granted permissions at that moment (media playback always; camera/microphone/
  connected-device only when their permission is currently granted) instead of a fixed
  manifest-only declaration — avoids the Android 14 crash of claiming a type without its
  permission. `BootReceiver` no longer starts unconditionally and is wrapped so a future OS
  restriction on background service starts can't crash the receiver.
- **Diagnostics**: `text_sensor` support added to the wire protocol (didn't exist before this
  branch) plus `missing_state` support for numeric sensors — every diagnostic sensor below
  reports "unavailable" rather than a fabricated value when the OS doesn't have the data.
  Android/app version, device model, IP, Wi-Fi SSID/BSSID, uptime, free/total memory,
  free/total storage, Wi-Fi frequency/link speed, battery voltage/current/power, charging
  source, foreground app.
- **Android sensors**: proximity, pressure, relative humidity, ambient temperature, and
  3-axis accelerometer/gyroscope/magnetic field — each hidden entirely (`isAvailable()`) on a
  device without the matching hardware, each independently rate-limited (never forwarding raw
  `SensorEvent` callbacks at their native rate).
- **App Launcher**: `select.launch_app`, restricted to an explicit per-app whitelist chosen on
  a new Allowed Apps screen — reuses `BluetoothCommandService`'s existing "command dropdown"
  pattern rather than exposing arbitrary Intents to the network.
- **Bluetooth proxy, passive AND active**: `switch.bluetooth_proxy` advertises
  `bluetooth_proxy_feature_flags` (`PASSIVE_SCAN | RAW_ADVERTISEMENTS | ACTIVE_CONNECTIONS`)
  and forwards every BLE advertisement Android's scanner sees as
  `BluetoothLERawAdvertisementsResponse` (id 93), matching current ESPHome firmware's wire
  format — **and** implements the active-connection side (`sensors/bluetooth_gatt.kt`): HA
  connecting through this device to a remote BLE peripheral (connect/disconnect, GATT service
  discovery, characteristic/descriptor read/write, notifications), each connection's GATT
  operations serialized through a FIFO queue (Android silently drops a second in-flight
  operation on the same connection otherwise). Pairing and cache-clearing are not implemented
  (explicit failure response, not a hang). See `docs/BLUETOOTH_PROXY.md`.
- **MJPEG HTTP server**: `/camera.jpg` and `/camera.mjpeg`, reusing `CameraService`'s existing
  Camera2/JPEG pipeline (no second camera open) via a small broadcast-listener hook, with a
  configurable port/max-FPS and a lightweight per-device token (`?token=...`, shown in the app
  and via `text_sensor.mjpeg_url`, toggle in-app only — deliberately not exposed as an HA
  entity, so no HA user can remotely disable the one thing gating access to the raw feed).
- **RTSP / H.264 server**: `rtsp://<ip>:8554/aesphome` — Camera2 → `MediaCodec` (hardware AVC
  encoder) → RFC 6184 RTP packetization → RTP-over-TCP interleaved → a minimal hand-rolled
  RTSP server (OPTIONS/DESCRIBE/SETUP/PLAY/TEARDOWN). **Could not be verified against a real
  player** (no camera-equipped device was available while building this) — see
  `docs/RTSP_PLAN.md`'s verification section before relying on it. Uses its own camera
  session, separate from Camera/MJPEG's (can't run both against the same lens at once).
- **Person detection**: `binary_sensor.person_detected` / `sensor.person_count` — an on-device
  TFLite object detector (Task Library + a bundled EfficientDet-Lite0 model, ~4.3MB,
  `assets/efficientdet_lite0.tflite`, CPU-only) run against whatever frame `CameraService`'s
  pipeline already produced, filtered to the "person" class. Runs on its own dedicated
  executor thread — never on `CameraService`'s capture thread — and drops (never queues) a
  frame that arrives while a previous one is still being classified. This was an explicit,
  user-chosen dependency trade-off (see the "TFLite embarqué" choice in this session) — the
  APK grows by roughly 18MB (native TFLite libraries across ABIs + the model).
- **Auto update**: `sensors/auto_update.kt` periodically checks
  `github.com/rafal83/AESPHome`'s latest release (`GET /repos/.../releases/latest`, platform
  `HttpURLConnection`/`org.json` only — no new networking/JSON dependency) and compares its tag
  against `BuildConfig.VERSION_NAME`. `update.aesphome_firmware` (a real ESPHome `update`
  entity as of v0.2.4 — see below) reports the result; its UPDATE command downloads the
  release's `.apk` asset and hands it to the system package installer via a `FileProvider`
  URI. The final install step always needs one tap on Android's own confirmation screen —
  there is no silent-install path without root/device-owner (same constraint as everything
  else in this branch), so this automates checking and downloading, not the security-gated
  last step. Adds one dependency, `androidx.core:core` (for `FileProvider` only — installing
  from a raw `file://` path is blocked by StrictMode on API 24+).
- **`esphome_version` no longer reports this app's own version**: it was pinned to
  `BuildConfig.VERSION_NAME`, which made HA's ESPHome integration compare our low version
  number (`0.1.0`) against real ESPHome releases and nag about an "update available" — for a
  device with no firmware to update. `DeviceInfoResponse.esphome_version` now reports a fixed,
  real, recent ESPHome release string (`REPORTED_ESPHOME_VERSION` in `esphome.kt`); this
  app's actual version is still visible via `text_sensor.app_version`, unchanged.
- **Permissions screen**: Granted/Denied/Not-supported for every permission an implemented
  feature depends on, each with its own Enable button — nothing requested in bulk on first
  launch.
- **Unit tests**: protobuf varint/message encode-decode round trips (including the 64-bit
  path GATT addresses need), the BLE MAC→uint64 packing (checked against ESPHome firmware's
  own byte order, cross-checked against a second upstream source file), GATT UUID short-form
  detection, screen-brightness conversion, MJPEG header/boundary formatting, and RTSP's
  Annex-B NAL splitting + RFC 6184 FU-A header byte-packing — all pure-Kotlin, no Robolectric/
  device needed.
- **CI**: `.github/workflows/android-build.yml` (push/PR/manual — test + assembleDebug +
  upload artifact) and `.github/workflows/release.yml` (`v*` tags — test + assembleRelease
  attempt, falling back to a clearly-labeled unsigned debug build, attached to a GitHub
  Release). See the CI section below for what signing requires going forward.
- **Baseline fixes** (before any feature work): committed a Gradle wrapper (none existed),
  raised the Gradle daemon heap (`packageDebug` OOMs by default while repackaging libVLC's
  native libraries), added `.gitignore`.

# Modified files

- `app/src/main/java/com/aesphome/esphome.kt` — text_sensor messages, `missing_state` for
  numeric sensors, `bluetooth_proxy_feature_flags` in `DeviceInfoResponse`, BLE
  subscribe/unsubscribe + raw-advertisement push, `hasAvailable()` filtering in the entity
  loops.
- `app/src/main/java/com/aesphome/protobuf.kt` — added a 64-bit varint field
  (`varintLong`), needed for the BLE `address` field (a MAC doesn't fit a 32-bit varint).
- `app/src/main/java/com/aesphome/sensors/Sensor.kt` — `TextSensor`/`ReadTextSensor`,
  `Toggleable.isAvailable()`, `ReadSensor.read()` made nullable, every new object registered.
- `app/src/main/java/com/aesphome/settings.kt` — generic boolean/string/string-set
  persistence (`getFlag`/`getStringFlag`/`getStringSetFlag` and setters), for entities whose
  HA-visible state isn't just "is this feature enabled."
- `app/src/main/java/com/aesphome/utils.kt` — `macStringToLong`, `hasUsageAccess`.
- `app/src/main/java/com/aesphome/MainActivity.kt` — `MainActivity.instance` (for the
  orientation/screen-sleep fallbacks), permission-aware `startForeground()`, `BootReceiver`
  gated on `StartAtBootSwitch`, Permissions/Allowed-Apps buttons, MJPEG URL status line.
- `app/src/main/java/com/aesphome/sensors/camera.kt` — frame broadcast (`addFrameListener`/
  `latestFrame`) so the MJPEG server shares the existing capture pipeline instead of opening
  the camera a second time.
- `app/src/main/java/com/aesphome/sensors/battery_temperature.kt`,
  `wifi_rssi.kt` — stopped fabricating a value (`0.0f`) when unavailable; return `null`.
- `app/src/main/AndroidManifest.xml` — new permissions (below), two new activities, the
  Device Admin receiver, a `<queries>` block for the app launcher.
- `app/build.gradle` — JUnit test dependency, `tensorflow-lite-task-vision` (person
  detection), `aaptOptions { noCompress "tflite" }`, conditional release `signingConfig`.
- New files: one per feature listed above under Implemented (`sensors/bluetooth_gatt.kt`,
  `sensors/rtsp_server.kt`, `sensors/person_detector.kt`, `sensors/stream_urls.kt`, plus every
  screen/diagnostics/sensor file from the first pass) — see `git log` for the exact list;
  nothing outside `app/src/main/java/com/aesphome/**`, `app/src/main/assets/**`, and
  `docs/**` was touched besides the build/CI files below.

# New ESPHome entities

| Entity | Type | Description |
|---|---|---|
| `number.screen_brightness` | number | 0-100%, system-wide via WRITE_SETTINGS |
| `select.screen_orientation` | select | auto/portrait/landscape/reverse_portrait/reverse_landscape |
| `button.screen_wake` | button | Timed WakeLock + best-effort foreground |
| `button.screen_sleep` | button | Device Admin lock, or app-dim fallback |
| `switch.keep_screen_on` | switch | Held screen-bright WakeLock while on |
| `switch.start_at_boot` | switch | Gates the existing BootReceiver |
| `text_sensor.android_version` | text_sensor | `Build.VERSION.RELEASE` |
| `text_sensor.device_model` | text_sensor | Manufacturer + model |
| `text_sensor.app_version` | text_sensor | `BuildConfig.VERSION_NAME` |
| `text_sensor.ip_address` | text_sensor | Wi-Fi IPv4 |
| `text_sensor.wifi_ssid` | text_sensor | Connected SSID |
| `text_sensor.wifi_bssid` | text_sensor | Connected BSSID |
| `text_sensor.charging_source` | text_sensor | AC/USB/WIRELESS/DOCK/UNKNOWN |
| `text_sensor.foreground_app` | text_sensor | Requires Usage Access |
| `sensor.uptime` | sensor | Seconds since boot |
| `sensor.free_memory` / `total_memory` | sensor | MB |
| `sensor.free_storage` / `total_storage` | sensor | MB, internal storage |
| `sensor.wifi_frequency` | sensor | MHz |
| `sensor.wifi_link_speed` | sensor | Mbps |
| `sensor.battery_voltage` / `current` / `power` | sensor | V / mA / W, where the device exposes them |
| `sensor.proximity` | sensor | cm |
| `sensor.pressure` | sensor | hPa |
| `sensor.relative_humidity` | sensor | % |
| `sensor.ambient_temperature` | sensor | °C |
| `sensor.accelerometer_x/y/z` | sensor | m/s² |
| `sensor.gyroscope_x/y/z` | sensor | rad/s |
| `sensor.magnetic_field_x/y/z` | sensor | µT |
| `select.launch_app` | select | Whitelisted apps only |
| `switch.bluetooth_proxy` | switch | Passive scan + active GATT connections → HA Bluetooth integration |
| `binary_sensor.mjpeg_server_running` | binary_sensor | MJPEG server up/down |
| `number.mjpeg_port` / `mjpeg_max_fps` | number | MJPEG server config |
| `text_sensor.mjpeg_url` | text_sensor | Ready-to-use MJPEG URL |
| `binary_sensor.rtsp_server_running` | binary_sensor | RTSP server up/down |
| `number.rtsp_port` / `rtsp_bitrate_kbps` | number | RTSP server config |
| `select.rtsp_resolution` | select | 640x480 / 1280x720 |
| `text_sensor.rtsp_url` | text_sensor | Ready-to-use RTSP URL |
| `binary_sensor.person_detected` | binary_sensor | On-device TFLite detection, "person" class |
| `sensor.person_count` | sensor | Count of "person" detections in the last inference |
| `update.aesphome_firmware` | update | Check/Install buttons, real progress bar during download |

`binary_sensor.screen_on` and `binary_sensor.charging` (as `battery_charging`) already
existed before this branch and are unchanged.

# Android permissions

| Permission | Why |
|---|---|
| `WRITE_SETTINGS` | System-wide brightness/orientation control |
| `WAKE_LOCK` | `button.screen_wake`'s timed WakeLock, `switch.keep_screen_on`'s held one |
| `BLUETOOTH_SCAN` (`neverForLocation`) / `ACCESS_FINE_LOCATION` (≤ API 30) | Passive BLE proxy scanning |
| `PACKAGE_USAGE_STATS` | `text_sensor.foreground_app` |
| `FOREGROUND_SERVICE_CAMERA` / `_MICROPHONE` / `_CONNECTED_DEVICE` | Required alongside the existing `FOREGROUND_SERVICE_MEDIA_PLAYBACK` now that the service's declared type set covers what it actually does |
| `REQUEST_INSTALL_PACKAGES` | `update.aesphome_firmware`'s Install command launching the system package installer |

The active Bluetooth GATT proxy and the RTSP server add no *new* permissions — `BLUETOOTH_CONNECT`
was already required unconditionally (`bluetooth_switch.kt`), and RTSP reuses the `CAMERA`
permission Camera/MJPEG already require.

Device Admin is *not* a manifest `<uses-permission>` — it's the `AESPHomeDeviceAdminReceiver`
`<receiver>` (`BIND_DEVICE_ADMIN`), activated per-device from the Permissions screen, never
requested automatically.

# Android version limitations

- **Foreground service types (Android 10/29+, enforced harder on 14/34)**: the type passed to
  `startForeground()` is computed from currently-granted permissions, so it can lag reality —
  granting Camera permission while the service is already running doesn't retroactively add
  the `camera` type to the *already-started* foreground service; it takes effect on the next
  service (re)start. Not fixed in this pass — would need `startForeground()` to be re-called
  when a relevant permission changes.
- **`WRITE_SETTINGS` / adaptive brightness**: writing `Settings.System.SCREEN_BRIGHTNESS` is
  immediately overridden if the device's adaptive/auto-brightness is on — this app does not
  force it off, since doing so is itself an intrusive, separate system setting.
- **BLE address type (Android < 13/14)**: `BluetoothLERawAdvertisement.address_type` is always
  sent as `0` (public) — the public SDK has no reliable pre-33 API to read the real address
  type from a `ScanResult`. Immaterial for passive-only proxying (only matters for connecting
  *to* the device, which this proxy doesn't do — see `docs/BLUETOOTH_PROXY.md`).
- **Wi-Fi BSSID without location permission**: Android returns a fixed placeholder
  (`02:00:00:00:00:00`) for `WifiInfo.bssid` without `ACCESS_FINE_LOCATION` — treated as
  unavailable (`text_sensor.wifi_bssid` reports `missing_state`) rather than as a real value.
- **`BATTERY_PROPERTY_CURRENT_NOW`**: not implemented on every OEM/Android version — returns
  `Int.MIN_VALUE` or `0` when unsupported; both are treated as unavailable.
- **Device Admin `lockNow()`**: works unchanged back to `minSdk` 22; no version-specific gap
  found in testing against the documented behavior.
- **Battery `DOCK` source** (`BatteryManager.BATTERY_PLUGGED_DOCK`, value `8`): only defined
  from API 33; the raw int value is used directly (rather than the named constant) so the
  check still compiles and behaves correctly on `minSdk` 22 — it simply never matches on
  older OSes, which is correct (no such source exists there).

# Settings screen reorganization (v0.2.3)

`MainActivity` previously listed all 70+ `Toggleable`s alphabetically in one flat list — not
scalable once the branch's feature count grew this much. Added `UiSection` (`sensors/Sensor.kt`):
an enum (Screen, Camera & Streaming, Bluetooth, Media, Sensors, Diagnostics, App Control,
Other) plus one central `id -> UiSection` map, rather than a property on every `Toggleable`
(which would mean touching 25+ files to add or move one entry). An id missing from the map
falls back to `Other` instead of failing to compile — `UiSectionTest.kt` pins down that every
currently-registered id is actually mapped, so nothing silently lands in the fallback.
`MainActivity` now renders one bold header + a heavier divider per section, sorted internally
by label exactly as before.

# Real `update` entity, RTSP fixes, matched camera resolution (v0.2.4)

- **Real ESPHome `update` entity** (ids 116/117/118): `update.aesphome_firmware` replaces the
  previous `binary_sensor.update_available` + `text_sensor.latest_available_version` +
  two buttons — the same "Update available" card with Check/Install buttons a real ESPHome
  device's own OTA flow shows in Home Assistant, instead of a hand-rolled approximation of
  one. `AutoUpdateService` now implements both `Service` (background timer) and the new
  `UpdateEntity` interface (`Sensor.kt`). Install progress is reported in real time via the
  protocol's `progress`/`has_progress` fields while the APK downloads.
- **RTSP: per-session writer thread**, fixing a real bug found by real-device testing — a
  stream that played fine then went silent after a Wi-Fi hiccup. Java's `Socket` has a read
  timeout but no write-timeout equivalent; the previous code wrote RTP packets directly from
  the single shared encoder drain thread, so one client's TCP send buffer filling (client not
  reading fast enough, or a brief network stall) could block that write indefinitely —
  freezing frame delivery to every session, forever, with no recovery. Each `RtspSession` now
  owns a small bounded queue (latest-frame-wins, matching the "keep only the newest" queue
  pattern already used for JPEG frames elsewhere in this codebase) and its own writer thread;
  `sendAccessUnit()` (called from the shared drain thread) only ever enqueues, never blocks.
- **RTSP now matches Camera's own resolution** — it previously had its own independent
  640x480/1280x720 choice; `CameraService.selectedResolution()` is now the single source of
  truth for "what resolution is this device's camera," used by RTSP the same way MJPEG/HA's
  own camera entity already did via the shared JPEG pipeline.
- The initial "no data received in 10s, Switching to TCP" a real-device test showed in VLC's
  log is understood to be VLC/live555's own client-side behavior (it attempts UDP internally
  regardless of the SETUP response, self-correcting via an internal watchdog) rather than a
  server-side bug — not changed, since there's nothing on this server's side to change about
  another program's transport-negotiation default.

# RTSP socket write race, Device Owner silent updates, Material redesign (v0.2.5 - v0.2.6)

- **RTSP: fixed the actual root cause of the "plays for a few seconds then dies" bug** that
  survived v0.2.4's writer-thread fix. Root cause: the `PLAY` handler flipped `playing = true`
  *before* sending `PLAY`'s own response, and RTSP control-response writes (from the socket's
  read/handle thread) shared no lock with RTP data writes (from the per-session writer thread).
  The writer thread could therefore send binary interleaved RTP bytes to the client before it
  had even received `RTSP/1.0 200 OK` for `PLAY`, permanently desyncing the client's
  interleaved-frame parser right at the point `PLAY` completes — deterministic, and matching
  the reported symptom exactly. Fixed with a `socketWriteLock` (`RtspSession`) shared by every
  write to that session's socket (`respond()`, `respondWithBody()`, `respondError()`,
  `writeRawRtp()`), and by reordering `PLAY` to send its response before flipping
  `playing = true`. See `docs/RTSP_PLAN.md` for the full writeup.
- **Device Owner silent updates**: `AutoUpdateService.downloadAndInstall()` now checks
  `isDeviceOwner()` (`utils.kt`) and, when true, commits the downloaded APK through a
  `PackageInstaller.Session` with `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` (API 31+) —
  a zero-tap install, handled by the new `AESPHomeUpdateInstallReceiver`. Device Owner has no
  in-app grant flow (it's an adb-only, largely irreversible device commitment); the Permissions
  screen shows its status as informational only, pointing to `FAQ.md` for the `adb shell dpm
  set-device-owner` command. Without it, the existing tap-to-confirm `ACTION_VIEW` install flow
  is unchanged.
- **Material Components UI redesign**: `Theme.AESPHome` (`Theme.Material3.DayNight` +
  `colors.xml`) replaces the default platform theme; `PermissionsActivity`, `MainActivity`, and
  `AppLauncherSettingsActivity` were rewritten with `MaterialButton`, `MaterialSwitch`,
  `MaterialCardView` (one per `UiSection` in `MainActivity`, replacing the old manually-drawn
  section dividers), `MaterialDivider`, `MaterialCheckBox`, and `TextInputLayout` +
  `TextInputEditText` (floating-label numeric settings, replacing bare `EditText`). Pinned to
  `com.google.android.material:material:1.12.0` rather than the current 1.14.0, since 1.13+
  pulls in an `androidx.core` version that requires `compileSdk` 35 / AGP 8.6+ — a separate,
  larger change not made in this pass. `SelectSetting`s still use the plain `Spinner` (it
  already re-skins correctly under `Theme.Material3.DayNight`) rather than an exposed dropdown
  menu, to keep this pass low-risk.

# Hardening pass: Noise encryption, BLE reliability, update/release security, MJPEG/RTSP auth

A deliberately non-feature-adding pass — the goal was hardening what already existed, not
growing the feature count further. Full detail (including exactly what was and wasn't
verified for the security-sensitive pieces) is in `docs/SECURITY.md`; this section is the
condensed version in the report structure this file already uses.

## Security

- **Noise encryption for the ESPHome API transport** (`noise.kt`) — opt-in, off by default,
  plaintext untouched. See `docs/SECURITY.md` for the full writeup, including exactly what
  was checked against ESPHome's own source vs. a library-naming quirk that turned out not to
  be a protocol difference, and a real handshake round-trip test
  (`NoiseHandshakeRoundTripTest.kt`) against a second, independent client-role
  `HandshakeState` over a loopback socket.
- **Auto-update asset safety**: `isAcceptableUpdateApkName()` rejects any `unsigned`/`debug`
  release asset unless this app's own build is itself a debug build; a SHA-256 checksum
  (published by `release.yml`, downloaded and verified before install) is now mandatory for
  any release that has one; an incomplete download (byte count vs. `Content-Length`) is
  rejected rather than installed short.
- **Release signing** (`release.yml`): fails if the tag doesn't match `versionName`; verifies
  the built APK's signature with `apksigner` when available; computes and publishes a SHA-256
  checksum next to every release APK; deletes the decoded keystore file unconditionally at
  the end of the job; marks an unsigned build's release as a prerelease with an explicit
  warning instead of looking like a normal release; no longer cancels an in-progress release
  run if a second tag is pushed.
- **MJPEG/RTSP auth**: MJPEG's existing token check switched to `MessageDigest.isEqual`
  (was a timing-observable `!=`) and gained `Authorization: Bearer` support alongside the URL
  token. RTSP gained optional Basic auth (credentials embedded in the shown URL, matching the
  ffmpeg/VLC/go2rtc convention) — previously had none at all.

## BLE

- **Per-operation timeout**: every GATT operation (read/write/notify-enable/`requestMtu`) now
  goes through `GattOpQueue` (`gatt_op_queue.kt`), a generic, unit-tested (no Robolectric
  needed — a fake, manually-advanced clock stands in for a real `Handler`) serial queue with a
  10s timeout per operation. Android can accept an operation and then never call its
  callback at all; previously that blocked every later operation on the same connection
  forever. A callback that finally arrives after its op already timed out is now recognized
  as stale and ignored, instead of being mistaken for completing whatever op is current by
  then.
- **Real MTU negotiation**: `requestMtu(517)` runs through the same queue/timeout right after
  connecting; success, failure, no callback at all, and a peripheral that doesn't support
  `requestMtu()` all fall back to the default 23-byte MTU rather than blocking or failing the
  connection. `BluetoothDeviceConnectionResponse` is now sent once this settles (with
  whatever MTU actually applies), not immediately on raw connect with a placeholder value.

## Camera

- **Audited, not refactored into a shared pipeline**: confirmed (matching the code's own
  existing documentation) that ESPHome Camera/MJPEG/person-detection share one Camera2
  session (`CameraService`'s `ImageReader`), while RTSP opens a fully independent one
  (`MediaCodec` encoder `Surface`) — see `docs/SECURITY.md` for why merging them wasn't
  attempted this pass. Added an explicit "camera multi-output unsupported, fallback active"
  log line when the two collide (`ERROR_CAMERA_IN_USE`), and made `stopEncoder()`
  `@Synchronized` against the several different callback threads that can call it on a
  failure path.
- **MJPEG hardening**: the accept loop's one-`Thread()`-per-client became a bounded
  `ThreadPoolExecutor` (`MAX_CONCURRENT_CLIENTS = 8`, `SynchronousQueue` — no queueing, an
  excess client gets an immediate 503 rather than an accepted-but-never-served socket).
  Stream-viewer count transitions (first connects / last disconnects) are now logged.
- **RTSP hardening**: fixed a real bug — `respondSetup()`'s socket write was missing the
  `socketWriteLock` every other response method already used, the exact class of race that
  lock exists to prevent. Added a bounded session count (`MAX_CONCURRENT_RTSP_SESSIONS = 4`)
  and a read timeout that applies only before `PLAY` (a read timing out during an active
  stream is normal — the control channel goes quiet for the whole stream — so it must not
  tear down a healthy connection).

## CI/CD

- `release.yml`: tag/versionName consistency check, `apksigner verify`, SHA-256 checksum
  generation and publishing, keystore cleanup, unsigned-build prerelease marking,
  `cancel-in-progress: false`. See Security above and `docs/SECURITY.md`.
- `android-build.yml`: unchanged — already had the CI essentials (test + `assembleDebug` +
  artifact upload, `concurrency` with `cancel-in-progress: true`, which is fine there since
  cancelling an in-progress *test* run has no partial-publish consequence).

## Tests

47 tests before this pass, 71 after — all still plain Kotlin/JVM, no Robolectric:
`GattOpQueueTest` (6, including "callback after timeout is ignored, not misapplied to a
different op"), `AutoUpdateSafetyTest` (7, asset-name safety + checksum parsing),
`NoiseFramingTest` (7, hand-computed wire-layout bytes), `NoiseHandshakeRoundTripTest` (2, a
real handshake + a wrong-PSK rejection, both over a real loopback socket), plus one new case
each in `AutoUpdateVersionTest` (double-digit SemVer comparison) and `MjpegHeadersTest`
(`tokenMatches`).

## Build

```
./gradlew clean test assembleDebug
```

Result: **BUILD SUCCESSFUL**, all 71 unit tests passing, debug APK produced. Release build
(`./gradlew assembleRelease`) also verified, including the new `apksigner verify` step in CI
against a real signed output — see the release.yml changes above.

## APK

Signed release APKs are published as GitHub Release assets on
`github.com/rafal83/AESPHome/releases`, named `AESPHome-<version>-release.apk` with a
matching `AESPHome-<version>-release.apk.sha256` alongside it as of this pass. The debug
build's APK path is unchanged: `app/build/outputs/apk/debug/app-debug.apk`.

# Post-release fixes from real-device testing (v0.2.2)

v0.2.1 was the first build actually installed on a device. It surfaced four issues, all
fixed here:

- **RTSP didn't work at all (MJPEG did)**: a real control-flow bug, not an unverified
  assumption — `DESCRIBE` needed SPS/PPS that only existed after `PLAY` started the encoder,
  but `DESCRIBE` always arrives *before* `PLAY`. Every session failed at the first step. Fixed
  in `rtsp_server.kt`: `DESCRIBE` now starts the encoder itself and polls (up to 4s) for
  SPS/PPS before responding. See `docs/RTSP_PLAN.md`.
- **`button.screen_wake` pulled AESPHome to the foreground**: intentional in the original
  design (matching the initial request to bring the app forward), but real usage showed it's
  unwanted — a "wake screen" action shouldn't steal focus from whatever the user was doing,
  the same way pressing a phone's power button doesn't launch anything. Removed the
  `startActivity()` call; the button now only wakes the screen.
- **`binary_sensor.screen_touch` never activated**: not a bug in the sensor itself —
  `TouchAccessibilityService` requires a manual grant in Android's Accessibility settings that
  had no discoverable path in this app (the Permissions screen didn't have a row for it).
  Added one (`isAccessibilityServiceEnabled()` in `utils.kt`, checked against
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`).
- **Allowed Apps list was missing some real apps**: `PackageManager.queryIntentActivities(...,
  MATCH_DEFAULT_ONLY)` excludes any launcher activity that doesn't also declare the `DEFAULT`
  category, which some real apps' launcher activities don't. Switched to `LauncherApps`
  (`AppLauncherSettingsActivity.kt`) — the API real launcher apps use for exactly this.

# Remaining work

As of the hardening pass below (Noise encryption, BLE GATT timeout/MTU, auto-update/release
signing hardening, MJPEG/RTSP hardening+auth) this list has shrunk considerably — see that
section for what moved from here to "done." What's left:

- **Bluetooth GATT: pairing, cache clearing, connection-parameter negotiation** — see
  `docs/BLUETOOTH_PROXY.md`'s "What's NOT implemented" section. Connect/discover/read/write/
  notify, MTU negotiation, and per-operation timeouts all work now; these remaining three are
  the parts tied to security material or fine connection tuning that don't change whether a
  basic GATT session works.
- **RTSP and Noise: real-device/real-client verification** — both compile, both pass their
  own unit/round-trip tests, but neither was exercised against the actual reference client it
  matters most against (a real player for RTSP, a real Home Assistant instance for Noise) —
  no camera-equipped device or live HA instance was available while building either. See
  `docs/RTSP_PLAN.md` and `docs/SECURITY.md` for exactly what was and wasn't verified for each.
- **Person detection: only "person" from a general 91-class COCO model** — no dedicated
  face/pose model, no per-region-of-interest configuration, no drawing of bounding boxes back
  onto the MJPEG/RTSP stream (the detection result is a plain HA sensor, not an overlay).
- **Per-ABI split APKs** — audited, not built; see `docs/SECURITY.md`'s "what was explicitly
  not attempted" section for why (P2 priority, and the feature's own spec explicitly permits
  keeping a single universal APK if per-ABI auto-update selection would add too much
  complexity for the benefit).
- **A unified CameraPipeline abstraction across ESPHome Camera/MJPEG/RTSP/TFLite** — audited,
  not implemented; see `docs/SECURITY.md` for the reasoning (merging two independent,
  already-working Camera2 sessions was judged a bigger risk than the status quo's documented,
  gracefully-handled exclusivity).
- **Foreground service type live updates** — see the Android version limitations note above.

# Build

```
./gradlew clean test assembleDebug
```

Result: **BUILD SUCCESSFUL**, 44 unit tests passing, `app-debug.apk` produced at
`app/build/outputs/apk/debug/app-debug.apk` (~112MB — up from ~94MB after this branch's
baseline audit; the growth is almost entirely the TFLite native libraries + bundled model for
person detection, a user-chosen trade-off — see Implemented above).

The same command (`./gradlew clean test assembleDebug`, from the repo's `AESPHome/`
directory) is what `.github/workflows/android-build.yml` runs — verified locally against the
same Gradle wrapper the workflow uses, so a green workflow run means the same thing a local
build does.
