package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Honest paper fills (08 Oct, research/X3_AUDIT.md): the half-spread on every aggressive fill, the stale-price rule. */
class PaperSpreadTest {
    private fun d(s: String) = BigDecimal(s)
    private val ist = SandboxRules.IST
    private fun at(t: String) = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse("2026-10-08"), LocalTime.parse(t)), ist)
    private val bn = "BANKNIFTY28OCT2656000CE"
    private val fin = "FINNIFTY28OCT2626000PE"
    private val stock = "RELIANCE28OCT261400CE"
    private val noTick = "NIFTY28OCT2625000CE"
    private val master = InstrumentMaster.of(listOf(
        Instrument(bn, "NFO", "OPTIDX", lotSize = 30),
        Instrument(fin, "NFO", "OPTIDX", lotSize = 65),
        Instrument(stock, "NFO", "OPTSTK", lotSize = 500),
        Instrument(noTick, "NFO", "OPTIDX", lotSize = 75, tickSize = 0.0),
    ))
    private val honest = SandboxConfig(stopSlippageBps = d("10"), spreadFallbackBps = d("5"), chargesEnabled = true,
        pnlAlwaysToFunds = true, paperSpread = true)
    private val sb = Sandbox(honest, master)
    private fun req(sym: String, action: String, qty: Int, type: String = "MARKET", price: Double? = null, trigger: Double? = null) =
        OrderRequest(sym, "NFO", action, qty, type, "MIS", price, trigger)
    private fun q(ltp: Double, bid: Double = 0.0, ask: Double = 0.0) = Quote(ltp, bid, ask)

    /** The last trade's price and spread. */
    private fun last(s: SandboxState) = s.trades.last().let { it.price to it.spread }

    // ---- the pure rules ----

    @Test fun theDefaultsAreTheMeasuredHalfSpreads() {
        assertEquals("BANKNIFTY", PaperSpread.underlying(bn))
        assertEquals("NIFTY", PaperSpread.underlying(noTick))
        assertEquals(0.0016, PaperSpread.defaultHalfSpread(bn))
        assertEquals(0.0016, PaperSpread.defaultHalfSpread(noTick))
        assertEquals(0.0021, PaperSpread.defaultHalfSpread("MIDCPNIFTY28OCT2613000CE"))
        assertEquals(0.0042, PaperSpread.defaultHalfSpread(fin))
        assertEquals(0.0020, PaperSpread.defaultHalfSpread("SENSEX30OCT2682000PE"))
        // Options on stocks, or anything that does not read as a symbol: 0.30%.
        assertEquals(0.0030, PaperSpread.defaultHalfSpread(stock))
        assertEquals(0.0030, PaperSpread.defaultHalfSpread("XYZ"))
        assertNull(PaperSpread.underlying("XYZ"))
    }

    @Test fun aRealBookSetsTheHalfSpread() {
        assertEquals(0.01, PaperSpread.fromBook(99.0, 101.0)!!, 1e-12)
        assertNull(PaperSpread.fromBook(0.0, 101.0))
        assertNull(PaperSpread.fromBook(99.0, 0.0))
        assertNull(PaperSpread.fromBook(101.0, 99.0), "crossed")
        assertNull(PaperSpread.fromBook(99.0, Double.POSITIVE_INFINITY))
        assertNull(PaperSpread.fromBook(90.0, 110.0), "a 10% half-spread is not a real book")
        assertEquals(0.01, PaperSpread.halfSpread(bn, q(100.0, 99.0, 101.0)), 1e-12)
        assertEquals(0.0016, PaperSpread.halfSpread(bn, q(100.0)))
        assertEquals(0.0016, PaperSpread.halfSpread(bn, null))
        // The price before the spread for a fill that crossed the book.
        assertEquals(100.0, PaperSpread.mid(q(100.5, 99.0, 101.0), 1.0))
        assertEquals(100.5, PaperSpread.mid(q(100.5, 0.0, 101.0), 1.0))
        assertEquals(1.0, PaperSpread.mid(q(0.0, 0.0, 101.0), 1.0))
        assertEquals(1.0, PaperSpread.mid(null, 1.0))
    }

    @Test fun theFillMovesByTheLargerOfSlippageAndSpreadOnTheTick() {
        val t = d("0.05")
        // 200 x 1.0016 = 200.32: up to 200.35 for a buy; 220 x 0.9984 = 219.648: down to 219.60 for a sell.
        assertEquals(d("200.35"), PaperSpread.fill(d("200"), "BUY", 0.0016, d("5"), t))
        assertEquals(d("219.60"), PaperSpread.fill(d("220"), "SELL", 0.0016, d("5"), t))
        // Slippage larger than the spread wins, never both (X3 issue #2): 10 bps on 200 = 200.20.
        assertEquals(d("200.20"), PaperSpread.fill(d("200"), "BUY", 0.0001, d("10"), t))
        assertEquals(d("200.00"), PaperSpread.fill(d("200"), "BUY", -1.0, BigDecimal.ZERO, t))
        assertEquals(d("0"), PaperSpread.fill(d("0"), "BUY", 0.0016, d("5"), t))
        // Never under one tick; a missing tick is 0.05.
        assertEquals(d("0.05"), PaperSpread.toTick(d("0.01"), false, t))
        assertEquals(d("10.05"), PaperSpread.toTick(d("10.01"), true, BigDecimal.ZERO))
        assertEquals(d("31.50"), PaperSpread.charged(d("200"), d("201.05"), -30))
        assertEquals(d("0.30"), PaperSpread.charged(d("200"), d("199.70"), 1, d("1")))
    }

    @Test fun staleIsAPriceOlderThanAMinuteAndTheExitTakesTheWorseSide() {
        // The minute that started at 0 closed at 60: 60 s old at 120, 61 at 121.
        assertEquals(0L, PaperSpread.ageSeconds(0, 30))
        assertEquals(60L, PaperSpread.ageSeconds(0, 120))
        assertEquals(false, PaperSpread.isStale(0, 120))
        assertEquals(true, PaperSpread.isStale(0, 121))
        assertEquals(98.0, PaperSpread.conservativeExit("SELL", 100.0, 98.0, 103.0))
        assertEquals(103.0, PaperSpread.conservativeExit("buy", 100.0, 98.0, 103.0))
        assertEquals(100.0, PaperSpread.conservativeExit("SELL", 100.0, 0.0, 103.0))
        assertEquals(100.0, PaperSpread.conservativeExit("SELL", 100.0, Double.NaN, 103.0))
        assertEquals(100.0, PaperSpread.conservativeExit("BUY", 100.0, 98.0, 0.0))
        assertEquals(100.0, PaperSpread.conservativeExit("BUY", 100.0, 98.0, Double.POSITIVE_INFINITY))
    }

    @Test fun theWordsSayTheSpread() {
        assertEquals("Spread charged Rs 10.50", PaperSpread.chargedLine(10.5))
        assertEquals("Spread charged Rs 1,234.00", PaperSpread.chargedLine(1234.0))
        assertNull(PaperSpread.chargedLine(0.0))
        assertNull(PaperSpread.chargedLine(Double.NaN))
        assertEquals("paper spread charged today: Rs 52.50 over 3 fills", PaperSpread.dayLine(52.5, 3))
        assertEquals("paper spread charged today: Rs 0.00 over 1 fill", PaperSpread.dayLine(Double.NaN, 1))
        assertTrue("bid/ask spread" in PaperSpread.NOTE)
    }

    // ---- the engine ----

    @Test fun aMarketRoundTripPaysTheSpreadBothWays() {
        var s = sb.newState(at("09:30"))
        s = sb.place(s, req(bn, "BUY", 30), q(200.0), at("10:00")).state
        assertEquals(d("200.35") to d("10.50"), last(s))
        s = sb.place(s, req(bn, "SELL", 30), q(220.0), at("10:05")).state
        assertEquals(d("219.60") to d("12.00"), last(s))
        // The P&L is on the honest prices: (219.60 - 200.35) x 30 = 577.50 before charges.
        assertEquals(0, sb.tradeBook(s, at("10:06")).first().spread.compareTo(12.0))
        val view = sb.positionBook(s, at("10:06")).result.positions.single()
        assertEquals(577.5, view.todayRealizedPnl, 0.001)
    }

    @Test fun aRealBookFillsAtTheAskOrBidAndNothingMore() {
        var s = sb.newState(at("09:30"))
        s = sb.place(s, req(bn, "BUY", 30), q(200.0, 199.0, 201.0), at("10:00")).state
        assertEquals(d("201.00") to d("30.00"), last(s))
        // One side only: the fill is that side, the spread is its distance from the LTP.
        s = sb.place(s, req(bn, "SELL", 30), q(210.0, 209.5, 0.0), at("10:01")).state
        assertEquals(d("209.50") to d("15.00"), last(s))
    }

    @Test fun theDefaultDependsOnTheUnderlyingAndAMissingTickIsFivepaise() {
        var s = sb.newState(at("09:30"))
        // FINNIFTY 0.42%: 100 x 1.0042 = 100.42 -> 100.45.
        s = sb.place(s, req(fin, "BUY", 65), q(100.0), at("10:00")).state
        assertEquals(d("100.45"), last(s).first)
        // A stock option 0.30%: 10 x 1.003 = 10.03 -> 10.05.
        s = sb.place(s, req(stock, "BUY", 500), q(10.0), at("10:00")).state
        assertEquals(d("10.05"), last(s).first)
        s = sb.place(s, req(noTick, "BUY", 75), q(100.0), at("10:00")).state
        assertEquals(d("100.20"), last(s).first)
    }

    @Test fun aRestingStopPaysTheSpreadFromItsTriggerOrTheBook() {
        var s = sb.newState(at("09:30"))
        s = sb.place(s, req(bn, "BUY", 30), q(200.0), at("10:00")).state
        s = sb.place(s, req(bn, "SELL", 30, "SL-M", trigger = 180.0), q(200.0), at("10:00")).state
        val stop = s.orders.last().orderId
        // At its trigger: 180 x 0.9984 = 179.712 -> 179.70 (more than the 10 bps stop slip, never both).
        val a = sb.fillRestingStop(s, stop, 180.0, at("10:10")).state
        assertEquals(d("179.70") to d("9.00"), last(a))
        // With a real book, its half-spread: 1% from 100 = 99.00.
        val b = sb.fillRestingStop(s, stop, 100.0, at("10:10"), q(100.0, 99.0, 101.0)).state
        assertEquals(d("99.00") to d("30.00"), last(b))
        // The regular pass: touched at 179, 179 x 0.9984 = 178.71 -> 178.70.
        val c = sb.onQuotes(s, mapOf(Sandbox.key(bn, "NFO") to q(179.0)), at("10:10")).state
        assertEquals(d("178.70") to d("9.00"), last(c))
    }

    @Test fun aRestingLimitFillsOnlyWhenTradedThroughAndPaysNoSpread() {
        var s = sb.newState(at("09:30"))
        s = sb.place(s, req(bn, "BUY", 30, "LIMIT", price = 190.0), q(200.0), at("10:00")).state
        assertEquals("open", s.orders.last().status)
        val key = Sandbox.key(bn, "NFO")
        s = sb.onQuotes(s, mapOf(key to q(190.0)), at("10:01")).state
        assertEquals("open", s.orders.last().status, "a touch is not a fill")
        s = sb.onQuotes(s, mapOf(key to q(189.95)), at("10:02")).state
        assertEquals(d("190.00") to BigDecimal.ZERO, last(s))
        // A resting sell limit: through means above it.
        s = sb.place(s, req(bn, "SELL", 30, "LIMIT", price = 210.0), q(200.0), at("10:03")).state
        s = sb.onQuotes(s, mapOf(key to q(210.0)), at("10:04")).state
        assertEquals("open", s.orders.last().status)
        s = sb.onQuotes(s, mapOf(key to q(210.05)), at("10:05")).state
        assertEquals(d("210.00"), last(s).first)
    }

    @Test fun aMarketableLimitOrStopPaysTheSpreadButNeverPastItsLimit() {
        var s = sb.newState(at("09:30"))
        // 200 x 1.0016 = 200.35, held to the 200.10 limit.
        s = sb.place(s, req(bn, "BUY", 30, "LIMIT", price = 200.10), q(200.0), at("10:00")).state
        assertEquals(d("200.10") to d("3.00"), last(s))
        // Well inside the limit: the full spread.
        s = sb.place(s, req(bn, "BUY", 30, "LIMIT", price = 210.0), q(200.0), at("10:00")).state
        assertEquals(d("200.35"), last(s).first)
        // An SL whose trigger is met at placement: 200.10 x 1.0016 = 200.42 -> 200.45, held to 200.20.
        s = sb.place(s, req(bn, "BUY", 30, "SL", price = 200.20, trigger = 200.0), q(200.10), at("10:00")).state
        assertEquals(d("200.20"), last(s).first)
    }

    @Test fun heldOrdersWaitAndTheSpreadSurvivesTheStore() {
        var s = sb.newState(at("09:30"))
        s = sb.place(s, req(bn, "BUY", 30, "LIMIT", price = 190.0), q(200.0), at("10:00")).state
        val id = s.orders.last().orderId
        val key = Sandbox.key(bn, "NFO")
        val held = sb.onQuotes(s, mapOf(key to q(180.0)), at("10:01"), hold = setOf(id)).state
        assertEquals("open", held.orders.last().status)
        s = sb.place(s, req(bn, "BUY", 30), q(200.0), at("10:02")).state
        val back = SandboxJson.decode(SandboxJson.encode(s))
        assertEquals(s, back)
        assertEquals(d("10.50"), back.trades.last().spread)
    }

    @Test fun offByDefaultTheDesktopFillsAreUnchanged() {
        val plain = Sandbox(honest.copy(paperSpread = false), master)
        var s = plain.newState(at("09:30"))
        s = plain.place(s, req(bn, "BUY", 30), q(200.0), at("10:00")).state
        // The desktop's 5 bps fallback only: 200.10, no spread recorded.
        assertEquals(d("200.10") to BigDecimal.ZERO, last(s))
        s = plain.place(s, req(bn, "BUY", 30, "LIMIT", price = 190.0), q(200.0), at("10:00")).state
        s = plain.onQuotes(s, mapOf(Sandbox.key(bn, "NFO") to q(190.0)), at("10:01")).state
        assertEquals(d("190.00"), last(s).first, "the desktop fills a limit on a touch")
    }
}
