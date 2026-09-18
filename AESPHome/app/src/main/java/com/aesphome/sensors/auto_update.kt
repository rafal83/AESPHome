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
    update.aesphome_firmware — a real ESPHome `update` entity (AutoUpdateService implements
    both Service and UpdateEntity: the periodic-check background feature and the HA entity are
    the same object), giving the exact same "Update available" card with Install/Check buttons
    a real ESPHome device's own OTA flow shows, rather than a plain binary_sensor + buttons.

    Checks this app's own GitHub fork (rafal83/AESPHome) for a newer tagged release than the
    one currently installed. Actually installing it still needs one tap from the user on
    Android's own "Install this app?" confirmation screen — there is no way around that
    without root or being the device owner (this project targets neither), so the UPDATE
    command downloads the release APK and hands it straight to the system installer rather
    than pretending to install it silently. Same pattern any sideloaded-app updater (F-Droid,
    Obtainium, ...) uses.

    Uses only what's already on the platform for the HTTP+JSON part (HttpURLConnection,
    org.json) — no new networking/JSON library — and androidx.core only for FileProvider
    (installing from a raw file:// path is blocked by StrictMode on API 24+).

*/


private const val REPO_OWNER = "rafal83"
private const val REPO_NAME = "AESPHome"
private const val GITHUB_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
private const val UPDATE_APK_FILENAME = "update.apk"
private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

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

object AutoUpdateService : Service, UpdateEntity {
  override val id                  = "auto_update"
  override val label               = "AESPHome Firmware"
  override val description         = "Periodically checks github.com/$REPO_OWNER/$REPO_NAME for a newer release"
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val icon                = "mdi:update"
  override val key: Int            = id.hashCode()

  val intervalSetting = Setting(
      id = "auto_update_interval_hours", label = "Update Check Interval (h)",
      default = 24f, min = 1f, max = 168f, step = 1f,
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false, icon = "mdi:timer-outline")

  override val settings: List<Setting> = listOf(intervalSetting)

  @Volatile private var running = false
  private var timerThread: Thread? = null
  private var appContext: Context? = null

  // Set by a successful check, consumed by the UPDATE command — the actual asset URL to
  // download, not just "yes/no an update exists".
  @Volatile private var pendingApkUrl: String? = null
  @Volatile private var pendingVersion: String? = null

  private val currentVersion get() = "v${BuildConfig.VERSION_NAME}"

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

  override fun onCheckCommand(context: Context) = checkNow(context)
  override fun onUpdateCommand(context: Context) = downloadAndInstall(context)

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
      val releaseUrl = json.optString("html_url", "")

      val isNewer = compareVersions(latestTag, currentVersion) > 0
      pendingApkUrl = null
      pendingVersion = null

      if (isNewer) {
        val assets = json.optJSONArray("assets")
        for (i in 0 until (assets?.length() ?: 0)) {
          val asset = assets!!.getJSONObject(i)
          if (asset.optString("name").endsWith(".apk")) {
            pendingApkUrl = asset.optString("browser_download_url")
            pendingVersion = latestTag
            break
          }
        }
        Log.i(TAG, "Auto update: $latestTag available (current $currentVersion)")
      }

      // latestVersion reported as the current one when nothing newer was found — HA's update
      // entity shows "up to date" exactly when these two are equal.
      AESPHomeService.instance?.pushUpdateState(this, UpdateState(
        currentVersion = currentVersion,
        latestVersion = if (isNewer) latestTag else currentVersion,
        releaseUrl = if (isNewer) releaseUrl else "",
      ))
    } catch (e: Exception) {
      Log.e(TAG, "Auto update: check failed: ${e.message}")
    }
  }

  // Downloads the update (if one was found by the last check) and hands it to the system
  // installer — this always shows Android's own confirmation UI; there is no silent path.
  // Reports progress via pushUpdateState() throughout, so HA's update card shows a real
  // progress bar rather than just sitting on "in progress" for the whole download.
  fun downloadAndInstall(context: Context) {
    val url = pendingApkUrl
    val version = pendingVersion
    if (url == null || version == null) {
      Log.e(TAG, "Auto update: install requested but no update is pending — checking now")
      checkNow(context)
      return
    }
    Thread({
      fun report(inProgress: Boolean, progress: Float?) {
        AESPHomeService.instance?.pushUpdateState(this, UpdateState(
          currentVersion = currentVersion, latestVersion = version,
          inProgress = inProgress, progress = progress,
        ))
      }
      report(inProgress = true, progress = null)
      try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
          instanceFollowRedirects = true
          connectTimeout = 10_000
          readTimeout = 30_000
        }
        if (connection.responseCode !in 200..299) {
          Log.e(TAG, "Auto update: APK download returned ${connection.responseCode}")
          connection.disconnect()
          report(inProgress = false, progress = null)
          return@Thread
        }
        val totalBytes = connection.contentLengthLong // -1 if the server didn't send one
        val outFile = File(context.cacheDir, UPDATE_APK_FILENAME)
        var downloaded = 0L
        connection.inputStream.use { input ->
          FileOutputStream(outFile).use { output ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
              val read = input.read(buffer)
              if (read < 0) break
              output.write(buffer, 0, read)
              downloaded += read
              if (totalBytes > 0) report(inProgress = true, progress = (downloaded * 100f / totalBytes))
            }
          }
        }
        connection.disconnect()

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(apkUri, "application/vnd.android.package-archive")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        Log.i(TAG, "Auto update: downloaded $version, launched installer")
        report(inProgress = false, progress = null)
      } catch (e: Exception) {
        Log.e(TAG, "Auto update: download/install failed", e)
        report(inProgress = false, progress = null)
      }
    }, "AESPHomeAutoUpdateInstall").start()
  }
}
