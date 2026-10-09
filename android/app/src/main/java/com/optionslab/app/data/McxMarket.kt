package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Upstox
import com.optionslab.engine.mcx.Mcx
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.mcx.McxInstruments
import com.optionslab.engine.mcx.McxMargin
import com.optionslab.engine.mcx.McxSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs

/**
 * MCX (commodities) on the phone (9 Oct 2026, Boss: "all MCX in the application"; research/MCX_GUIDE.md): the day's MCX
 * futures and options, their prices, the near-month chain, MCX's own hours and holidays, and what is held there. The rules
 * themselves are the engine's ([Mcx], [McxInstruments], [McxSession], [McxMargin]).
 *
 *  - Contracts: Zerodha's public MCX list (GET /instruments/MCX, read with the login when there is one) for the symbols,
 *    tokens, expiries and ticks, and Upstox's public MCX list for each contract's multiplier (Zerodha's list says lot 1 on
 *    MCX); without Zerodha's, Upstox's alone. Kept for the day in a plain file (public data, nothing of the owner's).
 *  - Prices: Zerodha (its stream, else its quote call) in Live mode; Upstox's public 1-minute candles in Paper mode - the
 *    same split as the indices ([Market.quote]).
 *  - Hours: [McxSession] with the list Upstox's holiday call gives for MCX ([keepHolidays], from [Holidays.refresh]), else
 *    the built-in 2026 list. MCX trades 09:00-23:30, 23:55 in US winter time; some NSE holidays have an MCX evening session.
 *
 * Nothing here trades: the paper account ([Paper]), the order review and the expiry exit ([McxGuard]) do.
 */
object McxMarket {
    private lateinit var app: Context

    fun init(context: Context) { app = context.applicationContext }
    private fun ready(): Boolean = ::app.isInitialized

    const val CLOSED_FOR_ORDERS = "MCX is closed now: MCX orders are taken 09:00-23:30 (23:55 in US winter time) on MCX trading days."

    // ---- the clock and the calendar -------------------------------------------------------------------------------------

    fun now(): LocalDateTime = Market.now().toLocalDateTime()

    private fun holidaysFile() = File(app.filesDir, "mcx_holidays.json")
    @Volatile private var cal: McxSession.Calendar? = null

    /** MCX's calendar: the fetched list where it covers the year, else the built-in one. */
    fun calendar(): McxSession.Calendar {
        cal?.let { return it }
        val fetched = if (!ready()) emptyMap() else runCatching {
            val o = JSONObject(holidaysFile().readText())
            o.keys().asSequence().associate { k ->
                val v = o.getString(k)
                LocalDate.parse(k) to (if (v == "shut") null else McxSession.Window(LocalTime.parse(v.substringBefore('-')), LocalTime.parse(v.substringAfter('-'))))
            }
        }.getOrDefault(emptyMap())
        return McxSession.Calendar(fetched).also { if (ready()) cal = it }
    }

    /** Upstox's MCX days ([McxSession.parseUpstox]), read with NSE's holidays: kept for the calendar. An empty read keeps the old list. */
    fun keepHolidays(days: Map<LocalDate, McxSession.Window?>) {
        if (!ready() || days.isEmpty()) return
        val o = JSONObject()
        days.forEach { (d, w) -> o.put(d.toString(), w?.let { "%02d:%02d-%02d:%02d".format(it.open.hour, it.open.minute, it.close.hour, it.close.minute) } ?: "shut") }
        runCatching { holidaysFile().writeText(o.toString()) }
        cal = null
    }

    /** MCX is trading now (its own hours and holidays). */
    fun isOpen(): Boolean = runCatching { calendar().isOpen(now()) }.getOrDefault(false)

    /** Today's MCX session, or null when MCX does not trade today. */
    fun todayWindow(): McxSession.Window? = runCatching { calendar().window(Market.today()) }.getOrNull()

    /** Whether a hand MCX order may be placed now (the screen tests' any-hour seam, as [Market.acceptsOrders]). */
    fun acceptsOrders(): Boolean = Market.testOrdersAnyTime || isOpen()

    /** The account guard's MCX entry cut-off: 30 minutes before today's MCX close (23:00, or 23:25 in US winter); null when shut. */
    fun entryCutoffMinute(): Int? = todayWindow()?.close?.minusMinutes(30)?.let { it.hour * 60 + it.minute }

    // ---- the contracts, once a day ----------------------------------------------------------------------------------------

    private fun contractsFile() = File(app.filesDir, "mcx_contracts.json")
    @Volatile private var mem: Pair<String, List<McxContract>>? = null
    private val loading = Mutex()

    /** The list on the phone (any day), never the network; empty before the first read. */
    fun cached(): List<McxContract> {
        mem?.let { return it.second }
        if (!ready()) return emptyList()
        return runCatching {
            val o = JSONObject(contractsFile().readText())
            val a = o.getJSONArray("c")
            val list = (0 until a.length()).map { a.getJSONArray(it) }.map {
                val r = it.getString(6)
                McxContract(it.getLong(0), it.getLong(1), it.getString(2), it.getString(3), LocalDate.parse(it.getString(4)), it.getDouble(5),
                    if (r.isEmpty()) null else Right.valueOf(r), it.getDouble(7), it.getInt(8))
            }
            (o.optString("day") to list).also { mem = it }.second
        }.getOrDefault(emptyList())
    }

    /** The day the list on the phone was read, or null. */
    fun cachedDay(): LocalDate? { cached(); return mem?.first?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }

    /** Today's list: the one on the phone when read today, else read now (Zerodha's and Upstox's public lists). */
    suspend fun contracts(force: Boolean = false): List<McxContract> {
        if (!force && cachedDay() == Market.today()) return cached()
        return loading.withLock {
            if (!force && cachedDay() == Market.today()) return@withLock cached()
            val names = Mcx.NAMES.toSet()
            val upstox = runCatching { McxInstruments.parseUpstox(Net.fetchMcxMaster()) }.getOrDefault(emptyList())
            val kite = runCatching { Broker.mcxInstrumentsText() }.getOrNull()
            val today = Market.today()
            val list = (if (kite != null) McxInstruments.parseKite(kite.lineSequence(), names, McxInstruments.multipliers(upstox))
                else McxInstruments.fromUpstox(upstox, names)).filter { !it.expiry.isBefore(today) }
            if (list.isEmpty()) {
                val old = cached()
                if (old.isNotEmpty()) return@withLock old
                throw java.io.IOException("Could not read MCX's contract list; try again in a moment")
            }
            val a = JSONArray()
            list.forEach { c ->
                a.put(JSONArray().put(c.token).put(c.exchangeToken).put(c.tradingSymbol).put(c.name).put(c.expiry.toString()).put(c.strike)
                    .put(c.right?.name ?: "").put(c.tick).put(c.multiplier))
            }
            val tmp = File(app.filesDir, "mcx_contracts.json.tmp")
            tmp.writeText(JSONObject().put("day", today.toString()).put("c", a).toString())
            if (!tmp.renameTo(contractsFile())) { contractsFile().writeText(tmp.readText()); tmp.delete() }
            mem = today.toString() to list
            list
        }
    }

    /** A contract by Zerodha's symbol or the paper symbol, from the list on the phone. */
    fun find(symbol: String): McxContract? = McxInstruments.find(cached(), symbol)

    fun find(name: String, expiry: LocalDate, strike: Double, right: Right?): McxContract? =
        cached().firstOrNull { it.name == name && it.expiry == expiry && it.right == right && (right == null || it.strike == strike) }

    /** Zerodha's instrument token for an MCX trading symbol (the live stream), or null. */
    fun tokenOf(tradingSymbol: String): Long? = cached().firstOrNull { it.tradingSymbol == tradingSymbol }?.token?.takeIf { it > 0 }

    /** The paper account's contract for [c]: units per lot as its lot (paper keeps units, as for NFO), a future's right IX. */
    fun paperContract(c: McxContract): Paper.Contract =
        Paper.Contract(c.paperSymbol, c.name, c.expiry, c.strike, c.right ?: Right.IX, c.multiplier, c.upstoxKey, Mcx.EXCHANGE)

    /** The near and next futures of every commodity (the Commodities card), in the table's order. */
    fun nearFutures(all: List<McxContract> = cached()): List<McxContract> =
        Mcx.NAMES.flatMap { McxInstruments.futures(all, it, Market.today(), 2) }

    // ---- margins ---------------------------------------------------------------------------------------------------------

    @Volatile private var margins: Pair<LocalDate, Map<String, Double>>? = null

    /** Zerodha's public margin per lot, read once a day; the fallback table when it cannot be read (never blocks a paper fill). */
    fun marginFeed(): Map<String, Double> = margins?.takeIf { it.first == Market.today() }?.second ?: emptyMap()

    suspend fun refreshMargins() {
        if (margins?.first == Market.today()) return
        val m = runCatching { McxMargin.parseKite(Broker.mcxMarginsText()) }.getOrDefault(emptyMap())
        if (m.isNotEmpty()) margins = Market.today() to m
    }

    // ---- prices ----------------------------------------------------------------------------------------------------------

    /** One contract's price. [lastSession]: the last session's close (MCX shut, or no candle yet today). */
    data class Quote(val contract: McxContract, val last: Double, val open: Double, val bid: Double?, val ask: Double?, val lastSession: Boolean) {
        val changePct: Double? get() = if (open > 0 && !lastSession) (last - open) / open else null
    }

    /**
     * [c]'s price: Live mode from Zerodha (its stream, else its quote call; not logged in is no price, never a silent
     * switch), Paper mode from Upstox's public candles (today's, else the last session's close).
     */
    suspend fun quote(c: McxContract, quick: Boolean = true): Quote? {
        if (AppSettings.load().live || Broker.loggedIn) {
            val q = runCatching { Broker.quotes(listOf(c.kiteKey))[c.kiteKey] }.getOrNull()
            if (q != null && q.last > 0) return Quote(c, q.last, q.open, q.bid, q.ask, false)
            if (AppSettings.load().live) return null
        }
        val today = Market.today()
        val bars = runCatching { if (quick) Net.intradayQuick(c.upstoxKey) else Net.intraday(c.upstoxKey) }.getOrDefault(emptyList()).filter { it.istDate == today }
        if (bars.isNotEmpty()) return Quote(c, bars.last().close, bars.first().open, null, null, false)
        val past = runCatching { ChartFeed.bars(c.tradingSymbol, "1m", null, null, quick = quick) }.getOrDefault(emptyList())
        val day = past.lastOrNull()?.istDate ?: return null
        val d = past.filter { it.istDate == day }
        return Quote(c, d.last().close, d.first().open, null, null, true)
    }

    /** Prices of [list]: one Zerodha call in Live mode (it takes many keys), else a few candle reads at a time. */
    suspend fun quotes(list: List<McxContract>): Map<String, Quote> = coroutineScope {
        if (list.isEmpty()) return@coroutineScope emptyMap()
        if (AppSettings.load().live || Broker.loggedIn) {
            val got = runCatching { Broker.quotes(list.map { it.kiteKey }) }.getOrNull()
            if (got != null && (got.isNotEmpty() || AppSettings.load().live))
                return@coroutineScope list.mapNotNull { c -> got[c.kiteKey]?.takeIf { it.last > 0 }?.let { q -> c.tradingSymbol to Quote(c, q.last, q.open, q.bid, q.ask, false) } }.toMap()
        }
        val gate = Semaphore(3)
        list.map { c -> async { gate.withPermit { runCatching { quote(c) }.getOrNull()?.let { c.tradingSymbol to it } } } }.awaitAll().filterNotNull().toMap()
    }

    // ---- the near-month chain ---------------------------------------------------------------------------------------------

    /**
     * [name]'s near-month option chain in the shape the Options tab prices ([Market.LiveChain]); its underlying (the
     * "spot" the analytics use) is the future the options turn into ([McxInstruments.underlyingFuture]). Lot = units per lot.
     */
    suspend fun chain(name: String, near: Int = 12): Market.LiveChain {
        val all = contracts()
        val today = Market.today()
        val options = McxInstruments.nearChain(all, name, today)
        if (options.isEmpty()) throw java.io.IOException("$name has no options listed on MCX")
        val fut = McxInstruments.underlyingFuture(all, options.first()) ?: throw java.io.IOException("no $name future for its options")
        val spot = quote(fut, quick = false)?.last ?: throw java.io.IOException("no price for ${fut.label}; MCX may be shut")
        val strikes = options.map { it.strike }.distinct().sortedBy { abs(it - spot) }.take(near * 2 + 1).toSet()
        val wanted = options.filter { it.strike in strikes }
        val contracts = wanted.map { Upstox.Contract(name, it.expiry, it.strike, it.right!!, it.multiplier, it.upstoxKey, it.tradingSymbol) }
        val expiry = wanted.first().expiry
        val lot = wanted.first().multiplier
        if (AppSettings.load().live || Broker.loggedIn) {
            val q = runCatching { Broker.quotes(wanted.map { it.kiteKey }) }.getOrNull()
            if (q != null && q.isNotEmpty()) {
                val minute = Market.minuteNow()
                val series = wanted.zip(contracts).mapNotNull { (m, c) ->
                    val qt = q[m.kiteKey]?.takeIf { it.last > 0 } ?: return@mapNotNull null
                    Series(c.expiry, c.strike, c.right, c.lotSize, intArrayOf(minute), doubleArrayOf(qt.last), doubleArrayOf(qt.last),
                        doubleArrayOf(qt.last), doubleArrayOf(qt.last), longArrayOf(qt.volume), longArrayOf(qt.oi))
                }
                if (series.isNotEmpty()) return Market.LiveChain(expiry, series, lot, contracts, spot, "Zerodha live quotes · MCX, underlying ${fut.label}", minute)
            }
            if (AppSettings.load().live) throw java.io.IOException("Zerodha returned no quotes for the $name chain")
        }
        val gate = Semaphore(6)
        val series = coroutineScope {
            contracts.map { c -> async { gate.withPermit { runCatching { Upstox.toSeries(c, Net.intraday(c.instrumentKey), today, contracts = false) }.getOrNull() } } }.awaitAll()
        }.filterNotNull().filter { it.size > 0 }
        if (series.isEmpty()) throw java.io.IOException("no candles yet for the $expiry $name chain; MCX may be shut")
        return Market.LiveChain(expiry, series, lot, contracts, spot, "Upstox public candles · MCX, underlying ${fut.label}")
    }

    // ---- what is held on MCX (the watch stays up for it in the evening) ------------------------------------------------------

    private const val K_LIVE_HELD = "mcx.liveHeld"

    /** The paper account holds an MCX position or has a working MCX order (no price read). */
    fun paperExposure(): Boolean = runCatching {
        val st = Paper.state
        st.positions.any { it.quantity != 0 && it.exchange == Mcx.EXCHANGE } ||
            st.orders.any { (it.status == "open" || it.status == "trigger pending") && it.exchange == Mcx.EXCHANGE }
    }.getOrDefault(false)

    /** Zerodha showed an open MCX position at the last look (kept for the day, so a restart keeps watching). */
    fun liveExposure(): Boolean = runCatching { SecurePrefs.getString(K_LIVE_HELD) == Market.today().toString() }.getOrDefault(false)

    /** Called with each read of Zerodha's positions. */
    fun noteLive(positions: List<Broker.Position>) {
        val held = positions.any { it.exchange == Mcx.EXCHANGE && it.qty != 0 }
        val now = liveExposure()
        if (held != now) runCatching { SecurePrefs.putAllSoon(mapOf(K_LIVE_HELD to if (held) Market.today().toString() else null)) }
    }

    /** An MCX order was just sent to Zerodha from the app: watched from now (the next read of the positions settles it). */
    fun markLive() { runCatching { SecurePrefs.putAllSoon(mapOf(K_LIVE_HELD to Market.today().toString())) } }

    fun exposure(): Boolean = paperExposure() || liveExposure()

    /**
     * The watch should run now for MCX: MCX is open and something is held or working there, or an MCX paper arm is on and
     * inside its minutes ([McxPaperArms.wantsWatch]: the evening break 17:00-23:16, the morning call 09:14-14:00).
     */
    fun watchDue(): Boolean = isOpen() && (exposure() || runCatching { McxPaperArms.wantsWatch() }.getOrDefault(false))

    /**
     * [watchDue] for the main thread (ANR fix, 9 Oct): from memory only - never a vault read (a Keystore decryption) and
     * never a wait on the paper book's lock, which a pass holds while it writes the book through the Keystore. Null when
     * that cannot be told without reading a vault (the paper book or the MCX arms not read yet in this process): the
     * caller then decides off the main thread, or lets the watch's own loop (off it) decide.
     */
    fun watchDueQuick(): Boolean? {
        if (!isOpen()) return false
        if (liveExposure()) return true
        // The books themselves when this process has read them, else what their last write left in the hint file.
        val paper = runCatching { Paper.mcxExposureIfLoaded() }.getOrNull() ?: hint(H_PAPER)
        if (paper == true) return true
        val arms = runCatching { McxPaperArms.wantsWatchIfLoaded() }.getOrNull() ?: hint(H_ARMS)?.let { armed -> if (armed) null else false }
        if (arms == true) return true
        return if (paper == null || arms == null) null else false
    }

    // ---- the watch hint: two booleans in a plain file, so the main thread never decrypts a vault to decide ----------------

    private const val H_PAPER = "paper"
    private const val H_ARMS = "arms"
    private fun hintFile() = File(app.filesDir, "mcx_watch.hint")
    private val hintLock = Any()
    /** The hint as this process last read or wrote it (null: not read yet). */
    private var hints: MutableMap<String, Boolean>? = null

    private fun hintsLocked(): MutableMap<String, Boolean> {
        hints?.let { return it }
        val m = HashMap<String, Boolean>()
        if (ready()) runCatching {
            val o = JSONObject(hintFile().readText())
            for (k in o.keys()) m[k] = o.getBoolean(k)
        }
        hints = m
        return m
    }

    /** What the hint file says for [key] (no vault, no network): null when nothing was ever written for it. */
    private fun hint(key: String): Boolean? = synchronized(hintLock) { hintsLocked()[key] }

    /** Kept when it changed: the paper book (after each write) says whether it holds or works anything on MCX. */
    fun notePaperExposure(held: Boolean) = noteHint(H_PAPER, held)

    /** Kept when it changed: whether any MCX paper arm is switched on (its book read or written). */
    fun noteArmsArmed(armed: Boolean) = noteHint(H_ARMS, armed)

    private fun noteHint(key: String, v: Boolean) {
        if (!ready()) return
        synchronized(hintLock) {
            val m = hintsLocked()
            if (m[key] == v) return
            m[key] = v
            runCatching { hintFile().writeText(JSONObject().apply { m.forEach { (k, x) -> put(k, x) } }.toString()) }
        }
    }

    /**
     * When the watch is next needed for MCX (epoch ms): MCX's next open while something is held or working there, or an
     * armed MCX paper arm's next window ([McxPaperArms.nextWakeMillis]), whichever is sooner; null otherwise (or not within a week).
     */
    fun nextOpenMillis(): Long? {
        val arms = runCatching { McxPaperArms.nextWakeMillis() }.getOrNull()
        val held = heldNextOpenMillis(exposure())
        return listOfNotNull(arms, held).minOrNull()
    }

    /** [exposure] from memory and the plain hint only (no vault read, no lock): for the main thread. Unknown counts as none. */
    fun exposureQuick(): Boolean = liveExposure() || (runCatching { Paper.mcxExposureIfLoaded() }.getOrNull() ?: hint(H_PAPER) ?: false)

    /** [nextOpenMillis] for the main thread: [exposureQuick], and the arms' next window only when their book is in memory. */
    fun nextOpenMillisQuick(): Long? {
        val arms = runCatching { McxPaperArms.nextWakeMillisIfLoaded() }.getOrNull()
        val held = runCatching { heldNextOpenMillis(exposureQuick()) }.getOrNull()
        return listOfNotNull(arms, held).minOrNull()
    }

    /** MCX's next open (epoch ms) while something is held or working there ([exposed]); null otherwise (or if not within a week). */
    private fun heldNextOpenMillis(exposed: Boolean): Long? {
        if (!exposed) return null
        val now = now()
        val c = calendar()
        for (i in 0..7) {
            val d = now.toLocalDate().plusDays(i.toLong())
            val w = c.window(d) ?: continue
            val open = d.atTime(w.open)
            if (open.isAfter(now)) return open.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()
        }
        return null
    }

    /** For tests: the day's lists and the calendar forgotten (their files go with the test's directories). */
    internal fun wipe() { mem = null; cal = null; margins = null; synchronized(hintLock) { hints = null } }
}
