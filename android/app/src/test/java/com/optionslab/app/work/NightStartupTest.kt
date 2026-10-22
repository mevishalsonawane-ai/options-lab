package com.optionslab.app.work

import android.app.AlarmManager
import android.app.Application
import com.optionslab.app.data.StartGate
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.UpstoxStub
import com.optionslab.engine.IST
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate
import java.time.LocalTime

/**
 * 22 Oct (the overnight kills: "Android stopped it for using too much CPU or battery in the background ... excessive binder
 * traffic during cached", 02:43 and 02:51): a start in the background is light. Only the alarms whose time changed are armed
 * again (the dead-man check always), a reboot or a lost mark arms everything, the heavy start-up work waits until the market
 * watch or the screen starts, and Android's record of the process endings is asked once.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class NightStartupTest : RobolectricTest() {
    private lateinit var upstox: UpstoxStub
    private val app get() = context.applicationContext as Application
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val am get() = shadowOf(alarmManager)

    @Before fun up() {
        upstox = UpstoxStub()
        Background.calendar(WED)
        Background.at(WED, 10, 0)
        Background.grantNotifications(context)
        Background.workManager(context)
        NightMarks.testOffMain = true
        AlarmMarks.testBootAtMs = 1_000_000L
        StartGate.resetForTest()
    }

    @After fun down() {
        StartGate.fullArm?.join(30_000)
        NightMarks.testOffMain = false
        AlarmMarks.testBootAtMs = null
        StartGate.resetForTest()
        ProcessExits.resetForTest()
        upstox.close(); Background.reset()
    }

    private fun alarms(action: String) = am.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == action }
    private fun at(day: LocalDate, t: LocalTime) = day.atTime(t).atZone(IST).toInstant().toEpochMilli()
    /** The alarm taken out behind the app's back: only to see whether the next start arms it again (its intent stays). */
    private fun drop(action: String) = alarms(action).forEach { a -> a.operation?.let { alarmManager.cancel(it) } }
    private fun startedServices(): List<android.content.Intent> {
        val out = ArrayList<android.content.Intent>()
        while (true) out += shadowOf(app).nextStartedService ?: break
        return out
    }

    @Test fun aStartInTheBackgroundArmsOnlyTheAlarmsWhoseTimeChangedAndTheDeadManCheckAlways() {
        Background.linkZerodha()
        Jobs.scheduleAll(context)                       // armed in full: each alarm's time kept
        assertEquals(1, alarms("ol.job.LIVE").size)
        drop("ol.job.LIVE"); drop("ol.job.HARVEST"); drop(DailyReports.Kind.MORNING.action); drop(Heartbeat.ACTION)
        // The app started again by a night job, nothing changed: the alarms are left as they are - but the open positions' two
        // (the 09:14 market watch and the dead-man check) are armed at every start.
        Jobs.scheduleAll(context, onlyChanged = true)
        assertTrue("the harvest, same time: left as it was", alarms("ol.job.HARVEST").isEmpty())
        assertTrue(alarms(DailyReports.Kind.MORNING.action).isEmpty())
        assertEquals("the 09:14 watch alarm is armed at every start", at(WED.plusDays(1), LocalTime.of(9, 14)), alarms("ol.job.LIVE").single().triggerAtMs)
        assertEquals("the dead-man check is armed at every start", 1, alarms(Heartbeat.ACTION).size)
        // A day later the times have moved on: armed again, for the new times.
        Background.at(WED.plusDays(1), 10, 0)
        Jobs.scheduleAll(context, onlyChanged = true)
        assertEquals(at(WED.plusDays(2), LocalTime.of(9, 14)), alarms("ol.job.LIVE").single().triggerAtMs)
        assertEquals(at(WED.plusDays(2), LocalTime.of(9, 0)), alarms(DailyReports.Kind.MORNING.action).single().triggerAtMs)
    }

    @Test fun aRebootOrAStartOnTheMainThreadArmsEverythingAsBefore() {
        Background.linkZerodha()
        Jobs.scheduleAll(context)
        drop("ol.job.HARVEST")
        // The phone restarted (Android drops every alarm): armed again.
        AlarmMarks.testBootAtMs = 1_000_000L + 60_000L
        Jobs.scheduleAll(context, onlyChanged = true)
        assertEquals(at(WED, LocalTime.of(15, 45)), alarms("ol.job.HARVEST").single().triggerAtMs)
        // On the main thread no mark is read: every alarm is armed, as before 22 Oct.
        drop("ol.job.HARVEST")
        NightMarks.testOffMain = false
        Jobs.scheduleAll(context, onlyChanged = true)
        assertEquals(1, alarms("ol.job.HARVEST").size)
    }

    @Test fun aJobTurnedOffIsTakenOutAtTheNextLightStartToo() {
        Background.linkZerodha()
        Jobs.scheduleAll(context)
        assertEquals(1, alarms("ol.job.HARVEST").size)
        com.optionslab.app.data.AppSettings.save(com.optionslab.app.data.AppSettings.load().copy(nightlyHarvest = false))
        Jobs.scheduleAll(context, onlyChanged = true)
        assertTrue(alarms("ol.job.HARVEST").isEmpty())
    }

    @Test fun theHeavyStartUpWorkWaitsAtNightAndTheMarketWatchOpensIt() {
        StartGate.resetForTest(held = true)
        // 02:00, nothing held: the watch is not due, the heavy work keeps waiting.
        Background.at(WED, 2, 0)
        Jobs.ensureWatch(context)
        assertFalse(StartGate.isOpen)
        // 09:14: the watch starts, and with it the waiting work.
        Background.at(WED, 9, 14)
        Jobs.ensureWatch(context)
        assertEquals("LIVE", startedServices().single().getStringExtra(Jobs.EXTRA_KIND))
        assertTrue(StartGate.isOpen)
        assertEquals("the market watch", StartGate.openedBy)
    }

    /**
     * OPEN POSITIONS AND ORDERS COME FIRST: Android ended the app in MCX's evening with a crude option held on paper, and a night
     * job starts it again. The heavy work is held, but the watch starts at once (and opens it), and the light start's alarms
     * still arm the dead-man check every few minutes.
     */
    @Test fun startedAtNightWithAnMcxPositionOpenTheWatchStartsAndTheDeadManCheckIsArmed() = kotlinx.coroutines.runBlocking {
        val day = LocalDate.of(2026, 10, 8)
        Background.calendar(day)
        Background.at(day, 20, 0)
        val option = com.optionslab.engine.mcx.McxContract(148600327L, 580470L, "CRUDEOIL26OCT8550CE", "CRUDEOIL", LocalDate.of(2026, 10, 15),
            8550.0, com.optionslab.engine.Right.CE, 0.1, 100)
        val c = com.optionslab.app.data.McxMarket.paperContract(option)
        assertTrue(com.optionslab.app.data.Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, com.optionslab.engine.sandbox.Quote(242.0)).ok)
        // A new process: nothing read yet, the heavy work held.
        com.optionslab.app.data.Paper.forgetForTest(); com.optionslab.app.data.McxMarket.wipe()
        StartGate.resetForTest(held = true)
        Jobs.scheduleAll(context, onlyChanged = true)
        assertEquals("the dead-man check is armed at a light start", 1, alarms(Heartbeat.ACTION).size)
        Jobs.ensureWatch(context)
        // Decided at once from the books' plain hint, or (hint not enough) on a background thread: either way, started.
        Background.await("the watch started") { shadowOf(app).peekNextStartedService() != null }
        assertEquals("LIVE", startedServices().single().getStringExtra(Jobs.EXTRA_KIND))
        Background.await("the held work opened") { StartGate.isOpen }
        assertEquals("the market watch", StartGate.openedBy)
    }

    @Test fun theScreenStartingOpensTheWorkAndArmsEveryAlarmOnce() {
        Background.linkZerodha()
        Jobs.scheduleAll(context)
        drop("ol.job.HARVEST")
        StartGate.resetForTest(held = true)
        StartGate.hold(context)
        val owner = object : androidx.lifecycle.LifecycleOwner {
            override val lifecycle: androidx.lifecycle.Lifecycle get() = throw UnsupportedOperationException()
        }
        StartGate.onStart(owner)
        assertTrue(StartGate.isOpen)
        assertEquals("the app opened", StartGate.openedBy)
        val t = checkNotNull(StartGate.fullArm) { "the screen's start arms every alarm" }
        t.join(30_000)
        assertFalse(t.isAlive)
        assertEquals("armed in full: the alarm Android lost is back", 1, alarms("ol.job.HARVEST").size)
        // A second screen start in the same process: no second full arming.
        StartGate.onStart(owner)
        assertSame(t, StartGate.fullArm)
    }

    @Test fun theOldDhanStoreIsClearedOncePerInstalledApp() {
        com.optionslab.app.security.SecurePrefs.putAll(mapOf("dhan.clientId" to "made-up-not-real"))
        assertTrue(com.optionslab.app.IraAlgoApp.OldStore.removeOnce(context))
        assertTrue(com.optionslab.app.security.SecurePrefs.keys("dhan.").isEmpty())
        // Done for this installed app: the next start (an hourly night job) does not do it again.
        com.optionslab.app.security.SecurePrefs.putAll(mapOf("dhan.clientId" to "made-up-not-real"))
        assertFalse(com.optionslab.app.IraAlgoApp.OldStore.removeOnce(context))
        assertEquals(listOf("dhan.clientId"), com.optionslab.app.security.SecurePrefs.keys("dhan."))
    }

    @Test fun anUnreadableSettingsVaultLeavesTheCleanUpForTheNextStart() {
        val prefs = com.optionslab.app.security.SecurePrefs
        prefs.putAll(mapOf("dhan.clientId" to "made-up-not-real"))
        com.optionslab.app.security.Vault.destroy()
        assertFalse(prefs.reload())
        assertTrue(prefs.unreadable)
        assertTrue("it ran", com.optionslab.app.IraAlgoApp.OldStore.removeOnce(context))
        // Readable again (here: erased) with an old key in it: the clean-up was not marked done, so it runs and removes it.
        prefs.wipe()
        prefs.putAll(mapOf("dhan.clientId" to "made-up-not-real"))
        assertTrue(com.optionslab.app.IraAlgoApp.OldStore.removeOnce(context))
        assertTrue(prefs.keys("dhan.").isEmpty())
    }

    @Test fun androidsRecordOfTheProcessEndingsIsAskedOncePerProcess() {
        ProcessExits.resetForTest()
        var asks = 0
        ProcessExits.testAsk = { asks++; emptyList() }
        ProcessExits.read(context)                      // the diary's process endings
        ProcessExits.records(context)                   // the app's health, the second reader
        ProcessExits.records(context)
        assertEquals("one ask of Android a process", 1, asks)
        // A failed ask is not kept: the next reader asks again.
        ProcessExits.resetForTest()
        asks = 0
        ProcessExits.testAsk = { asks++; throw IllegalStateException("simulated: Android said no") }
        ProcessExits.read(context)                      // swallowed there (runCatching)
        assertTrue(runCatching { ProcessExits.records(context) }.isFailure)
        assertEquals(2, asks)
    }
}
