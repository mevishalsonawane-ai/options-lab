package com.optionslab.ira

/**
 * 20 Nov 2026 (minor fixes, item 2: a Strategy Module leg whose exit stays refused - the square-off has taken it over, its stop cannot
 * be confirmed out yet - alerted Boss on every look): an alert told once per stuck episode ([tell] the first time for a key), then at
 * most once every [remindMs] while it lasts; [end] closes the episodes of a run (its keys under a prefix), so the next one is told at
 * once. Memory only, a short synchronized section, no I/O. Pure.
 */
class RepeatGate(private val remindMs: Long) {
    /** Per key: when it was last told, and the highest [level] told in this episode. */
    private val last = HashMap<String, Pair<Long, Int>>()

    /**
     * Whether [key]'s alert is told at [nowMs]: its first, [remindMs] or more since the last one told (a clock gone back tells), or -
     * 20 Nov 2026 (review) - a [level] above any told in this episode (a larger stop refusal: more positions refused), told at once. A
     * lower or equal level waits, so a count that goes back and forth is not told on every change.
     */
    @Synchronized fun tell(key: String, nowMs: Long, level: Int = 0): Boolean {
        val t = last[key]
        if (t != null && nowMs >= t.first && nowMs - t.first < remindMs && level <= t.second) return false
        last[key] = nowMs to maxOf(level, t?.second ?: level)
        return true
    }

    /** Every episode whose key starts with [prefix] is over (a run stopped or started again). */
    @Synchronized fun end(prefix: String) { last.keys.removeAll { it.startsWith(prefix) } }

    /** Episodes open now (tests, and a bound check). */
    @get:Synchronized val size: Int get() = last.size
}
