package com.optionslab.engine.mcx

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Zerodha's charges on one executed MCX order (zerodha.com/charges, Commodity tab, read 8 Oct 2026; research/MCX_GUIDE.md
 * 1e), on the leg's turnover (price x units: the premium for an option):
 *
 * | item           | futures                               | options                     |
 * |----------------|---------------------------------------|-----------------------------|
 * | brokerage      | 0.03% or Rs 20 an order, the lower    | Rs 20 an order              |
 * | CTT            | 0.01% on the sell side (non-agri)     | 0.05% of premium, sell side |
 * | MCX txn charge | 0.0021% of turnover                   | 0.0418% of premium          |
 * | SEBI fee       | Rs 10 a crore                         | Rs 10 a crore               |
 * | stamp duty     | 0.002% on the buy side                | 0.003% on the buy side      |
 * | GST            | 18% on brokerage + exchange + SEBI    | same                        |
 *
 * A 1-lot CRUDEOIL futures round trip at Rs 8,552 (Rs 8,55,200 a side) comes to Rs 194; a CRUDEOIL ATM option round trip on
 * Rs 24,200 of premium to Rs 84 - the guide's figures. Pure.
 */
object McxCosts {
    const val BROKERAGE_CAP = 20.0
    const val FUT_BROKERAGE_RATE = 0.0003
    const val FUT_CTT = 0.0001
    const val OPT_CTT = 0.0005
    const val FUT_TXN = 0.000021
    const val OPT_TXN = 0.000418
    const val SEBI_PER_RUPEE = 10.0 / 1_00_00_000
    const val FUT_STAMP = 0.00002
    const val OPT_STAMP = 0.00003
    const val GST = 0.18

    /** One leg's charges line by line: brokerage, CTT, exchange, SEBI, stamp, GST. Empty for a leg with no value. */
    fun breakdown(action: String, turnover: Double, option: Boolean): Map<String, Double> {
        if (!(turnover > 0) || !turnover.isFinite()) return emptyMap()
        val buy = action.uppercase() == "BUY"
        val brokerage = if (option) BROKERAGE_CAP else minOf(BROKERAGE_CAP, turnover * FUT_BROKERAGE_RATE)
        val ctt = if (buy) 0.0 else turnover * (if (option) OPT_CTT else FUT_CTT)
        val txn = turnover * (if (option) OPT_TXN else FUT_TXN)
        val sebi = turnover * SEBI_PER_RUPEE
        val stamp = if (buy) turnover * (if (option) OPT_STAMP else FUT_STAMP) else 0.0
        val gst = (brokerage + txn + sebi) * GST
        return linkedMapOf("Brokerage" to brokerage, "CTT" to ctt, "Exchange" to txn, "SEBI" to sebi, "Stamp duty" to stamp, "GST" to gst)
    }

    /** The leg's total in rupees, rounded half-even to paise. */
    fun charge(action: String, turnover: Double, option: Boolean): BigDecimal =
        BigDecimal(breakdown(action, turnover, option).values.sum()).setScale(2, RoundingMode.HALF_EVEN)

    /** A round trip (a buy and a sell at the same price) of [units] at [price]: what a screen quotes as "costs about". */
    fun roundTrip(price: Double, units: Int, option: Boolean): Double {
        val t = price * kotlin.math.abs(units)
        return charge("BUY", t, option).toDouble() + charge("SELL", t, option).toDouble()
    }
}
