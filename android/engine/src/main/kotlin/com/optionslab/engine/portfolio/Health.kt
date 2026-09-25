package com.optionslab.engine.portfolio

/** One scored dimension with its working: raw inputs, the formula, its designed and effective weight. */
data class HealthPillar(
    val key: String, val label: String, val score: Double?, val weight: Double, val formula: String,
    val inputs: Map<String, Any?>, val comment: String, val effectiveWeight: Double,
)
data class Health(val score: Double?, val grade: String?, val pillars: List<HealthPillar>, val unmeasured: List<String>)

/**
 * Port of `portfolio/health.py`: six pillars on stated 0-100 scales (effective
 * holdings 1..12, mean correlation 0.9..0, Sharpe 0..2, max drawdown -50%..-10%,
 * weight above its 200-session SMA, cost drag 5%..0), weighted 20/20/20/20/10/10.
 * An unmeasurable pillar is dropped and the rest renormalised, never scored 0.
 */
object PortfolioHealth {
    private const val TREND_LOOKBACK = 200
    private val GRADES = listOf(80.0 to "A", 65.0 to "B", 50.0 to "C", 35.0 to "D", 0.0 to "F")

    private class P(val key: String, val label: String, val score: Double, val weight: Double, val formula: String, val inputs: Map<String, Any?>, val comment: String)

    private fun scale(v: Double, low: Double, high: Double): Double {
        if (!v.isFinite()) return Double.NaN
        if (high == low) return 50.0
        return ((v - low) / (high - low)).coerceIn(0.0, 1.0) * 100.0
    }

    fun gradeFor(score: Double): String = GRADES.firstOrNull { score >= it.first }?.second ?: "F"

    internal fun health(
        symbols: List<String>, weights: DoubleArray, returns: Array<DoubleArray>, closes: Array<DoubleArray>,
        sharpe: Double, sortino: Double, maxDrawdown: Double, costDrag: Double, turnover: Double, d: Disp,
    ): Health {
        val pillars = ArrayList<P>()

        val c = PortfolioAnalytics.concentration(weights)
        val eff = c.effectiveHoldings
        pillars.add(P(
            "concentration", "Concentration", scale(eff, 1.0, 12.0), 0.20,
            "effective holdings = 1 / HHI, scaled linearly from 1 (all in one name) to 12",
            linkedMapOf("hhi" to d.r(c.hhi, 4), "effective_holdings" to d.r(eff, 4), "largest_weight" to d.r(c.largestWeight, 4), "holdings" to c.holdings),
            if (eff < 4) "${c.holdings} holdings, behaving like ${Py.fixed(eff, 1)} independent bets"
            else "spread across roughly ${Py.fixed(eff, 1)} independent bets",
        ))

        val avg = PortfolioAnalytics.averagePairwiseCorrelation(returns)
        pillars.add(P(
            "diversification", "True diversification", scale(-avg, -0.9, -0.0), 0.20,
            "mean pairwise correlation, scaled from 0.9 (moves as one) to 0.0 (unrelated); lower is better",
            linkedMapOf("average_pairwise_correlation" to (if (avg.isFinite()) d.r(avg, 4) else null)),
            if (avg.isFinite() && avg > 0.7) "holdings move together, so the spread is nominal"
            else "holdings move independently enough to diversify",
        ))

        pillars.add(P(
            "efficiency", "Risk-adjusted efficiency", scale(sharpe, 0.0, 2.0), 0.20,
            "Sharpe ratio scaled from 0.0 to 2.0",
            linkedMapOf("sharpe" to (if (sharpe.isFinite()) d.r(sharpe, 4) else null), "sortino" to (if (sortino.isFinite()) d.r(sortino, 4) else null)),
            if (sharpe.isFinite() && sharpe < 0.5) "taking risk without being paid for it"
            else if (sharpe.isFinite() && sharpe < 1.2) "modest reward for the risk taken"
            else "returns are compensating well for the risk taken",
        ))

        pillars.add(P(
            "drawdown", "Drawdown resilience", scale(maxDrawdown, -0.50, -0.10), 0.20,
            "worst peak-to-trough fall, scaled from -50% to -10%",
            linkedMapOf("max_drawdown" to (if (maxDrawdown.isFinite()) d.r(maxDrawdown, 4) else null)),
            if (maxDrawdown.isFinite() && maxDrawdown < -0.30) "a fall this deep needs a large gain to recover"
            else "falls have stayed within normal bounds",
        ))

        val above = LinkedHashMap<String, Boolean>()
        symbols.forEachIndexed { s, symbol ->
            val series = closes[s].filter { !it.isNaN() }
            if (series.size < TREND_LOOKBACK) return@forEachIndexed
            val tail = series.subList(series.size - TREND_LOOKBACK, series.size)
            val sma = tail.sum() / TREND_LOOKBACK
            if (sma.isFinite()) above[symbol] = series.last() > sma
        }
        if (above.isEmpty()) {
            pillars.add(P("trend", "Trend health", Double.NaN, 0.10, "weight above the 200-session moving average",
                linkedMapOf("measured" to 0), "less than 200 sessions of history"))
        } else {
            val wsum = Np.sum(weights)
            val w = symbols.indices.associate { symbols[it] to weights[it] / wsum }
            val covered = Np.sum(above.keys.map { w.getValue(it) })
            var up = 0.0
            for ((s, ok) in above) if (ok) up += w.getValue(s)
            val share = if (covered != 0.0) up / covered else 0.0
            pillars.add(P(
                "trend", "Trend health", share * 100.0, 0.10,
                "portfolio weight trading above its 200-session simple moving average, as a share of the weight that could be measured",
                linkedMapOf(
                    "above" to above.filter { it.value }.keys.sorted(),
                    "below" to above.filter { !it.value }.keys.sorted(),
                    "measured_weight" to d.r(covered, 4),
                ),
                if (share < 0.5) "most of the book is below its long-term average" else "most of the book is trending up",
            ))
        }

        pillars.add(P(
            "cost", "Cost efficiency", scale(-costDrag, -0.05, 0.0), 0.10,
            "return given up to trading costs, scaled from 5% to 0%; lower is better",
            linkedMapOf("cost_drag" to d.r(costDrag, 6), "turnover_total" to d.r(turnover, 4)),
            if (costDrag > 0.02) "rebalancing is eating a meaningful share of the return" else "trading costs are not material",
        ))

        val scored = pillars.filter { it.score.isFinite() }
        val totalWeight = Py.sum(scored.map { it.weight })
        val overall = if (totalWeight > 0) Py.sum(scored.map { it.score * it.weight }) / totalWeight else Double.NaN
        return Health(
            if (overall.isFinite()) d.r(overall, 1) else null,
            if (overall.isFinite()) gradeFor(overall) else null,
            pillars.map {
                HealthPillar(
                    it.key, it.label, if (it.score.isFinite()) d.r(it.score, 1) else null, it.weight, it.formula, it.inputs,
                    it.comment,
                    if (it.score.isFinite() && totalWeight > 0) d.r(it.weight / totalWeight, 4) else 0.0,
                )
            },
            pillars.filter { !it.score.isFinite() }.map { it.key },
        )
    }
}
