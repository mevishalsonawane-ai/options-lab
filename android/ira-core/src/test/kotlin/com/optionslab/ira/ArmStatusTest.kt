package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 21 Oct: an arm's status line from an earlier session is marked with its day, never shown as today's ([ArmStatus]). */
class ArmStatusTest {
    private val fri = LocalDate.of(2026, 10, 16)
    private val tue = LocalDate.of(2026, 10, 20)
    private val wed = LocalDate.of(2026, 10, 21)

    @Test fun yesterdaysNoteIsMarkedAndTodaysIsNot() {
        val s = mapOf("r52|NIFTY" to "13:45 put break missed (the fill window passed)", "r52|BANKNIFTY" to "10:15 call bought")
        val days = mapOf("r52|NIFTY" to tue, "r52|BANKNIFTY" to wed)
        val shown = ArmStatus.shown(s, days, wed)
        assertEquals("from 20 Oct: 13:45 put break missed (the fill window passed)", shown.getValue("r52|NIFTY"))
        assertEquals("10:15 call bought", shown.getValue("r52|BANKNIFTY"))
        // On its own day, as it is.
        assertEquals(s.getValue("r52|NIFTY"), ArmStatus.shown(s, days, tue).getValue("r52|NIFTY"))
    }

    @Test fun stickyLinesAndLinesOfNoKnownDayAreShownAsTheyAre() {
        val s = mapOf("m" to "switched off on your OK", "n" to "no break", "o" to "unknown")
        val shown = ArmStatus.shown(s, mapOf("m" to fri, "n" to fri), wed) { k, _ -> k == "m" }
        assertEquals("switched off on your OK", shown.getValue("m"))
        assertEquals("from 16 Oct: no break", shown.getValue("n"))
        assertEquals("unknown", shown.getValue("o"))
    }

    @Test fun keptDaysReadBack() {
        assertEquals(tue, ArmStatus.decodeDay("2026-10-20"))
        assertEquals(tue, ArmStatus.decodeDay("2026-10-20|5f3a"))
        assertNull(ArmStatus.decodeDay("bad")); assertNull(ArmStatus.decodeDay(null))
        assertEquals(tue, ArmStatus.unknownDay(tue, wed))
        assertEquals(wed, ArmStatus.unknownDay(wed.plusDays(3), wed), "a book saved 'in the future' (a clock change): today")
        assertEquals(wed, ArmStatus.unknownDay(null, wed))
    }
}
