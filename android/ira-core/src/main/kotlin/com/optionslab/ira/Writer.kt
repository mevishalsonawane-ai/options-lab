package com.optionslab.ira

/**
 * The on-device model only rewrites Ira's own answer in plainer words (rule 7: the model only writes text). This
 * builds what it is given - the question, the facts and Ira's draft, nothing else - and checks what comes back: every
 * number must be one of the facts' (rule 4), no advice or forecast (rule 5), no links, at most four sentences. Anything
 * that fails is thrown away and the draft stands. Pure.
 */
object Writer {
    /** Qwen's chat format (ChatML). */
    fun prompt(question: String, facts: List<String>, draft: String): String {
        val sys = "You are Jarvis, the market assistant inside the owner's trading app. Rewrite the DRAFT answer in clear, " +
            "natural English. Rules: use only the FACTS and the DRAFT; copy every number exactly as written; add no other " +
            "numbers, dates, names or events; give no advice, prediction or opinion about buying or selling; " +
            "at most four short sentences; reply with the answer only."
        val user = buildString {
            append("QUESTION: ").append(question.take(300)).append('\n')
            append("FACTS:\n"); relevant(facts, question, draft).forEach { append("- ").append(it).append('\n') }
            append("DRAFT: ").append(draft)
        }
        return "<|im_start|>system\n$sys<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }

    /**
     * The facts worth giving the model, in their own order: those sharing a number with the [draft], then those sharing
     * a word with the question or draft, at most [max]. Every word the model reads costs time on the phone (Boss: "late
     * response"), and the draft already holds what the answer needs; the check still uses all the facts.
     */
    fun relevant(facts: List<String>, question: String, draft: String, max: Int = 12): List<String> {
        if (facts.size <= max) return facts
        val nums = NUM.findAll(draft).map { it.value.replace(",", "") }.toSet()
        val words = WORD.findAll((question + " " + draft).lowercase()).map { it.value }.filter { it.length > 3 }.toSet()
        fun score(f: String): Int {
            val n = NUM.findAll(f).count { it.value.replace(",", "") in nums }
            val w = WORD.findAll(f.lowercase()).count { it.value in words }
            return n * 10 + w
        }
        val keep = facts.withIndex().map { it to score(it.value) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(max).map { it.first }.sortedBy { it.index }.map { it.value }
        return keep.ifEmpty { facts.take(max) }
    }

    /** Shorter than this, and one sentence: not rewritten. */
    const val SHORT = 90

    private val NUM = Regex("\\d[\\d,]*(\\.\\d+)?")
    private val WORD = Regex("[a-z]+")

    /** Should the model be asked at all? Only for answers built from facts - never orders, refusals or advice questions. */
    fun worthRewriting(q: Question, a: Answer): Boolean =
        // A one-line answer is plain already; rewriting it would only hold the model while the next question waits.
        (a.text.length >= SHORT || Regex("(?<=[.!?])\\s+\\S").findAll(a.text).count() >= 1) &&
        a.facts.isNotEmpty() && a.order == null && Topic.ADVICE !in q.topics && Topic.ORDER !in q.topics && Topic.BACKTEST !in q.topics

    private val ADVICE = Regex("\\b(should|shouldn't|recommend\\w*|suggest\\w*|advis\\w*|consider (buying|selling)|buy now|sell now|go long|go short|" +
        "stop[- ]?loss|target (price|of)|will (rise|fall|go up|go down|rally|crash)|expect\\w*|likely to|predict\\w*|forecast\\w*|guarantee\\w*)\\b",
        RegexOption.IGNORE_CASE)

    /**
     * The model's [output] cleaned, or null when it may not be shown: a number not in [facts] or the [draft] (the draft
     * is built from the facts), advice or forecast words the draft did not have, a link or a template token, or nothing.
     */
    fun check(output: String, facts: List<String>, draft: String): String? {
        var t = output.substringBefore("<|im_end|>").replace(Regex("<\\|[^|]*\\|>"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.length > 900) return null
        if (Regex("https?://|www\\.", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        val sentences = Regex("(?<=[.!?])\\s+").split(t).filter { it.isNotBlank() }
        if (sentences.size > 4) t = sentences.take(4).joinToString(" ")
        if (ADVICE.containsMatchIn(t) && !ADVICE.containsMatchIn(draft)) return null
        val known = facts + draft
        if (!Ira().numbersBacked(t, known)) return null
        return t
    }
}
