package com.optionslab.ira

import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Liquidity 15+5's drawdown (Boss, 07 Oct 2026): "how deep has liquidity fallen from its best", "liquidity drawdown",
 * "liquidity's current drawdown", "liquidity's worst losing streak", "how long did liquidity take to recover", "liquidity
 * peak se kitna neeche hai". From the arm's own book of closed paper trades ([LiquidityRecord.rows], oldest exit first),
 * net after charges PER LOT - so a change of lots does not bend the curve - as a running total from 0:
 *
 *   peak       the best running total so far (0 before any gain), and the trade and day it was reached
 *   now        the running total less that peak: 0 (at its best) or negative, in rupees and as a share of the peak
 *   worst      the deepest fall from a peak ever, the peak and the low it fell to, and how many trades and days the climb
 *              back to that peak took (or that it has not climbed back yet)
 *   streak     the longest run of losing trades (net 0 or below, as the win rate) and what it cost, and the run now
 *   unusual?   today's fall set against the arm's own past falls (deeper than how many of them) and, for the losing
 *              run, its chance at the backtest's win rate ([LiquidityRecord.lossRunChance]) - with too few trades said plainly
 *
 * Read only: nothing is armed, stopped, placed, closed or changed from it; the arm's rules, lots and switch stay Boss's
 * call. Pure: no clock, no storage - the trades are handed in.
 */
object LiquidityDrawdown {
    /** Fewer trades than this (the forward check's own bar) and the history is said to be too short to call anything unusual. */
    const val MIN_TRADES = LiquidityRecord.MIN_TRADES

    const val LOCKED = LiquidityRecord.LOCKED
    const val END = "From the arm's own paper book, per lot - facts, not advice; nothing about the arm changes from this: its rules, lots " +
        "and switch stay as they are."

    // ---- the question --------------------------------------------------------------------------------------------------

    private fun norm(text: String) = Spaced.joined(text)

    private val LIQ = Regex(" (liquidity|liquiditys|liqudity|liquidty) ")
    /**
     * A fall from its best asked: a drawdown, fallen, down or how far from its best / peak / high, below or off its peak,
     * underwater, a recovery or a climb back to its best, its peak equity or equity curve, the worst or longest losing streak;
     * in Hinglish "peak se kitna neeche", "kitna gira". Not a bare "how deep" or "get back" (a zone's depth, a re-entry).
     */
    private val DD = Regex(" (drawdown|drawdowns|draw down|draw downs|drawn down|underwater|under water|peak equity|equity peak|" +
        "(fallen|fell|fall|falls|dropped|drop|down|slipped|off|below|under|away|far|lost) (from )?(its |the )?(best|peak|high|highs|top|high water mark)|" +
        "how far( [a-z]+){1,3} (from|below|off) (its |the )?(best|peak|high|top)|(at|near) (its |the )?(best|peak)|equity curve|" +
        "deepest (fall|dip|hole)|(worst|biggest|deepest|largest) (fall|dip|slump|hole)|" +
        "(recover|recovered|recovery|recovering)|(climb|climbed|bounce|bounced|get|got|come|came) back (to|up to) (its |the )?(best|peak|high|top|old high)|" +
        "(worst|longest|biggest|most) (losing|loss|losses) (streak|streaks|run|runs)|(worst|longest) (streak|run) of (losses|losing trades)|" +
        "most losses in a row|" +
        "(peak|best|high|top) se (kitna|kitni|kitne) (neeche|niche|neche|gira|giri|gire|door|dur)|kitna gira|kitni giri) ")
    /**
     * Not this: a change or a switch (never from here), Boss's own account, a limit or a kill switch (Headroom, Bot
     * settings), today alone, why, the backtest or the shadows, a definition, another arm, or a level of the index.
     */
    private val NOT = Regex(" (should|shall|set|change|changes|changing|switch|switched|turn on|turn off|disable|enable|karo|kar do|kardo|band karo|" +
        "limit|limits|kill switch|guard|breaker|today|todays|aaj|tomorrow|kal|why|kyun|kyon|kyu|will|would|" +
        "backtest|back test|backtested|shadow|shadows|candidate|candidates|what is a|define|meaning|mean by|hero|solo|orb|gold|pine|" +
        "my|mera|meri|mere|i|main|mai|nifty|banknifty|bank nifty|finnifty|midcpnifty|midcap|level|levels|pool|pools|zone|zones|sweep|sweeps|grab|vix|stock|stocks) ")
    /** A second question said after it: left to the splitter, each answered on its own. */
    private val AND = Regex(" (and|aur|also|then|phir) (what|whats|how|hows|is|are|when|why|tell|show|give|check|kya|kitna|nifty|banknifty|my|mera|meri|mere) ")

    /** Is Liquidity 15+5's fall from its best (its drawdown, recovery or worst losing run) asked? */
    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean {
        val t = norm(text)
        // Its whole book only: a span named ("this week", "last 10 trades") is the record's, which cuts by span - this does not.
        return LIQ.containsMatchIn(t) && DD.containsMatchIn(t) && !NOT.containsMatchIn(t) && !AND.containsMatchIn(t) &&
            LiquidityRecord.spanOf(text).span == LiquidityRecord.Span.ALL
    }

    // ---- the figures ---------------------------------------------------------------------------------------------------

    /**
     * One fall from a peak: the peak (running total [peak] after trade [peakAt], 0 = before the first), the low ([trough]
     * after trade [troughAt]), and the trade it climbed back to the peak at ([recoveredAt], null: not yet).
     */
    data class Fall(val peakAt: Int, val peak: Double, val troughAt: Int, val trough: Double, val recoveredAt: Int?) {
        /** How far it fell: 0 or negative. */
        val depth: Double get() = trough - peak
    }

    /**
     * The read: [n] trades, the running [total] now, the [peak] (after trade [peakAt], 0 = none yet), the drawdown [now]
     * (0 or negative), every [falls] in order (the last one open when [now] < 0), the [worst], the longest losing run
     * ([longestLoss] trades, ending at trade [longestLossEnd], costing [longestLossCost]) and the losing run [lossNow].
     */
    data class Read(
        val n: Int, val total: Double, val peak: Double, val peakAt: Int, val now: Double, val falls: List<Fall>, val worst: Fall?,
        val longestLoss: Int, val longestLossEnd: Int, val longestLossCost: Double, val lossNow: Int,
    ) {
        /** The fall still open (not yet back at its peak), or null when at its best. */
        val open: Fall? get() = falls.lastOrNull()?.takeIf { it.recoveredAt == null }
        /** The falls that ended back at their peak. */
        val closed: List<Fall> get() = falls.filter { it.recoveredAt != null }
    }

    /** The per-lot nets of [rows] read as a running total from 0. Nets that are not numbers are left out. */
    fun read(rows: List<LiquidityRecord.Row>): Read = readNets(rows.map { it.perLot }.filter { it.isFinite() })

    /** As [read], on the nets themselves (oldest first). */
    fun readNets(nets: List<Double>): Read {
        var run = 0.0; var peak = 0.0; var peakAt = 0
        val falls = mutableListOf<Fall>()
        var cur: Fall? = null
        var longest = 0; var longestEnd = 0; var longestCost = 0.0
        var lossRun = 0; var lossCost = 0.0
        nets.forEachIndexed { i0, x ->
            val i = i0 + 1
            run += x
            if (x > 0) { lossRun = 0; lossCost = 0.0 } else {
                lossRun++; lossCost += x
                if (lossRun > longest) { longest = lossRun; longestEnd = i; longestCost = lossCost }
            }
            if (run >= peak) {
                cur?.let { falls += it.copy(recoveredAt = i) }; cur = null
                peak = run; peakAt = i
            } else {
                val c = cur
                cur = if (c == null) Fall(peakAt, peak, i, run, null) else if (run < c.trough) c.copy(troughAt = i, trough = run) else c
            }
        }
        cur?.let { falls += it }
        return Read(nets.size, run, peak, peakAt, run - peak, falls, falls.minByOrNull { it.depth }, longest, longestEnd, longestCost, lossRun)
    }

    /** [x] as a share of [of] (both rupees, [of] > 0), in whole percent; null when there is no peak above 0 to measure from. */
    fun pctOf(x: Double, of: Double): Int? = if (of > 0) (100 * kotlin.math.abs(x) / of).roundToInt() else null

    // ---- words ---------------------------------------------------------------------------------------------------------

    private fun rs(x: Double) = LiquidityRecord.rs(x)
    private fun date(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    private fun s(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun pct(x: Double) = "${(100 * x).roundToInt()}%"

    /** Trade [i] (1-based) of [rows] as a day, or "its start" for 0. */
    private fun at(rows: List<LiquidityRecord.Row>, i: Int) = if (i <= 0) "its start" else "trade $i (${date(rows[i - 1].day)})"

    private fun share(x: Double, peak: Double) = pctOf(x, peak)?.let { if (it > 100) " (all of that best and more)" else " ($it% of that best)" }.orEmpty()

    /** Whether today's state is unusual against the arm's own history: the fall now among its past falls, the losing run. */
    fun unusual(r: Read): String {
        val open = r.open
        val few = if (r.n < MIN_TRADES) " - though ${s(r.n, "trade")} ${if (r.n == 1) "is" else "are"} too short a history to call anything usual (it takes $MIN_TRADES)" else ""
        if (open == null) return "Nothing unusual now: it is at its best$few."
        val past = r.closed
        val now = r.now
        val line = when {
            past.isEmpty() -> "This is its first fall from a peak, so there is nothing of its own to set it against yet"
            past.all { now < it.depth } -> "The fall now is deeper than any of its ${s(past.size, "past fall")} - unusual for its own record"
            else -> {
                val shallower = past.count { now < it.depth }
                "The fall now is deeper than $shallower of its ${s(past.size, "past fall")} (the worst of them ${rs(past.minOf { it.depth })}) - " +
                    if (shallower * 2 > past.size) "on the deep side of its own record" else "within its own record"
            }
        }
        return "$line$few."
    }

    /** The answer from the arm's closed paper trades [rows] (oldest exit first). */
    fun answer(rows: List<LiquidityRecord.Row>): String {
        val mine = rows.filter { it.perLot.isFinite() }
        val r = read(mine)
        if (r.n == 0) return "Liquidity 15+5 has no closed paper trade in its book yet, Boss - no best to fall from.\n$END"
        val out = mutableListOf<String>()
        out += "Boss, over its ${s(r.n, "closed paper trade")} Liquidity 15+5 stands at ${rs(r.total)} a lot. " +
            if (r.peak <= 0) "It has not yet been above zero, so its best is still the start."
            else "Its best was ${rs(r.peak)}, at ${at(mine, r.peakAt)}."
        r.open?.let { o ->
            if (r.peak > 0) out += "Drawdown now: ${rs(r.now)} from that best${share(r.now, r.peak)}, ${s(r.n - o.peakAt, "trade")} since; " +
                "its low in this fall ${rs(o.depth)} at ${at(mine, o.troughAt)}."
            else out += "Its low so far ${rs(o.trough)} at ${at(mine, o.troughAt)}."
        } ?: run { out += "Drawdown now: none - it is at its best (${at(mine, r.peakAt)})." }
        r.worst?.let { w ->
            val climb = w.recoveredAt?.let { back ->
                val days = ChronoUnit.DAYS.between(mine[w.troughAt - 1].day, mine[back - 1].day)
                "it climbed back to that best at ${at(mine, back)}, ${s(back - w.troughAt, "trade")} and ${s(days.toInt(), "day")} after the low"
            } ?: "it has not climbed back to that best yet"
            out += "Worst drawdown ever: ${rs(w.depth)}${share(w.depth, w.peak)}, from ${rs(w.peak)} at ${at(mine, w.peakAt)} down to ${rs(w.trough)} " +
                "at ${at(mine, w.troughAt)}, ${s(w.troughAt - w.peakAt, "trade")} down; $climb."
        } ?: run { out += "Worst drawdown ever: none - it has never fallen from a best." }
        if (r.longestLoss == 0) out += "Longest losing streak: none - no losing trade yet."
        else {
            val start = r.longestLossEnd - r.longestLoss + 1
            val span = if (r.longestLoss == 1) "on ${date(mine[start - 1].day)}" else "from ${date(mine[start - 1].day)} to ${date(mine[r.longestLossEnd - 1].day)}"
            out += "Longest losing streak: ${s(r.longestLoss, "loss", "losses")} in a row $span, ${rs(r.longestLossCost)} a lot between them" +
                (if (r.lossNow > 0) "; it is on ${s(r.lossNow, "loss", "losses")} in a row now." else "; its last trade won.")
            if (r.longestLoss >= 2) {
                val chance = LiquidityRecord.lossRunChance(r.n, r.longestLoss, 1 - ForwardCheck.LIQUIDITY.winRate)
                out += "At the backtest's ${pct(ForwardCheck.LIQUIDITY.winRate)} win rate, a run of ${r.longestLoss} or more losses within ${s(r.n, "trade")} " +
                    "comes in ${pct(chance)} of runs - ${if (chance >= 0.2) "a normal run for it" else "rarer than usual for it"}."
            }
        }
        out += unusual(r)
        out += END
        return out.joinToString("\n")
    }
}
