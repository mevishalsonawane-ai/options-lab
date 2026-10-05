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

class StraddleDecayTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    /**
     * One session from 09:15 to [until] (minutes of the IST day): the index flat at 25,000 to 09:30, then [drift] points a
     * minute; the strikes 24,900 to 25,100 of an expiry [expiry] days away, each priced at its intrinsic value plus a time
     * value of 100 at 09:30 that loses half of itself by 15:30.
     */
    private fun session(d: LocalDate, drift: Double, expiry: Long = 2, until: Int = 930, optionsUntil: Int = until): Session {
        val mins = (555..until).toList().toIntArray()
        fun spot(m: Int) = 25_000.0 + drift * maxOf(0, m - 570)
        fun tv(m: Int) = 100.0 * (1 - 0.5 * (m - 570).coerceAtLeast(0) / 360.0)
        val ix = Series(null, 0.0, Right.IX, 0, mins, DoubleArray(mins.size) { spot(mins[it]) }, null, null, null, null, LongArray(mins.size))
        val om = mins.filter { it <= optionsUntil }.toIntArray()
        val opts = (-2..2).flatMap { j ->
            val k = 25_000.0 + 50 * j
            listOf(Right.CE, Right.PE).map { r ->
                Series(d.plusDays(expiry), k, r, 75, om, DoubleArray(om.size) { i ->
                    val s = spot(om[i]); (if (r == Right.CE) maxOf(0.0, s - k) else maxOf(0.0, k - s)) + tv(om[i]) }, null, null, null, null, LongArray(om.size))
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

    /** [n] sessions: flat, 30 points, 150 points from 09:30 to 14:30, in turn. */
    private fun past(n: Int): List<Session> = weekdays(n).mapIndexed { i, d -> session(d, listOf(0.0, 0.1, 0.5)[i % 3]) }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move|bounce)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(StraddleDecay.Q(), StraddleDecay.asked("how much does the atm straddle usually lose between 9:30 and 2:30"))
        assertEquals(StraddleDecay.Q(lead = StraddleDecay.Kind.QUIET),
            StraddleDecay.asked("how much does nifty's straddle decay on a quiet day vs a trending day"))
        assertEquals(StraddleDecay.Q(lead = StraddleDecay.Kind.TREND),
            StraddleDecay.asked("on a trending day how much does the banknifty straddle usually lose"))
        assertEquals(StraddleDecay.Q(), StraddleDecay.asked("straddle decay record for banknifty"))
        assertEquals(StraddleDecay.Q(), StraddleDecay.asked("how often does the atm straddle gain by 2:30"))
        assertEquals(StraddleDecay.Q(expiry = true), StraddleDecay.asked("on expiry day how much does the straddle usually lose"))
        assertEquals(StraddleDecay.Q(), StraddleDecay.asked("nifty ka straddle din mein kitna girta hai"))
        assertEquals(StraddleDecay.Q(), StraddleDecay.asked("how much do at the money option premiums usually decay intraday"))
        // A forecast, advice, Boss's own book, today or one past day, the straddle's expected move or IV, a definition, other
        // spreads, gold and VIX, Jarvis's own speed - and the index's own reads.
        for (q in listOf("how much will the straddle lose today", "should i buy the straddle", "how much is my straddle losing",
                "how much has the straddle lost today", "how much did the straddle lose yesterday", "what does the straddle imply",
                "what's the expected move from the straddle", "what is a straddle", "explain straddle decay",
                "how much does a strangle usually lose in a day", "how much does gold's straddle usually lose",
                "how much does the vix straddle usually decay", "how fast do you answer", "what's my theta",
                "how much am i losing to time decay", "how much does nifty usually give back after a 100 point run",
                "how long does nifty usually take to move 50 points", "atm straddle price"))
            assertNull(StraddleDecay.asked(q), q)
        assertNull(StraddleDecay.market(listOf(Market.GOLD)))
        assertNull(StraddleDecay.market(listOf(Market.VIX)))
        assertEquals(Market.NIFTY, StraddleDecay.market(emptyList()))
        assertEquals(Market.BANKNIFTY, StraddleDecay.market(listOf(Market.BANKNIFTY)))
    }

    @Test fun theRecord() {
        val quiet = assertNotNull(StraddleDecay.day(session(today.minusDays(1), 0.0)))
        assertEquals(25_000.0, quiet.strike, 1e-9)
        assertFalse(quiet.expiry)
        assertEquals(200.0, quiet.a, 1e-9)
        assertEquals(2 * (100 - 50.0 * 300 / 360), quiet.b, 1e-9)
        assertEquals(0.0, quiet.move, 1e-9)
        val trend = assertNotNull(StraddleDecay.day(session(today.minusDays(1), 0.5)))
        assertEquals(150.0 + 2 * (100 - 50.0 * 300 / 360), trend.b, 1e-9)
        assertEquals(0.6, trend.move, 1e-9)
        assertTrue(trend.change > 0)
        // The contract expiring that day: an expiry day.
        assertTrue(assertNotNull(StraddleDecay.day(session(today.minusDays(1), 0.0, expiry = 0))).expiry)
        // No price at 14:30 (the chain stops at 12:00): no day.
        assertNull(StraddleDecay.day(session(today.minusDays(1), 0.0, optionsUntil = 720)))
        // Streamed: today and later left out, the newest kept, oldest first.
        val ds = StraddleDecay.days((past(21) + session(today, 0.0)).asSequence(), today)
        assertEquals(21, ds.size)
        assertTrue(ds.zipWithNext().all { (a, b) -> a.day.isBefore(b.day) })
        val (q, t, cut) = assertNotNull(StraddleDecay.thirds(ds))
        assertEquals(7, q.size); assertEquals(7, t.size)
        assertTrue(q.all { it.move == 0.0 }); assertTrue(t.all { it.move > 0.5 })
        assertEquals(0.0, cut.first, 1e-9); assertEquals(0.6, cut.second, 1e-9)
        val g = StraddleDecay.group(ds)
        assertEquals(7, g.gained)
    }

    @Test fun theAnswer() {
        val ds = StraddleDecay.days(past(21).asSequence(), today)
        val a = StraddleDecay.answer(StraddleDecay.Q(), Market.NIFTY, ds, session(today, 0.1, until = 660), today, today.atTime(11, 0))
        assertTrue(a.startsWith("From 9:30 to 14:30, Nifty's at-the-money straddle"), a)
        assertTrue("lost 27% at the median over the last 21 sessions on this phone" in a, a)
        assertTrue("expiry days apart" in a, a)
        assertTrue("On the quietest third (7 sessions, Nifty within 0.00% of its 9:30 price at 14:30) it lost 42% at the median and gained on 0 (0%)" in a, a)
        assertTrue("on the third that moved most (7 sessions, 0.60% or more) it gained 33% at the median and gained on 7 (100%)" in a, a)
        assertTrue("worth more at 14:30 than at 9:30 on 7 of the 21 (33%); on those, Nifty had moved a median 0.60%" in a, a)
        assertTrue("a small record" in a, a)
        assertTrue("Today so far, the 25,000 straddle was 200.0 at 9:30 and" in a, a)
        assertTrue("at 11:00" in a, a)
        assertFalse(ADVICE.containsMatchIn(a), a)
        assertTrue(a.endsWith(StraddleDecay.NOTE), a)
        // A trending day asked first: that third leads.
        val tr = StraddleDecay.answer(StraddleDecay.Q(lead = StraddleDecay.Kind.TREND), Market.NIFTY, ds, null, today, today.atTime(16, 0))
        assertTrue("On the third that moved most (7 sessions" in tr, tr)
        // The kept chain stopping early is said.
        val early = StraddleDecay.answer(StraddleDecay.Q(), Market.NIFTY, ds, session(today, 0.0, optionsUntil = 660), today, today.atTime(16, 0))
        assertTrue("Today, the kept chain stops at 11:00; up to there" in early, early)
        // A whole session today.
        val whole = StraddleDecay.answer(StraddleDecay.Q(), Market.NIFTY, ds, session(today, 0.0), today, today.atTime(16, 0))
        assertTrue("Today, the 25,000 straddle was 200.0 at 9:30 and 116.7 at 14:30 - it lost 42%" in whole, whole)
        // Too few sessions, and expiry days asked with none kept.
        assertTrue(StraddleDecay.answer(StraddleDecay.Q(), Market.NIFTY, ds.take(5), null, today, today.atTime(12, 0))
            .startsWith("I have only 5 sessions of Nifty's option prices"))
        val ex = StraddleDecay.answer(StraddleDecay.Q(expiry = true), Market.SENSEX, ds, null, today, today.atTime(12, 0))
        assertTrue(ex.startsWith("I have only 0 expiry-day sessions of Sensex's option prices"), ex)
    }
}
