package com.optionslab.engine

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NseHoursTest {
    private val july31 = LocalDate.of(2026, 7, 31)
    private val aug3 = LocalDate.of(2026, 8, 3)

    @Test fun `F&O closes 15-30 up to 31 Jul 2026 and 15-40 from 3 Aug 2026`() {
        assertEquals(15 * 60 + 30, NseHours.foClose(july31))
        assertEquals(15 * 60 + 40, NseHours.foClose(aug3))
        assertEquals(15 * 60 + 40, NseHours.foClose(LocalDate.of(2026, 10, 9)))
        assertEquals(LocalTime.of(15, 30), NseHours.foCloseTime(july31))
        assertEquals(LocalTime.of(15, 40), NseHours.foCloseTime(aug3))
        assertEquals("15:30", NseHours.foCloseText(july31))
        assertEquals("15:40", NseHours.foCloseText(aug3))
    }

    @Test fun `the closing-price window is the last 30 minutes of F&O`() {
        assertEquals(15 * 60, NseHours.foClosingWindowFrom(july31))
        assertEquals(15 * 60 + 10, NseHours.foClosingWindowFrom(aug3))
    }

    @Test fun `F&O open by the day's own close, the index to 15-30 always`() {
        assertFalse(NseHours.foOpen(aug3, 9 * 60 + 14))
        assertTrue(NseHours.foOpen(aug3, 9 * 60 + 15))
        assertTrue(NseHours.foOpen(aug3, 15 * 60 + 35))
        assertTrue(NseHours.foOpen(aug3, 15 * 60 + 39))
        assertFalse(NseHours.foOpen(aug3, 15 * 60 + 40))
        assertTrue(NseHours.foOpen(july31, 15 * 60 + 29))
        assertFalse(NseHours.foOpen(july31, 15 * 60 + 35))
        // The index's last candle is 15:29's, on either side of the change.
        assertTrue(NseHours.indexOpen(15 * 60 + 29))
        assertFalse(NseHours.indexOpen(15 * 60 + 30))
        assertFalse(NseHours.indexOpen(15 * 60 + 35))
        assertFalse(NseHours.indexOpen(9 * 60 + 14))
        assertEquals(NseHours.INDEX_CLOSE, NseHours.FO_CLOSE_BEFORE)
    }

    @Test fun squareOffMinuteIsTheArmsOwnCappedByExpiryAndClose() {
        val oct12 = LocalDate.of(2026, 10, 12)
        assertEquals(15 * 60 + 10, NseHours.squareOffMinute(oct12, 15 * 60 + 10, expiresToday = false))
        // On its option's expiry day the app's square-off closes it at 15:05.
        assertEquals(NseHours.EXPIRY_SQUARE_OFF, NseHours.squareOffMinute(oct12, 15 * 60 + 10, expiresToday = true))
        assertEquals(14 * 60 + 30, NseHours.squareOffMinute(oct12, 14 * 60 + 30, expiresToday = true))
        // Never after F&O's close (15:40 now, 15:30 before 3 Aug 2026).
        assertEquals(15 * 60 + 40, NseHours.squareOffMinute(oct12, 16 * 60, expiresToday = false))
        assertEquals(15 * 60 + 30, NseHours.squareOffMinute(LocalDate.of(2026, 7, 31), 16 * 60, expiresToday = false))
    }

    @Test fun `25 Oct - the Muhurat Sunday is a special session, never a regular day`() {
        val muhurat = LocalDate.of(2026, 11, 8)
        val s = NseHours.special(muhurat)!!
        assertEquals(LocalTime.of(18, 0), s.open)
        assertEquals(LocalTime.of(19, 0), s.close)
        assertEquals("Diwali Muhurat trading, 18:00-19:00 IST", s.text)
        assertFalse(NseHours.regular(muhurat), "no arm's 09:15 clock runs that day")
        assertTrue(NseHours.regular(muhurat.plusDays(1)), "the Monday after is ordinary")
        assertEquals(null, NseHours.special(aug3))
        // The same window as MCX's own table (its circular).
        val mcx = com.optionslab.engine.mcx.McxSession.BUILT_IN.getValue(muhurat)!!
        assertEquals(mcx.open, s.open); assertEquals(mcx.close, s.close)
        assertEquals(listOf(muhurat), NseHours.specialsFrom(LocalDate.of(2026, 10, 25)).map { it.first })
        assertTrue(NseHours.specialsFrom(muhurat.plusDays(1)).isEmpty(), "past it, none listed")
    }
}
