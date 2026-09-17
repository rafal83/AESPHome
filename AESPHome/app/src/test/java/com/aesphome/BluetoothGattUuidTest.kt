package com.aesphome

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Whether a characteristic/service UUID gets sent as ESPHome's compact `short_uuid` or the
// full 128-bit `uuid` pair — see the long comment above shortUuidOrNull() in
// bluetooth_gatt.kt for why only this half (the standard Bluetooth Base UUID expansion) is
// verified against a real specification rather than being a guess.
class BluetoothGattUuidTest {

  @Test
  fun `recognizes a standard 16-bit Bluetooth SIG UUID`() {
    // 0x180F = Battery Service
    val batteryService = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    assertEquals(0x180F, shortUuidOrNull(batteryService))
  }

  @Test
  fun `recognizes 0x2902 (Client Characteristic Configuration Descriptor)`() {
    assertEquals(0x2902, shortUuidOrNull(CLIENT_CHARACTERISTIC_CONFIG_UUID))
  }

  @Test
  fun `does not misidentify a genuinely custom 128-bit UUID as a short one`() {
    val custom = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e") // Nordic UART service
    assertNull(shortUuidOrNull(custom))
  }

  @Test
  fun `custom UUID still round-trips through the 128-bit long pair without data loss`() {
    val custom = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val (high, low) = uuid128ToLongPair(custom)
    val rebuilt = UUID(high, low)
    assertEquals(custom, rebuilt)
  }
}
