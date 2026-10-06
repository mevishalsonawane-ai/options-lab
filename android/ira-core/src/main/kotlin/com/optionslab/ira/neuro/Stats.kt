package com.optionslab.ira.neuro

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The statistical guards every learned edge passes through. Nothing in the graph is a fact until it has held on data it
 * was not fitted on:
 *
 *  - a probability (B after A, an event in a context, a stock moving with its index on the index's big days) is fitted on
 *    the OLDER 70% of its samples and checked on the NEWEST 30% ([split]);
 *  - an edge exists at all (a HYPOTHESIS) only with at least [MIN_FIT] samples and [MIN_HITS] hits in the fit part, and a
 *    lift of at least [MIN_LIFT] over the base rate;
 *  - it is PROVEN only when the 95% Wilson lower bound of the fit rate is above the fit base rate AND, on the newest 30%
 *    (at least [MIN_TEST] samples), the 80% Wilson lower bound is still above that part's base rate;
 *  - and every probability edge is tested together: no edge is PROVEN unless its fit part's one-sided exact binomial
 *    p-value survives the Benjamini-Hochberg false-discovery-rate cut at [FDR_Q] across ALL the probability candidates of
 *    the build ([binomUpper], [bhCutoff]) - many relations are tried, so some would pass by chance alone;
 *  - a correlation is a hypothesis at |r| >= [MIN_CORR] on [MIN_CORR_N] days, proven when the Fisher 95% interval of the
 *    fit r stays beyond [PROVEN_CORR] and the newest 30% (at least [MIN_CORR_TEST] days) has the same sign beyond it too.
 *
 * Pure.
 */
object Stats {
    const val MIN_FIT = 20
    const val MIN_HITS = 5
    const val MIN_TEST = 8
    const val MIN_LIFT = 1.15
    const val MIN_CORR = 0.3
    const val PROVEN_CORR = 0.2
    const val MIN_CORR_N = 40
    const val MIN_CORR_TEST = 15
    /** z for a two-sided 95% interval, and for the 80% one the newest 30% is checked with. */
    const val Z95 = 1.96
    const val Z80 = 1.2816
    /** The false-discovery rate the probability edges are held to, together (Benjamini-Hochberg). */
    const val FDR_Q = 0.05

    /** The share of samples (oldest first) the edge is fitted on. */
    const val FIT_SHARE = 0.7

    /** The Wilson score interval of [k] successes in [n] trials at [z]: (low, high); (0, 1) when n is 0. */
    fun wilson(k: Int, n: Int, z: Double = Z95): Pair<Double, Double> {
        if (n <= 0) return 0.0 to 1.0
        val p = k.toDouble() / n
        val z2 = z * z
        val d = 1 + z2 / n
        val c = (p + z2 / (2 * n)) / d
        val h = z * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / d
        return (c - h).coerceAtLeast(0.0) to (c + h).coerceAtMost(1.0)
    }

    /** The Fisher-z interval of a correlation [r] on [n] pairs at [z]: (low, high). */
    fun fisher(r: Double, n: Int, z: Double = Z95): Pair<Double, Double> {
        if (n <= 3 || r.isNaN()) return -1.0 to 1.0
        val rr = r.coerceIn(-0.999999, 0.999999)
        val f = 0.5 * ln((1 + rr) / (1 - rr))
        val se = 1 / sqrt(n - 3.0)
        fun back(x: Double) = (exp(2 * x) - 1) / (exp(2 * x) + 1)
        return back(f - z * se) to back(f + z * se)
    }

    /** Pearson's r from sums: n, Σx, Σy, Σx², Σy², Σxy. NaN when either side does not vary. */
    fun pearson(n: Double, sx: Double, sy: Double, sxx: Double, syy: Double, sxy: Double): Double {
        if (n < 3) return Double.NaN
        val vx = sxx - sx * sx / n
        val vy = syy - sy * sy / n
        if (vx <= 1e-18 || vy <= 1e-18) return Double.NaN
        return ((sxy - sx * sy / n) / sqrt(vx * vy)).coerceIn(-1.0, 1.0)
    }

    /**
     * P(X >= [k]) for X ~ Binomial([n], [p]): the one-sided exact p-value of seeing [k] or more hits in [n] trials at the
     * base rate [p]. Summed in log space (n of a few thousand is fine).
     */
    fun binomUpper(k: Int, n: Int, p: Double): Double {
        if (k <= 0) return 1.0
        if (k > n || p <= 0.0) return 0.0
        if (p >= 1.0) return 1.0
        var logC = 0.0
        for (i in 0 until k) logC += ln((n - i).toDouble()) - ln((i + 1).toDouble())
        var logPmf = logC + k * ln(p) + (n - k) * ln(1 - p)
        val step = ln(p) - ln(1 - p)
        var sum = 0.0
        var x = k
        while (x <= n) {
            val term = exp(logPmf)
            sum += term
            if (term < sum * 1e-15 && x > n * p) break
            logPmf += ln((n - x).toDouble()) - ln((x + 1).toDouble()) + step
            x++
        }
        return sum.coerceIn(0.0, 1.0)
    }

    /**
     * The Benjamini-Hochberg cut for [ps] at false-discovery rate [q]: the largest p(i) (sorted ascending, i from 1) with
     * p(i) <= i / m * q - every p at or under it is a discovery. -1 when there is none (nothing may be proven).
     */
    fun bhCutoff(ps: List<Double>, q: Double = FDR_Q): Double {
        val m = ps.size
        if (m == 0) return -1.0
        val sorted = ps.filter { !it.isNaN() }.sorted()
        var cut = -1.0
        for ((i, p) in sorted.withIndex()) if (p <= (i + 1).toDouble() / m * q) cut = p
        return cut
    }

    /** The index that splits [n] samples, oldest first, into the fitted 70% (before it) and the checked 30%. */
    fun split(n: Int): Int = Math.floor(n * FIT_SHARE).toInt().coerceIn(0, n)

    /**
     * Split [n] samples into the fit part (before the first index) and the test part (from the second), leaving an EMBARGO
     * of [gap] samples between them: a sample whose outcome looks [gap] sessions ahead overlaps the test part's first days.
     */
    fun splitWithGap(n: Int, gap: Int): Pair<Int, Int> { val s = split(n); return s to (s + gap.coerceAtLeast(0)).coerceAtMost(n) }

    /** A probability edge's verdict: null (no edge), or proven / not ([pValue]: the fit part's, for the FDR cut). */
    data class Verdict(val proven: Boolean, val fitP: Double, val fitBase: Double, val lift: Double, val lo: Double, val hi: Double,
                       val testP: Double, val testBase: Double, val pValue: Double = Double.NaN)

    /**
     * [k] hits of [n] samples in the fit part against [base] (that part's base rate), [tk] of [tn] in the test part against
     * [tBase]. Null when there is no edge (too few samples or hits, or lift under [MIN_LIFT]).
     */
    fun judge(k: Int, n: Int, base: Double, tk: Int, tn: Int, tBase: Double): Verdict? {
        if (n < MIN_FIT || k < MIN_HITS || base <= 0.0 || base.isNaN()) return null
        val p = k.toDouble() / n
        val lift = p / base
        if (lift < MIN_LIFT) return null
        val (lo, hi) = wilson(k, n, Z95)
        val tp = if (tn > 0) tk.toDouble() / tn else Double.NaN
        val proven = lo > base && tn >= MIN_TEST && !tBase.isNaN() && wilson(tk, tn, Z80).first > tBase
        return Verdict(proven, p, base, lift, lo, hi, tp, tBase, binomUpper(k, n, base))
    }

    /** A correlation's verdict on the fit [r]/[n] and the test [tr]/[tn]; null when there is no edge. */
    fun judgeCorr(r: Double, n: Int, tr: Double, tn: Int): Pair<Boolean, Pair<Double, Double>>? {
        if (r.isNaN() || n < MIN_CORR_N || abs(r) < MIN_CORR) return null
        val ci = fisher(r, n, Z95)
        val beyond = if (r > 0) ci.first > PROVEN_CORR else ci.second < -PROVEN_CORR
        val held = tn >= MIN_CORR_TEST && !tr.isNaN() && (if (r > 0) tr >= PROVEN_CORR else tr <= -PROVEN_CORR)
        return (beyond && held) to ci
    }
}
