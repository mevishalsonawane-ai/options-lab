package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldTasTest {
    private val start = LocalDate.of(2026, 6, 1).atStartOfDay()

    /** 1-hour candles, each opening at the last close and moving by the given step, a dollar of wick each side. */
    private fun hours(steps: List<Double>): List<Bar> {
        var px = 2000.0
        return steps.mapIndexed { i, d -> val o = px; px += d; Bar(start.plusHours(i.toLong()), o, maxOf(o, px) + 1, minOf(o, px) - 1, px) }
    }

    private val fall = List(120) { if (it % 3 == 2) 2.0 else -4.0 }

    @Test fun tooFewCandlesDecideNothing() {
        assertNull(GoldTas.read(hours(List(GoldTas.MIN_BARS - 1) { 1.0 })))
    }

    @Test fun aFallIsDownAndARiseTurnsItUp() {
        val down = assertNotNull(GoldTas.read(hours(fall)))
        assertFalse(down.up); assertNull(down.turn); assertFalse(GoldTas.buys(down))
        assertTrue(down.line > hours(fall).last().close, "while down the line is above the price")
        val steps = fall + List(30) { if (it % 3 == 2) -2.0 else 6.0 }
        val b = hours(steps)
        val up = assertNotNull(GoldTas.read(b))
        assertTrue(up.up); val turn = assertNotNull(up.turn)
        assertTrue(up.line < b.last().close, "while up the line is under the price")
        val at = b.indexOfFirst { it.start == turn }
        assertEquals(b.size - 1 - at, up.sinceTurn)
        // the turn is the first candle of the up-run: the one before it was down
        assertFalse(GoldTas.read(b.subList(0, at))!!.up)
        assertTrue(GoldTas.read(b.subList(0, at + 1))!!.up)
        // the arrays and the read agree
        val (d, line) = GoldTas.tracker(b)
        assertEquals(1, d.last()); assertEquals(line.last(), up.line, 1e-9)
        assertEquals(GoldTas.score(b, d).last(), up.score!!, 1e-9)
    }

    @Test fun aStrongRiseScoresHighAndBuysSoonAfterTheTurn() {
        val steps = fall + List(8) { if (it % 3 == 2) -2.0 else 8.0 }
        val r = assertNotNull(GoldTas.read(hours(steps)))
        assertTrue(r.up)
        assertTrue(r.score!! >= GoldTas.MIN_SCORE, "score ${r.score}")
        assertTrue(GoldTas.buys(r))
    }

    @Test fun theBuyRule() {
        val t: LocalDateTime = start
        val ok = GoldTas.Read(t, up = true, line = 1990.0, score = 66.7, turn = t, sinceTurn = 3)
        assertTrue(GoldTas.buys(ok))
        assertTrue(GoldTas.buys(ok.copy(sinceTurn = GoldTas.LATE_BARS)))
        assertFalse(GoldTas.buys(ok.copy(sinceTurn = GoldTas.LATE_BARS + 1)), "too long after the turn")
        assertFalse(GoldTas.buys(ok.copy(score = 33.3)), "the score blocks it")
        assertFalse(GoldTas.buys(ok.copy(score = null)), "no score yet")
        assertFalse(GoldTas.buys(ok.copy(turn = null)), "up since the start of the history: no turn seen")
        assertFalse(GoldTas.buys(ok.copy(up = false)))
    }

    @Test fun noTargets() {
        assertTrue(GoldTas.TARGETS.isEmpty())
        assertTrue(GoldTas.targets(2000.0, 1990.0).isEmpty())
    }

    @Test fun weightedAverageNeedsAFullWindow() {
        val w = GoldTas.wma(doubleArrayOf(1.0, 2.0, 3.0, Double.NaN, 5.0, 6.0, 7.0), 3)
        assertTrue(w[1].isNaN()); assertEquals((1 + 4 + 9) / 6.0, w[2], 1e-12)
        assertTrue(w[3].isNaN()); assertTrue(w[5].isNaN()); assertEquals((5 + 12 + 21) / 6.0, w[6], 1e-12)
    }
}
