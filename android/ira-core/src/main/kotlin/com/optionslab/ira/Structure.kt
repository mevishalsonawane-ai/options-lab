package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * The day's intraday structure (Jarvis market intelligence, 2026-10-05): "what's the structure today?", "is Nifty making
 * higher highs?", "where are the swing levels?", "trend or range so far?". From today's 1-minute candles only: higher
 * highs / higher lows on 5- and 15-minute candles, where the day's high and low were made, whether the price holds above
 * or below the opening range and the prior close (and since when), the last three swing highs and lows with their times,
 * and whether the day so far reads as a trend day or a range day, with the measure said. Facts with numbers and times,
 * never a forecast or advice. The day's story stays [DayStory]'s, the opening range alone [OpeningRange]'s and the
 * market-wide day [MarketStory]'s. Pure.
 */
object Structure {
    enum class Ask { ALL, HIGHER_HIGHS, SWINGS, TREND_RANGE }

    /** Fewer of today's 1-minute candles than this: too early to read a structure. */
    const val MIN_BARS = 30
    /** A swing point: a candle higher (lower) than this many candles each side. */
    const val SWING_K = 2
    /** Trend-like: the net move from the open is at least this share of the day's range (and the price in the outer quarter). */
    const val TREND_SHARE = 0.6
    /** Range-like: the net move from the open is at most this share of the day's range. */
    const val RANGE_SHARE = 0.3
    /** The opening range: the first this many minutes (as [OpeningRange] reads it). */
    const val OR_MINUTES = 15L

    const val NOTE = "Facts from today's candles, Boss, not a forecast."
    const val NOT_HERE = "I read the day's structure for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim() + " "
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%+,.2f".format(Locale.ENGLISH, x)
    private fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    // Forecasts, advice, Boss's own book, other spans (the week's or the daily chart's structure is not today's), counting
    // and "when did it last" (the market memory's), and the app's own "structures" (fees, charges).
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|next|predict|prediction|forecast|should|shall|buy|sell|enter|exit|i|me|my|mine|we|our|" +
        "how many|how often|last time|when did|when was|yesterday|week|weekly|month|monthly|daily|days|sessions|backtest|strategy|" +
        "fee|fees|charge|charges|brokerage|tax|taxes|pricing|cost|margin|app|mean|means|meaning|define|what is a|what is an) ")
    private val STRUCTURE = Regex(" (structure|market structure|price structure|intraday structure|day s structure|todays structure) ")
    private val HIGHER = Regex(" (higher highs?|higher lows?|lower highs?|lower lows?|hh hl|lh ll|hhs|hls) ")
    private val SWINGS = Regex(" (swing levels?|swing highs?|swing lows?|swing points?|swings|swing high and low|swing highs and lows) ")
    private val TREND_RANGE = Regex(" (trend|trending|trendy|one way|directional) (day )?(or|vs|versus) (a )?(range|ranging|range bound|rangebound|sideways|choppy|chop)( day)? |" +
        " (range|ranging|range bound|rangebound|sideways|choppy) (day )?(or|vs|versus) (a )?(trend|trending|trendy|one way|directional)( day)? |" +
        " (is it|is today|today is|is this|is nifty|is banknifty|is bank nifty|is finnifty|is sensex|is the market) (having )?(a |an )?(trend|trending|range|range bound|rangebound|ranging) day |" +
        " (trend|range) day so far | so far (a )?(trend|range) day ")

    /** Which of the four was asked, or null. Today's structure only: never a forecast, advice or another span. */
    fun asked(text: String): Ask? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        return when {
            TREND_RANGE.containsMatchIn(t) -> Ask.TREND_RANGE
            SWINGS.containsMatchIn(t) -> Ask.SWINGS
            HIGHER.containsMatchIn(t) -> Ask.HIGHER_HIGHS
            STRUCTURE.containsMatchIn(t) -> Ask.ALL
            else -> null
        }
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        val m = markets.firstOrNull() ?: return Market.NIFTY
        return if (m == Market.GOLD || m == Market.VIX) markets.firstOrNull { it != Market.GOLD && it != Market.VIX } else m
    }

    /** One swing point: its price and the start of its candle. */
    data class Swing(val price: Double, val at: LocalDateTime)

    /** The structure of one candle size: the last two swing highs and lows, and what they make. */
    data class Shape(val minutes: Int, val highs: List<Swing>, val lows: List<Swing>) {
        /** +1 the newer swing high is higher, -1 lower, 0 equal, null fewer than two. */
        val highWay: Int? get() = if (highs.size < 2) null else highs.takeLast(2).let { (a, b) -> b.price.compareTo(a.price) }
        val lowWay: Int? get() = if (lows.size < 2) null else lows.takeLast(2).let { (a, b) -> b.price.compareTo(a.price) }

        /** "higher highs and higher lows", "lower highs and lower lows", ... or null without two of each. */
        val label: String? get() {
            val h = highWay ?: return null; val l = lowWay ?: return null
            return when {
                h > 0 && l > 0 -> "higher highs and higher lows"
                h < 0 && l < 0 -> "lower highs and lower lows"
                h > 0 && l < 0 -> "a higher high and a lower low (widening)"
                h < 0 && l > 0 -> "a lower high and a higher low (narrowing)"
                else -> "equal swings (flat)"
            }
        }
    }

    /** Where the price stands against a level: +1 above, -1 below, 0 on it; [since] that side since this minute. */
    data class Side(val level: Double, val side: Int, val since: LocalDateTime?)

    /** Today's structure of one index, from its 1-minute candles. */
    data class Read(
        val market: Market,
        val at: LocalDateTime,
        val open: Double,
        val last: Double,
        val high: Swing,
        val low: Swing,
        val five: Shape,
        val fifteen: Shape,
        val swingHighs: List<Swing>,
        val swingLows: List<Swing>,
        val orHigh: Double?,
        val orLow: Double?,
        val orSide: Side?,
        val prevClose: Double?,
        val prevSide: Side?,
        /** The net move from the open as a share of the day's range, 0..1. */
        val share: Double,
        /** Where the price sits in the day's range, 0 (the low) .. 1 (the high). */
        val place: Double,
        /** How often the 1-minute closes crossed the day's open. */
        val openCrosses: Int,
    ) {
        val range: Double get() = high.price - low.price
        val net: Double get() = last - open
        /** +1 trend-like up, -1 trend-like down, 0 range-like, null in between. */
        val dayKind: Int? get() = when {
            range <= 0 -> 0
            share >= TREND_SHARE && net > 0 && place >= 0.75 -> 1
            share >= TREND_SHARE && net < 0 && place <= 0.25 -> -1
            share <= RANGE_SHARE -> 0
            else -> null
        }
    }

    private fun shape(candles: List<Candle>, minutes: Int): Shape {
        val (hi, lo) = Candles.swings(candles, SWING_K)
        return Shape(minutes, hi.map { Swing(candles[it].h, candles[it].t) }, lo.map { Swing(candles[it].l, candles[it].t) })
    }

    /** The side of [level] the 1-minute closes are on now, and since which minute (from [from] on). */
    private fun side(day: List<Candle>, level: Double, from: LocalDateTime): Side {
        val last = day.last()
        val s = last.c.compareTo(level).coerceIn(-1, 1)
        if (s == 0) return Side(level, 0, null)
        val after = day.filter { !it.t.isBefore(from) }
        val flip = after.indexOfLast { it.c.compareTo(level).coerceIn(-1, 1) != s }
        val since = if (flip < 0) after.firstOrNull()?.t else after.getOrNull(flip + 1)?.t
        return Side(level, s, since)
    }

    /** [m]'s structure today from its 1-minute [bars] (any days; today's read, the session before for the prior close), or null. */
    fun read(m: Market, bars: List<Candle>, today: LocalDate): Read? {
        if (m.open == null) return null
        val day = bars.filter { it.t.toLocalDate() == today }.sortedBy { it.t }
        if (day.size < MIN_BARS) return null
        val last = day.last()
        val asOf = last.t.plusMinutes(1)
        val five = Candles.closed(Candles.fold(day, 5, m), 5, asOf)
        val fifteen = Candles.closed(Candles.fold(day, 15, m), 15, asOf)
        val hiBar = day.maxBy { it.h }; val loBar = day.minBy { it.l }
        val openAt = today.atTime(m.open)
        val orEnd = openAt.plusMinutes(OR_MINUTES)
        val or = day.filter { it.t.isBefore(orEnd) }
        val orReady = or.isNotEmpty() && !asOf.isBefore(orEnd)
        val orHi = if (orReady) or.maxOf { it.h } else null
        val orLo = if (orReady) or.minOf { it.l } else null
        val orSide = if (orHi != null && orLo != null) {
            val after = day.filter { !it.t.isBefore(orEnd) }
            when {
                last.c > orHi -> side(after, orHi, orEnd)
                last.c < orLo -> side(after, orLo, orEnd)
                else -> Side(last.c, 0, null)
            }
        } else null
        val prevClose = bars.filter { it.t.toLocalDate().isBefore(today) }.maxByOrNull { it.t }?.c
        val prevSide = prevClose?.let { side(day, it, day.first().t) }
        val s5 = shape(five, 5)
        val open = day.first().o
        val range = hiBar.h - loBar.l
        var crosses = 0
        var prev = 0
        for (b in day) {
            val s = b.c.compareTo(open).coerceIn(-1, 1)
            if (s != 0) { if (prev != 0 && s != prev) crosses++; prev = s }
        }
        return Read(m, last.t, open, last.c, Swing(hiBar.h, hiBar.t), Swing(loBar.l, loBar.t), s5, shape(fifteen, 15),
            s5.highs.takeLast(3), s5.lows.takeLast(3), orHi, orLo, orSide, prevClose, prevSide,
            if (range > 0) abs(last.c - open) / range else 0.0, if (range > 0) (last.c - loBar.l) / range else 0.5, crosses)
    }

    /** "in the first hour", "in the last hour" or "mid-session", for a minute of [m]'s session. */
    private fun part(m: Market, t: LocalDateTime): String {
        val open = m.open ?: return ""
        val close = m.close ?: return ""
        val tm = t.toLocalTime()
        return when {
            tm.isBefore(open.plusHours(1)) -> "in the first hour"
            !tm.isBefore(close.minusHours(1)) -> "in the last hour"
            else -> "mid-session"
        }
    }

    private fun swingList(s: List<Swing>): String = s.joinToString(", ") { "${n(it.price)} (${hm(it.at)})" }

    /** One candle size's structure in words, with the swings it is read from. */
    private fun shapeLine(s: Shape): String {
        val label = s.label ?: return "On ${s.minutes}-minute candles there are not two swing highs and two swing lows yet."
        val (h1, h2) = s.highs.takeLast(2); val (l1, l2) = s.lows.takeLast(2)
        return "On ${s.minutes}-minute candles: $label - swing highs ${n(h1.price)} (${hm(h1.at)}) then ${n(h2.price)} (${hm(h2.at)}), " +
            "swing lows ${n(l1.price)} (${hm(l1.at)}) then ${n(l2.price)} (${hm(l2.at)})."
    }

    private fun extremesLine(r: Read): String =
        "The day's high ${n(r.high.price)} came at ${hm(r.high.at)} (${part(r.market, r.high.at)}), the low ${n(r.low.price)} at ${hm(r.low.at)} (${part(r.market, r.low.at)})."

    private fun sideLine(r: Read): String? {
        val parts = ArrayList<String>()
        val hi = r.orHigh; val lo = r.orLow; val o = r.orSide
        if (hi != null && lo != null && o != null) parts += when (o.side) {
            1 -> "above its opening range (${n(lo)} to ${n(hi)})" + (o.since?.let { " since ${hm(it)}" } ?: "")
            -1 -> "below its opening range (${n(lo)} to ${n(hi)})" + (o.since?.let { " since ${hm(it)}" } ?: "")
            else -> "inside its opening range (${n(lo)} to ${n(hi)})"
        }
        val pc = r.prevClose; val p = r.prevSide
        if (pc != null && p != null) parts += when (p.side) {
            1 -> "above the prior close of ${n(pc)}" + (p.since?.let { " since ${hm(it)}" } ?: "")
            -1 -> "below the prior close of ${n(pc)}" + (p.since?.let { " since ${hm(it)}" } ?: "")
            else -> "right at the prior close of ${n(pc)}"
        }
        if (parts.isEmpty()) return null
        return "At ${n(r.last)} it is " + parts.joinToString(" and ") + " (1-minute closes)."
    }

    private fun kindWord(r: Read): String = when (r.dayKind) {
        1 -> "trend-like so far, up"
        -1 -> "trend-like so far, down"
        0 -> "range-like so far"
        else -> "in between so far - neither a clear trend nor a clear range"
    }

    private fun kindLine(r: Read): String {
        val pctShare = (r.share * 100).toInt(); val pctPlace = (r.place * 100).toInt()
        return "Trend or range: ${kindWord(r)}. The measure: the net move from the open (${pts(r.net)}) is $pctShare% of the day's range of " +
            "${n(r.range)} points, with the price $pctPlace% of the way up that range; I call it trend-like at ${(TREND_SHARE * 100).toInt()}% or more " +
            "with the price in the outer quarter, range-like at ${(RANGE_SHARE * 100).toInt()}% or less. " +
            "The 1-minute closes crossed the day's open ${r.openCrosses} time${if (r.openCrosses == 1) "" else "s"}."
    }

    private fun swingsLine(r: Read): String =
        if (r.swingHighs.isEmpty() && r.swingLows.isEmpty()) "No swing highs or lows on 5-minute candles yet."
        else "Recent swing levels on 5-minute candles - highs: " + (if (r.swingHighs.isEmpty()) "none yet" else swingList(r.swingHighs)) +
            "; lows: " + (if (r.swingLows.isEmpty()) "none yet" else swingList(r.swingLows)) + "."

    /**
     * The answer to [a] for [m] from its 1-minute [bars] on [today], [now] the time now (a candle older than five minutes
     * while the market trades is said). Facts only.
     */
    fun answer(a: Ask, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime? = null): String {
        if (m.open == null || m == Market.VIX) return NOT_HERE
        val todays = bars.count { it.t.toLocalDate() == today }
        val r = read(m, bars, today) ?: return if (todays == 0) "There are no ${m.label} candles for today on the phone yet, Boss, so I can't read its structure."
            else "It's early for ${m.label}, Boss: only $todays minute${if (todays == 1) "" else "s"} of candles today, too few to read a structure (I need $MIN_BARS)."
        val age = now?.takeIf { it.toLocalDate() == today && m.trading(it) && r.at.plusMinutes(6).isBefore(it) }
            ?.let { java.time.Duration.between(r.at, it).toMinutes() }
        val head = "${m.label}'s structure as of ${hm(r.at.plusMinutes(1))}" + (if (age != null) " (the newest candle on the phone; it is $age minutes old)" else "") + ", Boss. "
        val body = when (a) {
            Ask.HIGHER_HIGHS -> listOf(shapeLine(r.five), shapeLine(r.fifteen), extremesLine(r))
            Ask.SWINGS -> listOf(swingsLine(r), extremesLine(r))
            Ask.TREND_RANGE -> listOfNotNull(kindLine(r), sideLine(r))
            Ask.ALL -> listOfNotNull(shapeLine(r.fifteen), shapeLine(r.five), extremesLine(r), sideLine(r), swingsLine(r), kindLine(r))
        }
        return head + body.joinToString(" ") + " " + NOTE
    }

    /** One line for "make the case": Nifty's structure so far, a fact; null without enough of today's candles. */
    fun caseLine(m: Market, bars: List<Candle>, today: LocalDate): String? {
        val r = read(m, bars, today) ?: return null
        val shape = (r.fifteen.label ?: r.five.label)?.let { l -> "$l on ${if (r.fifteen.label != null) 15 else 5}-minute candles" }
        val sides = ArrayList<String>()
        r.orSide?.let { sides += when (it.side) { 1 -> "above its opening range"; -1 -> "below its opening range"; else -> "inside its opening range" } }
        r.prevSide?.let { sides += when (it.side) { 1 -> "above the prior close"; -1 -> "below the prior close"; else -> "at the prior close" } }
        val bits = listOfNotNull(shape, sides.takeIf { it.isNotEmpty() }?.joinToString(" and "),
            "${kindWord(r)} (net move ${(r.share * 100).toInt()}% of the day's range)")
        return "${m.label}'s structure at ${hm(r.at.plusMinutes(1))}: " + bits.joinToString("; ") + "."
    }
}
