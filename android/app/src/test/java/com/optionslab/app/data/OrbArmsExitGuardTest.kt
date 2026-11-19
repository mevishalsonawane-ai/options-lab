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
        kite.restingInKite(sym, "SELL", 60, 260.0, tag = "iraalgo")         // another book's sale covers 60 of the 90 held
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
}
