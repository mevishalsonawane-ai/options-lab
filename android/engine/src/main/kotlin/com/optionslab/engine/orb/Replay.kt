package com.optionslab.engine.orb

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import kotlin.math.sqrt

data class ReplayTrade(val signalBar: String, val exitBar: String, val right: String, val entry: Double, val exit: Double, val why: String) {
    val points: Double get() = exit - entry
}

/**
 * A day replayed on bars (the desktop's orb_shadow / the phone port's Replay):
 * decide on a completed bar's close, fill on the NEXT bar's open (filling on the
 * signal bar was the look-ahead bug that faked +23,727). A stop fills at
 * entry - 40, or at the open if the bar gaps through it.
 *
 * [index], [ce] and [pe] must be aligned bar for bar (same starts).
 */
object Replay {
    private fun r2(x: Double) = BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toDouble()
    private fun hhmm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    fun day(arm: Arm, index: List<Bar>, ce: List<Bar>, pe: List<Bar>, points: Double = OrbRules.STOP_POINTS): List<ReplayTrade> {
        val rng = OrbRules.openingRange(index) ?: return emptyList()
        val n = minOf(index.size, ce.size, pe.size)
        val trades = mutableListOf<ReplayTrade>()
        var k = 0
        var lastExit: LocalDateTime? = null
        while (k < n - 1) {
            val (direction, _) = OrbRules.entrySignal(index.subList(0, k + 1), rng, arm, lastExit)
            if (direction == 0) { k++; continue }
            val right = if (direction > 0) "CE" else "PE"
            val leg = if (direction > 0) ce else pe
            val entry = leg[k + 1].open
            if (!(entry > 0)) { k++; continue }
            var exitPx = leg[n - 1].close; var x = n - 1; var why = "last_bar"
            for (j in k + 1 until n) {
                val t = index[j].start
                if (!t.toLocalTime().isBefore(OrbRules.SQUARE_OFF)) { exitPx = leg[j].open; x = j; why = "session_end"; break }
                if (OrbRules.exitReason(entry, leg[j].low, t, points) == "stop") { exitPx = minOf(entry - points, leg[j].open); x = j; why = "stop"; break }
                if (OrbRules.exitReason(entry, leg[j].high, t, points) == "target") { exitPx = entry + points; x = j; why = "target"; break }
            }
            trades += ReplayTrade(hhmm(index[k].start), hhmm(index[x].start), right, r2(entry), r2(exitPx), why)
            lastExit = index[x].start
            k = x + 1
        }
        return trades
    }
}

/**
 * The pre-registered pass rule (reference/92_orb_forward_test_preregistration.md).
 * Ends at 60 closed trades or 40 trading days. Passes only if ALL hold on the
 * forward trades alone: net > 0 after charges; per-trade t > 2.0; net > 0 on up
 * days AND on down days; net > 0 after removing the three best trades.
 * Operator-closed trades are excluded by the caller.
 */
object PassRule {
    const val MAX_TRADES = 60
    const val MAX_DAYS = 40

    /** One closed forward trade: its net P&L after charges, and whether the index closed above its open that day. */
    data class Closed(val day: java.time.LocalDate, val net: Double, val upDay: Boolean?)

    data class Verdict(
        val trades: Int, val days: Int, val net: Double, val t: Double?, val upNet: Double, val downNet: Double,
        val dropBest3: Double, val finished: Boolean, val checks: List<Pair<String, Boolean>>,
    ) {
        val passes: Boolean get() = checks.all { it.second }
    }

    fun tStat(xs: List<Double>): Double? {
        if (xs.size < 2) return null
        val m = xs.average()
        val sd = sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
        return if (sd == 0.0) null else m / (sd / sqrt(xs.size.toDouble()))
    }

    fun judge(trades: List<Closed>): Verdict {
        val nets = trades.map { it.net }
        val net = nets.sum()
        val t = tStat(nets)
        val up = trades.filter { it.upDay == true }.sumOf { it.net }
        val down = trades.filter { it.upDay == false }.sumOf { it.net }
        val drop3 = nets.sortedDescending().drop(3).sum()
        val days = trades.map { it.day }.distinct().size
        return Verdict(
            trades.size, days, net, t, up, down, drop3,
            finished = trades.size >= MAX_TRADES || days >= MAX_DAYS,
            checks = listOf(
                "Net P&L > 0 after charges" to (net > 0),
                "Per-trade t > 2.0" to ((t ?: 0.0) > 2.0),
                "Net > 0 on up days" to (up > 0),
                "Net > 0 on down days" to (down > 0),
                "Net > 0 without the 3 best trades" to (drop3 > 0),
            ),
        )
    }
}
