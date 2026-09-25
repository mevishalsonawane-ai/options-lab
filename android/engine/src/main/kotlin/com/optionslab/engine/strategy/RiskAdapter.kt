package com.optionslab.engine.strategy

import com.optionslab.engine.risk.AggregateDecision
import com.optionslab.engine.risk.AggregateRisk
import com.optionslab.engine.risk.PositionDecision
import com.optionslab.engine.risk.PositionPnL
import com.optionslab.engine.risk.PositionRisk
import com.optionslab.engine.risk.Risk
import com.optionslab.engine.risk.RiskValues
import com.optionslab.engine.risk.Side
import com.optionslab.engine.risk.TrailMode

/**
 * The bridge between a run's state and the risk core. Port of
 * `services/strategy_module/risk_adapter.py`.
 *
 *     leg state -> PositionRisk  -> evaluatePosition  -> PositionDecision  -> leg state
 *     run state -> AggregateRisk -> evaluateAggregate -> AggregateDecision -> run state
 *
 * It owns no rule, only the translation. Every stop, target, trail and basket
 * decision comes from [Risk], so a change to how a trail ratchets reaches the
 * strategy engine and every other consumer at once. The system this ports
 * had its own evaluator, which is how four defects lived in it undetected.
 */
object RiskAdapter {
    /**
     * A leg's B/S as the core's side. Strict on purpose: the original read
     * anything that was not "B" as a short, so a leg that forgot its side was
     * evaluated upside down, its stop firing on a favourable move.
     */
    fun side(position: String?): Side = when ((position ?: "").uppercase()) {
        "B" -> Side.BUY
        "S" -> Side.SELL
        else -> throw IllegalArgumentException("Unusable leg position: ${Py.repr(position)}")
    }

    /** One configured unit in points: 1 for points, 1% of entry for percent (0 until there is an entry). */
    private fun pointsPerUnit(leg: LegState, entry: Double): Double =
        if (leg.riskUnit.lowercase() != "percent") 1.0 else if (entry > 0) entry / 100.0 else 0.0

    private fun inPoints(value: Double?, scale: Double): Double? {
        val converted = (value ?: return null) * scale
        return if (converted > 0) converted else null
    }

    /**
     * One leg as the core's input. Live effective levels win; the configured
     * points derive them on the first tick after entry. The initial stop is
     * always the configured one, because a stepped trail anchors to it and
     * handing it the trailed value would compound the advance every tick.
     *
     * Trail shapes: X alone is a fixed-distance trail (arm at X, hold an X gap:
     * trigger X, step X, continuous); X with Y arms at X and steps the stop Y
     * per X (stepped). A step of 0 would disable trailing outright.
     */
    fun legToPositionRisk(leg: LegState): PositionRisk {
        val side = side(leg.position)
        val entry = leg.entryAvg
        val scale = pointsPerUnit(leg, entry)
        val sl = inPoints(leg.slPts, scale)
        val target = inPoints(leg.targetPts, scale)
        val initialStop = if (sl != null && sl != 0.0) RiskValues.stopFromPoints(side, entry, sl) else null
        val configuredTarget = if (target != null && target != 0.0) RiskValues.targetFromPoints(side, entry, target) else null
        val x = inPoints(leg.trailX, scale) ?: 0.0
        val y = inPoints(leg.trailY, scale) ?: 0.0
        return PositionRisk(
            identifier = leg.legId.toString(),
            side = side,
            entryPrice = entry,
            quantity = leg.qty.toDouble(),
            stopPrice = leg.effectiveSl ?: initialStop,
            initialStopPrice = initialStop,
            targetPrice = leg.effectiveTarget ?: configuredTarget,
            trailingEnabled = x > 0,
            trailTrigger = x,
            trailStep = if (y > 0) y else x,
            trailMode = if (y > 0) TrailMode.STEPPED else TrailMode.CONTINUOUS,
            highestPrice = leg.highestPrice,
            lowestPrice = leg.lowestPrice,
        )
    }

    /**
     * Write an evaluation back, breach or not: the extremes and the trailed
     * stop are ratchets, and dropping them on a quiet tick would give back
     * protection the position already earned.
     */
    fun applyLegDecision(leg: LegState, decision: PositionDecision) {
        leg.effectiveSl = decision.stopPrice
        leg.effectiveTarget = decision.targetPrice
        leg.highestPrice = decision.highestPrice
        leg.lowestPrice = decision.lowestPrice
        leg.mtm = decision.pnl
        if (decision.trailArmed) leg.trailActive = true
    }

    /** Evaluate one leg against a tick and write the outcome back. */
    fun evaluateLeg(leg: LegState, lastPrice: Double): PositionDecision {
        val decision = Risk.evaluatePosition(legToPositionRisk(leg), lastPrice)
        if (decision.evaluated) leg.ltp = lastPrice
        applyLegDecision(leg, decision)
        return decision
    }

    /**
     * `(realized, unrealized)`, marked from each leg's own entry and price. A
     * leg not currently open contributes its realized figure: a signal leg
     * returns to "configured" after an exit, and keying on "closed" alone
     * dropped its profit from the total every basket rule is judged against.
     */
    fun runPnl(run: RunState): Pair<Double, Double> {
        val summary = Risk.aggregatePnl(
            run.legs.values
                .filter { it.status == "open" || it.realizedPnl != 0.0 }
                .map {
                    PositionPnL.fromState(
                        mapOf(
                            "identifier" to it.legId.toString(),
                            "side" to it.position,
                            "entry_price" to it.entryAvg,
                            "quantity" to it.qty.toDouble(),
                            "last_price" to it.ltp,
                            "closed" to (it.status != "open"),
                            "realized_pnl" to it.realizedPnl,
                        ),
                    )
                },
        )
        return summary.realized to summary.unrealized
    }

    /**
     * The run's basket limits and ratchets. A lock trail step only applies in
     * `lock_and_trail`: passing it in plain lock mode would turn a static floor
     * into a rising one, a different product from the one configured.
     */
    fun runToAggregateRisk(run: RunState, def: StrategyDef): AggregateRisk {
        val lock = def.lockProfit
        return AggregateRisk(
            combinedStoploss = def.overallSlMtm,
            combinedTarget = def.overallTargetMtm,
            lockProfitAt = lock?.ifProfitReaches,
            lockProfitFloor = lock?.lockProfit,
            lockTrailStep = if (lock?.mode == LockProfitMode.LOCK_AND_TRAIL) lock.trailStep else null,
            lockArmed = run.lockArmed,
            lockFloor = run.lockFloor,
            peakPnl = run.pnlPeak,
            troughPnl = run.pnlTrough,
            stopBypassed = run.trailToEntryActive,
        )
    }

    /** Write back on every pass: peak and trough are real numbers all session, not only on a breach. */
    fun applyRunDecision(run: RunState, decision: AggregateDecision) {
        run.pnlRealized = decision.realizedPnl
        run.pnlUnrealized = decision.unrealizedPnl
        run.pnlTotal = decision.totalPnl
        run.pnlPeak = decision.peakPnl
        run.pnlTrough = decision.troughPnl
        run.lockArmed = decision.lockArmed
        run.lockFloor = decision.lockFloor
    }

    fun evaluateRun(run: RunState, def: StrategyDef): AggregateDecision {
        val (realized, unrealized) = runPnl(run)
        val decision = Risk.evaluateAggregate(runToAggregateRisk(run, def), realized, unrealized)
        applyRunDecision(run, decision)
        return decision
    }

    /**
     * Move every other open leg's stop to its own entry, and say which moved.
     * Only a leg's own stop firing calls this; a manual close is an operator
     * override and must not tighten every remaining stop.
     */
    fun trailOpenLegsToEntry(run: RunState, triggeringLegId: Int): List<String> {
        val open = run.openLegs()
        val decision = Risk.trailStopsToEntry(
            open.map { legToPositionRisk(it) },
            exclude = listOf(triggeringLegId.toString()),
            lastPrices = open.associate { it.legId.toString() to it.ltp },
        )
        val byId = open.associateBy { it.legId.toString() }
        val moved = ArrayList<String>()
        for (move in decision.moves) {
            byId[move.identifier]?.let {
                it.effectiveSl = move.newStop
                moved.add(move.identifier)
            }
        }
        if (moved.isNotEmpty()) run.trailToEntryActive = true
        return moved
    }
}
