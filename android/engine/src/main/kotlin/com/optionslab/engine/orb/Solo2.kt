package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * "Solo 2.0" (15 Oct 2026, Boss: "Both"; research R57, research/HUNT_R57_SOLO_2.md, code research/hunt/r57/): Jarvis's own SECOND
 * paper Solo, next to Solo (midday) ([SoloMidday], unchanged). PAPER ONLY, forever: no live path, and never offered as a trade
 * idea. It is the A2 meta-labeller that passed every gate (final test 1 Apr - 14 Oct 2026: 128 trades, +Rs 99,172, twin p
 * 0.002, BH q 0.01) - with the write-up's caveats: 72% of that profit was MIDCPNIFTY, and 92 of its 128 trades came from S3,
 * whose exit R52 chose on the same window.
 *
 *   candidates  each day on NIFTY, BANKNIFTY, FINNIFTY and MIDCPNIFTY, never on an index's own expiry day ([Setup]):
 *                 S1  SoloMidday's setup at 12:00 (index 1-minute bars), 4-ITM; out on the index stop (0.3 ATR14 on a 1-minute
 *                     close), at breakeven once 0.45 ATR its way, or 14:30 ([SoloMidday.walk])
 *                 S2  the R51 Donchian 20 break ([DonchianArm]), 1-ITM; -15% / +30%, 30 minutes, 15:15
 *                 S3  R52's open outside value at 09:20 ([OovArm]), 1-ITM; 60 minutes, with the Solo layer (-30% stop, +10%
 *                     locked once +40%)
 *                 S4  the 15-minute opening range: the first 5-minute close beyond it (09:30-13:59), 1-ITM; -25% / +50%,
 *                     60 minutes, with the Solo layer
 *               Every signal is read from the index. SENSEX is out: R57 trained without it.
 *   score       the logistic model in A2.json ([Model], loaded once at start-up, never on the tick or order path): fill a
 *               missing input with its training median, standardise with the stored mean and scale, sigmoid of the
 *               intercept plus the weighted sum. 29 inputs ([FEATURES], [features]) read at the signal bar's close.
 *   take        candidates in time order (at the same minute the highest score first); the first scoring at least the
 *               threshold (0.4871, >=) is bought, 1 lot; then nothing more that day ([pick]).
 *   forward     as Solo (midday)'s ([SoloMidday.judge]): after 60 closed paper trades a net per trade at or below 0 switches
 *               it off; a drawdown beyond -Rs 25,000 at any time switches it off. Only Boss switches it back on.
 *   retrain     NOT on the phone. A gated monthly offline retrain (r57retrain.py) is a TODO: the model ships as an asset.
 *
 * Pure: no clock, no network, no storage, no orders.
 */
object Solo2 {
    const val SOURCE = "solo2"
    const val LABEL = "Solo 2.0"
    const val TAG = "Solo 2.0"
    const val RESEARCH_ID = "R57 (A2)"
    val ARM = Arm(SOURCE, LABEL, paperOnly = true)
    val UNDERLYINGS = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
    const val LOTS = 1
    /** The model asset (app/src/main/assets/solo2/A2.json: research/hunt/r57/results/models/A2.json, byte for byte). */
    const val ASSET = "solo2/A2.json"
    const val REPORT_APART = "MIDCPNIFTY"

    const val RULES = "Jarvis's own second paper Solo: each day it lists the candidates of four index setups (S1 Solo midday at 12:00, " +
        "S2 the Donchian 20 break, S3 open outside value at 09:20, S4 the 15-minute opening range) on NIFTY, BANKNIFTY, FINNIFTY and " +
        "MIDCPNIFTY, scores each with R57's logistic model, and buys the first scoring 0.4871 or more, 1 lot; then nothing more that " +
        "day; each setup's own stop and exits; never on an index's own expiry day; paper only, forever"
    const val RECORD = "Research R57 final test: 128 trades, +₹99,172, p 0.002, q 0.01 - 72% of it MIDCPNIFTY, 92 of the 128 trades from S3"
    const val RETRAIN_TODO = "No retraining on the phone: a gated monthly offline retrain is a to-do"

    // ---- the model ----------------------------------------------------------------------------------------------------

    /** The 29 inputs, in the model's order (r57model.names_a(False)). */
    val FEATURES = listOf("ret_15m", "ret_60m", "ret_day", "gap_pct", "day_range_vs_adr10", "pos_in_day_range", "rv_30m", "er_day",
        "dist_tpavg_pct", "range_30m_pct", "dist_prev_close_pct", "or30_state", "atr14d_pct", "minute", "dte", "vix", "vix_chg_pct",
        "pcr_oi", "iv_atm", "iv_skew", "idx_NIFTY", "idx_BANKNIFTY", "idx_FINNIFTY", "idx_MIDCPNIFTY", "side", "setup_S1", "setup_S2",
        "setup_S3", "setup_S4")

    /** R57's exported logistic model ("logit"): median-imputed, standardised, sigmoid. */
    data class Model(val features: List<String>, val threshold: Double, val rule: String, val medians: DoubleArray, val mean: DoubleArray,
                     val scale: DoubleArray, val coef: DoubleArray, val intercept: Double) {
        init {
            val n = features.size
            require(n > 0 && medians.size == n && mean.size == n && scale.size == n && coef.size == n) { "model arrays disagree with its ${features.size} inputs" }
            require(scale.all { it != 0.0 && it.isFinite() }) { "a zero or missing scale" }
            require(rule == ">=" || rule == ">") { "unknown take rule $rule" }
        }

        /** r57eval.score: [values] in [features] order; NaN (or null) = missing. */
        fun score(values: DoubleArray): Double {
            require(values.size == features.size) { "expected ${features.size} features, got ${values.size}" }
            var z = intercept
            for (i in values.indices) {
                val v = if (values[i].isNaN()) medians[i] else values[i]
                z += coef[i] * (v - mean[i]) / scale[i]
            }
            return sigmoid(z)
        }

        /** r57eval.take: score >= threshold (rule ">="), or > (rule ">"). */
        fun take(score: Double): Boolean = if (rule == ">") score > threshold else score >= threshold

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** r57eval._sigmoid (the stable form). */
    fun sigmoid(z: Double): Double = if (z >= 0) 1.0 / (1.0 + exp(-z)) else exp(z).let { it / (1.0 + it) }

    /** The model from A2.json's text: only the "logit" kind with exactly [FEATURES] is accepted (anything else: an error). */
    fun parseModel(text: String): Model {
        @Suppress("UNCHECKED_CAST")
        val o = MiniJson.parse(text) as? Map<String, Any?> ?: throw MiniJson.Error("not an object")
        require(o["kind"] == "logit") { "Solo 2.0 runs only the logistic model (kind ${o["kind"]})" }
        val names = (o["features"] as List<*>).map { it as String }
        require(names == FEATURES) { "the model's inputs are not R57's 29" }
        fun arr(k: String) = (o[k] as List<*>).map { (it as Double) }.toDoubleArray()
        return Model(names, o["threshold"] as Double, (o["rule"] as? String) ?: ">=", arr("medians"), arr("mean"), arr("scale"), arr("coef"),
            o["intercept"] as Double)
    }

    // ---- the setups ---------------------------------------------------------------------------------------------------

    enum class Setup(val key: String, val money: Int, val words: String) {
        S1("S1", 4, "Solo midday (12:00)"), S2("S2", 1, "Donchian 20 break"), S3("S3", 1, "open outside value (09:20)"), S4("S4", 1, "15-minute opening range");
    }

    /** Each setup's premium exits (r57setups.EXITS); S1's index exits are [SoloMidday.walk]'s. */
    val LAYER = listOf(0.40 to 0.10)
    fun exits(s: Setup): PassedArms.PremiumExits = when (s) {
        Setup.S1 -> PassedArms.PremiumExits(null, null, null, squareOff = SoloMidday.EXIT)
        Setup.S2 -> DonchianArm.EXITS
        Setup.S3 -> PassedArms.PremiumExits(0.30, null, 60, LAYER, LocalTime.of(15, 10))
        Setup.S4 -> PassedArms.PremiumExits(0.25, 0.50, 60, LAYER, LocalTime.of(15, 10))
    }

    /** A candidate: [setup] on [underlying], [side], the entry minute [entryRow] (the signal row is the one before), its strikes in order. */
    data class Candidate(val underlying: String, val setup: Setup, val side: Int, val entryRow: Int, val strikes: List<Int>,
                         /** S1: the 12:00 index price and ATR14 its exits read. */ val index: Double? = null, val atr: Double? = null) {
        val signalRow: Int get() = entryRow - 1
        val key: String get() = "${setup.key}|$underlying|$side|$entryRow"
        fun entryTime(day: LocalDate): LocalDateTime = day.atTime(PassedArms.Session.time(entryRow))
    }

    /** S1 on [underlying] (r57setups.s1_signal = SoloMidday.signal): null when no signal. The strikes in R57's order (4-ITM, then one either way). */
    fun s1(underlying: String, d: SoloMidday.Decision): Candidate? {
        val s = d.signal ?: return null
        val st = PassedArms.step(underlying)
        val w = OrbRules.atmStrike(s.index, st) - s.side * 4 * st
        return Candidate(underlying, Setup.S1, s.side, PassedArms.Session.row(12, 0), listOf(w, w + s.side * st, w - s.side * st), s.index, s.atr)
    }

    /** S2: each Donchian break today (the arm's own signals), 1-ITM. */
    fun s2(signals: List<DonchianArm.Signal>): List<Candidate> =
        signals.map { Candidate(it.underlying, Setup.S2, it.side, it.bar.entryRow, listOf(it.strike)) }

    /** S3: the 09:20 open-outside-value signal, 1-ITM (null: none). */
    fun s3(underlying: String, today: PassedArms.DayBars, levels: OovArm.Levels?): Candidate? {
        val side = OovArm.signal(today, levels)
        if (side == 0) return null
        return Candidate(underlying, Setup.S3, side, 5, listOf(OovArm.strike(underlying, side, today)))
    }

    /**
     * S4 (r57setups.s4_candidates): the opening range of 09:15-09:29 (rows 0-14); the first 5-minute bar from 09:30 to 13:55
     * (bars 3-56) whose last close is beyond it buys that way, 1-ITM of the close of its last minute; one a day.
     */
    fun s4(underlying: String, today: PassedArms.DayBars): Candidate? {
        val hs = (0 until 15).map { today.h[it] }.filter { !it.isNaN() }
        val ls = (0 until 15).map { today.l[it] }.filter { !it.isNaN() }
        if (hs.isEmpty() || ls.isEmpty()) return null
        val hi = hs.max(); val lo = ls.min()
        val last = today.lastRow
        for (b in 3 until 57) {
            if (5 * b + 4 > last) return null
            val rows = (5 * b until 5 * b + 5).filter { !today.c[it].isNaN() }
            if (rows.isEmpty()) continue
            val c = today.c[rows.last()]
            val side = if (c > hi) 1 else if (c < lo) -1 else 0
            if (side != 0) {
                val er = 5 * (b + 1)
                return Candidate(underlying, Setup.S4, side, er, listOf(PassedArms.itmStrike(underlying, side, today.closeAtOrBefore(er - 1), 1)))
            }
        }
        return null
    }

    /**
     * S2's plain-rule taking (R51's `take`: one position a side, at most 2 a side a day): with no option prices for trades it
     * never makes, a same-side signal counts as held for the 30 minutes its time stop allows (the latest it could be out).
     */
    fun s2Taken(c: List<Candidate>): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (x in c.sortedBy { it.entryRow }) {
            val same = out.filter { it.underlying == x.underlying && it.side == x.side }
            if (same.size >= DonchianArm.MAX_PER_SIDE) continue
            if (same.any { x.entryRow <= it.entryRow + 29 }) continue
            out += x
        }
        return out
    }

    // ---- the take rule ---------------------------------------------------------------------------------------------------

    /** A candidate with its score. */
    data class Scored(val c: Candidate, val score: Double)

    /**
     * r57study.select_a for one day: [scored] in time order (same entry minute: the highest score first); the first passing
     * [model]'s take rule, or null.
     */
    fun pick(scored: List<Scored>, model: Model): Scored? =
        scored.filter { it.score.isFinite() }.sortedWith(compareBy<Scored> { it.c.entryRow }.thenByDescending { it.score }).firstOrNull { model.take(it.score) }

    // ---- the inputs ------------------------------------------------------------------------------------------------------

    /**
     * What the inputs read besides today's minutes: the previous session's last close ([prevClose]), the full high-low ranges
     * of up to the 10 sessions before today ([prevRanges], oldest first), the daily ATR14 ([atr]), India VIX today ([vix]: its
     * 1-minute closes on the grid) and its previous session's last close ([vixPrev]), sessions to the index's next expiry
     * ([dte]) and the option-chain context at the signal minute ([pcr5]: put/call OI of ATM-5..+5, [ivAtm], [ivSkew]: put
     * at -3 minus call at +3). A value the phone does not have is NaN: the model fills it with its training median.
     */
    data class Context(val underlying: String, val prevClose: Double, val prevRanges: List<Double>, val atr: Double, val vix: DoubleArray?,
                       val vixPrev: Double, val dte: Double, val pcr5: Double = Double.NaN, val ivAtm: Double = Double.NaN,
                       val ivSkew: Double = Double.NaN) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val NAN = Double.NaN

    /**
     * The 12 brain features (research/brain/features: ret_15m, ret_60m, ret_day, gap_pct, day_range_vs_adr10, pos_in_day_range,
     * rv_30m, er_day, dist_tpavg_pct, range_30m_pct, dist_prev_close_pct, or30_state) at row [t] of [d], using only rows <= t,
     * exactly as pandas computes them (a missing minute is NaN; running highs and lows are NaN on a missing minute).
     */
    fun brain(d: PassedArms.DayBars, t: Int, prevClose: Double, prevRanges: List<Double>): DoubleArray {
        val c = d.c; val h = d.h; val l = d.l; val o = d.o
        val ct = c[t]
        fun retK(k: Int) = if (t - k >= 0) ln(ct / c[t - k]) * 100 else NAN
        var first = NAN
        for (r in 0..t) if (!o[r].isNaN()) { first = o[r]; break }
        var hi = NAN; var lo = NAN
        for (r in 0..t) { if (!h[r].isNaN()) hi = if (hi.isNaN()) h[r] else maxOf(hi, h[r]); if (!l[r].isNaN()) lo = if (lo.isNaN()) l[r] else minOf(lo, l[r]) }
        val hiT = if (h[t].isNaN()) NAN else hi
        val loT = if (l[t].isNaN()) NAN else lo
        val rng = hiT - loT
        val ret15 = retK(15)
        val ret60 = retK(60)
        val retDay = ln(ct / first) * 100
        val gap = (first / prevClose - 1) * 100
        val adr = adr10(prevRanges)
        val rangeVsAdr = rng / adr
        val pos = if (hiT > loT) (ct - loT) / (hiT - loT) else NAN
        // rv_30m: the sample std of the last 30 one-minute log returns, x sqrt(375); any missing: NaN.
        var rv = NAN
        if (t >= 30) {
            val r = DoubleArray(30) { k -> val i = t - 29 + k; ln(c[i] / c[i - 1]) * 100 }
            if (r.all { it.isFinite() }) {
                val m = r.sum() / 30
                var ss = 0.0
                for (x in r) ss += (x - m) * (x - m)
                rv = sqrt(ss / 29) * sqrt(375.0)
            }
        }
        val er = if (hiT > loT) abs(ct - first) / (hiT - loT) else NAN
        // dist_tpavg_pct: the session's running mean of typical price (NaN on a missing minute).
        var tpSum = 0.0; var tpN = 0
        for (r in 0..t) { val tp = (h[r] + l[r] + c[r]) / 3; if (!tp.isNaN()) { tpSum += tp; tpN++ } }
        val tpT = (h[t] + l[t] + c[t]) / 3
        val dTp = if (tpT.isNaN() || tpN == 0) NAN else (ct / (tpSum / tpN) - 1) * 100
        // range_30m_pct: the last 30 minutes all present.
        var r30 = NAN
        if (t >= 29) {
            val hs = (t - 29..t).map { h[it] }; val ls = (t - 29..t).map { l[it] }
            if (hs.none { it.isNaN() } && ls.none { it.isNaN() }) r30 = (hs.max() - ls.min()) / ct * 100
        }
        val dPrev = (ct / prevClose - 1) * 100
        // or30_state: known from row 29 on; the 30-minute range's high / low over the minutes it has.
        var or30 = NAN
        if (t >= 29 && !ct.isNaN()) {
            val oh = (0 until 30).map { h[it] }.filter { !it.isNaN() }
            val ol = (0 until 30).map { l[it] }.filter { !it.isNaN() }
            if (oh.isNotEmpty() && ol.isNotEmpty()) or30 = if (ct > oh.max()) 1.0 else if (ct < ol.min()) -1.0 else 0.0
        }
        return doubleArrayOf(ret15, ret60, retDay, gap, rangeVsAdr, pos, rv, er, dTp, r30, dPrev, or30)
    }

    /** The mean full range of the 10 sessions before today, needing 8 of them (pandas' rolling(10, min_periods=8)). */
    fun adr10(prevRanges: List<Double>): Double {
        val w = prevRanges.takeLast(10).filter { it.isFinite() }
        return if (w.size >= 8) w.sum() / w.size else NAN
    }

    /** The unsigned base inputs (r57model.base_row) at row [t]: the 12 brain features, then R57's own, then the index one-hots. */
    fun base(d: PassedArms.DayBars, t: Int, x: Context): DoubleArray {
        val b = brain(d, t, x.prevClose, x.prevRanges)
        val px = d.closeAtOrBefore(t)
        val atrPct = if (px.isFinite() && px > 0 && x.atr.isFinite()) x.atr / px * 100 else NAN
        var v = NAN
        x.vix?.let { a -> for (r in minOf(t, a.size - 1) downTo 0) if (!a[r].isNaN()) { v = a[r]; break } }
        val vchg = if (v.isFinite() && x.vixPrev.isFinite() && x.vixPrev > 0) (v / x.vixPrev - 1) * 100 else NAN
        val own = doubleArrayOf(atrPct, t.toDouble(), x.dte, v, vchg, pos(x.pcr5), pos(x.ivAtm), x.ivSkew)
        val idx = DoubleArray(UNDERLYINGS.size) { if (UNDERLYINGS[it] == x.underlying) 1.0 else 0.0 }
        return b + own + idx
    }

    /** A chain reading that is not positive is missing (r57data keeps PCR and IV only when > 0). */
    private fun pos(x: Double) = if (x.isFinite() && x > 0) x else NAN

    private val SIGNED = setOf("ret_15m", "ret_60m", "ret_day", "gap_pct", "dist_tpavg_pct", "dist_prev_close_pct", "or30_state")

    /** The 29 inputs of a candidate (r57model.row_a): the base at its signal row, directional ones signed by its side, then side and setup. */
    fun features(d: PassedArms.DayBars, c: Candidate, x: Context): DoubleArray {
        val b = base(d, c.signalRow, x)
        val s = c.side.toDouble()
        val out = DoubleArray(FEATURES.size)
        for (i in 0 until 24) {
            val n = FEATURES[i]
            out[i] = when {
                n in SIGNED -> b[i] * s
                n == "pos_in_day_range" -> if (b[i].isNaN()) NAN else if (s > 0) 1.0 - b[i] else b[i]
                else -> b[i]
            }
        }
        out[24] = s
        for ((k, st) in Setup.entries.withIndex()) out[25 + k] = if (c.setup == st) 1.0 else 0.0
        return out
    }

    /** Sessions from [today] to the nearest listed expiry on or after it, counting trading days ([isTradingDay]); NaN: none listed. */
    fun dte(today: LocalDate, expiries: Collection<LocalDate>, isTradingDay: (LocalDate) -> Boolean): Double {
        val e = expiries.filter { !it.isBefore(today) }.minOrNull() ?: return NAN
        var n = 0
        var d = today
        while (d.isBefore(e)) { d = d.plusDays(1); if (isTradingDay(d)) n++ }
        return n.toDouble()
    }

    /** One option of the chain as the phone holds it: its [strike], CE / PE ([call]), last price and open interest (null: none). */
    data class Leg(val strike: Double, val call: Boolean, val ltp: Double?, val oi: Long?)

    /** The chain's context inputs (r57data): put/call OI of ATM-5..+5, the ATM straddle's mean IV and the 3-strike skew, in %. */
    data class Chain(val pcr5: Double, val ivAtm: Double, val ivSkew: Double)

    /**
     * R57's chain context from the [legs] the phone holds (the lake's rolling ATM is the strike nearest [spot]): PCR = put OI /
     * call OI over ATM-5..+5 steps ([step]); ATM IV = the mean of the ATM call's and put's implied volatility; skew = the put's
     * IV 3 steps below ATM minus the call's 3 steps above (in %). The phone has no vendor IV, so it is Black-76 on the last price
     * with the spot as the forward, no rate, [years] to expiry. A part that cannot be read is NaN.
     */
    fun chainContext(spot: Double, step: Int, legs: List<Leg>, years: Double): Chain {
        if (!(spot > 0) || legs.isEmpty()) return Chain(NAN, NAN, NAN)
        val atm = legs.map { it.strike }.distinct().minByOrNull { abs(it - spot) }!!
        fun near(k: Double, n: Int) = abs(k - atm) <= n * step + 1e-9
        val ce = legs.filter { it.call && near(it.strike, 5) }.sumOf { it.oi ?: 0L }
        val pe = legs.filter { !it.call && near(it.strike, 5) }.sumOf { it.oi ?: 0L }
        val pcr = if (ce > 0 && pe > 0) pe.toDouble() / ce else NAN
        fun iv(k: Double, call: Boolean): Double {
            val l = legs.firstOrNull { abs(it.strike - k) < 1e-9 && it.call == call } ?: return NAN
            val px = l.ltp ?: return NAN
            if (!(px > 0) || !(years > 0)) return NAN
            val t = if (call) com.optionslab.engine.options.OptionType.CE else com.optionslab.engine.options.OptionType.PE
            return com.optionslab.engine.options.OptionMath.impliedVol(px, t, spot, k, years)?.let { it * 100 } ?: NAN
        }
        val ivc = iv(atm, true); val ivp = iv(atm, false)
        val ivAtm = if (ivc.isNaN() || ivp.isNaN()) NAN else (ivc + ivp) / 2
        val skew = iv(atm - 3 * step, false) - iv(atm + 3 * step, true)
        return Chain(pcr, ivAtm, skew)
    }

    // ---- words -----------------------------------------------------------------------------------------------------------

    fun why(c: Candidate, score: Double, model: Model): String =
        "${c.underlying} ${if (c.side > 0) "call" else "put"} from ${c.setup.key} (${c.setup.words}): score %.4f, at or above %.4f".format(java.util.Locale.ENGLISH, score, model.threshold)

    /** The forward-test card lines (SoloMidday's bar: 60 trades, -Rs 25,000). */
    fun bar(): String = "The bar (set in advance): after ${SoloMidday.FORWARD_TRADES} trades it must make more than ₹0 a trade, and it switches " +
        "itself off if it is ever more than ${SoloMidday.rs(SoloMidday.FORWARD_MAX_DRAWDOWN)} below its best; switching it back on is your choice"
}
