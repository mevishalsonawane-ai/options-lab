package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * Jarvis learning where Boss's small trades come from (learning round 29, 2026-10-05): a "small" trade is one whose move
 * before charges was less than twice its own charges ([Charges.small], [Charges.SMALL_RATIO]). From the closed trades of each
 * book (Paper, Zerodha) in the last [WINDOW_DAYS] days (since the last "forget", [Log.resetAt]) - trades that paid charges
 * only - he counts them by who placed them ([Charges.Trip.owner]: Manual, ORB, a Pine arm, Jarvis...) and by the time of day
 * they were opened ([Band]). A source or a time of day with at least [MIN_TRADES] such trades, closed on at least [MIN_DAYS]
 * days, of which at least [MIN_SMALL] and at least [SHARE] were small, is learned ([learned]).
 *
 * What it changes: the 15:45 wrap-up says ONE such fact, once ([wrapLine]: the clearest not said before, [Log.told]) - "Over
 * the last 10 days, 18 of ORB's 40 Paper trades moved less than twice their own charges, on 7 days." A fact from his own
 * record only: never advice to stop, change or size a strategy, never a judgement, and nothing learned acts - it never
 * arms, stops, trades or changes anything. Never on a locked phone (the app adds no line then, and nothing is kept as
 * told), never in IraGoldAlgo, never in the wrap-up Boss asks for in the day.
 *
 * "What have you learned about my charges?" / "which trades move less than twice their charges?" names the facts
 * ([Request.WHICH]; his record, so on an unlocked phone only); "stop mentioning my small trades" ([UNDO], [Request.RESET])
 * forgets it: nothing before then counts, so it takes a fresh [MIN_TRADES] trades before anything is learned again. Shown in
 * the Learnings ledger with that undo. It is a fact of his record, not a habit learned from what he says, so "undo everything
 * you learned this week" leaves it (as it leaves his other records). Pure: the app keeps the log and passes the trades.
 */
object SmallTrades {
    /** Only the trades closed in the last this many days (today among them) count. */
    const val WINDOW_DAYS = 10L
    /** A source or time of day needs at least this many closed trades that paid charges... */
    const val MIN_TRADES = 20
    /** ...closed on at least this many days... */
    const val MIN_DAYS = 5
    /** ...at least this many of them small... */
    const val MIN_SMALL = 8
    /** ...and at least this share of them small. */
    const val SHARE = 0.4
    /** Facts named at most. */
    const val SHOW = 3

    const val UNDO = "stop mentioning my small trades"
    const val LOCKED = "Unlock the phone for that, Boss."
    /** The undo on a locked phone: done all the same, in words that never name what was learned (or whether). */
    const val RESET_LOCKED = "Done, Boss: I've set aside what I noted about your trades and their charges; my count starts afresh from now."

    /** What a fact is cut by. */
    enum class By { SOURCE, TIME }

    /** The time of day a trade was opened (IST). */
    enum class Band(val label: String, val until: LocalTime) {
        OPEN("the first 45 minutes (9:15 to 10:00)", LocalTime.of(10, 0)),
        MORNING("late morning (10:00 to 12:00)", LocalTime.of(12, 0)),
        MIDDAY("midday (12:00 to 14:00)", LocalTime.of(14, 0)),
        LATE("the last stretch (14:00 to the close)", LocalTime.MAX);

        companion object {
            fun of(t: LocalTime): Band = entries.first { t.isBefore(it.until) || it == LATE }
        }
    }

    /** One book's closed trades: [label] "Paper" or "Zerodha". */
    data class Book(val label: String, val trips: List<Charges.Trip>)

    /**
     * When Boss last asked to forget it (nothing closed at or before it counts) and the facts already said in a wrap-up
     * ([Record.key]), each said once.
     */
    data class Log(val resetAt: LocalDateTime? = null, val told: Set<String> = emptySet())

    /** A learned fact: [small] of the [trades] of [book] by [by] = [name] were small, closed on [days] days, the newest on [newest]. */
    data class Record(val book: String, val by: By, val name: String, val trades: Int, val small: Int, val days: Int, val newest: LocalDate) {
        val key: String get() = "$book|$by|$name"
        val share: Double get() = small.toDouble() / trades
        /** "ORB's 40 Paper trades", "your 40 manual Paper trades", "the 40 Paper trades opened in midday (12:00 to 14:00)". */
        val whose: String get() = when {
            by == By.TIME -> "the $trades $book trades opened in ${Band.valueOf(name).label}"
            name == "Manual" -> "your $trades manual $book trades"
            else -> "$name's $trades $book trades"
        }
        /** "18 of ORB's 40 Paper trades moved less than twice their own charges, on 7 days". */
        fun fact(): String = "$small of $whose moved less than twice their own charges, on $days days"
        fun say(): String = "over the last $WINDOW_DAYS days, ${fact()}"
    }

    /** The first moment that counts at [now]: the window's first day, or just after the last forget. */
    private fun from(log: Log, now: LocalDateTime): LocalDateTime {
        val day = now.toLocalDate().minusDays(WINDOW_DAYS - 1).atStartOfDay().minusNanos(1)
        return log.resetAt?.takeIf { it.isAfter(day) } ?: day
    }

    /** The facts learned at [now] from [books] (since [log]'s last forget), the clearest first. */
    fun learned(books: List<Book>, log: Log, now: LocalDateTime): List<Record> {
        val from = from(log, now)
        val out = ArrayList<Record>()
        for (b in books) {
            val w = b.trips.filter { it.charges > 0 && it.closedAt.isAfter(from) && !it.closedAt.isAfter(now) }
            if (w.size < MIN_TRADES) continue
            fun cut(by: By, groups: Map<String, List<Charges.Trip>>) = groups.forEach { (name, ts) ->
                val small = ts.count { Charges.small(it) }
                val days = ts.map { it.closedAt.toLocalDate() }.distinct().size
                if (ts.size >= MIN_TRADES && days >= MIN_DAYS && small >= MIN_SMALL && small >= SHARE * ts.size)
                    out += Record(b.label, by, name, ts.size, small, days, ts.maxOf { it.closedAt }.toLocalDate())
            }
            cut(By.SOURCE, w.groupBy { it.owner.ifBlank { "Manual" } })
            cut(By.TIME, w.groupBy { Band.of(it.openedAt.toLocalTime()).name })
        }
        return out.sortedWith(compareByDescending<Record> { it.share }.thenByDescending { it.small }.thenBy { it.key })
    }

    /** The one fact for today's 15:45 wrap-up: the clearest not said before, or null. The app keeps its [Record.key] as told. */
    fun next(learned: List<Record>, log: Log): Record? = learned.firstOrNull { it.key !in log.told }

    /** The wrap-up's sentence for [r]: a fact only. */
    fun wrapLine(r: Record): String =
        "From your own record, Boss: ${r.say()}. A fact only, I change nothing - say \"$UNDO\" and I'll forget it."

    /** [log] with [r] said in a wrap-up. */
    fun told(log: Log, r: Record): Log = log.copy(told = log.told + r.key)

    /** "Stop mentioning my small trades": nothing closed before [now] counts any more, and nothing is kept as told. */
    fun reset(now: LocalDateTime): Log = Log(now, emptySet())

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { WHICH, RESET }

    private fun norm(text: String) = Spaced.joined(text)

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| any ?more| from now on| again| for me| so far| lately| recently)* $"
    private const val SMALL = "(small|tiny|little|chhote|chote) trades?"
    private const val CHG = "(charges|brokerage|trading charges|trading costs)"
    private const val LEARN = "(learn|learned|learnt|notice|noticed|found|find|figured out|seen|see)"

    private val WHICH = rx(LEAD + "what (have|did|do) you $LEARN (about|from|in) (my|our) ($CHG|$SMALL)( pattern| habits?| record)?" + TAIL + "|" +
        LEAD + "(which|what) (of my |my )?(trades|sources|strategies|arms|bots|times( of day)?|time of day) (make|makes|made|give|gives|gave|produce|produces|have|has|had|place|places|placed) (the most |most |so many |many )?(of my |my )?$SMALL" + TAIL + "|" +
        LEAD + "(which|what) (of my |my )?trades (move|moved|moves) less than (twice|two times|2 times|double) (their|its|the|my) (own )?$CHG" + TAIL + "|" +
        LEAD + "(where|when) do (my |the most )?$SMALL come from" + TAIL + "|" +
        LEAD + "who (makes|made|places|placed|takes|took) (the most |most |my |so many )?$SMALL" + TAIL + "|" +
        LEAD + "(whats |what is |show me |tell me about )?(my )?$SMALL (record|pattern|habit)" + TAIL + "|" +
        // Understanding round 29: his small trades named alone ("my small trades", "show my tiny trades", "mere chhote trades dikhao").
        LEAD + "(show me |show |list |what about |how about |and )?(my|mere|meri|our) $SMALL( dikhao| batao| dikha do| bata do)?" + TAIL + "|" +
        LEAD + "(how are|how re|hows|how s|how is) (my|our) $SMALL( doing| looking| going)?" + TAIL + "|" +
        "^ (jarvis )?(mere |meri |hamare )?(charges|brokerage|chhote trades|chote trades) ke baare (mein|me|main) (tumne )?kya (seekha|sikha|pata chala|dekha)" + TAIL + "|" +
        "^ (jarvis )?(chhote|chote) trades (kaun|kon|kis|kiske|kahan se|kab) (karta|banata|lagata|aate|hote|wala)( hai| hain)?" + TAIL + "|" +
        // Understanding round 28: one source named ("which bot makes tiny trades", "which strategy is making small trades",
        // "who is making tiny trades"), the Hinglish with the English words ("small trades kaun karta hai", "kaun sa bot chhote
        // trades karta hai") and the trades by their charges ("which trades are too small for their charges", "trades that
        // don't cover their charges").
        LEAD + "(which|what) (of my |my )?(trade|source|strategy|arm|bot|algo|algos|time slot|band) (makes|made|gives|gave|produces|places|placed|takes|took|has|had|is making|is placing|was making) (the most |most |so many |many )?(of my |my )?$SMALL" + TAIL + "|" +
        LEAD + "(which|what) (of my |my )?(trades|sources|strategies|arms|bots|algos) (are|were) (making|placing|giving|taking|producing) (the most |most |so many |many )?(of my |my )?$SMALL" + TAIL + "|" +
        LEAD + "who (is|was|keeps) (making|placing|taking) (the most |most |my |so many |these |all these |all the )?$SMALL" + TAIL + "|" +
        "^ (jarvis )?(kaun sa|kaunsa|konsa|kon sa|kaun si|kaunsi|konsi) (bot|algo|strategy|arm|time|source) (sabse zyada |zyada )?(chhote|chote|small|tiny) trades (karta|karti|banata|banati|lagata|lagati|leta|leti)( hai| hain)?" + TAIL + "|" +
        "^ (jarvis )?(mere )?(small|tiny) trades (kaun|kon|kis|kiske|kahan se|kab) (karta|banata|lagata|aate|hote|wala)( hai| hain)?" + TAIL + "|" +
        LEAD + "(which|what) (of my |my )?trades (are|were) too (small|tiny) (for|to cover) (their |its |the |my )?(own )?$CHG" + TAIL + "|" +
        LEAD + "(which |what )?(of my |my )?trades (that |which )?(dont|do not|didnt|did not|never) (cover|pay for|beat|clear) (their |its |the |my )?(own )?$CHG" + TAIL)

    /**
     * Only a clear undo naming his small trades or what was learned about his charges: "stop mentioning my small trades",
     * "don't tell me about my small trades", "forget what you learned about my charges", "reset my small trades". Never "forget
     * my ..." alone (one of Boss's own notes, [AboutBoss.forgetAsked]), never a stop of an arm ([Commands]' habit undo).
     */
    private val RESET = rx(LEAD + "stop (mentioning|telling|naming|listing|saying) (me )?(about )?(my |the )$SMALL( record| pattern| habit| fact)?" + TAIL + "|" +
        LEAD + "(dont|do not|no need to) (mention|tell|name) (me )?(about )?(my |the )$SMALL( record| pattern| habit| fact)?" + TAIL + "|" +
        LEAD + "(forget|unlearn|reset|clear) (what|everything) you (have )?(learned|learnt|know|noticed|found) about (my|our) ($CHG|$SMALL)" + TAIL + "|" +
        LEAD + "(unlearn|reset|clear) (my |the )$SMALL( record| pattern| habit| count)?" + TAIL + "|" +
        "^ (jarvis )?(mere )?(chhote|chote) trades (wali baat |ki baat )?(mat batao|mat bolo|bhool jao|bhul jao)" + TAIL)

    /** "What have you learned about my charges?" or "stop mentioning my small trades", else null. */
    fun asked(text: String): Request? = runCatching {
        val t = norm(text)
        when {
            RESET.containsMatchIn(t) -> Request.RESET
            WHICH.containsMatchIn(t) -> Request.WHICH
            else -> null
        }
    }.getOrNull()

    const val ONLY_FACTS = "A fact from your own record only: I never stop, change or size a strategy for it, and nothing I learn acts."

    private fun named(rs: List<Record>): String = rs.take(SHOW).map { it.fact() }.let {
        (if (it.size <= 1) it.joinToString("") else it.dropLast(1).joinToString("; ") + "; and " + it.last()) +
            (if (rs.size > SHOW) "; and ${rs.size - SHOW} more" else "")
    }

    /** "What have you learned about my charges?". */
    fun say(rs: List<Record>): String =
        if (rs.isEmpty()) "Nothing yet, Boss. When one source or one time of day has at least $MIN_TRADES trades on $MIN_DAYS days in the last " +
            "$WINDOW_DAYS days, and at least $MIN_SMALL of them - ${pct(SHARE)} or more - moved less than twice their own charges, I'll say it once in the 15:45 wrap-up. $ONLY_FACTS"
        else "Boss, over the last $WINDOW_DAYS days: ${named(rs)}. $ONLY_FACTS Say \"$UNDO\" to have me forget it."

    /** "Stop mentioning my small trades". */
    fun sayReset(rs: List<Record>): String =
        if (rs.isEmpty()) "I hadn't noted anything about your small trades yet, Boss. My count starts afresh from now."
        else "Done, Boss: I've forgotten what I noted about your small trades, and my count starts afresh from now."

    /** Noted in the app's activity when a fact is said in the wrap-up (no amounts). */
    fun toldNote(r: Record): String = "Said once in the wrap-up: ${r.fact()} (undo: \"$UNDO\")."

    /** The ledger's lines for [r]. */
    fun ledgerWhat(r: Record): String = r.fact().replaceFirstChar { it.uppercase() }
    fun ledgerWhy(r: Record): String = "from your trades closed in the last $WINDOW_DAYS days; said once in the 15:45 wrap-up, a fact only - nothing acts"

    private fun pct(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)
}
