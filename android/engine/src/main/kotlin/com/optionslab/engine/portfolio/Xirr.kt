package com.optionslab.engine.portfolio

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

/** XIRR is not defined for the given cash flows (IraAlgo's `XirrError`). */
class XirrException(message: String) : IllegalArgumentException(message)

/**
 * Extended IRR for dated, irregular cash flows. Port of `sip/xirr.py`:
 * actual/365 day count, Brent's method (scipy's `brentq`, ported line for
 * line) on the bracket [-99.99%, 10000%], xtol 1e-10, 200 iterations, and the
 * same refusals: fewer than two non-zero flows, one date, one sign, or no root
 * inside the bracket.
 */
object Xirr {
    const val DAYS_PER_YEAR = 365.0
    const val MIN_RATE = -0.9999
    const val MAX_RATE = 100.0

    fun npv(rate: Double, flows: List<Pair<LocalDate, Double>>, t0: LocalDate): Double {
        var total = 0.0
        for ((when_, amount) in flows) {
            val years = ChronoUnit.DAYS.between(t0, when_) / DAYS_PER_YEAR
            val denom = (1.0 + rate).pow(years)
            if (denom.isInfinite()) throw XirrException("cash flows produced an unsolvable NPV: (34, 'Numerical result out of range')")
            if (denom == 0.0) throw XirrException("cash flows produced an unsolvable NPV: float division by zero")
            total += amount / denom
        }
        return total
    }

    fun xirr(cashFlows: List<Pair<LocalDate, Double>>): Double {
        val flows = cashFlows.filter { it.second != 0.0 }
        if (flows.size < 2) throw XirrException("XIRR needs at least two non-zero cash flows")
        val sorted = flows.sortedBy { it.first }
        val t0 = sorted.first().first
        if (sorted.last().first == t0) throw XirrException("XIRR needs cash flows on at least two different dates")
        val neg = sorted.any { it.second < 0 }
        val pos = sorted.any { it.second > 0 }
        if (!(neg && pos)) {
            throw XirrException("XIRR is undefined for ${if (neg) "all outflows" else "all inflows"}: the series never crosses zero")
        }
        val f = { r: Double -> npv(r, sorted, t0) }
        val fLo = f(MIN_RATE)
        val fHi = f(MAX_RATE)
        if (!(fLo.isFinite() && fHi.isFinite())) throw XirrException("NPV is not finite at the bracket edges")
        if (fLo * fHi > 0) {
            throw XirrException(
                "no rate between -100% and 10000% zeroes the NPV; the cash flows are likely degenerate " +
                    "(a very short period, or a near-total loss)",
            )
        }
        return brentq(f, MIN_RATE, MAX_RATE, 1e-10, 4 * Math.ulp(1.0), 200)
    }

    fun xirrOrNull(cashFlows: List<Pair<LocalDate, Double>>): Double? =
        try { xirr(cashFlows) } catch (e: XirrException) { null }

    fun absoluteReturn(invested: Double, currentValue: Double) = if (invested <= 0) 0.0 else (currentValue - invested) / invested

    /** scipy.optimize.brentq (Brent 1973, as in scipy's `brentq.c`). */
    internal fun brentq(f: (Double) -> Double, xa: Double, xb: Double, xtol: Double, rtol: Double, iter: Int): Double {
        var xpre = xa; var xcur = xb
        var xblk = 0.0; var fblk = 0.0; var spre = 0.0; var scur = 0.0
        var fpre = call(f, xpre)
        var fcur = call(f, xcur)
        if (fpre == 0.0) return xpre
        if (fcur == 0.0) return xcur
        if (signbit(fpre) == signbit(fcur)) throw XirrException("solver failed to converge: f(a) and f(b) must have different signs")
        for (i in 0 until iter) {
            if (fpre != 0.0 && fcur != 0.0 && signbit(fpre) != signbit(fcur)) {
                xblk = xpre; fblk = fpre
                spre = xcur - xpre; scur = spre
            }
            if (abs(fblk) < abs(fcur)) {
                xpre = xcur; xcur = xblk; xblk = xpre
                fpre = fcur; fcur = fblk; fblk = fpre
            }
            val delta = (xtol + rtol * abs(xcur)) / 2
            val sbis = (xblk - xcur) / 2
            if (fcur == 0.0 || abs(sbis) < delta) return xcur
            if (abs(spre) > delta && abs(fcur) < abs(fpre)) {
                val stry = if (xpre == xblk) {
                    -fcur * (xcur - xpre) / (fcur - fpre)
                } else {
                    val dpre = (fpre - fcur) / (xpre - xcur)
                    val dblk = (fblk - fcur) / (xblk - xcur)
                    -fcur * (fblk * dblk - fpre * dpre) / (dblk * dpre * (fblk - fpre))
                }
                if (2 * abs(stry) < minOf(abs(spre), 3 * abs(sbis) - delta)) {
                    spre = scur; scur = stry
                } else {
                    spre = sbis; scur = sbis
                }
            } else {
                spre = sbis; scur = sbis
            }
            xpre = xcur; fpre = fcur
            xcur += if (abs(scur) > delta) scur else if (sbis > 0) delta else -delta
            fcur = call(f, xcur)
        }
        throw XirrException("solver failed to converge: Failed to converge after $iter iterations, value is $xcur.")
    }

    private fun call(f: (Double) -> Double, x: Double): Double {
        val v = f(x)
        if (v.isNaN()) throw XirrException("solver failed to converge: The function value at x=${Py.fixed(x, 6)} is NaN; solver cannot continue.")
        return v
    }

    private fun signbit(x: Double) = (java.lang.Double.doubleToRawLongBits(x) ushr 63) == 1L
}
