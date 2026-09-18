package com.aesphome

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.divider.MaterialDivider


/*

  Permissions
    One status row per permission an implemented feature actually depends on — Granted /
    Denied / Not supported (this Android version has no such concept), each with an Enable
    button for the ones that need one. Nothing here requests anything on its own; every grant
    happens because the user tapped a specific row for a specific reason, never all at once
    on first launch.

*/


private enum class PermissionStatus { GRANTED, DENIED, NOT_SUPPORTED }

private class PermissionRow(
    val label: String,
    val status: (Activity) -> PermissionStatus,
    val note: String? = null, // shown under the status text regardless of Enable — for rows with no in-app grant flow (Device Owner)
    val enable: ((Activity) -> Unit)? = null, // null: nothing to offer (already granted at install time, or purely informational)
)

class PermissionsActivity : Activity() {
  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  private fun granted(permission: String): PermissionStatus =
      if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) PermissionStatus.GRANTED else PermissionStatus.DENIED

  private fun requestRuntime(vararg permissions: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) requestPermissions(permissions, 1)
  }

  private val rows: List<PermissionRow> by lazy {
    listOf(
      PermissionRow("Camera",
          { granted(Manifest.permission.CAMERA) },
          enable = { requestRuntime(Manifest.permission.CAMERA) }),

      PermissionRow("Microphone",
          { granted(Manifest.permission.RECORD_AUDIO) },
          enable = { requestRuntime(Manifest.permission.RECORD_AUDIO) }),

      PermissionRow("Nearby devices / Bluetooth",
          {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) granted(Manifest.permission.BLUETOOTH_SCAN)
            else PermissionStatus.GRANTED // pre-12: normal permissions, granted at install time
          },
          enable = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              requestRuntime(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            }
          }),

      PermissionRow("Notifications",
          {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) granted(Manifest.permission.POST_NOTIFICATIONS)
            else PermissionStatus.GRANTED
          },
          enable = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestRuntime(Manifest.permission.POST_NOTIFICATIONS)
          }),

      PermissionRow("Accessibility Service (Screen Touch)",
          { if (isAccessibilityServiceEnabled(this, TouchAccessibilityService::class.java)) PermissionStatus.GRANTED else PermissionStatus.DENIED },
          enable = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),

      PermissionRow("Usage Access",
          { if (hasUsageAccess(this)) PermissionStatus.GRANTED else PermissionStatus.DENIED },
          enable = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }),

      PermissionRow("Modify system settings",
          { if (Settings.System.canWrite(this)) PermissionStatus.GRANTED else PermissionStatus.DENIED },
          enable = { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }),

      PermissionRow("Device Administrator",
          { if (isDeviceAdminActive(this)) PermissionStatus.GRANTED else PermissionStatus.DENIED },
          enable = { requestDeviceAdmin(this) }),

      PermissionRow("Battery Optimisation Exemption",
          {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) PermissionStatus.GRANTED else PermissionStatus.DENIED
          },
          enable = { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }),

      // Purely informational — there's nothing to "enable" beyond notifications (above) once
      // the service has actually been started (MainActivity does this every time it opens).
      PermissionRow("Foreground Service",
          { if (AESPHomeService.instance != null) PermissionStatus.GRANTED else PermissionStatus.DENIED }),

      PermissionRow("Install Unknown Apps (tap-to-confirm updates)",
          {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              if (packageManager.canRequestPackageInstalls()) PermissionStatus.GRANTED else PermissionStatus.DENIED
            } else PermissionStatus.GRANTED // pre-8: a single device-wide "Unknown sources" toggle, not per-app
          },
          enable = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
          }),

      // No Enable button — Device Owner has no in-app grant flow at all (see isDeviceOwner()
      // in utils.kt). Shown so it's at least discoverable that this status exists and what
      // unlocks it, rather than silently doing nothing differently when it's missing.
      PermissionRow("Device Owner (silent updates)",
          { if (isDeviceOwner(this)) PermissionStatus.GRANTED else PermissionStatus.DENIED },
          note = "Set once via adb (see FAQ.md) — updates install with one tap without it"),
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    actionBar?.setDisplayHomeAsUpEnabled(true)
    render()
  }

  override fun onResume() {
    super.onResume()
    render() // statuses can change from outside this Activity (e.g. returning from a Settings screen)
  }

  override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
    if (item.itemId == android.R.id.home) { finish(); return true }
    return super.onOptionsItemSelected(item)
  }

  private fun render() {
    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    for ((index, row) in rows.withIndex()) {
      val status = row.status(this)

      val rowLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
      }

      val textColumn = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      }
      textColumn.addView(TextView(this).apply {
        text = row.label
        textSize = 15f
      })
      textColumn.addView(TextView(this).apply {
        text = statusText(status)
        textSize = 13f
        setTextColor(statusColor(status))
      })
      if (row.note != null) {
        textColumn.addView(TextView(this).apply {
          text = row.note
          textSize = 11f
          setTextColor(Color.GRAY)
        })
      }
      rowLayout.addView(textColumn)

      if (status != PermissionStatus.GRANTED && row.enable != null) {
        rowLayout.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
          text = "Enable"
          setOnClickListener { row.enable.invoke(this@PermissionsActivity) }
        })
      }

      layout.addView(rowLayout)
      if (index != rows.lastIndex) layout.addView(MaterialDivider(this))
    }

    setContentView(ScrollView(this).apply { addView(layout) })
  }

  private fun statusText(status: PermissionStatus): String = when (status) {
    PermissionStatus.GRANTED -> "Granted"
    PermissionStatus.DENIED -> "Denied"
    PermissionStatus.NOT_SUPPORTED -> "Not supported"
  }

  private fun statusColor(status: PermissionStatus): Int = when (status) {
    PermissionStatus.GRANTED -> Color.parseColor("#2E7D32")
    PermissionStatus.DENIED -> Color.parseColor("#C62828")
    PermissionStatus.NOT_SUPPORTED -> Color.GRAY
  }
}
