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
 * The file is sealed with a key stretched from the PIN (PBKDF2-SHA256, 200,000
 * rounds, random salt) under AES-256-GCM: without the PIN it is noise, and any
 * change to it is detected.
 */
object Backup {
    private val MAGIC = "IRABK1".toByteArray()
    private const val ROUNDS = 200_000

    /** (directory, name): f = files, n = no-backup files. */
    // Not carried: protections (they name this phone's live Zerodha orders) and the holiday list
    // (fetched from NSE; a crafted one could mark every day closed and stop the market watch).
    private val FILES = listOf("f" to "strategies.vault", "f" to "orb.vault", "f" to "paper.vault",
        "n" to "ledger.vault", "n" to "alarms.vault", "n" to "live_trades.vault", "n" to "journal.vault")

    /** Preferences that stay on this phone only. */
    // Also never restored: trading mode and risk limits (k.), security switches (sec.) and the idle lock (lock.):
    // a file must not be able to switch on live trading, raise limits or weaken the locks.
    private val PRIVATE = listOf("kite.", "draft.kite", "pin.", "tls.", "ol.vault", "hb.", "sq.", "report.", "k.", "sec.", "lock.", "intent.", "relay.")
    const val DISARM = "restore.disarm"

    private fun file(ctx: Context, dir: String, name: String) = File(if (dir == "f") ctx.filesDir else ctx.noBackupFilesDir, name)

    private fun key(pin: CharArray, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(pin, salt, ROUNDS, 256)).encoded, "AES")
    }

    fun create(ctx: Context, pin: CharArray): ByteArray {
        val files = JSONObject()
        for ((dir, name) in FILES) {
            val f = file(ctx, dir, name)
            if (!f.exists()) continue
            val bytes = if (name.endsWith(".vault")) Vault.readFileSteady(f) else f.readBytes()
            if (bytes != null) files.put("$dir/$name", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        val prefs = JSONObject()
        SecurePrefs.snapshot().forEach { (k, v) -> if (PRIVATE.none { k.startsWith(it) } && v != null) prefs.put(k, v) }
        val body = JSONObject().put("v", 1).put("at", System.currentTimeMillis()).put("prefs", prefs).put("files", files)
            .toString().toByteArray(Charsets.UTF_8)
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(pin, salt), GCMParameterSpec(128, iv)) }
        c.updateAAD(MAGIC)
        return MAGIC + salt + iv + c.doFinal(body)
    }

    class WrongPin : Exception("That PIN does not open this backup, or the file was changed.")

    data class Contents(val createdAt: Long, val files: Int, val prefs: Int, internal val json: JSONObject)

    /** Open a backup with the PIN it was made with; nothing is written yet. */
    fun open(bytes: ByteArray, pin: CharArray): Contents {
        if (bytes.size < MAGIC.size + 28 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IllegalArgumentException("This is not an IraAlgo backup.")
        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = bytes.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val body = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(pin, salt), GCMParameterSpec(128, iv)); updateAAD(MAGIC) }
                .doFinal(bytes.copyOfRange(MAGIC.size + 28, bytes.size))
        } catch (e: javax.crypto.AEADBadTagException) { throw WrongPin() }
        val o = JSONObject(String(body, Charsets.UTF_8))
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
