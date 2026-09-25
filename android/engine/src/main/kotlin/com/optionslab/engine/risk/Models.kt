package com.optionslab.engine.risk

import com.optionslab.engine.risk.RiskValues.asFloat
import com.optionslab.engine.risk.RiskValues.asPrice
import com.optionslab.engine.risk.RiskValues.normaliseSide
import com.optionslab.engine.risk.RiskValues.normaliseTrailMode
import com.optionslab.engine.risk.RiskValues.optionalFloat
import com.optionslab.engine.risk.RiskValues.pyStr
import com.optionslab.engine.risk.RiskValues.sideFromQuantity
import com.optionslab.engine.risk.RiskValues.stopFromPoints
import com.optionslab.engine.risk.RiskValues.targetFromPoints
import com.optionslab.engine.risk.RiskValues.truthy

/** Python's `first(*names)`: the first key whose value is not None (False and 0 count). */
private fun Map<String, Any?>.first(vararg names: String): Any? {
    for (n in names) this[n]?.let { return it }
    return null
}

/**
 * Everything the core needs to judge one position against one tick.
 *
 * Stops and targets are absolute prices, because that is what IraAlgo stores
 * and what quotes speak; a points-configured caller converts once at the edge
 * with [RiskValues.stopFromPoints]. [stopPrice] is the live stop a trail may
 * already have moved; [initialStopPrice] is what the user configured and is
 * the anchor a stepped trail advances from. [quantity] is a magnitude: [side]
 * is the single source of direction.
 */
data class PositionRisk(
    val identifier: String = "",
    val side: Side = Side.BUY,
    val entryPrice: Double = 0.0,
    val quantity: Double = 0.0,
    val stopPrice: Double? = null,
    val initialStopPrice: Double? = null,
    val targetPrice: Double? = null,
    val trailingEnabled: Boolean = false,
    val trailStep: Double = 0.0,
    val trailTrigger: Double = DEFAULT_TRAIL_TRIGGER,
    val trailMode: TrailMode = TrailMode.CONTINUOUS,
    val highestPrice: Double? = null,
    val lowestPrice: Double? = null,
) {
    val isLong: Boolean get() = side == Side.BUY

    /**
     * The stop actually in force: the live one, else the configured one. There
     * is deliberately no fallback to entry; a target-only position given an
     * implicit stop at entry exits on the first tick back through it and
     * reports a stop loss the user never set.
     */
    val effectiveStop: Double? get() = asPrice(stopPrice) ?: asPrice(initialStopPrice)

    companion object {
        /**
         * Build from a loose map: the scalping row shape or the canonical one.
         * Both alias sets load, so a DB row, a wire payload and a REST body need
         * no translation at each call site.
         */
        fun fromState(state: Map<String, Any?>): PositionRisk {
            val entry = asFloat(state.first("entry_price", "entry", "entry_avg", "average_price"))
            val side = normaliseSide(state.first("side", "action", "position"))
            val stop = asPrice(state.first("stop_price", "current_sl", "currentSl"))
            var initialStop = asPrice(state.first("initial_stop_price", "initial_sl", "initialSl"))
            var target = asPrice(state.first("target_price", "target", "targetPrice"))
            // Points-configured callers convert once, here.
            if (stop == null && initialStop == null) {
                initialStop = stopFromPoints(side, entry, asFloat(state.first("sl_points", "sl_pts")))
            }
            if (target == null) {
                target = targetFromPoints(side, entry, asFloat(state.first("target_points", "target_pts")))
            }
            val trigger = state.first("trail_trigger", "trailing_trigger", "trail_x")
            val id = state.first("identifier", "id", "symbol")
            return PositionRisk(
                identifier = if (truthy(id)) pyStr(id) else "",
                side = side,
                entryPrice = entry,
                quantity = kotlin.math.abs(asFloat(state.first("quantity", "qty"))),
                stopPrice = stop,
                initialStopPrice = initialStop,
                targetPrice = target,
                trailingEnabled = truthy(state.first("trailing_enabled", "trailingEnabled")),
                trailStep = asFloat(state.first("trail_step", "trailing_step", "trailingStep", "trail_y")),
                trailTrigger = if (trigger != null) asFloat(trigger, DEFAULT_TRAIL_TRIGGER) else DEFAULT_TRAIL_TRIGGER,
                trailMode = normaliseTrailMode(state.first("trail_mode", "trailMode")),
                highestPrice = asPrice(state.first("highest_price", "highestPrice")),
                lowestPrice = asPrice(state.first("lowest_price", "lowestPrice")),
            )
        }
    }
}

/**
 * What one tick did to one position.
 *
 * [evaluated] is false when the tick itself was unusable; every other field is
 * then the input carried through, so a caller can always write the decision
 * back over its state without a special case.
 */
data class PositionDecision(
    val identifier: String = "",
    val evaluated: Boolean = true,
    val stopPrice: Double? = null,
    val targetPrice: Double? = null,
    val highestPrice: Double? = null,
    val lowestPrice: Double? = null,
    val pnl: Double = 0.0,
    val breached: Boolean = false,
    val reason: BreachReason? = null,
    val detail: String = "",
    val stopMoved: Boolean = false,
    val trailArmed: Boolean = false,
) {
    /** The legacy `evaluate_trail` return shape, field for field. */
    fun toTrailState(): Map<String, Any?> = linkedMapOf(
        "highest_price" to highestPrice,
        "lowest_price" to lowestPrice,
        "current_sl" to stopPrice,
        "breached" to breached,
        "reason" to reason?.wire,
    )

    fun asDict(): Map<String, Any?> = linkedMapOf(
        "identifier" to identifier,
        "evaluated" to evaluated,
        "stop_price" to stopPrice,
        "target_price" to targetPrice,
        "highest_price" to highestPrice,
        "lowest_price" to lowestPrice,
        "pnl" to pnl,
        "breached" to breached,
        "reason" to reason?.wire,
        "detail" to detail,
        "stop_moved" to stopMoved,
        "trail_armed" to trailArmed,
    )
}

/**
 * One position's contribution to the aggregate mark to market. Closed ones
 * contribute [realizedPnl]; open ones are marked from [lastPrice]; an open one
 * with no usable price contributes nothing and is counted as unpriced.
 */
data class PositionPnL(
    val identifier: String = "",
    val side: Side = Side.BUY,
    val entryPrice: Double = 0.0,
    val quantity: Double = 0.0,
    val lastPrice: Double? = null,
    val closed: Boolean = false,
    val realizedPnl: Double = 0.0,
) {
    companion object {
        fun fromState(state: Map<String, Any?>): PositionPnL {
            val quantity = asFloat(state["quantity"] ?: state["qty"])
            val sideValue = state["side"] ?: state["action"]
            val id = if (truthy(state["identifier"])) state["identifier"] else state["symbol"]
            val status = state["status"]
            return PositionPnL(
                identifier = if (truthy(id)) pyStr(id) else "",
                // A position book row carries direction in the sign of the
                // quantity rather than a side field.
                side = if (truthy(sideValue)) normaliseSide(sideValue) else sideFromQuantity(quantity),
                entryPrice = asFloat(state["entry_price"] ?: state["average_price"]),
                quantity = kotlin.math.abs(quantity),
                lastPrice = asPrice(state["last_price"] ?: state["ltp"]),
                closed = truthy(state["closed"]) || (if (truthy(status)) pyStr(status) else "").lowercase() == "closed",
                realizedPnl = asFloat(state["realized_pnl"] ?: state["realized"]),
            )
        }
    }
}

/** Aggregate mark to market, named as IraAlgo's strategy P&L service names it. */
data class PnLSummary(
    val realized: Double = 0.0,
    val unrealized: Double = 0.0,
    val total: Double = 0.0,
    val priced: Int = 0,
    val unpriced: Int = 0,
) {
    fun asDict(): Map<String, Any?> = linkedMapOf(
        "realized" to realized, "unrealized" to unrealized, "total" to total,
        "priced" to priced, "unpriced" to unpriced,
    )
}

/**
 * Portfolio-level limits and the ratchet state carried between ticks.
 *
 * Amounts are mark-to-market currency, not prices. [combinedStoploss] is read
 * as a magnitude, so 5000 and -5000 mean the same loss. Lock profit arms once
 * `total >= lockProfitAt` and then holds a floor at [lockProfitFloor]; with
 * [lockTrailStep] set the floor also ratchets to `peak - step` and never falls.
 */
data class AggregateRisk(
    val combinedStoploss: Double? = null,
    val combinedTarget: Double? = null,
    val lockProfitAt: Double? = null,
    val lockProfitFloor: Double? = null,
    val lockTrailStep: Double? = null,
    val lockArmed: Boolean = false,
    val lockFloor: Double? = null,
    val peakPnl: Double = 0.0,
    val troughPnl: Double = 0.0,
    val stopBypassed: Boolean = false,
) {
    companion object {
        fun fromState(state: Map<String, Any?>): AggregateRisk = AggregateRisk(
            combinedStoploss = optionalFloat(state.first("combined_stoploss", "overall_sl_mtm", "max_loss")),
            combinedTarget = optionalFloat(state.first("combined_target", "overall_target_mtm", "max_profit")),
            lockProfitAt = optionalFloat(state.first("lock_profit_at", "if_profit_reaches")),
            lockProfitFloor = optionalFloat(state.first("lock_profit_floor", "lock_profit")),
            lockTrailStep = optionalFloat(state.first("lock_trail_step", "trail_step")),
            lockArmed = truthy(state.first("lock_armed")),
            lockFloor = optionalFloat(state.first("lock_floor")),
            peakPnl = asFloat(state.first("peak_pnl", "pnl_peak")),
            troughPnl = asFloat(state.first("trough_pnl", "pnl_trough")),
            stopBypassed = truthy(state.first("stop_bypassed", "trail_to_entry_active")),
        )
    }
}

/**
 * What one aggregate evaluation decided. Peak, trough, armed and floor are
 * always returned, breach or not: they are ratchets the caller must persist.
 */
data class AggregateDecision(
    val totalPnl: Double = 0.0,
    val realizedPnl: Double = 0.0,
    val unrealizedPnl: Double = 0.0,
    val peakPnl: Double = 0.0,
    val troughPnl: Double = 0.0,
    val lockArmed: Boolean = false,
    val lockFloor: Double? = null,
    val lockArmedNow: Boolean = false,
    val lockFloorRaised: Boolean = false,
    val breached: Boolean = false,
    val reason: BreachReason? = null,
    val detail: String = "",
) {
    fun asDict(): Map<String, Any?> = linkedMapOf(
        "total_pnl" to totalPnl,
        "realized_pnl" to realizedPnl,
        "unrealized_pnl" to unrealizedPnl,
        "peak_pnl" to peakPnl,
        "trough_pnl" to troughPnl,
        "lock_armed" to lockArmed,
        "lock_floor" to lockFloor,
        "lock_armed_now" to lockArmedNow,
        "lock_floor_raised" to lockFloorRaised,
        "breached" to breached,
        "reason" to reason?.wire,
        "detail" to detail,
    )
}

/** One stop relocation the caller should apply. */
data class StopMove(val identifier: String, val previousStop: Double?, val newStop: Double) {
    fun asDict(): Map<String, Any?> = linkedMapOf(
        "identifier" to identifier, "previous_stop" to previousStop, "new_stop" to newStop,
    )
}

/**
 * Which stops trail to entry, and which were deliberately left alone. Nothing
 * is mutated; the caller applies [moves], which is what lets a handler that
 * owns no state use it and lets a caller veto a move before applying it.
 */
data class TrailToEntryDecision(
    val moves: List<StopMove> = emptyList(),
    val skippedNotImproving: List<String> = emptyList(),
    val skippedThroughPrice: List<String> = emptyList(),
    val skippedNoEntry: List<String> = emptyList(),
    val detail: String = "",
) {
    val moved: Int get() = moves.size

    fun asDict(): Map<String, Any?> = linkedMapOf(
        "moved" to moved,
        "moves" to moves.map { it.asDict() },
        "skipped_not_improving" to skippedNotImproving,
        "skipped_through_price" to skippedThroughPrice,
        "skipped_no_entry" to skippedNoEntry,
        "detail" to detail,
    )
}
