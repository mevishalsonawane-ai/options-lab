package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 22 Nov 2026 (B6 review: the app-lifetime scopes had no CoroutineExceptionHandler, so a failure escaping one of their jobs went to the
 * release crash handler, which ends the process): [ScopeGuard.handler] notes it and the scope goes on; the note names the scope, the
 * exception's class and the app's frame - never its message.
 */
class ScopeGuardTest : RobolectricTest() {
    @Test fun aFailureInAGuardedScopeIsNotedAndTheScopeGoesOn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ScopeGuard.handler("test scope"))
        val before = ScopeGuard.caughtCount
        withTimeout(5_000) { scope.launch { throw IllegalStateException("token=abc123 secret words") }.join() }
        assertEquals(before + 1, ScopeGuard.caughtCount)
        var ran = false
        withTimeout(5_000) { scope.launch { ran = true }.join() }
        assertTrue("the scope still runs its jobs", ran)
        // A cancellation is not a failure.
        withTimeout(5_000) { scope.launch { throw kotlinx.coroutines.CancellationException("stopped") }.join() }
        assertEquals(before + 1, ScopeGuard.caughtCount)
    }

    /**
     * Second review: a fatal error - out of memory, a class that cannot load or initialise - is not swallowed: it is passed on to the
     * thread's own handler (in the app the crash handler, which ends the process; the restart restores the open positions first).
     */
    @Test fun aFatalErrorIsPassedOnToTheThreadsOwnHandler() = runBlocking {
        val passed = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "scope-guard-test").apply { isDaemon = true; uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> passed += e } }
        }
        val scope = CoroutineScope(SupervisorJob() + pool.asCoroutineDispatcher() + ScopeGuard.handler("test scope"))
        try {
            val before = ScopeGuard.caughtCount
            val fatal = listOf(OutOfMemoryError("test"), StackOverflowError(), NoClassDefFoundError("test"), ExceptionInInitializerError("test"))
            for (e in fatal) withTimeout(5_000) { scope.launch { throw e }.join() }
            assertEquals("each passed on", fatal, passed.toList())
            assertEquals("none swallowed as noted", before, ScopeGuard.caughtCount)
            // An ordinary failure is still noted, never passed on.
            withTimeout(5_000) { scope.launch { throw IllegalStateException("ordinary") }.join() }
            assertEquals(fatal.size, passed.size)
            assertEquals(before + 1, ScopeGuard.caughtCount)
            assertTrue(ScopeGuard.fatal(OutOfMemoryError()) && ScopeGuard.fatal(ExceptionInInitializerError()) && !ScopeGuard.fatal(RuntimeException()))
        } finally { pool.shutdownNow() }
    }

    @Test fun theNoteNamesTheClassAndTheFrameNeverTheMessage() {
        val e = IllegalStateException("access_token=abc123 Rs 40,000")
        e.stackTrace = arrayOf(StackTraceElement("java.lang.Thread", "run", "Thread.java", 1),
            StackTraceElement("com.optionslab.app.data.KiteStream", "watch", "KiteStream.kt", 612))
        val line = ScopeGuard.line("price stream", e)
        assertEquals("price stream: a background job failed (IllegalStateException at KiteStream.watch:612); the app carries on", line)
        assertFalse(line.contains("abc123"))
        assertFalse(line.contains("40,000"))
    }
}
