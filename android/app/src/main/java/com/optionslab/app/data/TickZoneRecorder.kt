package com.optionslab.app.data

import android.content.Context
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.ira.LiveRecord
import com.optionslab.ira.TickRest
import com.optionslab.ira.TickZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

/**
 * The tick zone (Data Brain L1b, 12 Oct; the formulas and row shapes are [TickZone]'s): once a second, the order flow of a
 * fixed watch-list - BANKNIFTY and NIFTY near futures, their ATM +/-3 options of the current expiry and India VIX - copied
 * from what [KiteStream] already holds in memory, and once a minute each instrument's order-flow aggregates.
 *
 * Three jobs, none on the order or tick path (the stream's socket thread is not touched: nothing is added to it):
 *  - The layout (IO, every 30 s): the watch-list from the day's lists, followed under [OWNER] in the stream. [KiteStream]
 *    subscribes every instrument in Kite's full mode (five-level book, totals, OI), so nothing else changes there; this
 *    owner never starts or keeps the stream on by itself, and the indices, positions and live exits stay first under
 *    Kite's 3000-instrument cap ([com.optionslab.ira.StreamHealth.cap]). No trading subscription is changed.
 *  - The sampler (Default, each second while the F&O market is open and recording is on): reads each instrument's last
 *    tick from memory, copies it into a [TickZone.Snap] and adds the second to a bounded queue ([TickZone.SecondQueue]:
 *    a short lock, never a wait, never the settings vault, a file or the network; past 90 seconds waiting (ten minutes
 *    before 14 Oct) the oldest second is dropped and counted).
 *  - The writer (IO, once a minute; every 5 s before 14 Oct): turns the seconds into rows ([TickZone.TICKS_FILE]), feeds
 *    each instrument's [TickZone.Flow] and writes the closed minutes ([TickZone.FLOW_FILE]); every second not recorded
 *    becomes a gap run. The rows are the same as with the 5 s writer while each write ends within about 30 s (the queue holds
 *    90 seconds; a slower write drops the oldest seconds as gaps); a process killed between two writes loses at most the
 *    last minute's seconds, which is accepted.
 *
 * Battery (14 Oct, Boss: "my phone's battery drains in 3-4 hours"; [TickRest]): the three loops ran every second, 5 s and
 * 30 s all night and all weekend. Outside NSE's hours (nights, weekends, holidays, from the Market calendar) each now rests
 * until a minute before the next open (in one-minute steps that only re-read the clock). The layout filters the day's option list once a day and rebuilds the watch-list
 * only when the money moves a strike (or the day, the futures or the narrowing change). Under Android's battery saver or
 * a low battery not charging, it records the futures, VIX and ATM±1 only (a diary line says so); it never stops for it.
 *
 * Files sit beside the live recorder's in noBackupFilesDir/recordings/YYYY-MM-DD/. Raw tick files are deleted after
 * [TickZone.KEEP_TICK_DAYS] days (or over [TickZone.TICK_TOTAL_CAP_BYTES]); the minute files follow [LiveRecorder.keepDays].
 * A day's tick file stops at [TickZone.TICK_DAY_BUDGET_BYTES]. Recording follows the live recorder's switch. It never
 * places, changes, delays or refuses an order: every step is caught, and a failure is at most a gap.
 */
object TickZoneRecorder {
    /** The stream owner its instruments are followed under (never wakes or keeps the stream by itself: [KiteStream.needed]). */
    const val OWNER = "tickzone"
    private const val DIR = "recordings"

    @Volatile private var root: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = TickZone.SecondQueue()
    private val writeLock = Mutex()
    @Volatile private var jobs: List<Job> = emptyList()

    /** The watch-list now (replaced whole by the layout; the sampler reads the reference). */
    @Volatile private var list: List<TickZone.Inst> = emptyList()
    /** The last second sampled (memory; the card's "every second" and the next second's gap check). */
    @Volatile var lastSec: Long? = null
        private set

    fun enabled(): Boolean = !com.optionslab.app.BuildConfig.GOLD

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (!enabled()) return
        appContext = context.applicationContext
        root = File(context.applicationContext.noBackupFilesDir, DIR)
        if (jobs.any { it.isActive }) return
        jobs = listOf(
            scope.launch { while (true) { if (!testOff) runCatching { layout() }; delay(TickRest.LAYOUT_MS); layoutRest() } },
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { sampler() },
            scope.launch {
                while (true) {
                    delay(TickRest.DRAIN_MS)
                    if (!testOff) runCatching { drain(System.currentTimeMillis()) }
                    // The day's run written and closed, nothing waiting: asleep until the next open.
                    if (queue.size() == 0 && !runOpen) restUntilOpen()
                }
            },
        )
    }

    /** Milliseconds to a minute before NSE's next open (0 in market hours, or when it cannot be told). */
    internal fun untilOpenMs(): Long = runCatching { Market.untilOpenMs(leadMs = 60_000L) }.getOrDefault(0L)

    /**
     * Outside market hours: asleep until a minute before the next open (Market's calendar), in steps of at most a minute
     * that only re-read the clock ([com.optionslab.ira.RestClock.step]: a wait's clock stops in deep sleep), not a look
     * each second.
     */
    private suspend fun restUntilOpen() { while (true) { val ms = untilOpenMs(); if (ms <= 0) return; delay(com.optionslab.ira.RestClock.step(ms)) } }

    /** The layout's rest outside market hours: the watch-list dropped first, so the next open builds it afresh. */
    private suspend fun layoutRest() {
        if (untilOpenMs() <= 0) return
        if (list.isNotEmpty()) runCatching { follow(emptyList()) }
        lastKey = null
        restUntilOpen()
    }

    /** TEST ONLY: the loops do nothing (the tests sample and drain by hand). Throws unless BuildConfig.DEBUG. */
    @Volatile internal var testOff = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test switch exists only in debug builds" }; field = v }

    // ---- the watch-list ------------------------------------------------------------------------------------------------

    // The layout's own state (the layout loop's coroutine, or a test calling [layout]).
    /** The day and the instrument list's size the options below were filtered from. */
    private var optionsFor: Pair<LocalDate, Int>? = null
    private var optionsCache: Map<String, List<TickZone.Inst>> = emptyMap()
    /** Each index's strikes of its nearest expiry (the money's strike is looked up in these). */
    private var nearStrikes: Map<String, List<Double>> = emptyMap()
    private var lastKey: TickRest.Key? = null
    @Volatile private var narrowed: Boolean? = null

    /** TEST ONLY: battery saver as the test says (null: the phone's). */
    @Volatile internal var saverForTest: Boolean? = null

    /** Android's battery saver, or the battery low and not charging ([TickRest.narrow]). */
    private fun saverNow(): Boolean {
        saverForTest?.let { return it }
        val ctx = appContext ?: return false
        val power = com.optionslab.app.work.Battery.powerSave(ctx)
        val (percent, charging) = com.optionslab.app.work.Battery.state(ctx)
        return TickRest.narrow(power, percent, charging)
    }

    /** The instruments to follow (only with a Zerodha session; otherwise none and the stream owner cleared). */
    internal suspend fun layout() {
        if (root == null) return
        // Only while the stream runs (it is never started for this) and recording is on.
        if (!Broker.loggedIn || KiteStream.status.value == KiteStream.Status.OFF || runCatching { LiveRecorder.on }.getOrDefault(true).not()) {
            if (list.isNotEmpty()) follow(emptyList())
            lastKey = null
            return
        }
        val today = Market.today()
        val futs = runCatching { Kite.nearestFutures(Broker.futures(), today) }.getOrDefault(emptyMap())
        val futures = TickZone.INDICES.mapNotNull { n -> futs[n]?.let { n to TickZone.Inst(it.token, it.tradingSymbol, n, TickZone.Kind.FUT, expiry = it.expiry) } }.toMap()
        val all = runCatching { Broker.cachedInstruments() }.getOrNull().orEmpty()
        // Battery (14 Oct): the day's options filtered once (again only when the day or the list changes), not every 30 s.
        if (optionsFor != today to all.size) {
            optionsCache = TickZone.INDICES.associateWith { n ->
                all.filter { it.name == n && !it.expiry.isBefore(today) && (it.right == Right.CE || it.right == Right.PE) }.map {
                    TickZone.Inst(it.token, it.tradingSymbol, n, if (it.right == Right.CE) TickZone.Kind.CE else TickZone.Kind.PE, it.strike, it.expiry)
                }
            }
            nearStrikes = optionsCache.mapValues { (_, o) -> o.mapNotNull { it.expiry }.minOrNull()?.let { e -> o.filter { it.expiry == e }.mapNotNull { it.strike }.distinct() }.orEmpty() }
            optionsFor = today to all.size
        }
        val options = optionsCache
        // The money from the index's own tick, else its future's.
        val spots = TickZone.INDICES.associateWith { n ->
            Broker.indexToken(n)?.let { KiteStream.seen(it) }?.tick?.last?.takeIf { it > 0 }
                ?: futures[n]?.let { KiteStream.seen(it.token) }?.tick?.last?.takeIf { it > 0 }
        }
        val vix = Broker.indexToken("INDIAVIX")?.let { TickZone.Inst(it, "INDIA VIX", "INDIAVIX", TickZone.Kind.VIX) }
        val narrow = saverNow()
        TickRest.narrowNote(narrowed, narrow)?.let { runCatching { Diag.record("tickzone", it) } }
        narrowed = narrow
        val each = TickRest.eachSide(narrow)
        // Rebuilt only when the money moved a strike, or the day, the futures or the narrowing changed.
        val key = TickRest.Key(today, TickZone.INDICES.associateWith { TickRest.atm(nearStrikes[it].orEmpty(), spots[it]) }, each,
            futures.values.map { it.token } + listOfNotNull(vix?.token))
        if (!TickRest.relayout(lastKey, key)) return
        lastKey = key
        follow(TickZone.watchList(futures, options, spots, vix, each))
    }

    /** Whether the recording is narrowed now (the card; null before the first layout). */
    fun narrowedNow(): Boolean? = narrowed

    private fun follow(l: List<TickZone.Inst>) {
        if (l.map { it.token } != list.map { it.token }) list = l
        KiteStream.want(OWNER, l.map { it.token })
    }

    // ---- the sampler: memory only --------------------------------------------------------------------------------------

    private suspend fun sampler() {
        var prev: Long? = null
        var was = false
        while (true) {
            // Just past each second's start, so the second's ticks are in.
            delay(1_000L - Math.floorMod(System.currentTimeMillis(), 1_000L) + 20L)
            val nowMs = System.currentTimeMillis()
            val sec = nowMs / 1000
            val active = root != null && !testOff && runCatching { LiveRecorder.on && Market.isOpen() }.getOrDefault(false)
            if (active != was) { was = active; runCatching { com.optionslab.app.work.BatteryMeter.meter.set(com.optionslab.ira.LoopMeter.RECORDER, active) } }
            if (!active) {
                prev = null
                // Battery (14 Oct): outside market hours asleep to a minute before the next open, not a look each second;
                // recording switched off, a look every 30 s.
                if (untilOpenMs() > 0) restUntilOpen() else if (!testOff && !runCatching { LiveRecorder.on }.getOrDefault(true)) delay(30_000)
                continue
            }
            if (prev == sec) continue
            runCatching { sample(sec, prev, nowMs) }
            prev = sec
        }
    }

    /** One second copied from the stream's memory into the queue (internal: the tests call it). */
    internal fun sample(sec: Long, prev: Long?, nowMs: Long) {
        val l = list
        val live = KiteStream.status.value == KiteStream.Status.LIVE
        val snaps = ArrayList<TickZone.Snap>(l.size)
        val missing = ArrayList<Pair<Long, String>>()
        for (i in l) {
            val s = KiteStream.seen(i.token)
            when {
                !live -> missing += i.token to "stream down"
                s == null -> missing += i.token to "no tick"
                nowMs - s.at > TickZone.STALE_MS -> missing += i.token to "stale"
                else -> snaps += snap(s, nowMs)
            }
        }
        queue.offer(TickZone.Second(sec, prev, l, snaps, missing))
        lastSec = sec
    }

    private fun snap(s: KiteStream.Seen, nowMs: Long): TickZone.Snap {
        val t = s.tick
        fun lv(x: List<com.optionslab.engine.KiteTicks.Level>?) = x.orEmpty().take(5).map { TickZone.Level(it.price, it.qty, it.orders) }
        val full = t.buyQty != null
        return TickZone.Snap(
            t.token, t.last, t.volume.takeIf { full }, t.avgPrice, t.oi.takeIf { full && it > 0 }, t.buyQty, t.sellQty, t.lastQty,
            t.lastTradeTime, t.exchangeTime, lv(t.depth?.bids), lv(t.depth?.asks), (nowMs - s.at).coerceAtLeast(0),
        )
    }

    // ---- the writer ----------------------------------------------------------------------------------------------------

    // The writer's own state (under [writeLock]).
    private var day: LocalDate? = null
    private var flows = HashMap<Long, TickZone.Flow>()
    private val insts = HashMap<Long, TickZone.Inst>()
    private var gaps = TickZone.Gaps()
    private var written: Long? = null
    private var listWritten: List<TickZone.Inst>? = null
    private var fullNoted: LocalDate? = null
    /** A run is being written (the last second written is not closed yet): the writer does not rest yet. */
    @Volatile private var runOpen = false

    /** Everything queued turned into rows and written; minutes and gap runs closed once the sampler has stopped. */
    internal suspend fun drain(nowMs: Long) = writeLock.withLock {
        val (items, dropped) = queue.drain()
        val ticks = ArrayList<String>()
        val aggs = ArrayList<String>()
        fun flowOf(t: Long) = flows.getOrPut(t) { TickZone.Flow(t) }
        fun aggLine(a: TickZone.Agg?) { if (a != null) aggs += TickZone.agg(a, insts[a.token]) }
        fun flush(d: LocalDate) {
            if (ticks.isNotEmpty()) writeTicks(d, ticks)
            if (aggs.isNotEmpty()) writeFlow(d, aggs)
            ticks.clear(); aggs.clear()
        }
        fun lost(range: LongRange, why: String, l: List<TickZone.Inst>) {
            for (sec in range) {
                gaps.miss(0L, sec, why)?.let { ticks += TickZone.gap(it) }
                for (i in l) aggLine(flowOf(i.token).miss(sec))
            }
        }
        val droppedLeft = dropped.toMutableList()
        for (s in items) {
            val d = TickZone.dayOf(s.sec)
            if (d != day) {
                day?.let { old -> endOfRun().let { (g, a) -> ticks += g; aggs += a }; flush(old) }
                day = d; flows = HashMap(); gaps = TickZone.Gaps(); written = null; listWritten = null
                // Memory (14 Oct): yesterday's instruments go with the day (this list names them again at once).
                insts.clear()
            }
            if (s.list !== listWritten) {
                ticks += TickZone.instruments(s.sec, s.list); listWritten = s.list
                s.list.forEach { insts[it.token] = it }
            }
            // Seconds dropped from the full queue before this one, then seconds the sampler did not reach.
            droppedLeft.filter { it.last < s.sec && TickZone.dayOf(it.first) == d }.forEach { lost(it, "queue full", s.list) }
            droppedLeft.removeAll { it.last < s.sec }
            val p = s.prevSec
            if (p != null && s.sec - p > 1 && TickZone.dayOf(p) == d) lost(p + 1 until s.sec, "not sampled", s.list)
            if (s.list.isNotEmpty()) gaps.ok(0L)?.let { ticks += TickZone.gap(it) }
            for (x in s.snaps) {
                ticks += TickZone.tick(s.sec, x)
                gaps.ok(x.token)?.let { ticks += TickZone.gap(it) }
                aggLine(flowOf(x.token).add(s.sec, x))
            }
            for ((tok, why) in s.missing) {
                gaps.miss(tok, s.sec, why)?.let { ticks += TickZone.gap(it) }
                aggLine(flowOf(tok).miss(s.sec))
            }
            if (s.list.isEmpty()) gaps.miss(0L, s.sec, "no watch-list (Zerodha not logged in)")?.let { ticks += TickZone.gap(it) }
            written = s.sec
        }
        // The sampler stopped (the close, recording off, a pause): the last minute and the open runs closed.
        val cur = day
        if (cur != null) {
            val last = written
            val nowSec = nowMs / 1000
            if (items.isEmpty() && last != null && nowSec - last > 90) {
                endOfRun().let { (g, a) -> ticks += g; aggs += a }
                written = null
                // Memory audit (14 Oct, item 6): the run is closed (the close, recording off, a pause): instruments no longer
                // on the watch-list are let go - their minute closed above; one that comes back starts afresh.
                pruneAfterRun(list.map { it.token }.toSet())
            } else for (f in flows.values) aggLine(f.roll(nowSec - 5))
            flush(cur)
        }
        runOpen = written != null
    }

    /** Every instrument's flow and name not in [keep] let go (the writer's thread, after a run closed). */
    private fun pruneAfterRun(keep: Set<Long>) {
        flows.keys.retainAll(keep)
        // Names let go: the next second written names its watch-list again (even the same list coming back), so no minute
        // row is ever written without its instrument's name.
        if (insts.keys.retainAll(keep)) listWritten = null
    }

    /** The writer's instruments held now (flows, names): the memory figures and the tests. */
    internal fun heldForTest(): Pair<Int, Int> = flows.size to insts.size

    /** The open gap runs and each instrument's last minute, closed (rows for the tick and minute files). */
    private fun endOfRun(): Pair<List<String>, List<String>> {
        val g = gaps.closeAll().map { TickZone.gap(it) }
        val a = flows.values.mapNotNull { it.close() }.map { TickZone.agg(it, insts[it.token]) }
        return g to a
    }

    // ---- the files -----------------------------------------------------------------------------------------------------

    private fun dayDir(d: LocalDate): File? = root?.let { File(it, d.toString()) }

    private fun gz(text: String): ByteArray = java.io.ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(text.toByteArray(Charsets.UTF_8)) } }.toByteArray()

    private fun app(): String = runCatching { com.optionslab.app.BuildConfig.VERSION_NAME }.getOrDefault("")

    private var prunedOn: LocalDate? = null

    private fun writeTicks(d: LocalDate, lines: List<String>) {
        val dir = dayDir(d) ?: return
        dir.mkdirs()
        if (prunedOn != d) { prunedOn = d; runCatching { prune(d) } }
        val f = File(dir, TickZone.TICKS_FILE)
        if (TickZone.overTickBudget(f.length())) {
            if (fullNoted != d) { fullNoted = d; writeFlow(d, listOf(TickZone.note(written ?: 0L, "ticks", "the day's tick file is full; minute rows go on"))) }
            return
        }
        val head = if (f.exists()) emptyList() else listOf(TickZone.header(TickZone.TICKS_FILE, d, app()))
        java.io.FileOutputStream(f, true).use { it.write(gz((head + lines).joinToString("") { l -> l + "\n" })) }
    }

    private fun writeFlow(d: LocalDate, lines: List<String>) {
        val dir = dayDir(d) ?: return
        dir.mkdirs()
        val f = File(dir, TickZone.FLOW_FILE)
        val head = if (f.exists()) emptyList() else listOf(TickZone.header(TickZone.FLOW_FILE, d, app()))
        java.io.FileOutputStream(f, true).use { it.write(gz((head + lines).joinToString("") { l -> l + "\n" })) }
    }

    /** Raw tick files past [TickZone.KEEP_TICK_DAYS] days (or over the cap) deleted; never today's. */
    internal fun prune(today: LocalDate) {
        val days = LiveRecorder.days()
        val sizes = days.mapNotNull { (d, dir) -> File(dir, TickZone.TICKS_FILE).takeIf { it.exists() }?.let { LiveRecord.DayDir(d, it.length()) } }
        for (d in TickZone.pruneTicks(sizes, today)) days.firstOrNull { it.first == d }?.second?.let { File(it, TickZone.TICKS_FILE).delete() }
    }

    /** The card's figures (lists the folder). Off the main thread. */
    fun status(): TickZone.Status {
        val days = LiveRecorder.days()
        val today = runCatching { Market.today() }.getOrNull()
        val todayFile = days.firstOrNull { it.first == today }?.second?.let { File(it, TickZone.TICKS_FILE) }
        val bytes = todayFile?.takeIf { it.exists() }?.length() ?: 0L
        return TickZone.Status(list.size, bytes, days.count { File(it.second, TickZone.TICKS_FILE).exists() }, queue.dropped, lastSec,
            TickZone.overTickBudget(bytes))
    }

    /** Erase what is held in memory (the files go with the recordings folder: [LiveRecorder.wipe]). */
    fun wipe() {
        queue.drain()
        lastSec = null
        // Off the caller's thread (erase-everything runs on the main thread); kept so a test can wait for it (15 Oct: a reset
        // still pending landed after a test's own drain and emptied its flows - TickZoneRecorderTest was flaky). The names
        // (insts) go too, as the day's state does.
        wiping = scope.launch { writeLock.withLock { flows = HashMap(); gaps = TickZone.Gaps(); day = null; written = null; listWritten = null; insts.clear() } }
    }

    /** The last erase's reset, still running or done (tests wait for it before their own state is set). */
    @Volatile private var wiping: Job? = null

    /** TEST ONLY: recording into [context]'s folder without the loops, with [l] as the watch-list. */
    internal fun attachForTest(context: Context, l: List<TickZone.Inst>) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        // An erase's reset still pending (LiveRecorder.resetForTest wipes) lands first, never after the test's own drain.
        kotlinx.coroutines.runBlocking { wiping?.join() }
        testOff = true
        root = File(context.applicationContext.noBackupFilesDir, DIR)
        list = l
        // Anything the app's own loops queued before the switch went off is forgotten.
        queue.drain(); lastSec = null
        day = null; flows = HashMap(); gaps = TickZone.Gaps(); written = null; listWritten = null; insts.clear(); fullNoted = null; prunedOn = null
        lastKey = null; optionsFor = null; narrowed = null; saverForTest = null; runOpen = false
    }

    /** TEST ONLY: not recording, and the writer's state forgotten. */
    internal suspend fun detachForTest() {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        queue.drain(); lastSec = null; list = emptyList(); root = null
        writeLock.withLock { flows = HashMap(); gaps = TickZone.Gaps(); day = null; written = null; listWritten = null; insts.clear(); fullNoted = null; prunedOn = null }
        lastKey = null; optionsFor = null; narrowed = null; saverForTest = null; runOpen = false
    }

    /** TEST ONLY: the watch-list followed now. */
    internal fun listForTest(): List<TickZone.Inst> = list

    /** TEST ONLY: [l] as the watch-list from now (the layout's own change). Throws unless BuildConfig.DEBUG. */
    internal fun setListForTest(l: List<TickZone.Inst>) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; list = l }
}
