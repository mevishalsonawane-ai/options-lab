package com.optionslab.engine.options

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

enum class Side { BUY, SELL }
enum class Segment { OPTION, FUTURE }

/**
 * A Strategy Builder leg (`StrategyLeg` in `strategyMath.ts`).
 *
 * [price] is the entry premium per share (0 if unknown), [iv] the leg's IV in
 * PERCENT (0 = not fetched yet, use the fallback). The live-snapshot fields
 * ([marketPrice], [referenceUnderlying], [forwardPrice], [tickSize]) let the
 * T+0 curve pass exactly through the quoted premium and let the strategy infer
 * its carry; [expiryTs] (epoch seconds) is the authoritative expiry instant,
 * else [expiry] (DDMMMYY) is read as 15:30 IST. A finite [exitPrice] >= 0 closes
 * the leg: its P&L is frozen at (exit - entry) x qty x sign.
 */
data class StrategyLeg(
    val side: Side,
    val lots: Int,
    val lotSize: Int,
    val expiry: String,
    val price: Double,
    val segment: Segment = Segment.OPTION,
    val strike: Double? = null,
    val optionType: OptionType? = null,
    val iv: Double = 0.0,
    val active: Boolean = true,
    val symbol: String = "",
    val id: String = "",
    val exchange: String? = null,
    val expiryTs: Double? = null,
    val tickSize: Double? = null,
    val contractValid: Boolean? = null,
    val marketPrice: Double? = null,
    val referenceUnderlying: Double? = null,
    val forwardPrice: Double? = null,
    val exitPrice: Double? = null,
) {
    val quantity: Double get() = lots.toDouble() * lotSize
    val sign: Double get() = if (side == Side.BUY) 1.0 else -1.0

    /** Undefined is the only open state; an explicit finite non-negative exit closes the leg. */
    val isClosed: Boolean get() = exitPrice != null && exitPrice.isFinite() && exitPrice >= 0

    /** `isLegExecutable`: only an exactly-resolved, whole-lot, open, active contract may go to the broker. */
    val isExecutable: Boolean
        get() = active && !isClosed && contractValid == true && lots > 0 && lotSize > 0 &&
            tickSize != null && tickSize.isFinite() && tickSize > 0
}

/** One point of the payoff chart: P&L at the expiry horizon and at T+n. */
data class PayoffSample(val underlying: Double, val expiry: Double, val tplus0: Double)

/**
 * `PayoffResult`. [maxProfit] may be +Infinity and [maxLoss] -Infinity:
 * unbounded tails are detected from the payoff's asymptotic slope, not read
 * off a finite window. [zeroCrossings] are sample indexes where the expiry
 * P&L is exactly 0 (for shading).
 */
data class PayoffResult(
    val samples: List<PayoffSample>,
    val maxProfit: Double,
    val maxLoss: Double,
    val breakevens: List<Double>,
    val zeroCrossings: List<Int>,
)

data class Moneyness(val label: String, val kind: String, val steps: Int)

data class BsGreeks(val delta: Double, val gamma: Double, val theta: Double, val vega: Double)

/**
 * The Strategy Builder's payoff maths, ported from `frontend/src/lib/strategyMath.ts`.
 *
 * Options are priced with Black-76 at zero rate against their own FORWARD,
 * which is the scenario underlying grown by one strategy-wide carry rate
 * (inferred from the legs' spot/forward snapshots, median across legs) over
 * each leg's own remaining life - so calendars and diagonals, whose legs
 * expire at different times, are priced correctly, and a stale leg cannot
 * give a defined-risk structure a phantom tail. Time is calendar time,
 * 365-day years, to 15:30 IST.
 *
 * Max profit / loss and breakevens are exact at expiry (the payoff is
 * piecewise linear with kinks at strikes) and found by grid + bisection +
 * golden-section refinement before it. Probability of profit is lognormal at
 * zero drift from the ATM IV.
 */
object Payoff {
    private const val DAY_MS = 24.0 * 60 * 60 * 1000
    private const val YEAR_MS = 365 * DAY_MS
    private const val PAYOFF_EPSILON = 1e-8
    private val SQRT2PI = sqrt(2 * Math.PI)

    // ---------------------------------------------------------------- normal (A&S, as strategyMath.ts)

    /** Error function, Abramowitz & Stegun 7.1.26 (max error ~1.5e-7) - the strategy tab's own, kept for parity. */
    fun erf(x: Double): Double {
        val sign = if (x > 0) 1.0 else if (x < 0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1 / (1 + 0.3275911 * ax)
        val y = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return sign * y
    }

    fun normCdf(x: Double): Double = 0.5 * (1 + erf(x / Math.sqrt(2.0)))
    fun normPdf(x: Double): Double = exp(-0.5 * x * x) / SQRT2PI

    fun intrinsic(type: OptionType, spot: Double, strike: Double): Double =
        if (type.isCall) maxOf(spot - strike, 0.0) else maxOf(strike - spot, 0.0)

    /** `bsPrice`: Black-Scholes on spot with rate [r] and dividend yield [q] (decimals); intrinsic at zero vol or time. */
    fun bsPrice(type: OptionType, spot: Double, strike: Double, t: Double, iv: Double, r: Double = 0.0, q: Double = 0.0): Double {
        val tt = maxOf(t, 1e-8)
        if (iv <= 0 || tt <= 1e-8) return intrinsic(type, spot, strike)
        val d1 = (ln(spot / strike) + (r - q + 0.5 * iv * iv) * tt) / (iv * sqrt(tt))
        val d2 = d1 - iv * sqrt(tt)
        return if (type.isCall) spot * exp(-q * tt) * normCdf(d1) - strike * exp(-r * tt) * normCdf(d2)
        else strike * exp(-r * tt) * normCdf(-d2) - spot * exp(-q * tt) * normCdf(-d1)
    }

    /** `bsGreeks`: Black-Scholes delta, gamma, theta per day, vega per 1%. */
    fun bsGreeks(type: OptionType, spot: Double, strike: Double, t: Double, iv: Double, r: Double = 0.0, q: Double = 0.0): BsGreeks {
        val tt = maxOf(t, 1e-8)
        val v = maxOf(iv, 1e-8)
        val sqrtT = sqrt(tt)
        val d1 = (ln(spot / strike) + (r - q + 0.5 * v * v) * tt) / (v * sqrtT)
        val d2 = d1 - v * sqrtT
        val pdf = normPdf(d1)
        val delta = if (type.isCall) exp(-q * tt) * normCdf(d1) else exp(-q * tt) * (normCdf(d1) - 1)
        val gamma = exp(-q * tt) * pdf / (spot * v * sqrtT)
        val vega = spot * exp(-q * tt) * pdf * sqrtT * 0.01
        val common = -(spot * pdf * v * exp(-q * tt)) / (2 * sqrtT)
        val theta = if (type.isCall) common - r * strike * exp(-r * tt) * normCdf(d2) + q * spot * exp(-q * tt) * normCdf(d1)
        else common + r * strike * exp(-r * tt) * normCdf(-d2) - q * spot * exp(-q * tt) * normCdf(-d1)
        return BsGreeks(delta, gamma, theta / 365, vega)
    }

    /**
     * `strikeMoneyness`: ATM / ITMn / OTMn by whole strike steps from ATM
     * (JavaScript's round-half-up). Null without an ATM, type or positive step.
     */
    fun strikeMoneyness(strike: Double?, atmStrike: Double?, strikeStep: Double, type: OptionType?): Moneyness? {
        if (strike == null || atmStrike == null || type == null) return null
        if (!strikeStep.isFinite() || strikeStep <= 0) return null
        val steps = floor((strike - atmStrike) / strikeStep + 0.5).toInt()
        if (steps == 0) return Moneyness("ATM", "ATM", 0)
        val itm = (type.isCall && steps < 0) || (!type.isCall && steps > 0)
        val kind = if (itm) "ITM" else "OTM"
        return Moneyness("$kind${abs(steps)}", kind, steps)
    }

    // ---------------------------------------------------------------- time

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** `parseExpiryDate`: DDMMMYY (upper case, 1-2 digit day) at 15:30 IST = 10:00 UTC; null if malformed. */
    fun parseExpiryDate(expiry: String): Instant? {
        val m = Regex("^(\\d{1,2})([A-Z]{3})(\\d{2})$").find(expiry) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2])
        if (month < 0) return null
        return runCatching {
            LocalDate.of(2000 + m.groupValues[3].toInt(), month + 1, 1).atStartOfDay(ZoneOffset.UTC)
                .plusDays(m.groupValues[1].toLong() - 1).plusHours(10).toInstant() // Date.UTC rolls an overflowing day over
        }.getOrNull()
    }

    /** `daysToExpiry`: fractional calendar days to the 15:30 IST expiry, never negative. */
    fun daysToExpiry(expiry: String, now: Instant): Double {
        val d = parseExpiryDate(expiry) ?: return 0.0
        return maxOf(0.0, (d.toEpochMilli() - now.toEpochMilli()) / DAY_MS)
    }

    fun daysToYears(days: Double): Double = maxOf(0.0, days) / 365

    private fun remainingDays(leg: StrategyLeg, daysElapsed: Double, now: Instant): Double {
        val valuationMs = now.toEpochMilli() + daysElapsed * DAY_MS
        val ts = leg.expiryTs
        if (ts != null && ts.isFinite() && ts > 0) return maxOf(0.0, ts * 1000 - valuationMs) / DAY_MS
        return maxOf(daysToExpiry(leg.expiry, now) - daysElapsed, 0.0)
    }

    private fun remainingYears(leg: StrategyLeg, daysElapsed: Double, now: Instant): Double =
        remainingDays(leg, daysElapsed, now) * DAY_MS / YEAR_MS

    // ---------------------------------------------------------------- carry

    private fun Double?.finite(): Double? = this?.takeIf { it.isFinite() }

    /**
     * `resolveCarryRate`: ln(forward / spot) / years per open leg - a future's
     * own price, an option's parity forward - and the MEDIAN across legs, so one
     * stale or unpriced leg cannot move it. Null when no leg carries a snapshot.
     */
    fun carryRate(legs: List<StrategyLeg>, now: Instant): Double? {
        val rates = ArrayList<Double>()
        for (leg in legs) {
            if (!leg.active || leg.isClosed) continue
            val reference = leg.referenceUnderlying.finite()
            val carried = if (leg.segment == Segment.FUTURE) leg.marketPrice.finite() else leg.forwardPrice.finite()
            if (reference == null || carried == null || reference <= 0 || carried <= 0) continue
            val years = remainingYears(leg, 0.0, now)
            if (years <= 0) continue
            val rate = ln(carried / reference) / years
            if (rate.isFinite()) rates += rate
        }
        if (rates.isEmpty()) return null
        rates.sort()
        val mid = rates.size / 2
        return if (rates.size % 2 == 1) rates[mid] else (rates[mid - 1] + rates[mid]) / 2
    }

    private fun carryFactorAt(rate: Double?, tYears: Double): Double {
        if (rate == null || tYears <= 0) return 1.0
        val f = exp(rate * tYears)
        return if (f.isFinite() && f > 0) f else 1.0
    }

    // ---------------------------------------------------------------- P&L

    /**
     * `legPnlAt`: one leg's P&L at [underlying], [daysElapsed] calendar days
     * after [now], at IV [ivOverride] (percent) or the leg's own. Each leg uses
     * its OWN remaining time. At the unshifted live snapshot the quoted market
     * price is used when the model is within a tick of it, so T+0 passes through
     * the real quote rather than the model's rounding of it.
     */
    fun legPnlAt(leg: StrategyLeg, underlying: Double, daysElapsed: Double, ivOverride: Double? = null, now: Instant, carryRate: Double? = carryRate(listOf(leg), now)): Double {
        if (!leg.active) return 0.0
        val qty = leg.quantity
        if (leg.isClosed) return leg.sign * (leg.exitPrice!! - leg.price) * qty
        if (leg.segment == Segment.FUTURE) {
            if (leg.marketPrice.finite() != null && leg.referenceUnderlying.finite() != null) {
                val fv = underlying * carryFactorAt(carryRate, remainingYears(leg, daysElapsed, now))
                return leg.sign * (fv - leg.price) * qty
            }
            return leg.sign * (underlying - leg.price) * qty
        }
        val strike = leg.strike ?: return 0.0
        val type = leg.optionType ?: return 0.0
        val tLeg = remainingYears(leg, daysElapsed, now)
        val iv = (ivOverride ?: leg.iv) / 100
        val reference = leg.referenceUnderlying.finite()
        val hasForwardReference = reference != null && leg.forwardPrice.finite() != null
        val scenarioForward = underlying * carryFactorAt(carryRate, tLeg)
        // Black-76 needs a positive forward; its F -> 0 limit is undiscounted intrinsic.
        val pricingForward = if (scenarioForward.isFinite()) maxOf(0.0, scenarioForward) else 0.0
        val model = if (tLeg <= 1e-8 || iv <= 0 || pricingForward <= 0) intrinsic(type, pricingForward, strike)
        else OptionMath.price(type, pricingForward, strike, tLeg, 0.0, iv)
        val unshifted = hasForwardReference && abs(underlying - reference!!) <= 1e-8 && daysElapsed == 0.0 &&
            abs((ivOverride ?: leg.iv) - leg.iv) <= 1e-8
        val mp = leg.marketPrice.finite()
        val tick = leg.tickSize.finite()
        val valueNow = if (unshifted && mp != null && tick != null && tick > 0 && abs(model - mp) <= tick) mp else model
        return leg.sign * (valueNow - leg.price) * qty
    }

    /**
     * `totalPnlAt`: the strategy's P&L, each leg at its IV (or [fallbackIv],
     * typically the chain's ATM IV, when it has none) scaled by (1 + ivShiftPct/100).
     */
    fun totalPnlAt(legs: List<StrategyLeg>, underlying: Double, daysElapsed: Double, ivShiftPct: Double = 0.0, fallbackIv: Double = 0.0,
                   now: Instant, carryRate: Double? = carryRate(legs, now)): Double {
        var total = 0.0
        for (leg in legs) {
            val base = if (leg.iv > 0) leg.iv else fallbackIv
            total += legPnlAt(leg, underlying, daysElapsed, base * (1 + ivShiftPct / 100), now, carryRate)
        }
        return total
    }

    /** `netCredit`: premium received (+) or paid (-) on the active option legs. */
    fun netCredit(legs: List<StrategyLeg>): Double = legs.filter { it.active && it.segment == Segment.OPTION }
        .fold(0.0) { acc, l -> acc + (if (l.side == Side.SELL) 1.0 else -1.0) * l.price * l.quantity }

    /** `totalPremium`: gross premium of the active option legs. */
    fun totalPremium(legs: List<StrategyLeg>): Double = legs.filter { it.active && it.segment == Segment.OPTION }
        .fold(0.0) { acc, l -> acc + l.price * l.quantity }

    // ---------------------------------------------------------------- analysis

    /** Asymptotic slopes of the payoff at S -> +inf (right) and S -> 0+ (left), carry-scaled per leg. */
    private fun asymptoticSlopes(legs: List<StrategyLeg>, daysElapsed: Double, now: Instant, rate: Double?): Pair<Double, Double> {
        var right = 0.0
        var left = 0.0
        for (leg in legs) {
            if (!leg.active || leg.isClosed) continue
            val f = carryFactorAt(rate, remainingYears(leg, daysElapsed, now))
            val w = leg.sign * leg.quantity * f
            if (leg.segment == Segment.FUTURE) { right += w; left += w; continue }
            when (leg.optionType) {
                OptionType.CE -> right += w
                OptionType.PE -> left -= w
                null -> {}
            }
        }
        return right to left
    }

    private fun normalize(v: Double): Double = if (abs(v) <= PAYOFF_EPSILON) 0.0 else v

    /** Finite values, ascending, dropping any within [tolerance] of the previous kept one. */
    private fun uniqueSorted(values: List<Double>, tolerance: Double = PAYOFF_EPSILON): List<Double> {
        val out = ArrayList<Double>()
        for (v in values.filter { it.isFinite() }.sorted()) {
            if (out.isEmpty() || abs(v - out.last()) > tolerance) out += v
        }
        return out
    }

    private fun responsiveStrikes(legs: List<StrategyLeg>): List<Double> =
        uniqueSorted(legs.filter { it.active && !it.isClosed && it.segment == Segment.OPTION && it.strike != null }.map { it.strike!! })

    private fun hasResponsiveExposure(legs: List<StrategyLeg>) = legs.any {
        it.active && !it.isClosed && (it.segment == Segment.FUTURE || (it.segment == Segment.OPTION && it.strike != null && it.optionType != null))
    }

    private fun isTerminalHorizon(legs: List<StrategyLeg>, daysAtExpiry: Double, now: Instant) = legs.all {
        !it.active || it.isClosed || it.segment != Segment.OPTION || remainingDays(it, daysAtExpiry, now) <= 1e-6
    }

    // JavaScript's Math.max/min over a spread: NaN-propagating, -inf/+inf when empty.
    private fun jsMax(xs: List<Double>): Double = xs.fold(Double.NEGATIVE_INFINITY) { a, b -> Math.max(a, b) }
    private fun jsMin(xs: List<Double>): Double = xs.fold(Double.POSITIVE_INFINITY) { a, b -> Math.min(a, b) }

    /**
     * `lognormalPriceBand`: spot x exp(-sigma^2 T / 2 -/+ n sigma sqrt T), the
     * zero-rate lognormal quantile band used by PoP and the expected-move overlay.
     */
    fun lognormalPriceBand(spot: Double, atmIv: Double, tYears: Double, sd: Double): Pair<Double, Double>? {
        if (!spot.isFinite() || !atmIv.isFinite() || !tYears.isFinite() || !sd.isFinite() || spot <= 0 || atmIv <= 0 || tYears <= 0 || sd <= 0) return null
        val sigma = atmIv / 100
        val sqrtT = sqrt(tYears)
        val variance = sigma * sigma * tYears
        val sigmaT = sigma * sqrtT
        val drift = -0.5 * variance
        val spread = sd * sigmaT
        val lo = drift - spread
        val hi = drift + spread
        if (!listOf(sigma, sqrtT, variance, sigmaT, drift, spread, lo, hi).all { it.isFinite() }) return null
        val lower = spot * exp(lo)
        val upper = spot * exp(hi)
        return if (lower.isFinite() && upper.isFinite()) lower to upper else null
    }

    /** `payoffPriceRange`: the default chart window - spot +/- 10%, the 2-sigma band, and every strike. */
    fun payoffPriceRange(spot: Double, legs: List<StrategyLeg>, atmIv: Double, tYears: Double): Pair<Double, Double> {
        val strikes = responsiveStrikes(legs)
        val band = lognormalPriceBand(spot, atmIv, tYears, 2.0)
        val scaledUpper = spot * 1.1
        val upperBaseline = if (scaledUpper.isFinite()) scaledUpper else Double.MAX_VALUE
        val lower = listOf(spot * 0.9, band?.first ?: spot) + strikes
        val upper = listOf(upperBaseline, band?.second ?: spot) + strikes
        return Math.max(0.0, jsMin(lower)) to jsMax(upper)
    }

    private data class Analysis(val breakevens: List<Double>, val maxProfit: Double, val maxLoss: Double)

    private fun analyzeTerminal(legs: List<StrategyLeg>, daysAtExpiry: Double, ivShift: Double, fallbackIv: Double, now: Instant, rate: Double?): Analysis {
        val candidates = uniqueSorted(listOf(0.0) + responsiveStrikes(legs))
        val valueAt = { u: Double -> normalize(totalPnlAt(legs, u, daysAtExpiry, ivShift, fallbackIv, now, rate)) }
        if (!hasResponsiveExposure(legs)) {
            val c = valueAt(0.0)
            return Analysis(emptyList(), c, c)
        }
        val roots = ArrayList<Double>()
        val vals = candidates.map(valueAt).toDoubleArray()
        // A span flat on zero is one plateau, not a breakeven at each vertex.
        fun plateauEdge(i: Int) = i == 0 || i == vals.size - 1 || vals[i - 1] != 0.0 || vals[i + 1] != 0.0
        for (i in 0 until candidates.size - 1) {
            val l = candidates[i]; val r = candidates[i + 1]
            val lv = vals[i]; val rv = vals[i + 1]
            if (lv == 0.0 && plateauEdge(i) && i > 0) roots += l
            if (lv * rv < 0) roots += l + (0 - lv) * (r - l) / (rv - lv)
        }
        val last = candidates.lastOrNull() ?: 0.0
        val lastIndex = vals.size - 1
        val lastValue = vals[lastIndex]
        if (lastValue == 0.0 && plateauEdge(lastIndex)) roots += last
        val (right, _) = asymptoticSlopes(legs, daysAtExpiry, now, rate)
        if (abs(right) > PAYOFF_EPSILON) {
            val tail = last - lastValue / right
            if (tail > last + PAYOFF_EPSILON) roots += tail
        }
        var maxProfit = if (vals.isNotEmpty()) jsMax(vals.toList()) else 0.0
        var maxLoss = if (vals.isNotEmpty()) jsMin(vals.toList()) else 0.0
        if (right > PAYOFF_EPSILON) maxProfit = Double.POSITIVE_INFINITY
        if (right < -PAYOFF_EPSILON) maxLoss = Double.NEGATIVE_INFINITY
        return Analysis(uniqueSorted(roots), maxProfit, maxLoss)
    }

    /** Golden-section search for the extremum inside [left, right]; returns the value there. */
    private fun refineExtremum(valueAt: (Double) -> Double, left: Double, right: Double, maximize: Boolean): Double {
        val ratio = (sqrt(5.0) - 1) / 2
        var lo = left
        var hi = right
        var x1 = hi - ratio * (hi - lo)
        var x2 = lo + ratio * (hi - lo)
        var y1 = valueAt(x1)
        var y2 = valueAt(x2)
        repeat(64) {
            val keepLeft = if (maximize) y1 > y2 else y1 < y2
            if (keepLeft) {
                hi = x2; x2 = x1; y2 = y1
                x1 = hi - ratio * (hi - lo); y1 = valueAt(x1)
            } else {
                lo = x1; x1 = x2; y1 = y2
                x2 = lo + ratio * (hi - lo); y2 = valueAt(x2)
            }
        }
        return valueAt((lo + hi) / 2)
    }

    /** The constant the payoff tends to far to the right once the scaled terms are known to cancel. */
    private fun rightTailValue(legs: List<StrategyLeg>): Double {
        var v = 0.0
        for (leg in legs) {
            if (!leg.active) continue
            val q = leg.quantity
            when {
                leg.isClosed -> v += leg.sign * (leg.exitPrice!! - leg.price) * q
                leg.segment == Segment.OPTION && leg.strike != null && leg.optionType != null ->
                    v += leg.sign * ((if (leg.optionType == OptionType.CE) -leg.strike else 0.0) - leg.price) * q
                leg.segment == Segment.FUTURE -> v += leg.sign * (0 - leg.price) * q
            }
        }
        return normalize(v)
    }

    private fun analyzeNonTerminal(legs: List<StrategyLeg>, spot: Double, daysAtExpiry: Double, range: Pair<Double, Double>,
                                   ivShift: Double, fallbackIv: Double, now: Instant, rate: Double?): Analysis {
        val strikes = responsiveStrikes(legs)
        val maxStrike = jsMax(listOf(spot) + strikes)
        val maxYears = jsMax(listOf(0.0) + legs.map { remainingYears(it, daysAtExpiry, now) })
        val maxIv = jsMax(listOf(fallbackIv) + legs.map { if (it.iv > 0) it.iv else fallbackIv }) * (1 + ivShift / 100)
        val sigmaMove = if (spot > 0 && maxIv > 0) spot * (maxIv / 100) * sqrt(maxYears) else 0.0
        val valueAt = { u: Double -> normalize(totalPnlAt(legs, u, daysAtExpiry, ivShift, fallbackIv, now, rate)) }
        val (right, _) = asymptoticSlopes(legs, daysAtExpiry, now, rate)
        val tailLimit = rightTailValue(legs)
        var hi = jsMax(listOf(range.second, maxStrike * 2, spot + 6 * sigmaMove))
        for (e in 0 until 20) {
            val tv = valueAt(hi)
            val ahead = (right > PAYOFF_EPSILON && tv < 0) || (right < -PAYOFF_EPSILON && tv > 0) ||
                (abs(right) <= PAYOFF_EPSILON && tv * tailLimit < 0)
            if (!ahead) break
            hi *= 2
        }
        val intervals = 1024
        val grid = (0..intervals).map { hi * it / intervals }
        val xs = uniqueSorted(grid + strikes)
        val values = xs.map(valueAt).toDoubleArray()
        val roots = ArrayList<Double>()
        val extrema = values.toMutableList()
        if (abs(right) <= PAYOFF_EPSILON) extrema += tailLimit
        // Only interior zeros that are the edge of a plateau count: the ends are this window's own choice.
        for (i in 1 until values.size - 1) {
            if (values[i] != 0.0) continue
            if (values[i - 1] == 0.0 && values[i + 1] == 0.0) continue
            roots += xs[i]
        }
        for (i in 0 until xs.size - 1) {
            val lv = values[i]; val rv = values[i + 1]
            if (lv * rv >= 0) continue
            var lo = xs[i]; var h = xs[i + 1]
            for (it in 0 until 64) {
                val mid = (lo + h) / 2
                val mv = valueAt(mid)
                if (mv == 0.0) { lo = mid; h = mid; break }
                if (lv * mv < 0) h = mid else lo = mid
            }
            roots += (lo + h) / 2
        }
        for (i in 1 until xs.size - 1) {
            val p = values[i - 1]; val c = values[i]; val n = values[i + 1]
            if (c >= p && c >= n) extrema += refineExtremum(valueAt, xs[i - 1], xs[i + 1], true)
            if (c <= p && c <= n) extrema += refineExtremum(valueAt, xs[i - 1], xs[i + 1], false)
        }
        return Analysis(uniqueSorted(roots, 1e-6),
            if (right > PAYOFF_EPSILON) Double.POSITIVE_INFINITY else jsMax(extrema),
            if (right < -PAYOFF_EPSILON) Double.NEGATIVE_INFINITY else jsMin(extrema))
    }

    /**
     * `computePayoff`: the payoff chart and its summary.
     *
     * @param daysAtExpiry calendar days to advance for the "expiry" curve - for
     *   a calendar, the days to the NEAREST leg's expiry ([nearestLegDays]); the
     *   farther legs are then priced with their residual time value
     * @param daysAtT0 calendar days to advance for the T+n curve (0 = now)
     * @param priceRange requested window, e.g. [payoffPriceRange]; widened to
     *   include every strike and breakeven
     * @param ivShiftPct relative IV shift in percent (10 = every IV x 1.1)
     * @param fallbackIv IV (percent) for legs without their own
     */
    fun computePayoff(legs: List<StrategyLeg>, spot: Double, daysAtExpiry: Double, daysAtT0: Double, priceRange: Pair<Double, Double>,
                      steps: Int = 240, ivShiftPct: Double = 0.0, fallbackIv: Double = 0.0, now: Instant): PayoffResult {
        val terminal = isTerminalHorizon(legs, daysAtExpiry, now)
        val rate = carryRate(legs, now)
        val a = if (terminal) analyzeTerminal(legs, daysAtExpiry, ivShiftPct, fallbackIv, now, rate)
        else analyzeNonTerminal(legs, spot, daysAtExpiry, priceRange, ivShiftPct, fallbackIv, now, rate)
        val strikes = responsiveStrikes(legs)
        val framing = a.breakevens.filter { it > 0 }
        val lo = Math.max(0.0, jsMin(listOf(priceRange.first) + strikes + framing))
        val hi = jsMax(listOf(priceRange.second) + strikes + framing)
        val safeSteps = maxOf(1, steps)
        val step = (hi - lo) / safeSteps
        val uniform = (0..safeSteps).map { lo + it * step }
        val inFrame = { v: Double -> v >= lo && v <= hi }
        val sampleXs = uniqueSorted(uniform + strikes + a.breakevens).filter(inFrame)
        val sample = { u: Double ->
            PayoffSample(u, normalize(totalPnlAt(legs, u, daysAtExpiry, ivShiftPct, fallbackIv, now, rate)),
                normalize(totalPnlAt(legs, u, daysAtT0, ivShiftPct, fallbackIv, now, rate)))
        }
        var samples = sampleXs.map(sample)
        if (a.breakevens.isNotEmpty()) samples = uniqueSorted(sampleXs + a.breakevens.filter(inFrame)).map(sample)
        val zeros = samples.indices.filter { samples[it].expiry == 0.0 }
        return PayoffResult(samples, a.maxProfit, a.maxLoss, a.breakevens, zeros)
    }

    /**
     * `probabilityOfProfit`: lognormal S_T at zero drift (ln S_T/S_0 ~ N(-sigma^2 T/2, sigma^2 T))
     * from the ATM IV (percent); probability mass summed over sample intervals
     * whose midpoint expiry P&L is positive, plus both tails by the sign of the
     * end samples. Null on degenerate input.
     */
    fun probabilityOfProfit(samples: List<PayoffSample>, spot: Double, atmIv: Double, tYears: Double): Double? {
        if (samples.size < 2 || !spot.isFinite() || !atmIv.isFinite() || !tYears.isFinite() || spot <= 0 || atmIv <= 0 || tYears <= 0 ||
            samples.any { !it.underlying.isFinite() || !it.expiry.isFinite() }) return null
        val sigma = atmIv / 100
        val sqrtT = sqrt(tYears)
        val variance = sigma * sigma * tYears
        val sigmaT = sigma * sqrtT
        val mu = -0.5 * variance
        if (!listOf(sigma, sqrtT, variance, sigmaT, mu).all { it.isFinite() } || sigmaT <= 0) return null
        fun cdf(x: Double): Double? {
            if (x <= 0) return 0.0
            val ratio = x / spot
            val lr = ln(ratio)
            val num = lr - mu
            val z = num / sigmaT
            if (!listOf(ratio, lr, num, z).all { it.isFinite() }) return null
            return normCdf(z).takeIf { it.isFinite() }
        }
        var prob = 0.0
        for (i in 0 until samples.size - 1) {
            val a = samples[i]; val b = samples[i + 1]
            val mid = a.expiry / 2 + b.expiry / 2
            if (!mid.isFinite()) return null
            if (mid > 0) {
                val up = cdf(b.underlying) ?: return null
                val low = cdf(a.underlying) ?: return null
                val mass = up - low
                if (!mass.isFinite()) return null
                prob += mass
                if (!prob.isFinite()) return null
            }
        }
        val last = samples.last()
        if (last.expiry > 0) prob += 1 - (cdf(last.underlying) ?: return null)
        val first = samples.first()
        if (first.expiry > 0) prob += cdf(first.underlying) ?: return null
        if (!prob.isFinite()) return null
        return maxOf(0.0, minOf(1.0, prob))
    }

    // ---------------------------------------------------------------- expiries and symbols

    private fun legExpiryIdentity(leg: StrategyLeg): String {
        val ts = leg.expiryTs
        if (ts != null && ts.isFinite() && ts > 0) return Instant.ofEpochMilli((ts * 1000).toLong()).atOffset(ZoneOffset.UTC).toLocalDate().toString()
        parseExpiryDate(leg.expiry)?.let { return it.atOffset(ZoneOffset.UTC).toLocalDate().toString() }
        return leg.expiry.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
    }

    /** True when the open legs reach more than one calendar expiry (a calendar or diagonal). */
    fun hasMultipleActiveExpiries(legs: List<StrategyLeg>): Boolean =
        legs.filter { it.active && !it.isClosed }.map(::legExpiryIdentity).filter { it.isNotEmpty() }.toSet().size > 1

    /** Days to the nearest open leg's expiry: the "expiry" horizon for calendars. 0 with no open leg. */
    fun nearestLegDays(legs: List<StrategyLeg>, now: Instant): Double {
        var best = Double.POSITIVE_INFINITY
        for (leg in legs) {
            if (!leg.active || leg.isClosed) continue
            val d = remainingDays(leg, 0.0, now)
            if (d < best) best = d
        }
        return if (best == Double.POSITIVE_INFINITY) 0.0 else best
    }

    /** `buildOptionSymbol`: BASE + DDMMMYY + strike (integral strikes without decimals) + CE/PE. */
    fun buildOptionSymbol(base: String, expiry: String, strike: Double, type: OptionType): String {
        val s = if (strike == Math.rint(strike) || abs(strike - Math.rint(strike)) < 1e-6) Math.round(strike).toString() else jsNumber(strike)
        return "$base$expiry$s$type"
    }

    fun buildFutureSymbol(base: String, expiry: String): String = "${base}${expiry}FUT"

    /** JavaScript's String(number) for ordinary finite values: shortest round-trip, no trailing ".0". */
    internal fun jsNumber(x: Double): String {
        val s = java.math.BigDecimal(x.toString()).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    // ---------------------------------------------------------------- underlying reference

    private val NSE_INDEX = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50", "NIFTYIT", "NIFTYPHARMA", "NIFTYBANK")
    private val BSE_INDEX = setOf("SENSEX", "BANKEX", "SENSEX50")
    private val NO_SPOT = setOf("MCX", "CDS", "BCD", "NCDEX", "NCO")

    /** `get_quote_exchange` of `strategy_builder_reference_service.py`: where an underlying's own quote lives. */
    fun quoteExchange(base: String, exchange: String): String {
        val up = exchange.uppercase()
        return when {
            base in NSE_INDEX -> "NSE_INDEX"
            base in BSE_INDEX -> "BSE_INDEX"
            up == "NFO" -> "NSE"
            up == "BFO" -> "BSE"
            else -> up
        }
    }

    /**
     * `resolve_strategy_builder_reference` for venues with a tradable spot:
     * the chain's canonical pair when given, else (base, [quoteExchange]).
     * Null for no-spot venues (MCX, currencies) and crypto, whose reference is
     * the near-month or perpetual future - a master-contract lookup the caller does.
     */
    fun chartReference(base: String, exchange: String, underlyingSymbol: String? = null, underlyingExchange: String? = null,
                       cryptoExchanges: Set<String> = emptySet()): Pair<String, String>? {
        val b = base.trim().uppercase()
        val v = exchange.trim().uppercase()
        if (!underlyingSymbol.isNullOrEmpty() && !underlyingExchange.isNullOrEmpty()) return underlyingSymbol.trim().uppercase() to underlyingExchange.trim().uppercase()
        if (v in cryptoExchanges || v in NO_SPOT) return null
        return b to quoteExchange(b, v)
    }
}
