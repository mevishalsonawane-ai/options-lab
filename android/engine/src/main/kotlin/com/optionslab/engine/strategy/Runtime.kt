package com.optionslab.engine.strategy

import com.optionslab.engine.risk.RiskValues
import com.optionslab.engine.risk.Side
import java.time.ZonedDateTime

/**
 * What the runtime asks the app to do. The runtime never performs I/O: it
 * records the intent in the returned [RunState] first (the Python's "durable
 * intent before the broker is called") and then asks for it here.
 */
sealed interface Action {
    /**
     * Place one MARKET order. [orderId] is the runtime's own id for the intent;
     * the app quotes it back in [StrategyRuntime.onOrderAck] and
     * [StrategyRuntime.onOrderUpdate]. [product] is already translated to what
     * the venue accepts.
     */
    data class PlaceOrder(
        val orderId: Long,
        val legId: Int,
        val kind: String,
        val symbol: String,
        val exchange: String,
        val side: Side,
        val quantity: Int,
        val product: String,
        val priceType: String,
        val strategyName: String,
        val positionRef: String?,
    ) : Action

    /** Cancel a working entry: possible future exposure, not a position to reverse at full size. */
    data class CancelOrder(val orderId: Long, val legId: Int) : Action

    /** Start prices for these `(symbol, exchange)` instruments before the entries go out. */
    data class Subscribe(val instruments: List<Pair<String, String>>) : Action

    /** Release this run's price subscriptions. */
    data class Unsubscribe(val instruments: List<Pair<String, String>>) : Action

    /** An audit event at `info` or `warn`. */
    data class Log(val event: Event) : Action

    /** An audit event at `critical`: something the operator must see, e.g. a refused exit with the position still held. */
    data class Alert(val event: Event) : Action
}

/** A tick: one instrument's last price. */
data class Quote(val symbol: String, val exchange: String, val ltp: Double)

/**
 * One cumulative broker order frame, as brokers report it: the status and the
 * total filled so far, not a delta. [status] is the broker's own word
 * (`complete`, `filled`, `traded`, `cancelled`, `rejected`, `open`, ...).
 */
data class OrderUpdate(
    val orderId: Long,
    val status: String,
    val filledQty: Int? = null,
    val averagePrice: Double? = null,
    val rejectionReason: String? = null,
)

/** What one signal did, or why it did nothing. `note` answers a no-op, which is success, never a failure. */
data class SignalResult(
    val ok: Boolean,
    val note: String? = null,
    val error: String? = null,
    val legId: Int? = null,
    val runId: Long? = null,
    val flipped: Boolean = false,
) {
    /** Whether an order was placed. */
    val acted: Boolean get() = ok && note == null
}

/** A signal's outcome: the run it acted on (possibly newly opened), and a stale run it rolled, if any. */
data class SignalOutcome(val result: SignalResult, val run: RunState?, val rolledRun: RunState?, val actions: List<Action>)

/**
 * The Strategy Module's run lifecycle as pure functions. Port of
 * `services/strategy_module/engine.py` (start, stop, close_leg, fills, the
 * tick path) and `signals.py` (one leg per alert), over [RunState].
 *
 * Time, quotes, acknowledgements and fills are always inputs; orders, cancels,
 * subscriptions and audit events are always outputs. Every function copies
 * the state it is given and returns the copy, so the app can persist each
 * returned state (see [RunStateCodec]) and replay safely.
 *
 * Lifecycle: [start] places every entry (BUY legs before SELL legs, so a
 * spread's short leg is not refused for margin it would have had); acks and
 * fills move legs to open; [onTick] evaluates per-leg and basket risk and
 * exits; [closeAll] / [closeLeg] are manual; a run finalises only once every
 * exact owner is confirmed flat.
 */
class StrategyRuntime {
    private class Ctx(val run: RunState, val def: StrategyDef?, val now: ZonedDateTime) {
        val actions = ArrayList<Action>()
        fun emit(e: Event) { actions.add(if (e.severity == "critical") Action.Alert(e) else Action.Log(e)) }
        fun done(): Pair<RunState, List<Action>> { run.deriveStatus(); return run to actions }
    }

    // ------------------------------------------------------------------ start

    /**
     * Start a batch run: every leg must already be resolved (see
     * [SymbolResolver.resolve]); a single failure refuses the whole start,
     * because a leg refused after its siblings are filled leaves a position
     * nobody chose. A refused start returns an IDLE state carrying
     * [RunState.startError] and one Alert.
     */
    fun start(
        def: StrategyDef, resolved: List<ResolvedLeg>, now: ZonedDateTime, runId: Long,
        mode: RunMode = RunMode.SANDBOX, triggerSource: String = "manual",
    ): Pair<RunState, List<Action>> {
        val run = RunState(runId, def.id, mode, def.kind)
        val c = Ctx(run, def, now)
        fun refuse(error: String): Pair<RunState, List<Action>> {
            run.startError = error
            c.emit(Event("start_refused", error, "critical"))
            return c.done()
        }
        // A signal strategy's run opens on its first entry signal, never on a start.
        if (def.kind == StrategyKind.SIGNAL) {
            return refuse("A signal strategy has no start. Its run opens on the first long_entry or short_entry signal after the session boundary.")
        }
        // Checked here as well as by every caller: this is the last point before real orders.
        if (mode == RunMode.LIVE && !def.liveEnabled) return refuse("This strategy is not enabled for live trading. Enable it first.")
        if (def.legs.isEmpty()) return refuse("The strategy has no legs")
        if (def.legs.size > StrategyDef.MAX_LEGS) return refuse("A strategy takes at most ${StrategyDef.MAX_LEGS} legs, got ${def.legs.size}")
        val byLeg = resolved.associateBy { it.legId }
        for (leg in def.legs) {
            val r = byLeg[leg.id] ?: return refuse("Leg ${leg.id}: it was not resolved")
            if (!r.ok) return refuse("Leg ${leg.id}: ${r.error}")
            if (leg.position == null) return refuse("Leg ${leg.id} has an unusable position: None")
        }
        for (leg in def.legs) {
            val r = byLeg.getValue(leg.id)
            run.legs[leg.id.toString()] = Engine.newLegState(
                leg.id, leg.position!!.wire, r.symbol!!, r.exchange!!, r.lots ?: leg.lots, r.quantity!!,
                leg.slPts, leg.targetPts, leg.trail, leg.riskUnit.wire, run.token("p"),
            )
        }
        run.startedAt = now
        // Prices before entries: a fill can land in milliseconds, and an
        // unsubscribed leg would sit with no price and so no stop.
        c.actions.add(Action.Subscribe(run.legs.values.map { it.symbol to it.exchange }.distinct()))
        c.emit(Event("run_started", "Run started in ${mode.wire} mode ($triggerSource)"))

        val ordered = def.legs.sortedBy { if (it.position == Position.B) 0 else 1 }
        for (legDef in ordered) {
            val r = byLeg.getValue(legDef.id)
            val leg = run.leg(legDef.id)!!
            if (r.detail["expiry_fallback"] == true) {
                // Said out loud: next_week quietly trading this week is a different trade.
                c.emit(Event("leg_expiry_fallback", "Leg ${leg.legId} asked for the ${r.detail["expiry_rank"]} expiry; the chain lists only ${r.expiry}, which was used", "warn", leg.legId))
            }
            val action = if (leg.position == "B") "BUY" else "SELL"
            leg.entryOrderId = placeOrder(c, leg.legId, "entry", action, leg.qty, leg.symbol, leg.exchange, leg.positionRef, null, def.pricetype, signal = false)
        }
        return c.done()
    }

    /** Record the intent, then ask for it. Returns the order id. */
    private fun placeOrder(
        c: Ctx, legId: Int, kind: String, action: String, qty: Int, symbol: String, exchange: String,
        positionRef: String?, exitOwner: String?, priceType: String, signal: Boolean,
    ): Long {
        val def = c.def!!
        val id = c.run.orderId()
        val product = OrderRules.productForExchange(def.product.wire, exchange)
        c.run.orders[id] = OrderRecord(id, legId, kind, action, qty, symbol, exchange, product, positionRef, exitOwner, signal)
        c.actions.add(Action.PlaceOrder(id, legId, kind, symbol, exchange, if (action == "BUY") Side.BUY else Side.SELL, qty, product, priceType, def.name, positionRef))
        return id
    }

    // ------------------------------------------------------------------- acks

    /**
     * The broker (or sandbox) answered a placement: accepted means the order
     * reached it, not that it filled. A refused entry marks its leg rejected;
     * if every entry of a batch start was refused the run finalises as
     * `error` with the venue's own words. A refused exit releases its claim so
     * the stop, target, square-off and Close button can all retry it.
     */
    fun onOrderAck(run: RunState, def: StrategyDef, orderId: Long, accepted: Boolean, error: String? = null, now: ZonedDateTime): Pair<RunState, List<Action>> {
        val c = Ctx(run.deepCopy(), def, now)
        val r = c.run
        val order = r.orders[orderId] ?: return c.also { it.emit(Event("order_unknown", "No order $orderId on run ${r.runId}", "warn")) }.done()
        val signal = order.signal
        if (order.status == "pending") {
            order.status = if (accepted) "open" else "rejected"
            order.rejectReason = if (accepted) null else error
        }
        val leg = r.leg(order.legId)
        if (order.kind == "entry") {
            if (signal) {
                val claim = r.signalEntryClaims[order.legId.toString()]
                if (claim != null) Engine.finishSignalEntry(r, order.legId, order.positionRef, claim.claimToken, accepted)
                c.emit(
                    Event(
                        "leg_entry_placed", "Signal ${order.action} ${order.qty} ${order.symbol}" + (if (accepted) "" else " rejected: $error"),
                        if (accepted) "info" else "warn", order.legId,
                    ),
                )
                if (!accepted) reconcilePendingStopInto(c)
            } else {
                if (leg != null && leg.positionRef == order.positionRef && leg.entryStatus == "pending") {
                    leg.entryOrderId = orderId
                    leg.entryStatus = if (accepted) "open" else "rejected"
                    leg.status = if (accepted) "open" else "rejected"
                }
                c.emit(
                    if (accepted) Event("leg_entry_placed", "Entry ${order.action} ${order.qty} ${order.symbol} placed", "info", order.legId)
                    else Event("leg_entry_rejected", "Entry rejected on leg ${order.legId}: $error", "warn", order.legId),
                )
                // Every leg refused: nothing to manage, so the run must not read as running.
                val entries = r.orders.values.filter { it.kind == "entry" && !it.signal }
                if (entries.none { it.status == "pending" } && entries.none { it.status != "rejected" && it.status != "cancelled" } && r.stoppedAt == null) {
                    if (finalise(c, "error", "No entry order was accepted")) {
                        c.emit(Event("start_failed", rejectionSummary(entries), "critical"))
                    }
                } else if (!accepted) {
                    reconcilePendingStopInto(c)
                }
            }
        } else {
            if (!accepted) {
                if (order.exitOwner == "superseded") {
                    if (Engine.releaseSupersededExit(r, order.legId, orderId)) {
                        c.emit(Event("flip_outgoing_exit_rejected", "The exit for the outgoing side of a flip on leg ${order.legId} was refused; that position is still held", "critical", order.legId))
                    }
                } else {
                    Engine.releaseLegExit(r, order.legId, orderId)
                }
            }
            c.emit(
                if (accepted) Event("leg_exit_placed", if (signal) "Signal ${order.action} ${order.qty} ${order.symbol}" else "Exit ${order.action} ${order.qty} ${order.symbol} placed (${order.kind})", "info", order.legId)
                else Event("leg_exit_rejected", if (signal) "Signal ${order.action} ${order.qty} ${order.symbol} rejected: $error" else "Exit rejected on leg ${order.legId}: $error", "critical", order.legId),
            )
            if (!accepted && r.stopRequestedReason != null && r.requiresManagement()) {
                c.emit(Event("run_stop_failed", "Stop refused for 1 position(s); the run remains open, managed, and retryable", "critical"))
            }
        }
        return c.done()
    }

    /** One distinct refusal reads as itself; several are listed per leg. */
    private fun rejectionSummary(entries: List<OrderRecord>): String {
        val reasons = LinkedHashMap<String, MutableList<Int>>()
        for (o in entries) {
            val reason = (o.rejectReason ?: "").trim()
            if (reason.isNotEmpty()) reasons.getOrPut(reason) { ArrayList() }.add(o.legId)
        }
        if (reasons.isEmpty()) return "Every entry order was rejected"
        if (reasons.size == 1) return "Every entry order was rejected: ${reasons.keys.first()}"
        return "Every entry order was rejected. " + reasons.entries.joinToString("; ") { (reason, legs) -> "leg ${legs.joinToString(", ")}: $reason" }
    }

    // ------------------------------------------------------------------ fills

    /**
     * Fold one cumulative broker frame into its order and apply the new fill,
     * if any. Port of `order_events._apply_update` over
     * `fold_order_broker_frame`: quantity evidence only ever grows, a working
     * frame never reopens a terminal order, and a `complete` frame with no
     * quantity means the whole order traded. An exit's price is the price of
     * the newly reported quantity only, derived from the two averages.
     */
    fun onOrderUpdate(run: RunState, def: StrategyDef, update: OrderUpdate, now: ZonedDateTime): Pair<RunState, List<Action>> {
        val c = Ctx(run.deepCopy(), def, now)
        val r = c.run
        val order = r.orders[update.orderId] ?: return c.done()
        val broker = (update.status).trim().lowercase().replace("_", " ")
        val incomingStatus = when (broker) {
            in FILLED -> "complete"
            "cancelled", "canceled" -> "cancelled"
            "rejected" -> "rejected"
            else -> "open"
        }
        val incomingQty = update.filledQty?.takeIf { it > 0 } ?: 0
        val incomingPrice = update.averagePrice?.takeIf { RiskValues.isPrice(it) }

        val previousStatus = order.status
        val previousQty = order.filledQty
        val previousPrice = order.avgFillPrice
        val evidence = if (incomingStatus == "complete" && incomingQty <= 0) order.qty else incomingQty
        val cumulative = maxOf(previousQty, evidence)
        val delta = cumulative - previousQty
        val nextStatus = when {
            previousStatus == "complete" -> "complete"
            previousStatus in DEAD -> if (incomingStatus == "complete" && delta > 0) "complete" else previousStatus
            incomingStatus in TERMINAL -> incomingStatus
            else -> "open"
        }
        if (nextStatus == previousStatus && delta <= 0) return c.done()
        order.status = nextStatus
        if (delta > 0) { order.filledQty = cumulative; order.avgFillPrice = incomingPrice }
        if (update.rejectionReason != null && nextStatus in DEAD) order.rejectReason = update.rejectionReason
        val terminal = nextStatus in TERMINAL
        val wasTerminal = previousStatus in TERMINAL
        val isEntry = order.kind == "entry"
        val avgNow = if (delta > 0) incomingPrice else previousPrice

        val shouldApply = delta > 0 || (terminal && cumulative > 0)
        if (shouldApply && isEntry && delta > 0 && r.stoppedAt != null) {
            // A late entry fill on a finished run: exposure the run no longer manages.
            c.emit(Event("run_stop_failed", "A late broker entry fill for leg ${order.legId} arrived after the run finished. The fill requires immediate manual broker reconciliation.", "critical", order.legId))
            return c.done()
        }
        if (shouldApply) {
            val price = if (isEntry) avgNow else incrementalPrice(delta, cumulative, avgNow, previousQty, previousPrice)
            applyFillInto(
                c, order.legId, price, isEntry, delta, order.id, order.positionRef,
                cumulative = if (isEntry && (!terminal || previousQty > 0)) cumulative else null,
                terminal = terminal, allowPriorCorrection = wasTerminal,
            )
            if (delta > 0 && price == null) {
                c.emit(Event("fill_unpriced", "Leg ${order.legId} filled $cumulative without a usable price; its valuation is unavailable until the broker reports one", "critical", order.legId))
            }
        }
        if (nextStatus in DEAD && !wasTerminal) {
            if (isEntry && cumulative <= 0) {
                // Zero fill: the entry will never be a position.
                val leg = r.leg(order.legId)
                if (leg != null && (order.positionRef == null || leg.positionRef == order.positionRef) && leg.entryStatus != "complete") {
                    leg.entryStatus = nextStatus
                    leg.status = "rejected"
                }
                r.signalEntryClaims.remove(order.legId.toString())
                reconcilePendingStopInto(c)
            } else if (!isEntry) {
                val owner = if (!shouldApply) Engine.releaseOrderExit(r, order.legId, order.id, order.positionRef) else order.exitOwner
                val leg = r.leg(order.legId)
                val stillHeld = when (owner) {
                    "superseded" -> leg?.superseded != null
                    "live" -> leg != null && leg.status == "open"
                    else -> false
                }
                if (stillHeld) {
                    c.emit(Event("leg_exit_rejected", "The exit for leg ${order.legId} ended as $nextStatus; the position is still held and remains managed", "critical", order.legId))
                }
            }
        }
        return c.done()
    }

    private fun incrementalPrice(delta: Int, cumulative: Int, cumulativePrice: Double?, previousQty: Int, previousPrice: Double?): Double? {
        if (delta <= 0) return null
        val cum = cumulativePrice?.takeIf { RiskValues.isPrice(it) } ?: return null
        val prev = previousPrice?.takeIf { RiskValues.isPrice(it) }
        if (previousQty <= 0 || prev == null) return cum
        return ((cum * cumulative - prev * previousQty) / delta).takeIf { RiskValues.isPrice(it) }
    }

    /**
     * Apply a fill directly, for an app whose broker reports deltas rather than
     * cumulative frames. [filledQty] null means "the whole held quantity".
     */
    fun onFill(
        run: RunState, def: StrategyDef, orderId: Long, avgPrice: Double?, filledQty: Int? = null,
        terminal: Boolean = true, now: ZonedDateTime,
    ): Pair<RunState, List<Action>> {
        val c = Ctx(run.deepCopy(), def, now)
        val order = c.run.orders[orderId] ?: return c.done()
        if (terminal && order.status != "complete") order.status = "complete"
        if (filledQty != null) order.filledQty += filledQty
        applyFillInto(c, order.legId, avgPrice, order.kind == "entry", filledQty, orderId, order.positionRef, null, terminal, false)
        return c.done()
    }

    /** `_apply_fill` plus what it does after releasing the lock: reconcile a pending stop, or finalise a flat run. */
    private fun applyFillInto(
        c: Ctx, legId: Int, price: Double?, isEntry: Boolean, filledQty: Int?, orderId: Long, positionRef: String?,
        cumulative: Int?, terminal: Boolean, allowPriorCorrection: Boolean,
    ) {
        val r = c.run
        val result = Engine.applyFill(r, legId, price, isEntry, filledQty, orderId, positionRef, cumulative, terminal, allowPriorCorrection)
        result.ignored?.let { c.emit(Event("fill_ignored", it, "warn", legId)); return }
        result.warnings.forEach { c.emit(Event("fill_note", it, "warn", legId)) }
        if (result.entryApplied) {
            // The entry may have filled after a stop found it unfilled: claim its exact size now.
            reconcilePendingStopInto(c)
            return
        }
        if (!result.wentFlat || r.stoppedAt != null) return
        val requested = r.stopRequestedReason
        // A signal run is a trading day: a leg going flat mid-session is ordinary.
        if (requested == null && r.kind == StrategyKind.SIGNAL) return
        finalise(c, requested ?: "manual", if (requested != null) "Run stopped ($requested)" else "All legs closed")
    }

    // ------------------------------------------------------------------- tick

    /**
     * Evaluate quotes against the run, in order: per-leg stop, target and
     * trail; trail-to-entry when a stop fires and the strategy asks for it;
     * combined stop, combined target and lock profit; and the daily loss limit
     * against [bankedSessionPnl] (what earlier runs this session realised; see
     * [sessionBankedPnl]). A basket breach stops the whole run; a leg breach
     * exits that leg.
     *
     * [enforceExitTime] also squares off an intraday run once IST time reaches
     * its `exit_time`: upstream does this with a scheduler job at that minute
     * (see [Scheduler.plannedJobs]); doing it on the tick path as well means a
     * phone that missed the job's slot still gets flat. The stop is idempotent.
     */
    fun onTick(
        run: RunState, def: StrategyDef, quotes: List<Quote>, now: ZonedDateTime,
        bankedSessionPnl: Double? = null, enforceExitTime: Boolean = true,
    ): Pair<RunState, List<Action>> {
        val c = Ctx(run.deepCopy(), def, now)
        val r = c.run
        if (r.stoppedAt != null || r.startedAt == null) return c.done()
        if (enforceExitTime && def.strategyType == StrategyType.INTRADAY && def.exitTime != null &&
            r.stopRequestedReason == null && !now.withZoneSameInstant(IST).toLocalTime().isBefore(def.exitTime)
        ) {
            stopInto(c, "scheduler")
        }
        for (q in quotes) {
            if (r.stoppedAt != null) break
            val banked = if (Engine.dailyLossLimit(def) != null) bankedSessionPnl ?: 0.0 else null
            val decision = Engine.evaluateTick(r, def, q.symbol, q.exchange, q.ltp, banked)
            decision.events.forEach { c.emit(it) }
            if (decision.stopReason != null) {
                // A basket breach closes everything; the per-leg exits it also triggered are redundant.
                stopInto(c, decision.stopReason)
                continue
            }
            for ((legId, kind) in decision.legExits) exitLegsInto(c, listOf(legId), kind)
        }
        return c.done()
    }

    // ------------------------------------------------------------------ exits

    /**
     * Exit the named legs at MARKET, with the symbol the run holds - never a
     * re-resolved one, which hours later can name a different strike and open
     * a new position instead of closing one. Returns per-leg outcomes: an
     * unfilled entry is a refusal ("retry once it fills"), not a silent skip.
     */
    private fun exitLegsInto(c: Ctx, legIds: List<Int>, kind: String): List<Pair<Int, String?>> {
        val r = c.run
        val (claimed, unfilled) = Engine.claimLegsForExit(r, legIds, kind)
        val outcomes = ArrayList<Pair<Int, String?>>()
        for (leg in claimed) {
            val action = OrderRules.exitAction(leg.position)
            val id = placeOrder(c, leg.legId, kind, action, leg.qty, leg.symbol, leg.exchange, leg.positionRef, "live", OrderRules.EXIT_PRICETYPE, signal = false)
            Engine.bindLiveExit(r, leg.legId, leg.exitClaimToken, id, leg.positionRef)
            outcomes.add(leg.legId to null)
        }
        // A flip can leave an outgoing position under `superseded`; a stop must manage both owners.
        for (legId in legIds) {
            val s = r.leg(legId)?.superseded ?: continue
            val claim = Engine.claimSupersededExit(r, legId, s.position) ?: continue
            val id = placeOrder(c, legId, kind, OrderRules.exitAction(claim.position), claim.quantity ?: 0, claim.symbol, claim.exchange, claim.positionRef, "superseded", OrderRules.EXIT_PRICETYPE, signal = false)
            Engine.bindSupersededExit(r, legId, claim.claimToken, id)
            outcomes.add(legId to null)
        }
        for (leg in unfilled) {
            outcomes.add(leg.legId to "The entry for this leg has been accepted but not filled, so there is no confirmed quantity to exit. Retry once it fills.")
        }
        return outcomes
    }

    /**
     * Request a stop, exit every owned position, and finalise only once flat.
     * The first reason requested is the one kept. Working entries are
     * cancelled rather than reversed at full size, and the stop stays pending
     * until each either cancels (flat) or fills (then exited).
     */
    private fun stopInto(c: Ctx, reason: String): Boolean {
        val r = c.run
        if (r.stoppedAt != null) return false
        if (r.stopRequestedReason == null) {
            r.stopRequestedReason = reason
            r.stopRequestedAt = c.now
        }
        val persisted = r.stopRequestedReason!!
        // Blocks new signal entries from here on.
        r.stopping = true
        c.emit(Event("run_stop_requested", "Stop requested ($persisted); exit orders are being attempted"))

        for (leg in r.legs.values) {
            val entryId = leg.entryOrderId ?: continue
            val order = r.orders[entryId] ?: continue
            if (leg.status == "open" && leg.entryStatus == "open" && order.status == "open" && !order.cancelRequested) {
                order.cancelRequested = true
                c.actions.add(Action.CancelOrder(entryId, leg.legId))
            }
        }
        val kind = if (persisted == "manual") "exit_close_all" else "exit_$persisted".takeIf { it in Engine.ORDER_KINDS } ?: "exit_close_all"
        val outcomes = exitLegsInto(c, r.managedLegIds(), kind)
        val refused = outcomes.filter { (legId, err) ->
            // A working entry whose cancel is in flight is pending, not refused.
            err != null && r.leg(legId)?.entryOrderId?.let { r.orders[it]?.cancelRequested } != true
        }
        if (refused.isNotEmpty() && r.requiresManagement()) {
            c.emit(Event("run_stop_failed", "Stop refused for ${refused.size} position(s); the run remains open, managed, and retryable", "critical"))
            return false
        }
        if (r.requiresManagement()) return true
        return finalise(c, persisted, "Run stopped ($persisted)")
    }

    /**
     * Close every leg and stop the run (the Python `stop_run`, with reason
     * `manual` for the operator's Close All, `scheduler` for a square-off,
     * `eod` for a stale signal run). Safe to call again: a refused exit is
     * retried, an in-flight one is not duplicated.
     */
    fun closeAll(run: RunState, def: StrategyDef, now: ZonedDateTime, reason: String = "manual"): Pair<RunState, List<Action>> {
        val c = Ctx(run.deepCopy(), def, now)
        if (c.run.stoppedAt != null) {
            c.emit(Event("run_stop_failed", "Run is not active", "warn"))
            return c.done()
        }
        stopInto(c, reason)
        return c.done()
    }

    /**
     * Continue a durable stop: re-attempt refused exits and finalise a run
     * that has gone flat. Upstream's scheduler does this every five seconds for
     * every pending stop; the app should call it on a similar cadence.
     */
    fun reconcilePendingStop(run: RunState, def: StrategyDef, now: ZonedDateTime): Pair<RunState, List<Action>> {
        val c = Ctx(run.deepCopy(), def, now)
        reconcilePendingStopInto(c)
        return c.done()
    }

    private fun reconcilePendingStopInto(c: Ctx) {
        val r = c.run
        if (r.stoppedAt != null) return
        val reason = r.stopRequestedReason ?: return
        stopInto(c, reason)
    }

    /**
     * Exit one leg; the run continues with the rest. Deliberately does not
     * trail the other legs to entry: that answers the market moving against
     * the book, and an operator's close is an override.
     */
    fun closeLeg(run: RunState, def: StrategyDef, legId: Int, now: ZonedDateTime): Pair<RunState, List<Action>> {
        val c = Ctx(run.deepCopy(), def, now)
        if (c.run.stoppedAt != null) {
            c.emit(Event("leg_exit_rejected", "Run is not active", "warn", legId))
            return c.done()
        }
        val outcomes = exitLegsInto(c, listOf(legId), "exit_leg_manual")
        when {
            outcomes.isEmpty() -> c.emit(Event("leg_exit_rejected", "That leg is not open", "warn", legId))
            outcomes.any { it.second != null } -> c.emit(Event("leg_exit_rejected", "Exit refused: " + outcomes.mapNotNull { it.second }.joinToString("; "), "warn", legId))
            else -> c.emit(Event("leg_close_manual", "Operator requested closure of leg $legId", legId = legId))
        }
        return c.done()
    }

    /**
     * Close the run: only once nothing is held or working. Peak and trough
     * are kept on every path, not only one of several.
     */
    private fun finalise(c: Ctx, reason: String, message: String): Boolean {
        val r = c.run
        if (r.stoppedAt != null || r.requiresManagement()) return false
        r.stoppedAt = c.now
        r.stopReason = reason
        c.emit(Event("run_stopped", message))
        c.actions.add(Action.Unsubscribe(r.legs.values.map { it.symbol to it.exchange }.distinct()))
        return true
    }

    // ----------------------------------------------------------------- signal

    /**
     * Apply one alert (`long_entry`, `long_exit`, `short_entry`, `short_exit`)
     * to one leg. Port of `signals.handle_signal`.
     *
     * A signal strategy has one run per trading session: [run] is the current
     * one or null. When there is none (or it is finished), a run [newRunId] is
     * opened, live only if the strategy has opted in. When [run] belongs to an
     * earlier session it is rolled through an `eod` stop first; only confirmed
     * flatness allows the new run, otherwise the signal acts on the stale run.
     *
     * Repeats are no-ops that answer success with a note (`already_long`,
     * `no_matching_position`, `outside_trading_window`), because a refusal that
     * reads as a failure invites the retry that turns one alert into two
     * positions.
     */
    fun onSignal(
        run: RunState?, def: StrategyDef, action: String, instruments: MasterContract, now: ZonedDateTime, newRunId: Long,
        legId: Int? = null, symbol: String? = null, exchange: String? = null,
    ): SignalOutcome {
        val gate = Signals.gate(def, action, legId, symbol, exchange, now)
        if (gate.result != null) return SignalOutcome(gate.result, run, null, emptyList())
        val leg = gate.leg!!
        val side = gate.side!!
        val actions = ArrayList<Action>()

        // The session's run, rolling a stale one.
        var rolled: RunState? = null
        var current: RunState? = run?.takeIf { it.stoppedAt == null }
        if (current != null && current.startedAt != null && Session.sessionDay(current.startedAt!!) < Session.sessionDay(now)) {
            val (stopped, stopActions) = closeAll(current, def, now, "eod")
            actions.addAll(stopActions)
            if (stopped.stoppedAt != null) {
                actions.add(Action.Log(Event("eod_squareoff", "Previous day's run closed on the first signal of a new day", "warn")))
                rolled = stopped
                current = null
            } else {
                current = stopped
            }
        }
        if (current == null) {
            val mode = if (def.liveEnabled) RunMode.LIVE else RunMode.SANDBOX
            current = RunState(newRunId, def.id, mode, StrategyKind.SIGNAL, startedAt = now)
            actions.add(Action.Log(Event("run_started", "Signal run opened in ${mode.wire} mode")))
        }

        val c = Ctx(current.deepCopy(), def, now)
        val result = if (action in Signals.ENTRIES) {
            if (c.run.stopRequestedReason != null) SignalResult(false, note = "run_stopping", legId = leg.id, runId = c.run.runId)
            else enter(c, leg, side, instruments)
        } else {
            exit(c, leg, side)
        }
        val (finalRun, more) = c.done()
        actions.addAll(more)
        if (!result.ok && result.error != null) actions.add(Action.Log(Event("signal_rejected", result.error, "warn", leg.id)))
        return SignalOutcome(result, finalRun, rolled, actions)
    }

    private fun heldSide(r: RunState, legId: Int): String? {
        val live = r.leg(legId) ?: return null
        if (live.status != "open") return null
        return if (live.position == "B") "long" else "short"
    }

    /** Open a leg on the requested side, squaring the other side first when it is held. */
    private fun enter(c: Ctx, leg: LegDef, side: String, instruments: MasterContract): SignalResult {
        val r = c.run
        val position = if (side == "long") "B" else "S"
        val claimResult = Engine.claimSignalEntry(r, leg.id, position)
        claimResult.note?.let { return SignalResult(it != "run_stopping", note = it, legId = leg.id, runId = r.runId) }
        val claim = claimResult.claim!!
        var placed = false
        try {
            val held = when (claim.heldPosition) { "B" -> "long"; "S" -> "short"; else -> null }
            // Refused before anything is squared: a refusal must cost nothing.
            Signals.uncarryableShort(c.def!!, leg, side)?.let { return SignalResult(false, error = "Leg ${leg.id}: $it", legId = leg.id) }
            var flipped = false
            if (held != null) {
                val closed = exit(c, leg, held)
                if (!closed.ok || closed.note != null) return closed
                flipped = true
            }
            val (spec, error) = Signals.resolveSignalLeg(leg, side, instruments)
            if (error != null) return SignalResult(false, error = "Leg ${leg.id}: $error", legId = leg.id)
            val state = Engine.newLegState(
                leg.id, position, spec!!.symbol, spec.exchange, spec.lots, spec.quantity,
                leg.slPts, leg.targetPts, leg.trail, leg.riskUnit.wire, claim.positionRef,
            )
            val orderId = r.nextId
            if (Engine.addLeg(r, state, claim.claimToken, claim.expectedPositionRef, orderId) == null) {
                return SignalResult(false, error = "The position changed before its entry could be placed", legId = leg.id, runId = r.runId)
            }
            val id = placeOrder(c, leg.id, "entry", if (position == "B") "BUY" else "SELL", spec.quantity, spec.symbol, spec.exchange, claim.positionRef, null, c.def!!.pricetype, signal = true)
            check(id == orderId)
            // The claim is held until the placement is acknowledged (finishSignalEntry).
            placed = true
            return SignalResult(true, legId = leg.id, runId = r.runId, flipped = flipped)
        } finally {
            if (!placed && Engine.releaseSignalEntryClaim(r, leg.id, claim.claimToken)) reconcilePendingStopInto(c)
        }
    }

    /** Close a leg held on the requested side, or say it was not held. */
    private fun exit(c: Ctx, leg: LegDef, side: String): SignalResult {
        val r = c.run
        val position = if (side == "long") "B" else "S"
        if (heldSide(r, leg.id) != side) {
            // A flip whose closing order was refused leaves the outgoing side held.
            val outgoing = Engine.claimSupersededExit(r, leg.id, position)
            if (outgoing != null) {
                val id = placeOrder(c, leg.id, "exit_signal", OrderRules.exitAction(outgoing.position), outgoing.quantity ?: 0, outgoing.symbol, outgoing.exchange, outgoing.positionRef, "superseded", OrderRules.EXIT_PRICETYPE, signal = true)
                Engine.bindSupersededExit(r, leg.id, outgoing.claimToken, id)
                return SignalResult(true, legId = leg.id, runId = r.runId)
            }
            return SignalResult(true, note = "no_matching_position", legId = leg.id, runId = r.runId)
        }
        val snapshot = Engine.claimLegExit(r, leg.id, "exit_signal")
        if (snapshot == null) {
            val live = r.leg(leg.id)
            if (live != null && live.status == "open" && live.entryStatus != "complete") {
                return SignalResult(
                    false, legId = leg.id, runId = r.runId,
                    error = "The entry for this leg has been accepted but not filled, so there is no confirmed quantity to exit. Retry once it fills.",
                )
            }
            return SignalResult(true, note = "no_matching_position", legId = leg.id, runId = r.runId)
        }
        val id = placeOrder(c, leg.id, "exit_signal", OrderRules.exitAction(snapshot.position), snapshot.qty, snapshot.symbol, snapshot.exchange, snapshot.positionRef, "live", OrderRules.EXIT_PRICETYPE, signal = true)
        Engine.bindLiveExit(r, leg.id, snapshot.exitClaimToken, id, snapshot.positionRef)
        return SignalResult(true, legId = leg.id, runId = r.runId)
    }

    companion object {
        private val FILLED = setOf("complete", "completed", "filled", "executed", "traded")
        private val DEAD = setOf("cancelled", "rejected")
        private val TERMINAL = setOf("complete", "cancelled", "rejected")

        /**
         * What earlier runs of this strategy banked this session: the sum of
         * realised P&L over runs started since the session began, excluding
         * the live run, whose figure is read from its state instead.
         */
        fun sessionBankedPnl(runs: List<RunState>, strategyId: Long, excludeRunId: Long?, now: ZonedDateTime): Double {
            val since = Session.sessionStartedAt(now)
            return runs.filter {
                it.strategyId == strategyId && it.runId != excludeRunId && it.startedAt != null && !it.startedAt!!.isBefore(since)
            }.sumOf { it.pnlRealized }
        }
    }
}
