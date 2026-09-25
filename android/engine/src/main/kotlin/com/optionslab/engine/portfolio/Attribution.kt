package com.optionslab.engine.portfolio

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

data class AttributionRow(
    val symbol: String, val weight: Double?, val ret: Double?, val vsBenchmark: Double?, val contribution: Double?,
)

/** Excess return split into selection, allocation and costs; `available = false` carries only `reason`. */
data class Attribution(
    val available: Boolean,
    val reason: String? = null,
    val portfolioReturn: Double? = null,
    val grossPortfolioReturn: Double? = null,
    val equalWeightReturn: Double? = null,
    val benchmarkReturn: Double? = null,
    val excessReturn: Double? = null,
    val selectionEffect: Double? = null,
    val allocationEffect: Double? = null,
    val costEffect: Double? = null,
    val holdings: List<AttributionRow>? = null,
    val holdingsReason: String? = null,
    val method: String? = null,
)

/**
 * Port of `portfolio/attribution.py`. Not Brinson: only a benchmark price
 * series exists, so selection is an equal-weight basket of the holdings vs the
 * benchmark, allocation the realised gross path vs that basket, and costs the
 * engine's net path vs its gross path. The three reconcile to the net excess.
 */
internal object AttributionCalc {
    const val METHOD =
        "Selection compares an equal-weighted basket of the same holdings with the benchmark; allocation compares " +
            "the realized gross portfolio with that basket; costs compare the engine's net and gross paths. " +
            "Brinson-Fachler is not used because benchmark constituent weights and segment returns are unavailable."

    private fun compound(xs: DoubleArray): Double {
        var p = 1.0
        for (x in xs) if (!x.isNaN()) p *= (1.0 + x)
        return p - 1.0
    }

    fun run(run: BacktestRun, holdingReturns: Array<DoubleArray>, benchmark: Ser?, d: Disp): Attribution {
        if (holdingReturns.isEmpty() || holdingReturns[0].isEmpty() || benchmark == null || benchmark.size == 0) {
            return Attribution(false, reason = "a benchmark is required to attribute against")
        }
        val symbols = run.symbols
        val tsum = Np.sum(run.target)
        if (tsum <= 0) return Attribution(false, reason = "target weights sum to zero")
        val target = DoubleArray(symbols.size) { run.target[it] / tsum }
        val hDates = run.dates.subList(1, run.dates.size)
        val net = run.returns
        val bAt = HashMap<LocalDate, Double>()
        benchmark.dates.forEachIndexed { i, dt -> bAt[dt] = benchmark.v[i] }

        val rows = ArrayList<Int>() // positions t in hDates kept after the inner join + dropna
        for (t in hDates.indices) {
            val b = bAt[hDates[t]] ?: continue
            if (b.isNaN() || net[t].isNaN()) continue
            if (symbols.indices.any { holdingReturns[it][t].isNaN() || run.weights[t][it].isNaN() }) continue
            rows.add(t)
        }
        if (rows.size < 2) return Attribution(false, reason = "no overlapping sessions with the benchmark")

        val k = symbols.size
        val gross = DoubleArray(rows.size) { i ->
            val t = rows[i]
            var s = 0.0
            for (j in 0 until k) s += holdingReturns[j][t] * run.weights[t][j] // weights lagged one session
            s
        }
        val equal = DoubleArray(rows.size) { i ->
            var s = 0.0
            for (j in 0 until k) s += holdingReturns[j][rows[i]]
            s / k
        }
        val grossReturn = compound(gross)
        val netReturn = compound(DoubleArray(rows.size) { net[rows[it]] })
        val equalReturn = compound(equal)
        val benchReturn = compound(DoubleArray(rows.size) { bAt.getValue(hDates[rows[it]]) })

        val exact = benchmark.dates == hDates && rows.size == hDates.size
        var holdingsReason: String? = null
        val attrRows = ArrayList<Pair<Double, AttributionRow>>()
        if (exact) {
            val contributions = run.items.map { it.contributionPct }.toDoubleArray()
            val total = Np.sum(contributions)
            val close = abs(total - netReturn) <= max(1e-9 * max(abs(total), abs(netReturn)), 1e-9)
            if (contributions.none { it.isNaN() } && close) {
                for (j in 0 until k) {
                    val own = compound(DoubleArray(rows.size) { holdingReturns[j][rows[it]] })
                    val contribution = contributions[j] - target[j] * benchReturn
                    attrRows.add(Pair(contribution, AttributionRow(symbols[j], d.c(target[j]), d.c(own), d.c(own - benchReturn), d.c(contribution))))
                }
            } else {
                holdingsReason = "engine holding contributions do not reconcile to this period"
            }
        } else {
            holdingsReason = "per-holding contributions require the benchmark and engine result to cover identical sessions"
        }
        return Attribution(
            available = true,
            portfolioReturn = d.c(netReturn),
            grossPortfolioReturn = d.c(grossReturn),
            equalWeightReturn = d.c(equalReturn),
            benchmarkReturn = d.c(benchReturn),
            excessReturn = d.c(netReturn - benchReturn),
            selectionEffect = d.c(equalReturn - benchReturn),
            allocationEffect = d.c(grossReturn - equalReturn),
            costEffect = d.c(netReturn - grossReturn),
            holdings = attrRows.sortedByDescending { it.first }.map { it.second },
            holdingsReason = holdingsReason,
            method = METHOD,
        )
    }
}
