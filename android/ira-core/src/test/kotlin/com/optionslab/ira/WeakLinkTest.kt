package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeakLinkTest {
    private val start = LocalDate.of(2026, 8, 3)

    private fun at(d: LocalDate, h: Int, m: Int) = LocalDateTime.of(d, LocalTime.of(h, m))

    /** A day's candles from 09:15 to 15:29, opening at [open] and closing at [close]. */
    private fun session(d: LocalDate, open: Double, close: Double): List<Candle> = (0 until 375).map { i ->
        val t = at(d, 9, 15).plusMinutes(i.toLong()); val c = if (i == 0) open else close; Candle(t, c, c, c, c)
    }

    /**
     * 30 trading days: every third day gaps 1% up and its ORB trade is stopped 3 minutes after entry (-Rs 1,200); the
     * rest open flat and the trade goes out at the target (+Rs 600). Range Fade trades the flat days too, at 11:30.
     */
    private fun data(): Pair<List<WeakLink.Trade>, List<Candle>> {
        val bars = ArrayList<Candle>(); val ts = ArrayList<WeakLink.Trade>()
        var close = 50_000.0
        var d = start
        var i = 0
        while (i < 30) {
            if (d.dayOfWeek.value <= 5) {
                val gap = i % 3 == 0
                val open = if (gap) close * 1.01 else close
                bars += session(d, open, open)
                close = open
                if (gap) ts += WeakLink.Trade("ORB", at(d, 10, 10), at(d, 10, 13), "stop", -1_200.0)
                else {
                    ts += WeakLink.Trade("ORB", at(d, 10, 10), at(d, 10, 40), "target", 600.0)
                    if (i % 2 == 0) ts += WeakLink.Trade("Range Fade", at(d, 11, 30), at(d, 12, 0), "session_end", 100.0)
                }
                i++
            }
            d = d.plusDays(1)
        }
        return ts to bars
    }

    @Test fun asked() {
        listOf("what's the weakest link in my setup", "what is the weakest link in my trades", "my weak spots",
            "what usually goes wrong in my paper trades", "what keeps going wrong with my bots", "what goes wrong most often in my trades",
            "where do my trades go wrong", "where do my bots usually go wrong", "what do my losing trades have in common",
            "mere trades mein sabse kamzor kadi kya hai", "mere bots mein aksar kya galat hota hai")
            .forEach { assertTrue(WeakLink.asked(it), it) }
        listOf("what went wrong with my bots today", "why did orb lose today", "where are you weakest", "what's the weakest index",
            "should i switch off orb", "what went wrong with that trade", "what goes wrong with my trades today", "explain my bots trades today")
            .forEach { assertFalse(WeakLink.asked(it), it) }
    }

    @Test fun gaps() {
        val d1 = LocalDate.of(2026, 9, 1); val d2 = LocalDate.of(2026, 9, 2)
        val g = WeakLink.gaps(session(d1, 100.0, 100.0) + session(d2, 101.0, 101.0))
        assertEquals(1, g.size)
        assertEquals(1.0, g.getValue(d2), 1e-9)
    }

    @Test fun ranksTheGapDaysAndCountsTheExits() {
        val (ts, bars) = data()
        val a = WeakLink.answer(ts, bars)
        assertTrue(a.contains("last 40 closed paper trades"), a)
        assertTrue(a.contains("at the stop"), a)
        assertTrue(a.contains("came within 5 minutes of entry"), a)
        assertTrue(a.contains("1. Entries on days BankNifty gapped 0.75% or more either way"), a)
        assertTrue(a.contains("Arithmetic, not a forecast"), a)
        assertTrue(a.endsWith(WeakLink.NOTE), a)
        assertFalse(a.contains("should"), a)
    }

    @Test fun tooFew() {
        val (ts, bars) = data()
        val a = WeakLink.answer(ts.take(6), bars)
        assertTrue(a.contains("only 6 trades closed on paper"), a)
    }

    @Test fun smallSlicesAreCountedNotRanked() {
        val d = start
        val ts = (0 until 12).map { WeakLink.Trade("ORB", at(d.plusDays(it.toLong()), 10, 10), at(d.plusDays(it.toLong()), 10, 30), "target", 500.0) } +
            WeakLink.Trade("ORB", at(d.plusDays(20), 9, 16), at(d.plusDays(20), 9, 18), "stop", -900.0)
        val a = WeakLink.answer(ts, emptyList())
        assertTrue(a.contains("Too few to rank: entries in the first 5 minutes of the session (09:15 to 09:20) (1 trade)"), a)
        assertTrue(a.contains("13 trades have no BankNifty candles"), a)
    }
}
