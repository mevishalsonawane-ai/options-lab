package com.optionslab.engine

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.min

/**
 * Fills paired into round trips: first in, first out, per symbol. A closing
 * fill closes the oldest open lots first; one closing fill makes one trip,
 * whatever number of opening fills it consumed. Charges ride along: the
 * closing fill's own plus the consumed share of each opening fill's.
 *
 * The journal, the strategy comparison and the per-strategy calendar all read
 * these, so a trip carries the ids of every fill (and order) that made it.
 */
object RoundTrips {
    data class Fill(
        val id: String, val orderId: String, val symbol: String,
        /** +1 buy, -1 sell. */
        val side: Int, val qty: Int, val price: Double, val at: LocalDateTime, val charges: Double = 0.0,
    )

    data class Trip(
        val symbol: String,
        /** +1 long (bought first), -1 short. */
        val direction: Int,
        val qty: Int,
        val entry: Double,
        val exit: Double,
        val openedAt: LocalDateTime,
        val closedAt: LocalDateTime,
        val openFillIds: List<String>,
        val openOrderIds: List<String>,
        val closeFillId: String,
        val closeOrderId: String,
        val charges: Double,
    ) {
        val gross: Double get() = (exit - entry) * qty * direction
        val net: Double get() = gross - charges
        val day: LocalDate get() = closedAt.toLocalDate()
        val fillIds: List<String> get() = openFillIds + closeFillId
        val orderIds: List<String> get() = (openOrderIds + closeOrderId).distinct()
    }

    private class Lot(val fill: Fill, var left: Int)

    fun of(fills: List<Fill>): List<Trip> {
        val open = HashMap<String, ArrayDeque<Lot>>()
        val out = ArrayList<Trip>()
        for (f in fills.sortedWith(compareBy({ it.at }, { it.id }))) {
            if (f.qty <= 0) continue
            val lots = open.getOrPut(f.symbol) { ArrayDeque() }
            val held = lots.firstOrNull()?.fill?.side
            if (held == null || held == f.side) { lots.addLast(Lot(f, f.qty)); continue }
            // Closing: consume the oldest lots.
            var need = f.qty
            var cost = 0.0; var taken = 0; var charges = 0.0
            val ids = ArrayList<String>(); val orders = ArrayList<String>()
            var openedAt: LocalDateTime? = null
            while (need > 0 && lots.isNotEmpty()) {
                val lot = lots.first()
                val q = min(need, lot.left)
                cost += lot.fill.price * q; taken += q
                charges += lot.fill.charges * q / lot.fill.qty
                ids += lot.fill.id; orders += lot.fill.orderId
                if (openedAt == null) openedAt = lot.fill.at
                lot.left -= q; need -= q
                if (lot.left == 0) lots.removeFirst()
            }
            if (taken > 0) {
                val closingShare = f.charges * taken / f.qty
                out += Trip(f.symbol, held, taken, cost / taken, f.price, openedAt!!, f.at, ids.distinct(), orders.distinct(), f.id, f.orderId,
                    charges + closingShare)
            }
            // What is left of the fill beyond the position opens the other way.
            if (need > 0) lots.addLast(Lot(f.copy(qty = need, charges = f.charges * need / f.qty), need))
        }
        return out
    }

    /** Summary statistics over trips (net of charges): the strategy comparison's columns. */
    data class Stats(
        val trips: Int, val wins: Int, val net: Double, val grossProfit: Double, val grossLoss: Double,
        val best: Double, val worst: Double, val maxDrawdown: Double, val charges: Double,
    ) {
        val winRate: Double get() = if (trips > 0) wins.toDouble() / trips else 0.0
        val average: Double get() = if (trips > 0) net / trips else 0.0
        val profitFactor: Double? get() = if (grossLoss > 0) grossProfit / grossLoss else null
    }

    fun stats(trips: List<Trip>): Stats {
        var peak = 0.0; var cum = 0.0; var dd = 0.0
        trips.sortedBy { it.closedAt }.forEach { cum += it.net; peak = maxOf(peak, cum); dd = minOf(dd, cum - peak) }
        val nets = trips.map { it.net }
        return Stats(trips.size, nets.count { it > 0 }, nets.sum(), nets.filter { it > 0 }.sum(), abs(nets.filter { it < 0 }.sum()),
            nets.maxOrNull() ?: 0.0, nets.minOrNull() ?: 0.0, dd, trips.sumOf { it.charges })
    }
}
