package com.optionslab.ira

import com.optionslab.ira.ManualCoverRule.Facts
import com.optionslab.ira.ManualCoverRule.Plan
import com.optionslab.ira.ManualCoverRule.Pos
import com.optionslab.ira.ManualCoverRule.Skip
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 7 Nov: "Stop and target on my manual positions" - the pure decisions ([ManualCoverRule]). */
class ManualCoverRuleTest {
    private val sym = "NIFTY26N0925150CE"
    private val pos = Pos(sym, "NFO", "NRML", 130, 125.35, 120.0)
    private val key = "NFO:$sym"

    // ---- which positions ------------------------------------------------------------------------------------------------------

    @Test fun aBoughtNfoOrBfoOptionInMisOrNrmlIsCovered() {
        assertTrue(ManualCoverRule.covered(pos))
        assertTrue(ManualCoverRule.covered(pos.copy(product = "MIS")))
        assertTrue(ManualCoverRule.covered(pos.copy(symbol = "NIFTY26N0925150PE")))
        assertTrue(ManualCoverRule.covered(pos.copy(symbol = "SENSEX26N1382000CE", exchange = "BFO")))
        assertNull(ManualCoverRule.skip(pos, Facts()))
    }

    @Test fun shortsFuturesMcxCashAndCncAreNeverCovered() {
        assertEquals(Skip.NOT_COVERED, ManualCoverRule.skip(pos.copy(qty = -130), Facts()), "a short")
        assertEquals(Skip.NOT_COVERED, ManualCoverRule.skip(pos.copy(qty = 0), Facts()), "flat")
        assertEquals(Skip.NOT_COVERED, ManualCoverRule.skip(pos.copy(symbol = "NIFTY26NOVFUT"), Facts()), "a future")
        assertEquals(Skip.NOT_COVERED, ManualCoverRule.skip(pos.copy(symbol = "CRUDEOIL26NOV6000CE", exchange = "MCX"), Facts()), "MCX")
        assertEquals(Skip.NOT_COVERED, ManualCoverRule.skip(pos.copy(symbol = "RELIANCE", exchange = "NSE", product = "CNC"), Facts()), "cash")
        assertEquals(Skip.NOT_COVERED, ManualCoverRule.skip(pos.copy(product = "CNC"), Facts()), "CNC")
        assertEquals(Skip.NOT_COVERED, ManualCoverRule.skip(pos.copy(product = "MTF"), Facts()), "another product")
    }

    // ---- every skip rule ---------------------------------------------------------------------------------------------------------

    @Test fun eachReasonToLeaveItAlone() {
        assertEquals(Skip.FOLLOWED, ManualCoverRule.skip(pos, Facts(followed = setOf(sym))))
        assertEquals(Skip.PROTECTED, ManualCoverRule.skip(pos, Facts(protectedNow = setOf(sym))))
        assertEquals(Skip.HANDS_OFF, ManualCoverRule.skip(pos, Facts(handsOff = setOf(key))))
        assertEquals(Skip.WORKING_ORDER, ManualCoverRule.skip(pos, Facts(workingOrders = setOf(key))))
        assertEquals(Skip.GTT, ManualCoverRule.skip(pos, Facts(activeGtts = setOf(key))))
        assertEquals(Skip.APP_ORDER, ManualCoverRule.skip(pos, Facts(appOrders = setOf(key))))
        assertEquals(Skip.EXPIRING, ManualCoverRule.skip(pos, Facts(expiring = setOf(key))))
        // Another contract's facts, or the same symbol on another exchange's key, never apply.
        val other = Facts(followed = setOf("X"), protectedNow = setOf("X"), handsOff = setOf("BFO:$sym"), workingOrders = setOf("BFO:$sym"),
            activeGtts = setOf("NFO:X"), appOrders = setOf("NFO:X"), endedToday = listOf(ManualCoverRule.Ended("X", "removed")))
        assertNull(ManualCoverRule.skip(pos, other))
        // The first reason in order is given.
        assertEquals(Skip.FOLLOWED, ManualCoverRule.skip(pos, Facts(followed = setOf(sym), workingOrders = setOf(key))))
    }

    @Test fun aProtectionEndedTodayByBossIsNotAddedAgainButANaturalEndIs() {
        for (note in listOf("removed", "stop cancelled", "stop rejected", "exit orders no longer working", "covered by another exit",
                "GTT fired, part left", "stop not placed"))
            assertEquals(Skip.ENDED_TODAY, ManualCoverRule.skip(pos, Facts(endedToday = listOf(ManualCoverRule.Ended(sym, note)))), note)
        for (note in listOf("position closed", "GTT fired", "replaced", "stop filled", "target filled"))
            assertNull(ManualCoverRule.skip(pos, Facts(endedToday = listOf(ManualCoverRule.Ended(sym, note)))), note)
        // One natural end and one removal the same day: Boss's removal wins.
        assertEquals(Skip.ENDED_TODAY, ManualCoverRule.skip(pos, Facts(endedToday = listOf(ManualCoverRule.Ended(sym, "position closed"),
            ManualCoverRule.Ended(sym, "removed")))))
    }

    // ---- hours -------------------------------------------------------------------------------------------------------------------

    @Test fun onlyOnATradingDayFrom0915To1524() {
        assertFalse(ManualCoverRule.inHours(true, 9 * 60 + 14))
        assertTrue(ManualCoverRule.inHours(true, 9 * 60 + 15))
        assertTrue(ManualCoverRule.inHours(true, 12 * 60))
        assertTrue(ManualCoverRule.inHours(true, 15 * 60 + 24))
        assertFalse(ManualCoverRule.inHours(true, 15 * 60 + 25), "never an AMO after the session")
        assertFalse(ManualCoverRule.inHours(true, 23 * 60))
        assertFalse(ManualCoverRule.inHours(false, 12 * 60), "a holiday or a weekend")
    }

    @Test fun onItsExpiryDayAContractIsLeftToTheSquareOffFrom1500() {
        val sq = 15 * 60 + 5
        assertFalse(ManualCoverRule.leftToExpiry(true, 14 * 60 + 59, sq))
        assertTrue(ManualCoverRule.leftToExpiry(true, 15 * 60, sq))
        assertTrue(ManualCoverRule.leftToExpiry(true, 15 * 60 + 20, sq))
        assertFalse(ManualCoverRule.leftToExpiry(false, 15 * 60 + 20, sq), "the square-off switched off: Boss holds it, so it is covered")
    }

    // ---- the two looks ---------------------------------------------------------------------------------------------------------

    @Test fun oneLookIsNeverEnough() {
        val l = ManualCoverRule.Looks()
        assertFalse(l.see(key, 130, 1_000_000))
    }

    @Test fun twoLooksUnder20sAreNotEnoughAtOrOver20sTheyAre() {
        val l = ManualCoverRule.Looks()
        assertFalse(l.see(key, 130, 1_000_000))
        assertFalse(l.see(key, 130, 1_019_999))
        assertTrue(l.see(key, 130, 1_020_000))
        assertTrue(l.see(key, 130, 1_045_000), "still due on a later look")
    }

    @Test fun aQuantityChangedBetweenLooksStartsAgain() {
        val l = ManualCoverRule.Looks()
        assertFalse(l.see(key, 65, 1_000_000))
        assertFalse(l.see(key, 130, 1_025_000), "more bought: a new first look")
        assertFalse(l.see(key, 130, 1_040_000))
        assertTrue(l.see(key, 130, 1_045_000))
    }

    @Test fun aContractNotSeenUncoveredIsForgotten() {
        val l = ManualCoverRule.Looks()
        l.see(key, 130, 1_000_000); l.see("NFO:X", 75, 1_000_000)
        l.keepOnly(setOf("NFO:X"))
        assertEquals(1, l.size)
        assertFalse(l.see(key, 130, 1_030_000), "forgotten: its first look again")
        assertTrue(l.see("NFO:X", 75, 1_030_000))
        // A clock that went back (a test, a time change) is a first look, never due at once.
        assertFalse(l.see("NFO:X", 75, 900_000))
        l.clear(); assertEquals(0, l.size)
    }

    // ---- prices -------------------------------------------------------------------------------------------------------------------

    @Test fun theStopRoundsDownAndTheTargetUpToTheTick() {
        assertEquals(ManualCoverRule.Levels(119.05, 137.9), ManualCoverRule.levels(125.35, 0.05))   // 119.08 down, 137.89 up
        assertEquals(ManualCoverRule.Levels(95.0, 110.0), ManualCoverRule.levels(100.0, 0.05))       // exact: no tick added
        assertEquals(ManualCoverRule.Levels(123.5, 143.0), ManualCoverRule.levels(130.0, 0.05))
        assertEquals(ManualCoverRule.Levels(0.95, 1.1), ManualCoverRule.levels(1.0, 0.05))
        assertEquals(ManualCoverRule.Levels(9.5, 11.1), ManualCoverRule.levels(10.03, 0.1))          // another tick size
        assertNull(ManualCoverRule.levels(0.0, 0.05))
        assertNull(ManualCoverRule.levels(Double.NaN, 0.05))
        assertNull(ManualCoverRule.levels(100.0, 0.0))
        assertNull(ManualCoverRule.levels(0.05, 0.05), "a stop of 0.04 rounds to zero: nothing")
    }

    @Test fun thePlanFollowsTheLastPrice() {
        assertEquals(Plan.Both(119.05, 137.9), ManualCoverRule.plan(125.35, 120.0, 0.05))
        assertEquals(Plan.TooLow(119.05), ManualCoverRule.plan(125.35, 119.05, 0.05), "at the stop")
        assertEquals(Plan.TooLow(119.05), ManualCoverRule.plan(125.35, 90.0, 0.05), "under it")
        assertEquals(Plan.StopOnly(119.05, 137.9), ManualCoverRule.plan(125.35, 137.9, 0.05), "at the target")
        assertEquals(Plan.StopOnly(119.05, 137.9), ManualCoverRule.plan(125.35, 200.0, 0.05), "over it")
        assertEquals(Plan.Both(119.05, 137.9), ManualCoverRule.plan(125.35, 119.1, 0.05), "a tick over the stop")
        assertEquals(Plan.NoPrice, ManualCoverRule.plan(125.35, 0.0, 0.05), "no last price")
        assertEquals(Plan.NoPrice, ManualCoverRule.plan(0.0, 120.0, 0.05), "no average")
    }

    // ---- words ----------------------------------------------------------------------------------------------------------------------

    @Test fun theLineAndTheWords() {
        val d = LocalDate.of(2026, 11, 7)
        val t = ManualCoverRule.Tally(d, protectedN = 2, leftAlone = 1, failed = 0)
        assertEquals("Stop and target on my manual positions: on; today protected 2, left alone 1, failed 0", ManualCoverRule.line(true, true, t, d))
        assertEquals("Stop and target on my manual positions: off; today protected 0, left alone 0, failed 0",
            ManualCoverRule.line(false, true, t, d.plusDays(1)), "a new day starts at nothing")
        assertTrue(ManualCoverRule.line(true, false, t, d).contains("not read yet"))
        assertTrue(ManualCoverRule.saidProtected(sym, 130, 125.35, "Protected.").contains("you bought 130 yourself"))
        assertTrue(ManualCoverRule.saidTooLow(sym, 90.0, 119.05).contains("no stop was placed"))
        assertTrue(ManualCoverRule.saidStopOnly(sym, 200.0, 137.9).contains("only the stop"))
        assertTrue(ManualCoverRule.saidGaveUp(sym, 130, "RMS").contains("is NOT protected"))
    }
}
