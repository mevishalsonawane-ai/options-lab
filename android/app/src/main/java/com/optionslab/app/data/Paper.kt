package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.fmtG
import com.optionslab.engine.sandbox.Instrument
import com.optionslab.engine.sandbox.InstrumentMaster
import com.optionslab.engine.sandbox.OrderChange
import com.optionslab.engine.sandbox.OrderRequest
import com.optionslab.engine.sandbox.OrderResult
import com.optionslab.engine.sandbox.Quote
import com.optionslab.engine.sandbox.Sandbox
import com.optionslab.engine.sandbox.SandboxConfig
import com.optionslab.engine.sandbox.SandboxEvent
import com.optionslab.engine.sandbox.SandboxJson
import com.optionslab.engine.sandbox.SandboxState
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The sandbox account: IraAlgo's paper-trading engine (engine/sandbox, proven
 * step-for-step against the Python) running on the phone.
 *
 * SANDBOX MODE NEVER TOUCHES THE BROKER. Prices are Upstox's public 1-minute
 * candles, the same feed the rest of sandbox mode uses; orders, fills,
 * margin, MIS square-off and expiry settlement are all simulated here.
 *
 * Symbols are IraAlgo's (NIFTY29SEP2624500PE: underlying, DDMMMYY, strike,
 * right), because the engine reads the expiry from the symbol. Every
 * contract ever traded is remembered with its lot and feed key, so a
 * position can still be closed and settled after it drops off the master.
 *
 * The account lives in one encrypted vault file.
 */
object Paper {
    private lateinit var file: File

    fun init(context: Context) { file = File(context.applicationContext.filesDir, "paper.vault") }

    data class Contract(val symbol: String, val underlying: String, val expiry: LocalDate, val strike: Double, val right: Right,
                        val lotSize: Int, val feedKey: String)

    /** [why]: why the app cancelled an order (order id -> a reason key, see [cancel]); the newest [WHY_KEPT] only. */
    private data class Book(val state: SandboxState, val capital: BigDecimal, val contracts: Map<String, Contract>,
                            val why: Map<String, String> = emptyMap())

    /** How many cancel reasons are kept (the newest). */
    private const val WHY_KEPT = 300

    private val DDMMMYY = DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH)

    fun symbolOf(c: Upstox.Contract): String =
        c.underlying + c.expiry.format(DDMMMYY).uppercase(Locale.ENGLISH) + fmtG(c.strike) + c.right.name

    private var cache: Book? = null

    @Synchronized
    private fun book(): Book {
        cache?.let { return it }
        val loaded = runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching null, Charsets.UTF_8))
            val cs = o.optJSONArray("contracts") ?: JSONArray()
            val contracts = (0 until cs.length()).map { cs.getJSONArray(it) }.associate {
                it.getString(0) to Contract(it.getString(0), it.getString(1), LocalDate.parse(it.getString(2)), it.getDouble(3),
                    Right.valueOf(it.getString(4)), it.getInt(5), it.getString(6))
            }
            val w = o.optJSONObject("cancelWhy")
            val why = LinkedHashMap<String, String>()
            if (w != null) for (k in w.keys()) why[k] = w.optString(k)
            Book(SandboxJson.decode(o.getString("state")), BigDecimal(o.getString("capital")), contracts, why)
        }.getOrNull()
        // An unreadable account is set aside, never silently overwritten.
        if (loaded == null && file.exists()) file.renameTo(File(file.parentFile, "paper.unreadable.${System.currentTimeMillis()}"))
        val b = loaded ?: fresh(SandboxConfig().startingCapital, emptyMap())
        cache = b
        return b
    }

    private fun fresh(capital: BigDecimal, contracts: Map<String, Contract>) =
        Book(engine(capital, contracts).newState(Market.now()), capital, contracts)

    @Synchronized
    private fun save(b: Book) {
        val cs = JSONArray()
        b.contracts.values.forEach { cs.put(JSONArray().put(it.symbol).put(it.underlying).put(it.expiry.toString()).put(it.strike).put(it.right.name).put(it.lotSize).put(it.feedKey)) }
        val why = JSONObject(); b.why.forEach { (k, v) -> why.put(k, v) }
        Vault.writeFile(file, JSONObject().put("state", SandboxJson.encode(b.state)).put("capital", b.capital.toPlainString())
            .put("contracts", cs).put("cancelWhy", why).toString().toByteArray(Charsets.UTF_8))
        // An order placed, changed, cancelled or filled (any caller, or the book's own tick): Jarvis's kept account
        // figures are read afresh. Marking positions to the price alone does not count (the kept copy says its time).
        val ordersMoved = cache.let { was -> was == null || was.state.orders != b.state.orders || was.state.trades != b.state.trades }
        cache = b
        if (ordersMoved) runCatching { com.optionslab.app.ira.IraAccount.invalidate() }
    }

    private fun engine(capital: BigDecimal, contracts: Map<String, Contract>) = Sandbox(
        // The desktop sandbox's execution costs (TODO A9): stops slip 10 bps, a MARKET fill with no
        // bid/ask (the Upstox candle feed has none) slips 5 bps, and every leg pays its charges.
        // Every close's P&L reaches the balance (the desktop drops it when the position has no margin left to release).
        SandboxConfig(startingCapital = capital, stopSlippageBps = BigDecimal("10"), spreadFallbackBps = BigDecimal("5"), chargesEnabled = true,
            pnlAlwaysToFunds = true),
        InstrumentMaster { sym, ex ->
            if (ex != "NFO") null else contracts[sym]?.let {
                Instrument(sym, "NFO", "OPTIDX", it.lotSize, 0.05, it.expiry, it.strike)
            }
        },
    )

    val state: SandboxState get() = book().state
    /** An open order or an open position in the paper book (no price read): Battery, round 9. */
    fun watching(): Boolean = watched(book().state).isNotEmpty()
    val capital: BigDecimal get() = book().capital
    /** The contract a paper symbol stands for (underlying, expiry, type, lot), when it has been traded here. */
    fun contractOf(symbol: String): Contract? = book().contracts[symbol]
    fun engine(): Sandbox = book().let { engine(it.capital, it.contracts) }

    // ---- prices -----------------------------------------------------------------

    /**
     * The contract's price. With a Zerodha session the live tick from Zerodha's stream (updated many times a second,
     * the same price the exchange shows); otherwise, or until the stream has a tick for it, the last minute's close
     * from Upstox's public feed, with the day's range so the stale-quote check works.
     */
    suspend fun quote(c: Contract): Quote? {
        streamQuote(c)?.let { return it.also { remember(c.symbol, it) } }
        val bars = Net.intraday(c.feedKey).filter { it.istDate == Market.today() }
        if (bars.isEmpty()) return null
        candleBars[c.symbol] = System.currentTimeMillis() to bars
        return Quote(bars.last().close, high = bars.maxOf { it.high }, low = bars.minOf { it.low }, open = bars.first().open,
            volume = bars.sumOf { it.volume }).also { remember(c.symbol, it); candleQuotes[c.symbol] = System.currentTimeMillis() to it }
    }

    /** Today's 1-minute candles per symbol as [quote] last read them (when, bars): the minutes' highs and lows. */
    private val candleBars = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Upstox.Bar>>>()

    private fun epochMs(t: java.time.LocalDateTime): Long = t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    /** The day's candles of [c] that STARTED after the minute holding [since] (IST), from the ones [quote] read. */
    private fun barsAfter(c: Contract, since: java.time.LocalDateTime): List<Upstox.Bar>? {
        val bars = candleBars[c.symbol]?.second ?: return null
        val from = since.withSecond(0).withNano(0).atZone(com.optionslab.engine.IST).toEpochSecond()
        return bars.filter { it.epochSecond > from }.sortedBy { it.epochSecond }
    }

    /**
     * The highest price [c] traded after [since] (IST), for the profit lock's best price (research/HUNT_H20.md F2): every
     * tick of Zerodha's stream while it streams this contract, else the highs of the 1-minute candles that started after
     * [since]'s minute (the paper feed's; read again when the copy [quote] kept is older than [maxAgeMs]). Null: no price.
     */
    suspend fun highSince(c: Contract, since: java.time.LocalDateTime, maxAgeMs: Long = SHARED_QUOTE_MS): Double? {
        runCatching { kiteToken(c) }.getOrNull()?.takeIf { KiteStream.tick(it) != null }
            ?.let { tok -> KiteStream.highSince(tok, epochMs(since))?.let { return it } }
        val kept = candleBars[c.symbol]
        if (kept == null || System.currentTimeMillis() - kept.first !in 0..maxAgeMs) runCatching { quote(c) }
        return barsAfter(c, since)?.filter { it.istDate == Market.today() }?.maxOfOrNull { it.high }
    }

    /**
     * Where a resting SELL SL-M at [trigger], resting since [since] (placed or last moved), filled between two looks, as an
     * exchange fills it: at the trigger, or at the price that was already under it (a minute's open, a stream tick) - F1/F6.
     * The stream's ticks when it streams the contract, else the 1-minute candles [quote] read this pass; null: not reached
     * (or nothing to tell by), and the regular pass fills it on the last price as before.
     */
    private fun restingFill(c: Contract, since: java.time.LocalDateTime, trigger: Double): Double? {
        val tok = runCatching { kiteToken(c) }.getOrNull()?.takeIf { KiteStream.tick(it) != null }
        if (tok != null) return KiteStream.sellStopFill(tok, epochMs(since), trigger)
        val bars = barsAfter(c, since)?.filter { it.istDate == Market.today() } ?: return null
        return bars.firstNotNullOfOrNull { com.optionslab.engine.orb.ProfitLock.sellStopFill(trigger, it.open, it.low) }
    }

    /** The last price per symbol read from the day's candles (not the stream), and when: what [tick] may hand on. */
    private val candleQuotes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Quote>>()

    /** The candle prices [tick]'s last pass read itself (symbol -> when read, quote); replaced whole by each pass. */
    @Volatile private var tickCandles: Map<String, Pair<Long, Quote>> = emptyMap()

    /** How old a price [tick] read may be when [stopPrice] hands it on (the same pass's next step, never a later pass). */
    const val TICK_PRICE_MS = com.optionslab.ira.StopPrice.TICK_PRICE_MS

    /**
     * Battery (round 6): the price for a trailing stop in the same watch pass, right after [tick]. Exactly what
     * [lastPrice] gives - the stream's tick when there is one (no network), else the day's candles - except that
     * without a stream the candle price [tick] itself read for this symbol under [TICK_PRICE_MS] ago is handed on
     * instead of downloading the same candles again. The feed moves once a minute, so a read 2 s later is the same
     * close; it is also the very price the stop orders were just checked against. Older, or not read by [tick]: a
     * fresh read, as before.
     */
    suspend fun stopPrice(c: Contract): Double? {
        // Round 7: the choice is [com.optionslab.ira.StopPrice.source] (pure, tested: never a read 2 s old or from a past pass).
        val live = runCatching { streamQuote(c) }.getOrNull()
        val tickRead = tickCandles[c.symbol]
        return when (com.optionslab.ira.StopPrice.source(live != null, tickRead?.first, System.currentTimeMillis())) {
            com.optionslab.ira.StopPrice.Source.STREAM -> live?.let { q -> remember(c.symbol, q); q.ltp } ?: lastPrice(c)
            com.optionslab.ira.StopPrice.Source.TICK_READ -> tickRead?.second?.ltp ?: lastPrice(c)
            com.optionslab.ira.StopPrice.Source.FRESH -> lastPrice(c)
        }
    }

    /** The last price read for each symbol and when (the screen re-prices every few seconds). */
    private val lastQuotes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Quote>>()
    private fun remember(symbol: String, q: Quote) { lastQuotes[symbol] = System.currentTimeMillis() to q }

    /**
     * A price for a close or a cancel the owner just slid: the stream's tick, else one read in the last
     * [maxAgeMs], else a fresh read. The slide acts at once instead of waiting on the day's candles.
     */
    private suspend fun quickQuote(symbol: String, maxAgeMs: Long = 5_000): Quote? {
        val c = book().contracts[symbol] ?: return null
        streamQuote(c)?.let { return it }
        lastQuotes[symbol]?.let { (at, q) -> if (System.currentTimeMillis() - at <= maxAgeMs) return q }
        return runCatching { quote(c) }.getOrNull()
    }

    /** Zerodha's instrument token per paper symbol (0 = not listed there), looked up once. */
    private val kiteTokens = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun kiteToken(c: Contract): Long? {
        kiteTokens[c.symbol]?.let { return it.takeIf { t -> t > 0 } }
        if (!Broker.loggedIn) return null
        val list = Broker.cachedInstruments() ?: return null
        val t = Broker.find(list, c.underlying, c.expiry, c.strike, c.right)?.token ?: 0L
        kiteTokens[c.symbol] = t
        return t.takeIf { it > 0 }
    }

    /** The live tick from Zerodha's stream, if there is a fresh one; asking also keeps the contract subscribed. */
    private fun streamQuote(c: Contract): Quote? {
        val token = runCatching { kiteToken(c) }.getOrNull() ?: return null
        KiteStream.touch(listOf(token))
        val t = KiteStream.tick(token) ?: return null
        if (t.last <= 0) return null
        return Quote(t.last, bid = t.bid ?: 0.0, ask = t.ask ?: 0.0, high = t.high, low = t.low, open = t.open,
            prevClose = t.close, volume = t.volume, bidQty = t.bidQty ?: 0L, askQty = t.askQty ?: 0L)
    }

    /** The contract's latest price from the paper feed, or null when there is none today. */
    suspend fun lastPrice(c: Contract): Double? = runCatching { quote(c) }.getOrNull()?.ltp

    /**
     * Every symbol's quote, fetched in parallel (one round trip for the whole book, not one per position). [reuseMs] > 0
     * (words and cards only, [snapshot]): a price this process read at most that long ago is used instead of a new read.
     */
    private suspend fun quotes(symbols: Collection<String>, reuseMs: Long = 0L): Map<String, Quote> = kotlinx.coroutines.coroutineScope {
        val b = book()
        symbols.distinct().mapNotNull { s -> b.contracts[s]?.let { c -> s to c } }
            .map { (s, c) -> async { runCatching { if (reuseMs > 0L) recentQuote(c, reuseMs) else quote(c) }.getOrNull()?.let { Sandbox.key(s, "NFO") to it } } }
            .mapNotNull { it.await() }.toMap()
    }

    /**
     * Battery (round 5): the stream's tick (no network), else a price read in the last [maxAgeMs], else a fresh read.
     * Without a Zerodha session every paper price is a download of the contract's whole day of 1-minute candles, and a
     * watch pass read it once for the stops ([tick]) and again, seconds later, for the position cards and for each
     * words-only check ([snapshot] with [SHARED_QUOTE_MS]). The feed moves once a minute; the stops always read fresh.
     */
    internal suspend fun recentQuote(c: Contract, maxAgeMs: Long): Quote? {
        streamQuote(c)?.let { return it.also { remember(c.symbol, it) } }
        lastQuotes[c.symbol]?.let { (at, q) -> if (System.currentTimeMillis() - at in 0..maxAgeMs) return q }
        return quote(c)
    }

    /** How old a price the words-only checks and the position cards may share ([snapshot]); never the stops or limits. */
    const val SHARED_QUOTE_MS = 20_000L

    /** Symbols the engine needs prices for: open orders and open positions. */
    private fun watched(s: SandboxState): Set<String> =
        (s.orders.filter { it.status == "open" || it.status == "trigger pending" }.map { it.symbol } +
            s.positions.filter { it.quantity != 0 }.map { it.symbol }).toSet()

    // ---- actions -----------------------------------------------------------------

    data class Result(val ok: Boolean, val message: String, val events: List<SandboxEvent>, val orderId: String? = null)

    private fun describe(r: OrderResult, events: List<SandboxEvent>): Result {
        val fill = events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        val msg = when {
            !r.ok -> r.message ?: "refused"
            fill != null -> "Paper ${fill.action} ${fill.quantity} ${fill.symbol} filled @ ${"%.2f".format(Locale.ENGLISH, fill.price)}"
            else -> r.message ?: "Paper order placed"
        }
        return Result(r.ok, msg, events, r.orderId)
    }

    /** Resolve a listed option into a paper contract (and remember it). */
    fun contractFor(underlying: String, expiry: LocalDate, strike: Double, right: Right): Contract? {
        val c = Market.contracts().firstOrNull { it.underlying == underlying && it.expiry == expiry && it.strike == strike && it.right == right }
            ?: return null
        return Contract(symbolOf(c), c.underlying, c.expiry, c.strike, c.right, c.lotSize, c.instrumentKey)
    }


    /**
     * TEST SEAM (JVM tests only): the tests' fake candle feeds are a few fixed minutes of the morning, so the 08 Oct checks on
     * the candle feed (a stale price is never an entry's fill, [staleEntry]; Pine's thin-option check and its best price
     * from the minute highs) are skipped unless a test turns this off. False in the app, always: its setter throws in a
     * release build.
     */
    @Volatile internal var testSkipFeedChecks: Boolean = false
        set(v) {
            check(com.optionslab.app.BuildConfig.DEBUG) { "the feed-check seam exists only in debug builds" }
            field = v
        }

    /** Today's 1-minute candles of [c] from the paper feed (kept, as [quote] keeps them, for the minutes' highs and lows). */
    suspend fun minutes(c: Contract): List<Upstox.Bar> {
        val bars = Net.intraday(c.feedKey).filter { it.istDate == Market.today() }
        if (bars.isNotEmpty()) candleBars[c.symbol] = System.currentTimeMillis() to bars
        return bars
    }

    /**
     * Why a MARKET order that opens or adds to a position must not fill now (08 Oct, research/PROFIT_LOCK_8OCT.md fix 4):
     * without Zerodha's stream the only price is the last 1-minute candle's close, and when the contract has not traded
     * for a while ([com.optionslab.engine.risk.StaleEntry]) that close is not a price anyone could buy at (Pine #17 bought at
     * a 368.90 the market had left). Null when it may fill: the stream streams the contract, the candles are fresh, or the
     * order only reduces what is held (an exit always fills, on the candle as before).
     */
    private suspend fun staleEntry(c: Contract, action: String, priceType: String): String? {
        if (testSkipFeedChecks || priceType != "MARKET") return null
        val held = book().state.positions.filter { it.symbol == c.symbol }.sumOf { it.quantity }
        val opens = if (action.equals("BUY", true)) held >= 0 else held <= 0
        if (!opens) return null
        if (runCatching { streamQuote(c) }.getOrNull() != null) return null
        val kept = candleBars[c.symbol]
        val bars = if (kept != null && System.currentTimeMillis() - kept.first in 0..STALE_READ_MS) kept.second
            else runCatching { minutes(c) }.getOrNull() ?: return null
        return com.optionslab.engine.risk.StaleEntry.refusal(bars.map { it.epochSecond to it.volume }, System.currentTimeMillis() / 1000, c.symbol)
    }

    /** How old the candles [staleEntry] reads may be (the ones the caller's [quote] read just now): else read again. */
    private const val STALE_READ_MS = 30_000L

    /** [known]: the contract's quote when the caller has just read it (read here otherwise). */
    suspend fun place(c: Contract, action: String, lots: Int, priceType: String, product: String, price: Double?, trigger: Double?,
                      known: Quote? = null): Result {
        val q = known ?: runCatching { quote(c) }.getOrNull()
        // A paper entry never fills on a stale candle close (fix 4): refused with the reason, nothing placed.
        staleEntry(c, action, priceType)?.let { return Result(false, it, emptyList()) }
        synchronized(this) {
            // Remember the contract and place in one step, so a concurrent place cannot overwrite either.
            val b0 = book()
            val b = if (b0.contracts[c.symbol] == c) b0 else b0.copy(contracts = b0.contracts + (c.symbol to c))
            val out = engine(b.capital, b.contracts).place(b.state,
                OrderRequest(c.symbol, "NFO", action, lots * c.lotSize, priceType, product, price, trigger, "IraAlgo-Android"), q, Market.now())
            save(b.copy(state = out.state))
            return describe(out.result, out.events)
        }
    }

    fun modify(orderId: String, quantity: Int?, price: Double?, trigger: Double?): Result = synchronized(this) {
        val b = book()
        val out = engine(b.capital, b.contracts).modify(b.state, orderId, OrderChange(quantity, price, trigger), Market.now())
        save(b.copy(state = out.state))
        describe(out.result, out.events)
    }

    /**
     * Cancel a working order. [why] is why the app cancelled it, kept beside the order so Jarvis can say why later ("why was
     * my last order cancelled"): a key such as "position_closed", "exit:index_stop", "oco:stop", "unfilled_market", "you",
     * "jarvis", "strategy", "protection_removed" (worded by com.optionslab.ira.OrderWhy). Only a note: the cancel itself
     * is exactly as before.
     */
    suspend fun cancel(orderId: String, why: String? = null): Result {
        val sym = book().state.orders.firstOrNull { it.orderId == orderId }?.symbol
        val q = sym?.let { quickQuote(it) }
        synchronized(this) {
            val b = book()
            val out = engine(b.capital, b.contracts).cancel(b.state, orderId, Market.now(), q)
            val ended = out.events.any { it is SandboxEvent.OrderUpdate && it.orderId == orderId && it.status == "cancelled" }
            save(b.copy(state = out.state, why = if (ended && why != null) noted(b.why, mapOf(orderId to why)) else b.why))
            return describe(out.result, out.events)
        }
    }

    /** Why the app cancelled paper order [orderId] (a key from [cancel]), when it noted one. */
    fun cancelReason(orderId: String): String? = book().why[orderId]

    private fun noted(old: Map<String, String>, add: Map<String, String>): Map<String, String> {
        val m = LinkedHashMap(old); m.putAll(add)
        return if (m.size <= WHY_KEPT) m else LinkedHashMap(m.entries.drop(m.size - WHY_KEPT).associate { it.key to it.value })
    }

    suspend fun close(symbol: String, product: String): Result {
        val q = quickQuote(symbol)
        synchronized(this) {
            val b = book()
            val out = engine(b.capital, b.contracts).closePosition(b.state, symbol, "NFO", product, q, Market.now())
            save(b.copy(state = out.state))
            return describe(out.result, out.events)
        }
    }

    /**
     * One pass of IraAlgo's scheduled jobs: catch up after downtime, fill
     * resting orders against fresh prices, square off MIS past 15:15, settle
     * expired contracts and T+1. Called by the Trade tab and the watch.
     */
    suspend fun tick(): List<SandboxEvent> {
        val events = tickLocked()
        // The engine's own 15:15 MIS square-off is named on the order it placed.
        events.filterIsInstance<SandboxEvent.SquareOff>().mapNotNull { it.result.orderId }
            .forEach { runCatching { Strategies.tagOwner("paper:$it", Origins.AUTO_SQUARE_OFF) } }
        return events
    }

    private suspend fun tickLocked(): List<SandboxEvent> {
        val syms = watched(book().state)
        val q = quotes(syms)
        // The candle prices this pass read itself, kept for [stopPrice] (the stream's ticks are read afresh there).
        tickCandles = syms.mapNotNull { sym ->
            val got = q[Sandbox.key(sym, "NFO")] ?: return@mapNotNull null
            candleQuotes[sym]?.takeIf { com.optionslab.ira.StopPrice.handedOn(got, it.second) }?.let { sym to it }
        }.toMap()
        synchronized(this) {
            val b = book()
            val e = engine(b.capital, b.contracts)
            val now = Market.now()
            val events = ArrayList<SandboxEvent>()
            var s = b.state
            e.catchUp(s, now).also { s = it.state; events += it.events }
            // A resting SELL SL-M (a stop, or one the profit lock moved up) touched between two looks fills where it would
            // have at the exchange - its trigger, or the price already under it - not at whatever the price is now.
            for (o in s.orders.filter { it.status == "trigger pending" && it.priceType == "SL-M" && it.action == "SELL" }) {
                val c = b.contracts[o.symbol] ?: continue
                if (q[Sandbox.key(o.symbol, "NFO")] == null) continue          // only on a pass that read this contract's price
                val trig = o.triggerPrice?.toDouble() ?: continue
                val px = runCatching { restingFill(c, o.updateTimestamp, trig) }.getOrNull() ?: continue
                e.fillRestingStop(s, o.orderId, px, now).also { if (it.result.ok) { s = it.state; events += it.events } }
            }
            e.onQuotes(s, q, now).also { s = it.state; events += it.events }
            // Marks positions to the fresh LTP, which expiry settlement prices from.
            e.positionBook(s, now, q).also { s = it.state; events += it.events }
            // The square-off's own events kept apart: only its cancels are the 15:15 square-off (reason text only).
            val squareFrom = events.size
            e.squareOffDue(s, now, q).also { s = it.state; events += it.events }
            val squareTo = events.size
            e.settleExpiries(s, now).also { s = it.state; events += it.events }
            // The book's own cancels here: a contract's expiry, the 15:15 MIS square-off, or (the catch-up after a day's end,
            // anything else) the day ending - noted, so Jarvis can say why. Labels for the reason text only.
            val own = events.withIndex().filter { (_, ev) -> ev is SandboxEvent.OrderUpdate && ev.status == "cancelled" }.associate { (i, ev) ->
                val u = ev as SandboxEvent.OrderUpdate
                val o = s.orders.firstOrNull { it.orderId == u.orderId }
                val exp = o?.symbol?.let { sym -> b.contracts[sym]?.expiry }
                u.orderId to when {
                    exp != null && !exp.isAfter(now.toLocalDate()) -> "expiry"
                    i in squareFrom until squareTo && o?.product == "MIS" -> "square_off"
                    i in squareFrom until squareTo -> "expiry"
                    else -> "day_end"
                }
            }
            if (s != b.state) save(b.copy(state = s, why = if (own.isEmpty()) b.why else noted(b.why, own)))
            return events
        }
    }

    /** One resting stop the sweeper sold: its symbol, its trigger and where it filled. */
    data class Swept(val symbol: String, val trigger: Double, val price: Double, val orderId: String)

    /**
     * The missed-lock sweeper's paper half (08 Oct, Boss's item 7): every resting SELL SL-M whose contract's price now
     * ([quote]: the stream's tick, else the day's candles) is already at or under its trigger is filled now at that price
     * ([Sandbox.fillRestingStop]) - the resting order itself, so the owner (an ORB arm, a Pine script, a protection) sees
     * its stop filled and there is never a second sell. Also says whether any price was read ([Sweep.priced]) for the
     * no-price failsafe. Paper only: nothing reaches Zerodha.
     */
    suspend fun sweepMissedStops(): Sweep {
        val stops = book().state.orders.filter { it.status == "trigger pending" && it.priceType == "SL-M" && it.action == "SELL" }
        val held = book().state.positions.filter { it.quantity != 0 }.map { it.symbol }.toSet()
        val q = quotes(stops.map { it.symbol } + held)
        val out = ArrayList<Swept>()
        synchronized(this) {
            val b = book()
            val e = engine(b.capital, b.contracts)
            val now = Market.now()
            var s = b.state
            for (o in s.orders.filter { it.status == "trigger pending" && it.priceType == "SL-M" && it.action == "SELL" }) {
                val px = q[Sandbox.key(o.symbol, "NFO")]?.ltp ?: continue
                val trig = o.triggerPrice?.toDouble() ?: continue
                if (!com.optionslab.engine.risk.MissedLock.missed(px, trig)) continue
                val r = e.fillRestingStop(s, o.orderId, px, now)
                if (r.result.ok) { s = r.state; out += Swept(o.symbol, trig, s.orders.firstOrNull { it.orderId == o.orderId }?.averagePrice?.toDouble() ?: px, o.orderId) }
            }
            if (s != b.state) save(b.copy(state = s))
        }
        return Sweep(out, q.isNotEmpty(), held.isNotEmpty())
    }

    /** What [sweepMissedStops] did: the stops it sold, whether any price was read, and whether any position is open. */
    data class Sweep(val swept: List<Swept>, val priced: Boolean, val holding: Boolean)

    /**
     * Views, priced with fresh quotes where the engine uses them. [reuseQuotesMs] > 0 only for words and cards (never the
     * loss limit, the square-off, stops or anything that orders): a price read that recently is used again; the book
     * itself is always read as it is now.
     */
    suspend fun snapshot(reuseQuotesMs: Long = 0L): Snapshot {
        val q = quotes(watched(book().state) + book().state.holdings.map { it.symbol }, reuseQuotesMs)
        synchronized(this) {
            val b = book()
            val e = engine(b.capital, b.contracts)
            val now = Market.now()
            val funds = e.funds(b.state, now)
            val pos = e.positionBook(funds.state, now, q)
            val hold = e.holdings(pos.state, now, q)
            // The funds read again after re-pricing (and any expiry settlement), so they agree with the positions shown.
            val after = e.funds(hold.state, now)
            if (after.state != b.state) save(b.copy(state = after.state))
            return Snapshot(after.result, pos.result, e.orderBook(after.state, now), e.tradeBook(after.state, now), hold.result, q.isNotEmpty() || watched(after.state).isEmpty(),
                chargesSinceReset(after.state))
        }
    }

    /**
     * The books as this phone holds them, marked at the prices they were last marked to: no price read (no network) and
     * nothing saved. With when the account was last saved (epoch ms), so a figure from it carries its own "as of". Null
     * when the paper account was never used on this phone (nothing to show). For the "Open" home-screen widget.
     */
    fun localSnapshot(): Pair<Snapshot, Long>? {
        val savedAt = runCatching { file.lastModified() }.getOrDefault(0L)
        if (savedAt <= 0L) return null
        synchronized(this) {
            val b = book()
            val e = engine(b.capital, b.contracts)
            val now = Market.now()
            val funds = e.funds(b.state, now)
            val pos = e.positionBook(funds.state, now)
            val hold = e.holdings(pos.state, now)
            val after = e.funds(hold.state, now)
            return Snapshot(after.result, pos.result, e.orderBook(after.state, now), e.tradeBook(after.state, now), hold.result, false, chargesSinceReset(after.state)) to savedAt
        }
    }

    /** Every charge the account paid since its last reset (the funds' Total P&L is after them). */
    private fun chargesSinceReset(st: SandboxState): Double =
        st.trades.filter { !it.timestamp.isBefore(st.funds.lastResetDate) }.sumOf { it.charges.toDouble() }

    data class Snapshot(
        val funds: com.optionslab.engine.sandbox.FundsView,
        val positions: com.optionslab.engine.sandbox.PositionBook,
        val orders: com.optionslab.engine.sandbox.OrderBook,
        val trades: List<com.optionslab.engine.sandbox.TradeRow>,
        val holdings: com.optionslab.engine.sandbox.HoldingsBook,
        val priced: Boolean,
        /** Every charge paid since the last reset: the header's Total P&L is shown before them ([totalGross]). */
        val chargesSinceReset: Double = 0.0,
    ) {
        /**
         * The day's P&L AFTER charges: the positions' (realised today + unrealised) less the charges of today's trades.
         * Read from the positions, not the funds' running tally, so the calendar's kept figure and the loss limits can
         * never disagree (a build before the funds fix left the tally short). RISK LIMITS READ THIS ONE (the daily loss
         * limit, the guard, the loss breaker, Jarvis's checks): after charges is the safer figure. Screens show
         * [dayGross] with [dayCharges] under it (Boss, 5 Oct), never this as the headline.
         */
        val dayPnl: Double get() = positions.totalPnlToday - dayCharges

        /** Today's charges (the trade book holds today's trades only). */
        val dayCharges: Double get() = trades.sumOf { it.charges }

        /** The day's P&L BEFORE charges, as every screen shows it (as Zerodha shows its own): display only, never a limit. */
        val dayGross: Double get() = com.optionslab.ira.PnlCharges.gross(dayPnl, dayCharges)

        /** The funds' Total P&L (since the last reset) before charges, for display: the funds themselves stay as they are. */
        val totalGross: Double get() = com.optionslab.ira.PnlCharges.gross(funds.totalPnl, chargesSinceReset)
    }

    /** Back to a fresh account with [capital]; the contracts seen are kept. */
    @Synchronized
    fun reset(capital: BigDecimal) { save(fresh(capital, book().contracts)) }

    @Synchronized
    fun wipe() { cache = null; file.delete(); lastQuotes.clear(); candleQuotes.clear(); candleBars.clear(); tickCandles = emptyMap() }
}
