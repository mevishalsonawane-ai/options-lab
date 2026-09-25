package com.optionslab.engine.strategy

import com.optionslab.engine.risk.BreachReason
import com.optionslab.engine.risk.RiskValues
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZonedDateTime

/**
 * One audit event, as the Python `_emit` records it: a kind from the
 * strategy event vocabulary, a message, a severity (`info` / `warn` /
 * `critical`), and the leg or payload it concerns.
 */
data class Event(
    val kind: String,
    val message: String,
    val severity: String = "info",
    val legId: Int? = null,
    val payload: Map<String, Any?>? = null,
)

/**
 * The in-memory transitions of IraAlgo's `services/strategy_module/engine.py`
 * and `state.py`: claims, fills, tick evaluation, exits, stop and finalise.
 *
 * Everything the Python does under the run lock is here, line for line. What
 * the Python does outside it - database writes, broker calls, emits - becomes
 * [StrategyRuntime]'s actions. That split is the Python's own: a critical
 * section holds bookkeeping only, which is exactly the part that is pure.
 *
 * Two orderings are load bearing and kept:
 *
 * - **exits claim before they dispatch.** The claim (`exit_kind` plus a
 *   token) is taken before any order exists, so two rules firing on one leg
 *   cannot both send a covering order and leave the account reversed;
 * - **an unfilled entry is never squared off.** A leg is "open" once its
 *   entry is accepted, but there is no confirmed quantity to close until it
 *   fills, and a full-size exit against an entry that then cancels is a naked
 *   position the other way.
 */
internal object Engine {
    /** Only a leg's own stop triggers trail-to-entry; a manual close is an override, not a signal. */
    private const val STOP_DRIVEN_EXIT = "exit_sl"

    val EXIT_KIND_FOR_REASON = mapOf(
        BreachReason.STOP to "exit_sl",
        BreachReason.TARGET to "exit_target",
        BreachReason.COMBINED_STOP to "exit_overall_sl",
        BreachReason.COMBINED_TARGET to "exit_overall_target",
        BreachReason.LOCK_PROFIT to "exit_lock_profit",
    )

    private val STOP_REASON_FOR_REASON = mapOf(
        BreachReason.COMBINED_STOP to "overall_sl",
        BreachReason.COMBINED_TARGET to "overall_target",
        BreachReason.LOCK_PROFIT to "lock_profit",
    )

    val ORDER_KINDS = setOf(
        "entry", "exit_sl", "exit_target", "exit_trail", "exit_overall_sl", "exit_overall_target", "exit_lock_profit",
        "exit_eod", "exit_expiry", "exit_daily_loss_limit", "exit_close_all", "exit_leg_manual", "exit_recovery", "exit_signal",
    )

    // ------------------------------------------------------------ leg state

    /**
     * A new leg's state. The side is required for every kind of leg: the
     * original omitted it on signal legs, the evaluator read the absence as
     * short, and those legs' stops fired on favourable moves.
     */
    fun newLegState(
        legId: Int, position: String, symbol: String, exchange: String, lots: Int, quantity: Int,
        slPts: Double?, targetPts: Double?, trail: Trail?, riskUnit: String?, positionRef: String?,
    ): LegState {
        val p = position.uppercase()
        require(p == "B" || p == "S") { "Leg $legId has an unusable position: ${Py.repr(position)}" }
        return LegState(
            legId = legId, position = p, symbol = symbol, exchange = exchange, lots = lots, qty = quantity,
            positionRef = positionRef, riskUnit = riskUnit ?: "points", slPts = slPts, targetPts = targetPts,
            trailX = trail?.x?.takeIf { it != 0.0 } ?: 0.0, trailY = trail?.y?.takeIf { it != 0.0 } ?: 0.0,
        )
    }

    // ---------------------------------------------------------------- claims

    /**
     * Claim every exitable leg and name the ones that cannot be, in one pass:
     * claimed = open, entry filled, no exit in flight; unfilled = open with an
     * entry only accepted. One pass because a fill landing between two passes
     * made a leg appear in neither list and the run finalised holding it.
     */
    fun claimLegsForExit(run: RunState, legIds: List<Int>, kind: String): Pair<List<LegState>, List<LegState>> {
        val claimed = ArrayList<LegState>()
        val unfilled = ArrayList<LegState>()
        for (id in legIds) {
            val leg = run.leg(id) ?: continue
            if (leg.status != "open" || leg.exitInFlight) continue
            if (leg.entryStatus != "complete") { unfilled.add(leg.copy()); continue }
            leg.exitKind = kind
            leg.exitClaimToken = run.token()
            claimed.add(leg.copy())
        }
        return claimed to unfilled
    }

    /** Claim one leg for exit, or null when it must not be exited (again). */
    fun claimLegExit(run: RunState, legId: Int, kind: String): LegState? {
        val leg = run.leg(legId) ?: return null
        if (leg.status != "open" || leg.entryStatus != "complete" || leg.exitInFlight) return null
        leg.exitKind = kind
        leg.exitClaimToken = run.token()
        return leg.copy()
    }

    /** A claimed outgoing flip position to exit: side, size and the leg's contract. */
    class SupersededClaim(val legId: Int, val position: String, val positionRef: String?, val claimToken: String, val symbol: String, val exchange: String, val quantity: Int?)

    fun claimSupersededExit(run: RunState, legId: Int, position: String?): SupersededClaim? {
        val leg = run.leg(legId) ?: return null
        val s = leg.superseded ?: return null
        if (s.exitClaimToken != null || s.exitOrderId != null) return null
        if ((s.position ?: "").uppercase() != (position ?: "").uppercase()) return null
        val token = run.token()
        s.exitClaimToken = token
        return SupersededClaim(leg.legId, s.position!!, s.positionRef, token, leg.symbol, leg.exchange, s.qty)
    }

    fun bindLiveExit(run: RunState, legId: Int, claimToken: String?, orderId: Long, positionRef: String?): Boolean {
        val leg = run.leg(legId) ?: return false
        if (claimToken == null || leg.exitClaimToken != claimToken || leg.exitOrderId != null ||
            (positionRef != null && leg.positionRef != positionRef)
        ) return false
        leg.exitOrderId = orderId
        return true
    }

    fun bindSupersededExit(run: RunState, legId: Int, claimToken: String?, orderId: Long): Boolean {
        val s = run.leg(legId)?.superseded ?: return false
        if (claimToken == null || s.exitClaimToken != claimToken) return false
        s.exitOrderId = orderId
        return true
    }

    /**
     * Undo only the exact live-exit claim whose order was refused. Without it
     * the leg's stop, target, the square-off and the Close button all skip a
     * leg that is still held, for the rest of the session.
     */
    fun releaseLegExit(run: RunState, legId: Int, claimId: Any?): Boolean {
        if (claimId == null) return false
        val leg = run.leg(legId) ?: return false
        if (claimId != leg.exitClaimToken && claimId != leg.exitOrderId) return false
        leg.exitKind = null
        leg.exitClaimToken = null
        leg.exitOrderId = null
        return true
    }

    fun releaseSupersededExit(run: RunState, legId: Int, claimId: Any?): Boolean {
        if (claimId == null) return false
        val s = run.leg(legId)?.superseded ?: return false
        if (claimId != s.exitClaimToken && claimId != s.exitOrderId) return false
        s.exitOrderId = null
        s.exitClaimToken = null
        s.exitKind = null
        return true
    }

    /** Release the exact live or superseded owner of one terminal exit order. */
    fun releaseOrderExit(run: RunState, legId: Int, orderId: Long, positionRef: String?): String? {
        val leg = run.leg(legId) ?: return null
        val s = leg.superseded
        if (s != null && s.exitOrderId == orderId && (positionRef == null || s.positionRef == positionRef)) {
            s.exitOrderId = null; s.exitClaimToken = null; s.exitKind = null
            return "superseded"
        }
        if (leg.exitOrderId == orderId && (positionRef == null || leg.positionRef == positionRef)) {
            leg.exitOrderId = null; leg.exitClaimToken = null; leg.exitKind = null
            return "live"
        }
        return null
    }

    // ------------------------------------------------------------ signal claims

    /** A signal entry claim, or the no-op note that answers it. */
    class EntryClaim(val claim: SignalClaim?, val note: String?)

    /**
     * Claim one signal entry decision before any other work, so a duplicate
     * alert, a flip still settling, or a stop request cannot open a second
     * position. Port of `state.claim_signal_entry`.
     */
    fun claimSignalEntry(run: RunState, legId: Int, position: String): EntryClaim {
        if (run.stopping) return EntryClaim(null, "run_stopping")
        val key = legId.toString()
        if (key in run.signalEntryClaims) return EntryClaim(null, "flip_pending")
        val leg = run.legs[key]
        val requested = position.uppercase()
        val live = if (leg != null && leg.status == "open") leg.position else null
        val superseded = leg?.superseded
        val already = if (requested == "B") "already_long" else "already_short"
        if (live == requested) return EntryClaim(null, already)
        if (live != null && superseded != null) return EntryClaim(null, "flip_pending")
        if (superseded != null && superseded.position == requested) return EntryClaim(null, already)
        if (superseded != null) return EntryClaim(null, "flip_pending")
        if (live != null && leg!!.exitInFlight) return EntryClaim(null, "flip_pending")
        val claim = SignalClaim(run.token(), run.token("p"), requested, live, leg?.positionRef)
        run.signalEntryClaims[key] = claim
        return EntryClaim(claim, null)
    }

    fun releaseSignalEntryClaim(run: RunState, legId: Int, claimToken: String): Boolean {
        val claim = run.signalEntryClaims[legId.toString()] ?: return false
        if (claim.claimToken != claimToken) return false
        run.signalEntryClaims.remove(legId.toString())
        return true
    }

    /**
     * Install a claimed signal leg over its exact expected owner. A flip keeps
     * the outgoing position under `superseded` until its closing order fills,
     * and realized P&L carries forward so a daily loss limit is not reset by a
     * round trip.
     */
    fun addLeg(run: RunState, leg: LegState, claimToken: String, expectedPositionRef: String?, entryOrderId: Long): LegState? {
        if (run.stopping) return null
        val key = leg.legId.toString()
        val claim = run.signalEntryClaims[key] ?: return null
        if (claim.claimToken != claimToken || claim.positionRef != leg.positionRef || claim.expectedPositionRef != expectedPositionRef) return null
        val previous = run.legs[key]
        if (previous?.positionRef != expectedPositionRef) return null
        if (previous?.superseded != null) return null
        leg.entryOrderId = entryOrderId
        if (claim.heldPosition != null && previous != null) {
            if (previous.status == "open" && (previous.exitKind == null || previous.exitClaimToken == null)) return null
            if (previous.status == "open") {
                leg.superseded = Superseded(
                    previous.exitOrderId, previous.exitClaimToken, previous.exitKind, previous.entryOrderId,
                    previous.positionRef, previous.position, previous.entryAvg, previous.qty,
                )
            }
        }
        if (previous != null) leg.realizedPnl = previous.realizedPnl
        run.legs[key] = leg
        return leg
    }

    /** Apply one signal entry acknowledgement to its installed incarnation only, and drop the claim. */
    fun finishSignalEntry(run: RunState, legId: Int, positionRef: String?, claimToken: String, accepted: Boolean): Boolean {
        val leg = run.leg(legId)
        val claim = run.signalEntryClaims[legId.toString()]
        if (leg == null || leg.positionRef != positionRef || claim == null || claim.claimToken != claimToken) return false
        if (leg.entryStatus == "pending") {
            leg.entryStatus = if (accepted) "open" else "rejected"
            leg.status = if (accepted) "open" else "rejected"
        }
        run.signalEntryClaims.remove(legId.toString())
        return true
    }

    // ------------------------------------------------------------------ fills

    /** What one fill did. [ignored] names why it was not applied. */
    class FillResult(val ignored: String? = null, val wentFlat: Boolean = false, val entryApplied: Boolean = false, val warnings: List<String> = emptyList())

    /** `(applied, remaining)` whole quantities for one owner. */
    fun exitFillQuantities(filledQty: Int?, heldQty: Int?): Pair<Int, Int> {
        val held = maxOf(0, heldQty ?: 0)
        val applied = if (filledQty == null) held else minOf(maxOf(0, filledQty), held)
        return applied to held - applied
    }

    /**
     * Record a fill against a leg. Port of `engine._apply_fill`'s locked
     * section. Entry fills set the price every stop is measured from and
     * reconcile the size with what actually traded; exit fills book realized
     * P&L and close the leg. A fill naming an order the leg is not waiting on
     * belongs to a replaced incarnation and is ignored rather than allowed to
     * close or re-price the live position.
     */
    fun applyFill(
        run: RunState, legId: Int, avgPrice: Double?, isEntry: Boolean, filledQty: Int? = null, orderId: Long? = null,
        positionRef: String? = null, cumulativeFilledQty: Int? = null, terminal: Boolean = true, allowPriorCorrection: Boolean = false,
    ): FillResult {
        val warnings = ArrayList<String>()
        val leg = run.leg(legId) ?: return FillResult(ignored = "no such leg")
        val s = leg.superseded
        val settlesSuperseded = !isEntry && s != null && (
            (positionRef != null && s.positionRef == positionRef) ||
                (positionRef == null && s.exitOrderId == orderId) ||
                (positionRef == null && orderId == null && leg.exitOrderId == null)
            )
        var entryApplied = false
        if (settlesSuperseded) {
            val entry = s!!.entryAvg ?: 0.0
            val (applied, remaining) = exitFillQuantities(filledQty, s.qty)
            val sign = if (s.position == "B") 1.0 else -1.0
            if (entry > 0.0 && avgPrice != null) leg.realizedPnl = leg.realizedPnl + (avgPrice - entry) * applied * sign
            val release = terminal && (orderId == null || s.exitOrderId == orderId)
            if (remaining > 0 || !release) {
                s.qty = remaining
                if (release) { s.exitOrderId = null; s.exitClaimToken = null; s.exitKind = null }
            } else {
                leg.superseded = null
            }
        } else {
            if (positionRef != null && leg.positionRef != positionRef) {
                return FillResult(ignored = "Ignoring a fill for position $positionRef on leg $legId: the live position is ${leg.positionRef}")
            }
            if (orderId != null) {
                val expected = if (isEntry) leg.entryOrderId else leg.exitOrderId
                if (expected != null && expected != orderId && !allowPriorCorrection) {
                    return FillResult(ignored = "Ignoring a fill for order $orderId on leg $legId: the leg is waiting on $expected")
                }
            }
            if (!isEntry && orderId != null && leg.exitKind == null && leg.exitOrderId == null && !allowPriorCorrection) {
                return FillResult(ignored = "Ignoring exit fill for order $orderId on leg $legId: it has no exit in flight")
            }
            if (isEntry) {
                if (avgPrice != null) leg.entryAvg = avgPrice
                else warnings.add("Leg $legId filled without a usable average price; managing its quantity with valuation unavailable")
                // Every later exit must use what actually filled.
                val managed = cumulativeFilledQty ?: filledQty
                if (managed != null && managed != leg.qty) {
                    warnings.add("Leg $legId filled $managed of ${leg.qty}; managing the filled size")
                    leg.qty = managed
                }
                if (cumulativeFilledQty != null) leg.entryFilledQty = cumulativeFilledQty
                leg.entryStatus = if (terminal) "complete" else "open"
                leg.status = "open"
                entryApplied = terminal
            } else {
                if (avgPrice != null) leg.exitAvg = avgPrice
                val entry = leg.entryAvg
                val (applied, remaining) = exitFillQuantities(filledQty, leg.qty)
                val sign = if (leg.position == "B") 1.0 else -1.0
                if (applied > 0 && entry > 0.0 && avgPrice != null) {
                    leg.realizedPnl = leg.realizedPnl + (avgPrice - entry) * applied * sign
                } else if (applied > 0) {
                    // A missing price cannot be invented; book nothing for that quantity.
                    warnings.add("Leg $legId exited without complete fill pricing; booking no realized P&L for that quantity")
                }
                val release = terminal && (orderId == null || leg.exitOrderId == orderId)
                if (remaining > 0) {
                    leg.qty = remaining
                    leg.status = "open"
                } else {
                    leg.qty = 0
                    leg.status = "closed"
                    leg.mtm = 0.0
                }
                if (release) { leg.exitOrderId = null; leg.exitClaimToken = null; leg.exitKind = null }
            }
        }
        // Recomputed now so the figures finalise writes are the ones this fill produced.
        val (realized, unrealized) = RiskAdapter.runPnl(run)
        run.pnlRealized = realized
        run.pnlUnrealized = unrealized
        run.pnlTotal = realized + unrealized
        run.pnlPeak = RiskValues.pyMax(run.pnlPeak, run.pnlTotal)
        run.pnlTrough = RiskValues.pyMin(run.pnlTrough, run.pnlTotal)
        return FillResult(wentFlat = !run.requiresManagement(), entryApplied = entryApplied, warnings = warnings)
    }

    // ------------------------------------------------------------------- tick

    /** What one tick decided: legs to exit, a basket stop reason, and the events to record. */
    class TickDecision(val legExits: List<Pair<Int, String>>, val stopReason: String?, val events: List<Event>)

    private fun round2(x: Double): Double =
        if (x.isFinite()) BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toDouble() else x

    /**
     * A floor as Python prints it: the configured floor wins ties in the
     * ratchet's `max()`, so a floor equal to it keeps the literal's type.
     */
    private fun floorText(floor: Double?, def: StrategyDef): String {
        val lock = def.lockProfit
        if (floor != null && lock != null && lock.lockProfitWhole && floor == lock.lockProfit) return floor.toLong().toString()
        return Py.str(floor)
    }

    /** The daily loss limit as a positive number, or null. */
    fun dailyLossLimit(def: StrategyDef): Double? {
        val limit = def.dailyLossLimitInr ?: return null
        if (limit == 0.0) return null
        val v = kotlin.math.abs(limit)
        return if (v > 0) v else null
    }

    /**
     * Whether this session's loss has reached the daily limit: earlier runs'
     * banked figure plus this run marked now. A limit that only counted closed
     * runs would let an open one exceed it unnoticed; one reset per run would
     * let three losing runs start a fourth.
     */
    fun dailyLossBreached(def: StrategyDef, banked: Double?, run: RunState): String? {
        val limit = dailyLossLimit(def) ?: return null
        if (banked == null) return null
        val dayTotal = banked + run.pnlTotal
        if (dayTotal > -limit) return null
        return "Daily loss limit reached: the session is down ${RiskValues.fixed(kotlin.math.abs(dayTotal), 2)} against a limit of ${RiskValues.fixed(limit, 2)}"
    }

    /**
     * Evaluate one price against every open leg on that instrument, then the
     * basket. Port of the locked section of `_process_tick_for_run`: per-leg
     * decisions, trail-to-entry on a stop, the aggregate rules, and the daily
     * loss limit. [banked] is what earlier runs this session realised (null
     * when there is no daily limit); it is read before evaluation, never
     * during, exactly as upstream keeps the query out of the lock.
     */
    fun evaluateTick(run: RunState, def: StrategyDef, symbol: String, exchange: String, ltp: Double, banked: Double?): TickDecision {
        val legExits = ArrayList<Pair<Int, String>>()
        val events = ArrayList<Event>()
        for (leg in run.legs.values.filter { it.status == "open" && it.symbol == symbol && it.exchange == exchange }) {
            val decision = RiskAdapter.evaluateLeg(leg, ltp)
            if (decision.trailArmed) {
                events.add(Event("leg_trail_armed", "Trailing stop armed on leg ${leg.legId} at ${Py.str(decision.stopPrice)}", legId = leg.legId))
            }
            val kind = decision.reason?.let { EXIT_KIND_FOR_REASON[it] }
            if (decision.breached && kind != null) {
                legExits.add(leg.legId to kind)
                events.add(Event(if (decision.reason == BreachReason.STOP) "leg_sl_hit" else "leg_target_hit", decision.detail, "warn", leg.legId))
            }
        }
        if (def.trailSlToEntry) {
            for ((legId, kind) in legExits) {
                if (kind == STOP_DRIVEN_EXIT) {
                    val moved = RiskAdapter.trailOpenLegsToEntry(run, legId)
                    if (moved.isNotEmpty()) {
                        events.add(Event("trail_to_entry_activated", "Stop on leg $legId moved ${moved.size} other legs to entry", "warn"))
                    }
                    break
                }
            }
        }
        val aggregate = RiskAdapter.evaluateRun(run, def)
        if (aggregate.lockArmedNow) {
            events.add(Event("lock_profit_armed", "Lock profit armed with a floor of ${floorText(aggregate.lockFloor, def)}"))
        } else if (aggregate.lockFloorRaised) {
            events.add(Event("lock_profit_floor_advanced", "Lock profit floor advanced to ${floorText(aggregate.lockFloor, def)}"))
        }

        var stopReason: String? = null
        val dayLoss = dailyLossBreached(def, banked, run)
        val aggregateStop = aggregate.reason?.let { STOP_REASON_FOR_REASON[it] }
        if (dayLoss != null) {
            stopReason = "daily_loss_limit"
            events.add(Event("overall_sl_hit", dayLoss, "critical"))
        } else if (aggregate.breached && aggregateStop != null) {
            stopReason = aggregateStop
            val threshold = when (aggregateStop) {
                "overall_sl" -> -kotlin.math.abs(def.overallSlMtm!!)
                "overall_target" -> def.overallTargetMtm!!
                // Judged against the ratcheted floor of this evaluation, not the configured start.
                else -> aggregate.lockFloor!!
            }
            val payload = linkedMapOf<String, Any?>(
                "trigger_total" to round2(aggregate.totalPnl),
                "reason" to stopReason,
                "threshold" to round2(threshold),
                "triggering_tick" to linkedMapOf("symbol" to symbol, "exchange" to exchange, "ltp" to ltp),
                // The exact latest-known marks the decision saw; no invented timestamps.
                "legs" to run.legs.values.filter { it.status == "open" || it.realizedPnl != 0.0 }.map {
                    linkedMapOf(
                        "symbol" to it.symbol, "exchange" to it.exchange, "ltp" to it.ltp, "mtm" to round2(it.mtm),
                        "tick_source" to it.tickSource, "qty" to it.qty, "position" to it.position,
                    )
                },
            )
            val kind = mapOf("overall_sl" to "overall_sl_hit", "overall_target" to "overall_target_hit", "lock_profit" to "lock_profit_triggered")[stopReason]!!
            events.add(Event(kind, aggregate.detail, "warn", payload = payload))
        }
        return TickDecision(legExits, stopReason, events)
    }
}
