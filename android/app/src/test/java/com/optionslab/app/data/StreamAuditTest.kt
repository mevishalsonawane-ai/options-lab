package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.KiteTicks
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
        val busy = Relay.tunnelForTest(quietMs = 0)
        val t = Thread { Thread.sleep(400); Relay.tunnelEndedForTest(busy) }.apply { start() }
        val t0 = System.nanoTime()
        Relay.networkChanged()
        val waited = (System.nanoTime() - t0) / 1_000_000
        t.join()
        assertTrue("waited for the call in the tunnel: $waited ms", waited >= 300)
        assertFalse("then let go", Relay.staleForTest())
        assertNull("a failure on the old network is forgotten", Relay.recentFailure())
        assertFalse(Relay.connected)
        // A quiet pooled tunnel (no bytes for 10 s) does not hold it: let go at once.
        val idle = Relay.tunnelForTest(quietMs = 10_000)
        Relay.sessionForTest(s)
        val t1 = System.nanoTime()
        Relay.networkChanged()
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 300)
        assertFalse(Relay.staleForTest())
        Relay.tunnelEndedForTest(idle)
        // A call that never ends does not hold it past the bound.
        val stuck = Relay.tunnelForTest(quietMs = 0)
        Relay.sessionForTest(s)
        Thread { repeat(30) { stuck.set(System.nanoTime() / 1_000_000L); Thread.sleep(50) } }.apply { isDaemon = true; start() }
        Relay.networkChanged(waitMs = 500)
        assertFalse(Relay.staleForTest())
        Relay.tunnelEndedForTest(stuck)
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
        // A packet 9 s late arrives now: stamped back, so a stop asks Zerodha instead of taking it as fresh.
        val arrived = System.currentTimeMillis()
        KiteStream.feedForTest(listOf(KiteTicks.Tick(token, 90.0, bid = 89.9, ask = 90.1, exchangeTime = (arrived - 9_300) / 1000)), arrived, stampLate = true)
        val seen = KiteStream.seen(token)!!
        assertEquals(90.0, seen.tick.last, 0.0)
        assertTrue("stamped back: ${arrived - seen.at} ms", arrived - seen.at >= 5_000)
        assertNull("not fresh enough to decide a stop on", KiteStream.freshTick(token))
        assertNull("nor to show as live", KiteStream.tick(token))
        // Review: a new connection's first packet of it is Zerodha's snapshot of it NOW - a quiet option's carries the stamp of its
        // last change, a minute ago - and is kept as arrived. The same connection's next late packet is stamped back again.
        val reopened = System.currentTimeMillis()
        KiteStream.feedForTest(listOf(KiteTicks.Tick(token, 91.0, bid = 90.9, ask = 91.1, exchangeTime = (reopened - 60_300) / 1000)), reopened, stampLate = true, conn = 7)
        assertEquals(reopened, KiteStream.seen(token)!!.at)
        assertNotNull(KiteStream.freshTick(token))
        val again = System.currentTimeMillis()
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
        assertFalse(Broker.instrumentsAreToday())
        Broker.cachedInstruments()                                        // answered at once from the old list; today's fetched behind it
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
