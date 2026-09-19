package com.aesphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Checked against UI_SECTION_BY_ID directly (not Sensors.toggleables) — some registered
// objects (e.g. TouchSensor) construct real Android framework objects (a Handler bound to
// Looper.getMainLooper()) as part of object initialization, which a plain JVM unit test can't
// load. The ids below were the full set registered across sensors/*.kt at the time this test
// was written (verified with a grep across every "override val id = ..." plus the three
// axis-sensor files' constructor-literal ids) — if a new entity is added without a matching
// UI_SECTION_BY_ID entry, this catches it as a specific missing id rather than a vague "some
// entity fell into Other" failure.
private val KNOWN_IDS = listOf(
  "screen_on", "screen_brightness", "screen_orientation", "screen_wake", "screen_sleep",
  "keep_screen_on", "touch",
  "camera", "camera_lux", "mjpeg_server", "mjpeg_server_running", "mjpeg_url",
  "rtsp_server", "rtsp_server_running", "rtsp_url",
  "person_detector", "person_detected", "person_count",
  "bluetooth_switch", "bluetooth_commands", "bluetooth_proxy",
  "media_player", "system_volume",
  "battery_percent", "battery_charging", "battery_temperature_c",
  "device_movement", "device_orientation", "light_sensor", "decibel_meter",
  "proximity", "pressure", "relative_humidity", "ambient_temperature", "wifi_rssi",
  "accelerometer_x", "accelerometer_y", "accelerometer_z",
  "gyroscope_x", "gyroscope_y", "gyroscope_z",
  "magnetic_field_x", "magnetic_field_y", "magnetic_field_z",
  "android_version", "device_model", "app_version", "ip_address",
  "wifi_ssid", "wifi_bssid", "charging_source", "foreground_app", "uptime",
  "free_memory", "total_memory", "free_storage", "total_storage",
  "wifi_frequency", "wifi_link_speed",
  "battery_voltage", "battery_current", "battery_power",
  "mdns", "identify", "start_at_boot", "app_launcher", "auto_update", "check_for_update",
)

class UiSectionTest {

  @Test
  fun `every known id is mapped to a real section, not left to fall back to Other`() {
    val unmapped = KNOWN_IDS.filterNot { UI_SECTION_BY_ID.containsKey(it) }
    assertEquals("ids missing from UI_SECTION_BY_ID: $unmapped", emptyList<String>(), unmapped)
  }

  @Test
  fun `no mapped id points at the Other fallback itself`() {
    // Other exists as the safe fallback for an id NOT in the map — nothing should be
    // explicitly mapped to it on purpose.
    val explicitlyOther = UI_SECTION_BY_ID.filterValues { it == UiSection.OTHER }.keys
    assertTrue("ids explicitly mapped to OTHER: $explicitlyOther", explicitlyOther.isEmpty())
  }

  @Test
  fun `every UiSection value has a human-readable label`() {
    for (section in UiSection.entries) {
      assertTrue("${section.name} has a blank label", section.label.isNotBlank())
    }
  }
}
