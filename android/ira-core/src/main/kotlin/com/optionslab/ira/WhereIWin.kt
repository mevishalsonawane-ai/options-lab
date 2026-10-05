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
 * Reasoning round 26 (2026-10-05): the best and worst groups are each said with how many trades they rest on, and the gap
 * between them is checked against chance plainly - the two groups' trades shuffled between them ([chance]: every split
 * counted when there are few enough, else [SHUFFLES] fixed-seed shuffles, so the same record always gives the same
 * answer), and how often a gap at least as big as his came up is said as "N in 100". A gap chance seldom gives is said
 * so; one it often gives is said to be possibly chance. A description of his record, never a forecast. With three or more
 * groups of [MIN_GROUP] trades, picking the best and worst first would make a gap look rarer than it is, so the trades of
 * every such group are shuffled among them all and the gap between the best and worst average is what is counted
 * ([chanceAmong]).
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

    /**
     * One closed trade (or one round trip of it): [direction] +1 bought first, -1 sold first; [owner] "Manual" for Boss's
     * own; [openedAt] and [key] (the opening order's id, blank when not known) group a trade's partial exits ([grouped]).
     */
    data class Trade(
        val symbol: String, val direction: Int, val closedAt: LocalDateTime, val net: Double, val owner: String,
        val openedAt: LocalDateTime? = null, val key: String = "",
    )

    /**
     * [trades] (round trips, one per closing fill) grouped into trades, so a partial exit is not counted twice in a
     * group's trades and per-trade average (review, 2026-10-05): trips on the same symbol and side that share a non-blank
     * [Trade.key], or have the same owner and were opened in the same second, are one trade - closed at the last close,
     * the nets summed. Legs on different symbols stay apart, since every cut here is by symbol or side.
     */
    fun grouped(trades: List<Trade>): List<Trade> {
        if (trades.size < 2) return trades
        val parent = IntArray(trades.size) { it }
        fun root(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun join(a: Int, b: Int) { val ra = root(a); val rb = root(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }
        val seen = HashMap<List<Any>, Int>()
        trades.forEachIndexed { i, t ->
            val keys = ArrayList<List<Any>>()
            if (t.key.isNotBlank()) keys += listOf("k", t.symbol, t.direction, t.key)
            t.openedAt?.let { keys += listOf("s", t.symbol, t.direction, t.owner, it.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)) }
            for (k in keys) { val j = seen[k]; if (j == null) seen[k] = i else join(j, i) }
        }
        return trades.indices.groupBy { root(it) }.values.map { ix ->
            val ts = ix.map { trades[it] }
            val first = ts[0]
            Trade(first.symbol, first.direction, ts.maxOf { it.closedAt }, ts.sumOf { it.net }, first.owner,
                ts.mapNotNull { it.openedAt }.minOrNull(), first.key)
        }
    }

    data class Group(val name: String, val trades: Int, val won: Int, val net: Double) {
        val perTrade: Double get() = if (trades > 0) net / trades else 0.0
    }

    private fun rs(x: Double) = (if (x < -0.5) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun date(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " ${d.dayOfMonth} " +
        d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    // ---- the groups ----------------------------------------------------------------------------------------------

    private val INDICES = listOf("BANKNIFTY" to "BankNifty", "FINNIFTY" to "FinNifty", "MIDCPNIFTY" to "Midcap Nifty",
        "BANKEX" to "Bankex", "SENSEX" to "Sensex", "NIFTY" to "Nifty", "GOLD" to "Gold")

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

    /** Random shuffles used when there are too many splits to count them all. */
    const val SHUFFLES = 5000

    /** Every split is counted while there are at most this many. */
    private const val EXACT_MAX = 50_000L

    private fun choose(n: Int, k: Int): Long {
        var r = 1L
        for (i in 1..k) { r = r * (n - k + i) / i; if (r > EXACT_MAX) return r }
        return r
    }

    /**
     * How often chance alone gives a gap in the average per trade at least as big as the one between [a] and [b] (either
     * way round): their nets are pooled and split again into groups of the same sizes - every split when there are at
     * most [EXACT_MAX], else [SHUFFLES] shuffles from a fixed seed. A share from 0 to 1; 1 when either group is empty.
     */
    fun chance(a: List<Double>, b: List<Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 1.0
        val pool = (a + b).toDoubleArray()
        val n = pool.size
        val k = a.size
        val total = pool.sum()
        val observed = kotlin.math.abs(a.average() - b.average())
        val eps = 1e-9 * (1.0 + observed)
        fun gap(sumA: Double) = kotlin.math.abs(sumA / k - (total - sumA) / (n - k))
        if (choose(n, k) <= EXACT_MAX) {
            var hits = 0L
            var all = 0L
            fun walk(start: Int, left: Int, sum: Double) {
                if (left == 0) { all++; if (gap(sum) >= observed - eps) hits++; return }
                for (i in start..n - left) walk(i + 1, left - 1, sum + pool[i])
            }
            walk(0, k, 0.0)
            return hits.toDouble() / all
        }
        val rnd = java.util.Random(31L * n + k)
        val arr = pool.copyOf()
        var hits = 0
        repeat(SHUFFLES) {
            var sum = 0.0
            for (i in 0 until k) {
                val j = i + rnd.nextInt(n - i)
                val x = arr[i]; arr[i] = arr[j]; arr[j] = x
                sum += arr[i]
            }
            if (gap(sum) >= observed - eps) hits++
        }
        return hits.toDouble() / SHUFFLES
    }

    /**
     * How often chance alone gives a gap between the best and the worst average per trade at least as big as among
     * [groups] (three or more): every trade pooled and dealt out again into groups of the same sizes, [SHUFFLES] times
     * from a fixed seed (the same record always gives the same answer), the gap taken between whichever came out best and
     * worst each time. A share from 0 to 1; 1 with fewer than two non-empty groups.
     */
    fun chanceAmong(groups: List<List<Double>>): Double {
        val gs = groups.filter { it.isNotEmpty() }
        if (gs.size < 2) return 1.0
        val sizes = gs.map { it.size }.toIntArray()
        val pool = gs.flatten().toDoubleArray()
        val n = pool.size
        fun spread(xs: DoubleArray): Double {
            var lo = Double.POSITIVE_INFINITY
            var hi = Double.NEGATIVE_INFINITY
            var start = 0
            for (size in sizes) {
                var sum = 0.0
                for (i in start until start + size) sum += xs[i]
                val avg = sum / size
                if (avg < lo) lo = avg
                if (avg > hi) hi = avg
                start += size
            }
            return hi - lo
        }
        val observed = spread(pool)
        val eps = 1e-9 * (1.0 + observed)
        val rnd = java.util.Random(31L * n + sizes.fold(17L) { h, k -> h * 31 + k })
        val arr = pool.copyOf()
        var hits = 0
        repeat(SHUFFLES) {
            for (i in n - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val x = arr[i]; arr[i] = arr[j]; arr[j] = x
            }
            if (spread(arr) >= observed - eps) hits++
        }
        return hits.toDouble() / SHUFFLES
    }

    /**
     * A chance share said plainly: how often in 100 ("less than once in 100", "1 time in 100", "N times in 100") and what
     * that means - seldom chance at 5 in 100 or under, may still be chance ([more] would tell) at 20 or under, else could
     * well be chance. Shared with [TradesADay] (reasoning round 27).
     */
    fun chanceWords(share: Double, more: String = "trades"): Pair<String, String> {
        val inHundred = Math.round(share * 100).toInt()
        val how = if (inHundred < 1) "less than once in 100" else if (inHundred == 1) "1 time in 100" else "$inHundred times in 100"
        val verdict = when {
            share <= 0.05 -> "so chance alone seldom gives a gap that big"
            share <= 0.20 -> "so it may still be chance; more $more would tell"
            else -> "so it could well be chance"
        }
        return how to verdict
    }

    /**
     * The chance check said plainly: how often in 100 shuffles a gap this big came up, and what that means. [among] the
     * number of groups shuffled together and [pooled] their trades, when more than the two ([chanceAmong]).
     */
    fun chanceText(best: Group, worst: Group, share: Double, among: Int = 2, pooled: Int = best.trades + worst.trades): String {
        val n = pooled
        val (how, verdict) = chanceWords(share)
        return if (among > 2)
            " Is that gap more than chance? Shuffling all $n trades of those $among groups among them at random gave a gap between the best and worst at least as big $how, $verdict."
        else " Is that gap more than chance? Shuffling those $n trades between the two at random gave a gap at least as big $how, $verdict."
    }

    /** One cut's line from [trades] split by [key], or null when every trade falls in one group and the cut was not asked. */
    private fun cutLine(title: String, trades: List<Trade>, key: (Trade) -> String, asked: Boolean): String? {
        val gs = groups(trades, key)
        if (gs.size < 2) {
            if (!asked) return null
            val only = gs.firstOrNull() ?: return null
            return "$title: all ${plural(trades.size, "trade")} were ${only.name}, so there is nothing to set beside them."
        }
        val s = StringBuilder("$title: ").append(gs.joinToString("; ") { groupText(it) }).append('.')
        val enough = gs.filter { it.trades >= MIN_GROUP }
        if (enough.size >= 2) {
            val best = enough.maxBy { it.perTrade }
            val worst = enough.minBy { it.perTrade }
            if (best !== worst) {
                s.append(" A trade made most on ${best.name} (${rs(best.perTrade)}, on ${plural(best.trades, "trade")}) and least on " +
                    "${worst.name} (${rs(worst.perTrade)}, on ${plural(worst.trades, "trade")}).")
                if (enough.size == 2) {
                    val a = trades.filter { key(it) == best.name }.map { it.net }
                    val b = trades.filter { key(it) == worst.name }.map { it.net }
                    s.append(chanceText(best, worst, chance(a, b)))
                } else {
                    // Three or more: shuffled among every group with enough trades, so picking the extremes is allowed for.
                    val nets = enough.map { g -> trades.filter { key(it) == g.name }.map { it.net } }
                    s.append(chanceText(best, worst, chanceAmong(nets), enough.size, enough.sumOf { it.trades }))
                }
            }
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
        val inSpan = grouped(trades).filter { val d = it.closedAt.toLocalDate(); !d.isAfter(to) && (from == null || !d.isBefore(from)) }
        val own = inSpan.filter { it.owner.startsWith("Manual") }
        val mine = own.isNotEmpty()
        val used = if (mine) own else inSpan
        if (used.isEmpty()) return listOf("$label: no closed trades ${span.label}, so nothing to split.")
        val whose = if (mine) "your own trades" else "all trades (you have none of your own, so these are the bots')"
        val firstDay = used.minOf { it.closedAt }.toLocalDate()
        val lastDay = used.maxOf { it.closedAt }.toLocalDate()
        val period = if (span == MyNumbers.Span.ALL) " on record (${if (firstDay == lastDay) date(firstDay) else "${date(firstDay)} to ${date(lastDay)}"})" else " ${span.label}"
        val out = ArrayList<String>()
        out += "$label, $whose$period: ${plural(used.size, "closed trade")}, ${used.count { it.net > 0.5 }} won, net ${rs(used.sumOf { it.net })}."
        val order = when (first) {
            Cut.ALL, Cut.INDEX -> listOf(Cut.INDEX, Cut.KIND, Cut.SIDE)
            Cut.KIND -> listOf(Cut.KIND, Cut.SIDE, Cut.INDEX)
            Cut.SIDE -> listOf(Cut.SIDE, Cut.KIND, Cut.INDEX)
        }
        for (c in order) {
            val line = when (c) {
                Cut.INDEX -> cutLine("By index", used, { index(it.symbol) }, first == c)
                Cut.KIND -> cutLine("Calls, puts and futures", used, { kind(it.symbol) }, first == c)
                Cut.SIDE -> cutLine("Bought first against sold first", used, { side(it) }, first == c)
                Cut.ALL -> null
            }
            if (line != null) out += line
        }
        // The four together, for options only, when at least two of them have enough trades to say.
        val options = used.filter { kind(it.symbol) == "calls" || kind(it.symbol) == "puts" }
        val sideKind: (Trade) -> String = { (if (it.direction < 0) "sold " else "bought ") + kind(it.symbol) }
        if (groups(options, sideKind).count { it.trades >= MIN_GROUP } >= 2) out += cutLine("Options, side and kind together", options, sideKind, false)!!
        return out
    }

    // ---- asked -------------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + spacedWords(text.lowercase(Locale.ENGLISH).replace("’", "").replace("'", "").replace("&", " and ")) + " "

    private const val IDX = "(nifty|banknifty|bank nifty|finnifty|fin nifty|sensex|bankex|midcap nifty|midcpnifty|bnf|futures|options)"
    private const val RIGHT = "(calls?|puts?|ce|pe|call options?|put options?|call side|put side)"
    private const val SIDE = "(buying|selling|bought|sold|writing|option buying|option selling|options buying|options selling|buyer|seller|buying options|selling options)"
    private const val PAIRED = "($IDX|$RIGHT|$SIDE)"
    private const val PERF = "(make|makes|made|making|earn|earned|lose|lost|losing|win|won|winning|do better|did better|doing better|do worse|did worse|perform|performs|performed|work|works|worked|better|best|worse|worst|money|profit|profits|profitable|record|results|paisa|paise|kamata|kamaata|kamati|kamaati|kamai|nuksan|nuksaan|banta|bante|munafa)"

    /**
     * Asked loosely - "where did I lose money", "what did I make money on", "which index did I lose money on": his record,
     * unless it is tied to today or yesterday (or "where am I losing money" right now), which is the account's P&L.
     */
    private val LOOSE = rx(
        // "Where do I make my money?", "where did I lose most of my money this month?", "(tell me) where I make my money"
        " where ((do|did|have) )?i (make|lose|made|lost|earn|earned|been making|been losing) (most of |the most of |all of )?(my |the |)(most |)(money|profits?|losses|loss) " +
        // Round 22: "where have I been making money?", "where do I lose the most?", "what do I make money on?"
        "| where have i been (making|losing|earning) (most of |the most of |)(my |the |)(money|profits?|losses) " +
        "| where (do|did) i (make|lose|made|lost|earn|earned) (the )?most " +
        "| what (do|did) i (make|lose|made|lost|earn|earned) (the )?(most )?(my )?(money|profits?) (on|in|from|with) " +
        // "Which index do I make money on?", "which index am I best at?"
        "| which (index|indices|indexes) (do|did|am|have) i (\\w+ ){0,3}$PERF " +
        // Round 23: "where do I win?", "where do I win the most?"; Hinglish "main kahan kamata hoon", "kidhar se paisa banta hai mera"
        "| where (do|did|have) i (win|won|been winning)( (the )?most| most often| more)? $" +
        "| (kahan|kaha|kidhar|kis cheez) (se |par |pe |mein |me |)(main |mai |)(kamata|kamaata|kamati|kamaati|kamai karta|kamai karti|paisa kamata|paisa banata) (hoon|hu|hun|hai) " +
        "| (kahan|kaha|kidhar) (se |)(mera |meri |)(paisa|profit|kamai|munafa) (banta|bante|aata|aati|hota) (hai )?(mera |)")
    /** Today, yesterday or right now: the account's P&L ("where did I lose money today", "where am I losing money"). */
    private val NOW = rx(" (today|todays|aaj|yesterday|yesterdays|right now|abhi|this session) | where am i (making|losing|earning) ")
    /** "How do I do on BankNifty?" - his record only with no span named; with one it is the P&L or the history (round 22 review). */
    private val HOW_ON = rx(" how (do|did|have) i (do|done|perform|performed|fare|fared) (on|in|with|at|trading) $PAIRED ")

    private val ASK = rx(
        // Round 22: "which index works best for me?", "is selling working for me?"
        " which (index|indices|indexes|side|instrument|instruments) (works?|pays?|suits?) (best |)(for |)me " +
        "| (is|are) $PAIRED (working|paying|paying off) (out )?(for|with) me " +
        // "What kind of trades work for me?", "which trades make me money?", "what type of trades suit me?"
        "| (what|which) (kind|kinds|sort|sorts|type|types) of (trades?|trading|options?|positions?) (work|works|worked|work best|works best|make|makes|made|pay|pays|suit|suits|win|wins) (for |)(me|mine) " +
        "| which (of my )?(trades|side|instruments?) (work|works|make|makes|pay|pays|suit|suits) (best |)(for |)me " +
        // "Am I better at calls or puts?", "am I any good at selling?", "am I better at BankNifty?"
        "| (am i|i m|im|i am) (better|best|worse|worst|good|any good|bad) (at|with|in|on|trading) (trading |)$PAIRED " +
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

    /**
     * The chance line asked of (round 23): "is the gap between my best and worst real?", "is the difference between my calls and
     * puts just luck?", "is my edge on BankNifty real?", "best aur worst ka fark luck hai kya" - his record with its chance check.
     */
    private val CHANCE = rx(
        " is (the|my|this|that) (gap|difference) between my (\\w+ ){1,6}(real|just luck|luck|only luck|chance|just chance|random|significant|statistically significant|meaningful|more than chance|more than luck) " +
        "| is my (edge|record|win rate|winning|advantage|outperformance|lead) (on|in|with|at|trading) $PAIRED (real|just luck|luck|chance|just chance|random|significant|more than chance|more than luck) " +
        "| (mere |meri |)(best aur worst|calls? aur puts?|ce aur pe|buying aur selling|selling aur buying) (ka|ki|mein|me) (fark|farak|difference|gap) (sirf |bas |)(luck|chance|kismat|tukka|asli|real|sach) ")

    /** The wake word and "can you tell me", said first or last: left out before [NOT] (round 22 review). */
    private val POLITE = rx("^ ((hey|ok|okay|hi) )?(jarvis|ira|boss) | (can|could|would|will) you (please )?(tell|show|let) me( know)? | please | (jarvis|ira|boss) $")

    /** Something else is meant: the bots', Jarvis's, an order, advice, the open book, a strategy's test, the market. */
    private val NOT = rx(" (bot|bots|arm|arms|strategy|strategies|backtest|backtested|jarvis|your|you|should|shall|recommend|suggest|advise|advice|" +
        "kharidu|khareedu|loon|lun|lu|buy now|sell now|place|cancel|open positions?|positions?|holding|holdings|theta|delta|chain|option chain|oi|" +
        "open interest|pattern|patterns|charges|brokerage|tax|what is|meaning|mean by|explain|difference between) ")

    /** Does [text] ask where his own trading makes its money? The part asked first, or null when not asked. */
    fun asked(text: String): Cut? {
        for (s in listOf(text, Ask.reading(text))) {
            // "Jarvis, can you tell me where I make my money?" - the wake word and the asking are not Jarvis's own trades.
            var t = norm(s)
            while (true) { val u = " " + POLITE.replace(t, " ").trim() + " "; if (u == t) break; t = u }
            // (The chance line asked of names "the difference between" his own groups - not a definition.)
            val chance = CHANCE.containsMatchIn(t)
            if (NOT.containsMatchIn(if (chance) t.replace(" difference between ", " gap between ") else t)) continue
            val loose = LOOSE.containsMatchIn(t) && !NOW.containsMatchIn(t)
            val howOn = HOW_ON.containsMatchIn(t) && !NOW.containsMatchIn(t) && MyNumbers.span(s) == MyNumbers.Span.ALL
            if (!loose && !howOn && !chance && !ASK.containsMatchIn(t)) continue
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
