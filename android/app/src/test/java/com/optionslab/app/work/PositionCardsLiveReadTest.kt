package com.optionslab.app.work

import android.app.Notification
import com.optionslab.app.data.KiteStream
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
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

    /**
     * 2 Nov: a paper position whose live copy is open shows no card of its own (the live card is the one notice), yet still counts as
     * open for the watch's full-speed stop checks; its card comes back once nothing keeps it down. (Paper prices from a fake Upstox.)
     */
    @Test fun aPaperPositionCopiedLiveShowsNoCardButStaysWatched() = FakeUpstox().use { _ ->
        Background.calendar(Background.WED); Background.at(Background.WED, 11, 0)
        val option = com.optionslab.engine.mcx.McxContract(148600327L, 580470L, "CRUDEOIL26OCT8550CE", "CRUDEOIL", java.time.LocalDate.of(2026, 10, 15),
            8550.0, com.optionslab.engine.Right.CE, 0.1, 100)
        val c = com.optionslab.app.data.McxMarket.paperContract(option)
        assertTrue(runBlocking { com.optionslab.app.data.Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, com.optionslab.engine.sandbox.Quote(242.0)) }.ok)
        fun paperCard() = Background.notifications(context).getNotification(PositionCards.idOf("Paper", c.symbol))
        refresh()
        assertNotNull("an ordinary paper position has its card", paperCard())
        Notifier.liveCopyFilled(context, c.symbol)
        assertNull("taken down by the live fill", paperCard())
        refresh()
        assertNull("not put back while the live copy is open", paperCard())
        assertTrue("still watched at full speed", PositionCards.anyOpen)
        Notifier.resetLiveCopiesForTest()
        refresh()
        assertNotNull("back once nothing keeps it down", paperCard())
    }

    /** 2 Nov: paper notifications off (the default): no paper card, yet the paper position still counts as open for the watch. */
    @Test fun withPaperNotificationsOffNoPaperCardButStillWatched() = FakeUpstox().use { _ ->
        Background.calendar(Background.WED); Background.at(Background.WED, 11, 0)
        Notifier.testPaperAlerts = null                                    // the real setting: off by default
        val option = com.optionslab.engine.mcx.McxContract(148600327L, 580470L, "CRUDEOIL26OCT8550CE", "CRUDEOIL", java.time.LocalDate.of(2026, 10, 15),
            8550.0, com.optionslab.engine.Right.CE, 0.1, 100)
        val c = com.optionslab.app.data.McxMarket.paperContract(option)
        assertTrue(runBlocking { com.optionslab.app.data.Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, com.optionslab.engine.sandbox.Quote(242.0)) }.ok)
        refresh()
        assertNull("no paper card", Background.notifications(context).getNotification(PositionCards.idOf("Paper", c.symbol)))
        assertTrue("still watched at full speed", PositionCards.anyOpen)
        Notifier.testPaperAlerts = true
        refresh()
        assertNotNull("on again: the card is back", Background.notifications(context).getNotification(PositionCards.idOf("Paper", c.symbol)))
    }
}
