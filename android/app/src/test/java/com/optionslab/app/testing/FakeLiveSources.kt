package com.optionslab.app.testing

import com.optionslab.app.data.LiveSelfTest
import com.optionslab.engine.Upstox
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * TEST ONLY: the Live check's sources ([LiveSelfTest.Sources]) besides Zerodha's REST API (that is [FakeKite]). [TestApp]
 * hands every test a closed-market one, so a Live check a screen test starts never waits on a stream or reaches the
 * internet whatever the hour CI runs at; data/LiveSelfTestTest drives an open one.
 *
 * A price stream the test drives: while [live], every followed instrument (but the [silent] ones) ticks every [everyMs] on
 * a thread of its own, as the socket would; [dropForCheck] takes it down and, when [reconnects], back up after
 * [reconnectMs].
 */
class FakeLiveSources(open: Boolean = true) : LiveSelfTest.Sources, AutoCloseable {
    @Volatile var open = open
    @Volatile var live = true
    @Volatile var connectAfterMs: Long? = 0L
    @Volatile var everyMs = 25L
    @Volatile var silent: Set<Long> = emptySet()
    @Volatile var dropWorks = true
    @Volatile var reconnects = true
    @Volatile var reconnectMs = 200L
    @Volatile var offset: Pair<Long, String>? = 120L to "Zerodha's server clock"
    @Volatile var upstox: List<Upstox.Bar> = emptyList()
    /** How long the public candle feed takes to answer. */
    @Volatile var upstoxDelayMs = 0L
    @Volatile var paperAt = 0L
    @Volatile var stopsAt = 0L
    /** What the app's other position books say ([LiveSelfTest.Sources.booksOpen]): true, something is open in one. */
    @Volatile var booksOpen = false
    val price = ConcurrentHashMap<Long, Double>(mapOf(256265L to 24_480.0, 260105L to 52_120.0))
    val followed: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val starts = ArrayList<Collection<Long>>()
    @Volatile var released = 0
    @Volatile var drops = 0
    private val seen = ConcurrentHashMap<Long, Pair<Double, Long>>()
    private val count = AtomicLong()
    @Volatile private var lastAt = 0L
    @Volatile private var running = true
    /** The socket's thread, started by the first [streamStart] (a fake that is never asked costs no thread). */
    @Volatile private var ticker: Thread? = null

    @Synchronized private fun tick() {
        if (ticker != null) return
        ticker = Thread({
            while (running) {
                if (live) {
                    val now = System.currentTimeMillis()
                    for (t in followed) if (t !in silent) { seen[t] = (price[t] ?: 100.0) to now; count.incrementAndGet(); lastAt = now }
                }
                try { Thread.sleep(everyMs) } catch (_: InterruptedException) { return@Thread }
            }
        }, "fake-stream").apply { isDaemon = true; start() }
    }

    private fun later(ms: Long, block: () -> Unit) = Thread({ Thread.sleep(ms); block() }, "fake-stream-later").apply { isDaemon = true }.start()

    override fun marketOpen() = open
    override fun streamLive() = live
    override fun streamStart(tokens: Collection<Long>) {
        synchronized(starts) { starts += tokens.toList() }
        followed += tokens
        tick()
        if (!live) connectAfterMs?.let { later(it) { live = true } }
    }
    override fun streamRelease() { released++ }
    override fun seen(token: Long) = seen[token]
    override fun lastTickAt() = lastAt
    override fun ticksNow() = count.get()
    override fun dropForCheck(): Boolean {
        if (!dropWorks || !live) return false
        drops++
        live = false
        count.set(0)
        if (reconnects) later(reconnectMs) { live = true }
        return true
    }
    override suspend fun upstoxBars(key: String): List<Upstox.Bar> { if (upstoxDelayMs > 0) kotlinx.coroutines.delay(upstoxDelayMs); return upstox }
    override fun clockOffset() = offset
    override fun paperCheckedAt() = paperAt
    override fun stopsCheckedAt() = stopsAt
    override suspend fun booksOpen() = booksOpen
    override fun close() { running = false; ticker?.interrupt() }
}

