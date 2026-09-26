package com.optionslab.engine.options

import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Strategy Builder maths at its edges: closed and inactive legs, snapshots, degenerate inputs and the tails. */
class PayoffCoverageTest {
    private val now = Instant.parse("2026-09-21T04:30:00Z")               // 10:00 IST
    private val exp29 = "29SEP26"                                       // 15:30 IST = 10:00 UTC
    private val expTs = Instant.parse("2026-09-29T10:00:00Z").epochSecond.toDouble()
    private fun near(a: Double, b: Double, tol: Double = 1e-9) = assertTrue(abs(a - b) <= tol, "$a vs $b")

    private fun opt(side: Side, type: OptionType, k: Double, px: Double, lots: Int = 1, iv: Double = 15.0, expiry: String = exp29,
                    active: Boolean = true, exit: Double? = null, ts: Double? = null, mp: Double? = null, ref: Double? = null,
                    fwd: Double? = null, tick: Double? = null) =
        StrategyLeg(side, lots, 75, expiry, px, Segment.OPTION, k, type, iv, active, expiryTs = ts, tickSize = tick,
            marketPrice = mp, referenceUnderlying = ref, forwardPrice = fwd, exitPrice = exit)

    private fun fut(side: Side, px: Double, mp: Double? = null, ref: Double? = null, ts: Double? = null) =
        StrategyLeg(side, 1, 75, exp29, px, Segment.FUTURE, expiryTs = ts, marketPrice = mp, referenceUnderlying = ref)

    // ------------------------------------------------------------ leg flags

    @Test fun `closed and executable legs`() {
        val l = opt(Side.BUY, OptionType.CE, 100.0, 5.0)
        assertFalse(l.isClosed)
        assertTrue(l.copy(exitPrice = 0.0).isClosed); assertFalse(l.copy(exitPrice = -1.0).isClosed)
        assertFalse(l.copy(exitPrice = Double.NaN).isClosed); assertFalse(l.copy(exitPrice = Double.POSITIVE_INFINITY).isClosed)
        val ok = l.copy(contractValid = true, tickSize = 0.05)
        assertTrue(ok.isExecutable)
        for (bad in listOf(ok.copy(active = false), ok.copy(exitPrice = 1.0), ok.copy(contractValid = false), ok.copy(contractValid = null),
            ok.copy(lots = 0), ok.copy(lotSize = 0), ok.copy(tickSize = null), ok.copy(tickSize = Double.NaN), ok.copy(tickSize = 0.0))) {
            assertFalse(bad.isExecutable, bad.toString())
        }
        assertEquals(-1.0, l.copy(side = Side.SELL).sign); assertEquals(150.0, l.copy(lots = 2).quantity)
    }

    // ------------------------------------------------------------ pricing

    @Test fun `Black-Scholes with carry and dividends, and intrinsic at zero vol or time`() {
        val c = Payoff.bsPrice(OptionType.CE, 100.0, 105.0, 0.5, 0.2, 0.05, 0.02)
        val p = Payoff.bsPrice(OptionType.PE, 100.0, 105.0, 0.5, 0.2, 0.05, 0.02)
        near(c - p, 100.0 * exp(-0.02 * 0.5) - 105.0 * exp(-0.05 * 0.5), 1e-6)
        assertEquals(0.0, Payoff.bsPrice(OptionType.CE, 100.0, 105.0, 0.5, 0.0)); assertEquals(5.0, Payoff.bsPrice(OptionType.PE, 100.0, 105.0, 0.0, 0.2))
        val gc = Payoff.bsGreeks(OptionType.CE, 100.0, 105.0, 0.5, 0.2, 0.05, 0.02)
        val gp = Payoff.bsGreeks(OptionType.PE, 100.0, 105.0, 0.5, 0.2, 0.05, 0.02)
        near(gc.delta - gp.delta, exp(-0.02 * 0.5), 1e-12); near(gc.gamma, gp.gamma, 1e-15)
        assertTrue(gc.theta < 0)
        val d = Payoff.bsGreeks(OptionType.CE, 100.0, 100.0, 0.0, 0.0)
        assertTrue(d.delta.isFinite() && d.gamma.isFinite(), "floored time and vol stay finite")
        assertEquals(0.5, Payoff.normCdf(0.0), 1e-9); assertEquals(0.0, Payoff.erf(0.0), 1e-9)
        near(Payoff.erf(-1.0), -Payoff.erf(1.0), 1e-15); near(Payoff.normPdf(0.0), 0.3989422804014327, 1e-15)
    }

    @Test fun `strike moneyness by whole steps`() {
        assertNull(Payoff.strikeMoneyness(null, 100.0, 50.0, OptionType.CE)); assertNull(Payoff.strikeMoneyness(100.0, null, 50.0, OptionType.CE))
        assertNull(Payoff.strikeMoneyness(100.0, 100.0, 50.0, null)); assertNull(Payoff.strikeMoneyness(100.0, 100.0, 0.0, OptionType.CE))
        assertNull(Payoff.strikeMoneyness(100.0, 100.0, Double.NaN, OptionType.CE)); assertNull(Payoff.strikeMoneyness(100.0, 100.0, -50.0, OptionType.CE))
        assertEquals(Moneyness("ATM", "ATM", 0), Payoff.strikeMoneyness(24010.0, 24000.0, 50.0, OptionType.CE))
        assertEquals(Moneyness("OTM2", "OTM", 2), Payoff.strikeMoneyness(24100.0, 24000.0, 50.0, OptionType.CE))
        assertEquals(Moneyness("ITM2", "ITM", 2), Payoff.strikeMoneyness(24100.0, 24000.0, 50.0, OptionType.PE))
        assertEquals(Moneyness("ATM", "ATM", 0), Payoff.strikeMoneyness(23975.0, 24000.0, 50.0, OptionType.CE), "half a step rounds up (JS Math.round)")
        assertEquals(Moneyness("ITM1", "ITM", -1), Payoff.strikeMoneyness(23974.0, 24000.0, 50.0, OptionType.CE))
        assertEquals(Moneyness("OTM1", "OTM", -1), Payoff.strikeMoneyness(23950.0, 24000.0, 50.0, OptionType.PE))
    }

    @Test fun `expiry parsing and days`() {
        assertEquals(Instant.parse("2026-09-29T10:00:00Z"), Payoff.parseExpiryDate(exp29))
        assertEquals(Instant.parse("2026-09-05T10:00:00Z"), Payoff.parseExpiryDate("5SEP26"))
        assertEquals(Instant.parse("2026-03-03T10:00:00Z"), Payoff.parseExpiryDate("31FEB26"), "an overflowing day rolls over as Date.UTC does")
        assertNull(Payoff.parseExpiryDate("29sep26")); assertNull(Payoff.parseExpiryDate("29XYZ26")); assertNull(Payoff.parseExpiryDate("2026-09-29"))
        near(Payoff.daysToExpiry(exp29, now), 8.0 + 5.5 / 24)
        assertEquals(0.0, Payoff.daysToExpiry("bad", now)); assertEquals(0.0, Payoff.daysToExpiry(exp29, Instant.parse("2027-01-01T00:00:00Z")))
        assertEquals(0.0, Payoff.daysToYears(-5.0)); near(Payoff.daysToYears(73.0), 0.2)
        assertEquals(0.0, Payoff.nearestLegDays(emptyList(), now))
        assertEquals(0.0, Payoff.nearestLegDays(listOf(opt(Side.BUY, OptionType.CE, 1.0, 1.0, active = false)), now))
        near(Payoff.nearestLegDays(listOf(opt(Side.BUY, OptionType.CE, 1.0, 1.0, ts = expTs - 86400), opt(Side.BUY, OptionType.CE, 1.0, 1.0),
            opt(Side.BUY, OptionType.CE, 1.0, 1.0, exit = 2.0, ts = 1.0)), now), 7.0 + 5.5 / 24)
    }

    // ------------------------------------------------------------ carry

    @Test fun `carry rate is the median of the legs' own snapshots`() {
        assertNull(Payoff.carryRate(emptyList(), now))
        assertNull(Payoff.carryRate(listOf(opt(Side.BUY, OptionType.CE, 100.0, 1.0, ref = 100.0)), now), "no forward")
        assertNull(Payoff.carryRate(listOf(opt(Side.BUY, OptionType.CE, 100.0, 1.0, ref = 0.0, fwd = 101.0)), now))
        assertNull(Payoff.carryRate(listOf(opt(Side.BUY, OptionType.CE, 100.0, 1.0, ref = 100.0, fwd = Double.NaN)), now))
        assertNull(Payoff.carryRate(listOf(opt(Side.BUY, OptionType.CE, 100.0, 1.0, ref = 100.0, fwd = 101.0, expiry = "bad")), now), "no time left")
        assertNull(Payoff.carryRate(listOf(opt(Side.BUY, OptionType.CE, 100.0, 1.0, ref = 100.0, fwd = 101.0, active = false)), now))
        assertNull(Payoff.carryRate(listOf(opt(Side.BUY, OptionType.CE, 100.0, 1.0, ref = 100.0, fwd = 101.0, exit = 3.0)), now))
        val years = 73.0 / 365
        val t = now.plusSeconds(73L * 86400).epochSecond.toDouble()
        val one = Payoff.carryRate(listOf(fut(Side.BUY, 100.0, mp = 101.0, ref = 100.0, ts = t)), now)!!
        near(one, kotlin.math.ln(1.01) / years, 1e-12)
        val three = Payoff.carryRate(listOf(
            fut(Side.BUY, 100.0, mp = 101.0, ref = 100.0, ts = t),
            opt(Side.BUY, OptionType.CE, 100.0, 1.0, ref = 100.0, fwd = 102.0, ts = t),
            opt(Side.BUY, OptionType.PE, 100.0, 1.0, ref = 100.0, fwd = 103.0, ts = t),
        ), now)!!
        near(three, kotlin.math.ln(1.02) / years, 1e-12)
        val two = Payoff.carryRate(listOf(fut(Side.BUY, 100.0, mp = 101.0, ref = 100.0, ts = t), opt(Side.BUY, OptionType.CE, 100.0, 1.0, ref = 100.0, fwd = 103.0, ts = t)), now)!!
        near(two, (kotlin.math.ln(1.01) + kotlin.math.ln(1.03)) / 2 / years, 1e-12)
    }

    // ------------------------------------------------------------ P&L

    @Test fun `leg P&L - inactive, closed, futures, incomplete options and expiry`() {
        assertEquals(0.0, Payoff.legPnlAt(opt(Side.BUY, OptionType.CE, 100.0, 5.0, active = false), 200.0, 0.0, now = now))
        assertEquals(-2.0 * 75, Payoff.legPnlAt(opt(Side.SELL, OptionType.CE, 100.0, 5.0, exit = 7.0), 200.0, 0.0, now = now))
        assertEquals(10.0 * 75, Payoff.legPnlAt(fut(Side.BUY, 100.0), 110.0, 0.0, now = now))
        assertEquals(-10.0 * 75, Payoff.legPnlAt(fut(Side.SELL, 100.0), 110.0, 0.0, now = now))
        // A future with a snapshot is valued at the carried forward.
        val t = now.plusSeconds(73L * 86400).epochSecond.toDouble()
        val f = fut(Side.BUY, 101.0, mp = 101.0, ref = 100.0, ts = t)
        near(Payoff.legPnlAt(f, 100.0, 0.0, now = now), 0.0, 1e-9)
        near(Payoff.legPnlAt(f, 100.0, 0.0, now = now, carryRate = null), -75.0, 1e-9)
        assertEquals(0.0, Payoff.legPnlAt(opt(Side.BUY, OptionType.CE, 100.0, 5.0).copy(strike = null), 200.0, 0.0, now = now))
        assertEquals(0.0, Payoff.legPnlAt(opt(Side.BUY, OptionType.CE, 100.0, 5.0).copy(optionType = null), 200.0, 0.0, now = now))
        // At expiry: intrinsic.
        near(Payoff.legPnlAt(opt(Side.BUY, OptionType.PE, 100.0, 5.0), 90.0, 30.0, now = now), 5.0 * 75)
        // Zero IV or a non-positive forward: intrinsic.
        near(Payoff.legPnlAt(opt(Side.BUY, OptionType.CE, 100.0, 5.0, iv = 0.0), 110.0, 0.0, now = now), 5.0 * 75)
        near(Payoff.legPnlAt(opt(Side.BUY, OptionType.PE, 100.0, 5.0), 0.0, 0.0, now = now), 95.0 * 75)
        near(Payoff.legPnlAt(opt(Side.BUY, OptionType.PE, 100.0, 5.0), Double.NaN, 0.0, now = now), 95.0 * 75, 1e-9)
    }

    @Test fun `the unshifted live snapshot passes through the quoted premium within a tick`() {
        val k = 24000.0
        val model = { l: StrategyLeg -> Payoff.legPnlAt(l.copy(marketPrice = null), 24000.0, 0.0, now = now, carryRate = 0.0) / 75 + l.price }
        val base = opt(Side.BUY, OptionType.CE, k, 100.0, iv = 12.0, ref = 24000.0, fwd = 24000.0, tick = 0.05)
        val m = model(base)
        val near = base.copy(marketPrice = m + 0.03)
        assertEquals((m + 0.03 - 100.0) * 75, Payoff.legPnlAt(near, 24000.0, 0.0, now = now, carryRate = 0.0), 1e-9)
        val far = base.copy(marketPrice = m + 0.2)
        near((m - 100.0) * 75, Payoff.legPnlAt(far, 24000.0, 0.0, now = now, carryRate = 0.0), 1e-9)
        // Shifted in price, time or IV, or with no tick: the model.
        near((m - 100.0) * 75, Payoff.legPnlAt(near.copy(tickSize = null), 24000.0, 0.0, now = now, carryRate = 0.0), 1e-9)
        near((m - 100.0) * 75, Payoff.legPnlAt(near.copy(tickSize = 0.0), 24000.0, 0.0, now = now, carryRate = 0.0), 1e-9)
        assertTrue(abs(Payoff.legPnlAt(near, 24000.0, 0.0, ivOverride = 13.0, now = now, carryRate = 0.0) - (m + 0.03 - 100.0) * 75) > 1.0)
        assertTrue(abs(Payoff.legPnlAt(near, 24000.0, 1.0, now = now, carryRate = 0.0) - (m + 0.03 - 100.0) * 75) > 1.0)
        assertTrue(abs(Payoff.legPnlAt(near.copy(forwardPrice = null), 24000.0, 0.0, now = now, carryRate = 0.0) - (m + 0.03 - 100.0) * 75) > 1.0)
    }

    @Test fun `total P&L uses the fallback IV and shifts it`() {
        val l = opt(Side.BUY, OptionType.CE, 100.0, 1.0, iv = 0.0)
        val flat = Payoff.totalPnlAt(listOf(l), 100.0, 0.0, now = now)
        near(flat, -75.0, 1e-9)
        val fb = Payoff.totalPnlAt(listOf(l), 100.0, 0.0, fallbackIv = 20.0, now = now)
        val shifted = Payoff.totalPnlAt(listOf(l), 100.0, 0.0, ivShiftPct = 50.0, fallbackIv = 20.0, now = now)
        assertTrue(fb > flat && shifted > fb)
        val legs = listOf(opt(Side.SELL, OptionType.CE, 100.0, 5.0), opt(Side.BUY, OptionType.PE, 90.0, 2.0, lots = 2), opt(Side.SELL, OptionType.PE, 1.0, 9.0, active = false), fut(Side.BUY, 50.0))
        assertEquals((5.0 - 2.0 * 2) * 75, Payoff.netCredit(legs)); assertEquals((5.0 + 4.0) * 75, Payoff.totalPremium(legs))
    }

    // ------------------------------------------------------------ ranges and bands

    @Test fun `lognormal band refuses degenerate input`() {
        val (lo, hi) = Payoff.lognormalPriceBand(100.0, 20.0, 0.25, 1.0)!!
        near(lo, 100.0 * exp(-0.005 - 0.1)); near(hi, 100.0 * exp(-0.005 + 0.1))
        for ((s, iv, t, sd) in listOf(listOf(Double.NaN, 20.0, 0.25, 1.0), listOf(100.0, Double.NaN, 0.25, 1.0), listOf(100.0, 20.0, Double.NaN, 1.0),
            listOf(100.0, 20.0, 0.25, Double.NaN), listOf(0.0, 20.0, 0.25, 1.0), listOf(100.0, 0.0, 0.25, 1.0), listOf(100.0, 20.0, 0.0, 1.0),
            listOf(100.0, 20.0, 0.25, 0.0), listOf(100.0, 1e300, 1e300, 1.0), listOf(1e308, 20.0, 0.25, 50.0))) {
            assertNull(Payoff.lognormalPriceBand(s, iv, t, sd), "$s $iv $t $sd")
        }
    }

    @Test fun `price range spans strikes and the band, and survives an overflowing spot`() {
        val legs = listOf(opt(Side.BUY, OptionType.CE, 150.0, 1.0), opt(Side.BUY, OptionType.PE, 40.0, 1.0, exit = 1.0))
        val (lo, hi) = Payoff.payoffPriceRange(100.0, legs, 20.0, 0.25)
        near(lo, Payoff.lognormalPriceBand(100.0, 20.0, 0.25, 2.0)!!.first); near(hi, 150.0)
        val (lo2, hi2) = Payoff.payoffPriceRange(100.0, emptyList(), 0.0, 0.0)
        near(lo2, 90.0); near(hi2, 110.0)
        val (_, hi3) = Payoff.payoffPriceRange(Double.MAX_VALUE, emptyList(), 0.0, 0.0)
        assertEquals(Double.MAX_VALUE, hi3)
    }

    // ------------------------------------------------------------ payoff analysis

    private fun payoff(legs: List<StrategyLeg>, days: Double = 9.0, steps: Int = 120) =
        Payoff.computePayoff(legs, 100.0, days, 0.0, 50.0 to 150.0, steps, now = now)

    @Test fun `terminal payoffs - unbounded tails, bounded spreads and breakevens`() {
        val longCall = payoff(listOf(opt(Side.BUY, OptionType.CE, 100.0, 5.0)))
        assertEquals(Double.POSITIVE_INFINITY, longCall.maxProfit); near(longCall.maxLoss, -375.0)
        assertEquals(listOf(105.0), longCall.breakevens.map { Math.round(it * 1e6) / 1e6 })
        val shortCall = payoff(listOf(opt(Side.SELL, OptionType.CE, 100.0, 5.0)))
        assertEquals(Double.NEGATIVE_INFINITY, shortCall.maxLoss); near(shortCall.maxProfit, 375.0)
        val shortPut = payoff(listOf(opt(Side.SELL, OptionType.PE, 100.0, 5.0)))
        near(shortPut.maxLoss, -95.0 * 75); near(shortPut.maxProfit, 375.0)
        near(shortPut.breakevens.single(), 95.0)
        // A zero-cost call spread: flat zero up to the long strike, which is the one breakeven edge.
        val spread = payoff(listOf(opt(Side.BUY, OptionType.CE, 100.0, 0.0), opt(Side.SELL, OptionType.CE, 110.0, 0.0)))
        near(spread.maxLoss, 0.0); near(spread.maxProfit, 750.0)
        assertEquals(listOf(100.0), spread.breakevens)
        assertTrue(spread.zeroCrossings.isNotEmpty())
        // A put spread flat at zero ABOVE its strikes: the right edge of the plateau is the last strike.
        val putSpread = payoff(listOf(opt(Side.BUY, OptionType.PE, 110.0, 0.0), opt(Side.SELL, OptionType.PE, 100.0, 0.0)))
        assertEquals(listOf(110.0), putSpread.breakevens)
        // A long future with a snapshot-free leg is linear: one breakeven at its price.
        val f = payoff(listOf(fut(Side.BUY, 100.0)))
        assertEquals(Double.POSITIVE_INFINITY, f.maxProfit); near(f.breakevens.single(), 100.0, 1e-6)
    }

    @Test fun `a book with nothing left to respond is a constant`() {
        val closed = payoff(listOf(opt(Side.BUY, OptionType.CE, 100.0, 5.0, exit = 8.0), opt(Side.BUY, OptionType.CE, 100.0, 5.0, active = false)))
        near(closed.maxProfit, 225.0); near(closed.maxLoss, 225.0)
        assertTrue(closed.breakevens.isEmpty())
        assertTrue(closed.samples.all { abs(it.expiry - 225.0) < 1e-9 })
        val empty = Payoff.computePayoff(emptyList(), 100.0, 0.0, 0.0, 90.0 to 110.0, 0, now = now)
        assertEquals(2, empty.samples.size, "steps are at least 1")
    }

    @Test fun `non-terminal (calendar) payoffs are searched numerically`() {
        val near = opt(Side.SELL, OptionType.CE, 100.0, 3.0, iv = 20.0)
        val far = opt(Side.BUY, OptionType.CE, 100.0, 4.0, iv = 20.0, expiry = "27OCT26")
        val legs = listOf(near, far)
        assertTrue(Payoff.hasMultipleActiveExpiries(legs))
        val r = Payoff.computePayoff(legs, 100.0, Payoff.nearestLegDays(legs, now), 0.0, 80.0 to 120.0, 80, fallbackIv = 20.0, now = now)
        assertTrue(r.maxProfit.isFinite() && r.maxLoss.isFinite())
        assertEquals(2, r.breakevens.size)
        assertTrue(r.breakevens[0] < 100.0 && r.breakevens[1] > 100.0)
        near(r.maxLoss, -1.0 * 75, 1.0)   // both deep ITM or OTM: the net debit is lost
        // A non-terminal naked long call: unbounded upside, found from the slope.
        val lc = Payoff.computePayoff(listOf(far), 100.0, 9.0, 0.0, 80.0 to 120.0, 40, now = now)
        assertEquals(Double.POSITIVE_INFINITY, lc.maxProfit)
        val sc = Payoff.computePayoff(listOf(far.copy(side = Side.SELL)), 100.0, 9.0, 0.0, 80.0 to 120.0, 40, now = now)
        assertEquals(Double.NEGATIVE_INFINITY, sc.maxLoss)
        // A long put that is not yet at expiry: bounded both ways, the tail value is its lost premium.
        val lp = Payoff.computePayoff(listOf(opt(Side.BUY, OptionType.PE, 100.0, 6.0, expiry = "27OCT26")), 100.0, 9.0, 0.0, 80.0 to 120.0, 40, now = now)
        near(lp.maxLoss, -6.0 * 75, 1e-6); assertTrue(lp.maxProfit > 90.0 * 75)
    }

    @Test fun `expiry identities for calendars`() {
        assertFalse(Payoff.hasMultipleActiveExpiries(listOf(opt(Side.BUY, OptionType.CE, 1.0, 1.0), opt(Side.BUY, OptionType.CE, 1.0, 1.0, ts = expTs))))
        assertTrue(Payoff.hasMultipleActiveExpiries(listOf(opt(Side.BUY, OptionType.CE, 1.0, 1.0, expiry = "weekly"), opt(Side.BUY, OptionType.CE, 1.0, 1.0))))
        assertFalse(Payoff.hasMultipleActiveExpiries(listOf(opt(Side.BUY, OptionType.CE, 1.0, 1.0, expiry = " --- "), opt(Side.BUY, OptionType.CE, 1.0, 1.0))), "blank identity ignored")
        assertFalse(Payoff.hasMultipleActiveExpiries(listOf(opt(Side.BUY, OptionType.CE, 1.0, 1.0, expiry = "27OCT26", exit = 1.0), opt(Side.BUY, OptionType.CE, 1.0, 1.0))))
        assertFalse(Payoff.hasMultipleActiveExpiries(listOf(opt(Side.BUY, OptionType.CE, 1.0, 1.0, ts = Double.NaN), opt(Side.BUY, OptionType.CE, 1.0, 1.0))))
    }

    // ------------------------------------------------------------ probability of profit

    @Test fun `probability of profit refuses degenerate input and adds both tails`() {
        val s = listOf(PayoffSample(0.0, 1.0, 0.0), PayoffSample(100.0, -1.0, 0.0), PayoffSample(200.0, 1.0, 0.0))
        assertNull(Payoff.probabilityOfProfit(s.take(1), 100.0, 20.0, 0.1))
        for ((spot, iv, t) in listOf(Triple(Double.NaN, 20.0, 0.1), Triple(100.0, Double.NaN, 0.1), Triple(100.0, 20.0, Double.NaN),
            Triple(0.0, 20.0, 0.1), Triple(100.0, 0.0, 0.1), Triple(100.0, 20.0, 0.0), Triple(100.0, 1e200, 1e200))) {
            assertNull(Payoff.probabilityOfProfit(s, spot, iv, t), "$spot $iv $t")
        }
        assertNull(Payoff.probabilityOfProfit(s + PayoffSample(Double.NaN, 1.0, 0.0), 100.0, 20.0, 0.1))
        assertNull(Payoff.probabilityOfProfit(s + PayoffSample(300.0, Double.NaN, 0.0), 100.0, 20.0, 0.1))
        // Profit only in both tails, loss in the middle: both tails counted; the interval mass is zero.
        val both = Payoff.probabilityOfProfit(
            listOf(PayoffSample(50.0, 1.0, 0.0), PayoffSample(100.0, -1.0, 0.0), PayoffSample(150.0, 1.0, 0.0)), 100.0, 20.0, 0.1)!!
        assertTrue(both in 0.0..0.2, "$both")
        // Profit everywhere: probability one.
        assertEquals(1.0, Payoff.probabilityOfProfit(listOf(PayoffSample(0.0, 5.0, 0.0), PayoffSample(200.0, 5.0, 0.0)), 100.0, 20.0, 0.1)!!, 1e-9)
        // Loss everywhere: zero.
        assertEquals(0.0, Payoff.probabilityOfProfit(listOf(PayoffSample(0.0, -5.0, 0.0), PayoffSample(200.0, -5.0, 0.0)), 100.0, 20.0, 0.1))
        // A non-finite underlying is refused up front.
        assertNull(Payoff.probabilityOfProfit(listOf(PayoffSample(1.0, 5.0, 0.0), PayoffSample(Double.POSITIVE_INFINITY, 5.0, 0.0)), 100.0, 20.0, 0.1))
    }

    // ------------------------------------------------------------ symbols and references

    @Test fun `symbols and quote references`() {
        assertEquals("NIFTY29SEP2624000CE", Payoff.buildOptionSymbol("NIFTY", exp29, 24000.0, OptionType.CE))
        assertEquals("NIFTY29SEP2624000PE", Payoff.buildOptionSymbol("NIFTY", exp29, 24000.0000001, OptionType.PE))
        assertEquals("USDINR29SEP2683.25CE", Payoff.buildOptionSymbol("USDINR", exp29, 83.25, OptionType.CE))
        assertEquals("NIFTY29SEP26FUT", Payoff.buildFutureSymbol("NIFTY", exp29))
        assertEquals("0", Payoff.jsNumber(-0.0)); assertEquals("0.1", Payoff.jsNumber(0.1)); assertEquals("-2.5", Payoff.jsNumber(-2.5))
        assertEquals("NSE_INDEX", Payoff.quoteExchange("NIFTY", "NFO")); assertEquals("BSE_INDEX", Payoff.quoteExchange("SENSEX", "BFO"))
        assertEquals("NSE", Payoff.quoteExchange("RELIANCE", "nfo")); assertEquals("BSE", Payoff.quoteExchange("TCS", "BFO"))
        assertEquals("MCX", Payoff.quoteExchange("GOLD", "mcx"))
        assertEquals("NIFTY" to "NSE_INDEX", Payoff.chartReference(" nifty ", "nfo"))
        assertEquals("NIFTY 50" to "NSE_INDEX", Payoff.chartReference("NIFTY", "NFO", " nifty 50 ", "nse_index"))
        assertEquals("RELIANCE" to "NSE", Payoff.chartReference("RELIANCE", "NFO", "", "NSE"))
        assertEquals("RELIANCE" to "NSE", Payoff.chartReference("RELIANCE", "NFO", "X", null))
        assertNull(Payoff.chartReference("GOLD", "MCX")); assertNull(Payoff.chartReference("BTC", "DELTA", cryptoExchanges = setOf("DELTA")))
        assertNotNull(Payoff.chartReference("BTC", "DELTA"))
    }
}
