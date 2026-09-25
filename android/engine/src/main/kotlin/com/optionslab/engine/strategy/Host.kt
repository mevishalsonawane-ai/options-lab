package com.optionslab.engine.strategy

import com.optionslab.engine.risk.Side
import java.time.ZonedDateTime

/**
 * Drives [StrategyRuntime] against a real order pipe. The runtime is pure: it
 * returns actions; this carries them out through an [Executor] (the paper
 * account or Zerodha), feeds every acknowledgement and fill straight back in,
 * and keeps going until the runtime asks for nothing more. The app persists
 * the returned state after every [drive].
 *
 * [brokerIds] maps the runtime's own order ids to the executor's, so a later
 * cancel or status poll reaches the right order; it is persisted with the run.
 */
class StrategyHost(private val rt: StrategyRuntime = StrategyRuntime()) {

    /** What happened to one order the executor was asked to place. */
    sealed interface Placed {
        /** Accepted by the venue; [status] is the venue's word at the moment it answered. */
        data class Accepted(val brokerId: String, val status: String, val filledQty: Int, val avgPrice: Double?, val message: String? = null) : Placed
        /** Never reached the venue, or refused outright. */
        data class Refused(val reason: String) : Placed
    }

    /** One order's state as the venue reports it now. */
    data class Status(val status: String, val filledQty: Int, val avgPrice: Double?, val message: String? = null)

    interface Executor {
        fun place(order: Action.PlaceOrder): Placed
        fun cancel(brokerId: String): Boolean
        fun status(brokerId: String): Status?
        fun event(e: Event, alert: Boolean)
    }

    private val terminal = setOf("complete", "cancelled", "rejected")

    /**
     * [checkpoint] is called after every order the venue accepted, so the
     * app can persist the run (and its broker ids) before the next order goes
     * out - a process killed mid-basket must not forget an order it placed.
     */
    fun drive(run0: RunState, def: StrategyDef, actions0: List<Action>, exec: Executor, now: ZonedDateTime,
              brokerIds: MutableMap<Long, String>, checkpoint: (RunState) -> Unit = {}): RunState {
        var run = run0
        // BUY orders go first, entries and exits alike: a wing is bought before the short
        // it covers is sold, and a short is bought back before the wing that covered it is sold.
        val queue = ArrayDeque(actions0.sortedBy { if (it is Action.PlaceOrder && it.side == Side.SELL) 1 else 0 })
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 500) {
            when (val a = queue.removeFirst()) {
                is Action.PlaceOrder -> {
                    val placed = unhedged(run, a)?.let { Placed.Refused(it) } ?: exec.place(a)
                    val (r1, x1) = when (val p = placed) {
                        is Placed.Refused -> rt.onOrderAck(run, def, a.orderId, false, p.reason, now)
                        is Placed.Accepted -> {
                            brokerIds[a.orderId] = p.brokerId
                            val (acked, ax) = rt.onOrderAck(run, def, a.orderId, true, now = now)
                            val (upd, ux) = rt.onOrderUpdate(acked, def,
                                OrderUpdate(a.orderId, p.status.lowercase(), p.filledQty, p.avgPrice, p.message), now)
                            upd to (ax + ux)
                        }
                    }
                    run = r1; queue.addAll(x1)
                    if (placed is Placed.Accepted) checkpoint(run)
                }
                is Action.CancelOrder -> {
                    val id = brokerIds[a.orderId]
                    if (id != null && exec.cancel(id)) {
                        val st = exec.status(id)
                        val (r1, x1) = rt.onOrderUpdate(run, def,
                            OrderUpdate(a.orderId, st?.status?.lowercase() ?: "cancelled", st?.filledQty, st?.avgPrice, st?.message), now)
                        run = r1; queue.addAll(x1)
                    }
                }
                is Action.Log -> exec.event(a.event, false)
                is Action.Alert -> exec.event(a.event, true)
                is Action.Subscribe, is Action.Unsubscribe -> Unit   // prices are polled for run.subscribedSymbols()
            }
        }
        return run
    }

    /**
     * Why this SELL must not go out now, or null. IraAlgo sends each leg on its
     * own; on a phone holding real money these two orders are refused instead:
     *
     *  - a SELL entry while any BUY entry of the run has not completely filled
     *    (refused, rejected by the exchange, cancelled or still working): the
     *    short would be unhedged;
     *  - during a stop, a SELL that closes a long while any short of the run is
     *    still held: selling the wing first leaves the short naked. The stop
     *    stays pending and the SELL is retried once the short is closed.
     */
    private fun unhedged(run: RunState, a: Action.PlaceOrder): String? {
        if (a.side != Side.SELL) return null
        if (a.kind == "entry") {
            val bad = run.legs.values.firstOrNull { it.position == "B" && it.entryStatus != "complete" }
            if (bad != null) return "not sent: the BUY leg ${bad.symbol} is ${bad.entryStatus}, and this SELL would be unhedged"
            return null
        }
        if (run.stopRequestedReason != null) {
            val shortOpen = run.legs.values.firstOrNull { it.position == "S" && it.status == "open" }
            if (shortOpen != null) return "held back: ${shortOpen.symbol} (short) is still open; the wing is sold once it is closed"
        }
        return null
    }

    /** Re-read every order the venue has not finished, and feed what changed back in. */
    fun poll(run0: RunState, def: StrategyDef, exec: Executor, now: ZonedDateTime, brokerIds: MutableMap<Long, String>,
             checkpoint: (RunState) -> Unit = {}): RunState {
        var run = run0
        for ((id, rec) in run0.orders) {
            if (rec.status in terminal) continue
            val bid = brokerIds[id] ?: continue
            val st = exec.status(bid) ?: continue
            val (r1, x1) = rt.onOrderUpdate(run, def, OrderUpdate(id, st.status.lowercase(), st.filledQty, st.avgPrice, st.message), now)
            run = drive(r1, def, x1, exec, now, brokerIds, checkpoint)
        }
        return run
    }

    fun start(def: StrategyDef, resolved: List<ResolvedLeg>, now: ZonedDateTime, runId: Long, mode: RunMode, trigger: String,
              exec: Executor, brokerIds: MutableMap<Long, String>, checkpoint: (RunState) -> Unit = {}): RunState {
        val (run, actions) = rt.start(def, resolved, now, runId, mode, trigger)
        checkpoint(run)   // the intent is durable before the first order goes out
        return drive(run, def, actions, exec, now, brokerIds, checkpoint)
    }

    /**
     * Poll unfinished orders, evaluate the tick, and - while a stop is pending
     * (a refused exit, a wing held back behind its short) - retry it, as
     * upstream's scheduler does every few seconds.
     */
    fun tick(run: RunState, def: StrategyDef, quotes: List<Quote>, now: ZonedDateTime, banked: Double?, exec: Executor,
             brokerIds: MutableMap<Long, String>, checkpoint: (RunState) -> Unit = {}): RunState {
        val polled = poll(run, def, exec, now, brokerIds, checkpoint)
        val (r, actions) = rt.onTick(polled, def, quotes, now, banked)
        var next = drive(r, def, actions, exec, now, brokerIds, checkpoint)
        if (next.stopRequestedReason != null && next.stoppedAt == null) {
            val (rr, ra) = rt.reconcilePendingStop(next, def, now)
            next = drive(rr, def, ra, exec, now, brokerIds, checkpoint)
        }
        return next
    }

    fun stop(run: RunState, def: StrategyDef, now: ZonedDateTime, reason: String, exec: Executor, brokerIds: MutableMap<Long, String>,
             checkpoint: (RunState) -> Unit = {}): RunState {
        val (r, actions) = rt.closeAll(run, def, now, reason)
        return drive(r, def, actions, exec, now, brokerIds, checkpoint)
    }

    fun closeLeg(run: RunState, def: StrategyDef, legId: Int, now: ZonedDateTime, exec: Executor, brokerIds: MutableMap<Long, String>,
                 checkpoint: (RunState) -> Unit = {}): RunState {
        val (r, actions) = rt.closeLeg(run, def, legId, now)
        return drive(r, def, actions, exec, now, brokerIds, checkpoint)
    }
}

/** The keys the Strategy Module's create/update endpoint accepts. */
private val EDITABLE = setOf(
    "name", "direction", "universe_tab", "underlying", "underlying_exchange", "strategy_type", "entry_time",
    "exit_time", "product", "pricetype", "legs", "overall_sl_mtm", "overall_target_mtm", "lock_profit",
    "trail_sl_to_entry", "scheduler", "daily_loss_limit_inr", "webhook_ip_allowlist", "strategy_kind",
)

/**
 * Validate a definition built by the app's editor exactly as IraAlgo validates
 * a submitted form; on success the id and live switch are carried over.
 */
fun StrategyValidator.check(def: StrategyDef, lotSizeFor: (String, String) -> Int? = { _, _ -> null }): StrategyValidator.Result =
    when (val r = validate(StrategyCodec.toMap(def).filterKeys { it in EDITABLE }.filterValues { it != null }, lotSizeFor)) {
        is StrategyValidator.Result.Ok -> StrategyValidator.Result.Ok(r.def.copy(id = def.id, liveEnabled = def.liveEnabled))
        is StrategyValidator.Result.Invalid -> r
    }
