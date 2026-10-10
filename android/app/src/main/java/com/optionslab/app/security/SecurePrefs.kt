package com.optionslab.app.security

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Settings held in one vault file. Small, read once, cached in memory; every
 * write re-encrypts the whole map.
 *
 * Speed (2026-10-05): the encryption and the disk syncs of a write run OUTSIDE the lock the reads take. A write is a
 * Keystore operation (StrongBox on a Pixel, tens to hundreds of ms when the chip is busy) plus two fsyncs; held under
 * the read lock, every settings read on the screen's thread waited behind every background write. Writes still go to
 * disk one at a time and in order ([ioLock]); a write that finds a newer copy of the map already on disk is skipped (that
 * copy carries its values too), so an older map can never land over a newer one.
 */
object SecurePrefs {
    private lateinit var file: File
    private var cache: JSONObject? = null

    /**
     * The settings file exists but could not be decrypted. The app then fails
     * CLOSED: nothing is written over it (the PIN verifier and failure count
     * live here), and the lock screen offers only "try again" or "erase".
     */
    @Volatile var unreadable: Boolean = false
        private set

    /**
     * [generation], readable without this object's lock (round 2): a caller that keeps vault values in memory for the order
     * path (Broker's session, the relay switch, the pinned certificates) drops them when it changes. Bumped by [init],
     * [wipe] and [reload].
     */
    @Volatile var generationHint: Int = 0
        private set

    /**
     * TEST ONLY: how many settings reads [countedThread] made ([getString] and the other getters), to prove the order path
     * makes none. Null in the app, always (one volatile read per get); never read by the app.
     */
    internal val readsForTest = java.util.concurrent.atomic.AtomicLong()
    @Volatile internal var countedThread: Thread? = null

    private fun counted() { val t = countedThread; if (t != null && t === Thread.currentThread()) readsForTest.incrementAndGet() }

    /** The settings kept in memory for the order path elsewhere (and the kill switch, which clears them): a write bumps [generationHint]. */
    private val HOT = setOf("kite.apiKey", "kite.accessToken", "kite.loginAt", "kite.userName", "relay.on", "k.staticIp", "g.kill")

    /** Under this object's lock: [keys] include a kept-elsewhere setting (or a pinned certificate set): [generationHint] bumped. */
    private fun touched(keys: Collection<String>) {
        if (keys.any { it in HOT || it.startsWith("tls.") }) generationHint++
    }

    fun init(context: Context) {
        synchronized(ioLock) { synchronized(this) { generation++; generationHint = generation; pending = false; lazyDirty = false; lazyTask = 0L; writtenSeq = seq } }
        file = File(context.noBackupFilesDir, "prefs.vault")
    }

    /**
     * Held while the vault file is written, and taken BEFORE this object's lock whenever both are held (never the other
     * way round). Reads take only this object's lock, so they never wait on a write's encryption or disk sync.
     */
    private val ioLock = Any()
    /** Bumped (under this object's lock) each time the map is copied out to be written. */
    private var seq = 0L
    /** The newest copy on disk (written under [ioLock]; volatile so [unchanged] may look at it under this object's lock). */
    @Volatile private var writtenSeq = 0L

    // ---- write-behind, for writes that must not hold up an order -----------------------------------

    /** One background writer: saves run in order, each writing the whole (latest) map. Scheduled, for [putAllLazy]. */
    private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "prefs-writer").apply { isDaemon = true } }
    /** A save is queued and not yet written (guarded by this object's lock). */
    private var pending = false
    /** Bumped by [init] and [wipe]: a save queued before them is dropped, never written over the new state. */
    private var generation = 0

    /**
     * [putAll], with the values readable at once (every get sees them) and the vault write - a Keystore
     * encryption and two disk syncs - done on the background writer instead of the caller's thread.
     * A later [put]/[putAll] writes them too; [flush] waits for them.
     */
    @Synchronized fun putAllSoon(values: Map<String, Any?>) {
        if (unchanged(values)) return
        touched(values.keys)
        val m = map()
        for ((k, v) in values) if (v == null) m.remove(k) else m.put(k, v)
        if (pending) return
        pending = true
        val gen = generation
        writer.execute {
            val snap = synchronized(this) { if (pending && gen == generation) copyOut(gen) else null } ?: return@execute
            runCatching { write(snap) }
        }
    }

    // ---- coalesced writes, for figures that move with every price (ANR fix, 9 Oct) --------------------------------------

    /**
     * How long a [putAllLazy] value may stay in memory only. Every vault write re-encrypts the WHOLE settings map through
     * the Keystore, which does one operation at a time (StrongBox on a Pixel): the day's P&L figure and curve, recorded on
     * every price pass (the open Home / Trade page every 2-10 s, the watch every 15 s), kept it busy all session, and every
     * other vault read or write - the screen's, the app's start ("settings 1941 ms") - queued behind it.
     */
    const val LAZY_MS = 60_000L
    /** [putAllLazy] values not yet on disk (guarded by this object's lock). */
    private var lazyDirty = false
    /** The scheduled lazy write's number (0: none scheduled; guarded by this object's lock). */
    private var lazyTask = 0L
    private var lazySeq = 0L

    /**
     * [putAllSoon] for display figures that change on every price (the calendar's day P&L, the day's P&L curve): readable at
     * once, written at most [delayMs] later - or with the next write of any other setting, whichever comes first - so a
     * figure moving every few seconds costs one Keystore encryption a minute, not one a pass. [saveSoon] / [flush] write
     * them sooner (the app leaving the screen, the watch ending). A process killed in between loses at most that last
     * minute of the figure, which the next pass records again. Never for anything an order, a limit or a lock reads.
     */
    @Synchronized fun putAllLazy(values: Map<String, Any?>, delayMs: Long = LAZY_MS) {
        if (unchanged(values)) return
        touched(values.keys)
        val m = map()
        for ((k, v) in values) if (v == null) m.remove(k) else m.put(k, v)
        lazyDirty = true
        if (pending || lazyTask != 0L) return      // a queued write carries them, or a lazy one is already on its way
        val task = ++lazySeq
        lazyTask = task
        val gen = generation
        val ok = runCatching {
            writer.schedule(Runnable {
                val snap = synchronized(this) {
                    if (lazyTask != task) null
                    else {
                        lazyTask = 0L
                        if (lazyDirty && gen == generation) copyOut(gen) else null
                    }
                }
                if (snap != null) runCatching { write(snap) }
            }, delayMs.coerceAtLeast(0L), java.util.concurrent.TimeUnit.MILLISECONDS)
        }.isSuccess
        if (!ok) lazyTask = 0L
    }

    /** The [putAllLazy] values kept in memory only, written now on the background writer (never waits; any thread). */
    fun saveSoon() {
        val gen = synchronized(this) { if (lazyDirty) generation else null } ?: return
        runCatching {
            writer.execute {
                val snap = synchronized(this) { if (lazyDirty && gen == generation) copyOut(gen) else null }
                if (snap != null) runCatching { write(snap) }
            }
        }
    }

    /**
     * True when writing [values] would change nothing: each one is already the value in memory, and everything in memory is
     * on disk (nothing queued, no write in flight or failed, the file there). Then no Keystore encryption is spent on it -
     * the watch's and Jarvis's passes save their lists after every look, most of the time unchanged. Under this object's lock.
     */
    private fun unchanged(values: Map<String, Any?>): Boolean {
        if (values.isEmpty() || pending || lazyDirty || writeFailed || writtenSeq != seq) return false
        val m = map()
        if (unreadable) return false
        for ((k, v) in values) if (!same(if (m.has(k)) m.opt(k) else null, v)) return false
        return runCatching { file.exists() }.getOrDefault(false)
    }

    /** The same setting value: numbers by their JSON text (a reloaded 5 is the 5L or 5.0 that was put), the rest as is. */
    private fun same(old: Any?, new: Any?): Boolean = when {
        old == null || new == null -> old == null && new == null
        old is Number && new is Number -> runCatching { JSONObject.numberToString(old) == JSONObject.numberToString(new) }.getOrDefault(false)
        old is String || old is Boolean -> old == new
        else -> old.javaClass == new.javaClass && old.toString() == new.toString()
    }

    /** The last background write failed (e.g. a Keystore fault); the values stay in memory and the next save retries. */
    @Volatile private var writeFailed = false

    /**
     * Wait until every [putAllSoon] so far is on disk (never call it holding this object's lock).
     * False when the background write failed: a caller that needs the value durable writes it itself.
     */
    fun flush(): Boolean {
        // The lazily kept values too ([putAllLazy]): queued now, then waited for with the rest.
        saveSoon()
        if (synchronized(this) { pending || lazyDirty }) runCatching { writer.submit(Runnable {}).get(30, java.util.concurrent.TimeUnit.SECONDS) }
        // A write another thread copied out and is still putting on disk counts as not yet written: wait for it.
        val want = synchronized(this) { seq }
        val written = synchronized(ioLock) { writtenSeq >= want }
        return written && !writeFailed && synchronized(this) { !pending && !lazyDirty }
    }

    @Synchronized
    private fun map(): JSONObject {
        cache?.let { return it }
        val loaded = try {
            Vault.readFileSteady(file)?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: JSONObject()
        } catch (_: Exception) {
            // Tampered, or the key is gone. Trust none of it - and do not replace it
            // with an empty map, which would erase the PIN and let anyone set a new one.
            unreadable = true
            JSONObject()
        }
        cache = loaded
        return loaded
    }

    /**
     * Speed, round 6: the settings read (one Keystore decryption) done now, on the caller's thread. The app's start
     * calls it before any background thread starts its own vault reads: the Keystore (StrongBox on a Pixel) takes one
     * operation at a time, so a settings read made later on the screen's thread queued behind the pine scripts' and the
     * four gold books' decryptions - Boss's "app start 1308 ms, slowest: Jarvis's memory 1294 ms" was that wait
     * (Jarvis's model status is the start's first settings read). The settings are needed on that thread before the
     * start ends anyway (the restore disarm, the lock); read first, they cost only their own decryption.
     */
    @Synchronized fun warm() { map() }

    /** Drop the cache and read the file again (the "try again" on the unreadable-vault screen). */
    fun reload(): Boolean {
        flush()
        synchronized(this) { cache = null; unreadable = false; generationHint++; map(); return !unreadable }
    }

    /** The map as it is now, copied out to be written; null when nothing may be written (an unreadable vault). */
    private class Snap(val seq: Long, val gen: Int, val bytes: ByteArray)

    @Synchronized
    private fun copyOut(gen: Int = generation): Snap? {
        map()
        if (unreadable) return null   // never write over a vault we could not read
        pending = false               // this write carries every value set so far
        lazyDirty = false             // ...the lazily kept ones too
        seq++
        return Snap(seq, gen, map().toString().toByteArray(Charsets.UTF_8))
    }

    /** Writes [s] to disk, outside this object's lock (reads go on meanwhile); one write at a time, never an older map over a newer. */
    private fun write(s: Snap) {
        synchronized(ioLock) {
            if (s.seq <= writtenSeq) return                              // a newer copy is on disk already
            if (synchronized(this) { s.gen != generation }) return       // wiped (or started again) since: never written back
            try {
                Vault.writeFile(file, s.bytes)
                writtenSeq = s.seq
                writeFailed = false
            } catch (e: Exception) { writeFailed = true; throw e }
        }
    }


    @Synchronized fun getString(k: String, d: String? = null): String? { counted(); return map().optString(k, "").ifEmpty { d } }
    @Synchronized fun getBoolean(k: String, d: Boolean): Boolean { counted(); return if (map().has(k)) map().optBoolean(k, d) else d }
    @Synchronized fun getInt(k: String, d: Int): Int { counted(); return if (map().has(k)) map().optInt(k, d) else d }
    @Synchronized fun getLong(k: String, d: Long): Long { counted(); return if (map().has(k)) map().optLong(k, d) else d }
    @Synchronized fun getDouble(k: String, d: Double): Double { counted(); return if (map().has(k)) map().optDouble(k, d) else d }

    fun put(k: String, v: Any?) {
        val snap = synchronized(this) {
            if (unchanged(mapOf(k to v))) return
            touched(listOf(k))
            if (v == null) map().remove(k) else map().put(k, v)
            copyOut()
        } ?: return
        write(snap)
    }

    fun putAll(values: Map<String, Any?>) {
        val snap = synchronized(this) {
            if (unchanged(values)) return
            touched(values.keys)
            val m = map()
            for ((k, v) in values) if (v == null) m.remove(k) else m.put(k, v)
            copyOut()
        } ?: return
        write(snap)
    }

    /** Every key and value (the backup); callers filter out what must never leave the vault. */
    @Synchronized fun snapshot(): Map<String, Any?> = map().let { m -> m.keys().asSequence().associateWith { m.opt(it) } }

    /** The keys starting with [prefix] (to prune per-day keys); names only. */
    @Synchronized fun keys(prefix: String): List<String> = map().keys().asSequence().filter { it.startsWith(prefix) }.toList()

    /** Bumped by every [init] and [wipe]: a caller holding vault values in memory drops them when it changes. */
    @Synchronized fun generationNow(): Int = generation

    fun wipe() {
        // After any write in progress (a write finishing after the delete would bring the old vault back).
        synchronized(ioLock) {
            synchronized(this) {
                generation++; generationHint = generation; pending = false; lazyDirty = false; lazyTask = 0L
                cache = JSONObject()
                unreadable = false
                writtenSeq = seq
                file.delete()
            }
        }
    }
}
