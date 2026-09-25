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

    private data class Book(val state: SandboxState, val capital: BigDecimal, val contracts: Map<String, Contract>)

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
            Book(SandboxJson.decode(o.getString("state")), BigDecimal(o.getString("capital")), contracts)
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
        Vault.writeFile(file, JSONObject().put("state", SandboxJson.encode(b.state)).put("capital", b.capital.toPlainString())
            .put("contracts", cs).toString().toByteArray(Charsets.UTF_8))
        cache = b
    }

    private fun engine(capital: BigDecimal, contracts: Map<String, Contract>) = Sandbox(
        SandboxConfig(startingCapital = capital),
        InstrumentMaster { sym, ex ->
            if (ex != "NFO") null else contracts[sym]?.let {
                Instrument(sym, "NFO", "OPTIDX", it.lotSize, 0.05, it.expiry, it.strike)
            }
        },
    )

    val state: SandboxState get() = book().state
    val capital: BigDecimal get() = book().capital
    fun engine(): Sandbox = book().let { engine(it.capital, it.contracts) }

    // ---- prices -----------------------------------------------------------------

    /** The last minute's close, with the day's range so the stale-quote check works. */
    private suspend fun quote(c: Contract): Quote? {
        val bars = Net.intraday(c.feedKey).filter { it.istDate == Market.today() }
        if (bars.isEmpty()) return null
        return Quote(bars.last().close, high = bars.maxOf { it.high }, low = bars.minOf { it.low }, open = bars.first().open,
            volume = bars.sumOf { it.volume })
    }

    private suspend fun quotes(symbols: Collection<String>): Map<String, Quote> {
        val b = book()
        return symbols.distinct().mapNotNull { s ->
            val c = b.contracts[s] ?: return@mapNotNull null
            runCatching { quote(c) }.getOrNull()?.let { Sandbox.key(s, "NFO") to it }
        }.toMap()
    }

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


    suspend fun place(c: Contract, action: String, lots: Int, priceType: String, product: String, price: Double?, trigger: Double?): Result {
        val q = runCatching { quote(c) }.getOrNull()
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

    suspend fun cancel(orderId: String): Result {
        val sym = book().state.orders.firstOrNull { it.orderId == orderId }?.symbol
        val q = sym?.let { quotes(listOf(it))[Sandbox.key(it, "NFO")] }
        synchronized(this) {
            val b = book()
            val out = engine(b.capital, b.contracts).cancel(b.state, orderId, Market.now(), q)
            save(b.copy(state = out.state))
            return describe(out.result, out.events)
        }
    }

    suspend fun close(symbol: String, product: String): Result {
        val q = quotes(listOf(symbol))[Sandbox.key(symbol, "NFO")]
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
        val q = quotes(watched(book().state))
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
            e.squareOffDue(s, now, q).also { s = it.state; events += it.events }
            e.settleExpiries(s, now).also { s = it.state; events += it.events }
            if (s != b.state) save(b.copy(state = s))
            return events
        }
    }

    /** Views, priced with fresh quotes where the engine uses them. */
    suspend fun snapshot(): Snapshot {
        val q = quotes(watched(book().state) + book().state.holdings.map { it.symbol })
        synchronized(this) {
            val b = book()
            val e = engine(b.capital, b.contracts)
            val now = Market.now()
            val funds = e.funds(b.state, now)
            val pos = e.positionBook(funds.state, now, q)
            val hold = e.holdings(pos.state, now, q)
            if (hold.state != b.state) save(b.copy(state = hold.state))
            return Snapshot(funds.result, pos.result, e.orderBook(hold.state, now), e.tradeBook(hold.state, now), hold.result, q.isNotEmpty() || watched(hold.state).isEmpty())
        }
    }

    data class Snapshot(
        val funds: com.optionslab.engine.sandbox.FundsView,
        val positions: com.optionslab.engine.sandbox.PositionBook,
        val orders: com.optionslab.engine.sandbox.OrderBook,
        val trades: List<com.optionslab.engine.sandbox.TradeRow>,
        val holdings: com.optionslab.engine.sandbox.HoldingsBook,
        val priced: Boolean,
    )

    /** Back to a fresh account with [capital]; the contracts seen are kept. */
    @Synchronized
    fun reset(capital: BigDecimal) { save(fresh(capital, book().contracts)) }

    @Synchronized
    fun wipe() { cache = null; file.delete() }
}
