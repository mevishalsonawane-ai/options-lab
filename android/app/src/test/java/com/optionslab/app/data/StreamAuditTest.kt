package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.KiteTicks
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * 20 Nov 2026 (the audit's price-stream and market-data items): a network change reopens the stream and lets the relay's stale
 * session go (never under a call in a tunnel); a subscription the socket did not take is never counted as done, and the held
 * positions are subscribed first; a late packet is stamped by the exchange's time; the kept order and position reads are dropped
 * when the connection changes; today's instruments list replaces an earlier day's; SENSEX's list is read back after a restart;
 * the Muhurat hour is not "closed"; the boot starts the watch first; the relay's address never reaches the diary.
 * Nothing here reaches the network.
 */
class StreamAuditTest : RobolectricTest() {
    private lateinit var kite: FakeKite

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        NetWatch.resetForTest()
        KiteStream.resetForTest()
        kite.requests.clear()
    }

    @After fun down() {
        Paper.exitReadForTest = null
        KiteStream.stop()
        KiteStream.resetForTest()
        NetWatch.resetForTest()
        Relay.sessionForTest(null)
        Relay.close()
        Broker.resetBfoForTest()
        Market.testClock = null
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** A socket stand-in: [takes] says whether each message is taken; every message is kept in order. */
    private class Socket(var takes: (String) -> Boolean = { true }) : WebSocket {
        val sent = ArrayList<String>()
        override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("https://example.invalid/").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = takes(text).also { if (it) sent += text }
        override fun send(bytes: ByteString): Boolean = false
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    // ---- 1: a network change ----------------------------------------------------------------------------------------------

    @Test fun onlyARealNetworkChangeIsActedOnAndAStoppedStreamIsLeftAlone() {
        NetWatch.available("wifi-1")
        assertEquals("the network seen at registration is not a change", 0, NetWatch.changes)
        NetWatch.available("wifi-1")
        assertEquals(0, NetWatch.changes)
        NetWatch.available("mobile-2")
        assertEquals("Wi-Fi to mobile data", 1, NetWatch.changes)
        NetWatch.lost("mobile-2"); NetWatch.available("mobile-2")
        assertEquals("the same network back after a loss", 2, NetWatch.changes)
        // No stream running: nothing to reopen (ensure() starts it as before).
        assertFalse(KiteStream.networkChanged())
    }

    @Test fun aNetworkChangeLetsTheRelaysOldSessionGoButNeverUnderACallInATunnel() {
        val s = com.jcraft.jsch.JSch().getSession("ubuntu", "203.0.113.5", 22)
        Relay.sessionForTest(s)
        Relay.failedForTest("Relay: cannot reach the relay server on port 22")
        // An order is in a tunnel now (bytes moving): it is let finish before the old session goes.
        val busy = Relay.tunnelForTest(quietMs = 0, on = s)
        val t = Thread { Thread.sleep(400); Relay.tunnelEndedForTest(busy) }.apply { start() }
        val t0 = System.nanoTime()
        Relay.networkChanged()
        val waited = (System.nanoTime() - t0) / 1_000_000
        t.join()
        assertTrue("waited for the call in the tunnel: $waited ms", waited >= 300)
        assertFalse("then let go", Relay.staleForTest())
        assertNull("a failure on the old network is forgotten once the old session is let go", Relay.recentFailure())
        assertFalse(Relay.connected)
        // A pooled tunnel whose last reply came in full (quiet 10 s) does not hold it: let go at once.
        val idle = Relay.tunnelForTest(quietMs = 10_000, on = s)
        Relay.sessionForTest(s)
        val t1 = System.nanoTime()
        Relay.networkChanged()
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 300)
        assertFalse(Relay.staleForTest())
        Relay.tunnelEndedForTest(idle)
        // B5-D: an order POST quietly waiting for its reply (no bytes for 10 s) is never cut: the session waits for the reply.
        val waiting = Relay.tunnelForTest(quietMs = 10_000, awaitingReply = true, on = s)
        Relay.sessionForTest(s)
        val replied = Thread { Thread.sleep(500); Relay.tunnelMovedForTest(waiting, up = false); Thread.sleep(3_200); Relay.tunnelEndedForTest(waiting) }.apply { start() }
        val t2 = System.nanoTime()
        Relay.networkChanged()
        val held = (System.nanoTime() - t2) / 1_000_000
        replied.join()
        assertTrue("held until its reply came and went quiet: $held ms", held >= 400)
        assertFalse(Relay.staleForTest())
        // A call that never ends does not hold it past the bound (the write deadline in the app: [Relay.STALE_WAIT_MS], 80 s).
        assertEquals(80_000L, Relay.STALE_WAIT_MS)
        val stuck = Relay.tunnelForTest(quietMs = 0, awaitingReply = true, on = s)
        Relay.sessionForTest(s)
        val t3 = System.nanoTime()
        Relay.networkChanged(waitMs = 500)
        assertTrue((System.nanoTime() - t3) / 1_000_000 in 400..3_000)
        assertFalse(Relay.staleForTest())
        Relay.tunnelEndedForTest(stuck)
    }

    /**
     * B5-D: a flapping network is acted on at most once in the debounce; the relay's fail-fast memory is kept through the flaps and
     * forgotten only when the next change is acted on (a failure on the old network never refuses a call on the new one).
     */
    @Test fun aFlappingNetworkReconnectsAtMostOnceInTheDebounceAndKeepsTheFailFastMemory() {
        NetWatch.debounceMs = 600L
        NetWatch.available("wifi-1")
        NetWatch.available("mobile-2")
        val end = System.currentTimeMillis() + 5_000
        while (NetWatch.acts < 1 && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals("the first change: at once", 1, NetWatch.acts)
        Thread.sleep(100)
        Relay.failedForTest("Relay: cannot reach the relay server on port 22")
        // Three flaps inside the debounce: one more reconnect at its end, not three; the memory is kept meanwhile.
        NetWatch.available("wifi-1"); NetWatch.available("mobile-2"); NetWatch.available("wifi-1")
        assertEquals(4, NetWatch.changes)
        assertEquals("waits for the debounce", 1, NetWatch.acts)
        assertNotNull("kept through the flaps", Relay.recentFailure())
        while (NetWatch.acts < 2 && System.currentTimeMillis() < end) Thread.sleep(10)
        Thread.sleep(800)
        assertEquals("one reconnect for the three flaps", 2, NetWatch.acts)
        assertNull("forgotten once the change is acted on", Relay.recentFailure())
    }

    // ---- 2 is in OrderPathRound2Test (the stop's quote read in the safety lane) and RateGateTest ------------------------------

    @Test fun everyQuoteCallTakesItsTurnInZerodhasQuoteLimitAndNothingElseDoes() {
        assertTrue(Broker.quoteLimited("/quote?i=NFO%3AX"))
        assertTrue(Broker.quoteLimited("/quote/ohlc?i=NSE%3ANIFTY+50"))
        assertTrue(Broker.quoteLimited("/quote/ltp?i=NSE%3ANIFTY+50"))
        assertFalse(Broker.quoteLimited("/orders/regular"))
        assertFalse(Broker.quoteLimited("/portfolio/positions"))
        assertFalse(Broker.quoteLimited("/instruments/NFO"))
    }

    @Test fun theQuoteQueueWaitIsPartOfTheReadsOneDeadline() {
        assertEquals("a read's 5 s budget, nothing spent", 5_000L, Broker.quoteWaitMs(false, 5_000L))
        assertEquals("what is left of it (a 429 retry included)", 1_500L, Broker.quoteWaitMs(false, 5_000L, spentMs = 3_500L))
        assertTrue("none left", Broker.quoteWaitMs(false, 5_000L, spentMs = 6_000L) <= 0L)
        assertEquals("a quick read: 3 s at most", 3_000L, Broker.quoteWaitMs(true, 5_000L))
        assertEquals(1_000L, Broker.quoteWaitMs(true, 5_000L, spentMs = 4_000L))
        assertEquals("no budget: 10 s", 10_000L, Broker.quoteWaitMs(false, null))
    }

    /**
     * B5-D: several paper positions held (S5, Flow, ntfy) and the stream stalled - while a live stop's read waits in Zerodha's quote
     * queue, no paper exit read takes the slot (each decides on the stream's last price instead); the live read goes first. Once it has
     * gone, the paper reads run again, in the entries' lane, within their shared budget.
     */
    @Test fun liveStopReadsGoFirstAndPaperExitReadsWaitBehindThem() = runBlocking {
        Paper.resetExitBudgetForTest()
        val gate = Broker.quoteGateForTest()
        // The slot is taken; a live stop's read queues for the next one.
        while (!gate.acquireWithin(com.optionslab.ira.RateGate.Priority.SAFETY, 0L)) Thread.sleep(20)
        val stop = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { gate.acquire(com.optionslab.ira.RateGate.Priority.SAFETY) }
        val t0 = System.currentTimeMillis()
        while (gate.waitingFor(com.optionslab.ira.RateGate.Priority.SAFETY) == 0) { assertTrue(System.currentTimeMillis() - t0 < 5_000); Thread.sleep(2) }
        var asked = 0
        repeat(3) { assertNull("paper position $it: the stream's last price, no read", Paper.paperExitRead(3_000L) { asked++; 100.0 }) }
        assertEquals("not one paper read while the live stop's waits", 0, asked)
        stop.await()
        assertEquals(0, gate.waitingFor(com.optionslab.ira.RateGate.Priority.SAFETY))
        assertEquals(100.0, Paper.paperExitRead(3_000L) { asked++; 100.0 })
        // The paper reads' shared budget (4 s in 15 s): once spent, the stream's last price again.
        assertNull(Paper.paperExitRead(4_000L) { kotlinx.coroutines.delay(6_000); 1.0 })
        assertNull("the budget is spent", Paper.paperExitRead(3_000L) { asked++; 2.0 })
        assertEquals(1, asked)
        Paper.resetExitBudgetForTest()
    }

    /** B5-D: a tick stamped back for coming late never leaves a stop with no price when Zerodha's quote has no answer for it. */
    @Test fun aLateTickNeverLeavesAStopWithNoPriceWhenNoFreshQuoteAnswers() = runBlocking {
        val sym = "NIFTYLATE24000CE"
        val token = 51_000_777L
        kite.instruments += FakeKite.Ins(token, sym, "NIFTY", AutomationSupport.expiryAfter(7), 24_000.0, "CE", 75)
        java.io.File(context.filesDir, "kite_instruments.json").delete()
        Broker.instruments()
        assertEquals(token, Broker.tokenOf("NFO:$sym"))
        // A steady stream, then a packet 9 s late: kept, stamped back.
        var now = System.currentTimeMillis() - 2_000
        repeat(30) {
            KiteStream.feedForTest(listOf(KiteTicks.Tick(token, 100.0, bid = 99.9, ask = 100.1, exchangeTime = (now - 300) / 1000)), now, stampLate = true)
            now += 50
        }
        val arrived = System.currentTimeMillis()
        KiteStream.feedForTest(listOf(KiteTicks.Tick(token, 90.0, bid = 89.9, ask = 90.1, exchangeTime = (arrived - 9_300) / 1000)), arrived, stampLate = true)
        assertTrue("stamped back", arrived - KiteStream.seen(token)!!.at >= 5_000)
        assertNull(KiteStream.freshTick(token))
        kite.requests.clear()
        // Zerodha's reply has nothing for it: the latest tick (it reached the phone just now), not "no price".
        val q = Broker.stopQuotes(listOf("NFO:$sym"))
        assertTrue("Zerodha was asked first", kite.requests.any { it.path.startsWith("/quote") })
        assertEquals(90.0, q.getValue("NFO:$sym").last, 0.0)
        // An exit's read in the safety lane when its caller says so.
        Broker.lastQuoteLaneForTest = null
        Broker.quotes(listOf("NFO:$sym"), lane = com.optionslab.ira.RateGate.Priority.SAFETY)
        assertEquals(com.optionslab.ira.RateGate.Priority.SAFETY, Broker.lastQuoteLaneForTest)
    }

    // ---- 9: freeze levels from the day's instrument data ------------------------------------------------------------------

    @Test fun theMastersFreezeLevelsAreReadFromTheSameRows() {
        val json = """[
            {"segment":"NSE_FO","instrument_type":"CE","underlying_symbol":"NIFTY","expiry":1764000000000,"strike_price":24000,"lot_size":75,"instrument_key":"NSE_FO|1","trading_symbol":"NIFTY 24000 CE","freeze_quantity":1801.0},
            {"segment":"NSE_FO","instrument_type":"PE","underlying_symbol":"NIFTY","expiry":1764000000000,"strike_price":24000,"lot_size":75,"instrument_key":"NSE_FO|2","trading_symbol":"NIFTY 24000 PE","freeze_quantity":1801.0},
            {"segment":"NSE_FO","instrument_type":"CE","underlying_symbol":"BANKNIFTY","expiry":1764000000000,"strike_price":52000,"lot_size":35,"instrument_key":"NSE_FO|3","trading_symbol":"BANKNIFTY 52000 CE","freeze_quantity":"x"},
            {"segment":"NSE_EQ","instrument_type":"EQ","underlying_symbol":"NIFTY","instrument_key":"NSE_EQ|4","trading_symbol":"N","freeze_quantity":5.0}
        ]"""
        val freeze = HashMap<String, Int>()
        val got = Net.parseMaster(json.byteInputStream(), setOf("NIFTY", "BANKNIFTY"), freeze = freeze)
        assertEquals(3, got.size)
        assertEquals(mapOf("NIFTY" to 1801), freeze)
    }

    // ---- 5: a failed subscribe; positions first ---------------------------------------------------------------------------

    @Test fun aSubscribeTheSocketDidNotTakeIsNotCountedAndIsTriedAgainPositionsFirst() {
        val held = 41_000_001L
        val index = 256_265L
        val other = 41_000_009L
        val ws = Socket(takes = { false })
        val none = KiteStream.sendSubscriptionsForTest(ws, setOf(index, other, held), listOf(held, index))
        assertTrue("nothing counted as subscribed: $none", none.isEmpty())
        assertTrue("owed: the watchdog tries again", KiteStream.subscribeOwedForTest())
        ws.takes = { true }
        val all = KiteStream.sendSubscriptionsForTest(ws, setOf(index, other, held), listOf(held, index))
        assertEquals(setOf(held, index, other), all)
        assertFalse(KiteStream.subscribeOwedForTest())
        assertTrue("the held position is the first asked for: ${ws.sent.first()}", ws.sent.first().startsWith("""{"a":"subscribe","v":[$held,$index"""))
        assertTrue(ws.sent.any { it.startsWith("""{"a":"mode","v":["full"""") })
        // A failed unsubscribe keeps the instrument counted (it still streams), and is owed too.
        ws.takes = { !it.contains("unsubscribe") }
        assertEquals(setOf(held, index, other), KiteStream.sendSubscriptionsForTest(ws, setOf(held, index), listOf(held)))
        assertTrue(KiteStream.subscribeOwedForTest())
        ws.takes = { true }
        assertEquals(setOf(held, index), KiteStream.sendSubscriptionsForTest(ws, setOf(held, index), listOf(held)))
    }

    // ---- 4: late ticks ----------------------------------------------------------------------------------------------------

    @Test fun aLatePacketIsStampedByTheExchangeAndIsNotFreshForAStop() {
        val token = 41_000_101L
        var now = System.currentTimeMillis()
        // A steady stream: on time, kept as arrived.
        repeat(30) {
            KiteStream.feedForTest(listOf(KiteTicks.Tick(token, 100.0, bid = 99.9, ask = 100.1, exchangeTime = (now - 300) / 1000)), now, stampLate = true)
            now += 50
        }
        assertEquals(now - 50, KiteStream.seen(token)!!.at)
        // A packet 9 s late arrives now: stamped back, so a stop asks Zerodha instead of taking it as fresh. (On the same timeline as the
        // steady packets: a step against the monotonic clock would be a clock change, which forgets what was learnt.)
        val arrived = now
        KiteStream.feedForTest(listOf(KiteTicks.Tick(token, 90.0, bid = 89.9, ask = 90.1, exchangeTime = (arrived - 9_300) / 1000)), arrived, stampLate = true)
        val seen = KiteStream.seen(token)!!
        assertEquals(90.0, seen.tick.last, 0.0)
        assertTrue("stamped back: ${arrived - seen.at} ms", arrived - seen.at >= 5_000)
        assertNull("not fresh enough to decide a stop on", KiteStream.freshTick(token))
        assertNull("nor to show as live", KiteStream.tick(token))
        // Review: a new connection's first packet of it is Zerodha's snapshot of it NOW - a quiet option's carries the stamp of its
        // last change, a minute ago - and is kept as arrived. The same connection's next late packet is stamped back again.
        val reopened = arrived + 100
        KiteStream.feedForTest(listOf(KiteTicks.Tick(token, 91.0, bid = 90.9, ask = 91.1, exchangeTime = (reopened - 60_300) / 1000)), reopened, stampLate = true, conn = 7)
        assertEquals(reopened, KiteStream.seen(token)!!.at)
        val again = arrived + 200
        KiteStream.feedForTest(listOf(KiteTicks.Tick(token, 92.0, bid = 91.9, ask = 92.1, exchangeTime = (again - 9_300) / 1000)), again, stampLate = true, conn = 7)
        assertTrue(again - KiteStream.seen(token)!!.at >= 5_000)
    }

    // ---- 6: the kept reads ------------------------------------------------------------------------------------------------

    @Test fun theKeptPositionReadIsDroppedWhenTheStreamsConnectionChanges() = runBlocking {
        KiteStream.liveForTest(true)
        Broker.positionBook()
        kite.requests.clear()
        Broker.positionsRecon()
        assertTrue("the kept read stands while nothing changed", kite.requests.none { it.path.startsWith("/portfolio/positions") })
        // The stream dropped and opened again (order updates may have been missed): read afresh.
        Broker.reconStale()
        Broker.positionsRecon()
        assertTrue(kite.requests.any { it.path.startsWith("/portfolio/positions") })
    }

    // ---- 7, 8: the instruments lists --------------------------------------------------------------------------------------

    @Test fun anEarlierDaysListIsReplacedByTodaysWithTheExpiryMovedForAHoliday() = runBlocking {
        val today = Market.today()
        // Yesterday's list: the weekly expiry on its usual day.
        val usual = today.plusDays(3)
        val moved = today.plusDays(2)                                     // a holiday on the usual day: NSE moved it a day earlier
        java.io.File(context.filesDir, "kite_instruments.json").writeText(
            """{"day":"${today.minusDays(1)}","at":${System.currentTimeMillis() - 86_400_000L},"i":[[51000001,"NIFTYOLD24000CE","NIFTY","$usual",24000,75,"CE",0.05]]}""")
        kite.instruments += FakeKite.Ins(51_000_002L, "NIFTYNEW24000CE", "NIFTY", moved, 24_000.0, "CE", 75)
        Broker.resetInstrumentsRetryForTest()
        // Answered at once from the old list; today's is fetched behind it.
        assertEquals(listOf("NIFTYOLD24000CE"), Broker.cachedInstruments()!!.map { it.tradingSymbol })
        val end = System.currentTimeMillis() + 10_000
        while (!Broker.instrumentsAreToday() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("today's list fetched in the background", Broker.instrumentsAreToday())
        assertEquals(listOf(moved), Broker.cachedInstruments()!!.filter { it.name == "NIFTY" }.map { it.expiry }.distinct())
        assertEquals(51_000_002L, Broker.tokenOf("NFO:NIFTYNEW24000CE"))
        assertNull("the old day's contract is gone with its list", Broker.tokenOf("NFO:NIFTYOLD24000CE"))
    }

    @Test fun sensexsListIsReadBackAfterARestartAndItsTokenAsked() = runBlocking {
        val exp = AutomationSupport.expiryAfter(7)
        kite.instruments += FakeKite.Ins(52_000_001L, "SENSEX26NOV82000CE", "SENSEX", exp, 82_000.0, "CE", 20, exchange = "BFO")
        Broker.resetBfoForTest()
        // A restart: nothing in memory. The token is asked for: the list is read in the background, not waited for.
        assertNull(Broker.tokenOf("BFO:SENSEX26NOV82000CE"))
        val end = System.currentTimeMillis() + 10_000
        while (Broker.cachedBfoOptions() == null && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(52_000_001L, Broker.tokenOf("BFO:SENSEX26NOV82000CE"))
        assertEquals(1, kite.requests.count { it.path == "/instruments/BFO" })
        // Another restart the same day: read back from the phone, no download.
        Broker.resetBfoForTest()
        kite.requests.clear()
        java.io.File(context.filesDir, "kite_instruments_bfo.json").let { assertFalse("the test reset deletes it", it.exists()) }
        assertEquals(1, Broker.bfoOptionsNow().size)
        assertTrue(java.io.File(context.filesDir, "kite_instruments_bfo.json").exists())
        Broker.resetBfoForTest(keepFile = true)
        kite.requests.clear()
        assertEquals(1, Broker.bfoOptionsNow().size)
        assertTrue("read back from the phone", kite.requests.none { it.path == "/instruments/BFO" })
    }

    // ---- 10: Muhurat, the boot, a clock set back --------------------------------------------------------------------------

    @Test fun theMuhuratHourIsNotClosedButNoArmTradesIt() {
        val d = LocalDate.of(2026, 11, 8)
        assertNotNull(Market.specialSession(d))
        assertFalse("no arm trades it", Market.isTradingDay(d))
        assertTrue(Market.specialOpenAt(d, 14 * 60 + 20))
        assertTrue(Market.specialOpenAt(d, 15 * 60 + 39))
        assertFalse(Market.specialOpenAt(d, 15 * 60 + 40))
        assertFalse(Market.specialOpenAt(d, 14 * 60 + 19))
        assertFalse("an ordinary day has none", Market.specialOpenAt(d.plusDays(1), 14 * 60 + 30))
        Market.testClock = java.time.Clock.fixed(d.atTime(14, 45).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST)
        Market.testOrdersAnyTime = false
        try {
            assertTrue("a close is taken", Market.acceptsExits())
            // Review: a new order is not (the watch does not run that session, so nothing would check a new position).
            assertFalse(Market.acceptsOrders())
        } finally { Market.testOrdersAnyTime = true }
        assertTrue("held prices stream", KiteStream.inHoursNow())
        Market.testClock = java.time.Clock.fixed(d.atTime(17, 0).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST)
        assertFalse(Market.specialOpen())
    }

    // ---- 11: the relay's address ------------------------------------------------------------------------------------------

    @Test fun theRelaysAddressNeverReachesTheDiary() {
        Relay.host = "203.0.113.77"
        val line = Diag.redact("POST /orders/regular via relay -> FAILED IOException: Relay: cannot reach 203.0.113.77 on port 22")
        assertFalse(line, "203.0.113.77" in line)
        assertTrue(line, "the relay server" in line)
    }
}
