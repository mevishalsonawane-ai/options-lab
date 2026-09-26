package com.optionslab.engine.risk

import java.time.LocalDate
import org.junit.jupiter.api.Disabled as Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiskCoverageTest {

    // ---------------------------------------------------------------- values

    @Test fun `enums print their wire spelling and reasons read back`() {
        assertEquals("SELL", Side.SELL.toString()); assertEquals("stepped", TrailMode.STEPPED.toString())
        assertEquals("combined_sl", BreachReason.COMBINED_STOP.toString())
        for (r in BreachReason.entries) assertEquals(r, BreachReason.fromWire(r.wire))
        assertNull(BreachReason.fromWire("stop")); assertNull(BreachReason.fromWire(null))
    }

    @Test fun `pyFloat follows float()`() {
        assertNull(RiskValues.pyFloat(null)); assertEquals(1.0, RiskValues.pyFloat(true)); assertEquals(0.0, RiskValues.pyFloat(false))
        assertEquals(3.0, RiskValues.pyFloat(3)); assertEquals(2.5, RiskValues.pyFloat(2.5f))
        assertEquals(1000.5, RiskValues.pyFloat(" 1_000.5 ")); assertEquals(0.5, RiskValues.pyFloat(".5")); assertEquals(5.0, RiskValues.pyFloat("5."))
        assertEquals(1e-3, RiskValues.pyFloat("1E-3"))
        assertEquals(Double.POSITIVE_INFINITY, RiskValues.pyFloat("+Infinity")); assertEquals(Double.NEGATIVE_INFINITY, RiskValues.pyFloat("-inf"))
        assertTrue(RiskValues.pyFloat("NaN")!!.isNaN()); assertTrue(RiskValues.pyFloat("-nan")!!.isNaN())
        assertNull(RiskValues.pyFloat("abc")); assertNull(RiskValues.pyFloat("1__0")); assertNull(RiskValues.pyFloat("")); assertNull(RiskValues.pyFloat(listOf(1)))
    }

    @Test fun `price predicates and coercions`() {
        assertFalse(RiskValues.isPrice(null)); assertFalse(RiskValues.isPrice(true)); assertFalse(RiskValues.isPrice("x"))
        assertFalse(RiskValues.isPrice(0.0)); assertFalse(RiskValues.isPrice(-1)); assertFalse(RiskValues.isPrice(Double.NaN)); assertFalse(RiskValues.isPrice("inf"))
        assertTrue(RiskValues.isPrice("0.05")); assertTrue(RiskValues.isPrice(1L))
        assertEquals(7.0, RiskValues.asFloat(null, 7.0)); assertEquals(7.0, RiskValues.asFloat(false, 7.0)); assertEquals(7.0, RiskValues.asFloat("x", 7.0))
        assertEquals(7.0, RiskValues.asFloat(Double.NaN, 7.0)); assertEquals(-3.0, RiskValues.asFloat("-3"))
        assertNull(RiskValues.asPrice(-3)); assertEquals(3.0, RiskValues.asPrice("3"))
        assertNull(RiskValues.optionalFloat(null)); assertNull(RiskValues.optionalFloat(true)); assertNull(RiskValues.optionalFloat("x"))
        assertNull(RiskValues.optionalFloat(Double.NEGATIVE_INFINITY)); assertEquals(-2.0, RiskValues.optionalFloat(-2))
    }

    @Test fun `truthiness is Python's`() {
        val t = RiskValues::truthy
        assertFalse(t(null)); assertTrue(t(true)); assertFalse(t(false))
        assertFalse(t(0.0)); assertTrue(t(Double.NaN)); assertTrue(t(-0.5))
        assertFalse(t(0.0f)); assertTrue(t(Float.NaN)); assertTrue(t(1.5f))
        assertFalse(t(0)); assertTrue(t(2L)); assertFalse(t("")); assertTrue(t(" "))
        assertFalse(t(emptyList<Int>())); assertTrue(t(setOf(1))); assertFalse(t(emptyMap<String, Int>())); assertTrue(t(mapOf(1 to 2)))
        assertTrue(t(Any()))
    }

    @Test fun `str and repr of floats`() {
        assertEquals("None", RiskValues.pyStr(null)); assertEquals("True", RiskValues.pyStr(true)); assertEquals("False", RiskValues.pyStr(false))
        assertEquals("2.5", RiskValues.pyStr(2.5f)); assertEquals("5", RiskValues.pyStr(5)); assertEquals("x", RiskValues.pyStr("x"))
        val r = RiskValues::pyRepr
        assertEquals("nan", r(Double.NaN)); assertEquals("inf", r(Double.POSITIVE_INFINITY)); assertEquals("-inf", r(Double.NEGATIVE_INFINITY))
        assertEquals("0.0", r(0.0)); assertEquals("-0.0", r(-0.0))
        assertEquals("5.0", r(5.0)); assertEquals("123.25", r(123.25)); assertEquals("0.0001", r(1e-4))
        assertEquals("1e-05", r(1e-5)); assertEquals("-2.5e-07", r(-2.5e-7)); assertEquals("1e+16", r(1e16)); assertEquals("1.5e+16", r(1.5e16))
        assertEquals("1234567890123456.0", r(1234567890123456.0)); assertEquals("-12.5", r(-12.5))
    }

    @Test fun `side, trail mode and points`() {
        assertEquals(Side.SELL, RiskValues.normaliseSide(Side.SELL))
        for (s in listOf("sell", " S ", "short", -1)) assertEquals(Side.SELL, RiskValues.normaliseSide(s), "$s")
        for (s in listOf(null, "", 0, "BUY", "x", 1)) assertEquals(Side.BUY, RiskValues.normaliseSide(s), "$s")
        assertEquals(TrailMode.STEPPED, RiskValues.normaliseTrailMode(TrailMode.STEPPED))
        for (s in listOf("Stepped", "step", "staircase")) assertEquals(TrailMode.STEPPED, RiskValues.normaliseTrailMode(s))
        for (s in listOf(null, "", "continuous", 3)) assertEquals(TrailMode.CONTINUOUS, RiskValues.normaliseTrailMode(s))
        assertEquals(90.0, RiskValues.stopFromPoints("BUY", 100, 10)); assertEquals(110.0, RiskValues.stopFromPoints("SELL", "100", "10"))
        assertNull(RiskValues.stopFromPoints("BUY", 100, 100)); assertNull(RiskValues.stopFromPoints("BUY", 0, 10)); assertNull(RiskValues.stopFromPoints("BUY", 100, 0))
        assertEquals(110.0, RiskValues.targetFromPoints("BUY", 100, 10)); assertEquals(90.0, RiskValues.targetFromPoints("S", 100, 10))
        assertNull(RiskValues.targetFromPoints("S", 100, 100)); assertNull(RiskValues.targetFromPoints("S", null, 10)); assertNull(RiskValues.targetFromPoints("S", 100, "x"))
        assertEquals(Side.SELL, RiskValues.sideFromQuantity(-1)); assertEquals(Side.BUY, RiskValues.sideFromQuantity(0)); assertEquals(Side.BUY, RiskValues.sideFromQuantity("junk"))
    }

    @Test fun `fixed and compact prices round half-even on the exact binary value`() {
        assertEquals("1.0001", RiskValues.formatPrice(1.00005)); assertEquals("0.0001", RiskValues.formatPrice(0.00015)); assertEquals("2.5", RiskValues.formatPrice(2.5))
        assertEquals("0", RiskValues.formatPrice(0.0)); assertEquals("-0", RiskValues.formatPrice(-0.00001))
        assertEquals("100", RiskValues.formatPrice(100.0)); assertEquals("0.0001", RiskValues.formatPrice(0.0001))
        assertEquals("nan", RiskValues.fixed(Double.NaN, 2)); assertEquals("inf", RiskValues.fixed(Double.POSITIVE_INFINITY, 2))
        assertEquals("-inf", RiskValues.fixed(Double.NEGATIVE_INFINITY, 2)); assertEquals("-0.00", RiskValues.fixed(-0.0, 2))
        assertEquals("-0.00", RiskValues.fixed(-0.001, 2)); assertEquals("0.12", RiskValues.fixed(0.125, 2)); assertEquals("-1.50", RiskValues.fixed(-1.5, 2))
        assertTrue(RiskValues.pyMax(Double.NaN, 2.0).isNaN(), "b > NaN is false: a wins, as in Python")
        assertTrue(RiskValues.pyMin(Double.NaN, 2.0).isNaN())
        assertEquals(1.0, RiskValues.pyMin(3.0, 1.0))
    }

    // ------------------------------------------------------------ protection

    @Test fun `protection ticks and initial stops`() {
        assertEquals(1.234, Protection.onTick(1.234, 0.0, true)); assertEquals(1.234, Protection.onTick(1.234, -1.0, false))
        assertEquals(100.05, Protection.onTick(100.07, 0.05, true), 1e-9); assertEquals(100.1, Protection.onTick(100.07, 0.05, false), 1e-9)
        assertEquals(95.0, Protection.initialStop(1, 100.0, 95.02, 3.0)!!, 1e-9, "a given stop wins over the trail")
        assertEquals(97.0, Protection.initialStop(1, 100.0, null, 3.0)!!, 1e-9)
        assertEquals(103.05, Protection.initialStop(-1, 100.02, null, 3.0)!!, 1e-9)
        assertNull(Protection.initialStop(1, 100.0, null, null))
        assertNull(Protection.initialStop(1, 2.0, null, 5.0), "a stop at or below zero is no stop")
    }

    @Test fun `trailing stops only tighten`() {
        val long = Protection.Spec(1, 95.0, 5.0, null, 100.0)
        assertEquals(long, Protection.next(long, 0.0)); assertEquals(long, Protection.next(long, Double.NaN))
        val up = Protection.next(long, 103.0)
        assertEquals(103.0, up.best); assertEquals(98.0, up.stop!!, 1e-9)
        val down = Protection.next(up, 99.0)
        assertEquals(103.0, down.best); assertEquals(98.0, down.stop!!, 1e-9)
        val short = Protection.Spec(-1, 105.0, 5.0, 90.0, 100.0)
        val sdown = Protection.next(short, 97.0)
        assertEquals(97.0, sdown.best); assertEquals(102.0, sdown.stop!!, 1e-9)
        assertEquals(102.0, Protection.next(sdown, 101.0).stop!!, 1e-9)
        // No stop yet: the first candidate becomes it; a non-positive candidate keeps none.
        assertEquals(96.0, Protection.next(Protection.Spec(1, null, 4.0, null, 90.0), 100.0).stop!!, 1e-9)
        assertNull(Protection.next(Protection.Spec(1, null, 50.0, null, 10.0), 20.0).stop)
        // No trail: only the best price moves.
        val fixed = Protection.next(Protection.Spec(1, 95.0, null, null, 100.0), 110.0)
        assertEquals(110.0, fixed.best); assertEquals(95.0, fixed.stop)
    }

    @Test fun `protection hits and validation`() {
        val long = Protection.Spec(1, 95.0, null, 110.0, 100.0)
        assertNull(Protection.hit(long, 0.0)); assertNull(Protection.hit(long, Double.NaN))
        assertEquals("stop", Protection.hit(long, 95.0)); assertEquals("target", Protection.hit(long, 110.0)); assertNull(Protection.hit(long, 100.0))
        val short = Protection.Spec(-1, 105.0, null, 90.0, 100.0)
        assertEquals("stop", Protection.hit(short, 105.0)); assertEquals("target", Protection.hit(short, 90.0)); assertNull(Protection.hit(short, 100.0))
        assertNull(Protection.hit(Protection.Spec(1, null, null, null, 1.0), 5.0))
        assertEquals("Set a stop, a trailing distance or a target.", Protection.validate(1, 100.0, null, null, null))
        assertEquals("The trailing distance must be above zero.", Protection.validate(1, 100.0, null, 0.0, null))
        assertEquals("The trailing distance must be above zero.", Protection.validate(1, 100.0, null, Double.NaN, null))
        assertEquals("The stop must be below the current price (100.00).", Protection.validate(1, 100.0, 100.0, null, null))
        assertEquals("The stop must be above the current price (100.00).", Protection.validate(-1, 100.0, 99.0, null, null))
        assertEquals("The target must be above the current price (100.00).", Protection.validate(1, 100.0, null, null, 100.0))
        assertEquals("The target must be below the current price (100.00).", Protection.validate(-1, 100.0, null, null, 101.0))
        assertNull(Protection.validate(1, 100.0, 95.0, 2.0, 110.0)); assertNull(Protection.validate(-1, 100.0, 105.0, null, 90.0))
    }

    // ---------------------------------------------------------- account guard

    private val limits = AccountGuard.Limits()
    private fun acct(capital: Double = 100_000.0, equity: Double = 100_000.0, peak: Double = 100_000.0, day: Double = 0.0,
                     holdings: List<AccountGuard.Holding> = emptyList(), orders: Int = 0, minute: Int = 600) =
        AccountGuard.Account(capital, equity, peak, day, holdings, orders, minute)
    private val exp = LocalDate.of(2026, 9, 29)
    private fun opt(sym: String, side: String, qty: Int = 65, price: Double = 100.0, right: String? = "PE") =
        AccountGuard.Order(sym, side, qty, 65, price, "NIFTY", exp, right)

    @Test fun `exits pass everything but the kill switch`() {
        val held = listOf(AccountGuard.Holding("A", 130, 65), AccountGuard.Holding("A", -65, 65))
        val a = acct(day = -1e9, equity = 1.0, holdings = held, orders = 99, minute = 1000)
        assertTrue(AccountGuard.isExit(opt("A", "sell", qty = 65), a))
        assertFalse(AccountGuard.isExit(opt("A", "sell", qty = 130), a), "more than held reverses")
        assertFalse(AccountGuard.isExit(opt("A", "BUY", qty = 65), a), "same side adds")
        assertFalse(AccountGuard.isExit(opt("B", "SELL"), a), "nothing held")
        val shortHeld = acct(holdings = listOf(AccountGuard.Holding("S", -65, 65)))
        assertTrue(AccountGuard.isExit(opt("S", "buy"), shortHeld))
        assertEquals(emptyList(), AccountGuard.refusals(opt("A", "SELL"), a, limits))
        assertEquals(listOf("The kill switch is on: no orders at all until it is cleared."),
            AccountGuard.refusals(opt("A", "SELL"), a, limits.copy(killSwitch = true)))
    }

    @Test fun `every entry limit, at its boundary`() {
        val o = opt("N1", "BUY", price = 10.0)
        assertEquals(emptyList(), AccountGuard.refusals(o, acct(), limits))
        assertTrue(AccountGuard.refusals(o, acct(day = -2000.0), limits).single().startsWith("Daily loss limit reached: today's P&L is Rs -2,000"))
        assertEquals(emptyList(), AccountGuard.refusals(o, acct(day = -1999.0), limits))
        assertEquals(emptyList(), AccountGuard.refusals(o, acct(day = -1e9), limits.copy(maxDailyLoss = 0.0)))
        val dd = AccountGuard.refusals(o, acct(equity = 90_000.0), limits)
        assertEquals(2, dd.size); assertTrue(dd[0].contains("below capital") && dd[1].contains("below its peak"))
        assertTrue(AccountGuard.refusals(o, acct(equity = 95_000.0, peak = 110_000.0), limits).single().contains("below its peak Rs 110,000"))
        assertEquals(emptyList(), AccountGuard.refusals(o, acct(equity = 1.0), limits.copy(maxDrawdownPct = 0.0)))
        assertEquals(emptyList(), AccountGuard.refusals(o, acct(capital = 0.0, peak = 0.0, equity = -5.0), limits), "no capital and no peak: nothing to measure")
        val three = (1..3).map { AccountGuard.Holding("H$it", 65, 65) } + AccountGuard.Holding("Z", 0, 65)
        assertEquals("Open positions limit: 3 already held (limit 3).", AccountGuard.refusals(o, acct(holdings = three), limits).single())
        assertEquals(emptyList(), AccountGuard.refusals(o.copy(symbol = "H1"), acct(holdings = three), limits), "adding to a held one")
        assertEquals(emptyList(), AccountGuard.refusals(o, acct(holdings = three), limits.copy(maxOpenPositions = 0)))
        assertEquals("Trades per day limit: 10 sent today (limit 10).", AccountGuard.refusals(o, acct(orders = 10), limits).single())
        assertEquals(emptyList(), AccountGuard.refusals(o, acct(orders = 10), limits.copy(maxTradesPerDay = 0)))
        assertTrue(AccountGuard.refusals(o.copy(price = 8000.0), acct(), limits).single().startsWith("Order value Rs 520,000 is over the Rs 500,000 limit"))
        assertEquals(emptyList(), AccountGuard.refusals(o.copy(price = 8000.0), acct(), limits.copy(maxOrderValue = 0.0)))
        assertEquals("No new entries after 14:30.", AccountGuard.refusals(o, acct(minute = 870), limits).single())
        assertEquals(emptyList(), AccountGuard.refusals(o, acct(minute = 869), limits))
        assertEquals(emptyList(), AccountGuard.refusals(o, acct(minute = 1400), limits.copy(entryCutoffMinute = null)))
    }

    @Test fun `lots and exposure per symbol are judged after the order`() {
        val held = listOf(AccountGuard.Holding("N1", 130, 65))
        val add = opt("N1", "BUY", price = 10.0)
        assertEquals("Exposure limit: N1 would be 3 lots (limit 2).", AccountGuard.refusals(add, acct(holdings = held), limits).single())
        assertEquals(emptyList(), AccountGuard.refusals(add, acct(holdings = held), limits.copy(maxLotsPerSymbol = 0)))
        assertEquals(emptyList(), AccountGuard.refusals(add.copy(lot = 0), acct(holdings = held), limits))
        // Selling through a long into a short: |after| counts.
        val flip = opt("N1", "SELL", qty = 390, price = 10.0, right = null)
        assertTrue(AccountGuard.refusals(flip, acct(holdings = held), limits).contains("Exposure limit: N1 would be 4 lots (limit 2)."))
        val capped = limits.copy(maxSymbolExposure = 2000.0)
        val longN2 = acct(holdings = listOf(AccountGuard.Holding("N2", 65, 65)))
        assertEquals(emptyList(), AccountGuard.refusals(opt("N2", "BUY", price = 10.0, right = null), longN2, capped), "Rs 1,300 is under the cap")
        assertEquals("Exposure limit: N2 would be Rs 3,900 held (limit Rs 2,000).",
            AccountGuard.refusals(opt("N2", "BUY", price = 30.0, right = null), longN2, capped).single())
        // A short held counts by its size too.
        val shortN2 = acct(holdings = listOf(AccountGuard.Holding("N2", -65, 65)))
        assertTrue(AccountGuard.refusals(opt("N2", "BUY", qty = 130, price = 30.0, right = null), shortN2, capped.copy(maxLotsPerSymbol = 0)).single().contains("Rs 5,850"))
        assertEquals(emptyList(), AccountGuard.refusals(opt("N2", "BUY", price = 0.0), acct(), capped))
    }

    @Test fun `naked shorts need a bought option of the same series`() {
        val sell = opt("P24500", "SELL", price = 10.0)
        assertTrue(AccountGuard.refusals(sell, acct(), limits).single().startsWith("Naked short: selling P24500 needs a bought PE"))
        assertEquals(emptyList(), AccountGuard.refusals(sell, acct(), limits.copy(blockNakedShort = false)))
        assertEquals(emptyList(), AccountGuard.refusals(sell.copy(right = null), acct(), limits), "not an option")
        val wing = AccountGuard.Holding("P24300", 65, 65, "NIFTY", exp, "PE")
        assertEquals(emptyList(), AccountGuard.refusals(sell, acct(holdings = listOf(wing)), limits))
        // A wing of another expiry, type or underlying does not cover it.
        for (w in listOf(wing.copy(expiry = exp.plusDays(7)), wing.copy(right = "CE"), wing.copy(underlying = "BANKNIFTY"))) {
            assertTrue(AccountGuard.refusals(sell, acct(holdings = listOf(w)), limits).any { it.startsWith("Naked short") })
        }
        // Two lots short against one lot of wing.
        assertTrue(AccountGuard.refusals(sell.copy(qty = 130), acct(holdings = listOf(wing)), limits).any { it.startsWith("Naked short") })
        // Selling the wing itself beyond what is held never hedges itself.
        assertTrue(AccountGuard.refusals(opt("P24300", "SELL", qty = 130, price = 10.0), acct(holdings = listOf(wing)), limits).any { it.startsWith("Naked short") })
        // A buy is never a naked short.
        assertEquals(emptyList(), AccountGuard.refusals(opt("P24500", "BUY", price = 10.0), acct(), limits))
        assertEquals(12.0, AccountGuard.nextPeak(10.0, 12.0)); assertEquals(12.0, AccountGuard.nextPeak(12.0, 10.0))
    }

    @Ignore("BUG: AccountGuard.refusals fails OPEN on non-finite account figures: a NaN dayPnl or equity (e.g. an unpriced position marked NaN) makes every <=/>= comparison false, so the daily-loss and drawdown limits silently allow new entries; a NaN order price likewise skips the order-value limit")
    @Test fun `BUG - unknown account figures refuse new entries`() {
        val o = opt("N1", "BUY", price = 10.0)
        assertTrue(AccountGuard.refusals(o, acct(day = Double.NaN), limits).isNotEmpty(), "NaN day P&L")
        assertTrue(AccountGuard.refusals(o, acct(equity = Double.NaN), limits).isNotEmpty(), "NaN equity")
        assertTrue(AccountGuard.refusals(o.copy(price = Double.NaN), acct(), limits).isNotEmpty(), "NaN price")
    }

    // ------------------------------------------------------------------ risk

    @Test fun `trail edge cases - non-finite step, stepped anchors and short continuous trails`() {
        val base = PositionRisk("p", Side.BUY, 100.0, 1.0, initialStopPrice = 95.0, trailingEnabled = true, trailStep = Double.POSITIVE_INFINITY, trailTrigger = 1.0)
        assertFalse(Risk.evaluatePosition(base, 110.0).trailArmed, "an infinite step cannot trail")
        // Short, continuous: stop follows the low at a step's distance.
        val short = PositionRisk("s", Side.SELL, 100.0, 2.0, stopPrice = 110.0, trailingEnabled = true, trailStep = 3.0, trailTrigger = 2.0)
        val d = Risk.evaluatePosition(short, 95.0)
        assertEquals(98.0, d.stopPrice); assertTrue(d.stopMoved); assertEquals(10.0, d.pnl)
        assertTrue(d.detail.startsWith("trailing stop moved from 110 to 98"))
        // Stepped with no anchor at all: nothing to advance.
        val noAnchor = PositionRisk("n", Side.BUY, 100.0, 1.0, trailingEnabled = true, trailStep = 2.0, trailTrigger = 2.0, trailMode = TrailMode.STEPPED)
        val dn = Risk.evaluatePosition(noAnchor, 110.0)
        assertTrue(dn.trailArmed); assertNull(dn.stopPrice); assertFalse(dn.stopMoved)
        // Stepped, trigger zero: armed on any profit, but no step boundary.
        val zeroTrig = noAnchor.copy(initialStopPrice = 95.0, trailTrigger = 0.0)
        assertEquals(95.0, Risk.evaluatePosition(zeroTrig, 110.0).stopPrice)
        // Stepped short anchored on the live stop; the candidate is clamped to the low.
        val ss = PositionRisk("t", Side.SELL, 100.0, 1.0, stopPrice = 104.0, trailingEnabled = true, trailStep = 10.0, trailTrigger = 2.0, trailMode = TrailMode.STEPPED)
        assertEquals(97.0, Risk.evaluatePosition(ss, 97.0).stopPrice, "104 - 10 = 94 clamped up to the low 97")
        // Target on a short and a stop moving from none.
        val tgt = PositionRisk("u", Side.SELL, 100.0, 1.0, targetPrice = 90.0)
        assertEquals(BreachReason.TARGET, Risk.evaluatePosition(tgt, 90.0).reason)
        val fromNone = PositionRisk("v", Side.BUY, 100.0, 1.0, trailingEnabled = true, trailStep = 3.0, trailTrigger = 1.0)
        assertTrue(Risk.evaluatePosition(fromNone, 105.0).detail.startsWith("trailing stop moved from none to 102"))
        // Entry unusable: the extreme seeds from the tick and no P&L is booked.
        val noEntry = PositionRisk("w", Side.SELL, 0.0, 1.0)
        val dw = Risk.evaluatePosition(noEntry, 50.0)
        assertEquals(50.0, dw.lowestPrice); assertEquals(0.0, dw.pnl)
        assertEquals(0.0, Risk.evaluatePosition(PositionRisk("x", Side.BUY, 100.0, 0.0), 120.0).pnl)
    }

    @Test fun `validation with no reference price and default arguments`() {
        assertEquals(listOf("entry price is missing or not a positive number"), Risk.validatePosition(PositionRisk(stopPrice = 5.0)))
        assertEquals(emptyList(), Risk.validatePosition(PositionRisk(entryPrice = 100.0, stopPrice = 95.0, targetPrice = 110.0)))
        val s = PositionRisk(side = Side.SELL, entryPrice = 100.0, stopPrice = 99.0, targetPrice = 101.0)
        assertEquals(2, Risk.validatePosition(s).size)
        assertEquals(listOf("target 101 is at or above 98.5 on a short position, which exits immediately"), Risk.validatePosition(s, 98.5))
        val stepped = PositionRisk(entryPrice = 100.0, trailingEnabled = true, trailStep = 0.0, trailMode = TrailMode.STEPPED, trailTrigger = 0.0)
        assertEquals(2, Risk.validatePosition(stepped).size)
    }

    @Test fun `lock profit whose floor sits above its threshold triggers on arming and says why`() {
        val r = AggregateRisk(lockProfitAt = 100.0, lockProfitFloor = 150.0)
        val d = Risk.evaluateAggregate(r, 0.0, 120.0)
        assertEquals(BreachReason.LOCK_PROFIT, d.reason)
        assertTrue(d.detail.startsWith("lock profit floor 150 is above its arming threshold 100"))
        // An armed flag without a configuration never closes.
        val stale = AggregateRisk(lockArmed = true, lockFloor = 500.0)
        assertFalse(Risk.evaluateAggregate(stale, 0.0, 10.0).breached)
        // Trail step of zero does not ratchet from the peak.
        val flat = AggregateRisk(lockProfitAt = 100.0, lockProfitFloor = 50.0, lockTrailStep = 0.0, lockArmed = true, lockFloor = 50.0, peakPnl = 1000.0)
        val df = Risk.evaluateAggregate(flat, 0.0, 900.0)
        assertEquals(50.0, df.lockFloor); assertFalse(df.lockFloorRaised); assertEquals("", df.detail)
        // Stop bypassed by trail-to-entry: the combined stop is skipped, the target still applies.
        val bypass = AggregateRisk(combinedStoploss = -100.0, combinedTarget = 50.0, stopBypassed = true)
        assertFalse(Risk.evaluateAggregate(bypass, -500.0, 0.0).breached)
        assertEquals(BreachReason.COMBINED_TARGET, Risk.evaluateAggregate(bypass, 50.0, 0.0).reason)
    }

    @Test fun `trail stops to entry with defaults and loose maps`() {
        val d = Risk.trailStopsToEntry(listOf(
            PositionRisk("a", Side.BUY, 100.0, 1.0, stopPrice = 95.0),
            mapOf("identifier" to "b", "side" to "SELL", "entry_price" to 100.0, "current_sl" to 99.0),
            mapOf("identifier" to "c", "entry_price" to 0),
        ))
        assertEquals(listOf("a"), d.moves.map { it.identifier })
        assertEquals(listOf("b"), d.skippedNotImproving); assertEquals(listOf("c"), d.skippedNoEntry)
        assertEquals("moved 1 stop(s) to entry", d.detail)
        val none = Risk.trailStopsToEntry(emptyList())
        assertEquals("", none.detail); assertTrue(none.moves.isEmpty())
        val through = Risk.trailStopsToEntry(listOf(PositionRisk("a", Side.BUY, 100.0, 1.0), PositionRisk("b", Side.SELL, 100.0, 1.0)),
            lastPrices = mapOf("a" to 99.0, "b" to 101.0))
        assertEquals("no stop moved to entry", through.detail); assertEquals(listOf("a", "b"), through.skippedThroughPrice)
        val mixed = Risk.trailStopsToEntry(listOf(PositionRisk("a", Side.BUY, 100.0, 1.0), PositionRisk("b", Side.SELL, 100.0, 1.0)),
            lastPrices = mapOf("a" to 99.0, "b" to 99.0))
        assertEquals("moved 1 stop(s) to entry; left 1 alone because entry is already through the market", mixed.detail)
    }

    @Test fun `aggregate pnl over loose maps and closed positions`() {
        val s = Risk.aggregatePnl(listOf(
            PositionPnL("a", Side.BUY, 100.0, 2.0, 110.0, realizedPnl = 5.0),
            mapOf("side" to "SELL", "entry_price" to 100.0, "quantity" to 1.0, "last_price" to 90.0),
            PositionPnL("c", Side.BUY, 100.0, 1.0, null),
            PositionPnL("d", Side.BUY, 100.0, 1.0, 500.0, closed = true, realizedPnl = -3.0),
            PositionPnL(),
        ))
        assertEquals(2.0, s.realized); assertEquals(30.0, s.unrealized); assertEquals(32.0, s.total)
        assertEquals(2, s.priced); assertEquals(2, s.unpriced)
        assertEquals(0.0, Risk.positionPnl("BUY", 100.0, 0.0, 110.0))
        assertEquals(10.0, Risk.positionPnl("BUY", 100.0, -1.0, 110.0), "quantity magnitude")
        assertEquals(0.0, Risk.positionPnl("BUY", "x", 1.0, 110.0))
    }

    @Test fun `model defaults construct`() {
        val r = PositionRisk()
        assertEquals("", r.identifier); assertTrue(r.isLong); assertNull(r.effectiveStop); assertEquals(DEFAULT_TRAIL_TRIGGER, r.trailTrigger)
        assertEquals(0.0, PnLSummary().total); assertFalse(AggregateRisk().lockArmed); assertEquals("", AggregateDecision().detail)
        assertEquals(emptyList(), TrailToEntryDecision().moves); assertTrue(PositionDecision().evaluated)
        assertEquals(StopMove("a", null, 1.0), StopMove("a", null, 1.0))
    }
}
