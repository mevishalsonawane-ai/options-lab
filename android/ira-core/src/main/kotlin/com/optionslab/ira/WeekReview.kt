package com.optionslab.ira

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

/**
 * Boss's week, looked at like a coach would (usefulness round 3, 2026-10-05): his own trades this week against last
 * week - count, win rate, net, best and worst - and the habits that cost money, each with what it cost and how often it
 * happened the week before:
 *  - holding losing trades longer than winning ones;
 *  - trades opened in the first five minutes of the session;
 *  - trades opened soon after a losing one closed (chasing the loss back);
 *  - a day with far more trades than the week's usual.
 * Facts from Boss's own trade book only; never a word on what to buy or sell. Said in the chat with the numbers; aloud
 * (a locked phone may be heard) only the plain words, with no amount and no symbol. Pure.
 */
object WeekReview {
    /** The session opens at 09:15; "the first five minutes" is 09:15 up to 09:20. */
    const val OPEN = 9 * 60 + 15
    const val FIRST_MINUTES = 5
    /** A trade opened within this many minutes of a losing trade closing (the same day) counts as "after a loss". */
    const val AFTER_LOSS_MINUTES = 15L
    /** Losers held this many times as long as winners, or more, is the "held losers" habit. */
    const val HOLD_RATIO = 1.5
    /** A busy day: at least this many trades, and at least [BUSY_RATIO] times the week's other days on average. */
    const val BUSY_MIN = 6
    const val BUSY_RATIO = 2.0

    enum class Kind(val spoken: String) {
        HELD_LOSERS("you held losing trades longer than winning ones"),
        FIRST_MINUTES("trades in the first five minutes cost you"),
        AFTER_LOSS("trades taken soon after a loss cost you"),
        BUSY_DAY("one day had far more trades than the rest, and it lost"),
    }

    /** A habit seen this week: [cost] what those trades lost (0 for held losers), [count] how often, [before] last week. */
    data class Finding(val kind: Kind, val count: Int, val cost: Double, val before: Int, val text: String)

    data class Stats(val trades: Int, val won: Int, val net: Double) {
        val winRate: Int get() = if (trades == 0) 0 else 100 * won / trades
    }

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun dayName(d: LocalDate) = d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }

    /** This week (Monday to [today]) and last week (Monday to Sunday), by the day each trade closed. */
    fun split(trips: List<Insights.Trip>, today: LocalDate): Pair<List<Insights.Trip>, List<Insights.Trip>> {
        val monday = today.with(DayOfWeek.MONDAY)
        val lastMonday = monday.minusWeeks(1)
        fun inRange(t: Insights.Trip, a: LocalDate, b: LocalDate) = t.closedAt.toLocalDate().let { !it.isBefore(a) && !it.isAfter(b) }
        return trips.filter { inRange(it, monday, today) } to trips.filter { inRange(it, lastMonday, monday.minusDays(1)) }
    }

    fun stats(w: List<Insights.Trip>) = Stats(w.size, w.count { it.net > 0 }, w.sumOf { it.net })

    private fun held(t: Insights.Trip) = Duration.between(t.openedAt, t.closedAt).toMinutes().coerceAtLeast(0)

    private fun firstMinutes(w: List<Insights.Trip>) = w.filter {
        val m = it.openedAt.hour * 60 + it.openedAt.minute
        m >= OPEN && m < OPEN + FIRST_MINUTES
    }

    /** Trades opened within [AFTER_LOSS_MINUTES] after a losing trade closed, the same day. */
    private fun afterLoss(w: List<Insights.Trip>): List<Insights.Trip> {
        val losses = w.filter { it.net < 0 }
        return w.filter { t ->
            losses.any { l -> l !== t && l.closedAt.toLocalDate() == t.openedAt.toLocalDate() && !t.openedAt.isBefore(l.closedAt) &&
                !t.openedAt.isAfter(l.closedAt.plusMinutes(AFTER_LOSS_MINUTES)) }
        }
    }

    /** Average minutes held by winners and by losers, when there are at least two of each. */
    private fun holds(w: List<Insights.Trip>): Pair<Double, Double>? {
        val win = w.filter { it.net > 0 }; val loss = w.filter { it.net < 0 }
        if (win.size < 2 || loss.size < 2) return null
        return win.map { held(it).toDouble() }.average() to loss.map { held(it).toDouble() }.average()
    }

    private fun heldLosers(w: List<Insights.Trip>): Boolean =
        holds(w)?.let { (win, loss) -> loss >= maxOf(1.0, win) * HOLD_RATIO } ?: false

    /** The day with far more trades than the week's other days, when it lost money: (day, its trades). */
    private fun busyDay(w: List<Insights.Trip>): Pair<LocalDate, List<Insights.Trip>>? {
        val days = w.groupBy { it.openedAt.toLocalDate() }
        val (day, l) = days.maxByOrNull { it.value.size } ?: return null
        if (l.size < BUSY_MIN || l.sumOf { it.net } >= 0) return null
        val rest = days.filterKeys { it != day }.values
        val usual = if (rest.isEmpty()) 0.0 else rest.sumOf { it.size }.toDouble() / rest.size
        return if (rest.isEmpty() || l.size >= usual * BUSY_RATIO) day to l else null
    }

    private fun mins(x: Double) = "%.0f".format(Locale.ENGLISH, x)

    private fun lastWeek(n: Int, what: String) = if (n == 0) " Last week: none." else " Last week: ${plural(n, what)}."

    /** The habits seen in [w] that cost money, the costliest first; [prev] last week, to say whether it is new. */
    fun findings(w: List<Insights.Trip>, prev: List<Insights.Trip>): List<Finding> {
        val out = ArrayList<Finding>()
        if (heldLosers(w)) {
            val (win, loss) = holds(w)!!
            out += Finding(Kind.HELD_LOSERS, w.count { it.net < 0 }, 0.0, if (heldLosers(prev)) 1 else 0,
                "You held losing trades longer than winning ones: losers about ${mins(loss)} minutes on average, winners about ${mins(win)}." +
                    (if (heldLosers(prev)) " Same as last week." else holds(prev)?.let { " Last week it was the other way round." } ?: ""))
        }
        firstMinutes(w).takeIf { it.size >= 2 && it.sumOf { t -> t.net } < 0 }?.let { l ->
            val before = firstMinutes(prev).size
            out += Finding(Kind.FIRST_MINUTES, l.size, l.sumOf { it.net }, before,
                "${plural(l.size, "trade")} opened in the first five minutes of the session: ${l.count { it.net > 0 }} won, net ${rs(l.sumOf { it.net })}." +
                    lastWeek(before, "such trade"))
        }
        afterLoss(w).takeIf { it.size >= 2 && it.sumOf { t -> t.net } < 0 }?.let { l ->
            val before = afterLoss(prev).size
            out += Finding(Kind.AFTER_LOSS, l.size, l.sumOf { it.net }, before,
                "${plural(l.size, "trade")} opened within $AFTER_LOSS_MINUTES minutes of a losing trade closing: ${l.count { it.net > 0 }} won, net ${rs(l.sumOf { it.net })}." +
                    lastWeek(before, "such trade"))
        }
        busyDay(w)?.let { (day, l) ->
            out += Finding(Kind.BUSY_DAY, l.size, l.sumOf { it.net }, busyDay(prev)?.second?.size ?: 0,
                "${dayName(day)} had ${l.size} trades, far more than your other days this week, and lost ${rs(l.sumOf { it.net }).removePrefix("-")}.")
        }
        // Costliest first; the held-losers habit (its cost is in the losers themselves) after the ones with a price on them.
        return out.sortedWith(compareBy({ it.kind == Kind.HELD_LOSERS }, { it.cost }))
    }

    private fun compare(now: Stats, before: Stats): String {
        if (before.trades == 0) return "Last week: no trades to compare with."
        val rate = now.winRate - before.winRate
        return "Last week: ${plural(before.trades, "trade")}, ${before.winRate}% won, net ${rs(before.net)}" +
            when {
                now.trades == 0 -> "."
                rate > 0 -> "; your win rate is up $rate points."
                rate < 0 -> "; your win rate is down ${-rate} points."
                else -> "; the same win rate."
            }
    }

    /**
     * The review for the chat, with the numbers. [trips]: Boss's own round trips (not the bots'); [label] "Paper" or
     * "Zerodha".
     */
    fun lines(label: String, trips: List<Insights.Trip>, today: LocalDate): List<String> {
        val (w, prev) = split(trips, today)
        val now = stats(w); val before = stats(prev)
        if (w.isEmpty()) return listOf("$label: none of your own trades closed this week.", compare(now, before))
        val out = ArrayList<String>()
        out += "$label, your own trades this week: ${plural(now.trades, "trade")}, ${now.won} won (${now.winRate}%), net ${rs(now.net)}."
        out += compare(now, before)
        w.maxByOrNull { it.net }?.takeIf { it.net > 0 }?.let { out += "Best: ${it.symbol} ${rs(it.net)} on ${dayName(it.closedAt.toLocalDate())}." }
        w.minByOrNull { it.net }?.takeIf { it.net < 0 }?.let { out += "Worst: ${it.symbol} ${rs(it.net)} on ${dayName(it.closedAt.toLocalDate())}." }
        val f = findings(w, prev)
        if (f.isEmpty()) out += if (w.size >= 3) "No costly habit showed up this week: no trades rushed at the open, none chasing a loss, losers cut no slower than winners."
            else "Too few trades to see a habit."
        else {
            out += "Worth a look:"
            out += f.map { it.text }
            val gone = Kind.entries.filter { k -> f.none { it.kind == k } && findings(prev, emptyList()).any { it.kind == k } }
            if (gone.isNotEmpty()) out += "Better than last week: " + gone.joinToString("; ") { k -> when (k) {
                Kind.HELD_LOSERS -> "losers no longer held longer than winners"
                Kind.FIRST_MINUTES -> "no costly trades in the first five minutes"
                Kind.AFTER_LOSS -> "no costly trades right after a loss"
                Kind.BUSY_DAY -> "no day of far too many trades"
            } } + "."
        }
        return out
    }

    /**
     * The same review aloud, unasked (Friday after the close): no amount and no symbol - a locked phone may be heard.
     * Null when there were none of Boss's own trades this week.
     */
    fun spoken(trips: List<Insights.Trip>, today: LocalDate): String? {
        val (w, prev) = split(trips, today)
        if (w.isEmpty()) return null
        val now = stats(w); val before = stats(prev)
        val vs = when {
            before.trades == 0 -> ""
            now.winRate > before.winRate -> " You won more often than last week."
            now.winRate < before.winRate -> " You won less often than last week."
            else -> ""
        }
        val f = findings(w, prev).firstOrNull()
        return "Boss, your week's review is in the chat.$vs" +
            (if (f == null) " No costly habit showed up." else " One thing worth a look: ${f.kind.spoken}.")
    }
}
