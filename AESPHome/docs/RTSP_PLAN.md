# H.264 / RTSP

## Status: real-device root cause confirmed and fixed (v2026.9.1)

Continuing the diagnosis below: with the improved drain-loop logging shipped in v2026.9.1
(exception type + full stack trace, not just a swallowed/unlogged catch), the exact failure
was captured directly from the real device's logcat:

```
E/AESPHome/RTSP: encoder drain loop failed (IllegalStateException), tearing down
E/AESPHome/RTSP: java.lang.IllegalStateException
    at android.media.MediaCodec.native_dequeueOutputBuffer(Native Method)
    at android.media.MediaCodec.dequeueOutputBuffer(MediaCodec.java:2717)
    at com.aesphome.RtspServerService.startDrainLoop$lambda$9(rtsp_server.kt:393)
```

Confirmed on the device: **Person Detection was enabled** (its own periodic one-shot capture
requests were the `Camera: one-shot capture requested` lines firing every ~2-3s throughout
every earlier test), and RTSP's own lens selection reuses `CameraService`'s current choice
directly — it is never a different physical camera. `dequeueOutputBuffer` throwing
`IllegalStateException` (bare, no message) with no prior camera error logged is what a
hardware AVC encoder whose input `Surface` never actually receives real camera frames looks
like on this MediaTek-based device: the session *opens* without error, but with nothing
feeding it, the encoder's internal state degrades until it errors out — consistently around
10 seconds in, matching every real-device report of this bug from v0.2.2 onward.

**Fix**: `startEncoder()` now refuses to start at all — logging exactly why — when Camera,
MJPEG, or Person Detection is currently enabled, since any of them holds the same physical
camera RTSP would try to open. This turns the failure from "PLAY succeeds, then something
dies silently/cryptically ~10s later" into "DESCRIBE fails fast (~4s, via `sdpBody()`'s
existing SPS/PPS poll timeout) with a clear reason in logcat." A real shared-CameraPipeline
(one Camera2 session multiplexed across Camera/MJPEG/Person Detection/RTSP) remains the only
way to let RTSP actually run *at the same time* as those features — audited, not attempted
this pass; see below and `docs/SECURITY.md`. For now: disable Camera/MJPEG/Person Detection
while using RTSP, and vice versa.

## Status: real-device diagnosis via VLC + adb (2026.9.0) — likely root cause found, one confirmed protocol gap fixed

After v0.2.5's write-lock fix, real-device testing still reported the same "plays for a
moment then dies" symptom. This time it was reproduced directly: with the tablet on the same
LAN and reachable from a dev machine running VLC 3.0.20 with verbose logging
(`--rtsp-tcp -vvv --logfile=...`) and `adb logcat` open on the device simultaneously —

- **A raw hand-written RTSP client** (a small Python script driving the exact
  OPTIONS/DESCRIBE/SETUP/PLAY/TEARDOWN sequence and reading the interleaved frames directly)
  received a clean, continuous, correctly-framed RTP stream — hundreds of frames over 8-10s,
  zero desync — proving the *protocol* implementation itself (framing, RTP/FU-A
  packetization, the socketWriteLock fix) is correct.
- **VLC** (real live555 client), against the exact same running server, got `PLAY` `200 OK`
  and then **zero bytes of RTP data for the entire session** — `live555 demux warning: no
  data received in 10s, eof?` — even with `--rtsp-tcp` forcing TCP interleaved from the very
  first request (ruling out the known, harmless "tries UDP first" live555 quirk noted below).
- **`adb logcat` showed no corresponding `AESPHome/RTSP` log line at all** for the failed
  session — no camera error, no encoder error, nothing — while `Camera: one-shot capture
  requested` (Camera/MJPEG/person-detection's own JPEG pipeline, on this device seemingly
  enabled and firing every ~2-3s) kept cycling the same physical camera undisturbed the whole
  time.

**Likely root cause**: `MediaCodec.dequeueOutputBuffer()` returning "nothing yet" is not an
error — it doesn't throw, so the old code's `catch (e: Exception)` never saw anything wrong.
If the camera session RTSP opens for its own encoder input surface never actually gets a real
frame delivered to it — plausible on hardware whose camera HAL doesn't support two truly
independent concurrent sessions on one physical sensor, especially with another feature
(Camera/MJPEG/person detection) already cycling that same camera every few seconds — the drain
loop would just spin on "try again later" forever, producing zero output and logging zero
errors, while the RTSP control channel (already answered `PLAY 200 OK` before any of this)
has no way to tell the client anything went wrong. This was invisible for two compounding
reasons, both fixed now:

- `session.setRepeatingRequest(request, null, handler)` passed a **null** capture callback —
  any actual per-capture failure (e.g. `ERROR_CAMERA_IN_USE`-adjacent contention that doesn't
  surface through `CameraDevice.StateCallback`) was silently discarded. Now registers a real
  `CaptureCallback` that logs `onCaptureFailed` (rate-limited to the first few).
- The drain loop had no concept of "started but never produced anything" — it would spin
  quietly forever. Added `ENCODER_STARTUP_TIMEOUT_MS` (6s): if zero output has been produced
  by then, it logs an explicit diagnosis (naming the likely cause) and tears down, instead of
  leaving the client to work out for itself, 4-10s later, that nothing is coming.
- Separately: whatever ends the drain loop now always calls `stopEncoder()`. Previously, if
  the loop ended any way other than an explicit external `stopEncoder()` call, `streaming`
  could get stuck `true` forever — silently breaking *every future* RTSP session (each still
  getting a normal-looking `PLAY 200 OK`) until the app was restarted, since
  `onSessionPlay()` only calls `startEncoder()` when `!streaming`.

**Also fixed, independently of the above**: `DESCRIBE`'s response was missing a
`Content-Base` header. Several real RTSP clients (live555, which VLC uses, among them) rely
on it to resolve the SDP's relative `a=control:trackID=0` URL; without it, resolution is
implementation-defined rather than guaranteed. Verified this wasn't itself the primary cause
here (the raw Python client resolved the same relative URL to the right place regardless,
and VLC's own log showed it correctly deriving `.../aesphome/trackID=0` for `SETUP` even
without the header) but it's a real, cheap-to-fix gap against other/future clients.

**Still not proven**: which exact one of "HAL doesn't support two concurrent sessions" vs.
some other cause is the *complete* explanation — that needs an A/B test (disable Camera/
MJPEG/Person Detection entirely, retry RTSP alone) that wasn't done yet on this pass (the
device this was diagnosed on had Device Owner set, which blocks the `adb uninstall` needed to
swap in a differently-signed debug build for further live iteration — see `FAQ.md`). The
watchdog/callback logging above will make the *next* attempt immediately conclusive either
way, from logcat alone, without needing another multi-tool live debugging session to find it.

## Status: implemented, three real-device bugs found and fixed (v0.2.2 - v0.2.5)

Real-device testing kept reporting the stream starting and then dying a few seconds later,
deterministically, across two separate fix attempts:

- **v0.2.4**: theorized as a write-hang — Java's `Socket` has no write-timeout, so a slow/stuck
  client could block a write on the shared encoder-drain thread forever. Fixed by giving each
  session its own bounded queue and dedicated writer thread. This did **not** resolve the
  reported symptom.
- **v0.2.5** (the actual root cause): the RTSP control-response writes (from the socket's
  read/handle thread) and the RTP data writes (from the per-session writer thread added in
  v0.2.4) both wrote to the same `Socket`'s `OutputStream` with no lock between them — and the
  `PLAY` handler set `playing = true` *before* sending `PLAY`'s own response. That let the
  writer thread send binary interleaved RTP bytes to the client before the client had even
  received `RTSP/1.0 200 OK` for `PLAY`, permanently desyncing the client's interleaved-frame
  parser at exactly the point `PLAY` completes — matching the reported "works for a few seconds
  then dies" symptom exactly. Fixed with a `socketWriteLock` shared by every write to the
  session's socket, and by reordering `PLAY` to send its response before flipping
  `playing = true`.

## Status: implemented, one real-device bug found and fixed (v0.2.2)

Real-device testing (v0.2.1) reported RTSP not working while MJPEG worked fine on the same
build. Root cause: `DESCRIBE` — always a client's *first* request, before `SETUP`/`PLAY` —
required `spsNal`/`ppsNal` to already exist, but those are only produced once the encoder is
running, and the encoder was only started in reaction to `PLAY`. Every session failed at the
first step with a 503, before the encoder had ever been given a chance to start. Fixed:
`DESCRIBE`'s handler now starts the encoder itself (if not already running) and polls up to
4s for SPS/PPS to appear before responding, instead of requiring `PLAY` to have already
happened. Untested claims below are otherwise unchanged — this fix addresses a control-flow
bug, not the packetization/transport-negotiation questions still open.

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
  `number.rtsp_bitrate_kbps` (default 1500), `binary_sensor.rtsp_server_running`,
  `text_sensor.rtsp_url`. No separate resolution setting — the stream always uses whatever
  resolution is currently selected for the camera (`CameraService.selectedResolution()`),
  since streaming at a different resolution than the camera's own capture pipeline was found
  to be confusing in practice, not a useful degree of freedom.
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
