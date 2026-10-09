package com.optionslab.ira

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The gamma regime of an index (Boss, 9 Oct 2026): a LIVE READING and shadow-log fields only, never a rule. From the option
 * chain the app already prices (OI per strike, each side's implied vol): net gamma exposure (GEX) = Σ gamma × OI × lot,
 * calls against puts, at the spot, and the zero-gamma level - the price where the net would cross zero ([zeroGamma]),
 * found by re-pricing every strike's Black-Scholes gamma at prices ±5% around the spot.
 *
 * Who is short what is an ASSUMPTION, and two are offered ([Convention]): the standard one (dealers long the calls and
 * short the puts: calls count +, puts −) and the India-sellers variant (the big option writers short the calls retail buys
 * and long the puts' hedge: calls −, puts +). The variant only flips the sign - the zero-gamma level is the same price. A
 * positive net is read as "choppy" (hedging leans against moves), a negative one as "trending". Pure.
 */
object GammaRegime {
    enum class Convention(val label: String, val words: String) {
        STANDARD("Standard", "dealers long calls, short puts (calls +, puts −)"),
        INDIA_SELLERS("India sellers", "option sellers short calls, long puts (calls −, puts +)"),
    }

    enum class Sign(val words: String) { POSITIVE("positive (choppy)"), NEGATIVE("negative (trending)") }

    const val NOTE = "dealer sign is an assumption in India"

    /** Refreshed at most this often per index. */
    const val REFRESH_MS = 5 * 60_000L

    /** The re-pricing span around the spot (±5%) and its steps. */
    const val SPAN = 0.05
    const val STEPS = 200

    /** One strike: the calls' and puts' OI and implied vols (fractions; null: not known). */
    data class Strike(val strike: Double, val ceOi: Long, val peOi: Long, val ceIv: Double?, val peIv: Double?)

    /**
     * The regime of [underlying] at [atMs]: the net GEX at the spot under the STANDARD convention ([netStd]), the
     * zero-gamma level (null: no crossing within ±5%), the chain's spot and forward.
     */
    data class Result(val underlying: String, val atMs: Long, val spot: Double, val forward: Double, val netStd: Double, val zeroGamma: Double?) {
        fun net(c: Convention): Double = if (c == Convention.STANDARD) netStd else -netStd
        fun sign(c: Convention): Sign = if (net(c) >= 0) Sign.POSITIVE else Sign.NEGATIVE
        /** The spot's distance above the zero-gamma level (points; null: no level). */
        fun distance(price: Double = spot): Double? = zeroGamma?.let { price - it }
    }

    /** Black-Scholes gamma (no rate) of a strike [k] at price [s], [t] years, vol [sigma]; 0 when not defined. */
    fun gamma(s: Double, k: Double, t: Double, sigma: Double): Double {
        if (!(s > 0) || !(k > 0) || !(t > 0) || !(sigma > 0)) return 0.0
        val v = sigma * sqrt(t)
        val d1 = (ln(s / k) + 0.5 * v * v) / v
        val g = exp(-d1 * d1 / 2) / sqrt(2 * PI) / (s * v)
        return if (g.isFinite()) g else 0.0
    }

    /** The net GEX at price [s] under the STANDARD convention: Σ (Γcall·OIcall − Γput·OIput) × [lot]. */
    fun netAt(s: Double, strikes: List<Strike>, t: Double, lot: Int, fallbackIv: Double): Double {
        var n = 0.0
        for (k in strikes) {
            val ci = k.ceIv ?: k.peIv ?: fallbackIv
            val pi = k.peIv ?: k.ceIv ?: fallbackIv
            n += gamma(s, k.strike, t, ci) * k.ceOi - gamma(s, k.strike, t, pi) * k.peOi
        }
        return n * lot.coerceAtLeast(1)
    }

    /**
     * The zero-gamma level: of the prices where the net GEX changes sign within ±[span] of [spot] (re-priced at [steps]
     * points), the one nearest the spot, linearly interpolated. Null when the sign never changes there.
     */
    fun zeroGamma(strikes: List<Strike>, spot: Double, t: Double, lot: Int, fallbackIv: Double, span: Double = SPAN, steps: Int = STEPS): Double? {
        if (!(spot > 0) || steps < 2) return null
        val lo = spot * (1 - span); val step = 2 * span * spot / steps
        var best: Double? = null
        var px = lo; var prev = netAt(px, strikes, t, lot, fallbackIv)
        for (i in 1..steps) {
            val x = lo + i * step
            val v = netAt(x, strikes, t, lot, fallbackIv)
            if (prev == 0.0 || prev * v < 0) {
                val z = if (prev == 0.0) px else px + (x - px) * (prev / (prev - v))
                if (best == null || abs(z - spot) < abs(best - spot)) best = z
            }
            px = x; prev = v
        }
        return best
    }

    /** The regime from a chain: null with no time left, no spot, no OI or no implied vol at all. */
    fun compute(underlying: String, spot: Double, forward: Double, tYears: Double, lot: Int, strikes: List<Strike>, atMs: Long): Result? {
        if (!(spot > 0) || !(tYears > 0)) return null
        val used = strikes.filter { it.ceOi > 0 || it.peOi > 0 }
        if (used.isEmpty()) return null
        val ivs = used.flatMap { listOfNotNull(it.ceIv, it.peIv) }.filter { it > 0 && it.isFinite() }.sorted()
        if (ivs.isEmpty()) return null
        val fallback = ivs[ivs.size / 2]
        val net = netAt(spot, used, tYears, lot, fallback)
        return Result(underlying, atMs, spot, if (forward > 0) forward else spot, net, zeroGamma(used, spot, tYears, lot, fallback))
    }

    /** The regime from a priced chain (each strike's OI, its legs' implied vols as the chain shows them). */
    fun of(c: com.optionslab.engine.options.ChainSnapshot, atMs: Long): Result? = compute(c.underlying, c.spot, c.forward, c.tYears, c.lotSize,
        c.rows.mapIndexed { i, r ->
            Strike(r.strike, r.ce?.oi ?: 0L, r.pe?.oi ?: 0L,
                c.ceGreeks.getOrNull(i)?.ivPct?.takeIf { it > 0 }?.div(100), c.peGreeks.getOrNull(i)?.ivPct?.takeIf { it > 0 }?.div(100))
        }, atMs)

    private fun px(x: Double) = "%,.0f".format(Locale.ENGLISH, x)

    /** "Gamma: positive (choppy) · zero-γ 52,300 (spot 0.4% above)" under [c]; "Gamma: not known yet" with no result. */
    fun line(r: Result?, c: Convention): String {
        if (r == null) return "Gamma: not known yet"
        val z = r.zeroGamma?.let { zg ->
            val pct = (r.spot - zg) / zg * 100
            " · zero-γ ${px(zg)} (spot ${"%.1f".format(Locale.ENGLISH, abs(pct))}% ${if (pct >= 0) "above" else "below"})"
        } ?: " · no zero-γ within ±5%"
        return "Gamma: ${r.sign(c).words}$z"
    }

    /** Jarvis's answer for [name] under [c] (null result: not priced yet). */
    fun answer(name: String, r: Result?, c: Convention, nowMs: Long): String {
        val who = OrderFlow.label(name)
        if (r == null) return "I have no gamma regime for $who yet, Boss: it comes from the option chain, priced at most every 5 minutes " +
            "while the price stream runs, or when you open Options → GEX."
        val mins = ((nowMs - r.atMs) / 60_000).coerceAtLeast(0)
        val other = Convention.entries.first { it != c }
        return "$who's ${line(r, c).replaceFirstChar { it.lowercase() }} under the ${c.label.lowercase(Locale.ENGLISH)} convention " +
            "(${c.words}); under the ${other.label.lowercase(Locale.ENGLISH)} one it reads ${r.sign(other).words}. " +
            "The $NOTE. From the chain $mins minute${if (mins == 1L) "" else "s"} ago. A live reading, not a signal; no strategy uses it."
    }
}
