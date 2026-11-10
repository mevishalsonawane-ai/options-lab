package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test fun exitRetryLineEndsWithItsTrade() {
        val sym = "BANKNIFTY23NOV2654900PE"
        assertTrue(ArmStatus.exitRetryOf("exit_retry $sym", sym))
        assertTrue(ArmStatus.exitRetryOf("closed-elsewhere check failed on $sym: IOException; tried again next look", sym))
        assertFalse(ArmStatus.exitRetryOf("exit_retry NIFTY26N0924550PE", sym), "another contract's failed exit is kept")
        assertFalse(ArmStatus.exitRetryOf("exit_retry ${sym}X", sym), "a longer symbol is not this one")
        assertFalse(ArmStatus.exitRetryOf("closed-elsewhere check failed on ${sym}X: e", sym))
        assertFalse(ArmStatus.exitRetryOf(null, sym)); assertFalse(ArmStatus.exitRetryOf("exit_retry ", ""))
        assertTrue(ArmStatus.exitRetryOf("exit check failed on $sym: IOException; tried again next look", sym))
        assertEquals(sym, ArmStatus.exitRetrySymbol("exit_retry $sym"))
        assertEquals(sym, ArmStatus.exitRetrySymbol("exit check failed on $sym: X; tried again next look"))
        assertNull(ArmStatus.exitRetrySymbol("exit_retry "), "no contract named")
        assertNull(ArmStatus.exitRetrySymbol("exit check failed on : X"))
        assertNull(ArmStatus.exitRetrySymbol("exit check failed on $sym"), "no colon: not the line's form")
        assertNull(ArmStatus.exitRetrySymbol("bought $sym"), "any other line is not a failed exit")
        assertNull(ArmStatus.exitRetrySymbol(null))
    }

    @Test fun aFailedExitLineEndsOnceTheArmHoldsItNoLonger() {
        val st = mapOf(
            "R51|exit" to "exit_retry BANKNIFTY23NOV2654900PE",           // the 30 Oct line: that trade is closed
            "R72|exit" to "exit check failed on NIFTY26N0924550PE: IOException; tried again next look", // still held: kept
            "R52|exit" to "closed-elsewhere check failed on X1: E; tried again next look",
            "R53|exit" to "some other words",                              // not a failed-exit line: kept
            "R51|NIFTY" to "exit_retry BANKNIFTY23NOV2654900PE",          // not an exit key: kept
        )
        val open = setOf("R72" to "NIFTY26N0924550PE", "R51" to "X1")      // R51 holds X1, not R52
        assertEquals(setOf("R51|exit", "R52|exit"), ArmStatus.staleExitKeys(st, open).toSet())
        assertEquals(emptyList(), ArmStatus.staleExitKeys(emptyMap(), open))
        assertEquals(listOf("R51|exit"), ArmStatus.staleExitKeys(mapOf("R51|exit" to "exit_retry A"), emptySet()))
    }
}
