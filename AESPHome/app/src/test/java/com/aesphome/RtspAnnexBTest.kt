package com.aesphome

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

// splitAnnexB() is the one piece of rtsp_server.kt's protocol logic that's pure enough to
// unit test without a real MediaCodec/camera — see the file's header comment for why the
// RTP/RTSP layers around it couldn't be verified against a live player in this environment.
// Getting NAL boundaries wrong here would corrupt every downstream RTP packet, so this is
// exactly the kind of logic worth pinning down precisely.
class RtspAnnexBTest {

  @Test
  fun `splits two NALs separated by a 4-byte start code`() {
    val data = byteArrayOf(0, 0, 0, 1, 0x67, 0x11, 0x22, 0, 0, 0, 1, 0x68, 0x33)
    val nals = splitAnnexB(data)
    assertEquals(2, nals.size)
    assertArrayEquals(byteArrayOf(0x67, 0x11, 0x22), nals[0])
    assertArrayEquals(byteArrayOf(0x68, 0x33), nals[1])
  }

  @Test
  fun `splits NALs separated by a 3-byte start code`() {
    val data = byteArrayOf(0, 0, 1, 0x67, 0x11, 0, 0, 1, 0x68, 0x22)
    val nals = splitAnnexB(data)
    assertEquals(2, nals.size)
    assertArrayEquals(byteArrayOf(0x67, 0x11), nals[0])
    assertArrayEquals(byteArrayOf(0x68, 0x22), nals[1])
  }

  @Test
  fun `handles a mix of 3- and 4-byte start codes in one buffer`() {
    // Real MediaCodec output does exactly this — a 4-byte start code before the first NAL,
    // 3-byte ones between subsequent NALs in the same buffer.
    val data = byteArrayOf(0, 0, 0, 1, 0x67, 0x01, 0, 0, 1, 0x68, 0x02, 0, 0, 1, 0x65, 0x03, 0x04)
    val nals = splitAnnexB(data)
    assertEquals(3, nals.size)
    assertArrayEquals(byteArrayOf(0x67, 0x01), nals[0])
    assertArrayEquals(byteArrayOf(0x68, 0x02), nals[1])
    assertArrayEquals(byteArrayOf(0x65, 0x03, 0x04), nals[2])
  }

  @Test
  fun `single NAL with no trailing start code runs to the end of the buffer`() {
    val data = byteArrayOf(0, 0, 0, 1, 0x65, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
    val nals = splitAnnexB(data)
    assertEquals(1, nals.size)
    assertArrayEquals(byteArrayOf(0x65, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), nals[0])
  }

  @Test
  fun `empty or start-code-only input yields no NALs`() {
    assertEquals(0, splitAnnexB(ByteArray(0)).size)
    assertEquals(0, splitAnnexB(byteArrayOf(0, 0, 0, 1)).size)
  }

  @Test
  fun `identifies SPS, PPS, and IDR NAL types by their header byte`() {
    // NAL type is the low 5 bits of the first byte — nal_ref_idc(2 bits) is unrelated and
    // must not affect the type extraction (0x67 = 0110 0111: type 7 = SPS with ref_idc 3).
    val sps = byteArrayOf(0x67, 0x42.toByte())
    val pps = byteArrayOf(0x68, 0xCE.toByte())
    val idr = byteArrayOf(0x65, 0x88.toByte())
    assertEquals(7, sps[0].toInt() and 0x1F)
    assertEquals(8, pps[0].toInt() and 0x1F)
    assertEquals(5, idr[0].toInt() and 0x1F)
  }

  @Test
  fun `FU-A first fragment sets S=1 E=0 and preserves F-NRI, replaces type with 28`() {
    val nalHeader = 0x65.toByte() // F=0, NRI=3 (0x60), type=5 (IDR)
    val (indicator, header) = fuAHeaderBytes(nalHeader, isFirst = true, isLast = false)
    assertEquals(0x60 or 28, indicator.toInt() and 0xFF) // NRI preserved, type forced to 28 (FU-A)
    assertEquals(0x80 or 5, header.toInt() and 0xFF)     // S=1, E=0, original type (5) preserved
  }

  @Test
  fun `FU-A last fragment sets S=0 E=1`() {
    val (_, header) = fuAHeaderBytes(0x65.toByte(), isFirst = false, isLast = true)
    assertEquals(0x40 or 5, header.toInt() and 0xFF) // S=0, E=1
  }

  @Test
  fun `FU-A middle fragment sets both S and E to 0`() {
    val (_, header) = fuAHeaderBytes(0x65.toByte(), isFirst = false, isLast = false)
    assertEquals(5, header.toInt() and 0xFF) // S=0, E=0, just the original type
  }

  @Test
  fun `FU-A indicator preserves the forbidden-zero-bit (F) from the original NAL header`() {
    // F=1 (would mean a bitstream error, but the fragmenter must still propagate it
    // unmodified rather than silently clearing it) combined with NRI=2 and type=1.
    val nalHeader = (0x80 or 0x40 or 1).toByte() // 1 100 00001
    val (indicator, _) = fuAHeaderBytes(nalHeader, isFirst = true, isLast = false)
    assertEquals(0x80 or 0x40 or 28, indicator.toInt() and 0xFF)
  }
}
