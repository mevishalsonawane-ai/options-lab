package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.HealthTuner
import com.optionslab.ira.MemoryPlan
import com.optionslab.ira.SharedKnowledge
import com.optionslab.ira.StartOrder
import kotlinx.coroutines.CoroutineScope
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
 * OPEN POSITIONS AND ORDERS COME FIRST (Boss, 14 Oct; the memory audit), against the fake Kite with a live ORB lot held:
 *  - every memory-trim level Android has lets the listed items go and never the position watch: the held contract's
 *    price, its tick path and candles stay, its stream subscription stays, no cadence is lengthened, the paper book stays
 *    in memory - and a stop tick right after the harshest trim still sells within 300 ms;
 *  - after a restart (a low-memory kill) the open position and its stop are restored and the watch started FIRST, the
 *    stream next, and the shared knowledge loads only after both (it waits even when started first);
 *  - nothing open: the watch is still asked to start (16 Oct: Jobs.ensureWatch decides from the clock and the armed MCX arms,
 *    a no-op when nothing is due); a book that cannot be read counts as open.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PositionsFirstTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "BANKNIFTY26OCT52000CE"
    private val token = 22_000_001L
    private val paperCe = "BANKNIFTY-TEST-52000CE"
    private val paperPe = "BANKNIFTY-TEST-52000PE"
    private val expiry = AutomationSupport.expiryAfter(7)
    private lateinit var now: LocalDateTime
    private val stamp = System.currentTimeMillis() / 60_000 * 60 + 1

    @Before fun up() {
        kite = FakeKite()
        kite.keepAlive = true
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        kite.instruments += FakeKite.Ins(token, sym, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        runBlocking { Broker.instruments() }
        kite.requests.clear()
        val t = AutomationSupport.earlierToday()
        OrbArms.testNow = t
        now = t.toLocalDateTime().withNano(0)
        PositionsFirst.resetForTest(started = false)
        MemoryTrim.resetForTest()
        SharedKnowledgeHost.resetForTest()
        SharedKnowledgeHost.attachForTest(context)
    }

    @After fun down() {
        FastPath.resetForTest()
        SecurePrefs.countedThread = null
        KiteStream.stop()
        AutomationSupport.realClocks()
        PositionsFirst.resetForTest(started = false)
        MemoryTrim.resetForTest()
        SharedKnowledgeHost.resetForTest()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun contract(symbol: String, right: String) =
        JSONArray().put(symbol).put("BANKNIFTY").put(expiry.toString()).put(52_000.0).put(right).put(30).put("NSE_FO|TEST$right")

    private fun ago(minutes: Long) = maxOf(now.minusMinutes(minutes), now.toLocalDate().atStartOfDay())

    /** A live ORB lot bought at 200, its -40 stop (160) watched by the app itself (none resting at Zerodha). */
    private fun heldLive() {
        val p = JSONObject().put("arm", "orb").put("symbol", paperCe).put("right", "CE").put("qty", 30).put("entry", 200.0)
            .put("entryTime", ago(30).toString()).put("signalBar", ago(35).toString())
            .put("entryOrderId", "E1").put("stopOrderId", "").put("stopTrigger", 160.0)
            .put("exitTime", "").put("why", "").put("charges", 0.0).put("live", true).put("kite", sym).put("unconfirmed", false)
        val o = JSONObject()
            .put("armed", JSONObject().put("orb", false)).put("auto", JSONObject().put("orb", true)).put("liveOk", JSONObject().put("orb", true))
            .put("legs", JSONObject().put("day", now.toLocalDate().toString()).put("strike", 52_000).put("expiry", expiry.toString())
                .put("ce", contract(paperCe, "CE")).put("pe", contract(paperPe, "PE")))
            .put("positions", JSONArray(listOf(p)))
        // As a new process: the book on disk, nothing of it in memory.
        AutomationSupport.orbState(context, o)
        kite.position(sym, 30, 200.0, product = "MIS")
    }

    private fun tick(last: Double, atMs: Long = System.currentTimeMillis()) =
        KiteStream.feedForTest(listOf(KiteTicks.Tick(token, last, bid = last - 0.05, ask = last + 0.05, exchangeTime = stamp, bidQty = 750, askQty = 750)), atMs)

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(2) }
        throw AssertionError("timed out waiting for $what")
    }

    private val sells: List<FakeKite.Req> get() = kite.placed.filter { it.form["transaction_type"] == "SELL" && it.form["order_type"] == "MARKET" }

    private val levels = listOf(MemoryPlan.RUNNING_MODERATE, MemoryPlan.RUNNING_LOW, MemoryPlan.RUNNING_CRITICAL, MemoryPlan.UI_HIDDEN,
        MemoryPlan.BACKGROUND, MemoryPlan.MODERATE, MemoryPlan.COMPLETE)

    // ---- trims never touch the watch ------------------------------------------------------------------------------------

    @Test fun noTrimLevelLetsTheHeldPositionsWatchGoAndTheStopStillSellsWithin300ms() {
        heldLive()
        KiteStream.liveForTest(true)
        tick(200.0)
        runBlocking { OrbArms.priceCheckOnly() }
        assertEquals(listOf(sym), OrbArms.liveSymbolsHint)
        FastPath.start(context)
        tick(200.0)
        awaitTrue("the stream follows the held contract") { token in KiteStream.followedNow() }
        runBlocking { Paper.watching() }
        assertTrue(Paper.loaded())
        val cadences = HealthTuner.TUNABLES.associate { it.key to AppHealth.cadenceMs(it.key) }
        // An instrument nothing follows any more, quiet for three hours: the trims let it go.
        val gone = 33_000_001L
        KiteStream.feedForTest(listOf(KiteTicks.Tick(gone, 10.0, exchangeTime = stamp - 3 * 3600)), System.currentTimeMillis() - 3 * 3_600_000L)
        for (level in levels) {
            tick(200.0)
            AppHealth.trim(level)
            // What the trim lets go, done here and now (the app does it on a background thread).
            val done = MemoryTrim.release(MemoryPlan.releases(level), level)
            assertEquals("level $level", MemoryPlan.releases(level), done)
            assertNotNull("level $level: the held contract's price", KiteStream.tick(token))
            assertNotNull("level $level: its tick path (the trail's best price, resting stops)", KiteStream.highSince(token, System.currentTimeMillis() - 60_000))
            assertNotNull("level $level: its candles", KiteStream.candles.latest(token))
            assertTrue("level $level: its stream subscription", token in KiteStream.followedNow())
            assertEquals("level $level: the live exits' symbols", listOf(sym), OrbArms.liveSymbolsHint)
            assertTrue("level $level: the paper book", Paper.loaded())
            assertEquals("level $level: no cadence lengthened", cadences, HealthTuner.TUNABLES.associate { it.key to AppHealth.cadenceMs(it.key) })
            assertTrue("level $level: ${MemoryTrim.lastLine}", MemoryTrim.lastLine.orEmpty().endsWith("the position watch untouched"))
        }
        assertNull("the unfollowed quiet instrument's candles went", KiteStream.candles.latest(gone))
        // Right after the harshest trim: the stop tick sells within 300 ms, exactly once.
        Thread.sleep(400)
        assertTrue("nothing sold at 200", sells.isEmpty())
        val t0 = System.nanoTime()
        tick(150.0)
        awaitTrue("the sell") { sells.isNotEmpty() }
        val ms = (sells.first().atNanos - t0) / 1_000_000
        println("LATENCY after every trim level: stream tick -> live stop sell $ms ms")
        assertTrue("sent $ms ms after the tick (at most 300 ms)", ms <= 300)
        Thread.sleep(1_500)
        assertEquals("exactly one sell", 1, sells.size)
    }

    @Test fun theTrimCountsAndTheScreenHiddenDoesNot() {
        val now = System.currentTimeMillis()
        MemoryTrim.onTrim(MemoryPlan.UI_HIDDEN, now)
        assertFalse(MemoryTrim.underPressure(now))
        MemoryTrim.onTrim(MemoryPlan.BACKGROUND, now)
        assertTrue(MemoryTrim.underPressure(now + 60_000))
        assertFalse(MemoryTrim.underPressure(now + MemoryPlan.PRESSURE_MS))
    }

    // ---- the start-up order ---------------------------------------------------------------------------------------------

    @Test fun afterARestartTheOpenPositionAndItsStopAreRestoredAndWatchedFirst() {
        heldLive()
        // A fresh process: nothing of the books in memory.
        Paper.forgetForTest()
        assertFalse(Paper.loaded())
        assertTrue(OrbArms.liveSymbolsHint.isEmpty())
        PositionsFirst.resetForTest(started = true)
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        // The shared knowledge's load is started BEFORE the positions (as the app's start may): it must wait.
        val knowledge = CoroutineScope(Dispatchers.IO).launch { SharedKnowledgeHost.afterStart(); events += "knowledge" }
        Thread.sleep(300)
        assertEquals("the knowledge waits for the positions", 0, SharedKnowledgeHost.snapshot.size)
        assertTrue(events.isEmpty())
        PositionsFirst.appReady()
        // 17 Oct: the watch is asked at once (it reads the books it checks itself); the books are restored beside it, so the
        // stream that follows finds the held contract and the paper book in memory.
        runBlocking {
            PositionsFirst.steps(PositionsFirst::restore,
                watch = { events += "watch" },
                stream = { events += "stream with ${OrbArms.liveSymbolsHint} and paper ${if (Paper.loaded()) "loaded" else "not loaded"}" })
        }
        runBlocking { knowledge.join() }
        assertEquals(listOf("watch", "stream with [$sym] and paper loaded", "knowledge"), events.toList())
        assertEquals(StartOrder.Step.entries, PositionsFirst.order.log().map { it.first })
        assertEquals(true, PositionsFirst.foundOpen)
        assertTrue(SharedKnowledgeHost.snapshot.size > 0)
        assertNotNull(PositionsFirst.order.positionsWatchedAfter(PositionsFirst.startedAt))
        assertTrue(PositionsFirst.diagLine(), PositionsFirst.diagLine().startsWith("Start-up: positions +") && "open positions found and watched first" in PositionsFirst.diagLine())
    }

    @Test fun nothingOpenStillAsksTheWatchAndAnUnreadableBookCountsAsOpen() {
        AutomationSupport.orbState(context, JSONObject().put("positions", JSONArray()))
        PositionsFirst.resetForTest(started = true)
        PositionsFirst.appReady()
        var watched = 0
        // 16 Oct: asked even with nothing open - an MCX arm armed in its evening needs the watch with nothing held yet (the
        // watch's own start rule decides; it is a no-op when nothing is due).
        runBlocking { PositionsFirst.steps(PositionsFirst::restore, watch = { watched++ }, stream = {}) }
        assertEquals(1, watched)
        assertTrue(PositionsFirst.diagLine(), PositionsFirst.diagLine().endsWith("; nothing open"))
        assertEquals(false, PositionsFirst.foundOpen)
        assertTrue(PositionsFirst.order.isDone(StartOrder.Step.STREAM))
        // A failing read: the watch is started (it decides for itself).
        PositionsFirst.resetForTest(started = true)
        PositionsFirst.appReady()
        runBlocking { PositionsFirst.steps({ throw IllegalStateException("unreadable") }, watch = { watched++ }, stream = {}) }
        assertEquals(2, watched)
        assertEquals(true, PositionsFirst.foundOpen)
    }

    @Test fun jarvissUnreadablePaperTradesCountAsOpenAtTheRestore() {
        // Review, 14 Oct: IraSolo.holding() reads a failed vault as "nothing", so the restore uses the strict read.
        val key = "jarvis.solo.trades"
        SecurePrefs.put(key, "{not json")
        assertFalse("the everyday read still says nothing", com.optionslab.app.ira.IraSolo.holding())
        assertTrue(runCatching { com.optionslab.app.ira.IraSolo.holdingOrThrow() }.isFailure)
        if (com.optionslab.app.BuildConfig.JARVIS) assertTrue("an unreadable book counts as open", runBlocking { PositionsFirst.restore() })
        // A trade without its closed flag cannot be told closed: open.
        SecurePrefs.put(key, JSONArray().put(JSONObject().put("d", "2026-10-14")).toString())
        assertTrue(runCatching { com.optionslab.app.ira.IraSolo.holdingOrThrow() }.isFailure)
        // Open and closed trades read normally.
        SecurePrefs.put(key, JSONArray().put(JSONObject().put("c", true)).toString())
        assertFalse(com.optionslab.app.ira.IraSolo.holdingOrThrow())
        SecurePrefs.put(key, JSONArray().put(JSONObject().put("c", true)).put(JSONObject().put("c", false)).toString())
        assertTrue(com.optionslab.app.ira.IraSolo.holdingOrThrow())
        // Empty book: nothing open.
        SecurePrefs.put(key, null)
        assertFalse(com.optionslab.app.ira.IraSolo.holdingOrThrow())
    }

    @Test fun theWatchIsAskedBeforeASlowBookIsRead() {
        // 17 Oct (Boss's diagnostics: "positions +8707 ms ... slowest book: strategies 4411 ms"): the watch waited for every book.
        PositionsFirst.resetForTest(started = true)
        PositionsFirst.appReady()
        val slowBook = kotlinx.coroutines.CompletableDeferred<Unit>()
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                // The restore finishes only once the watch was asked: it never waits on the book.
                PositionsFirst.steps({ slowBook.await(); events += "books read"; true },
                    watch = { events += "watch"; slowBook.complete(Unit) }, stream = { events += "stream" })
            }
        }
        assertEquals(listOf("watch", "books read", "stream"), events.toList())
        assertTrue(PositionsFirst.watchAskedAt > 0L)
        assertTrue(PositionsFirst.diagLine(), PositionsFirst.diagLine().startsWith("Start-up: watch asked +"))
    }

    @Test fun theBooksThatHoldNoPositionAreReadAfterThePositionsAndTheStream() {
        PositionsFirst.resetForTest(started = true)
        PositionsFirst.appReady()
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        runBlocking {
            PositionsFirst.steps({ false }, watch = { events += "watch" }, stream = { events += "stream" }, late = {
                // Read only once the positions and the stream are marked done.
                assertTrue(PositionsFirst.order.isDone(StartOrder.Step.POSITIONS))
                assertTrue(PositionsFirst.order.isDone(StartOrder.Step.STREAM))
                events += "grammar"; true
            })
        }
        // One that could not be read: the stream is asked again, and the start says something may be open.
        assertEquals(listOf("watch", "stream", "grammar", "stream"), events.toList())
        assertEquals(true, PositionsFirst.foundOpen)
        // The app's own late books: nothing open on a fresh phone, named in the diagnostics.
        PositionsFirst.resetForTest(started = true)
        assertFalse(runBlocking { PositionsFirst.restoreLate() })
        assertTrue(PositionsFirst.lateSlowest.orEmpty(), PositionsFirst.lateSlowest.orEmpty().startsWith("grammar"))
    }

    @Test fun theUnitTestsAppNeverWaits() {
        // Not started in this process: the parts that never trade read at once.
        PositionsFirst.resetForTest(started = false)
        val t = System.nanoTime()
        runBlocking { PositionsFirst.awaitPositions(); PositionsFirst.awaitStream() }
        assertTrue((System.nanoTime() - t) / 1_000_000 < 1_000)
        assertEquals("Start-up: not run in this process", PositionsFirst.diagLine())
        assertEquals(SharedKnowledge.EMPTY.size, SharedKnowledgeHost.snapshot.size)
    }
}
