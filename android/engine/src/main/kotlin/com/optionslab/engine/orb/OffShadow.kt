package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The SWITCHED-OFF SHADOW (30 Oct 2026, Boss: "If i switch any arm off, it should be always run in shadow and record only, so i can
 * continuously observe it").
 *
 * An arm Boss switched OFF never sends an order: no paper position, no live order. It keeps deciding its signals every day, in Paper
 * or Live, and each would-be trade is written here instead: the entry as Zerodha would have filled it (the ask, with the app's
 * measured slippage: [LiveMirror.shadowFill]), the exit by the arm's own stop, target, lock, trail, index stop or time exit, priced
 * the same way (a stop at its resting SL's fill: [LiveMirror.shadowStopFill]), and the charges. Labelled [LABEL] wherever shown.
 *
 * A shadow record never touches the paper book's cash or positions and is never mirrored live: it has no order id and no path to the
 * order door. These rules are pure (no clock, no network, no orders); the app's OffShadow keeps the book and runs them.
 */
object OffShadow {
    private const val EPS = 1e-9

    /** The words beside every switched-off shadow record. */
    const val LABEL = "shadow (switched off)"

    /** What the rows say about an arm that is off. */
    const val OFF_WORDS = "off - runs in shadow (would-be trades recorded, nothing bought)"

    /** The NSE intraday square-off the shadow uses (the live mirror's: 15:15), and 15:05 on the contract's expiry day. */
    val SQUARE_OFF: LocalTime = LiveMirror.SQUARE_OFF
    val EXPIRY_SQUARE_OFF: LocalTime = LiveMirror.EXPIRY_SQUARE_OFF

    /** MCX would-be trades with no time exit of their own go by 23:20 (before MCX's close in every season). */
    val MCX_SQUARE_OFF: LocalTime = LocalTime.of(23, 20)

    /** At most this many would-be trades of one arm a day (a runaway signal never floods the book). */
    const val PER_ARM_DAY = 20

    /** Open would-be trades kept at most, over all arms. */
    const val OPEN_MAX = 60

    /**
     * The arm's own exits, as it would have run them: [stop] / [target] on the premium; [indexStop] / [indexTarget] on the underlying
     * (a call's stop below, a put's above: [Pos.view]); [lockAfterRs] once the trade's net is up this many rupees, out when the net
     * falls to [lockKeep] of its best; [trailPct] a stop trailed this share under the best premium; [exitAt] its own time exit that
     * day (the square-off backstops still apply); [carry] a carried (NRML) trade held overnight until [exitOn] at [exitAt].
     */
    data class Exits(
        val stop: Double? = null, val target: Double? = null, val indexStop: Double? = null, val indexTarget: Double? = null,
        val lockAfterRs: Double? = null, val lockKeep: Double = 0.6, val trailPct: Double? = null, val exitAt: LocalTime? = null,
        val carry: Boolean = false, val exitOn: LocalDate? = null,
    )

    /**
     * One would-be trade. [key] the arm's switch key; [ref] unique; [side] +1 a bought option or future, -1 a sold one; [view] +1 the
     * signal is bullish on the underlying (a call, a long future), -1 bearish (a put); [qty] in units ([multiplier] per unit: MCX's
     * lot, 1 elsewhere); [stopNow] the stop as trailed or locked so far; [best] the best price seen; [charges] both legs once closed
     * (the entry leg's while open).
     */
    data class Pos(
        val key: String, val label: String, val ref: String, val underlying: String, val symbol: String, val exchange: String,
        val side: Int, val view: Int, val qty: Int, val lots: Int, val multiplier: Int, val tick: Double, val entry: Double,
        val entryTime: LocalDateTime, val expiry: LocalDate?, val exits: Exits, val stopNow: Double? = exits.stop,
        val best: Double = entry, val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null,
        val charges: Double = 0.0, val entryWhy: String = "",
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        /** Rupees after charges, once closed. */
        val net: Double? get() = exit?.let { netAt(it, charges) }
        /** Rupees at [price] with [cost] taken off. */
        fun netAt(price: Double, cost: Double): Double = (price - entry) * qty * multiplier.coerceAtLeast(1) * side - cost
    }

    /** What one look saw: the premium now ([ltp]) and its extreme since the last look ([extreme]: a long's low), the underlying's. */
    data class Look(
        val now: LocalDateTime, val ltp: Double?, val extreme: Double? = null, val indexLtp: Double? = null, val indexExtreme: Double? = null,
        /** The best premium since the last look (a long's high), for the trail and the lock. */
        val bestSince: Double? = null,
        /** The exit leg's charges at [ltp] (the app's schedule); 0 when unknown. */
        val exitCost: Double = 0.0,
        /** False: not a session (a holiday, a weekend) - nothing is judged on a stale price. */
        val session: Boolean = true,
    )

    /** How a would-be trade ends this look: at a stop ([stop] true: its resting SL), or a market exit at the quote, with [why]. */
    data class Out(val why: String, val stop: Boolean, val trigger: Double? = null)

    /** Whether [t] is past the trade's square-off: NSE intraday 15:15 (15:05 on its expiry day), MCX 23:20, a carried one its own. */
    fun squareOffDue(p: Pos, t: LocalDateTime): Boolean {
        val at = t.toLocalTime()
        val d = t.toLocalDate()
        val x = p.exits
        if (x.carry) {
            val on = x.exitOn ?: return p.expiry == d && !at.isBefore(EXPIRY_SQUARE_OFF)
            if (d.isAfter(on)) return true
            if (d == on && !at.isBefore(x.exitAt ?: LocalTime.of(9, 16))) return true
            return p.expiry == d && !at.isBefore(EXPIRY_SQUARE_OFF)
        }
        if (d.isAfter(p.day)) return true
        if (p.exchange == "MCX") return !at.isBefore(x.exitAt?.takeIf { it.isBefore(MCX_SQUARE_OFF) } ?: MCX_SQUARE_OFF)
        if (x.exitAt != null && !at.isBefore(x.exitAt)) return true
        if (!at.isBefore(SQUARE_OFF)) return true
        return p.expiry == d && !at.isBefore(EXPIRY_SQUARE_OFF)
    }

    /** The premium stop after this look's best ([trailPct]) and lock: never loosened. */
    fun stopAfter(p: Pos, best: Double): Double? {
        val x = p.exits
        val trail = x.trailPct?.takeIf { it > 0 && it < 1 }?.let { if (p.side >= 0) best * (1 - it) else best * (1 + it) }
        val all = listOfNotNull(p.stopNow, trail)
        return if (p.side >= 0) all.maxOrNull() else all.minOrNull()
    }

    /** The best price including [seen] (a long's highest, a short's lowest). */
    fun bestAfter(p: Pos, seen: Double?): Double {
        val s = seen?.takeIf { it.isFinite() && it > 0 } ?: return p.best
        return if (p.side >= 0) maxOf(p.best, s) else minOf(p.best, s)
    }

    /**
     * The look's verdict, in order: no price and no session - nothing; the stop (premium, judged on the extreme too); the index stop;
     * the target; the profit lock; the time exit and square-off; else held. Null: held.
     */
    fun decide(p: Pos, l: Look): Out? {
        if (!p.open) return null
        // Off session (after the close, a holiday): nothing is judged on a stale price - except a square-off already due on the trade's
        // own day, booked at that day's last price (review: before, a look missed between the square-off and the close left it open
        // overnight and booked it at the next session's price).
        if (!l.session) return if (l.ltp != null && l.now.toLocalDate() == p.day && !p.exits.carry && squareOffDue(p, l.now)) Out("square_off", false) else null
        val stop = p.stopNow
        if (stop != null && LiveMirror.shadowStopReached(l.ltp, l.extreme, stop, p.side)) return Out("stop", true, stop)
        val ix = p.exits.indexStop
        if (ix != null && l.ltp != null) {
            val seen = listOfNotNull(l.indexLtp, l.indexExtreme)
            if (seen.any { if (p.view >= 0) it <= ix + EPS else it >= ix - EPS }) return Out("index_stop", false)
        }
        // No price: nothing is booked (never at the price paid); the next look with a price decides.
        val ltp = l.ltp ?: return null
        p.exits.target?.let { tg -> if (if (p.side >= 0) ltp >= tg - EPS else ltp <= tg + EPS) return Out("target", false) }
        val it = p.exits.indexTarget
        if (it != null && l.indexLtp != null && (if (p.view >= 0) l.indexLtp >= it - EPS else l.indexLtp <= it + EPS)) return Out("index_target", false)
        val lockAt = p.exits.lockAfterRs
        if (lockAt != null && lockAt > 0) {
            val bestNet = p.netAt(bestAfter(p, l.bestSince ?: ltp), p.charges + l.exitCost)
            val now = p.netAt(ltp, p.charges + l.exitCost)
            if (bestNet >= lockAt - EPS && now <= bestNet * p.exits.lockKeep.coerceIn(0.0, 1.0) + EPS) return Out("profit_lock", false)
        }
        if (squareOffDue(p, l.now)) {
            val own = p.exits.exitAt?.let { at -> !p.exits.carry && l.now.toLocalDate() == p.day && !l.now.toLocalTime().isBefore(at) } == true
            return Out(if (own) "time_exit" else "square_off", false)
        }
        return null
    }

    /** Why a new would-be trade is not written (the arm's runaway guard), or null. */
    fun refuse(key: String, day: LocalDate, all: List<Pos>): String? = when {
        all.count { it.open } >= OPEN_MAX -> "the shadow book holds $OPEN_MAX open would-be trades already"
        all.count { it.key == key && it.day == day } >= PER_ARM_DAY -> "$PER_ARM_DAY would-be trades today already"
        else -> null
    }

    /** A closed record: trades, net after charges, win rate (NaN with none). */
    fun record(closed: List<Pos>): LiveMirror.Record = LiveMirror.record(closed.mapNotNull { it.net })

    /** "shadow (switched off): 4 trades, Rs 1,200, 50% won" or "shadow (switched off): no would-be trade yet". */
    fun words(closed: List<Pos>, open: Int = 0): String {
        val r = record(closed)
        val head = if (r.trades == 0) "no would-be trade yet" else LiveMirror.recordWords(r).replace("shadow trade", "would-be trade")
        return "$LABEL: $head" + if (open > 0) " · $open open" else ""
    }
}
