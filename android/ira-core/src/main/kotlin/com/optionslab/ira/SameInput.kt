package com.optionslab.ira

/**
 * One result kept for as long as what it was made from is unchanged. The trade history (every round trip, the paper
 * tests' verdicts, the goals' standing) was rebuilt from all the fills several times in each market-watch pass although
 * the fills had not changed: [of] gives the kept result while the input is equal (`==`) to the one it was made from,
 * and makes it afresh (and keeps that) the moment the input differs.
 *
 * The input itself is the invalidation: nothing has to remember to clear it, a changed trade book is simply a different
 * input. So the caller passes a snapshot it will not change afterwards (a copy of a list that is added to in place), and
 * [make] must depend on nothing but the input. A [make] that throws keeps nothing (the next call tries again). Equality
 * is checked in full, never assumed from the size or the newest item. Thread-safe: [make] runs under the lock, so two
 * callers never build the same result twice. Pure.
 */
class SameInput<K : Any, V : Any> {
    private var input: K? = null
    private var kept: V? = null

    @Synchronized fun of(input: K, make: (K) -> V): V {
        val k = kept
        if (k != null && this.input == input) return k
        val v = make(input)
        this.input = input; kept = v
        return v
    }

    /** Forgets the kept result (the next [of] makes it afresh). */
    @Synchronized fun forget() { input = null; kept = null }
}
