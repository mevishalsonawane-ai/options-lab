package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The reach-from-the-open record (market intelligence, round 36): "how far does Nifty usually move from its open?", "how
 * often does Nifty go 1% from the open?", "how often does BankNifty trade 0.5% on both sides of the open?", "how often
 * does Nifty close within 0.3% of its open?", "open reach record", "open se kitna door jata hai nifty". The question an
 * option seller at the open asks: from the whole sessions of 1-minute candles on the phone (the newest [MAX_SESSIONS]
 * before today; each session read on its own, never set against another), how far each day got above and below its own
 * open (its high and low against the open), how often it got the size asked ([DEFAULT_PCT]% when none is, or points) or
 * more away on one side, on the other, on both sides or neither, the median reach on each side and on the farther side
 * with its middle half, how often it ended within that size of its open - all days, and the days that first went that far
 * - and the farthest day. Beside today: its reach so far (or how it ended, "ended" only for a whole session). A record of
 * past days on this phone, said with its counts and plainly when there are too few; never a forecast or advice; nothing
 * acts. The open as the day's high or low stays [OpenHighLow]'s, the opening range's breaks [RangeBreaks]', the gap
 * [GapRecord]'s, the first move [FirstMove]'s, a fall from the previous close [Comebacks]', the overnight part
 * [Overnight]'s, today's own move from the open the day's reads'. Pure.
 */
object OpenReach {
    /**
     * What was asked: the size in % ([pct]) or in points ([pts], then [pct] unused); [asked] the % said when it was outside
     * [MIN_PCT] to [MAX_PCT] (then [pct] is [DEFAULT_PCT], and the answer says so).
     */
    data class Q(val pct: Double = DEFAULT_PCT, val pts: Double? = null, val asked: Double? = null)

    /** One whole past session: its open, high, low and close. */
    data class Day(val day: LocalDate, val open: Double, val high: Double, val low: Double, val close: Double) {
        /** How far above the open it got, % of the open. */
        val upPct: Double get() = (high - open) / open * 100
        /** How far below the open it got, % of the open (a positive figure). */
        val downPct: Double get() = (open - low) / open * 100
        /** The farther of the two. */
        val farPct: Double get() = maxOf(upPct, downPct)
        /** Close against the open, %. */
        val closePct: Double get() = (close - open) / open * 100
    }

    /** The record for one size: [n] days; above only, below only, both sides, neither; ended within the size (all, and of those that got that far). */
    data class Record(val n: Int, val aboveOnly: Int, val belowOnly: Int, val both: Int, val neither: Int,
                      val endedNear: Int, val reachedEndedNear: Int, val medianUp: Double, val medianDown: Double,
                      val medianFar: Double, val farLow: Double, val farHigh: Double) {
        val reached: Int get() = aboveOnly + belowOnly + both
    }

    /** The sizes counted, % of the open. */
    const val MIN_PCT = 0.1
    const val MAX_PCT = 5.0
    /** The size when none is asked, % of the open. */
    const val DEFAULT_PCT = 0.5
    /** Fewer whole sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 20
    /** Fewer whole sessions than this: said as a small record. */
    const val FEW_SESSIONS = 60
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    private val CLOSE = LocalTime.of(15, 30)
    /** The indices the record is kept for (gold trades round the clock, India VIX is not traded). */
    internal val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the reach-from-the-open record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so it has no day's open to count from, and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9%. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun s2(x: Double) = (if (x > 0) "+" else "") + "%.2f%%".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun p1(x: Double) = "%.1f%%".format(Locale.ENGLISH, x).replace(".0%", "%")
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** The day's own open as the mark: "from its open", "either side of the open", "open se". */
    private val FROM_OPEN = Regex(" (from|off|away from|beyond|past|above|below|around|of|either side of|each side of|both sides of|on both sides of|" +
        "on either side of) (the |its |the day s |day s |nifty s |banknifty s |sensex s |finnifty s )?(daily )?open | open (se|ke upar|ke neeche|ke niche) ")
    /** Getting away from it: moving, going, reaching. */
    private val MOVE = Regex(" (move|moves|moved|moving|go|goes|went|going|get|gets|got|travel|travels|travelled|traveled|trade|trades|traded|" +
        "swing|swings|swung|run|runs|stretch|stretches|reach|reaches|reached|extend|extends|wander|wanders|drift|drifts|far|door|dur|" +
        "jata|jaati|jati|jaata|chalta|chalti|nikalta|nikalti) ")
    /** Ending near it: "close within 0.3% of its open", "end near the open". */
    private val NEAR = Regex(" (close|closes|closed|end|ends|ended|finish|finishes|settle|settles|band) (near|within|close to|around|at|back at|back near|" +
        "\\d+(?:\\.\\d+)? ?(?:%|percent|per cent|pc|pct|points|point|pts) of) ")
    /** Named as a record. */
    private val NAME = Regex(" (open reach|open excursion|excursion from the open|reach from the open|move from open|moves from open|move from the open|" +
        "moves from the open|distance from the open|distance from open) (record|records|stats|statistics|history|data) ")
    /** Asked of the record: how often, how far usually. */
    private val HOW = Regex(" (how often|how many times|how many days|how frequently|what share|what percent|what percentage|usually|normally|" +
        "typically|generally|tend to|tends to|on average|average|median|historically|record|history|stats|statistics|odds|chance|chances|" +
        "kitni baar|kitne din|aksar|zyada tar|mostly|often|jata hai|jaata hai|jati hai|jaati hai) ")
    private val SIZE = Regex(" (\\d+(?:\\.\\d+)?) ?(%|percent|per cent|pc|pct) ")
    private val POINTS = Regex(" (\\d{1,4}(?:\\.\\d+)?) ?(points|point|pts|pt) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, stock screens, a definition, a
    // reason, today or now (the day's own read), since a time, yesterday, the gap, the opening range, the open as the day's
    // high or low, the first or last part of the day, overnight, the day after, candles, a week's, month's or year's move,
    // expiry, options, gold or VIX.
    private val NOT = Regex(" (will|would|going to be|gonna|tomorrow|tomorrows|kal|predict|prediction|forecast|outlook|expect|should|shall|buy|sell|" +
        "enter|exit|i|me|my|mine|we|our|if|what if|suppose|imagine|scenario|agar|remind|reminder|alert|alarm|notify|bot|bots|algo|strategy|" +
        "backtest|stock|stocks|scan|scanner|screener|share|shares|mean|means|meaning|define|explain|why|kyun|kyon|kyu|reason|" +
        "today|todays|aaj|aj|now|right now|abhi|so far|this morning|since|yesterday|yesterdays|last time|when did|" +
        "gap|gaps|gapped|opening|open high|open low|open is|open was|open the high|open the low|open equals|first|last hour|closing hour|" +
        "overnight|night|raat|next day|day after|agle din|candle|candles|week|weekly|weeks|hafte|month|monthly|year|yearly|expiry|expiries|" +
        "call|calls|put|puts|premium|premiums|option|options|ce|pe|strike|straddle|strangle|gold|vix|fear|position|positions|portfolio|stop|stops|sl) ")

    /** What was asked, or null: the record of how far days got from their own open only, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD || it == Market.VIX }) return null
        if (!NAME.containsMatchIn(t)) {
            if (!FROM_OPEN.containsMatchIn(t) || !HOW.containsMatchIn(t)) return null
            if (!MOVE.containsMatchIn(t) && !NEAR.containsMatchIn(t)) return null
        }
        val p = POINTS.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        if (p != null && p > 0) return Q(pts = p)
        val size = SIZE.find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return Q()
        return if (size >= MIN_PCT && size <= MAX_PCT) Q(size) else Q(DEFAULT_PCT, asked = size)
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        if (markets.isEmpty()) return Market.NIFTY
        return markets.firstOrNull { it in INDICES }
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /** The whole sessions before [today] in [bars] ([Comebacks.whole]), newest [MAX_SESSIONS], oldest first. */
    fun past(bars: List<Candle>, today: LocalDate): List<Day> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) && Comebacks.whole(it) && it.open > 0 }
            .map { s -> Day(s.day, s.open, s.bars.maxOf { it.h }, s.bars.minOf { it.l }, s.close) }
            .takeLast(MAX_SESSIONS)

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun quartile(xs: List<Double>, q: Double): Double { val s = xs.sorted(); return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)] }

    /** [days]' record for a size of [pct]% of each day's open, or [pts] points when given. */
    fun record(days: List<Day>, pct: Double, pts: Double? = null): Record {
        fun line(d: Day) = if (pts != null) pts / d.open * 100 else pct
        val up = days.map { it.upPct >= line(it) }
        val down = days.map { it.downPct >= line(it) }
        var aboveOnly = 0; var belowOnly = 0; var both = 0; var neither = 0; var endedNear = 0; var reachedEndedNear = 0
        for (i in days.indices) {
            val near = abs(days[i].closePct) < line(days[i])
            if (near) endedNear++
            when {
                up[i] && down[i] -> both++
                up[i] -> aboveOnly++
                down[i] -> belowOnly++
                else -> neither++
            }
            if ((up[i] || down[i]) && near) reachedEndedNear++
        }
        val far = days.map { it.farPct }
        return Record(days.size, aboveOnly, belowOnly, both, neither, endedNear, reachedEndedNear,
            median(days.map { it.upPct }), median(days.map { it.downPct }), median(far), quartile(far, 0.25), quartile(far, 0.75))
    }

    /** [m]'s record from its 1-minute candles over many days, for [q], at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole session${if (days.size == 1) "" else "s"} of ${m.label} on the phone, Boss - " +
                "too few to say how far its days usually get from the open (I need $MIN_SESSIONS)."
        val r = record(days, q.pct, q.pts)
        val size = if (q.pts != null) "${pts(q.pts)} points" else p1(q.pct)
        val lines = ArrayList<String>()
        // The lead carries the key figure (ShortAnswer's line).
        lines += "${m.label} went $size or more from its open on ${r.reached} of the last ${r.n} whole sessions on this phone " +
            "(${share(r.reached, r.n)}), and on both sides of it on ${r.both} (${share(r.both, r.n)})."
        if (q.asked != null)
            lines += "I count sizes of ${p1(MIN_PCT).removeSuffix("%")} to ${p1(MAX_PCT)} only, so that is ${p1(q.pct)}, not the ${p1(q.asked)} asked."
        lines += "Above the open only on ${r.aboveOnly}, below it only on ${r.belowOnly}, and it never got that far on ${r.neither} " +
            "(${date(days.first().day)} to ${date(days.last().day)})."
        lines += "The median day reached ${p2(r.medianUp)} above its open and ${p2(r.medianDown)} below it, and ${p2(r.medianFar)} on its farther side " +
            "(the middle half ${p2(r.farLow)} to ${p2(r.farHigh)})."
        lines += "It ended within $size of its open on ${r.endedNear} days (${share(r.endedNear, r.n)})" +
            (if (r.reached > 0) ", ${r.reachedEndedNear} of them after first going that far (${share(r.reachedEndedNear, r.reached)} of the ${r.reached} that did)." else ".")
        val far = days.maxBy { it.farPct }
        val farSide = if (far.upPct >= far.downPct) "above" else "below"
        lines += "The farthest was ${date(far.day)}, ${p2(far.farPct)} $farSide its open, ending ${s2(far.closePct)} on it."
        if (r.n < FEW_SESSIONS) lines += "That is only ${r.n} days, so a few more would move these shares."
        todayLine(m, bars, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today's reach from its open, when the phone has today's session from the open. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val t = MarketStory.sessions(bars).lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() && it.open > 0 } ?: return null
        // Today's open is only today's open when its candles start at the open.
        if (t.bars.first().t.toLocalTime().isAfter(LocalTime.of(9, 20))) return null
        val d = Day(today, t.open, t.bars.maxOf { it.h }, t.bars.minOf { it.l }, t.close)
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val last = t.bars.last().t.toLocalTime()
        val reach = "${p2(d.upPct)} above its open and ${p2(d.downPct)} below it"
        // "Ended" only for a whole session; candles that stop early say when they stop.
        return when {
            live -> "Today ${m.label} has been $reach so far, and is ${s2(d.closePct)} on the open."
            Comebacks.whole(t) -> "Today ${m.label} got $reach, and ended ${s2(d.closePct)} on the open."
            else -> "Today ${m.label} got $reach by ${"%02d:%02d".format(Locale.ENGLISH, last.hour, last.minute)}, where its candles on the phone stop."
        }
    }
}
