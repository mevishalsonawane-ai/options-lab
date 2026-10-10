package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 2 of order speed: the cached-margin decision ([MarginCache]), the stream-or-REST price pick ([LiveQuote]), the
 * screens' coalescing ([Coalescer], at most one redraw every 250 ms, the latest always shown) and the warm-up schedule ([RouteWarm]).
 */
class HotPathTest {
    private val t0 = 1_791_694_800_000L

    @Test fun theMarginCheckUsesTheKeptSnapshotOnlyWhenFreshAndRoomy() {
        val snap = MarginCache.Snapshot(available = 100_000.0, atMs = t0)
        assertEquals(MarginCache.Use.CACHED, MarginCache.decide(snap, 50_000.0, t0 + 1_000))
        // Headroom of 20% of the need or more is enough; under it, read Zerodha first.
        assertEquals(MarginCache.Use.CACHED, MarginCache.decide(snap, 80_000.0, t0))
        assertEquals(MarginCache.Use.READ_NOW, MarginCache.decide(snap, 85_000.0, t0))
        assertEquals(MarginCache.Use.READ_NOW, MarginCache.decide(snap, 120_000.0, t0), "short on the snapshot")
        // Old (over 30 s), from the future (a clock change), missing, or an unknown need: read now.
        assertEquals(MarginCache.Use.CACHED, MarginCache.decide(snap, 10_000.0, t0 + MarginCache.MAX_AGE_MS))
        assertEquals(MarginCache.Use.READ_NOW, MarginCache.decide(snap, 10_000.0, t0 + MarginCache.MAX_AGE_MS + 1))
        assertEquals(MarginCache.Use.READ_NOW, MarginCache.decide(snap, 10_000.0, t0 - 1))
        assertEquals(MarginCache.Use.READ_NOW, MarginCache.decide(null, 10_000.0, t0))
        assertEquals(MarginCache.Use.READ_NOW, MarginCache.decide(snap, 0.0, t0))
        assertEquals(MarginCache.Use.READ_NOW, MarginCache.decide(snap, Double.NaN, t0))
        assertEquals(MarginCache.Use.READ_NOW, MarginCache.decide(snap.copy(available = Double.NaN), 10.0, t0))
        // The background refresh: none yet, or every 25 s (inside the 30 s the check accepts).
        assertTrue(MarginCache.due(null, t0))
        assertFalse(MarginCache.due(snap, t0 + 24_999))
        assertTrue(MarginCache.due(snap, t0 + MarginCache.REFRESH_MS))
        assertTrue(MarginCache.due(snap, t0 - 5))
        assertTrue(MarginCache.REFRESH_MS < MarginCache.MAX_AGE_MS)
    }

    @Test fun aFreshTickIsUsedAndAStaleOneFallsBackToRest() {
        assertEquals(LiveQuote.Source.STREAM, LiveQuote.pick(t0 - 2_000, t0))
        assertEquals(LiveQuote.Source.STREAM, LiveQuote.pick(t0, t0))
        assertEquals(LiveQuote.Source.REST, LiveQuote.pick(t0 - 2_001, t0))
        assertEquals(LiveQuote.Source.REST, LiveQuote.pick(null, t0))
        assertEquals(LiveQuote.Source.REST, LiveQuote.pick(t0 + 50, t0), "a tick from the future is not trusted")
        assertTrue(LiveQuote.allFresh(listOf(t0 - 10, t0 - 1_500), t0))
        assertFalse(LiveQuote.allFresh(listOf(t0 - 10, null), t0))
        assertFalse(LiveQuote.allFresh(listOf(t0 - 10, t0 - 3_000), t0))
        assertFalse(LiveQuote.allFresh(emptyList(), t0))
        assertEquals("live", LiveQuote.says(t0 - 900, t0))
        assertEquals("delayed 4s", LiveQuote.says(t0 - 4_200, t0))
        assertEquals("delayed", LiveQuote.says(null, t0))
        assertEquals("delayed", LiveQuote.says(t0 + 5_000, t0))
    }

    /** Ticks every [everyMs] for [forMs]: the emission times a [Coalescer] driven by them would make (the scheduled ones run on time). */
    private fun drive(everyMs: Long, forMs: Long): Pair<List<Long>, Long> {
        val c = Coalescer()
        val out = ArrayList<Long>()
        var scheduled: Long? = null
        var last = 0L
        var now = t0
        while (now <= t0 + forMs) {
            scheduled?.let { if (it <= now) { c.fired(it); out += it; scheduled = null } }
            val d = c.offer(now)
            if (d.now) out += now
            d.scheduleAt?.let { scheduled = it }
            last = now
            now += everyMs
        }
        scheduled?.let { c.fired(it); out += it }
        return out to last
    }

    @Test fun screensRedrawAtMostEvery250msAndAlwaysShowTheLatest() {
        val (emits, lastTick) = drive(everyMs = 10, forMs = 1_000)
        assertTrue(emits.size in 4..6, "$emits")
        assertTrue(emits.zipWithNext().all { (a, b) -> b - a >= Coalescer.GAP_MS }, "never closer than 250 ms: $emits")
        assertEquals(t0, emits.first(), "the first tick is shown at once")
        assertTrue(emits.last() >= lastTick && emits.last() - lastTick <= Coalescer.GAP_MS, "the last tick shown within 250 ms: $emits")
        // Sparse ticks (each over 250 ms apart) are each shown at once.
        val (sparse, _) = drive(everyMs = 400, forMs = 2_000)
        assertEquals((0..5).map { t0 + it * 400L }, sparse)
        // One held at a time, however many ticks come meanwhile.
        val c = Coalescer()
        assertTrue(c.offer(t0).now)
        assertEquals(t0 + 250, c.offer(t0 + 10).scheduleAt)
        assertTrue(c.pending)
        assertNull(c.offer(t0 + 20).scheduleAt)
        assertFalse(c.offer(t0 + 400).now, "the held one carries it")
        c.fired(t0 + 250)
        assertFalse(c.pending)
        assertTrue(c.offer(t0 + 600).now)
    }

    @Test fun theRouteIsWarmedEvery20sWhileLiveAndBeforeEachBarClose() {
        assertNull(RouteWarm.next(t0, null, liveActive = false, armed = false))
        assertEquals(t0, RouteWarm.next(t0, null, liveActive = true, armed = false))
        assertEquals(t0 + RouteWarm.LIVE_GAP_MS, RouteWarm.next(t0 + 1_000, t0, liveActive = true, armed = false))
        assertEquals(t0 + 50_000, RouteWarm.next(t0 + 50_000, t0, liveActive = true, armed = false), "overdue: now")
        // Armed: never later than 5 s before the next 5-minute close.
        assertEquals(FastLane.warmAt(t0), RouteWarm.next(t0, null, liveActive = false, armed = true))
        assertEquals(t0 + 275_000, RouteWarm.next(t0 + 270_000, t0 + 255_000, liveActive = true, armed = true))
        assertEquals(t0 + 295_000, RouteWarm.next(t0 + 285_000, t0 + 280_000, liveActive = true, armed = true))
    }
}
