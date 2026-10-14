package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.LiveRecord
import com.optionslab.ira.TickZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The tick zone's wiring (12 Oct): each second is copied from the stream's memory into the queue without a settings read,
 * written as per-second rows with the five-level book and "precision":"exact"; seconds the stream did not have, the sampler
 * did not reach or the full queue dropped are gap runs; closed minutes become order-flow rows; the layout follows BANKNIFTY's
 * ATM +/-3 options of the nearest expiry and VIX; the PIN-confirmed export writes the tick files and Boss's trades decrypted.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class TickZoneRecorderTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val fut = 31_000_001L
    private val ce = 31_000_002L
    private val vix = 264_969L
    private val expiry = AutomationSupport.expiryAfter(7)
    private val list = listOf(
        TickZone.Inst(fut, "BANKNIFTY26OCTFUT", "BANKNIFTY", TickZone.Kind.FUT, expiry = expiry),
        TickZone.Inst(ce, "BANKNIFTY26OCT52000CE", "BANKNIFTY", TickZone.Kind.CE, 52_000.0, expiry),
        TickZone.Inst(vix, "INDIA VIX", "INDIAVIX", TickZone.Kind.VIX),
    )
    /** Today 10:00:00 IST (a minute's start). */
    private val base = Market.today().atTime(10, 0).atZone(com.optionslab.ira.MarketRecord.IST).toEpochSecond()

    @Before fun up() {
        kite = FakeKite()
        kite.keepAlive = true
        kite.login()
        KiteStream.resetForTest()
        LiveRecorder.attachForTest(context)
        LiveRecorder.resetForTest()
        TickZoneRecorder.attachForTest(context, list)
    }

    @After fun down() {
        runBlocking { TickZoneRecorder.detachForTest() }
        LiveRecorder.detachForTest()
        SecurePrefs.countedThread = null
        KiteStream.stop()
        KiteStream.resetForTest()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun feed(sec: Long, futPx: Double, futVol: Long, cePx: Double = 200.0, ceVol: Long = 5_000) {
        fun book(px: Double, tick: Double) = KiteTicks.Depth((0 until 5).map { KiteTicks.Level(px - tick * (it + 1), 75L * (it + 1), it + 1) },
            (0 until 5).map { KiteTicks.Level(px + tick * (it + 1), 30L * (it + 1), it + 1) })
        KiteStream.feedForTest(listOf(
            KiteTicks.Tick(fut, futPx, volume = futVol, oi = 2_000_000, buyQty = 90_000, sellQty = 60_000, lastQty = 30, lastTradeTime = sec,
                exchangeTime = sec, depth = book(futPx, 0.05), bid = futPx - 0.05, ask = futPx + 0.05, avgPrice = futPx - 4.5),
            KiteTicks.Tick(ce, cePx, volume = ceVol, oi = 400_000, buyQty = 20_000, sellQty = 30_000, lastQty = 15, exchangeTime = sec,
                depth = book(cePx, 0.05), avgPrice = cePx + 1.0),
            KiteTicks.Tick(vix, 13.25, exchangeTime = sec, tradable = false),
        ), sec * 1000)
    }

    private fun sample(sec: Long, prev: Long?) = TickZoneRecorder.sample(sec, prev, sec * 1000 + 20)

    private fun lines(name: String): List<String> {
        val f = File(LiveRecorder.rootForTest(), "${Market.today()}/$name")
        if (!f.exists()) return emptyList()
        return GZIPInputStream(f.inputStream()).bufferedReader().readLines()
    }

    @Test fun eachSecondIsCopiedFromMemoryWithoutASettingsReadAndWrittenWithItsBook() {
        KiteStream.liveForTest(true)
        val reads = SecurePrefs.readsForTest.get()
        var prev: Long? = null
        for (i in 0L until 5L) {
            feed(base + i, 52_100.0 + i, 1_000L + 30 * i)
            SecurePrefs.countedThread = Thread.currentThread()
            sample(base + i, prev)
            SecurePrefs.countedThread = null
            prev = base + i
        }
        assertEquals("the sampler reads nothing from the settings vault", reads, SecurePrefs.readsForTest.get())
        runBlocking { TickZoneRecorder.drain((base + 6) * 1000) }
        val l = lines(TickZone.TICKS_FILE)
        assertTrue(l[0], l[0].startsWith("{\"t\":\"H\",\"schema\":1,"))
        assertTrue(l[1], l[1].startsWith("{\"t\":\"I\",\"s\":$base,\"list\":[[$fut,\"BANKNIFTY26OCTFUT\""))
        val t = l.filter { it.startsWith("{\"t\":\"T\"") }
        assertEquals(15, t.size)
        val f0 = t.first { "\"k\":$fut," in it }
        assertTrue(f0, f0.startsWith("{\"t\":\"T\",\"s\":$base,\"k\":$fut,\"ltp\":52100,\"v\":1000,\"atp\":52095.5,\"oi\":2000000,\"tbq\":90000,\"tsq\":60000,\"ltq\":30"))
        assertTrue(f0, "\"b\":[[52099.95,75,1],[52099.9,150,2],[52099.85,225,3],[52099.8,300,4],[52099.75,375,5]]" in f0)
        assertTrue(f0, "\"a\":[[52100.05,30,1]," in f0 && f0.endsWith("\"precision\":\"exact\"}"))
        // VIX: an index packet - its price and time, no volume or book.
        val v0 = t.first { "\"k\":$vix," in it }
        assertTrue(v0, "\"ltp\":13.25" in v0 && "\"v\":" !in v0 && "\"b\":" !in v0)
        assertTrue(l.none { it.startsWith("{\"t\":\"G\"") })
        // The minute is not over: no order-flow row yet.
        assertTrue(lines(TickZone.FLOW_FILE).isEmpty())
        val st = TickZoneRecorder.status()
        assertEquals(3, st.following)
        assertTrue(st.todayBytes > 0)
        assertEquals(1, st.tickDays)
    }

    @Test fun missingSecondsAreGapRunsAndClosedMinutesAreOrderFlowRows() {
        KiteStream.liveForTest(true)
        // 10:00:00 to :02 recorded: up 1 (buy 50), unchanged (buy 20).
        feed(base, 52_100.0, 1_000); sample(base, null)
        feed(base + 1, 52_101.0, 1_050); sample(base + 1, base)
        feed(base + 2, 52_101.0, 1_070); sample(base + 2, base + 1)
        // :03 the stream is down; :04 the sampler is late; :05 down a point (sell 30, after a gap: not a "size").
        KiteStream.liveForTest(false); sample(base + 3, base + 2); KiteStream.liveForTest(true)
        feed(base + 5, 52_100.0, 1_100); sample(base + 5, base + 3)
        // The next minute's first second (the sampler paused from :06 to :59).
        feed(base + 60, 52_100.0, 1_100); sample(base + 60, base + 5)
        runBlocking { TickZoneRecorder.drain((base + 61) * 1000) }
        val g = lines(TickZone.TICKS_FILE).filter { it.startsWith("{\"t\":\"G\"") }
        assertTrue(g.toString(), g.any { "\"from\":${base + 3},\"to\":${base + 3},\"n\":1,\"k\":$fut,\"why\":\"stream down\"" in it })
        assertTrue(g.toString(), g.any { "\"from\":${base + 4},\"to\":${base + 4},\"n\":1,\"why\":\"not sampled\"" in it })
        val a = lines(TickZone.FLOW_FILE)
        assertTrue(a[0], a[0].startsWith("{\"t\":\"H\""))
        val futMin = a.single { it.startsWith("{\"t\":\"A\"") && "\"k\":$fut," in it }
        assertTrue(futMin, futMin.startsWith("{\"t\":\"A\",\"m\":\"10:00\",\"s\":$base,\"k\":$fut,\"sym\":\"BANKNIFTY26OCTFUT\",\"und\":\"BANKNIFTY\",\"kind\":\"FUT\""))
        assertTrue(futMin, "\"vol\":100,\"buy\":70,\"sell\":30,\"unsigned\":0,\"delta\":40,\"cvd\":40" in futMin)
        assertTrue(futMin, "\"obi\":0.2,\"obiClose\":0.2" in futMin && "\"spread\":0.1," in futMin && "\"vwap\":52095.5" in futMin)
        assertTrue(futMin, "\"secs\":4,\"missing\":56,\"precision\":\"exact\",\"v\":1" in futMin)
        assertEquals(3, a.count { it.startsWith("{\"t\":\"A\"") })
        // The sampler stops (the close): the last minute and the open runs closed on a later pass.
        runBlocking { TickZoneRecorder.drain((base + 400) * 1000) }
        val a2 = lines(TickZone.FLOW_FILE).filter { it.startsWith("{\"t\":\"A\"") }
        assertTrue(a2.toString(), a2.any { "\"m\":\"10:01\"" in it && "\"k\":$fut," in it && "\"cvd\":40" in it })
    }

    @Test fun aFullQueueDropsTheOldestSecondsAndRecordsThemAsAGap() {
        KiteStream.liveForTest(true)
        val before = TickZoneRecorder.status().dropped
        var prev: Long? = null
        for (i in 0L until TickZone.QUEUE_CAP + 10L) { feed(base + i, 52_100.0, 1_000 + i); sample(base + i, prev); prev = base + i }
        assertEquals(10L, TickZoneRecorder.status().dropped - before)
        runBlocking { TickZoneRecorder.drain((base + TickZone.QUEUE_CAP + 11L) * 1000) }
        val g = lines(TickZone.TICKS_FILE).filter { it.startsWith("{\"t\":\"G\"") }
        assertTrue(g.toString(), g.any { "\"from\":$base,\"to\":${base + 9},\"n\":10,\"why\":\"queue full\"" in it })
        assertEquals(TickZone.QUEUE_CAP * 3, lines(TickZone.TICKS_FILE).count { it.startsWith("{\"t\":\"T\"") })
    }

    @Test fun theLayoutFollowsBothIndicesAtmPlusMinusThreeOfTheNearestExpiryAndVix() {
        val far = expiry.plusDays(7)
        for (e in listOf(expiry, far)) for (i in -6..6) for (r in listOf("CE", "PE")) {
            val k = 52_000.0 + i * 100
            kite.instruments += FakeKite.Ins(32_000_000L + (if (e == expiry) 0 else 1_000) + (i + 6) * 2 + (if (r == "CE") 0 else 1),
                "BANKNIFTY-${e}-${k.toInt()}$r", "BANKNIFTY", e, k, r, 30)
        }
        runBlocking { Broker.instruments() }
        KiteStream.liveForTest(true)
        KiteStream.feedForTest(listOf(KiteTicks.Tick(260_105L, 52_040.0, tradable = false)))
        runBlocking { TickZoneRecorder.layout() }
        val l = TickZoneRecorder.listForTest()
        val opts = l.filter { it.und == "BANKNIFTY" && (it.kind == TickZone.Kind.CE || it.kind == TickZone.Kind.PE) }
        assertEquals((-3..3).map { 52_000.0 + it * 100 }, opts.mapNotNull { it.strike }.distinct())
        assertEquals(14, opts.size)
        assertTrue(opts.all { it.expiry == expiry })
        assertTrue(l.any { it.kind == TickZone.Kind.VIX && it.token == vix })
        // Recording off: nothing followed.
        LiveRecorder.on = false
        runBlocking { TickZoneRecorder.layout() }
        assertTrue(TickZoneRecorder.listForTest().isEmpty())
        LiveRecorder.on = true
    }

    @Test fun batterySaverNarrowsTheRecordingToAtmPlusMinusOneWithADiaryLineAndNeverStopsIt() {
        // Battery, 14 Oct: under Android's battery saver (or a low battery not charging) the futures, VIX and ATM±1 only.
        for (i in -6..6) for (r in listOf("CE", "PE")) {
            val k = 52_000.0 + i * 100
            kite.instruments += FakeKite.Ins(33_000_000L + (i + 6) * 2 + (if (r == "CE") 0 else 1), "BANKNIFTY-${expiry}-${k.toInt()}$r", "BANKNIFTY", expiry, k, r, 30)
        }
        runBlocking { Broker.instruments() }
        KiteStream.liveForTest(true)
        KiteStream.feedForTest(listOf(KiteTicks.Tick(260_105L, 52_040.0, tradable = false)))
        TickZoneRecorder.saverForTest = true
        runBlocking { TickZoneRecorder.layout() }
        fun opts() = TickZoneRecorder.listForTest().filter { it.und == "BANKNIFTY" && (it.kind == TickZone.Kind.CE || it.kind == TickZone.Kind.PE) }
        assertEquals((-1..1).map { 52_000.0 + it * 100 }, opts().mapNotNull { it.strike }.distinct())
        assertEquals(6, opts().size)
        assertTrue("VIX still recorded", TickZoneRecorder.listForTest().any { it.kind == TickZone.Kind.VIX })
        assertEquals(true, TickZoneRecorder.narrowedNow())
        assertTrue(Diag.lines().any { "battery saver: recording the futures, VIX and ATM±1 only" in it })
        // The same money again: the watch-list stays as it is.
        val same = TickZoneRecorder.listForTest()
        runBlocking { TickZoneRecorder.layout() }
        assertTrue(same === TickZoneRecorder.listForTest())
        // Battery saver off: ATM±3 again, said once.
        TickZoneRecorder.saverForTest = false
        runBlocking { TickZoneRecorder.layout() }
        assertEquals(14, opts().size)
        assertTrue(Diag.lines().any { "battery saver off: recording ATM±3 again" in it })
        // The money moves a strike: rebuilt around the new one.
        KiteStream.feedForTest(listOf(KiteTicks.Tick(260_105L, 52_160.0, tradable = false)))
        runBlocking { TickZoneRecorder.layout() }
        assertEquals((-1..5).map { 52_000.0 + it * 100 }, opts().mapNotNull { it.strike }.distinct())
    }

    @Test fun thePinExportHasTheTickFilesAndTheTradesDecrypted() {
        KiteStream.liveForTest(true)
        feed(base, 52_100.0, 1_000); sample(base, null)
        runBlocking { TickZoneRecorder.drain((base + 1) * 1000) }
        LiveRecorder.signal("ORB", "BANKNIFTY26OCT52000CE", "BANKNIFTY", "CE", 200.0)
        runBlocking { LiveRecorder.drain() }
        val out = java.io.ByteArrayOutputStream()
        assertEquals(1, LiveRecorder.exportDecrypted(out))
        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() }
        }
        val day = Market.today().toString()
        assertTrue(entries.keys.toString(), entries.keys.containsAll(listOf("README.txt", "$day/${TickZone.TICKS_FILE}", "$day/trades.jsonl")))
        assertFalse("never the encrypted file", entries.keys.any { it.endsWith(LiveRecord.TRADES_FILE) })
        val trades = entries.getValue("$day/trades.jsonl").toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        assertTrue(trades.toString(), trades[0].startsWith("{\"t\":\"H\"") && trades.any { it.startsWith("{\"t\":\"S\"") && "\"arm\":\"ORB\"" in it })
        val ticks = GZIPInputStream(entries.getValue("$day/${TickZone.TICKS_FILE}").inputStream()).bufferedReader().readLines()
        assertTrue(ticks.any { it.startsWith("{\"t\":\"T\"") })
        val readme = entries.getValue("README.txt").toString(Charsets.UTF_8)
        assertTrue(readme, "DECRYPTED" in readme && "orderflow_1m.jsonl.gz" in readme)
    }

    /**
     * 15 Oct: an erase's reset runs off the caller's thread; a test's own setup waits for it, so it can never land after
     * the test's drain (the cause of this class's flaky run), and it lets the instruments' names go too.
     */
    @Test fun anErasesResetIsDoneBeforeATestsOwnStateAndClearsTheNames() {
        var prev: Long? = null
        for (i in 0L until 3L) { feed(base + i, 52_100.0, 1_000 + i); sample(base + i, prev); prev = base + i }
        runBlocking { TickZoneRecorder.drain((base + 4) * 1000) }
        assertEquals(3 to 3, TickZoneRecorder.heldForTest())
        TickZoneRecorder.wipe()
        TickZoneRecorder.attachForTest(context, list)
        assertEquals(0 to 0, TickZoneRecorder.heldForTest())
        // The test's own run afterwards is whole.
        prev = null
        for (i in 10L until 13L) { feed(base + i, 52_100.0, 2_000 + i); sample(base + i, prev); prev = base + i }
        runBlocking { TickZoneRecorder.drain((base + 14) * 1000) }
        assertEquals(3 to 3, TickZoneRecorder.heldForTest())
    }

    /** Memory audit (14 Oct, item 6): once a run is closed, the instruments no longer on the watch-list are let go. */
    @Test fun aClosedRunLetsGoTheInstrumentsNoLongerWatched() {
        var prev: Long? = null
        for (i in 0L until 5L) { feed(base + i, 52_100.0, 1_000 + i); sample(base + i, prev); prev = base + i }
        runBlocking { TickZoneRecorder.drain((base + 6) * 1000) }
        assertEquals(3 to 3, TickZoneRecorder.heldForTest())
        // The layout narrowed the list to the future (say battery saver); the run then closes (no second for over 90 s).
        TickZoneRecorder.setListForTest(list.take(1))
        runBlocking { TickZoneRecorder.drain((base + 200) * 1000) }
        assertEquals(1 to 1, TickZoneRecorder.heldForTest())
        // The same list kept through a pause: nothing let go (the day's flow goes on).
        TickZoneRecorder.setListForTest(list)
        for (i in 300L until 303L) { feed(base + i, 52_100.0, 2_000 + i); sample(base + i, if (i == 300L) null else base + i - 1) }
        runBlocking { TickZoneRecorder.drain((base + 304) * 1000) }
        runBlocking { TickZoneRecorder.drain((base + 500) * 1000) }
        assertEquals(3 to 3, TickZoneRecorder.heldForTest())
    }
}
