package com.optionslab.engine.orb

import com.optionslab.engine.orb.LiveMirror.Look
import com.optionslab.engine.orb.LiveMirror.Step
import com.optionslab.engine.orb.StepTrail.Ladder
import com.optionslab.engine.orb.StepTrail.Lock
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
 * 16 Nov 2026, the fourth version of Boss's exception (Boss: "For r51/r72 use Step lock (+₹500 at +₹1,000, and so on) And for liquidity
 * use Breakeven at +₹1,000"; CLAUDE.md's fifth exception, failed R186's gates): [StepTrail.Ladder]'s levels against R186's own
 * r186engine.lock_levels (its output, run on the same charge schedule as [StepTrail.roundTrip]), and how the passed arms' walk and the
 * live mirror use them.
 */
class StepLockTest {
    private val day = LocalDate.of(2026, 11, 16)
    private fun t(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private fun bar(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Bar(t(h, m), o, hi, lo, c)

    private data class Case(val entry: Double, val lot: Int, val lots: Int, val ticks: Double, val trig: List<Double>, val keep: List<Double>,
                            val beTrig: Double, val be: Double)

    /**
     * Parity: r186engine.lock_levels("L500", ...) and ("LBE", ...) for these fills, lots and half-spread ticks (R186's tks), printed by
     * running the research's own code with the app's F&O charge schedule on the whole quantity (L500: triggers and locks for k = 0..3; LBE:
     * its one trigger and its ₹0 lock).
     */
    private val parity = listOf(
        Case(300.0, 50, 1, 1.0, listOf(321.7, 331.75, 341.75, 351.8), listOf(312.1, 322.15, 332.15, 342.2), 321.7, 302.1),
        Case(300.0, 50, 3, 1.0, listOf(321.1, 331.1, 341.15, 351.15), listOf(311.5, 321.5, 331.55, 341.55), 321.1, 301.45),
        Case(150.0, 75, 1, 1.0, listOf(164.35, 171.05, 177.75, 184.4), listOf(157.95, 164.65, 171.3, 178.0), 164.35, 151.25),
        Case(212.35, 65, 2, 1.0, listOf(228.65, 236.35, 244.05, 251.8), listOf(221.25, 229.0, 236.7, 244.4), 228.65, 213.55),
        Case(80.0, 30, 1, 1.0, listOf(115.2, 131.9, 148.6, 165.3), listOf(98.7, 115.4, 132.1, 148.85), 115.2, 81.95),
        Case(600.0, 30, 2, 1.0, listOf(635.65, 652.35, 669.05, 685.75), listOf(619.65, 636.35, 653.1, 669.8), 635.65, 602.95),
        Case(300.0, 50, 1, 2.0, listOf(321.7, 331.75, 341.75, 351.8), listOf(312.2, 322.25, 332.25, 342.3), 321.7, 302.2),
        Case(300.0, 50, 3, 2.0, listOf(321.1, 331.1, 341.15, 351.15), listOf(311.6, 321.6, 331.65, 341.65), 321.1, 301.55),
        Case(212.35, 65, 2, 2.0, listOf(228.65, 236.35, 244.05, 251.8), listOf(221.35, 229.1, 236.8, 244.5), 228.65, 213.65),
        Case(600.0, 30, 2, 2.0, listOf(635.65, 652.35, 669.05, 685.75), listOf(619.75, 636.45, 653.2, 669.9), 635.65, 603.05),
    )

    @Test fun theLevelsAreR186sOwn() {
        for (c in parity) {
            val l = Ladder(c.entry, c.lot, c.lot * c.lots, Lock.L500, slipTicks = c.ticks)
            val b = Ladder(c.entry, c.lot, c.lot * c.lots, Lock.BE1000, slipTicks = c.ticks)
            for (k in 0..3) {
                assertEquals(c.trig[k], l.trigger(k)!!, 1e-9, "trigger $c k=$k")
                assertEquals(c.keep[k], l.keep(k)!!, 1e-9, "lock $c k=$k")
            }
            assertEquals(c.beTrig, b.trigger(0)!!, 1e-9, "BE1000 trigger $c")
            assertEquals(c.be, b.keep(0)!!, 1e-9, "BE1000 lock $c")
            assertNull(b.keep(1), "BE1000 has one step")
        }
        // The app's default half-spread is 1 tick.
        assertEquals(312.1, StepTrail.lockKeep(300.0, 50, 50, 0, Lock.L500)!!, 1e-9)
        assertEquals(321.7, StepTrail.lockTrigger(300.0, 50, 50, 0)!!, 1e-9)
    }

    @Test fun eachLevelNetsItsRupeesAfterChargesAndTheStopSlippage() {
        for ((e, lot, lots) in listOf(Triple(300.0, 50, 1), Triple(300.0, 50, 3), Triple(95.5, 75, 2), Triple(42.0, 15, 4))) {
            val q = lot * lots
            val l = Ladder(e, lot, q, Lock.L500)
            for (k in 0..5) {
                val tr = l.trigger(k)!!
                val kp = l.keep(k)!!
                assertTrue(StepTrail.netAt(e, tr, q) >= (1000.0 + 500 * k) * lots - 0.5, "trigger nets its step: $e x $q k=$k")
                assertTrue(StepTrail.netAt(e, tr - 0.05, q) < (1000.0 + 500 * k) * lots, "one tick less does not")
                // The stop sale fills at 0.999 of the lock less two ticks: that still nets the kept rupees.
                val sold = kp * StepTrail.SLIP_FILL - 2 * 0.05
                assertTrue(StepTrail.netAt(e, sold, q) >= (500.0 + 500 * k) * lots - 0.5, "lock nets its keep after slippage: $e x $q k=$k")
                assertTrue(kp < tr, "the lock sits under its trigger")
            }
        }
        // The stop price is null when the whole premium is worth less; bad inputs give nothing.
        assertNull(StepTrail.stopPrice(1.0, 50, -1000.0))
        assertNull(StepTrail.stopPrice(0.0, 50, 0.0)); assertNull(StepTrail.stopPrice(300.0, 0, 0.0)); assertNull(StepTrail.stopPrice(300.0, 50, Double.NaN))
        assertNull(StepTrail.stopPrice(300.0, 50, 0.0, 0.0)); assertNull(StepTrail.stopPrice(300.0, 50, 0.0, slipTicks = -1.0))
        assertNull(StepTrail.netPrice(0.0, 50, 1000.0)); assertNull(StepTrail.netPrice(300.0, 0, 1000.0)); assertNull(StepTrail.netPrice(300.0, 50, Double.NaN))
        assertNull(StepTrail.netPrice(300.0, 50, 1000.0, 0.0)); assertNull(StepTrail.netPrice(Double.NaN, 50, 1000.0)); assertNull(StepTrail.netPrice(300.0, 50, 1000.0, Double.NaN))
    }

    @Test fun oneMemoisingLadderPerTrade() {
        StepTrail.clearLadders()
        val a = StepTrail.ladder(300.0, 50, 50, Lock.L500)
        assertTrue(a === StepTrail.ladder(300.0, 50, 50, Lock.L500), "the same trade: the same ladder")
        assertFalse(a === StepTrail.ladder(300.0, 50, 100, Lock.L500), "another entry quantity: its own")
        assertFalse(a === StepTrail.ladder(300.0, 50, 50, Lock.BE1000))
        for (i in 0 until 600) StepTrail.ladder(100.0 + i * 0.05, 75, 75, Lock.L500)
        assertTrue(StepTrail.laddersKept() <= 512, "bounded")
        assertEquals(312.1, StepTrail.ladder(300.0, 50, 50, Lock.L500).keep(0)!!, 1e-9)
        StepTrail.clearLadders()
        assertEquals(0, StepTrail.laddersKept())
    }

    @Test fun theStopSaleIsBookedAsR186BooksIt() {
        // r51 engine's stop fill: round(px x 0.999, 2) - 2 x ticks x 0.05 (printed by the research's own formula).
        assertEquals(311.69, StepTrail.stopFill(312.1), 1e-9)
        assertEquals(304.6, StepTrail.stopFill(305.0), 1e-9)   // np.round(30469.5) = 30470 (half-even), as the research
        assertEquals(311.59, StepTrail.stopFill(312.1, slipTicks = 2.0), 1e-9)
        assertEquals(0.0, StepTrail.stopFill(0.05), 1e-9)
    }

    @Test fun exactlyAtPlusOneThousandAndNotATickBefore() {
        val l = Ladder(300.0, 50, 50, Lock.L500)
        val tr = l.trigger(0)!!
        assertNull(l.step(tr - 0.05)); assertNull(l.level(tr - 0.05))
        assertEquals(0, l.step(tr)); assertEquals(312.1, l.level(tr)!!, 1e-9)
        assertEquals(0, Ladder(300.0, 50, 50, Lock.BE1000).step(tr))
    }

    @Test fun theStepsGoUpInFiveHundredsAndAreFoundFromAnyBest() {
        val l = Ladder(300.0, 50, 50, Lock.L500)
        assertEquals(312.1, l.level(331.7)!!, 1e-9, "just under +1,500: still +500")
        assertEquals(322.15, l.level(331.75)!!, 1e-9, "at +1,500 the lock is the +1,000 one")
        assertEquals(332.15, l.level(341.75)!!, 1e-9)
        assertEquals(342.2, l.level(351.8)!!, 1e-9)
        var best = 300.0
        while (best < 480.0) {
            var k = -1
            while (l.trigger(k + 1)!! <= best + 1e-9) k++
            assertEquals(if (k < 0) null else k, l.step(best), "best $best")
            best = Math.round((best + 0.05) * 100) / 100.0
        }
        val far = l.step(3000.0)!!
        assertTrue(l.trigger(far)!! <= 3000.0 && l.trigger(far + 1)!! > 3000.0, "$far")
    }

    @Test fun theLockNeverLowersAsTheBestRises() {
        for (lock in Lock.entries) {
            val l = Ladder(150.0, 75, 150, lock)
            var last = Double.NEGATIVE_INFINITY
            var best = 150.0
            while (best < 260.0) {
                StepTrail.trailLevel(150.0, 75, best, ladder = l)?.let { assertTrue(it >= last - 1e-9, "$lock $best: $it < $last"); last = it }
                best += 0.05
            }
        }
    }

    @Test fun perLotOnTheEntryQuantity() {
        val one = Ladder(300.0, 50, 50, Lock.L500).trigger(0)!!
        val three = Ladder(300.0, 50, 150, Lock.L500).trigger(0)!!
        assertTrue(three <= one && one - three < 1.0, "$one vs $three")
        assertTrue(StepTrail.netAt(300.0, three, 150) >= 3000.0 - 0.5)
        val l = Ladder(300.0, 50, 100, Lock.L500)
        assertTrue(l.isFor(300.0, 50, 100, Lock.L500, 0.05))
        assertFalse(l.isFor(300.0, 50, 50, Lock.L500, 0.05), "a different quantity is another ladder")
        assertEquals(2.0, l.lots, 1e-9)
        for (bad in listOf(Ladder(300.0, 0, 50, Lock.L500), Ladder(300.0, 50, 0, Lock.L500), Ladder(0.0, 50, 50, Lock.L500), Ladder(300.0, 50, 50, Lock.L500, 0.0))) {
            assertNull(bad.trigger(0)); assertNull(bad.keep(0)); assertNull(bad.step(400.0)); assertNull(bad.why(312.1, null))
        }
        assertNull(l.trigger(-1)); assertNull(l.keep(-1)); assertNull(l.trigger(401))
    }

    @Test fun nothingWithoutALadderABestOrAFill() {
        val l = Ladder(300.0, 50, 50, Lock.L500)
        assertNull(l.level(null)); assertNull(l.level(Double.NaN), "a stale or broken price never moves it")
        assertNull(StepTrail.trailLevel(300.0, 50, 325.0), "V4 alone: nothing until +10%")
        assertEquals(StepTrail.breakevenLevel(300.0, 50)!!, StepTrail.trailLevel(300.0, 50, 400.0)!!, 1e-9)
        assertEquals(l.level(325.0)!!, StepTrail.trailLevel(300.0, 50, 325.0, ladder = l)!!, 1e-9)
    }

    @Test fun theLabelsAndTheArms() {
        assertEquals(Lock.L500, StepTrail.lockFor(DonchianArm.SOURCE))
        assertEquals(Lock.L500, StepTrail.lockFor(BreadthDonchianArm.SOURCE))
        assertEquals(Lock.BE1000, StepTrail.lockFor(LiquidityRules.ARM.source))
        for (b in LiquidityRules.BOOKS) assertEquals(Lock.BE1000, StepTrail.lockFor(b.source))
        assertNull(StepTrail.lockFor("orb_fresh"))
        for (l in listOf(StepTrail.LABEL_L500, StepTrail.LABEL_BE1000, StepTrail.SWITCH_LOCK, StepTrail.DIAG_LOCK, StepTrail.SWITCH, StepTrail.DIAG))
            assertTrue(l.contains(StepTrail.STATUS), l)
        assertEquals(StepTrail.LABEL, StepTrail.labelOf(null))
        assertEquals(StepTrail.LABEL_L500, StepTrail.labelOf(Lock.L500)); assertEquals(StepTrail.LABEL_BE1000, StepTrail.labelOf(Lock.BE1000))
        // The diagnostics hide every rupee figure: no rupee sign, no "Rs", no grouped figure.
        for (d in listOf(StepTrail.DIAG, StepTrail.DIAG_LOCK)) assertFalse(Regex("₹|Rs|\\d,\\d").containsMatchIn(d), d)
        assertTrue(StepTrail.isRuleWhy(StepTrail.WHY_LOCK))
        assertEquals("step lock (failed gates, Boss's exception)", StepTrail.words(StepTrail.WHY_LOCK))
        assertTrue(StepTrail.LOCK_RECORD.contains("1,097") && StepTrail.LOCK_RECORD.contains("₹98,239") && StepTrail.LOCK_RECORD.contains("failed the gates"))
    }

    @Test fun theStopIsNamedByItsLevel() {
        val be = StepTrail.breakevenLevel(300.0, 50)!!
        val l = Ladder(300.0, 50, 150, Lock.L500)
        assertEquals(StepTrail.WHY_TRAIL, StepTrail.whyAt(be, 300.0, 50, ladder = l))
        for (k in 0..6) assertEquals(StepTrail.WHY_LOCK, StepTrail.whyAt(l.keep(k), 300.0, 50, ladder = l), "k=$k (best not known: any step)")
        for (k in 0..6) assertEquals(StepTrail.WHY_LOCK, StepTrail.whyAt(l.keep(k), 300.0, 50, ladder = l, best = l.trigger(k)), "k=$k reached")
        // Review: a level at step 1's lock when the best reached only step 0 (an R64 lock landing there) is never named the step lock.
        assertNull(StepTrail.whyAt(l.keep(1), 300.0, 50, ladder = l, best = l.trigger(0)))
        assertNull(StepTrail.whyAt(l.keep(0), 300.0, 50, ladder = l, best = l.trigger(0)!! - 0.05), "no step reached yet")
        assertNull(StepTrail.whyAt(312.1, 300.0, 50), "V4 trade: no lock to name")
        assertNull(StepTrail.whyAt(255.0, 300.0, 50, ladder = l), "the arm's own -15%")
        assertNull(StepTrail.whyAt(316.0, 300.0, 50, ladder = l), "between the levels")
        val b = Ladder(300.0, 50, 50, Lock.BE1000)
        assertEquals(StepTrail.WHY_TRAIL, StepTrail.whyAt(302.1, 300.0, 50, ladder = b), "Liquidity's +₹1,000 breakeven")
        assertNull(StepTrail.whyAt(302.1, 300.0, 50, ladder = b, best = 321.65), "not reached yet")
        assertNull(StepTrail.whyAt(312.1, 300.0, 50, ladder = b), "Liquidity's lock is the breakeven only")
        assertNull(StepTrail.whyAt(Double.NaN, 300.0, 50, ladder = l))
        assertNull(l.why(null, null))
    }

    // ---- Liquidity: breakeven at +10% or +₹1,000 a lot, whichever first ----------------------------------------------------

    @Test fun liquidityBreakevenAtWhicheverComesFirst() {
        // A dear option (300, lot 50): +₹1,000 a lot at 321.70 (+7.2%), before +10% (330): R186's ₹0 stop (302.10) from 321.70.
        val l = Ladder(300.0, 50, 50, Lock.BE1000)
        assertNull(StepTrail.levelOf(300.0, 50, 321.65, ladder = l))
        assertEquals(302.1, StepTrail.levelOf(300.0, 50, 321.7, ladder = l)!!, 1e-9)
        assertNull(StepTrail.levelOf(300.0, 50, 321.7), "V4 alone waits for +10%")
        // Nothing more: far above, the higher of the two breakevens.
        val v4 = StepTrail.breakevenLevel(300.0, 50)!!
        assertEquals(maxOf(302.1, v4), StepTrail.levelOf(300.0, 50, 500.0, ladder = l)!!, 1e-9)
        // A cheap option (80, lot 30): +10% (88) comes long before +₹1,000 (115.20): V4's breakeven, as V4.
        assertEquals(StepTrail.breakevenLevel(80.0, 30)!!, StepTrail.levelOf(80.0, 30, 88.0, ladder = Ladder(80.0, 30, 30, Lock.BE1000))!!, 1e-9)
        // 2 lots at 600: +₹2,000 on the pair at 635.65; the ₹0 stop 602.95.
        val two = Ladder(600.0, 30, 60, Lock.BE1000)
        assertNull(StepTrail.levelOf(600.0, 30, 635.6, ladder = two))
        assertEquals(602.95, StepTrail.levelOf(600.0, 30, 635.65, ladder = two)!!, 1e-9)
    }

    @Test fun r51StepLockAboveTheBreakevenWhenHigher() {
        val l = Ladder(300.0, 50, 50, Lock.L500)
        assertEquals(312.1, StepTrail.levelOf(300.0, 50, 330.0, ladder = l)!!, 1e-9)
        val cheap = Ladder(80.0, 30, 30, Lock.L500)
        assertEquals(StepTrail.breakevenLevel(80.0, 30)!!, StepTrail.levelOf(80.0, 30, 90.0, ladder = cheap)!!, 1e-9)
        assertEquals(98.7, StepTrail.levelOf(80.0, 30, 115.2, ladder = cheap)!!, 1e-9)
    }

    // ---- the passed arms' walk (R51 / R72 paper) ----------------------------------------------------------------------------

    private val v4 = DonchianArm.EXITS.copy(target = null, stepTrail = PassedArms.StepTrailOn(50))
    private val v5 = DonchianArm.EXITS.copy(target = null, stepTrail = PassedArms.StepTrailOn(50, lock = Lock.L500, qty = 50))

    @Test fun theWalkLocksFromTheNextMinuteAndOnlyUp() {
        val up1 = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 331.75, 300.0, 331.0))
        val held = PassedArms.exitOnBars(300.0, t(10, 0), up1, null, t(10, 2), v5)
        assertNull(held.exit); assertEquals(322.15, held.lock!!, 1e-9, "+1,500 reached: +1,000 locked")
        val fell = up1 + bar(10, 2, 330.0, 330.0, 325.0, 326.0) + bar(10, 3, 324.0, 324.0, 320.0, 320.5)
        val w = PassedArms.exitOnBars(300.0, t(10, 0), fell, null, t(10, 4), v5)
        assertEquals(PassedArms.ExitKind.STEP_LOCK, w.exit!!.kind); assertEquals(322.15, w.exit!!.price, 1e-9)
        // Booked where R186 books the stop sale (r51 engine: round(0.999 x 322.15, 2) - 2 x 1 tick = 321.73), never at the padded stop.
        assertEquals(321.73, PassedArms.booked(PassedArms.ExitKind.STEP_LOCK, 318.0, null, 322.15), 1e-9)
        assertTrue(StepTrail.netAt(300.0, 321.73, 50) >= 1000.0 - 0.5, "the booked sale nets the +₹1,000 it locked")
        val same = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 331.75, 300.0, 301.0))
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), same, null, t(10, 2), v5).exit)
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), listOf(bar(10, 0, 300.0, 340.0, 300.0, 300.0)), null, t(10, 1), v5).lock)
        // A lower high after a higher one changes nothing (the level is worked out only on a new best).
        val dip = up1 + bar(10, 2, 330.0, 330.5, 329.0, 330.0)
        assertEquals(322.15, PassedArms.exitOnBars(300.0, t(10, 0), dip, null, t(10, 3), v5).lock!!, 1e-9)
    }

    /** 16 Nov (review): one walk returns both the whole lock and the arm's own (R64's) lock alone, for the live copy ([PassedArms.Walk.ownLock]). */
    @Test fun oneWalkGivesTheArmsOwnLockApart() {
        val x = PassedArms.withLock(v5)
        val bars = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 393.0, 300.0, 392.0))
        val w = PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 2), x, breakeven = 301.0)
        assertEquals(301.0, w.ownLock!!, 1e-9, "R64's +30% rung: breakeven")
        assertTrue(w.lock!! > w.ownLock!!, "the step lock is above it in the whole lock")
        // Without R64's lock: no own lock at all, whatever the rule earned.
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 2), v5).ownLock)
        // A restart that lost the minutes keeps the own lock it had seen (knownOwn), apart from the whole lock.
        val r = PassedArms.exitOnBars(300.0, t(10, 0), emptyList(), 330.0, t(10, 5), x, known = t(10, 2) to 330.0, breakeven = 301.0,
            knownOwn = t(10, 2) to 301.0)
        assertEquals(301.0, r.ownLock!!, 1e-9); assertEquals(330.0, r.lock!!, 1e-9)
    }

    @Test fun aGapBelowTheLockSellsAtTheOpen() {
        val bars = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 322.0, 300.0, 321.0), bar(10, 2, 305.0, 306.0, 300.0, 301.0))
        val w = PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 3), v5)
        assertEquals(PassedArms.ExitKind.STEP_LOCK, w.exit!!.kind); assertEquals(305.0, w.exit!!.price, 1e-9, "a stop order: the gap fills under it")
    }

    @Test fun aTradeOpenAcrossTheSwitchKeepsItsOwnRule() {
        val bars = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, 300.0, 325.0, 300.0, 324.0), bar(10, 2, 320.0, 320.0, 310.0, 311.0))
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 3), v4).exit)
        assertEquals(312.1, PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 3), v5).exit!!.price, 1e-9)
        val r = PassedArms.exitOnBars(300.0, t(10, 0), emptyList(), 311.0, t(10, 5), v5, known = t(10, 2) to 312.1)
        assertEquals(PassedArms.ExitKind.STEP_LOCK, r.exit!!.kind)
    }

    @Test fun staleOrMissingMinutesNeverMoveIt() {
        val bars = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0), bar(10, 1, Double.NaN, Double.NaN, Double.NaN, Double.NaN),
            bar(10, 2, 300.0, 315.0, 300.0, 314.0))
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), bars, null, t(10, 3), v5).lock)
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), bars, 340.0, t(10, 3), v5).lock)
    }

    @Test fun theSquareOffStillSellsOnExpiryDay() {
        val bars = listOf(bar(14, 58, 300.0, 300.0, 300.0, 300.0), bar(14, 59, 300.0, 332.0, 300.0, 331.0))
        val x = v5.copy(squareOff = LocalTime.of(15, 5), tmaxMinutes = null)
        val w = PassedArms.exitOnBars(300.0, t(14, 58), bars, 330.0, t(15, 5), x)
        assertEquals(PassedArms.ExitKind.SQUARE_OFF, w.exit!!.kind); assertEquals(322.15, w.lock!!, 1e-9)
    }

    // ---- the live mirror (R51 / R72 live) -------------------------------------------------------------------------------

    @Test fun liveTheStopIsModifiedUpToTheLockNeverTwoExits() {
        val lk = StepTrail.levelOf(300.0, 50, 332.0, ladder = Ladder(300.0, 50, 50, Lock.L500))
        assertNotNull(lk)
        val base = Look(side = 1, qty = 50, held = 50, ltp = 330.0, tick = 0.05, stopTrigger = 255.0, stopResting = true, ruleStop = lk,
            ruleWhy = StepTrail.WHY_LOCK, wanted = lk)
        val mv = LiveMirror.decide(base)
        assertEquals(Step.MOVE_STOP, mv.step); assertEquals(lk!!, mv.to!!, 1e-9)
        assertEquals(Step.HOLD, LiveMirror.decide(base.copy(stopTrigger = lk, ltp = lk)).step)
        val s = LiveMirror.decide(base.copy(ltp = 321.0))
        assertEquals(Step.SELL, s.step); assertEquals(StepTrail.WHY_LOCK, s.why)
    }

    /**
     * 20 Nov 2026 (audit B5, item 6: the locks stalled while the option's minutes stopped coming): a best the closed minutes do not show
     * ([PassedArms.exitOnBars]' high: the stream's ticks, a fresher read, the price now) raises the same lock a minute's high would, and
     * nothing else; without it (null, the research) the walk is exactly as before.
     */
    @Test fun aBestTheMinutesDoNotShowRaisesTheSameLock() {
        // The minutes stop at 10:00 (the entry minute); the option traded at 331.75 since: +1,000 locked at 322.15, as from a 10:01 minute.
        val stale = listOf(bar(10, 0, 300.0, 300.0, 300.0, 300.0))
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), stale, 331.0, t(10, 4), v5).lock, "before: the lock waited for the minutes")
        val w = PassedArms.exitOnBars(300.0, t(10, 0), stale, 331.0, t(10, 4), v5, high = 331.75)
        assertNull(w.exit); assertEquals(322.15, w.lock!!, 1e-9)
        assertEquals(PassedArms.exitOnBars(300.0, t(10, 0), stale + bar(10, 1, 300.0, 331.75, 300.0, 331.0), 331.0, t(10, 4), v5).lock!!, w.lock!!, 1e-9,
            "the same level as the minute's high")
        // The price now back under it: out at the lock, named the step lock.
        val out = PassedArms.exitOnBars(300.0, t(10, 0), stale, 321.0, t(10, 4), v5, high = 331.75)
        assertEquals(PassedArms.ExitKind.STEP_LOCK, out.exit!!.kind)
        // A lower best than the minutes already show, or none, a NaN or zero: nothing changes.
        val bars = stale + bar(10, 1, 300.0, 331.75, 300.0, 331.0)
        for (h in listOf(null, 310.0, Double.NaN, 0.0, -5.0))
            assertEquals(322.15, PassedArms.exitOnBars(300.0, t(10, 0), bars, 331.0, t(10, 2), v5, high = h).lock!!, 1e-9)
        for (h in listOf(310.0, Double.NaN, 0.0)) assertNull(PassedArms.exitOnBars(300.0, t(10, 0), stale, 305.0, t(10, 2), v5, high = h).lock)
        // Never a target by itself, never a sale above the price now, and the arm's own stop stays.
        val plain = DonchianArm.EXITS
        assertNull(PassedArms.exitOnBars(300.0, t(10, 0), stale, 330.0, t(10, 2), plain, high = 400.0).exit, "the +30% target is for the price itself")
        // R64's ladder (the arm's own lock) rises the same way, and its own lock apart.
        val x = PassedArms.withLock(v5)
        val r = PassedArms.exitOnBars(300.0, t(10, 0), stale, 392.0, t(10, 2), x, breakeven = 301.0, high = 393.0)
        assertEquals(301.0, r.ownLock!!, 1e-9, "R64's +30% rung: breakeven")
        assertTrue(r.lock!! > r.ownLock!!)
        // Never down: a remembered higher lock stays.
        assertEquals(330.0, PassedArms.exitOnBars(300.0, t(10, 0), stale, 335.0, t(10, 4), v5, known = t(10, 2) to 330.0, high = 331.75).lock!!, 1e-9)
    }
}
