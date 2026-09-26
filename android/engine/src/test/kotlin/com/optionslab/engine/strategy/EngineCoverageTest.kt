package com.optionslab.engine.strategy

import com.optionslab.engine.risk.Side
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The claim / bind / release / fill bookkeeping of [Engine], driven directly, plus the small rule objects around it. */
class EngineCoverageTest {
    private fun leg(id: Int = 1, pos: String = "B", status: String = "open", entry: String = "complete", qty: Int = 10,
                    ref: String? = "p$id", avg: Double = 100.0) =
        LegState(id, pos, "SYM$id", "NFO", qty = qty, positionRef = ref, entryStatus = entry, status = status, entryAvg = avg)

    private fun run(vararg legs: LegState) = RunState(1, 1).also { r -> legs.forEach { r.legs[it.legId.toString()] = it } }

    // ---------------------------------------------------------------- leg state

    @Test fun `newLegState normalises side and zero trails and refuses a missing side`() {
        val l = Engine.newLegState(1, "b", "X", "NFO", 2, 130, 5.0, null, Trail(0.0, 3.0), null, "p")
        assertEquals("B", l.position); assertEquals("points", l.riskUnit); assertEquals(0.0, l.trailX); assertEquals(3.0, l.trailY)
        assertEquals(2, l.lots); assertEquals(130, l.qty)
        val t = Engine.newLegState(1, "S", "X", "NFO", 1, 1, null, null, Trail(4.0, 0.0), "percent", null)
        assertEquals(4.0, t.trailX); assertEquals(0.0, t.trailY); assertEquals("percent", t.riskUnit)
        assertEquals(0.0, Engine.newLegState(1, "S", "X", "NFO", 1, 1, null, null, null, null, null).trailX)
        assertEquals("Leg 3 has an unusable position: 'X'",
            assertFailsWith<IllegalArgumentException> { Engine.newLegState(3, "X", "X", "NFO", 1, 1, null, null, null, null, null) }.message)
    }

    @Test fun `favourable peak points and exit in flight`() {
        val b = leg(pos = "B")
        assertEquals(0.0, b.favorablePeakPoints())
        b.highestPrice = 0.0; assertEquals(0.0, b.favorablePeakPoints())
        b.highestPrice = 90.0; assertEquals(0.0, b.favorablePeakPoints())
        b.highestPrice = 112.5; assertEquals(12.5, b.favorablePeakPoints())
        val s = leg(pos = "S")
        s.lowestPrice = 0.0; assertEquals(0.0, s.favorablePeakPoints())
        s.lowestPrice = 95.0; assertEquals(5.0, s.favorablePeakPoints())
        s.lowestPrice = 105.0; assertEquals(0.0, s.favorablePeakPoints())
        assertEquals(0.0, leg(avg = 0.0).favorablePeakPoints())
        assertFalse(leg().exitInFlight)
        assertTrue(leg().apply { exitClaimToken = "t" }.exitInFlight)
        assertTrue(leg().apply { exitOrderId = 5 }.exitInFlight)
    }

    @Test fun `run state helpers derive status and subscriptions`() {
        val r = run(leg(1), leg(2, status = "configured"), leg(3, status = "closed"))
        assertEquals(setOf("SYM1" to "NFO", "SYM2" to "NFO"), r.subscribedSymbols())
        assertEquals(RunStatus.IDLE, r.deriveStatus())
        r.startedAt = ZonedDateTime.now(IST)
        assertEquals(RunStatus.ACTIVE, r.deriveStatus())
        val entering = run(leg(1, status = "configured", entry = "pending")).apply { startedAt = ZonedDateTime.now(IST) }
        assertEquals(RunStatus.ENTERING, entering.deriveStatus())
        val flat = run(leg(1, status = "closed", entry = "complete")).apply { startedAt = ZonedDateTime.now(IST) }
        assertEquals(RunStatus.ACTIVE, flat.deriveStatus())
        assertFalse(flat.requiresManagement())
        flat.signalEntryClaims["1"] = SignalClaim("c", "p", "B", null, null)
        assertTrue(flat.requiresManagement())
        flat.stopRequestedReason = "manual"; assertEquals(RunStatus.EXITING, flat.deriveStatus())
        flat.stoppedAt = ZonedDateTime.now(IST); assertEquals(RunStatus.CLOSED, flat.deriveStatus())
        val sup = Superseded(1, "t", "k", 2, "p", "B", 10.0, 3)
        val c = sup.copy()
        assertEquals(listOf(1L, "t", "k", 2L, "p", "B", 10.0, 3), listOf(c.exitOrderId, c.exitClaimToken, c.exitKind, c.entryOrderId, c.positionRef, c.position, c.entryAvg, c.qty))
    }

    // ------------------------------------------------------------------ claims

    @Test fun `claimLegsForExit claims filled legs and names unfilled ones`() {
        val r = run(leg(1), leg(2, entry = "open"), leg(3, status = "closed"), leg(4).apply { exitKind = "exit_sl" })
        val (claimed, unfilled) = Engine.claimLegsForExit(r, listOf(1, 2, 3, 4, 99), "exit_close_all")
        assertEquals(listOf(1), claimed.map { it.legId })
        assertEquals(listOf(2), unfilled.map { it.legId })
        assertEquals("exit_close_all", r.leg(1)!!.exitKind)
        assertNotNull(r.leg(1)!!.exitClaimToken)
    }

    @Test fun `claimLegExit refuses a missing, closed, unfilled or in-flight leg`() {
        val r = run(leg(1), leg(2, status = "closed"), leg(3, entry = "open"))
        assertNull(Engine.claimLegExit(r, 9, "k"))
        assertNull(Engine.claimLegExit(r, 2, "k"))
        assertNull(Engine.claimLegExit(r, 3, "k"))
        assertNotNull(Engine.claimLegExit(r, 1, "k"))
        assertNull(Engine.claimLegExit(r, 1, "k"), "already in flight")
    }

    @Test fun `superseded claims match side case-insensitively and bind once`() {
        val l = leg(1).apply { superseded = Superseded(positionRef = "old", position = "S", qty = 7) }
        val r = run(l, leg(2))
        assertNull(Engine.claimSupersededExit(r, 9, "S"))
        assertNull(Engine.claimSupersededExit(r, 2, "S"), "no superseded position")
        assertNull(Engine.claimSupersededExit(r, 1, "B"), "other side")
        assertNull(Engine.claimSupersededExit(r, 1, null))
        val c = Engine.claimSupersededExit(r, 1, "s")!!
        assertEquals("S", c.position); assertEquals(7, c.quantity); assertEquals("old", c.positionRef); assertEquals("SYM1", c.symbol)
        assertEquals(1, c.legId); assertEquals("NFO", c.exchange)
        assertNull(Engine.claimSupersededExit(r, 1, "S"), "already claimed")
        assertFalse(Engine.bindSupersededExit(r, 2, c.claimToken, 5))
        assertFalse(Engine.bindSupersededExit(r, 1, null, 5))
        assertFalse(Engine.bindSupersededExit(r, 1, "wrong", 5))
        assertTrue(Engine.bindSupersededExit(r, 1, c.claimToken, 5))
        assertNull(Engine.claimSupersededExit(r, 1, "S"), "bound to an order")
        // Missing position on the superseded record never matches a side, but null==null matches.
        val blank = run(leg(1).apply { superseded = Superseded() })
        assertNull(Engine.claimSupersededExit(blank, 1, "B"))
    }

    @Test fun `bindLiveExit checks token, order and position ref`() {
        val r = run(leg(1).apply { exitClaimToken = "t1" })
        assertFalse(Engine.bindLiveExit(r, 9, "t1", 5, null))
        assertFalse(Engine.bindLiveExit(r, 1, null, 5, null))
        assertFalse(Engine.bindLiveExit(r, 1, "t2", 5, null))
        assertFalse(Engine.bindLiveExit(r, 1, "t1", 5, "other"))
        assertTrue(Engine.bindLiveExit(r, 1, "t1", 5, "p1"))
        assertFalse(Engine.bindLiveExit(r, 1, "t1", 6, null), "already bound")
    }

    @Test fun `release functions undo only their exact claim`() {
        val r = run(leg(1).apply { exitClaimToken = "t"; exitKind = "k"; exitOrderId = 5 })
        assertFalse(Engine.releaseLegExit(r, 1, null))
        assertFalse(Engine.releaseLegExit(r, 9, "t"))
        assertFalse(Engine.releaseLegExit(r, 1, "x"))
        assertTrue(Engine.releaseLegExit(r, 1, 5L))
        assertNull(r.leg(1)!!.exitKind)
        r.leg(1)!!.exitClaimToken = "t"
        assertTrue(Engine.releaseLegExit(r, 1, "t"))

        val s = run(leg(1).apply { superseded = Superseded(exitOrderId = 7, exitClaimToken = "st", exitKind = "k") }, leg(2))
        assertFalse(Engine.releaseSupersededExit(s, 1, null))
        assertFalse(Engine.releaseSupersededExit(s, 2, "st"))
        assertFalse(Engine.releaseSupersededExit(s, 9, "st"))
        assertFalse(Engine.releaseSupersededExit(s, 1, "zz"))
        assertTrue(Engine.releaseSupersededExit(s, 1, 7L))
        s.leg(1)!!.superseded!!.exitClaimToken = "st"
        assertTrue(Engine.releaseSupersededExit(s, 1, "st"))
        assertNull(s.leg(1)!!.superseded!!.exitKind)
    }

    @Test fun `releaseOrderExit names the owner it released`() {
        fun fresh() = run(leg(1, ref = "live").apply {
            exitOrderId = 5; exitKind = "k"
            superseded = Superseded(exitOrderId = 7, positionRef = "old", position = "S")
        })
        assertNull(Engine.releaseOrderExit(fresh(), 9, 5, null))
        assertEquals("superseded", Engine.releaseOrderExit(fresh(), 1, 7, null))
        assertEquals("superseded", Engine.releaseOrderExit(fresh(), 1, 7, "old"))
        assertNull(Engine.releaseOrderExit(fresh(), 1, 7, "live"), "ref mismatch on both owners")
        assertEquals("live", Engine.releaseOrderExit(fresh(), 1, 5, null))
        assertEquals("live", Engine.releaseOrderExit(fresh(), 1, 5, "live"))
        assertNull(Engine.releaseOrderExit(fresh(), 1, 5, "old"))
        assertNull(Engine.releaseOrderExit(fresh(), 1, 99, null))
        val noSup = run(leg(1).apply { exitOrderId = 5 })
        assertEquals("live", Engine.releaseOrderExit(noSup, 1, 5, null))
    }

    // ----------------------------------------------------------- signal claims

    @Test fun `signal entry claims answer every no-op note`() {
        assertEquals("run_stopping", Engine.claimSignalEntry(RunState(1, 1, stopping = true), 1, "B").note)
        val r = run(leg(1, pos = "B"))
        assertEquals("already_long", Engine.claimSignalEntry(r, 1, "b").note)
        val sh = run(leg(1, pos = "S"))
        assertEquals("already_short", Engine.claimSignalEntry(sh, 1, "S").note)
        val both = run(leg(1, pos = "B").apply { superseded = Superseded(position = "S") })
        assertEquals("flip_pending", Engine.claimSignalEntry(both, 1, "S").note)
        val supOnly = run(leg(1, pos = "B", status = "closed").apply { superseded = Superseded(position = "S") })
        assertEquals("already_short", Engine.claimSignalEntry(supOnly, 1, "S").note)
        assertEquals("flip_pending", Engine.claimSignalEntry(supOnly, 1, "B").note)
        val exiting = run(leg(1, pos = "B").apply { exitKind = "exit_signal" })
        assertEquals("flip_pending", Engine.claimSignalEntry(exiting, 1, "S").note)
        val fresh = RunState(1, 1)
        val c = Engine.claimSignalEntry(fresh, 1, "B").claim!!
        assertNull(c.heldPosition); assertNull(c.expectedPositionRef)
        assertEquals("flip_pending", Engine.claimSignalEntry(fresh, 1, "B").note)
        assertFalse(Engine.releaseSignalEntryClaim(fresh, 2, c.claimToken))
        assertFalse(Engine.releaseSignalEntryClaim(fresh, 1, "other"))
        assertTrue(Engine.releaseSignalEntryClaim(fresh, 1, c.claimToken))
    }

    @Test fun `addLeg installs only over the exact expected owner`() {
        fun claimed(r: RunState, pos: String) = Engine.claimSignalEntry(r, 1, pos).claim!!
        fun newLeg(c: SignalClaim) = leg(1, pos = c.position, status = "configured", entry = "pending", ref = c.positionRef)
        // Stopping, or no claim.
        run().also { r -> val c = claimed(r, "B"); r.stopping = true; assertNull(Engine.addLeg(r, newLeg(c), c.claimToken, null, 9)) }
        assertNull(Engine.addLeg(RunState(1, 1), leg(1), "t", null, 9))
        // Wrong token / ref / expected ref.
        run().also { r ->
            val c = claimed(r, "B")
            assertNull(Engine.addLeg(r, newLeg(c), "bad", null, 9))
            assertNull(Engine.addLeg(r, newLeg(c).apply { positionRef = "zz" }, c.claimToken, null, 9))
            assertNull(Engine.addLeg(r, newLeg(c), c.claimToken, "zz", 9))
            val installed = Engine.addLeg(r, newLeg(c), c.claimToken, null, 9)
            assertSame(installed, r.leg(1))
            assertEquals(9L, r.leg(1)!!.entryOrderId)
        }
        // Previous leg changed ref under the claim.
        run(leg(1, pos = "B", status = "closed", ref = "p1")).also { r ->
            val c = claimed(r, "S")
            r.leg(1)!!.positionRef = "moved"
            assertNull(Engine.addLeg(r, newLeg(c), c.claimToken, "p1", 9))
        }
        // Previous has a superseded position already.
        run(leg(1, pos = "B", status = "closed", ref = "p1")).also { r ->
            val c = claimed(r, "S")
            r.leg(1)!!.superseded = Superseded(position = "S")
            assertNull(Engine.addLeg(r, newLeg(c), c.claimToken, "p1", 9))
        }
        // A held leg must have its own exit claimed first.
        run(leg(1, pos = "B", ref = "p1")).also { r ->
            val c = claimed(r, "S")
            assertNull(Engine.addLeg(r, newLeg(c), c.claimToken, "p1", 9))
            r.leg(1)!!.exitKind = "exit_signal"
            assertNull(Engine.addLeg(r, newLeg(c), c.claimToken, "p1", 9), "kind without token")
            r.leg(1)!!.exitClaimToken = "t"; r.leg(1)!!.realizedPnl = 42.0
            val installed = Engine.addLeg(r, newLeg(c), c.claimToken, "p1", 9)!!
            assertEquals("p1", installed.superseded!!.positionRef)
            assertEquals(42.0, installed.realizedPnl)
        }
        // A held claim whose previous leg closed in the meantime: no superseded, P&L carried.
        run(leg(1, pos = "B", ref = "p1")).also { r ->
            val c = claimed(r, "S")
            r.leg(1)!!.status = "closed"; r.leg(1)!!.realizedPnl = 5.0
            val installed = Engine.addLeg(r, newLeg(c), c.claimToken, "p1", 9)!!
            assertNull(installed.superseded); assertEquals(5.0, installed.realizedPnl)
        }
    }

    @Test fun `finishSignalEntry applies only to its own incarnation`() {
        val r = RunState(1, 1)
        val c = Engine.claimSignalEntry(r, 1, "B").claim!!
        assertFalse(Engine.finishSignalEntry(r, 1, c.positionRef, c.claimToken, true), "no leg yet")
        r.legs["1"] = leg(1, entry = "pending", status = "configured", ref = c.positionRef)
        assertFalse(Engine.finishSignalEntry(r, 1, "zz", c.claimToken, true))
        assertFalse(Engine.finishSignalEntry(r, 1, c.positionRef, "zz", true))
        r.leg(1)!!.entryStatus = "complete"
        assertTrue(Engine.finishSignalEntry(r, 1, c.positionRef, c.claimToken, false))
        assertEquals("complete", r.leg(1)!!.entryStatus, "a fill that beat the ack is not undone")
        assertFalse(Engine.finishSignalEntry(r, 1, c.positionRef, c.claimToken, true), "claim gone")
    }

    // ------------------------------------------------------------------- fills

    @Test fun `exit fill quantities clamp to the held size`() {
        assertEquals(10 to 0, Engine.exitFillQuantities(null, 10))
        assertEquals(4 to 6, Engine.exitFillQuantities(4, 10))
        assertEquals(10 to 0, Engine.exitFillQuantities(40, 10))
        assertEquals(0 to 10, Engine.exitFillQuantities(-3, 10))
        assertEquals(0 to 0, Engine.exitFillQuantities(5, null))
        assertEquals(0 to 0, Engine.exitFillQuantities(5, -2))
    }

    @Test fun `applyFill ignores fills for other incarnations and orders`() {
        val r = run(leg(1).apply { entryOrderId = 3; exitOrderId = 4; exitKind = "k" })
        assertEquals("no such leg", Engine.applyFill(r, 9, 1.0, true).ignored)
        assertTrue(Engine.applyFill(r, 1, 1.0, true, positionRef = "zz").ignored!!.contains("live position is p1"))
        assertTrue(Engine.applyFill(r, 1, 1.0, true, orderId = 8).ignored!!.contains("waiting on 3"))
        assertTrue(Engine.applyFill(r, 1, 1.0, false, orderId = 8).ignored!!.contains("waiting on 4"))
        assertNull(Engine.applyFill(r, 1, 101.0, true, orderId = 8, allowPriorCorrection = true).ignored)
        val noExit = run(leg(1))
        assertTrue(Engine.applyFill(noExit, 1, 1.0, false, orderId = 8).ignored!!.contains("no exit in flight"))
        assertNull(Engine.applyFill(noExit, 1, 110.0, false, orderId = 8, allowPriorCorrection = true).ignored)
    }

    @Test fun `entry fills set price and size, exit fills book P&L on the right side`() {
        val r = run(leg(1, pos = "S", entry = "pending", status = "configured", qty = 65, avg = 0.0).apply { entryOrderId = 2 })
        val f1 = Engine.applyFill(r, 1, null, true, 30, 2, "p1", cumulativeFilledQty = 30, terminal = false)
        assertFalse(f1.entryApplied)
        assertEquals(2, f1.warnings.size)
        assertEquals(30, r.leg(1)!!.qty); assertEquals(30, r.leg(1)!!.entryFilledQty); assertEquals("open", r.leg(1)!!.entryStatus)
        val f2 = Engine.applyFill(r, 1, 100.0, true, 65, 2, "p1")
        assertTrue(f2.entryApplied); assertEquals(65, r.leg(1)!!.qty); assertEquals(100.0, r.leg(1)!!.entryAvg)
        // Exit without an order id: whole quantity, short books entry - exit.
        val f3 = Engine.applyFill(r, 1, 90.0, false, null)
        assertTrue(f3.wentFlat)
        assertEquals(650.0, r.leg(1)!!.realizedPnl, 1e-9)
        assertEquals("closed", r.leg(1)!!.status); assertEquals(0.0, r.leg(1)!!.mtm)
        assertEquals(650.0, r.pnlPeak, 1e-9)
        // An exit with no price books nothing and says so.
        val r2 = run(leg(1, qty = 10).apply { exitOrderId = 4; exitKind = "k" })
        val f4 = Engine.applyFill(r2, 1, null, false, 4, 4, terminal = false)
        assertTrue(f4.warnings.single().contains("without complete fill pricing"))
        assertEquals(6, r2.leg(1)!!.qty); assertEquals("open", r2.leg(1)!!.status)
        assertEquals(4L, r2.leg(1)!!.exitOrderId, "a non-terminal partial keeps its claim")
        // An exit of zero applied quantity books nothing and warns nothing.
        val f5 = Engine.applyFill(r2, 1, 120.0, false, 0, 4, terminal = true)
        assertTrue(f5.warnings.isEmpty()); assertNull(r2.leg(1)!!.exitOrderId)
        // An exit of an entry priced at zero books nothing either.
        val r3 = run(leg(1, avg = 0.0).apply { exitOrderId = 4 })
        assertTrue(Engine.applyFill(r3, 1, 120.0, false, null, 4).warnings.isNotEmpty())
    }

    @Test fun `superseded fills settle the outgoing owner by ref, by order, or by elimination`() {
        fun fresh(exitOrder: Long? = 7, qty: Int? = 10, entryAvg: Double? = 100.0, pos: String = "S") = run(
            leg(1, pos = "B", ref = "new").apply { superseded = Superseded(exitOrderId = exitOrder, exitClaimToken = "t", exitKind = "k", positionRef = "old", position = pos, entryAvg = entryAvg, qty = qty) })
        // By ref, partial, non-terminal: remains, claim kept.
        fresh().also { r ->
            Engine.applyFill(r, 1, 90.0, false, 4, 7, "old", terminal = false)
            assertEquals(6, r.leg(1)!!.superseded!!.qty); assertEquals(7L, r.leg(1)!!.superseded!!.exitOrderId)
            assertEquals(40.0, r.leg(1)!!.realizedPnl, 1e-9)
        }
        // By order id, partial, terminal: remains, claim released.
        fresh().also { r ->
            Engine.applyFill(r, 1, 90.0, false, 4, 7, null, terminal = true)
            assertEquals(6, r.leg(1)!!.superseded!!.qty); assertNull(r.leg(1)!!.superseded!!.exitOrderId)
        }
        // By elimination (no ref, no order, live leg has no exit): whole, terminal -> gone.
        fresh().also { r ->
            Engine.applyFill(r, 1, 110.0, false, null, null, null)
            assertNull(r.leg(1)!!.superseded)
            assertEquals(-100.0, r.leg(1)!!.realizedPnl, 1e-9)
        }
        // A terminal frame for another order: whole quantity but not released -> kept.
        fresh().also { r ->
            Engine.applyFill(r, 1, 110.0, false, null, null, "old", terminal = true)
            assertNull(r.leg(1)!!.superseded)
        }
        fresh().also { r ->
            Engine.applyFill(r, 1, 110.0, false, 10, 99, "old", terminal = true)
            assertEquals(0, r.leg(1)!!.superseded!!.qty, "fully applied but the claim belongs to order 7")
        }
        // Long outgoing, missing entry price and missing quantity.
        fresh(entryAvg = null, qty = null, pos = "B").also { r ->
            Engine.applyFill(r, 1, 110.0, false, 5, 7, "old")
            assertEquals(0.0, r.leg(1)!!.realizedPnl); assertNull(r.leg(1)!!.superseded)
        }
        // Not superseded: an entry, or an exit naming the live ref.
        fresh().also { r ->
            assertNull(Engine.applyFill(r, 1, 100.0, true, 10, null, "new").ignored)
            assertNotNull(r.leg(1)!!.superseded)
        }
        // No ref, an order that is not the superseded one, while the live leg has an exit: goes to the live leg.
        fresh().also { r ->
            r.leg(1)!!.exitOrderId = 8; r.leg(1)!!.exitKind = "k"
            Engine.applyFill(r, 1, 120.0, false, null, 8, null)
            assertEquals("closed", r.leg(1)!!.status); assertNotNull(r.leg(1)!!.superseded)
        }
        // No ref and no order id while the superseded exit is unbound: null == null attributes it to the
        // outgoing owner (as the Python's `None == None` does), even though the live leg has an exit bound.
        fresh(exitOrder = null).also { r ->
            r.leg(1)!!.exitOrderId = 8
            Engine.applyFill(r, 1, 120.0, false, null, null, null)
            assertNull(r.leg(1)!!.superseded)
            assertEquals("open", r.leg(1)!!.status)
        }
    }

    // --------------------------------------------------------------- tick/risk

    private val base = StrategyDef(
        id = 1, name = "T", underlying = "NIFTY", underlyingExchange = "NSE_INDEX", entryTime = LocalTime.of(9, 20),
        legs = listOf(LegDef(1, Segment.OPTIONS, Position.B, 1, OptionType.CE, StrikeMode.ATM, "ATM", expiry = "weekly")),
    )

    @Test fun `daily loss limit reading`() {
        assertNull(Engine.dailyLossLimit(base))
        assertNull(Engine.dailyLossLimit(base.copy(dailyLossLimitInr = 0.0)))
        assertEquals(500.0, Engine.dailyLossLimit(base.copy(dailyLossLimitInr = -500.0)))
        assertNull(Engine.dailyLossLimit(base.copy(dailyLossLimitInr = Double.NaN)), "NaN is not a limit")
        val r = RunState(1, 1).apply { pnlTotal = -100.0 }
        val d = base.copy(dailyLossLimitInr = 500.0)
        assertNull(Engine.dailyLossBreached(base, -1e9, r))
        assertNull(Engine.dailyLossBreached(d, null, r))
        assertNull(Engine.dailyLossBreached(d, -399.0, r))
        assertEquals("Daily loss limit reached: the session is down 500.00 against a limit of 500.00", Engine.dailyLossBreached(d, -400.0, r))
    }

    @Test fun `evaluateTick ignores other instruments and closed legs, and reports targets and trail arming`() {
        val l = leg(1, pos = "B", qty = 10).apply { targetPts = 5.0; trailX = 2.0 }
        val closed = leg(2, status = "closed").apply { symbol = "SYM1" }
        val r = run(l, closed)
        val other = Engine.evaluateTick(r, base, "SYM1", "BSE", 200.0, null)
        assertTrue(other.legExits.isEmpty() && other.events.isEmpty())
        val armed = Engine.evaluateTick(r, base, "SYM1", "NFO", 102.5, null)
        assertTrue(armed.events.any { it.kind == "leg_trail_armed" }, armed.events.toString())
        val hit = Engine.evaluateTick(r, base, "SYM1", "NFO", 105.0, null)
        assertEquals(listOf(1 to "exit_target"), hit.legExits)
        assertEquals("leg_target_hit", hit.events.last { it.legId == 1 }.kind)
        // Trail to entry with a target exit (not a stop) moves nothing.
        val tte = base.copy(trailSlToEntry = true)
        val r2 = run(leg(1, pos = "B").apply { targetPts = 5.0 }, leg(2, pos = "S").apply { symbol = "SYM2" })
        assertTrue(Engine.evaluateTick(r2, tte, "SYM1", "NFO", 106.0, null).events.none { it.kind == "trail_to_entry_activated" })
        // A stop with no other open leg in profit moves nothing either.
        val r3 = run(leg(1, pos = "B").apply { slPts = 5.0 })
        assertTrue(Engine.evaluateTick(r3, tte, "SYM1", "NFO", 94.0, null).events.none { it.kind == "trail_to_entry_activated" })
    }

    @Test fun `basket target, lock profit arming and floor text`() {
        val tgt = base.copy(overallTargetMtm = 100.0)
        val d1 = Engine.evaluateTick(run(leg(1, qty = 10)), tgt, "SYM1", "NFO", 111.0, null)
        assertEquals("overall_target", d1.stopReason)
        val ev = d1.events.single { it.kind == "overall_target_hit" }
        assertEquals(100.0, ev.payload!!["threshold"])
        val lock = base.copy(lockProfit = LockProfit(LockProfitMode.LOCK, 100.0, 50.0, lockProfitWhole = true))
        val r = run(leg(1, qty = 10))
        val d2 = Engine.evaluateTick(r, lock, "SYM1", "NFO", 111.0, null)
        assertEquals("Lock profit armed with a floor of 50", d2.events.single { it.kind == "lock_profit_armed" }.message)
        val d3 = Engine.evaluateTick(r, lock, "SYM1", "NFO", 104.0, null)
        assertEquals("lock_profit", d3.stopReason)
        val notWhole = base.copy(lockProfit = LockProfit(LockProfitMode.LOCK, 100.0, 50.0))
        assertEquals("Lock profit armed with a floor of 50.0",
            Engine.evaluateTick(run(leg(1, qty = 10)), notWhole, "SYM1", "NFO", 111.0, null).events.single { it.kind == "lock_profit_armed" }.message)
        val trail = base.copy(lockProfit = LockProfit(LockProfitMode.LOCK_AND_TRAIL, 100.0, 50.0, 20.0, lockProfitWhole = true))
        val rt = run(leg(1, qty = 10))
        Engine.evaluateTick(rt, trail, "SYM1", "NFO", 111.0, null)
        val adv = Engine.evaluateTick(rt, trail, "SYM1", "NFO", 130.0, null)
        assertEquals("Lock profit floor advanced to 280.0", adv.events.single { it.kind == "lock_profit_floor_advanced" }.message)
    }

    // --------------------------------------------------------------- rules

    @Test fun `order rules - product per venue, exit side and payload`() {
        assertEquals("MIS", OrderRules.productForExchange("mis", "NSE"))
        assertEquals("NRML", OrderRules.productForExchange("CNC", "nfo"))
        assertEquals("CNC", OrderRules.productForExchange(null, null))
        assertEquals("SELL", OrderRules.exitAction("b")); assertEquals("BUY", OrderRules.exitAction("S"))
        assertEquals("Cannot derive an exit action from position None", assertFailsWith<IllegalArgumentException> { OrderRules.exitAction(null) }.message)
        assertFailsWith<IllegalArgumentException> { OrderRules.exitAction("X") }
        assertEquals(
            linkedMapOf("symbol" to "X", "exchange" to "NFO", "action" to "BUY", "quantity" to "65", "product" to "NRML",
                "pricetype" to "MARKET", "price" to "0", "trigger_price" to "0", "strategy" to "S"),
            OrderRules.buildOrder("X", "NFO", "buy", 65, "CNC", "S"),
        )
        val lim = OrderRules.buildOrder("X", "NSE", "sell", 1, "MIS", "S", "SL", 101.5, 100.25)
        assertEquals("101.5", lim["price"]); assertEquals("100.25", lim["trigger_price"]); assertEquals("MIS", lim["product"])
    }

    private val sig = StrategyDef(
        id = 9, name = "Alerts", kind = StrategyKind.SIGNAL, underlying = "RELIANCE", underlyingExchange = "NSE",
        entryTime = LocalTime.of(9, 15), exitTime = LocalTime.of(15, 20), product = Product.MIS,
        legs = listOf(
            LegDef(1, Segment.CASH, symbol = "RELIANCE", exchange = "NSE", side = LegSide.LONG, qty = 10),
            LegDef(2, Segment.FUTURES, symbol = "NIFTY26MAY26FUT", exchange = "NFO", qty = 2, qtyMode = QtyMode.LOTS),
            LegDef(3, Segment.CASH, symbol = "TCS", exchange = null, qty = 1),
        ),
    )
    private val now = ZonedDateTime.of(2026, 5, 4, 10, 0, 0, 0, IST)

    @Test fun `signal gate - actions, kind, direction, leg lookup and side filter`() {
        assertEquals(listOf("long_entry", "long_exit", "short_entry", "short_exit"), Signals.actionsFor(StrategyKind.SIGNAL))
        assertEquals(listOf("start", "stop"), Signals.actionsFor(StrategyKind.BATCH))
        assertEquals("Unknown signal action: 'buy'", Signals.gate(sig, "buy", 1, null, null, now).result!!.error)
        assertEquals("A batch strategy accepts start and stop, not 'long_entry'",
            Signals.gate(sig.copy(kind = StrategyKind.BATCH), "long_entry", 1, null, null, now).result!!.error)
        assertEquals("This strategy is long_only; a short signal is not accepted",
            Signals.gate(sig.copy(direction = Direction.LONG_ONLY), "short_exit", 2, null, null, now).result!!.error)
        assertEquals("This strategy is short_only; a long signal is not accepted",
            Signals.gate(sig.copy(direction = Direction.SHORT_ONLY), "long_entry", 2, null, null, now).result!!.error)
        assertNull(Signals.gate(sig.copy(direction = Direction.SHORT_ONLY), "short_entry", 2, null, null, now).result)
        assertEquals("No leg matches this signal", Signals.gate(sig, "long_entry", 7, null, null, now).result!!.error)
        assertEquals("No leg matches this signal", Signals.gate(sig, "long_entry", null, null, null, now).result!!.error)
        assertEquals("No leg matches this signal", Signals.gate(sig, "long_entry", null, "", null, now).result!!.error)
        assertEquals("Leg 1 only accepts long signals", Signals.gate(sig, "short_entry", 1, null, null, now).result!!.error)
        val g = Signals.gate(sig, "long_entry", null, "nifty26may26fut", "nfo", now)
        assertEquals(2, g.leg!!.id); assertEquals("long", g.side)
        assertEquals(2, Signals.findLeg(sig, null, "NIFTY26MAY26FUT", null)!!.id)
        assertEquals(3, Signals.findLeg(sig, null, "TCS", "")!!.id)
        assertNull(Signals.findLeg(sig, null, "TCS", "NSE"), "the leg has no exchange")
        assertNull(Signals.findLeg(sig, null, "RELIANCE", "BSE"))
    }

    @Test fun `signal windows apply only to intraday strategies`() {
        val pos = sig.copy(strategyType = StrategyType.POSITIONAL)
        assertNull(Signals.windowNote(pos, "long_entry", now.withHour(23)))
        val noTimes = sig.copy(entryTime = null, exitTime = null)
        assertNull(Signals.windowNote(noTimes, "long_entry", now.withHour(1)))
        assertEquals("outside_trading_window", Signals.windowNote(sig, "long_exit", now.withHour(15).withMinute(20)))
        assertNull(Signals.windowNote(sig, "long_exit", now.withHour(9).withMinute(0)), "exits may come before entry time")
        assertEquals("outside_entry_window", Signals.windowNote(sig, "short_entry", now.withHour(9).withMinute(0)))
        // IST is applied whatever the zone of the clock.
        assertEquals("outside_trading_window", Signals.windowNote(sig, "long_exit", now.withHour(15).withMinute(30).withZoneSameInstant(java.time.ZoneOffset.UTC)))
    }

    @Test fun `carry-short refusal and signal leg resolution`() {
        val cnc = sig.copy(product = Product.CNC)
        assertNull(Signals.uncarryableShort(cnc, sig.legs[0], "long"))
        assertNull(Signals.uncarryableShort(sig, sig.legs[0], "short"), "MIS")
        assertNotNull(Signals.uncarryableShort(cnc, sig.legs[0], "short"))
        assertNull(Signals.uncarryableShort(cnc, sig.legs[1], "short"), "derivative")
        assertNull(Signals.uncarryableShort(cnc, sig.legs[2], "short"), "no exchange named")
        val mc = MasterContract(listOf(
            Instrument("RELIANCE", "NSE", "RELIANCE", null, null, 1, "EQ"),
            Instrument("NIFTY26MAY26FUT", "NFO", "NIFTY", "26-MAY-26", null, 65, "FUT"),
        ))
        val (s1, e1) = Signals.resolveSignalLeg(sig.legs[1], "long", mc)
        assertNull(e1); assertEquals(130, s1!!.quantity); assertEquals(2, s1.lots); assertEquals(65, s1.lotSize)
        val (s2, _) = Signals.resolveSignalLeg(sig.legs[0].copy(symbol = "reliance", exchange = "nse"), "long", mc)
        assertEquals("RELIANCE", s2!!.symbol); assertEquals(10, s2.quantity); assertEquals(1, s2.lots)
        for (bad in listOf(sig.legs[2], sig.legs[0].copy(symbol = ""), sig.legs[0].copy(qty = null), sig.legs[0].copy(qty = 0))) {
            assertEquals("symbol, exchange and quantity are all required", Signals.resolveSignalLeg(bad, "long", mc).second)
        }
        assertEquals("NIFTY is not a contract on NFO", Signals.resolveSignalLeg(sig.legs[1].copy(symbol = "NIFTY"), "long", mc).second)
        assertEquals("10 is not a whole number of lots; NIFTY26MAY26FUT trades in lots of 65",
            Signals.resolveSignalLeg(sig.legs[1].copy(qty = 10, qtyMode = QtyMode.UNITS), "long", mc).second)
        assertEquals("Quantity must be greater than zero", Signals.resolveSignalLeg(sig.legs[0].copy(qty = -1), "long", mc).second)
    }

    @Test fun `risk adapter reads sides strictly and scales percent units`() {
        assertEquals(Side.BUY, RiskAdapter.side("b")); assertEquals(Side.SELL, RiskAdapter.side("S"))
        assertEquals("Unusable leg position: None", assertFailsWith<IllegalArgumentException> { RiskAdapter.side(null) }.message)
        val pct = leg(1, pos = "S", avg = 200.0).apply { riskUnit = "PERCENT"; slPts = 10.0; targetPts = 5.0; trailX = 1.0; trailY = 0.5 }
        val p = RiskAdapter.legToPositionRisk(pct)
        assertEquals(220.0, p.stopPrice); assertEquals(190.0, p.targetPrice)
        assertEquals(2.0, p.trailTrigger); assertEquals(1.0, p.trailStep)
        val unpriced = leg(1, avg = 0.0).apply { riskUnit = "percent"; slPts = 10.0 }
        assertNull(RiskAdapter.legToPositionRisk(unpriced).stopPrice, "no entry, no percent stop")
        val zero = leg(1).apply { slPts = 0.0; targetPts = -1.0 }
        assertNull(RiskAdapter.legToPositionRisk(zero).stopPrice); assertNull(RiskAdapter.legToPositionRisk(zero).targetPrice)
    }
}
