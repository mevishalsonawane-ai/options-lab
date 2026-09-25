package com.optionslab.engine.strategy

import com.optionslab.engine.risk.Side
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostTest {
    private val monday = ZonedDateTime.of(2026, 5, 4, 10, 0, 0, 0, IST)

    private val contract = MasterContract(buildList {
        for (i in 0..8) {
            val s = 23400.0 + 50 * i
            add(Instrument("NIFTY05MAY26${s.toInt()}CE", "NFO", "NIFTY", "05-MAY-26", s, 65, "CE"))
            add(Instrument("NIFTY05MAY26${s.toInt()}PE", "NFO", "NIFTY", "05-MAY-26", s, 65, "PE"))
        }
    })

    private val def = StrategyDef(
        id = 7, name = "Short straddle", underlying = "NIFTY", underlyingExchange = "NSE_INDEX",
        entryTime = LocalTime.of(9, 20), exitTime = LocalTime.of(15, 15),
        legs = listOf(
            LegDef(1, Segment.OPTIONS, Position.S, 1, OptionType.CE, StrikeMode.ATM, "ATM", expiry = "weekly", slPts = 10.0),
            LegDef(2, Segment.OPTIONS, Position.S, 1, OptionType.PE, StrikeMode.ATM, "ATM", expiry = "weekly", slPts = 10.0),
        ),
    )

    /** A venue that fills every MARKET order at the price it is told. */
    private class FakeVenue(var price: Double) : StrategyHost.Executor {
        val placed = ArrayList<Action.PlaceOrder>()
        val alerts = ArrayList<Event>()
        val events = ArrayList<Event>()
        var refuseNext: String? = null
        /** The venue accepts, then its RMS rejects - Kite's usual margin refusal. */
        var rejectAfterAccept: String? = null
        val refused = ArrayList<Action.PlaceOrder>()
        override fun place(order: Action.PlaceOrder): StrategyHost.Placed {
            refuseNext?.let { refuseNext = null; refused += order; return StrategyHost.Placed.Refused(it) }
            rejectAfterAccept?.let { rejectAfterAccept = null; refused += order; return StrategyHost.Placed.Accepted("R${refused.size}", "REJECTED", 0, null, it) }
            placed += order
            return StrategyHost.Placed.Accepted("B${placed.size}", "COMPLETE", order.quantity, price)
        }
        override fun cancel(brokerId: String) = true
        override fun status(brokerId: String) = StrategyHost.Status("COMPLETE", 0, price)
        override fun event(e: Event, alert: Boolean) { events += e; if (alert) alerts += e }
    }

    @Test fun `a stop-loss exit is placed and filled without anyone in the loop`() {
        val host = StrategyHost()
        val venue = FakeVenue(100.0)
        val ids = HashMap<Long, String>()
        val resolved = SymbolResolver.resolve(def, contract, 23590.0, monday)
        var run = host.start(def, resolved, monday, 1, RunMode.SANDBOX, "manual", venue, ids)
        assertEquals(2, venue.placed.size)
        assertTrue(venue.placed.all { it.side == Side.SELL && it.quantity == 65 })
        assertEquals(RunStatus.ACTIVE, run.status)
        assertEquals(2, run.openLegs().size)

        // The call leg rises 12 points: through its 10-point stop.
        val ce = run.legs.values.first { it.symbol.endsWith("CE") }
        val pe = run.legs.values.first { it.symbol.endsWith("PE") }
        venue.price = 112.0
        run = host.tick(run, def, listOf(Quote(ce.symbol, "NFO", 112.0), Quote(pe.symbol, "NFO", 99.0)), monday.plusMinutes(5), 0.0, venue, ids)
        val exit = venue.placed.last()
        assertEquals(Side.BUY, exit.side)
        assertEquals(ce.symbol, exit.symbol)
        assertEquals(1, run.openLegs().size)
        assertEquals(-12.0 * 65, run.legs.values.first { it.symbol == ce.symbol }.realizedPnl, 1e-9)

        // Stop the rest: the put is bought back and the run closes.
        venue.price = 95.0
        run = host.stop(run, def, monday.plusMinutes(10), "manual", venue, ids)
        assertEquals(RunStatus.CLOSED, run.status)
        assertEquals(4, venue.placed.size)
    }

    @Test fun `a refused entry is reported, not assumed filled`() {
        val host = StrategyHost()
        val venue = FakeVenue(100.0).apply { refuseNext = "margin" }
        val ids = HashMap<Long, String>()
        val run = host.start(def, SymbolResolver.resolve(def, contract, 23590.0, monday), monday, 2, RunMode.SANDBOX, "manual", venue, ids)
        // IraAlgo keeps the run going with the leg that did fill; the refusal is an audit event the app must surface.
        assertEquals("rejected", run.legs.values.first { it.symbol.endsWith("CE") }.status)
        assertEquals("open", run.legs.values.first { it.symbol.endsWith("PE") }.status)
        assertTrue(venue.events.any { it.severity != "info" && it.legId != null })
    }

    @Test fun `a refused wing stops its short from being sent unhedged`() {
        val hedged = def.copy(legs = listOf(
            LegDef(1, Segment.OPTIONS, Position.S, 1, OptionType.PE, StrikeMode.ATM, "ATM", expiry = "weekly"),
            LegDef(2, Segment.OPTIONS, Position.B, 1, OptionType.PE, StrikeMode.ATM, "OTM2", expiry = "weekly"),
        ))
        val host = StrategyHost()
        val venue = FakeVenue(100.0).apply { refuseNext = "insufficient margin" }
        val ids = HashMap<Long, String>()
        val run = host.start(hedged, SymbolResolver.resolve(hedged, contract, 23590.0, monday), monday, 3, RunMode.SANDBOX, "manual", venue, ids)
        assertTrue(venue.placed.isEmpty(), "nothing may reach the venue: ${venue.placed}")
        assertTrue(run.openLegs().isEmpty())
    }

    @Test fun `an editor-built strategy passes IraAlgo's validator and round-trips`() {
        val r = StrategyValidator.check(def.copy(overallSlMtm = 3000.0, liveEnabled = true))
        assertTrue(r is StrategyValidator.Result.Ok, r.toString())
        val ok = (r as StrategyValidator.Result.Ok).def
        assertEquals(7L, ok.id); assertTrue(ok.liveEnabled)
        assertEquals(ok, StrategyCodec.decode(StrategyCodec.encode(ok)))
        val bad = StrategyValidator.check(def.copy(legs = emptyList()))
        assertTrue(bad is StrategyValidator.Result.Invalid)
    }

    private val spread = def.copy(legs = listOf(
        LegDef(1, Segment.OPTIONS, Position.B, 1, OptionType.PE, StrikeMode.ATM, "OTM2", expiry = "weekly"),
        LegDef(2, Segment.OPTIONS, Position.S, 1, OptionType.PE, StrikeMode.ATM, "ATM", expiry = "weekly"),
    ))

    @Test fun `a wing the exchange rejects after accepting still keeps the short back`() {
        val venue = FakeVenue(100.0).apply { rejectAfterAccept = "RMS: margin exceeds" }
        val run = StrategyHost().start(spread, SymbolResolver.resolve(spread, contract, 23590.0, monday), monday, 4, RunMode.SANDBOX, "manual", venue, HashMap())
        assertTrue(venue.placed.isEmpty(), "the SELL must not be sent: ${venue.placed}")
        assertTrue(run.openLegs().isEmpty())
    }

    @Test fun `a stop buys back the short before it sells the wing, and retries a refused exit`() {
        val host = StrategyHost()
        val venue = FakeVenue(100.0)
        val ids = HashMap<Long, String>()
        var run = host.start(spread, SymbolResolver.resolve(spread, contract, 23590.0, monday), monday, 5, RunMode.SANDBOX, "manual", venue, ids)
        assertEquals(listOf(Side.BUY, Side.SELL), venue.placed.map { it.side })
        assertEquals(2, run.openLegs().size)

        // The short's buy-back is refused (e.g. session lost): the wing must stay on.
        venue.refuseNext = "not logged in"
        run = host.stop(run, spread, monday.plusMinutes(1), "manual", venue, ids)
        assertEquals(2, venue.placed.size, "nothing sold while the short is open: ${venue.placed}")
        assertEquals(2, run.openLegs().size)

        // Next tick: the pending stop is retried - short bought back first, then the wing sold.
        run = host.tick(run, spread, emptyList(), monday.plusMinutes(2), 0.0, venue, ids)
        val exits = venue.placed.drop(2)
        assertEquals(listOf(Side.BUY, Side.SELL), exits.map { it.side })
        assertTrue(exits[0].symbol.endsWith("PE") && run.openLegs().isEmpty(), "${run.legs.values.map { it.symbol + ":" + it.status }}")
        assertEquals(RunStatus.CLOSED, run.status)
    }
}
