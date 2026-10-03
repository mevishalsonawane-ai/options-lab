package com.optionslab.ira

/**
 * Learning from the owner's corrections (Jarvis self-improvement, 2026-10-03): after "that was wrong", the next question
 * Boss asks and Jarvis understands is taken as what was meant, and the misunderstood words are read that way from then
 * on. Only questions are ever learned or rewritten - never a command or an order, so nothing learned can act. Pure.
 */
object Corrections {
    data class Learned(val wrong: String, val right: String)

    const val KEEP = 100
    /** How alike two wordings must be (0..1, by edit distance) to count as the same. */
    const val SAME = 0.85

    private val FILLER = Regex(" (jarvis|hey|ok|okay|please|boss|um|uh|so|tell me|can you|could you) ")

    fun normalize(text: String): String {
        var t = " " + text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        repeat(3) { t = FILLER.replace(t, " ") }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** A question (asks, never acts) that Jarvis understands. */
    fun understood(text: String): Boolean {
        val q = Ask.parse(text)
        return q.command == null && q.order == null && Topic.COMMAND !in q.topics && Topic.ORDER !in q.topics && Topic.OFF_TOPIC !in q.topics
    }

    /** What to learn from [wrong] (what Boss said first) and [right] (the rephrasing Jarvis understood), or null. */
    fun learn(wrong: String, right: String): Learned? {
        val w = normalize(wrong); val r = normalize(right)
        if (w.isEmpty() || r.isEmpty() || w == r || w.length > 200) return null
        if (!understood(right)) return null
        // A misheard command or order is never learned as a question in its place (it must be said again).
        if (acts(wrong)) return null
        return Learned(w, r)
    }

    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val n = a.length; val m = b.length
        if (n == 0 || m == 0) return 0.0
        var prev = IntArray(m + 1) { it }
        for (i in 1..n) {
            val cur = IntArray(m + 1); cur[0] = i
            for (j in 1..m) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return 1.0 - prev[m].toDouble() / maxOf(n, m)
    }

    /**
     * [text] as Boss meant it, from what was learned, or null. Only when the words as said are not understood (or are
     * exactly a learned wording), and only to a question - never to anything that acts.
     */
    fun apply(text: String, learned: List<Learned>): String? {
        if (learned.isEmpty() || acts(text)) return null
        val t = normalize(text)
        val exact = learned.lastOrNull { it.wrong == t }
        // Nearly the same words: only when they were not understood, or name no market where the correction does
        // (a misheard "nifti"), so a clear question is never re-read.
        val named = Market.mentioned(text).isNotEmpty()
        val hit = exact ?: learned.filter { l -> similarity(l.wrong, t) >= SAME && (!understood(text) || !named && Market.mentioned(l.right).isNotEmpty()) }
            .maxByOrNull { similarity(it.wrong, t) }
        val right = hit?.right ?: return null
        return right.takeIf { understood(it) }
    }

    /** Words that would act: a command or an order. */
    private fun acts(text: String): Boolean = Commands.parse(text) != null || Ask.parse(text).let { it.order != null || Topic.ORDER in it.topics || Topic.COMMAND in it.topics }

    fun lines(learned: List<Learned>): List<String> =
        if (learned.isEmpty()) emptyList() else listOf("What I've learned from your corrections:") + learned.takeLast(10).reversed().map { "\"${it.wrong}\" means \"${it.right}\"." }
}
