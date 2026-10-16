package com.optionslab.engine.orb

import java.time.LocalDateTime

/**
 * 16 Oct 2026 (R72 review): R51 Donchian 20 and R72 Breadth Donchian may hold the same option at once, each its own lot on paper.
 * When the paper account holds less of it than their open trades add up to, someone else sold some of it (Boss, the loss limit,
 * a square-off): this says which trades those sales closed, at what price and with what charges, so each arm's paper P&L stays
 * its own.
 *
 * - The sales are the option's SELL fills since the oldest open trade's entry, less what earlier closes already took from each
 *   ([Sale.left]; the arms' own exits take their whole sale, so another arm's own exit is never booked as "closed elsewhere").
 * - OLDEST FIRST: the outside sales close the open trades oldest first (by entry, then book order), each trade wholly - a sale
 *   that covers only part of the oldest trade closes nothing (as before: a part-sold lot is not noticed).
 * - Each trade takes its quantity from the sales oldest first: its price is their quantity-weighted price (any part no sale
 *   explains at its entry, no profit or loss), its charges each sale's charges in proportion to what it took, so every sale's
 *   charges are booked once, over the trades it closed.
 *
 * Pure: no clock, no storage, no orders.
 */
object SharedSales {
    /** An open trade on the option: [key] the caller's, [qty], [entry] price and [at] its entry time. */
    data class Open(val key: Int, val qty: Int, val entry: Double, val at: LocalDateTime)

    /** A SELL fill on the option: [left] of its [qty] not yet booked to a trade (its [charges] are for all [qty]). */
    data class Sale(val orderId: String, val qty: Int, val left: Int, val price: Double, val charges: Double, val at: LocalDateTime)

    /** A trade closed elsewhere: its exit [price], [charges] (its share of the sales'), [at] (null: no sale explains it) and [took]. */
    data class Closed(val key: Int, val price: Double, val charges: Double, val at: LocalDateTime?, val took: Map<String, Int>)

    /**
     * The trades among [open] that [held] (the account's quantity now) says were sold elsewhere, and how, from [sales] (any
     * order; those before the oldest open entry are ignored). Empty when the account holds at least what they add up to.
     */
    fun closedElsewhere(open: List<Open>, held: Int, sales: List<Sale>): List<Closed> {
        if (open.isEmpty()) return emptyList()
        var short = open.sumOf { it.qty } - held.coerceAtLeast(0)
        if (short <= 0) return emptyList()
        val first = open.minOf { it.at }
        val queue = sales.filter { !it.at.isBefore(first) && it.left > 0 && it.qty > 0 }.sortedBy { it.at }
            .map { it to intArrayOf(it.left.coerceAtMost(it.qty)) }
        val out = ArrayList<Closed>()
        for (o in open.withIndex().sortedWith(compareBy({ it.value.at }, { it.index })).map { it.value }) {
            if (o.qty <= 0 || short < o.qty) break
            short -= o.qty
            var need = o.qty
            var value = 0.0
            var charges = 0.0
            var at: LocalDateTime? = null
            val took = LinkedHashMap<String, Int>()
            for ((s, left) in queue) {
                if (need == 0) break
                if (left[0] == 0) continue
                val q = minOf(need, left[0])
                left[0] -= q; need -= q
                value += s.price * q
                charges += s.charges * q / s.qty
                at = s.at
                took[s.orderId] = (took[s.orderId] ?: 0) + q
            }
            out += Closed(o.key, (value + o.entry * need) / o.qty, charges, at, took)
        }
        return out
    }

    /** [took] as kept in a trade's record: "orderId:qty,orderId:qty" (null: none). */
    fun write(took: Map<String, Int>): String? = took.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.entries?.joinToString(",") { "${it.key}:${it.value}" }

    /** A record's "orderId:qty,..." read back (anything unreadable is skipped). */
    fun read(s: String?): Map<String, Int> {
        if (s.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (part in s.split(',')) {
            val i = part.lastIndexOf(':')
            if (i <= 0) continue
            val q = part.substring(i + 1).toIntOrNull() ?: continue
            out[part.substring(0, i)] = (out[part.substring(0, i)] ?: 0) + q
        }
        return out
    }
}
