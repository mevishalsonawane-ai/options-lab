package com.optionslab.engine.risk

import java.time.LocalDate
import kotlin.math.abs

/**
 * The account-wide guard: limits every order must pass, whoever placed it
 * (a strategy or the owner) and wherever it goes (paper or Zerodha).
 *
 * An EXIT (an order that only reduces what is held) is refused by the kill
 * switch alone: once a position exists, getting out must never be blocked by
 * a loss, count or time limit. Everything else applies to entries.
 */
object AccountGuard {

    data class Limits(
        val killSwitch: Boolean = false,
        /** Rupees; 0 = off. Refuse new entries once today's P&L is at or below -this. */
        val maxDailyLoss: Double = 2_000.0,
        /** Percent; 0 = off. Measured against the capital and against the highest equity seen. */
        val maxDrawdownPct: Double = 10.0,
        /** 0 = off. Distinct instruments held; adding to one already held does not count again. */
        val maxOpenPositions: Int = 3,
        /** 0 = off. Orders sent today (entries and exits). */
        val maxTradesPerDay: Int = 10,
        /** Rupees; 0 = off. Quantity x price of one order. */
        val maxOrderValue: Double = 500_000.0,
        /** Lots; 0 = off. What one instrument may be held at, after this order. */
        val maxLotsPerSymbol: Int = 2,
        /** Minute of the day (IST); no new entries at or after it. Null = off. 14:30 = 870. */
        val entryCutoffMinute: Int? = 14 * 60 + 30,
        /** Selling an option to open is refused unless a bought option of the same underlying, expiry and type is held. */
        val blockNakedShort: Boolean = true,
    )

    /** One held instrument; [qty] is signed (short negative), in units. */
    data class Holding(val symbol: String, val qty: Int, val lot: Int, val underlying: String? = null,
                       val expiry: LocalDate? = null, val right: String? = null)

    /** The account at the moment of the order. */
    data class Account(
        val capital: Double,
        val equity: Double,
        val peakEquity: Double,
        val dayPnl: Double,
        val holdings: List<Holding>,
        val ordersToday: Int,
        val minuteOfDay: Int,
    )

    /** The order being judged. [right] is "CE"/"PE" for an option, null otherwise. */
    data class Order(val symbol: String, val side: String, val qty: Int, val lot: Int, val price: Double,
                     val underlying: String? = null, val expiry: LocalDate? = null, val right: String? = null)

    /** True when the order only reduces an existing position of the same instrument. */
    fun isExit(o: Order, a: Account): Boolean {
        val held = a.holdings.filter { it.symbol == o.symbol }.sumOf { it.qty }
        val signed = if (o.side.equals("BUY", true)) o.qty else -o.qty
        return held != 0 && (held > 0) != (signed > 0) && abs(signed) <= abs(held)
    }

    /** Every reason the order is refused; empty when it may go. */
    fun refusals(o: Order, a: Account, l: Limits): List<String> {
        if (l.killSwitch) return listOf("The kill switch is on: no orders at all until it is cleared.")
        if (isExit(o, a)) return emptyList()
        val out = ArrayList<String>()
        if (l.maxDailyLoss > 0 && a.dayPnl <= -l.maxDailyLoss)
            out += "Daily loss limit reached: today's P&L is Rs %,.0f (limit -Rs %,.0f). Only exits are allowed today.".format(a.dayPnl, l.maxDailyLoss)
        if (l.maxDrawdownPct > 0) {
            if (a.capital > 0 && (a.capital - a.equity) / a.capital * 100 >= l.maxDrawdownPct)
                out += "Drawdown limit: equity Rs %,.0f is %.1f%% below capital Rs %,.0f (limit %.1f%%).".format(a.equity, (a.capital - a.equity) / a.capital * 100, a.capital, l.maxDrawdownPct)
            if (a.peakEquity > 0 && (a.peakEquity - a.equity) / a.peakEquity * 100 >= l.maxDrawdownPct)
                out += "Drawdown limit: equity Rs %,.0f is %.1f%% below its peak Rs %,.0f (limit %.1f%%).".format(a.equity, (a.peakEquity - a.equity) / a.peakEquity * 100, a.peakEquity, l.maxDrawdownPct)
        }
        val heldSymbols = a.holdings.filter { it.qty != 0 }.map { it.symbol }.toSet()
        if (l.maxOpenPositions > 0 && o.symbol !in heldSymbols && heldSymbols.size >= l.maxOpenPositions)
            out += "Open positions limit: ${heldSymbols.size} already held (limit ${l.maxOpenPositions})."
        if (l.maxTradesPerDay > 0 && a.ordersToday >= l.maxTradesPerDay)
            out += "Trades per day limit: ${a.ordersToday} sent today (limit ${l.maxTradesPerDay})."
        if (l.maxOrderValue > 0 && o.qty * o.price > l.maxOrderValue)
            out += "Order value Rs %,.0f is over the Rs %,.0f limit.".format(o.qty * o.price, l.maxOrderValue)
        if (l.maxLotsPerSymbol > 0 && o.lot > 0) {
            val held = a.holdings.filter { it.symbol == o.symbol }.sumOf { it.qty }
            val after = held + if (o.side.equals("BUY", true)) o.qty else -o.qty
            val lots = abs(after).toDouble() / o.lot
            if (lots > l.maxLotsPerSymbol) out += "Exposure limit: ${o.symbol} would be %.0f lots (limit %d).".format(lots, l.maxLotsPerSymbol)
        }
        l.entryCutoffMinute?.let { cut ->
            if (a.minuteOfDay >= cut) out += "No new entries after %02d:%02d.".format(cut / 60, cut % 60)
        }
        if (l.blockNakedShort && o.right != null && o.side.equals("SELL", true)) {
            val hedged = a.holdings.any { h -> h.qty > 0 && h.right == o.right && h.underlying == o.underlying && h.expiry == o.expiry }
            if (!hedged) out += "Naked short: selling ${o.symbol} needs a bought ${o.right} of the same underlying and expiry held first."
        }
        return out
    }

    /** The new peak after an equity reading: the peak only ever rises. */
    fun nextPeak(peak: Double, equity: Double): Double = maxOf(peak, equity)
}
