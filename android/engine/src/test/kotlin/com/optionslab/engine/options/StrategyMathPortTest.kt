package com.optionslab.engine.options

import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "multi-expiry structures" block of IraAlgo's `strategyMath.test.ts`,
 * ported case for case: the regressions its authors guard against (tail
 * slopes that cancel on quantity but not on carry, a stale wing, a window-edge
 * "breakeven") must stay fixed here too.
 */
class StrategyMathPortTest {
    private val spot = 24_800.0
    private val at = Instant.parse("2026-08-14T05:00:00Z")
    private val nearTs = at.toEpochMilli() / 1000.0 + 7 * 86_400
    private val farTs = at.toEpochMilli() / 1000.0 + 35 * 86_400
    private fun forwardFor(ts: Double) = spot * exp(0.06 * (ts * 1000 - at.toEpochMilli()) / (365 * 86_400_000.0))

    private fun leg(id: String, side: Side, type: OptionType, strike: Double, price: Double, ts: Double, lots: Int = 1) = StrategyLeg(
        side = side, lots = lots, lotSize = 75, expiry = if (ts == farTs) "18SEP26" else "21AUG26", price = price, strike = strike,
        optionType = type, iv = 12.0, symbol = id, id = id, expiryTs = ts, referenceUnderlying = spot, forwardPrice = forwardFor(ts),
    )

    private fun payoffFor(legs: List<StrategyLeg>): PayoffResult {
        val nearest = Payoff.nearestLegDays(legs, at)
        val range = Payoff.payoffPriceRange(spot, legs, 12.0, nearest / 365)
        return Payoff.computePayoff(legs, spot, nearest, 0.0, range, 240, 0.0, 12.0, at)
    }

    @Test fun `sees unbounded upside on a call calendar whose quantities cancel`() {
        val p = payoffFor(listOf(leg("near", Side.SELL, OptionType.CE, 24_800.0, 150.0, nearTs), leg("far", Side.BUY, OptionType.CE, 24_800.0, 300.0, farTs)))
        assertEquals(Double.POSITIVE_INFINITY, p.maxProfit)
        assertTrue(p.maxLoss.isFinite())
    }

    @Test fun `sees unbounded upside on a diagonal and a double diagonal`() {
        assertEquals(Double.POSITIVE_INFINITY, payoffFor(listOf(leg("near", Side.SELL, OptionType.CE, 24_800.0, 150.0, nearTs),
            leg("far", Side.BUY, OptionType.CE, 24_900.0, 260.0, farTs))).maxProfit)
        val dd = payoffFor(listOf(
            leg("near-call", Side.SELL, OptionType.CE, 24_900.0, 120.0, nearTs), leg("far-call", Side.BUY, OptionType.CE, 25_000.0, 240.0, farTs),
            leg("near-put", Side.SELL, OptionType.PE, 24_700.0, 120.0, nearTs), leg("far-put", Side.BUY, OptionType.PE, 24_600.0, 240.0, farTs)))
        assertEquals(Double.POSITIVE_INFINITY, dd.maxProfit)
        assertEquals(dd.breakevens.sorted(), dd.breakevens)
    }

    @Test fun `settles a covered call against spot on both legs`() {
        val carry = ln(24_900.0 / 24_800) / (7.0 / 365)
        val future = StrategyLeg(side = Side.BUY, lots = 1, lotSize = 75, expiry = "21AUG26", price = 24_900.0, segment = Segment.FUTURE,
            expiryTs = nearTs, marketPrice = 24_900.0, referenceUnderlying = 24_800.0, symbol = "NIFTY21AUG26FUT", id = "fut")
        val shortCall = leg("short-call", Side.SELL, OptionType.CE, 25_000.0, 120.0, nearTs).copy(forwardPrice = 24_800 * exp(carry * 7 / 365))
        val p = payoffFor(listOf(future, shortCall))
        assertEquals(1, p.breakevens.size)
        near(24_780.0, p.breakevens[0], 0.0, 5e-5, "breakeven")
        near(16_500.0, p.maxProfit, 0.0, 5e-5, "max profit")
        near(-1_858_500.0, p.maxLoss, 0.0, 5e-5, "max loss")
    }

    @Test fun `keeps an iron condor defined-risk when one wing has no forward`() {
        val p = payoffFor(listOf(
            leg("long-put", Side.BUY, OptionType.PE, 24_600.0, 30.0, nearTs), leg("short-put", Side.SELL, OptionType.PE, 24_700.0, 60.0, nearTs),
            leg("short-call", Side.SELL, OptionType.CE, 24_900.0, 60.0, nearTs), leg("long-call", Side.BUY, OptionType.CE, 25_000.0, 30.0, nearTs).copy(forwardPrice = null)))
        near(-3_000.0, p.maxLoss, 0.0, 5e-5, "max loss")
        near(4_500.0, p.maxProfit, 0.0, 5e-5, "max profit")
    }

    @Test fun `reports calendar breakevens of the strategy, not of the analysis window`() {
        val legs = listOf(leg("near", Side.SELL, OptionType.PE, 24_800.0, 40.0, nearTs), leg("far", Side.BUY, OptionType.PE, 24_800.0, 40.0, farTs))
        val nearest = Payoff.nearestLegDays(legs, at)
        for (hi in listOf(27_280.0, 60_000.0)) {
            val p = Payoff.computePayoff(legs, spot, nearest, 0.0, 22_320.0 to hi, 240, 0.0, 12.0, at)
            assertTrue(p.breakevens.all { it > 0 && it < 40_000 }, "${p.breakevens}")
        }
    }

    @Test fun `caps a batman below and leaves its upside loss unbounded`() {
        val p = payoffFor(listOf(
            leg("call-long", Side.BUY, OptionType.CE, 25_300.0, 40.0, nearTs), leg("call-short", Side.SELL, OptionType.CE, 25_550.0, 20.0, nearTs, 2),
            leg("put-long", Side.BUY, OptionType.PE, 24_300.0, 40.0, nearTs), leg("put-short", Side.SELL, OptionType.PE, 24_050.0, 20.0, nearTs, 2)))
        assertEquals(Double.NEGATIVE_INFINITY, p.maxLoss)
        assertTrue(p.maxProfit.isFinite())
    }

    @Test fun `does not invent a breakeven for an empty strategy`() {
        val p = Payoff.computePayoff(emptyList(), 100.0, 7.0, 0.0, 90.0 to 110.0, 240, 0.0, 20.0, at)
        assertEquals(emptyList(), p.breakevens)
        assertEquals(0.0, p.maxProfit); assertEquals(0.0, p.maxLoss)
    }

    @Test fun `an executable leg needs a resolved contract, whole lots and a tick`() {
        val l = leg("x", Side.BUY, OptionType.CE, 24_800.0, 100.0, nearTs)
        assertEquals(false, l.isExecutable)
        assertEquals(true, l.copy(contractValid = true, tickSize = 0.05).isExecutable)
        assertEquals(false, l.copy(contractValid = true, tickSize = 0.05, exitPrice = 0.0).isExecutable) // a zero exit is a closed leg
    }
}
