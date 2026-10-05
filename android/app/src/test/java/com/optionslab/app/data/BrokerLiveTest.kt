package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Outcome
import com.optionslab.app.testing.FakeKite.Reply
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * The app's real Zerodha HTTP code ([Broker]) against [FakeKite]: exactly what goes over the wire,
 * and how every kind of reply (refusal, throttling, lost reply, session end) is handled.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class BrokerLiveTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500PE"
    private fun order(qty: Int = 75, type: String = "LIMIT", price: Double? = 120.0, trigger: Double? = null) =
        Kite.Order(sym, Kite.Side.BUY, qty, 75, "NRML", type, price, 0.05, "NFO", "iratest", triggerPrice = trigger)

    @Before fun up() { kite = FakeKite(); kite.login() }
    @After fun down() { kite.close(); assertEquals("a test reached the internet", emptyList<String>(), NetworkGuard.blocked.toList()) }

    @Test fun placeSendsExactlyTheOrderFormWithAuth() = runBlocking {
        val id = Broker.placeOrder(order())
        val r = kite.placed.single()
        assertEquals("token testkey:test-token-not-real", r.auth)
        assertEquals(mapOf("tradingsymbol" to sym, "exchange" to "NFO", "transaction_type" to "BUY", "order_type" to "LIMIT",
            "quantity" to "75", "product" to "NRML", "price" to "120.00", "validity" to "DAY", "tag" to "iratest"), r.form)
        assertEquals("COMPLETE", Broker.awaitOrder(id, 5_000).status)
        assertEquals(1, Broker.sentToday())
    }

    @Test fun stopOrdersCarryTriggerAndMarketOrdersProtection() = runBlocking {
        Broker.placeOrder(order(type = "SL", price = 95.0, trigger = 100.0), exit = true)
        Broker.placeOrder(order(type = "SL-M", price = null, trigger = 100.0), exit = true)
        val (sl, slm) = kite.placed
        assertEquals("95.00", sl.form["price"]); assertEquals("100.00", sl.form["trigger_price"])
        assertNull(slm.form["price"]); assertEquals("-1", slm.form["market_protection"])
    }

    @Test fun nothingIsSentWhenNotLoggedIn() = runBlocking {
        com.optionslab.app.security.SecurePrefs.put("kite.accessToken", null)
        try { Broker.placeOrder(order()); fail() } catch (e: Broker.NotLoggedIn) { assertTrue(Broker.definite(e)) }
        assertTrue(kite.requests.isEmpty())
    }

    @Test fun anRmsRejectionIsReportedWithKitesMessage() = runBlocking {
        kite.nextPlace(outcome = Outcome.REJECT, message = "RMS:Margin Exceeds, Required:1,20,000.00, Available:40,000.00")
        val f = Broker.awaitOrder(Broker.placeOrder(order()), 5_000)
        assertEquals("REJECTED", f.status)
        assertTrue(f.message.startsWith("RMS:Margin Exceeds"))
    }

    @Test fun aFreezeQuantityRefusalIsDefinite() = runBlocking {
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Quantity exceeds the freeze limit of 1800.")
        try { Broker.placeOrder(order(qty = 1875)); fail() } catch (e: Broker.KiteError) {
            assertEquals("InputException", e.type); assertTrue(Broker.definite(e)); assertTrue(e.message!!.contains("freeze"))
        }
        assertTrue(kite.orders.isEmpty())
    }

    @Test fun kitesNetworkExceptionIsNotDefinite() = runBlocking {
        kite.nextPlace(reply = Reply.NETWORK_EXCEPTION)
        try { Broker.placeOrder(order()); fail() } catch (e: Exception) { assertFalse(Broker.definite(e)) }
    }

    @Test fun aLostReplyIsFoundInTheOrderBook() = runBlocking {
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        val o = order()
        val e = try { Broker.placeOrder(o); null } catch (e: Exception) { e }
        assertTrue("a dropped connection must surface as an error", e != null)
        assertFalse("a lost reply is not a refusal", Broker.definite(e!!))
        assertEquals("the POST must not be repeated by the HTTP stack", 1, kite.placed.size)
        val found = Broker.findRecentRetrying(o, emptyList(), tries = 2, waitMs = 10)
        assertEquals(kite.orders.keys.single(), found)
        assertNull("an order already known is never adopted twice", Broker.findRecentRetrying(o, listOf(found!!), tries = 1, waitMs = 10))
    }

    @Test fun a500AfterAcceptIsNotDefiniteEither() = runBlocking {
        kite.nextPlace(reply = Reply.ERROR_500_AFTER_ACCEPT)
        try { Broker.placeOrder(order()); fail() } catch (e: Exception) { assertFalse(Broker.definite(e)) }
        assertEquals(kite.orders.keys.single(), Broker.findRecentRetrying(order(), emptyList(), tries = 1, waitMs = 10))
    }

    @Test fun throttlingIsRetriedThenSucceeds() = runBlocking {
        kite.throttle = 1
        Broker.placeOrder(order())
        assertEquals(2, kite.placed.size)
        assertEquals("placed once", 1, kite.orders.size)
    }

    @Test fun anExpiredSessionEndsTheLogin() = runBlocking {
        kite.sessionExpired = true
        try { Broker.positions(); fail() } catch (e: Broker.KiteError) { assertEquals("TokenException", e.type) }
        assertFalse(Broker.loggedIn)
        try { Broker.placeOrder(order()); fail() } catch (_: Broker.NotLoggedIn) {}
        assertTrue("nothing was placed after the session ended", kite.placed.isEmpty())
    }

    @Test fun oneRefusedCallDoesNotEndTheDaysLogin() = runBlocking {
        kite.tokenRefused += "/portfolio/positions"
        try { Broker.positions(); fail() } catch (e: Broker.KiteError) { assertEquals("TokenException", e.type) }
        assertTrue("the profile still answers, so the session is kept", Broker.loggedIn)
    }

    @Test fun partialAndLateFills() = runBlocking {
        kite.nextPlace(outcome = Outcome.PARTIAL, partialQty = 75)
        val partial = Broker.awaitOrder(Broker.placeOrder(order(qty = 150)), 1_500)
        assertEquals("OPEN", partial.status); assertEquals(75, partial.filled)
        kite.nextPlace(FakeKite.Scenario(outcome = Outcome.FILL_AFTER_POLLS, polls = 2))
        val late = Broker.awaitOrder(Broker.placeOrder(order()), 10_000)
        assertEquals("COMPLETE", late.status); assertEquals(75, late.filled)
    }

    @Test fun cancelAndModifyUseTheirOwnVerbsAndFields() = runBlocking {
        kite.nextPlace(outcome = Outcome.OPEN)
        val id = Broker.placeOrder(order())
        val row = Broker.orders().single()
        Broker.modify(row, 150, "LIMIT", 118.5, null)
        val put = kite.requests.last()
        assertEquals("PUT", put.method); assertEquals("/orders/regular/$id", put.path)
        assertEquals(mapOf("quantity" to "150", "order_type" to "LIMIT", "price" to "118.50", "validity" to "DAY"), put.form)
        Broker.cancel(id)
        assertEquals("DELETE" to "/orders/regular/$id", kite.requests.last().let { it.method to it.path })
        assertEquals("CANCELLED", Broker.orderState(id)!!.status)
    }

    @Test fun accountReadsParseKitesShapes() = runBlocking {
        kite.position(sym, -75, 110.0); kite.quote("NFO:$sym", 100.0, 99.9, 100.1)
        val p = Broker.positions().single()
        assertEquals(-75, p.qty); assertEquals(750.0, p.pnl, 1e-9)
        assertEquals(500_000.0, Broker.funds().available, 0.0)
        val q = Broker.quotes(listOf("NFO:$sym")).getValue("NFO:$sym")
        assertEquals(99.9, q.bid!!, 0.0); assertEquals(100.1, q.ask!!, 0.0)
        val m = Broker.basketMargin(listOf(order()))
        assertEquals(40_000.0, m.required, 0.0); assertFalse(m.short)
        assertTrue("reads only", kite.writes.isEmpty())
    }

    @Test fun gttCreateListDelete() = runBlocking {
        val g = Kite.Gtt("two-leg", "NFO", sym, 100.0, listOf(80.0, 130.0),
            listOf(order().copy(side = Kite.Side.SELL, price = 79.0), order().copy(side = Kite.Side.SELL, price = 129.0)))
        val id = Broker.placeGtt(g)
        val post = kite.requests.last()
        assertEquals("two-leg", post.form["type"])
        assertTrue(post.form.getValue("condition").contains("\"trigger_values\":[80.00,130.00]"))
        assertEquals(listOf(80.0, 130.0), Broker.gtts().single().triggers)
        Broker.deleteGtt(id)
        assertEquals("DELETE" to "/gtt/triggers/$id", kite.requests.last().let { it.method to it.path })
        assertTrue(Broker.gtts().isEmpty())
    }

    @Test fun theOrderWatchsIndexLevelIsTheQuoteAloneNoDayCandles() = runBlocking {
        // Battery, round 16: the watch reads only the last price and the change from the open.
        kite.quote("NSE:NIFTY BANK", 55_000.0)
        repeat(3) {
            val q = Broker.indexQuote("BANKNIFTY", spark = false)
            assertEquals(55_000.0, q!!.last, 0.0)
        }
        val candles = { kite.requests.count { it.path.startsWith("/instruments/historical/") } }
        assertEquals("one quote a read", 3, kite.requests.count { it.path == "/quote" })
        assertEquals("and never the day's candles", 0, candles())
        // A screen that draws the spark still reads the day's candles with its quote (once a minute, as before).
        Broker.indexQuote("BANKNIFTY")
        assertEquals(4, kite.requests.count { it.path == "/quote" })
        assertTrue(candles() <= 1)
    }
}
