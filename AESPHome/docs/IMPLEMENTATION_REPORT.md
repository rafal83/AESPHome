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
- **Passive Bluetooth LE proxy**: `switch.bluetooth_proxy` — advertises
  `bluetooth_proxy_feature_flags` (`PASSIVE_SCAN | RAW_ADVERTISEMENTS`) and forwards every BLE
  advertisement Android's scanner sees as `BluetoothLERawAdvertisementsResponse` (id 93),
  matching current ESPHome firmware's wire format. See `docs/BLUETOOTH_PROXY.md` for what's
  in and out of scope.
- **MJPEG HTTP server**: `/camera.jpg` and `/camera.mjpeg`, reusing `CameraService`'s existing
  Camera2/JPEG pipeline (no second camera open) via a small broadcast-listener hook, with a
  configurable port/max-FPS and a lightweight per-device token (`?token=...`, shown in the app
  alongside the URL, toggle in-app only — deliberately not exposed as an HA entity, so no HA
  user can remotely disable the one thing gating access to the raw feed).
- **Permissions screen**: Granted/Denied/Not-supported for every permission an implemented
  feature depends on, each with its own Enable button — nothing requested in bulk on first
  launch.
- **Unit tests**: protobuf varint/message encode-decode round trips, the BLE MAC→uint64
  packing (checked against ESPHome firmware's own byte order), screen-brightness conversion,
  and MJPEG header/boundary formatting — all pure-Kotlin, no Robolectric/device needed.
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
- `app/build.gradle` — JUnit test dependency.
- New files: one per feature listed above under Implemented — see `git log` for the exact
  list; nothing outside `app/src/main/java/com/aesphome/**` and `docs/**` was touched besides
  the build/CI files below.

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
| `switch.bluetooth_proxy` | switch | Passive BLE scan → HA Bluetooth integration |
| `binary_sensor.mjpeg_server_running` | binary_sensor | MJPEG server up/down |
| `number.mjpeg_port` / `mjpeg_max_fps` | number | MJPEG server config |

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

# Remaining work

- **Active Bluetooth GATT proxy** (connections, pairing, read/write/notify) — see
  `docs/BLUETOOTH_PROXY.md` for the full breakdown of why this is out of scope for this pass
  and what implementing it would take.
- **H.264/RTSP** — not implemented; `docs/RTSP_PLAN.md` has the architecture.
- **Noise/encrypted ESPHome API transport** — audited, not implemented. The server currently
  only speaks the plaintext preamble (`0x00`); it never sends or accepts a Noise (`0x01`)
  frame. Implementing ESPHome's Noise handshake correctly means a full Noise_NNpsk0
  implementation (X25519 + ChaCha20-Poly1305 + a specific handshake pattern/transcript) —
  materially higher risk of a subtly wrong, silently-insecure implementation than every other
  change in this pass, and large enough to be its own dedicated effort with its own security
  review rather than one part of a much broader feature branch. Recommendation: adopt a
  vetted Noise library (e.g. a Java/Kotlin `Noise_NNpsk0_25519_ChaChaPoly_SHA256` implementation)
  rather than hand-rolling the cryptography, and land it as its own change with its own
  focused review.
- **Foreground service type live updates** — see the Android version limitations note above.
- **RTSP-adjacent**: nothing beyond the plan doc.
- **UI polish**: the spec's proposed section grouping (Screen/Camera/Bluetooth/Sensors/
  Android/Permissions/Diagnostics) is only partially reflected — `MainActivity` still lists
  every `Toggleable` alphabetically in one flat list (its existing, working layout), plus new
  buttons to the Permissions and Allowed-Apps screens. Reorganizing the whole settings screen
  into named sections was judged lower-value than the features themselves within this pass's
  scope, given the existing screen already groups each entity with its own settings.

# Build

```
./gradlew clean test assembleDebug
```

Result: **BUILD SUCCESSFUL**, 22 unit tests passing, `app-debug.apk` produced at
`app/build/outputs/apk/debug/app-debug.apk`.

The same command (`./gradlew clean test assembleDebug`, from the repo's `AESPHome/`
directory) is what `.github/workflows/android-build.yml` runs — verified locally against the
same Gradle wrapper the workflow uses, so a green workflow run means the same thing a local
build does.
