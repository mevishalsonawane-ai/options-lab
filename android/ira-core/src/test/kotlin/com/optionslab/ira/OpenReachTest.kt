package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenReachTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    /**
     * One session of 1-minute candles from 09:15 to [until]: from [open] straight to [high] by 11:00, down to [low] by
     * 13:00, then to [close] at 15:29.
     */
    private fun session(d: LocalDate, open: Double, high: Double, low: Double, close: Double, until: LocalTime = LocalTime.of(15, 29)): List<Candle> {
        val pts = listOf(LocalTime.of(9, 15) to open, LocalTime.of(11, 0) to high, LocalTime.of(13, 0) to low, LocalTime.of(15, 29) to close)
        fun px(t: LocalTime): Double {
            val s = t.toSecondOfDay()
            for (i in 0 until pts.size - 1) {
                val (a, pa) = pts[i]; val (b, pb) = pts[i + 1]
                if (s <= b.toSecondOfDay()) return pa + (pb - pa) * (s - a.toSecondOfDay()).toDouble() / (b.toSecondOfDay() - a.toSecondOfDay())
            }
            return close
        }
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15)
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

    /**
     * [n] days of Nifty opening at 25,000, by turns: a quiet day (+0.2% / -0.2%, ends +0.1%), a day up 0.8% and down 0.1%
     * (ends +0.6%), a two-sided day (+0.6% / -0.7%, ends -0.1%), a day down 1.2% (ends -1.0%).
     */
    private val SHAPES = listOf(listOf(0.2, -0.2, 0.1), listOf(0.8, -0.1, 0.6), listOf(0.6, -0.7, -0.1), listOf(0.1, -1.2, -1.0))
    private fun nifty(n: Int): List<Candle> = weekdays(n).withIndex().flatMap { (i, d) ->
        val (h, l, c) = SHAPES[i % 4]
        session(d, 25000.0, 25000 * (1 + h / 100), 25000 * (1 + l / 100), 25000 * (1 + c / 100))
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move|bounce)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(OpenReach.Q(), OpenReach.asked("how far does nifty usually move from its open"))
        assertEquals(OpenReach.Q(1.0), OpenReach.asked("how often does nifty go 1% from the open"))
        assertEquals(OpenReach.Q(0.5), OpenReach.asked("how often does banknifty trade 0.5% on both sides of the open"))
        assertEquals(OpenReach.Q(0.3), OpenReach.asked("how often does nifty close within 0.3% of its open"))
        assertEquals(OpenReach.Q(), OpenReach.asked("open reach record"))
        assertEquals(OpenReach.Q(), OpenReach.asked("open se kitna door jata hai nifty aksar"))
        assertEquals(OpenReach.Q(pts = 100.0), OpenReach.asked("how often does nifty move 100 points from the open"))
        assertEquals(OpenReach.Q(), OpenReach.asked("move from the open stats for sensex"))
        assertEquals(OpenReach.Q(1.0), OpenReach.asked("open se 1% kitni baar jata hai banknifty"))
        // A size outside 0.1 to 5%: kept as asked, the 0.5% days counted.
        assertEquals(OpenReach.Q(0.5, asked = 8.0), OpenReach.asked("how often does nifty move 8% from its open"))
        // Today, now, a forecast, advice, Boss's own book, a what-if, the gap, the opening range, the open as the high, the
        // first move, overnight, the day after, a week, expiry, options, gold and VIX are others'; a question with no record
        // asked of it is the day's own.
        for (q in listOf("how far is nifty from its open today", "how far has nifty moved from the open", "how far is nifty from the open right now",
                "will nifty move 1% from the open", "should i sell a straddle at the open", "how far did my trade move from its open",
                "what if nifty moves 1% from the open", "how often does the gap fill from the open", "do opening range breakouts usually hold",
                "how often is the open the high of the day", "how often does the first 30 minutes direction match the close",
                "does nifty make its moves overnight or during the day", "what happens the day after a big move from the open",
                "how far does nifty usually move from the open in a week", "how far does nifty move from the open on expiry",
                "how far do nifty calls usually move from their open", "how far does gold usually move from its open",
                "how far does vix usually move from its open", "how often does nifty close above its open",
                "how often does nifty recover a 1% fall during the day", "nifty ka high kab banta hai"))
            assertNull(OpenReach.asked(q), q)
        // A day of the week, a part of the day or a clock time, and a count of sessions are others' (Weekdays', the day's
        // clock, MultiDay's) - never the whole day's reach from its open.
        for (q in listOf("how far does nifty usually move from its open on mondays", "how often does nifty go 1% from the open on a friday",
                "how far does nifty usually move from its open in the morning", "how far does nifty usually move from the open in the afternoon",
                "how often does nifty go 0.5% from the open after 2 pm", "how often does nifty go 0.5% from the open by 11am",
                "how far does nifty usually move from its open before 10 30", "how far does banknifty usually move from its open in 3 sessions",
                "how often does nifty go 1% from the open over the next 3 days", "3 session move from the open record for nifty"))
            assertNull(OpenReach.asked(q), q)
        // "What share" is the record's own question (the stock sense of "share" is still refused).
        assertEquals(OpenReach.Q(1.0), OpenReach.asked("what share of days does nifty go 1% from the open"))
        assertNull(OpenReach.asked("how far does this share usually move from its open"))
        // A size beside "after" or "by" is a size, not a clock.
        assertEquals(OpenReach.Q(0.5), OpenReach.asked("how often does nifty move by 0.5 percent from its open"))
    }

    @Test fun theRecord() {
        val days = OpenReach.past(nifty(40), today)
        assertEquals(40, days.size)
        val r = OpenReach.record(days, 0.5)
        // Quiet days: neither. Up days: above only. Two-sided: both. Down days: below only.
        assertEquals(10, r.aboveOnly); assertEquals(10, r.belowOnly); assertEquals(10, r.both); assertEquals(10, r.neither)
        assertEquals(30, r.reached)
        // Within 0.5% at the close: the quiet and the two-sided days; of those that got that far, the two-sided ones.
        assertEquals(20, r.endedNear); assertEquals(10, r.reachedEndedNear)
        // 100 points on 25,000 is 0.4%: the same split here.
        assertEquals(30, OpenReach.record(days, 0.0, 100.0).reached)
        // Today is left out; a session that stops early is not whole.
        val cut = nifty(40) + session(today, 25000.0, 25100.0, 24900.0, 25050.0, until = LocalTime.of(12, 0))
        assertEquals(40, OpenReach.past(cut, today).size)
    }

    @Test fun theAnswer() {
        val bars = nifty(40) + session(today, 25000.0, 25150.0, 24950.0, 25050.0, until = LocalTime.of(12, 0))
        val a = OpenReach.answer(OpenReach.Q(), Market.NIFTY, bars, today, today.atTime(12, 1))
        assertTrue(a.startsWith("Nifty went 0.5% or more from its open on 30 of the last 40 whole sessions on this phone (75%), and on both sides of it on 10 (25%)."), a)
        assertTrue("Above the open only on 10, below it only on 10, and it never got that far on 10" in a, a)
        assertTrue("It ended within 0.5% of its open on 20 days (50%), 10 of them after first going that far" in a, a)
        assertTrue("The farthest was" in a && "1.20% below its open" in a, a)
        assertTrue("Today Nifty has been" in a && "so far" in a, a)
        assertFalse("ended" in a.substringAfter("Today"), a)
        assertTrue("only 40 days" in a, a)
        assertTrue(a.endsWith(OpenReach.NOTE), a)
        assertFalse(ADVICE.containsMatchIn(a), a)
        // The short line keeps the key figure.
        val line = ShortAnswer.of("how often does nifty go 0.5% from the open", a).line
        assertTrue("30 of the last 40" in line, line)
        // After the close, a whole session: "ended"; one that stopped early says where it stops.
        val whole = nifty(40) + session(today, 25000.0, 25150.0, 24950.0, 25050.0)
        assertTrue("and ended +0.20% on the open" in OpenReach.answer(OpenReach.Q(), Market.NIFTY, whole, today, today.atTime(16, 0)))
        val early = OpenReach.answer(OpenReach.Q(), Market.NIFTY, bars, today, today.atTime(16, 0))
        assertTrue("by 12:00, where its candles on the phone stop" in early, early)
        assertFalse("ended +" in early, early)
        // Points, a size out of range, too few days, gold.
        assertTrue(OpenReach.answer(OpenReach.Q(pts = 100.0), Market.NIFTY, bars, today, today.atTime(12, 1)).startsWith("Nifty went 100 points or more"))
        assertTrue("not the 8% asked" in OpenReach.answer(OpenReach.Q(0.5, asked = 8.0), Market.NIFTY, bars, today, today.atTime(12, 1)))
        assertTrue(OpenReach.answer(OpenReach.Q(), Market.NIFTY, nifty(10), today, today.atTime(12, 1)).startsWith("I have only 10 whole sessions"))
        assertNull(OpenReach.market(listOf(Market.GOLD)))
        assertEquals(Market.NIFTY, OpenReach.market(emptyList()))
        assertTrue("Boss" in OpenReach.NOT_HERE)
    }
}
