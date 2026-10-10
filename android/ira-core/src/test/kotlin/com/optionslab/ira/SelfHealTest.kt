package com.optionslab.ira

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Self-healing ([SelfHeal]): circuit breakers, fallbacks, incidents told once, workers restarted. */
class SelfHealTest {
    private var now = 1_000_000L
    private val m = SelfHeal.Monitor { now }

    @Test fun theBreakerShutsAfterRepeatedFailuresTriesOnceAndBacksOffLonger() {
        val b = SelfHeal.Breaker(failures = 3, openMs = 1_000, capMs = 8_000)
        repeat(2) { b.failure(now) }
        assertTrue(b.allow(now)); assertEquals(SelfHeal.Breaker.State.CLOSED, b.state)
        b.failure(now)
        assertEquals(SelfHeal.Breaker.State.OPEN, b.state)
        assertFalse(b.allow(now + 999))
        // One trial after the pause, and only one.
        assertTrue(b.allow(now + 1_000)); assertEquals(SelfHeal.Breaker.State.HALF_OPEN, b.state)
        assertFalse(b.allow(now + 1_001))
        // The trial fails: shut again for twice as long.
        b.failure(now + 1_001)
        assertEquals(now + 1_001 + 2_000, b.openUntil)
        assertTrue(b.allow(now + 3_001)); b.failure(now + 3_001)
        assertEquals(now + 3_001 + 4_000, b.openUntil)
        // A success closes it and the pause starts again at the shortest.
        assertTrue(b.allow(now + 7_001)); b.success()
        assertEquals(SelfHeal.Breaker.State.CLOSED, b.state)
        repeat(3) { b.failure(now + 8_000) }
        assertEquals(now + 9_000, b.openUntil)
    }

    @Test fun theStreamGoingStaleSwitchesPricesToRestAndBackOnceItRecovers() {
        m.stream(lastTickMs = now, expected = true)
        assertEquals(SelfHeal.Fallbacks(), m.fallbacks())
        now += SelfHeal.STREAM_STALE_MS + 1
        assertTrue(m.fallbacks().restQuotes)
        val started = m.evaluate()
        assertEquals(1, started.size)
        assertTrue(started[0].started)
        assertTrue(started[0].words.startsWith("The live price stream has gone quiet"), started[0].words)
        assertTrue("Zerodha's quotes" in started[0].words && "exits still go" in started[0].words)
        // Told once: the next looks say nothing while it lasts.
        now += 5_000; assertTrue(m.evaluate().isEmpty())
        // Back, but only "over" after 30 s of health (a flap is the same incident).
        m.stream(now, true)
        assertFalse(m.fallbacks().restQuotes)
        assertTrue(m.evaluate().isEmpty())
        now += SelfHeal.RECOVER_MS
        m.stream(now, true)
        val over = m.evaluate()
        assertEquals(1, over.size); assertFalse(over[0].started)
        assertTrue(over[0].words.startsWith("The live price stream is back to normal"), over[0].words)
        assertEquals(1, m.past().size); assertTrue(m.openIncidents().isEmpty())
        // Out of market hours a silent stream is no incident.
        m.stream(null, expected = false)
        assertTrue(m.evaluate().isEmpty())
    }

    @Test fun slowOrFailingZerodhaUsesTheStreamAndTheCachedBookAndBothDownIsUnreliableData() {
        m.stream(now, true)
        repeat(10) { m.rest(ok = true, latencyMs = 2_500) }
        assertEquals(SelfHeal.Level.SLOW, m.levels().getValue(SelfHeal.Dep.KITE_REST).first)
        assertTrue(m.fallbacks().streamAndCachedBook)
        assertFalse(m.fallbacks().dataUnreliable)
        // 429s: too many requests is SLOW.
        val m2 = SelfHeal.Monitor { now }
        repeat(3) { m2.rest(ok = true, latencyMs = 100, rateLimited = true) }
        assertEquals(SelfHeal.Level.SLOW, m2.levels().getValue(SelfHeal.Dep.KITE_REST).first)
        // Failing: DOWN, and the breaker refuses the next request (the fallback is taken instead).
        repeat(10) { m.rest(ok = false, latencyMs = 100) }
        assertEquals(SelfHeal.Level.DOWN, m.levels().getValue(SelfHeal.Dep.KITE_REST).first)
        assertFalse(m.allow(SelfHeal.Dep.KITE_REST))
        // And the stream stale too: no reliable price anywhere.
        now += SelfHeal.STREAM_STALE_MS + 1
        val f = m.fallbacks()
        assertTrue(f.dataUnreliable && f.restQuotes)
        assertTrue(f.words().any { "paused everywhere" in it })
    }

    @Test fun theRelayDownBlocksNewLiveEntriesTheCandleFeedFallsBackAndThePhoneIsWatched() {
        m.relay(false); assertFalse(m.fallbacks().blockLiveEntries)
        m.relay(false); assertTrue(m.fallbacks().blockLiveEntries)
        m.relay(true); assertFalse(m.fallbacks().blockLiveEntries)
        m.relay(false, enabled = false); m.relay(false, enabled = false)
        assertFalse(m.fallbacks().blockLiveEntries, "no relay in use: never blocks")
        repeat(3) { m.candles(false) }
        assertTrue(m.fallbacks().localCandles)
        m.candles(true); assertFalse(m.fallbacks().localCandles)
        m.keystore(2_500)
        assertEquals(SelfHeal.Level.SLOW, m.levels().getValue(SelfHeal.Dep.KEYSTORE).first)
        m.phone(12, charging = false, thermalStatus = 0)
        assertTrue(m.levels().getValue(SelfHeal.Dep.PHONE).second.contains("12%"))
        m.phone(12, charging = true, thermalStatus = 3)
        assertTrue(m.levels().getValue(SelfHeal.Dep.PHONE).second.contains("hot"))
        m.phone(80, charging = false, thermalStatus = 1)
        assertEquals(SelfHeal.Level.OK, m.levels().getValue(SelfHeal.Dep.PHONE).first)
        assertTrue(SelfHeal.line(SelfHeal.Incident(SelfHeal.Dep.RELAY, SelfHeal.Level.DOWN, 0, "x"), { "10:00:00" }).contains("(still open)"))
    }

    @Test fun aWorkerFailingRepeatedlyIsRestartedFreshAndToldAtMostItsLimitAnHour() = runBlocking {
        assertTrue(SelfHeal.shouldRestart(3, 0)); assertFalse(SelfHeal.shouldRestart(2, 0)); assertFalse(SelfHeal.shouldRestart(3, 6))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val restarts = CopyOnWriteArrayList<String>()
        val sup = Supervisor(scope, onRestart = { n, k -> restarts += "$n:$k" })
        try {
            sup.start(Supervisor.Spec("flaky", "x", 1_000, cancelOnTimeout = true, nextDelayMs = { 0L }, backoffBaseMs = 1, backoffCapMs = 4,
                restart = SelfHeal.Restart(after = 3, maxPerHour = 2))) { throw IOException("down") }
            withTimeout(5_000) { while (sup.status("flaky")!!.errors < 12) delay(2) }
            assertEquals(2, sup.status("flaky")!!.restarts, "at most its limit an hour")
            assertEquals(listOf("flaky:3", "flaky:3"), restarts.toList())
            assertTrue(Supervisor.words(sup.status("flaky")!!) { "" }.contains("restarted 2 times"))
        } finally { scope.cancel() }
    }
}
