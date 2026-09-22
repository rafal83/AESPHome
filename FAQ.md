ÆSPHome FAQ
---

Should I install this on my personal every day device like my phone?
---
nah

What versions of Android are supported?
---
It was built for Tablets running on **Android version 9.**

It will work on other versions, but some features may not work or work incorrectly.

What permissions does it need?
---
It can make use of Camera, Microphone, and "Nearby Devices"/Bluetooth permissions as well as the Accessibility Service. 

Permissions must be manually enabled by you, the operator. They will not be automatically requested.

Is this a polished piece of professional software?
---
No, this is a quarter-slop passion project to breath life and functionality into half-discarded devices.

Your mileage will vary.

Something is not showing up?
---
Is it enabled in the app?
Is the entity enabled in Home Assistant?
Does the app have the requisite permissions?
Did you press "Refresh Changes" in the app?

Why is the APK so large?
---
VLC library for media player entity, and not targeting specific architecture  

Why isn't the MAC address reported the actual MAC of the device?
---
Android being Android. Can't reliably get the WiFi MAC address, so a MAC-shaped hex of the dynamically generated Android ID is used instead.

How do I get zero-tap silent updates?
---
By default, installing an update still needs one tap on Android's own "Install" confirmation
screen — Android requires that unless the app is **Device Owner**, a device-management
privilege with no in-app grant flow (it can only be set from adb, and only on a device with no
Google account already signed in / no other Device Owner already set):

```
adb shell dpm set-device-owner com.aesphome/.AESPHomeDeviceAdminReceiver
```

Once set, the **Permissions** screen shows "Device Owner (silent updates)" as Granted, and
`update.aesphome_firmware`'s Install action from Home Assistant installs with no tap at all.
This is a device-wide, mostly-irreversible commitment (removing it again generally means a
factory reset) — only worth it for a device dedicated entirely to running this app.

The "Battery Optimisation Exemption" button in Permissions does nothing — is that broken?
---
On some OEM builds (confirmed on Amazon Fire OS), the system screen this button opens doesn't
exist for third-party apps at all — Fire OS removes "Ignore battery optimizations" from
Settings > Apps & Notifications > Special access entirely, so there's nothing for the intent
to land on. This is an OS-level restriction, not something the app can work around by itself.

Two options if this affects you:
1. You generally don't need to fix it — AESPHome runs a background watchdog alarm that
   detects if its own service was killed (e.g. by App Standby) and restarts it, typically
   within 15 minutes, without needing the exemption at all.
2. To grant the exemption directly anyway:
```
adb shell dumpsys deviceidle whitelist +com.aesphome
```
Check it took effect with `adb shell dumpsys deviceidle whitelist` (should list `com.aesphome`).

How can I help?
---
Use it, enjoy it, make bug reports, make suggestions for improvements or open a PR and add improvements.
