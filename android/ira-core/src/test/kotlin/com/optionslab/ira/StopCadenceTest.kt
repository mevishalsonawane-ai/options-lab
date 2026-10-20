package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 20 Oct, enhancement round 1 (idea 6): the 15-second stop check's window and its drift guard. */
class StopCadenceTest {
    private val day = LocalDate.of(2026, 10, 21)

    @Test fun theWindowRunsFromTheLastLooksStartNotItsEnd() {
        // The last look started at 100 s and took 3 s: the next is due at 115 s, not 118 s.
        assertEquals(115_000L, StopCadence.windowEnd(100_000L, 103_000L))
        // No look yet (0), or a clock that went back: a full window from now.
        assertEquals(118_000L, StopCadence.windowEnd(0L, 103_000L))
        assertEquals(118_000L, StopCadence.windowEnd(200_000L, 103_000L))
    }

    @Test fun aSlowLookIsFollowedSoonButNeverInATightLoop() {
        // The look took 20 s (over the window): the next comes 5 s after it (a slow broker is never hit back to back), not 15 s later.
        assertEquals(125_000L, StopCadence.windowEnd(100_000L, 120_000L))
        // A check of 12 s: 15 s from the start would leave 3 s; the rest is 5 s.
        assertEquals(117_000L, StopCadence.windowEnd(100_000L, 112_000L))
        assertEquals(115_000L + 0L, StopCadence.windowEnd(100_000L, 100_000L))
    }

    @Test fun gapsOf16sOrLessAreFineAndLongerOnesAreDrifts() {
        val d = StopCadence.Drift()
        assertNull(d.look(0L + 1, held = true, today = day), "the first look has nothing to measure")
        assertNull(d.look(15_001L, true, day))
        assertNull(d.look(31_001L, true, day), "16 s exactly is not a drift")
        val g = d.look(49_001L, true, day)
        assertNotNull(g); assertEquals(18_000L, g.gapMs); assertFalse(g.alert)
        val big = d.look(89_001L, true, day)
        assertNotNull(big); assertTrue(big.alert, "40 s is shown to Boss")
        assertEquals(Triple(2, 4, 40_000L), d.today(day))
        assertTrue(d.line(day).contains("2 of 4 gaps while holding were over 16 s today (worst 40 s)"), d.line(day))
        assertTrue(StopCadence.words(big).contains("40 s apart"))
        assertTrue(StopCadence.words(big).contains("keep the app open"))
        assertFalse(StopCadence.words(g).contains("keep the app open"))
    }

    @Test fun aLookWithNothingHeldStartsAgainAndANewDayResets() {
        val d = StopCadence.Drift()
        d.look(1_000L, true, day)
        assertNull(d.look(100_000L, false, day), "nothing held at this look: not measured")
        assertNull(d.look(200_000L, true, day), "the gap from a look with nothing held is not a drift")
        assertNotNull(d.look(230_000L, true, day))
        // The next morning: yesterday's last look is never a 17-hour drift.
        assertNull(d.look(230_000L + 17 * 3_600_000L, true, day.plusDays(1)))
        assertEquals(Triple(0, 0, 0L), d.today(day.plusDays(1)))
        assertEquals(Triple(0, 0, 0L), d.today(day.minusDays(1)), "another day than the one counted reads none")
        assertTrue(d.line(day.plusDays(1)).contains("none measured today"))
    }

    @Test fun allGapsFineSaysSo() {
        val d = StopCadence.Drift()
        d.look(1L, true, day); d.look(15_001L, true, day)
        assertEquals("Stop-check gaps: all 1 gaps while holding were 16 s or less today.", d.line(day))
    }

    @Test fun aClockThatWentBackIsNotADrift() {
        val d = StopCadence.Drift()
        d.look(50_000L, true, day)
        assertNull(d.look(10_000L, true, day))
    }

    /** 20 Oct (idea 2): the watch's evening look, after F&O's close, only while a carried protection waits for its GTT. */
    @Test fun theEveningLookIsDueOnlyJustAfterTheCloseWithSomethingWaiting() {
        val close = 15 * 60 + 40
        assertFalse(EveningLook.due(close, close, tradingDay = true, pending = true), "the close minute itself: the session's own pass")
        assertTrue(EveningLook.due(close + 1, close, true, true))
        assertTrue(EveningLook.due(close + EveningLook.MINUTES, close, true, true))
        assertFalse(EveningLook.due(close + EveningLook.MINUTES + 1, close, true, true), "bounded: half an hour")
        assertFalse(EveningLook.due(close + 5, close, true, pending = false), "nothing waiting: the watch ends at the close as before")
        assertFalse(EveningLook.due(close + 5, close, tradingDay = false, pending = true), "a holiday")
        assertFalse(EveningLook.due(9 * 60 + 30, close, true, true), "never in the session")
    }
}
