package com.optionslab.engine.orb

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Straddle Sell: no direction call. Sell the ATM call AND put once a day and keep the time decay; buy both back at
 * 15:10, or together as soon as the pair has lost half the premium collected. Paper only.
 *
 *   strike    the ORB's day strike (ATM from the first completed bar at or after 09:20), nearest expiry after today
 *   entry     both legs sold once the strike is known (the 09:20 bar closes at 09:25), not after [LAST_ENTRY]
 *   stop      buy both back when (cost to buy back - credit) >= [STOP_FRACTION] x credit
 *   exit      15:10 (OrbRules.SQUARE_OFF)
 *   limit     one straddle a day
 *
 * Why this one: on Feb 2024 - Feb 2026 BANKNIFTY (real option minute prices, both years separately) every way of BUYING
 * options lost, and this was the only structure positive in both years without a direction call: +Rs 60k and +Rs 29k
 * per lot (intraday, 50% stop). Not proven (t about 1), rare large losses without the stop, needs ~Rs 2 lakh margin
 * live - so it trades the paper account only.
 */
object StraddleRules {
    val ARM = Arm("straddle_sell", "Straddle Sell", straddle = true, paperOnly = true)
    const val STOP_FRACTION = 0.5
    val FIRST_ENTRY: LocalTime = LocalTime.of(9, 25)
    val LAST_ENTRY: LocalTime = LocalTime.of(10, 30)

    /** (sell now?, why) at [now], given whether a straddle was already sold today. */
    fun entryDecision(now: LocalDateTime, soldToday: Boolean): Pair<Boolean, String> {
        val t = now.toLocalTime()
        return when {
            soldToday -> false to "done_for_today"
            t.isBefore(FIRST_ENTRY) -> false to "waiting_for_0920_strike"
            !t.isBefore(LAST_ENTRY) -> false to "too_late_to_sell_today"
            else -> true to "sell"
        }
    }

    /** Loss of the sold pair now, in premium points (positive = losing): cost to buy back minus the credit. */
    fun loss(credit: Double, buyBack: Double): Double = buyBack - credit

    /** "stop" / "session_end" / null for the pair sold at [credit] (sum of the two sale prices), now worth [buyBack]. */
    fun exitReason(credit: Double, buyBack: Double, now: LocalDateTime): String? {
        if (!now.toLocalTime().isBefore(OrbRules.SQUARE_OFF)) return "session_end"
        if (credit > 0 && loss(credit, buyBack) >= STOP_FRACTION * credit - 1e-9) return "stop"
        return null
    }
}
