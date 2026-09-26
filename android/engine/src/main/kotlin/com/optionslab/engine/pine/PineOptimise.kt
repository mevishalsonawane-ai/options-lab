package com.optionslab.engine.pine

import kotlin.math.max

/**
 * Tries a script over a grid of input values and scores each combination.
 *
 * Each combination runs once over all the candles; its closed trades are then split by
 * entry time into the first [Plan.inSamplePct] percent of the period (in-sample, where
 * the best values are picked) and the rest (out-of-sample, the check that they were not
 * just fitted to the past). A combination that only shines in-sample is over-fitted.
 */
object PineOptimise {
    data class Range(val key: String, val from: Double, val to: Double, val step: Double) {
        fun values(): List<Double> {
            if (!(step > 0) || !from.isFinite() || !to.isFinite() || to < from) return listOf(from)
            val out = ArrayList<Double>()
            var v = from
            while (v <= to + step * 1e-9 && out.size < 200) { out += Math.round(v * 1e6) / 1e6; v += step }
            return out
        }
    }

    /** How an indicator's signals trade (strategies use their own orders). */
    data class Signals(val buy: Int, val sell: Int, val reverse: Boolean, val qty: Double, val capital: Double)

    data class Plan(
        val ranges: List<Range>, val inSamplePct: Int = 70, val signals: Signals? = null,
        val qty: Double? = null, val costs: Pine.Costs = Pine.Costs(), val maxCombos: Int = 400, val budgetMs: Long = 90_000,
    )

    data class Summary(val net: Double, val trades: Int, val winRate: Double, val profitFactor: Double?, val maxDrawdown: Double)
    data class Row(val values: Map<String, Double>, val inSample: Summary, val outSample: Summary?, val error: String?)
    data class Result(val rows: List<Row>, val tried: Int, val total: Int, val stoppedEarly: Boolean, val splitTime: Long?)

    fun combos(ranges: List<Range>): Int = ranges.fold(1L) { acc, r -> acc * r.values().size }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun run(
        script: Pine.Script, bars: List<Pine.Bar>, base: Map<String, Any?>, plan: Plan,
        symbol: String = "NIFTY", interval: String = "5m", progress: (Int, Int) -> Unit = { _, _ -> },
    ): Result {
        val grids = plan.ranges.map { it.key to it.values() }
        val total = grids.fold(1L) { acc, g -> acc * g.second.size }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val splitIdx = if (plan.inSamplePct in 1..99 && bars.size >= 2) (bars.size * plan.inSamplePct / 100).coerceIn(1, bars.size - 1) else bars.size
        val splitTime = if (splitIdx < bars.size) bars[splitIdx].time else null
        val deadline = System.nanoTime() + plan.budgetMs * 1_000_000
        val rows = ArrayList<Row>()
        var tried = 0
        var stopped = false
        val limit = minOf(total, plan.maxCombos)
        val idx = IntArray(grids.size)
        while (tried < limit) {
            if (System.nanoTime() > deadline) { stopped = true; break }
            val values = LinkedHashMap<String, Double>()
            grids.forEachIndexed { i, (k, vs) -> values[k] = vs[idx[i]] }
            val inputs = HashMap(base).apply { putAll(values) }
            val left = (deadline - System.nanoTime()) / 1_000_000
            rows += try {
                val r = Pine.run(script, bars, inputs, symbol, interval, plan.qty, budgetMs = max(1L, minOf(10_000L, left)), costs = plan.costs)
                val rep = if (plan.signals != null) {
                    val s = plan.signals
                    Pine.signalBacktest(bars, r.signals[s.buy], r.signals[s.sell], s.reverse, s.qty, s.capital, plan.costs)
                } else r.report
                val closed = rep?.trades?.filter { !it.open }.orEmpty()
                Row(values, summary(closed.filter { it.entryBar < splitIdx }),
                    if (splitIdx < bars.size) summary(closed.filter { it.entryBar >= splitIdx }) else null, r.error?.message)
            } catch (e: Throwable) {
                Row(values, summary(emptyList()), null, e.message ?: e.javaClass.simpleName)
            }
            tried++
            progress(tried, limit)
            // Next combination (odometer over the grids).
            var d = grids.size - 1
            while (d >= 0) { idx[d]++; if (idx[d] < grids[d].second.size) break; idx[d] = 0; d-- }
            if (d < 0) break
        }
        return Result(rows.sortedByDescending { it.inSample.net }, tried, total, stopped || tried < total, splitTime)
    }

    /** Net, count, win rate, profit factor and the deepest fall of the closed-trade P&L curve. */
    fun summary(trades: List<Pine.Trade>): Summary {
        val gp = trades.filter { it.pnl > 0 }.sumOf { it.pnl }
        val gl = -trades.filter { it.pnl < 0 }.sumOf { it.pnl }
        var run = 0.0; var peak = 0.0; var dd = 0.0
        for (t in trades.sortedBy { it.exitBar }) { run += t.pnl; peak = max(peak, run); dd = max(dd, peak - run) }
        return Summary(trades.sumOf { it.pnl }, trades.size, if (trades.isEmpty()) 0.0 else trades.count { it.pnl > 0 } * 100.0 / trades.size,
            if (gl > 0) gp / gl else null, dd)
    }
}
