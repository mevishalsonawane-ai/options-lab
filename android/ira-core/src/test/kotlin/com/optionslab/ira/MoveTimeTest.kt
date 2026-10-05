package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoveTimeTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    /** One session of 1-minute candles from 09:15 to [until], from [open] moving [step] points every minute. */
    private fun session(d: LocalDate, open: Double, step: Double, until: LocalTime = LocalTime.of(15, 29)): List<Candle> {
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15); var px = open
        while (!t.isAfter(until)) {
            val c = px + step
            out += Candle(d.atTime(t), px, maxOf(px, c), minOf(px, c), c)
            px = c; t = t.plusMinutes(1)
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

    /** [n] days of Nifty from 25,000 rising one point a minute. */
    private fun steady(n: Int): List<Candle> = weekdays(n).flatMap { session(it, 25000.0, 1.0) }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move|bounce)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(MoveTime.Q(pts = 50.0), MoveTime.asked("how long does nifty usually take to move 50 points"))
        assertEquals(MoveTime.Q(0.3, window = 30), MoveTime.asked("how often does nifty move 0.3% within 30 minutes"))
        assertEquals(MoveTime.Q(pts = 200.0), MoveTime.asked("how many minutes does banknifty take to move 200 points"))
        assertEquals(MoveTime.Q(), MoveTime.asked("time to move record for sensex"))
        assertEquals(MoveTime.Q(pts = 50.0), MoveTime.asked("nifty ko 50 point chalne mein kitna time lagta hai"))
        assertEquals(MoveTime.Q(pts = 100.0, window = 60), MoveTime.asked("how often does nifty move 100 points in an hour"))
        assertEquals(MoveTime.Q(0.5, window = 15), MoveTime.asked("what are the odds banknifty moves 0.5% in 15 minutes"))
        // A size outside 0.05 to 3%: kept as asked, the default counted.
        assertEquals(MoveTime.Q(MoveTime.DEFAULT_PCT, window = 30, asked = 5.0), MoveTime.asked("how often does nifty move 5% within 30 minutes"))
        // Today, one past move, a forecast, advice, Boss's own book, days or sessions, the open, the first or last hour, the
        // close, a gap or a comeback, candles, options, expiry, gold and VIX, and Jarvis's own speed are others'.
        for (q in listOf("how long did nifty take to move 50 points today", "how long will nifty take to move 50 points",
                "how often does nifty move 1% in a day", "how often does nifty move 2% in 3 sessions", "how often does nifty go 1% from the open",
                "how often does nifty move 0.5% in the first 30 minutes", "how often does nifty move 50 points in the last 30 minutes",
                "how long does nifty take to fill a gap of 50 points", "how long does nifty take to recover a 1% fall",
                "how often does a big 5 minute candle reverse", "how long does my option take to move 50 points",
                "how long does a nifty call take to move 20 points", "how long does nifty take to move 50 points on expiry",
                "how long does gold take to move 1%", "how often does vix move 5% in 30 minutes", "how long do you take to answer",
                "how long does it take to place an order", "which half hour moves the most", "explain this move",
                "should i buy if nifty moves 50 points in 10 minutes", "how often does nifty move 50 points in 500 minutes",
                "how long does nifty take", "how far does nifty usually move in 3 sessions"))
            assertNull(MoveTime.asked(q), q)
    }

    @Test fun theRecord() {
        val xs = MoveTime.past(steady(20), today, 0.0, 50.0)
        // 23 quarter-hour starts from 09:30 to 15:00 in each of 20 whole sessions.
        assertEquals(20 * 23, xs.size)
        // 50 points takes 50 minutes, always above first; the 14:45 and 15:00 starts run out of session.
        assertTrue(xs.filter { it.start.isBefore(LocalTime.of(14, 45)) }.all { it.wait == 50 && it.up == true })
        assertTrue(xs.filter { !it.start.isBefore(LocalTime.of(14, 45)) }.all { it.wait == null })
        val hour = MoveTime.record(xs, 60)
        // Starts with an hour left: 09:30 to 14:15, 20 a session; all got there.
        assertEquals(400, hour.n); assertEquals(400, hour.hit); assertEquals(400, hour.upFirst); assertEquals(0, hour.downFirst)
        assertEquals(50, hour.medianWait); assertEquals(20, hour.days)
        val half = MoveTime.record(xs, 30)
        // Starts with 30 minutes left: 09:30 to 14:45; none got there in 30.
        assertEquals(440, half.n); assertEquals(0, half.hit); assertEquals(50, half.medianWait)
        // 0.2% of about 25,000 is about 50 points: the same waits, give or take a minute.
        val pct = MoveTime.record(MoveTime.past(steady(20), today, 0.2), 60)
        assertEquals(400, pct.n); assertTrue(pct.hit > 350)
        // A session that stops early is not whole, and today is left out.
        val cut = steady(20) + session(today, 25000.0, 1.0) + session(today.plusDays(1), 25000.0, 1.0, until = LocalTime.of(12, 0))
        assertEquals(20 * 23, MoveTime.past(cut, today.plusDays(2), 0.0, 50.0).size - 23)
        assertEquals(MoveTime.sessions(cut, today.plusDays(2)).size, 21)
        // Nothing reached: no median wait.
        assertNull(MoveTime.record(MoveTime.past(steady(20), today, 0.0, 5000.0), 30).medianWait)
    }

    @Test fun theAnswer() {
        val live = steady(20) + session(today, 25000.0, 1.0, until = LocalTime.of(12, 0))
        val a = MoveTime.answer(MoveTime.Q(pts = 50.0, window = 60), Market.NIFTY, live, today, today.atTime(12, 1))
        assertTrue(a.startsWith("Nifty moved 50 points either way within 60 minutes on 400 of the last 400 quarter-hour starts on this phone (100%), and the median wait was 50 minutes."), a)
        assertTrue("above the start first on 400 and below it first on 0" in a, a)
        assertTrue("not 400 separate chances" in a, a)
        assertTrue("a small record" in a, a)
        assertTrue("Today so far, 7 of the 7 quarter-hour starts with 60 minutes behind them moved that far" in a, a)
        assertFalse("ended" in a, a)
        assertTrue(a.endsWith(MoveTime.NOTE), a)
        assertFalse(ADVICE.containsMatchIn(a), a)
        // The short line keeps the key figure.
        val line = ShortAnswer.of("how often does nifty move 50 points in an hour", a).line
        assertTrue("400 of the last 400" in line, line)
        // After the close, a whole session: "ended"; one that stopped early says where it stops.
        val whole = steady(20) + session(today, 25000.0, 1.0)
        assertTrue("Today's session ended with 20 of the 20" in MoveTime.answer(MoveTime.Q(pts = 50.0, window = 60), Market.NIFTY, whole, today, today.atTime(16, 0)))
        val early = MoveTime.answer(MoveTime.Q(pts = 50.0, window = 60), Market.NIFTY, live, today, today.atTime(16, 0))
        assertTrue("Today's candles stop at 12:00" in early, early)
        assertFalse("ended" in early, early)
        // Never reached: said so; a size out of range; too few sessions; gold.
        assertTrue("more than half of those tries never got that far" in MoveTime.answer(MoveTime.Q(pts = 5000.0), Market.NIFTY, live, today, today.atTime(12, 1)))
        assertTrue("not the 5% asked" in MoveTime.answer(MoveTime.Q(window = 30, asked = 5.0), Market.NIFTY, live, today, today.atTime(12, 1)))
        assertTrue(MoveTime.answer(MoveTime.Q(), Market.NIFTY, steady(5), today, today.atTime(12, 1)).startsWith("I have only 5 whole Nifty sessions"))
        assertNull(MoveTime.market(listOf(Market.GOLD)))
        assertEquals(Market.NIFTY, MoveTime.market(emptyList()))
    }
}
