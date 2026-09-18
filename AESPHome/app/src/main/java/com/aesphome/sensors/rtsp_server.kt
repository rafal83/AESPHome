package com.aesphome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random


/*

  RTSP / H.264 server
    rtsp://<ip>:8554/aesphome — implements the architecture in docs/RTSP_PLAN.md: Camera2's
    encoder-input Surface feeds MediaCodec (hardware AVC encoder when the device has one),
    RFC 6184 RTP packetization (single-NAL or FU-A fragmented), RTP-over-TCP interleaved only
    (no UDP transport — see docs/RTSP_PLAN.md for why that's the pragmatic first choice), and
    a minimal hand-rolled RTSP control server (OPTIONS/DESCRIBE/SETUP/PLAY/TEARDOWN only — no
    PAUSE/seeking, this is a live proxy, not VOD).

    IMPORTANT — unlike every other feature in this codebase, this one could not be verified
    against a real player (VLC/ffplay/go2rtc) in the environment this was built in: there was
    no camera-equipped Android device available to run it on. The protocol-level pieces (RTP
    header layout, FU-A fragmentation, Annex-B NAL splitting, SDP fmtp line) were written
    carefully against RFC 6184 / RFC 2326, and it compiles and the surrounding app builds/
    tests pass, but "compiles" is not the same claim as "a real player successfully decodes
    the stream." Treat this as needing a real-device smoke test (see docs/RTSP_PLAN.md's
    verification notes) before relying on it.

    Separate camera session from CameraService's JPEG pipeline (unlike MJPEG, which reuses it)
    — Camera2 does support a JPEG ImageReader and an encoder Surface as simultaneous outputs of
    one session, but sharing one was judged a bigger risk to the already-working JPEG/MJPEG
    path than opening the camera a second time here. Android's own camera framework already
    enforces exclusivity (CameraDevice.StateCallback.onError with ERROR_CAMERA_IN_USE) if RTSP
    and Camera/MJPEG try to use the same physical lens at once — handled the same way
    CameraService handles any other open failure (logged, reported cleanly, no crash), not a
    new failure mode this file has to invent.

*/


private const val RTP_PAYLOAD_TYPE = 96
private const val RTP_CLOCK_HZ = 90_000L
private const val MAX_RTP_PAYLOAD = 1400 // comfortably under a typical 1500-byte MTU once RTP/interleave framing is added
private const val NAL_TYPE_SPS = 7
private const val NAL_TYPE_PPS = 8
private const val NAL_TYPE_IDR = 5

// A handful, not MJPEG's 8 — every session opens/holds the one shared H.264 encoder open,
// so there's no real use case for many simultaneous RTSP viewers the way there is for
// MJPEG's lighter-weight per-client JPEG copies.
private const val MAX_CONCURRENT_RTSP_SESSIONS = 4

// Applies only before PLAY — a read timing out while a session IS playing is normal (the
// RTSP control channel goes quiet for the whole stream; all the action is on the
// interleaved data channel) and must not tear down a healthy stream. Before PLAY, an
// abandoned handshake (a client that connected but never finished OPTIONS/DESCRIBE/SETUP)
// would otherwise hold a thread open forever, same as the MJPEG server's read timeout.
private const val RTSP_HANDSHAKE_TIMEOUT_MS = 30_000

// How long the encoder drain loop waits for its very first output buffer before giving up —
// see startDrainLoop()'s doc comment for exactly what this catches (a camera session that
// opened but silently never delivered any frame to the encoder). Comfortably under most RTSP
// clients' own ~10s "no data received" timeout, so this app's own diagnostic log appears
// before the client just reports EOF with no explanation.
private const val ENCODER_STARTUP_TIMEOUT_MS = 6_000L

object RtspServerService : Service {
  override val id                  = "rtsp_server"
  override val label               = "RTSP Server"
  override val description         = "Requires Camera permission — H.264 stream for go2rtc/Frigate (see docs/RTSP_PLAN.md)"
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = false
  override val icon                = "mdi:video-wireless"

  val portSetting = Setting(
      id = "rtsp_port", label = "RTSP Port", default = 8554f, min = 1024f, max = 65535f, step = 1f,
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false, icon = "mdi:lan-connect", group = "Connection")

  val bitrateSetting = Setting(
      id = "rtsp_bitrate_kbps", label = "RTSP Bitrate (kbps)", default = 1500f, min = 250f, max = 8000f, step = 250f,
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false, icon = "mdi:speedometer", group = "Connection")

  // Not exposed to HA on purpose — same reasoning as MJPEG's authSetting (mjpeg_server.kt):
  // letting any HA user remotely disable the one thing gating access to a raw camera feed
  // defeats the point of it being configurable at all. RTSP's own Authorization header only
  // has a well-defined Basic scheme in practice for this kind of embedded server — Digest
  // would need a materially larger implementation (nonce tracking, replay protection) for a
  // LAN camera stream's realistic threat model; Basic is accepted here but documented as
  // such (see docs/SECURITY.md) rather than presented as equivalent to a proper Digest flow.
  val authSetting = SelectSetting(
      id = "rtsp_require_auth", label = "RTSP Require Auth", options = listOf("Disabled", "Enabled"),
      default = "Disabled", deviceUi = true, homeAssistant = false, entityCategory = EntityCategory.CONFIG,
      icon = "mdi:key-outline", group = "Authentication")

  // No resolution setting of its own — streams whatever CameraService's currently-selected
  // lens/resolution already is (CameraService.selectedResolution()), same "one camera, one
  // resolution" answer the JPEG/MJPEG path gives, rather than offering an independent choice
  // for what's conceptually the same camera.
  override val settings: List<Setting> = listOf(portSetting, bitrateSetting)
  override val selectSettings: List<SelectSetting> = listOf(authSetting)

  fun authRequired(context: Context): Boolean = getSelectSetting(context, authSetting) == "Enabled"

  fun credentials(context: Context): Pair<String, String> {
    val username = getStringFlag(context, "rtsp_username", "aesphome")
    var password = getStringFlag(context, "rtsp_password", "")
    if (password.isEmpty()) {
      password = UUID.randomUUID().toString().replace("-", "").take(16)
      setStringFlag(context, "rtsp_password", password)
    }
    return username to password
  }

  @Volatile private var running = false
  private var serverSocket: ServerSocket? = null
  private val sessions = CopyOnWriteArrayList<RtspSession>()
  private var appContext: Context? = null

  // Encoder/camera state — started when the first session PLAYs, torn down once no session
  // is still playing. Never held open just because the RTSP server itself is enabled.
  private var cameraThread: HandlerThread? = null
  private var mediaCodec: MediaCodec? = null
  private var cameraDevice: CameraDevice? = null
  private var captureSession: CameraCaptureSession? = null
  private var drainThread: Thread? = null
  @Volatile private var streaming = false
  @Volatile private var spsNal: ByteArray? = null
  @Volatile private var ppsNal: ByteArray? = null

  // No credentials, always trailing-slash — used as DESCRIBE's Content-Base (see respondBody
  // call site), which exists purely to tell the client what base a relative SDP control URL
  // ("a=control:trackID=0") resolves against; embedding credentials in it would be wrong
  // (it's not a fetchable resource) and every RTSP client resolves relative-URL bases the
  // same way regardless of auth.
  internal fun baseUrl(context: Context): String? {
    val ip = getWifiIpAddress() ?: return null
    val port = getSetting(context, portSetting).toInt()
    return "rtsp://$ip:$port/aesphome/"
  }

  fun url(context: Context): String? {
    val ip = getWifiIpAddress() ?: return null
    val port = getSetting(context, portSetting).toInt()
    // Credentials embedded directly in the URL (standard RTSP convention ffmpeg/VLC/go2rtc
    // all understand) when auth is enabled — avoids needing a separate in-app field just to
    // show them; this is the one place an operator needs them, to paste into a player/NVR.
    val auth = if (getSelectSetting(context, authSetting) == "Enabled") {
      val (username, password) = credentials(context)
      "$username:$password@"
    } else ""
    return "rtsp://$auth$ip:$port/aesphome"
  }

  override fun start(context: Context) {
    appContext = context
    running = true
    Thread({ acceptLoop(context) }, "AESPHomeRtspServer").start()
  }

  override fun stop(context: Context) {
    running = false
    try { serverSocket?.close() } catch (e: IOException) {}
    serverSocket = null
    for (session in sessions) session.close()
    sessions.clear()
    stopEncoder()
    RtspServerRunningSensor.updateState(false)
  }

  private fun acceptLoop(context: Context) {
    val wifiIp = getWifiIpAddress()
    if (wifiIp == null) { Log.e("$TAG/RTSP", "no Wi-Fi IP yet, not starting"); return }
    val port = getSetting(context, portSetting).toInt()
    try {
      val server = ServerSocket()
      server.reuseAddress = true
      server.bind(InetSocketAddress(InetAddress.getByName(wifiIp), port))
      serverSocket = server
      RtspServerRunningSensor.updateState(true)
      Log.i("$TAG/RTSP", "listening on $wifiIp:$port")
      while (running) {
        val socket = try { server.accept() } catch (e: IOException) { break }
        if (sessions.size >= MAX_CONCURRENT_RTSP_SESSIONS) {
          Log.w("$TAG/RTSP", "at MAX_CONCURRENT_RTSP_SESSIONS ($MAX_CONCURRENT_RTSP_SESSIONS) — rejecting new connection")
          try { socket.close() } catch (e: IOException) {}
          continue
        }
        val session = RtspSession(this, context, socket)
        sessions.add(session)
        session.start()
      }
    } catch (e: IOException) {
      Log.e("$TAG/RTSP", "failed to bind: ${e.message}")
    } finally {
      RtspServerRunningSensor.updateState(false)
    }
  }

  // ==================== Called by RtspSession ====================

  internal fun sessionEnded(session: RtspSession) {
    sessions.remove(session)
    if (sessions.none { it.playing }) stopEncoder()
  }

  internal fun onSessionPlay(context: Context) {
    if (!streaming) startEncoder(context)
  }

  // DESCRIBE is always the client's FIRST request (before SETUP/PLAY) — the encoder has to
  // already be running and have produced SPS/PPS by the time this returns, or every session
  // fails at the very first step with no SDP to offer. Starts the encoder here too (not just
  // on PLAY) and polls briefly for SPS/PPS to appear, since opening the camera and getting the
  // first codec-config buffer out of MediaCodec isn't instant.
  internal fun sdpBody(context: Context): String? {
    if (!streaming) startEncoder(context)
    val deadline = System.currentTimeMillis() + 4000L
    while ((spsNal == null || ppsNal == null) && System.currentTimeMillis() < deadline) {
      try { Thread.sleep(100) } catch (e: InterruptedException) { return null }
    }
    val sps = spsNal ?: return null
    val pps = ppsNal ?: return null
    val profileLevelId = "%02x%02x%02x".format(sps.getOrElse(1) { 0 }, sps.getOrElse(2) { 0 }, sps.getOrElse(3) { 0 })
    val spsB64 = Base64.encodeToString(sps, Base64.NO_WRAP)
    val ppsB64 = Base64.encodeToString(pps, Base64.NO_WRAP)
    return listOf(
      "v=0",
      "o=- 0 0 IN IP4 0.0.0.0",
      "s=AESPHome",
      "c=IN IP4 0.0.0.0",
      "t=0 0",
      "m=video 0 RTP/AVP $RTP_PAYLOAD_TYPE",
      "a=rtpmap:$RTP_PAYLOAD_TYPE H264/90000",
      "a=fmtp:$RTP_PAYLOAD_TYPE packetization-mode=1;profile-level-id=$profileLevelId;sprop-parameter-sets=$spsB64,$ppsB64",
      "a=control:trackID=0"
    ).joinToString("\r\n") + "\r\n"
  }

  // ==================== Camera2 -> MediaCodec ====================

  private fun startEncoder(context: Context) {
    if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      Log.e("$TAG/RTSP", "CAMERA permission not granted"); return
    }
    val nativeSize = CameraService.selectedResolution(context)
    if (nativeSize == null) {
      Log.e("$TAG/RTSP", "could not determine the camera's native resolution — is Camera enabled and its lens list refreshed?")
      return
    }
    val (width, height) = nativeSize.width to nativeSize.height
    val bitrate = (getSetting(context, bitrateSetting) * 1000).toInt()

    val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
      setInteger(MediaFormat.KEY_FRAME_RATE, 15)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    }
    val codec = try { MediaCodec.createEncoderByType("video/avc") } catch (e: Exception) { Log.e("$TAG/RTSP", "encoder create failed", e); return }
    try {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    } catch (e: Exception) { Log.e("$TAG/RTSP", "encoder configure failed", e); codec.release(); return }
    val inputSurface = codec.createInputSurface()
    codec.start()
    mediaCodec = codec
    streaming = true
    startDrainLoop(codec)

    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ids = try { manager.cameraIdList } catch (e: Exception) { Log.e("$TAG/RTSP", "cameraIdList failed", e); stopEncoder(); return }
    if (ids.isEmpty()) { Log.e("$TAG/RTSP", "no cameras found"); stopEncoder(); return }
    // Same lens selection CameraService's JPEG pipeline uses, for a consistent "which camera
    // is this device's camera" answer between both — but a fully independent open, not a
    // shared session (see file header comment).
    val selectedIndex = CameraService.lensSetting.options.indexOf(getSelectSetting(context, CameraService.lensSetting)).coerceAtLeast(0)
    val cameraId = ids.getOrElse(selectedIndex) { ids[0] }

    val thread = HandlerThread("AESPHomeRtspCamera").apply { start() }
    cameraThread = thread
    val handler = Handler(thread.looper)
    try {
      manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
          cameraDevice = device
          try {
            device.createCaptureSession(listOf(inputSurface), object : CameraCaptureSession.StateCallback() {
              override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                try {
                  val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(inputSurface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                  }.build()
                  // A real callback, not null: a null callback here means a per-capture
                  // failure (e.g. from contention with another active session on the same
                  // physical camera — Camera/MJPEG/person-detection's own capture pipeline —
                  // on hardware whose camera HAL doesn't actually support two independent
                  // concurrent sessions on one sensor) is completely invisible — no log, no
                  // exception, nothing; the encoder's dequeueOutputBuffer just spins forever
                  // getting no output, with no error anywhere to explain why. Rate-limited so
                  // a sustained failure (every repeating-request capture failing) doesn't
                  // flood logcat once per frame interval.
                  var loggedFailures = 0
                  session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                      if (loggedFailures++ < 3) {
                        Log.e("$TAG/RTSP", "capture failed (reason=${failure.reason}, wasImageCaptured=${failure.wasImageCaptured()}) — likely contention with Camera/MJPEG/person detection on the same physical camera")
                      }
                    }
                  }, handler)
                } catch (e: Exception) { Log.e("$TAG/RTSP", "setRepeatingRequest failed", e); stopEncoder() }
              }
              override fun onConfigureFailed(session: CameraCaptureSession) { Log.e("$TAG/RTSP", "createCaptureSession failed"); stopEncoder() }
            }, handler)
          } catch (e: Exception) { Log.e("$TAG/RTSP", "createCaptureSession threw", e); stopEncoder() }
        }
        override fun onDisconnected(device: CameraDevice) { device.close(); stopEncoder() }
        override fun onError(device: CameraDevice, error: Int) {
          // ERROR_CAMERA_IN_USE here almost always means Camera/MJPEG has the lens open
          // already — see the "separate camera session" note in the file header comment.
          // Logged explicitly as the documented fallback (not a crash, not a silent no-op)
          // rather than a full shared-CameraPipeline abstraction: this app's Camera2 usage
          // was deliberately kept as two independent single-output sessions (JPEG ImageReader
          // for Camera/MJPEG, encoder Surface for RTSP) specifically because it was judged a
          // smaller risk than merging them into one multi-output session shared across two
          // otherwise-independent features — see docs/IMPLEMENTATION_REPORT.md's hardening-
          // pass notes for the full reasoning.
          if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE) {
            Log.e("$TAG/RTSP", "camera multi-output unsupported here, fallback active — lens already in use by Camera/MJPEG")
          } else {
            Log.e("$TAG/RTSP", "camera error $error")
          }
          device.close()
          stopEncoder()
        }
      }, handler)
    } catch (e: Exception) { Log.e("$TAG/RTSP", "openCamera failed", e); stopEncoder() }
  }

  // Synchronized: called from several different callback threads on failure paths (the
  // camera thread's onError/onDisconnected/onConfigureFailed, startEncoder() itself, and
  // sessionEnded() from any RtspSession's own handler thread) — without this, two of those
  // racing could both see a non-null cameraDevice/captureSession and both attempt to close
  // it, or one could null out a field the other is mid-read on. Every individual close() is
  // already wrapped in try/catch (double-close is harmless on Android's Camera2 objects
  // regardless), so this is about avoiding the race itself, not just tolerating its outcome.
  @Synchronized
  private fun stopEncoder() {
    streaming = false
    drainThread?.interrupt(); drainThread = null
    try { captureSession?.close() } catch (e: Exception) {}
    try { cameraDevice?.close() } catch (e: Exception) {}
    try { mediaCodec?.stop() } catch (e: Exception) {}
    try { mediaCodec?.release() } catch (e: Exception) {}
    captureSession = null
    cameraDevice = null
    mediaCodec = null
    cameraThread?.quitSafely(); cameraThread = null
    spsNal = null
    ppsNal = null
  }

  private fun startDrainLoop(codec: MediaCodec) {
    val startedAtMs = System.currentTimeMillis()
    drainThread = Thread({
      val bufferInfo = MediaCodec.BufferInfo()
      var everProducedOutput = false
      while (streaming) {
        val outIndex = try { codec.dequeueOutputBuffer(bufferInfo, 100_000) } catch (e: Exception) {
          Log.e("$TAG/RTSP", "encoder drain loop failed (${e.javaClass.simpleName}), tearing down", e)
          break
        }
        if (outIndex < 0) {
          // dequeueOutputBuffer returning "nothing yet" (not an error — MediaCodec's normal
          // "try again" return) forever, with zero output ever produced, is exactly what a
          // camera session that opened but never actually delivered a frame to the encoder's
          // input Surface looks like from here — no exception anywhere to catch, since
          // nothing failed loudly; the frames just never arrived. Bounded so this shows up
          // as a clear, explained log line well before a client's own ~10s "no data" timeout,
          // instead of the encoder spinning here silently for the life of the session.
          if (!everProducedOutput && System.currentTimeMillis() - startedAtMs > ENCODER_STARTUP_TIMEOUT_MS) {
            Log.e("$TAG/RTSP", "no encoder output ${ENCODER_STARTUP_TIMEOUT_MS / 1000}s after starting — " +
                "the camera almost certainly never delivered a frame to the encoder. Common cause: this " +
                "device's camera can't run two independent concurrent sessions on the same physical lens " +
                "— disable Camera/MJPEG/Person Detection while using RTSP, or vice versa.")
            break
          }
          continue
        }
        everProducedOutput = true
        val buffer = codec.getOutputBuffer(outIndex)
        if (buffer == null) { try { codec.releaseOutputBuffer(outIndex, false) } catch (e: Exception) {}; continue }
        val data = ByteArray(bufferInfo.size)
        buffer.get(data)
        try {
          if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            for (nal in splitAnnexB(data)) {
              when (nal.getOrNull(0)?.toInt()?.and(0x1F)) {
                NAL_TYPE_SPS -> spsNal = nal
                NAL_TYPE_PPS -> ppsNal = nal
              }
            }
          } else if (data.isNotEmpty()) {
            val nals = splitAnnexB(data)
            val hasIdr = nals.any { (it.getOrNull(0)?.toInt() ?: 0) and 0x1F == NAL_TYPE_IDR }
            // Re-sends cached SPS/PPS in-band before every keyframe, on top of the SDP's own
            // sprop-parameter-sets — belt-and-suspenders so a client joining mid-stream can
            // decode from the next IDR without a fresh DESCRIBE round trip.
            val toSend = if (hasIdr) listOfNotNull(spsNal, ppsNal) + nals else nals
            for (session in sessions) if (session.playing) session.sendAccessUnit(toSend, bufferInfo.presentationTimeUs)
          }
        } finally {
          try { codec.releaseOutputBuffer(outIndex, false) } catch (e: Exception) {}
        }
      }
      // Whatever ended this loop — a genuine stop() (streaming already false, so this is a
      // harmless no-op), a dequeue exception, or the startup watchdog above — make sure
      // `streaming` and every resource it gates come down together. Without this, a loop
      // that exited on its own (not via an explicit stopEncoder() call from someone else)
      // left `streaming` stuck true forever, and onSessionPlay() only ever calls
      // startEncoder() when `!streaming` — so every subsequent RTSP session would silently
      // get a PLAY 200 OK and then nothing, forever, with no way to notice short of
      // restarting the app.
      stopEncoder()
    }, "AESPHomeRtspEncoderDrain").apply { start() }
  }
}

// RFC 6184 §5.8 FU-A: the NAL header's F/NRI bits are preserved on the FU indicator byte
// (with type replaced by 28 = FU-A); the FU header carries S(tart)/E(nd) plus the original
// NAL type. Pulled out as a pure function (from RtspSession.sendNal, which does the actual
// chunking/socket write) so this bit-packing — the trickiest part of the RTP layer — is
// directly unit-testable; see RtspAnnexBTest.kt.
internal fun fuAHeaderBytes(nalHeader: Byte, isFirst: Boolean, isLast: Boolean): Pair<Byte, Byte> {
  val header = nalHeader.toInt()
  val fnri = header and 0xE0
  val nalType = header and 0x1F
  val indicator = (fnri or 28).toByte()
  val fuHeader = (((if (isFirst) 1 else 0) shl 7) or ((if (isLast) 1 else 0) shl 6) or nalType).toByte()
  return indicator to fuHeader
}

// Splits an Annex-B buffer (each NAL prefixed by a 3- or 4-byte 00 00 [00] 01 start code —
// exactly what MediaCodec's AVC encoder output already is) into individual NAL units
// (start-code stripped). Each NAL's end is wherever the next start code begins (its leading
// zero bytes), or the end of the buffer for the last one.
internal fun splitAnnexB(data: ByteArray): List<ByteArray> {
  val starts = mutableListOf<Pair<Int, Int>>() // (start-code begin, NAL data begin)
  var i = 0
  while (i <= data.size - 3) {
    if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
      if (data[i + 2] == 1.toByte()) { starts.add(i to i + 3); i += 3; continue }
      if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) { starts.add(i to i + 4); i += 4; continue }
    }
    i++
  }
  val nals = mutableListOf<ByteArray>()
  for (idx in starts.indices) {
    val dataStart = starts[idx].second
    val dataEnd = if (idx + 1 < starts.size) starts[idx + 1].first else data.size
    if (dataEnd > dataStart) nals.add(data.copyOfRange(dataStart, dataEnd))
  }
  return nals
}

// One access unit (every NAL belonging to one encoded frame) queued for a session's writer
// thread — see the class doc comment below for why this exists instead of writing directly
// from the shared encoder drain thread.
private data class AccessUnit(val nals: List<ByteArray>, val presentationTimeUs: Long)

// ==================== One RTSP client connection ====================

// IMPORTANT: sendAccessUnit() below is called from RtspServerService's single shared encoder
// drain thread — the same thread for every connected session. Java's Socket has a read
// timeout (setSoTimeout) but no write-timeout equivalent; if a client stops reading (a Wi-Fi
// hiccup, a slow/stuck player) the TCP send buffer fills and a plain blocking
// socket.getOutputStream().write() call can hang indefinitely. If that write happened
// directly on the shared drain thread, one stuck client would silently freeze frame delivery
// to the camera pipeline and every other session forever — exactly the kind of
// stops-after-a-network-hiccup symptom this was written to rule out. So each session owns a
// small bounded queue (latest-frame-wins, like the JPEG queues elsewhere in this codebase)
// and its own dedicated writer thread; sendAccessUnit() only ever enqueues (non-blocking) and
// returns immediately — only that session's own writer thread can ever be the one stuck in a
// blocking write, never the encoder or another client.
internal class RtspSession(private val server: RtspServerService, private val context: Context, private val socket: Socket) {
  @Volatile var playing = false
  private val sessionId = UUID.randomUUID().toString().replace("-", "").take(16)
  private var seq = Random.nextInt(0, 0xFFFF)
  private val ssrc = Random.nextInt()

  private val outgoing = ArrayBlockingQueue<AccessUnit>(2)
  @Volatile private var writerRunning = false
  private var writerThread: Thread? = null

  // Guards every write to the socket's OutputStream — RTSP control responses (handle()'s
  // thread) and RTP data (the writer thread) would otherwise both write to the same socket
  // from two different threads with no ordering guarantee between them. A real bug this
  // fixes: PLAY's own response could race with the first queued RTP frame and lose, so the
  // client received binary interleaved-RTP bytes before ever seeing "RTSP/1.0 200 OK" for
  // PLAY — enough to permanently desync a client's interleaved-frame parser right at the
  // point PLAY completes, matching a real-device report of RTSP failing the same way, every
  // time, a fixed few seconds after PLAY.
  private val socketWriteLock = Any()

  fun start() {
    writerRunning = true
    writerThread = Thread({ writerLoop() }, "AESPHomeRtspWriter").apply { start() }
    Thread({ handle() }, "AESPHomeRtspClient").start()
  }

  fun close() {
    writerRunning = false
    writerThread?.interrupt()
    try { socket.close() } catch (e: IOException) {}
  }

  // The only thread that ever calls writeRawRtp() (and therefore the only one that can ever
  // block in a socket write) — sits idle on the queue until PLAY starts producing frames.
  // Closing the socket (close(), above) is what unblocks a write that's genuinely stuck: it
  // makes the in-flight write throw, caught below same as any other IOException.
  private fun writerLoop() {
    while (writerRunning) {
      val unit = try { outgoing.poll(1, TimeUnit.SECONDS) } catch (e: InterruptedException) { break } ?: continue
      val rtpTimestamp = ((unit.presentationTimeUs * RTP_CLOCK_HZ) / 1_000_000L).toInt()
      try {
        for ((i, nal) in unit.nals.withIndex()) {
          sendNal(nal, rtpTimestamp, markLast = i == unit.nals.lastIndex)
        }
      } catch (e: IOException) {
        playing = false // the read side (or the next enqueue) will notice and clean up
        break
      }
    }
  }

  private fun handle() {
    try {
      // Only bounds the pre-PLAY handshake — a read timing out while `playing` is true is
      // normal (the control channel goes quiet for the whole stream) and must not be
      // mistaken for an abandoned connection; caught and ignored below in that case.
      socket.soTimeout = RTSP_HANDSHAKE_TIMEOUT_MS
      val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charset.forName("ISO-8859-1")))
      while (true) {
        val requestLine = try { reader.readLine() } catch (e: java.net.SocketTimeoutException) {
          if (playing) continue else throw e // idle-but-healthy stream vs. a truly abandoned handshake
        } ?: break
        if (requestLine.isBlank()) continue
        val headers = readHeaders(reader)
        val cseq = headers["cseq"] ?: "0"

        if (RtspServerService.authRequired(context) && !isAuthorized(headers)) {
          respondUnauthorized(cseq)
          continue
        }

        when (requestLine.substringBefore(" ")) {
          "OPTIONS" -> respond(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n")
          "DESCRIBE" -> {
            val sdp = server.sdpBody(context)
            if (sdp == null) {
              respondError(cseq, 503, "Service Unavailable")
            } else {
              respondWithBody(cseq, sdp, server.baseUrl(context))
            }
          }
          "SETUP" -> respondSetup(cseq)
          "PLAY" -> {
            // Order matters: the response must reach the client before playing=true can let
            // the writer thread send any RTP data — see socketWriteLock's doc comment above.
            server.onSessionPlay(context)
            respond(cseq, "Range: npt=0.000-\r\nSession: $sessionId\r\nRTP-Info: url=trackID=0;seq=$seq\r\n")
            playing = true
          }
          "TEARDOWN" -> { respond(cseq, "Session: $sessionId\r\n"); break }
          else -> respondError(cseq, 501, "Not Implemented")
        }
      }
    } catch (e: IOException) {
      // Expected for a client that disconnects mid-session, or a genuinely abandoned
      // pre-PLAY handshake finally timing out (RTSP_HANDSHAKE_TIMEOUT_MS).
    } finally {
      playing = false
      server.sessionEnded(this)
      close()
    }
  }

  // RTSP Basic auth (RFC 2617, reused as-is — no RTSP-specific auth scheme is standardized;
  // this is the same scheme plenty of embedded IP cameras' RTSP servers use). Documented as
  // Basic, not Digest — see authSetting's doc comment (rtsp_server.kt) and docs/SECURITY.md
  // for why a full Digest implementation wasn't judged worth it for this threat model.
  private fun isAuthorized(headers: Map<String, String>): Boolean {
    val header = headers["authorization"] ?: return false
    val encoded = header.removePrefix("Basic ").trim()
    val decoded = try { String(Base64.decode(encoded, Base64.DEFAULT)) } catch (e: Exception) { return false }
    val (expectedUser, expectedPass) = RtspServerService.credentials(context)
    return java.security.MessageDigest.isEqual(decoded.toByteArray(), "$expectedUser:$expectedPass".toByteArray())
  }

  private fun respondUnauthorized(cseq: String) {
    val bytes = "RTSP/1.0 401 Unauthorized\r\nCSeq: $cseq\r\nWWW-Authenticate: Basic realm=\"aesphome\"\r\n\r\n".toByteArray()
    synchronized(socketWriteLock) { socket.getOutputStream().write(bytes) }
  }

  private fun readHeaders(reader: BufferedReader): Map<String, String> {
    val headers = HashMap<String, String>()
    while (true) {
      val line = reader.readLine() ?: break
      if (line.isEmpty()) break
      val idx = line.indexOf(':')
      if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
    }
    return headers
  }

  private fun respond(cseq: String, extraHeaders: String) {
    val bytes = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$extraHeaders\r\n".toByteArray()
    synchronized(socketWriteLock) { socket.getOutputStream().write(bytes) }
  }

  // Content-Base tells the client what to resolve the SDP's relative control URL
  // ("a=control:trackID=0") against — several real RTSP clients (VLC/live555 among them)
  // don't reliably fall back to the request URL itself when this is missing, and instead
  // resolve the relative track URL incorrectly (or refuse it outright), which manifests as
  // SETUP/PLAY going to the wrong URL or the client giving up right after DESCRIBE.
  private fun respondWithBody(cseq: String, body: String, contentBase: String?) {
    val bodyBytes = body.toByteArray()
    val baseHeader = if (contentBase != null) "Content-Base: $contentBase\r\n" else ""
    val text = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n${baseHeader}Content-Type: application/sdp\r\nContent-Length: ${bodyBytes.size}\r\n\r\n$body"
    synchronized(socketWriteLock) { socket.getOutputStream().write(text.toByteArray()) }
  }

  private fun respondError(cseq: String, code: Int, text: String) {
    val bytes = "RTSP/1.0 $code $text\r\nCSeq: $cseq\r\n\r\n".toByteArray()
    synchronized(socketWriteLock) { socket.getOutputStream().write(bytes) }
  }

  // Always confirms RTP/AVP/TCP interleaved (channel 0 for RTP, 1 for RTCP — RTCP channel is
  // declared but nothing is ever sent on it; every RTSP client this was designed against
  // tolerates an interleaved RTCP channel that simply stays silent) regardless of what
  // transport the client's SETUP actually proposed. This is the same pattern plenty of
  // TCP-only IP cameras use: the server is authoritative on what transport it actually
  // supports, and a well-behaved client (ffmpeg/VLC/go2rtc) adapts to what SETUP's response
  // confirms rather than insisting on what it originally asked for.
  private fun respondSetup(cseq: String) {
    val text = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\nSession: $sessionId\r\n\r\n"
    // This write was missing socketWriteLock (every other response method uses it) — the
    // exact same class of race socketWriteLock exists to prevent: a re-SETUP mid-stream
    // (some clients do this) could otherwise interleave with the writer thread's RTP writes
    // on the same socket with no ordering guarantee between them.
    synchronized(socketWriteLock) { socket.getOutputStream().write(text.toByteArray()) }
  }

  // Called from RtspServerService's shared encoder drain thread — must never block (see the
  // class doc comment above). "Latest access unit wins" if the writer thread is falling
  // behind: drop the queued-but-not-yet-sent one rather than let the queue (and therefore
  // latency) grow unbounded.
  fun sendAccessUnit(nals: List<ByteArray>, presentationTimeUs: Long) {
    val unit = AccessUnit(nals, presentationTimeUs)
    if (!outgoing.offer(unit)) {
      outgoing.poll()
      outgoing.offer(unit)
    }
  }

  private fun sendNal(nal: ByteArray, ts: Int, markLast: Boolean) {
    if (nal.isEmpty()) return
    if (nal.size <= MAX_RTP_PAYLOAD) {
      writeRawRtp(nal, ts, markLast)
      return
    }
    // RFC 6184 FU-A fragmentation.
    val nalHeader = nal[0]
    var offset = 1 // skip the original NAL header byte — it's replaced by the 2-byte FU indicator+header
    var first = true
    while (offset < nal.size) {
      val remaining = nal.size - offset
      val chunk = minOf(MAX_RTP_PAYLOAD - 2, remaining)
      val isLast = offset + chunk >= nal.size
      val (indicator, fuHeader) = fuAHeaderBytes(nalHeader, isFirst = first, isLast = isLast)
      val payload = ByteArray(2 + chunk)
      payload[0] = indicator
      payload[1] = fuHeader
      System.arraycopy(nal, offset, payload, 2, chunk)
      writeRawRtp(payload, ts, isLast && markLast)
      offset += chunk
      first = false
    }
  }

  private fun writeRawRtp(payload: ByteArray, ts: Int, marker: Boolean) {
    seq = (seq + 1) and 0xFFFF
    val header = ByteArray(12)
    header[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
    header[1] = ((if (marker) 0x80 else 0) or RTP_PAYLOAD_TYPE).toByte()
    header[2] = (seq shr 8).toByte(); header[3] = seq.toByte()
    header[4] = (ts shr 24).toByte(); header[5] = (ts shr 16).toByte(); header[6] = (ts shr 8).toByte(); header[7] = ts.toByte()
    header[8] = (ssrc shr 24).toByte(); header[9] = (ssrc shr 16).toByte(); header[10] = (ssrc shr 8).toByte(); header[11] = ssrc.toByte()

    val rtpLen = header.size + payload.size
    // RTSP interleaved framing (RFC 2326 §10.12): '$', channel (0 = RTP), 16-bit length, then
    // the raw RTP packet — sent on the same TCP socket the RTSP control messages use.
    val frame = ByteArray(4 + rtpLen)
    frame[0] = '$'.code.toByte()
    frame[1] = 0
    frame[2] = (rtpLen shr 8).toByte()
    frame[3] = rtpLen.toByte()
    System.arraycopy(header, 0, frame, 4, header.size)
    System.arraycopy(payload, 0, frame, 4 + header.size, payload.size)
    synchronized(socketWriteLock) { socket.getOutputStream().write(frame) }
  }
}

// binary_sensor.rtsp_server_running — same externally-driven pattern as MjpegServerRunningSensor.
object RtspServerRunningSensor : EventSensor {
  override val id                     = "rtsp_server_running"
  // Was "RTSP Server" — identical to RtspServerService.label, same fix as
  // MjpegServerRunningSensor (mjpeg_server.kt) for the same reason.
  override val label                  = "RTSP Server Running"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override fun kind(context: Context) = SensorKind.Binary()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val icon                   = "mdi:video-wireless"

  override fun isAvailable(context: Context): Boolean = isEnabled(context, RtspServerService)

  fun updateState(running: Boolean) {
    AESPHomeService.instance?.reportSensor(this, running)
  }

  override fun start(context: Context) {}
  override fun stop(context: Context) {}
}
