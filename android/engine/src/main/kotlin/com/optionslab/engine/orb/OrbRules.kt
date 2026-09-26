package com.optionslab.engine.orb

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.floor

/** A 5-minute bar, labelled by its START in IST. Usable only once it has closed. */
data class Bar(val start: LocalDateTime, val open: Double, val high: Double, val low: Double, val close: Double)

data class Arm(val source: String, val label: String, val freshOnly: Boolean = false)

/**
 * The opening-range-break rule, exactly as the desktop's
 * services/ai_signals/orb_arm.py decides it (strategies/orb/ORB_STRATEGY.md).
 * Pure: no clock, no network, no orders. The pre-registered forward test
 * forbids changing any of it; a change starts a new test.
 *
 *   range     high/low of the bars labelled 09:15 .. 10:00 (exists once 10:00 has closed)
 *   strike    ATM from the first completed bar at or after 09:20, half-up to 100, held all day
 *   decide    completed bars labelled after 10:00 and before 14:30
 *   entry     close above the range buys the CE, below buys the PE
 *   exits     -40 / +40 premium points, 15:10 square-off
 */
object OrbRules {
    val ORB = Arm("orb", "ORB")
    val ORB_FRESH = Arm("orb_fresh", "ORB Fresh", freshOnly = true)
    val ARMS = listOf(ORB, ORB_FRESH)

    const val UNDERLYING = "BANKNIFTY"
    const val TARGET_POINTS = 40.0
    const val STOP_POINTS = 40.0
    const val STRIKE_STEP = 100
    const val TICK = 0.05
    val OR_START: LocalTime = LocalTime.of(9, 15)
    val OR_END: LocalTime = LocalTime.of(10, 0)
    val LAST_ENTRY_BAR: LocalTime = LocalTime.of(14, 30)
    val STRIKE_BAR: LocalTime = LocalTime.of(9, 20)
    val SQUARE_OFF: LocalTime = LocalTime.of(15, 10)
    val WINDOW_FROM: LocalTime = LocalTime.of(9, 20)
    val WINDOW_UNTIL: LocalTime = LocalTime.of(15, 12)
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private const val EPS = 1e-9

    /** Bars that have closed by [now]; the forming bar is never a signal. */
    fun completed(bars: List<Bar>, now: LocalDateTime) = bars.filter { !it.start.plusMinutes(5).isAfter(now) }

    /** (high, low) of 09:15 .. 10:00, or null until the 10:00 bar is in. */
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

    /** (+1 CE / -1 PE / 0, why) on the last completed bar of [bars]. */
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

    /** The start of the 5-minute bar [moment] falls in. */
    fun barOf(moment: LocalDateTime): LocalDateTime =
        moment.withMinute(moment.minute - moment.minute % 5).withSecond(0).withNano(0)

    /** Half-up, not banker's: floor(spot / step + 0.5) * step. */
    fun atmStrike(spot: Double, step: Int = STRIKE_STEP) = (floor(spot / step + 0.5) * step).toInt()

    /** The first completed bar at or after 09:20. */
    fun strikeBar(bars: List<Bar>): Bar? = bars.firstOrNull { !it.start.toLocalTime().isBefore(STRIKE_BAR) }

    /** The nearest listed expiry strictly after [day]: never today's. */
    fun expiryAfter(day: LocalDate, listed: Collection<LocalDate>): LocalDate? = listed.filter { it.isAfter(day) }.minOrNull()

    fun optionSymbol(expiry: LocalDate, strike: Int, right: String, underlying: String = UNDERLYING) =
        "%s%02d%s%02d%d%s".format(underlying, expiry.dayOfMonth, MONTHS[expiry.monthValue - 1], expiry.year % 100, strike, right)

    /** The risk core's verdict for a bought option with a fixed stop and target. */
    fun exitReason(entry: Double, ltp: Double, now: LocalDateTime, points: Double = STOP_POINTS): String? {
        if (!now.toLocalTime().isBefore(SQUARE_OFF)) return "session_end"
        val stop = entry - points
        if (stop > EPS && ltp <= stop + EPS) return "stop"          // no stop at or below zero, as the core
        if (ltp >= entry + points - EPS) return "target"
        return null
    }

    /** The resting SL-M trigger on the 0.05 tick, or null when a 40-point stop has no level (premium <= 40). */
    fun stopTrigger(entry: Double): Double? {
        val level = entry - STOP_POINTS
        if (level <= EPS) return null
        val ticks = BigDecimal(level / TICK).setScale(0, RoundingMode.HALF_EVEN).toDouble()   // Python round(): half to even
        return BigDecimal(ticks * TICK).setScale(2, RoundingMode.HALF_EVEN).toDouble()
    }

    /** The arm runs every minute from 09:20 to 15:12. */
    fun inWindow(t: LocalTime) = !t.isBefore(WINDOW_FROM) && !t.isAfter(WINDOW_UNTIL)
}
