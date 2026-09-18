# Security

What's actually implemented, what it does and doesn't protect against, and — just as
important — what was verified versus what's a best-effort implementation against a protocol
this app couldn't be tested against a real reference client for. Only documents finished
work; anything still open is called out explicitly rather than glossed over.

## ESPHome API transport: plaintext + optional Noise encryption

By default this device speaks the same plaintext ESPHome Native API every version of this
app has always spoken — nothing changes for an existing installation.

Noise encryption (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`, the same transport real ESPHome
firmware and Home Assistant's own ESPHome integration support) is available as an **opt-in**
second mode, enabled from the "API Encryption (Noise)" card on the main screen: a switch, the
pre-shared key (base64, generated in-app), and a Generate button. Enabling it does not disable
plaintext at the protocol level — a new connection's very first byte tells this device which
framing that connection is using (`esphome.kt`'s `readMessage()`), so nothing about an existing
plaintext-configured Home Assistant integration breaks the moment Noise is turned on; it only
adds a second door in, which a client has to actually be configured to use.

**Implementation**: `app/src/main/java/com/aesphome/noise.kt`. The X25519 ECDH, ChaCha20-
Poly1305 AEAD, SHA-256, and the Noise handshake state machine itself all come from
[noise-java](https://github.com/rweather/noise-java) (via JitPack — no Maven Central artifact
or tagged release exists upstream, so it's pinned to a specific commit in `app/build.gradle`).
This app's own code only implements ESPHome's wire framing *around* that library.

**Verified against ESPHome's own source** (`esphome/components/api/api_frame_helper_noise.cpp`,
aioesphomeapi's `_frame_helper/noise.py`):
- The outer transport frame: 1-byte indicator (`0x00` plaintext / `0x01` Noise) + 2-byte
  big-endian length + content, for every frame in both directions, handshake and data alike.
- The prologue: the literal bytes `"NoiseAPIInit"`, then a 2-byte big-endian length and the
  ClientHello frame's content (not the ServerHello) — mixed into the handshake hash so a
  MITM tampering with the plaintext hello exchange makes the handshake fail rather than
  silently succeed with a spoofed hello.
- ServerHello's content: a fixed `0x01` "chosen protocol" byte, then the device name, then
  its MAC address, each null-terminated.
- The post-handshake data frame's decrypted structure: a 4-byte `[type_hi][type_lo][len_hi]
  [len_lo]` header followed by the payload, with no associated data.
- The handshake status byte this server prefixes onto its own outgoing handshake messages
  (`0x00` = proceeding normally; non-zero = what follows is a plaintext rejection reason
  instead of a Noise message) — used to reject a bad-PSK handshake explicitly rather than
  just closing the socket.

**A genuine library-naming quirk, not a protocol difference**: noise-java predates the modern
`NNpsk0` pattern-name convention and has no matching entry in its pattern table. It instead
uses an older `NoisePSK` prefix with the bare `NN` pattern (`NoisePSK_NN_25519_ChaChaPoly_
SHA256`). Checked against `HandshakeState.java`'s `start()`: this mixes the PSK in exactly
once, unconditionally, before any message-1 token is processed — the same point `psk0`
specifies. The two names produce identical cryptographic operations; this is a local API
call convention difference between two implementations of the same spec, never anything that
crosses the wire.

**Tested**: `NoiseHandshakeRoundTripTest.kt` runs a real handshake — this app's server-role
code (`performNoiseServerHandshake`) against a separate, real client-role `HandshakeState`
instance (INITIATOR) driven directly in the test — over an actual loopback TCP socket. It
proves: a correct PSK completes the handshake and the resulting ciphers correctly encrypt/
decrypt data in both directions; a wrong PSK is detected and rejected (not silently
"succeeding" with mismatched keys). `NoiseFramingTest.kt` checks every wire-layout function
above against hand-computed expected bytes.

**What this does NOT prove, and the reason Noise defaults to off**: none of the above proves
byte-for-byte wire compatibility with the *real* aioesphomeapi client inside Home Assistant —
no Home Assistant instance was available while building this. Before relying on Noise for
anything: enable it, set a key, configure the same key in Home Assistant's ESPHome
integration prompt, and confirm the connection actually establishes. If it doesn't, plaintext
remains available as a fallback (just turn the switch back off) — nothing about the plaintext
path was touched by this feature.

**Key storage**: `secure_storage.kt`. On API 23+, the PSK is AES-256-GCM encrypted under an
AndroidKeyStore-backed key before being written to SharedPreferences — the key never leaves
the keystore, so the encrypted value alone is worthless without device-level compromise. Below
API 23 (AndroidKeyStore has no symmetric-key support at all there — only RSA, which would need
a materially larger implementation for a single, largely obsolete API level given this app's
own stated floor is Android 9, see `FAQ.md`), it falls back to the same plain
SharedPreferences storage every other setting in this app already uses — not a regression.

## Auto-update: what gets installed, and how it's verified

Full flow: `sensors/auto_update.kt`.

- **Asset selection**: `isAcceptableUpdateApkName()` refuses any GitHub Release asset whose
  name contains `unsigned` or `debug`, *unless this app's own running build is itself a debug
  build* (`BuildConfig.DEBUG`) — never true for anything actually published, so an end user
  can't be tricked or misconfigured into accepting one. When more than one acceptable `.apk`
  asset exists, the one whose name contains `release` is preferred.
- **Integrity**: after downloading, the byte count actually received is checked against
  `Content-Length` (an incomplete transfer is refused, not silently installed short), and the
  matching `<apk-name>.sha256` release asset (produced by `release.yml`, see below) is
  downloaded and compared against a freshly computed local SHA-256 of the downloaded file.
  Any checksum mismatch, or a checksum that can't be fetched/parsed, refuses the install
  outright — it is never treated as "probably fine."
- **Install path**: tap-to-confirm via Android's own installer UI by default; a zero-tap
  silent install via `PackageInstaller` only when this app has also been made **Device Owner**
  (see `FAQ.md` for the `adb` command — there is no in-app grant flow for Device Owner by
  design, since it's a largely-irreversible, device-wide commitment).

## Release signing (`.github/workflows/release.yml`)

- Every release is built from a `v*` git tag; the workflow now **fails outright** if that
  tag's version doesn't match `app/build.gradle`'s `versionName` — catches tagging a release
  without remembering to bump the version first.
- No signing keystore is ever committed to the repository. `ANDROID_KEYSTORE_BASE64` (and
  its accompanying password/alias secrets) are decoded to a local file only for the duration
  of the build job, and that file is deleted (`rm -f release.keystore`) in a step that always
  runs, even if an earlier step failed.
- When a keystore secret is configured, the resulting APK's signature is verified with
  `apksigner verify` before it's ever published (skipped, with a warning rather than a
  failure, if this runner's Android SDK has no build-tools shipping `apksigner` — an
  environment gap, not evidence of a bad signature).
- A SHA-256 checksum is computed and published alongside every release APK
  (`AESPHome-x.y.z-release.apk.sha256`) — the exact file the auto-updater above verifies
  against.
- If no keystore secret is configured at all, the workflow still produces and publishes an
  APK (so a maintainer always gets *something* from every tag) but names it
  `*-unsigned.apk`, marks the GitHub Release itself as a **prerelease**, and adds an explicit
  warning to the release body — it does not look like, or get treated as, an official
  installable release. The in-app auto-updater refuses this asset unconditionally (see
  above), independent of anything the release page itself says.
- The release workflow's concurrency group no longer cancels an in-progress run when a second
  tag is pushed — a cancelled release could otherwise leave a partially-published GitHub
  Release (e.g. the APK uploaded but its checksum not) with no automatic cleanup.

## MJPEG server auth (`sensors/mjpeg_server.kt`)

A single shared token, auto-generated (`UUID.randomUUID()`, 122 bits of randomness) and
shown in-app next to the server's URL — required by default, and explicitly **not** exposed
as a Home Assistant entity (letting any HA user remotely disable the one thing gating a raw
camera feed would defeat the point of it being configurable at all). Accepted either as a
`?token=` query parameter (what the README's go2rtc/Frigate examples use) or an
`Authorization: Bearer <token>` header. Compared with `MessageDigest.isEqual`, not `==`/`!=`,
so response timing can't be used to guess it one byte at a time.

## RTSP server auth (`sensors/rtsp_server.kt`)

Optional (off by default) RTSP Basic auth — same reasoning as MJPEG for why it's not an HA
entity. Credentials are embedded directly in the connection URL shown in-app
(`rtsp://user:pass@ip:port/aesphome`), the same convention ffmpeg/VLC/go2rtc already
understand for RTSP URLs. Compared with `MessageDigest.isEqual`.

**Why Basic, not Digest**: RTSP has no other standardized auth scheme in wide practical use
for a server like this one, and a correct Digest implementation needs server-side nonce
tracking and replay protection — a materially larger addition than this LAN-camera-stream
threat model was judged to need. Basic auth sends credentials in a form that's trivially
recovered by anyone who can already read the connection (i.e. it adds essentially nothing
against an on-path attacker) — its value here is against a *different* attacker: someone on
the same network who can reach the port but does not already have the ability to observe
this specific connection's traffic. Network-level access control (the same LAN/VLAN
segmentation any camera stream should already have) remains the primary mitigation either way.

## What was explicitly not attempted, and why

- **RTP-over-UDP for RTSP**: TCP interleaved remains the only transport, on purpose — see
  `docs/RTSP_PLAN.md`.
- **A unified CameraPipeline abstraction** (one owner for Camera2 across ESPHome Camera/
  MJPEG/TFLite and RTSP's MediaCodec encoder): audited, not implemented. This app already
  runs two independent, single-output Camera2 sessions (JPEG `ImageReader` for Camera/MJPEG/
  person-detection; an encoder `Surface` for RTSP) rather than one multi-output session shared
  between them. Merging them was judged a bigger risk to the already-working JPEG/MJPEG path
  than the current, already-documented trade-off (`rtsp_server.kt`'s file header): Android's
  own camera framework already handles the conflict cleanly (`ERROR_CAMERA_IN_USE`, logged as
  "camera multi-output unsupported, fallback active", no crash) if RTSP and Camera/MJPEG ever
  try to open the same physical lens at once — they simply cannot stream simultaneously, which
  matches this device's Wi-Fi/CPU headroom being much better spent on one stream at a time
  regardless of whether the underlying session is shared or not.
- **Per-ABI split APKs**: not built this pass — the current single "fat" APK (all ABIs) stays
  the only release artifact. Revisit if APK size becomes a real problem in practice; the
  auto-updater would keep selecting a universal build either way per this feature's own
  stated fallback ("keep universal for auto-update, publish ABI splits as manual
  alternatives") to avoid taking on ABI-aware asset-selection logic for a P2-priority item.
- **RTSP/Noise verified against real reference clients**: RTSP against a real VLC/ffmpeg/
  go2rtc was done in an earlier pass (see `docs/RTSP_PLAN.md`'s changelog) and is believed
  working as of the socket-write-race fix in v0.2.5. Noise has not been — see above.
