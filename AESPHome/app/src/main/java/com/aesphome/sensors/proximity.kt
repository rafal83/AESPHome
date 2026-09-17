package com.aesphome

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager


/*

  Proximity
    Same shape as LightSensor (light_sensor.kt) — reports the raw distance Android's
    proximity sensor returns (typically 0/near or its max range/far, cm). isAvailable() hides
    the entity entirely on devices with no proximity sensor, rather than listing a HA entity
    that would never report anything.

*/


object ProximitySensor : EventSensor, SensorEventListener {
  override val id                  = "proximity"
  override val label               = "Proximity"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:ruler"
  override fun kind(c: Context)    = SensorKind.Numeric(unit = "cm", deviceClass = "distance")

  private var sensorManager: SensorManager? = null

  override fun isAvailable(context: Context): Boolean =
      (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_PROXIMITY) != null

  override fun start(context: Context) {
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    sensorManager = manager
    manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
      manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
    }
  }

  override fun stop(context: Context) {
    sensorManager?.unregisterListener(this)
    sensorManager = null
  }

  override fun onSensorChanged(event: SensorEvent?) {
    val distance = event?.values?.firstOrNull() ?: return
    AESPHomeService.instance?.reportSensor(this, distance)
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
