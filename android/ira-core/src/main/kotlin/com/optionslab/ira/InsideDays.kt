package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The index's inside-day and narrow-range record (market intelligence, round 19): "after an inside day, how often does
 * Nifty's range expand the next day?", "how often does an NR7 day lead to a bigger day?", "inside day record for
 * BankNifty", "do narrow range days usually break out next day?", "inside day ke baad kitni baar bada move aata hai". From
 * the whole sessions of 1-minute candles on the phone: an inside day is a session whose high and low both sat within the
 * session before's; a narrow-range day (NR7) is a session whose range (high to low) was the narrowest of its own and the
 * six before it. For each, the session after: how often its range was wider than the setup's and by how much, how often it
 * traded above the setup's high, below its low, both or neither, and how often it closed beyond either - set against what
 * followed every session on the phone (a narrow day is narrow, so a wider next day is common after any of them). Today
 * set beside: what the last session was, and today against it. A record of past days on this phone, said with its counts;
 * never a forecast or advice, nothing acts. The inside-bar candle pattern stays the pattern readers'; a day's range by
 * weekday stays [Weekdays]', today's range [Structure]'s. Pure.
 */
object InsideDays {
    /** The setup asked of: an inside day or a narrow-range (NR7) day. */
    enum class Kind { INSIDE, NARROW }

    /** One named session asked of (routing round 14): the last whole one, today, or the newest on a weekday. */
    enum class One { LAST, TODAY, WEEKDAY }

    /**
     * What was asked: one setup, or both (null); with [one], whether one named session was such a day ("was yesterday an
     * NR7 day?") rather than the record - [weekday] the day named for [One.WEEKDAY].
     */
    data class Q(val kind: Kind?, val one: One? = null, val weekday: java.time.DayOfWeek? = null)

    /** One whole session: its [open], [high], [low] and [close]. */
    data class Day(val day: LocalDate, val open: Double, val high: Double, val low: Double, val close: Double) {
        val range: Double get() = high - low
    }

    /** A [setup] session and the whole session right after it ([next]). */
    data class After(val setup: Day, val next: Day) {
        val wider: Boolean get() = next.range > setup.range
        val ratio: Double get() = if (setup.range > 0) next.range / setup.range else 0.0
        val above: Boolean get() = next.high > setup.high
        val below: Boolean get() = next.low < setup.low
        val closedAbove: Boolean get() = next.close > setup.high
        val closedBelow: Boolean get() = next.close < setup.low
    }

    /** Fewer sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 15
    /** Fewer setups (with a session after) than this: too few to say their record. */
    const val MIN_SETUPS = 5
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    /** The narrowest of this many sessions (its own and those before) is a narrow-range day. */
    const val NR = 7
    val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val FIRST_BY: LocalTime = LocalTime.of(9, 16)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the inside-day record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so its days have no session high and low of the kind, and India VIX is not traded."

    private fun norm(text: String) = " " + spacedWords(text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")) + " "
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun x2(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun plural(k: Int, w: String) = if (k == 1) w else w + "s"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** An inside day named. */
    private const val INSIDE = " (inside day|inside days|inside session|inside sessions|inside range day|inside range days|andar wala din|andar ka din) "
    /** A narrow-range day named. */
    private const val NARROW = " (nr7|nr 7|nr4|nr 4|nr7s|narrowest (day|session|range|range day) (in|of) (the last )?(7|seven|a week)|" +
        "narrow range day|narrow range days|narrow range session|narrow range sessions|narrow day|narrow days|narrow session|narrow sessions|" +
        "tight range day|tight range days|tight day|tight days|compressed day|compressed days|compression day|compression days|" +
        "small range day|small range days|chhota range wala din|chhote range wale din) "
    /** The day after asked of. */
    private const val AFTER = " (after|following|next day|next session|day after|the day after|ke baad|agle din|baad) "
    /** Expansion or a breakout asked of. */
    private const val EXPAND = " (expand|expands|expanded|expansion|bigger|wider|larger|breakout|breakouts|break out|breaks out|broke out|" +
        "big move|bigger move|trend day|follow through|follows through|bada move|range badhta|range badh) "
    /** A record asked of. */
    private const val HOW = " (how often|how many times|how frequently|how many days|how many sessions|what share|what percentage|what percent|what fraction|" +
        "usually|normally|typically|generally|tend to|tends to|on average|most times|most of the time|historically|history|record|rate|" +
        "stats|statistics|odds|chances|probability|percentage|kitni baar|kitne din|aksar|zyada tar|mostly|" +
        // "What happens after an inside day?" (routing round 13: not understood)
        "what happens|what usually happens|what typically happens) "
    /** "Inside day record", "NR7 stats" - the record named outright. */
    private const val NAMED = " (inside day|inside days|nr7|nr 7|narrow range day|narrow range days) (record|stats|statistics|behaviour|behavior|tendency|follow through) "
    // Forecasts, advice, Boss's own book, alerts, a single day, weekdays and expiry (Weekdays'), meanings, the candle pattern.
    private const val NOT = " (will|would|going to|gonna|tomorrow|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|trade|trades|trading|" +
        "i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|today|aaj|now|right now|abhi|this|yesterday|kal|" +
        "alert|alerts|notify|remind|reminder|warn|ping|watch|arm|arms|bot|bots|algo|algos|strategy|strategies|backtest|pine|news|" +
        "monday|mondays|tuesday|tuesdays|wednesday|wednesdays|thursday|thursdays|friday|fridays|weekday|weekdays|expiry|expiries|" +
        "inside bar|inside bars|inside candle|candle|candles|candlestick|" +
        "mean|means|meaning|define|explain|what is|what s|what are) "

    // One named session asked of (routing round 14): "was yesterday an NR7 day?", "is today an inside day?", "kal inside day tha kya".
    private const val WEEKDAY = "(monday|tuesday|wednesday|thursday|friday)"
    private const val WHICH_DAY = "(yesterday|yesterday s session|today|today s session|the last session|last session|the previous session|the prior session|" +
        "the last trading day|last trading day|(last )?$WEEKDAY|kal|aaj|pichla session|pichle session|pichla din)"
    private val ONE_EN = rx(" (was|is|were|has) (it |nifty |banknifty |finnifty |sensex |the market )?$WHICH_DAY( been)? (an? |the )?(${INSIDE.trim()}|${NARROW.trim()})( so far)? ")
    private val ONE_EN2 = rx(" (was|is|were) (nifty s |banknifty s |finnifty s |sensex s )?$WHICH_DAY( for [a-z]+)? (an? |the )?(${INSIDE.trim()}|${NARROW.trim()})|" +
        "^ (jarvis )?(was|is) (nifty|banknifty|finnifty|sensex|the market|it) (an? |on an? )?(${INSIDE.trim()}|${NARROW.trim()}) (today|yesterday|so far|kal|aaj) ")
    private val ONE_HI = rx(" (kya )?([a-z]+ (ka|ke|ki) )?$WHICH_DAY( ka din| ka session)? (an? )?(${INSIDE.trim()}|${NARROW.trim()}|nr7 day|nr7 din|inside din) (tha|hai|thi|hua|raha|rha)( kya)? ")
    // A forecast, advice, Boss's own book, an alert, the candle pattern, a meaning, gold.
    private const val ONE_NOT = " (will|would|going to|gonna|tomorrow|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|trade|trades|trading|" +
        "i|me|my|mine|we|our|what if|suppose|imagine|alert|alerts|notify|remind|reminder|warn|ping|watch|arm|arms|bot|bots|algo|algos|strategy|strategies|" +
        "backtest|pine|news|inside bar|inside bars|inside candle|candle|candles|candlestick|mean|means|meaning|define|explain|how often|usually|record|after|next day|ke baad|agle din) "

    private fun one(t: String): Q? {
        if (rx(ONE_NOT).containsMatchIn(t)) return null
        if (!ONE_EN.containsMatchIn(t) && !ONE_EN2.containsMatchIn(t) && !ONE_HI.containsMatchIn(t)) return null
        val inside = rx(INSIDE).containsMatchIn(t) || " inside din " in t
        val narrow = rx(NARROW).containsMatchIn(t) || rx(" nr7 (day|din) ").containsMatchIn(t)
        val kind = if (inside && !narrow) Kind.INSIDE else if (narrow && !inside) Kind.NARROW else null
        val wd = rx(" $WEEKDAY ").find(t)?.groupValues?.get(1)?.let { java.time.DayOfWeek.valueOf(it.uppercase(Locale.ENGLISH)) }
        val which = when {
            wd != null -> One.WEEKDAY
            rx(" (today|today s session|aaj) ").containsMatchIn(t) -> One.TODAY
            else -> One.LAST
        }
        return Q(kind, which, wd)
    }

    /**
     * What was asked, or null. The record of past setups, or (routing round 14) whether one named session was such a day
     * ([Q.one]); never a forecast, advice, an alert or the candle pattern.
     */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        one(t)?.let { return it }
        if (rx(NOT).containsMatchIn(t)) return null
        val inside = rx(INSIDE).containsMatchIn(t)
        val narrow = rx(NARROW).containsMatchIn(t)
        if (!inside && !narrow) return null
        val named = rx(NAMED).containsMatchIn(t)
        if (!named && !(rx(HOW).containsMatchIn(t) && (rx(AFTER).containsMatchIn(t) || rx(EXPAND).containsMatchIn(t)))) return null
        return Q(if (inside && !narrow) Kind.INSIDE else if (narrow && !inside) Kind.NARROW else null)
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        val m = markets.firstOrNull() ?: return Market.NIFTY
        return if (m == Market.GOLD || m == Market.VIX) markets.firstOrNull { it != Market.GOLD && it != Market.VIX } else m
    }

    // ---- the reading ----------------------------------------------------------------------------------------------

    private fun whole(s: MarketStory.Session) = s.bars.isNotEmpty() && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) &&
        !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    private fun day(s: MarketStory.Session): Day? {
        if (s.bars.isEmpty()) return null
        val d = Day(s.day, s.open, s.bars.maxOf { it.h }, s.bars.minOf { it.l }, s.close)
        return d.takeIf { it.open > 0 && it.range >= 0 }
    }

    /**
     * The sessions before [today] in [bars], newest [MAX_SESSIONS], oldest first; a session that is not whole (a half day,
     * or one the phone missed part of) is kept as null, so nothing is set against a session it did not follow.
     */
    fun past(bars: List<Candle>, today: LocalDate): List<Day?> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) }.takeLast(MAX_SESSIONS).map { if (whole(it)) day(it) else null }

    /** Whether [i] in [days] is an inside day (both it and the session before whole). */
    fun inside(days: List<Day?>, i: Int): Boolean {
        val d = days.getOrNull(i) ?: return false
        val p = days.getOrNull(i - 1) ?: return false
        return d.high <= p.high && d.low >= p.low
    }

    /** Whether [i] in [days] is the narrowest of its own and the [NR] - 1 whole sessions before it. */
    fun narrow(days: List<Day?>, i: Int): Boolean {
        if (i < NR - 1) return false
        val d = days.getOrNull(i) ?: return false
        val before = (i - NR + 1 until i).map { days[it] ?: return false }
        return before.all { d.range < it.range }
    }

    private fun isSetup(days: List<Day?>, i: Int, k: Kind) = if (k == Kind.INSIDE) inside(days, i) else narrow(days, i)

    /** Each [k] setup in [days] with the whole session right after it. */
    fun afters(days: List<Day?>, k: Kind): List<After> =
        days.indices.filter { isSetup(days, it, k) }.mapNotNull { i -> days.getOrNull(i + 1)?.let { After(days[i]!!, it) } }

    /** Every whole session with the whole session right after it: the base to set the setups against. */
    fun all(days: List<Day?>): List<After> =
        (0 until days.size - 1).mapNotNull { i -> val a = days[i]; val b = days[i + 1]; if (a != null && b != null) After(a, b) else null }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    private fun nameOf(k: Kind, many: Boolean) = when (k) {
        Kind.INSIDE -> if (many) "inside days" else "inside day"
        Kind.NARROW -> if (many) "narrow-range (NR7) days" else "narrow-range (NR7) day"
    }

    /** [q] answered for [m] from [bars] (1-minute candles over several days) at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        if (q.one != null) return oneDay(q, m, bars, today, now)
        val days = past(bars, today)
        val whole = days.count { it != null }
        if (whole < MIN_SESSIONS)
            return "I have only $whole whole ${m.label} ${plural(whole, "session")} on the phone, Boss - too few to say what followed inside or narrow days (I need $MIN_SESSIONS)."
        val first = days.first { it != null }!!; val last = days.last { it != null }!!
        val kinds = q.kind?.let { listOf(it) } ?: listOf(Kind.INSIDE, Kind.NARROW)
        val lines = ArrayList<String>()
        val counts = kinds.map { k -> days.indices.count { isSetup(days, it, k) } }
        lines += "Over the last $whole whole ${m.label} sessions on this phone (${date(first.day)} to ${date(last.day)}), with an inside day as a session whose high " +
            "and low both stayed within the session before's and a narrow-range (NR7) day as the narrowest high-to-low of its own and the six before it: " +
            kinds.zip(counts).joinToString(" and ") { (k, c) -> "$c " + (if (k == Kind.INSIDE) plural(c, "inside day") else "NR7 " + plural(c, "day")) } + "."
        for (k in kinds) lines += partLine(k, afters(days, k))
        val base = all(days)
        if (base.isNotEmpty()) {
            val w = base.count { it.wider }
            lines += "For comparison, after every whole session on the phone the next one's range was wider on $w of ${base.size} (${share(w, base.size)}), " +
                "a median ${x2(median(base.map { it.ratio }))} times the day before's - set the share above against that, not against half."
        }
        todayLine(m, bars, days, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /**
     * "Was yesterday an NR7 day?", "is today an inside day?" (routing round 14): the one session named - the last whole one
     * (said with its date, so a Monday's "yesterday" is Friday), today so far, or the newest on the weekday named - set
     * against the session before it (inside or not) and the six before it (the narrowest of seven or not), each said with
     * its figures. Today's is "so far" while the session is on. Facts from the phone's own candles; never a forecast.
     */
    fun oneDay(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today).toMutableList()
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val todays = MarketStory.sessions(bars).lastOrNull { it.day == today }?.let { day(it) }
        val i: Int = when (q.one) {
            One.TODAY -> {
                if (todays == null) return "${m.label} hasn't traded today yet, Boss, so today is neither an inside day nor a narrow one so far."
                days += todays; days.size - 1
            }
            One.WEEKDAY -> days.indexOfLast { it != null && it.day.dayOfWeek == q.weekday }.takeIf { it >= 0 }
                ?: return "I have no whole ${m.label} session on a ${q.weekday?.getDisplayName(TextStyle.FULL, Locale.ENGLISH) ?: "day"} on the phone, Boss."
            else -> days.indexOfLast { it != null }.takeIf { it >= 0 }
                ?: return "I have no whole ${m.label} session on the phone yet, Boss."
        }
        val d = days[i]!!
        val soFar = q.one == One.TODAY && live
        val who = when (q.one) {
            One.TODAY -> "${m.label} today (${date(d.day)})${if (soFar) " so far" else ""}"
            One.WEEKDAY -> "the newest whole ${m.label} session on that weekday was ${date(d.day)}"
            else -> "the last whole ${m.label} session was ${date(d.day)}"
        }
        val prev = days.getOrNull(i - 1)
        val insideLine = when {
            prev == null -> "I can't say if it was an inside day: the session before it is not whole on the phone."
            inside(days, i) -> "It ${if (soFar) "is" else "was"} an inside day: its high ${n(d.high)} and low ${n(d.low)} ${if (soFar) "are" else "were"} both within ${date(prev.day)}'s high ${n(prev.high)} and low ${n(prev.low)}."
            else -> "It ${if (soFar) "is not" else "was not"} an inside day: " + listOfNotNull(
                if (d.high > prev.high) "its high ${n(d.high)} went above ${date(prev.day)}'s ${n(prev.high)}" else null,
                if (d.low < prev.low) "its low ${n(d.low)} went below ${date(prev.day)}'s ${n(prev.low)}" else null).joinToString(" and ") + "."
        }
        val before = if (i >= NR - 1) (i - NR + 1 until i).map { days[it] } else emptyList()
        val narrowLine = when {
            before.size < NR - 1 || before.any { it == null } -> "I can't say if it was the narrowest of $NR (NR7): the phone does not hold the six whole sessions before it."
            narrow(days, i) -> "It ${if (soFar) "is" else "was"} an NR7 day${if (soFar) " so far" else ""}: its range of ${n(d.range)} points ${if (soFar) "is" else "was"} the narrowest of its own and the six before it " +
                "(the narrowest of those six was ${n(before.minOf { it!!.range })})."
            else -> { val low = before.filterNotNull().minByOrNull { it.range }!!
                "It ${if (soFar) "is not" else "was not"} an NR7 day${if (soFar) " so far" else ""}: its range of ${n(d.range)} points ${if (soFar) "is" else "was"} wider than ${date(low.day)}'s ${n(low.range)}, the narrowest of the six before it." }
        }
        val lines = if (q.kind == Kind.NARROW) listOf(narrowLine, insideLine) else listOf(insideLine, narrowLine)
        val tail = if (soFar) " The session is still on, so its high, low and range can still change." else ""
        return "Boss, $who. " + lines.joinToString(" ") + tail +
            " Ask what usually follows an ${if (q.kind == Kind.NARROW) "NR7" else "inside"} day for the record."
    }

    private fun partLine(k: Kind, xs: List<After>): String {
        val name = nameOf(k, true)
        if (xs.isEmpty()) return "No $name had a whole session after them on the phone, so there is nothing to say of what followed."
        if (xs.size < MIN_SETUPS) return "Only ${xs.size} of the $name had a whole session after them on the phone - too few to say what followed (I need $MIN_SETUPS)."
        val w = xs.count { it.wider }
        val up = xs.count { it.above && !it.below }; val dn = xs.count { it.below && !it.above }
        val both = xs.count { it.above && it.below }; val none = xs.size - up - dn - both
        val ca = xs.count { it.closedAbove }; val cb = xs.count { it.closedBelow }
        return "After the ${xs.size} $name, the next session's range was wider than the setup's on $w (${share(w, xs.size)}), a median ${x2(median(xs.map { it.ratio }))} times it; " +
            "it traded above the setup's high only on $up (${share(up, xs.size)}), below its low only on $dn (${share(dn, xs.size)}), beyond both on $both and stayed inside on $none; " +
            "it closed above the setup's high on $ca (${share(ca, xs.size)}) and below its low on $cb (${share(cb, xs.size)})."
    }

    /** What the last whole session was, and today against it; null when the phone has nothing to set beside. */
    private fun todayLine(m: Market, bars: List<Candle>, days: List<Day?>, today: LocalDate, now: LocalDateTime): String? {
        val i = days.size - 1
        val last = days.getOrNull(i) ?: return null
        val what = listOfNotNull(if (inside(days, i)) "an inside day" else null, if (narrow(days, i)) "the narrowest of its $NR (NR7)" else null)
        val was = if (what.isEmpty()) "neither an inside day nor the narrowest of its $NR"
            else what.joinToString(" and ")
        val head = "The last whole session, ${date(last.day)}, was $was (high ${n(last.high)}, low ${n(last.low)}, range ${n(last.range)} points)."
        val s = MarketStory.sessions(bars).lastOrNull { it.day == today }?.let { day(it) } ?: return head
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val beyond = when {
            s.high > last.high && s.low < last.low -> "beyond both its high and its low"
            s.high > last.high -> "above its high"
            s.low < last.low -> "below its low"
            else -> "inside its range"
        }
        val ratio = if (last.range > 0) " (${x2(s.range / last.range)} times)" else ""
        return head + " Today ${m.label} " + (if (live) "so far has ranged ${n(s.range)} points$ratio and traded $beyond - the session is still on."
            else "ranged ${n(s.range)} points$ratio and traded $beyond.")
    }
}
