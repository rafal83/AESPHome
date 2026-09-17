# Bluetooth Proxy

## What's implemented: passive scan

`switch.bluetooth_proxy` (`app/src/main/java/com/aesphome/sensors/bluetooth_proxy.kt`) makes
AESPHome show up to Home Assistant's Bluetooth integration the same way a real ESPHome
Bluetooth Proxy does:

1. `DeviceInfoResponse` (`esphome.kt`) advertises
   `bluetooth_proxy_feature_flags = PASSIVE_SCAN | RAW_ADVERTISEMENTS` (`1 | 32`) whenever the
   switch is enabled — the same bitmask `aioesphomeapi`'s `BluetoothProxyFeature` enum defines.
2. HA subscribes with `SubscribeBluetoothLEAdvertisementsRequest` (id 66).
3. Every BLE advertisement Android's own `BluetoothLeScanner` sees is forwarded as a
   `BluetoothLERawAdvertisementsResponse` (id 93) — address, RSSI, address type, and the raw
   advertisement data bytes (`ScanResult.scanRecord.bytes`), exactly the wire format current
   ESPHome firmware uses (the older non-raw `BluetoothLEAdvertisementResponse`, id 67, was
   removed upstream in ESPHome 2025.8.0, so this device only ever speaks the raw format).

All message ids and field numbers above were verified against upstream ESPHome's
`esphome/components/api/api.proto` (`dev` branch) at the time this was written, not guessed.

The MAC address is packed into the `uint64 address` field the same way ESPHome's own C++
firmware does it (`esp32_ble/ble.h`'s `ble_addr_to_uint64`: the first octet in the
most-significant position) — see `macStringToLong()` in `utils.kt` and
`ProtobufTest.kt`/`UtilsTest.kt` for the verified byte order.

Permissions: `BLUETOOTH_SCAN` (Android 12+, flagged `neverForLocation` since this app never
derives location from scan results) or `ACCESS_FINE_LOCATION` (Android ≤11, required by the
OS to receive scan results at all). Both are requested only from the Permissions screen.

## What's NOT implemented

- **Active GATT connections** (`BluetoothProxyFeature.ACTIVE_CONNECTIONS`) — HA connecting
  *through* this device to a remote BLE peripheral (reading/writing characteristics,
  subscribing to notifications). This is what lets HA integrations that need an actual GATT
  session (e.g. some BLE locks/trackers) work through a proxy, as opposed to integrations that
  only need advertisement data (e.g. most BLE thermometers/presence sensors, which is what
  passive scan already fully supports).
- **Pairing** (`PAIRING`) and **cache clearing** (`CACHE_CLEARING`).
- **Remote GATT caching** (`REMOTE_CACHING`).
- `BluetoothScannerStateResponse` / `BluetoothScannerSetModeRequest` (ids 126/127) — optional
  informational messages a real ESPHome device sends about its scanner's own state; this
  device's scanner state is only visible locally (`binary_sensor` isn't wired for it — the
  switch's own on/off already reflects whether scanning is requested).

## Why active connections aren't attempted here

Implementing `BluetoothDeviceRequest`/`BluetoothGATTGetServicesRequest`/
`BluetoothGATTReadRequest`/`BluetoothGATTWriteRequest`/`BluetoothGATTNotifyRequest` (ids
68-79, 82-84) correctly means:

- Managing Android's own `BluetoothGatt` connection state machine per remote device
  (connect/discover-services/read/write/notify, each genuinely asynchronous, each with its
  own Android-version-dependent quirks and a real risk of the stack wedging on some OEMs).
- Mapping ESPHome's 128-bit/16-bit UUID short-form encoding (`short_uuid` in
  `BluetoothGATTDescriptor`/`Characteristic`/`Service`, added in API 1.12) faithfully in both
  directions.
- A connection-slot accounting protocol (`SubscribeBluetoothConnectionsFreeRequest` /
  `BluetoothConnectionsFreeResponse`) so HA knows how many simultaneous connections this
  proxy can still take — real ESPHome hardware has a small, fixed limit; an Android phone's
  practical limit is different and not well-characterized without real device testing across
  several BLE chipsets.

This is a genuinely separate, much larger feature than passive scanning, with a much higher
risk of subtle correctness bugs that are hard to verify without a matrix of real BLE
peripherals and Android versions to test against. Passive scan alone already covers the
common "many battery/environmental BLE sensors advertising in range" use case this project's
target hardware (an old phone/tablet as a whole-house Bluetooth listening post) is best
suited for.

## If implementing active connections later

- Start from `BluetoothDeviceRequest`/`BluetoothDeviceConnectionResponse` (ids 68/69) only —
  connect/disconnect — before touching GATT read/write/notify at all, and test against one
  well-behaved BLE peripheral before generalizing.
- `android.bluetooth.BluetoothGatt` callbacks all land on a Binder thread already (unlike
  `BluetoothLeScanner`'s callbacks — see the comment in `bluetooth_proxy.kt`), but every
  operation (`readCharacteristic`, `writeCharacteristic`, ...) must be serialized per
  connection — Android silently drops a second in-flight GATT operation on the same
  `BluetoothGatt` rather than queuing it.
- Reuse `esphome.kt`'s existing `ProtobufMessageBuilder`/`decodeFields` — no new encoding
  layer is needed, only new message handlers in `handleClient()`'s `when`.
