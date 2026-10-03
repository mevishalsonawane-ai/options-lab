package com.optionslab.ira

/**
 * Follow-ups in context (Jarvis self-improvement, 2026-10-03): "How is Nifty?" then "and BankNifty?" asks the same of
 * BankNifty; "why?" asks why of the market just asked about; "what about tomorrow?" / "same for FinNifty". Only
 * questions carry over - a command or an order is never repeated by a follow-up. Pure.
 */
object FollowUp {
    private val SWAP = Regex("^ (and|what about|how about|same for|and what about|also|now) (the )?(.+?) $")
    private val WHY = Regex("^ (why|why so|how come|reason|and why|why is that|why did it) $")

    private fun t(s: String) = " " + s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        .replace(Regex("^(jarvis|hey jarvis|ok jarvis) "), "") + " "

    /** The previous question asked again for what [now] names, or null when [now] stands on its own. */
    fun resolve(prev: String?, now: String): String? {
        if (prev.isNullOrBlank()) return null
        // Only a question carries over (never a command or an order).
        val p = Ask.parse(prev)
        if (p.command != null || p.order != null || Topic.COMMAND in p.topics || Topic.ORDER in p.topics || Topic.OFF_TOPIC in p.topics) return null
        val n = t(now)
        if (n.trim().split(" ").size > 6) return null                      // a full question stands on its own
        val prevMarkets = Market.mentioned(prev)
        if (WHY.containsMatchIn(n)) {
            val m = prevMarkets.firstOrNull() ?: return null
            return "why is ${m.label} moving today"
        }
        val sw = SWAP.find(n) ?: return null
        val rest = sw.groupValues[3]
        val newMarkets = Market.mentioned(rest)
        if (newMarkets.isEmpty()) return null
        val from = prevMarkets.firstOrNull() ?: return "$rest: ${prev.trim().trimEnd('?')}"
        // The previous question with its market swapped for the new one.
        val swapped = Regex("(?i)\\b(" + listOf(from.label, from.name, from.label.replace(" ", "")).distinct().joinToString("|") { Regex.escape(it) } + ")\\b")
            .replace(prev.trim(), newMarkets.first().label)
        return if (swapped == prev.trim()) null else swapped
    }
}
