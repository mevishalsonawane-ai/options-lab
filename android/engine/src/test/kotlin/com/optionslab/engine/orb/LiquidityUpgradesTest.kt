package com.optionslab.engine.orb

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for Liquidity 15+5 UP-3 upgrades:
 *   - Ex-BANKNIFTY universe, opposing-wick absorption gate (< 0.35), causal straddle expansion (> 0),
 *     optional R140 wide-day skip, extended NIFTY/SENSEX stop/strike steps, and HTF-first tie-breaking.
 */
class LiquidityUpgradesTest {
    private val t0 = LocalDateTime.of(2026, 10, 19, 10, 15)
    private fun bar(o: Double, h: Double, l: Double, c: Double) = Bar(t0, o, h, l, c)

    @Test
    fun opposingWickAndAbsorptionGateHandleCleanAbsorbedFlatAndNaN() {
        val cleanBull = bar(100.0, 120.0, 100.0, 118.0)
        assertEquals(0.10, LiquidityRules.opposingWick(cleanBull, +1), 1e-9)
        assertTrue(LiquidityRules.passesAbsorptionGate(cleanBull, +1))

        val absorbedBull = bar(100.0, 120.0, 100.0, 111.0)
        assertEquals(0.45, LiquidityRules.opposingWick(absorbedBull, +1), 1e-9)
        assertFalse(LiquidityRules.passesAbsorptionGate(absorbedBull, +1))

        val cleanBear = bar(120.0, 120.0, 100.0, 102.0)
        assertTrue(LiquidityRules.passesAbsorptionGate(cleanBear, -1))

        val absorbedBear = bar(120.0, 120.0, 100.0, 108.0)
        assertFalse(LiquidityRules.passesAbsorptionGate(absorbedBear, -1))

        val flat = bar(100.0, 100.0, 100.0, 100.0)
        assertEquals(0.0, LiquidityRules.opposingWick(flat, +1), 1e-9)

        val nanBar = bar(Double.NaN, 120.0, 100.0, 118.0)
        assertEquals(0.0, LiquidityRules.opposingWick(nanBar, +1), 1e-9)
    }

    @Test
    fun passesUp3GateEnforcesExBankniftyAbsorptionStraddleAndOptionalWideDay() {
        val cleanBull = bar(100.0, 120.0, 100.0, 118.0)
        val absorbedBull = bar(100.0, 120.0, 100.0, 111.0)

        assertTrue(LiquidityRules.passesUp3Gate("MIDCPNIFTY", cleanBull, +1, 0.04))
        assertTrue(LiquidityRules.passesUp3Gate("NIFTY", cleanBull, +1, 0.02))
        assertTrue(LiquidityRules.passesUp3Gate("SENSEX", cleanBull, +1, 0.05))
        assertFalse(LiquidityRules.passesUp3Gate("BANKNIFTY", cleanBull, +1, 0.05))
        assertFalse(LiquidityRules.passesUp3Gate("NIFTY", absorbedBull, +1, 0.05))
        assertFalse(LiquidityRules.passesUp3Gate("NIFTY", cleanBull, +1, -0.01))
        assertFalse(LiquidityRules.passesUp3Gate("NIFTY", cleanBull, +1, null))
        assertFalse(LiquidityRules.passesUp3Gate("NIFTY", cleanBull, +1, Double.NaN))
        assertTrue(LiquidityRules.passesUp3Gate("NIFTY", cleanBull, +1, null, requireStraddle = false))
        assertFalse(LiquidityRules.passesUp3Gate("NIFTY", cleanBull, +1, 0.04, wideRatio = 1.40, applyWideDaySkip = true))
        assertTrue(LiquidityRules.passesUp3Gate("NIFTY", cleanBull, +1, 0.04, wideRatio = 1.10, applyWideDaySkip = true))
    }

    @Test
    fun rankCandidatesPrioritizesHigherTimeframesAndExtendedHelpersMatchSpecs() {
        val b = bar(100.0, 110.0, 99.0, 109.0)
        val sig = LiquidityRules.Signal(+1, 105.0, null)
        val c5 = LiquidityRules.Candidate(LiquidityRules.MID5, "MIDCPNIFTY", 5, sig, b)
        val c15 = LiquidityRules.Candidate(LiquidityRules.MID15, "MIDCPNIFTY", 15, sig, b)
        val c30 = LiquidityRules.Candidate(LiquidityRules.FIN30, "FINNIFTY", 30, sig, b)

        val ranked = LiquidityRules.rankCandidates(listOf(c5, c15, c30))
        assertEquals(listOf(30, 15, 5), ranked.map { it.minutes })

        assertEquals(50, LiquidityRules.strikeStepEx("NIFTY"))
        assertEquals(100, LiquidityRules.strikeStepEx("SENSEX"))
        assertEquals(25, LiquidityRules.strikeStepEx("MIDCPNIFTY"))
        assertEquals(15.0, LiquidityRules.indexStopPointsEx("NIFTY"))
        assertEquals(50.0, LiquidityRules.indexStopPointsEx("SENSEX"))
        assertEquals(8.0, LiquidityRules.indexStopPointsEx("MIDCPNIFTY"))
        assertEquals("NIFTY", LiquidityRules.underlyingOf(LiquidityRules.NIFTY15))
        assertEquals("NIFTY", LiquidityRules.underlyingOf(LiquidityRules.NIFTY5))
        assertEquals("SENSEX", LiquidityRules.underlyingOf(LiquidityRules.SENSEX15))
        assertEquals("SENSEX", LiquidityRules.underlyingOf(LiquidityRules.SENSEX5))
        assertEquals("BANKNIFTY", LiquidityRules.underlyingOf(LiquidityRules.ARM15))
    }
    
    @Test
    fun parkinsonFeynmanKacEstimatorAndGateVerifyRatioAndEdgeCases() {
        val activeBars = (0 until 30).map { i -> Bar(t0.plusMinutes(i.toLong()), 100.0, 100.10, 100.0, 100.05) }
        val quietBars = (0 until 30).map { i -> Bar(t0.plusMinutes(i.toLong()), 100.0, 100.03, 100.0, 100.01) }

        val rvActive = LiquidityRules.parkinsonRvPct(activeBars)
        val rvQuiet = LiquidityRules.parkinsonRvPct(quietBars)
        assertTrue(rvActive != null && rvActive > 15.0)
        assertTrue(rvQuiet != null && rvQuiet < 7.0)
        assertTrue(LiquidityRules.passesFeynmanKacParkinsonGate(rvActive, 15.0))
        assertFalse(LiquidityRules.passesFeynmanKacParkinsonGate(rvQuiet, 15.0))

        assertEquals(null, LiquidityRules.parkinsonRvPct(activeBars.take(10)))
        assertFalse(LiquidityRules.passesFeynmanKacParkinsonGate(rvActive, Double.NaN))
        assertFalse(LiquidityRules.passesFeynmanKacParkinsonGate(rvActive, 0.0))

        val cleanBull = bar(100.0, 120.0, 100.0, 118.0)
        assertTrue(LiquidityRules.passesUp3Gate("NIFTY", cleanBull, +1, 0.04, fkParkinsonRatio = 0.65, requireFkParkinson = true))
        assertFalse(LiquidityRules.passesUp3Gate("NIFTY", cleanBull, +1, 0.04, fkParkinsonRatio = 0.55, requireFkParkinson = true))
        assertFalse(LiquidityRules.passesUp3Gate("NIFTY", cleanBull, +1, 0.04, fkParkinsonRatio = null, requireFkParkinson = true))
        // Branch coverage for empty, invalid window, multi-day/invalid bars, and NaN/negative inputs
        assertEquals(null, LiquidityRules.parkinsonRvPct(emptyList()))
        assertEquals(null, LiquidityRules.parkinsonRvPct(activeBars, window = 0))
        val mixedBars = listOf(
            Bar(t0.minusDays(1), 100.0, 101.0, 99.0, 100.0),
            Bar(t0, 100.0, Double.NaN, 100.0, 100.0),
            Bar(t0.plusMinutes(1), 100.0, 100.1, Double.NaN, 100.0),
            Bar(t0.plusMinutes(2), 100.0, 100.1, 0.0, 100.0),
            Bar(t0.plusMinutes(3), 100.0, 99.0, 100.0, 100.0),
        ) + activeBars
        assertTrue(LiquidityRules.parkinsonRvPct(mixedBars) != null)
        assertEquals(null, LiquidityRules.feynmanKacParkinsonRatio(null, 15.0))
        assertEquals(null, LiquidityRules.feynmanKacParkinsonRatio(10.0, null))
        assertEquals(null, LiquidityRules.feynmanKacParkinsonRatio(Double.NaN, 15.0))
        assertEquals(null, LiquidityRules.feynmanKacParkinsonRatio(-1.0, 15.0))
        assertFalse(LiquidityRules.passesUp3Gate("NIFTY", cleanBull, +1, 0.04, fkParkinsonRatio = Double.NaN, requireFkParkinson = true))
    }
}
