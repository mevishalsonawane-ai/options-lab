package com.optionslab.engine.portfolio

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The SIP Backtester form, with IraAlgo's defaults. */
data class SipRequest(
    val symbol: String,
    val exchange: String = "NSE",
    val startDate: LocalDate,
    val endDate: LocalDate,
    /** The installment before any step-up. */
    val amount: Double,
    /** monthly | fortnightly | weekly | quarterly */
    val frequency: String = "monthly",
    /** 1-28, for monthly and quarterly SIPs. */
    val dayOfMonth: Int = 1,
    /** Annual increase, applied on each anniversary of the first installment. */
    val stepUpPercent: Double = 0.0,
    /** Brokerage per installment in percent (capped at Rs 20) and flat rupees; 0 = the preset. */
    val brokeragePercent: Double = 0.0,
    val brokerageFlat: Double = 0.0,
    val costModel: String = "indian_equity",
    val costExchange: String = "NSE",
    val chargeOverrides: Map<String, Map<String, Double?>> = emptyMap(),
    val gstRate: Double? = null,
    val costBps: Double = 0.0,
    val slippage: Double = 0.0,
    /** An index run on the identical schedule, for a SIP-to-SIP comparison. */
    val benchmark: String? = null,
    val benchmarkExchange: String = "NSE_INDEX",
    val source: String = "api",
    /** Rolling XIRR, the heatmaps and the crisis table re-run the SIP many times; false skips them. */
    val includeGrids: Boolean = true,
)

data class SipRequestEcho(
    val symbol: String, val exchange: String, val startDate: LocalDate, val endDate: LocalDate, val amount: Double,
    val frequency: String, val dayOfMonth: Int, val stepUpPercent: Double, val source: String,
)
data class SipCharges(
    val total: Double, val perInstallment: Double, val drag: Double?, val breakdown: Map<String, Double>, val model: String, val exchange: String,
)
data class SipCurves(val value: List<CurvePoint>, val invested: List<CurvePoint>)
/** The same schedule into the benchmark index; `error` alone when it could not be run. */
data class SipBenchmark(
    val symbol: String, val exchange: String, val headline: SipHeadline? = null, val xirr: Double? = null,
    val excessXirr: Double? = null, val beatBenchmark: Boolean? = null, val curve: List<CurvePoint>? = null, val error: String? = null,
)

/** Everything the SIP Backtester renders. Mirrors `run_sip_backtest`'s payload; grids are null without `includeGrids`. */
data class SipResult(
    val request: SipRequestEcho,
    val headline: SipHeadline,
    val underwater: Underwater,
    val drawdown: SipDrawdown,
    val yearly: List<SipYear>,
    val monthlyHeatmap: MonthlyHeatmap,
    val lumpsum: LumpsumComparison?,
    val charges: SipCharges,
    val frequencyComparison: List<FrequencyRow>,
    val curves: SipCurves,
    val installments: List<Installment>,
    val warnings: List<String>,
    val rollingXirr: List<RollingXirr>?,
    val startDateHeatmap: StartDateHeatmap?,
    val crisis: SipCrisis?,
    val sipDateHeatmap: SipDateHeatmap?,
    val benchmark: SipBenchmark?,
)

/**
 * The SIP Backtester. Port of `run_sip_backtest` in `services/sip_service.py`
 * over the `sip/` package; prices come in as daily bars keyed by symbol.
 * Validation failures throw [PortfolioException] with IraAlgo's status and
 * message. `displayRounding = false` returns full precision.
 */
object SipBacktest {
    const val MAX_SIP_YEARS = 30

    fun run(req: SipRequest, prices: Map<String, List<DailyBar>>, displayRounding: Boolean = true): SipResult {
        val d = Disp(displayRounding)
        val start = req.startDate
        val end = req.endDate
        if (end <= start) throw PortfolioException(400, "end_date must be after start_date")
        if (ChronoUnit.DAYS.between(start, end) > MAX_SIP_YEARS * 366L) throw PortfolioException(400, "SIP window cannot exceed $MAX_SIP_YEARS years")
        if (!req.amount.isFinite() || req.amount <= 0) throw PortfolioException(400, "amount must be a positive number")
        if (req.frequency !in SipSchedule.FREQUENCIES) {
            throw PortfolioException(400, "frequency must be one of ${SipSchedule.FREQUENCIES.joinToString(", ")}")
        }
        if ((req.frequency == "monthly" || req.frequency == "quarterly") && req.dayOfMonth !in 1..SipSchedule.MAX_DAY_OF_MONTH) {
            throw PortfolioException(400, "day_of_month must be an integer between 1 and ${SipSchedule.MAX_DAY_OF_MONTH}")
        }
        val exchange = req.exchange.ifEmpty { "NSE" }.uppercase()
        if (exchange !in PriceMatrix.SUPPORTED_EXCHANGES) {
            throw PortfolioException(400, "exchange must be one of ${PriceMatrix.SUPPORTED_EXCHANGES.joinToString(", ")} for a SIP")
        }
        if (!req.benchmark.isNullOrEmpty() && req.benchmarkExchange.uppercase() !in PriceMatrix.BENCHMARK_EXCHANGES) {
            throw PortfolioException(400, "benchmark_exchange must be one of ${PriceMatrix.BENCHMARK_EXCHANGES.joinToString(", ")}")
        }

        val matrix = PriceMatrix.load(listOf(req.symbol), listOf(exchange), start, end, prices, req.source)
        val closes = Ser(matrix.dates, matrix.closes[0])
        val warnings = matrix.warnings[req.symbol].orEmpty().map { "('${it.first}', ${Py.repr(it.second)})" }
        val costs = CostInputs(req.costModel, req.costExchange, req.chargeOverrides, req.gstRate, req.costBps, req.slippage)
            .build(req.brokeragePercent / 100.0, req.brokerageFlat)
        val kw = SipKw(req.frequency, req.dayOfMonth, req.stepUpPercent, costs)

        val result = try {
            SipEngine.run(closes, start, end, req.amount, req.frequency, req.dayOfMonth, req.stepUpPercent, costs, warnings = warnings, symbol = req.symbol)
        } catch (e: SipException) {
            throw PortfolioException(422, e.message ?: "")
        }

        val grids = req.includeGrids
        val crisisRows = if (grids) SipAnalyticsCalc.crisis(closes, req.amount, kw) else null
        return SipResult(
            request = SipRequestEcho(req.symbol, exchange, start, end, req.amount, req.frequency, req.dayOfMonth, req.stepUpPercent, matrix.source),
            headline = SipAnalyticsCalc.headline(result),
            underwater = SipAnalyticsCalc.underwater(result),
            drawdown = SipAnalyticsCalc.drawdown(result),
            yearly = SipAnalyticsCalc.yearly(result),
            monthlyHeatmap = SipAnalyticsCalc.monthlyHeatmap(result),
            lumpsum = SipAnalyticsCalc.lumpsumComparison(closes, result, start, end, costs),
            charges = SipCharges(
                d.r(result.charges, 2),
                d.r(result.charges / maxOf(result.installmentCount, 1), 2),
                if (result.totalInvested != 0.0) d.r(result.charges / result.totalInvested, 6) else null,
                result.chargeBreakdown.mapValues { d.r(it.value, 2) },
                req.costModel, req.costExchange,
            ),
            frequencyComparison = SipAnalyticsCalc.frequencyComparison(closes, start, end, req.amount, kw),
            curves = SipCurves(
                result.dates.indices.map { CurvePoint(result.dates[it], d.r(result.value[it], 2)) },
                result.dates.indices.map { CurvePoint(result.dates[it], d.r(result.invested[it], 2)) },
            ),
            installments = result.installments.map { it.copy(amount = d.r(it.amount, 2)) },
            warnings = result.warnings,
            rollingXirr = if (grids) SipAnalyticsCalc.rollingXirr(closes, req.amount, kw) else null,
            startDateHeatmap = if (grids) SipAnalyticsCalc.startDateHeatmap(closes, req.amount, kw) else null,
            crisis = crisisRows?.let { SipCrisis(SipAnalyticsCalc.crisisSummary(it), it) },
            sipDateHeatmap = if (grids && (req.frequency == "monthly" || req.frequency == "quarterly")) {
                SipAnalyticsCalc.sipDateHeatmap(closes, start, end, req.amount, kw)
            } else null,
            benchmark = if (!req.benchmark.isNullOrEmpty()) benchmarkSip(req, start, end, result, prices, kw, d) else null,
        )
    }

    private fun benchmarkSip(
        req: SipRequest, start: LocalDate, end: LocalDate, result: SipRun, prices: Map<String, List<DailyBar>>, kw: SipKw, d: Disp,
    ): SipBenchmark {
        val symbol = req.benchmark!!
        val exchange = req.benchmarkExchange.uppercase()
        val bench = try {
            val m = PriceMatrix.load(listOf(symbol), listOf(exchange), start, end, prices, req.source, allowedExchanges = PriceMatrix.BENCHMARK_EXCHANGES)
            SipEngine.run(Ser(m.dates, m.closes[0]), start, end, req.amount, kw.frequency, kw.dayOfMonth, kw.stepUpPercent, kw.costs, symbol = symbol)
        } catch (e: RuntimeException) {
            return SipBenchmark(symbol, exchange, error = e.message)
        }
        val bx = Xirr.xirrOrNull(bench.cashFlows)
        val sx = Xirr.xirrOrNull(result.cashFlows)
        return SipBenchmark(
            symbol, exchange, SipAnalyticsCalc.headline(bench), bx,
            if (sx != null && bx != null) sx - bx else null,
            sx != null && bx != null && sx > bx,
            bench.dates.indices.map { CurvePoint(bench.dates[it], d.r(bench.value[it], 2)) },
        )
    }
}
