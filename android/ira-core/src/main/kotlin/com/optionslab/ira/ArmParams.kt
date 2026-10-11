package com.optionslab.ira

import com.optionslab.engine.orb.Arm
import com.optionslab.engine.orb.LiquidityParams
import com.optionslab.engine.orb.OrbParams
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.RangeFadeRules
import com.optionslab.engine.orb.SweepRules
import com.optionslab.engine.orb.VixDivParams
import com.optionslab.engine.orb.VixDivRules
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Each arm's tunable parameters as the strategy lab keeps them (S1 / S3, 11 Oct): a map of named values per arm ([defaults]:
 * the arm's own constants), and the same values as the engine's typed parameters the arms and the lab's replays read
 * ([orb], [liquidity], [vixDiv]). A value missing from a map is the arm's default; an unknown key is ignored, so a lab file
 * can never reach a parameter the arm does not have.
 *
 * Liquidity 15+5 (S9): EXIT parameters only ([LIQUIDITY_EXIT_KEYS]); its entries (the levels, the room filter, the strike,
 * the hours) are frozen in [com.optionslab.engine.orb.LiquidityRules] and have no key here at all.
 *
 * Pure: no clock, no I/O.
 */
object ArmParams {
    /** The last entry bar (minutes of the day: bars labelled BEFORE it decide). */
    const val UNTIL = "until"
    /** The opening range's minutes from 09:15 (50: the bars 09:15 .. 10:00). */
    const val OR = "or"
    const val BUFFER = "buffer"
    const val STOP_PTS = "stopPts"
    const val TARGET_PTS = "targetPts"
    const val EDGE = "edge"
    const val STOP_PCT = "stopPct"
    /** Liquidity's and VIX divergence's time stop: minutes after the entry. */
    const val TS = "ts"
    /** Liquidity's time stop: the gain the premium needs by then. */
    const val TSG = "tsg"
    /** Liquidity's target rule: 1 = sell at the next liquidity level (the arm's), 0 = no level target. */
    const val TGT = "tgt"
    /** Liquidity's profit lock: its reference as a share of the premium paid (0: none, the arm's). */
    const val LOCK = "lock"
    /** VIX divergence's index move a check needs. */
    const val MOVE = "move"

    /** The ONLY keys a Liquidity 15+5 variant may carry: its exits. */
    val LIQUIDITY_EXIT_KEYS: Set<String> = setOf(STOP_PCT, TS, TSG, TGT, LOCK)

    /** The ORB family's arms by the lab's key. */
    val ORB_ARMS: Map<String, Arm> = listOf(OrbRules.ORB, OrbRules.ORB_FRESH, SweepRules.ARM, RangeFadeRules.ARM).associateBy { it.source }

    private fun minutes(t: LocalTime) = (t.hour * 60 + t.minute).toDouble()
    private fun time(m: Double): LocalTime { val x = m.roundToInt().coerceIn(0, 24 * 60 - 1); return LocalTime.of(x / 60, x % 60) }
    private fun orMinutes(orEnd: LocalTime) = minutes(orEnd) - minutes(OrbRules.OR_START) + 5

    /** [key]'s parameters as the arm runs them today (its constants), or empty for an arm the lab does not tune. */
    fun defaults(key: String): Map<String, Double> {
        ORB_ARMS[key]?.let { arm ->
            val p = OrbParams.of(arm)
            return when {
                arm.sweep -> mapOf(UNTIL to minutes(p.lastEntry), STOP_PTS to p.stopPoints, TARGET_PTS to p.targetPoints)
                arm.fade -> mapOf(UNTIL to minutes(p.lastEntry), EDGE to p.edge, STOP_PTS to p.stopPoints, TARGET_PTS to p.targetPoints)
                else -> mapOf(UNTIL to minutes(p.lastEntry), OR to orMinutes(p.orEnd), BUFFER to p.buffer, STOP_PTS to p.stopPoints,
                    TARGET_PTS to p.targetPoints)
            }
        }
        return when (key) {
            "liquidity" -> LiquidityParams.DEFAULT.let { p ->
                mapOf(STOP_PCT to p.stopPct, TS to p.timeStopMinutes.toDouble(), TSG to p.timeStopGain, TGT to if (p.nextLevelTarget) 1.0 else 0.0,
                    LOCK to p.lockPct)
            }
            VixDivRules.SOURCE -> VixDivParams.DEFAULT.let { p -> mapOf(MOVE to p.indexMove, STOP_PCT to p.stopPct, TS to p.minutes.toDouble()) }
            else -> emptyMap()
        }
    }

    /** The ORB family arm [key]'s engine parameters from [values] (missing: its defaults). */
    fun orb(key: String, values: Map<String, Double>?): OrbParams {
        val arm = ORB_ARMS[key] ?: OrbRules.ORB
        val d = OrbParams.of(arm)
        if (values.isNullOrEmpty()) return d
        return OrbParams(
            orEnd = values[OR]?.takeIf { !arm.sweep && !arm.fade }?.let { time(minutes(OrbRules.OR_START) + it - 5) } ?: d.orEnd,
            lastEntry = values[UNTIL]?.let { time(it) } ?: d.lastEntry,
            buffer = values[BUFFER]?.takeIf { !arm.sweep && !arm.fade } ?: d.buffer,
            stopPoints = values[STOP_PTS] ?: d.stopPoints,
            targetPoints = values[TARGET_PTS] ?: d.targetPoints,
            edge = values[EDGE]?.takeIf { arm.fade } ?: d.edge,
        )
    }

    /** Liquidity 15+5's EXIT parameters from [values]: any other key is ignored (its entries are frozen). */
    fun liquidity(values: Map<String, Double>?): LiquidityParams {
        val d = LiquidityParams.DEFAULT
        if (values.isNullOrEmpty()) return d
        return LiquidityParams(
            stopPct = values[STOP_PCT] ?: d.stopPct,
            timeStopMinutes = values[TS]?.roundToInt()?.toLong() ?: d.timeStopMinutes,
            timeStopGain = values[TSG] ?: d.timeStopGain,
            nextLevelTarget = values[TGT]?.let { it >= 0.5 } ?: d.nextLevelTarget,
            lockPct = values[LOCK]?.coerceAtLeast(0.0) ?: d.lockPct,
        )
    }

    /** VIX divergence's parameters from [values]. */
    fun vixDiv(values: Map<String, Double>?): VixDivParams {
        val d = VixDivParams.DEFAULT
        if (values.isNullOrEmpty()) return d
        return VixDivParams(values[MOVE] ?: d.indexMove, values[STOP_PCT] ?: d.stopPct, values[TS]?.roundToInt() ?: d.minutes)
    }
}
