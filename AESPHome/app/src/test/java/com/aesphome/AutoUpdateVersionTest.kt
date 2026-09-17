package com.aesphome

import org.junit.Assert.assertEquals
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
}
