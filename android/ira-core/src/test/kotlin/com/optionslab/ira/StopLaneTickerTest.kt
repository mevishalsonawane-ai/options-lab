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

    // ---- 25 Oct (Boss's decision on the app audit, H4): faster stop looks while the held prices are quiet ----

    @Test fun whileTheStreamIsQuietALookStartsEvery5sAndNeverMoreThanAbout5sApart() = runBlocking {
        val c = Clock()
        val starts = ArrayList<Pair<Long, Boolean>>()
        val gaps = StopCadence.QuietGaps()
        val day = java.time.LocalDate.of(2026, 10, 26)
        // Each look takes 2 s (a candle read): the next still starts 5 s after the last one started.
        StopCadence.Ticker({ c.t }, { c.t += it }, quiet = { true }, started = { t, q -> starts += t to q; gaps.look(t, q, day) })
            .run(stop = { starts.size >= 8 }, held = { true }, look = { c.t += 2_000L })
        assertTrue(starts.all { it.second }, "every look on the quiet clock")
        val g = gaps(starts.map { it.first })
        assertEquals(List(7) { 5_000L }, g)
        assertTrue(g.max() <= StopCadence.QUIET_DRIFT_MS)
        assertEquals(Triple(7, 0, 5_000L), gaps.today(day))
        assertTrue(gaps.line(day).startsWith("Quiet-stream stop checks: all 7 gaps while holding were 6 s or less today"), gaps.line(day))
    }

    @Test fun aStreamGoingQuietMidWaitBringsTheNextLookForwardAndFreshPricesGoBackTo15s() = runBlocking {
        val c = Clock()
        val starts = ArrayList<Long>()
        val from = c.t
        // Fresh for the first 20 s, quiet from 20 s to 40 s, fresh again after.
        val quiet = { c.t - from in 20_000L until 40_000L }
        StopCadence.Ticker({ c.t }, { c.t += it }, quiet = quiet).run(stop = { c.t - from > 80_000L }, held = { true }, look = { s -> starts += s - from })
        // 0, 15 (fresh), then the stream goes quiet at 20: the look due at 30 comes at 20 (5 s after 15), then every 5 s while quiet.
        assertEquals(listOf(0L, 15_000L, 20_000L, 25_000L, 30_000L, 35_000L), starts.take(6))
        // The look already due at 40 (set while quiet) still runs; fresh from then, back to 15 s from the last start (55, 70).
        assertEquals(listOf(40_000L, 55_000L, 70_000L), starts.drop(6).take(3))
    }

    @Test fun aSlowQuietLookIsFollowedOneSecondAfterItEndsAndTheGapIsCounted() = runBlocking {
        val c = Clock()
        val starts = ArrayList<Long>()
        val gaps = StopCadence.QuietGaps()
        val day = java.time.LocalDate.of(2026, 10, 26)
        val took = ArrayDeque(listOf(1_000L, 9_000L, 1_000L, 1_000L))
        StopCadence.Ticker({ c.t }, { c.t += it }, quiet = { true }, started = { t, q -> gaps.look(t, q, day) })
            .run(stop = { starts.size >= 4 }, held = { true }, look = { s -> starts += s; c.t += took.removeFirst() })
        // 0 (1 s), 5 (9 s: ends at 14), 15 (1 s after it ended), 20.
        assertEquals(listOf(5_000L, 10_000L, 5_000L), gaps(starts))
        val (n, over, worst) = gaps.today(day)
        assertEquals(3, n); assertEquals(1, over); assertEquals(10_000L, worst)
        assertTrue(gaps.line(day).startsWith("Quiet-stream stop checks: 1 of 3 gaps while holding were over 6 s today (worst 10 s)"), gaps.line(day))
    }

    @Test fun aQuietReadThatFailsCountsAsQuietAndTheGapsResetWhenNothingIsHeldOrOnANewDay() = runBlocking {
        val c = Clock()
        val starts = ArrayList<Long>()
        StopCadence.Ticker({ c.t }, { c.t += it }, quiet = { error("unreadable") }).run(stop = { starts.size >= 3 }, held = { true }, look = { s -> starts += s })
        assertEquals(listOf(5_000L, 5_000L), gaps(starts), "unknown: more looks, never fewer")
        val g = StopCadence.QuietGaps()
        val day = java.time.LocalDate.of(2026, 10, 26)
        assertEquals(null, g.look(0L, true, day))
        g.idle()
        assertEquals(null, g.look(60_000L, true, day), "after nothing was held, the first look is not a gap")
        assertEquals(null, g.look(64_000L, false, day), "a look on the plain clock is not a quiet gap")
        assertEquals(Triple(0, 0, 0L), g.today(day))
        assertEquals(Triple(0, 0, 0L), g.today(day.plusDays(1)))
        assertTrue(g.line(day).startsWith("Quiet-stream stop checks: none measured today"))
        assertEquals(null, g.look(1_000L, true, day.plusDays(1)), "a new day starts again")
    }
}
