package com.optionslab.ira

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Boss's month, looked at like a coach would (usefulness round 6, 2026-10-05): on the month's last trading day after
 * the close, and on "how was my month" / "review last month". His own trades (not the bots') this month against the
 * month before - count, win rate, net - then where they made and lost money: by index, by time of day, by weekday, by
 * how long they were held and by the reasons he noted ("Jarvis, note: I bought because..."); how steady the month was
 * (the share of green days, the best day against the rest, the worst day); and the one habit that cost most, with the
 * month before for comparison. Facts from Boss's own trade book only; never a word on what to buy or sell. Said in the
 * chat with the numbers; aloud (a locked phone may be heard) only plain words, with no amount and no symbol. Pure.
 */
object MonthReview {
    /** A day "of far more trades": at least this many, and at least [BUSY_RATIO] times the month's usual (median) day. */
    const val BUSY_MIN = 6
    const val BUSY_RATIO = 2.0
    /** A loser "held far longer": at least this many times the usual (median) winner's hold. */
    const val HOLD_RATIO = 2.0
    /** Trades opened at or after 15:00 are "late". */
    const val LATE = 15 * 60
    /** A habit is named only with at least this many trades, and only when they lost money together. */
    const val MIN_TRADES = 2
    /** A noted reason (or any group) is shown with at least this many trades. */
    const val MIN_GROUP = 2
    /** A note belongs to the trade opened nearest to it, within this many minutes (as the report card matches them). */
    const val NOTE_MINUTES = 60L

    enum class Habit(val spoken: String, val what: String) {
        FIRST_MINUTES("trades in the first five minutes of the session", "opened in the first five minutes of the session"),
        AFTER_LOSS("trades taken soon after a loss", "opened within ${WeekReview.AFTER_LOSS_MINUTES} minutes of a losing trade closing"),
        BUSY_DAYS("days with far more trades than your usual", "on days with far more trades than your usual day"),
        HELD_LOSERS("losing trades held far longer than your winners", "lost while held more than twice as long as your usual winner"),
        LATE("trades opened after three in the afternoon", "opened after 15:00"),
    }

    /** A habit seen in a month: the trades it covers, and what they made together (a loss is negative). */
    data class Cost(val habit: Habit, val count: Int, val won: Int, val net: Double)

    data class Group(val name: String, val trades: Int, val won: Int, val net: Double)

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun monthName(m: YearMonth) = m.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun dayName(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " ${d.dayOfMonth} " +
        d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun norm(text: String) = " " + spacedWords(text.lowercase()) + " "

    private val ASKED = Regex(" (how (was|is|s|has been|did) (my|the) month|how did i do (this|last|the) month|how did my (trading|trades) (go|do) (this|last) month|" +
        "(review|recap|summari[sz]e|sum up|go over|go through) (my |the )?(this |last )?month|(review|recap) of (my |the |this |last )?month|" +
        "(monthly|month s|months|month end|end of month) (review|report|recap|summary|report card)|(this|last) month s (trading|trades|review|performance)|" +
        "my (last )?month (go|went|been)|(mera |is |iss |pichla |pichle )?(mahina|mahine) (kaisa|kaise))")
    /** A weekday, expiry, a part of the day or an index named: the trade search's question, not the month's review. */
    private val FILTERED = Regex(" (monday|tuesday|wednesday|thursday|friday|mondays|tuesdays|wednesdays|thursdays|fridays|expiry|morning|afternoon|first hour|last hour|closing hour) ")

    /** Does [text] ask for the month's review of Boss's own trading? */
    fun asked(text: String): Boolean {
        val t = norm(Ask.reading(text))
        return ASKED.containsMatchIn(t) && !FILTERED.containsMatchIn(t) && Market.mentioned(text).isEmpty()
    }

    /** The month asked about: last month when said ("pichla mahina" too), this month otherwise. */
    fun month(text: String, today: LocalDate): YearMonth {
        val t = norm(text)
        val m = YearMonth.from(today)
        return if (rx(" (last|previous|past|pichla|pichle) (month|mahina|mahine) ").containsMatchIn(t)) m.minusMonths(1) else m
    }

    /** Is [today] the month's last trading day? (The next trading day falls in another month.) */
    fun lastTradingDay(today: LocalDate, isTradingDay: (LocalDate) -> Boolean): Boolean {
        if (!isTradingDay(today)) return false
        var d = today.plusDays(1)
        repeat(20) { if (isTradingDay(d)) return d.month != today.month; d = d.plusDays(1) }
        return true
    }

    /** The trades that closed in [month] and in the month before it (up to [today]). */
    fun split(trips: List<Insights.Trip>, month: YearMonth, today: LocalDate): Pair<List<Insights.Trip>, List<Insights.Trip>> {
        fun inMonth(t: Insights.Trip, m: YearMonth) = t.closedAt.toLocalDate().let { YearMonth.from(it) == m && !it.isAfter(today) }
        return trips.filter { inMonth(it, month) } to trips.filter { inMonth(it, month.minusMonths(1)) }
    }

    private fun held(t: Insights.Trip) = Duration.between(t.openedAt, t.closedAt).toMinutes().coerceAtLeast(0)
    private fun median(xs: List<Double>): Double = xs.sorted().let { s -> if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    private fun index(sym: String) = listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "NIFTY", "GOLD").firstOrNull { sym.startsWith(it) } ?: "Other"

    /** Opening times, as Boss would say them. */
    private fun timeOfDay(t: Insights.Trip): String {
        val m = t.openedAt.hour * 60 + t.openedAt.minute
        return when {
            m < WeekReview.OPEN -> "before 09:15"
            m < 10 * 60 -> "09:15-10:00"
            m < 12 * 60 -> "10:00-12:00"
            m < 13 * 60 + 30 -> "12:00-13:30"
            m < 14 * 60 + 30 -> "13:30-14:30"
            m < 15 * 60 + 30 -> "14:30-15:30"
            else -> "after 15:30"
        }
    }
    private val TIMES = listOf("before 09:15", "09:15-10:00", "10:00-12:00", "12:00-13:30", "13:30-14:30", "14:30-15:30", "after 15:30")

    private fun holding(t: Insights.Trip): String = held(t).let { when {
        t.closedAt.toLocalDate() != t.openedAt.toLocalDate() -> "overnight"
        it < 5 -> "under 5 minutes"
        it < 30 -> "5 to 30 minutes"
        it < 120 -> "30 minutes to 2 hours"
        else -> "over 2 hours"
    } }
    private val HOLDS = listOf("under 5 minutes", "5 to 30 minutes", "30 minutes to 2 hours", "over 2 hours", "overnight")

    private fun groups(w: List<Insights.Trip>, key: (Insights.Trip) -> String, order: List<String>? = null): List<Group> {
        val g = w.groupBy(key).map { (k, l) -> Group(k, l.size, l.count { it.net > 0 }, l.sumOf { it.net }) }
        return if (order != null) g.sortedBy { order.indexOf(it.name) } else g.sortedByDescending { it.net }
    }

    private fun groupText(g: Group) = "${g.name} ${plural(g.trades, "trade")}, ${g.won} won, ${rs(g.net)}"

    /** "By index: ...", with where the most was made and lost when the groups differ. */
    private fun byLine(what: String, gs: List<Group>): String? {
        if (gs.size < 2) return null
        val shown = gs.joinToString("; ") { groupText(it) }
        val best = gs.filter { it.trades >= MIN_GROUP }.maxByOrNull { it.net }?.takeIf { it.net > 0 }
        val worst = gs.filter { it.trades >= MIN_GROUP }.minByOrNull { it.net }?.takeIf { it.net < 0 }
        val tail = listOfNotNull(best?.let { "most made: ${it.name}" }, worst?.let { "most lost: ${it.name}" }).joinToString(", ")
        return "By $what: $shown." + if (tail.isNotEmpty()) " (${tail.replaceFirstChar { it.uppercase() }}.)" else ""
    }

    /** Each note matched to the trade opened nearest to it (within [NOTE_MINUTES]); the kind of reason per trade. */
    fun setups(w: List<Insights.Trip>, notes: List<Pair<LocalDateTime, String>>): List<Group> {
        val kinds = HashMap<Insights.Trip, String>()
        for ((at, n) in notes) {
            val t = w.filter { kotlin.math.abs(Duration.between(it.openedAt, at).toMinutes()) <= NOTE_MINUTES }
                .minByOrNull { kotlin.math.abs(Duration.between(it.openedAt, at).toMinutes()) } ?: continue
            kinds.putIfAbsent(t, TradeReasons.kind(n))
        }
        return kinds.entries.groupBy({ it.value }, { it.key }).map { (k, l) -> Group(k, l.size, l.count { it.net > 0 }, l.sumOf { it.net }) }
            .filter { it.trades >= MIN_GROUP }.sortedByDescending { it.net }
    }

    /** Days with far more trades than the month's usual day. */
    private fun busyDays(w: List<Insights.Trip>): List<Insights.Trip> {
        val days = w.groupBy { it.openedAt.toLocalDate() }
        if (days.size < 3) return emptyList()
        val usual = median(days.values.map { it.size.toDouble() })
        return days.values.filter { it.size >= BUSY_MIN && it.size >= usual * BUSY_RATIO }.flatten()
    }

    /** Losers held more than [HOLD_RATIO] times the usual winner (with at least two winners to know the usual). */
    private fun heldLosers(w: List<Insights.Trip>): List<Insights.Trip> {
        val win = w.filter { it.net > 0 }
        if (win.size < 2) return emptyList()
        val usual = maxOf(1.0, median(win.map { held(it).toDouble() }))
        return w.filter { it.net < 0 && held(it) > usual * HOLD_RATIO }
    }

    private fun late(w: List<Insights.Trip>) = w.filter { it.openedAt.hour * 60 + it.openedAt.minute >= LATE }

    private fun trades(h: Habit, w: List<Insights.Trip>): List<Insights.Trip> = when (h) {
        Habit.FIRST_MINUTES -> WeekReview.firstMinutes(w)
        Habit.AFTER_LOSS -> WeekReview.afterLoss(w)
        Habit.BUSY_DAYS -> busyDays(w)
        Habit.HELD_LOSERS -> heldLosers(w)
        Habit.LATE -> late(w)
    }

    /** Every habit seen in [w], what its trades made together. */
    fun costs(w: List<Insights.Trip>): List<Cost> = Habit.entries.map { h ->
        trades(h, w).let { l -> Cost(h, l.size, l.count { it.net > 0 }, l.sumOf { it.net }) }
    }

    /** The one habit that cost most in [w]: at least [MIN_TRADES] trades that lost money together, the biggest loss. */
    fun costliest(w: List<Insights.Trip>): Cost? = costs(w).filter { it.count >= MIN_TRADES && it.net < 0 }.minByOrNull { it.net }

    /** Green days, of days traded (by the day each trade closed). */
    private fun greenDays(w: List<Insights.Trip>): Pair<Int, Int> =
        w.groupBy { it.closedAt.toLocalDate() }.values.let { d -> d.count { l -> l.sumOf { it.net } > 0 } to d.size }

    private fun pct(a: Int, b: Int) = if (b == 0) 0 else 100 * a / b

    private fun steadiness(w: List<Insights.Trip>, prev: List<Insights.Trip>): List<String> {
        val out = ArrayList<String>()
        val (g, n) = greenDays(w)
        val (pg, pn) = greenDays(prev)
        out += "Green days: $g of $n (${pct(g, n)}%)." + if (pn > 0) " The month before: $pg of $pn (${pct(pg, pn)}%)." else ""
        val days = w.groupBy { it.closedAt.toLocalDate() }.mapValues { (_, l) -> l.sumOf { it.net } }
        if (days.size < 2) return out
        val net = days.values.sum()
        val best = days.maxBy { it.value }
        val worst = days.minBy { it.value }
        if (best.value > 0) {
            val rest = net - best.value
            out += "Best day: ${dayName(best.key)}, ${rs(best.value)}" + when {
                rest < 0 -> "; without it the month would be ${rs(rest)}."
                net > 0 -> ", ${pct(best.value.toInt().coerceAtLeast(0), net.toInt().coerceAtLeast(1))}% of the month's net; the other ${days.size - 1} days together ${rs(rest)}."
                else -> "."
            }
        }
        if (worst.value < 0) out += "Worst day: ${dayName(worst.key)}, ${rs(worst.value)}" +
            (if (net < 0) ", ${pct((-worst.value).toInt(), (-net).toInt().coerceAtLeast(1))}% of the month's loss." else ".")
        return out
    }

    private fun costText(c: Cost) = "${plural(c.count, "trade")} ${c.habit.what}: ${c.won} won, net ${rs(c.net)}"

    private fun compare(month: YearMonth, now: WeekReview.Stats, before: WeekReview.Stats): String {
        val name = monthName(month.minusMonths(1))
        if (before.trades == 0) return "$name: no trades of yours to compare with."
        val rate = now.winRate - before.winRate
        return "$name: ${plural(before.trades, "trade")}, ${before.winRate}% won, net ${rs(before.net)}" + when {
            now.trades == 0 -> "."
            rate > 0 -> "; your win rate is up $rate points."
            rate < 0 -> "; your win rate is down ${-rate} points."
            else -> "; the same win rate."
        }
    }

    /**
     * The review for the chat, with the numbers. [trips]: Boss's own round trips (not the bots'); [label] "Paper" or
     * "Zerodha"; [notes] the reasons he noted, with when (matched to the trades as the report card does).
     */
    fun lines(label: String, trips: List<Insights.Trip>, month: YearMonth, today: LocalDate,
              notes: List<Pair<LocalDateTime, String>> = emptyList()): List<String> {
        val (w, prev) = split(trips, month, today)
        val now = WeekReview.stats(w); val before = WeekReview.stats(prev)
        val name = monthName(month)
        if (w.isEmpty()) return listOf("$label: none of your own trades closed in $name.", compare(month, now, before))
        val out = ArrayList<String>()
        out += "$label, your own trades in $name: ${plural(now.trades, "trade")}, ${now.won} won (${now.winRate}%), net ${rs(now.net)}."
        out += compare(month, now, before)
        byLine("index", groups(w, { index(it.symbol) }))?.let { out += it }
        byLine("time of day (opened)", groups(w, ::timeOfDay, TIMES))?.let { out += it }
        byLine("weekday", groups(w, { it.openedAt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) },
            listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")))?.let { out += it }
        byLine("holding time", groups(w, ::holding, HOLDS))?.let { out += it }
        val s = setups(w, notes)
        out += if (s.isEmpty()) "Not enough noted reasons this month to tell which setups made money ($MIN_GROUP trades a reason). Say \"Jarvis, note: I bought because...\" when you take one."
            else "By the reasons you noted: " + s.joinToString("; ") { "\"${it.name}\" ${plural(it.trades, "trade")}, ${it.won} won, ${rs(it.net)}" } + "."
        out += steadiness(w, prev)
        val c = costliest(w)
        out += if (c == null) (if (w.size >= 5) "No costly habit showed up this month: no losing run of trades rushed at the open, chasing a loss, on overcrowded days, held far longer than your winners or opened late."
            else "Too few trades to see a habit.")
        else {
            val p = costs(prev).first { it.habit == c.habit }
            "The habit that cost most: ${costText(c)}." + when {
                prev.isEmpty() -> ""
                p.count == 0 -> " The month before: none."
                else -> " The month before: ${plural(p.count, "such trade")}, net ${rs(p.net)}."
            }
        }
        return out
    }

    /**
     * The same review aloud, unasked (the month's last trading day after the close): no amount and no symbol - a
     * locked phone may be heard. Null when there were none of Boss's own trades in [month].
     */
    fun spoken(trips: List<Insights.Trip>, month: YearMonth, today: LocalDate): String? {
        val (w, prev) = split(trips, month, today)
        if (w.isEmpty()) return null
        val now = WeekReview.stats(w); val before = WeekReview.stats(prev)
        val vs = when {
            before.trades == 0 -> ""
            now.winRate > before.winRate -> " You won more often than last month."
            now.winRate < before.winRate -> " You won less often than last month."
            else -> ""
        }
        val (g, n) = greenDays(w)
        val days = " ${plural(g, "day")} of $n ${if (g == 1) "was" else "were"} green."
        val c = costliest(w)
        return "Boss, your month's review is in the chat.$vs$days" +
            (if (c == null) " No costly habit showed up." else " The habit that cost most: ${c.habit.spoken}.")
    }
}
