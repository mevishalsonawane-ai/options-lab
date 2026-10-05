package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * How Boss trades right after a loss (usefulness round 32, 2026-10-05): "how do I trade after a loss?", "do I revenge
 * trade?", "how does my next trade do after a losing one?", "do I chase my losses?", "do I get careless after a win?",
 * "loss ke baad mera agla trade kaisa jaata hai", "kya main revenge trade karta hoon".
 *
 * [PreTrade] says one line about trades soon after a loss just before an order and [WeekReview] counts this week's; nothing
 * answered the question itself over his record. Here, from his own closed trades in the trade book (the bots' only when he
 * has none, and said so), each account apart (paper is never added to real money), over the span he names (this or last
 * week, this or last month; everything on record when none is said), each trade is placed by the trade that closed last
 * before it opened, the same day:
 *  - after a losing trade, against after a winning one: trades, wins, the win rate, net and what a trade made on average
 *    (compared only with [MIN_TRADES] on each side, and the gap checked against chance as [WhereIWin] does it);
 *  - soon after a loss (within [PreTrade.AFTER_LOSS_MINUTES] minutes) and after two losses in a row;
 *  - how long he waited, the middle gap, after a loss against after a win;
 *  - a day's first trades (nothing closed before them that day) for reference.
 * The side asked comes first in the lead, which carries the key fact. Trips are grouped into trades as [TradesADay] does.
 *
 * Facts from his own record only - never a forecast, never a rule set and never a trade blocked; nothing here places,
 * changes or arms anything. His account, so never on a locked phone. Pure.
 */
object AfterLoss {
    const val LOCKED = "Your trades stay out of it on a locked phone, Boss - unlock it for that."

    /** A side (after a loss, after a win) is set beside the other only with at least this many trades. */
    const val MIN_TRADES = 5

    /** Said once after every account's lines. */
    const val CLOSING = "That's how your own trades went after a loss and after a win, Boss - not a forecast and not a rule; how you trade after one is your call."

    /** The side asked first: [LOSS] after a losing trade, [WIN] after a winning one. */
    enum class Side { LOSS, WIN }

    data class Group(val count: Int, val won: Int, val net: Double) {
        val average: Double get() = if (count > 0) net / count else 0.0
        val rate: Int get() = if (count > 0) Math.round(100.0 * won / count).toInt() else 0
    }

    /** Each trade with what came before it the same day. */
    data class Placed(val trade: TradesADay.Trade, val before: TradesADay.Trade?, val beforeThat: TradesADay.Trade?) {
        val gapMinutes: Long? get() = before?.let { Duration.between(it.closedAt, trade.openedAt).toMinutes() }
    }

    private fun rs(x: Double) = (if (x < -0.5) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun date(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " ${d.dayOfMonth} " +
        d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    private fun lost(t: TradesADay.Trade?) = t != null && t.net < -0.5
    private fun won(t: TradesADay.Trade?) = t != null && t.net > 0.5

    /**
     * [trades] (already grouped) each with the trade that closed last at or before it opened, the same day, and the one
     * before that; trades still open at its opening never count as "before". The "before" trades are ALL trades closed
     * that day, a trade carried overnight and closed that morning included (not only those opened that day).
     */
    fun placed(trades: List<TradesADay.Trade>): List<Placed> {
        val byClose = trades.groupBy { it.closedAt.toLocalDate() }
        return trades.sortedBy { it.openedAt }.map { t ->
            val closed = byClose[t.openedAt.toLocalDate()].orEmpty()
                .filter { it !== t && !it.closedAt.isAfter(t.openedAt) }
                .sortedBy { it.closedAt }
            Placed(t, closed.lastOrNull(), closed.getOrNull(closed.size - 2))
        }
    }

    fun group(ps: List<Placed>): Group = Group(ps.size, ps.count { it.trade.net > 0.5 }, ps.sumOf { it.trade.net })

    private fun side(g: Group, what: String): String =
        if (g.count == 0) "you never opened a trade the same day $what"
        else "$what you won ${g.won} of ${g.count} (${g.rate}%), ${rs(g.average)} a trade"

    private fun median(xs: List<Long>): Long? = if (xs.isEmpty()) null else xs.sorted()[(xs.size - 1) / 2]

    /**
     * Boss's trades after a loss and after a win for one account ([label] "Paper" or "Zerodha"), from [trades] (every
     * owner's round trips, the owner named on each), over [span] up to [today], the side [first] asked first. ([CLOSING]
     * is said once after every account.)
     */
    fun lines(label: String, trades: List<TradesADay.Trade>, span: MyNumbers.Span, today: LocalDate, first: Side): List<String> {
        val (from, to) = MyNumbers.range(span, today)
        val inSpan = TradesADay.grouped(trades).filter { val d = it.openedAt.toLocalDate(); !d.isAfter(to) && (from == null || !d.isBefore(from)) }
        val own = inSpan.filter { it.owner.startsWith("Manual") }
        val mine = own.isNotEmpty()
        val used = if (mine) own else inSpan
        if (used.isEmpty()) return listOf("$label: no closed trades ${span.label}, so nothing to say about after a loss.")
        val whose = if (mine) "your own trades" else "all trades (you have none of your own, so these are the bots')"
        val ps = placed(used)
        val afterLoss = ps.filter { lost(it.before) }
        val afterWin = ps.filter { won(it.before) }
        val gl = group(afterLoss)
        val gw = group(afterWin)
        val lossPart = side(gl, "after a losing trade")
        val winPart = side(gw, "after a winning one")
        val out = ArrayList<String>()
        out += "$label, $whose ${span.label}: " + (if (first == Side.WIN) "${side(gw, "after a winning trade")}; ${side(gl, "after a losing one")}"
            else "$lossPart; $winPart") + "."

        val days = used.map { it.openedAt.toLocalDate() }
        val firstDay = days.min()
        val lastDay = days.max()
        val period = if (firstDay == lastDay) date(firstDay) else "${date(firstDay)} to ${date(lastDay)}"
        val s = StringBuilder("That is ${plural(used.size, "closed trade")} from $period")
        if (gl.count >= MIN_TRADES && gw.count >= MIN_TRADES) {
            val worse = if (gl.average < gw.average) "after a loss" else "after a win"
            s.append(", and a trade made less $worse.")
            val (how, verdict) = WhereIWin.chanceWords(WhereIWin.chance(afterLoss.map { it.trade.net }, afterWin.map { it.trade.net }), "trades")
            s.append(" Is that gap more than chance? Shuffling those ${plural(gl.count + gw.count, "trade")} between the two at random gave a gap at least as big $how, $verdict.")
        } else s.append("; with fewer than $MIN_TRADES trades on one side, the two are not set against each other yet.")
        out += s.toString()

        val soon = afterLoss.filter { (it.gapMinutes ?: Long.MAX_VALUE) <= PreTrade.AFTER_LOSS_MINUTES }
        val twice = afterLoss.filter { lost(it.beforeThat) }
        val more = ArrayList<String>()
        if (afterLoss.isNotEmpty()) {
            val g = group(soon)
            more += if (g.count == 0) "none within ${PreTrade.AFTER_LOSS_MINUTES} minutes of the loss"
                else "${plural(g.count, "trade")} within ${PreTrade.AFTER_LOSS_MINUTES} minutes of it, ${g.won} won, ${rs(g.net)}"
            val g2 = group(twice)
            if (g2.count > 0) more += "${plural(g2.count, "trade")} after two losses in a row, ${g2.won} won, ${rs(g2.net)}"
            val ml = median(afterLoss.mapNotNull { it.gapMinutes })
            val mw = median(afterWin.mapNotNull { it.gapMinutes })
            if (ml != null) more += "you came back ${plural(ml.toInt(), "minute")} after a loss in the middle case" +
                (if (mw != null) " and ${plural(mw.toInt(), "minute")} after a win" else "")
            out += "After a loss: " + more.joinToString("; ") + "."
        }
        val opening = group(ps.filter { it.before == null })
        if (opening.count > 0) out += "For reference, a day's first trades (nothing closed before them): won ${opening.won} of ${opening.count} (${opening.rate}%), ${rs(opening.average)} a trade."
        return out
    }

    // ---- asked -------------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + spacedWords(text.lowercase(Locale.ENGLISH).replace("’", "").replace("'", "")) + " "

    private const val LOSS = "(a loss|a losing trade|a losing one|a loser|losing trades|losses|a stop out|a stop loss hit|my stop is hit|my stop gets hit|i lose|i lost|i take a loss|i book a loss|my losses|my losing trades|a bad trade)"
    private const val WIN = "(a win|a winning trade|a winning one|a winner|winning trades|wins|i win|i won|a good trade|a profit|i book a profit|my wins|my winning trades)"

    private val ASK_LOSS = rx(
        // "Do I revenge trade?", "am I a revenge trader?", "do I chase my losses?", "do I go on tilt?"
        " (do|did|have) i (ever )?(revenge trade|revenge traded|chase (my )?losses|chase (my )?losers|go on tilt|trade on tilt|tilt) " +
        "| am i (a )?(revenge trader|revenge trading|chasing (my )?losses|on tilt) " +
        "| (my|any) revenge (trades|trading) " +
        // "How do I trade after a loss?", "how do my trades do after a losing trade?", "what happens after I lose?"
        "| how (do|did|does|have) (i|my trades|my next trade|my next trades|the next trade|my trade) (\\w+ ){0,2}(trade|do|go|went|gone|done|perform|turn out) (right |just |soon |straight )?after $LOSS " +
        "| (what|how) (happens|happened) (to my (next )?trades? )?(right |just )?after $LOSS " +
        "| (do|did) i (lose|win|make|earn|do) (\\w+ ){0,2}(right |just |soon )?after $LOSS " +
        "| (do|did) my (next )?trades? (\\w+ ){0,2}(right |just |soon )?after $LOSS " +
        "| my (next |)(trades?|record|results) (right |just |soon )?after $LOSS " +
        "| after $LOSS (do|did) i " +
        // Hinglish: "loss ke baad mera agla trade kaisa jaata hai", "kya main revenge trade karta hoon"
        "| (loss|nuksan|nuksaan|ghata) (hone )?ke baad (mera |meri |main |mai )?(agla |next |)(trade|trades)? ?(kaisa|kaise|kaisi|kya) " +
        "| (main|mai|kya main|kya mai) (revenge trade|revenge trading) (karta|karti|karte) ")

    private val ASK_WIN = rx(
        " (do|did) i (get|become|go) (careless|sloppy|overconfident|greedy|cocky) (\\w+ ){0,1}after $WIN " +
        "| how (do|did|does|have) (i|my trades|my next trade|my next trades|the next trade|my trade) (\\w+ ){0,2}(trade|do|go|went|gone|done|perform|turn out) (right |just |soon |straight )?after $WIN " +
        "| (do|did) i (lose|win|make|earn|do) (\\w+ ){0,2}(right |just |soon )?after $WIN " +
        "| (do|did) my (next )?trades? (\\w+ ){0,2}(right |just |soon )?after $WIN " +
        "| my (next |)(trades?|record|results) (right |just |soon )?after $WIN " +
        "| (profit|munafa) ke baad (mera |meri )?(agla |next |)(trade|trades)? ?(kaisa|kaise|kaisi) ")

    /** The wake word and "can you tell me", said first or last. */
    private val POLITE = rx("^ ((hey|ok|okay|hi) )?(jarvis|ira|boss) | (can|could|would|will) you (please )?(tell|show|let) me( know)? | please | (jarvis|ira|boss) $")

    /**
     * Something else is meant: the bots', Jarvis's, advice, a rule or reminder to set, today alone, the market, a day
     * (not a trade) lost, or one trade's own story ("why did my last trade lose").
     */
    private val NOT = rx(" (bot|bots|arm|arms|strategy|strategies|backtest|backtested|jarvis|your|you|should|shall|recommend|suggest|advise|advice|" +
        "rule|remind|reminder|alert|block|stop me|dont let|don t let|today|todays|aaj|yesterday|right now|abhi|nifty|banknifty|sensex|market|" +
        "losing day|red day|bad day|losing week|why did|what is|meaning|explain|charges|brokerage|tax) ")

    /** Does [text] ask how his trades went after a loss (or a win)? The side asked first, or null when not asked. */
    fun asked(text: String): Side? {
        for (s in listOf(text, Ask.reading(text))) {
            var t = norm(s)
            while (true) { val u = " " + POLITE.replace(t, " ").trim() + " "; if (u == t) break; t = u }
            if (NOT.containsMatchIn(t)) continue
            if (ASK_LOSS.containsMatchIn(t)) return Side.LOSS
            if (ASK_WIN.containsMatchIn(t)) return Side.WIN
        }
        return null
    }

    /** The span named in [text] (as [MyNumbers] reads it); everything on record when none is said. */
    fun span(text: String): MyNumbers.Span = MyNumbers.span(text)
}
