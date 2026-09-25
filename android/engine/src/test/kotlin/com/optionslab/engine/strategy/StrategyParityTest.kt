package com.optionslab.engine.strategy

import com.optionslab.engine.risk.Fixtures
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Strategy Module's pure logic must decide what IraAlgo's Python decides.
 * Every expectation was written by `src/test/resources/strategy/gen_strategy.py`
 * running the real code: the validator; the symbol resolver against a scratch
 * SQLite master contract (so the real expiry listing, strike ladder and lot
 * lookups ran); the scheduler's job planning; the session boundary; the
 * signal gates; and the engine's locked sections - `_process_tick_for_run`
 * with its database, broker and emit calls stubbed and captured, and
 * `apply_fill` with the state module's claim functions, over seeded op
 * sequences.
 */
@Suppress("UNCHECKED_CAST")
class StrategyParityTest {
    private fun m(v: Any?) = v as Map<String, Any?>
    private fun l(v: Any?) = v as List<Any?>

    private fun report(name: String, total: Int, failures: List<String>) {
        if (failures.isNotEmpty()) fail("$name: ${failures.size}/$total differ from Python:\n" + failures.take(5).joinToString("\n"))
        assertTrue(total > 0, "$name has no cases")
    }

    companion object {
        val resolverFixture by lazy { Fixtures.load("strategy/resolver.json.gz") }
        val contract by lazy {
            MasterContract(
                (resolverFixture["instruments"] as List<Map<String, Any?>>).map {
                    Instrument(
                        it["symbol"] as String, it["exchange"] as String, it["name"] as String?, it["expiry"] as String?,
                        (it["strike"] as Number?)?.toDouble(), (it["lotsize"] as Number?)?.toInt(), it["instrumenttype"] as String?,
                        (it["tick_size"] as Number?)?.toDouble(),
                    )
                },
            )
        }
        val today: LocalDate by lazy { LocalDate.parse(resolverFixture["today"] as String) }
    }

    private fun legDef(leg: Map<String, Any?>) = LegDef(
        id = 1,
        segment = wireOf<Segment>(leg["segment"] as String)!!,
        position = if (leg["action"] == "BUY") Position.B else Position.S,
        lots = (leg["lots"] as Number).toInt(),
        optionType = (leg["option_type"] as String?)?.let { wireOf<OptionType>(it) },
        strikeMode = (leg["strike_mode"] as String?)?.let { wireOf<StrikeMode>(it) },
        atmOffset = leg["atm_offset"] as String?,
        strike = (leg["strike"] as Number?)?.toDouble(),
        expiry = leg["expiry"] as String?,
        strikeInt = (leg["strike_int"] as Number?)?.toDouble(),
    )

    @Test fun `symbol resolution matches Python leg for leg`() {
        val cases = resolverFixture["cases"] as List<Map<String, Any?>>
        val failures = ArrayList<String>()
        for ((i, c) in cases.withIndex()) {
            val got = SymbolResolver.resolveLeg(
                legDef(m(c["leg"])), c["underlying"] as String, c["exchange"] as String, "straddle", contract,
                (c["ltp"] as Number).toDouble(), today,
            ).asDict()
            Fixtures.diff(c["expected"], got)?.let { failures.add("#$i $c\n   $it") }
        }
        report("resolver", cases.size, failures)
    }

    @Test fun `expiry ranks resolve as Python resolves them`() {
        val cases = resolverFixture["expiry"] as List<Map<String, Any?>>
        val failures = ArrayList<String>()
        for ((i, c) in cases.withIndex()) {
            val r = SymbolResolver.resolveExpiryRank(contract, c["underlying"] as String, c["exchange"] as String, c["instrument_type"] as String, c["rank"] as String, today)
            val got = linkedMapOf(
                "ok" to r.ok, "rank" to r.rank, "expiry" to r.expiry, "expiry_symbol" to r.expirySymbol, "available" to r.available,
                "fallback" to r.fallback, "error" to r.error, "code" to r.code,
            )
            Fixtures.diff(c["expected"], got)?.let { failures.add("#$i $c\n   $it") }
        }
        report("expiry", cases.size, failures)
    }

    @Test fun `master contract lookups match Python`() {
        val cases = resolverFixture["lookups"] as List<Map<String, Any?>>
        val failures = ArrayList<String>()
        for ((i, c) in cases.withIndex()) {
            val a = l(c["args"])
            val got: Any? = when (c["fn"]) {
                "lot_size_for" -> contract.lotSizeFor(a[0] as String, a[1] as String)
                "contract_exists" -> contract.contractExists(a[0] as String, a[1] as String)
                "resolve_quantity" -> contract.resolveQuantity(a[0], a[1] as String, a[2] as String, a[3] as String).let { listOf(it.quantity, it.lotSize, it.error) }
                else -> fail("fn ${c["fn"]}")
            }
            Fixtures.diff(c["expected"], got)?.let { failures.add("#$i $c\n   $it") }
        }
        report("lookups", cases.size, failures)
    }

    @Test fun `strategy validation matches Python, message for message`() {
        val cases = Fixtures.cases("strategy/validator.json.gz")
        val failures = ArrayList<String>()
        for ((i, c) in cases.withIndex()) {
            val got = StrategyValidator.validate(c["payload"]) { s, e -> contract.lotSizeFor(s, e) }
            val problem = when (got) {
                is StrategyValidator.Result.Invalid ->
                    if (c["error"] != got.message) "expected ${c["error"] ?: "OK"}, got error '${got.message}'" else null
                is StrategyValidator.Result.Ok ->
                    if (c["error"] != null) "expected error '${c["error"]}', got OK"
                    else Fixtures.diff(c["expected"], StrategyCodec.toMap(got.def) - "id" - "live_enabled")
            }
            problem?.let { failures.add("#$i payload=${c["payload"]}\n   $it") }
        }
        report("validator", cases.size, failures)
    }

    @Test fun `scheduler jobs and the session boundary match Python`() {
        val fixture = Fixtures.load("strategy/scheduler.json.gz")
        val failures = ArrayList<String>()
        val cases = fixture["cases"] as List<Map<String, Any?>>
        for ((i, c) in cases.withIndex()) {
            val jobs = Scheduler.plannedJobs(7, c["scheduler"] as? Map<String, Any?>, (c["exit_time"] as String?)?.let { LocalTime.parse(it) })
            Fixtures.diff(c["expected"], jobs.map { it.asDict() })?.let { failures.add("#$i $c\n   $it") }
        }
        val sessions = fixture["session"] as List<Map<String, Any?>>
        for ((i, c) in sessions.withIndex()) {
            val moment = ZonedDateTime.parse(c["moment"] as String)
            if (Session.sessionDay(moment).toString() != c["expected"]) failures.add("session #$i $c got ${Session.sessionDay(moment)}")
            if (!Session.sessionStartedAt(moment).isEqual(ZonedDateTime.parse(c["started"] as String))) failures.add("started #$i $c")
        }
        report("scheduler", cases.size + sessions.size, failures)
    }

    @Test fun `signal gates, the carry-short rule and signal quantities match Python`() {
        val cases = Fixtures.cases("strategy/signals.json.gz")
        val failures = ArrayList<String>()
        fun signalLeg(x: Map<String, Any?>) = LegDef(
            id = (x["id"] as Number).toInt(), segment = Segment.CASH, symbol = x["symbol"] as String?, exchange = x["exchange"] as String?,
            side = (x["side"] as String?)?.let { wireOf<LegSide>(it) }, qty = (x["qty"] as Number?)?.toInt(),
            qtyMode = (x["qty_mode"] as String?)?.let { wireOf<QtyMode>(it) },
        )
        for ((i, c) in cases.withIndex()) {
            val s = m(c["strategy"])
            val def = StrategyDef(
                name = "s", kind = StrategyKind.SIGNAL, direction = wireOf<Direction>(s["direction"] as String)!!, underlying = "X",
                underlyingExchange = "NSE", strategyType = wireOf<StrategyType>(s["strategy_type"] as String)!!,
                entryTime = (s["entry_time"] as String?)?.let { LocalTime.parse(it) }, exitTime = (s["exit_time"] as String?)?.let { LocalTime.parse(it) },
                product = wireOf<Product>(s["product"] as String)!!, legs = l(s["legs"]).map { signalLeg(m(it)) },
            )
            val gate = Signals.gate(
                def, c["action"] as String, (c["leg_id"] as Number?)?.toInt(), c["symbol"] as String?, c["exchange"] as String?,
                ZonedDateTime.parse(c["now"] as String),
            )
            val r = gate.result ?: SignalResult(false, error = "__gate_passed__", legId = gate.leg!!.id)
            Fixtures.diff(c["expected"], linkedMapOf("ok" to r.ok, "note" to r.note, "error" to r.error, "leg_id" to r.legId))
                ?.let { failures.add("gate #$i $c\n   $it") }

            val short = Signals.uncarryableShort(def, signalLeg(m(c["short_leg"])), c["short_side"] as String)
            if (short != c["short_expected"]) failures.add("short #$i expected ${c["short_expected"]} got $short")

            val (spec, err) = Signals.resolveSignalLeg(signalLeg(m(c["resolve_leg"])), c["short_side"] as String, contract)
            val expected = m(c["resolve_expected"])
            val want = (expected["spec"] as Map<String, Any?>?)?.let { mapOf("symbol" to it["symbol"], "exchange" to it["exchange"], "quantity" to it["quantity"], "lots" to it["lots"], "lot_size" to it["lot_size"]) }
            val have = spec?.let { mapOf("symbol" to it.symbol, "exchange" to it.exchange, "quantity" to it.quantity, "lots" to it.lots, "lot_size" to it.lotSize) }
            (Fixtures.diff(want, have) ?: Fixtures.diff(expected["error"], err))?.let { failures.add("resolve #$i ${c["resolve_leg"]}\n   $it") }
        }
        report("signals", cases.size, failures)
    }

    // ------------------------------------------------------------ engine

    /** A Python run dict as a [RunState]. */
    private fun runFrom(p: Map<String, Any?>): RunState {
        val r = RunState(
            runId = (p["run_id"] as Number).toLong(), strategyId = (p["strategy_id"] as Number).toLong(), startedAt = ZonedDateTime.now(),
            pnlRealized = (p["pnl_realized"] as Number).toDouble(), pnlUnrealized = (p["pnl_unrealized"] as Number).toDouble(),
            pnlTotal = (p["pnl_total"] as Number).toDouble(), pnlPeak = (p["pnl_peak"] as Number).toDouble(),
            pnlTrough = (p["pnl_trough"] as Number).toDouble(), lockArmed = p["lock_armed"] as Boolean,
            lockFloor = (p["lock_floor"] as Number?)?.toDouble(), trailToEntryActive = p["trail_to_entry_active"] as Boolean,
            stopping = p["stopping"] as Boolean,
        )
        m(p["legs"]).forEach { (k, v) -> r.legs[k] = RunStateCodec.legFromMap(m(v)) }
        return r
    }

    /** The run as Python keys, tokens reduced to their presence, exactly as the generator normalises. */
    private fun normal(r: RunState, keys: Set<String>): Map<String, Any?> {
        val out = RunStateCodec.toMap(r).filterKeys { it in keys }.toMutableMap()
        out["legs"] = r.legs.mapValues { (_, leg) ->
            RunStateCodec.legToMap(leg).toMutableMap().also { lm ->
                for (k in listOf("exit_claim_token", "position_ref")) if (lm[k] != null) lm[k] = "<tok>"
                (lm["superseded"] as Map<String, Any?>?)?.let { s ->
                    lm["superseded"] = s.toMutableMap().also { sm -> for (k in listOf("exit_claim_token", "position_ref")) if (sm[k] != null) sm[k] = "<tok>" }
                }
            }
        }
        out["signal_entry_claims"] = r.signalEntryClaims.keys.sorted()
        return out
    }

    @Test fun `tick evaluation matches the Python engine, events and exits included`() {
        val cases = Fixtures.cases("strategy/ticks.json.gz")
        val failures = ArrayList<String>()
        var steps = 0
        for ((i, c) in cases.withIndex()) {
            val def = StrategyCodec.fromMap(m(c["strategy"]))
            for ((j, s) in l(c["steps"]).map { m(it) }.withIndex()) {
                steps++
                val before = m(s["before"])
                val run = runFrom(before)
                val banked = if (Engine.dailyLossLimit(def) != null) (s["banked"] as Number).toDouble() else null
                val d = Engine.evaluateTick(run, def, s["symbol"] as String, "NFO", (s["ltp"] as Number).toDouble(), banked)
                val events = d.events.map { linkedMapOf("kind" to it.kind, "message" to it.message, "severity" to it.severity, "leg_id" to it.legId, "payload" to it.payload) }
                val problem = Fixtures.diff(s["after"], normal(run, m(s["after"]).keys))?.let { "state $it" }
                    ?: Fixtures.diff(s["events"], events)?.let { "events $it" }
                    // A basket stop closes everything, so upstream never dispatches the per-leg exits it also found.
                    ?: Fixtures.diff(s["exits"], if (d.stopReason != null) emptyList() else d.legExits.map { listOf(it.first, it.second) })?.let { "exits $it" }
                    ?: Fixtures.diff(s["stop"], d.stopReason)?.let { "stop $it" }
                problem?.let { failures.add("case $i step $j ${s["symbol"]}@${s["ltp"]}: $it") }
                if (failures.size > 20) break
            }
        }
        report("ticks ($steps steps)", steps, failures)
    }

    @Test fun `fills and exit claims match the Python engine op for op`() {
        val cases = Fixtures.cases("strategy/fills.json.gz")
        val failures = ArrayList<String>()
        var total = 0
        for ((i, c) in cases.withIndex()) {
            val run = runFrom(m(c["initial"]))
            for ((j, op) in l(c["ops"]).map { m(it) }.withIndex()) {
                total++
                val legId = (op["leg_id"] as Number).toInt()
                val problem: String? = when (op["op"]) {
                    "signal_entry" -> {
                        val claim = Engine.claimSignalEntry(run, legId, op["position"] as String)
                        if (claim.note != op["note"]) "note ${claim.note} vs ${op["note"]}"
                        else {
                            claim.claim?.let { cl ->
                                if (cl.heldPosition != null) {
                                    Engine.claimLegExit(run, legId, "exit_signal")?.let { snap ->
                                        Engine.bindLiveExit(run, legId, snap.exitClaimToken, (op["exit_row"] as Number).toLong(), snap.positionRef)
                                    }
                                }
                                val leg = Engine.newLegState(legId, op["position"] as String, "SIG", "NSE", 1, (op["quantity"] as Number).toInt(), 2.0, null, null, "points", cl.positionRef)
                                val installed = Engine.addLeg(run, leg, cl.claimToken, cl.expectedPositionRef, (op["entry_row"] as Number).toLong())
                                if (installed != null) Engine.finishSignalEntry(run, legId, cl.positionRef, cl.claimToken, op["accepted"] as Boolean)
                                else Engine.releaseSignalEntryClaim(run, legId, cl.claimToken)
                                if ((installed != null) != op["installed"]) "installed ${installed != null} vs ${op["installed"]}" else null
                            }
                        }
                    }
                    "claim_exit" -> {
                        val (claimed, unfilled) = Engine.claimLegsForExit(run, listOf(legId), op["kind"] as String)
                        if (claimed.size != (op["claimed"] as Number).toInt() || unfilled.size != (op["unfilled"] as Number).toInt()) "claim counts ${claimed.size}/${unfilled.size}"
                        else op["row"]?.let { row ->
                            val bound = Engine.bindLiveExit(run, legId, claimed[0].exitClaimToken, (row as Number).toLong(), claimed[0].positionRef)
                            if (bound != op["bound"]) "bound $bound" else null
                        }
                    }
                    "claim_superseded" -> {
                        val claim = Engine.claimSupersededExit(run, legId, run.leg(legId)?.superseded?.position)
                        if ((claim != null) != op["claimed"]) "superseded claim ${claim != null}"
                        else op["row"]?.let { row ->
                            val bound = Engine.bindSupersededExit(run, legId, claim!!.claimToken, (row as Number).toLong())
                            if (bound != op["bound"]) "bound $bound" else null
                        }
                    }
                    "release" -> {
                        val row = (op["row"] as Number?)?.toLong()
                        val result = if (row == null) false else if (op["which"] == "live") Engine.releaseLegExit(run, legId, row) else Engine.releaseSupersededExit(run, legId, row)
                        if (result != op["result"]) "release $result" else null
                    }
                    "fill" -> {
                        val leg = run.leg(legId)
                        val ref = when (op["ref"]) {
                            "live" -> leg?.positionRef
                            "superseded" -> leg?.superseded?.positionRef
                            "wrong" -> "not-a-ref"
                            else -> null
                        }
                        val r = Engine.applyFill(
                            run, legId, (op["price"] as Number?)?.toDouble(), op["is_entry"] as Boolean, (op["filled"] as Number?)?.toInt(),
                            (op["row"] as Number?)?.toLong(), ref, (op["cumulative"] as Number?)?.toInt(), op["terminal"] as Boolean, op["allow"] as Boolean,
                        )
                        val flat = r.ignored == null && !r.entryApplied && r.wentFlat
                        if (flat != op["flat"]) "flat $flat vs ${op["flat"]} (${r.ignored})" else null
                    }
                    else -> "unknown op ${op["op"]}"
                } ?: Fixtures.diff(op["after"], normal(run, m(op["after"]).keys))?.let { "state $it" }
                if (problem != null) {
                    failures.add("case $i op $j ${op["op"]} leg $legId: $problem\n   op=${op - "after"}")
                    break
                }
            }
        }
        report("fills ($total ops)", total, failures)
    }
}
