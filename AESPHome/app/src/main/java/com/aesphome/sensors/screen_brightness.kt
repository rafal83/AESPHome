package com.aesphome

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlin.math.roundToInt


/*

  Screen Brightness
    Exposes number.screen_brightness (0-100%), backed by the system-wide
    Settings.System.SCREEN_BRIGHTNESS (0-255) when WRITE_SETTINGS has been granted (see the
    Permissions screen) — this is the only way to change brightness outside of the app's own
    window on modern Android. Without that permission, the value can still be read here, but
    HA's writes are silently ignored by the OS; there's no in-app fallback for a Service-only
    feature (no window to dim).

    A ContentObserver mirrors manual brightness changes (physical slider, another app) back
    into the persisted Setting/HA — guarded on both sides so applying our own write doesn't
    immediately bounce back into another write of the same value.

*/


object ScreenBrightnessService : Service {
  override val id                  = "screen_brightness"
  override val label               = "Screen Brightness"
  override val description         = "Requires \"Modify system settings\" permission"
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:brightness-6"

  val brightnessSetting = Setting(
      id                 = "screen_brightness_pct",
      label              = "Screen Brightness",
      default            = 50f,
      min                = 1f,
      max                = 100f,
      step               = 1f,
      deviceUi           = true,
      homeAssistant      = true,
      icon               = "mdi:brightness-6",
      onChanged          = ::applyBrightness)

  override val settings: List<Setting> = listOf(brightnessSetting)

  private var observer: ContentObserver? = null

  override fun start(context: Context) {
    val handler = Handler(Looper.getMainLooper())
    val localObserver = object : ContentObserver(handler) {
      override fun onChange(selfChange: Boolean) {
        val raw = try { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }
                  catch (e: Settings.SettingNotFoundException) { return }
        val pct = rawToPct(raw)
        if (pct == getSetting(context, brightnessSetting)) return // our own write echoing back — skip
        Log.i(TAG, "Screen brightness changed externally: $pct%")
        setSetting(context, brightnessSetting, pct) // persists WITHOUT re-invoking onChanged (see setSetting) — avoided by the equality check above anyway
        AESPHomeService.instance?.reportSetting(brightnessSetting, pct)
      }
    }
    observer = localObserver
    context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, localObserver)

    // Seed the persisted setting from the device's actual current brightness on first-ever
    // enable, same pattern CameraService uses for JPEG quality — otherwise the first value
    // reported to HA would be an arbitrary compiled-in default instead of reality.
    if (!hasSetting(context, brightnessSetting)) {
      try {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        setSetting(context, brightnessSetting, rawToPct(raw))
      } catch (e: Settings.SettingNotFoundException) {}
    }
  }

  override fun stop(context: Context) {
    observer?.let { context.contentResolver.unregisterContentObserver(it) }
    observer = null
  }

  private fun rawToPct(raw: Int): Float = (raw * 100f / 255f).roundToInt().coerceIn(1, 100).toFloat()
  private fun pctToRaw(pct: Float): Int = (pct * 255f / 100f).roundToInt().coerceIn(1, 255)

  private fun applyBrightness(context: Context) {
    if (!Settings.System.canWrite(context)) {
      Log.e(TAG, "Screen brightness: WRITE_SETTINGS not granted — cannot change system brightness")
      return
    }
    val wantedRaw = pctToRaw(getSetting(context, brightnessSetting))
    val currentRaw = try { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (e: Settings.SettingNotFoundException) { -1 }
    if (wantedRaw == currentRaw) return // avoids an immediate, redundant ContentObserver bounce
    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, wantedRaw)
    Log.i(TAG, "Screen brightness set to ${getSetting(context, brightnessSetting)}%")
  }
}
