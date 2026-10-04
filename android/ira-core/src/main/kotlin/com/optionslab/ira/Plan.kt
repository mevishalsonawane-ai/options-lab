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

    private val STRONG = Regex("(?i)\\s*(?:,\\s*)?\\b(?:and then|then|after that|afterwards|next)\\b\\s*|\\s*;\\s*")
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
        val split = if (strong.size >= 2) strong.flatMap { splitAnd(it, isStep) } else splitAnd(t, isStep)
        if (split.size < 2 || split.size > MAX_STEPS || !split.all(isStep)) return null
        // A plan does something: questions alone are just answered (one after the other, as asked).
        if (split.all { Ask.parse(it).command == null }) return null
        return split
    }

    /** [p] split on "and" / commas only when every part is a step; else [p] whole. */
    private fun splitAnd(p: String, isStep: (String) -> Boolean): List<String> {
        val bits = p.split(AND).map { it.trim() }.filter { it.isNotEmpty() }
        return if (bits.size >= 2 && bits.all(isStep)) bits else listOf(p)
    }

    /** Did a step's result say it was not done? (The plan then stops there.) */
    fun failed(result: String): Boolean =
        Regex("(?i)^(not |nothing was|i could not|i did not|could not|couldn't|that did not work|zerodha refused|refused|no |you have no)").containsMatchIn(result.trim())

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
    private val ON = Regex("(?i)\\b(do (it|that|this|them|these|stops?) (automatically|on your own|by yourself|without asking)|stop (things |them )?automatically|" +
        "(don t|dont|do not|no need to) ask (me )?(before|to|for)( (stopping|parking|the plan|approval|permission))?|" +
        "(stop|park)\\w* without asking|automatic(ally)? (stops?|stopping|plan)|auto ?approve)\\b")
    private val OFF = Regex("(?i)\\b(ask (me )?(before|first)( (stopping|parking|you stop|doing))?|always ask( me)?|take my approval|don t do (it|that) automatically|stop doing (it|that) automatically)\\b")

    /** True: automatic from now; false: asked first; null: not about this. */
    fun read(text: String): Boolean? {
        val t = text.replace("'", " ")
        // ("Don't do it automatically" / "stop doing it automatically" hold "do it automatically": asked, first.)
        if (Regex("(?i)\\b(don t|dont|do not|stop|no more) (do|doing) (it|that|this|them|stops?) automatically").containsMatchIn(t)) return false
        return when { ON.containsMatchIn(t) -> true; OFF.containsMatchIn(t) -> false; else -> null }
    }

    fun said(on: Boolean): String = if (on) "Done, Boss: what I think should be stopped or parked (the morning paper-arm plan, the kill switch on a broken loss goal) I'll now do by myself and tell you. Trades still always ask. Say \"ask me before stopping\" to undo."
        else "Done, Boss: I'll ask you before stopping or parking anything."
}
