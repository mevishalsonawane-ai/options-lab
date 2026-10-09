package com.optionslab.ira

import com.optionslab.engine.KiteTicks
import com.optionslab.engine.options.ChainRow
import com.optionslab.engine.options.ChainSnapshot
import com.optionslab.engine.options.OptLeg
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The gamma regime ([GammaRegime]), the tape and the heatmap ([TapeHeat]), and the shadow log's context fields ([FlowShadow]). */
class GammaTapeTest {
    private fun s(k: Double, ce: Long, pe: Long, iv: Double? = 0.2) = GammaRegime.Strike(k, ce, pe, iv, iv)

    @Test fun blackScholesGamma() {
        // At the money, a year, 20%: φ(0.1) / (100 × 0.2) = 0.019848.
        assertEquals(0.0198476, GammaRegime.gamma(100.0, 100.0, 1.0, 0.2), 1e-6)
        assertEquals(0.0, GammaRegime.gamma(0.0, 100.0, 1.0, 0.2))
        assertEquals(0.0, GammaRegime.gamma(100.0, 100.0, 0.0, 0.2))
        assertEquals(0.0, GammaRegime.gamma(100.0, 100.0, 1.0, 0.0))
        assertEquals(0.0, GammaRegime.gamma(100.0, -1.0, 1.0, 0.2))
    }

    @Test fun zeroGammaSitsBetweenThePutWallAndTheCallWallAndTheConventionOnlyFlipsTheSign() {
        val chain = listOf(s(100.0, 0, 1000), s(110.0, 1000, 0))
        val t = 0.05
        // Standard: the puts' gamma dominates below, the calls' above.
        assertTrue(GammaRegime.netAt(98.0, chain, t, 1, 0.2) < 0)
        assertTrue(GammaRegime.netAt(112.0, chain, t, 1, 0.2) > 0)
        val zg = GammaRegime.zeroGamma(chain, 105.0, t, 1, 0.2)!!
        assertTrue(zg in 100.0..110.0, "$zg")
        assertTrue(abs(GammaRegime.netAt(zg, chain, t, 1, 0.2)) < 1.0, "${GammaRegime.netAt(zg, chain, t, 1, 0.2)}")
        val r = GammaRegime.compute("NIFTY", 108.0, 108.5, t, 50, chain, 1_000)!!
        assertEquals(zg, r.zeroGamma!!, 0.05)
        assertEquals(GammaRegime.Sign.POSITIVE, r.sign(GammaRegime.Convention.STANDARD))
        assertEquals(GammaRegime.Sign.NEGATIVE, r.sign(GammaRegime.Convention.INDIA_SELLERS))
        assertEquals(-r.netStd, r.net(GammaRegime.Convention.INDIA_SELLERS))
        assertEquals(108.0 - r.zeroGamma!!, r.distance()!!, 1e-9)
        assertEquals(108.5, r.forward)
        // The lot scales the net, not the level.
        assertEquals(50 * GammaRegime.netAt(108.0, chain, t, 1, 0.2), r.netStd, 1e-6)
        // Only calls: never crosses; a level exactly on a step is found too.
        assertNull(GammaRegime.zeroGamma(listOf(s(100.0, 10, 0)), 100.0, t, 1, 0.2))
        assertNull(GammaRegime.zeroGamma(chain, 0.0, t, 1, 0.2))
        assertNull(GammaRegime.compute("NIFTY", 108.0, 0.0, t, 1, listOf(s(100.0, 10, 0)), 0)!!.zeroGamma)
        assertEquals(108.0, GammaRegime.compute("NIFTY", 108.0, 0.0, t, 1, listOf(s(100.0, 10, 0)), 0)!!.forward)
    }

    @Test fun nothingToComputeWithoutTimeSpotOiOrVol() {
        val chain = listOf(s(100.0, 10, 10))
        assertNull(GammaRegime.compute("X", 0.0, 0.0, 0.1, 1, chain, 0))
        assertNull(GammaRegime.compute("X", 100.0, 100.0, 0.0, 1, chain, 0))
        assertNull(GammaRegime.compute("X", 100.0, 100.0, 0.1, 1, listOf(s(100.0, 0, 0)), 0))
        assertNull(GammaRegime.compute("X", 100.0, 100.0, 0.1, 1, listOf(s(100.0, 10, 10, null)), 0))
        // One side's vol missing: the other side's, then the chain's median.
        val mixed = listOf(GammaRegime.Strike(100.0, 10, 10, null, 0.3), GammaRegime.Strike(105.0, 10, 0, null, null))
        assertNotNull(GammaRegime.compute("X", 100.0, 100.0, 0.1, 1, mixed, 0))
    }

    @Test fun theRegimeFromAPricedChain() {
        val now = ZonedDateTime.of(2026, 10, 9, 11, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        val rows = (0..8).map { i ->
            val k = 24_600.0 + i * 100
            ChainRow(k, OptLeg("NIFTY$k CE", maxOf(25_000 - k, 0.0) + 120, oi = if (k > 25_000) 900_000 else 100_000, lotSize = 75),
                OptLeg("NIFTY$k PE", maxOf(k - 25_000, 0.0) + 120, oi = if (k < 25_000) 900_000 else 100_000, lotSize = 75))
        }
        val c = ChainSnapshot.of("NIFTY", now.toLocalDate().plusDays(5), 25_000.0, 75, rows, now)
        val r = GammaRegime.of(c, 5_000)!!
        assertEquals("NIFTY", r.underlying); assertEquals(25_000.0, r.spot); assertEquals(5_000L, r.atMs)
        assertNotNull(r.zeroGamma)
    }

    @Test fun theWords() {
        val r = GammaRegime.Result("BANKNIFTY", 0, 52_500.0, 52_600.0, 1e6, 52_290.0)
        assertEquals("Gamma: positive (choppy) · zero-γ 52,290 (spot 0.4% above)", GammaRegime.line(r, GammaRegime.Convention.STANDARD))
        assertEquals("Gamma: negative (trending) · zero-γ 52,290 (spot 0.4% above)", GammaRegime.line(r, GammaRegime.Convention.INDIA_SELLERS))
        assertEquals("Gamma: positive (choppy) · zero-γ 52,710 (spot 0.4% below)", GammaRegime.line(r.copy(zeroGamma = 52_710.0), GammaRegime.Convention.STANDARD))
        assertEquals("Gamma: positive (choppy) · no zero-γ within ±5%", GammaRegime.line(r.copy(zeroGamma = null), GammaRegime.Convention.STANDARD))
        assertEquals("Gamma: not known yet", GammaRegime.line(null, GammaRegime.Convention.STANDARD))
        val a = GammaRegime.answer("BANKNIFTY", r, GammaRegime.Convention.STANDARD, 120_000)
        assertTrue("positive (choppy)" in a && "india sellers one it reads negative (trending)" in a && GammaRegime.NOTE in a && "2 minutes ago" in a, a)
        assertTrue("1 minute ago" in GammaRegime.answer("BANKNIFTY", r, GammaRegime.Convention.INDIA_SELLERS, 60_000))
        assertTrue("no gamma regime" in GammaRegime.answer("BANKNIFTY", null, GammaRegime.Convention.STANDARD, 0))
    }

    // ---- the tape and the heatmap ----

    @Test fun theTapeKeepsTheNewestAndFlagsBigPrints() {
        assertTrue(TapeHeat.big(51, 10.0)); assertFalse(TapeHeat.big(50, 10.0)); assertFalse(TapeHeat.big(500, null)); assertFalse(TapeHeat.big(5, 0.0))
        val t = TapeHeat.Tape(3)
        for (i in 1..5) t.add(TapeHeat.Print(i * 1000L, 100.0 + i, i.toLong(), 1, false))
        assertEquals(listOf(5L, 4L, 3L), t.latest().map { it.qty })
        assertEquals(listOf(5L), t.latest(1).map { it.qty })
    }

    @Test fun theHeatmapDownsamplesAndForgets() {
        val h = TapeHeat.Heatmap(seconds = 10)
        val lv = { p: Double, q: Long -> KiteTicks.Level(p, q, 1) }
        for (s in 0L until 12L) h.snap(s, listOf(lv(99.0, 10 + s), lv(98.95, 5)), listOf(lv(99.05, 20), lv(0.0, 0)))
        h.dot(TapeHeat.Dot(1, 99.0, 50, TapeHeat.DotKind.PULL_BID))
        h.dot(TapeHeat.Dot(11, 99.05, 500, TapeHeat.DotKind.BIG_BUY))     // second 1 is now 10 s back: forgotten
        val f = h.frame(11, maxCols = 5)
        assertEquals(5, f.columns.size)                                 // seconds 2..11 in columns of 2
        assertEquals(listOf(2L, 4L, 6L, 8L, 10L), f.columns.map { it.sec })
        assertEquals(21L, f.columns.last().bids[99.0])                  // the larger of 20 and 21
        assertEquals(20L, f.columns.first().asks[99.05])
        assertEquals(21L, f.maxQty); assertEquals(98.95, f.low); assertEquals(99.05, f.high)
        assertEquals(listOf(TapeHeat.DotKind.BIG_BUY), f.dots.map { it.kind })
        val empty = TapeHeat.Heatmap(seconds = 5).frame(100)
        assertTrue(empty.columns.isEmpty()); assertEquals(0.0, empty.low)
    }

    // ---- the shadow log's context ----

    @Test fun theSignalLineCarriesTheContextFieldsAndTheyReadBack() {
        val prior = Auction.Levels(27.5, 52_300.0, 52_400.0, 52_200.0, emptyList(), emptyList(), 1.0, 52_500.0, 52_100.0)
        val tpo = MarketProfile.Read(52_450.0, 52_250.0, true, MarketProfile.OpenType.OPEN_DRIVE, 1, MarketProfile.DayType.TREND, emptyList(), false, false, 3)
        val snap = Auction.Snapshot("BANKNIFTY", 100, 52_500.0, 27.5, null, prior, Auction.Regime.ABOVE_TESTING, 1200, -340,
            Auction.Divergence.BEARISH, emptyList(), emptyList(), Auction.Vwap(52_400.0, 50.0, null, null), tpo, 0, false)
        val read = OrderFlow.Read("BANKNIFTY", 100, OrderFlow.Side.BUYERS, 60, 60, true, 52_510.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, null, emptyMap(), absorbAt = 52_520.0, absorbBy = -1)
        val g = GammaRegime.Result("BANKNIFTY", 0, 52_400.0, 52_480.0, -5.0, 52_300.0)
        val ctx = FlowShadow.Context(null, snap, read, g, GammaRegime.Convention.STANDARD, basis = 100.0)
        val line = FlowShadow.signal("id1", 100, "orb", "BANKNIFTY", 1, false, OrderFlow.Mode.SHADOW, 55, OrderFlow.Agreement.AGREES, false, read, ctx = ctx)
        val f = line.split('|')
        assertEquals(FlowShadow.CONTEXT_FROM + FlowShadow.CONTEXT_FIELDS.size, f.size)
        val c = FlowShadow.CONTEXT_FIELDS.withIndex().associate { (i, k) -> k to f[FlowShadow.CONTEXT_FROM + i] }
        assertEquals("210.0000", c["prior_poc_dist"]); assertEquals("110.0000", c["prior_vah_dist"]); assertEquals("310.0000", c["prior_val_dist"])
        assertEquals("ABOVE_TESTING", c["regime"]); assertEquals("-340", c["delta15"]); assertEquals("BEARISH", c["divergence"])
        assertEquals("absorption at 52,520 (sellers)", c["absorption"])
        assertEquals("-1", c["gex_sign"]); assertEquals("STANDARD", c["gex_convention"])
        assertEquals("110.0000", c["zero_gamma_dist"])                 // 52,510 − 100 basis − 52,300
        assertEquals("2.2000", c["vwap_sd"]); assertEquals("above", c["vwap_side"])
        assertEquals("200.0000", c["ib_range"]); assertEquals("OPEN_DRIVE", c["open_type"]); assertEquals("TREND", c["day_type"])
        // Read back with the signal; an older line without them has none.
        val sig = FlowShadow.parse(sequenceOf(line)).single()
        assertEquals(c.filterValues { it.isNotEmpty() }, sig.context)
        assertTrue(FlowShadow.parse(sequenceOf(f.take(FlowShadow.CONTEXT_FROM).joinToString("|"))).single().context.isEmpty())
        // No context: the fields are there, empty; the chain's own basis when none is given; the India sign flips.
        assertTrue(FlowShadow.context(null).all { it.isEmpty() } && FlowShadow.context(null).size == FlowShadow.CONTEXT_FIELDS.size)
        val india = FlowShadow.context(FlowShadow.Context(52_400.0, null, null, g, GammaRegime.Convention.INDIA_SELLERS))
        assertEquals("1", india[7]); assertEquals("20.0000", india[9])   // 52,400 − 80 − 52,300
        assertTrue(FlowShadow.context(FlowShadow.Context(null, null, null, null)).all { it.isEmpty() })
    }
}
