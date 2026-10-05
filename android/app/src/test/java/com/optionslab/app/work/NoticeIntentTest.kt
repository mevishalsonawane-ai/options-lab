package com.optionslab.app.work

import android.Manifest
import android.app.Notification
import android.content.Intent
import com.optionslab.app.MainActivity
import com.optionslab.app.data.AppSettings
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.RobolectricTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * A tap on any of the app's notifications carries its card (title, whole text, time, what it asks, the Settings row it
 * is about) into the app, each notification with its own PendingIntent; only the app's own (the nonce) are trusted.
 */
class NoticeIntentTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.BackgroundWatchdog()
    private fun posted(id: Int): Notification? = Background.notifications(context).getNotification(id)
    private fun cardOf(n: Notification): NoticeCard? = shadowOf(n.contentIntent).savedIntent.let { i -> NoticeCards.fromExtras { i.getStringExtra(it) } }

    @Before fun up() {
        Background.clearAlerts(); Background.clearCards(); Background.grantNotifications(context)
        AppSettings.save(AppSettings.load().copy(otherAlerts = true))
        MainActivity.cardRequests.value = null
    }
    @After fun down() { MainActivity.cardRequests.value = null; MainActivity.tabRequests.value = null; NoticeCards.forgetAll(); Background.reset() }

    @Test fun everyNoticeCarriesItsWholeCardWithItsOwnIntent() {
        Notifier.post(context, 2004, Notifier.APPROVAL, "Review today's Zerodha order", "SELL NIFTY 24500 PE x1 is ready.\nOpen the app to review.", "ticket")
        Notifier.post(context, 3001, Notifier.RISK, "Index nearing your breakeven", "NIFTY is 0.20% above.", "trade")
        val a = posted(2004)!!; val b = posted(3001)!!
        val ca = cardOf(a)!!
        assertEquals("Review today's Zerodha order", ca.title)
        assertEquals("SELL NIFTY 24500 PE x1 is ready.\nOpen the app to review.", ca.text)
        assertEquals(Notifier.APPROVAL, ca.kind); assertEquals(2004, ca.id); assertEquals("ticket", ca.tab)
        assertTrue(ca.at > 0)
        assertEquals("Index nearing your breakeven", cardOf(b)!!.title)
        // Two notifications, two PendingIntents (the second never overwrote the first's card).
        assertNotEquals(shadowOf(a.contentIntent).requestCode, shadowOf(b.contentIntent).requestCode)
        val open = shadowOf(a.contentIntent)
        assertTrue(open.isActivity); assertTrue(open.isImmutable)
        assertEquals(MainActivity::class.java.name, open.savedIntent.component!!.className)
        assertNotNull(open.savedIntent.getStringExtra(MainActivity.EXTRA_NONCE))
        assertEquals("the page it always opened", "ticket", open.savedIntent.getStringExtra(MainActivity.EXTRA_TAB))
        // The lock screen is as private as before.
        assertEquals(Notification.VISIBILITY_PRIVATE, a.visibility)
        assertEquals("Unlock to read", Background.text(a.publicVersion!!))
    }

    @Test fun aSettingsNoticeNamesItsRowNotAPage() {
        Notifier.post(context, 2005, Notifier.SCHEDULE, "Log in to Zerodha for today", "Yesterday's session ended at 06:00.", "broker", setting = "broker.login")
        val n = posted(2005)!!
        assertEquals("broker.login", cardOf(n)!!.setting)
        assertNull("the card opens the row; a plain page link would race it", shadowOf(n.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_TAB))
    }

    @Test fun aPositionCardOffersItsClose() {
        PositionCards.card(context, "Paper", "NIFTY25O2124500PE", 75, 100.0, 120.0, 1_500.0)
        val c = cardOf(posted(PositionCards.idOf("Paper", "NIFTY25O2124500PE"))!!)!!
        assertEquals("Paper", c.closeVenue); assertEquals("NIFTY25O2124500PE", c.closeSymbol)
        assertEquals("trade", c.tab)
    }

    @Test fun theAppTakesOnlyItsOwnCards() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        val card = NoticeCard(7401, "jarvis", "Boss, a goal is broken", "Shall I switch the kill switch on?", 1_000L, tab = "almanac", action = 42L)
        fun intent(nonce: String?) = Intent(context, MainActivity::class.java).apply {
            NoticeCards.toExtras(card).forEach { (k, v) -> putExtra(k, v) }
            if (nonce != null) putExtra(MainActivity.EXTRA_NONCE, nonce)
        }
        val bad = Robolectric.buildActivity(MainActivity::class.java, intent(null)).create()
        assertNull("another app's intent shows nothing", MainActivity.cardRequests.value)
        bad.destroy()
        val c = Robolectric.buildActivity(MainActivity::class.java, intent(MainActivity.nonce())).create()
        assertEquals(card, MainActivity.cardRequests.value)
        MainActivity.cardRequests.value = null
        c.newIntent(intent("guess"))
        assertNull(MainActivity.cardRequests.value)
        c.newIntent(intent(MainActivity.nonce()))
        assertEquals(card, MainActivity.cardRequests.value)
        c.destroy()
    }
}
