package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VolFilterTest {
    private val today = LocalDate.of(2026, 10, 7)

    /**
     * One session's 1-minute bars from 09:15 to 15:29: each minute moves by [step] points, alternately up and down (so a
     * 5-minute bar's body is one step), from 50,000. [jump]: minutes (from 09:15) that instead rise by that many points each.
     */
    private fun session(day: LocalDate, step: Double, jump: Pair<IntRange, Double>? = null): List<Bar> {
        var p = 50_000.0
        return (0 until 375).map { k ->
            val move = if (jump != null && k in jump.first) jump.second else if (k % 2 == 0) step else -step
            val o = p; p += move
            Bar(day.atTime(9, 15).plusMinutes(k.toLong()), o, maxOf(o, p), minOf(o, p), p)
        }
    }

    /** [n] earlier sessions (weekdays not needed: any earlier dates), the oldest with the smallest steps 1.0, 1.1, 1.2, ... */
    private fun history(n: Int, step: (Int) -> Double = { 1.0 + 0.1 * it }): List<Bar> =
        (0 until n).flatMap { i -> session(today.minusDays((n - i).toLong()), step(i)) }

    private fun at(h: Int, m: Int): LocalDateTime = today.atTime(h, m)

    @Test fun aCalmDayIsNotSkipped() {
        val v = VolFilter.judge(history(22) + session(today, 1.0), at(11, 0))
        assertEquals(VolFilter.Verdict(false, "calm"), v)
    }

    @Test fun aBigCandleJustBeforeTheSignalWouldSkipIt() {
        // 10:55-10:59 rise 12 points a minute: a 60-point body against a usual 2 or so (the last 20 sessions' steps 1.2-3.1).
        val v = VolFilter.judge(history(22) + session(today, 1.0, jump = (100..104) to 12.0), at(11, 0))
        assertEquals(true, v.wouldSkip)
        assertTrue(v.reason.startsWith("big candle: the 10:55 5-min bar moved 60 points, 27.9× the usual 2"), v.reason)
    }

    @Test fun theLastHalfHoursVolatilityInItsTopFifthWouldSkipIt() {
        // Four points a minute against at most 3.1 in the earlier sessions; a 4-point body is under three times the usual.
        val v = VolFilter.judge(history(22) + session(today, 4.0), at(11, 0))
        assertEquals(VolFilter.Verdict(true, "high volatility: the last 30 minutes in the top 20% for the time of day"), v)
        // Only the last 20 sessions count: 22 sessions whose two oldest are wild leave the percentile alone.
        val wildOld = history(22) { if (it < 2) 50.0 else 1.0 + 0.1 * it }
        assertEquals(true, VolFilter.judge(wildOld + session(today, 4.0), at(11, 0)).wouldSkip)
    }

    @Test fun bothReasonsAreGiven() {
        val v = VolFilter.judge(history(22) + session(today, 4.0, jump = (100..104) to 12.0), at(11, 0))
        assertEquals(true, v.wouldSkip)
        assertTrue(v.reason.startsWith("big candle:") && v.reason.endsWith("; high volatility: the last 30 minutes in the top 20% for the time of day"), v.reason)
    }

    @Test fun itNeverLooksPastTheSignalBarsClose() {
        // The jump is in the 11:00 bar, after the 11:00 close: not seen.
        assertEquals(false, VolFilter.judge(history(22) + session(today, 1.0, jump = (105..109) to 12.0), at(11, 0)).wouldSkip)
        // A bar before 09:15 is no part of the session.
        val pre = Bar(today.atTime(9, 7), 50_000.0, 51_000.0, 49_000.0, 51_000.0)
        assertEquals(false, VolFilter.judge(history(22) + session(today, 1.0) + pre, at(11, 0)).wouldSkip)
    }

    @Test fun fifteenHundredToFifteenOFiveWouldSkip() {
        assertEquals(VolFilter.Verdict(true, "15:00-15:05"), VolFilter.judge(history(22) + session(today, 1.0), at(15, 0)))
        assertEquals(true, VolFilter.judge(history(22) + session(today, 1.0), at(15, 5)).wouldSkip)
        assertEquals(false, VolFilter.judge(history(22) + session(today, 1.0), at(15, 10)).wouldSkip)
        assertEquals(false, VolFilter.judge(history(22) + session(today, 1.0), at(14, 55)).wouldSkip)
        // Late, even with no history at all.
        assertEquals(true, VolFilter.judge(session(today, 1.0), at(15, 0)).wouldSkip)
    }

    @Test fun withoutTheHistoryItIsUnknownNeverASkip() {
        val unknown = VolFilter.Verdict(null, "unknown: fewer than 5 earlier sessions or too few bars today")
        assertEquals(unknown, VolFilter.judge(session(today, 4.0, jump = (100..104) to 12.0), at(11, 0)))
        assertEquals(unknown, VolFilter.judge(history(4) + session(today, 4.0), at(11, 0)))
        assertEquals(false, VolFilter.judge(history(5) + session(today, 1.0), at(11, 0)).wouldSkip, "five sessions are enough")
        // A flat history has no typical move: the big-candle clause cannot be judged.
        assertEquals(unknown, VolFilter.judge(history(22) { 0.0 } + session(today, 0.0), at(11, 0)))
        // Too few minutes today for the half hour's volatility (09:15-09:19: four returns), and nothing else fires.
        assertEquals(unknown, VolFilter.judge(history(22) + session(today, 1.0), at(9, 20)))
        // No completed 5-minute bar yet today.
        assertEquals(unknown, VolFilter.judge(history(22) + session(today, 1.0), at(9, 16)))
    }

    @Test fun earlyInTheDayTheWindowIsTheSessionSoFar() {
        // 09:30: 15 minutes, 14 returns, compared with the same window of the earlier sessions.
        assertEquals(false, VolFilter.judge(history(22) + session(today, 1.0), at(9, 30)).wouldSkip)
        assertEquals(true, VolFilter.judge(history(22) + session(today, 4.0), at(9, 30)).wouldSkip)
    }

    @Test fun theHelpers() {
        assertEquals(3.0, VolFilter.body(Bar(at(10, 0), 100.0, 105.0, 95.0, 97.0)))
        assertEquals(2.0, VolFilter.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, VolFilter.median(listOf(4.0, 1.0, 2.0, 3.0)))
        assertEquals(0.0, VolFilter.median(emptyList()))
        assertEquals(4.2, VolFilter.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.8), 1e-9)
        assertEquals(7.0, VolFilter.percentile(listOf(7.0), 0.8))
        assertEquals(5.0, VolFilter.percentile(listOf(1.0, 5.0), 1.0))
        val s = session(today, 1.0)
        // 31 closes up to 11:00 (10:29-10:59): 30 returns of about 1/50,000 each.
        val rv = VolFilter.rv(s, at(11, 0))!!
        assertEquals(sqrt(30 * (ln(50_001.0 / 50_000.0)).let { it * it }), rv, 1e-7)
        assertNull(VolFilter.rv(s, at(9, 20)))
        assertFalse(VolFilter.rv(s, at(9, 21))!!.isNaN())
    }
}
