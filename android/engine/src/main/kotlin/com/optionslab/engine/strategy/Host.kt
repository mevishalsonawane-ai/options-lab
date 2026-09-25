package com.optionslab.engine.strategy

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

    fun drive(run0: RunState, def: StrategyDef, actions0: List<Action>, exec: Executor, now: ZonedDateTime,
              brokerIds: MutableMap<Long, String>): RunState {
        var run = run0
        val queue = ArrayDeque(actions0)
        var guard = 0
        // The runtime sends BUY entries before SELL entries so a spread is margined as one.
        // If a BUY (a hedge) is refused, IraAlgo would still send the SELL and leave a naked
        // short; here the run's remaining SELL entries are refused instead.
        var hedgeRefused = run0.legs.values.any { it.position == "B" && it.entryStatus == "rejected" }
        while (queue.isNotEmpty() && guard++ < 500) {
            when (val a = queue.removeFirst()) {
                is Action.PlaceOrder -> {
                    val placed = if (a.kind == "entry" && a.side == com.optionslab.engine.risk.Side.SELL && hedgeRefused)
                        Placed.Refused("not sent: a BUY leg of this basket was refused, and this SELL would be unhedged")
                    else exec.place(a)
                    if (placed is Placed.Refused && a.kind == "entry" && a.side == com.optionslab.engine.risk.Side.BUY) hedgeRefused = true
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

    /** Re-read every order the venue has not finished, and feed what changed back in. */
    fun poll(run0: RunState, def: StrategyDef, exec: Executor, now: ZonedDateTime, brokerIds: MutableMap<Long, String>): RunState {
        var run = run0
        for ((id, rec) in run0.orders) {
            if (rec.status in terminal) continue
            val bid = brokerIds[id] ?: continue
            val st = exec.status(bid) ?: continue
            val (r1, x1) = rt.onOrderUpdate(run, def, OrderUpdate(id, st.status.lowercase(), st.filledQty, st.avgPrice, st.message), now)
            run = drive(r1, def, x1, exec, now, brokerIds)
        }
        return run
    }

    fun start(def: StrategyDef, resolved: List<ResolvedLeg>, now: ZonedDateTime, runId: Long, mode: RunMode, trigger: String,
              exec: Executor, brokerIds: MutableMap<Long, String>): RunState {
        val (run, actions) = rt.start(def, resolved, now, runId, mode, trigger)
        return drive(run, def, actions, exec, now, brokerIds)
    }

    fun tick(run: RunState, def: StrategyDef, quotes: List<Quote>, now: ZonedDateTime, banked: Double?, exec: Executor,
             brokerIds: MutableMap<Long, String>): RunState {
        val polled = poll(run, def, exec, now, brokerIds)
        val (r, actions) = rt.onTick(polled, def, quotes, now, banked)
        return drive(r, def, actions, exec, now, brokerIds)
    }

    fun stop(run: RunState, def: StrategyDef, now: ZonedDateTime, reason: String, exec: Executor, brokerIds: MutableMap<Long, String>): RunState {
        val (r, actions) = rt.closeAll(run, def, now, reason)
        return drive(r, def, actions, exec, now, brokerIds)
    }

    fun closeLeg(run: RunState, def: StrategyDef, legId: Int, now: ZonedDateTime, exec: Executor, brokerIds: MutableMap<Long, String>): RunState {
        val (r, actions) = rt.closeLeg(run, def, legId, now)
        return drive(r, def, actions, exec, now, brokerIds)
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
