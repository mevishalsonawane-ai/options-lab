package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchKTest {
    private fun k(s: String) = Commands.parse(s)?.kind

    @Test fun commands() {
        for (s in listOf("Jarvis, that was wrong", "you got that wrong", "not what I asked", "wrong answer")) assertEquals(Command.Kind.MISTAKE, k(s), s)
        for (s in listOf("Jarvis, exit everything", "emergency exit", "panic", "close everything and stop")) assertEquals(Command.Kind.EXIT_ALL, k(s), s)
        assertEquals(Command.Kind.CLOSE_ALL, k("close all positions"))
        assertEquals(Command.Kind.BRIEF_ON, k("short answers please")); assertEquals(Command.Kind.BRIEF_OFF, k("detailed answers"))
        assertEquals(Command.Kind.MORE, k("tell me more"))
        assertEquals(Command.Kind.PRACTICE, k("practice on last Thursday"))
        assertEquals(10000.0, Commands.parse("set your weekly loss limit to 10000")?.level)
        assertEquals(Command.Kind.JTRADES_WEEKLY, k("set your weekly loss limit to 10000"))
        assertTrue(k("what is my weekly loss?") == null)
        assertEquals(setOf(Section.MISTAKES), AppAnswers.sections("What did you get wrong?"))
    }

    @Test fun practiceDays() {
        val today = LocalDate.of(2026, 10, 3)   // a Saturday
        assertEquals(LocalDate.of(2026, 10, 1), Practice.day("practice on last Thursday", today))
        assertEquals(LocalDate.of(2026, 10, 2), Practice.day("practice yesterday", today))
        assertEquals(LocalDate.of(2026, 9, 24), Practice.day("practice on 24 september", today))
        assertNull(Practice.day("practice", today))
    }

    private fun walk(day: LocalDate, start: Double, seed: Long): List<Candle> {
        val r = java.util.Random(seed); var px = start
        return (0 until 375).map { i ->
            val o = px; px += r.nextGaussian() * 8
            Candle(day.atTime(9, 15).plusMinutes(i.toLong()), o, maxOf(o, px) + 3, minOf(o, px) - 3, px)
        }
    }

    @Test fun practiceReplaysADayInOrder() {
        val day = LocalDate.of(2026, 9, 24)
        val prior = (1..10).flatMap { walk(day.minusDays(it.toLong() + 2), 24000.0, it.toLong()) }.sortedBy { it.t }
        val edges = PatternKind.entries.flatMap { kd -> listOf(5, 15).map { min ->
            PatternExpert.Edge(Market.NIFTY, min, kd, 100, 0.7, 0.7, 0.7, 0.3, 100, 5.0, 5.0, 5.0, 50, 50) } }
        val ev = Practice.run(Market.NIFTY, prior, walk(day, 24000.0, 99), edges, null, 50)
        assertEquals(ev.sortedBy { it.at }, ev)
        assertTrue(ev.all { it.at.toLocalTime() in LocalTime.of(9, 20)..LocalTime.of(15, 0) })
        assertTrue(Practice.summary(Market.NIFTY, day, ev).startsWith("Practice on 2026-09-24 (Nifty)"))
        assertTrue(Practice.run(Market.NIFTY, prior, walk(day, 24000.0, 99), emptyList(), null, 50).isEmpty())
    }

    @Test fun usageRiskWeeklyWake() {
        assertNull(Usage.line(Usage.Day()))
        assertEquals("I heard 12 questions today, did not understand 2, asked you to say \"Jarvis\" first 1 time, 1 thing failed.",
            Usage.line(Usage.Day(heard = 12, misunderstood = 2, nameFirst = 1, failed = 1)))
        assertEquals("If the stop hits you lose about Rs 1,125.00 (0.6% of your capital).", TradeRisk.say(100.0, 75, 1, 200000.0))
        val mon = LocalDate.of(2026, 9, 28)
        assertTrue(WeeklyCap.hit(listOf(mon to -5000.0, mon.plusDays(2) to -4500.0), mon.plusDays(3), 9000.0))
        assertFalse(WeeklyCap.hit(listOf(mon.minusDays(3) to -9000.0), mon.plusDays(3), 9000.0))
        assertEquals(listOf("Jarvis, how is Nifty"), WakeSense.accept(listOf("Jarvis, how is Nifty", "how is Nifty jarvis"), WakeSense.Level.STRICT))
        assertTrue(WakeSense.accept(listOf("I told jarvis about it"), WakeSense.Level.STRICT).isEmpty())
        assertEquals(2, WakeSense.accept(listOf("a", "b"), WakeSense.Level.NORMAL).size)
        assertTrue(Mistakes.lines(listOf(Mistakes.Entry(LocalDateTime.of(2026, 10, 2, 10, 5), "how is nifty", "BankNifty is up"))).last().contains("you said \"how is nifty\""))
    }
}
