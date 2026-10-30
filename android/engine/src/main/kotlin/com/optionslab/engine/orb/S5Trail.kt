package com.optionslab.engine.orb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "S5 Trail" (28 Oct 2026, Boss: "Add s5 trail to arms directly, if it does not perform I will turn it off"): research R123's
 * morning pick (N1-S5) with R128's trailing exit (T2-S5x1). A PAPER arm that FAILED the research gates: Boss's exception
 * (CLAUDE.md, 28 Oct), labelled [NOT_PROVEN] wherever it is shown. 30 Oct (Boss): live like every arm, only through the live gates.
 *
 *   signal   at the 09:20, 09:25 and 09:30 bar closes ([DECISION_ROWS]) on each of the five indices, R123's network N1 (a causal
 *            TCN over the last 120 minutes of seven index channels, plus an MLP over 42 inputs: the index, India VIX, breadth
 *            over the five indices, the calendar and the option chain's state) gives, for 8 target/stop/time cells and both
 *            sides, P(+X first), P(-Y first) and P(neither). EV = P(win) x X - P(loss) x Y + P(neither) x the training mean
 *            net of 'neither' trades ([Model.ev]). The best cell-side is taken when its EV is at least Rs 300 ([pick]).
 *            The signal is read from the index (the default); the option chain is context only.
 *   trade    buy that side's ATM option, 1 lot, at the next minute's open. Exits ([Trail]): the chosen cell's stop (-Rs 1,000
 *            or -Rs 2,000 net, resting in the book); once the trade is up Rs 1,000 net, out when it falls to 60% of its best
 *            (marked at minute closes); else the square-off (15:20; 15:05 on expiry days). One open trade per index; at most
 *            5 a day.
 *   record   R128 T2-S5x1: walk-forward 2023 - Mar 2026 555 trades, 59% won, +Rs 240 a trade; Apr - Oct 2026 41 trades, 83%
 *            won, +Rs 561. 86% of the trades were in 2024; the worst month (-Rs 18.6k) breaks Boss's -Rs 13k month stop.
 *
 * The network ships as assets s5/s5net.bin + s5/s5net.json (research/hunt/r128_missing/export_s5.py; R123's holdout fit,
 * reproduced to 1.4e-7 before export). Implied volatility: the network learned Dhan's; the app computes its own (Black-76 on
 * the index, rate 0) - research/hunt/r128_missing/s5_app_check.py measured what that changes.
 *
 * Pure: no clock, no network, no storage, no orders.
 */
object S5Trail {
    const val SOURCE = "s5trail"
    const val LABEL = "S5 Trail"
    const val OWNER = "s5trail"
    const val RESEARCH_ID = "R123 N1-S5 + R128 T2-S5x1"
    const val NOT_PROVEN = "Failed research gates (R128): Boss's exception - live only through the live gates (Boss, 30 Oct)"
    const val SIGNAL = "Signal: the index (the default); the option chain is context only; the ATM option is what is bought"
    const val RULES = "Each morning at 09:20, 09:25 and 09:30 on NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, R123's network " +
        "scores 8 target/stop cells on both sides and buys the ATM option of the best one when its expected net is at least ₹300, " +
        "1 lot; stop at the cell's −₹1,000 or −₹2,000 net; once up ₹1,000 net, out when it falls to 60% of its best; else 15:20 " +
        "(15:05 on expiry day); paper only"
    const val RECORD = "R128 backtest: walk-forward 555 trades, 59% won, +₹240 a trade; Apr–Oct 2026 41 trades, 83% won, +₹561 a trade. " +
        "86% of the trades were in 2024, and its worst month (−₹18.6k) breaks the −₹13k month stop"
    const val ASSET_BIN = "s5/s5net.bin"
    const val ASSET_META = "s5/s5net.json"
    val ARM = Arm(SOURCE, LABEL, paperOnly = true)

    val UNDERLYINGS = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
    /** The lots the network learned its rupee inputs and EVs with (R123 K.LOT, today's lots on 28 Oct). */
    val RESEARCH_LOT = mapOf("NIFTY" to 65, "BANKNIFTY" to 35, "FINNIFTY" to 60, "MIDCPNIFTY" to 120, "SENSEX" to 20)
    /** Decisions at the closes of the 09:19, 09:24 and 09:29 bars (09:20, 09:25, 09:30); entry at the next minute's open. */
    val DECISION_ROWS = listOf(4, 9, 14)
    const val EV_MIN = 300.0
    const val DAY_MAX = 5
    const val LOCK_FROM = 1000.0
    const val LOCK_KEEP = 0.6
    const val SEQ_N = 120
    const val CLIP = 5.0
    const val N = PassedArms.Session.N
    /** The 8 cells (target X, stop Y, minutes) in R123's order; column j = 2 x cell + side (0 call, 1 put). */
    val CELLS = listOf(Triple(1000.0, 1000.0, 30), Triple(1000.0, 1000.0, 60), Triple(1000.0, 2000.0, 30), Triple(1000.0, 2000.0, 60),
        Triple(2000.0, 1000.0, 30), Triple(2000.0, 1000.0, 60), Triple(2000.0, 2000.0, 30), Triple(2000.0, 2000.0, 60))
    const val NJ = 16

    /** The 42 inputs in the network's order (r123data.TAB_COLS). */
    val TAB_COLS = UNDERLYINGS.map { "u_$it" } + listOf("tod", "weekday", "to_expiry", "is_expiry", "is_budget", "gap", "from_open",
        "ret5", "ret15", "ret30", "ret60", "rv30", "day_range", "dist_hi", "dist_lo", "prev_range", "prev_ret", "avg_range20",
        "vix", "vix_chg", "vix_chg30", "br_up", "br_from_open", "br_ret15",
        "atm_c_pct", "atm_p_pct", "strad_pct", "log_strad_rs", "log_c_rs", "log_p_rs", "atm_iv", "skew",
        "pcr", "pcr_chg15", "c_oi_chg15", "p_oi_chg15", "cp_vol15")

    fun step(underlying: String): Int = PassedArms.step(underlying)

    // ---- the network --------------------------------------------------------------------------------------------------

    /** One seed's weights (PyTorch layouts: conv weight [out][in][k], linear [out][in]). */
    class Seed(val inpW: FloatArray, val inpB: FloatArray, val convW: List<FloatArray>, val convB: List<FloatArray>,
               val tabW: FloatArray, val tabB: FloatArray, val t0W: FloatArray, val t0B: FloatArray, val t3W: FloatArray, val t3B: FloatArray,
               val aW: FloatArray, val aB: FloatArray, val bW: FloatArray, val bB: FloatArray, val cW: FloatArray, val cB: FloatArray)

    /** What the network says at one row: probs[j][0..2] = P(+X first), P(-Y first), P(neither); range (30, 60) log %; P(up). */
    class Output(val probs: Array<DoubleArray>, val range: DoubleArray, val up: DoubleArray)

    /** N1 (3 seeds) with its scalers and the training 'neither' means. */
    class Model(val seeds: List<Seed>, val tabMed: DoubleArray, val tabMu: DoubleArray, val tabSd: DoubleArray, val seqMu: DoubleArray,
                val seqSd: DoubleArray, val rangeMu: DoubleArray, val rangeSd: DoubleArray, val neither: Map<String, DoubleArray>,
                val sha256: String) {
        init {
            require(seeds.isNotEmpty()) { "no seeds" }
            require(tabMed.size == TAB_COLS.size && tabMu.size == TAB_COLS.size && tabSd.size == TAB_COLS.size) { "tab scalers" }
            require(seqMu.size == 5 && seqSd.size == 5) { "seq scalers" }
            require(UNDERLYINGS.all { neither[it]?.size == NJ }) { "neither means" }
        }

        /** The scaled tabular row: a missing input takes its training median; (x - mean) / sd, clipped to +-5. */
        fun scaleTab(raw: DoubleArray): DoubleArray {
            require(raw.size == TAB_COLS.size) { "expected ${TAB_COLS.size} inputs" }
            return DoubleArray(raw.size) { i -> clip(((if (raw[i].isFinite()) raw[i] else tabMed[i]) - tabMu[i]) / tabSd[i]) }
        }

        /** The scaled window: channels 0-4 (x - mean) / sd clipped and zeroed where the mask is 0; channels 5-6 as they are. */
        fun scaleSeq(raw: Array<DoubleArray>): Array<DoubleArray> {
            require(raw.size == SEQ_N && raw.all { it.size == 7 }) { "the window is $SEQ_N x 7" }
            return Array(SEQ_N) { t ->
                val m = if (raw[t][6] > 0) 1.0 else 0.0
                DoubleArray(7) { c -> if (c < 5) clip((raw[t][c] - seqMu[c]) / seqSd[c]) * m else raw[t][c] }
            }
        }

        /** The ensemble's average over the seeds on raw inputs (r123net.NetModel.predict). */
        fun predict(rawSeq: Array<DoubleArray>, rawTab: DoubleArray): Output {
            val s = scaleSeq(rawSeq)
            val x = scaleTab(rawTab)
            val p = Array(NJ) { DoubleArray(3) }
            val r = DoubleArray(2)
            val u = DoubleArray(2)
            for (seed in seeds) {
                val o = forward(seed, s, x)
                for (j in 0 until NJ) for (k in 0 until 3) p[j][k] += o.probs[j][k] / seeds.size
                for (k in 0 until 2) { r[k] += o.range[k] / seeds.size; u[k] += o.up[k] / seeds.size }
            }
            return Output(p, DoubleArray(2) { r[it] * rangeSd[it] + rangeMu[it] }, u)
        }

        /** EV per cell-side for [underlying] (r123rules.attach). */
        fun ev(underlying: String, o: Output): DoubleArray {
            val m = neither.getValue(underlying)
            return DoubleArray(NJ) { j -> val (x, y, _) = CELLS[j / 2]; o.probs[j][0] * x - o.probs[j][1] * y + o.probs[j][2] * m[j] }
        }
    }

    private fun clip(v: Double) = min(max(v, -CLIP), CLIP)

    /** One seed's forward pass on scaled inputs (r123net.make_net; eval mode, so dropout is off). */
    fun forward(w: Seed, seq: Array<DoubleArray>, tab: DoubleArray): Output {
        val t = seq.size
        var h = Array(32) { o -> DoubleArray(t) { i -> var a = w.inpB[o].toDouble(); for (c in 0 until 7) a += w.inpW[o * 7 + c] * seq[i][c]; a } }
        for ((li, dil) in listOf(1, 2, 4, 8).withIndex()) {
            val cw = w.convW[li]; val cb = w.convB[li]
            val nh = Array(32) { o ->
                DoubleArray(t) { i ->
                    var a = cb[o].toDouble()
                    for (k in 0 until 3) {
                        val src = i - (2 - k) * dil
                        if (src < 0) continue
                        for (c in 0 until 32) a += cw[(o * 32 + c) * 3 + k] * h[c][src]
                    }
                    h[o][i] + max(a, 0.0)
                }
            }
            h = nh
        }
        val enc = DoubleArray(96)
        for (o in 0 until 32) {
            enc[o] = h[o][t - 1]
            var s15 = 0.0; for (i in t - 15 until t) s15 += h[o][i]; enc[32 + o] = s15 / 15
            enc[64 + o] = h[o].sum() / t
        }
        val tb = dense(w.tabW, w.tabB, tab, relu = true)
        val z1 = dense(w.t0W, w.t0B, enc + tb, relu = true)
        val z = dense(w.t3W, w.t3B, z1, relu = true)
        val la = dense(w.aW, w.aB, z, relu = false)
        val probs = Array(NJ) { j ->
            val a = la[j * 3]; val b = la[j * 3 + 1]; val c = la[j * 3 + 2]
            val mx = max(a, max(b, c))
            val ea = exp(a - mx); val eb = exp(b - mx); val ec = exp(c - mx); val s = ea + eb + ec
            doubleArrayOf(ea / s, eb / s, ec / s)
        }
        val rg = dense(w.bW, w.bB, z, relu = false)
        val up = dense(w.cW, w.cB, z, relu = false).map { 1.0 / (1.0 + exp(-it)) }.toDoubleArray()
        return Output(probs, rg, up)
    }

    private fun dense(w: FloatArray, b: FloatArray, x: DoubleArray, relu: Boolean): DoubleArray {
        val n = b.size
        require(w.size == n * x.size) { "layer ${w.size} vs ${n}x${x.size}" }
        return DoubleArray(n) { o ->
            var a = b[o].toDouble()
            val base = o * x.size
            for (i in x.indices) a += w[base + i] * x[i]
            if (relu) max(a, 0.0) else a
        }
    }

    /** The model from the two assets: [meta] (s5net.json's text) and [bin] (s5net.bin's bytes). Any mismatch is an error. */
    fun parseModel(meta: String, bin: ByteArray): Model {
        @Suppress("UNCHECKED_CAST")
        val o = MiniJson.parse(meta) as? Map<String, Any?> ?: throw MiniJson.Error("not an object")
        require((o["tab_cols"] as List<*>) == TAB_COLS) { "the model's inputs are not R123's 42" }
        require((o["seq_n"] as Double).toInt() == SEQ_N) { "the window is not $SEQ_N minutes" }
        val floats = (o["floats"] as Double).toInt()
        require(bin.size == floats * 4) { "s5net.bin has ${bin.size} bytes, the layout says ${floats * 4}" }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bin).joinToString("") { "%02x".format(it) }
        require(sha == o["sha256"]) { "s5net.bin does not match its checksum" }
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val bySeed = HashMap<Int, HashMap<String, FloatArray>>()
        val extra = HashMap<String, DoubleArray>()
        for (e in o["layout"] as List<*>) {
            @Suppress("UNCHECKED_CAST") val m = e as Map<String, Any?>
            val a = FloatArray((m["n"] as Double).toInt()).also { bb.get(it) }
            val seed = (m["seed"] as Double?)?.toInt()
            if (seed == null) extra[m["name"] as String] = DoubleArray(a.size) { a[it].toDouble() }
            else bySeed.getOrPut(seed) { HashMap() }[m["name"] as String] = a
        }
        val seeds = bySeed.keys.sorted().map { k ->
            val s = bySeed.getValue(k)
            fun g(n: String) = s[n] ?: throw IllegalArgumentException("missing $n")
            Seed(g("inp.weight"), g("inp.bias"), (0 until 4).map { g("convs.$it.conv.weight") }, (0 until 4).map { g("convs.$it.conv.bias") },
                g("tab.0.weight"), g("tab.0.bias"), g("trunk.0.weight"), g("trunk.0.bias"), g("trunk.3.weight"), g("trunk.3.bias"),
                g("head_a.weight"), g("head_a.bias"), g("head_b.weight"), g("head_b.bias"), g("head_c.weight"), g("head_c.bias"))
        }
        @Suppress("UNCHECKED_CAST") val nm = (o["neither_mean"] as Map<String, List<Double>>).mapValues { it.value.toDoubleArray() }
        fun x(n: String) = extra[n] ?: throw IllegalArgumentException("missing $n")
        return Model(seeds, x("tab.med"), x("tab.mu"), x("tab.sd"), x("seq.mu"), x("seq.sd"), x("range.mu"), x("range.sd"), nm, sha)
    }

    // ---- the pick -----------------------------------------------------------------------------------------------------

    /** The chosen cell-side: [j] (2 x cell + side), its EV, and the cell's target X, stop Y. */
    data class Pick(val underlying: String, val row: Int, val j: Int, val ev: Double) {
        val call: Boolean get() = j % 2 == 0
        val stopRs: Double get() = CELLS[j / 2].second
        val targetRs: Double get() = CELLS[j / 2].first
        val minutes: Int get() = CELLS[j / 2].third
    }

    /**
     * r123rules.candidates for S5: the best EV over the cell-sides whose trade can be filled ([fillable]: false for a side with
     * no option bar at the entry minute, or whose window passes the square-off), taken when it is at least Rs 300 and the row is
     * one of [DECISION_ROWS]. Null: no entry.
     */
    fun pick(underlying: String, row: Int, ev: DoubleArray, fillable: (Int) -> Boolean): Pick? {
        if (row !in DECISION_ROWS) return null
        var best = -1
        for (j in 0 until NJ) if (fillable(j) && ev[j].isFinite() && (best < 0 || ev[j] > ev[best])) best = j
        if (best < 0 || ev[best] < EV_MIN) return null
        return Pick(underlying, row, best, ev[best])
    }

    /** Whether cell-side [j]'s window fits before the square-off when entered at [entryRow] (r123data.trade_labels). */
    fun fitsWindow(j: Int, entryRow: Int, lastRow: Int): Boolean = entryRow - 1 + CELLS[j / 2].third <= lastRow

    /** The square-off row: 15:19's close (15:20); on expiry day 15:04's (15:05). */
    fun lastRow(expiryDay: Boolean): Int = if (expiryDay) PassedArms.Session.row(15, 4) else PassedArms.Session.row(15, 19)

    // ---- the exit -----------------------------------------------------------------------------------------------------

    /** A stop's fill slips this much under its trigger (research Fills.bps_stop; the paper book's SL-M slips the same). */
    const val STOP_BPS = 10.0

    /**
     * The stop's trigger premium (research r123data.target_stop): the premium at which a sell nets -[stopRs] after both legs'
     * charges ([charge] = one leg's charges at a premium, for the whole quantity), raised by [ticks] x 2 ticks and the stop fill's
     * [STOP_BPS] slippage so the FILL nets about -[stopRs], rounded UP to the tick. [ticks]: 1 for a liquid option (research: 5-minute
     * volume of 20 lots or more), 2 thin, 4 none. Null when it would be at or below one tick.
     */
    fun stopPremium(entry: Double, qty: Int, stopRs: Double, charge: (buy: Boolean, price: Double) -> Double, tick: Double = 0.05,
                    ticks: Int = 1): Double? {
        val cb = charge(true, entry)
        var s = entry - stopRs / qty
        repeat(3) { s = entry + (cb + charge(false, max(s, 0.0)) - stopRs) / qty }
        val sp = ceil((s + 2 * ticks * tick) / (1 - STOP_BPS / 1e4) / tick - 1e-9) * tick
        return if (sp > tick) Math.round(sp * 100) / 100.0 else null
    }

    /** Net of selling [qty] at [price] after buying at [entry]: both legs' charges ([charge] as in [stopPremium]). */
    fun netAt(entry: Double, price: Double, qty: Int, charge: (buy: Boolean, price: Double) -> Double): Double =
        (price - entry) * qty - charge(true, entry) - charge(false, price)

    /** The trailing lock (R128 x1): the best net seen at minute closes, and whether the latest close says "out". */
    data class Trail(val peak: Double = Double.NEGATIVE_INFINITY) {
        /** After a minute close at net [net]: the new state and whether to exit now. */
        fun onClose(net: Double): Pair<Trail, Boolean> {
            if (!net.isFinite()) return this to false
            val p = max(peak, net)
            return Trail(p) to (p >= LOCK_FROM && net <= LOCK_KEEP * p)
        }
        /** The net below which the lock exits (null until the trade has been up Rs 1,000). */
        val floor: Double? get() = if (peak >= LOCK_FROM) LOCK_KEEP * peak else null
    }

    // ---- the inputs ---------------------------------------------------------------------------------------------------

    /**
     * One index's sessions on the research grid, oldest first, today last ([PassedArms.DayBars]; today holds only rows up to the
     * decision). [byDay] is every index's sessions by day (the breadth and VIX sources), [vix] India VIX's by day.
     */
    class Market(val underlying: String, val sessions: List<PassedArms.DayBars>, val byDay: Map<String, Map<LocalDate, PassedArms.DayBars>>,
                 val vix: Map<LocalDate, PassedArms.DayBars>, past: List<Past>? = null) {
        init { require(sessions.isNotEmpty()) { "no sessions" } }
        /** The completed sessions before today, oldest first: [past] when given (older days from daily candles), else [sessions]. */
        val past: List<Past> = past ?: sessions.dropLast(1).map { Past.of(it) }
        val today: PassedArms.DayBars get() = sessions.last()
        val i: Int get() = sessions.size - 1
        private val others = UNDERLYINGS.filter { it != underlying }

        /** The flat minute grid of the last [k] sessions (k x 375 slots); slot (s, r) -> s * 375 + r. */
        private fun flat(k: Int, f: (PassedArms.DayBars) -> DoubleArray?): DoubleArray {
            val from = max(0, sessions.size - k)
            val out = DoubleArray((sessions.size - from) * N) { Double.NaN }
            for (s in from until sessions.size) f(sessions[s])?.copyInto(out, (s - from) * N)
            return out
        }

        private fun ffill(a: DoubleArray): DoubleArray {
            val o = a.copyOf(); var last = Double.NaN
            for (k in o.indices) if (o[k].isFinite()) last = o[k] else o[k] = last
            return o
        }

        /**
         * The raw 120 x 7 window ending at row [t] of today (r123data.IndexData.seq): ret, hl, from_open, vix_chg, breadth,
         * new_session, mask; slots before the first session are zeros (SeqStore's padding).
         */
        fun window(t: Int): Array<DoubleArray> {
            val k = min(sessions.size, 3)
            val from = sessions.size - k
            val c = flat(k) { it.c }
            val cf = ffill(c)
            val hgh = flat(k) { it.h }
            val lw = flat(k) { it.l }
            val o0 = DoubleArray(c.size) { sessions[from + it / N].firstOpen }
            val vc = flat(k) { vix[it.date]?.c }
            val vf = ffill(vc)
            val oth = others.map { u -> ffill(flat(k) { d -> byDay[u]?.get(d.date)?.c }) to flat(k) { d -> byDay[u]?.get(d.date)?.c } }
            val end = (k - 1) * N + t
            return Array(SEQ_N) { w ->
                val p = end - (SEQ_N - 1) + w
                if (p < 0) DoubleArray(7) else row(p, c, cf, hgh, lw, o0, vc, vf, oth)
            }
        }

        private fun row(p: Int, c: DoubleArray, cf: DoubleArray, h: DoubleArray, l: DoubleArray, o0: DoubleArray, vc: DoubleArray,
                        vf: DoubleArray, oth: List<Pair<DoubleArray, DoubleArray>>): DoubleArray {
            val prev = if (p > 0) cf[p - 1] else Double.NaN
            val ret = lr(c[p], prev)
            val hl = (h[p] - l[p]) / c[p] * 100
            val fo = lr(c[p], o0[p])
            val vch = lr(vc[p], if (p > 0) vf[p - 1] else Double.NaN)
            var bs = 0.0; var bn = 0
            for ((of, oc) in oth) { val b = lr(oc[p], if (p > 0) of[p - 1] else Double.NaN); if (b.isFinite()) { bs += b; bn++ } }
            val br = if (bn > 0) bs / bn else Double.NaN
            val mask = ret.isFinite() && hl.isFinite()
            val new = if (p % N == 0) 1.0 else 0.0
            fun z(v: Double) = if (!mask || !v.isFinite()) 0.0 else min(max(v, -50.0), 50.0)
            return doubleArrayOf(z(ret), z(hl), z(fo), z(vch), z(br), new, if (mask) 1.0 else 0.0)
        }

        /** The 24 index / VIX / breadth / calendar inputs at row [t] (r123data.tab_features without the chain). */
        fun tab(t: Int, cal: Calendar): Map<String, Double> {
            val f = LinkedHashMap<String, Double>()
            for (u in UNDERLYINGS) f["u_$u"] = if (u == underlying) 1.0 else 0.0
            val d = today.date
            f["tod"] = t.toDouble() / N
            f["weekday"] = (d.dayOfWeek.value - DayOfWeek.MONDAY.value) / 4.0
            f["to_expiry"] = cal.toExpiry?.let { min(it, 25).toDouble() } ?: Double.NaN
            f["is_expiry"] = if (cal.isExpiry) 1.0 else 0.0
            f["is_budget"] = if (cal.isBudget) 1.0 else 0.0
            val k = min(sessions.size, 3)
            val cf = ffill(flat(k) { it.c })
            val p = (k - 1) * N + t
            val c = cf[p]
            val prevDay = sessions.getOrNull(i - 1)
            val pc = prevDay?.lastClose ?: Double.NaN
            val o0 = today.firstOpen
            f["gap"] = lr(o0, pc)
            f["from_open"] = lr(c, o0)
            for (m in listOf(5, 15, 30, 60)) f["ret$m"] = if (p - m >= 0) lr(c, cf[p - m]) else Double.NaN
            val w = window(t)
            val rr = w.takeLast(30).filter { it[6] > 0 }.map { r -> r[0] }
            f["rv30"] = if (rr.size > 5) std(rr) else Double.NaN
            var hi = Double.NaN; var lo = Double.NaN
            for (r in 0..t) { if (today.h[r].isFinite()) hi = if (hi.isNaN()) today.h[r] else max(hi, today.h[r]); if (today.l[r].isFinite()) lo = if (lo.isNaN()) today.l[r] else min(lo, today.l[r]) }
            f["day_range"] = (hi - lo) / c * 100
            f["dist_hi"] = (hi - c) / c * 100
            f["dist_lo"] = (c - lo) / c * 100
            val ranges = past.map { it.range }
            f["prev_range"] = ranges.lastOrNull() ?: Double.NaN
            f["prev_ret"] = if (past.size > 1) lr(past[past.size - 1].close, past[past.size - 2].close) else Double.NaN
            f["avg_range20"] = ranges.takeLast(20).filter { it.isFinite() }.let { if (it.isEmpty()) Double.NaN else it.average() }
            val vt = vix[d]?.closeAtOrBefore(t) ?: Double.NaN
            val vprev = prevDay?.let { vix[it.date]?.lastClose } ?: Double.NaN
            f["vix"] = vt
            f["vix_chg"] = lr(vt, vprev)
            f["vix_chg30"] = if (t >= 30) lr(vt, vix[d]?.closeAtOrBefore(t - 30) ?: Double.NaN) else Double.NaN
            val ups = ArrayList<Double>(); val fos = ArrayList<Double>(); val r15 = ArrayList<Double>()
            for (u in others + underlying) {
                val day = if (u == underlying) today else byDay[u]?.get(d) ?: continue
                val cu = day.closeAtOrBefore(t)
                if (!cu.isFinite()) continue
                val prevC = prevDay?.let { if (u == underlying) it.lastClose else byDay[u]?.get(it.date)?.lastClose } ?: Double.NaN
                val ou = (0..t).map { day.c[it] }.firstOrNull { it.isFinite() } ?: Double.NaN
                if (prevC.isFinite()) ups += if (cu > prevC) 1.0 else 0.0
                fos += lr(cu, ou)
                if (t >= 15) r15 += lr(cu, day.closeAtOrBefore(t - 15))
            }
            f["br_up"] = if (ups.isEmpty()) Double.NaN else ups.average()
            f["br_from_open"] = fos.filter { it.isFinite() }.let { if (it.isEmpty()) Double.NaN else it.average() }
            f["br_ret15"] = r15.filter { it.isFinite() }.let { if (it.isEmpty()) Double.NaN else it.average() }
            return f
        }
    }

    /** A completed session's range in % of its last close, and that close (r123data.IndexData.d_range / d_close). */
    data class Past(val date: LocalDate, val range: Double, val close: Double) {
        companion object {
            fun of(d: PassedArms.DayBars) = Past(d.date, (d.high - d.low) / d.lastClose * 100, d.lastClose)
            /** From a daily candle (older days the minute history does not reach). */
            fun of(date: LocalDate, high: Double, low: Double, close: Double) = Past(date, (high - low) / close * 100, close)
        }
    }

    /** The calendar inputs: sessions to the next expiry after today (null: unknown), expiry day, budget day. */
    data class Calendar(val toExpiry: Int?, val isExpiry: Boolean, val isBudget: Boolean)

    /**
     * Today's chain on the research's offset grid: for each minute row and offset -10..+10 from THAT minute's ATM (the index
     * close of the row, half up to the strike step), each side's close, OI and volume (NaN: no bar), and the implied volatility
     * the app computes at that close ([iv]: price, spot, strike, row, call -> percent or NaN).
     */
    class Chain(val close: Array<Array<DoubleArray>>, val oi: Array<Array<DoubleArray>>, val volume: Array<Array<DoubleArray>>,
                val iv: Array<Array<DoubleArray>>) {
        companion object {
            const val OFFS = 21

            /**
             * From per-strike minute bars: [bars] (strike, call) -> rows -> (close, oi, volume) (NaN where no bar); [spot] the
             * index close per row (ffilled by the caller); [ivOf] the app's implied volatility in percent.
             */
            fun of(underlying: String, through: Int, spot: DoubleArray, bars: Map<Pair<Int, Boolean>, Array<DoubleArray>>,
                   ivOf: (price: Double, spot: Double, strike: Int, row: Int, call: Boolean) -> Double): Chain {
                val st = step(underlying)
                fun grid() = Array(through + 1) { Array(OFFS) { DoubleArray(2) { Double.NaN } } }
                val cl = grid(); val oi = grid(); val vol = grid(); val iv = grid()
                for (r in 0..through) {
                    val s = spot[r]
                    if (!s.isFinite()) continue
                    val atm = OrbRules.atmStrike(s, st)
                    for (off in -10..10) for (side in 0..1) {
                        val b = bars[(atm + off * st) to (side == 0)]?.getOrNull(r) ?: continue
                        cl[r][off + 10][side] = b[0]; oi[r][off + 10][side] = b[1]; vol[r][off + 10][side] = b[2]
                        if (b[0].isFinite() && b[0] > 0) iv[r][off + 10][side] = ivOf(b[0], s, atm + off * st, r, side == 0)
                    }
                }
                return Chain(cl, oi, vol, iv)
            }
        }

        /** The last finite value at or before [t] in each (offset, side) slot (r123data.ffill_rows, read at t). */
        private fun lastKnown(a: Array<Array<DoubleArray>>, t: Int): Array<DoubleArray> =
            Array(OFFS) { off -> DoubleArray(2) { side -> var v = Double.NaN; for (r in min(t, a.size - 1) downTo 0) if (a[r][off][side].isFinite()) { v = a[r][off][side]; break }; v } }

        /** The 13 chain inputs at row [t] (r123data.chain_features). */
        fun features(t: Int, lot: Int, spot: Double): Map<String, Double> {
            val f = LinkedHashMap<String, Double>()
            for (k in listOf("atm_c_pct", "atm_p_pct", "strad_pct", "log_strad_rs", "log_c_rs", "log_p_rs", "atm_iv", "skew", "pcr",
                    "pcr_chg15", "c_oi_chg15", "p_oi_chg15", "cp_vol15")) f[k] = Double.NaN
            if (!spot.isFinite()) return f
            val cl = lastKnown(close, t)
            val cc = cl[10][0]; val pp = cl[10][1]
            if (cc.isFinite() && cc > 0) { f["atm_c_pct"] = cc / spot * 100; f["log_c_rs"] = ln(cc * lot) }
            if (pp.isFinite() && pp > 0) { f["atm_p_pct"] = pp / spot * 100; f["log_p_rs"] = ln(pp * lot) }
            if (cc.isFinite() && pp.isFinite() && cc > 0 && pp > 0) { f["strad_pct"] = (cc + pp) / spot * 100; f["log_strad_rs"] = ln((cc + pp) * lot) }
            val ivt = lastKnown(iv, t)
            val atm = ivt[10].filter { it.isFinite() }
            f["atm_iv"] = if (atm.isEmpty()) Double.NaN else atm.average()
            f["skew"] = ivt[7][1] - ivt[13][0]
            val oit = lastKnown(oi, t)
            fun sum(a: Array<DoubleArray>, side: Int): Double { var s = 0.0; for (o in 5..15) if (a[o][side].isFinite()) s += a[o][side]; return s }
            val sc = sum(oit, 0); val sp = sum(oit, 1)
            f["pcr"] = if (sc > 0) sp / sc else Double.NaN
            if (t >= 15) {
                val o15 = lastKnown(oi, t - 15)
                val c15 = sum(o15, 0); val p15 = sum(o15, 1)
                if (c15 > 0 && p15 > 0 && sc > 0) {
                    f["pcr_chg15"] = f.getValue("pcr") - p15 / c15
                    f["c_oi_chg15"] = (sc / c15 - 1) * 100
                    f["p_oi_chg15"] = (sp / p15 - 1) * 100
                }
            }
            var vc = 0.0; var vp = 0.0
            for (r in max(t - 14, 0)..min(t, volume.size - 1)) for (o in 5..15) {
                if (volume[r][o][0].isFinite()) vc += volume[r][o][0]
                if (volume[r][o][1].isFinite()) vp += volume[r][o][1]
            }
            f["cp_vol15"] = ln((max(vc, 0.0) + 1) / (max(vp, 0.0) + 1))
            return f
        }
    }

    /** The 42 raw inputs in [TAB_COLS] order (NaN = missing). */
    fun inputs(m: Market, t: Int, cal: Calendar, chain: Chain?): DoubleArray {
        val f = m.tab(t, cal)
        val spot = m.today.closeAtOrBefore(t)
        val ch = chain?.features(t, RESEARCH_LOT.getValue(m.underlying), spot)
        return DoubleArray(TAB_COLS.size) { k -> val n = TAB_COLS[k]; f[n] ?: ch?.get(n) ?: Double.NaN }
    }

    /** Sessions after [today] up to and including [expiry] among [tradingDays] (r123data.build's to_expiry); null: none. */
    fun sessionsToExpiry(today: LocalDate, expiry: LocalDate?, tradingDays: List<LocalDate>): Int? =
        expiry?.let { e -> tradingDays.count { it > today && it <= e } }

    /** Calendar years from [row]'s close on [day] to 15:30 on [expiry] (the app's IV clock); 0 when past. */
    fun yearsToExpiry(day: LocalDate, row: Int, expiry: LocalDate): Double {
        val now = day.atTime(PassedArms.Session.time(row + 1))
        val end = expiry.atTime(15, 30)
        val s = java.time.Duration.between(now, end).seconds.toDouble()
        return max(s, 0.0) / (365.0 * 86400)
    }

    private fun lr(a: Double, b: Double): Double = if (a.isFinite() && b.isFinite() && a > 0 && b > 0) ln(a / b) * 100 else Double.NaN

    private fun std(v: List<Double>): Double { val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m) } / v.size) }

    /** Rounds [x] half up to [step] (r123data.atm). */
    fun atm(x: Double, step: Int): Int = (floor(x / step + 0.5) * step).toInt()
}
