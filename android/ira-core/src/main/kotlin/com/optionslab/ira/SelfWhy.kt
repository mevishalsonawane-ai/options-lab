package com.optionslab.ira

import java.time.LocalDateTime
import java.util.Locale

/**
 * "Why did you park ORB 5?", "why did you do that?" - Jarvis explains his own actions from his activity record
 * (each entry is written with its reason). The latest entry that shares the most words with the question; a bare
 * "why did you do that" is about the latest thing he did. Pure.
 */
object SelfWhy {
    private val ASKED = Regex("(?i)\\bwhy (did|have|has|were|was) (you|jarvis)\\b|\\bwhy (is|was|are|were) [a-z0-9 ]{1,30}\\b(parked|stopped|switched|taken|bought|held back|skipped|armed|closed|cancelled|offered)\\b|\\bwhat made you\\b|\\bexplain (that|what you did)\\b")
    private val STOP = setOf("why", "did", "you", "have", "has", "was", "were", "is", "are", "the", "a", "an", "do", "that", "this", "it", "jarvis",
        "what", "made", "explain", "me", "my", "to", "on", "of", "for", "in", "at", "today", "just", "now", "boss", "please", "tell")

    fun asked(text: String): Boolean = ASKED.containsMatchIn(text)

    private fun words(s: String) = s.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.length > 1 && it !in STOP }.toSet()

    /** The entry that answers [question], or null when the record holds nothing about it. */
    fun find(question: String, entries: List<Activity.Entry>): Activity.Entry? {
        if (entries.isEmpty()) return null
        val want = words(question)
        if (want.isEmpty()) return entries.maxByOrNull { it.at }
        val scored = entries.map { e -> e to words(e.what).count { w -> w in want || want.any { q -> w.startsWith(q) || q.startsWith(w) } } }
            .filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return null
        return scored.filter { it.second == best }.maxByOrNull { it.first.at }?.first
    }

    fun answer(question: String, entries: List<Activity.Entry>, now: LocalDateTime): String {
        val e = find(question, entries) ?: return "I have no record of doing that, Boss: my activity log keeps what I did (ask \"what did you do today\")."
        val day = if (e.at.toLocalDate() == now.toLocalDate()) "Today" else e.at.toLocalDate().toString()
        return "$day at " + "%02d:%02d".format(Locale.ENGLISH, e.at.hour, e.at.minute) + ": ${e.what.trimEnd('.')}." +
            " That is my reason as I wrote it then; ask \"what did you do today\" for the rest."
    }
}
