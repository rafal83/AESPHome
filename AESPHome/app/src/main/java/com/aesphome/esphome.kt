package com.aesphome

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.southernstorm.noise.protocol.CipherStatePair
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

// Message type constants
private const val MESSAGE_HELLO_REQUEST = 1
private const val MESSAGE_HELLO_RESPONSE = 2
private const val MESSAGE_DEVICE_INFO_REQUEST = 9
private const val MESSAGE_DEVICE_INFO_RESPONSE = 10
private const val MESSAGE_LIST_ENTITIES_REQUEST = 11
private const val MESSAGE_LIST_ENTITIES_RESPONSE = 63
private const val MESSAGE_LIST_ENTITIES_BINARY_SENSOR = 12
private const val MESSAGE_LIST_ENTITIES_SENSOR = 16
private const val MESSAGE_LIST_ENTITIES_DONE = 19
private const val MESSAGE_SUBSCRIBE_STATES_REQUEST = 20
private const val MESSAGE_STATE_RESPONSE = 64
private const val MESSAGE_BINARY_SENSOR_STATE = 21
private const val MESSAGE_PING_REQUEST = 7
private const val MESSAGE_PING_RESPONSE = 8
private const val MESSAGE_DISCONNECT_REQUEST = 5
private const val MESSAGE_DISCONNECT_RESPONSE = 6
private const val MESSAGE_MEDIA_PLAYER_COMMAND_REQUEST = 65
private const val MESSAGE_SENSOR_STATE = 25
private const val MESSAGE_LIST_ENTITIES_CAMERA = 43
private const val MESSAGE_CAMERA_IMAGE_RESPONSE = 44
private const val MESSAGE_CAMERA_IMAGE_REQUEST = 45
private const val MESSAGE_LIST_ENTITIES_NUMBER = 49
private const val MESSAGE_NUMBER_STATE_RESPONSE = 50
private const val MESSAGE_NUMBER_COMMAND_REQUEST = 51
private const val MESSAGE_LIST_ENTITIES_SELECT = 52
private const val MESSAGE_SELECT_STATE_RESPONSE = 53
private const val MESSAGE_SELECT_COMMAND_REQUEST = 54
private const val MESSAGE_LIST_ENTITIES_BUTTON = 61
private const val MESSAGE_BUTTON_COMMAND_REQUEST = 62
private const val MESSAGE_LIST_ENTITIES_SWITCH = 17
private const val MESSAGE_SWITCH_STATE_RESPONSE = 26
private const val MESSAGE_SWITCH_COMMAND_REQUEST = 33
private const val MESSAGE_LIST_ENTITIES_TEXT_SENSOR = 18
private const val MESSAGE_TEXT_SENSOR_STATE = 27
private const val MESSAGE_SUBSCRIBE_BLE_ADVERTISEMENTS_REQUEST = 66
private const val MESSAGE_UNSUBSCRIBE_BLE_ADVERTISEMENTS_REQUEST = 87
private const val MESSAGE_BLE_RAW_ADVERTISEMENTS_RESPONSE = 93

// update — real HA `update.` entity (the same "update available, Install button" card a real
// ESPHome device's own OTA flow shows), used by sensors/auto_update.kt instead of a plain
// binary_sensor + buttons.
private const val MESSAGE_LIST_ENTITIES_UPDATE = 116
private const val MESSAGE_UPDATE_STATE_RESPONSE = 117
private const val MESSAGE_UPDATE_COMMAND_REQUEST = 118

// Bluetooth GATT proxy (active connections) — see sensors/bluetooth_gatt.kt
private const val MESSAGE_BLE_DEVICE_REQUEST = 68
private const val MESSAGE_BLE_DEVICE_CONNECTION_RESPONSE = 69
private const val MESSAGE_BLE_GATT_GET_SERVICES_REQUEST = 70
private const val MESSAGE_BLE_GATT_GET_SERVICES_RESPONSE = 71
private const val MESSAGE_BLE_GATT_GET_SERVICES_DONE_RESPONSE = 72
private const val MESSAGE_BLE_GATT_READ_REQUEST = 73
private const val MESSAGE_BLE_GATT_READ_RESPONSE = 74
private const val MESSAGE_BLE_GATT_WRITE_REQUEST = 75
private const val MESSAGE_BLE_GATT_READ_DESCRIPTOR_REQUEST = 76
private const val MESSAGE_BLE_GATT_WRITE_DESCRIPTOR_REQUEST = 77
private const val MESSAGE_BLE_GATT_NOTIFY_REQUEST = 78
private const val MESSAGE_BLE_GATT_NOTIFY_DATA_RESPONSE = 79
private const val MESSAGE_SUBSCRIBE_BLE_CONNECTIONS_FREE_REQUEST = 80
private const val MESSAGE_BLE_CONNECTIONS_FREE_RESPONSE = 81
private const val MESSAGE_BLE_GATT_ERROR_RESPONSE = 82
private const val MESSAGE_BLE_GATT_WRITE_RESPONSE = 83
private const val MESSAGE_BLE_GATT_NOTIFY_RESPONSE = 84
private const val MESSAGE_BLE_DEVICE_PAIRING_RESPONSE = 85
private const val MESSAGE_BLE_DEVICE_UNPAIRING_RESPONSE = 86
private const val MESSAGE_BLE_DEVICE_CLEAR_CACHE_RESPONSE = 88

// Shared across every ListEntities*Response (InfoResponseProtoMessage base class):
// object_id, key, and name are always fields 1, 2, 3, regardless of entity type.
private const val F_OBJECT_ID = 1
private const val F_KEY = 2
private const val F_NAME = 3

// Shared across every *StateResponse/*CommandRequest (StateResponseProtoMessage /
// CommandProtoMessage base classes): key is always field 1.
private const val F_ENTITY_KEY = 1

// HelloResponse fields
private const val F_HELLO_API_VERSION_MAJOR = 1
private const val F_HELLO_API_VERSION_MINOR = 2
private const val F_HELLO_SERVER_INFO = 3
private const val F_HELLO_NAME = 4
private const val F_HELLO_REQUEST_CLIENT_INFO = 1 // HelloRequest field 1 — separate message from the response fields above

// DeviceInfoResponse fields
private const val F_DEVICE_INFO_NAME = 2
private const val F_DEVICE_INFO_MAC_ADDRESS = 3
private const val F_DEVICE_INFO_ESPHOME_VERSION = 4

// Reported as DeviceInfoResponse's esphome_version. Used to be a hand-picked fake string
// disconnected from this app's own version, specifically because the old SemVer-ish
// "0.x.y" versionName looked nothing like a real ESPHome release and would have made HA's
// ESPHome integration nag about a permanently-unresolvable "update available" (it compares
// this string against real published ESPHome releases). Now that this app's own versionName
// follows ESPHome/Home Assistant's own YYYY.M.PATCH convention (app/build.gradle), simply
// reporting BuildConfig.VERSION_NAME here already looks like a plausible, recent ESPHome
// version and needs no separate manual upkeep — see `esphomeVersion` below.
private val esphomeVersion get() = BuildConfig.VERSION_NAME
private const val F_DEVICE_INFO_MODEL = 6
private const val F_DEVICE_INFO_MANUFACTURER = 12
private const val F_DEVICE_INFO_FRIENDLY_NAME = 13
private const val F_DEVICE_INFO_BLUETOOTH_PROXY_FEATURE_FLAGS = 15

// BluetoothProxyFeature bitmask (aioesphomeapi model.py) — only the bits this server ever
// sets: passive scanning, plus raw-advertisement wire format (the only format modern
// ESPHome/aioesphomeapi still speak — the legacy non-raw BluetoothLEAdvertisementResponse
// was removed upstream in ESPHome 2025.8.0).
private const val BLUETOOTH_PROXY_FEATURE_PASSIVE_SCAN = 1
private const val BLUETOOTH_PROXY_FEATURE_ACTIVE_CONNECTIONS = 1 shl 1
private const val BLUETOOTH_PROXY_FEATURE_RAW_ADVERTISEMENTS = 1 shl 5

// ListEntitiesBinarySensorResponse fields beyond the shared object_id/key/name
private const val F_BINARY_SENSOR_DEVICE_CLASS = 5
private const val F_BINARY_SENSOR_DISABLED_BY_DEFAULT = 7
private const val F_BINARY_SENSOR_ICON = 8
private const val F_BINARY_SENSOR_ENTITY_CATEGORY = 9

// BinarySensorStateResponse
private const val F_BINARY_SENSOR_STATE = 2

// ListEntitiesSensorResponse fields beyond the shared object_id/key/name
private const val F_SENSOR_ICON = 5
private const val F_SENSOR_UNIT = 6
private const val F_SENSOR_ACCURACY_DECIMALS = 7
private const val F_SENSOR_DEVICE_CLASS = 9
private const val F_SENSOR_STATE_CLASS = 10
private const val F_SENSOR_DISABLED_BY_DEFAULT = 12
private const val F_SENSOR_ENTITY_CATEGORY = 13

// SensorStateResponse
private const val F_SENSOR_STATE = 2
private const val F_SENSOR_MISSING_STATE = 3

// ListEntitiesTextSensorResponse fields beyond the shared object_id/key/name
private const val F_TEXT_SENSOR_ICON = 5
private const val F_TEXT_SENSOR_DISABLED_BY_DEFAULT = 6
private const val F_TEXT_SENSOR_ENTITY_CATEGORY = 7

// TextSensorStateResponse
private const val F_TEXT_SENSOR_STATE = 2
private const val F_TEXT_SENSOR_MISSING_STATE = 3

// BluetoothLERawAdvertisementsResponse / BluetoothLERawAdvertisement (nested, repeated field 1)
private const val F_BLE_ADVERTISEMENTS = 1
private const val F_BLE_ADV_ADDRESS = 1
private const val F_BLE_ADV_RSSI = 2
private const val F_BLE_ADV_ADDRESS_TYPE = 3
private const val F_BLE_ADV_DATA = 4

// SubscribeBluetoothLEAdvertisementsRequest
private const val F_SUBSCRIBE_BLE_FLAGS = 1

// BluetoothDeviceRequest (client -> server) / BluetoothDeviceConnectionResponse (server -> client)
private const val F_BLE_DEV_ADDRESS = 1
private const val F_BLE_DEV_REQUEST_TYPE = 2
private const val F_BLE_CONN_CONNECTED = 2
private const val F_BLE_CONN_MTU = 3
private const val F_BLE_CONN_ERROR = 4

// BluetoothGATT*Request/Response — address is field 1 and handle is field 2 on every one of
// these (BluetoothGATTReadRequest, WriteRequest, ReadDescriptorRequest, WriteDescriptorRequest,
// NotifyRequest, ReadResponse, WriteResponse, NotifyDataResponse, NotifyResponse,
// ErrorResponse), same "shared base fields" pattern as F_ENTITY_KEY elsewhere in this file.
private const val F_BLE_GATT_ADDRESS = 1
private const val F_BLE_GATT_HANDLE = 2
private const val F_BLE_GATT_WRITE_RESPONSE_WANTED = 3 // WriteRequest's `response` bool
private const val F_BLE_GATT_WRITE_DATA = 4            // WriteRequest's `data` (ReadDescriptorRequest has none; WriteDescriptorRequest's data is field 3)
private const val F_BLE_GATT_WRITE_DESC_DATA = 3
private const val F_BLE_GATT_NOTIFY_ENABLE = 3          // NotifyRequest's `enable` bool
private const val F_BLE_GATT_READ_DATA = 3              // ReadResponse / NotifyDataResponse `data`
private const val F_BLE_GATT_ERROR = 3                  // ErrorResponse `error`

// BluetoothGATTGetServicesResponse / *Service / *Characteristic / *Descriptor
private const val F_BLE_SERVICES_ADDRESS = 1
private const val F_BLE_SERVICES_LIST = 2
private const val F_BLE_SVC_UUID = 1
private const val F_BLE_SVC_HANDLE = 2
private const val F_BLE_SVC_CHARACTERISTICS = 3
private const val F_BLE_SVC_SHORT_UUID = 4
private const val F_BLE_CHAR_UUID = 1
private const val F_BLE_CHAR_HANDLE = 2
private const val F_BLE_CHAR_PROPERTIES = 3
private const val F_BLE_CHAR_DESCRIPTORS = 4
private const val F_BLE_CHAR_SHORT_UUID = 5
private const val F_BLE_DESC_UUID = 1
private const val F_BLE_DESC_HANDLE = 2
private const val F_BLE_DESC_SHORT_UUID = 3

// BluetoothConnectionsFreeResponse
private const val F_BLE_CONNFREE_FREE = 1
private const val F_BLE_CONNFREE_LIMIT = 2

// BluetoothDevicePairingResponse / UnpairingResponse / ClearCacheResponse — address(1) shared,
// then a bool (paired/success) at field 2 and error at field 3 on every one of them.
private const val F_BLE_PAIR_ADDRESS = 1
private const val F_BLE_PAIR_RESULT = 2
private const val F_BLE_PAIR_ERROR = 3

// ListEntitiesUpdateResponse fields beyond the shared object_id/key/name
private const val F_UPDATE_ICON = 5
private const val F_UPDATE_DISABLED_BY_DEFAULT = 6
private const val F_UPDATE_ENTITY_CATEGORY = 7
private const val F_UPDATE_DEVICE_CLASS = 8

// UpdateStateResponse
private const val F_UPDATE_MISSING_STATE = 2
private const val F_UPDATE_IN_PROGRESS = 3
private const val F_UPDATE_HAS_PROGRESS = 4
private const val F_UPDATE_PROGRESS = 5
private const val F_UPDATE_CURRENT_VERSION = 6
private const val F_UPDATE_LATEST_VERSION = 7
private const val F_UPDATE_TITLE = 8
private const val F_UPDATE_RELEASE_SUMMARY = 9
private const val F_UPDATE_RELEASE_URL = 10

// UpdateCommandRequest (client -> server) — key is the shared F_ENTITY_KEY (field 1)
private const val F_UPDATE_COMMAND = 2

// ListEntitiesCameraResponse fields beyond the shared object_id/key/name
private const val F_CAMERA_DISABLED_BY_DEFAULT = 5
private const val F_CAMERA_ICON = 6
private const val F_CAMERA_ENTITY_CATEGORY = 7

// CameraImageResponse (server -> client)
private const val F_CAMERA_IMAGE_DATA = 2
private const val F_CAMERA_IMAGE_DONE = 3

// CameraImageRequest (client -> server)
private const val F_CAMERA_REQUEST_SINGLE = 1
private const val F_CAMERA_REQUEST_STREAM = 2

// ListEntitiesNumberResponse fields beyond the shared object_id/key/name
private const val F_NUMBER_ICON = 5
private const val F_NUMBER_MIN_VALUE = 6
private const val F_NUMBER_MAX_VALUE = 7
private const val F_NUMBER_STEP = 8
private const val F_NUMBER_DISABLED_BY_DEFAULT = 9
private const val F_NUMBER_ENTITY_CATEGORY = 10

// NumberStateResponse
private const val F_NUMBER_STATE = 2

// NumberCommandRequest (client -> server)
private const val F_NUMBER_COMMAND_STATE = 2

// ListEntitiesSelectResponse fields beyond the shared object_id/key/name
private const val F_SELECT_ICON = 5
private const val F_SELECT_OPTIONS = 6
private const val F_SELECT_DISABLED_BY_DEFAULT = 7
private const val F_SELECT_ENTITY_CATEGORY = 8

// ListEntitiesButtonResponse fields beyond the shared object_id/key/name
private const val F_BUTTON_ICON = 5
private const val F_BUTTON_DISABLED_BY_DEFAULT = 6
private const val F_BUTTON_ENTITY_CATEGORY = 7
private const val F_BUTTON_DEVICE_CLASS = 8

// ListEntitiesSwitchResponse fields beyond the shared object_id/key/name
private const val F_SWITCH_ICON = 5
private const val F_SWITCH_ASSUMED_STATE = 6
private const val F_SWITCH_DISABLED_BY_DEFAULT = 7
private const val F_SWITCH_ENTITY_CATEGORY = 8
private const val F_SWITCH_DEVICE_CLASS = 9

// SwitchStateResponse
private const val F_SWITCH_STATE = 2

// SwitchCommandRequest (client -> server)
private const val F_SWITCH_COMMAND_STATE = 2

// SelectStateResponse
private const val F_SELECT_STATE = 2
private const val F_SELECT_MISSING_STATE = 3

// SelectCommandRequest (client -> server)
private const val F_SELECT_COMMAND_STATE = 2

// ListEntitiesMediaPlayerResponse fields beyond the shared object_id/key/name
private const val F_MEDIA_PLAYER_ICON = 5
private const val F_MEDIA_PLAYER_DISABLED_BY_DEFAULT = 6
private const val F_MEDIA_PLAYER_ENTITY_CATEGORY = 7
private const val F_MEDIA_PLAYER_SUPPORTS_PAUSE = 8
private const val F_MEDIA_PLAYER_FEATURE_FLAGS = 11

// MediaPlayerStateResponse
private const val F_MEDIA_PLAYER_STATE = 2
private const val F_MEDIA_PLAYER_VOLUME = 3
private const val F_MEDIA_PLAYER_MUTED = 4

// MediaPlayerCommandRequest (client -> server)
private const val F_MPCMD_HAS_COMMAND = 2
private const val F_MPCMD_COMMAND = 3
private const val F_MPCMD_VOLUME = 5
private const val F_MPCMD_MEDIA_URL = 7
private const val F_MPCMD_ANNOUNCEMENT = 9

// Player state constants (MediaPlayerState in api.proto)
private const val STATE_IDLE = 1
private const val STATE_PLAYING = 2
private const val STATE_PAUSED = 3

// is this function is out of place and should be moved lower?
private fun MediaPlayerService.PlaybackState.toWire(): Int = when (this) {
  MediaPlayerService.PlaybackState.IDLE -> STATE_IDLE
  MediaPlayerService.PlaybackState.PLAYING -> STATE_PLAYING
  MediaPlayerService.PlaybackState.PAUSED -> STATE_PAUSED
}

// MediaPlayerCommandRequest command values
private const val COMMAND_PLAY = 0
private const val COMMAND_PAUSE = 1
private const val COMMAND_STOP = 2
private const val COMMAND_MUTE = 3
private const val COMMAND_UNMUTE = 4

// Feature flags (values match HA's own MediaPlayerEntityFeature bitmask, which is what
// ESPHome's feature_flags is interpreted as on the HA side)
private const val FEATURE_PAUSE = 1
private const val FEATURE_VOLUME_SET = 4
private const val FEATURE_VOLUME_MUTE = 8
private const val FEATURE_STOP = 4096
private const val FEATURE_PLAY_MEDIA = 512
private const val FEATURE_PLAY = 16384
private const val FEATURE_MEDIA_ANNOUNCE = 1048576

private inline fun <reified T> Map<Int, Any>.fieldOrNull(key: Int): T? = this[key] as? T





class AESPHome(context: Context, name: String? = null, friendlyName: String? = null, mac: String? = null, val port: Int = 6053) {

  internal val appContext         = context.applicationContext
  val name                        = name         ?: getDeviceHostname(appContext) // DNS-safe, used for mDNS + protocol name
  val friendlyName                = friendlyName ?: getDeviceName(appContext)     // raw device name, shown in HA's UI
  val mac                         = mac          ?: getDeviceMac(appContext)


  //private val mediaPlayer         = MediaPlayerController(appContext)


  private var activeConn: Socket? = null

  // Non-null exactly when the current activeConn is a Noise-encrypted connection (set once
  // performNoiseServerHandshake() succeeds, cleared together with activeConn on disconnect)
  // — readMessage()/send() below branch on this to pick plaintext vs. Noise framing for
  // whatever connection is currently active. There's only ever one at a time (this whole
  // class handles a single client connection, same as activeConn always has).
  private var noiseCiphers: CipherStatePair? = null

  // Read by CameraService's idle loop to skip capturing when nobody's listening,
  // without it needing to know what a connection actually is.
  val hasActiveConnection: Boolean get() = activeConn != null

  // Set once a client connects / says hello; cleared together when it disconnects.
  // Read by MainActivity to show who's currently connected.
  @Volatile var connectedClientAddress: String? = null
    private set
  @Volatile var connectedClientName: String? = null
    private set



  // Posts back to the main thread — handleClient() runs on a background socket thread,
  // and onMediaPlayerVolumeChanged below is meant to touch MainActivity's UI.
  private val mainHandler = Handler(Looper.getMainLooper())

  // Optional hook MainActivity registers (onResume, cleared in onPause) to be told
  // immediately when HA changes the media player volume, so the on-screen field reflects
  // it live instead of waiting for the next onResume() poll. Always invoked on the main thread.
  var onMediaPlayerVolumeChanged: ((Float) -> Unit)? = null

  // Let HA know the state of the media player
  private fun notifyMediaPlayerState() = pushToHA(MESSAGE_STATE_RESPONSE, mediaPlayerStatePayload())

  // Generic cache of every sensor's last known value, keyed by Sensor.id. Absent from this
  // map means "never reported" (SubscribeStatesRequest then reports it to HA as
  // missing_state, rather than a misleading 0), same as an explicit null read.
  private val sensorValues = HashMap<String, Float>()

  // Same idea as sensorValues, for TextSensor.
  private val sensorTextValues = HashMap<String, String>()

  // Called by MediaPlayerService when playback ends on its own — HA otherwise has no way to know.
  internal fun notifyMediaPlayerIdle() = notifyMediaPlayerState()


  //
  // Used by diagnostics loop (only?) to report sensor values to HA
  //
  fun reportSensor(sensor: Sensor, value: Boolean) = reportSensor(sensor, if (value) 1f else 0f)

  // value == null: the reading is genuinely unavailable right now (e.g. no Wi-Fi link) —
  // reported to HA as missing_state instead of a fabricated number. Binary sensors are
  // always non-null (start()/read() only ever report a real on/off) and keep their
  // only-send-on-change behavior; numeric sensors always send (including the transition
  // to/from unavailable).
  fun reportSensor(sensor: Sensor, value: Float?) {
    if (value != null && sensor.kind(appContext) is SensorKind.Binary && sensorValues[sensor.id] == value) return
    if (value != null) sensorValues[sensor.id] = value else sensorValues.remove(sensor.id)
    val (type, payload) = stateMessage(sensor, value)
    pushToHA(type, payload)
  }

  // Same missing-state convention as reportSensor, for TextSensor.
  fun reportTextSensor(sensor: TextSensor, value: String?) {
    if (value != null) sensorTextValues[sensor.id] = value else sensorTextValues.remove(sensor.id)
    val (type, payload) = textSensorStateMessage(sensor, value)
    pushToHA(type, payload)
  }



  //
  // Called by a SwitchEntity (e.g. BluetoothSwitch) when its underlying state changes
  // on its own, outside of an HA command — same idea as reportSensor, but for Switches.
  //
  fun reportSwitch(switch: SwitchEntity, on: Boolean) {
    val (type, payload) = switchStateMessage(switch, on)
    pushToHA(type, payload)
  }



  //
  // Threaded sender for HA connection
  //
  private fun pushToHA(msgType: Int, payload: ByteArray) {
    val conn = activeConn ?: return
    Thread({
      try {
        send(conn, msgType, payload)
      } catch (e: Exception) {
        Log.e(TAG, "State push failed: ${e.message}", e)
      }
    }, "AESPHomePush").start()
  }



  //
  // Helper functions for socket
  //
  private fun readVarintFromSocket(conn: Socket): Int = decodeVarint { recvExact(conn, 1)[0].toInt() }
  private fun recvExact(conn: Socket, count: Int): ByteArray {
    val data = ByteArray(count)
    var read = 0
    while (read < count) {
      val n = conn.getInputStream().read(data, read, count - read)
      if (n < 0) throw IOException("closed")
      read += n
    }
    return data
  }



  //
  // Dispatches on the connection's leading frame-indicator byte (0x00 plaintext, 0x01
  // Noise — see noise.kt) exactly once per connection: readMessage() is always called first
  // with noiseCiphers still null, so a Noise indicator on the very first read runs the full
  // handshake right here and then recurses to read the first real (now-encrypted) message
  // the exact same way any later one is read. Every subsequent call for this same connection
  // has noiseCiphers already set and skips straight to the Noise branch.
  //
  private fun readMessage(conn: Socket): Pair<Int, ByteArray> {
    noiseCiphers?.let { return readNoiseDataMessage(conn, it.getReceiver()) }

    val indicator = recvExact(conn, 1)[0].toInt() and 0xFF
    if (indicator == FRAME_INDICATOR_NOISE) {
      val psk = NoiseEncryptionSettings.getReadyPsk(appContext)
      if (psk == null) {
        rejectNoiseConnection(conn, "Noise encryption is not enabled on this device")
        throw IOException("Noise handshake attempted while disabled/unconfigured")
      }
      noiseCiphers = performNoiseServerHandshake(conn, psk, name, mac)
      return readMessage(conn)
    }
    if (indicator != FRAME_INDICATOR_PLAINTEXT) throw IOException("unexpected frame indicator $indicator")
    val length = readVarintFromSocket(conn)
    val msgType = readVarintFromSocket(conn)
    val payload = if (length > 0) recvExact(conn, length) else ByteArray(0)
    return Pair(msgType, payload)
  }



  //
  //
  //
  @Synchronized
  private fun send(conn: Socket, msgType: Int, payload: ByteArray) {
    noiseCiphers?.let { sendNoiseDataMessage(conn, it.getSender(), msgType, payload); return }
    val frame = byteArrayOf(0) + encodeVarint(payload.size) + encodeVarint(msgType) + payload
    conn.getOutputStream().write(frame)
  }



  //
  //
  //
  private fun mediaPlayerEntityListPayload(): ByteArray {
    val features = FEATURE_PAUSE + FEATURE_VOLUME_SET + FEATURE_VOLUME_MUTE + FEATURE_STOP + FEATURE_PLAY_MEDIA + FEATURE_PLAY + FEATURE_MEDIA_ANNOUNCE
    return ProtobufMessageBuilder()
      .string(F_OBJECT_ID, "media_player")
      .fixed32(F_KEY, MediaPlayerService.key)
      .string(F_NAME, "Media Player")
      .string(F_MEDIA_PLAYER_ICON, MediaPlayerService.icon)
      .varint(F_MEDIA_PLAYER_DISABLED_BY_DEFAULT, if (MediaPlayerService.enabledByDefaultHa) 0 else 1)
      .varint(F_MEDIA_PLAYER_ENTITY_CATEGORY, MediaPlayerService.entityCategory.wireValue)
      .varint(F_MEDIA_PLAYER_SUPPORTS_PAUSE, 1)
      .varint(F_MEDIA_PLAYER_FEATURE_FLAGS, features)
      .build()
  }




  //
  //
  //
  private fun cameraEntityListPayload(): Pair<Int, ByteArray> =
    MESSAGE_LIST_ENTITIES_CAMERA to ProtobufMessageBuilder()
      .string(F_OBJECT_ID, "camera")
      .fixed32(F_KEY, CameraService.key)
      .string(F_NAME, CameraService.label)
      .string(F_CAMERA_ICON, CameraService.icon)
      .varint(F_CAMERA_DISABLED_BY_DEFAULT, if (CameraService.enabledByDefaultHa) 0 else 1)
      .varint(F_CAMERA_ENTITY_CATEGORY, CameraService.entityCategory.wireValue)
      .build()



  // Sends a JPEG to whichever client is currently connected, chunked to CAMERA_CHUNK_SIZE.
  // A no-op if nobody's connected — safe for CameraService to call speculatively (e.g.
  // from its idle loop) without checking hasActiveConnection first.
  fun pushCameraFrame(jpeg: ByteArray) {
    val conn = activeConn ?: return
    val chunkSize = if (CAMERA_CHUNK_SIZE > 0) CAMERA_CHUNK_SIZE else jpeg.size

    var offset = 0
    while (offset < jpeg.size) {
      val end = minOf(offset + chunkSize, jpeg.size)
      val done = end == jpeg.size
      val chunkBytes = if (offset == 0 && done) jpeg else jpeg.copyOfRange(offset, end)

      val chunk = ProtobufMessageBuilder()
        .fixed32(F_ENTITY_KEY, CameraService.key)
        .bytes(F_CAMERA_IMAGE_DATA, chunkBytes)
        .varint(F_CAMERA_IMAGE_DONE, if (done) 1 else 0)
        .build()

      send(conn, MESSAGE_CAMERA_IMAGE_RESPONSE, chunk)
      offset = end
    }
  }



  // Settings belonging to a disabled component (e.g. camera rotation/lens when the
  // camera itself is off) shouldn't be reported either — gate on the owning
  // Toggleable's enabled state, not just each setting's own `homeAssistant` flag.
  private fun visibleSettings(): List<Setting> =
    Sensors.toggleables.filter { isEnabled(appContext, it) }.flatMap { it.settings }.filter { it.homeAssistant }

  private fun visibleSelectSettings(): List<SelectSetting> =
    Sensors.toggleables.filter { isEnabled(appContext, it) }.flatMap { it.selectSettings }.filter { it.homeAssistant }



  //
  //
  //
  private fun numberStateMessage(setting: Setting, value: Float): Pair<Int, ByteArray> =
      MESSAGE_NUMBER_STATE_RESPONSE to ProtobufMessageBuilder()
        .fixed32(F_ENTITY_KEY, setting.key)
        .float(F_NUMBER_STATE, value)
        .build()



  //
  // Forces HA to reconnect so it re-runs Hello -> DeviceInfo -> ListEntities ->
  // SubscribeStates from scratch, picking up any change to which entities exist
  // (e.g. a Sensor/Service toggled on or off) or their capabilities. Sent as a valid,
  // empty DisconnectRequest — reason defaults to UNSPECIFIED. api.proto marks
  // DisconnectRequest as SOURCE_BOTH, so it's valid for the device to send one
  // unprompted, not just in response to one from HA. HA acks with DisconnectResponse
  // and closes its side; our read loop then hits IOException on the closed socket and
  // cleans up via the existing catch/finally in handleClient(). Called from
  // MainActivity whenever a toggle changes.
  //
  fun requestDisconnect() = pushToHA(MESSAGE_DISCONNECT_REQUEST, ByteArray(0))



  //
  // Called from MainActivity when a setting changes on-device, inform HA
  //
  fun reportSetting(setting: Setting, value: Float) {
    if (setting === MediaPlayerService.volumeSetting) {
      MediaPlayerService.setVolume(value)
      // Volume lives on the media_player entity, not as a separate `number` entity in HA
      // (volumeSetting.homeAssistant is false on purpose), so it never reaches HA via the
      // numberStateMessage push below. Push the media player's state directly instead —
      // the same call the HA-command handler already makes to confirm its own changes.
      notifyMediaPlayerState()
      return
    }

    // This setting is not reported to HA
    if (!setting.homeAssistant){
      return
    }

    // Report new setting state to HA
    val (type, payload) = numberStateMessage(setting, value)
    pushToHA(type, payload)
  }



  //
  // Called from MainActivity when a dropdown setting changes on-device, inform HA
  //
  fun reportSelectSetting(setting: SelectSetting, value: String) {
    if (!setting.homeAssistant) return // this setting is not reported to HA
    val (type, payload) = selectStateMessage(setting, value)
    pushToHA(type, payload)
  }



  //
  //
  //
  private fun numberListMessage(setting: Setting): Pair<Int, ByteArray> =
    MESSAGE_LIST_ENTITIES_NUMBER to ProtobufMessageBuilder()
      .string(F_OBJECT_ID, setting.id)
      .fixed32(F_KEY, setting.key)
      .string(F_NAME, setting.label)
      .string(F_NUMBER_ICON, setting.icon)
      .float(F_NUMBER_MIN_VALUE, setting.min)
      .float(F_NUMBER_MAX_VALUE, setting.max)
      .float(F_NUMBER_STEP, setting.step)
      .varint(F_NUMBER_DISABLED_BY_DEFAULT, if (setting.enabledByDefaultHa) 0 else 1)
      .varint(F_NUMBER_ENTITY_CATEGORY, setting.entityCategory.wireValue)
      .build()



  //
  // Select entities render as a real dropdown in HA (unlike Number). `options` is a
  // repeated field — calling .string() once per option under the same field number is
  // exactly what protobuf's wire format expects for "repeated string".
  //
  private fun selectListMessage(setting: SelectSetting): Pair<Int, ByteArray> {
    val builder = ProtobufMessageBuilder()
      .string(F_OBJECT_ID, setting.id)
      .fixed32(F_KEY, setting.key)
      .string(F_NAME, setting.label)
      .string(F_SELECT_ICON, setting.icon)
    for (option in setting.options) builder.string(F_SELECT_OPTIONS, option)
    builder
      .varint(F_SELECT_DISABLED_BY_DEFAULT, if (setting.enabledByDefaultHa) 0 else 1)
      .varint(F_SELECT_ENTITY_CATEGORY, setting.entityCategory.wireValue)
    return MESSAGE_LIST_ENTITIES_SELECT to builder.build()
  }

  private fun selectStateMessage(setting: SelectSetting, value: String): Pair<Int, ByteArray> =
    MESSAGE_SELECT_STATE_RESPONSE to ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, setting.key)
      .string(F_SELECT_STATE, value)
      .varint(F_SELECT_MISSING_STATE, 0)
      .build()



  //
  // Buttons are press-only — no state message exists for them, unlike every other
  // entity type here. HA just calls press() and moves on.
  //
  private fun buttonListMessage(button: Button): Pair<Int, ByteArray> =
    MESSAGE_LIST_ENTITIES_BUTTON to ProtobufMessageBuilder()
      .string(F_OBJECT_ID, button.id)
      .fixed32(F_KEY, button.key)
      .string(F_NAME, button.label)
      .string(F_BUTTON_ICON, button.icon)
      .varint(F_BUTTON_DISABLED_BY_DEFAULT, if (button.enabledByDefaultHa) 0 else 1)
      .varint(F_BUTTON_ENTITY_CATEGORY, button.entityCategory.wireValue)
      .string(F_BUTTON_DEVICE_CLASS, "")
      .build()



  //
  // Switches are the only entity type here that's genuinely two-way: HA can flip them
  // (SwitchCommandRequest) and the underlying state can also change on its own, on the
  // device (see SwitchEntity.start(), which reports that back via reportSwitch()).
  //
  private fun switchListMessage(switch: SwitchEntity): Pair<Int, ByteArray> =
    MESSAGE_LIST_ENTITIES_SWITCH to ProtobufMessageBuilder()
      .string(F_OBJECT_ID, switch.id)
      .fixed32(F_KEY, switch.key)
      .string(F_NAME, switch.label)
      .string(F_SWITCH_ICON, switch.icon)
      .varint(F_SWITCH_ASSUMED_STATE, 0) // false: HA should trust our reported state, not show it as an assumed toggle
      .varint(F_SWITCH_DISABLED_BY_DEFAULT, if (switch.enabledByDefaultHa) 0 else 1)
      .varint(F_SWITCH_ENTITY_CATEGORY, switch.entityCategory.wireValue)
      .string(F_SWITCH_DEVICE_CLASS, "")
      .build()

  private fun switchStateMessage(switch: SwitchEntity, on: Boolean): Pair<Int, ByteArray> =
    MESSAGE_SWITCH_STATE_RESPONSE to ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, switch.key)
      .varint(F_SWITCH_STATE, if (on) 1 else 0)
      .build()



  //
  // update — see UpdateEntity's doc comment (Sensor.kt). Unlike every other entity type here,
  // its state is never derived on demand from a live read — it only ever changes when the
  // owning UpdateEntity calls pushUpdateState() (a check completed, an install started/
  // progressed/finished), so the last value has to be cached here for SubscribeStatesRequest
  // to answer correctly, the same role sensorValues/sensorTextValues play for Sensor/TextSensor.
  //
  private val updateStates = HashMap<String, UpdateState>() // keyed by UpdateEntity.id

  private fun updateListMessage(entity: UpdateEntity): Pair<Int, ByteArray> =
    MESSAGE_LIST_ENTITIES_UPDATE to ProtobufMessageBuilder()
      .string(F_OBJECT_ID, entity.id)
      .fixed32(F_KEY, entity.key)
      .string(F_NAME, entity.label)
      .string(F_UPDATE_ICON, entity.icon)
      .varint(F_UPDATE_DISABLED_BY_DEFAULT, if (entity.enabledByDefaultHa) 0 else 1)
      .varint(F_UPDATE_ENTITY_CATEGORY, entity.entityCategory.wireValue)
      .string(F_UPDATE_DEVICE_CLASS, "firmware")
      .build()

  // state == null: never checked yet (missing_state=true) — HA shows "unknown" rather than a
  // fabricated "up to date"/"update available" guess.
  private fun updateStateMessage(entity: UpdateEntity, state: UpdateState?): Pair<Int, ByteArray> {
    val builder = ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, entity.key)
      .varint(F_UPDATE_MISSING_STATE, if (state == null) 1 else 0)
      .string(F_UPDATE_TITLE, entity.label)
    if (state != null) {
      builder
        .varint(F_UPDATE_IN_PROGRESS, if (state.inProgress) 1 else 0)
        .varint(F_UPDATE_HAS_PROGRESS, if (state.progress != null) 1 else 0)
        .float(F_UPDATE_PROGRESS, state.progress ?: 0f)
        .string(F_UPDATE_CURRENT_VERSION, state.currentVersion)
        .string(F_UPDATE_LATEST_VERSION, state.latestVersion)
        .string(F_UPDATE_RELEASE_SUMMARY, state.releaseSummary)
        .string(F_UPDATE_RELEASE_URL, state.releaseUrl)
    }
    return MESSAGE_UPDATE_STATE_RESPONSE to builder.build()
  }

  // Called from whatever background thread AutoUpdateService's check/download already runs
  // on (never the main thread) — same direct-send style as the Bluetooth GATT push functions,
  // no extra Thread spawned per call.
  fun pushUpdateState(entity: UpdateEntity, state: UpdateState) {
    updateStates[entity.id] = state
    val conn = activeConn ?: return
    try {
      val (type, payload) = updateStateMessage(entity, state)
      send(conn, type, payload)
    } catch (e: Exception) { Log.e(TAG, "Update state push failed: ${e.message}") }
  }



  //
  // text_sensor — same shape as Sensor's binary_sensor/sensor pair, minus a `kind`, plus
  // missing_state for "genuinely unavailable right now" instead of a fabricated string.
  //
  private fun textSensorListMessage(sensor: TextSensor): Pair<Int, ByteArray> =
    MESSAGE_LIST_ENTITIES_TEXT_SENSOR to ProtobufMessageBuilder()
      .string(F_OBJECT_ID, sensor.id)
      .fixed32(F_KEY, sensor.key)
      .string(F_NAME, sensor.label)
      .string(F_TEXT_SENSOR_ICON, sensor.icon)
      .varint(F_TEXT_SENSOR_DISABLED_BY_DEFAULT, if (sensor.enabledByDefaultHa) 0 else 1)
      .varint(F_TEXT_SENSOR_ENTITY_CATEGORY, sensor.entityCategory.wireValue)
      .build()

  private fun textSensorStateMessage(sensor: TextSensor, value: String?): Pair<Int, ByteArray> =
    MESSAGE_TEXT_SENSOR_STATE to ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, sensor.key)
      .string(F_TEXT_SENSOR_STATE, value ?: "")
      .varint(F_TEXT_SENSOR_MISSING_STATE, if (value == null) 1 else 0)
      .build()



  //
  // Builds the ListEntities*Response (type + payload)
  //
  private fun listMessage(sensor: Sensor): Pair<Int, ByteArray> {
    val base = ProtobufMessageBuilder()
      .string(F_OBJECT_ID, sensor.id)
      .fixed32(F_KEY, sensor.key)
      .string(F_NAME, sensor.label)

    val disabledByDefault = if (sensor.enabledByDefaultHa) 0 else 1

    return when (val kind = sensor.kind(appContext)) {

      is SensorKind.Binary -> {
        val builder = base
          .string(F_BINARY_SENSOR_ICON, sensor.icon)
          .varint(F_BINARY_SENSOR_DISABLED_BY_DEFAULT, disabledByDefault)
          .varint(F_BINARY_SENSOR_ENTITY_CATEGORY, sensor.entityCategory.wireValue)
        if (kind.deviceClass != null) {
          builder.string(F_BINARY_SENSOR_DEVICE_CLASS, kind.deviceClass)
        }
        MESSAGE_LIST_ENTITIES_BINARY_SENSOR to builder.build()
      }

      is SensorKind.Numeric ->
        // state_class=1 (STATE_CLASS_MEASUREMENT); accuracy_decimals left at 0 (default).
        MESSAGE_LIST_ENTITIES_SENSOR to ProtobufMessageBuilder()
          .string(F_OBJECT_ID, sensor.id)
          .fixed32(F_KEY, sensor.key)
          .string(F_NAME, sensor.label)
          .string(F_SENSOR_ICON, sensor.icon)
          .string(F_SENSOR_UNIT, kind.unit)
          .varint(F_SENSOR_ACCURACY_DECIMALS, 0)
          .string(F_SENSOR_DEVICE_CLASS, kind.deviceClass)
          .varint(F_SENSOR_STATE_CLASS, 1)
          .varint(F_SENSOR_DISABLED_BY_DEFAULT, disabledByDefault)
          .varint(F_SENSOR_ENTITY_CATEGORY, sensor.entityCategory.wireValue)
          .build()
    }
  }



  //
  //
  //
  private fun stateMessage(sensor: Sensor, value: Float?): Pair<Int, ByteArray> = when (sensor.kind(appContext)) {
    is SensorKind.Binary -> MESSAGE_BINARY_SENSOR_STATE to ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, sensor.key)
      .varint(F_BINARY_SENSOR_STATE, if ((value ?: 0f) != 0f) 1 else 0)
      .build()
    is SensorKind.Numeric -> MESSAGE_SENSOR_STATE to ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, sensor.key)
      .float(F_SENSOR_STATE, value ?: 0f)
      .varint(F_SENSOR_MISSING_STATE, if (value == null) 1 else 0)
      .build()
  }



  //
  //
  //
  private fun mediaPlayerStatePayload(): ByteArray =
    ProtobufMessageBuilder()
      .fixed32(F_ENTITY_KEY, MediaPlayerService.key)
      .varint(F_MEDIA_PLAYER_STATE, MediaPlayerService.getState().toWire())
      .float(F_MEDIA_PLAYER_VOLUME, MediaPlayerService.getVolume())
      .varint(F_MEDIA_PLAYER_MUTED, if (MediaPlayerService.isMuted()) 1 else 0)
      .build()





  //
  //
  //
  private fun handleClient(conn: Socket) {
    activeConn = conn
    connectedClientAddress = conn.inetAddress?.hostAddress
    try {
      while (true) {
        val (msgType, payload) = readMessage(conn)
        when (msgType) {


          //
          // HelloRequest: initial handshake — confirm the API version and report our name.
          // Also records the client's self-reported name (e.g. "Home Assistant 2024.8.0"),
          // shown alongside its IP in MainActivity.
          //
          MESSAGE_HELLO_REQUEST -> {
            connectedClientName = decodeFields(payload).fieldOrNull<ByteArray>(F_HELLO_REQUEST_CLIENT_INFO)?.let { String(it) }
            send(conn, MESSAGE_HELLO_RESPONSE,
              ProtobufMessageBuilder()
                .varint(F_HELLO_API_VERSION_MAJOR, 1)
                .varint(F_HELLO_API_VERSION_MINOR, 9)
                .string(F_HELLO_SERVER_INFO, "aesphome")
                .string(F_HELLO_NAME, name)
                .build())
          }


          //
          // DeviceInfoRequest: static identity info about this device (name, MAC, versions).
          //
          MESSAGE_DEVICE_INFO_REQUEST -> {
            val builder = ProtobufMessageBuilder()
              .string(F_DEVICE_INFO_NAME, name)
              .string(F_DEVICE_INFO_MAC_ADDRESS, mac)
              .string(F_DEVICE_INFO_ESPHOME_VERSION, esphomeVersion)
              .string(F_DEVICE_INFO_MODEL, "Android Simulating ESPHome Device")
              .string(F_DEVICE_INFO_MANUFACTURER, "ÆSPHome")
              .string(F_DEVICE_INFO_FRIENDLY_NAME, friendlyName)
            // Only advertised when the Bluetooth proxy switch is actually enabled — an idle
            // client shouldn't see a capability this device isn't offering right now.
            if (isEnabled(appContext, BluetoothProxySwitch)) {
              builder.varint(F_DEVICE_INFO_BLUETOOTH_PROXY_FEATURE_FLAGS,
                BLUETOOTH_PROXY_FEATURE_PASSIVE_SCAN or BLUETOOTH_PROXY_FEATURE_RAW_ADVERTISEMENTS or BLUETOOTH_PROXY_FEATURE_ACTIVE_CONNECTIONS)
            }
            send(conn, MESSAGE_DEVICE_INFO_RESPONSE, builder.build())
          }


          //
          // ListEntitiesRequest: enumerate every entity this device exposes — media player,
          // camera, each Sensor, and every HA-facing Setting — ending with ListEntitiesDone.
          //
          MESSAGE_LIST_ENTITIES_REQUEST -> { 
            if (isEnabled(appContext, MediaPlayerService)){
              send(conn, MESSAGE_LIST_ENTITIES_RESPONSE, mediaPlayerEntityListPayload())
            }
            if (isEnabled(appContext, CameraService)) {
              val (type, bytes) = cameraEntityListPayload()
              send(conn, type, bytes)
            }
            for (sensor in Sensors.all.filter { isEnabled(appContext, it) && it.isAvailable(appContext) }) {
              val (type, bytes) = listMessage(sensor)
              send(conn, type, bytes)
            }
            for (sensor in Sensors.textSensors.filter { isEnabled(appContext, it) && it.isAvailable(appContext) }) {
              val (type, bytes) = textSensorListMessage(sensor)
              send(conn, type, bytes)
            }
            for (setting in visibleSettings()) {
              val (type, bytes) = numberListMessage(setting)
              send(conn, type, bytes)
            }
            for (setting in visibleSelectSettings()) {
              val (type, bytes) = selectListMessage(setting)
              send(conn, type, bytes)
            }
            for (button in Sensors.buttons.filter { isEnabled(appContext, it) && it.isAvailable(appContext) }) {
              val (type, bytes) = buttonListMessage(button)
              send(conn, type, bytes)
            }
            for (switch in Sensors.switches.filter { isEnabled(appContext, it) && it.isAvailable(appContext) }) {
              val (type, bytes) = switchListMessage(switch)
              send(conn, type, bytes)
            }
            for (update in Sensors.updates.filter { isEnabled(appContext, it) && it.isAvailable(appContext) }) {
              val (type, bytes) = updateListMessage(update)
              send(conn, type, bytes)
            }
            send(conn, MESSAGE_LIST_ENTITIES_DONE, ByteArray(0))
          }


          //
          // SubscribeStatesRequest: send the current value of every entity once — further
          // updates arrive later via reportSensor/reportSetting/notifyMediaPlayerState as they happen.
          //
          MESSAGE_SUBSCRIBE_STATES_REQUEST -> {
            if (isEnabled(appContext, MediaPlayerService)){
              send(conn, MESSAGE_STATE_RESPONSE, mediaPlayerStatePayload())
            }
            for (sensor in Sensors.all.filter { isEnabled(appContext, it) && it.isAvailable(appContext) }) {
              val (type, bytes) = stateMessage(sensor, sensorValues[sensor.id]) // null -> missing_state, not fabricated 0
              send(conn, type, bytes)
            }
            for (sensor in Sensors.textSensors.filter { isEnabled(appContext, it) && it.isAvailable(appContext) }) {
              val (type, bytes) = textSensorStateMessage(sensor, sensorTextValues[sensor.id])
              send(conn, type, bytes)
            }
            for (setting in visibleSettings()) {
              val (type, bytes) = numberStateMessage(setting, getSetting(appContext, setting))
              send(conn, type, bytes)
            }
            for (setting in visibleSelectSettings()) {
              val (type, bytes) = selectStateMessage(setting, getSelectSetting(appContext, setting))
              send(conn, type, bytes)
            }
            for (switch in Sensors.switches.filter { isEnabled(appContext, it) && it.isAvailable(appContext) }) {
              val (type, bytes) = switchStateMessage(switch, switch.isOn(appContext))
              send(conn, type, bytes)
            }
            for (update in Sensors.updates.filter { isEnabled(appContext, it) && it.isAvailable(appContext) }) {
              val (type, bytes) = updateStateMessage(update, updateStates[update.id])
              send(conn, type, bytes)
            }
            // HA has now finished its handshake and is listening — wake CameraService's
            // idle loop (same mechanism idleFpsSetting's onChanged uses) so it sends an
            // idle still right away instead of waiting out the rest of the current interval.
            CameraService.interruptIdleLoop()
          }


          //
          // PingRequest: keep-alive — just echo back an empty PingResponse.
          //
          MESSAGE_PING_REQUEST -> send(conn, MESSAGE_PING_RESPONSE, ByteArray(0))


          //
          // DisconnectRequest: HA is ending the connection cleanly — acknowledge and stop.
          //
          MESSAGE_DISCONNECT_REQUEST -> {
            send(conn, MESSAGE_DISCONNECT_RESPONSE, ByteArray(0))
            return
          }


          //
          // MediaPlayerCommandRequest: play/pause/stop/volume/media_url from HA's media_player
          // entity. Always confirm the resulting state back so HA's UI reflects it immediately.
          //
          MESSAGE_MEDIA_PLAYER_COMMAND_REQUEST -> {
            if (isEnabled(appContext, MediaPlayerService)) {
              val fields = decodeFields(payload)

              val isAnnouncement = (fields.fieldOrNull<Int>(F_MPCMD_ANNOUNCEMENT) ?: 0) != 0
              fields.fieldOrNull<ByteArray>(F_MPCMD_MEDIA_URL)?.let { MediaPlayerService.play(String(it), isAnnouncement) }
              fields.fieldOrNull<ByteArray>(F_MPCMD_VOLUME)?.let {
                val vol = bytesToFloat(it)
                MediaPlayerService.setVolume(vol)
                setSetting(appContext, MediaPlayerService.volumeSetting, vol)
                mainHandler.post { onMediaPlayerVolumeChanged?.invoke(vol) }
              }

              if (fields.containsKey(F_MPCMD_HAS_COMMAND)) {
                when (fields.fieldOrNull<Int>(F_MPCMD_COMMAND) ?: COMMAND_PLAY) {
                  COMMAND_PAUSE -> MediaPlayerService.pause()  // no-op unless currently playing
                  COMMAND_PLAY -> MediaPlayerService.resume()  // no-op unless currently paused
                  COMMAND_STOP -> MediaPlayerService.stopPlayback()
                  COMMAND_MUTE -> MediaPlayerService.mute()
                  COMMAND_UNMUTE -> MediaPlayerService.unmute()
                }
              }

              send(conn, MESSAGE_STATE_RESPONSE, mediaPlayerStatePayload())
            }
          }


          //
          // CameraImageRequest: single=one-shot capture; stream=true starts (or refreshes) a
          // continuous stream that stops itself once HA stops re-requesting it.
          //
          MESSAGE_CAMERA_IMAGE_REQUEST -> {
            if (isEnabled(appContext, CameraService)) {
              val stream = (decodeFields(payload).fieldOrNull<Int>(F_CAMERA_REQUEST_STREAM) ?: 0) != 0
              CameraService.onImageRequest(appContext, stream)
            }
          }


          //
          // NumberCommandRequest: HA changed one of our exposed Settings — persist it and
          // confirm the new value back. The run{} block's early returns only abandon this one
          // case (unknown key/malformed value), not the whole connection.
          //
          MESSAGE_NUMBER_COMMAND_REQUEST -> run {
            val fields = decodeFields(payload)
            val key = fields.fieldOrNull<ByteArray>(F_ENTITY_KEY)?.let(::bytesToInt) ?: return@run
            val value = fields.fieldOrNull<ByteArray>(F_NUMBER_COMMAND_STATE)?.let(::bytesToFloat) ?: return@run
            val setting = Sensors.toggleables.flatMap { it.settings }.find { it.key == key } ?: return@run
            val clamped = setSetting(appContext, setting, value)
            val (type, bytes) = numberStateMessage(setting, clamped)
            send(conn, type, bytes)
          }


          //
          // SelectCommandRequest: HA changed one of our exposed dropdown Settings (e.g.
          // camera rotation) — persist it and confirm the new value back. Same pattern
          // as NumberCommandRequest, but the value is a string that must be one of the
          // entity's fixed `options` rather than any float in range. setSelectSetting
          // fires the setting's own onChanged hook (e.g. CameraService's lens/rotation/
          // resolution settings stop an active stream there) — nothing camera-specific
          // needs to happen here.
          //
          // If the setting is a command dropdown (onCommand != null, e.g. BluetoothCommandService)
          // the picked value is never persisted — it just fires onCommand, then the entity is
          // reported straight back to its default so it resets in HA after every use.
          //
          MESSAGE_SELECT_COMMAND_REQUEST -> run {
            val fields = decodeFields(payload)
            val key = fields.fieldOrNull<ByteArray>(F_ENTITY_KEY)?.let(::bytesToInt) ?: return@run
            val value = fields.fieldOrNull<ByteArray>(F_SELECT_COMMAND_STATE)?.let { String(it) } ?: return@run
            val setting = Sensors.toggleables.flatMap { it.selectSettings }.find { it.key == key } ?: return@run
            if (value !in setting.options) return@run
            val onCommand = setting.onCommand
            val reported = if (onCommand != null) {
              onCommand(appContext, value)
              setting.default
            } else {
              setSelectSetting(appContext, setting, value)
              value
            }
            val (type, bytes) = selectStateMessage(setting, reported)
            send(conn, type, bytes)
          }


          //
          // ButtonCommandRequest: HA pressed one of our exposed Button entities. Buttons
          // are press-only — there's no value to persist and nothing to report back.
          //
          MESSAGE_BUTTON_COMMAND_REQUEST -> run {
            val fields = decodeFields(payload)
            val key = fields.fieldOrNull<ByteArray>(F_ENTITY_KEY)?.let(::bytesToInt) ?: return@run
            val button = Sensors.buttons.find { it.key == key } ?: return@run
            button.press(appContext)
          }


          //
          // SwitchCommandRequest: HA flipped one of our exposed Switch entities. Applies
          // the change, then reports back whatever the switch's state actually is right
          // now — not just an echo of what was requested, since enabling/disabling
          // Bluetooth is asynchronous (and can fail outright on Android 13+). The
          // BroadcastReceiver each SwitchEntity registers in start() sends the real
          // final state shortly after, once the OS actually finishes the transition.
          //
          MESSAGE_SWITCH_COMMAND_REQUEST -> run {
            val fields = decodeFields(payload)
            val key = fields.fieldOrNull<ByteArray>(F_ENTITY_KEY)?.let(::bytesToInt) ?: return@run
            val on = (fields.fieldOrNull<Int>(F_SWITCH_COMMAND_STATE) ?: 0) != 0
            val switch = Sensors.switches.find { it.key == key } ?: return@run
            switch.setOn(appContext, on)
            val (type, bytes) = switchStateMessage(switch, switch.isOn(appContext))
            send(conn, type, bytes)
          }


          //
          // UpdateCommandRequest: HA's "Check"/"Install" buttons on the update card. No state
          // is sent back directly here — the entity's onCheckCommand/onUpdateCommand run
          // asynchronously (a network call, a download) and report their own result through
          // pushUpdateState() once they actually have one, same as every other async command
          // in this codebase (e.g. Bluetooth enable/disable via SwitchEntity).
          //
          MESSAGE_UPDATE_COMMAND_REQUEST -> run {
            val fields = decodeFields(payload)
            val key = fields.fieldOrNull<ByteArray>(F_ENTITY_KEY)?.let(::bytesToInt) ?: return@run
            val command = fields.fieldOrNull<Int>(F_UPDATE_COMMAND) ?: 0
            val entity = Sensors.updates.find { it.key == key } ?: return@run
            when (command) {
              1 -> entity.onUpdateCommand(appContext) // UPDATE_COMMAND_UPDATE
              2 -> entity.onCheckCommand(appContext)  // UPDATE_COMMAND_CHECK
            }
          }


          //
          // SubscribeBluetoothLEAdvertisementsRequest: HA's Bluetooth integration wants this
          // device to act as a passive BLE proxy — start forwarding whatever
          // BluetoothProxyService is (or isn't yet) scanning. A no-op if the proxy itself is
          // disabled; DeviceInfoResponse already didn't advertise the capability in that case,
          // so a well-behaved client shouldn't send this, but nothing bad happens if it does.
          //
          MESSAGE_SUBSCRIBE_BLE_ADVERTISEMENTS_REQUEST -> {
            bleSubscribed = true
            Log.i(TAG, "HA subscribed to Bluetooth LE advertisements")
          }


          //
          // UnsubscribeBluetoothLEAdvertisementsRequest: stop forwarding — connection teardown
          // (below) covers the same thing if HA disconnects without sending this explicitly.
          //
          MESSAGE_UNSUBSCRIBE_BLE_ADVERTISEMENTS_REQUEST -> {
            bleSubscribed = false
            Log.i(TAG, "HA unsubscribed from Bluetooth LE advertisements")
          }


          //
          // Bluetooth GATT proxy (active connections) — every case here just decodes the
          // request and hands it straight to BluetoothGattProxy (sensors/bluetooth_gatt.kt),
          // which owns the actual android.bluetooth.BluetoothGatt objects and calls back into
          // pushBleXxx() below once Android's async GATT callbacks actually complete.
          //
          MESSAGE_BLE_DEVICE_REQUEST -> run {
            val address = findVarintLongField(payload, F_BLE_DEV_ADDRESS) ?: return@run
            val requestType = decodeFields(payload).fieldOrNull<Int>(F_BLE_DEV_REQUEST_TYPE) ?: 0
            BluetoothGattProxy.handleDeviceRequest(appContext, address, requestType)
          }

          MESSAGE_BLE_GATT_GET_SERVICES_REQUEST -> run {
            val address = findVarintLongField(payload, F_BLE_SERVICES_ADDRESS) ?: return@run
            BluetoothGattProxy.handleGetServices(address)
          }

          MESSAGE_BLE_GATT_READ_REQUEST, MESSAGE_BLE_GATT_READ_DESCRIPTOR_REQUEST -> run {
            val address = findVarintLongField(payload, F_BLE_GATT_ADDRESS) ?: return@run
            val handle = decodeFields(payload).fieldOrNull<Int>(F_BLE_GATT_HANDLE) ?: return@run
            BluetoothGattProxy.handleRead(address, handle)
          }

          MESSAGE_BLE_GATT_WRITE_REQUEST -> run {
            val address = findVarintLongField(payload, F_BLE_GATT_ADDRESS) ?: return@run
            val fields = decodeFields(payload)
            val handle = fields.fieldOrNull<Int>(F_BLE_GATT_HANDLE) ?: return@run
            val data = fields.fieldOrNull<ByteArray>(F_BLE_GATT_WRITE_DATA) ?: ByteArray(0)
            val wantsResponse = (fields.fieldOrNull<Int>(F_BLE_GATT_WRITE_RESPONSE_WANTED) ?: 0) != 0
            BluetoothGattProxy.handleWrite(address, handle, data, wantsResponse)
          }

          MESSAGE_BLE_GATT_WRITE_DESCRIPTOR_REQUEST -> run {
            val address = findVarintLongField(payload, F_BLE_GATT_ADDRESS) ?: return@run
            val fields = decodeFields(payload)
            val handle = fields.fieldOrNull<Int>(F_BLE_GATT_HANDLE) ?: return@run
            val data = fields.fieldOrNull<ByteArray>(F_BLE_GATT_WRITE_DESC_DATA) ?: ByteArray(0)
            BluetoothGattProxy.handleWriteDescriptor(address, handle, data)
          }

          MESSAGE_BLE_GATT_NOTIFY_REQUEST -> run {
            val address = findVarintLongField(payload, F_BLE_GATT_ADDRESS) ?: return@run
            val fields = decodeFields(payload)
            val handle = fields.fieldOrNull<Int>(F_BLE_GATT_HANDLE) ?: return@run
            val enable = (fields.fieldOrNull<Int>(F_BLE_GATT_NOTIFY_ENABLE) ?: 0) != 0
            BluetoothGattProxy.handleNotify(address, handle, enable)
          }

          //
          // SubscribeBluetoothConnectionsFreeRequest: report once — this device has no ongoing
          // "state changed" push for it, unlike real ESPHome hardware's fixed connection-slot
          // count; free/limit here just reflects BluetoothGattProxy's own soft cap.
          //
          MESSAGE_SUBSCRIBE_BLE_CONNECTIONS_FREE_REQUEST -> {
            val used = BluetoothGattProxy.activeConnectionCount()
            send(conn, MESSAGE_BLE_CONNECTIONS_FREE_RESPONSE,
              ProtobufMessageBuilder()
                .varint(F_BLE_CONNFREE_FREE, (BLE_MAX_CONNECTIONS - used).coerceAtLeast(0))
                .varint(F_BLE_CONNFREE_LIMIT, BLE_MAX_CONNECTIONS)
                .build())
          }

        // end when
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Client handler error", e)
    } finally {
      MediaPlayerService.stopPlayback()
      CameraService.stopStreamNow()
      BluetoothGattProxy.stop() // no client left to read/write/notify through — clean slate on reconnect
      bleSubscribed = false
      activeConn = null
      noiseCiphers = null
      connectedClientAddress = null
      connectedClientName = null
      conn.close()
    }
  }



  //
  // Bluetooth LE passive proxy — BluetoothProxyService (sensors/bluetooth_proxy.kt) owns the
  // actual scanning and calls this for every advertisement it sees; this is a no-op unless
  // HA has actually subscribed on the current connection, so a scan running with nobody
  // listening doesn't spam a socket write for every single advertisement.
  //
  @Volatile private var bleSubscribed = false

  // Called directly on whatever thread the BLE scan callback fires on (never the main
  // thread — see BluetoothProxyService, which registers its callback on its own
  // HandlerThread) — a scan can report several advertisements a second, so unlike
  // reportSensor/pushToHA this deliberately does NOT spend a new Thread per call; send()
  // itself is @Synchronized, so concurrent callers just serialize on the one socket.
  fun pushBleAdvertisement(address: Long, rssi: Int, addressType: Int, data: ByteArray) {
    if (!bleSubscribed) return
    val conn = activeConn ?: return
    val advertisement = ProtobufMessageBuilder()
      .varintLong(F_BLE_ADV_ADDRESS, address)
      .varint(F_BLE_ADV_RSSI, rssi)
      .varint(F_BLE_ADV_ADDRESS_TYPE, addressType)
      .bytes(F_BLE_ADV_DATA, data)
      .build()
    val payload = ProtobufMessageBuilder().bytes(F_BLE_ADVERTISEMENTS, advertisement).build()
    try { send(conn, MESSAGE_BLE_RAW_ADVERTISEMENTS_RESPONSE, payload) }
    catch (e: Exception) { Log.e(TAG, "BLE advertisement push failed: ${e.message}") }
  }



  //
  // Bluetooth GATT proxy (active connections) — BluetoothGattProxy (sensors/bluetooth_gatt.kt)
  // owns the actual android.bluetooth.BluetoothGatt objects and calls straight into these from
  // its BluetoothGattCallback (already off the main thread — Android's own Binder callback
  // thread — so, like pushBleAdvertisement above, no extra Thread is spun up here either).
  //

  private fun encodeWireUuid(builder: ProtobufMessageBuilder, uuidField: Int, shortUuidField: Int, uuid: WireUuid) {
    if (uuid.shortUuid != null) {
      builder.varint(shortUuidField, uuid.shortUuid)
    } else {
      // repeated uint64 uuid = 2 (fixed_array_size), high half then low half.
      builder.varintLong(uuidField, uuid.high)
      builder.varintLong(uuidField, uuid.low)
    }
  }

  private fun buildGattDescriptor(msg: BleGattDescMsg): ByteArray {
    val builder = ProtobufMessageBuilder()
    encodeWireUuid(builder, F_BLE_DESC_UUID, F_BLE_DESC_SHORT_UUID, msg.uuid)
    builder.varint(F_BLE_DESC_HANDLE, msg.handle)
    return builder.build()
  }

  private fun buildGattCharacteristic(msg: BleGattCharMsg): ByteArray {
    val builder = ProtobufMessageBuilder()
    encodeWireUuid(builder, F_BLE_CHAR_UUID, F_BLE_CHAR_SHORT_UUID, msg.uuid)
    builder.varint(F_BLE_CHAR_HANDLE, msg.handle)
    builder.varint(F_BLE_CHAR_PROPERTIES, msg.properties)
    for (desc in msg.descriptors) builder.bytes(F_BLE_CHAR_DESCRIPTORS, buildGattDescriptor(desc))
    return builder.build()
  }

  private fun buildGattService(msg: BleGattServiceMsg): ByteArray {
    val builder = ProtobufMessageBuilder()
    encodeWireUuid(builder, F_BLE_SVC_UUID, F_BLE_SVC_SHORT_UUID, msg.uuid)
    builder.varint(F_BLE_SVC_HANDLE, msg.handle)
    for (char in msg.characteristics) builder.bytes(F_BLE_SVC_CHARACTERISTICS, buildGattCharacteristic(char))
    return builder.build()
  }

  fun pushBleDeviceConnection(address: Long, connected: Boolean, mtu: Int, error: Int) {
    val conn = activeConn ?: return
    try {
      send(conn, MESSAGE_BLE_DEVICE_CONNECTION_RESPONSE, ProtobufMessageBuilder()
        .varintLong(F_BLE_DEV_ADDRESS, address)
        .varint(F_BLE_CONN_CONNECTED, if (connected) 1 else 0)
        .varint(F_BLE_CONN_MTU, mtu)
        .varint(F_BLE_CONN_ERROR, error)
        .build())
    } catch (e: Exception) { Log.e(TAG, "BLE device connection push failed: ${e.message}") }
  }

  internal fun pushBleServices(address: Long, services: List<BleGattServiceMsg>) {
    val conn = activeConn ?: return
    try {
      for (service in services) {
        send(conn, MESSAGE_BLE_GATT_GET_SERVICES_RESPONSE, ProtobufMessageBuilder()
          .varintLong(F_BLE_SERVICES_ADDRESS, address)
          .bytes(F_BLE_SERVICES_LIST, buildGattService(service))
          .build())
      }
    } catch (e: Exception) { Log.e(TAG, "BLE services push failed: ${e.message}") }
    pushBleServicesDone(address)
  }

  fun pushBleServicesDone(address: Long) {
    val conn = activeConn ?: return
    try {
      send(conn, MESSAGE_BLE_GATT_GET_SERVICES_DONE_RESPONSE,
        ProtobufMessageBuilder().varintLong(F_BLE_SERVICES_ADDRESS, address).build())
    } catch (e: Exception) { Log.e(TAG, "BLE services-done push failed: ${e.message}") }
  }

  fun pushBleGattRead(address: Long, handle: Int, data: ByteArray) {
    val conn = activeConn ?: return
    try {
      send(conn, MESSAGE_BLE_GATT_READ_RESPONSE, ProtobufMessageBuilder()
        .varintLong(F_BLE_GATT_ADDRESS, address)
        .varint(F_BLE_GATT_HANDLE, handle)
        .bytes(F_BLE_GATT_READ_DATA, data)
        .build())
    } catch (e: Exception) { Log.e(TAG, "BLE GATT read push failed: ${e.message}") }
  }

  fun pushBleGattWriteResponse(address: Long, handle: Int) {
    val conn = activeConn ?: return
    try {
      send(conn, MESSAGE_BLE_GATT_WRITE_RESPONSE, ProtobufMessageBuilder()
        .varintLong(F_BLE_GATT_ADDRESS, address)
        .varint(F_BLE_GATT_HANDLE, handle)
        .build())
    } catch (e: Exception) { Log.e(TAG, "BLE GATT write-response push failed: ${e.message}") }
  }

  fun pushBleGattNotifyData(address: Long, handle: Int, data: ByteArray) {
    val conn = activeConn ?: return
    try {
      send(conn, MESSAGE_BLE_GATT_NOTIFY_DATA_RESPONSE, ProtobufMessageBuilder()
        .varintLong(F_BLE_GATT_ADDRESS, address)
        .varint(F_BLE_GATT_HANDLE, handle)
        .bytes(F_BLE_GATT_READ_DATA, data)
        .build())
    } catch (e: Exception) { Log.e(TAG, "BLE GATT notify-data push failed: ${e.message}") }
  }

  fun pushBleGattNotifyResponse(address: Long, handle: Int) {
    val conn = activeConn ?: return
    try {
      send(conn, MESSAGE_BLE_GATT_NOTIFY_RESPONSE, ProtobufMessageBuilder()
        .varintLong(F_BLE_GATT_ADDRESS, address)
        .varint(F_BLE_GATT_HANDLE, handle)
        .build())
    } catch (e: Exception) { Log.e(TAG, "BLE GATT notify-response push failed: ${e.message}") }
  }

  fun pushBleGattError(address: Long, handle: Int, error: Int) {
    val conn = activeConn ?: return
    try {
      send(conn, MESSAGE_BLE_GATT_ERROR_RESPONSE, ProtobufMessageBuilder()
        .varintLong(F_BLE_GATT_ADDRESS, address)
        .varint(F_BLE_GATT_HANDLE, handle)
        .varint(F_BLE_GATT_ERROR, error)
        .build())
    } catch (e: Exception) { Log.e(TAG, "BLE GATT error push failed: ${e.message}") }
  }

  fun pushBlePairingResponse(address: Long, paired: Boolean, error: Int) {
    val conn = activeConn ?: return
    try {
      send(conn, MESSAGE_BLE_DEVICE_PAIRING_RESPONSE, ProtobufMessageBuilder()
        .varintLong(F_BLE_PAIR_ADDRESS, address)
        .varint(F_BLE_PAIR_RESULT, if (paired) 1 else 0)
        .varint(F_BLE_PAIR_ERROR, error)
        .build())
    } catch (e: Exception) { Log.e(TAG, "BLE pairing-response push failed: ${e.message}") }
  }

  fun pushBleUnpairingResponse(address: Long, success: Boolean, error: Int) {
    val conn = activeConn ?: return
    try {
      send(conn, MESSAGE_BLE_DEVICE_UNPAIRING_RESPONSE, ProtobufMessageBuilder()
        .varintLong(F_BLE_PAIR_ADDRESS, address)
        .varint(F_BLE_PAIR_RESULT, if (success) 1 else 0)
        .varint(F_BLE_PAIR_ERROR, error)
        .build())
    } catch (e: Exception) { Log.e(TAG, "BLE unpairing-response push failed: ${e.message}") }
  }

  fun pushBleClearCacheResponse(address: Long, success: Boolean, error: Int) {
    val conn = activeConn ?: return
    try {
      send(conn, MESSAGE_BLE_DEVICE_CLEAR_CACHE_RESPONSE, ProtobufMessageBuilder()
        .varintLong(F_BLE_PAIR_ADDRESS, address)
        .varint(F_BLE_PAIR_RESULT, if (success) 1 else 0)
        .varint(F_BLE_PAIR_ERROR, error)
        .build())
    } catch (e: Exception) { Log.e(TAG, "BLE clear-cache-response push failed: ${e.message}") }
  }



  //
  // Report some sensors every 60 seconds in thread
  //
  private fun diagnosticsLoop() {
    while (true) {
      for (sensor in Sensors.readSensors) {
        if (isEnabled(appContext, sensor) && sensor.isAvailable(appContext)) {
          reportSensor(sensor, sensor.read(appContext))
        }
      }
      for (sensor in Sensors.readTextSensors) {
        if (isEnabled(appContext, sensor) && sensor.isAvailable(appContext)) {
          reportTextSensor(sensor, sensor.read(appContext))
        }
      }
      Thread.sleep(60000)
    }
  }



  // One connection at a time. Bound to the Wi-Fi IP specifically (not left as 0.0.0.0),
  // so the API server is never reachable over cellular — same reasoning as mdns.kt's
  // getWifiNetwork() binding. Retries until Wi-Fi has an address, and re-binds if it's lost.
  fun start() {
    Thread({ diagnosticsLoop() }, "AESPHomeDiagnostics").start()
    while (true) {
      val wifiIp = getWifiIpAddress()
      if (wifiIp == null) {
        Log.e(TAG, "No Wi-Fi IP yet, retrying...")
        Thread.sleep(5000)
        continue
      }
      try {
        val server = ServerSocket()
        server.bind(InetSocketAddress(InetAddress.getByName(wifiIp), port))
        while (true) {
          val conn = server.accept()
          val clientThread = Thread({ handleClient(conn) }, "AESPHomeClient")
          clientThread.start()
          clientThread.join()
        }
      } catch (e: IOException) {
        Log.e(TAG, "API server failed, retrying: ${e.message}")
        Thread.sleep(5000)
      }
    }
  }
}
