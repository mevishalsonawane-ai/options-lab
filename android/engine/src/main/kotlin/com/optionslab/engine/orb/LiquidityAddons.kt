package com.optionslab.engine.orb

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Liquidity 15+5 BANKNIFTY add-ons pre-registered for a SHADOW test (research/PREREG_LIQUIDITY_ADDONS.md, 12 Oct 2026):
 * entry filters from research/hunt/x2/PREREG.md that made money in the locked holdout but did NOT pass their own bar before
 * it, so they may only be watched, never traded on, until the shadow test says otherwise. Both default OFF
 * ([LiquidityAddonParams.OFF]): with them off [decide] takes every signal, so nothing the arm does changes.
 *
 *  - A1 OI veto (x2 R1): skip a signal when the option OI build-up opposes it - [bu15] or [doiPc] (band 5), each z-scored
 *    against the previous 60 sessions ([z]) and signed toward the trade, at or below -1.
 *  - A2 calls need OI (x2 R5): take every put; take a call only when [bu15]'s signed z is at least +1.
 *
 * The features are h26's (research/hunt/h26/build.py day_feats, feats.py trail_z), read on the nearest expiry's 1-minute
 * chain at the signal minute, the strikes fixed from that minute's ATM. Both rules can only skip trades (lower risk).
 * Pure: no clock, no I/O.
 */
data class LiquidityAddonParams(
    /** A1: skip a signal the option OI build-up opposes. */
    val oiVeto: Boolean = false,
    /** A2: a call needs the OI build-up to confirm it; puts are always taken. */
    val callsNeedOi: Boolean = false,
) {
    companion object { val OFF = LiquidityAddonParams() }
}

object LiquidityAddons {
    /** The build-up's band: ATM +- 2 strikes. */
    const val BU_BAND = 2
    /** The put-minus-call OI change's band: ATM +- 5 strikes (h26 dOIpc5). */
    const val DOI_BAND = 5
    /** Both compare the signal minute with the minute this many minutes before. */
    const val LOOKBACK_MINUTES = 15
    /** z-scores use the previous 60 sessions, and need 20 of them. */
    const val Z_SESSIONS = 60
    const val Z_MIN_SESSIONS = 20
    /** A1 skips at a signed z at or below this; A2 takes a call at a signed z at or above [CALL_Z]. */
    const val VETO_Z = -1.0
    const val CALL_Z = 1.0

    /** One right of one strike at one minute: its last close and open interest (null: not seen yet that day). */
    data class Leg(val close: Double?, val oi: Double?)

    /** A strike's call and put at one minute. */
    data class Quote(val ce: Leg, val pe: Leg)

    private fun ok(x: Double?) = x != null && x.isFinite()

    /** The strikes ATM +- [band] steps around [atm]. */
    fun strikes(atm: Int, step: Int, band: Int): List<Int> = (-band..band).map { atm + it * step }

    /**
     * h26's BU15 at the signal minute: over ATM +- 2 ([atm] from the signal minute), each strike's sign of the call's price
     * change x |the call's OI change| less the same for the put, since [before] (15 minutes earlier), over the strikes' call +
     * put OI [now]. A strike missing any figure is left out of the sum, and one missing an OI now out of the total (numpy's
     * nansum). Null when the total is not positive.
     */
    fun bu15(now: Map<Int, Quote>, before: Map<Int, Quote>, atm: Int, step: Int): Double? {
        var num = 0.0
        var den = 0.0
        for (k in strikes(atm, step, BU_BAND)) {
            val n = now[k]
            val b = before[k]
            if (n != null && b != null && listOf(n.ce.close, n.ce.oi, n.pe.close, n.pe.oi, b.ce.close, b.ce.oi, b.pe.close, b.pe.oi).all(::ok))
                num += sign(n.ce.close!! - b.ce.close!!) * abs(n.ce.oi!! - b.ce.oi!!) - sign(n.pe.close!! - b.pe.close!!) * abs(n.pe.oi!! - b.pe.oi!!)
            if (n != null && ok(n.ce.oi) && ok(n.pe.oi)) den += n.ce.oi!! + n.pe.oi!!
        }
        return if (den > 0) num / den else null
    }

    /**
     * h26's dOIpc at the signal minute: over ATM +- [band], the put OI change less the call OI change since [before], over the
     * strikes' call + put OI [now]; a strike with any OI missing is left out of the sum. Null when the total is not positive.
     */
    fun doiPc(now: Map<Int, Quote>, before: Map<Int, Quote>, atm: Int, step: Int, band: Int = DOI_BAND): Double? {
        var num = 0.0
        var den = 0.0
        for (k in strikes(atm, step, band)) {
            val n = now[k]
            val b = before[k]
            if (n != null && b != null && listOf(n.ce.oi, n.pe.oi, b.ce.oi, b.pe.oi).all(::ok))
                num += (n.pe.oi!! - b.pe.oi!!) - (n.ce.oi!! - b.ce.oi!!)
            if (n != null && ok(n.ce.oi) && ok(n.pe.oi)) den += n.ce.oi!! + n.pe.oi!!
        }
        return if (den > 0) num / den else null
    }

    /** One session's normaliser input: the mean and mean square of a feature's 5-minute samples 09:30 .. 14:30. */
    data class Moments(val mean: Double, val meanSq: Double)

    /** A session's [Moments] from its 5-minute samples (unknown ones skipped); null when none is known. */
    fun moments(samples: List<Double?>): Moments? {
        val x = samples.filterNotNull().filter { it.isFinite() }
        if (x.isEmpty()) return null
        return Moments(x.average(), x.sumOf { it * it } / x.size)
    }

    /**
     * h26's trail_z: [value] against the last [Z_SESSIONS] sessions' [Moments] in [history] (oldest first, today excluded):
     * mean = the average of their means, sd = sqrt(average of mean squares - mean^2). Null under [Z_MIN_SESSIONS] sessions,
     * with no spread, or an unknown value.
     */
    fun z(value: Double?, history: List<Moments?>): Double? {
        if (value == null || !value.isFinite()) return null
        val h = history.takeLast(Z_SESSIONS).filterNotNull()
        if (h.size < Z_MIN_SESSIONS) return null
        val mu = h.sumOf { it.mean } / h.size
        val sd = sqrt(maxOf(h.sumOf { it.meanSq } / h.size - mu * mu, 0.0))
        return if (sd > 0) (value - mu) / sd else null
    }

    /** A decision and why, for the shadow log. */
    data class Verdict(val take: Boolean, val why: String)

    /**
     * Whether a signal going [side] (+1 call, -1 put) is taken under [p], from the z-scores [bu15z] and [doiPc5z] (unsigned;
     * null: unknown). A1 never skips on an unknown z; A2 does not take a call whose build-up z is unknown (x2's rule as run).
     */
    fun decide(p: LiquidityAddonParams, side: Int, bu15z: Double?, doiPc5z: Double?): Verdict {
        val bu = bu15z?.let { it * side }
        val doi = doiPc5z?.let { it * side }
        if (p.oiVeto && ((bu != null && bu <= VETO_Z) || (doi != null && doi <= VETO_Z))) return Verdict(false, "addon_oi_veto")
        if (p.callsNeedOi && side > 0 && (bu == null || bu < CALL_Z)) return Verdict(false, "addon_call_needs_oi")
        return Verdict(true, "addon_take")
    }

    /**
     * The shadow judgement of one add-on (PREREG_LIQUIDITY_ADDONS.md): over the paper trades' per-lot [nets] and whether the
     * add-on would have skipped each ([skipped]), the rupees a trade it would have saved, and a one-sided p that skipping as
     * many trades at random saves at least as much ([draws] random draws, seeded for a repeatable answer).
     */
    data class Judgement(val trades: Int, val skipped: Int, val savedPerTrade: Double, val p: Double, val enough: Boolean)

    /** The fewest shadow trades before an add-on is judged at all. */
    const val MIN_TRADES = 300

    fun judge(nets: List<Double>, skipped: List<Boolean>, draws: Int = 5000, seed: Long = 15): Judgement {
        require(nets.size == skipped.size) { "one flag a trade" }
        val n = nets.size
        val k = skipped.count { it }
        val saved = -nets.filterIndexed { i, _ -> skipped[i] }.sum()
        if (n == 0 || k == 0 || k == n) return Judgement(n, k, if (n == 0) 0.0 else saved / n, 1.0, n >= MIN_TRADES)
        val rnd = Random(seed)
        val idx = IntArray(n) { it }
        var atLeast = 0
        repeat(draws) {
            // a partial Fisher-Yates shuffle: the first k of idx are a random k-subset
            var s = 0.0
            for (i in 0 until k) {
                val j = i + rnd.nextInt(n - i)
                val t = idx[i]; idx[i] = idx[j]; idx[j] = t
                s -= nets[idx[i]]
            }
            if (s >= saved) atLeast++
        }
        return Judgement(n, k, saved / n, (1.0 + atLeast) / (draws + 1.0), n >= MIN_TRADES)
    }

    /** Holm's step-down adjustment of [p] (the family of add-ons judged together). */
    fun holm(p: List<Double>): List<Double> {
        val m = p.size
        val order = p.indices.sortedBy { p[it] }
        val out = DoubleArray(m)
        var run = 0.0
        order.forEachIndexed { r, i ->
            run = maxOf(run, minOf(1.0, (m - r) * p[i]))
            out[i] = run
        }
        return out.toList()
    }
}
