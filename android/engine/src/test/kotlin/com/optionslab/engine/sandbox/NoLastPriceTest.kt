package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 28 Oct (Boss: "Yes fix finnifty"): a quote with a live book but no last price ([Quote.noLast]; its ltp is the bid). Every order
 * is judged on the side it trades against: a buy on the ask, a sale on the bid; a long is marked at the bid, a short at the ask.
 * A quote with a last price is unchanged.
 */
class NoLastPriceTest {
    private fun d(s: String) = BigDecimal(s)
    private val ist = SandboxRules.IST
    private fun at(t: String) = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse("2026-10-28"), LocalTime.parse(t)), ist)
    private val fin = "FINNIFTY23NOV2624250PE"
    private val key = Sandbox.key(fin, "NFO")
    private val master = InstrumentMaster.of(listOf(Instrument(fin, "NFO", "OPTIDX", lotSize = 65)))
    private val sb = Sandbox(SandboxConfig(stopSlippageBps = d("10"), spreadFallbackBps = d("5"), chargesEnabled = true,
        pnlAlwaysToFunds = true, paperSpread = true), master)
    private fun req(action: String, type: String = "MARKET", price: Double? = null, trigger: Double? = null) =
        OrderRequest(fin, "NFO", action, 65, type, "MIS", price, trigger)
    /** No last price: the ltp is the bid, as the app builds it. */
    private fun book(bid: Double, ask: Double) = Quote(bid, bid, ask, noLast = true)
    private fun last(s: SandboxState) = s.trades.last().price

    @Test fun theTradeSideIsTheLastTradeOrTheBooksSide() {
        assertEquals(100.0, Quote(100.0, 99.0, 101.0).tradeSide(buy = true))
        assertEquals(100.0, Quote(100.0, 99.0, 101.0).tradeSide(buy = false))
        assertEquals(101.0, book(99.0, 101.0).tradeSide(buy = true))
        assertEquals(99.0, book(99.0, 101.0).tradeSide(buy = false))
        assertEquals(0.0, Quote(99.0, 99.0, 0.0, noLast = true).tradeSide(buy = true), "no ask: nothing to buy at")
        assertFalse(Quote(1.0).noLast)
    }

    @Test fun aMarketBuyFillsAtTheAskAndASellAtTheBid() {
        var s = sb.newState(at("09:15"))
        s = sb.place(s, req("BUY"), book(149.5, 150.0), at("09:20")).state
        assertEquals(d("150.00"), last(s))
        s = sb.place(s, req("SELL"), book(151.0, 151.5), at("09:40")).state
        assertEquals(d("151.00"), last(s))
    }

    @Test fun aLongIsMarkedAtTheBid() {
        var s = sb.newState(at("09:15"))
        s = sb.place(s, req("BUY"), book(149.5, 150.0), at("09:20")).state
        s = sb.positionBook(s, at("09:21"), mapOf(key to book(148.0, 149.0))).state
        assertEquals(0, s.positions.single().ltp!!.compareTo(d("148")))
    }

    @Test fun aShortIsMarkedAtTheAskAndItsStopReadsTheAsk() {
        var s = sb.newState(at("09:15"))
        s = sb.place(s, req("SELL"), book(149.5, 150.0), at("09:20")).state
        assertEquals(d("149.50"), last(s))
        s = sb.positionBook(s, at("09:21"), mapOf(key to book(150.0, 151.0))).state
        assertEquals(0, s.positions.single().ltp!!.compareTo(d("151")))
        // A short's BUY SL-M at 160: the bid at 159.5 says nothing, the ask at 160.5 reaches it (fills at that ask).
        s = sb.place(s, req("BUY", "SL-M", trigger = 160.0), book(150.0, 151.0), at("09:22")).state
        s = sb.onQuotes(s, mapOf(key to book(160.0, 160.5)), at("09:25")).state
        assertEquals(d("160.50"), last(s))
    }

    @Test fun aHeldLongsStopAndTargetUseTheBid() {
        var s = sb.newState(at("09:15"))
        s = sb.place(s, req("BUY"), book(149.5, 150.0), at("09:20")).state
        s = sb.place(s, req("SELL", "SL-M", trigger = 135.0), book(149.5, 150.0), at("09:20")).state
        // Only the bid counts for a sale: bid 136 is over the 135 stop, not reached.
        s = sb.onQuotes(s, mapOf(key to book(136.0, 140.0)), at("09:30")).state
        assertEquals("trigger pending", s.orders.last().status)
        // Bid 134.5: reached; filled at that bid (the book's own side: no second spread on top).
        s = sb.onQuotes(s, mapOf(key to book(134.5, 135.5)), at("09:31")).state
        assertEquals(d("134.50"), last(s))
        assertEquals(0, s.positions.single().quantity)
    }

    @Test fun aTargetLimitSellFillsOnlyWhenTheBidIsThroughIt() {
        var s = sb.newState(at("09:15"))
        s = sb.place(s, req("BUY"), book(149.5, 150.0), at("09:20")).state
        s = sb.place(s, req("SELL", "LIMIT", price = 180.0), book(149.5, 150.0), at("09:20")).state
        // The ask over the target is not a buyer there.
        s = sb.onQuotes(s, mapOf(key to book(179.0, 181.0)), at("09:40")).state
        assertEquals("open", s.orders.last().status)
        s = sb.onQuotes(s, mapOf(key to book(180.5, 181.0)), at("09:41")).state
        assertEquals(d("180.00"), last(s))
    }

    @Test fun aBuyLimitInsideTheSpreadRestsUntilTheAskComesToIt() {
        var s = sb.newState(at("09:15"))
        // Bid 149.5 is under the 149.8 limit but nobody sells there: the order rests.
        s = sb.place(s, req("BUY", "LIMIT", price = 149.8), book(149.5, 150.0), at("09:20")).state
        assertEquals("open", s.orders.last().status)
        s = sb.onQuotes(s, mapOf(key to book(149.0, 149.7)), at("09:21")).state
        assertEquals(d("149.80"), last(s))
    }

    @Test fun aOneSidedBookWithNoAskNeverFillsABuy() {
        val s = sb.newState(at("09:15"))
        val r = sb.place(s, req("BUY"), Quote(149.5, 149.5, 0.0, noLast = true), at("09:20"))
        assertFalse(r.events.any { it is SandboxEvent.Fill })
        assertTrue(r.state.trades.isEmpty())
    }

    @Test fun aQuoteWithALastPriceIsUnchanged() {
        var s = sb.newState(at("09:15"))
        s = sb.place(s, req("BUY"), Quote(150.0, 149.0, 151.0), at("09:20")).state
        assertEquals(d("151.00"), last(s))
        s = sb.positionBook(s, at("09:21"), mapOf(key to Quote(150.0, 149.0, 151.0))).state
        assertEquals(0, s.positions.single().ltp!!.compareTo(d("150")))
    }
}
