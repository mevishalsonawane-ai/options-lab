package com.optionslab.engine.orb

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * "R72 picker (shadow)" (6 Nov 2026, Boss: "R72 picker (shadow log)"): research R151's logistic trade picker for R72 Breadth
 * Donchian (research/HUNT_R151_DONCHIAN_WINRATE60.md section 2b; model R72-20-4-M2, threshold [THRESHOLD] fixed in Amendment C).
 * It FAILED its gate (Apr - Oct 2026: 652 trades won 48.9%, the bar was 50%). 6 Nov (Boss: "I am the owner, i want you to skip
 * the rule, and i want live"): Boss's exception (CLAUDE.md) - it ACTS on paper and live: a break it scores under [THRESHOLD] is
 * skipped (kept as a would-be trade). It only ever skips (never adds or sizes); one it cannot score is taken.
 *
 *   the model  L2 logistic (C 0.1) on 28 features, missing ones filled with the training median, then standardised
 *              ([MODEL]: research/hunt/r151_donchian_winrate60/results/r72_picker_model.json,
 *              written by export_picker.py from the model that reproduces Amendment C exactly).
 *   features   research/hunt/r151_donchian_winrate60/study.py `features`, line for line, from what is known when the signal bar
 *              closes (minute rows below the entry row [Input.er]); held to Python by R72PickerParityTest on three real days.
 *
 * Pure: no clock, no network, no storage, no orders.
 */
object R72Picker {
    const val LABEL = "R72 picker"
    const val RESEARCH_ID = "R151 Amendment C (R72-20-4-M2)"
    /** Take when the score is at or above this (Amendment C, the point quoted to Boss from the design curve). */
    const val THRESHOLD = 0.4656
    const val STATUS = "failed gates, Boss's exception"
    const val RECORD = "Research R151 Amendment C, Apr - Oct 2026 (2nd look): 652 trades, 48.9% won (gate 50%: failed), +₹2,19,057 " +
        "vs R72's +₹1,71,366 (1,202 trades); per trade ₹336 vs ₹143; worst month −₹8.3k vs −₹40.9k; drawdown ₹29.2k vs ₹61.9k"
    const val RULE = "every R72 break is scored by R151's logistic picker; one scoring under 0.4656 is skipped, on paper and live " +
        "(kept as a would-be trade, never sent to Zerodha); one it cannot score yet is taken as before"

    /** The five indices R72 reads together (breadth5, f_breadth). */
    val INDICES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
    /** Earlier sessions the features read: 20 for the S/R window and the range average, a few more for the EMA's warm-up. */
    const val HIST_SESSIONS = 25
    /** Daily candles f_rv needs (20-session vol ranked among the 250 before): 271; with room for holidays. */
    const val DAILY_SESSIONS = 300
    /** The least history the picker scores from (what the research always had): the S/R window and range average's 20 sessions
     * and today; f_rv's 271 daily closes; VIX's regime, 61. */
    const val MIN_SESSIONS = 21
    const val MIN_DAILY = 271
    const val MIN_VIX_DAILY = 61

    val FEATS = listOf("tod", "side", "day_move", "from_open", "range_ratio", "gap", "twap_dist", "ema_slope", "ema_dist", "bar_size",
        "break_size", "breaks_today", "ret5d", "sr_ahead", "sr_behind", "sr_touch_ahead", "sr_crossed", "piv_ahead", "vix", "dvix",
        "breadth5", "f_rv", "f_gap", "f_vixchg", "f_breadth", "f_dow", "f_count", "f_vixreg")
    /** R72-20-4-M2 (results/r72_picker_model.json). */
    val MODEL = Model(
        median = doubleArrayOf(150.0, 1.0, 0.0035795598201211476, 0.002038765254845698, 0.6886626638198432, 0.0008175398043315241,
        0.0021306766263454735, 0.0004901235550675433, 0.0024852560483146447, 1.614585737364515, 0.0006474160507453316, 1.0,
        0.003296818741822305, 0.002022462590201113, 0.0018025621751652787, 7.0, 0.0, 0.0025124256752460106, 13.51, 0.005791521979588825,
        0.4, 0.356, 0.0004589928235019247, 0.005032361851600831, 5.0, 2.0, 4.0, 2.0),
        mean = doubleArrayOf(154.9967437316835, 0.015955714750895474, 0.0036127365936460707, 0.002399937910342995,
        0.7395070776617516, 0.0012138059227249965, 0.0023781271866017187, 0.0006525279103530064, 0.0034498769719174256, 2.185900096875879,
        0.0015264785662875552, 1.2692933897753176, 0.0038866802594901963, 0.010631249312246342, 0.004575627458191103, 8.904916965157929,
        0.298599804623901, 0.00899968047652664, 13.980937805275223, 0.010535179998816955, 0.5043308368609596, 0.42211917942038246,
        0.00014705397955315418, 0.01164685956057948, 4.612504070335396, 2.0524259198957995, 4.47704330836861, 1.000325626831651),
        scale = doubleArrayOf(116.28992833958644, 0.9998726994806666, 0.00732368927460415, 0.00424127561605725,
        0.35364510760048185, 0.0064773054232207285, 0.0021581919998758356, 0.0005891170120044025, 0.003480857413416736, 1.8371336900054402,
        0.003242177357538386, 1.646196326894377, 0.020434326201978797, 0.018124928632846815, 0.009916203746730141, 7.837868560608332,
        0.45764392414024013, 0.016092844095322974, 3.261414247114277, 0.05317077925679518, 0.4423717198004967, 0.32641166221057605,
        0.007009676578713278, 0.05932645834691269, 1.1022134923752318, 1.5035569925110013, 0.4994727122769977, 0.9999999469836016),
        coef = doubleArrayOf(-0.08027770792375717, -0.01196682539925786, -0.08851984826305802, -0.08188143935955194,
        -0.025462150197724468, -0.03199251294244068, 0.1439023590127, -0.12064720018817085, -0.00419025247632626, 0.05026111844870985,
        0.03217099040382738, -0.07435235950570358, 0.026410733970102487, 0.06951177857882593, 0.0022053690269226533, -0.027500305083412214,
        -0.004366711200744361, 0.09269804581025348, 0.18620647842260254, -0.0568002548206924, 0.03090922206669158, -0.10800968957790316,
        0.02796771690870808, 0.18244517917606703, -0.050360102925355435, 0.11198230799006502, 0.06119536633474934, -0.025803038146987235),
        intercept = -0.13101807454805045,
        threshold = THRESHOLD)

    // The research's constants (study.py / R150 levels.py / R101 r101feat.py).
    private const val EMA_N = 20
    private const val PIV_K = 6                 // R150 B: 5-min pivots, 6 bars each side
    private const val SR_WINDOW = 20            // sessions of levels (today and the 19 before)
    private const val SR_TOL = 0.0015
    private const val SR_MIN_TOUCHES = 3
    private const val NONE_DIST = 0.05          // a distance with no level / pivot on that side
    private const val RV_N = 20
    private const val RV_RANK_N = 250
    private const val VIX_MEDIAN_N = 60

    /** A daily candle's open and close (sessions before today). */
    class Daily(val date: LocalDate, val open: Double, val close: Double)

    /**
     * One index's data at the decision: [hist5] its earlier sessions' 5-minute bars ([bars5], oldest first; today's are built
     * from [today]); [today] its minutes so far; [daily] its daily candles before today; [todayOpen] today's session open (the
     * research's daily file: the 09:15 open).
     */
    class Sym(val hist5: List<PassedArms.Bar5>, val today: PassedArms.DayBars?, val daily: List<Daily>, val todayOpen: Double)

    /**
     * One R72 break to score: [und] / [side] (+1 call, -1 put) of [date], entering at row [er] (5 x (signal bar + 1));
     * [earlier] the arm's breaks on this index earlier today; [fCount] how many of the five broke that way (4 or 5); [syms] the
     * five indices; [vixToday] India VIX's minutes today, [vixPrevClose] its last close of the session before, [vixDaily] its
     * daily candles before today.
     */
    class Input(val und: String, val date: LocalDate, val er: Int, val side: Int, val earlier: Int, val fCount: Int,
                val syms: Map<String, Sym>, val vixToday: PassedArms.DayBars?, val vixPrevClose: Double, val vixDaily: List<Daily>)

    /**
     * The research's 5-minute bars of [d] (bars15, minutes = 5): every bar whose last row is below [er] and that has a close. The
     * same as R51's own [PassedArms.bars5] through row er - 1 (one implementation, so the picker's bars are R72's).
     */
    fun bars5(d: PassedArms.DayBars, er: Int = PassedArms.Session.N): List<PassedArms.Bar5> = PassedArms.bars5(d, er - 1)

    /** The 20-bar EMA of [v] (study_d `ema`: seeded by the first finite value). */
    fun ema(v: DoubleArray, n: Int = EMA_N): DoubleArray {
        val a = 2.0 / (n + 1)
        var m = Double.NaN
        return DoubleArray(v.size) { i -> m = if (m.isNaN()) v[i] else a * v[i] + (1 - a) * m; m }
    }

    /** R150's pivots: bar j is a high (low) pivot when its high (low) is the extreme of bars j-k .. j+k. Prices, oldest first. */
    fun pivotPrices(bars: List<PassedArms.Bar5>, first: Int, k: Int = PIV_K): List<Double> {
        val out = ArrayList<Double>()
        for (j in k until bars.size - k) {
            val w = (j - k..j + k)
            if (j >= first && w.all { bars[j].high >= bars[it].high }) out += bars[j].high
            if (j >= first && w.all { bars[j].low <= bars[it].low }) out += bars[j].low
        }
        return out
    }

    /** R150's `group`: sorted prices, each joining the current group when within [tol] of its running mean. (level, touches). */
    fun group(prices: List<Double>, tol: Double = SR_TOL): List<Pair<Double, Int>> {
        val out = ArrayList<Pair<Double, Int>>()
        val cur = ArrayList<Double>()
        for (p in prices.sorted()) {
            if (cur.isNotEmpty() && abs(p / cur.average() - 1) <= tol) cur += p
            else { if (cur.isNotEmpty()) out += cur.average() to cur.size; cur.clear(); cur += p }
        }
        if (cur.isNotEmpty()) out += cur.average() to cur.size
        return out
    }

    /**
     * R150 B's 5-minute levels known after the last bar of [all] (the earlier sessions' bars, then today's closed ones): pivots
     * (6 bars each side) confirmed by then, of today's and the 19 earlier sessions, grouped within 0.15%. (level, touches).
     */
    fun levels(all: List<PassedArms.Bar5>): List<Pair<Double, Int>> {
        val dates = all.map { it.date }.distinct()
        val winStart = dates.getOrNull(maxOf(0, dates.size - SR_WINDOW)) ?: return emptyList()
        val first = all.indexOfFirst { it.date >= winStart }.coerceAtLeast(0)
        return group(pivotPrices(all, first))
    }

    /** Floor pivots of a session's high, low and close: P, R1, S1, R2, S2. */
    fun floorPivots(h: Double, l: Double, c: Double): List<Double> {
        val p = (h + l + c) / 3
        return listOf(p, 2 * p - l, 2 * p - h, p + (h - l), p - (h - l))
    }

    /** R101's rv_rank on [closes] (sessions before today, oldest first): the 20-session vol's share at or below it among the 250 before. */
    fun rvRank(closes: List<Double>, n: Int = RV_N, rankN: Int = RV_RANK_N): Double {
        if (closes.size < n + rankN + 1) return Double.NaN
        val r = DoubleArray(closes.size - 1) { ln(closes[it + 1] / closes[it]) }
        val rv = DoubleArray(r.size - n + 1) { k -> std(r, k, k + n) }
        val cur = rv.last()
        if (cur.isNaN()) return Double.NaN
        val hist = rv.copyOfRange(rv.size - rankN - 1, rv.size - 1)
        return hist.count { it <= cur }.toDouble() / rankN
    }

    private fun std(a: DoubleArray, from: Int, to: Int): Double {
        val m = (from until to).sumOf { a[it] } / (to - from)
        return sqrt((from until to).sumOf { (a[it] - m) * (a[it] - m) } / (to - from - 1))
    }

    /** R101's vix_regime as the model's number: 0 (LO: the last close at or below the median of the 60 before it), 2 (HI), NaN. */
    fun vixRegime(closes: List<Double>, n: Int = VIX_MEDIAN_N): Double {
        if (closes.size < n + 1) return Double.NaN
        val prev = closes.last()
        val w = closes.subList(closes.size - n - 1, closes.size - 1).sorted()
        val med = if (n % 2 == 1) w[n / 2] else (w[n / 2 - 1] + w[n / 2]) / 2
        if (prev.isNaN() || med.isNaN()) return Double.NaN
        return if (prev <= med) 0.0 else 2.0
    }

    private fun closeAt(d: PassedArms.DayBars?, row: Int): Double = d?.closeAtOrBefore(maxOf(row, 0)) ?: Double.NaN

    /** Every feature of [x], in [FEATS]' order (NaN: unknown, as the research's). */
    fun features(x: Input): DoubleArray {
        val s = x.side.toDouble()
        val me = x.syms[x.und]
        val today = me?.today?.through(x.er - 1)
        val hist = me?.hist5.orEmpty()
        val sessions = hist.groupBy { it.date }.toSortedMap().values.toList()
        val price = closeAt(today, x.er - 1)
        val prev = sessions.lastOrNull()?.last()?.close ?: Double.NaN
        val o0 = today?.let { t -> (4 downTo 0).map { t.o[it] }.firstOrNull { !it.isNaN() } } ?: Double.NaN
        val rowsNow = (0 until x.er).filter { today != null && !today.c[it].isNaN() }
        val hi = today?.let { t -> (0 until x.er).map { t.h[it] }.filter { !it.isNaN() }.maxOrNull() } ?: Double.NaN
        val lo = today?.let { t -> (0 until x.er).map { t.l[it] }.filter { !it.isNaN() }.minOrNull() } ?: Double.NaN
        val rngs = sessions.takeLast(20).map { ses -> ses.maxOf { it.high } - ses.minOf { it.low } }
        val tps = rowsNow.map { (today!!.h[it] + today.l[it] + today.c[it]) / 3 }.filter { !it.isNaN() }
        val twap = if (tps.isEmpty()) Double.NaN else tps.average()
        val all = hist + (today?.let { bars5(it, x.er) } ?: emptyList())
        val f = DoubleArray(FEATS.size) { Double.NaN }
        fun set(name: String, v: Double) { f[FEATS.indexOf(name)] = v }
        if (all.size >= 25) {
            val j = all.size - 1
            val e = ema(DoubleArray(all.size) { all[it].close })
            set("ema_slope", s * (e[j] - e[j - 3]) / price)
            set("ema_dist", s * (all[j].close - e[j]) / price)
            val rng5 = (j - 20 until j).map { all[it].high - all[it].low }.average()
            set("bar_size", (all[j].high - all[j].low) / maxOf(rng5, 1e-9))
            val edge = if (s > 0) (j - 20 until j).maxOf { all[it].high } else (j - 20 until j).minOf { all[it].low }
            set("break_size", s * (all[j].close - edge) / price)
        }
        val p5 = if (sessions.size >= 5) sessions[sessions.size - 5].last().close else Double.NaN
        val lv = levels(all)
        val strong = lv.filter { it.second >= SR_MIN_TOUCHES }
        val ahead = strong.filter { s * (it.first - price) > 0 }.map { abs(it.first / price - 1) }
        val behind = strong.filter { s * (it.first - price) <= 0 }.map { abs(it.first / price - 1) }
        val nearest = ahead.minOrNull()
        val touches = strong.firstOrNull { s * (it.first - price) > 0 && abs(it.first / price - 1) == nearest }?.second
        val prev5 = closeAt(today, x.er - 6)
        val crossed = !prev5.isNaN() && strong.any { (l, _) -> if (x.side > 0) prev5 <= l && l < price else price < l && l <= prev5 }
        val last = sessions.lastOrNull()
        val piv = if (last == null || last.last().close.isNaN()) emptyList()
            else floorPivots(last.maxOf { it.high }, last.minOf { it.low }, last.last().close)
        val pivAhead = piv.filter { s * (it - price) > 0 }.map { abs(it / price - 1) }
        val vnow = closeAt(x.vixToday?.through(x.er - 1), x.er - 1)
        val dvix = if (!vnow.isNaN() && !x.vixPrevClose.isNaN() && x.vixPrevClose > 0) ln(vnow / x.vixPrevClose) else Double.NaN
        val ups = INDICES.mapNotNull { u ->
            val y = x.syms[u] ?: return@mapNotNull null
            val pc = y.hist5.lastOrNull()?.close ?: return@mapNotNull null
            val now = closeAt(y.today, x.er - 1)
            if (y.today == null || pc.isNaN() || now.isNaN()) null else if (now > pc) 1.0 else 0.0
        }
        set("tod", x.er.toDouble()); set("side", s)
        set("day_move", s * (price / prev - 1)); set("from_open", s * (price / o0 - 1))
        set("range_ratio", if (rngs.isEmpty()) Double.NaN else (hi - lo) / rngs.average())
        set("gap", s * (o0 / prev - 1)); set("twap_dist", s * (price / twap - 1))
        set("breaks_today", x.earlier.toDouble()); set("ret5d", s * (price / p5 - 1))
        set("sr_ahead", nearest ?: NONE_DIST); set("sr_behind", behind.minOrNull() ?: NONE_DIST)
        set("sr_touch_ahead", touches?.toDouble() ?: 0.0); set("sr_crossed", if (crossed) 1.0 else 0.0)
        set("piv_ahead", pivAhead.minOrNull() ?: NONE_DIST)
        set("vix", vnow); set("dvix", dvix)
        set("breadth5", if (ups.isEmpty()) Double.NaN else ups.average())
        // R101's candidate features (r101feat.py): the daily file, VIX's minutes, the five indices' bar colours.
        set("f_rv", rvRank(me?.daily.orEmpty().map { it.close }))
        val pc = me?.daily?.lastOrNull()?.close ?: Double.NaN
        val open = me?.todayOpen ?: Double.NaN
        set("f_gap", if (pc.isNaN() || open.isNaN() || pc <= 0) Double.NaN else open / pc - 1)
        val vo = x.vixToday?.o?.get(0) ?: Double.NaN
        val vc = x.vixToday?.c?.getOrNull(x.er - 1) ?: Double.NaN
        set("f_vixchg", if (vo.isNaN() || vc.isNaN() || vo <= 0) Double.NaN else vc / vo - 1)
        val b = (x.er - 1) / 5
        // A bar unknown does not agree (the research's rule); no index's bar known at all: unknown (the research always had them).
        val colours = INDICES.mapNotNull { u -> x.syms[u]?.today?.let { t -> bars5(t, x.er).firstOrNull { it.b == b } } }
        set("f_breadth", if (colours.isEmpty()) Double.NaN
            else colours.count { kotlin.math.sign(it.close - it.open).toInt() == x.side }.toDouble())
        set("f_dow", (x.date.dayOfWeek.value - 1).toDouble())
        set("f_count", if (x.fCount > 0) x.fCount.toDouble() else Double.NaN)       // R51's breaks have none (0)
        set("f_vixreg", vixRegime(x.vixDaily.map { it.close }))
        return f
    }

    /**
     * One arm's frozen logistic picker: missing features filled with the training [median], standardised by [mean] / [scale], then
     * [coef] and [intercept]; a break is taken at a score of [threshold] or more.
     */
    class Model(val median: DoubleArray, val mean: DoubleArray, val scale: DoubleArray, val coef: DoubleArray, val intercept: Double,
                val threshold: Double) {
        init { require(listOf(median, mean, scale, coef).all { it.size == FEATS.size }) { "R72Picker.Model: want ${FEATS.size} numbers each" } }

        /** The probability for [f] (features in [FEATS]' order). */
        fun score(f: DoubleArray): Double {
            require(f.size == FEATS.size) { "R72Picker.score: ${f.size} features, want ${FEATS.size}" }
            var z = intercept
            for (i in f.indices) z += coef[i] * ((if (f[i].isNaN()) median[i] else f[i]) - mean[i]) / scale[i]
            return 1.0 / (1.0 + exp(-z))
        }

        /** Whether a break scoring [score] is taken at [at] (the model's own [threshold] unless another is given). */
        fun takes(score: Double, at: Double = threshold): Boolean = score >= at - 1e-12
    }

    /** R72's model's probability for [f]. */
    fun score(f: DoubleArray): Double = MODEL.score(f)

    /** Whether the picker would take a break scoring [score]. */
    fun takes(score: Double): Boolean = MODEL.takes(score)

    /** One index's part of the shadow log's line for a bar: its score and the decision. */
    fun part(und: String, score: Double): String =
        "$und ${"%.3f".format(java.util.Locale.ENGLISH, score)} ${if (takes(score)) "would take" else "would skip"}"
}
