package com.optionslab.ira

import java.time.LocalDateTime

/**
 * Jarvis learning which conditional instructions Boss keeps trying to give him (learning round 32, 2026-10-06): "if Nifty
 * falls below 24000 exit all", "agar loss 5000 ho jaye to sab band kar do", "if my call falls to 80 sell it". Jarvis can't
 * set an action to wait for a condition ([Conditional]: never a command, an order or a yes - [Conditional.SAY] answers it,
 * nothing is done). Each time it is said, only its kind ([Need]: an index level, his own loss or profit, a position's own
 * price) and the minute are kept ([noted]) - never his words, a level or an amount.
 *
 * Learned ([learned]): a kind he asked for at least [MIN_TIMES] times, on at least [MIN_DAYS] days, in the last
 * [WINDOW_DAYS] days (since the last undo, [Log.resetAt]).
 *
 * What it changes: the 15:35 wrap-up says ONE such kind, once ([next], [wrapLine]: the most asked not said before,
 * [Log.told]) - how often he asked, and that the app's own tool covers that need, naming its screen: a price alarm (More,
 * then Alerts) for an index level; a P&L alert (More, then Alerts) and the daily loss limit (More, then Bot settings) for
 * his own loss or profit; Protect - stop, trail, target - on the position itself (the Trade tab, tap the position)
 * for a position's price. A fact and a pointer only: nothing learned sets an alarm, a stop, a limit or anything else, and
 * nothing acts or places anything. Never on a locked phone (the app adds no line then, and nothing is kept as told),
 * never in IraGoldAlgo, never in the wrap-up Boss asks for in the day, and on a day the wrap-up says a [SmallTrades] fact it
 * waits for the next (one learned line a wrap-up); Jarvis only.
 *
 * "What have you learned about my conditional orders?" names the kinds ([Request.WHICH]; his own words' record, so on an
 * unlocked phone only); "stop mentioning my conditional orders" ([UNDO], [Request.RESET]) forgets it and counts afresh
 * from then - a habit's undo, never a STOP of an arm ([Commands]' habit undo). Shown in the Learnings ledger with that undo,
 * and reset by "undo everything you learned this week". Pure: the app keeps the log.
 */
object CondNeeds {
    /** Only the last this many days count (today among them). */
    const val WINDOW_DAYS = 20L
    /** A kind asked at least this many times... */
    const val MIN_TIMES = 3
    /** ...on at least this many different days. */
    const val MIN_DAYS = 3
    /** At most this many asks are kept (the oldest dropped first). */
    const val KEEP = 120

    const val UNDO = "stop mentioning my conditional orders"
    const val LOCKED = "Unlock the phone for that, Boss."
    /** The undo on a locked phone: done all the same, in words that never name what was learned (or whether). */
    const val RESET_LOCKED = "Done, Boss: I've set aside what I noted about the instructions you give me, and my count starts afresh from now."

    /** What a conditional instruction waited on, and the app's own tool for that need (with its screen). */
    enum class Need(val asked: String, val tool: String) {
        LEVEL("to act when an index reaches a level",
            "the app's own price alarm covers that - More, then Alerts (or say \"alert me when Nifty goes below 24000\") - it rings at your level and you decide"),
        PNL("to act when your loss or profit reaches an amount",
            "the app's own P&L alert (More, then Alerts) and daily loss limit (More, then Bot settings) cover that"),
        POSITION("to act when a position's own price gets somewhere",
            "the app's own stop loss covers that - the Trade tab, tap the position, then Protect: stop, trail, target - and the app watches it itself");
    }

    /** One conditional instruction: its kind and minute only. */
    data class Seen(val need: Need, val at: LocalDateTime)

    /** The asks kept, when Boss last asked to forget it, and the kinds already said in a wrap-up ([Need.name]). */
    data class Log(val seen: List<Seen> = emptyList(), val resetAt: LocalDateTime? = null, val told: Set<String> = emptySet())

    /** A kind learned: asked [times] times on [days] days, the newest on [newest]. */
    data class Record(val need: Need, val times: Int, val days: Int, val newest: java.time.LocalDate) {
        val key: String get() = need.name
        /** "4 times on 3 days you asked me to act when an index reaches a level". */
        fun fact(): String = "$times times on $days days you asked me ${need.asked}"
    }

    private val PNL_WORDS = rx(" (?:loss|losses|lose|losing|lost|profit|profits|mtm|m2m|pnl|p l|p and l|drawdown|nuksan|nuksaan|munafa|fayda|faida) ")

    /** The kind of a conditional instruction, or null when [text] is not one ([Conditional.asked]). */
    fun need(text: String): Need? {
        if (!runCatching { Conditional.asked(text) }.getOrDefault(false)) return null
        val t = Spaced.joined(text)
        return when {
            PNL_WORDS.containsMatchIn(t) -> Need.PNL
            Market.mentioned(text).isNotEmpty() -> Need.LEVEL
            else -> Need.POSITION
        }
    }

    /** [log] with [text] kept as said at [now] when it is a conditional instruction (its kind only); else [log] as it was. */
    fun noted(log: Log, text: String, now: LocalDateTime): Log {
        val n = need(text) ?: return log
        val from = now.minusDays(WINDOW_DAYS)
        return log.copy(seen = (log.seen.filter { it.at.isAfter(from) } + Seen(n, now)).takeLast(KEEP))
    }

    /** The kinds learned at [now] (since [log]'s last forget), the most asked first. */
    fun learned(log: Log, now: LocalDateTime): List<Record> {
        val day = now.toLocalDate().minusDays(WINDOW_DAYS - 1).atStartOfDay().minusNanos(1)
        val from = log.resetAt?.takeIf { it.isAfter(day) } ?: day
        return log.seen.filter { it.at.isAfter(from) && !it.at.isAfter(now) }.groupBy { it.need }.mapNotNull { (n, ss) ->
            val days = ss.map { it.at.toLocalDate() }.distinct().size
            if (ss.size >= MIN_TIMES && days >= MIN_DAYS) Record(n, ss.size, days, ss.maxOf { it.at }.toLocalDate()) else null
        }.sortedWith(compareByDescending<Record> { it.days }.thenByDescending { it.times }.thenBy { it.need.ordinal })
    }

    /** The one kind for today's 15:35 wrap-up: the most asked not said before, or null. The app keeps its [Record.key] as told. */
    fun next(learned: List<Record>, log: Log): Record? = learned.firstOrNull { it.key !in log.told }

    /** The wrap-up's sentence for [r]: a fact and a pointer only. */
    fun wrapLine(r: Record): String =
        "One thing from what you've asked me, Boss: in the last $WINDOW_DAYS days, ${r.fact()}, which I can't set - ${r.need.tool}. " +
            "A pointer only, I set nothing - say \"$UNDO\" and I'll forget it."

    /** [log] with [r] said in a wrap-up. */
    fun told(log: Log, r: Record): Log = log.copy(told = log.told + r.key)

    /** "Stop mentioning my conditional orders": nothing said before [now] counts any more, nothing is kept as told. */
    fun reset(now: LocalDateTime): Log = Log(emptyList(), now, emptySet())

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| any ?more| from now on| again| for me| so far| lately| recently)* $"
    private const val COND = "conditional (orders?|instructions?|exits?|commands?|requests?|asks?)"
    private const val LEARN = "(learn|learned|learnt|notice|noticed|found|figured out|seen|see)"

    private val WHICH = rx(LEAD + "what (have|did|do) you $LEARN (about|from|in) (my|our) $COND" + TAIL + "|" +
        LEAD + "(what|which) $COND (have|do|did) i (keep |usually |mostly )?(ask|asking|asked|give|giving|gave|try|trying|tried)( to give)?( you)?( for)?" + TAIL + "|" +
        LEAD + "(show me |show |list |what about |how about )?(my|mere|meri) $COND( dikhao| batao)?" + TAIL + "|" +
        LEAD + "(how often|how many times) (do|did|have) i (ask|asked|give|given|gave|try|tried)( you)? $COND" + TAIL + "|" +
        "^ (jarvis )?(mere |meri )?$COND ke baare (mein|me|main) (tumne )?kya (seekha|sikha|pata chala|dekha)" + TAIL)

    /**
     * Only a clear undo naming his conditional orders: "stop mentioning my conditional orders", "don't tell me about my
     * conditional orders", "forget what you learned about my conditional orders". Never "forget my ..." alone (one of Boss's
     * own notes, [AboutBoss.forgetAsked]), never a stop of an arm ([Commands]' habit undo).
     */
    private val RESET = rx(LEAD + "stop (mentioning|telling me about|telling|naming|listing|pointing out|saying) (me )?(about )?(my |the )$COND" + TAIL + "|" +
        LEAD + "(dont|do not|no need to) (mention|tell me about|tell|name|point out) (me )?(about )?(my |the )$COND" + TAIL + "|" +
        LEAD + "(forget|unlearn|reset|clear) (what|everything) you (have )?(learned|learnt|know|noticed|found) about (my|our) $COND" + TAIL + "|" +
        LEAD + "(unlearn|reset|clear) (my |the )$COND( record| pattern| habit| count)?" + TAIL + "|" +
        "^ (jarvis )?(mere |meri )?$COND (wali baat |ki baat )?(mat batao|mat bolo|bhool jao|bhul jao)" + TAIL)

    /** "What have you learned about my conditional orders?" or "stop mentioning my conditional orders", else null. */
    fun asked(text: String): Request? = askedKept.of(text) { askedFresh(text) }

    private val askedKept = Kept<Request?>(64)

    /** Every kept reading forgotten (tests). */
    internal fun forgetAsked() = askedKept.clear()

    private fun askedFresh(text: String): Request? = runCatching {
        val t = Spaced.joined(text)
        when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }.getOrNull()

    const val ONLY_POINTER = "A fact and a pointer only: I never set an alarm, a stop or a limit for you, and nothing I learn acts or places anything."

    /** "What have you learned about my conditional orders?". */
    fun say(rs: List<Record>): String =
        if (rs.isEmpty()) "Nothing yet, Boss. When you ask me to act on a condition - like \"if Nifty falls below 24000, exit\" - " +
            "$MIN_TIMES times or more on $MIN_DAYS different days in the last $WINDOW_DAYS days, I'll say once in the 15:35 wrap-up which of the app's own tools covers it. $ONLY_POINTER"
        else "Boss, in the last $WINDOW_DAYS days: " + rs.joinToString("; ") { it.fact() + " - " + it.need.tool } + ". $ONLY_POINTER Say \"$UNDO\" to have me forget it."

    /** "Stop mentioning my conditional orders". */
    fun sayReset(rs: List<Record>): String =
        if (rs.isEmpty()) "I hadn't noted a pattern in your conditional orders yet, Boss. My count starts afresh from now."
        else "Done, Boss: I've forgotten what I noted about your conditional orders, and my count starts afresh from now."

    /** Noted in the app's activity when a kind is said in the wrap-up (no words, no levels). */
    fun toldNote(r: Record): String = "Said once in the wrap-up: ${r.fact()}, with the app's own tool for it (undo: \"$UNDO\")."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = "You often ask me ${r.need.asked}, which I can't set"
    fun ledgerWhy(r: Record): String = "${r.times} times on ${r.days} days in the last $WINDOW_DAYS days; the app's own tool named once in the 15:35 wrap-up - a pointer only, nothing set"
}
