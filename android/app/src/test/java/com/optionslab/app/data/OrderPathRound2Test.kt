package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.KiteTicks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDateTime

/**
 * Order speed, round 2 (9 Oct), against the fake Kite:
 *  (i) a live ORB stop decided from a stream tick sends exactly one sell, within 300 ms of the tick;
 *  (iii) the exchange stop: an app exit takes the resting stop out first; a resting stop that filled is the exit; a cancel
 *        refused because the stop already filled sends no second sell; "keep a stop at the exchange" places a missing one;
 *  (iv) placing and following an order reads nothing from the settings vault;
 *  the price pick: a fresh tick is used without a REST call, a stale one falls back to REST; the screens' version moves at
 *  most every 250 ms and ends on the latest.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class OrderPathRound2Test : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val token = 22_000_001L
    private val peToken = 22_000_002L
    private val paperCe = "BANKNIFTY-TEST-52000CE"
    private val paperPe = "BANKNIFTY-TEST-52000PE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private lateinit var now: LocalDateTime
    /** One exchange stamp for every tick: no minute or bar closes during a test (only the price lanes run). */
    private val stamp = System.currentTimeMillis() / 60_000 * 60 + 1

    @Before fun up() {
        kite = FakeKite()
        kite.keepAlive = true
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(token, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(peToken, "BANKNIFTY26OCT52000PE", "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        // Each test's prices are its own: a tick an earlier test fed (a fall to 145) is answered by Broker.quotes for 5 s.
        assertNull("a stream tick left over from an earlier test: ${KiteStream.seen(token)}", KiteStream.tick(token))
        kite.requests.clear()
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        now = t.toLocalDateTime().withNano(0)
    }

    @After fun down() {
        FastPath.resetForTest()
        SecurePrefs.countedThread = null
        KiteStream.stop()
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    // ---- the arms' saved state (as OrbArmsLiveTest) -----------------------------------------------------------------

    private fun contract(symbol: String, right: String) =
        JSONArray().put(symbol).put("BANKNIFTY").put(expiry.toString()).put(52_000.0).put(right).put(30).put("NSE_FO|TEST$right")

    private fun ago(minutes: Long) = maxOf(now.minusMinutes(minutes), now.toLocalDate().atStartOfDay())

    private fun position(qty: Int, entry: Double, stopId: String? = null, stop: Double? = null) =
        JSONObject().put("arm", "orb").put("symbol", paperCe).put("right", "CE").put("qty", qty).put("entry", entry)
            .put("entryTime", ago(30).toString()).put("signalBar", ago(35).toString())
            .put("entryOrderId", "E1").put("stopOrderId", stopId ?: "").apply { stop?.let { put("stopTrigger", it) } }
            .put("exitTime", "").put("why", "").put("charges", 0.0).put("live", true).put("kite", sym).put("unconfirmed", false)

    private fun state(positions: List<JSONObject>) {
        val o = JSONObject()
            .put("armed", JSONObject().put("orb", false)).put("auto", JSONObject().put("orb", true)).put("liveOk", JSONObject().put("orb", true))
            .put("legs", JSONObject().put("day", now.toLocalDate().toString()).put("strike", 52_000).put("expiry", expiry.toString())
                .put("ce", contract(paperCe, "CE")).put("pe", contract(paperPe, "PE")))
            .put("positions", JSONArray(positions))
        AutomationSupport.orbState(context, o)
    }

    private fun arm() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "orb" }
    private fun pass() = runBlocking { OrbArms.priceCheckOnly() }
    private fun streamLook() = runBlocking { OrbArms.priceCheckOnly(fast = true, stream = true) }
    private fun stopOrder(qty: Int, trigger: Double, limit: Double) = runBlocking {
        Broker.placeOrder(Kite.Order(sym, Kite.Side.SELL, qty, 30, "MIS", "SL", limit, 0.05, "NFO", "iraorb", triggerPrice = trigger), exit = true)
    }

    private fun tick(last: Double, tok: Long = token, atMs: Long = System.currentTimeMillis()) =
        KiteStream.feedForTest(listOf(KiteTicks.Tick(tok, last, bid = last - 0.05, ask = last + 0.05, exchangeTime = stamp, bidQty = 750, askQty = 750)), atMs)

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(2) }
        throw AssertionError("timed out waiting for $what")
    }

    private val sells: List<FakeKite.Req> get() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }

    // ---- (i) a live stop decided from a stream tick ------------------------------------------------------------------

    @Test fun aLiveStopDecidedFromAStreamTickSendsExactlyOneSellWithin300ms() {
        // A live ORB lot bought at 200 whose stop is NOT resting at Zerodha (the app watches its -40 stop itself).
        state(listOf(position(30, 200.0, stopId = null, stop = 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        KiteStream.liveForTest(true)
        // The regular pass: Zerodha's orders and positions read (and kept for the fast looks); nothing to do at 200.
        tick(200.0)
        pass()
        assertTrue(sells.isEmpty())
        assertEquals(listOf(sym), OrbArms.liveSymbolsHint)
        FastPath.start(context)
        tick(200.0)
        Thread.sleep(400)
        assertTrue("nothing sold at 200", sells.isEmpty())
        val before = kite.requests.size
        // The price falls through the stop on the stream.
        val t0 = System.nanoTime()
        tick(150.0)
        awaitTrue("the sell") { sells.isNotEmpty() }
        val ms = (sells.first().atNanos - t0) / 1_000_000
        println("LATENCY stream tick -> live stop sell at Zerodha: $ms ms")
        assertTrue("sent $ms ms after the tick (at most 300 ms)", ms <= 300)
        // Decided from the stream: no quote, order book or position read between the tick and the decision.
        val reads = kite.requests.drop(before).takeWhile { it.method == "GET" }
        assertTrue("no quote read for the decision: $reads", reads.none { it.path.startsWith("/quote") })
        Thread.sleep(1_500)
        assertEquals("exactly one sell", 1, sells.size)
        assertEquals("30", sells.single().form["quantity"])
        assertEquals("stop", arm().today.single().why)
        assertTrue(OrderTiming.card().lines.toString(), OrderTiming.card().lines.any { it.startsWith("Decided from the stream 1") })
    }

    // ---- (iii) the exchange stop ----------------------------------------------------------------------------------------

    @Test fun anAppExitTakesTheRestingExchangeStopOutBeforeItSells() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position(30, 200.0, s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        kite.quote("NFO:$sym", 245.0, 244.95, 245.05)                    // ORB's +40 target
        kite.requests.clear()
        pass()
        val cancel = kite.requests.indexOfFirst { it.method == "DELETE" && it.path == "/orders/regular/$s1" }
        val sell = kite.requests.indexOfFirst { it.method == "POST" && it.path == "/orders/regular" && it.form["order_type"] == "MARKET" }
        assertTrue("the stop was cancelled", cancel >= 0)
        assertTrue("then sold", sell > cancel)
        assertEquals("CANCELLED", kite.order(s1).status)
        assertEquals(1, sells.size)
        val done = arm().today.single()
        assertEquals("the exit at 245 is the +40 target (exit ${done.exit}, the price a pass reads now: " +
            "${runBlocking { Broker.quotes(listOf("NFO:$sym")) }["NFO:$sym"]?.last})", "target", done.why)
        assertEquals("booked at the sell's own fill", 245.0, done.exit!!, 0.0)
        assertTrue(OrderTiming.card().lines.any { it.startsWith("Live · Exchange stop cancelled before an exit") })
    }

    @Test fun aRestingStopThatFilledIsTheExitSeenFromTheStreamsOrderUpdate() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position(30, 200.0, s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        KiteStream.liveForTest(true)
        tick(200.0)
        pass()                                                             // the kept books: the stop TRIGGER PENDING
        kite.fill(s1, 158.0)
        // Zerodha says so on the stream: the kept books are read afresh on the next look.
        KiteStream.textForTest("""{"type":"order","data":{"order_id":"$s1","status":"COMPLETE","status_message":null,""" +
            """"tradingsymbol":"$sym","average_price":158.0,"filled_quantity":30,"tag":"iraorb"}}""")
        tick(157.0)
        kite.requests.clear()
        streamLook()
        val p = arm().today.single()
        assertEquals("stop", p.why); assertEquals(158.0, p.exit!!, 0.0)
        assertTrue("no second sell", sells.isEmpty())
        assertTrue("nothing cancelled or sold", kite.writes.isEmpty())
    }

    @Test fun aCancelRefusedBecauseTheStopAlreadyFilledSendsNoSecondSell() {
        val s1 = stopOrder(30, 160.0, 152.0)
        state(listOf(position(30, 200.0, s1, 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        KiteStream.liveForTest(true)
        tick(200.0)
        pass()                                                             // kept: the stop working, 30 held
        // The stop fills at Zerodha and the stream says nothing (yet): the kept books still show it working.
        kite.fill(s1, 151.0)
        tick(145.0)                                                        // through the stop's limit: the app would sell
        kite.requests.clear()
        streamLook()
        assertTrue("the app tried to take its stop out first", kite.requests.any { it.method == "DELETE" && it.path == "/orders/regular/$s1" })
        assertTrue("no second sell: the filled stop is the exit", sells.isEmpty())
        val p = arm().today.single()
        assertEquals("stop", p.why); assertEquals(151.0, p.exit!!, 0.0)
        assertEquals(0, kite.positions.getValue("NFO:$sym:MIS").first)
    }

    @Test fun keepingAStopAtTheExchangePlacesAMissingOneAtTheArmsLevelOnlyWhenChosen() {
        state(listOf(position(30, 200.0, stopId = null, stop = 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        // Off (the default): the app watches the stop itself, nothing is placed.
        pass()
        assertTrue("setting off at 200: nothing placed, yet ${kite.placed.map { it.form }} (price " +
            "${runBlocking { Broker.quotes(listOf("NFO:$sym")) }["NFO:$sym"]?.last}, exits ${arm().today.map { it.why }})", kite.placed.isEmpty())
        assertNull(arm().open!!.stopOrderId)
        // On: the next regular check places an SL SELL at the arm's own 160 (its limit under it), and keeps it.
        AppSettings.save(AppSettings.load().copy(exchangeStops = true))
        assertTrue("the switch is read back", AppSettings.load().exchangeStops)
        pass()
        val sl = kite.placed.singleOrNull() ?: throw AssertionError("setting on: one SL placed at 160, got ${kite.placed.map { it.form }}")
        assertEquals("SL", sl.form["order_type"]); assertEquals("SELL", sl.form["transaction_type"])
        assertEquals("160.00", sl.form["trigger_price"]); assertEquals("30", sl.form["quantity"])
        assertEquals(kite.orders.keys.last(), arm().open!!.stopOrderId)
        // A fast look never places one, and the next pass does not place a second.
        pass()
        assertEquals(1, kite.placed.size)
        // A price already at the level: no stop placed (the app's own check sells instead).
        state(listOf(position(30, 200.0, stopId = null, stop = 160.0).put("arm", "orb_fresh")))
        kite.quote("NFO:$sym", 160.02, 160.0, 160.05)
        val n = kite.placed.size
        runCatching { pass() }
        assertTrue(kite.placed.drop(n).none { it.form["order_type"] == "SL" })
    }

    // ---- (iv) no vault read on the order path ---------------------------------------------------------------------------

    @Test fun placingAndFollowingAnOrderReadsNothingFromTheSettingsVault() = runBlocking {
        val o = Kite.Order(sym, Kite.Side.BUY, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", "iratest")
        // Warm: the first order after login reads the session once (it is then kept in memory).
        Broker.awaitOrder(Broker.placeOrder(o), 2_000)
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        val id = Broker.placeOrder(o)
        val f = Broker.awaitOrder(id, 2_000)
        SecurePrefs.countedThread = null
        assertEquals("COMPLETE", f.status)
        assertEquals("settings reads while placing and following the order", 0L, SecurePrefs.readsForTest.get())
        // The session is still the vault's: a logout clears the kept copy at once.
        assertTrue(Broker.loggedIn)
        Broker.dropCreds()
        assertTrue("read again from the vault", Broker.loggedIn)
        SecurePrefs.putAll(mapOf("kite.accessToken" to null))
        assertTrue("a session ended in the vault is never answered from memory", !Broker.loggedIn)
    }

    // ---- the price pick and the screens' pace -----------------------------------------------------------------------------

    @Test fun aFreshTickIsUsedWithoutARestCallAndAStaleOneFallsBackToRest() = runBlocking {
        kite.quote("NFO:BANKNIFTY26OCT52000PE", 90.0, 89.95, 90.05)
        tick(201.0)
        tick(88.0, tok = peToken, atMs = System.currentTimeMillis() - 10_000)
        kite.requests.clear()
        assertEquals(201.0, Broker.quotes(listOf("NFO:$sym")).getValue("NFO:$sym").last, 0.0)
        assertTrue("fresh: no REST quote", kite.requests.none { it.path.startsWith("/quote") })
        assertEquals("stale: Zerodha's quote", 90.0, Broker.quotes(listOf("NFO:BANKNIFTY26OCT52000PE")).getValue("NFO:BANKNIFTY26OCT52000PE").last, 0.0)
        assertTrue(kite.requests.any { it.path.startsWith("/quote") })
        // Deciding takes a tick at most 2 s old; 3 s is too old to decide on (the REST fallback), though a screen may show it.
        assertNotNull(KiteStream.freshTick(token))
        tick(202.0, atMs = System.currentTimeMillis() - 3_000)
        assertNull(KiteStream.freshTick(token))
        assertNotNull(KiteStream.tick(token))
        KiteStream.liveForTest(true)
        tick(203.0)
        assertTrue(KiteStream.allFresh(listOf(token)))
        assertTrue(!KiteStream.allFresh(listOf(token, peToken)))
    }

    @Test fun theScreensVersionMovesAtMostEvery250msAndEndsOnTheLatest() = runBlocking {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val start = System.currentTimeMillis()
        val watcher = launch(Dispatchers.IO) { KiteStream.version.collect { if (it >= start) seen += it } }
        Thread.sleep(300)
        var last = 0L
        repeat(100) { tick(200.0 + it * 0.05); last = System.currentTimeMillis(); Thread.sleep(10) }
        Thread.sleep(500)
        watcher.cancel()
        val v = seen.distinct().sorted()
        assertTrue("$v", v.size in 3..8)
        assertTrue("never closer than 250 ms: $v", v.zipWithNext().all { (a, b) -> b - a >= 250 })
        assertTrue("the last tick shown within 300 ms: ${v.last() - last}", v.last() >= last - 250 && v.last() - last <= 300)
    }
}
