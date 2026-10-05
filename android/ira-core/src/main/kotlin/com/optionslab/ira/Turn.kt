package com.optionslab.ira

/**
 * The end of a spoken turn, read from the recognizer's partial words (Boss, 4 Oct: "late response"). When the turn is
 * closed, the recognizer still takes a moment (up to a second or more) to give its final reading; a plain question
 * whose words have stood still is answered from them at once instead. Pure: the words in, what to do out.
 */
object Turn {
    /** The partial reading unchanged this long is what Boss said (the final reading adds nothing to it). */
    const val STABLE_MS = 600L

    /** Did the words change? A repeated, identical partial must not push the end of the turn back. */
    fun changed(before: String?, now: String): Boolean = norm(before.orEmpty()) != norm(now)

    /**
     * The words to answer now from a partial reading unchanged for [stableForMs], before the recognizer's final one - or
     * null to wait for it as before. Plain questions answered from what the phone keeps (prices, levels, patterns, news,
     * help, a greeting) only: never a command, an order, the account, a backtest, a suggested trade or words only the
     * model can place - those wait for the best reading (and the voice check). Nothing here acts; the words then go the
     * usual way, with every rule of a final reading.
     */
    fun early(partial: String?, awake: Boolean, stableForMs: Long): String? {
        if (stableForMs < STABLE_MS) return null
        val p = partial?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val h = Wake.heard(p, awake) as? Wake.Heard.Ask ?: return null
        // One word ("Jarvis... how") is a breath inside the sentence, not a question.
        if (norm(h.question).split(' ').size < 2) return null
        val q = Ask.parse(h.question)
        if (q.command != null || q.order != null || q.topics.isEmpty() || !QUICK.containsAll(q.topics)) return null
        return p
    }

    /**
     * When Boss finished speaking (elapsed ms), for the spoken-answer wait: the earlier of the last time his words changed
     * ([wordsAt]) and the recognizer's "speech ended" ([endAt]) - each 0 when not seen this turn, and a time more than
     * [STALE_MS] old (another turn's) is not taken. Neither: [now] (the wait then counts from the turn's end, as before).
     * The turn's closing wait and the recognizer's final reading are part of what Boss waits for, so they count too.
     */
    fun spokeEnd(wordsAt: Long, endAt: Long, now: Long): Long =
        listOf(wordsAt, endAt).filter { it > 0 && it <= now && now - it <= STALE_MS }.minOrNull() ?: now

    const val STALE_MS = 20_000L

    /** After the recognizer's "speech ended", the turn is closed this long later (unless it was already due sooner). */
    const val END_SPEECH_MS = 700L

    /**
     * How long from [now] ("speech ended" just heard) until the turn is closed, with a close already due at [dueAt]
     * (elapsed ms; 0 or past: none). "Speech ended" only ever brings the close nearer: it used to replace a close due
     * sooner (the words standing still, 0.9 s after they last changed) with one [END_SPEECH_MS] later, so Boss waited
     * longer exactly when the recognizer agreed he had finished (Boss, 5 Oct: speed). The close itself is unchanged -
     * it only ends the listening, and the words then go the usual way, with every rule.
     */
    fun closeIn(now: Long, dueAt: Long): Long =
        if (dueAt > now && dueAt - now < END_SPEECH_MS) dueAt - now else END_SPEECH_MS

    /** What a question may be about to be answered from its partial reading. */
    private val QUICK = setOf(Topic.OVERVIEW, Topic.WHY, Topic.TREND, Topic.LEVELS, Topic.PATTERNS, Topic.NEWS,
        Topic.VOLATILITY, Topic.HELP, Topic.GREETING, Topic.EXPLAIN)

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9% ]"), " ").replace(Regex("\\s+"), " ").trim()
}
