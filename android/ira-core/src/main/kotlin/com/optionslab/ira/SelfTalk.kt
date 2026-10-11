package com.optionslab.ira

/**
 * What Boss asks Jarvis about the app's own automation, his own habits and his studies (the self-improvement roadmap,
 * S10-S21): today's changes across every agent and undoing them all, the learning freeze, last month's automation
 * scorecard, his trading habits and which of his rules work, a study page, today's lesson and a bot trade's replay.
 * Words only: nothing here acts - "undo everything today" is put to Boss as a request by the app, and an undo that would
 * raise live risk is left for his PIN. Unfreezing is never by voice (Settings → Bot settings only). Pure.
 */
object SelfTalk {
    enum class Kind {
        /** "What changed today across the app?" */
        TIMELINE,
        /** "Undo everything today" (a request; never done without Boss's yes, and never a live raise without the PIN). */
        UNDO_ALL,
        /** "Is learning frozen?" / "why is learning frozen?" */
        FREEZE,
        /** "How did the automation do last month?" */
        SCORECARD,
        /** "What are my trading habits?" */
        HABITS,
        /** "Which of my rules work?" */
        RULES,
        /** "Teach me about VWAP" ([Ask.subject]: what to look up). */
        STUDY,
        /** "What's today's lesson?" */
        LESSON,
        /** "Replay my last Liquidity trade" ([Ask.subject]: the strategy's words, or null for the last bot trade). */
        REPLAY,
    }

    data class Ask(val kind: Kind, val subject: String? = null)

    // Spaced.joined: lowercased, apostrophes dropped ("today's" is "todays"), every other sign a space, padded with spaces.
    private const val LEAD = "^ (?:hey |ok |okay |so |and )?(?:jarvis )?(?:so )?(?:please )?(?:can you |could you |would you )?(?:tell me |show me )?"
    private const val TAIL = "(?: please| boss| jarvis| now| for me)* $"
    private const val APP = "(?:the |this |my |your )?(?:app|application|iraalgo|system|agents|bots)"

    private val TIMELINE = Regex(LEAD + "(?:what (?:all )?(?:has |have )?changed today (?:across|in|on|throughout) $APP|" +
        "what (?:did|have) (?:all )?(?:the |my )?(?:agents|bots|systems) changed? today|" +
        "(?:show |open )?(?:all |every )?(?:of )?todays changes(?: across $APP)?|(?:all |every )?(?:automatic|auto) changes today|" +
        "what changed by itself today|everything that changed today|what changed today across (?:the board|everything))" + TAIL)

    private val UNDO_ALL = Regex(LEAD + "(?:undo (?:everything|all(?: the)?(?: automatic| auto)? changes|all of it|every change|todays changes|all todays changes)(?: made)?(?: today)?|" +
        "roll back (?:everything|all changes|todays changes)(?: today)?|put (?:everything|it all) back today)" + TAIL)

    private val FREEZE = Regex(LEAD + "(?:(?:is|has) (?:the )?learning (?:been )?(?:frozen|paused|stopped)|why (?:is|has) (?:the )?learning (?:been )?(?:frozen|paused|stopped)|" +
        "(?:is )?(?:the )?learning freeze(?: on| off)?|(?:are|is) (?:the )?automatic changes (?:frozen|paused)|why (?:did|has) (?:the )?(?:automation|autopilot|learning) (?:freeze|frozen|stop)|" +
        "what is the learning freeze)" + TAIL)

    private val SCORECARD = Regex(LEAD + "(?:how (?:did|has) (?:the )?(?:automation|autopilot|auto pilot|self learning|automatic changes) (?:do|done|perform|performed|go|gone)(?: last month| this month)?|" +
        "(?:the |last months |this months )?(?:monthly )?(?:automation |autopilot )score ?card(?: for last month)?|monthly score ?card|" +
        "(?:show |open )?(?:the )?(?:automation|autopilot) score ?card|did (?:the )?(?:automation|autopilot|automatic changes) (?:help|pay|make money)(?: last month)?)" + TAIL)

    private val HABITS = Regex(LEAD + "(?:what (?:are|re) my (?:trading )?habits|my (?:trading )?habits|how do i (?:usually )?trade|" +
        "what (?:are|re) my (?:manual )?trading patterns)" + TAIL)

    private val RULES = Regex(LEAD + "(?:which (?:of )?my (?:own )?(?:rules|trading rules|habits) (?:work|works|working|pay|lose|losing|fail)|" +
        "(?:what are |show )?my own rules(?: that work)?|do my (?:own )?rules work|which of my trades (?:work|win|lose))" + TAIL)

    private val STUDY = Regex(LEAD + "(?<verb>teach me (?:about |on )?|explain (?:to me )?|(?:open )?(?:the )?study page (?:on |about |for )?|" +
        "(?:i want to |help me )?learn (?:about |more about ))(?<topic>.{2,60}?)" + TAIL)

    private val LESSON = Regex(LEAD + "(?:what is todays lesson|whats todays lesson|todays lesson|(?:the )?lesson (?:of|for) (?:the )?(?:day|today)|" +
        "whats the lesson today|what is the lesson (?:today|of today))" + TAIL)

    private val REPLAY = Regex(LEAD + "(?:replay|walk me through|go over) (?:my |the )?(?:last|latest|most recent) (?<what>.{0,30}?)(?:bot )?trade" + TAIL)

    private val BOT_WORD = Regex(" (bot|bots|bot s|algo|algos|arm|arms|strategy|strategys|strategies) ")
    private val STRATEGY_WORDS = Regex(" (orb|fresh|sweep|range fade|fade|liquidity|hero|solo|pine|night|vix|vixdiv|mcx|silver) ")

    /** The words "explain X" must name to be a study request (so "explain todays move" never is). */
    private val STUDY_TOPICS = Regex("(vwap|value area|order flow|orderflow|open interest| oi |vix|orb|opening range|liquidity|hero|night|" +
        "divergence|mcx|solo|pine|costs?|charges|random|multiple testing|trade manager|autopilot|strategy lab|the lab|risk|position siz|sizing|" +
        "ideas fail|most ideas)")

    /** Never a study page: Boss's own positions and trades, today's market ("explain my liquidity trade" is the bots' trades). */
    private val NOT_STUDY = Regex(" (my|mine|meri|mera|today|todays|yesterday|this|that|it|position|positions|trade|trades|move|moves|order|orders|pnl|p l) ")

    /** What Boss asked of these, or null. */
    fun asked(text: String): Ask? {
        val t = Spaced.joined(text)
        if (UNDO_ALL.containsMatchIn(t)) return Ask(Kind.UNDO_ALL)
        if (TIMELINE.containsMatchIn(t)) return Ask(Kind.TIMELINE)
        if (FREEZE.containsMatchIn(t)) return Ask(Kind.FREEZE)
        if (SCORECARD.containsMatchIn(t)) return Ask(Kind.SCORECARD)
        if (LESSON.containsMatchIn(t)) return Ask(Kind.LESSON)
        if (HABITS.containsMatchIn(t)) return Ask(Kind.HABITS)
        if (RULES.containsMatchIn(t)) return Ask(Kind.RULES)
        REPLAY.find(t)?.let { m ->
            val s = m.groups["what"]?.value.orEmpty().trim()
            // A bot's trade only when "bot" or a strategy is named: "go over my last trade" / "replay my last trade" is Boss's own
            // trade ([TradeReplay]), never the newest bot trade (review R5 #5).
            if (BOT_WORD.containsMatchIn(t) || STRATEGY_WORDS.containsMatchIn(" $s ")) return Ask(Kind.REPLAY, s.ifEmpty { null })
        }
        STUDY.find(t)?.let { m ->
            val verb = m.groups["verb"]?.value.orEmpty()
            val s = m.groups["topic"]?.value.orEmpty().trim()
            // "Explain X" only when X is one of the study topics; "teach me about X" and "learn about X" for anything named.
            val strong = !verb.startsWith("explain")
            if (s.isNotEmpty() && !NOT_STUDY.containsMatchIn(" $s ") && (strong || STUDY_TOPICS.containsMatchIn(" $s "))) return Ask(Kind.STUDY, s)
        }
        return null
    }

    /** Boss's own trades, refused on a locked phone. */
    const val LOCKED = "Unlock the phone for that, Boss: it is about your own trades."
    /** The bots' results on his account (the scorecard's rupees, today's changes, the lab, Autopilot), refused on a locked phone. */
    const val LOCKED_BOTS = "Unlock the phone for that, Boss: it is about your account's bots and their results."

    /**
     * What to say instead of answering [kind] on a [locked] phone, or null to answer: his habits, rules and a trade's replay
     * are his own; the scorecard and today's changes carry the bots' rupees on his account (G9-4, review R5 #2).
     */
    fun lockedRefusal(kind: Kind, locked: Boolean): String? = if (!locked) null else when (kind) {
        Kind.HABITS, Kind.RULES, Kind.REPLAY -> LOCKED
        Kind.SCORECARD, Kind.TIMELINE -> LOCKED_BOTS
        else -> null
    }
}
