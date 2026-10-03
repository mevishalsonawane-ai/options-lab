package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The second line-by-line review's findings (batches G to I), so they stay fixed. */
class ReviewFixes2Test {
    private fun k(s: String) = Commands.parse(s)

    @Test fun questionsAndStatementsNeverChangeALimit() {
        for (s in listOf("whats the daily loss limit, is it 5000", "tell me if max lots is 3", "if the daily loss limit is 5000 how much can I lose",
                "the daily loss limit is 5000 right", "show my nrml product positions", "the order product mis or nrml which is better",
                "increase the daily loss limit by 2000", "reduce max lots by 1", "set a stop loss limit of 200 on my nifty trade"))
            assertTrue(k(s)?.kind != Command.Kind.SET_LIMIT, s)
    }

    @Test fun offOnlyWhenSaid() {
        assertEquals(3.0, k("set max lots to 3 before the square off")?.level)
        assertEquals(0.0, k("turn off the daily loss limit")?.level)
        assertEquals(0.0, k("daily loss limit off")?.level)
        assertEquals(5.0, k("max lots 5")?.level)
        assertEquals(0.0, k("set order product to MIS")?.level)
    }

    @Test fun nakedShortsMeanWhatIsSaid() {
        assertEquals(1.0, k("disable naked shorts")?.level, "disabling the shorts is blocking them")
        assertEquals(1.0, k("block naked shorts")?.level)
        assertEquals(0.0, k("allow naked shorts")?.level)
        assertEquals(0.0, k("turn off naked shorts blocking")?.level)
    }

    @Test fun moveAlarmsNeedACondition() {
        assertTrue(k("tell me why nifty is down 2% today")?.kind != Command.Kind.ALARM_ADD)
        assertNull(MoveAlarm.read(" alert me if banknifty moves 1 percent "), "a move with no direction is asked again")
        assertEquals(Command.Kind.ALARM_ADD, k("tell me if BankNifty falls 1% from here")?.kind)
        assertEquals(Command.Kind.ALARM_ADD, k("alert me when nifty goes above 25000")?.kind)
    }

    @Test fun whatIfTakesTheWrittenTime() {
        assertEquals(11 * 60 + 15, WhatIf.minute("what if I had taken the 11:15 trade with 2 lots"))
        assertEquals(14 * 60, WhatIf.minute("what if I took the 2 pm trade"))
        assertEquals(10 * 60 + 30, WhatIf.minute("what if I had taken the 10 30 suggestion"))
    }

    @Test fun historyWithPercentDoesNotCrash() {
        val l = SettingsHistory.lines(listOf(SettingsHistory.Change(LocalDateTime.of(2026, 10, 2, 10, 0), SettingsTalk.Key.DRAWDOWN, 5.0, 10.0, "Jarvis")), LocalDate.of(2026, 10, 2))
        assertEquals("2026-10-02 10:00: the max drawdown 5% to 10% (Jarvis), more risk.", l[1])
    }

    @Test fun hindiKeepsTheSign() {
        assertNull(Hindi.accept("P&L +Rs 4,200.", "पी एंड एल -Rs 4,200."))
    }

    @Test fun mixedQuestionsKeepEveryAnswer() {
        val s = AppAnswers.sections("how is my position and what is my p&l today")
        assertTrue(Section.PNL in s && Section.EXPLAIN_POS in s, s.toString())
        assertTrue(Section.EXPLAIN_POS !in AppAnswers.sections("how is the trade check today"))
    }
}
