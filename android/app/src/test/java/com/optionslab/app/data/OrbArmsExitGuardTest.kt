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
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "your own sell order on $sym" in it && "It was cancelled" in it })
    }

    /**
     * 18 Nov 2026 (audit: B2, review): Boss also holds a lot of his own on the contract (Zerodha holds more than the app's trades): his
     * sell order may be for it, so it is never cancelled, even at a stop's level - he is told, and the trade's stop rests.
     */
    @Test fun bosssOwnSellIsNeverCancelledWhileHeHoldsLotsOfHisOwn() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 60, 200.0, product = "MIS")                      // his lot beside the arm's
        val his = kite.restingInKite(sym, "SELL", 60, 250.0)
        kite.quote("NFO:$sym", 150.0, 149.95, 150.05)
        kite.requests.clear()
        pass()
        assertEquals("OPEN", kite.order(his).status)
        assertEquals("TRIGGER PENDING", kite.order(s1).status)
        assertTrue("nothing cancelled, nothing sold", kite.requests.none { it.method != "GET" })
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "your own sell order on $sym" in it && "waits for it" in it })
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

    /**
     * 18 Nov 2026 (audit: B2, review): a stop put back for less than the trade holds (another sale covers the rest; the exit's read failed
     * after the stop came out) splits the trade, so its fill is never booked for more than it sold.
     */
    @Test fun aStopPutBackForLessThanTheTradeSplitsIt() {
        val s1 = stopOrder(60, 160.0, 152.0)
        state(listOf(position("orb", 60, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 90, 200.0, product = "MIS")
        kite.restingInKite(sym, "SELL", 60, 260.0, tag = "iraalgostrat")    // another book's sale covers 60 of the 90 held
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                       // the ORB's target
        kite.afterNext("/orders/regular/$s1") { kite.down += "/orders" }   // the order book fails from the stop's cancel on
        kite.requests.clear()
        pass()
        kite.down.clear()
        assertTrue("nothing sold", marketSells().isEmpty())
        val back = kite.placed.single { it.form["order_type"] == "SL" }
        assertEquals("30", back.form["quantity"])
        val open = view().single { it.arm.source == "orb" }.today.filter { it.open }
        assertEquals(listOf(30, 30), open.map { it.qty }.sorted())
        assertEquals("the stop is the covered part's", 1, open.count { it.stopOrderId == kite.orders.keys.last() })
    }

    /**
     * 19 Nov 2026 (second review, coordinator's decision: main's behaviour): from 15:10 an exit sale Zerodha refuses puts the stop back at
     * once, for the whole trade - a lot is never left with no stop because a sale was refused (the next pass takes it out again to sell).
     */
    @Test fun fromTheSquareOffARefusedExitPutsTheStopBack() {
        OrbArms.testNow = OrbArms.testNow!!.with(java.time.LocalTime.of(15, 12))
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        kite.nextPlace(reply = FakeKite.Reply.INPUT_EXCEPTION, message = "Markets are closed right now.")
        kite.requests.clear()
        pass()
        assertEquals("CANCELLED", kite.order(s1).status)
        val back = kite.placed.single { it.form["order_type"] == "SL" }
        assertEquals("30", back.form["quantity"])
        assertEquals(kite.orders.keys.last(), open("orb")!!.stopOrderId)
        assertEquals("TRIGGER PENDING", kite.order(open("orb")!!.stopOrderId!!).status)
    }

    /**
     * 19 Nov 2026 (review): a 15:10 sale refused: its stop is put back, and not taken out and sold again on every look - the next try
     * waits 45 s (the stop rests meanwhile).
     */
    @Test fun aRefusedSessionEndSaleIsTriedAgainOnlyAfterABackOff() {
        OrbArms.testNow = OrbArms.testNow!!.with(java.time.LocalTime.of(15, 12))
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        kite.nextPlace(reply = FakeKite.Reply.INPUT_EXCEPTION, message = "Markets are closed right now.")
        pass()
        val back = open("orb")!!.stopOrderId!!
        assertEquals("TRIGGER PENDING", kite.order(back).status)
        kite.requests.clear()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(15)
        pass()
        assertTrue("nothing cancelled or sold 15 s later: ${kite.requests.filter { it.method != "GET" }.map { it.method + " " + it.path }}",
            kite.requests.none { it.method == "DELETE" } && marketSells().isEmpty())
        assertEquals("TRIGGER PENDING", kite.order(back).status)
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(40)
        pass()
        assertEquals("tried again after the back-off", "CANCELLED", kite.order(back).status)
        assertEquals(1, marketSells().size)
    }

    /** 19 Nov 2026 (second review): with Boss's Stop for today on, a refused exit sale puts the stop back too (as main did). */
    @Test fun withStopForTodayARefusedExitPutsTheStopBack() {
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        kite.nextPlace(reply = FakeKite.Reply.INPUT_EXCEPTION, message = "Markets are closed right now.")
        kite.requests.clear()
        pass()
        assertEquals("the stop came out for the sale", "CANCELLED", kite.order(s1).status)
        assertEquals("the sale was tried", listOf("30"), marketSells().map { it.form["quantity"] })
        val back = kite.placed.single { it.form["order_type"] == "SL" }
        assertEquals("30", back.form["quantity"]); assertEquals("160.00", back.form["trigger_price"])
        val p = open("orb")!!
        assertEquals(kite.orders.keys.last(), p.stopOrderId)
        assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
    }

    /**
     * 19 Nov 2026 (second review): an unconfirmed row on the contract (an entry written down before its order went) never counts as the
     * app's lots when deciding whether Boss's own sell may be cancelled: Zerodha holds Boss's own lot beside the arm's, so it is left alone.
     */
    @Test fun anUnconfirmedRowNeverLetsTheAppCancelBosssSell() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position("orb", 30, 200.0, "E1", s1, 160.0),
            position("orb", 30, 200.0, entryId = null, stopId = null, stop = null).put("arm", "orb_fresh").put("unconfirmed", true)))
        kite.position(sym, 60, 200.0, product = "MIS")                      // the arm's lot and Boss's own
        val bosss = kite.restingInKite(sym, "SELL", 60, 250.0)
        kite.quote("NFO:$sym", 150.0, 149.95, 150.05)                       // through the stop's limit
        kite.requests.clear()
        pass()
        assertEquals("Boss's order left alone", "OPEN", kite.order(bosss).status)
        assertEquals("TRIGGER PENDING", kite.order(s1).status)
        assertTrue("nothing cancelled, nothing sold: ${kite.requests.filter { it.method != "GET" }.map { it.method + " " + it.path }}",
            kite.requests.none { it.method == "DELETE" } && marketSells().isEmpty())
    }

    // ---- B3: the resting stop never sells more than Zerodha holds ------------------------------------------------------------

    /** Two lots held with one stop for both; the arms' live copies' book read (they hold none of it). */
    private fun twoLots(): String {
        val s1 = stopOrder(60, 160.0, 152.0)
        state(listOf(position("orb", 60, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.dayBought["NFO:$sym:MIS"] = 60
        runBlocking { ArmLive.refresh() }
        kite.quote("NFO:$sym", 210.0, 209.95, 210.05)                       // no exit due
        kite.requests.clear()
        return s1
    }

    /**
     * 18 Nov 2026 (audit: B3): Boss sells one of the trade's two lots in Kite: Zerodha holds 30 of the 60 the trade has, and his sale is in
     * the order book - its resting stop is modified down to 30 and the trade split: 30 booked closed by him, 30 kept with its stop.
     */
    @Test fun aLotSoldInKiteShrinksTheStopToWhatIsHeldAndSplitsTheTrade() {
        val s1 = twoLots()
        kite.soldInKite(sym, 30, 211.0)
        kite.requests.clear()
        pass()
        assertEquals("the same stop, still resting", "TRIGGER PENDING", kite.order(s1).status)
        assertEquals("now for what is held", 30, kite.order(s1).qty)
        assertEquals(160.0, kite.order(s1).trigger, 1e-9)
        assertTrue("nothing sold", kite.placed.isEmpty())
        val kept = open("orb")!!
        assertEquals(30, kept.qty); assertEquals(s1, kept.stopOrderId)
        val closed = today("orb").single { !it.open }
        assertEquals(30, closed.qty); assertEquals("closed_by_you", closed.why)
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "Zerodha holds 30 of $sym" in it })
    }

    /**
     * 19 Nov 2026 (second review): a strategy leg traded the same contract today (its sale is in the order book): the reads agreeing for
     * a while never cut the arm's trade or its stop - only a sale outside the app covering the difference would.
     */
    @Test fun aStrategyLegOnTheContractNeverLetsTheTimerShrinkTheStop() {
        val s1 = twoLots()
        kite.soldInKite(sym, 30, 211.0, tag = "iraalgostrat")              // a strategy's leg sells 30 of the same contract
        pass()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(31)
        pass()
        OrbArms.testNow = OrbArms.testNow!!.plusMinutes(6)
        pass()
        assertEquals("the stop untouched", 60, kite.order(s1).qty)
        assertEquals("TRIGGER PENDING", kite.order(s1).status)
        assertEquals(60, open("orb")!!.qty)
        assertTrue(today("orb").none { !it.open })
    }

    /**
     * 19 Nov 2026 (second review): Boss sells one of the two lots from the app's own Trade screen ("iraalgo", a hand order): that sale is
     * the proof, as one in Kite is - the stop is shrunk to what is held at once, never held back as another book's trade.
     */
    @Test fun aLotSoldFromTheAppsTradeScreenShrinksTheStopAsOneSoldInKite() {
        val s1 = twoLots()
        kite.soldInKite(sym, 30, 211.0, tag = "iraalgo")
        pass()
        assertEquals("TRIGGER PENDING", kite.order(s1).status)
        assertEquals("now for what is held", 30, kite.order(s1).qty)
        assertEquals(30, open("orb")!!.qty)
        assertEquals("closed_by_you", today("orb").single { !it.open }.why)
    }

    /** 18 Nov 2026 (audit: B3): one read showing less with no sale anywhere changes nothing; five minutes of reads agreeing do. */
    @Test fun oneReadShowingLessNeverShrinksTheStop() {
        val s1 = twoLots()
        kite.position(sym, 30, 200.0, product = "MIS")                      // a short read: no sale in the order book
        pass()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(31)
        pass()
        assertEquals(60, kite.order(s1).qty)
        assertEquals(60, open("orb")!!.qty)
        assertTrue("nothing sent: ${kite.writes.map { it.method + " " + it.path }}", kite.writes.isEmpty())
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(270)               // five minutes of reads agreeing
        pass()
        assertEquals(30, kite.order(s1).qty)
        assertEquals(30, open("orb")!!.qty)
    }

    /**
     * 18 Nov 2026 (audit: B3): a stop Zerodha will not reduce is taken out (never a resting sell for more than is held) and placed again
     * for what is held on the next check.
     */
    @Test fun aStopZerodhaWillNotReduceIsTakenOutAndPlacedAgainForWhatIsHeld() {
        val s1 = twoLots()
        kite.soldInKite(sym, 30, 211.0)
        kite.modifyRefusals = 1
        pass()
        assertEquals("CANCELLED", kite.order(s1).status)
        val kept = open("orb")!!
        assertEquals(30, kept.qty); assertEquals(null, kept.stopOrderId)
        pass()
        val again = kite.placed.single { it.form["order_type"] == "SL" }
        assertEquals("30", again.form["quantity"]); assertEquals("160.00", again.form["trigger_price"])
        assertEquals(kite.orders.keys.last(), open("orb")!!.stopOrderId)
    }

    // ---- second review: a stop of these arms resting unclaimed is taken, never a second beside it ------------------------------

    /**
     * 19 Nov 2026 (second review): a trade with no stop of its own while a stop of these arms rests on its contract since its entry, no
     * trade claiming it (placed just before the app stopped): the price check takes that stop - raised to the trade's own level - and
     * places no second one.
     */
    @Test fun theReRestTakesAnUnclaimedStopRaisedToTheTradesLevelNeverASecond() {
        state(listOf(position("orb", 30, 200.0, "E1", stopId = null, stop = 160.0)))
        val orphan = stopOrder(30, 150.0, 142.0)                            // its stop, lower than its level, no trade holding its id
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.dayBought["NFO:$sym:MIS"] = 30
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        kite.requests.clear()
        pass()
        assertTrue("no second stop: ${kite.placed.map { it.form }}", kite.placed.isEmpty())
        val p = open("orb")!!
        assertEquals(orphan, p.stopOrderId)
        assertEquals("raised to its level", 160.0, kite.order(orphan).trigger, 1e-9)
        assertEquals(160.0, p.stopTrigger!!, 1e-9)
        assertEquals("TRIGGER PENDING", kite.order(orphan).status)
    }

    /** 19 Nov 2026 (second review): a stop labelled for another arm is never taken: this trade gets its own, the other's left alone. */
    @Test fun theReRestNeverTakesAStopLabelledForAnotherArm() {
        state(listOf(position("orb", 30, 200.0, "E1", stopId = null, stop = 160.0)))
        val theirs = stopOrder(30, 160.0, 152.0)
        runBlocking { Strategies.tagOwner("kite:$theirs", "ORB Fresh · stop") }
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.dayBought["NFO:$sym:MIS"] = 60
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        kite.requests.clear()
        pass()
        assertEquals("the other arm's stop untouched", "TRIGGER PENDING", kite.order(theirs).status)
        assertEquals(30, kite.order(theirs).qty)
        val own = kite.placed.single { it.form["order_type"] == "SL" }
        assertEquals("30", own.form["quantity"])
        assertEquals(kite.orders.keys.last(), open("orb")!!.stopOrderId)
    }

    /** 19 Nov 2026 (second review): two unclaimed stops of these arms on the contract: neither is taken and no third is placed. */
    @Test fun theReRestPlacesNothingBesideTwoUnclaimedStops() {
        state(listOf(position("orb", 30, 200.0, "E1", stopId = null, stop = 160.0)))
        val a = stopOrder(30, 160.0, 152.0)
        val c = stopOrder(30, 160.0, 152.0)
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        kite.requests.clear()
        pass()
        assertTrue("nothing placed", kite.placed.isEmpty())
        assertEquals(null, open("orb")!!.stopOrderId)
        assertEquals("TRIGGER PENDING", kite.order(a).status); assertEquals("TRIGGER PENDING", kite.order(c).status)
    }

    // ---- coordinator, 19 Nov: a stop never sells more than is held beside the other working sales ---------------------------

    /**
     * 19 Nov 2026 (coordinator): 90 held, another book's sale for 60 working, the trade's exit for the 30 left refused: its stop comes back
     * for 30 (never 90 beside the 60), the trade split so the stop's fill is booked for what it covers.
     */
    @Test fun aRefusedExitPutsTheStopBackOnlyForWhatTheOtherSalesLeave() {
        val s1 = stopOrder(90, 160.0, 152.0)
        state(listOf(position("orb", 90, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 90, 200.0, product = "MIS")
        val other = kite.restingInKite(sym, "SELL", 60, 260.0, tag = "iraalgostrat")
        runBlocking { Strategies.tagOwner("kite:$other", "Iron fly · exit") }
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                       // the ORB's target
        kite.nextPlace(reply = FakeKite.Reply.INPUT_EXCEPTION, message = "Markets are closed right now.")
        kite.requests.clear()
        pass()
        assertEquals("the sale tried was the 30 left", listOf("30"), marketSells().map { it.form["quantity"] })
        val back = kite.placed.single { it.form["order_type"] == "SL" }
        assertEquals("never more than the other sale leaves", "30", back.form["quantity"])
        val open = today("orb").filter { it.open }
        assertEquals(listOf(30, 60), open.map { it.qty }.sorted())
        assertEquals(kite.orders.keys.last(), open.single { it.qty == 30 }.stopOrderId)
    }

    /**
     * 19 Nov 2026 (coordinator): the price check rests a missing stop only for what Zerodha holds less the other working sales; with
     * nothing left it rests none and says so each minute (the other sales are the only cover).
     */
    @Test fun theReRestNeverRestsAStopForMoreThanTheOtherSalesLeave() {
        OrbArms.testNow = OrbArms.testNow!!.withSecond(0).withNano(0)      // on a whole minute: +20 s stays in it, +80 s is the next
        state(listOf(position("orb", 60, 200.0, "E1", stopId = null, stop = 160.0)))
        kite.position(sym, 60, 200.0, product = "MIS")
        val other = kite.restingInKite(sym, "SELL", 60, 260.0, tag = "iraalgostrat")
        runBlocking { Strategies.tagOwner("kite:$other", "Iron fly · exit") }
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        kite.requests.clear()
        pass()
        assertTrue("no stop beside the sale that covers all: ${kite.placed.map { it.form }}", kite.placed.isEmpty())
        assertTrue(AutomationSupport.alerts().toString(), AutomationSupport.alerts().any { "no stop can rest at Zerodha for $sym" in it })
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(20)
        pass()
        assertEquals("said once a minute", 1, AutomationSupport.alerts().count { "no stop can rest at Zerodha for $sym" in it })
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(60)
        pass()
        assertEquals("and again the next minute", 2, AutomationSupport.alerts().count { "no stop can rest at Zerodha for $sym" in it })
    }

    /** 19 Nov 2026 (coordinator): 90 held beside another book's sale for 60: the missing stop rests for 30 and the trade is split. */
    @Test fun theReRestRestsForWhatTheOtherSalesLeaveAndSplitsTheTrade() {
        state(listOf(position("orb", 60, 200.0, "E1", stopId = null, stop = 160.0)))
        kite.position(sym, 90, 200.0, product = "MIS")
        val other = kite.restingInKite(sym, "SELL", 60, 260.0, tag = "iraalgostrat")
        runBlocking { Strategies.tagOwner("kite:$other", "Iron fly · exit") }
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        kite.requests.clear()
        pass()
        assertEquals("30", kite.placed.single { it.form["order_type"] == "SL" }.form["quantity"])
        val open = today("orb").filter { it.open }
        assertEquals(listOf(30, 30), open.map { it.qty }.sorted())
        assertEquals(kite.orders.keys.last(), open.first { it.stopOrderId != null }.stopOrderId)
    }

    // ---- final check, 19 Nov: the re-rest's back-off, the cap on a stop taken, stops placed earlier in the pass ----------------

    /**
     * 19 Nov 2026 (final check): nothing left to cover (another book's sale and an unclaimed stop of another quantity - never taken - cover
     * all held): the re-rest counts no try, so its "no stop can rest" word really comes once each minute.
     */
    @Test fun nothingLeftToCoverIsSaidEachMinuteBesideAStopOfAnotherQuantity() {
        OrbArms.testNow = OrbArms.testNow!!.withSecond(0).withNano(0)
        state(listOf(position("orb", 60, 200.0, "E1", stopId = null, stop = 160.0)))
        kite.position(sym, 60, 200.0, product = "MIS")
        val other = kite.restingInKite(sym, "SELL", 30, 260.0, tag = "iraalgostrat")
        runBlocking { Strategies.tagOwner("kite:$other", "Iron fly · exit") }
        val stray = stopOrder(60, 150.0, 142.0)                            // unclaimed, for 60: never taken for this trade's 30 left
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        val said = { AutomationSupport.alerts().count { "no stop can rest at Zerodha for $sym" in it } }
        kite.requests.clear()
        pass()
        assertEquals(1, said())
        assertTrue("nothing placed", kite.placed.isEmpty())
        assertEquals("the other quantity's stop left alone", 60, kite.order(stray).qty)
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(20)
        pass()
        assertEquals("once in the minute", 1, said())
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(60)
        pass()
        assertEquals("again the next minute", 2, said())
    }

    /**
     * 19 Nov 2026 (final check): an unclaimed stop of these arms is taken for no more than is held less the other working sales - never
     * grown past it - and the trade split when that is less.
     */
    @Test fun anUnclaimedStopIsNeverGrownPastWhatTheOtherSalesLeave() {
        val buy = kite.boughtInKite(sym, 60, 200.0, tag = "iraorb")       // the entry's own order: its Zerodha time is known
        state(listOf(position("orb", 60, 200.0, buy, stopId = null, stop = 160.0)))
        kite.dayBought["NFO:$sym:MIS"] = 60
        val other = kite.restingInKite(sym, "SELL", 30, 260.0, tag = "iraalgostrat")
        runBlocking { Strategies.tagOwner("kite:$other", "Iron fly · exit") }
        val stray = stopOrder(15, 160.0, 152.0)
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        kite.requests.clear()
        pass()
        assertEquals("grown only to the 30 the other sale leaves", 30, kite.order(stray).qty)
        assertTrue("nothing new placed", kite.placed.isEmpty())
        val open = today("orb").filter { it.open }
        assertEquals(listOf(30, 30), open.map { it.qty }.sorted())
        assertEquals(stray, open.single { it.stopOrderId != null }.stopOrderId)
    }

    /**
     * 19 Nov 2026 (final check): two trades on one contract both missing their stops, another book's sale covering half of what is held:
     * the first stop placed this pass is counted for the second - one stop, never two beside the other sale.
     */
    @Test fun aStopPlacedEarlierInThePassCountsForTheNextTradesCap() {
        state(listOf(position("orb", 30, 200.0, "E1", stopId = null, stop = 160.0),
            position("orb_fresh", 30, 200.0, "E2", stopId = null, stop = 160.0).put("entryTime", ago(20).toString())))
        kite.position(sym, 60, 200.0, product = "MIS")
        val other = kite.restingInKite(sym, "SELL", 30, 260.0, tag = "iraalgostrat")
        runBlocking { Strategies.tagOwner("kite:$other", "Iron fly · exit") }
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        kite.requests.clear()
        pass()
        assertEquals("one stop for the 30 the other sale leaves", listOf("30"), kite.placed.filter { it.form["order_type"] == "SL" }.map { it.form["quantity"] })
    }

    /**
     * 19 Nov 2026 (final check): a square-off's exit went on the contract a moment ago (the pass's order read does not show it yet): the
     * re-rest puts no stop beside it while its gap lasts.
     */
    @Test fun noStopIsRestedInTheGapAfterASquareOffsExit() {
        SquareOffExit.resetForTest()                                       // the app's own gap (the test default is none)
        state(listOf(position("orb", 30, 200.0, "E1", stopId = null, stop = 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 205.0, 204.95, 205.05)
        SquareOffExit.notePlaced("NFO", sym, "MIS")
        kite.requests.clear()
        try {
            pass()
            assertTrue("no stop in the gap: ${kite.placed.map { it.form }}", kite.placed.none { it.form["order_type"] == "SL" })
        } finally { SquareOffExit.resetForTest(gap = 0L) }
        pass()
        assertEquals("rested once the gap is over (none here)", 1, kite.placed.count { it.form["order_type"] == "SL" })
    }

    // ---- 20 Nov 2026 (audit PC): a double sell after an exit whose reply was lost ---------------------------------------------------

    /**
     * 20 Nov 2026 (audit PC): with Boss's positions-lag switch in shadow (its default), an earlier sale of the lot that filled while
     * Zerodha's positions still trail it is never sold a second time by Liquidity's own exit (the guard acts for these exits whatever its
     * switch says, as for R51 / R72 since 18 Nov).
     */
    @Test fun evenWithTheLagGuardInShadowALiquidityExitNeverSellsAFilledSaleAgain() {
        ExitCoordinator.resetForTest(lagOn = false)
        try {
            val s1 = stopOrder(30, 170.0, 161.5)
            state(listOf(liquidity(30, 200.0, s1, 170.0)))                 // its time stop is due
            kite.position(sym, 30, 200.0, product = "MIS")
            kite.positionsTrailReads = 50                                  // the positions trail the next fill
            runBlocking { Broker.placeOrder(Kite.Order(sym, Kite.Side.SELL, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", "iraorb"), exit = true) }
            kite.requests.clear()
            pass()
            assertTrue("no second market sell: ${marketSells().map { it.form }}", marketSells().isEmpty())
            assertTrue("no stop rested on the lot already sold", kite.placed.none { it.form["order_type"] == "SL" })
            assertTrue("Boss's own switch untouched", !ExitCoordinator.isLagOn())
        } finally { ExitCoordinator.resetForTest() }
    }

    /** The Liquidity exit of a lot whose sale lost its reply (the order book does not list it yet, the positions trail it). */
    private fun lostLiquiditySale(reply: FakeKite.Reply = FakeKite.Reply.DROP_AFTER_ACCEPT, outcome: FakeKite.Outcome = FakeKite.Outcome.FILL): String {
        val s1 = stopOrder(30, 170.0, 161.5)
        state(listOf(liquidity(30, 200.0, s1, 170.0)))                     // its time stop is due
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.positionsTrailReads = 1_000
        kite.trailReads = 1_000
        kite.nextPlace(reply = reply, outcome = outcome)
        kite.requests.clear()
        pass()
        assertEquals("its stop came out first", "CANCELLED", kite.order(s1).status)
        assertEquals("one sale sent", 1, marketSells().size)
        assertTrue("held on its contract", OrbArms.lostExitForTest(sym))
        assertTrue(AutomationSupport.alerts().joinToString(), AutomationSupport.alerts().any { "got no reply from Zerodha" in it })
        return s1
    }

    /**
     * 20 Nov 2026 (audit PC): Liquidity's exit sale lost its reply and was not found at once: within its hold nothing more is sold and no
     * stop is put back on the contract; past it the order book is searched first - the sale found there is the trade's exit.
     */
    @Test fun aLostLiquidityExitIsHeldThenFoundAndBookedNeverSoldTwice() {
        lostLiquiditySale()
        pass(); pass()
        assertEquals("nothing more sold within the hold", 1, marketSells().size)
        assertTrue("no stop rested beside it", kite.placed.none { it.form["order_type"] == "SL" })
        assertNotNull("still open meanwhile", open("liquidity"))
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000 + 1)
        kite.showInBook()                                                  // the order book lists the sale now
        pass(); searchDone(); pass()                                       // the look starts the search; the next acts on it
        assertEquals("still one sale", 1, marketSells().size)
        assertTrue("no stop rested", kite.placed.none { it.form["order_type"] == "SL" })
        val done = today("liquidity").single()
        assertEquals("booked as its exit", "time_stop", done.why)
        assertEquals(30, done.qty)
        assertTrue(!OrbArms.lostExitForTest(sym))
    }

    /**
     * 20 Nov 2026 (review: a found lost sale is never awaited under the lock): found still working (a market sale's protection left it
     * resting), its rest is cancelled once, in the background; the next looks book what filled - nothing - and the exit goes again.
     */
    @Test fun aFoundLostExitStillWorkingIsCancelledInTheBackgroundThenTheExitGoesAgain() {
        lostLiquiditySale(outcome = FakeKite.Outcome.OPEN)
        kite.requests.clear()                                              // (its stop's own cancel is not counted below)
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000 + 1)
        kite.showInBook()
        pass(); searchDone(); pass()                                       // the look starts the search; the next acts on it
        assertNotNull("not booked while it works", open("liquidity"))
        assertEquals("nothing more sold", 0, marketSells().size)
        val end = System.currentTimeMillis() + 10_000
        fun cancels() = kite.requests.count { it.method == "DELETE" && it.path.startsWith("/orders/") }
        while (cancels() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals("its rest cancelled once, in the background", 1, cancels())
        pass(); pass()
        assertTrue(!OrbArms.lostExitForTest(sym))
        assertEquals("the exit went again, once, for what is still held", 1, marketSells().size)
        assertEquals("time_stop", today("liquidity").single().why)
    }

    /** Waits (wall clock, at most 10 s) until no background search of a lost exit on [sym] runs. */
    private fun searchDone() {
        val end = System.currentTimeMillis() + 10_000
        while (OrbArms.lostSearchingForTest(sym) && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("the background search ended", !OrbArms.lostSearchingForTest(sym))
    }

    /**
     * 20 Nov 2026 (deep review F1; coordinator: no network on the tick path): the first fast look past the hold only starts a background
     * search and returns; its answer, kept in memory, is booked by the next look from that look's own order book - never awaited.
     */
    @Test fun aFastLookPastTheHoldStartsABackgroundSearchAndTheNextLookBooksIt() {
        lostLiquiditySale()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000 + 1)
        kite.showInBook()
        runBlocking { OrbArms.priceCheckOnly(fast = true) }
        assertNotNull("not booked by the look that started the search", open("liquidity"))
        searchDone()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(1)
        runBlocking { OrbArms.priceCheckOnly(fast = true) }
        assertEquals("booked by the next look", "time_stop", today("liquidity").single().why)
        assertEquals("never sold twice", 1, marketSells().size)
        assertTrue(!OrbArms.lostExitForTest(sym))
    }

    /**
     * 20 Nov 2026 (coordinator: nothing on the tick or stream path touches the network or blocks): a stream look past the hold never makes
     * the order-book search itself - with the search held up, the look still returns at once, the search running in the background.
     */
    @Test fun aStreamLookNeverMakesTheSearchItself() {
        lostLiquiditySale()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000 + 1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val searches = java.util.concurrent.atomic.AtomicInteger()
        OrbArms.testLostSearch = { searches.incrementAndGet(); gate.await(30, java.util.concurrent.TimeUnit.SECONDS) }
        try {
            val look = Thread { runBlocking { OrbArms.priceCheckOnly(fast = true, stream = true) } }.apply { start() }
            look.join(5_000)
            assertTrue("the stream look returned while the search is held up", !look.isAlive)
            val end = System.currentTimeMillis() + 5_000
            while (searches.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
            assertEquals("one search, in the background", 1, searches.get())
            assertTrue(OrbArms.lostSearchingForTest(sym))
            runBlocking { OrbArms.priceCheckOnly(fast = true, stream = true) }
            assertEquals("never a second search while one runs", 1, searches.get())
            assertEquals("nothing more sold meanwhile", 1, marketSells().size)
        } finally { gate.countDown(); OrbArms.testLostSearch = null }
        searchDone()
    }

    /**
     * 20 Nov 2026 (deep review F1): the price falls through the stop while the owner's lost sale (which never reached Zerodha) is held:
     * nothing more is sold within the hold; the first fast look past it starts the search, and the next look - a second later - sells:
     * the gap from the hold's end to the sale stays within a few seconds.
     */
    @Test fun aPriceThroughTheStopDuringTheHoldIsSearchedAndSoldPromptly() {
        lostLiquiditySale(FakeKite.Reply.NETWORK_EXCEPTION)                // nothing reached Zerodha
        kite.positionsTrailReads = 0; kite.trailReads = 0
        kite.quote("NFO:$sym", 150.0, 149.95, 150.05)                      // through its 170 stop
        runBlocking { OrbArms.priceCheckOnly(fast = true) }
        pass()
        assertEquals("held within the hold", 1, marketSells().size)
        val holdEnd = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000)
        OrbArms.testNow = holdEnd.plusSeconds(1)
        runBlocking { OrbArms.priceCheckOnly(fast = true) }
        searchDone()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(1)
        runBlocking { OrbArms.priceCheckOnly(fast = true) }
        assertTrue(!OrbArms.lostExitForTest(sym))
        assertEquals("searched, not found, sold", 2, marketSells().size)
        assertTrue(today("liquidity").single().exit != null)
        val gap = java.time.Duration.between(holdEnd, OrbArms.testNow!!).seconds
        assertTrue("sold $gap s after the hold ended", gap in 0..6)
    }

    /**
     * 20 Nov 2026 (deep review F1): during one book's lost-exit hold, another book's trade on the same contract with no Zerodha stop (Boss
     * cancelled it in Kite: stopByYou) is still watched at full cadence and sold at its level - only the lost sale's own part is held.
     */
    @Test fun anotherBooksStopByYouTradeIsStillSoldAtItsLevelDuringTheHold() {
        val s1 = stopOrder(30, 170.0, 161.5)
        val other = position("liquidity15", 30, 200.0, "E2", null, 170.0).put("level", 52_050.0).put("lot", 30).put("stopByYou", true)
            .put("entryTime", ago(3).toString()).put("signalBar", ago(15).toString())
        state(listOf(liquidity(30, 200.0, s1, 170.0), other))              // the 5-minute book's time stop is due; the 15-minute one's not
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.positionsTrailReads = 1_000
        kite.trailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        kite.requests.clear()
        pass()
        assertTrue("the 5-minute book's sale was lost and is held", OrbArms.lostExitForTest(sym))
        assertEquals(1, marketSells().size)
        assertTrue("the other is still open", today("liquidity").any { it.arm == "liquidity15" && it.open })
        // Within the hold the price reaches the other's 170 level: it is sold there, at once.
        kite.quote("NFO:$sym", 165.0, 164.95, 165.05)
        runBlocking { OrbArms.priceCheckOnly(fast = true) }
        assertEquals("the other book's sale went within the hold", 2, marketSells().size)
        assertEquals("30", marketSells().last().form["quantity"])
        val done = today("liquidity").single { it.arm == "liquidity15" }
        assertEquals("stop", done.why)
        assertTrue("the lost sale is still held for its own part", OrbArms.lostExitForTest(sym))
    }

    /**
     * 20 Nov 2026 (review; deep review F1): a lost exit whose search cannot read Zerodha's order book holds its part for at most
     * [OrbArms.LOST_EXIT_TRIES] searches [OrbArms.LOST_EXIT_ASK_MS] apart after the hold, then is let go and said - the gap stays bounded.
     */
    @Test fun aLostExitWhoseBookCannotBeReadIsLetGoAfterItsTries() {
        lostLiquiditySale()
        val lostAt = OrbArms.testNow!!
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000 + 1)
        kite.down += "/orders"
        try {
            repeat(OrbArms.LOST_EXIT_TRIES - 1) { n ->
                assertEquals("try ${n + 1}: held", "hold", runBlocking { OrbArms.lostExitLookForTest(sym) })
                assertEquals("asked again only after a pause", "hold", runBlocking { OrbArms.lostExitLookForTest(sym) })
                OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_ASK_MS / 1000 + 1)
            }
            assertEquals("let go", "none", runBlocking { OrbArms.lostExitLookForTest(sym) })
            assertTrue(!OrbArms.lostExitForTest(sym))
            val gapS = java.time.Duration.between(lostAt, OrbArms.testNow!!).seconds
            assertTrue("bounded: $gapS s", gapS <= OrbArms.LOST_EXIT_HOLD_MS / 1000 + OrbArms.LOST_EXIT_TRIES * (OrbArms.LOST_EXIT_ASK_MS / 1000 + 1) + 1)
            assertTrue(AutomationSupport.alerts().joinToString(), AutomationSupport.alerts().any {
                "lost exit of $sym was not found: Zerodha's order book could not be read (${OrbArms.LOST_EXIT_TRIES} tries)" in it })
        } finally { kite.down.clear() }
    }

    /**
     * 20 Nov 2026 (deep review, plausible 2): on a split trade only the part that sent the lost sale is held and takes it (capped at that
     * part): the other part of the same trade (another quantity) sells its own lots at once, and the lost sale is booked on its own part.
     */
    @Test fun onASplitTradeOnlyThePartThatSentTheLostSaleTakesIt() {
        val s1 = stopOrder(30, 170.0, 161.5)
        val rest = position("liquidity5", 60, 200.0, "E1", null, null).put("level", 52_050.0).put("lot", 30)
        state(listOf(liquidity(30, 200.0, s1, 170.0), rest))               // one trade split in two parts; both time stops due
        kite.position(sym, 90, 200.0, product = "MIS")
        kite.positionsTrailReads = 1_000
        kite.trailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        kite.requests.clear()
        pass()
        assertTrue("the 30's sale was lost and is held", OrbArms.lostExitForTest(sym))
        assertEquals("the other part sold its own 60 at once", listOf("30", "60"), marketSells().map { it.form["quantity"] })
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000 + 1)
        kite.showInBook()
        pass(); searchDone(); pass()                                       // the look starts the search; the next acts on it
        assertEquals("never sold again", 2, marketSells().size)
        val parts = today("liquidity")
        assertEquals(listOf(30, 60), parts.map { it.qty }.sorted())
        assertTrue(parts.toString(), parts.all { it.exit != null && it.why == "time_stop" })
        assertTrue(!OrbArms.lostExitForTest(sym))
    }

    /**
     * 20 Nov 2026 (final review C1): a search in flight never holds the arms' lock - a regular look and a stream look both return at once,
     * and another book's trade on the contract is sold at its level meanwhile.
     */
    @Test fun looksRunPromptlyWhileASearchIsInFlight() {
        val s1 = stopOrder(30, 170.0, 161.5)
        val other = position("liquidity15", 30, 200.0, "E2", null, 170.0).put("level", 52_050.0).put("lot", 30).put("stopByYou", true)
            .put("entryTime", ago(3).toString()).put("signalBar", ago(15).toString())
        state(listOf(liquidity(30, 200.0, s1, 170.0), other))
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.positionsTrailReads = 1_000
        kite.trailReads = 1_000
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        pass()
        assertTrue(OrbArms.lostExitForTest(sym))
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000 + 1)
        val gate = java.util.concurrent.CountDownLatch(1)
        OrbArms.testLostSearch = { gate.await(30, java.util.concurrent.TimeUnit.SECONDS) }
        try {
            val regular = Thread { pass() }.apply { start() }
            regular.join(5_000)
            assertTrue("the regular look returned while the search is held up", !regular.isAlive)
            assertTrue("the search is in flight", OrbArms.lostSearchingForTest(sym))
            kite.quote("NFO:$sym", 165.0, 164.95, 165.05)                  // the other trade's 170 level
            val stream = Thread { runBlocking { OrbArms.priceCheckOnly(fast = true, stream = true) } }.apply { start() }
            stream.join(5_000)
            assertTrue("the stream look returned while the search is held up", !stream.isAlive)
            assertEquals("the other book's trade sold at its level meanwhile", "stop", today("liquidity").single { it.arm == "liquidity15" }.why)
            assertTrue("the search is still in flight", OrbArms.lostSearchingForTest(sym))
        } finally { gate.countDown(); OrbArms.testLostSearch = null }
        searchDone()
    }

    /**
     * 20 Nov 2026 (final review C2): book A's lost sale has filled (Zerodha's positions show it, its order book not yet); book B's exit falls
     * due with its stop resting. Everything B could sell is covered by A's unseen sale: B's stop stays resting - never taken out with
     * nothing sold.
     */
    @Test fun anotherBooksRestingStopStaysWhenALostSaleCoversTheLot() {
        val s1 = stopOrder(30, 170.0, 161.5)
        val s2 = stopOrder(30, 170.0, 161.5)
        val b = position("liquidity15", 30, 200.0, "E2", s2, 170.0).put("level", 52_050.0).put("lot", 30)
        state(listOf(liquidity(30, 200.0, s1, 170.0), b))                  // both time stops due
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.positionsTrailReads = 0                                       // the positions show A's fill at once
        kite.trailReads = 1_000                                            // its order book does not
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        kite.requests.clear()
        pass()
        assertTrue("A's sale lost and held", OrbArms.lostExitForTest(sym))
        assertEquals("only A's sale went", 1, marketSells().size)
        assertTrue("B's stop still resting: ${kite.order(s2).status}", kite.order(s2).status !in setOf("CANCELLED", "COMPLETE", "REJECTED"))
        val bNow = today("liquidity").single { it.arm == "liquidity15" }
        assertTrue("B still open, its stop on it", bNow.open && bNow.stopOrderId == s2)
    }

    /**
     * A's time-stop sale lost (its search then held up) and an earlier labelled sale of 30 that filled while Zerodha's positions still
     * show 60 (they lag every fill); then, at [at] (or with Stop for today, [stopForToday]), B squares off with its stop resting. Returns
     * B's stop and the search's gate (count it down to let the search answer).
     */
    private fun pendingLostAndLaggingPositions(at: java.time.LocalTime?, stopForToday: Boolean = false): Pair<String, java.util.concurrent.CountDownLatch> {
        val s1 = stopOrder(30, 170.0, 161.5)
        val s2 = stopOrder(30, 170.0, 161.5)
        val b = position("liquidity15", 30, 200.0, "E2", s2, 170.0).put("level", 52_050.0).put("lot", 30)
            .put("entryTime", ago(3).toString()).put("signalBar", ago(15).toString())
        state(listOf(liquidity(30, 200.0, s1, 170.0), b))                  // A's time stop is due; B's is not
        kite.position(sym, 60, 200.0, product = "MIS")
        kite.positionsTrailReads = 1_000                                   // the positions trail every fill below
        kite.trailReads = 1_000                                            // A's sale never shows in the order book
        kite.nextPlace(reply = FakeKite.Reply.DROP_AFTER_ACCEPT)
        pass()
        assertTrue("A's sale lost and held", OrbArms.lostExitForTest(sym))
        val earlier = runBlocking { Broker.placeOrder(Kite.Order(sym, Kite.Side.SELL, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", "iraorb"), exit = true) }
        runBlocking { Strategies.tagOwner("kite:$earlier", "Liquidity 30m FINNIFTY · square_off") }
        val gate = java.util.concurrent.CountDownLatch(1)
        OrbArms.testLostSearch = { gate.await(30, java.util.concurrent.TimeUnit.SECONDS) }
        if (at != null) OrbArms.testNow = now.toLocalDate().atTime(at).atZone(com.optionslab.engine.IST)
        else OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000 + 1)
        if (stopForToday) runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        kite.requests.clear()
        return s2 to gate
    }

    /**
     * 20 Nov 2026 (final review: a short-sale case): at 15:10 B's stop came out and only A's pending lost sale left nothing to sell, but
     * Zerodha's positions lag a fill - so A's sale may have filled unseen too: no stop is put back beyond what is held (none at all); once
     * A's sale is let go (not found), the square-off sells what Zerodha still holds, once.
     */
    @Test fun at1510WithLaggingPositionsNoStopIsPutBackAndTheExitSellsOnceTheLostSaleResolves() {
        val (s2, gate) = pendingLostAndLaggingPositions(java.time.LocalTime.of(15, 10, 5))
        try {
            pass()
            assertEquals("nothing sold", 0, marketSells().size)
            assertEquals("B's stop came out", "CANCELLED", kite.order(s2).status)
            assertTrue("no stop put back beyond what is held", kite.placed.none { it.form["order_type"] == "SL" })
            assertTrue("B still open", today("liquidity").single { it.arm == "liquidity15" }.open)
        } finally { gate.countDown(); OrbArms.testLostSearch = null }
        searchDone()
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(1)
        pass(); pass()
        assertTrue("A's sale let go", !OrbArms.lostExitForTest(sym))
        // (The square-off then sells what Zerodha still holds - once, for 30: A's part goes first and leaves B nothing to sell.)
        assertEquals("the square-off sold once the lost sale resolved", listOf("30"), marketSells().map { it.form["quantity"] })
        assertTrue("no stop rested meanwhile", kite.placed.none { it.form["order_type"] == "SL" })
    }

    /** 20 Nov 2026 (final review): the same with Stop for today on (before 15:10): no stop put back beyond what is held. */
    @Test fun withStopForTodayAndLaggingPositionsNoStopIsPutBack() {
        val (s2, gate) = pendingLostAndLaggingPositions(null, stopForToday = true)
        try {
            pass()
            assertEquals("nothing sold", 0, marketSells().size)
            assertTrue("no stop put back beyond what is held", kite.placed.none { it.form["order_type"] == "SL" })
            assertTrue(kite.order(s2).status == "CANCELLED" || today("liquidity").single { it.arm == "liquidity15" }.stopOrderId == s2)
        } finally { gate.countDown(); OrbArms.testLostSearch = null }
        searchDone()
    }

    /**
     * 20 Nov 2026 (final review, plausible 2): a restart while a lost sale still works at Zerodha (the hold is memory only): the next look
     * never rests a second stop beside that working sale, nor sells again.
     */
    @Test fun aRestartWhileALostSaleWorksNeverRestsAStopBesideIt() {
        lostLiquiditySale(outcome = FakeKite.Outcome.OPEN)
        kite.showInBook()                                                  // the working sale is in the book now
        OrbArms.forgetMemoryForTest()                                      // a restart: the hold is gone
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(1)
        pass(); pass()
        assertTrue("no stop rested beside the working sale", kite.placed.none { it.form["order_type"] == "SL" })
        assertEquals("nothing sold again", 1, marketSells().size)
    }

    /** 20 Nov 2026 (audit PC): a lost exit not found within its hold is said; then the exit goes again, once, for what is still held. */
    @Test fun aLostLiquidityExitNotFoundIsSaidAndTheExitGoesOnce() {
        lostLiquiditySale(FakeKite.Reply.NETWORK_EXCEPTION)                // nothing reached Zerodha
        kite.positionsTrailReads = 0; kite.trailReads = 0
        pass()
        assertEquals("nothing more sold within the hold", 1, marketSells().size)
        OrbArms.testNow = OrbArms.testNow!!.plusSeconds(OrbArms.LOST_EXIT_HOLD_MS / 1000 + 1)
        pass(); searchDone(); pass()                                       // the look starts the search; the next acts on it
        assertTrue(AutomationSupport.alerts().joinToString(), AutomationSupport.alerts().any { "lost exit of $sym was not found" in it })
        assertTrue(!OrbArms.lostExitForTest(sym))
        assertEquals("sold again once the search found nothing", 2, marketSells().size)
        assertEquals("time_stop", today("liquidity").single().why)
    }
}
