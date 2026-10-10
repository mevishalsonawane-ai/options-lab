package com.optionslab.ira

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Zerodha reads shared by workers asking at once ([SingleFlight]): one read, never an old or pre-order answer. */
class SingleFlightTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest fun end() = scope.cancel()

    @Test fun workersAskingAtOnceShareOneRead() = runBlocking {
        val f = SingleFlight<String>()
        val reads = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val answers = (1..5).map {
            async(Dispatchers.IO) { f.run(7L, 2_000) { reads.incrementAndGet(); gate.await(); "book" } }
        }
        delay(150)
        gate.complete(Unit)
        val got = answers.awaitAll()
        assertEquals(1, reads.get(), "one read for five workers")
        assertTrue(got.all { it.value == "book" })
        assertEquals(4, got.count { it.joined })
        assertEquals(4L, f.joins)
    }

    @Test fun nothingIsKeptAfterTheReadAndAChangeIsNeverJoined() = runBlocking {
        val f = SingleFlight<Int>()
        val reads = AtomicInteger()
        assertEquals(1, f.run(1L, 2_000) { reads.incrementAndGet() }.value)
        assertEquals(2, f.run(1L, 2_000) { reads.incrementAndGet() }.value, "a read that ended is not reused")
        // A read on its way under change count 1; an order goes (count 2): the next ask reads again.
        val slow = CompletableDeferred<Unit>()
        val first = async(Dispatchers.IO) { f.run(1L, 2_000) { slow.await(); reads.incrementAndGet() } }
        delay(100)
        val after = f.run(2L, 2_000) { reads.incrementAndGet() }
        assertFalse(after.joined)
        slow.complete(Unit)
        first.await()
        assertEquals(4, reads.get())
        // Join window 0: never joined.
        val g = CompletableDeferred<Unit>()
        val a = async(Dispatchers.IO) { f.run(3L, 0) { g.await(); 1 } }
        delay(100)
        assertEquals(2, f.run(3L, 0) { 2 }.value)
        g.complete(Unit)
        assertEquals(1, a.await().value)
    }

    @Test fun aReadOlderThanTheWindowIsNotJoined() = runBlocking {
        var now = 0L
        val f = SingleFlight<Int> { now }
        val g = CompletableDeferred<Unit>()
        val a = async(Dispatchers.IO) { f.run(1L, 2_000) { g.await(); 1 } }
        delay(100)
        now = 2_500
        assertEquals(2, f.run(1L, 2_000) { 2 }.value, "a read started 2.5 s ago is too old to join")
        g.complete(Unit)
        assertEquals(1, a.await().value)
    }

    @Test fun aFailureGoesToEveryCallerAndACallerGivingUpDoesNotEndTheRead() = runBlocking {
        val f = SingleFlight<Int>()
        val g = CompletableDeferred<Unit>()
        val a = async(Dispatchers.IO) { runCatching { f.run(1L, 2_000) { g.await(); throw IOException("down") } } }
        delay(100)
        val b = async(Dispatchers.IO) { runCatching { f.run(1L, 2_000) { 5 } } }
        delay(100)
        g.complete(Unit)
        assertTrue(a.await().exceptionOrNull() is IOException)
        assertTrue(b.await().exceptionOrNull() is IOException, "the joined caller gets the read's failure")
        assertFailsWith<IOException> { f.run(2L, 2_000) { throw IOException("x") } }
        // A caller with a short limit stops waiting; the read finishes for the one who waits.
        val h = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val patient = async(Dispatchers.IO) { f.run(3L, 2_000) { h.await(); reads.incrementAndGet() } }
        delay(100)
        assertNull(withTimeoutOrNull(50) { f.run(3L, 2_000) { -1 } })
        h.complete(Unit)
        assertEquals(1, patient.await().value)
        assertEquals(1, reads.get())
        // The first caller cancelled mid-read: whoever waited on it reads for itself.
        val never = CompletableDeferred<Unit>()
        val leader = async(Dispatchers.IO) { f.run(4L, 2_000) { never.await(); -1 } }
        delay(100)
        val joiner = async(Dispatchers.IO) { f.run(4L, 2_000) { 7 } }
        delay(100)
        leader.cancel()
        val got = joiner.await()
        assertEquals(7, got.value)
        assertFalse(got.joined)
    }
}
