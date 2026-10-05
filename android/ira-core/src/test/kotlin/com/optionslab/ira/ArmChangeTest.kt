package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArmChangeTest {
    /** Wednesday 7 Oct 2026: this week is Mon 5 Oct to Wed 7 Oct, last week Mon 28 Sep to Sun 4 Oct. */
    private val wed = LocalDate.of(2026, 10, 7)
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    private val orb = ArmChange.Arm("ORB", true, listOf(
        // Last week: 4 trades, 1 won, -Rs 2,100 (losers -1,000, -800, -600: average -800).
        ArmChange.Trade(d(9, 28), -1000.0), ArmChange.Trade(d(9, 29), 300.0), ArmChange.Trade(d(9, 30), -800.0), ArmChange.Trade(d(10, 2), -600.0),
        // This week: 3 trades, 2 won, +Rs 1,200 (one loser, -400).
        ArmChange.Trade(d(10, 5), 900.0), ArmChange.Trade(d(10, 6), -400.0), ArmChange.Trade(d(10, 7), 700.0),
        // Two weeks ago: never read.
        ArmChange.Trade(d(9, 22), 5000.0),
    ))
    private val fade = ArmChange.Arm("Range Fade", true, listOf(ArmChange.Trade(d(9, 29), 500.0), ArmChange.Trade(d(10, 6), 400.0)))
    private val idle = ArmChange.Arm("Liquidity 15+5", false, emptyList())

    @Test fun eachWeekIsCountedOnTheDayItClosed() {
        val (now, before) = ArmChange.weeks(wed)
        assertEquals(d(10, 5) to wed, now)
        assertEquals(d(9, 28) to d(10, 4), before)
        val w = ArmChange.week(orb.paper, before.first, before.second)
        assertEquals(4, w.trades); assertEquals(1, w.wins); assertEquals(-2100.0, w.net); assertEquals(-800.0, w.avgLoss); assertEquals(25, w.winRate)
        val n = ArmChange.week(orb.paper, now.first, now.second)
        assertEquals(3, n.trades); assertEquals(1200.0, n.net); assertEquals(-400.0, n.avgLoss)
        assertEquals(null, ArmChange.week(emptyList(), now.first, now.second).winRate)
    }

    @Test fun theBiggestChangeIsNamedFirstWithItsNumbers() {
        val rows = ArmChange.rows(listOf(fade, orb, idle), wed)
        assertEquals(listOf("ORB", "Range Fade", "Liquidity 15+5").filter { n -> rows.any { it.name == n } }, rows.map { it.name })
        assertEquals(3300.0, rows.first().change)
        val a = ArmChange.answer(listOf(fade, orb, idle), wed)
        assertTrue("The biggest change: ORB, net -Rs 2,100 last week to +Rs 1,200 this week (+Rs 3,300), win rate 25% to 67%, average loss -Rs 800 to -Rs 400." in a, a)
        assertTrue("All arms together: 4 trades, +Rs 1,600 this week; 5 trades, -Rs 1,600 last week (+Rs 3,200)." in a, a)
        assertTrue("- Range Fade: this week 1 trade, 1 won (100%), +Rs 400, no losing trade" in a, a)
        assertTrue("3 weekdays in, last week was 5" in a, a)
        assertTrue("Under 5 trades in a week" in a, a)
        assertTrue(a.endsWith(ArmChange.NOTE), a)
        // The arm switched off with no trade in either week is left out; one with trades is marked off.
        assertFalse("Liquidity" in a, a)
        val off = ArmChange.answer(listOf(fade.copy(armed = false)), wed)
        assertTrue("Range Fade (off now)" in off, off)
        // Facts, never advice.
        for (w in listOf("should", "recommend", "switch it", "you must")) assertFalse(w in a.lowercase(), w)
    }

    @Test fun noTradesEitherWeekIsSaidPlainly() {
        val a = ArmChange.answer(listOf(idle, ArmChange.Arm("ORB", true, emptyList())), wed)
        assertTrue(a.startsWith("Your arms have no closed paper trades this week or last, Boss"), a)
        // A whole week (Friday on) is not called a part week.
        assertFalse("part week" in ArmChange.answer(listOf(orb), d(10, 9)))
        assertFalse("part week" in ArmChange.answer(listOf(orb), d(10, 11)))
    }

    @Test fun theQuestionIsTheArmsWeekAgainstLastNeverASwitchOrAForecast() {
        for (s in listOf("what's changed in my arms' results this week vs last", "how are my bots doing this week compared to last week",
            "my arms this week vs last week", "my strategies week on week", "what changed in my bots this week",
            "how did my arms do this week versus last week", "compare my bots this week with last week", "week on week for my arms",
            "mere bots ka is hafte vs pichle hafte", "mere arms mein is hafte kya badla", "pichle hafte se mere bots mein kya badla",
            "What has changed with my strategies week on week?")) assertTrue(ArmChange.asked(s), s)
        for (s in listOf("how are my bots doing", "how was my week", "my trades this week vs last week", "will my bots do better next week",
            "should i switch off orb this week", "what changed today", "why did orb lose today", "what's the weakest link in my setup",
            "how are my bots doing today compared to yesterday")) assertFalse(ArmChange.asked(s), s)
    }
}
