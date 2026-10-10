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
 */
class RateGate(
    private val perSecond: Int = 8,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class Priority(val reserve: Int) { SAFETY(0), ENTRY(1), ANALYSIS(3) }

    /** When each request of the last second was let through, oldest first. */
    private val sent = ArrayDeque<Long>()

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
        if (sent.isNotEmpty() && sent.last() - now > 1_000L) sent.clear()
        while (sent.isNotEmpty() && now - sent.first() >= 1_000L) sent.removeFirst()
        val cap = cap(p)
        if (sent.size < cap) {
            sent.addLast(now)
            taken++
            return 0L
        }
        // Wait until enough of the oldest leave the second for one more to fit under this lane's share.
        val freeAt = sent.elementAt(sent.size - cap) + 1_000L
        return (freeAt - now).coerceIn(1L, 1_000L)
    }

    /** Wait (suspending) until [p] may send one request. */
    suspend fun acquire(p: Priority) {
        var w = tryTake(p)
        if (w > 0) waited++
        while (w > 0) {
            delay(w)
            w = tryTake(p)
        }
    }
}
