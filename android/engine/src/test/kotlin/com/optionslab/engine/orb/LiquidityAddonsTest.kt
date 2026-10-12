package com.optionslab.engine.orb

import com.optionslab.engine.orb.LiquidityAddons.Leg
import com.optionslab.engine.orb.LiquidityAddons.Moments
import com.optionslab.engine.orb.LiquidityAddons.Quote
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pre-registered Liquidity add-ons (research/PREREG_LIQUIDITY_ADDONS.md): h26's features, x2's R1 / R5 rules, the judgement. */
class LiquidityAddonsTest {
    private fun q(cc: Double?, co: Double?, pc: Double?, po: Double?) = Quote(Leg(cc, co), Leg(pc, po))
    private val step = 100
    private val atm = 50_000
    private val before = (-2..2).associate { atm + it * step to q(100.0, 1000.0, 100.0, 1000.0) }
    private val now = (-2..2).associate { atm + it * step to q(110.0, 1200.0, 90.0, 1100.0) }

    @Test fun offTakesEverything() {
        val p = LiquidityAddonParams.OFF
        assertFalse(p.oiVeto || p.callsNeedOi)
        for (side in listOf(1, -1)) for (z in listOf(null, -5.0, 0.0, 5.0))
            assertEquals(LiquidityAddons.Verdict(true, "addon_take"), LiquidityAddons.decide(p, side, z, z))
    }

    @Test fun buildUpByHand() {
        // each strike: +1 x 200 (call up, OI +200) - (-1) x 100 (put down, OI +100) = 300; over (1200 + 1100) x 5
        assertEquals(1500.0 / 11500.0, LiquidityAddons.bu15(now, before, atm, step)!!, 1e-12)
        // dOIpc: (100 - 200) a strike; the band's other strikes are missing and left out
        assertEquals(-500.0 / 11500.0, LiquidityAddons.doiPc(now, before, atm, step)!!, 1e-12)
        assertEquals(-500.0 / 11500.0, LiquidityAddons.doiPc(now, before, atm, step, band = 2)!!, 1e-12)
    }

    @Test fun missingFiguresFollowNansum() {
        val n2 = now + (atm to q(null, 1200.0, 90.0, 1100.0))           // no call close now: out of the sum, still in the total
        assertEquals(1200.0 / 11500.0, LiquidityAddons.bu15(n2, before, atm, step)!!, 1e-12)
        val n3 = now + (atm to q(110.0, null, 90.0, 1100.0))            // no call OI now: out of both
        assertEquals(1200.0 / 9200.0, LiquidityAddons.bu15(n3, before, atm, step)!!, 1e-12)
        assertEquals(-400.0 / 9200.0, LiquidityAddons.doiPc(n3, before, atm, step)!!, 1e-12)
        val b2 = before - atm                                           // strike not seen 15 minutes ago
        assertEquals(1200.0 / 11500.0, LiquidityAddons.bu15(now, b2, atm, step)!!, 1e-12)
        assertEquals(-400.0 / 11500.0, LiquidityAddons.doiPc(now, b2, atm, step)!!, 1e-12)
        val flat = before.mapValues { q(100.0, 1000.0, 100.0, 1000.0) } // no change: zero, not null
        assertEquals(0.0, LiquidityAddons.bu15(flat, before, atm, step)!!, 0.0)
        assertNull(LiquidityAddons.bu15(emptyMap(), before, atm, step))
        assertNull(LiquidityAddons.doiPc(emptyMap(), before, atm, step))
        val zeroOi = now.mapValues { q(110.0, 0.0, 90.0, 0.0) }
        assertNull(LiquidityAddons.bu15(zeroOi, before, atm, step))
        assertNull(LiquidityAddons.doiPc(zeroOi, before, atm, step))
        assertNull(LiquidityAddons.bu15(now.mapValues { q(110.0, 1200.0, 90.0, Double.NaN) }, before, atm, step))
    }

    @Test fun trailingZ() {
        assertNull(LiquidityAddons.moments(listOf(null, Double.NaN)))
        val m = LiquidityAddons.moments(listOf(1.0, null, 3.0))!!
        assertEquals(2.0, m.mean, 1e-12)
        assertEquals(5.0, m.meanSq, 1e-12)
        // 30 sessions: means alternate 0 / 2, mean squares 1 / 5 -> mu 1, sd sqrt(3 - 1)
        val h = (0 until 30).map { if (it % 2 == 0) Moments(0.0, 1.0) else Moments(2.0, 5.0) }
        assertEquals((3.0 - 1.0) / sqrt(2.0), LiquidityAddons.z(3.0, h)!!, 1e-12)
        // only the last 60 count: 100 old sessions far away change nothing
        assertEquals((3.0 - 1.0) / sqrt(2.0), LiquidityAddons.z(3.0, List(100) { Moments(50.0, 2600.0) } + h + h)!!, 1e-12)
        assertNull(LiquidityAddons.z(3.0, h.take(19) + List(5) { null }))  // under 20 known sessions
        assertNull(LiquidityAddons.z(null, h))
        assertNull(LiquidityAddons.z(Double.NaN, h))
        assertNull(LiquidityAddons.z(1.0, List(30) { Moments(1.0, 1.0) }))  // no spread
    }

    @Test fun oiVeto() {
        val p = LiquidityAddonParams(oiVeto = true)
        assertFalse(LiquidityAddons.decide(p, 1, -1.0, null).take)       // call against a build-up z of -1: skipped
        assertFalse(LiquidityAddons.decide(p, -1, 1.2, 0.0).take)        // put against +1.2: signed -1.2
        assertFalse(LiquidityAddons.decide(p, 1, 0.0, -1.5).take)        // dOIpc5 against
        assertEquals("addon_oi_veto", LiquidityAddons.decide(p, -1, null, 2.0).why)
        assertTrue(LiquidityAddons.decide(p, 1, -0.99, -0.5).take)
        assertTrue(LiquidityAddons.decide(p, 1, null, null).take)        // unknown never skips
        assertTrue(LiquidityAddons.decide(p, -1, -3.0, -3.0).take)       // agreeing
    }

    @Test fun callsNeedOi() {
        val p = LiquidityAddonParams(callsNeedOi = true)
        assertTrue(LiquidityAddons.decide(p, -1, null, null).take)       // puts always
        assertTrue(LiquidityAddons.decide(p, -1, 5.0, 5.0).take)
        assertTrue(LiquidityAddons.decide(p, 1, 1.0, null).take)
        assertEquals("addon_call_needs_oi", LiquidityAddons.decide(p, 1, 0.99, null).why)
        assertFalse(LiquidityAddons.decide(p, 1, null, 3.0).take)        // unknown: not taken (x2 as run)
        val both = LiquidityAddonParams(oiVeto = true, callsNeedOi = true)
        assertEquals("addon_oi_veto", LiquidityAddons.decide(both, 1, 2.0, -1.0).why)
        assertTrue(LiquidityAddons.decide(both, 1, 2.0, 0.0).take)
    }

    @Test fun judge() {
        assertFailsWith<IllegalArgumentException> { LiquidityAddons.judge(listOf(1.0), emptyList()) }
        val none = LiquidityAddons.judge(emptyList(), emptyList())
        assertEquals(0, none.trades); assertEquals(1.0, none.p); assertFalse(none.enough)
        // 300 trades: the 60 skipped all lose 1,000, the rest make 100 -> saving 200 a trade, random skips rarely match it
        val nets = List(300) { if (it % 5 == 0) -1000.0 else 100.0 }
        val skip = List(300) { it % 5 == 0 }
        val j = LiquidityAddons.judge(nets, skip, draws = 2000)
        assertEquals(300, j.trades); assertEquals(60, j.skipped); assertTrue(j.enough)
        assertEquals(200.0, j.savedPerTrade, 1e-9)
        assertTrue(j.p < 0.001)
        assertEquals(j, LiquidityAddons.judge(nets, skip, draws = 2000))  // seeded: repeatable
        // skipping the winners instead: no saving, p near 1
        val bad = LiquidityAddons.judge(nets, skip.map { !it }, draws = 2000)
        assertTrue(bad.savedPerTrade < 0 && bad.p > 0.99)
        // all or nothing skipped: no test
        assertEquals(1.0, LiquidityAddons.judge(nets, List(300) { false }).p)
        val all = LiquidityAddons.judge(listOf(-1.0, -2.0), listOf(true, true))
        assertEquals(1.5, all.savedPerTrade, 1e-12); assertEquals(1.0, all.p)
    }

    @Test fun holm() {
        assertEquals(listOf(0.03, 0.04, 0.04), LiquidityAddons.holm(listOf(0.01, 0.04, 0.02)).map { Math.round(it * 1e6) / 1e6 })
        assertEquals(listOf(1.0, 1.0), LiquidityAddons.holm(listOf(0.6, 0.9)))
        assertEquals(emptyList(), LiquidityAddons.holm(emptyList()))
    }
}
