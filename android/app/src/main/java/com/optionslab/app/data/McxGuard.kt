package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.mcx.Mcx
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.mcx.McxExpiry
import java.time.LocalTime
import kotlin.math.abs

/**
 * MCX expiry safety for a Rs 1 lakh account (9 Oct 2026, Boss; research/MCX_GUIDE.md 1d; the rules are [McxExpiry]):
 *
 *  - every MCX option position, paper and Zerodha, is closed by 23:00 on the trading day before its expiry (an MCX option
 *    that ends in the money turns into a future needing Rs 1.4-2.7 lakh of margin by 19:00 on expiry day);
 *  - gold, silver and base-metal futures (delivery-settled; Zerodha does not allow delivery) are closed 2 trading days
 *    before expiry; crude and natural gas settle in cash and are left alone;
 *  - a new MCX option buy is refused on expiry day and after 15:00 the day before, a new delivery future from its exit day
 *    ([entryRefusal]: the order sheets ask it).
 *
 * Runs from the market watch every pass while MCX trades (the NSE pass and the MCX evening pass), until a pass finds
 * nothing due still open: an exit sent is not a position closed. Settings → Bot settings → "MCX expiry exit" turns the
 * automatic closing off (on by default; the refusals stay - they only stop new risk). The kill switch does not stop it:
 * closing lowers risk. Zerodha: whenever there is a session (the positions are real in Paper mode too), as the 15:05
 * expiry square-off does; each exit is a MARKET order under every exit check ([Kite.refusals] with exit = true).
 */
object McxGuard {
    fun config(s: AppSettings): McxExpiry.Config = McxExpiry.Config(
        enabled = s.mcxExpiryExit,
        optionExitTime = LocalTime.of(s.mcxOptionExitMinute / 60, s.mcxOptionExitMinute % 60),
        futureExitTradingDays = s.mcxFutureExitDays,
    )

    /** Why a new MCX position in [c] may not be opened now ([buy]: a buy), or null. */
    fun entryRefusal(c: McxContract, buy: Boolean): String? = runCatching {
        McxExpiry.entryRefusal(c.name, c.expiry, c.isOption, buy, McxMarket.now(), McxMarket.calendar(), config(AppSettings.load()))
    }.getOrNull()

    /** [entryRefusal] for a paper contract. */
    fun entryRefusal(c: Paper.Contract, buy: Boolean): String? = if (!c.isMcx) null else runCatching {
        McxExpiry.entryRefusal(c.underlying, c.expiry, c.right != Right.IX, buy, McxMarket.now(), McxMarket.calendar(), config(AppSettings.load()))
    }.getOrNull()

    /** What the owner was already told ("day|what"), so a retry every pass does not repeat it. */
    private val told = HashSet<String>()

    private fun tellOnce(context: Context, key: String, title: String, text: String) {
        val k = "${Market.today()}|$key"
        synchronized(told) { if (!told.add(k)) return }
        runCatching { Diag.record("risk", "MCX: $text") }
        runCatching { Notifier.post(context, 2050, Notifier.RISK, title, text, "trade") }
    }

    suspend fun maybeRun(context: Context, s: AppSettings) {
        if (!s.mcxExpiryExit) return
        // Shut, nothing can be closed: whatever is due goes at the next session's first pass.
        if (!McxMarket.isOpen()) return
        val now = McxMarket.now()
        val cal = McxMarket.calendar()
        val cfg = config(s)

        // Paper: every MCX position whose exit is due (the book as held: no price is read unless one is due).
        runCatching {
            for (p in Paper.state.positions.filter { it.quantity != 0 && it.exchange == Mcx.EXCHANGE }) {
                val c = Paper.contractOf(p.symbol) ?: continue
                val due = McxExpiry.due(c.underlying, c.expiry, c.right != Right.IX, now, cal, cfg) ?: continue
                val r = Paper.close(p.symbol, p.product)
                r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", Origins.EXPIRY) } }
                if (r.ok) tellOnce(context, "paper ${p.symbol}", "MCX expiry exit", "Paper ${p.symbol} closed. ${due.text}")
                else tellOnce(context, "paper fail ${p.symbol}", "MCX expiry exit", "Paper ${p.symbol} could not be closed (${r.message}). Retrying every pass. ${due.text}")
            }
        }

        // Zerodha: whenever there is a session and an MCX position was seen there (the watch's own read of the positions,
        // an MCX order sent from the app); the pass's shared read. Shorts bought back before longs are sold.
        if (Broker.loggedIn && McxMarket.liveExposure()) runCatching {
            val book = Broker.passPositionBook().net
            McxMarket.noteLive(book)
            val held = book.filter { it.exchange == Mcx.EXCHANGE && it.qty != 0 }
            if (held.isEmpty()) return@runCatching
            val list = runCatching { McxMarket.contracts() }.getOrDefault(McxMarket.cached())
            val dueList = held.mapNotNull { p ->
                val c = list.firstOrNull { it.tradingSymbol == p.symbol }
                if (c == null) {
                    tellOnce(context, "unknown ${p.symbol}", "MCX position not checked",
                        "MCX ${p.symbol} (${p.qty}) is open and its expiry could not be read, so it is NOT closed automatically. Check it in Trade.")
                    null
                } else McxExpiry.due(c.name, c.expiry, c.isOption, now, cal, cfg)?.let { Triple(p, c, it) }
            }.sortedBy { if (it.first.qty < 0) 0 else 1 }
            if (dueList.isEmpty()) return@runCatching
            val working = Broker.orders().filter { it.working }
            for ((p, c, due) in dueList) {
                val side = if (p.qty < 0) Kite.Side.BUY else Kite.Side.SELL
                val same = working.filter { it.symbol == p.symbol && it.side == side.name && it.product == p.product }
                // A resting stop or target on it would keep the market exit from going: cancelled first, exit next pass.
                val others = same.filter { it.tag != "iraalgoexpiry" }
                if (others.isNotEmpty()) { others.forEach { runCatching { Broker.cancel(it.id, it.variety) } }; continue }
                val pending = same.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }
                // Zerodha keeps MCX positions and orders in lots: the whole open quantity, one lot at a time.
                val qty = com.optionslab.engine.risk.ExitQty.sendable(p.qty, pending, abs(p.qty), 1)
                if (qty <= 0) continue
                val o = Kite.Order(p.symbol, side, qty, 1, p.product, "MARKET", null, c.tick, Mcx.EXCHANGE, "iraalgoexpiry", multiplier = c.multiplier)
                val bad = Kite.refusals(o, s.limits(), Broker.sentToday(), false, exit = true)
                if (bad.isNotEmpty()) {
                    tellOnce(context, "refused ${p.symbol}", "MCX expiry exit", "${p.symbol}: the exit was not sent (${bad.joinToString("; ")}). Close it yourself in Trade. ${due.text}")
                    continue
                }
                try {
                    val id = Broker.placeOrder(o, exit = true)
                    runCatching { Strategies.tagOwner("kite:$id", Origins.EXPIRY) }
                    tellOnce(context, "live ${p.symbol}", "MCX expiry exit", "Zerodha ${p.symbol}: close sent. ${due.text}")
                } catch (e: Exception) {
                    if (Broker.definite(e)) tellOnce(context, "rejected ${p.symbol} ${e.message}", "MCX expiry exit",
                        "Zerodha refused the exit of ${p.symbol}: ${e.message}. Retrying every pass; close it yourself if it keeps failing.")
                    else runCatching { Broker.findRecentRetrying(o, working.map { it.id }) }
                }
            }
        }
    }
}
