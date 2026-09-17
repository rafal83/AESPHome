# H.264 / RTSP — Technical Plan (not implemented)

Per the task's own instruction for this area: MJPEG is implemented and working
(`docs/IMPLEMENTATION_REPORT.md`); H.264/RTSP is a substantially larger effort and is
documented here instead of attempted as a partial/unverified implementation.

## Why this is a separate tier of work from MJPEG

MJPEG reuses `CameraService`'s existing `ImageReader`(JPEG)-based Camera2 pipeline as-is —
each frame is already a complete, independent JPEG, so serving it over HTTP is just framing
bytes that already exist. H.264 needs an entirely different capture surface (a YUV/private
`Surface` feeding `MediaCodec`, not a JPEG `ImageReader`), a stateful encoder pipeline, RTP
packetization, and a real RTSP server — none of which share code with the existing pipeline.

## Proposed architecture

```
Camera2 (encoder-input Surface)
   -> MediaCodec (video/avc, hardware encoder when available)
   -> access-unit callback (SPS/PPS + NAL units, via MediaCodec.Callback or BufferInfo polling)
   -> RTP packetizer (RFC 6184 H.264 payload)
   -> RTSP session per client (DESCRIBE/SETUP/PLAY/TEARDOWN)
   -> rtsp://<ip>:8554/aesphome
```

### Camera2 → MediaCodec

- A second capture session target: `MediaCodec.createInputSurface()` passed to Camera2 as an
  additional output alongside (or instead of, while streaming) the existing JPEG
  `ImageReader` — Camera2 supports multiple simultaneous output surfaces from one session, so
  this *can* share the same open camera device as MJPEG/HA snapshots, but not the same
  `CaptureRequest` target list without care (JPEG capture and continuous encoder feed have
  different ideal capture rates/latency behavior) — needs real-device testing to confirm a
  shared session doesn't starve one consumer or the other; a dedicated capture session used
  only while RTSP has an active client (closed the rest of the time) is the safer starting
  point.
- `MediaFormat` for `MediaCodec.createEncoderByType("video/avc")`: resolution matching one of
  `CameraService`'s existing resolution options, a modest bitrate (e.g. 2 Mbps at 720p) and
  keyframe interval (e.g. 2s) tuned for a proxy — not for archival quality — and I-frame
  interval short enough that a client joining mid-stream doesn't wait long for a keyframe.
- Use the hardware encoder when available (`MediaCodecList` query for a hardware-backed
  AVC encoder) — a software fallback exists on every device but would defeat the point on the
  low-end/older hardware this project targets.

### SPS/PPS

- `MediaCodec` delivers the SPS/PPS as a `BUFFER_FLAG_CODEC_CONFIG` buffer once, before the
  first frame — cache it and re-send it (as RTSP's `sprop-parameter-sets` in the SDP, per
  RFC 6184 §8.1) on every new client's `DESCRIBE`, and re-insert it in-band before every
  keyframe so a client that joins mid-GOP can still decode from the next I-frame.

### RTP packetization

- RFC 6184 single-NAL and FU-A fragmentation (most encoded frames exceed one UDP-safe MTU) —
  this is genuinely non-trivial to get byte-exact; a subtly wrong FU-A header is the single
  most common source of "plays for a second then corrupts" bugs in a hand-rolled RTP sender.
- RTP-over-TCP (interleaved, RFC 2326 §10.12) is the pragmatic choice for a first
  implementation — avoids a second UDP port to open/firewall and avoids RTP timestamp/jitter
  buffer edge cases that show up more with real UDP loss; RTP-over-UDP can follow once TCP
  interleaving is verified working end to end.

### RTSP server

- Minimal method set: `OPTIONS`, `DESCRIBE` (returns an SDP `Content-Type: application/sdp`
  body derived from the cached SPS/PPS), `SETUP` (allocate a session, negotiate interleaved
  channel numbers), `PLAY`, `TEARDOWN`. No `PAUSE`/seeking — this is a live proxy, not VOD.
- Same hand-rolled plain-socket style as `esphome.kt`/`mjpeg_server.kt` — no new dependency;
  the RTSP control protocol itself is simple, line-based text (much like the MJPEG server's
  own request parsing), and RTP-over-TCP interleaving means no separate RTP socket code path
  either.

### Multiple clients

- Camera2 → MediaCodec is one encoder producing one elementary stream; multiple RTSP clients
  should share it (same encoder output fanned out to each session's RTP packetizer/socket),
  the same broadcast-listener pattern `CameraService.addFrameListener`/`broadcastFrame`
  already establishes for MJPEG — reused here for encoded access units instead of JPEGs.

### Authentication

- Same lightweight token-in-URL approach as MJPEG (`?token=...` — RTSP doesn't have a
  standard equivalent to a query string, so this would need to ride on RTSP Basic/Digest
  `Authorization` on `SETUP`/`DESCRIBE` instead), configured from the same place in the app.

### go2rtc / Frigate integration

Once implemented, the intended config mirrors the MJPEG examples in `README.md`:

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

## Suggested order of implementation

1. `MediaCodec` encoder fed from a still/synthetic `Surface` first (verify SPS/PPS + NAL
   output alone, no networking) before touching Camera2's dual-surface capture session.
2. RTSP `DESCRIBE`/`SETUP`/`PLAY` against a single hardcoded client, RTP-over-TCP only, no
   fragmentation (small frames / low resolution) — get one client playing back correctly in
   VLC or ffplay before anything else.
3. FU-A fragmentation for real-resolution frames.
4. Multiple simultaneous clients.
5. Camera2 dual-surface sharing with the existing JPEG pipeline (or confirm it needs to stay
   a separate capture session while RTSP is active).

Each step above is independently testable against a real player (VLC/ffplay/go2rtc) before
moving to the next — this is not a good candidate for "implement it all, then debug," given
how failure-opaque a wrong RTP/SDP byte tends to be from the symptom alone.
