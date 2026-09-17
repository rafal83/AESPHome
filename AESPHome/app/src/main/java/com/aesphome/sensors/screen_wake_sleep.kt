package com.aesphome

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log


/*

  Screen Wake / Sleep
    button.screen_wake: briefly wakes the screen with a timed WakeLock (never held past its
    own timeout, so a lost release can't leave it on forever) and tries to bring AESPHome to
    the foreground — best-effort, since Android 10+ can silently refuse to start an Activity
    from a background Service depending on the device's current state.

    button.screen_sleep: DevicePolicyManager.lockNow() when Device Admin is active — a real
    screen-off/lock, no root needed. Without Device Admin there is no public, non-root API to
    turn the screen off from here, so the fallback only dims the app's own window to its
    minimum while it happens to be visible, and is documented as exactly that: not a real
    screen off.

*/


private const val WAKE_DURATION_MS = 10_000L


// Registered as a <receiver> in the manifest with BIND_DEVICE_ADMIN — required for
// DevicePolicyManager.isAdminActive()/lockNow() to do anything. Every callback is optional;
// none are overridden because this app only ever calls lockNow(), which needs no ongoing
// policy enforcement.
class AESPHomeDeviceAdminReceiver : DeviceAdminReceiver()

fun deviceAdminComponent(context: Context): ComponentName = ComponentName(context, AESPHomeDeviceAdminReceiver::class.java)

fun isDeviceAdminActive(context: Context): Boolean =
    (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
        ?.isAdminActive(deviceAdminComponent(context)) == true

// Opens Android's own "Activate this device admin app?" screen — used by the Permissions
// screen's Enable button, never called automatically.
fun requestDeviceAdmin(context: Context) {
  val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent(context))
    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets ÆSPHome's Screen Sleep button lock the screen from Home Assistant.")
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  context.startActivity(intent)
}


object ScreenWakeButton : Button {
  override val id                  = "screen_wake"
  override val label               = "Screen Wake"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:cellphone-arrow-down"

  override fun press(context: Context) {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    @Suppress("DEPRECATION")
    val wakeLock = powerManager.newWakeLock(
        PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
        "AESPHome:screenWake")
    wakeLock.acquire(WAKE_DURATION_MS) // self-releasing — never held past this, even if release() below never runs
    Log.i(TAG, "Screen wake requested")

    try {
      val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    } catch (e: Exception) {
      // Android 10+ can refuse a background-started Activity outright depending on device
      // state — the WakeLock above still turned the screen on either way.
      Log.e(TAG, "Could not bring ÆSPHome to the foreground", e)
    }
  }
}

object ScreenSleepButton : Button {
  override val id                  = "screen_sleep"
  override val label               = "Screen Sleep"
  override val description         = "Locks the screen if Device Admin is enabled; otherwise only dims the app while visible"
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:cellphone-arrow-up"

  override fun press(context: Context) {
    if (isDeviceAdminActive(context)) {
      (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
      Log.i(TAG, "Screen locked via Device Admin")
      return
    }

    Log.e(TAG, "Screen sleep: Device Admin not enabled — dimming app window only (not a real screen off)")
    MainActivity.instance?.let { activity ->
      val params = activity.window.attributes
      params.screenBrightness = 0.01f
      activity.window.attributes = params
    }
  }
}
