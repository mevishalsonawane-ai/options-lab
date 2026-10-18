package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeKite.Reply
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Alerts
import com.optionslab.engine.Kite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
import java.time.LocalDateTime

/**
 * LIVE ORDER PATHS END TO END (Boss, 18 Oct: "test each and everything with actual works on live, like zerodha orders,
 * position, prices"), against the fake Kite made closer to Zerodha ([FakeKite]: its rate limits, its DataException and
 * GeneralException, a gateway's HTML page, slow answers, dropped connections, GTT triggers, the day book, holdings, OHLC
 * quotes and every exchange's instrument dump). It fills what the flow tests did not cover:
 *
 *  - an error Kite gives after it TOOK the order (DataException 502, GeneralException 500) is checked against the order book:
 *    the order found is the order (it was a refusal, so an ORB entry the exchange filled was left untracked with no stop, and
 *    an ORB exit that filled put a fresh stop back on a flat position: a sell resting with nothing held); not found, it is the
 *    refusal it was. A gateway's 502 page is a lost reply, looked for by the caller;
 *  - Kite's 10-orders-a-second and quote limits: every order goes exactly once, the 429s are waited out;
 *  - a connection dropped before the order was read places nothing and is never re-sent by the HTTP stack;
 *  - a session Zerodha ends mid-trade: nothing is cancelled, the stop resting at Zerodha stays, and Boss is told;
 *  - the kill switch never blocks a live stop's exit;
 *  - every paper-only arm is refused at Zerodha;
 *  - signal (approval) to order and to a resting stop, timed: an "E2E" line per flow for the CI log.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveOrderPathsE2ETest : RobolectricTest() {
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
        Alerts.forgetPosted()
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

    // ---- helpers (the ORB arms' saved state, as OrbArmsLiveTest writes it) ---------------------------------------------

    private fun order(qty: Int = 30, side: Kite.Side = Kite.Side.SELL, type: String = "MARKET", price: Double? = null, trigger: Double? = null) =
        Kite.Order(sym, side, qty, 30, "MIS", type, price, 0.05, "NFO", "irae2e", triggerPrice = trigger)

    private fun contract(symbol: String, right: String) =
        JSONArray().put(symbol).put("BANKNIFTY").put(expiry.toString()).put(52_000.0).put(right).put(30).put("NSE_FO|TEST$right")

    private fun ago(minutes: Long) = maxOf(now.minusMinutes(minutes), now.toLocalDate().atStartOfDay())

    private fun position(qty: Int, entry: Double, entryId: String, stopId: String?, stop: Double?) =
        JSONObject().put("arm", "orb").put("symbol", paperCe).put("right", "CE").put("qty", qty).put("entry", entry)
            .put("entryTime", ago(30).toString()).put("signalBar", ago(35).toString())
            .put("entryOrderId", entryId).put("stopOrderId", stopId ?: "").apply { stop?.let { put("stopTrigger", it) } }
            .put("exitTime", "").put("why", "").put("charges", 0.0).put("live", true).put("kite", sym).put("unconfirmed", false)

    private fun state(pending: Boolean = false, positions: List<JSONObject> = emptyList()) {
        val o = JSONObject()
            .put("armed", JSONObject().put("orb", true)).put("auto", JSONObject().put("orb", true)).put("liveOk", JSONObject().put("orb", true))
            .put("legs", JSONObject().put("day", now.toLocalDate().toString()).put("strike", 52_000).put("expiry", expiry.toString())
                .put("ce", contract(paperCe, "CE")).put("pe", contract(paperPe, "PE")))
            .put("positions", JSONArray(positions))
        if (pending) o.put("pending", JSONObject().put("orb", JSONObject().put("right", "CE").put("bar", ago(1).toString()).put("expires", now.plusMinutes(9).toString())))
        AutomationSupport.orbState(context, o)
    }

    private fun arm() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "orb" }
    private fun approve() = runBlocking { OrbArms.approve("orb", pinConfirmed = true) }
    private fun pass() = runBlocking { OrbArms.priceCheckOnly() }
    private fun restingStop(trigger: Double = 160.0, limit: Double = 152.0) = runBlocking {
        Broker.placeOrder(order(type = "SL", price = limit, trigger = trigger), exit = true)
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(5) }
        throw AssertionError("timed out waiting for $what")
    }

    private fun ms(fromNanos: Long, toNanos: Long) = (toNanos - fromNanos) / 1_000_000

    // ---- Kite's answers after it took the order -------------------------------------------------------------------------

    @Test fun kitesDataAndGeneralExceptionsAfterTheOrderWasTakenAreTheOrderNotARefusal() = runBlocking {
        for (reply in listOf(Reply.DATA_EXCEPTION_AFTER_ACCEPT, Reply.GENERAL_EXCEPTION_AFTER_ACCEPT)) {
            val before = kite.orders.keys.toList()
            kite.nextPlace(reply = reply)
            val id = Broker.placeOrder(order(), exit = true)
            assertEquals("$reply: the order Zerodha took, read from its book", kite.orders.keys.single { it !in before }, id)
        }
        assertEquals("each POST went once", 2, kite.placed.size)
        // A gateway's page in place of Kite's answer: a lost reply (not a refusal), found by the caller's own look.
        kite.nextPlace(reply = Reply.GATEWAY_HTML_AFTER_ACCEPT)
        val known = kite.orders.keys.toList()
        val e = try { Broker.placeOrder(order(), exit = true); null } catch (e: Exception) { e }
        assertNotNull(e)
        assertFalse("${e!!.javaClass.simpleName}: ${e.message}", Broker.definite(e))
        assertEquals(kite.orders.keys.single { it !in known }, Broker.findRecentRetrying(order(), emptyList(), tries = 1, waitMs = 10))
    }

    @Test fun aGeneralExceptionWithNothingPlacedIsStillARefusalAfterTheBookIsRead() = runBlocking {
        kite.nextPlace(reply = Reply.GENERAL_EXCEPTION)
        val t0 = System.nanoTime()
        try { Broker.placeOrder(order(), exit = true); fail("nothing was placed") } catch (e: Broker.KiteError) {
            assertEquals("GeneralException", e.type)
            assertTrue("a refusal, as before", Broker.definite(e))
        }
        val took = ms(t0, System.nanoTime())
        println("E2E Kite GeneralException, nothing placed: refusal known after the order book was read, in $took ms")
        assertTrue(kite.orders.isEmpty())
        assertEquals("the book read three times, as for a lost reply", 3, kite.requests.count { it.method == "GET" && it.path == "/orders" })
        // An order the phone already has the id of is never taken for another's lost reply.
        val first = Broker.placeOrder(order(), exit = true)
        kite.nextPlace(reply = Reply.NETWORK_EXCEPTION)
        try { Broker.placeOrder(order(), exit = true); fail("nothing was placed") } catch (e: Exception) { assertFalse(Broker.definite(e)) }
        assertNull("not $first again", Broker.findRecentRetrying(order(), emptyList(), tries = 1, waitMs = 10))
    }

    @Test fun aRestingStopOfTheSameSizeAndTagIsNeverTakenForAMarketExitsLostReply() = runBlocking {
        // As after a restart: an SL SELL the app placed before (its id no longer in memory) rests in the book.
        kite.orders["250926000000777"] = FakeKite.Order("250926000000777", sym, "NFO", "SELL", 30, "MIS", "SL", 152.0, 160.0, "irae2e", "TRIGGER PENDING")
        kite.nextPlace(reply = Reply.NETWORK_EXCEPTION)
        val exit = order()
        try { Broker.placeOrder(exit, exit = true); fail("nothing was placed") } catch (e: Exception) { assertFalse(Broker.definite(e)) }
        assertNull("the stop is not the market exit", Broker.findRecentRetrying(exit, emptyList(), tries = 1, waitMs = 10))
        // A Kite GeneralException on the exit is not turned into the stop's id either: it stays the refusal it was.
        kite.nextPlace(reply = Reply.GENERAL_EXCEPTION)
        try { Broker.placeOrder(exit, exit = true); fail("nothing was placed") } catch (e: Broker.KiteError) { assertTrue(Broker.definite(e)) }
    }

    @Test fun kitesOwnRefusalsStayDefiniteAndPlaceNothing() = runBlocking {
        for (reply in listOf(Reply.ORDER_EXCEPTION, Reply.INPUT_EXCEPTION)) {
            kite.nextPlace(reply = reply)
            try { Broker.placeOrder(order(), exit = true); fail("$reply was accepted") } catch (e: Broker.KiteError) {
                assertTrue("$reply is a refusal", Broker.definite(e))
            }
        }
        assertTrue("nothing in the book", kite.orders.isEmpty())
    }

    @Test fun aConnectionDroppedBeforeTheOrderWasReadPlacesNothingAndIsNeverResent() = runBlocking {
        kite.drop += "/orders/regular"
        val o = order()
        val e = try { Broker.placeOrder(o, exit = true); null } catch (e: Exception) { e }
        assertNotNull(e)
        assertFalse("its fate is unknown until the book is read", Broker.definite(e!!))
        assertEquals("the HTTP stack never sends an order twice", 1, kite.placed.size)
        kite.drop.clear()
        assertNull("the book holds no such order: not sent", Broker.findRecentRetrying(o, emptyList(), tries = 1, waitMs = 10))
    }

    @Test fun aSlowOrderReplyStillPlacesOneOrderAndAGatewayPageOnAReadKeepsTheSession() = runBlocking {
        kite.slowMs["/orders/regular"] = 1_200L
        val t0 = System.nanoTime()
        val id = Broker.placeOrder(order(), exit = true)
        val took = ms(t0, System.nanoTime())
        println("E2E slow Kite: an order answered after 1200 ms took $took ms end to end, placed once")
        assertTrue("answered late, not cut: $took ms", took >= 1_150)
        assertEquals(id, kite.orders.keys.single())
        kite.gatewayHtml["/portfolio/positions"] = 502
        try { Broker.positionBook(); fail("a gateway page is no position book") } catch (e: java.io.IOException) {
            assertFalse("a gateway's page is no answer of Zerodha's: ${e.message}", e is Broker.KiteError)
        }
        assertTrue("a gateway's page never ends the day's login", Broker.loggedIn)
    }

    // ---- Kite's rate limits ----------------------------------------------------------------------------------------------

    @Test fun kitesTenOrdersASecondIsWaitedOutAndEveryOrderGoesExactlyOnce() = runBlocking {
        kite.keepAlive = true
        kite.orderRate = 10
        val t0 = System.nanoTime()
        val ids = (1..25).map { async(Dispatchers.IO) { Broker.placeOrder(order(type = "LIMIT", price = 199.0 + it * 0.05), exit = true) } }.awaitAll()
        val took = ms(t0, System.nanoTime())
        println("E2E rate limit: 25 orders at once against 10 a second: ${kite.limited} answered 429, all placed once in $took ms")
        assertEquals("one id each", 25, ids.toSet().size)
        assertEquals("each order once in the book", 25, kite.orders.size)
        assertTrue("the limit was hit (${kite.limited})", kite.limited >= 1)
        assertEquals("a 429 is the only repeat", 25 + kite.limited, kite.placed.size)
    }

    @Test fun theQuoteLimitIsWaitedOutWithinTheReadsBudget() = runBlocking {
        kite.keepAlive = true
        kite.quoteRate = 3
        val keys = (1..6).map { "NFO:BANKNIFTY26OCT5${it}000CE" }
        keys.forEachIndexed { i, k -> kite.quote(k, 100.0 + i) }
        val t0 = System.nanoTime()
        val got = keys.map { k -> async(Dispatchers.IO) { Broker.quotes(listOf(k), keepStream = false)[k]?.last } }.awaitAll()
        val took = ms(t0, System.nanoTime())
        println("E2E rate limit: 6 quote reads at once against 3 a second: ${kite.limited} answered 429, all answered in $took ms")
        assertEquals(keys.indices.map { 100.0 + it }, got)
        assertTrue("the limit was hit (${kite.limited})", kite.limited >= 1)
    }

    // ---- the ORB arm (live), signal to exit ------------------------------------------------------------------------------

    @Test fun anApprovedOrbEntryIsTimedFromTheApprovalToTheBuyAndItsRestingStop() {
        state(pending = true)
        val t0 = System.nanoTime()
        assertEquals("Entered at Zerodha (live).", approve())
        val (buy, stop) = kite.placed
        val readsBefore = kite.requests.indexOf(buy)
        println("E2E orb approve->buy POST ${ms(t0, buy.atNanos)} ms ($readsBefore Zerodha calls before it); approve->stop resting ${ms(t0, stop.atNanos)} ms")
        assertEquals("BUY", buy.form["transaction_type"]); assertEquals("30", buy.form["quantity"]); assertEquals("MIS", buy.form["product"])
        assertEquals("SL", stop.form["order_type"]); assertEquals("160.00", stop.form["trigger_price"])
        // Generous ceilings (a slow CI runner): the steps are what is locked - only reads, and few, before the buy.
        assertTrue("approve -> buy took ${ms(t0, buy.atNanos)} ms", ms(t0, buy.atNanos) < 10_000)
        assertTrue("approve -> stop took ${ms(t0, stop.atNanos)} ms", ms(t0, stop.atNanos) < 15_000)
        assertTrue("only reads before the buy: ${kite.requests.take(readsBefore).map { it.method + " " + it.path }}",
            kite.requests.take(readsBefore).all { it.method == "GET" || it.path == "/margins/basket" })
        assertTrue("$readsBefore calls before the buy", readsBefore <= 8)
    }

    @Test fun anOrbBuyAnsweredWithKitesDataExceptionIsFoundAndGetsItsStopNeverLeftUntracked() {
        state(pending = true)
        kite.nextPlace(reply = Reply.DATA_EXCEPTION_AFTER_ACCEPT)
        assertEquals("Entered at Zerodha (live).", approve())
        val p = arm().open
        assertNotNull("the bought lot is held by the arm", p)
        assertNotNull("with its stop resting at Zerodha", p!!.stopOrderId)
        assertEquals("TRIGGER PENDING", kite.order(p.stopOrderId!!).status)
        assertEquals("one buy, one stop", listOf("BUY", "SELL"), kite.placed.map { it.form["transaction_type"] })
    }

    @Test fun anOrbExitAnsweredWithKitesDataExceptionIsBookedAndNoStopGoesBackOnAFlatPosition() {
        val s1 = restingStop()
        state(positions = listOf(position(30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                    // the +40 target
        kite.nextPlace(reply = Reply.DATA_EXCEPTION_AFTER_ACCEPT)
        kite.requests.clear()
        pass()
        val sells = kite.placed.filter { it.form["transaction_type"] == "SELL" }
        assertEquals("one market sell, and no stop placed again on the flat position: ${sells.map { it.form["order_type"] }}",
            listOf("MARKET"), sells.map { it.form["order_type"] })
        assertEquals("flat at Zerodha", 0, kite.positions.getValue("NFO:$sym:MIS").first)
        assertTrue("nothing left working: ${kite.orders.values.filter { it.working }.map { it.type }}", kite.orders.values.none { it.working })
        val done = arm().today.single()
        assertEquals("target", done.why); assertEquals(245.0, done.exit!!, 0.0)
        assertNull(arm().open)
    }

    @Test fun theKillSwitchNeverBlocksALiveStopsExit() {
        val s1 = restingStop()
        SecurePrefs.put("g.kill", true)
        state(positions = listOf(position(30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 150.0, 149.95, 150.05)                    // through the stop's 152 limit: the app sells
        kite.requests.clear()
        val t0 = System.nanoTime()
        pass()
        val sell = kite.placed.single { it.form["order_type"] == "MARKET" }
        println("E2E orb stop with the kill switch on: pass start->market sell POST ${ms(t0, sell.atNanos)} ms")
        assertEquals("SELL", sell.form["transaction_type"]); assertEquals("30", sell.form["quantity"])
        assertTrue("the resting stop came out first", kite.requests.indexOfFirst { it.method == "DELETE" && it.path == "/orders/regular/$s1" } in 0 until kite.requests.indexOf(sell))
        assertEquals(0, kite.positions.getValue("NFO:$sym:MIS").first)
        assertNull(arm().open)
        // And a new entry is still refused.
        state(pending = true)
        assertEquals("Refused: the kill switch is on", approve())
    }

    @Test fun aSessionZerodhaEndsMidTradeCancelsNothingKeepsTheRestingStopAndTellsBoss() {
        val s1 = restingStop()
        state(positions = listOf(position(30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 210.0)
        kite.sessionExpired = true
        kite.requests.clear()
        pass()
        assertFalse("Zerodha ended the session", Broker.loggedIn)
        assertTrue("nothing written at Zerodha: ${kite.writes.map { it.method + " " + it.path }}", kite.writes.isEmpty())
        assertEquals("the stop still rests at Zerodha", "TRIGGER PENDING", kite.order(s1).status)
        val p = arm().open
        assertNotNull("the position is still the arm's", p)
        assertEquals(s1, p!!.stopOrderId)
        awaitTrue("Boss is told") { Alerts.posted.any { it.title == "Zerodha session ended" } }
        val said = Alerts.posted.last { it.title == "Zerodha session ended" }.text
        assertTrue(said, said.contains("stops and GTTs resting at Zerodha still stand"))
        assertFalse("no account figure in it: $said", Regex("\\d").containsMatchIn(said))
    }

    @Test fun everyPaperOnlyArmIsRefusedAtZerodhaAndTheLiveArmsAreNot() {
        val paperOnly = listOf(com.optionslab.engine.orb.HeroRules.ARM, com.optionslab.engine.orb.HeroTicketRules.ARM, com.optionslab.engine.orb.SweepRules.ARM,
            com.optionslab.engine.orb.RangeFadeRules.ARM, com.optionslab.engine.orb.DonchianArm.ARM, com.optionslab.engine.orb.BreadthDonchianArm.ARM,
            com.optionslab.engine.orb.Solo2.ARM, com.optionslab.engine.orb.OovArm.ARM)
        for (a in paperOnly) assertNotNull("${a.label} must never reach Zerodha", OrbArms.liveRefusal(a.source))
        for (a in com.optionslab.engine.orb.OrbRules.ARMS + com.optionslab.engine.orb.LiquidityRules.ARM)
            assertNull("${a.label} may trade live after the PIN", OrbArms.liveRefusal(a.source))
        assertNotNull("an unknown arm is refused", OrbArms.liveRefusal("no_such_arm"))
    }

    // ---- 18 Oct (Boss): the paper-only period blocks every NEW live entry; exits never ------------------------------------

    private fun period(untilDays: Long = 8) = com.optionslab.ira.PaperOnlyPeriod.Period(now.toLocalDate().plusDays(untilDays), "10 days on paper", now.toLocalDate().minusDays(5))

    /** One entry as each source sends it to the order door: Boss's own, ORB/Liquidity, Pine, a strategy, Jarvis's approved idea, MCX, Gold. */
    private fun entries() = listOf(
        "manual" to order(side = Kite.Side.BUY).copy(tag = "iraalgo"),
        "orb/liquidity" to order(side = Kite.Side.BUY).copy(tag = "iraorb"),
        "pine" to order(side = Kite.Side.BUY).copy(tag = "irapine"),
        "strategy" to order(side = Kite.Side.BUY).copy(tag = "iraalgostrat"),
        "jarvis" to order(side = Kite.Side.BUY).copy(tag = "iraalgo"),
        "mcx" to Kite.Order("CRUDEOIL26NOVFUT", Kite.Side.BUY, 1, 1, "NRML", "LIMIT", 6_000.0, 1.0, "MCX", "iraorb", multiplier = 100),
        "gold" to Kite.Order("GOLDBEES", Kite.Side.BUY, 10, 1, "CNC", "LIMIT", 60.0, 0.01, "NSE", "iraalgo"),
    )

    @Test fun duringThePaperOnlyPeriodANewEntryFromEverySourceIsRefusedEvenWhenLive() = runBlocking {
        PaperOnly.resetForTest(period())
        assertTrue("the app is Live", AppSettings.load().live)
        for ((who, o) in entries()) {
            try { Broker.placeOrder(o, arm = if (o.tag == "iraorb") "orb" else null); fail("$who: an entry went in the paper-only period") }
            catch (e: Broker.KiteError) {
                assertEquals(who, "paper_only", e.type)
                assertTrue("$who: Boss is told why: ${e.message}", e.message!!.contains("paper-only period until") && e.message!!.contains("10 days on paper"))
                assertTrue("$who: a definite refusal (nothing sent, never looked for)", Broker.definite(e))
            }
        }
        assertTrue("nothing reached Zerodha: ${kite.writes.map { it.method + " " + it.path }}", kite.writes.isEmpty())
        // The ORB arm's own approved entry, end to end: refused, nothing placed.
        state(pending = true)
        val said = approve()
        assertFalse(said, said.startsWith("Entered"))
        assertTrue("the arm placed nothing", kite.placed.isEmpty())
        // A bot's refusal is told once a day (no screen shows it); the reason, never a figure of the account.
        awaitTrue("Boss is told") { Alerts.posted.any { it.title == "Live entry not sent: paper-only period" } }
        assertEquals("once a day", 1, Alerts.posted.count { it.title == "Live entry not sent: paper-only period" })
    }

    @Test fun duringThePaperOnlyPeriodExitsStopsAndGttsStillGo() = runBlocking {
        PaperOnly.resetForTest(period())
        kite.position(sym, 30, 200.0, product = "MIS")
        val id = Broker.placeOrder(order(type = "LIMIT", price = 199.0), exit = true)
        assertEquals(id, kite.orders.keys.single())
        val stop = restingStop()
        assertEquals("a resting stop goes", "TRIGGER PENDING", kite.order(stop).status)
        assertTrue("a GTT protection goes", Broker.placeGtt(Kite.Gtt("single", "NFO", sym, 200.0, listOf(160.0), listOf(order(type = "LIMIT", price = 158.0)))) > 0)
    }

    @Test fun duringThePaperOnlyPeriodAnOrbStopStillSellsTheLivePosition() {
        PaperOnly.resetForTest(period())
        val s1 = restingStop()
        state(positions = listOf(position(30, 200.0, "E1", s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 150.0, 149.95, 150.05)                    // through the stop: the app sells
        pass()
        val sell = kite.placed.single { it.form["order_type"] == "MARKET" }
        assertEquals("SELL", sell.form["transaction_type"]); assertEquals("30", sell.form["quantity"])
        assertEquals(0, kite.positions.getValue("NFO:$sym:MIS").first)
    }

    @Test fun aRestartKeepsThePaperOnlyBlockAndItFailsClosedUntilTheVaultIsRead() = runBlocking {
        SecurePrefs.put(PaperOnly.KEY, com.optionslab.ira.PaperOnlyPeriod.encode(period()))
        // As at a cold start (or a low-memory kill): memory empty, the period not read yet.
        PaperOnly.unreadForTest(); Broker.resetForTest(); kite.login()
        assertEquals(PaperOnly.ENTRY_UNREAD, PaperOnly.entryRefusal())
        try { Broker.placeOrder(order(side = Kite.Side.BUY)); fail("an entry went before the period was read") } catch (e: Broker.KiteError) { assertEquals("paper_only", e.type) }
        // An exit goes even then.
        kite.position(sym, 30, 200.0, product = "MIS")
        Broker.placeOrder(order(type = "LIMIT", price = 199.0), exit = true)
        // The start's read (a background thread in the app): the block is the period's own.
        PaperOnly.ensureLoaded()
        assertTrue(PaperOnly.entryRefusal()!!.contains("paper-only period until"))
        try { Broker.placeOrder(order(side = Kite.Side.BUY)); fail("an entry went in the paper-only period") } catch (e: Broker.KiteError) { assertEquals("paper_only", e.type) }
        assertEquals("only the exit went", listOf("SELL"), kite.placed.map { it.form["transaction_type"] })
    }

    @Test fun afterThePaperOnlyPeriodEndsEntriesGoAgain() = runBlocking {
        val ended = com.optionslab.ira.PaperOnlyPeriod.Period(now.toLocalDate().minusDays(1), "10 days on paper", now.toLocalDate().minusDays(11))
        SecurePrefs.put(PaperOnly.KEY, com.optionslab.ira.PaperOnlyPeriod.encode(ended))
        PaperOnly.unreadForTest(); PaperOnly.ensureLoaded()
        assertNull(PaperOnly.entryRefusal())
        val id = Broker.placeOrder(order(side = Kite.Side.BUY))
        assertEquals(id, kite.orders.keys.single())
        // And with no period at all.
        PaperOnly.resetForTest(null)
        assertNull(PaperOnly.entryRefusal())
    }

    /** Every order the app sends goes through [Broker.placeOrder] (the one door the paper-only check sits in): one POST in the code. */
    @Test fun theOrderPostIsInOnePlaceOnly() {
        val main = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val posts = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if ("\"/orders/regular\"" in l && "\"POST\"" in l) "${f.name}:${i + 1}" else null } }.toList()
        assertEquals("one POST /orders/regular: $posts", 1, posts.size)
        assertTrue(posts.single(), posts.single().startsWith("Broker.kt:"))
    }

    // ---- the fake's Zerodha shapes, read through the app's own code -------------------------------------------------------

    @Test fun theDayBookHoldingsOhlcAndOtherExchangesReadAsZerodhaSendsThem() = runBlocking {
        kite.overnight("NIFTY26OCT24500PE", 75, 120.0)
        kite.position(sym, 30, 200.0, product = "MIS")
        val book = Broker.positionBook()
        assertEquals(setOf("NIFTY26OCT24500PE", sym), book.net.map { it.symbol }.toSet())
        assertEquals("only today's trade is in the day book", listOf(sym), book.day.map { it.symbol })
        assertEquals(75, book.net.single { it.symbol == "NIFTY26OCT24500PE" }.overnight)
        kite.holdings += FakeKite.Holding("NIFTYBEES", 10, 250.0, 260.0)
        assertEquals(100.0, Broker.holdings().single().pnl, 1e-9)
        kite.quote("NSE:NIFTY 50", 24_600.0); kite.prevClose["NSE:NIFTY 50"] = 24_500.0
        assertEquals(24_600.0 to 24_500.0, Broker.lastAndPrevClose(listOf("NSE:NIFTY 50"))["NSE:NIFTY 50"])
        kite.instruments += FakeKite.Ins(55_000_001, "CRUDEOIL26NOVFUT", "CRUDEOIL", expiry.plusDays(30), 0.0, "FUT", 100, tick = 1.0, exchange = "MCX")
        val spec = Broker.spec("MCX", "CRUDEOIL26NOVFUT")
        assertEquals(55_000_001L, spec.token); assertEquals(1.0, spec.tickSize, 0.0)
        assertTrue("reads only", kite.writes.isEmpty())
    }

    @Test fun aTriggeredGttsOrderAndAnExchangeRejectedStopReadAsZerodhaSendsThem() = runBlocking {
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 159.0, 158.95, 159.05)
        val g = Broker.placeGtt(Kite.Gtt("two-leg", "NFO", sym, 200.0, listOf(160.0, 240.0),
            listOf(order(type = "LIMIT", price = 158.0), order(type = "LIMIT", price = 239.0))))
        val oid = kite.triggerGtt(g, leg = 0)
        assertEquals("triggered", Broker.gtts().single().status)
        assertEquals("COMPLETE", Broker.orderState(oid)!!.status)
        assertEquals("the GTT's sell closed it", 0, Broker.positionBook().net.single().qty)
        // A resting stop the exchange refused after taking it (outside the circuit): read as refused, with the reason.
        kite.position(sym, 30, 200.0, product = "MIS")
        val s = restingStop()
        kite.reject(s)
        val st = Broker.orderState(s)!!
        assertEquals("REJECTED", st.status); assertTrue(st.message, st.message.contains("circuit"))
        // A partial fill moves the position by what filled.
        kite.nextPlace(outcome = FakeKite.Outcome.OPEN)
        val sell = Broker.placeOrder(order(type = "LIMIT", price = 170.0), exit = true)
        kite.fillPart(sell, 15, 170.0)
        assertEquals(15, Broker.orderState(sell)!!.filled)
        assertEquals(15, Broker.positionBook().net.single().qty)
    }
}
