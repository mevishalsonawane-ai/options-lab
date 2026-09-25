package com.optionslab.engine.portfolio

import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The subset of `openstatz.stats` (a QuantStats descendant) the portfolio
 * screens read, with its quirks kept: the price-vs-return guess in
 * `_prepare_returns`, the phantom baseline session in the drawdown functions,
 * the risk-free rate de-annualised by the *rolling window* in
 * `rolling_sharpe`, and `cagr` ignoring rf because it is on the exempt list.
 * Defaults: 252 periods a year, rf = 0, VaR at 95%.
 */
internal object Openstatz {
    const val PERIODS = 252

    /** `_prepare_returns`; `exempt` is true for the callers openstatz skips excess returns for. */
    fun prepare(r: DoubleArray, rf: Double = 0.0, nperiods: Int? = null, exempt: Boolean = false): DoubleArray {
        var data = r.copyOf()
        val finite = data.filter { !it.isNaN() }
        if (finite.isNotEmpty() && finite.min() >= 0 && finite.max() > 1) {
            data = DoubleArray(data.size) { if (it == 0) Double.NaN else data[it] / data[it - 1] - 1.0 }
        }
        data = DoubleArray(data.size) { if (data[it].isNaN() || data[it].isInfinite()) 0.0 else data[it] }
        if (!exempt && rf > 0) {
            val per = if (nperiods != null) (1 + rf).pow(1.0 / nperiods) - 1.0 else rf
            data = DoubleArray(data.size) { data[it] - per }
        }
        return data
    }

    /** `_prepare_prices` with base 1. */
    private fun preparePrices(p: DoubleArray): DoubleArray {
        var data = p.copyOf()
        val finite = data.filter { !it.isNaN() }
        if (finite.isNotEmpty() && (finite.min() < 0 || finite.max() < 1)) {
            // to_prices(returns, base=1): base + base * compsum
            val z = DoubleArray(data.size) { if (data[it].isNaN() || data[it].isInfinite()) 0.0 else data[it] }
            var acc = 1.0
            data = DoubleArray(z.size) { acc *= (z[it] + 1.0); 1.0 + 1.0 * (acc - 1.0) }
        }
        return DoubleArray(data.size) { if (data[it].isNaN()) 0.0 else data[it] }
    }

    private fun baseline(prices: DoubleArray): Double {
        if (prices.isEmpty()) return 1.0
        val first = prices[0]
        return if (first > 1000) 100000.0 else if (first > 10) 100.0 else 1.0
    }

    fun cagr(returns: DoubleArray, rf: Double = 0.0, periods: Int = PERIODS): Double {
        val total = Np.comp(prepare(returns, rf, exempt = true))
        val years = returns.size.toDouble() / periods
        return abs(total + 1.0).pow(1.0 / years) - 1
    }

    fun volatility(returns: DoubleArray) = Np.std(prepare(returns)) * sqrt(PERIODS.toDouble())

    fun sharpe(returns: DoubleArray, rf: Double = 0.0): Double {
        val r = prepare(returns, rf, PERIODS)
        return Np.mean(r) / Np.std(r) * sqrt(PERIODS.toDouble())
    }

    fun sortino(returns: DoubleArray, rf: Double = 0.0): Double {
        val r = prepare(returns, rf, PERIODS)
        val neg = r.filter { it < 0 }.map { it * it }.toDoubleArray()
        val downside = sqrt(Np.sum(neg) / r.size)
        val res = if (downside == 0.0) Double.NaN else Np.mean(r) / downside
        return res * sqrt(PERIODS.toDouble())
    }

    /** `max_drawdown(prices)`: prices guessed as returns when they dip below 0 or never reach 1. */
    fun maxDrawdown(prices: DoubleArray): Double {
        val p = preparePrices(prices)
        if (p.isEmpty()) return 0.0
        var peak = baseline(p)
        var worst = 1.0 // phantom session: baseline / baseline
        for (x in p) {
            peak = maxOf(peak, x)
            val ratio = x / peak
            if (ratio < worst) worst = ratio
        }
        return worst - 1
    }

    fun calmar(returns: DoubleArray): Double {
        val r = prepare(returns)
        return cagr(r) / abs(maxDrawdown(r))
    }

    fun toDrawdownSeries(returns: DoubleArray): DoubleArray {
        val p = preparePrices(returns)
        var peak = baseline(p)
        return DoubleArray(p.size) {
            peak = maxOf(peak, p[it])
            val dd = p[it] / peak - 1.0
            if (dd.isInfinite() || dd == 0.0) 0.0 else dd
        }
    }

    fun winRate(returns: DoubleArray): Double {
        val nonZero = returns.count { it != 0.0 }
        if (nonZero == 0) return 0.0
        return returns.count { it > 0 }.toDouble() / nonZero
    }

    fun valueAtRisk(returns: DoubleArray, confidence: Double = 0.95): Double {
        val r = prepare(returns)
        val mu = Np.mean(r)
        val sigma = Np.std(r)
        if (!(sigma > 0)) return Double.NaN
        return Normal.ppf(1 - confidence) * sigma + mu
    }

    fun conditionalValueAtRisk(returns: DoubleArray): Double {
        val r = prepare(returns)
        val v = valueAtRisk(r)
        val below = r.filter { it < v }.toDoubleArray()
        val c = Np.mean(below)
        return if (!c.isNaN()) c else v
    }

    fun ulcerIndex(returns: DoubleArray): Double {
        val dd = toDrawdownSeries(returns)
        return sqrt(Np.sum(DoubleArray(dd.size) { dd[it] * dd[it] }) / (returns.size - 1))
    }

    fun recoveryFactor(returns: DoubleArray): Double {
        val r = prepare(returns)
        val total = Np.sum(r) - 0.0
        val mdd = maxDrawdown(r)
        if (mdd == 0.0) return Double.NaN
        return abs(total) / abs(mdd)
    }

    fun tailRatio(returns: DoubleArray, cutoff: Double = 0.95): Double {
        val r = prepare(returns)
        val upper = Np.pdQuantile(r, cutoff)
        val lower = Np.pdQuantile(r, 1 - cutoff)
        if (upper.isNaN() || lower.isNaN() || lower == 0.0) return Double.NaN
        return abs(upper / lower)
    }

    fun skew(returns: DoubleArray) = Np.skew(prepare(returns))
    fun kurtosis(returns: DoubleArray) = Np.kurt(prepare(returns))

    /** `greeks`: beta from numpy's covariance, alpha annualised; NaN becomes 0. */
    fun greeks(returns: DoubleArray, benchmark: DoubleArray): Pair<Double, Double> {
        val r = prepare(returns)
        val b = prepare(benchmark)
        val m = Np.cov2(r, b)
        val beta = if (m[1][1] == 0.0) Double.NaN else m[0][1] / m[1][1]
        val alpha = (Np.mean(r) - beta * Np.mean(b)) * PERIODS
        return Pair(if (beta.isNaN()) 0.0 else beta, if (alpha.isNaN()) 0.0 else alpha)
    }

    fun informationRatio(returns: DoubleArray, benchmark: DoubleArray): Double {
        val r = prepare(returns)
        val b = prepare(benchmark)
        val diff = DoubleArray(r.size) { r[it] - b[it] }
        val std = Np.std(diff)
        return if (std != 0.0) Np.mean(diff) / std else 0.0
    }

    // ── rolling windows, with pandas' constant-window and sign guards ──────

    private fun windowMean(a: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        var neg = 0
        var same = true
        for (j in from until to) { s += a[j]; if (1.0 / a[j] < 0 || a[j] < 0) neg++; if (a[j] != a[from]) same = false }
        val n = to - from
        var m = s / n
        if (same) m = a[to - 1] else if (neg == 0 && m < 0) m = 0.0 else if (neg == n && m > 0) m = 0.0
        return m
    }

    private fun windowStd(a: DoubleArray, from: Int, to: Int): Double {
        val n = to - from
        if (n <= 1) return if (n == 1) 0.0 else Double.NaN
        if ((from until to).all { a[it] == a[from] }) return 0.0
        val slice = a.copyOfRange(from, to)
        val mean = slice.sum() / n
        var ss = 0.0
        for (x in slice) ss += (x - mean) * (x - mean)
        return sqrt(ss / (n - 1))
    }

    /** `rolling_sharpe(...).dropna()`, dated by each window's last session. */
    fun rollingSharpeWithDates(returns: Ser, rf: Double, window: Int): Ser {
        val r = prepare(returns.v, rf, window)
        val d = ArrayList<LocalDate>(); val v = ArrayList<Double>()
        for (end in window..r.size) {
            val x = windowMean(r, end - window, end) / windowStd(r, end - window, end) * sqrt(PERIODS.toDouble())
            if (!x.isNaN()) { d.add(returns.dates[end - 1]); v.add(x) }
        }
        return Ser(d, v.toDoubleArray())
    }

    /** `rolling_volatility(...).dropna()`; openstatz passes the window as rf here, which its exemption list ignores. */
    fun rollingVolatilityWithDates(returns: Ser, window: Int): Ser {
        val r = prepare(returns.v, window.toDouble(), exempt = true)
        val d = ArrayList<LocalDate>(); val v = ArrayList<Double>()
        for (end in window..r.size) {
            val x = windowStd(r, end - window, end) * sqrt(PERIODS.toDouble())
            if (!x.isNaN()) { d.add(returns.dates[end - 1]); v.add(x) }
        }
        return Ser(d, v.toDoubleArray())
    }

    // ── calendar aggregation ────────────────────────────────────────────────

    /** `aggregate_returns` grouping by a key (groupby sorts keys), compounded. */
    fun <K : Comparable<K>> grouped(r: Ser, key: (LocalDate) -> K): List<Pair<K, Double>> {
        val groups = java.util.TreeMap<K, ArrayList<Double>>()
        r.dates.forEachIndexed { i, d -> groups.getOrPut(key(d)) { ArrayList() }.add(r.v[i]) }
        return groups.map { (k, v) -> Pair(k, Np.comp(v.toDoubleArray())) }
    }

    fun eom(r: Ser) = grouped(r) { it.year * 100 + it.monthValue }.map { it.second }.toDoubleArray()
    fun eoq(r: Ser) = grouped(r) { it.year * 10 + (it.monthValue - 1) / 3 + 1 }.map { it.second }.toDoubleArray()

    fun avgWin(a: DoubleArray) = Np.mean(a.filter { it > 0 }.toDoubleArray())
    fun avgLoss(a: DoubleArray) = Np.mean(a.filter { it < 0 }.toDoubleArray())

    /** Resample bin label for a date: W-MON (next Monday), ME, QE, YE. */
    fun binLabel(d: LocalDate, rule: String): LocalDate = when (rule) {
        "W-MON" -> d.plusDays(((8 - d.dayOfWeek.value) % 7).toLong())
        "ME" -> d.withDayOfMonth(d.lengthOfMonth())
        "QE" -> { val m = ((d.monthValue - 1) / 3 + 1) * 3; LocalDate.of(d.year, m, 1).let { it.withDayOfMonth(it.lengthOfMonth()) } }
        else -> LocalDate.of(d.year, 12, 31)
    }

    private fun nextLabel(d: LocalDate, rule: String): LocalDate = when (rule) {
        "W-MON" -> d.plusWeeks(1)
        "ME" -> d.plusMonths(1).let { it.withDayOfMonth(it.lengthOfMonth()) }
        "QE" -> d.plusMonths(3).let { it.withDayOfMonth(it.lengthOfMonth()) }
        else -> d.plusYears(1)
    }

    /**
     * `resample(rule).apply(f)`: one value per bin from the first bin to the
     * last, empty bins included (compounding an empty bin gives 0.0, as pandas'
     * apply does; `emptyValue` overrides that for `.last()`).
     */
    fun resample(r: Ser, rule: String, emptyValue: Double = 0.0, f: (DoubleArray) -> Double): Ser {
        if (r.size == 0) return Ser(emptyList(), DoubleArray(0))
        val groups = LinkedHashMap<LocalDate, ArrayList<Double>>()
        r.dates.forEachIndexed { i, d -> groups.getOrPut(binLabel(d, rule)) { ArrayList() }.add(r.v[i]) }
        val labels = ArrayList<LocalDate>()
        var cur = binLabel(r.dates.first(), rule)
        val last = binLabel(r.dates.last(), rule)
        while (cur <= last) { labels.add(cur); cur = nextLabel(cur, rule) }
        return Ser(labels, DoubleArray(labels.size) { i -> groups[labels[i]]?.let { f(it.toDoubleArray()) } ?: emptyValue })
    }

    fun isoYearWeek(d: LocalDate) = Pair(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    /** `drawdown_details`, sorted worst first: (start, valley, end, days, max drawdown in percent). */
    data class Episode(val start: LocalDate, val valley: LocalDate, val end: LocalDate, val days: Int, val maxDrawdownPct: Double)

    fun drawdownDetails(dd: Ser): List<Episode> {
        val n = dd.size
        if (n == 0) return emptyList()
        val noDd = BooleanArray(n) { dd.v[it] == 0.0 }
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        for (i in 1 until n) if (!noDd[i] && noDd[i - 1]) starts.add(i)
        // ends: the session before a recovery (shifted back one).
        for (i in 1 until n) if (noDd[i] && !noDd[i - 1]) ends.add(i - 1)
        if (starts.isEmpty()) return emptyList()
        if (ends.isNotEmpty() && starts[0] > ends[0]) starts.add(0, 0)
        if (ends.isEmpty() || starts.last() > ends.last()) ends.add(n - 1)
        val out = ArrayList<Episode>()
        for (i in starts.indices) {
            val s = starts[i]; val e = ends[i]
            var valley = s
            for (j in s..e) if (dd.v[j] < dd.v[valley]) valley = j
            out.add(
                Episode(
                    dd.dates[s], dd.dates[valley], dd.dates[e],
                    (java.time.temporal.ChronoUnit.DAYS.between(dd.dates[s], dd.dates[e]) + 1).toInt(),
                    dd.v[valley] * 100,
                ),
            )
        }
        return out
    }
}
