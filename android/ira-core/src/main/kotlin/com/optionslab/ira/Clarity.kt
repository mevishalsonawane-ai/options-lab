package com.optionslab.ira

import java.time.LocalDateTime

/**
 * Jarvis learning which of his answers Boss finds unclear (learning round 11, 2026-10-05): when Boss answers one of
 * Jarvis's answers with "what?", "come again", "pardon", "samjha nahi", "matlab?" or "what do you mean?" within
 * [REACT_MS] of asking, that answer's KIND (its main topic - the levels, the trend, the news...; [SelfDoubt.tags], never
 * the words) is noted as unclear. Set against how often Boss asked that kind at all (the daily kind tally [DoubtTally]
 * that [SelfDoubt] keeps already), a kind he keeps needing again is said SHORTER aloud:
 *
 *  - [Level.SHORTER]: two sentences aloud, the rest in the chat;
 *  - [Level.SHORTEST]: clearly unclear there - one sentence aloud, the rest in the chat.
 *
 * Only the words Jarvis SAYS change: the chat keeps every answer in full, "tell me more" still says the whole answer, and
 * a command or an order is never shortened or touched ([sentences] is for an answer to a question only). Nothing learned
 * here acts. "Tell me more" and "go on" are wanting more, not finding it unclear, and never count. "Which answers do you
 * keep short?" names them ([Request.WHICH]); "say your answers in full again" (and Boss's own "full answers") starts the
 * count afresh ([reset]). Only the last [WINDOW_DAYS] days count, so a kind that turns clear goes back to normal. Pure.
 */
object Clarity {
    /** Only the last this many days count. */
    const val WINDOW_DAYS = 30L
    /** "What?" this soon after the question was asked is about its answer (answers come in seconds; slow ones are rare). */
    const val REACT_MS = 90_000L
    /** Unclear this many times in a kind before anything changes (one "pardon" decides nothing: he may not have heard). */
    const val MIN_UNCLEAR = 3
    /** As if this many more answers of the kind had been clear: a few questions never decide alone. */
    const val PRIOR = 4.0
    const val SHORTER_RATE = 0.2
    const val SHORTEST_RATE = 0.35
    const val SHORTEST_UNCLEAR = 5
    /** Events kept at most. */
    const val KEEP = 300
    /** Kinds named at most. */
    const val SHOW = 3

    enum class Level(val sentences: Int?) { NORMAL(null), SHORTER(2), SHORTEST(1) }

    /** One unclear answer: its kind key ("topic:LEVELS") and when Boss said so. No words are kept. */
    data class Event(val kind: String, val at: LocalDateTime)

    /** The unclear answers noted, and when Boss last asked for full answers again (nothing before it counts). */
    data class Log(val events: List<Event> = emptyList(), val resetAt: LocalDateTime? = null)

    /** The kind of answer [text] gets: its main topic's key, or null (a command, an order, words not understood). */
    fun kind(text: String): String? = runCatching { SelfDoubt.tags(text).firstOrNull { it.dim == SelfDoubt.Dim.TOPIC }?.key }.getOrNull()

    private fun norm(text: String) = " " + text.lowercase().replace("'", "").replace("’", "")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private const val LEAD = "^ (sorry |umm |um |uh |hmm |jarvis |boss )*"
    private const val TAIL = "( jarvis| boss| please| sorry| again| bhai| yaar)* $"
    private val UNCLEAR = Regex(LEAD + "(" +
        "what|wha|huh|eh|pardon( me)?|come again|say (that|it)? ?again|repeat( that| it)?|what did you (just )?say|what was that|" +
        "(i )?(didnt|did not|dont|do not) (get|understand|follow)( that| it| you| this)?|(i )?(didnt|did not) catch (that|it)|" +
        "what do you mean( by that)?|what does that mean|meaning|you lost me|(that was |thats |too )?(confusing|unclear|not clear)|" +
        "(im|i am) (confused|lost)|" +
        "kya|matlab|matlab kya|kya matlab( hai)?|iska matlab|samjha nahi|samjhi nahi|samajh nahi aaya|kuch samajh nahi aaya|" +
        "samjha nahin|kya bola|kya kaha|kya bole|phir se bolo|phir se|dobara bolo|dobara|ek baar phir" +
        ")" + TAIL)

    /** Boss saying he did not follow an answer ("what?", "come again", "samjha nahi") - never "tell me more" or "go on". */
    fun unclear(text: String): Boolean = UNCLEAR.containsMatchIn(norm(text))

    /** [log] with one unclear answer of [kind] at [at] noted; older than the window (and past [KEEP]) dropped. */
    fun heard(log: Log, kind: String, at: LocalDateTime): Log {
        val from = at.minusDays(WINDOW_DAYS)
        return log.copy(events = (log.events.filter { it.at.isAfter(from) } + Event(kind, at)).takeLast(KEEP))
    }

    /** Boss asked for full answers again: the count starts afresh from [now]. */
    fun reset(log: Log, now: LocalDateTime): Log = Log(emptyList(), now)

    /** One kind's record: answers he found unclear of those asked (asked is never under unclear: the tally may start late). */
    data class Record(val kind: String, val unclear: Int, val asked: Int) {
        val rate: Double get() = unclear / (asked + PRIOR)
        val level: Level get() = when {
            unclear >= SHORTEST_UNCLEAR && rate >= SHORTEST_RATE -> Level.SHORTEST
            unclear >= MIN_UNCLEAR && rate >= SHORTER_RATE -> Level.SHORTER
            else -> Level.NORMAL
        }
        val phrase: String get() = SelfDoubt.phraseOf(kind)
        fun say(): String = "you asked \"what?\" or to hear it again after $unclear of my $asked $phrase"
        fun how(): String = if (level == Level.SHORTEST) "one sentence aloud, the rest in the chat" else "two sentences aloud, the rest in the chat"
    }

    /** Every kind with an unclear answer since the window (or the reset), most unclear first. */
    fun records(log: Log, tally: DoubtTally, now: LocalDateTime): List<Record> {
        val from = listOfNotNull(now.minusDays(WINDOW_DAYS), log.resetAt).max()
        val events = log.events.filter { it.at.isAfter(from) && !it.at.isAfter(now) }
        if (events.isEmpty()) return emptyList()
        val fromDay = from.toLocalDate(); val today = now.toLocalDate()
        val asked = HashMap<String, Int>()
        tally.filterKeys { !it.isBefore(fromDay) && !it.isAfter(today) }.values
            .forEach { m -> m.forEach { (k, n) -> asked[k] = (asked[k] ?: 0) + maxOf(0, n) } }
        return events.groupingBy { it.kind }.eachCount().map { (k, n) -> Record(k, n, maxOf(n, asked[k] ?: 0)) }
            .sortedWith(compareByDescending<Record> { it.level }.thenByDescending { it.rate }.thenByDescending { it.unclear })
    }

    /** The kinds he now says shorter aloud, most unclear first. */
    fun shorter(log: Log, tally: DoubtTally, now: LocalDateTime): List<Record> = records(log, tally, now).filter { it.level != Level.NORMAL }

    /**
     * How many sentences to say aloud answering [text], from the kinds said shorter ([shorter]); null: as usual. Never for
     * a command or an order (they have no kind).
     */
    fun sentences(text: String, shorter: List<Record>): Int? {
        if (shorter.isEmpty()) return null
        val k = kind(text) ?: return null
        return shorter.firstOrNull { it.kind == k }?.level?.sentences
    }

    /** The newest unclear answer of [kind] counted (for the ledger's date). */
    fun newest(log: Log, kind: String): LocalDateTime? = log.events.filter { it.kind == kind && (log.resetAt == null || it.at.isAfter(log.resetAt)) }.maxOfOrNull { it.at }

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private const val ASK_LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val ASK_TAIL = "( please| boss| jarvis| now)* $"
    private val WHICH = Regex(ASK_LEAD + "(which|what) (of )?(your |my )?(answers|replies|kinds of answers?) (do|have|did) (you|i) (keep|kept|say|make|cut) (short|shorter|brief)( now| aloud| these days)?" + ASK_TAIL + "|" +
        ASK_LEAD + "(which|what) (of )?(your |my )?(answers|replies) (do you|have you) (shorten|shortened|cut short|trim|trimmed)( now| aloud)?" + ASK_TAIL + "|" +
        ASK_LEAD + "(which|what) (of )?(your )?(answers|replies) (do i|did i) (find|found) (unclear|confusing|hard to follow)" + ASK_TAIL + "|" +
        ASK_LEAD + "(which|what) (of )?(your )?(answers|replies) (are|were) (unclear|confusing|hard to follow)( to me)?" + ASK_TAIL + "|" +
        ASK_LEAD + "(why|how come) (are )?(your )?answers (are )?(so )?(short|shorter) (now|lately|these days)" + ASK_TAIL + "|" +
        ASK_LEAD + "(kaun se|kon se|kaunse) (jawab|answers) (chhote|chote|short) (karte|kar rahe) ho" + ASK_TAIL + "|" +
        // Round 10: "are you keeping your answers short", "why are you giving short answers".
        ASK_LEAD + "(are|r) (you|u) (keeping|making|giving) (your |me |my )?(answers|replies) (short|shorter|brief)( now| lately| these days)?" + ASK_TAIL + "|" +
        ASK_LEAD + "(are|r) (you|u) (shortening|cutting short|trimming) (your |my )?(answers|replies)( now| lately| these days)?" + ASK_TAIL + "|" +
        ASK_LEAD + "(why|how come) (are|r) (you|u) (giving|keeping|making) (me )?(such )?(short|shorter|brief) (answers|replies)( now| lately| these days)?" + ASK_TAIL)
    private val RESET = Regex(ASK_LEAD + "(say|give|speak) (your |me your |me |my )?answers in full again" + ASK_TAIL + "|" +
        ASK_LEAD + "(dont|do not|no need to) (shorten|cut short|trim) (your |my |the )?answers( any ?more)?" + ASK_TAIL + "|" +
        ASK_LEAD + "(forget|reset|clear) (which|the) answers (i found |were |i find )?(unclear|confusing)" + ASK_TAIL + "|" +
        // Round 10: "can you give full answers (again)" - every answer, never the last one ("give me the full answer" is that).
        ASK_LEAD + "(give|say) (me )?full answers( again| from now on| always)?" + ASK_TAIL)

    /** "Which answers do you keep short?" or "say your answers in full again", else null. */
    fun asked(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    const val UNDO = "say your answers in full again"
    const val ONLY_VOICE = "The chat keeps every answer in full and \"tell me more\" says the whole of it; only my voice is shorter - nothing I learn acts."

    /** "Which answers do you keep short?". */
    fun say(log: Log, tally: DoubtTally, now: LocalDateTime): String {
        val s = shorter(log, tally, now)
        if (s.isEmpty()) {
            val some = records(log, tally, now).sumOf { it.unclear }
            return "I say every answer as usual, Boss" + (if (some > 0) " - you asked \"what?\" after $some of my answers lately, too few of one kind to change anything" else "") +
                ". If you keep asking \"what?\" or \"come again\" after one kind of answer, I'll say that kind shorter aloud. $ONLY_VOICE"
        }
        return "Boss, I say these shorter aloud: " + s.take(SHOW).joinToString("; ") { "${it.phrase} (${it.how()}) - ${it.say()}" } +
            (if (s.size > SHOW) "; and ${s.size - SHOW} more" else "") + ", in the last $WINDOW_DAYS days. $ONLY_VOICE Say \"$UNDO\" to start afresh."
    }

    /** "Say your answers in full again". */
    fun sayReset(log: Log, tally: DoubtTally, now: LocalDateTime): String =
        if (shorter(log, tally, now).isEmpty()) "I'm already saying every answer as usual, Boss. I'll start my count afresh from now."
        else "Done, Boss: every answer as usual aloud again, and my count starts afresh from now."
}
