package com.optionslab.ira

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The watch's workers ([Supervisor]): isolated, on their own clocks, timed out, restarted with backoff, reported. */
class SupervisorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timed = ConcurrentHashMap<String, Int>()
    private val errors = CopyOnWriteArrayList<String>()
    private val sup = Supervisor(scope, onTimed = { n, _ -> timed.merge(n, 1, Int::plus) }, onError = { n, e -> errors += "$n:${e.javaClass.simpleName}" })

    @AfterTest fun end() = scope.cancel()

    private suspend fun until(ms: Long = 5_000, cond: () -> Boolean) = withTimeout(ms) { while (!cond()) delay(5) }

    private fun every(ms: Long): suspend () -> Long? = { ms }

    @Test fun aCrashingWorkerNeverStopsAnotherAndIsRestartedWithBackoff() = runBlocking {
        val good = AtomicInteger()
        val bad = AtomicInteger()
        sup.start(Supervisor.Spec("good", "arms", 1_000, cancelOnTimeout = true, nextDelayMs = every(10))) { good.incrementAndGet() }
        sup.start(Supervisor.Spec("bad", "arms", 1_000, cancelOnTimeout = true, nextDelayMs = every(10), backoffBaseMs = 20, backoffCapMs = 40)) {
            bad.incrementAndGet(); throw IOException("down")
        }
        until { good.get() >= 20 && bad.get() >= 4 }
        val s = sup.status("bad")!!
        assertTrue(s.errors >= 4)
        assertEquals("IOException", s.lastError)
        assertTrue(s.failuresInRow >= 4)
        assertEquals(0, sup.status("good")!!.errors)
        assertTrue(errors.all { it == "bad:IOException" })
        // Even an Error is kept, never thrown up.
        val errs = AtomicInteger()
        sup.start(Supervisor.Spec("error", "x", 1_000, cancelOnTimeout = true, nextDelayMs = every(10), backoffBaseMs = 10)) {
            errs.incrementAndGet(); throw StackOverflowError()
        }
        until { errs.get() >= 2 }
        assertTrue(good.get() > 20)
    }

    @Test fun backoffDoublesToItsCapAndResetsAfterASuccess() {
        assertEquals(0L, Supervisor.backoffMs(0, 5_000, 300_000))
        assertEquals(5_000L, Supervisor.backoffMs(1, 5_000, 300_000))
        assertEquals(10_000L, Supervisor.backoffMs(2, 5_000, 300_000))
        assertEquals(40_000L, Supervisor.backoffMs(4, 5_000, 300_000))
        assertEquals(300_000L, Supervisor.backoffMs(50, 5_000, 300_000))
        runBlocking {
            val runs = AtomicInteger()
            sup.start(Supervisor.Spec("flaky", "x", 1_000, cancelOnTimeout = true, nextDelayMs = every(5), backoffBaseMs = 10)) {
                if (runs.incrementAndGet() % 2 == 1) throw IOException()
            }
            until { runs.get() >= 6 }
            until { sup.status("flaky")!!.failuresInRow == 0 }
        }
    }

    @Test fun aStuckReadIsCancelledAtItsLimitAndTheWorkerGoesOn() = runBlocking {
        val starts = AtomicInteger()
        sup.start(Supervisor.Spec("quotes", "data", 50, cancelOnTimeout = true, nextDelayMs = every(5), backoffBaseMs = 5)) {
            starts.incrementAndGet(); delay(10_000)
        }
        until { starts.get() >= 3 }
        val s = sup.status("quotes")!!
        assertEquals("TimedOut", s.lastError)
        assertTrue(s.errors >= 2)
    }

    @Test fun anOrderPlacingRunIsNeverCutButShownOverrun() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val finished = AtomicInteger()
        val starts = AtomicInteger()
        sup.start(Supervisor.Spec("ORB arms", "arms", 50, cancelOnTimeout = false, nextDelayMs = every(5), backoffBaseMs = 5)) {
            starts.incrementAndGet(); release.await(); finished.incrementAndGet()
        }
        until { sup.status("ORB arms")?.state == Supervisor.State.OVERRUN }
        delay(100)
        assertEquals(1, starts.get(), "never started again while it runs")
        assertTrue(sup.busy("ORB arms"))
        release.complete(Unit)
        until { finished.get() >= 1 && sup.status("ORB arms")!!.errors >= 1 }
        assertEquals("TimedOut", sup.status("ORB arms")!!.lastError)
        until { starts.get() >= 2 }
    }

    @Test fun aWorkerSleepsWhileItsMarketIsClosed() = runBlocking {
        var open = false
        val runs = AtomicInteger()
        sup.start(Supervisor.Spec("MCX arms", "arms", 1_000, cancelOnTimeout = true, nextDelayMs = { if (open) 5L else null }, sleepMs = 20)) {
            runs.incrementAndGet()
        }
        until { sup.status("MCX arms")?.state == Supervisor.State.SLEEPING }
        delay(80)
        assertEquals(0, runs.get(), "no run, no busy loop, while closed")
        open = true
        until { runs.get() >= 2 }
        // A schedule that throws is a failure like any other: kept, and the worker carries on.
        var bad = true
        sup.start(Supervisor.Spec("schedule", "x", 1_000, cancelOnTimeout = true, nextDelayMs = { if (bad) { bad = false; throw IllegalStateException() } else 5L }, backoffBaseMs = 5)) {}
        until { (sup.status("schedule")?.runs ?: 0) >= 1 }
        assertEquals("IllegalStateException", sup.status("schedule")!!.lastError)
    }

    @Test fun aSecondStartOfARunningWorkerIsTheSameWorkerAndStopEndsThem() = runBlocking {
        val runs = AtomicInteger()
        val spec = Supervisor.Spec("w", "x", 1_000, cancelOnTimeout = true, nextDelayMs = every(5))
        val a = sup.start(spec) { runs.incrementAndGet() }
        val b = sup.start(spec) { runs.addAndGet(1_000) }
        assertTrue(a === b)
        until { runs.get() >= 3 }
        assertTrue(runs.get() < 1_000)
        sup.stop()
        until { sup.status("w")?.state == Supervisor.State.STOPPED }
        val n = runs.get()
        delay(50)
        assertEquals(n, runs.get())
    }

    private class Marker(val v: String) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Marker> }

    @Test fun aGroupRunsSideBySideWithTheCallersContextAndEachInItsOwnTry() = runBlocking {
        val seen = ConcurrentHashMap<String, String>()
        val started = AtomicInteger()
        val allIn = CompletableDeferred<Unit>()
        fun step(n: String, fail: Boolean = false) = Supervisor.Step(n) {
            seen[n] = kotlin.coroutines.coroutineContext[Marker]?.v ?: "none"
            if (started.incrementAndGet() == 3) allIn.complete(Unit)
            // Each waits for all three to be in: only side by side can they all finish.
            allIn.await()
            if (fail) throw IOException()
        }
        val late = withContext(Marker("bar 10:05")) {
            sup.group("arms", listOf(step("ORB arms"), step("Pine scripts", fail = true), step("Night (R3)")), waitMs = 2_000)
        }
        assertEquals(emptyList(), late)
        assertEquals(mapOf("ORB arms" to "bar 10:05", "Pine scripts" to "bar 10:05", "Night (R3)" to "bar 10:05"), seen.toMap())
        assertEquals(listOf("Pine scripts:IOException"), errors.toList())
        assertEquals(1, sup.status("Pine scripts")!!.errors)
        assertEquals(0, sup.status("ORB arms")!!.errors)
        assertEquals(1, timed["ORB arms"])
    }

    @Test fun aSlowStepIsLeftToFinishAndNotStartedTwice() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val slowRuns = AtomicInteger()
        val fastRuns = AtomicInteger()
        val slow = Supervisor.Step("strategies") { slowRuns.incrementAndGet(); release.await() }
        val fast = Supervisor.Step("Solo") { fastRuns.incrementAndGet() }
        val t0 = System.currentTimeMillis()
        assertEquals(listOf("strategies"), sup.group("after", listOf(slow, fast), waitMs = 100))
        assertTrue(System.currentTimeMillis() - t0 < 2_000, "the pass goes on")
        assertEquals(Supervisor.State.OVERRUN, sup.status("strategies")!!.state)
        // The next pass: the slow one is still running, so only the fast one runs again.
        assertEquals(listOf("strategies"), sup.group("after", listOf(slow, fast), waitMs = 50))
        assertEquals(1, slowRuns.get())
        assertEquals(2, fastRuns.get())
        release.complete(Unit)
        until { !sup.busy("strategies") }
        assertEquals(emptyList(), sup.group("after", listOf(slow), waitMs = 1_000).also { assertEquals(2, slowRuns.get()) })
    }

    @Test fun aGroupStepsCancellationFromInsideIsKeptButTheOwnersEndIsPassedOn() = runBlocking {
        val r = sup.group("x", listOf(Supervisor.Step("inner timeout") { withTimeout(10) { delay(1_000) } }), waitMs = 2_000)
        assertEquals(emptyList(), r)
        assertEquals("TimeoutCancellationException", sup.status("inner timeout")!!.lastError)
        // The supervisor's scope ending ends a step still running (the service stopped).
        val own = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s2 = Supervisor(own)
        val ended = CompletableDeferred<Unit>()
        s2.group("y", listOf(Supervisor.Step("long") { try { delay(10_000) } finally { ended.complete(Unit) } }), waitMs = 20)
        own.cancel()
        withTimeout(2_000) { ended.await() }
        until { !s2.busy("long") }
        assertFalse(s2.busy("long"))
    }

    @Test fun statusInWords() {
        val s = Supervisor.Status("Jarvis words", "analysis", Supervisor.State.IDLE, lastStart = 0L, lastMs = 1_200, runs = 3, errors = 2, lastError = "IOException")
        assertEquals("Jarvis words (analysis): idle · last run 09:15:00, 1.2 s · 3 runs · 2 errors (last: IOException)",
            Supervisor.words(s) { "09:15:00" })
        assertEquals("a (b): asleep (its market is closed) · 1 run", Supervisor.words(Supervisor.Status("a", "b", Supervisor.State.SLEEPING, runs = 1)) { "" })
        assertEquals("a (b): backing off after a failure · 0 runs · 1 error", Supervisor.words(Supervisor.Status("a", "b", Supervisor.State.BACKING_OFF, errors = 1)) { "" })
    }
}
