package com.optionslab.engine.orb

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LossCutRulesTest {
    private val today = LocalDate.of(2026, 11, 2)

    /** Daily bars for the [n] sessions before today, each [range] wide, the last one [lastRange] wide. */
    private fun daily(n: Int, range: Double, lastRange: Double): List<Bar> = (n downTo 1).map { k ->
        val r = if (k == 1) lastRange else range
        Bar(today.minusDays(k.toLong()).atTime(9, 15), 100.0, 100.0 + r, 100.0, 100.0 + r / 2)
    }

    @Test fun aVeryWideYesterdaySkips() {
        // 19 sessions of 10, yesterday 20: average 10.5, ratio 1.905 >= 1.3206
        assertEquals(true, LossCutRules.wideDay(daily(20, 10.0, 20.0), today))
    }

    @Test fun aNormalYesterdayTakes() {
        assertEquals(false, LossCutRules.wideDay(daily(20, 10.0, 10.0), today))
    }

    @Test fun theCutItselfSkips() {
        // 19 x 10 and yesterday x: x / ((190 + x) / 20) = 1.3206  ->  x = 1.3206 * 190 / (20 - 1.3206)
        val x = LossCutRules.WIDE_CUT * 190.0 / (20.0 - LossCutRules.WIDE_CUT) + 1e-9
        assertEquals(true, LossCutRules.wideDay(daily(20, 10.0, x), today))
        assertEquals(false, LossCutRules.wideDay(daily(20, 10.0, x - 1e-3), today))
    }

    @Test fun onlyTheLast20SessionsBeforeTodayCount() {
        // 40 sessions: the 20 older ones are very wide, ignored; today's own bar is ignored too.
        val old = (40 downTo 21).map { k -> Bar(today.minusDays(k.toLong()).atTime(9, 15), 100.0, 300.0, 100.0, 200.0) }
        val todays = Bar(today.atTime(9, 15), 100.0, 900.0, 100.0, 500.0)
        assertEquals(true, LossCutRules.wideDay(old + daily(20, 10.0, 20.0) + todays, today))
        assertEquals(1.905, LossCutRules.wideRatio((old + daily(20, 10.0, 20.0) + todays).shuffled(java.util.Random(1)), today)!!, absoluteTolerance = 1e-3)
    }

    @Test fun tooFewSessionsOrNoRangeCannotJudge() {
        assertNull(LossCutRules.wideDay(daily(9, 10.0, 30.0), today))
        assertNull(LossCutRules.wideDay(emptyList(), today))
        assertNull(LossCutRules.wideDay(daily(20, 0.0, 0.0), today))
        // a broken bar (high below low) is left out
        val bad = daily(10, 10.0, 30.0) + Bar(today.minusDays(30).atTime(9, 15), 100.0, 90.0, 110.0, 100.0)
        assertEquals(true, LossCutRules.wideDay(bad, today))
        assertNull(LossCutRules.wideDay(daily(9, 10.0, 30.0) + Bar(today.minusDays(30).atTime(9, 15), 100.0, 90.0, 110.0, 100.0), today))
    }

    private val entryMin: LocalDateTime = today.atTime(9, 20)
    private fun bar(min: Int, high: Double) = Bar(entryMin.plusMinutes(min.toLong()), 100.0, high, 99.0, 100.0)

    @Test fun noProgressExitsAtEntryPlusFive() {
        val bars = (0..4).map { bar(it, 104.9) }
        assertEquals(LossCutRules.Verdict.EXIT, LossCutRules.noProgress(100.0, entryMin, bars, entryMin.plusMinutes(5)))
    }

    @Test fun aRiseOf5PercentInTheWindowHolds() {
        val bars = listOf(bar(0, 101.0), bar(2, 105.0), bar(4, 99.0))
        assertEquals(LossCutRules.Verdict.HOLD, LossCutRules.noProgress(100.0, entryMin, bars, entryMin.plusMinutes(6)))
    }

    @Test fun minutesOutsideTheWindowDoNotCount() {
        // the rise comes in minute 5 (after the window) and a bar before the entry minute: still no progress in the window
        val bars = listOf(bar(-1, 150.0), bar(1, 101.0), bar(4, 100.0), bar(5, 150.0))
        assertEquals(LossCutRules.Verdict.EXIT, LossCutRules.noProgress(100.0, entryMin, bars, entryMin.plusMinutes(6)))
        // the window's last minute (entry + 4) not in yet: wait, never guess
        assertEquals(LossCutRules.Verdict.UNKNOWN, LossCutRules.noProgress(100.0, entryMin, listOf(bar(0, 101.0), bar(3, 101.0)), entryMin.plusMinutes(6)))
    }

    @Test fun beforeDueLateOrUnknownNeverActs() {
        val bars = (0..4).map { bar(it, 100.0) }
        assertEquals(LossCutRules.Verdict.NOT_YET, LossCutRules.noProgress(100.0, entryMin, bars, entryMin.plusMinutes(4).plusSeconds(59)))
        assertEquals(LossCutRules.Verdict.EXIT, LossCutRules.noProgress(100.0, entryMin, bars, entryMin.plusMinutes(10)))
        assertEquals(LossCutRules.Verdict.TOO_LATE, LossCutRules.noProgress(100.0, entryMin, bars, entryMin.plusMinutes(10).plusSeconds(1)))
        assertEquals(LossCutRules.Verdict.UNKNOWN, LossCutRules.noProgress(100.0, entryMin, emptyList(), entryMin.plusMinutes(5)))
        assertEquals(LossCutRules.Verdict.UNKNOWN, LossCutRules.noProgress(0.0, entryMin, bars, entryMin.plusMinutes(5)))
    }
}
