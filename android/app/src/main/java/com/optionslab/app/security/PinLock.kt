package com.optionslab.app.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The app PIN. Only a salted PBKDF2-SHA256 verifier is stored, inside the
 * vault, so the PIN itself exists nowhere on the device. Comparison is
 * constant-time. Failed attempts escalate a lockout that survives restarts,
 * and an optional wipe destroys the vault after too many.
 */
object PinLock {
    private const val ITERATIONS = 150_000
    private const val FREE_ATTEMPTS = 5
    const val WIPE_AFTER = 10
    const val MIN_LENGTH = 6
    /** New PINs are exactly this long, so the pad can unlock on the last digit. */
    const val LENGTH = 6

    private const val K_SALT = "pin.salt"
    private const val K_HASH = "pin.hash"
    private const val K_ITER = "pin.iter"
    private const val K_LEN = "pin.len"
    private const val K_FAILS = "pin.fails"
    private const val K_UNTIL = "pin.lockedUntilWall"
    private const val K_LOCK_SECS = "pin.lockSeconds"
    private const val K_LOCK_AT = "pin.lockAtElapsed"

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val attemptsLeftBeforeLockout: Int) : Result
        data class LockedOut(val secondsLeft: Long) : Result
        data object Wiped : Result
    }

    /** An unreadable settings vault counts as set: the fallback must never be "choose a new PIN". */
    val isSet: Boolean get() = SecurePrefs.getString(K_HASH) != null || SecurePrefs.unreadable

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun setPin(pin: CharArray) {
        require(pin.size == LENGTH) { "The PIN must be $LENGTH digits" }
        require(pin.distinct().size > 1) { "a PIN of one repeated digit is too easy to guess" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS)
        SecurePrefs.putAll(mapOf(K_SALT to hex(salt), K_HASH to hex(hash), K_ITER to ITERATIONS, K_LEN to pin.size, K_FAILS to 0, K_UNTIL to null, K_LOCK_SECS to 0L))
        pin.fill('\u0000')
    }

    /**
     * Measured on the monotonic clock, so moving the phone's date forward does
     * not end a lockout. A reboot resets that clock; the lockout then starts
     * again in full rather than being forgotten.
     */
    fun lockoutSecondsLeft(): Long {
        val secs = SecurePrefs.getLong(K_LOCK_SECS, 0L)
        if (secs <= 0) return 0
        val now = android.os.SystemClock.elapsedRealtime()
        val at = SecurePrefs.getLong(K_LOCK_AT, 0L)
        if (now < at) { SecurePrefs.put(K_LOCK_AT, now); return secs }   // rebooted since
        val left = secs - (now - at) / 1000
        return if (left > 0) left else 0
    }

    /** The PIN's length, when known (older PINs learn it on their next unlock). */
    fun length(): Int? = SecurePrefs.getInt(K_LEN, 0).takeIf { it > 0 }

    fun verify(pin: CharArray, wipeOnExhaustion: Boolean): Result {
        val size = pin.size
        val wait = lockoutSecondsLeft()
        if (wait > 0) { pin.fill('\u0000'); return Result.LockedOut(wait) }
        val salt = SecurePrefs.getString(K_SALT)?.let(::unhex)
        val want = SecurePrefs.getString(K_HASH)?.let(::unhex)
        if (salt == null || want == null) { pin.fill('\u0000'); return Result.Wrong(0) }
        // PBKDF2 throws on an empty password; an empty PIN is simply wrong and costs no attempt.
        if (size == 0) return Result.Wrong(FREE_ATTEMPTS - SecurePrefs.getInt(K_FAILS, 0))
        val got = derive(pin, salt, SecurePrefs.getInt(K_ITER, ITERATIONS))
        pin.fill('\u0000')
        if (MessageDigest.isEqual(got, want)) {
            SecurePrefs.putAll(mapOf(K_FAILS to 0, K_UNTIL to null, K_LOCK_SECS to 0L, K_LEN to size))
            return Result.Ok
        }
        val fails = SecurePrefs.getInt(K_FAILS, 0) + 1
        if (wipeOnExhaustion && fails >= WIPE_AFTER) return Result.Wiped
        val updates = mutableMapOf<String, Any?>(K_FAILS to fails)
        if (fails >= FREE_ATTEMPTS) {
            // 30 s, 60 s, 120 s ... capped at an hour.
            val seconds = minOf(30L shl minOf(fails - FREE_ATTEMPTS, 7), 3600L)
            updates[K_LOCK_SECS] = seconds
            updates[K_LOCK_AT] = android.os.SystemClock.elapsedRealtime()
            SecurePrefs.putAll(updates)
            return Result.LockedOut(seconds)
        }
        SecurePrefs.putAll(updates)
        return Result.Wrong(FREE_ATTEMPTS - fails)
    }

    fun failures(): Int = SecurePrefs.getInt(K_FAILS, 0)
}
