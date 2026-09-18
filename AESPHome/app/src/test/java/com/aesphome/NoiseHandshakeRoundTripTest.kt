package com.aesphome

import com.southernstorm.noise.protocol.HandshakeState
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Drives a REAL Noise_NNpsk0_25519_ChaChaPoly_SHA256 handshake end to end over a real
// loopback TCP socket: this test's own client role (plain noise-java HandshakeState, driven
// directly here — not aioesphomeapi) against noise.kt's actual server-role implementation
// (performNoiseServerHandshake, readNoiseDataMessage, sendNoiseDataMessage — the exact same
// functions esphome.kt calls). This proves the server code is internally correct — a real
// X25519/ChaChaPoly handshake completes, and the two resulting CipherStatePairs can decrypt
// each other's data — and that this file's own understanding of ESPHome's framing (frame
// header, prologue, ServerHello, handshake-message status byte) is at least self-consistent.
//
// What this does NOT prove: byte-for-byte wire compatibility with the real aioesphomeapi
// client. That would need a real Home Assistant instance, not available while writing this
// — see noise.kt's file-level doc comment and docs/SECURITY.md.
class NoiseHandshakeRoundTripTest {

  @Test
  fun `client and server complete a handshake and exchange encrypted data both ways`() {
    val psk = ByteArray(NOISE_PSK_LENGTH) { it.toByte() }
    val serverName = "aesphome-test"
    val serverMac = "AA:BB:CC:DD:EE:FF"

    val serverSocket = ServerSocket(0)
    var serverError: Throwable? = null

    val serverThread = Thread {
      try {
        val conn = serverSocket.accept()
        val indicator = conn.getInputStream().read()
        if (indicator != FRAME_INDICATOR_NOISE) throw AssertionError("expected Noise indicator, got $indicator")
        val ciphers = performNoiseServerHandshake(conn, psk, serverName, serverMac)

        val (msgType, payload) = readNoiseDataMessage(conn, ciphers.getReceiver())
        if (msgType != 42 || !payload.contentEquals("ping".toByteArray())) {
          throw AssertionError("unexpected request: type=$msgType payload=${String(payload)}")
        }
        sendNoiseDataMessage(conn, ciphers.getSender(), 43, "pong".toByteArray())
      } catch (e: Throwable) {
        serverError = e
      }
    }
    serverThread.start()

    val clientSocket = Socket("127.0.0.1", serverSocket.localPort)
    val clientHelloContent = ByteArray(0) // matches aioesphomeapi's bare NOISE_HELLO
    clientSocket.getOutputStream().write(noiseTransportFrame(FRAME_INDICATOR_NOISE, clientHelloContent))

    val clientHandshake = HandshakeState(
        "NoisePSK_NN_25519_ChaChaPoly_SHA256", HandshakeState.INITIATOR
    )
    val prologue = noisePrologue(clientHelloContent)
    clientHandshake.setPrologue(prologue, 0, prologue.size)
    clientHandshake.setPreSharedKey(psk, 0, psk.size)
    clientHandshake.start()

    // ServerHello — sent in the clear, before any Noise handshake message.
    val (helloIndicator, helloContent) = readNoiseTransportFrame(clientSocket.getInputStream())
    assertEquals(FRAME_INDICATOR_NOISE, helloIndicator)
    assertEquals(0x01, helloContent[0].toInt()) // chosen protocol
    val expectedHello = noiseServerHelloPayload(serverName, serverMac)
    assertArrayEquals(expectedHello, helloContent)

    while (clientHandshake.getAction() != HandshakeState.SPLIT) {
      when (clientHandshake.getAction()) {
        HandshakeState.WRITE_MESSAGE -> {
          val buffer = ByteArray(256)
          val len = clientHandshake.writeMessage(buffer, 0, ByteArray(0), 0, 0)
          clientSocket.getOutputStream().write(noiseTransportFrame(FRAME_INDICATOR_NOISE, buffer.copyOf(len)))
        }
        HandshakeState.READ_MESSAGE -> {
          val (indicator, content) = readNoiseTransportFrame(clientSocket.getInputStream())
          assertEquals(FRAME_INDICATOR_NOISE, indicator)
          assertEquals(0, content[0].toInt()) // HANDSHAKE_STATUS_OK — server accepted the PSK
          val payloadOut = ByteArray(content.size)
          clientHandshake.readMessage(content, 1, content.size - 1, payloadOut, 0)
        }
        else -> throw AssertionError("unexpected client handshake action ${clientHandshake.getAction()}")
      }
    }
    val clientCiphers = clientHandshake.split()

    sendNoiseDataMessage(clientSocket, clientCiphers.getSender(), 42, "ping".toByteArray())
    val (responseType, responsePayload) = readNoiseDataMessage(clientSocket, clientCiphers.getReceiver())

    serverThread.join(5_000)
    assertNull("server-side handshake/exchange threw: $serverError", serverError)
    assertEquals(43, responseType)
    assertArrayEquals("pong".toByteArray(), responsePayload)

    clientSocket.close()
    serverSocket.close()
  }

  @Test
  fun `a client presenting the wrong PSK is rejected instead of completing a handshake`() {
    val serverPsk = ByteArray(NOISE_PSK_LENGTH) { it.toByte() }
    val wrongPsk = ByteArray(NOISE_PSK_LENGTH) { (it + 1).toByte() }

    val serverSocket = ServerSocket(0)
    var serverThrew = false
    val serverThread = Thread {
      try {
        val conn = serverSocket.accept()
        conn.getInputStream().read() // indicator byte
        performNoiseServerHandshake(conn, serverPsk, "s", "m")
      } catch (e: NoiseHandshakeException) {
        serverThrew = true
      } catch (e: Throwable) { /* any other failure also means it didn't silently succeed */ serverThrew = true }
    }
    serverThread.start()

    val clientSocket = Socket("127.0.0.1", serverSocket.localPort)
    val clientHelloContent = ByteArray(0)
    clientSocket.getOutputStream().write(noiseTransportFrame(FRAME_INDICATOR_NOISE, clientHelloContent))

    val clientHandshake = HandshakeState("NoisePSK_NN_25519_ChaChaPoly_SHA256", HandshakeState.INITIATOR)
    val prologue = noisePrologue(clientHelloContent)
    clientHandshake.setPrologue(prologue, 0, prologue.size)
    clientHandshake.setPreSharedKey(wrongPsk, 0, wrongPsk.size) // deliberately wrong
    clientHandshake.start()

    readNoiseTransportFrame(clientSocket.getInputStream()) // ServerHello — same regardless of PSK

    val buffer = ByteArray(256)
    val len = clientHandshake.writeMessage(buffer, 0, ByteArray(0), 0, 0)
    clientSocket.getOutputStream().write(noiseTransportFrame(FRAME_INDICATOR_NOISE, buffer.copyOf(len)))

    // The server must reject rather than proceed as if the PSK were correct.
    val (indicator, content) = readNoiseTransportFrame(clientSocket.getInputStream())
    assertEquals(FRAME_INDICATOR_NOISE, indicator)
    assertEquals("a mismatched PSK must produce a non-zero (reject) status byte, not a normal handshake message", 1, content[0].toInt())

    serverThread.join(5_000)
    assertEquals(true, serverThrew)

    clientSocket.close()
    serverSocket.close()
  }
}
