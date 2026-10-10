package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * Where an order's price sits against the market, said on the order review under each leg (usefulness, round 36). The
 * review already shows the bid / offer / last and the limit box, but not what they mean together: whether the limit
 * fills at once (a buy at or above the offer, a sale at or below the bid), waits inside the spread, or sits away from
 * the market and may not fill; a market order on a wide spread, or with no bid / offer showing; and a limit far from
 * the market, which is often a typo. One short line, or null when there is nothing worth saying.
 *
 * Words only: it never changes the order, its price or whether it can be sent. Pure; no state.
 */
object LimitFit {
    /** A spread at or above this share of the mid price is called wide. */
    const val WIDE = 0.03
    /** A limit this far (as a share) beyond the price it fills against is called far: check it is not a typo. */
    const val FAR = 0.10

    /**
     * The line for one leg: [side] BUY / SELL, [orderType] LIMIT / MARKET (SL / SL-M: null - a stop waits for its
     * trigger by design), its [limit] price, and the quote's [bid], [ask] and [last]. Bid / offer missing or not above
     * zero count as not showing.
     */
    fun note(side: String, orderType: String, limit: Double?, bid: Double?, ask: Double?, last: Double?): String? {
        val buy = side.trim().uppercase() == "BUY"
        val b = bid?.takeIf { it.isFinite() && it > 0 }
        val a = ask?.takeIf { it.isFinite() && it > 0 }
        val l = last?.takeIf { it.isFinite() && it > 0 }
        val wide = spread(b, a)?.takeIf { (_, pct) -> pct >= WIDE }?.let { (w, pct) -> " The spread is wide: ${p2(w)} (${pc(pct)} of the price)." } ?: ""
        when (orderType.trim().uppercase()) {
            "MARKET" -> {
                val against = if (buy) a else b
                if (against == null) return "Market order with no ${if (buy) "offer" else "bid"} showing: the fill price is not known${l?.let { " (last ${p2(it)})" } ?: ""}."
                return if (wide.isEmpty()) null
                else "Market order: it fills at about the ${if (buy) "offer" else "bid"} ${p2(against)}, not the last${l?.let { " ${p2(it)}" } ?: ""}.$wide"
            }
            "LIMIT" -> {
                val px = limit?.takeIf { it.isFinite() && it > 0 } ?: return null
                val against = if (buy) a else b
                val behind = if (buy) b else a
                if (against == null && behind == null) {
                    l ?: return null
                    val off = (px - l) / l
                    return if (abs(off) >= FAR) "No bid or offer showing; your limit is ${pc(abs(off))} ${if (off > 0) "above" else "below"} the last ${p2(l)}: check it is not a typo." else null
                }
                if (against != null && (if (buy) px >= against else px <= against)) {
                    val beyond = abs(px - against) / against
                    val far = if (beyond >= FAR) " Your limit is ${pc(beyond)} ${if (buy) "above the offer" else "below the bid"}: check it is not a typo." else ""
                    return "Fills at once, at about the ${if (buy) "offer" else "bid"} ${p2(against)}.$far$wide"
                }
                if (behind != null && (if (buy) px < behind else px > behind)) {
                    val away = abs(px - behind) / behind
                    val far = if (away >= FAR) " That is far from the market: check it is not a typo." else ""
                    return "Waits ${pc(away)} ${if (buy) "below the bid" else "above the offer"} ${p2(behind)}: it fills only if the price comes to it.$far$wide"
                }
                // Between the bid and the offer (or at the bid / offer on its own side).
                return "Waits inside the spread${against?.let { " (${if (buy) "offer" else "bid"} ${p2(it)})" } ?: ""}: it fills when a ${if (buy) "seller" else "buyer"} meets it.$wide"
            }
            else -> return null
        }
    }

    /** The spread's width and its share of the mid price, or null without both sides (or crossed). */
    fun spread(bid: Double?, ask: Double?): Pair<Double, Double>? {
        if (bid == null || ask == null || !(bid > 0) || !(ask >= bid)) return null
        val mid = (bid + ask) / 2
        return (ask - bid) to (ask - bid) / mid
    }

    private fun p2(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)
    private fun pc(x: Double) = String.format(Locale.ENGLISH, if (x < 0.1) "%.1f%%" else "%.0f%%", x * 100)
}
