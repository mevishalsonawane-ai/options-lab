package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PastReadsTest {
    private val today = LocalDate.of(2026, 10, 7)

    @BeforeTest fun reset() = PastReads.forget()
    @AfterTest fun after() = PastReads.forget()

    /** One full session (09:15 to 15:30): the index drifting [drift] points a minute from 09:30, five strikes of an expiry [expiry] days away. */
    private fun session(d: LocalDate, drift: Double, expiry: Long): Session {
        val mins = (555..930).toList().toIntArray()
        fun spot(m: Int) = 25_000.0 + drift * maxOf(0, m - 570)
        fun tv(m: Int) = 100.0 * (1 - 0.5 * (m - 570).coerceAtLeast(0) / 360.0)
        val ix = Series(null, 0.0, Right.IX, 0, mins, DoubleArray(mins.size) { spot(mins[it]) }, null, null, null, null, LongArray(mins.size))
        val opts = (-2..2).flatMap { j ->
            val k = 25_000.0 + 50 * j
            listOf(Right.CE, Right.PE).map { r ->
                val close = DoubleArray(mins.size) { i ->
                    val s = spot(mins[i]); (if (r == Right.CE) maxOf(0.0, s - k) else maxOf(0.0, k - s)) + tv(mins[i]) }
                val high = DoubleArray(mins.size) { i -> close[i] + if (mins[i] == 900) 300.0 else 0.0 }
                Series(d.plusDays(expiry), k, r, 75, mins, close, null, high, null, null, LongArray(mins.size))
            }
        }
        return Session(d, 75, listOf(ix) + opts)
    }

    /** [n] weekdays before today and today itself, every fifth an expiry day. */
    private fun sessions(n: Int): List<Session> {
        val days = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (days.size < n) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) days += d; d = d.minusDays(1) }
        return days.reversed().mapIndexed { i, x -> session(x, listOf(0.0, 0.1, 0.5, -0.5)[i % 4], if (i % 5 == 0) 0 else 2) } + session(today, 0.2, 1)
    }

    /** [list] as a stream that counts the sessions it hands out (the decoding work). */
    private class Counted(private val list: List<Session>) {
        var handed = 0
        fun stream(): Sequence<Session> = list.asSequence().map { handed++; it }
    }

    @Test fun oneStreamGivesWhatEachReadGivesAlone() {
        val all = sessions(40)
        val r = PastReads.read(all.asSequence(), today)
        assertEquals(StraddleDecay.days(all.asSequence(), today), r.straddleDays)
        assertEquals(ExpiryHour.days(all.asSequence(), today), r.expiryHourDays)
        assertEquals(AtmBuy.days(all.asSequence(), today), r.atmBuyDays)
        assertTrue(r.straddleDays.isNotEmpty() && r.atmBuyDays.isNotEmpty() && r.expiryHourDays.any { it.expiry })
        // The same answers as before, word for word.
        val m = Market.NIFTY
        val now = today.atTime(15, 0)
        assertEquals(StraddleDecay.answer(StraddleDecay.Q(), m, StraddleDecay.days(all.asSequence(), today), all.last(), today, now),
            StraddleDecay.answer(StraddleDecay.Q(), m, r.straddleDays, all.last(), today, now))
        assertEquals(AtmBuy.answer(AtmBuy.Q(), m, AtmBuy.days(all.asSequence(), today), all.last(), today, now),
            AtmBuy.answer(AtmBuy.Q(), m, r.atmBuyDays, all.last(), today, now))
    }

    @Test fun threeDifferentReadsStreamTheSessionsOnce() {
        val all = sessions(30)
        val src = Counted(all)
        val key = "$today|0|null"
        val straddle = PastReads.of("NIFTY", key, today, src::stream).straddleDays
        val hour = PastReads.of("NIFTY", key, today, src::stream).expiryHourDays
        val atm = PastReads.of("NIFTY", key, today, src::stream).atmBuyDays
        assertEquals(1, PastReads.streamCount)
        assertEquals(all.size, src.handed)                  // before: three streams, 3 x the sessions decoded
        assertEquals(StraddleDecay.days(all.asSequence(), today), straddle)
        assertEquals(ExpiryHour.days(all.asSequence(), today), hour)
        assertEquals(AtmBuy.days(all.asSequence(), today), atm)
        assertSame(PastReads.of("NIFTY", key, today, src::stream), PastReads.of("NIFTY", key, today, src::stream))
        assertEquals(1, PastReads.streamCount)
    }

    @Test fun anotherMarketOrNewKeptDayReadsAfresh() {
        val src = Counted(sessions(10))
        PastReads.of("NIFTY", "a", today, src::stream)
        PastReads.of("BANKNIFTY", "a", today, src::stream)
        assertEquals(2, PastReads.streamCount)
        PastReads.of("NIFTY", "a", today, src::stream)
        PastReads.of("BANKNIFTY", "a", today, src::stream)
        assertEquals(2, PastReads.streamCount)              // each market kept on its own
        PastReads.of("NIFTY", "b", today, src::stream)      // a new harvested day (or a new day): read again
        assertEquals(3, PastReads.streamCount)
    }

    @Test fun aFailedStreamIsNotKept() {
        val boom = { sequence<Session> { yield(sessions(1).first()); throw java.io.IOException("cut") } }
        assertFailsWith<java.io.IOException> { PastReads.of("NIFTY", "k", today, boom) }
        val src = Counted(sessions(5))
        PastReads.of("NIFTY", "k", today, src::stream)
        assertEquals(2, PastReads.streamCount)
        assertEquals(6, src.handed)
    }

    @Test fun forgetResets() {
        PastReads.of("NIFTY", "k", today) { emptySequence() }
        PastReads.forget()
        assertEquals(0, PastReads.streamCount)
        PastReads.of("NIFTY", "k", today) { emptySequence() }
        assertEquals(1, PastReads.streamCount)
    }
}
