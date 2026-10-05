package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The give-back record (market intelligence, round 39): "after Nifty runs 100 points in the first hour, how much does it
 * give back by the close?", "how much of a 1% run from the open does BankNifty give back?", "how deep is the pullback
 * after Nifty runs 100 points?", "give back record for Sensex", "100 point chalne ke baad nifty kitna wapas deta hai". The
 * question an option buyer holding a winner asks: once the index has run a size from its own open, how much of that run
 * is usually left at the close, and how deep the pullbacks inside it went. From the whole sessions of 1-minute candles on
 * the phone (the newest [MAX_SESSIONS] before today, each read on its own and never set against another, [Comebacks.whole]
 * only): the run is the first minute that traded the size asked ([DEFAULT_PCT]% of the open when none is, or points) away
 * from the open on one side - before [LAST_TOUCH], or inside the first stretch of the session when one is asked ("in the
 * first hour"); a minute that traded both sides at once is no run. From there: the run's peak (the day's farthest point that
 * way), the share of the run from the open to the peak that the close gave back (kept two thirds or more, gave back a third
 * to two thirds, more than two thirds, or closed back at or through the open), the deepest pullback from a running peak
 * after the run began (median and middle half), and when the peak came. Beside today's run so far ("ended" only for a
 * whole session). A record of past sessions on this phone, said with its counts and plainly when there are too few; never
 * a forecast or advice; nothing acts. A fall or rally from the previous close stays [Comebacks]', the reach from the open
 * [OpenReach]'s, the first move's direction against the close [FirstMove]'s, the last hour's turn [LastHour]'s, the time
 * to move [MoveTime]'s, when the high and low usually come [DayClock]'s. Pure.
 */
object GiveBack {
    /**
     * What was asked: the size in % of the open ([pct]) or in points ([pts], then [pct] unused); [by] the minutes from the
     * open the run must come within (null: any time before [LAST_TOUCH]); [asked] the % said when it was outside [MIN_PCT]
     * to [MAX_PCT] (then [pct] is [DEFAULT_PCT], and the answer says so).
     */
    data class Q(val pct: Double = DEFAULT_PCT, val pts: Double? = null, val by: Int? = null, val asked: Double? = null)

    /**
     * One session's run: [day], its [open], whether the run was [up], the minute it reached the size ([at]), its [peak] (the
     * farthest point that way from then to the close) and when ([peakAt]), the [close] (or the last price), and the
     * [deepest] pullback in points from a running peak after the run began.
     */
    data class Run(val day: LocalDate, val open: Double, val up: Boolean, val at: LocalTime, val peak: Double, val peakAt: LocalTime,
                   val close: Double, val deepest: Double) {
        /** The run from the open to its peak, in points. */
        val run: Double get() = abs(peak - open)
        /** The share of [run] the close gave back from the peak (1 or more: back at or through the open). */
        val gave: Double get() = if (run <= 0) 0.0 else (if (up) peak - close else close - peak) / run
    }

    /** The record: [days] whole sessions read, [runs] of them with a run; the four ways they closed; the medians. */
    data class Record(val days: Int, val runs: List<Run>, val keptMost: Int, val gaveSome: Int, val gaveMost: Int, val gaveAll: Int,
                      val medianGave: Double?, val medianRun: Double?, val medianRunPct: Double?, val medianDeep: Double?,
                      val deepLo: Double?, val deepHi: Double?, val medianDeepPct: Double?, val peakAfter: Int)

    /** The sizes counted, % of the open. */
    const val MIN_PCT = 0.1
    const val MAX_PCT = 3.0
    /** The size when none is asked, %. */
    const val DEFAULT_PCT = 0.5
    /** Fewer whole sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 10
    /** Fewer whole sessions than this: said as a small record. */
    const val FEW_SESSIONS = 30
    /** Fewer runs than this: said as a small record. */
    const val FEW_RUNS = 15
    /** At most this many of the newest whole sessions are read. */
    const val MAX_SESSIONS = 120
    /** A run must come before this when no stretch is asked (some session left to give it back in). */
    val LAST_TOUCH: LocalTime = LocalTime.of(15, 0)
    private val OPEN = LocalTime.of(9, 15)
    private val CLOSE = LocalTime.of(15, 30)
    /** The indices the record is kept for (gold trades round the clock, India VIX is not traded). */
    internal val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past sessions on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the give-back record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so its day has no open of the kind, and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9%. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun pts(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun count(x: Int) = "%,d".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun pc(x: Double) = "%d%%".format(Locale.ENGLISH, Math.round(x * 100))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    private fun sizeText(pct: Double) = "%.2f".format(Locale.ENGLISH, pct).trimEnd('0').trimEnd('.') + "%"
    private fun stretch(by: Int) = when (by) {
        60 -> "the first hour"
        30 -> "the first half hour"
        120 -> "the first two hours"
        MORNING -> "the morning (to 12:00)"
        else -> "the first $by minutes"
    }

    // ---- the questions --------------------------------------------------------------------------------------------

    /** Giving the run back, or the pullback inside it. */
    private val GIVE = Regex(" (give back|gives back|gave back|giving back|given back|give it back|gives it back|hand back|hands back|" +
        "give up|gives up|retrace|retraces|retracing|retracement|retracements|pull back|pulls back|pullback|pullbacks|" +
        "wapas deta|wapas deti|wapas de deta|waapas deta|vapas deta|kitna wapas|kitna waapas|kitna vapas) ")
    /** The run itself said. */
    private val RUN = Regex(" (run|runs|ran|running|rally|rallies|rallied|rise|rises|rose|move|moves|moved|surge|surges|climb|climbs|" +
        "fall|falls|fell|drop|drops|slide|slides|gain|gains|trend|chalne|chalta|chal ke|chadhne|chadhta|badhne|girne|girta) ")
    /** Asked of the record. */
    private val HOW = Regex(" (how much|how far|how deep|how often|how many times|what share|what percent|what percentage|usually|normally|" +
        "typically|generally|tend to|tends to|on average|average|median|historically|kitna|kitni|kitni baar|aksar|zyada tar|mostly|often) ")
    /** Named as a record. */
    private val NAME = Regex(" (give back|giveback|pullback|pull back|retracement|run and pullback) (record|records|stats|statistics|history|data) ")
    /** "Pullback after a run", "give back after a rally": the record named by what it measures. */
    private val AFTER_RUN = Regex(" (pullback|pullbacks|pull back|give back|retracement) after (a |the )?(run|runs|rally|rallies|move|moves|trend) ")
    /** From the open, or a first stretch of the session. */
    private val OPENISH = Regex(" (from the open|from its open|from open|from the opening|open se|first hour|first half hour|first half an hour|" +
        "first \\d+ (minutes|minute|mins|min)|first two hours|first 2 hours|pehle ghante|pehla ghanta|pehle aadhe ghante|pehle adhe ghante|" +
        "in the morning|subah) ")
    private val SIZE = Regex(" (\\d+(?:\\.\\d+)?) ?(%|percent|per cent|pc|pct) ")
    private val POINTS = Regex(" (\\d{1,5}(?:\\.\\d+)?) ?(points|point|pts|pt) ")
    private val FIRST_MIN = Regex(" first (\\d+) (minutes|minute|mins|min) ")
    /** A time window said without "first" ("in 30 minutes", "within an hour"): the time-to-move record's, left alone. */
    private val WINDOW = Regex(" (\\d+ (minutes|minute|mins|min)|an hour|one hour|\\d+ hours|\\d+ hour|half an hour|half hour|ghante mein|ghante me) ")
    private const val MORNING = 165
    // A forecast or advice, Boss's own book, booking or trailing, a what-if, alerts and reminders, the app's bots, stock
    // screens, a definition, a reason, today, now or one past day, the day after, sessions, weeks or months, the gap,
    // candles, the last hour, lunch, expiry, options, gold or VIX, levels, a comeback from the previous close (Comebacks'),
    // weekdays, and Jarvis's or the app's own speed.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|tomorrows|kal|next|predict|prediction|forecast|outlook|expect|expected|should|shall|" +
        "buy|sell|enter|exit|book|booking|trail|trailing|i|me|my|mine|we|our|if|what if|suppose|imagine|scenario|agar|remind|reminder|alert|alarm|" +
        "notify|bot|bots|algo|strategy|backtest|stock|stocks|scan|scanner|screener|shares|mean|means|meaning|define|explain|what is|why|kyun|kyon|kyu|reason|" +
        "today|todays|aaj|aj|now|right now|abhi|so far|this|that|last|past|previous|prev|recent|recently|yesterday|yesterdays|did|was|were|" +
        "day after|agle din|sessions|week|weekly|weeks|hafte|month|monthly|months|year|years|gap|gaps|candle|candles|bar|bars|lunch|" +
        "expiry|expiries|call|calls|put|puts|premium|premiums|option|options|ce|pe|strike|strikes|theta|gold|vix|fear|" +
        "position|positions|portfolio|stop|stops|sl|target|targets|news|order|orders|reply|answer|answers|respond|load|loads|login|" +
        "you|your|jarvis|app|fib|fibonacci|level|levels|support|resistance|pivot|pivots|vwap|recover|recovers|recovery|comeback|bounce|bounces|" +
        "monday|mondays|tuesday|tuesdays|wednesday|wednesdays|thursday|thursdays|friday|fridays|weekday|weekdays|" +
        "somvar|somwar|mangalvar|mangalwar|budhvar|budhwar|guruvar|guruwar|shukravar|shukrawar) ")

    /** What was asked, or null: the record of how much of a run from the open the close gave back, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    /** The first stretch asked, in minutes from the open; null when none is; -1 when one is said that is not counted. */
    private fun by(t: String): Int? {
        FIRST_MIN.find(t)?.let { m -> return m.groupValues[1].toIntOrNull()?.takeIf { it in 5..300 } ?: -1 }
        return when {
            rx(" (first two hours|first 2 hours) ").containsMatchIn(t) -> 120
            rx(" (first half hour|first half an hour|pehle aadhe ghante|pehle adhe ghante) ").containsMatchIn(t) -> 30
            rx(" (first hour|pehle ghante|pehla ghanta) ").containsMatchIn(t) -> 60
            rx(" (in the morning|subah) ").containsMatchIn(t) -> MORNING
            else -> null
        }
    }

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD || it == Market.VIX }) return null
        val p = POINTS.find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
        val pc = SIZE.find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
        val sized = p != null || pc != null
        val named = NAME.containsMatchIn(t) || AFTER_RUN.containsMatchIn(t)
        val ok = named || (GIVE.containsMatchIn(t) && HOW.containsMatchIn(t) && (sized || OPENISH.containsMatchIn(t)) &&
            (RUN.containsMatchIn(t) || OPENISH.containsMatchIn(t)))
        if (!ok) return null
        val b = by(t)
        if (b == -1) return null
        // A plain time window ("in 30 minutes") is how long the move took - the time-to-move record's.
        if (b == null && WINDOW.containsMatchIn(t)) return null
        if (p != null) return Q(pts = p, by = b)
        if (pc == null) return Q(by = b)
        return if (pc >= MIN_PCT && pc <= MAX_PCT) Q(pc, by = b) else Q(DEFAULT_PCT, by = b, asked = pc)
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        if (markets.isEmpty()) return Market.NIFTY
        return markets.firstOrNull { it in INDICES }
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /**
     * [s]'s run for a size of [pct]% of its open, or [pts] points when given, reached before the first [by] minutes are
     * over (before [LAST_TOUCH] when null); null when it never ran that far, or its first minute that far traded both sides.
     */
    internal fun run(s: MarketStory.Session, pct: Double, pts: Double?, by: Int?): Run? {
        val bars = s.bars
        if (bars.isEmpty()) return null
        val open = s.open
        if (open <= 0) return null
        val d = pts ?: (open * pct / 100)
        if (d <= 0) return null
        val until = by?.let { OPEN.plusMinutes(it.toLong()) } ?: LAST_TOUCH
        var j = -1; var up = false
        for (i in bars.indices) {
            val b = bars[i]
            if (!b.t.toLocalTime().isBefore(until)) break
            val hi = b.h >= open + d; val lo = b.l <= open - d
            if (hi && lo) return null
            if (hi || lo) { j = i; up = hi; break }
        }
        if (j < 0) return null
        var peak = if (up) bars[j].h else bars[j].l
        var peakAt = bars[j].t.toLocalTime()
        var deepest = 0.0
        for (k in j + 1 until bars.size) {
            val b = bars[k]
            // The pullback is measured from the peak before this minute (a minute's high and low come in an unknown order).
            deepest = maxOf(deepest, if (up) peak - b.l else b.h - peak)
            if (up && b.h > peak) { peak = b.h; peakAt = b.t.toLocalTime() }
            if (!up && b.l < peak) { peak = b.l; peakAt = b.t.toLocalTime() }
        }
        return Run(s.day, open, up, bars[j].t.toLocalTime(), peak, peakAt, bars.last().c, deepest)
    }

    /** The whole sessions before [today] in [bars], newest [MAX_SESSIONS], oldest first ([Comebacks.whole]). */
    fun sessions(bars: List<Candle>, today: LocalDate): List<MarketStory.Session> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) && Comebacks.whole(it) }.takeLast(MAX_SESSIONS)

    private fun median(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.sorted().let { s ->
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun quart(xs: List<Double>, q: Double): Double? = if (xs.isEmpty()) null else xs.sorted().let { s -> s[((s.size - 1) * q).toInt()] }

    /** The record of [ss] for [q]. */
    fun record(ss: List<MarketStory.Session>, q: Q): Record {
        val rs = ss.mapNotNull { run(it, q.pct, q.pts, q.by) }
        val g = rs.map { it.gave }
        val deep = rs.map { it.deepest }
        val end = q.by?.let { OPEN.plusMinutes(it.toLong()) }
        return Record(ss.size, rs,
            keptMost = g.count { it <= 1.0 / 3 }, gaveSome = g.count { it > 1.0 / 3 && it <= 2.0 / 3 },
            gaveMost = g.count { it > 2.0 / 3 && it < 1.0 }, gaveAll = g.count { it >= 1.0 },
            medianGave = median(g), medianRun = median(rs.map { it.run }), medianRunPct = median(rs.map { it.run / it.open * 100 }),
            medianDeep = median(deep), deepLo = quart(deep, 0.25), deepHi = quart(deep, 0.75),
            medianDeepPct = median(rs.map { it.deepest / it.open * 100 }),
            peakAfter = if (end == null) 0 else rs.count { !it.peakAt.isBefore(end) })
    }

    /** [m]'s give-back record from its 1-minute candles over many days, for [q], at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val ss = sessions(bars, today)
        if (ss.size < MIN_SESSIONS)
            return "I have only ${ss.size} whole ${m.label} session${if (ss.size == 1) "" else "s"} on the phone, Boss - " +
                "too few to say how much of a run it usually gives back (I need $MIN_SESSIONS)."
        val r = record(ss, q)
        val size = if (q.pts != null) "${pts(q.pts)} points" else sizeText(q.pct)
        val within = q.by?.let { " within ${stretch(it)}" } ?: ""
        val span = "${count(r.days)} whole sessions (${date(ss.first().day)} to ${date(ss.last().day)})"
        val lines = ArrayList<String>()
        val n = r.runs.size
        val sizeNote = q.asked?.let { "I count sizes of ${sizeText(MIN_PCT)} to ${sizeText(MAX_PCT)} only, so that is ${sizeText(q.pct)}, not the ${sizeText(it)} asked." }
        if (n == 0) {
            lines += "None of the last $span of ${m.label} on this phone ran $size from its open$within, Boss, so there is no give-back to count."
            sizeNote?.let { lines += it }
        } else {
            // The lead carries the key figure (ShortAnswer's line).
            lines += "When ${m.label} ran $size from its open$within - ${count(n)} of the last $span on this phone (${share(n, r.days)}) - " +
                "the close gave back a median ${pc(r.medianGave ?: 0.0)} of the run from its peak."
            sizeNote?.let { lines += it }
            if (q.pts != null) {
                val avg = r.runs.map { it.open }.average()
                lines += "At the average open of about ${pts(avg)}, ${pts(q.pts)} points is ${p2(q.pts / avg * 100)}."
            }
            lines += "Of those ${count(n)} runs (${r.runs.count { it.up }} up, ${r.runs.count { !it.up }} down), the close kept two thirds or more of the run on " +
                "${r.keptMost} (${share(r.keptMost, n)}), gave back a third to two thirds on ${r.gaveSome} (${share(r.gaveSome, n)}), more than two thirds on " +
                "${r.gaveMost} (${share(r.gaveMost, n)}), and came back to or through the open on ${r.gaveAll} (${share(r.gaveAll, n)})."
            val runLine = "The median run from the open to its peak was ${pts(r.medianRun ?: 0.0)} points (${p2(r.medianRunPct ?: 0.0)})"
            lines += if (r.medianDeep != null && r.deepLo != null && r.deepHi != null)
                "$runLine, and the deepest pullback from a running peak after the run began was a median ${pts(r.medianDeep)} points " +
                    "(${p2(r.medianDeepPct ?: 0.0)}), the middle half ${pts(r.deepLo)} to ${pts(r.deepHi)}, on 1-minute candles."
            else "$runLine."
            if (q.by != null)
                lines += "The run's peak came after ${stretch(q.by)} was over on ${r.peakAfter} of the ${count(n)} (${share(r.peakAfter, n)})."
            else median(r.runs.map { it.peakAt.toSecondOfDay().toDouble() })?.let {
                lines += "The median time the peak was made was ${hm(LocalTime.ofSecondOfDay(it.toLong()))}."
            }
            if (r.days < FEW_SESSIONS || n < FEW_RUNS)
                lines += "${count(n)} run${if (n == 1) "" else "s"} in ${count(r.days)} sessions is a small record, so a few more sessions would move these shares."
        }
        todayLine(m, bars, today, now, q, size)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today's run so far against the same measure ("so far" while it trades, "ended" only for a whole session). */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, q: Q, size: String): String? {
        val s = MarketStory.sessions(bars).lastOrNull { it.day == today && it.bars.isNotEmpty() } ?: return null
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val whole = Comebacks.whole(s)
        val lead = when {
            live -> "Today so far"
            whole -> "Today"
            else -> "Today's candles stop at ${hm(s.bars.last().t.toLocalTime())}; up to there"
        }
        val within = q.by?.let { " within ${stretch(it)}" } ?: ""
        val x = run(s, q.pct, q.pts, q.by)
            ?: return "$lead, ${m.label} has not run $size from its open$within."
        val way = if (x.up) "up" else "down"
        val now2 = if (live) "the price now" else "the last price"
        val give = if (live || !whole) "$now2 has given back ${pc(x.gave)} of it" else "the close gave back ${pc(x.gave)} of it"
        return "$lead, ${m.label} ran $way $size from its open by ${hm(x.at)}; its peak${if (live) " so far" else ""} was ${pts(x.run)} points from the open " +
            "(at ${hm(x.peakAt)}), and $give."
    }
}
