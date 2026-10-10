package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reasoning, round 30: the index move that covers the rest of today's decay on a bought option, beside the phone's record. */
class NeedsTrueCoverTest {
    private val at = LocalDateTime.of(2026, 10, 6, 13, 15)          // a Tuesday
    private val week = { d: LocalDate -> d.dayOfWeek.value <= 5 }
    private val put = PositionHealth.Pos("Paper", "NIFTY2610824500PE", 75, 80.0, 60.0, "NIFTY", 24500.0, "PE", LocalDate.of(2026, 10, 8),
        spot = 24650.0, theta = -6.0)
    private val call = PositionHealth.Pos("Paper", "NIFTY2610824700CE", 75, 70.0, 65.0, "NIFTY", 24700.0, "CE", LocalDate.of(2026, 10, 8),
        spot = 24650.0, theta = -7.5)
    private val ADVICE = Regex("(?i)\\b(should|recommend|suggest|will (rise|fall)|likely|exit|hold on)\\b")

    /**
     * [n] weekday sessions before [at] of 375 1-minute candles at 100, except [moved] of them, which from 13:30 rise to
     * [peak] by 13:45 and close at [close].
     */
    private fun sessions(n: Int, moved: Int, peak: Double, close: Double): List<Candle> {
        val days = ArrayList<LocalDate>()
        var d = at.toLocalDate()
        while (days.size < n) { d = d.minusDays(1); if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) days += d }
        days.reverse()
        return days.withIndex().flatMap { (k, day) -> (0 until 375).map { i ->
            val t = LocalDateTime.of(day, LocalTime.of(9, 15)).plusMinutes(i.toLong())
            val tm = t.toLocalTime()
            val p = if (k >= moved || tm.isBefore(LocalTime.of(13, 30))) 100.0
                else if (tm.isBefore(LocalTime.of(13, 45))) 100.2
                else if (tm == LocalTime.of(13, 45)) peak
                else close
            Candle(t, p, maxOf(p, if (tm == LocalTime.of(13, 45) && k < moved) peak else p), p, p)
        } }
    }

    @Test fun decayLeftToday() {
        assertEquals(2.16, NeedsTrue.decayLeftToday(-6.0, 135, false, null)!!, 1e-9)
        assertEquals(6.0, NeedsTrue.decayLeftToday(-6.0, 375, false, null)!!, 1e-9)
        assertEquals(6.0, NeedsTrue.decayLeftToday(-6.0, 400, false, null)!!, 1e-9)       // never more than one day's
        assertEquals(12.0, NeedsTrue.decayLeftToday(-6.0, 100, true, 12.0)!!, 1e-9)       // expiry day: the time value left
        assertNull(NeedsTrue.decayLeftToday(-6.0, 0, false, null))
        assertNull(NeedsTrue.decayLeftToday(null, 100, false, null))
        assertNull(NeedsTrue.decayLeftToday(-6.0, 100, true, 0.0))
    }

    @Test fun coverRecord() {
        // 10 sessions, 4 of which rise 1% by 13:45 (30 minutes after 13:15) and close 0.5% up.
        val bars = sessions(10, 4, 101.0, 100.5)
        val r = NeedsTrue.coverRecord(bars, at.toLocalDate(), LocalTime.of(13, 15), 0.004, up = true)
        assertEquals(NeedsTrue.Cover(10, 4, 4, 30), r)
        // 0.8% up: reached on the 4 (the high), held on none (they close 0.5% up).
        assertEquals(NeedsTrue.Cover(10, 4, 0, 30), NeedsTrue.coverRecord(bars, at.toLocalDate(), LocalTime.of(13, 15), 0.008, up = true))
        // Down: never.
        assertEquals(NeedsTrue.Cover(10, 0, 0, null), NeedsTrue.coverRecord(bars, at.toLocalDate(), LocalTime.of(13, 15), 0.004, up = false))
        // From 13:40 the move is 0.2% already in: 1.0/100.2 is about 0.8%, so 0.5% is reached at 13:45, 5 minutes on.
        assertEquals(NeedsTrue.Cover(10, 4, 0, 5), NeedsTrue.coverRecord(bars, at.toLocalDate(), LocalTime.of(13, 40), 0.005, up = true))
        // Today's session and short sessions are left out.
        assertEquals(0, NeedsTrue.coverRecord(bars.filter { it.t.minute % 2 == 0 }, at.toLocalDate(), LocalTime.NOON, 0.004, true).days)
        assertEquals(0, NeedsTrue.coverRecord(bars, bars.first().t.toLocalDate(), LocalTime.NOON, 0.004, true).days)
    }

    @Test fun aBoughtCallSaidWithItsRecord() {
        val bars = sessions(10, 4, 101.0, 100.5)
        val s = NeedsTrue.thetaCover(call, bars, at, week)
        assertNotNull(s)
        val delta = NeedsTrue.deltaNow(call, at)!!
        assertTrue(delta > 0.1 && delta < 0.6, delta.toString())
        // 7.5 a day, 135 of 375 minutes left: 2.70 a unit, Rs 203 on 75.
        val points = 2.7 / delta
        assertTrue(s.startsWith("Covering today's decay: about Rs 203 of decay is still to come on the position today " +
            "(one day's theta spread over the session, 2 h 15 min left). At its delta of ${"%.2f".format(java.util.Locale.ENGLISH, delta)} now, " +
            "Nifty has to move about ${"%,.0f".format(java.util.Locale.ENGLISH, points)} points"), s)
        assertTrue(s.contains(" up, your way, just to make that back."), s)
        assertTrue(s.contains("On the record from 13:15, it went that far up before the close on 4 of the last 10 whole sessions on the phone (40%), " +
            // About 0.03%: the 0.2% step at 13:30 is already that far, 15 minutes on; the 0.5% close holds it.
            "at a median of 15 min in, and was still that far up at the close on 4 (40%)."), s)
        assertTrue(s.endsWith("Delta held still - gamma, IV and the clock move it."), s)
        assertFalse(ADVICE.containsMatchIn(s), s)
    }

    @Test fun onlyABoughtOptionInALiveSession() {
        val bars = sessions(10, 4, 101.0, 100.5)
        assertNull(NeedsTrue.thetaCover(put.copy(qty = -75), bars, at, week))                    // sold: decay is his
        assertNull(NeedsTrue.thetaCover(put, bars, at.withHour(16), week))                        // after the close
        assertNull(NeedsTrue.thetaCover(put, bars, at.withDayOfMonth(4), week))                   // a Sunday
        assertNull(NeedsTrue.thetaCover(put.copy(spot = null), bars, at, week))
        assertNull(NeedsTrue.thetaCover(put.copy(theta = null), bars, at, week))
        assertNull(NeedsTrue.thetaCover(PositionHealth.Pos("Zerodha", "RELIANCE26OCTFUT", 500, 2900.0, 2950.0), bars, at, week))
        // A far out-of-the-money option with next to no delta is left out.
        assertNull(NeedsTrue.thetaCover(put.copy(strike = 22000.0, ltp = 0.3, avg = 1.0), bars, at, week))
        // Too few sessions on the phone: said so.
        val few = NeedsTrue.thetaCover(put, sessions(5, 0, 100.0, 100.0), at, week)!!
        assertTrue(few.endsWith("Too few whole sessions of Nifty on the phone (5) to say how often it moved that far from this time of day."), few)
    }

    @Test fun beforeTheOpenAndOnExpiryDay() {
        val bars = sessions(10, 0, 100.0, 100.0)
        val pre = NeedsTrue.thetaCover(put, bars, at.withHour(8).withMinute(50), week)!!
        // The whole session ahead: one day's theta, Rs 450 on 75.
        assertTrue(pre.startsWith("Covering today's decay: about Rs 450 of decay is still to come on the position today " +
            "(one day's theta spread over the session, 6 h 15 min left)."), pre)
        assertTrue(pre.contains("On the record from the open, it went that far down before the close on 0 of the last 10"), pre)
        // Expiring today: the time value left (60 - 0 intrinsic, 150 points out of the money) is what goes by 15:30.
        val exp = put.copy(expiry = at.toLocalDate())
        val e = NeedsTrue.thetaCover(exp, bars, at.withHour(14), week)
        if (NeedsTrue.deltaNow(exp, at.withHour(14))?.let { abs(it) >= NeedsTrue.MIN_DELTA } == true) {
            assertNotNull(e)
            assertTrue(e.startsWith("Covering today's decay: the Rs 4,500 of time value left on the position is gone by 15:30."), e)
        } else assertNull(e)
    }
}
