package com.aesphome

import android.content.Context
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.util.Log
import android.view.Surface


/*

  Screen Orientation
    Exposes select.screen_orientation (auto/portrait/landscape/reverse_portrait/
    reverse_landscape). Primary mechanism is the same system-wide rotation lock the Quick
    Settings tile uses (Settings.System.ACCELEROMETER_ROTATION + USER_ROTATION), gated behind
    the same WRITE_SETTINGS permission as screen brightness — this actually rotates whatever
    is currently on screen, not just this app. Falls back to locking MainActivity's own
    orientation (only effective while it's the visible activity) when that permission hasn't
    been granted.

    USER_ROTATION's four values (Surface.ROTATION_0/90/180/270) are relative to the device's
    natural orientation, not a fixed "portrait" — on a landscape-primary tablet, ROTATION_0
    is its natural (landscape) orientation. The Portrait/Landscape labels below match what
    Android's own rotation-lock UI shows on a typical phone; a landscape-primary device will
    see them swapped. Documented as a known limitation rather than special-cased per device.

*/


private const val OPTION_AUTO = "Auto"
private const val OPTION_PORTRAIT = "Portrait"
private const val OPTION_LANDSCAPE = "Landscape"
private const val OPTION_REVERSE_PORTRAIT = "Reverse Portrait"
private const val OPTION_REVERSE_LANDSCAPE = "Reverse Landscape"

object ScreenOrientationService : Service {
  override val id                  = "screen_orientation"
  override val label               = "Screen Orientation"
  override val description         = "Requires \"Modify system settings\" permission"
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:screen-rotation"

  val orientationSetting = SelectSetting(
      id            = "screen_orientation_mode",
      label         = "Screen Orientation",
      options       = listOf(OPTION_AUTO, OPTION_PORTRAIT, OPTION_LANDSCAPE, OPTION_REVERSE_PORTRAIT, OPTION_REVERSE_LANDSCAPE),
      default       = OPTION_AUTO,
      deviceUi      = true,
      homeAssistant = true,
      icon          = "mdi:screen-rotation",
      onChanged     = ::applyOrientation)

  override val selectSettings: List<SelectSetting> = listOf(orientationSetting)

  // Re-applies whatever was persisted — cheap, idempotent, and keeps the system rotation
  // lock in sync if it drifted (e.g. the user changed it from Quick Settings) while this
  // service wasn't running.
  override fun start(context: Context) = applyOrientation(context)
  override fun stop(context: Context) {}

  private fun applyOrientation(context: Context) {
    val value = getSelectSetting(context, orientationSetting)

    if (Settings.System.canWrite(context)) {
      val resolver = context.contentResolver
      if (value == OPTION_AUTO) {
        Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
      } else {
        val rotation = when (value) {
          OPTION_PORTRAIT -> Surface.ROTATION_0
          OPTION_LANDSCAPE -> Surface.ROTATION_90
          OPTION_REVERSE_PORTRAIT -> Surface.ROTATION_180
          OPTION_REVERSE_LANDSCAPE -> Surface.ROTATION_270
          else -> Surface.ROTATION_0
        }
        Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        Settings.System.putInt(resolver, Settings.System.USER_ROTATION, rotation)
      }
      Log.i(TAG, "System screen orientation set to $value")
      return
    }

    // Fallback: only affects this app, and only while it's the foreground activity.
    Log.e(TAG, "Screen orientation: WRITE_SETTINGS not granted — locking app window only")
    MainActivity.instance?.requestedOrientation = when (value) {
      OPTION_AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      OPTION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      OPTION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      OPTION_REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
      OPTION_REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
      else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
  }
}
