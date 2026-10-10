package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * The index's day clock (Jarvis market intelligence, round 13): "when does Nifty usually make its high?", "is the low
 * of the day usually in by now?", "which half hour moves the most?", "is the lunch hour usually quiet?". From the whole
 * past sessions of 1-minute candles on the phone: in which stretch of the day the session's high and low were made, how
 * often each had been made by this time of day, and how wide each half hour usually is (the median range, as % of the
 * price) - each set beside today's own times and half hours. A record of past days on this phone, said with its count;
 * never a forecast or advice, nothing acts. Today's structure stays [Structure]'s and the day's range against usual
 * [MarketStory]'s. Pure.
 */
object DayClock {
    enum class Ask { ALL, HIGH_LOW, BY_NOW, BUSY }

    /** Fewer whole past sessions than this: too few to say a record. */
    const val MIN_SESSIONS = 10
    /** At most this many of the newest whole sessions are read. */
    const val MAX_SESSIONS = 120
    /** A whole session: at least this many 1-minute candles, from by 09:20 to 15:25 or later. */
    const val MIN_BARS = 300
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val CLOSE: LocalTime = LocalTime.of(15, 30)

    /** The stretches of the day the high and low are counted in. */
    enum class Stretch(val from: LocalTime, val to: LocalTime, val said: String) {
        FIRST_HALF_HOUR(LocalTime.of(9, 15), LocalTime.of(9, 45), "the first half hour (09:15-09:45)"),
        MORNING(LocalTime.of(9, 45), LocalTime.of(11, 0), "09:45-11:00"),
        MIDDAY(LocalTime.of(11, 0), LocalTime.of(13, 0), "midday (11:00-13:00)"),
        AFTERNOON(LocalTime.of(13, 0), LocalTime.of(14, 30), "13:00-14:30"),
        LAST_HOUR(LocalTime.of(14, 30), LocalTime.of(15, 30), "the last hour (14:30-15:30)");

        companion object {
            fun of(t: LocalTime): Stretch = entries.lastOrNull { !t.isBefore(it.from) } ?: FIRST_HALF_HOUR
        }
    }

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the day clock for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private fun norm(text: String) = Spaced.words(text)
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    // Forecasts, advice, Boss's own book, a what-if, other spans than the day, the app's own "times" (alerts, reminders).
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|predict|prediction|forecast|should|shall|buy|sell|enter|exit|" +
        "i|me|my|mine|we|our|if|suppose|imagine|scenario|week|weekly|month|monthly|year|52 week|all time|ever|record high|lifetime|" +
        "remind|reminder|alert|alarm|news|headline|headlines|strategy|backtest) ")
    private val USUAL = "(usually|normally|typically|generally|mostly|often|most often|most days|tend to|tends to|on most days|on average)"
    private val EXTREME = "(high|low|highs|lows|top|bottom|peak)"
    private val INDEX = "(nifty|banknifty|bank nifty|finnifty|fin nifty|sensex|the market|the index|market|index|it)"
    /** A stretch of the session named in a question about when the high or low comes (round 10). */
    private val PART = "(first hour|first half hour|first 30 minutes|first 15 minutes|opening hour|opening half hour|open|opening|morning|" +
        "afternoon|last hour|last half hour|closing hour|close|lunch|lunch hour|midday|mid day|second half|first half)"
    private val HIGH_LOW = Regex(
        " (when|what time|which time|at what time|which part of the day|what part of the day|which hour|what hour) (does|do|is|are) ($INDEX )?$USUAL (make|makes|made|set|sets|print|prints|hit|hits|form|forms|put in) (its |the |their |her )?(day s |days |intraday )?$EXTREME |" +
        " (when|what time|which time|at what time|which part of the day|what part of the day|which hour|what hour) (is|are) (the )?(day s |days |daily |intraday )?$EXTREME (of the day )?$USUAL (made|set|in|done|formed|put in) |" +
        " (usual |normal |typical )?(time|timing) of (the )?(day s |days |daily |intraday )?$EXTREME (of the day )?$USUAL |" +
        " (usual |normal |typical )(time|timing) of (the )?(day s |days |daily |intraday )?$EXTREME |" +
        " (day s |days |intraday )?$EXTREME (of the day )?(usually |normally |typically |generally )?kab (banta|banti|bante|aata|aati|hota|hoti) |" +
        " $INDEX (ka |ki )?(high|low) (usually |normally |aksar )?kab (banta|banti|bante|aata|hota) |" +
        // "kab high banta hai", "high usually kitne baje banta hai" (round 10).
        " (kab|kitne baje|kis time) (ka )?(high|low) (usually |normally |aksar )?(banta|banti|bante|aata|hota) |" +
        " (high|low) (usually |normally |aksar )?(kitne baje|kis time) (banta|banti|bante|aata|hota) |" +
        // Round 10: a stretch of the day named - "is the high of the day usually made in the first hour", "does Nifty usually
        // make its high in the morning", "how often is the low made in the first half hour", "when does Nifty usually bottom".
        " (is|are) (the )?(day s |days |daily |intraday )?$EXTREME (of the day )?$USUAL (made|set|formed|put in|in|done) (in|during|before|after|by) (the )?$PART |" +
        " (does|do) ($INDEX )?$USUAL (make|set|print|hit|form|put in) (its |the |their )?(day s |days |intraday )?$EXTREME (of the day )?(in|during|before|after|by) (the )?$PART |" +
        " (how often|how many times|how many days) (is|are|was|were|does|do) (the )?(day s |days |daily |intraday )?$EXTREME (of the day )?(get )?(made|set|formed|come|in|done) (in|during|before|after|by) (the )?$PART |" +
        " (when|what time|at what time) (does|do) ($INDEX )?$USUAL (bottom|top|peak)( out)? |" +
        " (does|do) ($INDEX )?$USUAL (bottom|top|peak)( out)? (in|during|before|after|by) (the )?$PART |" +
        " (how often|how many times|how many days) (does|do|did) ($INDEX )?(make|set|hit|print|form) (its |the |their )?(day s |days |intraday )?$EXTREME (of the day )?(in|during|before|after|by) (the )?$PART ")
    private val BY_NOW = Regex(
        " (is|are) (the )?(day s |days |daily )?$EXTREME (of the day )?(usually |normally |typically |generally )?(already )?(in|done|made|set) (by now|by this time|already) |" +
        " (how often|how many times|how many days) (is|are|was|were|has|have) (the )?(day s |days |daily )?$EXTREME (of the day )?(already )?(been )?(in|done|made|set) by (now|this time|this hour) |" +
        " (has|have) (the )?(day s |days |daily )?$EXTREME (of the day )?(usually |normally |typically )?(already )?(been )?(made|set|in|done) by (now|this time) (on most days|usually|normally|typically) |" +
        " by (now|this time|this hour) (is|are|has|was) (the )?(day s |days )?$EXTREME (of the day )?(usually |normally |typically )(in|made|set|done) |" +
        " (high|low) (usually |normally )?(ban|aa) (chuka|chuki|gaya|gayi) (hota|hoti) (hai )?(ab tak|is time tak) |" +
        // "Has the low usually been made by now" (round 10: the usual word before, not after).
        " (has|have) (the )?(day s |days |daily )?$EXTREME (of the day )?$USUAL (already )?(been )?(made|set|in|done) by (now|this time) |" +
        // "Has the high been made already?" (today's, with how often it is in by now) - it fell to Boss's P&L on "made".
        "^ (has|have) the (day s |days |daily )?(high|low) (of the day )?(already )?been (made|set|put in) (already|by now|yet|for the day) $")
    private val BUSY = Regex(
        " (busiest|quietest|most active|least active|most volatile|least volatile|calmest|slowest|liveliest|deadest|dullest) (time|hour|half hour|part|stretch|period|window|minutes)( of the day| of day| in the day| of the session)? |" +
        " (which|what) (time|hour|half hour|part of the day|time of day|time of the day|stretch of the day) (usually |normally |typically )?(moves|move|is|has) (the )?(most|least|biggest|smallest|busiest|quietest|most active|most volatile|calmest) |" +
        " (is|are) (the )?(lunch|lunch hour|lunch time|lunchtime|midday|mid day|afternoon|opening|first half hour|last hour|closing hour) (hour |session |time )?$USUAL (quiet|slow|dull|dead|calm|busy|volatile|active|wild|choppy) |" +
        " (volatility|range|movement|moves|activity) by (time of day|time of the day|hour|half hour) |" +
        " (time of day|time of the day|hourly|half hourly|intraday) (volatility|range profile|activity|seasonality) |" +
        // Round 10: "when is Nifty most volatile", "is it usually quiet after lunch", "how volatile is the first hour usually",
        // "how does Nifty behave at lunch", and Hinglish - "sabse zyada movement kab hota hai", "lunch mein market shant rehta hai kya".
        " (when|what time) (is|does) ($INDEX )?(usually |normally |typically )?(the )?(most|least) (volatile|active|busy|quiet|lively)( of the day)? |" +
        " (is|are) $INDEX $USUAL (quiet|slow|dull|dead|calm|busy|volatile|active|wild|choppy) (after|during|at|around|before|in) (the )?(lunch|lunch hour|lunchtime|midday|mid day|open|opening|close|last hour|first hour|afternoon|morning) |" +
        " (is|are) (the )?(open|opening|first hour|first half hour|lunch hour|lunch|midday|afternoon|last hour|closing hour) $USUAL (the )?(busiest|quietest|most volatile|most active|calmest|least active|least volatile)( time| part)?( of the day)? |" +
        " (which|what) (time|hour|half hour) (moved|was) (the )?(most|busiest|most volatile|most active|quietest)( today| so far)? |" +
        " how (volatile|busy|quiet|active|wild|choppy) (is|are) (the )?(first hour|first half hour|opening|opening hour|lunch hour|lunch|midday|afternoon|last hour|closing hour) $USUAL |" +
        " how (does|do) $INDEX (usually |normally |typically )?(behave|move|trade) (at|during|around|in) (the )?(lunch|lunch hour|lunchtime|midday|mid day) |" +
        " sabse (zyada|jyada|kam) ([a-z]+ )?(movement|volatility|hilta|hilti|chalta|chalti|move) (kab|kis time|kitne baje) |" +
        " sabse (zyada|jyada|kam) (kab|kis time|kitne baje) (hilta|hilti|chalta|chalti|move karta|volatile) |" +
        " sabse (busy|zyada busy|quiet|shant|volatile) (time|waqt|samay) (kab|kaunsa|kaun sa|kya) |" +
        " (kab|kis time|kitne baje) sabse (zyada|jyada|kam) (movement|volatility|hilta|hilti|chalta|chalti|move) |" +
        " (lunch|dopahar) (mein|me|ke time|time) ($INDEX )?(shant|slow|dull|quiet|thanda|dheema) (rehta|rahta|hota|rehti|rahti) ")
    private val ALL = Regex(" (day clock|days clock|day s clock|time of day (pattern|patterns|stats|statistics|profile|habits)|intraday (seasonality|time pattern|time patterns)|time of the day (pattern|patterns|stats|profile)) ")

    /** Which was asked, or null. A record of past days by time of day only: never a forecast, advice or Boss's own book. */
    fun asked(text: String): Ask? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Ask?>(64)

    private fun askedFresh(text: String): Ask? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        return when {
            BY_NOW.containsMatchIn(t) -> Ask.BY_NOW
            HIGH_LOW.containsMatchIn(t) -> Ask.HIGH_LOW
            BUSY.containsMatchIn(t) -> Ask.BUSY
            ALL.containsMatchIn(t) -> Ask.ALL
            else -> null
        }
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        val m = markets.firstOrNull() ?: return Market.NIFTY
        return if (m == Market.GOLD || m == Market.VIX) markets.firstOrNull { it != Market.GOLD && it != Market.VIX } else m
    }

    /** One whole session's clock: when its high and low were first made, and each half hour's range as % of the open. */
    data class Day(val day: LocalDate, val highAt: LocalTime, val lowAt: LocalTime, val halfHours: Map<LocalTime, Double>)

    /** The start of [t]'s half hour, counted from 09:15. */
    fun slot(t: LocalTime): LocalTime {
        val m = t.hour * 60 + t.minute - (OPEN.hour * 60 + OPEN.minute)
        return OPEN.plusMinutes((Math.floorDiv(m, 30) * 30).toLong())
    }

    private fun clock(s: MarketStory.Session): Day {
        val open = s.open
        val halves = s.bars.groupBy { slot(it.t.toLocalTime()) }.toSortedMap()
            .mapValues { (_, b) -> if (open > 0) (b.maxOf { it.h } - b.minOf { it.l }) / open * 100 else 0.0 }
        return Day(s.day, s.high.t.toLocalTime(), s.low.t.toLocalTime(), halves)
    }

    /** The whole past sessions in [bars] before [today], newest [MAX_SESSIONS], oldest first. */
    fun past(bars: List<Candle>, today: LocalDate): List<Day> =
        // The whole history is read into sessions for each question: kept by its candles and the day ([BarsKept]).
        if (bars.size > KEEP_UP_TO) pastNow(bars, today) else pastKept.of(bars, today) { pastNow(bars, today) }

    private const val KEEP_UP_TO = 30_000
    private val pastKept = BarsKept<List<Day>>(8)

    internal fun pastNow(bars: List<Candle>, today: LocalDate): List<Day> = MarketStory.sessions(bars)
        .filter { it.day.isBefore(today) && it.bars.size >= MIN_BARS && !it.bars.first().t.toLocalTime().isAfter(FIRST_BY) &&
            !it.bars.last().t.toLocalTime().isBefore(LAST_FROM) }
        .takeLast(MAX_SESSIONS).map { clock(it) }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Each half hour's median range (% of the open) across [days], in time order. */
    fun usualHalfHours(days: List<Day>): Map<LocalTime, Double> =
        days.flatMap { it.halfHours.entries }.groupBy({ it.key }, { it.value }).filterValues { it.size >= MIN_SESSIONS }
            .toSortedMap().mapValues { median(it.value) }

    private fun counts(times: List<LocalTime>): String = Stretch.entries.map { st -> st to times.count { Stretch.of(it) == st } }
        .joinToString(", ") { (st, k) -> "${st.said} ${share(k, times.size)} ($k)" }

    private fun mostly(times: List<LocalTime>): Stretch = Stretch.entries.maxBy { st -> times.count { Stretch.of(it) == st } }

    private fun span(t: LocalTime) = "${hm(t)}-${hm(if (t.plusMinutes(30).isAfter(CLOSE)) CLOSE else t.plusMinutes(30))}"

    /** [ask] answered for [m] from [bars] (1-minute candles over several days) at [now] on [today]. */
    fun answer(ask: Ask, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole ${m.label} session${if (days.size == 1) "" else "s"} on the phone, Boss - too few to say when its high and low usually come or which half hour usually moves most (I need $MIN_SESSIONS)."
        val todayBars = bars.filter { it.t.toLocalDate() == today }.sortedBy { it.t }
        val lines = ArrayList<String>()
        when (ask) {
            Ask.HIGH_LOW -> lines += highLow(m, days, todayBars)
            Ask.BY_NOW -> lines += byNow(m, days, todayBars, today, now)
            Ask.BUSY -> lines += busy(m, days, todayBars, now)
            Ask.ALL -> { lines += highLow(m, days, todayBars); lines += busy(m, days, todayBars, now) }
        }
        lines += NOTE
        return lines.joinToString(" ")
    }

    private fun todaySoFar(m: Market, todayBars: List<Candle>): String? {
        if (todayBars.isEmpty()) return null
        val hi = todayBars.maxBy { it.h }
        val lo = todayBars.minBy { it.l }
        return "Today ${m.label}'s high so far is ${n(hi.h)} at ${hm(hi.t.toLocalTime())} and its low ${n(lo.l)} at ${hm(lo.t.toLocalTime())}."
    }

    private fun highLow(m: Market, days: List<Day>, todayBars: List<Candle>): List<String> {
        val hs = days.map { it.highAt }
        val ls = days.map { it.lowAt }
        val out = ArrayList<String>()
        out += "Over the last ${days.size} whole ${m.label} sessions on this phone, the day's high was made in ${counts(hs)}."
        out += "The low: ${counts(ls)}."
        val edge = days.count { Stretch.of(it.highAt) == Stretch.FIRST_HALF_HOUR || Stretch.of(it.highAt) == Stretch.LAST_HOUR ||
            Stretch.of(it.lowAt) == Stretch.FIRST_HALF_HOUR || Stretch.of(it.lowAt) == Stretch.LAST_HOUR }
        out += "Most often the high came in ${mostly(hs).said} and the low in ${mostly(ls).said}; on $edge of ${days.size} sessions " +
            "(${share(edge, days.size)}) the high or the low came in the first half hour or the last hour."
        todaySoFar(m, todayBars)?.let { out += it }
        return out
    }

    private fun byNow(m: Market, days: List<Day>, todayBars: List<Candle>, today: LocalDate, now: LocalDateTime): List<String> {
        val t = now.toLocalTime()
        if (now.toLocalDate() != today || t.isBefore(OPEN) || !t.isBefore(CLOSE))
            return listOf("The market is not open now, Boss, so there is no \"by now\" today - here is when the high and low usually came instead.") +
                highLow(m, days, todayBars)
        val hi = days.count { !it.highAt.isAfter(t) }
        val lo = days.count { !it.lowAt.isAfter(t) }
        val both = days.count { !it.highAt.isAfter(t) && !it.lowAt.isAfter(t) }
        val out = ArrayList<String>()
        out += "It's ${hm(t)}. Over the last ${days.size} whole ${m.label} sessions on this phone, the day's high had already been made by ${hm(t)} " +
            "on $hi (${share(hi, days.size)}), the low on $lo (${share(lo, days.size)}), and both on $both (${share(both, days.size)})."
        todaySoFar(m, todayBars)?.let { out += it }
        return out
    }

    private fun busy(m: Market, days: List<Day>, todayBars: List<Candle>, now: LocalDateTime): List<String> {
        val usual = usualHalfHours(days)
        if (usual.size < 2) return listOf("The ${m.label} sessions on the phone do not cover the half hours well enough to compare them, Boss.")
        val ranked = usual.entries.sortedByDescending { it.value }
        val top = ranked.take(2)
        val calm = ranked.last()
        val lunch = usual.filterKeys { !it.isBefore(LocalTime.of(11, 45)) && it.isBefore(LocalTime.of(13, 15)) }.values
        val out = ArrayList<String>()
        out += "Over the last ${days.size} whole ${m.label} sessions on this phone, the busiest half hour is ${span(top[0].key)} " +
            "(a usual range of ${p2(top[0].value)} of the price), then ${span(top[1].key)} (${p2(top[1].value)}); the quietest is ${span(calm.key)} (${p2(calm.value)})."
        if (lunch.isNotEmpty())
            out += "Around lunch (11:45-13:15) a half hour usually spans ${p2(lunch.average())}, ${"%.1f".format(Locale.ENGLISH, lunch.average() / top[0].value * 100)}% of the busiest one's."
        // Today's last whole half hour against its usual.
        if (todayBars.isNotEmpty() && todayBars.first().o > 0) {
            val open = todayBars.first().o
            val nowT = now.toLocalTime()
            val live = now.toLocalDate() == todayBars.first().t.toLocalDate() && nowT.isBefore(CLOSE)
            val done = todayBars.groupBy { slot(it.t.toLocalTime()) }.toSortedMap().filterKeys { !live || !it.plusMinutes(30).isAfter(nowT) }
            val last = done.entries.lastOrNull()
            val u = last?.let { usual[it.key] }
            if (last != null && u != null && u > 0) {
                val r = (last.value.maxOf { it.h } - last.value.minOf { it.l }) / open * 100
                out += "Today ${span(last.key)} spanned ${p2(r)}, ${"%.1f".format(Locale.ENGLISH, r / u)} times its usual for that half hour."
            }
        }
        return out
    }
}
