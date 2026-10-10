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

class ExpiryHourTest {
    private val today = LocalDate.of(2026, 10, 6)   // a Tuesday

    /**
     * One session from 09:15 to [until] (minutes of the IST day): the index flat at 25,000 to 14:30, then [drift] points a
     * minute; the strikes 24,900 to 25,100 of an expiry [expiry] days away, each priced at its intrinsic value plus a time
     * value of 40 at 14:30 that falls to [left] of itself by 15:29. [spike] adds that much to each candle's high at 15:00.
     */
    private fun session(d: LocalDate, drift: Double, expiry: Long = 0, left: Double = 0.1, until: Int = 930, optionsUntil: Int = until,
                        spike: Double? = null): Session {
        val mins = (555..until).toList().toIntArray()
        fun spot(m: Int) = 25_000.0 + drift * maxOf(0, m - 870)
        fun tv(m: Int) = 40.0 * (1 - (1 - left) * (m - 870).coerceIn(0, 59) / 59.0)
        val ix = Series(null, 0.0, Right.IX, 0, mins, DoubleArray(mins.size) { spot(mins[it]) }, null, null, null, null, LongArray(mins.size))
        val om = mins.filter { it <= optionsUntil }.toIntArray()
        val opts = (-2..2).flatMap { j ->
            val k = 25_000.0 + 50 * j
            listOf(Right.CE, Right.PE).map { r ->
                val close = DoubleArray(om.size) { i ->
                    val s = spot(om[i]); (if (r == Right.CE) maxOf(0.0, s - k) else maxOf(0.0, k - s)) + tv(om[i]) }
                val high = spike?.let { sp -> DoubleArray(om.size) { i -> close[i] + if (om[i] == 900) sp else 0.0 } }
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

    /** [n] weekdays: every fifth an expiry (flat, 2 points a minute up, 2 down, flat, in turn), the rest ordinary flat days. */
    private fun past(n: Int): List<Session> {
        var e = 0
        return weekdays(n).mapIndexed { i, d ->
            if (i % 5 == 4) session(d, listOf(0.0, 2.0, -2.0, 0.0)[e++ % 4]) else session(d, 0.0, expiry = 3, left = 0.8)
        }
    }

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|buy|sell|go long|go short|will (rise|fall|go|break|move|bounce)|likely|expect|target)\\b")

    @Test fun theQuestions() {
        assertEquals(ExpiryHour.Q(), ExpiryHour.asked("how does the atm option's premium behave in the last hour on expiry day"))
        assertEquals(ExpiryHour.Q(right = Right.CE), ExpiryHour.asked("how much does the atm call lose in the last hour of expiry"))
        assertEquals(ExpiryHour.Q(right = Right.PE, lead = ExpiryHour.Aim.DOUBLE),
            ExpiryHour.asked("how often does the atm put double in the final hour on expiry"))
        assertEquals(ExpiryHour.Q(), ExpiryHour.asked("expiry last hour premium record for banknifty"))
        assertEquals(ExpiryHour.Q(), ExpiryHour.asked("expiry ke aakhri ghante mein atm premium kitna girta hai"))
        assertEquals(ExpiryHour.Q(), ExpiryHour.asked("what usually happens to at the money premiums after 2:30 on expiry days"))
        // A forecast, advice, Boss's own, today or one past expiry, the straddle, the pin, gold and VIX, the index's own last
        // hour, the day from 9:30, a definition.
        for (q in listOf("will the atm call double in the last hour of expiry today", "should i hold the atm put into the last hour of expiry",
                "how did the atm call do in the last hour of last expiry", "how often does my atm call double in the last hour on expiry",
                "how much does the straddle lose in the last hour on expiry", "how often does gold's atm call double in the last hour of expiry",
                "how often does the vix atm call double in the last hour of expiry", "does nifty usually reverse in the last hour on expiry day",
                "how often does nifty pin to max pain in the last hour of expiry", "how often does the atm option double from its 9:30 price before the end of the day",
                "does nifty usually reverse in the last hour", "what is the last hour on expiry", "atm call price", "how is expiry going"))
            assertNull(ExpiryHour.asked(q), q)
        assertNull(ExpiryHour.market(listOf(Market.GOLD)))
        assertNull(ExpiryHour.market(listOf(Market.VIX)))
        assertEquals(Market.NIFTY, ExpiryHour.market(emptyList()))
        assertEquals(Market.SENSEX, ExpiryHour.market(listOf(Market.SENSEX)))
    }

    @Test fun theRecord() {
        val flat = assertNotNull(ExpiryHour.day(session(today.minusDays(1), 0.0)))
        assertTrue(flat.expiry)
        assertEquals(25_000.0, flat.strike, 1e-9)
        assertEquals(40.0, flat.ce.entry, 1e-9)
        assertEquals(4.0, flat.ce.end, 1e-9)
        assertTrue(flat.ce.halved); assertFalse(flat.ce.up); assertFalse(flat.ce.doubled)
        assertEquals(-0.9, flat.both, 1e-9)
        // Two points a minute up for the hour: the call doubles and ends worth more, the put goes to its little time value.
        val run = assertNotNull(ExpiryHour.day(session(today.minusDays(1), 2.0)))
        assertTrue(run.ce.up); assertTrue(run.ce.doubled); assertTrue(run.pe.halved)
        assertEquals(118.0, run.ixEnd - run.ixAt, 1e-9)
        // The candle's high counts as the best price reached.
        assertTrue(assertNotNull(ExpiryHour.day(session(today.minusDays(1), 0.0, spike = 60.0))).ce.doubled)
        // Not the expiring contract: not an expiry day.
        assertFalse(assertNotNull(ExpiryHour.day(session(today.minusDays(1), 0.0, expiry = 3))).expiry)
        // No option price at the end of the day (the chain stops at 14:00): no day.
        assertNull(ExpiryHour.day(session(today.minusDays(1), 0.0, optionsUntil = 840)))
        // Streamed: today and later left out, expiry days and the rest both kept, oldest first.
        val ds = ExpiryHour.days((past(60) + session(today, 0.0)).asSequence(), today)
        assertEquals(60, ds.size)
        assertEquals(12, ds.count { it.expiry })
        assertTrue(ds.zipWithNext().all { (a, b) -> a.day.isBefore(b.day) })
    }

    @Test fun theAnswer() {
        val ds = ExpiryHour.days(past(60).asSequence(), today)
        val a = ExpiryHour.answer(ExpiryHour.Q(), Market.NIFTY, ds, session(today, 0.0, until = 900), today, today.atTime(15, 0))
        assertTrue(a.startsWith("On the last 12 expiry days of Nifty on this phone ("), a)
        assertTrue("the expiring at-the-money call and put (the strike closest to Nifty at 14:30) from 14:30 to the end of the day: " in a, a)
        assertTrue("the call ended the hour a median down 90%, lost half or more on 9 (75%) and ended worth more on 3 (25%); " +
            "it touched twice its 14:30 price at some point in the hour on 3 (25%)" in a, a)
        assertTrue("at least one of the two doubled on 6 (50%)." in a, a)
        assertTrue("Nifty itself moved a median 59 points in that hour, the most 118 points on" in a, a)
        assertTrue("On the 48 other days, the same hour left the call a median down 20% and the put a median down 20%." in a, a)
        assertTrue("12 expiry days is a small record" in a, a)
        assertTrue("Today's expiry so far, the 25,000 call was 40.0 at 14:30 and " in a && " at 15:00 (down 46%)" in a, a)
        assertFalse(ADVICE.containsMatchIn(a), a)
        assertTrue(a.endsWith(ExpiryHour.NOTE), a)
        // Doubling asked first, the put alone; no today line on a day that is not an expiry.
        val d = ExpiryHour.answer(ExpiryHour.Q(right = Right.PE, lead = ExpiryHour.Aim.DOUBLE), Market.NIFTY, ds,
            session(today, 0.0, expiry = 3), today, today.atTime(15, 0))
        assertTrue("at-the-money put (" in d && ": the put touched twice its 14:30 price" in d, d)
        assertFalse("at least one of the two" in d || "Today" in d, d)
        // Before 14:30 on an expiry; the kept chain stopping early.
        assertTrue("Today is an expiry; its last hour starts at 14:30." in
            ExpiryHour.answer(ExpiryHour.Q(), Market.NIFTY, ds, session(today, 0.0, until = 780), today, today.atTime(13, 0)))
        val early = ExpiryHour.answer(ExpiryHour.Q(right = Right.CE), Market.NIFTY, ds, session(today, 0.0, optionsUntil = 900), today, today.atTime(16, 0))
        assertTrue("Today's expiry, the kept chain stops at 15:00; up to there" in early, early)
        // Too few expiry days.
        val few = ExpiryHour.answer(ExpiryHour.Q(), Market.BANKNIFTY, ds.filter { !it.expiry } + ds.filter { it.expiry }.take(3), null, today, today.atTime(12, 0))
        assertTrue(few.startsWith("I have only 3 expiry days of BankNifty's option prices"), few)
        assertTrue("I have 48 other sessions" in few, few)
    }
}
