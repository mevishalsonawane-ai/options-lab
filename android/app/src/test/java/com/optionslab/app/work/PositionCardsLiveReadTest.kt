package com.optionslab.app.work

import android.app.Notification
import com.optionslab.app.data.KiteStream
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 18 Oct, review: a Zerodha positions read that fails (a timeout, a network blip) is not "no positions". The Live cards
 * stay, and so does [PositionCards.anyOpen] (the watch's 15-second stop check), until a read that succeeds lacks them.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PositionCardsLiveReadTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val ce = "NIFTY26OCT25000CE"

    private fun posted(symbol: String): Notification? = Background.notifications(context).getNotification(PositionCards.idOf("Live", symbol))
    private fun refresh() = runBlocking { PositionCards.refresh(context) }

    @Before fun up() {
        Background.clearAlerts(); Background.clearCards(); Background.grantNotifications(context)
        PositionCards::class.java.getDeclaredField("lastLive").apply { isAccessible = true }.set(null, null)
        kite = FakeKite()
        kite.login()
    }

    @After fun down() {
        KiteStream.stop()
        kite.close()
        Background.reset()
        PositionCards::class.java.getDeclaredField("lastLive").apply { isAccessible = true }.set(null, null)
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun aFailedReadKeepsTheLiveCardAndTheStopCadence() {
        kite.position(ce, 75, 100.0)
        kite.quote("NFO:$ce", 110.0, 109.95, 110.05)
        refresh()
        assertNotNull(posted(ce))
        assertTrue(PositionCards.anyOpen)
        // One read fails: the card and the 15-second stop check stay.
        kite.down += "/portfolio/positions"
        refresh()
        assertNotNull("the card is not taken down on a failed read", posted(ce))
        assertTrue("still open: the stop check keeps its 15 s", PositionCards.anyOpen)
        refresh()
        assertTrue("and on a second failed read", PositionCards.anyOpen)
        // A read that succeeds and shows it closed: taken down.
        kite.down.clear()
        kite.flat()
        refresh()
        assertNull(posted(ce))
        assertFalse(PositionCards.anyOpen)
    }

    @Test fun aSuccessfulReadWithoutThePositionTakesItDown() {
        kite.position(ce, 75, 100.0)
        refresh()
        assertTrue(PositionCards.anyOpen)
        kite.flat()
        refresh()
        assertNull(posted(ce))
        assertFalse(PositionCards.anyOpen)
    }

    @Test fun aFailedFirstReadWithNothingKnownIsNotOpen() {
        kite.down += "/portfolio/positions"
        refresh()
        assertFalse("nothing was ever read: nothing to keep", PositionCards.anyOpen)
    }

    @Test fun inPaperModeNoLiveCardIsKept() {
        kite.position(ce, 75, 100.0)
        refresh()
        assertTrue(PositionCards.anyOpen)
        // Switched to Paper: the live book is not read, and its cards go (as before).
        com.optionslab.app.security.SecurePrefs.put("k.mode", "paper")
        refresh()
        assertNull(posted(ce))
        assertFalse(PositionCards.anyOpen)
    }
}
