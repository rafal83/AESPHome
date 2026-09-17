package com.aesphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacStringToLongTest {

  @Test
  fun `packs a MAC the same way ESPHome firmware does`() {
    // Verified against esphome/components/esp32_ble/ble.h's ble_addr_to_uint64(): byte 0 in
    // the most-significant position — this is what has to match for HA to show the right
    // address for a proxied BLE advertisement.
    assertEquals(0xAABBCCDDEEFFL, macStringToLong("AA:BB:CC:DD:EE:FF"))
    assertEquals(0x000000000000L, macStringToLong("00:00:00:00:00:00"))
    assertEquals(0xFFFFFFFFFFFFL, macStringToLong("FF:FF:FF:FF:FF:FF"))
  }

  @Test
  fun `is case-insensitive`() {
    assertEquals(macStringToLong("aa:bb:cc:dd:ee:ff"), macStringToLong("AA:BB:CC:DD:EE:FF"))
  }

  @Test
  fun `rejects malformed input instead of guessing`() {
    assertNull(macStringToLong(""))
    assertNull(macStringToLong("AA:BB:CC"))
    assertNull(macStringToLong("AA:BB:CC:DD:EE:FF:00"))
    assertNull(macStringToLong("ZZ:BB:CC:DD:EE:FF"))
    assertNull(macStringToLong("AABBCCDDEEFF"))
  }
}

class SanitizeForDnsTest {

  @Test
  fun `keeps a clean hostname untouched`() {
    assertEquals("android-phone", sanitizeForDns("android-phone"))
  }

  @Test
  fun `replaces spaces and collapses repeats`() {
    assertEquals("LGE-LM-T600", sanitizeForDns("LGE LM-T600"))
  }

  @Test
  fun `falls back rather than returning an empty label`() {
    assertEquals("android-device", sanitizeForDns("!!!"))
  }
}
