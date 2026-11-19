package com.optionslab.ira

/**
 * 20 Nov 2026 (minor fixes, item 2: a Strategy Module leg whose exit stays refused - the square-off has taken it over, its stop cannot
 * be confirmed out yet - alerted Boss on every look): an alert told once per stuck episode ([tell] the first time for a key), then at
 * most once every [remindMs] while it lasts; [end] closes the episodes of a run (its keys under a prefix), so the next one is told at
 * once. Memory only, a short synchronized section, no I/O. Pure.
 */
class RepeatGate(private val remindMs: Long) {
    private val last = HashMap<String, Long>()

    /** Whether [key]'s alert is told at [nowMs]: its first, or [remindMs] or more since the last one told (a clock gone back tells). */
    @Synchronized fun tell(key: String, nowMs: Long): Boolean {
        val t = last[key]
        if (t != null && nowMs >= t && nowMs - t < remindMs) return false
        last[key] = nowMs
        return true
    }

    /** Every episode whose key starts with [prefix] is over (a run stopped or started again). */
    @Synchronized fun end(prefix: String) { last.keys.removeAll { it.startsWith(prefix) } }

    /** Episodes open now (tests, and a bound check). */
    @get:Synchronized val size: Int get() = last.size
}
