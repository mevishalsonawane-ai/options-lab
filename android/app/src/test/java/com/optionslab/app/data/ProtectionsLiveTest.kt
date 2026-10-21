package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Reply
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/** Live stop / target protection against the fake Kite: the orders sent, OCO, lost replies, refusals. */
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProtectionsLiveTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500CE"

    @Before fun up() {
        kite = FakeKite()
        kite.instruments += FakeKite.Ins(12_345_678, sym, "NIFTY", LocalDate.now().plusDays(20), 24_500.0, "CE", 75)
        kite.position(sym, 75, 100.0)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
    }

    @After fun down() { Market.testClock = null; kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    /** The clock at [h]:[m] IST on the next trading day after today (still ticking). */
    private fun nextSession(h: Int, m: Int, nth: Int = 1) {
        var d = LocalDate.now(com.optionslab.engine.IST)
        repeat(nth) { d = d.plusDays(1); while (!Market.isTradingDay(d)) d = d.plusDays(1) }
        val at = d.atTime(h, m).atZone(com.optionslab.engine.IST).toInstant()
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), at))
    }

    private fun protect(stop: Double? = 90.0, target: Double? = 130.0) = runBlocking {
        Protections.protectLive(sym, "NFO", "NRML", 75, 100.0, stop, null, target)
    }

    @Test fun refusedOutsideLiveModeAndNothingSent() {
        kite.login(live = false)
        assertEquals("Switch to Live with the badge at the top first.", protect())
        assertTrue(kite.requests.isEmpty())
    }

    /** 12 Oct (SILVERM at 4243.50 "protected" with a target of 3900): a target under the price paid is refused, nothing sent. */
    @Test fun aTargetUnderThePricePaidIsRefusedAndNothingIsSent() {
        kite.login()
        // Bought at 100, now 80: 90 is above the price but under what was paid - a loss, not a target.
        val msg = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 75, 80.0, null, null, 90.0) }
        assertTrue(msg, msg.startsWith("Not protected: The target must be above the 100.00 you paid"))
        assertTrue(kite.placed.isEmpty())
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    /** 12 Oct, 10:44 ("Protected SILVERM...: " with nothing after it): a request that sets nothing never replaces a stop. */
    @Test fun aProtectionThatWouldSetNothingIsRefusedAndTheOldStopStays() {
        kite.login()
        protect(stop = 90.0, target = null)
        val before = runBlocking { Protections.active() }.single()
        // A trail longer than the price: the stop would be under zero.
        val msg = runBlocking { Protections.protectLive(sym, "NFO", "NRML", 75, 100.0, null, 150.0, null) }
        assertTrue(msg, msg.startsWith("Not protected:"))
        val paper = runBlocking { Protections.protectPaper(sym, "NRML", 75, 100.0, 0.0, null, null) }
        assertEquals("Not protected: The stop must be above zero.", paper)
        assertEquals(before, runBlocking { Protections.active() }.single())
        assertEquals("only the first stop was ever sent", 1, kite.placed.size)
    }

    @Test fun placesAStopWithALimitAndATarget() {
        kite.login()
        val msg = protect()
        assertTrue(msg, msg.startsWith("Protected $sym at Zerodha"))
        val (stop, target) = kite.placed
        assertEquals(mapOf("tradingsymbol" to sym, "exchange" to "NFO", "transaction_type" to "SELL", "order_type" to "SL",
            "quantity" to "75", "product" to "NRML", "price" to "81.00", "trigger_price" to "90.00", "validity" to "DAY", "tag" to "iraprotect"), stop.form)
        assertEquals("LIMIT", target.form["order_type"]); assertEquals("130.00", target.form["price"]); assertEquals("SELL", target.form["transaction_type"])
        val item = runBlocking { Protections.active() }.single()
        assertEquals(90.0, item.stop!!, 0.0); assertEquals(130.0, item.target!!, 0.0)
    }

    /**
     * 20 Oct, review: a carried (NRML) position's stop and target are DAY orders that end with their session. The next morning
     * the protection was marked finished and nothing watched the position. Now they are placed again at the same levels.
     */
    @Test fun aCarriedPositionsStopAndTargetArePlacedAgainNextSession() {
        kite.login()
        protect()
        val before = runBlocking { Protections.active() }.single()
        kite.orders.clear()                                           // yesterday's DAY orders are gone from today's book
        // Before the open: nothing sent yet (Zerodha would refuse), the protection kept.
        nextSession(9, 0)
        runBlocking { Protections.tick() }
        // In the session: placed again, the same levels, for what is still held.
        nextSession(9, 20)
        kite.requests.clear()
        runBlocking { Protections.tick() }
        val (stop, target) = kite.placed
        assertEquals("SL", stop.form["order_type"]); assertEquals("90.00", stop.form["trigger_price"]); assertEquals("75", stop.form["quantity"])
        assertEquals("LIMIT", target.form["order_type"]); assertEquals("130.00", target.form["price"])
        val now = runBlocking { Protections.active() }.single()
        assertEquals(before.stop, now.stop); assertEquals(before.target, now.target)
        assertTrue("new orders tracked", now.stopOrderId != before.stopOrderId && now.stopOrderId != null)
        // The next look finds them working: nothing more sent.
        kite.requests.clear()
        runBlocking { Protections.tick() }
        assertTrue(kite.placed.isEmpty())
    }

    /** At the close Zerodha cancels the DAY exits: the protection is kept (not ended) and placed again at the next open. */
    @Test fun exitsCancelledAtTheCloseArePlacedAgainAtTheNextOpen() {
        kite.login()
        protect()
        val before = runBlocking { Protections.active() }.single()
        nextSession(15, 45)
        listOfNotNull(before.stopOrderId, before.targetOrderId).forEach { kite.order(it).status = "CANCELLED" }
        runBlocking { Protections.tick() }
        assertEquals("kept overnight", 1, runBlocking { Protections.active() }.size)
        kite.orders.clear()
        nextSession(9, 20, nth = 2)
        kite.requests.clear()
        runBlocking { Protections.tick() }
        assertEquals(listOf("SL", "LIMIT"), kite.placed.map { it.form["order_type"] })
        assertEquals("90.00", kite.placed.first().form["trigger_price"])
    }

    /** An intraday (MIS) protection's exits cancelled: said and ended, as before (nothing carried). */
    @Test fun anIntradayProtectionCancelledIsNotPlacedAgain() {
        kite.login()
        kite.position(sym, 0, 0.0); kite.position(sym, 75, 100.0, product = "MIS")
        runBlocking { Protections.protectLive(sym, "NFO", "MIS", 75, 100.0, 90.0, null, null) }
        val before = runBlocking { Protections.active() }.single()
        nextSession(15, 45)
        kite.order(before.stopOrderId!!).status = "CANCELLED"
        runBlocking { Protections.tick() }
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    /** Second review: more held now (lots added since) - placed again for what the protection covered, never more. */
    @Test fun placedAgainForNoMoreThanItCovered() {
        kite.login()
        protect()
        kite.orders.clear()
        kite.position(sym, 225, 100.0)
        nextSession(9, 20)
        kite.requests.clear()
        runBlocking { Protections.tick() }
        assertEquals(listOf("75", "75"), kite.placed.map { it.form["quantity"] })
    }

    /** Second review: an exit already resting at the open (Boss's own after-market order) is subtracted: nothing doubled. */
    @Test fun anExitAlreadyRestingIsNotDoubled() {
        kite.login()
        protect()
        kite.orders.clear()
        nextSession(9, 20)
        runBlocking { Broker.placeOrder(com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, 75, 75, "NRML", "LIMIT", 150.0,
            0.05, "NFO", "manual"), exit = true) }
        kite.requests.clear()
        runBlocking { Protections.tick() }
        assertTrue("nothing more placed", kite.placed.isEmpty())
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    /** Second review: the price opened through the stop - Zerodha refuses the stop; said plainly, with the price. */
    @Test fun aGapThroughTheStopIsSaidPlainly() {
        kite.login()
        protect()
        kite.orders.clear()
        kite.quote("NFO:$sym", 80.0, 79.95, 80.05)
        nextSession(9, 20)
        kite.nextPlace(reply = FakeKite.Reply.INPUT_EXCEPTION, message = "Trigger price for stoploss sell orders should be lower than the last price.")
        kite.requests.clear()
        runBlocking { Protections.tick() }
        val said = com.optionslab.app.work.Alerts.queue.value.map { it.text }
        assertTrue(said.toString(), said.any { it.contains("already through its stop of 90.00") })
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    /** The position was closed overnight elsewhere: nothing is placed again, the protection ends. */
    @Test fun aCarriedProtectionWithNothingHeldIsNotPlacedAgain() {
        kite.login()
        protect()
        kite.orders.clear()
        kite.flat()
        nextSession(9, 20)
        kite.requests.clear()
        runBlocking { Protections.tick() }
        assertTrue(kite.placed.isEmpty())
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    @Test fun whenTheStopFillsTheTargetIsCancelled() {
        kite.login()
        protect()
        val item = runBlocking { Protections.active() }.single()
        kite.fill(item.stopOrderId!!, 89.0)
        runBlocking { Protections.tick() }
        assertTrue(kite.requests.any { it.method == "DELETE" && it.path == "/orders/regular/${item.targetOrderId}" })
        assertEquals("CANCELLED", kite.order(item.targetOrderId!!).status)
        assertTrue("finished once the other exit is confirmed gone", runBlocking { Protections.active() }.isEmpty())
    }

    @Test fun aLostReplyOnTheStopIsAdoptedNotPlacedAgain() {
        kite.login()
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        val msg = protect()
        assertTrue(msg, msg.startsWith("Protected"))
        assertEquals("one stop and one target at Zerodha, no duplicate", 2, kite.orders.size)
        val item = runBlocking { Protections.active() }.single()
        assertEquals(kite.orders.keys.first(), item.stopOrderId)
    }

    @Test fun aRefusedTargetKeepsTheStopAndSaysSo() {
        kite.login()
        kite.nextPlace()                                                   // the stop
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Price exceeds the circuit limit.")
        val msg = protect()
        assertTrue(msg, msg.contains("The target was not placed (Price exceeds the circuit limit.); only the stop rests at Zerodha."))
        val item = runBlocking { Protections.active() }.single()
        assertNull(item.target); assertNull(item.targetOrderId)
        assertEquals("TRIGGER PENDING", kite.order(item.stopOrderId!!).status)
    }

    @Test fun removingCancelsBothExits() {
        kite.login()
        protect()
        val item = runBlocking { Protections.active() }.single()
        assertEquals("Protection removed from $sym.", runBlocking { Protections.remove(item.id) })
        assertEquals(setOf(item.stopOrderId, item.targetOrderId),
            kite.requests.filter { it.method == "DELETE" }.map { it.path.substringAfterLast('/') }.toSet())
        assertTrue(runBlocking { Protections.active() }.isEmpty())
    }

    /** G1-4: an automatic move (AutoTrail) never loosens a stop, judged against the stop as it stands, not the caller's snapshot. */
    @Test fun anAutomaticMoveOnlyTightensTheStop() {
        kite.login()
        protect()
        val id = runBlocking { Protections.active() }.single().id
        val puts = { kite.requests.count { it.method == "PUT" } }
        // On the tick, rounded away from the price (down for a long), never up toward it.
        assertNull(runBlocking { Protections.moveStop(id, 92.03) })
        assertEquals(92.0, runBlocking { Protections.active() }.single().stop!!, 1e-9)
        val sent = puts()
        // Lower: refused, nothing sent to Zerodha.
        val lower = runBlocking { Protections.moveStop(id, 85.0) }
        assertTrue(lower ?: "moved", lower?.contains("only tightens") == true)
        assertEquals(sent, puts())
        assertEquals(92.0, runBlocking { Protections.active() }.single().stop!!, 1e-9)
        // The resting stop was raised to 96 at Zerodha meanwhile: a trail worked out from the older 92 must not pull it to 94.
        val stopId = runBlocking { Protections.active() }.single().stopOrderId!!
        val so = runBlocking { Broker.orders() }.first { it.id == stopId }
        runBlocking { Broker.modify(so, so.qty, "SL", 87.0, 96.0) }
        val stale = runBlocking { Protections.moveStop(id, 94.0) }
        assertTrue(stale ?: "moved", stale?.contains("only tightens") == true)
        assertEquals(96.0, runBlocking { Broker.orders() }.first { it.id == stopId }.trigger, 1e-9)
    }

    /**
     * Round 4: a refused automatic move says the stop that stands NOW (what Jarvis quotes: "Your stop stays at ..."), not the
     * caller's snapshot - the protection's own tighter stop, or Zerodha's tighter resting trigger.
     */
    @Test fun aRefusedMoveSaysTheStopThatStandsNow() {
        kite.login()
        protect()
        val id = runBlocking { Protections.active() }.single().id
        val moved = runBlocking { Protections.moveStopAt(id, 93.0) }
        assertNull(moved.why); assertEquals(93.0, moved.standing!!, 1e-9)
        // A caller with an older snapshot (90) asks for 91: refused, and the standing stop is 93.
        val own = runBlocking { Protections.moveStopAt(id, 91.0) }
        assertTrue(own.why ?: "moved", own.why?.contains("only tightens") == true)
        assertEquals(93.0, own.standing!!, 1e-9)
        // Zerodha's resting stop raised to 97 meanwhile: a move to 95 is refused and says 97.
        val stopId = runBlocking { Protections.active() }.single().stopOrderId!!
        val so = runBlocking { Broker.orders() }.first { it.id == stopId }
        runBlocking { Broker.modify(so, so.qty, "SL", 88.0, 97.0) }
        val zerodha = runBlocking { Protections.moveStopAt(id, 95.0) }
        assertTrue(zerodha.why ?: "moved", zerodha.why?.contains("at Zerodha is already at 97.00") == true)
        assertEquals(97.0, zerodha.standing!!, 1e-9)
    }
}
