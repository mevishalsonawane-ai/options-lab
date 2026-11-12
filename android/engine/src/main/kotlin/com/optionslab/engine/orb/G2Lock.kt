package com.optionslab.engine.orb

import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal

/**
 * "G2 giveback lock (paper test)" (12 Nov 2026, Boss: "test G2 at 1 lot on paper as Boss's exception", after research R167;
 * research/hunt/r167_donchian_smart_exits): once an R51 / R72 trade has been +Rs 1,500 (the sale at its best price netting that
 * after the round trip's charges), a lock rests at the price that nets +Rs 500 and trails Rs 1,000 (premium x quantity) under
 * the best price. Boss's ask (12 Nov): "the profit goes to 1.5k to 2.5k and ends up to 2k-3k loss".
 *
 * It FAILED R167's gates (raise the win rate 5 points with no less profit): on Apr - Oct 2026 at 1 lot it raised the win rate
 * about 3 points on R72 (45.8% to 49.1%, 1,202 trades) and 4 on R51 (44.9% to 48.9%, 1,670) and beat random exits (p 0.002), but made
 * Rs 68,404 and Rs 59,644 less; the walk-forward year (Apr 2025 - Mar 2026) the same way. So it runs only as Boss's exception,
 * as a PAPER TEST TWIN: each real R51 / R72 paper trade is also walked with this lock added, on the same minutes, and what G2 would
 * have done is recorded on it - never bought, never copied live, no cap or record changed. The real trade, and so its live copy,
 * keeps today's exits.
 *
 * Pure: no clock, no network, no storage, no orders.
 */
object G2Lock {
    const val LABEL = "G2 giveback lock (paper test)"
    const val STATUS = "failed gates, Boss's exception"
    const val RULE = "each R51 / R72 paper trade also has a test twin: the same trade walked with a lock that, once it has been " +
        "+₹1,500 (after charges), keeps at least +₹500 and trails ₹1,000 under the best price; the twin is never bought or copied " +
        "live, and the real trade keeps today's exits"
    const val RECORD = "Research R167, Apr - Oct 2026 at 1 lot: R72 won 49.1% vs 45.8% (1,202 trades) but made ₹1,02,962 vs ₹1,71,366; " +
        "R51 won 48.9% vs 44.9% (1,670 trades) but made ₹75,099 vs ₹1,34,743; it beat random exits (p 0.002) and failed the gate " +
        "'no less profit'"

    /** +Rs 1,500 trigger, +Rs 500 kept, Rs 1,000 trail: for the whole position (paper trades here are 1 lot). */
    const val TRIGGER = 1_500.0
    const val KEEP = 500.0
    const val TRAIL = 1_000.0
    const val TICK = 0.05

    /** The round trip's charges for [qty] bought at [entry] and sold at [exit] (Zerodha's, as the paper book books them). */
    fun charges(entry: Double, exit: Double, qty: Int): Double =
        SandboxCosts.charge("BUY", BigDecimal(entry), qty).toDouble() + SandboxCosts.charge("SELL", BigDecimal(exit), qty).toDouble()

    /**
     * The premium whose sale nets at least [net] rupees on [qty] bought at [entry] after the round trip's charges, on the tick grid
     * (rounded up), as research r106engine.target_price solves it (six rounds, the charges at the price found).
     */
    fun netPrice(entry: Double, qty: Int, net: Double, charge: (Double, Double, Int) -> Double = ::charges): Double {
        var x = entry + net / qty
        repeat(6) { x = entry + (net + charge(entry, x, qty)) / qty }
        return kotlin.math.ceil(Math.round(x / TICK * 1e6) / 1e6) * TICK
    }

    /** The lock's levels for a trade of [qty] filled at [entry]; null when it cannot be told (no fill or quantity). */
    fun levels(entry: Double, qty: Int, charge: (Double, Double, Int) -> Double = ::charges): PassedArms.RupeeLock? {
        if (!(entry > 0) || qty <= 0) return null
        return PassedArms.RupeeLock(netPrice(entry, qty, TRIGGER, charge), netPrice(entry, qty, KEEP, charge), TRAIL / qty)
    }
}
