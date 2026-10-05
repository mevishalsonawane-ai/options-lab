package com.optionslab.app.work

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.optionslab.app.data.Alarms
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.PriceAlarm
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.FakeAndroidKeyStore
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.UpstoxStub
import com.optionslab.app.testing.UpstoxStub.Companion.isIntraday
import com.optionslab.app.testing.UpstoxStub.Companion.status
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList

/** A phone that refuses to let the watch service into the foreground (Android 12+ background start, a spent budget). */
@Implements(Service::class)
class RefusingForegroundService : ShadowService() {
    @Implementation
    override fun startForeground(id: Int, notification: Notification?, foregroundServiceType: Int) {
        throw IllegalStateException("simulated: startForeground not allowed now")
    }
}

/**
 * The phone's own schedule: alarms re-armed for the next trading day, the watch service's start,
 * stop and time-out, what happens when Android refuses the foreground, the harvest's routing and
 * retry budget, and what the failure notices may say.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class JobsTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.BackgroundWatchdog()
    private lateinit var upstox: UpstoxStub
    private val app get() = context.applicationContext as Application
    private val services = ArrayList<ServiceController<WatchService>>()
    private val am get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Before fun up() {
        upstox = UpstoxStub()
        Background.clearAlerts(); Background.clearCards()
        Background.calendar(WED)
        Background.at(WED, 10, 0)
        Background.grantNotifications(context)
        Background.workManager(context)
    }

    @After fun down() {
        services.forEach { runCatching { it.destroy() } }
        FakeAndroidKeyStore.failing.clear()
        upstox.close(); Background.reset()
        assertEquals("a test reached the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun alarms(action: String) = am.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == action }
    private fun alarm(action: String) = alarms(action).single()
    private fun at(day: LocalDate, t: LocalTime) = day.atTime(t).atZone(IST).toInstant().toEpochMilli()
    private fun otherAlerts(on: Boolean) = AppSettings.save(AppSettings.load().copy(otherAlerts = on))
    private fun alerts() = Alerts.queue.value
    private fun alert(title: String) = alerts().firstOrNull { it.title == title }

    private fun startService(kind: Jobs.Kind?, session: LocalDate? = null): WatchService {
        val i = Intent(context, WatchService::class.java)
        kind?.let { i.putExtra(Jobs.EXTRA_KIND, it.name) }
        session?.let { i.putExtra(Jobs.EXTRA_SESSION, it.toString()) }
        val c = Robolectric.buildService(WatchService::class.java, i).create()
        services += c
        c.startCommand(0, 1)
        return c.get()
    }

    private fun broadcast(i: Intent) {
        context.sendBroadcast(i.setComponent(ComponentName(context, AlarmReceiver::class.java)))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun startedServices(): List<Intent> {
        val out = ArrayList<Intent>()
        while (true) out += shadowOf(app).nextStartedService ?: break
        return out
    }

    // ---- which jobs, when --------------------------------------------------------------------------

    @Test fun theWatchNeedsNoZerodhaAndTheRestDo() {
        val s = AppSettings.load()
        assertTrue(Jobs.enabled(Jobs.Kind.LIVE, s))
        for (k in Jobs.Kind.entries - Jobs.Kind.LIVE) assertFalse(k.name, Jobs.enabled(k, s))
        Background.linkZerodha()
        assertTrue(Jobs.enabled(Jobs.Kind.REMIND, s))
        assertTrue("a prepared real order needs the ticket job", Jobs.enabled(Jobs.Kind.TICKET, s))
        assertTrue(Jobs.enabled(Jobs.Kind.SETTLE, s))
        assertTrue(Jobs.enabled(Jobs.Kind.HARVEST, s))
        assertFalse(Jobs.enabled(Jobs.Kind.HARVEST, s.copy(nightlyHarvest = false)))
        assertFalse(Jobs.enabled(Jobs.Kind.TICKET, s.copy(prepareRealOrder = false, autoTicket = false)))
        assertFalse(Jobs.enabled(Jobs.Kind.REMIND, s.copy(entryReminder = false)))
    }

    @Test fun nextRunSkipsWeekendsAndHolidaysAndHonoursSpecialSessions() {
        fun from(d: LocalDate, h: Int, m: Int): ZonedDateTime = d.atTime(h, m).atZone(IST)
        assertEquals(from(WED, 9, 14), Jobs.nextRun(Jobs.Kind.LIVE, from(WED, 9, 0)))
        assertEquals("at the minute itself: tomorrow", from(WED.plusDays(1), 9, 14), Jobs.nextRun(Jobs.Kind.LIVE, from(WED, 9, 14)))
        val fri = WED.plusDays(2); val mon = WED.plusDays(5)
        assertEquals(from(mon, 15, 45), Jobs.nextRun(Jobs.Kind.HARVEST, from(fri, 16, 0)))
        Background.calendar(WED, holidays = setOf(mon))
        assertEquals(from(mon.plusDays(1), 15, 45), Jobs.nextRun(Jobs.Kind.HARVEST, from(fri, 16, 0)))
        Background.calendar(WED, extra = setOf(fri.plusDays(1)))
        assertEquals("a Saturday session", from(fri.plusDays(1), 9, 14), Jobs.nextRun(Jobs.Kind.LIVE, from(fri, 12, 0)))
    }

    @Test fun withoutZerodhaOnlyTheWatchAndItsHeartbeatAreArmed() {
        Jobs.scheduleAll(context)
        assertEquals(at(WED.plusDays(1), LocalTime.of(9, 14)), alarm("ol.job.LIVE").triggerAtMs)
        assertEquals(1, alarms(Heartbeat.ACTION).size)
        for (k in Jobs.Kind.entries - Jobs.Kind.LIVE) assertTrue(k.name, alarms("ol.job.${k.name}").isEmpty())
        for (r in DailyReports.Kind.entries) assertTrue(r.name, alarms(r.action).isEmpty())
    }

    @Test fun linkedEveryJobAndReportIsArmedOnceForItsNextTradingDay() {
        Background.linkZerodha()
        Jobs.scheduleAll(context)
        Jobs.scheduleAll(context)   // re-arming replaces, never duplicates
        assertEquals(at(WED.plusDays(1), LocalTime.of(9, 14)), alarm("ol.job.LIVE").triggerAtMs)
        assertEquals(at(WED, LocalTime.of(10, 55)), alarm("ol.job.REMIND").triggerAtMs)
        assertEquals(at(WED, LocalTime.of(11, 1)), alarm("ol.job.TICKET").triggerAtMs)
        assertEquals(at(WED, LocalTime.of(15, 45)), alarm("ol.job.HARVEST").triggerAtMs)
        assertEquals(at(WED, LocalTime.of(15, 45)), alarm(DailyReports.Kind.EVENING.action).triggerAtMs)
        assertEquals(at(WED.plusDays(1), LocalTime.of(9, 0)), alarm(DailyReports.Kind.MORNING.action).triggerAtMs)
        assertEquals(1, alarms(Heartbeat.ACTION).size)
        assertTrue(am.scheduledAlarms.all { it.type == AlarmManager.RTC_WAKEUP })
        // A job turned off is disarmed.
        AppSettings.save(AppSettings.load().copy(nightlyHarvest = false))
        Jobs.schedule(context, Jobs.Kind.HARVEST)
        assertTrue(alarms("ol.job.HARVEST").isEmpty())
    }

    // ---- the alarm receiver ------------------------------------------------------------------------

    @Test fun theHarvestAlarmReArmsThenStartsTheServiceForTodaysSession() {
        Background.linkZerodha()
        Background.at(WED, 15, 45)
        broadcast(Intent("ol.job.HARVEST").putExtra(Jobs.EXTRA_KIND, "HARVEST"))
        Background.await("the service to be started") { shadowOf(app).peekNextStartedService() != null }
        assertEquals(at(WED.plusDays(1), LocalTime.of(15, 45)), alarm("ol.job.HARVEST").triggerAtMs)
        val i = startedServices().single()
        assertEquals(WatchService::class.java.name, i.component!!.className)
        assertEquals("HARVEST", i.getStringExtra(Jobs.EXTRA_KIND))
        assertEquals(WED.toString(), i.getStringExtra(Jobs.EXTRA_SESSION))
        assertFalse(i.getBooleanExtra("manual", true))
    }

    @Test fun onAHolidayTheAlarmOnlyReArms() {
        Background.linkZerodha()
        Background.calendar(WED, holidays = setOf(WED))
        broadcast(Intent("ol.job.LIVE").putExtra(Jobs.EXTRA_KIND, "LIVE"))
        Background.await("re-arming") { alarms("ol.job.LIVE").isNotEmpty() }
        Thread.sleep(300)
        assertTrue(startedServices().isEmpty())
    }

    @Test fun expiryOnlyJobsWaitForAnExpiryDay() {
        Background.linkZerodha()
        Background.contracts(context, WED, listOf(Upstox.Contract("NIFTY", WED.plusDays(6), 24_500.0, Right.PE, 75, "NSE_FO|1", "N")))
        broadcast(Intent("ol.job.TICKET").putExtra(Jobs.EXTRA_KIND, "TICKET"))
        Background.await("re-arming") { alarms("ol.job.TICKET").isNotEmpty() }
        Thread.sleep(300)
        assertTrue("not an expiry day", startedServices().isEmpty())

        Background.contracts(context, WED, listOf(Upstox.Contract("NIFTY", WED, 24_500.0, Right.PE, 75, "NSE_FO|1", "N")))
        broadcast(Intent("ol.job.REMIND").putExtra(Jobs.EXTRA_KIND, "REMIND"))
        Background.await("the entry reminder") { alert("Expiry day - entry at 11:00") != null }
        assertTrue("the reminder runs in the receiver, no service", startedServices().isEmpty())
    }

    @Test fun heartbeatAndReportAlarmsAreRoutedToTheirOwners() {
        Background.linkZerodha()
        broadcast(Intent(Heartbeat.ACTION))
        Background.await("the heartbeat re-armed") { alarms(Heartbeat.ACTION).isNotEmpty() }
        Thread.sleep(300); startedServices()   // a stale heartbeat may restart the watch; that is Heartbeat's own test
        broadcast(Intent(DailyReports.Kind.LOGIN.action))
        Background.await("the report re-armed") { alarms(DailyReports.Kind.LOGIN.action).isNotEmpty() }
        Background.await("the report worker") { Background.unique(context, "report.LOGIN").isNotEmpty() }
        broadcast(Intent("ol.job.NOPE").putExtra(Jobs.EXTRA_KIND, "NOPE"))
        broadcast(Intent("ol.job.none"))
        Thread.sleep(300)
        assertTrue(startedServices().isEmpty())
    }

    @Test fun bootReArmsEverythingAndPicksTheWatchBackUpInHours() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, alarms("ol.job.LIVE").size)
        assertEquals("LIVE", startedServices().single().getStringExtra(Jobs.EXTRA_KIND))
        Background.at(WED, 18, 0)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue("after hours the watch waits for its alarm", startedServices().isEmpty())
    }

    // ---- starting work -----------------------------------------------------------------------------

    @Test fun harvestNowGoesToWorkManagerAndTheScheduledOneToTheService() {
        Background.linkZerodha()
        Jobs.start(context, Jobs.Kind.HARVEST, manual = true)
        assertTrue(startedServices().isEmpty())
        assertEquals(WorkInfo.State.ENQUEUED, Background.unique(context, "job.HARVEST").single().state)
        Jobs.start(context, Jobs.Kind.HARVEST, manual = false)
        assertEquals("HARVEST", startedServices().single().getStringExtra(Jobs.EXTRA_KIND))
    }

    @Test fun withoutZerodhaOnlyTheWatchStarts() {
        Jobs.start(context, Jobs.Kind.TICKET)
        Jobs.start(context, Jobs.Kind.HARVEST)
        assertTrue(startedServices().isEmpty())
        assertTrue(Background.unique(context, "job.HARVEST").isEmpty())
        Jobs.start(context, Jobs.Kind.LIVE, manual = false)
        assertEquals("LIVE", startedServices().single().getStringExtra(Jobs.EXTRA_KIND))
        Jobs.stopLive(context)
        assertEquals(WatchService.STOP, startedServices().single().action)
        NotificationActionReceiver().onReceive(context, Intent(NotificationActionReceiver.STOP_LIVE))
        assertEquals(WatchService.STOP, startedServices().single().action)
    }

    @Test fun whenTheForegroundStartIsRefusedEachJobFallsBackItsOwnWay() {
        Background.linkZerodha()
        val refusing = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent?): ComponentName? = throw IllegalStateException("not allowed from the background")
        }
        Jobs.start(refusing, Jobs.Kind.LIVE, manual = false)
        val n = Background.notifications(context).getNotification(2010)
        assertEquals("the watch cannot run in WorkManager: the owner is asked", "Order watch is off", Background.title(n))
        Jobs.start(refusing, Jobs.Kind.HARVEST, manual = false)
        assertEquals(WorkInfo.State.ENQUEUED, Background.unique(context, "job.HARVEST").single().state)
        Jobs.start(refusing, Jobs.Kind.TICKET, manual = false)
        assertEquals(1, Background.unique(context, "job.TICKET").size)
    }

    @Test fun ensureWatchStartsItOnlyWhenDue() {
        Background.at(WED, 9, 13); Jobs.ensureWatch(context); assertTrue(startedServices().isEmpty())
        Background.at(WED, 9, 14); Jobs.ensureWatch(context); assertEquals(1, startedServices().size)
        Background.at(WED, 15, 30); assertTrue(Jobs.watchDue())
        Background.at(WED, 15, 31); assertFalse(Jobs.watchDue())
        Background.at(WED.plusDays(3), 10, 0); assertFalse("Saturday", Jobs.watchDue())
    }

    // ---- the watch service -------------------------------------------------------------------------

    @Test fun theWatchGoesForegroundAtOnceAndStopsCleanly() {
        Background.at(WED, 9, 5)
        val svc = startService(Jobs.Kind.LIVE)
        val shadow = shadowOf(svc)
        assertEquals(Notifier.ID_LIVE, shadow.lastForegroundNotificationId)
        assertEquals(Notifier.LIVE, shadow.lastForegroundNotification.channelId)
        assertTrue(shadow.lastForegroundNotification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        Background.await("the first pass") { Background.title(shadow.lastForegroundNotification) == Tasks.WATCH_TITLE }
        assertEquals(Tasks.WATCH_IDLE, Background.text(shadow.lastForegroundNotification))
        assertTrue("the pass stamped the heartbeat", Heartbeat.last() > 0L || SecurePrefs.snapshot().containsKey("hb.last"))
        assertFalse(shadow.isStoppedBySelf)

        svc.onStartCommand(Intent(context, WatchService::class.java).setAction(WatchService.STOP), 0, 2)
        assertTrue(shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        assertFalse(Tasks.watchState.value.running)
    }

    @Test fun theWatchIsStickyAndEveryEndIsInTheDiary() {
        Background.at(WED, 9, 5)
        val svc = startService(Jobs.Kind.LIVE)
        // Already running: a second start (the dead-man's restart, Android's own) keeps it, and it stays sticky.
        assertEquals(Service.START_STICKY, svc.onStartCommand(Intent(context, WatchService::class.java).putExtra(Jobs.EXTRA_KIND, "LIVE"), 0, 2))
        assertEquals(Service.START_STICKY, svc.onStartCommand(null, 0, 3))
        Background.await("the start in the diary") { com.optionslab.app.data.Diag.lines().any { it.contains("[watch] started") } }
        svc.onStartCommand(Intent(context, WatchService::class.java).setAction(WatchService.STOP), 0, 4)
        Background.await("why it ended") { com.optionslab.app.data.Diag.lines().any { it.contains("[watch] ended: stopped from the notification or the app") } }
        assertNull("seen to end: the next start is not a process death", SecurePrefs.getString("watch.run.day"))
    }

    @Test fun aWatchWhoseProcessDiedIsToldAtTheNextStart() {
        Background.at(WED, 10, 0)
        SecurePrefs.put("watch.run.day", WED.toString())     // a watch was running today and never ended: the process died
        startService(Jobs.Kind.LIVE)
        Background.await("the restart in the diary") { com.optionslab.app.data.Diag.lines().any { it.contains("[watch] restarted: the app's process had ended") } }
    }

    @Test fun androidsRestartAfterHoursEndsQuietly() {
        Background.at(WED, 16, 30)
        val c = Robolectric.buildService(WatchService::class.java).create()
        services += c
        assertEquals(Service.START_NOT_STICKY, c.get().onStartCommand(null, 0, 1))
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertNull("no foreground tried, no notice", Background.notifications(context).getNotification(2011))
    }

    @Test fun androidsTimeLimitStopsTheWatchAndSaysSo() {
        Background.at(WED, 9, 5)
        val svc = startService(Jobs.Kind.LIVE)
        svc.onTimeout(1, 0)
        assertTrue(shadowOf(svc).isStoppedBySelf)
        Background.await("the time limit in the diary") { com.optionslab.app.data.Diag.lines().any { it.contains("[watch] ended: Android's time limit") } }
        val n = Background.notifications(context).getNotification(2012)
        assertEquals("Background watch stopped by Android", Background.title(n))
        assertEquals(Notifier.APPROVAL, n.channelId)
    }

    @Test
    @Config(shadows = [RefusingForegroundService::class])
    fun aRefusedForegroundStopsTheServiceAndTellsTheOwner() {
        Background.at(WED, 9, 5)
        val svc = startService(Jobs.Kind.LIVE)
        assertTrue("a started service that never goes foreground is killed: it stops itself", shadowOf(svc).isStoppedBySelf)
        val n = Background.notifications(context).getNotification(2011)
        assertEquals("IraAlgo could not run in the background", Background.title(n))
        assertTrue(Background.text(n)!!.startsWith("Open the app to continue: "))
        val running = WatchService::class.java.getDeclaredField("running").apply { isAccessible = true }.get(svc) as Map<*, *>
        assertTrue("no job launched to run on after stopSelf: $running", running.isEmpty())
    }

    @Test fun aFailedPassDoesNotEndTheWatch() {
        Background.at(WED, 9, 5)
        FakeAndroidKeyStore.failing += "ol.vault.data.v1"   // every settings write (the heartbeat) now fails
        val svc = startService(Jobs.Kind.LIVE)
        Background.await("the hiccup notice") { alert("Order watch hiccup") != null }
        val a = alert("Order watch hiccup")!!
        assertEquals("One pass failed (UnrecoverableKeyException); the watch goes on.", a.text)
        assertFalse("no raw exception message", a.text.contains("simulated"))
        assertFalse(shadowOf(svc).isStoppedBySelf)
        FakeAndroidKeyStore.failing.clear()
        Background.await("the next pass", timeoutMs = 30_000) { Background.title(shadowOf(svc).lastForegroundNotification) == Tasks.WATCH_TITLE }
        assertFalse(shadowOf(svc).isStoppedBySelf)
    }

    @Test fun zerodhaOnlyJobsStopAtOnceWithoutALinkedAccount() {
        val svc = startService(Jobs.Kind.HARVEST, WED)
        assertTrue(shadowOf(svc).isStoppedBySelf)
        assertEquals(0, upstox.paths.size)
    }

    @Test fun aFailedServiceHarvestIsHandedToWorkManagerWithoutANotice() {
        Background.linkZerodha()
        Background.at(WED, 15, 45)
        upstox.reply = { status(503) }      // the instrument master cannot be read
        val svc = startService(Jobs.Kind.HARVEST, WED)
        Background.await("the harvest to give up") { shadowOf(svc).isStoppedBySelf }
        assertEquals("$WED: did not complete (Market data service is unavailable (503))", SecurePrefs.getString("harvest.last"))
        assertEquals("$WED:1", SecurePrefs.getString("harvest.failures"))
        assertEquals(WorkInfo.State.ENQUEUED, Background.unique(context, "job.HARVEST").single().state)
        assertFalse(Tasks.state.value.running)
        assertNull("the harvest line says so; no notification", Background.notifications(context).getNotification(2900 + Jobs.Kind.HARVEST.ordinal))
    }

    @Test fun aFailedJobsNoticeNamesTheErrorTypeNotItsMessage() {
        Background.linkZerodha()
        otherAlerts(true)
        Background.at(WED, 11, 1)
        Background.contracts(context, WED, listOf(Upstox.Contract("BANKNIFTY", WED, 55_000.0, Right.PE, 35, "NSE_FO|9", "BN")))
        val svc = startService(Jobs.Kind.TICKET, WED)
        Background.await("the ticket to fail") { shadowOf(svc).isStoppedBySelf }
        val n = Background.notifications(context).getNotification(2900 + Jobs.Kind.TICKET.ordinal)
        assertEquals("Ticket did not complete", Background.title(n))
        assertEquals("It stopped with IllegalStateException. Open IraAlgo to check.", Background.text(n))
        assertFalse(Background.text(n)!!.contains("NIFTY"))
    }

    @Test fun failureReasonsAreGenericSentencesOrTypeNames() {
        assertEquals("IllegalStateException", Tasks.reason(IllegalStateException("NSE_FO|12345 answered {\"secret\":1}")))
        assertEquals("No connection to the market data service", Tasks.reason(com.optionslab.app.data.Net.Offline()))
        assertEquals("Market data service is unavailable (502)", Tasks.reason(com.optionslab.app.data.Net.HttpFailure(502)))
    }

    // ---- the watch's pass ----------------------------------------------------------------------------

    @Test fun riskStepsRunBeforeTheQuotesAndTheQuotesUseTheQuickLane() = com.optionslab.app.testing.bounded("riskStepsRunBeforeTheQuotesAndTheQuotesUseTheQuickLane") { runBlocking {
        val nm = Background.notifications(context)
        PositionCards.card(context, "Paper", "ZZTEST", 75, 100.0, 101.0, 75.0)
        val id = PositionCards.idOf("Paper", "ZZTEST")
        assertNotNull(nm.getNotification(id))
        val cardAtFirstQuote = CopyOnWriteArrayList<Boolean>()
        val quotePaths = Upstox.INDEX_KEYS.values.map { "${UpstoxStub.BASE}/intraday/$it/minutes/1" }.toSet()
        upstox.reply = { p ->
            if (p in quotePaths) { cardAtFirstQuote += nm.getNotification(id) != null; status(500) } else status(404)
        }
        val t = Tasks.watchTick(context, AppSettings.load(), HashSet())
        assertFalse("the position pass (a risk step) had already run when the first quote was asked for", cardAtFirstQuote.first())
        for (k in Upstox.INDEX_KEYS.values) assertEquals(k, 2, upstox.count { it == "${UpstoxStub.BASE}/intraday/$k/minutes/1" })
        assertEquals(Tasks.WATCH_TITLE, t.title)
        assertTrue(t.lines.isEmpty())
        assertEquals(-1, t.progress)
    } }

    @Test fun theWatchNoticeCarriesNoMarketData() = com.optionslab.app.testing.bounded("theWatchNoticeCarriesNoMarketData") { runBlocking {
        upstox.reply = { p -> if (isIntraday(p)) UpstoxStub.candles(UpstoxStub.minutes(WED, LocalTime.of(9, 15), 30, 24_800.0)) else status(404) }
        val t = Tasks.watchTick(context, AppSettings.load(), HashSet())
        // The index quotes were read (for alarms and the widget) but none reaches the notice: only orders and positions do.
        assertTrue(upstox.count(::isIntraday) > 0)
        assertTrue(t.lines.toString(), t.lines.none { it.startsWith("NIFTY") || it.startsWith("BANKNIFTY") || it.startsWith("VIX") })
        assertEquals(Tasks.WATCH_TITLE, t.title)
    } }

    @Test fun aHungFeedCannotStallTheWatch() = com.optionslab.app.testing.bounded("aHungFeedCannotStallTheWatch") { runBlocking {
        upstox.reply = { p -> if (isIntraday(p)) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else status(404) }
        val t0 = System.nanoTime()
        val t = Tasks.watchTick(context, AppSettings.load(), HashSet())
        val secs = (System.nanoTime() - t0) / 1e9
        assertTrue("took $secs s", secs < 35)
        assertTrue(t.lines.isEmpty())
    } }

    @Test fun priceAlarmsFireOnceAndNotAgainWithinHalfAnHour() {
        ShadowSystemClock.advanceBy(Duration.ofHours(2))
        Alarms.upsert(PriceAlarm(7, "NIFTY", above = true, level = 25_000.0, note = "take profit"))
        Alarms.upsert(PriceAlarm(8, "BANKNIFTY", above = false, level = 50_000.0))
        val fired = HashSet<String>()
        Tasks.checkAlarms(context, mapOf("NIFTY" to 25_010.0, "BANKNIFTY" to 55_000.0), fired)
        assertEquals(setOf("alarm.7"), fired)
        val a = alert("Alarm: NIFTY rises above 25,000.00")!!
        assertEquals(Alerts.Kind.ERROR, a.kind)
        assertEquals("NIFTY is at 25,010.00. take profit", a.text)
        assertTrue(Alarms.all().single { it.id == 7L }.firedAtMillis > 0)
        Background.clearAlerts()
        Tasks.checkAlarms(context, mapOf("NIFTY" to 25_020.0), HashSet())
        assertNull("fired less than 30 minutes ago", alert("Alarm: NIFTY rises above 25,000.00"))
    }

    // ---- the fallback worker -------------------------------------------------------------------------

    private fun worker(kind: String?, session: LocalDate? = WED, attempt: Int = 0): FallbackWorker {
        val pairs = ArrayList<Pair<String, Any?>>()
        kind?.let { pairs += Jobs.EXTRA_KIND to it }
        session?.let { pairs += Jobs.EXTRA_SESSION to it.toString() }
        return TestListenableWorkerBuilder.from(context, FallbackWorker::class.java)
            .setInputData(workDataOf(*pairs.toTypedArray())).setRunAttemptCount(attempt).build()
    }

    @Test fun aHarvestGetsThreeRealTriesPerSession() = com.optionslab.app.testing.bounded("aHarvestGetsThreeRealTriesPerSession") { runBlocking {
        Background.at(WED, 15, 50)
        upstox.reply = { status(503) }
        assertTrue(worker("HARVEST").doWork() is ListenableWorker.Result.Retry)
        assertTrue(worker("HARVEST", attempt = 5).doWork() is ListenableWorker.Result.Retry)
        assertTrue("the third real failure is the last", worker("HARVEST").doWork() is ListenableWorker.Result.Failure)
        assertEquals("$WED:3", SecurePrefs.getString("harvest.failures"))
        assertEquals("$WED: did not complete (Market data service is unavailable (503))", SecurePrefs.getString("harvest.last"))
        // Another session starts its own budget.
        assertTrue(worker("HARVEST", session = WED.plusDays(1)).doWork() is ListenableWorker.Result.Retry)
        assertEquals("${WED.plusDays(1)}:1", SecurePrefs.getString("harvest.failures"))
        assertFalse(Tasks.state.value.running)
    } }

    @Test fun aHarvestStoppedBySystemIsNotCountedAsAFailure() = com.optionslab.app.testing.bounded("aHarvestStoppedBySystemIsNotCountedAsAFailure") { runBlocking {
        Background.at(WED, 15, 50)
        upstox.reply = { status(503) }
        val w = worker("HARVEST")
        val stop = ListenableWorker::class.java.methods.first { it.name == "stop" }
        if (stop.parameterCount == 1) stop.invoke(w, 1) else stop.invoke(w)
        assertTrue(w.isStopped)
        try { w.doWork(); fail("a stopped harvest must end as stopped, not failed") } catch (_: kotlinx.coroutines.CancellationException) {}
        assertNull(SecurePrefs.getString("harvest.failures"))
    } }


    @Test fun theWorkerRunsTheOtherJobsAndRetriesThemTwice() = com.optionslab.app.testing.bounded("theWorkerRunsTheOtherJobsAndRetriesThemTwice") { runBlocking {
        assertTrue(worker(null).doWork() is ListenableWorker.Result.Failure)
        assertTrue(worker("BOGUS").doWork() is ListenableWorker.Result.Failure)
        assertTrue(worker("LIVE").doWork() is ListenableWorker.Result.Success)
        assertTrue(worker("REMIND").doWork() is ListenableWorker.Result.Success)
        assertNotNull(alert("Expiry day - entry at 11:00"))
        // No contract list and no master: the ticket cannot be drafted.
        upstox.reply = { status(503) }
        assertTrue(worker("TICKET", attempt = 0).doWork() is ListenableWorker.Result.Retry)
        assertTrue(worker("TICKET", attempt = 2).doWork() is ListenableWorker.Result.Failure)
        assertTrue("nothing open to settle", worker("SETTLE").doWork() is ListenableWorker.Result.Success)
    } }

    @Test fun theWorkersForegroundNoticeIsQuiet() = com.optionslab.app.testing.bounded("theWorkersForegroundNoticeIsQuiet") { runBlocking {
        val h = worker("HARVEST").getForegroundInfo()
        assertEquals(Notifier.ID_HARVEST, h.notificationId)
        assertEquals("Harvesting market data", Background.title(h.notification))
        assertEquals(Notifier.LIVE, h.notification.channelId)
        val o = worker("TICKET").getForegroundInfo()
        assertEquals(Notifier.ID_HARVEST + 1, o.notificationId)
        assertEquals("IraAlgo", Background.title(o.notification))
    } }

    @Test fun harvestFailuresAreCountedPerSession() {
        assertEquals(1, Tasks.harvestFailed(WED))
        assertEquals(2, Tasks.harvestFailed(WED))
        assertEquals(1, Tasks.harvestFailed(WED.plusDays(1)))
        assertEquals(1, Tasks.harvestFailed(WED))
    }

    // ---- daily reports -----------------------------------------------------------------------------

    private fun battery(unrestricted: Boolean) =
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, unrestricted)

    @Test fun theMorningCheckListsWhatNeedsFixing() = com.optionslab.app.testing.bounded("theMorningCheckListsWhatNeedsFixing") { runBlocking {
        battery(true)
        Background.linkZerodha()
        Background.contracts(context, WED, listOf(Upstox.Contract("NIFTY", WED.plusDays(6), 24_500.0, Right.PE, 75, "NSE_FO|1", "N")))
        val (title, lines) = DailyReports.morning(context)
        assertEquals("Morning check · Wed 15 Oct · 1 to fix", title)
        assertTrue(lines.first().startsWith("✗ Zerodha not logged in"))
        assertTrue(lines.contains("✓ NSE holiday list up to date"))
        assertTrue(lines.contains("✓ Today's option contracts loaded (1)"))
        assertTrue(lines.contains("✓ Kill switch off"))
        assertTrue(lines.contains("• Mode: Paper"))
        DailyReports.post(context, DailyReports.Kind.MORNING, title, lines)
        assertEquals(title, Background.title(Background.notifications(context).getNotification(DailyReports.Kind.MORNING.id)))
    } }

    @Test fun aBatteryOptimizedAppIsAMorningItemThatOpensTheBatteryRow() = com.optionslab.app.testing.bounded("aBatteryOptimizedAppIsAMorningItemThatOpensTheBatteryRow") { runBlocking {
        battery(false)
        val lines = DailyReports.morning(context).second
        assertTrue(lines.toString(), lines.contains("✗ " + com.optionslab.ira.WatchHealth.BATTERY_FIX))
        assertEquals("schedule.permissions", NoticeCards.morningSetting(lines, needsLogin = false))
        battery(true)
        assertTrue(DailyReports.morning(context).second.contains("✓ " + com.optionslab.ira.WatchHealth.BATTERY_OK))
    } }

    @Test fun aQuietDayReportsNoTrades() = com.optionslab.app.testing.bounded("aQuietDayReportsNoTrades") { runBlocking {
        val (title, lines) = DailyReports.evening(context)
        assertEquals("Day report · Wed 15 Oct", title)
        assertEquals(listOf("No trades today."), lines)
        SecurePrefs.put("hb.stalled.day", WED.toString())
        AppSettings.save(AppSettings.load().copy(guardKill = true))
        val (t2, l2) = DailyReports.evening(context)
        assertTrue(l2.contains("⚠ The market watch stopped at least once today"))
        assertTrue(l2.contains("⚠ The kill switch is on"))
        assertTrue(t2.startsWith("Day report · Wed 15 Oct · "))
    } }

    @Test fun reportsAreArmedOnlyForALinkedAccountAndFireOnlyOnTradingDays() {
        DailyReports.scheduleAll(context)
        assertTrue(am.scheduledAlarms.isEmpty())
        Background.linkZerodha()
        Background.calendar(WED, holidays = setOf(WED))
        DailyReports.fired(context, DailyReports.Kind.EVENING)
        assertEquals(at(WED.plusDays(1), LocalTime.of(15, 45)), alarm(DailyReports.Kind.EVENING.action).triggerAtMs)
        assertTrue("a holiday: no report", Background.unique(context, "report.EVENING").isEmpty())
        assertNull(DailyReports.of("ol.job.LIVE"))
        assertEquals(DailyReports.Kind.LOGIN, DailyReports.of("ol.report.login"))
    }
}
