package com.optionslab.app.work

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.optionslab.app.MainActivity
import com.optionslab.app.data.AppSettings
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.RobolectricTest
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
 * Notification channels and privacy (nothing readable on a locked screen unless the owner allows
 * it), which notices reach the shade, and the in-app banner queue every notice also feeds.
 */
class NotifierTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.BackgroundWatchdog()
    private val nm get() = context.getSystemService(NotificationManager::class.java)
    private fun posted(id: Int): Notification? = Background.notifications(context).getNotification(id)

    @Before fun up() { Background.clearAlerts(); Background.clearCards(); Background.grantNotifications(context) }
    @After fun down() = Background.reset()

    @Test fun eightChannelsAllPrivateOnTheLockScreen() {
        Notifier.createChannels(context)
        val expect = mapOf(
            Notifier.BUY to NotificationManager.IMPORTANCE_HIGH, Notifier.SELL to NotificationManager.IMPORTANCE_HIGH,
            Notifier.APPROVAL to NotificationManager.IMPORTANCE_HIGH, Notifier.RISK to NotificationManager.IMPORTANCE_HIGH,
            Notifier.LIVE to NotificationManager.IMPORTANCE_NONE, Notifier.SCHEDULE to NotificationManager.IMPORTANCE_DEFAULT,
            Notifier.HEALTH to NotificationManager.IMPORTANCE_DEFAULT, Notifier.IRA to NotificationManager.IMPORTANCE_DEFAULT,
        )
        for ((id, importance) in expect) {
            val c = nm.getNotificationChannel(id)
            assertNotNull(id, c)
            assertEquals(id, importance, c.importance)
            assertEquals(id, Notification.VISIBILITY_PRIVATE, c.lockscreenVisibility)
            assertFalse(id, c.description.isNullOrBlank())
        }
        assertEquals(8, nm.notificationChannels.size)
        assertFalse("the ongoing watch shows no badge", nm.getNotificationChannel(Notifier.LIVE).canShowBadge())
    }

    @Test fun byDefaultTheLockScreenShowsOnlyANeutralLine() {
        val n = Notifier.builder(context, Notifier.RISK, "Short put is in the money", "NIFTY 24,480.0 is below the 24500 strike.", "ticket").build()
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val pub = n.publicVersion!!
        assertEquals("IraAlgo", Background.title(pub))
        assertEquals("Unlock to read", Background.text(pub))
        assertEquals(Notifier.RISK, pub.channelId)
        assertEquals(NotificationCompat.PRIORITY_HIGH, n.priority)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val open = shadowOf(n.contentIntent)
        assertTrue(open.isActivity)
        assertEquals(MainActivity::class.java.name, open.savedIntent.component!!.className)
        assertEquals("ticket", open.savedIntent.getStringExtra(MainActivity.EXTRA_TAB))
        assertNotNull("a nonce, so only the app's own notices open a tab", open.savedIntent.getStringExtra(MainActivity.EXTRA_NONCE))
        assertTrue(open.isImmutable)
    }

    @Test fun aBuyOrSellShowsABigColouredTileAndStaysPrivate() {
        val n = Notifier.builder(context, Notifier.BUY, "XAUUSD paper buy", "0.01 lot at 2390.15\nlevel 2388.00", side = "BUY").build()
        assertNotNull("collapsed view", n.contentView); assertNotNull("expanded view", n.bigContentView)
        assertEquals(com.optionslab.app.R.layout.notif_trade, n.contentView.layoutId)
        assertEquals(com.optionslab.app.R.layout.notif_trade_big, n.bigContentView.layoutId)
        assertEquals("the title and text stay for screen readers", "XAUUSD paper buy", Background.title(n))
        assertEquals("Unlock to read", Background.text(n.publicVersion!!))
        val t = Notifier.tile(context, "SELL", 144, 104)
        // (The simulated phone does not paint pixels, so the tile is checked by size; its colour by sideColor.)
        assertTrue(t.width > t.height); assertEquals(0xFFE0322B.toInt(), Notifier.sideColor("SELL"))
        assertEquals(0xFFE0322B.toInt(), Notifier.sideColor("SHORT")); assertEquals(0xFF00A86B.toInt(), Notifier.sideColor("LONG"))
        // Other notices keep the plain layout.
        assertNull(Notifier.builder(context, Notifier.RISK, "Risk", "text").build().contentView)
    }

    @Test fun theOwnerMayChooseToShowAmountsOnTheLockScreen() {
        AppSettings.save(AppSettings.load().copy(hideAmountsOnLockScreen = false))
        val n = Notifier.builder(context, Notifier.SCHEDULE, "Settled WIN", "Rs +1,200", null).build()
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, n.priority)
        assertEquals(Notification.CATEGORY_STATUS, n.category)
    }

    @Test fun buySellAndApprovalAlwaysNotifyTheRestOnlyWhenTurnedOn() {
        Notifier.post(context, 2004, Notifier.APPROVAL, "Review today's Zerodha order", "SELL NIFTY 24500 PE x1 is ready.")
        Notifier.post(context, 2002, Notifier.SCHEDULE, "Paper ticket", "Recorded as paper - nothing was sent.")
        Notifier.post(context, 3001, Notifier.RISK, "Index nearing your breakeven", "NIFTY is 0.20% above.")
        assertEquals("Review today's Zerodha order", Background.title(posted(2004)!!))
        assertNull("other alerts are off by default", posted(2002))
        assertNull(posted(3001))
        // Every notice still drops in at the top of the app.
        val q = Alerts.queue.value
        assertEquals(listOf("Review today's Zerodha order", "Paper ticket", "Index nearing your breakeven"), q.map { it.title })
        assertEquals(listOf(Alerts.Kind.INFO, Alerts.Kind.SUCCESS, Alerts.Kind.ERROR), q.map { it.kind })

        AppSettings.save(AppSettings.load().copy(otherAlerts = true))
        Notifier.post(context, 2002, Notifier.SCHEDULE, "Paper ticket 2", "Recorded as paper.")
        assertEquals("Paper ticket 2", Background.title(posted(2002)!!))
    }

    @Test fun anApprovalThatFailedIsRed() {
        Notifier.post(context, 2011, Notifier.APPROVAL, "IraAlgo could not run in the background", "Open the app to continue.")
        Notifier.post(context, 7001, Notifier.BUY, "BUY filled", "75 NIFTY")
        assertEquals(listOf(Alerts.Kind.ERROR, Alerts.Kind.SUCCESS), Alerts.queue.value.map { it.kind })
    }

    @Test fun withoutThePermissionNothingReachesTheShadeButTheBannerStillShows() {
        Background.denyNotifications(context)
        assertFalse(Notifier.canPost(context))
        Notifier.post(context, 2004, Notifier.APPROVAL, "Approve", "An order is waiting.")
        assertNull(posted(2004))
        assertEquals("Approve", Alerts.queue.value.single().title)
        Notifier.orderFilled(context, "BUY", 75, "NIFTYX", 101.5, "Paper", "ORB")
        assertEquals(0, Background.notifications(context).size())
    }

    @Test fun aFillBecomesItsPositionsCard() {
        Notifier.orderFilled(context, "buy", 75, "NIFTY25O2124500PE", 101.5, "Paper", "ORB")
        val a = Alerts.queue.value.single()
        assertEquals("BUY filled · Paper · Strategy: ORB", a.title)
        assertEquals("75 NIFTY25O2124500PE @ 101.50", a.text)
        assertEquals(Alerts.Kind.SUCCESS, a.kind)
        val n = posted(PositionCards.idOf("Paper", "NIFTY25O2124500PE"))!!
        assertEquals("BUY filled · Paper · Strategy: ORB", Background.title(n))
        assertEquals(Notifier.BUY, n.channelId)
        assertEquals("Close position", n.actions.single().title.toString())

        Notifier.orderFilled(context, "SELL", 75, "NIFTY25O2124500CE", 80.0, "Zerodha", null)
        val s = posted(PositionCards.idOf("Live", "NIFTY25O2124500CE"))!!
        assertEquals("SELL filled · Zerodha · Manual", Background.title(s))
        assertEquals(Notifier.SELL, s.channelId)
        assertEquals("Close…", s.actions.single().title.toString())
    }

    @Test fun theBannerQueueKeepsTheLatestFourAndFiltersRepeats() {
        for (i in 1..6) Alerts.post("event $i")
        assertEquals((3..6).map { "event $it" }, Alerts.queue.value.map { it.text })
        Alerts.post("background retry", throttle = true)
        Alerts.post("background retry", throttle = true)
        assertEquals(1, Alerts.queue.value.count { it.text == "background retry" })
        Alerts.post("wrong PIN"); Alerts.post("wrong PIN")
        assertEquals("what the owner just did always shows", 2, Alerts.queue.value.count { it.text == "wrong PIN" })
        Alerts.post("", title = null)
        assertEquals(4, Alerts.queue.value.size)
        val id = Alerts.queue.value.first().id
        Alerts.dismiss(id)
        assertTrue(Alerts.queue.value.none { it.id == id })
        Alerts.success("Order placed"); Alerts.error("Order refused")
        assertEquals(listOf(Alerts.Kind.SUCCESS, Alerts.Kind.ERROR), Alerts.queue.value.takeLast(2).map { it.kind })
    }

    @Test fun plainWordsDecideTheColour() {
        for (bad in listOf("Order not placed", "Could not close", "Kill switch is on", "RMS rejected the order", "Session expired",
                "No quote for NIFTY", "Daily limit reached", "Background watch stopped by Android", "Harvest failed"))
            assertEquals(bad, Alerts.Kind.ERROR, Alerts.classify(bad))
        for (ok in listOf("Order placed", "Paper position closed", "Settled WIN: Rs +1,200", "Notice: nothing to do"))
            assertEquals(ok, Alerts.Kind.SUCCESS, Alerts.classify(ok))
    }
}
