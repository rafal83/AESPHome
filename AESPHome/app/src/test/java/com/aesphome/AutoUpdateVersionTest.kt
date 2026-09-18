package com.aesphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// compareVersions() decides whether button.install_update has anything to do — getting this
// wrong either hides a real update or nags about a non-existent one forever.
class AutoUpdateVersionTest {

  @Test
  fun `identical versions compare equal`() {
    assertEquals(0, compareVersions("v0.1.0", "v0.1.0"))
    assertEquals(0, compareVersions("0.1.0", "v0.1.0")) // leading "v" optional on either side
  }

  @Test
  fun `a higher patch, minor, or major version is newer`() {
    assertTrue(compareVersions("v0.1.1", "v0.1.0") > 0)
    assertTrue(compareVersions("v0.2.0", "v0.1.9") > 0)
    assertTrue(compareVersions("v1.0.0", "v0.9.9") > 0)
  }

  @Test
  fun `a lower version is older`() {
    assertTrue(compareVersions("v0.1.0", "v0.1.1") < 0)
    assertTrue(compareVersions("v0.9.9", "v1.0.0") < 0)
  }

  @Test
  fun `differing component counts compare as if the shorter one is zero-padded`() {
    assertEquals(0, compareVersions("v1.0", "v1.0.0"))
    assertTrue(compareVersions("v1.0.1", "v1.0") > 0)
  }

  @Test
  fun `a non-numeric component is treated as zero rather than throwing`() {
    assertEquals(0, compareVersions("v1.0.0-beta", "v1.0.0"))
  }

  // A naive string/lexical compare would say "0.2.10" < "0.2.9" (since "1" < "9" as
  // characters) — compareVersions splits into integer components specifically to avoid this.
  @Test
  fun `double-digit components compare numerically, not lexically`() {
    assertTrue(compareVersions("v0.2.10", "v0.2.9") > 0)
    assertTrue(compareVersions("v0.9.9", "v0.10.0") < 0)
    assertTrue(compareVersions("v1.0.0", "v0.99.99") > 0)
  }
}

// isAcceptableUpdateApkName() / parseSha256Checksum() gate what the auto-updater will ever
// download and install — getting these wrong means either a real update never gets offered,
// or (much worse) an unsigned/debug/corrupted APK gets installed over a real one.
class AutoUpdateSafetyTest {

  @Test
  fun `a normal release apk is accepted`() {
    assertTrue(isAcceptableUpdateApkName("AESPHome-0.3.0-release.apk", allowDebugOrUnsigned = false))
    assertTrue(isAcceptableUpdateApkName("AESPHome-0.3.0.apk", allowDebugOrUnsigned = false))
  }

  @Test
  fun `unsigned and debug builds are rejected for a normal (non-debug) app build`() {
    assertFalse(isAcceptableUpdateApkName("AESPHome-0.3.0-unsigned.apk", allowDebugOrUnsigned = false))
    assertFalse(isAcceptableUpdateApkName("AESPHome-0.3.0-debug.apk", allowDebugOrUnsigned = false))
    assertFalse(isAcceptableUpdateApkName("AESPHome-0.3.0-UNSIGNED.apk", allowDebugOrUnsigned = false)) // case-insensitive
  }

  @Test
  fun `unsigned and debug builds are allowed only when explicitly permitted`() {
    assertTrue(isAcceptableUpdateApkName("AESPHome-0.3.0-unsigned.apk", allowDebugOrUnsigned = true))
    assertTrue(isAcceptableUpdateApkName("AESPHome-0.3.0-debug.apk", allowDebugOrUnsigned = true))
  }

  @Test
  fun `anything that is not an apk is always rejected`() {
    assertFalse(isAcceptableUpdateApkName("AESPHome-0.3.0-release.apk.sha256", allowDebugOrUnsigned = false))
    assertFalse(isAcceptableUpdateApkName("README.md", allowDebugOrUnsigned = false))
    assertFalse(isAcceptableUpdateApkName("AESPHome-0.3.0-release.apk.sha256", allowDebugOrUnsigned = true))
  }

  @Test
  fun `checksum parsing accepts a bare hex digest`() {
    val hex = "a".repeat(64)
    assertEquals(hex, parseSha256Checksum(hex))
    assertEquals(hex, parseSha256Checksum("  $hex  \n")) // whitespace-tolerant
  }

  @Test
  fun `checksum parsing accepts standard sha256sum output and normalizes case`() {
    val hex = "A".repeat(64)
    assertEquals("a".repeat(64), parseSha256Checksum("$hex  AESPHome-0.3.0-release.apk\n"))
  }

  @Test
  fun `checksum parsing rejects anything that is not a well-formed digest`() {
    assertEquals(null, parseSha256Checksum(""))
    assertEquals(null, parseSha256Checksum("not a checksum"))
    assertEquals(null, parseSha256Checksum("a".repeat(63))) // one char short
    assertEquals(null, parseSha256Checksum("g".repeat(64))) // not hex
  }
}
