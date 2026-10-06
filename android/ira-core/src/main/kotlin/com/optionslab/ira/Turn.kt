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
        return p.takeIf { plain(p, awake) != null }
    }

    /**
     * The question in a partial reading whose answer may be worked out ahead, while Boss is still speaking or the
     * recognizer's final reading is awaited - or null. The same plain questions as [early], before their words have stood
     * still. Only the answer's words are worked out: nothing is said, shown, counted or done from them; the final words
     * then go the usual way, and the answer worked out ahead is used only for the same question ([Ahead]).
     */
    fun ahead(partial: String?, awake: Boolean): String? {
        val p = partial?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return plain(p, awake)
    }

    /** [p] said to Jarvis as a plain question: its question, else null. */
    private fun plain(p: String, awake: Boolean): String? {
        // Cut off mid-sentence ("Jarvis how is my", "why did I"): never answered or worked out from - its end is to come.
        if (unfinished(p)) return null
        val h = Wake.heard(p, awake) as? Wake.Heard.Ask ?: return null
        // One word ("Jarvis... how") is a breath inside the sentence, not a question.
        if (norm(h.question).split(' ').size < 2) return null
        return h.question.takeIf { quick(Ask.parse(it)) }
    }

    /**
     * [q] as the key of an answer worked out ahead ([Ahead]): its words normalised (case, punctuation, spacing), the rest
     * as read. A plain answer is made from the reading - markets, topics, pattern - and the words only by their words, so
     * readings with the same key get the same answer ("how is nifty" and the final "How is Nifty?").
     */
    fun key(q: Question): Question = q.copy(text = norm(q.text))

    /** Is [q] (as read) a plain question an answer may be worked out ahead for? */
    fun quick(q: Question): Boolean = q.command == null && q.order == null && q.topics.isNotEmpty() && QUICK.containsAll(q.topics)

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
     * it only ends the listening, and the words then go the usual way, with every rule. [unfinished] (the words so far stop
     * mid-sentence, [Turn.unfinished]): a close already due is kept even when later - his breath before the rest of the
     * sentence is often read as "speech ended" - so the rest is still heard (Voice, round 29).
     */
    fun closeIn(now: Long, dueAt: Long, unfinished: Boolean = false): Long =
        if (dueAt > now && (unfinished || dueAt - now < END_SPEECH_MS)) dueAt - now else END_SPEECH_MS

    /**
     * Voice, round 29 (Boss: "Jarvis what is my" answered before he said "P&L"): words whose last word cannot end a
     * sentence - an article, "my", a preposition that needs what follows, a joining word, a question's verb or subject with
     * the rest still to come, a possessive "'s", the Hinglish "ka / ki / ke / aur / mera / agar" - stop at a breath inside
     * the sentence. A still partial reading ending so is never answered early ([early]) nor worked out ahead ([ahead]),
     * and the turn is closed no sooner than [UNFINISHED_MS] after the words last changed ([endAfter], [closeIn]) - as for
     * the name alone. Only the wait changes: the words then go the usual way, with every rule (a yes or a no reads
     * exactly as before: "yes", "no", "haan", "nahi", "ya" are never such a word).
     */
    fun unfinished(words: String?): Boolean {
        val w = norm(words.orEmpty()).split(' ').filter { it.isNotEmpty() }
        return w.size >= 2 && w.last() in DANGLING
    }

    /** How long the words must stand still before the turn is closed: [paceMs] (Boss's own pause), longer for [unfinished] words. */
    fun endAfter(words: String?, paceMs: Long): Long = if (unfinished(words)) maxOf(paceMs, UNFINISHED_MS) else paceMs

    /** The least wait before a turn whose words stop mid-sentence is closed (the same as for the name alone). */
    const val UNFINISHED_MS = 1_800L

    /**
     * Last words that leave a sentence open. Not those that also end a whole question ("on", "in", "off", "up", "do",
     * "did", "this", "that", "you", "how", "why", "like", "before", "at", "we"): "is the kill switch on", "am I logged in", "what do
     * I do", "how are you", "what is nifty trading at", "where are we" close as quickly as before (review, 6 Oct).
     */
    private val DANGLING = setOf("the", "a", "an", "my", "your", "our", "his", "her", "their", "its", "s",
        "of", "for", "to", "about", "with", "from", "into", "versus", "vs", "than", "between", "compared", "per",
        "and", "or", "but", "because", "if", "what", "which",
        "is", "are", "was", "were", "does", "will", "would", "should", "can", "could", "i",
        "ka", "ki", "ke", "ko", "aur", "mera", "meri", "mere", "agar")

    /** What a question may be about to be answered from its partial reading. */
    private val QUICK = setOf(Topic.OVERVIEW, Topic.WHY, Topic.TREND, Topic.LEVELS, Topic.PATTERNS, Topic.NEWS,
        Topic.VOLATILITY, Topic.HELP, Topic.GREETING, Topic.EXPLAIN)

    private fun norm(s: String) = spacedWords(s.lowercase(), "%")
}

/**
 * One answer worked out ahead of the recognizer's final reading ([Turn.ahead]), kept with what it was worked out from
 * ([K]: the question as read and every input of the answer). The final words take it only when their key is the same -
 * the same question from the same prices - and at most once; any other key drops it (words that changed are never
 * answered from). Making it does nothing else: the answer then goes the usual way, with its usual effects.
 */
class Ahead<K : Any, V : Any> {
    private var kept: Pair<K, V>? = null

    @Synchronized fun put(key: K, value: V) { kept = key to value }

    /** The answer kept for [key], or null; either way nothing stays kept. */
    @Synchronized fun take(key: K): V? = kept?.takeIf { it.first == key }?.second.also { kept = null }
}
