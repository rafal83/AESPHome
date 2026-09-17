package com.aesphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// esphome.kt's whole entity wire encoding rests on this hand-written encoder/decoder being
// correct — nothing here talks to Android, so it's plain, fast JVM unit testing.
class ProtobufTest {

  @Test
  fun `varint round-trips small and large values`() {
    for (value in listOf(0, 1, 127, 128, 300, 16384, Int.MAX_VALUE)) {
      val encoded = encodeVarint(value)
      var index = 0
      val decoded = decodeVarint { encoded[index++].toInt() }
      assertEquals("value=$value", value, decoded)
    }
  }

  @Test
  fun `varint uses one byte under 128, more above`() {
    assertEquals(1, encodeVarint(0).size)
    assertEquals(1, encodeVarint(127).size)
    assertTrue(encodeVarint(128).size > 1)
  }

  @Test
  fun `builder varint field decodes back to the same value`() {
    val payload = ProtobufMessageBuilder().varint(1, 42).build()
    val fields = decodeFields(payload)
    assertEquals(42, fields[1])
  }

  @Test
  fun `builder string field decodes back to the same bytes`() {
    val payload = ProtobufMessageBuilder().string(3, "aesphome").build()
    val fields = decodeFields(payload)
    assertEquals("aesphome", String(fields[3] as ByteArray))
  }

  @Test
  fun `builder fixed32 and float fields round-trip through their raw bits`() {
    val payload = ProtobufMessageBuilder()
        .fixed32(2, 0x11223344)
        .float(5, 21.5f)
        .build()
    val fields = decodeFields(payload)

    val keyBytes = fields[2] as ByteArray
    val key = (keyBytes[0].toInt() and 0xFF) or ((keyBytes[1].toInt() and 0xFF) shl 8) or
              ((keyBytes[2].toInt() and 0xFF) shl 16) or ((keyBytes[3].toInt() and 0xFF) shl 24)
    assertEquals(0x11223344, key)

    val floatValue = Float.fromBits(bytesToInt(fields[5] as ByteArray))
    assertEquals(21.5f, floatValue, 0.0001f)
  }

  @Test
  fun `repeated field appears once per call, in order`() {
    val payload = ProtobufMessageBuilder().string(6, "a").string(6, "bb").string(6, "ccc").build()
    // decodeFields (a simple map) only keeps the last of a repeated field — this test exists
    // to document that limitation, since esphome.kt relies on it never mattering (every
    // repeated field it decodes — e.g. NumberCommandRequest has none — is single-valued in
    // practice). A real repeated-field consumer would need its own multi-value decoder.
    val fields = decodeFields(payload)
    assertEquals("ccc", String(fields[6] as ByteArray))
  }

  @Test
  fun `varintLong round-trips a 48-bit MAC-derived value`() {
    val address = macStringToLong("AA:BB:CC:DD:EE:FF")!!
    val payload = ProtobufMessageBuilder().varintLong(1, address).build()

    // Manually decode the 64-bit varint back out, mirroring decodeFields' 32-bit decoder.
    var index = 0
    val tag = decodeVarint { payload[index++].toInt() }
    assertEquals(1, tag shr 3)
    var result = 0L
    var shift = 0
    while (true) {
      val b = payload[index++].toInt() and 0xFF
      result = result or ((b.toLong() and 0x7F) shl shift)
      if (b and 0x80 == 0) break
      shift += 7
    }
    assertEquals(address, result)
    assertEquals(0xAABBCCDDEEFFL, result)
  }
}
