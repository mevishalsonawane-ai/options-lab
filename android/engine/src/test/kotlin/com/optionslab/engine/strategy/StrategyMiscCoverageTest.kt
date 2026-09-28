package com.optionslab.engine.strategy

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Persistence, presets, scheduling and the host's odd paths. */
class StrategyMiscCoverageTest {
    private val rt = StrategyRuntime()
    private val monday = ZonedDateTime.of(2026, 5, 4, 10, 0, 0, 0, IST)
    private val contract = MasterContract(listOf(Instrument("RELIANCE", "NSE", "RELIANCE", null, null, 1, "EQ")))
    private val signalDef = StrategyDef(
        id = 9, name = "Alerts", kind = StrategyKind.SIGNAL, underlying = "RELIANCE", underlyingExchange = "NSE",
        entryTime = LocalTime.of(9, 15), exitTime = LocalTime.of(15, 20), product = Product.MIS,
        legs = listOf(LegDef(1, Segment.CASH, symbol = "RELIANCE", exchange = "NSE", side = LegSide.BOTH, qty = 10, qtyMode = QtyMode.UNITS)),
    )

    private fun orders(a: List<Action>) = a.filterIsInstance<Action.PlaceOrder>()

    // ------------------------------------------------------------ codecs

    @Test fun `a run mid-flip with a pending claim round-trips through JSON`() {
        // Hold a long, then flip: the outgoing long sits under `superseded` with its exit bound.
        var run = rt.onSignal(null, signalDef, "long_entry", contract, monday, 1, legId = 1).let { out ->
            var r = out.run!!
            for (o in orders(out.actions)) {
                r = rt.onOrderAck(r, signalDef, o.orderId, true, now = monday).first
                r = rt.onOrderUpdate(r, signalDef, OrderUpdate(o.orderId, "complete", o.quantity, 2500.0), monday).first
            }
            r
        }
        val flip = rt.onSignal(run, signalDef, "short_entry", contract, monday, 1, legId = 1)
        run = flip.run!!
        assertNotNull(run.leg(1)!!.superseded)
        assertTrue(run.signalEntryClaims.isNotEmpty(), "the new entry's claim is held until its ack")
        val json = RunStateCodec.encode(run)
        val back = RunStateCodec.decode(json)
        assertEquals(json, RunStateCodec.encode(back))
        val s = back.leg(1)!!.superseded!!
        assertEquals("B", s.position); assertEquals(10, s.qty); assertEquals(2500.0, s.entryAvg); assertNotNull(s.exitOrderId)
        val c = back.signalEntryClaims.getValue("1")
        assertEquals("S", c.position); assertEquals("B", c.heldPosition); assertNotNull(c.expectedPositionRef)
        // The decoded state drives the runtime exactly as the original does.
        val (entryOrder, exitOrder) = orders(flip.actions).let { it[1] to it[0] }
        val a = rt.onOrderAck(run, signalDef, entryOrder.orderId, true, now = monday).first
        val b = rt.onOrderAck(back, signalDef, entryOrder.orderId, true, now = monday).first
        assertEquals(RunStateCodec.encode(a), RunStateCodec.encode(b))
        assertTrue(exitOrder.orderId in back.orders)
    }

    @Test fun `a sparse Python checkpoint reads with defaults`() {
        val r = RunStateCodec.fromMap(mapOf(
            "legs" to mapOf("1" to mapOf("leg_id" to "1", "position" to "S", "entry_order_id" to "41", "exit_order_id" to "x",
                "superseded" to mapOf("position" to "B", "exit_order_id" to 7L, "entry_order_id" to 3L, "qty" to 5L, "entry_avg" to 99.5))),
            "orders" to listOf(mapOf("id" to 1L, "leg_id" to 1L, "kind" to "entry", "action" to "SELL")),
            "status" to "nonsense",
        ))
        assertEquals(0L, r.runId); assertEquals(RunMode.SANDBOX, r.mode); assertEquals(StrategyKind.BATCH, r.kind); assertEquals(RunStatus.IDLE, r.status)
        assertEquals(1L, r.nextId); assertNull(r.startedAt)
        val l = r.leg(1)!!
        assertEquals("", l.symbol); assertEquals(0, l.qty); assertEquals(1, l.lots); assertEquals("pending", l.entryStatus)
        assertEquals(41L, l.entryOrderId); assertNull(l.exitOrderId, "a non-numeric id is no id")
        assertEquals(7L, l.superseded!!.exitOrderId); assertEquals(5, l.superseded!!.qty)
        val o = r.orders.getValue(1L)
        assertEquals("pending", o.status); assertEquals(0, o.qty); assertEquals("", o.symbol); assertFalse(o.signal); assertFalse(o.cancelRequested)
        assertEquals(RunStatus.CLOSED, RunStateCodec.fromMap(mapOf("status" to "closed", "stopped_at" to monday.toString())).status)
        val sparseSup = RunStateCodec.legFromMap(mapOf("leg_id" to 2, "position" to "B", "superseded" to mapOf<String, Any?>()))
        assertNull(sparseSup.superseded!!.exitOrderId); assertNull(sparseSup.superseded!!.entryOrderId)
        assertFailsWith<Json.ParseError> { RunStateCodec.legFromMap(mapOf("leg_id" to 1)) }
        assertFailsWith<NumberFormatException> { RunStateCodec.legFromMap(mapOf("leg_id" to "one", "position" to "B")) }
    }

    @Test fun `strategy codec defaults, python-typed lock profit and bad legs`() {
        val d = StrategyCodec.fromMap(mapOf("name" to "N", "underlying" to "NIFTY", "underlying_exchange" to "NSE_INDEX",
            "entry_time" to "9:20", "lock_profit" to mapOf<String, Any?>(), "scheduler" to mapOf<String, Any?>()))
        assertEquals(0L, d.id); assertEquals(StrategyKind.BATCH, d.kind); assertEquals(LocalTime.of(9, 20), d.entryTime)
        assertEquals(LockProfit(LockProfitMode.LOCK, 0.0, 0.0, null, false), d.lockProfit)
        assertEquals(SchedulerConfig(), d.scheduler); assertTrue(d.legs.isEmpty()); assertFalse(d.trailSlToEntry)
        val whole = StrategyCodec.fromMap(mapOf("name" to "N", "underlying" to "N", "underlying_exchange" to "N",
            "lock_profit" to mapOf("mode" to "lock_and_trail", "if_profit_reaches" to 3000L, "lock_profit" to 1000L, "trail_step" to 250.0)))
        assertTrue(whole.lockProfit!!.lockProfitWhole)
        assertEquals(1000L, StrategyCodec.toMap(whole).let { (it["lock_profit"] as Map<*, *>)["lock_profit"] })
        assertFailsWith<Json.ParseError> { StrategyCodec.legFromMap(mapOf("segment" to "options"), StrategyKind.BATCH) }
        assertFailsWith<Json.ParseError> { StrategyCodec.legFromMap(mapOf("id" to 1L, "segment" to "bonds"), StrategyKind.BATCH) }
        val sig = StrategyCodec.legFromMap(mapOf("id" to 1L, "segment" to "cash", "trail" to mapOf("x" to 2.0), "risk_unit" to "?"), StrategyKind.SIGNAL)
        assertEquals(LegSide.BOTH, sig.side); assertEquals(Trail(2.0, 0.0), sig.trail); assertEquals(RiskUnit.POINTS, sig.riskUnit)
        val signalMap = StrategyCodec.legToMap(LegDef(1, Segment.CASH, symbol = "X", exchange = "NSE"), StrategyKind.SIGNAL)
        assertEquals("both", signalMap["side"]); assertNull(signalMap["qty"]); assertNull(signalMap["qty_mode"])
        val batchMap = StrategyCodec.legToMap(LegDef(1, Segment.OPTIONS, strikeInt = 50.0), StrategyKind.BATCH)
        assertNull(batchMap["position"]); assertEquals(50.0, batchMap["strike_int"])
        assertFailsWith<Json.ParseError> { StrategyCodec.fromMap(mapOf("underlying" to "N", "underlying_exchange" to "N")) }
    }

    // ------------------------------------------------------------ presets

    private val day = LocalDate.of(2026, 9, 22)
    private val mins = intArrayOf(555, 556, 557, 558, 559, 560)
    private fun s(k: Double, r: Right, px: DoubleArray, exp: LocalDate? = day.plusDays(1), m: IntArray = mins) =
        Series(if (r == Right.IX) null else exp, k, r, 75, m, px, null, null, null, null, LongArray(px.size))
    private fun session(ix: Boolean = true, expired: Boolean = false, zeroPut: Boolean = false, lateCall: Boolean = false): Session {
        val out = ArrayList<Series>()
        if (ix) out += s(0.0, Right.IX, doubleArrayOf(100.0, 100.0, 100.0, 99.0, 98.0, 97.0))
        for (k in listOf(95.0, 100.0, 105.0)) {
            val e = if (expired) day.minusDays(1) else day.plusDays(1)
            out += if (lateCall && k == 100.0) s(k, Right.CE, doubleArrayOf(5.0), e, intArrayOf(600)) else s(k, Right.CE, DoubleArray(6) { 5.0 }, e)
            out += s(k, Right.PE, DoubleArray(6) { if (zeroPut && k == 100.0) 0.0 else 5.0 + it }, e)
        }
        return Session(day, 75, out)
    }

    @Test fun `preset replay skips what it cannot price`() {
        val st = Presets.byId("short_straddle")!!
        assertNull(Presets.byId("nope"))
        assertNull(Presets.day(st, session(ix = false), 75, 1, 557, 560, null, null), "no index")
        assertNull(Presets.day(st, session(), 75, 1, 500, 560, null, null), "no index bar yet")
        assertNull(Presets.day(st, session(expired = true), 75, 1, 557, 560, null, null), "only expired contracts")
        assertNull(Presets.day(st, session(zeroPut = true), 75, 1, 557, 560, null, null), "a leg priced at zero")
        assertNull(Presets.day(st, session(lateCall = true), 75, 1, 557, 560, null, null), "a leg with no bar at entry")
        assertNull(Presets.day(st, Session(day, 75, listOf(session().index!!)), 75, 1, 557, 560, null, null), "no chain")
        val calls = session().series.filter { it.right != Right.PE }
        assertNull(Presets.day(st, Session(day, 75, calls), 75, 1, 557, 560, null, null), "the put is not listed")
        var n = 0
        val r = Presets.backtest(st, sequenceOf(session(), session(), session()), { s -> if (n == 2) null else if (n == 3) 0 else 75 }, 1, 557, 560, null, null) { n = it }
        assertEquals(3, n)
        assertEquals(1, r.days.size); assertEquals(2, r.skipped)
    }

    @Test fun `preset replay ends on target or time and the result aggregates`() {
        val st = Presets.byId("short_straddle")!!
        // Put rises 1/min from 5: short loses 75/min; a target is never reached, a stop at 100 fires at 559.
        val t = Presets.day(st, session(), 75, 1, 557, 560, null, 10.0)!!
        assertEquals("Time", t.reason); assertEquals(560, t.exitMinute)
        val lp = Presets.day(Presets.byId("iron_fly")!!, session(), 75, 1, 557, 560, null, null)
        assertNull(lp, "wings three strikes out are off this ladder")
        val longPut = Presets.Preset("lp", "Long put", "", listOf(Presets.Leg(Position.B, OptionType.PE, 0)))
        val win = Presets.day(longPut, session(), 75, 1, 557, 560, null, 100.0)!!
        assertEquals("Target", win.reason); assertEquals(559, win.exitMinute); assertEquals(-7.0, win.credit)
        val res = Presets.Result(st, listOf(win, t, win.copy(day = day.plusDays(1))), 0)
        assertEquals(2, res.wins); assertEquals(2.0 / 3, res.winRate, 1e-12); assertEquals(res.net / 3, res.average, 1e-9)
        assertEquals(win.net, res.best); assertEquals(t.net, res.worst)
        assertTrue(res.maxDrawdown < 0); assertEquals(2 * win.net / -t.net, res.profitFactor!!, 1e-9)
        val empty = Presets.Result(st, emptyList(), 3)
        assertEquals(0.0, empty.winRate); assertEquals(0.0, empty.average); assertEquals(0.0, empty.best); assertEquals(0.0, empty.worst)
        assertEquals(0.0, empty.maxDrawdown); assertNull(empty.profitFactor)
        val def = Presets.def(st, "banknifty", 2, LocalTime.of(9, 20), LocalTime.of(15, 0), null, null)
        assertEquals(UniverseTab.MONTHLY_ONLY, def.universeTab); assertEquals("monthly", def.legs[0].expiry); assertEquals("ATM", def.legs[0].atmOffset)
        assertEquals("OTM2", Presets.def(Presets.byId("short_strangle")!!, "NIFTY", 1, LocalTime.of(9, 20), LocalTime.of(15, 0), null, null).legs[0].atmOffset)
    }

    // ------------------------------------------------------------ scheduler

    @Test fun `scheduler parsing, fallbacks and start gates`() {
        assertNull(Scheduler.parseHhmm(null)); assertNull(Scheduler.parseHhmm(915)); assertNull(Scheduler.parseHhmm("24:00")); assertNull(Scheduler.parseHhmm("9:60"))
        assertEquals(9 to 5, Scheduler.parseHhmm(" 9:05 ")); assertEquals(9 to 5, Scheduler.parseHhmm(LocalTime.of(9, 5)))
        assertNull(Scheduler.cronDays(null)); assertNull(Scheduler.cronDays(emptyList<String>())); assertNull(Scheduler.cronDays(listOf("MON", "FUNDAY")))
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), Scheduler.cronDays(listOf("fri", " mon", "FRI")))
        // Scheduled but no start time: only the stop, from exit_time when auto-stop is blank.
        val jobs = Scheduler.plannedJobs(3, mapOf("enabled" to true, "days" to listOf("MON")), "15:10")
        assertEquals(listOf(Scheduler.JobKind.STOP), jobs.map { it.kind })
        assertEquals("Strategy 3 scheduled square-off (exit_time)", jobs.single().name)
        assertEquals(mapOf("job_id" to "strategy:3:stop", "kind" to "stop", "name" to jobs.single().name, "day_of_week" to "mon", "hour" to 15, "minute" to 10),
            jobs.single().asDict())
        assertTrue(Scheduler.plannedJobs(3, mapOf("enabled" to true, "days" to listOf("MON")), null).isEmpty())
        assertTrue(Scheduler.plannedJobs(3, null, null).isEmpty())
        assertEquals(1, Scheduler.plannedJobs(3, mapOf("enabled" to true, "days" to listOf("XYZ"), "start_time" to "09:20"), "15:10").size, "a broken day list falls back to exit_time")
        val def = StrategyDef(id = 4, name = "S", underlying = "N", underlyingExchange = "N", legs = emptyList(),
            scheduler = SchedulerConfig(true, listOf(DayOfWeek.MONDAY), null, LocalTime.of(15, 0)))
        assertEquals(listOf("Strategy 4 scheduled square-off (scheduler.auto_stop_time)"), Scheduler.plannedJobs(def).map { it.name })
        val never = Scheduler.PlannedJob("j", Scheduler.JobKind.START, "n", emptyList(), 9, 0)
        assertNull(Scheduler.previousFire(never, monday)); assertNull(Scheduler.nextFire(never, monday))
        assertTrue(Scheduler.due(def.copy(scheduler = null), null, monday).isEmpty())
        assertEquals(Scheduler.StartDecision.Skip("the scheduler is disabled"), Scheduler.startDecision(def.copy(scheduler = null), false))
        assertEquals(Scheduler.StartDecision.Skip("the scheduler is disabled"), Scheduler.startDecision(def.copy(scheduler = SchedulerConfig(enabled = false)), false))
        val live = def.copy(scheduler = def.scheduler!!.copy(defaultMode = RunMode.LIVE), liveEnabled = true)
        assertEquals(Scheduler.StartDecision.Start(RunMode.LIVE), Scheduler.startDecision(live, false))
        assertNull(Scheduler.stopDecision(false))
        assertEquals(monday.toLocalDate().minusDays(1), com.optionslab.engine.strategy.Session.sessionDay(monday.withHour(2)))
        assertEquals(monday.withHour(3), com.optionslab.engine.strategy.Session.sessionStartedAt(monday))
        // A widened grace catches a slot an infrequent poller would otherwise drop.
        val sched = def.copy(scheduler = SchedulerConfig(true, listOf(DayOfWeek.MONDAY), LocalTime.of(9, 20), null))
        assertTrue(Scheduler.due(sched, null, monday.withHour(9).withMinute(25)).isEmpty())
        assertEquals(1, Scheduler.due(sched, null, monday.withHour(9).withMinute(25), grace = java.time.Duration.ofMinutes(10)).size)
    }

    // ------------------------------------------------------------ host

    @Test fun `host skips polling orders without a broker id or status, and a cancel with no status reads cancelled`() {
        val def = StrategyDef(id = 7, name = "Straddle", underlying = "NIFTY", underlyingExchange = "NSE_INDEX",
            entryTime = LocalTime.of(9, 20), exitTime = LocalTime.of(15, 15),
            legs = listOf(LegDef(1, Segment.OPTIONS, Position.B, 1, OptionType.CE, StrikeMode.ATM, "ATM", expiry = "weekly")))
        val chain = MasterContract(listOf(Instrument("NIFTY05MAY2623600CE", "NFO", "NIFTY", "05-MAY-26", 23600.0, 65, "CE")))
        val placed = ArrayList<Action.PlaceOrder>()
        var statusCalls = 0
        val exec = object : StrategyHost.Executor {
            override fun place(order: Action.PlaceOrder): StrategyHost.Placed { placed += order; return StrategyHost.Placed.Accepted("B1", "OPEN", 0, null) }
            override fun cancel(brokerId: String) = true
            override fun status(brokerId: String): StrategyHost.Status? { statusCalls++; return null }
            override fun event(e: Event, alert: Boolean) {}
        }
        val host = StrategyHost()
        val ids = HashMap<Long, String>()
        val run = host.start(def, SymbolResolver.resolve(def, chain, 23590.0, monday), monday, 1, RunMode.SANDBOX, "manual", exec, ids)
        assertEquals(1, placed.size)
        val polled = host.poll(run, def, exec, monday, HashMap())
        assertEquals(0, statusCalls, "no broker id: not asked"); assertEquals(RunStateCodec.encode(run), RunStateCodec.encode(polled))
        host.poll(run, def, exec, monday, ids)
        assertEquals(1, statusCalls, "asked, nothing to report")
        val stopped = host.stop(run, def, monday, "manual", exec, ids)
        assertEquals(RunStatus.CLOSED, stopped.status, "the working BUY was cancelled and read back as cancelled")
        assertEquals("manual", stopped.stopReason)
    }

    @Test fun `executor results are value objects`() {
        val a = StrategyHost.Placed.Accepted("B", "OPEN", 0, null)
        assertEquals(a, a.copy()); assertEquals("x", StrategyHost.Placed.Refused("x").reason)
        assertEquals(StrategyHost.Status("COMPLETE", 1, 2.0), StrategyHost.Status("COMPLETE", 1, 2.0, null))
    }
}
