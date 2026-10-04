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
        // The rupee amount: the largest number named with a unit, or alone (a strike-sized number with CE/PE is not it).
        val amounts = AMOUNT.findAll(text).mapNotNull { m ->
            val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
            n * when (m.groupValues[2].lowercase()) { "k", "thousand", "hazaar", "hazar" -> 1_000.0; "l", "lakh", "lakhs", "lac" -> 100_000.0; else -> 1.0 }
        }.filter { it >= 500 }.toList()
        val budget = amounts.maxOrNull() ?: return null
        val right = when { Regex("(?i)\\b(ce|call|calls)\\b").containsMatchIn(text) -> "CE"; Regex("(?i)\\b(pe|put|puts)\\b").containsMatchIn(text) -> "PE"; else -> null }
        return Asked(budget, Market.mentioned(text).firstOrNull { it != Market.VIX && it != Market.GOLD }, right)
    }

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
