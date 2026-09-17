package com.aesphome

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView


/*

  Allowed Apps
    Lets the user pick which installed, launchable apps HA is allowed to launch via
    select.launch_app (app_launcher.kt) — the whitelist IS the security boundary for that
    entity, so this screen is the only place it can be changed; there is deliberately no way
    to launch an arbitrary package from the network.

*/


class AppLauncherSettingsActivity : Activity() {
  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    layout.addView(TextView(this).apply {
      text = "Apps checked here can be launched from Home Assistant via select.launch_app."
      setPadding(0, 0, 0, dp(16))
    })

    val pm = packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
        .distinctBy { it.activityInfo.packageName }
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .sortedBy { it.second.lowercase() }

    val allowed = AppLauncherService.allowedPackages(this).toMutableSet()

    for ((packageName, label) in apps) {
      val checkBox = CheckBox(this)
      checkBox.text = label
      checkBox.isChecked = packageName in allowed
      checkBox.setOnCheckedChangeListener { _, checked ->
        if (checked) allowed.add(packageName) else allowed.remove(packageName)
        AppLauncherService.setAllowedPackages(this, allowed)
      }
      layout.addView(checkBox)
    }

    setContentView(ScrollView(this).apply { addView(layout) })
  }
}
