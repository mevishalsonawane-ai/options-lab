package com.optionslab.engine.risk

import kotlin.math.abs

/**
 * How much an automatic exit may send. Pure arithmetic; the app reads the
 * position and order books and places the orders.
 *
 * The rule every exit keeps: never more than is held, minus the exits already
 * working on it (anyone's: a protection's stop, another bot's stop, the
 * owner's own limit sell), in whole lots. Two exits that both fill must not
 * turn a long into a short.
 */
object ExitQty {
    /** What an order still has working: Kite's pending quantity when it gives one, else ordered minus filled. */
    fun remaining(qty: Int, filled: Int, pending: Int = 0): Int = (if (pending > 0) pending else qty - filled).coerceAtLeast(0)

    /**
     * The quantity to send now: at most [want], at most |[held]| minus [working], rounded down to whole
     * [lot]s. 0 means send nothing (what is held is already covered, or nothing is held).
     */
    fun sendable(held: Int, working: Int, want: Int, lot: Int): Int {
        val room = minOf(abs(held) - working.coerceAtLeast(0), want)
        if (room <= 0) return 0
        val l = lot.coerceAtLeast(1)
        return room / l * l
    }

    /**
     * A resting exit of [orderQty] with [filled] done, on a position now |[held]|: the order quantity it
     * should be modified to (what already filled plus what is still held, in whole lots), or null when
     * what it still has working does not exceed what is held.
     */
    fun shrinkTo(orderQty: Int, filled: Int, held: Int, lot: Int): Int? {
        val working = (orderQty - filled).coerceAtLeast(0)
        val h = abs(held)
        if (working <= h) return null
        val l = lot.coerceAtLeast(1)
        return filled.coerceAtLeast(0) + h / l * l
    }
}
