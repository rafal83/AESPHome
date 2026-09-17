package com.aesphome

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*

  Bluetooth GATT Proxy (active connections)
    The other half of switch.bluetooth_proxy (see bluetooth_proxy.kt for the passive scan
    side, and docs/BLUETOOTH_PROXY.md for what's deliberately NOT here — pairing, cache
    clearing, connection-parameter negotiation). This handles HA connecting *through* this
    device to a remote BLE peripheral: connect/disconnect, service discovery, characteristic/
    descriptor read/write, and notifications.

    Owns every android.bluetooth.BluetoothGatt this device holds; esphome.kt only knows how
    to encode/decode the wire messages and calls straight into the functions below — same
    split as CameraService (hardware) / esphome.kt (wire format) elsewhere in this codebase.

    Handles: ESPHome's GATT "handle" is opaque to the client — it only has to be a stable,
    unique identifier the client can hand back on a later read/write/notify request. Rather
    than rely on Android's deprecated BluetoothGattCharacteristic.getInstanceId() (removed in
    spirit, if not yet in fact, from newer Android), each connection assigns its own
    incrementing integer handle per characteristic/descriptor and keeps a local map — real
    ATT handles are never exposed to or needed by the client.

    GATT operation queueing: Android silently drops a second read/write/descriptor operation
    issued on the same BluetoothGatt before the previous one's callback has fired — so every
    operation for a given connection goes through a simple FIFO queue, one in flight at a
    time, advanced from each operation's own callback.

*/

// Arbitrary — Android has no fixed BLE connection-slot count like real ESPHome hardware;
// keeps BluetoothConnectionsFreeResponse (esphome.kt) meaningful. Shared (not file-private)
// since esphome.kt's SubscribeBluetoothConnectionsFreeRequest handler reports against it too.
internal const val BLE_MAX_CONNECTIONS = 3

// BluetoothDeviceRequestType (api.proto)
private const val REQUEST_TYPE_CONNECT = 0
private const val REQUEST_TYPE_DISCONNECT = 1
private const val REQUEST_TYPE_PAIR = 2
private const val REQUEST_TYPE_UNPAIR = 3
private const val REQUEST_TYPE_CONNECT_V3_WITH_CACHE = 4
private const val REQUEST_TYPE_CONNECT_V3_WITHOUT_CACHE = 5
private const val REQUEST_TYPE_CLEAR_CACHE = 6

private const val GATT_ERROR_NOT_CONNECTED = 129 // matches ESPHome's own GATT_NOT_CONNECTED-ish "not connected" convention: a value outside the real 0-255 ATT error space callers can recognize as synthetic

object BluetoothGattProxy {

  private class GattOp(val run: () -> Unit)

  private class Connection(val device: BluetoothDevice) {
    var gatt: BluetoothGatt? = null
    val charByHandle = HashMap<Int, BluetoothGattCharacteristic>()
    val descByHandle = HashMap<Int, BluetoothGattDescriptor>()
    val serviceHandle = HashMap<BluetoothGattService, Int>()
    var nextHandle = 1
    var servicesSent = false

    // Looks up this object's previously-assigned handle, or assigns and remembers a new one
    // — the single source of truth both the first GetServicesResponse and any later re-request
    // on the same connection go through, so handles are always stable for its whole lifetime.
    fun handleFor(service: BluetoothGattService): Int = serviceHandle.getOrPut(service) { nextHandle++ }
    fun handleFor(characteristic: BluetoothGattCharacteristic): Int =
        charByHandle.entries.firstOrNull { it.value == characteristic }?.key
            ?: (nextHandle++).also { charByHandle[it] = characteristic }
    fun handleFor(descriptor: BluetoothGattDescriptor): Int =
        descByHandle.entries.firstOrNull { it.value == descriptor }?.key
            ?: (nextHandle++).also { descByHandle[it] = descriptor }

    private val opQueue = ArrayDeque<GattOp>()
    @Volatile private var opInFlight = false

    @Synchronized fun enqueue(op: () -> Unit) {
      opQueue.addLast(GattOp(op))
      if (!opInFlight) runNext()
    }

    @Synchronized fun completeOp() {
      opInFlight = false
      runNext()
    }

    private fun runNext() {
      val next = opQueue.removeFirstOrNull() ?: return
      opInFlight = true
      next.run()
    }
  }

  private val connections = ConcurrentHashMap<Long, Connection>() // keyed by macStringToLong(device.address)
  private var appContext: Context? = null

  fun start(context: Context) { appContext = context }

  fun stop() {
    for (conn in connections.values) try { conn.gatt?.close() } catch (e: Exception) {}
    connections.clear()
  }

  fun activeConnectionCount(): Int = connections.size

  private fun adapter(context: Context) = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

  private fun hasConnectPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
  }

  // ==================== Device connect/disconnect/pair ====================

  fun handleDeviceRequest(context: Context, address: Long, requestType: Int) {
    when (requestType) {
      REQUEST_TYPE_CONNECT, REQUEST_TYPE_CONNECT_V3_WITH_CACHE, REQUEST_TYPE_CONNECT_V3_WITHOUT_CACHE -> connect(context, address)
      REQUEST_TYPE_DISCONNECT -> disconnect(address)
      REQUEST_TYPE_PAIR -> AESPHomeService.instance?.pushBlePairingResponse(address, paired = false, error = -1)
      REQUEST_TYPE_UNPAIR -> AESPHomeService.instance?.pushBleUnpairingResponse(address, success = false, error = -1)
      REQUEST_TYPE_CLEAR_CACHE -> AESPHomeService.instance?.pushBleClearCacheResponse(address, success = false, error = -1)
    }
  }

  private fun connect(context: Context, address: Long) {
    if (connections.containsKey(address)) return // already connected/connecting
    if (!hasConnectPermission(context)) {
      Log.e(TAG, "GATT connect: BLUETOOTH_CONNECT not granted")
      AESPHomeService.instance?.pushBleDeviceConnection(address, connected = false, mtu = 0, error = -1)
      return
    }
    if (connections.size >= BLE_MAX_CONNECTIONS) {
      Log.e(TAG, "GATT connect: at BLE_MAX_CONNECTIONS ($BLE_MAX_CONNECTIONS)")
      AESPHomeService.instance?.pushBleDeviceConnection(address, connected = false, mtu = 0, error = -1)
      return
    }
    val btAdapter = adapter(context) ?: return
    val device = try { btAdapter.getRemoteDevice(uint64ToMacString(address)) } catch (e: IllegalArgumentException) { return }

    val conn = Connection(device)
    connections[address] = conn
    try {
      conn.gatt = device.connectGatt(context, false, callback(address), BluetoothDevice.TRANSPORT_LE)
    } catch (e: SecurityException) {
      Log.e(TAG, "GATT connect denied", e)
      connections.remove(address)
      AESPHomeService.instance?.pushBleDeviceConnection(address, connected = false, mtu = 0, error = -1)
    }
  }

  private fun disconnect(address: Long) {
    val conn = connections[address] ?: return
    try { conn.gatt?.disconnect() } catch (e: SecurityException) {}
  }

  // ==================== GATT callback ====================

  private fun callback(address: Long) = object : BluetoothGattCallback() {

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        AESPHomeService.instance?.pushBleDeviceConnection(address, connected = true, mtu = 23, error = 0)
        try { gatt.discoverServices() } catch (e: SecurityException) {}
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        AESPHomeService.instance?.pushBleDeviceConnection(address, connected = false, mtu = 0, error = if (status != 0) status else 0)
        try { gatt.close() } catch (e: Exception) {}
        connections.remove(address)
      }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      val conn = connections[address] ?: return
      if (status != BluetoothGatt.GATT_SUCCESS) {
        AESPHomeService.instance?.pushBleServicesDone(address)
        return
      }
      val services = gatt.services.map { service -> buildServiceMessage(conn, service) }
      conn.servicesSent = true
      AESPHomeService.instance?.pushBleServices(address, services)
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
      val conn = connections[address]
      val handle = conn?.charByHandle?.entries?.firstOrNull { it.value == characteristic }?.key
      if (handle != null) {
        if (status == BluetoothGatt.GATT_SUCCESS) AESPHomeService.instance?.pushBleGattRead(address, handle, value)
        else AESPHomeService.instance?.pushBleGattError(address, handle, status)
      }
      conn?.completeOp()
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      val conn = connections[address]
      val handle = conn?.charByHandle?.entries?.firstOrNull { it.value == characteristic }?.key
      if (handle != null) {
        if (status == BluetoothGatt.GATT_SUCCESS) AESPHomeService.instance?.pushBleGattWriteResponse(address, handle)
        else AESPHomeService.instance?.pushBleGattError(address, handle, status)
      }
      conn?.completeOp()
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
      val conn = connections[address] ?: return
      val handle = conn.charByHandle.entries.firstOrNull { it.value == characteristic }?.key ?: return
      AESPHomeService.instance?.pushBleGattNotifyData(address, handle, value)
    }

    override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray) {
      val conn = connections[address]
      val handle = conn?.descByHandle?.entries?.firstOrNull { it.value == descriptor }?.key
      if (handle != null) {
        if (status == BluetoothGatt.GATT_SUCCESS) AESPHomeService.instance?.pushBleGattRead(address, handle, value)
        else AESPHomeService.instance?.pushBleGattError(address, handle, status)
      }
      conn?.completeOp()
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
      val conn = connections[address]
      // The CCCD write descriptor's write is how notify enable/disable (below) completes —
      // it's routed back as a NotifyResponse, not a plain descriptor WriteResponse, so HA's
      // "enable notifications" call resolves; a write aimed at any OTHER descriptor still
      // reports as a normal GATT write response.
      val pendingNotifyHandle = conn?.charByHandle?.entries
          ?.firstOrNull { it.value.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) === descriptor }?.key
      if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID && pendingNotifyHandle != null) {
        AESPHomeService.instance?.pushBleGattNotifyResponse(address, pendingNotifyHandle)
      } else {
        val handle = conn?.descByHandle?.entries?.firstOrNull { it.value == descriptor }?.key
        if (handle != null) {
          if (status == BluetoothGatt.GATT_SUCCESS) AESPHomeService.instance?.pushBleGattWriteResponse(address, handle)
          else AESPHomeService.instance?.pushBleGattError(address, handle, status)
        }
      }
      conn?.completeOp()
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {}
  }

  // ==================== GATT service tree encoding ====================

  // Idempotent: every handle comes from Connection.handleFor(), which assigns one the first
  // time an object is seen and returns the same one on every later call — so this can be
  // called again for a repeated GetServicesRequest on the same connection (handleGetServices
  // below) without handles shifting out from under a client that cached them.
  private fun buildServiceMessage(conn: Connection, service: BluetoothGattService): BleGattServiceMsg {
    val chars = service.characteristics.map { characteristic ->
      val descs = characteristic.descriptors.map { descriptor ->
        BleGattDescMsg(uuidToWire(descriptor.uuid), conn.handleFor(descriptor))
      }
      BleGattCharMsg(uuidToWire(characteristic.uuid), conn.handleFor(characteristic), characteristic.properties, descs)
    }
    return BleGattServiceMsg(uuidToWire(service.uuid), conn.handleFor(service), chars)
  }

  // ==================== Client requests ====================

  fun handleGetServices(address: Long) {
    val conn = connections[address]
    if (conn?.gatt == null) { AESPHomeService.instance?.pushBleServicesDone(address); return }
    if (conn.servicesSent) {
      // Re-serve from the already-discovered tree — handleFor() returns the same handles
      // assigned the first time (onServicesDiscovered), so this is safe to call again.
      AESPHomeService.instance?.pushBleServices(address, conn.gatt!!.services.map { buildServiceMessage(conn, it) })
    } else {
      try { conn.gatt!!.discoverServices() } catch (e: SecurityException) {}
    }
  }

  fun handleRead(address: Long, handle: Int) {
    val conn = connections[address] ?: run { AESPHomeService.instance?.pushBleGattError(address, handle, GATT_ERROR_NOT_CONNECTED); return }
    val gatt = conn.gatt ?: return
    conn.charByHandle[handle]?.let { char ->
      conn.enqueue { try { gatt.readCharacteristic(char) } catch (e: SecurityException) { conn.completeOp() } }
      return
    }
    conn.descByHandle[handle]?.let { desc ->
      conn.enqueue { try { gatt.readDescriptor(desc) } catch (e: SecurityException) { conn.completeOp() } }
      return
    }
    AESPHomeService.instance?.pushBleGattError(address, handle, GATT_ERROR_NOT_CONNECTED)
  }

  fun handleWrite(address: Long, handle: Int, data: ByteArray, expectResponse: Boolean) {
    val conn = connections[address] ?: run { AESPHomeService.instance?.pushBleGattError(address, handle, GATT_ERROR_NOT_CONNECTED); return }
    val gatt = conn.gatt ?: return
    val char = conn.charByHandle[handle] ?: run { AESPHomeService.instance?.pushBleGattError(address, handle, GATT_ERROR_NOT_CONNECTED); return }
    conn.enqueue {
      try {
        val writeType = if (expectResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          gatt.writeCharacteristic(char, data, writeType)
        } else {
          @Suppress("DEPRECATION")
          char.writeType = writeType
          @Suppress("DEPRECATION")
          char.value = data
          @Suppress("DEPRECATION")
          gatt.writeCharacteristic(char)
        }
        if (!expectResponse) conn.completeOp() // no-response writes never get onCharacteristicWrite
      } catch (e: SecurityException) { conn.completeOp() }
    }
  }

  fun handleWriteDescriptor(address: Long, handle: Int, data: ByteArray) {
    val conn = connections[address] ?: run { AESPHomeService.instance?.pushBleGattError(address, handle, GATT_ERROR_NOT_CONNECTED); return }
    val gatt = conn.gatt ?: return
    val desc = conn.descByHandle[handle] ?: run { AESPHomeService.instance?.pushBleGattError(address, handle, GATT_ERROR_NOT_CONNECTED); return }
    conn.enqueue {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          gatt.writeDescriptor(desc, data)
        } else {
          @Suppress("DEPRECATION")
          desc.value = data
          @Suppress("DEPRECATION")
          gatt.writeDescriptor(desc)
        }
      } catch (e: SecurityException) { conn.completeOp() }
    }
  }

  fun handleNotify(address: Long, handle: Int, enable: Boolean) {
    val conn = connections[address] ?: run { AESPHomeService.instance?.pushBleGattError(address, handle, GATT_ERROR_NOT_CONNECTED); return }
    val gatt = conn.gatt ?: return
    val char = conn.charByHandle[handle] ?: run { AESPHomeService.instance?.pushBleGattError(address, handle, GATT_ERROR_NOT_CONNECTED); return }
    val cccd = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
    conn.enqueue {
      try {
        gatt.setCharacteristicNotification(char, enable)
        if (cccd == null) { conn.completeOp(); return@enqueue } // no CCCD — nothing more to write, best-effort enable only
        val indicate = (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        val value = when {
          !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
          indicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
          else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          gatt.writeDescriptor(cccd, value)
        } else {
          @Suppress("DEPRECATION")
          cccd.value = value
          @Suppress("DEPRECATION")
          gatt.writeDescriptor(cccd)
        }
      } catch (e: SecurityException) { conn.completeOp() }
    }
  }
}

// ==================== UUID <-> wire helpers ====================

private val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"
internal val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902$BASE_UUID_SUFFIX")

// A standard Bluetooth SIG UUID is the 128-bit Base UUID with a 16/32-bit "assigned number"
// spliced into its first 4 hex bytes — detected and sent as ESPHome's compact `short_uuid`
// field instead of the full 128-bit form, matching the well-defined Bluetooth Base UUID
// expansion rule (not an ESPHome-specific detail, so this part carries no ambiguity). A
// genuinely custom 128-bit UUID falls back to the full two-uint64 `uuid` field — the split
// point there is a best-effort, unverified-against-upstream-source choice (see
// docs/BLUETOOTH_PROXY.md); it only affects how a *custom* UUID displays in HA, never
// read/write/notify correctness, since those are always routed through this app's own
// handle map, never by re-parsing a UUID back off the wire.
internal fun shortUuidOrNull(uuid: UUID): Int? {
  val text = uuid.toString()
  if (!text.endsWith(BASE_UUID_SUFFIX)) return null
  val assigned = text.substring(0, 8)
  if (!assigned.startsWith("0000")) return null // only the 16-bit form maps to a single wire int cleanly
  return assigned.substring(4).toIntOrNull(16)
}

internal fun uuid128ToLongPair(uuid: UUID): Pair<Long, Long> = uuid.mostSignificantBits to uuid.leastSignificantBits

internal data class WireUuid(val shortUuid: Int?, val high: Long, val low: Long)
private fun uuidToWire(uuid: UUID): WireUuid {
  val short = shortUuidOrNull(uuid)
  val (high, low) = uuid128ToLongPair(uuid)
  return WireUuid(short, high, low)
}

// Plain data carriers between BluetoothGattProxy (hardware) and AESPHome's wire encoding
// (esphome.kt) — mirrors how CameraService hands esphome.kt a finished JPEG rather than a
// protobuf message.
internal data class BleGattDescMsg(val uuid: WireUuid, val handle: Int)
internal data class BleGattCharMsg(val uuid: WireUuid, val handle: Int, val properties: Int, val descriptors: List<BleGattDescMsg>)
internal data class BleGattServiceMsg(val uuid: WireUuid, val handle: Int, val characteristics: List<BleGattCharMsg>)

private fun uint64ToMacString(address: Long): String {
  val bytes = (5 downTo 0).map { i -> (address ushr (i * 8)) and 0xFF }
  return bytes.joinToString(":") { "%02X".format(it) }
}
