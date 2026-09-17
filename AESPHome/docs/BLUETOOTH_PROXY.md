# Bluetooth Proxy

`switch.bluetooth_proxy` makes AESPHome show up to Home Assistant's Bluetooth integration the
same way a real ESPHome Bluetooth Proxy does, with both halves of the feature implemented:

- **Passive scan** (`sensors/bluetooth_proxy.kt`) — forwards every BLE advertisement Android
  sees to HA.
- **Active GATT connections** (`sensors/bluetooth_gatt.kt`) — HA connecting *through* this
  device to a remote BLE peripheral (read/write characteristics, subscribe to notifications).

Both share the one switch, one permission set, and one `DeviceInfoResponse` feature-flag
advertisement — matching how a real ESPHome `bluetooth_proxy` component's `active_connections`
option just adds capability to the same proxy rather than being a separate entity.

All message ids and field numbers below were verified against upstream ESPHome's
`esphome/components/api/api.proto` (`dev` branch) at the time this was written, not guessed.

## Passive scan

1. `DeviceInfoResponse` (`esphome.kt`) advertises `bluetooth_proxy_feature_flags =
   PASSIVE_SCAN | RAW_ADVERTISEMENTS | ACTIVE_CONNECTIONS` (`1 | 32 | 2`) whenever the switch
   is enabled — the same bitmask `aioesphomeapi`'s `BluetoothProxyFeature` enum defines.
2. HA subscribes with `SubscribeBluetoothLEAdvertisementsRequest` (id 66).
3. Every BLE advertisement Android's own `BluetoothLeScanner` sees is forwarded as a
   `BluetoothLERawAdvertisementsResponse` (id 93) — address, RSSI, address type, and the raw
   advertisement data bytes (`ScanResult.scanRecord.bytes`), exactly the wire format current
   ESPHome firmware uses (the older non-raw `BluetoothLEAdvertisementResponse`, id 67, was
   removed upstream in ESPHome 2025.8.0, so this device only ever speaks the raw format).

The MAC address is packed into the `uint64 address` field the same way ESPHome's own C++
firmware does it — verified two ways, not just against `esp32_ble/ble.h`'s
`ble_addr_to_uint64` (address[0] in the most-significant position): the currently-canonical
`ble_device_base/ble_device.h`'s `ESPBTDevice::address_uint64()` was traced directly and
confirmed to produce the identical value for a human-readable-order MAC (its comment "address_
is MSB-first; byte 0 of the result is the LSB" describes the same layout, from the opposite
direction — that function's *input* is already MSB-first, same as Android's
`BluetoothDevice.getAddress()` string, so it needed no reconciling). See `macStringToLong()`
in `utils.kt` and `ProtobufTest.kt`/`UtilsTest.kt` for the byte order this app uses.

Permissions: `BLUETOOTH_SCAN` (Android 12+, flagged `neverForLocation` since this app never
derives location from scan results) or `ACCESS_FINE_LOCATION` (Android ≤11, required by the
OS to receive scan results at all). Both are requested only from the Permissions screen.

## Active GATT connections

`sensors/bluetooth_gatt.kt`'s `BluetoothGattProxy` owns every `android.bluetooth.BluetoothGatt`
this device holds. `esphome.kt` only decodes the incoming request and calls straight into it;
`BluetoothGattProxy` calls back into `esphome.kt`'s `pushBleXxx()` functions once Android's
async GATT callbacks actually complete — the same hardware/wire-format split used everywhere
else in this codebase (e.g. `CameraService`/`esphome.kt`).

Implemented: `BluetoothDeviceRequest`/`BluetoothDeviceConnectionResponse` (connect/disconnect,
ids 68/69), `BluetoothGATTGetServicesRequest`/`Response`/`DoneResponse` (service discovery,
ids 70-72), `BluetoothGATTReadRequest`/`Response` (73/74), `BluetoothGATTWriteRequest`/
`WriteResponse` (75/83), `BluetoothGATTReadDescriptorRequest` (76, routed through the same
read path as a characteristic read), `BluetoothGATTWriteDescriptorRequest` (77),
`BluetoothGATTNotifyRequest`/`NotifyDataResponse`/`NotifyResponse` (78/79/84),
`BluetoothGATTErrorResponse` (82), and `SubscribeBluetoothConnectionsFreeRequest`/
`BluetoothConnectionsFreeResponse` (80/81, reported once against a fixed
`BLE_MAX_CONNECTIONS = 3` soft cap — Android has no fixed BLE connection-slot count the way
real ESPHome hardware does, so this is a deliberately arbitrary, conservative number rather
than a measured one).

**Handles** are opaque to the ESPHome client by design (it only has to hand back whatever
value it was given) — rather than rely on Android's deprecated
`BluetoothGattCharacteristic.getInstanceId()`, each connection assigns its own incrementing
integer handle per characteristic/descriptor and keeps a local map, looked up (not
re-assigned) on a repeated `GetServicesRequest` so handles stay stable for the connection's
whole life.

**Operation queueing**: Android silently drops a second read/write/descriptor operation
issued on the same `BluetoothGatt` before the previous one's callback has fired. Every
operation for a given connection goes through a small FIFO queue (`Connection.enqueue()`),
one in flight at a time, advanced from that operation's own callback.

**Notifications**: `BluetoothGATTNotifyRequest` calls `setCharacteristicNotification()` and
then writes the standard Client Characteristic Configuration Descriptor (UUID `0x2902`) with
`ENABLE_NOTIFICATION_VALUE` or `ENABLE_INDICATION_VALUE` (chosen from the characteristic's own
`PROPERTY_INDICATE` bit) — the descriptor write's completion is what triggers the
`BluetoothGATTNotifyResponse` back to HA, not a fixed delay.

**UUID encoding**: a standard Bluetooth SIG 16-bit UUID (the Base UUID with a 16-bit assigned
number spliced in, e.g. Battery Service `0x180F`) is sent as ESPHome's compact `short_uuid`
field — this detection is against the well-defined, non-ESPHome-specific Base UUID expansion
rule, so it carries no ambiguity (verified in `BluetoothGattUuidTest.kt`). A genuinely custom
128-bit UUID falls back to the full two-`uint64` `uuid` field, split high-then-low from
`UUID.getMostSignificantBits()`/`getLeastSignificantBits()` — **this specific split was not
verified against upstream ESPHome source** (it wasn't findable in the current `dev` branch;
the GATT-tree-building code that would show it isn't in any file this branch's research
located). This only affects how a *custom* UUID's identity displays in Home Assistant, never
read/write/notify correctness — those are always routed through this app's own handle map,
never by re-parsing a UUID back off the wire. If it turns out to be wrong, the fix is
localized to `uuid128ToLongPair()` in `bluetooth_gatt.kt`.

Permission: `BLUETOOTH_CONNECT`, already required unconditionally by this app since before
this feature existed (`bluetooth_switch.kt`/`bluetooth_commands.kt`), so this adds no new
permission surface.

## What's NOT implemented

- **Pairing** (`BluetoothDeviceRequestType.PAIR`/`UNPAIR`) and **cache clearing**
  (`CLEAR_CACHE`) — `BluetoothGattProxy.handleDeviceRequest()` responds to these with a
  failure result (so HA doesn't hang waiting) rather than performing them.
- **`REMOTE_CACHING`** — not advertised in the feature flags; every `GetServicesRequest`
  either re-serves the already-discovered tree for the current connection or triggers a fresh
  `discoverServices()`, never a cross-connection cache.
- **`CONNECTION_PARAMS_SETTING`** — connection interval/latency/timeout negotiation.
- **MTU negotiation** — connections report a fixed `mtu = 23` (the ATT default) rather than
  calling `requestMtu()`; works correctly for any characteristic within that size, just not
  optimized for throughput on larger ones.
- `BluetoothScannerStateResponse` / `BluetoothScannerSetModeRequest` (ids 126/127) — optional
  informational messages a real ESPHome device sends about its scanner's own state; this
  device's scanner state is only visible locally (the switch's own on/off already reflects
  whether scanning is requested).

None of these block the common cases (reading/writing/subscribing to an unpaired BLE
peripheral's characteristics) — they're the parts of the spec genuinely tied to either
security material (pairing) or throughput/connection tuning that don't change whether a basic
GATT session works at all.
