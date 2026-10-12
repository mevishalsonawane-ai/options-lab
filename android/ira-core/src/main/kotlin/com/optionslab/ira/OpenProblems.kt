package com.optionslab.ira

import java.util.Locale

/**
 * "What I missed / problems found" (Boss, 12 Oct: "find out why all my agents were not able to find these issues"). Every agent
 * that finds something wrong - the order review, the app's health, the session coverage check, the policy check - reports it
 * here as one [Problem]: who found it, how serious, one plain sentence with the numbers, and what to do. The Automation card
 * shows the open ones in a few lines, so Boss sees them without reading the diagnostics (on 12 Oct the 3.9 s stalls, the
 * starved history vault and the slow paper orders were each a line in the diagnostics only, and the ORB research finding a
 * bullet among twenty in the morning check).
 *
 * A problem is keyed (one key, one line: reported again, it is updated, never duplicated); it stays open while its agent keeps
 * reporting it and closes when the agent clears it or after [OPEN_MS] without a new report. Nothing here acts. Pure: the
 * clock is given.
 */
object OpenProblems {
    enum class Severity(val key: String, val word: String) {
        INFO("i", "note"), WARN("w", "warning"), SERIOUS("s", "serious");
        companion object { fun of(k: String): Severity? = entries.firstOrNull { it.key == k } }
    }

    /** One problem: [key] (stable for the same thing), [agent] who found it, [text] what (with its numbers), [fix] what to do. */
    data class Problem(
        val key: String, val agent: String, val severity: Severity, val text: String, val fix: String? = null,
        val firstMs: Long = 0L, val lastMs: Long = 0L, val times: Int = 1,
    ) {
        /** "SERIOUS · Order review: ORB (paper) ... · Fix: ..." */
        fun line(): String = "${severity.word.uppercase(Locale.ENGLISH)} · $agent: $text" + (fix?.let { " · Fix: $it" } ?: "")
    }

    data class Board(val items: List<Problem> = emptyList())

    /** A problem not reported again for this long is no longer open (a day and a half: one trading day's findings carry to the next morning). */
    const val OPEN_MS = 36 * 3_600_000L
    /** At most this many kept (oldest dropped first). */
    const val MAX = 60

    private fun clean(s: String, n: Int) = s.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim().take(n)

    /** [p] reported at [nowMs]: added, or the same key's line updated (its first sighting kept, counted once more). */
    fun report(b: Board, p: Problem, nowMs: Long): Board {
        val old = b.items.firstOrNull { it.key == p.key }
        val q = p.copy(key = clean(p.key, 120), agent = clean(p.agent, 40), text = clean(p.text, 400), fix = p.fix?.let { clean(it, 200) },
            firstMs = old?.firstMs ?: nowMs, lastMs = nowMs, times = (old?.times ?: 0) + 1)
        val rest = b.items.filter { it.key != p.key }
        return Board((rest + q).sortedBy { it.lastMs }.takeLast(MAX))
    }

    /** [key]'s problem is over (its agent saw it fixed). */
    fun clear(b: Board, key: String): Board = if (b.items.none { it.key == key }) b else Board(b.items.filter { it.key != key })

    /** Every problem whose key starts with [prefix] and is not in [keep] is over (an agent's sweep found only [keep]). */
    fun clearOthers(b: Board, prefix: String, keep: Set<String>): Board =
        Board(b.items.filter { !it.key.startsWith(prefix) || it.key in keep })

    /** The open problems at [nowMs]: the most serious first, then the newest. */
    fun open(b: Board, nowMs: Long): List<Problem> = b.items.filter { nowMs - it.lastMs in 0..OPEN_MS }
        .sortedWith(compareByDescending<Problem> { it.severity.ordinal }.thenByDescending { it.lastMs })

    /** The card's head line: "Problems found: 1 serious, 2 warnings" (null: none open). */
    fun head(open: List<Problem>): String? {
        if (open.isEmpty()) return null
        val parts = Severity.entries.reversed().mapNotNull { s ->
            val n = open.count { it.severity == s }
            if (n == 0) null else "$n ${if (n == 1) s.word else s.word + "s"}"
        }
        return "Problems found: " + parts.joinToString(", ")
    }

    /** The newest [n] lines of the open problems, the most serious first. */
    fun lines(open: List<Problem>, n: Int = 20): List<String> = open.take(n).map { it.line() }

    // ---- the kept file ------------------------------------------------------------------------------------------------

    fun encode(b: Board): String = b.items.joinToString("\n") { p ->
        listOf(p.key, p.agent, p.severity.key, p.text, p.fix ?: "", p.firstMs.toString(), p.lastMs.toString(), p.times.toString())
            .joinToString("|") { clean(it, 400) }
    }

    /** [encode] read back; a damaged line is skipped, never fatal. */
    fun decode(text: String?): Board {
        if (text.isNullOrBlank()) return Board()
        val out = ArrayList<Problem>()
        for (line in text.lines()) runCatching {
            val f = line.split('|')
            if (f.size < 8) return@runCatching
            out += Problem(f[0], f[1], Severity.of(f[2]) ?: return@runCatching, f[3], f[4].ifEmpty { null }, f[5].toLong(), f[6].toLong(), f[7].toInt())
        }
        return Board(out.distinctBy { it.key }.sortedBy { it.lastMs }.takeLast(MAX))
    }
}
