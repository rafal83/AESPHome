package com.aesphome

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager


/*

  Accelerometer (X/Y/Z)
    Three independent entities (sensor.accelerometer_x/y/z), each with its own listener
    registration on the same TYPE_ACCELEROMETER sensor — Android supports several listeners
    on one physical sensor simultaneously, so this avoids a shared-driver abstraction for
    what would otherwise be one Toggleable owning three ESPHome entities (the rest of this
    codebase's registry only models one entity per Toggleable). Each axis rate-limits its own
    reports independently via its own Setting, same convention as every other tunable sensor
    in this package (e.g. LightSensor) — deliberately NOT forwarding every SensorEvent, which
    at SENSOR_DELAY_NORMAL would still be several updates/second per axis.

*/


private const val DEFAULT_MIN_INTERVAL_S = 5f

private class AccelerometerAxisSensor(
    override val id: String,
    override val label: String,
    private val axisIndex: Int,
) : EventSensor, SensorEventListener {
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:axis-arrow"
  override fun kind(c: Context)    = SensorKind.Numeric(unit = "m/s²", deviceClass = "")

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
      (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

  override fun start(context: Context) {
    appContext = context
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    sensorManager = manager
    manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
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

val AccelerometerXSensor: EventSensor = AccelerometerAxisSensor("accelerometer_x", "Accelerometer X", 0)
val AccelerometerYSensor: EventSensor = AccelerometerAxisSensor("accelerometer_y", "Accelerometer Y", 1)
val AccelerometerZSensor: EventSensor = AccelerometerAxisSensor("accelerometer_z", "Accelerometer Z", 2)
