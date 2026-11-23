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
        for (note in listOf("removed", "stop cancelled", "stop rejected", "covered by another exit",
                "GTT fired, part left", "stop not placed"))
            assertEquals(Skip.ENDED_TODAY, ManualCoverRule.skip(pos, Facts(endedToday = listOf(ManualCoverRule.Ended(sym, note)))), note)
        for (note in listOf("position closed", "GTT fired", "replaced", "stop filled", "target filled", "exit orders no longer working",
                ManualCoverRule.SLICE_DONE, ManualCoverRule.STOP_BY_EXCHANGE))
            assertNull(ManualCoverRule.skip(pos, Facts(endedToday = listOf(ManualCoverRule.Ended(sym, note)))), note)
        // One natural end and one removal the same day: Boss's removal wins.
        assertEquals(Skip.ENDED_TODAY, ManualCoverRule.skip(pos, Facts(endedToday = listOf(ManualCoverRule.Ended(sym, "position closed"),
            ManualCoverRule.Ended(sym, "removed")))))
    }

    // ---- 7 Nov, review: spreads, two products, carried positions ------------------------------------------------------------------

    @Test fun aLongOptionIsLeftAloneWhileTheAccountIsShortAnOptionOnTheSameUnderlying() {
        val book = listOf(pos, Pos("NIFTY26N1624000PE", "NFO", "NRML", -65, 80.0, 82.0), Pos("BANKNIFTY26NOV52000CE", "NFO", "MIS", 30, 200.0, 210.0))
        val shorts = ManualCoverRule.shortUnderlyings(book)
        assertEquals(setOf("NIFTY"), shorts, "any expiry, any right")
        assertEquals(Skip.SPREAD, ManualCoverRule.skip(pos, Facts(shortUnderlyings = shorts)))
        assertNull(ManualCoverRule.skip(book[2], Facts(shortUnderlyings = shorts)), "another underlying is covered")
        // 7 Nov, second review: a future (long or short) is not an option short, but it makes the option a HEDGE (below).
        val fut = listOf(Pos("NIFTY26NOVFUT", "NFO", "NRML", -75, 1.0, 1.0))
        assertEquals(emptySet(), ManualCoverRule.shortUnderlyings(fut))
        assertEquals(setOf("NIFTY"), ManualCoverRule.hedgeUnderlyings(fut, emptySet()))
        assertEquals(Skip.HEDGE, ManualCoverRule.skip(pos, Facts(hedgeUnderlyings = ManualCoverRule.hedgeUnderlyings(fut, emptySet()))))
        assertEquals("SENSEX", ManualCoverRule.underlying("SENSEX26N1382000CE"))
        assertEquals("BANKNIFTY", ManualCoverRule.underlying("BANKNIFTY26NOV52000PE"))
    }

    @Test fun aWorkingOptionSellOpensAShortOnlyOnAContractNotHeldToday() {
        val book = listOf(pos)
        val shortLeg = ManualCoverRule.Sell("NIFTY26N1625000PE", "NFO", 65)
        // The spread's short leg still filling after its wing was bought: a contract with no long today.
        assertEquals(setOf("NIFTY"), ManualCoverRule.shortUnderlyings(book, listOf(shortLeg), heldToday = setOf(key)))
        // 7 Nov, third review: never on a contract held or held today - (a) an OCO's other half for a moment after the stop filled,
        // (b) Boss's own leftover stop after his target filled, (c) a stop larger than what is left after a part sold by hand.
        val c = "NIFTY26N1625000PE"
        assertEquals(emptySet(), ManualCoverRule.shortUnderlyings(book, listOf(ManualCoverRule.Sell(c, "NFO", 130)), heldToday = setOf(key, "NFO:$c")))
        assertEquals(emptySet(), ManualCoverRule.shortUnderlyings(book, listOf(ManualCoverRule.Sell(sym, "NFO", 195)), heldToday = setOf(key)), "more than held")
        assertEquals(emptySet(), ManualCoverRule.shortUnderlyings(book, listOf(ManualCoverRule.Sell("NIFTY26NOVFUT", "NFO", 75))), "a future sell")
        assertEquals(emptySet(), ManualCoverRule.shortUnderlyings(book, listOf(shortLeg.copy(qty = 0))), "nothing left of it")
    }

    @Test fun theUnderlyingOfAnFnoSymbol() {
        assertEquals("NIFTY", ManualCoverRule.underlying("NIFTY26N0925150CE"))
        assertEquals("NIFTY", ManualCoverRule.underlying("NIFTY2611725000PE"), "a January weekly")
        assertEquals("BANKNIFTY", ManualCoverRule.underlying("BANKNIFTY26NOV52000PE"))
        assertEquals("NIFTY", ManualCoverRule.underlying("NIFTY26NOVFUT"))
        assertEquals("SENSEX", ManualCoverRule.underlying("SENSEX26N1382000CE"))
        assertEquals("NIFTYNXT50", ManualCoverRule.underlying("NIFTYNXT5026NOV70000CE"))
        assertEquals("3MINDIA", ManualCoverRule.underlying("3MINDIA26NOV30000CE"), "a name starting with a digit")
        assertEquals("360ONE", ManualCoverRule.underlying("360ONE26NOV1100PE"))
        assertEquals("M&M", ManualCoverRule.underlying("M&M26NOV3000CE"))
        assertEquals("BAJAJ-AUTO", ManualCoverRule.underlying("BAJAJ-AUTO26NOV9000CE"))
        assertEquals("X", ManualCoverRule.underlying("ODD26NOV1CE", mapOf("ODD26NOV1CE" to "X")), "the contract list's name first")
        assertEquals("RELIANCE", ManualCoverRule.underlying("RELIANCE"), "a cash symbol is itself")
        // Index ETFs are their own names: never the index's.
        assertEquals("NIFTYBEES", ManualCoverRule.underlying("NIFTYBEES"))
        val f = listOf(Pos("360ONE26NOVFUT", "NFO", "NRML", 500, 1.0, 1.0))
        assertEquals(Skip.HEDGE, ManualCoverRule.skip(Pos("360ONE26NOV1100PE", "NFO", "NRML", 500, 10.0, 11.0),
            Facts(hedgeUnderlyings = ManualCoverRule.hedgeUnderlyings(f, emptySet()))))
    }

    @Test fun aStockOptionIsAHedgeWhileItsStockOrFutureIsHeld() {
        val opt = Pos("RELIANCE26NOV1400CE", "NFO", "NRML", 500, 20.0, 21.0)
        assertEquals(Skip.HEDGE, ManualCoverRule.skip(opt, Facts(hedgeUnderlyings = ManualCoverRule.hedgeUnderlyings(emptyList(), setOf("RELIANCE")))), "a holding")
        val cash = listOf(Pos("RELIANCE", "NSE", "MIS", 100, 1_400.0, 1_401.0))
        assertEquals(setOf("RELIANCE"), ManualCoverRule.hedgeUnderlyings(cash, emptySet()), "an intraday cash position")
        assertEquals(setOf("RELIANCE"), ManualCoverRule.hedgeUnderlyings(listOf(Pos("RELIANCE26NOVFUT", "NFO", "NRML", 500, 1.0, 1.0)), emptySet()))
        assertNull(ManualCoverRule.skip(opt, Facts(hedgeUnderlyings = setOf("TCS"))))
        assertTrue("NIFTY" in ManualCoverRule.INDEX_UNDERLYINGS)
        assertEquals(Skip.SPREAD, ManualCoverRule.spreadOrHedge(sym, setOf("NIFTY"), setOf("NIFTY")))
        assertEquals(Skip.HEDGE, ManualCoverRule.spreadOrHedge(sym, emptySet(), setOf("NIFTY")))
        assertNull(ManualCoverRule.spreadOrHedge(sym, setOf("BANKNIFTY"), emptySet()))
        assertTrue(ManualCoverRule.saidUnhedged("NIFTY 24000 PE", Skip.SPREAD).contains("removed because it is now part of a spread/hedge"))
        assertTrue(ManualCoverRule.saidUntracked(sym, "250926000000123").contains("Check Kite's order book"))
    }

    @Test fun theCheckJustBeforePlacing() {
        fun pc(book: List<Pos> = listOf(pos), sells: List<ManualCoverRule.Sell> = emptyList(), equity: Set<String> = emptySet(),
               sendable: Int = 130) = ManualCoverRule.precheck(pos, book, sells, setOf(key), equity, sendable, 130)
        assertNull(pc(), "all clear: placed")
        assertEquals(Skip.SPREAD, pc(book = listOf(pos, Pos("NIFTY26N1625000PE", "NFO", "NRML", -65, 1.0, 1.0)))!!.first)
        assertEquals(Skip.SPREAD, pc(sells = listOf(ManualCoverRule.Sell("NIFTY26N1625000PE", "NFO", 65)))!!.first)
        assertNull(pc(sells = listOf(ManualCoverRule.Sell(sym, "NFO", 130))), "an exit of what is held")
        assertEquals(Skip.HEDGE, pc(book = listOf(pos, Pos("NIFTY26NOVFUT", "NFO", "NRML", 75, 1.0, 1.0)))!!.first)
        val less = pc(sendable = 65)!!
        assertNull(less.first); assertTrue(less.second.contains("65 of 130"))
        assertFalse(ManualCoverRule.definiteRefusal("Not protected: ${less.second}"))
    }

    @Test fun theLastCheckInsideTheLock() {
        fun pc(on: Boolean = true, free: Boolean = true, or: Boolean = true, w: Boolean = false, gr: Boolean = true, g: Boolean = false) =
            ManualCoverRule.precheckInLock(on, free, or, w, gr, g)
        assertNull(pc())
        for ((r, words) in listOf(pc(on = false) to "switched off", pc(free = false) to "busy", pc(or = false) to "orders not read",
                pc(gr = false) to "GTTs not read")) {
            assertNull(r!!.first); assertTrue(r.second.contains(words), r.second)
            assertFalse(ManualCoverRule.definiteRefusal("Not protected: ${r.second}"), "never a refusal: ${r.second}")
        }
        assertEquals(Skip.WORKING_ORDER, pc(w = true)!!.first)
        assertEquals(Skip.GTT, pc(g = true)!!.first)
        assertTrue(ManualCoverRule.UNHEDGED in ManualCoverRule.NATURAL_ENDS, "taking one off never blocks covering again")
    }

    @Test fun theOpenTicketsLegsAreLeftAlone() {
        assertTrue(ManualCoverRule.isTicketLeg("NIFTY", 24_000.0, "PE", "NIFTY", 24_000.0, 23_800.0), "the short leg")
        assertTrue(ManualCoverRule.isTicketLeg("NIFTY", 23_800.0, "PE", "NIFTY", 24_000.0, 23_800.0), "the wing")
        assertFalse(ManualCoverRule.isTicketLeg("NIFTY", 23_900.0, "PE", "NIFTY", 24_000.0, 23_800.0))
        assertFalse(ManualCoverRule.isTicketLeg("NIFTY", 23_800.0, "CE", "NIFTY", 24_000.0, 23_800.0))
        assertFalse(ManualCoverRule.isTicketLeg("BANKNIFTY", 23_800.0, "PE", "NIFTY", 24_000.0, 23_800.0))
        assertTrue(ManualCoverRule.isTicketLeg("NIFTY", null, null, "NIFTY", 24_000.0, null), "details unknown: the safe side")
        assertTrue(ManualCoverRule.isTicketLeg("NIFTY", null, "PE", "NIFTY", 24_000.0, null), "a PE whose strike is unknown")
        assertFalse(ManualCoverRule.isTicketLeg("NIFTY", null, "CE", "NIFTY", 24_000.0, null), "a CE is never a ticket leg")
        assertEquals(Skip.SPREAD, ManualCoverRule.skip(pos, Facts(ticketLegs = setOf(key))))
    }

    @Test fun oneContractHeldInTwoProductsIsLeftAlone() {
        assertEquals(Skip.TWO_PRODUCTS, ManualCoverRule.skip(pos, Facts(twoProducts = setOf(key))))
        assertEquals("NFO:$sym:NRML", pos.lookKey)
        assertEquals("NFO:$sym:MIS", pos.copy(product = "MIS").lookKey)
        assertEquals("$sym: no automatic stop and target, held as both MIS and NRML: set the stops yourself.",
            ManualCoverRule.saidLeftAlone(sym, Skip.TWO_PRODUCTS))
        assertTrue(ManualCoverRule.saidLeftAlone(sym, Skip.SPREAD).contains("left alone (part of a spread)"))
    }

    @Test fun aCarriedPositionsProtectionRemovedOnAnEarlierDayIsStillRespected() {
        val earlier = ManualCoverRule.Ended(sym, "removed", today = false, exchange = "NFO")
        assertEquals(Skip.ENDED_EARLIER, ManualCoverRule.skip(pos.copy(carried = true), Facts(endedToday = listOf(earlier))), "carried NRML")
        assertEquals(Skip.ENDED_TODAY, ManualCoverRule.skip(pos.copy(carried = true), Facts(endedToday = listOf(earlier, earlier.copy(today = true)))))
        assertTrue(Skip.ENDED_EARLIER.words.contains("removed earlier"))
        assertNull(ManualCoverRule.skip(pos.copy(carried = false), Facts(endedToday = listOf(earlier))), "bought today: only today's count")
        assertNull(ManualCoverRule.skip(pos.copy(product = "MIS"), Facts(endedToday = listOf(earlier))), "intraday: only today's")
        assertNull(ManualCoverRule.skip(pos.copy(carried = true), Facts(endedToday = listOf(earlier.copy(exchange = "BFO")))), "another exchange")
        assertNull(ManualCoverRule.skip(pos.copy(carried = true), Facts(endedToday = listOf(earlier.copy(note = "exit orders no longer working")))),
            "its DAY orders ended with the session: no choice of Boss's")
    }

    // ---- 7 Nov, review: refusals and attempts -------------------------------------------------------------------------------------

    @Test fun onlyDefiniteRefusalsCountTowardTheTries() {
        assertTrue(ManualCoverRule.definiteRefusal("Not protected: Quantity exceeds the freeze limit."))
        assertTrue(ManualCoverRule.definiteRefusal("Not protected: RMS:Margin Exceeds, Required:1,20,000.00"))
        for (r in listOf("Could not read NIFTY26N0925150CE's lot and tick from Zerodha.",
                "Not protected: Couldn't connect to the OMS. Please retry.",
                "Not protected: Gateway timed out.",
                "Not protected: The stop must be below the current price (118.00).",
                "Not protected: Trigger price for stoploss sell orders should be less than last price.",
                "Not protected: Order status unknown; no matching order found at Zerodha",
                "Not protected: the previous stop or target on X could not be confirmed cancelled at Zerodha; try again in a moment.",
                "Switch to Live with the badge at the top first."))
            assertFalse(ManualCoverRule.definiteRefusal(r), r)
    }

    @Test fun attemptsAreCappedAndSpacedAMinuteApart() {
        assertTrue(ManualCoverRule.mayAttempt(0, null, 1_000_000))
        assertFalse(ManualCoverRule.mayAttempt(1, 1_000_000, 1_059_999))
        assertTrue(ManualCoverRule.mayAttempt(1, 1_000_000, 1_060_000))
        assertFalse(ManualCoverRule.mayAttempt(ManualCoverRule.MAX_ATTEMPTS, 0L, 10_000_000))
        assertTrue(ManualCoverRule.mayAttempt(2, 2_000_000, 1_000_000), "a clock that went back never blocks for good")
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

    /** Boss, 23 Nov 2026: 10 premium points under and over the average by default (bought at 120: stop 110, target 130). */
    @Test fun theStopRoundsDownAndTheTargetUpToTheTick() {
        assertEquals(ManualCoverRule.Levels(110.0, 130.0), ManualCoverRule.levels(120.0, 0.05), "Boss's own example")
        assertEquals(ManualCoverRule.Levels(115.35, 135.35), ManualCoverRule.levels(125.35, 0.05))
        assertEquals(ManualCoverRule.Levels(90.0, 110.0), ManualCoverRule.levels(100.0, 0.05))       // exact: no tick added
        // An average off the tick (several fills): the stop down to the tick, the target up (never looser than the points).
        assertEquals(ManualCoverRule.Levels(110.35, 130.4), ManualCoverRule.levels(120.37, 0.05))
        assertEquals(ManualCoverRule.Levels(110.0, 130.1), ManualCoverRule.levels(120.03, 0.1))       // another tick size
        // Boss's own points (editable in the app).
        assertEquals(ManualCoverRule.Levels(105.0, 145.0), ManualCoverRule.levels(120.0, 0.05, 15.0, 25.0))
        assertEquals(ManualCoverRule.Levels(117.5, 121.0), ManualCoverRule.levels(120.0, 0.05, 2.5, 1.0))
        assertNull(ManualCoverRule.levels(0.0, 0.05))
        assertNull(ManualCoverRule.levels(Double.NaN, 0.05))
        assertNull(ManualCoverRule.levels(100.0, 0.0))
        assertNull(ManualCoverRule.levels(100.0, Double.NaN))
        assertNull(ManualCoverRule.levels(0.05, 0.05), "a 5% stop of 0.0475 rounds to zero: nothing")
    }

    /** 23 Nov 2026: an option too cheap for the points (the stop under two ticks) keeps the old 5% stop, and says it did. */
    @Test fun aCheapOptionFallsBackToTheFivePercentStop() {
        assertEquals(ManualCoverRule.Levels(0.95, 11.0, fallback = true), ManualCoverRule.levels(1.0, 0.05))
        assertEquals(ManualCoverRule.Levels(9.5, 20.05, fallback = true), ManualCoverRule.levels(10.05, 0.05), "one tick left: under two")
        assertEquals(ManualCoverRule.Levels(0.1, 20.1), ManualCoverRule.levels(10.1, 0.05), "two ticks left: the points stop")
        assertEquals(ManualCoverRule.Levels(9.5, 20.1, fallback = true), ManualCoverRule.levels(10.03, 0.1))
        assertEquals(ManualCoverRule.Levels(85.5, 100.0, fallback = true), ManualCoverRule.levels(90.0, 0.05, 90.0, 10.0), "points at the price")
        assertTrue(ManualCoverRule.ruleWords(10.0, 10.0, fallback = true).contains("5% under your average (the option is too cheap for a 10-point stop)"))
        assertTrue(ManualCoverRule.saidProtected("X", 65, 1.0, "Protected.", fallback = true).contains("too cheap for a 10-point stop"))
    }

    /** 23 Nov 2026: the points Boss may set: 1 to 500, to 0.05; anything else is brought inside (a bad saved value included). */
    @Test fun thePointsAreKeptInSaneBounds() {
        assertEquals(1.0, ManualCoverRule.clampPoints(0.0, 10.0))
        assertEquals(1.0, ManualCoverRule.clampPoints(-5.0, 10.0))
        assertEquals(500.0, ManualCoverRule.clampPoints(9_999.0, 10.0))
        assertEquals(10.0, ManualCoverRule.clampPoints(Double.NaN, 10.0))
        assertEquals(10.0, ManualCoverRule.clampPoints(Double.POSITIVE_INFINITY, 10.0))
        assertEquals(7.55, ManualCoverRule.clampPoints(7.53, 10.0), 1e-9)
        assertEquals(ManualCoverRule.levels(120.0, 0.05, 1.0, 500.0), ManualCoverRule.levels(120.0, 0.05, -3.0, 1e9), "levels clamp too")
        // Widening the stop is more points (a looser stop); fewer is narrowing.
        assertTrue(ManualCoverRule.stopWidens(10.0, 15.0))
        assertFalse(ManualCoverRule.stopWidens(10.0, 10.0))
        assertFalse(ManualCoverRule.stopWidens(10.0, 5.0))
        assertFalse(ManualCoverRule.stopWidens(500.0, 9_999.0), "past the bound it is the bound: no wider")
        assertEquals("10", ManualCoverRule.points(10.0)); assertEquals("7.5", ManualCoverRule.points(7.5)); assertEquals("2.25", ManualCoverRule.points(2.25))
    }

    @Test fun thePlanFollowsTheLastPrice() {
        assertEquals(Plan.Both(115.35, 135.35), ManualCoverRule.plan(125.35, 125.0, 0.05))
        assertEquals(Plan.TooLow(115.35), ManualCoverRule.plan(125.35, 115.35, 0.05), "at the stop")
        assertEquals(Plan.TooLow(115.35), ManualCoverRule.plan(125.35, 90.0, 0.05), "under it")
        assertEquals(Plan.StopOnly(115.35, 135.35), ManualCoverRule.plan(125.35, 135.35, 0.05), "at the target")
        assertEquals(Plan.StopOnly(115.35, 135.35), ManualCoverRule.plan(125.35, 200.0, 0.05), "over it")
        // 7 Nov, review: the last price needs max(3 ticks, 1% of the average) = 1.2535 over the stop, so 116.6035 at least.
        assertEquals(Plan.TooLow(115.35), ManualCoverRule.plan(125.35, 115.4, 0.05), "a tick over the stop is too close")
        assertEquals(Plan.TooLow(115.35), ManualCoverRule.plan(125.35, 116.6, 0.05), "just short of the room")
        assertEquals(Plan.Both(115.35, 135.35), ManualCoverRule.plan(125.35, 116.65, 0.05), "just enough room")
        // Boss's own points.
        assertEquals(Plan.Both(105.0, 145.0), ManualCoverRule.plan(120.0, 121.0, 0.05, 15.0, 25.0))
        // A cheap option (the 5% stop): three ticks is the larger room (1% of 5.00 is 0.05).
        assertEquals(0.15, ManualCoverRule.room(5.0, 0.05), 1e-9)
        assertEquals(Plan.TooLow(4.75), ManualCoverRule.plan(5.0, 4.85, 0.05))
        assertEquals(Plan.Both(4.75, 15.0, fallback = true), ManualCoverRule.plan(5.0, 4.9, 0.05))
        assertEquals(Plan.StopOnly(4.75, 15.0, fallback = true), ManualCoverRule.plan(5.0, 16.0, 0.05))
        assertEquals(Plan.NoPrice, ManualCoverRule.plan(125.35, 0.0, 0.05), "no last price")
        assertEquals(Plan.NoPrice, ManualCoverRule.plan(0.0, 120.0, 0.05), "no average")
    }

    // ---- 23 Nov 2026: a manual buy looked at as soon as Zerodha reports it filled ------------------------------------------------

    @Test fun onlyAnUntaggedOptionBuyThatIsDoneFillingTriggersAtOnce() {
        fun t(side: String = "BUY", ex: String = "NFO", product: String = "MIS", sym: String = this.sym, status: String = "COMPLETE",
              filled: Int = 65, tag: String = "") = ManualCoverRule.fillTriggers(side, ex, product, sym, status, filled, tag)
        assertTrue(t(), "a Kite buy filled in full")
        assertTrue(t(product = "NRML")); assertTrue(t(ex = "BFO", sym = "SENSEX26N1382000PE"))
        assertTrue(t(status = "CANCELLED", filled = 30), "part filled, the rest cancelled: what is held is covered")
        assertFalse(t(status = "CANCELLED", filled = 0), "cancelled with nothing filled")
        assertFalse(t(status = "OPEN", filled = 30), "a part fill still working waits for its COMPLETE")
        assertFalse(t(status = "REJECTED", filled = 0))
        assertFalse(t(status = "UPDATE", filled = 65))
        assertFalse(t(side = "SELL"), "a sale is never covered")
        assertFalse(t(sym = "NIFTY26NOVFUT"), "a future"); assertFalse(t(ex = "NSE", sym = "RELIANCE", product = "CNC"), "stock")
        assertFalse(t(ex = "MCX", sym = "CRUDEOIL26NOV5000CE"), "MCX")
        assertFalse(t(product = "CNC"))
        assertFalse(t(tag = "iraalgo"), "the app's own order screen: left to the two looks (its bracket is never raced)")
        assertFalse(t(tag = "iraarm")); assertFalse(t(tag = "iraorb")); assertFalse(t(tag = "irapine"))
        assertFalse(t(side = "", ex = "", product = ""), "an update without them: the two looks")
        assertTrue(t(side = "buy"), "the side read either case")
        // 23 Nov 2026 (B6-I round 2): another app's order (a third-party tag) is Boss's own too.
        assertTrue(t(tag = "sensibull"), "another app's tag: Boss's"); assertTrue(t(tag = "Streak"))
        assertFalse(t(tag = "iraprotect")); assertFalse(t(tag = "iraalgostrat")); assertFalse(t(tag = "iraalgoexpiry")); assertFalse(t(tag = "iraalgoflat"))
    }

    /** 23 Nov 2026 (B6-I round 2): the app's own tags, and Boss's - none, the app's order screen's, another app's. */
    @Test fun theAppsOwnTagsAndBosss() {
        for (t in ManualCoverRule.APP_TAGS) { assertTrue(ManualCoverRule.appTag(t), t); assertFalse(ManualCoverRule.bossTag(t), t) }
        assertTrue(ManualCoverRule.appTag("IRAORB"), "either case")
        assertTrue(ManualCoverRule.appTag("iranew"), "any tag of the app's own family, a later one too")
        assertTrue(ManualCoverRule.bossTag(""), "Kite's own app: no tag")
        assertTrue(ManualCoverRule.bossTag("iraalgo"), "the app's own order screen: Boss's hand order")
        assertTrue(ManualCoverRule.bossTag("sensibull")); assertTrue(ManualCoverRule.bossTag("gtt")); assertTrue(ManualCoverRule.bossTag("streak"))
    }

    /** 23 Nov 2026 (B6-I round 2): the room over a points stop is at most half its distance - at an 800 average a 10-point stop's 5, not 8. */
    @Test fun theRoomOverAPointsStopIsAtMostHalfItsDistance() {
        assertEquals(5.0, ManualCoverRule.room(800.0, 0.05, 790.0), 1e-9)
        assertEquals(8.0, ManualCoverRule.room(800.0, 0.05), 1e-9, "no stop given: 1% as before")
        assertEquals(Plan.Both(790.0, 810.0), ManualCoverRule.plan(800.0, 796.0, 0.05), "a normal dip still covered")
        assertEquals(Plan.Both(790.0, 810.0), ManualCoverRule.plan(800.0, 795.0, 0.05), "exactly the room")
        assertEquals(Plan.TooLow(790.0), ManualCoverRule.plan(800.0, 794.95, 0.05), "under half the stop's distance over it")
        assertEquals(0.15, ManualCoverRule.room(5.0, 0.05, 4.75), 1e-9, "three ticks at the least")
        assertEquals(1.2535, ManualCoverRule.room(125.35, 0.05, 115.35), 1e-9, "1% where it is the smaller")
    }

    @Test fun aReportedFillIsConfirmedByAtLeastThatMuchHeld() {
        assertTrue(ManualCoverRule.fillHeld(65, 65))
        assertTrue(ManualCoverRule.fillHeld(130, 65), "more held (an earlier buy too): all of it is covered")
        assertFalse(ManualCoverRule.fillHeld(0, 65), "Zerodha's positions trail the fill: wait")
        assertFalse(ManualCoverRule.fillHeld(30, 65))
        assertFalse(ManualCoverRule.fillHeld(65, 0))
        assertEquals(ManualCoverRule.FILL_RETRY_MS, ManualCoverRule.followUpMs(ManualCoverRule.FollowUp.FILL_LAG))
        assertTrue(ManualCoverRule.FILL_RETRY_MS * ManualCoverRule.FILL_TRIES < ManualCoverRule.SETTLE_MS, "quicker than the two looks it replaces")
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
        assertTrue(ManualCoverRule.saidTooLow(sym, 90.0, 115.35).contains("no stop was placed"))
        assertTrue(ManualCoverRule.saidStopOnly(sym, 200.0, 135.35).contains("only the stop"))
        // 23 Nov 2026: the points rule in words, Boss's own points included.
        assertTrue(ManualCoverRule.saidProtected(sym, 130, 125.35, "Protected.").contains("a stop 10 points under your average and a target sell 10 points over it"))
        assertTrue(ManualCoverRule.saidProtected(sym, 130, 125.35, "Protected.", 7.5, 20.0).contains("a stop 7.5 points under your average and a target sell 20 points over it"))
        // 23 Nov 2026 (B6-I round 2): how to exit or set his own stop while the app's SL rests.
        assertTrue(ManualCoverRule.saidProtected(sym, 130, 125.35, "Protected.").endsWith(ManualCoverRule.OWN_EXIT_HELP))
        assertTrue(ManualCoverRule.saidGaveUp(sym, 130, "RMS").contains("is NOT protected"))
        assertTrue(ManualCoverRule.saidGaveUp(sym, 130, "timed out", ManualCoverRule.MAX_ATTEMPTS).contains("tried 10 times"))
    }

    // ---- 15 Nov: the follow-up look, the exchange's refusals, slices -----------------------------------------------------------

    /** A stop the exchange refused or cancelled is covered again; one cancelled with no reason (Boss, in Kite) is respected. */
    @Test fun aStopEndedByTheExchangeIsCoveredAgainButBosssCancelIsRespected() {
        assertNull(ManualCoverRule.skip(pos, Facts(endedToday = listOf(ManualCoverRule.Ended(sym, ManualCoverRule.STOP_BY_EXCHANGE)))))
        assertEquals(Skip.ENDED_TODAY, ManualCoverRule.skip(pos, Facts(endedToday = listOf(ManualCoverRule.Ended(sym, "stop cancelled")))))
        // A carried position: the exchange's end on an earlier day never blocks either.
        assertNull(ManualCoverRule.skip(pos.copy(carried = true), Facts(endedToday = listOf(ManualCoverRule.Ended(sym, ManualCoverRule.STOP_BY_EXCHANGE, today = false)))))
        assertTrue(ManualCoverRule.SLICE_DONE in ManualCoverRule.NATURAL_ENDS && ManualCoverRule.STOP_BY_EXCHANGE in ManualCoverRule.NATURAL_ENDS)
    }

    @Test fun theFollowUpLookComesJustAfterTheSettleOrSoonAfterAFailedRead() {
        assertNull(ManualCoverRule.followUpMs(ManualCoverRule.FollowUp.NONE))
        assertEquals(21_000L, ManualCoverRule.followUpMs(ManualCoverRule.FollowUp.SIGHTED))
        assertTrue(ManualCoverRule.followUpMs(ManualCoverRule.FollowUp.SIGHTED)!! > ManualCoverRule.SETTLE_MS, "due when it comes")
        assertEquals(10_000L, ManualCoverRule.followUpMs(ManualCoverRule.FollowUp.READ_FAILED))
        // The second look 21 s after the first is due (the settle rule is unchanged: one sighting is never enough).
        val l = ManualCoverRule.Looks()
        assertFalse(l.see("k", 130, 0L))
        assertEquals(setOf("k"), l.keys)
        assertTrue(l.see("k", 130, ManualCoverRule.FOLLOW_UP_MS))
        l.clear()
        assertTrue(l.keys.isEmpty())
    }

    /**
     * 15 Nov, review (R5): only REJECTED, or a cancel whose words clearly name the exchange or the broker's risk system, is the
     * exchange's; any other cancel - blank (as every cancel in Boss's diagnostics was) or unknown wording - is Boss's.
     */
    @Test fun onlyTheExchangesRefusalOrClearlyWordedCancelIsTheExchanges() {
        assertTrue(ManualCoverRule.endedByExchange("REJECTED", ""))
        assertTrue(ManualCoverRule.endedByExchange("REJECTED", "Insufficient funds. Margin required: 57675.75."))
        assertTrue(ManualCoverRule.endedByExchange("CANCELLED", "Cancelled by exchange"))
        assertTrue(ManualCoverRule.endedByExchange("CANCELLED", "Order cancelled by RMS"))
        assertTrue(ManualCoverRule.endedByExchange("CANCELLED", "Trigger price is outside the CIRCUIT limit"))
        assertTrue(ManualCoverRule.endedByExchange("CANCELLED", "Quantity exceeds the freeze limit"))
        assertTrue(ManualCoverRule.endedByExchange("CANCELLED", "Order validity expired"))
        assertTrue(ManualCoverRule.endedByExchange("CANCELLED", "Margin shortfall"))
        // 19 Nov 2026 (audit batch 2, final review): Zerodha's market-protection cancel is Zerodha's, never Boss's.
        assertTrue(ManualCoverRule.endedByExchange("CANCELLED", "Your order has been cancelled because of market protection."))
        assertFalse(ManualCoverRule.endedByExchange("CANCELLED", ""), "blank: Boss's (every cancel in the diagnostics)")
        assertFalse(ManualCoverRule.endedByExchange("CANCELLED", "   "))
        assertFalse(ManualCoverRule.endedByExchange("CANCELLED", "Cancelled by user"))
        assertFalse(ManualCoverRule.endedByExchange("CANCELLED", "Your order has been cancelled."), "unknown words: Boss's")
        assertFalse(ManualCoverRule.endedByExchange("CANCELLED", "Cancelled by system"), "unknown words: Boss's")
        assertFalse(ManualCoverRule.endedByExchange("COMPLETE", "exchange"))
        assertFalse(ManualCoverRule.endedByExchange("OPEN", ""))
    }

    /** 15 Nov, review: "pending" only for sightings of the last two minutes. */
    @Test fun recentSightingsOnlyArePending() {
        val l = ManualCoverRule.Looks()
        l.see("old", 130, 0L)
        l.see("new", 65, 100_000L)
        assertEquals(setOf("old", "new"), l.keysSince(0L))
        assertEquals(setOf("new"), l.keysSince(150_000L - ManualCoverRule.PENDING_MS))
        // A new quantity is a new sighting.
        l.see("old", 195, 200_000L)
        assertEquals(setOf("old"), l.keysSince(100_001L))
    }
}
