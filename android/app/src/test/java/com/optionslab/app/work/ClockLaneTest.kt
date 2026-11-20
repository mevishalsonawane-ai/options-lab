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
import kotlinx.coroutines.async
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
     * 20 Nov 2026 (CI on 80aa50e15: "timed out waiting for loss limit round 2"): a poke is one round, and a round never starts the
     * loss limit beside its own run still under way (never two at once) - and the test counts a run as done when its work is done, a
     * moment before its supervisor step ends. A poke in that moment found the last run still going, so that round's limit fell to the
     * next 15 s mark, past the test's wait. Poked only once that step has ended - by the same look the supervisor's start makes (a run
     * not completed, [com.optionslab.ira.Supervisor.kick]) - the poked round must run the limit itself.
     */
    private fun pokeOnceTheLossLimitEnded() {
        Background.await("the loss limit's run before ended", 10_000) { !lossStepUnderWay() }
        ClockLane.poke()
    }

    /** The loss limit's supervisor step claimed and not completed (the supervisor's own map, read as a test may). */
    private fun lossStepUnderWay(): Boolean {
        val sup = WatchWorkers.supervisor ?: return false
        val f = com.optionslab.ira.Supervisor::class.java.getDeclaredField("inFlight").apply { isAccessible = true }
        val job = (f.get(sup) as Map<*, *>)[ClockLane.LOSS] as? kotlinx.coroutines.Job
        return job != null && !job.isCompleted
    }

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
     * A stuck clock step never holds the loss limit or the next round back: with the expiry square-off hung, the loss limit runs every
     * round (each poked once the limit's last run has ended: a round never starts it beside its own run under way). The other square-offs come after it in their one step (19 Nov 2026: one sequence in the pass's order, never two
     * square-offs selling one contract at once), and run once it ends. The hung one is never started a second time beside itself.
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
                ClockLane.runLane(context, stop = { ctl.ended }, mode = { ctl.mode }, held = { true }, supervisor = { WatchWorkers.supervisor })
            }
            Background.await("the square-off hung", 10_000) { expiryStarts.get() == 1 }
            val lossBefore = ran.count { it == ClockLane.LOSS }
            for (i in 1..3) {
                pokeOnceTheLossLimitEnded()
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

    /** The pass runs a clock step as the same supervisor step: the lane's run under way is waited for, never run beside it. */
    @Test fun thePassWaitsForTheLanesRunAndNeverRunsBesideIt() = runBlocking {
        val inside = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val runs = java.util.concurrent.atomic.AtomicInteger()
        val release = CompletableDeferred<Unit>()
        ClockLane.testWork = { n ->
            if (n == ClockLane.EXPIRY) {
                most.accumulateAndGet(inside.incrementAndGet(), ::maxOf); runs.incrementAndGet()
                release.await(); inside.decrementAndGet()
            }
        }
        val run = WatchWorkers.begin(this)
        try {
            val s = com.optionslab.app.data.AppSettings.load()
            // Kicked from a worker thread, as the lane does (a kicked step keeps its caller's dispatcher: never this blocked thread's).
            kotlinx.coroutines.withContext(Dispatchers.IO) { WatchWorkers.supervisor!!.kick(ClockLane.GROUP, listOf(ClockLane.step(context, ClockLane.SQUARE_OFFS, s))) }
            Background.await("the lane's run", 5_000) { runs.get() == 1 }
            val pass = launch(Dispatchers.IO) { Tasks.clockStep(context, s, ClockLane.SQUARE_OFFS, inOrder = false) }
            Thread.sleep(500)
            assertFalse("the pass waits for the lane's run (what it closes is booked before the arms after it)", pass.isCompleted)
            release.complete(Unit)
            pass.join()
            // 19 Nov 2026 (second review): the lane's run just completed stands for the pass's own - never a second run back to back.
            assertEquals(1, runs.get())
            assertEquals("never two at once", 1, most.get())
        } finally {
            release.complete(Unit)
            WatchWorkers.end(run)
        }
    }

    /**
     * A due step with no completed run 45 s after its time while anything is held: one RISK alert per step. Nothing held: none. 19 Nov
     * 2026 (second review): the square-offs starved behind the hung one are judged by their own due time and told too; the loss
     * limit, completing every round, never is.
     */
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
            // Two minutes on with nothing held: the hung square-off is late, but nothing is told.
            repeat(6) { fake += 20_000; ClockLane.poke(); Thread.sleep(300) }
            assertTrue(lateAlerts().isEmpty())
            // Positions open: told once, the hung one only.
            held = true
            fake += 20_000
            pokeOnceTheLossLimitEnded()
            Background.await("the late alert", 5_000) { lateAlerts().isNotEmpty() }
            val at = fake
            Background.await("that round's loss limit", 5_000) { ClockLane.lastDone(ClockLane.LOSS) == at }
            Background.await("every starved square-off told", 5_000) { lateAlerts().size >= 3 }
            for (n in listOf(ClockLane.EXPIRY, ClockLane.MCX_EXPIRY, ClockLane.FLAT_BY))
                assertEquals(n, 1, lateAlerts().count { it.text.contains("The $n check") })
            assertFalse(lateAlerts().any { it.text.contains(ClockLane.LOSS) })
            fake += 20_000
            val next = fake
            pokeOnceTheLossLimitEnded()
            Background.await("the next round", 5_000) { ClockLane.lastDone(ClockLane.LOSS) == next }
            assertEquals("told once", 3, lateAlerts().size)
            ctl.ended = true; ClockLane.poke(); lane.join()
        } finally {
            release.complete(Unit)
            WatchWorkers.end(run)
        }
    }

    /**
     * 19 Nov 2026 (second review, item 5): the pass's arms wait for a loss-limit run under way (the lane's or the pass's), bounded,
     * as the old one-after-another pass did; then go on with a diary line.
     */
    @Test fun theArmsWaitForALossLimitRunUnderWayBounded() = runBlocking {
        val release = CompletableDeferred<Unit>()
        ClockLane.testWork = { n -> if (n == ClockLane.LOSS) release.await() }
        val run = WatchWorkers.begin(this)
        try {
            assertTrue("none running: no wait", Tasks.awaitLossLimit(300))
            val s = com.optionslab.app.data.AppSettings.load()
            kotlinx.coroutines.withContext(Dispatchers.IO) { WatchWorkers.supervisor!!.kick(ClockLane.GROUP, listOf(ClockLane.step(context, ClockLane.LOSS, s))) }
            Background.await("the loss limit running", 5_000) { WatchWorkers.supervisor!!.busy(ClockLane.LOSS) }
            assertFalse("bounded: the arms go on past the wait", Tasks.awaitLossLimit(300))
            val waiting = async(Dispatchers.IO) { Tasks.awaitLossLimit(20_000) }
            Thread.sleep(300)
            assertFalse("still waiting while it runs", waiting.isCompleted)
            release.complete(Unit)
            assertTrue("it ended: the arms go", waiting.await())
        } finally {
            release.complete(Unit)
            WatchWorkers.end(run)
        }
    }

    /**
     * 19 Nov 2026 (third review, review round): a square-off goes without the turn only behind the loss limit's sales HOLDING it -
     * behind another square-off (the sales merely queued there too) it never does: two square-offs never run at once.
     */
    @Test fun aSquareOffNeverGoesWithoutTheTurnBehindAnotherSquareOff() = runBlocking {
        ClockLane.salesTurnWaitMs = 500L
        val ran = java.util.concurrent.atomic.AtomicInteger()
        ClockLane.testWork = { n -> if (n == ClockLane.EXPIRY) ran.incrementAndGet() }
        val release = CompletableDeferred<Unit>()
        val hold = launch(Dispatchers.IO) { ClockLane.holdTurnForTest(ClockLane.FLAT_BY, release) }
        try {
            Background.await("another square-off holds the turn", 5_000) { ClockLane.squareOffRunning() }
            val queued = launch(Dispatchers.IO) { ClockLane.queueSalesForTest(10_000L) }
            Background.await("the sales queued behind it", 5_000) { ClockLane.salesQueuedForTest() }
            val s = com.optionslab.app.data.AppSettings.load()
            kotlinx.coroutines.withContext(Dispatchers.IO) { ClockLane.run(context, ClockLane.EXPIRY, s) }
            assertEquals("never ran beside the other square-off", 0, ran.get())
            release.complete(Unit)
            queued.join()
        } finally {
            release.complete(Unit)
            hold.join()
        }
    }

    /**
     * 19 Nov 2026 (third review, item B): once the limit has tripped today the arms never wait for a loss-limit run (their gates read
     * the stop, and the groups also run the exits of positions still open, which must not wait on the limit's sales).
     */
    @Test fun theArmsNeverWaitForTheLossLimitOnceItTripped() = runBlocking {
        val release = CompletableDeferred<Unit>()
        ClockLane.testWork = { n -> if (n == ClockLane.LOSS) release.await() }
        val run = WatchWorkers.begin(this)
        try {
            com.optionslab.app.security.SecurePrefs.put("breaker.day", com.optionslab.app.data.Market.today().toString())
            com.optionslab.app.data.LossBreaker.forgetForTest()
            assertTrue(com.optionslab.app.data.LossBreaker.trippedToday())
            val s = com.optionslab.app.data.AppSettings.load()
            kotlinx.coroutines.withContext(Dispatchers.IO) { WatchWorkers.supervisor!!.kick(ClockLane.GROUP, listOf(ClockLane.step(context, ClockLane.LOSS, s))) }
            Background.await("the loss limit running", 5_000) { WatchWorkers.supervisor!!.busy(ClockLane.LOSS) }
            val t0 = System.currentTimeMillis()
            assertTrue(Tasks.awaitLossLimit(10_000))
            val ms = System.currentTimeMillis() - t0
            assertTrue("no wait: $ms ms", ms < 2_000L)
        } finally {
            release.complete(Unit)
            WatchWorkers.end(run)
        }
    }

    /**
     * 19 Nov 2026 (second review, item 6): the stop rescue, now on its own beside the square-offs, never runs while a square-off holds
     * the turn (it may be closing the very contract): it waits, bounded, then is left to the next pass; it runs once the turn is free.
     * (The rescue itself is Jarvis's: this is its gate in the pass, [Tasks.afterSquareOffs].)
     */
    @Test fun theStopRescueWaitsWhileASquareOffHoldsTheTurn() = runBlocking {
        val ran = java.util.concurrent.atomic.AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val hold = launch(Dispatchers.IO) { ClockLane.holdTurnForTest(ClockLane.SQUARE_OFFS, release) }
        try {
            Background.await("a square-off holds the turn", 5_000) { ClockLane.squareOffRunning() }
            assertFalse("bounded: left to the next pass", Tasks.afterSquareOffs(300) { ran.incrementAndGet() })
            assertEquals("never beside the square-off", 0, ran.get())
            val waiting = async(Dispatchers.IO) { Tasks.afterSquareOffs(20_000) { ran.incrementAndGet() } }
            Thread.sleep(300)
            assertEquals(0, ran.get())
            release.complete(Unit)
            assertTrue(waiting.await())
            assertEquals("then it runs", 1, ran.get())
        } finally {
            release.complete(Unit)
            hold.join()
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
