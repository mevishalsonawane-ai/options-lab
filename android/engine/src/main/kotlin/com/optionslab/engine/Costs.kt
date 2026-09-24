package com.optionslab.engine

import kotlin.math.max

/**
 * Options transaction costs. Rates verified 2026-09-07. Port of `costs.py`.
 *
 * There is deliberately NO zero-cost regime. Once a zero-cost path exists,
 * some run uses it and gets reported.
 *
 * Brokerage is FLAT Rs 20/order, so the explicit fraction is premium-dependent,
 * and the SPREAD is 70-89% of the total and is an assumption, not data.
 */
object Costs {
    const val BROKERAGE_PER_ORDER = 20.0
    const val ORDERS_PER_ROUND_TRIP = 2
    const val STT_SELL_PCT = 0.0015
    const val EXCHANGE_PCT = 0.0003553
    const val SEBI_PER_CRORE = 10.0
    const val STAMP_BUY_PCT = 0.00003
    const val GST_PCT = 0.18
    const val STT_EXERCISE_PCT = 0.00125

    /** roll: in-session Roll estimator; quoted: NSE top-of-book; stress: beyond depth. */
    val SPREAD_PCT: Map<String, Double> = linkedMapOf("roll" to 0.0030, "quoted" to 0.0090, "stress" to 0.0200)
    val REGIMES: List<String> = SPREAD_PCT.keys.toList()

    /** spread per unit = 0.162 + 0.00292 x premium, floored at one tick. */
    const val TICK_SIZE = 0.05
    const val SPREAD_LAW_INTERCEPT = 0.162
    const val SPREAD_LAW_SLOPE = 0.00292
    private val REGIME_SCALE = SPREAD_PCT.mapValues { it.value / SPREAD_PCT.getValue("roll") }

    class UnknownRegime(regime: String) :
        IllegalArgumentException("unknown cost regime '$regime'; expected one of $REGIMES. There is no zero-cost regime by design.")

    private fun check(regime: String) { if (regime !in SPREAD_PCT) throw UnknownRegime(regime) }

    fun spreadPerUnit(premium: Double, regime: String = "quoted"): Double {
        check(regime)
        val law = SPREAD_LAW_INTERCEPT + SPREAD_LAW_SLOPE * max(premium, 0.0)
        return max(TICK_SIZE, REGIME_SCALE.getValue(regime) * law)
    }

    data class Charges(
        val brokerage: Double,
        val stt: Double,
        val exchange: Double,
        val sebi: Double,
        val stamp: Double,
        val gst: Double,
        val spread: Double,
        val premiumNotional: Double,
    ) {
        val total: Double get() = brokerage + stt + exchange + sebi + stamp + gst + spread
        val fractionOfPremium: Double get() = if (premiumNotional != 0.0) total / premiumNotional else 0.0

        fun items(): List<Pair<String, Double>> = listOf(
            "Brokerage" to brokerage, "STT" to stt, "Exchange" to exchange, "SEBI" to sebi,
            "Stamp" to stamp, "GST" to gst, "Spread" to spread,
        )
    }

    private fun charges(premium: Double, lotSize: Int, lots: Int, regime: String?): Charges {
        val qty = lotSize * lots
        val notional = premium * qty
        val brokerage = BROKERAGE_PER_ORDER * ORDERS_PER_ROUND_TRIP
        val stt = STT_SELL_PCT * notional
        val exchange = EXCHANGE_PCT * notional * 2
        val sebi = SEBI_PER_CRORE * (notional * 2) / 1e7
        val stamp = STAMP_BUY_PCT * notional
        val gst = GST_PCT * (brokerage + exchange + sebi)
        val spread = if (regime == null) 0.0 else spreadPerUnit(premium, regime) * qty
        return Charges(brokerage, stt, exchange, sebi, stamp, gst, spread, notional)
    }

    /** Statutory and brokerage charges only, no spread. */
    fun explicit(premium: Double, lotSize: Int, lots: Int = 1): Charges = charges(premium, lotSize, lots, null)

    /** Full round-trip cost including a crossed spread under a named regime. */
    fun roundTrip(premium: Double, lotSize: Int, lots: Int = 1, regime: String): Charges {
        check(regime)
        return charges(premium, lotSize, lots, regime)
    }

    /** Round-trip cost expressed as a move in the UNDERLYING, in index points. */
    fun breakevenIndexPoints(premium: Double, lotSize: Int, lots: Int = 1, regime: String, delta: Double): Double {
        require(delta != 0.0) { "delta must be non-zero to express cost in index points" }
        val rt = roundTrip(premium, lotSize, lots, regime)
        return rt.total / (lotSize * lots * kotlin.math.abs(delta))
    }

    /**
     * SELL an option and hold it to cash settlement: one order, sell-side STT,
     * one-sided exchange/SEBI, GST, half a spread. No stamp, no exercise STT.
     */
    fun sellToSettle(premium: Double, lotSize: Int, lots: Int = 1, regime: String): Charges {
        check(regime)
        val qty = lotSize * lots
        val notional = premium * qty
        val brokerage = BROKERAGE_PER_ORDER
        val stt = STT_SELL_PCT * notional
        val exchange = EXCHANGE_PCT * notional
        val sebi = SEBI_PER_CRORE * notional / 1e7
        val gst = GST_PCT * (brokerage + exchange + sebi)
        val spread = spreadPerUnit(premium, regime) / 2.0 * qty
        return Charges(brokerage, stt, exchange, sebi, 0.0, gst, spread, notional)
    }

    /**
     * BUY an option and hold it to settlement: one order, stamp duty, exercise
     * STT at 0.125% of intrinsic when it finishes in the money, half a spread.
     */
    fun buyToSettle(premium: Double, lotSize: Int, lots: Int = 1, regime: String, intrinsic: Double = 0.0): Charges {
        check(regime)
        val qty = lotSize * lots
        val notional = premium * qty
        val brokerage = BROKERAGE_PER_ORDER
        val stt = STT_EXERCISE_PCT * max(intrinsic, 0.0) * qty
        val exchange = EXCHANGE_PCT * notional
        val sebi = SEBI_PER_CRORE * notional / 1e7
        val stamp = STAMP_BUY_PCT * notional
        val gst = GST_PCT * (brokerage + exchange + sebi)
        val spread = spreadPerUnit(premium, regime) / 2.0 * qty
        return Charges(brokerage, stt, exchange, sebi, stamp, gst, spread, notional)
    }
}
