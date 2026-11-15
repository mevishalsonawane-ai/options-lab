package com.optionslab.engine.orb

import com.optionslab.engine.orb.LiveMirror.Look
import com.optionslab.engine.orb.LiveMirror.Step
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 15 Nov 2026 (Boss: "when a trade is bought the stop loss should be only 500 rs ... keep a 500 rs gap on every 500 rs gain"; per lot;
 * CLAUDE.md's fifth exception, not tested): [StepTrail]'s levels - the entry's -₹500 a lot, the steps from +₹1,000 a lot, rounded up
 * to the tick, tighter-only, never down - and how the passed arms' walk and the live mirror's decision use them.
 */
class StepTrailTest {
    private val day = LocalDate.of(2026, 11, 16)
    private fun t(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private fun bar(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Bar(t(h, m), o, hi, lo, c)

    @Test fun theEntryStopIsFiveHundredALotWhateverTheLots() {
        // A lot of 75 at 200: 500 / 75 = 6.667 under the fill -> 193.333, up to the tick 193.35 (1, 2 or 3 lots: the same per unit).
        assertEquals(193.35, StepTrail.stopLevel(200.0, 75)!!, 1e-9)
        // A lot of 30 at 300: 16.667 under -> 283.35; a lot of 65 at 100: 7.69 under -> 92.35.
        assertEquals(283.35, StepTrail.stopLevel(300.0, 30)!!, 1e-9)
        assertEquals(92.35, StepTrail.stopLevel(100.0, 65)!!, 1e-9)
        for (lots in 1..3) for (lot in listOf(15, 20, 30, 35, 65, 75)) {
            val s = StepTrail.stopLevel(321.15, lot)!!
            val loss = (321.15 - s) * lot * lots
            assertTrue(loss <= StepTrail.STEP_RS * lots + 1e-6 && loss > (StepTrail.STEP_RS - 0.05 * lot) * lots - 1e-6, "$lots x $lot: $loss")
        }
        assertEquals(100.0, StepTrail.stopLevel(110.0, 50)!!, 1e-9, "a level on a tick stays")
    }

    @Test fun nothingWithNoFillNoLotOrNoTickAndNoStopUnderATick() {
        for (e in listOf(0.0, -1.0, Double.NaN)) { assertNull(StepTrail.stopLevel(e, 75)); assertNull(StepTrail.trailLevel(e, 75, 300.0)) }
        assertNull(StepTrail.stopLevel(200.0, 0)); assertNull(StepTrail.trailLevel(200.0, 0, 300.0))
        assertNull(StepTrail.stopLevel(200.0, 75, 0.0)); assertNull(StepTrail.trailLevel(200.0, 75, 300.0, Double.NaN))
        assertNull(StepTrail.stopLevel(6.0, 75), "500 / 75 is more than the premium")
        assertNull(StepTrail.trailLevel(200.0, 75, null)); assertNull(StepTrail.trailLevel(200.0, 75, Double.NaN))
        assertNull(StepTrail.levelOf(0.0, 75, null))
    }

    @Test fun theStepsAtOneThousandFifteenHundredTwoThousandALot() {
        assertNull(StepTrail.stepPnl(999.0)); assertNull(StepTrail.stepPnl(Double.NaN))
        assertEquals(500.0, StepTrail.stepPnl(1_000.0)); assertEquals(500.0, StepTrail.stepPnl(1_499.0))
        assertEquals(1_000.0, StepTrail.stepPnl(1_500.0)); assertEquals(1_500.0, StepTrail.stepPnl(2_000.0)); assertEquals(4_500.0, StepTrail.stepPnl(5_250.0))
        // A lot of 50 at 100: +1,000 a lot is 120; the stop then +500 a lot = 110; at 130 (+1,500) 120; at 140 (+2,000) 130.
        assertNull(StepTrail.trailLevel(100.0, 50, 119.95))
        assertEquals(110.0, StepTrail.trailLevel(100.0, 50, 120.0)!!, 1e-9)
        assertEquals(110.0, StepTrail.trailLevel(100.0, 50, 129.95)!!, 1e-9)
        assertEquals(120.0, StepTrail.trailLevel(100.0, 50, 130.0)!!, 1e-9)
        assertEquals(130.0, StepTrail.trailLevel(100.0, 50, 140.0)!!, 1e-9)
        // Rounded up to the tick (a lot of 75 at 200: +500 a lot = 206.667 -> 206.70).
        assertEquals(206.70, StepTrail.trailLevel(200.0, 75, 213.4)!!, 1e-9)
        // The rule's stop: the entry's until the first step, the step after.
        assertEquals(90.0, StepTrail.levelOf(100.0, 50, 105.0)!!, 1e-9)
        assertEquals(110.0, StepTrail.levelOf(100.0, 50, 121.0)!!, 1e-9)
    }

    @Test fun tighterOnlyAndHits() {
        assertEquals(193.35, StepTrail.tighterStop(170.0, 193.35)); assertEquals(195.0, StepTrail.tighterStop(195.0, 193.35))
        assertEquals(1.0, StepTrail.tighterStop(null, 1.0)); assertEquals(1.0, StepTrail.tighterStop(1.0, null)); assertNull(StepTrail.tighterStop(null, null))
        assertTrue(StepTrail.isTighter(170.0, 193.35)); assertTrue(StepTrail.isTighter(null, 1.0))
        assertFalse(StepTrail.isTighter(195.0, 193.35)); assertFalse(StepTrail.isTighter(1.0, null))
        assertTrue(StepTrail.stopHit(193.35, 193.35)); assertFalse(StepTrail.stopHit(193.4, 193.35))
        for (p in listOf(null, 0.0, Double.NaN)) assertFalse(StepTrail.stopHit(p, 193.35))
        assertFalse(StepTrail.stopHit(100.0, null))
    }

    @Test fun whatARestingStopIs() {
        assertEquals(StepTrail.WHY_STOP, StepTrail.whyAt(90.0, 100.0, 50))
        assertEquals(StepTrail.WHY_TRAIL, StepTrail.whyAt(110.0, 100.0, 50)); assertEquals(StepTrail.WHY_TRAIL, StepTrail.whyAt(130.0, 100.0, 50))
        assertNull(StepTrail.whyAt(85.0, 100.0, 50)); assertNull(StepTrail.whyAt(112.5, 100.0, 50)); assertNull(StepTrail.whyAt(101.0, 100.0, 50))
        assertNull(StepTrail.whyAt(null, 100.0, 50)); assertNull(StepTrail.whyAt(110.0, 100.0, 0))
    }

    @Test fun onlyLiquidityR51AndR72() {
        assertTrue(StepTrail.covers(DonchianArm.SOURCE)); assertTrue(StepTrail.covers(BreadthDonchianArm.SOURCE)); assertTrue(StepTrail.covers("liquidity"))
        for (b in LiquidityRules.BOOKS) assertTrue(StepTrail.covers(b.source))
        for (o in listOf(OovArm.SOURCE, Solo2.SOURCE, S5Trail.SOURCE, CisdNet.SOURCE, Ft3Rules.SOURCE, NightRules.SOURCE, "orb", "")) assertFalse(StepTrail.covers(o), o)
        assertTrue(StepTrail.LABEL.contains("not tested, Boss's exception")); assertTrue(StepTrail.RECORD.contains("R152") && StepTrail.RECORD.contains("R172"))
        assertFalse(Regex("₹|\\bRs\\b|\\d,\\d").containsMatchIn(StepTrail.DIAG))
        assertEquals("₹500 stop (not tested, Boss's exception)", StepTrail.words(StepTrail.WHY_STOP))
        assertEquals("step trail (not tested, Boss's exception)", StepTrail.words(StepTrail.WHY_TRAIL))
        assertEquals("stop", StepTrail.words("stop"))
    }

    // ---- the passed arms' walk ------------------------------------------------------------------------------------------------

    /** R51's exits (-15% stop, +30% target, 30 minutes) with the step trail for a lot of 50 (entry 100: stop 90, steps from 120). */
    private val r51 = DonchianArm.EXITS.copy(stepTrail = PassedArms.StepTrailOn(50))

    @Test fun theWalkSellsAtTheFiveHundredStopWhereItIsTighter() {
        val bars = listOf(bar(10, 0, 100.0, 100.5, 99.5, 100.0), bar(10, 1, 99.0, 99.0, 89.0, 89.5))
        val w = PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 2), r51)
        assertEquals(PassedArms.ExitKind.STEP_STOP, w.exit!!.kind); assertEquals(90.0, w.exit!!.price, 1e-9)
        assertEquals(90.0, PassedArms.effectiveStop(100.0, r51)!!, 1e-9)
        assertNull(PassedArms.exitOnBars(100.0, t(10, 0), bars, null, t(10, 2), DonchianArm.EXITS).exit, "off: the -15% (85) holds")
        // The entry minute's own low never sets it off (it may be before the fill); the arm's own stop keeps the research's rule.
        assertNull(PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 0, 100.0, 100.0, 89.0, 100.0)), null, t(10, 1), r51).exit)
        assertEquals(PassedArms.ExitKind.STOP, PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 0, 100.0, 100.0, 84.0, 85.0)), null, t(10, 1), r51).exit!!.kind)
        // Between minutes, on the price now.
        assertEquals(PassedArms.ExitKind.STEP_STOP, PassedArms.exitOnBars(100.0, t(10, 0), emptyList(), 89.9, t(10, 0).plusSeconds(20), r51).exit!!.kind)
    }

    @Test fun theStepRisesWithTheBestHighFromTheNextMinuteAndNeverDown() {
        // 10:01 trades up to 130 (+1,500 a lot): from 10:02 the stop is 120; 10:02 dips to 119: out at 120 by the step trail. (R51's
        // +30% target is 130 itself: left out here, so the step is what is seen.)
        val r51 = r51.copy(target = null)
        val up = listOf(bar(10, 0, 100.0, 100.0, 100.0, 100.0), bar(10, 1, 100.0, 130.0, 100.0, 128.0))
        val held = PassedArms.exitOnBars(100.0, t(10, 0), up, null, t(10, 2), r51)
        assertNull(held.exit); assertEquals(120.0, held.lock!!, 1e-9)
        val w = PassedArms.exitOnBars(100.0, t(10, 0), up + bar(10, 2, 125.0, 125.0, 119.0, 119.5), null, t(10, 3), r51)
        assertEquals(PassedArms.ExitKind.STEP_TRAIL, w.exit!!.kind); assertEquals(120.0, w.exit!!.price, 1e-9)
        assertEquals(120.0, PassedArms.booked(PassedArms.ExitKind.STEP_TRAIL, 118.0, null, 120.0), 1e-9)
        // A restart that lost the minutes keeps the step it had (the known lock), named the step trail.
        val restarted = PassedArms.exitOnBars(100.0, t(10, 0), emptyList(), 119.0, t(10, 5), r51, known = t(10, 2) to 120.0)
        assertEquals(PassedArms.ExitKind.STEP_TRAIL, restarted.exit!!.kind)
        // An entry-minute high never starts it.
        assertNull(PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 0, 100.0, 130.0, 100.0, 100.0)), null, t(10, 1), r51).lock)
        // With R64's lock a higher lock is the lock; a step above the lock is the step.
        val locked = PassedArms.withLock(r51)
        val lw = PassedArms.exitOnBars(100.0, t(10, 0), up + bar(10, 2, 125.0, 125.0, 119.0, 119.5), null, t(10, 3), locked, breakeven = 100.5)
        assertEquals(PassedArms.ExitKind.STEP_TRAIL, lw.exit!!.kind)
        val ownLock = PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 0, 100.0, 100.0, 100.0, 100.0), bar(10, 1, 100.0, 161.0, 100.0, 160.0),
            bar(10, 2, 160.0, 160.0, 129.0, 129.5)), null, t(10, 3), locked, breakeven = 100.5)
        assertEquals(PassedArms.ExitKind.STEP_TRAIL, ownLock.exit!!.kind, "at 161 (+3,050 a lot) the step is 150, above the 130 lock")
    }

    @Test fun theArmsOwnStopTargetAndClockStill() {
        // A cheap option: -500 a lot is more than the premium: no rule stop; the -15% acts.
        val cheap = DonchianArm.EXITS.copy(stepTrail = PassedArms.StepTrailOn(100))
        assertEquals(PassedArms.ExitKind.STOP, PassedArms.exitOnBars(4.0, t(10, 0), listOf(bar(10, 0, 4.0, 4.0, 3.0, 3.1)), null, t(10, 1), cheap).exit!!.kind)
        // An own stop tighter than -500 a lot: the own one.
        val tight = DonchianArm.EXITS.copy(stop = 0.01, stepTrail = PassedArms.StepTrailOn(50))
        assertEquals(PassedArms.ExitKind.STOP, PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 0, 100.0, 100.0, 100.0, 100.0),
            bar(10, 1, 99.5, 99.5, 98.5, 99.0)), null, t(10, 2), tight).exit!!.kind)
        // The target still sells (no fixed target of the rule's own).
        assertEquals(PassedArms.ExitKind.TARGET, PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 0, 100.0, 100.0, 100.0, 100.0),
            bar(10, 1, 100.0, 131.0, 100.0, 130.0)), null, t(10, 2), r51).exit!!.kind)
        val sq = PassedArms.exitOnBars(100.0, t(15, 5), listOf(bar(15, 5, 100.0, 101.0, 99.5, 100.0)), 100.0, t(15, 10), r51.copy(squareOff = LocalTime.of(15, 10)))
        assertEquals(PassedArms.ExitKind.SQUARE_OFF, sq.exit!!.kind)
    }

    // ---- the live mirror ----------------------------------------------------------------------------------------------------

    private val live = Look(side = 1, qty = 75, held = 75, ltp = 210.0, tick = 0.05, stopTrigger = 170.0, stopResting = true, ruleStop = 193.35)

    @Test fun theAppSellsAtTheRuleStopWhileTheRestingStopIsLower() {
        val d = LiveMirror.decide(live.copy(ltp = 193.0))
        assertEquals(Step.SELL, d.step); assertEquals(StepTrail.WHY_STOP, d.why); assertEquals(75, d.qty)
        assertEquals(StepTrail.WHY_TRAIL, LiveMirror.decide(live.copy(ltp = 205.0, ruleStop = 206.7, ruleWhy = StepTrail.WHY_TRAIL)).why)
        assertEquals(Step.SELL, LiveMirror.decide(live.copy(ltp = 193.0, stopTrigger = null, stopResting = false)).step)
        // Above it: the stop moves up when it may rest there; at it already: Zerodha's own stop does it (no second exit).
        assertEquals(Step.MOVE_STOP, LiveMirror.decide(live.copy(wanted = 193.35)).step)
        assertEquals(Step.HOLD, LiveMirror.decide(live.copy(ltp = 193.0, stopTrigger = 193.35)).step)
        assertEquals(Step.HOLD, LiveMirror.decide(live.copy(ltp = null)).step)
        assertEquals(Step.HOLD, LiveMirror.decide(live.copy(side = -1, held = -75, ltp = 100.0, stopTrigger = 260.0)).step, "never a short")
        assertEquals("paper_exit", LiveMirror.decide(live.copy(ltp = 190.0, paperSell = 75)).why)
        assertEquals("operator_stop", LiveMirror.decide(live.copy(ltp = 190.0, operatorStop = true)).why)
    }
}
