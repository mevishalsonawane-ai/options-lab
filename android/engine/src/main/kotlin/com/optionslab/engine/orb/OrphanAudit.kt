package com.optionslab.engine.orb

import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The safety net for unwatched positions (16 Oct 2026, Boss: an R51 Donchian 20 re-entry on NIFTY19OCT2623050CE was held
 * past its +30% target and its 30-minute exit, with nothing selling it). CLAUDE.md: open positions come first; a position an
 * automatic arm opened is never left without its stop, target and time exit.
 *
 * Every pass, each long paper position is matched to the order that opened it (the newest buy that took it from flat) and
 * that order's owner (the label the arm tagged it with). It is an ORPHAN when an automatic arm opened it and nothing watches
 * it: no arm's open record holds the symbol, and no exit order (a resting stop or target, or a protection) is working on it.
 * An orphan is acted on only once it has been seen twice, [CONFIRM_MS] apart ([Pending]), so an entry still being booked
 * by its arm is never taken for one.
 *
 * Pure: no clock, no network, no storage, no orders.
 */
object OrphanAudit {
    /** Seen twice this far apart before it is acted on. */
    const val CONFIRM_MS = 20_000L

    /** The net's own levels when the arm's exits cannot be used: -15% stop and +30% target on the fill (R51's own). */
    const val NET_STOP = 0.15
    const val NET_TARGET = 0.30

    /** One fill on the symbol (oldest first): [orderId], buy or sell, quantity, price and time. */
    data class Fill(val orderId: String, val buy: Boolean, val qty: Int, val price: Double, val at: LocalDateTime)

    /** A position held now: its symbol, the quantity (long > 0), the account's average and its fills today, oldest first. */
    data class Held(val symbol: String, val qty: Int, val average: Double, val fills: List<Fill>)

    /**
     * What opened [Held]: the opening order ([orderId], null when no fill today explains it), its owner's label (null: none
     * kept), the price paid for what is held (the opening fills' average, else the account's) and when ([at], null: unknown).
     */
    data class Opened(val orderId: String?, val owner: String?, val fill: Double, val at: LocalDateTime?)

    /** An unwatched position an automatic arm opened. [known]: its fill time is known (the arm's own exits can be used). */
    data class Orphan(val symbol: String, val qty: Int, val owner: String, val orderId: String?, val fill: Double, val at: LocalDateTime?) {
        val known: Boolean get() = at != null
        /** The key it is remembered by (one position: a new trade on the symbol is a new key). */
        val key: String get() = "$symbol|${orderId ?: "?"}|$qty"
    }

    /**
     * What opened the long [h]: the fills are walked oldest first; the buy that took it from flat (or short) opens the trade, and
     * the buys after it (until the next flat) add to it. The price is their quantity-weighted average. [ownerOf] names an order.
     */
    fun opened(h: Held, ownerOf: (String) -> String?): Opened {
        var running = 0
        var opener: Fill? = null
        var cost = 0.0
        var bought = 0
        for (f in h.fills) {
            if (f.qty <= 0) continue
            val before = running
            running += if (f.buy) f.qty else -f.qty
            if (f.buy && before <= 0 && running > 0) { opener = f; cost = 0.0; bought = 0 }
            if (f.buy && running > 0) { cost += f.price * f.qty; bought += f.qty }
            if (running <= 0) { opener = null; cost = 0.0; bought = 0 }
        }
        // The fills today do not explain all of what is held (a carry from an earlier session): unknown.
        if (opener == null || running != h.qty) return Opened(null, null, h.average, null)
        val avg = if (bought > 0 && cost > 0) cost / bought else h.average
        return Opened(opener.orderId, ownerOf(opener.orderId), avg, opener.at)
    }

    /**
     * The orphans among [held]: long, opened by an order whose owner [automatic] names (its arm's label), and watched by
     * nothing ([watched]: the symbols an arm's open record holds or an exit order works on).
     */
    fun find(held: List<Held>, watched: Set<String>, ownerOf: (String) -> String?, automatic: (String) -> Boolean): List<Orphan> =
        held.filter { it.qty > 0 && it.symbol !in watched }.mapNotNull { h ->
            val o = opened(h, ownerOf)
            val owner = o.owner?.takeIf(automatic) ?: return@mapNotNull null
            Orphan(h.symbol, h.qty, owner, o.orderId, o.fill, o.at)
        }

    /** The arm's exits for an adopted position whose fill time is unknown: its stop and target, no time exit (the square-off). */
    fun unknownTime(x: PassedArms.PremiumExits): PassedArms.PremiumExits = x.copy(tmaxMinutes = null)

    /** The net's own stop for a long filled at [fill]: -[NET_STOP], down to the 0.05 tick. */
    fun netStop(fill: Double): Double = Math.round(floor(fill * (1 - NET_STOP) / 0.05 + 1e-6) * 0.05 * 100) / 100.0

    /** The net's own target for a long filled at [fill]: +[NET_TARGET], up to the 0.05 tick. */
    fun netTarget(fill: Double): Double = Math.round(ceil(fill * (1 + NET_TARGET) / 0.05 - 1e-6) * 0.05 * 100) / 100.0

    /**
     * Two sightings before acting: [confirm] returns the orphans also seen at least [CONFIRM_MS] earlier (and still orphans
     * now). One no longer seen is forgotten; [done] marks one acted on, so it is not acted on twice.
     */
    class Pending(private val confirmMs: Long = CONFIRM_MS) {
        private val first = HashMap<String, Long>()
        private val acted = HashSet<String>()

        @Synchronized fun confirm(found: List<Orphan>, nowMs: Long): List<Orphan> {
            val keys = found.map { it.key }.toSet()
            first.keys.retainAll(keys)
            acted.retainAll(keys)
            return found.filter { o ->
                val t = first.getOrPut(o.key) { nowMs }
                o.key !in acted && nowMs - t >= confirmMs
            }
        }

        @Synchronized fun done(o: Orphan) { acted += o.key }

        @Synchronized fun clear() { first.clear(); acted.clear() }
    }
}
