package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 19 Nov 2026 (audit batch 2: the watch and dead-man gaps): completed checks, asleep or busy, the dead-man's hours and pace. */
class WatchGapsTest {
    private val day = LocalDate.of(2026, 11, 19)

    @Test fun completedChecksMeasureCompletedToCompletedPerBook() {
        val c = StopCadence.Completed()
        assertEquals("Completed stop checks: none measured today (nothing held while the watch ran).", c.line(day))
        c.held(setOf("ORB arms", "arms' live trades"), 0L)
        c.done("ORB arms", 10_000L, day)
        c.done("ORB arms", 25_000L, day)
        c.done("arms' live trades", 12_000L, day)
        c.done("arms' live trades", 52_000L, day)
        assertEquals(Triple(2, 40_000L, "arms' live trades"), c.today(day))
        assertTrue(c.line(day).contains("worst 40.0 s (arms' live trades)"), c.line(day))
        assertEquals(Triple(0, 0L, null), c.today(day.plusDays(1)), "another day: none")
    }

    @Test fun aHeldBookNeverCompletedIsStaleFromWhenItWasFirstSeenHeld() {
        val c = StopCadence.Completed()
        c.held(setOf("strategies"), 100_000L)
        assertTrue(c.stale(144_999L).isEmpty())
        assertEquals(listOf("strategies" to 45_000L), c.stale(145_000L))
        c.done("strategies", 146_000L, day)
        assertTrue(c.stale(150_000L).isEmpty(), "completed: fresh")
        assertEquals(listOf("strategies" to 46_000L), c.stale(192_000L), "no completed check for 46 s")
    }

    @Test fun aBookThatStopsHoldingIsForgottenSoAFlatSpellIsNoGap() {
        val c = StopCadence.Completed()
        c.held(setOf("ORB arms"), 0L)
        c.done("ORB arms", 1_000L, day)
        c.held(emptySet(), 2_000L)
        assertTrue(c.stale(600_000L).isEmpty(), "nothing held: nothing stale")
        c.held(setOf("ORB arms"), 600_000L)
        c.done("ORB arms", 601_000L, day)
        assertEquals(0, c.today(day).first, "the gap across the flat spell is not counted")
        assertTrue(c.stale(640_000L).isEmpty())
    }

    @Test fun theOverTheLimitLineSaysSo() {
        val c = StopCadence.Completed()
        c.done("Pine scripts", 0L, day); c.done("Pine scripts", 50_000L, day)
        assertTrue(c.line(day).endsWith("over the 45 s limit."), c.line(day))
    }

    @Test fun asleepIsTheDifferenceOfTheTwoClocks() {
        val s = StopCadence.SleepSplit()
        assertEquals("Stop checks asleep or busy: none measured today.", s.line(day))
        assertEquals(0L, s.look(2_000L, 2_000L, day), "busy all along")
        assertEquals(28_000L, s.look(30_000L, 2_000L, day), "28 s asleep")
        assertEquals(0L, s.look(1_000L, 1_200L, day), "clock noise is never negative sleep")
        val l = s.line(day)
        assertTrue(l.contains("3 looks") && l.contains("1 with the phone asleep") && l.contains("worst 28 s asleep") && l.contains("2.0 s"), l)
        assertEquals("Stop checks asleep or busy: none measured today.", s.line(day.plusDays(1)))
    }

    @Test fun theDeadManJudgesMcxHoursAndRunsEveryMinuteWhileLiveIsHeld() {
        assertTrue(WatchHealth.staleJudged(nseHours = true, mcxWatchDue = false))
        assertTrue(WatchHealth.staleJudged(nseHours = false, mcxWatchDue = true), "MCX's evening with something held there")
        assertFalse(WatchHealth.staleJudged(nseHours = false, mcxWatchDue = false))
        assertEquals(60_000L, WatchHealth.deadManEveryMs(liveHeld = true))
        assertEquals(5 * 60_000L, WatchHealth.deadManEveryMs(liveHeld = false))
    }

    @Test fun theHoldIsRenewedInNseHoursAndWhileHeldWhateverTheLoopDoes() {
        assertTrue(WatchHealth.holdAwake(progressing = false, reader = false, nseHours = true, held = false))
        assertTrue(WatchHealth.holdAwake(progressing = false, reader = false, nseHours = false, held = true))
        assertTrue(WatchHealth.holdAwake(progressing = true, reader = false, nseHours = false, held = false))
        assertTrue(WatchHealth.holdAwake(progressing = false, reader = true, nseHours = false, held = false))
        assertFalse(WatchHealth.holdAwake(progressing = false, reader = false, nseHours = false, held = false), "a hung loop off-hours lapses")
    }

    @Test fun aClosedDayNeverEndsTheWatchWhileHeldAndACalendarCannotCloseATradingDay() {
        assertTrue(WatchHealth.keepOnClosedDay(nseClockHours = true, held = true))
        assertFalse(WatchHealth.keepOnClosedDay(nseClockHours = true, held = false))
        assertFalse(WatchHealth.keepOnClosedDay(nseClockHours = false, held = true), "outside the hours the dead-man and GTTs stand")
        assertTrue(WatchHealth.refuseClosingToday(trading = true, closesToday = true, minute = 9 * 60 + 15, openMinute = 9 * 60 + 15))
        assertFalse(WatchHealth.refuseClosingToday(trading = true, closesToday = true, minute = 9 * 60 + 14, openMinute = 9 * 60 + 15), "before the open: the fresh list wins")
        assertFalse(WatchHealth.refuseClosingToday(trading = false, closesToday = true, minute = 12 * 60, openMinute = 9 * 60 + 15))
        assertFalse(WatchHealth.refuseClosingToday(trading = true, closesToday = false, minute = 12 * 60, openMinute = 9 * 60 + 15))
    }
}
