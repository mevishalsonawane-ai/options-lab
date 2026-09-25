package com.optionslab.app.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A secret that only the owner's PIN can open. The Zerodha API secret is
 * needed once a day, at login, when the owner is present to type the PIN; so
 * it is sealed with a key derived from that PIN (PBKDF2, its own salt), inside
 * the vault. Code running in the app's process - on a rooted or hooked phone -
 * can still reach the vault, but not the PIN, and without the PIN the secret
 * stays sealed. (The day's access token cannot be protected this way: the
 * strategies' exits must be able to use it unattended.)
 */
object SecretBox {
    private const val ITERATIONS = 150_000

    private fun key(pin: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun seal(plain: String, pin: CharArray): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(pin, salt)) }
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "v1:${hex(salt)}:${hex(c.iv)}:${hex(ct)}"
    }

    /** The secret, or null when [pin] is not the one it was sealed with (GCM refuses it). */
    fun open(blob: String, pin: CharArray): String? = runCatching {
        val (v, salt, iv, ct) = blob.split(":")
        require(v == "v1")
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(pin, unhex(salt)), GCMParameterSpec(128, unhex(iv))) }
        String(c.doFinal(unhex(ct)), Charsets.UTF_8)
    }.getOrNull()
}
