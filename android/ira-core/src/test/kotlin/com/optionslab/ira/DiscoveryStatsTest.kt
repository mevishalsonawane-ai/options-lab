package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lab's own statistics: BH, the sign and bootstrap tests, the mSPRT's always-valid p, the penalty, the twin, the generator. */
class DiscoveryStatsTest {
    @Test fun benjaminiHochberg() {
        // m = 4, q = 0.1: cut-offs 0.025, 0.05, 0.075, 0.1 -> the largest passing p(k) is 0.04 (k = 2).
        val ps = listOf(0.01, 0.04, 0.2, 0.9)
        assertEquals(0.04, DiscoveryStats.bhThreshold(ps, 0.1), 1e-12)
        assertTrue(DiscoveryStats.bhPass(0.01, ps, 0.1))
        assertTrue(!DiscoveryStats.bhPass(0.2, ps, 0.1))
        // The same p among a thousand tries no longer passes: that is the point of counting every try.
        val many = listOf(0.01) + List(999) { 0.5 }
        assertTrue(!DiscoveryStats.bhPass(0.01, many, 0.1))
        assertEquals(0.0, DiscoveryStats.bhThreshold(emptyList(), 0.1))
    }

    @Test fun signAndBootstrap() {
        assertEquals(0.5, DiscoveryStats.signTestP(listOf(1.0)), 1e-12)
        assertTrue(DiscoveryStats.signTestP(List(20) { 1.0 }) < 1e-5)
        assertEquals(1.0, DiscoveryStats.signTestP(listOf(0.0, 0.0)))
        val up = List(60) { 100.0 + (it % 7) * 10 }
        assertTrue(DiscoveryStats.bootstrapP(up, 1) < 0.01)
        val r = DiscoveryStats.Rng(5)
        val noise = List(60) { r.nextGaussian() }
        assertTrue(DiscoveryStats.bootstrapP(noise, 2) > 0.01)
        // Seeded: the same every time.
        assertEquals(DiscoveryStats.bootstrapP(noise, 2), DiscoveryStats.bootstrapP(noise, 2))
    }

    @Test fun theSequentialTestRarelyFiresOnNoiseAndFiresOnAnEdge() {
        val r = DiscoveryStats.Rng(11)
        var fired = 0
        repeat(300) { val xs = List(80) { 500 * r.nextGaussian() }; if (DiscoveryStats.alwaysValidP(xs, 1, 50.0) <= 0.05) fired++ }
        // Always valid: at most about 5% of null runs ever cross, however often they are looked at.
        assertTrue(fired <= 300 * 0.07, "fired $fired")
        val edge = List(80) { 400 + 500 * r.nextGaussian() }
        assertTrue(DiscoveryStats.alwaysValidP(edge, 1, 50.0) < 0.01)
        assertTrue(DiscoveryStats.alwaysValidP(edge, -1, 50.0) > 0.5)
        // It only falls as data comes in.
        val ps = (5..80).map { DiscoveryStats.alwaysValidP(edge.take(it), 1, 50.0) }
        assertTrue(ps.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test fun thePenaltyTwinAndDrawdown() {
        assertEquals(0.05, DiscoveryStats.penalizedAlpha(0.05, 1), 1e-12)
        assertTrue(DiscoveryStats.penalizedAlpha(0.05, 1000) < 0.007)
        assertEquals(1.0 / 101, DiscoveryStats.twinP(10.0, List(100) { 1.0 }), 1e-12)
        assertEquals(1.0, DiscoveryStats.twinP(0.0, List(100) { 1.0 }), 1e-12)
        assertEquals(300.0, DiscoveryStats.maxDrawdown(listOf(100.0, -200.0, -100.0, 50.0)), 1e-12)
        assertEquals("+Rs 1,234", DiscoveryStats.rs(1234.0)); assertEquals("-Rs 56", DiscoveryStats.rs(-56.0))
    }

    @Test fun theGeneratorIsSeededAndTheBetaIsRight() {
        val a = DiscoveryStats.Rng(42); val b = DiscoveryStats.Rng(42)
        repeat(100) { assertEquals(a.nextLong(), b.nextLong()) }
        val r = DiscoveryStats.Rng(3)
        val xs = List(4000) { r.nextBeta(8.0, 2.0) }
        assertEquals(0.8, xs.average(), 0.02)
        assertTrue(xs.all { it in 0.0..1.0 })
        repeat(1000) { assertTrue(r.nextInt(7) in 0..6) }
    }
}
