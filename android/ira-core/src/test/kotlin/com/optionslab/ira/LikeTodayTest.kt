package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LikeTodayTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday

    private fun weekdays(count: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < count) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    /**
     * A session opening at [open], ranging [range] in its 09:30 bar, holding [open] to 10:15 and [close] from then;
     * [minutes] long from 09:15.
     */
    private fun session(d: LocalDate, open: Double, close: Double, range: Double, minutes: Int = 375): List<Candle> =
        (0 until minutes).map { i ->
            val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong())
            val c = if (t.toLocalTime().isBefore(LocalTime.of(10, 15))) open else close
            if (t.toLocalTime() == LocalTime.of(9, 30)) Candle(t, c, c + range / 2, c - range / 2, c) else Candle(t, c, c, c, c)
        }

    private fun vix(d: LocalDate, level: Double) = (0 until 375).map { i ->
        val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(i.toLong()); Candle(t, level, level, level, level) }

    /**
     * 30 weekdays closing at 24,000. Every third day (i % 3 == 0) opens 0.5% up (24,120) ranging 120 (0.5%) with VIX at 14,
     * and closes back at 24,000 after 10:15; the rest open flat ranging 240 with VIX at 18 and close flat.
     * Today opens 0.5% up, ranges 120 and VIX is 14.
     */
    private fun data(todayMinutes: Int = 375): Pair<List<Candle>, List<Candle>> {
        val bars = ArrayList<Candle>(); val vx = ArrayList<Candle>()
        weekdays(30).forEachIndexed { i, d ->
            if (i % 3 == 0) { bars += session(d, 24120.0, 24000.0, 120.0); vx += vix(d, 14.0)
            } else { bars += session(d, 24000.0, 24000.0, 240.0); vx += vix(d, 18.0) }
        }
        bars += session(today, 24120.0, 24200.0, 120.0, todayMinutes); vx += vix(today, 14.0)
        return bars to vx
    }

    @Test fun findsTheLikeDaysAndSaysHowTheyEnded() {
        val (bars, vx) = data()
        val (t, past) = LikeToday.days(bars, vx, today, LikeToday.FIRST_HOUR)
        assertNotNull(t)
        assertEquals(0.5, t.gapPct, 1e-9)
        assertEquals(0.5, t.rangePct, 0.01)
        assertEquals(14.0, t.vix)
        assertEquals(29, past.size)   // the oldest has no session before it
        val hit = past.filter { LikeToday.matches(it, t) }
        assertEquals(9, hit.size)
        assertTrue(hit.all { it.vix == 14.0 })
        val said = LikeToday.answer(Market.NIFTY, bars, vx, today, LocalDateTime.of(today, LocalTime.of(18, 0)))
        assertTrue("9 matched" in said, said)
        assertTrue("the first hour (09:15 to 10:15)" in said, said)
        assertTrue("Today itself went +0.33% from 10:15 to the close." in said, said)
        assertTrue(said.endsWith(LikeToday.NOTE), said)
        assertTrue("Boss" in said)
    }

    @Test fun whileTheFirstHourTradesThePastIsCutAtTheSameMinute() {
        val (bars, vx) = data(todayMinutes = 30)   // to 09:44
        val said = LikeToday.answer(Market.NIFTY, bars, vx, today, LocalDateTime.of(today, LocalTime.of(9, 45)))
        assertTrue("09:15 to 09:45" in said, said)
        assertTrue("9 matched" in said, said)
        val early = LikeToday.answer(Market.NIFTY, bars, vx, today, LocalDateTime.of(today, LocalTime.of(9, 20)))
        assertTrue("from 09:30" in early, early)
    }

    @Test fun tooFewSessionsIsSaid() {
        val (bars, vx) = data()
        val few = bars.filter { it.t.toLocalDate() >= weekdays(30)[25] }
        val said = LikeToday.answer(Market.NIFTY, few, vx, today, LocalDateTime.of(today, LocalTime.of(18, 0)))
        assertTrue("too few" in said, said)
    }

    @Test fun asked() {
        for (s in listOf("is today like any past day", "has there been a day like today", "which past days started like today",
            "how did days like today end", "similar days to today for banknifty", "aaj jaisa din pehle kab tha",
            "show me days like today", "were there sessions similar to today", "is today similar to any earlier day",
            "find past days like this"))
            assertTrue(LikeToday.asked(s), s)
        for (s in listOf("is today like yesterday", "will today end like days like today", "days like today should i buy",
            "a gap like today", "which strategy suits days like today", "what is nifty at", "is today a trend day",
            "how did my bots do on days like today", "is gold like any past day"))
            assertFalse(LikeToday.asked(s), s)
        assertEquals(Market.BANKNIFTY, LikeToday.market(listOf(Market.BANKNIFTY)))
        assertEquals(null, LikeToday.market(listOf(Market.GOLD)))
    }
}
