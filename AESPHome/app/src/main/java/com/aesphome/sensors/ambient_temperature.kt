package com.aesphome

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager


/*

  Ambient (Air) Temperature
    Sensor.TYPE_AMBIENT_TEMPERATURE — distinct from battery_temperature.kt's battery-pack
    reading. Rare on phones (most only expose a CPU/battery thermistor via other APIs). Same
    rate-limited pattern as PressureSensor/HumiditySensor.

*/


object AmbientTemperatureSensor : EventSensor, SensorEventListener {
  override val id                  = "ambient_temperature"
  override val label               = "Ambient Temperature"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:thermometer"
  override fun kind(c: Context)    = SensorKind.Numeric(unit = "°C", deviceClass = "temperature")

  private val minReportIntervalSetting = Setting(
    id                 = "ambient_temperature_min_report_interval",
    label              = "Ambient Temperature Report Interval (s)",
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
      (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE) != null

  override fun start(context: Context) {
    appContext = context
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    sensorManager = manager
    manager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let {
      manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
    }
  }

  override fun stop(context: Context) {
    sensorManager?.unregisterListener(this)
    sensorManager = null
  }

  override fun onSensorChanged(event: SensorEvent?) {
    val celsius = event?.values?.firstOrNull() ?: return
    val now = System.currentTimeMillis()
    val minIntervalMs = ((appContext?.let { getSetting(it, minReportIntervalSetting) } ?: minReportIntervalSetting.default) * 1000).toLong()
    if (now - lastReportTime < minIntervalMs) return
    lastReportTime = now
    AESPHomeService.instance?.reportSensor(this, celsius)
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
