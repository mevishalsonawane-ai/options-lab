package com.optionslab.app.work

import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 15 Nov (audit: ANRs in AlarmReceiver): [AlarmReceiver.finishWithin] - the alarm's broadcast is let go once, when its work ends
 * or after the bound, whichever is first; slow work is never cut short, and the processor is held for it from the bound to its
 * end (taken once, let go once); a throw still lets it go; quick work never waits for the bound and takes no hold.
 */
class AlarmReceiverBoundTest : RobolectricTest() {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test fun quickWorkLetsTheAlarmGoAtOnceAndOnce() = runBlocking {
        val finished = AtomicInteger(); val late = AtomicInteger(); val settled = AtomicInteger()
        val ran = AtomicInteger()
        val t0 = System.nanoTime()
        val job = AlarmReceiver.finishWithin(5_000, scope(), finish = { finished.incrementAndGet() }, late = { late.incrementAndGet() },
            settle = { settled.incrementAndGet() }) { ran.incrementAndGet() }
        withTimeout(5_000) { job.join() }
        assertTrue("never waits for the bound", (System.nanoTime() - t0) / 1_000_000 < 4_000)
        Thread.sleep(200)
        assertEquals(1, ran.get()); assertEquals(1, finished.get()); assertEquals("not late", 0, late.get())
        assertEquals("no hold taken, none let go", 0, settled.get())
    }

    @Test fun slowWorkLetsTheAlarmGoAfterTheBoundAndStillFinishes() = runBlocking {
        val finished = CountDownLatch(1); val count = AtomicInteger(); val late = AtomicInteger()
        val release = CountDownLatch(1); val done = AtomicInteger(); val settled = AtomicInteger()
        // The hold is taken before the broadcast is let go (the processor never sleeps in between).
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val job = AlarmReceiver.finishWithin(150, scope(), finish = { order += "finish"; count.incrementAndGet(); finished.countDown() },
            late = { order += "hold"; late.incrementAndGet() }, settle = { order += "release"; settled.incrementAndGet() }) {
            release.await(10, TimeUnit.SECONDS)
            done.incrementAndGet()
        }
        assertTrue("let go after the bound while the work still runs", finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, late.get())
        assertFalse("the work is not cut short", job.isCompleted)
        assertEquals(0, done.get())
        assertEquals("held while it runs", 0, settled.get())
        release.countDown()
        withTimeout(5_000) { job.join() }
        assertEquals("the work finished", 1, done.get())
        assertEquals("let go once only", 1, count.get())
        assertEquals("the hold let go once, when the work ended", 1, settled.get())
        assertEquals(listOf("hold", "finish", "release"), order.toList())
    }

    @Test fun workThatThrowsStillLetsTheAlarmGo() = runBlocking {
        val finished = CountDownLatch(1); val count = AtomicInteger()
        val failing = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> })
        val job = AlarmReceiver.finishWithin(5_000, failing, finish = { count.incrementAndGet(); finished.countDown() }) { throw IllegalStateException("a job failed") }
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        withTimeout(5_000) { job.join() }
        Thread.sleep(200)
        assertEquals(1, count.get())
    }

    @Test fun aFinishThatThrowsNeverStopsTheWork() = runBlocking {
        val done = AtomicInteger()
        val job = AlarmReceiver.finishWithin(50, scope(), finish = { throw IllegalStateException("already finished") }) { Thread.sleep(300); done.incrementAndGet() }
        withTimeout(5_000) { job.join() }
        assertEquals(1, done.get())
    }

    @Test fun aSlowWorkThatThrowsStillLetsTheHoldGo() = runBlocking {
        val settled = CountDownLatch(1); val late = AtomicInteger()
        val failing = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> })
        val job = AlarmReceiver.finishWithin(50, failing, finish = {}, late = { late.incrementAndGet() }, settle = { settled.countDown() }) {
            Thread.sleep(400); throw IllegalStateException("a job failed late")
        }
        withTimeout(5_000) { job.join() }
        assertTrue(settled.await(3, TimeUnit.SECONDS))
        assertEquals(1, late.get())
    }

    /**
     * 22 Nov 2026 (B6 review: a vault write that failed in the dead-man's check left the job by its scope - no handler - to the crash
     * handler, which in a release build ends the process): a work that throws is noted and its job ends normally; nothing reaches the
     * scope's handler (or, with none, the crash handler). A cancellation still goes up.
     */
    @Test fun aWorkThatThrowsIsNotedAndNeverLeavesItsJob() = runBlocking {
        val escaped = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val plain = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> escaped += e })
        val noted = com.optionslab.app.data.ScopeGuard.caughtCount
        val finished = CountDownLatch(1)
        val job = AlarmReceiver.finishWithin(5_000, plain, finish = { finished.countDown() }) { throw java.io.IOException("simulated disk fault") }
        withTimeout(5_000) { job.join() }
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertFalse("ended normally", job.isCancelled)
        assertTrue("nothing escaped the job: $escaped", escaped.isEmpty())
        assertEquals("noted once", noted + 1, com.optionslab.app.data.ScopeGuard.caughtCount)
    }

    /** These alarms are background broadcasts (no FLAG_RECEIVER_FOREGROUND): Android allows 60 s; the bound is well inside it. */
    @Test fun theBoundIsWellUnderAndroidsBackgroundBroadcastLimitAndTheHoldOutlastsIt() {
        assertTrue(AlarmReceiver.FINISH_MS in 20_000L..50_000L)
        assertTrue(AlarmReceiver.LATE_HOLD_MS in 30_000L..120_000L)
    }
}
