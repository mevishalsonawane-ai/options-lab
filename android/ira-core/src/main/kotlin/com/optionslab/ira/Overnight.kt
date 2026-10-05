package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * The overnight and in-session record (market intelligence, round 34): "does Nifty make its moves overnight or during the
 * day?", "overnight vs intraday returns for BankNifty", "how big are Nifty's overnight moves usually?", "is the trend made
 * in the gaps or in market hours?", "are weekend gaps bigger than weekday overnight moves?", "nifty ka move raat mein banta
 * hai ya din mein". From the whole sessions of 1-minute candles on the phone (the newest [MAX_SESSIONS] before today), each
 * set against the whole session just before it ([Comebacks.follows]: the previous trading day only, a missing session is
 * never bridged): the overnight part is the open against the previous close, the session part the close against the open.
 * How much each added up to over the window (compounded) beside the whole close-to-close, how often each was up, their
 * median sizes either way (in % and points), the share of all the movement that came overnight, the opens after a weekend
 * or holiday beside the ordinary overnights, and the biggest of each. Beside today: today's opening move and the session's
 * so far. A record of past days on this phone, said with its counts and plainly when there are too few; never a forecast
 * or advice; nothing acts. Whether a gap fills stays [GapRecord]'s, today's gap [Gap]'s, the two-year study [Study]'s and
 * the weekday record [Weekdays]'. Pure.
 */
object Overnight {
    /** What was asked: [weekend] when the opens after a weekend or holiday were named. */
    data class Q(val weekend: Boolean = false)

    /** One whole session against the whole session just before it: that close [prevClose], this [open] and [close]; [afterBreak] when a weekend or holiday came between. */
    data class Day(val day: LocalDate, val prevClose: Double, val open: Double, val close: Double, val afterBreak: Boolean) {
        /** The overnight move: the open against the previous close, % of it. */
        val nightPct: Double get() = (open - prevClose) / prevClose * 100
        /** The session's move: the close against the open, % of it. */
        val dayPct: Double get() = (close - open) / open * 100
        /** The whole move, close to close, % of the previous close. */
        val totalPct: Double get() = (close - prevClose) / prevClose * 100
    }

    /** How the days split between overnight and the session. */
    data class Record(
        val sessions: Int, val nightSum: Double, val daySum: Double, val totalSum: Double,
        val nightUp: Int, val nightDown: Int, val dayUp: Int, val dayDown: Int,
        val medianNight: Double, val medianDay: Double, val medianNightPts: Double, val medianDayPts: Double,
        /** The share (%) of all the movement, overnight plus in the session, that came overnight. */
        val nightShare: Double,
        val breaks: Int, val medianBreakNight: Double?, val medianPlainNight: Double?,
        val biggestNight: Day, val biggestDay: Day,
    )

    /** Fewer sessions with a whole one just before them than this: too few to say anything. */
    const val MIN_SESSIONS = 20
    /** Fewer than this: said as a small record. */
    const val FEW_SESSIONS = 60
    /** Fewer opens after a weekend or holiday than this: too few to set beside the ordinary overnights. */
    const val MIN_BREAKS = 5
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    /** The session before counts only when at most this many days earlier (a weekend and a holiday). */
    const val MAX_DAYS_APART = 4L
    /** An overnight or session move under this (%) is counted as flat. */
    const val FLAT = 0.02
    private val CLOSE = LocalTime.of(15, 30)
    /** The indices the record is kept for (gold trades round the clock, India VIX is not traded). */
    internal val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the overnight record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so it has no night to split off, and India VIX is not traded."

    // The recognizer's slips (round 23): "over nite", "overnite", "over knight", "over nait" are the night.
    private fun norm(text: String) = (" " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9%. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " ")
        .replace(SLIP, " overnight ")
    private val SLIP = Regex(" (over ?nite|over ?nites|over ?knight|over ?knights|over ?nait|over ?night s|ovarnight|overnigh) ")
    private fun s2(x: Double) = (if (x > 0) "+" else "") + "%.2f%%".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun n0(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** The night between sessions, said outright (never a gap alone, which is [GapRecord]'s first). */
    private val NIGHT = Regex(" (overnight|overnights|over night|over the night|between sessions|between the sessions|close to open|close to the open|" +
        "from close to open|from the close to the open|after hours|raat|raat mein|raat me|raat main|raat bhar|raat ko) ")
    /** The night or the opening jump, when set against the session. */
    private val NIGHT_OR_GAP = Regex(" (overnight|overnights|over night|between sessions|close to open|close to the open|after hours|raat|" +
        "gap|gaps|opening gap|opening gaps) ")
    /** Two parts set side by side. */
    private val SIDE = Regex(" (or|ya|vs|versus|compare|compared|comparison|against|than|bigger|smaller|larger|more|less|most|split|where|which part|what part|how much of) ")
    /** The hours the market is open. */
    private val SESSION = Regex(" (intraday|intra day|during the day|in the day|during the session|in the session|in session|during market hours|" +
        "in market hours|market hours|trading hours|during trading hours|open to close|from open to close|from the open to the close|" +
        "day session|din mein|din me|din main|din bhar|din ko|" +
        // Round 23: the day set against the gap in plain words - "the gaps compared to the day's move", "more than the day".
        "the day s move|the day s moves|the day s range|the rest of the day|than the day|or the day|vs the day|the session|the sessions) ")
    /** A move, a return or a trend. */
    private val MOVE = Regex(" (move|moves|moved|movement|movements|return|returns|gain|gains|gained|change|changes|trend|trends|rally|rallies|" +
        "fall|falls|made|make|makes|happen|happens|come|comes|came|chalta|chalti|banta|banti|hota|hoti|aata|aati) ")
    /** Asked of the record or set side by side. */
    private val HOW = Regex(" (how often|how many times|how much|how much of|how big|how large|how far|what share|what percent|what percentage|" +
        "what part|which part|usually|normally|typically|generally|tend to|tends to|on average|historically|record|history|stats|statistics|" +
        "bigger|smaller|larger|more|less|most|mostly|compare|compared|comparison|versus|vs|or|ya|kitna|kitni|kitne|zyada|aksar) ")
    /** Named outright: "overnight vs intraday", "overnight returns", "overnight record". */
    private val NAMED = Regex(" ((overnight|close to open) (vs|versus|and|or|against|compared to) (intraday|intra day|session|day|daytime|in session|open to close)|" +
        "(intraday|intra day|session|daytime|open to close) (vs|versus|and|or|against|compared to) (overnight|close to open)|" +
        "overnight (return|returns|record|records|split|stats|statistics|history|moves record|move record)|" +
        // Round 23: "overnight moves of Nifty" (and the recognizer's "over nite moves of Nifty").
        "overnight (moves|movements) (of|for|in|on)|" +
        "close to open (return|returns|move|moves))( |$)")
    /** A gap named, and the weekend's set against the rest (round 23). */
    private val GAPS = Regex(" (gap|gaps|opening gap|opening gaps) ")
    private val SET_AGAINST = Regex(" (bigger|smaller|larger|wider|vs|versus|than|compared|compare|against|usually|normally|typically|on average|zyada|bade|bada) ")
    /** The opens after a weekend or a holiday. */
    private val WEEKEND = Regex(" (weekend|weekends|week end|monday open|monday opens|monday gap|monday gaps|after a holiday|after holidays|after the weekend|" +
        "over the weekend|holiday gap|holiday gaps|chutti) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, stock screens, a definition, a
    // reason, today or now (today's gap is Gap's), a fill (GapRecord's), a week's or month's move, expiry, options, gold or VIX.
    // ("Trading hours" is the session, so "trading" alone is not Boss's own trading; "trade" and "trades" still are.)
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|tonight|kal|next|predict|prediction|forecast|outlook|expect|should|shall|buy|sell|" +
        "enter|exit|trade|trades|hold|holding|carry|carrying|i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|remind|reminder|alert|" +
        "alarm|notify|bot|bots|algo|strategy|backtest|stock|stocks|scan|scanner|screener|shares|mean|means|meaning|define|explain|" +
        "why|kyun|kyon|kyu|reason|today|todays|aaj|aj|now|right now|abhi|so far|this morning|yesterday|fill|fills|filled|filling|bharta|bharti|" +
        "this week|this month|last week|last month|expiry|expiries|call|calls|put|puts|premium|premiums|option|options|ce|pe|strike|gold|vix|fear|" +
        "position|positions|portfolio|stop|stops|sl|news) ")
    /** "What is ..." asks for a definition - unless the record is named outright ("what's the overnight vs intraday split"). */
    private val WHAT_IS = Regex(" (what is|what s) ")

    /** What was asked, or null: the record of overnight moves against the session's only, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (WHAT_IS.containsMatchIn(t) && !NAMED.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD || it == Market.VIX }) return null
        val weekend = WEEKEND.containsMatchIn(t)
        val ok = NAMED.containsMatchIn(t) ||
            // Set side by side: "overnight or during the day", "in the gaps or in market hours", "raat mein ya din mein".
            NIGHT_OR_GAP.containsMatchIn(t) && SESSION.containsMatchIn(t) && SIDE.containsMatchIn(t) && (MOVE.containsMatchIn(t) || HOW.containsMatchIn(t)) ||
            // The night alone, asked of its record: "how big are Nifty's overnight moves usually".
            NIGHT.containsMatchIn(t) && MOVE.containsMatchIn(t) && HOW.containsMatchIn(t) ||
            // The opens after a weekend set against the ordinary nights: "are weekend gaps bigger than weekday overnight moves".
            weekend && NIGHT.containsMatchIn(t) && HOW.containsMatchIn(t) ||
            // Round 23: the weekend's gaps set against the others, "gap" said for the night: "are Monday gaps bigger",
            // "weekend gaps vs weekday gaps" (a gap alone, or one day's, stays GapRecord's and Gap's).
            weekend && GAPS.containsMatchIn(t) && SET_AGAINST.containsMatchIn(t)
        return if (ok) Q(weekend) else null
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        if (markets.isEmpty()) return Market.NIFTY
        return markets.firstOrNull { it in INDICES }
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /**
     * The whole sessions before [today] in [bars] whose session before on the phone was whole, at most [MAX_DAYS_APART]
     * days earlier and the trading day just before it by [isTradingDay] ([Comebacks.follows]: a missing session is never
     * bridged); newest [MAX_SESSIONS], oldest first.
     */
    fun past(bars: List<Candle>, today: LocalDate, isTradingDay: (LocalDate) -> Boolean = Comebacks.WEEKDAYS): List<Day> {
        val ss = MarketStory.sessions(bars).filter { it.day.isBefore(today) && it.bars.isNotEmpty() }
        val out = ArrayList<Day>()
        for (i in 1 until ss.size) {
            val before = ss[i - 1]; val s = ss[i]
            if (!Comebacks.whole(before) || !Comebacks.whole(s) || before.close <= 0 || s.open <= 0) continue
            if (ChronoUnit.DAYS.between(before.day, s.day) > MAX_DAYS_APART || !Comebacks.follows(before.day, s.day, isTradingDay)) continue
            out += Day(s.day, before.close, s.open, s.close, ChronoUnit.DAYS.between(before.day, s.day) > 1)
        }
        return out.takeLast(MAX_SESSIONS)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    private fun compound(xs: List<Double>): Double = (xs.fold(1.0) { acc, p -> acc * (1 + p / 100) } - 1) * 100

    /** The days in [days] split into overnight and the session. [days] must not be empty. */
    fun record(days: List<Day>): Record {
        val nightAbs = days.sumOf { abs(it.nightPct) }; val dayAbs = days.sumOf { abs(it.dayPct) }
        val breaks = days.filter { it.afterBreak }; val plain = days.filter { !it.afterBreak }
        return Record(days.size, compound(days.map { it.nightPct }), compound(days.map { it.dayPct }), compound(days.map { it.totalPct }),
            days.count { it.nightPct >= FLAT }, days.count { it.nightPct <= -FLAT }, days.count { it.dayPct >= FLAT }, days.count { it.dayPct <= -FLAT },
            median(days.map { abs(it.nightPct) }), median(days.map { abs(it.dayPct) }),
            median(days.map { abs(it.open - it.prevClose) }), median(days.map { abs(it.close - it.open) }),
            if (nightAbs + dayAbs <= 0) 0.0 else nightAbs / (nightAbs + dayAbs) * 100,
            breaks.size, if (breaks.isEmpty()) null else median(breaks.map { abs(it.nightPct) }), if (plain.isEmpty()) null else median(plain.map { abs(it.nightPct) }),
            days.maxBy { abs(it.nightPct) }, days.maxBy { abs(it.dayPct) })
    }

    /** [m]'s record from its 1-minute candles over many days, for [q], at [now] on [today]; [isTradingDay] the app's calendar. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, isTradingDay: (LocalDate) -> Boolean = Comebacks.WEEKDAYS): String {
        val days = past(bars, today, isTradingDay)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole session${if (days.size == 1) "" else "s"} of ${m.label} with the one just before on the phone, Boss - " +
                "too few to split its moves between overnight and the session (I need $MIN_SESSIONS)."
        val r = record(days)
        val lines = ArrayList<String>()
        val breakLine = breakLine(m, r)
        lines += "Over the last ${r.sessions} whole sessions of ${m.label} on this phone (${date(days.first().day)} to ${date(days.last().day)}), " +
            "each split at its open - overnight from the previous close to the open, the session from the open to the close:"
        if (q.weekend && breakLine != null) lines += breakLine
        lines += "Added up, the overnight moves came to ${s2(r.nightSum)} and the sessions to ${s2(r.daySum)}, against ${s2(r.totalSum)} close to close."
        lines += "${m.label} opened up on ${r.nightUp} (${share(r.nightUp, r.sessions)}) and down on ${r.nightDown}; the session closed above its open on " +
            "${r.dayUp} (${share(r.dayUp, r.sessions)}) and below it on ${r.dayDown}."
        lines += "The median overnight move was ${p2(r.medianNight)} either way (about ${n0(r.medianNightPts)} points), the median session move ${p2(r.medianDay)} " +
            "(about ${n0(r.medianDayPts)} points); ${"%.0f".format(Locale.ENGLISH, r.nightShare)}% of all the movement came overnight."
        if (!q.weekend && breakLine != null) lines += breakLine
        else if (q.weekend && breakLine == null)
            lines += "Only ${r.breaks} of them opened after a weekend or holiday - too few to set beside the ordinary nights (I need $MIN_BREAKS)."
        val bn = r.biggestNight; val bd = r.biggestDay
        lines += "The biggest overnight move was ${date(bn.day)}, ${s2(bn.nightPct)}; the biggest session ${date(bd.day)}, ${s2(bd.dayPct)}."
        if (r.sessions < FEW_SESSIONS) lines += "That is only ${r.sessions} sessions, so a few days move these figures a lot."
        todayLine(m, bars, today, now, isTradingDay)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** The opens after a weekend or holiday beside the ordinary nights, when there are [MIN_BREAKS] or more of them. */
    private fun breakLine(m: Market, r: Record): String? {
        val b = r.medianBreakNight ?: return null; val p = r.medianPlainNight ?: return null
        if (r.breaks < MIN_BREAKS || r.sessions - r.breaks < MIN_BREAKS) return null
        return "The ${r.breaks} opens after a weekend or holiday moved a median ${p2(b)} from the close before, the ${r.sessions - r.breaks} after an " +
            "ordinary night ${p2(p)}${if (p > 0) " (${"%.1f".format(Locale.ENGLISH, b / p)} times)" else ""}."
    }

    /** Today so far, when the phone has today's session and a whole one just before it: its overnight move and the session's. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, isTradingDay: (LocalDate) -> Boolean): String? {
        val ss = MarketStory.sessions(bars)
        val t = ss.lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() && it.open > 0 } ?: return null
        val before = ss.lastOrNull { it.day.isBefore(today) }
            ?.takeIf { Comebacks.whole(it) && it.close > 0 && Comebacks.follows(it.day, today, isTradingDay) } ?: return null
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val night = (t.open - before.close) / before.close * 100
        val session = (t.close - t.open) / t.open * 100
        // "Ended" only for a whole session; candles that stop early say when they stop.
        val last = t.bars.last().t.toLocalTime()
        val where = when {
            live -> "is ${s2(session)} on its open so far"
            Comebacks.whole(t) -> "ended ${s2(session)} on its open"
            else -> "was ${s2(session)} on its open at ${"%02d:%02d".format(Locale.ENGLISH, last.hour, last.minute)}"
        }
        return "Today ${m.label} opened ${s2(night)} on ${date(before.day)}'s close and $where."
    }
}
