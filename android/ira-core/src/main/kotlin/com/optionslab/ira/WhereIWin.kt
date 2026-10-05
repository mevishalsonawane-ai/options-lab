package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Where Boss's own trading makes and loses its money (usefulness round 30, 2026-10-05): "where do I make my money?",
 * "am I better at calls or puts?", "do I make more buying or selling options?", "which index do I make money on?",
 * "what kind of trades work for me?", "my calls vs my puts", "call mein zyada kamata hoon ya put mein", "kis index mein
 * paisa banta hai".
 *
 * [MyNumbers] says his record as a whole and [MonthReview] splits one month by index; nothing set his calls beside his
 * puts, his bought trades beside his sold ones, or each index beside the others over the span he names. Here, from his
 * own closed trades in the trade book (the bots' only when he has none, and said so), each account apart (paper is never
 * added to real money), over the span he names (today, this or last week, this or last month; everything on record when
 * none is said): by index, by calls / puts / futures, by bought first against sold first, and for options the four
 * together (bought calls, bought puts, sold calls, sold puts) - each with its trades, wins and net, and what a trade made
 * on average. A group is compared only with [MIN_GROUP] trades; fewer are named as too few. The part asked comes first.
 *
 * Facts from his own record only - never a forecast and never what to trade. Nothing here places, changes or arms
 * anything. His account, so never on a locked phone. Pure.
 */
object WhereIWin {
    const val LOCKED = "Your trades stay out of it on a locked phone, Boss - unlock it for that."

    /** A group is set beside the others only with at least this many closed trades. */
    const val MIN_GROUP = 3

    /** Said once after every account's lines. */
    const val CLOSING = "That's where your own record made and lost its money, Boss - not a forecast and not what to trade; what you make of it is yours."

    /** The part of the split asked about first ([ALL]: none in particular). */
    enum class Cut { ALL, INDEX, KIND, SIDE }

    /** One closed trade: [direction] +1 bought first, -1 sold first; [owner] "Manual" for Boss's own. */
    data class Trade(val symbol: String, val direction: Int, val closedAt: LocalDateTime, val net: Double, val owner: String)

    data class Group(val name: String, val trades: Int, val won: Int, val net: Double) {
        val perTrade: Double get() = if (trades > 0) net / trades else 0.0
    }

    private fun rs(x: Double) = (if (x < -0.5) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun date(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " ${d.dayOfMonth} " +
        d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    // ---- the groups ----------------------------------------------------------------------------------------------

    private val INDICES = listOf("BANKNIFTY" to "BankNifty", "FINNIFTY" to "FinNifty", "MIDCPNIFTY" to "Midcap Nifty",
        "BANKEX" to "Bankex", "SENSEX" to "Sensex", "NIFTY" to "Nifty")

    /** The index [symbol] is on ("others" for anything else). */
    fun index(symbol: String): String {
        val s = symbol.uppercase(Locale.ENGLISH).substringAfter(':')
        return INDICES.firstOrNull { s.startsWith(it.first) }?.second ?: "others"
    }

    private val OPTION = Regex("\\d(CE|PE)$")

    /** "calls", "puts", "futures" or "others" for [symbol]. */
    fun kind(symbol: String): String {
        val s = symbol.uppercase(Locale.ENGLISH).trim()
        OPTION.find(s)?.let { return if (it.groupValues[1] == "CE") "calls" else "puts" }
        return if (s.endsWith("FUT")) "futures" else "others"
    }

    /** "bought first" or "sold first". */
    fun side(t: Trade): String = if (t.direction < 0) "sold first" else "bought first"

    /** [trades] grouped by [key], the best net first. */
    fun groups(trades: List<Trade>, key: (Trade) -> String): List<Group> =
        trades.groupBy(key).map { (k, ts) -> Group(k, ts.size, ts.count { it.net > 0.5 }, ts.sumOf { it.net }) }
            .sortedByDescending { it.net }

    private fun groupText(g: Group) =
        "${g.name} ${plural(g.trades, "trade")}, ${g.won} won, ${rs(g.net)}" +
            if (g.trades >= MIN_GROUP) " (${rs(g.perTrade)} a trade)" else " (too few to judge)"

    /** One cut's line, or null when every trade falls in one group and the cut was not asked. */
    private fun cutLine(title: String, gs: List<Group>, asked: Boolean, all: Int): String? {
        if (gs.size < 2) {
            if (!asked) return null
            val only = gs.firstOrNull() ?: return null
            return "$title: all ${plural(all, "trade")} were ${only.name}, so there is nothing to set beside them."
        }
        val s = StringBuilder("$title: ").append(gs.joinToString("; ") { groupText(it) }).append('.')
        val enough = gs.filter { it.trades >= MIN_GROUP }
        if (enough.size >= 2) {
            val best = enough.maxBy { it.perTrade }
            val worst = enough.minBy { it.perTrade }
            if (best !== worst) s.append(" A trade made most on ${best.name} (${rs(best.perTrade)}) and least on ${worst.name} (${rs(worst.perTrade)}).")
        } else if (asked) {
            s.append(" Fewer than two of these have $MIN_GROUP trades, so they are not set against each other yet.")
        }
        return s.toString()
    }

    /**
     * Boss's split for one account ([label] "Paper" or "Zerodha"), from [trades] (every owner's, the owner named on each),
     * over [span] up to [today], the part [first] asked first. ([CLOSING] is said once after every account.)
     */
    fun lines(label: String, trades: List<Trade>, span: MyNumbers.Span, today: LocalDate, first: Cut): List<String> {
        val (from, to) = MyNumbers.range(span, today)
        val inSpan = trades.filter { val d = it.closedAt.toLocalDate(); !d.isAfter(to) && (from == null || !d.isBefore(from)) }
        val own = inSpan.filter { it.owner.startsWith("Manual") }
        val mine = own.isNotEmpty()
        val used = if (mine) own else inSpan
        if (used.isEmpty()) return listOf("$label: no closed trades ${span.label}, so nothing to split.")
        val whose = if (mine) "your own trades" else "all trades (you have none of your own, so these are the bots')"
        val firstDay = used.minOf { it.closedAt }.toLocalDate()
        val lastDay = used.maxOf { it.closedAt }.toLocalDate()
        val period = if (span == MyNumbers.Span.ALL) " on record (${if (firstDay == lastDay) date(firstDay) else "${date(firstDay)} to ${date(lastDay)}"})" else " ${span.label}"
        val out = ArrayList<String>()
        out += "$label, $whose$period: ${plural(used.size, "trade")}, ${used.count { it.net > 0.5 }} won, net ${rs(used.sumOf { it.net })}."
        val order = when (first) {
            Cut.ALL, Cut.INDEX -> listOf(Cut.INDEX, Cut.KIND, Cut.SIDE)
            Cut.KIND -> listOf(Cut.KIND, Cut.SIDE, Cut.INDEX)
            Cut.SIDE -> listOf(Cut.SIDE, Cut.KIND, Cut.INDEX)
        }
        for (c in order) {
            val line = when (c) {
                Cut.INDEX -> cutLine("By index", groups(used) { index(it.symbol) }, first == c, used.size)
                Cut.KIND -> cutLine("Calls, puts and futures", groups(used) { kind(it.symbol) }, first == c, used.size)
                Cut.SIDE -> cutLine("Bought first against sold first", groups(used) { side(it) }, first == c, used.size)
                Cut.ALL -> null
            }
            if (line != null) out += line
        }
        // The four together, for options only, when at least two of them have enough trades to say.
        val options = used.filter { kind(it.symbol) == "calls" || kind(it.symbol) == "puts" }
        val both = groups(options) { (if (it.direction < 0) "sold " else "bought ") + kind(it.symbol) }
        if (both.count { it.trades >= MIN_GROUP } >= 2) out += cutLine("Options, side and kind together", both, false, options.size)!!
        return out
    }

    // ---- asked -------------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + spacedWords(text.lowercase(Locale.ENGLISH).replace("’", "").replace("'", "").replace("&", " and ")) + " "

    private const val IDX = "(nifty|banknifty|bank nifty|finnifty|fin nifty|sensex|bankex|midcap nifty|midcpnifty|bnf|futures|options)"
    private const val RIGHT = "(calls?|puts?|ce|pe|call options?|put options?|call side|put side)"
    private const val SIDE = "(buying|selling|bought|sold|writing|option buying|option selling|options buying|options selling|buyer|seller|buying options|selling options)"
    private const val PAIRED = "($IDX|$RIGHT|$SIDE)"
    private const val PERF = "(make|makes|made|making|earn|earned|lose|lost|losing|win|won|winning|do better|did better|doing better|do worse|did worse|perform|performs|performed|work|works|worked|better|best|worse|worst|money|profit|profits|profitable|record|results|paisa|paise|kamata|kamaata|kamati|kamaati|kamai|nuksan|nuksaan|banta|bante|munafa)"

    private val ASK = rx(
        // "Where do I make my money?", "where did I lose most of my money this month?"
        " where (do|did|have) i (make|lose|made|lost|earn|earned|been making|been losing) (most of |the most of |all of )?(my |the |)(most |)(money|profits?|losses|loss) " +
        // "What kind of trades work for me?", "which trades make me money?", "what type of trades suit me?"
        "| (what|which) (kind|kinds|sort|sorts|type|types) of (trades?|trading|options?|positions?) (work|works|worked|work best|works best|make|makes|made|pay|pays|suit|suits|win|wins) (for |)(me|mine) " +
        "| which (of my )?(trades|side|instruments?) (work|works|make|makes|pay|pays|suit|suits) (best |)(for |)me " +
        // "Am I better at calls or puts?", "am I any good at selling?", "am I better at BankNifty?"
        "| (am i|i m|im|i am) (better|best|worse|worst|good|any good|bad) (at|with|in|on|trading) (trading |)$PAIRED " +
        // "Which index do I make money on?", "which index am I best at?"
        "| which (index|indices|indexes) (do|did|am|have) i (\\w+ ){0,3}$PERF " +
        // "My best index", "my worst side", "my most profitable instrument"
        "| (my|mera|meri) (best|worst|most profitable|least profitable|strongest|weakest|winning|losing) (index|indices|side|instrument|kind of trade|type of trade) " +
        // "My calls vs my puts", "my buying against my selling"
        "| (my|mere) $PAIRED (vs|versus|against|or|compared to|compared with|ya) (my |mere |)$PAIRED " +
        // "Do I make more buying or selling?", "do I do better on calls than puts?"
        "| (do|did) i (make|lose|earn|do|win) (more|better|worse|most|less|best) (money |)(\\w+ ){0,3}$PAIRED (or|vs|versus|than|ya) (\\w+ ){0,2}$PAIRED " +
        // Hinglish: "call mein zyada kamata hoon ya put mein", "kis index mein paisa banta hai", "buying mein kamata hoon ya selling mein"
        "| $PAIRED (mein|me|se|par|pe) (zyada|jyada|zada|kam) (kamata|kamaata|kamati|kamaati|kamai|paisa|profit|munafa|nuksan|nuksaan|loss) " +
        "| (kis|kaun se|kaunse|konse|kaun sa|kaunsa|konsa) (index|trade|trades|side) (mein|me|se|par|pe) (mera |meri |mujhe |)(paisa|profit|kamai|munafa|nuksan|nuksaan|loss) " +
        "| (mujhe|mere liye) (kaun se|kaunse|konse|kis tarah ke) (trade|trades) (suit|kaam|fayda) ")

    /** Something else is meant: the bots', Jarvis's, an order, advice, the open book, a strategy's test, the market. */
    private val NOT = rx(" (bot|bots|arm|arms|strategy|strategies|backtest|backtested|jarvis|your|you|should|shall|recommend|suggest|advise|advice|" +
        "kharidu|khareedu|loon|lun|lu|buy now|sell now|place|cancel|open positions?|positions?|holding|holdings|theta|delta|chain|option chain|oi|" +
        "open interest|pattern|patterns|charges|brokerage|tax|what is|meaning|mean by|explain|difference between) ")

    /** Does [text] ask where his own trading makes its money? The part asked first, or null when not asked. */
    fun asked(text: String): Cut? {
        for (s in listOf(text, Ask.reading(text))) {
            val t = norm(s)
            if (NOT.containsMatchIn(t) || !ASK.containsMatchIn(t)) continue
            return when {
                rx(" $RIGHT ").containsMatchIn(t) -> Cut.KIND
                rx(" $SIDE ").containsMatchIn(t) -> Cut.SIDE
                rx(" (index|indices|indexes|nifty|banknifty|bank nifty|finnifty|fin nifty|sensex|bankex|midcap nifty|bnf) ").containsMatchIn(t) -> Cut.INDEX
                else -> Cut.ALL
            }
        }
        return null
    }

    /** The span named in [text] (as [MyNumbers] reads it); everything on record when none is said. */
    fun span(text: String): MyNumbers.Span = MyNumbers.span(text)
}
