package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime

/**
 * 18 Nov 2026 (audit, "Start again with critical"): the live exits of the ORB family and Liquidity 15+5 ([OrbArms]) against the fake
 * Kite - a held lot never loses its resting stop to a bad read or an exit that cannot go, and the app never rests sells for more than
 * Zerodha holds. The clock is fixed ([OrbArms.testNow]) and the index feed is a test feed (no test reaches the internet).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbArmsExitGuardTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val paperCe = "BANKNIFTY-TEST-52000CE"
    private val paperPe = "BANKNIFTY-TEST-52000PE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private lateinit var now: LocalDateTime

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(22_000_001, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(22_000_002, "BANKNIFTY26OCT52000PE", "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        kite.requests.clear()
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        // Liquidity's index exits read the index candles: a test feed with none (its time stop decides on the option's price alone).
        OrbArms.testIndexBars = { emptyList() }
        now = t.toLocalDateTime().withNano(0)
    }

    @After fun down() {
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun contract(symbol: String, right: String) =
        JSONArray().put(symbol).put("BANKNIFTY").put(expiry.toString()).put(52_000.0).put(right).put(30).put("NSE_FO|TEST$right")

    /** Minutes before [now], but never yesterday. */
    private fun ago(minutes: Long) = maxOf(now.minusMinutes(minutes), now.toLocalDate().atStartOfDay())

    private fun position(arm: String, qty: Int, entry: Double, entryId: String?, stopId: String? = null, stop: Double? = null) =
        JSONObject().put("arm", arm).put("symbol", paperCe).put("right", "CE").put("qty", qty).put("entry", entry)
            .put("entryTime", ago(30).toString()).put("signalBar", ago(35).toString())
            .put("entryOrderId", entryId ?: "").put("stopOrderId", stopId ?: "").apply { stop?.let { put("stopTrigger", it) } }
            .put("exitTime", "").put("why", "").put("charges", 0.0).put("live", true).put("kite", sym).put("unconfirmed", false)

    /** A live Liquidity 15+5 trade (the 5-minute BANKNIFTY book) that broke 52,050, one lot of 30. */
    private fun liquidity(qty: Int, entry: Double, stopId: String?, stop: Double?) =
        position("liquidity5", qty, entry, "E1", stopId, stop).put("level", 52_050.0).put("lot", 30)

    private fun state(positions: List<JSONObject>) {
        AutomationSupport.orbState(context, JSONObject()
            .put("armed", JSONObject().put("orb", true)).put("auto", JSONObject().put("orb", true)).put("liveOk", JSONObject().put("orb", true))
            .put("legs", JSONObject().put("day", now.toLocalDate().toString()).put("strike", 52_000).put("expiry", expiry.toString())
                .put("ce", contract(paperCe, "CE")).put("pe", contract(paperPe, "PE")))
            .put("positions", JSONArray(positions)))
    }

    private fun view() = runBlocking { OrbArms.view() }.arms
    private fun open(source: String) = view().single { it.arm.source == source }.open
    private fun today(source: String) = view().single { it.arm.source == source }.today
    private fun pass() = runBlocking { OrbArms.priceCheckOnly() }
    private fun stopOrder(qty: Int, trigger: Double, limit: Double) = runBlocking {
        Broker.placeOrder(Kite.Order(sym, Kite.Side.SELL, qty, 30, "MIS", "SL", limit, 0.05, "NFO", "iraorb", triggerPrice = trigger), exit = true)
    }

    // ---- B1: Liquidity's index and time exits keep the "shown gone but not confirmed" guard ------------------------------------

    /**
     * 18 Nov 2026 (audit: B1): Liquidity's time stop is due while one positions read shows the lot gone (a row at 0, no sale anywhere):
     * its resting stop is never taken out and nothing is sold; once the reads show it held again, the time stop takes the stop out and
     * sells it as before.
     */
    @Test fun liquiditysTimeStopNeverTakesTheStopOutOnOneReadShowingItGone() {
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // entered 30 minutes ago, still at 200: the time stop is due
        kite.position(sym, 0, 0.0, product = "MIS")                        // one bad read: a row at 0, no sale in the order book
        kite.requests.clear()
        pass()
        assertEquals("its stop rests", "TRIGGER PENDING", kite.order(s1).status)
        assertTrue("nothing cancelled, nothing sold: ${kite.writes.map { it.method + " " + it.path }}", kite.requests.none { it.method != "GET" })
        val held = open("liquidity")
        assertNotNull("kept", held)
        assertEquals("its stop is still its own", s1, held!!.stopOrderId)
        kite.position(sym, 30, 200.0, product = "MIS")                     // the next reads show it held
        pass()
        assertEquals("the time stop took its stop out", "CANCELLED", kite.order(s1).status)
        val sells = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }
        assertEquals(listOf("30"), sells.map { it.form["quantity"] })
        assertEquals("time_stop", today("liquidity").single().why)
    }

    // ---- B2: the stop comes out only when a sale will go; any exit that does not go puts it back -----------------------------

    private fun marketSells() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }

    /**
     * 18 Nov 2026 (audit: B2): the target is due but another sale already working covers the lot (the arm's own earlier sale whose reply
     * was lost): the resting stop is never taken out for a sale that cannot go.
     */
    @Test fun anExitThatCannotSellLeavesTheStopResting() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.restingInKite(sym, "SELL", 30, 260.0, tag = "iraorb")         // an earlier sale of the arm's, still working
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                       // the ORB's +40 target
        kite.requests.clear()
        pass()
        assertEquals("its stop rests", "TRIGGER PENDING", kite.order(s1).status)
        assertTrue("nothing cancelled, nothing sold", kite.requests.none { it.method != "GET" })
        assertEquals(s1, open("orb")!!.stopOrderId)
    }

    /** 18 Nov 2026 (audit: B2): the order book unreadable after the stop came out: the stop is put back at once, for what is held. */
    @Test fun anExitWhoseReadFailsAfterTheStopCameOutPutsTheStopBack() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)
        kite.afterNext("/orders/regular/$s1") { kite.down += "/orders" }   // the order book fails from the stop's cancel on
        kite.requests.clear()
        pass()
        kite.down.clear()
        assertEquals("CANCELLED", kite.order(s1).status)
        assertTrue("nothing sold", marketSells().isEmpty())
        val back = kite.placed.single { it.form["order_type"] == "SL" }
        assertEquals("30", back.form["quantity"]); assertEquals("160.00", back.form["trigger_price"])
        val p = open("orb")!!
        assertEquals("the stop put back is its stop", kite.orders.keys.last(), p.stopOrderId)
        assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
    }

    /**
     * 18 Nov 2026 (audit: B2): at a stop's level, Boss's own LIMIT sell in Kite covering the lot is cancelled and confirmed out first,
     * then the app takes its stop out and sells at market - never more than is held; Boss is told.
     */
    @Test fun aStopExitCancelsBosssOwnSellThatBlocksItThenSells() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        val his = kite.restingInKite(sym, "SELL", 30, 250.0)               // Boss's own LIMIT sell, no tag
        kite.quote("NFO:$sym", 150.0, 149.95, 150.05)                       // through the stop's limit: the app sells
        kite.requests.clear()
        pass()
        assertEquals("his order out first", "CANCELLED", kite.order(his).status)
        assertEquals("CANCELLED", kite.order(s1).status)
        assertEquals(listOf("30"), marketSells().map { it.form["quantity"] })
        assertEquals("stop", today("orb").single().why)
        assertEquals(0, kite.positions.getValue("NFO:$sym:MIS").first)
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "your own sell order on $sym" in it && "It is cancelled" in it })
    }

    /** 18 Nov 2026 (audit: B2): at the target, Boss's own sell covering the lot is left alone: Boss is told, the stop rests, nothing sold. */
    @Test fun aTargetNeverCancelsBosssOwnSellAndTellsHim() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        val his = kite.restingInKite(sym, "SELL", 30, 260.0)
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)
        kite.requests.clear()
        pass()
        assertEquals("OPEN", kite.order(his).status)
        assertEquals("TRIGGER PENDING", kite.order(s1).status)
        assertTrue("nothing cancelled, nothing sold", kite.requests.none { it.method != "GET" })
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "your own sell order on $sym" in it && "waits for it" in it })
        pass()
        assertEquals("told once a minute", 1, AutomationSupport.alerts().count { "your own sell order on $sym" in it })
    }

    /**
     * 18 Nov 2026 (audit: B2): a trade with no stop of its own whose stop's reply was lost (an "iraorb" stop no trade claims) adopts it as
     * its stop at its exit, takes it out as its own and sells.
     */
    @Test fun aLostStopNoTradeClaimsIsAdoptedAtTheExitThenTakenOutAndSold() {
        state(listOf(position("orb", 30, 200.0, "E1", stopId = null, stop = 160.0)))
        val orphan = stopOrder(30, 160.0, 152.0)                            // the stop's reply was lost: no trade has its id
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 155.0, 154.95, 155.05)                       // under its stop
        kite.requests.clear()
        pass()
        assertEquals("taken out as its own", "CANCELLED", kite.order(orphan).status)
        assertEquals(listOf("30"), marketSells().map { it.form["quantity"] })
        assertEquals("stop", today("orb").single().why)
        assertEquals(0, kite.positions.getValue("NFO:$sym:MIS").first)
    }

    /** 18 Nov 2026 (audit: B2): from 15:10 a refused exit does not put the stop back (the square-off sells it; Boss is told). */
    @Test fun fromTheSquareOffARefusedExitDoesNotPutTheStopBack() {
        OrbArms.testNow = OrbArms.testNow!!.with(java.time.LocalTime.of(15, 12))
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        kite.nextPlace(reply = FakeKite.Reply.INPUT_EXCEPTION, message = "Markets are closed right now.")
        kite.requests.clear()
        pass()
        assertEquals("CANCELLED", kite.order(s1).status)
        assertTrue("no stop placed again", kite.placed.none { it.form["order_type"] == "SL" })
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "was not put back (the square-off time)" in it })
    }
}
