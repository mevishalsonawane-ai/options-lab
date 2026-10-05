package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The index's prior-day high and low record (market intelligence, round 17): "when Nifty takes out yesterday's high in
 * the first hour, how often does it close above it?", "how often does Nifty break the previous day's high?", "does
 * BankNifty usually hold below yesterday's low after breaking it?", "PDH PDL record", "kal ka high todne ke baad kitni baar
 * upar band hota hai". From the whole sessions of 1-minute candles on the phone, each set against the whole session before
 * it: a take-out is the first 1-minute bar trading beyond the prior day's high (or low). For each side: how often it was
 * taken out, how often the close held beyond it, how often the close ended beyond the other edge (a full reversal), how many
 * opened beyond it, the median time of the take-out and how far it ran beyond; the take-outs in the first hour set beside
 * the later ones - and today against yesterday's high and low. A record of past days on this phone, said with its counts;
 * never a forecast or advice, nothing acts. Where yesterday's high stands and how far the price is from it stay the level
 * readers' ([Reason], [Distance]); today against yesterday measure by measure [DayCompare]'s. Pure.
 */
object PriorDay {
    /** What was asked: the side (+1 the prior high, -1 the prior low, null both) and whether the first hour was named. */
    data class Q(val dir: Int?, val firstHour: Boolean)

    /** One side of one session against the prior day's level [level]: when it was first traded beyond ([at], null never). */
    data class Take(val at: LocalTime?, val gapped: Boolean, val held: Boolean, val reversed: Boolean, val runPct: Double)

    /** One whole session against the whole session before it. */
    data class Day(val day: LocalDate, val prevHigh: Double, val prevLow: Double, val close: Double, val up: Take, val down: Take) {
        fun side(d: Int) = if (d == 1) up else down
    }

    /** Fewer session pairs than this: too few to say anything. */
    const val MIN_SESSIONS = 15
    /** Fewer take-outs of a side (or of a part of them) than this: too few to say its record. */
    const val MIN_TAKES = 5
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val CLOSE: LocalTime = LocalTime.of(15, 30)
    /** The first hour ends here: a take-out before it is a first-hour one. */
    val HOUR_END: LocalTime = LocalTime.of(10, 15)
    private val FIRST_BY: LocalTime = LocalTime.of(9, 16)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the prior-day high and low record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so its days have no clean high and low, and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "
    private fun n(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun plural(k: Int, w: String) = if (k == 1) w else w + "s"

    // ---- the questions --------------------------------------------------------------------------------------------

    private const val PREV = "(yesterday s|yesterdays|yesterday|previous day s|previous days|previous day|prior day s|prior days|prior day|" +
        "previous session s|previous sessions|previous session|prior session s|prior session|last session s|last sessions|last session|" +
        "previous daily|prior daily|day before s)"
    /** The prior day's high or low named: "yesterday's high", "previous day low", "PDH", "kal ka high". */
    private const val LEVEL = " ($PREV (highs and lows|high and lows|high and low|high or low|high low|highs|lows|high|low)|" +
        "pdh and pdl|pdh or pdl|pdh pdl|pdhl|pdh|pdl|(kal|pichle din|pichhle din|pichle session) (ka|ke|ki) (high|low)) "
    /** A record asked of. */
    private const val HOW = " (how often|how many times|how frequently|how many days|how many sessions|how many of|what share|what percentage|what percent|what fraction|" +
        "usually|normally|typically|generally|tend to|tends to|on average|most times|most of the time|historically|history|record|rate|success rate|hold rate|" +
        "fail rate|failure rate|stats|statistics|odds|chances|probability|reliable|reliability|hit rate|percentage|" +
        "kitni baar|kitne din|aksar|zyada tar|mostly) "
    // Forecasts, advice, Boss's own book, alerts, a single day (today's own place stays the level readers'), a gap, a meaning.
    private const val NOT = " (will|would|going to|gonna|tomorrow|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|trade|trades|trading|" +
        "i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|happened|last time|today|aaj|now|right now|abhi|this|" +
        "alert|alerts|notify|remind|reminder|warn|ping|watch|arm|arms|bot|bots|algo|algos|strategy|strategies|backtest|pine|news|" +
        "gap|gaps|gapped|mean|means|meaning|define|explain|what is pdh|what is a pdh|what s pdh|what is pdl|what s pdl|where is|where s|how far) "

    /** What was asked, or null. A record of past take-outs only: never a forecast, advice, an alert or today's own place. */
    fun asked(text: String): Q? {
        val t = norm(text)
        if (rx(NOT).containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD }) return null
        if (!rx(LEVEL).containsMatchIn(t)) return null
        val hinglish = rx(" (todne|toda|tootne|toot|tootta|todta|paar|cross) ").containsMatchIn(t) &&
            rx(" (tikta|tikti|tike|band|close|kitni baar|aksar) ").containsMatchIn(t)
        if (!rx(HOW).containsMatchIn(t) && !hinglish) return null
        return Q(dir(t), rx(" (first hour|first hour s|first 60 minutes|opening hour|by 10|before 10|by 10 15|before 10 15|early|pehle ghante) ").containsMatchIn(t))
    }

    /** The side asked, from the levels named: a high +1, a low -1, both (or "high or low", "PDH PDL") null. */
    private fun dir(t: String): Int? {
        val all = " " + rx(LEVEL).findAll(t).joinToString(" ") { it.value.trim() } + " "
        val hi = rx(" (high|highs|pdh|pdhl) ").containsMatchIn(all)
        val lo = rx(" (low|lows|pdl|pdhl) ").containsMatchIn(all)
        return if (hi && !lo) 1 else if (lo && !hi) -1 else null
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        val m = markets.firstOrNull() ?: return Market.NIFTY
        return if (m == Market.GOLD || m == Market.VIX) markets.firstOrNull { it != Market.GOLD && it != Market.VIX } else m
    }

    // ---- the reading ----------------------------------------------------------------------------------------------

    private fun whole(s: MarketStory.Session) = s.bars.isNotEmpty() && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) &&
        !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    /** One side of [s] against the prior day's [hi] and [lo]: +1 the high, -1 the low. */
    private fun take(s: MarketStory.Session, d: Int, hi: Double, lo: Double): Take {
        val level = if (d == 1) hi else lo
        val first = s.bars.firstOrNull { if (d == 1) it.h > level else it.l < level }
        val close = s.close
        if (first == null) return Take(null, false, false, false, 0.0)
        val gapped = first === s.bars.first() && (if (d == 1) first.o > level else first.o < level)
        val held = if (d == 1) close > level else close < level
        val reversed = if (d == 1) close < lo else close > hi
        val run = if (d == 1) (s.bars.maxOf { it.h } - level) / level * 100 else (level - s.bars.minOf { it.l }) / level * 100
        return Take(first.t.toLocalTime(), gapped, held, reversed, run)
    }

    /** [s] set against the session before it, [prev]. */
    fun read(s: MarketStory.Session, prev: MarketStory.Session): Day? {
        if (s.bars.isEmpty() || prev.bars.isEmpty()) return null
        val hi = prev.bars.maxOf { it.h }; val lo = prev.bars.minOf { it.l }
        if (hi <= 0 || lo <= 0) return null
        return Day(s.day, hi, lo, s.close, take(s, 1, hi, lo), take(s, -1, hi, lo))
    }

    /** The whole sessions before [today] in [bars], each against the whole session just before it, newest [MAX_SESSIONS], oldest first. */
    fun past(bars: List<Candle>, today: LocalDate): List<Day> {
        val ss = MarketStory.sessions(bars).filter { it.day.isBefore(today) }
        return ss.zipWithNext().filter { (a, b) -> whole(a) && whole(b) }.mapNotNull { (a, b) -> read(b, a) }.takeLast(MAX_SESSIONS)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun medianTime(ts: List<LocalTime>): LocalTime = LocalTime.ofSecondOfDay(median(ts.map { it.toSecondOfDay().toDouble() }).toLong()).withSecond(0)

    /** [q] answered for [m] from [bars] (1-minute candles over several days) at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole ${m.label} ${plural(days.size, "session")} with a whole one before ${if (days.size == 1) "it" else "them"} on the phone, Boss - " +
                "too few to say how the prior day's high and low were taken out (I need $MIN_SESSIONS)."
        val lines = ArrayList<String>()
        val ups = days.count { it.up.at != null }; val downs = days.count { it.down.at != null }
        val both = days.count { it.up.at != null && it.down.at != null }; val inside = days.count { it.up.at == null && it.down.at == null }
        lines += "Over the last ${days.size} whole ${m.label} sessions on this phone (${date(days.first().day)} to ${date(days.last().day)}), each against the session before it, " +
            "with a take-out as the first 1-minute bar trading beyond the prior day's level: it took out the prior high on $ups (${share(ups, days.size)}) " +
            "and the prior low on $downs (${share(downs, days.size)}); both on $both, neither on $inside."
        val sides = when (q.dir) { 1 -> listOf(1); -1 -> listOf(-1); else -> listOf(1, -1) }
        for (d in sides) lines += sideLine(d, days, q.firstHour)
        todayLine(m, bars, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    private fun sideLine(d: Int, days: List<Day>, firstHour: Boolean): String {
        val name = if (d == 1) "the prior high" else "the prior low"
        val beyond = if (d == 1) "above it" else "below it"
        val other = if (d == 1) "below the prior low" else "above the prior high"
        val takes = days.map { it.side(d) }.filter { it.at != null }
        if (takes.size < MIN_TAKES) return "Only ${takes.size} ${plural(takes.size, "session")} took out $name - too few to say how ${if (takes.size == 1) "it" else "they"} closed (I need $MIN_TAKES)."
        val held = takes.count { it.held }; val rev = takes.count { it.reversed }; val gap = takes.count { it.gapped }
        val closed = days.count { if (d == 1) it.close > it.prevHigh else it.close < it.prevLow }
        var s = "Of the ${takes.size} that took out $name, the close held $beyond on $held (${share(held, takes.size)}) and ended $other on $rev (${share(rev, takes.size)}); " +
            "$gap opened $beyond. The median take-out came at ${hm(medianTime(takes.mapNotNull { it.at }))}, and the median run beyond it was ${p2(median(takes.map { it.runPct }))}. " +
            "Across all ${days.size} sessions, the close was $beyond on $closed (${share(closed, days.size)})."
        if (firstHour) {
            val early = takes.filter { it.at!!.isBefore(HOUR_END) }; val late = takes.filter { !it.at!!.isBefore(HOUR_END) }
            s += " " + if (early.size < MIN_TAKES) "Only ${early.size} of those came in the first hour (${hm(OPEN)} to ${hm(HOUR_END)}) - too few to say how they closed (I need $MIN_TAKES)."
            else {
                val eh = early.count { it.held }
                "Taken out in the first hour (${hm(OPEN)} to ${hm(HOUR_END)}) on ${early.size}, the close held $beyond on $eh (${share(eh, early.size)})" +
                    (if (late.size >= MIN_TAKES) { val lh = late.count { it.held }; "; taken out later on ${late.size}, it held on $lh (${share(lh, late.size)})." }
                    else if (late.isEmpty()) "; none came later." else "; only ${late.size} came later.")
            }
        }
        return s
    }

    /** Today against yesterday's high and low so far (or the whole day after the close), or null with no candles today. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val ss = MarketStory.sessions(bars)
        val s = ss.lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() } ?: return null
        val prev = ss.lastOrNull { it.day.isBefore(today) && it.bars.isNotEmpty() } ?: return null
        val d = read(s, prev) ?: return null
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val px = s.close
        val parts = ArrayList<String>()
        parts += if (d.up.at != null) "took out the prior high at ${hm(d.up.at)}" else "has not taken out the prior high"
        parts += if (d.down.at != null) "took out the prior low at ${hm(d.down.at)}" else "not the prior low"
        val where = when { px > d.prevHigh -> "above the prior high"; px < d.prevLow -> "below the prior low"; else -> "between the two" }
        return "Today ${m.label}, against ${date(prev.day)}'s high ${n(d.prevHigh)} and low ${n(d.prevLow)}, ${parts[0]} and ${parts[1]}" +
            ", and ${if (live) "is $where now at ${n(px)} - the session is still on" else "closed $where at ${n(px)}"}."
    }
}
