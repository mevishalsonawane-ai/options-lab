package com.optionslab.engine.portfolio

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sign

data class WalkForwardWindow(
    val start: LocalDate, val end: LocalDate, val totalReturn: Double?, val cagr: Double?,
    val maxDrawdown: Double?, val sessions: Int,
)
data class WalkForwardSummary(
    val count: Int, val windowYears: Double, val stepYears: Double, val best: Double?, val worst: Double?,
    val median: Double?, val positiveShare: Double?, val spread: Double?,
)
data class WalkForward(val windows: List<WalkForwardWindow>, val summary: WalkForwardSummary?, val note: String?)

data class Band(val p05: Double?, val p25: Double?, val median: Double?, val p75: Double?, val p95: Double?)
/** Block-bootstrap outcomes; everything but `paths` and `note` is null when the series is too short. */
data class MonteCarlo(
    val paths: Int, val block: Int?, val seed: Long?, val totalReturn: Band?, val cagr: Band?, val maxDrawdown: Band?,
    val probabilityOfLoss: Double?, val worstDrawdownSeen: Double?, val note: String?,
)

data class SweepVariant(
    val label: String, val rule: String, val driftBand: Double, val totalReturn: Double?, val cagr: Double?,
    val volatility: Double?, val sharpe: Double?, val sortino: Double?, val maxDrawdown: Double?, val calmar: Double?,
    val costDrag: Double?, val turnover: Double?, val rebalances: Int,
)
data class RebalancingSweep(
    val variants: List<SweepVariant>, val curves: Map<String, List<CurvePoint>>, val bestBySharpe: String?,
    val bestByReturn: String?, val sessions: Int, val start: LocalDate, val end: LocalDate,
)

/**
 * Does the result survive doubt? Port of `portfolio/walkforward.py` and
 * `portfolio/compare.py`: rolling sub-window re-runs, a 20-session block
 * bootstrap with numpy's generator (seed 12345), and the same allocation
 * under every rebalancing rule plus a 5% drift band.
 */
internal object Robustness {
    private const val TRADING_DAYS = 252

    fun walkForward(
        prices: PriceMatrix, weights: Map<String, Double>, policy: RebalancePolicy, costs: CostModel,
        initialCapital: Double, windowYears: Double, stepYears: Double, d: Disp,
    ): WalkForward {
        val window = (windowYears * TRADING_DAYS).toInt()
        val step = maxOf(1, (stepYears * TRADING_DAYS).toInt())
        val n = prices.sessions
        if (n < window + 1) return WalkForward(emptyList(), null, "$n sessions is shorter than the $window-session window")
        val windows = ArrayList<WalkForwardWindow>()
        val totals = ArrayList<Double>()
        var begin = 0
        while (begin < n - window + 1) {
            val chunk = prices.slice(begin, begin + window)
            val r = PortfolioEngine.run(chunk, weights, policy, costs, initialCapital)
            val eq = r.equity
            val years = chunk.sessions.toDouble() / TRADING_DAYS
            val total = eq.last() / eq.first() - 1.0
            var peak = Double.NEGATIVE_INFINITY
            var dd = Double.POSITIVE_INFINITY
            for (x in eq) { peak = maxOf(peak, x); dd = minOf(dd, x / peak - 1.0) }
            totals.add(total)
            windows.add(
                WalkForwardWindow(
                    chunk.start, chunk.end, d.c(total),
                    d.c(if (years > 0) (1.0 + total).pow(1.0 / years) - 1.0 else Double.NaN),
                    d.c(dd), chunk.sessions,
                ),
            )
            begin += step
        }
        val a = totals.toDoubleArray()
        return WalkForward(
            windows,
            WalkForwardSummary(
                windows.size, d.r(windowYears, 6), d.r(stepYears, 6), d.c(a.max()), d.c(a.min()), d.c(Np.median(a)),
                d.c(a.count { it > 0 }.toDouble() / a.size), d.c(a.max() - a.min()),
            ),
            null,
        )
    }

    fun monteCarlo(returns: DoubleArray, simulations: Int, d: Disp, block: Int = 20, seed: Long = 12345): MonteCarlo {
        val values = returns.filter { !it.isNaN() }.toDoubleArray()
        val n = values.size
        if (n < block * 2) {
            return MonteCarlo(0, null, null, null, null, null, null, null, "$n sessions is too few for $block-session blocks")
        }
        val rng = Pcg64(seed)
        val finals = DoubleArray(simulations)
        val drawdowns = DoubleArray(simulations)
        val blocks = maxOf(1, ceil(n.toDouble() / block).toInt())
        for (i in 0 until simulations) {
            val starts = rng.integers(0, (n - block + 1).toLong(), blocks)
            val path = DoubleArray(n)
            var at = 0
            outer@ for (s in starts) for (j in 0 until block) {
                if (at >= n) break@outer
                path[at++] = values[s.toInt() + j]
            }
            var curve = 1.0
            var peak = Double.NEGATIVE_INFINITY
            var dd = Double.POSITIVE_INFINITY
            for (x in path) {
                curve *= (1.0 + x)
                peak = maxOf(peak, curve)
                dd = minOf(dd, curve / peak - 1.0)
            }
            finals[i] = curve - 1.0
            drawdowns[i] = dd
        }
        val years = n.toDouble() / TRADING_DAYS
        val cagrs = DoubleArray(simulations) {
            val g = 1.0 + finals[it]
            sign(g) * abs(g).pow(1.0 / years) - 1.0
        }
        fun band(a: DoubleArray) = Band(
            d.c(Np.percentile(a, 5.0)), d.c(Np.percentile(a, 25.0)), d.c(Np.median(a)),
            d.c(Np.percentile(a, 75.0)), d.c(Np.percentile(a, 95.0)),
        )
        return MonteCarlo(
            simulations, block, seed, band(finals), band(cagrs), band(drawdowns),
            d.c(finals.count { it < 0 }.toDouble() / simulations), d.c(drawdowns.min()), null,
        )
    }

    fun rebalancingSweep(
        prices: PriceMatrix, weights: Map<String, Double>, costs: CostModel, initialCapital: Double, rf: Double,
        d: Disp, driftBands: List<Double> = listOf(0.05),
    ): RebalancingSweep {
        val variants = listOf("never", "yearly", "quarterly", "monthly").map { rule ->
            Triple(rule.replaceFirstChar { it.uppercase() }, RebalancePolicy(rule), rule)
        } + driftBands.map { Triple("Drift ${(it * 100).toInt()}%", RebalancePolicy("never", it), "never") }

        val rows = ArrayList<SweepVariant>()
        val rawSharpe = ArrayList<Double>()
        val rawTotal = ArrayList<Double>()
        val curves = LinkedHashMap<String, List<CurvePoint>>()
        for ((label, policy, _) in variants) {
            val r = PortfolioEngine.run(prices, weights, policy, costs, initialCapital)
            val ret = r.returns
            val sharpe = Openstatz.sharpe(ret, rf)
            rawSharpe.add(sharpe)
            rawTotal.add(r.totalReturn)
            rows.add(
                SweepVariant(
                    label, policy.rule, policy.driftBand, d.c(r.totalReturn), d.c(Openstatz.cagr(ret, rf)),
                    d.c(Openstatz.volatility(ret)), d.c(sharpe), d.c(Openstatz.sortino(ret, rf)),
                    d.c(Openstatz.maxDrawdown(PortfolioAnalytics.cumprod(ret))), d.c(Openstatz.calmar(ret)),
                    d.c(r.costDrag), d.c(Np.sum(r.turnover.toDoubleArray())), r.rebalanceDates.size,
                ),
            )
            val eq = r.equity
            val step = maxOf(1, eq.size / 300)
            curves[label] = (eq.indices step step).map { CurvePoint(r.dates[it], d.r(eq[it], 2)) }
        }
        // Rank on Sharpe (NaN last); a stable descending sort, as Python's.
        val order = rows.indices.sortedByDescending { if (rawSharpe[it].isNaN()) -9e9 else rawSharpe[it] }
        var bestReturn = 0
        for (i in rows.indices) if (rawTotal[i] > rawTotal[bestReturn]) bestReturn = i
        return RebalancingSweep(
            rows, curves, rows.getOrNull(order.firstOrNull() ?: -1)?.label, rows.getOrNull(bestReturn)?.label,
            prices.sessions, prices.start, prices.end,
        )
    }
}
