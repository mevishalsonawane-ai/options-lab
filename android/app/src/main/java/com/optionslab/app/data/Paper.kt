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
        cache = b
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
        return Quote(bars.last().close, high = bars.maxOf { it.high }, low = bars.minOf { it.low }, open = bars.first().open,
            volume = bars.sumOf { it.volume }).also { remember(c.symbol, it); candleQuotes[c.symbol] = System.currentTimeMillis() to it }
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
            prevClose = t.close, volume = t.volume)
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


    /** [known]: the contract's quote when the caller has just read it (read here otherwise). */
    suspend fun place(c: Contract, action: String, lots: Int, priceType: String, product: String, price: Double?, trigger: Double?,
                      known: Quote? = null): Result {
        val q = known ?: runCatching { quote(c) }.getOrNull()
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
            return Snapshot(after.result, pos.result, e.orderBook(after.state, now), e.tradeBook(after.state, now), hold.result, q.isNotEmpty() || watched(after.state).isEmpty())
        }
    }

    data class Snapshot(
        val funds: com.optionslab.engine.sandbox.FundsView,
        val positions: com.optionslab.engine.sandbox.PositionBook,
        val orders: com.optionslab.engine.sandbox.OrderBook,
        val trades: List<com.optionslab.engine.sandbox.TradeRow>,
        val holdings: com.optionslab.engine.sandbox.HoldingsBook,
        val priced: Boolean,
    ) {
        /**
         * The day's P&L after charges, as every screen shows it: the positions' (realised today + unrealised) less the
         * charges of today's trades. Read from the positions, not the funds' running tally, so Home, the positions card,
         * the calendar and the loss limits can never disagree (a build before the funds fix left the tally short).
         */
        val dayPnl: Double get() = positions.totalPnlToday - trades.sumOf { it.charges }
    }

    /** Back to a fresh account with [capital]; the contracts seen are kept. */
    @Synchronized
    fun reset(capital: BigDecimal) { save(fresh(capital, book().contracts)) }

    @Synchronized
    fun wipe() { cache = null; file.delete(); lastQuotes.clear(); candleQuotes.clear(); tickCandles = emptyMap() }
}
