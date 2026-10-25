package com.optionslab.ira

import com.optionslab.ira.StreamHealth.Cause
import com.optionslab.ira.StreamHealth.Drop
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamHealthTest {
    private val now = LocalDateTime.of(2026, 10, 5, 10, 0)
    private val token = "abcd1234efgh5678ijkl9012mnop3456"
    private val key = "kitekey123xyz"

    @Test fun causes() {
        assertEquals(Cause.PING, StreamHealth.cause(Drop(error = "SocketTimeoutException", message = "sent ping but didn't receive pong within 20000ms (after 12 successful ping/pongs)")))
        assertEquals(Cause.TOKEN, StreamHealth.cause(Drop(error = "ProtocolException", message = "Expected HTTP 101 response but was '403 Forbidden'")))
        assertEquals(Cause.TOKEN, StreamHealth.cause(Drop(error = "ProtocolException", message = "x", http = 403)))
        assertEquals(Cause.TOO_MANY, StreamHealth.cause(Drop(error = "ProtocolException", message = "Expected HTTP 101 response but was '429 Too Many Requests'")))
        assertEquals(Cause.SERVER, StreamHealth.cause(Drop(http = 502)))
        assertEquals(Cause.CLOSED, StreamHealth.cause(Drop(code = 1001, reason = "going away")))
        assertEquals(Cause.SERVER, StreamHealth.cause(Drop(code = 1011, reason = "")))
        assertEquals(Cause.DNS, StreamHealth.cause(Drop(error = "UnknownHostException", message = "Unable to resolve host \"ws.kite.trade\"")))
        assertEquals(Cause.NETWORK_LOST, StreamHealth.cause(Drop(error = "SocketException", message = "Software caused connection abort")))
        assertEquals(Cause.RESET, StreamHealth.cause(Drop(error = "SocketException", message = "Connection reset")))
        assertEquals(Cause.TIMEOUT, StreamHealth.cause(Drop(error = "SocketTimeoutException", message = "connect timed out")))
        assertEquals(Cause.CLOSED, StreamHealth.cause(Drop(error = "EOFException", message = null)))
        assertEquals(Cause.TLS, StreamHealth.cause(Drop(error = "SSLHandshakeException", message = "Chain validation failed")))
        assertEquals(Cause.NETWORK_CHANGED, StreamHealth.cause(Drop(error = "SocketException", message = "Software caused connection abort", networkChanged = true)))
        assertEquals(Cause.OTHER, StreamHealth.cause(Drop(error = "IllegalStateException", message = "odd")))
        // Forced by the watchdog, and a dead connection right after the app was frozen.
        assertEquals(Cause.SILENT, StreamHealth.cause(Drop(forced = Cause.SILENT, silentSec = 12)))
        assertEquals(Cause.PAUSED, StreamHealth.cause(Drop(error = "SocketTimeoutException", message = "sent ping but didn't receive pong", pausedSec = 240)))
        assertEquals(Cause.PAUSED, StreamHealth.cause(Drop(forced = Cause.SILENT, silentSec = 250, pausedSec = 240)))
        // A refused session stays a refused session, paused or not.
        assertEquals(Cause.TOKEN, StreamHealth.cause(Drop(http = 403, pausedSec = 240)))
    }

    @Test fun neverASecret() {
        val url = "wss://ws.kite.trade/?api_key=$key&access_token=$token"
        val d = Drop(error = "IOException", message = "failed to connect to $url: access_token=$token api_key=$key", http = null, upMs = 5_000)
        val line = StreamHealth.diary(d, listOf(token, key))
        assertFalse(token in line, line)
        assertFalse(key in line, line)
        assertFalse("ws.kite.trade/?" in line, line)
        assertTrue("dropped:" in line, line)
        // Even without being told the secrets, the URL and the keyed values go.
        val bare = StreamHealth.scrub("Expected 101 from wss://ws.kite.trade/?api_key=$key&access_token=$token; access_token=$token")
        assertFalse(token in bare, bare); assertFalse(key in bare, bare)
        assertEquals("", StreamHealth.scrub(null))
        assertTrue(StreamHealth.scrub("x".repeat(500)).length <= 160)
    }

    @Test fun diaryLine() {
        val line = StreamHealth.diary(Drop(code = 1001, reason = "going away", upMs = 723_000, ticks = 1234, tokens = 25, foreground = false, watch = false))
        assertEquals("dropped: Zerodha closed the connection · up 12m 3s · ticks 1234 · tokens 25 · close 1001 going away · background · watch not running", line)
        val silent = StreamHealth.diary(Drop(forced = Cause.SILENT, silentSec = 11, upMs = 3_700_000, foreground = true, networkChanged = false, watch = true))
        assertEquals("dropped: no data from Zerodha for 11 s · up 1h 1m · ticks 0 · tokens 0 · foreground · watch running", silent)
        val paused = StreamHealth.diary(Drop(error = "SocketTimeoutException", message = "sent ping but didn't receive pong within 20000ms", upMs = 60_000, pausedSec = 240))
        assertTrue(paused.startsWith("dropped: Android paused the app for 240 s · up 1m 0s"), paused)
        val failed = StreamHealth.diary(Drop(error = "UnknownHostException", message = "Unable to resolve host", tries = 3))
        assertTrue(failed.startsWith("connect failed: no internet · try 3"), failed)
        assertTrue(" · http 403" in StreamHealth.diary(Drop(error = "ProtocolException", message = "Expected HTTP 101 response but was '403 Forbidden'")))
    }

    @Test fun watchdog() {
        assertFalse(StreamHealth.stalled(live = true, marketOpen = true, lastFrameAt = 1_000, now = 10_999))
        assertTrue(StreamHealth.stalled(live = true, marketOpen = true, lastFrameAt = 1_000, now = 11_000))
        assertFalse(StreamHealth.stalled(live = true, marketOpen = false, lastFrameAt = 1_000, now = 60_000))
        assertFalse(StreamHealth.stalled(live = false, marketOpen = true, lastFrameAt = 1_000, now = 60_000))
        assertFalse(StreamHealth.stalled(live = true, marketOpen = true, lastFrameAt = 0, now = 60_000))
        // 25 Oct (app audit; 23 Oct's 19 quiet minutes): heartbeats but no price for 20 s in NSE's session drops the socket too.
        assertFalse(StreamHealth.pricesStalled(live = true, nseSession = true, tokens = 25, lastTickAt = 1_000, openedAt = 500, now = 20_999))
        assertTrue(StreamHealth.pricesStalled(live = true, nseSession = true, tokens = 25, lastTickAt = 1_000, openedAt = 500, now = 21_000))
        assertFalse(StreamHealth.pricesStalled(live = true, nseSession = false, tokens = 25, lastTickAt = 1_000, openedAt = 500, now = 60_000), "outside NSE's session")
        assertFalse(StreamHealth.pricesStalled(live = true, nseSession = true, tokens = 0, lastTickAt = 1_000, openedAt = 500, now = 60_000), "nothing subscribed")
        assertFalse(StreamHealth.pricesStalled(live = false, nseSession = true, tokens = 25, lastTickAt = 1_000, openedAt = 500, now = 60_000), "not opened")
        assertFalse(StreamHealth.pricesStalled(live = true, nseSession = true, tokens = 25, lastTickAt = 0, openedAt = 0, now = 60_000))
        // 25 Oct (merge review): the count of such drops starts again only when prices kept coming past the first 20 s.
        assertFalse(StreamHealth.pricesResumed(lastTickAt = 50_300, openedAt = 50_000), "one quote on subscribe")
        assertFalse(StreamHealth.pricesResumed(lastTickAt = 69_999, openedAt = 50_000))
        assertTrue(StreamHealth.pricesResumed(lastTickAt = 70_000, openedAt = 50_000))
        assertFalse(StreamHealth.pricesResumed(lastTickAt = 0, openedAt = 50_000), "no price at all")
        // No price since it opened: counted from the open, so a fresh socket gets its 20 s.
        assertFalse(StreamHealth.pricesStalled(live = true, nseSession = true, tokens = 25, lastTickAt = 0, openedAt = 50_000, now = 69_999))
        assertTrue(StreamHealth.pricesStalled(live = true, nseSession = true, tokens = 25, lastTickAt = 0, openedAt = 50_000, now = 70_000))
        assertFalse(StreamHealth.nseTicking(9 * 60 + 15)); assertTrue(StreamHealth.nseTicking(9 * 60 + 16))
        assertTrue(StreamHealth.nseTicking(15 * 60 + 29)); assertFalse(StreamHealth.nseTicking(15 * 60 + 30))
        assertEquals("dropped: no data from Zerodha for 20 s · up 5m 0s · ticks 9 · tokens 25 · heartbeats only, no prices",
            StreamHealth.diary(Drop(forced = Cause.SILENT, silentSec = 20, message = "heartbeats only, no prices", upMs = 300_000, ticks = 9, tokens = 25)))
        assertNull(StreamHealth.paused(1_000, 3_100))
        assertEquals(240L, StreamHealth.paused(1_000, 241_000))
        assertNull(StreamHealth.paused(0, 241_000))
    }

    @Test fun waits() {
        assertEquals(500L, StreamHealth.wait(0, null, Cause.DNS))
        assertEquals(2_000L, StreamHealth.wait(1_000, null, Cause.DNS))
        // 12 Oct: the phone offline (no DNS, network lost or changed) waits at most 4 s between tries, not 30.
        assertEquals(4_000L, StreamHealth.wait(20_000, null, Cause.DNS))
        assertEquals(4_000L, StreamHealth.wait(4_000, null, Cause.NETWORK_CHANGED))
        assertEquals(4_000L, StreamHealth.wait(16_000, 2_000, Cause.NETWORK_LOST))
        assertEquals(30_000L, StreamHealth.wait(20_000, null, Cause.TIMEOUT))
        // Up a minute or more: at once again. Dropped within seconds of opening: the wait keeps growing (was reset to 0.5 s).
        assertEquals(500L, StreamHealth.wait(16_000, 60_000, Cause.SILENT))
        assertEquals(8_000L, StreamHealth.wait(4_000, 3_000, Cause.CLOSED))
        assertEquals(30_000L, StreamHealth.wait(1_000, 600_000, Cause.TOO_MANY))
        assertEquals(30_000L, StreamHealth.wait(0, null, Cause.TOKEN))
    }

    @Test fun capAndChunks() {
        val want = (1L..3500L).toSet()
        val first = listOf(256265L, 3400L, 3499L)
        val capped = StreamHealth.cap(want + 256265L, first)
        assertEquals(3000, capped.size)
        assertTrue(256265L in capped && 3400L in capped && 3499L in capped)
        assertEquals(want.take(10).toSet(), StreamHealth.cap(want.take(10).toSet(), first))
        assertEquals(listOf(500, 500, 200), StreamHealth.chunks((1L..1200L).toList()).map { it.size })
    }

    private val diary = listOf(
        "10-04 15:10:00 [stream] dropped: Zerodha closed the connection · up 5m 0s",
        "10-05 09:20:01 [stream] dropped: the connection stopped answering · up 4m 0s · ticks 900",
        "10-05 09:31:10 [error] Live price stream dropped (the connection stopped answering); reconnecting",
        "10-05 09:44:12 [stream] connect failed: no internet · try 2",
        "10-05 09:52:40 [stream] dropped: Android paused the app for 240 s · up 20m 0s",
        "10-05 09:58:45 [stream] dropped: Android paused the app for 95 s · up 5m 0s · background",
    )

    @Test fun tally() {
        val d = StreamHealth.day(diary, now)
        assertEquals(3, d.drops)
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 58, 45), d.lastAt)
        assertEquals("Android paused the app for 95 s", d.lastWhy)
        assertEquals(2, d.whys["Android paused the app"])
        assertEquals("Live stream: LIVE · drops today 3 · last drop 09:58:45 (Android paused the app for 95 s)", StreamHealth.statusLine("LIVE", diary, now))
        assertEquals("Live stream: OFF · drops today 0", StreamHealth.statusLine("OFF", emptyList(), now))
    }

    @Test fun asks() {
        for (q in listOf("why is live data dropping", "why is the live stream dropping again and again", "stream kyun band ho raha",
            "live data baar baar band ho raha hai", "why does the price feed keep disconnecting", "is the live stream working",
            "why is live streaming dropping again and again", "why do live prices keep stopping", "live stream status"))
            assertTrue(StreamHealth.asked(q), q)
        for (q in listOf("why is my relay failing", "is the relay down", "what is nifty at", "buy 1 lot nifty", "restart the live stream",
            "why is my voice dropping", "show me the chart", "stream the news"))
            assertFalse(StreamHealth.asked(q), q)
    }

    @Test fun answers() {
        val a = StreamHealth.answer(diary, now, "LIVE", watchRunning = false)
        assertTrue(a.startsWith("Boss, the live price stream has dropped 3 times today, the last at 9:58 (Android paused the app for 95 s); it's live now."), a)
        assertTrue("Android froze the app" in a, a)
        assertTrue("order watch isn't running" in a, a)
        assertFalse("ws.kite.trade" in a)
        val none = StreamHealth.answer(emptyList(), now, "OFF", watchRunning = null)
        assertEquals("Boss, the live price stream hasn't dropped today; it's off now. It runs only with a Zerodha login during market hours.", none)
    }
}
