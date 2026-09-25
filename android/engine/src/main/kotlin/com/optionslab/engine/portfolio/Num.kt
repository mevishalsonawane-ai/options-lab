package com.optionslab.engine.portfolio

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/** One daily bar, as the app fetches it from Zerodha's historical-data API. */
data class DailyBar(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
)

/**
 * A request the service layer refuses, with the HTTP status IraAlgo's endpoint
 * would have answered: 400 for a bad input, 422 for data that cannot answer it,
 * 500 where IraAlgo itself fails.
 */
class PortfolioException(val status: Int, message: String) : RuntimeException(message)

/** A date-indexed series, the shape every pandas Series here reduces to. */
internal class Ser(val dates: List<LocalDate>, val v: DoubleArray) {
    val size get() = v.size
}

/**
 * The numerical conventions of numpy and pandas that the Python results depend
 * on: numpy's pairwise summation, pandas' two-pass variance and bias-corrected
 * moments, numpy's linear quantile, Python's float floor division and rounding.
 * Matching these, rather than textbook formulas, is what makes the port agree
 * with IraAlgo to the last few bits.
 */
internal object Np {
    /** `numpy.add.reduce` on a contiguous float64 array (pairwise, 8-way unrolled). */
    fun sum(a: DoubleArray, from: Int = 0, to: Int = a.size): Double = pairwise(a, from, to - from)

    private fun pairwise(a: DoubleArray, off: Int, n: Int): Double {
        if (n < 8) {
            var res = 0.0
            for (i in 0 until n) res += a[off + i]
            return res
        }
        if (n <= 128) {
            val r = DoubleArray(8) { a[off + it] }
            var i = 8
            while (i < n - (n % 8)) {
                for (j in 0 until 8) r[j] += a[off + i + j]
                i += 8
            }
            var res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]))
            while (i < n) { res += a[off + i]; i++ }
            return res
        }
        var n2 = n / 2
        n2 -= n2 % 8
        return pairwise(a, off, n2) + pairwise(a, off + n2, n - n2)
    }

    fun sum(a: List<Double>) = sum(a.toDoubleArray())
    fun mean(a: DoubleArray): Double = if (a.isEmpty()) Double.NaN else sum(a) / a.size
    fun mean(a: List<Double>) = mean(a.toDoubleArray())

    /** pandas `Series.var` (two-pass, ddof = 1 by default). */
    fun variance(a: DoubleArray, ddof: Int = 1): Double {
        val n = a.size
        if (n - ddof <= 0) return Double.NaN
        val avg = sum(a) / n
        val sq = DoubleArray(n) { (avg - a[it]) * (avg - a[it]) }
        return sum(sq) / (n - ddof)
    }

    fun std(a: DoubleArray, ddof: Int = 1) = sqrt(variance(a, ddof))

    /** Sequential product, as `numpy.multiply.reduce` computes it. */
    fun prod(a: DoubleArray): Double { var p = 1.0; for (x in a) p *= x; return p }

    /** `(1 + r).prod() - 1`, openstatz' `comp`. */
    fun comp(a: DoubleArray): Double { var p = 1.0; for (x in a) p *= (1.0 + x); return p - 1.0 }

    /** pandas `Series.skew` (adjusted Fisher-Pearson, with its float-error guards). */
    fun skew(a: DoubleArray): Double {
        val count = a.size.toDouble()
        if (a.size < 3) return Double.NaN
        val mean = sum(a) / count
        val adj = DoubleArray(a.size) { a[it] - mean }
        var m2 = sum(DoubleArray(a.size) { adj[it] * adj[it] })
        var m3 = sum(DoubleArray(a.size) { adj[it] * adj[it] * adj[it] })
        if (abs(m2) < 1e-14) m2 = 0.0
        if (abs(m3) < 1e-14) m3 = 0.0
        if (m2 == 0.0) return 0.0
        return (count * Math.pow(count - 1, 0.5) / (count - 2)) * (m3 / Math.pow(m2, 1.5))
    }

    /** pandas `Series.kurtosis` (excess, bias-corrected). */
    fun kurt(a: DoubleArray): Double {
        val count = a.size.toDouble()
        val mean = sum(a) / count
        val adj = DoubleArray(a.size) { a[it] - mean }
        val a2 = DoubleArray(a.size) { adj[it] * adj[it] }
        val m2 = sum(a2)
        val m4 = sum(DoubleArray(a.size) { a2[it] * a2[it] })
        val adjTerm = 3 * (count - 1) * (count - 1) / ((count - 2) * (count - 3))
        var numerator = count * (count + 1) * (count - 1) * m4
        var denominator = (count - 2) * (count - 3) * (m2 * m2)
        if (abs(numerator) < 1e-14) numerator = 0.0
        if (abs(denominator) < 1e-14) denominator = 0.0
        if (a.size < 4) return Double.NaN
        if (denominator == 0.0) return 0.0
        return numerator / denominator - adjTerm
    }

    /** numpy's 'linear' quantile (Hyndman-Fan 7), `q` a fraction. */
    fun quantile(values: DoubleArray, q: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sortedArray()
        val n = s.size
        val virtual = (n - 1) * q
        var prev = floor(virtual)
        var next = prev + 1
        if (virtual >= n - 1) { prev = -1.0; next = -1.0 }
        if (virtual < 0) { prev = 0.0; next = 0.0 }
        val a = s[if (prev < 0) n - 1 else prev.toInt()]
        val b = s[if (next < 0) n - 1 else next.toInt()]
        val t = virtual - prev
        val diff = b - a
        return if (t >= 0.5) b - diff * (1 - t) else a + diff * t
    }

    /** pandas `Series.quantile(q)`: numpy percentile of `q * 100`. */
    fun pdQuantile(values: DoubleArray, q: Double) = quantile(values, (q * 100.0) / 100.0)

    /** `numpy.percentile(a, p)`. */
    fun percentile(values: DoubleArray, p: Double) = quantile(values, p / 100.0)

    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** pandas' pairwise Pearson correlation (Welford, clipped to [-1, 1]); `cov` for covariance. */
    fun corr(x: DoubleArray, y: DoubleArray, minPeriods: Int = 1, cov: Boolean = false): Double {
        var nobs = 0
        var ssqdmx = 0.0; var ssqdmy = 0.0; var covxy = 0.0; var meanx = 0.0; var meany = 0.0
        for (i in x.indices) {
            val vx = x[i]; val vy = y[i]
            if (vx.isNaN() || vy.isNaN()) continue
            nobs++
            val dx = vx - meanx
            val dy = vy - meany
            meanx += 1.0 / nobs * dx
            meany += 1.0 / nobs * dy
            ssqdmx += (vx - meanx) * dx
            ssqdmy += (vy - meany) * dy
            covxy += (vx - meanx) * dy
        }
        if (nobs < minPeriods) return Double.NaN
        val divisor = if (cov) nobs - 1.0 else sqrt(ssqdmx * ssqdmy)
        if (divisor == 0.0) return Double.NaN
        var v = covxy / divisor
        if (!cov) { if (v > 1.0) v = 1.0 else if (v < -1.0) v = -1.0 }
        return v
    }

    /** `numpy.cov(x, y)` as a 2x2, ddof = 1. */
    fun cov2(x: DoubleArray, y: DoubleArray): Array<DoubleArray> {
        val n = x.size
        val mx = sum(x) / n
        val my = sum(y) / n
        val dx = DoubleArray(n) { x[it] - mx }
        val dy = DoubleArray(n) { y[it] - my }
        val f = 1.0 / (n - 1)
        val xy = sum(DoubleArray(n) { dx[it] * dy[it] }) * f
        return arrayOf(
            doubleArrayOf(sum(DoubleArray(n) { dx[it] * dx[it] }) * f, xy),
            doubleArrayOf(xy, sum(DoubleArray(n) { dy[it] * dy[it] }) * f),
        )
    }
}

/** Python's float semantics where they differ from Kotlin's. */
internal object Py {
    /** `round(x, n)`: the exact binary value rounded half-even. */
    fun round(x: Double, n: Int): Double {
        if (!x.isFinite()) return x
        val r = BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toDouble()
        return if (r == 0.0) Math.copySign(0.0, x) else r
    }

    /** `f"{x:.{n}f}"`. */
    fun fixed(x: Double, n: Int): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        val s = BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toPlainString()
        return if ((x < 0 || (x == 0.0 && 1.0 / x < 0)) && !s.startsWith("-")) "-$s" else s
    }

    /** `repr(x)`: the shortest round-tripping digits, in Python's layout. */
    fun repr(x: Double): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        if (x == 0.0) return if (1.0 / x < 0) "-0.0" else "0.0"
        val exact = BigDecimal(x)
        var digits: BigDecimal = exact
        for (p in 1..17) {
            val c = exact.round(MathContext(p, RoundingMode.HALF_EVEN))
            if (c.toDouble() == x) { digits = c; break }
        }
        val unscaled = digits.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
        val exp10 = digits.precision() - digits.scale() - 1 // position of the leading digit
        val sign = if (x < 0) "-" else ""
        return if (exp10 in -4..15) {
            val plain = digits.abs().stripTrailingZeros().toPlainString()
            sign + if (plain.contains('.')) plain else "$plain.0"
        } else {
            val mant = if (unscaled.length > 1) unscaled[0] + "." + unscaled.substring(1) else unscaled
            val e = if (exp10 < 0) "-" + (-exp10).toString().padStart(2, '0') else "+" + exp10.toString().padStart(2, '0')
            "${sign}${mant}e$e"
        }
    }

    /** `str(value)` for the scalar types a broker payload carries. */
    fun str(value: Any?): String = when (value) {
        null -> "None"
        is Boolean -> if (value) "True" else "False"
        is Double -> repr(value)
        is Float -> repr(value.toDouble())
        else -> value.toString()
    }

    /** `a // b` for floats, via fmod exactly as CPython computes it. */
    fun floorDiv(vx: Double, wx: Double): Double {
        var mod = vx % wx
        var div = (vx - mod) / wx
        if (mod != 0.0) {
            if ((wx < 0) != (mod < 0)) { mod += wx; div -= 1.0 }
        }
        return if (div != 0.0) {
            var fd = floor(div)
            if (div - fd > 0.5) fd += 1.0
            fd
        } else Math.copySign(0.0, vx / wx)
    }

    /** Built-in `sum()` over Python floats (Neumaier-compensated since 3.12). */
    fun sum(xs: Iterable<Double>): Double {
        val it = xs.iterator()
        if (!it.hasNext()) return 0.0
        var f = it.next()
        var c = 0.0
        while (it.hasNext()) {
            val x = it.next()
            val t = f + x
            c += if (abs(f) >= abs(x)) (f - t) + x else (x - t) + f
            f = t
        }
        return if (c != 0.0 && c.isFinite()) f + c else f
    }
}

/** Standard normal quantile (`scipy.special.ndtri`), refined to full precision. */
internal object Normal {
    private const val SQRT_2PI = 2.5066282746310002

    private fun pdf(x: Double) = exp(-0.5 * x * x) / SQRT_2PI

    /** Lower tail, accurate to ~1e-15 relative for x <= 0. */
    private fun lowerTail(x: Double): Double {
        val ax = abs(x)
        if (ax < 5.0) {
            // Phi(x) = 1/2 + pdf(x) * (x + x^3/3 + x^5/(3*5) + ...)
            var term = x
            var total = x
            var k = 1
            while (abs(term) > 1e-17 * abs(total)) {
                term *= x * x / (2 * k + 1)
                total += term
                k++
            }
            return 0.5 + pdf(x) * total
        }
        // Continued fraction for the Mills ratio, deep in the tail.
        var f = 0.0
        for (k in 60 downTo 1) f = k / (ax + f)
        val tail = pdf(ax) / (ax + f)
        return if (x < 0) tail else 1.0 - tail
    }

    fun ppf(p: Double): Double {
        if (p.isNaN() || p < 0 || p > 1) return Double.NaN
        if (p == 0.0) return Double.NEGATIVE_INFINITY
        if (p == 1.0) return Double.POSITIVE_INFINITY
        if (p > 0.5) return -ppf(1.0 - p)
        // Initial guess (tail approximation), then Newton on the lower tail.
        var x = if (p < 1e-300) -37.0 else {
            val t = sqrt(-2.0 * ln(p))
            -(t - (2.515517 + 0.802853 * t + 0.010328 * t * t) / (1 + 1.432788 * t + 0.189269 * t * t + 0.001308 * t * t * t))
        }
        repeat(100) {
            val step = (lowerTail(x) - p) / pdf(x)
            x -= step
            if (abs(step) <= 1e-16 * abs(x)) return x
        }
        return x
    }
}
