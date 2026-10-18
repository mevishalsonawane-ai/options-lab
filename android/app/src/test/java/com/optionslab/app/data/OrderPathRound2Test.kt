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
import org.junit.Assert.assertFalse
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
        Paper.loadInlineForTest = false
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

    /**
     * 13 Oct (CI shard 0): the fast path's first look finds the paper book not in memory and starts its load, which can finish
     * before that look ends. The look's "not covered" was then kept the full 500 ms (the book counted as loaded only when
     * asked afterwards), and a stop's tick inside that window planned no LIVE lane - no sell at all until another tick.
     * Here the load is done at once (on the look's own thread), so the window is hit every time.
     */
    @Test fun aStopTickRightAfterTheFirstLookLoadedThePaperBookSellsWithin300ms() {
        state(listOf(position(30, 200.0, stopId = null, stop = 160.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        KiteStream.liveForTest(true)
        tick(200.0)
        pass()
        assertTrue(sells.isEmpty())
        // As a new process: the paper book not in memory; the first look's read of it loads it before the look ends.
        Paper.forgetForTest()
        assertFalse(Paper.loaded())
        Paper.loadInlineForTest = true
        FastPath.start(context)
        tick(200.0)
        awaitTrue("the first look loaded the paper book") { Paper.loaded() }
        Thread.sleep(100)                                   // well inside the 500 ms a covered look is kept
        assertTrue("nothing sold at 200", sells.isEmpty())
        val t0 = System.nanoTime()
        tick(150.0)
        awaitTrue("the sell") { sells.isNotEmpty() }
        val ms = (sells.first().atNanos - t0) / 1_000_000
        assertTrue("sent $ms ms after the tick (at most 300 ms)", ms <= 300)
        Thread.sleep(1_500)
        assertEquals("exactly one sell", 1, sells.size)
        assertEquals("stop", arm().today.single().why)
    }

    /**
     * Review R1: a position is managed with the stop and target kept on it at its entry (a 25-point variant then), not with
     * what the lab says now - after a restart the lab's book is read later, and until then the arm's defaults (-40) would
     * have widened that stop.
     */
    @Test fun aLivePositionIsManagedWithTheStopKeptAtItsEntry() {
        state(listOf(position(30, 200.0, stopId = null).put("stopPts", 25.0).put("targetPts", 40.0)))
        kite.position(sym, 30, 200.0, product = "MIS")
        KiteStream.liveForTest(true)
        tick(180.0)
        pass()
        assertTrue("nothing sold above the kept 25-point stop", sells.isEmpty())
        assertEquals(25.0, arm().today.single().stopPts!!, 1e-9)
        tick(170.0)
        pass()
        awaitTrue("the sell at the kept stop (the default -40 would still hold at 170)") { sells.isNotEmpty() }
        assertEquals("stop", arm().today.single().why)
        // Liquidity's kept exits read back as written; a damaged line reads as none (the lab's, as before).
        val l = com.optionslab.engine.orb.LiquidityParams(stopPct = 0.125, timeStopMinutes = 15, timeStopGain = 0.04, nextLevelTarget = true, lockPct = 0.3)
        assertEquals(l, OrbArms.liqOf(OrbArms.liqText(l)))
        assertNull(OrbArms.liqOf("junk"))
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

    /**
     * 18 Oct (speed review): the stream's socket thread is the tick path - a tick, even the day's first (the order-speed book
     * not yet in memory), reads nothing from the settings vault there. It used to: the clock-offset figure read the day's
     * book from the vault on the socket thread ([OrderTiming.clock]).
     */
    @Test fun aTickOnTheStreamsThreadReadsNothingFromTheSettingsVaultEvenTheDaysFirst() {
        OrderTiming.forget()
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        try {
            // feedForTest runs exactly what the socket's message runs, on this thread.
            tick(201.0)
            tick(201.5, tok = peToken)
        } finally { SecurePrefs.countedThread = null }
        assertEquals("settings reads on the stream's thread for a tick", 0L, SecurePrefs.readsForTest.get())
        assertNotNull(KiteStream.freshTick(token))
    }

    /**
     * 18 Oct, review: the stream's drop and its gap (both told on the socket thread) read nothing from the vault either, and
     * reach the speed card on its next read. One queued on an earlier day (no look overnight) is never filed under today.
     */
    @Test fun theStreamsDropAndGapAreQueuedOffTheVaultAndKeptToTheirOwnDay() {
        val was = Market.testClock
        try {
            OrderTiming.forget()
            SecurePrefs.readsForTest.set(0)
            SecurePrefs.countedThread = Thread.currentThread()
            try { OrderTiming.streamDropped(); OrderTiming.streamGap(1_200) } finally { SecurePrefs.countedThread = null }
            assertEquals("settings reads on the stream's thread for a drop", 0L, SecurePrefs.readsForTest.get())
            assertTrue(OrderTiming.card().lines.toString(), OrderTiming.card().lines.any { it.startsWith("Price stream drops today: 1") })
            // Queued yesterday, read today: dropped.
            OrderTiming.forget()
            Market.testClock = java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofDays(-1))
            OrderTiming.streamDropped(); OrderTiming.streamGap(900)
            Market.testClock = was
            assertFalse(OrderTiming.card().lines.toString(), OrderTiming.card().lines.any { it.startsWith("Price stream drops today") })
        } finally { Market.testClock = was; OrderTiming.forget() }
    }

    /**
     * 18 Oct: a tick whose arrival is "in the future" (the phone's clock stepped back since) is not fresh: the price read goes
     * to Zerodha's quote, and the decisions' fresh tick is none. It used to pass for fresh for as long as the clock's step.
     */
    @Test fun aTickFromBeforeTheClockSteppedBackIsNotFresh() = runBlocking {
        try {
            tick(150.0, atMs = System.currentTimeMillis() + 60_000)
            kite.requests.clear()
            assertNull(KiteStream.tick(token))
            assertNull(KiteStream.freshTick(token))
            assertEquals("Zerodha's quote, not the stepped tick", 200.0, Broker.quotes(listOf("NFO:$sym")).getValue("NFO:$sym").last, 0.0)
            assertTrue(kite.requests.any { it.path.startsWith("/quote") })
        } finally { KiteStream.resetForTest() }
    }

    /**
     * 18 Oct (speed review): a stop is judged on a tick only while it is 2 s fresh, else on Zerodha's quote call. The pass's
     * "fallback" used to be [Broker.quotes], which answers from a tick up to 5 s old - a stream gone quiet (called stalled
     * only after 10 s) had the stop judged on its last price, with no REST read.
     */
    @Test fun aStopIsNeverJudgedOnATickOlderThanTwoSecondsWithoutAskingZerodha() = runBlocking {
        try {
            // Fresh: answered from the stream, no quote call.
            tick(201.0)
            kite.requests.clear()
            assertEquals(201.0, Broker.stopQuotes(listOf("NFO:$sym")).getValue("NFO:$sym").last, 0.0)
            assertFalse("a fresh tick needs no quote call", kite.requests.any { it.path.startsWith("/quote") })
            // 3.5 s old (still answered by the screens' 5-s read): the stop read asks Zerodha.
            tick(201.0, atMs = System.currentTimeMillis() - 3_500)
            assertNotNull("the screens' read still takes it", KiteStream.tick(token))
            kite.quote("NFO:$sym", 150.0, 149.95, 150.05)
            kite.requests.clear()
            assertEquals("Zerodha's quote, not the 3.5-s tick", 150.0, Broker.stopQuotes(listOf("NFO:$sym")).getValue("NFO:$sym").last, 0.0)
            assertTrue(kite.requests.any { it.path.startsWith("/quote") })
            assertTrue("kept followed on the stream", token in KiteStream.touchedForTest())
            // The ORB pass with the app's own 160 stop: the stream's last price says 201, Zerodha's says 150 - it sells.
            state(listOf(position(30, 200.0, stopId = null, stop = 160.0)))
            kite.position(sym, 30, 200.0, product = "MIS")
            tick(201.0, atMs = System.currentTimeMillis() - 3_500)
            pass()
            awaitTrue("the sell at the stop, judged on Zerodha's 150") { sells.isNotEmpty() }
            assertEquals("stop", arm().today.single().why)
        } finally { KiteStream.resetForTest() }
    }

    /**
     * Review R1: a BOT's entry (tag "iraorb") also asks the door's bot checks - the daily loss limit's day, the day stop, the
     * day lock (and its amount), the smart workers and the portfolio guard - and still reads nothing from the settings vault.
     * A trip written later is seen at once (kept in memory, refreshed on the write).
     */
    @Test fun aBotsEntryThroughTheDoorReadsNothingFromTheSettingsVault() = runBlocking {
        val o = Kite.Order(sym, Kite.Side.BUY, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", "iraorb")
        // Warm: the first entry after login reads the session and the guards once (then kept in memory).
        Broker.awaitOrder(Broker.placeOrder(o, arm = "orb"), 2_000)
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        val id = Broker.placeOrder(o, arm = "orb")
        assertNull(Broker.entryRecheck(o, "orb"))
        SecurePrefs.countedThread = null
        assertEquals("COMPLETE", Broker.awaitOrder(id, 2_000).status)
        assertEquals("settings reads at the door for a bot's entry", 0L, SecurePrefs.readsForTest.get())
        // The loss limit's day written (as LossBreaker does, or anything else): the next bot entry is refused at once.
        SecurePrefs.put("breaker.day", Market.today().toString())
        try {
            assertEquals("refused: the daily loss limit was reached today", Broker.entryRecheck(o, "orb"))
            // Boss's own entry is never refused by the bots' checks.
            assertNull(Broker.entryRecheck(o.copy(tag = "iraalgo")))
        } finally { SecurePrefs.put("breaker.day", null) }
        assertNull(Broker.entryRecheck(o, "orb"))
    }

    /** Review R1: the vault's generation only ever grows (a kept kill switch can never match a stale one); dropKept forgets it. */
    @Test fun theKeptKillSwitchFollowsAStrictlyGrowingGenerationAndIsDroppedWithTheSession() {
        val o = Kite.Order(sym, Kite.Side.BUY, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", "iraorb")
        val g0 = SecurePrefs.generationHint
        SecurePrefs.put(AppSettings.KILL_KEY, true)
        val g1 = SecurePrefs.generationHint
        assertTrue("a kept-elsewhere write bumps it", g1 > g0)
        assertEquals("refused: the kill switch is on", Broker.entryRecheck(o))
        SecurePrefs.put(AppSettings.KILL_KEY, false)
        assertNull(Broker.entryRecheck(o))
        SecurePrefs.put(AppSettings.KILL_KEY, true)
        val g2 = SecurePrefs.generationHint
        // A wipe (and a reload) moves it on, never back to a value already seen.
        SecurePrefs.wipe()
        val g3 = SecurePrefs.generationHint
        assertTrue("$g3 after $g2", g3 > g2)
        assertNull("the wiped vault's kill switch is off", Broker.entryRecheck(o))
        SecurePrefs.reload()
        assertTrue(SecurePrefs.generationHint > g3)
        // The kept copy is dropped with the session's kept reads (logout, a session end, forget).
        assertNull(Broker.entryRecheck(o))
        val f = Broker::class.java.getDeclaredField("killKept").apply { isAccessible = true }
        assertNotNull(f.get(null))
        Broker.resetForTest()
        assertNull(f.get(null))
    }

    /**
     * Review R1: the trade manager's look prices a live trade from memory only once it is attached - its Zerodha token resolved
     * at the attach - so with the instruments file gone and the day's token map forgotten it still reads the stream's tick,
     * and reads nothing from the settings vault.
     */
    @Test fun theManagersLookReadsNoFileOnceTheTradeIsAttached() {
        TradeManagerHost.resetForTest()
        TradeManagerHost.testNoWake = true
        val id = "orb:orb_fresh:R1:L"
        val now = System.currentTimeMillis()
        try {
            TradeManagerHost.sync(listOf(TradeManagerHost.ArmTrade("orb_fresh", id, "ORB Fresh", "BANKNIFTY", 1, paperCe, 200.0, 30,
                160.0, 240.0, now + 3 * 3_600_000L, live = true, entryMs = now, kite = sym)))
            tick(201.0)
            awaitTrue("the token resolved at the attach") { TradeManagerHost.premiumForTest(id) == 201.0 }
            // No file to read and no map kept: a look that went to the file would find nothing.
            java.io.File(context.filesDir, "kite_instruments.json").delete()
            Broker::class.java.getDeclaredField("tokenMap").apply { isAccessible = true }.set(null, null)
            assertNull(Broker.cachedInstruments())
            tick(202.0)
            SecurePrefs.readsForTest.set(0)
            SecurePrefs.countedThread = Thread.currentThread()
            val px = TradeManagerHost.premiumForTest(id)
            SecurePrefs.countedThread = null
            assertEquals(202.0, px!!, 1e-9)
            assertEquals(0L, SecurePrefs.readsForTest.get())
        } finally { TradeManagerHost.resetForTest() }
    }

    /** Review R1: a shared read is joined only within one lane - an exit's SAFETY read never waits on a lower lane's read. */
    @Test fun aSharedReadIsJoinedOnlyWithinItsLane() = runBlocking {
        fun lane(p: com.optionslab.ira.RateGate.Priority) = Broker.Lane(p)
        val safety = kotlinx.coroutines.withContext(lane(com.optionslab.ira.RateGate.Priority.SAFETY)) { Broker.shareKeyForTest(7) }
        val entry = kotlinx.coroutines.withContext(lane(com.optionslab.ira.RateGate.Priority.ENTRY)) { Broker.shareKeyForTest(7) }
        val analysis = kotlinx.coroutines.withContext(lane(com.optionslab.ira.RateGate.Priority.ANALYSIS)) { Broker.shareKeyForTest(7) }
        val plain = Broker.shareKeyForTest(7)
        assertTrue(safety != entry && safety != analysis && safety != plain)
        assertTrue(entry != analysis)
        assertEquals("no lane reads as the ordinary (entry) lane", entry, plain)
        assertEquals(safety, kotlinx.coroutines.withContext(lane(com.optionslab.ira.RateGate.Priority.SAFETY)) { Broker.shareKeyForTest(7) })
        assertTrue(safety != kotlinx.coroutines.withContext(lane(com.optionslab.ira.RateGate.Priority.SAFETY)) { Broker.shareKeyForTest(8) })
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

    /** 18 Oct (review): the paper-only period is read again inside the entry door too: memory only, nothing from the vault. */
    @Test fun theDoorItselfRefusesAnEntryInThePaperOnlyPeriodReadingNothingFromTheVault() {
        val o = Kite.Order(sym, Kite.Side.BUY, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", "iraorb")
        assertNull("warm: the kept kill switch read once", Broker.entryRecheck(o, "orb"))
        val today = Market.today()
        val p = com.optionslab.ira.PaperOnlyPeriod.Period(today.plusDays(8), "10 days on paper", today.minusDays(1))
        PaperOnly.resetForTest(p)
        try {
            SecurePrefs.readsForTest.set(0)
            SecurePrefs.countedThread = Thread.currentThread()
            assertEquals(PaperOnly.entryWhy(p), Broker.entryRecheck(o, "orb"))
            // Known as the period's own refusal: the door types and tells it as at its front ("paper_only", Boss told once a day).
            assertTrue(PaperOnly.isRefusal(Broker.entryRecheck(o, "orb")!!))
            assertTrue(PaperOnly.isRefusal(PaperOnly.ENTRY_UNREAD))
            assertFalse(PaperOnly.isRefusal("refused: the kill switch is on"))
            assertEquals("Boss's own entry too", PaperOnly.entryWhy(p), Broker.entryRecheck(o.copy(tag = "iraalgo")))
            SecurePrefs.countedThread = null
            assertEquals("settings reads at the door", 0L, SecurePrefs.readsForTest.get())
            PaperOnly.unreadForTest()
            assertEquals("it fails closed before the period is read", PaperOnly.ENTRY_UNREAD, Broker.entryRecheck(o))
        } finally { SecurePrefs.countedThread = null; PaperOnly.resetForTest(null) }
        assertNull(Broker.entryRecheck(o, "orb"))
    }

    /** 18 Oct (review): a period the start could not read is tried again with a growing wait, never given up after ten minutes. */
    @Test fun theUnreadPeriodIsTriedAgainWithAGrowingWaitUntilItIsRead() {
        assertEquals(listOf(20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L),
            (0..5).map { PaperOnly.retryWaitMs(it) })
        assertEquals(300_000L, PaperOnly.retryWaitMs(10_000))
        assertEquals(1L, PaperOnly.retryWaitMs(0, everyMs = 0, maxWaitMs = 10))
        val realRead = PaperOnly.readRaw
        try {
            // Unreadable at the start, readable a moment later: read by the background retry (no screen opened).
            val failing = java.util.concurrent.atomic.AtomicInteger(4)
            PaperOnly.readRaw = { if (failing.getAndDecrement() > 0) throw java.io.IOException("simulated: the vault is busy") else realRead() }
            PaperOnly.unreadForTest()
            PaperOnly.loadSoon(everyMs = 5, maxWaitMs = 20, until = { true })
            val end = System.currentTimeMillis() + 10_000
            while (!PaperOnly.isLoaded && System.currentTimeMillis() < end) Thread.sleep(10)
            assertTrue("read in the background after the failed tries", PaperOnly.isLoaded)
            assertNull(PaperOnly.entryRefusal())
            // Its stop rule (tests only; the app's retry runs until the period is read): the retry ends, and new live entries stay
            // refused until the morning check or a Zerodha login reads it.
            PaperOnly.readRaw = { throw java.io.IOException("simulated: the vault is unreadable") }
            PaperOnly.unreadForTest()
            val asked = java.util.concurrent.atomic.AtomicInteger()
            PaperOnly.loadSoon(everyMs = 5, maxWaitMs = 5, until = { asked.incrementAndGet() < 3 })
            val stop = System.currentTimeMillis() + 5_000
            while (asked.get() < 3 && System.currentTimeMillis() < stop) Thread.sleep(10)
            Thread.sleep(200)
            assertEquals("asked until told to stop, then stopped", 3, asked.get())
            assertFalse(PaperOnly.isLoaded)
            assertEquals(PaperOnly.ENTRY_UNREAD, PaperOnly.entryRefusal())
        } finally { PaperOnly.readRaw = realRead; PaperOnly.resetForTest(null) }
    }
}
