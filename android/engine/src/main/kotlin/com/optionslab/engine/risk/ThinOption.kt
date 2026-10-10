package com.optionslab.engine.risk

import java.util.Locale

/**
 * Does an option trade enough to be bought by a bot? (08 Oct 2026, research/PROFIT_LOCK_8OCT.md fix 2.) FINNIFTY's monthly
 * option traded 0-12 lots a minute that day, with whole minutes of no trade: a paper fill there is a price nobody traded,
 * and a stop on it is gapped (the 09:30 Pine trade's breakeven lock at 338.81 was next traded at 326.70).
 *
 * An automatic buy is skipped when, in the last [WINDOW_MINUTES] minutes (the minute forming now and the ones before it),
 * fewer than [MIN_TRADED_MINUTES] minutes traded at all, or fewer than [MIN_LOTS] lots traded in all; or, when a quote has
 * a best bid and ask, when half the gap between them is more than [MAX_HALF_SPREAD_PCT] percent of the middle. Unknown
 * minutes (the feed could not be read) leave only the spread to judge; with neither, the buy is skipped too. Pure.
 */
object ThinOption {
    /** How many minutes are looked at: the forming one and the five before it. */
    const val WINDOW_MINUTES = 6
    /** At least this many of them traded. */
    const val MIN_TRADED_MINUTES = 4
    /** At least this many lots traded in all of them. */
    const val MIN_LOTS = 25
    /** Half the bid-ask gap, as a percent of the middle, at most this (when a quote has both). */
    const val MAX_HALF_SPREAD_PCT = 0.3

    /**
     * Why the option is not bought, in plain words, or null when it trades enough. [minutes]: today's 1-minute candles as
     * (start, epoch seconds; volume, units), null when they could not be read; [nowSec]: epoch seconds now; [lotSize]: units
     * a lot; [bid] / [ask]: the best prices when a quote has them (null or 0: none).
     */
    fun refusal(minutes: List<Pair<Long, Long>>?, nowSec: Long, lotSize: Int, bid: Double?, ask: Double?): String? {
        spreadRefusal(bid, ask)?.let { return it }
        if (minutes == null) {
            return if (hasQuote(bid, ask)) null else "how much it trades could not be read (no recent candles and no bid or ask)"
        }
        val (traded, units) = recent(minutes, nowSec)
        val lots = if (lotSize > 0) units / lotSize else units
        if (traded < MIN_TRADED_MINUTES || lots < MIN_LOTS)
            return "it barely trades: $traded of the last $WINDOW_MINUTES minutes traded, $lots lot${if (lots == 1L) "" else "s"} in all " +
                "(needs $MIN_TRADED_MINUTES minutes and $MIN_LOTS lots)"
        return null
    }

    /** (minutes that traded, units traded) over the last [WINDOW_MINUTES] minutes up to [nowSec]. */
    fun recent(minutes: List<Pair<Long, Long>>, nowSec: Long): Pair<Int, Long> {
        val thisMinute = nowSec - Math.floorMod(nowSec, 60L)
        val from = thisMinute - (WINDOW_MINUTES - 1) * 60L
        val inside = minutes.filter { (start, _) -> start in from..thisMinute }
        return inside.count { it.second > 0 } to inside.sumOf { it.second.coerceAtLeast(0) }
    }

    /** Half the bid-ask gap as a percent of the middle, or null when the quote has no usable bid and ask. */
    fun halfSpreadPct(bid: Double?, ask: Double?): Double? {
        if (!hasQuote(bid, ask)) return null
        val b = bid!!; val a = ask!!
        return (a - b) / 2 / ((a + b) / 2) * 100
    }

    private fun hasQuote(bid: Double?, ask: Double?): Boolean =
        bid != null && ask != null && bid.isFinite() && ask.isFinite() && bid > 0 && ask >= bid

    private fun spreadRefusal(bid: Double?, ask: Double?): String? {
        val half = halfSpreadPct(bid, ask) ?: return null
        if (half <= MAX_HALF_SPREAD_PCT + 1e-9) return null
        return "the gap between buyers (%.2f) and sellers (%.2f) is %.2f%% either side of the middle (more than %.1f%%)"
            .format(Locale.ENGLISH, bid, ask, half, MAX_HALF_SPREAD_PCT)
    }
}

/**
 * A paper entry never fills on a stale price (08 Oct 2026, research/PROFIT_LOCK_8OCT.md fix 4). Without Zerodha's stream the
 * paper price is the last 1-minute candle's close; when the contract has not traded for more than [MAX_AGE_SECONDS] (no
 * candle with a trade ended in that time), that close is not a price anyone could buy at (Pine #17 bought at 13:31 at the
 * 13:30 minute's last trade, 368.90; the 13:31 minute had no trade at all). Exits are not judged here: getting out always
 * fills. Pure.
 */
object StaleEntry {
    /** A trade at most this long ago (the end of the last minute with one) keeps the price fresh enough to fill an entry. */
    const val MAX_AGE_SECONDS = 120L

    /**
     * When the contract last traded, as the end of its last 1-minute candle with a trade (epoch seconds), from today's
     * [minutes] as (start, volume); a feed that gives no volume at all counts its last candle. Null: no candle.
     */
    fun lastTradeEnd(minutes: List<Pair<Long, Long>>): Long? {
        if (minutes.isEmpty()) return null
        val traded = minutes.filter { it.second > 0 }
        val last = (traded.ifEmpty { minutes }).maxOf { it.first }
        return last + 60
    }

    /** Why an entry in [symbol] must not fill on the candle price at [nowSec], or null when it may. */
    fun refusal(minutes: List<Pair<Long, Long>>, nowSec: Long, symbol: String): String? {
        val end = lastTradeEnd(minutes) ?: return "no trade in $symbol today, so there is no price to fill an entry at"
        val age = nowSec - end
        if (age <= MAX_AGE_SECONDS) return null
        val mins = age / 60
        return "$symbol has not traded for $mins minutes and the price stream is off, " +
            "so its last candle's close is stale: no entry was filled on it"
    }
}
