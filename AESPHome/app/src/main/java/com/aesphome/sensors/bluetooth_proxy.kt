package com.aesphome

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log


/*

  Bluetooth LE Passive Proxy
    switch.bluetooth_proxy — makes this device visible to Home Assistant's Bluetooth
    integration the same way a real ESPHome Bluetooth Proxy is: HA's ESPHome integration
    reads bluetooth_proxy_feature_flags from DeviceInfoResponse (esphome.kt), subscribes with
    SubscribeBluetoothLEAdvertisementsRequest, and this device forwards every BLE
    advertisement Android's own scanner sees as a BluetoothLERawAdvertisementsResponse —
    exactly the wire format current ESPHome firmware uses (the older, non-raw
    BluetoothLEAdvertisementResponse was removed upstream in ESPHome 2025.8.0, so this only
    ever speaks the raw format).

    Also starts/stops BluetoothGattProxy (bluetooth_gatt.kt), which handles the OTHER half of
    the same feature — HA connecting *through* this device to a remote BLE peripheral (GATT
    read/write/notify). One switch, one permission set, both capabilities — matching how a
    real ESPHome bluetooth_proxy component's active_connections option just adds capability
    to the same proxy rather than being a separate entity. Pairing/cache-clearing/connection-
    parameter negotiation are NOT implemented — see docs/BLUETOOTH_PROXY.md.

*/


object BluetoothProxySwitch : SwitchEntity {
  override val id                  = "bluetooth_proxy"
  override val label               = "Bluetooth Proxy"
  override val description         = "Requires Nearby Devices (Bluetooth) permission"
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:bluetooth-transfer"

  private var handlerThread: HandlerThread? = null
  private var handler: Handler? = null
  private var scanner: BluetoothLeScanner? = null

  // BluetoothLeScanner's public startScan() overload has no Handler parameter, and
  // ScanCallback isn't documented to always land off the main thread — so the actual push
  // (a blocking socket write under send()'s lock) is bounced onto our own HandlerThread
  // rather than risking it running on whatever thread onScanResult was invoked from.
  private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      val address = macStringToLong(result.device.address) ?: return
      val data = result.scanRecord?.bytes ?: return
      handler?.post { AESPHomeService.instance?.pushBleAdvertisement(address, result.rssi, 0, data) }
    }
    override fun onScanFailed(errorCode: Int) {
      Log.e(TAG, "Bluetooth LE scan failed to start: error $errorCode")
    }
  }

  override fun isOn(context: Context): Boolean = getFlag(context, "bluetooth_proxy_state", false)

  override fun setOn(context: Context, on: Boolean) {
    setFlag(context, "bluetooth_proxy_state", on)
    apply(context, on)
  }

  override fun start(context: Context) {
    apply(context, isOn(context))
    AESPHomeService.instance?.reportSwitch(this, isOn(context))
  }

  override fun stop(context: Context) = apply(context, false)

  private fun hasScanPermission(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
    return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
  }

  private fun apply(context: Context, on: Boolean) {
    if (!on) {
      stopScan()
      BluetoothGattProxy.stop()
      return
    }
    BluetoothGattProxy.start(context)
    if (!hasScanPermission(context)) {
      Log.e(TAG, "Bluetooth proxy: scan permission not granted")
      return
    }
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    if (adapter == null || !adapter.isEnabled) {
      Log.e(TAG, "Bluetooth proxy: adapter unavailable or disabled")
      return
    }
    val leScanner = try { adapter.bluetoothLeScanner } catch (e: SecurityException) { null }
    if (leScanner == null) { Log.e(TAG, "Bluetooth proxy: no BLE scanner available"); return }

    val thread = HandlerThread("AESPHomeBleScan").apply { start() }
    handlerThread = thread
    handler = Handler(thread.looper)
    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .build()
    try {
      leScanner.startScan(emptyList(), settings, scanCallback)
      scanner = leScanner
      Log.i(TAG, "Bluetooth proxy: passive scan started")
    } catch (e: SecurityException) {
      Log.e(TAG, "Bluetooth proxy: startScan denied", e)
      thread.quitSafely()
      handlerThread = null
      handler = null
    }
  }

  private fun stopScan() {
    try { scanner?.stopScan(scanCallback) } catch (e: SecurityException) {}
    scanner = null
    handlerThread?.quitSafely()
    handlerThread = null
    handler = null
    Log.i(TAG, "Bluetooth proxy: scan stopped")
  }
}
