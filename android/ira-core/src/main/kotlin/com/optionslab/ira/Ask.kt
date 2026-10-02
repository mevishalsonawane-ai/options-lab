package com.optionslab.ira

/** What a question is about. */
enum class Topic { OVERVIEW, WHY, TREND, LEVELS, PATTERNS, NEWS, VOLATILITY, ADVICE, ORDER, BACKTEST, GREETING, OFF_TOPIC }

/**
 * An order the owner asked for in words. Ira never sends it: the app opens its own order review filled with this, and
 * only the owner's Confirm (and PIN in Live) sends it. Missing parts are listed so Ira asks instead of guessing.
 */
data class OrderRequest(
    val market: Market?, val buy: Boolean, val lots: Int?, val strike: Int?, val right: String?,   // "CE" / "PE"
    val missing: List<String>,
)

data class Question(val text: String, val markets: List<Market>, val topics: Set<Topic>, val order: OrderRequest?,
                    /** A pattern named in the words ("backtest the hammer on nifty"), if any. */
                    val pattern: PatternKind? = null, /** A chart named in the words: 15 or 60 minutes, if any. */ val minutes: Int? = null)

/** Reads a question: which markets, what about, and whether it asks for an order. Pure, no model. */
object Ask {
    private val TOPIC_WORDS: List<Pair<Topic, List<String>>> = listOf(
        Topic.BACKTEST to listOf("backtest", "back test", "backtested", "strategy", "test this", "test the pattern", "test it", "make it an arm"),
        Topic.WHY to listOf("why", "reason", "what happened", "behind"),
        Topic.TREND to listOf("trend", "direction", "bullish", "bearish", "going up", "going down", "heading"),
        Topic.LEVELS to listOf("level", "levels", "support", "resistance", "target", "range", "high", "low", "pool", "liquidity"),
        Topic.PATTERNS to listOf("pattern", "patterns", "candle", "candles", "engulfing", "hammer", "doji", "breakout", "breakdown", "double top", "double bottom"),
        Topic.NEWS to listOf("news", "headline", "headlines", "update", "updates"),
        Topic.VOLATILITY to listOf("volatile", "volatility", "vix", "fear", "calm", "busy", "wild", "quiet"),
        Topic.ADVICE to listOf("should i", "shall i", "is it good to", "worth buying", "worth selling", "recommend", "suggest", "tip", "tips", "advice"),
        Topic.OVERVIEW to listOf("doing", "today", "now", "price", "status", "how is", "how's", "update me", "summary", "overview", "market"),
        Topic.GREETING to listOf("hello", "hi", "hey", "good morning", "good evening", "jarvis", "ira"),
    )

    fun parse(text: String): Question {
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        val order = order(t)
        val markets = Market.mentioned(text)
        val topics = LinkedHashSet<Topic>()
        if (order != null) topics += Topic.ORDER
        for ((topic, ws) in TOPIC_WORDS) if (ws.any { t.contains(" $it ") }) topics += topic
        if (topics.isEmpty() || topics == setOf(Topic.GREETING) && markets.isNotEmpty()) {
            topics.clear(); if (markets.isNotEmpty()) topics += Topic.OVERVIEW else topics += Topic.OFF_TOPIC
        }
        if (Topic.BACKTEST in topics) { topics.remove(Topic.OVERVIEW); topics.remove(Topic.PATTERNS) }
        return Question(text, markets, topics, order, pattern(t), when {
            Regex(" (1 hour|1h|hourly|60 minute|60m|one hour) ").containsMatchIn(t) -> 60
            Regex(" (15 minute|15m|15 min|fifteen minute) ").containsMatchIn(t) -> 15
            else -> null
        })
    }

    /** A pattern named in [t] (already lower-case, spaced). */
    private fun pattern(t: String): PatternKind? = when {
        t.contains(" bearish engulfing ") -> PatternKind.BEARISH_ENGULFING
        t.contains(" engulfing ") -> PatternKind.BULLISH_ENGULFING
        t.contains(" shooting star ") -> PatternKind.SHOOTING_STAR
        t.contains(" hammer ") -> PatternKind.HAMMER
        t.contains(" three white soldiers ") || t.contains(" three green ") -> PatternKind.THREE_WHITE_SOLDIERS
        t.contains(" three black crows ") || t.contains(" three red ") -> PatternKind.THREE_BLACK_CROWS
        t.contains(" breakdown ") || t.contains(" break down ") -> PatternKind.BREAKOUT_DOWN
        t.contains(" breakout ") || t.contains(" break out ") -> PatternKind.BREAKOUT_UP
        else -> null
    }

    /** "buy 2 lots banknifty 52000 ce" -> an [OrderRequest]; null when the words do not ask for an order. */
    private fun order(t: String): OrderRequest? {
        val buy = Regex(" (buy|purchase) ").containsMatchIn(t)
        val sell = Regex(" (sell(?! off)|square off) ").containsMatchIn(t)
        if (!buy && !sell) return null
        if (Regex(" (should|shall|can|could|would|is it|worth) ").containsMatchIn(t)) return null    // a question about buying, not an order
        val market = Market.mentioned(t).firstOrNull { it != Market.VIX }
        val lots = Regex(" (\\d+) (lot|lots) ").find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex(" (one|two|three|four|five) (lot|lots) ").find(t)?.groupValues?.get(1)?.let { listOf("one", "two", "three", "four", "five").indexOf(it) + 1 }
        val right = when {
            Regex(" (ce|call|calls) ").containsMatchIn(t) -> "CE"
            Regex(" (pe|put|puts) ").containsMatchIn(t) -> "PE"
            else -> null
        }
        val strike = Regex(" (\\d{4,6}) ").findAll(t).map { it.groupValues[1].toInt() }.firstOrNull { it >= 1000 }
        val missing = buildList {
            if (market == null) add("which index")
            if (lots == null) add("how many lots")
            if (market != Market.GOLD) { if (right == null) add("call or put"); if (strike == null) add("which strike") }
        }
        return OrderRequest(market, buy, lots, strike, right, missing)
    }
}
