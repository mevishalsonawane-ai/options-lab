package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cases of IraAlgo's own test/sandbox suite, ported: stale-quote guard,
 * session boundary, fund manager, CNC sell validation, holdings sells (#1640),
 * margin scenarios, rejected orders in the order book, and the MIS catch-up
 * filters. Plus the JSON store's contract.
 */
class SandboxTest {
    private val ist = SandboxRules.IST
    private fun at(d: String, t: String) = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse(d), LocalTime.parse(t)), ist)
    private val mon = "2026-09-21"
    private val instruments = InstrumentMaster.of(listOf(
        Instrument("RELIANCE", "NSE", "EQ"), Instrument("ZEEL", "NSE", "EQ"),
        Instrument("NIFTY29SEP26FUT", "NFO", "FUTIDX", lotSize = 75),
    ))
    private val sb = Sandbox(SandboxConfig(), instruments)
    private fun q(ltp: Double) = Quote(ltp = ltp)
    private fun buy(sym: String, qty: Int, product: String = "MIS") = OrderRequest(sym, "NSE", "BUY", qty, "MARKET", product)
    private fun sell(sym: String, qty: Int, product: String = "MIS") = OrderRequest(sym, "NSE", "SELL", qty, "MARKET", product)
    private fun d(s: String) = BigDecimal(s)

    // ---- test_stale_quote_guard.py -----------------------------------------

    @Test fun `the reported stale fill is deferred and coherent quotes fill`() {
        assertTrue(SandboxRules.quoteLooksStale(Quote(1047.60, high = 1345.0, low = 1262.0)))
        assertFalse(SandboxRules.quoteLooksStale(Quote(1296.0, high = 1345.0, low = 1262.0)))
        assertTrue(SandboxRules.quoteLooksStale(Quote(900.0, high = 1345.0, low = 1262.0)))
        assertTrue(SandboxRules.quoteLooksStale(Quote(1400.0, high = 1345.0, low = 1262.0)))
    }

    @Test fun `the day range is inclusive and missing OHLC never blocks`() {
        assertFalse(SandboxRules.quoteLooksStale(Quote(1262.0, high = 1345.0, low = 1262.0)))
        assertFalse(SandboxRules.quoteLooksStale(Quote(1345.0, high = 1345.0, low = 1262.0)))
        assertFalse(SandboxRules.quoteLooksStale(Quote(1047.60)))
        assertFalse(SandboxRules.quoteLooksStale(Quote(-5.0)))
        assertFalse(SandboxRules.quoteLooksStale(Quote(Double.NaN, high = 1.0, low = 0.5)))
    }

    // ---- session boundary ----------------------------------------------------

    @Test fun `the session boundary is the most recent 03_00 IST`() {
        val t = LocalDateTime.of(2026, 8, 18, 10, 0)
        assertEquals(LocalDateTime.of(2026, 8, 18, 3, 0), SandboxRules.lastSessionExpiry("03:00", t))
        assertEquals(LocalDateTime.of(2026, 8, 17, 3, 0), SandboxRules.lastSessionExpiry("03:00", t.withHour(1)))
        assertEquals(LocalDateTime.of(2026, 8, 18, 9, 15), SandboxRules.lastSessionExpiry("09:15", t))
    }

    @Test fun `a malformed session expiry falls back to 03_00 instead of raising`() {
        val t = LocalDateTime.of(2026, 8, 18, 10, 0)
        for (bad in listOf("", "abc", "3", "03:00:00", "25:00", "-1:00", "03:99")) {
            assertEquals(LocalDateTime.of(2026, 8, 18, 3, 0), SandboxRules.lastSessionExpiry(bad, t), bad)
        }
    }

    // ---- test_fund_manager.py ----------------------------------------------

    @Test fun `a new account holds one crore`() {
        val s = sb.newState(at(mon, "09:00"))
        assertEquals(10_000_000.0, sb.funds(s, at(mon, "09:01")).result.availableCash)
    }

    @Test fun `leverage follows product and instrument`() {
        val c = SandboxConfig()
        assertEquals(d("5"), SandboxRules.leverage(c, "RELIANCE", "NSE", "MIS", "BUY"))
        assertEquals(d("1"), SandboxRules.leverage(c, "RELIANCE", "NSE", "CNC", "BUY"))
        assertEquals(d("10"), SandboxRules.leverage(c, "NIFTY29SEP26FUT", "NFO", "NRML", "SELL"))
        assertEquals(d("1"), SandboxRules.leverage(c, "NIFTY29SEP2625000CE", "NFO", "NRML", "SELL"))
        assertEquals(d("1"), SandboxRules.leverage(c, "GOLD", "NCO", "NRML", "BUY"))
    }

    @Test fun `blocking then releasing with a profit returns the profit`() {
        var s = sb.newState(at(mon, "09:00"))
        s = sb.place(s, buy("RELIANCE", 100, "CNC"), Quote(1000.0), at(mon, "09:30")).state
        assertEquals(d("100000.00"), s.funds.usedMargin)
        assertEquals(d("9900000.00"), s.funds.availableBalance)
        s = sb.place(s, sell("RELIANCE", 100, "CNC"), Quote(1050.0), at(mon, "09:31")).state
        assertEquals(d("0.00"), s.funds.usedMargin)
        assertEquals(d("10005000.00"), s.funds.availableBalance)
        assertEquals(5000.0, sb.funds(s, at(mon, "09:32")).result.m2mRealized)
    }

    @Test fun `an order larger than the balance is refused with the shortage`() {
        val s = Sandbox(SandboxConfig(startingCapital = d("100000.00")), instruments).newState(at(mon, "09:00"))
        val r = Sandbox(SandboxConfig(startingCapital = d("100000.00")), instruments)
            .place(s, buy("RELIANCE", 100, "CNC"), Quote(2500.0), at(mon, "09:30")).result
        assertFalse(r.ok)
        assertEquals("Insufficient funds. Required: ₹250000.0, Available: ₹100000.00, Shortage: ₹150000.00", r.message)
    }

    @Test fun `unrealized P&L flows into total P&L`() {
        var s = sb.newState(at(mon, "09:00"))
        s = sb.place(s, buy("RELIANCE", 100), Quote(2500.0), at(mon, "09:30")).state
        s = sb.positionBook(s, at(mon, "09:40"), mapOf(Sandbox.key("RELIANCE", "NSE") to q(2750.0))).state
        val f = sb.funds(s, at(mon, "09:41")).result
        assertEquals(25_000.0, f.m2mUnrealized)
        assertEquals(25_000.0, f.totalPnl)
    }

    // ---- test_cnc_sell_validation.py ---------------------------------------

    @Test fun `CNC sell without shares is rejected and recorded`() {
        var s = sb.newState(at(mon, "09:00"))
        val o = sb.place(s, sell("RELIANCE", 10, "CNC"), q(2500.0), at(mon, "09:30"))
        assertFalse(o.result.ok)
        assertEquals(
            "Cannot sell RELIANCE in CNC. No positions or holdings available. CNC (delivery) requires existing shares. Use MIS for intraday short selling.",
            o.result.message,
        )
        s = o.state
        val book = sb.orderBook(s, at(mon, "09:31"))
        assertEquals(OrderStatus.REJECTED, book.orders.single().status)
        assertEquals(o.result.message, book.orders.single().rejectionReason)
        assertEquals(1, book.statistics.totalRejectedOrders)
        assertEquals(1, book.statistics.totalSellOrders)
    }

    @Test fun `CNC sell counts the signed position plus holdings`() {
        var s = sb.newState(at(mon, "09:00"))
        s = sb.place(s, buy("RELIANCE", 50, "CNC"), q(2500.0), at(mon, "09:30")).state
        s = s.copy(holdings = listOf(Holding("RELIANCE", "NSE", 100, d("2400.00"), d("2400.00"), d("0.00"), d("0.0000"),
            LocalDate.parse(mon), LocalDateTime.parse("2026-09-21T00:00"), LocalDateTime.parse("2026-09-21T00:00"))))
        val over = sb.place(s, sell("RELIANCE", 151, "CNC"), q(2500.0), at(mon, "09:31")).result
        assertEquals("Cannot sell 151 shares of RELIANCE in CNC. Only 150 shares available (Position: 50, Holdings: 100)", over.message)
        s = sb.place(s, sell("RELIANCE", 120, "CNC"), q(2600.0), at(mon, "09:32")).state
        // #1640: the long 50 closes, 70 come from the holding, which stays until T+1.
        assertEquals(-70, s.positions.single().quantity)
        assertEquals(100, s.holdings.single().quantity)
        // Holding 100, day position -70: only 30 left to sell.
        val again = sb.place(s, sell("RELIANCE", 31, "CNC"), q(2600.0), at(mon, "09:33")).result
        assertEquals("Cannot sell 31 shares of RELIANCE in CNC. Only 30 shares available (Position: -70, Holdings: 100)", again.message)
    }

    @Test fun `MIS allows a short without shares`() {
        val s = sb.place(sb.newState(at(mon, "09:00")), sell("ZEEL", 100), q(112.0), at(mon, "09:30")).state
        assertEquals(-100, s.positions.single().quantity)
        assertEquals(d("2240.00"), s.positions.single().marginBlocked)
    }

    // ---- test_holdings_sell.py ---------------------------------------------

    private fun withHolding(qty: Int): SandboxState = sb.newState(at(mon, "09:00")).copy(holdings = listOf(
        Holding("RELIANCE", "NSE", qty, d("2400.00"), d("2400.00"), d("0.00"), d("0.0000"), LocalDate.parse(mon),
            LocalDateTime.parse("2026-09-21T00:00"), LocalDateTime.parse("2026-09-21T00:00")),
    ))

    @Test fun `selling part of a holding leaves a negative day position and no cash yet`() {
        val s = sb.place(withHolding(200), sell("RELIANCE", 150, "CNC"), q(2500.0), at(mon, "09:30")).state
        assertEquals(200, s.holdings.single().quantity)
        assertEquals(-150, s.positions.single().quantity)
        assertEquals(d("10000000.00"), s.funds.availableBalance)
        // T+1 reduces the holding and credits the proceeds at the sale price.
        val t1 = sb.settleT1(s, at("2026-09-22", "00:00")).state
        assertEquals(50, t1.holdings.single().quantity)
        assertEquals(d("10375000.00"), t1.funds.availableBalance)
        assertTrue(t1.positions.isEmpty())
    }

    @Test fun `selling a whole holding settles it away`() {
        val s = sb.place(withHolding(200), sell("RELIANCE", 200, "CNC"), q(2500.0), at(mon, "09:30")).state
        assertEquals(-200, s.positions.single().quantity)
        assertTrue(sb.settleT1(s, at("2026-09-22", "00:01")).state.holdings.isEmpty())
    }

    // ---- test_margin_scenarios.py ------------------------------------------

    @Test fun `buy 100 then sell 50 twice releases all margin`() {
        var s = sb.newState(at(mon, "09:00"))
        s = sb.place(s, buy("ZEEL", 100, "CNC"), q(112.37), at(mon, "09:30")).state
        assertEquals(d("11237.00"), s.funds.usedMargin)
        s = sb.place(s, sell("ZEEL", 50, "CNC"), q(112.37), at(mon, "09:31")).state
        assertEquals(d("5618.50"), s.funds.usedMargin)
        s = sb.place(s, sell("ZEEL", 50, "CNC"), q(112.37), at(mon, "09:32")).state
        assertEquals(d("0.00"), s.funds.usedMargin)
        assertEquals(d("10000000.00"), s.funds.availableBalance)
    }

    @Test fun `a closed position reopens with fresh margin and keeps its realized P&L`() {
        var s = sb.newState(at(mon, "09:00"))
        s = sb.place(s, buy("ZEEL", 100), q(100.0), at(mon, "09:30")).state
        s = sb.place(s, sell("ZEEL", 100), q(110.0), at(mon, "09:31")).state
        s = sb.place(s, buy("ZEEL", 100), q(105.0), at(mon, "09:32")).state
        val p = s.positions.single()
        assertEquals(100, p.quantity)
        assertEquals(d("2100.00"), p.marginBlocked)
        assertEquals(d("1000.00"), p.accumulatedRealizedPnl)
        assertEquals(d("2100.00"), s.funds.usedMargin)
    }

    @Test fun `a reversal keeps only the excess exposure and reconciles the rest`() {
        var s = sb.newState(at(mon, "09:00"))
        s = sb.place(s, buy("ZEEL", 100), q(100.0), at(mon, "09:30")).state
        val o = sb.place(s, sell("ZEEL", 200), q(100.0), at(mon, "09:31"))
        s = o.state
        assertEquals(-100, s.positions.single().quantity)
        // The order blocked margin for the excess 100 (2000); the reversed
        // position is given 2000 x 100/200 = 1000, and reconciliation frees 1000.
        assertEquals(d("2000.00"), s.orders.last().marginBlocked)
        assertEquals(d("1000.00"), s.positions.single().marginBlocked)
        assertEquals(d("1000.00"), s.funds.usedMargin)
        assertTrue(o.events.any { it is SandboxEvent.MarginReconciled })
    }

    @Test fun `adding to a position averages the price and sums the margin`() {
        var s = sb.newState(at(mon, "09:00"))
        s = sb.place(s, buy("ZEEL", 100), q(100.0), at(mon, "09:30")).state
        s = sb.place(s, buy("ZEEL", 200), q(103.0), at(mon, "09:31")).state
        assertEquals(300, s.positions.single().quantity)
        assertEquals(d("102.00"), s.positions.single().averagePrice)
        assertEquals(d("6120.00"), s.positions.single().marginBlocked)
    }

    // ---- catch-up filters (test_catch_up_session_boundary.py) ---------------

    private fun misPosition(symbol: String, exchange: String, qty: Int, avg: String, updated: LocalDateTime) = Position(
        symbol, exchange, "MIS", qty, d(avg), d(avg), d("0.00"), d("0.0000"), d("0.00"), d("0.00"), d("0.00"),
        updated.minusDays(1), updated,
    )

    @Test fun `catch-up settles only MIS untouched since before the boundary, on configured exchanges`() {
        val now = at("2026-08-18", "10:00")
        val boundary = LocalDateTime.of(2026, 8, 18, 3, 0)
        val s = sb.newState(now).copy(positions = listOf(
            misPosition("ZEEL", "NSE", 50, "100.00", boundary.plusHours(6)),
            misPosition("RELIANCE", "NSE", -25, "200.00", boundary.minusHours(6)),
            misPosition("UNKNOWN", "UNKNOWN", 10, "100.00", boundary.minusHours(6)),
            misPosition("BTCUSD.P", "CRYPTO", 10, "3000.00", boundary.minusHours(6)),
        ))
        val after = sb.catchUp(s, now).state.positions.associate { it.symbol to it.quantity }
        assertEquals(mapOf("ZEEL" to 50, "RELIANCE" to 0, "UNKNOWN" to 10, "BTCUSD.P" to 10), after)
    }

    @Test fun `the position book keeps this session plus carried NRML`() {
        val now = at("2026-08-18", "14:07")
        val boundary = LocalDateTime.of(2026, 8, 18, 3, 0)
        fun p(sym: String, product: String, qty: Int, updated: LocalDateTime) =
            misPosition(sym, "NSE", qty, "100.00", updated).copy(product = product)
        val s = sb.newState(now).copy(positions = listOf(
            p("SBTEST1", "MIS", 10, boundary.plusMinutes(30)),
            p("SBTEST2", "MIS", 10, boundary.minusMinutes(30)),
            p("SBTEST3", "NRML", 10, boundary.minusMinutes(30)),
            p("SBTEST4", "MIS", 0, boundary.minusMinutes(30)),
        ))
        val shown = sb.positionBook(s, now).result.positions.map { it.symbol }.toSet()
        assertEquals(setOf("SBTEST1", "SBTEST3"), shown)
    }

    // ---- rules ----------------------------------------------------------------

    @Test fun `expiry is parsed from the first date in an F&O symbol`() {
        assertEquals(LocalDate.of(2025, 12, 9), SandboxRules.parseExpiryFromSymbol("NIFTY09DEC2526000CE", "NFO"))
        assertEquals(LocalDate.of(2025, 7, 31), SandboxRules.parseExpiryFromSymbol("BANKNIFTY31JUL25FUT", "NFO"))
        assertNull(SandboxRules.parseExpiryFromSymbol("RELIANCE", "NSE"))
        assertNull(SandboxRules.parseExpiryFromSymbol("NIFTY31FEB2526000CE", "NFO"))
        assertNull(SandboxRules.parseExpiryFromSymbol("GOLD05DEC25FUT", "NCO"))
    }

    @Test fun `expiry day settles from the exchange close only under expiry_day_close`() {
        val c = SandboxConfig()
        val exp = LocalDate.of(2026, 9, 29)
        assertFalse(SandboxRules.isContractExpiredNow(exp, "NFO", LocalDateTime.of(2026, 9, 29, 15, 39), c))
        assertTrue(SandboxRules.isContractExpiredNow(exp, "NFO", LocalDateTime.of(2026, 9, 29, 15, 40), c))
        assertFalse(SandboxRules.isContractExpiredNow(exp, "MCX", LocalDateTime.of(2026, 9, 29, 23, 29), c))
        assertTrue(SandboxRules.isContractExpiredNow(exp, "CDS", LocalDateTime.of(2026, 9, 29, 17, 0), c))
        val nextDay = c.copy(expirySettlementTiming = "next_day")
        assertFalse(SandboxRules.isContractExpiredNow(exp, "NFO", LocalDateTime.of(2026, 9, 29, 23, 59), nextDay))
        assertTrue(SandboxRules.isContractExpiredNow(exp, "NFO", LocalDateTime.of(2026, 9, 30, 0, 0), nextDay))
    }

    @Test fun `square-off times come from config per exchange`() {
        val c = SandboxConfig(mcxSquareOffTime = "bad")
        assertEquals(LocalTime.of(15, 15), c.squareOffTimes["NFO"])
        assertEquals(LocalTime.of(16, 45), c.squareOffTimes["BCD"])
        assertEquals(LocalTime.of(15, 15), c.squareOffTimes["MCX"])
        assertNull(c.squareOffTimes["CRYPTO"])
    }

    // ---- JSON store -----------------------------------------------------------

    @Test fun `state survives the JSON store exactly`() {
        var s = sb.newState(at(mon, "09:00"))
        s = sb.place(s, buy("ZEEL", 333), Quote(112.37, high = 115.0, low = 110.0), at(mon, "09:30")).state
        s = sb.place(s, OrderRequest("ZEEL", "NSE", "BUY", 7, "LIMIT", "MIS", price = 101.237, strategy = "a \"quoted\"\nline"), null, at(mon, "09:31")).state
        s = sb.place(s, sell("RELIANCE", 5, "CNC"), q(2500.0), at(mon, "09:32")).state
        val text = SandboxJson.encode(s)
        assertEquals(s, SandboxJson.decode(text))
        assertEquals(text, SandboxJson.encode(SandboxJson.decode(text)))
    }

    @Test fun `a truncated or foreign document is refused, not half-restored`() {
        val text = SandboxJson.encode(sb.newState(at(mon, "09:00")))
        assertFailsWith<IllegalArgumentException> { SandboxJson.decode(text.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { SandboxJson.decode(text.replace("\"version\":1", "\"version\":2")) }
        assertFailsWith<IllegalArgumentException> { SandboxJson.decode(text.replace("\"usedMargin\"", "\"used\"")) }
    }
}
