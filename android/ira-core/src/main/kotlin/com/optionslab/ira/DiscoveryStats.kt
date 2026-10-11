package com.optionslab.ira

import kotlin.math.abs

/**
 * The strategy lab's statistics under their old names, now THIN DELEGATES to the one engine in [LearnStats] (S4, 11 Oct:
 * one shared stats engine). [VariantLab], [StrategyForge] and [ShadowSim] call these unchanged; each method is the same
 * single implementation the agents' learning uses, and StatsGoldenTest pins the outputs from before the merge.
 *
 *  - [bhThreshold] / [bhPass]: Benjamini-Hochberg across every p-value ever made ([LearnStats.bhCut]).
 *  - [signTestP]: one-sided sign test ([LearnStats.binomUpper] at a half).
 *  - [bootstrapP], [alwaysValidP] (mSPRT), [penalizedAlpha], [twinP], [maxDrawdown], [t], [Rng]: see [LearnStats].
 *
 * Only the lab's wording helpers ([seedOf], [rs], [day]) live here. Pure: no clock, no I/O.
 */
object DiscoveryStats {
    fun bhThreshold(ps: Collection<Double>, q: Double): Double = LearnStats.bhThreshold(ps, q)
    fun bhPass(p: Double, all: Collection<Double>, q: Double): Boolean = LearnStats.bhPass(p, all, q)
    fun signTestP(xs: List<Double>): Double = LearnStats.signTestP(xs)
    fun bootstrapP(xs: List<Double>, seed: Long, b: Int = 1_000): Double = LearnStats.bootstrapP(xs, seed, b)
    fun alwaysValidP(xs: List<Double>, sign: Int = 1, minSd: Double = 1.0, minN: Int = 5): Double =
        LearnStats.alwaysValidP(xs, sign, minSd, minN)
    fun penalizedAlpha(alpha: Double, tried: Int): Double = LearnStats.penalizedAlpha(alpha, tried)
    fun twinP(observed: Double, twinMeans: List<Double>): Double = LearnStats.twinP(observed, twinMeans)
    fun maxDrawdown(pnls: List<Double>): Double = LearnStats.maxDrawdown(pnls)
    fun t(xs: List<Double>): Double = LearnStats.t(xs)

    /** The one seeded generator ([LearnStats.Rng]) under the lab's old name. */
    class Rng(seed: Long) : LearnStats.Rng(seed)

    /** A seed from a day and a salt (a night's forge is the same every time it is run for that day). */
    fun seedOf(day: java.time.LocalDate, salt: Long = 0L): Long = day.toEpochDay() * 1_000_003L + salt

    /** Rupees with a sign: "+Rs 1,234", "-Rs 56". */
    fun rs(x: Double): String = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(java.util.Locale.ENGLISH, abs(x))

    /** "11 Oct". */
    fun day(d: java.time.LocalDate): String = "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}"

    internal fun near(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) <= eps
}
