package com.optionslab.engine.portfolio

import kotlin.math.abs
import kotlin.math.min

/** One SWOT finding with the figure that produced it. `kind`: strength | weakness | opportunity | threat. */
data class Finding(
    val kind: String, val tag: String, val title: String, val detail: String, val severity: Double,
    val evidence: Map<String, Any?>,
)
data class InsightTag(val kind: String, val label: String)
data class Insights(
    val headline: Finding?, val tags: List<InsightTag>, val strengths: List<Finding>, val weaknesses: List<Finding>,
    val opportunities: List<Finding>, val threats: List<Finding>, val counts: Map<String, Int>,
)

/**
 * Port of `portfolio/insights.py`: rules over numbers the report already
 * shows (as shown, i.e. after display rounding), each finding carrying its
 * evidence. Sorted by severity; the most severe is the headline.
 */
internal object InsightRules {
    private const val STRENGTH = "strength"
    private const val WEAKNESS = "weakness"
    private const val OPPORTUNITY = "opportunity"
    private const val THREAT = "threat"

    private fun pct(v: Double?, dp: Int = 1) = if (v == null) "n/a" else "${Py.fixed(v * 100, dp)}%"

    fun build(
        metrics: Metrics, health: Health, structure: Structure, attribution: Attribution, crisis: CrisisSummary?,
        sweep: RebalancingSweep, drag: Double?, turnover: Double?, items: List<ItemRow>, rule: String, d: Disp,
    ): Insights {
        val out = ArrayList<Finding>()
        fun add(kind: String, tag: String, title: String, detail: String, severity: Double, evidence: Map<String, Any?>) =
            out.add(Finding(kind, tag, title, detail, severity, evidence))

        metrics.excessCagr?.let { excess ->
            if (excess < -0.01) add(WEAKNESS, "Lagging the market", "Lagging the market",
                "The portfolio is behind its benchmark by ${pct(abs(excess))} a year. It compounded at ${pct(metrics.cagr)} against ${pct(metrics.benchmarkCagr)}.",
                min(1.0, 0.5 + abs(excess) * 4), linkedMapOf("excess_cagr" to excess))
            else if (excess > 0.01) add(STRENGTH, "Beating the market", "Ahead of the benchmark",
                "The portfolio is ahead by ${pct(excess)} a year, compounding at ${pct(metrics.cagr)} against ${pct(metrics.benchmarkCagr)}.",
                min(1.0, 0.5 + excess * 4), linkedMapOf("excess_cagr" to excess))
        }

        metrics.sharpe?.let { sharpe ->
            if (sharpe < 0) add(WEAKNESS, "Unrewarded risk", "Risk without reward",
                "Sharpe is ${Py.fixed(sharpe, 2)}: the portfolio carried ${pct(metrics.volatility)} volatility and was not paid for it.",
                0.9, linkedMapOf("sharpe" to sharpe))
            else if (sharpe > 1.0) add(STRENGTH, "Efficient", "Well paid for the risk taken",
                "Sharpe is ${Py.fixed(sharpe, 2)} on ${pct(metrics.volatility)} volatility.",
                min(1.0, sharpe / 2), linkedMapOf("sharpe" to sharpe))
        }

        val up = metrics.upCapture
        val down = metrics.downCapture
        if (up != null && down != null && down > 0) {
            if (down < up) add(STRENGTH, "Defensive", "Falls less than it rises",
                "It captures ${Py.fixed(up * 100, 0)}% of the benchmark's up moves and only ${Py.fixed(down * 100, 0)}% of its falls.",
                0.6, linkedMapOf("up_capture" to up, "down_capture" to down))
            else if (down > up + 0.1) add(WEAKNESS, "Poor asymmetry", "Falls more than it rises",
                "It takes ${Py.fixed(down * 100, 0)}% of the benchmark's falls but only ${Py.fixed(up * 100, 0)}% of its gains.",
                0.8, linkedMapOf("up_capture" to up, "down_capture" to down))
        }

        val bets = structure.effectiveBets
        val largest = structure.largestClusterWeight
        if (items.isNotEmpty()) {
            if (largest > 0.5 && items.size > 2) {
                val members = structure.clusters.firstOrNull { it.weight == largest }?.members ?: emptyList()
                add(THREAT, "Concentration", "Concentrated despite the holding count",
                    "${pct(largest, 0)} of the book moves as one position (${members.take(4).joinToString(", ")}${if (members.size > 4) "..." else ""}). " +
                        "${items.size} holdings behave like $bets independent bets.",
                    min(1.0, largest + 0.2), linkedMapOf("largest_cluster_weight" to largest, "effective_bets" to bets))
            } else if (bets >= maxOf(3.0, items.size * 0.7)) {
                add(STRENGTH, "Genuinely diversified", "Holdings move independently",
                    "${items.size} holdings behave like $bets independent bets, so a shock to one is unlikely to take the rest with it.",
                    0.5, linkedMapOf("effective_bets" to bets))
            }
        }

        var heaviest = items.firstOrNull()
        for (item in items) if (item.weightFinal > heaviest!!.weightFinal) heaviest = item
        if (heaviest != null && heaviest.weightFinal > 0.4 && items.size > 1) {
            add(THREAT, "Single-name risk", "One holding dominates",
                "${heaviest.symbol} is ${pct(heaviest.weightFinal, 0)} of the portfolio. Its result is close to the portfolio's result.",
                min(1.0, heaviest.weightFinal + 0.3), linkedMapOf("symbol" to heaviest.symbol, "weight" to heaviest.weightFinal))
        }

        metrics.maxDrawdown?.let { dd ->
            if (dd < -0.30) add(THREAT, "Deep drawdown", "It has fallen a long way before",
                "The worst peak-to-trough fall was ${pct(dd)}, which needs a ${pct(1 / (1 + dd) - 1)} gain simply to recover.",
                min(1.0, abs(dd) + 0.4), linkedMapOf("max_drawdown" to dd))
            else if (dd > -0.15) add(STRENGTH, "Shallow drawdowns", "Falls have stayed contained",
                "The worst fall was ${pct(dd)}.", 0.4, linkedMapOf("max_drawdown" to dd))
        }

        val rows = attribution.holdings
        if (attribution.available && !rows.isNullOrEmpty()) {
            val best = rows.first()
            val worst = rows.last()
            val wc = worst.contribution ?: 0.0
            if (wc < -0.02) add(WEAKNESS, "Wealth eroder", "${worst.symbol} is costing the portfolio",
                "It returned ${pct(worst.ret)} against the benchmark's ${pct(attribution.benchmarkReturn)}, taking ${pct(abs(wc))} off the result at ${pct(worst.weight, 0)} weight.",
                min(1.0, abs(wc) * 3 + 0.4), linkedMapOf("symbol" to worst.symbol, "contribution" to wc))
            val bc = best.contribution ?: 0.0
            if (bc > 0.02 && (best.weight ?: 0.0) < 0.15) add(OPPORTUNITY, "Underweight winner", "${best.symbol} did well on a small position",
                "It returned ${pct(best.ret)} but held only ${pct(best.weight, 0)} of the book. A larger position would have contributed more than the ${pct(best.contribution)} it did.",
                0.6, linkedMapOf("symbol" to best.symbol, "weight" to best.weight))
            val selection = attribution.selectionEffect
            val allocation = attribution.allocationEffect
            if (selection != null && allocation != null && allocation < -0.02) add(OPPORTUNITY, "Sizing is costing you",
                "The weighting is working against the picks",
                "An equal-weighted version of the same holdings would have returned ${pct(attribution.equalWeightReturn)} against the actual ${pct(attribution.portfolioReturn)}. " +
                    "The picks added ${pct(selection)}; the sizing took ${pct(abs(allocation))} back.",
                0.7, linkedMapOf("allocation_effect" to allocation))
        }

        val variants = sweep.variants
        val bestLabel = sweep.bestBySharpe
        if (variants.isNotEmpty() && bestLabel != null) {
            val current = variants.firstOrNull { it.rule == rule }
            val best = variants.firstOrNull { it.label == bestLabel }
            if (current != null && best != null && best.label != current.label) {
                val gain = (best.sharpe ?: Double.NaN) - (current.sharpe ?: Double.NaN)
                if (gain > 0.02) add(OPPORTUNITY, "Better rebalancing rule", "${best.label} rebalancing suits this portfolio better",
                    "It reaches a Sharpe of ${Py.fixed(best.sharpe!!, 2)} against ${Py.fixed(current.sharpe!!, 2)} today, with " +
                        "${pct(best.costDrag, 2)} of cost drag against ${pct(current.costDrag, 2)}.",
                    0.55, linkedMapOf("suggested" to best.label, "sharpe_gain" to gain))
            }
        }

        if (drag != null && drag > 0.01) add(WEAKNESS, "Cost drag", "Trading is eating the return",
            "Costs took ${pct(drag, 2)} of the total return, on ${pct(turnover, 0)} of turnover.",
            min(1.0, drag * 20 + 0.3), linkedMapOf("cost_drag" to drag))

        val hit = crisis?.hitRate
        if (crisis != null && hit != null && crisis.count >= 3) {
            if (hit >= 0.6) add(STRENGTH, "Holds up in stress", "It has beaten the benchmark in most crises",
                "It outperformed in ${Py.fixed(hit * 100, 0)}% of the ${crisis.count} stress periods covered, averaging ${pct(crisis.averageReturn)}.",
                0.6, linkedMapOf("hit_rate" to hit))
            else if (hit <= 0.4) add(THREAT, "Weak in stress", "It has lagged in most crises",
                "It beat the benchmark in only ${Py.fixed(hit * 100, 0)}% of the ${crisis.count} stress periods covered.",
                0.7, linkedMapOf("hit_rate" to hit))
        }

        for (p in health.pillars) {
            val score = p.score ?: continue
            if (score < 30 && out.none { it.tag.lowercase() == p.label.lowercase() }) {
                add(WEAKNESS, p.label, "${p.label} scores ${Py.fixed(score, 0)}/100",
                    "${p.comment}. Measured as: ${p.formula}.", 0.4 + (30 - score) / 100,
                    linkedMapOf("pillar" to p.key, "score" to score))
            }
        }

        val sorted = out.sortedByDescending { it.severity }.map { f ->
            f.copy(severity = d.c(f.severity) ?: f.severity, evidence = f.evidence.mapValues { (_, v) -> if (v is Double) d.c(v) else v })
        }
        val grouped = linkedMapOf(
            "strengths" to sorted.filter { it.kind == STRENGTH },
            "weaknesses" to sorted.filter { it.kind == WEAKNESS },
            "opportunities" to sorted.filter { it.kind == OPPORTUNITY },
            "threats" to sorted.filter { it.kind == THREAT },
        )
        return Insights(
            sorted.firstOrNull(),
            sorted.take(6).map { InsightTag(it.kind, it.tag) },
            grouped.getValue("strengths"), grouped.getValue("weaknesses"),
            grouped.getValue("opportunities"), grouped.getValue("threats"),
            grouped.mapValues { it.value.size },
        )
    }
}
