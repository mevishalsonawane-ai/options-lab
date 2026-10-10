package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtmBuyTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    /**
     * One session from 09:15 to [until] (minutes of the IST day): the index flat at 25,000 to 09:30, then [drift] points a
     * minute; the strikes 24,900 to 25,100 of an expiry [expiry] days away, each priced at its intrinsic value plus a time
     * value of 100 at 09:30 that loses half of itself by 15:30. [spike] adds that much to each candle's high at 12:00.
     */
    private fun session(d: LocalDate, drift: Double, expiry: Long = 2, until: Int = 930, optionsUntil: Int = until, spike: Double? = null): Session {
        val mins = (555..until).toList().toIntArray()
        fun spot(m: Int) = 25_000.0 + drift * maxOf(0, m - 570)
        fun tv(m: Int) = 100.0 * (1 - 0.5 * (m - 570).coerceAtLeast(0) / 360.0)
        val ix = Series(null, 0.0, Right.IX, 0, mins, DoubleArray(mins.size) { spot(mins[it]) }, null, null, null, null, LongArray(mins.size))
        val om = mins.filter { it <= optionsUntil }.toIntArray()
        val opts = (-2..2).flatMap { j ->
            val k = 25_000.0 + 50 * j
            listOf(Right.CE, Right.PE).map { r ->
                val close = DoubleArray(om.size) { i ->
                    val s = spot(om[i]); (if (r == Right.CE) maxOf(0.0, s - k) else maxOf(0.0, k - s)) + tv(om[i]) }
                val high = spike?.let { sp -> DoubleArray(om.size) { i -> close[i] + if (om[i] == 720) sp else 0.0 } }
                Series(d.plusDays(expiry), k, r, 75, om, close, null, high, null, null, LongArray(om.size))
            }
        }
        return Session(d, 75, listOf(ix) + opts)
    }

    /** The [n] weekdays before today, oldest first. */
    private fun weekdays(n: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < n) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d; d = d.minusDays(1) }
        return out.reversed()
    }

    /** [n] sessions: flat, 36 points up, 180 points up, 180 points down by the end of the day, in turn. */
    private fun past(n: Int): List<Session> = weekdays(n).mapIndexed { i, d -> session(d, listOf(0.0, 0.1, 0.5, -0.5)[i % 4]) }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move|bounce)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(AtmBuy.Q(lead = AtmBuy.Aim.DOUBLE), AtmBuy.asked("how often does the atm option double from its 9:30 price before the end of the day"))
        assertEquals(AtmBuy.Q(right = Right.CE), AtmBuy.asked("how often does a bought atm call end the day worth more"))
        assertEquals(AtmBuy.Q(right = Right.PE, expiry = true, lead = AtmBuy.Aim.DOUBLE), AtmBuy.asked("how often does nifty's atm put double on expiry day"))
        assertEquals(AtmBuy.Q(lead = AtmBuy.Aim.DOUBLE), AtmBuy.asked("atm option double record for banknifty"))
        assertEquals(AtmBuy.Q(right = Right.CE, lead = AtmBuy.Aim.DOUBLE), AtmBuy.asked("atm call kitni baar double hota hai"))
        assertEquals(AtmBuy.Q(), AtmBuy.asked("what's the atm option buying hit rate"))
        assertEquals(AtmBuy.Q(), AtmBuy.asked("how often do at the money options end the day worth more than at 9:30"))
        // A forecast, advice, a seller's question, Boss's own trades, today or one past day, the straddle, a definition, the
        // expected move, gold and VIX, the quote itself - and the index's own reads.
        for (q in listOf("will the atm call double today", "should i buy the atm call", "how often does my atm call double",
                "has the atm call doubled today", "did the atm put double yesterday", "how often does the atm straddle gain by 2:30",
                "what is an atm option", "how often does selling the atm option work out", "how often does gold's atm call double",
                "how often does the vix atm call double", "atm call price", "how often does nifty go 1% from the open",
                "how fast do you answer", "how much does the atm straddle usually lose between 9:30 and 2:30",
                "what does the banknifty straddle imply for expiry", "how often does the atm option double if i buy at 9:30"))
            assertNull(AtmBuy.asked(q), q)
        assertNull(AtmBuy.market(listOf(Market.GOLD)))
        assertNull(AtmBuy.market(listOf(Market.VIX)))
        assertEquals(Market.NIFTY, AtmBuy.market(emptyList()))
        assertEquals(Market.BANKNIFTY, AtmBuy.market(listOf(Market.BANKNIFTY)))
    }

    @Test fun theRecord() {
        val flat = assertNotNull(AtmBuy.day(session(today.minusDays(1), 0.0)))
        assertEquals(25_000.0, flat.strike, 1e-9)
        assertFalse(flat.expiry)
        assertEquals(100.0, flat.ce.entry, 1e-9)
        assertFalse(flat.ce.up); assertFalse(flat.ce.doubled); assertFalse(flat.pe.up)
        val run = assertNotNull(AtmBuy.day(session(today.minusDays(1), 0.5)))
        assertTrue(run.ce.up); assertTrue(run.ce.doubled); assertTrue(run.ce.half)
        assertFalse(run.pe.up); assertFalse(run.pe.doubled)
        assertEquals(179.5 + 100 * (1 - 0.5 * 359 / 360.0), run.ce.end, 1e-9)
        // The candle's high counts as the best price reached.
        val spiked = assertNotNull(AtmBuy.day(session(today.minusDays(1), 0.0, spike = 150.0)))
        assertTrue(spiked.ce.doubled); assertFalse(spiked.ce.up)
        // The contract expiring that day: an expiry day.
        assertTrue(assertNotNull(AtmBuy.day(session(today.minusDays(1), 0.0, expiry = 0))).expiry)
        // No price at the end of the day (the chain stops at 12:00): no day.
        assertNull(AtmBuy.day(session(today.minusDays(1), 0.0, optionsUntil = 720)))
        // Streamed: today and later left out, the newest kept, oldest first.
        val ds = AtmBuy.days((past(20) + session(today, 0.0)).asSequence(), today)
        assertEquals(20, ds.size)
        assertTrue(ds.zipWithNext().all { (a, b) -> a.day.isBefore(b.day) })
        assertEquals(5, ds.count { it.ce.doubled }); assertEquals(5, ds.count { it.pe.doubled })
    }

    @Test fun theAnswer() {
        val ds = AtmBuy.days(past(20).asSequence(), today)
        val a = AtmBuy.answer(AtmBuy.Q(), Market.NIFTY, ds, session(today, 0.5, until = 660), today, today.atTime(11, 0))
        assertTrue(a.startsWith("Bought at 9:30 and held, Nifty's at-the-money call and put (the nearest expiry, the strike closest to Nifty at 9:30)"), a)
        assertTrue("over the last 20 sessions on this phone" in a && "expiry days apart" in a, a)
        assertTrue("the call ended the day worth more than at 9:30 on 5 (25%) and doubled from its 9:30 price at some point before the end of the day on 5 (25%)" in a, a)
        assertTrue("; the put ended the day worth more than at 9:30 on 5 (25%)" in a, a)
        assertTrue("The call touched one and a half times its 9:30 price on 5 (25%)" in a, a)
        assertTrue("At least one of the two doubled on 10 of the 20 (50%); both ended the day worth less on 10 (50%)." in a, a)
        assertTrue("a small record" in a, a)
        assertTrue("Today so far, the 25,000 call was 100.0 at 9:30, at best" in a, a)
        assertTrue("at 11:00" in a, a)
        assertFalse(ADVICE.containsMatchIn(a), a)
        assertTrue(a.endsWith(AtmBuy.NOTE), a)
        // Doubling asked first, and the put alone.
        val d = AtmBuy.answer(AtmBuy.Q(right = Right.PE, lead = AtmBuy.Aim.DOUBLE), Market.NIFTY, ds, null, today, today.atTime(16, 0))
        assertTrue("at-the-money put (" in d && ": the put doubled from its 9:30 price" in d, d)
        assertFalse("At least one of the two" in d, d)
        // The kept chain stopping early is said; a whole session today.
        val early = AtmBuy.answer(AtmBuy.Q(), Market.NIFTY, ds, session(today, 0.0, optionsUntil = 660), today, today.atTime(16, 0))
        assertTrue("Today, the kept chain stops at 11:00; up to there" in early, early)
        val whole = AtmBuy.answer(AtmBuy.Q(right = Right.CE), Market.NIFTY, ds, session(today, 0.5), today, today.atTime(16, 0))
        assertTrue("Today, the 25,000 call was 100.0 at 9:30" in whole && "doubled by then" in whole, whole)
        // Too few sessions, and expiry days asked with none kept.
        assertTrue(AtmBuy.answer(AtmBuy.Q(), Market.NIFTY, ds.take(5), null, today, today.atTime(12, 0))
            .startsWith("I have only 5 sessions of Nifty's option prices"))
        val ex = AtmBuy.answer(AtmBuy.Q(expiry = true), Market.SENSEX, ds, null, today, today.atTime(12, 0))
        assertTrue(ex.startsWith("I have only 0 expiry-day sessions of Sensex's option prices"), ex)
        assertTrue("I have 20 other sessions" in ex, ex)
    }
}
