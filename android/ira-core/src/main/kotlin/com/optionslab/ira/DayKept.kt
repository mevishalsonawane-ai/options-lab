package com.optionslab.ira

/**
 * Battery, round 14: one figure for a day, kept while the book it was made from is unchanged. Zerodha's charges
 * estimate beside the order watch's P&L line was made afresh every watch pass (once a minute through market hours): the
 * whole kept trade book copied and every fill's time parsed (thousands after a few months) to find today's, although
 * the book changes only when a fill is recorded. [of] gives the kept figure while both the [day] and the book's
 * [generation] (bumped by the owner on every new fill and on a wipe) are the ones it was made for, and makes it afresh
 * (and keeps that) the moment either moves: a new fill or a new day is never answered from the old figure.
 *
 * The figure may be null (no trades that day) and is kept as such. A [make] that throws keeps nothing (the next call
 * tries again: an unreadable vault must not be remembered as "no trades"). Thread-safe: [make] runs under the lock.
 * Pure.
 */
class DayKept<V> {
    private var day: String? = null
    private var generation = Long.MIN_VALUE
    private var has = false
    private var kept: V? = null

    @Synchronized fun of(day: String, generation: Long, make: () -> V): V {
        @Suppress("UNCHECKED_CAST")
        if (has && this.day == day && this.generation == generation) return kept as V
        val v = make()
        this.day = day; this.generation = generation; kept = v; has = true
        return v
    }

    /** Forgets the kept figure (the next [of] makes it afresh). */
    @Synchronized fun forget() { day = null; generation = Long.MIN_VALUE; kept = null; has = false }
}
