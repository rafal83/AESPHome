package com.aesphome

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView


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
    val enable: ((Activity) -> Unit)? = null, // null: nothing to offer (already granted at install time, or purely informational)
)

class PermissionsActivity : Activity() {
  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  private fun granted(permission: String): PermissionStatus =
      if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) PermissionStatus.GRANTED else PermissionStatus.DENIED

  private fun requestRuntime(vararg permissions: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) requestPermissions(permissions, 1)
  }

  private fun openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
  }

  private val rows: List<PermissionRow> by lazy {
    listOf(
      PermissionRow("Camera",
          { granted(Manifest.permission.CAMERA) },
          { requestRuntime(Manifest.permission.CAMERA) }),

      PermissionRow("Microphone",
          { granted(Manifest.permission.RECORD_AUDIO) },
          { requestRuntime(Manifest.permission.RECORD_AUDIO) }),

      PermissionRow("Nearby devices / Bluetooth",
          {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) granted(Manifest.permission.BLUETOOTH_SCAN)
            else PermissionStatus.GRANTED // pre-12: normal permissions, granted at install time
          },
          {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              requestRuntime(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            }
          }),

      PermissionRow("Notifications",
          {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) granted(Manifest.permission.POST_NOTIFICATIONS)
            else PermissionStatus.GRANTED
          },
          {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestRuntime(Manifest.permission.POST_NOTIFICATIONS)
          }),

      PermissionRow("Accessibility Service (Screen Touch)",
          { if (isAccessibilityServiceEnabled(this, TouchAccessibilityService::class.java)) PermissionStatus.GRANTED else PermissionStatus.DENIED },
          { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),

      PermissionRow("Usage Access",
          { if (hasUsageAccess(this)) PermissionStatus.GRANTED else PermissionStatus.DENIED },
          { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }),

      PermissionRow("Modify system settings",
          { if (Settings.System.canWrite(this)) PermissionStatus.GRANTED else PermissionStatus.DENIED },
          { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }),

      PermissionRow("Device Administrator",
          { if (isDeviceAdminActive(this)) PermissionStatus.GRANTED else PermissionStatus.DENIED },
          { requestDeviceAdmin(this) }),

      PermissionRow("Battery Optimisation Exemption",
          {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) PermissionStatus.GRANTED else PermissionStatus.DENIED
          },
          {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
          }),

      // Purely informational — there's nothing to "enable" beyond notifications (above) once
      // the service has actually been started (MainActivity does this every time it opens).
      PermissionRow("Foreground Service",
          { if (AESPHomeService.instance != null) PermissionStatus.GRANTED else PermissionStatus.DENIED }),

      PermissionRow("Install Unknown Apps (auto-update)",
          {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              if (packageManager.canRequestPackageInstalls()) PermissionStatus.GRANTED else PermissionStatus.DENIED
            } else PermissionStatus.GRANTED // pre-8: a single device-wide "Unknown sources" toggle, not per-app
          },
          {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
          }),
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    render()
  }

  override fun onResume() {
    super.onResume()
    render() // statuses can change from outside this Activity (e.g. returning from a Settings screen)
  }

  private fun render() {
    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    for (row in rows) {
      val status = row.status(this)

      val rowLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, dp(8))
      }

      val label = TextView(this).apply {
        text = "${row.label}\n${statusText(status)}"
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
      rowLayout.addView(label)

      if (status != PermissionStatus.GRANTED && row.enable != null) {
        val button = Button(this)
        button.text = "Enable"
        button.setOnClickListener { row.enable.invoke(this) }
        rowLayout.addView(button)
      }

      layout.addView(rowLayout)
    }

    setContentView(ScrollView(this).apply { addView(layout) })
  }

  private fun statusText(status: PermissionStatus): String = when (status) {
    PermissionStatus.GRANTED -> "Granted"
    PermissionStatus.DENIED -> "Denied"
    PermissionStatus.NOT_SUPPORTED -> "Not supported"
  }
}
