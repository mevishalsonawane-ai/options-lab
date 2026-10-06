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

class OtmReachTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday

    /**
     * One session from 09:15 to [until] (minutes of the IST day): the index flat at 25,000 to 09:30, then [drift] points a
     * minute; the strikes 24,500 to 25,500 (50 apart) of an expiry [expiry] days away, each priced at its intrinsic value
     * plus a time value of 100 / (1 + strikes from 25,000) at 09:30 that loses half of itself by 15:30.
     */
    private fun session(d: LocalDate, drift: Double, expiry: Long = 2, until: Int = 930, optionsUntil: Int = until): Session {
        val mins = (555..until).toList().toIntArray()
        fun spot(m: Int) = 25_000.0 + drift * maxOf(0, m - 570)
        val ix = Series(null, 0.0, Right.IX, 0, mins, DoubleArray(mins.size) { spot(mins[it]) }, null, null, null, null, LongArray(mins.size))
        val om = mins.filter { it <= optionsUntil }.toIntArray()
        val opts = (-10..10).flatMap { j ->
            val k = 25_000.0 + 50 * j
            fun tv(m: Int) = 100.0 / (1 + abs(j)) * (1 - 0.5 * (m - 570).coerceAtLeast(0) / 360.0)
            listOf(Right.CE, Right.PE).map { r ->
                val close = DoubleArray(om.size) { i ->
                    val s = spot(om[i]); (if (r == Right.CE) maxOf(0.0, s - k) else maxOf(0.0, k - s)) + tv(om[i]) }
                Series(d.plusDays(expiry), k, r, 75, om, close, null, null, null, null, LongArray(om.size))
            }
        }
        return Session(d, 75, listOf(ix) + opts)
    }

    private fun abs(x: Int) = if (x < 0) -x else x

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
        assertEquals(OtmReach.Q(points = 100.0), OtmReach.asked("how often does an otm option 100 points away end the day in the money"))
        assertEquals(OtmReach.Q(right = Right.CE, strikes = 2), OtmReach.asked("how often does a call two strikes out of the money finish in the money"))
        assertEquals(OtmReach.Q(right = Right.PE, lead = OtmReach.Aim.DOUBLE), OtmReach.asked("how often does nifty's otm put double"))
        assertEquals(OtmReach.Q(), OtmReach.asked("otm option record for banknifty"))
        assertEquals(OtmReach.Q(right = Right.CE, points = 100.0), OtmReach.asked("100 point door ka otm call kitni baar itm hota hai"))
        assertEquals(OtmReach.Q(right = Right.PE, strikes = 3, expiry = true), OtmReach.asked("how often does a put 3 strikes away end in the money on expiry day"))
        assertEquals(OtmReach.Q(right = Right.CE, points = 200.0, lead = OtmReach.Aim.DOUBLE),
            OtmReach.asked("how often does an out of the money call 200 points away double from 9:30"))
        // A forecast, advice, a seller's question, Boss's own trades, today or one past day, the at-the-money option, a
        // definition, a conditional, gold and VIX - and the index's own reach from the open.
        for (q in listOf("will the otm call end in the money today", "should i hold the otm call", "how often does my otm call double",
                "did the otm put end in the money yesterday", "how often does the atm option double from its 9:30 price before the end of the day",
                "what is an otm option", "how often does selling an otm option work out", "how often does gold's otm call end in the money",
                "how often does the vix otm call double", "how often does nifty go 100 points from the open", "otm call price",
                "how often does an otm option end in the money if nifty gaps up", "how fast do you answer", "what does otm mean"))
            assertNull(OtmReach.asked(q), q)
        assertNull(OtmReach.market(listOf(Market.GOLD)))
        assertNull(OtmReach.market(listOf(Market.VIX)))
        assertEquals(Market.NIFTY, OtmReach.market(emptyList()))
        assertEquals(Market.BANKNIFTY, OtmReach.market(listOf(Market.BANKNIFTY)))
    }

    @Test fun theRecord() {
        val flat = assertNotNull(OtmReach.day(session(today.minusDays(1), 0.0)))
        assertEquals(25_000.0, flat.spot, 1e-9)
        assertEquals(50.0, flat.step, 1e-9)
        assertEquals(25_000.0, flat.atm, 1e-9)
        assertFalse(flat.expiry)
        val ce = assertNotNull(flat.leg(Right.CE, OtmReach.Q()))
        assertEquals(25_100.0, ce.strike, 1e-9)
        assertEquals(100.0 / 3, ce.entry, 1e-9)
        assertFalse(flat.itm(Right.CE, ce)); assertFalse(flat.reached(Right.CE, ce)); assertFalse(ce.doubled)
        assertEquals(24_850.0, assertNotNull(flat.leg(Right.PE, OtmReach.Q(strikes = 3))).strike, 1e-9)
        assertEquals(25_150.0, assertNotNull(flat.leg(Right.CE, OtmReach.Q(points = 120.0))).strike, 1e-9)
        assertEquals(24_850.0, assertNotNull(flat.leg(Right.PE, OtmReach.Q(points = 120.0))).strike, 1e-9)
        // Further out than the chain keeps: no leg.
        assertNull(flat.leg(Right.CE, OtmReach.Q(points = 900.0)))
        val run = assertNotNull(OtmReach.day(session(today.minusDays(1), 0.5)))
        val rc = assertNotNull(run.leg(Right.CE, OtmReach.Q()))
        assertTrue(run.itm(Right.CE, rc)); assertTrue(run.reached(Right.CE, rc)); assertTrue(rc.doubled); assertTrue(rc.up)
        val rp = assertNotNull(run.leg(Right.PE, OtmReach.Q()))
        assertFalse(run.itm(Right.PE, rp)); assertFalse(rp.up)
        // The contract expiring that day: an expiry day.
        assertTrue(assertNotNull(OtmReach.day(session(today.minusDays(1), 0.0, expiry = 0))).expiry)
        // No option price at the end of the day (the chain stops at 12:00): no leg kept, no day.
        assertNull(OtmReach.day(session(today.minusDays(1), 0.0, optionsUntil = 720)))
        // Streamed: today and later left out, the newest kept, oldest first.
        val ds = OtmReach.days((past(20) + session(today, 0.0)).asSequence(), today)
        assertEquals(20, ds.size)
        assertTrue(ds.zipWithNext().all { (a, b) -> a.day.isBefore(b.day) })
    }

    @Test fun theAnswer() {
        val ds = OtmReach.days(past(20).asSequence(), today)
        val a = OtmReach.answer(OtmReach.Q(), Market.NIFTY, ds, session(today, 0.5, until = 660), today, today.atTime(11, 0))
        assertTrue(a.startsWith("Held from 9:30, Nifty's out-of-the-money call and put (the nearest expiry) over the last 20 sessions on this phone"), a)
        assertTrue("expiry days apart" in a, a)
        assertTrue("the call (2 strikes above the at-the-money strike at 9:30) ended the day in the money on 5 of 20 (25%)" in a, a)
        assertTrue("; the put (2 strikes below the at-the-money strike at 9:30) ended the day in the money on 5 of 20 (25%)" in a, a)
        assertTrue("The call's strike was a median 100 points from Nifty at 9:30, Nifty reached it at some point on 5 (25%)" in a, a)
        assertTrue("a small record" in a, a)
        assertTrue("Today so far, the 25,100 call was 33.3 at 9:30 and" in a && "at 11:00" in a, a)
        assertTrue("points out of the money" in a || "in the money by" in a, a)
        assertFalse(ADVICE.containsMatchIn(a), a)
        assertTrue(a.endsWith(OtmReach.NOTE), a)
        // Points asked: further out ends in the money less often; doubling first; the put alone.
        val far = OtmReach.answer(OtmReach.Q(right = Right.CE, points = 200.0), Market.NIFTY, ds, null, today, today.atTime(16, 0))
        assertTrue("the call (the first strike at least 200 points above the index at 9:30) ended the day in the money on 0 of 20 (0%)" in far, far)
        val d = OtmReach.answer(OtmReach.Q(right = Right.PE, lead = OtmReach.Aim.DOUBLE), Market.NIFTY, ds, null, today, today.atTime(16, 0))
        assertTrue("out-of-the-money put (" in d && "at 9:30) doubled from its 9:30 price" in d, d)
        // Too far for the kept chain, too few sessions, and expiry days asked with none kept.
        val none = OtmReach.answer(OtmReach.Q(points = 900.0), Market.NIFTY, ds, null, today, today.atTime(12, 0))
        assertTrue(none.startsWith("I have only 0 sessions of Nifty's option prices on the phone with a price kept for a strike that far out"), none)
        assertTrue(OtmReach.answer(OtmReach.Q(), Market.NIFTY, ds.take(5), null, today, today.atTime(12, 0))
            .startsWith("I have only 5 sessions of Nifty's option prices"))
        val ex = OtmReach.answer(OtmReach.Q(expiry = true), Market.SENSEX, ds, null, today, today.atTime(12, 0))
        assertTrue(ex.startsWith("I have only 0 expiry-day sessions of Sensex's option prices"), ex)
        assertTrue("I have 20 other sessions" in ex, ex)
        // The kept chain stopping early is said.
        val early = OtmReach.answer(OtmReach.Q(), Market.NIFTY, ds, session(today, 0.0, optionsUntil = 660), today, today.atTime(16, 0))
        assertTrue("Today, the kept chain stops at 11:00; up to there" in early, early)
    }
}
