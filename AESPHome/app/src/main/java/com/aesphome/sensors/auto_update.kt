package com.aesphome

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject


/*

  Auto Update
    Checks this app's own GitHub fork (rafal83/AESPHome) for a newer tagged release than the
    one currently installed, on a timer. Actually installing it still needs one tap from the
    user on Android's own "Install this app?" confirmation screen — there is no way around
    that without root or being the device owner (this project targets neither), so
    button.install_update downloads the update and hands it straight to the system installer
    rather than pretending to install it silently. Same pattern any sideloaded-app updater
    (F-Droid, Obtainium, ...) uses.

    Uses only what's already on the platform for the HTTP+JSON part (HttpURLConnection,
    org.json) — no new networking/JSON library — and androidx.core only for FileProvider
    (installing from a raw file:// path is blocked by StrictMode on API 24+).

*/


private const val REPO_OWNER = "rafal83"
private const val REPO_NAME = "AESPHome"
private const val GITHUB_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
private const val UPDATE_APK_FILENAME = "update.apk"

// Compares two "vX.Y.Z"-ish version strings component-by-component as integers (a leading
// "v" is stripped from either side; a missing/non-numeric component is treated as 0). Pure
// and independent of any network/Context so it's directly unit-testable — see
// AutoUpdateVersionTest.kt. Returns >0 if `current` is newer than `other`, <0 if older, 0 if
// equal (matches java.lang.Comparable's contract).
internal fun compareVersions(current: String, other: String): Int {
  val a = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
  val b = other.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
  for (i in 0 until maxOf(a.size, b.size)) {
    val diff = (a.getOrElse(i) { 0 }) - (b.getOrElse(i) { 0 })
    if (diff != 0) return diff
  }
  return 0
}

object AutoUpdateService : Service {
  override val id                  = "auto_update"
  override val label               = "Auto Update Check"
  override val description         = "Periodically checks github.com/$REPO_OWNER/$REPO_NAME for a newer release"
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:update"

  val intervalSetting = Setting(
      id = "auto_update_interval_hours", label = "Update Check Interval (h)",
      default = 24f, min = 1f, max = 168f, step = 1f,
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false, icon = "mdi:timer-outline")

  override val settings: List<Setting> = listOf(intervalSetting)

  @Volatile private var running = false
  private var timerThread: Thread? = null
  private var appContext: Context? = null

  // Set by a successful check, consumed by button.install_update — the actual asset URL to
  // download, not just "yes/no an update exists".
  @Volatile private var pendingApkUrl: String? = null
  @Volatile private var pendingVersion: String? = null

  override fun start(context: Context) {
    appContext = context
    running = true
    timerThread = Thread({ loop() }, "AESPHomeAutoUpdate").apply { start() }
  }

  override fun stop(context: Context) {
    running = false
    timerThread?.interrupt()
    timerThread = null
  }

  private fun loop() {
    while (running) {
      appContext?.let { checkNow(it) }
      val intervalMs = (getSetting(appContext ?: return, intervalSetting) * 3_600_000L).toLong()
      try { Thread.sleep(intervalMs) } catch (_: InterruptedException) {}
    }
  }

  fun checkNow(context: Context) {
    try {
      val connection = (URL(GITHUB_API_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Accept", "application/vnd.github+json")
        connectTimeout = 10_000
        readTimeout = 10_000
      }
      val body = try {
        if (connection.responseCode != 200) {
          Log.e(TAG, "Auto update: GitHub API returned ${connection.responseCode}")
          return
        }
        connection.inputStream.bufferedReader().use { it.readText() }
      } finally {
        connection.disconnect()
      }

      val json = JSONObject(body)
      val latestTag = json.optString("tag_name", "")
      if (latestTag.isEmpty()) return

      val isNewer = compareVersions(latestTag, "v${BuildConfig.VERSION_NAME}") > 0
      LatestVersionSensor.updateValue(context, latestTag)
      UpdateAvailableSensor.updateValue(context, isNewer)

      pendingApkUrl = null
      if (isNewer) {
        val assets = json.optJSONArray("assets")
        for (i in 0 until (assets?.length() ?: 0)) {
          val asset = assets!!.getJSONObject(i)
          val name = asset.optString("name")
          if (name.endsWith(".apk")) {
            pendingApkUrl = asset.optString("browser_download_url")
            pendingVersion = latestTag
            break
          }
        }
        Log.i(TAG, "Auto update: $latestTag available (current v${BuildConfig.VERSION_NAME})")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Auto update: check failed: ${e.message}")
    }
  }

  // Downloads the update (if one was found by the last check) and hands it to the system
  // installer — this always shows Android's own confirmation UI; there is no silent path.
  fun downloadAndInstall(context: Context) {
    val url = pendingApkUrl
    if (url == null) {
      Log.e(TAG, "Auto update: install requested but no update is pending — checking now")
      checkNow(context)
      return
    }
    Thread({
      try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
          instanceFollowRedirects = true
          connectTimeout = 10_000
          readTimeout = 30_000
        }
        if (connection.responseCode !in 200..299) {
          Log.e(TAG, "Auto update: APK download returned ${connection.responseCode}")
          connection.disconnect()
          return@Thread
        }
        val outFile = File(context.cacheDir, UPDATE_APK_FILENAME)
        connection.inputStream.use { input ->
          FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        connection.disconnect()

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(apkUri, "application/vnd.android.package-archive")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        Log.i(TAG, "Auto update: downloaded ${pendingVersion}, launched installer")
      } catch (e: Exception) {
        Log.e(TAG, "Auto update: download/install failed", e)
      }
    }, "AESPHomeAutoUpdateInstall").start()
  }
}

object UpdateAvailableSensor : EventSensor {
  override val id                     = "update_available"
  override val label                  = "Update Available"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val icon                   = "mdi:cloud-download-outline"
  override fun kind(context: Context) = SensorKind.Binary()
  override fun isAvailable(context: Context): Boolean = isEnabled(context, AutoUpdateService)
  override fun start(context: Context) {}
  override fun stop(context: Context) {}

  fun updateValue(context: Context, available: Boolean) {
    AESPHomeService.instance?.reportSensor(this, available)
  }
}

object LatestVersionSensor : TextSensor {
  override val id                  = "latest_available_version"
  override val label               = "Latest Available Version"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.DIAGNOSTIC
  override val icon                = "mdi:tag-outline"
  override fun isAvailable(context: Context): Boolean = isEnabled(context, AutoUpdateService)

  fun updateValue(context: Context, version: String) {
    AESPHomeService.instance?.reportTextSensor(this, version)
  }
}

object CheckForUpdateButton : Button {
  override val id                  = "check_for_update"
  override val label               = "Check For Update"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:refresh"
  override fun isAvailable(context: Context): Boolean = isEnabled(context, AutoUpdateService)
  override fun press(context: Context) = AutoUpdateService.checkNow(context)
}

object InstallUpdateButton : Button {
  override val id                  = "install_update"
  override val label               = "Install Update"
  override val description         = "Downloads the update and opens Android's install confirmation screen"
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:download"
  override fun isAvailable(context: Context): Boolean = isEnabled(context, AutoUpdateService)
  override fun press(context: Context) = AutoUpdateService.downloadAndInstall(context)
}
