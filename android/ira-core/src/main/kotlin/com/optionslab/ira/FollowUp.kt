package com.optionslab.ira

/**
 * Follow-ups in context (Jarvis self-improvement, 2026-10-03): "How is Nifty?" then "and BankNifty?" asks the same of
 * BankNifty; "why?" asks why of the market just asked about; "what are the levels?" asks it of that market too; "same
 * for FinNifty". Only
 * questions carry over - a command or an order is never repeated by a follow-up. Pure.
 */
object FollowUp {
    private val SWAP = Regex("^ (and|what about|how about|same for|and what about|also|now) (the )?(.+?) $")
    private val VERB = Regex(" (close|exit|buy|sell|stop|start|cancel|square|kill|switch|turn|set|place|enter|short|book|trail|move|modify|pause|resume|arm|disarm) ")
    private val WHY = Regex("^ (why|why so|how come|reason|and why|why is that|why did it) $")

    private fun t(s: String) = " " + s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        .replace(Regex("^(jarvis|hey jarvis|ok jarvis) "), "") + " "

    /** The previous question asked again for what [now] names, or null when [now] stands on its own. */
    fun resolve(prev: String?, now: String): String? {
        if (prev.isNullOrBlank()) return null
        // Only a question carries over (never a command or an order).
        val p = Ask.parse(prev)
        if (p.command != null || p.order != null || Topic.COMMAND in p.topics || Topic.ORDER in p.topics || Topic.OFF_TOPIC in p.topics) return null
        // What is said now must not act either: "and close BankNifty" is a command of its own, never a question.
        if (acts(now)) return null
        // "What's your plan today?" asks about Jarvis's own plan ([Agenda]), never the last market's.
        if (Agenda.asked(now)) return null
        return (found(prev, now) ?: carry(prev, now))?.takeIf { !acts(it) }
    }

    private val MARKET_TOPICS = setOf(Topic.OVERVIEW, Topic.TREND, Topic.LEVELS, Topic.PATTERNS, Topic.NEWS, Topic.VOLATILITY, Topic.WHY)
    /** Words that ask about the market as a whole, never about the one last named. */
    private val WHOLE = Regex(" (market|markets|everything|overall|all indices|all the indices|indices|sensex and nifty) ")

    /**
     * "How is BankNifty?" then "what are the levels?": a market question that names no market is about the one just
     * asked about (not Nifty, the usual default).
     */
    private fun carry(prev: String, now: String): String? {
        if (Market.mentioned(now).isNotEmpty()) return null
        val m = Market.mentioned(prev).firstOrNull() ?: return null
        val q = Ask.parse(now)
        if (q.topics.isEmpty() || !q.topics.all { it in MARKET_TOPICS }) return null
        if (WHOLE.containsMatchIn(t(now))) return null
        return now.trim().trimEnd('?', '.', '!').trim() + " on ${m.label}"
    }

    private fun acts(text: String): Boolean = Commands.parse(text) != null ||
        Ask.parse(text).let { it.command != null || it.order != null || Topic.COMMAND in it.topics || Topic.ORDER in it.topics }

    private fun found(prev: String, now: String): String? {
        val n = t(now)
        if (n.trim().split(" ").size > 6) return null                      // a full question stands on its own
        val prevMarkets = Market.mentioned(prev)
        if (WHY.containsMatchIn(n)) {
            val m = prevMarkets.firstOrNull() ?: return null
            return "why is ${m.label} moving today"
        }
        val sw = SWAP.find(n) ?: return null
        val rest = sw.groupValues[3]
        // A verb that acts ("and close BankNifty") is never turned into a question about the market.
        if (VERB.containsMatchIn(" $rest ")) return null
        val newMarkets = Market.mentioned(rest)
        if (newMarkets.isEmpty()) return null
        val from = prevMarkets.firstOrNull() ?: return null
        // The previous question with its market (as said: "bank nifty", "nifty 50", "bnf") swapped for the new one.
        val names = (from.aliases + from.label + from.name).distinct().sortedByDescending { it.length }
        val swapped = Regex("(?i)\\b(" + names.joinToString("|") { n -> n.split(" ").joinToString("\\s*") { Regex.escape(it) } } + ")\\b")
            .replace(prev.trim(), newMarkets.first().label)
        return if (swapped == prev.trim()) null else swapped
    }
}
