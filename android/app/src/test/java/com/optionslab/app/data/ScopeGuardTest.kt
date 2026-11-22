package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
