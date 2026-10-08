package com.optionslab.engine.risk

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The account day lock (08 Oct, research/PROFIT_LOCK_8OCT.md fix 5): new automatic entries stop at +Rs 8,000 for the day. */
class DayLockTest {
    private val day = LocalDate.of(2026, 10, 8)

    @Test fun reachingTheAmountLocksTheRestOfTheDay() {
        assertNull(DayLock.next(null, day, LocalTime.of(14, 4), 8_000.0, 7_658.0), "below it: no lock")
        val r = DayLock.next(null, day, LocalTime.of(14, 11, 37), 8_000.0, 8_001.0)!!
        assertEquals(LocalTime.of(14, 11), r.at)
        assertEquals(8_000.0, r.limit)
        assertEquals(8_001.0, r.pnl)
        // It holds for the day, even when the P&L falls back (Boss's +8k that fell to +3k).
        assertEquals(r, DayLock.next(r, day, LocalTime.of(14, 15), 8_000.0, 3_364.0))
        assertEquals(r, DayLock.next(r, day, LocalTime.of(14, 15), 8_000.0, null))
        assertTrue(DayLock.active(r, day, 8_000.0))
        // The next day starts unlocked.
        assertNull(DayLock.next(r, day.plusDays(1), LocalTime.of(9, 20), 8_000.0, 100.0))
        assertFalse(DayLock.active(r, day.plusDays(1), 8_000.0))
        // Exactly at the amount counts.
        assertEquals(8_000.0, DayLock.next(null, day, LocalTime.NOON, 8_000.0, 8_000.0)!!.pnl)
    }

    @Test fun zeroIsOffAndAnUnknownPnlNeverLocks() {
        assertNull(DayLock.next(null, day, LocalTime.NOON, 0.0, 50_000.0))
        assertNull(DayLock.next(null, day, LocalTime.NOON, 8_000.0, Double.NaN))
        assertNull(DayLock.next(null, day, LocalTime.NOON, 8_000.0, null))
        val r = DayLock.next(null, day, LocalTime.NOON, 5_000.0, 5_500.0)!!
        assertFalse(DayLock.active(r, day, 0.0), "switched off in Bot settings: lifted")
        assertFalse(DayLock.active(null, day, 8_000.0))
    }

    @Test fun aBrokenAmountIsTheDefaultNeverOff() {
        assertEquals(DayLock.DEFAULT_RUPEES, DayLock.clean(Double.NaN))
        assertEquals(DayLock.DEFAULT_RUPEES, DayLock.clean(Double.POSITIVE_INFINITY))
        assertEquals(DayLock.DEFAULT_RUPEES, DayLock.clean(-1.0))
        assertEquals(0.0, DayLock.clean(0.0))
        assertEquals(15_000.0, DayLock.clean(15_000.0))
        assertNull(DayLock.next(null, day, LocalTime.NOON, Double.NaN, 7_999.0))
        assertEquals(8_000.0, DayLock.next(null, day, LocalTime.NOON, Double.NaN, 8_100.0)!!.limit)
        assertEquals(8_000.0, DayLock.CHOICES[1])
        assertTrue(0.0 in DayLock.CHOICES)
    }

    @Test fun itIsSaidInPlainWords() {
        val r = DayLock.Reached(day, LocalTime.of(14, 11), 8_001.0, 8_000.0)
        assertEquals("Day lock: reached +Rs 8,000 at 14:11, no new entries", DayLock.line(r))
        assertEquals("Day lock (paper): reached +Rs 8,000 at 14:11, no new entries", DayLock.line(r, "paper"))
        assertEquals("Day lock (Zerodha): reached +Rs 8,000 at 14:11, no new entries today (open positions keep their own exits)",
            DayLock.refusal(r, "Zerodha"))
        assertEquals("+Rs 8,000", DayLock.describe(8_000.0))
        assertEquals("off", DayLock.describe(0.0))
    }

    @Test fun itIsKeptAsOneLine() {
        val r = DayLock.Reached(day, LocalTime.of(9, 5), 8_123.5, 8_000.0)
        assertEquals("2026-10-08|09:05|8123.5|8000.0", DayLock.encode(r))
        assertEquals(r, DayLock.decode(DayLock.encode(r)))
        for (bad in listOf(null, "", "2026-10-08|09:05|8123.5", "x|09:05|1|1", "2026-10-08|09:05|NaN|8000", "2026-10-08|09:05|1|0"))
            assertNull(DayLock.decode(bad), bad)
    }
}
