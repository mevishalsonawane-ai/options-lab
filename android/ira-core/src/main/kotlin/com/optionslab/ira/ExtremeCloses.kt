package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The close-at-the-ends record (market intelligence, round 29): "how often does Nifty close near its high?", "what
 * happens the day after BankNifty closes at the low?", "strong close record", "high pe close hone ke baad agle din kya
 * hota hai". From the whole sessions of 1-minute candles on the phone: the days that closed in the top [END_SHARE] of
 * their own range (at the high end) or the bottom [END_SHARE] (at the low end), how often each came, and what the next
 * session on the phone did - its open against that close (a gap the same way or not) and its close against that close
 * (carried on the same way or turned), with the median next-day change counted in the close's direction. Beside where
 * today's close (or the price now) sits in today's range. A record of past days on this phone, said with its counts and
 * plainly when there are too few; never a forecast or advice; nothing acts. Trend days (opened at one end, closed at the
 * other) stay [MarketMemory]'s, the last hour's record [LastHour]'s, the day before's levels [PriorDay]'s. Pure.
 */
object ExtremeCloses {
    /** What was asked: the end (+1 the high end, -1 the low end, null both). */
    data class Q(val side: Int?)

    /** One whole past session: its high, low and close, and the next whole session's open and close (null when none). */
    data class Day(val day: LocalDate, val high: Double, val low: Double, val close: Double,
                   val nextOpen: Double? = null, val nextClose: Double? = null) {
        /** Where the close sits in the day's range, 0 the low, 1 the high; null on a day with no range. */
        val at: Double? get() = if (high > low) (close - low) / (high - low) else null
        /** +1 closed at the high end, -1 at the low end, 0 neither. */
        val side: Int get() { val a = at ?: return 0; return if (a >= 1 - END_SHARE) 1 else if (a <= END_SHARE) -1 else 0 }
        val hasNext: Boolean get() = nextOpen != null && nextClose != null
        /** The next open's gap from this close, %, signed so + is this close's own direction. */
        val gapPct: Double get() = if (nextOpen == null || close <= 0) 0.0 else (nextOpen - close) / close * 100 * side
        /** The next close from this close, %, signed so + is this close's own direction. */
        val nextPct: Double get() = if (nextClose == null || close <= 0) 0.0 else (nextClose - close) / close * 100 * side
    }

    /** A close within this share of the range from one end is "at that end". */
    const val END_SHARE = 0.1
    /** Fewer whole sessions than this: too few to say a record. */
    const val MIN_SESSIONS = 20
    /** Fewer whole sessions than this: said as a small record. */
    const val FEW_SESSIONS = 40
    /** Fewer end-closes (of the kind) with a next session than this: too few to say what the next day did. */
    const val MIN_KIND = 5
    /** At most this many of the newest whole sessions are read. */
    const val MAX_SESSIONS = 120
    /** A "next" session more than this many calendar days later is not taken as the next day (data missing between). */
    const val NEXT_WITHIN_DAYS = 5L
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the close-at-the-ends record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9%. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun s2(x: Double) = (if (x > 0) "+" else "") + "%.2f%%".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private val PCT = (END_SHARE * 100).toInt()

    // ---- the questions --------------------------------------------------------------------------------------------

    /** A close at one end of the day: "close near the high", "closing at its low", "strong close", "high pe close". */
    private val AT_END = Regex(" ((close|closes|closed|closing|closings|band|bandh|settle|settles|settled|finish|finishes|finished) " +
        "(at|near|on|in|by|around|right at|close to|into)? ?(the |its |day s |the day s |their |top of the |bottom of the |top 10% of the |bottom 10% of the )?" +
        "(very )?(high|low|highs|lows|top|bottom|high end|low end|top end|bottom end|range high|range low|day high|day low|day s high|day s low)" +
        "|(high|low|top|bottom) (pe|par|pr) (close|band|bandh|khatam|settle)" +
        "|(strong|weak|high|low|extreme|top|bottom) (close|closes|closing|closings)) ")
    /** Asked of a record of past days or what followed: "how often", "record", "next day", "agle din", "after". */
    private val RECORD = Regex(" (how often|how many|kitni baar|kitne din|record|history|stats|statistics|usually|generally|typically|" +
        "next day|next session|following day|day after|the day after|agle din|agla din|after|afterwards|ke baad|baad|follow|follows|followed|" +
        "follow through|carry on|carries on|continue|continues|continued|reverse|reverses|reversed|what happens|what happened|kya hota|kya hua|kaisa) ")
    private val HIGH = Regex(" (high|highs|top|high end|top end|range high|day high|day s high|strong) ")
    private val LOW = Regex(" (low|lows|bottom|low end|bottom end|range low|day low|day s low|weak) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, stock screens, a definition,
    // an option's price, gold or VIX, the week's, month's or year's high.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|kal|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|" +
        "i|me|my|mine|we|our|if|what if|suppose|agar|imagine|scenario|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|backtest|" +
        "stock|stocks|scan|scanner|screener|share|shares|mean|means|meaning|define|what is a|what s a|explain|gold|vix|chart|" +
        "call|calls|put|puts|premium|option|options|ce|strike|week s|weekly|52|month s|monthly|year s|all time|record high|record low|yesterday s|previous day s|prior day s) ")

    /** What was asked, or null: the record of closes at the ends of the day's range only, never a forecast or advice. */
    fun asked(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t) || !AT_END.containsMatchIn(t) || !RECORD.containsMatchIn(t)) return null
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

    /** The whole sessions before [today] in [bars], newest [MAX_SESSIONS], oldest first, each with its next whole session. */
    fun past(bars: List<Candle>, today: LocalDate): List<Day> {
        val ss = MarketStory.sessions(bars).filter { it.day.isBefore(today) && whole(it) }.takeLast(MAX_SESSIONS + 1)
        val out = ArrayList<Day>()
        for ((i, s) in ss.withIndex()) {
            val nx = ss.getOrNull(i + 1)?.takeIf { !it.day.isAfter(s.day.plusDays(NEXT_WITHIN_DAYS)) }
            out += Day(s.day, s.bars.maxOf { it.h }, s.bars.minOf { it.l }, s.close, nx?.open, nx?.close)
        }
        return out.takeLast(MAX_SESSIONS)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** What the next session did after the end-closes of one kind. */
    private fun kindLine(ds: List<Day>, side: Int): String {
        val name = if (side > 0) "high-end" else "low-end"
        val way = if (side > 0) "higher" else "lower"
        val sx = ds.filter { it.hasNext }
        if (sx.size < MIN_KIND)
            return "That is too few $name closes with a next session on the phone (${sx.size}) to say what the next day did - I need $MIN_KIND."
        val gapOn = sx.count { it.gapPct > 0 }; val carried = sx.count { it.nextPct > 0 }
        return "After the ${sx.size} $name closes with a next session, the next day opened $way on $gapOn (${share(gapOn, sx.size)}) " +
            "and closed $way than that close on $carried (${share(carried, sx.size)}); the median next-day change was ${s2(median(sx.map { it.nextPct }))}, counted in the close's direction."
    }

    /** [q] answered for [m] from its 1-minute candles over several days, at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole ${m.label} session${if (days.size == 1) "" else "s"} on the phone, Boss - too few to say how often it closed at the ends of its range or what came next (I need $MIN_SESSIONS)."
        val k = days.size
        val tops = days.filter { it.side > 0 }; val bottoms = days.filter { it.side < 0 }
        val lines = ArrayList<String>()
        lines += "Over the last $k whole ${m.label} sessions on this phone (${date(days.first().day)} to ${date(days.last().day)}),"
        lines += "${tops.size} (${share(tops.size, k)}) closed in the top $PCT% of the day's range and ${bottoms.size} (${share(bottoms.size, k)}) in the bottom $PCT%."
        when (q.side) {
            1 -> lines += kindLine(tops, 1)
            -1 -> lines += kindLine(bottoms, -1)
            else -> { lines += kindLine(tops, 1); lines += kindLine(bottoms, -1) }
        }
        if (k < FEW_SESSIONS) lines += "That is only $k sessions, so a few days move these figures a lot."
        todayLine(m, bars, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Where today's close, or the price now, sits in today's range. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val tb = bars.filter { it.t.toLocalDate() == today }.sortedBy { it.t }
        if (tb.isEmpty()) return null
        val hi = tb.maxOf { it.h }; val lo = tb.minOf { it.l }
        if (hi <= lo) return null
        val last = tb.last().c
        val pos = Math.round((last - lo) / (hi - lo) * 100)
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val what = if (live) "now ${m.price(last)}, is" else "closed at ${m.price(last)},"
        return "Today ${m.label}, $what $pos% of the way up the day's range (low ${m.price(lo)}, high ${m.price(hi)})${if (live) " so far" else ""}."
    }
}
