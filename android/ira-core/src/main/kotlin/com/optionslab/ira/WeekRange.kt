package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The week's range record (market intelligence, round 30): "which day of the week usually makes the weekly high?", "weekly
 * range record for Nifty", "how big is a normal week for BankNifty?", "hafte ka low kis din banta hai". From the whole
 * sessions of 1-minute candles on the phone, grouped into calendar weeks (Monday to Friday): each past week with at least
 * [MIN_DAYS] whole sessions on the phone - its range (the week's high to its low, as % of the week's first open), whether it
 * closed above that open, and the weekday of the session that made the week's high and the one that made its low. The
 * median week's range and its middle half, how many weeks closed up, and how the highs and lows fell across the weekdays,
 * set against what chance alone would give each day. Beside this week so far (its range, and the days its high and low
 * came on). A record of past weeks on this phone, said with its counts and plainly when there are too few; never a
 * forecast or advice; nothing acts. Each weekday's own day range stays [Weekdays]'s, how the index did this week
 * [PeriodMove]'s, the day before's levels [PriorDay]'s. Pure.
 */
object WeekRange {
    /** What was asked: the end of the week's range (+1 the high, -1 the low, null both). */
    data class Q(val side: Int?)

    /** One past week: its first day, its open/high/low/close, and the weekdays its high and its low came on. */
    data class Week(val start: LocalDate, val days: Int, val open: Double, val high: Double, val low: Double, val close: Double,
                    val highDay: DayOfWeek, val lowDay: DayOfWeek) {
        /** The week's range, % of its first open. */
        val rangePct: Double get() = if (open <= 0) 0.0 else (high - low) / open * 100
        val up: Boolean get() = close > open
    }

    /** A week with fewer whole sessions than this on the phone is not read (data missing, or a holiday-short week). */
    const val MIN_DAYS = 4
    /** Fewer whole weeks than this: too few to say a record. */
    const val MIN_WEEKS = 8
    /** Fewer whole weeks than this: said as a small record. */
    const val FEW_WEEKS = 20
    /** At most this many of the newest whole weeks are read. */
    const val MAX_WEEKS = 52
    private val FIRST_BY = java.time.LocalTime.of(9, 20)
    private val LAST_FROM = java.time.LocalTime.of(15, 25)
    private val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)
    private val WEEKDAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    const val NOTE = "A record of past weeks on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the week's range record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9%. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun name(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    // ---- the questions --------------------------------------------------------------------------------------------

    /** The week: "weekly", "the week's", "week", "hafte ka". "Day of the week" is a weekday's, never the week's, and is taken out first. */
    private val WEEK = Regex(" (week s|weekly|week|weeks|hafte|hafta|hafte ka|hafte ki|hafte ke|saptah) ")
    private val DAY_OF_WEEK = Regex(" (day|days|din) of (the )?week ")
    /** The week's range, or its high or low: "range", "high", "low", "swing", "how big", "kitna chalta". */
    private val WHAT = Regex(" (high|low|highs|lows|top|bottom|range|ranges|swing|swings|size|wide|width|big|bada|move|moves|chalta|hilta) ")
    /** Asked of a record of past weeks: "usually", "which day", "record", "kis din", "how often". */
    private val RECORD = Regex(" (how often|how many|which day|what day|which weekday|on which day|kis din|kaunse din|kaun se din|konse din|kon se din|" +
        "record|history|stats|statistics|usually|usual|typically|typical|normally|normal|generally|average|median|kitni baar|aam taur|aksar) ")
    private val HIGH = Regex(" (high|highs|top) ")
    private val LOW = Regex(" (low|lows|bottom) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, stock screens, a definition,
    // an option's price, gold or VIX, the 52-week or a month's or year's high, expiry weeks.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|kal|next week|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|" +
        "i|me|my|mine|we|our|if|what if|suppose|agar|imagine|scenario|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|backtest|" +
        "stock|stocks|scan|scanner|screener|share|shares|mean|means|meaning|define|what is a|what s a|explain|gold|vix|chart|" +
        "call|calls|put|puts|premium|option|options|ce|strike|52|month s|monthly|year s|yearly|all time|expiry|expiries|close|closes|closed|closing|" +
        "p&l|pnl|profit|loss|journal|review) ")

    /** What was asked, or null: the record of the week's range and the days of its high and low only, never a forecast or advice. */
    fun asked(text: String): Q? {
        val t = norm(text).replace(DAY_OF_WEEK, " ")
        if (NOT.containsMatchIn(t) || !WEEK.containsMatchIn(t) || !WHAT.containsMatchIn(t) || !RECORD.containsMatchIn(t)) return null
        val up = HIGH.containsMatchIn(t); val down = LOW.containsMatchIn(t)
        return Q(if (up == down) null else if (up) 1 else -1)
    }

    /** The index asked about: the first index named, Nifty when none is; null when only gold or India VIX is. */
    fun market(markets: List<Market>): Market? {
        val idx = markets.firstOrNull { it in INDICES }
        if (idx == null && markets.any { it == Market.GOLD || it == Market.VIX }) return null
        return idx ?: Market.NIFTY
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /** Whole sessions only (their start and end on the phone). */
    private fun whole(s: MarketStory.Session): Boolean =
        s.bars.isNotEmpty() && s.open > 0 && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) && !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    /** The Monday of [d]'s week. */
    fun weekStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

    /** One week from its sessions (oldest first, at least one): the high's and the low's day are the first to reach them. */
    private fun weekOf(ss: List<MarketStory.Session>): Week {
        val hiS = ss.maxBy { s -> s.bars.maxOf { it.h } }; val loS = ss.minBy { s -> s.bars.minOf { it.l } }
        return Week(weekStart(ss.first().day), ss.size, ss.first().open, hiS.bars.maxOf { it.h }, loS.bars.minOf { it.l }, ss.last().close,
            hiS.day.dayOfWeek, loS.day.dayOfWeek)
    }

    /** The whole weeks before [today]'s week in [bars] (each with at least [MIN_DAYS] whole sessions), newest [MAX_WEEKS], oldest first. */
    fun past(bars: List<Candle>, today: LocalDate): List<Week> {
        val thisWeek = weekStart(today)
        return MarketStory.sessions(bars).filter { it.day.isBefore(thisWeek) && it.day.dayOfWeek in WEEKDAYS && whole(it) }
            .groupBy { weekStart(it.day) }.toSortedMap().values
            .filter { it.size >= MIN_DAYS }.map { weekOf(it) }.takeLast(MAX_WEEKS)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun quartile(xs: List<Double>, q: Double): Double { val s = xs.sorted(); return s[((s.size - 1) * q).toInt()] }

    /** How the week's highs (or lows) fell across the weekdays. */
    private fun dayLine(ws: List<Week>, side: Int): String {
        val counts = WEEKDAYS.associateWith { d -> ws.count { (if (side > 0) it.highDay else it.lowDay) == d } }
        val most = counts.maxBy { it.value }
        val what = if (side > 0) "high" else "low"
        return "The week's $what came on " + WEEKDAYS.joinToString(", ") { "${name(it)} ${counts[it]} (${share(counts[it] ?: 0, ws.size)})" } +
            " - most often on ${name(most.key)}."
    }

    /** [q] answered for [m] from its 1-minute candles over several weeks, at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val weeks = past(bars, today)
        if (weeks.size < MIN_WEEKS)
            return "I have only ${weeks.size} whole ${m.label} week${if (weeks.size == 1) "" else "s"} on the phone (weeks with at least $MIN_DAYS whole sessions), Boss - too few to say how big a week usually is or which day makes its high and low (I need $MIN_WEEKS)."
        val k = weeks.size
        val ranges = weeks.map { it.rangePct }
        val ups = weeks.count { it.up }
        val lines = ArrayList<String>()
        lines += "Over the last $k whole ${m.label} weeks on this phone (week of ${date(weeks.first().start)} to week of ${date(weeks.last().start)}),"
        lines += "the median week's range was ${p2(median(ranges))} of its first open (the middle half from ${p2(quartile(ranges, 0.25))} to ${p2(quartile(ranges, 0.75))}), and $ups (${share(ups, k)}) closed above that open."
        when (q.side) {
            1 -> lines += dayLine(weeks, 1)
            -1 -> lines += dayLine(weeks, -1)
            else -> { lines += dayLine(weeks, 1); lines += dayLine(weeks, -1) }
        }
        lines += "With five trading days a week, each day would get about 20% by chance alone; a short week counts only the days it had."
        if (k < FEW_WEEKS) lines += "That is only $k weeks, so a few weeks move these figures a lot."
        thisWeekLine(m, bars, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** This week so far: its range against its first open, and the days its high and low came on. */
    private fun thisWeekLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val start = weekStart(today)
        val ss = MarketStory.sessions(bars).filter { !it.day.isBefore(start) && !it.day.isAfter(today) && it.bars.isNotEmpty() && it.open > 0 }
        if (ss.isEmpty()) return null
        val w = weekOf(ss)
        if (w.high <= w.low) return null
        val live = now.toLocalDate() == today && ss.last().day == today && now.toLocalTime().isBefore(java.time.LocalTime.of(15, 30))
        return "This week ${if (live) "so far" else "on the phone"} (${ss.size} session${if (ss.size == 1) "" else "s"}), ${m.label}'s range is ${p2(w.rangePct)} of the week's first open " +
            "(high ${m.price(w.high)} on ${name(w.highDay)}, low ${m.price(w.low)} on ${name(w.lowDay)})."
    }
}
