package com.optionslab.engine.portfolio

import java.time.LocalDate
import kotlin.math.abs

/**
 * When a portfolio resets to target. Port of `portfolio/rebalance.py`.
 *
 * `rule` is never | monthly | quarterly | yearly (the last session of each
 * calendar period, never the purchase session); `driftBand` > 0 also
 * rebalances whenever any weight is more than that many points off target.
 */
data class RebalancePolicy(val rule: String = "never", val driftBand: Double = 0.0) {
    init {
        if (rule !in RULES) throw PortfolioException(400, "unknown rebalance rule '$rule'; expected one of ${RULES.sorted().pyList()}")
        if (!(driftBand >= 0.0 && driftBand < 1.0)) {
            throw PortfolioException(400, "drift_band is a fraction of 1.0 (0.05 = 5 percentage points), got ${Py.repr(driftBand)}")
        }
    }

    val isBuyAndHold get() = rule == "never" && driftBand == 0.0

    companion object {
        val RULES = listOf("never", "monthly", "quarterly", "yearly")

        /** The last available session of each period, minus the first session. */
        fun calendarDates(index: List<LocalDate>, rule: String): Set<LocalDate> {
            if (rule == "never" || index.isEmpty()) return emptySet()
            val last = LinkedHashMap<Int, LocalDate>()
            for (d in index) {
                val key = when (rule) {
                    "monthly" -> d.year * 12 + d.monthValue
                    "quarterly" -> d.year * 4 + (d.monthValue - 1) / 3
                    else -> d.year
                }
                last[key] = d
            }
            return last.values.toSortedSet() - index.first()
        }

        fun drifted(weights: DoubleArray, target: DoubleArray, band: Double): Boolean {
            if (band <= 0.0) return false
            return weights.indices.maxOf { abs(weights[it] - target[it]) } > band
        }
    }
}

/** One holding's itemised P&L; `contributionPct` columns sum to the portfolio return. */
data class ItemRow(
    val symbol: String,
    val weightTarget: Double,
    val weightFinal: Double,
    val invested: Double,
    val pricePnl: Double,
    val costs: Double,
    val netPnl: Double,
    val contributionPct: Double,
    val symbolReturn: Double,
)

/** Everything one simulation produces. Port of `BacktestResult`. */
class BacktestRun internal constructor(
    val dates: List<LocalDate>,
    val symbols: List<String>,
    val equity: DoubleArray,
    /** weights[session][holding] after any rebalance on that session. */
    val weights: Array<DoubleArray>,
    val rebalanceDates: List<LocalDate>,
    val turnover: List<Double>,
    val costDrag: Double,
    val costBreakdown: LinkedHashMap<String, Double>,
    val items: List<ItemRow>,
    val target: DoubleArray,
    val orders: Int,
) {
    /** Daily net returns (first session dropped). */
    val returns: DoubleArray get() = DoubleArray(equity.size - 1) { equity[it + 1] / equity[it] - 1.0 }
    val totalReturn get() = equity.last() / equity.first() - 1.0
    internal fun returnSeries() = Ser(dates.subList(1, dates.size), returns)
}

/**
 * Weights + prices + a rebalancing policy -> an equity curve net of costs.
 * Port of `portfolio/engine.py`: a drift-and-reset simulation held in currency
 * per symbol, with turnover, costs and a cost-free twin for the drag.
 */
object PortfolioEngine {
    /** Order weights to match `symbols` and scale them to sum to 1 (percent or fraction). */
    fun normaliseWeights(weights: Map<String, Double>, symbols: List<String>): DoubleArray {
        val missing = symbols.filter { it !in weights }
        if (missing.isNotEmpty()) throw PortfolioException(400, "no weight given for ${missing.joinToString(", ")}")
        val extra = weights.keys.filter { it !in symbols }
        if (extra.isNotEmpty()) throw PortfolioException(400, "weight given for unheld symbol(s): ${extra.joinToString(", ")}")
        val v = DoubleArray(symbols.size) { weights.getValue(symbols[it]) }
        if (v.any { !it.isFinite() }) throw PortfolioException(400, "weights must be finite")
        if (v.any { it < 0 }) throw PortfolioException(400, "negative weights are not supported; this is a long-only engine")
        val total = Np.sum(v)
        if (total <= 0) throw PortfolioException(400, "weights sum to zero")
        return DoubleArray(v.size) { v[it] / total }
    }

    fun run(
        prices: PriceMatrix,
        weights: Map<String, Double>,
        policy: RebalancePolicy = RebalancePolicy(),
        costs: CostModel = FlatCosts(),
        initialCapital: Double = 100_000.0,
    ): BacktestRun {
        if (!initialCapital.isFinite() || initialCapital <= 0) throw PortfolioException(400, "initial capital must be positive and finite")
        val symbols = prices.symbols
        val target = normaliseWeights(weights, symbols)
        val k = symbols.size
        val closes = prices.closes
        if (closes.any { col -> col.any { !it.isFinite() || it <= 0 } }) {
            throw PortfolioException(400, "price matrix must contain only positive finite closes")
        }
        val index = prices.dates
        val scheduled = RebalancePolicy.calendarDates(index, policy.rule)
        val n = index.size

        val equity = DoubleArray(n)
        val weightPath = Array(n) { DoubleArray(k) }
        val rebalanced = ArrayList<LocalDate>()
        val turnover = ArrayList<Double>()
        var orderCount = 0
        val breakdown: LinkedHashMap<String, Double> = when (costs) {
            is CostSchedule -> LinkedHashMap(costs.breakdown(0.0, 0.0, 0).mapValues { 0.0 })
            is FlatCosts -> linkedMapOf("total" to 0.0, "orders" to 0.0)
        }

        var sleeve = DoubleArray(k) { target[it] * initialCapital }
        val pricePnl = DoubleArray(k)
        val costPaid = DoubleArray(k)
        var gross = sleeve.copyOf()

        for (i in 0 until n) {
            var value: Double
            if (i > 0) {
                val growth = DoubleArray(k) { closes[it][i] / closes[it][i - 1] }
                val grown = DoubleArray(k) { sleeve[it] * growth[it] }
                for (s in 0 until k) pricePnl[s] += grown[s] - sleeve[s]
                sleeve = grown
                value = Np.sum(sleeve)
                gross = DoubleArray(k) { gross[it] * growth[it] }
                val held = DoubleArray(k) { sleeve[it] / value }
                if (index[i] in scheduled || RebalancePolicy.drifted(held, target, policy.driftBand)) {
                    val desired = DoubleArray(k) { target[it] * value }
                    val moved = DoubleArray(k) { abs(desired[it] - sleeve[it]) }
                    val traded = Np.sum(moved) / 2.0 / value
                    if (traded > 0) {
                        val tradedValue = traded * value
                        val orders = moved.count { it > 1e-9 }
                        orderCount += orders
                        val charge: Double
                        when (costs) {
                            is CostSchedule -> {
                                val lines = costs.breakdown(tradedValue, tradedValue, orders)
                                for ((key, amount) in lines) breakdown[key] = (breakdown[key] ?: 0.0) + amount
                                charge = lines.getValue("total")
                            }
                            is FlatCosts -> {
                                charge = tradedValue * costs.total
                                breakdown["total"] = breakdown.getValue("total") + charge
                                breakdown["orders"] = breakdown.getValue("orders") + orders.toDouble()
                            }
                        }
                        val movedSum = Np.sum(moved)
                        for (s in 0 until k) costPaid[s] += charge * (if (movedSum > 0) moved[s] / movedSum else moved[s])
                        value -= charge
                        rebalanced.add(index[i])
                        turnover.add(traded)
                    }
                    sleeve = DoubleArray(k) { target[it] * value }
                    val grossValue = Np.sum(gross)
                    gross = DoubleArray(k) { target[it] * grossValue }
                }
            } else {
                value = Np.sum(sleeve)
            }
            equity[i] = value
            for (s in 0 until k) weightPath[i][s] = sleeve[s] / value
        }

        val grossTotal = Np.sum(gross) / initialCapital - 1.0
        val netTotal = equity[n - 1] / initialCapital - 1.0
        val items = symbols.mapIndexed { s, symbol ->
            val net = pricePnl[s] - costPaid[s]
            ItemRow(
                symbol = symbol,
                weightTarget = target[s],
                weightFinal = weightPath[n - 1][s],
                invested = target[s] * initialCapital,
                pricePnl = pricePnl[s],
                costs = costPaid[s],
                netPnl = net,
                contributionPct = net / initialCapital,
                symbolReturn = closes[s][n - 1] / closes[s][0] - 1.0,
            )
        }
        return BacktestRun(
            index, symbols, equity, weightPath, rebalanced, turnover,
            grossTotal - netTotal, breakdown, items, target, orderCount,
        )
    }
}
