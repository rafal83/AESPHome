package com.aesphome

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject


/*

  Auto Update
    update.aesphome_firmware — a real ESPHome `update` entity (AutoUpdateService implements
    both Service and UpdateEntity: the periodic-check background feature and the HA entity are
    the same object), giving the exact same "Update available" card with Install/Check buttons
    a real ESPHome device's own OTA flow shows, rather than a plain binary_sensor + buttons.

    Checks this app's own GitHub fork (rafal83/AESPHome) for a newer tagged release than the
    one currently installed. Two install paths, chosen automatically:

      - Device Owner (isDeviceOwner(), utils.kt) — a genuinely silent install via
        PackageInstaller.Session.commit(), no tap required. Device Owner has no in-app grant
        flow; it's set once via `adb shell dpm set-device-owner
        com.aesphome/.AESPHomeDeviceAdminReceiver` before any account is added on the device
        (or after a factory reset) — see the Permissions screen and FAQ.md.
      - Otherwise — the release APK is downloaded and handed to the system installer via a
        FileProvider URI; the user still taps "Install" on Android's own confirmation screen.
        Same pattern any sideloaded-app updater (F-Droid, Obtainium, ...) uses without special
        device management privileges.

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

// A GitHub Release asset is only ever a candidate update APK if its name ends in .apk AND
// doesn't look like an unsigned or debug build (release.yml names those "*-unsigned.apk";
// a debug CI artifact would say "*-debug.apk") — installing either over a real, signed
// install would either fail outright or silently downgrade the install to something nobody
// can verify. `allowDebugOrUnsigned` only ever comes from BuildConfig.DEBUG (checkNow()
// below) — a real signed release build of this app can never set it, so this can't be
// misconfigured by an end user; it only lets a developer's own debug build of AESPHome
// self-update to another debug build while testing.
internal fun isAcceptableUpdateApkName(name: String, allowDebugOrUnsigned: Boolean): Boolean {
  val lower = name.lowercase()
  if (!lower.endsWith(".apk")) return false
  val looksUnsafe = lower.contains("unsigned") || lower.contains("debug")
  return !looksUnsafe || allowDebugOrUnsigned
}

// Parses either a bare 64-hex-char digest or the standard `sha256sum` output format
// ("<hash>  <filename>") — release.yml (see .github/workflows/release.yml) produces the
// latter. Returns null (never throws) for anything that isn't a well-formed SHA-256 hex
// digest, so a corrupted/truncated checksum asset fails safe as "can't verify" rather than
// as a false match.
internal fun parseSha256Checksum(text: String): String? {
  val token = text.trim().split(Regex("\\s+")).firstOrNull() ?: return null
  return if (token.length == 64 && token.all { it in "0123456789abcdefABCDEF" }) token.lowercase() else null
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
  // download, not just "yes/no an update exists". pendingSha256Url is null when the release
  // has no matching "<apk-name>.sha256" asset (e.g. one published before this checksum
  // feature existed) — downloadAndInstall() treats that as "can't verify" and proceeds
  // rather than permanently refusing to update past that point.
  @Volatile private var pendingApkUrl: String? = null
  @Volatile private var pendingSha256Url: String? = null
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

  // `onResult`, when given, is invoked with the resulting UpdateState (or null on failure —
  // network error, non-200 response, unparseable release) — used by MainActivity's manual
  // "Check for Updates Now" button to show immediate feedback, in addition to the state always
  // being pushed to HA via pushUpdateState() below. Runs its network call on the calling
  // thread (blocking), same as before this parameter was added — callers off the main thread.
  fun checkNow(context: Context, onResult: ((UpdateState?) -> Unit)? = null) {
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
          onResult?.invoke(null)
          return
        }
        connection.inputStream.bufferedReader().use { it.readText() }
      } finally {
        connection.disconnect()
      }

      val json = JSONObject(body)
      val latestTag = json.optString("tag_name", "")
      if (latestTag.isEmpty()) {
        onResult?.invoke(null)
        return
      }
      val releaseUrl = json.optString("html_url", "")

      val isNewer = compareVersions(latestTag, currentVersion) > 0
      pendingApkUrl = null
      pendingSha256Url = null
      pendingVersion = null

      if (isNewer) {
        val assets = json.optJSONArray("assets")
        val assetList = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
        // Prefer a name containing "release" when more than one acceptable .apk asset
        // exists — release.yml always names the real signed artifact "*-release.apk".
        val apkAsset = assetList
          .filter { isAcceptableUpdateApkName(it.optString("name"), allowDebugOrUnsigned = BuildConfig.DEBUG) }
          .sortedByDescending { it.optString("name").lowercase().contains("release") }
          .firstOrNull()
        if (apkAsset != null) {
          val apkName = apkAsset.optString("name")
          pendingApkUrl = apkAsset.optString("browser_download_url")
          pendingSha256Url = assetList.firstOrNull { it.optString("name") == "$apkName.sha256" }
              ?.optString("browser_download_url")
          pendingVersion = latestTag
          Log.i(TAG, "Auto update: $latestTag available (current $currentVersion)")
        } else {
          Log.e(TAG, "Auto update: $latestTag has no acceptable .apk asset — not offering it")
        }
      }

      // latestVersion reported as the current one when nothing newer was found — HA's update
      // entity shows "up to date" exactly when these two are equal.
      val state = UpdateState(
        currentVersion = currentVersion,
        latestVersion = if (isNewer) latestTag else currentVersion,
        releaseUrl = if (isNewer) releaseUrl else "",
      )
      AESPHomeService.instance?.pushUpdateState(this, state)
      onResult?.invoke(state)
    } catch (e: Exception) {
      Log.e(TAG, "Auto update: check failed: ${e.message}")
      onResult?.invoke(null)
    }
  }

  // Downloads the update (if one was found by the last check) and hands it to the system
  // installer — this always shows Android's own confirmation UI; there is no silent path.
  // Reports progress via pushUpdateState() throughout, so HA's update card shows a real
  // progress bar rather than just sitting on "in progress" for the whole download.
  fun downloadAndInstall(context: Context) {
    val url = pendingApkUrl
    val version = pendingVersion
    val sha256Url = pendingSha256Url
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

        if (totalBytes > 0 && downloaded != totalBytes) {
          Log.e(TAG, "Auto update: incomplete download ($downloaded/$totalBytes bytes) — discarding")
          outFile.delete()
          report(inProgress = false, progress = null)
          return@Thread
        }

        // Never install anything without checking it against the checksum GitHub Actions
        // computed for it (see .github/workflows/release.yml) — a corrupted download, a
        // tampered mirror, or a truncated transfer that happened to still match totalBytes
        // must never reach the installer. sha256Url is only null for a release published
        // before this checksum feature existed (see pendingSha256Url's doc comment above);
        // that's the one case this proceeds without verification.
        if (sha256Url != null) {
          val expected = try {
            (URL(sha256Url).openConnection() as HttpURLConnection).apply {
              connectTimeout = 10_000; readTimeout = 10_000
            }.let { conn ->
              try {
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.bufferedReader().use { it.readText() }.let(::parseSha256Checksum)
              } finally { conn.disconnect() }
            }
          } catch (e: Exception) { null }

          if (expected == null) {
            Log.e(TAG, "Auto update: could not fetch/parse checksum — refusing to install")
            outFile.delete()
            report(inProgress = false, progress = null)
            return@Thread
          }
          val actual = sha256Hex(outFile)
          if (!actual.equals(expected, ignoreCase = true)) {
            Log.e(TAG, "Auto update: checksum mismatch — refusing to install")
            outFile.delete()
            report(inProgress = false, progress = null)
            return@Thread
          }
          Log.i(TAG, "Auto update: checksum valid")
        } else {
          Log.e(TAG, "Auto update: no checksum asset for $version — installing unverified")
        }

        if (isDeviceOwner(context)) {
          installSilently(context, outFile, version)
          // Silent install is asynchronous too (PackageInstaller delivers its result via
          // AESPHomeUpdateInstallReceiver below) — report() here just clears the download's
          // own progress bar; the receiver reports the final in_progress=false.
          report(inProgress = true, progress = null)
        } else {
          val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
          val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          context.startActivity(intent)
          Log.i(TAG, "Auto update: downloaded $version, launched installer")
          report(inProgress = false, progress = null)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Auto update: download/install failed", e)
        report(inProgress = false, progress = null)
      }
    }, "AESPHomeAutoUpdateInstall").start()
  }

  private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  // Device Owner only — PackageInstaller.Session.commit() from a Device Owner app installs
  // without showing the normal confirmation UI (true since PackageInstaller existed, API 21;
  // setRequireUserAction(false), API 31+, makes that explicit rather than implicit). The
  // commit's actual result (success/failure, or — defensively — a device that still wants
  // confirmation despite Device Owner status) arrives asynchronously via
  // AESPHomeUpdateInstallReceiver, not here.
  private fun installSilently(context: Context, apkFile: File, version: String) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
    }
    var sessionId = -1
    try {
      sessionId = installer.createSession(params)
      installer.openSession(sessionId).use { session ->
        apkFile.inputStream().use { input ->
          session.openWrite("aesphome_update", 0, apkFile.length()).use { output ->
            input.copyTo(output)
            session.fsync(output)
          }
        }
        val receiverIntent = Intent(context, AESPHomeUpdateInstallReceiver::class.java).apply {
          putExtra(AESPHomeUpdateInstallReceiver.EXTRA_VERSION, version)
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0)
        val pendingIntent = PendingIntent.getBroadcast(context, sessionId, receiverIntent, pendingIntentFlags)
        session.commit(pendingIntent.intentSender)
        Log.i(TAG, "Auto update: silent install of $version committed (session $sessionId)")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Auto update: silent install failed", e)
      if (sessionId >= 0) try { installer.abandonSession(sessionId) } catch (e2: Exception) {}
      AESPHomeService.instance?.pushUpdateState(this, UpdateState(currentVersion = currentVersion, latestVersion = version))
    }
  }
}

// Receives PackageInstaller's asynchronous commit result for a silent (Device Owner) install
// — success, failure, or (defensively, in case a specific device/OEM doesn't fully honor
// Device Owner's silent-install privilege) a fallback to the normal confirmation UI.
class AESPHomeUpdateInstallReceiver : BroadcastReceiver() {
  companion object { const val EXTRA_VERSION = "version" }

  override fun onReceive(context: Context, intent: Intent) {
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    val version = intent.getStringExtra(EXTRA_VERSION) ?: "v${BuildConfig.VERSION_NAME}"
    when (status) {
      PackageInstaller.STATUS_SUCCESS -> {
        Log.i(TAG, "Auto update: silent install of $version succeeded")
        // The app is about to be replaced/restarted by the OS — no further state push needed.
      }
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        Log.e(TAG, "Auto update: device did not honor silent install — falling back to confirmation UI")
        @Suppress("DEPRECATION")
        val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        try {
          confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          confirmIntent?.let { context.startActivity(it) }
        } catch (e: Exception) { Log.e(TAG, "Auto update: could not show install confirmation", e) }
      }
      else -> {
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.e(TAG, "Auto update: silent install of $version failed: status=$status message=$message")
        AESPHomeService.instance?.pushUpdateState(AutoUpdateService, UpdateState(
          currentVersion = "v${BuildConfig.VERSION_NAME}", latestVersion = version,
        ))
      }
    }
  }
}
