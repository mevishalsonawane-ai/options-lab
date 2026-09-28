package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrbCoverageTest {
    private val d = LocalDate.of(2026, 9, 21)
    private fun at(h: Int, m: Int) = d.atTime(h, m)
    private fun bar(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Bar(at(h, m), o, hi, lo, c)
    private fun flat(h: Int, m: Int, px: Double) = bar(h, m, px, px, px, px)

    /** 09:15..10:00 inside 100..110, then the given closes every 5 minutes from 10:05. */
    private fun index(vararg closes: Double): List<Bar> {
        val or = (0..9).map { i -> val t = at(9, 15).plusMinutes(5L * i); Bar(t, 105.0, 110.0, 100.0, 105.0) }
        return or + closes.mapIndexed { i, c -> val t = at(10, 5).plusMinutes(5L * i); Bar(t, c, c, c, c) }
    }
    private fun leg(n: Int, px: (Int) -> Double) = (0 until n).map { i -> val t = at(9, 15).plusMinutes(5L * i); val p = px(i); Bar(t, p, p, p, p) }

    @Test fun `replay - no range, a PE break, gaps through the stop, a non-positive entry and the last bar`() {
        assertTrue(Replay.day(OrbRules.ORB, index().take(5), leg(5) { 100.0 }, leg(5) { 100.0 }).isEmpty(), "no range until 10:00")
        // Break down at 10:05 -> PE from 10:10 open 100; it gaps to 50 at 10:15 (open below the 60 stop).
        val ix = index(95.0, 95.0, 95.0)
        val pe = leg(13) { i -> if (i >= 12) 50.0 else 100.0 }
        val t = Replay.day(OrbRules.ORB, ix, leg(13) { 100.0 }, pe)
        assertEquals(1, t.size)
        assertEquals(ReplayTrade("10:05", "10:15", "PE", 100.0, 50.0, "stop"), t[0])
        assertEquals(-50.0, t[0].points)
        // A zero premium on the next bar skips the signal; no later bar can enter.
        assertTrue(Replay.day(OrbRules.ORB, index(95.0, 95.0), leg(12) { 100.0 }, leg(12) { i -> if (i == 11) 0.0 else 100.0 }).isEmpty())
        // No exit before the data ends: last bar close.
        val last = Replay.day(OrbRules.ORB, index(115.0, 115.0, 115.0), leg(13) { i -> 100.0 + i * 0.1 }, leg(13) { 100.0 })
        assertEquals("last_bar", last.single().why); assertEquals("CE", last.single().right)
        assertEquals(101.2, last.single().exit)
    }

    @Test fun `replay squares off at 15_10 on the open`() {
        val n = (15 * 60 + 10 - (9 * 60 + 15)) / 5 + 1   // bars through 15:10
        val closes = DoubleArray(n - 10) { if (it == 0) 115.0 else 105.0 }
        val t = Replay.day(OrbRules.ORB, index(*closes), leg(n) { i -> 100.0 + (i % 3) }, leg(n) { 100.0 })
        assertEquals("session_end", t.single().why); assertEquals("15:10", t.single().exitBar)
    }

    @Test fun `opening range and fresh breaks`() {
        assertNull(OrbRules.openingRange(emptyList()))
        assertNull(OrbRules.openingRange(listOf(flat(9, 10, 1.0), flat(10, 5, 1.0))), "no bar inside the range window")
        assertNull(OrbRules.openingRange(index().take(9)), "10:00 not yet in")
        assertEquals(110.0 to 100.0, OrbRules.openingRange(listOf(flat(9, 10, 500.0)) + index()), "a pre-open bar is outside the window")
        val rng = 110.0 to 100.0
        // A single decision bar is always fresh.
        assertEquals(1 to "break", OrbRules.entrySignal(listOf(flat(10, 5, 111.0)), rng, OrbRules.ORB_FRESH, null))
        assertEquals(0 to "not_a_fresh_break", OrbRules.entrySignal(listOf(flat(10, 5, 111.0), flat(10, 10, 112.0)), rng, OrbRules.ORB_FRESH, null))
        assertEquals(-1 to "break", OrbRules.entrySignal(listOf(flat(10, 5, 111.0), flat(10, 10, 99.0)), rng, OrbRules.ORB_FRESH, null))
        assertEquals(1 to "break", OrbRules.entrySignal(listOf(flat(10, 5, 111.0), flat(10, 10, 112.0)), rng, OrbRules.ORB, null))
        assertEquals(listOf(OrbRules.ORB, OrbRules.ORB_FRESH), OrbRules.ARMS)
    }

    @Test fun `the arm's minute window`() {
        assertFalse(OrbRules.inWindow(LocalTime.of(9, 19))); assertTrue(OrbRules.inWindow(LocalTime.of(9, 20)))
        assertTrue(OrbRules.inWindow(LocalTime.of(15, 12))); assertFalse(OrbRules.inWindow(LocalTime.of(15, 13)))
    }

    @Test fun `the pass rule`() {
        assertNull(PassRule.tStat(listOf(1.0))); assertNull(PassRule.tStat(listOf(2.0, 2.0)))
        assertEquals(kotlin.math.sqrt(3.0), PassRule.tStat(listOf(0.0, 1.0, 2.0))!!, 1e-12)
        val empty = PassRule.judge(emptyList())
        assertFalse(empty.passes); assertFalse(empty.finished); assertNull(empty.t)
        val days = (0 until 40).map { PassRule.Closed(d.plusDays(it.toLong()), 100.0 + it, if (it % 2 == 0) true else if (it % 3 == 0) false else null) }
        val v = PassRule.judge(days)
        assertTrue(v.finished); assertEquals(40, v.days); assertEquals(40, v.trades)
        assertTrue(v.checks.first { it.first == "Net > 0 on down days" }.second)
        assertTrue(v.passes, v.checks.toString())
        val sixty = List(60) { PassRule.Closed(d, if (it % 2 == 0) 50.0 else -10.0, it % 2 == 0) }
        val s = PassRule.judge(sixty)
        assertTrue(s.finished); assertEquals(1, s.days)
        assertFalse(s.checks.first { it.first == "Net > 0 on down days" }.second)
        val dropped = PassRule.judge(listOf(PassRule.Closed(d, 100.0, true), PassRule.Closed(d, 1.0, false), PassRule.Closed(d, 1.0, true), PassRule.Closed(d, -5.0, false)))
        assertFalse(dropped.checks.first { it.first == "Net > 0 without the 3 best trades" }.second)
    }
}
