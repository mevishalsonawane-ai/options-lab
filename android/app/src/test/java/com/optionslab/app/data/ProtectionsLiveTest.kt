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
import org.junit.Ignore
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

    @After fun down() { kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun protect(stop: Double? = 90.0, target: Double? = 130.0) = runBlocking {
        Protections.protectLive(sym, "NFO", "NRML", 75, 100.0, stop, null, target)
    }

    @Test fun refusedOutsideLiveModeAndNothingSent() {
        kite.login(live = false)
        assertEquals("Switch to Live with the badge at the top first.", protect())
        assertTrue(kite.requests.isEmpty())
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

    @Ignore("MONEY BUG: a dropped connection after the stop order's POST makes HttpURLConnection re-send it: Zerodha gets " +
        "TWO stop orders (3 orders instead of 2), one of them untracked and resting. Same cause and fix as " +
        "BrokerLiveTest.aLostReplyIsFoundInTheOrderBook (fixed-length streaming mode in Broker.call).")
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
}
