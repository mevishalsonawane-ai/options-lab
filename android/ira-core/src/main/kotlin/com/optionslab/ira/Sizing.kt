package com.optionslab.ira

import java.util.Locale

/**
 * "How many lots of Nifty can I buy with 20,000?": the arithmetic only - the at-the-money option's premium times the
 * lot size, how many lots the amount covers, and the loss at the usual 15% stop. Never advice to buy, never an order.
 * Pure: the app reads the chain.
 */
object Sizing {
    data class Asked(val budget: Double, val market: Market?, val right: String?)

    private val ASK = Regex("(?i)\\b(how many lots|kitne lots?|lots? (can|could) i (buy|take|afford)|can i afford)\\b")
    private val AMOUNT = Regex("(?i)(?:rs\\.?|₹|inr)?\\s*(\\d+(?:[.,]\\d+)*)\\s*(k|thousand|hazaar|hazar|l|lakh|lakhs|lac)?\\b")

    fun asked(text: String): Asked? {
        if (!ASK.containsMatchIn(text)) return null
        // About Boss's own holding ("how many lots do I hold"), not a budget.
        if (rx("(?i)\\b(do i|did i|have i|i have|i hold|i bought|my)\\b").containsMatchIn(text)) return null
        // The rupee amount (review, 4 Oct: the strike was taken for it): a number after "with / for / mein / Rs" or with a
        // unit wins; a number followed by CE/PE, after the index's name, or called a strike is never the amount.
        data class Num(val v: Double, val marked: Boolean)
        val nums = AMOUNT.findAll(text).mapNotNull { m ->
            val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
            val after = text.substring(m.range.last + 1).trimStart().lowercase()
            val before = text.substring(0, m.range.first).trimEnd().lowercase()
            if (rx("^(ce|pe|call|put|strike)\\b").containsMatchIn(after) || rx("(nifty|banknifty|bank nifty|finnifty|strike)$").containsMatchIn(before)) return@mapNotNull null
            val unit = m.groupValues[2].lowercase()
            val v = n * when (unit) { "k", "thousand", "hazaar", "hazar" -> 1_000.0; "l", "lakh", "lakhs", "lac" -> 100_000.0; else -> 1.0 }
            Num(v, unit.isNotEmpty() || rx("(with|for|mein|me|of|rs\\.?|₹|inr)$").containsMatchIn(before) || m.value.trimStart().let { it.startsWith("rs", true) || it.startsWith("₹") })
        }.filter { it.v >= 500 }.toList()
        val budget = (nums.filter { it.marked }.ifEmpty { nums }).maxOfOrNull { it.v } ?: return null
        val right = when { rx("(?i)\\b(ce|call|calls)\\b").containsMatchIn(text) -> "CE"; rx("(?i)\\b(pe|put|puts)\\b").containsMatchIn(text) -> "PE"; else -> null }
        return Asked(budget, Market.mentioned(text).firstOrNull { it != Market.VIX && it != Market.GOLD }, right)
    }

    /**
     * "Kitne lot le sakta hoon", "how many lots can I take": the lots asked with no budget in it (Boss's own holding,
     * "how many lots am I holding", is his account's) - asked for the budget, never guessed.
     */
    fun needsBudget(text: String): Boolean = ASK.containsMatchIn(text) && asked(text) == null &&
        !Regex("(?i)\\b(do i|did i|have i|i have|i hold|i bought|my|am i|i am|i'm|holding|hold|held|open|mere|mera|meri|order|orders|khule|khula|liye|liya|kiye|pade|chal)\\b").containsMatchIn(text)

    private fun rs(v: Double) = "Rs %,.0f".format(Locale.ENGLISH, v)

    fun say(a: Asked, market: Market, right: String, strike: Double, premium: Double, lotSize: Int): String {
        val perLot = premium * lotSize
        if (perLot <= 0) return "I have no price for the ${market.label} at-the-money option just now, Boss."
        val lots = (a.budget / perLot).toInt()
        val strikeTxt = "%.0f".format(Locale.ENGLISH, strike)
        val head = "${market.label} $strikeTxt $right (at the money) is about ${rs(premium)} × $lotSize = ${rs(perLot)} a lot."
        return if (lots < 1) "$head ${rs(a.budget)} does not cover one lot, Boss."
        else "$head ${rs(a.budget)} covers $lots lot${if (lots > 1) "s" else ""} (${rs(lots * perLot)}); at a 15% stop that risks about ${rs(lots * perLot * 0.15)}. " +
            "Arithmetic only, Boss - not a suggestion to buy."
    }
}
