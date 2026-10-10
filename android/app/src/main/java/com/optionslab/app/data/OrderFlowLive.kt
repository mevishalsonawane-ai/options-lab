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
        MoveRecorder.init(context)
        if (!enabled || layoutJob != null) return
        // Every 30 s: the instruments to follow (only while the stream runs), the paper results and the shadow log's flush.
        layoutJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // The big-move recorder's switch read once here, off the main thread (the stream's checks then read memory).
            runCatching { MoveRecorder.refreshSwitch() }
            while (true) {
                if (testOff) { delay(30_000); continue }
                runCatching { if (KiteStream.status.value != KiteStream.Status.OFF) relayout() else if (watched.isNotEmpty()) clear() }
                // The auction's prior-day profiles and today's early minutes (once a day each), and the gamma regime (5 min).
                if (KiteStream.status.value != KiteStream.Status.OFF) {
                    runCatching { auctionCandles() }
                    runCatching { GammaLive.refreshIfDue() }
                }
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

    /**
     * The futures, the focus index's ATM ±2 options and the MCX bots' futures, at most [OrderFlow.FULL_MODE_CAP]; with the
     * big-move recorder on ([MoveRecorder.on]), also the other of NIFTY / BANKNIFTY's ATM ±2 options, recorded only
     * ([OrderFlow.Role.REC_CE] / [OrderFlow.Role.REC_PE]: never part of a read), at most [com.optionslab.ira.MoveEvents.RECORD_CAP].
     */
    private suspend fun relayout() {
        if (!Broker.loggedIn) { if (watched.isNotEmpty()) clear(); return }
        val today = Market.today()
        val out = ArrayList<OrderFlow.Board.Entry>()
        val futs = runCatching { Kite.nearestFutures(Broker.futures(), today) }.getOrDefault(emptyMap())
        for (n in OrderFlow.INDICES) futs[n]?.let { out += OrderFlow.Board.Entry(it.token, n, OrderFlow.Role.FUTURE) }
        // The focus index's options: ATM from the index's tick (else its future's), recentred every 5 minutes at most (a new
        // focus at once) - the full-mode set stays small and steady (HUNT R7).
        val f = focus
        out += atmOptions(f, today, futs[f]?.token, recordOnly = false)
        // The option the Chart shows (its tape and heatmap only; never part of the read).
        watching?.takeIf { it.name == f || it.name in OrderFlow.INDICES }?.let { out += it }
        // The MCX bots' near futures, while one is on.
        val mcx = runCatching { mcxNames() }.getOrDefault(emptyList())
        if (mcx.isNotEmpty()) {
            val all = runCatching { McxMarket.cached() }.getOrDefault(emptyList())
            for (n in mcx) com.optionslab.engine.mcx.McxInstruments.futures(all, n, today, 1).firstOrNull()?.takeIf { it.token > 0 }
                ?.let { out += OrderFlow.Board.Entry(it.token, n, OrderFlow.Role.FUTURE) }
        }
        // The big-move recorder: the other index's options too (last, so nothing above is ever crowded out).
        val record = runCatching { MoveRecorder.on }.getOrDefault(false)
        val other = com.optionslab.ira.MoveEvents.other(f).takeIf { record && f in com.optionslab.ira.MoveEvents.INDICES }
        if (other != null) out += atmOptions(other, today, futs[other]?.token, recordOnly = true)
        follow(out.distinctBy { it.token }.take(if (record) com.optionslab.ira.MoveEvents.RECORD_CAP else OrderFlow.FULL_MODE_CAP))
        // The trap guard's time windows: each index's session (an expiry day's last hour, the day's event minutes), MCX's hours.
        runCatching { sessions(today, mcx) }
    }

    /** The charted option (its tape and heatmap; null: an index or nothing charted). */
    @Volatile private var watching: OrderFlow.Board.Entry? = null

    /**
     * The Chart shows [symbol] (call off the main thread): an NFO option of a followed index is added to the full-mode set
     * for its tape and heatmap (from the next layout, at once); anything else drops it.
     */
    fun watchSymbol(symbol: String) {
        if (!enabled) return
        val ins = runCatching { Broker.cachedInstruments() }.getOrNull().orEmpty()
        val c = ins.firstOrNull { it.tradingSymbol.equals(symbol.replace(" ", ""), ignoreCase = true) && (it.right == Right.CE || it.right == Right.PE) }
        val next = c?.takeIf { it.name in OrderFlow.INDICES }?.let { OrderFlow.Board.Entry(it.token, it.name, OrderFlow.Role.WATCH, it.strike) }
        if (next == watching) return
        watching = next
        if (!testOff) CoroutineScope(Dispatchers.IO).launch { runCatching { relayout() } }
    }

    /** Whether the charted option is followed (the sheets offer its tape and heatmap). */
    fun watchingName(): String? = watching?.let { w -> board.entries().firstOrNull { it.token == w.token }?.let { w.name } }

    // ---- the auction's candles: the prior day's profile, today's minutes before the stream ------------------------------

    private val priorFor = HashMap<String, LocalDate>()
    private val seededFor = HashMap<String, LocalDate>()
    private val candleTried = HashMap<String, Long>()

    /**
     * Once a day per index (a failure retried after 10 minutes): the prior session's profile from its near future's 1-minute
     * candles (Zerodha's historical read; else the order-flow recorder's file of that day), and today's minutes before the
     * stream began. Call off the main thread.
     */
    private suspend fun auctionCandles() {
        if (!Broker.loggedIn) return
        val today = Market.today()
        val now = System.currentTimeMillis()
        val futs = board.entries().filter { it.role == OrderFlow.Role.FUTURE && it.name in OrderFlow.INDICES }
        for (e in futs) {
            val n = e.name
            if (priorFor[n] == today && seededFor[n] == today) continue
            if (now - (candleTried[n] ?: 0L) < 10 * 60_000L) continue
            candleTried[n] = now
            if (priorFor[n] != today) {
                var d = today.minusDays(1)
                repeat(10) { if (!runCatching { Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5)) d = d.minusDays(1) }
                val bars = runCatching { Broker.minuteBars(e.token, d) }.getOrDefault(emptyList())
                val levels = com.optionslab.ira.Auction.fromCandles(bars.map { com.optionslab.ira.Auction.Candle(it.epochSecond, it.open, it.high, it.low, it.close, it.volume) })
                    ?: runCatching { recordedProfile(n, d) }.getOrNull()
                if (levels != null) { board.prior(n, levels); priorFor[n] = today }
            }
            if (seededFor[n] != today && runCatching { Market.isOpen() }.getOrDefault(false)) {
                val hhmmss = java.time.LocalTime.now(com.optionslab.engine.IST).withNano(0).toString().let { if (it.length == 5) "$it:00" else it }
                val bars = runCatching { Broker.minuteBars(e.token, today, to = hhmmss) }.getOrDefault(emptyList())
                if (bars.isNotEmpty() && board.seed(n, bars.map { com.optionslab.ira.Auction.Candle(it.epochSecond, it.open, it.high, it.low, it.close, it.volume) }, now / 1000))
                    seededFor[n] = today
            }
        }
    }

    /** [name]'s future's profile on [day] from the order-flow recorder's 1-second rows (no side: candles of one second). */
    private fun recordedProfile(name: String, day: LocalDate): com.optionslab.ira.Auction.Levels? {
        val f = Recorder.days().firstOrNull { it.first == day }?.second ?: return null
        val out = ArrayList<com.optionslab.ira.Auction.Candle>()
        java.util.zip.GZIPInputStream(f.inputStream()).bufferedReader().useLines { lines ->
            for (l in lines) {
                val c = l.split(',')
                if (c.size < 12 || c[3] != name || c[4] != OrderFlow.Role.FUTURE.name) continue
                val px = c[7].toDoubleOrNull() ?: continue
                val v = c[11].toLongOrNull() ?: continue
                if (v > 0) out += com.optionslab.ira.Auction.Candle(c[0].toLongOrNull() ?: 0L, px, px, px, px, v)
            }
        }
        return com.optionslab.ira.Auction.fromCandles(out)
    }

    /** Each index's option set's last centring (5 minutes kept): the entries and when. */
    private val centred = java.util.concurrent.ConcurrentHashMap<String, Pair<List<OrderFlow.Board.Entry>, Long>>()
    private const val RECENTRE_MS = 5 * 60_000L

    /** Each followed option's expiry ("2026-10-14"), for the recorders' files. */
    private val expiries = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * [name]'s nearest-expiry ATM ±2 calls and puts (CE / PE, or the recorder's REC_CE / REC_PE when [recordOnly]): ATM from
     * the index's tick, else its future's ([futToken]); the last centring is kept 5 minutes.
     */
    private suspend fun atmOptions(name: String, today: LocalDate, futToken: Long?, recordOnly: Boolean): List<OrderFlow.Board.Entry> {
        val now = System.currentTimeMillis()
        centred[name]?.let { (list, at) ->
            if (list.isNotEmpty() && now - at < RECENTRE_MS && list.all { (it.role == OrderFlow.Role.REC_CE || it.role == OrderFlow.Role.REC_PE) == recordOnly }) return list
        }
        val spot = Broker.indexToken(name)?.let { KiteStream.tick(it, 60_000)?.last } ?: futToken?.let { KiteStream.tick(it, 60_000)?.last }
        if (spot == null || spot <= 0) return emptyList()
        val opts = (Broker.cachedInstruments() ?: runCatching { Broker.instruments() }.getOrNull()).orEmpty()
            .filter { it.name == name && !it.expiry.isBefore(today) }
        val expiry = opts.minOfOrNull { it.expiry } ?: return emptyList()
        val chain = opts.filter { it.expiry == expiry }
        val out = ArrayList<OrderFlow.Board.Entry>()
        for (k in OrderFlow.atmStrikes(chain.map { it.strike }, spot)) for (r in listOf(Right.CE, Right.PE))
            chain.firstOrNull { it.strike == k && it.right == r }?.let {
                val role = when {
                    recordOnly -> if (r == Right.CE) OrderFlow.Role.REC_CE else OrderFlow.Role.REC_PE
                    else -> if (r == Right.CE) OrderFlow.Role.CE else OrderFlow.Role.PE
                }
                out += OrderFlow.Board.Entry(it.token, name, role, k)
                expiries[it.token] = expiry.toString()
            }
        centred[name] = out to now
        return out
    }

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
        // The charted option's tape and heatmap (it may be followed already as an ATM call or put: that role is kept).
        board.watch(watching?.token)
        watched = entries.map { it.token }.toSet()
        if (expiries.size > 500) expiries.keys.retainAll(watched)
        KiteStream.want(OWNER, watched)
    }

    /** The instruments followed now (the Diagnostics line, the tests). */
    fun entries(): List<OrderFlow.Board.Entry> = board.entries()

    // ---- the ticks ------------------------------------------------------------------------------------------------------

    /**
     * From the stream's socket thread: the ticks of the followed instruments, and the cash indices' and VIX's (the big-move
     * recorder's per-second index prints), are queued (nothing computed here).
     */
    fun offer(got: List<KiteTicks.Tick>, now: Long) {
        if (!enabled) return
        val w = watched
        if (w.isEmpty()) return
        val mine = got.filter { it.token in w || it.token in MoveRecorder.INDEX_TOKENS }
        if (mine.isEmpty()) return
        queue.add(mine to now)
        kick()
    }

    /**
     * TEST ONLY: queued ticks wait for [secondForTest] (no pump of its own on the wall clock). False in the app, always: the
     * setter throws unless BuildConfig.DEBUG.
     */
    @Volatile internal var testManual = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test switch exists only in debug builds" }; field = v }

    private fun kick() {
        if (testManual) return
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
            for (t in ticks) {
                if (t.token in MoveRecorder.INDEX_TOKENS && t.token !in watched) { MoveRecorder.onIndexTick(t.token, t.last, at); continue }
                if (board.offer(t, at)) n++
            }
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
        publishAuction(now)
        // The trade manager's look at what is held (10 Oct): the reads just published, memory only.
        runCatching { TradeManagerHost.onLook() }
        // The closed seconds since each instrument's own cursor, and the indices' prints: the 1-second file's and the
        // big-move recorder's rows (each with its gap and OI change).
        val rows = takeRows(now)
        Recorder.collect(board, rows, now)
        runCatching { MoveRecorder.onSecond(rows, now, moveFeed) }
    }

    /** Each followed instrument's last second handed to the recorders (its cursor). Pruned to the followed set. */
    private val cursors = HashMap<Long, Long>()
    private val sequencer = com.optionslab.ira.MoveEvents.Sequencer()

    private fun takeRows(now: Long): List<com.optionslab.ira.MoveEvents.Row> {
        val got = board.closedAfter { t -> cursors[t] ?: -1L }
        for ((e, b) in got) if (b.sec > (cursors[e.token] ?: -1L)) cursors[e.token] = b.sec
        if (cursors.size > 200) cursors.keys.retainAll(board.tokens())
        val out = ArrayList<com.optionslab.ira.MoveEvents.Row>(got.size + 4)
        for ((e, b) in got) out += sequencer.row(e.token, e.name, e.role.name, e.strike, expiries[e.token].orEmpty(), b)
        for (r in MoveRecorder.indexRows(now / 1000 - 1)) out += sequencer.row(r.token, r.name, r.role, 0.0, "", r.bar)
        return out
    }

    /** What the big-move recorder reads from the flow: the bars in memory, the options' expiries, the context at a trigger. */
    private val moveFeed = object : MoveRecorder.Feed {
        override fun window(fromSec: Long, pick: (OrderFlow.Board.Entry) -> Boolean) = board.window(fromSec, pick)
        override fun expiry(token: Long): String = expiries[token].orEmpty()
        override fun context(t: com.optionslab.ira.MoveEvents.Trigger, nowMs: Long): com.optionslab.ira.MoveEvents.Context = contextOf(t, nowMs)
    }

    /**
     * The H line's context at a trigger: VIX, the future's price, its VWAP distance, the value-area position, the gamma
     * sign and zero-gamma distance, the IB and the day type, the minutes since the open, the expiry flag, today's scheduled
     * events, the flow read and its traps, the latest headline. Each part on its own: one that fails is left out.
     */
    private fun contextOf(t: com.optionslab.ira.MoveEvents.Trigger, nowMs: Long): com.optionslab.ira.MoveEvents.Context {
        val n = t.name
        val a = runCatching { board.auction(n, nowMs) }.getOrNull()
        val read = _reads.value[n]
        val g = runCatching { GammaLive.state.value[n] }.getOrNull()
        val conv = runCatching { GammaLive.convention.value }.getOrNull()
        val today = Market.today()
        val events = runCatching { com.optionslab.app.ira.IraEvents.upcoming(0).filter { it.day == today }.map { it.name } }.getOrDefault(emptyList())
        val expiry = runCatching { Market.upcomingExpiries(n).firstOrNull() == today }.getOrNull()
        val price = a?.last?.takeIf { it > 0 } ?: read?.mid
        val headline = runCatching { com.optionslab.app.ira.IraHub.state.value.news.firstOrNull()?.let { h -> (h.at?.let { "${com.optionslab.ira.MoveEvents.hhmm(it.epochSecond)} " } ?: "") + h.title } }.getOrNull()
        return com.optionslab.ira.MoveEvents.Context(
            vix = MoveRecorder.vixAt(t.startSec + 60), price = price,
            vwapSd = price?.let { p -> a?.vwap?.sdUnits(p) }, valuePos = a?.regime?.words,
            gamma = if (g != null && conv != null) g.sign(conv).name.lowercase(Locale.ENGLISH) else null, zeroGammaDist = g?.distance(),
            ibHigh = a?.tpo?.ibHigh, ibLow = a?.tpo?.ibLow, openType = a?.tpo?.openType?.code, dayType = a?.tpo?.dayType?.words,
            minutesOpen = com.optionslab.ira.MoveEvents.minuteIndex(t.startSec), expiryDay = expiry, events = events,
            flow = read?.let { OrderFlow.word(it) }, buyers = read?.buyers, traps = read?.flags?.map { it.words }.orEmpty(), headline = headline)
    }

    /**
     * Keep the price stream on from 09:15 to F&O's close on a trading day while the big-move recorder is on (so a day with
     * the app in the background has no holes) - but never in Android's battery saver or Jarvis's low-battery saver. Read by
     * [KiteStream]'s "is the stream needed" (its login and hours checks still come first). Cheap: the switch is cached.
     */
    fun keepStreamOn(): Boolean {
        if (!enabled) return false
        val ctx = appContext ?: return false
        if (!runCatching { MoveRecorder.on }.getOrDefault(false)) return false
        if (!runCatching { Market.isTradingDay() }.getOrDefault(false)) return false
        val m = Market.minuteNow()
        if (m < 9 * 60 + 15 || m > Market.foClose()) return false
        if (runCatching { ctx.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)) return false
        if (runCatching { com.optionslab.app.work.Battery.saving(ctx) }.getOrDefault(false)) return false
        return true
    }

    private val _auction = MutableStateFlow<Map<String, com.optionslab.ira.Auction.Snapshot>>(emptyMap())
    /** Each index future's auction (profile, regime, delta, footprint, VWAP, TPO), once a second; display only. */
    val auction: StateFlow<Map<String, com.optionslab.ira.Auction.Snapshot>> = _auction

    private val _chartLevels = MutableStateFlow<Map<String, List<com.optionslab.ira.Auction.ChartLevel>>>(emptyMap())
    /** Each index's optional chart lines in the INDEX's prices (empty while its basis is not known), once a second. */
    val chartLevels: StateFlow<Map<String, List<com.optionslab.ira.Auction.ChartLevel>>> = _chartLevels

    private fun publishAuction(now: Long) {
        val m = LinkedHashMap<String, com.optionslab.ira.Auction.Snapshot>()
        for (n in names()) if (n in OrderFlow.INDICES) board.auction(n, now)?.let { m[n] = it }
        _auction.value = m
        _chartLevels.value = m.mapValues { (n, s) -> basis(n, s.last)?.let { com.optionslab.ira.Auction.chartLevels(s, it) }.orEmpty() }
    }

    /** [name]'s future − index now (null: the index's tick is not fresh). */
    fun basis(name: String, future: Double): Double? {
        if (!(future > 0)) return null
        val idx = runCatching { Broker.indexToken(name)?.let { KiteStream.tick(it, 60_000)?.last } }.getOrNull()
        return idx?.takeIf { it > 0 }?.let { future - it }
    }

    /** [underlying]'s auction now, fresh from the flow (the shadow log's; null: no future followed or nothing today). */
    fun auctionNow(underlying: String, nowMs: Long = System.currentTimeMillis()): com.optionslab.ira.Auction.Snapshot? {
        testAuction?.let { return it(underlying) }
        if (!enabled) return null
        return board.auction(underlying, nowMs)
    }

    /** [underlying]'s future's time & sales ([watch]: the charted option's), newest first. Call off the main thread. */
    fun tape(underlying: String, watch: Boolean): List<com.optionslab.ira.TapeHeat.Print> = board.tape(underlying, watch)

    /** [underlying]'s future's heatmap ([watch]: the charted option's) now. Call off the main thread. */
    fun heat(underlying: String, watch: Boolean, nowMs: Long = System.currentTimeMillis()): com.optionslab.ira.TapeHeat.Frame? =
        board.heat(underlying, watch, nowMs)

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
     * The flow's 1-second bars of the index futures and the ATM options during NSE hours (and the MCX bots' futures while
     * they run), with the cash indices' and VIX's per-second prints, one gzip file a day under noBackupFilesDir/orderflow
     * (plain: market data only, never in a backup), written once a minute as a new gzip member, 30 days kept (and at most
     * [BUDGET_BYTES]); exported with the market recorder's zip. Its columns ([com.optionslab.ira.MoveEvents.DAILY_HEADER])
     * keep the first 16 of before and add the best bid and offer, the 5 levels' sizes and order counts, pulls, the quote
     * rule's split, prints and the largest print, the trade range, the 5-level OFI, Kite's totals and the feed's health.
     * Beside it, the index futures' footprint per minute (every traded price's buy and sell volume): footprint-<day>.csv.gz.
     */
    internal object Recorder {
        const val KEEP_DAYS = 30L
        const val BUDGET_BYTES = 1_024L * 1024 * 1024
        val HEADER: String = com.optionslab.ira.MoveEvents.DAILY_HEADER
        private val buf = StringBuilder()
        private val footBuf = StringBuilder()
        private val footCursor = HashMap<String, Long>()
        private var lastFlush = 0L
        private var rotated: LocalDate? = null
        private var headerChecked: LocalDate? = null

        fun dir(): File? = appContext?.let { File(it.noBackupFilesDir, "orderflow") }

        /** This second's [rows] (NSE's in its hours; the MCX bots' futures whenever they come) and the footprint's new minutes. */
        suspend fun collect(b: OrderFlow.Board, rows: List<com.optionslab.ira.MoveEvents.Row>, now: Long) {
            val open = testOpen ?: runCatching { Market.isOpen() }.getOrDefault(false)
            for (r in rows) {
                if (r.role == OrderFlow.Role.WATCH.name) continue
                val mcx = r.role == OrderFlow.Role.FUTURE.name && r.name !in OrderFlow.INDICES
                if (!open && !mcx) continue
                val t = Instant.ofEpochSecond(r.bar.sec).atZone(com.optionslab.engine.IST).toLocalTime().withNano(0)
                buf.append(com.optionslab.ira.MoveEvents.dailyLine(r, if (t.second == 0) "$t:00" else t.toString()))
            }
            if (open) {
                val nowSec = now / 1000
                for (n in com.optionslab.ira.MoveEvents.INDICES) {
                    val from = footCursor[n] ?: (nowSec / 60 - 2)
                    footCursor[n] = from
                    for ((m, prices) in b.footMinutes(n, from, nowSec - 5)) {
                        footBuf.append(com.optionslab.ira.MoveEvents.footLines(n, m, prices))
                        footCursor[n] = maxOf(footCursor[n] ?: m, m)
                    }
                }
            }
            if ((buf.isNotEmpty() || footBuf.isNotEmpty()) && (now - lastFlush >= 60_000 || buf.length > 512_000)) flush(now)
        }

        /** Writes what is buffered to today's files (a new gzip member each) and drops days over [KEEP_DAYS] or the budget. */
        suspend fun flush(now: Long = System.currentTimeMillis()) {
            lastFlush = now
            if (buf.isEmpty() && footBuf.isEmpty()) return
            val text = buf.toString(); buf.setLength(0)
            val foot = footBuf.toString(); footBuf.setLength(0)
            val dir = dir() ?: return
            val day = Market.today()
            withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    val f = File(dir, "$day.csv.gz")
                    // A day file begun by an older build (16 columns) gets the new header line before the new rows.
                    val fresh = !f.exists() || (headerChecked != day && !startsWithHeader(f))
                    headerChecked = day
                    if (text.isNotEmpty()) java.util.zip.GZIPOutputStream(java.io.FileOutputStream(f, true)).use { z ->
                        if (fresh) z.write(HEADER.toByteArray(Charsets.UTF_8))
                        z.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
                runCatching {
                    if (foot.isNotEmpty()) {
                        val f = File(dir, "footprint-$day.csv.gz")
                        val fresh = !f.exists()
                        java.util.zip.GZIPOutputStream(java.io.FileOutputStream(f, true)).use { z ->
                            if (fresh) z.write(com.optionslab.ira.MoveEvents.FOOT_HEADER.toByteArray(Charsets.UTF_8))
                            z.write(foot.toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                if (rotated != day) {
                    rotated = day
                    runCatching {
                        val all = (days() + footDays()).sortedBy { it.first }
                        val old = all.filter { it.first.isBefore(day.minusDays(KEEP_DAYS - 1)) }
                        old.forEach { it.second.delete() }
                        val kept = all.filter { it !in old }
                        var total = kept.sumOf { it.second.length() }
                        for ((d, f) in kept) if (total > BUDGET_BYTES && d.isBefore(day)) { total -= f.length(); f.delete() }
                    }
                }
            }
        }

        /** Whether [f]'s first line is today's [HEADER] (only its first line is read). */
        private fun startsWithHeader(f: File): Boolean = runCatching {
            java.util.zip.GZIPInputStream(f.inputStream()).bufferedReader().use { it.readLine() } + "\n" == HEADER
        }.getOrDefault(true)

        private val DAY = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.csv\\.gz$")
        private val FOOT_DAY = Regex("^footprint-(\\d{4}-\\d{2}-\\d{2})\\.csv\\.gz$")

        /** The kept day files, oldest first. */
        fun days(): List<Pair<LocalDate, File>> = listed(DAY)

        /** The kept footprint files, oldest first. */
        fun footDays(): List<Pair<LocalDate, File>> = listed(FOOT_DAY)

        private fun listed(rx: Regex): List<Pair<LocalDate, File>> = (dir()?.listFiles() ?: emptyArray()).mapNotNull { f ->
            rx.find(f.name)?.let { m -> runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()?.let { it to f } }
        }.sortedBy { it.first }

        /** TEST ONLY: market hours as given (null: the real clock). */
        @Volatile internal var testOpen: Boolean? = null

        /** TEST ONLY: forget what is buffered and when it was written. */
        internal fun resetForTest() {
            buf.setLength(0); footBuf.setLength(0); footCursor.clear(); lastFlush = 0L; rotated = null; headerChecked = null; testOpen = null
        }
    }

    /** The kept order-flow days (date, bytes), newest first. Lists the folder only. */
    fun recordedDays(): List<Pair<LocalDate, Long>> = Recorder.days().reversed().map { it.first to it.second.length() }

    /**
     * Each kept day's gzip file, the footprint files, the big-move recorder's event files and the shadow log, into the market
     * recorder's export zip (copied as they are). Returns files.
     */
    fun export(zip: java.util.zip.ZipOutputStream): Int {
        var n = 0
        for ((day, f) in Recorder.days()) {
            zip.putNextEntry(java.util.zip.ZipEntry("orderflow-$day.csv.gz")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); n++
        }
        for ((day, f) in Recorder.footDays()) {
            zip.putNextEntry(java.util.zip.ZipEntry("orderflow-footprint-$day.csv.gz")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); n++
        }
        n += runCatching { MoveRecorder.export(zip) }.getOrDefault(0)
        for (f in FlowGate.files()) if (f.exists()) {
            zip.putNextEntry(java.util.zip.ZipEntry("orderflow-${f.name}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); n++
        }
        return n
    }

    // ---- tests ----------------------------------------------------------------------------------------------------------

    /** TEST ONLY: a read for each underlying, as if from the flow (null in the app, always). Throws unless BuildConfig.DEBUG. */
    @Volatile internal var testRead: ((String) -> OrderFlow.Read?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test read exists only in debug builds" }; field = v }

    /** TEST ONLY: an auction for each underlying, as if from the flow (null in the app, always). Throws unless BuildConfig.DEBUG. */
    @Volatile internal var testAuction: ((String) -> com.optionslab.ira.Auction.Snapshot?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test auction exists only in debug builds" }; field = v }

    /** TEST ONLY: publish [m] as the auctions (and [levels] as the chart's lines). Throws unless BuildConfig.DEBUG. */
    internal fun publishForTest(m: Map<String, com.optionslab.ira.Auction.Snapshot>, levels: Map<String, List<com.optionslab.ira.Auction.ChartLevel>> = emptyMap()) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test publish exists only in debug builds" }
        _auction.value = m; _chartLevels.value = levels
    }

    /** TEST ONLY: follow [entries] as a layout would (no network). Throws unless BuildConfig.DEBUG. */
    internal fun layoutForTest(entries: List<OrderFlow.Board.Entry>) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test layout exists only in debug builds" }
        follow(entries)
    }

    /**
     * TEST ONLY: take the queued ticks, publish the reads and run the once-a-second work (the recorders) now, at [nowMs].
     * Throws unless BuildConfig.DEBUG.
     */
    internal fun secondForTest(nowMs: Long) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test second exists only in debug builds" }
        drain(); publish(nowMs)
        kotlinx.coroutines.runBlocking { perSecond(nowMs) }
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
        testAuction = null; _auction.value = emptyMap(); _chartLevels.value = emptyMap(); watching = null
        synchronized(cursors) { cursors.clear() }; sequencer.clear(); centred.clear(); expiries.clear(); testManual = false
        Recorder.resetForTest()
    }
}
