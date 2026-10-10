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
 * The opening read is put in the chat once a day ([IraOpening.postOnce]): a read that fails (the opening candles not on
 * the phone yet) or is cancelled does not keep the day as done - the next round puts it; once put, never twice. Its
 * switch is its own under Market alerts, on by default.
 */
class IraOpeningOnceTest : RobolectricTest() {
    private val day = LocalDate.of(2026, 10, 6)

    @Test fun aFailedOrCancelledReadIsTriedAgainAndAPutOneIsNeverPutTwice() = runBlocking {
        SecurePrefs.putAll(mapOf("jarvis.opening.done" to null))
        val posted = ArrayList<String>()
        assertFalse(IraOpening.postOnce(day, { error("not read yet") }) { posted += it })
        assertNull("not kept as done", SecurePrefs.getString("jarvis.opening.done"))
        try {
            IraOpening.postOnce(day, { throw CancellationException("stopped") }) { posted += it }
        } catch (_: CancellationException) {}
        assertNull("not kept as done", SecurePrefs.getString("jarvis.opening.done"))
        assertTrue(posted.isEmpty())

        assertTrue(IraOpening.postOnce(day, { "The open." }) { posted += it })
        assertEquals(day.toString(), SecurePrefs.getString("jarvis.opening.done"))
        assertFalse(IraOpening.postOnce(day, { "The open again." }) { posted += it })
        assertEquals(listOf("The open."), posted)
        assertTrue(IraOpening.postOnce(day.plusDays(1), { "Next." }) { posted += it })
        assertEquals(listOf("The open.", "Next."), posted)
    }

    @Test fun itsSwitchIsItsOwnUnderMarketAlertsAndOnByDefault() {
        assertTrue(Automations.isSub(Automations.Auto.OPENING))
        assertEquals(Automations.Group.MARKET, Automations.groupOf(Automations.Auto.OPENING))
        assertTrue(Automations.Auto.OPENING.byDefault)
    }
}
