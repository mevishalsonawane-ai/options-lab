package com.optionslab.engine.strategy

import com.optionslab.engine.risk.Side
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Branch coverage of [StrategyRuntime]: the refusal, retry and odd-frame paths the scenarios do not reach. */
class RuntimeCoverageTest {
    private val rt = StrategyRuntime()
    private val monday = ZonedDateTime.of(2026, 5, 4, 10, 0, 0, 0, IST)

    private val contract = MasterContract(buildList {
        for (i in 0..8) {
            val s = 23400.0 + 50 * i
            add(Instrument("NIFTY05MAY26${s.toInt()}CE", "NFO", "NIFTY", "05-MAY-26", s, 65, "CE"))
            add(Instrument("NIFTY05MAY26${s.toInt()}PE", "NFO", "NIFTY", "05-MAY-26", s, 65, "PE"))
        }
        add(Instrument("RELIANCE", "NSE", "RELIANCE", null, null, 1, "EQ"))
        add(Instrument("NIFTY26MAY26FUT", "NFO", "NIFTY", "26-MAY-26", null, 65, "FUT"))
    })

    private fun def(vararg legs: LegDef, exitTime: LocalTime? = LocalTime.of(15, 15), type: StrategyType = StrategyType.INTRADAY,
                    daily: Double? = null) = StrategyDef(
        id = 3, name = "Basket", underlying = "NIFTY", underlyingExchange = "NSE_INDEX", strategyType = type,
        entryTime = LocalTime.of(9, 20), exitTime = exitTime, legs = legs.toList(), dailyLossLimitInr = daily,
    )

    private val shortCe = LegDef(1, Segment.OPTIONS, Position.S, 1, OptionType.CE, StrikeMode.ATM, "ATM", expiry = "weekly", slPts = 10.0)
    private val shortPe = LegDef(2, Segment.OPTIONS, Position.S, 1, OptionType.PE, StrikeMode.ATM, "ATM", expiry = "weekly", slPts = 10.0)
    private val straddle = def(shortCe, shortPe)

    private fun resolved(d: StrategyDef) = SymbolResolver.resolve(d, contract, 23590.0, monday)
    private fun orders(a: List<Action>) = a.filterIsInstance<Action.PlaceOrder>()
    private fun events(a: List<Action>) = a.mapNotNull { (it as? Action.Log)?.event ?: (it as? Action.Alert)?.event }
    private fun alerts(a: List<Action>) = a.filterIsInstance<Action.Alert>().map { it.event }

    private fun started(d: StrategyDef = straddle) = rt.start(d, resolved(d), monday, 11)

    private fun filled(d: StrategyDef = straddle, price: Double = 100.0): RunState {
        var (run, actions) = started(d)
        for (o in orders(actions)) {
            run = rt.onOrderAck(run, d, o.orderId, true, now = monday).first
            run = rt.onOrderUpdate(run, d, OrderUpdate(o.orderId, "complete", o.quantity, price), monday).first
        }
        return run
    }

    // ------------------------------------------------------------------ start

    @Test fun `start refuses an empty, oversized, unresolved, failed or sideless basket`() {
        fun err(d: StrategyDef, r: List<ResolvedLeg> = resolved(d)) = rt.start(d, r, monday, 1).first.startError
        assertEquals("The strategy has no legs", err(def()))
        val eleven = def(*Array(11) { shortCe.copy(id = it + 1) })
        assertEquals("A strategy takes at most 10 legs, got 11", err(eleven, emptyList()))
        assertEquals("Leg 2: it was not resolved", err(straddle, resolved(straddle).take(1)))
        assertEquals("Leg 1: boom", err(straddle, listOf(ResolvedLeg(false, error = "boom", legId = 1))))
        val sideless = def(shortCe.copy(position = null))
        assertEquals("Leg 1 has an unusable position: None",
            err(sideless, listOf(ResolvedLeg(true, symbol = "X", exchange = "NFO", quantity = 65, legId = 1))))
        val (run, actions) = rt.start(def(), emptyList(), monday, 1)
        assertEquals(RunStatus.IDLE, run.status)
        assertEquals("start_refused", alerts(actions).single().kind)
        // Live mode on a live-enabled strategy is allowed.
        val live = straddle.copy(liveEnabled = true)
        assertEquals(RunMode.LIVE, rt.start(live, resolved(live), monday, 2, RunMode.LIVE, "scheduler").first.mode)
    }

    @Test fun `start names an expiry fallback and falls back to the configured lot count`() {
        val r = listOf(ResolvedLeg(true, symbol = "NIFTY05MAY2623600CE", exchange = "NFO", quantity = 130, lots = null, expiry = "05-MAY-26",
            legId = 1, detail = mapOf("expiry_fallback" to true, "expiry_rank" to "next_week")))
        val d = def(shortCe.copy(lots = 2))
        val (run, actions) = rt.start(d, r, monday, 5, triggerSource = "webhook")
        assertEquals(2, run.leg(1)!!.lots)
        val w = events(actions).first { it.kind == "leg_expiry_fallback" }
        assertEquals("warn", w.severity)
        assertEquals("Leg 1 asked for the next_week expiry; the chain lists only 05-MAY-26, which was used", w.message)
        assertTrue(events(actions).any { it.message == "Run started in sandbox mode (webhook)" })
    }

    // ------------------------------------------------------------------- acks

    @Test fun `an ack for an unknown order is a warning, and a repeated ack changes nothing`() {
        val (run, actions) = started()
        val (_, a0) = rt.onOrderAck(run, straddle, 999, true, now = monday)
        assertEquals("order_unknown", events(a0).single().kind)
        val id = orders(actions).first().orderId
        val r1 = rt.onOrderAck(run, straddle, id, true, now = monday).first
        // A late refusal for an order already accepted keeps it open and the leg open.
        val (r2, a2) = rt.onOrderAck(r1, straddle, id, false, "late", monday)
        assertEquals("open", r2.orders[id]!!.status)
        assertEquals("open", r2.leg(1)!!.status)
        assertEquals("leg_entry_rejected", events(a2).single().kind)
    }

    @Test fun `every entry refused without a reason, or for different reasons, is summarised`() {
        var (run, actions) = started()
        val all = ArrayList<Action>()
        for (o in orders(actions)) run = rt.onOrderAck(run, straddle, o.orderId, false, null, monday).also { all += it.second }.first
        assertEquals("Every entry order was rejected", alerts(all).single { it.kind == "start_failed" }.message)
        assertEquals("error", run.stopReason)

        var (run2, actions2) = started()
        val all2 = ArrayList<Action>()
        val reasons = listOf("margin", "  ", "freeze")
        val three = def(shortCe, shortPe, shortCe.copy(id = 3, optionType = OptionType.PE, atmOffset = "OTM1"))
        val (r3, a3) = rt.start(three, resolved(three), monday, 12)
        run2 = r3
        orders(a3).forEachIndexed { i, o -> run2 = rt.onOrderAck(run2, three, o.orderId, false, reasons[i], monday).also { all2 += it.second }.first }
        assertEquals("Every entry order was rejected. leg 1: margin; leg 3: freeze", alerts(all2).single { it.kind == "start_failed" }.message)
        assertTrue(actions2.isNotEmpty())
    }

    @Test fun `a refused entry while a stop is pending lets the stop finish`() {
        val (run0, actions) = started()
        val placed = orders(actions)
        var run = rt.onOrderAck(run0, straddle, placed[0].orderId, true, now = monday).first
        run = rt.onOrderUpdate(run, straddle, OrderUpdate(placed[0].orderId, "complete", 65, 100.0), monday).first
        run = rt.closeAll(run, straddle, monday).first
        assertEquals(RunStatus.EXITING, run.status)
        val (r2, a2) = rt.onOrderAck(run, straddle, placed[1].orderId, false, "margin", monday)
        assertEquals("rejected", r2.leg(2)!!.status)
        assertTrue(events(a2).any { it.kind == "run_stop_requested" }, "the pending stop is reconciled")
    }

    @Test fun `a refused exit during a stop says the stop failed and stays retryable`() {
        val run = filled()
        val (r1, a1) = rt.closeAll(run, straddle, monday)
        val exit = orders(a1).first()
        val (r2, a2) = rt.onOrderAck(r1, straddle, exit.orderId, false, "rms", monday)
        assertTrue(alerts(a2).any { it.kind == "run_stop_failed" })
        assertEquals("Exit rejected on leg ${exit.legId}: rms", alerts(a2).first { it.kind == "leg_exit_rejected" }.message)
        assertNull(r2.leg(exit.legId)!!.exitOrderId)
        // Accepted exits log "placed (kind)".
        val (_, a3) = rt.onOrderAck(r1, straddle, orders(a1)[1].orderId, true, now = monday)
        assertEquals("Exit BUY 65 NIFTY05MAY2623600PE placed (exit_close_all)", events(a3).single().message)
    }

    // ------------------------------------------------------------------ fills

    @Test fun `frames for unknown orders are ignored and a cancelled entry with no fill rejects its leg`() {
        val (run, actions) = started()
        assertEquals(RunStateCodec.encode(run), RunStateCodec.encode(rt.onOrderUpdate(run, straddle, OrderUpdate(77, "complete"), monday).first))
        val id = orders(actions)[0].orderId
        val acked = rt.onOrderAck(run, straddle, id, true, now = monday).first
        val (r1, _) = rt.onOrderUpdate(acked, straddle, OrderUpdate(id, "CANCELED", 0, null, "user"), monday)
        assertEquals("cancelled", r1.orders[id]!!.status)
        assertEquals("user", r1.orders[id]!!.rejectReason)
        assertEquals("rejected", r1.leg(1)!!.status)
        assertEquals("cancelled", r1.leg(1)!!.entryStatus)
        // A later "open" frame never reopens it; a complete with quantity revives it as a fill.
        val (r2, a2) = rt.onOrderUpdate(r1, straddle, OrderUpdate(id, "open", 0), monday)
        assertEquals("cancelled", r2.orders[id]!!.status); assertTrue(a2.isEmpty())
        val (r3, _) = rt.onOrderUpdate(r1, straddle, OrderUpdate(id, "rejected", 0, null, "again"), monday)
        assertEquals("user", r3.orders[id]!!.rejectReason)
        val (r4, _) = rt.onOrderUpdate(r1, straddle, OrderUpdate(id, "Filled", 65, 99.0), monday)
        assertEquals("complete", r4.orders[id]!!.status)
        assertEquals(99.0, r4.leg(1)!!.entryAvg)
    }

    @Test fun `a completed order stays complete and extra quantity evidence is still folded`() {
        val run = filled()
        val id = run.leg(1)!!.entryOrderId!!
        val (r1, a1) = rt.onOrderUpdate(run, straddle, OrderUpdate(id, "cancelled", 0), monday)
        assertEquals("complete", r1.orders[id]!!.status); assertTrue(a1.isEmpty())
        // A complete frame without a quantity means the whole order: nothing new.
        val (_, a2) = rt.onOrderUpdate(run, straddle, OrderUpdate(id, "executed", null, 100.0), monday)
        assertTrue(a2.isEmpty())
    }

    @Test fun `an entry filled without a usable price is managed but flagged unpriced`() {
        val (run, actions) = started()
        val id = orders(actions)[0].orderId
        val (r1, a1) = rt.onOrderUpdate(run, straddle, OrderUpdate(id, "traded", 65, Double.NaN), monday)
        assertTrue(alerts(a1).any { it.kind == "fill_unpriced" })
        assertTrue(events(a1).any { it.kind == "fill_note" && it.message.contains("without a usable average price") })
        assertEquals("complete", r1.leg(1)!!.entryStatus)
        // Partial, then non-price frame: evidence grows but no reprice.
        val (r2, _) = rt.onOrderUpdate(run, straddle, OrderUpdate(orders(actions)[1].orderId, "open", 30, -5.0), monday)
        assertEquals(30, r2.leg(2)!!.qty)
        assertEquals("open", r2.leg(2)!!.entryStatus)
    }

    @Test fun `a late entry fill on a finished run is a critical reconciliation`() {
        val (run0, actions) = started()
        var run = run0
        val placed = orders(actions)
        for (o in placed) run = rt.onOrderAck(run, straddle, o.orderId, true, now = monday).first
        run = rt.closeAll(run, straddle, monday).first
        for (o in placed) run = rt.onOrderUpdate(run, straddle, OrderUpdate(o.orderId, "cancelled", 0), monday).first
        assertEquals(RunStatus.CLOSED, run.status)
        val (r2, a2) = rt.onOrderUpdate(run, straddle, OrderUpdate(placed[0].orderId, "complete", 65, 100.0), monday)
        assertEquals("run_stop_failed", alerts(a2).single().kind)
        assertEquals(RunStatus.CLOSED, r2.status)
    }

    @Test fun `an exit that ends cancelled while the leg is held is critical, and incremental prices degrade to null`() {
        val run = filled()
        val (r1, a1) = rt.closeLeg(run, straddle, 1, monday)
        val exit = orders(a1).single()
        var r = rt.onOrderAck(r1, straddle, exit.orderId, true, now = monday).first
        // A partial at a real price, then a partial whose average cannot be split.
        r = rt.onOrderUpdate(r, straddle, OrderUpdate(exit.orderId, "open", 20, 110.0), monday).first
        assertEquals(45, r.leg(1)!!.qty)
        val (rN, aN) = rt.onOrderUpdate(r, straddle, OrderUpdate(exit.orderId, "open", 30, 1.0), monday)   // (30 - 2200)/10 < 0
        assertTrue(events(aN).any { it.kind == "fill_note" && it.message.contains("without complete fill pricing") })
        assertEquals(35, rN.leg(1)!!.qty)
        val (rC, aC) = rt.onOrderUpdate(r, straddle, OrderUpdate(exit.orderId, "cancelled", 20, null, "IOC"), monday)
        assertEquals("The exit for leg 1 ended as cancelled; the position is still held and remains managed", alerts(aC).single().message)
        assertEquals("open", rC.leg(1)!!.status)
        assertNull(rC.leg(1)!!.exitOrderId)
        // Rejected with no fill at all: the claim is released via the order, and the leg is held.
        val (rR, aR) = rt.onOrderUpdate(rt.onOrderAck(r1, straddle, exit.orderId, true, now = monday).first, straddle,
            OrderUpdate(exit.orderId, "rejected", 0, null, "rms"), monday)
        assertTrue(alerts(aR).any { it.kind == "leg_exit_rejected" })
        assertNull(rR.leg(1)!!.exitKind)
        // The first exit frame has no price: nothing to split.
        val (rU, aU) = rt.onOrderUpdate(r1, straddle, OrderUpdate(exit.orderId, "open", 10, null), monday)
        assertTrue(alerts(aU).any { it.kind == "fill_unpriced" })
        assertEquals(55, rU.leg(1)!!.qty)
        // Second frame with a price but the first had none: the cumulative average stands.
        val (rP, _) = rt.onOrderUpdate(rU, straddle, OrderUpdate(exit.orderId, "complete", 65, 112.0), monday)
        assertEquals("closed", rP.leg(1)!!.status)
        assertEquals(-12.0 * 55, rP.leg(1)!!.realizedPnl, 1e-9)
    }

    @Test fun `onFill applies delta fills directly`() {
        val (run, actions) = started()
        assertEquals(RunStateCodec.encode(run), RunStateCodec.encode(rt.onFill(run, straddle, 404, 1.0, now = monday).first))
        val id = orders(actions)[0].orderId
        // Non-terminal partial: the leg opens, the order stays pending.
        val (r1, _) = rt.onFill(run, straddle, id, 100.0, 30, terminal = false, now = monday)
        assertEquals("pending", r1.orders[id]!!.status)
        assertEquals(30, r1.orders[id]!!.filledQty)
        assertEquals("open", r1.leg(1)!!.entryStatus)
        assertEquals(30, r1.leg(1)!!.qty)
        // Terminal whole fill.
        val (r2, _) = rt.onFill(run, straddle, id, 100.0, now = monday)
        assertEquals("complete", r2.orders[id]!!.status)
        assertEquals("complete", r2.leg(1)!!.entryStatus)
        assertEquals(65, r2.leg(1)!!.qty)
        // An already complete order stays complete.
        assertEquals("complete", rt.onFill(r2, straddle, id, 100.0, now = monday).first.orders[id]!!.status)
    }

    @Test fun `an unfilled entry blocks a stop, which then says it failed`() {
        val (run, actions) = started()
        val id = orders(actions)[0].orderId
        // Partially filled via a delta fill, never acked: no cancel can be requested for it.
        val r1 = rt.onFill(run, straddle, id, 100.0, 30, terminal = false, now = monday).first
        val (r2, a2) = rt.closeAll(r1, straddle, monday)
        assertTrue(alerts(a2).any { it.kind == "run_stop_failed" })
        assertTrue(a2.none { it is Action.CancelOrder })
        assertEquals(RunStatus.EXITING, r2.status)
        // closeLeg on the same leg: refused with the retry advice.
        val (_, a3) = rt.closeLeg(r1, straddle, 1, monday)
        assertTrue(events(a3).single().message.startsWith("Exit refused: The entry for this leg has been accepted but not filled"))
    }

    @Test fun `a fill for a replaced order is ignored with a warning`() {
        val run = filled()
        val (r1, a1) = rt.closeLeg(run, straddle, 1, monday)
        val exit = orders(a1).single()
        // A frame on the ENTRY order after the leg's exit claim: prior correction allowed only once terminal.
        val bogus = r1.deepCopy().also { it.orders[900] = OrderRecord(900, 1, "exit_sl", "BUY", 65, exit.symbol, "NFO", "NRML", r1.leg(1)!!.positionRef, "live") }
        val (_, a2) = rt.onOrderUpdate(bogus, straddle, OrderUpdate(900, "complete", 65, 101.0), monday)
        assertTrue(events(a2).any { it.kind == "fill_ignored" && it.message.contains("waiting on ${exit.orderId}") })
    }

    // ------------------------------------------------------------------- tick

    @Test fun `ticks on idle, finished, positional and exit-time-disabled runs`() {
        val (idle, _) = rt.start(def(), emptyList(), monday, 1)
        assertTrue(rt.onTick(idle, straddle, listOf(Quote("X", "NFO", 1.0)), monday).second.isEmpty())
        val run = filled()
        val late = monday.withHour(15).withMinute(30)
        assertTrue(orders(rt.onTick(run, straddle, emptyList(), late, enforceExitTime = false).second).isEmpty())
        val positional = straddle.copy(strategyType = StrategyType.POSITIONAL)
        assertTrue(orders(rt.onTick(run, positional, emptyList(), late).second).isEmpty())
        val noExit = straddle.copy(exitTime = null)
        assertTrue(orders(rt.onTick(run, noExit, emptyList(), late).second).isEmpty())
        // A stop already requested is not requested again by the exit time.
        val stopping = rt.closeAll(run, straddle, monday).first
        assertTrue(orders(rt.onTick(stopping, straddle, emptyList(), late).second).isEmpty())
        // Finished run: nothing.
        val (done, _) = rt.start(def(), emptyList(), monday, 1)
        done.startedAt = monday; done.stoppedAt = monday
        assertTrue(rt.onTick(done, straddle, listOf(Quote("X", "NFO", 1.0)), monday).second.isEmpty())
    }

    @Test fun `a quote after the run finalises in the same tick is not evaluated`() {
        // Exit time on a run with nothing held finalises at once; the quotes loop then stops.
        val (run, actions) = started()
        var r = run
        for (o in orders(actions)) r = rt.onOrderAck(r, straddle, o.orderId, false, "x", monday).first
        r.stoppedAt = null; r.stopReason = null
        r.deriveStatus()
        val (r2, a2) = rt.onTick(r, straddle, listOf(Quote("NIFTY05MAY2623600CE", "NFO", 500.0)), monday.withHour(15).withMinute(20))
        assertEquals(RunStatus.CLOSED, r2.status)
        assertEquals("scheduler", r2.stopReason)
        assertTrue(orders(a2).isEmpty())
    }

    @Test fun `a daily limit of zero is no limit`() {
        val z = straddle.copy(dailyLossLimitInr = 0.0)
        val run = filled(z)
        val (r, _) = rt.onTick(run, z, listOf(Quote(run.leg(1)!!.symbol, "NFO", 105.0)), monday, bankedSessionPnl = -1e9)
        assertNull(r.stopRequestedReason)
        val neg = straddle.copy(dailyLossLimitInr = -100.0)
        val (r2, _) = rt.onTick(filled(neg), neg, listOf(Quote(run.leg(1)!!.symbol, "NFO", 105.0)), monday)
        assertEquals("daily_loss_limit", r2.stopRequestedReason, "a negative limit reads as its magnitude; banked null counts as 0")
    }

    // ------------------------------------------------------------------ stops

    @Test fun `closing a finished run or a missing leg is refused`() {
        val (idle, _) = rt.start(def(), emptyList(), monday, 1)
        idle.stoppedAt = monday
        assertEquals("Run is not active", events(rt.closeAll(idle, straddle, monday).second).single().message)
        assertEquals("Run is not active", events(rt.closeLeg(idle, straddle, 1, monday).second).single().message)
        assertEquals("That leg is not open", events(rt.closeLeg(filled(), straddle, 9, monday).second).single().message)
        // reconcilePendingStop without a stop, and on a finished run, does nothing.
        assertTrue(rt.reconcilePendingStop(filled(), straddle, monday).second.isEmpty())
        assertTrue(rt.reconcilePendingStop(idle, straddle, monday).second.isEmpty())
    }

    @Test fun `a stop with an unknown reason exits as close-all`() {
        val (_, a) = rt.closeAll(filled(), straddle, monday, "whatever")
        assertEquals(setOf("exit_close_all"), orders(a).map { it.kind }.toSet())
        val (_, b) = rt.closeAll(filled(), straddle, monday, "expiry")
        assertEquals(setOf("exit_expiry"), orders(b).map { it.kind }.toSet())
    }

    @Test fun `session banked pnl filters strategy, run, start and session`() {
        val now = monday.withHour(14)
        val a = RunState(1, 3, startedAt = monday, pnlRealized = -100.0)
        val other = RunState(2, 4, startedAt = monday, pnlRealized = -1000.0)
        val self = RunState(3, 3, startedAt = monday, pnlRealized = -2000.0)
        val never = RunState(4, 3, pnlRealized = -3000.0)
        val early = RunState(5, 3, startedAt = monday.withHour(2), pnlRealized = -4000.0)   // before the 03:00 reset
        assertEquals(-100.0, StrategyRuntime.sessionBankedPnl(listOf(a, other, self, never, early), 3, 3, now))
        assertEquals(-2100.0, StrategyRuntime.sessionBankedPnl(listOf(a, self), 3, null, now))
    }

    @Test fun `signal results report whether they acted`() {
        assertTrue(SignalResult(true).acted)
        assertFalse(SignalResult(true, note = "x").acted)
        assertFalse(SignalResult(false).acted)
    }

    // ---------------------------------------------------------------- signals

    private val signalDef = StrategyDef(
        id = 9, name = "Alerts", kind = StrategyKind.SIGNAL, underlying = "RELIANCE", underlyingExchange = "NSE",
        entryTime = LocalTime.of(9, 15), exitTime = LocalTime.of(15, 20), product = Product.MIS,
        legs = listOf(LegDef(1, Segment.CASH, symbol = "RELIANCE", exchange = "NSE", side = LegSide.BOTH, qty = 10, qtyMode = QtyMode.UNITS, slPts = 5.0)),
    )

    private fun ackFill(out: SignalOutcome, price: Double, d: StrategyDef = signalDef): RunState {
        var run = out.run!!
        for (o in orders(out.actions)) {
            run = rt.onOrderAck(run, d, o.orderId, true, now = monday).first
            run = rt.onOrderUpdate(run, d, OrderUpdate(o.orderId, "complete", o.quantity, price), monday).first
        }
        return run
    }

    @Test fun `a signal on a finished run opens a new one, live when enabled`() {
        val finished = RunState(1, 9, kind = StrategyKind.SIGNAL, startedAt = monday, stoppedAt = monday)
        val live = signalDef.copy(liveEnabled = true)
        val out = rt.onSignal(finished, live, "long_entry", contract, monday, 50, symbol = "reliance", exchange = "nse")
        assertEquals(50, out.run!!.runId)
        assertEquals(RunMode.LIVE, out.run!!.mode)
        assertTrue(events(out.actions).any { it.message == "Signal run opened in live mode" })
    }

    @Test fun `a signal whose contract does not exist is refused and logged, releasing its claim`() {
        val bad = signalDef.copy(legs = listOf(signalDef.legs[0].copy(symbol = "NOPE", exchange = "NFO")))
        val out = rt.onSignal(null, bad, "long_entry", contract, monday, 1, legId = 1)
        assertFalse(out.result.ok)
        assertEquals("Leg 1: NOPE is not a contract on NFO", out.result.error)
        assertEquals("signal_rejected", events(out.actions).last().kind)
        assertTrue(out.run!!.signalEntryClaims.isEmpty())
    }

    @Test fun `a second entry while the first is unacknowledged is a pending flip, not a second order`() {
        val first = rt.onSignal(null, signalDef, "long_entry", contract, monday, 1, legId = 1)
        val again = rt.onSignal(first.run, signalDef, "short_entry", contract, monday, 2, legId = 1)
        assertEquals("flip_pending", again.result.note)
        assertTrue(again.result.ok)
        assertTrue(orders(again.actions).isEmpty())
    }

    @Test fun `a signal entry refused by the venue rejects its leg and a second ack is harmless`() {
        val first = rt.onSignal(null, signalDef, "long_entry", contract, monday, 1, legId = 1)
        val id = orders(first.actions).single().orderId
        val (r1, a1) = rt.onOrderAck(first.run!!, signalDef, id, false, "rms", monday)
        assertEquals("Signal BUY 10 RELIANCE rejected: rms", events(a1).single().message)
        assertEquals("warn", events(a1).single().severity)
        assertEquals("rejected", r1.leg(1)!!.status)
        assertTrue(r1.signalEntryClaims.isEmpty())
        val (_, a2) = rt.onOrderAck(r1, signalDef, id, true, now = monday)
        assertEquals("Signal BUY 10 RELIANCE", events(a2).single().message)
        // Cancelled with no fill: the claim is dropped, the leg rejected.
        val (r3, _) = rt.onOrderUpdate(first.run!!, signalDef, OrderUpdate(id, "cancelled", 0), monday)
        assertEquals("rejected", r3.leg(1)!!.status)
        assertTrue(r3.signalEntryClaims.isEmpty())
    }

    @Test fun `exit signals - unfilled entry, exit already in flight, and signal exit wording`() {
        val first = rt.onSignal(null, signalDef, "long_entry", contract, monday, 1, legId = 1)
        val id = orders(first.actions).single().orderId
        val acked = rt.onOrderAck(first.run!!, signalDef, id, true, now = monday).first
        val unfilled = rt.onSignal(acked, signalDef, "long_exit", contract, monday, 1, legId = 1)
        assertFalse(unfilled.result.ok)
        assertTrue(unfilled.result.error!!.startsWith("The entry for this leg has been accepted but not filled"))
        assertEquals("signal_rejected", events(unfilled.actions).single().kind)

        val held = rt.onOrderUpdate(acked, signalDef, OrderUpdate(id, "complete", 10, 2500.0), monday).first
        val exit1 = rt.onSignal(held, signalDef, "long_exit", contract, monday, 1, legId = 1)
        assertTrue(exit1.result.acted)
        val ex = orders(exit1.actions).single()
        assertEquals("exit_signal", ex.kind); assertEquals(Side.SELL, ex.side)
        val exit2 = rt.onSignal(exit1.run, signalDef, "long_exit", contract, monday, 1, legId = 1)
        assertEquals("no_matching_position", exit2.result.note)
        // Signal exit acks use the signal wording.
        val (_, okA) = rt.onOrderAck(exit1.run!!, signalDef, ex.orderId, true, now = monday)
        assertEquals("Signal SELL 10 RELIANCE", events(okA).single().message)
        val (_, badA) = rt.onOrderAck(exit1.run!!, signalDef, ex.orderId, false, "rms", monday)
        assertEquals("Signal SELL 10 RELIANCE rejected: rms", alerts(badA).single().message)
        // An entry while the stale-free run is stopping is a no-op note.
        val stopping = rt.closeAll(held, signalDef, monday).first
        val blocked = rt.onSignal(stopping, signalDef, "short_entry", contract, monday, 1, legId = 1)
        assertEquals("run_stopping", blocked.result.note)
    }

    /** Short, flipped to long, entry filled; the flip's closing BUY is then cancelled by the venue with nothing filled. */
    private fun flipWithCancelledClose(): Triple<RunState, Action.PlaceOrder, List<Action>> {
        val short = ackFill(rt.onSignal(null, signalDef, "short_entry", contract, monday, 1, legId = 1), 2500.0)
        assertEquals("S", short.leg(1)!!.position)
        val flip = rt.onSignal(short, signalDef, "long_entry", contract, monday, 1, legId = 1)
        assertTrue(flip.result.flipped)
        val (exitOrder, entryOrder) = orders(flip.actions)
        assertEquals(Side.BUY, exitOrder.side); assertEquals("exit_signal", exitOrder.kind)
        assertEquals(Side.BUY, entryOrder.side); assertEquals("entry", entryOrder.kind)
        var run = rt.onOrderAck(flip.run!!, signalDef, entryOrder.orderId, true, now = monday).first
        run = rt.onOrderUpdate(run, signalDef, OrderUpdate(entryOrder.orderId, "complete", 10, 2490.0), monday).first
        run = rt.onOrderAck(run, signalDef, exitOrder.orderId, true, now = monday).first
        val (r1, a1) = rt.onOrderUpdate(run, signalDef, OrderUpdate(exitOrder.orderId, "cancelled", 0), monday)
        return Triple(r1, exitOrder, a1)
    }

    @Test fun `a flip whose closing order is cancelled keeps the outgoing short managed and retryable`() {
        val (r1, _, a1) = flipWithCancelledClose()
        assertEquals("The exit for leg 1 ended as cancelled; the position is still held and remains managed", alerts(a1).single().message)
        assertNotNull(r1.leg(1)!!.superseded)
        assertNull(r1.leg(1)!!.superseded!!.exitOrderId)
        // Another short entry while the outgoing short is held: the flip is still pending.
        assertEquals("flip_pending", rt.onSignal(r1, signalDef, "short_entry", contract, monday, 1, legId = 1).result.note)
        // A short_exit now targets the outgoing short: it is re-sent under `superseded`.
        val retry = rt.onSignal(r1, signalDef, "short_exit", contract, monday, 1, legId = 1)
        assertTrue(retry.result.acted)
        val again = orders(retry.actions).single()
        assertEquals(Side.BUY, again.side)
        assertEquals(10, again.quantity)
        // Its refusal is the flip-outgoing alert, and the claim is released again.
        val (rX, aX) = rt.onOrderAck(retry.run!!, signalDef, again.orderId, false, "rms", monday)
        assertEquals("flip_outgoing_exit_rejected", alerts(aX).first().kind)
        assertNull(rX.leg(1)!!.superseded!!.exitClaimToken)
        // It fills instead: the short's round trip is booked, the long stays.
        var r2 = rt.onOrderAck(retry.run!!, signalDef, again.orderId, true, now = monday).first
        r2 = rt.onOrderUpdate(r2, signalDef, OrderUpdate(again.orderId, "complete", 10, 2480.0), monday).first
        assertNull(r2.leg(1)!!.superseded)
        assertEquals(200.0, r2.leg(1)!!.realizedPnl, 1e-9)
        assertEquals("B", r2.leg(1)!!.position)
    }

    @Test fun `a stop during a pending flip exits both owners, and a refused superseded exit fails the stop`() {
        val (run, _, _) = flipWithCancelledClose()
        val (r1, a1) = rt.closeAll(run, signalDef, monday)
        val exits = orders(a1)
        assertEquals(listOf(Side.SELL, Side.BUY), exits.map { it.side }, "the live long and the outgoing short")
        val sup = exits[1]
        // The superseded exit is cancelled by the venue: still held, critical.
        val acked = rt.onOrderAck(r1, signalDef, sup.orderId, true, now = monday).first
        val (r2, a2) = rt.onOrderUpdate(acked, signalDef, OrderUpdate(sup.orderId, "cancelled", 0), monday)
        assertTrue(alerts(a2).any { it.kind == "leg_exit_rejected" })
        assertNotNull(r2.leg(1)!!.superseded)
        // A refusal of the superseded exit during the stop: the stop reports failure.
        val (_, a3) = rt.onOrderAck(r1, signalDef, sup.orderId, false, "rms", monday)
        assertTrue(alerts(a3).any { it.kind == "flip_outgoing_exit_rejected" })
        assertTrue(alerts(a3).any { it.kind == "run_stop_failed" })
    }

    @Test fun `BUG - a refused ack for a flip's closing order must release the outgoing position's exit claim`() {
        // BUG: the flip's closing order is placed while the old position is still the LIVE leg, so its
        // OrderRecord.exitOwner is "live". addLeg then moves that position (with exitOrderId) under
        // `superseded`. When the venue refuses the placement, onOrderAck calls releaseLegExit on the NEW
        // live leg (no match) instead of releaseSupersededExit, so superseded.exitOrderId stays bound to a
        // dead order: claimSupersededExit refuses it forever, no signal or stop can retry the outgoing
        // position, and requiresManagement() keeps the run from ever finalising. (The cancel-frame path,
        // onOrderUpdate -> releaseOrderExit, matches by order id and works.)
        val short = ackFill(rt.onSignal(null, signalDef, "short_entry", contract, monday, 1, legId = 1), 2500.0)
        val flip = rt.onSignal(short, signalDef, "long_entry", contract, monday, 1, legId = 1)
        val (exitOrder, entryOrder) = orders(flip.actions)
        var run = rt.onOrderAck(flip.run!!, signalDef, entryOrder.orderId, true, now = monday).first
        run = rt.onOrderUpdate(run, signalDef, OrderUpdate(entryOrder.orderId, "complete", 10, 2490.0), monday).first
        val (r1, _) = rt.onOrderAck(run, signalDef, exitOrder.orderId, false, "rms", monday)
        assertNull(r1.leg(1)!!.superseded!!.exitOrderId, "the refused order still owns the outgoing short")
        val retry = rt.onSignal(r1, signalDef, "short_exit", contract, monday, 1, legId = 1)
        assertEquals(1, orders(retry.actions).size, "the outgoing short can be closed again")
    }

    @Test fun `a flat signal run stays open, and a finished signal run is not flattened again`() {
        val long = ackFill(rt.onSignal(null, signalDef, "long_entry", contract, monday, 1, legId = 1), 2500.0)
        val out = ackFill(rt.onSignal(long, signalDef, "long_exit", contract, monday, 1, legId = 1), 2501.0)
        assertNull(out.stoppedAt)
        assertEquals(10.0, out.pnlRealized, 1e-9)
    }
}
