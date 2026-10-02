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

    /** The model's [reply] as a line of the list (lower-case), or null when it is not exactly one. */
    fun pick(reply: String): String? {
        val line = reply.trim().lineSequence().firstOrNull()?.trim()?.trim('"', '\'', '.', ' ')?.lowercase() ?: return null
        if (line.isEmpty() || line == "none") return null
        return line.takeIf { l -> PATTERNS.any { it.matches(l) } }
    }
}
