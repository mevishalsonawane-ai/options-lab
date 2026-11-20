package com.optionslab.app.work

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.ArmWeek
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20 Nov 2026 (audit): the arms' week ([DailyReports.week]) says itself that the arms' book is not readable right now, instead of failing
 * the run and leaving it to the worker's retries (which say nothing).
 */
class DailyReportsWeekTest : RobolectricTest() {
    @Test fun aBookThatCannotBeReadIsSaidNotThrown() = runBlocking {
        val r = DailyReports.readWeek { throw java.io.IOException("the vault is locked") }
        assertEquals(DailyReports.WeekRead.Unreadable, r)
        assertTrue(DailyReports.WEEK_UNREADABLE.contains("not readable right now"))
    }

    @Test fun aWeekReadIsSaidAndNothingToSayIsNothing() = runBlocking {
        val said = ArmWeek.Said("chat", "aloud")
        assertEquals(DailyReports.WeekRead.Read(said), DailyReports.readWeek { said })
        assertEquals(DailyReports.WeekRead.None, DailyReports.readWeek { null })
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun aCancelledRunIsNeverTakenForAnUnreadableBook() {
        runBlocking { DailyReports.readWeek { throw kotlinx.coroutines.CancellationException("the worker stopped") } }
    }
}
