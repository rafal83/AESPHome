package com.aesphome

import android.content.Context

private const val PREFS_NAME = "aesphome_settings"

// Generic on/off toggle, keyed by id — every Sensor and Service shares this, so
// adding a new one never requires a new getter/setter pair here.
fun isEnabled(context: Context, component: Toggleable): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(component.id, component.enabledByDefaultApp)

fun setEnabled(context: Context, component: Toggleable, enabled: Boolean) {
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(component.id, enabled).apply()
}

// Generic per-Sensor setting value, keyed by id — same pattern as isEnabled/setEnabled.
fun getSetting(context: Context, setting: Setting): Float =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(setting.id, setting.default)

// Returns the clamped value actually persisted, so callers that need to report it
// elsewhere (e.g. back to HA) never accidentally echo the pre-clamp input instead.
fun setSetting(context: Context, setting: Setting, value: Float): Float {
  val clamped = value.coerceIn(setting.min, setting.max)
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(setting.id, clamped).apply()
  setting.onChanged?.invoke(context)
  return clamped
}

// Same generic-by-id pattern as getSetting/setSetting, but for SelectSetting's string options.
fun getSelectSetting(context: Context, setting: SelectSetting): String =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(setting.id, setting.default) ?: setting.default

fun setSelectSetting(context: Context, setting: SelectSetting, value: String) {
  if (value !in setting.options) return // ignore anything that isn't one of the fixed choices
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(setting.id, value).apply()
  setting.onChanged?.invoke(context)
}

// Generic persisted boolean, independent of a Toggleable's own enable flag (isEnabled/
// setEnabled above) — for a SwitchEntity whose HA-visible on/off state is more than just
// "is this feature turned on at all" (e.g. keep_screen_on, start_at_boot, the Bluetooth
// proxy's scan state). Keyed by an arbitrary string so callers don't need a Setting/
// SelectSetting object just to store one bit.
fun getFlag(context: Context, key: String, default: Boolean): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, default)

fun setFlag(context: Context, key: String, value: Boolean) {
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
}

// Same idea as getFlag/setFlag, for a raw string — currently only the MJPEG server's
// auto-generated auth token (mjpeg_server.kt), which isn't a fixed-choice SelectSetting.
fun getStringFlag(context: Context, key: String, default: String): String =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, default) ?: default

fun setStringFlag(context: Context, key: String, value: String) {
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(key, value).apply()
}

// Same idea, for a small set of strings — currently only the app launcher's whitelist of
// allowed package names (sensors/app_launcher.kt).
fun getStringSetFlag(context: Context, key: String): Set<String> =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(key, emptySet()) ?: emptySet()

fun setStringSetFlag(context: Context, key: String, value: Set<String>) {
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putStringSet(key, value).apply()
}

// True if this Setting has ever been explicitly persisted (by the device UI, HA, or code).
// Lets a first-use default be seeded from something other than a fixed compile-time value —
// e.g. CameraService's JPEG quality, seeded once from the camera's own default — without
// every later read re-applying it over a value the user may have since changed.
fun hasSetting(context: Context, setting: Setting): Boolean =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(setting.id)
