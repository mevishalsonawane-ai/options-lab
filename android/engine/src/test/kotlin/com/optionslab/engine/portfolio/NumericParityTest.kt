package com.optionslab.engine.portfolio

import com.optionslab.engine.portfolio.Fixtures.arr
import com.optionslab.engine.portfolio.Fixtures.num
import com.optionslab.engine.portfolio.Fixtures.obj
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The numerical building blocks against numpy, pandas, scipy and openstatz directly. */
class NumericParityTest {
    private val units = obj(Fixtures.load("units"))

    private fun near(k: Double, p: Double, what: String, rel: Double = 1e-9, absTol: Double = 1e-12) =
        assertTrue((k.isNaN() && p.isNaN()) || k == p || abs(k - p) <= rel * maxOf(abs(k), abs(p)) + absTol, "$what: kotlin $k vs python $p")

    @Test fun `ndtri matches scipy`() {
        for (row in arr(units["ndtri"])) {
            val (p, x) = arr(row).map { num(it) }
            near(Normal.ppf(p), x, "ndtri($p)", rel = 1e-12)
        }
    }

    @Test fun `numpy default_rng integers are reproduced bit for bit`() {
        val g = Pcg64(12345)
        for (seq in arr(units["pcg64_seed12345"])) {
            val m = obj(seq)
            val high = num(m["high"]).toLong()
            val expected = arr(m["values"]).map { num(it).toLong() }
            assertEquals(expected, g.integers(0, high, expected.size).toList(), "integers(0, $high)")
        }
        val raw = Pcg64(7)
        val expected = arr(units["pcg64_seed7_next64"]).map { java.lang.Long.parseUnsignedLong(it as String) }
        assertEquals(expected, List(expected.size) { raw.nextLong() })
    }

    @Test fun `pandas and numpy reductions match`() {
        for (case in arr(units["numeric"])) {
            val m = obj(case)
            val a = arr(m["values"]).map { num(it) }.toDoubleArray()
            assertEquals(num(m["sum"]), Np.sum(a), "sum of ${a.size}")
            for ((q, v) in obj(m["quantile"])) assertEquals(num(v), Np.pdQuantile(a, q.toDouble()), "quantile $q of ${a.size}")
            for ((q, v) in obj(m["percentile"])) assertEquals(num(v), Np.percentile(a, q.toDouble()), "percentile $q of ${a.size}")
            assertEquals(num(m["median"]), Np.median(a))
            near(Np.std(a), num(m["std"]), "std")
            near(Np.skew(a), num(m["skew"]), "skew")
            near(Np.kurt(a), num(m["kurt"]), "kurt")
        }
    }

    @Test fun `xirr matches the Python solver and refuses what it refuses`() {
        for (case in arr(units["xirr"])) {
            val m = obj(case)
            val flows = arr(m["flows"]).map { f -> arr(f).let { Pair(LocalDate.parse(it[0] as String), num(it[1])) } }
            if (m.containsKey("rate")) near(Xirr.xirr(flows), num(m["rate"]), "xirr $flows", absTol = 1e-9)
            else assertEquals(m["error"], assertFailsWith<XirrException> { Xirr.xirr(flows) }.message)
        }
    }

    @Test fun `sip schedules match`() {
        val sessions = generateSequence(LocalDate.of(2020, 1, 1)) { it.plusDays(1) }
            .takeWhile { it <= LocalDate.of(2022, 12, 31) }.filter { it.dayOfWeek.value <= 5 }.toList()
        for (case in arr(units["schedule"])) {
            val m = obj(case)
            val kw = obj(m["kwargs"])
            val got = SipSchedule.build(
                sessions, LocalDate.of(2020, 1, 3), LocalDate.of(2022, 11, 20), 1000.0,
                kw["frequency"] as String, (kw["day_of_month"] as Double? ?: 1.0).toInt(), kw["step_up_percent"] as Double? ?: 0.0,
            )
            val want = arr(m["installments"]).map { arr(it) }
            assertEquals(want.size, got.size)
            want.zip(got).forEach { (w, g) ->
                assertEquals(w[0], g.requested.toString()); assertEquals(w[1], g.executed.toString()); near(g.amount, num(w[2]), "amount")
            }
        }
    }

    @Test fun `openstatz metrics on awkward series match`() {
        for ((name, rowAny) in obj(units["openstatz"])) {
            val row = obj(rowAny)
            val r = arr(row["returns"]).map { num(it) }.toDoubleArray()
            val dates = arr(row["dates"]).map { LocalDate.parse(it as String) }
            val ser = Ser(dates, r)
            for (rf in listOf(0.0, 0.07)) {
                val key = if (rf == 0.0) "0.0" else "0.07"
                val k = Fixtures.tree(PortfolioAnalytics.metrics(PortfolioAnalytics.summaryRaw(ser, null, rf), Disp(true)))
                val diff = Fixtures.compare(k, obj(row["summary_rf$key"]), "$name/summary_rf$key")
                assertTrue(diff.problems.isEmpty(), diff.problems.joinToString("\n"))
                val rs = arr(row["rolling_sharpe_rf$key"]).map { num(it) }
                val ks = Openstatz.rollingSharpeWithDates(ser, rf, 60).v
                assertEquals(rs.size, ks.size, "$name rolling sharpe size")
                rs.indices.forEach { near(ks[it], rs[it], "$name rolling sharpe rf=$rf [$it]") }
            }
            val rv = arr(row["rolling_vol"]).map { num(it) }
            val kv = Openstatz.rollingVolatilityWithDates(ser, 60).v
            assertEquals(rv.size, kv.size)
            rv.indices.forEach { near(kv[it], rv[it], "$name rolling vol [$it]") }
            val dd = arr(row["drawdown"]).map { num(it) }
            val kd = Openstatz.toDrawdownSeries(r)
            dd.indices.forEach { near(kd[it], dd[it], "$name drawdown [$it]") }
            near(Openstatz.maxDrawdown(PortfolioAnalytics.cumprod(r)), num(row["max_drawdown_prices"]), "$name mdd(prices)")
            near(Openstatz.maxDrawdown(r), num(row["max_drawdown_returns"]), "$name mdd(returns)")
        }
    }

    @Test fun `python float semantics`() {
        assertEquals(9.0, Py.floorDiv(1.0, 0.1))
        assertEquals(2.0, Py.floorDiv(0.3, 0.1))
        assertEquals(0.12, Py.round(0.125, 2))
        assertEquals(2.67, Py.round(2.675, 2), "2.675 is below the half in binary")
        assertEquals("-0.0", Py.fixed(-0.0001, 1))
        assertEquals("0.5", Py.repr(0.5))
        assertEquals("1e-05", Py.repr(0.00001))
        assertEquals("0.0001", Py.repr(0.0001))
        assertEquals("0.6666666666666666", Py.repr(2.0 / 3))
        assertEquals("1e+16", Py.repr(1e16))
        assertEquals("123456789012345.0", Py.repr(123456789012345.0))
    }

    @Test fun `the comparator reports a perturbed value`() {
        val py = mapOf("a" to listOf(1.0, 2.0), "b" to mapOf("c" to "x"))
        assertTrue(Fixtures.compare(mapOf("a" to listOf(1.0, 2.0), "b" to mapOf("c" to "x")), py).problems.isEmpty())
        assertEquals(1, Fixtures.compare(mapOf("a" to listOf(1.0, 2.000001), "b" to mapOf("c" to "x")), py).problems.size)
        assertEquals(1, Fixtures.compare(mapOf("a" to listOf(1.0, 2.0), "b" to mapOf("c" to "y")), py).problems.size)
        assertEquals(1, Fixtures.compare(mapOf("a" to listOf(1.0, 2.0)), py).problems.size, "a missing key is a mismatch")
        assertEquals(1, Fixtures.compare(mapOf("a" to listOf(1.0, 2.0), "b" to mapOf("c" to "x"), "z" to 1.0), py).problems.size)
    }
}
