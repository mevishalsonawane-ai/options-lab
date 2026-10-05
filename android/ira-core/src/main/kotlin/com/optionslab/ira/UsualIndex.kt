package com.optionslab.ira

import java.time.LocalDateTime

/**
 * Jarvis learning which index Boss means when he names none (learning round 23, 2026-10-05). A market question that
 * names no index ("what's the trend?", "levels batao") is answered for Nifty, as always. When Boss's very next words,
 * within [REACT_MINUTES] minutes, correct it to another index - "no, BankNifty", "I meant BankNifty", "nahi, banknifty
 * ka" ([correction]) - the question is asked again for that index on the spot, and only that index and the time are
 * noted: never his words, never the question.
 *
 * An index he corrected Jarvis to at least [MIN_TIMES] times in the last [WINDOW_DAYS] days (since the last reset), more
 * than all the other corrections together, is learned ([learned]): an unnamed market question ([unnamed]) is then read
 * for it ([reading]) and said so explicitly - "BankNifty, as you usually mean, Boss" ([took]). Understanding only: a
 * named index always wins; an order, a command, a trade check, an idea, the volatility, the news, the market-wide check
 * and anything about Boss's own account are never read so (they name their index or are not about one); nothing learned
 * arms, trades, switches or confirms anything. On a locked phone it is not used (his habit is not said there).
 *
 * "Which index do I usually mean?" names it ([Request.WHICH]); "use Nifty when I don't name an index" ([UNDO], [Request.RESET]) undoes
 * it and starts the count afresh. Shown in the Learnings ledger with that undo and reset by "undo everything you learned
 * this week". Pure: the app keeps the log; the readers are cached by the words alone ([Kept]).
 */
object UsualIndex {
    /** An index is learned when corrected to at least this many times... */
    const val MIN_TIMES = 3
    /** ...in the last this many days. */
    const val WINDOW_DAYS = 30L
    /** A correction counts for the unnamed question asked at most this many minutes before it. */
    const val REACT_MINUTES = 3L
    /** Notes kept at most. */
    const val KEEP = 60

    const val UNDO = "use Nifty when I don't name an index"

    /** The indices Boss may mean (never Gold or the VIX: they are not an index question's default). */
    val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    /** One correction: the index Boss said he meant ([Market.name]) and when. Never words. */
    data class Note(val at: LocalDateTime, val market: String)

    /** The corrections noted, and when Boss last asked to forget it (nothing before it counts). */
    data class Log(val notes: List<Note> = emptyList(), val resetAt: LocalDateTime? = null)

    private fun norm(text: String) = " " + spacedWords(text.lowercase().replace("'", "").replace("’", "")) + " "

    // ---- the unnamed market question ---------------------------------------------------------------------------------

    /** The topics read for an index Boss did not name: the index's own reads only. */
    private val TOPICS = setOf(Topic.OVERVIEW, Topic.WHY, Topic.TREND, Topic.LEVELS, Topic.PATTERNS)
    /** The market-wide, every index, Gold, the VIX, or Boss's own: never read for one index. */
    private val WIDE = rx(" (market|markets|indices|indexes|index|bazaar|bazar|sab|sabhi|all|every|each|both|gold|vix|my|mine|our|i|me|we|meri|mera|mere|apna|apni|position|positions|trade|trades|bot|bots|strategy|strategies) ")

    /** Is [text] a market question that names no index ("what's the trend?") - never an order or a command? */
    fun unnamed(text: String): Boolean = unnamedKept.of(text) { unnamedFresh(text) }

    private val unnamedKept = Kept<Boolean>(64)

    private fun unnamedFresh(text: String): Boolean {
        val p = runCatching { Ask.parse(text) }.getOrNull() ?: return false
        if (p.order != null || p.command != null || p.markets.isNotEmpty()) return false
        if (p.topics.isEmpty() || !p.topics.all { it in TOPICS }) return false
        if (Market.mentioned(text).isNotEmpty() || Market.mentioned(Ask.reading(text)).isNotEmpty()) return false
        return !WIDE.containsMatchIn(norm(text)) && !WIDE.containsMatchIn(norm(Ask.reading(text)))
    }

    /**
     * [text] (an [unnamed] question) read for [m]: "what's the trend on BankNifty" - or null when it does not read as the
     * same question for that index alone (then it is answered as usual). Never an order or a command.
     */
    fun reading(text: String, m: Market): String? {
        if (m !in INDICES || !unnamed(text)) return null
        val before = Ask.parse(text).topics
        val bare = text.trim().trimEnd('?', '.', '!', ' ')
        return listOf("$bare on ${m.label}", "${m.label} $bare").firstOrNull { s ->
            val p = runCatching { Ask.parse(s) }.getOrNull()
            p != null && p.order == null && p.command == null && p.markets == listOf(m) && p.topics == before
        }
    }

    // ---- Boss's correction ---------------------------------------------------------------------------------------------

    private const val C_LEAD = "^ (?:oh |arre |arey |are |sorry |jarvis |boss )*"
    private const val C_NO = "(?:no|nope|nah|nahi|nahin|na|not that(?: one)?|wrong (?:one|index)|galat)"
    private const val C_MEANT = "(?:i meant|i mean|i was asking about|i asked about|i was asking for|i asked for|i was talking about|i wanted|mera matlab|matlab)"
    /** The only capturing group: the index's name. */
    private const val C_IDX = "([a-z0-9 ]+?)"
    private const val C_TAIL = "(?: (?:ka|ki|ke|wala|wali|vala|se|tha|thi|hai|please|boss|jarvis|yaar|bhai|one|i meant|batao|bolo|bata do|bol do|ki baat|poocha|pucha|puchha|kar raha|kar rha))* $"
    private val CORRECTED = listOf(
        rx(C_LEAD + C_NO + "(?: i meant| i mean| mera matlab| matlab)?(?: about| for)? " + C_IDX + C_TAIL),
        rx(C_LEAD + C_MEANT + "(?: about| for)? " + C_IDX + C_TAIL),
        rx(C_LEAD + "(?:no |nahi |nahin )?not [a-z0-9]+(?: [a-z0-9]+)? (?:but |i meant |i mean )?" + C_IDX + C_TAIL),
        rx(C_LEAD + C_IDX + " (?:ka|ki|ke) (?:bolo|batao|bata do|bol do|poocha|pucha|puchha|baat)(?: tha| thi| kar raha tha| kar rha tha)?(?: boss| jarvis| yaar)* $"))

    /**
     * The index Boss says he meant in [text] ("no, BankNifty", "I meant bank nifty", "nahi banknifty ka"), or null: a bare
     * correction only - the index's own name and nothing more, never a question about it, an order or a command.
     */
    fun correction(text: String): Market? = correctionKept.of(text) { correctionFresh(text) }

    private val correctionKept = Kept<Market?>(64)

    private fun correctionFresh(text: String): Market? {
        val t = norm(text)
        val m = CORRECTED.firstNotNullOfOrNull { r ->
            r.find(t)?.let { found -> index(found.groupValues[1]) }
        } ?: return null
        val p = runCatching { Ask.parse(text) }.getOrNull() ?: return null
        if (p.order != null || p.command != null) return null
        return m
    }

    /** The index [words] are exactly a name of, or null. */
    private fun index(words: String): Market? {
        val w = words.trim()
        if (w.isEmpty()) return null
        return INDICES.firstOrNull { m -> w == m.name.lowercase() || w in m.aliases }
    }

    /** Is a correction at [now] about the unnamed question asked at [askedAt]? */
    fun fresh(askedAt: LocalDateTime, now: LocalDateTime): Boolean =
        !now.isBefore(askedAt) && !now.isAfter(askedAt.plusMinutes(REACT_MINUTES))

    /** [log] with Boss's correction to [m] at [at] noted; older than the window (and past [KEEP]) dropped. */
    fun heard(log: Log, m: Market, at: LocalDateTime): Log {
        val from = at.minusDays(WINDOW_DAYS)
        return log.copy(notes = (log.notes.filter { it.at.isAfter(from) } + Note(at, m.name)).sortedBy { it.at }.takeLast(KEEP))
    }

    /** "Use Nifty when I don't name an index": nothing before [now] counts any more. */
    fun reset(log: Log, now: LocalDateTime): Log = Log(emptyList(), now)

    /** The index learned: how many times Boss corrected Jarvis to it, to any other, and the newest. */
    data class Record(val market: Market, val times: Int, val others: Int, val newest: LocalDateTime) {
        val phrase: String get() = market.label
        fun say(): String = "you said you meant ${market.label} $times time${if (times == 1) "" else "s"}" +
            (if (others > 0) " (another index $others)" else "")
    }

    /**
     * The index learned at [now], or null: one corrected to at least [MIN_TIMES] times, more than all the others together,
     * and not Nifty (already what an unnamed question is read for).
     */
    fun learned(log: Log, now: LocalDateTime): Record? {
        val from = now.minusDays(WINDOW_DAYS)
        val reset = log.resetAt
        val xs = log.notes.filter { it.at.isAfter(from) && !it.at.isAfter(now) && (reset == null || it.at.isAfter(reset)) }
        if (xs.isEmpty()) return null
        val (key, mine) = xs.groupBy { it.market }.entries.maxWithOrNull(compareBy<Map.Entry<String, List<Note>>> { it.value.size }.thenBy { e -> e.value.maxOf { it.at } })
            ?.let { it.key to it.value } ?: return null
        val m = INDICES.firstOrNull { it.name == key } ?: return null
        val others = xs.size - mine.size
        if (m == Market.NIFTY || mine.size < MIN_TIMES || mine.size <= others) return null
        return Record(m, mine.size, others, mine.maxOf { it.at })
    }

    // ---- what is said ----------------------------------------------------------------------------------------------

    /** Beside an unnamed question read for the learned index (after "I took that as ..."). */
    fun took(r: Record): String = "${r.phrase}, as you usually mean, Boss - say \"no, Nifty\" if not."

    /** Beside the question asked again for Boss's correction to [m] ([learnedNow]: this correction just made it learned). */
    fun corrected(m: Market, learnedNow: Record?): String =
        "${m.label} then, Boss." + (learnedNow?.let { r ->
            " You've meant ${r.phrase} ${r.times} times lately when you named no index - from now on I'll take ${r.phrase} then, and say so each time; " +
                "a named index always wins. Say \"$UNDO\" to undo it."
        } ?: "")

    const val LOCKED = "Unlock the phone for that, Boss."
    const val ONLY_UNDERSTANDING = "It only changes how I read your question: a named index always wins, I say so each time, and nothing I learn acts."

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| any ?more| from now on| again| for me| by default)* $"
    private const val NAMED = "(when i (dont|do not) (name|say|mention) (one|an index|the index|any index)|by default|if i (dont|do not) (say|name) (one|it|which))"

    private val WHICH = rx(LEAD + "(which|what) index do (i|you think i) (usually |normally |mostly )?(mean|want|ask about|ask for)( $NAMED)?" + TAIL + "|" +
        LEAD + "(which|what) index do you (assume|take|use|pick|go with|answer for)( $NAMED)?" + TAIL + "|" +
        LEAD + "(whats|what is) my (usual|default|normal) index" + TAIL + "|" +
        LEAD + "do you know (which|what) index i (usually |normally |mostly )?mean" + TAIL + "|" +
        LEAD + "(mera|meri) (usual|default) index (kaunsa|kaun sa|kya|kon sa|konsa) (hai|he)" + TAIL + "|" +
        LEAD + "(main|mai) (kaunsa|kaun sa|konsa|kon sa) index (usually |mostly )?(poochta|puchta|puchhta) (hoon|hu|hun)" + TAIL)
    private val RESET = rx(LEAD + "(reset|clear|unlearn|drop) (my|the) (usual|default) index" + TAIL + "|" +
        LEAD + "(dont|do not) assume (my|an|the|which) index" + TAIL + "|" +
        LEAD + "(go back to|use) nifty when i (dont|do not) (name|say|mention) (one|an index|the index|any index)" + TAIL + "|" +
        LEAD + "(mera|meri) (usual|default) index (bhool|bhul) (jao|jaao|ja)" + TAIL)

    /** "Which index do I usually mean?" or "use Nifty when I don't name an index", else null. */
    fun asked(text: String): Request? = askedKept.of(text) { askedFresh(text) }

    private val askedKept = Kept<Request?>(64)

    private fun askedFresh(text: String): Request? {
        val t = norm(text)
        return when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }

    /** "Which index do I usually mean?". */
    fun say(log: Log, now: LocalDateTime): String {
        val r = learned(log, now) ?: return "When you name no index I take Nifty, Boss. If you keep correcting me to another one - " +
            "\"no, BankNifty\" - $MIN_TIMES times, I'll take that one then and say so each time. $ONLY_UNDERSTANDING"
        return "Boss, when you name no index I take ${r.phrase}, as you usually mean - ${r.say()} in the last $WINDOW_DAYS days. " +
            "$ONLY_UNDERSTANDING Say \"$UNDO\" to undo it."
    }

    /** "Use Nifty when I don't name an index". */
    fun sayReset(log: Log, now: LocalDateTime): String =
        if (learned(log, now) == null) "I already take Nifty when you name no index, Boss. I'll start my count afresh from now."
        else "Done, Boss: Nifty again when you name no index, and my count starts afresh from now."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "a market question naming no index: read as ${r.phrase}, said so each time"
    fun ledgerWhy(r: Record): String = "${r.say()} in the last $WINDOW_DAYS days; a named index always wins - understanding only"
}
