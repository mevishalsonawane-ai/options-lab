package com.optionslab.engine.options

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.jupiter.api.Disabled as Ignore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Black-76, implied volatility and IraAlgo's leg rules at their edges. */
class OptionMathCoverageTest {
    private fun near(a: Double, b: Double, tol: Double) = assertTrue(abs(a - b) <= tol, "$a vs $b")

    @Test fun `normal cdf across its three regimes and symmetry`() {
        assertEquals(0.5, OptionMath.normCdf(0.0))
        near(OptionMath.normCdf(1.0), 0.8413447460685429, 1e-15)
        near(OptionMath.normCdf(-1.96), 0.024997895148220435, 1e-15)
        // Tail branch (|x| >= 7.07): ~1e-12 and its complement.
        near(OptionMath.normCdf(-7.5), 3.190891672910919e-14, 1e-20)
        near(OptionMath.normCdf(7.5), 1.0, 1e-13)
        assertEquals(0.0, OptionMath.normCdf(-40.0)); assertEquals(1.0, OptionMath.normCdf(40.0))
        for (x in listOf(-3.0, -0.3, 0.7, 2.5, 8.0)) near(OptionMath.normCdf(x) + OptionMath.normCdf(-x), 1.0, 1e-15)
        near(OptionMath.normPdf(0.0), 0.3989422804014327, 1e-16)
        assertTrue(OptionType.CE.isCall); assertFalse(OptionType.PE.isCall)
    }

    @Test fun `Black-76 put-call parity and greeks signs`() {
        val f = 24000.0; val k = 24200.0; val t = 7 / 365.0; val r = 0.065; val s = 0.14
        val c = OptionMath.price(OptionType.CE, f, k, t, r, s)
        val p = OptionMath.price(OptionType.PE, f, k, t, r, s)
        near(c - p, kotlin.math.exp(-r * t) * (f - k), 1e-8)
        val gc = OptionMath.greeks(OptionType.CE, f, k, t, r, s)
        val gp = OptionMath.greeks(OptionType.PE, f, k, t, r, s)
        near(gc.delta - gp.delta, kotlin.math.exp(-r * t), 1e-12)
        near(gc.gamma, gp.gamma, 1e-15); near(gc.vega, gp.vega, 1e-12)
        assertTrue(gc.theta < 0 && gp.theta < 0); assertTrue(gc.rho < 0 && gp.rho < 0)
        near(gc.rho, -t * c * 0.01, 1e-15)
        // Vega in vol points agrees with a finite difference.
        val bump = (OptionMath.price(OptionType.CE, f, k, t, r, s + 1e-5) - OptionMath.price(OptionType.CE, f, k, t, r, s - 1e-5)) / 2e-5 * 0.01
        near(gc.vega, bump, 1e-6)
    }

    @Test fun `implied volatility refusals and clamps`() {
        val f = 100.0; val t = 0.1
        assertEquals(OptionMath.IvSolve.Failed(OptionMath.IvFailure.BELOW_INTRINSIC), OptionMath.solveIv(9.0, OptionType.CE, f, 90.0, t, 0.0))
        assertEquals(OptionMath.IvSolve.Failed(OptionMath.IvFailure.ABOVE_MAXIMUM), OptionMath.solveIv(101.0, OptionType.CE, f, 90.0, t, 0.0))
        assertEquals(OptionMath.IvSolve.Failed(OptionMath.IvFailure.ABOVE_MAXIMUM), OptionMath.solveIv(90.5, OptionType.PE, f, 90.0, t, 0.0))
        // A put on a zero forward priced at its strike is not invertible.
        assertEquals(OptionMath.IvSolve.Failed(OptionMath.IvFailure.ABOVE_MAXIMUM), OptionMath.solveIv(90.0, OptionType.PE, 0.0, 90.0, t, 0.0))
        // Time at or below zero: opengreeks' bounds.
        assertEquals(OptionMath.IvSolve.Solved(OptionMath.MAX_VOL), OptionMath.solveIv(1.0, OptionType.CE, f, 100.0, 0.0, 0.0))
        assertEquals(OptionMath.IvSolve.Solved(OptionMath.MIN_VOL), OptionMath.solveIv(1.0, OptionType.CE, f, 100.0, -1.0, 0.0))
        assertEquals(OptionMath.IvSolve.Solved(OptionMath.MIN_VOL), OptionMath.solveIv(1.0, OptionType.CE, f, 100.0, Double.NaN, 0.0))
        // Zero price OTM -> the lower bound; a price above the sigma=5 value -> the upper bound.
        assertEquals(OptionMath.IvSolve.Solved(OptionMath.MIN_VOL), OptionMath.solveIv(0.0, OptionType.CE, f, 120.0, t, 0.0))
        assertEquals(OptionMath.IvSolve.Solved(OptionMath.MAX_VOL), OptionMath.solveIv(99.99, OptionType.CE, f, 100.0, t, 0.0))
        assertNull(OptionMath.impliedVol(101.0, OptionType.CE, f, 90.0, t))
    }

    @Test fun `implied volatility inverts the price across moneyness and tenor`() {
        for (k in listOf(60.0, 90.0, 100.0, 110.0, 180.0)) for (t in listOf(0.0005, 0.02, 1.0)) for (sig in listOf(0.05, 0.3, 1.5)) {
            for (type in OptionType.entries) {
                val px = OptionMath.price(type, 100.0, k, t, 0.05, sig)
                val iv = OptionMath.impliedVol(px, type, 100.0, k, t, 0.05) ?: continue
                // Where the price carries information, the solve recovers sigma; elsewhere it recovers the price.
                near(OptionMath.price(type, 100.0, k, t, 0.05, iv), px, 1e-9 * maxOf(1.0, px))
            }
        }
        near(OptionMath.impliedVol(OptionMath.price(OptionType.PE, 24000.0, 23800.0, 3 / 365.0, 0.0, 0.12), OptionType.PE, 24000.0, 23800.0, 3 / 365.0)!!, 0.12, 1e-10)
    }

    @Test fun `leg greeks - refusals, theoretical branches and solved legs`() {
        val t = 7 / 365.0
        assertNull(OptionMath.legGreeks(OptionType.CE, 100.0, 100.0, 0.0, 1.0))
        assertNull(OptionMath.legGreeks(OptionType.CE, 0.0, 100.0, t, 1.0))
        assertNull(OptionMath.legGreeks(OptionType.CE, 100.0, 100.0, t, 0.0))
        assertNull(OptionMath.legGreeks(OptionType.CE, 100.0, 0.0, t, 1.0))
        assertNull(OptionMath.legGreeks(OptionType.CE, 100.0, 90.0, t, 101.0), "above the theoretical maximum")
        val noTv = OptionMath.legGreeks(OptionType.PE, 100.0, 110.0, t, 9.5)!!
        assertTrue(noTv.theoretical); assertEquals(-1.0, noTv.greeks.delta); assertEquals(0.0, noTv.timeValue); assertEquals(10.0, noTv.intrinsic)
        val tiny = OptionMath.legGreeks(OptionType.CE, 110.0, 100.0, t, 10.005)!!
        assertTrue(tiny.theoretical); assertEquals(1.0, tiny.greeks.delta); near(tiny.timeValue, 0.005, 1e-12)
        // A negative rate discounts UP, so a price above intrinsic can still be "below intrinsic" for opengreeks.
        val below = OptionMath.legGreeks(OptionType.CE, 110.0, 100.0, 0.5, 10.5, ratePct = -50.0)!!
        assertTrue(below.theoretical); assertEquals(0.5, below.timeValue, 1e-12)
        val g = OptionMath.legGreeks(OptionType.CE, 24000.0, 24100.0, t, 120.0, ratePct = 6.5)!!
        assertFalse(g.theoretical); assertTrue(g.ivPct in 5.0..40.0); assertEquals(0.0, g.intrinsic)
        val r = OptionMath.legGreeksRounded(OptionType.CE, 24000.0, 24100.0, t, 120.0, 6.5)!!
        assertEquals(pyRound(g.ivPct, 2), r.ivPct); assertEquals(pyRound(g.greeks.gamma, 6), r.greeks.gamma); assertEquals(pyRound(g.greeks.rho, 6), r.greeks.rho)
        val rt = OptionMath.legGreeksRounded(OptionType.PE, 100.0, 110.0, t, 9.5)!!
        assertTrue(rt.theoretical); assertEquals(10.0, rt.intrinsic)
        assertNull(OptionMath.legGreeksRounded(OptionType.PE, 100.0, 110.0, 0.0, 9.5))
    }

    @Ignore("BUG: OptionMath.legGreeks / impliedVol accept a NaN price (or forward): 'price <= 0' is false for NaN, so solveIv bisects to ~MAX_VOL and a leg with no usable quote reports IV ~500% instead of null (chainGreeks correctly returns null for the same input)")
    @Test fun `BUG - a NaN price is not a price`() {
        assertNull(OptionMath.legGreeks(OptionType.CE, 24000.0, 24100.0, 7 / 365.0, Double.NaN))
        assertNull(OptionMath.impliedVol(Double.NaN, OptionType.CE, 24000.0, 24100.0, 7 / 365.0))
        assertNull(OptionMath.legGreeks(OptionType.CE, Double.NaN, 24100.0, 7 / 365.0, 100.0))
    }

    @Test fun `chain greeks - empty, unusable inputs, theoretical ITM and OTM legs`() {
        assertEquals(emptyList<ChainLegGreeks?>() to emptyList(), OptionMath.chainGreeks(emptyList(), emptyList(), emptyList(), 100.0, 0.1))
        assertEquals(listOf(null, null), OptionMath.chainGreeks(listOf(90.0, 100.0), listOf(1.0, 1.0), listOf(1.0, 1.0), 0.0, 0.1).first)
        assertEquals(listOf(null, null), OptionMath.chainGreeks(listOf(90.0, 100.0), listOf(1.0, 1.0), listOf(1.0, 1.0), 100.0, 0.0).second)
        val strikes = listOf(0.0, 80.0, 100.0, 130.0)
        val (ce, pe) = OptionMath.chainGreeks(strikes, listOf(5.0, 20.0, 3.0, null), listOf(1.0, 0.0, 3.0, 131.0), 100.0, 0.05, ratePct = 0.0)
        assertNull(ce[0], "zero strike"); assertNull(pe[0])
        assertEquals(ChainLegGreeks(0.0, 1.0, 0.0, 0.0, 0.0), ce[1], "ITM call with no time value")
        assertNull(pe[1], "no quote")
        assertTrue(ce[2]!!.ivPct > 0 && ce[2]!!.delta in 0.4..0.6)
        assertTrue(pe[2]!!.delta in -0.6..-0.4)
        assertNull(ce[3])
        // A put above its maximum does not invert: ITM -> delta -1.
        assertEquals(ChainLegGreeks(0.0, -1.0, 0.0, 0.0, 0.0), pe[3])
        // An OTM call priced above the forward does not invert either: delta 0.
        val (otm, _) = OptionMath.chainGreeks(listOf(130.0), listOf(150.0), listOf(null), 100.0, 0.05)
        assertEquals(ChainLegGreeks(0.0, 0.0, 0.0, 0.0, 0.0), otm[0])
        val (nan, _) = OptionMath.chainGreeks(listOf(100.0), listOf(Double.NaN), listOf(null), 100.0, 0.05)
        assertNull(nan[0])
    }

    @Test fun `expiry codes, symbols, cut-offs and time`() {
        assertEquals(LocalTime.of(23, 30), OptionMath.expiryCutoff("mcx")); assertEquals(LocalTime.of(12, 30), OptionMath.expiryCutoff("CDS"))
        assertEquals(LocalTime.of(15, 30), OptionMath.expiryCutoff("NFO"))
        assertEquals(LocalDate.of(2025, 11, 28), OptionMath.parseExpiryCode(" 28nov25 "))
        assertEquals("Invalid expiry format: 2025-11-28. Expected DDMMMYY (e.g. 28NOV25)",
            assertFailsWith<IllegalArgumentException> { OptionMath.parseExpiryCode("2025-11-28") }.message)
        assertEquals("Invalid month in expiry: 28XYZ25", assertFailsWith<IllegalArgumentException> { OptionMath.parseExpiryCode("28XYZ25") }.message)
        val s = OptionMath.parseOptionSymbol("usdinr28nov2483.50ce")
        assertEquals(OptionMath.OptionSymbol("USDINR", LocalDate.of(2024, 11, 28), 83.5, OptionType.CE), s)
        assertEquals(OptionType.PE, OptionMath.parseOptionSymbol("NIFTY28NOV2424000PE-EXTRA").type)
        assertFailsWith<IllegalArgumentException> { OptionMath.parseOptionSymbol("9NIFTY28NOV2424000CE") }
        assertFailsWith<IllegalArgumentException> { OptionMath.parseOptionSymbol("NIFTY24000CE") }
        assertEquals("05JAN26", OptionMath.expiryCode(LocalDate.of(2026, 1, 5)))
        val exp = LocalDate.of(2026, 9, 29)
        assertEquals(ZonedDateTime.of(exp, LocalTime.of(15, 30), OptionMath.IST), OptionMath.expiryInstant(exp))
        val cut = OptionMath.expiryInstant(exp)
        assertEquals(0.0 to 0.0, OptionMath.timeToExpiry(cut.plusSeconds(1), cut))
        assertEquals(OptionMath.MIN_YEARS to OptionMath.MIN_YEARS * 365.0, OptionMath.timeToExpiry(cut, cut))
        assertEquals(OptionMath.MIN_YEARS, OptionMath.timeToExpiry(cut.minusMinutes(30), cut).first)
        val (y, d) = OptionMath.timeToExpiry(cut.minusDays(73), cut)
        near(d, 73.0, 1e-12); near(y, 0.2, 1e-12)
        near(OptionMath.timeToExpiryYears(cut.minusDays(365), exp), 1.0, 1e-12)
        near(OptionMath.timeToExpiryYears(cut.minusDays(1), exp, LocalTime.of(23, 30)), (1 + 8 / 24.0) / 365.0, 1e-12)
    }

    @Test fun `forward from parity and the price used for greeks`() {
        assertEquals(24050.0, OptionMath.forwardFromParity(24000.0, 150.0, 100.0, 1.0))
        for ((k, c, p) in listOf(Triple(null, 1.0, 1.0), Triple(0.0, 1.0, 1.0), Triple(1.0, null, 1.0), Triple(1.0, 1.0, null),
            Triple(1.0, 0.0, 1.0), Triple(1.0, 1.0, -1.0))) assertEquals(7.0, OptionMath.forwardFromParity(k, c, p, 7.0))
        assertEquals(10.5, OptionMath.priceForGreeks(9.0, 10.0, 11.0))
        assertEquals(10.0, OptionMath.priceForGreeks(9.0, 10.0, 10.0), "a locked book is two-sided")
        assertEquals(9.0, OptionMath.priceForGreeks(9.0, 11.0, 10.0), "crossed book")
        assertEquals(9.0, OptionMath.priceForGreeks(9.0, null, 10.0)); assertEquals(9.0, OptionMath.priceForGreeks(9.0, 10.0, null))
        assertEquals(9.0, OptionMath.priceForGreeks(9.0, 0.0, 10.0)); assertEquals(9.0, OptionMath.priceForGreeks(9.0, 10.0, 0.0))
        assertEquals(0.0, OptionMath.priceForGreeks(null, null, null)); assertEquals(0.0, OptionMath.priceForGreeks(-1.0, null, null))
    }

    @Test fun `python rounding keeps signs and passes non-finite values`() {
        assertTrue(pyRound(Double.NaN, 2).isNaN()); assertEquals(Double.POSITIVE_INFINITY, pyRound(Double.POSITIVE_INFINITY, 2))
        assertEquals(-0.0, pyRound(-0.001, 2)); assertTrue(1.0 / pyRound(-0.001, 2) < 0)
        assertEquals(0.0, pyRound(0.001, 2)); assertEquals(2.67, pyRound(2.675, 2)); assertEquals(0.12, pyRound(0.125, 2))
        assertNotNull(OptionMath.EXCHANGE_EXPIRY_TIMES["MCX"])
        assertIs<OptionMath.IvSolve.Solved>(OptionMath.solveIv(1.0, OptionType.CE, 100.0, 100.0, 0.1, 0.0))
    }
}
