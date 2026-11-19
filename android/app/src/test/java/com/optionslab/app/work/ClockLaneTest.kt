package com.optionslab.app.work

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 18 Nov 2026 (audit: the 15:05 expiry square-off, the 15:20 / 15:25 flat-by, the daily loss limit and the MCX expiry exit ran only
 * inside the full watch pass - p95 10 min 42 s on Boss's phone - and no alarm woke the watch for them): the clock lane ([ClockLane])
 * runs them every 15 s by the clock beside the pass, and the clock alarm ([ClockAlarm]) wakes the watch for them.
 */
class ClockLaneTest : RobolectricTest() {
    private val tue: LocalDate = WED.minusDays(1)     // Tue 14 Oct 2025: NIFTY's weekly expiry
    private val thu: LocalDate = WED.plusDays(1)      // Thu 16 Oct 2025: SENSEX's weekly expiry
    private val am get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val all = listOf(ClockLane.LOSS, ClockLane.EXPIRY, ClockLane.MCX_EXPIRY, ClockLane.FLAT_BY)

    @Before fun up() {
        Background.calendar(WED)
        Background.at(tue, 15, 5)
        Background.clearAlerts()
        Alerts.forgetPosted()
        Background.grantNotifications(context)
        ClockLane.resetForTest()
        WatchWorkers.resetForTest()
    }

    @After fun down() {
        ClockLane.resetForTest()
        WatchWorkers.resetForTest()
        Background.reset()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun epoch(d: LocalDate, h: Int, m: Int, s: Int = 0) = d.atTime(LocalTime.of(h, m, s)).atZone(IST).toInstant().toEpochMilli()
    private fun alarms() = am.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == ClockAlarm.ACTION }

    /**
     * The pass stuck for minutes on one inline step (on 18 Nov the stop rescue: 8 min 45 s). Before, the expiry square-off, the
     * flat-by and the loss limit waited for it; now they run from the lane at once, with nothing held (they read the books themselves).
     */
    @Test fun aStuckPassNeverHoldsTheClockStepsBack() = runBlocking {
        val ran = CopyOnWriteArrayList<String>()
        ClockLane.testWork = { n -> ran += n }
        val run = WatchWorkers.begin(this)
        val ctl = StopLane.Control()
        try {
            val stuck = CompletableDeferred<Unit>()
            // The watch loop's pass: it says NSE's look is on, then hangs on a step that never ends.
            val pass = launch(Dispatchers.IO) { ctl.mode = StopLane.Mode.NSE; stuck.await() }
            val lane = launch(Dispatchers.IO) {
                ClockLane.runLane(context, stop = { ctl.ended }, mode = { ctl.mode }, held = { false }, supervisor = { WatchWorkers.supervisor })
            }
            Background.await("every clock step ran beside the stuck pass", 10_000) { ran.containsAll(all) }
            all.forEach { assertTrue("$it stamped as completed", ClockLane.lastDone(it) > 0L) }
            assertFalse("the pass is still stuck", pass.isCompleted)
            ctl.ended = true; ClockLane.poke(); lane.join()
            stuck.complete(Unit); pass.join()
        } finally {
            WatchWorkers.end(run)
        }
    }

    /** Before the open, in the evening look, on a closed day (no market look on): no clock step runs, as before. */
    @Test fun nothingRunsWhileNoMarketLookIsOn() = runBlocking {
        val ran = CopyOnWriteArrayList<String>()
        ClockLane.testWork = { n -> ran += n }
        val run = WatchWorkers.begin(this)
        val ctl = StopLane.Control()
        try {
            val lane = launch(Dispatchers.IO) {
                ClockLane.runLane(context, stop = { ctl.ended }, mode = { ctl.mode }, held = { true }, supervisor = { WatchWorkers.supervisor })
            }
            Thread.sleep(1_500)
            assertTrue(ran.isEmpty())
            // MCX's evening look: the loss limit, the MCX expiry exit and the flat-by - never NSE's 15:05 square-off.
            ctl.mode = StopLane.Mode.MCX
            Background.await("MCX's clock steps", 10_000) { ran.containsAll(ClockLane.stepsFor(StopLane.Mode.MCX)) }
            assertFalse(ran.contains(ClockLane.EXPIRY))
            ctl.ended = true; ClockLane.poke(); lane.join()
        } finally {
            WatchWorkers.end(run)
        }
    }

    /**
     * A stuck clock step never holds the others or the next round back: with the expiry square-off hung, the loss limit runs every
     * round. The other square-offs share its one turn (never two square-offs selling one contract at once): they wait for it, never
     * beside it, and run once it is free. The hung one is never started a second time beside itself.
     */
    @Test fun aStuckClockStepNeverBlocksTheLossLimitAndTheSquareOffsNeverRunTogether() = runBlocking {
        val ran = CopyOnWriteArrayList<String>()
        val release = CompletableDeferred<Unit>()
        val expiryStarts = java.util.concurrent.atomic.AtomicInteger()
        val squareOffsInside = java.util.concurrent.atomic.AtomicInteger()
        val mostInside = java.util.concurrent.atomic.AtomicInteger()
        ClockLane.testWork = { n ->
            if (n != ClockLane.LOSS) mostInside.accumulateAndGet(squareOffsInside.incrementAndGet(), ::maxOf)
            try {
                if (n == ClockLane.EXPIRY) { expiryStarts.incrementAndGet(); release.await() }
                ran += n
            } finally {
                if (n != ClockLane.LOSS) squareOffsInside.decrementAndGet()
            }
        }
        val run = WatchWorkers.begin(this)
        val ctl = StopLane.Control().apply { mode = StopLane.Mode.NSE }
        try {
            val lane = launch(Dispatchers.IO) {
                ClockLane.runLane(context, stop = { ctl.ended }, mode = { ctl.mode }, held = { false }, supervisor = { WatchWorkers.supervisor })
            }
            Background.await("the square-off hung", 10_000) { expiryStarts.get() == 1 }
            val lossBefore = ran.count { it == ClockLane.LOSS }
            for (i in 1..3) {
                ClockLane.poke()
                Background.await("loss limit round $i", 10_000) { ran.count { it == ClockLane.LOSS } >= lossBefore + i }
            }
            assertEquals("never started again beside itself", 1, expiryStarts.get())
            release.complete(Unit)
            ClockLane.poke()
            Background.await("the other square-offs once the turn is free", 20_000) {
                ran.contains(ClockLane.EXPIRY) && ran.contains(ClockLane.FLAT_BY) && ran.contains(ClockLane.MCX_EXPIRY)
            }
            assertEquals("never two square-offs at once", 1, mostInside.get())
            ctl.ended = true; ClockLane.poke(); lane.join()
        } finally {
            release.complete(Unit)
            WatchWorkers.end(run)
        }
    }

    /** The pass runs a clock step as the same supervisor step: the lane's run under way is waited for, never doubled. */
    @Test fun thePassWaitsForTheLanesRunAndNeverStartsASecond() = runBlocking {
        val inside = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val runs = java.util.concurrent.atomic.AtomicInteger()
        val release = CompletableDeferred<Unit>()
        ClockLane.testWork = { _ ->
            most.accumulateAndGet(inside.incrementAndGet(), ::maxOf); runs.incrementAndGet()
            release.await(); inside.decrementAndGet()
        }
        val run = WatchWorkers.begin(this)
        try {
            val s = com.optionslab.app.data.AppSettings.load()
            WatchWorkers.supervisor!!.kick(ClockLane.GROUP, listOf(ClockLane.step(context, ClockLane.EXPIRY, s)))
            Background.await("the lane's run", 5_000) { runs.get() == 1 }
            val pass = launch(Dispatchers.IO) { Tasks.clockStep(context, s, ClockLane.EXPIRY, inOrder = false) }
            Thread.sleep(500)
            assertFalse("the pass waits for the lane's run (what it closes is booked before the arms after it)", pass.isCompleted)
            release.complete(Unit)
            pass.join()
            assertEquals("one run, never two at once", 1, runs.get())
            assertEquals(1, most.get())
        } finally {
            release.complete(Unit)
            WatchWorkers.end(run)
        }
    }

    /** A due step with no completed run 45 s after its time, positions open: one RISK alert; nothing held: none. */
    @Test fun aLateStepWithPositionsOpenIsARiskAlertOnce() = runBlocking {
        var fake = epoch(tue, 15, 5, 1)
        ClockLane.clock = { fake }
        val release = CompletableDeferred<Unit>()
        ClockLane.testWork = { n -> if (n == ClockLane.EXPIRY) release.await() }
        var held = false
        val run = WatchWorkers.begin(this)
        val ctl = StopLane.Control().apply { mode = StopLane.Mode.NSE }
        fun lateAlerts() = Alerts.posted.filter { it.title == "Safety check late" }
        try {
            val lane = launch(Dispatchers.IO) {
                ClockLane.runLane(context, stop = { ctl.ended }, mode = { ctl.mode }, held = { held }, supervisor = { WatchWorkers.supervisor })
            }
            Background.await("the first round", 5_000) { ClockLane.lastDone(ClockLane.LOSS) == epoch(tue, 15, 5, 1) }
            // A minute on, nothing held: the stuck square-off is late but not told.
            for (i in 1..3) {
                fake += 20_000
                val before = fake
                ClockLane.poke()
                Background.await("round $i", 5_000) { ClockLane.lastDone(ClockLane.LOSS) == before }
            }
            assertTrue(lateAlerts().isEmpty())
            held = true
            fake += 20_000
            val at = fake
            ClockLane.poke()
            Background.await("the late alert", 5_000) { lateAlerts().isNotEmpty() }
            Background.await("that round's loss limit", 5_000) { ClockLane.lastDone(ClockLane.LOSS) == at }
            // The hung square-off (and the two waiting for its turn) told; the loss limit - completing every round - never.
            assertEquals(1, lateAlerts().count { it.text.contains("expiry square-off") })
            assertFalse(lateAlerts().any { it.text.contains("daily loss limit") })
            // Told once.
            fake += 20_000
            val next = fake
            ClockLane.poke()
            Background.await("the next round", 5_000) { ClockLane.lastDone(ClockLane.LOSS) == next }
            assertEquals(1, lateAlerts().count { it.text.contains("expiry square-off") })
            assertFalse(lateAlerts().any { it.text.contains("daily loss limit") })
            ctl.ended = true; ClockLane.poke(); lane.join()
        } finally {
            release.complete(Unit)
            WatchWorkers.end(run)
        }
    }

    // ---- the clock alarm ------------------------------------------------------------------------------

    @Test fun theAlarmIsArmedFor1504OnAnExpiryDayOnly() {
        Background.at(tue, 9, 30)
        Jobs.scheduleAll(context)
        Jobs.scheduleAll(context)   // re-arming replaces, never duplicates
        assertEquals(epoch(tue, 15, 4), alarms().single().triggerAtMs)
        // Not an expiry day (Wednesday): the flat-by's 15:19 first, no 15:04.
        Background.at(WED, 9, 30)
        ClockAlarm.schedule(context)
        assertEquals(epoch(WED, 15, 19), alarms().single().triggerAtMs)
        Background.at(WED, 15, 19)
        ClockAlarm.schedule(context)
        assertEquals(epoch(WED, 15, 24), alarms().single().triggerAtMs)
        // After the last one: SENSEX's Thursday expiry, 15:04.
        Background.at(WED, 15, 24)
        ClockAlarm.schedule(context)
        assertEquals(epoch(thu, 15, 4), alarms().single().triggerAtMs)
        assertTrue(am.scheduledAlarms.all { it.type == AlarmManager.RTC_WAKEUP })
    }

    @Test fun holidaysAndWeekendsAreSkipped() {
        // Tuesday a holiday: NIFTY's expiry moves to Monday, so Wednesday is no expiry day; the next alarm is Wednesday 15:19.
        Background.calendar(WED, holidays = setOf(tue))
        Background.at(tue.minusDays(1), 16, 0)
        ClockAlarm.schedule(context)
        assertEquals(epoch(WED, 15, 19), alarms().single().triggerAtMs)
        // Friday after 15:24: Monday (not an expiry day by the rule: 15:19).
        val fri = WED.plusDays(2)
        Background.calendar(WED)
        Background.at(fri, 15, 30)
        ClockAlarm.schedule(context)
        val next = java.time.Instant.ofEpochMilli(alarms().single().triggerAtMs).atZone(IST)
        assertEquals("never the weekend", fri.plusDays(3), next.toLocalDate())
        assertTrue(next.toLocalTime() in setOf(LocalTime.of(15, 4), LocalTime.of(15, 19)))
    }

    private fun broadcast(i: Intent) {
        context.sendBroadcast(i.setComponent(ComponentName(context, AlarmReceiver::class.java)))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun theAlarmStartsTheWatchThenReArms() {
        Background.at(tue, 15, 4)
        broadcast(Intent(ClockAlarm.ACTION))
        val app = context.applicationContext as Application
        Background.await("the watch started") { shadowOf(app).peekNextStartedService() != null }
        val i = shadowOf(app).nextStartedService
        assertEquals(WatchService::class.java.name, i.component!!.className)
        assertEquals("LIVE", i.getStringExtra(Jobs.EXTRA_KIND))
        Background.await("re-armed") { alarms().isNotEmpty() }
        assertEquals(epoch(tue, 15, 19), alarms().single().triggerAtMs)
    }

    @Test fun onAHolidayTheAlarmStartsNothing() {
        Background.calendar(WED, holidays = setOf(WED))
        Background.at(WED, 15, 19)
        broadcast(Intent(ClockAlarm.ACTION))
        Background.await("re-armed") { alarms().isNotEmpty() }
        Thread.sleep(300)
        assertEquals(null, shadowOf(context.applicationContext as Application).peekNextStartedService())
        assertEquals(epoch(thu, 15, 4), alarms().single().triggerAtMs)
    }
}
