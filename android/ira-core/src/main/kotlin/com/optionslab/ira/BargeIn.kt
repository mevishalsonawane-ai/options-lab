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
        "quiet|be quiet|shut up|silence|hush|chup|chup ho jao|chup raho|chup karo|bas|bas bas|bas karo|bas ho gaya|rehne do|rukk?o|ruk jao|" +
        "wait|wait wait|hold on|one second|one sec|ok ok|okay okay|ok ok ok|ok got it|okay got it|got it|fine fine|theek hai theek hai|thik hai thik hai|" +
        "next|next one|skip|skip it|never ?mind|forget it|leave it|cancel that)( please| now| boss)?$")

    private val GO_ON = Regex("^(ok |okay |yes |yeah |haan |ha |please |so )?(go on|carry on|continue|keep going|keep talking|you were saying|" +
        "finish|finish it|finish that|say the rest|tell me the rest|the rest|rest of it|and the rest|what were you saying|" +
        "aage bolo|aage batao|aage bata|aage boliye|aage bataiye|baaki bolo|baki bolo|baaki batao|baki batao|bolte raho|haan bolo|haan aage)( please| now| boss)?$")

    private fun norm(s: String?) = spacedWords((s ?: "").lowercase())

    /**
     * What a partial reading [partial] heard while Jarvis was saying [saying] means: [Cut.NAME], [Cut.HUSH], or null
     * (his own words heard back, the room, or a sentence that is not a stop - it is left to the turn's end).
     */
    fun cut(partial: String?, saying: String?): Cut? {
        val t = norm(partial)
        if (t.isEmpty()) return null
        if (Wake.named(t)) return Cut.NAME
        val said = norm(saying).split(' ').filter { it.isNotEmpty() }.toSet()
        // His own words first (the speaker's tail heard before Boss spoke), then Boss's.
        val tail = t.split(' ').dropWhile { it in said }
        if (tail.isEmpty() || tail.size > MAX_STOP_WORDS) return null
        // Any of those words in what he is saying could be the echo itself ("Shall I stop ORB?"): not a stop.
        if (tail.any { it in said }) return null
        return if (STOP.matches(tail.joinToString(" "))) Cut.HUSH else null
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
