package com.optionslab.ira

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The ONE statistics engine (S4, 11 Oct): the agents' learning ([AgentLessons], [AutoChange], [Experiments], [AutoGate]) and
 * the strategy lab ([VariantLab], [StrategyForge], [ShadowSim], through the thin delegates in [DiscoveryStats]) judge with
 * the same single, tested implementation of each method. StatsGoldenTest pins the outputs from before the merge.
 * Small, pure and deterministic; every number documented where it is set.
 *
 *  - [signFlipP]: a lesson's rupee effect tested against zero by a sign-flip (randomisation) test on its per-day sums - the
 *    days are the independent units (trades on one day share the market), so a day is flipped as a whole. Exact up to
 *    [EXACT_DAYS] days (every sign pattern counted), the normal approximation of the same statistic beyond. It is the
 *    analytic twin of a day-block bootstrap: no random numbers, so the same days always give the same p.
 *  - [binomTwoSided] / [binomUpper]: exact binomial tests (a rate of days or events against its threshold; daily reviews).
 *  - [twoSampleP]: the difference of two sides' mean per case (a regime split), with day-clustered variances.
 *  - [bh]: Benjamini-Hochberg q-values across a family of tests (the false discovery rate controlled at the q chosen).
 *  - [bar]: the evidence bar raised as Autopilot's trials grow - a Bonferroni-style bump: q / (1 + trials / [TRIAL_BLOCK]),
 *    so every [TRIAL_BLOCK] configurations tried shares the bar once more (0.10 at 0 trials, 0.05 at 20, 0.025 at 60).
 *  - [Sprt]: Wald's sequential probability ratio test on rupees per case, for probations and paper experiments.
 *
 * The lab's side (formerly DiscoveryStats' own copies):
 *  - [bhThreshold] / [bhPass]: the same Benjamini-Hochberg as [bh], given as the cut-off p over a family.
 *  - [signTestP]: one-sided sign test = [binomUpper] at a half (ties dropped).
 *  - [bootstrapP]: one-sided centred bootstrap that a mean is above zero (seeded).
 *  - [alwaysValidP]: the mixture SPRT's always-valid p (a forward record judged every night without cheating); [Sprt] is
 *    Wald's fixed-effect test for probations - two sequential tests answering different questions, both kept here.
 *  - [penalizedAlpha]: the lab's trial-count penalty alpha / (1 + ln tried) (its own documented bar; [bar] is Autopilot's).
 *  - [twinP], [maxDrawdown], [t] and the seeded generator [Rng] (splitmix64, with Gamma and Beta draws).
 */
object LearnStats {
    /** Up to this many days a sign-flip test counts every pattern (2^10 = 1,024 sums); beyond, the normal approximation. */
    const val EXACT_DAYS = 10

    /** Every this many configurations tried, the evidence bar is shared once more ([bar]). */
    const val TRIAL_BLOCK = 20

    /** The bar a q-value must clear after [trials] configurations tried, from the base [q] (Bonferroni-style). */
    fun bar(trials: Int, q: Double): Double = q / (1.0 + maxOf(0, trials).toDouble() / TRIAL_BLOCK)

    // ---- the normal distribution ------------------------------------------------------------------------------------------

    /** erfc(x) to about 1e-7 (Numerical Recipes' Chebyshev fit). */
    fun erfc(x: Double): Double {
        val z = abs(x)
        val t = 1.0 / (1.0 + 0.5 * z)
        val r = t * exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
            t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))))
        return if (x >= 0) r else 2.0 - r
    }

    /** P(Z <= z) for a standard normal. */
    fun phi(z: Double): Double = 0.5 * erfc(-z / sqrt(2.0))

    /** The two-sided p of a z-score. */
    fun pOfZ(z: Double): Double = if (!z.isFinite()) (if (z.isNaN()) 1.0 else 0.0) else minOf(1.0, erfc(abs(z) / sqrt(2.0)))

    // ---- one sample: the rupee effect against zero -------------------------------------------------------------------------

    /**
     * Two-sided p that the per-day sums [sums] are centred on zero (no effect), flipping each day's sign. Fewer than three
     * days with anything in them say nothing (1.0).
     */
    fun signFlipP(sums: DoubleArray): Double {
        var g = 0
        for (s in sums) if (abs(s) > 1e-9) g++
        if (g < 3) return 1.0
        val xs = DoubleArray(g)
        var k = 0
        for (s in sums) if (abs(s) > 1e-9) xs[k++] = s
        var total = 0.0
        var sq = 0.0
        for (x in xs) { total += x; sq += x * x }
        if (g <= EXACT_DAYS) {
            // Every sign pattern, walked in Gray-code order (one flip a step): count those at least as far from zero.
            val target = abs(total) - 1e-9 * (1.0 + abs(total))
            var sum = total
            val sign = IntArray(g) { 1 }
            var count = if (abs(sum) >= target) 1 else 0
            val patterns = 1 shl g
            for (i in 1 until patterns) {
                val bit = Integer.numberOfTrailingZeros(i)
                sum -= 2.0 * sign[bit] * xs[bit]
                sign[bit] = -sign[bit]
                if (abs(sum) >= target) count++
            }
            return count.toDouble() / patterns
        }
        return pOfZ(total / sqrt(sq))
    }

    // ---- binomial ------------------------------------------------------------------------------------------------------------

    private fun logPmf(k: Int, n: Int, p: Double, logFact: DoubleArray): Double =
        logFact[n] - logFact[k] - logFact[n - k] + (if (k > 0) k * ln(p) else 0.0) + (if (n - k > 0) (n - k) * ln(1 - p) else 0.0)

    private fun logFactorials(n: Int): DoubleArray {
        val f = DoubleArray(n + 1)
        for (i in 1..n) f[i] = f[i - 1] + ln(i.toDouble())
        return f
    }

    /** Exact two-sided binomial p of [k] in [n] at rate [p0] (every outcome no likelier than [k] summed). */
    fun binomTwoSided(k: Int, n: Int, p0: Double): Double {
        if (n <= 0 || k < 0 || k > n) return 1.0
        val p = p0.coerceIn(1e-9, 1 - 1e-9)
        val lf = logFactorials(n)
        val at = logPmf(k, n, p, lf)
        var s = 0.0
        for (i in 0..n) { val l = logPmf(i, n, p, lf); if (l <= at + 1e-7) s += exp(l) }
        return s.coerceIn(0.0, 1.0)
    }

    /** Exact one-sided p of [k] or more in [n] at rate [p0]. */
    fun binomUpper(k: Int, n: Int, p0: Double): Double {
        if (n <= 0 || k <= 0) return 1.0
        if (k > n) return 0.0
        val p = p0.coerceIn(1e-9, 1 - 1e-9)
        val lf = logFactorials(n)
        var s = 0.0
        for (i in k..n) s += exp(logPmf(i, n, p, lf))
        return s.coerceIn(0.0, 1.0)
    }

    // ---- two samples: a split by regime, expiry day or part of the day -----------------------------------------------------

    /** One side of a split: per-day counts [n] and rupee sums [rs]. */
    class Side(val n: IntArray, val rs: DoubleArray) {
        val total: Int get() = n.sum()
        val mean: Double get() = total.takeIf { it > 0 }?.let { rs.sum() / it } ?: 0.0
        /** The mean's day-clustered variance (null: fewer than two days). */
        fun variance(): Double? {
            val g = n.count { it > 0 }
            if (g < 2) return null
            val t = total.toDouble()
            val m = mean
            var v = 0.0
            for (i in n.indices) if (n[i] > 0) { val r = rs[i] - m * n[i]; v += r * r }
            return v / (t * t) * g / (g - 1)
        }
    }

    /** Two-sided p that sides [a] and [b] have the same mean per case (day-clustered z). 1.0 when a side is too thin. */
    fun twoSampleP(a: Side, b: Side): Double {
        val va = a.variance() ?: return 1.0
        val vb = b.variance() ?: return 1.0
        val v = va + vb
        val d = a.mean - b.mean
        if (v <= 1e-18) return if (abs(d) > 1e-9) 0.0 else 1.0
        return pOfZ(d / sqrt(v))
    }

    // ---- many tests: Benjamini-Hochberg --------------------------------------------------------------------------------------

    /**
     * Benjamini-Hochberg q-values of [p] (same order): the smallest false-discovery rate at which each would be called. A
     * test passes at level q when its q-value is at most q.
     */
    fun bh(p: DoubleArray): DoubleArray {
        val m = p.size
        if (m == 0) return DoubleArray(0)
        val idx = (0 until m).sortedWith(compareBy({ p[it] }, { it }))
        val q = DoubleArray(m)
        var run = 1.0
        for (r in m - 1 downTo 0) {
            val i = idx[r]
            run = minOf(run, p[i] * m / (r + 1))
            q[i] = run.coerceIn(0.0, 1.0)
        }
        return q
    }

    /**
     * The same Benjamini-Hochberg as [bh], as a cut-off: the largest p(k) with p(k) <= k q / m over [ps] (NaN counted as 1),
     * or null when none passes. A p at or under it is a discovery - the same calls as [bh]'s q-values at most [q] (up to a
     * floating tie exactly at the level; StatsGoldenTest checks the two agree).
     */
    fun bhCut(ps: Collection<Double>, q: Double): Double? {
        if (ps.isEmpty()) return null
        val s = ps.map { if (it.isNaN()) 1.0 else it.coerceIn(0.0, 1.0) }.sorted()
        val m = s.size
        var cut: Double? = null
        for (k in 1..m) if (s[k - 1] <= k * q / m) cut = s[k - 1]
        return cut
    }

    /** The cut-off at false-discovery rate [q], or 0.0 when nothing passes (nothing is a discovery). */
    fun bhThreshold(ps: Collection<Double>, q: Double): Double = bhCut(ps, q) ?: 0.0

    /** Is [p] a discovery among [all] (which must include it) at rate [q]? */
    fun bhPass(p: Double, all: Collection<Double>, q: Double): Boolean {
        val t = bhCut(all, q) ?: return false
        return p <= t
    }

    // ---- the lab's one-sample tests ------------------------------------------------------------------------------------------

    /** One-sided sign test: P(at least this many positives | fair coin) = [binomUpper] at a half, zeros dropped; 1.0 for none. */
    fun signTestP(xs: List<Double>): Double {
        val pos = xs.count { it > 0 }
        val n = pos + xs.count { it < 0 }
        if (n == 0 || pos == 0) return 1.0
        return binomUpper(pos, n, 0.5)
    }

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

    /** The lab's trial-count penalty: [alpha] / (1 + ln(tried)), every idea or variant ever tried counted. */
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

    /** splitmix64: small, fast, the same numbers for the same seed on every JVM. Open: [DiscoveryStats.Rng] is this one. */
    open class Rng(seed: Long) {
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

    // ---- sequential: Wald's SPRT ---------------------------------------------------------------------------------------------

    /**
     * Wald's sequential probability ratio test on a stream of rupees per case (oldest first), each signed so that positive
     * means the change is right ([AutoChange] signs the probation's stream by what the change expected; an experiment's by
     * the variant's lead over the control).
     *
     *  - H0 "it hurts": mean -[EFFECT] sd; H1 "it helps": mean +[EFFECT] sd. The log-likelihood ratio after k cases is
     *    2 x [EFFECT] x (sum of the cases) / sd, with sd the running sample sd (floored at the running |mean|, so a run of
     *    identical cases is never infinite evidence, and at 1e-9).
     *  - KEEP once it reaches ln((1 - beta) / alpha); REVERT once it falls to ln(beta / (1 - alpha)); never before [MIN_N]
     *    cases. alpha ([ALPHA], 0.05) bounds keeping a change that hurts; beta ([BETA], 0.20) bounds reverting one that helps
     *    (Wald's bounds; with the sd estimated as it goes they hold approximately - a seeded simulation checks them).
     *  - Noise (a mean near zero) drifts nowhere: it stays undecided until the probation's cap, where the old rule decides.
     */
    object Sprt {
        const val ALPHA = 0.05
        const val BETA = 0.20
        /** The effect each hypothesis names, in standard deviations of a case. */
        const val EFFECT = 0.2
        /** No call before this many cases. */
        const val MIN_N = 5

        enum class Call(val words: String) { KEEP("keep"), REVERT("revert") }

        data class State(val n: Int, val llr: Double, val upper: Double, val lower: Double, val call: Call?) {
            /** How far toward the nearer boundary (0..1; 1 once called). */
            val progress: Double get() = when {
                call != null -> 1.0
                llr >= 0 -> (llr / upper).coerceIn(0.0, 1.0)
                else -> (llr / lower).coerceIn(0.0, 1.0)
            }
            val leaning: Call? get() = if (llr > 1e-9) Call.KEEP else if (llr < -1e-9) Call.REVERT else null

            /** "decided after 7 cases: keep" / "deciding: 62% of the way (leaning keep), 9 cases". */
            fun words(): String = if (call != null) "decided after $n case${if (n == 1) "" else "s"}: ${call.words}"
                else "deciding: ${Math.round(progress * 100)}% of the way" + (leaning?.let { " (leaning ${it.words})" } ?: "") +
                    ", $n case${if (n == 1) "" else "s"}"
        }

        fun upper(alpha: Double = ALPHA, beta: Double = BETA): Double = ln((1 - beta) / alpha)
        fun lower(alpha: Double = ALPHA, beta: Double = BETA): Double = ln(beta / (1 - alpha))

        /** The test over [ys], stopped at the first case that crosses a boundary (or at the end, undecided). */
        fun run(ys: List<Double>, alpha: Double = ALPHA, beta: Double = BETA, effect: Double = EFFECT, minN: Int = MIN_N): State {
            val up = upper(alpha, beta)
            val lo = lower(alpha, beta)
            var n = 0
            var mean = 0.0
            var m2 = 0.0
            var sum = 0.0
            var llr = 0.0
            for (y in ys) {
                if (!y.isFinite()) continue
                n++
                sum += y
                val d = y - mean
                mean += d / n
                m2 += d * (y - mean)
                // The sd as estimated so far, widened as a Student t's is (sqrt(n / (n - 2))): an early sd is often too small.
                val sd = if (n > 2) sqrt(m2 / (n - 1) * n / (n - 2)) else if (n > 1) sqrt(m2) * 2 else 0.0
                val sigma = max(max(sd, abs(mean)), 1e-9)
                llr = if (abs(sum) < 1e-9) 0.0 else 2 * effect * sum / sigma
                if (n >= minN) {
                    if (llr >= up) return State(n, llr, up, lo, Call.KEEP)
                    if (llr <= lo) return State(n, llr, up, lo, Call.REVERT)
                }
            }
            return State(n, llr, up, lo, null)
        }
    }
}
