package com.optionslab.app.security

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The settings vault's large values, each in its own vault file (Speed, 12 Oct). Boss's start read "app start 2069 ms
 * (slowest: settings 1990 ms)": the one settings file had grown with Jarvis's records - his study, Solo trades, findings,
 * notes, logs - and all of it was decrypted on the screen's thread at every start, for the few small settings the start
 * needs. The keys in [KEYS] now live here instead: the same Keystore key and the same encryption ([Vault.writeFile]), one
 * file per key, decrypted only when that key is first read (each reader is a background job or Jarvis's own thread), and
 * a write re-encrypts only that one value, never the whole settings map.
 *
 * Callers do not change: [SecurePrefs] sends these keys here, and its backup snapshot, restore, key listing, reload and wipe
 * cover them too. What a backup carries or leaves out is decided exactly as before (by key, in the backup).
 *
 * Moving them out (the first start of this version): [SecurePrefs] keeps a value it finds for one of these keys in its
 * file as a "legacy" value - still written back with every settings write, so it stays on disk - until its own file here
 * has been written, read back and found equal; only then is it dropped from the settings file. A crash at any point
 * leaves the value in one place or both, never in neither. Where both exist, this file wins: it is only ever written with
 * the legacy value itself or a newer one. A deleted value is written as an empty record (a "tombstone"), so an old copy
 * still in the settings file can never come back.
 *
 * Lock order: [lock] (read for one key's work, write for a wipe), then one key's [Entry], then [SecurePrefs]' locks.
 * Never the other way round: [SecurePrefs] calls in here only while holding none of its own locks.
 */
internal object SecureBlobs {
    /**
     * The settings that live here: bulk records only (lists, logs, books that grow with use). Never a lock, PIN, Live,
     * AI-live, guard, broker or kill-switch setting - those stay in the settings file the order path keeps in memory.
     * Names are plain ([NAME]): each is its own file name.
     */
    val KEYS: Set<String> = setOf(
        // Read early - by the watch's passes (Solo's holdings, the bots' exposure) or a screen - so read ahead ([PREFETCH]).
        "jarvis.solo.trades",       // Solo's paper trades (the last 500)
        "jarvis.newstrades",        // his news / pattern trades (the last 200)
        "jarvis.study",             // his study's findings, pattern edges and IV history
        "jarvis.suggestions",       // every suggestion (the last 300)
        "jarvis.activity",          // what he did, and when (the last 300)
        // Read only by his ask lane, his own passes and the workers: decrypted when first asked for.
        "jarvis.thinking",          // his decision steps (two days)
        "jarvis.newsMoves",         // news-theme moves (180 days)
        "jarvis.daybook",           // the day journal (90 days)
        "jarvis.bots.switches",     // the arms' on/off habits (180 days)
        "jarvis.bots.testedDays",   // the arms' backtest by day
        "jarvis.mistakes",          // his answers Boss corrected
        "jarvis.notes",             // Boss's notes
        "jarvis.trendReads",        // his trend calls and how they went
        "jarvis.wordfit",           // which words he heard right
        "jarvis.patternCalls",      // his pattern calls and how they went
        "jarvis.wrongThing",        // "that's not what I asked"
        "jarvis.askedAgain",        // questions asked twice
        "jarvis.outlookCheck",      // his outlooks checked against the day
        "jarvis.moreAfter",         // where Boss asked for more
        "jarvis.learned",           // his corrections
        "jarvis.memory",            // what Boss asked him to remember (never in a backup, as before)
    )

    /**
     * Read ahead on a background thread right after the start's settings read ([SecurePrefs.warm]): the watch's passes and
     * the Ira page read these early, and must find them in memory - never decrypting on the order path or the screen's thread.
     */
    val PREFETCH: List<String> = listOf("jarvis.solo.trades", "jarvis.newstrades", "jarvis.study", "jarvis.suggestions", "jarvis.activity")

    /** Every [PREFETCH] value decrypted now (a background thread's; one at a time, order-path ones first). */
    fun prefetch() { for (k in PREFETCH) runCatching { get(k) } }

    /** The keys that must never move here, whatever is added to [KEYS] (checked by a test). */
    internal val NEVER = listOf("pin.", "lock.", "kite.", "relay.", "tls.", "g.", "breaker.", "live", "ai.", "jarvis.trades.live",
        "paperOnly", "backup.disarm")

    private val NAME = Regex("[A-Za-z0-9._-]+")

    fun isBlob(k: String): Boolean = k in KEYS

    private lateinit var dir: File
    private val lock = ReentrantReadWriteLock()

    private class Entry {
        var loaded = false
        var value: Any? = null
        /** The file is there but could not be decrypted: never written over until [SecurePrefs] itself is readable. */
        var unreadable = false
        /** Changed in memory by [putSoon], not yet on disk. */
        var dirty = false
        /** Bumped by each change, so a write knows whether what it wrote is still the newest. */
        var version = 0L
        /**
         * Held while the file is written (20 Oct). The value's own lock is held only to read or set the value, never for the
         * Keystore encryption: the screen's thread putting a value ([putSoon]) waited behind a write of the same key for
         * the encryption's seconds (the 17 Oct ANR, "Input dispatching timed out", while Jarvis's log was written).
         */
        val writing = Any()
    }
    private val entries = ConcurrentHashMap<String, Entry>()

    fun init(d: File) = lock.write { dir = d; entries.clear() }

    /** The cached values dropped (the vault's "try again"): each is read from its file again when next asked for. */
    fun reset() = lock.write { entries.clear() }

    /** Every file deleted. Called by [SecurePrefs.wipe] before it takes its own locks. */
    fun <T> wipe(then: () -> T): T = lock.write {
        entries.clear()
        runCatching { if (::dir.isInitialized) dir.deleteRecursively() }
        then()
    }

    private fun file(k: String) = File(dir, "$k.vault")
    private fun entry(k: String) = entries.getOrPut(k) { Entry() }

    // ---- what tests and the diagnostics see (counts and key names only, never a value) --------------------------------

    /** How many files were decrypted, and how many of those on the screen's thread (should stay 0). */
    internal val loads = java.util.concurrent.atomic.AtomicLong()
    internal val loadsOnMain = java.util.concurrent.atomic.AtomicLong()
    /** The last key decrypted on the screen's thread (its name only), for the diagnostics. */
    @Volatile var lastOnMain: String? = null
        private set

    private fun onMain(): Boolean = runCatching { android.os.Looper.getMainLooper()?.isCurrentThread == true }.getOrDefault(false)

    // ---- reads ---------------------------------------------------------------------------------------------------------

    fun get(k: String): Any? = lock.read {
        val e = entry(k)
        synchronized(e) { load(k, e); e.value }
    }

    /** Under [e]'s lock: the value read from its file (or, before it is moved, the settings file's legacy copy). */
    private fun load(k: String, e: Entry) {
        if (e.loaded) return
        val f = file(k)
        val bytes = try {
            loads.incrementAndGet()
            if (onMain()) { loadsOnMain.incrementAndGet(); lastOnMain = k }
            Vault.readFileSteady(f)
        } catch (_: Exception) {
            // Tampered, or the key is gone. The settings file's copy, if it still has one, is the value until a write.
            e.unreadable = true
            e.value = SecurePrefs.legacy(k)
            e.loaded = true
            return
        }
        if (bytes != null) {
            e.value = decode(bytes)
            // Read back whole: this file now carries the value, and the settings file's copy can go.
            SecurePrefs.dropLegacy(k)
        } else {
            e.value = SecurePrefs.legacy(k)
        }
        e.loaded = true
    }

    private fun decode(bytes: ByteArray): Any? = JSONObject(String(bytes, Charsets.UTF_8)).let { if (it.has("v")) it.get("v") else null }
    private fun encode(v: Any?): ByteArray = (if (v == null) JSONObject() else JSONObject().put("v", v)).toString().toByteArray(Charsets.UTF_8)

    /** Whether [k] holds a value, without decrypting it when it is not read yet (a tombstone counts; its reader sees null). */
    fun has(k: String): Boolean = lock.read {
        val e = entry(k)
        synchronized(e) { if (e.loaded) e.value != null else SecurePrefs.legacy(k) != null || runCatching { file(k).exists() }.getOrDefault(false) }
    }

    // ---- writes --------------------------------------------------------------------------------------------------------

    /** [v] written to [k]'s file now (null: a tombstone). Throws when the encryption or the disk fails, as a settings write does. */
    fun put(k: String, v: Any?) = lock.read {
        val e = entry(k)
        synchronized(e.writing) {
            synchronized(e) {
                if (e.loaded && !e.dirty && !e.unreadable && SecurePrefs.sameValue(e.value, v)) return@read
                e.value = v; e.loaded = true; e.dirty = true; e.version++
            }
            write(k, e)
        }
    }

    /** [v] readable at once; its file written by [SecurePrefs]' background writer ([saveNow]). Never waits on a write. */
    fun putSoon(k: String, v: Any?): Boolean = lock.read {
        val e = entry(k)
        synchronized(e) {
            if (e.loaded && !e.dirty && !e.unreadable && SecurePrefs.sameValue(e.value, v)) return@read false
            e.value = v; e.loaded = true; e.dirty = true; e.version++
            true
        }
    }

    /** A [putSoon] value written now (on the background writer). */
    fun saveNow(k: String) = lock.read {
        val e = entry(k)
        synchronized(e.writing) { runCatching { write(k, e) } }
    }

    /**
     * Under [Entry.writing] only (20 Oct): the newest value taken under [e]'s lock, then encrypted and written with that lock
     * let go; still dirty afterwards when it changed meanwhile (the next write takes the newer value).
     */
    private fun write(k: String, e: Entry) {
        if (SecurePrefs.unreadable) return                     // the settings vault fails closed; so does every part of it
        val (v, ver, unreadable) = synchronized(e) { if (!e.dirty) return; Triple(e.value, e.version, e.unreadable) }
        val f = file(k)
        if (unreadable) { runCatching { if (f.exists()) Vault.setAside(f) }; synchronized(e) { e.unreadable = false } }
        Vault.writeFile(f, encode(v))
        synchronized(e) { if (e.version == ver) e.dirty = false }
        SecurePrefs.dropLegacy(k)
    }

    /** Any value changed in memory and not yet written. */
    fun anyDirty(): Boolean = entries.values.any { synchronized(it) { it.dirty } }

    /** Under [e]'s lock and [Entry.writing]: [migrate]'s one-time write only (every other write is [write]). */
    private fun save(k: String, e: Entry) {
        if (SecurePrefs.unreadable) return                     // the settings vault fails closed; so does every part of it
        val f = file(k)
        if (e.unreadable) { runCatching { if (f.exists()) Vault.setAside(f) }; e.unreadable = false }
        Vault.writeFile(f, encode(e.value))
        e.dirty = false
        SecurePrefs.dropLegacy(k)
    }

    /**
     * One legacy value moved out of the settings file (on the background writer): written to its own file, read back and
     * compared, and only then dropped from the settings file's map. True when [k] no longer needs the settings file.
     */
    fun migrate(k: String, legacy: Any?): Boolean = lock.read {
        val e = entry(k)
        synchronized(e.writing) { synchronized(e) {
            val f = file(k)
            if (e.loaded && !e.unreadable) {
                if (e.dirty) save(k, e)
                // Written by this process since (or read from its file): that copy is the newer one.
                if (runCatching { f.exists() }.getOrDefault(false)) { SecurePrefs.dropLegacy(k); return@read true }
            }
            val existing = try { Vault.readFileSteady(f) } catch (_: Exception) { null.also { runCatching { Vault.setAside(f) } } }
            if (existing != null) { SecurePrefs.dropLegacy(k); return@read true }   // its own file wins
            val bytes = encode(legacy)
            Vault.writeFile(f, bytes)
            val back = Vault.readFileSteady(f)
            if (back == null || !back.contentEquals(bytes)) return@read false       // kept in the settings file; tried again later
            if (!e.loaded || e.unreadable) { e.value = legacy; e.loaded = true; e.unreadable = false }
            SecurePrefs.dropLegacy(k)
            true
        } }
    }

    // ---- the diagnostics' sizes ----------------------------------------------------------------------------------------

    /** Each key's file size in bytes (encrypted, so a little more than the value); names and sizes only. */
    fun sizes(): Map<String, Long> = lock.read {
        KEYS.associateWith { k -> runCatching { file(k).length() }.getOrDefault(0L) }.filterValues { it > 0 }
    }

    init {
        require(KEYS.all { NAME.matches(it) }) { "a blob key is not a plain file name" }
    }
}
