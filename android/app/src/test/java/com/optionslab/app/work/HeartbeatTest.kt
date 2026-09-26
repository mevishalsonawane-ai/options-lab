package com.optionslab.app.work

import android.app.AlarmManager
import android.app.Application
import android.os.SystemClock
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.LocalDate
import kotlin.math.abs

/**
 * The dead-man alert: armed whenever the watch is (paper bots need no Zerodha account), and when
 * the watch has gone quiet in market hours it restarts it and tells the owner once per stall.
 */
class HeartbeatTest : RobolectricTest() {
    private val am get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val app get() = context.applicationContext as Application
    private fun beats() = am.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == Heartbeat.ACTION }
    private fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, m).atZone(IST).toInstant().toEpochMilli()
    private fun stalled() = Background.notifications(context).getNotification(2014)

    @Before fun up() {
        Background.clearAlerts()
        Background.calendar(WED)
        Background.grantNotifications(context)
    }

    @After fun down() = Background.reset()

    @Test fun armedWithoutAZerodhaAccount() {
        Background.at(WED, 16, 0)
        Heartbeat.schedule(context)
        assertEquals("the next check is 09:20 on the next trading day", at(WED.plusDays(1), 9, 20), beats().single().triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, beats().single().type)
    }

    @Test fun inMarketHoursItChecksEveryFiveMinutes() {
        Background.at(WED, 11, 0)
        Heartbeat.schedule(context)
        Heartbeat.schedule(context)
        val t = beats().single().triggerAtMs
        // Five minutes from "now", whichever clock the app reads (Robolectric may stand System.currentTimeMillis still).
        val fromUptime = abs(t - 300_000L - SystemClock.uptimeMillis())
        val fromWall = abs(t - 300_000L - java.time.Instant.now().toEpochMilli())
        assertTrue("trigger $t", fromUptime < 5_000 || fromWall < 60_000)
    }

    @Test fun beforeTheOpenItWaitsForTodayAndItSkipsClosedDays() {
        Background.at(WED, 8, 0)
        Heartbeat.schedule(context)
        assertEquals(at(WED, 9, 20), beats().single().triggerAtMs)
        val fri = WED.plusDays(2); val mon = WED.plusDays(5)
        Background.calendar(WED, holidays = setOf(mon))
        Background.at(fri, 15, 30)
        Heartbeat.schedule(context)
        assertEquals("the weekend and a Monday holiday skipped", at(mon.plusDays(1), 9, 20), beats().single().triggerAtMs)
    }

    @Test fun aLiveWatchIsLeftAlone() {
        Background.at(WED, 11, 0)
        Heartbeat.beat(context)
        Heartbeat.check(context)
        assertFalse(Heartbeat.stale())
        assertNull(stalled())
        assertNull(shadowOf(app).peekNextStartedService())
        assertEquals(1, beats().size)
        assertFalse(Heartbeat.stalledToday())
    }

    @Test fun aSilentWatchIsRestartedAndReportedOncePerStall() {
        Background.at(WED, 11, 0)
        SecurePrefs.put("hb.last", 1L)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        assertTrue(Heartbeat.stale())
        Heartbeat.check(context)
        val n = stalled()
        assertNotNull(n)
        assertEquals("Market watch stopped", Background.title(n))
        assertTrue(Background.text(n)!!.startsWith("The watch has not run today. Stops, targets and strategy exits are not being watched."))
        assertEquals(Notifier.APPROVAL, n.channelId)
        assertEquals("the watch was restarted", "LIVE", shadowOf(app).nextStartedService.getStringExtra(Jobs.EXTRA_KIND))
        assertTrue(Heartbeat.stalledToday())

        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        Background.clearAlerts()
        Heartbeat.check(context)
        assertNull("told once per stall", stalled())
        assertEquals("but restarted again", "LIVE", shadowOf(app).nextStartedService.getStringExtra(Jobs.EXTRA_KIND))

        // The watch comes back: the notice goes, and a later stall is told again.
        Heartbeat.check(context)
        Heartbeat.beat(context)
        assertNull(SecurePrefs.getString("hb.alerted"))
        SecurePrefs.put("hb.last", 1L)
        Heartbeat.check(context)
        assertNotNull(stalled())
    }

    @Test fun outsideMarketHoursSilenceIsNormal() {
        SecurePrefs.put("hb.last", 1L)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        Background.at(WED, 9, 16)
        assertFalse("the first minutes after the open", Heartbeat.stale())
        Background.at(WED, 15, 30)
        assertFalse(Heartbeat.stale())
        Background.at(WED.plusDays(3), 11, 0)
        assertFalse("Saturday", Heartbeat.stale())
        Heartbeat.check(context)
        assertNull(stalled())
    }
}
