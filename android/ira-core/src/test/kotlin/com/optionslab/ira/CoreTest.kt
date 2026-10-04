package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val day = LocalDate.of(2026, 9, 29)          // a Tuesday

    @Test fun marketsAreFoundByTheirNames() {
        assertEquals(listOf(Market.BANKNIFTY), Market.mentioned("What is Bank Nifty doing?"))
        assertEquals(listOf(Market.NIFTY), Market.mentioned("how's nifty today"))
        assertEquals(setOf(Market.GOLD, Market.FINNIFTY), Market.mentioned("gold and finnifty").toSet())
        assertEquals(listOf(Market.VIX), Market.mentioned("is india vix high"))
        assertTrue(Market.mentioned("hello there").isEmpty())
        assertEquals("24,612.40", Market.NIFTY.price(24612.4))
        assertEquals("$4,214.70", Market.GOLD.price(4214.7))
    }

    @Test fun sessions() {
        assertTrue(Market.NIFTY.trading(day.atTime(9, 15)))
        assertFalse(Market.NIFTY.trading(day.atTime(15, 30)))
        assertFalse(Market.NIFTY.trading(day.atTime(9, 14)))
        assertFalse(Market.NIFTY.trading(LocalDate.of(2026, 10, 3).atTime(11, 0)))   // Saturday
        assertTrue(Market.GOLD.trading(day.atTime(3, 0)))
        assertFalse(Market.GOLD.trading(day.atTime(21, 30)))
        assertFalse(Market.GOLD.trading(LocalDate.of(2026, 10, 2).atTime(22, 0)))    // Friday night
    }

    @Test fun indexCandlesCountFrom0915() {
        val mins = Fixtures.indexDays(1, day)
        val c15 = Candles.fold(mins, 15, Market.NIFTY)
        assertEquals(day.atTime(9, 15), c15.first().t)
        assertEquals(day.atTime(9, 30), c15[1].t)
        assertEquals(25, c15.size)                                   // 09:15 .. 15:15
        assertEquals(mins.take(15).maxOf { it.h }, c15[0].h)
        assertEquals(mins[14].c, c15[0].c)
        val c60 = Candles.fold(mins, 60, Market.NIFTY)
        assertEquals(day.atTime(10, 15), c60[1].t)
        assertEquals(c15.size, Candles.closed(c15, 15, day.atTime(15, 30)).size)
        assertEquals(24, Candles.closed(c15, 15, day.atTime(15, 29)).size)
        assertEquals(mins, Candles.fold(mins, 1, Market.NIFTY))
        val gold = Candles.fold(listOf(Fixtures.c(day.atTime(10, 59), 1.0, 2.0, 0.5, 1.5), Fixtures.c(day.atTime(11, 1), 1.5, 3.0, 1.0, 2.0)), 60, Market.GOLD)
        assertEquals(listOf(day.atTime(10, 0), day.atTime(11, 0)), gold.map { it.t })
    }

    @Test fun trackerFollowsTheMove() {
        val up = Fixtures.bars(day.atTime(9, 15), 15, List(40) { (100.0 + it * 2) to (102.0 + it * 2) })
        val (d1, l1) = Candles.tracker(up)
        assertEquals(1, d1.last()); assertTrue(l1.last() < up.last().c)
        val down = Fixtures.bars(day.atTime(9, 15), 15, List(40) { (200.0 - it * 2) to (198.0 - it * 2) })
        val (d2, l2) = Candles.tracker(down)
        assertEquals(-1, d2.last()); assertTrue(l2.last() > down.last().c)
        val a = Candles.atr(up, 14)
        assertEquals(up[0].range, a[0], 1e-9); assertTrue(a.last() > 0)
        assertEquals(0.5, Candles.percentile(listOf(1.0, 2.0, 3.0, 4.0), 2.0), 1e-9)
        assertEquals(0.5, Candles.percentile(emptyList(), 2.0), 1e-9)
    }

    @Test fun swingPoints() {
        val oc = listOf(1.0, 2.0, 3.0, 6.0, 3.0, 2.0, 1.0, 0.0, -3.0, 0.0, 1.0, 2.0).map { it to it }
        val c = Fixtures.bars(day.atTime(9, 15), 15, oc, wick = 0.1)
        val (hi, lo) = Candles.swings(c, 3)
        assertEquals(listOf(3), hi); assertEquals(listOf(8), lo)
    }
}
