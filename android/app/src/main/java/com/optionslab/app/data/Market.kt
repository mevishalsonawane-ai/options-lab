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

    fun now(): ZonedDateTime = ZonedDateTime.now(IST)
    fun today(): LocalDate = now().toLocalDate()
    fun minuteNow(): Int = now().let { it.hour * 60 + it.minute }

    const val OPEN = 9 * 60 + 15
    const val CLOSE = 15 * 60 + 30

    fun isWeekday(d: LocalDate = today()) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY

    /** A weekday that is not an NSE trading holiday (see [Holidays]). */
    fun isTradingDay(d: LocalDate = today()) = isWeekday(d) && !Holidays.isHoliday(d)
    fun isOpen(): Boolean = isTradingDay() && minuteNow() in OPEN until CLOSE

    data class Quote(val symbol: String, val last: Double, val open: Double, val high: Double, val low: Double,
                     val minute: Int, val spark: List<Double>) {
        val change: Double get() = last - open
        val changePct: Double get() = if (open != 0.0) change / open else 0.0
    }

    /**
     * LIVE mode: Zerodha only. Not logged in is an error the screen shows, never
     * a silent switch to another feed. SANDBOX mode: Upstox's public candles.
     */
    fun liveMode(): Boolean = AppSettings.load().live

    suspend fun quote(symbol: String): Quote? =
        if (liveMode()) Broker.indexQuote(symbol) else upstoxQuote(symbol)

    private suspend fun upstoxQuote(symbol: String): Quote? {
        val key = Upstox.INDEX_KEYS.getValue(symbol)
        var bars = runCatching { Net.intraday(key) }.getOrDefault(emptyList()).filter { it.istDate == today() }
        // Weekend, holiday, before the open: the last session the market traded, not nothing.
        if (bars.isEmpty()) {
            val past = runCatching { ChartFeed.bars(symbol, "1m", null, null) }.getOrDefault(emptyList())
            val day = past.lastOrNull()?.istDate
            bars = past.filter { it.istDate == day }
        }
        if (bars.isEmpty()) return null
        return Quote(symbol, bars.last().close, bars.first().open, bars.maxOf { it.high }, bars.minOf { it.low },
            bars.last().istMinute, bars.map { it.close })
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
        return if (liveMode()) Broker.liveChain(underlying, near) else upstoxChain(underlying, near)
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
