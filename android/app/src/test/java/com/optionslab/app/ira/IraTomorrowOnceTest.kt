package com.optionslab.app.ira

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tomorrow's plan is put in the chat once a day ([IraTomorrow.postOnce]): a plan that fails to be made (or is cancelled)
 * does not keep the day as done - the next round puts it; once put, never twice.
 */
class IraTomorrowOnceTest : RobolectricTest() {
    private val day = LocalDate.of(2026, 10, 6)

    @Test fun aFailedOrCancelledPlanIsTriedAgainAndAPutOneIsNeverPutTwice() = runBlocking {
        SecurePrefs.putAll(mapOf("jarvis.tomorrow.done" to null))
        val posted = ArrayList<String>()
        assertFalse(IraTomorrow.postOnce(day, { error("a read failed") }) { posted += it })
        assertNull("not kept as done", SecurePrefs.getString("jarvis.tomorrow.done"))
        try {
            IraTomorrow.postOnce(day, { throw CancellationException("stopped") }) { posted += it }
        } catch (_: CancellationException) {}
        assertNull("not kept as done", SecurePrefs.getString("jarvis.tomorrow.done"))
        assertTrue(posted.isEmpty())

        assertTrue(IraTomorrow.postOnce(day, { "The plan." }) { posted += it })
        assertEquals(day.toString(), SecurePrefs.getString("jarvis.tomorrow.done"))
        assertFalse(IraTomorrow.postOnce(day, { "The plan again." }) { posted += it })
        assertEquals(listOf("The plan."), posted)
        // The next day: put again.
        assertTrue(IraTomorrow.postOnce(day.plusDays(1), { "Next." }) { posted += it })
        assertEquals(listOf("The plan.", "Next."), posted)
    }
}
