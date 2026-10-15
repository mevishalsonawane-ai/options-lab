package com.optionslab.engine.signals

import com.optionslab.engine.NseHours
import com.optionslab.engine.signals.CallParser.Trig
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.floor

/**
 * Paper-trading a Telegram chat's calls (15 Oct 2026, Boss's explicit choice): the rules, pure. BOSS'S CHOICE - NOT PROVEN,
 * PAPER ONLY ([BOSS_CHOICE]); the app's arm places only paper orders and has no live path at all.
 *
 * Per call, with Boss's settings ([Settings], these defaults): 1 lot; a reaction delay of 30 s after the message, then bought
 * when the premium trades at or above the call's entry within 15 minutes of the message ([phase], [triggered]; "near" within
 * 2% of it, "cmp" at once), else "not triggered" and no order; the stop is the call's own when given (below the fill), else
 * 20% under the fill - "our stop", labelled ([stop]); out per the exit rule ([Exit]: book all at T1 by default, or half at T1
 * and the stop to cost, or hold to the last target - [target]); square-off 15:15, or 15:05 on that index's expiry day
 * ([squareOff]); MIS. At most 3 paper trades a day per chat, and a chat's paper trading pauses for the day once its day's paper
 * loss reaches the cap (Rs 3,000 by default) ([refusal]). Calls that failed the reality check or were not read surely never get
 * here (they wait in "Needs review").
 */
object SignalPaperRules {
    const val LABEL = "Telegram calls (paper)"
    const val BOSS_CHOICE = "Boss's choice — not proven, paper only"
    /** The owner tag on its paper orders. */
    const val OWNER = "Signal tester (paper)"

    enum class Exit(val key: String, val words: String) {
        T1_ALL("t1", "book all at T1"), HALF_COST("half", "half at T1, then the stop to cost"), HOLD_LAST("last", "hold to the last target");
        companion object { fun of(k: String?): Exit = entries.firstOrNull { it.key == k } ?: T1_ALL }
    }

    data class Settings(val lots: Int = 1, val delaySec: Int = 30, val validityMin: Int = 15, val exit: Exit = Exit.T1_ALL,
                        val maxPerDay: Int = 3, val lossCap: Double = 3_000.0, val ourStop: Double = CallEval.OUR_STOP)

    enum class Phase { WAIT, OPEN, LATE }

    /** The square-off of a day: 15:15, or 15:05 when the option expires that day. */
    fun squareOff(expiryDay: Boolean): LocalTime = if (expiryDay) LocalTime.of(NseHours.EXPIRY_SQUARE_OFF / 60, NseHours.EXPIRY_SQUARE_OFF % 60) else CallEval.SQ

    /** Where a call sent at [msgAt] stands at [now]: before its reaction delay, in its entry window, or past it (or the square-off). */
    fun phase(msgAt: LocalDateTime, now: LocalDateTime, s: Settings, expiryDay: Boolean): Phase {
        val from = maxOf(msgAt, msgAt.toLocalDate().atTime(CallEval.OPEN))
        val end = minOf(from.plusMinutes(s.validityMin.toLong()), msgAt.toLocalDate().atTime(squareOff(expiryDay)))
        return when {
            !now.toLocalDate().isEqual(msgAt.toLocalDate()) || !now.isBefore(end) -> Phase.LATE
            now.isBefore(from.plusSeconds(s.delaySec.toLong())) -> Phase.WAIT
            else -> Phase.OPEN
        }
    }

    /**
     * The premium has traded at the entry since the reaction delay: [ltp] now and [high] / [low] of the minutes since then (null:
     * unknown). "Above": at or above the entry; "near": inside 2% of it (or of the range); "cmp": at once.
     */
    fun triggered(trig: Trig, entry: Double?, entryHigh: Double?, ltp: Double?, high: Double?, low: Double?): Boolean {
        if (trig == Trig.CMP) return ltp != null && ltp > 0
        val e = entry ?: return false
        return when (trig) {
            Trig.ABOVE -> (ltp != null && ltp >= e) || (high != null && high >= e)
            else -> {
                val lo = e * (1 - CallEval.ZONE); val hi = (entryHigh ?: e) * (1 + CallEval.ZONE)
                (ltp != null && ltp in lo..hi) || (high != null && low != null && high >= lo && low <= hi)
            }
        }
    }

    /** [x] down to the 0.05 tick. */
    fun tickDown(x: Double): Double = floor(x / 0.05 + 1e-9) * 0.05

    /** The stop for a fill at [fill]: the call's [given] stop when below the fill, else [Settings.ourStop] under it ("our stop": true). */
    fun stop(given: Double?, fill: Double, s: Settings): Pair<Double, Boolean> =
        given?.takeIf { it > 0 && it < fill }?.let { it to false } ?: (CallParser.round2(tickDown(fill * (1 - s.ourStop))).coerceAtLeast(0.05) to true)

    /** The level the exit rule sells at first: T1 (all, or half), or the last target; null without targets (stop or square-off). */
    fun target(exit: Exit, targets: List<Double>): Double? = when (exit) {
        Exit.HOLD_LAST -> targets.lastOrNull()
        else -> targets.firstOrNull()
    }

    /** The lots sold at T1 under [Exit.HALF_COST] (0 for 1 lot: then only the stop moves to cost and the lot rides to the last target). */
    fun halfLots(lots: Int): Int = lots / 2

    /** Why a chat may not take a new paper trade today ([tradesToday] taken, [netToday] its closed paper rupees today), or null. */
    fun refusal(tradesToday: Int, netToday: Double, s: Settings): String? = when {
        netToday <= -s.lossCap -> "paused for today: its paper loss reached the ₹%,.0f cap".format(java.util.Locale.ENGLISH, s.lossCap)
        tradesToday >= s.maxPerDay -> "${s.maxPerDay} paper trades today already"
        else -> null
    }
}
