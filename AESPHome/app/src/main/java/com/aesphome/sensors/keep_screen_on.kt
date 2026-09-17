package com.aesphome

import android.content.Context
import android.os.PowerManager
import android.util.Log


/*

  Keep Screen On
    switch.keep_screen_on — for a device left mounted as a wall dashboard, this is meant to
    be held ON indefinitely, which is exactly what a screen-bright WakeLock does; it's
    released the moment the switch (or the whole feature) is turned off, so it's never an
    accidental leak — only an intentional, user-controlled hold. Survives service restarts
    and reboots via the persisted flag (re-applied from start(), which start_at_boot's
    service also runs through).

*/


object KeepScreenOnSwitch : SwitchEntity {
  override val id                  = "keep_screen_on"
  override val label               = "Keep Screen On"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:cellphone-lock"

  private var wakeLock: PowerManager.WakeLock? = null

  override fun isOn(context: Context): Boolean = getFlag(context, "keep_screen_on_state", false)

  override fun setOn(context: Context, on: Boolean) {
    setFlag(context, "keep_screen_on_state", on)
    apply(context, on)
  }

  override fun start(context: Context) {
    apply(context, isOn(context))
    AESPHomeService.instance?.reportSwitch(this, isOn(context))
  }

  override fun stop(context: Context) = apply(context, false)

  private fun apply(context: Context, on: Boolean) {
    if (on) {
      if (wakeLock?.isHeld == true) return
      val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      @Suppress("DEPRECATION")
      val lock = powerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "AESPHome:keepScreenOn")
      lock.acquire() // intentionally held with no timeout — released explicitly below, not left to expire
      wakeLock = lock
      Log.i(TAG, "Keep Screen On enabled")
    } else {
      wakeLock?.let { if (it.isHeld) it.release() }
      wakeLock = null
      Log.i(TAG, "Keep Screen On disabled")
    }
  }
}
