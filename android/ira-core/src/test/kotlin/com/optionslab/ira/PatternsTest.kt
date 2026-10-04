package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatternsTest {
    private val t0 = LocalDate.of(2026, 9, 29).atTime(9, 15)
    private fun c(i: Int, o: Double, h: Double, l: Double, cl: Double) = Candle(t0.plusMinutes(15L * i), o, h, l, cl)
    /** 21 quiet candles (bodies of 2) to sit behind the tested ones. */
    private val calm = List(21) { c(it, 100.0, 102.0, 98.0, if (it % 2 == 0) 101.0 else 99.0) }
    private fun after(vararg x: Candle) = calm + x.mapIndexed { i, k -> k.copy(t = t0.plusMinutes(15L * (calm.size + i))) }
    private fun last(cs: List<Candle>) = Patterns.at(cs, cs.size - 1)

    @Test fun engulfing() {
        assertTrue(PatternKind.BULLISH_ENGULFING in last(after(c(0, 100.0, 100.5, 97.5, 98.0), c(0, 97.8, 101.5, 97.5, 101.0))))
        assertTrue(PatternKind.BEARISH_ENGULFING in last(after(c(0, 98.0, 100.5, 97.5, 100.0), c(0, 100.2, 100.5, 96.5, 97.0))))
    }

    @Test fun hammerStarDoji() {
        assertTrue(PatternKind.HAMMER in last(after(c(0, 99.0, 100.2, 95.0, 100.0))))
        assertTrue(PatternKind.SHOOTING_STAR in last(after(c(0, 100.0, 105.0, 98.8, 99.0))))
        assertTrue(PatternKind.DOJI in last(after(c(0, 100.0, 102.0, 98.0, 100.1))))
    }

    @Test fun insideBarAndRuns() {
        assertTrue(PatternKind.INSIDE_BAR in last(after(c(0, 99.0, 104.0, 96.0, 101.0), c(0, 100.0, 102.0, 98.0, 101.0))))
        assertTrue(PatternKind.THREE_WHITE_SOLDIERS in last(after(c(0, 100.0, 102.5, 99.5, 102.0), c(0, 102.0, 104.5, 101.5, 104.0), c(0, 104.0, 106.5, 103.5, 106.0))))
        assertTrue(PatternKind.THREE_BLACK_CROWS in last(after(c(0, 100.0, 100.5, 97.5, 98.0), c(0, 98.0, 98.5, 95.5, 96.0), c(0, 96.0, 96.5, 93.5, 94.0))))
    }

    @Test fun breakouts() {
        assertTrue(PatternKind.BREAKOUT_UP in last(after(c(0, 101.0, 104.0, 100.5, 103.5))))
        assertTrue(PatternKind.BREAKOUT_DOWN in last(after(c(0, 99.0, 99.5, 96.0, 96.5))))
    }

    @Test fun doubleTopAndBottom() {
        // two highs at 110 five candles apart, a trough at 104, then a close under it
        val path = listOf(100.0, 103.0, 106.0, 110.0, 107.0, 104.0, 106.0, 108.0, 110.0, 107.0, 105.0, 104.5, 103.0)
        val top = path.mapIndexed { i, v -> c(i, v, v + 0.2, v - 0.2, v) }
        val pad = List(20) { c(it, 100.0, 100.2, 99.8, 100.0) }
        val seq = (pad + top).mapIndexed { i, k -> k.copy(t = t0.plusMinutes(15L * i)) }
        assertTrue(PatternKind.DOUBLE_TOP in Patterns.at(seq, seq.size - 1), Patterns.at(seq, seq.size - 1).toString())
        val bottom = path.map { 210.0 - it }.mapIndexed { i, v -> c(i, v, v + 0.2, v - 0.2, v) }
        val seq2 = (pad.map { it.copy(o = 110.0, h = 110.2, l = 109.8, c = 110.0) } + bottom).mapIndexed { i, k -> k.copy(t = t0.plusMinutes(15L * i)) }
        assertTrue(PatternKind.DOUBLE_BOTTOM in Patterns.at(seq2, seq2.size - 1), Patterns.at(seq2, seq2.size - 1).toString())
    }

    @Test fun edgesAndScan() {
        assertTrue(Patterns.at(calm, 0).isEmpty())
        assertTrue(Patterns.at(calm, 99).isEmpty())
        val s = Patterns.scan(after(c(0, 101.0, 104.0, 100.5, 103.5)), 15)
        assertTrue(s.any { it.kind == PatternKind.BREAKOUT_UP && it.minutes == 15 })
    }

    @Test fun theBookLearnsEachCandleOnce() {
        val days = Fixtures.indexDays(30, seed = 3)
        val c15 = Candles.fold(days, 15, Market.NIFTY)
        val b = PatternBook()
        val n1 = b.learn(Market.NIFTY, 15, c15.take(400))
        assertTrue(n1 > 0)
        val total1 = b.size
        assertEquals(0, b.learn(Market.NIFTY, 15, c15.take(400)), "the same candles teach nothing new")
        val n2 = b.learn(Market.NIFTY, 15, c15)
        assertTrue(n2 > 0); assertEquals(total1 + n2, b.size)
        val again = PatternBook.load(b.save())
        assertEquals(b.size, again.size)
        assertEquals(b.learnedUntil(Market.NIFTY, 15), again.learnedUntil(Market.NIFTY, 15))
        val k = PatternKind.entries.first { b.stat(Market.NIFTY, 15, it).seen > 0 }
        assertEquals(b.stat(Market.NIFTY, 15, k), again.stat(Market.NIFTY, 15, k))
        val st = b.stat(Market.NIFTY, 15, k)
        assertTrue(st.rate in 0.0..1.0)
        assertEquals(0.0, PatternBook.Stat().rate); assertEquals(0.0, PatternBook.Stat().avgMovePct)
        assertEquals(0, PatternBook.load("junk\nS|bad\n").size)
    }

    @Test fun eachSessionIsScoredTheWayTheBookLearns() {
        val c15 = Candles.fold(Fixtures.indexDays(30, seed = 11), 15, Market.BANKNIFTY)
        val b = PatternBook()
        b.learn(Market.BANKNIFTY, 15, c15)
        val days = c15.map { it.t.toLocalDate() }.distinct()
        val all = days.map { b.score(c15, it) }.reduce { x, y -> x + y }
        assertEquals(b.size, all.seen, "every pattern outcome learned is scored on its own day")
        assertEquals(b.entries().sumOf { it.stat.worked }, all.worked)
        assertEquals(b.size, b.entries().sumOf { it.stat.seen })
        assertTrue(all.rate in 0.0..1.0); assertEquals(0.0, PatternBook.Score(0, 0).rate)
        assertEquals(PatternBook.Score(0, 0), b.score(c15, days.first().minusDays(1)))
        val best = b.best(n = 2, min = 5)
        assertTrue(best.size <= 2 && best.all { it.stat.seen >= 5 && it.market == Market.BANKNIFTY && it.minutes == 15 })
        assertTrue(best.zipWithNext().all { (x, y) -> x.stat.rate >= y.stat.rate })
        assertTrue(b.best(min = 100_000).isEmpty())
    }
}
