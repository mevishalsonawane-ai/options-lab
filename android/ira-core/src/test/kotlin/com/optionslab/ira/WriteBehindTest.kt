package com.optionslab.ira

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WriteBehind] (19 Oct, order speed): a slow vault write (1.5 s, as a Pixel's StrongBox) never holds up the caller; the newest
 * book is always the last one written; a failed write never escapes; [WriteBehind.await] waits for what was kicked.
 */
class WriteBehindTest {
    /** A store as the paper book keeps it: the newest book pending, written by [write] (here: slowly, into [disk]). */
    private class SlowStore(val writeMs: Long) {
        val pending = AtomicReference<Int?>(null)
        val disk = CopyOnWriteArrayList<Int>()
        val pool = WriteBehind.thread("test-writer")
        val writer = WriteBehind(pool) {
            val v = pending.getAndSet(null) ?: return@WriteBehind
            Thread.sleep(writeMs)
            disk += v
        }
        fun save(v: Int) { pending.set(v); writer.kick() }
    }

    @Test fun aSlowWriteNeverHoldsTheCaller() {
        val s = SlowStore(writeMs = 1_500)
        try {
            val t0 = System.nanoTime()
            // The 19 Oct morning: 11 entries, each saving the book (before: each waited its write, one after another).
            for (i in 1..11) s.save(i)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 200, "11 saves returned in $ms ms with a 1.5 s write each")
            assertTrue(s.writer.await(10_000))
            assertEquals(11, s.disk.last(), "the newest book is the last one on disk")
            assertTrue(s.disk.size <= 3, "saves while a write was queued were folded into it: ${s.disk}")
            assertEquals(s.disk.sorted(), s.disk.toList(), "never an older book over a newer one")
        } finally { s.pool.shutdownNow() }
    }

    @Test fun aSaveDuringAWriteIsWrittenAfterIt() {
        val s = SlowStore(writeMs = 300)
        try {
            s.save(1)
            Thread.sleep(100)                       // the first write is running
            s.save(2)
            assertTrue(s.writer.await(5_000))
            assertEquals(listOf(1, 2), s.disk.toList())
        } finally { s.pool.shutdownNow() }
    }

    @Test fun aFailedWriteIsCountedAndNeverThrown() {
        val pool = WriteBehind.thread("test-writer-2")
        try {
            val w = WriteBehind(pool) { error("disk full") }
            w.kick()
            assertTrue(w.await(5_000))
            assertEquals(1L, w.failures.get())
            assertEquals(1L, w.runs.get())
            w.inline = true
            w.kick()                                  // inline: on the caller's thread, still never thrown
            assertEquals(2L, w.failures.get())
            assertTrue(w.await(1))
        } finally { pool.shutdownNow() }
    }

    @Test fun aWriterThatIsGoneWritesOnTheCaller() {
        val pool = WriteBehind.thread("test-writer-3")
        pool.shutdownNow()
        val done = CopyOnWriteArrayList<String>()
        val w = WriteBehind(pool) { done += Thread.currentThread().name }
        w.kick()
        assertEquals(listOf(Thread.currentThread().name), done.toList())
        assertTrue(w.await(1_000))
    }
}
