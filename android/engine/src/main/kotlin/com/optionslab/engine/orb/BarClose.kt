package com.optionslab.engine.orb

import com.optionslab.engine.Upstox

/**
 * Entries within seconds of the bar close (08 Oct, research X1 change 2, Boss's yes). The arms decided only on the watch's
 * full pass, once a minute, and the feed's 5-minute bar waited up to two more minutes for its last minute: Liquidity's buys
 * came 1-3 minutes after the bar closed, which costs about Rs 45-80 a BANKNIFTY / FINNIFTY trade and Rs 130-145 a
 * MIDCPNIFTY trade on history. Now the watch wakes a few seconds after every 5-minute boundary ([nextWake]; 15- and
 * 30-minute bars close on those too) and the arms decide then; the minutes the candle feed does not have yet are built
 * from Zerodha's stream when it streams the index ([complete]). The decision itself is unchanged and taken once per bar
 * (the arms' own "decided" record), with every guard as before. Pure.
 */
object BarClose {
    /** Every bar the arms read closes on a multiple of this (5-, 15- and 30-minute bars). */
    const val BAR_MS = 5 * 60_000L
    /** How long after the boundary the check runs: the last ticks of the minute are in. */
    const val AFTER_MS = 3_000L
    /** At most this many trailing minutes are built from the stream (its kept ticks cover about five). */
    const val MAX_BUILT = 4

    /**
     * When the next bar-close check runs after [nowMs] (epoch ms): [AFTER_MS] past the next 5-minute boundary, or past
     * the one just gone when that moment is still ahead. IST is UTC+5:30, so epoch boundaries are IST's.
     */
    fun nextWake(nowMs: Long): Long {
        val base = nowMs - Math.floorMod(nowMs, BAR_MS)
        val here = base + AFTER_MS
        return if (here > nowMs) here else base + BAR_MS + AFTER_MS
    }

    /**
     * [feed] (one day's 1-minute candles, epoch seconds) with the minutes it does not have yet built from the stream:
     * every whole minute after its last candle that has ended by [nowSec], at most [MAX_BUILT], that [built] knows
     * (minute start in epoch seconds -> open, high, low, close; null: the stream has none for it). A minute the stream
     * cannot build stops the filling there (a gap is never skipped over). An empty feed is returned as it is.
     */
    fun complete(feed: List<Upstox.Bar>, nowSec: Long, built: (Long) -> DoubleArray?): List<Upstox.Bar> {
        val last = feed.maxByOrNull { it.epochSecond } ?: return feed
        val out = feed.toMutableList()
        var m = last.epochSecond + 60
        var n = 0
        while (n < MAX_BUILT && m + 60 <= nowSec) {
            val b = built(m)?.takeIf { it.size >= 4 && it.all { x -> x.isFinite() && x > 0 } } ?: break
            out += Upstox.Bar(m, b[0], b[1], b[2], b[3], 0, 0)
            m += 60; n++
        }
        return out
    }
}
