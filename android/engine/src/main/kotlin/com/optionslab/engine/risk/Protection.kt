package com.optionslab.engine.risk

import kotlin.math.ceil
import kotlin.math.floor

/**
 * A position's protection: a stop (fixed, or trailing by a number of points)
 * and an optional target. Pure rules; the app places and moves the orders.
 *
 *  - A trailing stop follows the best price seen since it was set (the high for
 *    a long, the low for a short) at [trailPoints] behind it, and ONLY EVER
 *    TIGHTENS: it never moves back, whatever the price does.
 *  - Stops sit on the exchange tick, rounded away from the price (down for a
 *    long's stop, up for a short's) so rounding never tightens past the rule.
 */
object Protection {
    data class Spec(
        /** +1 long (the exit is a SELL), -1 short (the exit is a BUY). */
        val direction: Int,
        val stop: Double?,
        val trailPoints: Double?,
        val target: Double?,
        /** Best price seen since the protection was set. */
        val best: Double,
        val tick: Double = 0.05,
    )

    fun onTick(x: Double, tick: Double, down: Boolean): Double {
        if (tick <= 0) return x
        val n = x / tick
        return (if (down) floor(n + 1e-9) else ceil(n - 1e-9)) * tick
    }

    /** The starting stop: the given level, or [trailPoints] behind the current price. */
    fun initialStop(direction: Int, price: Double, stop: Double?, trailPoints: Double?, tick: Double = 0.05): Double? {
        val raw = stop ?: trailPoints?.let { price - direction * it } ?: return null
        return onTick(raw, tick, down = direction > 0).takeIf { it > 0 }
    }

    /** The spec after [price]: the best price moved on, and the trailing stop tightened if that allows. */
    fun next(s: Spec, price: Double): Spec {
        if (!(price > 0)) return s
        val best = if (s.direction > 0) maxOf(s.best, price) else minOf(s.best, price)
        val trail = s.trailPoints ?: return s.copy(best = best)
        val candidate = onTick(best - s.direction * trail, s.tick, down = s.direction > 0)
        val cur = s.stop
        val tighter = when {
            cur == null -> candidate
            s.direction > 0 -> maxOf(cur, candidate)
            else -> minOf(cur, candidate)
        }
        return s.copy(best = best, stop = tighter.takeIf { it > 0 } ?: cur)
    }

    /** "stop", "target" or null for a price, for a protection watched by polling (no resting order). */
    fun hit(s: Spec, price: Double): String? {
        if (!(price > 0)) return null
        val stop = s.stop
        if (stop != null && (if (s.direction > 0) price <= stop else price >= stop)) return "stop"
        val target = s.target
        if (target != null && (if (s.direction > 0) price >= target else price <= target)) return "target"
        return null
    }

    /** What is wrong with a request, or null: the stop must be on the losing side, the target on the winning side. */
    fun validate(direction: Int, price: Double, stop: Double?, trailPoints: Double?, target: Double?): String? {
        if (stop == null && trailPoints == null && target == null) return "Set a stop, a trailing distance or a target."
        if (trailPoints != null && !(trailPoints > 0)) return "The trailing distance must be above zero."
        if (stop != null && (if (direction > 0) stop >= price else stop <= price))
            return "The stop must be ${if (direction > 0) "below" else "above"} the current price (%.2f).".format(price)
        if (target != null && (if (direction > 0) target <= price else target >= price))
            return "The target must be ${if (direction > 0) "above" else "below"} the current price (%.2f).".format(price)
        return null
    }
}
