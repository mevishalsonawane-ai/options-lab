package com.optionslab.engine

import kotlin.math.sqrt

object Stats {
    /** pandas' median: the mean of the two middle values for an even count. */
    fun median(xs: Collection<Double>): Double {
        val v = xs.filter { !it.isNaN() }.sorted()
        if (v.isEmpty()) return Double.NaN
        val n = v.size
        return if (n % 2 == 1) v[n / 2] else (v[n / 2 - 1] + v[n / 2]) / 2.0
    }

    fun mean(xs: Collection<Double>): Double {
        val v = xs.filter { !it.isNaN() }
        return if (v.isEmpty()) Double.NaN else v.sum() / v.size
    }

    /** Sample standard deviation (ddof = 1), as pandas computes it. */
    fun std(xs: Collection<Double>): Double {
        val v = xs.filter { !it.isNaN() }
        if (v.size < 2) return Double.NaN
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    /** numpy's default ('linear') percentile. */
    fun percentile(xs: Collection<Double>, q: Double): Double {
        val v = xs.filter { !it.isNaN() }.sorted()
        if (v.isEmpty()) return Double.NaN
        val pos = q / 100.0 * (v.size - 1)
        val lo = kotlin.math.floor(pos).toInt()
        val hi = kotlin.math.ceil(pos).toInt()
        return v[lo] + (v[hi] - v[lo]) * (pos - lo)
    }

    /** Average ranks (ties share the mean rank), as pandas' rank() does. */
    fun rank(xs: DoubleArray): DoubleArray {
        val idx = xs.indices.sortedBy { xs[it] }
        val out = DoubleArray(xs.size)
        var i = 0
        while (i < idx.size) {
            var j = i
            while (j + 1 < idx.size && xs[idx[j + 1]] == xs[idx[i]]) j++
            val r = (i + j) / 2.0 + 1.0
            for (k in i..j) out[idx[k]] = r
            i = j + 1
        }
        return out
    }

    fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        if (n < 2) return Double.NaN
        val ma = a.average()
        val mb = b.average()
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in 0 until n) {
            val da = a[i] - ma
            val db = b[i] - mb
            sab += da * db; saa += da * da; sbb += db * db
        }
        return if (saa == 0.0 || sbb == 0.0) Double.NaN else sab / sqrt(saa * sbb)
    }

    fun spearman(a: DoubleArray, b: DoubleArray): Double {
        val keep = a.indices.filter { !a[it].isNaN() && !b[it].isNaN() }
        if (keep.size < 3) return Double.NaN
        val x = DoubleArray(keep.size) { a[keep[it]] }
        val y = DoubleArray(keep.size) { b[keep[it]] }
        if (x.distinct().size < 2 || y.distinct().size < 2) return Double.NaN
        return pearson(rank(x), rank(y))
    }
}

/**
 * The summary the PC prints per split (`cli._report`), as data.
 */
data class Summary(
    val label: String,
    val n: Int,
    val winRate: Double,
    val mean: Double,
    val median: Double,
    val worst: Double,
    val best: Double,
    val total: Double,
    val creditPerLot: Double,
    val medianCost: Double,
    val costShare: Double,
    val skipped: Int,
) {
    companion object {
        fun of(label: String, trades: List<ExpiryPut.Trade>, skipped: Int): Summary? {
            if (trades.isEmpty()) return null
            val pnl = trades.map { it.netPnl }
            // Read the lot off the trades: under dated lots it varies by session.
            val credit = Stats.median(trades.map { it.credit * it.lotSize })
            val cost = Stats.median(trades.map { it.cost })
            return Summary(
                label = label, n = trades.size,
                winRate = trades.count { it.won }.toDouble() / trades.size,
                mean = pnl.average(), median = Stats.median(pnl),
                worst = pnl.min(), best = pnl.max(), total = pnl.sum(),
                creditPerLot = credit, medianCost = cost, costShare = cost / credit,
                skipped = skipped,
            )
        }
    }
}

/** The whole `expiry-put` command's result. */
data class BacktestReport(
    val params: ExpiryPut.Params,
    val days: List<java.time.LocalDate>,
    val train: List<ExpiryPut.Trade>,
    val holdout: List<ExpiryPut.Trade>,
    val trainDays: List<java.time.LocalDate>,
    val holdoutDays: List<java.time.LocalDate>,
    val skipped: List<ExpiryPut.Skip>,
    val plan: Sizing.Plan?,
    val planError: String?,
) {
    val all: List<ExpiryPut.Trade> get() = (train + holdout)
    val trainSummary get() = Summary.of("train", train, skipped.count { it.day in trainDays })
    val holdoutSummary get() = Summary.of("holdout", holdout, skipped.count { it.day in holdoutDays })
    val combined get() = Summary.of("combined", all, skipped.size)

    /** Holdout minus train mean, and whether it held up (> -Rs 50). */
    val drift: Double? get() =
        if (train.isNotEmpty() && holdout.isNotEmpty()) holdout.map { it.netPnl }.average() - train.map { it.netPnl }.average() else null

    val losers: List<ExpiryPut.Trade> get() = all.filter { !it.won }.sortedBy { it.netPnl }

    companion object {
        fun run(
            sessions: List<Session>, params: ExpiryPut.Params, holdout: Int,
            capital: Double, survive: Double,
        ): BacktestReport {
            val (trades, skips) = ExpiryPut.runBacktest(sessions, params)
            return assemble(sessions.map { it.day }, trades, skips, params, holdout, capital, survive)
        }

        /**
         * Split an already-run backtest into train and holdout. Each session's
         * trade depends only on its own chain, so running everything first and
         * sealing the tail afterwards is the same computation as the PC's -
         * and it lets a phone stream sessions instead of holding them all.
         */
        fun assemble(
            days: List<java.time.LocalDate>, trades: List<ExpiryPut.Trade>, skips: List<ExpiryPut.Skip>,
            params: ExpiryPut.Params, holdout: Int, capital: Double, survive: Double,
        ): BacktestReport {
            val (trainDays, holdoutDays) =
                if (holdout > 0) ExpiryPut.splitSessions(days, holdout) else days.sorted() to emptyList()
            val sealed = holdoutDays.toSet()
            val sorted = trades.sortedBy { it.session }
            var plan: Sizing.Plan? = null
            var err: String? = null
            try { plan = Sizing.planPosition(capital, survive) } catch (e: IllegalArgumentException) { err = e.message }
            return BacktestReport(
                params, days.sorted(), sorted.filter { it.session !in sealed }, sorted.filter { it.session in sealed },
                trainDays, holdoutDays, skips.sortedBy { it.day }, plan, err,
            )
        }
    }
}
