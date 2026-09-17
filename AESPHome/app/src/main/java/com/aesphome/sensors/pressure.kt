package com.aesphome

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager


/*

  Barometric Pressure
    Environmental sensor, rare on phones — reported at most once per minReportIntervalSetting
    (default 60s; this value changes slowly, unlike motion) rather than on every SensorEvent.

*/


object PressureSensor : EventSensor, SensorEventListener {
  override val id                  = "pressure"
  override val label               = "Pressure"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:gauge"
  override fun kind(c: Context)    = SensorKind.Numeric(unit = "hPa", deviceClass = "pressure")

  private val minReportIntervalSetting = Setting(
    id                 = "pressure_min_report_interval",
    label              = "Pressure Report Interval (s)",
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
      (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_PRESSURE) != null

  override fun start(context: Context) {
    appContext = context
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    sensorManager = manager
    manager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
      manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
    }
  }

  override fun stop(context: Context) {
    sensorManager?.unregisterListener(this)
    sensorManager = null
  }

  override fun onSensorChanged(event: SensorEvent?) {
    val hpa = event?.values?.firstOrNull() ?: return
    val now = System.currentTimeMillis()
    val minIntervalMs = ((appContext?.let { getSetting(it, minReportIntervalSetting) } ?: minReportIntervalSetting.default) * 1000).toLong()
    if (now - lastReportTime < minIntervalMs) return
    lastReportTime = now
    AESPHomeService.instance?.reportSensor(this, hpa)
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
