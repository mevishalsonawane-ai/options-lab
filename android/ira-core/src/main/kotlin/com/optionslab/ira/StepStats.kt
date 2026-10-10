package com.optionslab.ira

import java.util.Locale

/**
 * How long each named step of the order watch takes, today (Boss, 10 Oct: "measure first"): every step the watch, its
 * workers and the event-driven checks run is timed, and its typical (p50), worst one in twenty (p95) and longest time are
 * kept in memory for the day - the diagnostics and the Order speed card say the slowest ([slowest], [line]).
 *
 * Memory only and bounded: the last [keep] durations per step (the percentiles are over those), the day's count, total
 * and maximum, at most [maxSteps] step names (the app's own words, never data). A new day starts empty. Thread-safe.
 */
class StepStats(private val keep: Int = 512, private val maxSteps: Int = 200) {
    /** One step's day: [count] runs, [p50]/[p95] over the last kept runs, [max] and [totalMs] over the whole day. */
    data class Row(val name: String, val count: Int, val p50: Long, val p95: Long, val max: Long, val totalMs: Long)

    private class Acc(size: Int) {
        val ring = LongArray(size)
        var filled = 0
        var next = 0
        var count = 0
        var max = 0L
        var total = 0L
    }

    private var day: String? = null
    private val accs = LinkedHashMap<String, Acc>()

    /** [name] took [ms] on [day] (an ISO date): a new day drops the old one's figures. Negative durations count as 0. */
    @Synchronized
    fun record(name: String, ms: Long, day: String) {
        if (day != this.day) { accs.clear(); this.day = day }
        val a = accs[name] ?: (if (accs.size >= maxSteps) return else Acc(keep).also { accs[name] = it })
        val v = ms.coerceAtLeast(0L)
        a.ring[a.next] = v
        a.next = (a.next + 1) % a.ring.size
        if (a.filled < a.ring.size) a.filled++
        a.count++
        a.total += v
        if (v > a.max) a.max = v
    }

    /** Every step timed on [day], in the order first seen; empty for any other day. */
    @Synchronized
    fun rows(day: String): List<Row> {
        if (day != this.day) return emptyList()
        return accs.map { (name, a) ->
            val sorted = a.ring.copyOf(a.filled).also { it.sort() }
            Row(name, a.count, pct(sorted, 50), pct(sorted, 95), a.max, a.total)
        }
    }

    /** The [n] slowest steps on [day]: by p95, then by the longest run, then by name. */
    fun slowest(day: String, n: Int = 5): List<Row> =
        rows(day).sortedWith(compareByDescending<Row> { it.p95 }.thenByDescending { it.max }.thenBy { it.name }).take(n.coerceAtLeast(0))

    /** TEST ONLY / a wipe: nothing kept. */
    @Synchronized
    fun clear() { accs.clear(); day = null }

    companion object {
        /** Nearest-rank percentile of [sorted] (ascending); 0 when empty. */
        internal fun pct(sorted: LongArray, p: Int): Long {
            if (sorted.isEmpty()) return 0L
            val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        /** "850 ms", "2.4 s", "1 min 5 s". */
        fun ms(v: Long): String = when {
            v < 1_000 -> "$v ms"
            v < 60_000 -> String.format(Locale.ENGLISH, "%.1f s", v / 1_000.0)
            else -> "${v / 60_000} min ${(v % 60_000) / 1_000} s"
        }

        /** One step in words: "ORB arms: p50 120 ms · p95 900 ms · max 2.1 s (34 runs)". */
        fun words(r: Row): String = "${r.name}: p50 ${ms(r.p50)} · p95 ${ms(r.p95)} · max ${ms(r.max)} (${r.count} run${if (r.count == 1) "" else "s"})"

        /** The diagnostics' and the Order speed card's line: "Slowest steps today: ...", or that nothing ran yet. */
        fun line(rows: List<Row>): String =
            if (rows.isEmpty()) "Slowest steps today: none timed yet"
            else "Slowest steps today: " + rows.joinToString("; ") { words(it) }
    }
}
