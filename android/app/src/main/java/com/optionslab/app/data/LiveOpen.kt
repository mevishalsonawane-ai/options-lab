package com.optionslab.app.data

import com.optionslab.ira.OpenChange

/**
 * A Zerodha position's open-trade average (16 Oct 2026): on an intraday re-entry Zerodha's own average may count the closed
 * round trip too, so the open trade's average is worked out from the day's fills on that symbol and product
 * ([OpenChange.openAverage]). When that cannot be done for certain (no fills read, a position carried in from an earlier
 * day, fills that do not add up to the position or to Zerodha's day buys and sells), Zerodha's average is kept and said
 * to be Zerodha's. Pure: reads only the lists it is given (the trades the app already read), never the network or disk.
 */
object LiveOpen {
    /** The average to measure the open trade from, and true when it is Zerodha's own (not worked out from the fills). */
    data class Avg(val price: Double, val brokers: Boolean)

    fun of(p: Broker.Position, trades: List<Broker.Trade>?): Avg {
        val fills = trades.orEmpty()
            .filter { it.symbol == p.symbol && it.product == p.product && it.exchange.ifEmpty { p.exchange } == p.exchange }
            .sortedWith(compareBy<Broker.Trade>({ it.at }, { it.id.toLongOrNull() ?: Long.MAX_VALUE }, { it.id }))
            .map { OpenChange.Fill(it.side.equals("BUY", ignoreCase = true), it.qty, it.price) }
        // The fills must be the whole day's on it: they add up to Zerodha's own bought and sold quantities for the day (a
        // trades read a moment behind the positions can still net to the position by chance, and would mislead).
        val whole = fills.filter { it.buy }.sumOf { it.qty } == p.buyQty && fills.filter { !it.buy }.sumOf { it.qty } == p.sellQty
        val own = if (fills.isEmpty() || !whole) null else OpenChange.openAverage(fills, p.qty, p.overnight)
        return if (own != null) Avg(own, false) else Avg(p.avg, true)
    }
}
