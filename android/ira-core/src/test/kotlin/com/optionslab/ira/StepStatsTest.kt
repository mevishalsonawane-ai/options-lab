package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The watch's per-step timings ([StepStats]): p50, p95 and max per day, the slowest first, bounded memory. */
class StepStatsTest {
    private val day = "2026-10-10"

    @Test fun percentilesAndMaxPerStep() {
        val s = StepStats()
        for (ms in 1L..100L) s.record("ORB arms", ms, day)
        val r = s.rows(day).single()
        assertEquals("ORB arms", r.name)
        assertEquals(100, r.count)
        assertEquals(50L, r.p50)
        assertEquals(95L, r.p95)
        assertEquals(100L, r.max)
        assertEquals(5050L, r.totalMs)
    }

    @Test fun theSlowestComeFirstAndANewDayStartsEmpty() {
        val s = StepStats()
        s.record("paper orders", 20, day)
        s.record("daily loss limit", 7_000, day)
        s.record("Pine scripts", 900, day)
        assertEquals(listOf("daily loss limit", "Pine scripts", "paper orders"), s.slowest(day).map { it.name })
        assertEquals(listOf("daily loss limit"), s.slowest(day, 1).map { it.name })
        val line = StepStats.line(s.slowest(day, 2))
        assertTrue(line.startsWith("Slowest steps today: daily loss limit: p50 7.0 s · p95 7.0 s · max 7.0 s (1 run); Pine scripts"), line)
        assertEquals(emptyList(), s.rows("2026-10-09"), "another day's figures are not today's")
        s.record("paper orders", 5, "2026-10-11")
        assertEquals(listOf("paper orders"), s.rows("2026-10-11").map { it.name })
        assertEquals(emptyList(), s.rows(day))
        assertEquals("Slowest steps today: none timed yet", StepStats.line(emptyList()))
    }

    @Test fun memoryIsBounded() {
        val s = StepStats(keep = 10, maxSteps = 3)
        for (i in 1..1_000) s.record("a", i.toLong(), day)
        val a = s.rows(day).single()
        assertEquals(1_000, a.count)
        assertEquals(1_000L, a.max)
        assertTrue(a.p50 > 990, "the percentiles are over the last kept runs: ${a.p50}")
        for (n in listOf("b", "c", "d", "e")) s.record(n, 1, day)
        assertEquals(listOf("a", "b", "c"), s.rows(day).map { it.name }, "at most 3 names")
        s.clear()
        assertEquals(emptyList(), s.rows(day))
        s.record("x", -5, day)
        assertEquals(0L, s.rows(day).single().max, "a negative duration counts as 0")
    }

    @Test fun durationsInWords() {
        assertEquals("850 ms", StepStats.ms(850))
        assertEquals("2.4 s", StepStats.ms(2_400))
        assertEquals("1 min 5 s", StepStats.ms(65_000))
        assertEquals(0L, StepStats.pct(LongArray(0), 50))
    }
}
