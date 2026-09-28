package com.optionslab.app.work

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import com.optionslab.app.MainActivity
import com.optionslab.app.testing.Background
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
import org.robolectric.Shadows.shadowOf

/**
 * One live card per open position. A paper card's Close closes it from the shade (no money); a
 * Zerodha card's Close only opens the app's own review, never sends an order from the shade.
 */
class PositionCardsTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.BackgroundWatchdog()
    private fun posted(venue: String, symbol: String): Notification? =
        Background.notifications(context).getNotification(PositionCards.idOf(venue, symbol))

    @Before fun up() { Background.clearAlerts(); Background.clearCards(); Background.grantNotifications(context); Background.at(Background.WED, 11, 0); Background.calendar(Background.WED) }
    @After fun down() {
        Background.reset()
        assertEquals("a test reached the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun aPaperCardShowsTheLivePositionAndClosesFromTheShade() {
        PositionCards.card(context, "Paper", "NIFTY25O2124500PE", 75, 100.0, 120.0, 1_500.0)
        val n = posted("Paper", "NIFTY25O2124500PE")!!
        assertEquals("NIFTY25O2124500PE · Paper · +₹1,500", Background.title(n))
        assertEquals("LONG 75 @ 100.00 · LTP 120.00\nP&L +₹1,500 (+20.0%)", Background.text(n))
        assertEquals(com.optionslab.app.work.Notifier.BUY, n.channelId)
        assertTrue("stays while the position is open", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val close = n.actions.single()
        assertEquals("Close position", close.title.toString())
        val pi = shadowOf(close.actionIntent)
        assertTrue(pi.isBroadcast)
        assertTrue(pi.isImmutable)
        assertEquals(NotificationActionReceiver::class.java.name, pi.savedIntent.component!!.className)
        assertEquals(PositionCards.ACTION_CLOSE_PAPER, pi.savedIntent.action)
        assertEquals("NIFTY25O2124500PE", pi.savedIntent.getStringExtra(PositionCards.EXTRA_SYMBOL))
    }

    @Test fun aZerodhaCardsCloseOnlyOpensTheReviewInTheApp() {
        PositionCards.card(context, "Live", "NIFTY25O2124500CE", -75, 80.0, 90.0, -750.0)
        val n = posted("Live", "NIFTY25O2124500CE")!!
        assertEquals("NIFTY25O2124500CE · Live · −₹750", Background.title(n))
        assertEquals("SHORT 75 @ 80.00 · LTP 90.00\nP&L −₹750 (-12.5%)", Background.text(n))
        assertEquals(com.optionslab.app.work.Notifier.SELL, n.channelId)
        val close = n.actions.single()
        assertEquals("Close…", close.title.toString())
        val pi = shadowOf(close.actionIntent)
        assertTrue("an activity, not a broadcast that could send an order", pi.isActivity)
        assertEquals(MainActivity::class.java.name, pi.savedIntent.component!!.className)
        assertEquals("NIFTY25O2124500CE", pi.savedIntent.getStringExtra(MainActivity.EXTRA_CLOSE))
        assertNotNull(pi.savedIntent.getStringExtra(MainActivity.EXTRA_NONCE))
    }

    @Test fun aClosedPositionsCardIsTakenDown() {
        PositionCards.card(context, "Paper", "X", 75, 100.0, 98.0, -150.0)
        assertNotNull(posted("Paper", "X"))
        // Squared off / settled: the card goes away (the result is in the app's P&L).
        PositionCards.card(context, "Paper", "X", 0, 100.0, null, -200.0)
        assertNull(posted("Paper", "X"))
    }

    @Test fun aPaperExitFillTakesTheCardDownInsteadOfPostingASell() {
        PositionCards.card(context, "Paper", "NOPOS", 75, 100.0, 101.0, 75.0)
        // No paper position is open in NOPOS: the SELL fill squared it off.
        Notifier.orderFilled(context, "SELL", 75, "NOPOS", 101.0, "Paper", null)
        assertNull(posted("Paper", "NOPOS"))
    }

    @Test fun anUnknownPriceIsLeftOut() {
        PositionCards.card(context, "Paper", "Y", 50, 0.0, null, 0.0)
        assertEquals("LONG 50 @ 0.00\nP&L +₹0", Background.text(posted("Paper", "Y")!!))
    }

    @Test fun theCardSaysWhoOpenedThePositionAndKeepsItOnSilentUpdates() {
        PositionCards.card(context, "Paper", "W", 75, 100.0, 110.0, 750.0, source = "ORB + Manual")
        assertEquals("LONG 75 @ 100.00 · LTP 110.00\nP&L +₹750 (+10.0%)\nOpened by ORB + Manual", Background.text(posted("Paper", "W")!!))
        PositionCards.card(context, "Paper", "W", 75, 100.0, 111.0, 825.0)
        assertTrue(Background.text(posted("Paper", "W")!!)!!.endsWith("\nOpened by ORB + Manual"))
    }

    @Test fun withoutThePermissionNoCard() {
        Background.denyNotifications(context)
        PositionCards.card(context, "Paper", "Z", 75, 100.0, 101.0, 75.0)
        assertEquals(0, Background.notifications(context).size())
    }

    @Test fun aCardWhosePositionVanishedIsTakenDown() = com.optionslab.app.testing.bounded("aCardWhosePositionVanishedIsTakenDown") { runBlocking {
        PositionCards.card(context, "Paper", "GONE", 75, 100.0, 101.0, 75.0)
        assertNotNull(posted("Paper", "GONE"))
        PositionCards.refresh(context)
        assertNull(posted("Paper", "GONE"))
        assertFalse(PositionCards.anyOpen)
    } }

    @Test fun closingAPaperPositionThatIsNotOpenSaysSo() {
        context.sendBroadcast(Intent(PositionCards.ACTION_CLOSE_PAPER).setComponent(ComponentName(context, NotificationActionReceiver::class.java))
            .putExtra(PositionCards.EXTRA_SYMBOL, "NIFTY25O2124500PE"))
        shadowOf(Looper.getMainLooper()).idle()
        Background.await("the refusal") { Alerts.queue.value.any { it.text == "No open paper position in NIFTY25O2124500PE." } }
        assertEquals(Alerts.Kind.ERROR, Alerts.queue.value.single { it.text.startsWith("No open paper position") }.kind)
    }

    @Test fun aCloseWithoutASymbolDoesNothing() {
        NotificationActionReceiver().onReceive(context, Intent(PositionCards.ACTION_CLOSE_PAPER))
        assertTrue(Alerts.queue.value.isEmpty())
    }
}
