package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Kite
import kotlin.math.abs

/**
 * TODO A5: on an expiry day, at 15:05, close every open option position that
 * expires today - paper and live, every product (MIS and NRML) - instead of
 * holding it into the settlement. Runs from the market watch; once it has
 * gone through, it does not repeat that day.
 *
 * The Expiry Put ticket is designed to be held to the 15:30 settlement, so its
 * legs are left alone unless the owner turns that off. The kill switch stops
 * this like any other order.
 */
object ExpirySquareOff {
    const val AT_MINUTE = 15 * 60 + 5
    private const val K_DONE = "sq.expiry.done"

    suspend fun maybeRun(context: Context, s: AppSettings) {
        // The kill switch stops new orders, not this: closing what expires today only lowers risk.
        if (!s.expirySquareOff) return
        val today = Market.today()
        if (Market.minuteNow() < AT_MINUTE || Market.minuteNow() >= Market.CLOSE) return
        if (SecurePrefs.getString(K_DONE) == today.toString()) return
        val ticket = Ledger.openTicket()?.row?.ticket?.takeIf { s.keepExpiryPut && it.session == today }
        fun isTicketLeg(underlying: String?, strike: Double?, right: String?): Boolean =
            ticket != null && underlying == ticket.underlying && right == "PE" && (strike == ticket.strike || strike == ticket.wingStrike)

        val closed = ArrayList<String>()
        var failed = false

        // Paper: every option position expiring today.
        runCatching {
            val snap = Paper.snapshot()
            for (p in snap.positions.positions.filter { it.quantity != 0 }) {
                val c = Paper.contractOf(p.symbol) ?: continue
                if (c.expiry != today || isTicketLeg(c.underlying, c.strike, c.right.name)) continue
                val r = Paper.close(p.symbol, p.product)
                if (r.ok) closed += "paper ${p.symbol}" else failed = true
            }
        }.onFailure { failed = true }

        // Live: whenever there is a Zerodha session (the positions are real even while the app shows Paper);
        // shorts are bought back before longs are sold.
        if (Broker.loggedIn) runCatching {
            val ins = Broker.cachedInstruments().orEmpty().associateBy { it.tradingSymbol }
            val open = Broker.positionBook().net.filter { it.qty != 0 && it.exchange == "NFO" }
                .filter { p -> ins[p.symbol]?.let { it.expiry == today && !isTicketLeg(it.name, it.strike, it.right.name) } == true }
                .sortedBy { if (it.qty < 0) 0 else 1 }
            // Exits already working at Zerodha (an earlier pass's order resting in a thin book, or one whose
            // reply was lost) are subtracted first, so a retry never sends a second exit and reverses the position.
            val working = runCatching { Broker.orders() }.getOrElse { failed = true; return@runCatching }.filter { it.working }
            for (p in open) {
                val i = ins.getValue(p.symbol)
                val side = if (p.qty < 0) Kite.Side.BUY else Kite.Side.SELL
                val same = working.filter { it.symbol == p.symbol && it.side == side.name && it.product == p.product }
                // Any other resting exit on it (a protection's stop or target, a strategy's stop) would keep the
                // market exit from ever going out: cancel them first; the exit goes on the next pass.
                val others = same.filter { it.tag != "iraalgoexpiry" }
                if (others.isNotEmpty()) {
                    others.forEach { runCatching { Broker.cancel(it.id, it.variety) } }
                    failed = true; continue
                }
                val pending = same.sumOf { (it.pending.takeIf { q -> q > 0 } ?: (it.qty - it.filled)).coerceAtLeast(0) }
                val qty = abs(p.qty) - pending
                if (qty <= 0) { failed = true; continue }                  // still closing; checked again next pass
                // Above the exchange freeze quantity the exit goes as several orders, each one the exchange accepts.
                val orders = Kite.slices(qty, i.lotSize, Kite.freezeQuantity("NFO", p.symbol))
                    .map { Kite.Order(p.symbol, side, it, i.lotSize, p.product, "MARKET", null, i.tickSize, "NFO", "iraalgoexpiry") }
                if (orders.any { Kite.refusals(it, s.limits(), Broker.sentToday(), false, exit = true).isNotEmpty() }) { failed = true; continue }
                val placed = ArrayList<String>()
                for (o in orders) {
                    try {
                        placed += Broker.placeOrder(o, exit = true)
                    } catch (e: Broker.KiteError) {
                        failed = true; break
                    } catch (e: Exception) {
                        // The reply was lost, not necessarily the order: look for it before trying again.
                        runCatching { Broker.findRecent(o, placed) }.getOrNull()?.let { placed += it }
                        failed = true; break
                    }
                }
                if (placed.isNotEmpty()) closed += "live ${p.symbol}"
            }
        }.onFailure { failed = true }

        // Done for the day once nothing failed; a failure is retried on the next watch tick.
        if (!failed) SecurePrefs.put(K_DONE, today.toString())
        if (closed.isNotEmpty()) Notifier.post(context, 2017, Notifier.APPROVAL, "Expiry square-off at 15:05",
            "Closed ${closed.size} position(s) expiring today: ${closed.joinToString()}" + if (failed) ". Some could not be closed yet; retrying." else ".", "trade")
    }
}
