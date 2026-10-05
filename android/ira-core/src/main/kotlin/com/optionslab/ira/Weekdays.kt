package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * The index's weekday record (market intelligence, round 15): "are Mondays more volatile?", "which day of the week moves
 * the most?", "how does Nifty usually do on Fridays?", "are expiry days wider than other days?", "weekday record for
 * BankNifty", "monday ko nifty kaisa chalta hai", "expiry ke din range zyada hota hai kya". From the whole sessions of
 * 1-minute candles on the phone: for each weekday, how many sessions, the median day's range (high to low, as % of the
 * open), the median move from the previous close either way and how many closed up - and, from the days the phone knows
 * were expiry days (the option candles saved that day), expiry days against the other days, the last hour (14:30 to
 * the close) beside them. Set beside today's weekday and today's range so far. A record of past days on this phone,
 * said with its counts; never a forecast or advice, nothing acts. The overnight study's weekday finding stays [Study]'s,
 * the last expiries one by one [MarketMemory]'s and today's expiry [ExpiryDay]'s. Pure.
 */
object Weekdays {
    /** What was asked: one weekday ([day]), expiry days against the rest ([expiry]), else every weekday. */
    data class Q(val day: DayOfWeek?, val expiry: Boolean)

    /** One whole past session: its range and move (% of the open / of the previous close), its last hour, an expiry day. */
    data class Day(val day: LocalDate, val rangePct: Double, val movePct: Double?, val lastHourPct: Double?, val expiry: Boolean) {
        val weekday: DayOfWeek get() = day.dayOfWeek
    }

    /** Fewer whole sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 15
    /** Fewer sessions of one weekday (or expiry days) than this: too few to say its record. */
    const val MIN_PER_DAY = 4
    /** Fewer expiry days than this: too few to set against the rest. */
    const val MIN_EXPIRY = 3
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    /** The previous session counts for the move only when at most this many days earlier (a weekend and a holiday). */
    const val MAX_DAYS_APART = 4L
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)
    val LAST_HOUR: LocalTime = LocalTime.of(14, 30)
    val CLOSE: LocalTime = LocalTime.of(15, 30)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the weekday record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."
    private const val NO_EXPIRY = "I only know an expiry day from the option candles saved that day"

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun times(x: Double) = "%.1f".format(Locale.ENGLISH, x)
    private fun name(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    // ---- the questions --------------------------------------------------------------------------------------------

    private val DAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mondays" to DayOfWeek.MONDAY, "somvar" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tuesdays" to DayOfWeek.TUESDAY, "mangalvar" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wednesdays" to DayOfWeek.WEDNESDAY, "budhvar" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thursdays" to DayOfWeek.THURSDAY, "guruvar" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fridays" to DayOfWeek.FRIDAY, "shukravar" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "saturdays" to DayOfWeek.SATURDAY, "shanivar" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sundays" to DayOfWeek.SUNDAY, "ravivar" to DayOfWeek.SUNDAY,
    )
    private val DAY_WORD = " (mondays?|tuesdays?|wednesdays?|thursdays?|fridays?|saturdays?|sundays?|somvar|mangalvar|budhvar|guruvar|shukravar|shanivar|ravivar) "
    private const val PLURAL = " (mondays|tuesdays|wednesdays|thursdays|fridays) "
    /** The record asked of: usual, more or most volatile, wider, how it does on, a Hinglish "kaisa chalta". */
    private const val HOW = " (usually|normally|typically|generally|mostly|on average|tend to|tends to|historically|record|stats|statistics|pattern|effect|" +
        "volatile|volatility|more volatile|less volatile|most volatile|least volatile|bigger|biggest|wider|widest|narrower|narrowest|calmer|calmest|quieter|quietest|" +
        "range|ranges|move more|moves more|move the most|moves the most|move most|moves most|swing|swings|swingy|choppy|" +
        "best day|worst day|kaisa chalta|kaisa rehta|kaisa hota|kaise chalta|aksar|zyada hilta|sabse zyada|" +
        // ("Are Tuesdays quiet", routing round 11.)
        "quiet|calm|busy|busier|busiest) "
    private const val WEEKDAY_NAME = " (day of the week|days of the week|day of week|weekday|weekdays|week day|week days|day wise|daywise|weekday wise|kis din|kaun se din|konse din) "
    private const val WEEKDAY_HOW = " (which|what|record|effect|pattern|stats|statistics|most|least|biggest|widest|quietest|calmest|volatile|best|worst|compare|comparison|breakdown|kaisa|sabse|zyada) "
    private const val EXPIRY = " (expiry day|expiry days|expiry session|expiry sessions|expiries|expiry ke din|expiry wale din|expiry din) "
    private const val EXPIRY_HOW = " (more volatile|less volatile|volatile|bigger|wider|narrower|calmer|quieter|range|ranges|move more|moves more|swing|swings|" +
        "versus|vs|compared|compare|than other days|than normal days|other days|normal days|usually|normally|typically|on average|record|stats|zyada|kam) "
    private const val WHICH_DAY = " which day (has|have|had|sees|gets|gives|is) (the )?(most|biggest|widest|largest|highest|least|smallest|narrowest|lowest|calmest|quietest|busiest) (range|ranges|move|moves|movement|swing|swings|volatility|volatile)? ?| which day (moves|swings) (the )?(most|least) |" +
        " (konsa|kaunsa|kaun sa|kon sa|konse|kaun se) din (sabse|zyada|sabse zyada) (move|hilta|chalta|volatile|range|bada|swing) "
    // Forecasts, advice, Boss's own book, a single past or coming day, the calendar's questions (open, a holiday), a meaning.
    private const val NOT = " (will|would|going to|gonna|tomorrow|kal|next|coming|upcoming|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|trade|trades|trading|" +
        "i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|was|were|did|happened|last|previous|pichle|pichli|this|today|aaj|yesterday|" +
        "open|opens|closed|holiday|holidays|band|khula|khulega|when|kab|mean|means|meaning|define|strategy|strategies|backtest|bot|bots|news|gap|gaps) "

    /** What was asked, or null. A record of past weekdays only: never a forecast, advice, Boss's own book or one day's story. */
    fun asked(text: String): Q? {
        val t = norm(text)
        if (rx(NOT).containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        val ex = rx(EXPIRY).containsMatchIn(t)
        if (ex) return if (rx(EXPIRY_HOW).containsMatchIn(t)) Q(null, true) else null
        val day = rx(DAY_WORD).find(t)?.groupValues?.get(1)?.let { DAYS[it] }
        if (day != null) {
            // A record word ("is Monday usually volatile"), or "Mondays" (plural) asked how they go ("how are Mondays for Nifty").
            val general = rx(PLURAL).containsMatchIn(t) && rx(" (how|kaise|kaisa|kaisi|what about|what are|tell me about) ").containsMatchIn(t)
            return if (rx(HOW).containsMatchIn(t) || general) Q(day, false) else null
        }
        if (rx(WEEKDAY_NAME).containsMatchIn(t) && rx(WEEKDAY_HOW).containsMatchIn(t)) return Q(null, false)
        // "Which day has the biggest range", "which day is the most volatile", "konsa din sabse zyada move hota hai" (routing round 11).
        if (rx(WHICH_DAY).containsMatchIn(t) && !rx(" (mera|meri|mere|maine|apna|apni|apne|expiry) ").containsMatchIn(t)) return Q(null, false)
        return null
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        val m = markets.firstOrNull() ?: return Market.NIFTY
        return if (m == Market.GOLD || m == Market.VIX) markets.firstOrNull { it != Market.GOLD && it != Market.VIX } else m
    }

    // ---- the reading ----------------------------------------------------------------------------------------------

    private fun whole(s: MarketStory.Session) = s.bars.isNotEmpty() && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) &&
        !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    /** The last hour's move (14:30 to the close, either way) as % of the price at 14:30, or null. */
    private fun lastHour(s: MarketStory.Session): Double? {
        val at = s.bars.lastOrNull { !it.t.toLocalTime().isAfter(LAST_HOUR) }?.c?.takeIf { it > 0 } ?: return null
        return abs(s.close - at) / at * 100
    }

    /** The whole sessions before [today] in [bars], newest [MAX_SESSIONS], oldest first; [expiries]: the days known as expiry days. */
    fun past(bars: List<Candle>, today: LocalDate, expiries: Set<LocalDate> = emptySet()): List<Day> {
        val ss = MarketStory.sessions(bars).filter { it.day.isBefore(today) && whole(it) && it.open > 0 }
        val out = ArrayList<Day>()
        for ((i, s) in ss.withIndex()) {
            val prev = ss.getOrNull(i - 1)?.takeIf { ChronoUnit.DAYS.between(it.day, s.day) <= MAX_DAYS_APART && it.close > 0 }
            out += Day(s.day, s.range / s.open * 100, prev?.let { (s.close - it.close) / it.close * 100 }, lastHour(s), s.day in expiries)
        }
        return out.takeLast(MAX_SESSIONS)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** One group's figures: "median range 0.82%, median move 0.45% either way, closed up on 7 of 12 (58%)". */
    private fun figures(ds: List<Day>, lastHour: Boolean = false): String {
        val bits = ArrayList<String>()
        bits += "median range ${p2(median(ds.map { it.rangePct }))}"
        val moves = ds.mapNotNull { it.movePct }
        if (moves.isNotEmpty()) {
            val up = moves.count { it > 0 }
            bits += "median move ${p2(median(moves.map { abs(it) }))} either way"
            bits += "closed up on $up of ${moves.size} (${share(up, moves.size)})"
        }
        if (lastHour) ds.mapNotNull { it.lastHourPct }.takeIf { it.isNotEmpty() }?.let { bits += "the last hour from 14:30 moved a median ${p2(median(it))}" }
        return bits.joinToString(", ")
    }

    /**
     * [q] answered for [m] from [bars] (1-minute candles over several days) at [now] on [today]; [expiries]: the days the
     * phone knows were [m]'s expiry days (today's included when it is one).
     */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, expiries: Set<LocalDate> = emptySet()): String {
        q.day?.takeIf { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }?.let {
            return "${m.label} does not trade on ${name(it)}s, Boss: the exchange is shut at weekends, so there is no ${name(it)} record."
        }
        val days = past(bars, today, expiries)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole ${m.label} session${if (days.size == 1) "" else "s"} on the phone, Boss - too few to say how its weekdays differ (I need $MIN_SESSIONS)."
        val head = "Over the last ${days.size} whole ${m.label} sessions on this phone (${date(days.first().day)} to ${date(days.last().day)})"
        val lines = ArrayList<String>()
        when {
            q.expiry -> lines += expiryLines(head, days)
            q.day != null -> lines += dayLines(head, q.day, days)
            else -> lines += allLines(head, days)
        }
        todayLine(m, bars, today, now, days, expiries, q)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    private fun allLines(head: String, days: List<Day>): List<String> {
        val out = ArrayList<String>()
        val groups = DayOfWeek.entries.take(5).map { w -> w to days.filter { it.weekday == w } }
        out += "$head, by weekday:"
        val said = ArrayList<Pair<DayOfWeek, Double>>()
        for ((w, ds) in groups) {
            if (ds.size < MIN_PER_DAY) { out += "${name(w)}s: only ${ds.size}, too few to say (I need $MIN_PER_DAY)."; continue }
            out += "${name(w)}s (${ds.size}): ${figures(ds)}."
            said += w to median(ds.map { it.rangePct })
        }
        if (said.size >= 2) {
            val wide = said.maxBy { it.second }; val narrow = said.minBy { it.second }
            out += "Widest by median range: ${name(wide.first)} (${p2(wide.second)}); narrowest: ${name(narrow.first)} (${p2(narrow.second)})" +
                (if (narrow.second > 0) ", ${times(wide.second / narrow.second)} times." else ".")
        }
        val ex = days.filter { it.expiry }
        if (ex.size >= MIN_EXPIRY) {
            val weekdays = ex.groupingBy { it.weekday }.eachCount().entries.sortedByDescending { it.value }
            out += "${ex.size} of these were expiry days (" + weekdays.joinToString(", ") { "${it.value} on a ${name(it.key)}" } +
                "), so a weekday's figures carry its expiries; ask \"are expiry days wider\" to see them apart."
        }
        return out
    }

    private fun dayLines(head: String, w: DayOfWeek, days: List<Day>): List<String> {
        val out = ArrayList<String>()
        val ds = days.filter { it.weekday == w }; val rest = days.filter { it.weekday != w }
        if (ds.size < MIN_PER_DAY) return listOf("$head, ${ds.size} ${if (ds.size == 1) "was a ${name(w)}" else "were ${name(w)}s"} - too few to say their record (I need $MIN_PER_DAY).")
        out += "$head, ${ds.size} were ${name(w)}s: ${figures(ds)}."
        out += "The other ${rest.size} days: ${figures(rest)}."
        val a = median(ds.map { it.rangePct }); val b = median(rest.map { it.rangePct })
        if (b > 0) out += "So ${name(w)}s' median range was ${times(a / b)} times the other days'."
        ds.maxBy { it.rangePct }.let { out += "The widest ${name(w)} was ${date(it.day)} (${p2(it.rangePct)})." }
        val ex = ds.count { it.expiry }
        if (ex > 0) out += "$ex of those ${name(w)}s ${if (ex == 1) "was an expiry day" else "were expiry days"}."
        return out
    }

    private fun expiryLines(head: String, days: List<Day>): List<String> {
        val ex = days.filter { it.expiry }; val rest = days.filter { !it.expiry }
        if (ex.size < MIN_EXPIRY)
            return listOf("$head, I know of ${ex.size} expiry day${if (ex.size == 1) "" else "s"} - too few to set against the rest (I need $MIN_EXPIRY). $NO_EXPIRY, Boss.")
        val out = ArrayList<String>()
        out += "$head, ${ex.size} were expiry days: ${figures(ex, lastHour = true)}."
        out += "The other ${rest.size} days: ${figures(rest, lastHour = true)}."
        val a = median(ex.map { it.rangePct }); val b = median(rest.map { it.rangePct })
        if (rest.isNotEmpty() && b > 0) out += "So expiry days' median range was ${times(a / b)} times the other days'."
        val la = ex.mapNotNull { it.lastHourPct }; val lb = rest.mapNotNull { it.lastHourPct }
        if (la.isNotEmpty() && lb.isNotEmpty() && median(lb) > 0) out += "Their last hour moved ${times(median(la) / median(lb))} times the other days'."
        out += "$NO_EXPIRY, so an expiry the phone saved no options for is counted with the other days."
        return out
    }

    /** Today's weekday and its range so far (or its whole range after the close), beside that weekday's median. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, days: List<Day>, expiries: Set<LocalDate>, q: Q): String? {
        val s = MarketStory.sessions(bars).lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() && it.open > 0 } ?: return null
        val w = today.dayOfWeek
        if (q.day != null && q.day != w) return null
        val isExpiry = today in expiries
        if (q.expiry && !isExpiry) return null
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val range = s.range / s.open * 100
        val same = if (q.expiry) days.filter { it.expiry } else days.filter { it.weekday == w }
        val usual = if (same.size >= (if (q.expiry) MIN_EXPIRY else MIN_PER_DAY)) median(same.map { it.rangePct }) else null
        val what = if (q.expiry) "an expiry day" else "a ${name(w)}" + (if (isExpiry) " and an expiry day" else "")
        return "Today, $what, ${m.label}'s range ${if (live) "so far is" else "was"} ${p2(range)}" +
            (usual?.let { " against the median ${if (q.expiry) "expiry day" else name(w)}'s ${p2(it)}" } ?: "") +
            (if (live) " - the session is still on." else ".")
    }
}
