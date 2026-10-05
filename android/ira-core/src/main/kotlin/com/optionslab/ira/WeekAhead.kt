package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The week ahead, planned by the calendar (Jarvis usefulness, round 15): "what does this week look like?", "week ahead",
 * "is this an expiry week?", "plan for next week", "how many trading days this week?", "is hafte kya hai". From the
 * exchange calendar (holidays and any weekend session), the expiry dates of the loaded contracts and the events the app
 * knows (the Fed's decisions, the budget and, on an unlocked phone only, the ones Boss added himself): how many sessions
 * the week has and how many are left, each day with something on it - an expiry (weekly or monthly, and an expiry moved
 * off its usual weekday by a holiday), the market closed, a weekend session, the last session before three or more days
 * shut, an event - and the indices with no expiry that week with their next one.
 *
 * Calendar facts only: never a forecast or advice, nothing is placed, changed or armed. Market data (Boss's own events
 * only on an unlocked phone; on a locked one only their count is said). Pure.
 */
object WeekAhead {
    enum class Which { THIS, NEXT }

    /** The indices whose expiries are planned, in the order said. */
    val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    /** A break of this many calendar days or more after a session is pointed out (a plain weekend is 2). */
    const val LONG_BREAK = 3

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace(rx("[^a-z0-9' ]"), " ")
        .replace("'", "").replace(rx("\\s+"), " ").trim() + " "

    // Boss's own week (his P&L, trades, notes), the market's move over the week, a forecast or something to do.
    private val NOT = rx(" (i|me|my|mine|our|did|was|were|pnl|p l|profit|loss|losses|trades|traded|said|say|write|wrote|note|notes|" +
        "perform|performance|moved|rally|fell|structure|strength|learned|learnt|review|charges|" +
        "will nifty|forecast|predict|prediction|expected move|should|buy|sell|exit|square off|arm|start|stop|remind|reminder|alert|alerts) ")
    private val WEEK = "(this|the|next|coming|upcoming)"
    private val ASK = rx(
        " (the )?week ahead | weeks ahead | (this|next|coming) weeks (calendar|schedule|plan|planner|lineup|line up|expiries|holidays) |" +
        " (calendar|schedule|plan|planner|lineup|line up|preview|outlook calendar) (for|of) $WEEK week |" +
        " (weekly|week) (calendar|planner|plan|schedule|preview) |" +
        " what (does|will) $WEEK week look like | how does $WEEK week look | how is $WEEK week looking |" +
        " what (is|s) (on|coming up|happening|lined up|in store) (for )?$WEEK week | whats (on|coming up|happening|lined up) (for )?$WEEK week |" +
        " what (is|s) (on|coming up|lined up) (for )?the week | anything (on|coming up|special|important) $WEEK week |" +
        " (is|will) (this|next|it|the coming) (week )?(be )?(an |a )?expiry week | (is|will) (this|next) week (be )?(an |a )?expiry week |" +
        " expiry week (plan|planner|calendar|schedule|preview|look|lookout|check) | plan (for |of )?(the )?expiry week |" +
        " (expiries|expiry dates) (and holidays |and events )?(this|next|for the|for this|for next) week | (holidays|holiday) and expiries (this|next) week |" +
        " how many (trading |market )?(days|sessions) (are there |do we have |left |remaining )?(this|next|in this|in the|in next|in the coming) week |" +
        " how many (trading |market )?(days|sessions) (are )?(left|remaining) (this|in the) week |" +
        " (is|iss|agle|agla|aane wale) hafte (mein |me |ka |ki )?(kya|kya kya|plan|calendar|kitne|expiry|chutti|chhutti|holiday) ")

    /** "This week" or "next week", or null when [text] does not ask for the week's calendar. */
    fun asked(text: String): Which? {
        val t = norm(text)
        if (!ASK.containsMatchIn(t) || NOT.containsMatchIn(t)) return null
        return if (rx(" (next|agle|agla|aane wale) (week|weeks|hafte) ").containsMatchIn(t) &&
            !rx(" (this|is|iss) (week|weeks|hafte) ").containsMatchIn(t)) Which.NEXT else Which.THIS
    }

    /** Monday of the week [which] names from [today]; on a weekend "this week" is the coming one. */
    fun monday(which: Which, today: LocalDate): LocalDate {
        var m = today.with(DayOfWeek.MONDAY)
        if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) m = m.plusWeeks(1)
        return if (which == Which.NEXT) m.plusWeeks(1) else m
    }

    private fun dd(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.dayOfMonth + " " +
        d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    private fun dow(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    private fun rel(d: LocalDate, today: LocalDate) = dd(d) + when (d) { today -> " (today)"; today.plusDays(1) -> " (tomorrow)"; else -> "" }
    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"

    /** [d] is the last expiry of its month among [dates], known only when a later month's date is listed too. */
    private fun monthly(d: LocalDate, dates: List<LocalDate>): Boolean? {
        if (dates.none { it.year * 12 + it.monthValue > d.year * 12 + d.monthValue }) return null
        return dates.none { it.isAfter(d) && it.year == d.year && it.month == d.month }
    }

    /** The weekday most of [dates] fall on, when at least 3 dates and a clear majority (over half) agree. */
    private fun usualDay(dates: List<LocalDate>): DayOfWeek? {
        if (dates.size < 3) return null
        val top = dates.groupingBy { it.dayOfWeek }.eachCount().maxByOrNull { it.value } ?: return null
        return top.key.takeIf { top.value * 2 > dates.size }
    }

    /**
     * The answer. [tradingDay]: the exchange holds a session that day (weekends and holidays not, a weekend session yes);
     * [holiday]: the holiday's name on a weekday, or null; [expiries]: each index's known expiry dates (any order, past
     * ones ignored); [events]: the app's own events for the week (built in, never the index expiries - those come from
     * [expiries]); [owner]: the events Boss added; [unlocked]: his own events may be said. [afterClose]: today's session
     * is over (it is then not counted as left).
     */
    fun answer(which: Which, today: LocalDate, afterClose: Boolean, tradingDay: (LocalDate) -> Boolean, holiday: (LocalDate) -> String?,
               expiries: Map<Market, List<LocalDate>>, events: List<Events.Event>, owner: List<Events.Event>, unlocked: Boolean): String {
        val mon = monday(which, today)
        val days = (0L until 7L).map { mon.plusDays(it) }
        val sessions = days.filter { tradingDay(it) }
        val weekName = when {
            which == Which.NEXT -> "Next week"
            today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY -> "The coming week"
            else -> "This week"
        }
        val out = ArrayList<String>()
        val span = "${dd(mon)} to ${dd(mon.plusDays(4))}"
        if (sessions.isEmpty()) {
            out += "$weekName, Boss ($span), the market has no session on the exchange calendar."
        } else {
            val left = sessions.count { it.isAfter(today) || (it == today && !afterClose) }
            val leftSaid = if (!mon.isAfter(today) && left < sessions.size)
                (if (left == 0) ", none left" else ", ${plural(left, "session")} left" + if (today in sessions && !afterClose) " counting today" else "") else ""
            out += "$weekName, Boss ($span): ${plural(sessions.size, "trading day")}$leftSaid."
        }

        // Each day with something on it.
        val dayLines = linkedMapOf<LocalDate, MutableList<String>>()
        fun on(d: LocalDate, s: String) { dayLines.getOrPut(d) { ArrayList() } += s }
        val upcoming = expiries.mapValues { (_, ds) -> ds.filter { !it.isBefore(today) }.distinct().sorted() }
        val none = ArrayList<String>()
        for (m in INDICES) {
            val ds = upcoming[m] ?: continue
            if (ds.isEmpty()) continue
            val inWeek = ds.filter { it in days }
            if (inWeek.isEmpty()) { ds.firstOrNull { it.isAfter(days.last()) }?.let { none += "${m.label} (next ${dd(it)})" }; continue }
            val usual = usualDay(expiries[m].orEmpty().distinct())
            for (d in inWeek) {
                val kind = when (monthly(d, ds)) { true -> " monthly"; false -> " weekly"; null -> "" }
                var s = "${m.label}$kind expiry"
                if (usual != null && d.dayOfWeek != usual) {
                    val usualDate = mon.plusDays((usual.value - 1).toLong())
                    s += " (not its usual ${dow(usual)}" + (holiday(usualDate)?.let { ", ${dd(usualDate)} is a holiday" } ?: "") + ")"
                }
                on(d, s)
            }
        }
        for (d in days) {
            val wk = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
            if (!wk && !tradingDay(d)) on(d, "market closed" + (holiday(d)?.let { " ($it)" } ?: ""))
            if (wk && tradingDay(d)) on(d, "a special weekend session on the exchange calendar")
            if (tradingDay(d)) {
                var gap = 0; var x = d.plusDays(1)
                while (!tradingDay(x) && gap < 10) { gap++; x = x.plusDays(1) }
                if (gap >= LONG_BREAK) on(d, "the last session before ${plural(gap, "day")} shut (next session ${dd(x)})")
            }
        }
        events.filter { it.day in days }.sortedBy { it.day }.forEach { on(it.day, it.name) }
        val mine = owner.filter { it.day in days }
        if (unlocked) mine.sortedBy { it.day }.forEach { on(it.day, "your event: ${it.name}") }

        if (dayLines.isEmpty()) out += if (upcoming.values.all { it.isEmpty() })
            "No holiday or event on the calendar for it, and I have no expiry dates loaded yet (the contracts load in the morning)."
        else "No expiry, holiday or event on the calendar for it."
        else dayLines.toSortedMap().forEach { (d, ss) -> out += "${rel(d, today)}: ${ss.joinToString("; ")}." }
        if (dayLines.isNotEmpty() && upcoming.values.all { it.isEmpty() }) out += "I have no expiry dates loaded yet (the contracts load in the morning)."
        if (none.isNotEmpty()) out += "No expiry that week for " + none.joinToString(", ") + "."
        if (!unlocked && mine.isNotEmpty()) out += "${plural(mine.size, "event")} you added ${if (mine.size == 1) "is" else "are"} said on an unlocked phone only."
        out += "From the exchange calendar and the loaded contracts, Boss; the plan is yours."
        return out.joinToString(" ")
    }
}
