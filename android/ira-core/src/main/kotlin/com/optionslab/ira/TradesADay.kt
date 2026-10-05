package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * How Boss's own days went by how many trades he took (usefulness round 31, 2026-10-05): "do I do better when I trade
 * less?", "do I lose more on days I trade a lot?", "how many trades a day work best for me?", "how does my first trade
 * of the day do?", "do my later trades lose?", "kam trade karne pe zyada kamata hoon kya", "din ka pehla trade kaisa
 * jaata hai".
 *
 * [Headroom] says the trades left today against the guard and [WeekReview] flags one busy day in a week; nothing set his
 * quiet days beside his busy ones over his whole record, or his first trade of a day beside his third. Here, from his own
 * closed trades in the trade book (the bots' only when he has none, and said so), each account apart (paper is never
 * added to real money), over the span he names (this or last week, this or last month; everything on record when none is
 * said):
 *  - his trading days grouped by how many trades he opened that day (1, 2, 3, 4 to 5, 6 or more): the days, the green
 *    days, the net and what a day made on average - a group compared only with [MIN_DAYS] days;
 *  - his trades by their place in the day (the 1st, 2nd, 3rd, 4th or later he opened that day): trades, wins, net and
 *    what a trade made on average - compared only with [MIN_TRADES] trades.
 * The part asked comes first. A trade is counted on the day it was opened.
 *
 * Review (2026-10-05): the trade book makes one round trip per closing fill, so "buy 2 lots, sell 1 and 1" is two trips
 * and a strangle's legs are a trip each. Trips are grouped into one trade before anything is counted ([grouped]): trips
 * sharing an opening order ([Trade.key]) or opened by the same owner in the same second are one trade, their nets summed.
 *
 * Reasoning round 27 (2026-10-05): when a best and a worst group are named, the gap between them is checked against chance
 * as [WhereIWin] does it ([WhereIWin.chance] for two groups, [WhereIWin.chanceAmong] for three or more) - the days' nets
 * (or the trades' nets, by place) of the groups compared are pooled and dealt out again into groups of the same sizes,
 * from a fixed seed, and how often a gap at least as big came up is said as "N in 100" with the units it rests on.
 *
 * Facts from his own record only - never a forecast, never a number of trades to take and never a limit set; nothing here
 * places, changes or arms anything. His account, so never on a locked phone. Pure.
 */
object TradesADay {
    const val LOCKED = "Your trades stay out of it on a locked phone, Boss - unlock it for that."

    /** A group of days is set beside the others only with at least this many days. */
    const val MIN_DAYS = 3

    /** A place in the day is set beside the others only with at least this many trades. */
    const val MIN_TRADES = 3

    /** Said once after every account's lines. */
    const val CLOSING = "That's how your own record went by how many trades you took, Boss - not a forecast and not a number to take; how many you take is your call."

    /** The part asked first: [DAYS] quiet days against busy ones, [PLACE] the 1st trade of a day against later ones. */
    enum class Part { DAYS, PLACE }

    /**
     * One closed trade (or one round trip of it); [owner] "Manual" for Boss's own; [key] the opening order's id when known
     * (blank otherwise) - trips with the same key, or opened by the same owner in the same second, are one trade ([grouped]).
     */
    data class Trade(val openedAt: LocalDateTime, val closedAt: LocalDateTime, val net: Double, val owner: String, val key: String = "")

    /**
     * [trades] (round trips) grouped into trades: trips sharing a non-blank [Trade.key], or with the same owner and opened in
     * the same second, are one trade - opened at the first opening, closed at the last close, the nets summed. In the order
     * first opened. A partial exit and a two-leg entry are each one trade.
     */
    fun grouped(trades: List<Trade>): List<Trade> {
        if (trades.size < 2) return trades
        val parent = IntArray(trades.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun join(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb) }
        val byKey = HashMap<String, Int>()
        val bySecond = HashMap<Pair<String, LocalDateTime>, Int>()
        trades.forEachIndexed { i, t ->
            if (t.key.isNotBlank()) byKey[t.key]?.let { join(it, i) } ?: run { byKey[t.key] = i }
            val sec = t.owner to t.openedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            bySecond[sec]?.let { join(it, i) } ?: run { bySecond[sec] = i }
        }
        return trades.indices.groupBy { find(it) }.values.map { ix ->
            val ts = ix.map { trades[it] }
            val first = ts.minBy { it.openedAt }
            Trade(first.openedAt, ts.maxOf { it.closedAt }, ts.sumOf { it.net }, first.owner, first.key)
        }.sortedBy { it.openedAt }
    }

    data class Group(val name: String, val count: Int, val green: Int, val net: Double) {
        val average: Double get() = if (count > 0) net / count else 0.0
    }

    private fun rs(x: Double) = (if (x < -0.5) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun date(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " ${d.dayOfMonth} " +
        d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    /** The day-size bucket for [n] trades in a day. */
    fun bucket(n: Int): String = when {
        n <= 1 -> "1-trade days"
        n == 2 -> "2-trade days"
        n == 3 -> "3-trade days"
        n <= 5 -> "4-5-trade days"
        else -> "days of 6 or more"
    }

    private val BUCKETS = listOf("1-trade days", "2-trade days", "3-trade days", "4-5-trade days", "days of 6 or more")

    /** The place-in-the-day name for the [i]th (1-based) trade opened that day. */
    fun place(i: Int): String = when (i) { 1 -> "1st trades"; 2 -> "2nd trades"; 3 -> "3rd trades"; else -> "4th and later" }

    private val PLACES = listOf("1st trades", "2nd trades", "3rd trades", "4th and later")

    /** [trades] by the day they were opened, each day in the order opened. */
    private fun byDay(trades: List<Trade>): Map<LocalDate, List<Trade>> =
        trades.groupBy { it.openedAt.toLocalDate() }.mapValues { (_, ts) -> ts.sortedBy { it.openedAt } }

    /** Days grouped by how many trades were opened on them, in [BUCKETS]' order. */
    fun dayGroups(trades: List<Trade>): List<Group> {
        val days = byDay(trades).values
        return BUCKETS.mapNotNull { b ->
            val ds = days.filter { bucket(it.size) == b }
            if (ds.isEmpty()) null else {
                val nets = ds.map { d -> d.sumOf { it.net } }
                Group(b, ds.size, nets.count { it > 0.5 }, nets.sum())
            }
        }
    }

    /** Trades grouped by their place in the day, in [PLACES]' order. */
    fun placeGroups(trades: List<Trade>): List<Group> {
        val placed = byDay(trades).values.flatMap { d -> d.mapIndexed { i, t -> place(i + 1) to t } }
        return PLACES.mapNotNull { p ->
            val ts = placed.filter { it.first == p }.map { it.second }
            if (ts.isEmpty()) null else Group(p, ts.size, ts.count { it.net > 0.5 }, ts.sumOf { it.net })
        }
    }

    /**
     * The chance check for the best and worst of [enough] (each with its own [nets]): "Is that gap more than chance?
     * Shuffling those 6 days between the two at random ...". [unit] "day" or "trade". Deterministic.
     */
    fun chanceLine(enough: List<Group>, nets: Map<String, List<Double>>, unit: String): String {
        val lists = enough.map { nets[it.name].orEmpty() }
        val n = lists.sumOf { it.size }
        return if (enough.size == 2) {
            val (how, verdict) = WhereIWin.chanceWords(WhereIWin.chance(lists[0], lists[1]), unit + "s")
            " Is that gap more than chance? Shuffling those ${plural(n, unit)} between the two at random gave a gap at least as big $how, $verdict."
        } else {
            val (how, verdict) = WhereIWin.chanceWords(WhereIWin.chanceAmong(lists), unit + "s")
            " Is that gap more than chance? Shuffling all ${plural(n, unit)} of those ${enough.size} groups among them at random gave a gap between the best and worst at least as big $how, $verdict."
        }
    }

    /** Each day group's day nets, by [bucket] name. */
    private fun dayNets(trades: List<Trade>): Map<String, List<Double>> =
        byDay(trades).values.groupBy({ bucket(it.size) }, { d -> d.sumOf { it.net } })

    /** Each place group's trade nets, by [place] name. */
    private fun placeNets(trades: List<Trade>): Map<String, List<Double>> =
        byDay(trades).values.flatMap { d -> d.mapIndexed { i, t -> place(i + 1) to t.net } }.groupBy({ it.first }, { it.second })

    private fun daysLine(gs: List<Group>, asked: Boolean, nets: Map<String, List<Double>>): String? {
        if (gs.size < 2) {
            if (!asked) return null
            val only = gs.firstOrNull() ?: return null
            return "By trades in a day: every day on record was one of your ${only.name}, so there is nothing to set beside them."
        }
        val s = StringBuilder("By trades in a day: ").append(gs.joinToString("; ") { g ->
            "${g.name} ${g.count}, ${g.green} green, ${rs(g.net)}" +
                if (g.count >= MIN_DAYS) " (${rs(g.average)} a day)" else " (too few to judge)"
        }).append('.')
        val enough = gs.filter { it.count >= MIN_DAYS }
        if (enough.size >= 2) {
            val best = enough.maxBy { it.average }
            val worst = enough.minBy { it.average }
            if (best !== worst) {
                s.append(" A day made most on your ${best.name} (${rs(best.average)}, over ${plural(best.count, "day")}) and least on your " +
                    "${worst.name} (${rs(worst.average)}, over ${plural(worst.count, "day")}).")
                s.append(chanceLine(enough, nets, "day"))
            }
        } else if (asked) s.append(" Fewer than two of these have $MIN_DAYS days, so they are not set against each other yet.")
        return s.toString()
    }

    private fun placeLine(gs: List<Group>, asked: Boolean, nets: Map<String, List<Double>>): String? {
        if (gs.size < 2) {
            if (!asked) return null
            return "By place in the day: you never took more than one trade in a day, so every trade was a day's first."
        }
        val s = StringBuilder("By place in the day: ").append(gs.joinToString("; ") { g ->
            "${g.name} ${g.count}, ${g.green} won, ${rs(g.net)}" +
                if (g.count >= MIN_TRADES) " (${rs(g.average)} a trade)" else " (too few to judge)"
        }).append('.')
        val enough = gs.filter { it.count >= MIN_TRADES }
        if (enough.size >= 2) {
            val best = enough.maxBy { it.average }
            val worst = enough.minBy { it.average }
            if (best !== worst) {
                s.append(" A trade made most as one of your ${best.name} (${rs(best.average)}, on ${plural(best.count, "trade")}) and least as one of your " +
                    "${worst.name} (${rs(worst.average)}, on ${plural(worst.count, "trade")}).")
                s.append(chanceLine(enough, nets, "trade"))
            }
        } else if (asked) s.append(" Fewer than two of these have $MIN_TRADES trades, so they are not set against each other yet.")
        return s.toString()
    }

    /**
     * Boss's days by trade count for one account ([label] "Paper" or "Zerodha"), from [trades] (every owner's, the owner
     * named on each), over [span] up to [today], the part [first] asked first. ([CLOSING] is said once after every account.)
     */
    fun lines(label: String, trades: List<Trade>, span: MyNumbers.Span, today: LocalDate, first: Part): List<String> {
        val (from, to) = MyNumbers.range(span, today)
        val inSpan = grouped(trades).filter { val d = it.openedAt.toLocalDate(); !d.isAfter(to) && (from == null || !d.isBefore(from)) }
        val own = inSpan.filter { it.owner.startsWith("Manual") }
        val mine = own.isNotEmpty()
        val used = if (mine) own else inSpan
        if (used.isEmpty()) return listOf("$label: no closed trades ${span.label}, so nothing to count by day.")
        val whose = if (mine) "your own trades" else "all trades (you have none of your own, so these are the bots')"
        val days = byDay(used)
        val firstDay = days.keys.min()
        val lastDay = days.keys.max()
        val period = if (span == MyNumbers.Span.ALL) " on record (${if (firstDay == lastDay) date(firstDay) else "${date(firstDay)} to ${date(lastDay)}"})" else " ${span.label}"
        val sizes = days.values.map { it.size }.sorted()
        val middle = sizes[sizes.size / 2]
        val out = ArrayList<String>()
        out += "$label, $whose$period: ${plural(used.size, "closed trade")} over ${plural(days.size, "trading day")}, " +
            "${plural(middle, "trade")} on the middle day and ${sizes.last()} at most, net ${rs(used.sumOf { it.net })}."
        val order = if (first == Part.PLACE) listOf(Part.PLACE, Part.DAYS) else listOf(Part.DAYS, Part.PLACE)
        for (p in order) {
            val line = if (p == Part.DAYS) daysLine(dayGroups(used), first == p, dayNets(used)) else placeLine(placeGroups(used), first == p, placeNets(used))
            if (line != null) out += line
        }
        return out
    }

    // ---- asked -------------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + spacedWords(text.lowercase(Locale.ENGLISH).replace("’", "").replace("'", "")) + " "

    private const val PERF = "(better|worse|best|worst|more|less|most|well|badly|good|bad|money|profit|profits|loss|losses|win|wins|lose|make|made)"
    private const val FEW = "(less|fewer|more|lots of|a lot of|many|few|lots|a lot|too many|too much|heavily|a few)"

    /** Quiet days against busy ones. */
    private val DAYS = rx(
        // "Do I do better when I trade less?", "do I lose more on days I trade a lot?", "do I make more money when I take fewer trades?"
        " (do|did|have) i (do|did|done|make|made|earn|earned|lose|lost|win|won|perform|performed|trade|traded) (\\w+ ){0,2}$PERF (\\w+ ){0,2}(when|on days|on the days|the days|days|if) (when |that |)i (trade|take|took|traded|make|made|place|placed) (\\w+ ){0,2}$FEW( trades?)? " +
        // "Does trading more hurt me?", "does trading less help me?", "do more trades hurt me?"
        "| (does|did|do) (trading|taking) (more|less|fewer|a lot|too much|lots) (trades )?(hurt|help|cost|work for|pay|pay off for) me " +
        "| (do|does|did) (more|fewer|extra) trades (hurt|help|cost|make|lose) (me|my) " +
        // Round 23: "do fewer trades work better for me?", "is it better for me to take fewer trades?", "are my busy days worse?",
        // "how much did I make when I traded less?", "my P&L on days with few trades"
        "| (do|does|did) (more|fewer|less|extra) trades (work|works|worked|go|went|do|did|pay|pays) (out )?(better|worse|best|well|badly) (for me|with me) " +
        "| (is it|would it be|was it) (better|worse) (for me )?(to|if i) (take|took|do|did|make|made|place|placed|trade|traded) (\\w+ ){0,1}$FEW( trades?)? " +
        "| (are|were|have been) my (busy|heavy|quiet|light|slow) (trading )?days (better|worse|good|bad|my best|my worst|profitable|losing|any good) " +
        // Review: up to three words between ("how much did i make last week when i traded less") - the span is read apart.
        "| how much (do|did|have) i (make|made|lose|lost|earn|earned) (\\w+ ){0,3}(on days |on the days |the days |when |on days when |if )(when |that |)i (trade|traded|take|took|place|placed) (\\w+ ){0,2}$FEW( trades?)? " +
        "| my (p l|p and l|pnl|results|profit|profits|returns|record) on (the )?days (with|when i take|when i took|i take|i took|i trade|i traded) (\\w+ ){0,1}$FEW( trades?)? " +
        // "How many trades a day work best for me?", "my best number of trades a day", "how many trades a day suit me?"
        "| how many trades (a|per|in a|each) day (work|works|worked|is|are|suit|suits|pay|pays) (best |well |)(for |)me " +
        "| (my|what s my|whats my|what is my) (best|ideal|most profitable|winning) (number of trades|trade count|number of trades a day|number of trades per day) " +
        // "My results by number of trades", "my days by trade count", "my quiet days vs my busy days"
        "| my (results|days|record|p and l|pnl) by (the )?(number of trades|trade count|trades a day|trades per day|how many trades) " +
        "| my (quiet|light|slow|few trade) days (vs|versus|against|or|compared to|compared with) (my )?(busy|heavy|many trade) days " +
        "| my (busy|heavy) days (vs|versus|against|or|compared to|compared with) (my )?(quiet|light|slow) days " +
        "| (how do|how did|how have) my (busy|heavy|quiet|light) days (go|gone|do|done|went|turn out) " +
        // Hinglish: "kam trade karne pe zyada kamata hoon kya", "zyada trade karne se nuksan hota hai kya", "kitne trade din mein best hain mere liye"
        "| (kam|zyada|jyada|zada) (trade|trades) (karne|karu|karun|karta|karke) (pe|par|se|mein|me) (\\w+ ){0,2}(kamata|kamaata|kamai|profit|munafa|nuksan|nuksaan|loss|fayda) " +
        "| (kitne|kitni) (trade|trades) (din mein|din me|ek din mein|roz) (best|sahi|theek|accha|achha) (hain|hai|rehte|rahte) (mere liye|mujhe|meri liye) ")

    /** The first trade of the day against the later ones. */
    private val PLACE = rx(
        // "How does my first trade of the day do?", "how do my first trades go?", "is my first trade my best?"
        " (how (do|does|did|have|has)|is|are) my (first|1st|second|2nd|third|3rd|fourth|4th|later|later in the day) (trade|trades) (of (the|a|each) day |in (the|a) day |of the session |each day |)(do|go|gone|done|perform|went|turn out|work|my best|my worst|better|worse|the best|the worst|any good|win|lose|losing|winning) " +
        // "Do my later trades lose?", "do my second trades do worse?", "do I lose after my second trade?"
        "| (do|did) my (later|second|2nd|third|3rd|fourth|4th|first|1st) (trade|trades) (\\w+ ){0,2}(lose|lost|win|won|do better|do worse|make money|lose money|go wrong|go right) " +
        "| (do|did) i (lose|win|make|earn) (\\w+ ){0,2}(after|past|beyond) my (first|1st|second|2nd|third|3rd|fourth|4th) (trade|trades) " +
        "| my (first|1st) (trade|trades) (of the day |each day |in the day |)(vs|versus|against|compared to|compared with) (my )?(later|other|second|2nd|rest|remaining) " +
        "| my (results|trades|record) by (place|order|position|number) in the day " +
        // Hinglish: "din ka pehla trade kaisa jaata hai", "mera pehla trade kaisa rehta hai"
        "| (din ka |mera |meri |)(pehla|pahla|doosra|dusra|teesra|tisra|first|1st|second|2nd) (trade) (of the day |din ka )?(kaisa|kaise|kaisi) (jaata|jata|rehta|rahta|hota|jaate|rehte) ")

    /** The wake word and "can you tell me", said first or last. */
    private val POLITE = rx("^ ((hey|ok|okay|hi) )?(jarvis|ira|boss) | (can|could|would|will) you (please )?(tell|show|let) me( know)? | please | (jarvis|ira|boss) $")

    /** Something else is meant: the bots', Jarvis's, the guard's limit, advice, today alone or the market. */
    private val NOT = rx(" (bot|bots|arm|arms|strategy|strategies|backtest|backtested|jarvis|your|you|should|shall|recommend|suggest|advise|advice|" +
        "limit|limits|left|allowed|guard|max|maximum|overtrading|over trading|today|todays|aaj|yesterday|right now|abhi|nifty|banknifty|sensex|market|" +
        "charges|brokerage|tax|what is|meaning|explain) ")

    /**
     * The size of a trade, not the number of trades ("when i took more lots", "on days i trade bigger size"): never asked
     * here (review, 2026-10-05).
     */
    private val SIZE = rx(" (more|less|fewer|bigger|smaller|extra|many|few|lots of|a lot of|a few|too many|too much|heavily|higher|lower|lots|a lot) " +
        "(\\w+ ){0,1}(lots|lot|qty|quantity|quantities|size|sizes|sized|sizing) ")

    /** Does [text] ask how his days went by how many trades he took? The part asked first, or null when not asked. */
    fun asked(text: String): Part? {
        for (s in listOf(text, Ask.reading(text))) {
            var t = norm(s)
            while (true) { val u = " " + POLITE.replace(t, " ").trim() + " "; if (u == t) break; t = u }
            if (NOT.containsMatchIn(t) || SIZE.containsMatchIn(t)) continue
            val place = PLACE.containsMatchIn(t)
            val days = DAYS.containsMatchIn(t)
            if (!place && !days) continue
            return if (place && !days) Part.PLACE else Part.DAYS
        }
        return null
    }

    /** The span named in [text] (as [MyNumbers] reads it); everything on record when none is said. */
    fun span(text: String): MyNumbers.Span = MyNumbers.span(text)
}
