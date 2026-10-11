package com.optionslab.ira

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4 golden values: the outputs of the lab's ([DiscoveryStats]) and the agents' ([LearnStats]) statistics pinned BEFORE the
 * two were merged into one engine, so the merge is proven not to move a single number (within floating tolerance).
 * Every input is seeded; every expected value was printed by the pre-merge code.
 */
class StatsGoldenTest {
    private fun near(expected: Double, actual: Double, what: String) {
        val tol = 1e-12 * maxOf(1.0, abs(expected))
        assertTrue(abs(expected - actual) <= tol || (expected == actual), "$what: expected $expected, got $actual")
    }

    private val g = DiscoveryStats.Rng(7)
    private val xs = List(40) { 120 + 600 * g.nextGaussian() }
    private val noise = List(30) { 600 * g.nextGaussian() }
    private val ps = List(50) { g.nextDouble() * g.nextDouble() * 0.2 }

    @Test fun theGeneratorIsUnchanged() {
        val r = DiscoveryStats.Rng(42)
        assertEquals(-4767286540954276203L, r.nextLong())
        near(0.1599103928769201, r.nextDouble(), "double")
        assertEquals(3, r.nextInt(17))
        near(1.4190220997251124, r.nextGaussian(), "gauss")
        near(0.3195932811026914, r.nextGamma(0.7), "gamma<1")
        near(3.6798514634558175, r.nextGamma(3.5), "gamma")
        near(0.06440462027092453, r.nextBeta(2.0, 5.0), "beta")
        near(938.995378474337, xs[0], "xs0"); near(-55.18807883611677, xs[39], "xs39"); near(69.42482915192284, noise[29], "noise29")
    }

    @Test fun theLabsTestsAreUnchanged() {
        near(0.6820859986855824, DiscoveryStats.signTestP(xs), "sign xs")
        near(0.5722322240471871, DiscoveryStats.signTestP(noise), "sign noise")
        near(5.896998563585176E-27, DiscoveryStats.signTestP(List(300) { if (it % 5 == 0) -1.0 else 1.0 }), "sign big")
        near(0.1848151848151848, DiscoveryStats.bootstrapP(xs, 9), "boot xs")
        near(0.469061876247505, DiscoveryStats.bootstrapP(noise, 9, 500), "boot noise")
        near(1.0, DiscoveryStats.alwaysValidP(xs, 1, 50.0), "avp")
        near(1.0, DiscoveryStats.alwaysValidP(xs, -1, 50.0), "avp-")
        near(4.019224793656005E-6, DiscoveryStats.alwaysValidP(xs.map { it + 400 }, 1, 50.0), "avp edge")
        near(9.020645516498465E-5, DiscoveryStats.alwaysValidP(xs.map { it - 500 }, -1, 100.0), "avp edge-")
        near(1.0, DiscoveryStats.alwaysValidP(noise, 1, 100.0, 3), "avp noise")
        near(0.010843827833691902, DiscoveryStats.penalizedAlpha(0.05, 37), "penalty")
        near(0.05, DiscoveryStats.penalizedAlpha(0.05, 0), "penalty0")
        near(0.6666666666666666, DiscoveryStats.twinP(1.5, listOf(0.3, 1.5, 2.0, -1.0, 1.7)), "twin")
        near(1631.5586333560886, DiscoveryStats.maxDrawdown(xs), "dd")
        near(0.8635949524794865, DiscoveryStats.t(xs), "t")
        near(0.06616227277481197, DiscoveryStats.bhThreshold(ps, 0.1), "bh cut")
        near(0.001080369999205586, DiscoveryStats.bhThreshold(ps + List(200) { 1.0 }, 0.1), "bh cut padded")
    }

    @Test fun theAgentsTestsAreUnchanged() {
        val q = LearnStats.bh(ps.toDoubleArray())
        near(0.05184137032697601, q[0], "q0"); near(0.17109127572088315, q[4], "q4"); near(0.013504624990069825, q[16], "q16")
        near(0.1503725131549764, q[43], "q43"); near(0.11523843325460506, q[49], "q49")
        val q2 = LearnStats.bh(doubleArrayOf(0.01, 0.04, 0.04, 0.2, 0.9, 0.0))
        listOf(0.03, 0.06, 0.06, 0.24000000000000005, 0.9, 0.0).forEachIndexed { i, e -> near(e, q2[i], "q2[$i]") }
        near(0.09375, LearnStats.signFlipP(doubleArrayOf(300.0, -120.0, 450.0, 80.0, -60.0, 220.0, 0.0, 510.0)), "flip exact")
        near(0.38629704065181736, LearnStats.signFlipP(xs.toDoubleArray()), "flip normal")
        near(0.01602962880044086, LearnStats.binomTwoSided(14, 40, 0.55), "binom2")
        near(0.008294501687487382, LearnStats.binomUpper(28, 40, 0.5), "binomU")
        near(0.01239336004590997, LearnStats.twoSampleP(
            LearnStats.Side(intArrayOf(2, 1, 3, 0, 2), doubleArrayOf(400.0, -100.0, 900.0, 0.0, 250.0)),
            LearnStats.Side(intArrayOf(1, 2, 2, 1), doubleArrayOf(-300.0, 100.0, -200.0, 50.0))), "two-sample")
        near(0.03508771929824561, LearnStats.bar(37, 0.1), "bar")
        near(0.903199509886342, LearnStats.phi(1.3), "phi")
        near(0.02780689249229054, LearnStats.pOfZ(-2.2), "pz")
        val s = LearnStats.Sprt.run(xs.map { it + 200 })
        assertEquals(19, s.n); near(3.207936368942773, s.llr, "sprt llr"); assertEquals(LearnStats.Sprt.Call.KEEP, s.call)
        val s2 = LearnStats.Sprt.run(noise.map { it - 250 }, alpha = 0.02)
        assertEquals(17, s2.n); near(-1.8077347505144925, s2.llr, "sprt2 llr"); assertEquals(LearnStats.Sprt.Call.REVERT, s2.call)
        val s3 = LearnStats.Sprt.run(noise)
        assertEquals(30, s3.n); near(0.1555514937705386, s3.llr, "sprt3 llr"); assertEquals(null, s3.call)
    }

    /** The lab's BH cut-off and the agents' q-values call the same discoveries (random families, ties and padding included). */
    @Test fun bothBenjaminiHochbergFormsAgree() {
        val r = DiscoveryStats.Rng(99)
        repeat(400) { rep ->
            val m = 1 + r.nextInt(60)
            val fam = List(m) { if (r.nextInt(5) == 0) 1.0 else (r.nextDouble() * r.nextDouble() * 0.3) } +
                (if (rep % 7 == 0) listOf(0.05, 0.05) else emptyList())
            val q = LearnStats.bh(fam.toDoubleArray())
            // A q-value within a hair of the level is a floating tie (p m / r against r q / m): either call is right.
            for ((i, p) in fam.withIndex()) if (abs(q[i] - 0.1) > 1e-12)
                assertEquals(q[i] <= 0.1, DiscoveryStats.bhPass(p, fam, 0.1), "rep $rep p $p q ${q[i]}")
        }
    }

    /** The sign test is the binomial upper tail at a half. */
    @Test fun theSignTestIsTheBinomialTail() {
        for (n in 1..60) for (k in 0..n) {
            val xs = List(k) { 1.0 } + List(n - k) { -1.0 }
            val a = DiscoveryStats.signTestP(xs)
            val b = if (k == 0) 1.0 else LearnStats.binomUpper(k, n, 0.5)
            assertTrue(abs(a - b) <= 1e-12 * maxOf(1e-300, a) + 1e-15, "n $n k $k: $a vs $b")
        }
    }
}
