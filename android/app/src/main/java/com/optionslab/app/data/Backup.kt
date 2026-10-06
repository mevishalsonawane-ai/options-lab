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
 * (at least [MIN_PASSPHRASE] characters and not rated weak by [strength]; PBKDF2-SHA256,
 * 600,000 rounds - OWASP's figure for PBKDF2-SHA256 - random salt) under AES-256-GCM:
 * without the passphrase it is noise, and any change to it is detected.
 * A 6-digit PIN is not enough for a file that can be copied and guessed at offline.
 *
 * Formats (the round count is fixed by the header, never read from the file, so a crafted
 * file cannot choose it): IRABK3 = passphrase, 600,000 rounds (everything made now);
 * IRABK2 = passphrase, 310,000 rounds (open only); IRABK1 = the app PIN, 200,000 rounds
 * (open only - after restoring one, the owner is told to change the PIN, see [Restored]).
 */
object Backup {
    private val MAGIC_PIN = "IRABK1".toByteArray()        // v1: sealed with the app PIN (open only)
    private val MAGIC_V2 = "IRABK2".toByteArray()         // v2: backup passphrase, 310k rounds (open only)
    private val MAGIC = "IRABK3".toByteArray()            // v3: backup passphrase, 600k rounds (made now)
    private const val ROUNDS_PIN = 200_000
    private const val ROUNDS_V2 = 310_000
    private const val ROUNDS = 600_000
    const val MIN_PASSPHRASE = 10

    /** (directory, name): f = files, n = no-backup files. */
    // Not carried: protections (they name this phone's live Zerodha orders) and the holiday list
    // (fetched from NSE; a crafted one could mark every day closed and stop the market watch).
    private val FILES = listOf("f" to "strategies.vault", "f" to "orb.vault", "f" to "paper.vault", "f" to "pine.vault",
        "n" to "ledger.vault", "n" to "alarms.vault", "n" to "live_trades.vault", "n" to "journal.vault",
        // What Ira / Jarvis learned (the pattern book; proposals, review journal and conversation).
        "n" to "ira-book.vault", "n" to "ira-state.vault")

    /**
     * Preferences that stay on this phone only: never written to a backup, never taken from one - the PIN, Live mode and
     * order limits, the risk guard, the locks, AI trades going live, Jarvis's automations and Solo, timed commands, Google
     * speech and the limits' undo history. The list and its rule are [com.optionslab.ira.Upkeep.PRIVATE] (tested in
     * ira-core): a new setting of that kind needs its prefix added there.
     */
    private fun carried(key: String): Boolean = com.optionslab.ira.Upkeep.carried(key)
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

    /**
     * 1 (PIN-sealed, old) or 2 (passphrase-sealed, whatever its round count); throws when the
     * bytes are not a backup. (The screen only needs to know which secret to ask for.)
     */
    fun version(bytes: ByteArray): Int = if (format(bytes) == 1) 1 else 2

    /** The exact format: 1, 2 or 3 (see the class comment). */
    private fun format(bytes: ByteArray): Int {
        if (bytes.size < MAGIC.size + 28) throw IllegalArgumentException("This is not an IraAlgo backup.")
        val head = bytes.copyOfRange(0, MAGIC.size)
        return when {
            head.contentEquals(MAGIC) -> 3
            head.contentEquals(MAGIC_V2) -> 2
            head.contentEquals(MAGIC_PIN) -> 1
            else -> throw IllegalArgumentException("This is not an IraAlgo backup.")
        }
    }

    /** Make a backup sealed with [passphrase] (the caller wipes it). Reads on IO, derives the key on Default. */
    suspend fun create(ctx: Context, passphrase: CharArray): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE) { "The backup passphrase needs at least $MIN_PASSPHRASE characters." }
        require(strength(passphrase) >= 2) { "That passphrase is too easy to guess. Make it longer, or mix in capitals, digits or symbols." }
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
        }.also { runCatching { SecurePrefs.put("backup.last", Market.today().toString()) } }   // for the backup reminder
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
        SecurePrefs.snapshot().forEach { (k, v) -> if (carried(k) && v != null) prefs.put(k, v) }
        return JSONObject().put("v", 1).put("at", System.currentTimeMillis()).put("prefs", prefs).put("files", files)
            .toString().toByteArray(Charsets.UTF_8)
    }

    class WrongPin(passphrase: Boolean) : Exception(
        if (passphrase) "That passphrase does not open this backup, or the file was changed."
        else "That PIN does not open this backup, or the file was changed.")

    /** [pinSealed]: an old v1 file, opened with an app PIN (see [Restored.changePin]). */
    data class Contents(val createdAt: Long, val files: Int, val prefs: Int, internal val json: JSONObject, val pinSealed: Boolean = false)

    /**
     * What [restore] did. [changePin]: the file was an old PIN-sealed (v1) backup. Anyone holding
     * a copy of that file can guess its 6-digit PIN offline - and it may well be this phone's PIN -
     * so the owner should choose a new app PIN now.
     */
    data class Restored(val changePin: Boolean)

    /**
     * Open a backup with its passphrase (v2) or, for an old v1 file, the PIN it was made
     * with; nothing is written yet. Slow on purpose: call it off the main thread.
     */
    fun open(bytes: ByteArray, secret: CharArray): Contents {
        val v = format(bytes)
        val magic = when (v) { 3 -> MAGIC; 2 -> MAGIC_V2; else -> MAGIC_PIN }
        val rounds = when (v) { 3 -> ROUNDS; 2 -> ROUNDS_V2; else -> ROUNDS_PIN }
        val salt = bytes.copyOfRange(magic.size, magic.size + 16)
        val iv = bytes.copyOfRange(magic.size + 16, magic.size + 28)
        val body = try {
            Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(secret, salt, rounds), GCMParameterSpec(128, iv)); updateAAD(magic) }
                .doFinal(bytes.copyOfRange(magic.size + 28, bytes.size))
        } catch (e: javax.crypto.AEADBadTagException) { throw WrongPin(v != 1) }
        val o = JSONObject(String(body, Charsets.UTF_8)).also { body.fill(0) }
        return Contents(o.optLong("at"), o.getJSONObject("files").length(), o.getJSONObject("prefs").length(), o, pinSealed = v == 1)
    }

    /**
     * Replace this phone's data with the backup's. Zerodha credentials, the PIN and the
     * pinned certificates on this phone are kept as they are. The app must restart after.
     */
    fun restore(ctx: Context, c: Contents): Restored {
        // FIRST, before any file is touched: every restored strategy, ORB arm and Pine script starts
        // disarmed, paper only, on the next start (IraAlgoApp). Should the restore stop half-way,
        // the flag is already there.
        SecurePrefs.put(DISARM, true)
        if (SecurePrefs.unreadable) throw IllegalStateException("The settings vault is unreadable; nothing was restored.")
        val files = c.json.getJSONObject("files")
        // Liquidity 15+5's size in this phone's book now (before it is replaced): a restore never raises it ([liquidityLots]).
        val lotsHere = currentLiquidityLots(ctx)
        for ((dir, name) in FILES) {
            val f = file(ctx, dir, name)
            val b64 = files.optString("$dir/$name").ifEmpty { null }
            // An older backup without Jarvis's learning leaves the phone's own in place.
            if (b64 == null) { if (!name.startsWith("ira-")) f.delete(); continue }
            val bytes = disarmed(name, Base64.decode(b64, Base64.NO_WRAP), lotsHere)
            try {
                if (name.endsWith(".vault")) Vault.writeFile(f, bytes) else f.writeBytes(bytes)
            } finally { bytes.fill(0) }
        }
        val prefs = c.json.getJSONObject("prefs")
        val values = HashMap<String, Any?>()
        prefs.keys().forEach { k -> if (carried(k)) values[k] = prefs.get(k) }
        values[DISARM] = true
        SecurePrefs.putAll(values)
        return Restored(changePin = c.pinSealed)
    }

    /**
     * The file's contents with everything that could trade by itself switched off, in the
     * form each store writes it (Strategies, OrbArms, PineScripts). Belt and braces with
     * [DISARM]: if a file is not in the expected shape it is kept as it is and the flag alone
     * disarms it at the next start.
     */
    /** Liquidity 15+5's size in this phone's arms' book (null: no book, or none saved, or it cannot be read). */
    private fun currentLiquidityLots(ctx: Context): Int? = runCatching {
        val o = JSONObject(String(Vault.readFileSteady(file(ctx, "f", "orb.vault")) ?: return@runCatching null, Charsets.UTF_8))
        if (o.has("liqLots")) o.optInt("liqLots") else null
    }.getOrNull()

    /**
     * Liquidity 15+5's size in a restored arms' book [o]: never above [lotsHere], what this phone's book has (Boss's 06 Oct
     * rule: more lots only on his yes, as arming only on his switch). A higher size in the backup is kept aside
     * ("liqLotsAsk"): the row says so, and it applies only when Boss chooses it.
     */
    internal fun liquidityLots(o: JSONObject, lotsHere: Int?): JSONObject {
        val (kept, ask) = com.optionslab.engine.orb.LiquidityLots.restored(lotsHere, if (o.has("liqLots")) o.optInt("liqLots") else null)
        o.put("liqLots", kept)
        if (ask != null) o.put("liqLotsAsk", ask) else o.remove("liqLotsAsk")
        return o
    }

    private fun disarmed(name: String, bytes: ByteArray, lotsHere: Int? = null): ByteArray = runCatching {
        val text = String(bytes, Charsets.UTF_8)
        val out: String = when (name) {
            // Strategies.save: defs are StrategyCodec strings; runs / autoApprove / pending as in Strategies.disarmAll.
            "strategies.vault" -> {
                val o = JSONObject(text)
                val defs = o.getJSONArray("defs")
                val clean = org.json.JSONArray()
                for (i in 0 until defs.length()) {
                    val d = com.optionslab.engine.strategy.StrategyCodec.decode(defs.getString(i))
                    clean.put(com.optionslab.engine.strategy.StrategyCodec.encode(
                        d.copy(liveEnabled = false, scheduler = d.scheduler?.copy(enabled = false))))
                }
                o.put("defs", clean).put("runs", JSONObject()).put("autoApprove", JSONObject()).put("pending", JSONObject()).toString()
            }
            // OrbArms.save: maps keyed by arm, as cleared by OrbArms.disarmAll.
            "orb.vault" -> liquidityLots(JSONObject(text).put("armed", JSONObject()).put("auto", JSONObject()).put("liveOk", JSONObject())
                .put("pending", JSONObject()), lotsHere).toString()
            // PineScripts.save: an array of scripts, each with an "auto" object whose "on" starts it trading.
            "pine.vault" -> {
                val a = org.json.JSONArray(text)
                for (i in 0 until a.length()) a.getJSONObject(i).optJSONObject("auto")?.put("on", false)
                a.toString()
            }
            else -> return@runCatching bytes
        }
        out.toByteArray(Charsets.UTF_8)
    }.getOrDefault(bytes)
}
