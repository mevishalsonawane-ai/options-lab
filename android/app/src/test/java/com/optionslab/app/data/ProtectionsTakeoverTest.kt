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
        kite.flat()
        later()
        kite.requests.clear()
        tick()
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

    /** The exchange's cancel, with its reason, is ended as the exchange's (so an automatic cover may come back). */
    @Test fun aStopTheExchangeCancelledIsNotedAsTheExchanges() {
        val p = protect()
        kite.reject(p.stopOrderId!!, message = "Your order has been cancelled because of market protection.", status = "CANCELLED")
        later()
        tick()
        assertEquals(com.optionslab.ira.ManualCoverRule.STOP_BY_EXCHANGE, runBlocking { Protections.active() }.single().note)
        assertFalse(Alerts.posted.any { it.text.contains("cancelled by you") })
        assertTrue(Alerts.posted.any { it.text.contains("was cancelled (Your order has been cancelled because of market protection.)") })
        assertEquals("not the app's own: its target stays", "OPEN", kite.order(p.targetOrderId!!).status)
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

    /** The exit filled in full (AppModel then forgets the takeover): nothing is put back or said while the positions trail. */
    @Test fun aTakeoverForgottenAfterItsExitFilledPutsNothingBack() {
        val p = protect()
        takeOver(p)
        val ids = listOfNotNull(p.stopOrderId, p.targetOrderId)
        Protections.forgetTakenOver(ids)
        assertFalse(Protections.takenOverForTest(ids.first()))
        kite.positionsTrailReads = 1_000
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 150, 75, "NRML", "LIMIT", 99.95,
            0.05, "NFO", "manual"), exit = true) }
        Market.testClock = MarketClockRule.clockAt(12, 10)                 // past the minute: the positions trail all the same
        kite.requests.clear()
        tick(); tick()
        assertTrue(kite.placed.isEmpty())
        assertFalse("never said as Boss's own cancel", Alerts.posted.any { it.text.contains("cancelled by you") })
        assertEquals("kept until the positions show it closed", p.id, runBlocking { Protections.active() }.single().id)
    }
}
