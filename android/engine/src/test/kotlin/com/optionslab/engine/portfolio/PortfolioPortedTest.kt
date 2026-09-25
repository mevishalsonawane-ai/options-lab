package com.optionslab.engine.portfolio

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** IraAlgo's own `test/test_portfolio_engine.py` and `test_portfolio_service.py`, ported. */
class PortfolioPortedTest {
    companion object {
        fun bdays(start: String, n: Int): List<LocalDate> =
            generateSequence(LocalDate.parse(start)) { it.plusDays(1) }
                .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }.take(n).toList()

        fun matrix(data: Map<String, List<Double>>, start: String = "2024-01-01"): PriceMatrix {
            val n = data.values.first().size
            val dates = bdays(start, n)
            val symbols = data.keys.toList()
            val closes = Array(symbols.size) { data.getValue(symbols[it]).toDoubleArray() }
            return PriceMatrix(dates, symbols, closes, "db", PriceMatrix.splitArtifacts(dates, symbols, closes))
        }

        fun gauss(seed: Long, n: Int, mean: Double, sd: Double): DoubleArray {
            val r = java.util.Random(seed)
            return DoubleArray(n) { mean + sd * r.nextGaussian() }
        }
    }

    private fun approx(a: Double, b: Double, tol: Double = 1e-9) = assertTrue(abs(a - b) <= tol * maxOf(1.0, abs(b)), "$a vs $b")
    private fun <T> status400(block: () -> T) = assertFailsWith<PortfolioException> { block() }.also { assertEquals(400, it.status) }

    // ── weights and policy ──────────────────────────────────────────────────

    @Test fun `percentages are normalised and ordered by symbol`() {
        val w = PortfolioEngine.normaliseWeights(mapOf("B" to 60.0, "A" to 40.0), listOf("A", "B"))
        approx(w.sum(), 1.0); approx(w[0], 0.4)
    }

    @Test fun `missing, extra, negative and non-finite weights raise`() {
        assertTrue("no weight given" in status400 { PortfolioEngine.normaliseWeights(mapOf("A" to 1.0), listOf("A", "B")) }.message!!)
        assertTrue("unheld" in status400 { PortfolioEngine.normaliseWeights(mapOf("A" to 1.0, "Z" to 1.0), listOf("A")) }.message!!)
        assertTrue("long-only" in status400 { PortfolioEngine.normaliseWeights(mapOf("A" to -1.0, "B" to 2.0), listOf("A", "B")) }.message!!)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue("finite" in status400 { PortfolioEngine.normaliseWeights(mapOf("A" to bad), listOf("A")) }.message!!)
        }
    }

    @Test fun `policy rejects unknown rules and out of range bands`() {
        assertTrue("unknown rebalance rule" in status400 { RebalancePolicy("fortnightly") }.message!!)
        assertTrue("fraction" in status400 { RebalancePolicy(driftBand = 5.0) }.message!!)
        assertTrue(RebalancePolicy().isBuyAndHold)
        assertFalse(RebalancePolicy("monthly").isBuyAndHold)
        assertFalse(RebalancePolicy(driftBand = 0.05).isBuyAndHold)
    }

    @Test fun `calendar lands on real sessions and never skips the purchase`() {
        val index = generateSequence(LocalDate.of(2024, 1, 1)) { it.plusDays(1) }.takeWhile { it <= LocalDate.of(2024, 3, 29) }
            .filter { it.dayOfWeek.value <= 5 }.toList()
        val dates = RebalancePolicy.calendarDates(index, "monthly")
        assertTrue(dates.all { it in index })
        assertFalse(index[0] in dates)
        assertEquals(setOf(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 29)), dates)
        assertTrue(RebalancePolicy.calendarDates(bdays("2024-01-01", 100), "never").isEmpty())
    }

    @Test fun `drift is absolute points not relative`() {
        val target = doubleArrayOf(0.5, 0.5)
        assertFalse(RebalancePolicy.drifted(doubleArrayOf(0.52, 0.48), target, 0.05))
        assertTrue(RebalancePolicy.drifted(doubleArrayOf(0.56, 0.44), target, 0.05))
        assertFalse(RebalancePolicy.drifted(doubleArrayOf(0.9, 0.1), target, 0.0))
    }

    // ── engine ──────────────────────────────────────────────────────────────

    private val doubles = mapOf("A" to listOf(100.0, 200.0), "B" to listOf(100.0, 100.0))
    private val monthEnd = mapOf("A" to List(21) { 100.0 } + 200.0, "B" to List(22) { 100.0 })

    @Test fun `buy and hold compounds the weighted assets and lets weights drift`() {
        val r = PortfolioEngine.run(matrix(doubles), mapOf("A" to 50.0, "B" to 50.0), initialCapital = 1000.0)
        approx(r.equity.last(), 1500.0); approx(r.totalReturn, 0.5)
        approx(r.weights.last()[0], 2.0 / 3); assertTrue(r.rebalanceDates.isEmpty())
    }

    @Test fun `buy and hold never trades so costs cannot bite`() {
        val r = PortfolioEngine.run(matrix(doubles), mapOf("A" to 50.0, "B" to 50.0), costs = FlatCosts(100.0, 0.01))
        approx(r.costDrag, 0.0); assertTrue(r.turnover.isEmpty())
    }

    @Test fun `rebalancing resets weights, turnover is one way and costs scale with it`() {
        val free = PortfolioEngine.run(matrix(monthEnd), mapOf("A" to 50.0, "B" to 50.0), RebalancePolicy("monthly"))
        assertTrue(free.rebalanceDates.isNotEmpty()); approx(free.weights.last()[0], 0.5)
        assertTrue(abs(free.turnover.last() - 1.0 / 6) < 1e-3 / 6)
        val paid = PortfolioEngine.run(matrix(monthEnd), mapOf("A" to 50.0, "B" to 50.0), RebalancePolicy("monthly"), FlatCosts(100.0, 0.01))
        assertTrue(paid.equity.last() < free.equity.last())
        approx(paid.equity.last(), free.equity.last() * (1 - (1.0 / 6) * 0.02), 1e-6)
        assertTrue(paid.costDrag > 0)
        approx(paid.costDrag, free.totalReturn - paid.totalReturn, 1e-6)
    }

    @Test fun `cost breakdown uses the realised rebalance value`() {
        val schedule = CostSchedule(
            "test", charges = listOf(Charge("brokerage", "Brokerage", "order", flat = 10.0, taxed = true), Charge("fee", "Fee", "turnover", rate = 0.01, taxed = true)),
            taxRate = 0.10, slippage = 0.02,
        )
        val r = PortfolioEngine.run(matrix(monthEnd), mapOf("A" to 50.0, "B" to 50.0), RebalancePolicy("monthly"), schedule, 1_000.0)
        approx(r.costBreakdown.getValue("brokerage"), 20.0); approx(r.costBreakdown.getValue("fee"), 5.0)
        approx(r.costBreakdown.getValue("tax"), 2.5); approx(r.costBreakdown.getValue("slippage"), 10.0)
        approx(r.costBreakdown.getValue("total"), 37.5)
    }

    @Test fun `drift band triggers without a calendar, flat market leaves capital untouched`() {
        val r = PortfolioEngine.run(matrix(doubles), mapOf("A" to 50.0, "B" to 50.0), RebalancePolicy("never", 0.05))
        assertEquals(1, r.rebalanceDates.size); approx(r.weights.last()[0], 0.5)
        val flat = PortfolioEngine.run(matrix(mapOf("A" to List(30) { 100.0 }, "B" to List(30) { 50.0 })), mapOf("A" to 30.0, "B" to 70.0),
            RebalancePolicy("monthly"), FlatCosts(50.0), 1234.0)
        approx(flat.equity.last(), 1234.0); approx(flat.costDrag, 0.0)
    }

    @Test fun `returns drop the first row, capital and prices are validated`() {
        val r = PortfolioEngine.run(matrix(mapOf("A" to listOf(100.0, 110.0, 121.0))), mapOf("A" to 100.0))
        assertEquals(2, r.returns.size); approx(r.returns[0], 0.1)
        for (cap in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue("initial capital must be positive and finite" in status400 {
                PortfolioEngine.run(matrix(mapOf("A" to listOf(100.0, 101.0))), mapOf("A" to 100.0), initialCapital = cap)
            }.message!!)
        }
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue("positive finite" in status400 { PortfolioEngine.run(matrix(mapOf("A" to listOf(100.0, bad))), mapOf("A" to 100.0)) }.message!!)
        }
    }

    @Test fun `split guard flags steps a real session cannot make`() {
        assertTrue(matrix(mapOf("A" to listOf(100.0, 102.0, 99.0, 105.0), "B" to listOf(50.0, 51.0, 49.0, 52.0))).warnings.isEmpty())
        val split = matrix(mapOf("A" to listOf(100.0, 101.0, 50.5, 51.0))).warnings
        assertTrue(split.getValue("A")[0].second < -0.4)
        assertTrue(matrix(mapOf("A" to listOf(100.0, 80.0, 96.0))).warnings.isEmpty())
        assertTrue("A" in matrix(mapOf("A" to listOf(50.0, 51.0, 153.0))).warnings)
    }

    @Test fun `itemised pnl is an attribution that sums to the portfolio return`() {
        val r = PortfolioEngine.run(matrix(mapOf("A" to listOf(100.0, 130.0), "B" to listOf(100.0, 90.0))), mapOf("A" to 50.0, "B" to 50.0), initialCapital = 1000.0)
        approx(r.items.sumOf { it.contributionPct }, r.totalReturn)
        val d = PortfolioEngine.run(matrix(doubles), mapOf("A" to 50.0, "B" to 50.0), initialCapital = 1000.0)
        approx(d.items[0].netPnl, 500.0); approx(d.items[1].netPnl, 0.0)
        approx(d.items[0].symbolReturn, 1.0); approx(d.items[0].contributionPct, 0.5)
        val c = PortfolioEngine.run(matrix(monthEnd), mapOf("A" to 50.0, "B" to 50.0), RebalancePolicy("monthly"), FlatCosts(100.0, 0.01), 5000.0)
        approx(c.items.sumOf { it.contributionPct }, c.totalReturn); assertTrue(c.items.sumOf { it.costs } > 0)
        val loser = PortfolioEngine.run(matrix(mapOf("A" to listOf(100.0, 50.0), "B" to listOf(100.0, 100.0))), mapOf("A" to 50.0, "B" to 50.0), initialCapital = 1000.0)
        approx(loser.items[0].netPnl, -250.0); assertTrue(loser.items[0].contributionPct < 0)
    }

    @Test fun `buy and hold drag is exactly zero, drag equals the gap to an uncharged run`() {
        val r = PortfolioEngine.run(matrix(mapOf("A" to listOf(100.0, 137.0, 92.0, 118.0), "B" to listOf(55.0, 51.0, 63.0, 60.0))),
            mapOf("A" to 35.0, "B" to 65.0), costs = FlatCosts(250.0, 0.02))
        assertEquals(0.0, r.costDrag)
        val px = matrix(mapOf("A" to List(21) { 100.0 } + 180.0, "B" to List(22) { 100.0 }))
        val free = PortfolioEngine.run(px, mapOf("A" to 50.0, "B" to 50.0), RebalancePolicy("monthly"), initialCapital = 10_000.0)
        val paid = PortfolioEngine.run(px, mapOf("A" to 50.0, "B" to 50.0), RebalancePolicy("monthly"), FlatCosts(75.0), 10_000.0)
        assertTrue(abs(paid.costDrag - (free.totalReturn - paid.totalReturn)) <= 1e-12)
    }

    // ── analytics ───────────────────────────────────────────────────────────

    private fun threeReturns(n: Int = 260): Array<DoubleArray> {
        val base = gauss(7, n, 0.0004, 0.01)
        val na = gauss(8, n, 0.0, 0.002); val nb = gauss(9, n, 0.0, 0.002)
        return arrayOf(DoubleArray(n) { base[it] + na[it] }, DoubleArray(n) { base[it] + nb[it] }, gauss(10, n, 0.0004, 0.01))
    }

    @Test fun `correlation is square, unit diagonal, and NaN on thin overlap`() {
        val corr = PortfolioAnalytics.correlationMatrix(threeReturns())
        assertEquals(3, corr.size); corr.indices.forEach { approx(corr[it][it], 1.0) }
        assertTrue(PortfolioAnalytics.correlationMatrix(threeReturns(5)).any { row -> row.any { it.isNaN() } })
        val avg = PortfolioAnalytics.averagePairwiseCorrelation(threeReturns())
        assertTrue(avg > 0.0 && avg < 1.0)
        assertTrue(PortfolioAnalytics.averagePairwiseCorrelation(arrayOf(threeReturns()[0])).isNaN())
    }

    @Test fun `concentration and diversification ratio`() {
        approx(PortfolioAnalytics.concentration(doubleArrayOf(25.0, 25.0, 25.0, 25.0)).effectiveHoldings, 4.0)
        val lop = PortfolioAnalytics.concentration(doubleArrayOf(80.0) + DoubleArray(19) { 20.0 / 19 })
        assertEquals(20, lop.holdings); assertTrue(lop.effectiveHoldings < 2.0)
        status400 { PortfolioAnalytics.concentration(doubleArrayOf(0.0)) }
        val r = threeReturns()
        val twins = PortfolioAnalytics.diversificationRatio(doubleArrayOf(0.5, 0.5), arrayOf(r[0], r[1]))
        val mixed = PortfolioAnalytics.diversificationRatio(doubleArrayOf(0.5, 0.5), arrayOf(r[0], r[2]))
        assertTrue(mixed > twins)
    }

    @Test fun `capture ratios split up and down markets and stay stable`() {
        val bench = doubleArrayOf(0.02, -0.02, 0.02, -0.02, 0.01, -0.01)
        val port = doubleArrayOf(0.02, -0.01, 0.02, -0.01, 0.01, -0.005)
        val (up, down) = PortfolioAnalytics.captureRatios(port, bench)
        assertTrue(abs(up - 1.0) < 0.02); assertTrue(down < 0.6)
        assertTrue(PortfolioAnalytics.captureRatios(doubleArrayOf(0.01, 0.01, 0.01), doubleArrayOf(0.01, 0.02, 0.01)).second.isNaN())
        fun pair(n: Int): Pair<DoubleArray, DoubleArray> {
            val b = gauss(3, n, 0.0005, 0.011); val e = gauss(4, n, 0.0, 0.002)
            return Pair(DoubleArray(n) { b[it] * 0.8 + e[it] }, b)
        }
        val short = PortfolioAnalytics.captureRatios(pair(120).first, pair(120).second).first
        val long = PortfolioAnalytics.captureRatios(pair(2500).first, pair(2500).second).first
        assertTrue(abs(short - long) < 0.25); assertTrue(long > 0.5 && long < 1.1)
        val b = pair(400).second
        val (u, dn) = PortfolioAnalytics.captureRatios(b.copyOf(), b)
        approx(u, 1.0); approx(dn, 1.0)
    }

    @Test fun `summary covers the headline metrics and adds relative ones with a benchmark`() {
        val r = threeReturns()
        val dates = bdays("2023-01-02", r[0].size)
        val out = PortfolioAnalytics.summaryRaw(Ser(dates, r[0]), null, 0.0)
        for (k in listOf("cagr", "volatility", "sharpe", "sortino", "max_drawdown", "cvar")) assertTrue(out.getValue(k).isFinite(), k)
        assertFalse("beta" in out)
        val rel = PortfolioAnalytics.summaryRaw(Ser(dates, r[0]), Ser(dates, r[2]), 0.0)
        for (k in listOf("alpha", "beta", "information_ratio", "up_capture", "excess_cagr")) assertTrue(k in rel, k)
    }

    // ── health ──────────────────────────────────────────────────────────────

    private fun kit(n: Int = 300): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val ra = gauss(11, n, 0.0006, 0.01); val rb = gauss(12, n, 0.0004, 0.012)
        var a = 100.0; var b = 100.0
        val closes = arrayOf(DoubleArray(n) { a *= 1 + ra[it]; a }, DoubleArray(n) { b *= 1 + rb[it]; b })
        val rets = Array(2) { s -> DoubleArray(n - 1) { closes[s][it + 1] / closes[s][it] - 1 } }
        return Pair(closes, rets)
    }

    private fun health(weights: DoubleArray = doubleArrayOf(0.5, 0.5), n: Int = 300, sharpe: Double = 1.0, mdd: Double = -0.15, drag: Double = 0.001): Health {
        val (closes, rets) = kit(n)
        return PortfolioHealth.health(listOf("A", "B"), weights, rets, closes, sharpe, 1.2, mdd, drag, 0.4, Disp(true))
    }

    @Test fun `every pillar shows its working and the grade tracks the score`() {
        val h = health()
        assertEquals(6, h.pillars.size)
        h.pillars.forEach { assertTrue(it.formula.isNotEmpty() && it.comment.isNotEmpty()) }
        val good = health(sharpe = 2.5, mdd = -0.05, drag = 0.0)
        val bad = health(sharpe = -0.5, mdd = -0.55, drag = 0.08)
        assertTrue(good.score!! > bad.score!!); assertTrue(good.grade!! < bad.grade!!)
        assertTrue(health(drag = 0.0).score!! > health(drag = 0.05).score!!)
        approx(h.pillars.sumOf { it.effectiveWeight }, 1.0, 1e-3)
    }

    @Test fun `concentration pillar sees through holding count`() {
        val lo = health(doubleArrayOf(0.97, 0.03)).pillars.first { it.key == "concentration" }.score!!
        val ev = health(doubleArrayOf(0.5, 0.5)).pillars.first { it.key == "concentration" }.score!!
        assertTrue(lo < ev)
    }

    @Test fun `an unmeasurable pillar is dropped, not scored zero`() {
        val h = health(n = 50)
        assertTrue("trend" in h.unmeasured)
        val trend = h.pillars.first { it.key == "trend" }
        assertNull(trend.score); assertEquals(0.0, trend.effectiveWeight)
        approx(h.pillars.sumOf { it.effectiveWeight }, 1.0, 1e-3)
    }

    @Test fun `grade boundaries`() {
        for ((score, grade) in listOf(80.0 to "A", 79.9 to "B", 65.0 to "B", 64.9 to "C", 50.0 to "C", 49.9 to "D", 35.0 to "D", 34.9 to "F")) {
            assertEquals(grade, PortfolioHealth.gradeFor(score))
        }
    }

    // ── costs ───────────────────────────────────────────────────────────────

    @Test fun `india preset reproduces the published calculator`() {
        val b = CostSchedule.scheduleFor("india_delivery_nse").breakdown(50_00_000.0, 50_00_000.0, 0)
        approx(b.getValue("brokerage"), 0.0); approx(b.getValue("stt"), 10_000.0)
        assertTrue(abs(b.getValue("exchange_txn") - 307.0) <= 0.5); assertTrue(abs(b.getValue("sebi") - 10.0) <= 0.01)
        assertTrue(abs(b.getValue("tax") - 57.06) <= 0.1); approx(b.getValue("stamp_duty"), 750.0)
        assertTrue(abs(b.getValue("total") - 11_124.06) <= 0.5)
        approx(b.getValue("tax"), (b.getValue("brokerage") + b.getValue("exchange_txn") + b.getValue("sebi")) * 0.18)
        assertTrue(b.getValue("stt") / b.getValue("total") > 0.85)
        assertTrue(abs(b.getValue("total") / 1_00_00_000.0 - 0.001112) <= 1e-5)
    }

    @Test fun `stamp duty on the buy leg only, BSE dearer than NSE`() {
        val c = CostSchedule.indiaDelivery()
        assertTrue(c.breakdown(1_00_000.0, 0.0, 0).getValue("stamp_duty") > 0)
        assertEquals(0.0, c.breakdown(0.0, 1_00_000.0, 0).getValue("stamp_duty"))
        assertTrue(c.charge(1_00_000.0, 0.0) > c.charge(0.0, 1_00_000.0))
        assertTrue(CostSchedule.indiaDelivery("BSE").charge(50_00_000.0, 50_00_000.0) > c.charge(50_00_000.0, 50_00_000.0))
        assertEquals(0.0, c.charge(0.0, 0.0))
    }

    @Test fun `every rate is overridable, brokerage flat or capped, other markets need no code`() {
        approx(CostSchedule.scheduleFor("india_delivery_nse", mapOf("stt" to mapOf("rate" to 0.00125))).breakdown(50_00_000.0, 50_00_000.0, 0).getValue("stt"), 12_500.0)
        val flat = CostSchedule.scheduleFor("india_delivery_nse", mapOf("brokerage" to mapOf("flat" to 20.0)))
        approx(flat.breakdown(1_00_000.0, 1_00_000.0, 4).getValue("brokerage"), 80.0)
        approx(flat.breakdown(10_00_000.0, 10_00_000.0, 4).getValue("brokerage"), 80.0)
        val capped = CostSchedule.scheduleFor("india_delivery_nse", mapOf("brokerage" to mapOf("flat" to 0.0, "rate" to 0.0003, "cap" to 20.0)))
        approx(capped.breakdown(5_00_000.0, 5_00_000.0, 2).getValue("brokerage"), 40.0)
        val us = CostSchedule.scheduleFor("us_equity").breakdown(50_000.0, 50_000.0, 2)
        approx(us.getValue("sec_fee"), 50_000 * 0.0000278); approx(us.getValue("tax"), 0.0)
        approx(CostSchedule.scheduleFor("us_equity").breakdown(50_000.0, 0.0, 1).getValue("sec_fee"), 0.0)
        assertTrue(CostSchedule.scheduleFor("india_delivery_nse", mapOf("nonexistent" to mapOf("rate" to 1.0))).breakdown(1000.0, 1000.0, 0).getValue("total") > 0)
        assertTrue("unknown cost schedule" in status400 { CostSchedule.scheduleFor("mars_equity") }.message!!)
    }

    @Test fun `cost values are validated at construction`() {
        for ((rate, flat, cap) in listOf(Triple(-0.01, 0.0, 0.0), Triple(0.0, Double.NaN, 0.0), Triple(0.0, 0.0, Double.POSITIVE_INFINITY))) {
            assertTrue("finite and non-negative" in status400 { Charge("bad", "Bad", "turnover", rate, flat, cap) }.message!!)
        }
        assertTrue("basis" in status400 { Charge("bad", "Bad", "portfolio", rate = 0.01) }.message!!)
        assertTrue("finite and non-negative" in status400 { CostSchedule("bad", taxRate = -0.1) }.message!!)
        assertTrue("finite and non-negative" in status400 { CostSchedule("bad", slippage = Double.NaN) }.message!!)
        assertTrue("finite and non-negative" in status400 { FlatCosts(-1.0) }.message!!)
        assertTrue("finite and non-negative" in status400 { FlatCosts(slippage = Double.NaN) }.message!!)
    }

    // ── robustness ──────────────────────────────────────────────────────────

    @Test fun `walk forward uses the requested initial capital`() {
        val n = 300
        val px = matrix(mapOf("A" to List(n) { 100.0 + 120.0 * it / (n - 1) }, "B" to List(n) { 100.0 }), "2023-01-02")
        val schedule = CostSchedule("flat orders", charges = listOf(Charge("brokerage", "Brokerage", "order", flat = 20.0)))
        val wf = Robustness.walkForward(px, mapOf("A" to 50.0, "B" to 50.0), RebalancePolicy("monthly"), schedule, 1_000.0, 1.0, 0.5, Disp(false))
        val direct = PortfolioEngine.run(px.slice(0, 252), mapOf("A" to 50.0, "B" to 50.0), RebalancePolicy("monthly"), schedule, 1_000.0)
        approx(wf.windows[0].totalReturn!!, direct.totalReturn)
    }

    private fun sweepPrices(): PriceMatrix {
        val ra = gauss(5, 800, 0.0006, 0.012); val rb = gauss(6, 800, 0.0003, 0.008)
        var a = 100.0; var b = 100.0
        return matrix(mapOf("A" to List(800) { a *= 1 + ra[it]; a }, "B" to List(800) { b *= 1 + rb[it]; b }), "2020-01-01")
    }

    @Test fun `the sweep covers every rule plus drift, on one window, ranked on sharpe`() {
        val out = Robustness.rebalancingSweep(sweepPrices(), mapOf("A" to 50.0, "B" to 50.0), FlatCosts(), 100_000.0, 0.0, Disp(false))
        assertEquals(listOf("Never", "Yearly", "Quarterly", "Monthly"), out.variants.take(4).map { it.label })
        assertTrue(out.variants.any { it.label.startsWith("Drift") })
        assertEquals(1, out.curves.values.map { it.size }.toSet().size)
        val best = out.variants.first { it.label == out.bestBySharpe }
        assertEquals(out.variants.maxOf { it.sharpe!! }, best.sharpe)
    }

    @Test fun `only never cannot incur cost, and more trading costs more`() {
        val out = Robustness.rebalancingSweep(sweepPrices(), mapOf("A" to 50.0, "B" to 50.0), CostSchedule.scheduleFor("india_delivery_nse"), 100_000.0, 0.0, Disp(false))
        val by = out.variants.associateBy { it.label }
        assertEquals(0.0, by.getValue("Never").costDrag); assertEquals(0, by.getValue("Never").rebalances)
        assertTrue(by.getValue("Monthly").costDrag!! > 0); assertTrue(by.getValue("Monthly").rebalances > 30)
        assertTrue(by.getValue("Monthly").turnover!! > by.getValue("Quarterly").turnover!!)
        assertTrue(by.getValue("Monthly").costDrag!! > by.getValue("Quarterly").costDrag!!)
    }

    // ── live holdings ───────────────────────────────────────────────────────

    @Test fun `holdings parsing coerces, recovers and drops`() {
        val h = PortfolioAnalyzer.parseHoldings(listOf(mapOf("symbol" to "itc", "exchange" to "nse", "quantity" to "4", "average_price" to "296.14", "pnl" to "-39.55")))
        assertEquals("ITC", h[0].symbol); assertEquals("NSE", h[0].exchange); assertEquals(4.0, h[0].quantity)
        approx(PortfolioAnalyzer.parseHoldings(listOf(mapOf("symbol" to "X", "quantity" to 4, "average_price" to 100, "pnl" to -40)))[0].lastPrice, 90.0)
        assertEquals(listOf("D"), PortfolioAnalyzer.parseHoldings(listOf(
            mapOf("symbol" to "A", "quantity" to 0, "average_price" to 10), mapOf("symbol" to "B", "quantity" to 5, "average_price" to 0),
            mapOf("symbol" to "C", "quantity" to "junk", "average_price" to 10), mapOf("symbol" to "D", "quantity" to 5, "average_price" to 10, "pnl" to 0),
        )).map { it.symbol })
        assertEquals(listOf("GOOD"), PortfolioAnalyzer.parseHoldings(listOf(
            mapOf("symbol" to "", "quantity" to 1, "last_price" to 100), mapOf("symbol" to "NAN_PRICE", "quantity" to 1, "last_price" to Double.NaN),
            mapOf("symbol" to "INF_QTY", "quantity" to Double.POSITIVE_INFINITY, "last_price" to 100), mapOf("symbol" to "GOOD", "quantity" to 1, "last_price" to 100),
        )).map { it.symbol })
        assertEquals(50.0, PortfolioAnalyzer.parseHoldings(listOf(mapOf("symbol" to "X", "quantity" to 2, "ltp" to 50)))[0].lastPrice)
        assertTrue(PortfolioAnalyzer.parseHoldings(listOf(mapOf("symbol" to "X", "quantity" to 5, "average_price" to 0, "pnl" to 0))).isEmpty())
    }

    @Test fun `holdings are weighted by current value and totals reconcile`() {
        val d = Disp(true)
        val s = PortfolioAnalyzer.summary(PortfolioAnalyzer.parseHoldings(listOf(
            mapOf("symbol" to "A", "quantity" to 10, "average_price" to 100, "pnl" to 1000), mapOf("symbol" to "B", "quantity" to 10, "average_price" to 100, "pnl" to 0),
        )), d)
        val by = s.holdings.associateBy { it.symbol }
        assertTrue(abs(by.getValue("A").weight - 2.0 / 3) < 1e-4); assertTrue(abs(by.getValue("B").weight - 1.0 / 3) < 1e-4)
        val t = PortfolioAnalyzer.summary(PortfolioAnalyzer.parseHoldings(listOf(
            mapOf("symbol" to "A", "quantity" to 7, "average_price" to 282.96, "pnl" to -50.01), mapOf("symbol" to "B", "quantity" to 4, "average_price" to 296.14, "pnl" to -39.55),
        )), d)
        assertTrue(abs(t.current - (t.invested!! + t.pnl)) <= 0.02)
        assertTrue(abs(t.holdings.sumOf { it.weight } - 1.0) <= 1e-4)
        val empty = PortfolioAnalyzer.summary(emptyList(), d)
        assertEquals(0, empty.count); assertEquals(0.0, empty.pnlPct)
    }

    @Test fun `holdings without a cost basis report null, not zero`() {
        val rows = listOf(
            mapOf("symbol" to "NHPC", "exchange" to "NSE", "quantity" to 24, "average_price" to 0, "last_price" to 78.07, "pnl" to 2.05),
            mapOf("symbol" to "SBICARD", "exchange" to "NSE", "quantity" to 1, "average_price" to 0, "last_price" to 657.80, "pnl" to -232.95),
            mapOf("symbol" to "NIFTYBEES", "exchange" to "NSE", "quantity" to 2, "average_price" to 0, "last_price" to 276.49, "pnl" to 1.56),
        )
        val s = PortfolioAnalyzer.summary(PortfolioAnalyzer.parseHoldings(rows), Disp(true))
        assertEquals(3, s.count)
        val total = 24 * 78.07 + 657.80 + 2 * 276.49
        assertTrue(abs(s.holdings.first { it.symbol == "NHPC" }.weight - 24 * 78.07 / total) < 1e-4)
        assertTrue(abs(s.current - total) <= 0.01)
        assertNull(s.invested); assertNull(s.pnlPct); assertFalse(s.hasCostBasis)
        assertTrue(s.holdings.all { it.pnlPct == null })
        assertTrue(abs(s.pnl - (2.05 - 232.95 + 1.56)) <= 0.01)
        val partial = PortfolioAnalyzer.summary(listOf(LiveHolding("KNOWN", "NSE", 2.0, 100.0, 120.0, 40.0), LiveHolding("UNKNOWN", "NSE", 1.0, 0.0, 200.0, 25.0)), Disp(true))
        assertNull(partial.invested); assertEquals(65.0, partial.pnl); assertFalse(partial.hasCostBasis)
        val full = PortfolioAnalyzer.summary(PortfolioAnalyzer.parseHoldings(listOf(mapOf("symbol" to "A", "quantity" to 10, "average_price" to 100, "last_price" to 110, "pnl" to 100))), Disp(true))
        assertTrue(full.hasCostBasis); approx(full.invested!!, 1000.0); approx(full.pnlPct!!, 10.0)
    }

    // ── attribution ─────────────────────────────────────────────────────────

    private fun attribute(weights: Map<String, Double>, good: DoubleArray? = null, bad: DoubleArray? = null, bench: DoubleArray? = null,
                          costs: CostModel = FlatCosts(), policy: RebalancePolicy = RebalancePolicy()): Pair<BacktestRun, Attribution> {
        val n = good?.size ?: 500
        val g = good ?: gauss(21, n, 0.0010, 0.011)
        val b = bad ?: gauss(22, n, -0.0002, 0.011)
        val bm = bench ?: gauss(23, n, 0.0004, 0.009)
        var cg = 100.0; var cb = 100.0
        val px = matrix(mapOf("GOOD" to List(n) { cg *= 1 + g[it]; cg }, "BAD" to List(n) { cb *= 1 + b[it]; cb }), "2022-01-03")
        val run = PortfolioEngine.run(px, weights, policy, costs, 10_000.0)
        val daily = px.returns()
        val bser = Ser(px.dates.subList(1, n), DoubleArray(n - 1) { bm[it + 1] })
        return Pair(run, AttributionCalc.run(run, daily, bser, Disp(false)))
    }

    @Test fun `attribution effects reconcile to the net excess`() {
        val (_, out) = attribute(mapOf("GOOD" to 70.0, "BAD" to 30.0))
        assertTrue(out.available)
        assertTrue(abs(out.selectionEffect!! + out.allocationEffect!! + out.costEffect!! - out.excessReturn!!) < 1e-6)
        val (_, good) = attribute(mapOf("GOOD" to 90.0, "BAD" to 10.0))
        val (_, badW) = attribute(mapOf("GOOD" to 10.0, "BAD" to 90.0))
        assertTrue(good.allocationEffect!! > 0); assertTrue(badW.allocationEffect!! < 0)
        val (_, even) = attribute(mapOf("GOOD" to 50.0, "BAD" to 50.0))
        val by = even.holdings!!.associateBy { it.symbol }
        assertTrue(by.getValue("GOOD").contribution!! > 0); assertTrue(by.getValue("BAD").contribution!! < 0)
    }

    @Test fun `identical holdings leave no allocation effect`() {
        val g = gauss(21, 500, 0.0010, 0.011)
        val (_, out) = attribute(mapOf("GOOD" to 90.0, "BAD" to 10.0), g, g.copyOf())
        assertTrue(abs(out.allocationEffect!!) < 1e-9)
    }

    @Test fun `a dynamic path with costs reconciles to the engine`() {
        val (run, out) = attribute(
            mapOf("GOOD" to 50.0, "BAD" to 50.0), DoubleArray(22) { if (it == 21) 1.0 else 0.0 }, DoubleArray(22), DoubleArray(22),
            CostSchedule("test", charges = listOf(Charge("brokerage", "Brokerage", "order", flat = 10.0))), RebalancePolicy("monthly"),
        )
        approx(out.portfolioReturn!!, run.totalReturn)
        approx(out.selectionEffect!! + out.allocationEffect!! + out.costEffect!!, out.excessReturn!!)
        approx(out.holdings!!.sumOf { it.contribution!! }, out.excessReturn!!)
        assertTrue(out.costEffect!! < 0)
    }

    @Test fun `attribution refuses without a benchmark or an overlap`() {
        val (run, _) = attribute(mapOf("GOOD" to 50.0, "BAD" to 50.0))
        val n = run.dates.size
        val daily = Array(2) { s -> DoubleArray(n - 1) { 0.0 } }.also { _ -> }
        val none = AttributionCalc.run(run, daily, null, Disp(false))
        assertFalse(none.available); assertTrue("benchmark" in none.reason!!)
        val far = Ser(listOf(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2)), doubleArrayOf(0.01, 0.02))
        assertFalse(AttributionCalc.run(run, daily, far, Disp(false)).available)
    }

    // ── crises ──────────────────────────────────────────────────────────────

    @Test fun `the crisis set is ordered, unique, scoped and reaches back to 1995`() {
        val c = Crises.INDIA
        assertTrue(c.minOf { it.start } < "1996"); assertTrue(c.size >= 30)
        assertTrue(c.all { it.start < it.end }); assertEquals(c.size, c.map { it.key }.toSet().size)
        assertEquals(setOf("india", "global"), c.map { it.scope }.toSet())
    }

    @Test fun `windows outside the data are dropped, a long history reaches the old ones`() {
        val short = Ser(bdays("2024-01-01", 200), gauss(2, 200, 0.0004, 0.01))
        val keys = Crises.analyse(short, null, Disp(false)).periods.map { it.key }
        assertFalse("asian_crisis" in keys); assertFalse("kargil" in keys)
        val long = Ser(bdays("1995-01-02", 8000), gauss(3, 8000, 0.0003, 0.01))
        val all = Crises.analyse(long, null, Disp(false)).periods.map { it.key }
        for (old in listOf("bear_1995", "asian_crisis", "kargil", "election_2004")) assertTrue(old in all, old)
    }

    // ── service ─────────────────────────────────────────────────────────────

    @Test fun `a negative charge override is a client error`() {
        val bars = mapOf("A" to bdays("2024-01-01", 2).mapIndexed { i, d -> DailyBar(d, 100.0, 100.0, 100.0, 100.0 + i) })
        val e = assertFailsWith<PortfolioException> {
            PortfolioBacktest.run(PortfolioRequest(listOf(Holding("A", "NSE", 100.0)), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2),
                chargeOverrides = mapOf("stt" to mapOf("rate" to -0.01))), bars)
        }
        assertEquals(400, e.status); assertTrue("non-negative" in e.message!!)
    }

    @Test fun `the service serialises realised costs and names gst`() {
        val dates = bdays("2024-01-01", 22)
        val bars = mapOf(
            "A" to dates.mapIndexed { i, d -> val c = if (i == 21) 200.0 else 100.0; DailyBar(d, c, c, c, c) },
            "B" to dates.map { DailyBar(it, 100.0, 100.0, 100.0, 100.0) },
        )
        val out = PortfolioBacktest.run(PortfolioRequest(
            listOf(Holding("A", "NSE", 50.0), Holding("B", "NSE", 50.0)), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 30),
            rebalance = "monthly", initialCapital = 1_000.0, gstRate = 0.10, slippage = 0.02,
            chargeOverrides = mapOf(
                "brokerage" to mapOf("flat" to 10.0), "stt" to mapOf("rate" to 0.01), "exchange_txn" to mapOf("rate" to 0.0),
                "sebi" to mapOf("rate" to 0.0), "stamp_duty" to mapOf("rate" to 0.0),
            ),
        ), bars)
        val lines = out.costs.lines
        assertFalse("tax" in lines)
        assertEquals(20.0, lines["brokerage"]); assertEquals(5.0, lines["stt"]); assertEquals(2.0, lines["gst"])
        assertEquals(10.0, lines["slippage"]); assertEquals(37.0, lines["total"])
        // The tearsheet test's figure: the charged run's compounded return.
        var growth = 1.0
        for (i in 1 until out.equity.size) growth *= out.equity[i].value / out.equity[i - 1].value
        assertTrue(abs(growth - 1.0 - 0.463) < 1e-6)
        assertEquals("India delivery (NSE)", out.costs.schedule)
    }
}
