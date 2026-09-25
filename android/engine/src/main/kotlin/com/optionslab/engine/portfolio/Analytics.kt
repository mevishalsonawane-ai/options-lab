package com.optionslab.engine.portfolio

import java.time.LocalDate
import kotlin.math.sqrt

/** A dated value, as every chart series is shipped. */
data class CurvePoint(val date: LocalDate, val value: Double)

/**
 * The display rounding IraAlgo's endpoints apply (`_clean` to 6 places,
 * `_curve` to 4, and each module's own `round` calls). With `on = false` every
 * one of them is the identity, which reproduces the service at full precision.
 */
internal class Disp(val on: Boolean) {
    fun r(x: Double, n: Int) = if (on) Py.round(x, n) else x
    fun c(x: Double): Double? = if (x.isFinite()) r(x, 6) else null
    fun c(x: Double?): Double? = x?.let { c(it) }
    fun curve(s: Ser, scale: Double = 1.0): List<CurvePoint> =
        s.dates.indices.map { CurvePoint(s.dates[it], r(s.v[it] * scale, 4)) }
}

/** Headline metrics; null where the Python API returns null. */
data class Metrics(
    val cagr: Double?, val volatility: Double?, val sharpe: Double?, val sortino: Double?, val calmar: Double?,
    val maxDrawdown: Double?, val winRate: Double?, val bestDay: Double?, val worstDay: Double?,
    val valueAtRisk: Double?, val cvar: Double?, val ulcerIndex: Double?, val recoveryFactor: Double?,
    val tailRatio: Double?, val skew: Double?, val kurtosis: Double?,
    val alpha: Double? = null, val beta: Double? = null, val informationRatio: Double? = null,
    val benchmarkCagr: Double? = null, val excessCagr: Double? = null,
    val upCapture: Double? = null, val downCapture: Double? = null,
)

/**
 * Investor-facing analytics. Port of `portfolio/analytics.py`: correlation,
 * concentration, diversification ratio, capture ratios and the openstatz
 * metric summary (252 trading days, rf as an annual rate).
 */
object PortfolioAnalytics {
    const val TRADING_DAYS = 252

    /** Pairwise correlation; pairs with fewer than `minOverlap` observations are NaN. */
    fun correlationMatrix(returns: Array<DoubleArray>, minOverlap: Int = 20): Array<DoubleArray> =
        Array(returns.size) { i -> DoubleArray(returns.size) { j -> Np.corr(returns[i], returns[j], minOverlap) } }

    fun averagePairwiseCorrelation(returns: Array<DoubleArray>): Double {
        if (returns.size < 2) return Double.NaN
        val upper = ArrayList<Double>()
        for (i in returns.indices) for (j in i + 1 until returns.size) {
            val c = Np.corr(returns[i], returns[j])
            if (!c.isNaN()) upper.add(c)
        }
        return if (upper.isEmpty()) Double.NaN else Np.mean(upper)
    }

    data class Concentration(val hhi: Double, val effectiveHoldings: Double, val largestWeight: Double, val holdings: Int)

    fun concentration(weights: DoubleArray): Concentration {
        val total = Np.sum(weights)
        if (total <= 0) throw PortfolioException(400, "weights sum to zero")
        val w = DoubleArray(weights.size) { weights[it] / total }
        val hhi = Np.sum(DoubleArray(w.size) { w[it] * w[it] })
        return Concentration(hhi, 1.0 / hhi, w.max(), w.size)
    }

    fun diversificationRatio(weights: DoubleArray, returns: Array<DoubleArray>): Double {
        if (returns.size < 2) return Double.NaN
        val wsum = Np.sum(weights)
        val w = DoubleArray(weights.size) { weights[it] / wsum }
        val k = w.size
        val cov = Array(k) { i -> DoubleArray(k) { j -> Np.corr(returns[i], returns[j], 1, cov = true) * TRADING_DAYS } }
        val wc = DoubleArray(k) { j -> Np.sum(DoubleArray(k) { i -> w[i] * cov[i][j] }) }
        val portVol = sqrt(Np.sum(DoubleArray(k) { wc[it] * w[it] }))
        if (!(portVol > 0)) return Double.NaN
        val weighted = Np.sum(DoubleArray(k) { w[it] * sqrt(cov[it][it]) })
        return weighted / portVol
    }

    /** Up/down capture: ratio of mean returns over benchmark-up and benchmark-down sessions. */
    fun captureRatios(port: DoubleArray, bench: DoubleArray): Pair<Double, Double> {
        fun capture(mask: (Int) -> Boolean): Double {
            val idx = bench.indices.filter(mask)
            if (idx.isEmpty()) return Double.NaN
            val b = Np.mean(DoubleArray(idx.size) { bench[idx[it]] })
            if (b == 0.0) return Double.NaN
            return Np.mean(DoubleArray(idx.size) { port[idx[it]] }) / b
        }
        return Pair(capture { bench[it] > 0 }, capture { bench[it] < 0 })
    }

    /** Inner join of two dated series on their common dates, in date order. */
    internal fun join(a: Ser, b: Ser): Triple<List<LocalDate>, DoubleArray, DoubleArray> {
        val bAt = HashMap<LocalDate, Double>()
        b.dates.forEachIndexed { i, d -> bAt[d] = b.v[i] }
        val dates = ArrayList<LocalDate>(); val x = ArrayList<Double>(); val y = ArrayList<Double>()
        a.dates.forEachIndexed { i, d ->
            val bv = bAt[d]
            if (bv != null && !a.v[i].isNaN() && !bv.isNaN()) { dates.add(d); x.add(a.v[i]); y.add(bv) }
        }
        return Triple(dates, x.toDoubleArray(), y.toDoubleArray())
    }

    /** `summary()`: raw values (NaN/inf where openstatz produces them). */
    internal fun summaryRaw(returns: Ser, benchmark: Ser?, rf: Double): LinkedHashMap<String, Double> {
        val r = returns.v
        val p = Openstatz.prepare(r)
        val out = linkedMapOf(
            "cagr" to Openstatz.cagr(r, rf),
            "volatility" to Openstatz.volatility(r),
            "sharpe" to Openstatz.sharpe(r, rf),
            "sortino" to Openstatz.sortino(r, rf),
            "calmar" to Openstatz.calmar(r),
            "max_drawdown" to Openstatz.maxDrawdown(cumprod(r)),
            "win_rate" to Openstatz.winRate(p),
            "best_day" to p.max(),
            "worst_day" to p.min(),
            "value_at_risk" to Openstatz.valueAtRisk(r),
            "cvar" to Openstatz.conditionalValueAtRisk(r),
            "ulcer_index" to Openstatz.ulcerIndex(r),
            "recovery_factor" to Openstatz.recoveryFactor(r),
            "tail_ratio" to Openstatz.tailRatio(r),
            "skew" to Openstatz.skew(r),
            "kurtosis" to Openstatz.kurtosis(r),
        )
        if (benchmark != null && benchmark.size > 0) {
            val (_, port, bench) = join(returns, benchmark)
            if (port.isNotEmpty()) {
                val (beta, alpha) = Openstatz.greeks(port, bench)
                out["alpha"] = alpha
                out["beta"] = beta
                out["information_ratio"] = Openstatz.informationRatio(port, bench)
                out["benchmark_cagr"] = Openstatz.cagr(bench, rf)
                out["excess_cagr"] = out.getValue("cagr") - out.getValue("benchmark_cagr")
                val (up, down) = captureRatios(port, bench)
                out["up_capture"] = up
                out["down_capture"] = down
            }
        }
        return out
    }

    internal fun metrics(raw: Map<String, Double>, d: Disp): Metrics {
        fun g(k: String) = raw[k]?.let { d.c(it) }
        return Metrics(
            g("cagr"), g("volatility"), g("sharpe"), g("sortino"), g("calmar"), g("max_drawdown"), g("win_rate"),
            g("best_day"), g("worst_day"), g("value_at_risk"), g("cvar"), g("ulcer_index"), g("recovery_factor"),
            g("tail_ratio"), g("skew"), g("kurtosis"), g("alpha"), g("beta"), g("information_ratio"),
            g("benchmark_cagr"), g("excess_cagr"), g("up_capture"), g("down_capture"),
        )
    }

    /** `(1 + r).cumprod()`. */
    internal fun cumprod(r: DoubleArray): DoubleArray {
        var acc = 1.0
        return DoubleArray(r.size) { acc *= (1.0 + r[it]); acc }
    }
}
