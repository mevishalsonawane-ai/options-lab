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
}
