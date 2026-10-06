package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The index's last-hour record (market intelligence, round 18): "how often does the last hour continue the day's
 * direction?", "does Nifty usually reverse in the last hour?", "on up days does BankNifty extend in the closing hour?",
 * "last hour record for Sensex", "aakhri ghante mein kitni baar palat-ta hai". From the whole sessions of 1-minute candles
 * on the phone: the day's direction is the price at 14:30 against the session's open; the last hour is 14:30 to the close.
 * How often the last hour went the same way (continued), the other way (reversed) or stayed flat; how often it undid the
 * whole day (closed on the other side of the open) and how often it set a new extreme of the day in the day's direction;
 * the median sizes; the bigger days (1% or more by 14:30) set beside the rest - and today so far beside it. A record of
 * past days on this phone, said with its counts; never a forecast or advice, nothing acts. How busy the last hour is
 * stays [DayClock]'s, the last hour by weekday [Weekdays]', the indices' move in the last hour [Breadth]'s. Pure.
 */
object LastHour {
    /** What was asked: the days asked of (+1 up by 14:30, -1 down by 14:30, null all). */
    data class Q(val dir: Int?)

    /**
     * One whole past session: [open], the price at 14:30 [at], the [close], the day's move to 14:30 [dayPct] (% of the open),
     * the last hour's [lastPct] (% of the price at 14:30), and whether the last hour set a new high (or low) of the day
     * beyond everything before 14:30 in the day's direction ([extended]).
     */
    data class Day(val day: LocalDate, val open: Double, val at: Double, val close: Double, val dayPct: Double, val lastPct: Double, val extended: Boolean) {
        /** +1 up by 14:30, -1 down, 0 no clear direction. */
        val dir: Int get() = if (abs(dayPct) < MIN_DAY_PCT) 0 else if (dayPct > 0) 1 else -1
        /** +1 the last hour went the day's way, -1 against it, 0 flat (or no direction). */
        val turn: Int get() = if (dir == 0 || abs(lastPct) < FLAT_PCT) 0 else if (lastPct * dir > 0) 1 else -1
        /** The close on the other side of the open from 14:30: the last hour undid the whole day. */
        val undid: Boolean get() = dir != 0 && (close - open) * dir < 0
    }

    /** Fewer sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 15
    /** Fewer days in a part (up days, bigger days...) than this: too few to say its record. */
    const val MIN_DAYS = 5
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    /** A day moved less than this (% of the open) by 14:30 has no clear direction. */
    const val MIN_DAY_PCT = 0.10
    /** A last hour moving less than this (% of the 14:30 price) is flat. */
    const val FLAT_PCT = 0.05
    /** A day moved this much or more (% of the open) by 14:30 is a bigger day. */
    const val BIG_DAY_PCT = 1.0
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val FROM: LocalTime = LocalTime.of(14, 30)
    val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val FIRST_BY: LocalTime = LocalTime.of(9, 16)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)
    /** The price at 14:30 must come from a bar no older than this. */
    private val AT_FROM: LocalTime = LocalTime.of(14, 20)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the last-hour record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so its day has no last hour of the kind, and India VIX is not traded."

    private fun norm(text: String) = Spaced.words(text)
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun sp2(x: Double) = (if (x >= 0) "+" else "-") + "%.2f%%".format(Locale.ENGLISH, abs(x))
    private fun share(k: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun plural(k: Int, w: String) = if (k == 1) w else w + "s"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** The last hour named. */
    private const val LAST = " (last hour|final hour|closing hour|power hour|last 60 minutes|last one hour|14 30 to 15 30|2 30 to 3 30|after 2 30|after 14 30|" +
        "aakhri ghanta|aakhri ghante|akhri ghanta|akhri ghante|aakhri ghante mein|last ghanta|last ghante) "
    /** Continue or reverse asked of. */
    private const val WAY = " (continue|continues|continued|continuing|continuation|extend|extends|extended|follow through|follows through|" +
        "reverse|reverses|reversed|reversal|reversals|turn|turns|turned|turn around|turns around|flip|flips|fade|fades|faded|undo|undoes|" +
        "give back|gives back|same direction|same way|other way|direction|trend|trends|against the day|with the day|" +
        "keep falling|keeps falling|keep rising|keeps rising|keep going|keeps going|keep climbing|keeps climbing|keep dropping|keeps dropping|" +
        "palat|palatta|palta|palti|ulta|ulat|wahi direction|usi direction) "
    /** A record asked of. */
    private const val HOW = " (how often|how many times|how frequently|how many days|how many sessions|what share|what percentage|what percent|what fraction|" +
        "usually|normally|typically|generally|tend to|tends to|on average|most times|most of the time|historically|history|record|rate|" +
        "stats|statistics|odds|chances|probability|percentage|kitni baar|kitne din|aksar|zyada tar|mostly|" +
        // "On up days does BankNifty extend in the closing hour?" - the days named make it a record (routing round 13).
        "on (up|down|green|red|rally|rising|falling|bullish|bearish) days) "
    /** "Last hour record", "closing hour behaviour" - the record named outright. */
    private const val NAMED = " (last hour|final hour|closing hour|power hour) (record|stats|statistics|behaviour|behavior|pattern|patterns|tendency|habit|habits) "
    // Forecasts, advice, Boss's own book, alerts, a single day, how busy (DayClock's), weekdays and expiry (Weekdays'), news.
    private const val NOT = " (will|would|going to|gonna|tomorrow|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|trade|trades|trading|" +
        "i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|happened|was|were|did|today|aaj|now|right now|abhi|this|yesterday|kal|" +
        "alert|alerts|notify|remind|reminder|warn|ping|watch|arm|arms|bot|bots|algo|algos|strategy|strategies|backtest|pine|news|" +
        "volatile|volatility|busy|busiest|quiet|quietest|active|calm|range|how much|leading|lead|leads|strongest|weakest|" +
        "monday|mondays|tuesday|tuesdays|wednesday|wednesdays|thursday|thursdays|friday|fridays|weekday|weekdays|expiry|expiries|" +
        "mean|means|meaning|define|explain|what is) "

    /** What was asked, or null. A record of past last hours only: never a forecast, advice, an alert or today's own move. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (rx(NOT).containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        if (!rx(LAST).containsMatchIn(t)) return null
        val named = rx(NAMED).containsMatchIn(t)
        if (!named && !(rx(WAY).containsMatchIn(t) && rx(HOW).containsMatchIn(t))) return null
        return Q(dir(t))
    }

    /** The days asked of: up days +1, down days -1, else null. */
    private fun dir(t: String): Int? {
        val up = rx(" (up day|up days|green day|green days|rally day|rally days|rising day|rising days|bullish day|bullish days|day is up|days are up|is up|are up|teji|hare din) ").containsMatchIn(t)
        val down = rx(" (down day|down days|red day|red days|falling day|falling days|bearish day|bearish days|day is down|days are down|is down|are down|mandi|lal din) ").containsMatchIn(t)
        return if (up && !down) 1 else if (down && !up) -1 else null
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        val m = markets.firstOrNull() ?: return Market.NIFTY
        return if (m == Market.GOLD || m == Market.VIX) markets.firstOrNull { it != Market.GOLD && it != Market.VIX } else m
    }

    // ---- the reading ----------------------------------------------------------------------------------------------

    private fun whole(s: MarketStory.Session) = s.bars.isNotEmpty() && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) &&
        !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    /** [s] read to its last bar: the open, the price at 14:30 (the close of the last bar starting before it), the close. */
    fun read(s: MarketStory.Session): Day? {
        val bars = s.bars
        if (bars.isEmpty()) return null
        val open = bars.first().o
        val before = bars.filter { it.t.toLocalTime().isBefore(FROM) }
        val atBar = before.lastOrNull()?.takeIf { !it.t.toLocalTime().isBefore(AT_FROM) } ?: return null
        val at = atBar.c
        if (open <= 0 || at <= 0) return null
        val after = bars.filter { !it.t.toLocalTime().isBefore(FROM) }
        val dayPct = (at - open) / open * 100
        val lastPct = (s.close - at) / at * 100
        val extended = after.isNotEmpty() && when {
            abs(dayPct) < MIN_DAY_PCT -> false
            dayPct > 0 -> after.maxOf { it.h } > before.maxOf { it.h }
            else -> after.minOf { it.l } < before.minOf { it.l }
        }
        return Day(s.day, open, at, s.close, dayPct, lastPct, extended)
    }

    /** The whole sessions before [today] in [bars], newest [MAX_SESSIONS], oldest first. */
    fun past(bars: List<Candle>, today: LocalDate): List<Day> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) && whole(it) }.mapNotNull { read(it) }.takeLast(MAX_SESSIONS)

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** [q] answered for [m] from [bars] (1-minute candles over several days) at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole ${m.label} ${plural(days.size, "session")} on the phone, Boss - too few to say how the last hour went against the day (I need $MIN_SESSIONS)."
        val lines = ArrayList<String>()
        val moved = days.filter { it.dir != 0 }
        val ups = moved.count { it.dir == 1 }; val downs = moved.count { it.dir == -1 }
        lines += "Over the last ${days.size} whole ${m.label} sessions on this phone (${date(days.first().day)} to ${date(days.last().day)}), with the day's direction " +
            "as the price at ${hm(FROM)} against the open and the last hour as ${hm(FROM)} to the close: $ups were up by ${hm(FROM)}, $downs down, and " +
            "${days.size - moved.size} within ${p2(MIN_DAY_PCT)} of the open (no clear direction, left out below)."
        val asked = when (q.dir) { 1 -> moved.filter { it.dir == 1 }; -1 -> moved.filter { it.dir == -1 }; else -> moved }
        val name = when (q.dir) { 1 -> "up days"; -1 -> "down days"; else -> "days with a direction" }
        lines += partLine(name, asked)
        if (asked.size >= MIN_DAYS) {
            val big = asked.filter { abs(it.dayPct) >= BIG_DAY_PCT }; val small = asked.filter { abs(it.dayPct) < BIG_DAY_PCT }
            lines += if (big.size < MIN_DAYS) "Only ${big.size} of them had moved ${p2(BIG_DAY_PCT)} or more by ${hm(FROM)} - too few to set the bigger days apart (I need $MIN_DAYS)."
            else {
                val bc = big.count { it.turn == 1 }; val br = big.count { it.turn == -1 }
                val sc = small.count { it.turn == 1 }; val sr = small.count { it.turn == -1 }
                "On the ${big.size} that had moved ${p2(BIG_DAY_PCT)} or more by ${hm(FROM)}, the last hour continued on $bc (${share(bc, big.size)}) and reversed on $br (${share(br, big.size)})" +
                    (if (small.size >= MIN_DAYS) "; on the ${small.size} smaller ones it continued on $sc (${share(sc, small.size)}) and reversed on $sr (${share(sr, small.size)})." else ".")
            }
        }
        lines += "Across all ${days.size}, the last hour moved a median ${p2(median(days.map { abs(it.lastPct) }))} either way."
        todayLine(m, bars, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    private fun partLine(name: String, ds: List<Day>): String {
        if (ds.size < MIN_DAYS) return "Only ${ds.size} ${if (ds.size == 1) name.replace("days", "day") else name} - too few to say how the last hour went (I need $MIN_DAYS)."
        val cont = ds.count { it.turn == 1 }; val rev = ds.count { it.turn == -1 }; val flat = ds.size - cont - rev
        val undid = ds.count { it.undid }; val ext = ds.count { it.extended }
        var s = "On the ${ds.size} $name, the last hour continued the day's way on $cont (${share(cont, ds.size)}), went against it on $rev (${share(rev, ds.size)}) " +
            "and stayed within ${p2(FLAT_PCT)} on $flat; it undid the whole day (closing on the other side of the open) on $undid (${share(undid, ds.size)}), " +
            "and set a new ${if (ds.all { it.dir == -1 }) "low" else if (ds.all { it.dir == 1 }) "high" else "extreme"} of the day the day's way on $ext (${share(ext, ds.size)})."
        val c = ds.filter { it.turn == 1 }; val r = ds.filter { it.turn == -1 }
        val bits = ArrayList<String>()
        if (c.isNotEmpty()) bits += "a median ${p2(median(c.map { abs(it.lastPct) }))} when it continued"
        if (r.isNotEmpty()) bits += "${if (c.isEmpty()) "a median " else ""}${p2(median(r.map { abs(it.lastPct) }))} when it reversed"
        if (bits.isNotEmpty()) s += " It moved " + bits.joinToString(" and ") + "."
        return s
    }

    /** Today so far against the record, or null with no candles today. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val s = MarketStory.sessions(bars).lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() } ?: return null
        val open = s.bars.first().o.takeIf { it > 0 } ?: return null
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val d = read(s)
        if (d == null || s.bars.none { !it.t.toLocalTime().isBefore(FROM) }) {
            val pct = (s.close - open) / open * 100
            return "Today ${m.label} is ${sp2(pct)} from the open at ${n(s.close)}" + (if (live) "; the last hour has not begun." else "; the phone has no last hour for it.")
        }
        val way = when (d.turn) { 1 -> "with the day"; -1 -> "against the day"; else -> if (d.dir == 0) "with no clear direction to go with or against" else "flat" }
        return "Today ${m.label} was ${sp2(d.dayPct)} from the open at ${hm(FROM)} (${n(d.at)}), and the last hour " +
            (if (live) "so far has moved ${sp2(d.lastPct)} to ${n(d.close)}, $way - the session is still on." else "moved ${sp2(d.lastPct)} to close at ${n(d.close)}, $way.")
    }
}
