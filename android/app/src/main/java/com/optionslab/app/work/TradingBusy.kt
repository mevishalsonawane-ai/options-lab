package com.optionslab.app.work

import java.util.concurrent.atomic.AtomicInteger

/**
 * "Trading work is running now" (Boss, 10 Oct: Jarvis's on-device model must never compete with trading work): set while
 * the watch's safety steps, the 15-second stop check, the event-driven live exits and every Zerodha order run. The model
 * looks at it before it starts writing (it waits up to a few hundred ms for it to clear) and is told when it turns on, so a
 * screen-only rewrite under way gives way at once ([com.optionslab.app.ira.IraModel]). Memory only; never blocks.
 */
object TradingBusy {
    private val count = AtomicInteger()

    /** Called (off the main thread, cheaply) each time trading work starts while none was running. */
    @Volatile var onBusy: (() -> Unit)? = null

    val active: Boolean get() = count.get() > 0

    fun begin() { if (count.incrementAndGet() == 1) runCatching { onBusy?.invoke() } }
    fun end() { if (count.decrementAndGet() < 0) count.set(0) }

    /** [block] marked as trading work. */
    inline fun <T> during(block: () -> T): T {
        begin()
        try { return block() } finally { end() }
    }

    /** Wait (suspending, at most [maxMs]) for trading work to finish; true when it is quiet now. */
    suspend fun awaitQuiet(maxMs: Long = 300L): Boolean {
        val until = System.currentTimeMillis() + maxMs
        while (active) {
            if (System.currentTimeMillis() >= until) return false
            kotlinx.coroutines.delay(25)
        }
        return true
    }

    internal fun resetForTest() { count.set(0); onBusy = null }
}
