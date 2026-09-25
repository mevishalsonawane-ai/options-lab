package com.iraalgo.app.rules

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.floor

data class Bar(val start: LocalDateTime, val open: Double, val high: Double, val low: Double, val close: Double)
data class Arm(val source: String, val freshOnly: Boolean = false)

/** Port of the pure half of services/ai_signals/orb_arm.py. Change it only together with the Python and the vectors. */
object OrbRules {
    val ORB = Arm("orb")
    val ORB_FRESH = Arm("orb_fresh", freshOnly = true)
    val ARMS = listOf(ORB, ORB_FRESH)
    const val TARGET_POINTS = 40.0
    const val STOP_POINTS = 40.0
    private val OR_START = LocalTime.of(9, 15)
    private val OR_END = LocalTime.of(10, 0)
    private val LAST_ENTRY_BAR = LocalTime.of(14, 30)
    val SQUARE_OFF: LocalTime = LocalTime.of(15, 10)
    val STRIKE_BAR: LocalTime = LocalTime.of(9, 20)
    private const val TICK = 0.05
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private const val EPS = 1e-9

    fun completed(bars: List<Bar>, now: LocalDateTime) = bars.filter { !it.start.plusMinutes(5).isAfter(now) }

    fun openingRange(bars: List<Bar>): Pair<Double, Double>? {
        val window = bars.filter { val t = it.start.toLocalTime(); !t.isBefore(OR_START) && !t.isAfter(OR_END) }
        if (window.isEmpty() || window.last().start.toLocalTime() != OR_END) return null
        return window.maxOf { it.high } to window.minOf { it.low }
    }

    fun breakDirection(bar: Bar, orh: Double, orl: Double) = when {
        bar.close > orh -> 1
        bar.close < orl -> -1
        else -> 0
    }

    fun mayDecide(bar: Bar): Boolean { val t = bar.start.toLocalTime(); return t.isAfter(OR_END) && t.isBefore(LAST_ENTRY_BAR) }

    fun entrySignal(bars: List<Bar>, rng: Pair<Double, Double>, arm: Arm, lastExit: LocalDateTime?): Pair<Int, String> {
        val last = bars.last()
        if (!mayDecide(last)) return 0 to "no_decision_bar"
        if (lastExit != null && !last.start.isAfter(barOf(lastExit))) return 0 to "cooling_down_after_exit"
        val direction = breakDirection(last, rng.first, rng.second)
        if (direction == 0) return 0 to "inside_range"
        if (arm.freshOnly && bars.size > 1 && breakDirection(bars[bars.size - 2], rng.first, rng.second) == direction) {
            return 0 to "not_a_fresh_break"
        }
        return direction to "break"
    }

    fun barOf(moment: LocalDateTime): LocalDateTime =
        moment.withMinute(moment.minute - moment.minute % 5).withSecond(0).withNano(0)

    fun atmStrike(spot: Double, step: Int = 100) = (floor(spot / step + 0.5) * step).toInt()

    fun optionSymbol(expiry: LocalDate, strike: Int, right: String, underlying: String = "BANKNIFTY") =
        "%s%02d%s%02d%d%s".format(underlying, expiry.dayOfMonth, MONTHS[expiry.monthValue - 1], expiry.year % 100, strike, right)

    /** The risk core's verdict for a bought option with a fixed stop and target (services/risk). */
    fun exitReason(entry: Double, ltp: Double, now: LocalDateTime, points: Double = 40.0): String? {
        if (!now.toLocalTime().isBefore(SQUARE_OFF)) return "session_end"
        val stop = entry - points
        if (stop > EPS && ltp <= stop + EPS) return "stop"          // no stop at or below zero, as the core
        if (ltp >= entry + points - EPS) return "target"
        return null
    }

    fun stopTrigger(entry: Double): Double? {
        val level = entry - STOP_POINTS
        if (level <= EPS) return null
        val ticks = BigDecimal(level / TICK).setScale(0, RoundingMode.HALF_EVEN).toDouble()   // Python round(): half to even
        return BigDecimal(ticks * TICK).setScale(2, RoundingMode.HALF_EVEN).toDouble()
    }
}
