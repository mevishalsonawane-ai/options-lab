package com.optionslab.engine.strategy

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

private fun hhmm(t: LocalTime?): String? = t?.let { "%02d:%02d".format(it.hour, it.minute) }
private fun time(s: String?): LocalTime? = s?.let { LocalTime.parse(if (it.length == 4) "0$it" else it) }
private val DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

/**
 * [StrategyDef] to and from JSON, in exactly the shape IraAlgo's validator
 * produces (`strategy_kind`, `legs[].atm_offset`, `scheduler.days: ["MON"]`,
 * times as `"HH:MM"`), so a definition exported by the platform loads here and
 * one saved here would load there. Optional fields are omitted when unset, as
 * the Python omits them, rather than written as null.
 */
object StrategyCodec {
    fun encode(def: StrategyDef): String = Json.write(toMap(def))

    fun decode(json: String): StrategyDef {
        @Suppress("UNCHECKED_CAST")
        return fromMap(Json.parse(json) as Map<String, Any?>)
    }

    fun legToMap(leg: LegDef, kind: StrategyKind): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("id" to leg.id.toLong(), "segment" to leg.segment.wire)
        if (kind == StrategyKind.SIGNAL) {
            m["symbol"] = leg.symbol
            m["exchange"] = leg.exchange
            m["side"] = (leg.side ?: LegSide.BOTH).wire
            m["qty_mode"] = leg.qtyMode?.wire
            m["qty"] = leg.qty?.toLong()
        } else {
            m["position"] = leg.position?.wire
            m["lots"] = leg.lots.toLong()
            leg.optionType?.let { m["option_type"] = it.wire }
            leg.strikeMode?.let { m["strike_mode"] = it.wire }
            leg.atmOffset?.let { m["atm_offset"] = it }
            leg.strike?.let { m["strike"] = it }
            leg.strikeInt?.let { m["strike_int"] = it }
        }
        leg.expiry?.let { m["expiry"] = it }
        leg.slPts?.let { m["sl_pts"] = it }
        leg.targetPts?.let { m["target_pts"] = it }
        leg.trail?.let { m["trail"] = linkedMapOf("x" to it.x, "y" to it.y) }
        m["risk_unit"] = leg.riskUnit.wire
        return m
    }

    fun toMap(def: StrategyDef): Map<String, Any?> = linkedMapOf(
        "id" to def.id,
        "name" to def.name,
        "strategy_kind" to def.kind.wire,
        "direction" to def.direction.wire,
        "universe_tab" to def.universeTab.wire,
        "underlying" to def.underlying,
        "underlying_exchange" to def.underlyingExchange,
        "strategy_type" to def.strategyType.wire,
        "entry_time" to hhmm(def.entryTime),
        "exit_time" to hhmm(def.exitTime),
        "product" to def.product.wire,
        "pricetype" to def.pricetype,
        "legs" to def.legs.map { legToMap(it, def.kind) },
        "overall_sl_mtm" to def.overallSlMtm,
        "overall_target_mtm" to def.overallTargetMtm,
        "lock_profit" to def.lockProfit?.let {
            linkedMapOf<String, Any?>(
                "mode" to it.mode.wire, "if_profit_reaches" to it.ifProfitReaches,
                "lock_profit" to if (it.lockProfitWhole) it.lockProfit.toLong() else it.lockProfit,
            )
                .also { m -> it.trailStep?.let { s -> m["trail_step"] = s } }
        },
        "trail_sl_to_entry" to def.trailSlToEntry,
        "scheduler" to def.scheduler?.let {
            linkedMapOf(
                "enabled" to it.enabled,
                "days" to it.days.map { d -> DAYS[d.value - 1] },
                "start_time" to hhmm(it.startTime),
                "auto_stop_time" to hhmm(it.autoStopTime),
                "default_mode" to it.defaultMode.wire,
            )
        },
        "daily_loss_limit_inr" to def.dailyLossLimitInr,
        "webhook_ip_allowlist" to def.webhookIpAllowlist,
        "live_enabled" to def.liveEnabled,
    )

    private fun num(v: Any?): Double? = (v as Number?)?.toDouble()

    @Suppress("UNCHECKED_CAST")
    fun legFromMap(m: Map<String, Any?>, kind: StrategyKind): LegDef {
        val o = JsonObj(m)
        val trail = o.obj("trail")?.let { Trail(it.dbl("x") ?: 0.0, it.dbl("y") ?: 0.0) }
        return LegDef(
            id = o.int("id") ?: throw Json.ParseError("leg without id"),
            segment = wireOf<Segment>(o.str("segment")) ?: throw Json.ParseError("bad segment ${o.str("segment")}"),
            position = wireOf<Position>(o.str("position")),
            lots = o.int("lots") ?: 1,
            optionType = wireOf<OptionType>(o.str("option_type")),
            strikeMode = wireOf<StrikeMode>(o.str("strike_mode")),
            atmOffset = o.str("atm_offset"),
            strike = o.dbl("strike"),
            expiry = o.str("expiry"),
            symbol = o.str("symbol"),
            exchange = o.str("exchange"),
            side = if (kind == StrategyKind.SIGNAL) wireOf<LegSide>(o.str("side")) ?: LegSide.BOTH else null,
            qty = o.int("qty"),
            qtyMode = wireOf<QtyMode>(o.str("qty_mode")),
            slPts = o.dbl("sl_pts"),
            targetPts = o.dbl("target_pts"),
            trail = trail,
            riskUnit = wireOf<RiskUnit>(o.str("risk_unit")) ?: RiskUnit.POINTS,
            strikeInt = o.dbl("strike_int"),
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun fromMap(m: Map<String, Any?>): StrategyDef {
        val o = JsonObj(m)
        val kind = wireOf<StrategyKind>(o.str("strategy_kind")) ?: StrategyKind.BATCH
        val lock = o.obj("lock_profit")?.let {
            LockProfit(
                wireOf<LockProfitMode>(it.str("mode")) ?: LockProfitMode.LOCK,
                it.dbl("if_profit_reaches") ?: 0.0, it.dbl("lock_profit") ?: 0.0, it.dbl("trail_step"),
                it.m["lock_profit"] is Long,
            )
        }
        val sched = o.obj("scheduler")?.let { s ->
            SchedulerConfig(
                enabled = s.bool("enabled") ?: false,
                days = (s.list("days") ?: emptyList()).map { DayOfWeek.of(DAYS.indexOf(it.toString().uppercase()) + 1) }.sorted(),
                startTime = time(s.str("start_time")),
                autoStopTime = time(s.str("auto_stop_time")),
                defaultMode = wireOf<RunMode>(s.str("default_mode")) ?: RunMode.SANDBOX,
            )
        }
        return StrategyDef(
            id = o.long("id") ?: 0,
            name = o.reqStr("name"),
            kind = kind,
            direction = wireOf<Direction>(o.str("direction")) ?: Direction.BOTH,
            universeTab = wireOf<UniverseTab>(o.str("universe_tab")) ?: UniverseTab.WEEKLY_MONTHLY,
            underlying = o.reqStr("underlying"),
            underlyingExchange = o.reqStr("underlying_exchange"),
            strategyType = wireOf<StrategyType>(o.str("strategy_type")) ?: StrategyType.INTRADAY,
            entryTime = time(o.str("entry_time")),
            exitTime = time(o.str("exit_time")),
            product = wireOf<Product>(o.str("product")) ?: Product.NRML,
            pricetype = o.str("pricetype") ?: "MARKET",
            legs = (o.list("legs") ?: emptyList()).map { legFromMap(it as Map<String, Any?>, kind) },
            overallSlMtm = num(m["overall_sl_mtm"]),
            overallTargetMtm = num(m["overall_target_mtm"]),
            lockProfit = lock,
            trailSlToEntry = o.bool("trail_sl_to_entry") ?: false,
            scheduler = sched,
            dailyLossLimitInr = num(m["daily_loss_limit_inr"]),
            liveEnabled = o.bool("live_enabled") ?: false,
            webhookIpAllowlist = o.list("webhook_ip_allowlist")?.map { it.toString() },
        )
    }
}

/**
 * [RunState] to and from JSON. Legs and the run's P&L fields use the Python
 * run-state keys (`entry_avg`, `effective_sl`, `pnl_peak`, `lock_floor`, ...),
 * which is also the shape of a `sm_strategy_checkpoint.leg_state`, so a
 * checkpoint moves between the platform and the phone without translation.
 * The durable run-row facts (mode, stop request, final reason) and the order
 * registry ride alongside under their own keys.
 */
object RunStateCodec {
    fun encode(run: RunState): String = Json.write(toMap(run))

    fun decode(json: String): RunState {
        @Suppress("UNCHECKED_CAST")
        return fromMap(Json.parse(json) as Map<String, Any?>)
    }

    fun legToMap(l: LegState): Map<String, Any?> = linkedMapOf(
        "leg_id" to l.legId.toLong(),
        "position" to l.position,
        "symbol" to l.symbol,
        "exchange" to l.exchange,
        "lots" to l.lots.toLong(),
        "qty" to l.qty.toLong(),
        "position_ref" to l.positionRef,
        "entry_order_id" to l.entryOrderId,
        "entry_status" to l.entryStatus,
        "entry_filled_qty" to l.entryFilledQty.toLong(),
        "entry_avg" to l.entryAvg,
        "exit_order_id" to l.exitOrderId,
        "exit_claim_token" to l.exitClaimToken,
        "exit_kind" to l.exitKind,
        "exit_avg" to l.exitAvg,
        "ltp" to l.ltp,
        "mtm" to l.mtm,
        "realized_pnl" to l.realizedPnl,
        "status" to l.status,
        "tick_source" to l.tickSource,
        "risk_unit" to l.riskUnit,
        "sl_pts" to l.slPts,
        "target_pts" to l.targetPts,
        "trail_x" to l.trailX,
        "trail_y" to l.trailY,
        "effective_sl" to l.effectiveSl,
        "effective_target" to l.effectiveTarget,
        "trail_active" to l.trailActive,
        "highest_price" to l.highestPrice,
        "lowest_price" to l.lowestPrice,
        "superseded" to l.superseded?.let {
            linkedMapOf(
                "exit_order_id" to it.exitOrderId, "exit_claim_token" to it.exitClaimToken, "exit_kind" to it.exitKind,
                "entry_order_id" to it.entryOrderId, "position_ref" to it.positionRef, "position" to it.position,
                "entry_avg" to it.entryAvg, "qty" to it.qty?.toLong(),
            )
        },
    )

    /** Reads a Python leg dict too: ids may be ints or strings, numbers ints or floats. */
    @Suppress("UNCHECKED_CAST")
    fun legFromMap(m: Map<String, Any?>): LegState {
        val o = JsonObj(m)
        fun id(k: String): Long? = when (val v = m[k]) { null -> null; is Number -> v.toLong(); else -> v.toString().toLongOrNull() }
        return LegState(
            legId = (m["leg_id"] as? Number)?.toInt() ?: m["leg_id"].toString().toInt(),
            position = o.reqStr("position"),
            symbol = o.str("symbol") ?: "",
            exchange = o.str("exchange") ?: "",
            lots = o.int("lots") ?: 1,
            qty = o.int("qty") ?: 0,
            positionRef = o.str("position_ref"),
            entryOrderId = id("entry_order_id"),
            entryStatus = o.str("entry_status") ?: "pending",
            entryFilledQty = o.int("entry_filled_qty") ?: 0,
            entryAvg = o.dbl("entry_avg") ?: 0.0,
            exitOrderId = id("exit_order_id"),
            exitClaimToken = o.str("exit_claim_token"),
            exitKind = o.str("exit_kind"),
            exitAvg = o.dbl("exit_avg"),
            ltp = o.dbl("ltp"),
            mtm = o.dbl("mtm") ?: 0.0,
            realizedPnl = o.dbl("realized_pnl") ?: 0.0,
            status = o.str("status") ?: "configured",
            tickSource = o.str("tick_source") ?: "ws",
            riskUnit = o.str("risk_unit") ?: "points",
            slPts = o.dbl("sl_pts"),
            targetPts = o.dbl("target_pts"),
            trailX = o.dbl("trail_x") ?: 0.0,
            trailY = o.dbl("trail_y") ?: 0.0,
            effectiveSl = o.dbl("effective_sl"),
            effectiveTarget = o.dbl("effective_target"),
            trailActive = o.bool("trail_active") ?: false,
            highestPrice = o.dbl("highest_price"),
            lowestPrice = o.dbl("lowest_price"),
            superseded = o.obj("superseded")?.let { s ->
                val sm = s.m
                Superseded(
                    (sm["exit_order_id"] as? Number)?.toLong(), s.str("exit_claim_token"), s.str("exit_kind"),
                    (sm["entry_order_id"] as? Number)?.toLong(), s.str("position_ref"), s.str("position"), s.dbl("entry_avg"), s.int("qty"),
                )
            },
        )
    }

    private fun orderToMap(o: OrderRecord): Map<String, Any?> = linkedMapOf(
        "id" to o.id, "leg_id" to o.legId.toLong(), "kind" to o.kind, "action" to o.action, "qty" to o.qty.toLong(),
        "symbol" to o.symbol, "exchange" to o.exchange, "product" to o.product, "position_ref" to o.positionRef,
        "exit_owner" to o.exitOwner, "signal" to o.signal, "status" to o.status, "filled_qty" to o.filledQty.toLong(),
        "avg_fill_price" to o.avgFillPrice, "reject_reason" to o.rejectReason, "cancel_requested" to o.cancelRequested,
    )

    private fun orderFromMap(m: Map<String, Any?>): OrderRecord {
        val o = JsonObj(m)
        return OrderRecord(
            o.long("id")!!, o.int("leg_id")!!, o.reqStr("kind"), o.reqStr("action"), o.int("qty") ?: 0, o.str("symbol") ?: "",
            o.str("exchange") ?: "", o.str("product") ?: "", o.str("position_ref"), o.str("exit_owner"), o.bool("signal") ?: false,
            o.str("status") ?: "pending", o.int("filled_qty") ?: 0, o.dbl("avg_fill_price"), o.str("reject_reason"),
            o.bool("cancel_requested") ?: false,
        )
    }

    fun toMap(r: RunState): Map<String, Any?> = linkedMapOf(
        "run_id" to r.runId,
        "strategy_id" to r.strategyId,
        "mode" to r.mode.wire,
        "strategy_kind" to r.kind.wire,
        "status" to r.status.name.lowercase(),
        "pnl_realized" to r.pnlRealized,
        "pnl_unrealized" to r.pnlUnrealized,
        "pnl_total" to r.pnlTotal,
        "pnl_peak" to r.pnlPeak,
        "pnl_trough" to r.pnlTrough,
        "lock_armed" to r.lockArmed,
        "lock_floor" to r.lockFloor,
        "trail_to_entry_active" to r.trailToEntryActive,
        "tick_source_degraded" to r.tickSourceDegraded,
        "stopping" to r.stopping,
        "signal_entry_claims" to r.signalEntryClaims.mapValues { (_, c) ->
            linkedMapOf(
                "claim_token" to c.claimToken, "position_ref" to c.positionRef, "position" to c.position,
                "held_position" to c.heldPosition, "expected_position_ref" to c.expectedPositionRef,
            )
        },
        "legs" to r.legs.mapValues { legToMap(it.value) },
        "orders" to r.orders.values.map { orderToMap(it) },
        "started_at" to r.startedAt?.toString(),
        "stop_requested_at" to r.stopRequestedAt?.toString(),
        "stop_requested_reason" to r.stopRequestedReason,
        "stopped_at" to r.stoppedAt?.toString(),
        "stop_reason" to r.stopReason,
        "start_error" to r.startError,
        "next_id" to r.nextId,
    )

    @Suppress("UNCHECKED_CAST")
    fun fromMap(m: Map<String, Any?>): RunState {
        val o = JsonObj(m)
        val r = RunState(
            runId = o.long("run_id") ?: 0,
            strategyId = o.long("strategy_id") ?: 0,
            mode = wireOf<RunMode>(o.str("mode")) ?: RunMode.SANDBOX,
            kind = wireOf<StrategyKind>(o.str("strategy_kind")) ?: StrategyKind.BATCH,
            status = o.str("status")?.let { s -> RunStatus.entries.firstOrNull { it.name.lowercase() == s } } ?: RunStatus.IDLE,
            pnlRealized = o.dbl("pnl_realized") ?: 0.0,
            pnlUnrealized = o.dbl("pnl_unrealized") ?: 0.0,
            pnlTotal = o.dbl("pnl_total") ?: 0.0,
            pnlPeak = o.dbl("pnl_peak") ?: 0.0,
            pnlTrough = o.dbl("pnl_trough") ?: 0.0,
            lockArmed = o.bool("lock_armed") ?: false,
            lockFloor = o.dbl("lock_floor"),
            trailToEntryActive = o.bool("trail_to_entry_active") ?: false,
            tickSourceDegraded = o.bool("tick_source_degraded") ?: false,
            stopping = o.bool("stopping") ?: false,
            startedAt = o.str("started_at")?.let { ZonedDateTime.parse(it) },
            stopRequestedAt = o.str("stop_requested_at")?.let { ZonedDateTime.parse(it) },
            stopRequestedReason = o.str("stop_requested_reason"),
            stoppedAt = o.str("stopped_at")?.let { ZonedDateTime.parse(it) },
            stopReason = o.str("stop_reason"),
            startError = o.str("start_error"),
            nextId = o.long("next_id") ?: 1,
        )
        (m["signal_entry_claims"] as Map<String, Any?>?)?.forEach { (k, v) ->
            val c = JsonObj(v as Map<String, Any?>)
            r.signalEntryClaims[k] = SignalClaim(c.reqStr("claim_token"), c.reqStr("position_ref"), c.reqStr("position"), c.str("held_position"), c.str("expected_position_ref"))
        }
        (m["legs"] as Map<String, Any?>?)?.forEach { (k, v) -> r.legs[k] = legFromMap(v as Map<String, Any?>) }
        (m["orders"] as List<Any?>?)?.forEach { v -> orderFromMap(v as Map<String, Any?>).let { r.orders[it.id] = it } }
        return r
    }
}
