package com.optionslab.engine.portfolio

import java.time.LocalDate

data class DrawdownEpisode(val start: LocalDate, val valley: LocalDate, val end: LocalDate, val days: Int, val depth: Double?)
data class Rolling(val window: Int, val sharpe: List<CurvePoint>, val volatility: List<CurvePoint>)
/** Rows down, columns across; null cells render as gaps. */
data class Grid(val years: List<String>, val columns: List<String>, val values: List<List<Double?>>)
data class WeeklyGrid(val years: List<String>, val weeks: List<Int>, val values: List<List<Double?>>)
data class Seasonality(
    val bestMonth: Double?, val worstMonth: Double?, val avgUpMonth: Double?, val avgDownMonth: Double?,
    val winMonths: Double?, val winQuarters: Double?,
)
/** A box plot of returns at one holding period. */
data class ReturnQuantile(
    val period: String, val count: Int, val min: Double?, val q1: Double?, val median: Double?, val q3: Double?,
    val max: Double?, val mean: Double?, val negativeShare: Double?, val outliers: List<Double?>,
)
/** One calendar year; the benchmark columns are null without a benchmark. */
data class YearlyReturn(
    val year: String, val portfolio: Double?, val benchmark: Double? = null, val difference: Double? = null,
    val won: Boolean? = null, val multiplier: Double? = null,
)

data class SeriesAnalytics(
    val drawdown: List<CurvePoint>,
    val drawdownEpisodes: List<DrawdownEpisode>?,
    val rolling: Rolling?,
    val monthlyReturns: Grid?,
    val seasonality: Seasonality?,
    val returnQuantiles: List<ReturnQuantile>?,
    val weeklyGrid: WeeklyGrid?,
    val weeklyReturns: List<CurvePoint>,
    val yearlyReturns: List<YearlyReturn>,
)

/** Port of `_series_analytics` in `services/portfolio_service.py`. */
internal object SeriesAnalyticsCalc {
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun build(returns: Ser, rf: Double, benchmark: Ser?, d: Disp): SeriesAnalytics {
        val dd = Ser(returns.dates, Openstatz.toDrawdownSeries(returns.v))
        val drawdown = d.curve(dd, 100.0)

        val episodes = Openstatz.drawdownDetails(dd)
            .sortedBy { it.maxDrawdownPct }.take(5)
            .map { DrawdownEpisode(it.start, it.valley, it.end, it.days, d.c(it.maxDrawdownPct / 100.0)) }
            .takeIf { it.isNotEmpty() }

        val n = returns.size
        val window = if (n > 200) 126 else maxOf(20, n / 4)
        val rolling = if (n > window) Rolling(
            window,
            d.curve(Openstatz.rollingSharpeWithDates(returns, rf, window)),
            d.curve(Openstatz.rollingVolatilityWithDates(returns, window)),
        ) else null

        // monthly_returns(eoy=False): year x month, compounded, missing months 0.
        val byMonth = Openstatz.grouped(returns) { it.year * 100 + it.monthValue }
        val years = byMonth.map { it.first / 100 }.distinct().sorted()
        val cell = byMonth.associate { it.first to it.second }
        val monthly = Grid(
            years.map { it.toString() },
            MONTHS.map { it.uppercase() },
            years.map { y -> (1..12).map { m -> d.c(cell[y * 100 + m] ?: 0.0) } },
        )

        val eom = Openstatz.eom(returns)
        val eoq = Openstatz.eoq(returns)
        val seasonality = Seasonality(
            d.c(eom.maxOrNull() ?: Double.NaN), d.c(eom.minOrNull() ?: Double.NaN),
            d.c(Openstatz.avgWin(eom)), d.c(Openstatz.avgLoss(eom)),
            d.c(Openstatz.winRate(eom)), d.c(Openstatz.winRate(eoq)),
        )

        val quantiles = ArrayList<ReturnQuantile>()
        // distribution(prepare_returns=False): the raw daily returns, NaN dropped.
        val keepIdx = returns.v.indices.filter { !returns.v[it].isNaN() }
        val daily = Ser(keepIdx.map { returns.dates[it] }, DoubleArray(keepIdx.size) { returns.v[keepIdx[it]] })
        val buckets = linkedMapOf(
            "Daily" to daily.v,
            "Weekly" to Openstatz.resample(daily, "W-MON") { Np.comp(it) }.v,
            "Monthly" to Openstatz.resample(daily, "ME") { Np.comp(it) }.v,
            "Quarterly" to Openstatz.resample(daily, "QE") { Np.comp(it) }.v,
            "Yearly" to Openstatz.resample(daily, "YE") { Np.comp(it) }.v,
        )
        for ((period, data) in buckets) {
            val q1 = Np.pdQuantile(data, 0.25)
            val q3 = Np.pdQuantile(data, 0.75)
            val iqr = q3 - q1
            val keep = data.filter { it >= q1 - 1.5 * iqr && it <= q3 + 1.5 * iqr }
            val out = data.filter { !(it >= q1 - 1.5 * iqr && it <= q3 + 1.5 * iqr) }
            val values = keep.filter { !it.isNaN() }.toDoubleArray()
            if (values.isEmpty()) continue
            val outliers = out.filter { !it.isNaN() }
            quantiles.add(
                ReturnQuantile(
                    period, values.size, d.c(values.min()), d.c(Np.pdQuantile(values, 0.25)), d.c(Np.median(values)),
                    d.c(Np.pdQuantile(values, 0.75)), d.c(values.max()), d.c(Np.mean(values)),
                    d.c(values.count { it < 0 }.toDouble() / values.size), outliers.take(40).map { d.c(it) },
                ),
            )
        }

        val weekly = Openstatz.resample(returns, "W-MON") { Np.comp(it) }
        val weeklyGrid = if (weekly.size > 0) {
            val cells = LinkedHashMap<Pair<Int, Int>, Double>()
            weekly.dates.forEachIndexed { i, day -> cells[Openstatz.isoYearWeek(day)] = weekly.v[i] }
            val ys = cells.keys.map { it.first }.distinct().sorted()
            val ws = cells.keys.map { it.second }.distinct().sorted()
            WeeklyGrid(ys.map { it.toString() }, ws, ys.map { y -> ws.map { w -> cells[Pair(y, w)]?.let { d.c(it) } } })
        } else null

        val yearly = ArrayList<YearlyReturn>()
        if (benchmark != null && benchmark.size > 0) {
            val (dates, port, bench) = PortfolioAnalytics.join(returns, benchmark)
            if (dates.isNotEmpty()) {
                val b = Openstatz.grouped(Ser(dates, bench)) { it.year }
                val p = Openstatz.grouped(Ser(dates, port)) { it.year }
                for (i in b.indices) {
                    val benchPct = b[i].second * 100
                    val portPct = p[i].second * 100
                    val benchForRatio = if (benchPct == 0.0) Double.NaN else benchPct
                    yearly.add(
                        YearlyReturn(
                            year = b[i].first.toString(),
                            benchmark = d.c(benchPct / 100.0),
                            portfolio = d.c(portPct / 100.0),
                            difference = d.c((portPct - benchPct) / 100.0),
                            won = portPct >= benchPct,
                            multiplier = if (benchPct > 0) d.c(portPct / benchForRatio) else null,
                        ),
                    )
                }
            }
        } else {
            for ((year, v) in Openstatz.grouped(returns) { it.year }) {
                if (!v.isNaN()) yearly.add(YearlyReturn(year.toString(), d.c(v)))
            }
        }

        return SeriesAnalytics(
            drawdown, episodes, rolling, monthly, seasonality, quantiles, weeklyGrid,
            d.curve(weekly), yearly,
        )
    }
}
