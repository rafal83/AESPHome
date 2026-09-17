package com.aesphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// rawToPct/pctToRaw convert between Settings.System.SCREEN_BRIGHTNESS's 0-255 range and the
// 0-100% number.screen_brightness exposes to HA — pure math, no Context needed.
class ScreenBrightnessConversionTest {

  @Test
  fun `known raw values map to the expected percent`() {
    assertEquals(1f, ScreenBrightnessService.rawToPct(0)) // clamped up to the 1% floor, never 0
    assertEquals(100f, ScreenBrightnessService.rawToPct(255))
    assertEquals(50f, ScreenBrightnessService.rawToPct(128), 1f) // 128/255 ~= 50.2%
  }

  @Test
  fun `known percent values map to the expected raw brightness`() {
    assertEquals(255, ScreenBrightnessService.pctToRaw(100f))
    assertEquals(1, ScreenBrightnessService.pctToRaw(0f)) // clamped up — 0 would read as screen-off on many OEMs
    assertEquals(128, ScreenBrightnessService.pctToRaw(50f))
  }

  @Test
  fun `round-tripping through both conversions stays within one percentage point`() {
    for (pct in listOf(1f, 10f, 25f, 50f, 75f, 99f, 100f)) {
      val raw = ScreenBrightnessService.pctToRaw(pct)
      val roundTripped = ScreenBrightnessService.rawToPct(raw)
      assertEquals("pct=$pct raw=$raw", pct, roundTripped, 1f)
    }
  }

  @Test
  fun `never produces a raw value that would read as screen-off`() {
    for (pct in 0..100) {
      assertTrue("pct=$pct", ScreenBrightnessService.pctToRaw(pct.toFloat()) >= 1)
    }
  }
}
