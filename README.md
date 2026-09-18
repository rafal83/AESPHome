# ÆSPHome

<img width="438" height="320" alt="Main Image" src="https://github.com/user-attachments/assets/4022084f-d793-497c-9eac-ec22fc399b92" />

[![Android Build](https://github.com/rafal83/AESPHome/actions/workflows/android-build.yml/badge.svg)](https://github.com/rafal83/AESPHome/actions/workflows/android-build.yml)

Android Simulating ESPHome Device for use with Home Assistant.

*Work in progress.*

## What is it?

It's an Android app that looks to Home Assistant like an ESPHome device:

```
Android  →  ESPHome Native API  →  Home Assistant
```

**Does it work?** Yes. Sorta.
**Is it ESPHome?** No. Sorta.

## Do I need to install anything?

Just the app on an old Android device. Home Assistant detects it automatically through the
ESPHome integration — you don't need to install anything on the Home Assistant side.

More background questions (supported Android versions, permissions, why the APK is this
large, why the MAC address looks made up) are answered in the [FAQ](FAQ.md).

The API connection is plaintext by default (same as always) with an opt-in Noise-encrypted
transport available from the main screen; the MJPEG/RTSP feeds each have their own optional
auth. See `AESPHome/docs/SECURITY.md` for exactly what's implemented and, importantly, what
has and hasn't been verified against a real Home Assistant instance.

---

## Features

Every feature below is a real Home Assistant entity, grouped by area. `AESPHome/docs/IMPLEMENTATION_REPORT.md`
has the complete, exhaustive list with the exact permission each one needs and why.

### Media & Bluetooth

| Feature | Requirement | Entity |
|---|---|---|
| Media player (audio) | — | `media_player.*` (libVLC-backed) |
| System volume | — | `number.system_volume` |
| Bluetooth radio on/off | Nearby Devices permission | `switch.bluetooth_enabled` |
| Connect/disconnect a paired speaker | Nearby Devices permission | `select` (command dropdown) |
| Bluetooth LE proxy — passive scan **and** active GATT connections | `BLUETOOTH_SCAN`/location + `BLUETOOTH_CONNECT` | `switch.bluetooth_proxy` |

Enabling `switch.bluetooth_proxy` makes the device show up to Home Assistant's own Bluetooth
integration as a full Bluetooth Proxy: passive advertisement scanning (most sensors/trackers)
**and** active GATT connections (HA connecting through this device to read/write/subscribe to
a BLE peripheral's characteristics). Pairing and cache-clearing aren't implemented — see
`AESPHome/docs/BLUETOOTH_PROXY.md` for exactly what is.

### Camera & streaming

| Feature | Requirement | Entity |
|---|---|---|
| ESPHome camera (stills + stream) | Camera permission | `camera.*` |
| Lens / rotation / resolution / effect / JPEG quality / idle update rate | — | `select`/`number` per option |
| Illuminance from the camera or light sensor | Camera or light sensor | `sensor.lux_*` |
| MJPEG HTTP server | Camera enabled | `binary_sensor.mjpeg_server_running`, `number.mjpeg_port`, `number.mjpeg_max_fps`, `text_sensor.mjpeg_url` |
| RTSP / H.264 server ⚠️ | Camera permission | `binary_sensor.rtsp_server_running`, `number.rtsp_port`, `number.rtsp_bitrate_kbps`, `text_sensor.rtsp_url` |
| Person detection (on-device TFLite) | Camera enabled | `binary_sensor.person_detected`, `sensor.person_count` |

Enabling the MJPEG server (alongside Camera) exposes the same capture feed the ESPHome camera
entity uses, over plain HTTP, for anything that wants a direct feed instead of going through
Home Assistant:

```
http://<device-ip>:8080/camera.jpg     # single JPEG
http://<device-ip>:8080/camera.mjpeg   # multipart/x-mixed-replace stream
```

Port and max FPS are configurable in-app. A per-device token is required by default and shown
in the app next to the URL (`?token=...`); disabling the token requirement is in-app only —
deliberately not a Home Assistant entity, so it can't be switched off remotely.

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
MediaCodec → RTP-over-TCP, always at the camera's own selected resolution — there's no
separate RTSP resolution setting) — see `AESPHome/docs/RTSP_PLAN.md`. **It cannot run at the
same time as Camera, MJPEG, or Person Detection** — RTSP always targets the same physical
camera those use, and on real hardware that contention doesn't fail cleanly; it silently
starves RTSP's encoder of real frames until it errors out a few seconds in. Disable those
three while using RTSP, and vice versa; RtspServerService now refuses to start (with a clear
log line) rather than failing confusingly if it detects the conflict.

Person detection runs a small on-device model (EfficientDet-Lite0, bundled, CPU-only,
~4.3MB) against the camera feed — no image or video data leaves the device. It's a genuinely
new dependency (`tensorflow-lite-task-vision`), adding roughly 18MB to the APK; it's opt-in
and off by default.

### Screen

| Feature | Requirement | Entity |
|---|---|---|
| Brightness | "Modify system settings" permission | `number.screen_brightness` |
| Orientation lock | "Modify system settings" permission | `select.screen_orientation` |
| Wake screen | — (WakeLock) | `button.screen_wake` |
| Sleep/lock screen | Device Admin (real lock) or dim-only fallback | `button.screen_sleep` |
| Keep screen on | — (WakeLock) | `switch.keep_screen_on` |
| Screen on/off state | — | `binary_sensor.screen_on` |
| Screen touch (recent activity) | Accessibility Service enabled | `binary_sensor.screen_touch` |

`number.screen_brightness` and `select.screen_orientation` control the device system-wide
once "Modify system settings" is granted from the in-app **Permissions** screen — without it,
they only affect this app's own window while it's visible. `button.screen_sleep` performs a
real screen lock once **Device Admin** is enabled (also from the Permissions screen); without
it, the button only dims the app's own window and says so in its Home Assistant description.
`button.screen_wake` only wakes the screen — it does not bring AESPHome to the foreground.

### App control

| Feature | Requirement | Entity |
|---|---|---|
| Start at boot | `RECEIVE_BOOT_COMPLETED` (already required) | `switch.start_at_boot` |
| Launch an app | Whitelist chosen in-app (Allowed Apps screen) | `select.launch_app` |
| Foreground app | Usage Access permission | `text_sensor.foreground_app` |
| Check for / install app updates | `REQUEST_INSTALL_PACKAGES` (install step only) | `update.aesphome_firmware` — same Check/Install card real ESPHome devices show |
| Identify (audible beep) | — | `button.identify` |

`select.launch_app` can only launch a package you've explicitly allowed on the **Allowed
Apps** screen — there's no way to launch an arbitrary app from the network. The app
periodically compares its own version against the latest release on
`github.com/rafal83/AESPHome` (interval configurable in-app); a **Check for Updates Now**
button next to that setting forces an immediate check without waiting for the interval, with
a toast reporting the result. Home Assistant offers a real Install action on
`update.aesphome_firmware`, same as any other ESPHome device. Installing still needs one tap
on Android's own confirmation screen — unless the app has also been made **Device Owner**
(see the [FAQ](FAQ.md)), in which case the update installs with zero taps.

### Sensors

| Feature | Requirement | Entity |
|---|---|---|
| Battery percent / charging / temperature / voltage / current / power / source | Device-dependent | `sensor.battery_*`, `binary_sensor.battery_charging`, `text_sensor.charging_source` |
| Wi-Fi RSSI / frequency / link speed | — | `sensor.wifi_*` |
| Device movement / orientation | — | `binary_sensor.device_movement`, `sensor.device_orientation` |
| Ambient noise (dB) | Microphone permission | `sensor.ambient_noise` |
| Proximity, pressure, humidity, ambient temperature | Matching hardware (hidden if absent) | `sensor.*` |
| Accelerometer / gyroscope / magnetic field (x/y/z) | Matching hardware (hidden if absent) | `sensor.*` |

### Diagnostics

| Feature | Requirement | Entity |
|---|---|---|
| Android/app version, device model | — | `text_sensor.*` |
| IP address, Wi-Fi SSID/BSSID | — | `text_sensor.*` |
| Uptime, free/total memory, free/total storage | — | `sensor.*` |

### Permissions

The app never requests every permission on first launch. Open **Permissions** from the main
screen to see Granted/Denied/Not-supported for each one an enabled feature needs, with an
Enable button that opens the right Android settings screen.
