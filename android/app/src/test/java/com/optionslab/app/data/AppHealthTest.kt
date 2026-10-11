package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.HealthBook
import com.optionslab.ira.HealthTuner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The app's own health on the phone ([AppHealth]): the screen watchdog keeps a stall of a blocked main thread; the tuner
 * lengthens a non-critical cadence where stalls cluster and puts it back after three healthy days (a finding each time);
 * the diagnostics' "Health:" line; memory trims; and the hot calls read no setting.
 */
class AppHealthTest : RobolectricTest() {
    @Before fun up() { AppHealth.resetForTest(); SmartWorkers.resetForTest(); Market.testClock = null }

    @After fun down() {
        Speed.stop(); Speed.testPost = null; Speed.testClock = null
        AppHealth.resetForTest(); SmartWorkers.resetForTest(); Market.testClock = null
        SecurePrefs.countedThread = null
    }

    private fun today(): LocalDate = Market.today()
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    private fun awaitTrue(what: String, ms: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) { if (System.currentTimeMillis() > end) throw AssertionError(what); Thread.sleep(10) }
    }

    @Test fun theWatchdogKeepsAStallOfABlockedMainThread() {
        // The main thread's queue held (a blocked looper) and the watchdog's clock as given.
        val held = LinkedBlockingQueue<Runnable>()
        val clock = AtomicLong(10_000L)
        Speed.testClock = { clock.get() }
        Speed.testPost = { r -> held.add(r) }
        Speed.start()
        val posted = held.poll(5, TimeUnit.SECONDS)
        assertNotNull("the watchdog posts to the main thread", posted)
        // The main thread is stuck 900 ms, then runs the watchdog's empty task.
        clock.addAndGet(900)
        posted!!.run()
        awaitTrue("the stall is kept") { AppHealth.book().day(HealthBook.dayOf(System.currentTimeMillis()))?.stalls == 1 }
        val d = AppHealth.book().day(HealthBook.dayOf(System.currentTimeMillis()))!!
        assertEquals(900L, d.longestMs)
        Speed.stop()
        // A wait under 0.7 s is not one (the screen's own "Speed:" line still counts it).
        AppHealth.stall(650, "X.y")
        assertEquals(1, AppHealth.book().day(HealthBook.dayOf(System.currentTimeMillis()))!!.stalls)
    }

    @Test fun theTunerLengthensAClusteredCadenceAndRestoresItAfterThreeHealthyDays() {
        val day = today()
        var b = HealthBook.Book()
        repeat(3) { b = HealthBook.stall(b, at(day, 10, it), 900, "Choreographer.doFrame in BrainSection.invoke") }
        AppHealth.setForTest(b)
        assertEquals(15_000L, AppHealth.cadenceMs("brain.consensus"))
        val adj = AppHealth.tick(context, force = true)
        assertEquals(listOf("brain.consensus"), adj.map { it.key })
        assertEquals(30_000L, AppHealth.cadenceMs("brain.consensus"))
        // Told once as a finding (and a diary line); the other cadences and the order path untouched.
        val said = SmartWorkers.bus.recent(System.currentTimeMillis()).filter { it.who == "health tuner" }
        assertEquals(1, said.size)
        assertTrue(said[0].words, said[0].words.startsWith("the Brain card's consensus refresh slowed from every 15 s to every 30 s"))
        assertEquals(30_000L, AppHealth.cadenceMs("moves.flush"))
        assertEquals(30_000L, AppHealth.cadenceMs("findings.flush"))
        assertEquals(0L, AppHealth.cadenceMs("order.path"))
        // Four days on, three whole days healthy: back to normal.
        val tuned = AppHealth.book().tuned.getValue("brain.consensus")
        AppHealth.setForTest(HealthBook.Book(tuned = mapOf("brain.consensus" to tuned.copy(since = day.minusDays(4), stepOn = day.minusDays(4)))))
        val back = AppHealth.tick(context, force = true)
        assertEquals(listOf(true), back.map { it.restore })
        assertEquals(15_000L, AppHealth.cadenceMs("brain.consensus"))
        assertTrue(AppHealth.book().tuned.isEmpty())
    }

    @Test fun theDiagnosticsHaveAHealthLine() = runBlocking<Unit> {
        val day = today()
        var b = HealthBook.Book()
        repeat(2) { b = HealthBook.stall(b, at(day, 10, it), 1_100, "MoveRecorder.write") }
        b = HealthBook.exits(b, listOf(HealthBook.Exit(at(day, 9), HealthBook.ExitKind.ANR, "Input dispatching timed out")))
        AppHealth.setForTest(b)
        val line = AppHealth.diagLine()
        assertTrue(line, line.startsWith("Health: 2 stalls today (longest 1.1 s, most in MoveRecorder.write) · last ANR "))
        assertTrue(line, "in Input dispatching timed out · no crash seen · tuner: nothing adjusted" in line)
        val report = Diag.report()
        assertTrue(report.lines().any { it.startsWith("Health: 2 stalls today") })
    }

    @Test fun memoryTrimsAreCountedAndTheScreenHiddenIsNot() {
        AppHealth.trim(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(null, AppHealth.book().day(HealthBook.dayOf(System.currentTimeMillis())))
        AppHealth.trim(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        AppHealth.trim(android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE)
        assertEquals(2, AppHealth.book().day(HealthBook.dayOf(System.currentTimeMillis()))!!.trims)
    }

    @Test fun theHotCallsReadNoSetting() {
        SecurePrefs.countedThread = Thread.currentThread()
        val before = SecurePrefs.readsForTest.get()
        repeat(100) { AppHealth.cadenceMs("moves.flush"); AppHealth.cadenceMs("findings.flush") }
        AppHealth.stall(800, "X.y"); AppHealth.reconnect(); AppHealth.stale(); AppHealth.overrun("Market brain")
        assertEquals(before, SecurePrefs.readsForTest.get())
        assertTrue(HealthTuner.TUNABLES.none { HealthTuner.FORBIDDEN.containsMatchIn(it.key) })
    }
}
