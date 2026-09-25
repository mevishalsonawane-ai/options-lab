package com.optionslab.engine.portfolio

import java.time.LocalDate

/** One parsed broker holding. */
data class LiveHolding(
    val symbol: String, val exchange: String, val quantity: Double, val averagePrice: Double, val lastPrice: Double,
    val pnl: Double, val product: String = "",
) {
    val invested get() = quantity * averagePrice
    val current get() = quantity * lastPrice
}

data class HoldingSummaryRow(
    val symbol: String, val exchange: String, val quantity: Double, val averagePrice: Double, val lastPrice: Double,
    val invested: Double, val current: Double, val pnl: Double, val pnlPct: Double?, val weight: Double, val product: String,
)

/** Invested/current/P&L totals; `invested` and `pnlPct` are null when the feed carries no cost basis. */
data class HoldingsSummary(
    val holdings: List<HoldingSummaryRow>, val weights: Map<String, Double>, val invested: Double?, val current: Double,
    val pnl: Double, val pnlPct: Double?, val hasCostBasis: Boolean, val count: Int,
)

data class AnalyzerMeta(val lookbackDays: Int, val start: LocalDate, val end: LocalDate, val basis: String)

/**
 * The Portfolio Analyzer's page: today's holdings and, when any are on NSE/BSE,
 * the full backtest analysis of those weights over the lookback, or the reason
 * it could not run.
 */
data class AnalyzerResult(
    val summary: HoldingsSummary,
    val analysis: PortfolioResult?,
    val analysisError: String?,
    val skipped: List<String>,
    val meta: AnalyzerMeta,
)

/**
 * The Portfolio Analyzer. Port of `analyse_live_holdings` and
 * `portfolio/holdings.py`: live holdings give the weights (by current value),
 * past prices give the behaviour, run buy-and-hold over `lookbackDays`.
 * It shows how today's portfolio would have behaved, not how the account did.
 */
object PortfolioAnalyzer {
    const val BASIS =
        "Current holdings weighted by market value, run over past prices. This shows how today's portfolio would " +
            "have behaved, not how the account performed -- a holdings payload does not say when each lot was bought."

    private fun number(value: Any?): Double {
        if (value == null || value == "" || value == "-") return 0.0
        return when (value) {
            is Number -> value.toDouble()
            is Boolean -> if (value) 1.0 else 0.0
            is String -> value.trim().let { s ->
                s.toDoubleOrNull()?.takeIf { !s.endsWith("d", true) && !s.endsWith("f", true) }
                    ?: when (s.lowercase()) { "nan", "+nan", "-nan" -> Double.NaN; "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY; "-inf", "-infinity" -> Double.NEGATIVE_INFINITY; else -> 0.0 }
            }
            else -> 0.0
        }
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is Boolean -> value
        else -> true
    }

    /** `parse_holdings`: coerce broker numerics; keep rows with a quantity and a usable price. */
    fun parseHoldings(rows: List<Map<String, Any?>>): List<LiveHolding> {
        val out = ArrayList<LiveHolding>()
        for (row in rows) {
            val symbol = Py.str(row.getOrDefault("symbol", "")).trim().uppercase()
            if (symbol.isEmpty()) continue
            val quantity = number(row["quantity"])
            val average = number(row["average_price"])
            val pnl = number(row["pnl"])
            var last = number(if (truthy(row["last_price"])) row["last_price"] else row["ltp"])
            if (listOf(quantity, average, pnl, last).any { !it.isFinite() }) continue
            if (quantity <= 0) continue
            if (last <= 0 && average > 0) last = average + pnl / quantity
            if (last <= 0) continue
            out.add(
                LiveHolding(
                    symbol, Py.str(row.getOrDefault("exchange", "NSE")).trim().uppercase(), quantity, average, last, pnl,
                    Py.str(row.getOrDefault("product", "")),
                ),
            )
        }
        return out
    }

    internal fun summary(holdings: List<LiveHolding>, d: Disp): HoldingsSummary {
        if (holdings.isEmpty()) return HoldingsSummary(emptyList(), emptyMap(), 0.0, 0.0, 0.0, 0.0, false, 0)
        val invested = Py.sum(holdings.map { it.invested })
        val current = Py.sum(holdings.map { it.current })
        val hasCost = holdings.all { it.averagePrice > 0 }
        val weights = LinkedHashMap<String, Double>()
        val rows = holdings.map { h ->
            val weight = if (current > 0) h.current / current else 0.0
            weights[h.symbol] = weight * 100.0
            HoldingSummaryRow(
                h.symbol, h.exchange, h.quantity, d.r(h.averagePrice, 2), d.r(h.lastPrice, 2), d.r(h.invested, 2),
                d.r(h.current, 2), d.r(h.pnl, 2),
                if (h.invested != 0.0) d.r((h.current / h.invested - 1.0) * 100, 2) else null,
                d.r(weight, 5), h.product,
            )
        }.sortedByDescending { it.current }
        return HoldingsSummary(
            rows, weights,
            if (hasCost) d.r(invested, 2) else null,
            d.r(current, 2),
            if (hasCost) d.r(current - invested, 2) else d.r(Py.sum(holdings.map { it.pnl }), 2),
            if (hasCost) d.r((current / invested - 1.0) * 100, 2) else null,
            hasCost, rows.size,
        )
    }

    private fun cleaned(s: HoldingsSummary, d: Disp) = s.copy(
        holdings = s.holdings.map {
            it.copy(
                quantity = d.c(it.quantity)!!, averagePrice = d.c(it.averagePrice)!!, lastPrice = d.c(it.lastPrice)!!,
                invested = d.c(it.invested)!!, current = d.c(it.current)!!, pnl = d.c(it.pnl)!!, pnlPct = d.c(it.pnlPct),
                weight = d.c(it.weight)!!,
            )
        },
        weights = s.weights.mapValues { d.c(it.value)!! },
        invested = d.c(s.invested), current = d.c(s.current)!!, pnl = d.c(s.pnl)!!, pnlPct = d.c(s.pnlPct),
    )

    /**
     * Analyse the rows of a broker holdings payload (`symbol`, `exchange`,
     * `quantity`, `average_price`, `last_price` or `ltp`, `pnl`, `product`;
     * numbers or numeric strings). `today` stands in for the server's date.
     */
    fun analyze(
        rows: List<Map<String, Any?>>,
        prices: Map<String, List<DailyBar>>,
        today: LocalDate,
        lookbackDays: Int = 365,
        benchmark: String? = "NIFTY",
        benchmarkExchange: String = "NSE_INDEX",
        riskFreeRate: Double = 0.0,
        names: Map<String, String> = emptyMap(),
        source: String = "api",
        displayRounding: Boolean = true,
    ): AnalyzerResult {
        val d = Disp(displayRounding)
        val holdings = parseHoldings(rows)
        if (holdings.isEmpty()) throw PortfolioException(422, "no holdings with a usable quantity and price")
        val summary = summary(holdings, d)
        val tradable = summary.holdings.filter { it.exchange in PriceMatrix.SUPPORTED_EXCHANGES }
            .map { Holding(it.symbol, it.exchange, it.weight * 100) }
        val skipped = summary.holdings.filter { it.exchange !in PriceMatrix.SUPPORTED_EXCHANGES }.map { it.symbol }
        val end = today
        val start = end.minusDays(lookbackDays.toLong())
        var analysis: PortfolioResult? = null
        var error: String? = null
        if (tradable.isNotEmpty()) {
            try {
                analysis = PortfolioBacktest.run(
                    PortfolioRequest(
                        tradable, start, end, benchmark = benchmark, benchmarkExchange = benchmarkExchange,
                        rebalance = "never", riskFreeRate = riskFreeRate, source = source,
                    ),
                    prices, names, displayRounding,
                )
            } catch (e: PortfolioException) {
                error = e.message ?: "analysis unavailable"
            }
        }
        return AnalyzerResult(cleaned(summary, d), analysis, error, skipped, AnalyzerMeta(lookbackDays, start, end, BASIS))
    }
}
