package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The index's opening-range breakout record (market intelligence, round 16): "when Nifty breaks its first 15-minute
 * range, how often does it hold by the close?", "do opening range breakouts usually hold?", "how often do ORB breakouts
 * fail?", "how often does Nifty stay inside the opening range all day?", "opening range breakout record for BankNifty",
 * "opening range todne ke baad kitni baar tikta hai". From the whole sessions of 1-minute candles on the phone: the
 * opening range is the first 15 minutes (5, 30 or the first hour when asked); a break is the first 1-minute close beyond
 * it. For each side: how many sessions broke that way first, how often the close held beyond the broken edge, how often
 * the other side broke too later and how often the close ended beyond the other edge (a full reversal); how many sessions
 * never broke at all; the median time of the first break and the median width of the range - set beside today's range
 * and its break so far. A record of past days on this phone, said with its counts; never a forecast or advice, nothing
 * acts. Where the price stands against today's opening range stays [OpeningRange]'s; Boss's ORB arms are his bots
 * ([BotHealth]); the two-year "first 15 minutes up" study [Study]'s. Pure.
 */
object RangeBreaks {
    /** What was asked: the break's side (+1 up, -1 down, null both) and the opening range's length in minutes. */
    data class Q(val dir: Int?, val minutes: Int)

    /**
     * One whole past session: its opening range [lo] to [hi], the side it broke first ([first]: +1, -1, 0 none) and when,
     * whether the other side broke later ([both]), and its [close].
     */
    data class Day(val day: LocalDate, val open: Double, val hi: Double, val lo: Double, val first: Int, val at: LocalTime?, val both: Boolean, val close: Double) {
        /** The close stayed beyond the edge it first broke. */
        val held: Boolean get() = (first == 1 && close > hi) || (first == -1 && close < lo)
        /** The close ended beyond the other edge: a full reversal. */
        val reversed: Boolean get() = (first == 1 && close < lo) || (first == -1 && close > hi)
        val widthPct: Double get() = (hi - lo) / open * 100
    }

    const val DEFAULT_MINUTES = 15
    /** Fewer whole sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 15
    /** Fewer first breaks of a side than this: too few to say its record. */
    const val MIN_BREAKS = 5
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val FIRST_BY: LocalTime = LocalTime.of(9, 16)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the opening-range record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so it has no opening range, and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun span(minutes: Int) = if (minutes == 60) "the first hour" else "the first $minutes minutes"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** The opening range named: "opening range", "first 15 minute range", "first hour's range", "initial range". */
    private const val RANGE = " (opening range|opening ranges|opening range s|initial range|first (5|15|30|fifteen|five|thirty) (minute|minutes|min|mins) (range|ranges|high|low|high and low|high low)|" +
        "first half hour (range|high|low)|first hour (range|high|low)|first hour s (range|high|low)|opening (15|5|30|fifteen) (minute|minutes|min|mins) range|" +
        "opening range breakout|opening range breakouts|orb breakout|orb breakouts|orb break|orb breaks) "
    /** "ORB" alone is also Boss's ORB arms: only with a breakout word. */
    private const val ORB = " orb (breakout|breakouts|break|breaks|breakdown|breakdowns|record|stats|statistics|hold rate|success rate|fail rate|failure rate) "
    /** A record asked of. */
    private const val HOW = " (how often|how many times|how frequently|how many days|how many sessions|how many of|what share|what percentage|what percent|what fraction|" +
        "usually|normally|typically|generally|tend to|tends to|on average|most times|most of the time|historically|history|record|rate|success rate|hold rate|" +
        "fail rate|failure rate|stats|statistics|odds|chances|probability|reliable|reliability|work|works|hit rate|percentage|" +
        "kitni baar|kitne din|aksar|zyada tar|mostly|" +
        // "Do ORB breakouts fail often?" (routing round 12: it went to Boss's strategies).
        "often|fail|fails|failing) "
    // Forecasts, advice, Boss's own book or bots, a single day (today's own break stays OpeningRange's), a gap, a meaning.
    private const val NOT = " (will|would|going to|gonna|tomorrow|kal|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|trade|trades|trading|" +
        "i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|was|were|did|happened|last time|yesterday|today|aaj|now|right now|abhi|this|" +
        "arm|arms|bot|bots|algo|algos|strategy|strategies|backtest|pine|news|gap|gaps|gapped|mean|means|meaning|define|explain|what is an|what s an) "
    private const val UP = " (up|upside|above|higher|high|upar|bullish|breakout|breakouts) "
    private const val DOWN = " (down|downside|below|lower|low|neeche|bearish|breakdown|breakdowns) "

    /** What was asked, or null. A record of past breaks only: never a forecast, advice, Boss's ORB arms or today's own break. */
    fun asked(text: String): Q? {
        val t = norm(text)
        if (rx(NOT).containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        if (!rx(RANGE).containsMatchIn(t) && !rx(ORB).containsMatchIn(t)) return null
        val hinglish = rx(" (todne|toda|tootne|toot|tootta|break) ").containsMatchIn(t) && rx(" (tikta|tikti|tike|hold|kitni baar|aksar) ").containsMatchIn(t)
        if (!rx(HOW).containsMatchIn(t) && !hinglish) return null
        return Q(dir(t), minutes(t))
    }

    /** The side asked: "breaks above / upside" +1, "breaks below / downside" -1, both or neither null. */
    private fun dir(t: String): Int? {
        // The range's own words ("high and low", "breakout" in "opening range breakout") never pick a side.
        val s = t.replace(rx(" (high and low|high low|opening range breakouts?|orb breakouts?|first hour s|first hour) "), " ")
            .replace(rx(" first (5|15|30|fifteen|five|thirty) (minute|minutes|min|mins) (range|ranges|high|low) "), " ")
        val up = rx(UP).containsMatchIn(s); val down = rx(DOWN).containsMatchIn(s)
        return if (up && !down) 1 else if (down && !up) -1 else null
    }

    private fun minutes(t: String): Int = when {
        rx(" first hour | first hour s | first 60 ").containsMatchIn(t) -> 60
        rx(" first (30|thirty) | first half hour | opening (30|thirty) ").containsMatchIn(t) -> 30
        rx(" first (5|five) | opening (5|five) ").containsMatchIn(t) -> 5
        else -> DEFAULT_MINUTES
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        val m = markets.firstOrNull() ?: return Market.NIFTY
        return if (m == Market.GOLD || m == Market.VIX) markets.firstOrNull { it != Market.GOLD && it != Market.VIX } else m
    }

    // ---- the reading ----------------------------------------------------------------------------------------------

    private fun whole(s: MarketStory.Session) = s.bars.isNotEmpty() && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) &&
        !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    /** One session read with an opening range of [minutes], or null when the range is not over in its bars. */
    fun read(s: MarketStory.Session, minutes: Int): Day? {
        if (s.bars.isEmpty() || s.open <= 0) return null
        val end = OPEN.plusMinutes(minutes.toLong())
        val inRange = s.bars.filter { it.t.toLocalTime().isBefore(end) }
        val after = s.bars.filter { !it.t.toLocalTime().isBefore(end) }
        if (inRange.isEmpty()) return null
        val hi = inRange.maxOf { it.h }; val lo = inRange.minOf { it.l }
        var first = 0; var at: LocalTime? = null; var both = false
        for (b in after) {
            val side = if (b.c > hi) 1 else if (b.c < lo) -1 else 0
            if (side == 0) continue
            if (first == 0) { first = side; at = b.t.toLocalTime() } else if (side != first) { both = true; break }
        }
        return Day(s.day, s.open, hi, lo, first, at, both, s.close)
    }

    /** The whole sessions before [today] in [bars] read with an opening range of [minutes], newest [MAX_SESSIONS], oldest first. */
    fun past(bars: List<Candle>, today: LocalDate, minutes: Int = DEFAULT_MINUTES): List<Day> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) && whole(it) }.mapNotNull { read(it, minutes) }.takeLast(MAX_SESSIONS)

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun medianTime(ts: List<LocalTime>): LocalTime = LocalTime.ofSecondOfDay(median(ts.map { it.toSecondOfDay().toDouble() }).toLong()).withSecond(0)

    /** [q] answered for [m] from [bars] (1-minute candles over several days) at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today, q.minutes)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole ${m.label} session${if (days.size == 1) "" else "s"} on the phone, Boss - too few to say how its opening-range breaks went (I need $MIN_SESSIONS)."
        val end = OPEN.plusMinutes(q.minutes.toLong())
        val lines = ArrayList<String>()
        val ups = days.filter { it.first == 1 }; val downs = days.filter { it.first == -1 }; val none = days.count { it.first == 0 }
        lines += "Over the last ${days.size} whole ${m.label} sessions on this phone (${date(days.first().day)} to ${date(days.last().day)}), with " +
            "${span(q.minutes)} (${hm(OPEN)} to ${hm(end)}) as the opening range and a break as the first 1-minute close beyond it: " +
            "it broke up first on ${ups.size}, down first on ${downs.size} and never broke on $none."
        val sides = when (q.dir) { 1 -> listOf(1 to ups); -1 -> listOf(-1 to downs); else -> listOf(1 to ups, -1 to downs) }
        for ((d, ds) in sides) lines += sideLine(d, ds)
        val broke = days.filter { it.first != 0 }
        if (q.dir == null && broke.size >= MIN_BREAKS) {
            val held = broke.count { it.held }
            lines += "All told the first break held to the close on $held of ${broke.size} (${share(held, broke.size)}); the median first break came at ${hm(medianTime(broke.mapNotNull { it.at }))}."
        }
        lines += "The median opening range was ${p2(median(days.map { it.widthPct }))} of the open."
        todayLine(m, bars, today, now, q.minutes)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    private fun sideLine(d: Int, ds: List<Day>): String {
        val way = if (d == 1) "upside" else "downside"
        val edge = if (d == 1) "above the range high" else "below the range low"
        val other = if (d == 1) "the low" else "the high"
        if (ds.size < MIN_BREAKS) return "Only ${ds.size} $way break${if (ds.size == 1) "" else "s"} came first - too few to say how they held (I need $MIN_BREAKS)."
        val held = ds.count { it.held }; val both = ds.count { it.both }; val rev = ds.count { it.reversed }
        return "Of the ${ds.size} $way breaks first, the close held $edge on $held (${share(held, ds.size)}); " +
            "$other broke too later on $both (${share(both, ds.size)}), and on $rev (${share(rev, ds.size)}) it closed beyond $other - a full reversal. " +
            "Their median first break came at ${hm(medianTime(ds.mapNotNull { it.at }))}."
    }

    /** Today's opening range and its break so far (or the whole day after the close), or null with no candles today. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, minutes: Int): String? {
        val s = MarketStory.sessions(bars).lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() && it.open > 0 } ?: return null
        val end = OPEN.plusMinutes(minutes.toLong())
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        if (live && now.toLocalTime().isBefore(end)) return "Today's opening range is not over yet: it runs to ${hm(end)}."
        val d = read(s, minutes) ?: return null
        val range = "Today ${m.label}'s opening range was ${n(d.lo)} to ${n(d.hi)}"
        if (d.first == 0) return "$range, and it has ${if (live) "not broken yet" else "never broken"}${if (live) " - the session is still on" else ""}."
        val px = s.close
        val where = when { px > d.hi -> "above the range" ; px < d.lo -> "below the range"; else -> "back inside the range" }
        return "$range; it broke ${if (d.first == 1) "up" else "down"} first at ${hm(d.at!!)}" +
            (if (d.both) ", then the other side too" else "") +
            ", and ${if (live) "is $where now at ${n(px)} - the session is still on" else "closed $where at ${n(px)}"}."
    }
}
