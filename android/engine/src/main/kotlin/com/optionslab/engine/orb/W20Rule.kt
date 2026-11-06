package com.optionslab.engine.orb

/**
 * "R51 W20 (shadow)" (6 Nov 2026, Boss: "Do whatever you recommend", after "What about r51?"): research R150 B's W20 on R51
 * Donchian 20 (research/HUNT_R150_SR_LEVELS.md; research/hunt/r150_sr_levels/study_b.py `skip("W20", ...)`): a break is one W20
 * would skip when a 5-minute support / resistance level (3+ touches, the last 20 sessions, [R72Picker.levels]) lies within 0.25%
 * AHEAD of the trade (above a call, below a put) at the signal bar's close. It lost far less than R51 in every period tested
 * (Dec 2022 - Nov 2024 unseen: -Rs 2.3 lakh vs -Rs 4.0 lakh, beat random skips at p < 0.001; Apr - Oct 2026: drawdown Rs 46.5k vs
 * Rs 81.7k) but failed R140's gate that the arm still makes money there, so it is a SHADOW LOG ONLY (CLAUDE.md: a failed idea
 * stays a note): each R51 break is labelled "W20 would skip / keep"; nothing is skipped or changed, paper or live.
 *
 * Pure: no clock, no network, no storage, no orders.
 */
object W20Rule {
    const val LABEL = "R51 W20 (shadow)"
    const val STATUS = "failed gates; a shadow log only"
    const val RULE = "each R51 break is labelled 'W20 would skip' when a 5-minute support / resistance level (3+ touches, last 20 " +
        "sessions) is within 0.25% ahead of it, else 'would keep'; nothing is skipped or changed (shadow log only)"
    const val RECORD = "Research R150 B: unseen Dec 2022 - Nov 2024 -₹2.32 lakh on 2,717 trades vs R51's -₹4.00 lakh on 4,815 (beat " +
        "random skips, p < 0.001); Apr - Oct 2026 +₹1.14 lakh on 980 trades, drawdown ₹46.5k, vs R51's +₹1.35 lakh on 1,670, drawdown " +
        "₹81.7k; win rate about the same (45%); failed only 'the arm still makes money' in the unseen years"
    /** How near ahead a level must be (0.25%), and its least touches. */
    const val DIST = 0.0025
    const val MIN_TOUCHES = 3

    /** R150's wall_ahead: a level of [minTouches]+ touches within [dist] ahead of [price] for [side] (+1 call above, -1 put below). */
    fun wallAhead(levels: List<Pair<Double, Int>>, price: Double, side: Int, dist: Double = DIST, minTouches: Int = MIN_TOUCHES): Boolean =
        levels.any { (l, n) -> n >= minTouches && if (side > 0) price < l && l <= price * (1 + dist) else price * (1 - dist) <= l && l < price }

    /**
     * Whether W20 would skip the break entering at row [er] on [side]: [hist5] the index's earlier sessions' 5-minute bars (oldest
     * first, 20+ sessions), [today] its minutes. Null when unknown (no price before the entry): R150 never skipped then.
     */
    fun skips(hist5: List<PassedArms.Bar5>, today: PassedArms.DayBars, er: Int, side: Int): Boolean? {
        val price = today.closeAtOrBefore(er - 1)
        if (price.isNaN()) return null
        return wallAhead(R72Picker.levels(hist5 + R72Picker.bars5(today, er)), price, side)
    }
}
