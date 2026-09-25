package com.optionslab.engine.portfolio

import com.optionslab.engine.portfolio.Fixtures.arr
import com.optionslab.engine.portfolio.Fixtures.obj
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The three services against IraAlgo's own output on the same bars: every
 * scenario twice, once as the endpoint returns it (display rounding included)
 * and once with every rounding turned off, at 1e-9 relative.
 */
class ServiceParityTest {
    private val prices = Fixtures.prices

    private fun d(x: Any?) = x as Double?
    private fun s(x: Any?) = x as String?

    private fun portfolioRequest(r: Map<String, Any?>) = PortfolioRequest(
        holdings = arr(r["holdings"]).map { h ->
            val m = obj(h)
            Holding(m["symbol"] as String, (m["exchange"] as String?) ?: "NSE", d(m["weight"]) ?: 0.0)
        },
        startDate = LocalDate.parse(r["start_date"] as String),
        endDate = LocalDate.parse(r["end_date"] as String),
        benchmark = s(r["benchmark"]),
        benchmarkExchange = s(r["benchmark_exchange"]) ?: "NSE_INDEX",
        rebalance = s(r["rebalance"]) ?: "never",
        driftBand = d(r["drift_band"]) ?: 0.0,
        costModel = s(r["cost_model"]) ?: "indian_equity",
        brokeragePct = d(r["brokerage_pct"]) ?: 0.0,
        costExchange = s(r["cost_exchange"]) ?: "NSE",
        chargeOverrides = Fixtures.overrides(r["charge_overrides"]),
        gstRate = d(r["gst_rate"]),
        costBps = d(r["cost_bps"]) ?: 0.0,
        slippage = d(r["slippage"]) ?: 0.0,
        initialCapital = d(r["initial_capital"]) ?: 100_000.0,
        riskFreeRate = d(r["risk_free_rate"]) ?: 0.0,
        mcSimulations = (d(r["mc_simulations"]) ?: 1000.0).toInt(),
        walkWindowYears = d(r["walk_window_years"]) ?: 1.0,
        walkStepYears = d(r["walk_step_years"]) ?: 0.5,
        source = "db",
    )

    private fun sipRequest(r: Map<String, Any?>) = SipRequest(
        symbol = r["symbol"] as String,
        exchange = s(r["exchange"]) ?: "NSE",
        startDate = LocalDate.parse(r["start_date"] as String),
        endDate = LocalDate.parse(r["end_date"] as String),
        amount = d(r["amount"])!!,
        frequency = s(r["frequency"]) ?: "monthly",
        dayOfMonth = (d(r["day_of_month"]) ?: 1.0).toInt(),
        stepUpPercent = d(r["step_up_percent"]) ?: 0.0,
        brokeragePercent = d(r["brokerage_percent"]) ?: 0.0,
        brokerageFlat = d(r["brokerage_flat"]) ?: 0.0,
        costModel = s(r["cost_model"]) ?: "indian_equity",
        costExchange = s(r["cost_exchange"]) ?: "NSE",
        chargeOverrides = Fixtures.overrides(r["charge_overrides"]),
        gstRate = d(r["gst_rate"]),
        costBps = d(r["cost_bps"]) ?: 0.0,
        slippage = d(r["slippage"]) ?: 0.0,
        benchmark = s(r["benchmark"]),
        benchmarkExchange = s(r["benchmark_exchange"]) ?: "NSE_INDEX",
        source = "db",
        includeGrids = (r["include_grids"] as Boolean?) ?: true,
    )

    /** Messages naming Historify are about IraAlgo's store; the port says where its bars were missing. */
    private fun sameMessage(kotlin: String?, python: String?) =
        if (python != null && "Historify" in python) kotlin != null && kotlin.substringBefore(" history") == python.substringBefore(" history")
        else kotlin == python

    private val skipStoreMessages: (String) -> Boolean = { it.endsWith(".benchmark.error") }

    private var leaves = 0
    private var worst = 0.0
    private var worstAt = ""

    private fun check(name: String, fixture: Map<String, Any?>, mode: String, run: (Boolean) -> Any?, tol: (String) -> Pair<Double, Double>) {
        val status = (fixture["status"] as Double).toInt()
        val py = obj(fixture[mode])
        if (status != 200) {
            try {
                run(mode == "api")
                fail("$name: expected status $status (${py["message"]}), kotlin succeeded")
            } catch (e: PortfolioException) {
                assertEquals(status, e.status, "$name: ${e.message} vs ${py["message"]}")
                assertTrue(sameMessage(e.message, py["message"] as String?), "$name: '${e.message}' vs '${py["message"]}'")
            }
            return
        }
        val k = Fixtures.tree(run(mode == "api"))
        val diff = Fixtures.compare(k, py, "$name/$mode", tol = tol, skip = skipStoreMessages)
        leaves += diff.compared
        if (diff.worstRel > worst) { worst = diff.worstRel; worstAt = diff.worstPath }
        if (diff.problems.isNotEmpty()) {
            fail("$name/$mode: ${diff.problems.size} mismatches of ${diff.compared}:\n" + diff.problems.take(25).joinToString("\n"))
        }
    }

    private val exact: (String) -> Pair<Double, Double> = { Pair(1e-9, 1e-12) }

    /** XIRR is Brent's root to xtol 1e-10; everything computed from one inherits that. */
    private val sipTol: (String) -> Pair<Double, Double> = { path ->
        val iterative = listOf("xirr", "advantage", "rolling_xirr", "sip_date_heatmap", "start_date_heatmap", ".best", ".worst", ".median", ".spread")
        if (iterative.any { it in path }) Pair(1e-9, 1e-8) else Pair(1e-9, 1e-12)
    }

    private val portfolioFixtures = listOf(
        "p1_quarterly_india_costs_rf", "p2_late_listing_monthly_drift_flat", "p3_bse_overrides_short",
        "p4_banks_split_never", "p5_tiny_window_missing_benchmark", "p6_brokerage_flat_override_monthly",
        "p7_suspension_late_benchmark", "p8_nine_holdings_yearly",
        "e1_duplicate_symbol", "e2_missing_history", "e3_zero_weights", "e4_bad_exchange", "e5_no_overlap", "e6_negative_override",
    )

    @Test fun `portfolio backtester matches IraAlgo`() {
        for (name in portfolioFixtures) {
            val f = obj(Fixtures.load(name))
            val req = portfolioRequest(obj(f["request"]))
            for (mode in listOf("api", "raw")) {
                check(name, f, mode, { rounded -> PortfolioBacktest.run(req, prices, Fixtures.names, rounded) }, exact)
            }
        }
        assertTrue(leaves > 50_000, "compared only $leaves values")
        println("portfolio: $leaves values compared, worst relative difference $worst at $worstAt")
    }

    private val sipFixtures = listOf(
        "s1_monthly_stepup_benchmark", "s2_weekly_flat_bps", "s3_quarterly_late_listing", "s4_fortnightly_expensive_share",
        "s5_monthly_no_grids", "s6_long_crisis_window", "s7_weekly_through_suspension", "se1_cannot_afford", "se2_bad_day", "se3_end_before_start",
        "se4_missing_symbol", "se5_bad_amount", "se6_too_short", "se7_flat_bps_crashes",
    )

    @Test fun `sip backtester matches IraAlgo`() {
        for (name in sipFixtures) {
            val f = obj(Fixtures.load(name))
            val req = sipRequest(obj(f["request"]))
            for (mode in listOf("api", "raw")) {
                check(name, f, mode, { rounded -> SipBacktest.run(req, prices, rounded) }, sipTol)
            }
        }
        assertTrue(leaves > 20_000, "compared only $leaves values")
        println("sip: $leaves values compared, worst relative difference $worst at $worstAt")
    }

    @Test fun `portfolio analyzer matches IraAlgo`() {
        for (name in listOf("a1_default", "a2_long_no_bench_rf", "a3_no_cost_basis", "a4_only_unpriceable", "a5_nothing_usable")) {
            val f = obj(Fixtures.load(name))
            val spec = obj(obj(f["request"]))
            @Suppress("UNCHECKED_CAST")
            val rows = (spec["rows"] as List<Map<String, Any?>>)
            val kw = obj(spec["kwargs"])
            val bench = if (kw.containsKey("benchmark")) kw["benchmark"] as String? else "NIFTY"
            for (mode in listOf("api", "raw")) {
                check(name, f, mode, { rounded ->
                    PortfolioAnalyzer.analyze(
                        rows, prices, LocalDate.parse(f["today"] as String),
                        lookbackDays = (d(kw["lookback_days"]) ?: 365.0).toInt(),
                        benchmark = bench,
                        benchmarkExchange = s(kw["benchmark_exchange"]) ?: "NSE_INDEX",
                        riskFreeRate = d(kw["risk_free_rate"]) ?: 0.0,
                        names = Fixtures.names, source = "db", displayRounding = rounded,
                    )
                }, exact)
            }
        }
        assertTrue(leaves > 10_000, "compared only $leaves values")
        println("analyzer: $leaves values compared, worst relative difference $worst at $worstAt")
    }
}
