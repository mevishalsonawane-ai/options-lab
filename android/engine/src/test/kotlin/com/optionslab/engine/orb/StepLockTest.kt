package com.optionslab.engine.orb

import com.optionslab.engine.orb.LiveMirror.Look
import com.optionslab.engine.orb.LiveMirror.Step
import com.optionslab.engine.orb.StepTrail.Lock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 16 Nov 2026, the fourth version of Boss's exception (Boss: "For r51/r72 use Step lock (+₹500 at +₹1,000, and so on) And for liquidity
 * use Breakeven at +₹1,000"; CLAUDE.md's fifth exception, failed R186's gates): [StepTrail.Lock]'s levels against R186's r186engine
 * (lock_levels: the triggers and locks are r106engine.target_price's premiums for the per-lot rupees, here with the engine's own F&O charge
 * schedule and no stop-slippage pad), and how the passed arms' walk and the live mirror use them.
 */
class StepLockTest {
    private val day = LocalDate.of(2026, 11, 16)
    private fun t(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private fun bar(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Bar(t(h, m), o, hi, lo, c)

    /**
     * Parity: r186engine's triggers (+₹1,000 + ₹500k a lot) and L500's locks (+₹500 + ₹500k a lot) for k = 0..3, computed by the research's
     * own r106engine.target_price (6 fixed-point passes, then up to the 0.05 tick) on the same charge schedule as [StepTrail.roundTrip].
     */
    private val parity = listOf(
        Triple(300.0, 50, 1) to (listOf(321.7, 331.75, 341.75, 351.8) to listOf(311.7, 321.7, 331.75, 341.75)),
        Triple(300.0, 50, 3) to (listOf(321.1, 331.1, 341.15, 351.15) to listOf(311.05, 321.1, 331.1, 341.15)),
        Triple(150.0, 75, 1) to (listOf(164.35, 171.05, 177.75, 184.4) to listOf(157.7, 164.35, 171.05, 177.75)),
        Triple(212.35, 65, 2) to (listOf(228.65, 236.35, 244.05, 251.8) to listOf(220.95, 228.65, 236.35, 244.05)),
        Triple(80.0, 30, 1) to (listOf(115.2, 131.9, 148.6, 165.3) to listOf(98.5, 115.2, 131.9, 148.6)),
    )

    @Test fun theLevelsMatchTheResearch() {
        for ((case, levels) in parity) {
            val (e, lot, lots) = case
            val q = lot * lots
            for (k in 0..3) {
                assertEquals(levels.first[k], StepTrail.lockTrigger(e, lot, q, k)!!, 1e-9, "trigger $case k=$k")
                assertEquals(levels.second[k], StepTrail.lockKeep(e, lot, q, k, Lock.L500)!!, 1e-9, "lock $case k=$k")
            }
        }
    }

    @Test fun theLevelsNetTheirRupeesAfterCharges() {
        // Each level is the lowest tick whose sale nets the rupees x lots after the round trip's charges: one tick less does not.
        for ((e, lot, lots) in listOf(Triple(300.0, 50, 1), Triple(300.0, 50, 3), Triple(95.5, 75, 2), Triple(42.0, 15, 4))) {
            val q = lot * lots
            for (k in 0..5) {
                val tr = StepTrail.lockTrigger(e, lot, q, k)!!
                val kp = StepTrail.lockKeep(e, lot, q, k, Lock.L500)!!
                assertTrue(StepTrail.netAt(e, tr, q) >= (1000.0 + 500 * k) * lots - 1e-6 && StepTrail.netAt(e, tr - 0.05, q) < (1000.0 + 500 * k) * lots)
                assertTrue(StepTrail.netAt(e, kp, q) >= (500.0 + 500 * k) * lots - 1e-6 && StepTrail.netAt(e, kp - 0.05, q) < (500.0 + 500 * k) * lots)
                assertTrue(kp < tr, "the lock sits under its trigger")
            }
        }
        // The charges are counted: the gross move alone would be cheaper.
        assertTrue(StepTrail.lockTrigger(300.0, 50, 50, 0)!! > 300.0 + 1000.0 / 50)
        assertTrue(StepTrail.roundTrip(300.0, 320.0, 50) > 40.0)
    }

    @Test fun theLevelsAreRememberedAndTheMemoryStaysSmall() {
        val a = StepTrail.netPrice(300.0, 50, 1000.0)!!
        assertEquals(a, StepTrail.netPrice(300.0, 50, 1000.0)!!, 0.0)
        // Thousands of different fills: the memory is cleared as it fills, and the levels stay right.
        for (i in 0 until 4200) StepTrail.netPrice(100.0 + i * 0.05, 75, 1000.0)
        assertEquals(a, StepTrail.netPrice(300.0, 50, 1000.0)!!, 0.0)
        assertEquals(321.7, StepTrail.lockTrigger(300.0, 50, 50, 0)!!, 1e-9)
    }

    @Test fun exactlyAtPlusOneThousandAndNotATickBefore() {
        val tr = StepTrail.lockTrigger(300.0, 50, 50, 0)!!
        assertNull(StepTrail.lockStep(300.0, 50, 50, tr - 0.05, Lock.L500))
        assertNull(StepTrail.lockLevel(300.0, 50, 50, tr - 0.05, Lock.L500))
        assertEquals(0, StepTrail.lockStep(300.0, 50, 50, tr, Lock.L500))
        assertEquals(311.7, StepTrail.lockLevel(300.0, 50, 50, tr, Lock.L500)!!, 1e-9)
        assertEquals(0, StepTrail.lockStep(300.0, 50, 50, tr, Lock.BE1000))
    }

    @Test fun theStepsGoUpInFiveHundredsAndAreFoundFromAnyBest() {
        val (e, lot) = 300.0 to 50
        // +1,500 locks +1,000, +2,000 +1,500, +2,500 +2,000.
        assertEquals(311.7, StepTrail.lockLevel(e, lot, 50, 331.7, Lock.L500)!!, 1e-9, "just under +1,500: still +500")
        assertEquals(321.7, StepTrail.lockLevel(e, lot, 50, 331.75, Lock.L500)!!, 1e-9, "at +1,500 the lock is the +1,000 premium")
        assertEquals(331.75, StepTrail.lockLevel(e, lot, 50, 341.75, Lock.L500)!!, 1e-9)
        assertEquals(341.75, StepTrail.lockLevel(e, lot, 50, 351.8, Lock.L500)!!, 1e-9)
        // Against a plain walk up the steps, for every best on the tick from the fill to +60%.
        var best = e
        while (best < e * 1.6) {
            var k = -1
            while (StepTrail.lockTrigger(e, lot, 50, k + 1)!! <= best + 1e-9) k++
            assertEquals(if (k < 0) null else k, StepTrail.lockStep(e, lot, 50, best, Lock.L500), "best $best")
            best = Math.round((best + 0.05) * 100) / 100.0
        }
        // A far best (a 10x option): settles on the right step from its estimate.
        val far = StepTrail.lockStep(e, lot, 50, 3000.0, Lock.L500)!!
        assertTrue(StepTrail.lockTrigger(e, lot, 50, far)!! <= 3000.0 && StepTrail.lockTrigger(e, lot, 50, far + 1)!! > 3000.0, "$far")
    }

    @Test fun theLockNeverLowersAsTheBestRises() {
        for (lock in Lock.entries) {
            var last = Double.NEGATIVE_INFINITY
            var best = 150.0
            while (best < 260.0) {
                StepTrail.trailLevel(150.0, 75, best, lock = lock, qty = 150)?.let { assertTrue(it >= last - 1e-9, "$lock $best: $it < $last"); last = it }
                best += 0.05
            }
        }
    }

    @Test fun perLotForAnyNumberOfLots() {
        // 3 lots: the rupees are x3, so the per-unit move is about the same (a little lower: the flat ₹20 a leg is shared).
        val one = StepTrail.lockTrigger(300.0, 50, 50, 0)!!
        val three = StepTrail.lockTrigger(300.0, 50, 150, 0)!!
        assertTrue(three <= one && one - three < 1.0, "$one vs $three")
        assertTrue(StepTrail.netAt(300.0, three, 150) >= 3000.0 - 1e-6)
        // A lot of 0 or no quantity: nothing.
        assertNull(StepTrail.lockTrigger(300.0, 0, 50, 0)); assertNull(StepTrail.lockTrigger(300.0, 50, 0, 0)); assertNull(StepTrail.lockTrigger(300.0, 50, 50, -1))
        assertNull(StepTrail.lockKeep(300.0, 50, 0, 0, Lock.L500)); assertNull(StepTrail.lockKeep(300.0, 50, 50, -1, Lock.L500))
        assertNull(StepTrail.lockStep(300.0, 50, 0, 400.0, Lock.L500))
        assertNull(StepTrail.netPrice(0.0, 50, 1000.0)); assertNull(StepTrail.netPrice(300.0, 0, 1000.0)); assertNull(StepTrail.netPrice(300.0, 50, Double.NaN))
        assertNull(StepTrail.netPrice(300.0, 50, 1000.0, 0.0)); assertNull(StepTrail.netPrice(Double.NaN, 50, 1000.0)); assertNull(StepTrail.netPrice(300.0, 50, 1000.0, Double.NaN))
    }

    @Test fun nothingWithoutALockABestOrAFill() {
        assertNull(StepTrail.lockLevel(300.0, 50, 50, 400.0, null))
        assertNull(StepTrail.lockLevel(300.0, 50, 50, null, Lock.L500))
        assertNull(StepTrail.lockLevel(300.0, 50, 50, Double.NaN, Lock.L500), "a stale or broken price never moves it")
        assertNull(StepTrail.lockLevel(0.0, 50, 50, 400.0, Lock.L500))
        assertNull(StepTrail.lockLevel(300.0, 50, 50, 400.0, Lock.L500, tick = 0.0))
        // V4 alone (no lock) is unchanged: nothing until +10%, then the breakeven.
        assertNull(StepTrail.trailLevel(300.0, 50, 325.0))
        assertEquals(StepTrail.breakevenLevel(300.0, 50)!!, StepTrail.trailLevel(300.0, 50, 400.0)!!, 1e-9)
    }

    @Test fun theLabelsAndTheArms() {
        assertEquals(Lock.L500, StepTrail.lockFor(DonchianArm.SOURCE))
        assertEquals(Lock.L500, StepTrail.lockFor(BreadthDonchianArm.SOURCE))
        assertEquals(Lock.BE1000, StepTrail.lockFor(LiquidityRules.ARM.source))
        for (b in LiquidityRules.BOOKS) assertEquals(Lock.BE1000, StepTrail.lockFor(b.source))
        assertNull(StepTrail.lockFor("orb_fresh"))
        for (l in listOf(StepTrail.LABEL_L500, StepTrail.LABEL_BE1000, StepTrail.SWITCH_V5, StepTrail.DIAG_V5)) assertTrue(l.contains(StepTrail.STATUS), l)
        assertEquals(StepTrail.LABEL, StepTrail.labelOf(null))
        assertEquals(StepTrail.LABEL_L500, StepTrail.labelOf(Lock.L500)); assertEquals(StepTrail.LABEL_BE1000, StepTrail.labelOf(Lock.BE1000))
        assertTrue(!StepTrail.DIAG_V5.contains("₹"), "the diagnostics hide rupee figures")
        assertTrue(StepTrail.isRuleWhy(StepTrail.WHY_LOCK))
        assertEquals("step lock (failed gates, Boss's exception)", StepTrail.words(StepTrail.WHY_LOCK))
        assertTrue(StepTrail.LOCK_RECORD.contains("1,097") && StepTrail.LOCK_RECORD.contains("₹98,239") && StepTrail.LOCK_RECORD.contains("failed the gates"))
    }

    @Test fun theStopIsNamedByItsLevel() {
        val be = StepTrail.breakevenLevel(300.0, 50)!!
        assertEquals(StepTrail.WHY_TRAIL, StepTrail.whyAt(be, 300.0, 50, lock = Lock.L500, qty = 50))
        for (k in 0..6) assertEquals(StepTrail.WHY_LOCK, StepTrail.whyAt(StepTrail.lockKeep(300.0, 50, 150, k, Lock.L500), 300.0, 50, lock = Lock.L500, qty = 150), "k=$k")
        assertNull(StepTrail.whyAt(311.7, 300.0, 50), "V4 trade: no lock to name")
        assertNull(StepTrail.whyAt(255.0, 300.0, 50, lock = Lock.L500, qty = 50), "the arm's own -15%")
        assertNull(StepTrail.whyAt(316.0, 300.0, 50, lock = Lock.L500, qty = 50), "between the levels")
        assertNull(StepTrail.whyAt(311.7, 300.0, 50, lock = Lock.BE1000, qty = 50), "Liquidity's lock is the breakeven only")
        assertNull(StepTrail.whyAt(Double.NaN, 300.0, 50, lock = Lock.L500, qty = 50))
        assertNull(StepTrail.whyAt(311.7, 300.0, 50, lock = Lock.L500, qty = 0))
    }

    // ---- Liquidity: breakeven at +10% or +₹1,000 a lot, whichever first ----------------------------------------------------

    @Test fun liquidityBreakevenAtWhicheverComesFirst() {
        // A dear option (300, lot 50): +₹1,000 a lot is reached at 321.70 (+7.2%), before +10% (330): the breakeven from 321.70.
        val be = StepTrail.breakevenLevel(300.0, 50)!!
        assertNull(StepTrail.levelOf(300.0, 50, 321.65, lock = Lock.BE1000, qty = 50))
        assertEquals(be, StepTrail.levelOf(300.0, 50, 321.7, lock = Lock.BE1000, qty = 50)!!, 1e-9)
        assertNull(StepTrail.levelOf(300.0, 50, 321.7), "V4 alone waits for +10%")
        // Nothing more: far above, still the breakeven (never a step lock on Liquidity).
        assertEquals(be, StepTrail.levelOf(300.0, 50, 500.0, lock = Lock.BE1000, qty = 50)!!, 1e-9)
        // A cheap option (80, lot 30): +10% (88) comes long before +₹1,000 (115.20): the +10% breakeven, as V4.
        assertEquals(StepTrail.breakevenLevel(80.0, 30)!!, StepTrail.levelOf(80.0, 30, 88.0, lock = Lock.BE1000, qty = 30)!!, 1e-9)
        // 2 lots: +₹2,000 on the pair.
        val t2 = StepTrail.lockTrigger(300.0, 50, 100, 0)!!
        assertTrue(StepTrail.netAt(300.0, t2, 100) >= 2000.0 - 1e-6)
        assertNull(StepTrail.levelOf(300.0, 50, t2 - 0.05, lock = Lock.BE1000, qty = 100))
        assertEquals(be, StepTrail.levelOf(300.0, 50, t2, lock = Lock.BE1000, qty = 100)!!, 1e-9)
    }

    @Test fun r51StepLockAboveTheBreakevenWhenHigher() {
        // At +10% (330) R51 has the +₹500 lock (311.70) above the breakeven (about 301.x): the higher wins.
        assertEquals(311.7, StepTrail.levelOf(300.0, 50, 330.0, lock = Lock.L500, qty = 50)!!, 1e-9)
        // A cheap option: at +10% (88) no step yet (+₹1,000 is at 115.20): the breakeven alone.
        assertEquals(StepTrail.breakevenLevel(80.0, 30)!!, StepTrail.levelOf(80.0, 30, 90.0, lock = Lock.L500, qty = 30)!!, 1e-9)
        assertEquals(98.5, StepTrail.levelOf(80.0, 30, 115.2, lock = Lock.L500, qty = 30)!!, 1e-9)
    }

    // ---- the passed arms' walk (R51 / R72 paper) ----------------------------------------------------------------------------

    private val v4 = DonchianArm.EXITS.copy(target = null, stepTrail = PassedArms.StepTrailOn(50))
    private val v5 = DonchianArm.EXITS.copy(target = null, stepTrail = PassedArms.StepTrailOn(50, lock = Lock.L500, qty = 50))

    @Test fun theWalkLocksFromTheNextMinuteAndOnlyUp() {
        val up1 = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 331.75, 300.0, 331.0))
        val held = PassedArms.exitOnBars(300.0, t(10, 0), up1, null, t(10, 2), v5)
        assertNull(held.exit); assertEquals(321.7, held.lock!!, 1e-9, "+1,500 reached: +1,000 locked")
        // It falls back: the lock holds (never down) and sells at 321.70 as a step lock.
        val fell = up1 + bar(10, 2, 330.0, 330.0, 325.0, 326.0) + bar(10, 3, 324.0, 324.0, 320.0, 320.5)
        val w = PassedArms.exitOnBars(300.0, t(10, 0), fell, null, t(10, 4), v5)
        assertEquals(PassedArms.ExitKind.STEP_LOCK, w.exit!!.kind); assertEquals(321.7, w.exit!!.price, 1e-9)
        assertEquals(321.7, PassedArms.booked(PassedArms.ExitKind.STEP_LOCK, 318.0, null, 321.7), 1e-9)
        // The minute that reaches a step never sells on its own low (the lock acts from the next minute).
        val same = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 331.75, 300.0, 301.0))
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), same, null, t(10, 2), v5).exit)
        // The entry minute's high never starts it.
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), listOf(bar(10, 0, 300.0, 340.0, 300.0, 300.0)), null, t(10, 1), v5).lock)
    }

    @Test fun aGapBelowTheLockSellsAtTheOpen() {
        val bars = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 322.0, 300.0, 321.0), bar(10, 2, 305.0, 306.0, 300.0, 301.0))
        val w = PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 3), v5)
        assertEquals(PassedArms.ExitKind.STEP_LOCK, w.exit!!.kind); assertEquals(305.0, w.exit!!.price, 1e-9, "a stop order: the gap fills under it")
    }

    @Test fun aTradeOpenAcrossTheSwitchKeepsItsOwnRule() {
        // The same minutes: the trade that entered under V4 (no lock) holds through 320; the one with the lock is out at 311.70.
        val bars = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 325.0, 300.0, 324.0), bar(10, 2, 320.0, 320.0, 310.0, 311.0))
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 3), v4).exit)
        assertEquals(311.7, PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 3), v5).exit!!.price, 1e-9)
        // A restart that lost the minutes keeps the lock it had (the known lock), named the step lock.
        val r = PassedArms.exitOnBars(300.0, t(10, 0), emptyList(), 311.0, t(10, 5), v5, known = t(10, 2) to 311.7)
        assertEquals(PassedArms.ExitKind.STEP_LOCK, r.exit!!.kind)
    }

    @Test fun staleOrMissingMinutesNeverMoveIt() {
        val bars = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, Double.NaN, Double.NaN, Double.NaN, Double.NaN),
            bar(10, 2, 300.0, 315.0, 300.0, 314.0))
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 3), v5).lock)
        // The price now alone (between minutes) never raises the lock.
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), bars, 340.0, t(10, 3), v5).lock)
    }

    @Test fun theSquareOffStillSellsOnExpiryDay() {
        val bars = listOf(bar(14, 58, 300.0, 300.0, 300.0, 300.0), bar(14, 59, 300.0, 332.0, 300.0, 331.0))
        val x = v5.copy(squareOff = LocalTime.of(15, 5), tmaxMinutes = null)
        val w = PassedArms.exitOnBars(300.0, t(14, 58), bars, 330.0, t(15, 5), x)
        assertEquals(PassedArms.ExitKind.SQUARE_OFF, w.exit!!.kind); assertEquals(321.7, w.lock!!, 1e-9)
    }

    // ---- the live mirror (R51 / R72 live) -------------------------------------------------------------------------------

    @Test fun liveTheStopIsModifiedUpToTheLockNeverTwoExits() {
        val lk = StepTrail.levelOf(300.0, 50, 332.0, lock = Lock.L500, qty = 50)
        assertNotNull(lk)
        val base = Look(side = 1, qty = 50, held = 50, ltp = 330.0, tick = 0.05, stopTrigger = 255.0, stopResting = true, ruleStop = lk,
            ruleWhy = StepTrail.WHY_LOCK, wanted = lk)
        val mv = LiveMirror.decide(base)
        assertEquals(Step.MOVE_STOP, mv.step); assertEquals(lk!!, mv.to!!, 1e-9)
        // Already resting at the lock: Zerodha's own stop sells it (the app holds: never a second exit).
        assertEquals(Step.HOLD, LiveMirror.decide(base.copy(stopTrigger = lk, ltp = lk)).step)
        // Could not rest there (refused) and the price is through it: the app sells, named the step lock.
        val s = LiveMirror.decide(base.copy(ltp = 321.0))
        assertEquals(Step.SELL, s.step); assertEquals(StepTrail.WHY_LOCK, s.why)
    }
}
