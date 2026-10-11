package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Risk across the arms (TODO S12, 11 Oct): how alike the arms' days are, and what they could lose together.
 *
 *  - [matrix]: the correlation of each two arms' daily P&L (paper and live together, after charges) over the last
 *    [WINDOW_DAYS] days either traded, counted only on the days both traded ([MIN_COMMON] such days or no figure);
 *  - [stops]: each arm's loss when its stop is hit - the 90th percentile of its losing trades in the window (its largest
 *    loss with fewer than 10 losses), a cautious stand-in for "its stop";
 *  - [worstCase]: the combined loss if the correlated arms (rho above [RHO]) all hit their stops at once - per group of arms
 *    linked by such correlations, the largest group's sum;
 *  - [PortfolioGuard]: the pre-entry check that uses them.
 *
 * Words for Jarvis ([asked], [answerCorrelation], [answerBlocked]). Pure.
 */
object PortfolioRisk {
    const val WINDOW_DAYS = 60
    const val RHO = 0.6
    const val MIN_COMMON = 10
    const val CAP_PCT_DEFAULT = 2.0
    /** The Bot settings' choices for the cap, % of capital. */
    val CAP_CHOICES = listOf(1.0, 1.5, 2.0, 3.0, 5.0)

    data class Trade(val arm: String, val day: LocalDate, val net: Double, val live: Boolean = false)

    /** Names that are not an arm (Boss's own trades, square-offs, trades outside the app): never part of the matrix. */
    private val NOT_ARMS = listOf("manual", "outside", "expiry square", "auto square", "protection", "boss")

    /**
     * One key for an arm however it is named: a strategy key ("liquidity:BANKNIFTY", "orb_fresh"), a book's or an owner's
     * name ("Liquidity 5m FINNIFTY", "ORB Fresh", "Jarvis Solo"); a Liquidity name with no index takes [symbol]'s. Null for a
     * name that is not an arm ([NOT_ARMS]).
     */
    fun canonOrNull(name: String, symbol: String? = null): String? {
        val t = name.lowercase(Locale.ENGLISH).trim()
        if (t.isEmpty() || NOT_ARMS.any { t.startsWith(it) }) return null
        return canon(name, symbol)
    }

    fun canon(name: String, symbol: String? = null): String {
        // A strategy's own name on screen ("Night (R3)", "MCX natural gas evening") is its key.
        FlowShadow.STRATEGIES.firstOrNull { it.label.equals(name.trim(), ignoreCase = true) && !it.key.equals(name.trim(), ignoreCase = true) &&
            !it.key.startsWith("liquidity") }
            ?.let { return canon(it.key) }
        val t = name.lowercase(Locale.ENGLISH).replace('_', ' ').replace(':', ' ').trim()
        fun has(w: String) = Regex("\\b$w\\b").containsMatchIn(t)
        val idx = listOf("midcpnifty", "finnifty", "banknifty", "sensex", "nifty").firstOrNull { has(it) }
            ?: symbol?.uppercase(Locale.ENGLISH)?.let { s -> listOf("MIDCPNIFTY", "FINNIFTY", "BANKNIFTY", "SENSEX", "NIFTY").firstOrNull { s.startsWith(it) } }?.lowercase(Locale.ENGLISH)
        return when {
            t.startsWith("liquidity") -> "liquidity" + (idx?.let { ":" + it.uppercase(Locale.ENGLISH) } ?: "")
            has("orb") && has("fresh") -> "orb_fresh"
            has("orb") && has("sweep") -> "orb_sweep"
            has("range") && has("fade") -> "range_fade"
            has("orb") -> "orb"
            has("night") -> "night_r3"
            has("hero") -> "hero"
            has("solo") -> "solo"
            has("vix") -> "vix_div"
            has("pine") -> "pine"
            t.startsWith("mcx") -> t.replace(' ', '_')
            has("saved") -> "saved"
            has("jarvis") -> "jarvis"
            else -> t.replace(Regex("[^a-z0-9]+"), "_").trim('_')
        }
    }

    /** "Liquidity BANKNIFTY", "ORB Fresh", "Night (R3)": an arm key in words. */
    fun label(key: String): String = when {
        key.startsWith("liquidity") -> "Liquidity" + key.substringAfter(':', "").let { if (it.isEmpty()) "" else " $it" }
        else -> FlowShadow.strategy(key)?.label ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Each arm's net per day, over the last [window] days any arm traded up to [today]. */
    fun daily(trades: List<Trade>, today: LocalDate, window: Int = WINDOW_DAYS): Map<String, Map<LocalDate, Double>> {
        val inWin = trades.filter { !it.day.isAfter(today) }
        val days = inWin.map { it.day }.distinct().sortedDescending().take(window).toSet()
        val out = HashMap<String, HashMap<LocalDate, Double>>()
        for (t in inWin) if (t.day in days) out.getOrPut(t.arm) { HashMap() }.merge(t.day, t.net, Double::plus)
        return out
    }

    /** Pearson's r of [x] and [y] (same length), or null (fewer than [MIN_COMMON] pairs, or one side flat). */
    fun pearson(x: DoubleArray, y: DoubleArray): Double? {
        val n = x.size
        if (n < MIN_COMMON || y.size != n) return null
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until n) { val a = x[i] - mx; val b = y[i] - my; sxy += a * b; sxx += a * a; syy += b * b }
        if (sxx <= 1e-12 || syy <= 1e-12) return null
        return (sxy / sqrt(sxx * syy)).coerceIn(-1.0, 1.0)
    }

    /** The arms' correlations: [rho] of each pair (both ways), [n] the days both traded. */
    data class Matrix(val arms: List<String>, val rho: Map<Pair<String, String>, Double>, val n: Map<Pair<String, String>, Int>) {
        fun of(a: String, b: String): Double? = if (a == b) 1.0 else rho[a to b]
        fun days(a: String, b: String): Int = n[a to b] ?: 0
        val empty: Boolean get() = arms.size < 2
    }

    fun matrix(trades: List<Trade>, today: LocalDate, window: Int = WINDOW_DAYS): Matrix {
        val d = daily(trades, today, window)
        val arms = d.keys.sorted()
        val rho = HashMap<Pair<String, String>, Double>(); val n = HashMap<Pair<String, String>, Int>()
        for (i in arms.indices) for (j in i + 1 until arms.size) {
            val a = d.getValue(arms[i]); val b = d.getValue(arms[j])
            val common = a.keys.filter { it in b }.sorted()
            n[arms[i] to arms[j]] = common.size; n[arms[j] to arms[i]] = common.size
            pearson(DoubleArray(common.size) { a.getValue(common[it]) }, DoubleArray(common.size) { b.getValue(common[it]) })?.let {
                rho[arms[i] to arms[j]] = it; rho[arms[j] to arms[i]] = it
            }
        }
        return Matrix(arms, rho, n)
    }

    /** Each arm's loss at its stop (rupees, positive): the 90th percentile of its losing trades in the window. */
    fun stops(trades: List<Trade>, today: LocalDate, window: Int = WINDOW_DAYS): Map<String, Double> {
        val days = trades.filter { !it.day.isAfter(today) }.map { it.day }.distinct().sortedDescending().take(window).toSet()
        return trades.filter { it.day in days && it.net < 0 }.groupBy { it.arm }.mapValues { (_, ts) ->
            val l = ts.map { -it.net }.sorted()
            if (l.size < 10) l.last() else l[((l.size - 1) * 0.9).toInt()]
        }
    }

    /** The groups of arms linked by correlations above [rho] (each of 2 or more arms). */
    fun groups(m: Matrix, rho: Double = RHO): List<Set<String>> {
        val seen = HashSet<String>()
        val out = ArrayList<Set<String>>()
        for (a in m.arms) {
            if (a in seen) continue
            val g = LinkedHashSet<String>(); val todo = ArrayDeque(listOf(a))
            while (todo.isNotEmpty()) {
                val x = todo.removeFirst()
                if (!g.add(x)) continue
                for (y in m.arms) if (y !in g && (m.of(x, y) ?: 0.0) > rho) todo += y
            }
            seen += g
            if (g.size >= 2) out += g
        }
        return out
    }

    /** The combined loss if the correlated arms all hit their stops: the largest group's sum (and the group). */
    fun worstCase(m: Matrix, stops: Map<String, Double>, rho: Double = RHO): Pair<Double, Set<String>> =
        groups(m, rho).map { g -> g.sumOf { stops[it] ?: 0.0 } to g }.maxByOrNull { it.first } ?: (0.0 to emptySet())

    // ---- words ----

    fun rs(x: Double): String = "Rs " + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun f2(x: Double) = "%.2f".format(Locale.ENGLISH, x)

    /** The sheet's rows: each pair with a figure, the most alike first. */
    fun pairLines(m: Matrix): List<String> {
        val out = ArrayList<Pair<Double, String>>()
        for (i in m.arms.indices) for (j in i + 1 until m.arms.size) {
            val a = m.arms[i]; val b = m.arms[j]
            val r = m.of(a, b)
            out += (r ?: -2.0) to "${label(a)} · ${label(b)}: " + (r?.let { "${f2(it)}${if (it > RHO) " (moves together)" else ""}" }
                ?: "too few common days (${m.days(a, b)} of $MIN_COMMON)")
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /** The Brain card's line: "Portfolio: 2 arms move together (rho 0.72); worst case together Rs 6,400 (cap Rs 8,000)". */
    fun line(m: Matrix, stops: Map<String, Double>, cap: Double, blocksToday: Int): String {
        if (m.empty) return "Portfolio: too few arms with a record yet to compare."
        val (worst, g) = worstCase(m, stops)
        val tail = if (blocksToday > 0) " · $blocksToday entr${if (blocksToday == 1) "y" else "ies"} blocked today" else ""
        if (g.isEmpty()) return "Portfolio: no two arms move together (rho above ${f2(RHO)})$tail."
        return "Portfolio: ${g.size} arms move together (${g.joinToString(", ") { label(it) }}); if all hit their stops together " +
            "${rs(worst)} (cap ${rs(cap)})$tail."
    }

    enum class Ask { CORRELATION, WHY_BLOCKED }

    private val CORR = Regex("\\b(correlat\\w*|move together|moving together|overlap\\w*|diversif\\w*)\\b")
    private val ARMS = Regex("\\b(strateg\\w*|arms?|bots?|portfolio)\\b")
    private val BLOCKED = Regex("\\bwhy\\b.*\\b(blocked|block)\\b|\\b(blocked|block)\\b.*\\bwhy\\b")

    /** "how correlated are my strategies?", "do my arms move together?", "why was Liquidity blocked?"; null otherwise. */
    fun asked(q: String): Ask? {
        val t = q.lowercase(Locale.ENGLISH)
        if (CORR.containsMatchIn(t) && ARMS.containsMatchIn(t)) return Ask.CORRELATION
        if (BLOCKED.containsMatchIn(t) && !Regex("\\b(order|refused|rejected|kill switch)\\b").containsMatchIn(t) &&
            (Regex("\\b(portfolio|correlat\\w*)\\b").containsMatchIn(t) || ARM_WORDS.any { Regex("\\b$it\\b").containsMatchIn(t) })) return Ask.WHY_BLOCKED
        return null
    }

    private val ARM_WORDS = listOf("orb", "liquidity", "night", "hero", "solo", "vix", "pine", "mcx", "range fade", "sweep", "fresh", "arm", "bot", "entry", "strategy")
    /** The words that name one arm (not "arm", "bot", "entry" or "strategy"). */
    private val NAMED_ARMS = listOf("orb fresh", "orb sweep", "range fade", "orb", "liquidity", "night", "hero", "solo", "vix", "pine", "mcx")

    fun answerCorrelation(m: Matrix, stops: Map<String, Double>, cap: Double): String {
        if (m.empty) return "Boss, I need at least two arms with $MIN_COMMON or more days of trades in the last $WINDOW_DAYS to say how alike they are."
        val lines = pairLines(m).take(5)
        val (worst, g) = worstCase(m, stops)
        val head = if (g.isEmpty()) "Boss, none of your arms move together (no pair above ${f2(RHO)} over the last $WINDOW_DAYS trading days)."
            else "Boss, ${g.joinToString(", ") { label(it) }} move together (above ${f2(RHO)}): if they all hit their stops on one day " +
                "that is about ${rs(worst)} together, against a cap of ${rs(cap)}."
        return head + " " + lines.joinToString("; ") + ". I block a later same-direction entry by a correlated arm when the combined stop " +
            "loss would pass the cap; I never add a trade."
    }

    fun answerBlocked(q: String, blocks: List<PortfolioGuard.Block>): String {
        if (blocks.isEmpty()) return "Boss, the portfolio guard has blocked nothing today."
        val t = q.lowercase(Locale.ENGLISH)
        val hit = blocks.lastOrNull { b -> label(b.arm).lowercase(Locale.ENGLISH).split(' ').any { w -> w.length >= 3 && Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(t) } }
        if (hit != null) return "Boss, " + hit.why + "."
        // An arm named that the guard did not block: said so, never another arm's block as its answer (G9-10).
        val named = NAMED_ARMS.firstOrNull { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
        if (named != null) return "Boss, the portfolio guard has not blocked ${label(canon(named))} today; it blocked " +
            blocks.map { label(it.arm) }.distinct().joinToString(", ") + ". Something else may have stopped it (a regime sit-out, a news pause)."
        return "Boss, " + blocks.last().why + "."
    }
}

/**
 * The portfolio guard (TODO S12): before a NEW entry, paper or live, it asks whether arms that move together (rho above
 * [PortfolioRisk.RHO]) already opened today in the SAME direction on the same account; if so and their combined stop losses
 * with this one would pass the cap ([Config.capPct] of [Config.capital]), this later entry is blocked for the day. It only
 * ever blocks: it never places, sizes up, reverses or exits anything, and an exit never asks it.
 *
 * Memory only: the matrix, the stops and the config are handed in off the order path ([update]); [check] reads nothing
 * else. Thread-safe.
 */
class PortfolioGuard {
    data class Config(val capital: Double = 0.0, val capPct: Double = PortfolioRisk.CAP_PCT_DEFAULT, val rho: Double = PortfolioRisk.RHO,
                      val on: Boolean = true) {
        val capRs: Double get() = capital * capPct / 100.0
    }

    data class Block(val arm: String, val direction: Int, val live: Boolean, val atMs: Long, val partners: List<String>,
                     val combined: Double, val cap: Double, val why: String)

    data class Decision(val block: Boolean, val why: String? = null, val partners: List<String> = emptyList(), val combined: Double = 0.0)

    // [seq]: the order things happened in (two checks can share a millisecond; the door asks "let through since the block?").
    private data class Opened(val arm: String, val direction: Int, val live: Boolean, val atMs: Long, val seq: Long = 0L)

    @Volatile var matrix: PortfolioRisk.Matrix = PortfolioRisk.Matrix(emptyList(), emptyMap(), emptyMap())
        private set
    @Volatile var stops: Map<String, Double> = emptyMap()
        private set
    @Volatile var config: Config = Config()
        private set

    private var day: LocalDate? = null
    private val opened = ArrayList<Opened>()
    private val blocks = ArrayList<Block>()
    private val blockSeq = ArrayList<Long>()
    private var seq = 0L
    /** Each arm's latest live decision a direction ("arm|dir" -> blocked, when): the door refuses only an arm still blocked. */
    private val liveDecided = HashMap<String, Pair<Boolean, Long>>()

    fun update(m: PortfolioRisk.Matrix, s: Map<String, Double>, c: Config) { matrix = m; stops = s; config = c }
    fun setConfig(c: Config) { config = c }

    private fun roll(d: LocalDate) { if (day != d) { day = d; opened.clear(); blocks.clear(); blockSeq.clear(); liveDecided.clear() } }

    /**
     * [arm]'s new entry leaning [direction] (+1 / -1) on [today]: blocked when correlated arms already opened today the same
     * way on the same account ([live]) and the combined stop losses pass the cap; otherwise let through - and counted as
     * opened when [open] (the app passes false and calls [opened] once the entry is actually taken: review R1, an entry
     * skipped later by the flow, the regime or the account guard, or never filled, never blocks another arm).
     */
    @Synchronized
    fun check(arm: String, direction: Int, live: Boolean, today: LocalDate, nowMs: Long, open: Boolean = true): Decision {
        roll(today)
        fun pass(d: Decision): Decision {
            if (live && direction != 0) liveDecided["$arm|$direction"] = false to nowMs
            if (open) note(arm, direction, live, nowMs)
            return d
        }
        val c = config
        if (!c.on || direction == 0 || !(c.capital > 0)) return pass(Decision(false))
        val m = matrix
        val partners = opened.filter { it.live == live && it.direction == direction && it.arm != arm && (m.of(arm, it.arm) ?: 0.0) > c.rho }
            .map { it.arm }.distinct()
        if (partners.isEmpty()) return pass(Decision(false))
        val s = stops
        val combined = (s[arm] ?: 0.0) + partners.sumOf { s[it] ?: 0.0 }
        if (combined <= c.capRs) return pass(Decision(false, null, partners, combined))
        val why = "${PortfolioRisk.label(arm)}'s ${if (live) "live" else "paper"} ${if (direction > 0) "bullish" else "bearish"} entry was blocked by the " +
            "portfolio guard: ${partners.joinToString(", ") { PortfolioRisk.label(it) }} already opened the same way today and move with it " +
            "(${partners.joinToString(", ") { "%.2f".format(Locale.ENGLISH, m.of(arm, it) ?: 0.0) }}); all at their stops together is about " +
            "${PortfolioRisk.rs(combined)}, over the cap of ${PortfolioRisk.rs(c.capRs)} (${trim(c.capPct)}% of capital)"
        blocks += Block(arm, direction, live, nowMs, partners, combined, c.capRs, why); blockSeq += ++seq
        if (blocks.size > 200) { blocks.removeAt(0); blockSeq.removeAt(0) }
        if (live) liveDecided["$arm|$direction"] = true to nowMs
        return Decision(true, why, partners, combined)
    }

    /** [arm]'s entry leaning [direction] was actually taken (a paper fill, an order sent to Zerodha): counted as opened. */
    @Synchronized
    fun opened(arm: String, direction: Int, live: Boolean, today: LocalDate, nowMs: Long) {
        roll(today)
        if (direction != 0) note(arm, direction, live, nowMs)
    }

    private fun note(arm: String, direction: Int, live: Boolean, nowMs: Long) {
        opened.removeAll { it.arm == arm && it.direction == direction && it.live == live }
        opened += Opened(arm, direction, live, nowMs, ++seq)
    }

    /**
     * The entry door's part for [arm]'s live order leaning [direction] (review R1: the door names the arm): refused only when
     * the guard's latest live decision for THAT arm that way was a block, in the last [windowMs] - an order sent despite its
     * arm's block. Another arm's entry, an arm let through since, or an order whose arm never asked is never refused here.
     */
    @Synchronized
    fun doorRefusal(arm: String, direction: Int, today: LocalDate, nowMs: Long, windowMs: Long = 60_000L): String? {
        if (day != today || direction == 0) return null
        val (blocked, at) = liveDecided["$arm|$direction"] ?: return null
        if (!blocked || nowMs - at !in 0..windowMs) return null
        val b = blocks.lastOrNull { it.live && it.arm == arm && it.direction == direction } ?: return null
        return "refused: " + b.why
    }

    @Synchronized fun blocksToday(today: LocalDate): List<Block> = if (day == today) blocks.toList() else emptyList()

    @Synchronized fun clear() { day = null; opened.clear(); blocks.clear(); blockSeq.clear(); liveDecided.clear() }

    private fun trim(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()
}
