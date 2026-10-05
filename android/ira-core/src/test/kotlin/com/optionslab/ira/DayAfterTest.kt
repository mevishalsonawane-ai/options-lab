package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayAfterTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    /** One session of 1-minute candles from [from] to [until]: a straight walk from [open] to [close] at 15:29. */
    private fun session(d: LocalDate, open: Double, close: Double, until: LocalTime = LocalTime.of(15, 29), from: LocalTime = LocalTime.of(9, 15)): List<Candle> {
        val first = LocalTime.of(9, 15).toSecondOfDay(); val last = LocalTime.of(15, 29).toSecondOfDay()
        fun px(t: LocalTime) = open + (close - open) * (t.toSecondOfDay() - first).toDouble() / (last - first)
        val out = ArrayList<Candle>()
        var t = from
        while (!t.isAfter(until)) {
            val o = if (t == LocalTime.of(9, 15)) open else px(t.minusMinutes(1))
            val c = px(t)
            out += Candle(d.atTime(t), o, maxOf(o, c), minOf(o, c), c)
            t = t.plusMinutes(1)
        }
        return out
    }

    /** The [n] weekdays before today, oldest first. */
    private fun weekdays(n: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < n) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d; d = d.minusDays(1) }
        return out.reversed()
    }

    /** A flat first day, then [n] days by turns of +1.2%, -0.3%, -1.5%, +0.5% close to close, each opening at the close before. */
    private val MOVES = listOf(1.2, -0.3, -1.5, 0.5)
    private fun nifty(n: Int): List<Candle> {
        val ds = weekdays(n + 1)
        val out = ArrayList<Candle>()
        out += session(ds[0], 25000.0, 25000.0)
        var pc = 25000.0
        for (i in 1..n) {
            val close = pc * (1 + MOVES[(i - 1) % 4] / 100)
            out += session(ds[i], pc, close)
            pc = close
        }
        return out
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move|bounce)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(DayAfter.Q(-1, 1.0), DayAfter.asked("after nifty falls 1% in a day what happens the next day"))
        assertEquals(DayAfter.Q(-1, 1.0), DayAfter.asked("does banknifty bounce the day after a big down day"))
        assertEquals(DayAfter.Q(1, 2.0), DayAfter.asked("after a 2% up day does nifty follow through the next session"))
        assertEquals(DayAfter.Q(null, 1.0), DayAfter.asked("big day follow through record"))
        assertEquals(DayAfter.Q(-1, 1.0), DayAfter.asked("1% girne ke baad agle din nifty kya karta hai"))
        assertEquals(DayAfter.Q(1, 1.5), DayAfter.asked("how does sensex usually do the day after a 1.5% rally"))
        assertEquals(DayAfter.Q(-1, 1.0), DayAfter.asked("does nifty recover the day after a 1% fall"))
        // Both ways said: the big day's is the one with the size or "big", or after "after" / before "ke baad".
        assertEquals(DayAfter.Q(-1, 1.0), DayAfter.asked("does nifty rally the day after a 1% drop"))
        assertEquals(DayAfter.Q(1, 1.0), DayAfter.asked("does nifty fall the day after a big rally"))
        assertEquals(DayAfter.Q(-1, 2.0), DayAfter.asked("after nifty falls 2% does it rally the next day"))
        assertEquals(DayAfter.Q(1, 1.0), DayAfter.asked("does banknifty drop the next day after a big up day"))
        assertEquals(DayAfter.Q(-1, 1.0), DayAfter.asked("bade girne ke baad agle din nifty aksar uchalta hai"))
        // A size outside 0.3 to 5%: kept as asked, the 1% days counted.
        assertEquals(DayAfter.Q(-1, 1.0, 7.0), DayAfter.asked("does nifty bounce the day after a 7% fall"))
        assertEquals(DayAfter.Q(1, 1.0, 0.1), DayAfter.asked("does nifty follow through the day after a 0.1% rise"))
        assertEquals(DayAfter.Q(1, 5.0), DayAfter.asked("does nifty follow through the day after a 5% rise"))
        // Today, yesterday, a forecast, advice, Boss's own book, a what-if, the last time, a run of closes, the intraday
        // comeback, a gap, a close at the low, VIX, gold and options are others'.
        for (q in listOf("what happens after today's 1% fall", "will nifty bounce tomorrow after a big fall",
                "should i buy the day after a 2% fall", "what if nifty falls 2% the next day", "how did nifty do the day after yesterday's fall",
                "when did nifty last fall 1% and what happened the next day", "after 3 down days in a row how does nifty do the next day",
                "how often does nifty recover a 1% fall during the day", "what usually happens the next day after a gap up",
                "what happens the day after nifty closes at the low", "after a vix spike how much does nifty move the next day",
                "how much does gold move the day after a big fall", "how do nifty puts do the day after a big fall",
                "how often does nifty fall 1% in a day", "what happens the next day", "is nifty down 1% today"))
            assertNull(DayAfter.asked(q), q)
        // The intraday comeback never takes the day after now.
        assertNull(Comebacks.asked("does nifty recover the day after a 1% fall"))
    }

    @Test fun theIndex() {
        assertEquals(Market.NIFTY, DayAfter.market(emptyList()))
        assertEquals(Market.BANKNIFTY, DayAfter.market(listOf(Market.BANKNIFTY)))
        assertNull(DayAfter.market(listOf(Market.GOLD)))
        assertNull(DayAfter.market(listOf(Market.VIX)))
    }

    @Test fun theRecord() {
        val days = DayAfter.past(nifty(40), today)
        assertEquals(39, days.size)
        val up = DayAfter.side(days, 1, 1.0)!!
        assertEquals(10, up.count); assertEquals(0, up.openedSame); assertEquals(0, up.closedSame); assertEquals(10, up.closedOther)
        assertEquals(0, up.beyondExtreme); assertEquals(0, up.undone)
        assertEquals(-0.3, up.medianNext, 1e-6)
        assertEquals(19, up.baseSame); assertEquals(39, up.baseCount)
        val down = DayAfter.side(days, -1, 1.0)!!
        assertEquals(10, down.count); assertEquals(0, down.closedSame); assertEquals(10, down.closedOther)
        assertEquals(-0.5, down.medianNext, 1e-6)   // measured the big day's way: +0.5% after a fall
        assertEquals(20, down.baseSame)
        assertNull(DayAfter.side(days, 1, 1.3))

        val said = DayAfter.answer(DayAfter.Q(null), Market.NIFTY, nifty(40), today, today.atTime(16, 0))
        assertTrue(said.startsWith("Over the last 39 whole sessions of Nifty on this phone"), said)
        assertTrue(said.contains("a big day being a close 1% or more from the close before"), said)
        assertTrue(said.contains("After the 10 big down days, the next session opened down on 0 (0%), closed down again on 0 (0%) and up on 10;"), said)
        assertTrue(said.contains("After the 10 big up days, the next session opened up on 0 (0%), closed up again on 0 (0%) and down on 10;"), said)
        assertTrue(said.contains("The median next-day move was +0.50%"), said)
        assertTrue(said.contains("After any whole session, Nifty closed up the next day on 49%"), said)
        assertTrue(said.contains("only 10 days"), said)
        assertTrue(said.endsWith(DayAfter.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
        assertFalse(said.contains("Today"), said)
        // One side asked; a size with too few days said plainly.
        val dn = DayAfter.answer(DayAfter.Q(-1), Market.NIFTY, nifty(40), today, today.atTime(16, 0))
        assertFalse(dn.contains("big up days"), dn)
        val few = DayAfter.answer(DayAfter.Q(1, 1.3), Market.NIFTY, nifty(40), today, today.atTime(16, 0))
        assertTrue(few.contains("Only 0 big up days of 1.3% or more - too few"), few)
    }

    @Test fun todayEndedOnlyForAWholeSession() {
        val past = nifty(40)
        val pc = past.last().c
        val live = DayAfter.answer(DayAfter.Q(-1), Market.NIFTY, past + session(today, pc, pc * 0.988, LocalTime.of(11, 0)), today, today.atTime(11, 0))
        assertTrue(Regex("Today Nifty is -\\d\\.\\d\\d% on \\d+ \\w+'s close so far").containsMatchIn(live), live)
        val whole = DayAfter.answer(DayAfter.Q(-1), Market.NIFTY, past + session(today, pc, pc * 0.988), today, today.atTime(16, 0))
        assertTrue(Regex("Today Nifty ended -1\\.20% on \\d+ \\w+'s close, a move of the size asked\\.").containsMatchIn(whole), whole)
        val cut = DayAfter.answer(DayAfter.Q(-1), Market.NIFTY, past + session(today, pc, pc * 0.988, LocalTime.of(11, 0)), today, today.atTime(16, 0))
        assertTrue(Regex("Today Nifty was -\\d\\.\\d\\d% on \\d+ \\w+'s close at 11:00").containsMatchIn(cut), cut)
        assertFalse(cut.contains("Today Nifty ended"), cut)
        // A fall today is not "of the size asked" when the big up days were asked.
        val upAsked = DayAfter.answer(DayAfter.Q(1), Market.NIFTY, past + session(today, pc, pc * 0.988), today, today.atTime(16, 0))
        assertTrue(Regex("Today Nifty ended -1\\.20% on \\d+ \\w+'s close\\.").containsMatchIn(upAsked), upAsked)
        assertFalse(upAsked.contains("a move of the size asked"), upAsked)
        val both = DayAfter.answer(DayAfter.Q(null), Market.NIFTY, past + session(today, pc, pc * 0.988), today, today.atTime(16, 0))
        assertTrue(both.contains("a move of the size asked"), both)
    }

    @Test fun aSizeOutsideTheRangeIsSaid() {
        val said = DayAfter.answer(DayAfter.Q(-1, 1.0, 7.0), Market.NIFTY, nifty(40), today, today.atTime(16, 0))
        assertTrue(said.startsWith("I count big days of 0.3 to 5% only, Boss, so here are the 1% days. Over the last 39"), said)
        assertFalse(DayAfter.answer(DayAfter.Q(-1), Market.NIFTY, nifty(40), today, today.atTime(16, 0)).contains("I count big days"))
    }

    @Test fun aMissingSessionIsNeverBridged() {
        val ds = weekdays(41)
        val gap = (5 until 35).first { ds[it].dayOfWeek == DayOfWeek.WEDNESDAY }
        val bars = nifty(40).filter { it.t.toLocalDate() != ds[gap] }
        val days = DayAfter.past(bars, today)
        assertEquals(36, days.size)
        assertTrue(days.none { it.day == ds[gap - 1] || it.day == ds[gap + 1] })
        // A holiday on the exchange calendar is no missing session: Tuesday's next is Thursday.
        val held = DayAfter.past(bars, today) { it.dayOfWeek.value <= 5 && it != ds[gap] }
        assertEquals(38, held.size)
        assertEquals(ds[gap + 1], held.single { it.day == ds[gap - 1] }.next)
        // Yesterday missing: today is not set against Monday's close.
        val past = nifty(40).filter { it.t.toLocalDate() != today.minusDays(1) }
        val pc = past.last().c
        assertFalse(DayAfter.answer(DayAfter.Q(null), Market.NIFTY, past + session(today, pc, pc * 1.01, LocalTime.of(11, 0)), today, today.atTime(11, 0)).contains("Today"))
    }

    @Test fun tooFewSessionsAndHalfDays() {
        val few = DayAfter.answer(DayAfter.Q(null), Market.NIFTY, nifty(10), today, today.atTime(16, 0))
        assertTrue(few.startsWith("I have only 9 whole sessions of Nifty with a whole one on each side on the phone, Boss"), few)
        // A half day is not whole: neither it nor the days on either side of it are counted.
        val ds = weekdays(41)
        val half = ds[20]
        val bars = nifty(40).filter { it.t.toLocalDate() != half || it.t.toLocalTime().isBefore(LocalTime.of(13, 0)) }
        val days = DayAfter.past(bars, today)
        assertEquals(36, days.size)
        assertTrue(days.none { it.day == half || it.day == ds[19] || it.day == ds[21] })
    }
}
