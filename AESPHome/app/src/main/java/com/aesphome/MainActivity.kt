package com.aesphome

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

const val TAG = "AESPHome"
private const val CONNECTION_INFO_REFRESH_MS = 2000L

class AESPHomeService : Service() {

  companion object {
    var instance: AESPHome? = null
  }

  override fun onCreate() {
    super.onCreate()

    val channelId = "aesphome_channel"
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(channelId, "ÆSPHome", NotificationManager.IMPORTANCE_LOW)
    )
    val notification = Notification.Builder(this, channelId)
        .setContentTitle("ÆSPHome")
        .setContentText("Running in the background.")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .build()

    // Android 14+ requires the foreground service type actually passed here to be backed
    // by a currently-granted permission for camera/microphone specifically — passing one
    // that isn't yet granted throws instead of just being ignored. Only include a type
    // whose permission is already granted; mediaPlayback carries no such gate. This means
    // the declared type can lag reality if a permission is granted after the service is
    // already running (not re-evaluated automatically) — see docs/IMPLEMENTATION_REPORT.md.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(1, notification, currentForegroundServiceType())
    } else {
      startForeground(1, notification)
    }

    // Some OEM builds (confirmed on Fire OS — see PermissionsActivity's Battery Optimisation
    // row) kill an idle foreground service via App Standby regardless of the manifest's
    // foreground declaration, and also hide the standard UI this app would otherwise point
    // the user at to prevent it. The watchdog alarm below is the actual fix: it doesn't stop
    // the OS from killing the service, it just notices and restarts it.
    scheduleWatchdog(this)

    // AESPHome.start() blocks forever (accept loop), so it needs its own thread.
    // Startup happens here too, after `instance` is set, so anything that reports
    // immediately (like ScreenStateSensor) can't race ahead of it being assigned.
    // Nothing is named directly — anything Startable in the registry (EventSensor,
    // Service, SwitchEntity, ...) starts the same way, so a new one needs no changes here.
    Thread({
      val aesphome = AESPHome(this)
      instance = aesphome

      for (component in Sensors.toggleables) {
        if (component is Startable && isEnabled(applicationContext, component)) {
          component.start(applicationContext)
        }
      }

      aesphome.start()
    }, "AESPHomeInit").start()
  }

  override fun onDestroy() {
    super.onDestroy()
    for (component in Sensors.toggleables) {
      if (component is Startable) component.stop(applicationContext)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  // Every type here is also declared on the <service> in the manifest — this only ever
  // narrows that set down to what's actually permitted right now, never widens it.
  private fun currentForegroundServiceType(): Int {
    var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
      type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    }
    return type
  }
}

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!StartAtBootSwitch.isOn(context)) return
    try {
      context.startForegroundService(Intent(context, AESPHomeService::class.java))
    } catch (e: Exception) {
      // Android 12+ can refuse a foreground service start from certain background/broadcast
      // contexts — BOOT_COMPLETED is normally exempt, but this keeps a future OS restriction
      // from crashing the receiver instead of just leaving the service stopped.
      Log.e(TAG, "startForegroundService from boot failed", e)
    }
  }
}

private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

// Self-rescheduling watchdog alarm: fires every ~15min, asks the OS to (re)start
// AESPHomeService, then arms itself again — independent of whether the process handling this
// broadcast is the same one the service last ran in. This exists because some OEM builds kill
// an idle foreground service outright (confirmed on Fire OS via logcat: "Stopping service due
// to app idle" after ~14h) despite the foreground declaration that's supposed to exempt it,
// and — on Fire OS specifically — also hide the standard battery-optimisation-exemption UI a
// user would otherwise use to prevent that (see PermissionsActivity's Battery Optimisation
// row). startForegroundService() on an already-running service is a harmless no-op
// (onCreate() only reruns if the process was actually killed), so this never double-starts
// anything — it only matters on the runs where the service really is dead.
// setAndAllowWhileIdle (not setRepeating, which Doze can defer for hours) is the standard
// pattern for a periodic task that must still eventually fire while idle.
internal fun scheduleWatchdog(context: Context) {
  if (!StartAtBootSwitch.isOn(context)) return // same switch that gates BootReceiver — "keep AESPHome running persistently"
  val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
  val flags = PendingIntent.FLAG_UPDATE_CURRENT or
      (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
  val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(context, WatchdogReceiver::class.java), flags)
  val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
  } else {
    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
  }
}

class WatchdogReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    try {
      context.startForegroundService(Intent(context, AESPHomeService::class.java))
    } catch (e: Exception) {
      Log.e(TAG, "watchdog: startForegroundService failed", e)
    }
    scheduleWatchdog(context) // re-arm regardless of outcome above — this loop is the whole point
  }
}

class MainActivity : Activity() {
  companion object {
    // Weak-in-spirit reference for features that need to touch the live Activity window
    // while it happens to be visible (orientation fallback, screen-sleep dim fallback) —
    // cleared in onDestroy() below so nothing holds this past the Activity's own lifecycle.
    var instance: MainActivity? = null
  }

  private val settingInputs = mutableListOf<Pair<Setting, EditText>>()
  private val selectSettingSpinners = mutableListOf<Pair<SelectSetting, Spinner>>()
  private var wifiIpText: TextView? = null
  private var haStatusText: TextView? = null
  private var mjpegStatusText: TextView? = null

  // Reposts itself every CONNECTION_INFO_REFRESH_MS so the Wi-Fi/HA lines stay live
  // the whole time this screen is on-screen, not just when it's first opened.
  private val refreshHandler = Handler(Looper.getMainLooper())
  private val refreshRunnable = object : Runnable {
    override fun run() {
      refreshConnectionInfo()
      refreshHandler.postDelayed(this, CONNECTION_INFO_REFRESH_MS)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    instance = this

    // MainActivity can be the very first thing to touch Sensors.selectSettings (e.g. first
    // launch, before AESPHomeService has run CameraService.start()), so refresh this here
    // too rather than assuming the service already did it.
    CameraService.refreshLensOptions(this)
    BluetoothCommandService.refreshOptions(this)
    AppLauncherService.refreshOptions(this)

    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    val statusText = TextView(this)
    statusText.text = "ÆSPHome is running in the background."
    statusText.textSize = 15f
    statusText.setPadding(0, 0, 0, dp(12))
    layout.addView(statusText)

    val wifiIpText = TextView(this)
    val haStatusText = TextView(this)
    val mjpegStatusText = TextView(this)
    val infoCard = card().apply {
      addView(LinearLayout(this@MainActivity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(wifiIpText.apply { setPadding(0, 0, 0, dp(4)) })
        addView(haStatusText.apply { setPadding(0, 0, 0, dp(4)) })
        addView(mjpegStatusText)
      })
    }
    layout.addView(infoCard)

    this.wifiIpText = wifiIpText
    this.haStatusText = haStatusText
    this.mjpegStatusText = mjpegStatusText
    refreshConnectionInfo()

    val permissionsButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
      text = "Permissions"
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(12)
      }
      setOnClickListener { startActivity(Intent(this@MainActivity, PermissionsActivity::class.java)) }
    }
    layout.addView(permissionsButton)

    val appLauncherButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
      text = "Allowed Apps (App Launcher)"
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
      }
      setOnClickListener { startActivity(Intent(this@MainActivity, AppLauncherSettingsActivity::class.java)) }
    }
    layout.addView(appLauncherButton)

    layout.addView(noiseEncryptionCard())

    // Grouped by UiSection (Sensor.kt) instead of one flat alphabetical list of 70+ rows —
    // each section is a collapsible (accordion-style) MaterialCardView: tapping its header
    // toggles the whole section's content, so the screen reads as a short list of section
    // names rather than every single setting for every feature at once. Every section starts
    // collapsed the very first time this screen is ever opened (deliberately not "expanded
    // if anything inside is enabled" — with most sections having *something* on, that made
    // nearly everything start expanded, which read as "no accordion at all"); each section's
    // expanded/collapsed state is then remembered from then on (per section, across app
    // restarts), so it stays exactly how the operator last left it. Still built entirely from
    // registry metadata: a new Sensor/Service/Setting needs no changes here to show up
    // correctly grouped, as long as its id is mapped in Sensor.kt's UI_SECTION_BY_ID (anything
    // missing there falls back to "Other" rather than being dropped).
    val sections = Sensors.toggleables.groupBy { it.uiSection }
    val orderedSections = UiSection.entries.filter { sections.containsKey(it) }
    // Filled in as each component's switch is built below, read back by the conflict-dialog
    // handler so it can visually update the OTHER switch it just disabled (which may belong
    // to a component built earlier or later than the one just toggled) without waiting for
    // the whole screen to be rebuilt.
    val switchByComponent = HashMap<Toggleable, MaterialSwitch>()
    for (section in orderedSections) {
      val groups = sections.getValue(section).sortedBy { it.label }
      val expandedPrefKey = "ui_section_expanded_${section.name}"
      val startExpanded = getFlag(this, expandedPrefKey, false)

      val sectionContent = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(16))
        visibility = if (startExpanded) View.VISIBLE else View.GONE
      }

      // RTSP can't run at the same time as Camera/MJPEG/Person Detection on this hardware
      // (docs/RTSP_PLAN.md) — surfaced here as a standing note, in addition to the switches
      // below actively refusing the conflicting combination, so the constraint is visible
      // even before anyone taps anything.
      if (section == UiSection.CAMERA) {
        sectionContent.addView(TextView(this).apply {
          text = "RTSP Server cannot run at the same time as Camera, MJPEG, or Person " +
              "Detection — they all use the same physical camera. Enabling one disables the others."
          textSize = 12f
          setTextColor(Color.GRAY)
          setPadding(0, 0, 0, dp(12))
        })
      }

      for ((index, component) in groups.withIndex()) {
        if (index != 0) sectionContent.addView(MaterialDivider(this).apply {
          layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8); bottomMargin = dp(8)
          }
        })

        val switch = MaterialSwitch(this)
        switch.text = component.label
        switch.isChecked = isEnabled(this, component)
        switchByComponent[component] = switch

        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header.addView(switch)
        if (component.description.isNotEmpty()) {
          header.addView(TextView(this).apply {
            text = component.description
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(dp(4), 0, 0, 0)
          })
        }
        sectionContent.addView(header)

        val childRows = componentChildRows(component)
        // Hidden rather than greyed out while the toggle is off — its settings don't do
        // anything until it's back on, so there's nothing useful to show in the meantime.
        childRows.forEach { sectionContent.addView(it); it.visibility = if (switch.isChecked) View.VISIBLE else View.GONE }

        // Guards against the programmatic `switch.isChecked = false` below re-entering this
        // same listener as if the operator had tapped it themselves.
        var suppressListener = false
        switch.setOnCheckedChangeListener { _, checked ->
          if (suppressListener) return@setOnCheckedChangeListener

          val conflict = if (checked) cameraConflictFor(this, component) else null
          if (conflict != null) {
            suppressListener = true
            switch.isChecked = false // revert until the operator actually resolves the conflict below
            suppressListener = false

            AlertDialog.Builder(this)
              .setTitle("Camera conflict")
              .setMessage("${component.label} can't run at the same time as ${conflict.label} — " +
                  "they both use this device's physical camera.\n\nDisable ${conflict.label} now to enable ${component.label}?")
              .setPositiveButton("Disable ${conflict.label}") { _, _ ->
                // Prefer toggling the OTHER component's own switch (if it's already been
                // built) so its own listener does the real work consistently, rather than
                // duplicating setEnabled()/stop() here — falls back to doing it directly for
                // a component whose switch isn't on screen (a future conflict pair in a
                // different section, in principle, even though today's only pair is not).
                val otherSwitch = switchByComponent[conflict]
                if (otherSwitch != null) {
                  otherSwitch.isChecked = false
                } else {
                  setEnabled(this, conflict, false)
                  if (conflict is Startable) conflict.stop(applicationContext)
                }
                switch.isChecked = true // re-enter this listener; no conflict now, so it takes the normal path below
              }
              .setNegativeButton("Cancel", null)
              .show()
            return@setOnCheckedChangeListener
          }

          setEnabled(this, component, checked)
          if (component is Startable) {
            if (checked) component.start(applicationContext) else component.stop(applicationContext)
          }
          childRows.forEach { it.visibility = if (checked) View.VISIBLE else View.GONE }
        }

        // A concrete reference (not a generic Toggleable/UpdateEntity hook) since there is
        // exactly one update entity today — same pattern as MjpegServerService/RtspServerService
        // being referenced directly elsewhere in this Activity. Runs independently of the
        // switch above: the switch only governs the periodic background check, not the
        // operator's ability to force one right now. Left enabled/disabled state on the button
        // is the only feedback while the network call runs; the Toast reports the outcome.
        if (component === AutoUpdateService) {
          sectionContent.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Check for Updates Now"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
              topMargin = dp(8)
            }
            setOnClickListener {
              isEnabled = false
              text = "Checking..."
              Thread({
                AutoUpdateService.checkNow(applicationContext) { state ->
                  runOnUiThread {
                    isEnabled = true
                    text = "Check for Updates Now"
                    val message = when {
                      state == null -> "Update check failed — see logs"
                      state.latestVersion != state.currentVersion -> "Update available: ${state.latestVersion}"
                      else -> "Up to date (${state.currentVersion})"
                    }
                    android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
                  }
                }
              }, "AESPHomeManualUpdateCheck").start()
            }
          })
        }
      }

      val chevron = TextView(this).apply {
        text = if (startExpanded) "▾" else "▸"
        textSize = 18f
        setPadding(dp(8), 0, 0, 0)
      }
      val headerRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        isClickable = true
        isFocusable = true
        // Without this, a clickable plain LinearLayout shows literally no visual feedback
        // on tap/press — indistinguishable from a static row, which was reported as "there's
        // no accordion" even though the collapse logic itself was working correctly. The
        // themed ripple/highlight drawable is what actually signals "this is tappable."
        val pressedBackground = android.util.TypedValue().also {
          theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        setBackgroundResource(pressedBackground.resourceId)
        addView(TextView(this@MainActivity).apply {
          text = section.label
          textSize = 16f
          setTypeface(typeface, android.graphics.Typeface.BOLD)
          layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(chevron)
        setOnClickListener {
          val expand = sectionContent.visibility != View.VISIBLE
          sectionContent.visibility = if (expand) View.VISIBLE else View.GONE
          chevron.text = if (expand) "▾" else "▸"
          setFlag(this@MainActivity, expandedPrefKey, expand)
        }
      }

      layout.addView(card().apply {
        addView(LinearLayout(this@MainActivity).apply {
          orientation = LinearLayout.VERTICAL
          addView(headerRow)
          addView(sectionContent)
        })
      })
    }

    // Entity-affecting changes (toggles, lens/resolution picks, etc.) only take effect in
    // HA once it reconnects and re-enumerates entities. Left as an explicit action rather
    // than firing on every change, so several changes can be made before paying for it.
    val refreshButton = MaterialButton(this).apply {
      text = "Refresh Changes / Force Reconnect"
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(16)
      }
      setOnClickListener {
        // Both device lists (paired Bluetooth devices, camera lenses) can change while
        // the app is running, so re-gather them here rather than only at start()/launch
        // — otherwise this button would just re-send the same stale options.
        for ((component, refresh) in listOf<Pair<Toggleable, () -> Unit>>(
          BluetoothCommandService to { BluetoothCommandService.refreshOptions(applicationContext) },
          CameraService to { CameraService.refreshLensOptions(applicationContext) },
        )) {
          if (isEnabled(applicationContext, component)) refresh()
        }
        AESPHomeService.instance?.requestDisconnect()
      }
    }
    layout.addView(refreshButton)

    val shutdownButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
      text = "Shutdown ÆSPHome"
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
      }
      setOnClickListener {
        AlertDialog.Builder(this@MainActivity)
          .setTitle("Shutdown ÆSPHome?")
          .setMessage("This disconnects Home Assistant and stops all background monitoring until you reopen the app.")
          .setPositiveButton("Shutdown") { _, _ -> shutdown() }
          .setNegativeButton("Cancel", null)
          .show()
      }
    }
    layout.addView(shutdownButton)

    setContentView(ScrollView(this).apply { addView(layout) })

    startForegroundService(Intent(this, AESPHomeService::class.java))
  }

  // Tells HA we're going away, tears down the service (which stops every Toggleable —
  // Bluetooth receivers, camera, media player, etc.), then kills the process outright.
  // A plain stopService() isn't enough on its own: AESPHome.start()'s accept loop and its
  // diagnosticsLoop() are bare background threads with no cancellation hook, so they'd
  // keep running past onDestroy() if the process weren't ended too. BootReceiver only
  // relaunches on device boot / package replace, so this stays down until reopened by hand.
  private fun shutdown() {
    AESPHomeService.instance?.requestDisconnect()
    // requestDisconnect() sends asynchronously on its own thread — give it a moment to
    // actually reach HA before the connection is torn down from under it.
    refreshHandler.postDelayed({
      stopService(Intent(this, AESPHomeService::class.java))
      finishAndRemoveTask()
      android.os.Process.killProcess(android.os.Process.myPid())
    }, 300)
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  // A rounded, elevated container used for the connection-info panel and each UiSection —
  // replaces the old flat LinearLayout + manually-drawn divider lines with the platform's
  // standard "grouped settings" look.
  private fun card(): MaterialCardView = MaterialCardView(this).apply {
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(12)
    }
    radius = dp(12).toFloat()
    cardElevation = dp(1).toFloat()
    useCompatPadding = true
  }

  // App-local transport setting, not an HA entity (an entity reachable only through the
  // same transport it configures would be circular) — enable switch, the base64 PSK field
  // (visible, not password-masked: the whole point is copying it into Home Assistant's own
  // ESPHome integration prompt, same as ESPHome's own dashboard shows it), and a Generate
  // button. Plaintext connections keep working unconditionally either way — this only ever
  // adds a second, opt-in way in (see noise.kt's file header for what's and isn't verified).
  private fun noiseEncryptionCard(): View {
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    content.addView(TextView(this).apply {
      text = "API Encryption (Noise)"
      textSize = 16f
      setTypeface(typeface, android.graphics.Typeface.BOLD)
      setPadding(0, 0, 0, dp(4))
    })
    content.addView(TextView(this).apply {
      text = "Optional — wraps the ESPHome API connection in a Noise-encrypted transport " +
          "instead of plaintext. Not yet verified against a real Home Assistant instance; " +
          "leave off unless you've confirmed it works for you, and keep the key private."
      textSize = 12f
      setTextColor(Color.GRAY)
      setPadding(0, 0, 0, dp(8))
    })

    val enableSwitch = MaterialSwitch(this).apply {
      text = "Enable Noise encryption"
      isChecked = NoiseEncryptionSettings.isEnabled(this@MainActivity)
    }
    content.addView(enableSwitch)

    val keyLayout = TextInputLayout(this).apply {
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
      }
      hint = "Encryption key (base64)"
    }
    val keyInput = TextInputEditText(keyLayout.context).apply {
      setText(NoiseEncryptionSettings.getPskBase64(this@MainActivity) ?: "")
      imeOptions = EditorInfo.IME_ACTION_DONE
    }
    fun commitKey() {
      val text = keyInput.text?.toString()?.trim() ?: return
      if (text.isEmpty()) return
      NoiseEncryptionSettings.setPskBase64(this@MainActivity, text)
    }
    keyInput.setOnEditorActionListener { _, actionId, _ ->
      if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
      commitKey(); keyInput.clearFocus(); true
    }
    keyInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitKey() }
    keyLayout.addView(keyInput)
    content.addView(keyLayout)

    val generateButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
      text = "Generate New Key"
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
      }
      setOnClickListener {
        val generated = generateNoisePsk()
        keyInput.setText(generated)
        NoiseEncryptionSettings.setPskBase64(this@MainActivity, generated)
      }
    }
    content.addView(generateButton)

    enableSwitch.setOnCheckedChangeListener { _, checked -> NoiseEncryptionSettings.setEnabled(this, checked) }

    return card().apply { addView(content) }
  }

  // Shows this device's own Wi-Fi IP, and — if Home Assistant is currently connected —
  // its IP and the client name it reported at handshake (e.g. "Home Assistant 2024.8.0").
  private fun refreshConnectionInfo() {
    wifiIpText?.text = "Wi-Fi IP: ${getWifiIpAddress() ?: "not connected"}"

    val ha = AESPHomeService.instance
    val haAddress = ha?.connectedClientAddress
    haStatusText?.text = if (haAddress != null) {
      "Home Assistant: $haAddress (${ha.connectedClientName ?: "unknown"})"
    } else {
      "Home Assistant: not connected"
    }

    val mjpegLine = if (isEnabled(this, MjpegServerService)) "MJPEG: ${MjpegServerService.url(this) ?: "starting..."}" else null
    val rtspLine = if (isEnabled(this, RtspServerService)) "RTSP: ${RtspServerService.url(this) ?: "starting..."}" else null
    mjpegStatusText?.text = listOfNotNull(mjpegLine, rtspLine).joinToString("\n")
  }

  // Builds one component's settings/select-settings rows, clustering any that share a
  // non-null Setting.group/SelectSetting.group (e.g. RTSP's port+bitrate under "Connection",
  // its auth toggle under "Authentication") under a small sub-header, in first-seen group
  // order — e.g. RtspServerService.settings/selectSettings puts "Connection" before
  // "Authentication" simply because portSetting/bitrateSetting are declared before
  // authSetting. Ungrouped settings (group == null, the default — most of them) render
  // first, flat, exactly as before this existed.
  private fun componentChildRows(component: Toggleable): List<View> {
    val labeled = component.settings.filter { it.deviceUi }.map { it.group to settingRow(it) } +
                  component.selectSettings.filter { it.deviceUi }.map { it.group to selectSettingRow(it) }

    val rows = mutableListOf<View>()
    labeled.filter { it.first == null }.forEach { rows.add(it.second) }
    for ((groupName, groupRows) in labeled.filter { it.first != null }.groupBy({ it.first!! }, { it.second })) {
      rows.add(TextView(this).apply {
        text = groupName
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.DKGRAY)
        setPadding(dp(32), dp(8), 0, dp(2))
      })
      rows.addAll(groupRows)
    }
    return rows
  }

  // One indented floating-label numeric field for a Setting, wired to persist on commit and
  // push the new value to HA.
  private fun settingRow(setting: Setting): View {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(32), dp(8), 0, 0)
    }

    val inputLayout = TextInputLayout(this).apply {
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
      hint = setting.label
    }
    val input = TextInputEditText(inputLayout.context)
    input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    input.imeOptions = EditorInfo.IME_ACTION_DONE
    input.setText(getSetting(this, setting).toString())

    fun commit() {
      val value = input.text.toString().toFloatOrNull() ?: return
      val clamped = setSetting(this, setting, value)
      AESPHomeService.instance?.reportSetting(setting, clamped)
    }

    // The keyboard's Done action is the primary way to finish editing; losing focus
    // (e.g. tapping elsewhere) still commits too, as a safety net.
    input.setOnEditorActionListener { _, actionId, _ ->
      if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
      commit()
      input.clearFocus()
      true
    }
    input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }

    inputLayout.addView(input)
    row.addView(inputLayout)
    settingInputs.add(setting to input)
    return row
  }

  // One indented label + dropdown for a SelectSetting — same pattern as settingRow, just
  // a fixed set of choices instead of free entry.
  private fun selectSettingRow(setting: SelectSetting): View {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(32), dp(4), 0, dp(4))
    }

    val label = TextView(this)
    label.text = setting.label
    row.addView(label)

    val spinner = Spinner(this)
    spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, setting.options)
    spinner.setSelection(setting.options.indexOf(getSelectSetting(this, setting)).coerceAtLeast(0))
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        val value = setting.options[position]
        setSelectSetting(this@MainActivity, setting, value)
        AESPHomeService.instance?.reportSelectSetting(setting, value)
      }
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    row.addView(spinner)
    selectSettingSpinners.add(setting to spinner)
    return row
  }

  // Re-reads every setting from storage whenever this screen becomes visible again, so a
  // value HA changed while the app was merely backgrounded (not recreated) still shows up
  // — onCreate() only runs once and won't otherwise notice a change made outside the app.
  // Skips any field the user is actively typing in.
  override fun onResume() {
    super.onResume()
    refreshHandler.post(refreshRunnable) // keeps reposting itself every CONNECTION_INFO_REFRESH_MS
    for ((setting, input) in settingInputs) {
      if (!input.hasFocus()) input.setText(getSetting(this, setting).toString())
    }
    for ((setting, spinner) in selectSettingSpinners) {
      spinner.setSelection(setting.options.indexOf(getSelectSetting(this, setting)).coerceAtLeast(0))
    }

    // Live-update the volume field the moment HA changes it, instead of only on the
    // next onResume() poll.
    AESPHomeService.instance?.onMediaPlayerVolumeChanged = { volume ->
      settingInputs.firstOrNull { it.first === MediaPlayerService.volumeSetting }?.second?.let { input ->
        if (!input.hasFocus()) input.setText(volume.toString())
      }
    }
  }

  // Stops the repeating refresh while the screen isn't visible — no point polling for
  // something nobody can see, and it'd otherwise run forever in the background.
  override fun onPause() {
    super.onPause()
    refreshHandler.removeCallbacks(refreshRunnable)
    AESPHomeService.instance?.onMediaPlayerVolumeChanged = null
  }

  override fun onDestroy() {
    super.onDestroy()
    if (instance === this) instance = null
  }
}
