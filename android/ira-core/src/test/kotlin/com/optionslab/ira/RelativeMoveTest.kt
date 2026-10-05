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

class RelativeMoveTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    /** One session of 1-minute candles from 09:15 to [until]: a straight walk from [open] to [close], the 12:00 candle reaching [hi] and [lo]. */
    private fun session(d: LocalDate, open: Double, close: Double, hi: Double, lo: Double, until: LocalTime = LocalTime.of(15, 29)): List<Candle> {
        val first = LocalTime.of(9, 15).toSecondOfDay(); val last = LocalTime.of(15, 29).toSecondOfDay()
        fun px(t: LocalTime) = open + (close - open) * (t.toSecondOfDay() - first).toDouble() / (last - first)
        val out = ArrayList<Candle>()
        var t = LocalTime.of(9, 15)
        while (!t.isAfter(until)) {
            val o = if (t == LocalTime.of(9, 15)) open else px(t.minusMinutes(1))
            val c = px(t)
            val noon = t == LocalTime.of(12, 0)
            out += Candle(d.atTime(t), o, if (noon) hi else maxOf(o, c), if (noon) lo else minOf(o, c), c)
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
     * [n] days: Nifty ranges 200 points on 25000 (0.80%) and closes +50 / -50 by turns; BankNifty ranges 880 on 55000
     * (1.60%, twice Nifty's) and closes the same way twice as far - except every fourth day, when it ranges only 220 (0.40%).
     */
    private fun nifty(n: Int): List<Candle> = weekdays(n).withIndex().flatMap { (i, d) ->
        val up = i % 2 == 0
        session(d, 25000.0, if (up) 25050.0 else 24950.0, 25100.0, 24900.0)
    }

    private fun bank(n: Int): List<Candle> = weekdays(n).withIndex().flatMap { (i, d) ->
        val up = i % 2 == 0
        if (i % 4 == 3) session(d, 55000.0, if (up) 55050.0 else 54950.0, 55110.0, 54890.0)
        else session(d, 55000.0, if (up) 55200.0 else 54800.0, 55440.0, 54560.0)
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(RelativeMove.Q(Market.BANKNIFTY, Market.NIFTY), RelativeMove.asked("how often does banknifty move more than nifty in % on a day"))
        assertEquals(RelativeMove.Q(Market.SENSEX, Market.NIFTY), RelativeMove.asked("is sensex more volatile than nifty"))
        assertEquals(RelativeMove.Q(Market.NIFTY, Market.BANKNIFTY), RelativeMove.asked("does nifty move less than bank nifty usually"))
        assertEquals(RelativeMove.Q(Market.BANKNIFTY, Market.NIFTY), RelativeMove.asked("banknifty nifty se zyada kitni baar chalta hai"))
        assertEquals(RelativeMove.Q(Market.BANKNIFTY, Market.NIFTY), RelativeMove.asked("kya nifty se zyada banknifty chalta hai"))
        assertEquals(RelativeMove.Q(Market.BANKNIFTY, null), RelativeMove.asked("relative volatility of banknifty"))
        assertEquals(RelativeMove.Q(Market.FINNIFTY, Market.NIFTY), RelativeMove.asked("which moves more, finnifty or nifty"))
        assertNotNull(RelativeMove.asked("how many times bigger are banknifty's moves than nifty's"))
        // Today's strength, moving together, a forecast, a reason, advice, Boss's own book, options, gold and VIX are others'.
        for (q in listOf("is banknifty stronger than nifty", "is banknifty moving more than nifty today", "does banknifty move with nifty",
                "will banknifty move more than nifty tomorrow", "why does banknifty move more than nifty", "should i trade banknifty since it moves more than nifty",
                "do banknifty options move more than nifty options", "does gold move more than nifty", "does vix move more than nifty",
                "how is nifty", "which day of the week moves the most", "does banknifty move more than nifty in a week", "how much did banknifty move"))
            assertNull(RelativeMove.asked(q), q)
    }

    @Test fun thePair() {
        assertEquals(Market.BANKNIFTY to Market.NIFTY, RelativeMove.pair(RelativeMove.Q(Market.BANKNIFTY, Market.NIFTY), listOf(Market.BANKNIFTY, Market.NIFTY)))
        assertEquals(Market.SENSEX to Market.NIFTY, RelativeMove.pair(RelativeMove.Q(Market.SENSEX, null), listOf(Market.SENSEX)))
        assertEquals(Market.NIFTY to Market.BANKNIFTY, RelativeMove.pair(RelativeMove.Q(Market.NIFTY, null), listOf(Market.NIFTY)))
        assertEquals(Market.BANKNIFTY to Market.NIFTY, RelativeMove.pair(RelativeMove.Q(null, null), emptyList()))
        assertNull(RelativeMove.pair(RelativeMove.Q(null, null), listOf(Market.GOLD)))
    }

    @Test fun theRecord() {
        val ds = RelativeMove.days(bank(40), nifty(40), today)
        assertEquals(40, ds.size)
        assertEquals(30, ds.count { it.rangeA > it.rangeB })
        // The first day has no session before it on the phone: no change.
        assertNull(ds.first().changeA); assertNotNull(ds.last().changeA)
        val said = RelativeMove.answer(Market.BANKNIFTY, Market.NIFTY, bank(40), nifty(40), today, today.atTime(10, 0))
        assertTrue(said.contains("wider than Nifty's on 30 of them (75%)"), said)
        assertTrue(said.contains("2.00 times Nifty's"), said)
        assertTrue(said.contains("closed the same way on 39 (100%)"), said)
        assertTrue(said.contains("BankNifty's change was the larger in size on 39 of 39 days (100%)"), said)
        assertTrue(said.contains("only 40 days"), said)
        assertTrue(said.endsWith(RelativeMove.NOTE), said)
        assertTrue(said.contains("Boss"))
        assertFalse(ADVICE.containsMatchIn(said), said)
        // Nothing today on the phone: no line for it; past days only, today's session never counted.
        assertFalse(said.contains("Today"), said)
    }

    @Test fun todaySoFarIsBesideTheRecord() {
        val withToday = bank(30) + session(today, 55000.0, 55100.0, 55275.0, 54945.0, LocalTime.of(12, 30))
        val niftyToday = nifty(30) + session(today, 25000.0, 25020.0, 25050.0, 24975.0, LocalTime.of(12, 30))
        assertEquals(30, RelativeMove.days(withToday, niftyToday, today).size)
        val said = RelativeMove.answer(Market.BANKNIFTY, Market.NIFTY, withToday, niftyToday, today, today.atTime(12, 30))
        assertTrue(said.contains("Today so far, BankNifty has ranged 0.60% of its open and Nifty 0.30% - 2.00 times."), said)
    }

    @Test fun tooFewDaysAndHalfDays() {
        val said = RelativeMove.answer(Market.BANKNIFTY, Market.NIFTY, bank(10), nifty(10), today, today.atTime(10, 0))
        assertTrue(said.startsWith("I have only 10 whole days"), said)
        assertTrue(said.contains("Boss"))
        // A half session on the phone is not a whole day.
        val d = weekdays(1).single()
        val half = session(d, 55000.0, 55100.0, 55200.0, 54900.0, LocalTime.of(12, 30))
        assertTrue(RelativeMove.days(half, session(d, 25000.0, 25010.0, 25040.0, 24990.0), today).isEmpty())
    }

    @Test fun neverActsNorAnOrder() {
        for (q in listOf("how often does banknifty move more than nifty", "banknifty nifty se zyada kitni baar chalta hai", "is sensex more volatile than nifty")) {
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertFalse(Bundle.acts(q), q)
        }
    }
}
