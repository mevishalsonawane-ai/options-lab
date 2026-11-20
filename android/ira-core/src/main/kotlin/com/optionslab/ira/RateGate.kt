package com.optionslab.ira

import kotlinx.coroutines.delay

/**
 * One budget for every request to Zerodha's REST API from this phone (Boss, 10 Oct: with the watch's workers running side
 * by side, their reads must never add up past Kite's limit of about 10 a second): at most [perSecond] requests (8 by
 * default, under Kite's 10) in ANY rolling second, shared by everything.
 *
 * With priority: near the limit the lower lanes leave room for the higher ones, so an exit or a stop's read is never
 * queued behind analysis - [Priority.SAFETY] (exits, stops, the loss limit, every order) may use the whole budget,
 * [Priority.ENTRY] (entries and ordinary reads) leaves one request a second, [Priority.ANALYSIS] (Jarvis's words, studies)
 * leaves three. A wait is a suspension ([acquire]), never a blocked thread. [tryTake] is the pure decision, clock given.
 *
 * 20 Nov 2026 (audit: "stop-price quotes compete with about 40 other readers"): the lanes are also served in order. While a
 * caller of a higher lane is waiting in [acquire], a lower lane takes no slot at all, so a stop's quote read, an exit or an
 * order is next through the moment a slot frees, never behind the readers that were already polling for it. The budget
 * itself (at most [perSecond] in any rolling second) is unchanged.
 *
 * [windowMs]: the "second" itself, 1,000 ms by default. 20 Nov 2026 (review): Zerodha's quote calls have their own limit of one a
 * second; that gate's window is a little longer, so network jitter never lands two inside one of Zerodha's seconds.
 */
class RateGate(
    private val perSecond: Int = 8,
    private val windowMs: Long = 1_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class Priority(val reserve: Int) { SAFETY(0), ENTRY(1), ANALYSIS(3) }

    /** When each request of the last second was let through, oldest first. */
    private val sent = ArrayDeque<Long>()

    /** Callers of each lane waiting in [acquire] now (by [Priority.ordinal]). */
    private val waitingNow = IntArray(Priority.values().size)

    /** How long a lower lane waits before asking again while a higher one is waiting. */
    private val yieldMs = 20L

    /** Requests let through so far, and how many had to wait: for the diagnostics. */
    @Volatile var taken = 0L
        private set
    @Volatile var waited = 0L
        private set

    /** The share of the budget [p] may use: all of it for safety; never less than one request a second. */
    private fun cap(p: Priority): Int = (perSecond - p.reserve).coerceAtLeast(1)

    /** Take a slot for [p] at [now]: 0 when taken, else the milliseconds to wait before asking again (nothing taken). */
    @Synchronized
    fun tryTake(p: Priority, now: Long = clock()): Long {
        // A clock set back by more than the window (the default clock never is): the window starts again.
        if (sent.isNotEmpty() && sent.last() - now > windowMs) sent.clear()
        while (sent.isNotEmpty() && now - sent.first() >= windowMs) sent.removeFirst()
        // A higher lane waiting: this one leaves the next slot to it (20 Nov 2026).
        if ((0 until p.ordinal).any { waitingNow[it] > 0 }) return yieldMs
        val cap = cap(p)
        if (sent.size < cap) {
            sent.addLast(now)
            taken++
            return 0L
        }
        // Wait until enough of the oldest leave the second for one more to fit under this lane's share.
        val freeAt = sent.elementAt(sent.size - cap) + windowMs
        return (freeAt - now).coerceIn(1L, windowMs)
    }

    /** Wait (suspending) until [p] may send one request. */
    suspend fun acquire(p: Priority) {
        var w = tryTake(p)
        if (w <= 0) return
        waited++
        waiting(p, +1)
        try {
            while (w > 0) {
                delay(w)
                w = tryTake(p)
            }
        } finally {
            waiting(p, -1)
        }
    }

    /**
     * 20 Nov 2026 (review): [acquire], but waiting [maxMs] at most (on the gate's clock): true when a slot was taken, false when the
     * wait ran out (nothing taken - the caller gives up, as on a refusal, instead of queueing without end).
     */
    suspend fun acquireWithin(p: Priority, maxMs: Long): Boolean {
        var w = tryTake(p)
        if (w <= 0) return true
        val until = clock() + maxMs
        waited++
        waiting(p, +1)
        try {
            while (w > 0) {
                val left = until - clock()
                if (left <= 0) return false
                delay(minOf(w, left))
                w = tryTake(p)
            }
            return true
        } finally {
            waiting(p, -1)
        }
    }

    @Synchronized private fun waiting(p: Priority, d: Int) { waitingNow[p.ordinal] = (waitingNow[p.ordinal] + d).coerceAtLeast(0) }

    /** Callers of [p] waiting now (the tests and the diagnostics). */
    @Synchronized fun waitingFor(p: Priority): Int = waitingNow[p.ordinal]
}
