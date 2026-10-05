package com.optionslab.ira

/**
 * One read shared within one market-watch pass. The pass's words-only checks (heads-up, trades going nowhere, the
 * day's target, the plan, the 15:10 MIS word, the watch notice's P&L line) each read the broker's positions seconds
 * apart: the first read of the pass is kept and the others are given it, instead of a network round trip each.
 *
 * Kept only while a pass is open ([open] .. [close]) and at most [maxAgeMs] old. [drop] (any write to the broker - an
 * order, a change, a cancel - or a session end) forgets it, and a read that was started before a drop is never kept,
 * so nothing here outlives a change the app itself made. A failed read is never kept (the caller simply does not
 * [put]). Outside a pass nothing is kept: every read is fresh. Thread-safe. Pure: the clock is passed in.
 */
class PassShare<T : Any>(private val maxAgeMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private var passOpen = false
    private var gen = 0L
    private var kept: Pair<Long, T>? = null

    @Synchronized fun open() { passOpen = true; gen++; kept = null }

    @Synchronized fun close() { passOpen = false; gen++; kept = null }

    @Synchronized fun drop() { gen++; kept = null }

    /** Taken before a read: [put] keeps its result only if nothing was dropped (and no pass began or ended) since. */
    @Synchronized fun ticket(): Long = gen

    /** The kept read, if a pass is open and it is at most [maxAgeMs] old; else null (read fresh). */
    @Synchronized fun get(): T? {
        if (!passOpen) return null
        val k = kept ?: return null
        val age = clock() - k.first
        return if (age in 0..maxAgeMs) k.second else null
    }

    /** A read that succeeded, started at [ticket] and [startedAt]: kept for the rest of the pass. */
    @Synchronized fun put(ticket: Long, startedAt: Long, value: T) {
        if (!passOpen || ticket != gen) return
        kept = startedAt to value
    }

    /** The kept read, else [read] once (its result kept on success; a failure is thrown to the caller, nothing kept). */
    inline fun share(read: () -> T): T {
        get()?.let { return it }
        val t = ticket()
        val at = now()
        return read().also { put(t, at, it) }
    }

    fun now(): Long = clock()
}
