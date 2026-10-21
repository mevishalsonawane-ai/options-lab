package com.optionslab.engine.trend

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.MiniJson
import com.optionslab.engine.trend.TrendTurn.Rule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TrendTurnTest {
    private val day = LocalDate.of(2025, 6, 2)

    /** A session of [closes] from 09:15, each minute's open = the previous close, high / low = the two +- [spread]. */
    private fun session(d: LocalDate, closes: DoubleArray, spread: Double = 1.0, skip: Set<Int> = emptySet()): List<Bar> =
        closes.indices.filter { it !in skip }.map { r ->
            val o = if (r == 0) closes[0] else closes[r - 1]
            Bar(d.atTime(9, 15).plusMinutes(r.toLong()), o, maxOf(o, closes[r]) + spread, minOf(o, closes[r]) - spread, closes[r])
        }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun matchesTheResearchGoldenVectors() {
        val g = MiniJson.parse(javaClass.getResourceAsStream("/trend/r63_golden.json")!!.bufferedReader().readText()) as Map<String, Any?>
        fun d(x: Any?) = (x as? Number)?.toDouble() ?: Double.NaN
        val minutes = (g["minutes"] as List<List<Any?>>).map { r -> Bar(LocalDateTime.parse(r[0] as String), d(r[1]), d(r[2]), d(r[3]), d(r[4])) }
        val turns = g["turns"] as Map<String, List<List<Any?>>>
        var n = 0
        for (rule in Rule.entries) for (call in listOf(true, false)) {
            val want = turns.getValue(rule.key + if (call) "C" else "P").map { LocalDateTime.parse(it[0] as String) to (it[1] as String?)?.let { s -> LocalDateTime.parse(s) } }
            val got = TrendTurn.turns(rule, minutes, call).map { it.act to it.firstAct }
            assertEquals(want, got, "${rule.key} ${if (call) "call" else "put"}")
            n += got.size
        }
        assertTrue(n > 100)
    }

    @Test
    fun barsSkipEmptyBarsAndKeepPartialOnes() {
        val ms = session(day, DoubleArray(375) { 100.0 + it * 0.01 }, skip = (5 until 10).toSet() + 12)
        val b = TrendTurn.bars(ms, 5)
        assertEquals(74, b.size)
        assertTrue(b.none { it.b == 1 })
        val b2 = b.first { it.b == 2 }
        assertEquals(day.atTime(9, 30), b2.act)
        assertEquals(ms.first { it.start == day.atTime(9, 29) }.close, b2.close)
    }

    @Test
    fun aMinuteWithoutCloseDoesNotCountAndAMissingOpenFallsBack() {
        val t = day.atTime(9, 15)
        val ms = listOf(Bar(t, Double.NaN, 101.0, 99.0, 100.0), Bar(t.plusMinutes(1), 100.0, 102.0, 98.0, Double.NaN),
            Bar(t.plusMinutes(1), 100.0, 103.0, 97.0, 101.0), Bar(t.plusSeconds(150), 1.0, 1.0, 1.0, 1.0), Bar(day.atTime(8, 0), 1.0, 1.0, 1.0, 1.0))
        val b = TrendTurn.bars(ms, 5).single()
        assertEquals(100.0, b.open)          // the open falls back to the close
        assertEquals(101.0, b.high)          // the second minute (no close) did not count; its repeat was dropped
        assertEquals(100.0, b.close)
        assertEquals((101.0 + 99.0 + 100.0) / 3, b.twap, 1e-9)
    }

    @Test
    fun noMinutesNoBarsNoTurns() {
        assertTrue(TrendTurn.bars(emptyList(), 15).isEmpty())
        for (r in Rule.entries) assertTrue(TrendTurn.turns(r, emptyList(), true).isEmpty())
        assertTrue(TrendTurn.supertrendDir(DoubleArray(0), DoubleArray(0), DoubleArray(0)).isEmpty())
        val one = session(day, DoubleArray(10) { 100.0 })
        assertTrue(TrendTurn.turns(Rule.T1, one, true).isEmpty())
    }

    @Test
    fun emaHandValues() {
        assertEquals(listOf(10.0, 15.0, 22.5), TrendTurn.ema(doubleArrayOf(10.0, 20.0, 30.0), 3).toList())
    }

    @Test
    fun supertrendFlipsDownOnceAfterARallyAndAFall() {
        val c = DoubleArray(40) { 100.0 + it * 100.0 / 39 } + DoubleArray(30) { 200.0 - it * 80.0 / 29 }
        val d = TrendTurn.supertrendDir(DoubleArray(c.size) { c[it] + 1 }, DoubleArray(c.size) { c[it] - 1 }, c)
        assertTrue(d.take(9).all { it == 0 })
        assertEquals(1, d[20]); assertEquals(-1, d.last())
        assertEquals(1, (1 until d.size).count { d[it - 1] == 1 && d[it] == -1 })
        // a fall then a rally flips up
        val c2 = DoubleArray(40) { 200.0 - it * 2.0 } + DoubleArray(30) { 120.0 + it * 4.0 }
        val d2 = TrendTurn.supertrendDir(DoubleArray(c2.size) { c2[it] + 1 }, DoubleArray(c2.size) { c2[it] - 1 }, c2)
        assertEquals(-1, d2[20]); assertEquals(1, d2.last())
    }

    @Test
    fun t1CrossDownOnTheSecondDayAgainstTheCallOnly() {
        val up = DoubleArray(375) { 100.0 + it * 60.0 / 374 }
        val dn = DoubleArray(375) { 160.0 - it * 60.0 / 374 }
        val d2 = day.plusDays(1)
        val ms = session(day, up) + session(d2, dn)
        val call = TrendTurn.turns(Rule.T1, ms, true)
        assertTrue(call.isNotEmpty() && call.all { it.act.toLocalDate() == d2 })
        assertTrue(TrendTurn.turns(Rule.T1, session(day, up), false).isEmpty())
        // the rally's put side: an up-cross after a fall
        assertTrue(TrendTurn.turns(Rule.T1, session(day, dn) + session(d2, up), false).isNotEmpty())
    }

    @Test
    fun t4DonchianBreakAndT3Pairs() {
        val c = DoubleArray(375) { if (it < 60) 100.0 else 90.0 }
        val t4 = TrendTurn.turns(Rule.T4, session(day, c, 0.5), true)
        assertEquals(day.atTime(10, 20), t4.first().act)                  // bar 12 (10:15-10:19) acts at 10:20
        assertTrue(TrendTurn.turns(Rule.T4, session(day, c, 0.5), false).isEmpty())
        val c3 = DoubleArray(375) { if (it < 100) 100.0 else 95.0 }
        val t3 = TrendTurn.turns(Rule.T3, session(day, c3, 0.1), true)
        assertEquals(day.atTime(11, 5), t3.first().act)
        assertEquals(day.atTime(11, 0), t3.first().firstAct)
        val up = DoubleArray(375) { if (it < 100) 100.0 else 105.0 }
        assertTrue(TrendTurn.turns(Rule.T3, session(day, up, 0.1), false).isNotEmpty())
        val upB = DoubleArray(375) { if (it < 60) 100.0 else 110.0 }
        assertTrue(TrendTurn.turns(Rule.T4, session(day, upB, 0.5), false).isNotEmpty())
    }

    @Test
    fun t3NeverPairsAcrossSessions() {
        val a = DoubleArray(375) { if (it >= 370) 90.0 else 100.0 }
        val b = DoubleArray(375) { 80.0 }
        val t = TrendTurn.turns(Rule.T3, session(day, a, 0.1) + session(day.plusDays(1), b, 0.1), true)
        assertTrue(t.filter { it.act.toLocalDate() == day.plusDays(1) }.all { it.act.toLocalTime() > LocalTime.of(9, 20) })
    }

    @Test
    fun exitWaitsFiveMinutesAndT3NeedsBothBarsOpen() {
        val t = day.atTime(11, 5)
        val turns = listOf(TrendTurn.Turn(day.atTime(10, 40), null), TrendTurn.Turn(t, null))
        assertEquals(t, TrendTurn.exitTurn(Rule.T1, turns, day.atTime(11, 0))?.act)
        assertNull(TrendTurn.exitTurn(Rule.T1, turns, day.atTime(11, 1)))
        // The fill's minute counts, as the research's rows do: filled at 11:00:40, the 11:05 act is 5 minutes on.
        assertEquals(t, TrendTurn.exitTurn(Rule.T1, turns, day.atTime(11, 0, 40))?.act)
        val pair = listOf(TrendTurn.Turn(day.atTime(11, 10), day.atTime(11, 5)), TrendTurn.Turn(day.atTime(11, 15), day.atTime(11, 10)))
        assertEquals(day.atTime(11, 10), TrendTurn.exitTurn(Rule.T3, pair, day.atTime(11, 4))?.act)
        assertEquals(day.atTime(11, 15), TrendTurn.exitTurn(Rule.T3, pair, day.atTime(11, 6))?.act)
        assertNull(TrendTurn.exitTurn(Rule.T3, listOf(TrendTurn.Turn(day.atTime(11, 10), null)), day.atTime(11, 0)))
        assertNull(TrendTurn.exitTurn(Rule.T2, emptyList(), day.atTime(11, 0)))
    }

    @Test
    fun lookAheadGuard() {
        val rnd = java.util.Random(7)
        var px = 24000.0
        val ms = (0 until 3).flatMap { i -> session(day.plusDays(i.toLong()), DoubleArray(375) { px += rnd.nextGaussian() * 8; px }, 3.0) }
        val cut = day.plusDays(1).atTime(12, 0)
        for (rule in Rule.entries) for (call in listOf(true, false)) {
            val full = TrendTurn.turns(rule, ms, call).filter { !it.act.isAfter(cut) }
            val changed = ms.map { if (it.start.isBefore(cut)) it else it.copy(close = it.close * 1.05 + 50, high = it.high * 1.05 + 60, low = it.low * 1.05 + 40) }
            val deleted = ms.filter { it.start.isBefore(cut) }
            assertEquals(full, TrendTurn.turns(rule, changed, call).filter { !it.act.isAfter(cut) }, "${rule.key} changed")
            assertEquals(full, TrendTurn.turns(rule, deleted, call).filter { !it.act.isAfter(cut) }, "${rule.key} deleted")
        }
    }

    @Test
    fun gridsRulesAndNeeds() {
        assertEquals(LocalTime.of(9, 20), TrendTurn.Grid.NSE.time(5))
        assertEquals(5, TrendTurn.Grid.NSE.row(LocalTime.of(9, 20, 30)))
        assertEquals(LocalTime.of(9, 0), TrendTurn.Grid.MCX.open)
        assertEquals(Rule.T2, Rule.of("T2")); assertNull(Rule.of("X")); assertNull(Rule.of(null))
        assertEquals(listOf(2, 11, 2, 11), Rule.entries.map { TrendTurn.barsNeeded(it) })
        // the last 15-minute bar of an MCX-like grid that is not a whole number of bars
        val g = TrendTurn.Grid(LocalTime.of(9, 0), 22)
        val ms = (0 until 22).map { Bar(day.atTime(9, it), 10.0, 11.0, 9.0, 10.0) }
        val b = TrendTurn.bars(ms, 15, g)
        assertEquals(2, b.size); assertEquals(day.atTime(9, 22), b.last().act)
        assertFalse(TrendTurn.flags(Rule.T1, b.take(1)).first.any { it })
    }
}
