package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonthTurnsTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday: 1 and 2 Oct before it

    /** Weekdays from [from] to [to], oldest first. */
    private fun weekdays(from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = from
        while (!d.isAfter(to)) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d
            d = d.plusDays(1)
        }
        return out
    }

    /** Whole sessions on [days] (two candles each, 09:15 and 15:29), each day's close [step] times the one before. */
    private fun build(days: List<LocalDate>, step: (LocalDate, Int) -> Double, todayUntil: Int = 29): List<Candle> {
        val out = ArrayList<Candle>()
        var px = 25000.0
        for ((i, d) in days.withIndex()) {
            val open = px
            val close = px * step(d, i)
            out += Candle(d.atTime(9, 15), open, maxOf(open, close) + 10, minOf(open, close) - 10, open)
            out += Candle(d.atTime(if (d == today) 11 else 15, todayUntil), close, maxOf(open, close) + 10, minOf(open, close) - 10, close)
            px = close
        }
        return out
    }

    /** The month's first three weekdays up 1%, the rest down 0.1%. */
    private fun startUp(d: LocalDate, @Suppress("UNUSED_PARAMETER") i: Int): Double {
        val firsts = weekdays(d.withDayOfMonth(1), d.withDayOfMonth(minOf(7, d.lengthOfMonth()))).take(3)
        return if (d in firsts) 1.01 else 0.999
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(MonthTurns.Q(MonthTurns.Part.START), MonthTurns.asked("How does Nifty do at the start of the month?"))
        assertEquals(MonthTurns.Q(null), MonthTurns.asked("Is there a turn of the month effect?"))
        assertEquals(MonthTurns.Q(MonthTurns.Part.END), MonthTurns.asked("How are the last few days of the month for BankNifty?"))
        assertEquals(MonthTurns.Q(MonthTurns.Part.END), MonthTurns.asked("month end record"))
        assertEquals(MonthTurns.Q(MonthTurns.Part.START), MonthTurns.asked("mahine ki shuruaat mein nifty kaisa chalta hai"))
        assertEquals(MonthTurns.Q(MonthTurns.Part.START), MonthTurns.asked("does nifty rally in the first 3 trading days of the month"))
        assertEquals(MonthTurns.Q(null), MonthTurns.asked("month start vs month end record for sensex"))
        assertEquals(MonthTurns.Q(MonthTurns.Part.END), MonthTurns.asked("mahine ke aakhri dino mein banknifty kaisa rehta hai"))
        // Not this record: a forecast, advice, his month, one month's move, the expiry, today, a meaning, gold.
        assertNull(MonthTurns.asked("will nifty rise at the start of the month"))
        assertNull(MonthTurns.asked("should I buy at the start of the month"))
        assertNull(MonthTurns.asked("month end review"))
        assertNull(MonthTurns.asked("how did nifty do this month"))
        assertNull(MonthTurns.asked("how did nifty do last month"))
        assertNull(MonthTurns.asked("how does nifty move on month end expiry"))
        assertNull(MonthTurns.asked("is today the start of the month"))
        assertNull(MonthTurns.asked("what does turn of the month mean"))
        assertNull(MonthTurns.asked("how does gold do at the start of the month"))
        assertNull(MonthTurns.asked("how does nifty do on mondays"))
        assertNull(MonthTurns.asked("start of the month"))
    }

    @Test fun theMarket() {
        assertEquals(Market.NIFTY, MonthTurns.market(emptyList()))
        assertEquals(Market.BANKNIFTY, MonthTurns.market(listOf(Market.BANKNIFTY)))
        assertNull(MonthTurns.market(listOf(Market.GOLD)))
        assertNull(MonthTurns.market(listOf(Market.VIX)))
    }

    @Test fun tooFewSaysSoPlainly() {
        val bars = build(weekdays(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), ::startUp)
        val said = MonthTurns.answer(MonthTurns.Q(null), Market.NIFTY, bars, today, today.atTime(12, 0))
        assertTrue(said.contains("too few"), said)
        assertTrue(said.contains("I need ${MonthTurns.MIN_SESSIONS}"), said)
    }

    @Test fun placesOnlyKnownEdges() {
        val days = weekdays(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 10, 2))
        val past = MonthTurns.past(build(days, ::startUp), today)
        // June's start is not on the phone; July, August, September and October's are.
        assertTrue(past.none { it.start != null && it.day.monthValue == 6 })
        assertEquals(listOf(7, 8, 9, 10), past.filter { it.start == 1 }.map { it.day.monthValue })
        assertEquals(LocalDate.of(2026, 7, 1), past.first { it.start == 1 && it.day.monthValue == 7 }.day)
        // September's end is known (1 Oct follows), October's is not.
        assertEquals(LocalDate.of(2026, 9, 30), past.first { it.end == 1 && it.day.monthValue == 9 }.day)
        assertTrue(past.none { it.end != null && it.day.monthValue == 10 })
        // No day is both an edge and well inside; the start's first days near a gap are never inside.
        assertTrue(past.none { it.rest && (it.start != null || it.end != null) })
        assertTrue(past.none { it.rest && it.day.monthValue == 6 && it.day.dayOfMonth <= 7 })
    }

    @Test fun theRecordWithStretchesAndToday() {
        val days = weekdays(LocalDate.of(2026, 4, 1), today)
        val bars = build(days, ::startUp)
        val said = MonthTurns.answer(MonthTurns.Q(null), Market.NIFTY, bars, today, today.atTime(11, 45))
        assertTrue(said.contains("first 3 trading days"), said)
        assertTrue(said.contains("last 3 trading days"), said)
        assertTrue(said.contains("well inside a month"), said)
        // Every month's first three days rose: closed up on all of them; the stretches each +3.03%.
        assertTrue(Regex("first 3 trading days \\(\\d+ days across \\d+ months\\): .*?closed up on (\\d+) of \\1 \\(100%\\)").containsMatchIn(said), said)
        assertTrue(said.contains("+3.03%"), said)
        assertTrue(said.contains("Taken together they rose in"), said)
        // 1 and 2 Oct are the first two, so today is the third.
        assertTrue(said.contains("Today is the 3rd trading day of October"), said)
        assertTrue(said.contains("+1.00% from the last close so far"), said)
        assertTrue(said.endsWith(MonthTurns.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
    }

    @Test fun onlyTheEdgeAsked() {
        val bars = build(weekdays(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 2)), ::startUp)
        val start = MonthTurns.answer(MonthTurns.Q(MonthTurns.Part.START), Market.BANKNIFTY, bars, today, today.atTime(18, 0))
        assertTrue(start.contains("first 3 trading days") && !start.contains("last 3 trading days"), start)
        assertTrue(start.contains("BankNifty"), start)
        val end = MonthTurns.answer(MonthTurns.Q(MonthTurns.Part.END), Market.NIFTY, bars, today, today.atTime(18, 0))
        assertTrue(end.contains("last 3 trading days") && !end.contains("first 3 trading days"), end)
        // No session today: no today line.
        assertFalse(end.contains("Today is"), end)
    }

    @Test fun fewMonthsSaidAsSmall() {
        val bars = build(weekdays(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 10, 2)), ::startUp)
        val said = MonthTurns.answer(MonthTurns.Q(null), Market.NIFTY, bars, today, today.atTime(18, 0))
        assertTrue(said.contains("one big day moves these figures a lot"), said)
    }
}
