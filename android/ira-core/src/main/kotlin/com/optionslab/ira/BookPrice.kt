package com.optionslab.ira

import java.util.Locale

/**
 * 28 Oct (Boss: "Yes fix finnifty"): a price for an option that has a live bid and ask but NO last price.
 *
 * Why: R72 and R51's FINNIFTY entries (FINNIFTY23NOV2624250PE / CE) were "not bought: no fresh price" all morning. Zerodha
 * reports a last price of 0 for an option that has not traded, and the paper price layer threw away every quote with no last
 * price, then fell back to the day's 1-minute candles, which an untraded option does not have. The book itself (a bid and an
 * ask) was there all along. A buyer pays the ask, so a paper entry may fill there, as the record-only path already priced its
 * would-be trades.
 *
 * Only when the book is sane: both sides there, not crossed, and the ask within research h14's capped limit of the mid
 * ([BossRisk.cap]: +0.5%, the same cap the app's capped entries use) or a single tick wide (the narrowest a book can be; a cheap
 * option's one-tick spread is wider than 0.5% of its price). A one-sided, empty, crossed or wide book is refused with a plain
 * reason. The 0.3% spread check ([BossRisk.spread]) stays shadow only, logged as before. Pure: no clock, disk or network.
 */
object BookPrice {
    /** [ask]: what a buyer pays (null when refused); [bid]: what a held long is worth now (null: none); [why]: the refusal. */
    data class Verdict(val ask: Double?, val bid: Double?, val why: String?) {
        val ok: Boolean get() = why == null
    }

    /** One tick of an NSE / BSE index option. */
    const val TICK = BossRisk.TICK

    /** The book [bid] / [ask] judged for an option with no last price. */
    fun judge(bid: Double?, ask: Double?): Verdict {
        val b = bid?.takeIf { it.isFinite() && it > 0 }
        val a = ask?.takeIf { it.isFinite() && it > 0 }
        if (b == null && a == null) return Verdict(null, null, "no last price and an empty book (no bid, no ask)")
        if (a == null) return Verdict(null, b, "no last price and a one-sided book (bid ${num(b)}, no ask)")
        if (b == null) return Verdict(null, null, "no last price and a one-sided book (ask ${num(a)}, no bid)")
        if (a < b) return Verdict(null, b, "no last price and a crossed book (bid ${num(b)}, ask ${num(a)})")
        val mid = (a + b) / 2
        val oneTick = a - b <= TICK + 1e-9
        val cap = BossRisk.cap(mid)
        if (!oneTick && (cap == null || !BossRisk.underCap(a, cap))) {
            val hs = (a - b) / 2 / mid * 100
            return Verdict(null, b, "no last price and the spread is too wide (bid ${num(b)}, ask ${num(a)}: half-spread " +
                "%.2f%%, over h14's 0.5%% cap)".format(Locale.ENGLISH, hs))
        }
        return Verdict(a, b, null)
    }

    /** Diagnostics line for a paper fill priced from the book alone. */
    fun filledLine(symbol: String, bid: Double, ask: Double, fill: Double): String =
        "$symbol had no last price; paper priced it from the live book (bid ${num(bid)}, ask ${num(ask)}) and the buy filled at ${num(fill)}"

    private fun num(v: Double?): String = v?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none"
}
