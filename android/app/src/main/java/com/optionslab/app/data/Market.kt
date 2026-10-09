package com.optionslab.app.data

import android.content.Context
import com.optionslab.engine.ExpiryPut
import com.optionslab.engine.IST
import com.optionslab.engine.Live
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Upstox
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs

/** Live quotes, the live chain, and the expiry calendar. */
object Market {
    private lateinit var app: Context

    fun init(context: Context) { app = context.applicationContext }

    /**
     * TEST SEAM (JVM tests only): a fixed or stepped wall clock for the market calendar. Its setter
     * throws unless BuildConfig.DEBUG and no app code sets it; when null (always, in the app) [now]
     * reads the system clock exactly as before.
     */
    @Volatile internal var testClock: java.time.Clock? = null
        set(v) {
            check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }
            field = v
        }

    fun now(): ZonedDateTime = testClock?.let { ZonedDateTime.now(it).withZoneSameInstant(IST) } ?: ZonedDateTime.now(IST)
    fun today(): LocalDate = now().toLocalDate()
    fun minuteNow(): Int = now().let { it.hour * 60 + it.minute }

    const val OPEN = com.optionslab.engine.NseHours.OPEN
    /**
     * The cash market's and the indices' close (15:30): index candles (the last is 15:29's), index-based signals, the
     * index's settlement. Not F&O's close, which is [foClose] (15:40 from 3 Aug 2026).
     */
    const val INDEX_CLOSE = com.optionslab.engine.NseHours.INDEX_CLOSE

    /**
     * NSE F&O's close on [d] (15:40 from 3 Aug 2026, 15:30 before): orders, positions, option and future prices, the market
     * watch, square-offs and stale-price checks. An older day keeps its own hours (replays, backtests).
     */
    fun foClose(d: LocalDate = today()): Int = com.optionslab.engine.NseHours.foClose(d)
    /** Minutes after the open that an empty intraday read still means "no candle yet", not a failure. */
    private const val FIRST_CANDLE_GRACE = 2

    fun isWeekday(d: LocalDate = today()) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY

    /** A weekday that is not an NSE trading holiday, or a special session NSE called (see [Holidays]). */
    fun isTradingDay(d: LocalDate = today()) = Holidays.isExtraSession(d) || (isWeekday(d) && !Holidays.isHoliday(d))
    /** NSE F&O trades now: a trading day, 09:15 to [foClose] (15:40 from 3 Aug 2026). The app's "Market open". */
    fun isOpen(): Boolean = now().let { isOpenAt(it.toLocalDate(), it.hour * 60 + it.minute) }

    /** F&O trades on [d] at [minute] (minutes of the day, IST). */
    fun isOpenAt(d: LocalDate, minute: Int): Boolean = isTradingDay(d) && com.optionslab.engine.NseHours.foOpen(d, minute)

    /** The index (cash) session is on now: a trading day, 09:15 to 15:30 - for index-based reads and signals only. */
    fun isIndexOpen(): Boolean = isTradingDay() && com.optionslab.engine.NseHours.indexOpen(minuteNow())

    /** What a hand order placed outside market hours is told (paper trades by the exchange's F&O hours too). */
    val CLOSED_FOR_ORDERS: String
        get() = "Market is closed now: orders are taken 09:15-${com.optionslab.engine.NseHours.foCloseText(today())} on trading days."

    /**
     * TEST SEAM (JVM tests only): the screen tests place paper orders at whatever hour CI runs, so they
     * take orders at any time unless a test turns this off. Its setter throws in a release build.
     */
    @Volatile internal var testOrdersAnyTime: Boolean = false
        set(v) {
            check(com.optionslab.app.BuildConfig.DEBUG) { "the order-hours seam exists only in debug builds" }
            field = v
        }

    /** Whether a hand order may be placed now: in F&O's hours on a trading day (to 15:40 from 3 Aug 2026). */
    fun acceptsOrders(): Boolean = testOrdersAnyTime || isOpen()

    data class Quote(val symbol: String, val last: Double, val open: Double, val high: Double, val low: Double,
                     val minute: Int, val spark: List<Double>,
                     /** True when this is the last traded session's close, not today's price. */
                     val lastSession: Boolean = false) {
        val change: Double get() = last - open
        val changePct: Double get() = if (open != 0.0) change / open else 0.0
    }

    /**
     * LIVE mode: Zerodha only. Not logged in is an error the screen shows, never
     * a silent switch to another feed. SANDBOX mode: Upstox's public candles.
     */
    fun liveMode(): Boolean = AppSettings.load().live

    /**
     * [quick]: short timeouts and no queueing behind chart loads, for the market watch (Upstox feed only). [spark] false:
     * the caller reads only the last price and the change from the open, so Zerodha's day candles are not read for it
     * (Live mode; the Upstox feed reads its candles for the price itself).
     */
    suspend fun quote(symbol: String, quick: Boolean = false, spark: Boolean = true): Quote? =
        if (liveMode()) Broker.indexQuote(symbol, spark) else upstoxQuote(symbol, quick)

    private suspend fun upstoxQuote(symbol: String, quick: Boolean): Quote? {
        val key = Upstox.INDEX_KEYS.getValue(symbol)
        val read = runCatching { if (quick) Net.intradayQuick(key) else Net.intraday(key) }
        read.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        var bars = read.getOrDefault(emptyList()).filter { it.istDate == today() }
        if (bars.isEmpty() && isIndexOpen()) {
            // In the index's session (to 15:30; F&O's later close does not move an index) a failed read is an error, never the last close passed off as the live price.
            read.exceptionOrNull()?.let { throw it }
            // An empty read is normal only before the first candle prints; later it means no data.
            if (minuteNow() > OPEN + FIRST_CANDLE_GRACE) return null
        }
        // Weekend, holiday, after the close, the first minutes of a session: the last session the
        // market traded (its close as the last price), not nothing.
        val fallback = bars.isEmpty()
        if (fallback) {
            val past = runCatching { ChartFeed.bars(symbol, "1m", null, null, quick = quick) }.getOrDefault(emptyList())
            val day = past.lastOrNull()?.istDate
            bars = past.filter { it.istDate == day }
        }
        if (bars.isEmpty()) return null
        return Quote(symbol, bars.last().close, bars.first().open, bars.maxOf { it.high }, bars.minOf { it.low },
            bars.last().istMinute, bars.map { it.close }, lastSession = fallback)
    }

    // ---- instrument master, cached per day ---------------------------------

    private fun contractsFile() = File(app.filesDir, "contracts.json")

    fun wipe() { contractsFile().delete() }

    /** Parsed once and kept in memory; the file is only re-read when it changes. */
    @Volatile private var mem: Pair<Long, Pair<LocalDate, List<Upstox.Contract>>>? = null

    // Two locks: reading the saved list never waits behind the (minutes-long) master download.
    private val readLock = Any()
    private val downloadLock = Any()
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    fun cachedContracts(): Pair<LocalDate, List<Upstox.Contract>>? = synchronized(readLock) {
        val f = contractsFile()
        val stamp = f.lastModified()
        mem?.let { if (it.first == stamp && f.exists()) return it.second }
        return parseContracts()?.also { mem = stamp to it }
    }

    private fun parseContracts(): Pair<LocalDate, List<Upstox.Contract>>? = runCatching {
        val o = JSONObject(contractsFile().readText())
        val day = LocalDate.parse(o.getString("day"))
        val arr = o.getJSONArray("c")
        day to (0 until arr.length()).map {
            val c = arr.getJSONArray(it)
            Upstox.Contract(c.getString(0), LocalDate.parse(c.getString(1)), c.getDouble(2),
                if (c.getString(3) == "CE") Right.CE else Right.PE, c.getInt(4), c.getString(5), c.getString(6))
        }
    }.getOrNull()

    /** Today's listed options; the master is fetched at most once a day. */
    /** Synchronized so two callers never download the (tens of MB) master at once. */
    fun contracts(forceRefresh: Boolean = false): List<Upstox.Contract> = synchronized(downloadLock) {
        val cached = cachedContracts()
        if (!forceRefresh && cached != null && cached.first == today()) return cached.second
        val fresh = Net.fetchMaster()
        val arr = JSONArray()
        fresh.forEach { arr.put(JSONArray().put(it.underlying).put(it.expiry.toString()).put(it.strike).put(it.right.name).put(it.lotSize).put(it.instrumentKey).put(it.tradingSymbol)) }
        // Written to a temporary file and moved into place, so a reader never sees half a list.
        val tmp = File(app.filesDir, "contracts.json.tmp")
        tmp.writeText(JSONObject().put("day", today().toString()).put("c", arr).toString())
        synchronized(readLock) { if (!tmp.renameTo(contractsFile())) { contractsFile().writeText(tmp.readText()); tmp.delete() } }
        return fresh
    }

    /** Refresh the day's list in the background, once at a time. */
    fun refreshContractsInBackground() {
        if (!refreshing.compareAndSet(false, true)) return
        Thread { try { runCatching { contracts() } } finally { refreshing.set(false) } }.start()
    }

    /**
     * Upcoming expiries from the last master seen. Without one the calendar
     * says so rather than guessing a weekday.
     */
    fun upcomingExpiries(underlying: String = "NIFTY"): List<LocalDate> {
        if (liveMode()) return Broker.cachedInstruments()?.filter { it.name == underlying && !it.expiry.isBefore(today()) }
            ?.map { it.expiry }?.distinct()?.sorted() ?: emptyList()
        val c = cachedContracts()?.second ?: return emptyList()
        return c.filter { it.underlying == underlying && !it.expiry.isBefore(today()) }.map { it.expiry }.distinct().sorted()
    }

    fun isExpiryDay(underlying: String = "NIFTY", day: LocalDate = today()): Boolean = day in upcomingExpiries(underlying)

    // ---- the live chain (port of cli._live_chain) --------------------------

    /**
     * [pricedAt] is set when the chain is a snapshot of live quotes taken at
     * that minute rather than 1-minute candles; the ticket is then priced - and
     * labelled - at that minute, never passed off as the entry-time bar.
     */
    data class LiveChain(
        val expiry: LocalDate, val series: List<Series>, val lotSize: Int, val contracts: List<Upstox.Contract>, val spot: Double,
        val source: String = "Upstox public candles", val pricedAt: Int? = null,
    )

    /**
     * Today's chain for the nearest expiry, priced off the intraday endpoint.
     * Only the 29 strikes nearest the money are fetched: parity needs ten that
     * quote both sides, and Upstox rate-limits at 429.
     */
    suspend fun liveChain(underlying: String, near: Int = 14): LiveChain {
        val liveNow = liveMode()
        // An MCX name (CRUDEOIL, NATURALGAS, GOLDM, SILVERM...): its near-month chain on its near future (9 Oct).
        val lc = if (com.optionslab.engine.mcx.Mcx.isMcxName(underlying)) McxMarket.chain(underlying, near)
            else if (liveNow) Broker.liveChain(underlying, near) else upstoxChain(underlying, near)
        chainReads[chainKey(underlying, near)] = Triple(System.currentTimeMillis(), liveNow, lc)
        return lc
    }

    /** The last chain read per index and strike count: when, in which mode (true = Live), and the chain. In memory. */
    private val chainReads = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Boolean, LiveChain>>()
    private fun chainKey(underlying: String, near: Int) = "$underlying|$near"

    /**
     * Battery (round 7): a chain of [underlying] with [near] strikes this process read under 5 minutes ago in the mode it
     * is in now ([com.optionslab.ira.StartChain]), else null. Only for the Options tab's background pricing at app start.
     */
    fun recentChain(underlying: String, near: Int): LiveChain? {
        val (readAt, readLive, lc) = chainReads[chainKey(underlying, near)] ?: return null
        val liveNow = runCatching { liveMode() }.getOrNull() ?: return null
        return lc.takeIf { com.optionslab.ira.StartChain.reuse(readAt, System.currentTimeMillis(), sameMode = readLive == liveNow) }
    }

    private suspend fun upstoxChain(underlying: String, near: Int): LiveChain {
        // The saved contract list is good while its nearest expiry is still ahead; the day's fresh
        // list (tens of MB) then downloads in the background instead of holding the chain up.
        val saved = cachedContracts()?.second?.filter { it.underlying == underlying }
        val usable = saved?.let { Upstox.contractsToRefresh(it, today()) }?.takeIf { it.isNotEmpty() }
        if (usable != null && cachedContracts()?.first != today()) refreshContractsInBackground()
        val live = usable ?: Upstox.contractsToRefresh(contracts().filter { it.underlying == underlying }, today())
        if (live.isEmpty()) throw IllegalStateException("no listed $underlying options in the master")
        val expiry = live.minOf { it.expiry }
        val chain = live.filter { it.expiry == expiry }
        val spot = quote(underlying)?.last ?: throw IllegalStateException("no intraday bars for $underlying; the market may be shut")
        val strikes = chain.map { it.strike }.distinct().sortedBy { abs(it - spot) }.take(near * 2 + 1).toSet()
        val wanted = chain.filter { it.strike in strikes }
        val gate = Semaphore(10)
        val series = coroutineScope {
            wanted.map { c -> async { gate.withPermit { runCatching { Upstox.toSeries(c, Net.intraday(c.instrumentKey), today(), contracts = false) }.getOrNull() } } }.awaitAll()
        }.filterNotNull().filter { it.size > 0 }
        if (series.isEmpty()) throw IllegalStateException("no bars returned for the $expiry chain")
        return LiveChain(expiry, series, wanted.first().lotSize, wanted, spot)
    }

    /** The order the strategy implies now. NOTHING IS SENT. */
    data class TicketDraft(val ticket: Live.Ticket, val shortKey: String?, val wingKey: String?, val source: String = "")

    suspend fun draftTicket(s: AppSettings): TicketDraft {
        val lc = liveChain(s.ticketUnderlying)
        if (lc.expiry != today()) throw IllegalStateException(
            "nearest ${s.ticketUnderlying} expiry is ${lc.expiry}, not today (${today()}). This strategy trades ONLY on expiry day - the edge is the last few hours of theta, and holding overnight is a different bet.")
        // A quote snapshot is priced at the minute it was taken (never earlier
        // than the entry time); candles are priced at the entry-time bar.
        val at = lc.pricedAt?.let { maxOf(it, s.entryMinute) } ?: s.entryMinute
        val t = Live.buildTicket(lc.series, today(), s.ticketUnderlying, lc.lotSize, lc.expiry, s.otmPct, at, s.ticketLots, s.wingPct)
        fun key(k: Double?) = k?.let { strike -> lc.contracts.firstOrNull { it.strike == strike && it.right == Right.PE }?.instrumentKey }
        val src = lc.source + if (lc.pricedAt != null) " at ${com.optionslab.engine.minuteText(at)} IST" else ", entry bar ${s.entry}"
        return TicketDraft(t, key(t.strike), key(t.wingStrike), src)
    }

    /** Cash settlement: the average of spot over 15:00-15:29, never the 15:29 print. */
    suspend fun settlement(underlying: String, day: LocalDate = today()): Double {
        if (liveMode()) {
            val bars = Broker.indexMinuteBars(underlying, day)
            if (bars.isEmpty()) throw IllegalStateException("Zerodha returned no index bars for $day; enter the exchange's settlement price by hand")
            return ExpiryPut.settlementPrice(bars.filter { it.istDate == day }.associate { it.istMinute to it.close })
        }
        val key = Upstox.INDEX_KEYS.getValue(underlying)
        // The intraday endpoint only has today's session; an earlier day comes from history.
        val bars = (if (day == today()) Net.intraday(key) else Net.history(key, day, day)).filter { it.istDate == day }
        return ExpiryPut.settlementPrice(bars.associate { it.istMinute to it.close })
    }

    /** Live mark of an open paper ticket, from its own legs' latest prints. */
    suspend fun markOpenTicket(e: Ledger.Entry): Double? {
        val tk = e.row.ticket
        if (liveMode()) {
            val ins = Broker.instruments()
            val short = Broker.find(ins, tk.underlying, tk.expiry, tk.strike, Right.PE)
            val wing = tk.wingStrike?.let { Broker.find(ins, tk.underlying, tk.expiry, it, Right.PE) }
            if (short != null && (tk.wingStrike == null || wing != null)) {
                val keys = listOfNotNull(short, wing).map { "NFO:${it.tradingSymbol}" }
                val q = Broker.quotes(keys)
                val s = q["NFO:${short.tradingSymbol}"]?.last
                val w = wing?.let { q["NFO:${it.tradingSymbol}"]?.last } ?: 0.0
                if (s != null) return (tk.credit - (s - w)) * tk.qty
            }
            return null
        }
        val sk = e.shortKey?.takeIf { !it.startsWith("kite:") } ?: return null
        val short = Net.intraday(sk).lastOrNull { it.istDate == today() } ?: return null
        val wing = e.wingKey?.let { Net.intraday(it).lastOrNull { b -> b.istDate == today() } ?: return null }
        return (e.row.ticket.credit - (short.close - (wing?.close ?: 0.0))) * e.row.ticket.qty
    }
}
