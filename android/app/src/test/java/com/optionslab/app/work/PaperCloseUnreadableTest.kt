package com.optionslab.app.work

import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 19 Nov 2026 (audit batch 2): "Close position" on a paper card while the paper book cannot be read for a moment (a Keystore hiccup:
 * its read throws instead of handing back an empty account) tells Boss to try again and never crashes the app; nothing is closed.
 */
class PaperCloseUnreadableTest : RobolectricTest() {
    @Before fun up() {
        Background.calendar(WED)
        Background.at(WED, 11, 0)
        Background.clearAlerts()
    }

    @After fun down() { Background.reset() }

    private fun said() = Alerts.queue.value.map { it.text }

    @Test fun anUnreadableBookIsSaidAndNothingCrashes() {
        val before = PositionCards.closedFromShade.value
        runBlocking {
            NotificationActionReceiver().closePaper(context, "NIFTY25OCT24500CE", readState = { throw java.io.IOException("hiccup") })
        }
        assertTrue(said().toString(), PAPER_UNREADABLE in said())
        assertEquals("nothing closed, the cards left alone", before, PositionCards.closedFromShade.value)
    }

    @Test fun anUnreadableContractListIsSaidToo() {
        Background.clearAlerts()
        runBlocking {
            NotificationActionReceiver().closePaper(context, "NIFTY25OCT24500CE", contractOf = { throw IllegalStateException("book being read") })
        }
        assertTrue(said().toString(), PAPER_UNREADABLE in said())
    }

    @Test fun aReadableBookWithNoSuchPositionIsSaidAsBefore() {
        Background.clearAlerts()
        runBlocking { NotificationActionReceiver().closePaper(context, "NIFTY25OCT24500CE") }
        assertTrue(said().toString(), "No open paper position in NIFTY25OCT24500CE." in said())
        assertTrue(PAPER_UNREADABLE !in said())
    }
}
