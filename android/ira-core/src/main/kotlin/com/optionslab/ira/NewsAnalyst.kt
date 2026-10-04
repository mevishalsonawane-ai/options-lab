package com.optionslab.ira

import java.util.Locale

/**
 * Jarvis reading the news as it comes (every 5 minutes, the owner's wish): which new headlines matter, whether they read
 * good or bad and for which market, and what that means for the owner's own arms and positions. The tone is a word-list
 * reading ([News.tone]), said as such; the "use" is practical, never a direction call. Pure.
 */
object NewsAnalyst {
    /** News that moves the whole market whatever its words' tone. */
    private val POLICY = Regex("\\b(rbi|repo rate|monetary policy|mpc|fed|fomc|powell|inflation|cpi|wpi|gdp|budget|fiscal|sebi|tariff|tariffs|war|ceasefire|election|crude|opec|rupee|downgrade|rating)\\b", RegexOption.IGNORE_CASE)

    data class Take(val title: String, val text: String)

    /** Is [h] worth telling the owner now? A clear tone about our markets, or market-wide policy news. */
    fun matters(h: Headline): Boolean = h.markets.isNotEmpty() && (kotlin.math.abs(h.tone) >= 0.5 || POLICY.containsMatchIn(h.title))

    /**
     * What [h] means. [armsOn]: arm or strategy name -> the index it trades; [holding]: indices with open positions.
     */
    fun take(h: Headline, armsOn: Map<String, Market>, holding: Set<Market>): Take {
        val policy = POLICY.containsMatchIn(h.title)
        val mk = h.markets.distinct()
        val reads = mk.map { m -> val t = if (m == Market.GOLD) News.tone(h.title, Market.GOLD) else h.tone
            m to when { t >= 0.25 -> "good"; t <= -0.25 -> "bad"; else -> "neutral" } }
        val verdict = reads.filter { it.second != "neutral" }.joinToString("; ") { "${it.second} for ${it.first.label}" }
            .ifBlank { if (policy) "market-wide policy news" else "neutral" }
        val use = ArrayList<String>()
        if (policy) use += "Policy news moves the whole market: expect bigger swings for 15 to 30 minutes, so wait before new entries."
        val armsHit = armsOn.filter { it.value in mk }.keys
        if (armsHit.isNotEmpty()) use += "${armsHit.joinToString()} trade${if (armsHit.size == 1) "s" else ""} ${mk.filter { m -> armsOn.values.contains(m) }.joinToString { it.label }}: run the trade check before its next entry."
        val held = holding.filter { it in mk }
        if (held.isNotEmpty()) use += "You hold ${held.joinToString { it.label }} positions: make sure their stops are set."
        if (use.isEmpty()) use += when {
            reads.any { it.second == "bad" } -> "Nothing of yours is exposed; a bad read can mean a weaker day, so let the trade check decide."
            reads.any { it.second == "good" } -> "Nothing of yours is exposed; if it moves on this, don't chase a big move."
            else -> "Nothing of yours is exposed."
        }
        val tone = String.format(Locale.ENGLISH, "%+.1f", h.tone)
        return Take("News: $verdict", "${h.source}: \"${h.title}\". It reads $verdict (word tone $tone). ${use.joinToString(" ")}")
    }
}
