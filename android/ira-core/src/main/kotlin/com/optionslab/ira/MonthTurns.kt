package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * The month-start and month-end record (market intelligence, round 25): "how does Nifty do at the start of the month?",
 * "is there a turn of the month effect?", "how are the last few days of the month for BankNifty?", "month end record",
 * "mahine ki shuruaat mein nifty kaisa chalta hai". From the whole sessions of 1-minute candles on the phone: the first
 * [EDGE] trading days of each month and the last [EDGE] against the rest - each group's median range, median move either
 * way, how many closed up and the average day's move - and each month's first and last [EDGE] days taken together (how
 * many months they rose in, the median, the widest either way). A day counts as a month's first or last only when the
 * phone also has the session just across the month's turn (within [MAX_DAYS_APART] days), so a gap in the record is never
 * passed off as the month's edge. Beside today's place when it is one of a month's first days. A record of past days on
 * this phone, said with its counts and plainly when there are too few; never a forecast or advice; nothing acts. A month's
 * own move stays [PeriodMove]'s, Boss's month [MonthReview]'s, a weekday's record [Weekdays]'. Pure.
 */
object MonthTurns {
    /** Which edge of the month was asked: its start, its end, or both (null). */
    enum class Part { START, END }
    data class Q(val part: Part?)

    /**
     * One whole past session: its range (% of the open), its move from the previous close (%, null when that is not on the
     * phone), and its place in the month - [start] 1..[EDGE] for a month's first days, [end] 1..[EDGE] for its last (1 =
     * the month's last day), [rest] for a day well inside the month.
     */
    data class Day(val day: LocalDate, val rangePct: Double, val movePct: Double?, val start: Int?, val end: Int?, val rest: Boolean)

    /** One month's first or last [EDGE] days together: from the close before them to the last one's close (%). */
    data class Stretch(val month: YearMonth, val pct: Double)

    /** A month's first and last this many trading days. */
    const val EDGE = 3
    /** Fewer whole sessions than this: too few to say anything. */
    const val MIN_SESSIONS = 40
    /** Fewer months with a known start (or end) than this: too few to say that edge's record. */
    const val MIN_MONTHS = 3
    /** Fewer months than this: said as a small record. */
    const val FEW_MONTHS = 8
    /** At most this many of the newest sessions are read. */
    const val MAX_SESSIONS = 250
    /** The session across a month's turn counts only when at most this many days away (a weekend and a holiday). */
    const val MAX_DAYS_APART = 4L
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)
    private val CLOSE: LocalTime = LocalTime.of(15, 30)
    private val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the month-start and month-end record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades round the clock and India VIX is not traded."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "
    private fun p2(x: Double) = "%.2f%%".format(Locale.ENGLISH, x)
    private fun signed(x: Double) = "%+.2f%%".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun monthName(m: YearMonth) = "${m.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${m.year}"
    private fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

    // ---- the questions --------------------------------------------------------------------------------------------

    private val START = Regex(" (start|starts|beginning|opening|first (few |3 |three |2 |two )?(trading )?(days|day|sessions|session)) of (the |a |each |every |a new |the new )?(month|months) |" +
        " month (start|starts|beginning|opening)s? | month s (start|beginning|first (few )?(days|sessions)) | start of month | new month s first days |" +
        " (mahine|mahina|month) (ki|ke|ka) (shuruaat|shuruat|shuru|start|starting|pehle|pahle|first) ")
    private val END = Regex(" (end|ends|close|closing|last (few |3 |three |2 |two )?(trading )?(days|day|sessions|session)) of (the |a |each |every )?(month|months) |" +
        " month (end|ends|ending|close|closing) | month s (end|close|last (few )?(days|sessions)) | end of month |" +
        " (mahine|mahina|month) (ki|ke|ka) (aakhri|akhri|aakhir|end|last|ant|khatam) ")
    private val TURN = Regex(" turn of (the )?(month|months) | month turn | month turns | turn of month ")
    /** A record asked of: how it goes, usually, an effect or a lean, against the rest. */
    private val RECORD = Regex(" (how|usually|normally|typically|generally|tend to|tends to|on average|record|records|stats|statistics|history|" +
        "historically|pattern|patterns|effect|seasonality|seasonal|bias|edge|kaisa|kaisi|kaise|aksar|rally|rallies|rise|rises|fall|falls|up|down|" +
        "strong|stronger|weak|weaker|volatile|better|worse|compare|compared|versus|vs|than|rest of the month|perform|performs|do|does|go|goes|chalta|rehta|hota|" +
        // Round 18: "is month end bullish", "what happens to nifty at month end", "upar jata hai kya", "girta hai"
        "bullish|bearish|happen|happens|upar|neeche|niche|girta|girti|chadhta|badhta|jata|jaata|karta) ")
    // A forecast or advice, Boss's own book or month, one month's move, today, the expiry, the calendar, a meaning.
    private val NOT = Regex(" (will|would|going to|gonna|tomorrow|kal|predict|prediction|forecast|outlook|should|shall|buy|sell|enter|exit|trade|trades|" +
        "i|me|my|mine|we|our|what if|suppose|agar|today|todays|aaj|now|abhi|right now|this month|last month|next month|previous month|coming month|" +
        "is mahine|iss mahine|pichle mahine|agle mahine|review|report|recap|summary|pnl|salary|sip|expiry|expiries|holiday|holidays|open|" +
        "mean|means|meaning|define|bot|bots|algo|strategy|backtest|news|gold|vix) ")

    private val INDEX_TRADES = Regex(" (nifty|banknifty|finnifty|sensex|market|index) (usually |normally |typically |generally )?trade ")

    /** What was asked, or null: the record of past month edges only, never a forecast, advice or one month's move. */
    fun asked(text: String): Q? {
        // "How does Nifty usually trade at month end": the index trading, never Boss's own trade (round 18).
        val t = INDEX_TRADES.replace(norm(text), " $1 $2go ")
        if (NOT.containsMatchIn(t) || !RECORD.containsMatchIn(t)) return null
        val turn = TURN.containsMatchIn(t)
        val s = START.containsMatchIn(t); val e = END.containsMatchIn(t)
        if (!turn && !s && !e) return null
        return Q(if (turn || (s && e)) null else if (s) Part.START else Part.END)
    }

    /** The index asked about: the first index named, Nifty when none is; null when only gold or India VIX is. */
    fun market(markets: List<Market>): Market? {
        val idx = markets.firstOrNull { it in INDICES }
        if (idx == null && markets.any { it == Market.GOLD || it == Market.VIX }) return null
        return idx ?: Market.NIFTY
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    private fun whole(s: MarketStory.Session) = s.bars.isNotEmpty() && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) &&
        !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    private fun near(a: LocalDate, b: LocalDate) = abs(ChronoUnit.DAYS.between(a, b)) <= MAX_DAYS_APART

    /** A session's place in its month: [start] / [end] (null where not one or the edge is not known), well [inside] it. */
    private data class Place(val start: Int?, val end: Int?, val inside: Boolean)

    /**
     * Each session in [all] (oldest first) placed in its month. Well inside: neither edge, and each edge either known or a
     * week of calendar days away (so the record's own gaps never put a month's first or last days among the rest).
     */
    private fun places(all: List<MarketStory.Session>): List<Place> {
        val out = ArrayList<Place>()
        var i = 0
        while (i < all.size) {
            val ym = YearMonth.from(all[i].day)
            var j = i
            while (j + 1 < all.size && YearMonth.from(all[j + 1].day) == ym) j++
            val startKnown = i > 0 && near(all[i - 1].day, all[i].day)
            val endKnown = j + 1 < all.size && near(all[j].day, all[j + 1].day)
            val n = j - i + 1
            for (k in 0 until n) {
                val start = if (startKnown && k < EDGE) k + 1 else null
                val end = if (endKnown && n - k <= EDGE && start == null) n - k else null
                val d = all[i + k].day
                val inside = start == null && end == null && (startKnown || d.dayOfMonth > 7) && (endKnown || d.lengthOfMonth() - d.dayOfMonth >= 7)
                out += Place(start, end, inside)
            }
            i = j + 1
        }
        return out
    }

    /** The whole sessions before [today] in [bars], newest [MAX_SESSIONS], oldest first, each placed in its month. */
    fun past(bars: List<Candle>, today: LocalDate): List<Day> {
        val all = MarketStory.sessions(bars).filter { it.bars.isNotEmpty() && !it.day.isAfter(today) }
        val pl = places(all)
        val out = ArrayList<Day>()
        for ((i, s) in all.withIndex()) {
            if (!s.day.isBefore(today) || !whole(s) || s.open <= 0) continue
            val prev = all.getOrNull(i - 1)?.takeIf { near(it.day, s.day) && whole(it) && it.close > 0 }
            val place = pl[i]
            out += Day(s.day, s.range / s.open * 100, prev?.let { (s.close - it.close) / it.close * 100 }, place.start, place.end, place.inside)
        }
        return out.takeLast(MAX_SESSIONS)
    }

    /** Each month's first (or last) [EDGE] days together, where all of them and the close before them are on the phone. */
    private fun stretches(days: List<Day>, start: Boolean): List<Stretch> {
        val out = ArrayList<Stretch>()
        val edge = days.filter { (if (start) it.start else it.end) != null }.groupBy { YearMonth.from(it.day) }
        for ((ym, ds) in edge) {
            if (ds.size != EDGE || ds.any { it.movePct == null }) continue
            // Consecutive sessions each with its move from the one before: chain the moves.
            val f = ds.sortedBy { it.day }.fold(1.0) { acc, d -> acc * (1 + d.movePct!! / 100) }
            out += Stretch(ym, (f - 1) * 100)
        }
        return out.sortedBy { it.month }
    }

    private fun median(xs: List<Double>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    /** One group's figures: "median range 0.82%, median move 0.45% either way, closed up on 7 of 12 (58%), average day +0.05%". */
    private fun figures(ds: List<Day>): String {
        val bits = ArrayList<String>()
        bits += "median range ${p2(median(ds.map { it.rangePct }))}"
        val moves = ds.mapNotNull { it.movePct }
        if (moves.isNotEmpty()) {
            val up = moves.count { it > 0 }
            bits += "median move ${p2(median(moves.map { abs(it) }))} either way"
            bits += "closed up on $up of ${moves.size} (${share(up, moves.size)})"
            bits += "average day ${signed(moves.average())}"
        }
        return bits.joinToString(", ")
    }

    private fun edgeLines(days: List<Day>, start: Boolean): List<String> {
        val ds = days.filter { (if (start) it.start else it.end) != null }
        val months = ds.map { YearMonth.from(it.day) }.distinct().size
        val what = if (start) "first $EDGE trading days" else "last $EDGE trading days"
        if (months < MIN_MONTHS)
            return listOf("I can place the $what of only $months month${if (months == 1) "" else "s"} on the phone - too few to say their record (I need $MIN_MONTHS).")
        val out = ArrayList<String>()
        out += "A month's $what (${ds.size} days across $months months): ${figures(ds)}."
        val st = stretches(days, start)
        if (st.size >= MIN_MONTHS) {
            val up = st.count { it.pct > 0 }
            val hi = st.maxBy { it.pct }; val lo = st.minBy { it.pct }
            out += "Taken together they rose in $up of ${st.size} months (median ${signed(median(st.map { it.pct }))})" +
                (if (signed(hi.pct) == signed(lo.pct)) "." else "; the best was ${monthName(hi.month)} (${signed(hi.pct)}), the worst ${monthName(lo.month)} (${signed(lo.pct)}).")
        }
        return out
    }

    /** [q] answered for [m] from its 1-minute candles over several days, at [now] on [today]. */
    fun answer(q: Q, m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        val days = past(bars, today)
        if (days.size < MIN_SESSIONS)
            return "I have only ${days.size} whole ${m.label} session${if (days.size == 1) "" else "s"} on the phone, Boss - too few to say how the start and end of a month differ (I need $MIN_SESSIONS)."
        val lines = ArrayList<String>()
        lines += "Over the last ${days.size} whole ${m.label} sessions on this phone (${date(days.first().day)} to ${date(days.last().day)}):"
        if (q.part != Part.END) lines += edgeLines(days, start = true)
        if (q.part != Part.START) lines += edgeLines(days, start = false)
        val rest = days.filter { it.rest }
        if (rest.isNotEmpty()) lines += "The ${rest.size} days well inside a month: ${figures(rest)}."
        val months = days.map { YearMonth.from(it.day) }.distinct().size
        if (months < FEW_MONTHS) lines += "That is only $months months, so one big day moves these figures a lot."
        todayLine(m, bars, today, now)?.let { lines += it }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** Today's place when it is one of the month's first [EDGE] trading days, and its move so far. */
    private fun todayLine(m: Market, bars: List<Candle>, today: LocalDate, now: LocalDateTime): String? {
        val all = MarketStory.sessions(bars).filter { it.bars.isNotEmpty() && !it.day.isAfter(today) }
        val i = all.indexOfLast { it.day == today }
        if (i < 0) return null
        val start = places(all)[i].start ?: return null
        val prev = all.getOrNull(i - 1)?.takeIf { near(it.day, today) && whole(it) && it.close > 0 } ?: return null
        val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
        val mv = (all[i].close - prev.close) / prev.close * 100
        val month = today.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "Today is the ${ordinal(start)} trading day of $month on this record; ${m.label} ${if (live) "is" else "closed"} ${signed(mv)} from the last close" +
            (if (live) " so far - the session is still on." else ".")
    }
}
