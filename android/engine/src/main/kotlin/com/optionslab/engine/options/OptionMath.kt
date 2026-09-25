package com.optionslab.engine.options

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Call or put, in IraAlgo's (and NSE's) spelling. */
enum class OptionType {
    CE, PE;

    /** opengreeks' flag: 'c' or 'p'. */
    val isCall: Boolean get() = this == CE
}

/**
 * Black-76 Greeks in opengreeks' trader units, which is what IraAlgo shows:
 * theta per calendar day, vega per 1 vol point, rho per 1 rate point.
 */
data class Greeks(
    val delta: Double,
    val gamma: Double,
    val theta: Double,
    val vega: Double,
    val rho: Double,
)

/**
 * Outcome of IraAlgo's `calculate_greeks` for one leg.
 *
 * [theoretical] marks the "deep ITM / no time value" branch: IV 0, delta +/-1,
 * every other Greek 0, exactly as IraAlgo (and Opstra) report it rather than
 * inverting a price that carries no volatility information.
 */
data class LegGreeks(
    /** Implied volatility in percent (14.5 = 14.5%). 0 on the theoretical branch. */
    val ivPct: Double,
    val greeks: Greeks,
    val intrinsic: Double,
    /** max(price - intrinsic, 0), as the service reports it. */
    val timeValue: Double,
    val theoretical: Boolean,
)

/** One leg of IraAlgo's vectorised chain Greeks (`calculate_chain_greeks`), rounded as it rounds them. */
data class ChainLegGreeks(val ivPct: Double, val delta: Double, val gamma: Double, val theta: Double, val vega: Double)

/**
 * Black-76 pricing, implied volatility and Greeks. Port of
 * `services/option_greeks_service.py`, which wraps the `opengreeks` Rust
 * library, and of `frontend/src/lib/optionGreeks.ts`, which mirrors it.
 *
 * Black-76 prices off the FORWARD, not spot: Indian index futures trade at a
 * premium, and pricing an index chain off the cash LTP biases every delta.
 * IraAlgo's forward is the per-expiry synthetic future K + C - P at the ATM
 * strike ([ChainAnalytics.syntheticFuture]), falling back to spot.
 *
 * Conventions, all IraAlgo's:
 *  - rates are passed as DECIMALS here (0.065) but IraAlgo's services take an
 *    annualised PERCENT and default it to 0 for every exchange (NFO/BFO/CDS/MCX);
 *  - time is calendar time: seconds / 86400 / 365, to the exchange cut-off
 *    (15:30 IST for NFO/BFO, 12:30 CDS, 23:30 MCX), floored at 0.0001 years;
 *  - there is no dividend yield: the forward already carries it.
 */
object OptionMath {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** NFO, BFO and anything following the equity-derivatives session. */
    val DEFAULT_EXPIRY_TIME: LocalTime = LocalTime.of(15, 30)

    /** Per-exchange expiry cut-offs (IST). MCX is a default; the real one varies by commodity. */
    val EXCHANGE_EXPIRY_TIMES: Map<String, LocalTime> = mapOf("MCX" to LocalTime.of(23, 30), "CDS" to LocalTime.of(12, 30))

    /** opengreeks' volatility search bracket. Prices outside it clamp to the bound instead of failing. */
    const val MIN_VOL = 1e-6
    const val MAX_VOL = 5.0

    /** `calculate_time_to_expiry` never returns less than this for an unexpired option (~53 minutes). */
    const val MIN_YEARS = 0.0001

    private val SQRT_2PI = sqrt(2 * Math.PI)
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    // ---------------------------------------------------------------- normal

    /**
     * Cumulative standard normal (Hart 1968, as popularised by West): the same
     * routine `optionGreeks.ts` uses, accurate to about double precision across
     * the range. Abramowitz-Stegun 7.1.26 (~1e-7) is not enough to keep an
     * implied-volatility solve in agreement with opengreeks.
     */
    fun normCdf(x: Double): Double {
        val z = abs(x)
        var c = 0.0
        if (z <= 37) {
            val e = exp(-z * z / 2)
            c = if (z < 7.07106781186547) {
                var b = 3.52624965998911e-2 * z + 0.700383064443688
                b = b * z + 6.37396220353165
                b = b * z + 33.912866078383
                b = b * z + 112.079291497871
                b = b * z + 221.213596169931
                b = b * z + 220.206867912376
                var d = 8.83883476483184e-2 * z + 1.75566716318264
                d = d * z + 16.064177579207
                d = d * z + 86.7807322029461
                d = d * z + 296.564248779674
                d = d * z + 637.333633378831
                d = d * z + 793.826512519948
                d = d * z + 440.413735824752
                e * b / d
            } else {
                var b = z + 0.65
                b = z + 4 / b
                b = z + 3 / b
                b = z + 2 / b
                b = z + 1 / b
                e / (b * 2.506628274631)
            }
        }
        return if (x > 0) 1 - c else c
    }

    fun normPdf(x: Double): Double = exp(-x * x / 2) / SQRT_2PI

    // ---------------------------------------------------------------- Black-76

    /**
     * Black-76 price. [rate] and [sigma] are decimals; [tYears] in years.
     * Matches `opengreeks.black76.black` to ~1e-12 relative.
     */
    fun price(type: OptionType, forward: Double, strike: Double, tYears: Double, rate: Double, sigma: Double): Double {
        val vol = sigma * sqrt(tYears)
        val d1 = (ln(forward / strike) + sigma * sigma * tYears / 2) / vol
        val d2 = d1 - vol
        val df = exp(-rate * tYears)
        return if (type.isCall) df * (forward * normCdf(d1) - strike * normCdf(d2))
        else df * (strike * normCdf(-d2) - forward * normCdf(-d1))
    }

    /**
     * Black-76 Greeks in opengreeks' units (verified against it):
     * theta = (-F e^-rt n(d1) sigma / 2 sqrt t + r price) / 365, vega x 0.01,
     * rho = -t price x 0.01 (a futures option's only rate exposure is its discount).
     */
    fun greeks(type: OptionType, forward: Double, strike: Double, tYears: Double, rate: Double, sigma: Double): Greeks {
        val sqrtT = sqrt(tYears)
        val d1 = (ln(forward / strike) + sigma * sigma * tYears / 2) / (sigma * sqrtT)
        val df = exp(-rate * tYears)
        val pdf = normPdf(d1)
        val p = price(type, forward, strike, tYears, rate, sigma)
        return Greeks(
            delta = if (type.isCall) df * normCdf(d1) else -df * normCdf(-d1),
            gamma = df * pdf / (forward * sigma * sqrtT),
            theta = (-forward * df * pdf * sigma / (2 * sqrtT) + rate * p) / 365.0,
            vega = df * forward * pdf * sqrtT * 0.01,
            rho = -tYears * p * 0.01,
        )
    }

    /** Why opengreeks refused to invert a price. */
    enum class IvFailure { BELOW_INTRINSIC, ABOVE_MAXIMUM }

    /** Either a volatility (decimal) or opengreeks' refusal. */
    sealed interface IvSolve {
        data class Solved(val sigma: Double) : IvSolve
        data class Failed(val reason: IvFailure) : IvSolve
    }

    /**
     * opengreeks' `black76.implied_volatility(price, F, K, r, t, flag)`, with
     * its exact failure behaviour (probed, not guessed):
     *  - price < e^-rt x intrinsic raises "price is below intrinsic value";
     *  - price > e^-rt x F (call) or e^-rt x K (put) raises "exceeds theoretical maximum";
     *  - otherwise the answer is clamped to [MIN_VOL, MAX_VOL]: a zero price
     *    returns 1e-6, a price above the sigma = 5 value returns 5.
     * Inside the bracket the root is solved to machine precision, as opengreeks'
     * rational solver does.
     */
    fun solveIv(price: Double, type: OptionType, forward: Double, strike: Double, tYears: Double, rate: Double): IvSolve {
        val df = exp(-rate * tYears)
        val intrinsic = df * if (type.isCall) max(forward - strike, 0.0) else max(strike - forward, 0.0)
        val maximum = df * if (type.isCall) forward else strike
        if (price < intrinsic) return IvSolve.Failed(IvFailure.BELOW_INTRINSIC)
        if (price > maximum || forward <= 0) return IvSolve.Failed(IvFailure.ABOVE_MAXIMUM)
        if (!(tYears > 0)) return IvSolve.Solved(if (tYears == 0.0) MAX_VOL else MIN_VOL)
        val f = { s: Double -> price(type, forward, strike, tYears, rate, s) - price }
        if (f(MIN_VOL) >= 0) return IvSolve.Solved(MIN_VOL)
        if (f(MAX_VOL) <= 0) return IvSolve.Solved(MAX_VOL)
        // Safeguarded Newton on a bracket that always holds the root: the
        // price is monotone in sigma, so bisection can never lose it, and
        // Newton converges quadratically wherever vega is not vanishing.
        var lo = MIN_VOL
        var hi = MAX_VOL
        var s = 0.25
        for (i in 0 until 200) {
            val diff = f(s)
            if (diff == 0.0) return IvSolve.Solved(s)
            if (diff > 0) hi = s else lo = s
            val vega = exp(-rate * tYears) * forward * normPdf((ln(forward / strike) + s * s * tYears / 2) / (s * sqrt(tYears))) * sqrt(tYears)
            var next = if (vega > 0) s - diff / vega else Double.NaN
            if (!(next > lo && next < hi)) next = (lo + hi) / 2
            if (abs(next - s) <= 1e-15 * max(1.0, s) || hi - lo <= 1e-15 * hi) return IvSolve.Solved(next)
            s = next
        }
        return IvSolve.Solved(s)
    }

    /** Implied volatility as a decimal, or null where opengreeks would raise. */
    fun impliedVol(price: Double, type: OptionType, forward: Double, strike: Double, tYears: Double, rate: Double = 0.0): Double? =
        (solveIv(price, type, forward, strike, tYears, rate) as? IvSolve.Solved)?.sigma

    // ---------------------------------------------------------------- IraAlgo's leg rules

    private fun theoreticalGreeks(type: OptionType) = Greeks(if (type.isCall) 1.0 else -1.0, 0.0, 0.0, 0.0, 0.0)

    /**
     * Port of `calculate_greeks` (the per-symbol Greeks endpoint) after symbol
     * parsing and quote fetching: unrounded, so a caller can round for display.
     *
     * Returns null where IraAlgo returns an error: non-positive forward, price
     * or strike, an expired leg, or a price above opengreeks' maximum. A leg
     * with no time value - `time_value <= 0`, or an ITM leg with less than
     * 0.01 of it - gets theoretical Greeks (IV 0, delta +/-1), and so does a
     * price opengreeks calls "below intrinsic". Note the no-time-value test uses
     * the UNDISCOUNTED intrinsic, as IraAlgo does.
     *
     * @param ratePct annualised percent, IraAlgo's unit; its default is 0 on every exchange.
     */
    fun legGreeks(type: OptionType, forward: Double, strike: Double, tYears: Double, price: Double, ratePct: Double = 0.0): LegGreeks? {
        if (tYears <= 0) return null
        if (forward <= 0 || price <= 0) return null
        if (strike <= 0) return null
        val intrinsic = if (type.isCall) max(forward - strike, 0.0) else max(strike - forward, 0.0)
        val timeValue = price - intrinsic
        val theoretical = LegGreeks(0.0, theoreticalGreeks(type), intrinsic, max(timeValue, 0.0), true)
        if (timeValue <= 0 || (intrinsic > 0 && timeValue < 0.01)) return theoretical
        val rate = ratePct / 100.0
        return when (val iv = solveIv(price, type, forward, strike, tYears, rate)) {
            is IvSolve.Failed -> if (iv.reason == IvFailure.BELOW_INTRINSIC) theoretical else null
            is IvSolve.Solved -> LegGreeks(iv.sigma * 100.0, greeks(type, forward, strike, tYears, rate, iv.sigma), intrinsic, max(timeValue, 0.0), false)
        }
    }

    /** [legGreeks] rounded exactly as IraAlgo's response rounds it (IV 2 dp, delta/theta/vega 4, gamma 6, rho 6). */
    fun legGreeksRounded(type: OptionType, forward: Double, strike: Double, tYears: Double, price: Double, ratePct: Double = 0.0): LegGreeks? =
        legGreeks(type, forward, strike, tYears, price, ratePct)?.let { g ->
            if (g.theoretical) g.copy(intrinsic = pyRound(g.intrinsic, 2), timeValue = pyRound(g.timeValue, 2))
            else g.copy(
                ivPct = pyRound(g.ivPct, 2),
                greeks = Greeks(pyRound(g.greeks.delta, 4), pyRound(g.greeks.gamma, 6), pyRound(g.greeks.theta, 4), pyRound(g.greeks.vega, 4), pyRound(g.greeks.rho, 6)),
                intrinsic = pyRound(g.intrinsic, 2), timeValue = pyRound(g.timeValue, 2),
            )
        }

    /**
     * Port of `calculate_chain_greeks`: IV and Greeks for a whole strike ladder,
     * aligned to [strikes], rounded as the option chain shows them.
     *
     * A null price (or 0) means "no quote" and yields null. A leg with no time
     * value, or whose IV does not converge, gets theoretical Greeks - but here,
     * unlike the single-leg endpoint, only an IN-the-money leg collapses to
     * delta +/-1: an OTM leg that will not invert is worth ~0 and gets delta 0.
     */
    fun chainGreeks(
        strikes: List<Double>, cePrices: List<Double?>, pePrices: List<Double?>,
        forward: Double, tYears: Double, ratePct: Double = 0.0,
    ): Pair<List<ChainLegGreeks?>, List<ChainLegGreeks?>> {
        val n = strikes.size
        if (n == 0) return emptyList<ChainLegGreeks?>() to emptyList()
        if (forward <= 0 || tYears <= 0) return List<ChainLegGreeks?>(n) { null } to List(n) { null }
        val rate = ratePct / 100.0
        fun side(prices: List<Double?>, type: OptionType): List<ChainLegGreeks?> = strikes.indices.map { i ->
            val k = strikes[i]
            val p = prices[i] ?: 0.0
            val intrinsic = if (type.isCall) max(forward - k, 0.0) else max(k - forward, 0.0)
            val tv = p - intrinsic
            if (!(p > 0 && k > 0)) return@map null
            val noTimeValue = tv <= 0 || (intrinsic > 0 && tv < 0.01)
            val sigma = if (noTimeValue) null else impliedVol(p, type, forward, k, tYears, rate)
            if (sigma != null && sigma.isFinite() && sigma > 0) {
                val g = greeks(type, forward, k, tYears, rate, sigma)
                ChainLegGreeks(pyRound(sigma * 100.0, 2), pyRound(g.delta, 4), pyRound(g.gamma, 6), pyRound(g.theta, 4), pyRound(g.vega, 4))
            } else {
                val d = if (intrinsic > 0) (if (type.isCall) 1.0 else -1.0) else 0.0
                ChainLegGreeks(0.0, d, 0.0, 0.0, 0.0)
            }
        }
        return side(cePrices, OptionType.CE) to side(pePrices, OptionType.PE)
    }

    // ---------------------------------------------------------------- time

    /** `get_exchange_expiry_time`: the IST wall-clock cut-off after which an option has no time value. */
    fun expiryCutoff(exchange: String): LocalTime = EXCHANGE_EXPIRY_TIMES[exchange.uppercase()] ?: DEFAULT_EXPIRY_TIME

    /** Parse IraAlgo's DDMMMYY expiry code ("28NOV25"). */
    fun parseExpiryCode(code: String): LocalDate {
        val m = Regex("(\\d{2})([A-Z]{3})(\\d{2})").matchEntire(code.trim().uppercase())
            ?: throw IllegalArgumentException("Invalid expiry format: $code. Expected DDMMMYY (e.g. 28NOV25)")
        val (d, mon, y) = m.destructured
        val month = MONTHS.indexOf(mon) + 1
        require(month > 0) { "Invalid month in expiry: $code" }
        return LocalDate.of(2000 + y.toInt(), month, d.toInt())
    }

    /** An option symbol split into its parts: `NIFTY28NOV2424000CE`, `USDINR28NOV2483.50CE`. */
    data class OptionSymbol(val underlying: String, val expiry: LocalDate, val strike: Double, val type: OptionType)

    /** `parse_option_symbol`: SYMBOL + DD + MMM + YY + STRIKE + CE/PE (a prefix match, as Python's re.match). */
    fun parseOptionSymbol(symbol: String): OptionSymbol {
        val m = Regex("([A-Z]+)(\\d{2})([A-Z]{3})(\\d{2})([\\d.]+)(CE|PE)").find(symbol.uppercase())
            ?.takeIf { it.range.first == 0 } ?: throw IllegalArgumentException("Invalid option symbol format: $symbol")
        val (base, d, mon, y, strike, type) = m.destructured
        return OptionSymbol(base, parseExpiryCode(d + mon + y), strike.toDouble(), OptionType.valueOf(type))
    }

    /** Format a date as IraAlgo's DDMMMYY code. */
    fun expiryCode(date: LocalDate): String =
        "%02d%s%02d".format(date.dayOfMonth, MONTHS[date.monthValue - 1], date.year % 100)

    /** `get_expiry_datetime`: the IST instant an expiry ends. */
    fun expiryInstant(expiry: LocalDate, cutoff: LocalTime = DEFAULT_EXPIRY_TIME): ZonedDateTime =
        ZonedDateTime.of(expiry, cutoff, IST)

    /**
     * `calculate_time_to_expiry`: (years, days) of calendar time to the
     * cut-off. An expired option is (0, 0); anything closer than [MIN_YEARS]
     * - including the cut-off instant itself - is floored to it, so an option
     * in its last hour still has a finite gamma.
     */
    fun timeToExpiry(now: ZonedDateTime, expiry: ZonedDateTime): Pair<Double, Double> {
        if (expiry.toInstant().isBefore(now.toInstant())) return 0.0 to 0.0
        val d = Duration.between(now.toInstant(), expiry.toInstant())
        val seconds = d.seconds + d.nano / 1e9
        var days = seconds / (60 * 60 * 24)
        var years = days / 365.0
        if (years < MIN_YEARS) {
            years = MIN_YEARS
            days = years * 365.0
        }
        return years to days
    }

    /** Years to [expiry]'s cut-off (15:30 IST unless [cutoff] says otherwise), IraAlgo's basis. */
    fun timeToExpiryYears(now: ZonedDateTime, expiry: LocalDate, cutoff: LocalTime = DEFAULT_EXPIRY_TIME): Double =
        timeToExpiry(now, expiryInstant(expiry, cutoff)).first

    // ---------------------------------------------------------------- forward and price choice

    /**
     * `forwardFromParity` / `_forward_from_chain`: F = K + C - P at the ATM
     * strike when both legs are priced, else the fallback (underlying LTP).
     */
    fun forwardFromParity(atmStrike: Double?, atmCe: Double?, atmPe: Double?, fallback: Double): Double =
        if (atmStrike != null && atmStrike != 0.0 && atmCe != null && atmPe != null && atmCe > 0 && atmPe > 0) atmStrike + atmCe - atmPe
        else fallback

    /**
     * `priceForGreeks`: the mid when the book is two-sided and uncrossed, else
     * the LTP. A stale last trade on an illiquid strike makes IV jump; the live
     * book does not.
     */
    fun priceForGreeks(ltp: Double?, bid: Double?, ask: Double?): Double {
        if (bid != null && ask != null && bid > 0 && ask > 0 && ask >= bid) return (bid + ask) / 2
        return if (ltp != null && ltp > 0) ltp else 0.0
    }
}

/**
 * Python's `round(x, n)`: correctly rounded from the exact binary value, ties
 * to even. IraAlgo rounds its responses this way, and a few of its numbers
 * (GEX from 6-dp gamma, max pain on 2-dp pain) are computed from rounded
 * values, so parity needs the identical rounding, not `Math.round`.
 */
fun pyRound(x: Double, digits: Int): Double {
    if (!x.isFinite()) return x
    val r = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    return if (r == 0.0) Math.copySign(0.0, x) else r // Python keeps the sign of a rounded-away negative
}
