package com.aesphome

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock


/*

  Diagnostics — numeric sensors
    One object per sensor.* entity from the spec's diagnostics section (Wi-Fi RSSI and
    battery temperature already existed — see wifi_rssi.kt / battery_temperature.kt). Every
    read() returns null (never a fabricated number) when the value isn't available on this
    device/right now, reported to HA as missing_state via reportSensor's contract.

*/


object UptimeSensor : ReadSensor {
  override val id                     = "uptime"
  override val label                  = "Uptime"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:clock-outline"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "s", deviceClass = "duration")
  override fun read(context: Context): Float = SystemClock.elapsedRealtime() / 1000f
}

object FreeMemorySensor : ReadSensor {
  override val id                     = "free_memory"
  override val label                  = "Free Memory"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:memory"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "MB", deviceClass = "data_size")

  override fun read(context: Context): Float {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    return info.availMem / 1_048_576f
  }
}

object TotalMemorySensor : ReadSensor {
  override val id                     = "total_memory"
  override val label                  = "Total Memory"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:memory"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "MB", deviceClass = "data_size")

  override fun read(context: Context): Float {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    return info.totalMem / 1_048_576f
  }
}

object FreeStorageSensor : ReadSensor {
  override val id                     = "free_storage"
  override val label                  = "Free Storage"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:harddisk"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "MB", deviceClass = "data_size")

  override fun read(context: Context): Float {
    val stat = StatFs(Environment.getDataDirectory().path)
    return stat.availableBytes / 1_048_576f
  }
}

object TotalStorageSensor : ReadSensor {
  override val id                     = "total_storage"
  override val label                  = "Total Storage"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:harddisk"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "MB", deviceClass = "data_size")

  override fun read(context: Context): Float {
    val stat = StatFs(Environment.getDataDirectory().path)
    return stat.totalBytes / 1_048_576f
  }
}

object WifiFrequencySensor : ReadSensor {
  override val id                     = "wifi_frequency"
  override val label                  = "WiFi Frequency"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:wifi"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "MHz", deviceClass = "frequency")

  override fun read(context: Context): Float? {
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val frequency = wifiManager.connectionInfo?.frequency ?: return null
    return if (frequency <= 0) null else frequency.toFloat()
  }
}

object WifiLinkSpeedSensor : ReadSensor {
  override val id                     = "wifi_link_speed"
  override val label                  = "WiFi Link Speed"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:wifi-strength-3"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "Mbps", deviceClass = "")

  override fun read(context: Context): Float? {
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val linkSpeed = wifiManager.connectionInfo?.linkSpeed ?: return null
    return if (linkSpeed < 0) null else linkSpeed.toFloat()
  }
}

// Shared one-shot ACTION_BATTERY_CHANGED lookup — voltage/current/power all read from the
// same broadcast/property snapshot rather than each re-registering their own receiver.
private fun batteryIntent(context: Context): Intent? =
    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

private fun batteryManager(context: Context): BatteryManager =
    context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

object BatteryVoltageSensor : ReadSensor {
  override val id                     = "battery_voltage"
  override val label                  = "Battery Voltage"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:current-dc"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "V", deviceClass = "voltage")

  override fun read(context: Context): Float? {
    val millivolts = batteryIntent(context)?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
    return if (millivolts <= 0) null else millivolts / 1000f
  }
}

object BatteryCurrentSensor : ReadSensor {
  override val id                     = "battery_current"
  override val label                  = "Battery Current"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:current-ac"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "mA", deviceClass = "current")

  // BATTERY_PROPERTY_CURRENT_NOW is in microamps and not implemented on every device
  // (returns Int.MIN_VALUE / 0 when unsupported, depending on OEM) — treated as unavailable
  // rather than reported as a bogus zero.
  override fun read(context: Context): Float? {
    val microamps = batteryManager(context).getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    return if (microamps == Int.MIN_VALUE || microamps == 0) null else microamps / 1000f
  }
}

object BatteryPowerSensor : ReadSensor {
  override val id                     = "battery_power"
  override val label                  = "Battery Power"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:lightning-bolt"
  override fun kind(context: Context) = SensorKind.Numeric(unit = "W", deviceClass = "power")

  // Derived from voltage x current — only meaningful when both are actually available.
  override fun read(context: Context): Float? {
    val voltage = BatteryVoltageSensor.read(context) ?: return null
    val currentMa = BatteryCurrentSensor.read(context) ?: return null
    return voltage * (currentMa / 1000f)
  }
}
