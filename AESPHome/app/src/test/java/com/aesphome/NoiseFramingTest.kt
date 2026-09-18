package com.aesphome

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

// Pure byte-layout tests for noise.kt's wire framing — each expected byte sequence is
// hand-computed against ESPHome's own source (see noise.kt's doc comments for exactly which
// file/function each one was checked against), the same "don't just trust the code that
// wrote it" approach as RtspAnnexBTest.kt used for RTSP's NAL/FU-A packetization.
class NoiseFramingTest {

  @Test
  fun `transport frame is indicator plus big-endian 16-bit length plus content`() {
    val frame = noiseTransportFrame(0x01, byteArrayOf(0x11, 0x22, 0x33))
    assertArrayEquals(byteArrayOf(0x01, 0x00, 0x03, 0x11, 0x22, 0x33), frame)
  }

  @Test
  fun `transport frame handles empty content (a bare ClientHello)`() {
    val frame = noiseTransportFrame(0x01, ByteArray(0))
    assertArrayEquals(byteArrayOf(0x01, 0x00, 0x00), frame)
  }

  @Test
  fun `transport frame length field is big-endian across both bytes`() {
    val frame = noiseTransportFrame(0x01, ByteArray(300)) // 300 = 0x012C, exercises both length bytes
    assertEquals(0x01, frame[1].toInt() and 0xFF)
    assertEquals(0x2C, frame[2].toInt() and 0xFF)
    assertEquals(303, frame.size)
  }

  @Test
  fun `ServerHello is chosen-proto byte then null-terminated name then null-terminated mac`() {
    val payload = noiseServerHelloPayload("aesphome", "AA:BB:CC")
    val expected = byteArrayOf(0x01) + "aesphome".toByteArray() + byteArrayOf(0) + "AA:BB:CC".toByteArray() + byteArrayOf(0)
    assertArrayEquals(expected, payload)
  }

  @Test
  fun `ServerHello with empty name and mac is still well-formed`() {
    val payload = noiseServerHelloPayload("", "")
    assertArrayEquals(byteArrayOf(0x01, 0, 0), payload)
  }

  @Test
  fun `prologue is the fixed prefix, a big-endian length, then the ClientHello content`() {
    val clientHello = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val prologue = noisePrologue(clientHello)
    val expected = "NoiseAPIInit".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 2, 0xAA.toByte(), 0xBB.toByte())
    assertArrayEquals(expected, prologue)
  }

  @Test
  fun `prologue with an empty ClientHello is just the prefix plus a zero length`() {
    val prologue = noisePrologue(ByteArray(0))
    assertArrayEquals("NoiseAPIInit".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0), prologue)
  }

  // decodeNoisePskOrNull()/generateNoisePsk() are thin wrappers around android.util.Base64,
  // which — like every other android.* API — throws "not mocked" in this project's plain-
  // JVM unit tests (no Robolectric; see UiSectionTest.kt's history for the same constraint).
  // Their actual logic (length check) is exercised indirectly by
  // NoiseHandshakeRoundTripTest.kt, which uses raw PSK bytes directly and doesn't touch
  // Base64 at all.
}
