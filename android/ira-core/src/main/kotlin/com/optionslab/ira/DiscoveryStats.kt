package com.optionslab.ira

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The strategy lab's own small statistics (11 Oct, "make the strategies self-improve and find new strategies"): the honest
 * tests the shadow variants ([VariantLab]) and the forge ([StrategyForge]) must pass, kept here so the two judge alike.
 *
 *  - [bhThreshold] / [bhPass]: Benjamini-Hochberg across every p-value ever made (the false-discovery rate, not one test).
 *  - [signTestP]: one-sided sign test (more winners than a coin would give), ties dropped.
 *  - [bootstrapP]: one-sided bootstrap test that a mean is above zero (centred resampling, seeded).
 *  - [alwaysValidP]: the mixture sequential probability ratio test (mSPRT) on rupees per trade - a p-value that stays valid
 *    however often it is looked at, so a forward record can be judged every night without cheating.
 *  - [penalizedAlpha]: the trial-count penalty - the more ideas ever tried, the stricter the bar for each.
 *  - [twinP]: the random-twin control (the candidate's mean against the same number of random entries on the same days).
 *  - [Rng]: a small deterministic generator (splitmix64), so a night's run is the same every time for the same seed.
 *
 * Pure: no clock, no I/O.
 */
object DiscoveryStats {
    /**
     * The Benjamini-Hochberg cut-off at false-discovery rate [q] over [ps]: the largest p(k) with p(k) <= k q / m, or 0.0 when
     * none passes (nothing is a discovery). A p at or under it is a discovery.
     */
    fun bhThreshold(ps: Collection<Double>, q: Double): Double = cut(ps, q) ?: 0.0

    /** The largest passing p(k), or null when none passes. */
    private fun cut(ps: Collection<Double>, q: Double): Double? {
        if (ps.isEmpty()) return null
        val s = ps.map { if (it.isNaN()) 1.0 else it.coerceIn(0.0, 1.0) }.sorted()
        val m = s.size
        var cut: Double? = null
        for (k in 1..m) if (s[k - 1] <= k * q / m) cut = s[k - 1]
        return cut
    }

    /** Is [p] a discovery among [all] (which must include it) at rate [q]? */
    fun bhPass(p: Double, all: Collection<Double>, q: Double): Boolean {
        val t = cut(all, q) ?: return false
        return p <= t
    }

    /** One-sided sign test: P(at least this many positives | fair coin), zeros dropped; 1.0 for nothing to test. */
    fun signTestP(xs: List<Double>): Double {
        val pos = xs.count { it > 0 }
        val n = pos + xs.count { it < 0 }
        if (n == 0) return 1.0
        // P(X >= pos), X ~ Bin(n, 1/2), summed in logs (n can be a few hundred).
        var p = 0.0
        for (k in pos..n) p += exp(lnChoose(n, k) - n * LN2)
        return p.coerceIn(0.0, 1.0)
    }

    private val LN2 = ln(2.0)
    private fun lnChoose(n: Int, k: Int): Double = lnFact(n) - lnFact(k) - lnFact(n - k)
    private fun lnFact(n: Int): Double { var s = 0.0; for (i in 2..n) s += ln(i.toDouble()); return s }

    /**
     * One-sided bootstrap p that the mean of [xs] is above zero: resample the centred values [b] times (seeded), the share of
     * resampled means at or above the observed one. 1.0 for fewer than two values.
     */
    fun bootstrapP(xs: List<Double>, seed: Long, b: Int = 1_000): Double {
        if (xs.size < 2) return 1.0
        val m = xs.average()
        val c = DoubleArray(xs.size) { xs[it] - m }
        val r = Rng(seed)
        var hits = 0
        for (i in 0 until b) {
            var s = 0.0
            for (j in c.indices) s += c[r.nextInt(c.size)]
            if (s / c.size >= m) hits++
        }
        return (hits + 1.0) / (b + 1.0)
    }

    /**
     * The mSPRT's always-valid one-sided p after each value of [xs] (rupees per trade, or a variant's rupees minus the current
     * rules' on the same day): normal mixture with scale tau = the running standard deviation (floored at [minSd]), H0 mean
     * <= 0 (with [sign] -1: H0 mean >= 0, testing "worse"). The p only falls: p(n) = min(p(n-1), 1 / Lambda(n)), and only
     * moves on a mean of the tested sign. 1.0 until [minN] values.
     */
    fun alwaysValidP(xs: List<Double>, sign: Int = 1, minSd: Double = 1.0, minN: Int = 5): Double {
        var p = 1.0
        var sum = 0.0; var sum2 = 0.0
        for ((i, x0) in xs.withIndex()) {
            val x = x0 * sign
            sum += x; sum2 += x * x
            val n = i + 1
            if (n < minN) continue
            val mean = sum / n
            val v = ((sum2 - n * mean * mean) / (n - 1)).coerceAtLeast(minSd * minSd)
            if (mean <= 0) continue
            val tau2 = v
            val lambda = sqrt(v / (v + n * tau2)) * exp(n.toDouble() * n * tau2 * mean * mean / (2 * v * (v + n * tau2)))
            if (lambda.isFinite() && lambda > 0) p = minOf(p, 1.0 / lambda) else if (lambda.isInfinite()) p = 0.0
        }
        return p.coerceIn(0.0, 1.0)
    }

    /** The trial-count penalty: [alpha] / (1 + ln(tried)), every idea or variant ever tried counted. */
    fun penalizedAlpha(alpha: Double, tried: Int): Double = alpha / (1.0 + ln(maxOf(1, tried).toDouble()))

    /** The random-twin p: the share of [twinMeans] at or above [observed] (with the observed itself counted once). */
    fun twinP(observed: Double, twinMeans: List<Double>): Double =
        (1.0 + twinMeans.count { it >= observed }) / (twinMeans.size + 1.0)

    /** The deepest fall of the running sum of [pnls] (a positive number of rupees; 0 for none). */
    fun maxDrawdown(pnls: List<Double>): Double {
        var peak = 0.0; var run = 0.0; var dd = 0.0
        for (x in pnls) { run += x; if (run > peak) peak = run; dd = maxOf(dd, peak - run) }
        return dd
    }

    /** The plain t of a mean (0 for fewer than two or no spread). */
    fun t(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        val sd = sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
        return if (sd == 0.0) 0.0 else m / (sd / sqrt(xs.size.toDouble()))
    }

    /** splitmix64: small, fast, the same numbers for the same seed on every JVM. */
    class Rng(seed: Long) {
        private var s = seed
        fun nextLong(): Long {
            s += -0x61c8864680b583ebL
            var z = s
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        /** Uniform in [0, 1). */
        fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
        fun nextInt(n: Int): Int = if (n <= 1) 0 else ((nextLong() ushr 1) % n).toInt()
        fun nextGaussian(): Double {
            val u = nextDouble().coerceAtLeast(1e-300); val v = nextDouble()
            return sqrt(-2.0 * ln(u)) * kotlin.math.cos(2 * Math.PI * v)
        }
        /** Gamma(shape, 1) by Marsaglia-Tsang (shape < 1 boosted). */
        fun nextGamma(shape: Double): Double {
            if (shape < 1.0) return nextGamma(shape + 1.0) * Math.pow(nextDouble().coerceAtLeast(1e-300), 1.0 / shape)
            val d = shape - 1.0 / 3.0
            val c = 1.0 / sqrt(9.0 * d)
            while (true) {
                var x: Double; var v: Double
                do { x = nextGaussian(); v = 1.0 + c * x } while (v <= 0)
                v = v * v * v
                val u = nextDouble()
                if (u < 1 - 0.0331 * x * x * x * x) return d * v
                if (ln(u.coerceAtLeast(1e-300)) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
            }
        }
        /** Beta(a, b). */
        fun nextBeta(a: Double, b: Double): Double {
            val x = nextGamma(a); val y = nextGamma(b)
            return if (x + y == 0.0) 0.5 else x / (x + y)
        }
    }

    /** A seed from a day and a salt (a night's forge is the same every time it is run for that day). */
    fun seedOf(day: java.time.LocalDate, salt: Long = 0L): Long = day.toEpochDay() * 1_000_003L + salt

    /** Rupees with a sign: "+Rs 1,234", "-Rs 56". */
    fun rs(x: Double): String = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(java.util.Locale.ENGLISH, abs(x))

    /** "11 Oct". */
    fun day(d: java.time.LocalDate): String = "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)}"

    internal fun near(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) <= eps
}
