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
 * or after the bound, whichever is first; slow work is never cut short; a throw still lets it go; quick work never waits for the
 * bound.
 */
class AlarmReceiverBoundTest : RobolectricTest() {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test fun quickWorkLetsTheAlarmGoAtOnceAndOnce() = runBlocking {
        val finished = AtomicInteger(); val late = AtomicInteger()
        val ran = AtomicInteger()
        val t0 = System.nanoTime()
        val job = AlarmReceiver.finishWithin(5_000, scope(), finish = { finished.incrementAndGet() }, late = { late.incrementAndGet() }) { ran.incrementAndGet() }
        withTimeout(5_000) { job.join() }
        assertTrue("never waits for the bound", (System.nanoTime() - t0) / 1_000_000 < 4_000)
        Thread.sleep(200)
        assertEquals(1, ran.get()); assertEquals(1, finished.get()); assertEquals("not late", 0, late.get())
    }

    @Test fun slowWorkLetsTheAlarmGoAfterTheBoundAndStillFinishes() = runBlocking {
        val finished = CountDownLatch(1); val count = AtomicInteger(); val late = AtomicInteger()
        val release = CountDownLatch(1); val done = AtomicInteger()
        val job = AlarmReceiver.finishWithin(150, scope(), finish = { count.incrementAndGet(); finished.countDown() }, late = { late.incrementAndGet() }) {
            release.await(10, TimeUnit.SECONDS)
            done.incrementAndGet()
        }
        assertTrue("let go after the bound while the work still runs", finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, late.get())
        assertFalse("the work is not cut short", job.isCompleted)
        assertEquals(0, done.get())
        release.countDown()
        withTimeout(5_000) { job.join() }
        assertEquals("the work finished", 1, done.get())
        assertEquals("let go once only", 1, count.get())
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

    @Test fun theBoundIsUnderAndroidsForegroundBroadcastLimit() {
        assertTrue(AlarmReceiver.FINISH_MS in 1_000L..9_000L)
    }
}
