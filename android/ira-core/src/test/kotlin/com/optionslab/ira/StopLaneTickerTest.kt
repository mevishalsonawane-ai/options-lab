package com.optionslab.ira

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 22 Oct: the stop lane's own clock ([StopCadence.Ticker]). The 21 Oct diagnostics logged stop looks more than 16 s apart while
 * a position was held (the look waited behind the full pass and the bar-close entry check). These run on a test clock.
 */
class StopLaneTickerTest {
    /** A test clock: sleeping moves it on, and so does a look's own time. */
    private class Clock(var t: Long = 1_000_000L)

    private fun gaps(starts: List<Long>) = starts.zipWithNext { a, b -> b - a }

    @Test fun whileHeldALookStartsEvery15sFromTheLastStart() = runBlocking {
        val c = Clock()
        val starts = ArrayList<Long>()
        // Each look takes 4 s: the next still starts 15 s after the last one started (never 19 s).
        StopCadence.Ticker({ c.t }, { c.t += it }).run(stop = { starts.size >= 6 }, held = { true }, look = { s -> starts += s; c.t += 4_000L })
        assertEquals(1_000_000L, starts.first(), "the first held look is at once")
        assertEquals(List(5) { 15_000L }, gaps(starts))
    }

    @Test fun aLookThatRunsPastTheWindowIsFollowedOneSecondAfterItEnds() = runBlocking {
        val c = Clock()
        val starts = ArrayList<Long>()
        val took = ArrayDeque(listOf(20_000L, 2_000L, 2_000L))
        StopCadence.Ticker({ c.t }, { c.t += it }).run(stop = { starts.size >= 3 }, held = { true },
            look = { s -> starts += s; c.t += took.removeFirst() })
        // A 20 s look: the next 1 s after it ends (21 s), not 5 s after (25 s) and never back to back; then 15 s again.
        assertEquals(listOf(21_000L, 15_000L), gaps(starts))
    }

    @Test fun nothingHeldNoLookAndTheDriftCountStartsAgainOnce() = runBlocking {
        val c = Clock()
        val starts = ArrayList<Long>()
        var idles = 0
        var polls = 0
        StopCadence.Ticker({ c.t }, { c.t += it; polls++ }).run(stop = { polls >= 100 }, held = { false }, look = { s -> starts += s },
            idle = { idles++ })
        assertTrue(starts.isEmpty(), "nothing held: no look at all")
        assertEquals(0, idles, "never held: nothing to start again")
        // Held for 40 s, then nothing held: idle runs once at the change, not at every poll.
        var held = true
        val from = c.t
        polls = 0
        StopCadence.Ticker({ c.t }, { c.t += it; polls++; if (c.t - from >= 40_000L) held = false }).run(stop = { polls >= 100 },
            held = { held }, look = { s -> starts += s }, idle = { idles++ })
        assertEquals(1, idles)
        assertEquals(listOf(from, from + 15_000L, from + 30_000L), starts, "looks at 0, 15 and 30 s of the 40 s held")
    }

    @Test fun aNewPositionGetsItsFirstLookWithinASecond() = runBlocking {
        val c = Clock()
        val starts = ArrayList<Long>()
        val openedAt = c.t + 7_500L
        StopCadence.Ticker({ c.t }, { c.t += it }).run(stop = { starts.isNotEmpty() }, held = { c.t >= openedAt }, look = { s -> starts += s })
        assertTrue(starts.single() - openedAt <= StopCadence.LANE_POLL_MS, "${starts.single() - openedAt} ms")
    }

    @Test fun aClockMovedBackIsNeverWaitedOut() = runBlocking {
        val c = Clock()
        val starts = ArrayList<Long>()
        StopCadence.Ticker({ c.t }, { c.t += it }).run(stop = { starts.size >= 2 }, held = { true },
            look = { s -> starts += s; if (starts.size == 1) c.t -= 3_600_000L })
        // The clock went back an hour during the first look: the next look comes within a window of the new time, not an hour on.
        assertTrue(starts[1] - (starts[0] - 3_600_000L) <= StopCadence.WINDOW_MS, "$starts")
    }

    @Test fun aFailedLookIsToldAndTheLaneGoesOn() = runBlocking {
        val c = Clock()
        var looks = 0
        val failed = ArrayList<Throwable>()
        StopCadence.Ticker({ c.t }, { c.t += it }).run(stop = { looks >= 3 }, held = { true },
            look = { looks++; error("broker down") }, failed = { failed += it })
        assertEquals(3, failed.size)
        // A cancellation escaped from inside a look (a timeout), the lane itself still running: a failure, and the lane goes on.
        looks = 0
        StopCadence.Ticker({ c.t }, { c.t += it }).run(stop = { looks >= 2 }, held = { true },
            look = { looks++; throw kotlinx.coroutines.CancellationException("a step's own timeout") }, failed = { failed += it })
        assertEquals(2, looks)
        assertEquals(5, failed.size)
        // The lane's own cancellation ends it.
        val lane = launch {
            StopCadence.Ticker({ c.t }, { kotlinx.coroutines.delay(it) }).run(stop = { false }, held = { true }, look = { })
        }
        kotlinx.coroutines.delay(50)
        lane.cancelAndJoin()
        assertTrue(lane.isCancelled)
        // A "held" answer that throws counts as held (open positions come first).
        var n = 0
        StopCadence.Ticker({ c.t }, { c.t += it }).run(stop = { n >= 1 }, held = { error("unreadable") }, look = { n++ })
        assertEquals(1, n)
    }

    @Test fun anotherLookOnTimeMovesTheWindowAndALateOneIsCoveredAt15s() = runBlocking {
        // MCX's own pass looks at the stops itself: the lane looks only when that pass is late, never twice in one window.
        val c = Clock()
        val t0 = c.t
        // The pass started at 0, 15 and 30 s (on time), then took 40 s (the next at 70 s).
        val passStarts = listOf(t0, t0 + 15_000L, t0 + 30_000L, t0 + 70_000L)
        val outside = { passStarts.lastOrNull { it <= c.t } ?: 0L }
        val starts = ArrayList<Long>()
        StopCadence.Ticker({ c.t }, { c.t += it }).run(stop = { c.t >= t0 + 90_000L }, held = { true }, look = { s -> starts += s },
            outside = outside)
        // A second's grace for the pass to start on its own (it comes back 15 s after its start): 46 s, then 61 s; the pass at
        // 70 s moves the window to 86 s.
        assertEquals(listOf(t0 + 46_000L, t0 + 61_000L, t0 + 86_000L), starts, "only while the pass was late")
        val all = (starts + passStarts).sorted()
        all.zipWithNext { a, b -> b - a }.forEach { assertTrue(it <= StopCadence.DRIFT_MS, "a gap of $it ms in $all") }
    }

    @Test fun theLanesRestIsShorterThanTheOldOne() {
        // The default window keeps its reviewed 5 s rest; the lane passes its own 1 s.
        assertEquals(125_000L, StopCadence.windowEnd(100_000L, 120_000L))
        assertEquals(121_000L, StopCadence.windowEnd(100_000L, 120_000L, minGapMs = StopCadence.LANE_REST_MS))
        assertEquals(115_000L, StopCadence.windowEnd(100_000L, 104_000L, minGapMs = StopCadence.LANE_REST_MS))
    }
}
