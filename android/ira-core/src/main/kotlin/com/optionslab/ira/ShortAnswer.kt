package com.optionslab.ira

import java.util.Locale

/**
 * Short, precise answers (Boss, 5 Oct: "what's the P&L today" should be "Your P&L today is +Rs 2,575, Boss", not
 * paragraphs). Every answer Jarvis gives - spoken and in the chat bubble - is this one line by default; the full answer
 * stays on the message ("Details") and "more" / "details" / "aur batao" / "go on" gives the rest.
 *
 * The common kinds have their own one-liner, the key figure first ([Kind]); every other answer gets the general
 * fallback: its lead sentence (the first, joined with the next one when the first is a short lead-in with no figure),
 * cut to about [MAX_WORDS] words at a clause boundary.
 *
 * Never dropped: a safety warning ([Aloud.warning]), a staleness note ("as of 14:05", "the market is closed", "last
 * session"), a "could not read Zerodha" / "not logged in" note, a failure ("Not protected", "failed", "refused",
 * "rejected", "not placed", "insufficient"...), a lock-screen refusal, a question to Boss, and a GOLD or paper-only
 * label - those sentences are kept after the line (a long "Zerodha did not answer..." is said shorter). The kept
 * figures' lead ("As of 14:05, Boss (...):", [KeptFigures.note]) is put back in front of the short line.
 * The app never passes an action's, a confirm's or a command's result here, nor the guard's own report: those are said
 * and shown whole (review, 5 Oct); the failure words above are the backstop.
 * Kept whole, never shortened: a question Jarvis asks (yes/no, confirm, "which one?"), an order or trade preview or
 * confirmation (Confirm, PIN, fingerprint, swipe, approve), a Hindi answer, the "more"/details reply itself.
 * Words only: no figure is changed except paise dropped from rupees and decimals dropped from levels of 1,000 or more
 * (rounded, the sign kept). Pure.
 */
object ShortAnswer {
    enum class Kind { PNL, POSITIONS, ORDERS, FUNDS, MARKET, VIX, TRADES_LEFT, THETA, LAST_TRADE, BOTS, OTHER,
        /** "More", "details", "aur batao", "explain in detail": the whole answer, never shortened. */
        WHOLE }

    /**
     * [line]: what is said and shown; [details]: the full answer behind the bubble's "Details" (null: the line is the
     * whole answer); [rest]: the sentences the line left out, for "go on" (null: none).
     */
    data class Short(val line: String, val details: String?, val rest: String? = null)

    /** About this many words in the fallback's line. */
    const val MAX_WORDS = 25

    /** Offered in the chat under a shortened answer (the details are behind the bubble's "Details" too). */
    const val MORE_HINT = "Say \"more\" for details."

    private val SENTENCE = Regex("(?<=[.!?\\u0964])\\s+|\\s*\\n+\\s*")
    /** "1." alone after a split: a list item's number, joined back to its item. */
    private val BARE_NUMBER = Regex("^\\d{1,3}\\.$")
    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
    /** "2. ORB 15 (ORB, stop 40): on." - a list's item is data, not a warning (its words "stop", "margin" are names). */
    private val LIST_ITEM = Regex("^\\d+\\. ")

    private val STALE = Regex("\\bas of\\b|last session|market is closed|no trading today|last prices|last closed|old prices|" +
        "\\bstale\\b|\\bdelayed\\b|minutes? old|not live|not fresh", RegexOption.IGNORE_CASE)
    private val UNREAD = Regex("could ?n[o']?t (read|be read|reach)|could not open|did not answer|didn't answer|not logged in|did not open|" +
        "no answer from|is offline|are offline|not connected|not included", RegexOption.IGNORE_CASE)
    private val LOCK = Regex("\\bunlock\\b", RegexOption.IGNORE_CASE)
    /** "Paper only", "paper mode", "not real money" in any case; "GOLD" (IraGoldAlgo's label) as written, never the metal's name. */
    private val LABEL = Regex("(?-i:\\bGOLD\\b)|\\bpaper (only|mode)\\b|not real money", RegexOption.IGNORE_CASE)
    /** A failure or refusal: never dropped (review, 5 Oct: "Not protected: Insufficient funds." was cut). */
    private val FAIL = Regex("\\b(not protected|failed|refused|rejected|not sent|not placed|not set|could not|insufficient)\\b", RegexOption.IGNORE_CASE)
    /** The kept figures' lead ([KeptFigures.note]): taken off before reading, put back in front of the line. */
    private val AS_OF_LEAD = Regex("^(As of \\d{1,2}:\\d{2}, Boss \\([^)]*\\)[:.])\\s+(?=\\S)")
    /** An answer that asks Boss or carries an order's confirm: never shortened. */
    private val ASKS = Regex("\\b(tap confirm|confirm it|confirmed with|your confirm|fingerprint|\\bPIN\\b|swipe|approve|yes or no|say yes|" +
        "which one|order review|nothing is sent until|shall i)\\b", RegexOption.IGNORE_CASE)

    private fun norm(q: String) = " " + spacedWords(q.lowercase().replace("p&l", "p l").replace("'", ""), "") + " "

    private val Q_WHOLE = Regex("^ (tell me |say |some )?more( please| details| detail| boss)? $| (details?|in detail|detail mein|detail me|vistar se|" +
        "aur batao|aur bolo|aur bataiye|explain more|full answer|the full answer|go on|tell me more|in full|elaborate|say the rest|the rest) ")
    private val Q_TRADES_LEFT = Regex(" (trades? (left|remaining)|how many (more )?trades (can|may|left)|more trades) ")
    private val Q_THETA = Regex(" (theta|time decay|decay) ")
    private val Q_LAST = Regex(" last (trade|order|fill) ")
    private val Q_PNL = Regex(" (p l|pnl|profit|loss|mtm|m2m|made|make|making|lost|earned|kamaya|munafa|nuksaan) ")
    private val Q_HISTORY = Regex(" (yesterday|week|weekly|month|monthly|history|best day|worst day|this year|all time) ")
    private val Q_POS = Regex(" (positions?|holdings?|open trades|exposure) ")
    private val Q_ORDERS = Regex(" (orders?|fills?|rejected|rejections?) ")
    private val Q_FUNDS = Regex(" (funds?|margin|margins|balance|cash|buying power) ")
    private val Q_BOTS = Regex(" (strategy|strategies|arms?|bots?|algos?) ")
    private val Q_VIX = Regex(" (vix|india vix) ")
    private val Q_MARKET = Regex(" (nifty|banknifty|bank nifty|finnifty|fin nifty|sensex|gold|market|index) ")

    /** The question's kind (blank: an unasked note - the fallback). */
    fun kind(question: String?): Kind {
        if (question.isNullOrBlank()) return Kind.OTHER
        val s = norm(question)
        return when {
            Q_WHOLE.containsMatchIn(s) -> Kind.WHOLE
            Q_TRADES_LEFT.containsMatchIn(s) -> Kind.TRADES_LEFT
            Q_THETA.containsMatchIn(s) -> Kind.THETA
            Q_LAST.containsMatchIn(s) -> Kind.LAST_TRADE
            Q_HISTORY.containsMatchIn(s) -> Kind.OTHER
            Q_PNL.containsMatchIn(s) -> Kind.PNL
            Q_POS.containsMatchIn(s) -> Kind.POSITIONS
            Q_ORDERS.containsMatchIn(s) -> Kind.ORDERS
            Q_FUNDS.containsMatchIn(s) -> Kind.FUNDS
            Q_BOTS.containsMatchIn(s) -> Kind.BOTS
            Q_VIX.containsMatchIn(s) -> Kind.VIX
            Q_MARKET.containsMatchIn(s) -> Kind.MARKET
            else -> Kind.OTHER
        }
    }

    /** [text]'s sentences; a list item's bare number ("1.") stays with its item ("1. ORB 15 (ORB, stop 40): on."). */
    fun sentences(text: String): List<String> {
        val raw = SENTENCE.split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>(raw.size)
        var i = 0
        while (i < raw.size) {
            if (BARE_NUMBER.matches(raw[i]) && i + 1 < raw.size) { out += raw[i] + " " + raw[i + 1]; i += 2 } else { out += raw[i]; i++ }
        }
        return out
    }

    /** A sentence that must stay in the short line (warning, staleness, could not read, a failure, lock, a question, a label). */
    fun safety(s: String): Boolean = !LIST_ITEM.containsMatchIn(s) && Aloud.warning(s) || STALE.containsMatchIn(s) || UNREAD.containsMatchIn(s) ||
        FAIL.containsMatchIn(s) || LOCK.containsMatchIn(s) || s.trimEnd().endsWith("?") || LABEL.containsMatchIn(s)

    /** Kept whole: Jarvis asks Boss, or the answer carries an order's confirm; a Hindi answer. */
    fun exempt(answer: String): Boolean = ASKS.containsMatchIn(answer) || DEVANAGARI.containsMatchIn(answer)

    /**
     * [answer] to [question] (null or blank: an unasked note) as one short line, with the rest as details. [short]
     * false (Boss's "detailed" choice): the answer as it is.
     */
    fun of(question: String?, answer: String, short: Boolean = true): Short {
        val whole = Short(answer, null)
        if (!short || answer.isBlank() || exempt(answer)) return whole
        // "As of 14:05, Boss (...):" before kept figures: the answer read without it, and it put back in front.
        AS_OF_LEAD.find(answer)?.let { m ->
            val inner = of(question, answer.substring(m.range.last + 1), short)
            return if (inner.details == null) whole else Short(m.groupValues[1] + " " + inner.line, answer, inner.rest)
        }
        val kind = kind(question)
        if (kind == Kind.WHOLE) return whole
        val parts = sentences(answer)
        if (parts.size <= 1 && words(answer) <= MAX_WORDS) return whole
        val key: Pair<String, Set<Int>>? = runCatching { keyed(kind, parts) }.getOrNull()
        val (lead, used) = key ?: fallback(parts)
        // Safety sentences not already in the line, in their order (a long "could not read" said shorter).
        val keep = parts.withIndex().filter { (i, s) -> i !in used && safety(s) }
        val keepText = keep.map { (_, s) -> shortSafety(s) }.distinct()
        val line = (listOf(tidy(lead)) + keepText).joinToString(" ").trim()
        val usedAll = used + keep.map { it.index }
        val rest = parts.withIndex().filter { it.index !in usedAll }.map { it.value }
        if (line == answer.trim()) return whole
        // A lead cut short: "go on" says that sentence whole, then the rest.
        val restText = (if (lead.endsWith(CUT)) listOf(parts[used.minOrNull() ?: 0]) else emptyList<String>()) + rest
        return Short(line, answer, restText.joinToString(" ").ifBlank { null })
    }

    /** The trimmed fallback ends with this (the sentence went on). */
    private const val CUT = "…"

    private fun words(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** The general fallback: the lead sentence (with the next one when the first is a short lead-in without a figure), at most about [MAX_WORDS] words. */
    private fun fallback(parts: List<String>): Pair<String, Set<Int>> {
        // A lead sentence that is only a safety note (the market is closed...) is kept by the safety pass: the lead is the first other one.
        val first = parts.indexOfFirst { !safety(it) || DIGIT.containsMatchIn(it) }.takeIf { it >= 0 } ?: 0
        var lead = parts[first]
        val used = mutableSetOf(first)
        val next = parts.getOrNull(first + 1)
        if (next != null && !DIGIT.containsMatchIn(lead) && words(lead) < 8 && DIGIT.containsMatchIn(next) && words(lead) + words(next) <= MAX_WORDS) {
            lead = "$lead $next"; used += first + 1
        }
        return trim(lead) to used
    }

    private val DIGIT = Regex("\\d")

    /** [s] cut to about [MAX_WORDS] words at a clause boundary (a comma, a colon, a semicolon or a dash), never inside a figure. */
    fun trim(s: String): String {
        val w = s.trim().split(Regex("\\s+"))
        if (w.size <= MAX_WORDS + 5) return s.trim()
        // The last clause boundary within the first MAX_WORDS words (after at least 6 words).
        var cutAt = -1
        for (i in 5 until minOf(w.size, MAX_WORDS)) {
            val x = w[i]
            if (x.endsWith(",") && !Regex("\\d,$").containsMatchIn(x) || x.endsWith(";") || x.endsWith(":") || w.getOrNull(i + 1) == "-") cutAt = i
        }
        val upto = if (cutAt >= 0) cutAt + 1 else MAX_WORDS
        return w.take(upto).joinToString(" ").trimEnd(',', ';', ':', ' ') + CUT
    }

    /** "Zerodha did not answer just now, so its orders are not included." -> "Zerodha could not be read just now." */
    private fun shortSafety(s: String): String = when {
        Regex("^Zerodha did not answer", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "Zerodha could not be read just now."
        Regex("^Zerodha: not logged in", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "Zerodha is not logged in today."
        else -> s
    }

    // ---- the hand-tuned kinds -------------------------------------------------------------------------------------

    private val PNL_LINE = Regex("^(Paper|Zerodha|Live|Gold[A-Za-z ]*?) P&L today ([+-])Rs ([\\d,]+(?:\\.\\d+)?)")
    private val PNL_NONE = Regex("^No P&L on (\\w+) today")
    private val POS_LINE = Regex("^(\\w+): (\\d+) open positions?\\.")
    private val POS_NONE = Regex("^No open positions on (\\w+)\\.")
    private val ORD_LINE = Regex("^(\\w+): (\\d+) orders? today, (\\d+) filled, (\\d+) open, (\\d+) rejected or cancelled\\.")
    private val ORD_NONE = Regex("^No orders on (\\w+) today\\.")
    private val FUNDS_LINE = Regex("^(\\w+) funds: (Rs -?[\\d,]+(?:\\.\\d+)?) available")
    /** Any account's funds sentence: one that [FUNDS_LINE] cannot read sends the whole answer to the fallback. */
    private val FUNDS_ANY = Regex("^(\\w+) funds:")
    private val MARKET_LINE = Regex("^(?:Boss, )?([A-Z][A-Za-z ]{1,20}?) (is at|last closed at) [\\d,]+")
    private val THETA_LINE = Regex("theta|time decay|decay", RegexOption.IGNORE_CASE)
    private val LEFT_LINE = Regex("trades? (left|remaining)|more trades?", RegexOption.IGNORE_CASE)
    private val LAST_LINE = Regex("last (trade|order|fill)|order \\d{1,2}:\\d{2}", RegexOption.IGNORE_CASE)
    private val BOTS_LINE = Regex("^(\\d+) of (\\d+) strategies and arms are switched on\\.|switched on|running", RegexOption.IGNORE_CASE)

    private fun acct(a: String) = when (a.lowercase()) { "paper" -> "paper"; "live" -> "Zerodha"; else -> a }

    /** The kind's own line and the sentences it used, or null (the fallback then). */
    private fun keyed(kind: Kind, parts: List<String>): Pair<String, Set<Int>>? = when (kind) {
        Kind.PNL -> {
            val found = parts.withIndex().mapNotNull { (i, s) ->
                PNL_LINE.find(s)?.let { m -> Triple(i, acct(m.groupValues[1]), m.groupValues[2] to m.groupValues[3]) }
                    ?: PNL_NONE.find(s)?.let { m -> Triple(i, acct(m.groupValues[1]), null) }
            }.distinctBy { it.second }
            when {
                found.isEmpty() -> null
                found.size == 1 -> {
                    val (i, a, v) = found[0]
                    (if (v == null) "No P&L on $a today." else "Your $a P&L today is ${signed(v.first, v.second)}.") to setOf(i)
                }
                else -> (found.joinToString(", ") { (_, a, v) -> "$a " + (v?.let { "${it.first}Rs ${rupees(it.second)}" } ?: "nil") }
                    .replaceFirstChar { it.uppercase() } + " today.") to found.map { it.first }.toSet()
            }
        }
        Kind.POSITIONS -> collect(parts) { s ->
            POS_LINE.find(s)?.let { m -> "${acct(m.groupValues[1])} ${m.groupValues[2]} open" } ?: POS_NONE.find(s)?.let { m -> "${acct(m.groupValues[1])} none open" }
        }?.let { (l, u) -> "Positions: $l." to u }
        Kind.ORDERS -> collect(parts) { s ->
            ORD_LINE.find(s)?.let { m -> "${acct(m.groupValues[1])} ${m.groupValues[2]} today (${m.groupValues[3]} filled, ${m.groupValues[4]} open, ${m.groupValues[5]} rejected or cancelled)" }
                ?: ORD_NONE.find(s)?.let { m -> "${acct(m.groupValues[1])} none today" }
        }?.let { (l, u) -> "Orders: $l." to u }
        Kind.FUNDS -> if (parts.any { FUNDS_ANY.containsMatchIn(it) && !FUNDS_LINE.containsMatchIn(it) }) null
            else collect(parts) { s -> FUNDS_LINE.find(s)?.let { m -> "${acct(m.groupValues[1])} ${rupeesText(m.groupValues[2])}" } }
                ?.let { (l, u) -> "Available: $l." to u }
        Kind.MARKET, Kind.VIX -> {
            val hits = parts.withIndex().filter { (_, s) -> MARKET_LINE.containsMatchIn(s) &&
                (kind == Kind.MARKET || s.contains("VIX", ignoreCase = true)) }.take(3)
            if (hits.isEmpty()) null else hits.joinToString(" ") { (_, s) -> level(s) } to hits.map { it.index }.toSet()
        }
        Kind.THETA -> first(parts) { THETA_LINE.containsMatchIn(it) && DIGIT.containsMatchIn(it) }
        Kind.TRADES_LEFT -> first(parts) { LEFT_LINE.containsMatchIn(it) && DIGIT.containsMatchIn(it) }
        Kind.LAST_TRADE -> first(parts) { LAST_LINE.containsMatchIn(it) }
        Kind.BOTS -> first(parts) { BOTS_LINE.containsMatchIn(it) }
        Kind.OTHER, Kind.WHOLE -> null
    }

    private fun first(parts: List<String>, ok: (String) -> Boolean): Pair<String, Set<Int>>? =
        parts.indexOfFirst(ok).takeIf { it >= 0 }?.let { trim(parts[it]) to setOf(it) }

    /** Each account's piece (one per account, in order) joined: "Zerodha 2 open, paper none open". */
    private fun collect(parts: List<String>, piece: (String) -> String?): Pair<String, Set<Int>>? {
        val hits = parts.withIndex().mapNotNull { (i, s) -> piece(s)?.let { i to it } }.distinctBy { it.second.substringBefore(' ') }
        if (hits.isEmpty()) return null
        return hits.joinToString(", ") { it.second } to hits.map { it.first }.toSet()
    }

    private fun signed(sign: String, amount: String) = if (sign == "-") "a loss of Rs ${rupees(amount)}" else "+Rs ${rupees(amount)}"

    /** "2,575.40" -> "2,575" (whole rupees, rounded; the grouping kept). */
    fun rupees(amount: String): String = whole(amount)

    private fun rupeesText(s: String) = s.replace(Regex("Rs (-?)([\\d,]+(?:\\.\\d+)?)")) { m -> "Rs " + m.groupValues[1] + rupees(m.groupValues[2]) }

    /** [n] ("24,612.40", "1,23,456.7") rounded to a whole number, written with the same grouping. */
    fun whole(n: String): String {
        if (!n.contains('.')) return n
        val v = n.replace(",", "").toDoubleOrNull() ?: return n
        val r = Math.round(v)
        val indian = Regex("\\d,\\d{2},\\d{3}").containsMatchIn(n)
        val plain = r.toString()
        if (!n.contains(',') && plain.length <= 3) return plain
        return if (indian) indianGroup(plain) else "%,d".format(Locale.ENGLISH, r)
    }

    private fun indianGroup(d: String): String {
        if (d.length <= 3) return d
        val last3 = d.takeLast(3)
        var head = d.dropLast(3)
        val out = ArrayList<String>()
        while (head.length > 2) { out.add(0, head.takeLast(2)); head = head.dropLast(2) }
        if (head.isNotEmpty()) out.add(0, head)
        return out.joinToString(",") + "," + last3
    }

    /** A market line kept to its level, its day's change and its "as of": "Nifty is at 24,612, up 0.42% as of 14:05." */
    private fun level(s: String): String {
        var t = s.substringBefore(", in a range").trimEnd('.', ' ')
        t = t.replace(Regex("\\(([+\\-\\u2212]?)(\\d+(?:\\.\\d+)?)% on the day\\)")) { m ->
            val down = m.groupValues[1] == "-" || m.groupValues[1] == "−"
            (if (down) "down " else if (m.groupValues[2].toDouble() == 0.0) "flat " else "up ") + m.groupValues[2] + "%"
        }.replace(Regex("(\\d) (down|up|flat) ")) { m -> "${m.groupValues[1]}, ${m.groupValues[2]} " }
        return tidy(t) + "."
    }

    /** Paise and a level's decimals dropped (1,000 and over), rounded: "+Rs 2,575.40" -> "+Rs 2,575", "24,612.40" -> "24,612". */
    fun tidy(s: String): String = s
        .replace(Regex("Rs ([\\d,]+\\.\\d+)")) { m -> "Rs " + whole(m.groupValues[1]) }
        .replace(Regex("(?<![\\d.,])(\\d{1,3}(?:,\\d{2,3})+\\.\\d+)(?![\\d%])")) { m -> whole(m.groupValues[1]) }
}
