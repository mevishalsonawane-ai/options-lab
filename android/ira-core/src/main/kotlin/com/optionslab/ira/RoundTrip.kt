package com.optionslab.ira

import java.util.Locale

/**
 * The order review's round-trip line (usefulness, round 37). The review shows Zerodha's one-way "Charges, estimated"
 * from the basket-margin reply; beside it, what getting in AND out at the same price would cost - the order itself and
 * the opposite order of the same size at the same price, each on the leg's own schedule ([PnlCharges.segment],
 * [PnlCharges.perFill]) - and how far the price must move the leg's way to pay for it:
 *
 *   "Round trip ≈ ₹64 (to buy and sell back at this price); needs +0.85 points on the premium to cover it"
 *
 * Held shares (CNC) are sold back on a later day (the DP charge on the sale, delivery STT both ways); a same-day share
 * trade (MIS) on the same day. A commodity or currency leg ([PnlCharges.Segment.OTHER], approximate rates), a leg with
 * no price or no quantity: no line. Words only: the order, its price and whether it can be sent are unchanged. Pure.
 */
object RoundTrip {
    /** The round trip's charges in rupees and the points per unit that pay for them. */
    data class Cost(val charges: Double, val points: Double, val segment: PnlCharges.Segment)

    /** The round trip of [side] [qty] of [symbol] on [exchange] under [product] at [price]; null when there is none to say. */
    fun cost(side: String, price: Double?, qty: Int, symbol: String, exchange: String, product: String): Cost? {
        if (price == null || !price.isFinite() || price <= 0 || qty <= 0) return null
        val seg = PnlCharges.segment(symbol, exchange, product)
        if (seg == PnlCharges.Segment.OTHER) return null
        val buy = side.trim().uppercase() == "BUY"
        // Held shares are sold another day (a same-day CNC square-off would be charged as a same-day trade).
        val backDay = if (seg == PnlCharges.Segment.DELIVERY) "2" else "1"
        val fills = listOf(
            PnlCharges.Fill(if (buy) "BUY" else "SELL", price, qty, "in", symbol, exchange, product, "1"),
            PnlCharges.Fill(if (buy) "SELL" else "BUY", price, qty, "out", symbol, exchange, product, backDay),
        )
        val charges = PnlCharges.perFill(fills).sumOf { it.values.sum() }
        if (!(charges > 0)) return null
        return Cost(charges, charges / qty, seg)
    }

    /** The line under the leg, or null ([cost]). */
    fun line(side: String, price: Double?, qty: Int, symbol: String, exchange: String, product: String): String? {
        val c = cost(side, price, qty, symbol, exchange, product) ?: return null
        val buy = side.trim().uppercase() == "BUY"
        val what = if (c.segment == PnlCharges.Segment.OPTIONS) "premium" else "price"
        val pts = String.format(Locale.ENGLISH, "%.2f", c.points)
        val trip = if (buy) "to buy and sell back at this price" else "to sell and buy back at this price"
        val needs = if (buy) "needs +$pts points on the $what to cover it" else "needs the $what to fall $pts points to cover it"
        return "Round trip ≈ ${PnlCharges.inr(c.charges)} ($trip); $needs"
    }
}
