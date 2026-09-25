package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules as ORB_STRATEGY.md states them, case by case. */
class OrbRulesTest {
    private val day = LocalDate.of(2026, 9, 24)
    private fun at(h: Int, m: Int) = LocalDateTime.of(day, LocalTime.of(h, m))
    private fun bar(h: Int, m: Int, close: Double, high: Double = close, low: Double = close, open: Double = close) =
        Bar(at(h, m), open, high, low, close)

    /** 09:15 .. 10:00 inside 55000..55200, then [after] bars from 10:05 at the given closes. */
    private fun session(vararg after: Double): List<Bar> {
        val out = ArrayList<Bar>()
        var t = at(9, 15)
        while (!t.toLocalTime().isAfter(LocalTime.of(10, 0))) {
            out += Bar(t, 55100.0, 55200.0, 55000.0, 55100.0); t = t.plusMinutes(5)
        }
        for (c in after) { out += Bar(t, c, c, c, c); t = t.plusMinutes(5) }
        return out
    }

    @Test fun rangeNeedsTheTenOClockBar() {
        val bars = session()
        assertEquals(10, bars.size)
        assertEquals(55200.0 to 55000.0, OrbRules.openingRange(bars))
        assertNull(OrbRules.openingRange(bars.dropLast(1)))
    }

    @Test fun onlyCompletedBarsCount() {
        val bars = session(55300.0)
        assertEquals(10, OrbRules.completed(bars, at(10, 9)).size)       // 10:05 still forming
        assertEquals(11, OrbRules.completed(bars, at(10, 10)).size)
    }

    @Test fun breakDirections() {
        val rng = 55200.0 to 55000.0
        assertEquals(1 to "break", OrbRules.entrySignal(session(55201.0), rng, OrbRules.ORB, null))
        assertEquals(-1 to "break", OrbRules.entrySignal(session(54999.0), rng, OrbRules.ORB, null))
        assertEquals(0 to "inside_range", OrbRules.entrySignal(session(55200.0), rng, OrbRules.ORB, null))
        assertEquals(0 to "no_decision_bar", OrbRules.entrySignal(session(), rng, OrbRules.ORB, null))   // 10:00 is range, not decision
    }

    @Test fun lastDecisionBarIs1425() {
        assertTrue(OrbRules.mayDecide(bar(14, 25, 1.0)))
        assertTrue(!OrbRules.mayDecide(bar(14, 30, 1.0)))
        assertTrue(!OrbRules.mayDecide(bar(10, 0, 1.0)))
        assertTrue(OrbRules.mayDecide(bar(10, 5, 1.0)))
    }

    @Test fun freshOnlySkipsAContinuedBreak() {
        val rng = 55200.0 to 55000.0
        val twoUp = session(55250.0, 55300.0)
        assertEquals(0 to "not_a_fresh_break", OrbRules.entrySignal(twoUp, rng, OrbRules.ORB_FRESH, null))
        assertEquals(1 to "break", OrbRules.entrySignal(twoUp, rng, OrbRules.ORB, null))
        // A break the other way after an up-break is fresh.
        assertEquals(-1 to "break", OrbRules.entrySignal(session(55250.0, 54900.0), rng, OrbRules.ORB_FRESH, null))
    }

    @Test fun reentryFromTheBarAfterTheExitBar() {
        val rng = 55200.0 to 55000.0
        val bars = session(55250.0, 55300.0, 55350.0)       // 10:05, 10:10, 10:15
        assertEquals(0 to "cooling_down_after_exit", OrbRules.entrySignal(bars.dropLast(1), rng, OrbRules.ORB, at(10, 12)))
        assertEquals(1 to "break", OrbRules.entrySignal(bars, rng, OrbRules.ORB, at(10, 12)))
    }

    @Test fun strikeIsHalfUp() {
        assertEquals(55100, OrbRules.atmStrike(55050.0))     // banker's would give 55000
        assertEquals(55000, OrbRules.atmStrike(55049.99))
        assertEquals(55200, OrbRules.atmStrike(55150.0))
        val bars = listOf(bar(9, 15, 1.0), bar(9, 20, 55123.0))
        assertEquals(at(9, 20), OrbRules.strikeBar(bars)!!.start)
    }

    @Test fun expiryIsNeverToday() {
        val listed = listOf(day, LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 24))
        assertEquals(LocalDate.of(2026, 10, 27), OrbRules.expiryAfter(day, listed))
        assertEquals("BANKNIFTY27OCT2656300CE", OrbRules.optionSymbol(LocalDate.of(2026, 10, 27), 56300, "CE"))
    }

    @Test fun exitsAndStopTrigger() {
        assertEquals("stop", OrbRules.exitReason(200.0, 160.0, at(11, 0)))
        assertNull(OrbRules.exitReason(200.0, 160.05, at(11, 0)))
        assertEquals("target", OrbRules.exitReason(200.0, 240.0, at(11, 0)))
        assertEquals("session_end", OrbRules.exitReason(200.0, 200.0, at(15, 10)))
        assertNull(OrbRules.exitReason(35.0, 0.5, at(11, 0)))          // no stop at or below zero
        assertEquals(160.0, OrbRules.stopTrigger(200.0))
        assertEquals(172.35, OrbRules.stopTrigger(212.37))
        assertNull(OrbRules.stopTrigger(40.0))
    }

    @Test fun replayFillsOnTheNextBarOpen() {
        val index = session(55300.0, 55300.0, 55300.0)          // breaks up at 10:05
        val flat = index.map { Bar(it.start, 300.0, 300.0, 300.0, 300.0) }
        val ce = flat.toMutableList().also {
            it[11] = Bar(at(10, 10), 310.0, 312.0, 305.0, 311.0)    // entry at 310 on the 10:10 open
            it[12] = Bar(at(10, 15), 320.0, 355.0, 318.0, 350.0)    // +40 reached
        }
        val t = Replay.day(OrbRules.ORB, index, ce, flat)
        assertEquals(1, t.size)
        assertEquals(ReplayTrade("10:05", "10:15", "CE", 310.0, 350.0, "target"), t[0])
    }

    @Test fun replayStopGapsThrough() {
        val index = session(55300.0, 55300.0, 55300.0)
        val flat = index.map { Bar(it.start, 300.0, 300.0, 300.0, 300.0) }
        val ce = flat.toMutableList().also {
            it[11] = Bar(at(10, 10), 310.0, 312.0, 305.0, 311.0)
            it[12] = Bar(at(10, 15), 250.0, 255.0, 240.0, 245.0)    // opens 60 below: fills at the open, not 270
        }
        assertEquals(250.0, Replay.day(OrbRules.ORB, index, ce, flat)[0].exit)
    }

    @Test fun passRule() {
        val d = LocalDate.of(2026, 9, 23)
        val v = PassRule.judge(listOf(
            PassRule.Closed(d, 1000.0, true), PassRule.Closed(d, -200.0, false), PassRule.Closed(d.plusDays(1), 300.0, false),
        ))
        assertEquals(1100.0, v.net)
        assertEquals(2, v.days)
        assertTrue(!v.passes)              // drop-best-3 leaves nothing, t is small
        assertTrue(!v.finished)
    }
}
