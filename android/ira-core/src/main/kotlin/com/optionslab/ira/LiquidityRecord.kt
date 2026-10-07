package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Liquidity 15+5's paper record over time (Boss, 06 Oct 2026): "how did liquidity do this week", "liquidity on 3 Oct",
 * "liquidity last 10 trades", "which index works best for liquidity", "liquidity win streak", "is liquidity on track",
 * "liquidity ne is hafte kaisa kiya". Answered from the arm's own book of closed paper trades (every day it keeps, not just
 * today): trades, wins and losses, net after charges in all and per lot, the best and worst trade, by index, by exit
 * reason, the current streak, and one line each against the research - the backtest the forward check holds it to
 * ([ForwardCheck.LIQUIDITY], and its verdict on the forward trades: the live-vs-backtest card's) and the five-year
 * losing-trades study ([TradeLesson]). Too few trades to judge is said plainly.
 *
 * Facts from the book only - never advice: nothing here arms, stops, places or closes anything. Pure: no clock, no storage.
 */
object LiquidityRecord {
    // ---- the question --------------------------------------------------------------------------------------------

    /** Which trades: every closed one, a week, a month, one day, the last [Q.n]. */
    enum class Span { ALL, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, YESTERDAY, DAY, LAST_N }

    /** What is asked first: the whole record, by index, by exit reason, the streak, the best and worst, on track or not. */
    enum class Focus { SUMMARY, BY_INDEX, BY_EXIT, STREAK, BEST_WORST, ON_TRACK }

    /** What was asked: [span] (with its [day] or its [n]) and [focus]. */
    data class Q(val span: Span = Span.ALL, val focus: Focus = Focus.SUMMARY, val day: MonthDay? = null, val n: Int? = null)

    /** At least this many trades before the record is judged against the research (the forward check's own bar). */
    const val MIN_TRADES = ForwardCheck.MIN_TRADES
    /** At most this many trades are told one by one (a day, the last few). */
    const val MAX_LISTED = 10

    const val NOTE = "From the arm's own paper book - facts, not advice."
    const val LOCKED = "Unlock the phone for that, Boss."

    private fun norm(text: String) = Spaced.joined(text)

    private val LIQ = Regex(" (liquidity|liquiditys|liqudity|liquidty) ")
    /**
     * Not the record: the arm's levels (LiquidityMap), its size, health, a switch or a change, why a trade went as it did
     * (BotTrades, ArmDay), today alone (the day's own answers), a forecast or a fit for today, the backtest or shadows on
     * their own, a definition, or another product's liquidity.
     */
    private val NOT = Regex(" (level|levels|pool|pools|zone|zones|map|waiting|lot size|lots|size|sizing|health|healthy|" +
        "switch|switched|turn on|turn off|disarm|arm it|band|chalu|karo|kar do|set|change|why|kyun|kyon|kyu|" +
        "mean|means|meaning|define|what is a|kya hota|option|options|strike|strikes|oi|volume|spread|stock|stocks|" +
        "today|todays|aaj|tomorrow|kal|will|would|should|shall|suit|suits|like today|backtest|back test|backtested|" +
        "shadow|shadows|candidate|candidates|filter|rule|rules|explain|samjhao) ")

    private val STREAK = Regex(" (streak|streaks|winning run|losing run|win run|loss run|in a row|lagatar|consecutive) ")
    private val ON_TRACK = Regex(" (on track|on course|in line|going to plan|going as planned|as expected|track pe|track par|sahi chal|sahi ja|" +
        "(meeting|matching|living up to|keeping up with) (its |the )?(research|expectations?)|(vs|versus|against|compared to|compared with) (the |its )?research) ")
    private val BY_INDEX = Regex(" ((which|what|kaun sa|kaunsa|konsa|kis) (index|indices|book|books)|by (index|book)|(index|book) wise|per (index|book)|" +
        "(banknifty|bank nifty|finnifty|fin nifty) (or|vs|versus|aur|ya) (banknifty|bank nifty|finnifty|fin nifty)|works? best|does best|better on|best on|accha chalta) ")
    private val BY_EXIT = Regex(" ((by|per) (exit|exits|exit reason|exit reasons)|exit reasons?|exit wise|exits? breakdown|breakdown of (its |the )?exits|how (do|did|are|were) (its |the )?(trades? )?(exit|exits|end|close|closed|exited)) ")
    private val BEST_WORST = Regex(" ((best|worst|biggest|largest) (trade|trades|win|wins|loss|losses|winner|winners|loser|losers)|" +
        "sabse (bada|badi|accha|acchi|bura|buri) (trade|win|loss|profit|nuksan)) ")
    private val RECORD = Regex(" (record|track record|performance|performed|perform|stats|statistics|results|scorecard|score card|report card|" +
        "win rate|hit rate|win ratio|win percentage|history|hisaab|hisab|so far|till now|ab tak|abhi tak|overall|all time|since it started|" +
        "total|pnl|p l|profit and loss|net|made|earned|kamaya|kamaaya|gawaya|fare|fared|weekly (review|report|record|recap)) ")
    private val THIS_WEEK = Regex(" ((this|the) week|this weeks|weekly (review|report|record|recap)|(is|iss) (hafte|hafta)|hafte (ka|ki|mein|me)) ")
    private val LAST_WEEK = Regex(" (last|previous|past|pichle|pichhle|pichla|pichhla|guzre) (week|weeks|hafte|hafta) ")
    private val THIS_MONTH = Regex(" ((this|the) month|this months|(is|iss) (mahine|mahina)) ")
    private val LAST_MONTH = Regex(" (last|previous|past|pichle|pichhle|pichla|pichhla) (month|months|mahine|mahina) ")
    private val YESTERDAY = Regex(" (yesterday|yesterdays) ")
    private val LAST_N = Regex(" (last|latest|recent|past|pichle|pichhle|aakhri|akhri) (\\d{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|" +
        "eleven|twelve|fifteen|twenty|thirty|fifty)? ?(trade|trades|entries) ")
    private val NUMBERS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "fifty" to 50)
    private const val MONTHS = "jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|nov|november|dec|december"
    private val DAY_MONTH = Regex(" (\\d{1,2})(st|nd|rd|th)? (of )?($MONTHS) ")
    private val MONTH_DAY = Regex(" ($MONTHS) (\\d{1,2})(st|nd|rd|th)? ")

    private fun month(s: String): Int = when (s.take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6; "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; else -> 12
    }

    private fun monthDay(m: Int, d: Int): MonthDay? = runCatching { MonthDay.of(m, d) }.getOrNull()

    /** Is Liquidity 15+5's record over time asked? Null when not. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Q?>(64)

    /**
     * The span [text] names (a day, the last N trades, this or last week or month, yesterday; every closed trade when none),
     * as a [Q] with the summary focus - the same reading [asked] makes, for another reader of the arm's book ([LotsWhatIf]).
     */
    fun spanOf(text: String): Q = spanIn(norm(text))

    private fun spanIn(t: String): Q {
        val day = DAY_MONTH.find(t)?.let { m -> monthDay(month(m.groupValues[4]), m.groupValues[1].toInt()) }
            ?: MONTH_DAY.find(t)?.let { m -> monthDay(month(m.groupValues[1]), m.groupValues[2].toInt()) }
        val lastN = LAST_N.find(t)
        val span = when {
            day != null -> Span.DAY
            lastN != null -> Span.LAST_N
            LAST_WEEK.containsMatchIn(t) -> Span.LAST_WEEK
            THIS_WEEK.containsMatchIn(t) -> Span.THIS_WEEK
            LAST_MONTH.containsMatchIn(t) -> Span.LAST_MONTH
            THIS_MONTH.containsMatchIn(t) -> Span.THIS_MONTH
            YESTERDAY.containsMatchIn(t) -> Span.YESTERDAY
            else -> Span.ALL
        }
        val n = lastN?.groupValues?.get(2)?.let { it.toIntOrNull() ?: NUMBERS[it] }?.takeIf { it > 0 } ?: if (lastN != null) 1 else null
        return Q(span, Focus.SUMMARY, if (span == Span.DAY) day else null, if (span == Span.LAST_N) n else null)
    }

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (!LIQ.containsMatchIn(t) || NOT.containsMatchIn(t)) return null
        val sp = spanIn(t)
        val span = sp.span
        val focus = when {
            STREAK.containsMatchIn(t) -> Focus.STREAK
            ON_TRACK.containsMatchIn(t) -> Focus.ON_TRACK
            BY_INDEX.containsMatchIn(t) -> Focus.BY_INDEX
            BY_EXIT.containsMatchIn(t) -> Focus.BY_EXIT
            BEST_WORST.containsMatchIn(t) -> Focus.BEST_WORST
            else -> Focus.SUMMARY
        }
        // The whole record with no word of a record is not asked ("how is liquidity doing" stays the arm's own answer).
        if (span == Span.ALL && focus == Focus.SUMMARY && !RECORD.containsMatchIn(t)) return null
        return sp.copy(focus = focus)
    }

    // ---- the record ------------------------------------------------------------------------------------------------

    /** One closed paper trade of the arm, as read from its book: [net] rupees after charges in all, [perLot] per lot. */
    data class Row(val trade: BotTrades.Trade, val index: String, val book: String, val lots: Double, val net: Double, val perLot: Double) {
        val day: LocalDate get() = trade.entryTime.toLocalDate()
        val win: Boolean get() = net > 0
        val why: String get() = trade.why ?: "exit"
    }

    private fun indexName(u: String) = if (u == "FINNIFTY") "FinNifty" else "BankNifty"

    /**
     * The arm's closed paper trades among [trades] (any arm's; Liquidity 15+5's four books kept, renamed books included),
     * oldest exit first: each with its net after charges, in all and per lot (its lots bought: [BotTrades.Trade.qty] over the
     * contract's lot, 1 when not known).
     */
    fun rows(trades: List<BotTrades.Trade>): List<Row> = trades
        .filter { BotTrades.group(it.source) == "liquidity" && !it.live && it.exit != null && it.exitTime != null }
        .sortedWith(compareBy({ it.exitTime }, { it.entryTime }))
        .map { t ->
            val lots = t.lot?.takeIf { it > 0 }?.let { t.qty.toDouble() / it }?.takeIf { it > 0 } ?: 1.0
            val net = (t.exit!! - t.entry) * t.qty - t.charges
            val u = BotTrades.underlying(t)
            val mins = BotTrades.label(t.source).let { l -> Regex("(\\d+)m").find(l)?.groupValues?.get(1) }
            Row(t, u, indexName(u) + (mins?.let { " $it-min" } ?: ""), lots, net, net / lots)
        }

    /** A group's tally: [trades], [wins], [net] in all, [perLot] summed per lot, [charges]. */
    data class Tally(val trades: Int, val wins: Int, val net: Double, val perLot: Double, val charges: Double) {
        val losses: Int get() = trades - wins
        /** Per lot a trade, or null for none. */
        val perTrade: Double? get() = if (trades == 0) null else perLot / trades
        val winRate: Double? get() = if (trades == 0) null else wins.toDouble() / trades
    }

    fun tally(rows: List<Row>): Tally = Tally(rows.size, rows.count { it.win }, rows.sumOf { it.net }, rows.sumOf { it.perLot },
        rows.sumOf { it.trade.charges })

    /** The run the newest trade is part of: [wins] true for wins, [length] trades (0 with none). */
    data class Run(val wins: Boolean, val length: Int)

    /** The current run (from the newest trade back), and the longest run of wins and of losses, in [rows]' order. */
    fun runs(rows: List<Row>): Triple<Run, Int, Int> {
        var bestWin = 0; var bestLoss = 0; var cur = 0; var curWin = false
        rows.forEachIndexed { i, r ->
            if (i == 0 || r.win != curWin) { cur = 1; curWin = r.win } else cur++
            if (curWin) bestWin = maxOf(bestWin, cur) else bestLoss = maxOf(bestLoss, cur)
        }
        return Triple(Run(curWin, if (rows.isEmpty()) 0 else cur), bestWin, bestLoss)
    }

    /**
     * The chance that [n] trades, each lost with chance [lossRate], hold a run of at least [k] losses in a row (exact, by
     * the run length so far). 0 for k > n, 1 for k <= 0.
     */
    fun lossRunChance(n: Int, k: Int, lossRate: Double): Double {
        if (k <= 0) return 1.0
        if (k > n) return 0.0
        var a = DoubleArray(k).also { it[0] = 1.0 }
        repeat(n) {
            val b = DoubleArray(k)
            for (j in 0 until k) {
                b[0] += a[j] * (1 - lossRate)
                if (j + 1 < k) b[j + 1] += a[j] * lossRate
            }
            a = b
        }
        return (1 - a.sum()).coerceIn(0.0, 1.0)
    }

    /** The days [q] names at [today] (inclusive), or null for every day; the last-N span is cut by count instead. */
    fun window(q: Q, today: LocalDate): Pair<LocalDate, LocalDate>? = when (q.span) {
        Span.ALL, Span.LAST_N -> null
        Span.THIS_WEEK -> today.with(DayOfWeek.MONDAY) to today
        Span.LAST_WEEK -> today.with(DayOfWeek.MONDAY).minusWeeks(1).let { it to it.plusDays(6) }
        Span.THIS_MONTH -> today.withDayOfMonth(1) to today
        Span.LAST_MONTH -> today.withDayOfMonth(1).minusMonths(1).let { it to it.plusMonths(1).minusDays(1) }
        Span.YESTERDAY -> lastWeekdayBefore(today).let { it to it }
        Span.DAY -> dayOf(q.day ?: MonthDay.from(today), today).let { it to it }
    }

    /** The last weekday before [today] (the session before, holidays aside). */
    fun lastWeekdayBefore(today: LocalDate): LocalDate {
        var d = today.minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }

    /** [md] in [today]'s year, or the year before when that is still to come. (29 Feb in a common year: the 28th.) */
    fun dayOf(md: MonthDay, today: LocalDate): LocalDate {
        val d = md.atYear(today.year)
        return if (d.isAfter(today)) md.atYear(today.year - 1) else d
    }

    /** [all] cut to what [q] names at [today]. */
    fun pick(q: Q, all: List<Row>, today: LocalDate): List<Row> {
        if (q.span == Span.LAST_N) return all.takeLast(q.n ?: 1)
        val w = window(q, today) ?: return all
        return all.filter { !it.day.isBefore(w.first) && !it.day.isAfter(w.second) }
    }

    // ---- words -------------------------------------------------------------------------------------------------------

    internal fun rs(x: Double): String {
        val r = abs(x).roundToLong()
        return if (r == 0L) "Rs 0" else (if (x < 0) "-Rs " else "+Rs ") + String.format(Locale.ENGLISH, "%,d", r)
    }
    private fun rsPlain(x: Double) = "Rs " + String.format(Locale.ENGLISH, "%,d", abs(x).roundToLong())
    private fun pct(x: Double) = "${(100 * x).roundToInt()}%"
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun hm(r: Row) = String.format(Locale.ENGLISH, "%02d:%02d", r.trade.entryTime.hour, r.trade.entryTime.minute)
    private fun s(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun leg(right: String) = if (right.equals("PE", true)) "PE" else "CE"

    /** An exit reason as booked, in words. */
    fun reason(why: String): String = when (why) {
        "index_stop" -> "index stop"
        "failed_break" -> "failed break"
        "time_stop" -> "time stop"
        "stop" -> "15% premium stop"
        "next_liquidity" -> "next liquidity level"
        "new_liquidity" -> "new liquidity"
        "session_end" -> "15:10 square-off"
        "operator_stop" -> "stopped for the day"
        "closed_by_you" -> "closed by you"
        "backstop_square_off" -> "backstop square-off"
        else -> why.replace('_', ' ')
    }

    internal fun spanWords(q: Q, today: LocalDate, shown: List<Row>): String = when (q.span) {
        Span.ALL -> "so far"
        Span.THIS_WEEK -> "this week (from ${date(today.with(DayOfWeek.MONDAY))})"
        Span.LAST_WEEK -> window(q, today)!!.let { "last week (${date(it.first)} - ${date(it.second)})" }
        Span.THIS_MONTH -> "this month (from ${date(today.withDayOfMonth(1))})"
        Span.LAST_MONTH -> window(q, today)!!.let { "last month (${it.first.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)})" }
        Span.YESTERDAY -> "on ${date(window(q, today)!!.first)}"
        Span.DAY -> "on ${date(window(q, today)!!.first)}"
        Span.LAST_N -> if (shown.size == 1) "for its last trade" else "over its last ${shown.size} trades"
    }

    /** What the research says of a trade of [perLot] (TradeLesson's buckets). */
    private fun sizeWords(perLot: Double): String = when (TradeLesson.size(perLot)) {
        TradeLesson.Size.BIG_WIN -> "a big win, in the top 10% of its wins in the research"
        TradeLesson.Size.GOOD_WIN -> "a good win, in the top quarter of its wins in the research"
        TradeLesson.Size.NORMAL_WIN -> "a normal win for it"
        TradeLesson.Size.SMALL_WIN -> "a small win"
        TradeLesson.Size.SMALL_LOSS -> "a small loss"
        TradeLesson.Size.NORMAL_LOSS -> "a normal loss for it"
        TradeLesson.Size.LARGE_LOSS -> "larger than its usual loss, in the worst quarter in the research"
        TradeLesson.Size.BIG_LOSS -> "a big loss, among the worst 10% in the research"
    }

    private fun one(r: Row) = "${date(r.day)} ${hm(r)} ${r.book} ${leg(r.trade.right)}: ${rs(r.perLot)} a lot (${reason(r.why)})"

    private fun tallyLine(t: Tally): String {
        val per = t.perTrade?.let { ", ${rs(it)} a lot a trade" }.orEmpty()
        return "${s(t.trades, "trade")}, ${t.wins} won and ${t.losses} lost, net ${rs(t.net)} after ${rsPlain(t.charges)} charges " +
            "(${rs(t.perLot)} a lot$per)"
    }

    private fun bestWorst(rows: List<Row>): String? {
        if (rows.isEmpty()) return null
        val best = rows.maxByOrNull { it.perLot }!!
        val worst = rows.minByOrNull { it.perLot }!!
        val b = "Best: ${one(best)} - ${sizeWords(best.perLot)}."
        if (best === worst) return b
        return "$b Worst: ${one(worst)} - ${sizeWords(worst.perLot)}."
    }

    private fun byIndex(rows: List<Row>): String {
        val groups = rows.groupBy { it.index }.entries.sortedByDescending { tally(it.value).perLot }
        val parts = groups.joinToString("; ") { (u, rs) ->
            val t = tally(rs)
            "${indexName(u)} ${s(t.trades, "trade")} (${t.wins} won), ${rs(t.perLot)} a lot" + (t.perTrade?.let { ", ${rs(it)} a trade" }.orEmpty())
        }
        return "By index: $parts."
    }

    private fun byBook(rows: List<Row>): String = "By book: " + rows.groupBy { it.book }.entries.sortedByDescending { tally(it.value).perLot }
        .joinToString("; ") { (b, rs) -> val t = tally(rs); "$b ${s(t.trades, "trade")} (${t.wins} won), ${rs(t.perLot)} a lot" } + "."

    private fun byExit(rows: List<Row>, withResearch: Boolean): String {
        val wins = rows.count { it.win }; val losses = rows.size - wins
        val parts = rows.groupBy { it.why }.entries.sortedByDescending { it.value.size }.joinToString("; ") { (why, rs) ->
            val t = tally(rs)
            val share = if (!withResearch) "" else {
                val w = TradeLesson.WIN_SHARE[why]; val l = TradeLesson.LOSS_SHARE[why]
                listOfNotNull(
                    if (t.wins > 0 && wins > 0 && w != null) "${pct(t.wins.toDouble() / wins)} of its wins vs ${w.roundToInt()}% in the research" else null,
                    if (t.losses > 0 && losses > 0 && l != null) "${pct(t.losses.toDouble() / losses)} of its losses vs ${l.roundToInt()}% in the research" else null,
                ).joinToString(", ").takeIf { it.isNotEmpty() }?.let { " - $it" }.orEmpty()
            }
            "${reason(why)} ${t.trades} (${t.wins} won), ${rs(t.perLot)} a lot$share"
        }
        return "By exit: $parts."
    }

    private fun streakLine(rows: List<Row>): String {
        val (cur, w, l) = runs(rows)
        if (cur.length == 0) return "No streak yet."
        val now = if (cur.length == 1) "its last trade ${if (cur.wins) "won" else "lost"}" else "${cur.length} ${if (cur.wins) "wins" else "losses"} in a row now"
        return "Streak: $now (longest: ${s(w, "win")}, ${s(l, "loss", "losses")} in a row)."
    }

    /** The research: what the backtest and the five-year study expect, set against [t] (judged only from [MIN_TRADES] trades). */
    private fun researchLine(t: Tally): String {
        val e = ForwardCheck.LIQUIDITY
        val studyWin = TradeLesson.WINNERS.toDouble() / (TradeLesson.WINNERS + TradeLesson.LOSERS)
        val expect = "the backtest made ${rs(e.mean)} a lot a trade and won ${pct(e.winRate)}; the five-year study won ${pct(studyWin)} " +
            "(middle win ${rs(TradeLesson.WIN_PCT[2])}, middle loss ${rs(TradeLesson.LOSS_PCT[2])} a lot)"
        val per = t.perTrade ?: return "Against the research: nothing to compare yet - $expect."
        val mine = "${rs(per)} a lot a trade and ${pct(t.winRate!!)} won"
        if (t.trades < MIN_TRADES) return "Against the research: $mine, but ${s(t.trades, "trade")} ${if (t.trades == 1) "is" else "are"} too few to judge " +
            "(it takes $MIN_TRADES) - $expect."
        val band = ForwardCheck.band(e, t.trades)!!
        val where = when {
            per < band.low -> "below what the backtest allows for ${t.trades} trades (${rs(band.low)} at least)"
            per > band.high -> "above what the backtest allows for ${t.trades} trades (${rs(band.high)} at most)"
            else -> "within what the backtest allows for ${t.trades} trades (${rs(band.low)} to ${rs(band.high)})"
        }
        return "Against the research: $mine - $where; $expect."
    }

    /** The forward check's verdict on every trade since its current rules (the live-vs-backtest card's). */
    fun forward(all: List<Row>): ForwardCheck.Result = ForwardCheck.check(ForwardCheck.LIQUIDITY, all.map { ForwardCheck.Trade(it.day, it.perLot) })

    private fun forwardLine(r: ForwardCheck.Result): String {
        val since = ForwardCheck.LIQUIDITY.since?.let { " since ${date(it)}" }.orEmpty()
        return "Live vs backtest (its forward trades$since): " + when {
            r.trades == 0 -> "no forward trade yet."
            else -> "${ForwardCheck.words(r)} - ${s(r.trades, "trade")}, ${rs(r.perTrade!!)} a lot a trade vs the expected ${rs(r.expectation.mean)} ± ${rsPlain(r.band!!.half)}" +
                (if (r.alarm) "; a sustained run below the backtest since trade ${r.alarmAt}" else "") + "."
        }
    }

    // ---- the answer -------------------------------------------------------------------------------------------------

    /** The answer to [q] at [today] from the arm's closed paper trades [all] ([rows]' order: oldest exit first). */
    fun answer(q: Q, all: List<Row>, today: LocalDate): String {
        if (all.isEmpty()) return "Liquidity 15+5 has no closed paper trade in its book yet, Boss - nothing to judge. $NOTE"
        val shown = pick(q, all, today)
        val span = spanWords(q, today, shown)
        val out = ArrayList<String>()
        if (shown.isEmpty()) {
            val last = all.last()
            out += "Liquidity 15+5 closed no paper trade $span, Boss. Its book has ${s(all.size, "closed trade")}, the last on ${date(last.day)} " +
                "(${tally(all).let { "${it.wins} won, ${rs(it.perLot)} a lot in all" }})."
            out += forwardLine(forward(all))
            out += NOTE
            return out.joinToString("\n")
        }
        val t = tally(shown)
        val head = "Boss, Liquidity 15+5's paper record $span: ${tallyLine(t)}."
        when (q.focus) {
            Focus.STREAK -> {
                val (cur, w, l) = runs(all)
                val now = if (cur.length == 1) "its last trade ${if (cur.wins) "won" else "lost"} (${date(all.last().day)})"
                    else "it is on ${cur.length} ${if (cur.wins) "wins" else "losses"} in a row (the last on ${date(all.last().day)})"
                out += "Boss, Liquidity 15+5's streak: $now. Its longest over ${s(all.size, "closed trade")}: ${s(w, "win")} and ${s(l, "loss", "losses")} in a row."
                val lossRate = 1 - ForwardCheck.LIQUIDITY.winRate
                if (l >= 2) {
                    val chance = lossRunChance(all.size, l, lossRate)
                    out += "At the backtest's ${pct(ForwardCheck.LIQUIDITY.winRate)} win rate, a run of $l or more losses within ${s(all.size, "trade")} " +
                        "comes in ${pct(chance)} of runs - ${if (chance >= 0.2) "a normal run for it" else "rarer than usual for it"}."
                }
                if (q.span != Span.ALL) out += head
            }
            Focus.ON_TRACK -> {
                val f = forward(all)
                out += "Boss, is Liquidity 15+5 on track? " + forwardLine(f).removeSuffix(".") + "." +
                    if (f.verdict == ForwardCheck.Verdict.TOO_FEW) " Too few to say either way yet." else ""
                out += head.removePrefix("Boss, ")
                out += researchLine(t)
            }
            Focus.BY_INDEX -> {
                out += head
                out += byIndex(shown)
                out += byBook(shown)
                val few = shown.groupBy { it.index }.values.all { it.size < MIN_TRADES }
                out += if (few) "Each index has fewer than $MIN_TRADES trades here: too few to say one works better. The research pins no split by index - this is the paper record alone."
                    else "The research pins no split by index - this is the paper record alone."
            }
            Focus.BY_EXIT -> {
                out += head
                out += byExit(shown, withResearch = true)
                if (t.trades < MIN_TRADES) out += "${s(t.trades, "trade")} ${if (t.trades == 1) "is" else "are"} too few to judge its exits against the research (it takes $MIN_TRADES)."
            }
            Focus.BEST_WORST -> {
                out += head
                bestWorst(shown)?.let { out += it }
            }
            Focus.SUMMARY -> {
                out += head
                if (q.span == Span.DAY || q.span == Span.YESTERDAY || q.span == Span.LAST_N) {
                    shown.takeLast(MAX_LISTED).forEachIndexed { i, r -> out += "${i + 1}. ${one(r)}." }
                    if (shown.size > MAX_LISTED) out += "And ${shown.size - MAX_LISTED} earlier; ask for a shorter span for those."
                }
                bestWorst(shown)?.takeIf { shown.size > 1 }?.let { out += it }
                if (shown.map { it.index }.distinct().size > 1) out += byIndex(shown)
                out += byExit(shown, withResearch = false)
                out += streakLine(shown)
                out += researchLine(t)
                out += forwardLine(forward(all))
            }
        }
        out += NOTE
        return out.joinToString("\n")
    }
}
