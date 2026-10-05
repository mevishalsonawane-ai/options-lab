package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The lunch-hour range record (market intelligence, round 26): "does Nifty break the lunch range?", "lunch range
 * breakout record", "is the lunch lull real?", "how wide is BankNifty's lunch range against the morning?", "lunch ki
 * range todne ke baad kya hota hai". From the whole sessions of 1-minute candles on the phone: the lunch window is 12:00
 * to 13:30 - its median range (high to low, % of the open) beside the morning's (09:15-12:00) and the afternoon's
 * (13:30-15:30), and how often it was the narrowest of the three; how often the day's high or low was made inside it
 * (set beside the share of the session it is, 90 of 375 minutes); after 13:30, which side of the lunch range the price
 * first closed a minute beyond (or that it never did), how often the close held beyond that edge, came back inside or
 * ended beyond the other edge, and the median time of the break - and today's lunch range and its break so far beside it.
 * A record of past days on this phone, said with its counts and plainly when there are too few; never a forecast or
 * advice; nothing acts. How busy each half hour is and when the high and low usually come stay [DayClock]'s, the opening
 * range's breaks [RangeBreaks]', the last hour's turn [LastHour]'s. Pure.
 */
object LunchRange {
    /** What was asked: the break's side (+1 above the lunch high, -1 below the lunch low, null both). */
    data class Q(val dir: Int?)

    /**
     * One whole past session: the lunch window's high [hi] and low [lo]; the three stretches' ranges (% of the open);
     * whether the day's high / low was first made inside the lunch window; the side the afternoon first closed beyond the
     * lunch range ([first]: +1, -1, 0 never) and when, whether the other side was closed beyond later ([both]); its [close].
     */
    data class Day(
        val day: LocalDate, val open: Double, val hi: Double, val lo: Double,
        val morningPct: Double, val lunchPct: Double, val afternoonPct: Double,
        val highInLunch: Boolean, val lowInLunch: Boolean,
        val first: Int, val at: LocalTime?, val both: Boolean, val close: Double,
    ) {
        /** The lunch window was narrower than both the morning and the afternoon. */
        val narrowest: Boolean get() = lunchPct < morningPct && lunchPct < afternoonPct
        /** Where the close ended against the broken edge: +1 held beyond it, 0 back inside, -1 beyond the other edge. */
        val held: Int get() = when {
            first == 0 -> 0
            (if (first > 0) close > hi else close < lo) -> 1
            (if (first > 0) close < lo else close > hi) -> -1
            else -> 0
        }
    }

    /** Fewer whole sessions than this: too few to say a record. */
    const val MIN_SESSIONS = 20
    /** Fewer whole sessions than this: said as a small record. */
    const val FEW_SESSIONS = 40
    /** At most this many of the newest whole sessions are read. */
    const val MAX_SESSIONS = 120
    /** A lunch window with fewer 1-minute candles than this (of its 90) is not whole. */
    const val MIN_LUNCH_BARS = 45
    /** An afternoon with fewer 1-minute candles than this (of its 120) is not whole. */
    const val MIN_AFTERNOON_BARS = 30
    val LUNCH_FROM: LocalTime = LocalTime.of(12, 0)
    val LUNCH_TO: LocalTime = LocalTime.of(13, 30)
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the lunch-range record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun days(k: Int) = if (k == 1) "1 day" else "$k days"

    // ---- the questions --------------------------------------------------------------------------------------------

    private val LUNCH = "(lunch|lunch hour|lunch time|lunchtime|lunch window|lunch break|midday|mid day|dopahar|noon)"
    /** The lunch window's own range, box or lull named: "the lunch range", "lunch hour box", "lunch ki range", "lunch lull". */
    private val RANGE = Regex(" $LUNCH (s )?(ki |ke |ka )?(range|ranges|box|band|lull|consolidation|zone|high low|high and low) |" +
        " (range|box|lull|consolidation) (of|during|at|over|in) (the )?$LUNCH |" +
        " (12|12 00|noon) (to|till|until|and) (1 30|13 30|one thirty) (range|box|window|stretch|lull) ")
    private val UP = Regex(" (above|upside|up|higher|upar|upwards|upward) ")
    private val DOWN = Regex(" (below|downside|down|lower|neeche|niche|downwards|downward) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, other spans, gold or VIX.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|kal|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|" +
        "i|me|my|mine|we|our|if|what if|suppose|agar|imagine|scenario|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|backtest|" +
        "week|weekly|month|monthly|year|yearly|order|orders|food|mean|means|meaning|define|gold|vix) ")

    /** What was asked, or null: the record of past lunch windows only, never a forecast, advice or an alert. */
    fun asked(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t) || !RANGE.containsMatchIn(t)) return null
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

    private fun inLunch(t: LocalTime) = !t.isBefore(LUNCH_FROM) && t.isBefore(LUNCH_TO)

    private fun pct(bars: List<Candle>, open: Double) = if (bars.isEmpty() || open <= 0) 0.0 else (bars.maxOf { it.h } - bars.minOf { it.l }) / open * 100

    /** One session read, or null when it is not whole (its start, its end, its lunch window or its afternoon missing). */
    internal fun read(s: MarketStory.Session): Day? {
        if (s.bars.isEmpty() || s.open <= 0) return null
        if (s.bars.first().t.toLocalTime().isAfter(FIRST_BY) || s.bars.last().t.toLocalTime().isBefore(LAST_FROM)) return null
        val morning = s.bars.filter { it.t.toLocalTime().isBefore(LUNCH_FROM) }
        val lunch = s.bars.filter { inLunch(it.t.toLocalTime()) }
        val afternoon = s.bars.filter { !it.t.toLocalTime().isBefore(LUNCH_TO) }
        if (morning.isEmpty() || lunch.size < MIN_LUNCH_BARS || afternoon.size < MIN_AFTERNOON_BARS) return null
        val hi = lunch.maxOf { it.h }; val lo = lunch.minOf { it.l }
        var first = 0; var at: LocalTime? = null; var both = false
        for (b in afternoon) {
            val side = if (b.c > hi) 1 else if (b.c < lo) -1 else 0
            if (side == 0) continue
            if (first == 0) { first = side; at = b.t.toLocalTime() } else if (side != first) { both = true; break }
        }
        return Day(s.day, s.open, hi, lo, pct(morning, s.open), pct(lunch, s.open), pct(afternoon, s.open),
            inLunch(s.high.t.toLocalTime()), inLunch(s.low.t.toLocalTime()), first, at, both, s.close)
    }

    /** The whole sessions before [today] in [bars], newest [MAX_SESSIONS], oldest first. */
    fun past(bars: List<Candle>, today: LocalDate): List<Day> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) }.mapNotNull { read(it) }.takeLast(MAX_SESSIONS)

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    private fun medianTime(ts: List<LocalTime>): LocalTime {
        val s = ts.map { it.toSecondOfDay() }.sorted()
        val m = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        return LocalTime.ofSecondOfDay(m.toLong()).withSecond(0)
    }

    /** What the closes did after a break to one side: "the close held above the lunch high on 9 (60%), ...". */
    private fun sideLine(ds: List<Day>, dir: Int): String {
        val edge = if (dir > 0) "above the lunch high" else "below the lunch low"
        val other = if (dir > 0) "below the lunch low" else "above the lunch high"
        if (ds.isEmpty()) return "The afternoon never closed a minute $edge first."
        val held = ds.count { it.held == 1 }; val back = ds.count { it.held == 0 }; val flip = ds.count { it.held == -1 }
        val bothSides = ds.count { it.both }
        val at = medianTime(ds.mapNotNull { it.at })
        return "The afternoon closed a minute $edge first on ${days(ds.size)} (median time ${hm(at)}): the day's close held $edge on $held (${share(held, ds.size)}), " +
            "came back inside the lunch range on $back (${share(back, ds.size)}) and ended $other on $flip (${share(flip, ds.size)}); " +
            "the price also closed a minute $other later on $bothSides of them."
    }

    /** [q] answered for [m] from its 1-minute candles over several days, at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole ${m.label} session${if (days.size == 1) "" else "s"} on the phone, Boss - too few to say how its lunch hour usually goes (I need $MIN_SESSIONS)."
        val k = days.size
        val lines = ArrayList<String>()
        lines += "Over the last $k whole ${m.label} sessions on this phone (${date(days.first().day)} to ${date(days.last().day)}):"
        val narrow = days.count { it.narrowest }
        lines += "the lunch window (12:00-13:30) had a median range of ${p2(median(days.map { it.lunchPct }))}, against ${p2(median(days.map { it.morningPct }))} " +
            "for the morning (09:15-12:00) and ${p2(median(days.map { it.afternoonPct }))} for the afternoon (13:30-15:30); it was the narrowest of the three on $narrow of $k days (${share(narrow, k)})."
        val hiIn = days.count { it.highInLunch }; val loIn = days.count { it.lowInLunch }; val either = days.count { it.highInLunch || it.lowInLunch }
        lines += "The day's high was made inside it on $hiIn days (${share(hiIn, k)}) and the low on $loIn (${share(loIn, k)}) - either on $either (${share(either, k)}); " +
            "the window is 90 of the session's 375 minutes (24%)."
        val ups = days.filter { it.first > 0 }; val downs = days.filter { it.first < 0 }; val never = days.count { it.first == 0 }
        if (q.dir == null || q.dir > 0) lines += sideLine(ups, 1)
        if (q.dir == null || q.dir < 0) lines += sideLine(downs, -1)
        lines += "The afternoon never closed a minute outside the lunch range on $never of $k days (${share(never, k)})."
        if (k < FEW_SESSIONS) lines += "That is only $k sessions, so a few days move these figures a lot."
        todayLine(m, bars, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today's lunch range (so far, or whole) and whether the afternoon has closed beyond it so far. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val tb = bars.filter { it.t.toLocalDate() == today }.sortedBy { it.t }
        val lunch = tb.filter { inLunch(it.t.toLocalTime()) }
        if (lunch.isEmpty() || tb.first().o <= 0) return null
        val hi = lunch.maxOf { it.h }; val lo = lunch.minOf { it.l }
        val rangePct = (hi - lo) / tb.first().o * 100
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val afternoon = tb.filter { !it.t.toLocalTime().isBefore(LUNCH_TO) }
        if (afternoon.isEmpty())
            return "Today ${m.label}'s lunch range so far is ${n(lo)} to ${n(hi)} (${p2(rangePct)}, to ${hm(lunch.last().t.toLocalTime())})."
        val br = afternoon.firstOrNull { it.c > hi || it.c < lo }
        val said = if (br == null) "the afternoon has ${if (live) "not closed a minute outside it so far" else "never closed a minute outside it"}"
            else "the afternoon first closed a minute ${if (br.c > hi) "above its high" else "below its low"} at ${hm(br.t.toLocalTime())}"
        return "Today ${m.label}'s lunch range was ${n(lo)} to ${n(hi)} (${p2(rangePct)}); $said."
    }
}
