package com.aesphome

import android.content.Context
import android.util.Base64
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom

/*

  Noise encryption (ESPHome's "api: encryption:" transport)
    Real ESPHome devices can run the Native API either in plaintext (this app's only mode
    until now) or wrapped in a Noise Protocol Framework handshake — the same "encryption
    key" HA's ESPHome integration already knows how to prompt for and use. This adds that as
    a second, OPT-IN mode: plaintext keeps working exactly as before (esphome.kt dispatches
    on the very first byte of a new connection — 0x00 keeps going through the existing
    varint-framed path unchanged; 0x01 comes here), so a device already configured for
    plaintext HA integration is completely unaffected until the operator explicitly enables
    Noise AND sets a key from the device's own UI.

    NOT hand-rolled crypto: the actual X25519 ECDH, ChaCha20-Poly1305 AEAD, SHA-256, and the
    entire Noise handshake state machine (HandshakeState/CipherState/CipherStatePair below)
    come from noise-java (github.com/rweather/noise-java, via JitPack — see app/build.gradle
    for why: no Maven Central artifact and no tagged release exist upstream, so it's pinned
    to a specific commit). This file only implements ESPHome's own wire framing AROUND that
    library — which frame goes on the wire in which order, and how the prologue/ServerHello
    bytes are built — verified against ESPHome's actual source (esphome/components/api/
    api_frame_helper_noise.cpp, aioesphomeapi's _frame_helper/noise.py), not against memory
    of "roughly how Noise works." See docs/SECURITY.md for exactly what was verified this
    way versus inferred from protocol self-consistency, and — importantly — that this has
    NOT been exercised against a real live Home Assistant connection (no HA instance was
    available while building this): it compiles, and NoiseHandshakeRoundTripTest.kt proves
    this file's own client-role and server-role code can complete a real handshake and
    exchange encrypted data with each other, but that alone doesn't prove wire compatibility
    with aioesphomeapi's actual bytes. Noise defaults to OFF for exactly this reason — enable
    it deliberately, and keep plaintext available as a fallback until it's been confirmed
    working against your real Home Assistant instance.

*/

// ESPHome's own wire-level protocol identity is "Noise_NNpsk0_25519_ChaChaPoly_SHA256" (the
// modern Noise spec naming, where "psk0" means the PSK is mixed in as the first token of
// message 1) — but that string is never transmitted anywhere; it only configures a local
// Noise library instance, and noise-java (unlike ESPHome's own noise-c-based firmware)
// predates that naming convention. noise-java's Pattern.lookup() has no entry for "NNpsk0"
// at all (confirmed against Pattern.java's pattern table) — it instead uses a "NoisePSK"
// prefix with the bare pattern name "NN", and HandshakeState.start() mixes the PSK in
// exactly once, unconditionally, before any message-1 token is processed (confirmed against
// HandshakeState.java's start()) — the same position "psk0" specifies. The two are
// cryptographically identical for what actually gets computed and sent on the wire; this is
// purely a local API-naming difference between two library implementations of the same spec.
private const val NOISE_PROTOCOL_NAME = "NoisePSK_NN_25519_ChaChaPoly_SHA256"
private const val NOISE_PROLOGUE_PREFIX = "NoiseAPIInit"

// The transport-frame indicator byte — esphome.kt peeks this on every new connection's first
// byte to decide plaintext vs. Noise framing for that connection's whole lifetime.
internal const val FRAME_INDICATOR_PLAINTEXT = 0x00
internal const val FRAME_INDICATOR_NOISE = 0x01

// ServerHello's first content byte: which Noise variant this server is using. Always this
// one fixed value — this server never negotiates a different protocol.
private const val NOISE_CHOSEN_PROTO: Byte = 0x01

// The status byte this server prefixes onto every one of its own outgoing handshake
// messages (verified: api_frame_helper_noise.cpp's `buffer[0] = noise::HANDSHAKE_STATUS_OK`
// on its handshake write path). 0 means "proceeding normally"; any non-zero value here (this
// server only ever sends exactly 1) means what follows is a plaintext UTF-8 rejection reason
// instead of a Noise handshake message — used to reject before/instead of ever running a
// handshake (disabled, unconfigured, or a mid-handshake MAC failure).
private const val HANDSHAKE_STATUS_OK: Byte = 0x00
private const val HANDSHAKE_STATUS_REJECTED: Byte = 0x01

internal const val NOISE_PSK_LENGTH = 32

class NoiseHandshakeException(message: String) : IOException(message)

// ==================== PSK generation / storage ====================

fun generateNoisePsk(): String {
  val bytes = ByteArray(NOISE_PSK_LENGTH)
  SecureRandom().nextBytes(bytes)
  return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

// Returns null (never throws) for anything that doesn't decode to exactly the 32 raw bytes a
// Noise_..._25519_..._SHA256 PSK must be — malformed base64, wrong length. Same fail-safe-to-
// null convention as auto_update.kt's parseSha256Checksum().
internal fun decodeNoisePskOrNull(base64: String): ByteArray? {
  val bytes = try { Base64.decode(base64.trim(), Base64.DEFAULT) } catch (e: Exception) { return null }
  return if (bytes.size == NOISE_PSK_LENGTH) bytes else null
}

object NoiseEncryptionSettings {
  private const val KEY_ENABLED = "noise_encryption_enabled"
  private const val KEY_PSK = "noise_psk_b64" // stored via setSecureSecret() — see secure_storage.kt

  fun isEnabled(context: Context): Boolean = getFlag(context, KEY_ENABLED, false)
  fun setEnabled(context: Context, enabled: Boolean) = setFlag(context, KEY_ENABLED, enabled)

  fun getPskBase64(context: Context): String? = getSecureSecret(context, KEY_PSK)
  fun setPskBase64(context: Context, base64: String) = setSecureSecret(context, KEY_PSK, base64)

  // Enabled AND a validly-shaped (32 raw bytes once base64-decoded) key is actually
  // configured — esphome.kt gates every Noise handshake attempt on this, rather than ever
  // handshaking with a missing/malformed key. Deliberately null (not an exception) for
  // "not ready" — that's an expected, common state (freshly enabled with no key typed yet
  // is exactly as "not ready" as never having touched the setting at all).
  fun getReadyPsk(context: Context): ByteArray? {
    if (!isEnabled(context)) return null
    return getPskBase64(context)?.let(::decodeNoisePskOrNull)
  }
}

// ==================== Wire framing (pure, tested — see NoiseFramingTest.kt) ====================

// 1-byte indicator + 2-byte big-endian length + content — every Noise-mode frame, handshake
// or data, is wrapped in this. Verified against aioesphomeapi's
// `header = bytes((0x01, (frame_len >> 8) & 0xFF, frame_len & 0xFF))` and
// api_frame_helper_noise.cpp's matching read side.
internal fun noiseTransportFrame(indicator: Int, content: ByteArray): ByteArray {
  val frame = ByteArray(3 + content.size)
  frame[0] = indicator.toByte()
  frame[1] = ((content.size shr 8) and 0xFF).toByte()
  frame[2] = (content.size and 0xFF).toByte()
  System.arraycopy(content, 0, frame, 3, content.size)
  return frame
}

// ServerHello's content — sent unencrypted, immediately after reading ClientHello, before
// any Noise handshake state exists. Verified against api_frame_helper_noise.cpp's
// state_action_server_hello_(): a fixed 0x01 "chosen protocol" byte (this server never
// negotiates anything else), then the device name, then its MAC address, each
// null-terminated.
internal fun noiseServerHelloPayload(name: String, mac: String): ByteArray {
  val nameBytes = name.toByteArray(Charsets.UTF_8)
  val macBytes = mac.toByteArray(Charsets.UTF_8)
  val payload = ByteArray(1 + nameBytes.size + 1 + macBytes.size + 1)
  payload[0] = NOISE_CHOSEN_PROTO
  System.arraycopy(nameBytes, 0, payload, 1, nameBytes.size)
  System.arraycopy(macBytes, 0, payload, 2 + nameBytes.size, macBytes.size)
  return payload
}

// The Noise handshake's prologue — cryptographically binds the plaintext ClientHello
// exchange to the handshake, so a MITM tampering with it causes the handshake to fail
// rather than silently proceeding with a spoofed hello. Verified against
// api_frame_helper_noise.cpp: the constant "NoiseAPIInit" followed by a 2-byte big-endian
// length and the raw bytes of the ClientHello frame's CONTENT ONLY (not its own 3-byte
// transport header, and the ServerHello is NOT part of the prologue at all).
internal fun noisePrologue(clientHelloContent: ByteArray): ByteArray {
  val prefix = NOISE_PROLOGUE_PREFIX.toByteArray(Charsets.US_ASCII)
  val prologue = ByteArray(prefix.size + 2 + clientHelloContent.size)
  System.arraycopy(prefix, 0, prologue, 0, prefix.size)
  prologue[prefix.size] = ((clientHelloContent.size shr 8) and 0xFF).toByte()
  prologue[prefix.size + 1] = (clientHelloContent.size and 0xFF).toByte()
  System.arraycopy(clientHelloContent, 0, prologue, prefix.size + 2, clientHelloContent.size)
  return prologue
}

// ==================== Socket-level I/O ====================

private fun recvExactFrom(input: java.io.InputStream, count: Int): ByteArray {
  val data = ByteArray(count)
  var read = 0
  while (read < count) {
    val n = input.read(data, read, count - read)
    if (n < 0) throw IOException("closed")
    read += n
  }
  return data
}

// Reads one already-framed Noise transport frame (indicator + 2-byte length + content).
// internal (not private): NoiseHandshakeRoundTripTest.kt drives a real client-role Noise
// handshake over a loopback socket and needs this to read the server's ServerHello/
// handshake-message frames the same way the real client-side implementation would.
internal fun readNoiseTransportFrame(input: java.io.InputStream): Pair<Int, ByteArray> {
  val header = recvExactFrom(input, 3)
  val indicator = header[0].toInt() and 0xFF
  val length = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
  val content = if (length > 0) recvExactFrom(input, length) else ByteArray(0)
  return indicator to content
}

// Reads a ClientHello frame's remaining bytes — its indicator byte (FRAME_INDICATOR_NOISE)
// is assumed to already have been consumed by the caller (esphome.kt peeks it to decide
// plaintext vs. Noise mode in the first place, and a Socket InputStream can't be un-read).
private fun readClientHelloContent(input: java.io.InputStream): ByteArray {
  val lengthBytes = recvExactFrom(input, 2)
  val length = ((lengthBytes[0].toInt() and 0xFF) shl 8) or (lengthBytes[1].toInt() and 0xFF)
  return if (length > 0) recvExactFrom(input, length) else ByteArray(0)
}

private fun sendReject(output: java.io.OutputStream, reason: String) {
  try {
    val reasonBytes = reason.toByteArray(Charsets.UTF_8)
    val payload = ByteArray(1 + reasonBytes.size)
    payload[0] = HANDSHAKE_STATUS_REJECTED
    System.arraycopy(reasonBytes, 0, payload, 1, reasonBytes.size)
    output.write(noiseTransportFrame(FRAME_INDICATOR_NOISE, payload))
  } catch (e: Exception) { /* best effort — the connection is being torn down regardless */ }
}

// Rejects a Noise connection attempt outright, without ever running a handshake — used when
// Noise is disabled or has no valid key configured, so this device never attempts a
// handshake with a missing/garbage key. Reads and discards the ClientHello frame first so
// the socket is left in a clean state before the caller closes it.
fun rejectNoiseConnection(conn: Socket, reason: String) {
  try { readClientHelloContent(conn.getInputStream()) } catch (e: Exception) { /* already going to close */ }
  sendReject(conn.getOutputStream(), reason)
}

// Runs the full server side of the Noise handshake on `conn` (ClientHello already detected
// by the caller via its leading indicator byte, but not yet otherwise read) and returns the
// resulting CipherStatePair once split() succeeds. Throws NoiseHandshakeException — never
// partially completes silently — on a malformed frame or a handshake/MAC failure; the caller
// (esphome.kt) closes the connection when this throws.
//
// NNpsk0 is a 2-message handshake with the client (initiator) always sending message 1
// first: this server only ever reads once then writes once before reaching SPLIT.
fun performNoiseServerHandshake(conn: Socket, psk: ByteArray, name: String, mac: String): CipherStatePair {
  val input = conn.getInputStream()
  val output = conn.getOutputStream()

  val clientHelloContent = readClientHelloContent(input)
  val prologue = noisePrologue(clientHelloContent)

  output.write(noiseTransportFrame(FRAME_INDICATOR_NOISE, noiseServerHelloPayload(name, mac)))

  val handshake = try {
    HandshakeState(NOISE_PROTOCOL_NAME, HandshakeState.RESPONDER)
  } catch (e: Exception) {
    throw NoiseHandshakeException("could not initialize Noise handshake: ${e.message}")
  }
  handshake.setPrologue(prologue, 0, prologue.size)
  handshake.setPreSharedKey(psk, 0, psk.size)
  handshake.start()

  while (handshake.getAction() != HandshakeState.SPLIT) {
    when (handshake.getAction()) {
      HandshakeState.READ_MESSAGE -> {
        val (indicator, content) = readNoiseTransportFrame(input)
        if (indicator != FRAME_INDICATOR_NOISE) throw NoiseHandshakeException("expected a Noise frame during handshake")
        val payloadOut = ByteArray(content.size)
        try {
          handshake.readMessage(content, 0, content.size, payloadOut, 0)
        } catch (e: Exception) {
          sendReject(output, "Handshake MAC failure")
          throw NoiseHandshakeException("handshake read failed (likely wrong PSK): ${e.message}")
        }
      }
      HandshakeState.WRITE_MESSAGE -> {
        // NNpsk0's message 2 ("e, ee") is well under 128 bytes; 256 is a generous margin.
        val buffer = ByteArray(256)
        val len = handshake.writeMessage(buffer, 1, ByteArray(0), 0, 0)
        buffer[0] = HANDSHAKE_STATUS_OK
        output.write(noiseTransportFrame(FRAME_INDICATOR_NOISE, buffer.copyOf(len + 1)))
      }
      else -> throw NoiseHandshakeException("unexpected handshake state ${handshake.getAction()}")
    }
  }
  return handshake.split()
}

// ==================== Post-handshake data framing ====================

// Verified against aioesphomeapi's _frame_helper/noise.py: `data_header = bytes(((type_ >>
// 8) & 0xFF, type_ & 0xFF, (data_len >> 8) & 0xFF, data_len & 0xFF)); frame =
// encrypt_cipher.encrypt(data_header + data)` — decrypting a data-phase Noise frame's
// ciphertext yields that same 4-byte [type_hi][type_lo][len_hi][len_lo] header followed by
// the actual protobuf payload. No associated data (AD) is used, matching the plain
// `.encrypt(...)` call with nothing else passed on the Python side.
fun readNoiseDataMessage(conn: Socket, receiver: CipherState): Pair<Int, ByteArray> {
  val (indicator, ciphertext) = readNoiseTransportFrame(conn.getInputStream())
  if (indicator != FRAME_INDICATOR_NOISE) throw IOException("unexpected frame indicator $indicator in Noise mode")
  val plaintext = ByteArray(ciphertext.size) // upper bound — real length is ciphertext.size minus the AEAD tag
  val n = try {
    receiver.decryptWithAd(ByteArray(0), ciphertext, 0, plaintext, 0, ciphertext.size)
  } catch (e: Exception) { throw IOException("Noise decrypt failed: ${e.message}") }
  if (n < 4) throw IOException("Noise data frame too short ($n bytes)")
  val msgType = ((plaintext[0].toInt() and 0xFF) shl 8) or (plaintext[1].toInt() and 0xFF)
  val dataLen = ((plaintext[2].toInt() and 0xFF) shl 8) or (plaintext[3].toInt() and 0xFF)
  if (4 + dataLen > n) throw IOException("Noise data frame length field out of range")
  return msgType to plaintext.copyOfRange(4, 4 + dataLen)
}

fun sendNoiseDataMessage(conn: Socket, sender: CipherState, msgType: Int, payload: ByteArray) {
  val header = byteArrayOf(
      ((msgType shr 8) and 0xFF).toByte(), (msgType and 0xFF).toByte(),
      ((payload.size shr 8) and 0xFF).toByte(), (payload.size and 0xFF).toByte(),
  )
  val plaintext = header + payload
  val ciphertext = ByteArray(plaintext.size + sender.getMACLength())
  val n = sender.encryptWithAd(ByteArray(0), plaintext, 0, ciphertext, 0, plaintext.size)
  conn.getOutputStream().write(noiseTransportFrame(FRAME_INDICATOR_NOISE, ciphertext.copyOf(n)))
}
