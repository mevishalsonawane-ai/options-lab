package com.optionslab.ira

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A store's disk write done on its own background thread, never on the caller's (19 Oct, Boss's diagnostics: "Paper orders took
 * 5.7 s typical from decision to sent"). The paper book and the passed-research arms' book each wrote their encrypted vault file
 * (a Keystore encryption and two disk syncs, a second or more on a Pixel's StrongBox) before the caller went on, so every paper
 * entry waited on two or three writes, one after another, and the next index's entry waited behind them.
 *
 * [kick] returns at once: [write] runs next on [executor] (one thread: writes stay in order). Kicks while a write is queued are
 * folded into it; a kick while one is running queues one more, so the newest book is always written last. [write] itself takes
 * the newest pending book (the store keeps it) and must never throw past this class (a throw is counted in [failures] only).
 * [inline]: the write runs on the caller's thread instead (tests that read the file at once; never in the app).
 */
class WriteBehind(private val executor: Executor, private val write: () -> Unit) {
    private val queued = AtomicBoolean(false)
    /** Writes run, and writes that threw (memory only; for tests and the diagnostics). */
    val runs = AtomicLong()
    val failures = AtomicLong()

    @Volatile var inline: Boolean = false

    /** The newest change is written soon, on [executor]. Never blocks, never throws. */
    fun kick() {
        if (inline) { runOnce(); return }
        if (!queued.compareAndSet(false, true)) return
        try {
            executor.execute { queued.set(false); runOnce() }
        } catch (e: RejectedExecutionException) {
            // The writer is gone (the process is ending): written here rather than lost.
            queued.set(false)
            runOnce()
        }
    }

    private fun runOnce() {
        try { write() } catch (e: Throwable) { failures.incrementAndGet() } finally { runs.incrementAndGet() }
    }

    /**
     * Waits until every write kicked before this call has run (a wipe, the screen going away, a test), at most [timeoutMs].
     * False when it timed out. Never on the order path.
     */
    fun await(timeoutMs: Long): Boolean {
        if (inline) return true
        val done = CountDownLatch(1)
        return try {
            executor.execute { done.countDown() }
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) { true }
    }

    companion object {
        /** One daemon writer thread named [name] (one per store: a slow write of one book never delays another's). */
        fun thread(name: String): java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }
    }
}
