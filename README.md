
# ÆSPHome
<img width="438" height="320" alt="Main Image" src="https://github.com/user-attachments/assets/4022084f-d793-497c-9eac-ec22fc399b92" />

[![Android Build](https://github.com/rafal83/AESPHome/actions/workflows/android-build.yml/badge.svg)](https://github.com/rafal83/AESPHome/actions/workflows/android-build.yml)

 
 Android Simulating ESPHome Device for use with Home Assistant
 
---
Work in Progress
---
Featuring 
---
* Media Player
* Bluetooth Speaker
* Camera
* Other Stuff!

Does it work?
---
Yes. sorta.

Is it ESPHome?
---
No. sorta.

What is it?
---
It's an Android app that looks to Home Assistant like an ESPHome device.

Do I need to install anything?
---
Just the app on an old Android device.
Home Assistant detects it automatically through the ESPHome integration.

You do not need to install anything to Home Assistant.

What about...
---
[FAQ](FAQ.md)

---

Features
---
* Controls
  * Enable / Disable Bluetooth
    * May required "Nearby Devices" / Bluetooth permissions
  * Connect / Disconnect to paired Bluetooth Speakers
    * Trigger a connection or disconnection from a known Bluetooth Speaker
    * May required "Nearby Devices" / Bluetooth permissions
  * Media Player
    * Backed by VLC library
    * Audio Only at the moment
  * System Volume
    * Adjust the system volume
 * Sensors
   * Ambient Noise in dB
     * Estimate ambient sound levels with microphone
     * Requires Microphone Permissions
   * Camera
      * Stills
      * Streaming Video
      * Requires Camera Permissions
   * Device Movement
      * Is the device at rest, or moving
   * Device Orientation
      * Is the device oriented at 0°, 90°, 180°, 270°
   * LUX (Camera)
       * LUX estimated from camera still shots
   * LUX (Sensor)
      * LUX reported from devices light sensor (if exists)
   * Screen On
     * Is the screen currently on or off
   * Screen Touch
     * Is the screen being used at this moment.
     * Requires Accessibility Service enabled

 * Configuration 
   * Camera resolution (per lens)
   * Camera rotation (per lens)
   * Camera Effect
     * Effects supported by the camera platform. E.g. Mono / Negative / Solarize / etc
   * Camera JPEG Quality
     * A 1-100 sliding scale of quality. 1 is lowest. 100 is highest.
   * Camera idle update
     * How often selected camera lens should send a still
   * Camera Lens
     * Select which camera lens should be considered this devices Camera at this time
   * LUX Sensor Report Interval
     * How often to send an idle LUX sensor update
   * LUX Sensor Report Threshold
     * How large of a LUX change should be reported immediately outside of the the report interval
   * Movement Reset Time
     * The time it take to reset after "Device Movement" is triggered
   * Movement Sensitivity
     * How sensitive the "Device Movement" sensor is, lower is more sensitive. Down to 0.01
   * Screen Touch Reset Time
     * The time it takes to reset after "Screen Touch" is triggered

 * Diagnostic
   * Battery Charging
     * Is the device charging
   * Battery Percent
     * The percent of battery charged
   * Battery Temperature
     * The temperature of the battery
   * Identify
     * When pressed will trigger a short audible "beep beep" from the device
   * WiFi RSSI
     * That thing you leave disabled

---

New Features
---

| Feature | Android requirement | Home Assistant entity |
|---|---|---|
| Screen brightness | "Modify system settings" permission | `number.screen_brightness` |
| Screen orientation lock | "Modify system settings" permission | `select.screen_orientation` |
| Wake screen | — (WakeLock) | `button.screen_wake` |
| Sleep/lock screen | Device Admin (real lock) or none (dim-only fallback) | `button.screen_sleep` |
| Keep screen on | — (WakeLock) | `switch.keep_screen_on` |
| Start at boot | `RECEIVE_BOOT_COMPLETED` (already required) | `switch.start_at_boot` |
| Android/app version, model, IP, Wi-Fi SSID/BSSID, charging source | — | `text_sensor.*` |
| Foreground app | Usage Access permission | `text_sensor.foreground_app` |
| Uptime, memory, storage, Wi-Fi frequency/link speed, battery voltage/current/power | — (device-dependent) | `sensor.*` |
| Proximity, pressure, humidity, ambient temperature, accelerometer/gyroscope/magnetic field (x/y/z) | Matching hardware sensor (entity hidden if absent) | `sensor.*` |
| App launcher | — (whitelist chosen in-app) | `select.launch_app` |
| Bluetooth LE proxy (passive scan + active GATT connections) | `BLUETOOTH_SCAN` (12+) / location (≤11) + `BLUETOOTH_CONNECT` (already required) | `switch.bluetooth_proxy` |
| MJPEG camera server | Camera enabled | `binary_sensor.mjpeg_server_running`, `number.mjpeg_port`, `number.mjpeg_max_fps`, `text_sensor.mjpeg_url` |
| RTSP / H.264 server ⚠️ not verified on real hardware | Camera permission | `binary_sensor.rtsp_server_running`, `number.rtsp_port`, `number.rtsp_bitrate_kbps`, `select.rtsp_resolution`, `text_sensor.rtsp_url` |
| Person detection (on-device TFLite) | Camera enabled | `binary_sensor.person_detected`, `sensor.person_count` |
| Auto update check | `REQUEST_INSTALL_PACKAGES` (install step only) | `binary_sensor.update_available`, `text_sensor.latest_available_version`, `button.check_for_update`, `button.install_update` |

See `AESPHome/docs/IMPLEMENTATION_REPORT.md` for the full entity list and exactly what
permission each feature needs and why.

### Screen controls

`number.screen_brightness` and `select.screen_orientation` control the device system-wide
once "Modify system settings" is granted from the in-app **Permissions** screen — without it,
they only affect this app's own window while it's visible. `button.screen_sleep` performs a
real screen lock once **Device Admin** is enabled (also from the Permissions screen); without
it, the button only dims the app's own window and says so in its Home Assistant description.

### Permissions

The app never requests every permission on first launch. Open **Permissions** from the main
screen to see Granted/Denied/Not-supported for each one an enabled feature needs, with an
Enable button that opens the right Android settings screen.

### Bluetooth Proxy

Enabling `switch.bluetooth_proxy` makes the device show up to Home Assistant's own Bluetooth
integration as a full Bluetooth Proxy: passive advertisement scanning (most sensors/trackers)
**and** active GATT connections (HA connecting through this device to read/write/subscribe to
a BLE peripheral's characteristics). Pairing and cache-clearing aren't implemented — see
`AESPHome/docs/BLUETOOTH_PROXY.md` for exactly what is.

### MJPEG / go2rtc / Frigate

Enabling the MJPEG server (alongside Camera) exposes the same capture feed the ESPHome camera
entity uses over plain HTTP, for anything that wants a direct feed instead of going through
Home Assistant:

```
http://<device-ip>:8080/camera.jpg     # single JPEG
http://<device-ip>:8080/camera.mjpeg   # multipart/x-mixed-replace stream
```

The port and max FPS are configurable in-app (`number.mjpeg_port`, `number.mjpeg_max_fps`);
a per-device token is required by default and shown in the app next to the URL
(`?token=...`). Disabling the token requirement is in-app only — deliberately not an HA
entity, so it can't be switched off remotely.

**go2rtc:**

```yaml
streams:
  aesphome:
    - "http://192.168.x.x:8080/camera.mjpeg?token=YOUR_TOKEN"
```

**Frigate** (via go2rtc):

```yaml
go2rtc:
  streams:
    aesphome:
      - "http://192.168.x.x:8080/camera.mjpeg?token=YOUR_TOKEN"

cameras:
  aesphome:
    ffmpeg:
      inputs:
        - path: rtsp://127.0.0.1:8554/aesphome
          roles:
            - detect
```

A direct `rtsp://<device-ip>:8554/aesphome` H.264 stream is also implemented (Camera2 →
MediaCodec → RTP-over-TCP) — see `AESPHome/docs/RTSP_PLAN.md`, including an important caveat:
it was never verified against a real player, only built and unit-tested for the pure protocol
logic. Test it on real hardware before relying on it.

### Person Detection

Enabling **Person Detection** (alongside Camera) runs a small on-device TFLite model
(EfficientDet-Lite0, bundled, CPU-only, ~4.3MB) against the camera feed and reports
`binary_sensor.person_detected` / `sensor.person_count` to Home Assistant — no image or video
data leaves the device. This is a genuinely new dependency (`tensorflow-lite-task-vision`),
adding roughly 18MB to the APK; it's opt-in and off by default.

### Auto Update

With **Auto Update Check** enabled, the app periodically compares its own version against the
latest release on `github.com/rafal83/AESPHome` and reports `binary_sensor.update_available`
/ `text_sensor.latest_available_version`. `button.install_update` (or the in-app equivalent)
downloads that release's APK and opens Android's own install-confirmation screen — the last
tap is unavoidable without root or device-owner status, so this automates checking and
downloading, not the final install itself.

---


<img width="343" height="1901" alt="Controls, Sensors, Configuration" src="https://github.com/user-attachments/assets/45da6d1e-fec6-4c83-a9d9-fb3774731fc0" />


