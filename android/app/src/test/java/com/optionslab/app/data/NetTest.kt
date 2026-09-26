package com.optionslab.app.data

import com.optionslab.app.testing.Background
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.UpstoxStub
import com.optionslab.app.testing.UpstoxStub.Companion.json
import com.optionslab.app.testing.UpstoxStub.Companion.status
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPOutputStream

/**
 * [Net], the app's only Upstox client, against a local HTTPS stub: what is retried, how long it
 * waits (virtual time under runTest), what is never retried, and that errors stay generic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class NetTest : RobolectricTest() {
    private lateinit var upstox: UpstoxStub
    private val url = "https://api.upstox.com/v3/historical-candle/probe"

    /** Replies served in order; the last one repeats. */
    private fun script(vararg r: MockResponse) {
        val q = ConcurrentLinkedQueue(r.toList())
        val last = r.last()
        upstox.reply = { q.poll() ?: last }
    }

    @Before fun up() { upstox = UpstoxStub() }
    @After fun down() {
        upstox.close(); Background.reset()
        assertEquals("a test reached the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun anOkReplyIsParsedAndTheHostIsNeverReal() = runTest {
        script(json("""{"status":"success","n":7}"""))
        assertEquals(7, Net.getJson(url).getInt("n"))
        assertEquals(listOf("/v3/historical-candle/probe"), upstox.paths.toList())
    }

    @Test fun serverErrorsAreRetriedWithGrowingWaitsAndNoWaitAfterTheLast() = runTest {
        script(status(503))
        val t0 = testScheduler.currentTime
        try { Net.getJson(url, tries = 3); fail() } catch (e: Net.HttpFailure) { assertEquals(503, e.code) }
        assertEquals(3, upstox.paths.size)
        assertEquals("1.5 s after the first try, 3 s after the second, nothing after the last", 4_500L, testScheduler.currentTime - t0)
    }

    @Test fun rateLimitWaitsTheLongerOfRetryAfterAndTheBackoff() = runTest {
        script(
            status(429).setHeader("Retry-After", "30"),     // 30 s beats the 10 s backoff
            status(429).setHeader("Retry-After", "5"),      // the 20 s backoff beats 5 s
            status(429).setHeader("Retry-After", "Wed, 21 Oct 2025 07:28:00 GMT"),   // an HTTP-date is ignored: 40 s
            json("""{"ok":true}"""),
        )
        val t0 = testScheduler.currentTime
        assertTrue(Net.getJson(url, tries = 6).getBoolean("ok"))
        assertEquals(30_000L + 20_000L + 40_000L, testScheduler.currentTime - t0)
        assertEquals(4, upstox.paths.size)
    }

    @Test fun aHugeRetryAfterIsCapped() = runTest {
        script(status(429).setHeader("Retry-After", "86400"), json("{}"))
        val t0 = testScheduler.currentTime
        Net.getJson(url, tries = 2)
        assertEquals(160_000L, testScheduler.currentTime - t0)
    }

    @Test fun rateLimitedToTheEndFailsWithoutAFinalWait() = runTest {
        script(status(429))
        val t0 = testScheduler.currentTime
        try { Net.getJson(url, tries = 2); fail() } catch (e: Net.HttpFailure) {
            assertEquals(429, e.code); assertTrue(e.message!!.contains("rate-limiting"))
        }
        assertEquals("only the wait between the two tries", 10_000L, testScheduler.currentTime - t0)
    }

    @Test fun clientErrorsAreNotRetried() = runTest {
        script(status(404))
        val t0 = testScheduler.currentTime
        try { Net.getJson(url); fail() } catch (e: Net.HttpFailure) { assertEquals(404, e.code) }
        assertEquals(1, upstox.paths.size)
        assertEquals(0L, testScheduler.currentTime - t0)
    }

    @Test fun anAuthWallIsFatalNotEmpty() = runTest {
        script(status(401))
        try { Net.getJson(url); fail() } catch (e: Upstox.UpstoxError) { assertTrue(e.message!!.contains("authentication")) }
        assertEquals(1, upstox.paths.size)
    }

    @Test fun notJsonIsRetriedThenReportedPlainly() = runTest {
        script(MockResponse().setResponseCode(200).setBody("<html>maintenance</html>"))
        try { Net.getJson(url, tries = 2); fail() } catch (e: IOException) {
            assertEquals("Market data returned something that is not JSON", e.message)
        }
        assertEquals(2, upstox.paths.size)
    }

    @Test fun aDroppedConnectionIsOfflineAndRetried() = runTest {
        script(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START), json("""{"n":1}"""))
        assertEquals(1, Net.getJson(url, tries = 3).getInt("n"))
        script(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try { Net.getJson(url, tries = 2); fail() } catch (e: Net.Offline) { assertEquals("No connection to the market data service", e.message) }
    }

    @Test fun errorsNeverCarryTheUrlOrTheInstrument() {
        for (code in listOf(429, 500, 503, 403)) {
            val m = Net.HttpFailure(code).message!!
            assertFalse(m, m.contains("http") || m.contains("NSE_") || m.contains("upstox"))
        }
    }

    @Test fun cancellingTheCallerEndsAHungReadAndIsNotRetried() = runBlocking {
        script(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t0 = System.currentTimeMillis()
        val r = withTimeoutOrNull(1_000) { Net.getJson(url, tries = 5, timeoutMs = 60_000) }
        assertNull(r)
        assertTrue("the deadline, not the 60 s read timeout, ended it", System.currentTimeMillis() - t0 < 10_000)
        Thread.sleep(2_500)   // a retry would have gone out 1.5 s after the cancelled try
        assertEquals("a cancelled read is not retried", 1, upstox.paths.size)
    }

    @Test fun quickIntradayTriesTwiceOnly() = runTest {
        script(status(500))
        val key = Upstox.INDEX_KEYS.getValue("NIFTY")
        try { Net.intradayQuick(key); fail() } catch (e: Net.HttpFailure) { assertEquals(500, e.code) }
        assertEquals(listOf("/v3/historical-candle/intraday/$key/minutes/1", "/v3/historical-candle/intraday/$key/minutes/1"), upstox.paths.toList())
    }

    @Test fun candlesAreSortedAscendingAndErrorsAreNeverEmpty() {
        val day = LocalDate.of(2025, 10, 15)
        val bars = UpstoxStub.minutes(day, LocalTime.of(9, 15), 3, 100.0)
        val body = UpstoxStub.candles(bars).getBody()!!.readUtf8()
        val parsed = Net.parseCandles(JSONObject(body))
        assertEquals(bars.map { it.epochSecond }, parsed.map { it.epochSecond })
        assertEquals(bars.last().oi, parsed.last().oi)
        try {
            Net.parseCandles(JSONObject("""{"status":"error","errors":[{"errorCode":"UDAPI100","message":"bad key"}]}"""))
            fail()
        } catch (e: Upstox.UpstoxError) { assertEquals("UDAPI100: bad key", e.message) }
        try { Net.parseCandles(JSONObject("""{"status":"error"}""")); fail() } catch (e: Upstox.UpstoxError) { assertEquals("unknown Upstox error", e.message) }
        assertTrue(Net.parseCandles(JSONObject("""{"status":"success","data":{}}""")).isEmpty())
        // A six-column candle (no OI) reads as OI 0.
        val six = Net.parseCandles(JSONObject("""{"status":"success","data":{"candles":[["2025-10-15T09:15:00+05:30",1,2,0.5,1.5,10]]}}"""))
        assertEquals(0L, six.single().oi)
    }

    @Test fun historyIsFetchedInMonthWindowsAndDailyInDecades() = runTest {
        upstox.reply = { UpstoxStub.candles(emptyList()) }
        Net.history("NSE_FO|1", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 15))
        assertEquals(3, upstox.paths.size)
        assertTrue(upstox.paths.all { it.startsWith("/v3/historical-candle/NSE_FO|1/minutes/1/") })
        upstox.paths.clear()
        Net.daily("NSE_INDEX|Nifty 50", LocalDate.of(2000, 1, 1), LocalDate.of(2025, 1, 1))
        assertEquals("3601-day windows", 3, upstox.paths.size)
        assertTrue(upstox.paths.all { it.contains("/days/1/") })
    }

    private fun master(vararg rows: String): MockResponse {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(("[" + rows.joinToString(",") + "]").toByteArray()) }
        return MockResponse().setResponseCode(200).setBody(Buffer().write(out.toByteArray()))
    }

    @Test fun theMasterIsStreamedAndOnlyIndexOptionsKept() {
        val expiryMs = LocalDate.of(2025, 10, 21).atStartOfDay(com.optionslab.engine.IST).toInstant().toEpochMilli()
        upstox.reply = {
            master(
                """{"segment":"NSE_FO","instrument_type":"PE","underlying_symbol":"NIFTY","expiry":$expiryMs,"strike_price":24500.0,"lot_size":75,"instrument_key":"NSE_FO|111","trading_symbol":"NIFTY 24500 PE 21 OCT 25","exchange_token":null}""",
                """{"segment":"NSE_FO","instrument_type":"CE","asset_symbol":"BANKNIFTY","expiry":$expiryMs,"strike_price":55000.0,"lot_size":35,"instrument_key":"NSE_FO|222","trading_symbol":"BANKNIFTY 55000 CE"}""",
                """{"segment":"NSE_FO","instrument_type":"FUT","underlying_symbol":"NIFTY","expiry":$expiryMs,"instrument_key":"NSE_FO|333"}""",
                """{"segment":"NSE_EQ","instrument_type":"EQ","trading_symbol":"INFY","instrument_key":"NSE_EQ|INE009A01021"}""",
                """{"segment":"NSE_FO","instrument_type":"PE","underlying_symbol":"RELIANCE","expiry":$expiryMs,"strike_price":1400.0,"lot_size":500,"instrument_key":"NSE_FO|444"}""",
            )
        }
        val got = Net.fetchMaster()
        assertEquals(listOf("NSE_FO|111", "NSE_FO|222"), got.map { it.instrumentKey })
        assertEquals(Right.PE, got[0].right); assertEquals(LocalDate.of(2025, 10, 21), got[0].expiry); assertEquals(75, got[0].lotSize)
        assertEquals("BANKNIFTY", got[1].underlying)
        assertEquals(listOf("/market-quote/instruments/exchange/complete.json.gz"), upstox.paths.toList())

        assertEquals(mapOf(("NSE_EQ" to "INFY") to "NSE_EQ|INE009A01021"), Net.fetchEquityKeys(setOf("NSE_EQ" to "INFY", "NSE_EQ" to "NOPE")))
        assertTrue(Net.fetchEquityKeys(emptySet()).isEmpty())

        upstox.reply = { status(503) }
        try { Net.fetchMaster(); fail() } catch (e: Net.HttpFailure) { assertEquals(503, e.code) }
        try { Net.fetchEquityKeys(setOf("NSE_EQ" to "INFY")); fail() } catch (e: Net.HttpFailure) { assertEquals(503, e.code) }
        upstox.reply = { MockResponse().setResponseCode(200).setBody("not gzip") }
        try { Net.fetchMaster(); fail() } catch (_: Net.Offline) {}
    }
}
