package com.aesphome

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log


/*

  App Launcher
    select.launch_app — deliberately NOT an arbitrary Intent executor exposed to the
    network: HA can only ever launch one of the apps the user explicitly allowed on the
    Allowed Apps screen (AppLauncherSettingsActivity). Same "command dropdown" pattern
    BluetoothCommandService already uses (onCommand fires once, then the entity resets to
    its placeholder default) — reused as-is rather than inventing a new entity shape.

*/


private const val NO_APP = "Select an app"
private const val WHITELIST_KEY = "app_launcher_whitelist" // Set<String> of package names

object AppLauncherService : Service {
  override val id                  = "app_launcher"
  override val label               = "App Launcher"
  override val description         = "Choose which apps are allowed from the Allowed Apps screen"
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:apps"

  // label -> package name, rebuilt by refreshOptions() from the current whitelist.
  private var labelToPackage: Map<String, String> = emptyMap()

  val launchSetting = SelectSetting(
      id                 = "launch_app",
      label              = "Launch App",
      options            = listOf(NO_APP), // placeholder — replaced by refreshOptions() with the real whitelist
      default            = NO_APP,
      deviceUi           = false,
      homeAssistant      = true,
      enabledByDefaultHa = false,
      icon               = "mdi:application-outline",
      onCommand          = ::launchApp)

  override val selectSettings: List<SelectSetting> = listOf(launchSetting)

  override fun start(context: Context) = refreshOptions(context)
  override fun stop(context: Context) {}

  fun allowedPackages(context: Context): Set<String> = getStringSetFlag(context, WHITELIST_KEY)

  fun setAllowedPackages(context: Context, packages: Set<String>) {
    setStringSetFlag(context, WHITELIST_KEY, packages)
    refreshOptions(context)
  }

  // Called on start() and whenever the Allowed Apps screen changes the whitelist — rebuilds
  // the dropdown's option list from whichever of those packages are still actually
  // installed (one could be uninstalled since being allowed).
  fun refreshOptions(context: Context) {
    val pm = context.packageManager
    val entries = allowedPackages(context).mapNotNull { pkg ->
      val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                  catch (e: PackageManager.NameNotFoundException) { return@mapNotNull null }
      label to pkg
    }.sortedBy { it.first }

    labelToPackage = entries.toMap()
    launchSetting.options = listOf(NO_APP) + entries.map { it.first }
  }

  private fun launchApp(context: Context, label: String) {
    if (label == NO_APP) return
    val pkg = labelToPackage[label] ?: run { Log.e(TAG, "App Launcher: '$label' is no longer allowed"); return }
    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
    if (intent == null) { Log.e(TAG, "App Launcher: no launch intent for $pkg"); return }
    try {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
      Log.i(TAG, "App Launcher: launched $pkg")
    } catch (e: Exception) {
      Log.e(TAG, "App Launcher: failed to launch $pkg", e)
    }
  }
}
