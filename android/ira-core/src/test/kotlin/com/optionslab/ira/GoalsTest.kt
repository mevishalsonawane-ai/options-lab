package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalsTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    @Test fun goalsInWords() {
        assertEquals(Goals.Goal(Goals.Kind.MAX_LOSS, Goals.Period.WEEK, 5000.0), Goals.read("Jarvis, goal: keep my weekly loss under 5,000"))
        assertEquals(Goals.Goal(Goals.Kind.TARGET, Goals.Period.MONTH, 20000.0), Goals.read("my goal is to make 20k this month"))
        assertEquals(Goals.Goal(Goals.Kind.MAX_TRADES, Goals.Period.DAY, 3.0), Goals.read("goal: no more than 3 trades a day"))
        assertEquals(Goals.Goal(Goals.Kind.MAX_LOSS, Goals.Period.MONTH, 100000.0), Goals.read("target: monthly loss under 1 lakh"))
        assertNull(Goals.read("what are my goals"))
        assertNull(Goals.read("buy nifty"))
        assertNull(Goals.read("buy banknifty weekly 52000 CE target 200"))
        assertNull(Goals.read("sell nifty weekly 24000 pe stop loss 80"))
        assertTrue(Goals.asked("what are my goals")); assertTrue(Goals.clearAsked("clear my goals"))
    }

    @Test fun progressIsTracked() {
        val loss = Goals.Goal(Goals.Kind.MAX_LOSS, Goals.Period.WEEK, 5000.0)
        val closed = listOf(Goals.Closed(today.minusDays(1), -2500.0), Goals.Closed(today, -1500.0), Goals.Closed(today.minusDays(9), -9000.0))
        val s = Goals.status(loss, closed, 0, today)
        assertTrue(s.near && !s.broken, s.text)
        assertTrue(Goals.status(loss, closed + Goals.Closed(today, -1200.0), 0, today).broken)
        val t = Goals.status(Goals.Goal(Goals.Kind.TARGET, Goals.Period.MONTH, 3000.0), listOf(Goals.Closed(today, 3500.0)), 0, today)
        assertTrue(t.met && t.text.endsWith("reached; protect it."), t.text)
        assertTrue(Goals.status(Goals.Goal(Goals.Kind.MAX_TRADES, Goals.Period.DAY, 3.0), emptyList(), 3, today).text.endsWith("the day's limit is reached."))
        assertEquals(1, Goals.add(listOf(loss), loss.copy(amount = 4000.0)).size)
    }
}
