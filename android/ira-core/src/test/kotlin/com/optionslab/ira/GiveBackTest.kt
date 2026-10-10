package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GiveBackTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    /**
     * One session of 1-minute candles from 09:15 to [until]: from [open], [rise] points a minute for [upFor] minutes, then
     * [fall] points a minute down.
     */
    private fun session(d: LocalDate, open: Double, rise: Double, upFor: Int, fall: Double, until: LocalTime = LocalTime.of(15, 29)): List<Candle> {
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15); var px = open; var i = 0
        while (!t.isAfter(until)) {
            val c = if (i < upFor) px + rise else px - fall
            out += Candle(d.atTime(t), px, maxOf(px, c), minOf(px, c), c)
            px = c; t = t.plusMinutes(1); i++
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

    /** [n] days of Nifty from 25,000: up 2 points a minute for the first hour (to 25,120), then down 0.2 a minute (closing 25,057). */
    private fun runs(n: Int): List<Candle> = weekdays(n).flatMap { session(it, 25000.0, 2.0, 60, 0.2) }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move|bounce)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(GiveBack.Q(pts = 100.0, by = 60), GiveBack.asked("after nifty runs 100 points in the first hour how much does it give back by the end of the day"))
        assertEquals(GiveBack.Q(1.0), GiveBack.asked("how much of a 1% run from the open does banknifty usually give back"))
        assertEquals(GiveBack.Q(pts = 100.0), GiveBack.asked("how deep is the pullback after nifty runs 100 points"))
        assertEquals(GiveBack.Q(), GiveBack.asked("give back record for sensex"))
        assertEquals(GiveBack.Q(), GiveBack.asked("pullback after a run record"))
        assertEquals(GiveBack.Q(pts = 100.0), GiveBack.asked("100 point chalne ke baad nifty kitna wapas deta hai"))
        assertEquals(GiveBack.Q(0.5, by = 30), GiveBack.asked("when banknifty rallies 0.5% in the first half hour how much does it usually give back"))
        assertEquals(GiveBack.Q(pts = 150.0, by = 30), GiveBack.asked("how much does finnifty retrace after a 150 point move in the first 30 minutes"))
        // A size outside 0.1 to 3%: kept as asked, the default counted.
        assertEquals(GiveBack.Q(GiveBack.DEFAULT_PCT, asked = 5.0), GiveBack.asked("how much does nifty give back after a 5% run from the open"))
        // A forecast, advice, Boss's own book, today or one past day, a comeback from the previous close, the reach from the
        // open, the time to move, the first move's direction, the last hour, gold and VIX, a definition, Jarvis's own speed.
        for (q in listOf("how much will nifty give back after running 100 points", "should i book profit after nifty runs 100 points",
                "how much did nifty give back today", "how much has nifty given back so far", "how much did nifty give back yesterday",
                "how much does my option give back after a 100 point run", "how much does my position give back",
                "does nifty recover from a 1% fall", "how far does nifty usually move from its open",
                "how long does nifty usually take to move 50 points", "does the first half hour usually decide the day",
                "does nifty usually reverse in the last hour", "how much does gold give back after a 1% run",
                "how much does vix give back after a 10% run", "what is a pullback", "what is a fibonacci retracement",
                "how fast do you answer", "how much does nifty give back after running 100 points in 30 minutes",
                "how much does nifty give back after a 1% run in 3 sessions", "will nifty pull back after this run",
                "how much does nifty give back if it runs 100 points"))
            assertNull(GiveBack.asked(q), q)
    }

    @Test fun theRecord() {
        val ss = GiveBack.sessions(runs(20), today)
        assertEquals(20, ss.size)
        val x = GiveBack.run(ss.first(), 0.0, 100.0, null)!!
        assertTrue(x.up)
        assertEquals(LocalTime.of(10, 4), x.at)
        assertEquals(25120.0, x.peak, 1e-6)
        assertEquals(LocalTime.of(10, 14), x.peakAt)
        assertEquals(25057.0, x.close, 1e-6)
        assertEquals(63.0, x.deepest, 1e-6)
        assertEquals(63.0 / 120, x.gave, 1e-9)
        val r = GiveBack.record(ss, GiveBack.Q(pts = 100.0, by = 60))
        assertEquals(20, r.runs.size)
        assertEquals(20, r.gaveSome)
        assertEquals(0, r.keptMost + r.gaveMost + r.gaveAll)
        assertEquals(0, r.peakAfter)
        // Asked within the first half hour: 100 points is never reached by 09:45 (60 points by then).
        assertEquals(0, GiveBack.record(ss, GiveBack.Q(pts = 100.0, by = 30)).runs.size)
        // A run down: the mirror.
        val down = GiveBack.run(MarketStory.sessions(session(today, 25000.0, -2.0, 60, -0.2)).first(), 0.0, 100.0, null)!!
        assertFalse(down.up)
        assertEquals(63.0 / 120, down.gave, 1e-9)
        // Back through the open: all of it given back.
        val all = GiveBack.run(MarketStory.sessions(session(today, 25000.0, 2.0, 60, 1.0)).first(), 0.0, 100.0, null)!!
        assertTrue(all.gave >= 1.0)
        // A minute that traded both sides of the size at once is no run.
        val wide = listOf(Candle(today.atTime(9, 15), 25000.0, 25200.0, 24800.0, 25000.0)) + session(today, 25000.0, 0.0, 0, 0.0).drop(1)
        assertNull(GiveBack.run(MarketStory.sessions(wide).first(), 0.0, 100.0, null))
    }

    @Test fun theAnswer() {
        val live = runs(20) + session(today, 25000.0, 2.0, 60, 0.2, until = LocalTime.of(11, 0))
        val a = GiveBack.answer(GiveBack.Q(pts = 100.0, by = 60), Market.NIFTY, live, today, today.atTime(11, 1))
        assertTrue(a.startsWith("When Nifty ran 100 points from its open within the first hour - 20 of the last 20 whole sessions"), a)
        assertTrue("gave back a median 53% of the run" in a, a)
        assertTrue("a third to two thirds on 20 (100%)" in a, a)
        assertTrue("a small record" in a, a)
        assertTrue("Today so far, Nifty ran up 100 points from its open by 10:04; its peak so far was 120 points" in a, a)
        assertFalse(ADVICE.containsMatchIn(a), a)
        assertTrue(a.endsWith(GiveBack.NOTE), a)
        val whole = runs(20) + session(today, 25000.0, 2.0, 60, 0.2)
        assertTrue("Today, Nifty ran up 100 points" in GiveBack.answer(GiveBack.Q(pts = 100.0), Market.NIFTY, whole, today, today.atTime(16, 0)))
        assertTrue("the close gave back 53% of it" in GiveBack.answer(GiveBack.Q(pts = 100.0), Market.NIFTY, whole, today, today.atTime(16, 0)))
        assertTrue("Today's candles stop at 11:00" in GiveBack.answer(GiveBack.Q(pts = 100.0), Market.NIFTY, live, today, today.atTime(16, 0)))
        val none = GiveBack.answer(GiveBack.Q(pts = 5000.0), Market.NIFTY, live, today, today.atTime(11, 1))
        assertTrue(none.startsWith("None of the last 20 whole sessions"), none)
        assertTrue("Today so far, Nifty has not run 5,000 points from its open." in none, none)
        assertTrue("not the 5% asked" in GiveBack.answer(GiveBack.Q(asked = 5.0), Market.NIFTY, live, today, today.atTime(11, 1)))
        assertTrue(GiveBack.answer(GiveBack.Q(), Market.NIFTY, runs(5), today, today.atTime(12, 1)).startsWith("I have only 5 whole Nifty sessions"))
        assertNull(GiveBack.market(listOf(Market.GOLD)))
        assertEquals(Market.NIFTY, GiveBack.market(emptyList()))
    }
}
