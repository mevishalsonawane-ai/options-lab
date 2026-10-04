package com.optionslab.ira

/**
 * A request in several steps (Boss, 4 Oct: "make it think like an AGI, for this app"): "stop all strategies, then
 * switch the kill switch on and then switch to paper" is read as its steps, shown as one plan, confirmed once and done
 * in order - each step through the same gates as when asked alone, and the plan stops at the first step that fails.
 * Pure: the app says what a step is.
 */
object Plan {
    const val MAX_STEPS = 5

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
    fun say(steps: List<String>): String = steps.mapIndexed { i, s -> "${i + 1}) $s" }.joinToString("; ")

    /** What happened, step by step: done, failed (and the rest not tried). */
    fun report(results: List<Pair<String, String>>, total: Int): String {
        val lines = results.mapIndexed { i, (step, r) -> "${i + 1}) $step: ${if (failed(r)) "NOT done - " else ""}$r" }
        val stopped = results.lastOrNull()?.let { failed(it.second) } == true && results.size < total
        return lines.joinToString(" ") + if (stopped) (if (total - results.size > 1) " I stopped there: the ${total - results.size} steps after it were not tried." else " I stopped there: the step after it was not tried.") else ""
    }
}
