package com.optionslab.engine.risk

import com.optionslab.engine.risk.RiskValues.asPrice
import com.optionslab.engine.risk.RiskValues.formatPrice
import com.optionslab.engine.risk.RiskValues.isPrice
import com.optionslab.engine.risk.RiskValues.normaliseSide
import com.optionslab.engine.risk.RiskValues.pyFloat
import com.optionslab.engine.risk.RiskValues.pyMax
import com.optionslab.engine.risk.RiskValues.pyMin
import kotlin.math.abs
import kotlin.math.floor

/**
 * Every risk decision: per-position stop, target and trailing stop, and the
 * aggregate combined stop, combined target, lock-profit floor and trail to
 * entry. Port of `services/risk/position.py`, `aggregate.py` and `adapters.py`.
 *
 * The behavioural choices are IraAlgo's reconciliation of two older
 * evaluators, and each is kept because it is the safer reading:
 *
 * - the trail arms off the favourable PEAK, not the current price, so a state
 *   restored after a restart re-derives the trailed stop it had earned;
 * - a missing stop is no stop - there is no implicit stop at entry;
 * - zero is never a price, so a zero stop or target reads as absent;
 * - an unusable tick (missing, zero, negative, non-finite) changes nothing;
 * - a non-positive entry disables trailing and P&L, never "vast profit";
 * - a trail never places the stop beyond the best price actually traded;
 * - when stop and target both hit on one tick, the stop wins, because the
 *   order of the two touches within a tick is unknowable.
 */
object Risk {
    // ------------------------------------------------------------------ position

    /**
     * Judge one position against one last price.
     *
     * Order of work: update the favourable extreme, move the trail, then test
     * the stop, then the target. The trail runs first so a stop that has just
     * ratcheted is the one tested and can fire on the tick that set it.
     *
     * [lastPrice] is `Any?` because the Python accepts whatever a feed sends;
     * a string that parses as a positive number is a price there, so it is here.
     */
    fun evaluatePosition(risk: PositionRisk, lastPrice: Any?): PositionDecision {
        if (!isPrice(lastPrice)) {
            return PositionDecision(
                identifier = risk.identifier,
                evaluated = false,
                stopPrice = risk.effectiveStop,
                targetPrice = asPrice(risk.targetPrice),
                highestPrice = risk.highestPrice,
                lowestPrice = risk.lowestPrice,
                pnl = 0.0,
                detail = "tick ignored: last price is missing, zero, negative or not finite",
            )
        }
        val ltp = pyFloat(lastPrice)!!
        val long = risk.isLong
        val entry = if (isPrice(risk.entryPrice)) risk.entryPrice else null

        // Seed a missing extreme from entry, or from this tick when even entry
        // is unusable, so the extreme is never seeded at zero.
        val seed = entry ?: ltp
        var highest = risk.highestPrice
        var lowest = risk.lowestPrice
        if (long) highest = pyMax(highest ?: seed, ltp) else lowest = pyMin(lowest ?: seed, ltp)

        var favourable = 0.0
        if (entry != null) {
            val best = if (long) highest else lowest
            if (best != null) favourable = pyMax(0.0, if (long) best - entry else entry - best)
        }

        var stop = risk.effectiveStop
        val originalStop = stop
        var trailArmed = false

        val trigger = pyMax(0.0, risk.trailTrigger)
        val canTrail = risk.trailingEnabled && risk.trailStep > 0.0 && risk.trailStep.isFinite() && entry != null
        // A trigger of zero means "as soon as it is in profit", not "from entry".
        if (canTrail && favourable > 0.0 && favourable >= trigger) {
            trailArmed = true
            var candidate = trailCandidate(risk, favourable, highest, lowest, trigger)
            if (candidate != null) {
                candidate = clampToPeak(candidate, long, highest, lowest)
                // Ratchet: a trail may only ever tighten.
                if (stop == null || (if (long) candidate > stop else candidate < stop)) stop = candidate
            }
        }

        val stopMoved = pyNe(stop, originalStop)
        val target = asPrice(risk.targetPrice)
        val stopHit = stop != null && (if (long) ltp <= stop else ltp >= stop)
        val targetHit = target != null && (if (long) ltp >= target else ltp <= target)

        var reason: BreachReason? = null
        var detail = ""
        val book = if (long) "long" else "short"
        if (stopHit) {
            reason = BreachReason.STOP
            detail = "stop loss hit: last price ${formatPrice(ltp)} is at or " +
                "${if (long) "below" else "above"} the stop ${formatPrice(stop!!)} on a $book position"
        } else if (targetHit) {
            reason = BreachReason.TARGET
            detail = "target hit: last price ${formatPrice(ltp)} is at or " +
                "${if (long) "above" else "below"} the target ${formatPrice(target!!)} on a $book position"
        } else if (stopMoved && stop != null) {
            val previous = if (originalStop != null) formatPrice(originalStop) else "none"
            detail = "trailing stop moved from $previous to ${formatPrice(stop)} after " +
                "${formatPrice(favourable)} of favourable movement"
        }

        var pnl = 0.0
        if (entry != null && risk.quantity != 0.0) {
            pnl = if (long) (ltp - entry) * risk.quantity else (entry - ltp) * risk.quantity
        }
        return PositionDecision(
            identifier = risk.identifier,
            evaluated = true,
            stopPrice = stop,
            targetPrice = target,
            highestPrice = highest,
            lowestPrice = lowest,
            pnl = pnl,
            breached = reason != null,
            reason = reason,
            detail = detail,
            stopMoved = stopMoved,
            trailArmed = trailArmed,
        )
    }

    /** For callers holding a loose map rather than a [PositionRisk]. */
    fun evaluatePositionState(state: Map<String, Any?>, lastPrice: Any?): PositionDecision =
        evaluatePosition(PositionRisk.fromState(state), lastPrice)

    /**
     * Plain-text reasons a configuration is unusable, empty when it is fine.
     * The same checks the browser's Set-SL dialog runs, kept here so the form,
     * the save path and a REST caller cannot drift: a stop on the wrong side of
     * the market is an instant exit rather than protection.
     */
    fun validatePosition(risk: PositionRisk, lastPrice: Any? = null): List<String> {
        val problems = ArrayList<String>()
        val long = risk.isLong
        val entry = if (isPrice(risk.entryPrice)) risk.entryPrice else null
        val reference = if (isPrice(lastPrice)) pyFloat(lastPrice) else entry

        if (entry == null) problems.add("entry price is missing or not a positive number")

        val stop = risk.effectiveStop
        if (stop != null && reference != null) {
            if (long && stop >= reference) {
                problems.add("stop ${formatPrice(stop)} is at or above ${formatPrice(reference)} on a long position, which exits immediately")
            }
            if (!long && stop <= reference) {
                problems.add("stop ${formatPrice(stop)} is at or below ${formatPrice(reference)} on a short position, which exits immediately")
            }
        }
        val target = asPrice(risk.targetPrice)
        if (target != null && reference != null) {
            if (long && target <= reference) {
                problems.add("target ${formatPrice(target)} is at or below ${formatPrice(reference)} on a long position, which exits immediately")
            }
            if (!long && target >= reference) {
                problems.add("target ${formatPrice(target)} is at or above ${formatPrice(reference)} on a short position, which exits immediately")
            }
        }
        if (risk.trailingEnabled && !(risk.trailStep > 0.0)) {
            problems.add("trailing is enabled but the trail step is not a positive number")
        }
        if (risk.trailingEnabled && risk.trailMode == TrailMode.STEPPED && !(risk.trailTrigger > 0.0)) {
            problems.add("a stepped trail needs a positive trail trigger")
        }
        if (risk.trailingEnabled && risk.trailMode == TrailMode.STEPPED &&
            risk.trailStep > risk.trailTrigger && risk.trailTrigger > 0.0
        ) {
            problems.add(
                "a stepped trail with a step larger than its trigger gives back more than " +
                    "it locks in; reduce the step or raise the trigger",
            )
        }
        return problems
    }

    /** The level the trail wants, before the peak clamp and the ratchet. */
    private fun trailCandidate(
        risk: PositionRisk, favourable: Double, highest: Double?, lowest: Double?, trigger: Double,
    ): Double? {
        if (risk.trailMode == TrailMode.STEPPED) {
            // A stepped trail with no trigger has no step boundary.
            if (trigger <= 0.0) return null
            val anchor = asPrice(risk.initialStopPrice) ?: risk.effectiveStop ?: return null
            val steps = floor(favourable / trigger)
            if (steps <= 0) return null
            val advance = steps * risk.trailStep
            return if (risk.isLong) anchor + advance else anchor - advance
        }
        return if (risk.isLong) highest?.let { it - risk.trailStep } else lowest?.let { it + risk.trailStep }
    }

    /** Never place a stop beyond the best price the position has actually seen. */
    private fun clampToPeak(candidate: Double, long: Boolean, highest: Double?, lowest: Double?): Double = when {
        long && highest != null -> pyMin(candidate, highest)
        !long && lowest != null -> pyMax(candidate, lowest)
        else -> candidate
    }

    /** Python's `a != b` over optional floats. */
    private fun pyNe(a: Double?, b: Double?): Boolean =
        if (a == null || b == null) (a == null) != (b == null) else !(a == b)

    // ----------------------------------------------------------------- aggregate

    /** Mark to market for one open position; zero when it cannot be marked. */
    fun positionPnl(side: Any?, entryPrice: Any?, quantity: Double, lastPrice: Any?): Double {
        if (!isPrice(entryPrice) || !isPrice(lastPrice) || quantity == 0.0) return 0.0
        val entry = pyFloat(entryPrice)!!
        val ltp = pyFloat(lastPrice)!!
        val magnitude = abs(quantity)
        return if (normaliseSide(side) == Side.BUY) (ltp - entry) * magnitude else (entry - ltp) * magnitude
    }

    /**
     * Sum realized and unrealized mark to market across positions, marking open
     * ones from their own entry, quantity and last price, so the aggregate can
     * never be computed from a stale per-position number.
     *
     * Realized counts whether or not the position is open now: a leg
     * re-entered after a round trip still carries that round trip, and
     * dropping it on re-entry reset a daily loss limit on every flat moment.
     *
     * Items are [PositionPnL] or loose maps, as the Python accepts either.
     */
    fun aggregatePnl(positions: Iterable<Any>): PnLSummary {
        var realized = 0.0
        var unrealized = 0.0
        var priced = 0
        var unpriced = 0
        for (item in positions) {
            @Suppress("UNCHECKED_CAST")
            val e = item as? PositionPnL ?: PositionPnL.fromState(item as Map<String, Any?>)
            realized += e.realizedPnl
            if (e.closed) continue
            if (!isPrice(e.lastPrice) || !isPrice(e.entryPrice)) {
                unpriced++
                continue
            }
            priced++
            unrealized += positionPnl(e.side, e.entryPrice, e.quantity, e.lastPrice)
        }
        return PnLSummary(realized, unrealized, realized + unrealized, priced, unpriced)
    }

    /**
     * Judge the whole set against its combined limits.
     *
     * Layer order: mark to market, then lock profit (its floor sits above the
     * combined stop, so it is the tighter rule whenever armed), then combined
     * stop, then combined target. Trail to entry bypasses only the combined
     * stop, never the target: a book made risk free should still take profit.
     */
    fun evaluateAggregate(risk: AggregateRisk, realizedPnl: Double, unrealizedPnl: Double): AggregateDecision {
        val total = realizedPnl + unrealizedPnl
        val peak = pyMax(risk.peakPnl, total)
        val trough = pyMin(risk.troughPnl, total)

        var lockArmed = risk.lockArmed
        var lockFloor = risk.lockFloor
        var armedNow = false
        var floorRaised = false

        if (risk.lockProfitAt != null) {
            if (!lockArmed && total >= risk.lockProfitAt) {
                lockArmed = true
                armedNow = true
            }
            if (lockArmed) {
                // The max of these is what makes the floor a ratchet.
                var newFloor = risk.lockProfitFloor ?: 0.0
                if (lockFloor != null) newFloor = pyMax(newFloor, lockFloor)
                if (risk.lockTrailStep != null && risk.lockTrailStep > 0.0) newFloor = pyMax(newFloor, peak - risk.lockTrailStep)
                floorRaised = !armedNow && lockFloor != null && newFloor > lockFloor
                lockFloor = newFloor
            }
        }

        fun decide(reason: BreachReason?, detail: String) = AggregateDecision(
            totalPnl = total,
            realizedPnl = realizedPnl,
            unrealizedPnl = unrealizedPnl,
            peakPnl = peak,
            troughPnl = trough,
            lockArmed = lockArmed,
            lockFloor = lockFloor,
            lockArmedNow = armedNow,
            lockFloorRaised = floorRaised,
            breached = reason != null,
            reason = reason,
            detail = detail,
        )

        // Gated on the configuration still being present: a persisted armed
        // flag left over from a removed configuration must not keep closing.
        if (risk.lockProfitAt != null && lockArmed && lockFloor != null && total <= lockFloor) {
            // A floor above its arming threshold self-triggers on the arming
            // tick; that is a configuration error, so it says so.
            if (armedNow && lockFloor > risk.lockProfitAt) {
                return decide(
                    BreachReason.LOCK_PROFIT,
                    "lock profit floor ${formatPrice(lockFloor)} is above its arming threshold " +
                        "${formatPrice(risk.lockProfitAt)}, so it triggered on the tick it armed; " +
                        "the floor must be below the threshold",
                )
            }
            return decide(
                BreachReason.LOCK_PROFIT,
                "lock profit triggered: mark to market ${formatPrice(total)} fell to or below the locked floor ${formatPrice(lockFloor)}",
            )
        }

        if (!risk.stopBypassed && risk.combinedStoploss != null) {
            val limit = abs(risk.combinedStoploss)
            if (total <= -limit) {
                return decide(
                    BreachReason.COMBINED_STOP,
                    "combined stop loss hit: mark to market ${formatPrice(total)} fell to or below the limit ${formatPrice(-limit)}",
                )
            }
        }

        if (risk.combinedTarget != null && total >= risk.combinedTarget) {
            return decide(
                BreachReason.COMBINED_TARGET,
                "combined target hit: mark to market ${formatPrice(total)} reached the target ${formatPrice(risk.combinedTarget)}",
            )
        }

        var detail = ""
        if (armedNow) {
            detail = "lock profit armed at ${formatPrice(total)}, floor set to ${formatPrice(lockFloor!!)}"
        } else if (floorRaised && lockFloor != null) {
            detail = "lock profit floor raised to ${formatPrice(lockFloor)} on a peak of ${formatPrice(peak)}"
        }
        return decide(null, detail)
    }

    fun evaluateAggregateState(state: Map<String, Any?>, realizedPnl: Double, unrealizedPnl: Double): AggregateDecision =
        evaluateAggregate(AggregateRisk.fromState(state), realizedPnl, unrealizedPnl)

    /**
     * Move every remaining position's stop to its own entry price.
     *
     * Fired when one position's rule fires and the rest should be made risk
     * free; the trigger is named in [exclude] because it is closing anyway. A
     * move is skipped when it would not tighten the stop, and - when a last
     * price is supplied - when entry is already through the market, because
     * moving a loser's stop to entry is an instant market exit at a loss, the
     * opposite of what making the winners risk free is for.
     */
    fun trailStopsToEntry(
        positions: List<Any>,
        exclude: Iterable<String> = emptyList(),
        lastPrices: Map<String, Any?>? = null,
    ): TrailToEntryDecision {
        val excluded = exclude.toSet()
        val prices = lastPrices ?: emptyMap()
        val moves = ArrayList<StopMove>()
        val notImproving = ArrayList<String>()
        val throughPrice = ArrayList<String>()
        val noEntry = ArrayList<String>()

        for (item in positions) {
            @Suppress("UNCHECKED_CAST")
            val risk = item as? PositionRisk ?: PositionRisk.fromState(item as Map<String, Any?>)
            if (risk.identifier in excluded) continue
            if (!isPrice(risk.entryPrice)) {
                noEntry.add(risk.identifier)
                continue
            }
            val entry = risk.entryPrice
            val current = risk.effectiveStop
            val long = risk.isLong
            if (current != null && (if (long) entry <= current else entry >= current)) {
                notImproving.add(risk.identifier)
                continue
            }
            val reference = asPrice(prices[risk.identifier])
            if (reference != null && (if (long) entry >= reference else entry <= reference)) {
                throughPrice.add(risk.identifier)
                continue
            }
            moves.add(StopMove(risk.identifier, current, entry))
        }

        var detail = ""
        if (moves.isNotEmpty()) {
            detail = "moved ${moves.size} stop(s) to entry"
            if (throughPrice.isNotEmpty()) {
                detail += "; left ${throughPrice.size} alone because entry is already through the market"
            }
        } else if (throughPrice.isNotEmpty() || notImproving.isNotEmpty() || noEntry.isNotEmpty()) {
            detail = "no stop moved to entry"
        }
        return TrailToEntryDecision(moves, notImproving, throughPrice, noEntry, detail)
    }

    // ------------------------------------------------------------------ adapters

    /**
     * Drop-in for the legacy `scalping_risk_monitor_service.evaluate_trail`:
     * same input map, same output keys, same `sl` / `target` reasons. An
     * unusable tick reports the input unchanged and no breach, because the
     * legacy contract has no "not evaluated" state.
     */
    fun evaluateTrail(state: Map<String, Any?>, lastPrice: Any?): Map<String, Any?> {
        val decision = evaluatePosition(PositionRisk.fromState(state), lastPrice)
        if (!decision.evaluated) {
            return linkedMapOf(
                "highest_price" to state["highest_price"],
                "lowest_price" to state["lowest_price"],
                "current_sl" to state["current_sl"],
                "breached" to false,
                "reason" to null,
            )
        }
        return decision.toTrailState()
    }
}
