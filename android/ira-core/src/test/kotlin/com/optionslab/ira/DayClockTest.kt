package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayClockTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday

    /**
     * One whole session on [d]: 375 1-minute candles from 09:15, quiet but for wider swings in the first half hour, the
     * day's high made at minute [hiMin] and its low at [loMin] (minutes after 09:15).
     */
    private fun session(d: LocalDate, hiMin: Int, loMin: Int, minutes: Int = 375): List<Candle> = (0 until minutes).map { i ->
        val base = 24_000.0 + (if (i < 30) (if (i % 2 == 0) 20.0 else -20.0) else (if (i % 2 == 0) 2.0 else -2.0))
        val h = if (i == hiMin) 24_200.0 else base + 3
        val l = if (i == loMin) 23_800.0 else base - 3
        Candle(d.atTime(9, 15).plusMinutes(i.toLong()), base, h, l, base)
    }

    /** [count] weekdays before [today], oldest first. */
    private fun weekdays(count: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < count) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    /** 20 past sessions: 12 with the high in the first half hour and the low in the last hour, 8 the other way round at midday. */
    private val past: List<Candle> = weekdays(20).flatMapIndexed { k, d ->
        if (k < 12) session(d, hiMin = 5, loMin = 330) else session(d, hiMin = 150, loMin = 10)
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(DayClock.Ask.HIGH_LOW, DayClock.asked("When does Nifty usually make its high?"))
        assertEquals(DayClock.Ask.HIGH_LOW, DayClock.asked("what time does banknifty normally make the day's low"))
        assertEquals(DayClock.Ask.HIGH_LOW, DayClock.asked("nifty ka high kab banta hai"))
        assertEquals(DayClock.Ask.HIGH_LOW, DayClock.asked("usual time of the day's high"))
        assertEquals(DayClock.Ask.BY_NOW, DayClock.asked("Is the low of the day usually in by now?"))
        assertEquals(DayClock.Ask.BY_NOW, DayClock.asked("how often is the high already made by this time"))
        assertEquals(DayClock.Ask.BUSY, DayClock.asked("Which half hour moves the most?"))
        assertEquals(DayClock.Ask.BUSY, DayClock.asked("is the lunch hour usually quiet"))
        assertEquals(DayClock.Ask.BUSY, DayClock.asked("busiest time of the day for banknifty"))
        assertEquals(DayClock.Ask.ALL, DayClock.asked("Nifty's day clock"))
        // A forecast, advice, Boss's own book, gold, and today's own structure are not this.
        assertNull(DayClock.asked("when will nifty make its high today"))
        assertNull(DayClock.asked("when do I usually trade"))
        assertNull(DayClock.asked("when does gold usually make its high"))
        assertNull(DayClock.asked("where was today's high made"))
        assertNull(DayClock.asked("should I buy in the quietest hour"))
        assertNull(DayClock.asked("what is the 52 week high"))
    }

    @Test fun theMarket() {
        assertEquals(Market.NIFTY, DayClock.market(emptyList()))
        assertEquals(Market.BANKNIFTY, DayClock.market(listOf(Market.BANKNIFTY)))
        assertNull(DayClock.market(listOf(Market.GOLD)))
        assertNull(DayClock.market(listOf(Market.VIX)))
    }

    @Test fun wholeSessionsOnly() {
        val partial = session(today.minusDays(40), 5, 300, minutes = 200)
        val todays = session(today, 5, 100, minutes = 60)
        val days = DayClock.past(partial + past + todays, today)
        assertEquals(20, days.size)
        assertTrue(days.none { it.day == today })
        assertEquals(LocalTime.of(9, 20), days.first().highAt)
        assertEquals(LocalTime.of(14, 45), days.first().lowAt)
    }

    @Test fun whenTheHighAndLowCome() {
        val todays = session(today, hiMin = 2, loMin = 40, minutes = 120)
        val said = DayClock.answer(DayClock.Ask.HIGH_LOW, Market.NIFTY, past + todays, today, today.atTime(11, 15))
        assertTrue(said.contains("Over the last 20 whole Nifty sessions"), said)
        assertTrue(said.contains("the first half hour (09:15-09:45) 60% (12)"), said)
        assertTrue(said.contains("midday (11:00-13:00) 40% (8)"), said)
        assertTrue(said.contains("the last hour (14:30-15:30) 60% (12)"), said)
        assertTrue(said.contains("on 20 of 20 sessions (100%) the high or the low came in the first half hour or the last hour"), said)
        assertTrue(said.contains("Today Nifty's high so far is 24,200.00 at 09:17 and its low 23,800.00 at 09:55."), said)
        assertTrue(said.endsWith(DayClock.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
    }

    @Test fun byNow() {
        // At 12:00: every high from the first half hour (12) is in, the midday ones (11:45) too; lows: the 8 at 09:25.
        val said = DayClock.answer(DayClock.Ask.BY_NOW, Market.NIFTY, past, today, today.atTime(12, 0))
        assertTrue(said.contains("It's 12:00."), said)
        assertTrue(said.contains("the day's high had already been made by 12:00 on 20 (100%), the low on 8 (40%), and both on 8 (40%)"), said)
        // Closed: said honestly, with the record instead.
        val closed = DayClock.answer(DayClock.Ask.BY_NOW, Market.NIFTY, past, today, today.atTime(18, 0))
        assertTrue(closed.startsWith("The market is not open now"), closed)
        assertTrue(closed.contains("the day's high was made in"), closed)
    }

    @Test fun busiestHalfHour() {
        val todays = session(today, hiMin = 2, loMin = 40, minutes = 70)
        val said = DayClock.answer(DayClock.Ask.BUSY, Market.NIFTY, past + todays, today, today.atTime(10, 30))
        assertTrue(said.contains("the busiest half hour is 09:15-09:45"), said)
        assertTrue(said.contains("Around lunch (11:45-13:15)"), said)
        // Today's last whole half hour by 10:30 is 09:45-10:15 (10:15 still running), its low 23,800 at 09:55 making it wide.
        assertTrue(said.contains("Today 09:45-10:15 spanned"), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
    }

    @Test fun tooFewIsSaidHonestly() {
        val few = weekdays(4).flatMap { session(it, 5, 300) }
        val said = DayClock.answer(DayClock.Ask.ALL, Market.BANKNIFTY, few, today, today.atTime(11, 0))
        assertTrue(said.startsWith("I have only 4 whole BankNifty sessions on the phone"), said)
    }
}
