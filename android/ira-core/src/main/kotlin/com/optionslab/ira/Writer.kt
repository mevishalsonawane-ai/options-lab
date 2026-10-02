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
            append("FACTS:\n"); facts.take(40).forEach { append("- ").append(it).append('\n') }
            append("DRAFT: ").append(draft)
        }
        return "<|im_start|>system\n$sys<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }

    /** Should the model be asked at all? Only for answers built from facts - never orders, refusals or advice questions. */
    fun worthRewriting(q: Question, a: Answer): Boolean =
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
