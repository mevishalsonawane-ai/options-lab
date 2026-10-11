package com.optionslab.ira

import java.time.LocalDate

/**
 * Memory consolidation (Boss, 11 Oct: "enhance the self improvement"): once a week - on Sunday, or the first run after it -
 * what the app keeps is tidied, the way sleep tidies memory:
 *
 *  - Jarvis's lessons: near-duplicates (one key spelled two ways) merged by the lessons' own rule ([AgentLessons.merge]) and
 *    the book held to its caps ([AgentLessons.capped]); nothing is re-judged here;
 *  - the findings history: a day's log older than [COMPACT_AFTER_DAYS] days keeps its data-health lines (what Jarvis reads
 *    back) once each, and the rest becomes one count line per who and kind ([compact]) - the same file compacted again is
 *    unchanged;
 *  - old recordings beyond their caps trimmed ([trim]): the oldest first, those a learning goal needs last;
 *  - a compact "week summary" record ([GrowthReport.Week]) kept for the weekly report.
 *
 * Deterministic (the same input gives the same output) and fast (well under 100 ms for a full book). Pure: the app reads and
 * writes the files (noBackupFilesDir), passes them in and the day.
 */
object Consolidation {
    /** A findings log older than this many days is compacted. */
    const val COMPACT_AFTER_DAYS = 7L
    /** The findings logs: kept this many days and at most this many bytes. */
    const val FINDINGS_KEEP_DAYS = 30L
    const val FINDINGS_MAX_BYTES = 20L * 1024 * 1024

    /** The Sunday on or before [today]: when this week's consolidation is due; null when the last one ([last]) was on or after it. */
    fun due(today: LocalDate, last: LocalDate?): LocalDate? {
        val sunday = today.minusDays((today.dayOfWeek.value % 7).toLong())
        return if (last == null || last.isBefore(sunday)) sunday else null
    }

    /** The lessons merged ([AgentLessons.merge]) and capped; how many fewer there are. */
    fun lessons(b: AgentLessons.Book): Pair<AgentLessons.Book, Int> {
        val merged = AgentLessons.capped(b.copy(lessons = AgentLessons.merge(b.lessons)))
        return merged to (b.lessons.size - merged.lessons.size)
    }

    /**
     * One day's findings log compacted: its data-health lines (the "F|...|DATA_..." ones Jarvis reads back) once each in their
     * order, then one "C|who|kind|count" line per who and kind for the rest (a compacted file's own count lines are added in).
     */
    fun compact(lines: List<String>): List<String> {
        val keep = LinkedHashSet<String>()
        val counts = java.util.TreeMap<String, Int>()
        for (l in lines) {
            if (l.isBlank()) continue
            if (l.startsWith("C|")) {
                val p = l.split('|')
                val n = p.getOrNull(3)?.toIntOrNull() ?: continue
                counts.merge("${p[1]}|${p[2]}", n, Int::plus)
                continue
            }
            if (!l.startsWith("F|")) continue
            // Only the data-health lines are read in whole; the rest are counted by who and kind from their fields.
            val p = l.split('|', limit = 6)
            if (p.size < 6 || p[4] !in KINDS) continue
            if (p[4].startsWith("DATA_")) {
                // The same finding posted again (its number aside) is one.
                val f = Findings.parse(l) ?: continue
                keep += Findings.line(f.copy(seq = 0))
            } else counts.merge("${p[3]}|${p[4]}", 1, Int::plus)
        }
        return keep.toList() + counts.map { (k, n) -> "C|$k|$n" }
    }

    private val KINDS = Findings.Kind.entries.map { it.name }.toHashSet()

    /** Is a findings log of [day] due for compaction on [today]? */
    fun compactDue(day: LocalDate, today: LocalDate): Boolean = day.isBefore(today.minusDays(COMPACT_AFTER_DAYS))

    /** One recording file: its name, its day, its size, and the index or family it is about (null: none). */
    data class Rec(val name: String, val day: LocalDate, val bytes: Long, val about: String? = null)

    /**
     * The recordings to delete on [today]: those older than [keepDays] days, then, while the rest are over [maxBytes], the
     * oldest - those not in [focus] (a learning goal's indices or families) first. Ordered by day then name: deterministic.
     */
    fun trim(recs: List<Rec>, keepDays: Long, maxBytes: Long, today: LocalDate, focus: Set<String> = emptySet()): List<String> {
        val from = today.minusDays(keepDays - 1)
        val out = ArrayList<String>()
        val rest = ArrayList<Rec>()
        for (r in recs.sortedWith(compareBy<Rec>({ it.day }, { it.name }))) if (r.day.isBefore(from)) out += r.name else rest += r
        var total = rest.sumOf { it.bytes }
        if (total > maxBytes) {
            val order = rest.filter { !it.day.isAfter(today) }.sortedWith(compareBy<Rec>({ if (it.about != null && it.about in focus) 1 else 0 }, { it.day }, { it.name }))
            for (r in order) { if (total <= maxBytes) break; out += r.name; total -= r.bytes }
        }
        return out
    }

    /** What one consolidation did, in a line for the diary and the findings. */
    data class Done(val merged: Int, val compacted: Int, val trimmed: Int, val week: LocalDate) {
        fun words(): String = "weekly consolidation: ${merged} lesson${if (merged == 1) "" else "s"} merged, $compacted findings log${if (compacted == 1) "" else "s"} compacted, " +
            "$trimmed old recording${if (trimmed == 1) "" else "s"} trimmed, the ${GrowthReport.title(week).replaceFirstChar { it.lowercase() }} summary kept"
    }
}
