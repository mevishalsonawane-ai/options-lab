package com.optionslab.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Panel information coefficient, with its null band and its controls attached.
 * Port of `ic.py` and the `ic` command.
 *
 * An IC reported alone is not evidence: [IcRow.format] refuses to print a raw
 * IC without its partial beside it, because that omission is exactly how PCR
 * and max pain look real.
 */
object Ic {
    const val NULL_DRAWS = 20
    const val DECILES = 10
    const val DEGENERATE_VAR_RATIO = 1e-12
    val HORIZONS = listOf(1, 5, 15, 30)
    val LOTS = mapOf("NIFTY" to 65, "BANKNIFTY" to 30)

    class UncontrolledIC(msg: String) : IllegalStateException(msg)

    data class IcRow(
        val feature: String, val horizon: Int, val nObs: Int, val nSessions: Int,
        val ic: Double, val dailyIcMean: Double, val dailyIcLo: Double, val dailyIcHi: Double,
        val icIidLo: Double, val icIidHi: Double, val nullIcHi: Double,
        val decileSpreadPts: Double, val breakevenPts: Double, val edgeOverCost: Double,
        val partialIc: Double, val controlled: Boolean, val singleLegEdgePts: Double,
        val nSessionsMeasured: Int,
    ) {
        val monotone: Boolean get() =
            !ic.isNaN() && !decileSpreadPts.isNaN() && Math.signum(ic) == Math.signum(decileSpreadPts)
        val clearsCost: Boolean get() = edgeOverCost >= 2.0 && monotone
        val beatsNull: Boolean get() = abs(dailyIcMean) > nullIcHi

        fun format(): String {
            if (!controlled) throw UncontrolledIC(
                "$feature: refusing to report a raw IC of %.4f with no partial beside it.".format(ic))
            return ("$feature h=$horizon n=$nObs sessions=$nSessionsMeasured/$nSessions ic=%+.4f partial_ic=%+.4f " +
                "daily[%+.4f,%+.4f] iid[%+.4f,%+.4f] null_hi=%.4f spread=%+.2fpts leg=%+.2fpts breakeven=%.2fpts " +
                "edge_over_cost=%.2fx").format(ic, partialIc, dailyIcLo, dailyIcHi, icIidLo, icIidHi, nullIcHi,
                decileSpreadPts, singleLegEdgePts, breakevenPts, edgeOverCost) +
                if (monotone) "" else " [NON-MONOTONE: tails disagree with IC]"
        }
    }

    /** Least-squares residual of y on controls plus an intercept (all-NaN if degenerate). */
    fun residualise(y: DoubleArray, controls: List<DoubleArray>): DoubleArray {
        val n = y.size
        val k = controls.size + 1
        val x = Array(n) { i -> DoubleArray(k) { j -> if (j == 0) 1.0 else controls[j - 1][i] } }
        val xtx = Array(k) { DoubleArray(k) }
        val xty = DoubleArray(k)
        for (i in 0 until n) for (a in 0 until k) {
            xty[a] += x[i][a] * y[i]
            for (b in 0 until k) xtx[a][b] += x[i][a] * x[i][b]
        }
        val beta = solve(xtx, xty)
        val resid = DoubleArray(n) { i -> y[i] - (0 until k).sumOf { x[i][it] * beta[it] } }
        val varY = variance(y)
        if (varY > 0 && variance(resid) / varY < DEGENERATE_VAR_RATIO) return DoubleArray(n) { Double.NaN }
        return resid
    }

    private fun variance(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        val m = v.average()
        return v.sumOf { (it - m) * (it - m) } / v.size
    }

    /** Gaussian elimination with partial pivoting; singular columns get a zero coefficient (min-norm-like). */
    private fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray {
        val n = b0.size
        val a = Array(n) { a0[it].copyOf() }
        val b = b0.copyOf()
        val piv = IntArray(n) { -1 }
        var row = 0
        for (col in 0 until n) {
            var best = row
            for (r in row until n) if (abs(a[r][col]) > abs(a[best][col])) best = r
            if (row >= n || abs(a[best][col]) < 1e-12 * (1 + abs(a[best][col]))) continue
            a[row] = a[best].also { a[best] = a[row] }
            b[row] = b[best].also { b[best] = b[row] }
            for (r in 0 until n) if (r != row) {
                val f = a[r][col] / a[row][col]
                if (f != 0.0) { for (c in col until n) a[r][c] -= f * a[row][c]; b[r] -= f * b[row] }
            }
            piv[col] = row
            row++
        }
        return DoubleArray(n) { c -> if (piv[c] >= 0) b[piv[c]] / a[piv[c]][c] else 0.0 }
    }

    fun panelIc(
        feature: DoubleArray, forward: DoubleArray, sessions: IntArray, controls: List<DoubleArray>?,
        name: String, horizon: Int, breakevenPts: Double, nullDraws: Int = NULL_DRAWS, seed: Long = 0,
    ): IcRow {
        val n = feature.size
        require(forward.size == n && sessions.size == n) {
            "length mismatch: feature=$n forward=${forward.size} sessions=${sessions.size} - align before measuring, never truncate"
        }
        val overall = Stats.spearman(feature, forward)
        val groups = sessions.indices.groupBy { sessions[it] }
        fun sub(a: DoubleArray, idx: List<Int>) = DoubleArray(idx.size) { a[idx[it]] }
        val perDay = groups.values.map { idx -> Stats.spearman(sub(feature, idx), sub(forward, idx)) }.filter { !it.isNaN() }
        val dailyMean = if (perDay.isNotEmpty()) perDay.average() else Double.NaN
        var lo = Double.NaN
        var hi = Double.NaN
        if (perDay.size >= 2) {
            val se = Stats.std(perDay) / sqrt(perDay.size.toDouble())
            lo = dailyMean - 1.96 * se; hi = dailyMean + 1.96 * se
        }
        val seIid = 1.0 / sqrt(maxOf(n - 3, 1).toDouble())

        // Null: permute the feature's SIGN within each session.
        val rng = java.util.Random(seed)
        val draws = ArrayList<Double>()
        repeat(nullDraws) {
            val flipped = DoubleArray(n) { feature[it] * (if (rng.nextBoolean()) 1.0 else -1.0) }
            val nulls = groups.values.map { idx -> Stats.spearman(sub(flipped, idx), sub(forward, idx)) }.filter { !it.isNaN() }
            if (nulls.isNotEmpty()) draws += abs(nulls.average())
        }
        val nullHi = if (draws.isNotEmpty()) Stats.percentile(draws, 95.0) else Double.NaN

        val spread = decileSpread(feature, forward)
        var partial = Double.NaN
        val controlled = controls != null && controls.isNotEmpty()
        if (controlled) partial = Stats.spearman(residualise(feature, controls!!), residualise(forward, controls))
        val single = if (spread.isNaN()) Double.NaN else spread / 2.0
        return IcRow(
            feature = name, horizon = horizon, nObs = n, nSessions = groups.size,
            ic = overall, dailyIcMean = dailyMean, dailyIcLo = lo, dailyIcHi = hi,
            icIidLo = overall - 1.96 * seIid, icIidHi = overall + 1.96 * seIid, nullIcHi = nullHi,
            decileSpreadPts = spread, breakevenPts = breakevenPts,
            edgeOverCost = if (breakevenPts != 0.0) abs(single) / breakevenPts else Double.NaN,
            partialIc = partial, controlled = controlled, singleLegEdgePts = single,
            nSessionsMeasured = perDay.size,
        )
    }

    /** pd.qcut(feature.rank(method="first"), 10): mean forward of the top decile minus the bottom. */
    fun decileSpread(feature: DoubleArray, forward: DoubleArray): Double {
        val n = feature.size
        if (n < DECILES) return Double.NaN
        val order = feature.indices.sortedWith(compareBy({ feature[it] }, { it }))
        val rank = DoubleArray(n)
        order.forEachIndexed { r, i -> rank[i] = r + 1.0 }
        val sortedRanks = (1..n).map { it.toDouble() }
        val edges = (0..DECILES).map { Stats.percentile(sortedRanks, it * 100.0 / DECILES) }
        val sums = DoubleArray(DECILES)
        val counts = IntArray(DECILES)
        for (i in 0 until n) {
            var b = 0
            while (b < DECILES - 1 && rank[i] > edges[b + 1]) b++
            if (!forward[i].isNaN()) { sums[b] += forward[i]; counts[b]++ }
        }
        if (counts[0] == 0 || counts[DECILES - 1] == 0) return Double.NaN
        return sums[DECILES - 1] / counts[DECILES - 1] - sums[0] / counts[0]
    }

    /** One session's (flow, flow_norm, forward, index_ret, lag_ret) aligned on the index grid. */
    class Panel(val flow: DoubleArray, val flowNorm: DoubleArray, val forward: DoubleArray, val indexRet: DoubleArray, val lagRet: DoubleArray) {
        val size get() = flow.size
    }

    fun sessionPanel(day: Session, horizon: Int): Panel? {
        val index = day.index ?: return null
        val chain = day.options
        if (index.size < horizon + 5 || chain.isEmpty()) return null
        val raw = Flow.signedFlow(chain)
        val norm = Flow.signedFlow(chain, normalise = true)
        val c = index.close
        val n = c.size
        val f = ArrayList<Double>(); val fn = ArrayList<Double>(); val fw = ArrayList<Double>()
        val ir = ArrayList<Double>(); val lr = ArrayList<Double>()
        for (i in 0 until n) {
            if (i + horizon >= n || i - horizon < 0 || i < 1) continue
            val m = index.minutes[i]
            f += raw[m] ?: 0.0; fn += norm[m] ?: 0.0
            fw += c[i + horizon] - c[i]
            ir += c[i] - c[i - horizon]
            lr += c[i] - c[i - 1]
        }
        if (f.size <= horizon) return null
        return Panel(f.toDoubleArray(), fn.toDoubleArray(), fw.toDoubleArray(), ir.toDoubleArray(), lr.toDoubleArray())
    }

    /** Median traded premium of the 20 most active contracts - the cost denominator. */
    fun atmPremium(day: Session): Double? {
        val chain = day.options.filter { it.volume != null }
        val active = chain.map { s -> s to (0 until s.size).filter { s.volume!![it] > 0 } }.filter { it.second.isNotEmpty() }
        if (active.isEmpty()) return null
        val busiest = active.sortedByDescending { (s, idx) -> idx.sumOf { s.volume!![it] } }.take(20)
        return Stats.median(busiest.flatMap { (s, idx) -> idx.map { s.close[it] } })
    }

    data class IcResult(
        val underlying: String, val sessions: List<java.time.LocalDate>, val medianPremium: Double,
        val lot: Int, val regime: String, val breakeven: Double, val rows: List<IcRow>,
    ) {
        val cleared: List<IcRow> get() = rows.filter { it.clearsCost && it.beatsNull }
    }

    /**
     * The `ic` command. Days are consumed as a sequence and never held
     * together: each is reduced to its small per-horizon panels as it streams.
     */
    fun measure(underlying: String, days: Sequence<Session>, regime: String, progress: (Int) -> Unit = {}): IcResult {
        val lot = LOTS.getValue(underlying)
        val prem = ArrayList<Double>()
        val seen = ArrayList<java.time.LocalDate>()
        val panels = HORIZONS.associateWith { ArrayList<Pair<Int, Panel>>() }
        days.forEachIndexed { i, d ->
            seen += d.day
            atmPremium(d)?.let { prem += it }
            for (h in HORIZONS) sessionPanel(d, h)?.let { panels.getValue(h) += i to it }
            progress(i + 1)
        }
        require(seen.isNotEmpty()) { "no sessions to measure. Run the harvester first." }
        require(prem.isNotEmpty()) { "no traded option premiums found; cannot express cost in index points." }
        val median = Stats.median(prem)
        val breakeven = Costs.breakevenIndexPoints(median, lot, 1, regime, 0.5)
        val rows = ArrayList<IcRow>()
        for (h in HORIZONS) {
            val ps = panels.getValue(h)
            if (ps.isEmpty()) continue
            val sess = ps.flatMap { (i, p) -> List(p.size) { i } }.toIntArray()
            fun cat(sel: (Panel) -> DoubleArray) = ps.flatMap { sel(it.second).asList() }.toDoubleArray()
            val fwd = cat { it.forward }
            val controls = listOf(cat { it.indexRet }, cat { it.lagRet })
            rows += panelIc(cat { it.flow }, fwd, sess, controls, "flow", h, breakeven)
            rows += panelIc(cat { it.flowNorm }, fwd, sess, controls, "flow_norm", h, breakeven)
        }
        return IcResult(underlying, seen, median, lot, regime, breakeven, rows)
    }
}
