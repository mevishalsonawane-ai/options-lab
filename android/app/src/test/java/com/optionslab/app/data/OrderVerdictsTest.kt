package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.Cleared
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.risk.OrderFingerprint
import com.optionslab.engine.risk.OrderGates
import com.optionslab.engine.sandbox.Quote
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * The order fingerprint on the risk verdicts (22 Oct, [OrderVerdicts]): a real entry goes only with a verdict that cleared this
 * exact order today; a changed field, a verdict from another day or none refuses it at the door, names the field, logs it and
 * tells Boss; exits never ask; a paper order with a verdict is held to it the same way; the arm's gate line shows the outcome.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrderVerdictsTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val sym = "NIFTY26OCT24500CE"
    private fun order(qty: Int = 75, tag: String = "iraorb") = Kite.Order(sym, Kite.Side.BUY, qty, 75, "MIS", "MARKET", null, 0.05, "NFO", tag)

    @Before fun up() {
        kite = FakeKite(); kite.login()
        kite.instruments += FakeKite.Ins(41_000_001, sym, "NIFTY", AutomationSupport.expiryAfter(7), 24_500.0, "CE", 75)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        runBlocking { Broker.instruments() }
        OrderVerdicts.resetForTest()
        AutomationSupport.clearAlerts()
    }

    @After fun down() { kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun refused(block: suspend () -> Unit): String = runBlocking {
        try { block(); fail("the order went"); "" } catch (e: Broker.KiteError) { assertEquals("fingerprint", e.type); assertTrue(Broker.definite(e)); e.message!! }
    }

    @Test fun aChangedQuantityIsRefusedAtTheDoorAndNamed() {
        val v = Cleared.live(order(75), "orb")
        val why = refused { Broker.placeOrder(order(150), arm = "orb", verdict = v) }
        assertTrue(why, why.contains("the order changed after its check: quantity 75 → 150"))
        assertTrue("nothing sent", kite.placed.isEmpty())
        // Said to Boss, logged, and kept for the arm's gate line.
        assertTrue(AutomationSupport.alerts().any { it.contains("NOT sent") && it.contains("quantity 75 → 150") })
        assertTrue(runBlocking { Diag.report() }.contains("quantity 75 → 150"))
        assertNotNull(OrderVerdicts.lastRefused("orb"))
        assertNull(OrderVerdicts.lastMatched("orb"))
        val gate = OrderGates.arm(true, true, true, OrderVerdicts.lastMatched("orb"), OrderVerdicts.lastRefused("orb"))[2]
        assertFalse(gate.done)
        assertTrue(gate.line(), gate.line().contains("last send refused"))
        assertTrue(OrderVerdicts.diagLine().contains("1 send(s) refused"))
    }

    @Test fun everyOtherFieldChangedIsRefusedToo() {
        val v = Cleared.live(order(), "orb")
        for ((o, words) in listOf(
            order().copy(side = Kite.Side.SELL) to "side BUY → SELL",
            order().copy(product = "NRML") to "product MIS → NRML",
            order().copy(orderType = "LIMIT", price = 101.0) to "order type MARKET → LIMIT",
            order().copy(tradingSymbol = "NIFTY26OCT24600CE") to "symbol",
            order(tag = "irapine") to "source",
        )) assertTrue(words, refused { Broker.placeOrder(o, arm = if (o.tag == "irapine") "pine" else "orb", verdict = v) }.contains(words))
        // The stop the sender rests differs from the one the check saw.
        val withStop = OrderVerdicts.cleared(OrderVerdicts.liveFields(order(), Market.today(), "orb", stop = 60.0), "test")
        assertTrue(refused { Broker.placeOrder(order(), arm = "orb", verdict = withStop, stop = 70.0) }.contains("stop 60.00 → 70.00"))
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun aVerdictFromAnotherDayOrNoneOrARefusalNeverClears() {
        val old = OrderFingerprint.Verdict(OrderVerdicts.liveFields(order(), Market.today().minusDays(1), "orb"), passed = true)
        assertTrue(refused { Broker.placeOrder(order(), arm = "orb", verdict = old) }.contains("not today"))
        assertTrue(refused { Broker.placeOrder(order(), arm = "orb") }.contains("no check cleared this order"))
        val failed = OrderVerdicts.stamp(OrderVerdicts.liveFields(order(), Market.today(), "orb"), listOf("Daily loss limit reached."), "guard")
        assertTrue(refused { Broker.placeOrder(order(), arm = "orb", verdict = failed) }.contains("the check refused this order: Daily loss limit reached."))
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun theSameOrderGoesAndAnExitNeverAsks() = runBlocking {
        // 30 Oct: the ORB family's and Pine's live entries need Live and "AI trades go live" at the door ([Broker.ARM_TAGS]).
        AutomationSupport.liveSettings()
        val v = Cleared.live(order(), "orb")
        Broker.placeOrder(order(), arm = "orb", verdict = v)
        assertEquals(1, kite.placed.size)
        assertEquals(v.fingerprint, OrderVerdicts.lastMatched("orb"))
        assertTrue(OrderGates.allOpen(OrderGates.arm(true, true, true, OrderVerdicts.lastMatched("orb"), OrderVerdicts.lastRefused("orb"))))
        // An exit with no verdict at all: closing lowers risk.
        Broker.placeOrder(order().copy(side = Kite.Side.SELL), exit = true)
        assertEquals(2, kite.placed.size)
        assertEquals(0, OrderVerdicts.diagLine().let { Regex("(\\d+) send\\(s\\) refused").find(it)!!.groupValues[1].toInt() })
    }

    @Test fun aPaperOrderIsHeldToItsVerdictTheSameWayAndNeverWithoutOne() {
        val expiry = AutomationSupport.expiryAfter(7)
        AutomationSupport.contracts(context, listOf(Upstox.Contract("NIFTY", expiry, 24_500.0, Right.CE, 75, "NSE_FO|FPCE", "NIFTY-FP-24500CE")))
        val c = Paper.contractFor("NIFTY", expiry, 24_500.0, Right.CE)!!
        val v = OrderVerdicts.cleared(OrderVerdicts.paperFields(c, "BUY", 1, "MARKET", "MIS", null, null, Market.today(), "manual"), "paper order")
        // Two lots where one was checked: refused, named.
        val bad = runBlocking { Paper.place(c, "BUY", 2, "MARKET", "MIS", null, null, Quote(100.0), verdict = v) }
        assertFalse(bad.ok)
        assertTrue(bad.message, bad.message.contains("quantity 75 → 150"))
        assertTrue(runBlocking { Paper.snapshot() }.positions.positions.none { it.quantity != 0 })
        // The order as checked: placed. No verdict (a resting stop, an exit): as before.
        assertTrue(runBlocking { Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, Quote(100.0), verdict = v) }.ok)
        assertTrue(runBlocking { Paper.place(c, "SELL", 1, "MARKET", "MIS", null, null, Quote(100.0)) }.ok)
    }
}
