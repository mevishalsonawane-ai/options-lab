package com.optionslab.engine.orb

import com.optionslab.engine.orb.LiveMirror.Look
import com.optionslab.engine.orb.LiveMirror.Step
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 14 Nov 2026 (Boss: "Make it 2k profit and 1k loss fix"; CLAUDE.md's fifth exception, failed gates): [FixedRupee]'s levels for the
 * whole position (lots x lot size), rounded to the tick on the safe side, the stop only where it is tighter than the arm's own, and
 * how the passed arms' walk ([PassedArms.exitOnBars]) and the live mirror's decision ([LiveMirror.decide]) use them.
 */
class FixedRupeeTest {
    private val day = LocalDate.of(2026, 11, 16)
    private fun t(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private fun bar(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Bar(t(h, m), o, hi, lo, c)

    // ---- the levels -----------------------------------------------------------------------------------------------------------

    @Test fun oneTwoAndThreeLotsOfNiftyAtSeventyFive() {
        // 1 lot of 75 bought at 200: -1,000 / 75 = -13.333 -> 186.6667, up to the tick 186.70; +2,000 / 75 = 26.667 -> 226.70.
        assertEquals(186.70, FixedRupee.stopLevel(200.0, 75)!!, 1e-9)
        assertEquals(226.70, FixedRupee.targetLevel(200.0, 75)!!, 1e-9)
        // 2 lots (150): -6.667 -> 193.35; +13.333 -> 213.35.
        assertEquals(193.35, FixedRupee.stopLevel(200.0, 150)!!, 1e-9)
        assertEquals(213.35, FixedRupee.targetLevel(200.0, 150)!!, 1e-9)
        // 3 lots (225): -4.444 -> 195.56 -> 195.60; +8.889 -> 208.889 -> 208.90.
        assertEquals(195.60, FixedRupee.stopLevel(200.0, 225)!!, 1e-9)
        assertEquals(208.90, FixedRupee.targetLevel(200.0, 225)!!, 1e-9)
        // The safe side: the loss at the stop is never more than Rs 1,000, the profit at the target never less than Rs 2,000.
        for (q in listOf(15, 20, 30, 35, 65, 75, 120, 140, 150, 225, 450)) {
            val s = FixedRupee.stopLevel(321.15, q)!!; val g = FixedRupee.targetLevel(321.15, q)!!
            assertTrue((321.15 - s) * q <= FixedRupee.STOP_RS + 1e-6, "loss at the stop for $q: ${(321.15 - s) * q}")
            assertTrue((321.15 - s) * q > FixedRupee.STOP_RS - 0.05 * q - 1e-6, "within a tick of Rs 1,000 for $q")
            assertTrue((g - 321.15) * q >= FixedRupee.TARGET_RS - 1e-6, "profit at the target for $q: ${(g - 321.15) * q}")
            assertTrue((g - 321.15) * q < FixedRupee.TARGET_RS + 0.05 * q + 1e-6, "within a tick of Rs 2,000 for $q")
        }
    }

    @Test fun aLevelAlreadyOnATickStaysWhereItIs() {
        // 1 lot of 50 at 120: -20 exactly -> 100.00; +40 -> 160.00 (never pushed a tick on).
        assertEquals(100.0, FixedRupee.stopLevel(120.0, 50)!!, 1e-9)
        assertEquals(160.0, FixedRupee.targetLevel(120.0, 50)!!, 1e-9)
        // Another tick size is honoured.
        assertEquals(100.10, FixedRupee.stopLevel(120.07, 50, 0.10)!!, 1e-9)
    }

    @Test fun nothingWithNoFillNoQuantityOrNoTickAndNoStopUnderATick() {
        for (e in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(FixedRupee.stopLevel(e, 75), "entry $e"); assertNull(FixedRupee.targetLevel(e, 75), "entry $e")
        }
        for (q in listOf(0, -75)) { assertNull(FixedRupee.stopLevel(200.0, q)); assertNull(FixedRupee.targetLevel(200.0, q)) }
        for (tk in listOf(0.0, -0.05, Double.NaN)) { assertNull(FixedRupee.stopLevel(200.0, 75, tk)); assertNull(FixedRupee.targetLevel(200.0, 75, tk)) }
        // A cheap option: -1,000 / 75 = -13.33 under a 12.00 fill - no premium left to rest a stop at; the target still stands.
        assertNull(FixedRupee.stopLevel(12.0, 75))
        assertEquals(38.70, FixedRupee.targetLevel(12.0, 75)!!, 1e-9)
        // One tick exactly is no stop either.
        assertNull(FixedRupee.stopLevel(13.38, 75))
    }

    @Test fun theStopOnlyEverTightens() {
        assertEquals(186.7, FixedRupee.tighterStop(170.0, 186.7))
        assertEquals(190.0, FixedRupee.tighterStop(190.0, 186.7), "the arm's own stop already tighter wins")
        assertEquals(186.7, FixedRupee.tighterStop(null, 186.7))
        assertEquals(170.0, FixedRupee.tighterStop(170.0, null))
        assertNull(FixedRupee.tighterStop(null, null))
        assertTrue(FixedRupee.fixedIsTighter(170.0, 186.7)); assertTrue(FixedRupee.fixedIsTighter(null, 186.7))
        assertFalse(FixedRupee.fixedIsTighter(190.0, 186.7)); assertFalse(FixedRupee.fixedIsTighter(186.7, 186.7)); assertFalse(FixedRupee.fixedIsTighter(170.0, null))
    }

    @Test fun hitsNeedAPrice() {
        assertTrue(FixedRupee.targetHit(226.7, 226.7)); assertTrue(FixedRupee.targetHit(230.0, 226.7)); assertFalse(FixedRupee.targetHit(226.65, 226.7))
        for (p in listOf(null, 0.0, -1.0, Double.NaN)) { assertFalse(FixedRupee.targetHit(p, 226.7), "$p"); assertFalse(FixedRupee.stopHit(p, 186.7), "$p") }
        assertFalse(FixedRupee.targetHit(300.0, null)); assertFalse(FixedRupee.stopHit(100.0, null))
        assertTrue(FixedRupee.stopHit(186.7, 186.7)); assertTrue(FixedRupee.stopHit(180.0, 186.7)); assertFalse(FixedRupee.stopHit(186.75, 186.7))
    }

    @Test fun aRestingStopAtTheLevelIsTheFixedOne() {
        assertTrue(FixedRupee.isFixedStop(186.7, 200.0, 75))
        assertFalse(FixedRupee.isFixedStop(170.0, 200.0, 75))
        assertFalse(FixedRupee.isFixedStop(null, 200.0, 75))
        assertFalse(FixedRupee.isFixedStop(186.7, 200.0, 0))
    }

    @Test fun onlyLiquidityR51AndR72() {
        assertTrue(FixedRupee.covers(DonchianArm.SOURCE)); assertTrue(FixedRupee.covers(BreadthDonchianArm.SOURCE))
        assertTrue(FixedRupee.covers(LiquidityRules.ARM.source))
        for (b in LiquidityRules.BOOKS) assertTrue(FixedRupee.covers(b.source), b.source)
        for (other in listOf(OovArm.SOURCE, Solo2.SOURCE, S5Trail.SOURCE, CisdNet.SOURCE, Ft3Rules.SOURCE, NightRules.SOURCE, HeroRules.ARM.source,
            SweepRules.ARM.source, RangeFadeRules.ARM.source, "orb", "jarvis", "manual", "")) assertFalse(FixedRupee.covers(other), other)
        assertTrue(FixedRupee.LABEL.contains("failed gates, Boss's exception")); assertTrue(FixedRupee.SWITCH.contains("Liquidity, R51, R72"))
        assertTrue(FixedRupee.RECORD.contains("R152")); assertTrue(FixedRupee.RULE.contains("tighter"))
    }

    // ---- the passed arms' walk ---------------------------------------------------------------------------------------------------

    /** R51's exits (-15% stop, its own target) with the ₹2k / ₹1k for 1 lot of 75 at 200 (186.70 / 226.70). */
    private fun r51(fixed: Boolean = true) = DonchianArm.EXITS.let { x ->
        if (!fixed) x else x.copy(fixedStop = FixedRupee.stopLevel(200.0, 75), fixedTarget = FixedRupee.targetLevel(200.0, 75))
    }

    @Test fun theWalkSellsAtTheFixedStopWhereItIsTighter() {
        val bars = listOf(bar(10, 0, 200.0, 201.0, 199.0, 200.0), bar(10, 1, 199.0, 199.0, 186.0, 187.0))
        val w = PassedArms.exitOnBars(200.0, t(10, 0), bars, null, t(10, 2), r51())
        assertEquals(PassedArms.ExitKind.FIXED_STOP, w.exit!!.kind)
        assertEquals(186.7, w.exit!!.price, 1e-9)
        assertEquals(FixedRupee.WHY_STOP, w.exit!!.kind.why)
        // Off: the same minutes hold (the arm's -15% stop is at 170).
        assertNull(PassedArms.exitOnBars(200.0, t(10, 0), bars, null, t(10, 2), r51(false)).exit)
        // A gap through it fills at the minute's open (as any resting stop).
        val gap = PassedArms.exitOnBars(200.0, t(9, 59), listOf(bar(10, 0, 180.0, 181.0, 179.0, 180.0)), null, t(10, 1), r51())
        assertEquals(180.0, gap.exit!!.price, 1e-9)
    }

    /** Review: the entry minute's low or high may come from before the fill - the fixed levels are judged from the next minute on. */
    @Test fun theEntryMinutesWickNeverSetsOffTheFixedLevels() {
        val wick = listOf(bar(10, 0, 200.0, 228.0, 186.0, 200.0))
        assertNull(PassedArms.exitOnBars(200.0, t(10, 0), wick, null, t(10, 1), r51()).exit)
        // The arm's own levels keep the research's rule (the entry minute counts).
        assertEquals(PassedArms.ExitKind.STOP, PassedArms.exitOnBars(200.0, t(10, 0), listOf(bar(10, 0, 200.0, 201.0, 169.0, 170.0)), null, t(10, 1), r51()).exit!!.kind)
        // The next minute's: the fixed stop.
        val next = PassedArms.exitOnBars(200.0, t(10, 0), wick + bar(10, 1, 200.0, 200.0, 186.0, 187.0), null, t(10, 2), r51())
        assertEquals(PassedArms.ExitKind.FIXED_STOP, next.exit!!.kind)
    }

    @Test fun theWalkSellsAtTheFixedTargetFirstAndBooksItAtTheLevel() {
        val bars = listOf(bar(10, 0, 200.0, 228.0, 199.0, 227.0))
        val w = PassedArms.exitOnBars(200.0, t(9, 59), bars, null, t(10, 1), r51())
        assertEquals(PassedArms.ExitKind.FIXED_TARGET, w.exit!!.kind)
        assertEquals(226.7, w.exit!!.price, 1e-9)
        assertEquals(226.7, PassedArms.booked(PassedArms.ExitKind.FIXED_TARGET, 229.0, PassedArms.bookedTarget(PassedArms.ExitKind.FIXED_TARGET, 200.0, r51()), null), 1e-9)
        assertEquals(225.0, PassedArms.booked(PassedArms.ExitKind.FIXED_TARGET, 225.0, 226.7), 1e-9)
        // Between minutes, on the price now.
        val now = PassedArms.exitOnBars(200.0, t(10, 0), emptyList(), 226.7, t(10, 0).plusSeconds(20), r51())
        assertEquals(PassedArms.ExitKind.FIXED_TARGET, now.exit!!.kind)
        val stopNow = PassedArms.exitOnBars(200.0, t(10, 0), emptyList(), 186.0, t(10, 0).plusSeconds(20), r51())
        assertEquals(PassedArms.ExitKind.FIXED_STOP, stopNow.exit!!.kind)
        assertEquals(186.0, PassedArms.booked(PassedArms.ExitKind.FIXED_STOP, 185.0, null, 186.0), 1e-9)
        // No price now and no minutes: nothing (a stale price is never invented).
        assertNull(PassedArms.exitOnBars(200.0, t(10, 0), emptyList(), null, t(10, 0).plusSeconds(20), r51()).exit)
    }

    @Test fun theArmsOwnStopOrTargetWhenItComesFirst() {
        // A cheap option (a fill of 12 at 1 lot of 75: -13.33 is more than the premium): no fixed stop; the arm's -15% (10.20) acts.
        val cheap = DonchianArm.EXITS.copy(fixedStop = FixedRupee.stopLevel(12.0, 75), fixedTarget = FixedRupee.targetLevel(12.0, 75))
        assertNull(cheap.fixedStop)
        val w = PassedArms.exitOnBars(12.0, t(10, 0), listOf(bar(10, 0, 12.0, 12.0, 10.0, 10.1)), null, t(10, 1), cheap)
        assertEquals(PassedArms.ExitKind.STOP, w.exit!!.kind)
        // A fixed stop looser than the arm's own (an own -1% stop at 198 above the fixed 186.70): the arm's own one acts.
        val tightOwn = DonchianArm.EXITS.copy(stop = 0.01, fixedStop = 186.7)
        assertEquals(198.0, PassedArms.effectiveStop(200.0, tightOwn)!!, 1e-9)
        val w2 = PassedArms.exitOnBars(200.0, t(10, 0), listOf(bar(10, 0, 200.0, 200.0, 197.5, 198.0)), null, t(10, 1), tightOwn)
        assertEquals(PassedArms.ExitKind.STOP, w2.exit!!.kind)
        // An own target nearer than the fixed one: the arm's own.
        val ownTarget = DonchianArm.EXITS.copy(target = 0.05, fixedTarget = 226.7)
        assertEquals(210.0, PassedArms.effectiveTarget(200.0, ownTarget)!!, 1e-9)
        assertEquals(210.0, PassedArms.bookedTarget(PassedArms.ExitKind.TARGET, 200.0, ownTarget)!!, 1e-9)
        val w3 = PassedArms.exitOnBars(200.0, t(10, 0), listOf(bar(10, 0, 200.0, 212.0, 199.0, 211.0)), null, t(10, 1), ownTarget)
        assertEquals(PassedArms.ExitKind.TARGET, w3.exit!!.kind)
        // With the lock (no own target), the fixed target still acts; a lock above the fixed stop is the lock.
        val locked = PassedArms.withLock(r51())
        assertNull(locked.target); assertEquals(226.7, locked.fixedTarget)
        val lw = PassedArms.exitOnBars(200.0, t(10, 0), listOf(bar(10, 0, 200.0, 262.0, 199.0, 261.0), bar(10, 1, 261.0, 261.0, 255.0, 256.0)),
            null, t(10, 2), locked)
        assertEquals(PassedArms.ExitKind.FIXED_TARGET, lw.exit!!.kind)
        val lockFirst = PassedArms.exitOnBars(200.0, t(10, 0), listOf(bar(10, 0, 200.0, 262.0, 199.0, 250.0), bar(10, 1, 250.0, 250.0, 199.0, 200.0)),
            null, t(10, 2), PassedArms.withLock(r51()).copy(fixedTarget = null), breakeven = 201.0)
        assertEquals(PassedArms.ExitKind.LOCK, lockFirst.exit!!.kind)
        // Effective levels with nothing fixed are the arm's own.
        assertEquals(PassedArms.stopLevel(200.0, DonchianArm.EXITS), PassedArms.effectiveStop(200.0, DonchianArm.EXITS))
        assertEquals(PassedArms.targetLevel(200.0, DonchianArm.EXITS), PassedArms.effectiveTarget(200.0, DonchianArm.EXITS))
        assertEquals(PassedArms.targetLevel(200.0, DonchianArm.EXITS), PassedArms.bookedTarget(null, 200.0, DonchianArm.EXITS))
    }

    @Test fun theTimeStopAndSquareOffStillAct() {
        val x = r51().copy(squareOff = LocalTime.of(15, 10))
        val w = PassedArms.exitOnBars(200.0, t(15, 5), listOf(bar(15, 5, 200.0, 201.0, 199.0, 200.0)), 200.0, t(15, 10), x)
        assertEquals(PassedArms.ExitKind.SQUARE_OFF, w.exit!!.kind)
    }

    // ---- the live mirror ---------------------------------------------------------------------------------------------------------

    private val live = Look(side = 1, qty = 75, held = 75, ltp = 210.0, tick = 0.05, stopTrigger = 186.7, stopResting = true, target = 226.7)

    @Test fun theLiveTargetSellsTheWholePositionOnce() {
        val d = LiveMirror.decide(live.copy(ltp = 226.7))
        assertEquals(Step.SELL, d.step); assertEquals(FixedRupee.WHY_TARGET, d.why); assertEquals(75, d.qty)
        assertEquals(Step.HOLD, LiveMirror.decide(live).step)
        // No price: never sold on the target.
        assertEquals(Step.HOLD, LiveMirror.decide(live.copy(ltp = null)).step)
        // Boss's stop and the square-off still come first; the paper trade's own exit too.
        assertEquals("operator_stop", LiveMirror.decide(live.copy(ltp = 230.0, operatorStop = true)).why)
        assertEquals("square_off", LiveMirror.decide(live.copy(ltp = 230.0, squareOff = true)).why)
        assertEquals("paper_exit", LiveMirror.decide(live.copy(ltp = 230.0, paperSell = 75)).why)
        // Only what is held is sold.
        assertEquals(30, LiveMirror.decide(live.copy(ltp = 230.0, held = 30)).qty)
        // A short never uses it (these arms buy options).
        assertEquals(Step.HOLD, LiveMirror.decide(live.copy(side = -1, held = -75, ltp = 230.0, stopTrigger = 260.0)).step)
        // No target: as before.
        assertEquals(Step.HOLD, LiveMirror.decide(live.copy(ltp = 300.0, target = null)).step)
    }

    @Test fun theLiveStopMovesUpToTheFixedLevel() {
        // The resting stop at the arm's -15% (170) moves up to the ₹1k level (186.70) when that is the wanted level.
        val d = LiveMirror.decide(live.copy(stopTrigger = 170.0, wanted = 186.7))
        assertEquals(Step.MOVE_STOP, d.step); assertEquals(186.7, d.to)
        // Never down.
        assertEquals(Step.HOLD, LiveMirror.decide(live.copy(stopTrigger = 190.0, wanted = 186.7)).step)
        // Nothing resting: placed at it.
        val p = LiveMirror.decide(live.copy(stopTrigger = 170.0, stopResting = false, wanted = 186.7))
        assertEquals(Step.PLACE_STOP, p.step); assertEquals(186.7, p.to)
        assertNotNull(LiveMirror.stopLimit(186.7, 0.05, 1))
    }

    /** 14 Nov review: the ₹1k level reached while the resting (or watched) stop is lower: the app sells; never with no price or a short. */
    @Test fun theAppSellsAtTheOneThousandLevelWhileTheStopIsLower() {
        val lower = live.copy(stopTrigger = 170.0, fixedStop = 186.7)
        val d = LiveMirror.decide(lower.copy(ltp = 186.0))
        assertEquals(Step.SELL, d.step); assertEquals(FixedRupee.WHY_STOP, d.why); assertEquals(75, d.qty)
        assertEquals(Step.SELL, LiveMirror.decide(lower.copy(ltp = 186.0, stopTrigger = null, stopResting = false)).step)
        // Above it: held (the stop moves up when it may rest).
        assertEquals(Step.MOVE_STOP, LiveMirror.decide(lower.copy(ltp = 190.0, wanted = 186.7)).step)
        // The stop already at the level: Zerodha's stop does it (no app sale beside it).
        assertEquals(Step.HOLD, LiveMirror.decide(lower.copy(ltp = 186.0, stopTrigger = 186.7)).step)
        assertEquals(Step.HOLD, LiveMirror.decide(lower.copy(ltp = null)).step)
        assertEquals(Step.HOLD, LiveMirror.decide(lower.copy(side = -1, held = -75, ltp = 100.0, stopTrigger = 260.0)).step)
    }
}
