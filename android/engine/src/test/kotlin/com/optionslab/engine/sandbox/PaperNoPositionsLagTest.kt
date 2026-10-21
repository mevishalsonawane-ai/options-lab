package com.optionslab.engine.sandbox

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 21 Oct, enhancement round 5 (idea 2): measured - the paper book has no positions lag. At Zerodha an exit can show COMPLETE in
 * the orders a moment before the positions move (round 3's positions-lag guard). In paper, the order's status, its trade and the
 * position change in ONE step and come back as one state: every state a reader can see has each position equal to the signed sum
 * of its trades, and every complete order has its trade. So the guard has nothing to do on paper; nothing was built for it.
 */
class PaperNoPositionsLagTest {
    private val ist = SandboxRules.IST
    private fun at(t: String) = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse("2026-09-21"), LocalTime.parse(t)), ist)
    private val ce = "NIFTY29SEP2625000CE"
    private val sb = Sandbox(SandboxConfig(), InstrumentMaster.of(listOf(Instrument(ce, "NFO", "OPTIDX", lotSize = 75))))
    private fun req(action: String, qty: Int, type: String = "MARKET", product: String = "NRML", trigger: Double? = null, price: Double? = null) =
        OrderRequest(ce, "NFO", action, qty, type, product, price, trigger)

    /** Each position's quantity is the signed sum of its trades, and every complete order has its filled quantity in trades. */
    private fun coherent(s: SandboxState) {
        for (p in s.positions) {
            val traded = s.trades.filter { it.symbol == p.symbol && it.product == p.product }.sumOf { if (it.action == "BUY") it.quantity else -it.quantity }
            assertEquals(traded, p.quantity, "position ${p.symbol} ${p.product} against its trades")
        }
        for (o in s.orders.filter { it.status == OrderStatus.COMPLETE }) {
            assertEquals(o.filledQuantity, s.trades.filter { it.orderId == o.orderId }.sumOf { it.quantity }, "order ${o.orderId} against its trades")
        }
    }

    private fun held(s: SandboxState, product: String = "NRML") = s.positions.filter { it.symbol == ce && it.product == product }.sumOf { it.quantity }

    @Test fun aMarketExitsFillAndThePositionComeBackTogether() {
        var s = sb.newState(at("09:00"))
        s = sb.place(s, req("BUY", 150), Quote(100.0), at("09:30")).state
        coherent(s)
        assertEquals(150, held(s))
        val out = sb.place(s, req("SELL", 75), Quote(110.0), at("09:31"))
        val o = out.state.orders.single { it.orderId == out.result.orderId }
        assertEquals(OrderStatus.COMPLETE, o.status)
        assertEquals(75, held(out.state), "the same state that shows the exit complete shows the position down")
        coherent(out.state)
    }

    @Test fun aRestingStopThatTriggersOnAQuoteMovesThePositionInTheSameState() {
        var s = sb.newState(at("09:00"))
        s = sb.place(s, req("BUY", 75), Quote(100.0), at("09:30")).state
        val stop = sb.place(s, req("SELL", 75, "SL-M", trigger = 90.0), Quote(100.0), at("09:31"))
        s = stop.state
        assertEquals(OrderStatus.TRIGGER_PENDING, s.orders.single { it.orderId == stop.result.orderId }.status)
        assertEquals(75, held(s))
        coherent(s)
        s = sb.onQuotes(s, mapOf("NFO:$ce" to Quote(88.0)), at("09:40")).state
        assertEquals(OrderStatus.COMPLETE, s.orders.single { it.orderId == stop.result.orderId }.status)
        assertEquals(0, held(s), "the stop's fill and the closed position in one state")
        coherent(s)
    }

    @Test fun everyStepOfABusyDayIsCoherentAcrossProducts() {
        var s = sb.newState(at("09:00"))
        val steps = listOf<(SandboxState) -> SandboxState>(
            { sb.place(it, req("BUY", 150), Quote(100.0), at("09:30")).state },
            { sb.place(it, req("BUY", 75, product = "MIS"), Quote(101.0), at("09:31")).state },
            { sb.place(it, req("SELL", 75, "LIMIT", price = 120.0), Quote(101.0), at("09:32")).state },
            { sb.place(it, req("SELL", 75, "SL-M", product = "MIS", trigger = 95.0), Quote(101.0), at("09:33")).state },
            { sb.onQuotes(it, mapOf("NFO:$ce" to Quote(121.0)), at("09:40")).state },
            { sb.onQuotes(it, mapOf("NFO:$ce" to Quote(94.0)), at("09:50")).state },
            { sb.place(it, req("SELL", 75), Quote(94.0), at("09:51")).state },
        )
        for (f in steps) { s = f(s); coherent(s) }
        assertEquals(0, held(s))
        assertEquals(0, held(s, "MIS"))
    }
}
