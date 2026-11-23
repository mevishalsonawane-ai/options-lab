package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.MarketClockRule
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Alerts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 22 Nov 2026 (B6 review of the exits, the protections - [Protections]), against the fake Kite:
 *  - M5: Zerodha takes no second full sale beside a resting stop (a new short, refused for funds), so an intraday target was never placed,
 *    saved as none, and nothing watched it. Now an intraday stop and target rest only the stop at Zerodha; the target is the app's own
 *    (saved, shown): reached, the app takes the stop out first (marked its own), confirms it out (a part filled: only the rest is sold),
 *    sells, and puts the stop back if the sale is refused; a stream tick only marks it. A carried target refused the same way is the app's
 *    too; any other refusal is said, never dropped unsaid (the re-placing at the open included).
 *  - M6: the marks of the stops the app cancels itself (a square-off, Boss's exit from the app, the target's sale) are kept on disk, so a
 *    restart never reads such a cancel as Boss's; they expire, and one whose cancel never took ends.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProtectionsB6Test : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500CE"

    @Before fun up() {
        kite = FakeKite()
        kite.instruments += FakeKite.Ins(12_345_678L, sym, "NIFTY", Market.today().plusDays(20), 24_500.0, "CE", 75)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        kite.position(sym, 75, 100.0, product = "MIS")
        kite.login()
        Alerts.forgetPosted()
        com.optionslab.app.testing.Background.clearAlerts()
        DayOco.resetForTest()
        com.optionslab.app.testing.AutomationSupport.liveSettings()
        runBlocking { Broker.instruments() }
    }

    @After fun down() {
        Protections.resetFollowUpForTest()
        KiteStream.resetForTest()
        SquareOffExit.resetForTest(gap = 0L)
        Broker.lostSearchMsForTest = null; Broker.lostSearchInlineForTest = false; Broker.lostLookGapMsForTest = null
        Market.testClock = null; kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(h: Int, m: Int) { Market.testClock = MarketClockRule.clockAt(h, m) }
    private fun tick() = runBlocking { Protections.tick() }
    private fun active() = runBlocking { Protections.active() }
    private fun protect(product: String = "MIS", stop: Double? = 90.0, target: Double? = 130.0, qty: Int = 75): String = runBlocking {
        Protections.protectLive(sym, "NFO", product, qty, 100.0, stop, null, target, recheck = true)
    }
    private fun held(product: String = "MIS") = kite.positions["NFO:$sym:$product"]?.first ?: 0
    private fun sales() = kite.placed.filter { it.form["order_type"] == "MARKET" && it.form["transaction_type"] == "SELL" }

    // ---- M5: the intraday target is the app's own -------------------------------------------------------------------------------

    @Test fun anIntradayStopAndTargetRestOnlyTheStopAndTheTargetIsKeptAndShown() {
        val msg = protect()
        assertTrue(msg, msg.startsWith("Protected") && msg.contains(Protections.TARGET_BY_APP))
        assertEquals("only the stop at Zerodha", listOf("SL"), kite.placed.map { it.form["order_type"] })
        val p = active().single()
        assertEquals("the target kept", 130.0, p.target!!, 1e-9)
        assertTrue(p.appTarget); assertNull(p.targetOrderId)
        assertEquals("stop 90.00 · target 130.00 (watched by the app)", p.describe())
        // Saved: a restart keeps it.
        Protections.restartForTest()
        val again = active().single()
        assertTrue(again.appTarget); assertEquals(130.0, again.target!!, 1e-9)
        // A target alone (no stop beside it) still rests at Zerodha as before.
        runBlocking { Protections.remove(again.id) }
        kite.requests.clear()
        protect(stop = null)
        assertEquals(listOf("LIMIT"), kite.placed.map { it.form["order_type"] })
        assertFalse(active().single().appTarget)
    }

    /** Reached: the stop is marked the app's own and taken out first, confirmed, then the sale goes - one exit; then it ends. */
    @Test fun theTargetReachedTakesTheStopOutThenSells() {
        SquareOffExit.resetForTest(gap = SquareOffExit.GAP_MS)               // (the app's gap: the sale counted through it for the square-offs)
        protect()
        val p = active().single()
        at(12, 5)
        tick()
        assertTrue("below the target: nothing", sales().isEmpty())
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.requests.clear()
        tick()
        val reqs = kite.requests.toList()
        val cancel = reqs.indexOfFirst { it.method == "DELETE" && it.path.endsWith(p.stopOrderId!!) }
        val sale = reqs.indexOfFirst { it.method == "POST" && it.path == "/orders/regular" && it.form["order_type"] == "MARKET" }
        assertTrue("the stop out before the sale: $reqs", cancel in 0 until sale)
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals(listOf("75"), sales().map { it.form["quantity"] })
        assertEquals("iraprotect", sales().single().form["tag"])
        assertEquals("flat, never short", 0, held())
        assertTrue("its cancel marked the app's own", Protections.takenOverForTest(p.stopOrderId!!))
        assertEquals("the square-offs count it as going for their gap", 75, SquareOffExit.lostPending(sym, "MIS"))
        // (Fourth round, m3: never for Boss's own exit - Zerodha took it, so its fill is in his lag guard.)
        assertEquals(0, SquareOffExit.pendingForBoss(sym, "MIS")["protections"])
        Protections.ageTargetSalesForTest(SquareOffExit.GAP_MS)
        assertEquals(0, Protections.targetSalesGoing(sym, "MIS"))
        com.optionslab.app.testing.SecondRead.clockOn(); tick()
        assertTrue("ended", active().isEmpty())
        assertEquals("never a stop put back on a flat position", 0, kite.placed.count { it.form["order_type"] == "SL" })
        assertTrue(Alerts.posted.any { it.text.contains("its target 130.00 was reached") })
    }

    /** One seller at a time on a row: while a square-off holds the row's claim, the target takes nothing out; then it sells. */
    @Test fun theTargetWaitsWhileASquareOffHoldsTheRowsClaim() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", "iraalgoflat")!!
        try {
            tick()
            assertTrue("its stop left resting", kite.order(p.stopOrderId!!).working)
            assertTrue(sales().isEmpty())
        } finally { claim.release() }
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals(1, sales().size)
        assertEquals(null, SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
    }

    /**
     * #7 (fifth round): one of the protections' own stops going back holds the row a moment. The target sale is never waited for under the look's
     * lock: this look skips it at once (its stop left resting, nothing sold). Sixth round: the skip asks for a look about a second on, which
     * sells once the stop has let the row go - within the same tick.
     */
    @Test fun theTargetSkipsTheLookWhileItsOwnStopGoesBackAndLooksAgain() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", Protections.STOP_CLAIM_OWNER)!!
        val before = kite.requests.size
        val ticking = Thread { tick() }.apply { start() }
        // Once the look has begun (its first read), the look's lock is free again within its own work - never held a second for the stop.
        val until = System.currentTimeMillis() + 10_000
        while (kite.requests.size == before && System.currentTimeMillis() < until) Thread.sleep(5)
        val t0 = System.nanoTime()
        runBlocking { Protections.holdLockForTest(kotlinx.coroutines.CompletableDeferred(Unit)) }
        val lockMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("its stop left resting", kite.order(p.stopOrderId!!).working)
        assertTrue(sales().isEmpty())
        claim.release()
        ticking.join(15_000)
        assertTrue("never waited for the stop under the lock (the old wait was a full second): $lockMs ms", lockMs < 700)
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals("sold on the look after", 1, sales().size)
        assertEquals(null, SquareOffExit.exitClaimedBy("NFO", sym, "MIS"))
    }

    /**
     * Sixth round: the stop a target sale took out showed cancelled late, and as the next look came one of the protections' own stops was going
     * back on the row - that look skipped the sale. The cancel's mark is kept until the sale has the row, so the look after sells; the stop is
     * never put back.
     */
    @Test fun aSkippedSaleKeepsTheLateCancelsMarkAndSellsNeverPutsTheStopBack() {
        protect()
        val p = active().single()
        val sid = p.stopOrderId!!
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.cancelLagReads = 50
        fastLookAlone()
        assertTrue("nothing sold while its cancel has not shown", sales().isEmpty())
        assertTrue(Protections.takenOverForTest(sid))
        kite.order(sid).status = "CANCELLED"                                 // the cancel shows now
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", Protections.STOP_CLAIM_OWNER)!!
        Thread { Thread.sleep(400); claim.release() }.start()
        tick()
        assertEquals("sold on the look after", listOf("75"), sales().map { it.form["quantity"] })
        assertEquals("never put back", 1, kite.placed.count { it.form["order_type"] == "SL" })
        assertFalse(Alerts.posted.any { it.text.contains("cancelled them for your exit") })
        assertEquals(0, held())
    }

    /**
     * B6-G follow-ups, item 1: Kite's unsure answer to the target's sale ([Broker.KiteUnsure]) goes straight to the lost sale's route - no second
     * ~4 s search of its own - and its stop stays out while the sale may be going.
     */
    @Test fun anUnsureAnswerToATargetSaleGoesStraightToItsLostSale() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.nextPlace(reply = FakeKite.Reply.GENERAL_EXCEPTION)
        val searches = Broker.callerSearchesForTest.get()
        tick()
        assertEquals("no second search of its own", searches, Broker.callerSearchesForTest.get())
        assertTrue("looked for as a lost sale", Broker.lostKeys("prottarget:").isNotEmpty())
        assertEquals("its stop stays out while the sale may be going", "CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals("never put back beside it", 1, kite.placed.count { it.form["order_type"] == "SL" })
    }

    /** The stops resting at Zerodha on [sym] (working SL orders). */
    private fun restingStops() = kite.orders.values.count { it.symbol == sym && it.type == "SL" && it.working }

    /**
     * Sixth round (the unsure stop): Zerodha took the stop, but its answer was a GeneralException and its order book did not show it yet
     * ([Broker.KiteUnsure]). The protection is kept as "its stop may be resting", looked for, and no other stop is placed on the holding - not
     * even when Boss sets it again; once the book shows it, it is adopted as the stop. One stop, never two.
     */
    @Test fun anUnsureStopThatShowsLaterIsAdoptedNeverDoubled() {
        Broker.lostSearchInlineForTest = true; Broker.lostLookGapMsForTest = 0L
        kite.trailReads = 1_000                                              // in the book only later
        kite.nextPlace(reply = FakeKite.Reply.GENERAL_EXCEPTION_AFTER_ACCEPT)
        val msg = protect()
        assertTrue(msg, msg.contains("the stop may be resting at Zerodha"))
        val p = active().single()
        assertEquals(Protections.STOP_LOST, p.note)
        assertEquals(null, p.stopOrderId)
        val again = protect()
        assertTrue(again, again.startsWith("Not protected: the stop placed on $sym a moment ago may be resting at Zerodha"))
        at(12, 5)
        tick()
        assertEquals("nothing placed beside it while it is looked for", 1, restingStops())
        kite.showInBook()
        tick()
        val q = active().single()
        assertEquals("adopted as its stop", kite.orders.values.single { it.type == "SL" }.id, q.stopOrderId)
        assertEquals("", q.note)
        tick()
        assertEquals("one stop, never two", 1, restingStops())
    }

    /**
     * Sixth round: the stop never reached Zerodha (its answer a GeneralException, nothing in the book). Nothing is placed beside it while it is
     * looked for; only once a read of the book that succeeded 2 minutes after it went still does not show it is it placed again - once.
     */
    @Test fun anUnsureStopThatNeverShowsIsPlacedAgainOnlyAfterTheWindow() {
        Broker.lostSearchInlineForTest = true; Broker.lostLookGapMsForTest = 0L
        kite.nextPlace(reply = FakeKite.Reply.GENERAL_EXCEPTION)
        val msg = protect()
        assertTrue(msg, msg.contains("the stop may be resting at Zerodha"))
        val p = active().single()
        at(12, 5)
        tick()
        assertEquals("nothing placed within the window", 0, restingStops())
        assertEquals(Protections.STOP_LOST, active().single().note)
        Broker.ageLostForTest("protstop:${p.id}", Broker.LOST_SEARCH_MS + 1_000)
        tick()
        val q = active().single()
        assertEquals("placed again once a good read 2 minutes on did not show it", 1, restingStops())
        assertEquals(kite.orders.values.single { it.type == "SL" && it.working }.id, q.stopOrderId)
        tick()
        assertEquals("once", 1, restingStops())
    }

    /** The sale refused outright: the stop is put back at once, the target still the app's. */
    @Test fun aRefusedTargetSalePutsTheStopBackAtOnce() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "MARKET orders are blocked for this contract.")
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals(75, held())
        val now = active().single()
        assertNotNull(now.stopOrderId)
        assertTrue("a new stop rests", now.stopOrderId != p.stopOrderId && kite.order(now.stopOrderId!!).working)
        assertEquals("75", kite.placed.last().form["quantity"]); assertEquals("SL", kite.placed.last().form["order_type"])
        assertTrue("still the app's target", now.appTarget); assertEquals(130.0, now.target!!, 1e-9)
        assertTrue(Alerts.posted.any { it.text.contains("refused the sale") })
        assertFalse("its mark ended with the put-back", Protections.takenOverForTest(p.stopOrderId!!))
    }

    /** The stop filled in part as it came out: only the rest is sold. */
    @Test fun aStopFilledInPartAsItCameOutSellsOnlyTheRest() {
        kite.position(sym, 150, 100.0, product = "MIS")
        protect(qty = 150)
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        // Its cancel finds 75 of it filled meanwhile (it triggered as it came out).
        kite.afterNext("/orders/regular/${p.stopOrderId}") {
            val o = kite.order(p.stopOrderId!!)
            o.filled = 75; o.avg = 90.0
            kite.positions["NFO:$sym:MIS"] = 75 to 100.0
        }
        tick()
        assertEquals("only the rest", listOf("75"), sales().map { it.form["quantity"] })
        assertEquals("flat, never short", 0, held())
    }

    /**
     * A stop still working after its cancel (not confirmed out): nothing is sold beside it. Second round: no second cancel while the first
     * may still land (its marks younger than [Protections.TAKEN_SETTLE_MS]); once they end, the next look cancels afresh and sells.
     */
    @Test fun aStopNotConfirmedOutSellsNothing() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.cancelsLost = 1
        tick()
        assertTrue("its stop still rests", kite.order(p.stopOrderId!!).working)
        assertTrue("nothing beside it", sales().isEmpty())
        tick()
        assertEquals("one cancel while it may still land", 1, kite.requests.count { it.method == "DELETE" })
        assertTrue(sales().isEmpty())
        Protections.ageTakenOverForTest(p.stopOrderId!!, Protections.TAKEN_SETTLE_MS + 1_000)
        Protections.ageTakenOverForTest(Protections.TARGET_CANCEL + p.stopOrderId!!, Protections.TAKEN_SETTLE_MS + 1_000)
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals(1, sales().size)
        assertEquals(0, held())
    }

    // ---- second round of the B6 review ------------------------------------------------------------------------------------------

    /** A cancel Zerodha's book shows a moment late (pending at the first read) is read again: the target sells in the same look. */
    @Test fun aCancelShownPendingAtFirstIsReadAgainAndTheTargetSells() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.cancelLagReads = 3
        fastLookAlone()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals(listOf("75"), sales().map { it.form["quantity"] })
        assertEquals("no stop put back", 1, kite.placed.count { it.form["order_type"] == "SL" })
        assertEquals(0, held())
    }

    /**
     * The loop the review found: a cancel still pending after a second of reads left the stop out; the next look put it back ("cancelled for
     * your exit") and the look after cancelled it again - the target never sold. Now the next look that finds it cancelled sells at once.
     */
    @Test fun aStopWhoseCancelShowsLateIsSoldOnTheNextLookNeverPutBack() {
        protect()
        val p = active().single()
        val sid = p.stopOrderId!!
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.cancelLagReads = 50
        fastLookAlone()
        assertTrue("nothing sold while its cancel has not shown", sales().isEmpty())
        assertTrue(Protections.takenOverForTest(sid))
        kite.order(sid).status = "CANCELLED"                                 // the cancel shows now
        tick()
        assertEquals("sold at once", listOf("75"), sales().map { it.form["quantity"] })
        assertEquals("never put back", 1, kite.placed.count { it.form["order_type"] == "SL" })
        assertFalse(Alerts.posted.any { it.text.contains("cancelled them for your exit") })
        assertEquals(1, kite.requests.count { it.method == "DELETE" })
        assertEquals(0, held())
    }

    /** The same, but the price fell back below the target by the time the cancel showed (and some seconds passed): put back, as before. */
    @Test fun aStopWhoseCancelShowsLateIsPutBackWhenTheTargetIsGone() {
        protect()
        val p = active().single()
        val sid = p.stopOrderId!!
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.cancelLagReads = 50
        fastLookAlone()
        kite.order(sid).status = "CANCELLED"
        kite.quote("NFO:$sym", 120.0, 119.95, 120.05)
        Protections.ageTakenOverForTest(Protections.TARGET_CANCEL + sid, 6_000)
        tick()
        assertTrue("nothing sold", sales().isEmpty())
        val now = active().single()
        assertTrue("its stop rests again", now.stopOrderId != sid && kite.order(now.stopOrderId!!).working)
        assertFalse(Protections.takenOverForTest(Protections.TARGET_CANCEL + sid))
    }

    /** A sale on a cancel that showed late, refused by Zerodha: the stop goes back at once (one try for that cancel, never one each look). */
    @Test fun aSaleOnALateCancelThatIsRefusedPutsTheStopBack() {
        protect()
        val p = active().single()
        val sid = p.stopOrderId!!
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.cancelLagReads = 50
        fastLookAlone()
        kite.order(sid).status = "CANCELLED"
        kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "MARKET orders are blocked for this contract.")
        tick()
        val now = active().single()
        assertTrue("its stop rests again", now.stopOrderId != sid && kite.order(now.stopOrderId!!).working)
        assertFalse(Protections.takenOverForTest(Protections.TARGET_CANCEL + sid))
        assertEquals(75, held())
    }

    /**
     * WHO CANCELLED A STOP: Boss cancels the stop in Kite just as the target is reached - the app's cancel is refused and the stop shows
     * cancelled. Not the app's doing: its marks end, nothing is sold, and the stop is never put back; the next look says it is his.
     */
    @Test fun aStopBossCancelledAsTheTargetWasReachedIsNeverPutBack() {
        protect()
        val p = active().single()
        val sid = p.stopOrderId!!
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        Protections.testBeforeTargetCancel = { kite.order(sid).status = "CANCELLED"; kite.order(sid).message = "" }
        try { tick() } finally { Protections.testBeforeTargetCancel = null }
        assertTrue("nothing sold", sales().isEmpty())
        assertFalse("its marks ended", Protections.takenOverForTest(sid) || Protections.takenOverForTest(Protections.TARGET_CANCEL + sid))
        tick()
        assertEquals("never put back", 1, kite.placed.count { it.form["order_type"] == "SL" })
        assertTrue("ended", active().isEmpty())
        assertTrue(Alerts.posted.any { it.text.contains("cancelled by you") && it.text.contains("is not watched any more") })
    }

    /** m-2: a square-off holding the row past its gap (its stops slow to come out): no stop goes back beside it; once it lets go, it does. */
    @Test fun noStopGoesBackWhileASquareOffHoldsTheRowPastItsGap() {
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        protect(product = "NRML", target = null)
        val p = active().single()
        val sid = p.stopOrderId!!
        val claim = SquareOffExit.claimExit("NFO", sym, "NRML", "iraalgoflat")!!
        try {
            Protections.noteTakenOver(listOf(sid))                           // the square-off's takeOut: marked, then cancelled
            runBlocking { Broker.cancel(sid) }
            at(12, 5)
            tick()
            assertEquals("nothing put back beside its sale", 1, kite.placed.count { it.form["order_type"] == "SL" })
        } finally { claim.release() }
        var claimedWhilePut: String? = "not put back"
        kite.afterNext("/orders/regular") { claimedWhilePut = SquareOffExit.exitClaimedBy("NFO", sym, "NRML") }
        tick()
        val now = active().single()
        assertTrue("put back once it let go", now.stopOrderId != sid && kite.order(now.stopOrderId!!).working)
        assertEquals("the row claimed under the protections' stop name while it went in", Protections.STOP_CLAIM_OWNER, claimedWhilePut)
        assertEquals("said as a stop going back", "a protection's stop going back at Zerodha", SquareOffExit.claimWords(Protections.STOP_CLAIM_OWNER))
        assertEquals(null, SquareOffExit.exitClaimedBy("NFO", sym, "NRML"))
    }

    /** m-4: after a restart, a lost target sale's saved mark makes its row "not known" to the square-offs until its search would have ended. */
    @Test fun aLostTargetSaleIsNotKnownToTheSquareOffsAfterARestart() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.trailReads = 1_000
        kite.positionsTrailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        tick()
        assertEquals(1, sales().size)
        Protections.restartForTest(); Broker.forgetLostForTest()
        active()
        assertEquals("not known", -1, Protections.targetSalesGoing(sym, "MIS"))
        assertEquals("another row is", 0, Protections.targetSalesGoing("NIFTY26OCT24600CE", "MIS"))
        assertEquals("another product is", 0, Protections.targetSalesGoing(sym, "NRML"))
        Protections.ageTakenOverForTest("prottarget:${p.id}", Broker.LOST_SEARCH_MS)
        assertEquals("the order book shows it by now", 0, Protections.targetSalesGoing(sym, "MIS"))
    }

    /**
     * m-4 (review): after a start, before the list is read every row is "not known", and with the marks' file not read yet a row with an
     * app-watched target is - only within the lost search's time from the start.
     */
    @Test fun theSquareOffsAreToldNotKnownBeforeTheListOrTheMarksAreRead() {
        protect()
        Protections.restartForTest(); Protections.startedForTest(0)
        assertEquals("the list not read: any row", -1, Protections.targetSalesGoing("RELIANCE", "CNC"))
        Protections.marksReadForTest = { throw java.io.IOException("test: the Keystore is busy") }
        try {
            active()
            assertEquals("its app-watched target's row", -1, Protections.targetSalesGoing(sym, "MIS"))
            assertEquals("another row", 0, Protections.targetSalesGoing("RELIANCE", "CNC"))
            Protections.startedForTest(Broker.LOST_SEARCH_MS)
            assertEquals("the order book shows any such sale by now", 0, Protections.targetSalesGoing(sym, "MIS"))
        } finally { Protections.marksReadForTest = null }
    }

    /** m-4: a target sale is counted for the square-offs' gap from when Zerodha took it, not from when it started. */
    @Test fun aTargetSaleIsCountedForTheGapFromWhenZerodhaTookIt() {
        SquareOffExit.resetForTest(gap = SquareOffExit.GAP_MS)
        var t = 50_000_000L
        SquareOffExit.clock = { t }
        protect()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.afterNext("/orders/regular") { t += 10_000 }                    // its placing took 10 s
        tick()
        assertEquals(1, sales().size)
        t += 10_000                                                          // 20 s after it started, 10 s after Zerodha took it
        assertEquals(75, Protections.targetSalesGoing(sym, "MIS"))
        t += 6_000
        assertEquals(0, Protections.targetSalesGoing(sym, "MIS"))
    }

    /** m-1: a target sale held back (here a square-off's claim) leaves the stream looks quiet for a while - no flag, no look apart. */
    @Test fun aHeldBackTargetSaleDoesNotAskLookAfterLook() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        val claim = SquareOffExit.claimExit("NFO", sym, "MIS", "iraalgoflat")!!
        try {
            tick()                                                           // tried: held back, nothing taken out
            assertTrue(sales().isEmpty())
            KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(12_345_678L, 131.0)))
            runBlocking { Protections.tick(fast = true, stream = true) }
            assertFalse("not marked", Protections.targetMarkedForTest(p.id))
            assertFalse("no look apart asked", Protections.followUpPendingForTest())
            // Its quiet time over: marked again (the look apart held back here, as when one is already waiting).
            at(12, 6)
            val apart = Protections::class.java.getDeclaredField("followApart").apply { isAccessible = true }.get(Protections) as java.util.concurrent.atomic.AtomicBoolean
            apart.set(true)
            try {
                KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(12_345_678L, 131.0)))
                runBlocking { Protections.tick(fast = true, stream = true) }
                assertTrue("marked again", Protections.targetMarkedForTest(p.id))
            } finally { apart.set(false) }
        } finally { claim.release() }
    }

    /** With the app's own target (no order at Zerodha) a filled stop never says "the other exit was cancelled". */
    @Test fun aFilledStopWithTheAppsTargetSaysNoOtherExit() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.fill(p.stopOrderId!!, 90.0)
        tick()
        assertFalse(Alerts.posted.any { it.text.contains("the other exit was cancelled") })
        assertTrue(Alerts.posted.any { it.text.contains("stop filled at Zerodha; its target, which the app watched, ends with it") })
    }

    /** One look alone (its follow-up held back, as one already waiting), so a test sees what that look did. */
    private fun fastLookAlone() {
        val apart = Protections::class.java.getDeclaredField("followApart").apply { isAccessible = true }.get(Protections) as java.util.concurrent.atomic.AtomicBoolean
        apart.set(true)
        try { runBlocking { Protections.tick(fast = true) } } finally { apart.set(false) }
    }

    /**
     * A stream tick (the live lane) only marks the target reached and asks a look apart - no Zerodha write from the tick's look; the look
     * apart sells the target the tick reached even if the price has slipped back a moment later.
     */
    @Test fun aStreamTickOnlyMarksTheTargetAndALookApartSells() {
        protect()
        val p = active().single()
        at(12, 5)
        tick()
        // The look apart held back (as when one is already waiting), so this sees what the stream look itself does.
        val apart = Protections::class.java.getDeclaredField("followApart").apply { isAccessible = true }.get(Protections) as java.util.concurrent.atomic.AtomicBoolean
        apart.set(true)
        try {
            kite.requests.clear()
            KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(12_345_678L, 131.0)))
            runBlocking { Protections.tick(fast = true, stream = true) }
            assertTrue("no Zerodha write from the tick's look: ${kite.requests}", kite.requests.none { it.method != "GET" })
            assertTrue("nothing sold yet", sales().isEmpty())
        } finally { apart.set(false) }
        KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(12_345_678L, 129.5)))
        tick()
        assertEquals("CANCELLED", kite.order(p.stopOrderId!!).status)
        assertEquals("the target the tick reached is sold", 1, sales().size)
        assertEquals(0, held())
    }

    /** The target's sale whose reply is lost and not found: no stop goes back beside it until the book shows it or it is let go. */
    @Test fun aLostTargetSaleKeepsTheStopOutUntilItIsKnown() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.trailReads = 1_000
        kite.positionsTrailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        Broker.lostLookGapMsForTest = 0; Broker.lostSearchInlineForTest = true
        tick()
        assertEquals(1, sales().size)
        assertTrue(Alerts.posted.any { it.text.contains("answer to the sale was lost") })
        at(12, 6); tick(); tick()
        assertEquals("no stop beside a sale that may be selling", 1, kite.placed.count { it.form["order_type"] == "SL" })
        kite.showInBook(); kite.positionsCatchUp()
        at(12, 7); tick()
        com.optionslab.app.testing.SecondRead.clockOn(); tick()
        assertTrue("found, flat: ended", active().isEmpty())
        assertEquals(1, kite.placed.count { it.form["order_type"] == "SL" })
        assertEquals(0, held())
    }

    /**
     * Review: after a restart a lost target sale's search (memory only) is gone, but its mark is kept: no stop goes back beside it for two
     * minutes from the sale; after that the lag-guarded reads decide - the sale shown filled, nothing is put back.
     */
    @Test fun aLostTargetSaleStillKeepsTheStopOutAfterARestart() {
        protect()
        val p = active().single()
        at(12, 5)
        kite.quote("NFO:$sym", 131.0, 130.95, 131.05)
        kite.trailReads = 1_000
        kite.positionsTrailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        tick()
        assertEquals(1, sales().size)
        Protections.restartForTest(); Broker.forgetLostForTest()              // the phone restarted: the search is gone
        at(12, 6); tick()
        assertEquals("no stop beside the lost sale", 1, kite.placed.count { it.form["order_type"] == "SL" })
        assertEquals(1, active().size)
        Protections.ageTakenOverForTest("prottarget:${p.id}", Broker.LOST_SEARCH_MS)
        kite.showInBook()                                                     // the book shows it by now
        at(12, 8); tick()
        assertEquals("the sale shown filled: nothing put back", 1, kite.placed.count { it.form["order_type"] == "SL" })
        kite.positionsCatchUp()
        at(12, 9); tick()
        assertTrue(active().isEmpty())
        assertEquals(0, held())
    }

    /** A carried target refused for funds beside its stop is the app's; any other refusal is said - the re-placing at the open included. */
    @Test fun aCarriedTargetRefusedForFundsIsTheAppsAndOtherRefusalsAreSaid() {
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        kite.afterNext("/orders/regular") { kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Insufficient funds. Required margin is 52000.00 but available margin is 1200.00.") }
        val msg = protect(product = "NRML")
        assertTrue(msg, msg.contains(Protections.TARGET_BY_APP))
        val p = active().single()
        assertTrue(p.appTarget); assertEquals(130.0, p.target!!, 1e-9)
        // The open's re-placing: its stop and target ended with yesterday's session; the target refused otherwise is said, not dropped.
        runBlocking { Protections.remove(p.id) }
        kite.requests.clear()
        kite.afterNext("/orders/regular") { kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Price exceeds the circuit limit.") }
        val second = protect(product = "NRML")
        assertTrue(second, second.contains("The target was not placed (Price exceeds the circuit limit.)"))
        assertFalse(active().single().appTarget)
    }

    /** Before: restAgainInTurn dropped a refused target unsaid. Now the carried protection's open re-placing says it. */
    @Test fun aTargetRefusedAtTheOpenIsSaid() {
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        protect(product = "NRML")
        val p = active().single()
        assertNotNull(p.targetOrderId)
        // Boss's exit takes both out, then does not go: the stop and target are put back - the target refused.
        Protections.noteTakenOver(listOfNotNull(p.stopOrderId, p.targetOrderId))
        runBlocking { listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { Broker.cancel(it) } }
        at(12, 5)
        kite.afterNext("/orders/regular") { kite.nextPlace(FakeKite.Reply.INPUT_EXCEPTION, message = "Price exceeds the circuit limit.") }
        tick()
        val now = active().single()
        assertNotNull("the stop back", now.stopOrderId)
        assertTrue("the refusal said", Alerts.posted.any { it.text.contains("its target 130.00 could not be placed again") })
    }

    // ---- M6: the taken-over marks kept across a restart ---------------------------------------------------------------------

    /** A square-off's or Boss's own exit from the app took the stop out, then the phone restarted: still the app's cancel - put back. */
    @Test fun aTakenOverMarkSurvivesARestartAndTheStopIsPutBack() {
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        protect(product = "NRML", target = null)
        val p = active().single()
        Protections.noteTakenOver(listOf(p.stopOrderId!!))
        runBlocking { Broker.cancel(p.stopOrderId!!) }
        Protections.restartForTest()                                         // the phone restarted before the put-back
        at(12, 5)
        tick()
        val now = active().single()
        assertTrue("put back, never read as Boss's", now.stopOrderId != null && now.stopOrderId != p.stopOrderId)
        assertTrue(kite.order(now.stopOrderId!!).working)
        assertFalse(Alerts.posted.any { it.text.contains("cancelled by you") })
        assertFalse("the mark ended with the put-back", Protections.takenOverForTest(p.stopOrderId!!))
        Protections.restartForTest()
        active()
        assertFalse("and on disk too", Protections.takenOverForTest(p.stopOrderId!!))
    }

    /** Without a mark (Boss cancelled it in Kite), the same cancel is his: never put back - as before. */
    @Test fun withoutAMarkACancelledStopIsBossesAfterARestart() {
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        protect(product = "NRML", target = null)
        val p = active().single()
        runBlocking { Broker.cancel(p.stopOrderId!!) }
        Protections.restartForTest()
        at(12, 5)
        tick()
        assertEquals("never put back", 1, kite.placed.count { it.form["order_type"] == "SL" })
        assertTrue(active().isEmpty())
    }

    /** A mark older than an hour is not taken back after a restart; one whose stop still works two minutes on ends (its cancel never took). */
    @Test fun takenOverMarksExpire() {
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        protect(product = "NRML", target = null)
        val p = active().single()
        val id = p.stopOrderId!!
        Protections.noteTakenOver(listOf(id))
        at(12, 5)
        tick()
        assertTrue("kept while its cancel may still land", Protections.takenOverForTest(id))
        Protections.ageTakenOverForTest(id, Protections.TAKEN_SETTLE_MS + 1_000)
        tick()
        assertFalse("still working two minutes on: ended", Protections.takenOverForTest(id))
        // Boss now cancels it in Kite: his - never put back.
        runBlocking { Broker.cancel(id) }
        tick()
        assertEquals(1, kite.placed.count { it.form["order_type"] == "SL" })
        // An hour-old mark kept on disk is not read back after a restart; a fresh one is.
        Protections.drainMarksForTest()
        val now = System.currentTimeMillis()
        com.optionslab.app.security.Vault.writeFile(java.io.File(context.noBackupFilesDir, "protections_marks.vault"),
            org.json.JSONObject().put("old-1", now - 61 * 60_000L).put("fresh-1", now - 60_000L).toString().toByteArray(Charsets.UTF_8))
        Protections.restartForTest()
        active()
        assertFalse(Protections.takenOverForTest("old-1"))
        assertTrue(Protections.takenOverForTest("fresh-1"))
    }

    /**
     * Second round (nit with a safety effect): a read of the marks' file that fails for a moment (the Keystore) leaves them not loaded, and no
     * write replaces the file meanwhile; read later, its marks come back beside the new ones. One that fails authentication, or does not
     * parse, is set aside and the marks start afresh.
     */
    @Test fun aMarksFileThatCannotBeReadNowIsNeverWrittenOver() {
        Protections.noteTakenOver(listOf("kept-1"))
        Protections.drainMarksForTest()
        val f = java.io.File(context.noBackupFilesDir, "protections_marks.vault")
        val before = f.readBytes()
        Protections.restartForTest()
        Protections.marksReadForTest = { throw java.io.IOException("test: the Keystore is busy") }
        try {
            active()
            assertFalse(Protections.marksLoadedForTest())
            Protections.noteTakenOver(listOf("new-1"))
            Protections.drainMarksForTest()
            assertTrue("the file is as it was", before.contentEquals(f.readBytes()))
        } finally { Protections.marksReadForTest = null }
        Protections.noteTakenOver(listOf("new-2"))                         // the next write reads it first
        Protections.drainMarksForTest()
        assertTrue(Protections.marksLoadedForTest())
        Protections.restartForTest(); active()
        assertTrue(Protections.takenOverForTest("kept-1")); assertTrue(Protections.takenOverForTest("new-1")); assertTrue(Protections.takenOverForTest("new-2"))
        // Failed authentication: set aside, never overwritten; the marks start afresh.
        Protections.restartForTest()
        Protections.marksReadForTest = { throw com.optionslab.app.security.Vault.Tampered() }
        try { active() } finally { Protections.marksReadForTest = null }
        assertTrue(Protections.marksLoadedForTest())
        assertTrue("kept aside", f.parentFile!!.listFiles()!!.any { it.name.startsWith("protections_marks.vault.unreadable.") })
        // Not parsed: the same.
        Thread.sleep(5)                                                      // (a second set-aside copy under its own time)
        Protections.drainMarksForTest()
        com.optionslab.app.security.Vault.writeFile(f, "not json".toByteArray(Charsets.UTF_8))
        Protections.restartForTest(); active()
        assertTrue(Protections.marksLoadedForTest())
        assertEquals(2, f.parentFile!!.listFiles()!!.count { it.name.startsWith("protections_marks.vault.unreadable.") })
    }

    /** Review: a marks' file that never reads again (its key gone) is set aside after a few tries over minutes - new marks are saved again. */
    @Test fun aMarksFileThatNeverReadsIsSetAsideAfterAFewTries() {
        Protections.noteTakenOver(listOf("old-1"))
        Protections.drainMarksForTest()
        Protections.restartForTest()
        Protections.marksReadForTest = { throw java.io.IOException("test: the key is gone") }
        try {
            active()
            repeat(Protections.MARKS_FAILS_BEFORE_ASIDE) { Protections.noteTakenOver(listOf("n$it")); Protections.drainMarksForTest() }
            assertFalse("not within minutes", Protections.marksLoadedForTest())
            Protections.ageMarksFailsForTest(Protections.MARKS_FAIL_ASIDE_MS)
            Protections.noteTakenOver(listOf("last")); Protections.drainMarksForTest()
            assertTrue(Protections.marksLoadedForTest())
        } finally { Protections.marksReadForTest = null }
        val dir = java.io.File(context.noBackupFilesDir, "protections_marks.vault").parentFile!!
        assertTrue("kept aside", dir.listFiles()!!.any { it.name.startsWith("protections_marks.vault.unreadable.") })
        assertTrue(Alerts.posted.any { it.text.contains("could not be read and was set aside") })
        Protections.restartForTest(); active()
        assertTrue("new marks saved again", Protections.takenOverForTest("last") && Protections.takenOverForTest("n0"))
        assertFalse(Protections.takenOverForTest("old-1"))
    }

    /** "Erase everything": the marks' file goes with the rest. */
    @Test fun theMarksFileIsErased() {
        Protections.noteTakenOver(listOf("x1"))
        Protections.drainMarksForTest()
        val f = java.io.File(context.noBackupFilesDir, "protections_marks.vault")
        assertTrue(f.exists())
        assertTrue(Protections.filesForErase().contains(f))
        Protections.wipe()
        assertFalse(f.exists())
        Protections.restartForTest()
        active()
        assertFalse(Protections.takenOverForTest("x1"))
    }
}
