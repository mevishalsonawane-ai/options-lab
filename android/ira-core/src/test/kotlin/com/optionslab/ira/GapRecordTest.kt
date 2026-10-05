package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GapRecordTest {
    private val today = LocalDate.of(2026, 10, 5)   // a Monday

    /** One session: its gap (% from [prev]), the minute after 09:15 it came back to [prev] (null: never), and its close against its open. */
    private data class Spec(val gapPct: Double, val fillMin: Int?, val closeVsOpen: Double = 0.0, val minutes: Int = 375)

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

    /** Sessions on [days] following [specs], each opening its gap from the session before's close. */
    private fun build(days: List<LocalDate>, specs: List<Spec>, start: Double = 24_000.0): List<Candle> {
        var prev = start
        val out = ArrayList<Candle>()
        for ((d, s) in days.zip(specs)) {
            val open = prev * (1 + s.gapPct / 100)
            val up = open > prev
            val close = open + s.closeVsOpen
            for (i in 0 until s.minutes) {
                val c = if (i == s.minutes - 1) close else open
                var h = maxOf(open, c) + 1; var l = minOf(open, c) - 1
                if (i == s.fillMin) { if (up) l = prev - 2 else h = prev + 2 }
                out += Candle(d.atTime(9, 15).plusMinutes(i.toLong()), open, h, l, c)
            }
            prev = close
        }
        return out
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|fill)|likely|expect|target)\\b")

    /**
     * 20 past sessions: 10 gap ups of 0.6% (3 filled by 10:00 at 09:30, 2 more at 10:30, 1 at 14:00, 4 never - 2 of those
     * closing above the open), 8 gap downs of 0.6% (all filled at 09:20), 2 small gaps of 0.2% up (filled at once).
     */
    private val specs: List<Spec> =
        List(3) { Spec(0.62, 15) } + List(2) { Spec(0.62, 75) } + listOf(Spec(0.62, 285)) + List(2) { Spec(0.62, null, 50.0) } + List(2) { Spec(0.62, null, -10.0) } +
            List(8) { Spec(-0.62, 5) } + List(2) { Spec(0.2, 0) }
    private val past = build(weekdays(21), listOf(Spec(0.0, null)) + specs)

    @Test fun theQuestions() {
        val q = GapRecord.asked("When Nifty gaps up over 0.5%, how often does it fill the gap by 11?")
        assertNotNull(q)
        assertEquals(1, q.dir); assertEquals(0.5, q.minPct); assertEquals(LocalTime.of(11, 0), q.by)
        assertEquals(-1, GapRecord.asked("do gap downs usually fill?")?.dir)
        assertNull(GapRecord.asked("do gaps usually get filled")?.dir)
        assertNotNull(GapRecord.asked("what usually happens after a gap up"))
        assertNotNull(GapRecord.asked("gap fill rate for banknifty"))
        assertNotNull(GapRecord.asked("nifty gap kitni baar bharta hai"))
        assertEquals(LocalTime.of(13, 0), GapRecord.asked("how often does a gap down fill by 1 pm")?.by)
        assertEquals(LocalTime.NOON, GapRecord.asked("how often does the gap fill by noon")?.by)
        assertEquals(100.0, GapRecord.asked("when banknifty gaps up 100 points how often does it fill")?.minPts)
        assertEquals(true, GapRecord.asked("how often does a gap like today's fill")?.likeToday)
        // Today's gap, the last time, a forecast, advice, Boss's own trades or bots, a meaning, gold: not this.
        assertNull(GapRecord.asked("did nifty gap up today"))
        assertNull(GapRecord.asked("when did nifty last gap down"))
        assertNull(GapRecord.asked("will the gap fill today"))
        assertNull(GapRecord.asked("should I sell when the gap usually fills"))
        assertNull(GapRecord.asked("how do my trades do on gap up days usually"))
        assertNull(GapRecord.asked("what does gap fill mean"))
        assertNull(GapRecord.asked("how often does gold fill its gap"))
        assertNull(GapRecord.asked("what if nifty gaps up 1% tomorrow"))
        assertNull(GapRecord.asked("how many gap ups this month"))
    }

    @Test fun theMarket() {
        assertEquals(Market.NIFTY, GapRecord.market(emptyList()))
        assertEquals(Market.SENSEX, GapRecord.market(listOf(Market.SENSEX)))
        assertNull(GapRecord.market(listOf(Market.GOLD)))
    }

    @Test fun thePastGaps() {
        val days = GapRecord.past(past, today)
        assertEquals(20, days.size)
        assertEquals(12, days.count { it.up })
        assertEquals(LocalTime.of(9, 30), days.first().filledAt)
        // A partial session (late start) or one far from the session before is left out.
        val late = build(listOf(today.minusDays(60), today.minusDays(59)), listOf(Spec(0.0, null), Spec(0.62, null, minutes = 100)))
        assertEquals(20, GapRecord.past(late + past, today).size)
    }

    @Test fun theRecord() {
        val q = GapRecord.asked("when nifty gaps up over 0.5% how often does it fill by 11")!!
        val said = GapRecord.answer(q, Market.NIFTY, past, today, today.atTime(12, 0))
        assertTrue(said.contains("Over the last 20 Nifty sessions on this phone, it gapped up 0.5% or more on 10."), said)
        assertTrue(said.contains("by 10:00 on 3 (30%), by 11:00 on 5 (50%), and by the close on 6 (60%)"), said)
        assertTrue(said.contains("When it filled, the median time was 10:00."), said)
        assertTrue(said.contains("On 4 of the 10 (40%) it never came back that day, and on 2 (20%) it closed above its open."), said)
        assertFalse(said.contains("gapped down"), said)
        assertTrue(said.endsWith(GapRecord.NOTE), said)
        assertFalse(ADVICE.containsMatchIn(said), said)
    }

    @Test fun bothSidesAndTooFew() {
        val q = GapRecord.asked("do gaps usually fill")!!
        val said = GapRecord.answer(q, Market.NIFTY, past, today, today.atTime(18, 0))
        assertTrue(said.contains("it gapped up 0.5% or more on 10."), said)
        assertTrue(said.contains("it gapped down 0.5% or more on 8."), said)
        assertTrue(said.contains("by 10:00 on 8 (100%)"), said)
        val big = GapRecord.answer(GapRecord.asked("how often does a gap up of 1% fill")!!, Market.NIFTY, past, today, today.atTime(18, 0))
        assertTrue(big.contains("it gapped up 1% or more on 0 - too few"), big)
        val few = GapRecord.answer(q, Market.BANKNIFTY, build(weekdays(5), List(5) { Spec(0.62, 3) }), today, today.atTime(18, 0))
        assertTrue(few.startsWith("I have only 4 BankNifty sessions"), few)
    }

    @Test fun todayBesideTheRecord() {
        val todays = build(listOf(today), listOf(Spec(0.64, null, minutes = 90)), start = past.last().c)
        val q = GapRecord.asked("how often does a gap like today's fill")!!
        val said = GapRecord.answer(q, Market.NIFTY, past + todays, today, today.atTime(10, 45))
        assertTrue(said.contains("it gapped up 0.6% (today's is 0.64%) or more on 10"), said)
        assertTrue(said.contains("Today Nifty opened 0.64% up"), said)
        assertTrue(said.contains("the gap has not filled so far."), said)
        val filled = build(listOf(today), listOf(Spec(-0.3, 20, minutes = 90)), start = past.last().c)
        val s2 = GapRecord.answer(GapRecord.asked("do gap downs usually fill")!!, Market.NIFTY, past + filled, today, today.atTime(10, 45))
        assertTrue(s2.contains("Today Nifty opened 0.30% down"), s2)
        assertTrue(s2.contains("the gap filled at 09:35."), s2)
    }
}
