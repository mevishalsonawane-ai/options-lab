package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiDayTest {
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
     * [days] of Nifty, every one opening and closing at 25,000, by turns: up 0.6% and down 0.1%, up 0.1% and down 0.6%, quiet
     * (+0.2% / -0.2%), wide (+1.2% / -1.2%).
     */
    private val SHAPES = listOf(0.6 to -0.1, 0.1 to -0.6, 0.2 to -0.2, 1.2 to -1.2)
    private fun flat(days: List<LocalDate>): List<Candle> = days.withIndex().flatMap { (i, d) ->
        val (h, l) = SHAPES[i % 4]
        session(d, 25000.0, 25000 * (1 + h / 100), 25000 * (1 + l / 100), 25000.0)
    }

    /** [n] days of Nifty rising 0.6% each, its low the open and its high the close. */
    private fun rising(n: Int): List<Candle> {
        var px = 25000.0
        return weekdays(n).flatMap { d -> val c = px * 1.006; session(d, px, c, px, c).also { px = c } }
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move|bounce)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(MultiDay.Q(3), MultiDay.asked("how far does nifty usually move in 3 sessions"))
        assertEquals(MultiDay.Q(3, 2.0), MultiDay.asked("how often does nifty move 2% in 3 days"))
        assertEquals(MultiDay.Q(4, 1.5), MultiDay.asked("how often does banknifty stay within 1.5% over 4 sessions"))
        assertEquals(MultiDay.Q(3), MultiDay.asked("3 day move record for sensex"))
        assertEquals(MultiDay.Q(3), MultiDay.asked("teen din mein nifty kitna chalta hai"))
        assertEquals(MultiDay.Q(2), MultiDay.asked("how much does nifty usually move over the next two trading days"))
        assertEquals(MultiDay.Q(5, pts = 300.0), MultiDay.asked("how often does nifty move 300 points in 5 sessions"))
        assertEquals(MultiDay.Q(3, 1.0), MultiDay.asked("how many times has banknifty gone 1% within 3 sessions"))
        // A size outside 0.2 to 10%: kept as asked, the 1% stretches counted.
        assertEquals(MultiDay.Q(3, 1.0, asked = 15.0), MultiDay.asked("how often does nifty move 15% in 3 days"))
        // One day, more than ten, today, the last few days as they went, a forecast, advice, Boss's own book, a run of closes,
        // the day after, the open, a calendar week, expiry, options, gold and VIX are others'; no record asked is no record.
        for (q in listOf("how far does nifty usually move in a day", "how often does nifty move 5% in 20 sessions",
                "how much has nifty moved today", "how much did nifty move in the last 3 days", "how did nifty do over the past 3 sessions",
                "how far will nifty move in 3 days", "should i hold my put for 3 days", "how far can my position move in 3 sessions",
                "how many days in a row has nifty risen", "what happens the day after a big fall", "how often does nifty go 1% from the open",
                "how big is a normal week for banknifty", "how far does nifty usually move in 3 days to expiry",
                "how much does a nifty straddle lose in 3 days", "how far does gold usually move in 3 days", "how far does vix usually move in 3 days",
                "nifty moved 2% in 3 days", "what if nifty falls 2% in 3 sessions", "after a 1% fall how does nifty do over the next 3 sessions",
                "how far is nifty from its open today", "how much did nifty fall this week"))
            assertNull(MultiDay.asked(q), q)
        // A day of the week: Weekdays' or a calendar question, never the stretch record.
        for (q in listOf("how far does nifty usually move in 3 sessions from monday", "how often does nifty move 2% in 3 days starting friday",
                "how far does banknifty usually move over 2 sessions after thursdays", "somvar se teen din mein nifty kitna chalta hai"))
            assertNull(MultiDay.asked(q), q)
    }

    @Test fun theRecord() {
        val days = weekdays(40)
        val xs = MultiDay.past(flat(days), today, 2)
        // 40 sessions in a row: 38 stretches of two after a whole session.
        assertEquals(38, xs.size)
        // Pairs of shapes by turns: (down-ish, quiet), (quiet, wide), (wide, up-ish), (up-ish, down-ish) from the second session on.
        val r = MultiDay.record(xs, 1.0)
        // 1%: only a stretch holding the wide day gets that far, on both sides.
        assertEquals(0, r.aboveOnly); assertEquals(0, r.belowOnly)
        assertEquals(xs.count { s -> s.upPct >= 1.0 }, r.both); assertEquals(r.n - r.both, r.neither)
        // Every close is 25,000: nothing ended away, nothing ended higher.
        assertEquals(0, r.endedAway); assertEquals(0, r.endedUp)
        // 0.5%: the up-ish and down-ish pair is on both sides; down-ish with quiet below only; nothing above only (up-ish pairs with wide or down-ish).
        val half = MultiDay.record(xs, 0.5)
        assertEquals(0, half.aboveOnly); assertTrue(half.belowOnly > 0); assertEquals(0, half.neither)
        // 150 points on 25,000 is 0.6%: the same as 0.6%.
        assertEquals(MultiDay.record(xs, 0.6).reached, MultiDay.record(xs, 0.0, 150.0).reached)
        // A rise every day: above only, ended up and away each time.
        val up = MultiDay.record(MultiDay.past(rising(30), today, 2), 1.0)
        assertEquals(28, up.n); assertEquals(28, up.aboveOnly); assertEquals(28, up.endedAway); assertEquals(28, up.endedUp)
        // A missing session is never bridged: the stretches across it are left out.
        val gap = flat(days.filterIndexed { i, _ -> i != 20 })
        assertEquals(35, MultiDay.past(gap, today, 2).size)
        // A session that stops early is not whole, and today is left out.
        val cut = flat(days) + session(today, 25000.0, 25100.0, 24900.0, 25050.0, until = LocalTime.of(12, 0))
        assertEquals(38, MultiDay.past(cut, today, 2).size)
    }

    @Test fun theAnswer() {
        val bars = rising(40) + session(today, 25000 * Math.pow(1.006, 40.0), 25000 * Math.pow(1.006, 40.0) * 1.003,
            25000 * Math.pow(1.006, 40.0), 25000 * Math.pow(1.006, 40.0) * 1.002, until = LocalTime.of(12, 0))
        val a = MultiDay.answer(MultiDay.Q(2), Market.NIFTY, bars, today, today.atTime(12, 1))
        assertTrue(a.startsWith("Over 2 sessions, Nifty went 1% or more from the close it started from on 38 of the last 38 stretches on this phone (100%), and ended 1% or more away on 38 (100%)."), a)
        assertTrue("Above that close only on 38, below it only on 0, on both sides on 0, and never that far on 0" in a, a)
        assertTrue("higher on 38 (100%)" in a, a)
        assertTrue("so they are not 38 separate tries" in a, a)
        assertTrue("a small record" in a, a)
        assertTrue("Counting today, the newest 2 sessions have taken Nifty" in a && "so far" in a, a)
        assertFalse("ended" in a.substringAfter("Counting today"), a)
        assertTrue(a.endsWith(MultiDay.NOTE), a)
        assertFalse(ADVICE.containsMatchIn(a), a)
        // The short line keeps the key figure.
        val line = ShortAnswer.of("how often does nifty move 1% in 2 sessions", a).line
        assertTrue("38 of the last 38" in line, line)
        // After the close, a whole session: "ended"; one that stopped early says where it stops.
        val whole = rising(40) + session(today, 25000 * Math.pow(1.006, 40.0), 25000 * Math.pow(1.006, 40.0) * 1.003,
            25000 * Math.pow(1.006, 40.0), 25000 * Math.pow(1.006, 40.0) * 1.002)
        val w = MultiDay.answer(MultiDay.Q(2), Market.NIFTY, whole, today, today.atTime(16, 0))
        assertTrue("The newest 2 sessions on the phone, to 7 Oct, ended +" in w, w)
        val early = MultiDay.answer(MultiDay.Q(2), Market.NIFTY, bars, today, today.atTime(16, 0))
        assertTrue("at 12:00, where its candles stop" in early, early)
        assertFalse("to 7 Oct, ended" in early, early)
        // Points, a size out of range, too few stretches, gold.
        assertTrue(MultiDay.answer(MultiDay.Q(2, pts = 100.0), Market.NIFTY, bars, today, today.atTime(12, 1)).startsWith("Over 2 sessions, Nifty went 100 points or more"))
        assertTrue("not the 15% asked" in MultiDay.answer(MultiDay.Q(2, 1.0, asked = 15.0), Market.NIFTY, bars, today, today.atTime(12, 1)))
        assertTrue(MultiDay.answer(MultiDay.Q(3), Market.NIFTY, rising(10), today, today.atTime(12, 1)).startsWith("I have only 7 stretches"))
        assertNull(MultiDay.market(listOf(Market.GOLD)))
        assertEquals(Market.NIFTY, MultiDay.market(emptyList()))
        assertTrue("Boss" in MultiDay.NOT_HERE)
    }

    @Test fun theNewestIsRankedAgainstTheOthersOnlyAndNeverPast100() {
        val d0 = LocalDate.of(2026, 9, 1)
        fun st(i: Int, far: Double) = MultiDay.Stretch(d0.plusDays(i.toLong()), d0.plusDays(i + 2L), 100.0, 100.0 + far, 100.0, 100.0)
        // 199 others reached less far, one as far: rounded down to 99, never 100 (nor "farther than 100%").
        val others = (0 until 199).map { st(it, 1.0) } + st(199, 5.0)
        val newest = st(300, 5.0)
        assertEquals("a farther reach than 99% of the other stretches in this record", MultiDay.rankText(others + newest, newest))
        // Every other one reached less far: said as every other stretch, and the newest is not compared with itself.
        val all = (0 until 20).map { st(it, 1.0) }
        val top = st(300, 4.0)
        assertEquals("a farther reach than every other stretch in this record", MultiDay.rankText(all + top, top))
        assertEquals("a farther reach than every other stretch in this record", MultiDay.rankText(all, top))
        // Itself alone: no share at all.
        assertEquals("the first stretch of its kind in this record", MultiDay.rankText(listOf(top), top))
        // Half the others reached less far.
        val half = (0 until 10).map { st(it, 1.0) } + (10 until 20).map { st(it, 9.0) }
        assertEquals("a farther reach than 50% of the other stretches in this record", MultiDay.rankText(half + top, top))
    }
}
