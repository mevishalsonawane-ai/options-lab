package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.LiveRecord
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
 * The live recording's wiring (Data Brain L8): a signal, a live order and its fill are snapshotted from memory (the chain at
 * ATM +/-10 from the stream's ticks, the traded option's five-level book, the index and VIX) and written encrypted; the
 * minute rows are written plain; the hooks read nothing from the settings vault; recording that stops while NSE is open
 * with the watch running is an open problem; the export zips the folder as stored.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveRecorderTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val bnToken = 260_105L
    private val vixToken = 264_969L
    private val expiry = AutomationSupport.expiryAfter(7)
    private val sym = "BANKNIFTY26OCT52000CE"
    private val strikes = (-12..12).map { 52_000.0 + it * 100 }

    private fun tokenOf(strike: Double, right: String) = 23_000_000L + (strike / 100).toLong() * 2 + if (right == "CE") 0 else 1

    @Before fun up() {
        kite = FakeKite()
        kite.keepAlive = true
        kite.login()
        for (k in strikes) for (r in listOf("CE", "PE")) {
            val s = if (k == 52_000.0 && r == "CE") sym else "BANKNIFTY26OCT${k.toInt()}$r"
            kite.instruments += FakeKite.Ins(tokenOf(k, r), s, "BANKNIFTY", expiry, k, r, 30)
        }
        runBlocking { Broker.instruments() }
        KiteStream.resetForTest()
        LiveRecorder.attachForTest(context)
        LiveRecorder.resetForTest()
    }

    @After fun down() {
        LiveRecorder.detachForTest()
        SecurePrefs.countedThread = null
        Market.testClock = null
        KiteStream.stop()
        KiteStream.resetForTest()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun feed(atMs: Long = System.currentTimeMillis(), spot: Double = 52_030.0) {
        val ticks = ArrayList<KiteTicks.Tick>()
        ticks += KiteTicks.Tick(bnToken, spot)
        ticks += KiteTicks.Tick(vixToken, 12.5)
        for (k in strikes) {
            ticks += KiteTicks.Tick(tokenOf(k, "CE"), maxOf(spot - k, 0.0) + 150.0, oi = 100_000, bid = 199.95, ask = 200.05, bidQty = 750, askQty = 600,
                depth = KiteTicks.Depth((0 until 5).map { KiteTicks.Level(199.95 - it * 0.05, 750L, 3) }, (0 until 5).map { KiteTicks.Level(200.05 + it * 0.05, 600L, 2) }))
            ticks += KiteTicks.Tick(tokenOf(k, "PE"), maxOf(k - spot, 0.0) + 150.0, oi = 90_000)
        }
        KiteStream.feedForTest(ticks, atMs)
    }

    private fun dayDirs(): List<File> = LiveRecorder.rootForTest()?.listFiles()?.filter { it.isDirectory }.orEmpty()

    private fun tradeLines(): List<String> {
        val f = dayDirs().map { File(it, LiveRecord.TRADES_FILE) }.single { it.exists() }
        return f.readLines().filter { it.isNotBlank() }.flatMap { l ->
            GZIPInputStream(Vault.decrypt(android.util.Base64.decode(l, android.util.Base64.NO_WRAP)).inputStream()).bufferedReader().readLines()
        }
    }

    @Test fun aSignalIsSnapshottedFromMemoryAndWrittenEncrypted() {
        feed()
        LiveRecorder.signal("Liquidity BANKNIFTY", sym, "BANKNIFTY", "CE", 200.0, paper = true)
        assertEquals(1, LiveRecorder.queuedForTest())
        runBlocking { LiveRecorder.drain() }
        val file = dayDirs().map { File(it, LiveRecord.TRADES_FILE) }.single { it.exists() }
        assertFalse("encrypted on disk", file.readText().contains("BANKNIFTY"))
        val lines = tradeLines()
        assertTrue(lines[0], lines[0].startsWith("{\"t\":\"H\",\"schema\":1,"))
        val s = lines[1]
        assertTrue(s, s.startsWith("{\"t\":\"S\","))
        assertTrue(s, "\"arm\":\"Liquidity BANKNIFTY\"" in s && "\"spot\":52030" in s && "\"vix\":12.5" in s)
        // The chain: ATM +/-10 strikes, both rights (42 options), with OI and an IV.
        val chain = s.substringAfter("\"chain\":[").substringBefore("]]")
        assertEquals(42, chain.split("],[").size)
        assertTrue(s, "[51000,\"CE\"," in s && "[53000,\"PE\"," in s && "[50900," !in s)
        // The traded option's five-level book.
        assertTrue(s, "\"bids\":[[199.95,750,3],[199.9,750,3],[199.85,750,3],[199.8,750,3],[199.75,750,3]]" in s)
        assertTrue(s, "\"missing\"" !in s)
        assertTrue(LiveRecorder.lastWriteMs != null)
    }

    @Test fun aLiveOrderAndItsFillAreSnapshotted() {
        feed()
        val o = Kite.Order(sym, Kite.Side.BUY, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", "iraorb")
        FillSlippage.sent("X1", o, exit = false, arm = "orb", intended = 200.0)
        FillSlippage.ended("X1", "COMPLETE", 200.4, 30)
        runBlocking { LiveRecorder.drain() }
        val lines = tradeLines()
        val order = lines.single { it.startsWith("{\"t\":\"O\"") }
        val fill = lines.single { it.startsWith("{\"t\":\"F\"") }
        assertTrue(order, "\"arm\":\"orb\"" in order && "\"intended\":200" in order && "\"sent\":" in order)
        assertTrue(fill, "\"price\":200.4" in fill && "\"qty\":30" in fill && "\"paper\":false" in fill && "\"filled\":" in fill)
    }

    @Test fun theHooksReadNothingFromTheSettingsVault() {
        feed()
        val o = Kite.Order(sym, Kite.Side.BUY, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", "iraorb")
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        LiveRecorder.signal("ORB", sym, "BANKNIFTY", "CE", 200.0, paper = false)
        LiveRecorder.order(o, false, "orb", 200.0, System.currentTimeMillis())
        LiveRecorder.fill(sym, "BUY", false, 30, 200.4, 200.0, paper = false, arm = "orb")
        SecurePrefs.countedThread = null
        assertEquals(0L, SecurePrefs.readsForTest.get())
        assertEquals(3, LiveRecorder.queuedForTest())
        assertTrue("nothing written on the caller's thread", dayDirs().isEmpty())
    }

    @Test fun theMinuteRowsArePlainGzipWithBarAndChain() {
        val now = System.currentTimeMillis()
        val start = now - Math.floorMod(now, 60_000L) - 60_000L
        // The path must reach back before the minute's start for its open to be known.
        feed(start - 2_000, 52_010.0)
        feed(start + 5_000, 52_000.0)
        feed(start + 20_000, 52_080.0)
        feed(start + 40_000, 51_990.0)
        feed(start + 55_000, 52_030.0)
        runBlocking { LiveRecorder.minute(now) }
        val f = dayDirs().map { File(it, LiveRecord.MARKET_FILE) }.single { it.exists() }
        val lines = GZIPInputStream(f.inputStream()).bufferedReader().readLines()
        assertTrue(lines[0], lines[0].startsWith("{\"t\":\"H\",\"schema\":1,\"file\":\"market.jsonl.gz\""))
        val bn = lines.single { "\"ix\":\"BANKNIFTY\"" in it }
        assertTrue(bn, "\"o\":52000,\"h\":52080,\"l\":51990,\"c\":52030" in bn)
        assertTrue(bn, "\"vix\":12.5" in bn && "\"chain\":[[51000," in bn)
        // NIFTY has nothing in memory here: its row says what is missing.
        val n = lines.single { "\"ix\":\"NIFTY\"" in it }
        assertTrue(n, "\"missing\":[" in n && "\"spot\"" in n)
    }

    @Test fun aStopWhileOpenWithTheWatchRunningIsAProblem() {
        val zone = com.optionslab.engine.IST
        // A Wednesday at 11:00 (an ordinary trading day).
        val at = java.time.LocalDate.of(2026, 10, 14).atTime(11, 0).atZone(zone)
        Market.testClock = java.time.Clock.fixed(at.toInstant(), zone)
        ProblemsHost.resetForTest()
        val nowMs = at.toInstant().toEpochMilli()
        LiveRecorder.resetForTest(startedAt = nowMs - 30 * 60_000L)
        LiveRecorder.healthCheck(alive = false, nowMs = nowMs)
        assertTrue(ProblemsHost.open(nowMs = nowMs).none { it.key == LiveRecorder.PROBLEM })
        LiveRecorder.healthCheck(alive = true, nowMs = nowMs)
        assertTrue(ProblemsHost.open(nowMs = nowMs).any { it.key == LiveRecorder.PROBLEM })
        // Written again: cleared.
        feed()
        runBlocking { LiveRecorder.minute(System.currentTimeMillis()) }
        LiveRecorder.healthCheck(alive = true, nowMs = LiveRecorder.lastWriteMs!! + 1_000)
        assertTrue(ProblemsHost.open(nowMs = nowMs).none { it.key == LiveRecorder.PROBLEM })
    }

    @Test fun theExportZipsTheFolderAsStoredAndRotationKeepsTheSetting() {
        feed()
        LiveRecorder.signal("ORB", sym, "BANKNIFTY", "CE", 200.0)
        runBlocking { LiveRecorder.drain(); LiveRecorder.minute(System.currentTimeMillis()) }
        // An old day past the 60 days kept.
        val old = File(LiveRecorder.rootForTest(), "2020-01-02").apply { mkdirs() }
        File(old, LiveRecord.MARKET_FILE).writeText("x")
        val zip = LiveRecorder.exportZip(context)
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.toString(), "README.txt" in names && names.any { it.endsWith("/" + LiveRecord.TRADES_FILE) } && names.any { it.endsWith("/" + LiveRecord.MARKET_FILE) })
        // The trades part stays encrypted in the zip.
        java.util.zip.ZipFile(zip).use { z ->
            val e = z.entries().toList().first { it.name.endsWith(LiveRecord.TRADES_FILE) }
            assertFalse(z.getInputStream(e).readBytes().toString(Charsets.US_ASCII).contains("ORB"))
        }
        runBlocking { LiveRecorder.rotate(Market.today()) }
        assertFalse(old.exists())
        val st = LiveRecorder.status()
        assertEquals(1, st.days)
        assertEquals(60, st.keepDays)
        assertTrue(st.bytes > 0)
    }
}
