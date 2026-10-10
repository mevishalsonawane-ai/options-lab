package com.optionslab.engine.orb

import com.optionslab.engine.sandbox.Instrument
import com.optionslab.engine.sandbox.InstrumentMaster
import com.optionslab.engine.sandbox.OrderRequest
import com.optionslab.engine.sandbox.OrderStatus
import com.optionslab.engine.sandbox.Quote
import com.optionslab.engine.sandbox.Sandbox
import com.optionslab.engine.sandbox.SandboxConfig
import com.optionslab.engine.sandbox.SandboxRules
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The profit lock as the resting stop (07 Oct, research/HUNT_H20.md F1-F6): it moves the stop up, never down. */
class RestingLockTest {
    @Test fun theRungsAreOnEachArmsOwnTarget() {
        // ORB / ORB Fresh / Range Fade (+40): +10 / +20 / +30. ORB Sweep (+80): +20 / +40 / +60 (F4: no rule change).
        assertEquals(listOf(10.0 to 0.0, 20.0 to 10.0, 30.0 to 20.0), ProfitLock.rungs(OrbRules.TARGET_POINTS))
        assertEquals(listOf(20.0 to 0.0, 40.0 to 20.0, 60.0 to 40.0), ProfitLock.rungs(ProfitLock.targetOf(SweepRules.ARM)!!))
        assertEquals("+10 → breakeven, +20 → +10, +30 → +20", ProfitLock.describeRungs(40.0))
        assertEquals("+20 → breakeven, +40 → +20, +60 → +40", ProfitLock.describeRungs(80.0))
        assertEquals("+15 → breakeven, +30 → +15, +45 → +30", ProfitLock.describeRungs(60.0))
        assertEquals("+7.5 → breakeven, +15 → +7.5, +22.5 → +15", ProfitLock.describeRungs(30.0))
    }

    @Test fun aLockLevelIsPutOnTheTickRoundedUp() {
        assertEquals(302.1, ProfitLock.onTick(302.07))
        assertEquals(310.0, ProfitLock.onTick(310.0))
        assertEquals(310.0, ProfitLock.onTick(310.0000000001))
        assertEquals(1.5, ProfitLock.onTick(1.26, 0.5))
    }

    @Test fun reachingARungMovesTheRestingStopUpToTheLock() {
        // Bought at 300, the -40 stop rests at 260. +12 earns breakeven after charges; +22 earns +10; +31 earns +20.
        val c = ProfitLock.roundTripPerUnit(300.0, 30)
        assertNull(ProfitLock.raise(300.0, 40.0, 309.0, 260.0, 309.0, c), "below the first rung: the stop stays")
        assertEquals(ProfitLock.onTick(300.0 + c), ProfitLock.raise(300.0, 40.0, 312.0, 260.0, 311.0, c))
        assertEquals(310.0, ProfitLock.raise(300.0, 40.0, 322.0, 300.0 + c, 318.0, c))
        assertEquals(320.0, ProfitLock.raise(300.0, 40.0, 331.0, 310.0, 330.0, c))
        // ORB Sweep: +20 is its first rung, +40 its second.
        assertNull(ProfitLock.raise(300.0, 80.0, 312.0, 260.0, 311.0, c))
        assertEquals(320.0, ProfitLock.raise(300.0, 80.0, 341.0, 260.0, 335.0, c))
    }

    @Test fun theStopIsNeverMovedDown() {
        // A lower best (impossible, but asked) or the same rung again: no move. A stop resting above the lock: no move.
        assertNull(ProfitLock.raise(300.0, 40.0, 322.0, 310.0, 318.0))
        assertNull(ProfitLock.raise(300.0, 40.0, 312.0, 310.0, 318.0))
        assertNull(ProfitLock.raise(300.0, 40.0, 331.0, 325.0, 330.0))
        // Over a whole walk of prices the stop only ever rises.
        var stop = 260.0
        var peak = 300.0
        val path = listOf(305.0, 312.0, 304.0, 321.0, 315.0, 333.0, 329.0, 340.0, 301.0)
        for (px in path) {
            ProfitLock.raise(300.0, 40.0, peak, stop, px)?.let { assertTrue(it > stop, "$stop -> $it"); stop = it }
            peak = ProfitLock.nextPeak(peak, px, null)
        }
        assertEquals(320.0, stop)
    }

    @Test fun aRefusedModifyLeavesTheOldStopAndIsAskedForAgainOnTheNextLook() {
        // The modify to 310 was refused: the stop still rests at 260, so the next look asks for 310 again (and for a
        // higher rung if one was earned meanwhile); once it went through, nothing more is asked at that rung.
        val first = ProfitLock.raise(300.0, 40.0, 322.0, 260.0, 318.0)
        assertEquals(310.0, first)
        assertEquals(first, ProfitLock.raise(300.0, 40.0, 322.0, 260.0, 317.0))
        assertEquals(320.0, ProfitLock.raise(300.0, 40.0, 332.0, 260.0, 325.0))
        assertNull(ProfitLock.raise(300.0, 40.0, 322.0, first, 317.0))
    }

    @Test fun eachArmTakesItsOwnTargetPaperAndLive() {
        val t = LocalDateTime.of(2026, 9, 21, 11, 0)
        assertEquals(80.0, OrbRules.targetFor(SweepRules.ARM))
        assertEquals(40.0, OrbRules.targetFor(OrbRules.ORB))
        assertEquals(40.0, OrbRules.targetFor(RangeFadeRules.ARM))
        assertEquals(80.0, ArmsBacktest.targetOf(SweepRules.ARM))
        // +45: the ORB's target, not ORB Sweep's (F5: the live path used the ORB's +40 for every arm).
        assertEquals("target", OrbRules.exitReasonFor(OrbRules.ORB, 300.0, 345.0, t))
        assertNull(OrbRules.exitReasonFor(SweepRules.ARM, 300.0, 345.0, t))
        assertEquals("target", OrbRules.exitReasonFor(SweepRules.ARM, 300.0, 380.0, t))
        assertEquals("stop", OrbRules.exitReasonFor(SweepRules.ARM, 300.0, 260.0, t))
        assertEquals("session_end", OrbRules.exitReasonFor(RangeFadeRules.ARM, 300.0, 300.0, t.withHour(15).withMinute(10)))
    }

    @Test fun aStopIsNotRaisedToOrAboveThePriceNow() {
        // The price is already at the lock: the stop is left where it is and the app's own check sells it.
        assertNull(ProfitLock.raise(300.0, 40.0, 322.0, 260.0, 310.0))
        assertNull(ProfitLock.raise(300.0, 40.0, 322.0, 260.0, 305.0))
        assertNull(ProfitLock.raise(300.0, 40.0, 322.0, null, 305.0))
        assertEquals(310.0, ProfitLock.raise(300.0, 40.0, 322.0, null, 310.5), "no stop at all: one at the lock")
    }

    @Test fun theBestPriceComesFromTheHighsAndCountsFromTheNextLook() {
        // A spike to +15 inside a minute that closed at +6: the candle's high earns the rung, the close would not.
        val before = 300.0
        assertFalse(ProfitLock.exits(300.0, 40.0, before, 306.0), "this look still uses the best from before it")
        val peak = ProfitLock.nextPeak(before, 306.0, 315.0)
        assertEquals(315.0, peak)
        assertEquals(306.0, ProfitLock.nextPeak(before, 306.0, null))
        assertEquals(300.0, ProfitLock.nextPeak(before, null, Double.NaN))
        assertEquals(301.0, ProfitLock.nextPeak(before, null, 301.0))
        // From the next look on, the price back at the price paid is sold.
        assertTrue(ProfitLock.exits(300.0, 40.0, peak, 300.0))
        assertEquals(300.0, ProfitLock.raise(300.0, 40.0, peak, 260.0, 306.0))
    }

    @Test fun aFailedExitPutsTheStopBackAtTheLockNotTheMinus40() {
        assertEquals(260.0, ProfitLock.restingStop(300.0, 260.0, 40.0, 305.0), "below a rung: the -40")
        assertEquals(310.0, ProfitLock.restingStop(300.0, 260.0, 40.0, 322.0), "+22 earned +10")
        assertEquals(260.0, ProfitLock.restingStop(300.0, 260.0, null, 322.0), "no ladder: the stop it had")
        assertEquals(310.0, ProfitLock.restingStop(300.0, null, 40.0, 322.0), "no -40 (cheap option): the lock alone")
        assertNull(ProfitLock.restingStop(300.0, null, 40.0, 301.0))
        assertNull(ProfitLock.restingStop(300.0, null, null, 340.0))
    }

    @Test fun aRestingSellStopFillsAtItsTriggerOrAGapOpen() {
        assertEquals(310.0, ProfitLock.sellStopFill(310.0, 318.0, 305.0), "touched inside the bar: at the trigger")
        assertEquals(296.0, ProfitLock.sellStopFill(310.0, 296.0, 290.0), "opened under it: at the open")
        assertEquals(310.0, ProfitLock.sellStopFill(310.0, 315.0, 310.0), "a touch fills")
        assertNull(ProfitLock.sellStopFill(310.0, 318.0, 310.5))
    }

    // ---- the paper engine fills a raised stop as a resting SL-M --------------------------------------------------------

    private val ist = SandboxRules.IST
    private val t = ZonedDateTime.of(LocalDateTime.of(LocalDate.of(2026, 9, 21), LocalTime.of(10, 40)), ist)
    private val sym = "BANKNIFTY29SEP2654000CE"
    private val sb = Sandbox(SandboxConfig(stopSlippageBps = BigDecimal("10"), chargesEnabled = true),
        InstrumentMaster.of(listOf(Instrument(sym, "NFO", "OPTIDX", lotSize = 30, expiry = LocalDate.of(2026, 9, 29), strike = 54000.0))))

    @Test fun thePaperBookMovesItsStopUpAndFillsItAtTheStop() {
        var s = sb.newState(t)
        s = sb.place(s, OrderRequest(sym, "NFO", "BUY", 30, "MARKET", "MIS"), Quote(300.0), t).state
        s = sb.place(s, OrderRequest(sym, "NFO", "SELL", 30, "SL-M", "MIS", null, 260.0), Quote(300.0), t).state
        val stop = s.orders.last()
        assertEquals(OrderStatus.TRIGGER_PENDING, stop.status)
        // Modified up to the lock (310), never cancelled: the same order id rests at the new trigger.
        s = sb.modify(s, stop.orderId, com.optionslab.engine.sandbox.OrderChange(null, null, 310.0), t).state
        assertEquals(0, BigDecimal("310").compareTo(s.orders.single { it.orderId == stop.orderId }.triggerPrice))
        // A minute that touched 305 between two looks: filled at the stop (less the 10 bps stop slippage), not at the price now.
        val filled = sb.fillRestingStop(s, stop.orderId, 310.0, t.plusMinutes(1))
        assertTrue(filled.result.ok)
        val o = filled.state.orders.single { it.orderId == stop.orderId }
        assertEquals(OrderStatus.COMPLETE, o.status)
        assertEquals(0, BigDecimal("309.69").compareTo(o.averagePrice))
        assertTrue(filled.state.positions.single().quantity == 0)
        // Only a resting SELL SL-M can be filled this way; anything else is refused untouched.
        assertFalse(sb.fillRestingStop(filled.state, stop.orderId, 310.0, t).result.ok, "already filled")
        assertFalse(sb.fillRestingStop(s, "nope", 310.0, t).result.ok)
        assertFalse(sb.fillRestingStop(s, stop.orderId, Double.NaN, t).result.ok)
        assertFalse(sb.fillRestingStop(s, stop.orderId, 0.0, t).result.ok)
        val buy = s.orders.first().orderId
        assertFalse(sb.fillRestingStop(s, buy, 310.0, t).result.ok, "a filled buy")
        val lim = sb.place(s, OrderRequest(sym, "NFO", "SELL", 30, "LIMIT", "MIS", 400.0), Quote(300.0), t).state
        assertFalse(sb.fillRestingStop(lim, lim.orders.last().orderId, 400.0, t).result.ok, "a resting limit")
        val slmBuy = sb.place(s, OrderRequest(sym, "NFO", "BUY", 30, "SL-M", "MIS", null, 350.0), Quote(300.0), t).state
        assertEquals(OrderStatus.TRIGGER_PENDING, slmBuy.orders.last().status)
        assertFalse(sb.fillRestingStop(slmBuy, slmBuy.orders.last().orderId, 350.0, t).result.ok, "a buy stop")
        val sl = sb.place(s, OrderRequest(sym, "NFO", "SELL", 30, "SL", "MIS", 250.0, 255.0), Quote(300.0), t).state
        assertFalse(sb.fillRestingStop(sl, sl.orders.last().orderId, 255.0, t).result.ok, "an SL (with a limit)")
    }
}
