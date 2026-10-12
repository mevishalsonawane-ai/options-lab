package com.optionslab.engine.orb

import java.time.LocalTime

/**
 * The arms' tunable parameters (the strategy lab, S1 / S3, 11 Oct 2026): what a proven variant may change, and nothing else.
 * Each default is the arm's own constant, so an arm given its defaults trades exactly as before. The lab replays the arms with
 * these (a variant is the real rules with other values) and the app arms read them per session; the bounds a value may take
 * are the lab's ([com.optionslab.ira.VariantLab]), never wider than the arm's researched maximum, and lots are not a parameter.
 *
 * Pure: no clock, no network, no orders.
 */
data class OrbParams(
    /** The opening range: the bars labelled 09:15 .. [orEnd] (the ORB's 10:00). */
    val orEnd: LocalTime,
    /** Decisions on bars labelled before [lastEntry] (ORB 14:00, ORB Sweep 14:30, Range Fade 14:00). */
    val lastEntry: LocalTime,
    /** A break must close beyond the range by this share of it (0: any close beyond, as the arm). */
    val buffer: Double,
    /** The premium stop and target in points (-40 / +40; ORB Sweep -40 / +80). The ladder's rungs sit on the target. */
    val stopPoints: Double,
    val targetPoints: Double,
    /** Range Fade's edge: the outer share of the range a bar must reach (10%). */
    val edge: Double = RangeFadeRules.EDGE,
    /**
     * ORB / ORB Fresh: at most this many entries a day on each side (CE, PE); 0 = no cap, the arms' rule until R14
     * ([ReentryCap], research/HUNT_R14_REENTRY_CHURN.md: 1 a side cut ORB's 5-year loss from Rs 8.4 lakh to Rs 1.2 lakh,
     * mostly charges). Fewer entries only: it never changes an exit.
     */
    val sideCap: Int = 0,
) {
    companion object {
        val ORB = OrbParams(OrbRules.OR_END, OrbRules.LAST_ENTRY_BAR, 0.0, OrbRules.STOP_POINTS, OrbRules.TARGET_POINTS)
        val SWEEP = OrbParams(OrbRules.OR_END, OrbRules.SWEEP_LAST_ENTRY_BAR, 0.0, SweepRules.STOP_POINTS, SweepRules.TARGET_POINTS)
        val FADE = OrbParams(OrbRules.OR_END, RangeFadeRules.LAST_BAR, 0.0, OrbRules.STOP_POINTS, OrbRules.TARGET_POINTS, RangeFadeRules.EDGE)

        /** [arm]'s defaults: its own constants. */
        fun of(arm: Arm): OrbParams = when {
            arm.sweep -> SWEEP
            arm.fade -> FADE
            else -> ORB
        }
    }
}

/**
 * Liquidity 15+5's EXIT parameters (S9: the only proven edge may tune its exits only; its entries - the levels, the room
 * filter, the strike, the hours - are constants in [LiquidityRules] and are not here at all). Defaults: the 15% premium stop,
 * out after 20 minutes unless +5%, out at the next liquidity level, no profit lock.
 */
data class LiquidityParams(
    val stopPct: Double = LiquidityRules.PREMIUM_STOP,
    val timeStopMinutes: Long = LiquidityRules.TIME_STOP_MINUTES,
    val timeStopGain: Double = LiquidityRules.TIME_STOP_GAIN,
    /** Sell when the index touches the next liquidity level (the arm's target); false: no level target. */
    val nextLevelTarget: Boolean = true,
    /** The profit lock's reference as a share of the premium paid ([ProfitLock] rungs on it); 0: none (the arm today). */
    val lockPct: Double = 0.0,
) {
    /** The profit-lock reference in premium points for a buy at [entry], or null with no lock. */
    fun lockTarget(entry: Double): Double? = if (lockPct > 0) entry * lockPct else null

    companion object { val DEFAULT = LiquidityParams() }
}

/** VIX divergence's tunables: the index move a check needs, and NIFTY's stop and time-stop minutes. */
data class VixDivParams(
    val indexMove: Double = VixDivRules.INDEX_MOVE,
    val stopPct: Double = VixDivRules.LIQ_STOP,
    val minutes: Int = VixDivRules.LIQ_MINUTES,
) {
    companion object { val DEFAULT = VixDivParams() }
}

/**
 * Which account (paper or Zerodha) an arm's NEW entries go to (line-by-line review G2-1..G2-3, 11 Oct): decided once and
 * shared by the signal's parameters, the approval notice, the approval itself and the strategy lab's record, so none of
 * them can disagree. Exits are never decided here: an open position keeps its own account. Pure: no clock, no I/O.
 */
object ArmAccount {
    /**
     * A new entry of [arm]: live only when the app is in Live ([appLive]), the arm may trade on Zerodha (not paper-only), and -
     * Liquidity 15+5 - its self-review has not parked it to paper ([parked]).
     */
    fun entry(arm: Arm, appLive: Boolean, parked: Boolean): Boolean = appLive && !arm.paperOnly && !(arm.liquidity && parked)

    /**
     * An approval of [arm]'s signal: the account [entry] decides now, or null when that is not the account the approval notice
     * named ([noticed]; null for a signal saved before the notice kept it) - nothing is then entered, so the notice and the
     * order never disagree (a paper notice never becomes a Zerodha order).
     */
    fun approval(arm: Arm, appLive: Boolean, parked: Boolean, noticed: Boolean?): Boolean? {
        val live = entry(arm, appLive, parked)
        return if (noticed != null && noticed != live) null else live
    }

    /**
     * The strategy lab's account per lab key ([keyOf] an arm's source) for the [armed] arms: live when ANY armed arm of the key
     * enters live ([live]) - one answer per key, whatever the arms' order.
     */
    fun byKey(armed: List<Arm>, keyOf: (String) -> String, live: (Arm) -> Boolean): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        for (a in armed) { val k = keyOf(a.source); out[k] = out[k] == true || live(a) }
        return out
    }
}
