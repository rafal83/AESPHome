package com.aesphome

import android.content.Context
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
import java.util.concurrent.atomic.AtomicInteger


/*

  MJPEG HTTP Server
    A small plain-socket HTTP server (same hand-rolled style as esphome.kt's own TCP server —
    no new dependency) giving direct access to the camera feed alongside the ESPHome API:

      GET /camera.jpg     -> latest single JPEG frame
      GET /camera.mjpeg   -> multipart/x-mixed-replace stream, for go2rtc/Frigate/a browser

    Reuses CameraService's existing capture pipeline end to end — a viewer here just calls
    the same onImageRequest() HA's own CameraImageRequest handler calls, and receives frames
    through the same broadcastFrame() every capture already goes through. No second camera
    open, ever.

    Bound to the Wi-Fi IP only, same reasoning as the ESPHome API server itself (never
    reachable over cellular). Auth is a single shared token in the query string
    (?token=...), auto-generated and shown in MainActivity — "lightweight" by design, matching
    what the feature asks for; it is not meant to replace network-level access control.

*/


private const val BOUNDARY = "aesphomeframe"
private const val STREAM_KEEPALIVE_MS = 2000L // comfortably under CAMERA_STREAM_TIMEOUT_MS
private const val SINGLE_SHOT_WAIT_MS = 4000L

object MjpegServerService : Service {
  override val id                  = "mjpeg_server"
  override val label               = "MJPEG Server"
  override val description         = "Requires Camera enabled — direct HTTP access to the camera feed"
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = false
  override val icon                = "mdi:server-network"

  val portSetting = Setting(
      id = "mjpeg_port", label = "MJPEG Port", default = 8080f, min = 1024f, max = 65535f, step = 1f,
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false, icon = "mdi:lan-connect")

  val fpsSetting = Setting(
      id = "mjpeg_max_fps", label = "MJPEG Max FPS", default = 5f, min = 1f, max = 30f, step = 1f,
      deviceUi = true, homeAssistant = true, entityCategory = EntityCategory.CONFIG,
      enabledByDefaultHa = false, icon = "mdi:speedometer")

  // Not exposed to HA on purpose — letting any HA user remotely disable the one thing
  // gating access to a raw camera feed defeats the point of it being configurable at all.
  val authSetting = SelectSetting(
      id = "mjpeg_require_token", label = "MJPEG Require Token", options = listOf("Enabled", "Disabled"),
      default = "Enabled", deviceUi = true, homeAssistant = false, entityCategory = EntityCategory.CONFIG,
      icon = "mdi:key-outline")

  override val settings: List<Setting> = listOf(portSetting, fpsSetting)
  override val selectSettings: List<SelectSetting> = listOf(authSetting)

  @Volatile private var serverSocket: ServerSocket? = null
  @Volatile private var running = false
  private val clientCount = AtomicInteger(0)

  fun token(context: Context): String {
    val existing = getStringFlag(context, "mjpeg_token", "")
    if (existing.isNotEmpty()) return existing
    val generated = UUID.randomUUID().toString().replace("-", "")
    setStringFlag(context, "mjpeg_token", generated)
    return generated
  }

  fun url(context: Context): String? {
    val ip = getWifiIpAddress() ?: return null
    val port = getSetting(context, portSetting).toInt()
    val suffix = if (getSelectSetting(context, authSetting) == "Enabled") "?token=${token(context)}" else ""
    return "http://$ip:$port/camera.mjpeg$suffix"
  }

  override fun start(context: Context) {
    running = true
    Thread({ acceptLoop(context) }, "AESPHomeMjpegServer").start()
  }

  override fun stop(context: Context) {
    running = false
    try { serverSocket?.close() } catch (e: IOException) {}
    serverSocket = null
    MjpegServerRunningSensor.updateState(false)
  }

  private fun acceptLoop(context: Context) {
    val wifiIp = getWifiIpAddress()
    if (wifiIp == null) {
      Log.e(TAG, "MJPEG server: no Wi-Fi IP yet, not starting")
      return
    }
    val port = getSetting(context, portSetting).toInt()
    try {
      val server = ServerSocket()
      server.reuseAddress = true
      server.bind(InetSocketAddress(InetAddress.getByName(wifiIp), port))
      serverSocket = server
      MjpegServerRunningSensor.updateState(true)
      Log.i(TAG, "MJPEG server listening on $wifiIp:$port")
      while (running) {
        val socket = try { server.accept() } catch (e: IOException) { break } // server.close() on stop() lands here
        Thread({ handleConnection(context, socket) }, "AESPHomeMjpegClient").start()
      }
    } catch (e: IOException) {
      Log.e(TAG, "MJPEG server failed to bind: ${e.message}")
    } finally {
      MjpegServerRunningSensor.updateState(false)
    }
  }

  private fun handleConnection(context: Context, socket: Socket) {
    try {
      socket.soTimeout = 10_000
      val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charset.forName("ISO-8859-1")))
      val requestLine = reader.readLine() ?: return
      while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break } // drain headers, unused

      val parts = requestLine.split(" ")
      if (parts.size < 2) return
      val target = parts[1]
      val path = target.substringBefore("?")
      val query = target.substringAfter("?", "")
      val token = query.split("&").firstOrNull { it.startsWith("token=") }?.removePrefix("token=")

      if (getSelectSetting(context, authSetting) == "Enabled" && token != token(context)) {
        writeStatus(socket, 401, "Unauthorized")
        return
      }

      when (path) {
        "/camera.jpg" -> serveSingle(context, socket)
        "/camera.mjpeg" -> serveStream(context, socket)
        else -> writeStatus(socket, 404, "Not Found")
      }
    } catch (e: IOException) {
      // Expected for a client that disconnects mid-request — not worth its own log line.
    } finally {
      try { socket.close() } catch (e: IOException) {}
    }
  }

  private fun writeStatus(socket: Socket, code: Int, text: String) {
    socket.getOutputStream().write("HTTP/1.0 $code $text\r\nConnection: close\r\n\r\n".toByteArray())
  }

  private fun serveSingle(context: Context, socket: Socket) {
    var frame = CameraService.latestFrame()?.takeIf { (CameraService.latestFrameAgeMs() ?: Long.MAX_VALUE) < SINGLE_SHOT_WAIT_MS }
    if (frame == null) {
      val queue = ArrayBlockingQueue<ByteArray>(1)
      val listener: (ByteArray) -> Unit = { queue.offer(it) }
      CameraService.addFrameListener(listener)
      try {
        CameraService.onImageRequest(context, stream = false)
        frame = queue.poll(SINGLE_SHOT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
      } finally {
        CameraService.removeFrameListener(listener)
      }
    }
    if (frame == null) { writeStatus(socket, 503, "Service Unavailable"); return }

    val out = socket.getOutputStream()
    out.write("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\nConnection: close\r\n\r\n".toByteArray())
    out.write(frame)
    out.flush()
  }

  private fun serveStream(context: Context, socket: Socket) {
    val fpsMinIntervalMs = (1000f / getSetting(context, fpsSetting).coerceAtLeast(1f)).toLong()
    val queue = ArrayBlockingQueue<ByteArray>(1)
    val listener: (ByteArray) -> Unit = { if (!queue.offer(it)) { queue.poll(); queue.offer(it) } }

    clientCount.incrementAndGet()
    CameraService.addFrameListener(listener)
    try {
      val out = socket.getOutputStream()
      out.write("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())

      var lastSentMs = 0L
      var lastKeepAliveMs = 0L
      while (running) {
        val now = System.currentTimeMillis()
        if (now - lastKeepAliveMs >= STREAM_KEEPALIVE_MS) {
          CameraService.onImageRequest(context, stream = true) // same call HA's own stream re-requests make
          lastKeepAliveMs = now
        }
        val frame = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
        if (now - lastSentMs < fpsMinIntervalMs) continue
        lastSentMs = now
        out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
        out.write(frame)
        out.write("\r\n".toByteArray())
        out.flush()
      }
    } finally {
      CameraService.removeFrameListener(listener)
      clientCount.decrementAndGet()
    }
  }
}

// binary_sensor.mjpeg_server — mirrors CameraLuxSensor's pattern (Sensor.kt): a plain
// EventSensor with no listener of its own, driven externally (here, by
// MjpegServerService.acceptLoop/stop) rather than owning a start()/stop() lifecycle.
object MjpegServerRunningSensor : EventSensor {
  override val id                     = "mjpeg_server_running"
  override val label                  = "MJPEG Server"
  override val description            = ""
  override val key: Int               = id.hashCode()
  override fun kind(context: Context) = SensorKind.Binary()
  override val enabledByDefaultApp    = false
  override val enabledByDefaultHa     = true
  override val icon                   = "mdi:server-network"

  override fun isAvailable(context: Context): Boolean = isEnabled(context, MjpegServerService)

  fun updateState(running: Boolean) {
    AESPHomeService.instance?.reportSensor(this, running)
  }

  override fun start(context: Context) {}
  override fun stop(context: Context) {}
}
