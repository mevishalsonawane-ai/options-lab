package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The index's first-move record (market intelligence, round 20): "how often does the first 30 minutes' direction match
 * the day's close?", "does the opening move usually decide the day?", "when Nifty is up in the first half hour how often
 * does it close up?", "first move record for BankNifty", "pehle aadhe ghante ki direction se din ka close kitni baar
 * milta hai". From the whole sessions of 1-minute candles on the phone: the first move is the price at the end of the
 * first 30 minutes (15 or the first hour when asked) against the session's open; the day is the close against the open.
 * How often the close went the first move's way, the other way or stayed flat; how often the rest of the day carried on
 * beyond the price at the end of the first move; the match rate set against what chance alone would give from how often
 * days closed up and down (a market that mostly closes up matches an up first move often anyway); the bigger first moves
 * set beside the smaller; and today's first move beside it. A record of past days on this phone, said with its counts;
 * never a forecast or advice, nothing acts. The opening range's breaks stay [RangeBreaks]', how busy the first half hour
 * is [DayClock]'s, the gap [GapRecord]'s, the last hour [LastHour]'s. Pure.
 */
object FirstMove {
    /** What was asked: the first move's length in [minutes], and the first moves asked of (+1 up, -1 down, null all). */
    data class Q(val minutes: Int = DEFAULT_MINUTES, val dir: Int? = null)

    /**
     * One whole past session: [open], the price at the end of the first move [at], the [close]; the first move [firstPct]
     * (% of the open), the day [dayPct] (close against the open, % of the open) and the rest of the day [restPct] (close
     * against [at], % of [at]).
     */
    data class Day(val day: LocalDate, val open: Double, val at: Double, val close: Double, val firstPct: Double, val dayPct: Double, val restPct: Double) {
        /** +1 the first move was up, -1 down, 0 within [FLAT_PCT] (no first move). */
        val first: Int get() = if (abs(firstPct) < FLAT_PCT) 0 else if (firstPct > 0) 1 else -1
        /** +1 the day closed up from the open, -1 down, 0 within [FLAT_PCT]. */
        val close1: Int get() = if (abs(dayPct) < FLAT_PCT) 0 else if (dayPct > 0) 1 else -1
        /** +1 the close went the first move's way, -1 the other way, 0 flat (or no first move). */
        val match: Int get() = if (first == 0 || close1 == 0) 0 else if (first == close1) 1 else -1
        /** The rest of the day carried on beyond the price at the end of the first move, the first move's way. */
        val carried: Boolean get() = first != 0 && restPct * first >= FLAT_PCT
    }

    /** Fewer sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 15
    /** Fewer days in a part (up first moves, bigger ones...) than this: too few to say its record. */
    const val MIN_DAYS = 5
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    /** A first move, a day or a rest of the day under this (% of the price) is flat. */
    const val FLAT_PCT = 0.05
    /** A first move this size or more (% of the open) is a bigger one. */
    const val BIG_PCT = 0.40
    const val DEFAULT_MINUTES = 30
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val FIRST_BY: LocalTime = LocalTime.of(9, 16)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the first-move record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so its day has no opening move of the kind, and India VIX is not traded."

    private fun norm(text: String) = " " + spacedWords(text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")) + " "
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun sp2(x: Double) = (if (x >= 0) "+" else "-") + "%.2f%%".format(Locale.ENGLISH, abs(x))
    private fun share(k: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun plural(k: Int, w: String) = if (k == 1) w else w + "s"
    private fun span(minutes: Int) = if (minutes == 60) "the first hour" else "the first $minutes minutes"
    /** The end of the first move. */
    fun end(minutes: Int): LocalTime = OPEN.plusMinutes(minutes.toLong())

    // ---- the questions --------------------------------------------------------------------------------------------

    /** The opening move named. */
    private const val FIRST = " (first (15|30|fifteen|thirty|60) (minute|minutes|min|mins)|first half hour|first half an hour|first hour|first move|first moves|" +
        "opening move|opening moves|opening direction|opening drive|opening drives|opening half hour|opening hour|opening (15|30) (minute|minutes|min|mins)|" +
        "initial move|initial direction|morning move|morning direction|early move|early direction|direction at the open|" +
        "pehle aadhe ghante|pehla aadha ghanta|pehle adhe ghante|pehle ghante|pehla ghanta|pehle 30 minute|pehle 15 minute|shuru ki direction|shuruaat ki direction) "
    /** The day's close or direction asked of. */
    private const val DAY = " (close|closes|closed|closing|day s close|day s direction|day direction|direction of the day|the day|whole day|rest of the day|end of the day|" +
        "decide|decides|sets the tone|set the tone|sets the direction|match|matches|matched|same direction|same way|same side|hold|holds|held|stick|sticks|" +
        "continue|continues|reverse|reverses|reversal|follow through|din ka close|din ki direction|din ka|band|band hota|milta|milti) "
    /** A record asked of. */
    private const val HOW = " (how often|how many times|how frequently|how many days|how many sessions|what share|what percentage|what percent|what fraction|" +
        "usually|normally|typically|generally|tend to|tends to|on average|most times|most of the time|historically|history|record|rate|" +
        "stats|statistics|odds|chances|probability|percentage|reliable|kitni baar|kitne din|aksar|zyada tar|mostly) "
    /** "First move record", "opening move stats" - the record named outright. */
    private const val NAMED = " (first move|opening move|opening drive|first half hour direction|opening direction) (record|stats|statistics|behaviour|behavior|tendency|habit|habits) "
    // Forecasts, advice, Boss's own book, alerts, a single day, the opening range's breaks (RangeBreaks'), how busy or
    // volatile the open is and when the high or low comes (DayClock's), gaps, the last hour, weekdays and expiry, news.
    private const val NOT = " (will|would|going to|gonna|tomorrow|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|trade|trades|trading|" +
        "i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|happened|was|were|did|today|aaj|now|right now|abhi|this|yesterday|kal|" +
        "alert|alerts|notify|remind|reminder|warn|ping|watch|arm|arms|bot|bots|algo|algos|strategy|strategies|backtest|pine|news|" +
        "range|ranges|breakout|breakouts|breakdown|break|breaks|orb|high|low|highs|lows|gap|gaps|gapped|last hour|closing hour|final hour|" +
        "volatile|volatility|busy|busiest|quiet|quietest|active|calm|how much|leading|lead|leads|strongest|weakest|" +
        "monday|mondays|tuesday|tuesdays|wednesday|wednesdays|thursday|thursdays|friday|fridays|weekday|weekdays|expiry|expiries|" +
        "mean|means|meaning|define|explain|what is) "

    /** What was asked, or null. A record of past first moves only: never a forecast, advice, an alert or today's own move. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (rx(NOT).containsMatchIn(t)) return null
        // A big 5-minute candle in the first hour and what followed it is the big-candle record's (round 19).
        if (BigCandles.asked(text) != null) return null
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        if (!rx(FIRST).containsMatchIn(t)) return null
        val named = rx(NAMED).containsMatchIn(t)
        // Round 14: a record asked without a record word - "does the first half hour decide the day", "if Nifty is down in the
        // first 30 minutes does it close down", "first half hour ki direction se din kaisa jaata hai".
        val general = rx("^ (jarvis )?(does|do) (the )?[a-z ]*(decide|decides|set the tone|sets the tone|set the direction|sets the direction) ").containsMatchIn(t) ||
            rx("^ (jarvis )?(if|when|jab) .* (does|do) (it|nifty|banknifty|finnifty|sensex|the index|the market|the day) (close|end|finish) ").containsMatchIn(t) ||
            rx(" se din (kaisa|kaise|kis taraf|kidhar) (jaata|jata|chalta|band hota) ").containsMatchIn(t)
        if (!named && !(rx(DAY).containsMatchIn(t) && rx(HOW).containsMatchIn(t)) && !general) return null
        return Q(minutes(t), dir(t))
    }

    private fun minutes(t: String): Int = when {
        rx(" first hour | opening hour | first 60 | pehle ghante | pehla ghanta ").containsMatchIn(t) -> 60
        rx(" first (15|fifteen) | opening 15 | pehle 15 ").containsMatchIn(t) -> 15
        else -> DEFAULT_MINUTES
    }

    /** The first moves asked of: "when it is up in the first half hour" +1, "down" -1, both or neither null. */
    private fun dir(t: String): Int? {
        // "Close up" / "closes down" is the day's side, not the first move's: only the first side named counts.
        val s = t.replace(rx(" (close|closes|closed|closing|end|ends|ended|finish|finishes|finished) (up|down|higher|lower|green|red) "), " ")
        val up = rx(" (up|higher|green|rises|rise|rising|rallies|rally|bullish|positive|upar|tez) ").containsMatchIn(s)
        val down = rx(" (down|lower|red|falls|fall|falling|drops|drop|bearish|negative|neeche|gira|girta) ").containsMatchIn(s)
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

    /**
     * [s] read to its last bar with a first move of [minutes]: the open, the price at the end of the first move (the close
     * of the last bar starting before it, from its last 5 minutes), the close. Null without such a bar.
     */
    fun read(s: MarketStory.Session, minutes: Int): Day? {
        val bars = s.bars
        if (bars.isEmpty()) return null
        val open = bars.first().o
        val stop = end(minutes)
        val atBar = bars.lastOrNull { it.t.toLocalTime().isBefore(stop) }?.takeIf { !it.t.toLocalTime().isBefore(stop.minusMinutes(5)) } ?: return null
        val at = atBar.c
        if (open <= 0 || at <= 0) return null
        return Day(s.day, open, at, s.close, (at - open) / open * 100, (s.close - open) / open * 100, (s.close - at) / at * 100)
    }

    /** The whole sessions before [today] in [bars], newest [MAX_SESSIONS], oldest first. */
    fun past(bars: List<Candle>, today: LocalDate, minutes: Int): List<Day> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) && whole(it) }.mapNotNull { read(it, minutes) }.takeLast(MAX_SESSIONS)

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** [q] answered for [m] from [bars] (1-minute candles over several days) at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today, q.minutes)
        val first = span(q.minutes); val stop = hm(end(q.minutes))
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole ${m.label} ${plural(days.size, "session")} on the phone, Boss - too few to say how often $first matched the day's close (I need $MIN_SESSIONS)."
        val lines = ArrayList<String>()
        val moved = days.filter { it.first != 0 }
        val ups = moved.count { it.first == 1 }; val downs = moved.count { it.first == -1 }
        lines += "Over the last ${days.size} whole ${m.label} sessions on this phone (${date(days.first().day)} to ${date(days.last().day)}), with the first move as " +
            "the price at $stop against the open and the day as the close against the open: $first went up on $ups, down on $downs, and stayed within " +
            "${p2(FLAT_PCT)} of the open on ${days.size - moved.size} (no first move, left out below)."
        val asked = when (q.dir) { 1 -> moved.filter { it.first == 1 }; -1 -> moved.filter { it.first == -1 }; else -> moved }
        val name = when (q.dir) { 1 -> "days $first went up"; -1 -> "days $first went down"; else -> "days with a first move" }
        lines += partLine(name, asked)
        if (q.dir == null && asked.size >= MIN_DAYS) {
            val bits = ArrayList<String>()
            for ((d, w) in listOf(1 to "up", -1 to "down")) {
                val part = moved.filter { it.first == d }
                if (part.size >= MIN_DAYS) { val k = part.count { it.match == 1 }; bits += "after an $w first move the close was $w on $k of ${part.size} (${share(k, part.size)})" }
                else bits += "only ${part.size} $w first ${plural(part.size, "move")}, too few to set apart"
            }
            lines += bits.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
        }
        if (asked.size >= MIN_DAYS) {
            // What chance alone would give: a first move as often up as these were, against closes as often up as all days were.
            val upClose = days.count { it.close1 == 1 }.toDouble() / days.size; val downClose = days.count { it.close1 == -1 }.toDouble() / days.size
            val chance = asked.sumOf { if (it.first == 1) upClose else downClose } / asked.size * 100
            lines += "For comparison, all ${days.size} sessions closed up on ${days.count { it.close1 == 1 }} and down on ${days.count { it.close1 == -1 }}, so if the first move " +
                "had nothing to do with the close about ${Math.round(chance)}% of these would have matched anyway."
            val big = asked.filter { abs(it.firstPct) >= BIG_PCT }; val small = asked.filter { abs(it.firstPct) < BIG_PCT }
            lines += if (big.size < MIN_DAYS) "Only ${big.size} of them moved ${p2(BIG_PCT)} or more in $first - too few to set the bigger first moves apart (I need $MIN_DAYS)."
            else {
                val bm = big.count { it.match == 1 }; val sm = small.count { it.match == 1 }
                "When $first moved ${p2(BIG_PCT)} or more (${big.size} days) the close matched on $bm (${share(bm, big.size)})" +
                    (if (small.size >= MIN_DAYS) "; when it moved less (${small.size} days) on $sm (${share(sm, small.size)})." else ".")
            }
        }
        lines += "Across all ${days.size}, $first moved a median ${p2(median(days.map { abs(it.firstPct) }))} either way, and the rest of the day " +
            "a median ${p2(median(days.map { abs(it.restPct) }))} from $stop to the close."
        todayLine(q, m, bars, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    private fun partLine(name: String, ds: List<Day>): String {
        if (ds.size < MIN_DAYS) return "Only ${ds.size} ${if (ds.size == 1) name.replaceFirst("days", "day") else name} - too few to say how the close went against it (I need $MIN_DAYS)."
        val same = ds.count { it.match == 1 }; val other = ds.count { it.match == -1 }; val flat = ds.size - same - other
        val carried = ds.count { it.carried }
        return "On the ${ds.size} $name, the day closed the first move's way on $same (${share(same, ds.size)}), the other way on $other (${share(other, ds.size)}) " +
            "and within ${p2(FLAT_PCT)} of the open on $flat; the rest of the day carried on beyond the first move's end, its way, on $carried (${share(carried, ds.size)})."
    }

    /** Today's first move against the record, or null with no candles today. */
    private fun todayLine(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val s = MarketStory.sessions(bars).lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() } ?: return null
        val open = s.bars.first().o.takeIf { it > 0 } ?: return null
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val first = span(q.minutes); val stop = end(q.minutes)
        val sofar = (s.close - open) / open * 100
        if (s.bars.none { !it.t.toLocalTime().isBefore(stop) }) {
            return "Today ${m.label} is ${sp2(sofar)} from the open at ${n(s.close)}" +
                (if (live) "; $first ${if (now.toLocalTime().isBefore(stop)) "are" else "were"} not over in the candles yet." else "; the phone has nothing after $first for it.")
        }
        val d = read(s, q.minutes) ?: return null
        val way = when (d.first) { 1 -> "up"; -1 -> "down"; else -> "flat" }
        val vs = when {
            d.first == 0 -> "no first move to go with or against"
            d.close1 == 0 -> "near the open"
            d.close1 == d.first -> "the first move's way"
            else -> "the other way"
        }
        return "Today $first went $way (${sp2(d.firstPct)} to ${n(d.at)} at ${hm(stop)}), and ${m.label} " +
            (if (live) "is now ${sp2(d.dayPct)} from the open at ${n(d.close)}, $vs - the session is still on." else "closed ${sp2(d.dayPct)} from the open at ${n(d.close)}, $vs.")
    }
}
