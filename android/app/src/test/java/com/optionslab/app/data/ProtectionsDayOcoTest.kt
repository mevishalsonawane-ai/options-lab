package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
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
import java.time.LocalDate

/**
 * 21 Oct, enhancement round 4 (idea 1): a carried (NRML) live protection's stop and target as ONE two-leg GTT in the day too
 * ([DayOco], off by default, on only with the PIN). Whichever leg fires, Zerodha cancels the other itself, so a dead phone never
 * leaves the target resting after the stop filled. The overnight GTT is kept at the open; a trailing stop moves it in place; less
 * held shrinks it; the position closed deletes it; switched off in market hours, it goes first (confirmed), then the two DAY orders.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProtectionsDayOcoTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500CE"

    @Before fun up() {
        ExitCoordinator.resetForTest()
        DayOco.resetForTest()
        kite = FakeKite()
        kite.instruments += FakeKite.Ins(12_345_678, sym, "NIFTY", LocalDate.now().plusDays(20), 24_500.0, "CE", 75)
        kite.position(sym, 75, 100.0)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        kite.login()
        session(10, 0)
        com.optionslab.app.testing.Background.clearAlerts()
    }

    @After fun down() { DayOco.resetForTest(); Market.testClock = null; kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    /** The clock at [h]:[m] IST on the [nth] trading day after today (still ticking). */
    private fun session(h: Int, m: Int, nth: Int = 1) {
        var d = LocalDate.now(com.optionslab.engine.IST)
        repeat(nth) { d = d.plusDays(1); while (!Market.isTradingDay(d)) d = d.plusDays(1) }
        val at = d.atTime(h, m).atZone(com.optionslab.engine.IST).toInstant()
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), at))
    }

    private fun protect(qty: Int = 75, product: String = "NRML", stop: Double? = 90.0, target: Double? = 130.0, trail: Double? = null) = runBlocking {
        Protections.protectLive(sym, "NFO", product, qty, 100.0, stop, trail, target)
    }

    private fun one() = runBlocking { Protections.active() }.single()
    private fun tick() = runBlocking { Protections.tick() }
    private fun writes() = kite.requests.filter { it.method != "GET" && !it.path.startsWith("/margins") && !it.path.startsWith("/charges") }
    private val gttPosts get() = kite.requests.filter { it.method == "POST" && it.path == "/gtt/triggers" }
    private val gttPuts get() = kite.requests.filter { it.method == "PUT" && it.path.startsWith("/gtt/triggers/") }
    private val gttDeletes get() = kite.requests.filter { it.method == "DELETE" && it.path.startsWith("/gtt/triggers/") }
    private fun triggers(id: Long): List<Double> = kite.gtts.getValue(id).getJSONObject("condition").getJSONArray("trigger_values").let { a -> (0 until a.length()).map { a.getDouble(it) } }
    private fun legQty(id: Long): Int = kite.gtts.getValue(id).getJSONArray("orders").getJSONObject(0).getInt("quantity")
    private fun alerted(t: String) = com.optionslab.app.work.Alerts.queue.value.any { it.text.contains(t) }

    // ---- the switch -------------------------------------------------------------------------------------------------------------

    @Test fun theSwitchIsOffByDefaultTakesThePinToGoOnAndIsSaved() {
        DayOco.load()
        assertFalse("off by default", DayOco.isOn())
        assertTrue(DayOco.setOn(true).contains("PIN"))
        assertFalse("never on without the PIN", DayOco.isOn())
        assertTrue(DayOco.setOn(true, pinConfirmed = true).startsWith("On:"))
        assertTrue(DayOco.isOn())
        DayOco.resetForTest()
        DayOco.load()
        assertTrue("read back from the settings", DayOco.isOn())
        assertTrue(DayOco.setOn(false).startsWith("Off:"))
        DayOco.load()
        assertFalse(DayOco.isOn())
    }

    @Test fun offTheDaysStopAndTargetAreTwoOrdersAsBefore() {
        protect()
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertTrue(gttPosts.isEmpty())
        assertNull(one().gttId)
    }

    // ---- placing ----------------------------------------------------------------------------------------------------------------

    @Test fun onANewCarriedProtectionRestsAsOneTwoLegGttAndNoOrders() {
        DayOco.resetForTest(on = true)
        val said = protect()
        assertTrue(said, said.contains("as one GTT"))
        assertTrue("no DAY orders", kite.placed.isEmpty())
        val g = kite.gtts.values.single()
        assertEquals("two-leg", g.getString("type"))
        val id = g.getLong("id")
        assertEquals(listOf(90.0, 130.0), triggers(id))
        val leg = g.getJSONArray("orders").getJSONObject(0)
        assertEquals("SELL", leg.getString("transaction_type")); assertEquals(75, leg.getInt("quantity")); assertEquals("NRML", leg.getString("product"))
        val p = one()
        assertEquals(id, p.gttId)
        assertEquals(Protections.DAY_GTT, p.note)
        assertNull(p.stopOrderId); assertNull(p.targetOrderId)
        assertEquals(90.0, p.stop!!, 0.0); assertEquals(130.0, p.target!!, 0.0)
    }

    @Test fun onIntradayAndStopOnlyProtectionsAreUnchanged() {
        DayOco.resetForTest(on = true)
        kite.position(sym, 0, 0.0); kite.position(sym, 75, 100.0, product = "MIS")
        protect(product = "MIS")
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        kite.position(sym, 0, 0.0, product = "MIS"); kite.position(sym, 75, 100.0)
        val before = kite.placed.size
        protect(target = null)
        assertEquals("a stop alone stays an SL order", listOf("SL"), kite.placed.drop(before).map { it.form["order_type"] })
        assertTrue(gttPosts.isEmpty())
    }

    @Test fun aGttZerodhaWouldRefuseFallsBackToTheTwoOrdersAndSaysSo() {
        DayOco.resetForTest(on = true)
        kite.quote("NFO:$sym", 90.1, 90.05, 90.15)                 // the stop within 0.25% of the price: Zerodha refuses that GTT
        val said = protect()
        assertTrue(said, said.contains("two orders"))
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertTrue(kite.gtts.isEmpty())
        assertNull(one().gttId)
    }

    @Test fun nothingHeldYetFallsBackToTheTwoOrders() {
        DayOco.resetForTest(on = true)
        kite.flat()                                               // the position book trails the entry's fill
        protect()
        assertTrue(kite.gtts.isEmpty())
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
    }

    // ---- the point: one fills, the other is gone at Zerodha, with the phone off -------------------------------------------------

    @Test fun offWithThePhoneDeadTheTargetStillRestsAfterTheStopFilled() {
        protect()
        kite.fill(one().stopOrderId!!)                            // the stop fills; no look follows (the phone is off)
        assertTrue("the target order still rests: it could fill later into a short", kite.orders.values.any { it.working && it.side == "SELL" })
    }

    @Test fun onWithThePhoneDeadTheTargetIsGoneOnceTheStopFired() {
        DayOco.resetForTest(on = true)
        protect()
        val id = one().gttId!!
        kite.quote("NFO:$sym", 88.0, 87.95, 88.05)
        val oid = kite.triggerGtt(id, leg = 0)                     // Zerodha fires the stop leg; no look follows (the phone is off)
        assertEquals("COMPLETE", kite.order(oid).status)
        assertEquals(0, kite.positions["NFO:$sym:NRML"]?.first)
        assertTrue("nothing else rests to sell", kite.orders.values.none { it.working })
        assertFalse("the GTT is over (its target leg too)", kite.gtts.getValue(id).getString("status") == "active")
        // The phone back: the protection ends, nothing more is placed.
        session(10, 5)
        kite.requests.clear()
        tick()
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertTrue("no new orders or GTTs", writes().none { it.method == "POST" })
        assertTrue(alerted("its GTT fired"))
    }

    @Test fun firedButItsLimitNotFilledIsKeptAndSaidThenEndsOnceFilled() {
        DayOco.resetForTest(on = true)
        protect()
        val id = one().gttId!!
        kite.quote("NFO:$sym", 84.0, 83.95, 84.05)                 // gapped under the stop leg's limit (90 less 5%): rests unfilled
        val oid = kite.triggerGtt(id, leg = 0)
        assertEquals("OPEN", kite.order(oid).status)
        session(10, 5)
        tick()
        assertEquals("kept while its sell rests", 1, runBlocking { Protections.active() }.size)
        assertTrue(alerted("rests at Zerodha unfilled"))
        assertTrue("nothing new placed beside it", kite.placed.isEmpty())
        kite.fill(oid, 85.5)
        tick()
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun firedAndItsOrderCancelledUnfilledIsSaidPlainly() {
        DayOco.resetForTest(on = true)
        protect()
        val id = one().gttId!!
        kite.quote("NFO:$sym", 84.0, 83.95, 84.05)
        val oid = kite.triggerGtt(id, leg = 0)
        kite.order(oid).status = "CANCELLED"
        session(10, 5)
        tick()
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertTrue(alerted("did not fill"))
    }

    // ---- the open ---------------------------------------------------------------------------------------------------------------

    /** The close: Zerodha cancels the DAY exits (the clock at 15:45, the orders still in its book). */
    private fun theClose() {
        val p = one()
        session(15, 45)
        listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { kite.order(it).status = "CANCELLED" }
    }

    @Test fun onTheOvernightGttIsKeptAtTheOpenWithNothingDeletedOrPlaced() {
        protect()
        theClose()
        tick()
        val id = one().gttId!!
        assertEquals("the overnight GTT, two legs", 2, triggers(id).size)
        DayOco.resetForTest(on = true)
        kite.orders.clear()
        session(9, 20, nth = 2)
        kite.requests.clear()
        tick()
        assertTrue("nothing deleted, nothing placed", writes().isEmpty())
        val p = one()
        assertEquals(id, p.gttId)
        assertEquals(Protections.DAY_GTT, p.note)
        assertNull(p.stopOrderId)
        assertTrue(alerted("stay at Zerodha as one GTT today"))
        // Later looks that day: nothing more sent.
        session(9, 30, nth = 2)
        kite.requests.clear()
        tick()
        assertTrue(writes().isEmpty())
    }

    @Test fun offTheOvernightGttStillGivesWayToTheDaysOrdersAsBefore() {
        protect()
        theClose()
        tick()
        kite.orders.clear()
        session(9, 20, nth = 2)
        kite.requests.clear()
        tick()
        assertEquals("DELETE", writes().first().method)
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertTrue(kite.gtts.isEmpty())
    }

    @Test fun onWithNoOvernightGttTheOpenPlacesOneGttNotTwoOrders() {
        protect()
        theClose()                                                // the phone was off at the close: no overnight GTT
        DayOco.resetForTest(on = true)
        kite.orders.clear()
        session(9, 20, nth = 2)
        kite.requests.clear()
        tick()
        assertTrue(kite.placed.isEmpty())
        assertEquals(1, gttPosts.size)
        val p = one()
        assertEquals(Protections.DAY_GTT, p.note)
        assertEquals(listOf(90.0, 130.0), triggers(p.gttId!!))
    }

    // ---- during the day ---------------------------------------------------------------------------------------------------------

    @Test fun aTrailingStopMovesTheGttInPlaceNeverDeletedAndPlacedAgain() {
        DayOco.resetForTest(on = true)
        protect(trail = 10.0)
        val id = one().gttId!!
        session(10, 3)
        kite.quote("NFO:$sym", 110.0, 109.95, 110.05)
        kite.requests.clear()
        tick()
        assertEquals(1, gttPuts.size)
        assertTrue(gttDeletes.isEmpty()); assertTrue(gttPosts.isEmpty())
        assertEquals(listOf(100.0, 130.0), triggers(id))
        assertEquals(100.0, one().stop!!, 0.0)
        // The price back down: a trailing stop never loosens.
        kite.quote("NFO:$sym", 104.0, 103.95, 104.05)
        kite.requests.clear()
        tick()
        assertTrue(gttPuts.isEmpty())
        assertEquals(listOf(100.0, 130.0), triggers(id))
    }

    @Test fun aStreamLookNeverReadsTheGttList() {
        DayOco.resetForTest(on = true)
        protect(trail = 10.0)
        session(10, 3)
        kite.requests.clear()
        runBlocking { Protections.tick(fast = true, stream = true) }
        assertTrue(kite.requests.none { it.path == "/gtt/triggers" })
        assertEquals(1, runBlocking { Protections.active() }.size)
    }

    @Test fun theFirstMinuteNeverJudgesThePositionsRead() {
        DayOco.resetForTest(on = true)
        protect()
        kite.flat()                                               // the positions read trailing in the protection's first minute
        tick()
        assertEquals("kept, its GTT untouched", 1, runBlocking { Protections.active() }.size)
        assertTrue(gttDeletes.isEmpty())
    }

    @Test fun thePositionClosedByHandDeletesTheGttConfirmed() {
        DayOco.resetForTest(on = true)
        protect()
        session(10, 3)
        kite.flat()
        tick()
        assertEquals(1, gttDeletes.size)
        assertTrue(kite.gtts.isEmpty())
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    @Test fun lessHeldShrinksTheGttToWhatIsHeld() {
        DayOco.resetForTest(on = true)
        kite.position(sym, 150, 100.0)
        protect(qty = 150)
        val id = one().gttId!!
        assertEquals(150, legQty(id))
        session(10, 3)
        kite.position(sym, 75, 100.0)                              // half sold by hand on Kite's site
        tick()
        assertEquals(75, legQty(id))
        assertEquals(listOf(90.0, 130.0), triggers(id))
        assertEquals(75, one().qty)
        assertTrue(gttDeletes.isEmpty())
    }

    @Test fun switchedOffInMarketHoursTheGttGoesFirstThenTheTwoOrders() {
        DayOco.resetForTest(on = true)
        protect()
        session(10, 3)
        DayOco.setOn(false)
        kite.requests.clear()
        tick()
        val w = writes()
        assertEquals("DELETE", w.first().method)
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertTrue(kite.gtts.isEmpty())
        val p = one()
        assertNull(p.gttId)
        assertNotNull(p.stopOrderId)
        assertTrue(p.note != Protections.DAY_GTT)
        assertTrue(alerted("rest at Zerodha as two orders again"))
    }

    @Test fun switchedOffAfterTheCloseTheGttStaysOvernight() {
        DayOco.resetForTest(on = true)
        protect()
        session(16, 0)
        DayOco.setOn(false)
        kite.requests.clear()
        tick()
        assertTrue(writes().isEmpty())
        assertEquals(Protections.DAY_GTT, one().note)
    }

    @Test fun aGttDeletedOnKitesSiteIsSaidPlainlyAndTheProtectionEnds() {
        DayOco.resetForTest(on = true)
        protect()
        session(10, 3)
        kite.gtts.clear()
        tick()
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertTrue(alerted("NOT protected"))
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun aGttReadThatFailsChangesNothing() {
        DayOco.resetForTest(on = true)
        protect()
        session(10, 3)
        kite.gttReadFailures = 20
        kite.requests.clear()
        tick()
        assertEquals(1, runBlocking { Protections.active() }.size)
        assertTrue(writes().isEmpty())
    }

    @Test fun removingItDeletesTheGtt() {
        DayOco.resetForTest(on = true)
        protect()
        val said = runBlocking { Protections.remove(one().id) }
        assertTrue(said, said.startsWith("Protection removed"))
        assertTrue(kite.gtts.isEmpty())
    }

    @Test fun aStopMoveModifiesTheGttsStopLegAndAnAutomaticMoveNeverLoosensIt() {
        DayOco.resetForTest(on = true)
        protect()
        val p = one()
        session(10, 3)
        assertNull(runBlocking { Protections.moveStop(p.id, 95.0) })
        assertEquals(listOf(95.0, 130.0), triggers(p.gttId!!))
        assertEquals(95.0, one().stop!!, 0.0)
        val no = runBlocking { Protections.moveStopAt(p.id, 92.0) }
        assertNotNull(no.why)
        assertEquals(95.0, no.standing!!, 0.0)
        assertEquals(listOf(95.0, 130.0), triggers(p.gttId!!))
    }
    // ---- the expiry square-off ------------------------------------------------------------------------------------------------

    /** Expiry day, 15:06: the square-off takes the protection's GTT out (confirmed) before it sells, so it can never fire after. */
    @Test fun theExpirySquareOffTakesTheDayGttOutBeforeItSells() {
        com.optionslab.app.testing.AutomationSupport.liveSettings()
        DayOco.resetForTest(on = true)
        val exp = "NIFTY26OCT24600CE"
        kite.instruments += FakeKite.Ins(12_345_679, exp, "NIFTY", Market.today(), 24_600.0, "CE", 75)
        runBlocking { Broker.instruments() }
        kite.position(exp, 75, 100.0)
        kite.quote("NFO:$exp", 100.0, 99.95, 100.05)
        runBlocking { Protections.protectLive(exp, "NFO", "NRML", 75, 100.0, 90.0, null, 130.0) }
        val gid = runBlocking { Protections.active() }.single { it.symbol == exp }.gttId!!
        assertEquals(listOf(gid), Protections.dayGttHint["NFO:$exp"])
        session(15, 6)
        ExpirySquareOff.testNow = Market.now()
        ExpirySquareOff.armOwns = { false }
        com.optionslab.app.testing.AutomationSupport.resetExpiryTold()
        try {
            kite.requests.clear()
            repeat(2) { runBlocking { ExpirySquareOff.maybeRun(context, AppSettings.load()) } }
            val w = writes()
            val del = w.indexOfFirst { it.method == "DELETE" && it.path.endsWith("/$gid") }
            val sell = w.indexOfFirst { it.method == "POST" && it.path == "/orders/regular" }
            assertTrue("the GTT out: $w", del >= 0)
            assertTrue("then the sell: $w", sell > del)
            assertFalse(kite.gtts.containsKey(gid))
            assertEquals(0, kite.positions["NFO:$exp:NRML"]?.first)
            assertEquals("the expiring one sold once, the other untouched", 75, kite.placed.filter { it.form["tradingsymbol"] == exp }.sumOf { it.form["quantity"]!!.toInt() })
            com.optionslab.app.testing.Background.clearAlerts()
            session(15, 8)
            tick()
            assertTrue(runBlocking { Protections.active() }.none { it.symbol == exp })
            assertFalse("closed, so never said unprotected", alerted("NOT protected"))
        } finally {
            ExpirySquareOff.testNow = null
            ExpirySquareOff.armOwns = { ArmLive.ownerOf(it) != null }
        }
    }

    @Test fun withoutADayGttTheExpiryExitsReadNoGtts() {
        assertTrue(runBlocking { Protections.dayGttsOut("NFO", sym, "test") })
        assertTrue(kite.requests.none { it.path.startsWith("/gtt/") })
    }
    // ---- the review's findings (21 Oct) -----------------------------------------------------------------------------------------

    /** A lost answer: the GTT may exist, so nothing else is placed; the next look finds it and keeps it (never a second one). */
    @Test fun aLostAnswerPlacesNothingElseAndTheNextLookAdoptsTheGtt() {
        DayOco.resetForTest(on = true)
        kite.gttRepliesLost = 1
        kite.gttReadFailures = 20                                  // and the look for it fails too
        val said = protect()
        assertTrue(said, said.contains("answer to the GTT was lost"))
        assertTrue("no DAY orders beside a GTT that may exist", kite.placed.isEmpty())
        assertEquals("the GTT was made at Zerodha", 1, kite.gtts.size)
        val p = one()
        assertNull(p.gttId); assertEquals(Protections.DAY_GTT, p.note)
        kite.gttReadFailures = 0
        session(10, 3)
        kite.requests.clear()
        tick()
        assertTrue("adopted, none placed", gttPosts.isEmpty())
        assertEquals(kite.gtts.keys.single(), one().gttId)
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun aLostAnswerAtTheOpenPlacesNothingElse() {
        protect()
        theClose()                                                // the phone was off at the close: no overnight GTT
        DayOco.resetForTest(on = true)
        kite.orders.clear()
        session(9, 20, nth = 2)
        kite.gttRepliesLost = 1
        kite.gttReadFailures = 20
        kite.requests.clear()
        tick()
        assertTrue("no DAY orders beside it", kite.placed.isEmpty())
        assertEquals(1, kite.gtts.size)
        assertEquals(Protections.DAY_GTT, one().note)
        kite.gttReadFailures = 0
        session(9, 22, nth = 2)
        tick()
        assertEquals(kite.gtts.keys.single(), one().gttId)
        assertEquals(1, gttPosts.size)
    }

    /** After a restart the switch reads off until the settings are read: a day GTT is never taken out for that. */
    @Test fun theUnreadSwitchAfterARestartNeverTakesTheGttOut() {
        DayOco.resetForTest(on = true)
        protect()
        DayOco.resetForTest(on = false, loaded = false)
        session(10, 3)
        kite.requests.clear()
        tick()
        assertTrue(writes().isEmpty())
        assertEquals(Protections.DAY_GTT, one().note)
        assertNotNull(one().gttId)
    }

    /** A part closed by hand with the price at the stop: the GTT cannot be reduced, so it goes (confirmed) for two orders sized to what is held. */
    @Test fun aGttThatCannotBeReducedIsReplacedByTwoOrdersForWhatIsHeld() {
        DayOco.resetForTest(on = true)
        kite.position(sym, 150, 100.0)
        protect(qty = 150)
        session(10, 3)
        kite.position(sym, 75, 100.0)
        kite.quote("NFO:$sym", 90.1, 90.05, 90.15)                 // within 0.25% of the stop: Zerodha refuses the reduced GTT
        kite.requests.clear()
        tick()
        val w = writes()
        assertEquals("the GTT out first: $w", "DELETE", w.first { it.method != "PUT" }.method)
        assertTrue(kite.gtts.isEmpty())
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertTrue(kite.placed.all { it.form["quantity"] == "75" })
        assertEquals(75, one().qty)
    }

    /** Fired in an earlier session, its order not in today's book: nothing rests, said plainly, finished (never "rests unfilled" forever). */
    @Test fun aFiredGttWhoseOrderEndedWithItsSessionIsSaidPlainly() {
        DayOco.resetForTest(on = true)
        protect()
        val id = one().gttId!!
        kite.quote("NFO:$sym", 84.0, 83.95, 84.05)
        val oid = kite.triggerGtt(id, leg = 0)
        kite.orders.remove(oid)                                    // the next session: yesterday's order is not in today's book
        session(10, 0, nth = 2)
        tick()
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertTrue(alerted("did not fill"))
    }

    @Test fun aFiredGttThatLeftAShortIsSaidPlainlyNotAsClosed() {
        DayOco.resetForTest(on = true)
        protect()
        val id = one().gttId!!
        kite.quote("NFO:$sym", 88.0, 87.95, 88.05)
        kite.triggerGtt(id, leg = 0)
        kite.position(sym, -75, 88.0)                              // another sell filled beside it
        session(10, 5)
        tick()
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertTrue(alerted("other way round"))
        assertFalse(alerted("the position is closed"))
    }

    @Test fun aFiredGttThatSoldPartIsSaidAsPartLeft() {
        DayOco.resetForTest(on = true)
        kite.position(sym, 150, 100.0)
        protect(qty = 150)
        val id = one().gttId!!
        // Another exit covered half since: the GTT covers 150 but its sell filled only 75 before Zerodha cancelled the rest.
        kite.quote("NFO:$sym", 84.0, 83.95, 84.05)
        val oid = kite.triggerGtt(id, leg = 0)
        kite.fillPart(oid, 75, 85.5)
        kite.order(oid).status = "CANCELLED"
        session(10, 5)
        tick()
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertTrue(alerted("its GTT fired and sold 75"))
    }

    /** The expiry exits: a GTT that fired a moment before (its sell working, not in the pass's read) is cancelled first. */
    @Test fun theExpiryExitsCancelAJustFiredGttsWorkingSellFirst() {
        DayOco.resetForTest(on = true)
        protect()
        val id = one().gttId!!
        kite.quote("NFO:$sym", 84.0, 83.95, 84.05)
        val oid = kite.triggerGtt(id, leg = 0)
        assertEquals("OPEN", kite.order(oid).status)
        assertFalse("the exit waits for its next pass", runBlocking { Protections.dayGttsOut("NFO", sym, "test") })
        assertEquals("CANCELLED", kite.order(oid).status)
        assertTrue(runBlocking { Protections.dayGttsOut("NFO", sym, "test") })
    }
}
