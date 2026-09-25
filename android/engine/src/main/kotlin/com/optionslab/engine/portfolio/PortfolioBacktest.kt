package com.optionslab.engine.portfolio

import java.time.LocalDate

/** One holding; `weight` may be a percentage or a fraction, only the ratios matter. */
data class Holding(val symbol: String, val exchange: String = "NSE", val weight: Double = 0.0)

/** The Portfolio Backtester form, with IraAlgo's defaults. */
data class PortfolioRequest(
    val holdings: List<Holding>,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val benchmark: String? = null,
    val benchmarkExchange: String = "NSE_INDEX",
    /** never | monthly | quarterly | yearly */
    val rebalance: String = "never",
    val driftBand: Double = 0.0,
    /** indian_equity (the statutory schedule) or flat_bps */
    val costModel: String = "indian_equity",
    /** Brokerage as a fraction per order, capped at Rs 20; 0 = the preset (free delivery). */
    val brokeragePct: Double = 0.0,
    val costExchange: String = "NSE",
    val chargeOverrides: Map<String, Map<String, Double?>> = emptyMap(),
    val gstRate: Double? = null,
    val walkWindowYears: Double = 1.0,
    val walkStepYears: Double = 0.5,
    val mcSimulations: Int = 1000,
    val costBps: Double = 0.0,
    val slippage: Double = 0.0,
    val initialCapital: Double = 100_000.0,
    val riskFreeRate: Double = 0.0,
    /** Recorded on the result, as IraAlgo records db/api. */
    val source: String = "api",
)

data class CostModelInfo(val kind: String, val name: String? = null, val bps: Double? = null)

data class PortfolioMeta(
    val symbols: List<String>,
    val targetWeights: Map<String, Double>,
    val rule: String,
    val driftBand: Double,
    val costModel: CostModelInfo,
    val orders: Int,
    val slippage: Double,
    val initialCapital: Double,
    val sessions: Int,
    val source: String,
    val start: LocalDate,
    val end: LocalDate,
    val benchmark: String?,
    val riskFreeRate: Double,
    /** Broker history is price-return: dividends are absent from every figure. */
    val totalReturnBasis: String,
    val dataWarnings: Map<String, List<Pair<LocalDate, Double>>>,
)

data class CorrelationView(val symbols: List<String>, val matrix: List<List<Double?>>, val averagePairwise: Double?)
data class Diversification(
    val hhi: Double?, val effectiveHoldings: Double?, val largestWeight: Double?, val holdings: Int, val diversificationRatio: Double?,
)
/** Trailing close-to-close return per holding over 1W..5Y (session counts 5..1260); null past the history. */
data class AssetReturn(val symbol: String, val last: Double, val windows: Map<String, Double?>)
data class Allocation(val dates: List<LocalDate>, val symbols: List<String>, val series: Map<String, List<Double>>, val average: Map<String, Double>)
/** Contract-note totals over the run; `lines` keys: brokerage, stt, exchange_txn, sebi, stamp_duty, gst, slippage, total, orders. */
data class CostsSummary(val model: String, val lines: Map<String, Double?>, val schedule: String, val drag: Double?, val turnover: Double?)
data class RebalancingSummary(
    val rule: String, val driftBand: Double, val count: Int, val costDrag: Double?, val turnoverTotal: Double?, val dates: List<LocalDate>,
)

/** Everything the Portfolio Backtester's tabs render. Mirrors `run_portfolio_backtest`'s payload. */
data class PortfolioResult(
    val meta: PortfolioMeta,
    val equity: List<CurvePoint>,
    val benchmarkEquity: List<CurvePoint>,
    val metrics: Metrics,
    val items: List<ItemRow>,
    val correlation: CorrelationView,
    val diversification: Diversification,
    val series: SeriesAnalytics,
    val walkForward: WalkForward,
    val monteCarlo: MonteCarlo,
    val rebalancingSweep: RebalancingSweep,
    val attribution: Attribution,
    val assetReturns: List<AssetReturn>,
    val structure: Structure,
    val allocation: Allocation,
    val crisis: CrisisAnalysis,
    val health: Health,
    val costs: CostsSummary,
    val rebalancing: RebalancingSummary,
    val insights: Insights,
)

/**
 * The Portfolio Backtester. Port of `run_portfolio_backtest` in
 * `services/portfolio_service.py`: prices come in as daily bars keyed by
 * symbol (benchmark included) instead of from Historify or the broker.
 *
 * Validation failures throw [PortfolioException] with the status and message
 * IraAlgo answers with. Numbers carry IraAlgo's display rounding; pass
 * `displayRounding = false` for full precision.
 */
object PortfolioBacktest {
    const val MAX_SYMBOLS = 50

    fun run(
        req: PortfolioRequest,
        prices: Map<String, List<DailyBar>>,
        names: Map<String, String> = emptyMap(),
        displayRounding: Boolean = true,
    ): PortfolioResult {
        val d = Disp(displayRounding)
        if (req.holdings.isEmpty()) throw PortfolioException(400, "no holdings supplied")
        if (req.holdings.size > MAX_SYMBOLS) throw PortfolioException(400, "${req.holdings.size} holdings exceeds the $MAX_SYMBOLS limit")
        val symbols = req.holdings.map { it.symbol.trim().uppercase() }
        val exchanges = req.holdings.map { it.exchange.trim().uppercase() }
        val weights = LinkedHashMap<String, Double>()
        symbols.forEachIndexed { i, s -> weights[s] = req.holdings[i].weight }
        if (symbols.toSet().size != symbols.size) throw PortfolioException(400, "duplicate symbol in holdings")

        val matrix = PriceMatrix.load(symbols, exchanges, req.startDate, req.endDate, prices, req.source)
        val policy = RebalancePolicy(req.rebalance, req.driftBand)
        val costs = CostInputs(req.costModel, req.costExchange, req.chargeOverrides, req.gstRate, req.costBps, req.slippage)
            .build(req.brokeragePct)
        val result = PortfolioEngine.run(matrix, weights, policy, costs, req.initialCapital)

        var benchReturns: Ser? = null
        var benchCurve: List<CurvePoint> = emptyList()
        if (!req.benchmark.isNullOrEmpty()) {
            try {
                val bm = req.benchmark.trim().uppercase()
                val b = PriceMatrix.load(listOf(bm), listOf(req.benchmarkExchange), req.startDate, req.endDate, prices, req.source,
                    allowedExchanges = PriceMatrix.BENCHMARK_EXCHANGES)
                val at = HashMap<LocalDate, Double>()
                b.dates.forEachIndexed { i, dt -> at[dt] = b.closes[0][i] }
                // Reindexed onto the portfolio's sessions, forward-filled, leading gaps dropped.
                val dates = ArrayList<LocalDate>(); val vals = ArrayList<Double>()
                var last = Double.NaN
                for (dt in matrix.dates) {
                    at[dt]?.let { last = it }
                    if (!last.isNaN()) { dates.add(dt); vals.add(last) }
                }
                if (dates.size > 1) {
                    benchReturns = Ser(dates.subList(1, dates.size), DoubleArray(dates.size - 1) { vals[it + 1] / vals[it] - 1.0 })
                    benchCurve = dates.indices.map { CurvePoint(dates[it], d.r(vals[it] / vals[0] * req.initialCapital, 4)) }
                }
            } catch (e: PortfolioException) {
                // A missing benchmark degrades the report; it does not invalidate the portfolio.
            }
        }

        val returns = result.returnSeries()
        val holdingReturns = matrix.returns()
        val target = result.target

        val rawMetrics = PortfolioAnalytics.summaryRaw(returns, benchReturns, req.riskFreeRate)
        val metrics = PortfolioAnalytics.metrics(rawMetrics, d)
        val corr = PortfolioAnalytics.correlationMatrix(holdingReturns)
        val conc = PortfolioAnalytics.concentration(target)
        val turnoverTotal = Np.sum(result.turnover.toDoubleArray())

        val meta = PortfolioMeta(
            symbols, symbols.indices.associate { symbols[it] to target[it] }, policy.rule, policy.driftBand,
            when (costs) {
                is CostSchedule -> CostModelInfo("schedule", name = costs.name)
                is FlatCosts -> CostModelInfo("flat_bps", bps = costs.bps)
            },
            result.orders, when (costs) { is CostSchedule -> costs.slippage; is FlatCosts -> costs.slippage },
            req.initialCapital, matrix.sessions, req.source, matrix.start, matrix.end, req.benchmark, req.riskFreeRate,
            "price", matrix.warnings,
        )
        val sweep = Robustness.rebalancingSweep(matrix, weights, costs, req.initialCapital, req.riskFreeRate, d)
        val attribution = AttributionCalc.run(result, holdingReturns, benchReturns, d)
        val structure = Grouping.structure(symbols, target, holdingReturns, names, d)
        val crisis = Crises.analyse(returns, benchReturns, d)
        fun orNan(x: Double?) = if (x == null || x == 0.0) Double.NaN else x
        val health = PortfolioHealth.health(
            symbols, target, holdingReturns, matrix.closes,
            orNan(metrics.sharpe), orNan(metrics.sortino), orNan(metrics.maxDrawdown), result.costDrag, turnoverTotal, d,
        )
        val lines = LinkedHashMap<String, Double?>()
        for ((k, v) in result.costBreakdown) lines[if (k == "tax") "gst" else k] = d.c(v)
        val costsSummary = CostsSummary(
            req.costModel, lines, if (costs is CostSchedule) costs.name else "flat bps", d.c(result.costDrag), d.c(turnoverTotal),
        )
        val items = result.items.map {
            it.copy(
                weightTarget = d.c(it.weightTarget)!!, weightFinal = d.c(it.weightFinal)!!, invested = d.c(it.invested)!!,
                pricePnl = d.c(it.pricePnl)!!, costs = d.c(it.costs)!!, netPnl = d.c(it.netPnl)!!,
                contributionPct = d.c(it.contributionPct)!!, symbolReturn = d.c(it.symbolReturn)!!,
            )
        }

        return PortfolioResult(
            meta = meta,
            equity = d.curve(Ser(result.dates, result.equity)),
            benchmarkEquity = benchCurve,
            metrics = metrics,
            items = items,
            correlation = CorrelationView(symbols, corr.map { row -> row.map { d.c(it) } }, d.c(PortfolioAnalytics.averagePairwiseCorrelation(holdingReturns))),
            diversification = Diversification(
                d.c(conc.hhi), d.c(conc.effectiveHoldings), d.c(conc.largestWeight), conc.holdings,
                d.c(PortfolioAnalytics.diversificationRatio(target, holdingReturns)),
            ),
            series = SeriesAnalyticsCalc.build(returns, req.riskFreeRate, benchReturns, d),
            walkForward = Robustness.walkForward(matrix, weights, policy, costs, req.initialCapital, req.walkWindowYears, req.walkStepYears, d),
            monteCarlo = Robustness.monteCarlo(returns.v, req.mcSimulations, d),
            rebalancingSweep = sweep,
            attribution = attribution,
            assetReturns = assetReturns(matrix, d),
            structure = structure,
            allocation = allocationPath(result, d),
            crisis = crisis,
            health = health,
            costs = costsSummary,
            rebalancing = RebalancingSummary(policy.rule, policy.driftBand, result.rebalanceDates.size, d.c(result.costDrag), d.c(turnoverTotal), result.rebalanceDates),
            insights = InsightRules.build(
                metrics, health, structure, attribution, crisis.summary, sweep, costsSummary.drag, costsSummary.turnover,
                items, policy.rule, d,
            ),
        )
    }

    private val WINDOWS = linkedMapOf("1W" to 5, "1M" to 21, "3M" to 63, "1Y" to 252, "3Y" to 756, "5Y" to 1260)

    internal fun assetReturns(matrix: PriceMatrix, d: Disp): List<AssetReturn> {
        if (matrix.sessions == 0) return emptyList()
        val rows = matrix.symbols.mapIndexed { s, symbol ->
            val series = matrix.closes[s].filter { !it.isNaN() }
            val last = series.last()
            val w = LinkedHashMap<String, Double?>()
            for ((label, n) in WINDOWS) {
                w[label] = if (series.size > n) {
                    val start = series[series.size - n - 1]
                    if (start != 0.0) d.c(d.r(last / start - 1.0, 6)) else null
                } else null
            }
            AssetReturn(symbol, d.c(d.r(last, 2))!!, w)
        }
        return rows.sortedWith(compareBy<AssetReturn> { it.windows["1Y"] == null }.thenBy { -(it.windows["1Y"] ?: 0.0) })
    }

    internal fun allocationPath(run: BacktestRun, d: Disp, maxPoints: Int = 400): Allocation {
        val n = run.dates.size
        if (n == 0) return Allocation(emptyList(), emptyList(), emptyMap(), emptyMap())
        val step = maxOf(1, n / maxPoints)
        val idx = ArrayList((0 until n step step).toList())
        if (idx.last() != n - 1) idx.add(n - 1)
        return Allocation(
            idx.map { run.dates[it] },
            run.symbols,
            run.symbols.indices.associate { s -> run.symbols[s] to idx.map { d.c(d.r(run.weights[it][s], 5))!! } },
            run.symbols.indices.associate { s -> run.symbols[s] to d.c(d.r(Np.mean(DoubleArray(n) { run.weights[it][s] }), 5))!! },
        )
    }
}
