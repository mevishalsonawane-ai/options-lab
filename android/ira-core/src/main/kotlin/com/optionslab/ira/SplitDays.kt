package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Two indices on opposite sides of the day (market intelligence, round 23): "how often do Nifty and BankNifty close
 * opposite ways?", "Nifty BankNifty divergence record", "what happens the day after Nifty and BankNifty split?", "how often
 * does BankNifty go the other way from Nifty?", "nifty aur banknifty kitni baar ulte chalte hain". From the whole sessions
 * of 1-minute candles on the phone: each day both indices have a whole session with the previous one beside it, each
 * index's close against its own previous close; the days one rose and the other fell (each by [FLAT_PCT] or more) counted,
 * which way round, how far apart they ended; and what the next whole session did after those days - how often the two
 * then moved the same way, against how often they did on every day (so a next day that only looks like any other is not
 * passed off as more), and which of the two the next day's move sided with. Today's own close-to-close so far set beside.
 * A record of past days on this phone, said with its counts; never a forecast or advice, nothing acts. Whether the two
 * move together today stays [Together]'s, the leader over a stretch [Breadth]'s, today against yesterday [DayCompare]'s. Pure.
 */
object SplitDays {
    /** What was asked: the two indices, in the order said (Nifty first when only one other is named). */
    data class Q(val a: Market, val b: Market)

    /** One day both indices closed with their previous close beside them: each one's close-to-close change (%). */
    data class Day(val day: LocalDate, val aPct: Double, val bPct: Double) {
        private fun side(x: Double) = if (abs(x) < FLAT_PCT) 0 else if (x > 0) 1 else -1
        val aSide: Int get() = side(aPct)
        val bSide: Int get() = side(bPct)
        /** One up and the other down, each by [FLAT_PCT] or more. */
        val split: Boolean get() = aSide != 0 && bSide != 0 && aSide != bSide
        /** Both the same way, each by [FLAT_PCT] or more. */
        val same: Boolean get() = aSide != 0 && aSide == bSide
    }

    /** A change from the previous close under this (%) is flat: neither way. */
    const val FLAT_PCT = 0.05
    /** Fewer days with both closes known than this: too few to say anything. */
    const val MIN_DAYS = 20
    /** Fewer split days with a next day than this: too few to say what followed them. */
    const val MIN_SPLITS = 5
    /** At most this many of the newest days are read. */
    const val MAX_DAYS = 250
    /** Sessions count as one after the other only when at most this many days apart (a weekend and a holiday). */
    const val MAX_DAYS_APART = 4L
    private val FIRST_BY: LocalTime = LocalTime.of(9, 20)
    private val LAST_FROM: LocalTime = LocalTime.of(15, 25)
    val CLOSE: LocalTime = LocalTime.of(15, 30)

    const val NOTE = "A record of past days on this phone, Boss, not a forecast."
    const val NOT_HERE = "I keep the split-day record for Nifty, BankNifty, FinNifty and Sensex only, Boss: gold trades on its own clock, and India VIX is not an index that rises with the market."

    private val INDICES = listOf(Market.NIFTY, Market.BANKNIFTY, Market.FINNIFTY, Market.SENSEX)

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", " ").replace(rx("[^a-z0-9 ]"), " ")
        .replace(rx("\\s+"), " ").trim() + " "
    private fun sp2(x: Double) = (if (x >= 0) "+" else "-") + "%.2f%%".format(Locale.ENGLISH, abs(x))
    private fun pp(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun share(k: Int, of: Int) = "%d%%".format(Locale.ENGLISH, Math.round(k * 100.0 / of))
    private fun date(d: LocalDate) = "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun plural(k: Int, w: String) = if (k == 1) w else w + "s"

    // ---- the questions --------------------------------------------------------------------------------------------

    /** The two going opposite ways, named. */
    private val SPLIT = Regex(" (opposite ways|opposite way|opposite directions|opposite direction|opposite sides|the other way|other way round|" +
        "different ways|different directions|diverge|diverges|diverged|diverging|divergence|divergences|split|splits|split up|" +
        "one up and the other down|one up one down|one green and the other red|one green one red|one red one green|" +
        "ulta|ulte|ulti|ulat|alag alag disha|alag disha|alag alag direction|opposite disha) ")
    /** A record asked of, or what followed. */
    private val RECORD = Regex(" (how often|how many times|how many days|how frequently|how common|how rare|what share|what percentage|usually|normally|typically|" +
        "generally|tend to|tends to|on average|record|records|stats|statistics|history|historically|next day|next session|day after|the day after|following day|" +
        "what happens after|what happened after|what follows|kitni baar|kitne din|aksar|agle din|ke baad|" +
        // Round 16: "nifty banknifty ulta kab chalte hain"
        "kab chalte|kab chalti|kab jaate|kab jate|kab hote) ")
    /** Round 16: one index up and the other down said with both named ("nifty rise and banknifty fall", "nifty upar banknifty neeche"). */
    private val CROSS = Regex(" (rise|rises|rose|go up|goes up|went up|up|gain|gains|gained|green|upar|oopar) (and |aur |but |while )?(nifty|banknifty|bank nifty|finnifty|fin nifty|sensex) " +
        "(fall|falls|fell|go down|goes down|went down|down|lose|loses|lost|red|neeche|niche|gira|girta) | (fall|falls|fell|go down|goes down|went down|down|lose|loses|lost|red|neeche|niche) " +
        "(and |aur |but |while )?(nifty|banknifty|bank nifty|finnifty|fin nifty|sensex) (rise|rises|rose|go up|goes up|went up|up|gain|gains|gained|green|upar|oopar) ")
    /** The record named outright ("divergence record", "split days"). */
    private val NAME = Regex(" (divergence|split|splits|split day|split days|opposite day|opposite days) (record|records|stats|statistics|history|days) | (split days|split day record) ")
    /** Indices as a kind ("do the indices diverge often"). */
    private val INDICES_WORD = Regex(" (indices|indexes|the two indices|both indices|the two) ")
    // Another reading of divergence (indicators, the chain), a forecast or advice, Boss's own book, a what-if, one day alone,
    // a span of the calendar, the last time (MarketMemory), a meaning, a gap.
    private val NOT = Regex(" (rsi|macd|indicator|indicators|oscillator|oi|open interest|pcr|put call|chain|volume|price action|vix|" +
        "will|would|going to|gonna|tomorrow|predict|prediction|forecast|should|shall|buy|sell|enter|exit|trade|trades|trading|hedge|pair trade|" +
        "i|me|my|mine|we|our|what if|suppose|imagine|scenario|agar|last time|when did|last|mean|means|meaning|define|strategy|backtest|" +
        "gap|gaps|this week|this month|this year|last week|last month|stronger|weaker|leading|lagging|ratio|relative strength) ")

    /** What was asked, or null. A record of past split days only: never a forecast, advice, Boss's own book or bots. */
    fun asked(text: String): Q? {
        val t = norm(text)
        val named = Market.mentioned(text)
        val cross = CROSS.containsMatchIn(t) && named.filter { it in INDICES }.distinct().size >= 2
        if (!(SPLIT.containsMatchIn(t) || cross) || NOT.containsMatchIn(t)) return null
        if (Market.GOLD in named) return null
        val idx = named.filter { it in INDICES }
        if (idx.isEmpty() && !INDICES_WORD.containsMatchIn(t)) return null
        if (!(NAME.containsMatchIn(t) || RECORD.containsMatchIn(t))) return null
        val p = pair(idx) ?: return null
        return Q(p.first, p.second)
    }

    /** The two indices in the app's own order (Nifty, BankNifty, FinNifty, Sensex): two named; one alone (not Nifty) set against Nifty; none, Nifty and BankNifty. */
    fun pair(named: List<Market>): kotlin.Pair<Market, Market>? {
        val idx = named.filter { it in INDICES }.distinct().sortedBy { INDICES.indexOf(it) }
        return when {
            idx.size >= 2 -> idx[0] to idx[1]
            idx.size == 1 && idx[0] == Market.NIFTY -> Market.NIFTY to Market.BANKNIFTY
            idx.size == 1 -> Market.NIFTY to idx[0]
            else -> Market.NIFTY to Market.BANKNIFTY
        }
    }

    /** The pair asked about from the markets the question named: null when gold or India VIX is named with fewer than two indices. */
    fun market(markets: List<Market>, q: Q): kotlin.Pair<Market, Market>? {
        val idx = markets.filter { it in INDICES }
        if (idx.size < 2 && markets.any { it == Market.GOLD || it == Market.VIX }) return null
        return if (idx.isEmpty()) q.a to q.b else pair(idx)
    }

    // ---- the record -----------------------------------------------------------------------------------------------

    private fun whole(s: MarketStory.Session) = s.bars.isNotEmpty() && !s.bars.first().t.toLocalTime().isAfter(FIRST_BY) && !s.bars.last().t.toLocalTime().isBefore(LAST_FROM)

    /** Each whole session's close by day, before [today]. */
    private fun closes(bars: List<Candle>, today: LocalDate): Map<LocalDate, Double> =
        MarketStory.sessions(bars).filter { it.day.isBefore(today) && whole(it) && it.close > 0 }.associate { it.day to it.close }

    /**
     * The past days before [today] both [a] and [b] have a whole session, each with the day before on the phone (both
     * whole, at most [MAX_DAYS_APART] days earlier) - newest [MAX_DAYS], oldest first.
     */
    fun past(a: List<Candle>, b: List<Candle>, today: LocalDate): List<Day> {
        val ca = closes(a, today); val cb = closes(b, today)
        val days = ca.keys.intersect(cb.keys).sorted()
        val out = ArrayList<Day>()
        for (i in 1 until days.size) {
            val prev = days[i - 1]; val d = days[i]
            if (ChronoUnit.DAYS.between(prev, d) > MAX_DAYS_APART) continue
            out += Day(d, (ca.getValue(d) - ca.getValue(prev)) / ca.getValue(prev) * 100, (cb.getValue(d) - cb.getValue(prev)) / cb.getValue(prev) * 100)
        }
        return out.takeLast(MAX_DAYS)
    }

    /** Today's change of each so far from its last whole session before today, or null when either is missing. */
    fun today(a: List<Candle>, b: List<Candle>, today: LocalDate): Day? {
        fun one(bars: List<Candle>): Double? {
            val last = bars.lastOrNull { it.t.toLocalDate() == today } ?: return null
            val prev = MarketStory.sessions(bars).lastOrNull { it.day.isBefore(today) && whole(it) }
                ?.takeIf { ChronoUnit.DAYS.between(it.day, today) <= MAX_DAYS_APART && it.close > 0 } ?: return null
            return (last.c - prev.close) / prev.close * 100
        }
        val x = one(a) ?: return null; val y = one(b) ?: return null
        return Day(today, x, y)
    }

    /** [q] answered for the pair [a] and [b] from their 1-minute candles over several days, at [now] on [today]. */
    fun answer(a: Market, b: Market, aBars: List<Candle>, bBars: List<Candle>, today: LocalDate, now: LocalDateTime): String {
        if (a == b) return "Name two different indices, Boss - Nifty and BankNifty, say - and I'll count the days they went opposite ways."
        val days = past(aBars, bBars, today)
        val pair = "${a.label} and ${b.label}"
        if (days.size < MIN_DAYS)
            return "I have only ${days.size} ${plural(days.size, "day")} on the phone with both $pair's whole sessions and the day before beside them, Boss - too few to say how often they go opposite ways (I need $MIN_DAYS)."
        val lines = ArrayList<String>()
        val splits = days.filter { it.split }
        val k = splits.size
        val aUp = splits.count { it.aSide > 0 }
        val same = days.count { it.same }
        val rest = days.size - k - same
        lines += "Over the last ${days.size} days on this phone with both whole sessions, $pair ended on opposite sides of their previous closes on $k (${share(k, days.size)}); " +
            "they ended the same way on $same (${share(same, days.size)})" +
            (if (rest > 0) ", and on the other $rest at least one was about flat (under ${pp(FLAT_PCT)}%)." else ".")
        if (k == 0) {
            lines += "Not once did one rise while the other fell, so there is nothing to say about what followed."
        } else {
            val gap = splits.map { abs(it.aPct - it.bPct) }.average()
            lines += "On $aUp of those ${a.label} rose while ${b.label} fell, on ${k - aUp} the other way round; on average they ended ${pp(gap)} percentage points apart."
            val last = splits.last()
            lines += "The last was ${date(last.day)}: ${a.label} ${sp2(last.aPct)}, ${b.label} ${sp2(last.bPct)}."
            lines += after(days, splits)
        }
        today(aBars, bBars, today)?.let { d ->
            val live = now.toLocalDate() == today && now.toLocalTime().isBefore(CLOSE)
            val so = if (live) "so far " else ""
            val read = when {
                d.split -> "on opposite sides"
                d.same -> "the same way"
                else -> "with at least one about flat"
            }
            lines += "Today ${so}${a.label} is ${sp2(d.aPct)} and ${b.label} ${sp2(d.bPct)} from their previous closes: $read."
        }
        lines += NOTE
        return lines.joinToString(" ")
    }

    /** What the next day on the phone did after the split days, set against every day. */
    private fun after(days: List<Day>, splits: List<Day>): String {
        val byDay = days.associateBy { it.day }
        val sorted = days.map { it.day }
        // The next day only when it follows straight on (within MAX_DAYS_APART), so a gap in the phone's data is not bridged.
        fun next(d: LocalDate): Day? = sorted.firstOrNull { it.isAfter(d) }?.takeIf { ChronoUnit.DAYS.between(d, it) <= MAX_DAYS_APART }?.let { byDay[it] }
        val followed = splits.mapNotNull { s -> next(s.day)?.let { s to it } }
        val n = followed.size
        if (n < MIN_SPLITS)
            return "Only $n of them ${if (n == 1) "has" else "have"} the next day on the phone - too few to say what followed (I need $MIN_SPLITS)."
        val sameNext = followed.count { it.second.same }
        val splitNext = followed.count { it.second.split }
        val allSame = share(days.count { it.same }, days.size)
        val allSplit = share(days.count { it.split }, days.size)
        // Moving together the next day: with the one that had risen (both up) or with the one that had fallen (both down).
        val bothUp = followed.count { (_, x) -> x.same && x.aSide > 0 }
        return "The next day, the two moved the same way on $sameNext of $n (${share(sameNext, n)}, against $allSame of all days) and split again on $splitNext " +
            "(${share(splitNext, n)}, against $allSplit of all days). Of the $sameNext next days they moved together, both rose on $bothUp and both fell on ${sameNext - bothUp}."
    }
}
