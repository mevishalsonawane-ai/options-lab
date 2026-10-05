package com.optionslab.ira

/**
 * A request in several steps (Boss, 4 Oct: "make it think like an AGI, for this app"): "stop all strategies, then
 * switch the kill switch on and then switch to paper" is read as its steps, shown as one plan, confirmed once and done
 * in order - each step through the same gates as when asked alone, and the plan stops at the first step that fails.
 * Pure: the app says what a step is.
 */
object Plan {
    const val MAX_STEPS = 5

    /**
     * The only actions a plan may hold (review, 4 Oct): each lowers risk and is prepared without doing anything. Any
     * other action (starting, the kill switch off, limits, settings, anything that acts as it is read) is asked for
     * on its own, where its own gates apply (Boss's voice, the high-risk refusals).
     */
    val ALLOWED = setOf(Command.Kind.STOP_ALL, Command.Kind.STOP_ONE, Command.Kind.CANCEL_ALL, Command.Kind.CANCEL_ONE,
        Command.Kind.CLOSE_ALL, Command.Kind.CLOSE_ONE, Command.Kind.KILL_ON, Command.Kind.MODE_PAPER, Command.Kind.AUTOPILOT_OFF,
        Command.Kind.EXIT_ALL)

    /** Said when a request in steps holds an action a plan may not. */
    const val ONLY_LOWERING = "I make a plan only of steps that lower risk (stop, cancel, close, kill switch on, paper, the emergency exit) and questions, Boss. Ask for the others one at a time - nothing was done."

    // (Hinglish steps too - "meri put explain karo phir isko band karo", routing round 13 - never "phir se", "again".)
    private val STRONG = Regex("(?i)\\s*(?:,\\s*)?\\b(?:and then|then|after that|afterwards|next|aur phir|phir(?!\\s+se\\b)|uske baad|iske baad)\\b\\s*|\\s*;\\s*")
    private val AND = Regex("(?i)\\s*(?:,\\s*)?\\band\\b\\s*|\\s*,\\s*")

    /**
     * The steps of [text], or null when it is one request. [isStep]: the app can do that part on its own. Parts are
     * split on "then", "after that", ";" first; only when there is no such word on "and" or commas - and then only
     * when every part is a step (so "start ORB and Liquidity" stays one request when the parts are not).
     */
    fun steps(text: String, isStep: (String) -> Boolean): List<String>? {
        val t = text.trim().trimEnd('.', '!', '?')
        fun parts(r: Regex) = t.split(r).map { it.trim().trim(',').trim() }.filter { it.isNotEmpty() }
        val strong = parts(STRONG)
        val split = named(if (strong.size >= 2) strong.flatMap { splitAnd(it, isStep) } else splitAnd(t, isStep))
        if (split.size < 2 || split.size > MAX_STEPS || !split.all(isStep)) return null
        // A close by a pronoun left with no position named before it is never a step ("nifty kaisa hai phir isko band karo":
        // asked which position, [pronounUnclear]; routing round 13).
        if (split.drop(1).any { pronounClose(it) }) return null
        // A plan does something: questions alone are just answered (one after the other, as asked).
        if (split.all { Ask.parse(it).command == null }) return null
        return split
    }

    /** [p] split on "and" / commas only when every part is a step; else [p] whole. */
    private fun splitAnd(p: String, isStep: (String) -> Boolean): List<String> {
        val bits = p.split(AND).map { it.trim() }.filter { it.isNotEmpty() }
        // ("Explain my put and close it": the close by a pronoun is a step once the position it means is named.)
        return if (bits.size >= 2 && named(bits).all(isStep)) bits else listOf(p)
    }

    // ---- a close by a pronoun ("explain my put then close it"; routing round 12) ----------------------------------

    /**
     * "Close it", "exit that", "square it off", "close this position", "isko band karo": a close that names no position
     * of its own. Alone it is never a command (it says nothing of what to close); after a part that names one of Boss's
     * positions it is put as that position's close, a step of the plan - shown and confirmed first, never done silently.
     */
    private val PRONOUN_CLOSE = rx("^ (?:and |then |also |now )*(?:please )?(?:" +
        "(?:close|exit|square off|squareoff|cut|get out of) (?:it|that|this|that one|this one|that position|this position|that trade|this trade|the position|the trade)(?: off)?|" +
        "square (?:it|that|this|that one|this one) off|" +
        "(?:isko|usko|ise|use|ye|yeh|wo|woh|vo|isse|usse) (?:close|band|exit|square off) (?:karo|kar do|kardo|kar de)|" +
        "(?:close|band|exit|square off) (?:karo|kar do|kardo|kar de) (?:isko|usko|ise|use|ye|yeh|wo|woh|vo)" +
        ")(?: now| too| as well| also| bhi| please| boss| jarvis)* $")

    /** One of Boss's positions named by its kind: "my put", "my 24500 put", "meri call", "my BankNifty straddle". */
    private val NAMED = rx("\\b(?:my|meri|mera|mere) ((?:[a-z0-9]+ ){0,2}?(?:put|call|ce|pe|straddle|strangle|iron condor|condor|spread|butterfly|position|trade))s?\\b")

    private fun words(s: String) = " " + s.lowercase().replace("'", "").replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    /** Is [part] a close by a pronoun ("close it", "exit that", "square it off", "isko band karo")? */
    fun pronounClose(part: String): Boolean = PRONOUN_CLOSE.containsMatchIn(words(part))

    /** The last of Boss's positions named in [parts], as said ("24500 put"), or null. */
    private fun lastNamed(parts: List<String>): String? = parts.asReversed().firstNotNullOfOrNull { p ->
        NAMED.findAll(words(p)).lastOrNull()?.groupValues?.get(1)?.trim()
    }

    /** [parts] with each close by a pronoun put as the close of the position named before it; left as said when none is. */
    private fun named(parts: List<String>): List<String> = parts.mapIndexed { i, p ->
        if (i == 0 || !pronounClose(p)) p
        else lastNamed(parts.subList(0, i))?.let { w ->
            if (w.substringAfterLast(' ') in setOf("put", "call", "ce", "pe", "position", "trade")) "close my $w" else "close my $w position"
        } ?: p
    }

    /** Said with a close by a pronoun after something else, and no position of Boss's named before it to close. */
    fun pronounUnclear(text: String): Boolean {
        val bits = text.trim().trimEnd('.', '!', '?').split(STRONG).flatMap { it.split(AND) }.map { it.trim() }.filter { it.isNotEmpty() }
        return bits.size >= 2 && bits.withIndex().any { (i, b) -> i > 0 && pronounClose(b) && lastNamed(bits.subList(0, i)) == null }
    }

    /**
     * Said with a close by a pronoun after something else ("my put is losing, close it"), named before it or not: when no
     * plan is formed from it, the hub asks which position ([WHICH_POSITION]) - the close is never dropped silently.
     */
    fun pronounAfter(text: String): Boolean {
        val bits = text.trim().trimEnd('.', '!', '?').split(STRONG).flatMap { it.split(AND) }.map { it.trim() }.filter { it.isNotEmpty() }
        return bits.size >= 2 && bits.withIndex().any { (i, b) -> i > 0 && pronounClose(b) }
    }

    /** Said when a close by a pronoun names no position: nothing is done. */
    const val WHICH_POSITION = "Which position should I close, Boss? Say it by name, like \"close my 24500 put\" - nothing was done."

    /** Did a step's result say it was not done? (The plan then stops there.) */
    fun failed(result: String): Boolean =
        rx("(?i)^(not |nothing was|i could not|i did not|could not|couldn't|that did not work|zerodha refused|refused|no |you have no)").containsMatchIn(result.trim())

    /** The plan in words, numbered. */
    fun say(steps: List<String>): String = steps.mapIndexed { i, s -> "${i + 1}) $s (${Toolbox.needOf(s).label})" }.joinToString("; ")

    /** What happened, step by step: done, failed (and the rest not tried). */
    fun report(results: List<Pair<String, String>>, total: Int): String {
        val lines = results.mapIndexed { i, (step, r) -> "${i + 1}) $step: ${if (failed(r)) "NOT done - " else ""}$r" }
        val stopped = results.lastOrNull()?.let { failed(it.second) } == true && results.size < total
        return lines.joinToString(" ") + if (stopped) (if (total - results.size > 1) " I stopped there: the ${total - results.size} steps after it were not tried." else " I stopped there: the step after it was not tried.") else ""
    }
}

/**
 * Boss's choice for what Jarvis thinks should be stopped (4 Oct): asked first by default ("while stopping anything the
 * AI thinks should be stopped, take my approval"), or done automatically when Boss says so in chat ("do it
 * automatically", "don't ask me before stopping") - and asked again on "ask me before stopping". It covers only what
 * Jarvis proposes himself that stops or parks (the morning plan of paper arms, the kill switch on a broken loss goal),
 * never a trade or anything that adds risk. Pure.
 */
object AutoStop {
    private val ON = Regex("(?i)\\b(do (it|that|this|them|these) (automatically|on your own|by yourself|without asking)|stop (things |them )?automatically|" +
        "(don t|dont|do not|no need to) ask (me )?(before|for) (stopping|parking|the plan|approval|permission)|" +
        "(stop|park)\\w* (them |things )?without asking)\\b")
    private val OFF = Regex("(?i)\\b(ask (me )?(before|first)( (stopping|parking|you stop|doing))?|always ask( me)?|take my approval|don t do (it|that) automatically|stop doing (it|that) automatically)\\b")

    /** True: automatic from now; false: asked first; null: not about this. */
    fun read(text: String): Boolean? {
        val t = text.replace("'", " ")
        // An order or setting ("set automatic stop loss", "auto approve the ORB arm") is never this choice.
        if (rx("(?i)\\b(stop ?loss|sl|order|arm|lots?|buy|sell|approve)\\b").containsMatchIn(t) && !rx("(?i)\\bask me\\b").containsMatchIn(t)) return null
        // ("Don't do it automatically" / "stop doing it automatically" hold "do it automatically": asked, first.)
        if (rx("(?i)\\b(don t|dont|do not|stop|no more) (do|doing) (it|that|this|them|stops?) automatically").containsMatchIn(t)) return false
        return when { ON.containsMatchIn(t) -> true; OFF.containsMatchIn(t) -> false; else -> null }
    }

    fun said(on: Boolean): String = if (on) "Done, Boss: what I think should be stopped or parked (the morning paper-arm plan, the kill switch on a broken loss goal) I'll now do by myself and tell you. Trades still always ask. Say \"ask me before stopping\" to undo."
        else "Done, Boss: I'll ask you before stopping or parking anything."
}
