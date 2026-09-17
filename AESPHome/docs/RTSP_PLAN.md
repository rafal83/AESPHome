# H.264 / RTSP

## Status: implemented, NOT verified against a real player

`rtsp_server.kt` implements the architecture this document originally proposed (kept below,
since the implementation follows it directly). Unlike every other feature in this codebase,
**this one could not be tested end to end** — the environment it was built in had no
camera-equipped Android device to actually run it on. What was verified:

- The app compiles and packages with it (`./gradlew clean test assembleDebug`).
- The one genuinely pure piece of protocol logic — Annex-B NAL splitting and RFC 6184 FU-A
  header byte-packing — is unit tested (`RtspAnnexBTest.kt`) against hand-computed expected
  bytes.
- The RTP header layout, SDP `fmtp` line, and RTSP method set were written directly against
  RFC 6184 / RFC 2326, not from memory of "roughly how RTSP works."

What was **not** verified, because it requires a real camera and a real player (VLC/ffplay/
go2rtc) neither of which were available:

- That Camera2's encoder-`Surface` + `MediaCodec` pipeline actually produces valid Annex-B
  output on real hardware the way it's assumed to (this is well-documented, standard Android
  behavior, but "documented" isn't "observed here").
- That a real RTSP client accepts this server always answering `SETUP` with
  `RTP/AVP/TCP;interleaved=0-1` regardless of what transport it originally proposed. This is
  a known-working pattern for TCP-only RTSP servers in general, but it's a claim about other
  people's client software, not something this branch exercised against one.
- That the SDP `sprop-parameter-sets`/`profile-level-id` values a real decoder receives
  actually let it initialize correctly, and that the in-band SPS/PPS-before-every-IDR
  re-insertion is both correctly formed and actually necessary/sufficient in practice.

**Before relying on this**: install the APK on a real device, enable Camera + RTSP Server, and
point `ffplay rtsp://<ip>:8554/aesphome` (or VLC, or go2rtc) at it. If it doesn't play, the
first things to check, in order: (1) does `ffplay -rtsp_transport tcp` work when plain `ffplay`
doesn't — confirms whether the "always answer TCP" SETUP behavior above is the issue; (2)
capture the raw TCP stream (e.g. Wireshark on the RTSP port) and check the RTP sequence
numbers/timestamps are monotonically increasing and the first few NAL units look like a
plausible SPS (starts `0x67`) — confirms whether the problem is Camera2/MediaCodec output
format vs. this file's RTP packetization.

## Implemented

- `switch`-free — `Service` `rtsp_server`, `number.rtsp_port` (default 8554),
  `number.rtsp_bitrate_kbps` (default 1500), `select.rtsp_resolution` (640x480/1280x720, no
  finer control — kept simple rather than exposing every MediaCodec knob),
  `binary_sensor.rtsp_server_running`, `text_sensor.rtsp_url`.
- Camera2 → `MediaCodec` (`video/avc`, `COLOR_FormatSurface` input, hardware encoder when the
  device has one, 15fps, 2s I-frame interval) — a dedicated capture session separate from
  `CameraService`'s JPEG pipeline (see `rtsp_server.kt`'s file header for why sharing one
  wasn't attempted).
- SPS/PPS extracted from `MediaCodec`'s `BUFFER_FLAG_CODEC_CONFIG` buffer, cached, sent in the
  SDP (`sprop-parameter-sets`) and re-inserted in-band before every IDR.
- RFC 6184 RTP packetization: single-NAL-unit packets when a NAL fits one RTP payload,
  FU-A fragmentation otherwise.
- RTP-over-TCP interleaved only (RFC 2326 §10.12) — no UDP transport (see "why RTP-over-TCP"
  below).
- RTSP methods: `OPTIONS`, `DESCRIBE`, `SETUP`, `PLAY`, `TEARDOWN`. No `PAUSE`/seeking — this
  is a live proxy, not VOD.
- Multiple simultaneous clients share one encoder (same broadcast-to-listeners pattern
  `CameraService.addFrameListener`/`broadcastFrame` already established for MJPEG, applied
  here to encoded access units instead of JPEGs).
- Encoder/camera only run while at least one session is `PLAY`ing — never held open just
  because the RTSP server itself is enabled.

## Not implemented

- **Authentication** — no RTSP `Authorization` (Basic/Digest), unlike MJPEG's token. Network-
  level access control (the same LAN/VLAN segmentation any camera stream should already have)
  is the mitigation until this exists.
- **RTP-over-UDP** — see "why RTP-over-TCP" below.
- **MTU-aware fragmentation tuning / RTCP** — `MAX_RTP_PAYLOAD` is a fixed, conservative 1400
  bytes; no path-MTU discovery. An RTCP channel is declared in `SETUP`'s interleaved range but
  nothing is ever sent on it (receiver reports are simply never read either).
- **Shared camera session with the JPEG pipeline** — RTSP and Camera/MJPEG cannot run
  simultaneously against the same physical lens; Android's own camera framework enforces this
  (a clean `ERROR_CAMERA_IN_USE` callback, not a crash) rather than this app coordinating it.

## Why this is a separate tier of work from MJPEG

MJPEG reuses `CameraService`'s existing `ImageReader`(JPEG)-based Camera2 pipeline as-is —
each frame is already a complete, independent JPEG, so serving it over HTTP is just framing
bytes that already exist. H.264 needs an entirely different capture surface (an encoder
`Surface` feeding `MediaCodec`, not a JPEG `ImageReader`), a stateful encoder pipeline, RTP
packetization, and a real RTSP server — none of which share code with the existing pipeline.

## Why RTP-over-TCP (not UDP) for the first implementation

- Avoids a second port to open/firewall alongside the RTSP control connection.
- Avoids RTP timestamp/jitter-buffer edge cases that show up more under real UDP loss —
  harder to reason about without a real network and real player to observe them with.
- Many real IP cameras only ever offer TCP interleaved and work fine with ffmpeg/VLC/go2rtc;
  it's a legitimate, not merely simplified, choice for a first implementation.

RTP-over-UDP could follow once TCP interleaving is confirmed working end to end on real
hardware.

## go2rtc / Frigate integration (as documented in README.md)

```yaml
go2rtc:
  streams:
    aesphome:
      - rtsp://192.168.x.x:8554/aesphome

cameras:
  aesphome:
    ffmpeg:
      inputs:
        - path: rtsp://127.0.0.1:8554/aesphome
          roles:
            - detect
```
