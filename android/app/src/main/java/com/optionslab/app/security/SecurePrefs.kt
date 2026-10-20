package com.optionslab.app.security

import android.content.Context
import kotlinx.coroutines.launch
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
     * Readable without this object's lock (round 2): a caller that keeps vault values in memory for the order path (Broker's
     * session, the relay switch, the pinned certificates) drops them when it changes. Bumped by [init], [wipe], [reload] and
     * a write of a kept-elsewhere setting; strictly increasing (never set back, so a kept value can never match a stale
     * generation again).
     */
    @Volatile var generationHint: Int = 0
        private set

    /**
     * The entry door's guards' generation (review R1, 11 Oct): bumped by a write of the daily loss limit's day, the day lock's
     * days or its amount ([GUARD]) - and never by anything else, so the door's kept guards ([com.optionslab.app.data.LossBreaker],
     * [com.optionslab.app.data.DayLockGuard]) stay in memory on the order path. Kept values compare it with [generationHint].
     */
    @Volatile var guardHint: Int = 0
        private set

    /**
     * 18 Oct (final review F2): the Live switch's generation - bumped by a write of the app's mode, its real-orders switch or
     * "AI trades go live" ([LIVE]) and never by anything else, so the entry door keeps those three in memory
     * ([com.optionslab.app.data.ArmLive.liveFlags]) and still sees a flip at once. Kept values compare it with [generationHint].
     */
    @Volatile var liveHint: Int = 0
        private set

    /** The settings behind [liveHint]: the mode, the real-orders switch and "AI trades go live" (IraNewsTrades.PAPER_KEY). */
    private val LIVE = setOf("k.mode", "k.allow", "jarvis.trades.paper.v3")

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
        if (keys.any { it in GUARD }) guardHint++
        if (keys.any { it in LIVE }) liveHint++
    }

    /** The settings the entry door keeps in memory for the bots' entries (the loss limit's day, the day lock). */
    private val GUARD = setOf("breaker.day", "breaker.daylock.paper", "breaker.daylock.live", "g.dayLock")

    fun init(context: Context) {
        synchronized(ioLock) { synchronized(this) { generation++; generationHint++; pending = false; lazyDirty = false; lazyTask = 0L; writtenSeq = seq } }
        file = File(context.noBackupFilesDir, "prefs.vault")
        SecureBlobs.init(File(context.noBackupFilesDir, "prefs.blobs"))
    }

    // ---- the large values, in their own files ([SecureBlobs]) ---------------------------------------------------------

    /**
     * Values of [SecureBlobs.KEYS] found in the settings file (written by an older version, or restored): kept out of [map],
     * but still written back with every settings write until each has its own file, read back and checked
     * ([SecureBlobs.migrate]). Guarded by this object's lock.
     */
    private val legacy = HashMap<String, Any?>()
    /** A move of [legacy] values to their own files is queued on the background writer (guarded by this object's lock). */
    private var migrating = false

    /** [k]'s value still in the settings file, if it has not been moved out yet. */
    @Synchronized internal fun legacy(k: String): Any? { map(); return legacy[k] }

    /** [k] has its own file now: the settings file's copy goes with the next settings write. */
    @Synchronized internal fun dropLegacy(k: String) { legacy.remove(k) }

    internal fun sameValue(old: Any?, new: Any?): Boolean = same(old, new)

    /** Under this object's lock: the move of [legacy] values queued, off the caller's thread (once per load). */
    private fun queueMigration() {
        if (legacy.isEmpty() || migrating) return
        migrating = true
        val gen = generation
        runCatching {
            writer.execute {
                val todo = synchronized(this) { if (gen == generation) HashMap(legacy) else HashMap() }
                for ((k, v) in todo) runCatching { SecureBlobs.migrate(k, v) }
                // The settings file written without those now in their own files (each on disk, read back and compared).
                val snap = synchronized(this) {
                    migrating = false
                    if (gen == generation && todo.keys.any { it !in legacy }) copyOut(gen) else null
                }
                if (snap != null) runCatching { write(snap) }
            }
        }.onFailure { migrating = false }
    }

    /** [values] split: the settings file's own, and the large ones ([SecureBlobs]). */
    private fun split(values: Map<String, Any?>): Pair<Map<String, Any?>, Map<String, Any?>> =
        if (values.keys.none(SecureBlobs::isBlob)) values to emptyMap() else values.entries.partition { !SecureBlobs.isBlob(it.key) }
            .let { (a, b) -> a.associate { it.key to it.value } to b.associate { it.key to it.value } }

    /** One large value read (its own file, decrypted the first time it is asked for; never under this object's lock). */
    private fun blob(k: String): Any? { counted(); return SecureBlobs.get(k) }

    /** A JSON-typed read of a large value, the way the settings map reads its own. */
    private fun blobJson(k: String): JSONObject? = blob(k)?.let { JSONObject().put("v", it) }

    /**
     * The settings' sizes, by key class (digits folded to "#", so per-day keys add up together), biggest first: names and
     * byte counts only, never a value. The large values' are their files' sizes. For the diagnostics and tests.
     */
    fun sizes(): List<Pair<String, Long>> {
        val main = synchronized(this) {
            val m = map()
            m.keys().asSequence().map { it to (m.opt(it)?.toString()?.length ?: 0).toLong() + it.length }.toList() +
                legacy.map { (k, v) -> k to (v?.toString()?.length ?: 0).toLong() + k.length }
        }
        val all = main + SecureBlobs.sizes().map { (k, n) -> "$k (own file)" to n }
        return all.groupBy({ it.first.replace(Regex("[0-9]+"), "#") }, { it.second }).mapValues { it.value.sum() }
            .toList().sortedByDescending { it.second }
    }

    /** The diagnostics' line: the settings file's size, its five biggest key classes, and screen-thread reads of the large values. */
    fun sizeLine(): String {
        val all = sizes()
        val own = all.filter { !it.first.endsWith("(own file)") }.sumOf { it.second }
        val top = all.take(5).joinToString(", ") { (k, n) -> "$k ${n / 1024} KB" }
        val main = SecureBlobs.loadsOnMain.get().let { n -> if (n == 0L) "none" else "$n (last: ${SecureBlobs.lastOnMain})" }
        return "Settings size: ${own / 1024} KB in the settings file · biggest: $top · large values read on the screen's thread: $main"
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
    fun putAllSoon(all: Map<String, Any?>) {
        val (values, blobs) = split(all)
        blobsSoon(blobs)
        if (values.isNotEmpty()) synchronized(this) { putAllSoonHere(values) }
    }

    /** Large values readable at once, each written to its own file by the background writer. */
    private fun blobsSoon(blobs: Map<String, Any?>) {
        for ((k, v) in blobs) if (SecureBlobs.putSoon(k, v)) runCatching { writer.execute { SecureBlobs.saveNow(k) } }
    }

    private fun putAllSoonHere(values: Map<String, Any?>) {
        if (unchanged(values)) return
        touched(values.keys)
        val m = map()
        for ((k, v) in values) if (v == null) m.remove(k) else m.put(k, v)
        queueWrite()
    }

    /** The whole map queued for the background writer, once (under this object's lock). */
    private fun queueWrite() {
        if (pending) return
        pending = true
        val gen = generation
        writer.execute {
            val snap = synchronized(this) { if (pending && gen == generation) copyOut(gen) else null } ?: return@execute
            runCatching { write(snap) }
        }
    }

    /**
     * 18 Oct, review: every write still in memory (the screen's deferred ones, the lazily kept figures) put on disk now, on a
     * background thread of its own - the screen going away ([com.optionslab.app.MainActivity.onStop]) and Android asking for
     * memory back ([com.optionslab.app.IraAlgoApp.onTrimMemory]) call it, so a process killed soon after loses none of them.
     * A failed write is queued once more. Never waits on the caller's thread; never throws.
     */
    fun flushSoon(): kotlinx.coroutines.Job = flushScope.launch {
        flushSoonRuns.incrementAndGet()
        runCatching { if (!flush()) { synchronized(this@SecurePrefs) { if (!unreadable) queueWrite() }; flush() } }
    }

    /**
     * The whole map as it is in memory written now, on the caller's thread (18 Oct, review: the kill switch's own writer
     * after a failed background write). Never on the screen's thread or the order path. False: an unreadable vault (never
     * written over); throws when the write fails.
     */
    fun writeNow(): Boolean {
        val snap = synchronized(this) { if (unreadable) null else copyOut() } ?: return false
        write(snap)
        return true
    }

    private val flushScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    /** How many [flushSoon]s ran (the tests: the screen's stop and the memory trim are wired to it). */
    internal val flushSoonRuns = java.util.concurrent.atomic.AtomicInteger()

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
    fun putAllLazy(all: Map<String, Any?>, delayMs: Long = LAZY_MS) {
        val (values, blobs) = split(all)
        blobsSoon(blobs)
        if (values.isNotEmpty()) synchronized(this) { putAllLazyHere(values, delayMs) }
    }

    private fun putAllLazyHere(values: Map<String, Any?>, delayMs: Long) {
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
        // 20 Oct, review: a large value whose write failed was left dirty with no write queued, and flush still said "written".
        // Each one still dirty is queued again here, and flush is true only once none is left.
        for (k in SecureBlobs.dirtyKeys()) runCatching { writer.execute { SecureBlobs.saveNow(k) } }
        if (synchronized(this) { pending || lazyDirty || migrating } || SecureBlobs.anyDirty()) runCatching { writer.submit(Runnable {}).get(30, java.util.concurrent.TimeUnit.SECONDS) }
        // A write another thread copied out and is still putting on disk counts as not yet written: wait for it.
        val want = synchronized(this) { seq }
        val written = synchronized(ioLock) { writtenSeq >= want }
        return written && !writeFailed && synchronized(this) { !pending && !lazyDirty } && !SecureBlobs.anyDirty()
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
        // The large values (written here by an older version, or restored) moved to their own files, off this thread.
        for (k in loaded.keys().asSequence().filter(SecureBlobs::isBlob).toList()) legacy[k] = loaded.remove(k)
        cache = loaded
        queueMigration()
        return loaded
    }

    /**
     * Speed, round 6: the settings read (one Keystore decryption) done now, on the caller's thread. The app's start
     * calls it before any background thread starts its own vault reads: the Keystore (StrongBox on a Pixel) takes one
     * operation at a time, so a settings read made later on the screen's thread queued behind the pine scripts' and the
     * four gold books' decryptions - Boss's "app start 1308 ms, slowest: Jarvis's memory 1294 ms" was that wait
     * (Jarvis's model status is the start's first settings read). The settings are needed on that thread before the
     * start ends anyway (the restore disarm, the lock); read first, they cost only their own decryption. 12 Oct: the large
     * values are no longer in that file ([SecureBlobs]); those read early are decrypted next, on their own thread.
     */
    fun warm() {
        synchronized(this) { map() }
        // The large values the watch and the Ira page read early, decrypted now on their own thread (never this one).
        runCatching { Thread({ SecureBlobs.prefetch() }, "prefs-prefetch").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start() }
    }

    /** Drop the cache and read the file again (the "try again" on the unreadable-vault screen). */
    fun reload(): Boolean {
        flush()
        SecureBlobs.reset()
        synchronized(this) { cache = null; legacy.clear(); migrating = false; unreadable = false; generationHint++; map(); return !unreadable }
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
        // The large values not yet in their own files are written back with the rest, so they are never off disk.
        val m = if (legacy.isEmpty()) map() else JSONObject(map().toString()).also { c -> legacy.forEach { (k, v) -> c.put(k, v) } }
        return Snap(seq, gen, m.toString().toByteArray(Charsets.UTF_8))
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


    fun getString(k: String, d: String? = null): String? =
        if (SecureBlobs.isBlob(k)) (blobJson(k)?.optString("v", "") ?: "").ifEmpty { d }
        else synchronized(this) { counted(); map().optString(k, "").ifEmpty { d } }
    fun getBoolean(k: String, d: Boolean): Boolean =
        if (SecureBlobs.isBlob(k)) blobJson(k)?.optBoolean("v", d) ?: d
        else synchronized(this) { counted(); if (map().has(k)) map().optBoolean(k, d) else d }
    fun getInt(k: String, d: Int): Int =
        if (SecureBlobs.isBlob(k)) blobJson(k)?.optInt("v", d) ?: d
        else synchronized(this) { counted(); if (map().has(k)) map().optInt(k, d) else d }
    fun getLong(k: String, d: Long): Long =
        if (SecureBlobs.isBlob(k)) blobJson(k)?.optLong("v", d) ?: d
        else synchronized(this) { counted(); if (map().has(k)) map().optLong(k, d) else d }
    fun getDouble(k: String, d: Double): Double =
        if (SecureBlobs.isBlob(k)) blobJson(k)?.optDouble("v", d) ?: d
        else synchronized(this) { counted(); if (map().has(k)) map().optDouble(k, d) else d }

    // ---- the screen's thread (17 Oct) ----------------------------------------------------------------------------------

    /**
     * Boss's diagnostics, 17 Oct 01:07: "The screen froze 5.8 s in BinderProxy.transactNative in Vault.encrypt" (and 2.3 s): a
     * settings write made on the screen's thread waited on the Keystore. A [put] or [putAll] made there now goes the way of
     * [putAllSoon]: every read sees the value at once, and the encryption and the disk syncs run on the background writer,
     * in order, a moment later. The writes that must be on disk before the caller goes on ([durable]: the PIN and its failure
     * count, the lock, Live and real orders, every guard setting, the kill switch, the day lock and the loss limit, AI trades
     * going live, paper only, the restore's disarm, the Zerodha session - the same families [SecureBlobs.NEVER] keeps in this
     * file) are still written right there, as before, and noted by [MainWrites] with their caller.
     * Off the screen's thread nothing changes.
     */
    private fun durable(k: String): Boolean = k in HOT || k in GUARD || k in DURABLE || SecureBlobs.NEVER.any { k.startsWith(it) }

    private val DURABLE = setOf("k.mode", "k.allow", "k.oneTap", "k.oneTap2", "jarvis.trades.paper.v3", "restore.disarm", "sec.wipe", "sec.refuse")

    /** Whether a write on the screen's thread is moved to the background writer (true; [MainWrites.onMain] tells the thread). */
    @Volatile internal var deferOnMain: Boolean = true

    private fun deferHere(keys: Collection<String>): Boolean = deferOnMain && MainWrites.onMain() && keys.none(::durable)

    fun put(k: String, v: Any?) {
        if (deferHere(listOf(k))) { putAllSoon(mapOf(k to v)); return }
        MainWrites.guard({ "SecurePrefs.put $k" }) { putNow(k, v) }
    }

    private fun putNow(k: String, v: Any?) {
        if (SecureBlobs.isBlob(k)) { SecureBlobs.put(k, v); return }
        val snap = synchronized(this) {
            if (unchanged(mapOf(k to v))) return
            touched(listOf(k))
            if (v == null) map().remove(k) else map().put(k, v)
            copyOut()
        } ?: return
        write(snap)
    }

    fun putAll(all: Map<String, Any?>) {
        if (all.isEmpty()) return
        if (deferHere(all.keys)) { putAllSoon(all); return }
        MainWrites.guard({ "SecurePrefs.putAll " + all.keys.take(4).joinToString(" ") + if (all.size > 4) " +${all.size - 4}" else "" }) { putAllNow(all) }
    }

    private fun putAllNow(all: Map<String, Any?>) {
        val (values, blobs) = split(all)
        for ((k, v) in blobs) SecureBlobs.put(k, v)
        if (values.isEmpty()) return
        val snap = synchronized(this) {
            if (unchanged(values)) return
            touched(values.keys)
            val m = map()
            for ((k, v) in values) if (v == null) m.remove(k) else m.put(k, v)
            copyOut()
        } ?: return
        write(snap)
    }

    /**
     * Every key and value (the backup); callers filter out what must never leave the vault. The large values are read from
     * their own files (so never on the screen's thread).
     */
    fun snapshot(): Map<String, Any?> {
        val main = synchronized(this) { map().let { m -> m.keys().asSequence().associateWith { m.opt(it) } } }
        val blobs = SecureBlobs.KEYS.associateWith { SecureBlobs.get(it) }.filterValues { it != null }
        return main + blobs
    }

    /** The keys starting with [prefix] (to prune per-day keys); names only (a large value's file is not decrypted for it). */
    fun keys(prefix: String): List<String> =
        synchronized(this) { map().keys().asSequence().filter { it.startsWith(prefix) }.toList() } +
            SecureBlobs.KEYS.filter { it.startsWith(prefix) && SecureBlobs.has(it) }

    /** Bumped by every [init] and [wipe]: a caller holding vault values in memory drops them when it changes. */
    @Synchronized fun generationNow(): Int = generation

    fun wipe() { SecureBlobs.wipe {
        // After any write in progress (a write finishing after the delete would bring the old vault back).
        synchronized(ioLock) {
            synchronized(this) {
                generation++; generationHint++; pending = false; lazyDirty = false; lazyTask = 0L
                cache = JSONObject()
                legacy.clear(); migrating = false
                unreadable = false
                writtenSeq = seq
                file.delete()
            }
        }
    } }
}
