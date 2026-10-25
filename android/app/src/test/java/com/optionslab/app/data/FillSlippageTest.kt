package com.optionslab.app.data

import com.optionslab.app.testing.Cleared
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.Slippage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * Slippage per fill (round 4, measure only), against the fake Kite: a market entry measured from the stream's last price, an
 * exit from the price its caller decided on, a resting stop from its trigger when the stream says it filled; the paper book's
 * modelled fills kept apart; the file holds encrypted lines and reads back; placing and following an order reads nothing
 * from the settings vault and sends nothing more to Zerodha.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class FillSlippageTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT25000CE"
    private val token = 23_000_001L

    @Before fun up() {
        kite = FakeKite()
        kite.keepAlive = true
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        kite.instruments += FakeKite.Ins(token, sym, "NIFTY", AutomationSupport.expiryAfter(7), 25_000.0, "CE", 75)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        FillSlippage.settleMs = 0L
    }

    @After fun down() {
        SecurePrefs.countedThread = null
        FillSlippage.resetForTest()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun tick(last: Double) = KiteStream.feedForTest(listOf(KiteTicks.Tick(token, last, bid = last - 0.05, ask = last + 0.05)))

    private fun awaitFills(n: Int): List<Slippage.Fill> {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) { val f = FillSlippage.fills(); if (f.size >= n) return f; Thread.sleep(10) }
        throw AssertionError("timed out waiting for $n fills: ${FillSlippage.fills()}")
    }

    private fun buy() = Kite.Order(sym, Kite.Side.BUY, 75, 75, "MIS", "MARKET", null, 0.05, "NFO", "iraorb")

    @Test fun aMarketEntryIsMeasuredFromTheStreamsLastPriceWithNoVaultRead() = runBlocking {
        // Warm: the first order after login reads the session once (it is then kept in memory).
        tick(200.0)
        kite.nextPlace(FakeKite.Scenario(fillPrice = 200.0))
        Broker.awaitOrder(Broker.placeOrder(buy(), arm = "orb", verdict = Cleared.live(buy(), "orb")), 2_000)
        awaitFills(1)
        tick(200.0)
        kite.nextPlace(FakeKite.Scenario(fillPrice = 200.5))
        val before = kite.requests.size
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        val id = Broker.placeOrder(buy(), arm = "orb", verdict = Cleared.live(buy(), "orb"))
        val f = Broker.awaitOrder(id, 2_000)
        SecurePrefs.countedThread = null
        assertEquals("COMPLETE", f.status)
        assertEquals("settings reads while placing and following the order", 0L, SecurePrefs.readsForTest.get())
        // Measuring sends nothing to Zerodha: one POST and the order's reads, as before.
        assertEquals(1, kite.requests.drop(before).count { it.method != "GET" })
        val x = awaitFills(2).last()
        assertEquals("ORB", x.strategy); assertEquals(Slippage.Leg.ENTRY, x.leg); assertEquals("BUY", x.side)
        assertEquals(Slippage.Basis.LTP, x.basis); assertFalse(x.paper)
        assertEquals(0.5, x.rupees, 1e-9); assertEquals(10.0, x.ticks, 1e-6)
    }

    @Test fun anExitFromTheCallersPriceAndARestingStopFromItsTrigger() = runBlocking {
        kite.position(sym, 150, 200.0, product = "MIS")
        // An exit: the caller's decided price (199.00), filled at 198.80 - a cost of 0.20 on a sell.
        kite.nextPlace(FakeKite.Scenario(fillPrice = 198.8))
        val sell = Kite.Order(sym, Kite.Side.SELL, 75, 75, "MIS", "MARKET", null, 0.05, "NFO", "irapine")
        Broker.awaitOrder(Broker.placeOrder(sell, exit = true, intended = 199.0), 2_000)
        val exit = awaitFills(1).single()
        assertEquals("Pine", exit.strategy); assertEquals(Slippage.Leg.EXIT, exit.leg); assertEquals(Slippage.Basis.SIGNAL, exit.basis)
        assertEquals(0.2, exit.rupees, 1e-9)
        // A resting stop (trigger 180) that Zerodha says on the stream filled at 178.
        val stop = Kite.Order(sym, Kite.Side.SELL, 75, 75, "MIS", "SL", 175.0, 0.05, "NFO", "iraprotect", triggerPrice = 180.0)
        val sid = Broker.placeOrder(stop, exit = true)
        kite.fill(sid, 178.0)
        KiteStream.textForTest("""{"type":"order","data":{"order_id":"$sid","status":"COMPLETE","status_message":null,""" +
            """"average_price":178.0,"filled_quantity":75,"tradingsymbol":"$sym","tag":"iraprotect"}}""")
        val s = awaitFills(2).last()
        assertEquals("Protection", s.strategy); assertEquals(Slippage.Basis.TRIGGER, s.basis)
        assertEquals(2.0, s.rupees, 1e-9); assertEquals(40.0, s.ticks, 1e-6)
        // One fill is measured once, whoever says it ended (the order book's read again here).
        Broker.orders()
        Thread.sleep(50)
        assertEquals(2, FillSlippage.fills().size)
    }

    @Test fun anUnfilledOrderIsNotMeasured() = runBlocking {
        tick(200.0)
        kite.nextPlace(FakeKite.Scenario(outcome = FakeKite.Outcome.REJECT))
        val f = Broker.awaitOrder(Broker.placeOrder(buy(), arm = "orb", verdict = Cleared.live(buy(), "orb")), 2_000)
        assertEquals("REJECTED", f.status)
        Thread.sleep(100)
        assertTrue(FillSlippage.fills().isEmpty())
    }

    /**
     * 25 Oct (app audit: "no fills measured yet" every day of 19-24 Oct): a resting paper stop that filled on a pass is measured
     * against its trigger (an exit), a resting limit buy against its price (an entry); a market order is not measured here.
     */
    @Test fun restingPaperStopsAndLimitsAreMeasuredFromTheirTriggerAndPrice() {
        val t = java.time.LocalDateTime.of(2026, 10, 23, 9, 26)
        fun order(id: String, type: String, action: String, price: Double?, trigger: Double?) = com.optionslab.engine.sandbox.Order(id, null, sym, "NFO", action, 75,
            price?.toBigDecimal(), trigger?.toBigDecimal(), type, "MIS", "complete", null, 75, 0, null, java.math.BigDecimal.ZERO, t, t)
        fun fill(id: String, action: String, px: Double) = com.optionslab.engine.sandbox.SandboxEvent.Fill(id, "t$id", sym, "NFO", action, 75, px, "MIS")
        FillSlippage.resting(fill("1", "SELL", 117.2), order("1", "SL-M", "SELL", null, 120.0))
        FillSlippage.resting(fill("2", "BUY", 28.3), order("2", "LIMIT", "BUY", 28.5, null))
        FillSlippage.resting(fill("3", "SELL", 150.0), order("3", "MARKET", "SELL", null, null))
        FillSlippage.resting(fill("4", "SELL", 150.0), null)
        val got = awaitFills(2)
        Thread.sleep(100)
        assertEquals(2, FillSlippage.fills().size)
        val stop = got.single { it.basis == Slippage.Basis.TRIGGER }
        assertTrue(stop.paper); assertEquals(Slippage.Leg.EXIT, stop.leg); assertEquals(2.8, stop.rupees, 1e-9)
        val lim = got.single { it.basis == Slippage.Basis.LIMIT }
        assertEquals(Slippage.Leg.ENTRY, lim.leg); assertEquals(-0.2, lim.rupees, 1e-9)
        assertTrue(FillSlippage.diagLine().contains("paper (modelled) kept"))
    }

    @Test fun paperFillsAreKeptApartAndTheFileReadsBackEncrypted() {
        FillSlippage.paper(sym, "BUY", entry = true, qty = 75, intended = 100.0, avg = 100.05, limit = false)
        val p = awaitFills(1).single()
        assertTrue(p.paper); assertEquals("Paper", p.strategy); assertEquals(0.05, p.rupees, 1e-9)
        assertTrue(FillSlippage.cardLines().any { it.startsWith("Paper slippage (modelled)") })
        assertTrue(FillSlippage.diagLine().contains("paper (modelled) today"))
        // The file: one line a fill, encrypted (no symbol in it), read back after a restart.
        val end = System.currentTimeMillis() + 5_000
        while (FillSlippage.fileTextForTest().isBlank() && System.currentTimeMillis() < end) Thread.sleep(10)
        val text = FillSlippage.fileTextForTest()
        assertEquals(1, text.lines().count { it.isNotBlank() })
        assertFalse(text.contains(sym))
        FillSlippage.forgetMemoryForTest()
        assertEquals(listOf(p), FillSlippage.fills())
        // Jarvis's words on it.
        assertTrue(FillSlippage.answer(Slippage.Ask(null, true)).startsWith("Your paper (the sandbox's modelled) slippage"))
    }
}
