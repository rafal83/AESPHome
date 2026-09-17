package com.aesphome

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager


/*

  Relative Humidity
    Same rate-limited pattern as PressureSensor — an environmental reading, not a motion one.

*/


object HumiditySensor : EventSensor, SensorEventListener {
  override val id                  = "relative_humidity"
  override val label               = "Relative Humidity"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:water-percent"
  override fun kind(c: Context)    = SensorKind.Numeric(unit = "%", deviceClass = "humidity")

  private val minReportIntervalSetting = Setting(
    id                 = "humidity_min_report_interval",
    label              = "Humidity Report Interval (s)",
    default            = 60f,
    min                = 5f,
    max                = 3600f,
    step               = 1f,
    deviceUi           = true,
    homeAssistant      = true,
    entityCategory     = EntityCategory.CONFIG,
    enabledByDefaultHa = false,
    icon               = "mdi:timer-outline")

  override val settings = listOf(minReportIntervalSetting)

  private var sensorManager: SensorManager? = null
  private var appContext: Context? = null
  private var lastReportTime = 0L

  override fun isAvailable(context: Context): Boolean =
      (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY) != null

  override fun start(context: Context) {
    appContext = context
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    sensorManager = manager
    manager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)?.let {
      manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
    }
  }

  override fun stop(context: Context) {
    sensorManager?.unregisterListener(this)
    sensorManager = null
  }

  override fun onSensorChanged(event: SensorEvent?) {
    val pct = event?.values?.firstOrNull() ?: return
    val now = System.currentTimeMillis()
    val minIntervalMs = ((appContext?.let { getSetting(it, minReportIntervalSetting) } ?: minReportIntervalSetting.default) * 1000).toLong()
    if (now - lastReportTime < minIntervalMs) return
    lastReportTime = now
    AESPHomeService.instance?.reportSensor(this, pct)
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
