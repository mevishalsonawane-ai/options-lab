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
 *
 * v2 (everything sealed now): the PBKDF2 output is also run through an HMAC
 * whose key lives in the Android Keystore ([PinPepper]) and never leaves it. A
 * copy of the vault taken off the phone is then useless for guessing the PIN:
 * every guess needs this device's Keystore. v1 records (no pepper) still open;
 * [upgrade] re-seals one as v2.
 */
object SecretBox {
    private const val ITERATIONS = 150_000

    private fun stretch(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
    }

    private fun key(pin: CharArray, salt: ByteArray, peppered: Boolean): SecretKeySpec {
        val raw = stretch(pin, salt)
        val k = if (peppered) PinPepper.mix(PinPepper.BOX, raw).also { raw.fill(0) } else raw
        return SecretKeySpec(k, "AES").also { k.fill(0) }        // SecretKeySpec keeps its own copy
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun sealAs(plain: String, pin: CharArray, peppered: Boolean): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(pin, salt, peppered)) }
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "${if (peppered) "v2" else "v1"}:${hex(salt)}:${hex(c.iv)}:${hex(ct)}"
    }

    /**
     * Seal as v2 (PIN + this device's Keystore). Should the Keystore refuse the pepper
     * key, the secret is still sealed - as v1, PIN only, exactly as before - rather than
     * not saved; the next [upgrade] moves it to v2.
     */
    fun seal(plain: String, pin: CharArray): String =
        try { sealAs(plain, pin, peppered = true) } catch (_: Exception) { sealAs(plain, pin, peppered = false) }

    /** The secret, or null when [pin] is not the one it was sealed with (GCM refuses it). */
    fun open(blob: String, pin: CharArray): String? = runCatching {
        val (v, salt, iv, ct) = blob.split(":")
        require(v == "v1" || v == "v2")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(pin, unhex(salt), peppered = v == "v2"), GCMParameterSpec(128, unhex(iv))) }
        String(c.doFinal(unhex(ct)), Charsets.UTF_8)
    }.getOrNull()

    /** True when [blob] is already in the current (peppered) format. */
    fun isCurrent(blob: String): Boolean = blob.startsWith("v2:")

    /**
     * After [open] succeeded on an old v1 [blob] with [pin]: the same secret re-sealed as v2,
     * for the caller to store in its place. Null when there is nothing to do (already v2) or
     * the Keystore could not seal it now (keep the old blob; try again next time).
     */
    fun upgrade(blob: String, pin: CharArray, plain: String): String? =
        if (isCurrent(blob)) null else runCatching { sealAs(plain, pin, peppered = true) }.getOrNull()
}

/**
 * A non-exportable HMAC-SHA256 key in the Android Keystore, mixed into every
 * PIN-derived value ([SecretBox] v2, the [PinLock] v2 verifier), so a 6-digit
 * PIN cannot be brute-forced from a copy of the vault: each guess has to be
 * computed by this phone's Keystore. Each use is labelled, so a value from one
 * purpose is never valid for another.
 */
internal object PinPepper {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "ol.pin.pepper.v1"
    const val BOX = "secretbox"
    const val VERIFIER = "pin-verifier"

    private fun keyStore(): java.security.KeyStore = java.security.KeyStore.getInstance(STORE).apply { load(null) }

    /** The key, created on first use. A Keystore fault throws; it never yields a different key silently. */
    @Synchronized
    private fun key(): javax.crypto.SecretKey {
        (keyStore().getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_SIGN).build()
        return javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE)
            .run { init(spec); generateKey() }
    }

    /** HMAC-SHA256(pepper, label || 0 || [input]); retried a few times so a Keystore hiccup is not taken for a wrong PIN. */
    fun mix(label: String, input: ByteArray): ByteArray {
        var last: Exception? = null
        repeat(3) { i ->
            try {
                val mac = javax.crypto.Mac.getInstance("HmacSHA256")
                mac.init(key())
                mac.update(label.toByteArray(Charsets.UTF_8))
                mac.update(0.toByte())
                return mac.doFinal(input)
            } catch (e: Exception) { last = e; if (i < 2) Thread.sleep(100L * (i + 1)) }
        }
        throw last!!
    }

    /** Erase: every v2 sealed secret and verifier becomes useless with it. */
    fun destroy() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }
}
