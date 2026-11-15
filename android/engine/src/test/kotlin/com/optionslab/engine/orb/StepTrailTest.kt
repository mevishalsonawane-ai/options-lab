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
 * 15 Nov 2026, second version (Boss: "Make it 1.5k stoploss", "Instead 10% breakeven, make it 5% breakeven"; per lot; CLAUDE.md's fifth
 * exception, failed gates): [StepTrail]'s levels - the entry's ₹1,500 a lot after charges, the breakeven once the option is 5% up, rounded
 * up to the tick, tighter-only, never down - and how the passed arms' walk and the live mirror's decision use them.
 */
class StepTrailTest {
    private val day = LocalDate.of(2026, 11, 16)
    private fun t(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private fun bar(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Bar(t(h, m), o, hi, lo, c)
    private fun up(x: Double): Double = kotlin.math.ceil(x / 0.05 - 1e-6) * 0.05

    @Test fun theChargesAreSmallAndPositive() {
        for ((e, lot) in listOf(300.0 to 50, 200.0 to 75, 120.0 to 30, 80.0 to 65)) {
            val c = StepTrail.chargesPerUnit(e, lot)
            assertTrue(c > 0 && c < e * 0.02, "$e x $lot: $c")
        }
    }

    @Test fun theEntryStopLosesFifteenHundredALotAfterChargesWhateverTheLots() {
        // A lot of 50 at 300: 1,500 / 50 = 30 under the fill, plus the charges a unit, up to the tick.
        val c = StepTrail.chargesPerUnit(300.0, 50)
        assertEquals(up(300.0 - 30.0 + c), StepTrail.stopLevel(300.0, 50)!!, 1e-9)
        for (lots in 1..3) for (lot in listOf(15, 20, 30, 35, 65, 75)) for (e in listOf(150.0, 321.15, 480.0)) {
            val s = StepTrail.stopLevel(e, lot)!!
            val loss = ((e - s) * lot + StepTrail.chargesPerUnit(e, lot) * lot) * lots
            assertTrue(loss <= StepTrail.STOP_RS * lots + 1e-6 && loss > (StepTrail.STOP_RS - 0.05 * lot) * lots - 1e-6, "$lots x $lot at $e: $loss")
        }
    }

    @Test fun nothingWithNoFillNoLotOrNoTickAndNoStopUnderATick() {
        for (e in listOf(0.0, -1.0, Double.NaN)) { assertNull(StepTrail.stopLevel(e, 75)); assertNull(StepTrail.trailLevel(e, 75, 300.0)) }
        assertNull(StepTrail.stopLevel(200.0, 0)); assertNull(StepTrail.trailLevel(200.0, 0, 300.0))
        assertNull(StepTrail.stopLevel(200.0, 75, 0.0)); assertNull(StepTrail.trailLevel(200.0, 75, 300.0, Double.NaN))
        assertNull(StepTrail.stopLevel(15.0, 75), "1,500 / 75 = 20 is more than the premium")
        assertNull(StepTrail.trailLevel(200.0, 75, null)); assertNull(StepTrail.trailLevel(200.0, 75, Double.NaN))
        assertNull(StepTrail.levelOf(0.0, 75, null))
    }

    @Test fun theBreakevenFromFivePercentUp() {
        val be = up(300.0 + StepTrail.chargesPerUnit(300.0, 50))
        assertEquals(315.0, StepTrail.breakevenTrigger(300.0), 1e-9)
        assertEquals(be, StepTrail.breakevenLevel(300.0, 50)!!, 1e-9)
        assertTrue(be > 300.0 && be < 302.0)
        assertNull(StepTrail.trailLevel(300.0, 50, 314.95), "under +5%: no breakeven yet")
        assertEquals(be, StepTrail.trailLevel(300.0, 50, 315.0)!!, 1e-9)
        assertEquals(be, StepTrail.trailLevel(300.0, 50, 400.0)!!, 1e-9, "one step only: the breakeven, whatever the best")
        // The rule's stop: the entry's until +5%, the breakeven after (and never under the entry's).
        assertEquals(StepTrail.stopLevel(300.0, 50)!!, StepTrail.levelOf(300.0, 50, 310.0)!!, 1e-9)
        assertEquals(be, StepTrail.levelOf(300.0, 50, 316.0)!!, 1e-9)
        // The breakeven is never under the entry's stop.
        assertTrue(StepTrail.levelOf(300.0, 50, 316.0)!! > StepTrail.stopLevel(300.0, 50)!!)
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
        val s = StepTrail.stopLevel(300.0, 50)!!; val be = StepTrail.breakevenLevel(300.0, 50)!!
        assertEquals(StepTrail.WHY_STOP, StepTrail.whyAt(s, 300.0, 50))
        assertEquals(StepTrail.WHY_TRAIL, StepTrail.whyAt(be, 300.0, 50))
        assertNull(StepTrail.whyAt(255.0, 300.0, 50)); assertNull(StepTrail.whyAt(330.0, 300.0, 50))
        assertNull(StepTrail.whyAt(null, 300.0, 50)); assertNull(StepTrail.whyAt(s, 300.0, 0))
    }

    @Test fun onlyLiquidityR51AndR72AndTheWords() {
        assertTrue(StepTrail.covers(DonchianArm.SOURCE)); assertTrue(StepTrail.covers(BreadthDonchianArm.SOURCE)); assertTrue(StepTrail.covers("liquidity"))
        for (b in LiquidityRules.BOOKS) assertTrue(StepTrail.covers(b.source))
        for (o in listOf(OovArm.SOURCE, Solo2.SOURCE, S5Trail.SOURCE, CisdNet.SOURCE, Ft3Rules.SOURCE, NightRules.SOURCE, "orb", "")) assertFalse(StepTrail.covers(o), o)
        assertTrue(StepTrail.LABEL.contains("failed gates, Boss's exception")); assertTrue(StepTrail.RECORD.contains("R179"))
        assertFalse(Regex("₹|\\bRs\\b|\\d,\\d").containsMatchIn(StepTrail.DIAG))
        assertEquals("₹1,500 stop (failed gates, Boss's exception)", StepTrail.words(StepTrail.WHY_STOP))
        assertEquals("breakeven lock (failed gates, Boss's exception)", StepTrail.words(StepTrail.WHY_TRAIL))
        // The morning's version's words on older records still read as the rule's.
        assertEquals("₹500 stop (failed gates, Boss's exception)", StepTrail.words(StepTrail.OLD_WHY_STOP))
        assertEquals("step trail (failed gates, Boss's exception)", StepTrail.words(StepTrail.OLD_WHY_TRAIL))
        assertTrue(StepTrail.isRuleStop(StepTrail.OLD_WHY_STOP) && StepTrail.isRuleStop(StepTrail.WHY_STOP) && !StepTrail.isRuleStop(StepTrail.WHY_TRAIL))
        assertEquals("stop", StepTrail.words("stop"))
    }

    // ---- the passed arms' walk ------------------------------------------------------------------------------------------------

    /** R51's exits (-15% stop, +30% target, 30 minutes) with the rule for a lot of 50 (entry 300: stop about 270.x, breakeven from 315). */
    private val r51 = DonchianArm.EXITS.copy(stepTrail = PassedArms.StepTrailOn(50))
    private val s300 = StepTrail.stopLevel(300.0, 50)!!
    private val be300 = StepTrail.breakevenLevel(300.0, 50)!!

    @Test fun theWalkSellsAtTheFifteenHundredStopWhereItIsTighter() {
        val bars = listOf(bar(10, 0, 300.0, 301.0, 299.0, 300.0), bar(10, 1, 298.0, 298.0, 268.0, 269.0))
        val w = PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 2), r51)
        assertEquals(PassedArms.ExitKind.STEP_STOP, w.exit!!.kind); assertEquals(s300, w.exit!!.price, 1e-9)
        assertEquals(s300, PassedArms.effectiveStop(300.0, r51)!!, 1e-9)
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 2), DonchianArm.EXITS).exit, "off: the -15% (255) holds")
        // The entry minute's own low never sets it off (it may be before the fill); the arm's own stop keeps the research's rule.
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), listOf(bar(10, 0, 300.0, 300.0, 268.0, 300.0)), null, t(10, 1), r51).exit)
        assertEquals(PassedArms.ExitKind.STOP, PassedArms.exitOnBars(300.0, t(10, 0), listOf(bar(10, 0, 300.0, 300.0, 250.0, 252.0)), null, t(10, 1), r51).exit!!.kind)
        // Between minutes, on the price now.
        assertEquals(PassedArms.ExitKind.STEP_STOP, PassedArms.exitOnBars(300.0, t(10, 0), emptyList(), s300 - 0.1, t(10, 0).plusSeconds(20), r51).exit!!.kind)
    }

    @Test fun theBreakevenFromTheNextMinuteAndNeverDown() {
        // 10:01 trades up to 316 (+5.3%): from 10:02 the stop is the breakeven; 10:02 dips to 300: out at the breakeven.
        val up1 = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 316.0, 300.0, 314.0))
        val held = PassedArms.exitOnBars(300.0, t(10, 0), up1, null, t(10, 2), r51)
        assertNull(held.exit); assertEquals(be300, held.lock!!, 1e-9)
        val w = PassedArms.exitOnBars(300.0, t(10, 0), up1 + bar(10, 2, 310.0, 310.0, 300.0, 300.5), null, t(10, 3), r51)
        assertEquals(PassedArms.ExitKind.STEP_TRAIL, w.exit!!.kind); assertEquals(be300, w.exit!!.price, 1e-9)
        assertEquals(be300, PassedArms.booked(PassedArms.ExitKind.STEP_TRAIL, 299.0, null, be300), 1e-9)
        // A restart that lost the minutes keeps the breakeven it had (the known lock), named the breakeven lock.
        val restarted = PassedArms.exitOnBars(300.0, t(10, 0), emptyList(), 300.0, t(10, 5), r51, known = t(10, 2) to be300)
        assertEquals(PassedArms.ExitKind.STEP_TRAIL, restarted.exit!!.kind)
        // An entry-minute high never starts it; under +5% nothing moves.
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), listOf(bar(10, 0, 300.0, 330.0, 300.0, 300.0)), null, t(10, 1), r51).lock)
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 314.0, 300.0, 313.0)),
            null, t(10, 2), r51.copy(target = null)).lock)
        // With R64's lock a higher lock is the lock: at +60% the ladder's +30% (390) is above the breakeven.
        val locked = PassedArms.withLock(r51.copy(target = null))
        val ownLock = PassedArms.exitOnBars(300.0, t(10, 0), listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 481.0, 300.0, 480.0),
            bar(10, 2, 480.0, 480.0, 389.0, 389.5)), null, t(10, 3), locked, breakeven = 301.0)
        assertEquals(PassedArms.ExitKind.LOCK, ownLock.exit!!.kind, "the ladder's +30% lock is above the breakeven")
    }

    @Test fun theArmsOwnStopTargetAndClockStill() {
        // A cheap option: 1,500 a lot is more than the premium: no rule stop; the -15% acts.
        val cheap = DonchianArm.EXITS.copy(stepTrail = PassedArms.StepTrailOn(100))
        assertEquals(PassedArms.ExitKind.STOP, PassedArms.exitOnBars(4.0, t(10, 0), listOf(bar(10, 0, 4.0, 4.0, 3.0, 3.1)), null, t(10, 1), cheap).exit!!.kind)
        // Where -15% loses less than 1,500 a lot (at 100, lot 50: -15% = 15 points = ₹750 a lot), the -15% is the stop.
        assertEquals(PassedArms.ExitKind.STOP, PassedArms.exitOnBars(100.0, t(10, 0), listOf(bar(10, 0, 100.0, 100.0, 100.0, 100.0),
            bar(10, 1, 99.0, 99.0, 84.0, 84.5)), null, t(10, 2), r51).exit!!.kind)
        // The target still sells (no fixed target of the rule's own).
        assertEquals(PassedArms.ExitKind.TARGET, PassedArms.exitOnBars(300.0, t(10, 0), listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0),
            bar(10, 1, 300.0, 391.0, 300.0, 390.0)), null, t(10, 2), r51).exit!!.kind)
        val sq = PassedArms.exitOnBars(300.0, t(15, 5), listOf(bar(15, 5, 300.0, 301.0, 299.5, 300.0)), 300.0, t(15, 10), r51.copy(squareOff = LocalTime.of(15, 10)))
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
