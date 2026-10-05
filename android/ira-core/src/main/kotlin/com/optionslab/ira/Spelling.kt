package com.optionslab.ira

/**
 * Typing on a phone (the owner's wish, 2026-10-03: "i dlnr see any statergirs on"): a word Jarvis does not know but
 * that is a near miss of one it does - one letter off in a short word, or the same letters jumbled in a long one - is
 * read as that word before the question is parsed. Only Jarvis's own words are corrected, only when one is clearly
 * meant, and words it already knows are never touched. Pure.
 */
object Spelling {
    private val WORDS = listOf(
        "strategy", "strategies", "position", "positions", "order", "orders", "profit", "profits", "history", "settings",
        "status", "alarms", "alarm", "alerts", "funds", "margin", "trading", "trades", "running", "switch", "cancel",
        "close", "start", "stop", "today", "yesterday", "journal", "review", "pattern", "patterns", "backtest", "breakout",
        "breakdown", "engulfing", "hammer", "shooting", "events", "levels", "support", "resistance", "trend", "bullish",
        "bearish", "market", "nifty", "banknifty", "finnifty", "sensex", "options", "option", "expiry", "volatility",
        "autopilot", "zerodha", "protections", "targets", "trailing", "losses", "weekly", "monthly",
        "chain", "pivot", "pivots", "positional", "studied", "study", "suggest", "approve", "reject", "positive", "negative", "everything", "scripts",
    )
    private val KNOWN = WORDS.toSet() + setOf("the", "and", "any", "see", "on", "off", "my", "what", "how", "is", "are", "show", "tell", "me",
        "stock", "stocks", "start", "stars", "state", "other", "today", "about", "doing", "there", "where", "which", "should", "would",
        "trade", "alert", "above", "below", "studies", "tomorrow", "please", "price", "prices", "level", "close", "closed", "orders",
        "stops", "start", "started", "stopped", "events", "event", "today's", "week", "month", "money", "lots", "lot", "call", "calls",
        "five", "like", "line", "life", "give", "love", "shop", "smart", "older", "order", "minute", "minutes", "chart", "charts", "store")

    fun fix(text: String): String = fixed.same(text) { fixFresh(text) }

    /** The last words read ([Kept]; pure). */
    private val fixed = Kept<String>(64)

    private fun fixFresh(text: String): String {
        var changed = false
        val out = rx("[A-Za-z]+").replace(text) { m ->
            val w = m.value.lowercase()
            if (w.length < 4 || w in KNOWN) return@replace m.value
            val pick = meant.of(w) { closest(w) }
            if (pick != null) { changed = true; pick } else m.value
        }
        return if (changed) out else text
    }

    /** The one word of Jarvis's [w] (lower case) is a near miss of, or null (none, or more than one meant). */
    private fun closest(w: String): String? {
        val best = WORDS.filter { near(w, it) }
        return if (best.size == 1 || best.map { it.trimEnd('s') }.distinct().size == 1 && best.isNotEmpty())
            best.minByOrNull { kotlin.math.abs(it.length - w.length) }!!
        else null
    }

    /** Words already looked up ([Kept]; each one's answer is its letters' alone): the same words come back in every question. */
    private val meant = Kept<String?>(1024)

    /** One letter off (two in long words), or the same letters jumbled in a long word with the same first letter. */
    private fun near(w: String, v: String): Boolean {
        if (kotlin.math.abs(w.length - v.length) > 2) return false
        val d = distance(w, v)
        if (d <= (if (v.length >= 8) 2 else 1)) return true
        if (v.length >= 7 && w.first() == v.first()) {
            val a = w.groupingBy { it }.eachCount(); val b = v.groupingBy { it }.eachCount()
            val diff = (a.keys + b.keys).sumOf { kotlin.math.abs((a[it] ?: 0) - (b[it] ?: 0)) }
            return diff <= 2
        }
        return false
    }

    /** Edit distance with swapped neighbours counted as one. */
    internal fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val c = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
