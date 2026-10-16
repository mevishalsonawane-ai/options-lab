package com.optionslab.ira

import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * An open position's own move, and the exits its arm is holding it to (16 Oct 2026, Boss on paper: the popup of
 * NIFTY19OCT2623050CE said "Change +48.57%" and Boss read it as "+50% profit, still not selling". The open trade was only
 * +18.4% on its 122.20 average; the 48.57% was the whole day's P&L on the symbol - an earlier closed trade's +2,399 plus the
 * open +1,459 - over the open position's cost, the sandbox's Zerodha-style row figure. R51 Donchian's +30% target was
 * 158.86, so not selling was right; nothing said so.)
 *
 * [pct] is the open trade alone: (last - average) / average for a long, mirrored for a short. A closed position, a zero
 * average or no price yet has none. [exits] words an arm's stop, target and exit time as one line. Pure.
 */
object OpenChange {
    /** The open trade's move on its average price, in percent; null for a closed position, a zero average or no price. */
    fun pct(qty: Int, avg: Double, ltp: Double): Double? {
        if (qty == 0 || !avg.isFinite() || avg <= 0 || !ltp.isFinite() || ltp <= 0) return null
        return (ltp - avg) / avg * 100 * (if (qty > 0) 1 else -1)
    }

    /** [pct] as the popup shows it ("+18.37%"), or null. */
    fun line(qty: Int, avg: Double, ltp: Double): String? = pct(qty, avg, ltp)?.let { "%+.2f%%".format(Locale.ENGLISH, it) }

    /** One fill of the day on a symbol, in the order it happened: [buy] or a sale, [qty] units at [price]. */
    data class Fill(val buy: Boolean, val qty: Int, val price: Double)

    /**
     * The average price of what is still open, from the day's [fills] on one symbol and product in time order (16 Oct: on an
     * intraday re-entry Zerodha's average may count the closed round trip too). Each time the position goes flat the average
     * starts again; a fill that crosses through flat opens the rest at its own price; adding to the position averages in.
     * Null - keep the broker's average - when the position was carried in from an earlier day ([overnight] not 0), the fills
     * do not end at [qty] (not all read yet), or nothing is open.
     */
    fun openAverage(fills: List<Fill>, qty: Int, overnight: Int = 0): Double? {
        if (overnight != 0 || qty == 0) return null
        var q = 0
        var avg = 0.0
        for (f in fills) {
            if (f.qty <= 0 || !f.price.isFinite() || f.price <= 0) return null
            val d = if (f.buy) f.qty else -f.qty
            when {
                q == 0 || (q > 0) == (d > 0) -> { avg = (avg * kotlin.math.abs(q) + f.price * f.qty) / (kotlin.math.abs(q) + f.qty); q += d }
                kotlin.math.abs(d) <= kotlin.math.abs(q) -> { q += d; if (q == 0) avg = 0.0 }
                else -> { q += d; avg = f.price }
            }
        }
        return if (q == qty && avg > 0) avg else null
    }

    private fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x)

    /**
     * "stop 103.87 · target 158.86 · time 10:45" from the parts known (a price that is not a positive number is left out),
     * plus [note] (e.g. "half at 5x, the rest at 20x"); null when nothing is known.
     */
    fun exits(stop: Double?, target: Double?, at: LocalTime?, note: String? = null): String? {
        val parts = listOfNotNull(
            stop?.takeIf { it.isFinite() && it > 0 }?.let { "stop ${px(it)}" },
            target?.takeIf { it.isFinite() && it > 0 }?.let { "target ${px(it)}" },
            at?.let { "time %02d:%02d".format(Locale.ENGLISH, it.hour, it.minute) },
            note?.takeIf { it.isNotBlank() },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}

/**
 * Each arm's open trades and the exits it is holding them to now, as the arm last published its own book (memory only: a
 * map written by the arms' book publish, read by the position popup; no disk, no network, nothing on the order or tick
 * path). Each arm ([Entry.owner]) replaces its whole list at once, so a trade it no longer holds drops out. Shown only
 * beside an open position on the same account and symbol.
 */
object ArmExits {
    data class Entry(val owner: String, val label: String, val live: Boolean, val symbol: String,
                     val stop: Double?, val target: Double?, val at: LocalTime?, val note: String? = null)

    private val byOwner = ConcurrentHashMap<String, List<Entry>>()

    /** [owner]'s open trades now (an empty list: none). */
    fun set(owner: String, entries: List<Entry>) {
        if (entries.isEmpty()) byOwner.remove(owner) else byOwner[owner] = entries.toList()
    }

    /** The open trades on [symbol] in the live (Zerodha) or paper account, any arm. */
    fun of(live: Boolean, symbol: String): List<Entry> =
        byOwner.values.flatten().filter { it.live == live && it.symbol == symbol }.sortedBy { it.label }

    /** The popup's "Exits" line for [symbol]: each arm's own (named when two hold it), or null when no arm holds it. */
    fun line(live: Boolean, symbol: String): String? {
        val all = of(live, symbol)
        val words = all.mapNotNull { e -> OpenChange.exits(e.stop, e.target, e.at, e.note)?.let { e.label to it } }
        if (words.isEmpty()) return null
        return if (words.size == 1) words.single().second else words.joinToString("; ") { (l, w) -> "$l: $w" }
    }

    /** TEST ONLY: forget every arm's list. */
    fun clear() = byOwner.clear()
}
