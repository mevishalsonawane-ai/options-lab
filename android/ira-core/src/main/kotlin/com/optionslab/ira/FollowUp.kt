package com.optionslab.ira

/**
 * Follow-ups in context (Jarvis self-improvement, 2026-10-03): "How is Nifty?" then "and BankNifty?" asks the same of
 * BankNifty; "why?" asks why of the market just asked about; "what are the levels?" asks it of that market too; "same
 * for FinNifty". Since 2026-10-05 also in Hinglish ("aur Sensex?", "BankNifty ka kya?", "kyun?"), of another day ("what
 * about yesterday?", "and last week?") and what comes next ("uske baad?", "then what?"), with the recognizer's fillers
 * ("umm, and BankNifty?") left out. Only questions carry over - a command or an order is never repeated by a
 * follow-up, and nothing said now that acts is ever read as one. Pure.
 */
object FollowUp {
    // ("Aur Sensex?" - the recognizer often writes Hindi "aur" as "or".)
    private val SWAP = Regex("^ (and|what about|how about|same for|same with|and what about|and how about|also|now|aur|or|ab) (the )?(.+?) $")
    /** "BankNifty ka kya?", "Sensex mein?", "FinNifty bhi": a market alone with a Hindi tail asks the same of it. */
    private val HSWAP = Regex("^ (.+?) (ka|ki|ke|mein|me|main|bhi|too|as well)( kya| kya haal| kya haal hai| haal| haal hai| kya hai)? $")
    /** Tails dropped from what a swap names ("and BankNifty too", "aur Sensex ka"). */
    private val TAIL = Regex(" (too|as well|also|bhi|ka|ki|ke|mein|me|main|ka kya|ka kya haal hai|ka haal|ka kya hai)$")
    private val VERB = Regex(" (close|exit|buy|sell|stop|start|cancel|square|kill|switch|turn|set|place|enter|short|book|trail|move|modify|pause|resume|arm|disarm) ")
    private val WHY = Regex("^ (why|why so|how come|reason|and why|why is that|why did it|kyun|kyu|kyon|aisa kyun|aisa kyu|ye kyun|yeh kyun|kyun hua|kyu hua|aisa kyun hua|par kyun|but why) $")
    /**
     * "Uske baad?", "then what?", "what next?": where the market just asked about is heading (its trend - never a call
     * to buy or sell).
     */
    private val NEXT = Regex("^ (uske baad|iske baad|us ke baad|is ke baad|uske baad kya|phir|fir|phir kya|fir kya|aage|aage kya|then what|and then|and then what|after that|what next|what s next|whats next|what happens next) $")

    private enum class Span { YESTERDAY, LAST_WEEK, WEEK, MONTH, TODAY }
    /** "What about yesterday?", "and last week?", "aur pichle hafte?": the same asked of another day or span. */
    private val WHEN = listOf(
        Span.YESTERDAY to Regex("^(yesterday|yesterday s|the day before|the previous day|last session|the last session)$"),
        Span.LAST_WEEK to Regex("^(last week|previous week|pichle hafte|pichhle hafte|pichla hafta|pichle hafta)$"),
        Span.WEEK to Regex("^(this week|week|is hafte|iss hafte|is hafta)$"),
        Span.MONTH to Regex("^(this month|month|is mahine|iss mahine|is mahina)$"),
        Span.TODAY to Regex("^(today|aaj)$"),
    )

    private fun t(s: String) = " " + s.lowercase().replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim()
        .replace(rx("^(jarvis|hey jarvis|ok jarvis) "), "") + " "

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
        // ("Umm, and BankNifty?": the recognizer's fillers are not part of the follow-up.)
        val said = Filler.clean(now).ifBlank { return null }
        if (acts(said)) return null
        return (found(prev, said) ?: carry(prev, said))?.takeIf { !acts(it) }
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

    /** Does [text] ask for anything to be done (a command or an order), as heard? */
    internal fun acts(text: String): Boolean = Commands.parse(text) != null ||
        Ask.parse(text).let { it.command != null || it.order != null || Topic.COMMAND in it.topics || Topic.ORDER in it.topics }

    private fun found(prev: String, now: String): String? {
        val n = t(now)
        if (n.trim().split(" ").size > 6) return null                      // a full question stands on its own
        val prevMarkets = Market.mentioned(prev)
        if (WHY.containsMatchIn(n)) {
            val m = prevMarkets.firstOrNull() ?: return null
            return "why is ${m.label} moving today"
        }
        if (NEXT.containsMatchIn(n)) {
            val m = prevMarkets.firstOrNull { it != Market.VIX } ?: return null
            return "where is ${m.label} heading next"
        }
        val rest = SWAP.find(n)?.groupValues?.get(3)?.let { TAIL.replace(it, "").trim() }
            ?: HSWAP.find(n)?.groupValues?.get(1)?.trim()?.takeIf { onlyMarkets(it) } ?: return null
        // A verb that acts ("and close BankNifty") is never turned into a question about the market.
        // (Nor is any word that could act, in English or Hinglish: "aur Sensex ka order lagao".)
        if (VERB.containsMatchIn(" $rest ") || Compound.ACTION.containsMatchIn(" $rest ")) return null
        val newMarkets = Market.mentioned(rest)
        if (newMarkets.isEmpty()) return WHEN.firstOrNull { it.second.matches(rest) }?.let { at(prev, prevMarkets, it.first) }
        val from = prevMarkets.firstOrNull() ?: return null
        return swap(prev, from, newMarkets.first())
    }

    /** [text] with [from] (as said: "bank nifty", "nifty 50", "bnf") swapped for [to]; null when [from] is not in it. */
    internal fun swap(text: String, from: Market, to: Market): String? {
        val names = (from.aliases + from.label + from.name).distinct().sortedByDescending { it.length }
        val swapped = rx("(?i)\\b(" + names.joinToString("|") { n -> n.split(" ").joinToString("\\s*") { Regex.escape(it) } } + ")\\b")
            .replace(text.trim(), to.label)
        return if (swapped == text.trim()) null else swapped
    }

    /** Is [s] only market names ("bank nifty", "the sensex")? */
    internal fun onlyMarkets(s: String): Boolean {
        if (Market.mentioned(s).isEmpty()) return false
        var r = " " + s.lowercase().replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "
        Market.ALIASES_LONGEST_FIRST.forEach { r = r.replace(" $it ", " ") }
        return r.replace(rx(" (the|and|aur|or|on|for|in|index) "), " ").replace(rx(" (the|and|aur|or|on|for|in|index) "), " ").isBlank()
    }

    /**
     * The previous question asked of another day or span: the market's own figures for a market question, Boss's P&L
     * for an account one. ("And tomorrow?" is not here: nothing is known of tomorrow.)
     */
    private fun at(prev: String, prevMarkets: List<Market>, span: Span): String? {
        if (Ask.parse(prev).topics == setOf(Topic.ACCOUNT)) return when (span) {
            Span.YESTERDAY -> "what was my p&l yesterday"
            Span.LAST_WEEK -> "what was my p&l last week"
            Span.WEEK -> "what is my p&l this week"
            Span.MONTH -> "what is my p&l this month"
            Span.TODAY -> "what is my p&l today"
        }
        val m = prevMarkets.firstOrNull { it != Market.VIX && it != Market.GOLD } ?: return null
        return when (span) {
            Span.YESTERDAY -> "what was yesterday's high, low and close on ${m.label}"
            Span.LAST_WEEK -> "how did ${m.label} do last week"
            Span.WEEK -> "how has ${m.label} done this week"
            Span.MONTH -> "how has ${m.label} done this month"
            Span.TODAY -> "how is ${m.label} doing today"
        }
    }
}
