package com.aesphome

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager


/*

  Gyroscope (X/Y/Z)
    Same design as accelerometer.kt — see its header comment.

*/


private const val DEFAULT_MIN_INTERVAL_S = 5f

private class GyroscopeAxisSensor(
    override val id: String,
    override val label: String,
    private val axisIndex: Int,
) : EventSensor, SensorEventListener {
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:axis-arrow"
  override fun kind(c: Context)    = SensorKind.Numeric(unit = "rad/s", deviceClass = "")

  val minReportIntervalSetting = Setting(
    id = "${id}_min_report_interval", label = "$label Report Interval (s)",
    default = DEFAULT_MIN_INTERVAL_S, min = 1f, max = 3600f, step = 1f,
    deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
    enabledByDefaultHa = false, icon = "mdi:timer-outline")
  override val settings = listOf(minReportIntervalSetting)

  private var sensorManager: SensorManager? = null
  private var appContext: Context? = null
  private var lastReportTime = 0L

  override fun isAvailable(context: Context): Boolean =
      (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

  override fun start(context: Context) {
    appContext = context
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    sensorManager = manager
    manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
      manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
    }
  }

  override fun stop(context: Context) {
    sensorManager?.unregisterListener(this)
    sensorManager = null
  }

  override fun onSensorChanged(event: SensorEvent?) {
    val value = event?.values?.getOrNull(axisIndex) ?: return
    val now = System.currentTimeMillis()
    val minIntervalMs = ((appContext?.let { getSetting(it, minReportIntervalSetting) } ?: DEFAULT_MIN_INTERVAL_S) * 1000).toLong()
    if (now - lastReportTime < minIntervalMs) return
    lastReportTime = now
    AESPHomeService.instance?.reportSensor(this, value)
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

val GyroscopeXSensor: EventSensor = GyroscopeAxisSensor("gyroscope_x", "Gyroscope X", 0)
val GyroscopeYSensor: EventSensor = GyroscopeAxisSensor("gyroscope_y", "Gyroscope Y", 1)
val GyroscopeZSensor: EventSensor = GyroscopeAxisSensor("gyroscope_z", "Gyroscope Z", 2)
