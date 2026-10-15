package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import java.time.LocalDate

/**
 * The findings logs counted, not kept (memory audit, 14 Oct 2026, item 2). The shared knowledge read up to 200,000 findings
 * lines into objects and held them all, only to count them by kind and by agent. This keeps the counts alone: one small
 * map of (kind, agent) to a count per day, a few hundred numbers however long the logs are.
 *
 *  - [line] counts one log line as it is read (nothing of the line is kept).
 *  - [since] keeps the days of a window (the last 30 days); [before] the days before one (tonight's snapshot leaves today out,
 *    so tomorrow counts today's log again whole).
 *  - [text] / [read]: the nightly snapshot file's words (counts only - the same counts the findings bus already shows; the
 *    app keeps the file encrypted). A file that does not read gives null and the logs are counted again.
 *
 * The order the agents were first seen in is kept (a LinkedHashMap), so "most from A, B" breaks a tie exactly as the old
 * list did. Not thread-safe: one builder at a time (the shared knowledge's build is one at a time). Pure; no clock.
 */
class FindingsTally {
    /** (kind, agent) -> epoch day -> count, in first-seen order. */
    private val rows = LinkedHashMap<Pair<Findings.Kind, String>, java.util.TreeMap<Long, Int>>()

    /** One finding counted. */
    fun add(f: Findings.Finding) = add(f.kind, f.who, AgentLessons.dayOf(f.atMs).toEpochDay(), 1)

    /** [n] findings of [kind] by [who] on [epochDay] counted (n below 1: nothing). */
    fun add(kind: Findings.Kind, who: String, epochDay: Long, n: Int) {
        if (n < 1) return
        val days = rows.getOrPut(kind to who) { java.util.TreeMap() }
        days[epochDay] = (days[epochDay] ?: 0) + n
    }

    /** One findings log line counted when it reads as a finding; true when it did. Nothing of it is kept. */
    fun line(l: String): Boolean {
        val f = Findings.parse(l) ?: return false
        add(f)
        return true
    }

    /** Every row of [other] added after this one's (its first-seen order follows this one's). */
    fun addAll(other: FindingsTally) {
        for ((k, days) in other.rows) for ((d, n) in days) add(k.first, k.second, d, n)
    }

    /** Findings counted in all. */
    val total: Int get() = rows.values.sumOf { it.values.sum() }

    fun isEmpty(): Boolean = rows.isEmpty()

    /** Rows kept (each a kind and an agent): the memory this holds is a few numbers each. */
    val size: Int get() = rows.size

    /** The latest day counted, or null. */
    fun lastDay(): LocalDate? = rows.values.mapNotNull { it.lastKeyOrNull() }.maxOrNull()?.let(LocalDate::ofEpochDay)

    private fun java.util.TreeMap<Long, Int>.lastKeyOrNull(): Long? = if (isEmpty()) null else lastKey()

    /** A copy with only the days [keep] says yes to (rows left with no day are dropped). */
    private fun filtered(keep: (Long) -> Boolean): FindingsTally {
        val out = FindingsTally()
        for ((k, days) in rows) for ((d, n) in days) if (keep(d)) out.add(k.first, k.second, d, n)
        return out
    }

    /** The days from [from] on. */
    fun since(from: LocalDate): FindingsTally { val f = from.toEpochDay(); return filtered { it >= f } }

    /** The days before [day]. */
    fun before(day: LocalDate): FindingsTally { val f = day.toEpochDay(); return filtered { it < f } }

    /** One kind's figures: the posts, the days they were on, and each agent's posts in first-seen order. */
    data class KindCount(val kind: Findings.Kind, val count: Int, val days: Int, val who: List<Pair<String, Int>>)

    /** Each kind counted (in first-seen order of the kinds). */
    fun byKind(): List<KindCount> {
        val kinds = LinkedHashMap<Findings.Kind, MutableList<Pair<String, java.util.TreeMap<Long, Int>>>>()
        for ((k, days) in rows) kinds.getOrPut(k.first) { ArrayList() } += k.second to days
        return kinds.map { (kind, list) ->
            val allDays = HashSet<Long>()
            list.forEach { allDays.addAll(it.second.keys) }
            KindCount(kind, list.sumOf { it.second.values.sum() }, allDays.size, list.map { it.first to it.second.values.sum() })
        }
    }

    /** Each (kind, agent)'s posts and days, the lessons bundle's rows (in first-seen order; the bundle sorts them). */
    fun counts(): List<LessonsBundle.FindingCount> =
        rows.map { (k, days) -> LessonsBundle.FindingCount(k.first.name, k.second, days.values.sum(), days.size) }

    /** The snapshot file's words: [through] (the last whole day it counts) and every row. */
    fun text(through: LocalDate): String = Json.write(mapOf(
        "v" to VERSION, "through" to through.toString(),
        "rows" to rows.flatMap { (k, days) -> days.map { (d, n) -> listOf(k.first.name, k.second, d, n) } },
    ))

    override fun equals(other: Any?): Boolean = other is FindingsTally && other.rows == rows
    override fun hashCode(): Int = rows.hashCode()
    override fun toString(): String = "FindingsTally($total in ${rows.size} rows)"

    companion object {
        const val VERSION = 1

        /** The tally of [findings] (in their order). */
        fun of(findings: List<Findings.Finding>): FindingsTally = FindingsTally().also { t -> findings.forEach { t.add(it) } }

        /** A snapshot file's day and tally, or null when it does not read (another version, damaged): count the logs then. */
        fun read(text: String): Pair<LocalDate, FindingsTally>? = runCatching {
            val m = Json.parse(text) as? Map<*, *> ?: return null
            if ((m["v"] as? Number)?.toInt() != VERSION) return null
            val through = LocalDate.parse(m["through"] as? String ?: return null)
            val t = FindingsTally()
            for (r in (m["rows"] as? List<*>).orEmpty()) {
                val a = r as? List<*> ?: continue
                if (a.size < 4) continue
                val kind = runCatching { Findings.Kind.valueOf(a[0] as String) }.getOrNull() ?: continue
                val who = a[1] as? String ?: continue
                val day = (a[2] as? Number)?.toLong() ?: continue
                val n = (a[3] as? Number)?.toInt() ?: continue
                t.add(kind, who, day, n)
            }
            through to t
        }.getOrNull()
    }
}
