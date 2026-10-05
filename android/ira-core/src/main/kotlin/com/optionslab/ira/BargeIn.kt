package com.optionslab.ira

/**
 * Boss talking over Jarvis (Boss, 5 Oct: "stop", "bas", "ok ok", a new question while he talks). Read from the
 * recognizer's partial words while Jarvis speaks, so his voice stops as soon as the words are read - not at the end of
 * Boss's turn. Jarvis's own voice heard back is never Boss: he never says his name aloud, so the name is always Boss's;
 * a stop word counts only when it is not one of the words Jarvis is saying, after any of his own words heard first.
 * Stopping only ever stops the voice: nothing is done or said from a cut.
 *
 * "Go on" / "continue" / "aage bolo" after a cut says the rest of the answer that was cut off - its words only (the
 * chat already shows them), never anything new. Pure.
 */
object BargeIn {
    enum class Cut {
        /** The name heard over him: stop now and keep listening, the question follows in the same turn. */
        NAME,
        /** A clear stop word ("stop", "bas", "ok ok", "next"): stop now and listen again at once. */
        HUSH,
    }

    /** At most this many words of Boss's own after any echo: a stop word is a short thing to say. */
    private const val MAX_STOP_WORDS = 4

    private val STOP = Regex("^(ok |okay |please |just |arre |arey |haan |ha )?(stop|stop it|stop now|stop that|stop talking|stop speaking|enough|that s enough|thats enough|" +
        "quiet|be quiet|shut up|silence|hush|chup|chup ho jao|chup raho|chup karo|bas|bas bas|bas karo|bas ho gaya|rehne do|rukk?o|ruko ruko|rukiye|ruk jao|" +
        "wait|wait wait|hold on|one second|one sec|ok ok|okay okay|ok ok ok|ok got it|okay got it|got it|fine fine|theek hai theek hai|thik hai thik hai|" +
        "next|next one|skip|skip it|never ?mind|forget it|leave it|cancel that)( please| now| boss)?$")

    private val GO_ON = Regex("^(ok |okay |yes |yeah |haan |ha |please |so )?(go on|carry on|continue|keep going|keep talking|you were saying|" +
        "finish|finish it|finish that|say the rest|tell me the rest|the rest|rest of it|and the rest|what were you saying|" +
        "aage bolo|aage batao|aage bata|aage boliye|aage bataiye|baaki bolo|baki bolo|baaki batao|baki batao|bolte raho|haan bolo|haan aage)( please| now| boss)?$")

    private fun norm(s: String?) = spacedWords((s ?: "").lowercase())

    /**
     * Stop words firm enough to count wherever Boss says them among the newest words, not only as the whole of what is
     * left after his echo: one recognizer word each, or two side by side.
     */
    private val FIRM = setOf("stop", "bas", "ruko", "rukko", "rukiye", "chup", "enough", "quiet", "hush", "silence", "wait")
    private val FIRM_PAIRS = setOf("shut up", "ruk jao", "bas karo", "chup karo", "be quiet")

    /** After a firm stop word, at most this many words, all his own (the speaker's tail read after Boss's stop). */
    private const val ECHO_AFTER = 2

    /**
     * What a partial (or final) reading [partial] heard while Jarvis was saying [saying] means: [Cut.NAME], [Cut.HUSH],
     * or null (his own words heard back, the room, or a sentence that is not a stop - it is left to the turn's end).
     *
     * Boss's diagnostics (5 Oct): his "stop" over Jarvis was lost whenever the recognizer misread one of Jarvis's own
     * words before it ("nifty is at 24612 stop": "24612" is not one of his words as said, so the stop was not the whole of
     * what was left and nothing counted). Now the newest words are read: a stop phrase ending the reading, or a firm stop
     * word followed only by a word or two of his own, counts - unless that very word is his own, heard in the same place
     * ([own]). The name and the stop words are never taken for his echo just because his other words surround them.
     */
    fun cut(partial: String?, saying: String?): Cut? {
        val t = norm(partial)
        if (t.isEmpty()) return null
        if (Wake.named(t)) return Cut.NAME
        val h = t.split(' ').filter { it.isNotEmpty() }
        val s = norm(saying).split(' ').filter { it.isNotEmpty() }
        val said = s.toSet()
        // A stop phrase ending the reading ("stop", "banks led stop", "ok ok", "bas karo").
        for (k in 1..minOf(MAX_STOP_WORDS, h.size)) {
            val from = h.size - k
            if (!STOP.matches(h.subList(from, h.size).joinToString(" "))) continue
            // A soft one ("ok ok", "next") only after nothing but his own words: inside a sentence of Boss's it is no stop.
            if (!firm(h, from, k) && !h.subList(0, from).all { it in said }) continue
            if (ownPhrase(h, from, k, s, said)) continue
            return Cut.HUSH
        }
        // A firm stop word with only a word or two of his own read after it ("stop the": the speaker's tail).
        for (i in h.indices.reversed()) {
            val len = when {
                i + 1 < h.size && "${h[i]} ${h[i + 1]}" in FIRM_PAIRS -> 2
                h[i] in FIRM -> 1
                else -> continue
            }
            val after = h.subList(minOf(h.size, i + len), h.size)
            // Nothing after it: judged above (a stop phrase ending the reading). More, or Boss's own words: his sentence.
            if (after.isEmpty() || after.size > ECHO_AFTER || !after.all { it in said }) continue
            if ((i until i + len).any { h[it] in said && own(h, it, s) }) continue
            return Cut.HUSH
        }
        return null
    }

    /** Is [h] from [from] ([k] words) a firm stop ([FIRM], [FIRM_PAIRS])? */
    private fun firm(h: List<String>, from: Int, k: Int): Boolean {
        val w = h.subList(from, from + k)
        return w.any { it in FIRM } || (0 until w.size - 1).any { "${w[it]} ${w[it + 1]}" in FIRM_PAIRS }
    }

    /**
     * Could the stop phrase [h] from [from] ([k] words) be his own words heard back? A firm stop word: only when it is
     * his, heard in the same place ([own]). Any other word ("ok ok", "next", "got it"): whenever it is one of his.
     */
    private fun ownPhrase(h: List<String>, from: Int, k: Int, s: List<String>, said: Set<String>): Boolean {
        for (i in from until from + k) {
            val w = h[i]
            if (w !in said) continue
            if (w !in FIRM || own(h, i, s)) return true
        }
        return false
    }

    /**
     * Is the word [h]`[i]`, one of the words he is saying ([s]), his own heard back? Yes when a word heard next to it is
     * its neighbour in what he is saying too, or when there is nothing around it to compare (a lone "stop" while he says
     * "stop"); no when its neighbours differ ("stop stop" while he says "a stop loss" is Boss).
     */
    internal fun own(h: List<String>, i: Int, s: List<String>): Boolean {
        for (j in s.indices) {
            if (s[j] != h[i]) continue
            val prev = i > 0 && j > 0
            val next = i < h.lastIndex && j < s.lastIndex
            if (!prev && !next) return true
            if ((prev && s[j - 1] == h[i - 1]) || (next && s[j + 1] == h[i + 1])) return true
        }
        return false
    }

    /** [cut] over all of the recognizer's readings: the name in any of them, else the first stop. */
    fun cutAny(readings: List<String>, saying: String?): Cut? {
        if (readings.any { Wake.named(norm(it)) }) return Cut.NAME
        return readings.asSequence().mapNotNull { cut(it, saying) }.firstOrNull()
    }

    /** Is [text] (the name already taken off) Boss asking for the rest of what was cut off? */
    fun goOn(text: String): Boolean = GO_ON.matches(norm(text).replace(rx("^(jarvis )+|( jarvis)+$"), "").trim())

    /** An answer cut off: its full text (as in the chat), the first sentence not yet heard, and whether it is about the account. */
    data class CutOff(val full: String, val from: Int, val account: Boolean, val at: Long)

    /** How long "go on" may still bring back a cut-off answer. */
    const val KEEP_MS = 120_000L

    /** A sentence ends at . ! ? or the Hindi full stop, never inside a number. */
    private val SENTENCE = Regex("(?<=[.!?\\u0964])\\s+")

    fun sentences(text: String): List<String> = SENTENCE.split(text.trim()).filter { it.isNotBlank() }

    /**
     * Which sentence of [spoken] was being said at character [at] (0 for the first; the sentence under way is counted
     * as not heard, so it is said again whole; at its end, all of them). [at] below 0 (the voice gave no position): 0.
     */
    fun sentenceAt(spoken: String, at: Int): Int {
        if (at <= 0) return 0
        // All of it said (the voice reached its end).
        if (at >= spoken.trimEnd().length) return sentences(spoken).size
        var n = 0
        for (m in SENTENCE.findAll(spoken)) { if (m.range.last < at) n++ else break }
        return n
    }

    /** The cut-off answer as kept, or null when nothing of it is left unsaid. */
    fun cutOff(full: String?, spoken: String?, at: Int, account: Boolean, now: Long): CutOff? {
        if (full.isNullOrBlank() || spoken.isNullOrBlank()) return null
        val from = sentenceAt(spoken, at)
        return if (from >= sentences(full).size) null else CutOff(full, from, account, now)
    }

    /**
     * An answer said to its end (not cut): what is left of [full] after [spoken] for "go on" - the sentences [spoken]
     * did not say, its own closing line ("The rest is in the chat.") not counted as one of the answer's. Null: nothing left.
     */
    fun finished(full: String?, spoken: String?, account: Boolean, now: Long): CutOff? {
        if (full.isNullOrBlank() || spoken.isNullOrBlank()) return null
        val said = sentences(spoken).count { s -> TAILS.none { s.trim().endsWith(it) } }
        return if (said >= sentences(full).size) null else CutOff(full, said, account, now)
    }

    /** A spoken answer's own closing lines, never one of the answer's sentences. */
    private val TAILS = listOf("The rest is in the chat.", "बाकी चैट में है।", ShortAnswer.MORE_HINT)

    /**
     * The words "go on" says: the cut-off answer from its first unheard sentence, or null (none, too old, or nothing
     * left). A locked phone never says an account answer aloud: [Refused] then.
     */
    sealed class Rest {
        data class Say(val text: String) : Rest()
        data class Refused(val why: String) : Rest()
    }

    fun rest(c: CutOff?, now: Long, locked: Boolean): Rest? {
        if (c == null || now - c.at > KEEP_MS || now < c.at) return null
        val left = sentences(c.full).drop(c.from)
        if (left.isEmpty()) return null
        if (locked && c.account) return Rest.Refused(LockRule.refuse(true, false, true, false)!!)
        return Rest.Say(left.joinToString(" "))
    }
}
