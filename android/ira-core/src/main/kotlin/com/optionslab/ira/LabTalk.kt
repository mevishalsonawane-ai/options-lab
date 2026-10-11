package com.optionslab.ira

/**
 * Jarvis on the strategy lab (11 Oct): "how are my strategies improving?", "did you find any new strategy?", "what is candidate
 * F12?", "how many ideas have you tried?", "what have you learned from the new strategies?", "which ideas keep failing?",
 * "what are you exploring next?". Facts from the lab's own books, plain and honest: most ideas fail, and he says so. Nothing
 * here acts - a proposal is only ever put to Boss, and a live one needs his PIN. Pure.
 */
object LabTalk {
    enum class Kind { IMPROVING, FOUND, CANDIDATE, TRIED, LEARNED, FAILING, EXPLORING }

    data class Ask(val kind: Kind, val id: String? = null)

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(tell me |can you tell me )?"
    private const val TAIL = "( please| boss| jarvis)* $"
    private const val STRATS = "(my |the |our |your )?(strategies|strategy|arms|bots)"
    private const val NEW = "(new |any new |a new )"

    private val IMPROVING = Regex(LEAD + "(how are $STRATS (improving|getting better|self improving|learning)|are $STRATS (improving|getting better|self improving)|" +
        "how (is|s) the strategy lab( doing)?|strategy lab (report|status)|how are the (strategy )?variants doing|any variants? (ready|winning)|" +
        "is (any|a) variant (ready|winning)|how are the shadow variants doing)" + TAIL)
    private val FOUND = Regex(LEAD + "(did you find (any |a )?new strateg(y|ies)( yet)?|have you found (any |a )?new strateg(y|ies)( yet)?|" +
        "(any|found any) new strateg(y|ies)( found| yet)?|what new strateg(y|ies) (have you|did you) (found|find)|" +
        "did the forge find (anything|any strategy)|koi nayi strategy mili)" + TAIL)
    private val CANDIDATE = Regex(LEAD + "(what is|what s|whats|tell me about|explain|describe|show me) (the )?(candidate|idea) (number )?(f ?)?([0-9]+)" + TAIL)
    private val CANDIDATE_F = Regex(LEAD + "(what is|what s|whats|tell me about|explain) (candidate )?f ?([0-9]+)" + TAIL)
    private val TRIED = Regex(LEAD + "(how many (ideas|strategies|candidates|new strategies) (have you|did you|you have) (tried|tested|try|test)( so far)?|" +
        "how many ideas (have been )?tried( so far)?|(show me )?(the )?(strategy )?graveyard|how many ideas failed)" + TAIL)
    private val LEARNED = Regex(LEAD + "(what (have you|did you|you have|youve) (learned|learnt|learn) from (the |your |all the )?(new strategies|new ideas|" +
        "strategy lab|lab|forge|variants|shadow variants|strategy variants|candidates))" + TAIL)
    private val FAILING = Regex(LEAD + "(which (ideas|blocks|building blocks|strategies|candidates) keep failing|what keeps failing|which ideas (fail|failed) (most|the most)|" +
        "what ideas keep failing)" + TAIL)
    private val EXPLORING = Regex(LEAD + "(what are you exploring( next)?|what will you (try|explore|test) next|what are you (trying|testing) next|" +
        "what (ideas )?(will|are) you (going to )?(try|test|explore) next|what next in the lab)" + TAIL)

    /** The question, or null when it is not one of these. */
    fun asked(text: String): Ask? = askedKept.of(text) { askedFresh(text) }

    private val askedKept = Kept<Ask?>(64)

    private fun askedFresh(text: String): Ask? {
        val t = Spaced.joined(text)
        if (LEARNED.containsMatchIn(t)) return Ask(Kind.LEARNED)
        if (FAILING.containsMatchIn(t)) return Ask(Kind.FAILING)
        if (EXPLORING.containsMatchIn(t)) return Ask(Kind.EXPLORING)
        if (IMPROVING.containsMatchIn(t)) return Ask(Kind.IMPROVING)
        if (FOUND.containsMatchIn(t)) return Ask(Kind.FOUND)
        if (TRIED.containsMatchIn(t)) return Ask(Kind.TRIED)
        CANDIDATE.find(t)?.let { return Ask(Kind.CANDIDATE, "F" + it.groupValues[11]) }
        CANDIDATE_F.find(t)?.let { return Ask(Kind.CANDIDATE, "F" + it.groupValues[8]) }
        return null
    }

    // ---- the answers -----------------------------------------------------------------------------------------------------

    /**
     * The answer to [a]. On a [locked] phone, none: the lab's books carry the rupees of the bots on Boss's account
     * ([SelfTalk.LOCKED_BOTS]; G9-4).
     */
    fun answer(a: Ask, f: StrategyForge.Book, v: VariantLab.Book, locked: Boolean = false): String = if (locked) SelfTalk.LOCKED_BOTS else when (a.kind) {
        Kind.IMPROVING -> improving(v)
        Kind.FOUND -> found(f)
        Kind.CANDIDATE -> candidate(f, a.id ?: "")
        Kind.TRIED -> tried(f, v)
        Kind.LEARNED -> learned(f, v)
        Kind.FAILING -> failing(f)
        Kind.EXPLORING -> exploring(f)
    }

    fun improving(v: VariantLab.Book): String {
        val lines = ArrayList<String>()
        for (s in VariantLab.STRATEGIES) {
            val alive = v.alive(s.key)
            if (alive.isEmpty() && v.runOf(s.key) == VariantLab.Run.OFF) continue
            val best = VariantLab.best(v, s.key)
            lines += "• ${s.label} (${v.runOf(s.key).words}): ${alive.size} variant${if (alive.size == 1) "" else "s"} shadowing" +
                (best?.let { "; best ${it.id} (${it.moved()}): ${VariantLab.state(it)}" } ?: "; none compared yet") + "."
        }
        // Paper promotions only: a safety-net revert acts on both accounts and has no Undo (G7-8); it is said apart.
        val applied = v.changes.filter { it.status == VariantLab.ChangeStatus.APPLIED && it.kind == VariantLab.ChangeKind.AUTO_PAPER }.takeLast(2)
        val reverted = v.changes.filter { it.status == VariantLab.ChangeStatus.APPLIED && it.kind == VariantLab.ChangeKind.SAFETY_REVERT }.takeLast(2)
        val waiting = v.changes.filter { it.status == VariantLab.ChangeStatus.OFFERED }
        if (lines.isEmpty() && applied.isEmpty() && reverted.isEmpty()) return "No strategy is running for the lab to tune yet, Boss. Arm one and its shadow variants start the next day."
        val tail = listOfNotNull(
            applied.takeIf { it.isNotEmpty() }?.let { "Changed on paper by itself: " + it.joinToString("; ") { c -> "${label(c.strategy)} on ${DiscoveryStats.day(c.day)}" } + " (undo on Home → Automation card → Strategy lab)." },
            reverted.takeIf { it.isNotEmpty() }?.let { "Put back to its defaults by the safety net: " + it.joinToString("; ") { c -> "${label(c.strategy)} on ${DiscoveryStats.day(c.day)}" } + "." },
            waiting.takeIf { it.isNotEmpty() }?.let { "Waiting for you (live, your PIN): " + it.joinToString("; ") { c -> label(c.strategy) } + "." },
            "${v.tried} variant${if (v.tried == 1) "" else "s"} tried so far; ${v.retiredCount} retired. A variant must beat the current rules for at least " +
                "${VariantLab.MIN_N} days and pass the false-discovery check across all of them.")
        return "How the strategies are improving, Boss:\n" + (lines + tail).joinToString("\n")
    }

    private fun label(key: String) = VariantLab.strategy(key)?.label ?: key

    fun found(f: StrategyForge.Book): String {
        val c = StrategyForge.counts(f)
        val waiting = f.waiting
        val paper = f.records.filter { it.stage == StrategyForge.Stage.PAPER }
        val fwd = f.forward
        if (c.tried == 0) return "Not yet, Boss: the forge has not run. It tries a few ideas each night while the phone charges or after 18:00."
        val head = when {
            waiting.isNotEmpty() -> "Yes, Boss: ${waiting.joinToString(", ") { it.c.name }} kept passing ${if (waiting.size == 1) "its" else "their"} forward test - " +
                "waiting for your yes to start on paper (paper only)."
            paper.isNotEmpty() -> "${paper.joinToString(", ") { it.c.name }} ${if (paper.size == 1) "is" else "are"} approved for paper; nothing new since."
            else -> "Not a real one yet, Boss: of ${c.tried} idea${if (c.tried == 1) "" else "s"} tried, none has passed its forward test."
        }
        val now = if (fwd.isEmpty()) "Nothing is in forward test now." else "In forward test: " + fwd.joinToString("; ") {
            "${it.c.name} (${it.fwdDays} day${if (it.fwdDays == 1) "" else "s"}, ${it.fwd.size} trades, ${DiscoveryStats.rs(it.fwdNet)})" } + "."
        return "$head $now ${c.passed} passed the backtest gates; ${c.retired} retired after a forward test."
    }

    fun candidate(f: StrategyForge.Book, id: String): String {
        val r = f.find(id) ?: return "I have no candidate called $id, Boss." + (f.records.lastOrNull()?.let { " The newest is ${it.c.id}." } ?: "")
        val bt = r.bt
        val btLine = bt?.let {
            "Backtest: ${it.trades} trades on ${it.days} stored days, ${DiscoveryStats.rs(it.net)} after costs, ${it.winRate.toInt()}% won, " +
                "folds ${it.folds.joinToString(" / ") { x -> DiscoveryStats.rs(x) }}, random twin p ${VariantLab.fmtP(it.twinP)}" +
                (if (it.approx) " (index-only approximation)" else "") + "."
        } ?: ""
        val fwd = if (r.fwdDays == 0) "" else " Forward: ${r.fwd.size} trades over ${r.fwdDays} days, ${DiscoveryStats.rs(r.fwdNet)}."
        return "${r.c.name}: ${r.c.rules.words(r.c.underlying)} $btLine$fwd Now: ${r.stage.words} - ${r.why}."
    }

    fun tried(f: StrategyForge.Book, v: VariantLab.Book): String {
        val c = StrategyForge.counts(f)
        return "${c.tried} new idea${if (c.tried == 1) "" else "s"} tried, Boss: ${c.rejected} failed the backtest gates, ${c.retired} retired after a forward test, " +
            "${c.forward} in forward test, ${c.proposed} reached you" + (if (c.paper > 0) ", ${c.paper} approved for paper" else "") + ". " +
            "And ${v.tried} variant${if (v.tried == 1) "" else "s"} of the running strategies, ${v.retiredCount} retired. Most ideas fail after costs - that is the point of the gates."
    }

    fun learned(f: StrategyForge.Book, v: VariantLab.Book): String {
        val ls = LabLessons.lessons(f, v)
        if (ls.isEmpty()) return "Nothing yet from the new strategies, Boss: too few ideas tried to say anything with numbers."
        val pick = (ls.filter { it.kind != LabLessons.Kind.GRAVEYARD }.sortedByDescending { it.n }.take(4) + ls.filter { it.kind == LabLessons.Kind.GRAVEYARD })
        return "What the new strategies taught me, Boss:\n" + pick.joinToString("\n") { "• " + it.text }
    }

    fun failing(f: StrategyForge.Book): String {
        val xs = LabLessons.failing(f)
        if (xs.isEmpty()) return "Too few tries to say yet, Boss: a building block needs ${LabLessons.MIN_TRIES} tries before I judge it."
        return "The ideas that keep failing, Boss:\n" + xs.joinToString("\n") { "• $it" } + "\nI try them less now, but never never: 1 pick in 10 stays random."
    }

    fun exploring(f: StrategyForge.Book): String {
        val xs = LabLessons.exploring(f)
        return "Next I lean toward: " + xs.joinToString("; ") { it.second } + ". Picks follow what has worked so far, and 1 in 10 is chosen at random so " +
            "nothing is ruled out. Every idea still faces the same gates, Boss."
    }
}
