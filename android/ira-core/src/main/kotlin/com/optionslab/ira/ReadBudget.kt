package com.optionslab.ira

/**
 * 20 Nov 2026 (B5-D review: the paper arms' fresh exit reads ran one after another ahead of the live copies' stop look): the time
 * the paper arms' fresh exit reads (S5, Flow, ntfy) may take together in any [windowMs] - [budgetMs] at most. Past it a look
 * decides on the stream's last price instead (an exit is never skipped), so they can never hold the minute lane, and the live
 * look behind them, for more than [budgetMs] a window. Memory only; thread-safe; the clock is given (monotonic in the app).
 */
class ReadBudget(
    private val windowMs: Long = 15_000L,
    private val budgetMs: Long = 4_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var windowStart = Long.MIN_VALUE
    private var spent = 0L

    /** How long the next read may take (at most [wantMs]); 0: none left in this window (use the stream's last price). */
    @Synchronized
    fun take(wantMs: Long): Long {
        roll(clock())
        return minOf(wantMs, budgetMs - spent).coerceAtLeast(0L)
    }

    /** A read took [ms]. */
    @Synchronized
    fun spend(ms: Long) {
        roll(clock())
        spent += ms.coerceAtLeast(0L)
    }

    /** A new window when the last began [windowMs] ago - or the clock went back (never stuck). */
    private fun roll(now: Long) {
        if (windowStart == Long.MIN_VALUE || now - windowStart >= windowMs || now < windowStart) { windowStart = now; spent = 0L }
    }
}
