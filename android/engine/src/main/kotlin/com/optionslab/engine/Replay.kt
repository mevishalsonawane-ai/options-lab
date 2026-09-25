package com.optionslab.engine

import kotlin.math.abs
import kotlin.math.sign

/**
 * The replay page's pretend account: one instrument, bought and sold at the
 * current candle's close, marked to market as the candles step forward.
 * Position is signed (short is negative); a trade through zero flips it, and
 * realised P&L is booked on the average price, as a broker does.
 */
class ReplayAccount {
    data class Fill(val minute: Int, val side: Int, val qty: Int, val price: Double, val realised: Double)

    var position = 0; private set
    var average = 0.0; private set
    var realised = 0.0; private set
    var charges = 0.0; private set
    val fills = ArrayList<Fill>()

    /** [side] +1 buy, -1 sell; [cost] is this fill's charges. */
    fun trade(minute: Int, side: Int, qty: Int, price: Double, cost: Double = 0.0) {
        require(qty > 0 && (side == 1 || side == -1) && price > 0)
        var left = qty
        var booked = 0.0
        if (position != 0 && position.sign != side) {
            val closing = minOf(left, abs(position))
            booked = (price - average) * closing * position.sign
            position += side * closing
            left -= closing
            if (position == 0) average = 0.0
        }
        if (left > 0) {
            val newPos = position + side * left
            average = (average * abs(position) + price * left) / abs(newPos)
            position = newPos
        }
        realised += booked
        charges += cost
        fills += Fill(minute, side, qty, price, booked)
    }

    fun unrealised(price: Double): Double = if (position == 0) 0.0 else (price - average) * position
    fun net(price: Double): Double = realised + unrealised(price) - charges

    fun reset() { position = 0; average = 0.0; realised = 0.0; charges = 0.0; fills.clear() }
}
