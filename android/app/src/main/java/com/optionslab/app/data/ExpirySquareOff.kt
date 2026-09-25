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
        if (!s.expirySquareOff || s.guardKill) return
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

        // Live: only in Live mode with a session; shorts are bought back before longs are sold.
        if (s.live && s.allowRealOrders && Broker.loggedIn) runCatching {
            val ins = Broker.cachedInstruments().orEmpty().associateBy { it.tradingSymbol }
            val open = Broker.positionBook().net.filter { it.qty != 0 && it.exchange == "NFO" }
                .filter { p -> ins[p.symbol]?.let { it.expiry == today && !isTicketLeg(it.name, it.strike, it.right.name) } == true }
                .sortedBy { if (it.qty < 0) 0 else 1 }
            for (p in open) {
                val i = ins.getValue(p.symbol)
                val side = if (p.qty < 0) Kite.Side.BUY else Kite.Side.SELL
                val o = Kite.Order(p.symbol, side, abs(p.qty), i.lotSize, p.product, "MARKET", null, i.tickSize, "NFO", "iraalgoexpiry")
                val why = Kite.refusals(o, s.limits(), Broker.sentToday(), false, exit = true)
                if (why.isNotEmpty()) { failed = true; continue }
                runCatching { Broker.placeOrder(o) }.onSuccess { closed += "live ${p.symbol}" }.onFailure { failed = true }
            }
        }.onFailure { failed = true }

        // Done for the day once nothing failed; a failure is retried on the next watch tick.
        if (!failed) SecurePrefs.put(K_DONE, today.toString())
        if (closed.isNotEmpty()) Notifier.post(context, 7900, Notifier.APPROVAL, "Expiry square-off at 15:05",
            "Closed ${closed.size} position(s) expiring today: ${closed.joinToString()}" + if (failed) ". Some could not be closed yet; retrying." else ".", "trade")
    }
}
