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
import java.util.concurrent.CopyOnWriteArrayList
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
      enabledByDefaultHa = false, icon = "mdi:lan-connect")

  val bitrateSetting = Setting(
      id = "rtsp_bitrate_kbps", label = "RTSP Bitrate (kbps)", default = 1500f, min = 250f, max = 8000f, step = 250f,
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false, icon = "mdi:speedometer")

  val resolutionSetting = SelectSetting(
      id = "rtsp_resolution", label = "RTSP Resolution",
      options = listOf("640x480", "1280x720"), default = "640x480",
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      icon = "mdi:image-size-select-large")

  override val settings: List<Setting> = listOf(portSetting, bitrateSetting)
  override val selectSettings: List<SelectSetting> = listOf(resolutionSetting)

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

  fun url(context: Context): String? {
    val ip = getWifiIpAddress() ?: return null
    return "rtsp://$ip:${getSetting(context, portSetting).toInt()}/aesphome"
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
    if (wifiIp == null) { Log.e(TAG, "RTSP server: no Wi-Fi IP yet, not starting"); return }
    val port = getSetting(context, portSetting).toInt()
    try {
      val server = ServerSocket()
      server.reuseAddress = true
      server.bind(InetSocketAddress(InetAddress.getByName(wifiIp), port))
      serverSocket = server
      RtspServerRunningSensor.updateState(true)
      Log.i(TAG, "RTSP server listening on $wifiIp:$port")
      while (running) {
        val socket = try { server.accept() } catch (e: IOException) { break }
        val session = RtspSession(this, context, socket)
        sessions.add(session)
        session.start()
      }
    } catch (e: IOException) {
      Log.e(TAG, "RTSP server failed to bind: ${e.message}")
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

  internal fun sdpBody(): String? {
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
      Log.e(TAG, "RTSP: CAMERA permission not granted"); return
    }
    val (width, height) = parseResolution(getSelectSetting(context, resolutionSetting))
    val bitrate = (getSetting(context, bitrateSetting) * 1000).toInt()

    val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
      setInteger(MediaFormat.KEY_FRAME_RATE, 15)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    }
    val codec = try { MediaCodec.createEncoderByType("video/avc") } catch (e: Exception) { Log.e(TAG, "RTSP: encoder create failed", e); return }
    try {
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    } catch (e: Exception) { Log.e(TAG, "RTSP: encoder configure failed", e); codec.release(); return }
    val inputSurface = codec.createInputSurface()
    codec.start()
    mediaCodec = codec
    streaming = true
    startDrainLoop(codec)

    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ids = try { manager.cameraIdList } catch (e: Exception) { Log.e(TAG, "RTSP: cameraIdList failed", e); stopEncoder(); return }
    if (ids.isEmpty()) { Log.e(TAG, "RTSP: no cameras found"); stopEncoder(); return }
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
                  session.setRepeatingRequest(request, null, handler)
                } catch (e: Exception) { Log.e(TAG, "RTSP: setRepeatingRequest failed", e); stopEncoder() }
              }
              override fun onConfigureFailed(session: CameraCaptureSession) { Log.e(TAG, "RTSP: createCaptureSession failed"); stopEncoder() }
            }, handler)
          } catch (e: Exception) { Log.e(TAG, "RTSP: createCaptureSession threw", e); stopEncoder() }
        }
        override fun onDisconnected(device: CameraDevice) { device.close(); stopEncoder() }
        override fun onError(device: CameraDevice, error: Int) {
          // error 4 (ERROR_CAMERA_IN_USE) here almost always means Camera/MJPEG has the lens
          // open already — see the "separate camera session" note in the file header comment.
          Log.e(TAG, "RTSP: camera error $error — is Camera/MJPEG using the same lens?")
          device.close()
          stopEncoder()
        }
      }, handler)
    } catch (e: Exception) { Log.e(TAG, "RTSP: openCamera failed", e); stopEncoder() }
  }

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
    drainThread = Thread({
      val bufferInfo = MediaCodec.BufferInfo()
      while (streaming) {
        val outIndex = try { codec.dequeueOutputBuffer(bufferInfo, 100_000) } catch (e: Exception) { break }
        if (outIndex < 0) continue
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

private fun parseResolution(value: String): Pair<Int, Int> {
  val (w, h) = value.split("x").takeIf { it.size == 2 } ?: return 640 to 480
  return (w.toIntOrNull() ?: 640) to (h.toIntOrNull() ?: 480)
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

// ==================== One RTSP client connection ====================

internal class RtspSession(private val server: RtspServerService, private val context: Context, private val socket: Socket) {
  @Volatile var playing = false
  private val sessionId = UUID.randomUUID().toString().replace("-", "").take(16)
  private var seq = Random.nextInt(0, 0xFFFF)
  private val ssrc = Random.nextInt()
  private val writeLock = Any()

  fun start() {
    Thread({ handle() }, "AESPHomeRtspClient").start()
  }

  fun close() {
    try { socket.close() } catch (e: IOException) {}
  }

  private fun handle() {
    try {
      val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charset.forName("ISO-8859-1")))
      while (true) {
        val requestLine = reader.readLine() ?: break
        if (requestLine.isBlank()) continue
        val headers = readHeaders(reader)
        val cseq = headers["cseq"] ?: "0"
        when (requestLine.substringBefore(" ")) {
          "OPTIONS" -> respond(cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n")
          "DESCRIBE" -> {
            val sdp = server.sdpBody()
            if (sdp == null) respondError(cseq, 503, "Service Unavailable") else respondWithBody(cseq, sdp)
          }
          "SETUP" -> respondSetup(cseq)
          "PLAY" -> {
            playing = true
            server.onSessionPlay(context)
            respond(cseq, "Range: npt=0.000-\r\nSession: $sessionId\r\nRTP-Info: url=trackID=0;seq=$seq\r\n")
          }
          "TEARDOWN" -> { respond(cseq, "Session: $sessionId\r\n"); break }
          else -> respondError(cseq, 501, "Not Implemented")
        }
      }
    } catch (e: IOException) {
      // Expected for a client that disconnects mid-session.
    } finally {
      playing = false
      server.sessionEnded(this)
      close()
    }
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
    socket.getOutputStream().write("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$extraHeaders\r\n".toByteArray())
  }

  private fun respondWithBody(cseq: String, body: String) {
    val bytes = body.toByteArray()
    val text = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Type: application/sdp\r\nContent-Length: ${bytes.size}\r\n\r\n$body"
    socket.getOutputStream().write(text.toByteArray())
  }

  private fun respondError(cseq: String, code: Int, text: String) {
    socket.getOutputStream().write("RTSP/1.0 $code $text\r\nCSeq: $cseq\r\n\r\n".toByteArray())
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
    socket.getOutputStream().write(text.toByteArray())
  }

  fun sendAccessUnit(nals: List<ByteArray>, presentationTimeUs: Long) {
    val rtpTimestamp = ((presentationTimeUs * RTP_CLOCK_HZ) / 1_000_000L).toInt()
    synchronized(writeLock) {
      try {
        for ((i, nal) in nals.withIndex()) {
          sendNal(nal, rtpTimestamp, markLast = i == nals.lastIndex)
        }
      } catch (e: IOException) {
        playing = false // next write attempt (or the read side) will notice and clean up
      }
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
    socket.getOutputStream().write(frame)
  }
}

// binary_sensor.rtsp_server_running — same externally-driven pattern as MjpegServerRunningSensor.
object RtspServerRunningSensor : EventSensor {
  override val id                     = "rtsp_server_running"
  override val label                  = "RTSP Server"
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
