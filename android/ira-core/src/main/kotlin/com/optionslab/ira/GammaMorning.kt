package com.optionslab.ira

import com.optionslab.engine.options.OptionMath
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Jarvis's morning gamma line (Boss approved, 14 Oct 2026; research/HUNT_R28_GEX.md): dealer gamma exposure (GEX) of an
 * index's nearest-expiry chain at 10:00, the time R28 measured, worked out exactly as the research's one GEX core
 * (research/brain/gex.py, one minute of it), so the phone and the study can never disagree:
 *
 *  - price: each option's last traded price at most [STALE_MIN] minutes old and at least [IV_MIN_PRICE];
 *  - IV: Black-Scholes on SPOT, r 6.5%, q 0, time to 15:30 IST on the expiry day; outside 1%-300% a year, or where one IV
 *    point moves the price by less than [IV_MIN_VEGA], there is none, and the option takes the IV of the nearest strike of
 *    its own side that has one (the lower strike on a tie);
 *  - GEX of an option, Rs per 1% move = gamma x OI (units, no lot) x spot^2 x 0.01;
 *  - sign S1 (R28's primary, the US one: dealers long calls, short puts): calls +, puts -;
 *  - fewer than [MIN_CONTRACTS] options with a value: no reading (a stale or thin chain says nothing);
 *  - zero-gamma flip: S1 re-priced with spot moved -5% .. +5% in 0.05% steps (IV and T held), the crossing nearest spot;
 *  - gamma walls: the strike with the biggest call gamma (most positive S1) and the one with the biggest put gamma (most
 *    negative S1).
 *
 * What R28 found at 10:00 (design days, 2023 and Apr 2025 - Oct 2026; [R28]): negative GEX came with a somewhat wider
 * rest-of-day range on all five indices (NIFTY's was weak at 10:00, q 0.17), and nothing about direction. So the line is
 * INFORMATION ONLY: it never places, changes, skips or resizes an order, paper or live. Pure: the app's
 * [com.optionslab.app.data.GammaMorningHost] reads the chain from memory and says it.
 */
object GammaMorning {
    /** R28's constants (research/brain/config.py, gex.py). */
    const val RATE = 0.065
    const val DIV_YIELD = 0.0
    const val STALE_MIN = 30.0
    const val MIN_CONTRACTS = 10
    const val IV_MIN = 0.01
    const val IV_MAX = 3.0
    const val IV_MIN_PRICE = 0.10
    const val IV_MIN_VEGA = 0.05
    /** Strikes each side of the money kept (the lake stores ATM +/-10). */
    const val EACH_SIDE = 10
    const val CRORE = 1e7

    /** The flip grid: spot x (1 + x), x from -5% to +5% in 0.05% steps (201 points, as numpy's arange then round(6)). */
    val FLIP_GRID: DoubleArray = DoubleArray(201) { i -> Math.round((-0.05 + i * 0.0005) * 1e6) / 1e6 }

    /** When the line is made: 10:00 (R28's minute), and only until [LATE] (a later look skips the day: an old reading is not the 10:00 one). */
    val AT: LocalTime = LocalTime.of(10, 0)
    val LATE: LocalTime = LocalTime.of(10, 15)

    /** The indices read, in the order said. SENSEX only when its chain is on the phone (its options are BSE's). */
    val INDICES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

    /** One option as the app holds it in memory: its last price, how old that price is (minutes), its OI (units). */
    data class Option(val strike: Double, val right: String, val ltp: Double?, val ageMin: Double?, val oi: Long?)

    /**
     * One reading. [netCr] / [grossCr] in Rs crore per 1% move; [flipDistPct] the flip's distance from spot in % (null: no
     * crossing within +/-5%); [callWall] / [putWall] the strikes with the biggest call and put gamma (null: none).
     */
    data class Reading(
        val index: String, val at: LocalDateTime, val spot: Double, val netCr: Double, val grossCr: Double, val contracts: Int,
        val flipDistPct: Double?, val callWall: Double?, val putWall: Double?,
    ) {
        val negative: Boolean get() = netCr < 0
        val flip: Double? get() = flipDistPct?.let { spot * (1 + it / 100) }
        val ratio: Double? get() = if (grossCr > 0) netCr / grossCr else null
    }

    /** R28's 10:00 result for one index: median rest-of-day range (%) on negative and positive GEX days, the days, BH q. */
    data class Median(val neg: Double, val pos: Double, val negDays: Int, val posDays: Int, val q: Double) {
        val significant: Boolean get() = q < 0.10
    }

    /** research/hunt/r28/results/describe.md, Q1 at 10:00, S1, rest-of-day range (design days only). */
    val R28: Map<String, Median> = linkedMapOf(
        "NIFTY" to Median(0.68, 0.62, 142, 454, 0.173),
        "BANKNIFTY" to Median(0.87, 0.75, 199, 397, 0.000),
        "FINNIFTY" to Median(0.81, 0.75, 168, 415, 0.025),
        "MIDCPNIFTY" to Median(1.21, 0.76, 150, 340, 0.000),
        "SENSEX" to Median(0.71, 0.60, 164, 351, 0.005),
    )

    // ---- Black-Scholes on spot (research/brain/bs.py) ------------------------------------------------------------------

    private fun d1(s: Double, k: Double, t: Double, sig: Double): Double =
        (ln(s / k) + (RATE - DIV_YIELD + 0.5 * sig * sig) * t) / (sig * sqrt(t))

    /** Black-Scholes price on spot (r [RATE], q [DIV_YIELD]). */
    fun price(s: Double, k: Double, t: Double, sig: Double, call: Boolean): Double {
        val a = d1(s, k, t, sig)
        val b = a - sig * sqrt(t)
        val dq = exp(-DIV_YIELD * t); val dr = exp(-RATE * t)
        return if (call) s * dq * OptionMath.normCdf(a) - k * dr * OptionMath.normCdf(b)
        else k * dr * OptionMath.normCdf(-b) - s * dq * OptionMath.normCdf(-a)
    }

    private fun vega(s: Double, k: Double, t: Double, sig: Double): Double =
        s * exp(-DIV_YIELD * t) * OptionMath.normPdf(d1(s, k, t, sig)) * sqrt(t)

    /**
     * Implied volatility as bs.implied_vol: null when the price is outside the band at [IV_MIN] .. [IV_MAX], the solve does
     * not converge, or one IV point moves the price by less than [IV_MIN_VEGA] (no volatility information).
     */
    fun impliedVol(p: Double, s: Double, k: Double, t: Double, call: Boolean): Double? {
        if (!(p.isFinite() && s.isFinite() && k.isFinite() && t.isFinite()) || t <= 0 || p <= 0 || s <= 0 || k <= 0) return null
        val plo = price(s, k, t, IV_MIN, call); val phi = price(s, k, t, IV_MAX, call)
        if (p < plo - 1e-9 || p > phi + 1e-9) return null
        var a = IV_MIN; var b = IV_MAX; var x = 0.2
        for (i in 0 until 100) {
            val f = price(s, k, t, x, call) - p
            if (abs(f) < 1e-9 * p || b - a < 1e-10) break
            if (f < 0) a = x
            if (f > 0) b = x
            val xn = x - f / vega(s, k, t, x)
            x = if (!xn.isFinite() || xn <= a || xn >= b) 0.5 * (a + b) else xn
        }
        val f = price(s, k, t, x, call) - p
        val ok = abs(f) < 1e-6 * p || b - a < 1e-8
        return if (ok && vega(s, k, t, x) / 100 >= IV_MIN_VEGA) x else null
    }

    /** gamma x S^2 x 0.01 per unit of OI: Rs of delta-hedge per 1% move (NaN where not defined). */
    fun gammaRs(s: Double, k: Double, t: Double, iv: Double): Double {
        if (!(s > 0 && k > 0 && t > 0 && iv > 0)) return Double.NaN
        val g = exp(-DIV_YIELD * t) * OptionMath.normPdf(d1(s, k, t, iv)) / (s * iv * sqrt(t))
        return g * s * s * 0.01
    }

    /** Calendar years from [now] to [expiry]'s 15:30 IST (null at or past it), as gex.years_to_expiry. */
    fun years(now: LocalDateTime, expiry: LocalDate): Double? {
        val secs = java.time.Duration.between(now, expiry.atTime(15, 30)).seconds
        return if (secs <= 0) null else secs / (365.0 * 86_400)
    }

    /** NaNs replaced by the nearest finite value of the array (the lower index first on a tie), as gex.fill_nearest. */
    internal fun fillNearest(a: DoubleArray): DoubleArray {
        val out = a.copyOf()
        val miss = BooleanArray(a.size) { !a[it].isFinite() }
        for (k in 1 until a.size) {
            if (miss.none { it }) break
            for (sh in intArrayOf(k, -k)) {
                for (j in a.indices) {
                    if (!miss[j]) continue
                    val src = j - sh
                    if (src in a.indices && a[src].isFinite()) { out[j] = a[src]; miss[j] = false }
                }
            }
        }
        return out
    }

    // ---- the reading ---------------------------------------------------------------------------------------------------

    private class Side(val k: DoubleArray, val iv: DoubleArray, val oi: DoubleArray, val c: DoubleArray)

    /**
     * The GEX reading of [index]'s chain at [now] ([options]: the nearest expiry, any strikes; the [EACH_SIDE] strikes each
     * side of [spot] are used). Null with no spot, no time left, or fewer than [MIN_CONTRACTS] options with a value (no
     * prices under [STALE_MIN] minutes old: a stale chain gives no reading).
     */
    fun read(index: String, options: List<Option>, spot: Double?, now: LocalDateTime, expiry: LocalDate?): Reading? {
        if (spot == null || !spot.isFinite() || spot <= 0 || expiry == null) return null
        val t = years(now, expiry) ?: return null
        val strikes = MarketRecord.around(options.map { it.strike }.filter { it.isFinite() && it > 0 }, spot, EACH_SIDE)
        if (strikes.isEmpty()) return null
        fun side(right: String): Side {
            val n = strikes.size
            val k = DoubleArray(n) { Double.NaN }; val iv = DoubleArray(n) { Double.NaN }; val oi = DoubleArray(n) { Double.NaN }
            for ((j, x) in strikes.withIndex()) {
                val o = options.firstOrNull { it.strike == x && it.right == right } ?: continue
                k[j] = x
                oi[j] = o.oi?.toDouble() ?: Double.NaN
                val px = o.ltp?.takeIf { p -> o.ageMin != null && o.ageMin <= STALE_MIN && p >= IV_MIN_PRICE }
                if (px != null) iv[j] = impliedVol(px, spot, x, t, right == "CE") ?: Double.NaN
            }
            val filled = fillNearest(iv)
            val ivs = DoubleArray(n) { if (k[it].isFinite()) filled[it] else Double.NaN }
            val c = DoubleArray(n) { j ->
                val g = gammaRs(spot, k[j], t, ivs[j])
                if (g.isFinite() && oi[j].isFinite() && oi[j] >= 0) g * oi[j] else Double.NaN
            }
            return Side(k, ivs, oi, c)
        }
        val ce = side("CE"); val pe = side("PE")
        val cnt = ce.c.count { it.isFinite() } + pe.c.count { it.isFinite() }
        if (cnt < MIN_CONTRACTS) return null
        val sc = ce.c.filter { it.isFinite() }.sum(); val sp = pe.c.filter { it.isFinite() }.sum()
        // Per strike (S1): a call's gamma minus the put's at the same strike; the walls are its largest and smallest.
        var best = Double.NEGATIVE_INFINITY; var worst = Double.POSITIVE_INFINITY
        var callWall: Double? = null; var putWall: Double? = null
        for (j in strikes.indices) {
            if (!ce.c[j].isFinite() && !pe.c[j].isFinite()) continue
            val per = (if (ce.c[j].isFinite()) ce.c[j] else 0.0) - (if (pe.c[j].isFinite()) pe.c[j] else 0.0)
            val kk = if (ce.k[j].isFinite()) ce.k[j] else pe.k[j]
            if (per > best) { best = per; callWall = kk }
            if (per < worst) { worst = per; putWall = kk }
        }
        return Reading(index, now, spot, (sc - sp) / CRORE, (sc + sp) / CRORE, cnt, flip(spot, ce, pe, t), callWall, putWall)
    }

    /** The zero-gamma flip's distance from spot in % (null: no crossing on the grid), as gex._flip. */
    private fun flip(spot: Double, ce: Side, pe: Side, t: Double): Double? {
        val tot = DoubleArray(FLIP_GRID.size) { gi ->
            val s = spot * (1 + FLIP_GRID[gi])
            var v = 0.0
            for ((sgn, d) in listOf(1.0 to ce, -1.0 to pe)) for (j in d.k.indices) {
                if (!d.c[j].isFinite()) continue
                val g = gammaRs(s, d.k[j], t, d.iv[j])
                if (g.isFinite()) v += sgn * g * d.oi[j]
            }
            v
        }
        var bestX: Double? = null
        for (i in 0 until tot.size - 1) {
            val a = tot[i]; val b = tot[i + 1]
            if (!(Math.signum(a) * Math.signum(b) < 0 || Math.signum(a) == 0.0)) continue
            val x = if (a == 0.0) FLIP_GRID[i] else FLIP_GRID[i] + (FLIP_GRID[i + 1] - FLIP_GRID[i]) * a / (a - b)
            if (bestX == null || abs(x) < abs(bestX)) bestX = x
        }
        return bestX?.let { it * 100 }
    }

    // ---- when -------------------------------------------------------------------------------------------------------

    /** Why no reading was made in the window (20 Oct; for the diagnostics): no chain on the phone. */
    const val NO_CHAIN = "no option chain on the phone 10:00-10:15"
    const val SWITCHED_OFF = "switched off"
    const val NOT_LOOKED = "the app did not look 10:00-10:15"

    /**
     * A kept "day|text" (the day's line or why there was none, 20 Oct: memory only before, so the 13:33 restart and the next
     * morning's catch-up both said "no reading" whatever 10:00 had found), for [day]; null: another day or nothing kept.
     */
    fun keptFor(kept: String?, day: LocalDate): String? =
        kept?.takeIf { it.startsWith("$day|") }?.substringAfter('|')?.takeIf { it.isNotBlank() }

    fun keep(day: LocalDate, text: String): String = "$day|${text.replace('\n', ' ')}"

    /** The line is due at [now]: a trading day, 10:00 to [LATE], not made yet today ([doneOn]). */
    fun due(now: LocalDateTime, tradingDay: Boolean, doneOn: LocalDate?): Boolean =
        tradingDay && doneOn != now.toLocalDate() && !now.toLocalTime().isBefore(AT) && now.toLocalTime().isBefore(LATE)

    // ---- the words --------------------------------------------------------------------------------------------------

    const val INFO = "Information only, not a trade signal."
    private fun k(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%.2f".format(Locale.ENGLISH, x)

    /** One index's sentence(s): the sign, R28's medians with their days, "nothing about direction", the walls and the flip. */
    fun line(r: Reading): String {
        val who = OrderFlow.label(r.index)
        val m = R28[r.index]
        val head = "Dealer gamma is ${if (r.negative) "negative" else "positive"} this morning on $who."
        val history = m?.let {
            val weak = if (it.significant) "" else " (a weak difference for $who at 10:00, not significant)"
            if (r.negative) " Such days had a somewhat wider rest-of-day range (median ${p2(it.neg)}% vs ${p2(it.pos)}%, ${it.negDays} vs ${it.posDays} days, 2023-26)$weak."
            else " Such days had a somewhat narrower rest-of-day range (median ${p2(it.pos)}% vs ${p2(it.neg)}% on negative days, ${it.posDays} vs ${it.negDays} days, 2023-26)$weak."
        } ?: ""
        val levels = listOfNotNull(r.callWall?.let { "biggest call gamma at ${k(it)}" }, r.putWall?.let { "biggest put gamma at ${k(it)}" },
            r.flip?.let { "the zero-gamma flip near ${k(it)}" })
        val watch = if (levels.isEmpty()) "" else " Levels to watch: ${levels.joinToString(", ")}."
        return "$head$history It says nothing about direction.$watch"
    }

    /** The morning note: each index read, then [INFO]; null when no index could be read. */
    fun note(readings: List<Reading>): String? =
        if (readings.isEmpty()) null else readings.joinToString(" ") { line(it) } + " " + INFO

    /**
     * Jarvis's answer to "what's gamma saying" ([index]: the one asked, or null for all): today's 10:00 readings, else
     * [now] (a reading made when asked, labelled with its time), else why there is none.
     */
    fun answer(index: String?, morning: List<Reading>, now: List<Reading>, tradingDay: Boolean, at: LocalDateTime): String {
        if (!tradingDay) return "The market is closed today, Boss, so there is no gamma reading. On a trading day I read it at 10:00. $INFO"
        val pick = { l: List<Reading> -> if (index == null) l else l.filter { it.index == index } }
        val m = pick(morning)
        if (m.isNotEmpty()) return "At 10:00: " + m.joinToString(" ") { line(it) } + " $INFO"
        val n = pick(now)
        if (n.isNotEmpty()) {
            val hm = "%02d:%02d".format(Locale.ENGLISH, at.hour, at.minute)
            val early = when {
                at.toLocalTime().isBefore(AT) -> " It is before 10:00, the time the research measured, so treat it as early."
                at.toLocalTime().isBefore(LATE) -> ""
                else -> " The research measured it at 10:00; a later reading was not tested."
            }
            return "From the chain on the phone at $hm: " + n.joinToString(" ") { line(it) } + early + " $INFO"
        }
        val who = index?.let { OrderFlow.label(it) } ?: "any index"
        return "I have no gamma reading for $who right now, Boss: it needs that index's option chain on the phone with prices under " +
            "${STALE_MIN.toInt()} minutes old (the live price stream, or the Options tab opened). $INFO"
    }

    private val ASK = Regex(" (gamma|gex) (saying|say|says|telling|tell|today|this morning|line|walls?|levels?) | morning gamma | gamma walls? | (what s|whats|what is) (the )?(gamma|gex) ")
    private val NOT = Regex(" (regime|flip|zero gamma|exposure|convention|explain|meaning|definition|turn|switch) ")
    /** The word itself ("what is gamma", "what's GEX"): the glossary's, not this reading. */
    private val WORD = Regex("^ (what is|what s|whats|define) (a |the )?(gamma|gex|dealer gamma)( mean| means)? $")

    /**
     * Which index "what's gamma saying" asks about ("" for none named), or null when [text] is not this question (the
     * live regime's own words - "gamma regime", "zero gamma", "gamma flip" - stay the order flow's answer).
     */
    fun asked(text: String): String? {
        val t = " " + text.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (!ASK.containsMatchIn(t) || NOT.containsMatchIn(t) || WORD.containsMatchIn(t)) return null
        return when {
            Regex(" (midcap ?nifty|midcpnifty|mid cap nifty|midcp) ").containsMatchIn(t) -> "MIDCPNIFTY"
            Regex(" (fin ?nifty|finnifty|nifty fin) ").containsMatchIn(t) -> "FINNIFTY"
            Regex(" (bank ?nifty|banknifty|nifty bank|bn) ").containsMatchIn(t) -> "BANKNIFTY"
            Regex(" sensex ").containsMatchIn(t) -> "SENSEX"
            Regex(" (nifty|nifty 50|nf) ").containsMatchIn(t) -> "NIFTY"
            else -> ""
        }
    }

    /** The diagnostics line (signs and levels only, never an amount): "gamma 10:00: BANKNIFTY negative (call wall 55000, put wall 54000)". */
    fun diagLine(readings: List<Reading>, why: String? = null): String =
        if (readings.isEmpty()) "gamma 10:00: no reading" + (why?.let { " ($it)" } ?: "") else "gamma 10:00: " + readings.joinToString("; ") { r ->
            "${r.index} ${if (r.negative) "negative" else "positive"} (call wall ${r.callWall?.let { "%.0f".format(Locale.ENGLISH, it) } ?: "-"}, " +
                "put wall ${r.putWall?.let { "%.0f".format(Locale.ENGLISH, it) } ?: "-"}, ${r.contracts} options)"
        }
}
