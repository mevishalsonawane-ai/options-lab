package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Smart scheduling ([WorkerWake]): workers woken by what they wait for, never skipped while holding, counted. */
class WorkerWakeTest {
    private var now = 1_760_000_000_000L - Math.floorMod(1_760_000_000_000L, 300_000L) + 60_000L   // 1 min past a 5-min boundary
    private val s = WorkerWake.Scheduler { now }
    private val five = WorkerWake.Spec("ORB arms", setOf(WorkerWake.Cause.CANDLE_5M, WorkerWake.Cause.POSITION_CHANGE, WorkerWake.Cause.ORDER_UPDATE,
        WorkerWake.Cause.SIGNAL_WINDOW, WorkerWake.Cause.NEAR_LEVEL), maxSleepMs = 6 * 60_000L, minIntervalMs = 20_000L, instruments = setOf("BANKNIFTY"))

    @Test fun aFiveMinuteWorkerSkipsTheMinutesBetweenItsCandlesAndWakesAtTheClose() {
        s.register(five)
        assertEquals(WorkerWake.Cause.FIRST, s.decide("ORB arms").cause)
        s.ran("ORB arms")
        // The next three minutes: no 5-minute candle closed, nothing posted: skipped.
        repeat(3) { now += 60_000L; assertFalse(s.decide("ORB arms").run) }
        // The boundary (plus the settle): woken by the candle.
        now += 60_000L + WorkerWake.SETTLE_MS
        val d = s.decide("ORB arms")
        assertTrue(d.run); assertEquals(WorkerWake.Cause.CANDLE_5M, d.cause)
        s.ran("ORB arms")
        assertFalse(s.decide("ORB arms").run)
        val c = s.counts().getValue("ORB arms")
        assertEquals(2, c.wakeTotal); assertEquals(4, c.skips)
        assertTrue(WorkerWake.words("ORB arms", c).startsWith("ORB arms: 2 wakes ("), WorkerWake.words("ORB arms", c))
    }

    @Test fun holdingAlwaysRunsAndOutOfHoursSleeps() {
        s.register(five); s.ran("ORB arms")
        now += 1_000
        assertEquals(WorkerWake.Cause.HOLDING, s.decide("ORB arms", WorkerWake.Pace(holding = true)).cause)
        // Holding wins even out of hours (an exit is never skipped).
        assertTrue(s.decide("ORB arms", WorkerWake.Pace(inHours = false, holding = true)).run)
        val d = s.decide("ORB arms", WorkerWake.Pace(inHours = false))
        assertFalse(d.run); assertTrue(d.asleep)
        assertEquals(1, s.counts().getValue("ORB arms").asleep)
        // An unknown worker is never skipped by mistake.
        assertTrue(s.decide("nobody").run)
    }

    @Test fun postedEventsWakeOnlyTheWorkersWaitingOnThemAfterTheirMinimumGap() {
        s.register(five)
        s.register(WorkerWake.Spec("MCX", setOf(WorkerWake.Cause.ORDER_UPDATE), maxSleepMs = 600_000L, instruments = setOf("CRUDEOIL")))
        s.ran("ORB arms"); s.ran("MCX")
        now += 5_000
        s.post(WorkerWake.Cause.ORDER_UPDATE, "BANKNIFTY")
        assertTrue(s.anyPending())
        // Within ORB's 20 s minimum gap: not yet.
        assertFalse(s.decide("ORB arms").run)
        assertFalse(s.decide("MCX").run, "another instrument's update does not wake MCX")
        now += 16_000
        assertEquals(WorkerWake.Cause.ORDER_UPDATE, s.decide("ORB arms").cause)
        s.ran("ORB arms")
        assertFalse(s.anyPending())
        // A market-wide post (no instrument) wakes every worker waiting on the cause.
        s.post(WorkerWake.Cause.ORDER_UPDATE)
        now += 30_000
        assertTrue(s.decide("MCX").run)
        // A cause nobody waits on wakes nobody.
        s.ran("MCX"); s.ran("ORB arms"); now += 30_000
        s.post(WorkerWake.Cause.NEWS)
        assertFalse(s.anyPending())
    }

    @Test fun theMinimumGapAdaptsToTheMarket() {
        val base = 20_000L
        assertEquals(10_000L, WorkerWake.minIntervalMs(base, WorkerWake.Pace(busy = true)))
        assertEquals(10_000L, WorkerWake.minIntervalMs(base, WorkerWake.Pace(nearLevel = true)))
        assertEquals(40_000L, WorkerWake.minIntervalMs(base, WorkerWake.Pace(quiet = true)))
        assertEquals(20_000L, WorkerWake.minIntervalMs(base, WorkerWake.Pace(quiet = true, holding = true)))
        assertEquals(20_000L, WorkerWake.minIntervalMs(base, WorkerWake.Pace()))
        // Busy: a posted event 12 s after the last run wakes it (10 s gap); normal it waits (20 s).
        s.register(five); s.ran("ORB arms"); now += 12_000
        s.post(WorkerWake.Cause.POSITION_CHANGE)
        assertFalse(s.decide("ORB arms", WorkerWake.Pace()).run)
        assertTrue(s.decide("ORB arms", WorkerWake.Pace(busy = true)).run)
    }

    @Test fun theLongestSleepAndTheSignalWindowAndANearLevelWake() {
        s.register(five); s.ran("ORB arms")
        now += 30_000
        assertEquals(WorkerWake.Cause.SIGNAL_WINDOW, s.decide("ORB arms", WorkerWake.Pace(inWindow = true)).cause)
        assertEquals(WorkerWake.Cause.NEAR_LEVEL, s.decide("ORB arms", WorkerWake.Pace(nearLevel = true)).cause)
        // A clock set back: looked at again at once.
        s.ran("ORB arms"); now -= 120_000
        assertEquals(WorkerWake.Cause.FIRST, s.decide("ORB arms").cause)
        s.ran("ORB arms"); now += 6 * 60_000L
        assertEquals(WorkerWake.Cause.MAX_SLEEP, s.decide("ORB arms", WorkerWake.Pace(quiet = true)).cause)
        // Re-registering keeps the counts; a new day clears them.
        s.register(five)
        assertTrue(s.counts().getValue("ORB arms").wakeTotal > 0)
        s.resetCounts()
        assertEquals(0, s.counts().getValue("ORB arms").wakeTotal)
        assertNull(s.counts().getValue("ORB arms").wakes[WorkerWake.Cause.FIRST])
    }

    @Test fun theSafetyNetIsFifteenSecondsWhileHoldingWhateverTheSetting() {
        assertEquals(15_000L, WorkerWake.safetyNetMs(anyOpen = true, flatSec = 60))
        assertEquals(30_000L, WorkerWake.safetyNetMs(anyOpen = false))
        assertEquals(60_000L, WorkerWake.safetyNetMs(anyOpen = false, flatSec = 600))
        assertEquals(15_000L, WorkerWake.safetyNetMs(anyOpen = false, flatSec = 1))
    }

    @Test fun oneMinuteCandlesAreReadFromTheClock() {
        val m = 1_760_000_040_000L - Math.floorMod(1_760_000_040_000L, 60_000L)
        assertEquals(m - 60_000L, WorkerWake.lastClose(m + 1_000L, 60_000L), "a second after the boundary it has not settled")
        assertEquals(m, WorkerWake.lastClose(m + WorkerWake.SETTLE_MS, 60_000L))
        val one = WorkerWake.Scheduler { now }
        one.register(WorkerWake.Spec("Night", setOf(WorkerWake.Cause.CANDLE_1M), maxSleepMs = 120_000L))
        one.ran("Night"); now += 61_000
        assertEquals(WorkerWake.Cause.CANDLE_1M, one.decide("Night").cause)
    }
}
