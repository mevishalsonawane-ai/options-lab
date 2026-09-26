package com.optionslab.engine.portfolio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** numpy / pandas / CPython numeric conventions at their edges. */
class NumCoverageTest {
    private fun near(a: Double, b: Double, tol: Double = 1e-12) = assertTrue(abs(a - b) <= tol || (a.isNaN() && b.isNaN()), "$a vs $b")

    @Test fun `sums, means, variance and products`() {
        near(Np.sum(DoubleArray(300) { 0.1 }), 30.000000000000004)
        near(Np.sum(DoubleArray(20) { 1.0 }, 5, 12), 7.0)
        assertTrue(Np.mean(DoubleArray(0)).isNaN()); near(Np.mean(listOf(1.0, 2.0, 3.0)), 2.0); near(Np.sum(listOf(1.0, 2.0)), 3.0)
        assertTrue(Np.variance(doubleArrayOf(1.0)).isNaN()); near(Np.variance(doubleArrayOf(1.0, 3.0)), 2.0)
        near(Np.variance(doubleArrayOf(1.0, 3.0), ddof = 0), 1.0); near(Np.std(doubleArrayOf(1.0, 3.0)), kotlin.math.sqrt(2.0))
        near(Np.prod(doubleArrayOf(2.0, 3.0, 4.0)), 24.0); near(Np.prod(DoubleArray(0)), 1.0)
        near(Np.comp(doubleArrayOf(0.1, -0.1)), -0.01, 1e-15)
    }

    @Test fun `higher moments with pandas' guards`() {
        assertTrue(Np.skew(doubleArrayOf(1.0, 2.0)).isNaN())
        assertEquals(0.0, Np.skew(doubleArrayOf(5.0, 5.0, 5.0)), "no dispersion")
        near(Np.skew(doubleArrayOf(1.0, 2.0, 3.0)), 0.0)
        assertTrue(Np.skew(doubleArrayOf(1.0, 2.0, 10.0)) > 0)
        assertTrue(Np.kurt(doubleArrayOf(1.0, 2.0, 3.0)).isNaN())
        assertEquals(0.0, Np.kurt(doubleArrayOf(2.0, 2.0, 2.0, 2.0)))
        near(Np.kurt(doubleArrayOf(1.0, 2.0, 3.0, 4.0)), -1.2)
    }

    @Test fun `quantiles and medians`() {
        assertTrue(Np.quantile(DoubleArray(0), 0.5).isNaN())
        val v = doubleArrayOf(4.0, 1.0, 3.0, 2.0)
        near(Np.quantile(v, 0.0), 1.0); near(Np.quantile(v, 1.0), 4.0); near(Np.quantile(v, 0.25), 1.75); near(Np.quantile(v, 0.9), 3.7)
        near(Np.quantile(v, -0.5), 1.0, 1e-12)
        near(Np.pdQuantile(v, 0.5), 2.5); near(Np.percentile(v, 50.0), 2.5)
        assertTrue(Np.median(DoubleArray(0)).isNaN()); near(Np.median(v), 2.5); near(Np.median(doubleArrayOf(3.0, 1.0, 2.0)), 2.0)
    }

    @Test fun `correlation, covariance and their degenerate cases`() {
        near(Np.corr(doubleArrayOf(1.0, 2.0, Double.NaN, 3.0), doubleArrayOf(2.0, 4.0, 1.0, 6.0)), 1.0)
        near(Np.corr(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(3.0, 2.0, 1.0)), -1.0)
        assertTrue(Np.corr(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 2.0)).isNaN())
        assertTrue(Np.corr(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, Double.NaN), minPeriods = 2).isNaN())
        near(Np.corr(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(2.0, 4.0, 6.0), cov = true), 2.0)
        assertTrue(Np.corr(doubleArrayOf(1.0), doubleArrayOf(1.0), cov = true).isNaN())
        val c = Np.cov2(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(2.0, 4.0, 6.0))
        near(c[0][0], 1.0); near(c[0][1], 2.0); near(c[1][0], 2.0); near(c[1][1], 4.0)
    }

    @Test fun `Python rounding, formatting and repr`() {
        assertTrue(Py.round(Double.NaN, 2).isNaN()); assertEquals(Double.NEGATIVE_INFINITY, Py.round(Double.NEGATIVE_INFINITY, 2))
        assertEquals(2.67, Py.round(2.675, 2)); assertTrue(1.0 / Py.round(-0.001, 2) < 0)
        assertEquals("nan", Py.fixed(Double.NaN, 2)); assertEquals("inf", Py.fixed(Double.POSITIVE_INFINITY, 2)); assertEquals("-inf", Py.fixed(Double.NEGATIVE_INFINITY, 2))
        assertEquals("-0.00", Py.fixed(-0.0, 2)); assertEquals("-0.00", Py.fixed(-0.001, 2)); assertEquals("-1.50", Py.fixed(-1.5, 2)); assertEquals("0.12", Py.fixed(0.125, 2))
        assertEquals("nan", Py.repr(Double.NaN)); assertEquals("inf", Py.repr(Double.POSITIVE_INFINITY)); assertEquals("-inf", Py.repr(Double.NEGATIVE_INFINITY))
        assertEquals("0.0", Py.repr(0.0)); assertEquals("-0.0", Py.repr(-0.0)); assertEquals("0.1", Py.repr(0.1)); assertEquals("-2.5", Py.repr(-2.5))
        assertEquals("1e-05", Py.repr(1e-5)); assertEquals("1.5e+16", Py.repr(1.5e16)); assertEquals("-1e+16", Py.repr(-1e16)); assertEquals("100.0", Py.repr(100.0))
        assertEquals("0.30000000000000004", Py.repr(0.1 + 0.2)); assertEquals("1.7976931348623157e+308", Py.repr(Double.MAX_VALUE))
        assertEquals("None", Py.str(null)); assertEquals("True", Py.str(true)); assertEquals("False", Py.str(false))
        assertEquals("2.5", Py.str(2.5)); assertEquals("2.5", Py.str(2.5f)); assertEquals("7", Py.str(7))
    }

    @Test fun `float floor division and compensated sums`() {
        assertEquals(3.0, Py.floorDiv(7.0, 2.0)); assertEquals(-4.0, Py.floorDiv(-7.0, 2.0)); assertEquals(-4.0, Py.floorDiv(7.0, -2.0))
        assertEquals(3.0, Py.floorDiv(-7.0, -2.0)); assertEquals(-1.0, Py.floorDiv(-0.5, 2.0)); assertTrue(1.0 / Py.floorDiv(0.0, -2.0) < 0, "0 // -2 is -0.0")
        assertEquals(0.0, Py.floorDiv(1.0, 2.0)); assertEquals(2.0, Py.floorDiv(6.0, 3.0))
        assertEquals(0.0, Py.sum(emptyList())); assertEquals(1.0, Py.sum(listOf(1.0)))
        assertEquals(1e100, Py.sum(listOf(1e100, 1.0, -1.0)))
        assertEquals(2.0, Py.sum(listOf(1.0, 1e100, 1.0, -1e100)), "compensated")
        assertEquals(0.30000000000000004, Py.sum(listOf(0.1, 0.2)))
        assertTrue(Py.sum(listOf(Double.POSITIVE_INFINITY, 1.0)).isInfinite())
    }

    @Test fun `normal quantile`() {
        assertTrue(Normal.ppf(Double.NaN).isNaN()); assertTrue(Normal.ppf(-0.1).isNaN()); assertTrue(Normal.ppf(1.1).isNaN())
        assertEquals(Double.NEGATIVE_INFINITY, Normal.ppf(0.0)); assertEquals(Double.POSITIVE_INFINITY, Normal.ppf(1.0))
        near(Normal.ppf(0.5), 0.0, 1e-15); near(Normal.ppf(0.975), 1.959963984540054, 1e-12); near(Normal.ppf(0.025), -1.959963984540054, 1e-12)
        near(Normal.ppf(1e-10), -6.361340902404056, 1e-9); near(Normal.ppf(1e-320), -38.26, 0.1)
    }
}
