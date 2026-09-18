package com.aesphome

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*

  Secure secret storage
    Used for the Noise PSK (noise.kt) — the one secret in this app sensitive enough to be
    worth more than settings.kt's plain SharedPreferences (already used for e.g. the MJPEG
    server's auth token). On API 23+, the value is AES-256-GCM encrypted with a key that
    never leaves the device's AndroidKeyStore before being written to SharedPreferences —
    the ciphertext alone is worthless without that key, which typically can't be extracted
    even with root on a device with hardware-backed keystore support.

    Why not just add androidx.security:security-crypto (Jetpack's EncryptedSharedPreferences)
    instead of this: that library's own minSdk is 23, and Gradle enforces a dependency's
    minSdk as a floor on the whole app's — adding it would silently raise this app's minSdk
    from 22 to 23, which the project's own constraints explicitly rule out ("don't raise
    minSdk without absolute necessity"). Implementing directly against AndroidKeyStore/JCA
    avoids that entirely, at the cost of writing the ~30 lines below by hand instead of
    depending on a library for them — a reasonable trade for one call site.

    Below API 23, AndroidKeyStore has no symmetric (AES) key support at all — only RSA,
    which would need a materially larger implementation (key wrapping instead of direct
    AEAD) for a single, largely obsolete API level given this app's own stated floor is
    Android 9 (see FAQ.md). This falls back to plain SharedPreferences there instead — not a
    regression, since that's the same protection every other setting in this app (including
    the MJPEG token) already has on every Android version.
*/

private const val KEYSTORE_ALIAS = "aesphome_secure_storage_key"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128

private fun getOrCreateKey(): SecretKey {
  val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

  val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
  generator.init(
      KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build()
  )
  return generator.generateKey()
}

// Persists `value` under `key`, encrypted when possible. The stored string is always
// prefixed with how it was stored ("enc:" or "plain:") so a later read never has to guess —
// important across an Android version change (e.g. a device upgraded past API 23 after a
// value was already stored in plain form).
fun setSecureSecret(context: Context, key: String, value: String) {
  val stored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    try {
      val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
      val iv = cipher.iv
      val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
      "enc:" + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    } catch (e: Exception) {
      "plain:$value" // Keystore unavailable for some reason — fail open to plain storage rather than losing the value
    }
  } else {
    "plain:$value"
  }
  setStringFlag(context, key, stored)
}

// Returns null if nothing has ever been stored under `key`, or if a stored ciphertext can no
// longer be decrypted (e.g. the Keystore key was invalidated by a factory-reset-adjacent
// event) — callers treat that the same as "never set", not as a crash.
fun getSecureSecret(context: Context, key: String): String? {
  val stored = getStringFlag(context, key, "")
  if (stored.isEmpty()) return null
  return when {
    stored.startsWith("plain:") -> stored.removePrefix("plain:")
    stored.startsWith("enc:") -> try {
      val parts = stored.removePrefix("enc:").split(":")
      if (parts.size != 2) return null
      val iv = Base64.decode(parts[0], Base64.NO_WRAP)
      val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
      val cipher = Cipher.getInstance(TRANSFORMATION).apply {
        init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
      }
      String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (e: Exception) { null }
    else -> null
  }
}
