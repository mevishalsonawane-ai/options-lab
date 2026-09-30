package com.optionslab.engine.orb

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import com.optionslab.engine.orb.PassRule.tStat
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * The arms replayed on recorded days - the bundled month plus whatever the phone has harvested - with the real option
 * minute bars, so every arm is judged on the same days and the same fills.
 *
 *   bars      5-minute BANKNIFTY bars folded from the index minutes
 *   contract  ATM from the 09:20 bar (nearest recorded strike), the nearest expiry after the day, as the arms trade
 *   entry     the option's first minute after the signal bar closes, plus [slip]
 *   exits     each arm's own stop / target on the option minute lows and highs, 15:10 square-off, minus [slip]
 *   costs     [charges] rupees a round trip, one lot
 *
 * Rows also split the days in two halves (first / second): an arm that only works in one half is not an edge.
 */
object ArmsBacktest {
    val ARMS: List<Arm> = OrbRules.ARMS + SweepRules.ARM + RangeFadeRules.ARM

    data class Trade(val day: LocalDate, val arm: String, val right: String, val signalBar: String, val entry: Double,
                     val exit: Double, val why: String, val net: Double)

    data class Row(val arm: Arm, val trades: Int, val wins: Int, val net: Double, val avg: Double, val t: Double?,
                   val firstHalf: Double, val secondHalf: Double) {
        val winPct: Int get() = if (trades == 0) 0 else Math.round(100.0 * wins / trades).toInt()
    }

    data class Result(val days: Int, val firstDay: LocalDate?, val lastDay: LocalDate?, val rows: List<Row>, val trades: List<Trade>)

    fun stopOf(arm: Arm) = if (arm.sweep) SweepRules.STOP_POINTS else OrbRules.STOP_POINTS
    fun targetOf(arm: Arm) = if (arm.sweep) SweepRules.TARGET_POINTS else OrbRules.TARGET_POINTS

    fun run(sessions: Sequence<Session>, slip: Double = 0.5, charges: Double = 40.0): Result {
        val trades = ArrayList<Trade>()
        val days = sortedSetOf<LocalDate>()
        for (s in sessions) {
            val day = day(s, slip, charges) ?: continue
            days += s.day
            trades += day
        }
        val sorted = days.toList()
        val firstHalf = sorted.take(sorted.size / 2).toSet()
        val rows = ARMS.map { a ->
            val mine = trades.filter { it.arm == a.source }
            val nets = mine.map { it.net }
            Row(a, mine.size, mine.count { it.net > 0 }, nets.sum(), if (nets.isEmpty()) 0.0 else nets.average(), tStat(nets),
                mine.filter { it.day in firstHalf }.sumOf { it.net }, mine.filter { it.day !in firstHalf }.sumOf { it.net })
        }
        return Result(sorted.size, sorted.firstOrNull(), sorted.lastOrNull(), rows, trades)
    }

    /** Every arm's trades on one recorded day, or null when the day lacks the index or its options. */
    fun day(s: Session, slip: Double = 0.5, charges: Double = 40.0): List<Trade>? {
        val ix = s.index ?: return null
        val bars = fiveMinute(s.day, ix)
        OrbRules.openingRange(bars) ?: return null
        val spot = OrbRules.strikeBar(bars)?.close ?: return null
        val strike = OrbRules.atmStrike(spot)
        val expiry = OrbRules.expiryAfter(s.day, s.options.mapNotNull { it.expiry }.toSet()) ?: return null
        fun leg(r: Right) = s.options.filter { it.expiry == expiry && it.right == r && it.size > 0 }.minByOrNull { abs(it.strike - strike) }
        val ce = leg(Right.CE) ?: return null
        val pe = leg(Right.PE) ?: return null
        return ARMS.flatMap { arm -> armDay(s.day, arm, bars, ce, pe, slip, charges) }
    }

    fun fiveMinute(day: LocalDate, ix: Series): List<Bar> {
        val out = ArrayList<Bar>()
        var i = 0
        while (i < ix.size) {
            val m = ix.minutes[i]
            val start = m - m % 5
            var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY
            val open = ix.open?.get(i) ?: ix.close[i]
            var close = ix.close[i]
            while (i < ix.size && ix.minutes[i] - ix.minutes[i] % 5 == start) {
                hi = maxOf(hi, ix.high?.get(i) ?: ix.close[i]); lo = minOf(lo, ix.low?.get(i) ?: ix.close[i]); close = ix.close[i]; i++
            }
            if (start in 9 * 60 + 15..15 * 60 + 25) out += Bar(day.atTime(start / 60, start % 60), open, hi, lo, close)
        }
        return out
    }

    private fun armDay(day: LocalDate, arm: Arm, bars: List<Bar>, ce: Series, pe: Series, slip: Double, charges: Double): List<Trade> {
        val rng = OrbRules.openingRange(bars)!!
        val out = ArrayList<Trade>()
        var lastExit: LocalDateTime? = null
        var k = 0
        while (k < bars.size) {
            val seen = bars.subList(0, k + 1)
            val (dir, _) = when {
                arm.fade -> RangeFadeRules.entrySignal(seen, rng, lastExit, out.size)
                arm.sweep -> SweepRules.entrySignal(seen, rng, lastExit, out.size)
                else -> OrbRules.entrySignal(seen, rng, arm, lastExit)
            }
            if (dir == 0) { k++; continue }
            val leg = if (dir > 0) ce else pe
            val signal = bars[k].start
            val from = signal.hour * 60 + signal.minute + 5
            val t = fill(leg, from, stopOf(arm), targetOf(arm), slip)
            if (t == null) { k++; continue }
            val (entry, exit, why, exitMinute) = t
            out += Trade(day, arm.source, if (dir > 0) "CE" else "PE", "%02d:%02d".format(signal.hour, signal.minute),
                entry, exit, why, (exit - entry) * leg.lot - charges)
            lastExit = day.atTime(exitMinute / 60, exitMinute % 60)
            k++
        }
        return out
    }

    private data class Fill(val entry: Double, val exit: Double, val why: String, val minute: Int)

    /** Bought at the first minute at or after [from]; exits on the stop, the target, or the 15:10 square-off. */
    private fun fill(leg: Series, from: Int, stop: Double, target: Double, slip: Double): Fill? {
        var i = leg.lastAtOrBefore(from - 1) + 1
        if (i >= leg.size) return null
        val squareOff = OrbRules.SQUARE_OFF.hour * 60 + OrbRules.SQUARE_OFF.minute
        if (leg.minutes[i] >= squareOff) return null
        val e = (leg.open?.get(i) ?: leg.close[i]) + slip
        while (i < leg.size) {
            val m = leg.minutes[i]
            val lo = leg.low?.get(i) ?: leg.close[i]
            val hi = leg.high?.get(i) ?: leg.close[i]
            if (e - stop > 0 && lo <= e - stop) return Fill(e, e - stop - slip, "stop", m)
            if (hi >= e + target) return Fill(e, e + target - slip, "target", m)
            if (m >= squareOff) return Fill(e, leg.close[i] - slip, "session_end", m)
            i++
        }
        return Fill(e, leg.close[leg.size - 1] - slip, "last_bar", leg.minutes[leg.size - 1])
    }
}
