package com.iraalgo.app.rules
import java.math.BigDecimal
import java.math.RoundingMode

object Costs {
    fun charge(action: String, price: Double, qty: Int): Double {
        val value = price * qty
        if (value <= 0) return 0.0
        val buy = if (action == "BUY") value else 0.0
        val sell = if (action == "BUY") 0.0 else value
        val brokerage = 20.0
        val stt = sell * 0.0015
        val txn = (buy + sell) * 0.0003553
        val sebi = (buy + sell) * (10.0 / 1_00_00_000)
        val stamp = buy * 0.00003
        val gst = (brokerage + txn + sebi) * 0.18
        val total = brokerage + stt + txn + sebi + stamp + gst
        return BigDecimal(total).setScale(2, RoundingMode.HALF_EVEN).toDouble()   // Python round(float, 2)
    }

    fun fillPrice(price: Double, action: String, priceType: String, usedQuoteBook: Boolean,
                  stopBps: Double = 10.0, spreadBps: Double = 5.0): Double = when {
        priceType == "SL-M" || priceType == "SL" -> adverse(price, action, stopBps)
        priceType == "MARKET" && !usedQuoteBook -> adverse(price, action, spreadBps)
        else -> price
    }

    private fun adverse(price: Double, action: String, bps: Double): Double {
        if (bps <= 0 || price <= 0) return price
        val p = BigDecimal(price.toString())
        val delta = p.multiply(BigDecimal(bps.toString())).divide(BigDecimal(10_000))
        val slipped = if (action == "BUY") p + delta else p - delta
        if (slipped.signum() <= 0) return 0.01
        return slipped.setScale(2, RoundingMode.HALF_EVEN).toDouble()        // Decimal.quantize default
    }
}
