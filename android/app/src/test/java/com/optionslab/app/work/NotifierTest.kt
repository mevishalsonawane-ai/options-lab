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

    @Test fun aFillSaysItsOrderId() {
        Notifier.orderFilled(context, "BUY", 75, "NIFTY25O2124500CE", 101.5, "Paper", "Jarvis solo · entry", "PAPER-00000042")
        val a = Alerts.queue.value.last()
        assertEquals("BUY filled · Paper · Jarvis: solo · entry", a.title)
        assertEquals("75 NIFTY25O2124500CE @ 101.50 · order #00000042", a.text)
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

    /**
     * Boss (07 Oct 2026): a Liquidity 15+5 PAPER fill is in the shade but silent - no sound, no buzz, no pop-up - while a
     * manual paper order, another strategy's paper fill and a live order (a live Liquidity order too) sound as before.
     */
    @Test fun aLiquidityPaperFillIsSilentAndEveryOtherFillSounds() {
        fun card(venue: String, symbol: String) = posted(PositionCards.idOf(venue, symbol))!!
        fun silent(n: Notification) = n.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY
        Notifier.orderFilled(context, "BUY", 30, "BANKNIFTYLIQCE", 300.0, "Paper", "Liquidity 15+5 · entry", "PAPER-00000001")
        val e = card("Paper", "BANKNIFTYLIQCE")
        assertTrue("the same fill card, words unchanged", Background.title(e)!!.startsWith("BUY filled · Paper · "))
        assertTrue("a Liquidity paper entry is silent", silent(e))
        assertEquals("and does not pop up", NotificationCompat.PRIORITY_LOW, e.priority)
        // A book's own name (its exits and stops) is Liquidity 15+5's too.
        Notifier.orderFilled(context, "SELL", 30, "BANKNIFTYLIQPE", 280.0, "Paper", "Liquidity 5m FINNIFTY · stop", "PAPER-00000002")
        assertTrue(silent(card("Paper", "BANKNIFTYLIQPE")))
        assertTrue(Notifier.quietFill("Paper", "Liquidity 5m · exit"))
        // Everything else sounds as before. Each card is read as soon as it is posted: a live fill's card is re-checked against
        // Zerodha's book 2 s later (and taken down when the book does not hold it), which a slow test runner could reach first.
        fun sounds(venue: String, symbol: String) {
            val n = card(venue, symbol)
            assertFalse("$venue $symbol sounds", silent(n))
            assertEquals("$venue $symbol", NotificationCompat.PRIORITY_HIGH, n.priority)
        }
        Notifier.orderFilled(context, "BUY", 75, "NIFTYMANUALCE", 101.5, "Paper", null)
        sounds("Paper", "NIFTYMANUALCE")
        Notifier.orderFilled(context, "BUY", 75, "NIFTYORBCE", 101.5, "Paper", "ORB")
        sounds("Paper", "NIFTYORBCE")
        Notifier.orderFilled(context, "BUY", 30, "BANKNIFTYLIVECE", 300.0, "Live", "Liquidity 15+5 · entry", "250101000000001")
        sounds("Live", "BANKNIFTYLIVECE")
        Notifier.orderFilled(context, "SELL", 75, "NIFTYZERODHAPE", 80.0, "Zerodha", null)
        sounds("Live", "NIFTYZERODHAPE")
        assertFalse(Notifier.quietFill("Live", "Liquidity 15+5 · entry"))
        assertFalse(Notifier.quietFill("Paper", "Manual"))
        assertFalse(Notifier.quietFill("Paper", "Pine · Liquidity sweep"))
        assertFalse(Notifier.quietFill("Paper", null))
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

    // ---- 2 Nov: one notice for a live trade ----

    @Test fun thePaperContractIsReadFromAnArmsPaperBuyNoticeOnly() {
        assertEquals("NIFTY03NOV2623200CE", Notifier.paperOf("R72 Breadth Donchian bought NIFTY03NOV2623200CE · Paper"))
        assertEquals("SILVERMIC30NOV26FUT", Notifier.paperOf("US-night silver (not proven, futures) bought SILVERMIC30NOV26FUT · Paper"))
        assertEquals("SILVERMIC30NOV26FUT", Notifier.paperOf("US-night silver sold SILVERMIC30NOV26FUT · Paper"))
        assertNull("not a paper notice", Notifier.paperOf("R72 Breadth Donchian bought NIFTY03NOV2623200CE · Live"))
        assertNull("no contract", Notifier.paperOf("Hero fired"))
        assertNull(Notifier.paperOf(" bought  · Paper"))
        assertNull(Notifier.paperOf(""))
    }

    @Test fun aLiveCopyTakesThePaperNoticeDownAndKeepsLaterOnesOffForTenMinutes() {
        Notifier.post(context, 6201, Notifier.BUY, "R51 Donchian 20 bought NIFTY03NOV2623200CE · Paper", "Bought 65 @ 138.50.")
        Notifier.post(context, 6202, Notifier.BUY, "R51 Donchian 20 bought BANKNIFTY23NOV2654900CE · Paper", "Bought 30 @ 938.95.")
        assertNotNull(posted(6201)); assertNotNull(posted(6202))
        val t = System.currentTimeMillis()
        Notifier.liveCopyFilled(context, "NIFTY03NOV2623200CE", t)
        assertNull("its paper notice is down", posted(6201))
        assertNotNull("another contract's stays", posted(6202))
        assertTrue(Notifier.liveCopyShown("NIFTY03NOV2623200CE", t + 60_000))
        assertFalse("ten minutes on, only the live book keeps it down", Notifier.liveCopyShown("NIFTY03NOV2623200CE", t + 11 * 60_000))
        // A second arm's paper buy of the same contract right after: not shown again in the shade.
        Notifier.post(context, 6203, Notifier.BUY, "R72 Breadth Donchian bought NIFTY03NOV2623200CE · Paper", "Bought 65 @ 138.50.")
        assertNull(posted(6203))
        // Other notices are never touched.
        Notifier.post(context, 6204, Notifier.APPROVAL, "Liquidity: approve BUY NIFTY03NOV2623200CE", "Approve?")
        assertNotNull(posted(6204))
        Notifier.resetLiveCopiesForTest()
    }

    @Test fun aPaperFillWhoseLiveCopyIsOpenShowsNoCardOfItsOwn() {
        Notifier.liveCopyFilled(context, "NIFTY03NOV2623200CE")
        Notifier.orderFilled(context, "BUY", 65, "NIFTY03NOV2623200CE", 138.5, "Paper", "R72 Breadth Donchian · entry")
        assertNull(Background.notifications(context).getNotification(PositionCards.idOf("Paper", "NIFTY03NOV2623200CE")))
        // The live fill itself is shown, and an unrelated paper fill too.
        Notifier.orderFilled(context, "BUY", 65, "NIFTY26N0323200CE", 137.1, "Live", "R51 Donchian 20 · live entry")
        assertNotNull(Background.notifications(context).getNotification(PositionCards.idOf("Live", "NIFTY26N0323200CE")))
        Notifier.orderFilled(context, "BUY", 30, "BANKNIFTY23NOV2654900CE", 938.95, "Paper", "R51 Donchian 20 · entry")
        assertNotNull(Background.notifications(context).getNotification(PositionCards.idOf("Paper", "BANKNIFTY23NOV2654900CE")))
        Notifier.resetLiveCopiesForTest()
    }

    // An arm that reuses a notice id for its exit: a later live fill of the same contract never takes that exit notice down.
    @Test fun aReusedNoticeIdIsNoLongerThePaperBuy() {
        Notifier.post(context, 6971, Notifier.BUY, "US-night silver bought SILVERMIC30NOV26FUT · Paper", "Bought 1 @ 183,543.")
        Notifier.post(context, 6971, Notifier.SELL, "US-night silver closed SILVERMIC30NOV26FUT · Paper", "Rs +1,200 after charges.")
        Notifier.liveCopyFilled(context, "SILVERMIC30NOV26FUT")
        assertNotNull("the exit notice stays", posted(6971))
        Notifier.resetLiveCopiesForTest()
    }
}
