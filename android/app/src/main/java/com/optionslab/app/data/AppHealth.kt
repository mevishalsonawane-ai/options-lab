package com.optionslab.app.data

import android.content.Context
import com.optionslab.ira.Findings
import com.optionslab.ira.HealthBook
import com.optionslab.ira.HealthTuner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's own health on the phone (Boss, 11 Oct: "enhance the self improvement"; the logic is ira-core's [HealthBook] and
 * [HealthTuner]). It collects, locally and with no network:
 *
 *  - main-thread stalls of 0.7 s or more, from the screen watchdog that already runs while the app is on screen ([Speed]: one
 *    empty post to the main thread a second - nothing new runs for this);
 *  - the previous runs' ANRs and crashes (Android's exit records on Android 11 and later; before that the app's own sealed
 *    crash file), read once at start;
 *  - the order path's day median (signal to order sent) from [OrderTiming], read off the order path;
 *  - the price stream's reconnects ([KiteStream]) and stale-feed episodes (self-healing's incidents);
 *  - workers past their time limit ([com.optionslab.app.work.WatchWorkers]); Android's battery saver; memory-trim callbacks.
 *
 * Its tuner ([tick], at most every [TICK_MS]) lengthens a NON-critical cadence where stalls cluster - the Brain card's
 * consensus refresh, the move recorder's flush, the findings log's write - within their bounds, and restores it after three
 * healthy days; each change is a finding and a diary line. The order path, stops and the stream's tick consumer are never
 * touched. Under memory pressure the caches that are safe to drop are dropped ([trim]).
 *
 * Kept in noBackupFilesDir/growth/health.txt (never the settings vault: nothing here reads a setting, so the order path's
 * "no settings read" holds; never a backup). Class and method names and numbers only - never a message, a symbol or a key.
 */
object AppHealth {
    private const val DIR = "growth"
    private const val FILE = "health.txt"
    /** The tuner and the samples (battery saver, the order path's median) at most this often. */
    const val TICK_MS = 10 * 60_000L
    /** A change is written at most this long after it happened (a crash writes what it can at once through [Diag]). */
    private const val SAVE_DELAY_MS = 60_000L

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var book = HealthBook.Book()
    @Volatile private var loaded = false
    private val saving = AtomicBoolean(false)
    private val tickedAt = java.util.concurrent.atomic.AtomicLong(0L)
    /** One write of the file at a time (the coalesced save and a tick share one ".tmp"). */
    private val writeLock = Any()
    @Volatile private var trimmedAt = 0L

    /** The cadences now, by key (memory: one map read; the readers never wait). */
    @Volatile private var cadences: Map<String, Long> = emptyMap()

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch { runCatching { ensureLoaded(); readExits() } }
        // 12 Oct: one look a minute and a half after the start (the app's start time, last night's budget), market watch or not.
        // (Never in the unit tests' process: a tick a test did not ask for would change another test's state.)
        if (android.os.Build.FINGERPRINT != "robolectric") scope.launch { delay(90_000L); runCatching { tick(null) } }
    }

    private fun file(): File? = appContext?.let { File(File(it.noBackupFilesDir, DIR), FILE) }

    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val read = runCatching { HealthBook.decode(file()?.takeIf { it.isFile }?.readText()) }.getOrDefault(HealthBook.Book())
            // What was seen in the moment before the file was read is added to it (counted once).
            book = merge(read, book)
            cadences = book.tuned.mapValues { (k, t) -> HealthTuner.bounded(k, t.ms) }
            loaded = true
        }
    }

    /** [read] (the file) with [early] (seen before it was read): early days' counts added in. */
    private fun merge(read: HealthBook.Book, early: HealthBook.Book): HealthBook.Book {
        if (early == HealthBook.Book()) return read
        var b = read
        for (d in early.days) {
            for (s in d.samples) b = HealthBook.stall(b, s.atMs, s.ms, s.where)
            repeat(d.reconnects) { b = HealthBook.reconnect(b, d.day.atTime(12, 0).atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()) }
        }
        return HealthBook.exits(b, early.exits)
    }

    private fun mutate(f: (HealthBook.Book) -> HealthBook.Book) {
        synchronized(lock) { book = f(book) }
        saveSoon()
    }

    private fun saveSoon() {
        if (appContext == null || !saving.compareAndSet(false, true)) return
        scope.launch {
            try { delay(SAVE_DELAY_MS); saveNow() } finally { saving.set(false) }
        }
    }

    /** Written now (a worker thread). */
    fun saveNow() {
        val f = file() ?: return
        synchronized(writeLock) {
            // Encoded under the write lock, so the last write is always the newest book.
            val text = synchronized(lock) { if (!loaded) return; HealthBook.encode(book) }
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }

    /** What is kept now (the diagnostics, Jarvis, the weekly report). */
    fun book(): HealthBook.Book = book

    /** The interval now for a tunable cadence ([HealthTuner.TUNABLES]); its normal value when not lengthened. Memory only. */
    fun cadenceMs(key: String): Long = cadences[key] ?: HealthTuner.defaultMs(key)

    // ---- what is recorded (each call cheap, from any thread; never the order path's own work) --------------------------------

    /** The screen watchdog saw the main thread stuck [ms] in [where] (class and method names). */
    fun stall(ms: Long, where: String?) {
        if (ms < HealthBook.STALL_MS) return
        val now = System.currentTimeMillis()
        mutate { HealthBook.stall(it, now, ms, where) }
    }

    /** A worker ran past its time limit. */
    fun overrun(worker: String) { val now = System.currentTimeMillis(); mutate { HealthBook.overrun(it, now, worker) } }

    /** The price stream dropped a live connection (it reconnects by itself). */
    fun reconnect() { val now = System.currentTimeMillis(); mutate { HealthBook.reconnect(it, now) } }

    /** Self-healing found the prices stale (an incident started). */
    fun stale() { val now = System.currentTimeMillis(); mutate { HealthBook.stale(it, now) } }

    /**
     * Android asked for memory back ([level]: ComponentCallbacks2's): counted, and the caches that are safe to drop are dropped
     * - the short-answer readings and the move recorder's parsed summaries (both made again when next needed). The screen
     * just hidden (UI_HIDDEN) is not memory pressure.
     */
    fun trim(level: Int) {
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        val now = System.currentTimeMillis()
        mutate { HealthBook.trim(it, now) }
        runCatching { com.optionslab.ira.ShortAnswer.clearCache() }
        runCatching { MoveRecorder.dropCaches() }
        if (now - trimmedAt > TICK_MS) {
            trimmedAt = now
            runCatching { Diag.record("health", "memory trim (level $level): the answer and big-move summary caches dropped") }
        }
    }

    // ---- ANRs and crashes of the previous runs ---------------------------------------------------------------------------------

    /** Android's exit records (11+) or the app's own crash file, read once at start (a worker thread). */
    internal fun readExits() {
        val ctx = appContext ?: return
        val seen = ArrayList<HealthBook.Exit>()
        if (android.os.Build.VERSION.SDK_INT >= 30) runCatching {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return@runCatching
            for (x in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 16)) {
                val kind = when (x.reason) {
                    android.app.ApplicationExitInfo.REASON_ANR -> HealthBook.ExitKind.ANR
                    android.app.ApplicationExitInfo.REASON_CRASH -> HealthBook.ExitKind.CRASH
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> HealthBook.ExitKind.NATIVE_CRASH
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> HealthBook.ExitKind.LOW_MEMORY
                    else -> null
                } ?: continue
                // An ANR's description is Android's own ("Input dispatching timed out"); a crash's place comes from the app's own
                // crash file (frames only) - its description can carry an exception's message, which is never kept.
                val where = if (kind == HealthBook.ExitKind.ANR) words(x.description) else crashWhere(ctx, x.timestamp) ?: "unknown place"
                seen += HealthBook.Exit(x.timestamp, kind, where)
            }
        }
        if (android.os.Build.VERSION.SDK_INT < 30) runCatching {
            val f = File(ctx.filesDir, com.optionslab.app.IraAlgoApp.CRASH_FILE)
            if (f.isFile) seen += HealthBook.Exit(f.lastModified(), HealthBook.ExitKind.CRASH, crashWhere(ctx, null) ?: "unknown place")
        }
        if (seen.isNotEmpty()) mutate { HealthBook.exits(it, seen) }
    }

    /** Letters, digits and spaces only, short: Android's own words for an ANR. */
    private fun words(s: String?): String = s.orEmpty().replace(Regex("[^A-Za-z0-9 .]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifEmpty { "unknown place" }

    /** The app's own first frame in its sealed crash file (class.method), when the file is from about [atMs] (null: any). */
    private fun crashWhere(ctx: Context, atMs: Long?): String? = runCatching {
        val f = File(ctx.filesDir, com.optionslab.app.IraAlgoApp.CRASH_FILE)
        if (!f.isFile || (atMs != null && kotlin.math.abs(f.lastModified() - atMs) > 120_000L)) return@runCatching null
        val text = com.optionslab.app.security.Vault.readFile(f)?.toString(Charsets.UTF_8) ?: return@runCatching null
        val frame = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("at com.optionslab.") } ?: return@runCatching null
        frame.removePrefix("at ").substringBefore('(').split('.').takeLast(2).joinToString(".")
    }.getOrNull()

    // ---- the tuner and the samples ---------------------------------------------------------------------------------------------

    /**
     * At most every [TICK_MS] (memory check until then; a worker thread): the battery saver and the order path's median
     * sampled, the tuner run ([HealthTuner.evaluate]), each change applied (the cadence read by its reader), told as a finding
     * and a diary line, and the book written. Returns the changes. [force]: now (tests).
     */
    fun tick(context: Context? = null, force: Boolean = false): List<HealthTuner.Adjust> {
        val now = System.currentTimeMillis()
        // Claimed with a CAS: two threads looking at once never both tick.
        val last = tickedAt.get()
        if (!force && now - last < TICK_MS) return emptyList()
        if (force) tickedAt.set(now) else if (!tickedAt.compareAndSet(last, now)) return emptyList()
        if (context != null && appContext == null) appContext = context.applicationContext
        ensureLoaded()
        val ctx = context ?: appContext
        val saver = runCatching { ctx?.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode }.getOrNull()
        val order = runCatching { OrderTiming.signalToSent() }.getOrNull()
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.engine.IST) }
        val changes = synchronized(lock) {
            var b = book
            if (saver != null) b = HealthBook.saver(b, now, saver)
            if (order != null) b = HealthBook.orderPath(b, today, order.first, order.second)
            // S14: while learning is frozen the samples are kept and nothing is retuned.
            val (nb, adj) = if (com.optionslab.app.ira.LearningFreeze.holds()) b to emptyList<HealthTuner.Adjust>() else HealthTuner.evaluate(b, today)
            book = nb
            cadences = nb.tuned.mapValues { (k, t) -> HealthTuner.bounded(k, t.ms) }
            adj
        }
        for (a in changes) {
            runCatching { Diag.record("health", "self-tuning: " + a.words()) }
            runCatching { SmartWorkers.found("health tuner", Findings.Kind.OTHER, Findings.ALL, words = a.words(), strength = 40, ttlMs = 24 * 3_600_000L) }
        }
        runCatching { healthProblems(today) }
        saveNow()
        return changes
    }

    /**
     * 12 Oct: what was measured and never told - a screen freeze of 1 s or more with where it was stuck, a slow app start, the
     * background budget spent early, slow paper orders, a failure named only by a shrunk class - as open problems ([ProblemsHost],
     * the Automation card). Memory only (the health book, the speed watchdog, the budget's meters, the order timings, the diary's
     * counts). Each problem stays open until it is no longer seen for a day and a half.
     */
    internal fun healthProblems(today: LocalDate, now: java.time.LocalDateTime = runCatching { Market.now().toLocalDateTime() }.getOrElse { java.time.LocalDateTime.now() }) {
        val b = book
        val found = listOfNotNull(
            com.optionslab.ira.HealthChecks.stalls(b.day(today)),
            com.optionslab.ira.HealthChecks.appStart(Speed.startMs, Speed.startSlowest),
            runCatching { com.optionslab.app.work.BackgroundBudget.spentNow() }.getOrNull()?.let { (k, ms) ->
                com.optionslab.ira.HealthChecks.budget(k, ms, com.optionslab.ira.BudgetRule.NIGHT_CPU_MS, now) },
            runCatching { OrderTiming.paperDecisionToSent() }.getOrNull().let { com.optionslab.ira.HealthChecks.paperOrders(it?.first, it?.second ?: 0) },
            com.optionslab.ira.HealthChecks.unreadable(runCatching { Diag.errors.of(today).unreadable }.getOrDefault(emptyMap())),
        )
        for (f in found) ProblemsHost.report(f.key, "App health", f.severity, f.text, f.fix)
    }

    /**
     * "Today's changes" undo (S13): [key]'s cadence back to normal now (the tuner may lengthen it again on a later day if stalls
     * cluster there again). What to say, or null when it was not lengthened.
     */
    fun restoreCadence(key: String): String? {
        ensureLoaded()
        val was = synchronized(lock) {
            val t = book.tuned[key] ?: return null
            book = book.copy(tuned = book.tuned - key)
            cadences = book.tuned.mapValues { (k, x) -> HealthTuner.bounded(k, x.ms) }
            t
        }
        saveSoon()
        runCatching { Diag.record("health", "undone by you: ${HealthTuner.words(key)} back to normal") }
        return "${HealthTuner.words(key)} is back to every ${HealthBook.msWords(HealthTuner.defaultMs(key))} (it was every ${HealthBook.msWords(was.ms)})."
    }

    // ---- words -----------------------------------------------------------------------------------------------------------------

    private val HHMM = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    private fun hhmm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(com.optionslab.engine.IST).format(HHMM)
    private fun today(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now(com.optionslab.engine.IST) }

    /** The diagnostics' "Health:" line. */
    fun diagLine(): String { ensureLoaded(); return HealthBook.diagLine(book, today(), ::hhmm) }

    /** "Is the app healthy?" */
    fun sayHealthy(): String { ensureLoaded(); return HealthBook.sayHealthy(book, today()) }

    /** "Why was the app slow?" */
    fun sayWhySlow(): String { ensureLoaded(); return HealthBook.sayWhySlow(book, today(), ::hhmm) }

    /** TEST ONLY: nothing kept, normal cadences. */
    internal fun resetForTest() {
        synchronized(lock) { book = HealthBook.Book(); cadences = emptyMap(); loaded = true }
        tickedAt.set(0L); trimmedAt = 0L
    }

    /** TEST ONLY: [b] as what is kept. */
    internal fun setForTest(b: HealthBook.Book) {
        synchronized(lock) { book = b; cadences = b.tuned.mapValues { (k, t) -> HealthTuner.bounded(k, t.ms) }; loaded = true }
    }
}
