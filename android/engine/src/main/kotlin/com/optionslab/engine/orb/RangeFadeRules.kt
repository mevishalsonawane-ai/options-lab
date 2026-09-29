package com.optionslab.engine.orb

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Range Fade: on a range day, a bar that reaches the outer tenth of the opening range and closes back inside is faded
 * back toward the middle. Paper only.
 *
 *   range     the ORB's own opening range (09:15 .. 10:00), day strike and next expiry
 *   decide    completed 5-minute bars from 10:30 to 13:55
 *   entry     high within the top 10% of the range (or above it) and the close below the range high: buy the PE;
 *             low within the bottom 10% (or below it) and the close above the range low: buy the CE
 *   exits     -40 / +40 premium points (the ORB's own), 15:10 square-off
 *   limit     at most [MAX_ENTRIES] entries a day; after an exit it may decide again from the next bar
 *
 * Found on 29 Sep 2026 among 8 intraday setups tested on 21 BANKNIFTY sessions (10 Aug - 10 Sep 2026) with the real
 * option minute bars: 33 trades, 79% winners, +Rs 18,050 per lot net, t = 3.2, profitable on both halves. That month
 * was range-bound (every breakout setup lost there), so it is a regime bet: paper only until more months agree.
 * The engine's own replay of the same month (ArmsBacktest: 10:30 start, cap of 2) gives 28 trades, 75%, +Rs 14,133, t = 2.6.
 */
object RangeFadeRules {
    val ARM = Arm("range_fade", "Range Fade", fade = true, paperOnly = true)
    const val EDGE = 0.10
    const val MAX_ENTRIES = 2
    val FIRST_BAR: LocalTime = LocalTime.of(10, 30)
    val LAST_BAR: LocalTime = LocalTime.of(14, 0)
    private const val EPS = 1e-9

    fun mayDecide(bar: Bar): Boolean { val t = bar.start.toLocalTime(); return !t.isBefore(FIRST_BAR) && t.isBefore(LAST_BAR) }

    /** (+1 CE / -1 PE / 0, why) on the last completed bar of [bars]; [entriesToday] = the arm's entries so far today. */
    fun entrySignal(bars: List<Bar>, rng: Pair<Double, Double>, lastExit: LocalDateTime?, entriesToday: Int): Pair<Int, String> {
        val last = bars.lastOrNull() ?: return 0 to "no_decision_bar"
        if (!mayDecide(last)) return 0 to "no_decision_bar"
        if (entriesToday >= MAX_ENTRIES) return 0 to "day_limit_reached"
        if (lastExit != null && !last.start.isAfter(OrbRules.barOf(lastExit))) return 0 to "cooling_down_after_exit"
        val (orh, orl) = rng
        val w = orh - orl
        if (w <= EPS) return 0 to "no_range"
        return when {
            last.high >= orh - EDGE * w - EPS && last.close < orh - EPS -> -1 to "fade_of_high"
            last.low <= orl + EDGE * w + EPS && last.close > orl + EPS -> 1 to "fade_of_low"
            else -> 0 to "not_at_the_edge"
        }
    }
}
