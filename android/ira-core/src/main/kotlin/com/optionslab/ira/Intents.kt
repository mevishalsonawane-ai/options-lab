package com.optionslab.ira

/**
 * Free-form requests (the owner's wish, 2026-10-02): when the words match nothing Jarvis knows, the on-device model is
 * asked to pick ONE line from a fixed list of what the app can do or answer, filling in only a number, an index or a
 * price. Its reply is used only when it is exactly such a line; the line then goes through the normal parser, and an
 * action understood this way always waits for the owner's Confirm (never an order, never Live mode). Pure.
 */
object Intents {
    private const val N = "<n>"
    private const val MARKET = "<market>"
    private const val LEVEL = "<level>"

    val LINES = listOf(
        "stop all strategies", "start all strategies again", "stop strategy $N", "start strategy $N",
        "cancel all orders", "cancel order $N", "close all positions", "close position $N",
        "turn the kill switch on", "switch to paper mode", "keep your trades on paper",
        "set an alarm on $MARKET above $LEVEL", "set an alarm on $MARKET below $LEVEL",
        "should I trade now", "what did you study last night", "show my positions", "show my orders",
        "what is my p&l today", "my weekly review", "how are my news trades doing", "$MARKET pcr and max pain",
        "what did fiis do", "any events this week", "how is $MARKET doing", "$MARKET levels", "what can you do",
    )

    private val MARKETS = "(nifty|banknifty|finnifty|sensex|gold|vix)"
    private val PATTERNS = LINES.map { l ->
        Regex("^" + Regex.escape(l.lowercase()).replace(N, "\\E(\\d{1,2})\\Q").replace(MARKET, "\\E$MARKETS\\Q").replace(LEVEL, "\\E(\\d{2,6}(?:\\.\\d+)?)\\Q") + "$")
    }

    fun prompt(request: String): String {
        val sys = "You map a trader's request to one line of a fixed list. Reply with exactly one line from the LIST, " +
            "replacing <n> with a number, <market> with one of nifty, banknifty, finnifty, sensex, gold, vix, and <level> " +
            "with a price, all taken from the request. If no line fits, reply NONE. Reply with the line only."
        val user = "LIST:\n" + LINES.joinToString("\n") + "\nREQUEST: " + request.take(300)
        return "<|im_start|>system\n$sys<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }

    private val FILL = Regex(" (jarvis|please|boss|hey|ok|okay|can you|could you|would you|will you|i want you to|i want to|i need to|i d like to|just|now|right now|quickly|for me) ")
    private const val ALL = "(all|all the|all my|every|everything|my|the)"

    /**
     * Everyday words for the commonest requests, read at once by fixed rules (no model, no wait): "pause all bots",
     * "square off everything", "pull my orders", "am I up today". Only what stops, cancels, closes or protects, and
     * questions - never what starts or adds risk. The line goes through the normal parser and waits for Confirm, like
     * the model's pick.
     */
    private val QUICK = listOf(
        Regex("^(pause|halt|freeze|stop|kill|disable|shut down|shut off|switch off|turn off) $ALL? ?(bots?|algos?|strateg(y|ies)|arms?|automations?|auto trading|automatic trading)$") to "stop all strategies",
        Regex("^(pause|halt|freeze|stop) (all )?(trading|everything automatic)$") to "stop all strategies",
        Regex("^(square off|squareoff|exit|close|flatten|get out of|sell off|liquidate) $ALL? ?(positions?|trades?|everything|holdings)$") to "close all positions",
        Regex("^(square off|squareoff|flatten) everything$") to "close all positions",
        Regex("^(cancel|withdraw|pull|remove|delete|scrap) $ALL? ?(pending |open )?orders$") to "cancel all orders",
        Regex("^(activate|enable|switch on|turn on|hit|press|engage) (the )?(kill switch|panic button|emergency stop)$") to "turn the kill switch on",
        Regex("^(go|switch|move|change|back) (back )?(to )?paper( mode| trading)?$") to "switch to paper mode",
        Regex("^(how much (have i|did i) (made|make|earned|earn|lost|lose)( today)?|am i (up|down|in profit|in loss|making money|losing money)( today)?|what s my (profit|loss|pnl|p l)( today)?)$") to "what is my p&l today",
        Regex("^(what am i holding|what (positions|trades) (do i have|are open)( open)?|what do i (hold|have open))$") to "show my positions",
        Regex("^(can i trade( now| today)?|is it (safe|ok|okay|a good time) to trade( now| today)?|good time to trade( now)?|should i be trading( now| today)?)$") to "should i trade now",
        Regex("^(what did you (learn|find|study)( last night| yesterday| overnight)?|what s new from your study|any (findings|lessons) from (last night|your study))$") to "what did you study last night",
    )

    /** [text] as one line of the list by the fixed rules, or null. */
    fun quick(text: String): String? {
        var t = " " + text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        repeat(3) { t = FILL.replace(t, " ") }
        t = t.replace(Regex("\\s+"), " ").trim()
        return QUICK.firstOrNull { it.first.matches(t) }?.second
    }

    /** The model's [reply] as a line of the list (lower-case), or null when it is not exactly one. */
    fun pick(reply: String): String? {
        val line = reply.trim().lineSequence().firstOrNull()?.trim()?.trim('"', '\'', '.', ' ')?.lowercase() ?: return null
        if (line.isEmpty() || line == "none") return null
        return line.takeIf { l -> PATTERNS.any { it.matches(l) } }
    }
}
