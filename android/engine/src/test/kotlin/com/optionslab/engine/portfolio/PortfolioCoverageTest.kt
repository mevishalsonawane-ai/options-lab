package com.optionslab.engine.portfolio

import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** openstatz quirks, the price matrix, XIRR, the RNG, grouping and the analyzer's input parsing. */
class PortfolioCoverageTest {
    private val d0 = LocalDate.of(2025, 1, 1)
    private fun near(a: Double, b: Double, tol: Double = 1e-12) = assertTrue(abs(a - b) <= tol || (a.isNaN() && b.isNaN()), "$a vs $b")
    private fun ser(vararg v: Double) = Ser(v.indices.map { d0.plusDays(it.toLong()) }, v)

    // ------------------------------------------------------------ openstatz

    @Test fun `prepare guesses prices, cleans non-finite values and de-annualises rf`() {
        assertContentEquals(doubleArrayOf(0.0, 0.1, -0.5), Openstatz.prepare(doubleArrayOf(100.0, 110.0, 55.0)).let { doubleArrayOf(it[0], Math.round(it[1] * 1e12) / 1e12, it[2]) })
        // An infinite value passes openstatz' "max > 1" test, so the whole series is read as prices (then inf and NaN become 0).
        assertContentEquals(doubleArrayOf(0.0, 0.0, -1.0), Openstatz.prepare(doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, 0.01)))
        assertContentEquals(doubleArrayOf(0.0, 0.0, 0.01), Openstatz.prepare(doubleArrayOf(Double.NaN, Double.NEGATIVE_INFINITY, 0.01)))
        near(Openstatz.prepare(doubleArrayOf(0.01), rf = 0.02)[0], -0.01)
        near(Openstatz.prepare(doubleArrayOf(0.01), rf = 0.21, nperiods = 2)[0], 0.01 - 0.1, 1e-12)
        near(Openstatz.prepare(doubleArrayOf(0.01), rf = 0.02, exempt = true)[0], 0.01)
        assertContentEquals(DoubleArray(0), Openstatz.prepare(DoubleArray(0)))
    }

    @Test fun `ratios and their degenerate guards`() {
        val flat = doubleArrayOf(0.0, 0.0, 0.0)
        assertTrue(Openstatz.sortino(doubleArrayOf(0.01, 0.02)).isNaN(), "no downside")
        assertTrue(Openstatz.sharpe(flat).isNaN())
        assertEquals(0.0, Openstatz.winRate(flat))
        assertTrue(Openstatz.valueAtRisk(flat).isNaN())
        assertTrue(Openstatz.conditionalValueAtRisk(flat).isNaN())
        assertTrue(Openstatz.recoveryFactor(doubleArrayOf(0.01, 0.02)).isNaN(), "no drawdown")
        assertTrue(Openstatz.tailRatio(flat).isNaN())
        assertTrue(Openstatz.tailRatio(DoubleArray(0)).isNaN())
        assertEquals(0.0 to 0.0, Openstatz.greeks(doubleArrayOf(0.01, 0.02, 0.03), doubleArrayOf(0.0, 0.0, 0.0)), "a flat benchmark has no beta")
        assertEquals(0.0, Openstatz.informationRatio(doubleArrayOf(0.01, 0.02), doubleArrayOf(0.01, 0.02)))
        near(Openstatz.sharpe(doubleArrayOf(0.01, 0.02, 0.03)), 2.0 * kotlin.math.sqrt(252.0), 1e-9)
        assertTrue(Openstatz.sortino(doubleArrayOf(0.01, -0.02, 0.03)) > 0)
        near(Openstatz.calmar(doubleArrayOf(0.1, -0.5, 0.2)), Openstatz.cagr(doubleArrayOf(0.1, -0.5, 0.2)) / 0.5, 1e-9)
    }

    @Test fun `drawdowns with the phantom baseline and price-level series`() {
        assertEquals(0.0, Openstatz.maxDrawdown(DoubleArray(0)))
        near(Openstatz.maxDrawdown(doubleArrayOf(100.0, 120.0, 90.0)), 90.0 / 120 - 1)
        near(Openstatz.maxDrawdown(doubleArrayOf(2000.0, 1000.0)), -0.99, 1e-12)   // openstatz' phantom baseline of 100000 for prices above 1000
        near(Openstatz.maxDrawdown(doubleArrayOf(5.0, 2.5)), -0.5, 1e-12)
        near(Openstatz.maxDrawdown(doubleArrayOf(-0.1, 0.05)), -0.1, 1e-12, )
        near(Openstatz.maxDrawdown(doubleArrayOf(0.5, 0.25)), 0.0, 1e-12)   // never reaching 1, these are read as returns: +50%, +25%
        assertContentEquals(doubleArrayOf(0.0, 0.0), Openstatz.toDrawdownSeries(doubleArrayOf(0.1, 0.1)))
        assertContentEquals(doubleArrayOf(-1.0), Openstatz.toDrawdownSeries(doubleArrayOf(Double.NaN)), "an unpriced session reads as a price of 0")
        near(Openstatz.ulcerIndex(doubleArrayOf(0.1, -0.1, 0.0)), kotlin.math.sqrt((0.0 + 0.01 + 0.01) / 2), 1e-12)
        assertEquals(emptyList(), Openstatz.drawdownDetails(Ser(emptyList(), DoubleArray(0))))
        assertEquals(emptyList(), Openstatz.drawdownDetails(ser(0.0, 0.0)))
        assertEquals(emptyList(), Openstatz.drawdownDetails(ser(-0.1, -0.2, 0.0)), "a drawdown already open on day one has no start")
        val two = Openstatz.drawdownDetails(ser(0.0, -0.1, -0.3, 0.0, -0.2, -0.1))
        assertEquals(2, two.size)
        assertEquals(Openstatz.Episode(d0.plusDays(1), d0.plusDays(2), d0.plusDays(2), 2, -30.0), two[0].let { it.copy(maxDrawdownPct = Math.round(it.maxDrawdownPct * 1e9) / 1e9) })
        assertEquals(d0.plusDays(5), two[1].end, "an episode still open ends on the last session")
        val reopened = Openstatz.drawdownDetails(ser(-0.1, 0.0, -0.2, 0.0))
        assertEquals(listOf(d0, d0.plusDays(2)), reopened.map { it.start }, "an end before the first start inserts a start at day one")
    }

    @Test fun `rolling windows and resampling bins`() {
        val r = ser(0.01, 0.01, 0.01, 0.02, -0.01)
        val sh = Openstatz.rollingSharpeWithDates(r, 0.0, 3)
        assertEquals(listOf(d0.plusDays(2), d0.plusDays(3), d0.plusDays(4)), sh.dates)
        assertEquals(Double.POSITIVE_INFINITY, sh.v[0], "a constant positive window: mean over zero std, kept as pandas keeps inf")
        val vol = Openstatz.rollingVolatilityWithDates(r, 3)
        assertEquals(3, vol.size); assertEquals(0.0, vol.v[0])
        assertEquals(5, Openstatz.rollingVolatilityWithDates(r, 1).size, "a one-session window has zero volatility")
        val sat = LocalDate.of(2025, 1, 4)
        assertEquals(LocalDate.of(2025, 1, 6), Openstatz.binLabel(sat, "W-MON")); assertEquals(LocalDate.of(2025, 1, 6), Openstatz.binLabel(LocalDate.of(2025, 1, 6), "W-MON"))
        assertEquals(LocalDate.of(2025, 1, 31), Openstatz.binLabel(sat, "ME")); assertEquals(LocalDate.of(2025, 3, 31), Openstatz.binLabel(sat, "QE"))
        assertEquals(LocalDate.of(2025, 12, 31), Openstatz.binLabel(sat, "YE"))
        val long = Ser(listOf(LocalDate.of(2025, 1, 15), LocalDate.of(2025, 4, 15), LocalDate.of(2026, 7, 1)), doubleArrayOf(0.1, 0.2, 0.3))
        assertEquals(listOf(LocalDate.of(2025, 3, 31), LocalDate.of(2025, 6, 30), LocalDate.of(2025, 9, 30), LocalDate.of(2025, 12, 31),
            LocalDate.of(2026, 3, 31), LocalDate.of(2026, 6, 30), LocalDate.of(2026, 9, 30)), Openstatz.resample(long, "QE") { Np.comp(it) }.dates)
        assertEquals(listOf(0.1, 0.2, 0.0, 0.0, 0.0, 0.0, 0.3), Openstatz.resample(long, "QE") { Np.comp(it) }.v.map { Math.round(it * 1e9) / 1e9 })
        assertTrue(Openstatz.resample(long, "ME", Double.NaN) { it.last() }.v[1].isNaN(), "an empty month is the override")
        assertEquals(2, Openstatz.resample(long, "YE") { Np.comp(it) }.size)
        assertEquals(0, Openstatz.resample(Ser(emptyList(), DoubleArray(0)), "ME") { 0.0 }.size)
        assertEquals(Pair(2025, 1), Openstatz.isoYearWeek(LocalDate.of(2024, 12, 30)))
        assertTrue(Openstatz.avgWin(doubleArrayOf(-1.0)).isNaN()); near(Openstatz.avgLoss(doubleArrayOf(-1.0, -3.0, 2.0)), -2.0)
    }

    // ------------------------------------------------------------ prices

    private fun bars(vararg closes: Double, from: LocalDate = d0) = closes.mapIndexed { i, c -> DailyBar(from.plusDays(i.toLong()), c, c, c, c) }

    @Test fun `price matrix refusals and the inner join`() {
        val px = mapOf("A" to bars(10.0, 11.0, 12.0, 20.0), "B" to bars(5.0, 5.5, from = d0.plusDays(1)), "Z" to bars(1.0, 0.0), "N" to bars(Double.NaN, 1.0))
        val end = d0.plusDays(10)
        assertEquals("no symbols requested", assertFailsWith<PortfolioException> { PriceMatrix.load(emptyList(), emptyList(), d0, end, px) }.message)
        assertEquals("2 symbols but 1 exchanges; pass one exchange or one per symbol",
            assertFailsWith<PortfolioException> { PriceMatrix.load(listOf("A", "B"), listOf("NSE"), d0, end, px) }.message)
        assertEquals("(blank) is not supported here; expected NSE or BSE", assertFailsWith<PortfolioException> { PriceMatrix.load(listOf("A"), listOf(" "), d0, end, px) }.message)
        assertEquals("NFO is not supported here; expected NSE or BSE", assertFailsWith<PortfolioException> { PriceMatrix.load(listOf("A"), listOf("nfo"), d0, end, px) }.message)
        assertEquals("C: no D history for $d0..$end", assertFailsWith<PortfolioException> { PriceMatrix.load(listOf("C"), listOf("NSE"), d0, end, px) }.message)
        assertEquals(422, assertFailsWith<PortfolioException> { PriceMatrix.load(listOf("Z"), listOf("NSE"), d0, end, px) }.status)
        assertTrue(assertFailsWith<PortfolioException> { PriceMatrix.load(listOf("N"), listOf("BSE"), d0, end, px) }.message!!.startsWith("only 1 session(s)"))
        val m = PriceMatrix.load(listOf("A", "B"), listOf("nse", "BSE"), d0, end, px)
        assertEquals(listOf(d0.plusDays(1), d0.plusDays(2)), m.dates); assertEquals(2, m.sessions)
        assertContentEquals(doubleArrayOf(11.0, 12.0), m.close("A")); assertEquals(d0.plusDays(1), m.start); assertEquals(d0.plusDays(2), m.end)
        assertEquals("api", m.source)
        val jumpy = PriceMatrix.load(listOf("A"), listOf("NSE"), d0, end, px)
        assertEquals(listOf(d0.plusDays(3)), jumpy.warnings.getValue("A").map { it.first })
        assertEquals("NSE_INDEX", PriceMatrix.normaliseExchange(" nse_index ", PriceMatrix.BENCHMARK_EXCHANGES))
    }

    // ------------------------------------------------------------ XIRR

    @Test fun `xirr solves, refuses degenerate flows and the solver's own edges`() {
        val flows = listOf(d0 to -1000.0, d0.plusDays(365) to 1100.0)
        near(Xirr.xirr(flows), 0.1, 1e-9)
        assertEquals("XIRR needs at least two non-zero cash flows", assertFailsWith<XirrException> { Xirr.xirr(listOf(d0 to -1.0, d0 to 0.0)) }.message)
        assertEquals("XIRR needs cash flows on at least two different dates", assertFailsWith<XirrException> { Xirr.xirr(listOf(d0 to -1.0, d0 to 2.0)) }.message)
        assertTrue(assertFailsWith<XirrException> { Xirr.xirr(listOf(d0 to -1.0, d0.plusDays(1) to -2.0)) }.message!!.contains("all outflows"))
        assertTrue(assertFailsWith<XirrException> { Xirr.xirr(listOf(d0 to 1.0, d0.plusDays(1) to 2.0)) }.message!!.contains("all inflows"))
        assertTrue(assertFailsWith<XirrException> { Xirr.xirr(listOf(d0 to -1.0, d0.plusDays(1) to 1e-9)) }.message!!.startsWith("no rate between"))
        assertTrue(assertFailsWith<XirrException> { Xirr.xirr(listOf(d0 to -1.0, d0.plusDays(365L * 200) to 2.0)) }.message!!.endsWith("float division by zero"))
        assertTrue(assertFailsWith<XirrException> { Xirr.npv(100.0, listOf(d0 to -1.0, d0.plusDays(365L * 200) to 2.0), d0) }.message!!.contains("out of range"))
        assertNull(Xirr.xirrOrNull(emptyList())); assertNotNull(Xirr.xirrOrNull(flows))
        assertTrue(assertFailsWith<XirrException> { Xirr.npv(-1.0, flows, d0) }.message!!.endsWith("float division by zero"))
        assertEquals(0.0, Xirr.absoluteReturn(0.0, 5.0)); near(Xirr.absoluteReturn(100.0, 125.0), 0.25)
        assertEquals(-1.0, Xirr.brentq({ x -> x + 1 }, -1.0, 1.0, 1e-10, 1e-15, 10))
        assertEquals(1.0, Xirr.brentq({ x -> x - 1 }, -1.0, 1.0, 1e-10, 1e-15, 10))
        assertTrue(assertFailsWith<XirrException> { Xirr.brentq({ 1.0 }, -1.0, 1.0, 1e-10, 1e-15, 10) }.message!!.contains("different signs"))
        assertTrue(assertFailsWith<XirrException> { Xirr.brentq({ Double.NaN }, -1.0, 1.0, 1e-10, 1e-15, 10) }.message!!.contains("is NaN"))
        assertTrue(assertFailsWith<XirrException> { Xirr.brentq({ x -> x * x * x - 0.3 }, -1.0, 1.0, 1e-15, 1e-15, 1) }.message!!.contains("Failed to converge after 1 iterations"))
        near(Xirr.brentq({ x -> x * x * x - 0.3 }, -1.0, 1.0, 1e-14, 4e-16, 100), Math.cbrt(0.3), 1e-12)
    }

    // ------------------------------------------------------------ RNG

    @Test fun `pcg64 bounded integers across both Lemire paths`() {
        val g = Pcg64(42)
        assertTrue(g.integers(5, 6, 3).all { it == 5L })
        assertTrue(g.integers(0, 1L shl 32, 50).all { it in 0L..0xFFFFFFFFL }, "a full 32-bit range")
        assertTrue(g.integers(0, 3L shl 30, 2000).all { it in 0L until (3L shl 30) }, "32-bit rejection")
        val wide = g.integers(0, (3L shl 62) + 1, 2000)
        assertTrue(wide.all { java.lang.Long.compareUnsigned(it, 3L shl 62) <= 0 }, "64-bit rejection")
        assertTrue(g.integers(-10, 10, 100).all { it in -10L..9L })
        assertFailsWith<IllegalArgumentException> { Pcg64.seedSequenceState(-1, 4) }
        assertEquals(4, Pcg64.seedSequenceState(1L shl 40, 4).size)
        assertFalse(Pcg64.seedSequenceState(1L shl 40, 4).contentEquals(Pcg64.seedSequenceState(1, 4)))
    }

    // ------------------------------------------------------------ grouping

    @Test fun `grouping classes and clusters`() {
        assertEquals("fund", Grouping.classifyInstrument("NIFTYBEES", null)); assertEquals("fund", Grouping.classifyInstrument("X", "Gold ETF"))
        assertEquals("stock", Grouping.classifyInstrument("TCS", "Tata Consultancy"))
        assertEquals(listOf(listOf("A")), Grouping.correlationClusters(listOf("A"), arrayOf(doubleArrayOf(1.0))))
        val a = doubleArrayOf(0.01, 0.02, -0.01, 0.03); val b = DoubleArray(4) { a[it] * 2 }; val c = doubleArrayOf(0.01, -0.02, 0.01, -0.03)
        assertEquals(listOf(listOf("A", "B"), listOf("C")), Grouping.correlationClusters(listOf("A", "B", "C"), arrayOf(a, b, c)))
        assertEquals(3, Grouping.correlationClusters(listOf("A", "B", "C"), arrayOf(a, b, c), threshold = 1.1).size)
        assertEquals(listOf(listOf("A", "B"), listOf("Z")), Grouping.correlationClusters(listOf("A", "B", "Z"), arrayOf(a, b, DoubleArray(4)), threshold = -1.0),
            "a flat series has no correlation, so it never joins")
        val s = Grouping.structure(listOf("NIFTYBEES", "TCS"), doubleArrayOf(0.0, 0.0), arrayOf(a, c), emptyMap(), Disp(true))
        assertEquals(mapOf("fund" to 0.0, "stock" to 0.0), s.instrumentClasses, "zero weights do not divide by zero")
        assertEquals(2, s.effectiveBets); assertEquals(0.0, s.largestClusterWeight)
        val s2 = Grouping.structure(listOf("A", "B"), doubleArrayOf(3.0, 1.0), arrayOf(a, b), mapOf("A" to "Alpha Fund"), Disp(false))
        assertEquals(1, s2.effectiveBets); assertEquals(1.0, s2.largestClusterWeight); assertFalse(s2.clusters.single().independent)
        assertEquals(0.75, s2.instrumentClasses["fund"])
    }

    // ------------------------------------------------------------ analytics

    @Test fun `portfolio analytics edges`() {
        assertTrue(PortfolioAnalytics.averagePairwiseCorrelation(arrayOf(doubleArrayOf(1.0))).isNaN())
        assertTrue(PortfolioAnalytics.averagePairwiseCorrelation(arrayOf(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 2.0))).isNaN())
        assertEquals(400, assertFailsWith<PortfolioException> { PortfolioAnalytics.concentration(doubleArrayOf(0.0, 0.0)) }.status)
        val c = PortfolioAnalytics.concentration(doubleArrayOf(1.0, 1.0))
        near(c.hhi, 0.5); near(c.effectiveHoldings, 2.0); assertEquals(2, c.holdings)
        assertTrue(PortfolioAnalytics.diversificationRatio(doubleArrayOf(1.0), arrayOf(doubleArrayOf(0.1, 0.2))).isNaN())
        assertTrue(PortfolioAnalytics.diversificationRatio(doubleArrayOf(1.0, 1.0), arrayOf(doubleArrayOf(0.1, 0.1), doubleArrayOf(0.2, 0.2))).isNaN(), "no volatility")
        val (up, down) = PortfolioAnalytics.captureRatios(doubleArrayOf(0.02, -0.01, 0.0), doubleArrayOf(0.01, -0.02, 0.0))
        near(up, 2.0); near(down, 0.5)
        val (u2, d2) = PortfolioAnalytics.captureRatios(doubleArrayOf(0.02), doubleArrayOf(0.0))
        assertTrue(u2.isNaN() && d2.isNaN())
        val (dates, x, y) = PortfolioAnalytics.join(ser(0.1, Double.NaN, 0.3), Ser(listOf(d0, d0.plusDays(1), d0.plusDays(2)), doubleArrayOf(1.0, 2.0, Double.NaN)))
        assertEquals(listOf(d0), dates); assertContentEquals(doubleArrayOf(0.1), x); assertContentEquals(doubleArrayOf(1.0), y)
        val raw = PortfolioAnalytics.summaryRaw(ser(0.01, -0.02), Ser(listOf(d0.plusDays(5)), doubleArrayOf(0.1)), 0.0)
        assertFalse("alpha" in raw, "no overlap with the benchmark: no relative metrics")
        val m = PortfolioAnalytics.metrics(mapOf("cagr" to Double.NaN, "sharpe" to 1.23456789), Disp(true))
        assertNull(m.cagr); assertEquals(1.234568, m.sharpe); assertNull(m.alpha)
    }

    // ------------------------------------------------------------ analyzer inputs

    @Test fun `broker holdings are parsed leniently but never invented`() {
        val rows = listOf(
            mapOf("symbol" to " tcs ", "quantity" to "10", "average_price" to "3000", "last_price" to "3100.5", "pnl" to 1005, "product" to "CNC"),
            mapOf("symbol" to "", "quantity" to 1),
            mapOf("symbol" to "A", "quantity" to 0, "average_price" to 1, "last_price" to 1),
            mapOf("symbol" to "B", "quantity" to "nan", "average_price" to 1, "last_price" to 1),
            mapOf("symbol" to "C", "quantity" to 2, "average_price" to 100.0, "last_price" to "-", "ltp" to null, "pnl" to "20"),
            mapOf("symbol" to "D", "quantity" to 2, "average_price" to "", "last_price" to 0, "ltp" to "0"),
            mapOf("symbol" to "E", "quantity" to true, "average_price" to "1e3", "last_price" to "12d", "ltp" to 5L, "exchange" to "bse"),
            mapOf("symbol" to "F", "quantity" to 1, "average_price" to "inf"),
            mapOf("symbol" to "G", "quantity" to 1, "average_price" to "-Infinity"),
            mapOf("symbol" to "H", "quantity" to 1, "average_price" to 1, "last_price" to listOf(1), "ltp" to 7),
            mapOf("symbol" to 12345, "quantity" to 1.0, "average_price" to 1, "last_price" to "+inf"),
        )
        val h = PortfolioAnalyzer.parseHoldings(rows)
        assertEquals(listOf("TCS", "C", "E", "H"), h.map { it.symbol })
        assertEquals(LiveHolding("TCS", "NSE", 10.0, 3000.0, 3100.5, 1005.0, "CNC"), h[0])
        near(h[1].lastPrice, 110.0, 1e-12)
        assertEquals("BSE", h[2].exchange); assertEquals(1.0, h[2].quantity); assertEquals(1000.0, h[2].averagePrice)
        assertEquals(1000.0, h[2].lastPrice, "'12d' is not a Python float: 0, then the cost basis")
        assertEquals(1.0, h[3].lastPrice, "an unreadable last price falls back to the cost basis")
        val s = PortfolioAnalyzer.summary(h, Disp(true))
        assertEquals(4, s.count); assertTrue(s.hasCostBasis)
        val noCost = PortfolioAnalyzer.summary(listOf(LiveHolding("X", "NSE", 1.0, 0.0, 10.0, 3.0)), Disp(false))
        assertNull(noCost.invested); assertNull(noCost.pnlPct); assertEquals(3.0, noCost.pnl); assertNull(noCost.holdings[0].pnlPct)
        assertEquals(0, PortfolioAnalyzer.summary(emptyList(), Disp(true)).count)
        assertEquals(422, assertFailsWith<PortfolioException> { PortfolioAnalyzer.analyze(listOf(rows[1]), emptyMap(), d0) }.status)
        val r = PortfolioAnalyzer.analyze(listOf(rows[0], rows[6]), emptyMap(), d0.plusDays(30), lookbackDays = 30)
        assertNull(r.analysis); assertTrue(r.analysisError!!.contains("no D history"), r.analysisError)
        assertEquals(emptyList(), r.skipped)
        val nfo = PortfolioAnalyzer.analyze(listOf(mapOf("symbol" to "X", "exchange" to "NFO", "quantity" to 1, "average_price" to 1, "last_price" to 2)), emptyMap(), d0)
        assertEquals(listOf("X"), nfo.skipped); assertNull(nfo.analysis); assertNull(nfo.analysisError)
        assertEquals(PortfolioAnalyzer.BASIS, nfo.meta.basis); assertEquals(d0.minusDays(365), nfo.meta.start)
    }
}
