package com.optionslab.engine.portfolio

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The health score's pillar edges and the SWOT rules that only fire on unusual books. */
class InsightsHealthCoverageTest {
    private val on = Disp(true)

    // ---------------------------------------------------------------- health

    @Test fun `grades at each boundary`() {
        assertEquals("A", PortfolioHealth.gradeFor(80.0)); assertEquals("B", PortfolioHealth.gradeFor(79.9))
        assertEquals("C", PortfolioHealth.gradeFor(50.0)); assertEquals("D", PortfolioHealth.gradeFor(35.0))
        assertEquals("F", PortfolioHealth.gradeFor(0.0)); assertEquals("F", PortfolioHealth.gradeFor(-5.0), "below every band")
        assertEquals("F", PortfolioHealth.gradeFor(Double.NaN))
    }

    @Test fun `unmeasurable pillars are dropped and the rest renormalised`() {
        // One holding: no pairwise correlation; NaN Sharpe/drawdown; short history.
        val h = PortfolioHealth.health(listOf("A"), doubleArrayOf(1.0), arrayOf(doubleArrayOf(0.01, -0.01)), arrayOf(doubleArrayOf(100.0, 101.0)),
            Double.NaN, Double.NaN, Double.NaN, 0.0, 0.0, on)
        assertEquals(listOf("diversification", "efficiency", "drawdown", "trend"), h.unmeasured)
        val eff = h.pillars.first { it.key == "efficiency" }
        assertNull(eff.score); assertEquals(0.0, eff.effectiveWeight); assertNull(eff.inputs["sharpe"]); assertNull(eff.inputs["sortino"])
        assertEquals("returns are compensating well for the risk taken", eff.comment, "a NaN Sharpe falls to the last comment")
        assertEquals("holdings move independently enough to diversify", h.pillars.first { it.key == "diversification" }.comment)
        assertNull(h.pillars.first { it.key == "drawdown" }.inputs["max_drawdown"])
        assertEquals("falls have stayed within normal bounds", h.pillars.first { it.key == "drawdown" }.comment)
        // Concentration scores 0 (one name), cost scores 100; each carries half the measured weight.
        assertEquals(0.0, h.pillars.first { it.key == "concentration" }.score)
        assertEquals(0.6667, h.pillars.first { it.key == "concentration" }.effectiveWeight)
        assertEquals(33.3, h.score); assertEquals("F", h.grade)
    }

    @Test fun `nothing measurable leaves no score and no grade`() {
        val h = PortfolioHealth.health(listOf("A"), doubleArrayOf(Double.POSITIVE_INFINITY), arrayOf(doubleArrayOf(0.01)), arrayOf(doubleArrayOf(1.0)),
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.0, on)
        assertNull(h.score); assertNull(h.grade)
        assertEquals(6, h.unmeasured.size)
        assertTrue(h.pillars.all { it.score == null && it.effectiveWeight == 0.0 })
    }

    @Test fun `comments, correlated books and the trend pillar`() {
        val up = DoubleArray(210) { 100.0 + it }                       // last close above its SMA
        val down = DoubleArray(210) { 400.0 - it }                      // below
        val gappy = DoubleArray(210) { if (it % 2 == 0) Double.NaN else 50.0 }   // 105 real closes: not measured
        val r = doubleArrayOf(0.01, 0.02, -0.01, 0.03)
        val h = PortfolioHealth.health(listOf("UP", "DN", "GAP"), doubleArrayOf(0.5, 0.3, 0.2),
            arrayOf(r, r.map { it * 2 }.toDoubleArray(), r), arrayOf(up, down, gappy), 0.3, 0.4, -0.4, 0.03, 1.5, on)
        val div = h.pillars.first { it.key == "diversification" }
        assertEquals("holdings move together, so the spread is nominal", div.comment); assertEquals(0.0, div.score)
        assertEquals("taking risk without being paid for it", h.pillars.first { it.key == "efficiency" }.comment)
        assertEquals("a fall this deep needs a large gain to recover", h.pillars.first { it.key == "drawdown" }.comment)
        assertEquals(25.0, h.pillars.first { it.key == "drawdown" }.score)
        val trend = h.pillars.first { it.key == "trend" }
        assertEquals(62.5, trend.score, "0.5 of the 0.8 measured weight is above")
        assertEquals(listOf("UP"), trend.inputs["above"]); assertEquals(listOf("DN"), trend.inputs["below"]); assertEquals(0.8, trend.inputs["measured_weight"])
        assertEquals("most of the book is trending up", trend.comment)
        val cost = h.pillars.first { it.key == "cost" }
        assertEquals("rebalancing is eating a meaningful share of the return", cost.comment); assertEquals(40.0, cost.score)
        assertEquals("modest reward for the risk taken", PortfolioHealth.health(listOf("A", "B"), doubleArrayOf(1.0, 1.0), arrayOf(r, r), arrayOf(up, up),
            1.0, 1.0, -0.1, 0.0, 0.0, on).pillars.first { it.key == "efficiency" }.comment)
    }

    @Test fun `trend weight that is all zero reads as nothing above`() {
        val long = DoubleArray(210) { 100.0 + it }
        val h = PortfolioHealth.health(listOf("Z", "B"), doubleArrayOf(0.0, 1.0), arrayOf(doubleArrayOf(0.01), doubleArrayOf(0.02)),
            arrayOf(long, doubleArrayOf(1.0)), 1.5, 1.5, -0.2, 0.0, 0.0, on)
        val trend = h.pillars.first { it.key == "trend" }
        assertEquals(0.0, trend.score, "the only measured holding carries no weight")
        assertEquals("most of the book is below its long-term average", trend.comment)
    }

    // -------------------------------------------------------------- insights

    private fun metrics(sharpe: Double? = 0.7, mdd: Double? = -0.2, up: Double? = null, down: Double? = null) =
        Metrics(0.1, 0.15, sharpe, 1.0, 0.5, mdd, 0.5, 0.02, -0.02, -0.02, -0.03, 1.0, 1.0, 1.0, 0.0, 0.0, upCapture = up, downCapture = down)
    private val health = Health(60.0, "C", listOf(HealthPillar("trend", "Trend health", null, 0.1, "f", emptyMap(), "c", 0.0)), listOf("trend"))
    private fun structure(largest: Double = 0.3, bets: Int = 2, clusters: List<Cluster> = emptyList()) =
        Structure(emptyMap(), "names", clusters, 0.6, bets, largest, "")
    private val noAttr = Attribution(false, "no benchmark")
    private fun variant(label: String, rule: String, sharpe: Double?) =
        SweepVariant(label, rule, 0.0, 0.1, 0.1, 0.1, sharpe, 1.0, -0.1, 1.0, 0.001, 0.2, 3)
    private fun sweep(vararg v: SweepVariant, best: String? = v.firstOrNull()?.label) =
        RebalancingSweep(v.toList(), emptyMap(), best, best, 100, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1))
    private fun item(sym: String, w: Double) = ItemRow(sym, w, w, 1000.0, 10.0, 1.0, 9.0, 1.0, 0.01)

    private fun build(
        m: Metrics = metrics(), s: Structure = structure(), a: Attribution = noAttr, c: CrisisSummary? = null,
        sw: RebalancingSweep = sweep(), items: List<ItemRow> = emptyList(), rule: String = "monthly",
    ) = InsightRules.build(m, health, s, a, c, sw, null, null, items, rule, on)

    private fun tags(i: Insights) = (i.strengths + i.weaknesses + i.opportunities + i.threats).map { it.tag }.toSet()

    @Test fun `capture asymmetry both ways and the dead zone between`() {
        val poor = build(metrics(up = 0.8, down = 0.95))
        val f = poor.weaknesses.single { it.tag == "Poor asymmetry" }
        assertEquals("It takes 95% of the benchmark's falls but only 80% of its gains.", f.detail); assertEquals(0.8, f.severity)
        assertTrue("Poor asymmetry" !in tags(build(metrics(up = 0.8, down = 0.85))), "within 10 points: neither")
        assertTrue("Defensive" in tags(build(metrics(up = 0.9, down = 0.8))))
        assertTrue(tags(build(metrics(up = 0.9, down = 0.0))).none { it == "Defensive" || it == "Poor asymmetry" }, "no down capture measured")
        assertTrue(tags(build(metrics(up = null, down = 0.5))).none { it == "Poor asymmetry" })
        assertTrue(tags(build(metrics(sharpe = null, mdd = null))).isEmpty(), "no figures, no findings")
    }

    @Test fun `concentration, diversification and one dominant name`() {
        val three = listOf(item("A", 0.45), item("B", 0.3), item("C", 0.25))
        val clusters = listOf(Cluster(1, listOf("A", "B", "C", "D", "E"), 0.75, false))
        val conc = build(s = structure(0.75, 1, clusters), items = three)
        val t = conc.threats.first { it.tag == "Concentration" }
        assertTrue(t.detail.startsWith("75% of the book moves as one position (A, B, C, D...). 3 holdings behave like 1 independent bets."), t.detail)
        val single = conc.threats.single { it.tag == "Single-name risk" }
        assertEquals("A", single.evidence["symbol"]); assertEquals(0.75, single.severity)
        // No cluster carries the largest weight: members are unknown.
        assertTrue(build(s = structure(0.75, 1), items = three).threats.first { it.tag == "Concentration" }.detail.contains("position ()"))
        // Two holdings cannot be "concentrated despite the count"; enough bets reads as diversified.
        val two = listOf(item("A", 0.3), item("B", 0.7))
        assertTrue("Concentration" !in tags(build(s = structure(0.9, 1), items = two)))
        assertEquals("B", build(items = two).threats.single { it.tag == "Single-name risk" }.evidence["symbol"], "the heaviest, found later in the list")
        assertTrue("Genuinely diversified" in tags(build(s = structure(0.2, 3), items = three)))
        assertTrue("Single-name risk" !in tags(build(items = listOf(item("A", 1.0)))), "a one-stock book is not a single-name threat")
    }

    @Test fun `attribution findings`() {
        val rows = listOf(AttributionRow("WIN", 0.1, 0.5, 0.4, 0.05), AttributionRow("MID", null, null, null, null), AttributionRow("LOSE", 0.3, -0.2, -0.3, -0.06))
        val a = Attribution(true, portfolioReturn = 0.08, equalWeightReturn = 0.12, benchmarkReturn = 0.1, selectionEffect = 0.02,
            allocationEffect = -0.04, holdings = rows)
        val i = build(a = a)
        val sizing = i.opportunities.single { it.tag == "Sizing is costing you" }
        assertEquals("An equal-weighted version of the same holdings would have returned 12.0% against the actual 8.0%. The picks added 2.0%; the sizing took 4.0% back.",
            sizing.detail)
        assertEquals(-0.04, sizing.evidence["allocation_effect"])
        assertTrue("Underweight winner" in tags(i)); assertTrue("Wealth eroder" in tags(i))
        // No selection figure, a mild allocation, null contributions: nothing.
        assertTrue(tags(build(a = a.copy(selectionEffect = null))).none { it == "Sizing is costing you" })
        assertTrue(tags(build(a = a.copy(allocationEffect = -0.01))).none { it == "Sizing is costing you" })
        assertTrue(tags(build(a = a.copy(allocationEffect = null))).none { it == "Sizing is costing you" })
        val blank = listOf(AttributionRow("X", null, null, null, null))
        assertTrue(tags(build(a = a.copy(holdings = blank, allocationEffect = null))).isEmpty())
        assertTrue(tags(build(a = a.copy(holdings = emptyList()))).isEmpty(), "no rows, no attribution findings at all")
        // A winner that was already a big position is no opportunity.
        assertTrue("Underweight winner" !in tags(build(a = a.copy(holdings = listOf(rows[0].copy(weight = 0.2))))))
        assertTrue("Underweight winner" in tags(build(a = a.copy(holdings = listOf(rows[0].copy(weight = null))))), "an unknown weight counts as small")
    }

    @Test fun `rebalancing suggestions`() {
        val cur = variant("Monthly", "monthly", 0.8)
        val better = variant("Quarterly", "quarterly", 0.9)
        val o = build(sw = sweep(cur, better, best = "Quarterly")).opportunities.single()
        assertEquals("Better rebalancing rule", o.tag); assertEquals("Quarterly rebalancing suits this portfolio better", o.title)
        assertEquals("It reaches a Sharpe of 0.90 against 0.80 today, with 0.10% of cost drag against 0.10%.", o.detail)
        assertTrue(build(sw = sweep(cur, variant("Q", "quarterly", 0.81), best = "Q")).opportunities.isEmpty(), "gain too small")
        assertTrue(build(sw = sweep(cur, better, best = "Monthly")).opportunities.isEmpty(), "already the best")
        assertTrue(build(sw = sweep(cur, better, best = "Nope")).opportunities.isEmpty())
        assertTrue(build(sw = sweep(better, best = "Quarterly")).opportunities.isEmpty(), "the current rule was not swept")
        assertTrue(build(sw = sweep(cur, better, best = null)).opportunities.isEmpty())
        assertTrue(build(sw = sweep(variant("M", "monthly", null), better, best = "Quarterly")).opportunities.isEmpty(), "a NaN gain")
    }

    @Test fun `stress record and cost drag`() {
        fun crisis(n: Int, hit: Double?) = CrisisSummary(n, -0.05, hit, -0.2, 0.1, emptyMap())
        val weak = build(c = crisis(4, 0.25)).threats.single()
        assertEquals("Weak in stress", weak.tag); assertEquals("It beat the benchmark in only 25% of the 4 stress periods covered.", weak.detail)
        assertEquals(0.7, weak.severity)
        assertEquals("Holds up in stress", build(c = crisis(3, 0.6)).strengths.single().tag)
        assertTrue(tags(build(c = crisis(3, 0.5))).isEmpty(), "a middling record")
        assertTrue(tags(build(c = crisis(2, 0.0))).isEmpty(), "too few periods")
        assertTrue(tags(build(c = crisis(5, null))).isEmpty())
        val drag = InsightRules.build(metrics(), health, structure(), noAttr, null, sweep(), 0.02, 0.5, emptyList(), "monthly", on)
        assertEquals("Costs took 2.00% of the total return, on 50% of turnover.", drag.weaknesses.single().detail)
        assertEquals(0.7, drag.weaknesses.single().severity)
        assertTrue(InsightRules.build(metrics(), health, structure(), noAttr, null, sweep(), 0.005, null, emptyList(), "monthly", on).weaknesses.isEmpty())
    }
}
