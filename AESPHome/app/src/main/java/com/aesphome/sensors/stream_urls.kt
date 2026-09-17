package com.aesphome

import android.content.Context


/*

  Stream URLs
    text_sensor entities exposing the ready-to-use URLs for the MJPEG and RTSP servers —
    same values MainActivity already shows in-app (mjpegStatusText), now also visible from
    Home Assistant (e.g. for a dashboard card, or copying straight into a go2rtc config)
    without needing to open the app. null (missing_state) whenever the owning server isn't
    enabled or doesn't have a URL yet (no Wi-Fi IP), same "never fabricate" contract as every
    other diagnostic sensor.

*/


object MjpegUrlSensor : ReadTextSensor {
  override val id                  = "mjpeg_url"
  override val label               = "MJPEG URL"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.DIAGNOSTIC
  override val icon                = "mdi:link"
  override fun isAvailable(context: Context): Boolean = isEnabled(context, MjpegServerService)
  override fun read(context: Context): String? = MjpegServerService.url(context)
}

object RtspUrlSensor : ReadTextSensor {
  override val id                  = "rtsp_url"
  override val label               = "RTSP URL"
  override val description         = ""
  override val key: Int            = id.hashCode()
  override val enabledByDefaultApp = false
  override val enabledByDefaultHa  = true
  override val entityCategory      = EntityCategory.DIAGNOSTIC
  override val icon                = "mdi:link"
  override fun isAvailable(context: Context): Boolean = isEnabled(context, RtspServerService)
  override fun read(context: Context): String? = RtspServerService.url(context)
}
