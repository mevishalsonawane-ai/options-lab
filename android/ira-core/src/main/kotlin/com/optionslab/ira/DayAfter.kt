package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * The day-after-a-big-day record (market intelligence, round 35): "after Nifty falls 1% in a day, what happens the next
 * day?", "does BankNifty bounce the day after a big down day?", "after a 2% up day does Nifty follow through the next
 * session?", "big day follow through record", "1% girne ke baad agle din nifty kya karta hai". From the whole sessions of
 * 1-minute candles on the phone (the newest [MAX_SESSIONS] before today), each big day set against the whole session just
 * before it and the whole session just after it ([Comebacks.follows] both ways: the previous and next trading day only, a
 * missing session is never bridged): a big day closed [DEFAULT_PCT]% (or the size asked) or more away from the close before.
 * For the big up days and big down days apart (or the side asked): how the next session opened against the big day's close,
 * how it closed (the same way, the other way or flat), how often it traded beyond the big day's own high (or low), how often
 * it closed back past the close before the big day (the whole move undone), and the median next-day move and range - set
 * against what followed every whole session on the phone, so an index that mostly closes up is not passed off as a bounce.
 * The newest big day and its next session, and today against the close before. A record of past days on this phone, said
 * with its counts and plainly when there are too few; never a forecast or advice; nothing acts. The intraday comeback stays
 * [Comebacks]', a close at the day's high or low [ExtremeCloses]', the run of closes [Streak]'s, the big days one by one
 * [MarketMemory]'s, the day after a VIX jump [VixNext]'s. Pure.
 */
object DayAfter {
    /**
     * What was asked: the big day's way ([side] +1 a big rise, -1 a big fall, null both) and its size in % ([pct]); [asked]
     * the size said when it was outside [MIN_PCT] to [MAX_PCT] (then [pct] is [DEFAULT_PCT], and the answer says so).
     */
    data class Q(val side: Int?, val pct: Double = DEFAULT_PCT, val asked: Double? = null)

    /** The sizes of a big day counted, % close to close. */
    const val MIN_PCT = 0.3
    const val MAX_PCT = 5.0

    /** One big-day candidate: a whole session ([day]) between the whole sessions just before and just after it. */
    data class Day(
        val day: LocalDate, val prevClose: Double, val high: Double, val low: Double, val close: Double,
        val next: LocalDate, val nextOpen: Double, val nextHigh: Double, val nextLow: Double, val nextClose: Double,
    ) {
        /** The day's move, close to close, % of the close before. */
        val movePct: Double get() = (close - prevClose) / prevClose * 100
        /** The next session's move, close to close, % of this close. */
        val nextPct: Double get() = (nextClose - close) / close * 100
        /** The next session's open against this close, %. */
        val nextGapPct: Double get() = (nextOpen - close) / close * 100
        /** The next session's range, high to low, % of its open. */
        val nextRangePct: Double get() = (nextHigh - nextLow) / nextOpen * 100
    }

    /** What followed the big days of one side. */
    data class Side(
        val side: Int, val count: Int, val openedSame: Int, val closedSame: Int, val closedOther: Int,
        val beyondExtreme: Int, val undone: Int, val medianNext: Double, val medianNextRange: Double,
        /** Every day on the phone with a next session: how often the next one closed this side's way. */
        val baseSame: Int, val baseCount: Int, val medianRange: Double,
    )

    /** Fewer days with a whole session on each side than this: too few to say anything. */
    const val MIN_SESSIONS = 20
    /** Fewer big days of a side than this: too few to say that side's record. */
    const val MIN_BIG = 5
    /** Fewer big days of a side than this: said as a small record. */
    const val FEW_BIG = 15
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    /** The size of a big day when none is asked, % close to close. */
    const val DEFAULT_PCT = 1.0
    /** A neighbour session counts only when at most this many days away (a weekend and a holiday). */
    const val MAX_DAYS_APART = 4L
    /** A next-day move under this (%) is counted as flat. */
    const val FLAT = 0.02
    private val CLOSE = LocalTime.of(15, 30)
    /** The indices the record is kept for (gold trades round the clock, India VIX is not traded). */
    internal val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the day-after record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock, so it has no close to count a day from, and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ")
        .replace(rx("[^a-z0-9%. ]"), " ").replace(rx("(?<!\\d)\\.|\\.(?!\\d)"), " ").replace(rx("\\s+"), " ").trim() + " "
    private fun s2(x: Double) = (if (x > 0) "+" else "") + "%.2f%%".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun p1(x: Double) = "%.1f%%".format(Locale.ENGLISH, x).replace(".0%", "%")
    private fun share(k: Int, of: Int) = if (of == 0) "0%" else "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** The session after, said outright. */
    private val NEXT = Regex(" (next day|next days|next session|next sessions|next trading day|the day after|day after|days after|" +
        "the following day|following day|the following session|following session|the session after|the session that follows|" +
        "agle din|agla din|agle session|uske agle din|dusre din|doosre din) ")
    /** Following through, named as a record: "follow through record", "day after record". */
    private val NAME = Regex(" ((big day|big days|big move|big moves|big up day|big down day|day after|next day) " +
        "(follow through|followthrough|record|records|stats|statistics|history)|follow through (record|records|stats|statistics|history|rate|odds)) ")
    /** A big day: a size in %, or said big. */
    private val SIZE = Regex(" (\\d+(?:\\.\\d+)?) ?(%|percent|per cent|pc|pct) ")
    private val BIG = Regex(" (big|large|huge|sharp|strong|heavy|massive|bada|badi|bade|tez) ")
    private val DOWN = Regex(" (down|fall|falls|fell|falling|fallen|drop|drops|dropped|dip|dips|dipped|slide|slid|selloff|sell off|crash|crashes|crashed|" +
        "red|loss|decline|declines|declined|girta|girti|girne|gira|giri|gire|gir|tootne|toota|tuta) ")
    private val UP = Regex(" (up|rise|rises|rose|rising|risen|rally|rallies|rallied|jump|jumps|jumped|surge|surges|surged|gain|gains|gained|" +
        "green|climb|climbs|climbed|chadhta|chadhti|chadhne|chadha|chadhe|badhne|badha|uchhal|uchalta|uchla) ")
    /** Asked of the record or of what followed. */
    private val HOW = Regex(" (how often|how many times|how frequently|what share|what percent|what percentage|usually|normally|typically|" +
        "generally|tend to|tends to|on average|historically|record|history|stats|statistics|odds|chance|chances|what happens|what happened|" +
        "what does|how does|how did|does|do|follow through|follows through|followed through|bounce|bounces|bounced|rebound|rebounds|" +
        "continue|continues|continued|reverse|reverses|reversed|kya hota|kya karta|kya karti|kaisa|kitni baar|aksar) ")
    // A forecast or advice, Boss's own book, a what-if, alerts and reminders, the app's bots, stock screens, a definition, a
    // reason, today, yesterday or now (one day is the day's own read), the last time (MarketMemory's), a run of closes
    // (Streak's), the intraday comeback, the open's gap, candles, the first or last hour, a week's, month's or year's move,
    // expiry, options, gold or VIX.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|tomorrows|kal|predict|prediction|forecast|outlook|expect|should|shall|buy|sell|" +
        "enter|exit|trade|trades|hold|holding|carry|i|me|my|mine|we|our|if|what if|suppose|imagine|scenario|agar|remind|reminder|alert|alarm|" +
        "notify|bot|bots|algo|strategy|backtest|stock|stocks|scan|scanner|screener|share|shares|mean|means|meaning|define|explain|" +
        "why|kyun|kyon|kyu|reason|today|todays|aaj|aj|now|right now|abhi|so far|this morning|yesterday|yesterdays|last|last time|when did|" +
        "in a row|consecutive|streak|streaks|straight|intraday|intra day|in the day|during the day|within the day|during the session|din mein|" +
        "gap|gaps|gapped|candle|candles|first hour|first half hour|last hour|week|weekly|weeks|month|monthly|year|yearly|expiry|expiries|" +
        "call|calls|put|puts|premium|premiums|option|options|ce|pe|strike|gold|vix|fear|position|positions|portfolio|stop|stops|sl|news) ")

    /** What was asked, or null: the record of what followed big days only, never a forecast or advice. */
    fun asked(text: String): Q? = askedKept.of(text) { askedFresh(text) }

    /** The last words read (the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Q?>(64)

    private fun askedFresh(text: String): Q? {
        val t = norm(text)
        if (NOT.containsMatchIn(t)) return null
        if (Market.mentioned(text).any { it == Market.GOLD || it == Market.VIX }) return null
        val named = NAME.containsMatchIn(t)
        val size = SIZE.find(t)?.groupValues?.get(1)?.toDoubleOrNull()
        val down = DOWN.find(t)?.range?.first
        val up = UP.find(t)?.range?.first
        if (!named) {
            // The session after, a big day (a size or "big") with its way, asked of what followed.
            if (!NEXT.containsMatchIn(t) || !HOW.containsMatchIn(t)) return null
            if (size == null && !BIG.containsMatchIn(t)) return null
            if (down == null && up == null) return null
        }
        val side = side(t)
        val inRange = size != null && size >= MIN_PCT && size <= MAX_PCT
        return Q(side, if (inRange) size!! else DEFAULT_PCT, if (size != null && !inRange) size else null)
    }

    /** A word's way: -1 a fall, +1 a rise, 0 neither. */
    private fun way(w: String): Int = when {
        DOWN.containsMatchIn(" $w ") -> -1
        UP.containsMatchIn(" $w ") -> 1
        else -> 0
    }

    /**
     * The big day's way in [t] (normalized): with one way said, that one. With both ("does nifty rally the day after a 1%
     * drop"), the way said with the size or "big" ("1% drop", "big rally", "falls 1%"), else the one in the clause after
     * "after" (or before "ke baad"), else the first.
     */
    private fun side(t: String): Int? {
        val ws = t.trim().split(" ").filter { it.isNotEmpty() }
        val ways = ws.map { way(it) }.toMutableList()
        for (i in 0 until ws.size - 1) if (ws[i] == "sell" && ws[i + 1] == "off") ways[i] = -1
        val said = ways.filter { it != 0 }
        if (said.isEmpty()) return null
        if (said.distinct().size == 1) return said[0]
        fun one(xs: List<Int>): Int? = xs.filter { it != 0 }.distinct().singleOrNull()
        // Attached to the size or "big": the nearest word around it, the one after first ("1% drop", "big down day",
        // "falls 1%", "big sharp fall").
        val marks = ws.indices.filter { i -> SIZE.containsMatchIn(" ${ws[i]} ") || BIG.containsMatchIn(" ${ws[i]} ") ||
            (i + 1 < ws.size && SIZE.containsMatchIn(" ${ws[i]} ${ws[i + 1]} ")) }
        for (i in marks) for (d in listOf(1, -1, 2, -2, 3)) {
            val j = i + d
            if (j in ws.indices && ways[j] != 0) return ways[j]
        }
        // The clause before "ke baad" (Hindi), or after the last "after" that is followed by one way.
        val baad = (0 until ws.size - 1).lastOrNull { ws[it] == "ke" && ws[it + 1] == "baad" }
        if (baad != null) one(ways.subList(0, baad))?.let { return it }
        for (a in ws.indices.reversed().filter { ws[it] == "after" }) one(ways.subList(a + 1, ws.size))?.let { return it }
        return said[0]
    }

    /** The index asked about (Nifty when none is named), or null for gold or India VIX alone. */
    fun market(markets: List<Market>): Market? {
        if (markets.isEmpty()) return Market.NIFTY
        return markets.firstOrNull { it in INDICES }
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    /**
     * The whole sessions before [today] in [bars] with a whole session just before and just after them, each at most
     * [MAX_DAYS_APART] days away and the neighbouring trading day by [isTradingDay] ([Comebacks.follows]: a missing session
     * is never bridged), the next one before [today] too; newest [MAX_SESSIONS], oldest first.
     */
    fun past(bars: List<Candle>, today: LocalDate, isTradingDay: (LocalDate) -> Boolean = Comebacks.WEEKDAYS): List<Day> {
        val ss = MarketStory.sessions(bars).filter { it.day.isBefore(today) && it.bars.isNotEmpty() }
        fun linked(a: MarketStory.Session, b: MarketStory.Session) =
            Comebacks.whole(a) && Comebacks.whole(b) && a.close > 0 && b.open > 0 &&
                ChronoUnit.DAYS.between(a.day, b.day) <= MAX_DAYS_APART && Comebacks.follows(a.day, b.day, isTradingDay)
        val out = ArrayList<Day>()
        for (i in 1 until ss.size - 1) {
            val before = ss[i - 1]; val s = ss[i]; val nx = ss[i + 1]
            if (!linked(before, s) || !linked(s, nx)) continue
            out += Day(s.day, before.close, s.bars.maxOf { it.h }, s.bars.minOf { it.l }, s.close,
                nx.day, nx.open, nx.bars.maxOf { it.h }, nx.bars.minOf { it.l }, nx.close)
        }
        return out.takeLast(MAX_SESSIONS)
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** The days of [days] that moved [pct]% or more the [side]'s way. */
    fun big(days: List<Day>, side: Int, pct: Double): List<Day> = days.filter { it.movePct * side >= pct }

    /** What followed the big days of [side] in [days] (null when there are none). */
    fun side(days: List<Day>, side: Int, pct: Double): Side? {
        val xs = big(days, side, pct)
        if (xs.isEmpty() || days.isEmpty()) return null
        return Side(side, xs.size,
            xs.count { it.nextGapPct * side >= FLAT },
            xs.count { it.nextPct * side >= FLAT }, xs.count { it.nextPct * side <= -FLAT },
            xs.count { if (side > 0) it.nextHigh > it.high else it.nextLow < it.low },
            xs.count { if (side > 0) it.nextClose <= it.prevClose else it.nextClose >= it.prevClose },
            median(xs.map { it.nextPct * side }), median(xs.map { it.nextRangePct }),
            days.count { it.nextPct * side >= FLAT }, days.size, median(days.map { it.nextRangePct }))
    }

    /** [m]'s record from its 1-minute candles over many days, for [q], at [now] on [today]; [isTradingDay] the app's calendar. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, isTradingDay: (LocalDate) -> Boolean = Comebacks.WEEKDAYS): String {
        val days = past(bars, today, isTradingDay)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole session${if (days.size == 1) "" else "s"} of ${m.label} with a whole one on each side on the phone, Boss - " +
                "too few to say what followed its big days (I need $MIN_SESSIONS)."
        val lines = ArrayList<String>()
        if (q.asked != null)
            lines += "I count big days of ${p1(MIN_PCT).removeSuffix("%")} to ${p1(MAX_PCT)} only, Boss, so here are the ${p1(q.pct)} days."
        lines += "Over the last ${days.size} whole sessions of ${m.label} on this phone (${date(days.first().day)} to ${date(days.last().day)}), " +
            "a big day being a close ${p1(q.pct)} or more from the close before:"
        val sides = if (q.side != null) listOf(q.side) else listOf(-1, 1)
        for (sd in sides) lines += sideLine(m, days, sd, q.pct)
        val newest = days.lastOrNull { abs(it.movePct) >= q.pct && (q.side == null || it.movePct * q.side > 0) }
        if (newest != null)
            lines += "The newest was ${date(newest.day)}, when ${m.label} ended ${s2(newest.movePct)}; the next session, ${date(newest.next)}, ended ${s2(newest.nextPct)} on it."
        todayLine(m, bars, today, now, q.pct, q.side, isTradingDay)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    private fun sideLine(m: Market, days: List<Day>, sd: Int, pct: Double): String {
        val name = if (sd > 0) "up" else "down"
        val r = side(days, sd, pct)
        if (r == null || r.count < MIN_BIG)
            return "Only ${r?.count ?: 0} big $name day${if (r?.count == 1) "" else "s"} of ${p1(pct)} or more - too few to say what followed them (I need $MIN_BIG)."
        val way = if (sd > 0) "up" else "down"; val other = if (sd > 0) "down" else "up"
        val edge = if (sd > 0) "high" else "low"
        val small = if (r.count < FEW_BIG) " That is only ${r.count} days, so one or two more would move these shares a lot." else ""
        return "After the ${r.count} big $name days, the next session opened $way on ${r.openedSame} (${share(r.openedSame, r.count)}), " +
            "closed $way again on ${r.closedSame} (${share(r.closedSame, r.count)}) and $other on ${r.closedOther}; " +
            "it traded beyond the big day's $edge on ${r.beyondExtreme} and undid the whole move by its close on ${r.undone}. " +
            "The median next-day move was ${s2(r.medianNext * sd)} and its median range ${p2(r.medianNextRange)}, against ${p2(r.medianRange)} after every session. " +
            "After any whole session, ${m.label} closed $way the next day on ${share(r.baseSame, r.baseCount)}, so that is the share to set ${share(r.closedSame, r.count)} against.$small"
    }

    /** Today against the close before, when the phone has today's session and a whole one just before it. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime, pct: Double, side: Int?, isTradingDay: (LocalDate) -> Boolean): String? {
        val ss = MarketStory.sessions(bars)
        val t = ss.lastOrNull { it.day == today }?.takeIf { it.bars.isNotEmpty() } ?: return null
        val before = ss.lastOrNull { it.day.isBefore(today) }
            ?.takeIf { Comebacks.whole(it) && it.close > 0 && Comebacks.follows(it.day, today, isTradingDay) } ?: return null
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val move = (t.close - before.close) / before.close * 100
        // "Ended" only for a whole session; candles that stop early say when they stop.
        val last = t.bars.last().t.toLocalTime()
        val where = when {
            live -> "is ${s2(move)} on ${date(before.day)}'s close so far"
            Comebacks.whole(t) -> "ended ${s2(move)} on ${date(before.day)}'s close"
            else -> "was ${s2(move)} on ${date(before.day)}'s close at ${"%02d:%02d".format(Locale.ENGLISH, last.hour, last.minute)}"
        }
        val sized = if (abs(move) >= pct && (side == null || move * side > 0)) ", a move of the size asked" else ""
        return "Today ${m.label} $where$sized."
    }
}
