package com.optionslab.ira

import java.util.Locale

/**
 * "Jarvis, should I trade now?" - the owner's wish (2026-10-02): Jarvis says whether to trade, and why. It never
 * guesses a direction; it weighs what is known: whether trading is possible at all (market, data, Zerodha, the app's
 * own stops), today's risks (events, India VIX, gaps, sudden moves), the owner's own day so far, and each arm's
 * tested record (research/SIDEWAYS_GUARD.md: two BANKNIFTY years, real option prices). The worst reason decides:
 * STOP, then CAREFUL, else GO. Pure.
 */
object TradeCheck {
    enum class Level { GO, CAREFUL, STOP }
    data class Reason(val level: Level, val text: String)
    data class Verdict(val level: Level, val reasons: List<Reason>, val reads: List<String> = emptyList()) {
        /** In words, the deciding reasons first. */
        fun say(): String {
            val head = when (level) {
                Level.STOP -> "Don't trade now."
                Level.CAREFUL -> "Careful today."
                Level.GO -> "Conditions are normal: fine to trade."
            }
            val shown = reasons.sortedByDescending { it.level }.filter { it.level != Level.GO || level == Level.GO }.take(5)
            val arms = reasons.filter { it.text.startsWith("Tested:") }
            return (reads + listOf(head) + shown.map { it.text } + arms.filter { it !in shown }.map { it.text } +
                (if (reads.isNotEmpty()) listOf("That is how the market is moving now, not a forecast.") else emptyList())).joinToString(" ")
        }
    }

    /** Everything the app knows right now (null where it does not know). */
    data class Now(
        val marketOpen: Boolean, val tradingDay: Boolean, val minute: Int,          // IST minute of the day
        val liveMode: Boolean, val zerodhaLoggedIn: Boolean, val staticIpOk: Boolean?,
        val pricesFresh: Boolean,
        val killSwitch: Boolean, val breakerTripped: Boolean, val botsStopped: Boolean,
        val dayPnl: Double?, val dayLossLimit: Double,
        val vix: Double?, val vixChangePct: Double?,
        val indexChangePct: Double?, val gapPct: Double?, val burst30Pct: Double?, val usual30Pct: Double?,
        val openingRangeRatio: Double?,   // today's 09:15-10:00 range / its 20-day average (after 10:00)
        val eventsToday: List<String>, val expiryToday: Boolean,
        val armsOn: List<String>,
        /** How the indices are moving now ([read]), first in the answer. */
        val reads: List<String> = emptyList(),
    )

    /**
     * How [s] is moving NOW - bullish, bearish or no clear trend - from the trend on the 15-minute and 1-hour charts and
     * the day's change, and whether the day is busy or quiet. A description, never a forecast.
     */
    /** Today's 09:15-10:00 range against the average of the previous [days] sessions' (after 10:00), or null. */
    fun openingRangeRatio(bars: List<Candle>, days: Int = 20): Double? {
        val byDay = bars.groupBy { it.t.toLocalDate() }
        fun or(d: List<Candle>): Double? = d.filter { it.t.hour == 9 && it.t.minute >= 15 }.takeIf { it.size >= 40 }?.let { w -> w.maxOf { it.h } - w.minOf { it.l } }
        val keys = byDay.keys.sorted()
        val today = keys.lastOrNull() ?: return null
        val now = or(byDay.getValue(today)) ?: return null
        val past = keys.dropLast(1).takeLast(days).mapNotNull { or(byDay.getValue(it)) }
        if (past.size < 10) return null
        return now / past.average()
    }

    fun read(s: Snapshot): String {
        val t15 = s.trend(15)?.up; val t60 = s.trend(60)?.up; val ch = s.changePct
        val word = when {
            t15 == true && t60 == true && (ch ?: 0.0) >= 0 -> "bullish"
            t15 == false && t60 == false && (ch ?: 0.0) <= 0 -> "bearish"
            t15 != null && t60 != null && t15 != t60 -> "mixed (the 15-minute and 1-hour trends disagree)"
            else -> "no clear trend"
        }
        val day = ch?.let { " (${pct(it)} on the day)" } ?: ""
        val mood = s.mood?.let { ", a ${it.word} day" } ?: ""
        return "${s.market.label} is $word now$day$mood."
    }

    /** Each arm's tested record (BANKNIFTY, Rs per lot after costs, year A then year B). */
    val RECORD = linkedMapOf(
        "Liquidity 15+5" to (103_019.0 to 30_250.0),
        "ORB" to (-77_013.0 to -69_754.0),
        "ORB Fresh" to (-16_838.0 to -6_361.0),
        "ORB Sweep" to (-24_046.0 to -76_025.0),
        "Range Fade" to (-47_158.0 to -72_340.0),
    )

    private fun pct(x: Double) = "%+.1f%%".format(Locale.ENGLISH, x)
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    fun check(n: Now): Verdict {
        val r = ArrayList<Reason>()
        fun stop(t: String) { r += Reason(Level.STOP, t) }
        fun careful(t: String) { r += Reason(Level.CAREFUL, t) }
        fun ok(t: String) { r += Reason(Level.GO, t) }

        // Can trading happen at all?
        if (!n.tradingDay) stop("The market is closed today.")
        else if (!n.marketOpen) stop(if (n.minute < 9 * 60 + 15) "The market opens at 09:15." else "The market is closed for the day.")
        else {
            if (n.minute < 9 * 60 + 20) careful("The first five minutes swing hardest: wait until 09:20.")
            if (n.minute >= 14 * 60 + 55) stop("It is past 14:55: the app takes no new positions this late.")
            else if (n.minute >= 14 * 60 + 30) careful("Under 45 minutes left: little time for a trade to work.")
        }
        if (!n.pricesFresh) stop("Live prices are not coming in: never trade blind.")
        if (n.liveMode && !n.zerodhaLoggedIn) stop("Zerodha is not logged in today.")
        if (n.liveMode && n.staticIpOk == false) stop("You are not on your registered static IP: Zerodha will refuse new positions.")
        if (n.killSwitch) stop("The kill switch is on.")
        if (n.breakerTripped) stop("The daily loss limit was hit today: the day is over.")
        if (n.botsStopped) careful("The bots are stopped for today.")
        // Your own day.
        n.dayPnl?.let { p ->
            if (n.dayLossLimit > 0 && p <= -0.75 * n.dayLossLimit) stop("You are down ${rs(p)} today, near the ${rs(n.dayLossLimit).removePrefix("+")} daily limit: stop for today.")
            else if (n.dayLossLimit > 0 && p <= -0.5 * n.dayLossLimit) careful("You are down ${rs(p)} today, half the daily limit: trade smaller or stop.")
        }
        // Today's risks.
        n.eventsToday.forEach { careful("Event today: $it.") }
        if (n.expiryToday) careful("Expiry today: option prices decay and jump fast.")
        n.vix?.let { v -> if (v >= 22) careful("India VIX is high (${"%.1f".format(Locale.ENGLISH, v)}): options are expensive and moves are wild.") }
        n.vixChangePct?.let { c -> if (c >= 8) careful("India VIX is ${pct(c)} today: fear is rising.") }
        n.gapPct?.let { g -> if (kotlin.math.abs(g) >= 1.0 && n.minute < 9 * 60 + 45) careful("The market opened with a ${pct(g)} gap: let the first 30 minutes settle.") }
        n.indexChangePct?.let { c -> if (kotlin.math.abs(c) >= 1.5) careful("The index is already ${pct(c)} on the day: late entries chase the move.") }
        val b = n.burst30Pct; val u = n.usual30Pct
        if (b != null && u != null && u > 0 && kotlin.math.abs(b) >= 3 * u && kotlin.math.abs(b) >= 0.4)
            careful("A sudden ${pct(b)} move in the last 30 minutes: wait for it to settle.")
        // The arms' tested records.
        val narrow = n.openingRangeRatio?.let { it < 0.7 } == true
        for (a in n.armsOn) {
            val rec = RECORD[a] ?: continue
            if (rec.first > 0 && rec.second > 0) ok("Tested: $a made money in both years (${rs(rec.first)}, ${rs(rec.second)}): fine to run.")
            else careful("Tested: $a lost in both years (${rs(rec.first)}, ${rs(rec.second)}): better switched off." +
                if (narrow && a.startsWith("ORB")) " Today's opening range is narrow, when ORB breakouts failed most." else "")
        }
        if (r.none { it.level != Level.GO }) ok("Nothing unusual: no events, VIX calm, the market moving normally.")
        val level = r.maxOfOrNull { it.level } ?: Level.GO
        return Verdict(level, r, n.reads)
    }
}
