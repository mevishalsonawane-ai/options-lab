package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.Kite
import com.optionslab.engine.KiteTicks
import com.optionslab.engine.Right
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * Order speed (9 Oct): the event-driven checks ([FastPath]) and Zerodha's order updates on the stream, against the fakes.
 *  (i) a stream tick through a paper stop fills it at once, without the watch's 15-second pass;
 *  (ii) an order being waited on is answered by Zerodha's stream update, not by more reads;
 *  (iii) in Live with real orders on, the event-driven path sends nothing to Zerodha for a paper holding.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class FastPathTest : RobolectricTest() {
    private var kite: FakeKite? = null
    private var upstox: FakeUpstox? = null
    private val token = 12_345_678L

    @After fun down() {
        FastPath.resetForTest()
        KiteStream.stop()
        kite?.close()
        upstox?.close()
        assertEquals("a test reached the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun tick(last: Double, stampSec: Long = System.currentTimeMillis() / 1000) =
        KiteTicks.Tick(token, last, bid = last - 0.05, ask = last + 0.05, exchangeTime = stampSec, bidQty = 750, askQty = 750)

    /** A paper NIFTY 24500 PE bought at about 100 with its SELL SL-M stop resting at 80; the contract streams on Zerodha. */
    private fun paperHoldingWithAStop(live: Boolean): Pair<Paper.Contract, String> = runBlocking {
        val k = FakeKite().also { kite = it }
        k.login(live = live)
        if (!live) TradeFixtures.paperSettings()
        val u = FakeUpstox().also { upstox = it }
        TradeFixtures.seed(context, u)
        val expiry = TradeFixtures.nearExpiry
        k.instruments += FakeKite.Ins(token, "NIFTY26OCT24500PE", "NIFTY", expiry, 24_500.0, "PE", 75)
        Broker.instruments()
        val c = Paper.contractFor("NIFTY", expiry, 24_500.0, Right.PE) ?: throw AssertionError("contract not listed")
        // The stream prices the contract (fresh): the paper book reads it, never the candle feed.
        KiteStream.feedForTest(listOf(tick(100.0)))
        val buy = Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null)
        assertTrue(buy.message, buy.ok)
        val stop = Paper.place(c, "SELL", 1, "SL-M", "NRML", null, 80.0)
        assertTrue(stop.message, stop.ok)
        val id = stop.orderId ?: throw AssertionError("no stop order id")
        assertEquals("trigger pending", Paper.state.orders.first { it.orderId == id }.status)
        c to id
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(10) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun aTickThroughAPaperStopFillsItAtOnceWithoutTheWatchPass() {
        val (_, stopId) = paperHoldingWithAStop(live = false)
        FastPath.start(context)
        val t0 = System.currentTimeMillis()
        // The price falls through the stop on the stream: no watch pass runs in this test.
        KiteStream.feedForTest(listOf(tick(75.0)))
        awaitTrue("the stop to fill") { Paper.state.orders.firstOrNull { it.orderId == stopId }?.status == "complete" }
        val ms = System.currentTimeMillis() - t0
        println("LATENCY stream tick -> paper stop filled: $ms ms")
        assertTrue("filled in $ms ms, well inside the 15-second pass", ms < 3_000)
        assertEquals("the position is closed", 0, Paper.state.positions.filter { it.symbol.contains("24500PE") }.sumOf { it.quantity })
        // Paper fills are timed as paper.
        assertTrue(OrderTiming.card().lines.any { it.startsWith("Paper · ") })
    }

    @Test fun anOrderBeingWaitedOnIsAnsweredByZerodhasStreamUpdate() = runBlocking {
        val k = FakeKite().also { kite = it }
        k.keepAlive = true
        k.login()
        // Zerodha's book keeps saying OPEN: only the stream's update can say it filled.
        k.nextPlace(FakeKite.Scenario(outcome = FakeKite.Outcome.OPEN))
        val id = Broker.placeOrder(Kite.Order("NIFTY26OCT24500PE", Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 120.0, 0.05, "NFO", "iratest"))
        val t0 = System.currentTimeMillis()
        val waiting = async(kotlinx.coroutines.Dispatchers.IO) { Broker.awaitOrder(id, 10_000) }
        delay(300)
        KiteStream.textForTest("""{"type":"order","data":{"account_id":"XY0000","order_id":"$id","status":"COMPLETE","status_message":null,""" +
            """"tradingsymbol":"NIFTY26OCT24500PE","average_price":119.85,"filled_quantity":75,"tag":"iratest"}}""")
        val f = waiting.await()
        val ms = System.currentTimeMillis() - t0
        assertEquals("COMPLETE", f.status)
        assertEquals(119.85, f.avgPrice, 1e-9)
        assertEquals(75, f.filled)
        assertEquals("", f.message)
        assertTrue("answered on the update, not at the 10 s timeout: $ms ms", ms < 2_000)
        assertNotNull(KiteStream.orderUpdate(id))
        // An update for another order, or a non-terminal one, answers nothing: the reads go on until the timeout.
        k.nextPlace(FakeKite.Scenario(outcome = FakeKite.Outcome.OPEN))
        val id2 = Broker.placeOrder(Kite.Order("NIFTY26OCT24500PE", Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 120.0, 0.05, "NFO", "iratest"))
        KiteStream.textForTest("""{"type":"order","data":{"order_id":"$id2","status":"OPEN"}}""")
        assertEquals("OPEN", Broker.awaitOrder(id2, 800).status)
        assertTrue(OrderTiming.card().lines.any { it.startsWith("Live fills first seen on Zerodha's stream: 1 of") })
    }

    @Test fun inLiveTheEventDrivenPathSendsNothingToZerodhaForAPaperHolding() {
        val (_, stopId) = paperHoldingWithAStop(live = true)
        assertTrue("Live with real orders on", AppSettings.load().let { it.live && it.allowRealOrders })
        FastPath.start(context)
        val before = kite!!.requests.size
        // A minute closes on the stream (the minute lane runs too) and the price falls through the paper stop.
        val now = System.currentTimeMillis() / 1000
        KiteStream.feedForTest(listOf(tick(90.0, now - 60)))
        Thread.sleep(250)
        KiteStream.feedForTest(listOf(tick(75.0, now)))
        awaitTrue("the paper stop to fill") { Paper.state.orders.firstOrNull { it.orderId == stopId }?.status == "complete" }
        // Long enough for the 2-second (Zerodha) lane and the minute lane (3 s after the close) to have run.
        Thread.sleep(4_000)
        val sent = kite!!.requests.drop(before)
        assertTrue("no order, change or cancel reached Zerodha: $sent", sent.none { it.method != "GET" })
        assertTrue(kite!!.placed.isEmpty())
    }
}
