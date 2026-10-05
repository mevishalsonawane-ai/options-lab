package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The open-high / open-low record (market intelligence, round 27): "how often is the open the high of the day?", "open =
 * low days record", "how do open high days close for BankNifty?", "open low wale din nifty kaise band hota hai". From the
 * whole sessions of 1-minute candles on the phone: the days whose open was within 0.05% of the day's high (the open was
 * the high) or of the day's low (the open was the low), how often each came, how those days closed against their open
 * (and by the median of close against open), and their median range beside the other days' - and today's open against
 * its high and low so far beside it. A record of past days on this phone, said with its counts and plainly when there are
 * too few; never a forecast or advice; nothing acts. The gap's record stays [GapRecord]'s, the opening range's breaks
 * [RangeBreaks]', the first move's [FirstMove]'s. Pure.
 */
object OpenHighLow {
    /** What was asked: the side (+1 the open was the high, -1 the open was the low, null both). */
    data class Q(val side: Int?)

    /**
     * One whole past session: its [open], [high], [low] and [close]; whether the open was within [TOLERANCE_PCT] of the
     * high ([atHigh]) or of the low ([atLow]).
     */
    data class Day(val day: LocalDate, val open: Double, val high: Double, val low: Double, val close: Double) {
        val atHigh: Boolean get() = open > 0 && (high - open) / open * 100 <= TOLERANCE_PCT
        val atLow: Boolean get() = open > 0 && (open - low) / open * 100 <= TOLERANCE_PCT
        /** The close against the open, % of the open. */
        val movePct: Double get() = if (open <= 0) 0.0 else (close - open) / open * 100
        /** The day's range, % of the open. */
        val rangePct: Double get() = if (open <= 0) 0.0 else (high - low) / open * 100
    }

    /** The open within this % of the day's high (or low) counts as the open being the high (or low). */
    const val TOLERANCE_PCT = 0.05
    /** Fewer whole sessions than this: too few to say a record. */
    const val MIN_SESSIONS = 20
    /** Fewer whole sessions than this: said as a small record. */
    const val FEW_SESSIONS = 40
    /** Fewer days of one kind than this: too few to say how they closed. */
    const val MIN_KIND = 5
    /** At most this many of the newest whole sessions are read. */
    const val MAX_SESSIONS = 120
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the open-high and open-low record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("=", " equals ").replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun s2(x: Double) = (if (x > 0) "+" else "") + "%.2f%%".format(Locale.ENGLISH, x)
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun dayCount(k: Int) = if (k == 1) "1 day" else "$k days"

    // ---- the questions --------------------------------------------------------------------------------------------

    private val END = "(high|low|top|bottom)"
    /** The open named as the day's high or low: "open = high", "open equals low", "opened at the day's high", "o=h", "open high days". */
    private val OPEN_END = Regex(" open (price )?(equals|equal to|equal|same as|is the|was the|as the|at the|at|the) (the |its )?(day s |days |day )?$END |" +
        " (opened|opens|opening) (at|on|as) (the |its )?(day s |days |day )?$END |" +
        " $END (of the day )?(equals|equal to|was the|is the|was|is|at the|at) (the )?(day s |days )?(open|opening) |" +
        " o equals [hl] | o [hl] (o [hl] )?(days|day|record|setup|kitni|kitne|wale|waale) |" +
        " open (high|low) (or |and |aur |ya |open )*(open (high|low) )?(ka |ke |ki )?(days|day|record|setup|setups|count|kitni|kitne|kitna|wale|waale|vale|din|pattern) |" +
        // Round 19: "kitni baar open hi high hota hai" (the open itself the high), "open high open low ka record".
        " open (hi|hee) (high|low) |" +
        " open (high|low) (or |and |aur |ya )(open )?(high|low) (days|day|record|setup|count|kitni|kitne|wale|waale|din) ")
    private val HIGH = Regex(" (high|top) ")
    private val LOW = Regex(" (low|bottom) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, stock screens, gold or VIX.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|kal|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|" +
        "i|me|my|mine|we|our|if|what if|suppose|agar|imagine|scenario|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|backtest|" +
        "stock|stocks|scan|scanner|screener|share|shares|interest|oi|mean|means|meaning|define|gold|vix) ")

    /** What was asked, or null: the record of past opens at the day's high or low only, never a forecast, advice or an alert. */
    fun asked(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t) || !OPEN_END.containsMatchIn(t)) return null
        val hi = HIGH.containsMatchIn(t) || Regex(" o (equals )?h ").containsMatchIn(t)
        val lo = LOW.containsMatchIn(t) || Regex(" o (equals )?l ").containsMatchIn(t)
        return Q(if (hi == lo) null else if (hi) 1 else -1)
    }

    /** The index asked about: the first index named, Nifty when none is; null when only gold or India VIX is. */
    fun market(markets: List<Market>): Market? {
        val idx = markets.firstOrNull { it in INDICES }
        if (idx == null && markets.any { it == Market.GOLD || it == Market.VIX }) return null
        return idx ?: Market.NIFTY
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /** One session read, or null when it is not whole (its start or its end missing). */
    internal fun read(s: MarketStory.Session): Day? {
        if (s.bars.isEmpty() || s.open <= 0) return null
        if (s.bars.first().t.toLocalTime().isAfter(FIRST_BY) || s.bars.last().t.toLocalTime().isBefore(LAST_FROM)) return null
        return Day(s.day, s.open, s.high.h, s.low.l, s.close)
    }

    /** The whole sessions before [today] in [bars], newest [MAX_SESSIONS], oldest first. */
    fun past(bars: List<Candle>, today: LocalDate): List<Day> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) }.mapNotNull { read(it) }.takeLast(MAX_SESSIONS)

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** How the days of one kind closed: "On the 9 open-high days the close was below the open on 7 (78%), ...". */
    private fun kindLine(ds: List<Day>, rest: List<Day>, side: Int): String {
        val name = if (side > 0) "open-high" else "open-low"
        if (ds.size < MIN_KIND)
            return "That is too few $name days (${ds.size}) to say how they usually closed - I need $MIN_KIND."
        val below = ds.count { it.close < it.open }; val above = ds.count { it.close > it.open }
        val restRange = if (rest.isEmpty()) "" else ", against ${p2(median(rest.map { it.rangePct }))} on the other days"
        return "On the ${ds.size} $name days the close was below the open on $below (${share(below, ds.size)}) and above it on $above (${share(above, ds.size)}), " +
            "a median close of ${s2(median(ds.map { it.movePct }))} from the open; their median range was ${p2(median(ds.map { it.rangePct }))}$restRange."
    }

    /** [q] answered for [m] from its 1-minute candles over several days, at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole ${m.label} session${if (days.size == 1) "" else "s"} on the phone, Boss - too few to say how often its open was the day's high or low (I need $MIN_SESSIONS)."
        val k = days.size
        val highs = days.filter { it.atHigh }; val lows = days.filter { it.atLow }
        val lines = ArrayList<String>()
        lines += "Over the last $k whole ${m.label} sessions on this phone (${date(days.first().day)} to ${date(days.last().day)}),"
        lines += "the open was within 0.05% of the day's high on ${dayCount(highs.size)} (${share(highs.size, k)}) and within 0.05% of the day's low on ${dayCount(lows.size)} (${share(lows.size, k)})."
        if (q.side == null || q.side > 0) lines += kindLine(highs, days.filter { !it.atHigh && !it.atLow }, 1)
        if (q.side == null || q.side < 0) lines += kindLine(lows, days.filter { !it.atHigh && !it.atLow }, -1)
        if (k < FEW_SESSIONS) lines += "That is only $k sessions, so a few days move these figures a lot."
        todayLine(m, bars, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today's open against its high and low so far (or the whole day's). */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val tb = bars.filter { it.t.toLocalDate() == today }.sortedBy { it.t }
        if (tb.isEmpty() || tb.first().o <= 0) return null
        val d = Day(today, tb.first().o, tb.maxOf { it.h }, tb.minOf { it.l }, tb.last().c)
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val sofar = if (live) " so far" else ""
        val offHigh = (d.high - d.open) / d.open * 100; val offLow = (d.open - d.low) / d.open * 100
        val said = when {
            d.atHigh && d.atLow -> "is within 0.05% of both its high and its low$sofar"
            d.atHigh -> "is within 0.05% of its high$sofar (${p2(offHigh)} below it)"
            d.atLow -> "is within 0.05% of its low$sofar (${p2(offLow)} above it)"
            else -> "is ${p2(offHigh)} below its high$sofar and ${p2(offLow)} above its low$sofar"
        }
        return "Today ${m.label}'s open, ${n(d.open)}, $said."
    }
}
