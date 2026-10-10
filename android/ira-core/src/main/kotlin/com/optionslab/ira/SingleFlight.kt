package com.optionslab.ira

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * One read shared by everyone who asks for it at once (Boss, 10 Oct: the watch's workers run side by side, and each asking
 * Zerodha for the same positions, orders or margins in the same moment is the same read several times over): a caller
 * whose read of the same [key] is already on its way - started at most `joinWithinMs` ago - waits for that one instead of
 * sending another. Nothing is kept after the read ends: the next ask after it reads again, so a reader never gets an
 * answer older than the join window, and never one from before a change ([key] carries the change count: an order from
 * this phone or one Zerodha's stream reports makes a new key, and a read started before it is never joined).
 *
 * The first caller reads in its own coroutine, exactly as it did alone (no hand-over to another thread: nothing is added
 * to its time). A failure goes to every caller of that read, as their own read's failure would; if the first caller is
 * cancelled (its own time limit), each caller waiting on it reads for itself. Thread-safe.
 */
class SingleFlight<T>(private val clock: () -> Long = { System.currentTimeMillis() }) {
    /** A read's answer: [value], when its read [startedAt] (epoch ms), and whether this caller [joined] another's read. */
    data class Shared<T>(val value: T, val startedAt: Long, val joined: Boolean)

    private class Flight<T>(val key: Any, val startedAt: Long, val d: CompletableDeferred<T>)

    /** The first caller stopped waiting (it was cancelled): the read did not finish for the others. */
    private class LeaderGone : RuntimeException()

    private var current: Flight<T>? = null

    /** Reads joined (not sent again) so far: for the diagnostics. */
    @Volatile var joins = 0L
        private set

    /**
     * [read], or the same read already on its way for [key] when it started at most [joinWithinMs] ago (0: never join).
     * [context]: markers the read runs with (the caller's own, e.g. a quick-read marker); never a dispatcher change needed.
     */
    suspend fun run(key: Any, joinWithinMs: Long, context: CoroutineContext = EmptyCoroutineContext, read: suspend () -> T): Shared<T> {
        val now = clock()
        var mine: CompletableDeferred<T>? = null
        val flight = synchronized(this) {
            val c = current
            if (joinWithinMs > 0 && c != null && c.key == key && !c.d.isCompleted && now - c.startedAt in 0..joinWithinMs) {
                joins++
                c
            } else {
                val f = Flight(key, now, CompletableDeferred<T>())
                mine = f.d
                current = f
                f
            }
        }
        val own = mine
        if (own == null) {
            return try {
                Shared(flight.d.await(), flight.startedAt, true)
            } catch (e: LeaderGone) {
                val t0 = clock()
                Shared(withContext(context) { read() }, t0, false)
            }
        }
        try {
            val v = withContext(context) { read() }
            own.complete(v)
            return Shared(v, now, false)
        } catch (e: CancellationException) {
            own.completeExceptionally(LeaderGone())
            throw e
        } catch (e: Throwable) {
            own.completeExceptionally(e)
            throw e
        }
    }
}
