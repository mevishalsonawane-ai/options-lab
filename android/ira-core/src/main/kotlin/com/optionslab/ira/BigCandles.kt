package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The big-candle record (market intelligence, round 28): "after a big 5-minute candle in the first hour, does the day
 * continue?", "big candle record for BankNifty", "how often does a big green candle in the morning follow through?",
 * "subah bada candle aane ke baad nifty kya karta hai". From the whole sessions of 1-minute candles on the phone, built
 * into 5-minute candles from 09:15: the days whose first hour (09:15-10:15) had a 5-minute candle that moved [BIG_PCT] or
 * more open to close (the first such candle is the day's), how often that came, and how those days closed against that
 * candle - beyond its close in its own direction (continued), or back past its open (given back) - with the median close
 * from the candle's close, measured in the candle's direction. Beside today's first hour. A record of past days on this
 * phone, said with its counts and plainly when there are too few; never a forecast or advice; nothing acts. A sharp move
 * explained stays [SharpMove]'s, the first half hour's direction [FirstMove]'s, candle patterns [PatternCalls]'. Pure.
 */
object BigCandles {
    /** What was asked: the candle's direction (+1 a big rise, -1 a big fall, null either). */
    data class Q(val side: Int?)

    /** One 5-minute candle: its start, open and close. */
    data class Bar(val start: LocalTime, val open: Double, val close: Double) {
        /** Open to close, % of the open. */
        val movePct: Double get() = if (open <= 0) 0.0 else (close - open) / open * 100
    }

    /**
     * One whole past session: its first big first-hour 5-minute candle ([big], null when none), and the day's [close].
     */
    data class Day(val day: LocalDate, val big: Bar?, val close: Double) {
        /** The candle's direction, +1 up, -1 down, 0 when no big candle. */
        val side: Int get() = big?.let { if (it.movePct > 0) 1 else -1 } ?: 0
        /** The day's close beyond the big candle's close in its direction. */
        val continued: Boolean get() = big != null && (close - big.close) * side > 0
        /** The day's close back past the big candle's open, against its direction. */
        val gaveBack: Boolean get() = big != null && (close - big.open) * side < 0
        /** The close from the big candle's close, % of that close, signed so + is the candle's own direction. */
        val afterPct: Double get() = if (big == null || big.close <= 0) 0.0 else (close - big.close) / big.close * 100 * side
    }

    /** A 5-minute candle moving this % or more, open to close, is big. */
    const val BIG_PCT = 0.4
    /** Fewer whole sessions than this: too few to say a record. */
    const val MIN_SESSIONS = 20
    /** Fewer whole sessions than this: said as a small record. */
    const val FEW_SESSIONS = 40
    /** Fewer big-candle days (of the kind asked) than this: too few to say how they closed. */
    const val MIN_KIND = 5
    /** At most this many of the newest whole sessions are read. */
    const val MAX_SESSIONS = 120
    private val OPEN: LocalTime = LocalTime.of(9, 15)
    private val HOUR_END: LocalTime = LocalTime.of(10, 15)
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the big-candle record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("(\\d)\\s*-?\\s*(min|mins|minute|minutes|m)\\b"), "$1 min ")
        .replace(rx("[^a-z0-9. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun s2(x: Double) = (if (x > 0) "+" else "") + "%.2f%%".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    // ---- the questions --------------------------------------------------------------------------------------------

    /** A big candle named: "big 5-minute candle", "large green bar", "bada candle", "a 0.4% candle". */
    private val BIG_CANDLE = Regex(" (\\d min |five min |five minute )?(big|bigger|large|huge|massive|long|strong|wide|sharp|bada|badi|bade|lamba|lambi|0\\.\\d+) " +
        "(\\d min |five min |five minute |green |red |bullish |bearish |up |down |opening |morning |first hour |early )*(candle|candles|bar|bars|candlestick|candlesticks) ")
    /** Asked of a record of past days, or of the morning: "first hour", "morning", "record", "follow through", "ke baad". */
    private val RECORD = Regex(" (first hour|morning|subah|early|opening hour|in the open|at the open|after|ke baad|baad|record|history|stats|" +
        "statistics|how often|kitni baar|kitne din|usually|generally|follow through|follows through|followed through|follow|continue|continues|continued|" +
        "carry on|carries on|keep going|keeps going|hold|holds|held|reverse|reverses|reversed|give back|gives back|given back|kya karta|kaisa) ")
    private val UP = Regex(" (green|bullish|up|rise|rising|rally|upside) ")
    private val DOWN = Regex(" (red|bearish|down|fall|falling|drop|downside) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, stock screens, a definition,
    // a pattern's name, gold or VIX.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|kal|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|" +
        "i|me|my|mine|we|our|if|what if|suppose|agar|imagine|scenario|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|backtest|" +
        "stock|stocks|scan|scanner|screener|share|shares|mean|means|meaning|define|what is a|what s a|explain|pattern|patterns|engulfing|" +
        "marubozu|doji|hammer|gold|vix|chart) ")

    /** What was asked, or null: the record of big first-hour 5-minute candles only, never a forecast, advice or an alert. */
    fun asked(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t) || !BIG_CANDLE.containsMatchIn(t) || !RECORD.containsMatchIn(t)) return null
        val up = UP.containsMatchIn(t); val down = DOWN.containsMatchIn(t)
        return Q(if (up == down) null else if (up) 1 else -1)
    }

    /** The index asked about: the first index named, Nifty when none is; null when only gold or India VIX is. */
    fun market(markets: List<Market>): Market? {
        val idx = markets.firstOrNull { it in INDICES }
        if (idx == null && markets.any { it == Market.GOLD || it == Market.VIX }) return null
        return idx ?: Market.NIFTY
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /** The 5-minute candles of the first hour (09:15-10:15) built from one day's 1-minute [bars], in time order. */
    internal fun firstHour(bars: List<Candle>): List<Bar> =
        bars.filter { val t = it.t.toLocalTime(); !t.isBefore(OPEN) && t.isBefore(HOUR_END) }
            .sortedBy { it.t }
            .groupBy { (it.t.toLocalTime().toSecondOfDay() - OPEN.toSecondOfDay()) / 300 }
            .toSortedMap().values
            .map { g -> Bar(OPEN.plusSeconds(((g.first().t.toLocalTime().toSecondOfDay() - OPEN.toSecondOfDay()) / 300) * 300L), g.first().o, g.last().c) }

    /** The first big candle in [bars], or null. */
    private fun firstBig(bars: List<Bar>): Bar? = bars.firstOrNull { abs(it.movePct) >= BIG_PCT }

    /** One session read, or null when it is not whole (its start or its end missing). */
    internal fun read(s: MarketStory.Session): Day? {
        if (s.bars.isEmpty() || s.open <= 0) return null
        if (s.bars.first().t.toLocalTime().isAfter(FIRST_BY) || s.bars.last().t.toLocalTime().isBefore(LAST_FROM)) return null
        return Day(s.day, firstBig(firstHour(s.bars)), s.close)
    }

    /** The whole sessions before [today] in [bars], newest [MAX_SESSIONS], oldest first. */
    fun past(bars: List<Candle>, today: LocalDate): List<Day> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) }.mapNotNull { read(it) }.takeLast(MAX_SESSIONS)

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** How the big-candle days of one kind closed. */
    private fun kindLine(ds: List<Day>, side: Int?): String {
        val name = when (side) { 1 -> "big-rise"; -1 -> "big-fall"; else -> "big-candle" }
        if (ds.size < MIN_KIND)
            return "That is too few $name days (${ds.size}) to say how they usually closed - I need $MIN_KIND."
        val on = ds.count { it.continued }; val back = ds.count { it.gaveBack }
        return "On the ${ds.size} $name days the day closed beyond that candle's close, in its direction, on $on (${share(on, ds.size)}), " +
            "and back past the candle's open on $back (${share(back, ds.size)}); the median close was ${s2(median(ds.map { it.afterPct }))} from the candle's close, counted in its direction."
    }

    /** [q] answered for [m] from its 1-minute candles over several days, at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole ${m.label} session${if (days.size == 1) "" else "s"} on the phone, Boss - too few to say what followed a big first-hour candle (I need $MIN_SESSIONS)."
        val k = days.size
        val bigs = days.filter { it.big != null }
        val ups = bigs.filter { it.side > 0 }; val downs = bigs.filter { it.side < 0 }
        val lines = ArrayList<String>()
        lines += "Over the last $k whole ${m.label} sessions on this phone (${date(days.first().day)} to ${date(days.last().day)}),"
        lines += "${bigs.size} (${share(bigs.size, k)}) had a 5-minute candle of ${"%.1f".format(Locale.ENGLISH, BIG_PCT)}% or more in the first hour - ${ups.size} up and ${downs.size} down, counting each day's first such candle."
        when (q.side) {
            1 -> lines += kindLine(ups, 1)
            -1 -> lines += kindLine(downs, -1)
            else -> {
                lines += kindLine(bigs, null)
                if (ups.size >= MIN_KIND && downs.size >= MIN_KIND)
                    lines += "After a big rise it continued on ${ups.count { it.continued }} of ${ups.size}; after a big fall on ${downs.count { it.continued }} of ${downs.size}."
            }
        }
        if (k < FEW_SESSIONS) lines += "That is only $k sessions, so a few days move these figures a lot."
        todayLine(m, bars, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today's first hour: its first big 5-minute candle and where the index is against it, or its biggest candle. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val tb = bars.filter { it.t.toLocalDate() == today }.sortedBy { it.t }
        if (tb.isEmpty()) return null
        val hour = firstHour(tb)
        if (hour.isEmpty()) return null
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val inHour = live && now.toLocalTime().isBefore(HOUR_END)
        val big = firstBig(hour)
        if (big == null) {
            val most = hour.maxByOrNull { abs(it.movePct) }!!
            val sofar = if (inHour) " so far" else ""
            return "Today ${m.label} has had no 5-minute candle of ${"%.1f".format(Locale.ENGLISH, BIG_PCT)}% or more in the first hour$sofar - the biggest was ${s2(most.movePct)} at ${hm(most.start)}."
        }
        val last = tb.last().c
        val side = if (big.movePct > 0) 1 else -1
        val from = if (big.close <= 0) 0.0 else (last - big.close) / big.close * 100 * side
        val where = if (live) "now" else "at the close"
        return "Today ${m.label} had a ${s2(big.movePct)} 5-minute candle at ${hm(big.start)}; $where it is ${s2(from)} from that candle's close, counted in its direction."
    }
}
