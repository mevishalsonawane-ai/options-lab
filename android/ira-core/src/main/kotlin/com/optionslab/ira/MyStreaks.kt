package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Boss's own streaks (usefulness round 16, 2026-10-05): "am I on a winning streak?", "how many green days in a row?",
 * "my losing streak", "what's my best weekday?", "which day of the week do I lose most?", "lagatar kitne din loss hua".
 * From his own closed trades in the trade book (the bots' only when he has none, and said so): each day he traded
 * counted by its closed trades' net, the run he is on now (green or red days in a row, counting today so far while the
 * session is open), the longest green and red runs on record, the run of trades (wins or losses in a row), and his best
 * and worst weekday (each weekday needs [MIN_DAYS] days on record). A day without trades is not a day of the run; a flat
 * day ends one. Facts from his own record only - never a forecast and never what to do. His account, so the app's
 * account answer (never on a locked phone). Pure.
 */
object MyStreaks {
    /** A weekday is compared only with at least this many traded days on record. */
    const val MIN_DAYS = 3

    data class Day(val date: LocalDate, val net: Double, val trades: Int)
    data class Run(val green: Boolean, val days: List<Day>) {
        val net: Double get() = days.sumOf { it.net }
    }
    data class Weekday(val day: DayOfWeek, val days: Int, val green: Int, val net: Double)

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun dname(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun date(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " ${d.dayOfMonth} " +
        d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    private fun norm(text: String) = Spaced.words(text)

    private val MINE = rx(" (i|my|me|mine|am i|i m|im|i ve|ive|mera|meri|mere|main|maine) ")
    private val RUN = rx(" (winning|losing|win|loss|losses|green|red|profit|profitable|good|bad) (streak|streaks|run|runs|stretch)| (streak|streaks) ")
    private val ROW = rx(" (days?|sessions?|trades?|wins|losses|losers|winners) (in a row|back to back|straight|on the trot)| consecutive (green |red |winning |losing |profitable |loss |losing )?(days|sessions|trades|wins|losses)| in a row | back to back ")
    private val HINGLISH = rx(" (lagatar|lagataar|lagaatar) | kitne din se (loss|profit|munafa|nuksan|nuksaan|green|red|haar|jeet) ")
    private val WEEKDAY = rx(" (best|worst|luckiest|unluckiest|strongest|weakest|most profitable|least profitable|favourite|favorite) (week ?day|weekdays|day of (the )?week|days of (the )?week|day to trade|trading day of the week)" +
        "| which (week ?day|day of (the )?week|day) (do i|did i|am i|have i|i) (make|made|lose|lost|win|won|earn|earned|do best|do worst|trade best|trade worst|do well|do badly|do the best|do the worst|make the most|lose the most|make most|lose most)" +
        "| (week ?day|weekday) (record|breakdown|wise|split|stats)| (by|per) (week ?day|day of (the )?week)" +
        "| (kaun sa|kaunsa|konsa|kon sa) din (best|accha|acha|sabse accha|sabse acha|bura|sabse bura|worst) " +
        "| which (week ?day|day of (the )?week|day) (is|was) my (best|worst|strongest|weakest|luckiest|unluckiest|most profitable|least profitable)" +
        "| do i (lose|make|win|earn|do) (more|most|less|money|better|worse|best|worst) on (mondays|tuesdays|wednesdays|thursdays|fridays) ")
    /** Something else is meant: a market's run, a forecast, the bots' or Jarvis's own record, or an alert. */
    private val NOT = rx(" (nifty|banknifty|bank nifty|sensex|finnifty|vix|market|index|indices|candle|candles|alert|alerts|bot|bots|strategy|strategies|arm|arms|your|you|jarvis s) ")

    /** His own words for a run without "my" (routing round 11): "how many green days in a row", "lagatar kitne din loss hua",
     *  "do I lose more on Mondays" - green, red, winning and losing days are a trader's own (an index's are up and down days). */
    private val OWN = rx(" (green|red|winning|losing|profitable|loss making|losing) (days?|sessions?|trades?) (in a row|back to back|straight|on the trot) |" +
        " (lagatar|lagataar|lagaatar) kitne (din|trade) (se )?(loss|profit|munafa|nuksan|nuksaan|green|red)")

    private fun hit(t: String) = (MINE.containsMatchIn(t) && (RUN.containsMatchIn(t) || ROW.containsMatchIn(t) || HINGLISH.containsMatchIn(t) || WEEKDAY.containsMatchIn(t)) || OWN.containsMatchIn(t)) &&
        !NOT.containsMatchIn(t)

    /** Does [text] ask for Boss's own streaks or his best and worst weekday? */
    fun asked(text: String): Boolean {
        if (Market.mentioned(text).isNotEmpty()) return false
        return hit(norm(text)) || hit(norm(Ask.reading(text)))
    }

    /** Said once after every account's lines. */
    const val CLOSING = "That's your record as it stands, Boss - not a forecast; what you make of it is yours."

    /** Asked about the weekdays (then said first). */
    fun weekdayAsked(text: String): Boolean = WEEKDAY.containsMatchIn(norm(text)) || WEEKDAY.containsMatchIn(norm(Ask.reading(text)))

    /** Each day with closed trades, oldest first: its closed trades' net. */
    fun days(trips: List<Insights.Trip>): List<Day> = trips.groupBy { it.closedAt.toLocalDate() }.toSortedMap()
        .map { (d, ts) -> Day(d, ts.sumOf { it.net }, ts.size) }

    /** The runs of green or red days, oldest first (a flat day ends a run and starts none). */
    fun runs(days: List<Day>): List<Run> {
        val out = ArrayList<Run>()
        var cur = ArrayList<Day>()
        var green: Boolean? = null
        fun close() { if (cur.isNotEmpty() && green != null) out += Run(green!!, cur); cur = ArrayList(); green = null }
        for (d in days) {
            val g = when { d.net > 0.005 -> true; d.net < -0.005 -> false; else -> null }
            if (g == null) { close(); continue }
            if (green != g) close()
            green = g; cur += d
        }
        close()
        return out
    }

    /** The run Boss is on now: the last run, when it reaches the last day he traded (null after a flat day). */
    fun current(days: List<Day>): Run? {
        val last = days.lastOrNull() ?: return null
        return runs(days).lastOrNull()?.takeIf { it.days.last().date == last.date }
    }

    /** Wins (true) or losses (false) in a row up to the last closed trade, and how many; null with no decided trade last. */
    fun tradeRun(trips: List<Insights.Trip>): Pair<Boolean, Int>? {
        val sorted = trips.sortedBy { it.closedAt }
        val last = sorted.lastOrNull() ?: return null
        val win = when { last.net > 0.005 -> true; last.net < -0.005 -> false; else -> return null }
        var n = 0
        for (t in sorted.asReversed()) if (if (win) t.net > 0.005 else t.net < -0.005) n++ else break
        return win to n
    }

    /** Each weekday traded, Monday first. */
    fun weekdays(days: List<Day>): List<Weekday> = days.groupBy { it.date.dayOfWeek }.toSortedMap()
        .map { (w, ds) -> Weekday(w, ds.size, ds.count { it.net > 0.005 }, ds.sumOf { it.net }) }

    private fun span(r: Run) = if (r.days.size == 1) date(r.days[0].date) else "${date(r.days.first().date)} to ${date(r.days.last().date)}"

    /**
     * Boss's streaks for one account ([label] "Paper" or "Zerodha"), from [trips] (every owner's, the owner named on
     * each). [sessionOpen]: today's session is still trading, so today's figure is so far. [weekdayFirst]: the weekdays
     * were asked, so they lead. ([CLOSING] is said once after every account.)
     */
    fun lines(label: String, trips: List<Insights.Trip>, today: LocalDate, sessionOpen: Boolean, weekdayFirst: Boolean = false): List<String> {
        val own = trips.filter { it.owner.startsWith("Manual") && !it.closedAt.toLocalDate().isAfter(today) }
        val mine = own.isNotEmpty()
        val used = if (mine) own else trips.filter { !it.closedAt.toLocalDate().isAfter(today) }
        if (used.isEmpty()) return listOf("$label: no closed trades on record yet, so no streaks to tell.")
        val whose = if (mine) "your own trades" else "all trades (you have none of your own, so these are the bots')"
        val ds = days(used)
        val out = ArrayList<String>()
        val todaySoFar = sessionOpen && ds.last().date == today
        out += "$label, $whose: ${plural(ds.size, "day")} with closed trades on record (${date(ds.first().date)} to ${date(ds.last().date)}); " +
            "a day counts by its closed trades' net, and days without trades are skipped."

        val streak = ArrayList<String>()
        val cur = current(ds)
        val so = if (todaySoFar) ", counting today so far" else ""
        if (cur == null) streak += "Your last trading day, ${date(ds.last().date)}, closed flat$so: no run either way."
        else {
            val n = cur.days.size
            val word = if (cur.green) "green" else "red"
            streak += if (n == 1) {
                val before = ds.getOrNull(ds.size - 2)
                "Your last trading day, ${date(cur.days[0].date)}, was $word (${rs(cur.net)})$so" +
                    (before?.let { b -> ", after a ${when { b.net > 0.005 -> "green"; b.net < -0.005 -> "red"; else -> "flat" }} ${date(b.date)}" } ?: "") + "."
            } else "You're on ${plural(n, "$word day")} in a row$so: ${span(cur)}, ${rs(cur.net)} together."
        }
        val all = runs(ds)
        val bestGreen = all.filter { it.green }.maxWithOrNull(compareBy<Run> { it.days.size }.thenBy { it.days.last().date })
        val bestRed = all.filter { !it.green }.maxWithOrNull(compareBy<Run> { it.days.size }.thenBy { it.days.last().date })
        val longest = listOfNotNull(
            bestGreen?.let { "green ${plural(it.days.size, "day")} (${span(it)}, ${rs(it.net)})" },
            bestRed?.let { "red ${plural(it.days.size, "day")} (${span(it)}, ${rs(it.net)})" },
        )
        if (longest.isNotEmpty()) streak += "Longest runs on record: ${longest.joinToString("; ")}."
        tradeRun(used)?.let { (win, n) ->
            streak += if (n == 1) "Trade by trade, your last trade ${if (win) "won" else "lost"}."
            else "Trade by trade, your last ${plural(n, "trade")} were ${if (win) "wins" else "losses"} in a row."
        }

        val wk = ArrayList<String>()
        val counted = weekdays(ds).filter { it.days >= MIN_DAYS }
        if (counted.size < 2) wk += "Not enough days yet to compare weekdays: each needs $MIN_DAYS days with trades on record."
        else {
            val best = counted.maxWith(compareBy<Weekday> { it.net }.thenBy { it.green.toDouble() / it.days })
            val worst = counted.minWith(compareBy<Weekday> { it.net }.thenBy { it.green.toDouble() / it.days })
            fun say(w: Weekday) = "${dname(w.day)} (${w.green} of ${w.days} green, ${rs(w.net)} net, ${rs(w.net / w.days)} a day)"
            wk += "By weekday, your best is ${say(best)} and your worst ${say(worst)}" +
                (if (best.net < 0) " - every weekday counted is down" else if (worst.net > 0) " - every weekday counted is up" else "") + "."
            val few = weekdays(ds).filter { it.days < MIN_DAYS }.map { dname(it.day) }
            if (few.isNotEmpty()) wk += "Too few days to count yet: ${few.joinToString(", ")}."
        }
        out += if (weekdayFirst) wk + streak else streak + wk
        return out
    }
}
