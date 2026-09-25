package com.optionslab.engine.options

import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChainSnapshotTest {
    private val now = ZonedDateTime.of(2026, 9, 25, 11, 0, 0, 0, IST)
    private val expiry = LocalDate.of(2026, 9, 29)
    private val spot = 25_000.0

    /** A flat-vol Black-76 chain, as minute series, so the answers are known. */
    private fun chain(vol: Double = 0.12): List<Series> {
        val t = OptionMath.timeToExpiryYears(now, expiry)
        return (-10..10).flatMap { i ->
            val k = spot + 50.0 * i
            listOf(Right.CE, Right.PE).map { r ->
                val px = OptionMath.price(if (r == Right.CE) OptionType.CE else OptionType.PE, spot, k, t, 0.0, vol)
                val oi = (100_000L - 4_000L * abs(i)) * (if (r == Right.PE && i < 0) 2 else 1)
                Series(expiry, k, r, 65, intArrayOf(600, 601), doubleArrayOf(px, px), null, null, null, longArrayOf(10, 20), longArrayOf(oi, oi))
            }
        }
    }

    @Test fun `every screen is computed from one priced chain`() {
        val rows = ChainSnapshot.rowsFrom(chain(), emptyMap(), 65)
        assertEquals(21, rows.size)
        assertEquals(30L, rows[0].ce!!.volume)
        val s = ChainSnapshot.of("NIFTY", expiry, spot, 65, rows, now)
        assertEquals(25_000.0, s.atm)
        assertTrue(abs(s.forward - spot) < 0.5, "forward ${s.forward}")
        val atmIv = assertNotNull(s.atmIv)
        assertTrue(abs(atmIv - 12.0) < 0.1, "ATM IV $atmIv")
        val atmIdx = rows.indexOfFirst { it.strike == 25_000.0 }
        val d = assertNotNull(s.ceGreeks[atmIdx]).delta
        assertTrue(d in 0.45..0.55, "ATM call delta $d")
        assertNotNull(s.maxPain)
        assertNotNull(s.gex)
        assertNotNull(s.gammaDensity)
        assertTrue(s.pcr.pcrOi > 1.0, "puts below spot carry double OI")
        assertEquals("29SEP26", s.expiryCode)
    }

    @Test fun `a template lands on the chain and its payoff has the textbook shape`() {
        val rows = ChainSnapshot.rowsFrom(chain(), emptyMap(), 65)
        val s = ChainSnapshot.of("NIFTY", expiry, spot, 65, rows, now)
        val condor = StrategyTemplates.ALL.first { it.name.contains("Iron Condor", ignoreCase = true) }
        val r = StrategyTemplates.resolve(condor, rows, s.atm!!, s.expiryCode, listOf(s.expiryCode))
        assertTrue(r.ok, r.errors.toString())
        val legs = StrategyTemplates.toStrategyLegs(r, "NIFTY", 65) { 12.0 }
        val inst = now.toInstant()
        val range = Payoff.payoffPriceRange(spot, legs, 12.0, s.tYears)
        val p = Payoff.computePayoff(legs, spot, Payoff.nearestLegDays(legs, inst), 0.0, range, now = inst)
        assertTrue(p.maxProfit > 0 && p.maxProfit.isFinite())
        assertTrue(p.maxLoss < 0 && p.maxLoss.isFinite())
        assertEquals(2, p.breakevens.size)
        assertTrue(Payoff.netCredit(legs) > 0, "an iron condor is a credit")
    }
}
