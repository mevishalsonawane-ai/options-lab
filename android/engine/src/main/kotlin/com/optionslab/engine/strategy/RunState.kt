package com.optionslab.engine.strategy

import java.time.ZonedDateTime

/**
 * The lifecycle a caller sees. Upstream keeps it implicit (run row
 * timestamps plus leg statuses); it is derived here by [RunState.deriveStatus]
 * after every call so the app can switch on it:
 *
 * - IDLE: never started (a refused start carries [RunState.startError]);
 * - ENTERING: entry orders are working and none is filled yet;
 * - ACTIVE: holding at least one filled leg, no stop requested;
 * - EXITING: a stop was requested and exposure or working orders remain;
 * - CLOSED: finalised; [RunState.stopReason] says why.
 */
enum class RunStatus { IDLE, ENTERING, ACTIVE, EXITING, CLOSED }

/**
 * A flip's outgoing position, kept while its closing order is unfilled. A
 * signal flip squares the held side and opens the other at once, so for that
 * window one leg id names two positions; overwriting the old one lost its
 * order id and let its exit fill close the NEW position.
 */
class Superseded(
    var exitOrderId: Long? = null,
    var exitClaimToken: String? = null,
    var exitKind: String? = null,
    var entryOrderId: Long? = null,
    var positionRef: String? = null,
    var position: String? = null,
    var entryAvg: Double? = null,
    var qty: Int? = null,
) {
    fun copy() = Superseded(exitOrderId, exitClaimToken, exitKind, entryOrderId, positionRef, position, entryAvg, qty)
}

/**
 * One leg's live state: field for field the Python leg dict in
 * `state._new_leg_state`, and serialised under the same keys by
 * [RunStateCodec], so a checkpoint round-trips between the two.
 *
 * Statuses: [status] is `configured` / `open` / `closed` / `rejected`;
 * [entryStatus] is `pending` / `open` (accepted, unfilled) / `complete` /
 * `rejected` / `cancelled`. A leg is "open" from the moment its entry is
 * accepted, but it can only be exited once the entry is `complete`: squaring
 * off an unfilled entry sends a naked position the other way if it cancels.
 */
class LegState(
    var legId: Int,
    var position: String,
    var symbol: String,
    var exchange: String,
    var lots: Int = 1,
    var qty: Int,
    var positionRef: String? = null,
    var entryOrderId: Long? = null,
    var entryStatus: String = "pending",
    var entryFilledQty: Int = 0,
    var entryAvg: Double = 0.0,
    var exitOrderId: Long? = null,
    var exitClaimToken: String? = null,
    var exitKind: String? = null,
    var exitAvg: Double? = null,
    var ltp: Double? = null,
    var mtm: Double = 0.0,
    var realizedPnl: Double = 0.0,
    var status: String = "configured",
    var tickSource: String = "ws",
    var riskUnit: String = "points",
    var slPts: Double? = null,
    var targetPts: Double? = null,
    var trailX: Double = 0.0,
    var trailY: Double = 0.0,
    var effectiveSl: Double? = null,
    var effectiveTarget: Double? = null,
    var trailActive: Boolean = false,
    var highestPrice: Double? = null,
    var lowestPrice: Double? = null,
    var superseded: Superseded? = null,
) {
    fun copy() = LegState(
        legId, position, symbol, exchange, lots, qty, positionRef, entryOrderId, entryStatus, entryFilledQty, entryAvg,
        exitOrderId, exitClaimToken, exitKind, exitAvg, ltp, mtm, realizedPnl, status, tickSource, riskUnit, slPts,
        targetPts, trailX, trailY, effectiveSl, effectiveTarget, trailActive, highestPrice, lowestPrice, superseded?.copy(),
    )

    /** An exit is in flight: claimed, or bound to an order. */
    val exitInFlight: Boolean get() = exitKind != null || exitClaimToken != null || exitOrderId != null

    /** How far the leg has moved in its favour, in points, for display. Derived from the price ratchet. */
    fun favorablePeakPoints(): Double {
        if (entryAvg == 0.0) return 0.0
        return if (position == "B") highestPrice?.takeIf { it != 0.0 }?.let { maxOf(0.0, it - entryAvg) } ?: 0.0
        else lowestPrice?.takeIf { it != 0.0 }?.let { maxOf(0.0, entryAvg - it) } ?: 0.0
    }
}

/** An in-flight signal entry decision, claimed before any I/O so one alert can never open two positions. */
class SignalClaim(
    val claimToken: String,
    val positionRef: String,
    val position: String,
    val heldPosition: String?,
    val expectedPositionRef: String?,
)

/**
 * One order intent: the phone's stand-in for a `sm_strategy_order` row. The
 * runtime records it before asking the app to place it (durable intent before
 * the broker is called), and every ack and fill names it by [id].
 * [filledQty]/[avgFillPrice] hold the cumulative broker facts that
 * [StrategyRuntime.onOrderUpdate] folds new frames into.
 */
class OrderRecord(
    val id: Long,
    val legId: Int,
    val kind: String,
    val action: String,
    val qty: Int,
    val symbol: String,
    val exchange: String,
    val product: String,
    val positionRef: String?,
    /** `live` or `superseded` for an exit; null for an entry. */
    val exitOwner: String?,
    /** Placed by the signal path, whose audit wording differs from the batch path's. */
    val signal: Boolean = false,
    var status: String = "pending",
    var filledQty: Int = 0,
    var avgFillPrice: Double? = null,
    var rejectReason: String? = null,
    /** A cancel was asked for (a working entry at stop); the stop waits for its outcome rather than calling it refused. */
    var cancelRequested: Boolean = false,
) {
    fun copy() = OrderRecord(id, legId, kind, action, qty, symbol, exchange, product, positionRef, exitOwner, signal, status, filledQty, avgFillPrice, rejectReason, cancelRequested)
}

/**
 * A run's whole state: the Python run dict (P&L ratchets, lock state, legs,
 * signal claims) plus the durable facts the Python keeps on the run row
 * (mode, stop request, final reason) and the order registry.
 *
 * Mutable, but never shared: every [StrategyRuntime] call deep-copies its
 * input and returns the copy, so a caller holding an earlier state never sees
 * it change. The mutation inside mirrors the Python line for line, which is
 * what keeps the port reviewable against it.
 *
 * [nextId] makes the runtime deterministic: order ids and claim tokens come
 * from it rather than from a clock or a UUID, so the same inputs always
 * produce the same actions.
 */
class RunState(
    var runId: Long,
    var strategyId: Long,
    var mode: RunMode = RunMode.SANDBOX,
    var kind: StrategyKind = StrategyKind.BATCH,
    var status: RunStatus = RunStatus.IDLE,
    var pnlRealized: Double = 0.0,
    var pnlUnrealized: Double = 0.0,
    var pnlTotal: Double = 0.0,
    var pnlPeak: Double = 0.0,
    var pnlTrough: Double = 0.0,
    var lockArmed: Boolean = false,
    var lockFloor: Double? = null,
    var trailToEntryActive: Boolean = false,
    var tickSourceDegraded: Boolean = false,
    var stopping: Boolean = false,
    val signalEntryClaims: LinkedHashMap<String, SignalClaim> = LinkedHashMap(),
    val legs: LinkedHashMap<String, LegState> = LinkedHashMap(),
    val orders: LinkedHashMap<Long, OrderRecord> = LinkedHashMap(),
    var startedAt: ZonedDateTime? = null,
    var stopRequestedAt: ZonedDateTime? = null,
    var stopRequestedReason: String? = null,
    var stoppedAt: ZonedDateTime? = null,
    var stopReason: String? = null,
    var startError: String? = null,
    var nextId: Long = 1,
) {
    fun deepCopy(): RunState {
        val c = RunState(
            runId, strategyId, mode, kind, status, pnlRealized, pnlUnrealized, pnlTotal, pnlPeak, pnlTrough, lockArmed,
            lockFloor, trailToEntryActive, tickSourceDegraded, stopping, LinkedHashMap(signalEntryClaims),
            LinkedHashMap(), LinkedHashMap(), startedAt, stopRequestedAt, stopRequestedReason, stoppedAt, stopReason,
            startError, nextId,
        )
        legs.forEach { (k, v) -> c.legs[k] = v.copy() }
        orders.forEach { (k, v) -> c.orders[k] = v.copy() }
        return c
    }

    fun leg(legId: Int): LegState? = legs[legId.toString()]

    /** A fresh opaque id: the port's `uuid4().hex`, deterministic. */
    internal fun token(prefix: String = "t"): String = "$prefix${nextId++}"

    internal fun orderId(): Long = nextId++

    /** The legs that are open and so still carry risk. */
    fun openLegs(): List<LegState> = legs.values.filter { it.status == "open" }

    /** Every `(symbol, exchange)` the run needs ticks for. */
    fun subscribedSymbols(): Set<Pair<String, String>> =
        legs.values.filter { it.status == "configured" || it.status == "open" }.map { it.symbol to it.exchange }.toSet()

    /** Whether a leg still owns exposure, or an entry that may become exposure. */
    fun legRequiresManagement(leg: LegState): Boolean =
        leg.superseded != null || leg.exitOrderId != null || leg.exitClaimToken != null ||
            leg.status == "open" || leg.entryStatus == "pending" || leg.entryStatus == "open"

    fun managedLegIds(): List<Int> = legs.values.filter { legRequiresManagement(it) }.map { it.legId }

    /** Whether any actual or working position keeps this run from being terminal. */
    fun requiresManagement(): Boolean = signalEntryClaims.isNotEmpty() || legs.values.any { legRequiresManagement(it) }

    /** Recompute [status] from the facts. See [RunStatus]. */
    fun deriveStatus(): RunStatus {
        status = when {
            stoppedAt != null -> RunStatus.CLOSED
            startedAt == null -> RunStatus.IDLE
            stopRequestedReason != null -> RunStatus.EXITING
            legs.values.any { it.status == "open" && it.entryStatus == "complete" } -> RunStatus.ACTIVE
            legs.values.any { it.entryStatus == "pending" || it.entryStatus == "open" } -> RunStatus.ENTERING
            else -> RunStatus.ACTIVE
        }
        return status
    }
}
