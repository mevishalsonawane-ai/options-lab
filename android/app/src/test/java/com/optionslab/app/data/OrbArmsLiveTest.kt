package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Outcome
import com.optionslab.app.testing.FakeKite.Reply
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime

/**
 * The ORB arms in Live ([OrbArms]) against the fake Kite: an approved entry buys one lot and rests its stop at
 * Zerodha; a buy whose reply was lost is held as unconfirmed and settled from the order book; a partial exit
 * books what sold and sells the rest next pass; one arm's exit never cancels the other arm's stop; and a phone
 * that failed the security check sends nothing. The arms' state is written as the app saves it, and the
 * clock is fixed ([OrbArms.testNow]) so the session-end rules do not depend on when the tests run.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrbArmsLiveTest : RobolectricTest() {
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
        now = t.toLocalDateTime().withNano(0)
    }

    @After fun down() {
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    // ---- the arms' saved state -------------------------------------------------------------------

    private fun contract(symbol: String, right: String) =
        JSONArray().put(symbol).put("BANKNIFTY").put(expiry.toString()).put(52_000.0).put(right).put(30).put("NSE_FO|TEST$right")

    /** Minutes before [now], but never yesterday (a run just after midnight IST would otherwise hold yesterday's positions). */
    private fun ago(minutes: Long) = maxOf(now.minusMinutes(minutes), now.toLocalDate().atStartOfDay())

    private fun position(arm: String, qty: Int, entry: Double, entryId: String?, stopId: String? = null, stop: Double? = null) =
        JSONObject().put("arm", arm).put("symbol", paperCe).put("right", "CE").put("qty", qty).put("entry", entry)
            .put("entryTime", ago(30).toString()).put("signalBar", ago(35).toString())
            .put("entryOrderId", entryId ?: "").put("stopOrderId", stopId ?: "").apply { stop?.let { put("stopTrigger", it) } }
            .put("exitTime", "").put("why", "").put("charges", 0.0).put("live", true).put("kite", sym).put("unconfirmed", false)

    private fun state(armed: Boolean = true, pending: Boolean = false, expires: LocalDateTime = now.plusMinutes(9), positions: List<JSONObject> = emptyList()) {
        val o = JSONObject()
            .put("armed", JSONObject().put("orb", armed)).put("auto", JSONObject().put("orb", true)).put("liveOk", JSONObject().put("orb", armed))
            .put("legs", JSONObject().put("day", now.toLocalDate().toString()).put("strike", 52_000).put("expiry", expiry.toString())
                .put("ce", contract(paperCe, "CE")).put("pe", contract(paperPe, "PE")))
            .put("positions", JSONArray(positions))
        if (pending) o.put("pending", JSONObject().put("orb", JSONObject().put("right", "CE").put("bar", ago(1).toString()).put("expires", expires.toString())))
        AutomationSupport.orbState(context, o)
    }

    private fun arm(source: String = "orb") = runBlocking { OrbArms.view() }.arms.single { it.arm.source == source }
    private fun approve(pin: Boolean = true) = runBlocking { OrbArms.approve("orb", pinConfirmed = pin) }
    private fun pass() = runBlocking { OrbArms.priceCheckOnly() }
    private fun stopOrder(qty: Int, trigger: Double, limit: Double) = runBlocking {
        Broker.placeOrder(Kite.Order(sym, Kite.Side.SELL, qty, 30, "MIS", "SL", limit, 0.05, "NFO", "iraorb", triggerPrice = trigger), exit = true)
    }

    // ---- entries --------------------------------------------------------------------------------

    @Test fun anApprovedLiveEntryBuysOneLotThenRestsItsStop() {
        state(pending = true)
        assertEquals("Entered at Zerodha (live).", approve())
        val (buy, stop) = kite.placed
        val keys = setOf("tradingsymbol", "exchange", "transaction_type", "order_type", "quantity", "product", "tag", "price", "trigger_price")
        assertEquals(mapOf("tradingsymbol" to sym, "exchange" to "NFO", "transaction_type" to "BUY", "order_type" to "MARKET",
            "quantity" to "30", "product" to "MIS", "tag" to "iraorb"), buy.form.filterKeys { it in keys })
        assertEquals(mapOf("tradingsymbol" to sym, "exchange" to "NFO", "transaction_type" to "SELL", "order_type" to "SL",
            "quantity" to "30", "product" to "MIS", "tag" to "iraorb", "price" to "152.00", "trigger_price" to "160.00"), stop.form.filterKeys { it in keys })
        val a = arm()
        val p = a.open!!
        assertTrue(p.live); assertFalse(p.unconfirmed)
        assertEquals(200.0, p.entry, 0.0); assertEquals(160.0, p.stopTrigger!!, 0.0)
        assertEquals(kite.orders.keys.last(), p.stopOrderId)
        assertNull("the approval is used up", a.pending)
        assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
    }

    @Test fun inLiveAnApprovalWithoutThePinSendsNothing() {
        state(pending = true)
        assertEquals("The app is in Live: approve with your PIN on Home → Strategies.", approve(pin = false))
        assertTrue(kite.requests.isEmpty())
        assertNotNull("still waiting for the PIN", arm().pending)
    }

    @Test fun aPhoneThatFailedTheSecurityCheckSendsNoLiveEntry() {
        AutomationSupport.security(compromised = true)
        state(pending = true)
        assertEquals("Refused: this phone failed the security check; no live order sent", approve())
        assertTrue(kite.placed.isEmpty())
        assertNull(arm().open)
    }

    @Test fun theKillSwitchRefusesTheEntry() {
        SecurePrefs.put("g.kill", true)
        state(pending = true)
        assertEquals("Refused: the kill switch is on", approve())
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun anExpiredSignalIsNotEntered() {
        state(pending = true, expires = now.minusMinutes(1))
        val msg = approve()
        assertTrue(msg, msg.startsWith("The ORB signal expired at "))
        assertTrue(kite.requests.isEmpty())
        assertEquals("Nothing is waiting for approval.", approve())
    }

    @Test fun aLostReplyIsHeldUnconfirmedThenSettledWithItsStop() {
        state(pending = true)
        kite.nextPlace(reply = Reply.DROP_AFTER_ACCEPT)
        kite.down += "/orders"                                         // and the order book cannot be read either
        assertEquals("Bought at Zerodha, not yet confirmed: held until the order book shows what filled.", approve())
        val held = arm().open!!
        assertTrue(held.unconfirmed); assertNull(held.stopOrderId); assertNull(held.entryOrderId)
        assertEquals("the arm buys nothing more meanwhile", "BUY", kite.placed.single().form["transaction_type"])
        assertTrue(AutomationSupport.alerts().any { it.contains("Zerodha did not confirm the buy of $sym") })

        kite.down.clear()
        pass()
        val settled = arm().open!!
        assertFalse(settled.unconfirmed)
        assertEquals(kite.orders.keys.first(), settled.entryOrderId)
        assertEquals(30, settled.qty); assertEquals(200.0, settled.entry, 0.0)
        assertEquals("its stop now rests", "SL", kite.placed.last().form["order_type"])
        assertEquals("still one buy", 1, kite.placed.count { it.form["transaction_type"] == "BUY" })
        assertEquals(settled.stopOrderId, kite.orders.keys.last())
    }

    @Test fun anUnconfirmedBuyThatNeverReachedZerodhaIsDropped() {
        val p = position("orb", 30, 200.0, entryId = null).put("unconfirmed", true)
        state(positions = listOf(p))
        pass()
        assertNull("nothing is held", arm().open)
        assertTrue(kite.placed.isEmpty())
    }

    // ---- exits ----------------------------------------------------------------------------------

    @Test fun aPartialExitBooksWhatSoldAndSellsTheRestNextPass() {
        state(positions = listOf(position("orb", 60, 200.0, entryId = "E1")))
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                    // +40 target
        kite.nextPlace(outcome = Outcome.PARTIAL, partialQty = 30)
        pass()
        assertEquals("60", kite.placed.single().form["quantity"])
        val today = arm().today
        val closed = today.single { !it.open }
        assertEquals(30, closed.qty); assertEquals("target", closed.why); assertEquals(245.0, closed.exit!!, 0.0)
        assertEquals(30, arm().open!!.qty)
        assertEquals("the unfilled rest was cancelled", "CANCELLED", kite.order(kite.orders.keys.last()).status)

        pass()
        assertEquals(listOf("60", "30"), kite.placed.map { it.form["quantity"] })
        assertNull(arm().open)
        assertEquals(0, kite.positions.getValue("NFO:$sym:MIS").first)
    }

    @Test fun oneArmsExitNeverCancelsTheOtherArmsStop() {
        val s1 = stopOrder(30, 160.0, 152.0)
        val s2 = stopOrder(30, 190.0, 180.0)
        state(positions = listOf(position("orb", 30, 200.0, "E1", s1, 160.0), position("orb_fresh", 30, 230.0, "E2", s2, 190.0)))
        kite.position(sym, 60, 215.0, product = "MIS")
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                    // ORB's target; not ORB Fresh's
        kite.requests.clear()
        pass()
        assertTrue("ORB's own stop comes out first", kite.requests.any { it.method == "DELETE" && it.path == "/orders/regular/$s1" })
        assertTrue("ORB Fresh's stop is never touched", kite.requests.none { it.path.endsWith("/$s2") && it.method != "GET" })
        assertEquals("TRIGGER PENDING", kite.order(s2).status)
        assertEquals("only ORB's lot is sold", listOf("30"), kite.placed.map { it.form["quantity"] })
        assertEquals("target", arm("orb").today.single().why)
        assertTrue(arm("orb_fresh").open != null)
        assertEquals(30, kite.positions.getValue("NFO:$sym:MIS").first)
    }

    @Test fun aStopThatFilledAtZerodhaIsTheExit() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.fill(s1, 158.0)
        kite.quote("NFO:$sym", 150.0)
        kite.requests.clear()
        pass()
        val p = arm().today.single()
        assertEquals("stop", p.why); assertEquals(158.0, p.exit!!, 0.0)
        assertTrue("no second sell after the stop", kite.placed.isEmpty())
    }

    @Test fun closedByHandTheStopComesOutAndNothingIsSold() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 0, 0.0, product = "MIS")                       // sold in the Kite app
        kite.quote("NFO:$sym", 210.0)
        kite.requests.clear()
        pass()
        assertTrue(kite.requests.any { it.method == "DELETE" && it.path == "/orders/regular/$s1" })
        assertTrue("a sell now would open a short", kite.placed.isEmpty())
        assertEquals("closed_by_you", arm().today.single().why)
    }

    // ---- the profit lock moves the stop at Zerodha (07 Oct, research/HUNT_H20.md F1-F3, F5) ------------------------

    /** A laddered ORB-family position (as every new entry is) with its stop resting at Zerodha; [peak]: its best so far. */
    private fun laddered(arm: String, entry: Double, stopId: String, stop: Double, peak: Double? = null) =
        position(arm, 30, entry, "E1", stopId, stop).put("ladder", true).apply { peak?.let { put("peak", it) } }

    /** The paper feed's candles (the best price without a stream), served by a fake so no test reaches the internet. */
    private fun <T> withUpstox(body: () -> T): T {
        val u = FakeUpstox()
        try { u.price("NSE_FO|TESTCE", 1.0); return body() } finally { u.close() }
    }

    @Test fun theLockModifiesTheStopAtZerodhaUpAndNeverDown() = withUpstox {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(laddered("orb", 200.0, s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 222.0)                                     // +22: +10 is locked
        kite.requests.clear()
        pass()
        val o = kite.order(s1)
        assertEquals("the same order, still resting", "TRIGGER PENDING", o.status)
        assertEquals(210.0, o.trigger, 1e-9)
        assertEquals("SL", o.type)
        assertTrue("its limit sits under the trigger", o.price < 210.0)
        assertTrue("modified, never cancelled", kite.requests.none { it.method == "DELETE" })
        assertTrue("never a new sell", kite.placed.isEmpty())
        assertEquals(210.0, arm().open!!.stopTrigger!!, 1e-9)
        kite.quote("NFO:$sym", 215.0)                                     // lower again: the stop stays where it is
        kite.requests.clear()
        pass()
        assertEquals(210.0, kite.order(s1).trigger, 1e-9)
        assertTrue("never moved down", kite.requests.none { it.method == "PUT" })
        // Zerodha sells at the lock by itself (the app need not be running): booked as the profit lock.
        kite.fill(s1, 209.5)
        kite.quote("NFO:$sym", 205.0)
        pass()
        val closed = arm().today.single()
        assertEquals("profit_lock", closed.why)
        assertEquals(209.5, closed.exit!!, 1e-9)
        assertTrue("no second sell", kite.placed.isEmpty())
    }

    @Test fun aRefusedModifyKeepsTheOldStopAndIsTriedAgainOnTheNextCheck() = withUpstox {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(laddered("orb", 200.0, s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 222.0)
        kite.down += "/orders/regular/$s1"
        kite.requests.clear()
        pass()
        assertEquals("the old stop still works", 160.0, kite.order(s1).trigger, 1e-9)
        assertEquals("TRIGGER PENDING", kite.order(s1).status)
        assertEquals(160.0, arm().open!!.stopTrigger!!, 1e-9)
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { it.contains("did not move the stop on $sym up to the profit lock 210.00") })
        assertTrue("nothing sold, nothing cancelled", kite.placed.isEmpty())
        kite.down.clear()
        pass()
        assertEquals(210.0, kite.order(s1).trigger, 1e-9)
        assertEquals(210.0, arm().open!!.stopTrigger!!, 1e-9)
    }

    @Test fun aLockExitThatFailsPutsTheStopBackAtTheLockNotTheMinus40() = withUpstox {
        val s1 = stopOrder(30, 160.0, 152.0)
        // Its best (222) earned +10 (210), but the stop still rests at 160 (the modify was refused); the price is back at 205.
        state(positions = listOf(laddered("orb", 200.0, s1, 160.0, peak = 222.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 205.0)
        kite.nextPlace(reply = Reply.INPUT_EXCEPTION, message = "Markets are closed right now.")
        kite.requests.clear()
        pass()
        assertEquals("the -40 stop came out first", "CANCELLED", kite.order(s1).status)
        assertEquals("the market sell was tried", "MARKET", kite.placed.first().form["order_type"])
        val restop = kite.placed.last()
        assertEquals("SL", restop.form["order_type"])
        assertEquals("back at the lock, not at 160", "210.00", restop.form["trigger_price"])
        val p = arm().open!!
        assertEquals(210.0, p.stopTrigger!!, 1e-9)
        assertEquals(kite.orders.keys.last(), p.stopOrderId)
    }

    @Test fun liveExitsTakeEachArmsOwnTarget() {
        // F5: the live path used the ORB's +40 for every arm. ORB Sweep's own target is +80.
        val s1 = stopOrder(30, 160.0, 152.0)
        state(positions = listOf(position("orb_sweep", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 245.0)                                     // +45: the ORB's target, not ORB Sweep's
        kite.requests.clear()
        pass()
        assertNotNull("held", arm("orb_sweep").open)
        assertTrue(kite.placed.isEmpty())
        kite.quote("NFO:$sym", 281.0)                                     // +81: ORB Sweep's +80
        pass()
        assertEquals("target", arm("orb_sweep").today.single().why)
    }

    // ---- arming ---------------------------------------------------------------------------------

    @Test fun armingInLiveTakesThePinAndDisarmingKeepsThePositionManaged() = runBlocking {
        state(armed = false, positions = listOf(position("orb", 30, 200.0, "E1")))
        // Un-retired by Boss on 07 Oct: ORB arms like any arm - in Live only with the PIN or fingerprint, exactly as before.
        assertEquals("The app is in Live: arm it with your PIN or fingerprint.", OrbArms.setArmed("orb", true, automatic = true))
        assertFalse(arm().armed)
        val on = OrbArms.setArmed("orb", true, automatic = true, pinConfirmed = true)
        assertTrue(on, on.startsWith("ORB armed on ZERODHA (live), fully automatic"))
        assertTrue(arm().liveOk)
        assertEquals("ORB disarmed. Its open position is still managed to its exit.", OrbArms.setArmed("orb", false, automatic = true))
        assertEquals("Skipped.", OrbArms.skip("orb"))
        assertEquals("You skipped the last signal.", OrbArms.describe(arm().status))
        assertTrue(kite.requests.isEmpty())
    }

    @Test fun describeSaysEveryStateInPlainWords() {
        assertEquals("Waits for the market watch.", OrbArms.describe(""))
        assertEquals("Refused by Bot settings: max open", OrbArms.describe("guard_refused: max open"))
        assertEquals("The order was refused: x", OrbArms.describe("order_refused: x"))
        assertEquals("Could not check: y", OrbArms.describe("error: y"))
        assertEquals("Breakout: waiting for your approval with PIN (live).", OrbArms.describe("awaiting_approval"))
        assertEquals("something new", OrbArms.describe("something new"))
    }
}
