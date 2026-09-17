package com.aesphome

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build


/*

  Diagnostics — text sensors
    One object per text_sensor.* entity from the spec's diagnostics section. Every read()
    returns null (never a fabricated string) when the value genuinely isn't available on this
    device/right now — reported to HA as missing_state via ReadTextSensor's contract.

*/


object AndroidVersionSensor : ReadTextSensor {
  override val id                  = "android_version"
  override val label               = "Android Version"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.DIAGNOSTIC
  override val icon                = "mdi:android"
  override fun read(context: Context): String = Build.VERSION.RELEASE
}

object DeviceModelSensor : ReadTextSensor {
  override val id                  = "device_model"
  override val label               = "Device Model"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.DIAGNOSTIC
  override val icon                = "mdi:cellphone-information"
  override fun read(context: Context): String = "${Build.MANUFACTURER} ${Build.MODEL}"
}

object AppVersionSensor : ReadTextSensor {
  override val id                  = "app_version"
  override val label               = "App Version"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.DIAGNOSTIC
  override val icon                = "mdi:tag-outline"
  override fun read(context: Context): String = BuildConfig.VERSION_NAME
}

object IpAddressSensor : ReadTextSensor {
  override val id                  = "ip_address"
  override val label               = "IP Address"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.DIAGNOSTIC
  override val icon                = "mdi:ip-network"
  override fun read(context: Context): String? = getWifiIpAddress()
}

object WifiSsidSensor : ReadTextSensor {
  override val id                  = "wifi_ssid"
  override val label               = "Wi-Fi SSID"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.DIAGNOSTIC
  override val icon                = "mdi:wifi"

  override fun read(context: Context): String? {
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val ssid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"") ?: return null
    return if (ssid.isBlank() || ssid == "<unknown ssid>") null else ssid
  }
}

object WifiBssidSensor : ReadTextSensor {
  override val id                  = "wifi_bssid"
  override val label               = "Wi-Fi BSSID"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.DIAGNOSTIC
  override val icon                = "mdi:router-wireless"

  override fun read(context: Context): String? {
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val bssid = wifiManager.connectionInfo?.bssid ?: return null
    // Android randomizes this to a fixed placeholder without the location permission the
    // app doesn't otherwise need (Wi-Fi RSSI/frequency/link-speed all work without it) — that
    // placeholder isn't a real BSSID, so it's reported as unavailable rather than fabricated.
    return if (bssid.isBlank() || bssid == "02:00:00:00:00:00") null else bssid
  }
}

object ChargingSourceSensor : ReadTextSensor {
  override val id                  = "charging_source"
  override val label               = "Charging Source"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.DIAGNOSTIC
  override val icon                = "mdi:power-plug-outline"

  override fun read(context: Context): String? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    return when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
      BatteryManager.BATTERY_PLUGGED_AC -> "AC"
      BatteryManager.BATTERY_PLUGGED_USB -> "USB"
      BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
      8 /* BATTERY_PLUGGED_DOCK, API 33+ */ -> "DOCK"
      0 -> null // not charging at all — nothing meaningful to report
      else -> "UNKNOWN"
    }
  }
}

object ForegroundAppSensor : ReadTextSensor {
  override val id                  = "foreground_app"
  override val label               = "Foreground App"
  override val description         = "Requires Usage Access permission"
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.NONE
  override val icon                = "mdi:application-outline"

  override fun read(context: Context): String? {
    if (!hasUsageAccess(context)) return null
    val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    val events = manager.queryEvents(now - 60_000, now)
    val event = android.app.usage.UsageEvents.Event()
    var lastPackage: String? = null
    while (events.hasNextEvent()) {
      events.getNextEvent(event)
      if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ||
          event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
        lastPackage = event.packageName
      }
    }
    return lastPackage
  }
}
