package com.optionslab.engine.orb

import java.time.LocalDateTime

/**
 * ORB Sweep: the liquidity-sweep reversal at the opening range. Paper only.
 *
 *   range     the ORB's own opening range (09:15 .. 10:00), and its day strike / next expiry
 *   decide    completed 5-minute bars after 10:00 and before 14:30 (as the ORB)
 *   entry     a bar whose high goes above the range high and CLOSES back below it buys the PE (the breakout
 *             failed); a bar whose low goes below the range low and closes back above it buys the CE
 *   exits     -40 premium points (the resting SL-M, as the ORB), +80 premium points, 15:10 square-off
 *   limit     at most [MAX_ENTRIES] entries a day; after an exit it may decide again from the next bar
 *
 * Chosen on 29 Sep 2026 from 360 settings on 21 BANKNIFTY sessions (10 Aug - 10 Sep 2026) priced with the real
 * option minute bars: settings were picked on the first 11 sessions and checked on the other 10. This one held up
 * out of sample (+Rs 7,112 on 14 trades, 57% winners, t = 1.1) where the in-sample favourites did not; the evidence
 * is still thin (t well under 2), which is why it trades paper only until a forward test says otherwise.
 */
object SweepRules {
    val ARM = Arm("orb_sweep", "ORB Sweep", sweep = true, paperOnly = true)
    const val TARGET_POINTS = 80.0
    const val STOP_POINTS = OrbRules.STOP_POINTS
    const val MAX_ENTRIES = 2
    private const val EPS = 1e-9

    /** (+1 CE / -1 PE / 0, why) on the last completed bar of [bars]; [entriesToday] = the arm's entries so far today. */
    fun entrySignal(bars: List<Bar>, rng: Pair<Double, Double>, lastExit: LocalDateTime?, entriesToday: Int): Pair<Int, String> {
        val last = bars.lastOrNull() ?: return 0 to "no_decision_bar"
        if (!OrbRules.mayDecide(last)) return 0 to "no_decision_bar"
        if (entriesToday >= MAX_ENTRIES) return 0 to "day_limit_reached"
        if (lastExit != null && !last.start.isAfter(OrbRules.barOf(lastExit))) return 0 to "cooling_down_after_exit"
        val (orh, orl) = rng
        return when {
            last.high > orh + EPS && last.close < orh - EPS -> -1 to "sweep_of_high"
            last.low < orl - EPS && last.close > orl + EPS -> 1 to "sweep_of_low"
            else -> 0 to "no_sweep"
        }
    }

    /** Exit for a bought option: -40 stop, +80 target, the ORB's 15:10 square-off. */
    fun exitReason(entry: Double, ltp: Double, now: LocalDateTime): String? {
        if (!now.toLocalTime().isBefore(OrbRules.SQUARE_OFF)) return "session_end"
        val stop = entry - STOP_POINTS
        if (stop > EPS && ltp <= stop + EPS) return "stop"
        if (ltp >= entry + TARGET_POINTS - EPS) return "target"
        return null
    }
}
