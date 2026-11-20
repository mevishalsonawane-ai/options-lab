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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 15 Nov (Boss's diagnostics, 6 Nov: a protection's stop cancelled by the app for his square-off stayed gone when the sale did not
 * go): a stop or target the app cancelled itself to send Boss's exit ([Protections.noteTakenOver]) is placed again once the exit
 * is done, for what is still held - never while his exit is going, never more than is held, and said loudly when it cannot be.
 * A stop cancelled with no reason given (Boss in Kite) is respected and said; one the exchange refused is ended as the exchange's.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProtectionsTakeoverTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500CE"

    @Before fun up() {
        kite = FakeKite()
        kite.instruments += FakeKite.Ins(12_345_678, sym, "NIFTY", Market.today().plusDays(20), 24_500.0, "CE", 75)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        kite.position(sym, 150, 100.0)
        kite.login()
        Alerts.forgetPosted()
        com.optionslab.app.testing.Background.clearAlerts()
        DayOco.resetForTest()
    }

    @After fun down() {
        ArmLive.handsBack("NFO", sym)
        Market.testClock = null; kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun protect(): Protections.Item = runBlocking {
        Protections.protectLive(sym, "NFO", "NRML", 150, 100.0, 90.0, null, 130.0)
        Protections.active().single()
    }

    /** The app cancels both exits for Boss's exit, as the exit takeover in Trade does. */
    private fun takeOver(p: Protections.Item) = runBlocking {
        val ids = listOfNotNull(p.stopOrderId, p.targetOrderId)
        Protections.noteTakenOver(ids)
        ids.forEach { Broker.cancel(it) }
    }

    private fun tick() = runBlocking { Protections.tick() }
    private fun later() { Market.testClock = MarketClockRule.clockAt(12, 5) }

    @Test fun anExitThatDidNotGoGetsItsStopAndTargetBack() {
        val p = protect()
        takeOver(p)
        later()
        kite.requests.clear()
        tick()
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertEquals(listOf("150", "150"), kite.placed.map { it.form["quantity"] })
        assertEquals("90.00", kite.placed[0].form["trigger_price"]); assertEquals("130.00", kite.placed[1].form["price"])
        val now = runBlocking { Protections.active() }.single()
        assertTrue(now.stopOrderId != p.stopOrderId && now.stopOrderId != null)
        assertEquals(90.0, now.stop!!, 1e-9)
        assertTrue(Alerts.posted.any { it.text.contains("rest at Zerodha again") })
        // The next look finds them working: nothing more.
        kite.requests.clear()
        tick()
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun whileBossesExitIsGoingNothingIsPlacedBesideIt() {
        val p = protect()
        ArmLive.handsOff("NFO", sym)
        takeOver(p)
        later()
        kite.requests.clear()
        tick(); tick()
        assertTrue("nothing beside his sale", kite.placed.isEmpty())
        assertEquals("kept, waiting", p.id, runBlocking { Protections.active() }.single().id)
        ArmLive.handsBack("NFO", sym)
        tick()
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
    }

    @Test fun anExitThatPartFilledGetsItsStopBackForWhatIsLeftOnly() {
        val p = protect()
        takeOver(p)
        kite.position(sym, 75, 100.0)                                       // half of it sold
        later()
        kite.requests.clear()
        tick()
        assertEquals(listOf("75", "75"), kite.placed.map { it.form["quantity"] })
        assertEquals(75, runBlocking { Protections.active() }.single().qty)
    }

    @Test fun anExitThatFilledEndsTheProtectionAndPlacesNothing() {
        val p = protect()
        takeOver(p)
        kite.position(sym, 0, 0.0)                                          // 20 Nov 2026: Zerodha's row at 0 (a missing row is not known)
        later()
        kite.requests.clear()
        tick()
        com.optionslab.app.testing.SecondRead.clockOn(); tick()                                                        // closed counts on a second agreeing read
        assertTrue(kite.placed.isEmpty())
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    /** Boss's own working sale covers part: only the rest gets a stop again (never two exits for the same units). */
    @Test fun bossesSaleStillWorkingIsSubtracted() {
        val p = protect()
        takeOver(p)
        kite.nextPlace(outcome = FakeKite.Outcome.OPEN)
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 75, 75, "NRML", "LIMIT", 150.0,
            0.05, "NFO", "manual"), exit = true) }
        later()
        kite.requests.clear()
        tick()
        assertEquals(listOf("75", "75"), kite.placed.map { it.form["quantity"] })
    }

    @Test fun aStopThatCannotBePlacedAgainIsSaidLoudly() {
        val p = protect()
        takeOver(p)
        later()
        kite.nextPlace(reply = FakeKite.Reply.INPUT_EXCEPTION, message = "RMS: blocked")      // the stop placed again: refused
        tick()
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.ERROR && it.text.contains("could not be placed again") && it.text.contains("NOT protected") })
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    /** Not cancelled by the app: a stop cancelled with no reason given (Boss in Kite) is respected, never put back, and said loudly. */
    @Test fun aStopBossCancelledHimselfIsNotPutBack() {
        val p = protect()
        runBlocking { Broker.cancel(p.stopOrderId!!) }
        later()
        kite.requests.clear()
        tick()
        assertTrue(kite.placed.isEmpty())
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.ERROR && it.text.contains("It was cancelled by you") })
        val now = runBlocking { Protections.active() }.single()
        assertEquals("its target stays (Boss's protection, not the app's own)", p.targetOrderId, now.targetOrderId)
        assertEquals(null, now.stopOrderId)
        assertEquals("stop cancelled", now.note)
    }

    /** The exchange's cancel, its words naming the exchange, is ended as the exchange's (so an automatic cover may come back). */
    @Test fun aStopTheExchangeCancelledIsNotedAsTheExchanges() {
        val p = protect()
        kite.reject(p.stopOrderId!!, message = "Cancelled by exchange: circuit limit breached.", status = "CANCELLED")
        later()
        tick()
        assertEquals(com.optionslab.ira.ManualCoverRule.STOP_BY_EXCHANGE, runBlocking { Protections.active() }.single().note)
        assertFalse(Alerts.posted.any { it.text.contains("cancelled by you") })
        assertTrue(Alerts.posted.any { it.text.contains("was cancelled (Cancelled by exchange: circuit limit breached.)") })
        assertEquals("not the app's own: its target stays", "OPEN", kite.order(p.targetOrderId!!).status)
    }

    /** R5: a cancel in words the app does not recognise is taken as Boss's: respected, and said loudly. */
    @Test fun aCancelInUnknownWordsIsTakenAsBosss() {
        val p = protect()
        kite.reject(p.stopOrderId!!, message = "Your order has been cancelled.", status = "CANCELLED")
        later()
        kite.requests.clear()
        tick()
        assertEquals("stop cancelled", runBlocking { Protections.active() }.single().note)
        assertTrue(Alerts.posted.any { it.kind == Alerts.Kind.ERROR && it.text.contains("It was cancelled by you") })
        assertTrue("never put back", kite.placed.isEmpty())
    }

    /** A protection resting as one GTT ("one GTT" on): its GTT deleted for an exit that did not go is placed again, never ended as gone. */
    @Test fun aDayGttDeletedForAnExitThatDidNotGoIsPlacedAgain() {
        DayOco.resetForTest(on = true)
        try {
            val said = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 150, 100.0, 90.0, null, 130.0) }
            assertTrue(said, said.contains("as one GTT"))
            val gid = runBlocking { Protections.active() }.single().gttId!!
            Protections.noteTakenOver(listOf("gtt:$gid"))
            runBlocking { Broker.deleteGtt(gid) }
            later()
            tick()
            val now = runBlocking { Protections.active() }.single()
            val again = now.gttId
            assertTrue("a new GTT rests", again != null && again != gid && kite.gtts.containsKey(again))
            assertFalse(Alerts.posted.any { it.text.contains("no longer at Zerodha") })
        } finally { DayOco.resetForTest() }
    }

    /** Not deleted by the app: a GTT gone at Zerodha is said and ended, as before. */
    @Test fun aDayGttGoneOtherwiseIsSaidAsBefore() {
        DayOco.resetForTest(on = true)
        try {
            runBlocking { Protections.protectLive(sym, "NFO", "NRML", 150, 100.0, 90.0, null, 130.0) }
            val gid = runBlocking { Protections.active() }.single().gttId!!
            runBlocking { Broker.deleteGtt(gid) }
            later()
            tick()
            assertTrue(runBlocking { Protections.active() }.isEmpty())
            assertTrue(Alerts.posted.any { it.text.contains("no longer at Zerodha") })
        } finally { DayOco.resetForTest() }
    }

    // ---- 15 Nov, review: Boss's exit filled but Zerodha's positions still trail it -----------------------------------------------

    /** Boss's sale of all of it, filled a moment ago (the takeover's ids still noted): the positions trail it, so nothing is put back. */
    private fun bossSoldAllWithThePositionsTrailing() = runBlocking {
        kite.positionsTrailReads = 1_000                                    // every positions read shows the old 150
        Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 150, 75, "NRML", "LIMIT", 99.95,
            0.05, "NFO", "manual"), exit = true)
        assertEquals("sold at Zerodha", 0, kite.positions.getValue("NFO:$sym:NRML").first)
    }

    @Test fun aRecentExitFillWithThePositionsTrailingPlacesNoStop() {
        val p = protect()
        takeOver(p)
        later()                                                              // the fill is stamped now, within the minute
        bossSoldAllWithThePositionsTrailing()
        kite.requests.clear()
        tick(); tick()
        assertTrue("never a stop on a flat account", kite.placed.isEmpty())
        assertEquals(p.id, runBlocking { Protections.active() }.single().id)
        assertFalse(Alerts.posted.any { it.text.contains("rest at Zerodha again") })
    }

    /** The exit filled in full (AppModel says so) and the positions trail it: the lag guard sees the fill, nothing is put back or said. */
    @Test fun anExitFilledInFullWithThePositionsTrailingGetsNoStop() {
        val p = protect()
        takeOver(p)
        later()
        kite.positionsTrailReads = 1_000
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 150, 75, "NRML", "LIMIT", 99.95,
            0.05, "NFO", "manual"), exit = true) }
        val ids = listOfNotNull(p.stopOrderId, p.targetOrderId)
        Protections.exitFilledFor(ids)
        assertTrue(Protections.exitDoneForTest(ids.first()))
        assertTrue("still marked, in case the position is still held after the wait", Protections.takenOverForTest(ids.first()))
        kite.requests.clear()
        tick(); tick()
        assertTrue(kite.placed.isEmpty())
        assertFalse("never said as Boss's own cancel", Alerts.posted.any { it.text.contains("cancelled by you") })
        assertEquals("kept until the positions show it closed", p.id, runBlocking { Protections.active() }.single().id)
    }

    /**
     * N1, R1: every leg "filled in full" but lots were added between the review and the send, so part is still held: the stop comes
     * back for what is held on the first regular look (never ended as Boss's cancel, no two-minute wait).
     */
    @Test fun anExitFilledInFullThatLeftPartHeldGetsItsStopBackAtOnce() {
        val p = protect()
        takeOver(p)
        later()                                                              // 12:05
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 75, 75, "NRML", "LIMIT", 99.95,
            0.05, "NFO", "manual"), exit = true) }                          // the stale square-off sold 75 of the 150
        val ids = listOfNotNull(p.stopOrderId, p.targetOrderId)
        Protections.exitFilledFor(ids)
        kite.requests.clear()
        tick()
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertEquals(listOf("75", "75"), kite.placed.map { it.form["quantity"] })
        assertEquals(75, runBlocking { Protections.active() }.single().qty)
        assertFalse(Alerts.posted.any { it.text.contains("cancelled by you") })
    }

    /** N2: an exit that filled only in part gets its stop back on the next regular look, for what is left (no minute's wait). */
    @Test fun aPartFilledExitGetsItsStopBackOnTheNextLook() {
        val p = protect()
        takeOver(p)
        later()
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 75, 75, "NRML", "LIMIT", 99.95,
            0.05, "NFO", "manual"), exit = true) }                          // filled a moment ago: 75 left
        kite.requests.clear()
        tick()
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertEquals(listOf("75", "75"), kite.placed.map { it.form["quantity"] })
    }

    /** N7: a day GTT deleted for the exit whose placing again fails stays marked, so every later try is made the same careful way. */
    @Test fun aDayGttNotPlacedAgainStaysMarkedForTheNextTry() {
        DayOco.resetForTest(on = true)
        try {
            runBlocking { Protections.protectLive(sym, "NFO", "NRML", 150, 100.0, 90.0, null, 130.0) }
            val gid = runBlocking { Protections.active() }.single().gttId!!
            Protections.noteTakenOver(listOf("gtt:$gid"))
            runBlocking { Broker.deleteGtt(gid) }
            ArmLive.handsOff("NFO", sym)                                    // Boss's exit still going: nothing placed this look
            later()
            tick()
            assertTrue(Protections.takenOverForTest("gtt:$gid"))
            assertEquals(gid, runBlocking { Protections.active() }.single().gttId)
            ArmLive.handsBack("NFO", sym)
            tick()
            assertFalse("placed now: no longer marked", Protections.takenOverForTest("gtt:$gid"))
            assertTrue(kite.gtts.isNotEmpty())
        } finally { DayOco.resetForTest() }
    }

    /**
     * R1: minutes after Boss's sale of all of it, the exit registry long expired and the fill no longer "recent", Zerodha's positions
     * still show the old quantity: the forced lag guard compares today's fills with Kite's day quantity, so no stop rests on a flat
     * account.
     */
    @Test fun aStalePositionsReadMinutesAfterTheFillNeverGetsAStop() {
        val p = protect()
        takeOver(p)
        later()                                                              // 12:05
        kite.positionsTrailReads = 1_000
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 150, 75, "NRML", "LIMIT", 99.95,
            0.05, "NFO", "manual"), exit = true) }
        Protections.exitFilledFor(listOfNotNull(p.stopOrderId, p.targetOrderId))
        Market.testClock = MarketClockRule.clockAt(12, 9)                  // four minutes on: past every wait and every "recent"
        ExitCoordinator.resetForTest()                                       // the exit registry's entries gone
        kite.requests.clear()
        tick(); tick()
        assertTrue("never a stop on a flat account", kite.placed.isEmpty())
        assertFalse(Alerts.posted.any { it.text.contains("cancelled by you") })
    }

    // ---- 15 Nov, fourth review: the order book trails Boss's fill too (the app heard it from Zerodha's stream first) ---------------

    private fun bossSells(qty: Int, bookTrails: Int, positionsTrail: Int): String = runBlocking {
        kite.trailReads = bookTrails                                        // his order missing from the next order-book reads
        kite.positionsTrailReads = positionsTrail
        Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, qty, 75, "NRML", "LIMIT", 99.95,
            0.05, "NFO", "manual"), exit = true)
    }

    /** F1: the book and the positions both trail his sale of all of it: nothing placed; the book catching up, the lag guard: still nothing. */
    @Test fun aSaleTheOrderBookDoesNotShowYetGetsNoStop() {
        val p = protect()
        takeOver(p)
        later()
        val boss = bossSells(150, bookTrails = 2, positionsTrail = 1_000)
        Protections.exitFilledFor(listOfNotNull(p.stopOrderId, p.targetOrderId), mapOf(boss to 150))
        kite.requests.clear()
        tick()
        assertTrue("the book does not show his fill yet: nothing", kite.placed.isEmpty())
        repeat(3) { tick() }
        assertTrue("the book shows it, the positions still trail: the lag guard counts it sold", kite.placed.isEmpty())
        assertEquals(p.id, runBlocking { Protections.active() }.single().id)
    }

    /** F1: part of it still held: its stop comes back once the order book shows his fill, for what is left only. */
    @Test fun aPartStillHeldGetsItsStopOnceTheBookShowsTheFill() {
        val p = protect()
        takeOver(p)
        later()
        val boss = bossSells(75, bookTrails = 2, positionsTrail = 0)
        Protections.exitFilledFor(listOfNotNull(p.stopOrderId, p.targetOrderId), mapOf(boss to 75))
        kite.requests.clear()
        tick()
        assertTrue("waits a look for the book", kite.placed.isEmpty())
        repeat(3) { tick() }
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertEquals(listOf("75", "75"), kite.placed.map { it.form["quantity"] })
    }

    /** F2: a day GTT deleted for an exit, nothing held once the trailing fill is counted: asked again, no one-GTT try used up. */
    @Test fun aDayGttPutBackWithNothingHeldUsesNoTry() {
        DayOco.resetForTest(on = true)
        try {
            runBlocking { Protections.protectLive(sym, "NFO", "NRML", 150, 100.0, 90.0, null, 130.0) }
            val p = runBlocking { Protections.active() }.single()
            val gid = p.gttId!!
            Protections.noteTakenOver(listOf("gtt:$gid"))
            runBlocking { Broker.deleteGtt(gid) }
            later()
            bossSells(150, bookTrails = 0, positionsTrail = 1_000)
            kite.requests.clear()
            tick()
            assertTrue(kite.placed.isEmpty() && kite.gtts.isEmpty())
            assertEquals("the one-GTT route kept", 0, Protections.gttTriesForTest(p.id))
            assertTrue("still marked", Protections.takenOverForTest("gtt:$gid"))
        } finally { DayOco.resetForTest() }
    }
}
