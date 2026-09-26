package com.optionslab.app.data

import android.content.Context
import android.util.Base64
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * An encrypted backup of the app's own data, to a file the owner chooses, and
 * its restore - so a reinstall (or a new phone) does not lose everything.
 *
 * In it: settings, strategies, the ORB arms and forward test, the paper account,
 * the paper-ticket ledger, alarms, protections, the journal and the Zerodha
 * trade history. NEVER in it: the Zerodha API key, secret or session, the PIN,
 * the pinned certificates or the biometric key - after a restore Zerodha is
 * linked again from scratch.
 *
 * The file is sealed with a key stretched from a backup passphrase the owner chooses
 * (at least [MIN_PASSPHRASE] characters; PBKDF2-SHA256, 310,000 rounds, random salt)
 * under AES-256-GCM: without the passphrase it is noise, and any change to it is detected.
 * A 6-digit PIN is not enough for a file that can be copied and guessed at offline.
 *
 * Older files (IRABK1) were sealed with the app PIN (200,000 rounds); they still open
 * with that PIN, and nothing new is ever sealed that way.
 */
object Backup {
    private val MAGIC_PIN = "IRABK1".toByteArray()        // v1: sealed with the app PIN (open only)
    private val MAGIC = "IRABK2".toByteArray()            // v2: sealed with the backup passphrase
    private const val ROUNDS_PIN = 200_000
    private const val ROUNDS = 310_000
    const val MIN_PASSPHRASE = 10

    /** (directory, name): f = files, n = no-backup files. */
    // Not carried: protections (they name this phone's live Zerodha orders) and the holiday list
    // (fetched from NSE; a crafted one could mark every day closed and stop the market watch).
    private val FILES = listOf("f" to "strategies.vault", "f" to "orb.vault", "f" to "paper.vault", "f" to "pine.vault",
        "n" to "ledger.vault", "n" to "alarms.vault", "n" to "live_trades.vault", "n" to "journal.vault")

    /** Preferences that stay on this phone only. */
    // Also never restored: trading mode and risk limits (k.), security switches (sec.) and the idle lock (lock.):
    // a file must not be able to switch on live trading, raise limits or weaken the locks.
    private val PRIVATE = listOf("kite.", "draft.kite", "pin.", "tls.", "ol.vault", "hb.", "sq.", "report.", "k.", "sec.", "lock.", "intent.", "relay.", "breaker.")
    const val DISARM = "restore.disarm"

    private fun file(ctx: Context, dir: String, name: String) = File(if (dir == "f") ctx.filesDir else ctx.noBackupFilesDir, name)

    private fun key(secret: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(secret, salt, rounds, 256)
        try {
            val raw = f.generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES").also { raw.fill(0) }           // SecretKeySpec keeps its own copy
        } finally { spec.clearPassword() }
    }

    /**
     * A rough strength hint for a passphrase: 0 too short, 1 weak, 2 fair, 3 strong.
     * Estimates the search space from length and kinds of characters; repeats count less.
     */
    fun strength(pass: CharArray): Int {
        if (pass.size < MIN_PASSPHRASE) return 0
        var pool = 0
        if (pass.any { it in 'a'..'z' }) pool += 26
        if (pass.any { it in 'A'..'Z' }) pool += 26
        if (pass.any { it in '0'..'9' }) pool += 10
        if (pass.any { !it.isLetterOrDigit() }) pool += 33
        if (pass.any { it.isLetter() && it !in 'a'..'z' && it !in 'A'..'Z' }) pool += 26
        val distinct = pass.toSet().size
        val bits = minOf(pass.size, distinct * 2) * (Math.log(maxOf(pool, 2).toDouble()) / Math.log(2.0))
        return when { bits < 50 -> 1; bits < 72 -> 2; else -> 3 }
    }

    /** 1 (PIN-sealed, old) or 2 (passphrase-sealed); throws when the bytes are not a backup. */
    fun version(bytes: ByteArray): Int {
        if (bytes.size < MAGIC.size + 28) throw IllegalArgumentException("This is not an IraAlgo backup.")
        val head = bytes.copyOfRange(0, MAGIC.size)
        return when {
            head.contentEquals(MAGIC) -> 2
            head.contentEquals(MAGIC_PIN) -> 1
            else -> throw IllegalArgumentException("This is not an IraAlgo backup.")
        }
    }

    /** Make a backup sealed with [passphrase] (the caller wipes it). Reads on IO, derives the key on Default. */
    suspend fun create(ctx: Context, passphrase: CharArray): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE) { "The backup passphrase needs at least $MIN_PASSPHRASE characters." }
        val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { snapshot(ctx) }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val rnd = SecureRandom()
                val salt = ByteArray(16).also(rnd::nextBytes)
                val iv = ByteArray(12).also(rnd::nextBytes)
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ROUNDS), GCMParameterSpec(128, iv)) }
                c.updateAAD(MAGIC)
                MAGIC + salt + iv + c.doFinal(body)
            } finally { body.fill(0) }
        }
    }

    private fun snapshot(ctx: Context): ByteArray {
        val files = JSONObject()
        for ((dir, name) in FILES) {
            val f = file(ctx, dir, name)
            if (!f.exists()) continue
            val bytes = if (name.endsWith(".vault")) Vault.readFileSteady(f) else f.readBytes()
            if (bytes != null) files.put("$dir/$name", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        val prefs = JSONObject()
        SecurePrefs.snapshot().forEach { (k, v) -> if (PRIVATE.none { k.startsWith(it) } && v != null) prefs.put(k, v) }
        return JSONObject().put("v", 1).put("at", System.currentTimeMillis()).put("prefs", prefs).put("files", files)
            .toString().toByteArray(Charsets.UTF_8)
    }

    class WrongPin(passphrase: Boolean) : Exception(
        if (passphrase) "That passphrase does not open this backup, or the file was changed."
        else "That PIN does not open this backup, or the file was changed.")

    data class Contents(val createdAt: Long, val files: Int, val prefs: Int, internal val json: JSONObject)

    /**
     * Open a backup with its passphrase (v2) or, for an old v1 file, the PIN it was made
     * with; nothing is written yet. Slow on purpose: call it off the main thread.
     */
    fun open(bytes: ByteArray, secret: CharArray): Contents {
        val v = version(bytes)
        val magic = if (v == 2) MAGIC else MAGIC_PIN
        val salt = bytes.copyOfRange(magic.size, magic.size + 16)
        val iv = bytes.copyOfRange(magic.size + 16, magic.size + 28)
        val body = try {
            Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(secret, salt, if (v == 2) ROUNDS else ROUNDS_PIN), GCMParameterSpec(128, iv)); updateAAD(magic) }
                .doFinal(bytes.copyOfRange(magic.size + 28, bytes.size))
        } catch (e: javax.crypto.AEADBadTagException) { throw WrongPin(v == 2) }
        val o = JSONObject(String(body, Charsets.UTF_8)).also { body.fill(0) }
        return Contents(o.optLong("at"), o.getJSONObject("files").length(), o.getJSONObject("prefs").length(), o)
    }

    /**
     * Replace this phone's data with the backup's. Zerodha credentials, the PIN and the
     * pinned certificates on this phone are kept as they are. The app must restart after.
     */
    fun restore(ctx: Context, c: Contents) {
        val files = c.json.getJSONObject("files")
        for ((dir, name) in FILES) {
            val f = file(ctx, dir, name)
            val b64 = files.optString("$dir/$name").ifEmpty { null }
            if (b64 == null) { f.delete(); continue }
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            if (name.endsWith(".vault")) Vault.writeFile(f, bytes) else f.writeBytes(bytes)
        }
        val prefs = c.json.getJSONObject("prefs")
        val values = HashMap<String, Any?>()
        prefs.keys().forEach { k -> if (PRIVATE.none { k.startsWith(it) }) values[k] = prefs.get(k) }
        // Every restored strategy and ORB arm starts disarmed, paper only, on the next start.
        values[DISARM] = true
        SecurePrefs.putAll(values)
    }
}
