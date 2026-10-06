package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.ira.Events
import com.optionslab.ira.Flows
import com.optionslab.ira.Headline
import com.optionslab.ira.MarketRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream

/**
 * The market recorder ([MarketRecorder]) over a stand-in for the app's feeds: what one pass writes (news first seen, the
 * calendar, FII/DII, levels, futures with basis, the book, the chain with IV and the Rs 1-5 options), encrypted at rest
 * and read back; the window (nothing before 09:00, after 15:35 or on a holiday); a feed that fails or hangs is a gap and
 * never reaches the watch; no token or key is ever written; the zip export.
 */
class MarketRecorderTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var day: LocalDate
    private val token = "SESSIONtoken0123456789"
    private val apiKey = "apikey987654"
    private lateinit var expiry: LocalDate

    private open inner class Feeds : MarketRecorder.Source {
        var loggedIn = true
        var quoteCalls = 0
        override fun loggedIn() = loggedIn
        override suspend fun futures() = listOf(
            Kite.Future(1L, "NIFTY26OCTFUT", "NIFTY", expiry.plusDays(20), 75, "NFO"),
            Kite.Future(2L, "BANKNIFTY26OCTFUT", "BANKNIFTY", expiry.plusDays(20), 35, "NFO"),
            Kite.Future(3L, "NIFTY26SEPFUT", "NIFTY", day.minusDays(7), 75, "NFO"))
        override suspend fun options(): List<Kite.Instrument> = (0..60).flatMap { i ->
            val k = 24_000.0 + 50 * i
            listOf(Kite.Instrument(1000L + i, "NIFTYT${k.toInt()}CE", "NIFTY", expiry, k, 75, Right.CE, 0.05),
                Kite.Instrument(2000L + i, "NIFTYT${k.toInt()}PE", "NIFTY", expiry, k, 75, Right.PE, 0.05))
        }
        override fun indexKey(name: String) = when (name) { "NIFTY" -> "NSE:NIFTY 50"; "BANKNIFTY" -> "NSE:NIFTY BANK"; else -> null }
        override fun indexToken(name: String): Long? = null
        override suspend fun quotes(keys: Map<String, Long?>): Map<String, MarketRecord.Quote> {
            quoteCalls++
            return keys.keys.associateWith { k ->
                when {
                    k == "NSE:NIFTY 50" -> MarketRecord.Quote(25_010.0)
                    k == "NSE:NIFTY BANK" -> MarketRecord.Quote(56_000.0)
                    k.endsWith("FUT") -> MarketRecord.Quote(25_080.0, 1_000_000, 9_000_000, 25_079.5, 75, 25_080.5, 150, 400_000, 380_000)
                    else -> {
                        val strike = Regex("(\\d+)(CE|PE)$").find(k)!!.groupValues[1].toDouble()
                        val call = k.endsWith("CE")
                        val intrinsic = if (call) maxOf(0.0, 25_010 - strike) else maxOf(0.0, strike - 25_010)
                        val p = maxOf(1.5, intrinsic + 120 - kotlin.math.abs(strike - 25_000) * 0.2)
                        MarketRecord.Quote(p, 5_000, 70_000, p - 0.05, 750, p + 0.05, 900, 30_000, 28_000)
                    }
                }
            }
        }
        override suspend fun minuteBars(token: Long, day: LocalDate): List<Upstox.Bar> =
            (9 * 60 + 15 until 15 * 60 + 30).map { m -> Upstox.Bar(day.atTime(m / 60, m % 60).atZone(IST).toEpochSecond(), 1.0, 2.0, 0.5, 1.5, 10, 20) }
        override fun news() = Triple(listOf(
            Headline("RBI keeps repo rate unchanged, $token", "https://rbi.example/press?access_token=$token", "RBI", day.atTime(9, 50).atZone(IST).toInstant(), -0.1,
                listOf(com.optionslab.ira.Market.NIFTY, com.optionslab.ira.Market.BANKNIFTY)),
            Headline("Old story", "https://old.example/1", "Livemint", day.minusDays(5).atTime(10, 0).atZone(IST).toInstant(), 0.0, emptyList())),
            day.atTime(9, 58, 41).atZone(IST).toInstant(), listOf("SEBI"))
        override fun events() = listOf(Events.Event(day.plusDays(1), "NIFTY expiry"))
        override suspend fun flows() = listOf(Flows.Flow("FII", "05-Oct-2026", 10_000.0, 11_000.0, -1_000.0))
        override fun matters(h: Headline) = h.title.startsWith("RBI")
        override fun secrets() = listOf(token, apiKey)
    }

    @Before fun up() {
        day = AutomationSupport.tradingDayAt(10, 0).toLocalDate()
        expiry = day.plusDays(1)
        while (!Market.isTradingDay(expiry)) expiry = expiry.plusDays(1)
        MarketRecorder.wipe()
        MarketRecorder.on = true
    }

    @After fun down() {
        MarketRecorder.testSource = null; MarketRecorder.testNow = null
        Market.testClock = null
        MarketRecorder.wipe()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(h: Int, m: Int, s: Int = 5, d: LocalDate = day): ZonedDateTime {
        val z = d.atTime(h, m, s).atZone(IST)
        MarketRecorder.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
        return z
    }

    private fun lines(d: LocalDate = day) = MarketRecorder.readDay(d).lines

    @Test fun onePassWritesEveryKindEncryptedAndReadsBack() {
        MarketRecorder.testSource = Feeds()
        runBlocking { MarketRecorder.pass(at(10, 0, 3)) }
        val l = lines()
        assertEquals("H,v1,$day", l.first())
        // News first seen at Jarvis's read (09:58:41), with its own tags; the story before the last close is not repeated.
        val n = l.single { it.startsWith("N,") }
        assertTrue(n, n.startsWith("N,09:58:41,RBI,"))
        assertTrue(n, n.contains(",NIFTY;BANKNIFTY,1,"))
        assertTrue(l.any { it == "G,10:00:03,news: SEBI,the feed did not answer" })
        assertTrue(l.contains("E,${day.plusDays(1)},,NIFTY expiry,0"))
        assertTrue(l.contains("X,05-Oct-2026,FII,10000,11000,-1000"))
        assertTrue(l.contains("S,10:00:03,NIFTY,25010"))
        // The current month's future (the expired one passed over), with its basis, and its book.
        assertTrue(l.any { it.startsWith("F,10:00:03,NIFTY,NIFTY26OCTFUT,") && it.endsWith(",25010,70") })
        assertFalse(l.any { "NIFTY26SEPFUT" in it })
        assertTrue(l.any { it.startsWith("D,10:00:03,NIFTY26OCTFUT,25080,25079.5,75,25080.5,150,400000,380000,1,r") })
        // The book at the money: ATM +/-2, both rights (10 rows); the chain ATM +/-15 (62 rows) with an IV.
        assertEquals(10, l.count { it.startsWith("D,10:00:03,NIFTYT") })
        val chain = l.filter { it.startsWith("C,10:00,NIFTY,") }
        assertEquals(62, chain.size)
        assertTrue(chain.any { MarketRecord.fields(it)[9].isNotEmpty() })
        assertTrue("the Rs 1-5 options in the chain", l.any { it.startsWith("L,10:00,NIFTY,") })
        // At rest: nothing readable without the vault's key.
        val raw = java.io.File(context.noBackupFilesDir, "recorder/$day.rec").readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("NIFTY"))
        // A second pass the same minute writes no prices again; the chain waits 5 minutes.
        runBlocking { MarketRecorder.pass(at(10, 0, 40)) }
        assertEquals(1, lines().count { it.startsWith("S,") && it.contains(",NIFTY,") })
        runBlocking { MarketRecorder.pass(at(10, 1, 5)) }
        assertEquals(62, lines().count { it.startsWith("C,") })
        runBlocking { MarketRecorder.pass(at(10, 5, 5)) }
        assertEquals(124, lines().count { it.startsWith("C,") })
        assertEquals("the story once a day", 1, lines().count { it.startsWith("N,") })
    }

    @Test fun noTokenOrKeyIsEverWritten() {
        MarketRecorder.testSource = Feeds()
        runBlocking { MarketRecorder.pass(at(10, 0)) }
        val all = lines().joinToString("\n")
        assertFalse(token in all); assertFalse(apiKey in all)
        assertFalse(Regex("(?i)access_token=[^*]").containsMatchIn(all))
        val out = ByteArrayOutputStream()
        MarketRecorder.export(out)
        val text = String(out.toByteArray(), Charsets.ISO_8859_1) + unzip(out.toByteArray()).values.joinToString()
        assertFalse(token in text); assertFalse(apiKey in text)
    }

    @Test fun theFuturesCandlesAreReadAfterTheCloseOnce() {
        MarketRecorder.testSource = Feeds()
        runBlocking { MarketRecorder.pass(at(15, 29, 10)) }
        assertEquals(2 * (15 * 60 + 29 - (9 * 60 + 15)), lines().count { it.startsWith("B,") })   // to 15:28, both futures
        runBlocking { MarketRecorder.pass(at(15, 30, 10)) }
        assertEquals(2 * (15 * 60 + 30 - (9 * 60 + 15)), lines().count { it.startsWith("B,") })   // 15:29 added, nothing twice
        runBlocking { MarketRecorder.pass(at(15, 31, 10)) }
        assertEquals(2 * (15 * 60 + 30 - (9 * 60 + 15)), lines().count { it.startsWith("B,") })
    }

    @Test fun withoutZerodhaOnlyTheNewsCalendarAndFlowsAndOneGap() {
        val f = Feeds().apply { loggedIn = false }
        MarketRecorder.testSource = f
        runBlocking { MarketRecorder.pass(at(10, 0)); MarketRecorder.pass(at(10, 1)) }
        val l = lines()
        assertTrue(l.any { it.startsWith("N,") })
        assertFalse(l.any { it.startsWith("S,") || it.startsWith("F,") || it.startsWith("C,") })
        assertEquals(1, l.count { it.contains("no Zerodha login") })
        assertEquals(0, f.quoteCalls)
    }

    @Test fun onlyInsideTheWindowOnTradingDays() {
        val f = Feeds()
        MarketRecorder.testSource = f
        at(8, 59, 59); MarketRecorder.kick(); waitIdle()
        at(15, 35, 1); MarketRecorder.kick(); waitIdle()
        var holiday = day.plusDays(1)
        while (Market.isTradingDay(holiday)) holiday = holiday.plusDays(1)
        at(11, 0, 0, holiday); MarketRecorder.kick(); waitIdle()
        assertTrue("nothing recorded outside the window", MarketRecorder.days().isEmpty())
        MarketRecorder.on = false
        at(11, 0); MarketRecorder.kick(); waitIdle()
        assertTrue("nothing while switched off", MarketRecorder.days().isEmpty())
        MarketRecorder.on = true
        at(11, 0); MarketRecorder.kick(); waitIdle()
        assertEquals(listOf(day), MarketRecorder.days().map { it.first })
    }

    @Test fun aFailingOrHangingFeedIsAGapAndNeverReachesTheWatch() {
        val broken = object : Feeds() {
            override suspend fun quotes(keys: Map<String, Long?>): Map<String, MarketRecord.Quote> = throw java.io.IOException("https://api.kite.trade/quote?access_token=$token")
            override suspend fun flows(): List<Flows.Flow> = throw IllegalStateException("boom")
            override fun news(): Triple<List<Headline>, Instant?, List<String>> = throw RuntimeException("feed")
        }
        MarketRecorder.testSource = broken
        runBlocking { MarketRecorder.pass(at(10, 0)) }
        val l = lines()
        assertTrue(l.contains("G,10:00:05,market,IOException"))
        assertTrue(l.contains("G,10:00:05,FII/DII,IllegalStateException"))
        assertTrue(l.contains("G,10:00:05,news,RuntimeException"))
        assertTrue("the calendar still recorded", l.any { it.startsWith("E,") })
        assertFalse("never the failure's message", l.any { "kite.trade" in it || token in it })
        assertEquals(3, MarketRecorder.status().gapsToday)
        // A feed that hangs: the watch's kick returns at once, a second kick while it runs is skipped, nothing throws.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        MarketRecorder.testSource = object : Feeds() {
            override suspend fun quotes(keys: Map<String, Long?>): Map<String, MarketRecord.Quote> { gate.await(); return emptyMap() }
        }
        at(10, 7)
        val t0 = System.nanoTime()
        MarketRecorder.kick(); MarketRecorder.kick()
        assertTrue("the kick never waits on the pass", (System.nanoTime() - t0) / 1_000_000 < 500)
        assertTrue(MarketRecorder.busy >= 1)
        gate.complete(Unit)
        waitIdle()
    }

    @Test fun theStatusCountsDaysBytesAndGapsAndJarvisSaysIt() {
        MarketRecorder.testSource = Feeds().apply { loggedIn = false }
        runBlocking { MarketRecorder.pass(at(10, 0)) }
        val s = MarketRecorder.status()
        assertEquals(1, s.days); assertTrue(s.bytes > 0); assertEquals(day, s.first); assertTrue("gaps today: ${s.gapsToday}", s.gapsToday >= 1)   // the market part, plus any feed the fake leaves empty
        assertTrue(s.lastWrite != null)
        assertTrue(MarketRecorder.answer(), MarketRecorder.answer().startsWith("1 trading day of market data recorded"))
    }

    @Test fun theExportIsAZipOfOneCsvADayWithAReadme() {
        MarketRecorder.testSource = Feeds()
        runBlocking { MarketRecorder.pass(at(10, 0)) }
        val out = ByteArrayOutputStream()
        assertEquals(1, MarketRecorder.export(out))
        val files = unzip(out.toByteArray())
        assertEquals(setOf("README.txt", "market-$day.csv"), files.keys)
        assertEquals(lines(), files.getValue("market-$day.csv").trimEnd('\n').split('\n'))
        assertTrue(files.getValue("README.txt").contains("C,time,underlying,expiry,strike"))
    }

    @Test fun rotationDeletesDaysOlderThanTwelveMonths() {
        MarketRecorder.testSource = Feeds().apply { loggedIn = false }
        val old = day.minusMonths(13)
        MarketRecorder.append(old, listOf("H,v1,$old"))
        MarketRecorder.append(day.minusDays(3), listOf("H,v1,x"))
        runBlocking { MarketRecorder.pass(at(10, 0)) }
        assertEquals(listOf(day.minusDays(3), day), MarketRecorder.days().map { it.first })
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return out
    }

    /** Waits for the recorder's pass started by a kick (its own scope) to finish. */
    private fun waitIdle() {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            if (!MarketRecorder.running) return
            Thread.sleep(20)
        }
        throw AssertionError("the recorder's pass did not finish")
    }
}
