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
 * turnover, stamp 0.003% on buys, GST 18% on brokerage + exchange + SEBI. 20 Nov 2026 (audit, B5 item 4: "BSE charges are slightly
 * overstated"): a BSE leg (SENSEX options, BFO) pays BSE's exchange rate, [BSE_TXN] (0.0325% of premium), not NSE's.
 */
object SandboxCosts {
    /** NSE's F&O options exchange transaction charge on premium turnover (as before). */
    const val NSE_TXN = 0.0003553
    /** BSE's options exchange transaction charge on premium turnover (SENSEX options, BFO): 0.0325%. */
    const val BSE_TXN = 0.000325

    /** The exchange transaction rate of a leg on [exchange]: BSE's on BFO / BSE, NSE's everywhere else. */
    fun txnRate(exchange: String): Double = if (exchange.uppercase() == "BFO" || exchange.uppercase() == "BSE") BSE_TXN else NSE_TXN

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

    /**
     * One leg's charges line by line on [exchange]: an MCX leg on the commodity schedule ([com.optionslab.engine.mcx.McxCosts]:
     * CTT for STT; [symbol] ending CE/PE is an option, else a future), every other exchange exactly as [breakdown].
     */
    fun breakdown(action: String, price: Double, quantity: Int, exchange: String, symbol: String): Map<String, Double> =
        if (exchange.uppercase() == "MCX") com.optionslab.engine.mcx.McxCosts.breakdown(action, price * kotlin.math.abs(quantity), isOptionSymbol(symbol))
        else breakdown(action, price, quantity, txnRate(exchange))

    /** [charge] on [exchange]: an MCX leg on the commodity schedule, every other exchange exactly as [charge]. */
    fun charge(action: String, price: BigDecimal, quantity: Int, contractValue: BigDecimal, exchange: String, symbol: String): BigDecimal =
        if (exchange.uppercase() == "MCX") com.optionslab.engine.mcx.McxCosts.charge(action,
            price.toDouble() * kotlin.math.abs(quantity) * contractValue.toDouble(), isOptionSymbol(symbol))
        else charge(action, price, quantity, contractValue, txnRate(exchange))

    private fun isOptionSymbol(symbol: String): Boolean = symbol.uppercase().let { it.endsWith("CE") || it.endsWith("PE") }

    /** One leg's charges line by line (the charges report): brokerage, STT, exchange ([txn] of turnover), SEBI, stamp, GST. */
    fun breakdown(action: String, price: Double, quantity: Int, txn: Double = NSE_TXN): Map<String, Double> {
        val value = price * kotlin.math.abs(quantity)
        if (!(value > 0)) return emptyMap()
        val buy = if (action.uppercase() == "BUY") value else 0.0
        val sell = if (action.uppercase() == "BUY") 0.0 else value
        val exch = (buy + sell) * txn
        val sebi = (buy + sell) * (10.0 / 1_00_00_000)
        return linkedMapOf("Brokerage" to 20.0, "STT" to sell * 0.0015, "Exchange" to exch, "SEBI" to sebi,
            "Stamp duty" to buy * 0.00003, "GST" to (20.0 + exch + sebi) * 0.18)
    }

    /** Total cost of one executed F&O leg in rupees ([txn]: its exchange's rate), rounded half-even to paise (Python round). */
    fun charge(action: String, price: BigDecimal, quantity: Int, contractValue: BigDecimal = BigDecimal.ONE, txn: Double = NSE_TXN): BigDecimal {
        val value = price.toDouble() * kotlin.math.abs(quantity) * contractValue.toDouble()
        if (!(value > 0)) return BigDecimal.ZERO.setScale(2)
        val buy = if (action.uppercase() == "BUY") value else 0.0
        val sell = if (action.uppercase() == "BUY") 0.0 else value
        val brokerage = 20.0
        val stt = sell * 0.0015
        val exch = (buy + sell) * txn
        val sebi = (buy + sell) * (10.0 / 1_00_00_000)
        val stamp = buy * 0.00003
        val gst = (brokerage + exch + sebi) * 0.18
        return BigDecimal(brokerage + stt + exch + sebi + stamp + gst).setScale(2, RoundingMode.HALF_EVEN)
    }
}
