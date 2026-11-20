package com.optionslab.ira

/**
 * 20 Nov 2026 (B5-D review: the paper arms' fresh exit reads ran one after another ahead of the live copies' stop look): the time
 * the paper arms' fresh exit reads (S5, Flow, ntfy) may take together in any [windowMs] - [budgetMs] at most. Past it a look
 * decides on the stream's last price instead (an exit is never skipped), so they can never hold the minute lane, and the live
 * look behind them, for more than [budgetMs] a window. Memory only; the clock is given (monotonic in the app).
 *
 * B5-D (final review: take and spend apart could be overspent by reads side by side): a read RESERVES its time up front
 * ([reserve], atomic) and gives back what it did not use ([settle]) - so reads running at once can never together pass the budget.
 */
class ReadBudget(
    private val windowMs: Long = 15_000L,
    private val budgetMs: Long = 4_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** A reservation: [ms] held in window [window]. */
    class Held internal constructor(val ms: Long, internal val window: Long)

    private var windowStart = Long.MIN_VALUE
    private var windowNo = 0L
    private var spent = 0L

    /** Up to [wantMs] reserved now (null: none left in this window - use the stream's last price). Atomic. */
    @Synchronized
    fun reserve(wantMs: Long): Held? {
        roll(clock())
        val ms = minOf(wantMs, budgetMs - spent)
        if (ms <= 0L) return null
        spent += ms
        return Held(ms, windowNo)
    }

    /** The read under [held] took [usedMs]: what it did not use is given back (in its own window only). Atomic. */
    @Synchronized
    fun settle(held: Held, usedMs: Long) {
        roll(clock())
        if (held.window != windowNo) return
        val unused = held.ms - usedMs.coerceIn(0L, held.ms)
        spent = (spent - unused).coerceAtLeast(0L)
    }

    /** What is left in the window now (tests and the diagnostics). */
    @Synchronized
    fun left(): Long { roll(clock()); return (budgetMs - spent).coerceAtLeast(0L) }

    /** A new window when the last began [windowMs] ago - or the clock went back (never stuck). */
    private fun roll(now: Long) {
        if (windowStart == Long.MIN_VALUE || now - windowStart >= windowMs || now < windowStart) { windowStart = now; spent = 0L; windowNo++ }
    }
}
