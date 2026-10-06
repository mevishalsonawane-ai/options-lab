package com.optionslab.ira

import com.optionslab.engine.orb.SoloMidday

/**
 * Forward-test watch (Boss, 06 Oct 2026): Jarvis tells Boss when a paper arm's live-vs-backtest verdict ([ForwardCheck])
 * changes, so drift is caught early. The arms watched: Liquidity 15+5 ([ForwardCheck.LIQUIDITY]), Solo (midday)
 * ([ForwardCheck.SOLO]) and Hero ([ForwardCheck.HERO]).
 *
 *   category   the verdict as told: too few trades, in line, above, below the band, a sustained run below (the CUSUM's
 *              alarm, [ForwardCheck.Result.alarm]) or - for a lottery (Hero) - a drawdown deeper than the backtest's worst
 *              ([category])
 *   told       the category last told for an arm, and the last milestone told ([Told]; kept by the app per arm under
 *              [KEY_PREFIX])
 *   note       one chat note when the category changes from the last told one ("in line" -> "below expectation", the
 *              CUSUM's alarm first raised, back to "in line") or a milestone is reached: 20 trades (enough to judge), and
 *              for Solo's forward test 40 and 60 ([decide]); never for "too few trades" by itself (a first look, or a record
 *              that shrank, is kept silently)
 *
 * Each note says the arm, its trades, its net a trade (per lot; Hero per ₹5,000 ticket) against the backtest's, the band,
 * what changed and what it means in plain words - and for Solo how far it is from its own −₹25,000 switch-off line
 * ([SoloBar], [SoloMidday.FORWARD_MAX_DRAWDOWN]). Words only: nothing here switches an arm, changes its lots or its rules
 * (Solo's own switch-off stays Solo's own). Asked: "is anything drifting", "how are my arms vs backtest", "forward test
 * status" ([asked]) - one line an arm ([summary], the card's own [ForwardCheck.line]). Pure: no clock, no storage.
 */
object ForwardWatch {
    /** The arms watched, in the order told and summed up. */
    val ARMS: List<ForwardCheck.Expectation> = listOf(ForwardCheck.LIQUIDITY, ForwardCheck.SOLO, ForwardCheck.HERO)

    /**
     * Where the app keeps each arm's last told category ("jarvis.forward.told.<arm>"): not a secret and nothing that
     * trades - carried in a backup ([Upkeep.carried]) with the arms' own records it was read from, so a restored phone
     * does not tell the same change again.
     */
    const val KEY_PREFIX = "jarvis.forward.told."

    /** The key an arm's last told category is kept under. */
    fun key(e: ForwardCheck.Expectation): String = KEY_PREFIX + e.key

    /** The milestones told for every arm: enough trades to judge. */
    val MILESTONES: List<Int> = listOf(ForwardCheck.MIN_TRADES)

    /** Solo's: enough to judge, then 40 and its forward test's 60 ([SoloMidday.FORWARD_TRADES]). */
    val SOLO_MILESTONES: List<Int> = listOf(ForwardCheck.MIN_TRADES, 40, SoloMidday.FORWARD_TRADES)

    /** The verdict as told. */
    enum class Category(val words: String) {
        TOO_FEW("too few trades to judge"),
        IN_LINE("in line with the backtest"),
        ABOVE("above expectation"),
        BELOW("below expectation"),
        RUN_BELOW("a sustained run below the backtest"),
        DEEPER("a drawdown deeper than the backtest's worst"),
    }

    /** What was last told for an arm: its [category] and the highest [milestone] told (0: none). */
    data class Told(val category: Category, val milestone: Int = 0) {
        /** As kept: "IN_LINE|20". */
        fun encode(): String = "${category.name}|$milestone"

        companion object {
            /** A kept [encode]d value, or null (none, or not one this build reads). */
            fun decode(s: String?): Told? {
                if (s.isNullOrBlank()) return null
                val c = Category.entries.firstOrNull { it.name == s.substringBefore('|') } ?: return null
                val m = s.substringAfter('|', "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
                return Told(c, m)
            }
        }
    }

    /**
     * Solo's own switch-off line: [drawdown] (0 or negative) is how far below its best it is now, counted from its
     * baseline (the last time Boss switched it back on); [room] how far that is from [bar] (Solo switches itself off past
     * it - its own rule, untouched here).
     */
    data class SoloBar(val drawdown: Double, val bar: Double = SoloMidday.FORWARD_MAX_DRAWDOWN) {
        val room: Double get() = bar + drawdown
    }

    /**
     * Solo's [SoloBar] from its closed forward trades' [nets] in order and its [base]: the equity now against its best
     * since the baseline (as [SoloMidday.drawdownSince] counts it, but now rather than the worst).
     */
    fun soloBar(nets: List<Double>, base: SoloMidday.Baseline = SoloMidday.Baseline()): SoloBar {
        var eq = nets.take(base.from).sum()
        var peak = base.peak
        for (n in nets.drop(base.from)) { eq += n; peak = maxOf(peak, eq) }
        return SoloBar(minOf(0.0, eq - peak))
    }

    /** The milestones of [e]'s arm. */
    fun milestones(e: ForwardCheck.Expectation): List<Int> = if (e.key == ForwardCheck.SOLO.key) SOLO_MILESTONES else MILESTONES

    /** The highest milestone of [e]'s arm at [trades] (0: none yet). */
    fun milestone(e: ForwardCheck.Expectation, trades: Int): Int = milestones(e).lastOrNull { it <= trades } ?: 0

    /** [r]'s category: a lottery below is its drawdown; else the CUSUM's alarm before the band's verdict. */
    fun category(r: ForwardCheck.Result): Category = when {
        r.expectation.lottery && r.verdict == ForwardCheck.Verdict.BELOW -> Category.DEEPER
        !r.expectation.lottery && r.alarm -> Category.RUN_BELOW
        r.verdict == ForwardCheck.Verdict.TOO_FEW -> Category.TOO_FEW
        r.verdict == ForwardCheck.Verdict.IN_LINE -> Category.IN_LINE
        r.verdict == ForwardCheck.Verdict.ABOVE -> Category.ABOVE
        else -> Category.BELOW
    }

    /** What to keep for an arm now, and the note to post ([text] null: keep it silently). */
    data class Decision(val told: Told, val text: String?)

    /**
     * After [r] (an arm's check now) with [before] last told (null: never): null when nothing is new; else what to keep
     * and, when it is worth a word, the note. A word when the category changed from the last told one (but never into
     * "too few trades"), when a first look already has something to judge, or when a milestone is reached; the highest
     * milestone told is kept even when the record shrinks. [solo]: Solo's switch-off line, said in Solo's note.
     */
    fun decide(r: ForwardCheck.Result, before: Told?, solo: SoloBar? = null): Decision? {
        val c = category(r)
        val m = milestone(r.expectation, r.trades)
        val keep = Told(c, maxOf(m, before?.milestone ?: 0))
        if (keep == before) return null
        val changed = before != null && before.category != c
        val reached = m > (before?.milestone ?: 0)
        val speak = c != Category.TOO_FEW && (changed || before == null || reached)
        return Decision(keep, if (speak) note(r, before?.category?.takeIf { changed }, if (reached) m else null, solo, still = before != null && !changed) else null)
    }

    private fun rs(x: Double) = ForwardCheck.rs(x)
    private fun trades(n: Int) = "$n trade${if (n == 1) "" else "s"}"

    /** The arm's name as Jarvis says it. */
    fun name(e: ForwardCheck.Expectation): String = if (e.key == ForwardCheck.SOLO.key) "Solo (midday)" else e.name

    /** "per lot" / "per ₹5,000 ticket". */
    private fun unit(e: ForwardCheck.Expectation) = e.unit

    /** The numbers: trades, net a trade against the backtest's, and the band (a lottery: its drawdown against the worst). */
    fun numbers(r: ForwardCheck.Result): String {
        val e = r.expectation
        if (r.trades == 0) return "No forward trades yet (the backtest made ${rs(e.mean)} a trade ${unit(e)})."
        val per = "${trades(r.trades)}, ${rs(r.perTrade!!)} a trade ${unit(e)} vs the backtest's ${rs(e.mean)}"
        if (e.lottery) return "$per; drawdown ${rs(r.drawdown)} vs the backtest's worst ${rs(e.maxDrawdown)} (a lottery: judged by drawdown, not mean)."
        val b = r.band!!
        return "$per (its band for ${trades(r.trades)}: ${rs(b.low)} to ${rs(b.high)})."
    }

    /** What [c] means for [r], in plain words, ending in "nothing has been changed". [back]: it came back from below. */
    fun meaning(r: ForwardCheck.Result, c: Category, back: Boolean = false): String {
        val n = trades(r.trades)
        return when (c) {
            Category.TOO_FEW -> "Too few trades to judge yet: one trade's luck swamps the average until ${ForwardCheck.MIN_TRADES}."
            Category.IN_LINE -> when {
                r.expectation.lottery -> "Its drawdown is within the backtest's worst - nothing to do; nothing has been changed."
                back -> "Back within what the backtest allows for $n - the research describes it again; nothing has been changed."
                else -> "Within what the backtest allows for $n - nothing to do; nothing has been changed."
            }
            Category.ABOVE -> "Above what the backtest allows for $n - better than researched so far (it may not last); nothing has been changed."
            Category.BELOW -> "Below what the backtest allows for $n - worth watching; nothing has been changed."
            Category.RUN_BELOW -> "Its trades have kept coming in below the backtest since trade ${r.alarmAt} - an early warning of a " +
                "sustained shortfall, before the band can tell; worth watching; nothing has been changed."
            Category.DEEPER -> "Its drawdown is already deeper than the backtest's worst - the research no longer describes it; worth " +
                "watching; nothing has been changed."
        }
    }

    /** What the milestone [m] means for [e]'s arm. */
    fun milestoneWords(e: ForwardCheck.Expectation, m: Int): String = when {
        e.key == ForwardCheck.SOLO.key && m == SoloMidday.FORWARD_TRADES ->
            "$m trades: Solo's forward test is complete - its own bar set in advance (more than ₹0 a trade after $m) is Solo's to judge, as always."
        e.key == ForwardCheck.SOLO.key && m > ForwardCheck.MIN_TRADES -> "$m of Solo's ${SoloMidday.FORWARD_TRADES} forward-test trades."
        else -> "$m trades: enough to judge against the backtest."
    }

    /** Solo's switch-off line in words. */
    fun soloWords(b: SoloBar): String {
        val line = rs(-b.bar)
        if (b.room <= 0) return "Solo's own switch-off line: ${rs(-b.drawdown)} below its best - at or past the $line line; Solo's own " +
            "switch-off acts on that, not this note."
        val where = if (b.drawdown >= 0) "at its best now" else "${rs(-b.drawdown)} below its best now"
        return "Solo's own switch-off line: $where, ${rs(b.room)} from the $line line where Solo switches itself off (its own rule, untouched)."
    }

    /**
     * The note for [r]: "Forward-test watch - <arm>: now <category> (was <before>)." (or the category alone on a first
     * look, "still ..." at a milestone with no change, [still]), the milestone's words, the numbers, what it means and - for Solo - its own line.
     */
    fun note(r: ForwardCheck.Result, was: Category?, reached: Int?, solo: SoloBar? = null, still: Boolean = false): String {
        val e = r.expectation
        val c = category(r)
        val head = when {
            was != null -> "now ${c.words} (was ${was.words})"
            still -> "still ${c.words}"
            else -> c.words
        }
        val back = was == Category.BELOW || was == Category.RUN_BELOW || was == Category.DEEPER
        return listOfNotNull(
            "Forward-test watch - ${name(e)}: $head.",
            reached?.let { milestoneWords(e, it) },
            numbers(r),
            meaning(r, c, back),
            solo?.takeIf { e.key == ForwardCheck.SOLO.key }?.let { soloWords(it) },
        ).joinToString(" ")
    }

    // ---- asked ----------------------------------------------------------------------------------------------------

    /** Said on a locked phone: the paper record's figures stay off a screen anyone can see. */
    const val LOCKED = "Unlock the phone for your arms against their backtests, Boss - it names their records."

    /**
     * The on-demand answer: one line an arm in [ARMS]' order - the card's own line ([ForwardCheck.line]) - from [checks]
     * (null: that arm's record could not be read); Solo's with its own switch-off line when [solo] is known.
     */
    fun summary(checks: List<Pair<ForwardCheck.Expectation, ForwardCheck.Result?>>, solo: SoloBar? = null): String =
        checks.joinToString("\n") { (e, r) ->
            if (r == null) return@joinToString "${name(e)}: its record could not be read just now."
            val line = ForwardCheck.line(r).removePrefix("Live vs backtest: ").removeSuffix(".")
            val bar = if (e.key == ForwardCheck.SOLO.key && solo != null && r.trades > 0)
                "; ${if (solo.room <= 0) "at or past" else "${rs(solo.room)} from"} its own ${rs(-solo.bar)} switch-off line" else ""
            "${name(e)}: $line$bar."
        }

    private const val LEAD = "(jarvis |hey jarvis |ok jarvis |okay jarvis |boss |so |and |please |ok |okay |hi |hello |bata |batao |can you tell me |tell me )*"
    private const val TAIL = "( boss| jarvis| please| then| now| yaar| na| bhai| so far| today)* $"
    private const val ARMS_W = "(arms?|paper arms?|bots?|strateg(y|ies)|algos?)"
    private const val BT = "(the )?(backtests?|back tests?|research|backtested numbers)"
    private const val VS = "(vs|versus|against|compared (to|with)|relative to)"
    private val ASKED = Regex(
        // "is anything drifting", "is any arm drifting", "is any of my arms drifting", "are my arms drifting", "anything drifting from the backtest"
        "^ $LEAD(is )?(anything|something|any $ARMS_W|any of (my |the )?$ARMS_W|one of (my |the )?$ARMS_W) drifting( (from|away from) $BT)?$TAIL|" +
        "^ $LEAD(are|is) (my |the |any of my )?$ARMS_W drifting( (from|away from) $BT)?$TAIL|" +
        "^ $LEAD(any drift|drift check|drift status|drift report)( (on|in) (my |the )?$ARMS_W)?$TAIL|" +
        // "how are my arms vs backtest", "how are my arms doing against the backtest", "my arms vs the backtest", "arms vs backtest"
        "^ $LEAD(how (are|re|is) |hows |how s )?(my |the |all my |all the )?$ARMS_W (doing |holding up |performing )?$VS $BT$TAIL|" +
        // "are my arms in line with the backtest"
        "^ $LEAD(are|is) (my |the |all my )?$ARMS_W (in line|on track) with $BT$TAIL|" +
        // "live vs backtest", "live vs backtest status", "show live vs backtest"
        "^ $LEAD(show |show me |whats |what is |what s |how is |hows )?(the |my )?(live|forward|paper) $VS $BT( status| check| summary| update)?$TAIL|" +
        // "forward test status", "how is the forward test going", "forward test update", "status of my forward tests"
        "^ $LEAD(whats |what is |what s |show |show me )?(the |my )?forward ?tests?( watch)? (status|update|summary|check|report)$TAIL|" +
        "^ $LEAD(whats |what is |what s )?(the )?status of (the |my )?forward ?tests?$TAIL|" +
        "^ ${LEAD}(how (is|are|s)|hows) (the |my )?forward ?tests? (going|doing|looking)$TAIL|" +
        "^ $LEAD(forward ?test watch|forward ?tests? kaisa (chal )?(raha|rahe) (hai|hain)|forward ?test ka haal( kya hai)?)$TAIL|" +
        // Hinglish: "kya koi arm drift kar raha hai", "koi bot drift ho raha hai kya", "mere arms backtest ke hisaab se kaise hain"
        "^ $LEAD(kya )?koi $ARMS_W drift (kar|ho) (raha|rahi) (hai|he)( kya)?$TAIL|" +
        "^ $LEAD(mere |meri |hamare )?$ARMS_W backtest ke (hisaab|hisab|mukable|mukabale|against) (se |me |mein )?(kaise|kaisa|kaisi) (hain|hai|chal rahe hain)$TAIL"
    )
    /** Never this: an arm or a shadow named (its own answer), another day, an act. */
    private val NOT = Regex(" (liquidity|solo|hero|orb|fade|sweep|momentum|shadows?|retired|pine|nifty|banknifty|finnifty|sensex|gold|" +
        "yesterday|last|week|month|buy|sell|stop|start|switch|turn|disarm|pause|close|exit|run|make|create|build|write|backtest it|test this) ")

    /** "Is anything drifting", "how are my arms vs backtest", "forward test status", "live vs backtest". */
    fun asked(text: String): Boolean {
        val t = Spaced.joined(text)
        if (NOT.containsMatchIn(t)) return false
        return ASKED.containsMatchIn(t)
    }
}
