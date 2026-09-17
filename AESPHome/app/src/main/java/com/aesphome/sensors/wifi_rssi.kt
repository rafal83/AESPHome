package com.aesphome

import android.content.Context
import android.net.wifi.WifiManager


/*

  WiFi RSSI

  TODO:
    * Add logging  

*/


object WifiRssiSensor : ReadSensor {
  override val id                     = "wifi_rssi"
  override val label                  = "WiFi RSSI"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val entityCategory         = EntityCategory.DIAGNOSTIC
  override val icon                   = "mdi:wifi"
  override fun kind(context: Context) = SensorKind.Numeric(unit="dBm", deviceClass="signal_strength")

  override fun read(context: Context): Float? {
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val rssi = wifiManager.connectionInfo?.rssi ?: return null
    return if (rssi == Int.MIN_VALUE) null else rssi.toFloat() // Int.MIN_VALUE: no current connection
  }
}
