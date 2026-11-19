package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 19 Nov 2026 (audit batch 2: the independent safety net): Zerodha's positions against every book, report only. */
class ReconcileTest {
    private val ce = "NIFTY26NOV24000CE"
    private val pe = "NIFTY26NOV24000PE"

    @Test fun aPositionNoBookOwnsIsFoundAndAnOwnedOneIsNot() {
        val f = Reconcile.find(mapOf(ce to 75, pe to -75, "CRUDEOIL26DECFUT" to 0), owned = setOf(pe), orders = emptyList(), gtts = emptyList())
        assertEquals(listOf(Reconcile.Finding(ce, 75, false)), f)
        assertTrue(Reconcile.find(emptyMap(), emptySet(), null, null).isEmpty(), "flat: nothing")
        assertTrue(Reconcile.find(mapOf(ce to 75), setOf(ce), null, null).isEmpty(), "owned: nothing")
        assertTrue(Reconcile.find(mapOf("" to 75), emptySet(), null, null).isEmpty(), "a blank symbol is never said")
    }

    @Test fun aRestingStopIsAWorkingStopOrderOrAnActiveGttOnTheClosingSide() {
        val sl = Reconcile.Order(ce, "SELL", "SL-M", working = true)
        assertEquals(true, Reconcile.hasStop(ce, 75, listOf(sl), emptyList()))
        assertEquals(false, Reconcile.hasStop(ce, -75, listOf(sl), emptyList()), "a short is closed by a BUY")
        assertEquals(false, Reconcile.hasStop(ce, 75, listOf(sl.copy(working = false)), emptyList()), "a cancelled stop is none")
        assertEquals(false, Reconcile.hasStop(ce, 75, listOf(sl.copy(type = "LIMIT")), emptyList()), "a limit sale is a target, not a stop")
        assertEquals(false, Reconcile.hasStop(ce, 75, listOf(sl.copy(symbol = pe)), emptyList()), "another contract's stop")
        assertEquals(true, Reconcile.hasStop(ce, 75, emptyList(), listOf(Reconcile.Gtt(ce, true, listOf("SELL")))))
        assertEquals(true, Reconcile.hasStop(ce, 75, emptyList(), listOf(Reconcile.Gtt(ce, true, listOf("SELL", "SELL")))), "an OCO")
        assertEquals(false, Reconcile.hasStop(ce, 75, emptyList(), listOf(Reconcile.Gtt(ce, false, listOf("SELL")))), "a triggered GTT")
        assertNull(Reconcile.hasStop(ce, 75, null, null), "neither book read: unknown, never 'no stop'")
        assertNull(Reconcile.hasStop(ce, 75, emptyList(), null), "the GTTs not read: no stop order is not proof")
        assertEquals(true, Reconcile.hasStop(ce, 75, listOf(sl), null), "a stop found in the one book read is enough")
    }

    @Test fun saidOnlyOnceTwoReadsAgreeAndItLastedAMinuteThenEveryFiveMinutes() {
        val t = Reconcile.Tracker()
        val f = listOf(Reconcile.Finding(ce, 75, false))
        assertTrue(t.step(0L, f, true).isEmpty(), "one read is not enough")
        assertEquals(f, t.step(60_000L, f, true), "two reads a minute apart agree")
        assertTrue(t.step(120_000L, f, true).isEmpty(), "not again within 5 minutes")
        assertTrue(t.step(300_000L, f, true).isEmpty())
        assertEquals(f, t.step(360_000L, f, true), "again 5 minutes later while it lasts")
        assertTrue(t.step(420_000L, emptyList(), false).isEmpty(), "gone: cleared")
        assertTrue(t.step(480_000L, f, true).isEmpty(), "back: a fresh count, one read")
        assertEquals(f, t.step(540_000L, f, true))
    }

    @Test fun oneWithAnExitStopRestingIsToldOnceWhileItLasts() {
        val t = Reconcile.Tracker()
        val f = listOf(Reconcile.Finding(ce, 75, true))
        t.step(0L, f, true)
        assertEquals(f, t.step(60_000L, f, true))
        assertTrue(t.step(360_000L, f, true).isEmpty(), "a stop rests: not every 5 minutes")
        assertTrue(t.step(3_600_000L, f, true).isEmpty())
        val unprotected = listOf(Reconcile.Finding(ce, 75, false))
        assertEquals(unprotected, t.step(3_660_000L, unprotected, true), "its stop gone: told again at once")
    }

    /** 19 Nov 2026 (second review): Boss's own manual position as the app knows it is told once, not every 5 minutes. */
    @Test fun bossesKnownManualPositionIsToldOnceWhileItLasts() {
        val found = Reconcile.find(mapOf(ce to 75, pe to -75), emptySet(), emptyList(), emptyList(), known = setOf(ce))
        assertEquals(listOf(true, false), found.map { it.known })
        val t = Reconcile.Tracker()
        t.step(0L, found, true)
        assertEquals(found, t.step(60_000L, found, true), "both told once the two reads agree")
        assertEquals(listOf(pe), t.step(360_000L, found, true).map { it.symbol }, "only the unknown one again after 5 minutes")
        assertTrue(t.step(3_600_000L, found, true).none { it.symbol == ce })
        assertTrue(Reconcile.say(found[0]).contains("your own position"), Reconcile.say(found[0]))
    }

    /** 20 Nov 2026 (delta review): Paper mode's unowned positions repeat unless a stop rests, and are said as "app in Paper". */
    @Test fun paperModesUnownedPositionsRepeatUnlessAStopRests() {
        val noStop = Reconcile.find(mapOf(ce to 75), emptySet(), emptyList(), emptyList(), paper = setOf(ce))
        assertTrue(noStop.single().paper && !noStop.single().known)
        val s = Reconcile.say(noStop.single())
        assertTrue(s.contains("a Zerodha position no book follows (app in Paper)") && !s.contains("your own position"), s)
        val t = Reconcile.Tracker()
        t.step(0L, noStop, true)
        assertEquals(noStop, t.step(60_000L, noStop, true))
        assertEquals(noStop, t.step(360_000L, noStop, true), "no stop: every 5 minutes")
        val stop = Reconcile.find(mapOf(ce to 75), emptySet(), listOf(Reconcile.Order(ce, "SELL", "SL-M", true)), emptyList(), paper = setOf(ce))
        val t2 = Reconcile.Tracker()
        t2.step(0L, stop, true)
        assertEquals(stop, t2.step(60_000L, stop, true))
        assertTrue(t2.step(360_000L, stop, true).isEmpty(), "a stop rests: told once")
        // Known to the manual-positions switch wins over Paper's wording.
        assertFalse(Reconcile.find(mapOf(ce to 75), emptySet(), null, null, known = setOf(ce), paper = setOf(ce)).single().paper)
    }

    /** 19 Nov 2026 (second review): Zerodha holding more than the books own is a duplicate or oversized fill; less never is. */
    @Test fun moreAtZerodhaThanTheBooksOwnIsFlaggedLessNever() {
        val owned = setOf(ce, pe, "X")
        val qty = Reconcile.ownedQty(listOf(Reconcile.Claim(ce, 75), Reconcile.Claim(pe, -75), Reconcile.Claim("X", null)))
        val f = Reconcile.find(mapOf(ce to 150, pe to -30, "X" to 999), owned, emptyList(), emptyList(), qty)
        assertEquals(listOf(Reconcile.Finding(ce, 150, null, Reconcile.Kind.OVERSIZE, 75)), f, "a partial exit (pe) and a book with no quantity (X) are not")
        assertTrue(Reconcile.say(f[0]).contains("more than the 75") && !Reconcile.say(f[0]).contains("stop"), Reconcile.say(f[0]))
        assertTrue(Reconcile.unownedOrOversized(mapOf(ce to 150), owned, qty))
        assertFalse(Reconcile.unownedOrOversized(mapOf(ce to 75, pe to -30), owned, qty))
        // The other side from the books (an exit sent twice): its own kind, whatever the size.
        val wrong = Reconcile.find(mapOf(ce to -75), owned, null, null, qty).single()
        assertEquals(Reconcile.Kind.WRONG_SIDE, wrong.kind)
        assertTrue(Reconcile.say(wrong).contains("the other side"), Reconcile.say(wrong))
        assertTrue(Reconcile.unownedOrOversized(mapOf(ce to -75), owned, qty))
        assertEquals("RISK: Zerodha holds more than, or other than, the app's books", Reconcile.title(listOf(wrong)))
        assertEquals("RISK: a Zerodha position no book follows", Reconcile.title(listOf(Reconcile.Finding(pe, 1, false))))
        assertEquals("RISK: Zerodha's positions differ from the app's books", Reconcile.title(listOf(wrong, Reconcile.Finding(pe, 1, false))))
        // The same two-read, one-minute and 5-minute rules, counted apart from an unowned finding on the same symbol.
        val t = Reconcile.Tracker()
        assertTrue(t.step(0L, f, true).isEmpty())
        assertEquals(f, t.step(60_000L, f, true))
        assertTrue(t.step(120_000L, f, true).isEmpty())
        assertEquals(f, t.step(360_000L, f, true))
        // Even with a resting stop (the arm's own, on the arm's units): never told just once.
        val withStop = listOf(f.single().copy(stop = true))
        assertEquals(withStop, t.step(720_000L, withStop, true))
    }

    @Test fun theOwnedQuantitySumsHoldersAndFallsBackForProtectionsAndUnknowns() {
        val q = Reconcile.ownedQty(listOf(
            Reconcile.Claim(ce, 75), Reconcile.Claim(ce, -50), Reconcile.Claim(ce, 75, holds = false),
            Reconcile.Claim(pe, 30, holds = false), Reconcile.Claim(pe, 60, holds = false),
            Reconcile.Claim("X", 10), Reconcile.Claim("X", null)))
        assertEquals(25, q[ce], "the holders' signed units summed (a protection on it is an overlay)")
        assertNull(q[pe], "protections only: Boss may add to his own position, so symbol only")
        assertNull(q["X"], "a claim with no quantity: symbol only")
        assertTrue("Y" !in q)
    }

    @Test fun twoReadsCloseTogetherWaitForTheMinute() {
        val t = Reconcile.Tracker()
        val f = listOf(Reconcile.Finding(ce, 75, null))
        assertTrue(t.step(0L, f, true).isEmpty())
        assertTrue(t.step(20_000L, f, true).isEmpty(), "agreed, but only 20 s old")
        assertEquals(f, t.step(56_000L, f, true), "a lane minute a little early still counts")
    }

    @Test fun aFailedReadNeverAlertsClearsOrReadsFlat() {
        val t = Reconcile.Tracker()
        val f = listOf(Reconcile.Finding(ce, 75, false))
        t.step(0L, f, true)
        t.failed()
        assertTrue(t.heldHint(30_000L), "a failed read keeps 'Zerodha holds something'")
        assertEquals(f, t.step(60_000L, f, true), "the read before the failure still agrees")
        assertEquals(setOf(ce), t.open())
    }

    @Test fun theSideMustAgreeBetweenTheTwoReads() {
        val t = Reconcile.Tracker()
        t.step(0L, listOf(Reconcile.Finding(ce, 75, false)), true)
        assertTrue(t.step(60_000L, listOf(Reconcile.Finding(ce, -75, false)), true).isEmpty(), "long then short: not agreed yet")
        assertEquals(1, t.step(120_000L, listOf(Reconcile.Finding(ce, -50, false)), true).size, "short twice: agreed")
    }

    @Test fun zerodhaHeldTurnsFlatOnlyAfterTwoFlatReadsAndLapses() {
        val t = Reconcile.Tracker()
        assertFalse(t.heldHint(0L), "never read: not held by this hint (the books' own hints still count)")
        t.step(0L, emptyList(), true)
        assertTrue(t.heldHint(1L))
        t.step(60_000L, emptyList(), false)
        assertTrue(t.heldHint(60_001L), "one flat read is not flat")
        t.step(120_000L, emptyList(), false)
        assertFalse(t.heldHint(120_001L), "two flat reads: flat")
        t.step(180_000L, emptyList(), true)
        assertTrue(t.heldHint(180_000L + Reconcile.HINT_MS - 1))
        assertFalse(t.heldHint(180_000L + Reconcile.HINT_MS), "no read for 10 minutes: it lapses")
        assertFalse(t.heldHint(179_000L), "a clock set back: not trusted")
    }

    @Test fun dueEveryMinuteWhileLoggedInInEitherMarketsHours() {
        assertTrue(Reconcile.due(true, true, false, 0L, 1_000L))
        assertTrue(Reconcile.due(true, false, true, 0L, 1_000L), "MCX's evening")
        assertFalse(Reconcile.due(true, false, false, 0L, 1_000L), "no market open")
        assertFalse(Reconcile.due(false, true, true, 0L, 1_000L), "not logged in")
        assertFalse(Reconcile.due(true, true, false, 10_000L, 40_000L))
        assertTrue(Reconcile.due(true, true, false, 10_000L, 65_000L))
        assertTrue(Reconcile.due(true, true, false, 100_000L, 50_000L), "a clock set back: due")
    }

    @Test fun theWordsNameTheContractAndPromiseNothingWasPlaced() {
        val s = Reconcile.notice(listOf(Reconcile.Finding(ce, 75, false), Reconcile.Finding(pe, -75, true), Reconcile.Finding("X", 1, null)))
        assertTrue(s.contains("Zerodha holds 75 $ce") && s.contains("no exit stop rests"), s)
        assertTrue(s.contains("an exit stop rests at Zerodha"), s)
        assertTrue(s.contains("could not be read"), s)
        assertTrue(s.contains("placed, changed or cancelled nothing"), s)
        assertFalse(s.contains("₹"), "never rupees")
    }
}
