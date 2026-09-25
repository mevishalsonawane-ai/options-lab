package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What a paper fill really costs, as the desktop sandbox models it
 * (sandbox/charges.py and sandbox/slippage.py; strategies/orb/execution_config.json).
 *
 * Slippage is always ADVERSE: a BUY pays more, a SELL receives less. Only the
 * two optimistic paths slip: stop fills (SL, SL-M) and a MARKET fill that fell
 * back to the LTP because the quote had no bid/ask. LIMIT fills and MARKET fills
 * that crossed a real book are exact. An SL is then clamped to its limit.
 *
 * Charges are debited per leg at fill time (F&O schedule):
 * Rs 20 brokerage, STT 0.15% on sells, exchange 0.03553% and SEBI Rs 10/crore on
 * turnover, stamp 0.003% on buys, GST 18% on brokerage + exchange + SEBI.
 */
object SandboxCosts {
    private val BPS = BigDecimal(10_000)
    private val CENT = BigDecimal("0.01")

    fun adverse(price: BigDecimal, action: String, bps: BigDecimal): BigDecimal {
        if (bps.signum() <= 0 || price.signum() <= 0) return price
        val delta = price.multiply(bps).divide(BPS)
        val slipped = if (action.uppercase() == "BUY") price + delta else price - delta
        if (slipped.signum() <= 0) return CENT
        return slipped.setScale(2, RoundingMode.HALF_EVEN)
    }

    fun clampToLimit(price: BigDecimal, action: String, limit: BigDecimal?): BigDecimal {
        if (limit == null || limit.signum() <= 0) return price
        return if (action.uppercase() == "BUY") price.min(limit) else price.max(limit)
    }

    fun fillPrice(price: BigDecimal, action: String, priceType: String, usedQuoteBook: Boolean, limit: BigDecimal?, config: SandboxConfig): BigDecimal =
        when (priceType.uppercase()) {
            "SL" -> clampToLimit(adverse(price, action, config.stopSlippageBps), action, limit)
            "SL-M" -> adverse(price, action, config.stopSlippageBps)
            "MARKET" -> if (usedQuoteBook) price else adverse(price, action, config.spreadFallbackBps)
            else -> price
        }

    /** Total cost of one executed F&O leg in rupees, rounded half-even to paise (Python round). */
    fun charge(action: String, price: BigDecimal, quantity: Int, contractValue: BigDecimal = BigDecimal.ONE): BigDecimal {
        val value = price.toDouble() * kotlin.math.abs(quantity) * contractValue.toDouble()
        if (!(value > 0)) return BigDecimal.ZERO.setScale(2)
        val buy = if (action.uppercase() == "BUY") value else 0.0
        val sell = if (action.uppercase() == "BUY") 0.0 else value
        val brokerage = 20.0
        val stt = sell * 0.0015
        val txn = (buy + sell) * 0.0003553
        val sebi = (buy + sell) * (10.0 / 1_00_00_000)
        val stamp = buy * 0.00003
        val gst = (brokerage + txn + sebi) * 0.18
        return BigDecimal(brokerage + stt + txn + sebi + stamp + gst).setScale(2, RoundingMode.HALF_EVEN)
    }
}
