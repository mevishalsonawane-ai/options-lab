package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Branch coverage of the paper-trading state machine: validation, matching, margin, settlement and the books. */
class SandboxCoverageTest {
    private val ist = SandboxRules.IST
    private fun at(d: String, t: String) = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(d), LocalTime.parse(t)), ist)
    private val mon = "2026-09-21"
    private val t10 = at(mon, "10:00")
    private val ce = "NIFTY29SEP2625000CE"
    private val fut = "NIFTY29SEP26FUT"
    private val master = InstrumentMaster.of(listOf(
        Instrument("RELIANCE", "NSE", "EQ"), Instrument("TCS", "NSE", "EQ"),
        Instrument(fut, "NFO", "FUTIDX", lotSize = 75),
        Instrument(ce, "NFO", "OPTIDX", lotSize = 75),
        Instrument("ODDLOT", "NFO", "FUT", lotSize = 0),
        Instrument("CRUDE", "NCO", "FUT", lotSize = 100, expiry = LocalDate.parse("2026-09-20")),
        Instrument("BTCUSDT", "CRYPTO", "FUT", lotSize = 1, contractValue = 0.01),
        Instrument("ZEROCV", "NSE", "EQ", contractValue = 0.0),
    ))
    private val sb = Sandbox(SandboxConfig(), master)
    private fun fresh(t: ZonedDateTime = at(mon, "09:00")) = sb.newState(t)
    private fun req(sym: String, action: String, qty: Int, type: String = "MARKET", product: String = "MIS", exch: String = "NSE",
                    price: Double? = null, trigger: Double? = null) = OrderRequest(sym, exch, action, qty, type, product, price, trigger)
    private fun q(ltp: Double, bid: Double = 0.0, ask: Double = 0.0, high: Double = 0.0, low: Double = 0.0) = Quote(ltp, bid, ask, high, low)
    private fun d(s: String) = BigDecimal(s)
    private fun eq(expected: String, actual: BigDecimal?) = assertEquals(0, d(expected).compareTo(actual!!), "$expected vs $actual")

    // ------------------------------------------------------------ validation

    @Test fun `every validation refusal`() {
        val s = fresh()
        fun msg(r: OrderRequest) = sb.place(s, r, q(100.0), t10).result.message
        assertEquals("Missing required field: symbol", msg(OrderRequest(null, "NSE", "BUY", 1)))
        assertEquals("Missing required field: exchange", msg(OrderRequest("X", "", "BUY", 1)))
        assertEquals("Missing required field: action", msg(OrderRequest("X", "NSE", null, 1)))
        assertEquals("Missing required field: quantity", msg(OrderRequest("X", "NSE", "BUY", 0)))
        assertEquals("Missing required field: quantity", msg(OrderRequest("X", "NSE", "BUY", null)))
        assertEquals("Missing required field: price_type", msg(OrderRequest("X", "NSE", "BUY", 1, null)))
        assertEquals("Missing required field: product", msg(OrderRequest("X", "NSE", "BUY", 1, "MARKET", "")))
        assertEquals("Invalid action. Must be BUY or SELL", msg(req("X", "HOLD", 1)))
        assertEquals("Invalid price_type. Must be MARKET, LIMIT, SL, or SL-M", msg(req("X", "BUY", 1, type = "STOP")))
        assertEquals("Invalid product. Must be CNC, NRML, or MIS", msg(req("X", "BUY", 1, product = "CO")))
        assertEquals("NRML product not allowed for BSE equity segment. Use CNC for delivery or MIS for intraday.", msg(req("X", "BUY", 1, product = "nrml", exch = "bse")))
        assertEquals("CNC product not allowed for MCX derivatives segment. Use NRML for carryforward or MIS for intraday.", msg(req("X", "BUY", 1, product = "CNC", exch = "MCX")))
        assertEquals("Quantity must be positive", msg(req("X", "BUY", -5)))
        assertEquals("LIMIT orders require price", msg(req("X", "BUY", 1, type = "LIMIT")))
        assertEquals("limit orders require price", msg(req("X", "BUY", 1, type = "limit", price = 0.0)))
        assertEquals("Price must be positive", msg(req("X", "BUY", 1, type = "SL", price = -1.0, trigger = 1.0)))
        assertEquals("SL-M orders require trigger_price", msg(req("X", "BUY", 1, type = "SL-M")))
        assertEquals("SL orders require trigger_price", msg(req("X", "BUY", 1, type = "SL", price = 5.0, trigger = 0.0)))
        assertEquals("Trigger price must be positive", msg(req("X", "BUY", 1, type = "SL-M", trigger = -2.0)))
        assertTrue(msg(req("X", "BUY", 1, product = "CNC", exch = "LSE"))!!.startsWith("Invalid exchange. Must be one of NSE, NFO, CDS"))
        assertEquals("Symbol NOPE not found on NSE", msg(req("NOPE", "BUY", 1)))
        assertEquals("Quantity must be in multiples of lot size 75", msg(req(fut, "BUY", 50, product = "NRML", exch = "NFO")))
        assertEquals(400, sb.place(s, req("NOPE", "BUY", 1), null, t10).result.httpStatus)
    }

    @Test fun `a zero lot size is one, and NCO skips the lot check`() {
        val s = fresh()
        assertTrue(sb.place(s, req("ODDLOT", "BUY", 3, product = "NRML", exch = "NFO"), q(10.0), t10).result.ok)
        assertTrue(sb.place(s, req("CRUDE", "BUY", 7, product = "NRML", exch = "NCO"), q(10.0), t10).result.ok)
    }

    // ------------------------------------------------------------ MIS hours

    @Test fun `MIS after square-off or before the open may only reduce`() {
        val s0 = fresh()
        val long = sb.place(s0, req("RELIANCE", "BUY", 10), q(100.0), t10).state
        val late = at(mon, "15:20")
        assertEquals("MIS orders cannot be placed after square-off time (15:15 IST). Trading resumes at 09:00 AM IST.",
            sb.place(long, req("RELIANCE", "BUY", 5), q(100.0), late).result.message)
        assertFalse(sb.place(long, req("TCS", "SELL", 5), q(100.0), late).result.ok, "nothing open to reduce")
        assertTrue(sb.place(long, req("RELIANCE", "SELL", 5), q(100.0), late).result.ok, "reducing a long")
        val early = at(mon, "08:59")
        assertFalse(sb.place(s0, req("RELIANCE", "BUY", 5), q(100.0), early).result.ok)
        val short = sb.place(s0, req("RELIANCE", "SELL", 10), q(100.0), t10).state
        assertTrue(sb.place(short, req("RELIANCE", "BUY", 10), q(100.0), late).result.ok, "reducing a short")
        assertFalse(sb.place(short, req("RELIANCE", "SELL", 1), q(100.0), late).result.ok, "adding to a short")
        // No square-off time for the venue: MIS is never refused for the hour.
        assertTrue(sb.place(s0, req("BTCUSDT", "BUY", 1, exch = "CRYPTO"), q(100.0), at(mon, "23:59")).result.ok)
    }

    // ------------------------------------------------------------ CNC sells

    @Test fun `CNC sells need shares from the day position or holdings`() {
        val s0 = fresh()
        val none = sb.place(s0, req("RELIANCE", "SELL", 5, product = "CNC"), q(100.0), t10)
        assertFalse(none.result.ok); assertNotNull(none.result.orderId)
        assertEquals(OrderStatus.REJECTED, none.state.orders.single().status)
        assertTrue(none.result.message!!.startsWith("Cannot sell RELIANCE in CNC. No positions or holdings available."))
        eq("0.00", none.state.funds.usedMargin)
        val held = s0.copy(holdings = listOf(Holding("RELIANCE", "NSE", 3, d("90.00"), d("90.00"), d("0.00"), d("0.0000"), LocalDate.parse(mon), t10.toLocalDateTime(), t10.toLocalDateTime()),
            Holding("TCS", "NSE", -2, d("1.00"), null, d("0.00"), d("0.0000"), LocalDate.parse(mon), t10.toLocalDateTime(), t10.toLocalDateTime())))
        val bought = sb.place(held, req("RELIANCE", "BUY", 4, product = "CNC"), q(100.0), t10).state
        assertEquals("Cannot sell 9 shares of RELIANCE in CNC. Only 7 shares available (Position: 4, Holdings: 3)",
            sb.place(bought, req("RELIANCE", "SELL", 9, product = "CNC"), q(100.0), t10).result.message)
        val sold = sb.place(bought, req("RELIANCE", "SELL", 7, product = "CNC"), q(100.0), t10)
        assertTrue(sold.result.ok)
        assertFalse(sb.place(held, req("TCS", "SELL", 1, product = "CNC"), q(100.0), t10).result.ok, "a negative holding is not shares")
    }

    // ------------------------------------------------------------ pricing at placement

    @Test fun `a MARKET order without a quote uses the position's last price, else is refused`() {
        val s0 = fresh()
        assertTrue(sb.place(s0, req("RELIANCE", "BUY", 1), null, t10).result.message!!.startsWith("Cannot place MARKET order for RELIANCE"))
        assertFalse(sb.place(s0, req("RELIANCE", "BUY", 1), q(Double.NaN), t10).result.ok)
        assertFalse(sb.place(s0, req("RELIANCE", "BUY", 1), q(-3.0), t10).result.ok)
        val held = sb.place(s0, req("RELIANCE", "BUY", 10), q(100.0), t10).state
        val waiting = sb.place(held, req("RELIANCE", "BUY", 10), null, t10)
        assertTrue(waiting.result.ok)
        val o = waiting.state.orders.last()
        assertEquals(OrderStatus.OPEN, o.status); eq("100.00", o.price)
        // It fills on the next quote at the ask.
        val filled = sb.onQuotes(waiting.state, mapOf(Sandbox.key("RELIANCE", "NSE") to q(101.0, bid = 100.9, ask = 101.1)), t10)
        assertEquals(OrderStatus.COMPLETE, filled.state.orders.last().status); eq("101.10", filled.state.orders.last().averagePrice)
    }

    @Test fun `a stale quote at placement leaves a MARKET order open`() {
        val r = sb.place(fresh(), req("RELIANCE", "BUY", 1), q(50.0, high = 120.0, low = 100.0), t10)
        assertEquals(OrderStatus.OPEN, r.state.orders.single().status)
        assertTrue(r.state.trades.isEmpty())
    }

    @Test fun `limits and stops fill at placement only when marketable`() {
        val s0 = fresh()
        // Marketable LIMIT buy fills at the LTP (price improvement).
        val lb = sb.place(s0, req("RELIANCE", "BUY", 1, type = "LIMIT", price = 105.0), q(100.0), t10).state
        eq("100.00", lb.orders.single().averagePrice)
        // Non-marketable LIMIT sell rests.
        val ls = sb.place(s0, req("RELIANCE", "SELL", 1, type = "LIMIT", price = 105.0), q(100.0), t10).state
        assertEquals(OrderStatus.OPEN, ls.orders.single().status)
        // Marketable LIMIT sell with no quote rests too.
        assertEquals(OrderStatus.OPEN, sb.place(s0, req("RELIANCE", "SELL", 1, type = "LIMIT", price = 95.0), null, t10).state.orders.single().status)
        // SL buy triggered and inside its limit fills; triggered outside its limit rests OPEN; untouched rests TRIGGER PENDING.
        eq("100.00", sb.place(s0, req("RELIANCE", "BUY", 1, type = "SL", price = 101.0, trigger = 99.0), q(100.0), t10).state.orders.single().averagePrice)
        assertEquals(OrderStatus.OPEN, sb.place(s0, req("RELIANCE", "BUY", 1, type = "SL", price = 99.5, trigger = 99.0), q(100.0), t10).state.orders.single().status)
        assertEquals(OrderStatus.TRIGGER_PENDING, sb.place(s0, req("RELIANCE", "BUY", 1, type = "SL", price = 111.0, trigger = 110.0), q(100.0), t10).state.orders.single().status)
        // SL sell and SL-M sell.
        eq("100.00", sb.place(s0, req("RELIANCE", "SELL", 1, type = "SL", price = 99.0, trigger = 101.0), q(100.0), t10).state.orders.single().averagePrice)
        assertEquals(OrderStatus.OPEN, sb.place(s0, req("RELIANCE", "SELL", 1, type = "SL", price = 100.5, trigger = 101.0), q(100.0), t10).state.orders.single().status)
        eq("100.00", sb.place(s0, req("RELIANCE", "SELL", 1, type = "SL-M", trigger = 101.0), q(100.0), t10).state.orders.single().averagePrice)
        assertEquals(OrderStatus.TRIGGER_PENDING, sb.place(s0, req("RELIANCE", "SELL", 1, type = "SL-M", trigger = 90.0), q(100.0), t10).state.orders.single().status)
        // Triggered SL-M on a stale quote rests OPEN (trigger met), and a later tick fills it at the LTP.
        val staleSlm = sb.place(s0, req("RELIANCE", "BUY", 1, type = "SL-M", trigger = 99.0), q(100.0, high = 98.0, low = 90.0), t10).state
        assertEquals(OrderStatus.OPEN, staleSlm.orders.single().status)
        assertNull(staleSlm.orders.single().price, "SL-M never stores a price")
        val k = Sandbox.key("RELIANCE", "NSE")
        assertEquals(OrderStatus.OPEN, sb.onQuotes(staleSlm, mapOf(k to q(98.0)), t10).state.orders.single().status)
        eq("99.50", sb.onQuotes(staleSlm, mapOf(k to q(99.5)), t10).state.orders.single().averagePrice)
    }

    @Test fun `an insufficient balance refuses the order before it is recorded`() {
        val small = Sandbox(SandboxConfig(startingCapital = d("1000")), master)
        val r = small.place(small.newState(t10), req("RELIANCE", "BUY", 100, product = "CNC"), q(100.0), t10)
        assertEquals("Insufficient funds. Required: ₹10000.0, Available: ₹1000.00, Shortage: ₹9000.00", r.result.message)
        assertTrue(r.state.orders.isEmpty())
    }

    // ------------------------------------------------------------ matching engine

    @Test fun `the engine matches open and trigger-pending orders against their own quote`() {
        val s0 = fresh()
        val k = Sandbox.key("RELIANCE", "NSE")
        var s = sb.place(s0, req("RELIANCE", "BUY", 1, type = "LIMIT", price = 95.0), q(100.0), t10).state
        s = sb.place(s, req("TCS", "SELL", 1, type = "LIMIT", price = 105.0), q(100.0), t10).state
        s = sb.place(s, req("RELIANCE", "SELL", 1, type = "SL", price = 89.0, trigger = 90.0), q(100.0), t10).state
        s = sb.place(s, req("RELIANCE", "BUY", 1, type = "SL-M", trigger = 110.0), q(100.0), t10).state
        // No quote for TCS: untouched. A zero LTP and a stale quote are skipped.
        assertEquals(s.orders, sb.onQuotes(s, mapOf(k to q(0.0)), t10).state.orders)
        assertEquals(s.orders, sb.onQuotes(s, mapOf(k to q(50.0, high = 120.0, low = 100.0)), t10).state.orders)
        // 94: the LIMIT buy fills at its limit; the SL sell is not yet triggered.
        val a = sb.onQuotes(s, mapOf(k to q(94.0)), t10).state
        eq("95.00", a.orders[0].averagePrice)
        assertEquals(OrderStatus.TRIGGER_PENDING, a.orders[2].status)
        // 89.5: the SL sell triggers and fills inside its limit.
        eq("89.50", sb.onQuotes(a, mapOf(k to q(89.5)), t10).state.orders[2].averagePrice)
        // 88: triggered but through its limit -> moves to the open book.
        val b = sb.onQuotes(a, mapOf(k to q(88.0)), t10)
        assertEquals(OrderStatus.OPEN, b.state.orders[2].status)
        assertTrue(b.events.any { it is SandboxEvent.OrderUpdate && it.status == OrderStatus.OPEN })
        // Open SL sell fills once back inside [limit, trigger].
        eq("89.00", sb.onQuotes(b.state, mapOf(k to q(89.0)), t10).state.orders[2].averagePrice)
        assertEquals(OrderStatus.OPEN, sb.onQuotes(b.state, mapOf(k to q(88.0)), t10).state.orders[2].status)
        // SL-M buy triggers at 110 and fills at the LTP.
        eq("111.00", sb.onQuotes(a, mapOf(k to q(111.0)), t10).state.orders[3].averagePrice)
        // TCS LIMIT sell.
        val tk = Sandbox.key("TCS", "NSE")
        assertEquals(OrderStatus.OPEN, sb.onQuotes(a, mapOf(tk to q(104.0)), t10).state.orders[1].status)
        eq("105.00", sb.onQuotes(a, mapOf(tk to q(106.0)), t10).state.orders[1].averagePrice)
    }

    @Test fun `an open SL buy fills inside its band and a market sell uses the bid`() {
        val k = Sandbox.key("RELIANCE", "NSE")
        val s = sb.place(fresh(), req("RELIANCE", "BUY", 1, type = "SL", price = 101.0, trigger = 100.0), q(102.0), t10).state
        assertEquals(OrderStatus.OPEN, s.orders.single().status)
        assertEquals(OrderStatus.OPEN, sb.onQuotes(s, mapOf(k to q(99.0)), t10).state.orders.single().status)
        eq("100.50", sb.onQuotes(s, mapOf(k to q(100.5)), t10).state.orders.single().averagePrice)
        val mkt = sb.place(fresh(), req("RELIANCE", "SELL", 1), q(100.0, bid = 99.9, ask = 100.1), t10).state
        eq("99.90", mkt.orders.single().averagePrice)
        val noBid = sb.place(fresh(), req("RELIANCE", "SELL", 1), q(100.0), t10).state
        eq("100.00", noBid.orders.single().averagePrice)
    }

    @Test fun `an order that already has a trade is completed from it, never filled twice`() {
        val filled = sb.place(fresh(), req("RELIANCE", "BUY", 1), q(100.0), t10).state
        val o = filled.orders.single()
        val reopened = filled.copy(orders = listOf(o.copy(status = OrderStatus.OPEN, averagePrice = null, filledQuantity = 0)))
        val r = sb.onQuotes(reopened, mapOf(Sandbox.key("RELIANCE", "NSE") to q(200.0)), t10)
        assertEquals(1, r.state.trades.size)
        eq("100.00", r.state.orders.single().averagePrice)
        assertTrue(r.events.single() is SandboxEvent.Fill)
        val pending = filled.copy(orders = listOf(o.copy(status = OrderStatus.TRIGGER_PENDING)))
        assertEquals(pending.orders, sb.onQuotes(pending, mapOf(Sandbox.key("RELIANCE", "NSE") to q(200.0)), t10).state.orders)
    }

    // ------------------------------------------------------------ costs on fill

    @Test fun `charges and slippage are applied when configured`() {
        val cfg = SandboxConfig(chargesEnabled = true, stopSlippageBps = d("50"), spreadFallbackBps = d("20"))
        val c = Sandbox(cfg, master)
        val s0 = c.newState(t10)
        val r = c.place(s0, req(fut, "BUY", 75, product = "NRML", exch = "NFO"), q(100.0), t10).state
        val t = r.trades.single()
        eq("100.20", t.price)      // no book: 20 bps adverse
        eq(SandboxCosts.charge("BUY", d("100.20"), 75).toPlainString(), t.charges)
        eq(SandboxCosts.charge("BUY", d("100.20"), 75).negate().toPlainString(), r.funds.realizedPnl)
        val book = c.place(s0, req(fut, "SELL", 75, product = "NRML", exch = "NFO"), q(100.0, bid = 99.95, ask = 100.05), t10).state
        eq("99.95", book.trades.single().price)
        val sl = c.place(s0, req(fut, "SELL", 75, "SL", "NRML", "NFO", price = 99.8, trigger = 100.5), q(100.0), t10).state
        eq("99.80", sl.trades.single().price, )  // 100 - 0.5 = 99.50 clamped to the 99.80 limit
        // Crypto contract value scales the charge.
        val btc = c.place(s0, req("BTCUSDT", "BUY", 1, exch = "CRYPTO"), q(60000.0, ask = 60000.0), t10).state
        eq(SandboxCosts.charge("BUY", d("60000.0"), 1, d("0.01")).toPlainString(), btc.trades.single().charges)
    }

    // ------------------------------------------------------------ positions

    @Test fun `positions open, add, reduce, reverse and close with margin and P&L`() {
        val k = Sandbox.key(fut, "NFO")
        fun mkt(s: SandboxState, action: String, qty: Int, px: Double) =
            sb.place(s, req(fut, action, qty, product = "NRML", exch = "NFO"), q(px, bid = px, ask = px), t10).state
        var s = mkt(fresh(), "BUY", 75, 100.0)
        eq("750.00", s.positions.single().marginBlocked)        // 75 x 100 / 10
        s = mkt(s, "BUY", 75, 110.0)
        val p1 = s.positions.single()
        assertEquals(150, p1.quantity); eq("105.00", p1.averagePrice); eq("1575.00", p1.marginBlocked)
        s = mkt(s, "SELL", 75, 120.0)
        val p2 = s.positions.single()
        assertEquals(75, p2.quantity); eq("1125.00", p2.todayRealizedPnl); eq("787.50", p2.marginBlocked)
        eq("1125.00", s.funds.realizedPnl)
        // Reverse: sell 150 -> short 75 at 130.
        s = mkt(s, "SELL", 150, 130.0)
        val p3 = s.positions.single()
        assertEquals(-75, p3.quantity); eq("130.00", p3.averagePrice); eq("3000.00", p3.todayRealizedPnl)
        // Close the short: flat, the rest of the P&L booked.
        s = mkt(s, "BUY", 75, 120.0)
        val p4 = s.positions.single()
        assertEquals(0, p4.quantity); eq("3750.00", p4.accumulatedRealizedPnl); eq("0.00", p4.marginBlocked)
        eq("0.00", s.funds.usedMargin)
        // Re-open from flat.
        s = mkt(s, "SELL", 75, 100.0)
        assertEquals(-75, s.positions.single().quantity); eq("100.00", s.positions.single().averagePrice)
        assertTrue(sb.onQuotes(s, mapOf(k to q(1.0)), t10).state.orders.all { it.status == OrderStatus.COMPLETE })
    }

    @Test fun `closing a position with no margin books no P&L to the funds (Python parity)`() {
        val base = fresh()
        val pos = Position(fut, "NFO", "NRML", 75, d("100.00"), d("100.00"), d("0.00"), d("0.0000"), d("0.00"), d("0.00"), d("0.00"),
            t10.toLocalDateTime(), t10.toLocalDateTime())
        val s = sb.place(base.copy(positions = listOf(pos)), req(fut, "SELL", 75, product = "NRML", exch = "NFO"), q(110.0), t10).state
        eq("750.00", s.positions.single().accumulatedRealizedPnl)
        eq("0.00", s.funds.realizedPnl)
    }

    @Test fun `close position places the opposite market order or explains why not`() {
        val none = sb.closePosition(fresh(), fut, "NFO", "NRML", q(100.0), t10)
        assertEquals(404, none.result.httpStatus)
        assertEquals("No open position found for $fut", none.result.message)
        val s = sb.place(fresh(), req(fut, "SELL", 75, product = "NRML", exch = "NFO"), q(100.0), t10).state
        val closed = sb.closePosition(s, fut, "NFO", "NRML", q(90.0), t10)
        assertEquals("Position close order placed for $fut", closed.result.message)
        assertEquals("AUTO_SQUARE_OFF", closed.state.orders.last().strategy)
        assertEquals("BUY", closed.state.orders.last().action)
        // A flat row: the close order has no quantity, and its refusal is passed through.
        val flat = sb.closePosition(closed.state, fut, "NFO", "NRML", q(90.0), t10)
        assertEquals("Missing required field: quantity", flat.result.message)
    }

    // ------------------------------------------------------------ modify / cancel

    @Test fun `modify refuses what the order type does not use`() {
        val s0 = fresh()
        val r = sb.place(s0, req(fut, "BUY", 75, "LIMIT", "NRML", "NFO", price = 90.0), q(100.0), t10)
        val id = r.result.orderId!!
        assertEquals(404, sb.modify(r.state, "nope", OrderChange(), t10).result.httpStatus)
        assertEquals("Quantity must be in multiples of lot size 75", sb.modify(r.state, id, OrderChange(quantity = 10), t10).result.message)
        assertEquals("LIMIT orders do not accept a trigger_price", sb.modify(r.state, id, OrderChange(triggerPrice = 80.0), t10).result.message)
        val ok = sb.modify(r.state, id, OrderChange(quantity = 150, price = 91.0, triggerPrice = 0.0), t10)
        assertEquals("Order modified successfully", ok.result.message)
        assertEquals(150, ok.state.orders.single().quantity); eq("91.00", ok.state.orders.single().price)
        val slm = sb.place(s0, req(fut, "BUY", 75, "SL-M", "NRML", "NFO", trigger = 120.0), q(100.0), t10)
        assertEquals("SL-M orders do not accept a price", sb.modify(slm.state, slm.result.orderId!!, OrderChange(price = 121.0), t10).result.message)
        eq("125.00", sb.modify(slm.state, slm.result.orderId!!, OrderChange(triggerPrice = 125.0, price = 0.0), t10).state.orders.single().triggerPrice)
        // Equity quantities are free; an odd-lot NFO instrument reads lot 1.
        val eqo = sb.place(s0, req("RELIANCE", "BUY", 1, "LIMIT", price = 50.0), q(100.0), t10)
        assertEquals(7, sb.modify(eqo.state, eqo.result.orderId!!, OrderChange(quantity = 7), t10).state.orders.single().quantity)
        val odd = sb.place(s0, req("ODDLOT", "BUY", 1, "LIMIT", "NRML", "NFO", price = 50.0), q(100.0), t10)
        assertEquals(3, sb.modify(odd.state, odd.result.orderId!!, OrderChange(quantity = 3), t10).state.orders.single().quantity)
        // Unknown instrument now: the lot is not checked.
        val gone = Sandbox(SandboxConfig(), InstrumentMaster { _, _ -> null })
        assertEquals(11, gone.modify(r.state, id, OrderChange(quantity = 11), t10).state.orders.single().quantity)
        // Completed orders cannot change.
        val done = sb.place(s0, req("RELIANCE", "BUY", 1), q(100.0), t10)
        assertEquals("Cannot modify order in complete status", sb.modify(done.state, done.result.orderId!!, OrderChange(quantity = 2), t10).result.message)
        assertEquals("Cannot cancel order in complete status", sb.cancel(done.state, done.result.orderId!!, t10).result.message)
        assertEquals(404, sb.cancel(done.state, "nope", t10).result.httpStatus)
    }

    @Test fun `cancel releases blocked margin, or recomputes it for a reducing order (Python parity)`() {
        val s0 = fresh()
        val lim = sb.place(s0, req(fut, "BUY", 75, "LIMIT", "NRML", "NFO", price = 90.0), q(100.0), t10)
        eq("675.00", lim.state.funds.usedMargin)
        val c = sb.cancel(lim.state, lim.result.orderId!!, t10)
        eq("0.00", c.state.funds.usedMargin); assertEquals(OrderStatus.CANCELLED, c.state.orders.single().status)
        assertEquals("Order cancelled successfully", c.result.message)
        // A reducing LIMIT blocks nothing; cancelling it releases a recomputed margin from its price.
        val held = sb.place(s0, req(fut, "BUY", 150, product = "NRML", exch = "NFO"), q(100.0), t10).state
        val red = sb.place(held, req(fut, "SELL", 75, "LIMIT", "NRML", "NFO", price = 120.0), q(100.0), t10)
        eq("0.00", red.state.orders.last().marginBlocked)
        eq("1500.00", red.state.funds.usedMargin)
        eq("600.00", sb.cancel(red.state, red.result.orderId!!, t10).state.funds.usedMargin)
        // A reducing SL-M has no price: the quote prices it, else nothing is released.
        val slm = sb.place(held, req(fut, "SELL", 75, "SL-M", "NRML", "NFO", trigger = 80.0), q(100.0), t10)
        eq("600.00", sb.cancel(slm.state, slm.result.orderId!!, t10, q(120.0)).state.funds.usedMargin)
        eq("1500.00", sb.cancel(slm.state, slm.result.orderId!!, t10, q(Double.NaN)).state.funds.usedMargin)
        eq("1500.00", sb.cancel(slm.state, slm.result.orderId!!, t10).state.funds.usedMargin)
        // A CNC sell of owned shares would never have blocked: nothing released.
        val cnc = sb.place(s0, req("RELIANCE", "BUY", 10, product = "CNC"), q(100.0), t10).state
        val sell = sb.place(cnc, req("RELIANCE", "SELL", 5, "LIMIT", "CNC", price = 200.0), q(100.0), t10)
        eq("1000.00", sb.cancel(sell.state, sell.result.orderId!!, t10).state.funds.usedMargin)
        // Instrument gone from the master: nothing to recompute.
        val gone = Sandbox(SandboxConfig(), InstrumentMaster { _, _ -> null })
        eq("1500.00", gone.cancel(red.state, red.result.orderId!!, t10).state.funds.usedMargin)
    }

    // ------------------------------------------------------------ square-off and expiry

    @Test fun `square-off cancels MIS orders, cancels expired-contract orders and closes MIS positions`() {
        val s0 = fresh()
        var s = sb.place(s0, req("RELIANCE", "BUY", 10), q(100.0), t10).state
        s = sb.place(s, req("RELIANCE", "BUY", 1, "LIMIT", price = 50.0), q(100.0), t10).state
        s = sb.place(s, req("BTCUSDT", "BUY", 1, "LIMIT", exch = "CRYPTO", price = 50.0), q(100.0), t10).state
        s = sb.place(s, req("CRUDE", "BUY", 1, "LIMIT", "NRML", "NCO", price = 50.0), q(100.0), t10).state
        s = sb.place(s, req("BTCUSDT", "BUY", 1, exch = "CRYPTO"), q(100.0), t10).state
        // Before the cut only the expired contract's order goes: CRUDE's master expiry (NCO never parses the symbol) was yesterday.
        val early = sb.squareOffDue(s, at(mon, "15:00"), emptyMap())
        assertEquals(listOf(OrderStatus.COMPLETE, OrderStatus.OPEN, OrderStatus.OPEN, OrderStatus.CANCELLED, OrderStatus.COMPLETE),
            early.state.orders.map { it.status })
        val late = sb.squareOffDue(s, at(mon, "15:16"), mapOf(Sandbox.key("RELIANCE", "NSE") to q(105.0)))
        val st = late.state.orders.map { it.symbol to it.status }
        assertTrue(("RELIANCE" to OrderStatus.CANCELLED) in st)
        assertTrue(("BTCUSDT" to OrderStatus.OPEN) in st, "no square-off on CRYPTO")
        assertTrue(("CRUDE" to OrderStatus.CANCELLED) in st)
        val so = late.events.filterIsInstance<SandboxEvent.SquareOff>().single()
        assertEquals("RELIANCE", so.symbol); assertTrue(so.result.ok)
        assertEquals(0, late.state.positions.first { it.symbol == "RELIANCE" }.quantity)
        assertEquals(1, late.state.positions.first { it.symbol == "BTCUSDT" }.quantity)
    }

    @Test fun `expired contracts settle - options at LTP or zero, futures at LTP or average`() {
        val s0 = fresh()
        var s = sb.place(s0, req(ce, "BUY", 75, product = "NRML", exch = "NFO"), q(10.0), t10).state
        s = sb.place(s, req(fut, "SELL", 75, product = "NRML", exch = "NFO"), q(25000.0), t10).state
        // Mark: CE at 30, FUT at 25100.
        s = sb.positionBook(s, t10, mapOf(Sandbox.key(ce, "NFO") to q(30.0), Sandbox.key(fut, "NFO") to q(25100.0))).state
        val expiryClose = at("2026-09-29", "15:40")
        val before = sb.settleExpiries(s, at("2026-09-29", "15:39"))
        assertTrue(before.events.isEmpty())
        val r = sb.settleExpiries(s, expiryClose)
        val settled = r.events.filterIsInstance<SandboxEvent.ExpirySettled>().associateBy { it.symbol }
        eq("30.00", settled.getValue(ce).price); eq("1500.00", settled.getValue(ce).pnl)
        eq("25100.00", settled.getValue(fut).price); eq("-7500.00", settled.getValue(fut).pnl)
        assertTrue(r.state.positions.all { it.quantity == 0 })
        eq("0.00", r.state.funds.usedMargin)
        // "zero" settlement for options; next_day timing waits a day.
        val zero = Sandbox(SandboxConfig(optionExpirySettlement = "zero"), master)
        eq("0.00", zero.settleExpiries(s, expiryClose).events.filterIsInstance<SandboxEvent.ExpirySettled>().first { it.symbol == ce }.price)
        val nextDay = Sandbox(SandboxConfig(expirySettlementTiming = "next_day"), master)
        assertTrue(nextDay.settleExpiries(s, expiryClose).events.isEmpty())
        assertEquals(2, nextDay.settleExpiries(s, at("2026-09-30", "09:00")).events.size)
        // An option whose last price is zero settles at zero; a future with no LTP at its average.
        val unmarked = s.copy(positions = s.positions.map { it.copy(ltp = if (it.symbol == ce) d("0.00") else null) })
        val ev = sb.settleExpiries(unmarked, expiryClose).events.filterIsInstance<SandboxEvent.ExpirySettled>().associateBy { it.symbol }
        eq("0.00", ev.getValue(ce).price); eq("25000.00", ev.getValue(fut).price)
    }

    @Test fun `square-off time cancels orders on an expired contract`() {
        val s = sb.place(fresh(), req(ce, "BUY", 75, "LIMIT", "NRML", "NFO", price = 5.0), q(10.0), t10).state
        val r = sb.squareOffDue(s, at("2026-09-29", "15:45"), emptyMap())
        assertEquals(OrderStatus.CANCELLED, r.state.orders.single().status)
        eq("0.00", r.state.funds.usedMargin)
    }

    // ------------------------------------------------------------ T+1, catch-up, resets

    private fun pos(sym: String, qty: Int, avg: String, product: String, created: LocalDateTime, ltp: String? = avg,
                    margin: String = "0.00", today: String = "0.00", exch: String = "NSE", updated: LocalDateTime = created) =
        Position(sym, exch, product, qty, d(avg), ltp?.let(::d), d("0.00"), d("0.0000"), d(today), d(today), d(margin), created, updated)

    @Test fun `T+1 merges into holdings, credits sales and drops flat rows`() {
        val y = LocalDateTime.of(2026, 9, 20, 11, 0)
        val funds = fresh().funds.copy(usedMargin = d("2000.00"), availableBalance = d("9998000.00"))
        val h = Holding("RELIANCE", "NSE", 10, d("90.00"), d("95.00"), d("0.00"), d("0.0000"), LocalDate.parse("2026-09-19"), y, y)
        val h2 = Holding("TCS", "NSE", 5, d("100.00"), d("100.00"), d("0.00"), d("0.0000"), LocalDate.parse("2026-09-19"), y, y)
        val s = SandboxState(funds, positions = listOf(
            pos("RELIANCE", 10, "110.00", "CNC", y, margin = "1100.00"),
            pos("TCS", -5, "120.00", "CNC", y),
            pos("ZEROCV", 0, "1.00", "CNC", y),
            pos("NEW", 3, "10.00", "CNC", y, ltp = null),
            pos("TODAY", 3, "10.00", "CNC", t10.toLocalDateTime()),
        ), holdings = listOf(h, h2))
        val r = sb.settleT1(s, t10)
        assertEquals(3, r.result)
        assertEquals(SandboxEvent.T1Settled(3), r.events.single())
        val rel = r.state.holdings.first { it.symbol == "RELIANCE" }
        assertEquals(20, rel.quantity); eq("100.00", rel.averagePrice)
        assertNull(r.state.holdings.firstOrNull { it.symbol == "TCS" }, "sold out")
        val nw = r.state.holdings.first { it.symbol == "NEW" }
        eq("10.00", nw.ltp); assertEquals(LocalDate.parse(mon), nw.settlementDate)
        assertEquals(listOf("TODAY"), r.state.positions.map { it.symbol })
        // 2000 used: RELIANCE's 1100 cost and NEW's 30 move out of used margin; TCS's sale credits 600 to cash.
        eq("870.00", r.state.funds.usedMargin)
        eq("9998600.00", r.state.funds.availableBalance)
    }

    @Test fun `catch-up closes stale MIS at the last price, runs a missed T+1 and zeroes stale today P&L`() {
        val y = LocalDateTime.of(2026, 9, 20, 11, 0)
        val funds = fresh().funds.copy(usedMargin = d("300.00"), availableBalance = d("9999700.00"), todayRealizedPnl = d("50.00"), updatedAt = y)
        val s = SandboxState(funds, positions = listOf(
            pos("RELIANCE", 10, "100.00", "MIS", y, ltp = "110.00", margin = "200.00"),
            pos("TCS", -5, "100.00", "MIS", y, ltp = "0.00", margin = "500.00"),
            pos("CRUDE", 1, "10.00", "MIS", y, exch = "NCO"),
            pos("BTCUSDT", 2, "100.00", "MIS", y, ltp = "200.00", exch = "CRYPTO"),
            pos("ZEROCV", 1, "5.00", "CNC", LocalDateTime.of(2026, 9, 19, 10, 0), today = "7.00"),
        ))
        val r = sb.catchUp(s, t10)
        val rel = r.state.positions.firstOrNull { it.symbol == "RELIANCE" }
        assertEquals(0, rel!!.quantity); eq("100.00", rel.accumulatedRealizedPnl); eq("0.00", rel.todayRealizedPnl)
        assertEquals(0, r.state.positions.first { it.symbol == "TCS" }.quantity)
        assertEquals(1, r.state.positions.first { it.symbol == "CRUDE" }.quantity, "no square-off time for NCO")
        assertEquals(2, r.state.positions.first { it.symbol == "BTCUSDT" }.quantity)
        eq("100.00", r.state.funds.realizedPnl, )
        eq("0.00", r.state.funds.usedMargin, )           // 300 - 700 clamped at zero
        // Python parity: the MIS catch-up's own funds write moves updated_at past the boundary, so yesterday's
        // 50 of "today" realized P&L is no longer seen as stale and survives into today.
        eq("50.00", r.state.funds.todayRealizedPnl)
        assertEquals(1, r.state.holdings.size, "the CNC row moved in the missed T+1")
    }

    @Test fun `catch-up with nothing stale changes nothing`() {
        val s = sb.place(fresh(), req("RELIANCE", "BUY", 1), q(100.0), t10).state
        val r = sb.catchUp(s, t10)
        assertEquals(s.positions, r.state.positions); assertEquals(s.funds, r.state.funds)
        // Only positions stale: funds kept.
        val y = LocalDateTime.of(2026, 9, 20, 11, 0)
        val onlyPos = s.copy(positions = listOf(pos("ZEROCV", 0, "5.00", "NRML", y, today = "3.00")))
        val rp = sb.catchUp(onlyPos, t10)
        eq("0.00", rp.state.positions.single().todayRealizedPnl)
        assertEquals(s.funds.updatedAt, rp.state.funds.updatedAt)
    }

    @Test fun `weekly fund reset happens once on its day after its time`() {
        val sunday = Sandbox(SandboxConfig(resetDay = "Sunday", resetTime = "10:00"), master)
        val sun = at("2026-09-20", "10:30")
        val s = sunday.place(sunday.newState(at("2026-09-19", "09:00")), req("RELIANCE", "BUY", 10, product = "CNC"), q(100.0), at("2026-09-19", "10:00")).state
        val r = sunday.funds(s, sun)
        assertEquals(1, r.result.resetCount)
        assertTrue(r.state.positions.isEmpty())
        assertEquals(1, sunday.funds(r.state, sun.plusHours(1)).result.resetCount, "once per day")
        assertEquals(0, sunday.funds(s, at("2026-09-20", "09:59")).result.resetCount)
        assertEquals(0, sunday.funds(s, t10).result.resetCount, "Monday")
        val bad = Sandbox(SandboxConfig(resetDay = "Sunday", resetTime = "ten"), master)
        assertEquals(0, bad.funds(s, sun).result.resetCount)
        val manual = sb.resetFunds(s, t10)
        assertEquals(1, manual.state.funds.resetCount)
        assertEquals(SandboxEvent.FundsReset(1), manual.events.single())
    }

    @Test fun `margin reconciliation reports the correction or nothing`() {
        val s = sb.place(fresh(), req(fut, "BUY", 75, product = "NRML", exch = "NFO"), q(100.0), t10).state
        assertEquals(BigDecimal.ZERO, sb.reconcileMargin(s, t10).result)
        val off = s.copy(funds = s.funds.copy(usedMargin = d("1000.00"), availableBalance = s.funds.availableBalance - d("250.00")))
        val r = sb.reconcileMargin(off, t10)
        eq("250.00", r.result); eq("750.00", r.state.funds.usedMargin)
        assertEquals(SandboxEvent.MarginReconciled(d("250.00")), r.events.single())
    }

    // ------------------------------------------------------------ books

    @Test fun `position book marks longs and shorts, shows carried NRML and hides old rows`() {
        val y = LocalDateTime.of(2026, 9, 20, 11, 0)
        val now = t10.toLocalDateTime()
        val s = SandboxState(fresh().funds, positions = listOf(
            pos("RELIANCE", 10, "100.00", "MIS", now),
            pos("TCS", -10, "100.00", "MIS", now),
            pos(fut, 75, "200.00", "NRML", y, exch = "NFO"),
            pos("OLDMIS", 5, "1.00", "MIS", y),
            pos("FLAT", 0, "1.00", "MIS", now, today = "12.00"),
            pos("STALEFLAT", 0, "1.00", "MIS", y, today = "9.00"),
            pos("ZEROAVG", 3, "0.00", "MIS", now),
        ))
        val quotes = mapOf(Sandbox.key("RELIANCE", "NSE") to q(110.0), Sandbox.key("TCS", "NSE") to q(90.0),
            Sandbox.key(fut, "NFO") to q(-1.0), Sandbox.key("ZEROAVG", "NSE") to q(5.0))
        val b = sb.positionBook(s, t10, quotes)
        val rows = b.result.positions.associateBy { it.symbol }
        assertEquals(setOf("RELIANCE", "TCS", fut, "FLAT", "ZEROAVG"), rows.keys)
        assertEquals(100.0, rows.getValue("RELIANCE").pnl); assertEquals(10.0, rows.getValue("RELIANCE").pnlPercent)
        assertEquals(100.0, rows.getValue("TCS").pnl); assertEquals(10.0, rows.getValue("TCS").pnlPercent)
        assertEquals(0.0, rows.getValue(fut).pnl, "no usable quote: unmarked")
        assertEquals(12.0, rows.getValue("FLAT").pnl); assertEquals(0.0, rows.getValue("FLAT").averagePrice)
        assertEquals(0.0, rows.getValue("ZEROAVG").pnlPercent)
        assertEquals(215.0, b.result.totalUnrealizedPnl)
        eq("215.00", b.state.funds.unrealizedPnl)
        assertEquals(1.0, rows.getValue("RELIANCE").lotSize)
        eq("0.00", b.state.positions.first { it.symbol == "STALEFLAT" }.todayRealizedPnl)
    }

    @Test fun `holdings book marks at the quote and totals`() {
        val y = LocalDateTime.of(2026, 9, 20, 11, 0)
        val hs = listOf(
            Holding("RELIANCE", "NSE", 10, d("100.00"), d("100.00"), d("0.00"), d("0.0000"), LocalDate.parse("2026-09-20"), y, y),
            Holding("TCS", "NSE", 5, d("0.00"), null, d("0.00"), d("0.0000"), LocalDate.parse("2026-09-20"), y, y),
            Holding("ZEROCV", "NSE", 0, d("1.00"), d("1.00"), d("0.00"), d("0.0000"), LocalDate.parse("2026-09-20"), y, y),
            Holding("ITC", "NSE", 2, d("50.00"), d("0.00"), d("0.00"), d("0.0000"), LocalDate.parse("2026-09-20"), y, y),
        )
        val s = fresh().copy(holdings = hs)
        val b = sb.holdings(s, t10, mapOf(Sandbox.key("RELIANCE", "NSE") to q(120.0), Sandbox.key("TCS", "NSE") to q(10.0),
            Sandbox.key("ITC", "NSE") to q(0.0))).result
        assertEquals(listOf("RELIANCE", "TCS", "ITC"), b.holdings.map { it.symbol })
        assertEquals(200.0, b.holdings[0].pnl); assertEquals(20.0, b.holdings[0].pnlPercent); assertEquals(1200.0, b.holdings[0].currentValue)
        assertEquals(0.0, b.holdings[1].pnlPercent, "zero average: no percentage"); assertEquals(50.0, b.holdings[1].pnl)
        assertEquals(0.0, b.holdings[2].currentValue, "unpriced")
        assertEquals(1250.0, b.totalHoldingValue); assertEquals(1100.0, b.totalInvValue)
        assertEquals(250.0, b.totalProfitAndLoss)
        assertEquals(0.0, sb.holdings(fresh(), t10).result.totalProfitAndLoss)
    }

    @Test fun `order status, order book and trade book`() {
        var r = sb.place(fresh(), req("RELIANCE", "BUY", 1), q(100.0), t10)
        val id = r.result.orderId!!
        r = sb.place(r.state, req("RELIANCE", "SELL", 5, product = "CNC"), q(100.0), t10)
        val row = sb.orderStatus(r.state, id)!!
        assertEquals(100.0, row.averagePrice); assertEquals(0.0, row.triggerPrice); assertEquals("", row.rejectionReason); assertEquals("", row.strategy)
        assertNull(sb.orderStatus(r.state, "nope"))
        val rej = sb.orderStatus(r.state, r.result.orderId!!)!!
        assertTrue(rej.rejectionReason.startsWith("Cannot sell RELIANCE in CNC"))
        val tb = sb.tradeBook(r.state, t10)
        assertEquals(1, tb.size); assertEquals(100.0, tb[0].tradeValue); assertEquals("", tb[0].strategy)
        assertTrue(sb.tradeBook(r.state, at("2026-09-22", "10:00")).isEmpty(), "next session")
        val ob = sb.orderBook(r.state, t10)
        assertEquals(1, ob.statistics.totalRejectedOrders); assertEquals(1, ob.statistics.totalCompletedOrders)
    }

    // ------------------------------------------------------------ rules and costs

    @Test fun `rules - classification, staleness, times and expiry`() {
        assertTrue(SandboxRules.isFuture("BTCUSDT", "CRYPTO")); assertFalse(SandboxRules.isFuture("BTC26SEP60000CE", "CRYPTO"))
        assertFalse(SandboxRules.isFuture("BTC26SEP60000PE", "CRYPTO")); assertFalse(SandboxRules.isFuture("RELIANCE", "NSE"))
        assertTrue(SandboxRules.isFuture(fut, "NFO")); assertTrue(SandboxRules.isOption(ce, "NCO")); assertFalse(SandboxRules.isOption(ce, "NSE"))
        assertFalse(SandboxRules.quoteLooksStale(Quote(100.0, high = Double.NaN, low = 1.0)))
        assertFalse(SandboxRules.quoteLooksStale(Quote(100.0, high = 120.0, low = Double.POSITIVE_INFINITY)))
        assertFalse(SandboxRules.quoteLooksStale(Quote(100.0, high = 120.0, low = 0.0)))
        assertFalse(SandboxRules.quoteLooksStale(Quote(100.0, high = -1.0, low = 90.0)))
        assertNull(SandboxRules.parseHhMm("ab:10")); assertNull(SandboxRules.parseHhMm("10:cd")); assertNull(SandboxRules.parseHhMm("10:60"))
        assertEquals(LocalTime.of(9, 5), SandboxRules.parseHhMm(" 9 : 05 "))
        assertNull(SandboxRules.parseExpiryFromSymbol("NIFTY", "NFO")); assertNull(SandboxRules.parseExpiryFromSymbol("NIFTY29XYZ26FUT", "NFO"))
        assertNull(SandboxRules.parseExpiryFromSymbol("NIFTY31FEB26FUT", "NFO")); assertNull(SandboxRules.parseExpiryFromSymbol(fut, "NCO"))
        assertEquals(LocalDate.of(2026, 9, 29), SandboxRules.parseExpiryFromSymbol(fut, "NFO"))
        assertEquals(LocalDate.parse("2026-09-20"), SandboxRules.contractExpiry("CRUDE", "NCO", master.lookup("CRUDE", "NCO")))
        assertNull(SandboxRules.contractExpiry("CRUDE", "NCO", null))
        val c = SandboxConfig()
        val exp = LocalDate.of(2026, 9, 29)
        assertFalse(SandboxRules.isContractExpiredNow(null, "NFO", LocalDateTime.of(2030, 1, 1, 0, 0), c))
        assertTrue(SandboxRules.isContractExpiredNow(exp, "NFO", exp.plusDays(1).atStartOfDay(), c))
        assertFalse(SandboxRules.isContractExpiredNow(exp, "NFO", exp.minusDays(1).atTime(23, 59), c))
        assertFalse(SandboxRules.isContractExpiredNow(exp, "NSE", exp.atTime(15, 29), c))
        assertTrue(SandboxRules.isContractExpiredNow(exp, "NSE", exp.atTime(15, 30), c), "default close 15:30")
        assertFalse(SandboxRules.isContractExpiredNow(exp, "MCX", exp.atTime(23, 29), c))
        assertEquals(BigDecimal.ONE, SandboxRules.leverage(c, "INDEX", "NSE_INDEX", "MIS", "BUY"))
        assertEquals(d("1"), SandboxRules.leverage(c, ce, "NFO", "NRML", "BUY"))
        assertEquals("2026-09-21 10:00:00", SandboxRules.ts(t10.toLocalDateTime()))
        eq("1.0", SandboxRules.pyDec(1.0)); assertEquals("0.1", SandboxRules.pyDec(0.1).toString()); assertEquals(0.12, SandboxRules.round2(0.125))
        assertEquals(LocalTime.of(15, 15), SandboxConfig(nseBseSquareOffTime = "bad").squareOffTimes.getValue("NSE"))
        val fromMap = SandboxConfig.fromConfigMap(mapOf("starting_capital" to " 500 ", "reset_day" to "Monday", "futures_leverage" to "20"))
        eq("500", fromMap.startingCapital); assertEquals("Monday", fromMap.resetDay); eq("20", fromMap.futuresLeverage)
    }

    @Test fun `costs - adverse slippage, limit clamp and charges`() {
        eq("100.50", SandboxCosts.adverse(d("100"), "buy", d("50"))); eq("99.50", SandboxCosts.adverse(d("100"), "SELL", d("50")))
        eq("100", SandboxCosts.adverse(d("100"), "BUY", d("0"))); eq("0", SandboxCosts.adverse(d("0"), "BUY", d("50")))
        eq("0.01", SandboxCosts.adverse(d("1"), "SELL", d("20000")), )
        eq("100", SandboxCosts.clampToLimit(d("100"), "BUY", null)); eq("100", SandboxCosts.clampToLimit(d("100"), "BUY", d("0")))
        eq("99", SandboxCosts.clampToLimit(d("100"), "BUY", d("99"))); eq("101", SandboxCosts.clampToLimit(d("100"), "sell", d("101")))
        val cfg = SandboxConfig(stopSlippageBps = d("100"), spreadFallbackBps = d("10"))
        eq("101.00", SandboxCosts.fillPrice(d("100"), "BUY", "sl-m", false, null, cfg))
        eq("100.50", SandboxCosts.fillPrice(d("100"), "BUY", "SL", false, d("100.50"), cfg))
        eq("100", SandboxCosts.fillPrice(d("100"), "BUY", "MARKET", true, null, cfg))
        eq("100.10", SandboxCosts.fillPrice(d("100"), "BUY", "MARKET", false, null, cfg))
        eq("100", SandboxCosts.fillPrice(d("100"), "BUY", "LIMIT", false, d("100"), cfg))
        assertEquals(emptyMap(), SandboxCosts.breakdown("BUY", 0.0, 10)); assertEquals(emptyMap(), SandboxCosts.breakdown("BUY", Double.NaN, 10))
        val buy = SandboxCosts.breakdown("buy", 100.0, -10)
        assertEquals(0.0, buy.getValue("STT")); assertEquals(1000.0 * 0.00003, buy.getValue("Stamp duty"), 1e-12)
        val sell = SandboxCosts.breakdown("SELL", 100.0, 10)
        assertEquals(1.5, sell.getValue("STT"), 1e-12); assertEquals(0.0, sell.getValue("Stamp duty"))
        eq("0.00", SandboxCosts.charge("BUY", d("0"), 10)); eq("0.00", SandboxCosts.charge("SELL", d("100"), 0))
        val total = sell.values.sum()
        eq(BigDecimal(total).setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString(), SandboxCosts.charge("SELL", d("100"), 10))
    }
}
