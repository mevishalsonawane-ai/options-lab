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
    const val MIN_PASSPHRASE = com.optionslab.ira.Passphrase.MIN

    /** (directory, name): f = files, n = no-backup files. */
    // Not carried: protections (they name this phone's live Zerodha orders) and the holiday list
    // (fetched from NSE; a crafted one could mark every day closed and stop the market watch).
    private val FILES = listOf("f" to "strategies.vault", "f" to "orb.vault", "f" to "paper.vault", "f" to "pine.vault",
        "n" to "ledger.vault", "n" to "alarms.vault", "n" to "live_trades.vault", "n" to "journal.vault",
        // What Ira / Jarvis learned (the pattern book; proposals, review journal and conversation).
        "n" to "ira-book.vault", "n" to "ira-state.vault",
        // Jarvis's weekly reviews (their own file since they left the preferences: IraWeekly.FILE).
        "n" to WEEKLY_REVIEWS)
    private const val WEEKLY_REVIEWS = "weekly-reviews.vault"

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
    fun strength(pass: CharArray): Int = com.optionslab.ira.Passphrase.strength(pass)

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
        require(strength(passphrase) >= 2) { com.optionslab.ira.Passphrase.advice(passphrase)?.full ?: "That passphrase is too easy to guess." }
        val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { snapshot(ctx) }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val salt = ByteArray(16).also(SecureRandom()::nextBytes)
                seal(body, key(passphrase, salt, ROUNDS), salt)
            } finally { body.fill(0) }
        }.also { runCatching { SecurePrefs.put("backup.last", Market.today().toString()) } }   // for the backup reminder
    }

    private fun seal(body: ByteArray, k: SecretKeySpec, salt: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        c.updateAAD(MAGIC)
        return MAGIC + salt + iv + c.doFinal(body)
    }

    /**
     * The key [create] stretches from [passphrase] with [salt] (PBKDF2-SHA256, 600,000 rounds), as raw bytes: GitHub sync
     * (TODO G2, 13 Oct) keeps this key - never the passphrase - so its unattended evening backup can be sealed ([createSealed]).
     * Slow on purpose: off the main thread.
     */
    internal fun stretch(passphrase: CharArray, salt: ByteArray): ByteArray = key(passphrase, salt, ROUNDS).encoded

    /**
     * The same backup as [create], sealed with a key already stretched by [stretch] from the passphrase and [salt]. The file
     * is an ordinary IRABK3 backup: it opens with the passphrase like any other ([open]), and [restore] treats it the same
     * (everything comes back switched off). Reads on IO, seals on Default. Made is not yet off the phone: the backup reminder
     * rests only once GitHub has it ([markOffPhone], 13 Oct review).
     */
    suspend fun createSealed(ctx: Context, key: ByteArray, salt: ByteArray): ByteArray {
        require(key.size == 32 && salt.size == 16) { "The GitHub backup key is not set." }
        val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { snapshot(ctx) }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try { seal(body, SecretKeySpec(key, "AES"), salt) } finally { body.fill(0) }
        }
    }

    /** A backup is off the phone (GitHub sync uploaded it): the backup reminder rests. Never called for a failed upload. */
    fun markOffPhone() { runCatching { SecurePrefs.put("backup.last", Market.today().toString()) } }

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
     * so the owner should choose a new app PIN now. [notes] (22 Nov 2026, B6 review): what the restore kept from this phone or left
     * out of the backup, in Boss's words - also posted as a notification and kept in the diagnostics (the app closes at once).
     */
    data class Restored(val changePin: Boolean, val notes: List<String> = emptyList())

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
        // 22 Nov 2026 (B6 review): from here until the restart no new entry goes to Zerodha ([restoringRefusal], asked inside the entry
        // door): one sent in these seconds would be booked into a book the restore then replaces (or one frozen for it), and forgotten.
        // Exits always go. A restore refused, or failed before any file was frozen for it, lets entries go again; one that failed after
        // keeps them held (the books are frozen, and both callers close the app after a failure).
        restoring = true
        var done = false
        var frozen = false
        try {
            return restoreNow(ctx, c) { frozen = true }.also { done = true }
        } finally { if (!done && !frozen) restoring = false }
    }

    private fun restoreNow(ctx: Context, c: Contents, onFrozen: () -> Unit): Restored {
        // 20 Nov 2026 (audit, B5 item 2): never while a live position is open - the restore replaces the books that record it (the
        // ORB and Liquidity book, the strategies' runs, the paper trades a live copy follows, the Zerodha trade record). Refused
        // before anything is written (not even the disarm flag): this phone's data stays exactly as it is.
        liveHeld().takeIf { it.isNotEmpty() }?.let { throw LiveHeld(it) }
        // 20 Nov 2026 (B5 item 3, review P4): this phone's account-stop days, read before anything is written. Unreadable, the restore is
        // refused: they are never taken as empty, and never written over.
        val stopDaysHere = accountStopDaysHere() ?: throw AccountStopUnread()
        // 22 Nov 2026 (B6 review, item 2): this phone's arms' book, read before anything is written: its closed LIVE trades are kept in the
        // restored book ([orbRestored]) - Liquidity's live stop rule and the arms' live-copies account stop are worked out from them, so an
        // older backup must never erase a losing trade and read a stop already reached as "not reached". Unreadable: refused, as above.
        val orbHere = orbBookHere(ctx)
        // 22 Nov 2026 (B6 review): this phone's strategies' book too - its Stop for today (each side's) is kept when it is later than the
        // backup's ([strategiesRestored]): a restore never lifts today's stop. Unreadable: refused; none on this phone: nothing to keep.
        val strategiesHere = strategiesBookHere(ctx)
        // What the restore kept from this phone or left out of the backup, told to Boss (22 Nov 2026, B6 review).
        val notes = ArrayList<String>()
        // FIRST, before any file is touched: every restored strategy, ORB arm and Pine script starts
        // disarmed, paper only, on the next start (IraAlgoApp). Should the restore stop half-way,
        // the flag is already there.
        SecurePrefs.put(DISARM, true)
        if (SecurePrefs.unreadable) throw IllegalStateException("The settings vault is unreadable; nothing was restored.")
        val files = c.json.getJSONObject("files")
        // Liquidity 15+5's size in this phone's book now (before it is replaced): a restore never raises it ([liquidityLots]).
        val lotsHere = orbHere?.let { if (it.has("liqLots")) it.optInt("liqLots") else null }
        // 20 Oct, review: the live stores keep saving their old books until the restart; from here only this restore writes
        // the vault files it replaces ([Vault.freezeForRestore]), so none of them is written over in those seconds.
        Vault.freezeForRestore(FILES.filter { it.second.endsWith(".vault") }.map { (dir, name) -> file(ctx, dir, name) })
        onFrozen()
        // A restore that stops half-way (no restart follows) lets the stores save again, as before.
        try {
        for ((dir, name) in FILES) {
            val f = file(ctx, dir, name)
            val b64 = files.optString("$dir/$name").ifEmpty { null }
            // 22 Nov 2026 (B6 review, item 2): the arms' book with this phone's closed live trades kept - even when the backup has no book.
            val bytes = when (name) {
                ORB -> orbRestored(b64?.let { Base64.decode(it, Base64.NO_WRAP) }, lotsHere, orbHere, notes)
                // (With a stop made since the book was read - its save dropped by the freeze - from Strategies' memory.)
                STRATEGIES -> strategiesRestored(b64?.let { Base64.decode(it, Base64.NO_WRAP) }, withStopsInMemory(strategiesHere,
                    runCatching { Strategies.stopKeptHint() }.getOrNull(), runCatching { Strategies.mcxStopHint() }.getOrNull(),
                    runCatching { Strategies.mcxStopSellsHint() }.getOrDefault(true)), notes)
                else -> b64?.let { disarmed(name, Base64.decode(it, Base64.NO_WRAP)) }
            }
            // An older backup without Jarvis's learning leaves the phone's own in place. So with the weekly reviews: an
            // older backup has them under the old preferences key (IraWeekly.KEY), and IraWeekly.migrate merges the two.
            if (bytes == null) { if (!name.startsWith("ira-") && name != WEEKLY_REVIEWS) f.delete(); continue }
            try {
                if (name.endsWith(".vault")) Vault.writeRestored(f, bytes) else f.writeBytes(bytes)
            } finally { bytes.fill(0) }
        }
        val prefs = c.json.getJSONObject("prefs")
        val values = HashMap<String, Any?>()
        prefs.keys().forEach { k -> if (carried(k)) values[k] = prefs.get(k) }
        values[DISARM] = true
        // 20 Nov 2026 (audit, B5 item 3): the account stop rule's days - this phone's and the backup's together, never milder than this
        // phone's own ([com.optionslab.ira.AccountStop.restored]): an older backup no longer lowers its drawdown or month figure.
        if (values.containsKey(ACCOUNT_STOP_DAYS)) {
            val theirs = com.optionslab.ira.AccountStop.decode(values[ACCOUNT_STOP_DAYS] as? String)
            val kept = com.optionslab.ira.AccountStop.restored(stopDaysHere, theirs, runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() })
            // (Review P4: nothing to keep leaves this phone's value as it is - never a null written over it.)
            if (kept.isEmpty()) values.remove(ACCOUNT_STOP_DAYS) else values[ACCOUNT_STOP_DAYS] = com.optionslab.ira.AccountStop.encode(kept)
        }
        // 22 Nov 2026 (B6 review, item 1): the other carried settings that hold live entries back, merged with this phone's own so a restore
        // only ever tightens them ([mergeGuards]); the ones that must never come from a file are this phone's alone (Upkeep.PRIVATE).
        mergeGuards(values, notes)
        // 22 Nov 2026 (B6 review, item 9c): the cheaper strike's "switched on by the update" entry is decided by this phone's own past, never
        // by the books and marks a restore writes ([CheaperStrike.ranBefore]).
        values[CheaperStrike.K_RESTORED] = true
        // 19 Nov (review): Boss's one-time automatic-stops choice is never run by a restore's books (it would switch his stops off with
        // no PIN): marked done, whatever the backup held. His switches stay as this phone has them.
        // 20 Nov 2026 (minor fixes, item 3): on a phone where it had not run yet, the card says it was not applied because of the restore.
        // (Review: both steps - a phone that ran the first choice but not yet Jarvis's cutoff step had it cut short by the restore too.)
        val firstRan = runCatching { SecurePrefs.getBoolean(com.optionslab.ira.ArmAutoStops.MIGRATION_KEY, false) }.getOrDefault(false)
        val choiceWasRun = firstRan && runCatching { SecurePrefs.getBoolean(com.optionslab.ira.ArmAutoStops.CUTOFF_MIGRATION_KEY, false) }.getOrDefault(false)
        // 22 Nov 2026 (B6 review, item 9d): the first choice ran and only its last step (Jarvis's cutoff) was cut short - said as that.
        val why = if (firstRan) com.optionslab.ira.ArmAutoStops.NotApplied.CUT_SHORT else com.optionslab.ira.ArmAutoStops.NotApplied.RESTORE
        values[com.optionslab.ira.ArmAutoStops.MIGRATION_KEY] = true
        values[com.optionslab.ira.ArmAutoStops.CUTOFF_MIGRATION_KEY] = true
        // (Never the backup's own reason: it was another phone's, or another time's.)
        if (!choiceWasRun) values[com.optionslab.ira.ArmAutoStops.NOT_APPLIED_KEY] = why.key
        else values.remove(com.optionslab.ira.ArmAutoStops.NOT_APPLIED_KEY)
        SecurePrefs.putAll(values)
        // ...and in memory, so a retry before the restart cannot run it either (a later read keeps the mark: [ArmAutoStopsHost.load]).
        runCatching { ArmAutoStopsHost.markChoiceRun(if (choiceWasRun) null else why) }
        // The danger meter's switch as restored, in the entry path's memory copy at once (the restart loads it again).
        runCatching { AppSettings.syncDangerHalf() }
        } catch (e: Throwable) {
            // 20 Oct, second review: kept frozen. Both callers close the app after a failed restore too; a thaw here let the
            // live stores write their old books over the files already restored, and the restart read a mix of the two.
            throw e
        }
        // 22 Nov 2026 (B6 review): what was kept or left out, told where Boss will see it after the restart (the app closes at once).
        if (notes.isNotEmpty()) {
            runCatching { Diag.record("backup", "restore: " + notes.joinToString(" ")) }
            runCatching { com.optionslab.app.work.Notifier.post(ctx, RESTORE_NOTICE, com.optionslab.app.work.Notifier.APPROVAL,
                "Restored: what was kept from this phone", notes.joinToString(" "), "almanac") }
        }
        return Restored(changePin = c.pinSealed, notes = notes)
    }

    /** The account stop rule's recorded days ([AccountStopHost]): carried, and merged on a restore so its figures never drop. */
    internal const val ACCOUNT_STOP_DAYS = "accountStop.days"

    /** 22 Nov 2026 (B6 review): the guard's live drawdown peak, as [Guard] keeps it (carried; a restore never lowers it, [mergeGuards]). */
    internal const val LIVE_PEAK = "guard.peak.live"

    /** 22 Nov 2026 (B6 review): the restore's notice (what it kept from this phone or left out of the backup). */
    private const val RESTORE_NOTICE = 2019

    /** 22 Nov 2026 (B6 review): a restore is running - from its start until the app restarts (cleared when it is refused or fails). */
    @Volatile private var restoring = false

    /**
     * 22 Nov 2026 (B6 review): the entry door's refusal while a restore runs ([Broker.entryRecheck]): a new entry sent then would be booked
     * into a book the restore replaces, and forgotten. Memory only (the order path); exits never ask it. Null: none running.
     */
    fun restoringRefusal(): String? = if (restoring) RESTORING else null

    internal const val RESTORING = "refused: a backup is being restored (the app restarts when it is done); exits still go"

    /** TEST ONLY: no restore running (a test's restore is never followed by the restart that would clear it). */
    internal fun restoreEndedForTest() { restoring = false }

    /**
     * 22 Nov 2026 (B6 review, item 1): the carried settings in [values] (the backup's) that hold live entries back, merged with this phone's
     * own (read from the settings vault, which was found readable) so a restore never loosens one: the guard's live drawdown peak (the
     * higher), the self-review's parks to paper and pauses (both sets: a restore only ever adds one), and the brain's entry pause and the
     * findings' reactions (each the stricter). A setting the backup does not have is left as this phone has it. The paper peak is not
     * merged: it goes with the paper account the restore puts back (as a paper reset starts it again).
     */
    private fun mergeGuards(values: MutableMap<String, Any?>, notes: MutableList<String>) {
        if (values.containsKey(LIVE_PEAK)) {
            val here = SecurePrefs.getDouble(LIVE_PEAK, Double.NaN)
            val kept = com.optionslab.ira.Upkeep.higherPeak(here, (values[LIVE_PEAK] as? Number)?.toDouble())
            if (kept == null) values.remove(LIVE_PEAK) else values[LIVE_PEAK] = kept
            // (Raised above this phone's own - a peak lowered here by the payouts, with the PIN, among them: said, so Boss can lower it again.)
            if (kept != null && here.isFinite() && here > 0 && kept > here) notes += "The live account's drawdown peak was raised to the backup's " +
                "(${com.optionslab.engine.orb.LiveMirror.rs(kept)}, this phone had ${com.optionslab.engine.orb.LiveMirror.rs(here)}): a restore never lowers it. If this phone's was lowered for " +
                "payouts, lower it again on the Account guard page with your PIN."
        }
        // 22 Nov 2026 (B6 review): a park or pause of R51, R72 or Liquidity while their self-review stop is off (Boss's 19 Nov choice: they
        // run until he stops them) stays as this phone has it - a backup never adds one, and this phone's own are kept. Off: on this phone
        // now and after the restore (the backup's switch is carried; one it switches on makes the review act again, so both then).
        // Every other key: both.
        val reviewKey = com.optionslab.ira.ArmAutoStops.Rule.SELF_REVIEW.prefKey
        val reviewOnHere = runCatching { SecurePrefs.getBoolean(reviewKey, true) }.getOrDefault(true)
        val reviewOnAfter = when (val v = values[reviewKey]) { is Boolean -> v; is String -> v.toBooleanStrictOrNull() ?: reviewOnHere; else -> reviewOnHere }
        val reviewOff = !reviewOnHere && !reviewOnAfter
        for (k in listOf(SmartWorkers.K_PARKED, SmartWorkers.K_PAUSED))
            if (values.containsKey(k)) values[k] = com.optionslab.ira.Upkeep.union(SecurePrefs.getString(k), values[k] as? String,
                hereOnly = { reviewOff && com.optionslab.ira.ArmAutoStops.covers(it) })
        if (values.containsKey(SmartWorkers.K_PAUSE))
            values[SmartWorkers.K_PAUSE] = com.optionslab.ira.Upkeep.stricterPause(SecurePrefs.getString(SmartWorkers.K_PAUSE), values[SmartWorkers.K_PAUSE] as? String)
        if (values.containsKey(SmartWorkers.K_REACT))
            values[SmartWorkers.K_REACT] = com.optionslab.ira.Upkeep.stricterReactions(SecurePrefs.getString(SmartWorkers.K_REACT), values[SmartWorkers.K_REACT] as? String)
    }

    /**
     * 20 Nov 2026 (audit, B5 item 2): a restore refused because live positions are open, or a live book cannot tell yet ([books]: each
     * reason, in Boss's words). Nothing was written.
     */
    class LiveHeld(val books: List<String>) : Refused(liveHeldWords(books))

    /** 20 Nov 2026 (B5): a restore refused before anything was written; its message is for Boss, and the app stays open. */
    open class Refused(message: String) : IllegalStateException(message)

    /** What Boss is told when [LiveHeld] refuses a restore: each reason a whole clause. */
    internal fun liveHeldWords(books: List<String>): String =
        "Not restored: ${books.joinToString("; ")}. A restore replaces the books that record them. Nothing on this phone was changed. " +
            "Restore once nothing is open (after the market's close is safest)."

    /** 20 Nov 2026 (B5 item 3, review P4): a restore refused because this phone's account-stop days could not be read. Nothing was written. */
    class AccountStopUnread : Refused(ACCOUNT_STOP_UNREAD)

    internal const val ACCOUNT_STOP_UNREAD = "Not restored: this phone's account stop record could not be read, and a restore must never " +
        "lower it. Nothing on this phone was changed. Try again in a minute, or after opening the app again."

    /** TEST SEAM: reads this phone's account-stop days in place of the settings vault (it may throw). Null in the app, always. */
    @Volatile internal var testAccountStopRead: (() -> String?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the account-stop seam exists only in debug builds" }; field = v }

    /** This phone's account-stop days ([ACCOUNT_STOP_DAYS]); null when they cannot be read (the vault unreadable, or the read failed). */
    private fun accountStopDaysHere(): List<com.optionslab.ira.AccountStop.Day>? = try {
        val seam = testAccountStopRead
        if (seam != null) com.optionslab.ira.AccountStop.decode(seam())
        else {
            // (Review: read first, then judge - a failed load marks the vault unreadable and answers empty.)
            val raw = SecurePrefs.getString(ACCOUNT_STOP_DAYS)
            if (SecurePrefs.unreadable) null else com.optionslab.ira.AccountStop.decode(raw)
        }
    } catch (e: Exception) { null }

    /** TEST SEAM (JVM tests only): what [liveHeld] answers. Null in the app, always: its setter throws in a release build. */
    @Volatile internal var testLiveHeld: (() -> List<String>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the live-held seam exists only in debug builds" }; field = v }

    /** The reasons [liveHeld] gives, as whole clauses (the tests read them). */
    internal const val HELD_START = "the open positions are still being read after the app's start"
    internal const val HELD_ARMS = "the arms' live trades (R51, R72 and the others) are open"
    internal const val HELD_ARMS_BOOK = "the arms' live book is being read or recovered (log in to Zerodha to finish)"
    internal const val HELD_ORB = "Liquidity 15+5 or an ORB arm holds a live position"
    internal const val HELD_PINE = "Pine holds a live position"
    internal const val HELD_STRATEGY = "a saved strategy is running (paper or live)"
    internal const val HELD_PROTECT = "a live protection is active"
    internal const val HELD_ZERODHA = "Zerodha shows open positions"

    /**
     * 20 Nov 2026 (audit, B5 item 2): the live books holding an open position now, in Boss's words (empty: none). Memory only (each
     * book's own hint, as the watch reads them: never a vault read or the network). A book that cannot tell counts as holding.
     * (Review: the hints are only right once this start has read the position books ([PositionsFirst], step 1): until then the whole
     * restore is refused as "still being read". [started] / [startRead]: the start began / its read is done - the unit tests' app never
     * runs the start.)
     */
    fun liveHeld(started: Boolean = runCatching { PositionsFirst.startedAt != 0L }.getOrDefault(true),
                 startRead: Boolean = runCatching { PositionsFirst.foundOpen != null }.getOrDefault(false)): List<String> = testLiveHeld?.invoke() ?: buildList {
        fun held(name: String, f: () -> Boolean) { if (runCatching(f).getOrDefault(true)) add(name) }
        if (started && !startRead) add(HELD_START)
        // (Review P3: a book being read or recovered is said as such, not as open trades.)
        val armsOpen = runCatching { ArmLive.holdingHint }.getOrDefault(true)
        if (armsOpen) add(HELD_ARMS) else held(HELD_ARMS_BOOK) { ArmLive.recoverHint }
        held(HELD_ORB) { OrbArms.liveHint }
        held(HELD_PINE) { PineAuto.held.value.values.any { it.live } }
        held(HELD_STRATEGY) { Strategies.runningHint }
        held(HELD_PROTECT) { Protections.activeHint == true }
        held(HELD_ZERODHA) { Reconciler.heldHint() }
    }

    private const val ORB = "orb.vault"
    private const val STRATEGIES = "strategies.vault"

    /**
     * 22 Nov 2026 (B6 review): a restore refused because this phone's strategies' book is there but could not be read: its Stop for today
     * (kept there) would be lost. Nothing was written.
     */
    class StrategiesBookUnread : Refused(STRATEGIES_UNREAD)

    internal const val STRATEGIES_UNREAD = "Not restored: this phone's strategies' book could not be read, and a restore must never lift " +
        "today's Stop for today (it is kept there). Nothing on this phone was changed. Try again in a minute, or after opening the app again."

    /** This phone's strategies' book as saved (null: none, or not a book); [StrategiesBookUnread] when it is there but cannot be read now. */
    private fun strategiesBookHere(ctx: Context): JSONObject? {
        val raw = try { Vault.readFileSteady(file(ctx, "f", STRATEGIES)) } catch (e: Exception) { throw StrategiesBookUnread() } ?: return null
        return try { runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull() } finally { raw.fill(0) }
    }

    /** 22 Nov 2026 (B6 review): one side's Stop for today as a strategies' book keeps it - its day, reason and time, and (MCX) whether it sells. */
    internal data class SideStop(val day: String, val why: String?, val at: String?, val sells: Boolean = true)

    private fun JSONObject.text(k: String): String? = optString(k).takeIf { it.isNotBlank() }

    /** The index side's stop in the strategies' book [o] (as Strategies reads it). */
    internal fun indexStop(o: JSONObject?): SideStop? = o?.text("stoppedDay")?.let { SideStop(it, o.text("stoppedWhy"), o.text("stoppedAt")) }

    /** The commodity side's stop in [o] (as Strategies reads it: an older book's one stop counts for both, unless it was the loss stop). */
    internal fun mcxStop(o: JSONObject?): SideStop? {
        o ?: return null
        val split = o.optInt("stopSides", 0) >= 1
        val (day, why) = com.optionslab.ira.DayStop.migratedMcx(split, o.text("stoppedDay"), o.text("stoppedWhy"), o.text("mcxStoppedDay"),
            o.text("mcxStoppedWhy")) ?: return null
        return SideStop(day, why, if (split) o.text("mcxStoppedAt") else o.text("stoppedAt"), o.optBoolean("mcxStopSells", true))
    }

    /** Of [here] (this phone's) and [theirs] (the backup's), the later stop; this phone's on the same day (its own is the newer). */
    private fun later(here: SideStop?, theirs: SideStop?): SideStop? = when {
        here == null -> theirs
        theirs == null -> here
        theirs.day > here.day -> theirs
        else -> here
    }

    /**
     * 22 Nov 2026 (B6 review): this phone's strategies' book as read ([here], its stop fields and counters) with today's stops Strategies
     * holds in memory now ([index], [mcx] and its [mcxSells]): one made while the restore ran is never saved (the book is frozen for it),
     * so it is taken from there - the newer on the same day. Null [here] and none in memory: null.
     */
    internal fun withStopsInMemory(here: JSONObject?, index: com.optionslab.ira.DayStop.Kept?, mcx: com.optionslab.ira.DayStop.Kept?,
                                   mcxSells: Boolean): JSONObject? {
        if (index == null && mcx == null) return here
        val today = runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() }.toString()
        val i = later(index?.let { SideStop(today, it.why.wire, it.at) }, indexStop(here))
        val m = later(mcx?.let { SideStop(today, it.why.wire, it.at, mcxSells) }, mcxStop(here))
        val o = JSONObject().put("stopSides", 1).put("nextRunId", here?.optLong("nextRunId", 1) ?: 1L)
            .put("nextStrategyId", here?.optLong("nextStrategyId", 1) ?: 1L)
        i?.let { s -> o.put("stoppedDay", s.day); s.why?.let { o.put("stoppedWhy", it) }; s.at?.let { o.put("stoppedAt", it) } }
        m?.let { s -> o.put("mcxStoppedDay", s.day); s.why?.let { o.put("mcxStoppedWhy", it) }; s.at?.let { o.put("mcxStoppedAt", it) }; o.put("mcxStopSells", s.sells) }
        return o
    }

    /**
     * 22 Nov 2026 (B6 review): the restored strategies' book [o] with each side's Stop for today the later of this phone's ([here]) and the
     * backup's, with its reason and time (and the MCX side's sale): a restore never lifts today's stop. Kept in this build's form (both
     * sides apart). One kept from this phone over the backup's today is told in [notes].
     */
    internal fun withDayStops(o: JSONObject, here: JSONObject?, notes: MutableList<String>): JSONObject {
        val theirsIndex = indexStop(o); val theirsMcx = mcxStop(o)
        val index = later(indexStop(here), theirsIndex); val mcx = later(mcxStop(here), theirsMcx)
        for (k in listOf("stoppedDay", "stoppedWhy", "stoppedAt", "mcxStoppedDay", "mcxStoppedWhy", "mcxStoppedAt")) o.remove(k)
        index?.let { s -> o.put("stoppedDay", s.day); s.why?.let { o.put("stoppedWhy", it) }; s.at?.let { o.put("stoppedAt", it) } }
        o.put("stopSides", 1)
        mcx?.let { s -> o.put("mcxStoppedDay", s.day); o.put("mcxStoppedWhy", s.why ?: com.optionslab.ira.DayStop.Why.BOSS.wire); s.at?.let { o.put("mcxStoppedAt", it) } }
        o.put("mcxStopSells", mcx?.sells ?: true)
        val today = runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() }.toString()
        for ((side, kept, theirs) in listOf(Triple("index", index, theirsIndex), Triple("commodity (MCX)", mcx, theirsMcx)))
            if (kept != null && kept.day == today && kept != theirs) notes += "Today's Stop for today on the $side side is kept as this phone had " +
                "it${kept.at?.let { " (since $it)" }.orEmpty()}: a restore never lifts it."
        return o
    }

    /**
     * 22 Nov 2026 (B6 review): the strategies' book to write on a restore ([bytes]: the backup's, null when it has none; [here]: this
     * phone's, read before anything was written): the backup's, disarmed ([disarmed]), with each side's Stop for today never earlier than
     * this phone's ([withDayStops]). No book in the backup (or one not in a book's shape) while this phone is stopped today: an empty book
     * that keeps the stop. Null: no book to write (none in the backup and no stop today here - removed, as before).
     */
    internal fun strategiesRestored(bytes: ByteArray?, here: JSONObject?, notes: MutableList<String> = ArrayList()): ByteArray? {
        val theirs = bytes?.let { b -> runCatching { JSONObject(String(disarmed(STRATEGIES, b), Charsets.UTF_8)) }.getOrNull() }
        val today = runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() }.toString()
        val out = when {
            theirs != null -> withDayStops(theirs, here, notes)
            listOfNotNull(indexStop(here), mcxStop(here)).any { it.day == today } -> withDayStops(JSONObject().put("defs", org.json.JSONArray())
                .put("runs", JSONObject()).put("history", org.json.JSONArray()).put("brokerIds", JSONObject()).put("log", org.json.JSONArray())
                .put("nextRunId", here?.optLong("nextRunId", 1) ?: 1L).put("nextStrategyId", here?.optLong("nextStrategyId", 1) ?: 1L), here, notes)
            else -> return bytes?.let { disarmed(STRATEGIES, it) }
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * 22 Nov 2026 (B6 review, item 2): a restore refused because this phone's arms' book is there but could not be read: its closed live
     * trades (the live stop rules' record) would be lost. Nothing was written.
     */
    class OrbBookUnread : Refused(ORB_UNREAD)

    internal const val ORB_UNREAD = "Not restored: this phone's arms' book could not be read, and a restore must never drop its closed live " +
        "trades (Liquidity's live stop rule and the arms' live-copies account stop read them). Nothing on this phone was changed. Try again " +
        "in a minute, or after opening the app again."

    /**
     * This phone's arms' book as saved (null: none, or not a book the arms could read either - nothing of it stands); [OrbBookUnread]
     * when the file is there but cannot be decrypted now. Read before anything is written.
     */
    private fun orbBookHere(ctx: Context): JSONObject? {
        val raw = try { Vault.readFileSteady(file(ctx, "f", ORB)) } catch (e: Exception) { throw OrbBookUnread() } ?: return null
        return try { runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull() } finally { raw.fill(0) }
    }

    /** The closed LIVE trades (rows as OrbArms writes them: "live" true, an "exit") in the arms' book [book]. */
    internal fun closedLive(book: JSONObject?): List<JSONObject> {
        val rows = book?.optJSONArray("positions") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter { it.optBoolean("live", false) && it.has("exit") }
    }

    /** One trade in two books: [b] (the backup's row) a live one, with the same entry order id, or the same arm, option and entry time. */
    private fun sameTrade(a: JSONObject, b: JSONObject): Boolean {
        if (!b.optBoolean("live", false)) return false
        val id = a.optString("entryOrderId")
        if (id.isNotEmpty() && id == b.optString("entryOrderId")) return true
        return a.optString("arm") == b.optString("arm") && a.optString("symbol") == b.optString("symbol") &&
            a.optString("entryTime") == b.optString("entryTime")
    }

    private fun entryTimeOf(o: Any?): java.time.LocalDateTime? =
        (o as? JSONObject)?.optString("entryTime")?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }

    /**
     * 22 Nov 2026 (B6 review, item 2): the restored arms' book [o] with this phone's closed live trades [live] in it - a trade in both keeps
     * this phone's row (by entry order id, or arm, option and entry time), one the backup lacks is put in by its entry time - so the live
     * record a stop rule reads is never milder than this phone's. Every other row is the backup's, as it was.
     */
    internal fun withClosedLive(o: JSONObject, live: List<JSONObject>): JSONObject {
        if (live.isEmpty()) return o
        val rows = o.optJSONArray("positions")
        val out = ArrayList<Any?>()
        val rest = live.toMutableList()
        for (i in 0 until (rows?.length() ?: 0)) {
            val row = rows!!.opt(i)
            val mine = (row as? JSONObject)?.let { r -> rest.firstOrNull { sameTrade(it, r) } }
            if (mine != null) rest.remove(mine)
            out += mine ?: row
        }
        for (p in rest) {
            // (After the last row that entered at or before it; one with no readable entry time goes at the end.)
            val t = entryTimeOf(p)
            out.add(if (t == null) out.size else out.indexOfLast { r -> entryTimeOf(r)?.let { !it.isAfter(t) } == true } + 1, p)
        }
        return o.put("positions", org.json.JSONArray(out))
    }

    /**
     * 22 Nov 2026 (B6 review): the restored arms' book [o] without the backup's OPEN live trades that match nothing on this phone (this
     * phone's own open ones cannot be there: a restore is refused while one is open, [liveHeld]). Restored, one would be booked closed at
     * a made-up price dated today (softening the live stop rules' month), hold up every later restore, or claim a lot Boss bought by
     * hand. Each one left out is named in [notes].
     */
    internal fun withoutOpenLive(o: JSONObject, notes: MutableList<String>): JSONObject {
        val rows = o.optJSONArray("positions") ?: return o
        val kept = org.json.JSONArray()
        val gone = ArrayList<String>()
        for (i in 0 until rows.length()) {
            val r = rows.opt(i)
            if (r is JSONObject && r.optBoolean("live", false) && !r.has("exit"))
                gone += "${r.optString("symbol")} (${r.optString("arm")}, entered ${r.optString("entryTime").replace('T', ' ')})"
            else kept.put(r)
        }
        if (gone.isEmpty()) return o
        notes += "Left out of the backup: ${gone.size} live trade(s) it still had open, which this phone has no record of - ${gone.joinToString("; ")}. " +
            "Check Kite for them; the restored book never books them closed."
        return o.put("positions", kept)
    }

    /** The arms' book [o] with nothing armed, automatic, cleared for Zerodha or waiting (as OrbArms.disarmAll clears them). */
    private fun disarmBook(o: JSONObject): JSONObject =
        o.put("armed", JSONObject()).put("auto", JSONObject()).put("liveOk", JSONObject()).put("pending", JSONObject())

    /**
     * 22 Nov 2026 (B6 review, item 2): the arms' book to write on a restore ([bytes]: the backup's, null when it has none; [here]: this
     * phone's, read before anything was written). The backup's, disarmed, Liquidity's size never raised ([liquidityLots]) and this phone's
     * closed live trades kept in it ([withClosedLive]). A backup with no book (or one not in a book's shape) while this phone has closed
     * live trades: this phone's own book with only those, disarmed (its parkings and one-time changes as they are). Null: no book to write
     * (none in the backup, no live trade here - removed, as before); a backup's book not in the expected shape with none here is written
     * as it is (the disarm flag alone disarms it at the next start, as before).
     */
    internal fun orbRestored(bytes: ByteArray?, lotsHere: Int?, here: JSONObject?, notes: MutableList<String> = ArrayList()): ByteArray? {
        val live = closedLive(here)
        val theirs = bytes?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        val out = when {
            theirs != null -> withoutOpenLive(withClosedLive(liquidityLots(disarmBook(theirs), lotsHere), live), notes)
            live.isNotEmpty() -> disarmBook(JSONObject(here.toString())).put("positions", org.json.JSONArray(live))
            else -> return bytes
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

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

    /**
     * A restored Pine file: every script switched off, and under Boss's 06 Oct rule ([com.optionslab.ira.PineProtection])
     * a stop-loss and a target on each (a 0 or none in the backup becomes 30 / 60; a nonzero stop is kept) and the profit
     * lock on - a backup can never bring back a script without them. PineScripts.parse applies the same rule on reading.
     */
    internal fun pineRestored(text: String): String {
        val a = org.json.JSONArray(text)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val au = o.optJSONObject("auto") ?: JSONObject().also { o.put("auto", it) }
            val e = com.optionslab.ira.PineProtection.normalise(au.optDouble("stopPts", 0.0), au.optDouble("targetPts", 0.0), au.optBoolean("profitLock", true))
            au.put("on", false).put("stopPts", e.stopPts).put("targetPts", e.targetPts).put("profitLock", true)
        }
        return a.toString()
    }

    /**
     * The file's contents with everything that could trade by itself switched off, in the
     * form each store writes it (Strategies, PineScripts; the arms' book: [orbRestored]). Belt and braces with
     * [DISARM]: if a file is not in the expected shape it is kept as it is and the flag alone
     * disarms it at the next start.
     */
    private fun disarmed(name: String, bytes: ByteArray): ByteArray = runCatching {
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
            // PineScripts.save: an array of scripts, each with an "auto" object whose "on" starts it trading.
            "pine.vault" -> pineRestored(text)
            else -> return@runCatching bytes
        }
        out.toByteArray(Charsets.UTF_8)
    }.getOrDefault(bytes)
}
