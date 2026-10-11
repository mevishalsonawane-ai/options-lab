package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Line-by-line review G3 (L3): the trade manager's "out by" for a Night (R3) position is the next trading session's 09:16 -
 * the arm's own sale - never the next calendar day's (a Friday's entry is out by Monday 09:16, not Saturday's).
 */
class NightArmOutByTest : RobolectricTest() {
    @Test fun aFridaysEntryIsOutByMondayAndAHolidayIsSkipped() {
        // Fixed fixture days (not today's date): a Wednesday, a Friday, and a Friday before a holiday Monday.
        assertEquals(LocalDate.of(2026, 10, 15).atTime(9, 16), NightArm.outBy(LocalDate.of(2026, 10, 14)))
        assertEquals(LocalDate.of(2026, 10, 12).atTime(9, 16), NightArm.outBy(LocalDate.of(2026, 10, 9)))
        Holidays.add(LocalDate.of(2026, 10, 12))
        assertEquals(LocalDate.of(2026, 10, 13).atTime(9, 16), NightArm.outBy(LocalDate.of(2026, 10, 9)))
    }
}
