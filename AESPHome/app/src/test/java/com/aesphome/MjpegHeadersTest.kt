package com.aesphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The header builders extracted from mjpeg_server.kt's socket-handling code — pure string
// formatting, checked against what an HTTP client (a browser, go2rtc, curl) actually needs:
// correct status line, a Content-Length that matches the real payload, and a multipart
// boundary that's both declared correctly and used consistently in every chunk.
class MjpegHeadersTest {

  @Test
  fun `status header ends with the blank line HTTP requires`() {
    val header = httpStatusHeader(401, "Unauthorized")
    assertEquals("HTTP/1.0 401 Unauthorized\r\nConnection: close\r\n\r\n", header)
    assertTrue(header.endsWith("\r\n\r\n"))
  }

  @Test
  fun `jpeg header advertises the exact frame size`() {
    val frame = ByteArray(12345)
    val header = jpegResponseHeader(frame.size)
    assertTrue(header.contains("Content-Type: image/jpeg"))
    assertTrue(header.contains("Content-Length: 12345"))
    assertTrue(header.endsWith("\r\n\r\n"))
  }

  @Test
  fun `stream header declares multipart with a boundary`() {
    val header = multipartStreamHeader("myboundary")
    assertTrue(header.contains("Content-Type: multipart/x-mixed-replace; boundary=myboundary"))
    assertTrue(header.endsWith("\r\n\r\n"))
  }

  @Test
  fun `every chunk uses the same boundary the stream header declared`() {
    val streamHeader = multipartStreamHeader("myboundary")
    val declaredBoundary = streamHeader.substringAfter("boundary=").substringBefore("\r\n")

    val chunkHeader = multipartChunkHeader(999, "myboundary")
    assertEquals("--$declaredBoundary\r\nContent-Type: image/jpeg\r\nContent-Length: 999\r\n\r\n", chunkHeader)
    assertTrue(chunkHeader.startsWith("--$declaredBoundary"))
  }

  @Test
  fun `default boundary matches between stream and chunk headers`() {
    val declaredBoundary = multipartStreamHeader().substringAfter("boundary=").substringBefore("\r\n")
    assertTrue(multipartChunkHeader(1).startsWith("--$declaredBoundary"))
  }

  @Test
  fun `tokenMatches accepts the exact expected token and nothing else`() {
    assertTrue(tokenMatches("abc123", "abc123"))
    assertFalse(tokenMatches("abc124", "abc123"))
    assertFalse(tokenMatches("abc12", "abc123")) // shorter
    assertFalse(tokenMatches("abc1234", "abc123")) // longer
    assertFalse(tokenMatches(null, "abc123")) // no token provided at all (neither query nor Bearer header)
  }
}
