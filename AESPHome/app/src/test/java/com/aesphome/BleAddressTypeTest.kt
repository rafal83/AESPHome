package com.aesphome

import org.junit.Assert.assertEquals
import org.junit.Test

// Whether Bermuda/HA's IRK-based resolution of a rotating private BLE address ever runs
// depends entirely on this being right — see bleAddressType()'s doc comment (bluetooth_proxy.kt)
// for why Android gives us no reliable way to ask the OS directly on the devices this app
// targets, and why the three "random" bit patterns below are exactly the ones the Bluetooth
// Core Spec defines.
class BleAddressTypeTest {

  private fun addressWithTopByte(topByte: Int): Long =
      (topByte.toLong() and 0xFF) shl 40 // rest of the address doesn't affect the top two bits

  @Test
  fun `a static random address (top bits 11) is reported as random`() {
    assertEquals(1, bleAddressType(addressWithTopByte(0xC0))) // 0b11000000
    assertEquals(1, bleAddressType(addressWithTopByte(0xFF))) // 0b11111111
  }

  @Test
  fun `a resolvable private address (top bits 01) is reported as random`() {
    assertEquals(1, bleAddressType(addressWithTopByte(0x40))) // 0b01000000 — the kind IRK resolution targets
    assertEquals(1, bleAddressType(addressWithTopByte(0x7F))) // 0b01111111
  }

  @Test
  fun `a non-resolvable private address (top bits 00) is reported as random`() {
    assertEquals(1, bleAddressType(addressWithTopByte(0x00)))
    assertEquals(1, bleAddressType(addressWithTopByte(0x3F))) // 0b00111111
  }

  @Test
  fun `the one reserved-for-random bit pattern (10) is reported as public`() {
    assertEquals(0, bleAddressType(addressWithTopByte(0x80))) // 0b10000000
    assertEquals(0, bleAddressType(addressWithTopByte(0xBF))) // 0b10111111
  }

  @Test
  fun `only the top two bits of the address matter, not the rest`() {
    val a = (0xC0L shl 40) or 0x112233L
    val b = (0xC0L shl 40) or 0xAABBCCL
    assertEquals(bleAddressType(a), bleAddressType(b))
  }
}
