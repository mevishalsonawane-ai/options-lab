package com.optionslab.app.data

import android.content.Context
import com.optionslab.engine.Kite
import com.optionslab.engine.KiteTicks
import com.optionslab.engine.Right
import com.optionslab.ira.OrderFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live order flow (Boss, 9 Oct 2026; the logic is [OrderFlow]'s): the near futures of NIFTY, BANKNIFTY, FINNIFTY and
 * MIDCPNIFTY, the ATM ±2 calls and puts of the nearest expiry of the index in focus (the charted one, else BANKNIFTY), and
 * the MCX near futures the MCX bots trade while one is on - about 20 instruments, all in Kite's full mode ([KiteStream]
 * subscribes every instrument in full mode; this set stays small: [OrderFlow.FULL_MODE_CAP]).
 *
 * Off the main thread throughout: the stream's socket thread only queues the ticks of these instruments ([offer]: a set
 * lookup and a queue add); one thread of its own takes them every 250 ms, updates the flow and publishes the reads
 * ([reads], coalesced: at most four times a second, memory only - the screens read nothing else). Once a second it hands the
 * closed 1-second bars to the day's recorder (a plain gzip file, written once a minute) and the reads to [FlowGate]'s
 * shadow log. Nothing here places, changes or blocks an order: the flow is shown, logged, and used only by [FlowGate].
 * Never in the gold build (it only talks).
 */
object OrderFlowLive {
    /** The stream owner its instruments are followed under (it never keeps the stream awake by itself: [KiteStream.needed]). */
    const val OWNER = "orderflow"

    private val board = OrderFlow.Board()
    @Volatile private var watched: Set<Long> = emptySet()
    private val queue = ConcurrentLinkedQueue<Pair<List<KiteTicks.Tick>, Long>>()
    private val pumping = AtomicBoolean(false)
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "order-flow").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)
    @Volatile private var appContext: Context? = null
    private var layoutJob: Job? = null

    private val enabled: Boolean get() = !com.optionslab.app.BuildConfig.GOLD

    private val _reads = MutableStateFlow<Map<String, OrderFlow.Read>>(emptyMap())
    /** Each underlying's latest read (published at most every 250 ms); the screens collect this and nothing else. */
    val reads: StateFlow<Map<String, OrderFlow.Read>> = _reads

    /** The index the option side follows: the charted one (set by the Chart), else BANKNIFTY. */
    @Volatile var focus: String = "BANKNIFTY"
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!enabled || layoutJob != null) return
        // Every 30 s: the instruments to follow (only while the stream runs), the paper results and the shadow log's flush.
        layoutJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (true) {
                if (testOff) { delay(30_000); continue }
                runCatching { if (KiteStream.status.value != KiteStream.Status.OFF) relayout() else if (watched.isNotEmpty()) clear() }
                runCatching { FlowGate.settle() }
                delay(30_000)
            }
        }
    }

    /** TEST ONLY: the layout loop does nothing (tests lay out by hand, [layoutForTest]). Throws unless BuildConfig.DEBUG. */
    @Volatile internal var testOff = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test switch exists only in debug builds" }; field = v }

    /** The Chart shows [underlying]: its ATM options are followed from the next layout (at once). */
    fun focus(underlying: String) {
        val u = underlying.uppercase(Locale.ENGLISH)
        if (u !in OrderFlow.INDICES || u == focus) return
        focus = u
        if (enabled && !testOff) CoroutineScope(Dispatchers.IO).launch { runCatching { relayout() } }
    }

    // ---- what to follow ------------------------------------------------------------------------------------------------

    private fun clear() {
        board.layout(emptyList()); watched = emptySet()
        KiteStream.want(OWNER, emptyList())
    }

    /** The futures, the focus index's ATM ±2 options and the MCX bots' futures, at most [OrderFlow.FULL_MODE_CAP]. */
    private suspend fun relayout() {
        if (!Broker.loggedIn) { if (watched.isNotEmpty()) clear(); return }
        val today = Market.today()
        val out = ArrayList<OrderFlow.Board.Entry>()
        val futs = runCatching { Kite.nearestFutures(Broker.futures(), today) }.getOrDefault(emptyMap())
        for (n in OrderFlow.INDICES) futs[n]?.let { out += OrderFlow.Board.Entry(it.token, n, OrderFlow.Role.FUTURE) }
        // The focus index's options: ATM from the index's tick (else its future's), recentred every 5 minutes at most (a new
        // focus at once) - the full-mode set stays small and steady (HUNT R7).
        val f = focus
        val now = System.currentTimeMillis()
        val keep = centred.takeIf { it.isNotEmpty() && centredFor == f && now - centredAt < RECENTRE_MS }
        if (keep != null) out += keep
        val spot = if (keep != null) null else Broker.indexToken(f)?.let { KiteStream.tick(it, 60_000)?.last } ?: futs[f]?.let { KiteStream.tick(it.token, 60_000)?.last }
        if (spot != null && spot > 0) {
            val opts = (Broker.cachedInstruments() ?: runCatching { Broker.instruments() }.getOrNull()).orEmpty()
                .filter { it.name == f && !it.expiry.isBefore(today) }
            val expiry = opts.minOfOrNull { it.expiry }
            if (expiry != null) {
                val chain = opts.filter { it.expiry == expiry }
                for (k in OrderFlow.atmStrikes(chain.map { it.strike }, spot)) for (r in listOf(Right.CE, Right.PE))
                    chain.firstOrNull { it.strike == k && it.right == r }?.let {
                        out += OrderFlow.Board.Entry(it.token, f, if (r == Right.CE) OrderFlow.Role.CE else OrderFlow.Role.PE, k)
                    }
                centred = out.filter { it.role != OrderFlow.Role.FUTURE }; centredFor = f; centredAt = now
            }
        }
        // The MCX bots' near futures, while one is on.
        val mcx = runCatching { mcxNames() }.getOrDefault(emptyList())
        if (mcx.isNotEmpty()) {
            val all = runCatching { McxMarket.cached() }.getOrDefault(emptyList())
            for (n in mcx) com.optionslab.engine.mcx.McxInstruments.futures(all, n, today, 1).firstOrNull()?.takeIf { it.token > 0 }
                ?.let { out += OrderFlow.Board.Entry(it.token, n, OrderFlow.Role.FUTURE) }
        }
        follow(out.distinctBy { it.token }.take(OrderFlow.FULL_MODE_CAP))
        // The trap guard's time windows: each index's session (an expiry day's last hour, the day's event minutes), MCX's hours.
        runCatching { sessions(today, mcx) }
    }

    /** The option set's last centring (5 minutes kept), and for which index. */
    @Volatile private var centred: List<OrderFlow.Board.Entry> = emptyList()
    @Volatile private var centredFor: String? = null
    @Volatile private var centredAt = 0L
    private const val RECENTRE_MS = 5 * 60_000L

    /** The time traps' sessions: NSE's with its expiry day and event minutes (RBI 10:00; US data 18:00), MCX's for its bots. */
    private fun sessions(today: LocalDate, mcx: List<String>) {
        val names = runCatching { com.optionslab.app.ira.IraEvents.upcoming(0) }.getOrDefault(emptyList()).filter { it.day == today }.map { it.name }
        val nse = com.optionslab.ira.TrapGuard.eventMinutes(today, names, mcx = false)
        for (n in OrderFlow.INDICES) {
            // The traded index's own expiry day: its last hour from 14:30.
            val expiry = runCatching { Market.upcomingExpiries(n).firstOrNull() == today }.getOrDefault(false)
            board.session(n, com.optionslab.ira.TrapGuard.Session.nse(today).copy(expiryDay = expiry, eventMins = nse))
        }
        if (mcx.isNotEmpty()) {
            // MCX closes 23:30, or 23:55 while New York is on winter time; the EIA reports on Wednesdays and Thursdays.
            val winter = !java.time.ZoneId.of("America/New_York").rules.isDaylightSavings(java.time.Instant.now())
            val s = com.optionslab.ira.TrapGuard.Session.MCX.copy(closeMin = if (winter) 23 * 60 + 55 else 23 * 60 + 30,
                eventMins = com.optionslab.ira.TrapGuard.eventMinutes(today, names, mcx = true))
            for (n in mcx) board.session(n, s)
        }
    }

    /** The commodities the armed MCX bots trade (their futures' flow confirms their entries). */
    private fun mcxNames(): List<String> {
        val names = ArrayList<String>()
        if (McxPaperArms.armed(com.optionslab.engine.mcx.McxEveRules.SOURCE)) names += com.optionslab.engine.mcx.McxEveRules.UNDERLYING
        if (McxPaperArms.armed(com.optionslab.engine.mcx.McxMorningRules.SOURCE)) names += com.optionslab.engine.mcx.McxMorningRules.UNDERLYING
        if (McxPaperArms.armed(com.optionslab.engine.mcx.McxUsSilverRules.SOURCE)) names += com.optionslab.engine.mcx.McxUsSilverRules.UNDERLYING
        if (McxPaperArms.armed(com.optionslab.engine.mcx.McxTrendRules.SOURCE)) names += com.optionslab.engine.mcx.McxTrendRules.LEGS
        return names.distinct()
    }

    private fun follow(entries: List<OrderFlow.Board.Entry>) {
        board.layout(entries)
        watched = entries.map { it.token }.toSet()
        KiteStream.want(OWNER, watched)
    }

    /** The instruments followed now (the Diagnostics line, the tests). */
    fun entries(): List<OrderFlow.Board.Entry> = board.entries()

    // ---- the ticks ------------------------------------------------------------------------------------------------------

    /** From the stream's socket thread: the ticks of the followed instruments are queued (nothing computed here). */
    fun offer(got: List<KiteTicks.Tick>, now: Long) {
        if (!enabled) return
        val w = watched
        if (w.isEmpty()) return
        val mine = got.filter { it.token in w }
        if (mine.isEmpty()) return
        queue.add(mine to now)
        kick()
    }

    private fun kick() {
        if (!pumping.compareAndSet(false, true)) return
        scope.launch {
            try { pump() } finally {
                pumping.set(false)
                if (queue.isNotEmpty()) kick()
            }
        }
    }

    @Volatile private var lastSecond = 0L

    /** Every 250 ms while ticks come: take them, publish the reads; once a second, the recorder and the shadow log. Ends after 10 s of none. */
    private suspend fun pump() {
        var idle = 0
        while (true) {
            val n = drain()
            val now = System.currentTimeMillis()
            if (n > 0) { idle = 0; publish(now) } else if (++idle > 40) return
            if (now - lastSecond >= 1000) { lastSecond = now; runCatching { perSecond(now) } }
            delay(250)
        }
    }

    @Synchronized private fun drain(): Int {
        var n = 0
        while (true) {
            val (ticks, at) = queue.poll() ?: break
            for (t in ticks) if (board.offer(t, at)) n++
        }
        return n
    }

    private fun names(): List<String> = board.entries().map { it.name }.distinct()

    private fun publish(now: Long) {
        val m = LinkedHashMap<String, OrderFlow.Read>()
        for (n in names()) board.read(n, now)?.let { m[n] = it }
        _reads.value = m
    }

    private suspend fun perSecond(now: Long) {
        FlowGate.onReads(_reads.value, now)
        Recorder.collect(board, now)
    }

    // ---- reads ----------------------------------------------------------------------------------------------------------

    /** [underlying]'s read now, fresh from the flow (a decision's read; null: not followed or no tick yet). */
    fun readNow(underlying: String, nowMs: Long = System.currentTimeMillis()): OrderFlow.Read? {
        testRead?.let { return it(underlying) }
        if (!enabled) return null
        drain()
        return board.read(underlying, nowMs)
    }

    /** [underlying]'s buyers' share every 10 s over the last 30 minutes (the detail's mini history). */
    fun history(underlying: String): List<Pair<Long, Int>> = board.history(underlying)

    /** The Diagnostics / order speed line: coverage, the tick rate and Kite's subscription count against its limit. */
    fun coverageLine(nowMs: Long = System.currentTimeMillis()): String {
        if (!enabled) return "Order flow: not in this build"
        val c = board.coverage(nowMs)
        val subs = KiteStream.following()
        return "Order flow: ${c.followed} instrument${if (c.followed == 1) "" else "s"} in full mode (${c.live} ticking) · " +
            "%.1f ticks/s · Kite stream follows %,d of 3,000 (each in full mode; the flow's set capped at %d)".format(Locale.ENGLISH, c.perSec, subs, OrderFlow.FULL_MODE_CAP)
    }

    // ---- the daily recorder (1-second bars, gzip, 30 days) -----------------------------------------------------------------

    /**
     * The flow's 1-second bars of the index futures and the ATM options during NSE hours, one gzip file a day under
     * noBackupFilesDir/orderflow (plain: market data only, never in a backup), written once a minute as a new gzip member,
     * 30 days kept; exported with the market recorder's zip.
     */
    internal object Recorder {
        const val KEEP_DAYS = 30L
        const val HEADER = "epoch_sec,time_ist,token,name,role,strike,mid,last,ofi,buy_vol,sell_vol,vol,depth_imb,queue_imb,buy_sell_ratio,oi\n"
        private val buf = StringBuilder()
        private var lastSec = 0L
        private var lastFlush = 0L
        private var rotated: LocalDate? = null

        fun dir(): File? = appContext?.let { File(it.noBackupFilesDir, "orderflow") }

        private fun d(x: Double) = if (x.isNaN()) "" else "%.4f".format(Locale.ENGLISH, x)

        suspend fun collect(b: OrderFlow.Board, now: Long) {
            val open = testOpen ?: runCatching { Market.isOpen() }.getOrDefault(false)
            if (open) {
                val rows = b.closedAfter(lastSec).filter { (e, _) -> e.role != OrderFlow.Role.FUTURE || e.name in OrderFlow.INDICES }
                for ((e, bar) in rows) {
                    val t = Instant.ofEpochSecond(bar.sec).atZone(com.optionslab.engine.IST).toLocalTime()
                    buf.append(bar.sec).append(',').append(t).append(',').append(e.token).append(',').append(e.name).append(',').append(e.role.name)
                        .append(',').append(if (e.strike > 0) d(e.strike) else "").append(',').append(d(bar.mid)).append(',').append(d(bar.last))
                        .append(',').append(d(bar.ofi)).append(',').append(bar.buyVol).append(',').append(bar.sellVol).append(',').append(bar.vol)
                        .append(',').append(d(bar.depth)).append(',').append(d(bar.queue)).append(',').append(d(bar.ratio)).append(',').append(bar.oi).append('\n')
                }
                rows.maxOfOrNull { it.second.sec }?.let { lastSec = maxOf(lastSec, it) }
            } else lastSec = now / 1000
            if (buf.isNotEmpty() && (now - lastFlush >= 60_000 || buf.length > 512_000)) flush(now)
        }

        /** Writes what is buffered to today's file (a new gzip member) and drops days over [KEEP_DAYS]. */
        suspend fun flush(now: Long = System.currentTimeMillis()) {
            lastFlush = now
            if (buf.isEmpty()) return
            val text = buf.toString(); buf.setLength(0)
            val dir = dir() ?: return
            val day = Market.today()
            withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    val f = File(dir, "$day.csv.gz")
                    val fresh = !f.exists()
                    java.util.zip.GZIPOutputStream(java.io.FileOutputStream(f, true)).use { z ->
                        if (fresh) z.write(HEADER.toByteArray(Charsets.UTF_8))
                        z.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
                if (rotated != day) {
                    rotated = day
                    runCatching { days().filter { it.first.isBefore(day.minusDays(KEEP_DAYS - 1)) }.forEach { it.second.delete() } }
                }
            }
        }

        private val DAY = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.csv\\.gz$")

        /** The kept day files, oldest first. */
        fun days(): List<Pair<LocalDate, File>> = (dir()?.listFiles() ?: emptyArray()).mapNotNull { f ->
            DAY.find(f.name)?.let { m -> runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()?.let { it to f } }
        }.sortedBy { it.first }

        /** TEST ONLY: market hours as given (null: the real clock). */
        @Volatile internal var testOpen: Boolean? = null
    }

    /** The kept order-flow days (date, bytes), newest first. Lists the folder only. */
    fun recordedDays(): List<Pair<LocalDate, Long>> = Recorder.days().reversed().map { it.first to it.second.length() }

    /** Each kept day's gzip file and the shadow log, into the market recorder's export zip (copied as they are). Returns files. */
    fun export(zip: java.util.zip.ZipOutputStream): Int {
        var n = 0
        for ((day, f) in Recorder.days()) {
            zip.putNextEntry(java.util.zip.ZipEntry("orderflow-$day.csv.gz")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); n++
        }
        for (f in FlowGate.files()) if (f.exists()) {
            zip.putNextEntry(java.util.zip.ZipEntry("orderflow-${f.name}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); n++
        }
        return n
    }

    // ---- tests ----------------------------------------------------------------------------------------------------------

    /** TEST ONLY: a read for each underlying, as if from the flow (null in the app, always). Throws unless BuildConfig.DEBUG. */
    @Volatile internal var testRead: ((String) -> OrderFlow.Read?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test read exists only in debug builds" }; field = v }

    /** TEST ONLY: follow [entries] as a layout would (no network). Throws unless BuildConfig.DEBUG. */
    internal fun layoutForTest(entries: List<OrderFlow.Board.Entry>) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test layout exists only in debug builds" }
        follow(entries)
    }

    /** TEST ONLY: take the queued ticks and publish the reads now, at [nowMs]. Throws unless BuildConfig.DEBUG. */
    internal fun pumpForTest(nowMs: Long) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test pump exists only in debug builds" }
        drain(); publish(nowMs)
    }

    /** TEST ONLY: forget everything followed and read. Throws unless BuildConfig.DEBUG. */
    internal fun resetForTest() {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test reset exists only in debug builds" }
        queue.clear(); board.layout(emptyList()); watched = emptySet(); _reads.value = emptyMap(); testRead = null
    }
}
