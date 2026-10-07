package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Liquidity 15+5 by day and time (Boss, 07 Oct 2026): "which day does liquidity do best", "liquidity on expiry days",
 * "liquidity by weekday", "what time of entry works best for liquidity", "liquidity kis din achha karta hai". From the
 * arm's own book of closed paper trades ([LiquidityRecord.rows]), net after charges PER LOT, split three ways:
 *
 *   weekday   the entry's day of the week
 *   expiry    on an expiry day of its index or not - read from the option expiries in its own book (the expiry in each
 *             paper symbol, DDMMMYY): a trade's day is an expiry day when an option of the same index in the book expired
 *             that day. The arm buys the next expiry, never the one expiring that day, so its own contract alone never
 *             tells; an expiry with no trade before it in the book is missed - said plainly. No readable expiry at all: the
 *             split is skipped and said so.
 *   time      the entry time in [BUCKET_MIN]-minute buckets (09:30-10:00...)
 *
 * Each bucket: its trades, win rate and net a lot; fewer than [MIN_BUCKET] trades is "too few to tell". One honest takeaway
 * (the buckets with enough trades, best and least a trade - a pattern, not proof). Boss's own trades by time of day are
 * other answers (Journal's); this is the arm's paper book alone.
 *
 * Read only: nothing is armed, stopped, placed, closed or changed from it; the arm's days, hours, rules, lots and switch
 * stay Boss's call, and it never says to change them. Pure: no clock, no storage - the trades are handed in.
 */
object LiquidityWhen {
    /** What was asked: every split, or one of them. */
    enum class Focus { ALL, WEEKDAY, EXPIRY, TIME }

    /** A bucket with fewer trades than this is "too few to tell". */
    const val MIN_BUCKET = 10
    /** The entry-time buckets' width, in minutes. */
    const val BUCKET_MIN = 30
    /** An expiry read from a symbol further than this many days past the trade is not taken as its option's expiry. */
    const val MAX_DAYS_TO_EXPIRY = 100L

    const val LOCKED = LiquidityRecord.LOCKED
    const val END = "From the arm's own paper book, per lot - facts, not advice; nothing about the arm changes from this: its days, " +
        "hours, rules, lots and switch stay as they are."

    // ---- the question --------------------------------------------------------------------------------------------------

    private fun norm(text: String) = Spaced.joined(text)

    private val LIQ = Regex(" (liquidity|liquiditys|liqudity|liquidty) ")
    /** A day of the week asked: which day, by weekday, Mondays (plural: the record of them), in Hinglish "kis din". */
    private val WEEKDAY = Regex(" (weekday|weekdays|week day|week days|day of the week|days of the week|day of week|which day|which days|what day|" +
        "what days|best day|best days|worst day|worst days|day wise|weekday wise|mondays|tuesdays|wednesdays|thursdays|fridays|kis din|kaun se din|kaunsa din|" +
        "kaun sa din|konsa din|konse din|kon sa din) ")
    /** Expiry days asked: on expiry, expiry days against the rest, in Hinglish "expiry ke din". */
    private val EXPIRY = Regex(" (expiry day|expiry days|expiry session|expiry sessions|on expiry|on expiries|non expiry|expiry vs|" +
        "expiry versus|expiry ke din|expiry wale din|expiry waale din|expiry din|expiry pe|expiry par) ")
    /** The entry's time of day asked: the time of entry, which hour or half hour, in Hinglish "kis time". */
    private val TIME = Regex(" (time of day|time of the day|times of day|time of entry|time of its entry|entry time|entry times|entry hour|" +
        "entry hours|which time|what time|which hour|what hour|which half hour|half hour|half hours|half hourly|time bucket|time buckets|" +
        "time slot|time slots|time wise|hour wise|by hour|by the hour|hourly|best time|worst time|best hour|worst hour|" +
        "morning|mornings|afternoon|afternoons|morning or afternoon|morning vs afternoon|morning versus afternoon|kis time|kis waqt|kis samay|kitne baje) ")
    /** How it did there: best or worst, works, a record or a split, in Hinglish "achha karta", "kaisa". */
    private val HOW = Regex(" (best|worst|better|worse|well|good|works|work|working|perform|performs|performed|performance|win rate|" +
        "record|stats|breakdown|split|compare|comparison|vs|versus|how|money|profit|profitable|made|makes|earns|achha|accha|acha|acchha|" +
        "lose|loses|losing|loss|losses|kaisa|kaise|kamata|kamaata) ")
    /** A split named as one: by weekday, by entry time, weekday wise. */
    private val BY = Regex(" ((by|per|across) (weekday|weekdays|week day|day of the week|days of the week|day of week|entry time|entry times|" +
        "time of day|time of entry|time|hour|hours|half hour|expiry|expiry day|expiry days)|(weekday|day|time|hour|expiry) wise|" +
        "(weekday|time|expiry) (breakdown|split)) ")
    /** Day and time named together: every split. */
    private val BOTH = Regex(" (day and time|days and times|day and hour|day and entry time|din aur time|din aur waqt) ")
    /**
     * Not this: a change or a switch (never from here), a single or coming day, today alone, why, its rules (when it enters
     * or stops, which expiry or strike it buys), its levels or lots, its hold time, drawdown or streak, the backtest or the
     * shadows, a definition, Boss's own trades, another arm, or an index named.
     */
    private val NOT = Regex(" (should|shall|set|change|changes|changing|switch|switched|turn on|turn off|disable|enable|karo|kar do|kardo|band karo|" +
        "stop|stops|stopping|today|todays|aaj|tomorrow|kal|next|will|would|why|kyun|kyon|kyu|was|were|yesterday|last|this|" +
        "backtest|back test|backtested|research|shadow|shadows|candidate|candidates|what is a|define|meaning|mean by|" +
        "hero|solo|orb|gold|pine|my|mera|meri|mere|i|main|mai|level|levels|pool|pools|lot|lots|size|" +
        "hold|holds|held|how long|drawdown|streak|which expiry|what expiry|buy|buys|bought|contract|contracts|strike|strikes|" +
        "enter|enters|entering|start|starts|when|nifty|banknifty|bank nifty|finnifty|fin nifty|" +
        "(does|do|can) (liquidity|it) (trade|take) (on|during|at)) ")
    /** A second question said after it: left to the splitter, each answered on its own. */
    private val AND = Regex(" (and|aur|also|then|phir) (what|whats|how|hows|is|are|when|why|tell|show|give|check|kya|kitna|nifty|banknifty|my|mera|meri|mere) ")

    /** Is Liquidity 15+5 by day and time asked? The split asked (every one when more than one is named), or null when not. */
    fun asked(text: String): Focus? = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Focus?>(64)

    private fun askedFresh(text: String): Focus? {
        val t = norm(text)
        if (!LIQ.containsMatchIn(t) || NOT.containsMatchIn(t) || AND.containsMatchIn(t)) return null
        val how = HOW.containsMatchIn(t) || BY.containsMatchIn(t)
        if (BOTH.containsMatchIn(t)) return Focus.ALL
        val day = WEEKDAY.containsMatchIn(t) && how
        val time = TIME.containsMatchIn(t) && how
        val expiry = EXPIRY.containsMatchIn(t)
        val named = listOf(day to Focus.WEEKDAY, expiry to Focus.EXPIRY, time to Focus.TIME).filter { it.first }
        return when (named.size) {
            0 -> null
            1 -> named[0].second
            else -> Focus.ALL
        }
    }

    // ---- the figures ---------------------------------------------------------------------------------------------------

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    /** The paper symbol's expiry: the index, then DDMMMYY, then the strike ("BANKNIFTY28OCT2656000CE"). */
    private val SYMBOL = Regex("^[A-Z]+?(\\d{2})([A-Z]{3})(\\d{2})\\d")

    /**
     * The option's expiry from the trade's own symbol, or null when it is not readable there: no DDMMMYY, not a date, or not
     * between the trade's day and [MAX_DAYS_TO_EXPIRY] days after it (a symbol of another format read wrongly).
     */
    fun expiryOf(r: LiquidityRecord.Row): LocalDate? {
        val m = SYMBOL.find(r.trade.symbol.uppercase(Locale.ENGLISH)) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2]) + 1
        if (month == 0) return null
        val d = runCatching { LocalDate.of(2000 + m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }.getOrNull() ?: return null
        val ahead = ChronoUnit.DAYS.between(r.day, d)
        return d.takeIf { ahead in 0..MAX_DAYS_TO_EXPIRY }
    }

    /** One bucket: its [label], [n] trades, [wins], [perLot] net a lot summed. */
    data class Cell(val label: String, val n: Int, val wins: Int, val perLot: Double) {
        val winRate: Double get() = if (n == 0) 0.0 else wins.toDouble() / n
        /** Net a lot a trade. */
        val avg: Double get() = if (n == 0) 0.0 else perLot / n
        val enough: Boolean get() = n >= MIN_BUCKET
    }

    private fun cell(label: String, rows: List<LiquidityRecord.Row>) = Cell(label, rows.size, rows.count { it.win }, rows.sumOf { it.perLot })

    private fun dayName(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    /** By the entry's weekday, Monday first; only the days with trades. */
    fun byWeekday(rows: List<LiquidityRecord.Row>): List<Cell> =
        rows.groupBy { it.day.dayOfWeek }.toSortedMap().map { (d, xs) -> cell(dayName(d), xs) }

    /** The start of [r]'s entry-time bucket, in minutes from midnight. */
    fun bucketOf(r: LiquidityRecord.Row): Int = r.trade.entryTime.let { it.hour * 60 + it.minute } / BUCKET_MIN * BUCKET_MIN

    private fun hm(m: Int) = String.format(Locale.ENGLISH, "%02d:%02d", m / 60, m % 60)

    /** By the entry's [BUCKET_MIN]-minute bucket, earliest first; only the buckets with trades. */
    fun byTime(rows: List<LiquidityRecord.Row>): List<Cell> =
        rows.groupBy { bucketOf(it) }.toSortedMap().map { (b, xs) -> cell("${hm(b)}-${hm(b + BUCKET_MIN)}", xs) }

    /**
     * The expiry split: [readable] trades with their option's expiry in the symbol (0: skipped), the [expiry] and [other]
     * days' cells among every trade, by whether its day was an expiry of its index seen in the book.
     */
    data class Expiry(val readable: Int, val expiry: Cell, val other: Cell)

    fun byExpiry(rows: List<LiquidityRecord.Row>): Expiry {
        val seen = rows.mapNotNull { r -> expiryOf(r)?.let { r.index to it } }
        val days = seen.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        val (on, off) = rows.partition { r -> days[r.index]?.contains(r.day) == true }
        return Expiry(seen.size, cell("expiry days", on), cell("other days", off))
    }

    // ---- words ---------------------------------------------------------------------------------------------------------

    private fun rs(x: Double) = LiquidityRecord.rs(x)
    private fun s(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun pct(x: Double) = "${(100 * x).roundToInt()}%"

    /** One bucket in words: its trades, win rate and net a lot, or "too few to tell" under [MIN_BUCKET]. */
    fun said(c: Cell): String = "${c.label} ${s(c.n, "trade")}, ${pct(c.winRate)} won, ${rs(c.perLot)} a lot" +
        if (c.enough) "" else " (too few to tell)"

    /**
     * The plain takeaway over [cells] (one split): the buckets with [MIN_BUCKET] trades or more, the one that made the
     * most a trade and the least - a pattern, not proof; under two such buckets, too few to tell. Never a change.
     */
    fun takeaway(cells: List<Cell>): String = standout(cells) ?: TOO_FEW

    private const val TOO_FEW = "Too few trades in its buckets to tell which day or time suits it - it takes $MIN_BUCKET trades in a bucket " +
        "before I set one against another."

    /**
     * The takeaway over the splits shown, each split read on its own (a weekday is never set against "other days" or an
     * hour: the same trades sit in every split); too few to tell when no split has two buckets with enough trades.
     */
    fun takeawayOver(splits: List<List<Cell>>): String =
        splits.mapNotNull { standout(it) }.ifEmpty { listOf(TOO_FEW) }.joinToString(" ")

    /** [takeaway] over one split, or null under two buckets with [MIN_BUCKET] trades or more. */
    private fun standout(cells: List<Cell>): String? {
        val enough = cells.filter { it.enough }
        if (enough.size < 2) return null
        val best = enough.maxByOrNull { it.avg }!!; val least = enough.minByOrNull { it.avg }!!
        if (best.avg - least.avg < 1.0) return "Among the buckets with $MIN_BUCKET trades or more, none stands apart - about the same a trade."
        return "Among the buckets with $MIN_BUCKET trades or more, ${best.label} made the most a trade (${rs(best.avg)} a lot) and ${least.label} " +
            "the least (${rs(least.avg)} a lot) - a pattern in a paper book, not proof: a few trades can swing a bucket."
    }

    /** The answer for [focus] from the arm's closed paper trades [rows] (oldest exit first). */
    fun answer(focus: Focus, rows: List<LiquidityRecord.Row>): String {
        val mine = rows.filter { it.perLot.isFinite() }
        if (mine.isEmpty()) return "Liquidity 15+5 has no closed paper trade in its book yet, Boss - nothing to split by day or time.\n$END"
        val out = mutableListOf<String>()
        out += "Boss, Liquidity 15+5's ${s(mine.size, "closed paper trade")} by " + when (focus) {
            Focus.WEEKDAY -> "weekday"
            Focus.EXPIRY -> "expiry day"
            Focus.TIME -> "entry time"
            Focus.ALL -> "day and time"
        } + ", net a lot after charges:"
        val shown = mutableListOf<List<Cell>>()
        if (focus == Focus.ALL || focus == Focus.WEEKDAY) {
            val cells = byWeekday(mine); shown += listOf(cells)
            out += "By weekday: " + cells.joinToString("; ") { said(it) } + "."
        }
        if (focus == Focus.ALL || focus == Focus.EXPIRY) {
            val e = byExpiry(mine)
            out += if (e.readable == 0) "Expiry days: its book does not carry a readable option expiry for these trades, so I can't tell its " +
                "expiry days from the rest - that split is skipped."
            else {
                val cells = listOf(e.expiry, e.other).filter { it.n > 0 }; shown += listOf(cells)
                val none = if (e.expiry.n == 0) " None of its trades fell on an expiry day its book shows." else ""
                "Expiry days against the rest: " + cells.joinToString("; ") { said(it) } + ".$none (Expiry days read from the expiries of " +
                    "the options in its own book - it buys the next expiry, never the one expiring that day - so an expiry with no trade " +
                    "before it in the book is missed.)"
            }
        }
        if (focus == Focus.ALL || focus == Focus.TIME) {
            val cells = byTime(mine); shown += listOf(cells)
            out += "By entry time ($BUCKET_MIN-minute buckets): " + cells.joinToString("; ") { said(it) } + "."
        }
        if (shown.isNotEmpty()) out += takeawayOver(shown)
        out += END
        return out.joinToString("\n")
    }
}
