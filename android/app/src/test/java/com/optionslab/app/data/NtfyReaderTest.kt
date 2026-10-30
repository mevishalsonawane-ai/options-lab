package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * ntfy alerts' reader ([NtfyReader], 25 Oct) against a fake ntfy server on this machine (never the internet): the catch-up poll
 * then the stream from the last id read, keepalive and open events skipped, a duplicate across the poll and the stream read once,
 * a failure retried with a backoff (and the poll again), the access token sent, "Test" reading without trading or marking
 * anything read, and the topic never in the diagnostics.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class NtfyReaderTest : RobolectricTest() {
    private lateinit var server: MockWebServer
    private val topic = "a-long-random-topic-0123456789"
    private var nowSec = 0L

    @Before fun up() {
        server = MockWebServer(); server.start()
        val t = AutomationSupport.tradingDayAt(10, 0)
        Market.testClock = Clock.fixed(t.toInstant(), IST)
        nowSec = t.toEpochSecond()
        NtfyReader.testServer = server.url("/").toString().trimEnd('/')
        // No stock list download in these tests (never the internet).
        StockOptions.testFetch = { emptyList<com.optionslab.engine.Upstox.Contract>() to emptyMap() }
        NtfyReader.testInHours = false
        SecurePrefs.put(NtfyReader.K_TOPIC, topic)
        runBlocking { NtfyArm.setOn(true, log = false) }
        NtfyReader.testInHours = true
    }

    @After fun down() {
        NtfyReader.wipe()
        NtfyReader.hoursCheckMs = 30_000L
        Market.testClock = null
        server.shutdown()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun line(id: String, text: String, event: String = "message", at: Long = nowSec) =
        """{"id":"$id","time":$at,"expires":${at + 43200},"event":"$event","topic":"$topic","message":"$text","priority":3}""" + "\n"
    private fun keepalive(at: Long = nowSec) = """{"id":"ka$at","time":$at,"event":"keepalive","topic":"$topic"}""" + "\n"
    private fun open(at: Long = nowSec) = """{"id":"op$at","time":$at,"event":"open","topic":"$topic"}""" + "\n"

    private fun waitFor(what: String, ms: Long = 15_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { assertTrue("timed out: $what", System.currentTimeMillis() < end); Thread.sleep(20) }
    }
    private fun take(): RecordedRequest = server.takeRequest(10, TimeUnit.SECONDS)!!

    @Test fun pollThenStreamEachMessageOnceAndKeepalivesSkipped() {
        // The catch-up poll: two messages and a keepalive. Then the stream: the poll's last again (a duplicate), a new one, then it ends.
        server.enqueue(MockResponse().setBody(line("m1", "NIFTY CE PE") + keepalive() + line("m2", "BUY NIFTY 25000 CE")))
        server.enqueue(MockResponse().setBody(open() + line("m2", "BUY NIFTY 25000 CE") + keepalive() + line("m3", "SENSEX PUT")))
        // After the stream ends: the reconnect polls again from the last id read (nothing new), then a stream that stays quiet.
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        NtfyReader.ensure()
        waitFor("three messages") { NtfyArm.view.value.alerts.size == 3 }
        val poll = take()
        assertTrue(poll.path, poll.path!!.startsWith("/$topic/json?poll=1&since=${nowSec - 5 * 60}"))
        val stream = take()
        assertEquals("/$topic/json?since=m2", stream.path)
        val again = take()
        assertEquals("the reconnect catches up from the last id read", "/$topic/json?poll=1&since=m3", again.path)
        assertEquals(listOf("m3", "m2", "m1"), NtfyArm.view.value.alerts.map { it.id })
        assertEquals("m3", NtfyArm.view.value.last)
        assertTrue(NtfyReader.status.value.running)
        NtfyReader.stop("test")
        assertFalse(NtfyReader.status.value.running)
    }

    @Test fun aFailureIsRetriedWithABackoffAndTheTokenIsSent() {
        SecurePrefs.put(NtfyReader.K_TOKEN, "tk_abc")
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(line("m1", "BUY NIFTY CE")))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        NtfyReader.ensure()
        val first = take()
        assertEquals("Bearer tk_abc", first.getHeader("Authorization"))
        waitFor("the retry") { NtfyArm.view.value.alerts.size == 1 }
        assertTrue(take().path!!.contains("poll=1"))
        assertEquals(0, NtfyReader.status.value.failures)
        assertTrue(Diag.lines().any { it.contains("reader: server said 500") })
        assertFalse("the topic is never in the diary", Diag.lines().any { it.contains(topic) || it.contains("tk_abc") })
    }

    @Test fun noTopicOrOutOfHoursMeansNotRunningOnOrOff() {
        // 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow"): off, the reader still runs in its hours with a
        // topic (each buy becomes a would-be trade); out of hours it never runs, on or off.
        NtfyReader.testInHours = false
        runBlocking { NtfyArm.setOn(false, log = false) }
        NtfyReader.ensure()
        assertFalse(NtfyReader.status.value.running)
        assertTrue("off is still armed (shadow)", NtfyReader.armedHint)
        // 26 Oct: out of hours BEFORE switching on (switching on starts the reader at once when in hours: a request could slip in).
        runBlocking { NtfyArm.setOn(true, log = false) }
        NtfyReader.ensure()
        assertFalse(NtfyReader.status.value.running)
        assertEquals(0, server.requestCount)
        assertEquals("Type your topic first.", runBlocking { SecurePrefs.put(NtfyReader.K_TOPIC, null); NtfyReader.test() })
    }

    /** 25 Oct review: the stream stayed open all night (the hours were checked only by the in-session round). */
    @Test fun theReaderStopsItselfOutsideMarketHours() {
        NtfyReader.hoursCheckMs = 50
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        NtfyReader.ensure()
        waitFor("connected") { NtfyReader.status.value.connected }
        waitFor("the stream asked for") { server.requestCount == 2 }
        // The F&O close passes while the stream is open: the timer stops it, with no new request.
        NtfyReader.testInHours = false
        waitFor("stopped by the timer") { !NtfyReader.status.value.running }
        assertTrue(NtfyReader.status.value.said, NtfyReader.status.value.said.contains("outside market hours"))
        Thread.sleep(300)
        assertEquals(2, server.requestCount)
    }

    @Test fun aReconnectAfterHoursNeverConnects() {
        NtfyReader.hoursCheckMs = 60_000
        server.enqueue(MockResponse().setBody(""))
        // The stream ends; by then the market has closed: no reconnect.
        server.enqueue(MockResponse().setBody(keepalive()).setBodyDelay(800, TimeUnit.MILLISECONDS))
        NtfyReader.ensure()
        waitFor("connected") { NtfyReader.status.value.connected }
        NtfyReader.testInHours = false
        waitFor("the loop ended") { !NtfyReader.status.value.running }
        Thread.sleep(1_500)
        assertEquals("no poll after hours", 2, server.requestCount)
        assertTrue(NtfyReader.status.value.said, NtfyReader.status.value.said.contains("outside market hours"))
    }

    /** 25 Oct review: a new prefix applied only after a reconnect; it is read for each message now, and saving it restarts the reader. */
    @Test fun aNewPrefixAppliesToTheNextMessageAtOnce() {
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setBody(line("m1", "BUY NIFTY 25000 CE")).setBodyDelay(1_500, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        NtfyReader.ensure()
        waitFor("connected") { NtfyReader.status.value.connected }
        SecurePrefs.put(NtfyReader.K_PREFIX, "IRA:")
        waitFor("the message") { NtfyArm.view.value.alerts.size == 1 }
        assertTrue(NtfyArm.view.value.alerts.single().why, NtfyArm.view.value.alerts.single().why.contains("prefix"))
        // Saving a prefix through the page restarts the reader (a new connection: the catch-up poll again).
        val before = server.requestCount
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        NtfyReader.setPrefix("IRB:")
        waitFor("reconnected") { server.requestCount > before }
        assertTrue(NtfyReader.status.value.running)
        NtfyReader.stop("test")
    }

    @Test fun testFetchesWithoutTradingOrMarkingRead() {
        server.enqueue(MockResponse().setBody(line("m1", "BUY NIFTY 25000 CE") + line("m2", "NIFTY CE PE") + keepalive()))
        val said = runBlocking { NtfyReader.test() }
        assertTrue(said, said.startsWith("Connected. 2 message(s) in the last hour."))
        assertTrue(said, said.contains("would buy: NIFTY 25000 CE") && said.contains("not a trade: both CE and PE"))
        assertTrue(said, said.contains("Nothing was traded or marked read"))
        assertEquals("/$topic/json?poll=1&since=1h", take().path)
        assertTrue(NtfyArm.view.value.alerts.isEmpty())
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runBlocking { NtfyReader.test() }.contains("check the access token"))
        server.enqueue(MockResponse().setBody(""))
        assertTrue(runBlocking { NtfyReader.test() }.contains("No messages in the last hour"))
    }

    @Test fun settingsAreCheckedAndTheTopicNeverShown() {
        // A saved setting restarts the reader when it should run: here it must not (no test reaches a real server).
        NtfyReader.testInHours = false
        assertTrue(NtfyReader.setTopic("bad topic").contains("Not saved"))
        assertEquals(topic, SecurePrefs.getString(NtfyReader.K_TOPIC))
        assertTrue(NtfyReader.setTopic("alerts1").contains("anyone who guesses it"))
        assertFalse(NtfyReader.setTopic("alerts1").contains("alerts1"))
        assertTrue(NtfyReader.setServer("http://ntfy.sh").contains("Not saved"))
        NtfyReader.testServer = null
        assertEquals("Server: https://ntfy.example.org.", NtfyReader.setServer("https://ntfy.example.org/"))
        assertEquals("https://ntfy.example.org", NtfyReader.config().server)
        assertEquals("Server: https://ntfy.sh.", NtfyReader.setServer(""))
        assertEquals(null, SecurePrefs.getString(NtfyReader.K_SERVER))
        assertTrue(NtfyReader.setToken("a b").contains("Not saved"))
        assertEquals("Access token saved (****).", NtfyReader.setToken("tk_1"))
        assertEquals("Access token cleared.", NtfyReader.setToken(""))
        assertEquals("No prefix: every message is read.", NtfyReader.setPrefix(" "))
        assertTrue(NtfyReader.setPrefix("IRA:").contains("IRA:"))
        assertEquals("Topic cleared. Nothing is read.", NtfyReader.setTopic(""))
        val d = NtfyReader.diagWords()
        assertTrue(d, d.startsWith("no topic set, token none, ntfy.sh"))
        SecurePrefs.put(NtfyReader.K_TOPIC, topic)
        assertTrue(NtfyReader.diagWords().startsWith("topic set (****)"))
        assertFalse(NtfyReader.diagWords().contains(topic))
        assertTrue(NtfyReader.releaseForMemory().not())
    }

    // ---- 26 Oct (Boss: "I think ntfy messages are getting read late, please check") -------------------------------------------

    /** A message from the stream is decided by its own pass within seconds (it waited for the watch's next minute before). */
    @Test fun aStreamedMessageIsDecidedWithinSecondsNotAtTheNextMinute() {
        NtfyArm.testNoKick = false
        server.enqueue(MockResponse().setBody(""))
        // Mid-minute, the stream brings an exit (nothing held: decided at once, no market data needed).
        server.enqueue(MockResponse().setBody(open() + line("x1", "exit nifty", at = nowSec - 1)).setBodyDelay(300, TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val t0 = System.nanoTime()
        NtfyReader.ensure()
        waitFor("read and decided", 8_000) { NtfyArm.view.value.alerts.any { it.id == "x1" && it.state != NtfyArm.PENDING } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("read and decided in $ms ms", ms < 5_000)
        val a = NtfyArm.view.value.alerts.single { it.id == "x1" }
        assertEquals(NtfyArm.NOT_TRADED, a.state)
        assertTrue(a.why, a.why.contains("nothing held on NIFTY"))
        assertTrue(NtfyArm.kickedPasses >= 1)
        // Its timing: sent 09:59:59, read and decided at 10:00:00 by the market's clock; the topic is never in the diary.
        assertEquals("read 1.0s · decided 1.0s", a.timingWords)
        assertTrue(Diag.lines().any { it.contains("message x1: not traded") && it.contains("after it was sent") })
        assertFalse(Diag.lines().any { it.contains(topic) })
        NtfyReader.stop("test")
    }

    /** The supervisor starts the reader at its hours' open (09:00), not at the words lane's 09:15. */
    @Test fun theSupervisorStartsTheReaderAtNine() {
        NtfyReader.testInHours = null
        val d = AutomationSupport.tradingDayAt(9, 0)
        Market.testClock = Clock.fixed(d.minusSeconds(2).toInstant(), IST)
        NtfyReader.superviseMs = 100; NtfyReader.idleLookMs = 200
        NtfyReader.supervise()
        waitFor("looked") { NtfyReader.looks >= 2 }
        Thread.sleep(400)
        assertFalse("08:59:58: never before its hours", NtfyReader.status.value.running)
        assertEquals(0, server.requestCount)
        assertTrue(NtfyReader.armedHint)
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        Market.testClock = Clock.fixed(d.toInstant(), IST)
        waitFor("started at 09:00", 3_000) { NtfyReader.status.value.running }
        waitFor("polled") { server.requestCount >= 1 }
        assertTrue(take().path!!.contains("poll=1"))
        NtfyReader.stopSupervisor(); NtfyReader.stop("test")
    }

    /** A reader stopped in its hours (memory pressure, a stop) runs again within seconds; outside its hours it stays stopped. */
    @Test fun theSupervisorRestartsAStoppedReaderWithinSecondsAndNeverOutsideHours() {
        NtfyReader.superviseMs = 100; NtfyReader.idleLookMs = 200; NtfyReader.hoursCheckMs = 50
        repeat(2) {
            server.enqueue(MockResponse().setBody(""))
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        }
        NtfyReader.supervise()
        waitFor("running") { NtfyReader.status.value.running && server.requestCount >= 2 }
        val polls = server.requestCount
        assertTrue("it was running and is closed", NtfyReader.releaseForMemory())
        assertTrue(Diag.lines().none { it.contains(topic) })
        val t0 = System.nanoTime()
        waitFor("running again", 3_000) { NtfyReader.status.value.running }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000)
        waitFor("caught up by its poll (a new connection)") { server.requestCount > polls }
        // The F&O close: stopped by its timer, and the supervisor never starts it again outside its hours.
        NtfyReader.testInHours = false
        waitFor("stopped") { !NtfyReader.status.value.running }
        val n = server.requestCount
        Thread.sleep(600)
        assertFalse(NtfyReader.status.value.running)
        assertEquals(n, server.requestCount)
        // 30 Oct: off is still armed - it runs in shadow (each buy a would-be trade), so its hours still start it.
        runBlocking { NtfyArm.setOn(false, log = false) }
        NtfyReader.ensure()
        assertTrue(NtfyReader.armedHint)
        NtfyReader.stopSupervisor()
    }
}
