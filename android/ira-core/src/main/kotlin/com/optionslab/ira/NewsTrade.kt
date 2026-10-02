package com.optionslab.ira

import java.time.LocalDateTime
import java.util.Locale

/**
 * A trade suggested by the news (the owner's wish, 2026-10-02) - only when strong news and the candles agree, and only
 * for the owner's Approve: a headline that reads strongly good or bad for an index, fresh, while that index's 15-minute
 * trend and its last 15 minutes both already move the news's way, in entry hours, with the trade check not saying stop.
 * The option is chosen as the app's Liquidity 15+5 arm chooses (the app does that): ATM, next expiry, 1 lot, a resting
 * stop 15% below the price paid. UNTESTED as a strategy: each one is recorded so its results can be judged. Pure.
 */
object NewsTrade {
    const val MIN_TONE = 0.6
    const val MIN_MOVE_PCT = 0.15
    const val FRESH_MINUTES = 15L
    const val MAX_A_DAY = 2

    data class Idea(val market: Market, val call: Boolean, val why: String, /** The pattern's two-year hit rate, when a pattern brought it. */ val hitRate: Double? = null)

    /** [snap] and [bars] (1-minute) of the index the headline concerns; [minute]: IST minute now. */
    fun idea(h: Headline, snap: Snapshot?, bars: List<Candle>, now: LocalDateTime, check: TradeCheck.Level?, today: Int,
             expiryToday: Boolean = false, lossLimitHit: Boolean = false): Idea? {
        if (today >= MAX_A_DAY || lossLimitHit) return null
        if (JarvisTrades.expiryBlock(expiryToday, now) != null) return null
        if (kotlin.math.abs(h.tone) < MIN_TONE) return null
        val m = h.markets.firstOrNull { it in listOf(Market.BANKNIFTY, Market.NIFTY, Market.FINNIFTY) } ?: return null
        // Undated news is never fresh enough to trade on.
        val at = h.at ?: return null
        if (at.isBefore(now.atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant().minusSeconds(FRESH_MINUTES * 60))) return null
        val minute = now.hour * 60 + now.minute
        if (minute < 9 * 60 + 20 || minute > 14 * 60 + 30) return null
        if (check == TradeCheck.Level.STOP) return null
        val s = snap ?: return null
        if (s.market != m) return null
        val up = h.tone > 0
        val t15 = s.trend(15)?.up ?: return null
        if (t15 != up) return null
        if (bars.size < 16) return null
        // The last 15 minutes of today's session only (never from last night's close).
        if (bars[bars.size - 16].t.toLocalDate() != now.toLocalDate()) return null
        val last = bars.last().c; val ago = bars[bars.size - 16].c
        val move = (last - ago) / ago * 100
        if (if (up) move < MIN_MOVE_PCT else move > -MIN_MOVE_PCT) return null
        val why = "${h.source}: \"${h.title}\" reads ${if (up) "good" else "bad"} for ${m.label} (tone ${"%+.1f".format(Locale.ENGLISH, h.tone)}), " +
            "and the candles agree: the 15-minute trend is ${if (up) "up" else "down"} and ${m.label} moved ${"%+.2f".format(Locale.ENGLISH, move)}% in the last 15 minutes."
        return Idea(m, up, why)
    }
}
