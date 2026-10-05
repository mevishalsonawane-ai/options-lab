package com.optionslab.ira

import java.time.LocalDateTime

/**
 * Jarvis learning how long Boss likes his answers, topic by topic (learning round 22, 2026-10-05). When Boss answers
 * one of Jarvis's answers within [REACT_MINUTES] minutes with a wish for its length - "in short", "short mein batao",
 * "just the gist" ([Dir.SHORT]) or "in detail", "detail mein batao", "elaborate" ([Dir.LONG]) - that answer's KIND (its
 * main topic, [Clarity.kind]: the levels, the trend, the news...) and the wish's direction are noted: never his words,
 * never the answer.
 *
 * A topic he asked one way at least [MIN_TIMES] times more than the other in the last [WINDOW_DAYS] days (since the last
 * reset) is learned ([learned]): that topic's answers are then SAID one sentence long ([Dir.SHORT]) or in full
 * ([Dir.LONG], as "tell me more" says them) aloud ([sentences]). Only the voice changes: the chat keeps every answer
 * whole, a command or an order is never shortened or lengthened (they have no kind), and Boss's own "short answers"
 * setting is never overridden by anything learned here (the app checks it first).
 *
 * The wish itself is answered on the spot ([reply]): the last answer in its first sentence, or whole. Nothing learned
 * acts - it never arms, trades, switches or confirms anything. "How long do I like your answers?" names the topics
 * ([Request.WHICH]); "say every topic at the usual length" ([UNDO], [Request.RESET]) undoes it and starts the count
 * afresh. Shown in the Learnings ledger with that undo and reset by "undo everything you learned this week". Pure: the
 * app keeps the log.
 */
object TopicLength {
    /** A topic is learned when asked one way at least this many times more than the other... */
    const val MIN_TIMES = 2
    /** ...in the last this many days. */
    const val WINDOW_DAYS = 30L
    /** A wish counts for the answer given at most this many minutes before it. */
    const val REACT_MINUTES = 3L
    /** Notes kept at most. */
    const val KEEP = 120
    /** Topics named at most. */
    const val SHOW = 3

    const val UNDO = "say every topic at the usual length"

    /** Which way Boss wished an answer: [sentences] aloud for a topic learned that way. */
    enum class Dir(val key: String, val sentences: Int, val how: String) {
        SHORT("short", Aloud.Length.SHORT.sentences, "in one sentence aloud"),
        LONG("long", Aloud.Length.FULL.sentences, "in full aloud");
    }

    /** One wish: the answer's kind ([Clarity.kind]'s key), its direction ([Dir.key]) and when. Never words. */
    data class Note(val at: LocalDateTime, val kind: String, val dir: String)

    /** The wishes noted, and when Boss last asked for the usual length (nothing before it counts). */
    data class Log(val notes: List<Note> = emptyList(), val resetAt: LocalDateTime? = null)

    private fun norm(text: String) = " " + text.lowercase().replace("'", "").replace("’", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private const val W_LEAD = "^ (ok |okay |hmm |jarvis |boss |thoda |zara |just |now |acha |accha |achha |haan |so )*"
    private const val W_TAIL = "( please| boss| jarvis| yaar| bhai| na| do| dena| karo| for me| for that| again)* $"
    private const val SAY = "(batao|bolo|bataiye|boliye|samjhao|samjhaiye|kaho|bata do|bol do|samjha do)"
    private val SHORT_WISH = rx(W_LEAD + "(" +
        "in short|in brief|briefly|(tell me |say it |give it to me |put it )?(in short|in brief|briefly)|" +
        "(give me |tell me )?(just )?the gist|(give me |tell me )?the short version|tl ?dr|in (a|one) (line|sentence)|" +
        "(short|chhota|chota|chhote|chote|sankshep) (mein|me|main)( $SAY)?|(short|chhota|chota) (karke|kar ke|kar) $SAY|" +
        "ek (line|lafz|shabd) (mein|me|main)( $SAY)?|sankshep (mein|me)( $SAY)?" +
        ")" + W_TAIL)
    private val LONG_WISH = rx(W_LEAD + "(" +
        "in detail|(tell me |say it |explain it |explain that |explain )?in (full )?detail|(please )?elaborate( on (that|it|this))?|" +
        "(give me |tell me )?the (long|full|detailed) version|go deeper( into (it|that))?|explain (it |that )?properly|" +
        "(detail|details|vistar|vistaar|tafseel) (mein|me|main|se)( $SAY)?|(poori|puri|pura|poora) (detail|baat)( $SAY)?|" +
        "(detail|details) (mein|me) (samjhao|batao|bolo)" +
        ")" + W_TAIL)

    /**
     * The length Boss wishes in [text] ("in short", "detail mein batao"), or null: a bare wish only - never a question
     * about something else, an order or a command ("shorter", "tell me more" and "full answers" stay his commands).
     */
    fun wish(text: String): Dir? {
        val t = norm(text)
        val d = when {
            SHORT_WISH.containsMatchIn(t) -> Dir.SHORT
            LONG_WISH.containsMatchIn(t) -> Dir.LONG
            else -> return null
        }
        val p = runCatching { Ask.parse(text) }.getOrNull() ?: return null
        if (p.order != null || p.command != null) return null
        return d
    }

    /** Is a wish at [now] about the answer given at [answeredAt]? */
    fun fresh(answeredAt: LocalDateTime, now: LocalDateTime): Boolean =
        !now.isBefore(answeredAt) && !now.isAfter(answeredAt.plusMinutes(REACT_MINUTES))

    /** [log] with Boss's wish [dir] after an answer of [kind] at [at] noted; older than the window (and past [KEEP]) dropped. */
    fun heard(log: Log, kind: String, dir: Dir, at: LocalDateTime): Log {
        val from = at.minusDays(WINDOW_DAYS)
        return log.copy(notes = (log.notes.filter { it.at.isAfter(from) } + Note(at, kind, dir.key)).sortedBy { it.at }.takeLast(KEEP))
    }

    /** "Say every topic at the usual length": nothing before [now] counts any more. */
    fun reset(log: Log, now: LocalDateTime): Log = Log(emptyList(), now)

    /** A learned topic: its kind, the way it is said, the wishes each way, the newest wish. */
    data class Record(val kind: String, val dir: Dir, val same: Int, val other: Int, val newest: LocalDateTime) {
        val phrase: String get() = SelfDoubt.phraseOf(kind)
        fun say(): String = "you asked for ${if (dir == Dir.SHORT) "it in short" else "more detail"} $same time${if (same == 1) "" else "s"}" +
            (if (other > 0) " (the other way $other)" else "")
    }

    /** The topics learned at [now], the clearest first. Empty until one is wished one way [MIN_TIMES] times more than the other. */
    fun learned(log: Log, now: LocalDateTime): List<Record> {
        val from = now.minusDays(WINDOW_DAYS)
        val reset = log.resetAt
        return log.notes.filter { it.at.isAfter(from) && !it.at.isAfter(now) && (reset == null || it.at.isAfter(reset)) }
            .groupBy { it.kind }
            .mapNotNull { (k, xs) ->
                val s = xs.count { it.dir == Dir.SHORT.key }
                val l = xs.count { it.dir == Dir.LONG.key }
                val newest = xs.maxOf { it.at }
                when {
                    s - l >= MIN_TIMES -> Record(k, Dir.SHORT, s, l, newest)
                    l - s >= MIN_TIMES -> Record(k, Dir.LONG, l, s, newest)
                    else -> null
                }
            }
            .sortedWith(compareByDescending<Record> { it.same - it.other }.thenByDescending { it.newest }.thenBy { it.kind })
    }

    /**
     * How many sentences to say aloud answering [text], from the topics learned ([learned]); null: as usual. Never for a
     * command or an order (they have no kind).
     */
    fun sentences(text: String, learned: List<Record>): Int? = if (learned.isEmpty()) null else sentencesOf(Clarity.kind(text), learned)

    /** [sentences] for a question of kind [kind] ([Clarity.kind], read once by the caller; null: none). */
    fun sentencesOf(kind: String?, learned: List<Record>): Int? {
        if (learned.isEmpty()) return null
        val k = kind ?: return null
        return learned.firstOrNull { it.kind == k }?.dir?.sentences
    }

    private val SENTENCE = Regex("(?<=[.!?\\u0964])\\s+")

    /**
     * The answer to Boss's wish [dir] for the [last] answer (its full text as in the chat; null: none at hand), with
     * [learnedNow] when this wish just made its topic learned. Words only.
     */
    fun reply(dir: Dir, last: String?, learnedNow: Record?): String {
        val body = last?.trim()?.takeIf { it.isNotEmpty() }
        val head = when {
            body == null -> if (dir == Dir.SHORT) "Noted, Boss: shorter." else "Noted, Boss: in more detail - ask me again and I'll say it whole."
            dir == Dir.SHORT -> "In short, Boss: " + SENTENCE.split(body).first { it.isNotBlank() }.trim()
            else -> "In full, Boss: $body"
        }
        val tail = learnedNow?.let { r ->
            " From now on I'll say ${r.phrase} ${r.dir.how} - the chat keeps every word; say \"$UNDO\" to undo it."
        } ?: ""
        return head + tail
    }

    /** Said for "in detail" on a locked phone: the wish is noted, the answer is not said again. */
    const val LOCKED_LONG = "Noted, Boss. Unlock the phone and I'll say it whole - it's all in the chat."

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| any ?more| from now on| again| aloud| for me)* $"
    private const val ANSWERS = "(my |your |the )?(answers|replies)"
    private const val SIZE = "(short|shorter|brief|in short|long|longer|in detail|in full|detailed)"

    private val WHICH = rx(LEAD + "how (long|short|detailed) do (i|you think i) (like|want|prefer) $ANSWERS( on each topic| by topic| per topic)?" + TAIL + "|" +
        LEAD + "do you (shorten|lengthen|cut short|keep short|say short) (some|any|certain) topics" + TAIL + "|" +
        LEAD + "do you know how (long|short|detailed) i (like|want|prefer) $ANSWERS" + TAIL + "|" +
        LEAD + "(which|what) topics do you (keep|say|make|give|cut) $SIZE( for me)?" + TAIL + "|" +
        LEAD + "(which|what) topics do i (like|want|prefer) $SIZE" + TAIL + "|" +
        LEAD + "(what|which) answer lengths? do i (like|want|prefer)( by topic| per topic)?" + TAIL + "|" +
        LEAD + "(kaun se|kon se|kaunse) topic (short|chhote|chote|detail) (mein|me) (batate|bolte) ho" + TAIL)
    private val RESET = rx(LEAD + "say (every|each|all) topics? at (the |your )?usual length" + TAIL + "|" +
        LEAD + "(forget|reset|clear|unlearn) how (long|short|detailed) i (like|want|prefer) $ANSWERS" + TAIL + "|" +
        LEAD + "(dont|do not|stop|quit) (shortening|lengthening|cutting|changing|sizing) $ANSWERS by topic" + TAIL + "|" +
        LEAD + "(dont|do not) (shorten|lengthen|cut|change|size) $ANSWERS by topic" + TAIL + "|" +
        LEAD + "stop (saying|giving) topics $SIZE" + TAIL + "|" +
        LEAD + "(har|sab) topic (normal|usual) (length|lambai) (mein|me) (bolo|batao)" + TAIL)

    /** "How long do I like your answers?" or "say every topic at the usual length", else null. */
    fun asked(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    const val ONLY_VOICE = "Only my voice changes: the chat keeps every answer whole, your own \"short answers\" setting comes first, and nothing I learn acts."

    /** "How long do I like your answers?". */
    fun say(log: Log, now: LocalDateTime): String {
        val rs = learned(log, now)
        if (rs.isEmpty()) return "I say every topic at the usual length, Boss. If you keep saying \"in short\" or \"detail mein batao\" " +
            "after one topic's answers, I'll say that topic that way aloud. $ONLY_VOICE"
        return "Boss, aloud I say " + rs.take(SHOW).joinToString("; ") { "${it.phrase} ${it.dir.how} - ${it.say()}" } +
            (if (rs.size > SHOW) "; and ${rs.size - SHOW} more" else "") + ", in the last $WINDOW_DAYS days. $ONLY_VOICE Say \"$UNDO\" to undo it."
    }

    /** "Say every topic at the usual length". */
    fun sayReset(log: Log, now: LocalDateTime): String =
        if (learned(log, now).isEmpty()) "I already say every topic at the usual length, Boss. I'll start my count afresh from now."
        else "Done, Boss: every topic at the usual length aloud again, and my count starts afresh from now."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "${r.phrase}: ${r.dir.how}"
    fun ledgerWhy(r: Record): String = "${r.say()} in the last $WINDOW_DAYS days; only my voice changes, the chat keeps it all"
}
