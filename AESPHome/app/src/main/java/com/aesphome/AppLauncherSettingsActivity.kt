package com.aesphome

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.divider.MaterialDivider


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
    actionBar?.setDisplayHomeAsUpEnabled(true)

    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    layout.addView(TextView(this).apply {
      text = "Apps checked here can be launched from Home Assistant via select.launch_app."
      textSize = 13f
      setPadding(0, 0, 0, dp(12))
    })

    // LauncherApps (not PackageManager.queryIntentActivities) — the API real launcher apps
    // use to enumerate launchable apps; queryIntentActivities(MATCH_DEFAULT_ONLY) was found to
    // miss some real apps in practice (e.g. ones whose launcher activity doesn't also declare
    // the DEFAULT category, which that flag additionally requires).
    val launcherApps = getSystemService(LauncherApps::class.java)
    val apps = launcherApps.getActivityList(null, Process.myUserHandle())
        .distinctBy { it.applicationInfo.packageName }
        .map { it.applicationInfo.packageName to it.label.toString() }
        .sortedBy { it.second.lowercase() }

    val allowed = AppLauncherService.allowedPackages(this).toMutableSet()

    val listLayout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(8), dp(16), dp(8))
    }
    for ((index, app) in apps.withIndex()) {
      val (packageName, label) = app
      if (index != 0) listLayout.addView(MaterialDivider(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
          topMargin = dp(4); bottomMargin = dp(4)
        }
      })

      val checkBox = MaterialCheckBox(this)
      checkBox.text = label
      checkBox.isChecked = packageName in allowed
      checkBox.setOnCheckedChangeListener { _, checked ->
        if (checked) allowed.add(packageName) else allowed.remove(packageName)
        AppLauncherService.setAllowedPackages(this, allowed)
      }
      listLayout.addView(checkBox)
    }

    layout.addView(MaterialCardView(this).apply {
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
      radius = dp(12).toFloat()
      cardElevation = dp(1).toFloat()
      useCompatPadding = true
      addView(listLayout)
    })

    setContentView(ScrollView(this).apply { addView(layout) })
  }

  override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
    if (item.itemId == android.R.id.home) { finish(); return true }
    return super.onOptionsItemSelected(item)
  }
}
