package com.optionslab.engine

import java.time.LocalDate

/**
 * Turn a live chain into an order you could place, and keep a paper record.
 * Port of `live.py`.
 *
 * A TICKET IS NOT A TRADE. This computes and records an intention. It sends
 * nothing, holds no credentials, and contains no code path that could place an
 * order. Every ledger row carries `paper = true`.
 *
 * Settled rows go through [ExpiryPut.settleTrade] - the same function the
 * backtest uses - so the health checks apply to paper results unchanged.
 */
object Live {
    class TooEarly(msg: String) : Exception(msg)
    class AlreadyRecorded(msg: String) : Exception(msg)
    class NotRecorded(msg: String) : Exception(msg)

    data class Ticket(
        val session: LocalDate,
        val underlying: String,
        val expiry: LocalDate,
        val side: String,
        val right: String,
        val strike: Double,
        val lotSize: Int,
        val lots: Int,
        val qty: Int,
        val credit: Double,
        val forward: Double,
        val breakeven: Double,
        val margin: Double,
        val maxLoss: Double?,
        val wingStrike: Double?,
        val wingDebit: Double?,
    ) {
        fun format(): String {
            val head = buildString {
                append("expiry $expiry  forward %,.1f\n".format(forward))
                append("  $side  $underlying ${fmtG(strike)} $right  x$lots lot ($lotSize)\n")
                append("  credit    Rs %.2f/unit  =  Rs %,.0f\n".format(credit, credit * qty))
                append("  margin    Rs %,.0f  (estimate, broker SPAN varies)\n".format(margin))
                append("  breakeven %,.1f   (settle above this to keep the credit)\n".format(breakeven))
            }
            // Naming it is the point. A number here would be false, and an
            // empty field would be read as zero.
            if (wingStrike == null) return head + "  max loss  UNBOUNDED  - naked short put\n"
            return head +
                "  BUY       $underlying ${fmtG(wingStrike)} $right  x$lots lot   debit Rs %.2f/unit\n".format(wingDebit) +
                "  max loss  Rs %,.0f  (bounded)\n".format(maxLoss)
        }
    }

    /**
     * The session must actually have REACHED the entry time. Live, taking the
     * latest bar at or before 11:00 would quietly price a 10:00 quote and label
     * it an 11:00 ticket.
     */
    fun liveSnapshot(chain: List<Series>, entryMinute: Int): List<ExpiryPut.Quote> {
        val opts = chain.filter { it.right != Right.IX && it.size > 0 }
        val latest = opts.maxOfOrNull { it.minutes.last() }
            ?: throw TooEarly("no bars at or before ${minuteText(entryMinute)}; the signal does not exist yet")
        if (latest < entryMinute) throw TooEarly(
            "latest bar is ${minuteText(latest)}, entry is ${minuteText(entryMinute)}; the signal does not exist yet - " +
                "a ticket now would be priced off stale quotes and dated as though it were not")
        val snap = ExpiryPut.snapshot(opts, entryMinute)
        if (snap.isEmpty()) throw TooEarly("no bars at or before ${minuteText(entryMinute)}; the signal does not exist yet")
        return snap
    }

    private fun leg(snap: List<ExpiryPut.Quote>, strike: Double): Double {
        val q = snap.firstOrNull { it.strike == strike && it.right == Right.PE }
        if (q == null || q.close <= 0) throw ExpiryPut.SessionSkipped("no PE quote at strike ${fmtG(strike)}")
        return q.close
    }

    fun buildTicket(
        chain: List<Series>, session: LocalDate, underlying: String, lotSize: Int,
        expiry: LocalDate? = null, otmPct: Double = ExpiryPut.DEFAULT_OTM_PCT,
        entryMinute: Int = ExpiryPut.DEFAULT_ENTRY, lots: Int = 1, wingPct: Double? = null,
    ): Ticket {
        val snap = liveSnapshot(chain, entryMinute)
        val forward = ExpiryPut.parityForward(snap)
        val strikes = snap.map { it.strike }.distinct()
        val strike = ExpiryPut.selectStrike(strikes, forward, otmPct)
        val credit = leg(snap, strike)
        var wingStrike: Double? = null
        var wingDebit: Double? = null
        var maxLoss: Double? = null
        if (wingPct != null) {
            val ws = ExpiryPut.selectStrike(strikes, forward, otmPct + wingPct)
            if (ws >= strike) throw ExpiryPut.SessionSkipped("wing at ${fmtG(ws)} is not below ${fmtG(strike)}")
            wingStrike = ws
            wingDebit = leg(snap, ws)
        }
        val qty = lotSize * lots
        val netCredit = credit - (wingDebit ?: 0.0)
        val margin: Double
        if (wingStrike != null) {
            maxLoss = (strike - wingStrike - netCredit) * qty
            margin = maxLoss   // a defined-risk spread blocks roughly its own max loss
        } else {
            // Measured exchange margin was Rs 107,264 for one lot of 65; scaled by quantity.
            margin = Sizing.EXCHANGE_MARGIN_RS * (qty.toDouble() / Sizing.LOT_SIZE)
        }
        return Ticket(
            session = session, underlying = underlying, expiry = expiry ?: session, side = "SELL", right = "PE",
            strike = strike, lotSize = lotSize, lots = lots, qty = qty, credit = netCredit, forward = forward,
            breakeven = strike - netCredit, margin = margin, maxLoss = maxLoss,
            wingStrike = wingStrike, wingDebit = wingDebit,
        )
    }

    /** A ledger row - open until settled. Mirrors the JSON the PC writes. */
    data class LedgerRow(
        val ticket: Ticket,
        val status: String,                  // open | settled
        val settlement: Double? = null,
        val trade: ExpiryPut.Trade? = null,
        val recordedAtMillis: Long = 0L,
    ) {
        val paper: Boolean get() = true

        fun toMonitorRow(): Monitor.Row? {
            val t = trade ?: return null
            return Monitor.Row(ticket.session, t.strike, t.credit, t.settlement, ticket.forward, t.lotSize,
                t.intrinsic, (ticket.forward - t.strike) / ticket.forward)
        }
    }

    fun settle(row: LedgerRow, settlement: Double, regime: String = "quoted"): LedgerRow {
        val tk = row.ticket
        // The ticket stores its credit NET of the wing debit, and settleTrade
        // nets the wing itself. Passing the net figure would subtract the wing
        // twice and cost the short leg on the wrong premium, so the short
        // leg's own credit is rebuilt first.
        val shortCredit = tk.credit + (tk.wingDebit ?: 0.0)
        val trade = ExpiryPut.settleTrade(
            strike = tk.strike, credit = shortCredit, settlement = settlement,
            lotSize = tk.lotSize, lots = tk.lots, regime = regime,
            wingStrike = tk.wingStrike, wingDebit = tk.wingDebit ?: 0.0,
        ).copy(session = tk.session, forward = tk.forward, otmRealised = (tk.forward - tk.strike) / tk.forward)
        return row.copy(status = "settled", settlement = settlement, trade = trade)
    }

    /**
     * Mark-to-market of an open paper ticket against the live chain: what it
     * would cost to buy the position back now, before charges.
     */
    fun markToMarket(tk: Ticket, chain: List<Series>, minute: Int): Double? {
        val snap = ExpiryPut.snapshot(chain, minute)
        val shortNow = snap.firstOrNull { it.strike == tk.strike && it.right == Right.PE }?.close ?: return null
        val wingNow = tk.wingStrike?.let { ws -> snap.firstOrNull { it.strike == ws && it.right == Right.PE }?.close ?: return null } ?: 0.0
        return (tk.credit - (shortNow - wingNow)) * tk.qty
    }
}
