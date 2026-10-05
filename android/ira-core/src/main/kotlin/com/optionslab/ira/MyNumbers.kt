package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Boss's own trading numbers (usefulness round 17, 2026-10-05): "what's my average win and average loss?", "what's
 * my risk reward on my trades?", "my profit factor", "my expectancy", "how much do I make per trade?", "do I hold my
 * losers longer than my winners?", "do I cut my winners short?", "my trading stats", "mera average loss kitna hai".
 * From his own closed trades in the trade book (the bots' only when he has none, and said so), over the span he names
 * (today, this or last week, this or last month; everything on record when none is said): the count and the win rate,
 * the average win against the average loss and what one is to the other, the win rate those sizes need to break even
 * against his own, what a trade made on average and the profit factor, how long winners and losers were held (the
 * median), and the biggest win and loss with what the rest came to without the single best trade. Facts from his own
 * record only - never a forecast and never what to do. His account, so the app's account answer (never on a locked
 * phone); nothing is placed, changed or armed. Pure.
 */
object MyNumbers {
    /** Averages and ratios are said only with at least this many winning and this many losing trades. */
    const val MIN_EACH = 3
    /** Held-time comparison only when the medians differ by at least this share of the shorter one. */
    const val HOLD_GAP = 0.5

    enum class Span(val label: String) { ALL("on record"), TODAY("today"), WEEK("this week"), LAST_WEEK("last week"), MONTH("this month"), LAST_MONTH("last month") }

    data class Numbers(
        val trades: Int, val wins: Int, val losses: Int, val net: Double,
        val sumWin: Double, val sumLoss: Double,
        val winHoldMin: Long?, val lossHoldMin: Long?,
    ) {
        val avgWin: Double? get() = if (wins > 0) sumWin / wins else null
        /** Average loss as a positive amount. */
        val avgLoss: Double? get() = if (losses > 0) -sumLoss / losses else null
        /** Average win over average loss. */
        val payoff: Double? get() = avgWin?.let { w -> avgLoss?.takeIf { it > 0 }?.let { w / it } }
        /** Wins' total over losses' total (positive). */
        val profitFactor: Double? get() = if (sumLoss < 0) sumWin / -sumLoss else null
        /** The win rate (wins over decided trades) these sizes need to break even: 1 / (1 + payoff). */
        val breakEven: Double? get() = payoff?.let { 1.0 / (1.0 + it) }
        val decided: Int get() = wins + losses
        val winRate: Double? get() = if (decided > 0) wins.toDouble() / decided else null
        val perTrade: Double get() = if (trades > 0) net / trades else 0.0
    }

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun amt(x: Double) = "Rs " + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun pct(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)
    private fun x1(x: Double) = "%.1f".format(Locale.ENGLISH, x)
    private fun x2(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun date(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " ${d.dayOfMonth} " +
        d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    private fun mins(m: Long) = if (m < 60) plural(m.toInt(), "minute") else "%d h %02d min".format(Locale.ENGLISH, m / 60, m % 60)
    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("'", "").replace("’", "").replace("&", " and ")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private val MINE = rx(" (i|my|me|mine|am i|do i|did i|mera|meri|mere|main|maine|mujhe) ")
    private const val WINS = "(win|wins|winner|winners|winning trades?|profit|profits|profitable trades?|gain|gains|munafa|profit trade)"
    private const val LOSSES = "(loss|losses|loser|losers|losing trades?|nuksan|nuksaan|loss trade)"
    private val ASKED = rx(
        " (average|avg|mean|typical) (size of (my |a )?)?($WINS|$LOSSES)" +
        "| ($WINS|$LOSSES) (on |per trade )?(on )?average " +
        "| (profit factor|expectancy|payoff ratio|pay off ratio|win loss ratio|win to loss ratio|win loss size|reward to risk|risk to reward|risk reward|risk and reward|r multiple|r multiples) " +
        "| (how much|what) do i (make|lose|earn|net|get) (on average |on an average )?(per|a|each|an average|every) trade" +
        "| (make|lose|earn|net) per trade| per trade (on )?average| average (p l|pnl|profit and loss|net|result|outcome) (per|a|of a|of my) trade" +
        "| (hold|keep|holding|keeping|sit on|sitting on) (on to |onto )?(my )?$LOSSES (longer|too long|for longer)" +
        "| (cut|cutting|book|booking|take|taking|close|closing) (my )?$WINS (short|early|too early|too soon|too fast|quickly|too quickly)" +
        "| (hold|holding|keep|keeping) (my )?$WINS (long enough|too short|short)" +
        "| (my|mere|meri) (trading )?(stats|statistics|metrics|numbers|trade stats|trade statistics|kpis|ratios) " +
        "| trading (stats|statistics|metrics|numbers|ratios) " +
        "| (average|avg) ($WINS|$LOSSES|trade) (kitna|kitni|kitne|kya) | kitna (average|avg) ")
    /** Something else is meant: a market's figure, an order's price, the bots' or Jarvis's record, a strategy's test. */
    private val NOT = rx(" (nifty|banknifty|bank nifty|sensex|finnifty|vix|market|index|indices|candle|candles|chain|option chain|alert|alerts|bot|bots|strategy|strategies|arm|arms|backtest|backtested|your|jarvis s|pattern|patterns|engulfing|hammer|price|entry price|buy price|average price|averaging|average down|premium|charges|brokerage|streak|streaks|limit|limits|stop|stop loss) ")

    private fun hit(t: String) = MINE.containsMatchIn(t) && ASKED.containsMatchIn(t) && !NOT.containsMatchIn(t)

    /** Does [text] ask for Boss's own trading numbers (averages, ratios, per trade, hold times)? */
    fun asked(text: String): Boolean {
        if (Market.mentioned(text).isNotEmpty()) return false
        return hit(norm(text)) || hit(norm(Ask.reading(text)))
    }

    /** The span named in [text]; everything on record when none is said. */
    fun span(text: String): Span {
        val t = norm(text)
        return when {
            rx(" (today|todays|aaj|aaj ka|aaj ke) ").containsMatchIn(t) -> Span.TODAY
            rx(" (last|previous|past|pichle|pichla) (week|hafte|hafta) ").containsMatchIn(t) -> Span.LAST_WEEK
            rx(" (last|previous|past|pichle|pichla) (month|mahina|mahine) ").containsMatchIn(t) -> Span.LAST_MONTH
            rx(" (this|is) (week|hafte|hafta) | weekly ").containsMatchIn(t) -> Span.WEEK
            rx(" (this|is) (month|mahina|mahine) | monthly ").containsMatchIn(t) -> Span.MONTH
            else -> Span.ALL
        }
    }

    /** The days of [span] up to [today], first and last (null: no bound). */
    fun range(span: Span, today: LocalDate): Pair<LocalDate?, LocalDate> = when (span) {
        Span.ALL -> null to today
        Span.TODAY -> Charges.range(Charges.Span.TODAY, today)
        Span.WEEK -> Charges.range(Charges.Span.WEEK, today)
        Span.LAST_WEEK -> Charges.range(Charges.Span.LAST_WEEK, today)
        Span.MONTH -> Charges.range(Charges.Span.MONTH, today)
        Span.LAST_MONTH -> Charges.range(Charges.Span.LAST_MONTH, today)
    }

    private fun median(xs: List<Long>): Long? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun held(t: Insights.Trip) = Duration.between(t.openedAt, t.closedAt).toMinutes().coerceAtLeast(0)

    /** The numbers of [trips] (a trade within half a rupee of zero is neither a win nor a loss). */
    fun of(trips: List<Insights.Trip>): Numbers {
        val w = trips.filter { it.net > 0.5 }
        val l = trips.filter { it.net < -0.5 }
        return Numbers(trips.size, w.size, l.size, trips.sumOf { it.net }, w.sumOf { it.net }, l.sumOf { it.net },
            median(w.map(::held)), median(l.map(::held)))
    }

    /** Said once after every account's lines. */
    const val CLOSING = "Those are your own numbers as they stand, Boss - not a forecast and not what to do; what you make of them is yours."

    /**
     * Boss's numbers for one account ([label] "Paper" or "Zerodha"), from [trips] (every owner's, the owner named on
     * each), over [span] up to [today]. ([CLOSING] is said once after every account.)
     */
    fun lines(label: String, trips: List<Insights.Trip>, span: Span, today: LocalDate): List<String> {
        val (from, to) = range(span, today)
        val inSpan = trips.filter { val d = it.closedAt.toLocalDate(); !d.isAfter(to) && (from == null || !d.isBefore(from)) }
        val own = inSpan.filter { it.owner.startsWith("Manual") }
        val mine = own.isNotEmpty()
        val used = if (mine) own else inSpan
        if (used.isEmpty()) return listOf("$label: no closed trades ${span.label}, so no numbers to tell.")
        val whose = if (mine) "your own trades" else "all trades (you have none of your own, so these are the bots')"
        val n = of(used)
        val out = ArrayList<String>()
        val first = used.minOf { it.closedAt }.toLocalDate()
        val last = used.maxOf { it.closedAt }.toLocalDate()
        val period = if (span == Span.ALL) " on record (${if (first == last) date(first) else "${date(first)} to ${date(last)}"})" else " ${span.label}"
        val flat = n.trades - n.decided
        out += "$label, $whose$period: ${plural(n.trades, "trade")}, ${n.wins} won" +
            (n.winRate?.let { " (${pct(it)} of those decided)" } ?: "") + (if (flat > 0) ", ${flat} flat" else "") +
            ", net ${rs(n.net)}, ${rs(n.perTrade)} a trade on average."

        if (n.wins < MIN_EACH || n.losses < MIN_EACH) {
            out += "Too few to compare sizes yet: averages and ratios need $MIN_EACH winning and $MIN_EACH losing trades (here ${n.wins} and ${n.losses})."
        } else {
            val aw = n.avgWin!!; val al = n.avgLoss!!; val p = n.payoff!!
            out += "Your average win is ${amt(aw)} and your average loss ${amt(al)}: " +
                (if (p >= 1) "a win is ${x1(p)} times a loss." else "a loss is ${x1(1 / p)} times a win.")
            val be = n.breakEven!!; val wr = n.winRate!!
            val gap = Math.round((wr - be) * 100).toInt()
            out += "At those sizes, breaking even takes winning about ${pct(be)} of trades; you won ${pct(wr)}" +
                when { gap >= 1 -> ", $gap points above it."; gap <= -1 -> ", ${-gap} points below it."; else -> ", about level with it." }
            n.profitFactor?.let { pf ->
                out += "Wins came to ${amt(n.sumWin)} against losses of ${amt(n.sumLoss)}: a profit factor of ${x2(pf)}."
            }
        }
        val wh = n.winHoldMin; val lh = n.lossHoldMin
        if (wh != null && lh != null && n.wins >= MIN_EACH && n.losses >= MIN_EACH) {
            val shorter = minOf(wh, lh).coerceAtLeast(1)
            out += "Held (the middle trade): winners ${mins(wh)}, losers ${mins(lh)}" + when {
                lh - wh >= HOLD_GAP * shorter -> " - losers were held longer than winners."
                wh - lh >= HOLD_GAP * shorter -> " - winners were held longer than losers."
                else -> " - about the same."
            }
        }
        val best = used.maxByOrNull { it.net }!!
        val worst = used.minByOrNull { it.net }!!
        val ends = ArrayList<String>()
        if (best.net > 0.5) ends += "biggest win ${rs(best.net)} (${best.symbol}, ${date(best.closedAt.toLocalDate())})"
        if (worst.net < -0.5) ends += "biggest loss ${rs(worst.net)} (${worst.symbol}, ${date(worst.closedAt.toLocalDate())})"
        if (ends.isNotEmpty()) {
            var s = ends.joinToString("; ").replaceFirstChar { it.uppercase() }
            if (best.net > 0.5 && n.trades >= 5) {
                val rest = n.net - best.net
                s += "; without that one best trade the other ${plural(n.trades - 1, "trade")} came to ${rs(rest)}"
            }
            out += "$s."
        }
        return out
    }
}
