package com.optionslab.engine.orb

import java.time.LocalDate
import kotlin.test.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the Nov 2026 verified options-buying upgrades:
 *   1. BreadthDonchianArm opposing-wick absorption gate (oppWick < 0.35), Ex-BANKNIFTY execution,
 *      straddle_vs_0920 > 0 gate, breakStrength >= 0.50 ATR, and highest-breakStrength tie-breaking.
 *   2. NiftyExpiryD6Arm (0DTE Round-100 S/R + Max-OI wall + live Delta surge + Volume + OI unwind).
 */
class R72UpgradesAndD6Test {
    private val prev = LocalDate.of(2026, 10, 16)
    private val day = LocalDate.of(2026, 10, 19)
    private fun b5(d: LocalDate, b: Int, o: Double, h: Double, l: Double, c: Double) = PassedArms.Bar5(d, b, o, h, l, c)

    @Test
    fun opposingWickBlocksAbsorbedBreakoutAndAllowsCleanClose() {
        val cleanBull = b5(day, 5, 100.0, 120.0, 100.0, 118.0)
        assertEquals(0.10, BreadthDonchianArm.opposingWick(cleanBull, +1), 1e-9)
        assertTrue(BreadthDonchianArm.passesAbsorptionGate(cleanBull, +1))

        val absorbedBull = b5(day, 5, 100.0, 120.0, 100.0, 111.0)
        assertEquals(0.45, BreadthDonchianArm.opposingWick(absorbedBull, +1), 1e-9)
        assertFalse(BreadthDonchianArm.passesAbsorptionGate(absorbedBull, +1))

        val cleanBear = b5(day, 5, 120.0, 120.0, 100.0, 102.0)
        assertTrue(BreadthDonchianArm.passesAbsorptionGate(cleanBear, -1))

        val absorbedBear = b5(day, 5, 120.0, 120.0, 100.0, 108.0)
        assertFalse(BreadthDonchianArm.passesAbsorptionGate(absorbedBear, -1))

        val flat = b5(day, 5, 100.0, 100.0, 100.0, 100.0)
        assertEquals(0.0, BreadthDonchianArm.opposingWick(flat, +1), 1e-9)
    }

    @Test
    fun filteredSignalsKeepsBankniftyInVoteExcludesFromExecAndSortsByBreakStrength() {
        fun baseSeries(base: Double, breakClose: Double, breakHigh: Double): List<PassedArms.Bar5> =
            (55 until 75).map { b5(prev, it, base, base + 2.0, base - 2.0, base) } +
                listOf(b5(day, 1, base, breakHigh, base - 1.0, breakClose))

        val series = mapOf(
            "NIFTY" to baseSeries(24000.0, 24005.0, 24006.0),
            "BANKNIFTY" to baseSeries(52000.0, 52010.0, 52011.0),
            "FINNIFTY" to baseSeries(23000.0, 23005.0, 23015.0),
            "MIDCPNIFTY" to baseSeries(12000.0, 12000.0, 12001.0),
            "SENSEX" to baseSeries(80000.0, 80012.0, 80013.0),
        )
        val straddle = mapOf(
            ("NIFTY" to 1) to 0.04,
            ("FINNIFTY" to 1) to 0.05,
            ("SENSEX" to 1) to 0.06,
        )

        val sigs = BreadthDonchianArm.filteredSignals(series, day, straddleVs0920ByBar = straddle)
        assertEquals(listOf("SENSEX", "NIFTY"), sigs.map { it.underlying })
        assertTrue(sigs.all { it.side == +1 })

        val negStraddle = straddle + (("SENSEX" to 1) to -0.02)
        val sigs2 = BreadthDonchianArm.filteredSignals(series, day, straddleVs0920ByBar = negStraddle)
        assertEquals(listOf("NIFTY"), sigs2.map { it.underlying })
    }

    @Test
    fun niftyExpiryD6FiresOnlyOnExpiryDayWithFullConfluence() {
        val barUp = b5(day, 10, 24492.0, 24514.0, 24490.0, 24510.0)
        val chainBull = NiftyExpiryD6Arm.ChainSnapshot(
            atmIvPct = 14.5,
            ceEntryPrice = 42.0,
            peEntryPrice = 36.0,
            maxOiCallDistPct = 0.18,
            maxOiPutDistPct = -0.65,
            volRatio15 = 1.25,
            volRatio15RollingMean = 1.00,
            oiCeAtmChg30Pct = -4.5,
            oiPeAtmChg30Pct = 6.0,
            pcrOiChg30 = 0.05,
        )
        assertEquals(+1, NiftyExpiryD6Arm.sideAt(true, barUp, 24495.0, +0.05, chainBull))
        assertEquals(0, NiftyExpiryD6Arm.sideAt(false, barUp, 24495.0, +0.05, chainBull))
        assertEquals(0, NiftyExpiryD6Arm.sideAt(true, barUp, 24495.0, +0.01, chainBull))
        val dCe = NiftyExpiryD6Arm.liveCallDelta(24510.0, 42.0, 36.0, 14.5, 60)
        assertTrue(dCe.isFinite() && dCe in 0.50..0.75)
        val dFallback = NiftyExpiryD6Arm.liveCallDelta(24510.0, 42.0, 36.0, Double.NaN, 60)
        assertTrue(dFallback.isFinite() && dFallback in 0.50..0.75)
        assertTrue(NiftyExpiryD6Arm.liveCallDelta(Double.NaN, 42.0, 36.0, 14.5, 60).isNaN())
    }

@Test
fun directionalSemiVolFeynmanKacEstimatorAndGateVerifyBothSidesAndFilteredSignals() {
    val t0 = LocalDateTime.of(2026, 10, 19, 9, 15)
val upBars = (0..30).map { i ->
val px = 100.0 * Math.pow(1.0005, i.toDouble())
Bar(t0.plusMinutes(i.toLong()), px, px, px, px)
}
        val svUp = BreadthDonchianArm.directionalSemiVolPct(upBars, +1)
        val svUpWrongSide = BreadthDonchianArm.directionalSemiVolPct(upBars, -1)
        assertTrue(svUp != null && svUp > 20.0)
        assertEquals(0.0, svUpWrongSide ?: -1.0, 1e-9)
        assertTrue(BreadthDonchianArm.passesFeynmanKacSemiVolGate(svUp, 15.0))
        assertFalse(BreadthDonchianArm.passesFeynmanKacSemiVolGate(svUpWrongSide, 15.0))

        val dnBars = (0..30).map { i ->
            val px = 100.0 * Math.pow(0.9995, i.toDouble())
            Bar(t0.plusMinutes(i.toLong()), px, px, px, px)
        }
        val svDn = BreadthDonchianArm.directionalSemiVolPct(dnBars, -1)
        assertTrue(BreadthDonchianArm.passesFeynmanKacSemiVolGate(svDn, 15.0))
        assertFalse(BreadthDonchianArm.passesFeynmanKacSemiVolGate(BreadthDonchianArm.directionalSemiVolPct(dnBars, +1), 15.0))

        assertEquals(null, BreadthDonchianArm.directionalSemiVolPct(upBars.take(10), +1))
        assertFalse(BreadthDonchianArm.passesFeynmanKacSemiVolGate(svUp, Double.NaN))
        assertFalse(BreadthDonchianArm.passesFeynmanKacSemiVolGate(svUp, 0.0))

        fun baseSeries(base: Double, breakClose: Double, breakHigh: Double): List<PassedArms.Bar5> =
            (55 until 75).map { b5(prev, it, base, base + 2.0, base - 2.0, base) } +
                listOf(b5(day, 1, base, breakHigh, base - 1.0, breakClose))
        val series = mapOf(
            "NIFTY" to baseSeries(24000.0, 24005.0, 24006.0),
            "BANKNIFTY" to baseSeries(52000.0, 52010.0, 52011.0),
            "FINNIFTY" to baseSeries(23000.0, 23005.0, 23006.0),
            "MIDCPNIFTY" to baseSeries(12000.0, 12000.0, 12001.0),
            "SENSEX" to baseSeries(80000.0, 80012.0, 80013.0),
        )
        val fkMap = mapOf(("SENSEX" to 1) to 0.85, ("NIFTY" to 1) to 0.55, ("FINNIFTY" to 1) to Double.NaN)
        val filtered = BreadthDonchianArm.filteredSignals(series, day, fkSemiVolRatioByBar = fkMap)
        assertEquals(listOf("SENSEX"), filtered.map { it.underlying })
}
}
