package com.optionslab.ira

import java.time.LocalDate

/**
 * 21 Oct, enhancement round 5 (idea 1): the day GTT's counts for the diagnostics, so Boss can judge "Stop and target as one GTT"
 * after a week on. Each day keeps how many protections' stop and target were placed as one GTT, kept at the open (the overnight
 * GTT stayed for the day), trailed (the stop leg moved), shrunk (a part sold by hand), fired at Zerodha, replaced by the two orders
 * (refused, switched off, or could not shrink), and found gone at Zerodha (deleted on Kite's site, expired).
 *
 * Pure: no clock, no I/O. The app keeps the list in memory and writes it on a worker thread ([encode]).
 */
object DayGttTally {
    enum class Event(val words: String) {
        PLACED("placed"), KEPT("kept at the open"), TRAILED("trailed"), SHRUNK("shrunk"), FIRED("fired"),
        REPLACED("replaced by two orders"), GONE("gone at Zerodha"),
    }

    /** One day's counts (only events that happened are in [counts]). */
    data class Day(val day: LocalDate, val counts: Map<Event, Int> = emptyMap()) {
        operator fun get(e: Event): Int = counts[e] ?: 0
    }

    /** Days kept (a week, plus today). */
    const val KEEP_DAYS = 8L

    /** [list] with [n] of [e] added on [day]; days more than [KEEP_DAYS] before the newest day kept are dropped. Sorted by day. */
    fun add(list: List<Day>, day: LocalDate, e: Event, n: Int = 1): List<Day> {
        if (n <= 0) return list
        val old = list.firstOrNull { it.day == day } ?: Day(day)
        val next = old.copy(counts = old.counts + (e to old[e] + n))
        return prune(list.filter { it.day != day } + next)
    }

    private fun prune(list: List<Day>): List<Day> {
        val newest = list.maxOfOrNull { it.day } ?: return list
        return list.filter { !it.day.isBefore(newest.minusDays(KEEP_DAYS - 1)) }.sortedBy { it.day }
    }

    /** The counts of the last seven days to [today] (today included). */
    fun week(list: List<Day>, today: LocalDate): Map<Event, Int> {
        val from = today.minusDays(6)
        val days = list.filter { !it.day.isBefore(from) && !it.day.isAfter(today) }
        return Event.values().associateWith { e -> days.sumOf { it[e] } }.filterValues { it > 0 }
    }

    private fun words(c: Map<Event, Int>): String =
        if (c.isEmpty()) "nothing yet" else Event.values().filter { (c[it] ?: 0) > 0 }.joinToString(", ") { "${it.words} ${c[it]}" }

    /** The diagnostics' line: the switch, today's counts and the last seven days'. */
    fun line(on: Boolean, list: List<Day>, today: LocalDate): String {
        val t = list.firstOrNull { it.day == today }?.counts.orEmpty().filterValues { it > 0 }
        val w = week(list, today)
        val days = list.count { !it.day.isBefore(today.minusDays(6)) && !it.day.isAfter(today) && it.counts.values.any { n -> n > 0 } }
        return "Stop and target as one GTT in the day: ${if (on) "on" else "off"}; today ${words(t)}; last 7 days ${words(w)}" +
            if (days > 0) " ($days day${if (days == 1) "" else "s"} with any)" else ""
    }

    /** "day|EVENT=n,EVENT=n;..." for the settings vault; [decode] reads it back (a bad row or count is skipped). */
    fun encode(list: List<Day>): String =
        list.joinToString(";") { d -> "${d.day}|" + d.counts.filterValues { it > 0 }.entries.joinToString(",") { "${it.key.name}=${it.value}" } }

    fun decode(s: String?): List<Day> = s.orEmpty().split(';').mapNotNull { row ->
        val f = row.split('|')
        if (f.size != 2) return@mapNotNull null
        val day = runCatching { LocalDate.parse(f[0]) }.getOrNull() ?: return@mapNotNull null
        val counts = f[1].split(',').mapNotNull { kv ->
            val (k, v) = kv.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val e = Event.values().firstOrNull { it.name == k } ?: return@mapNotNull null
            val n = v.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            e to n
        }.toMap()
        Day(day, counts)
    }.groupBy { it.day }.map { (_, ds) -> ds.last() }.sortedBy { it.day }

    /**
     * [mine] (seen in this process before the saved list was read) added to [saved], per day and event. The app writes nothing
     * before that read, so this process's events are never already in [saved].
     */
    fun merge(saved: List<Day>, mine: List<Day>): List<Day> {
        val days = (saved.map { it.day } + mine.map { it.day }).distinct().sorted()
        return days.map { d ->
            val a = saved.firstOrNull { it.day == d }; val b = mine.firstOrNull { it.day == d }
            Day(d, Event.values().associateWith { e -> (a?.get(e) ?: 0) + (b?.get(e) ?: 0) }.filterValues { it > 0 })
        }
    }
}
