package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The account's "P&L today" (funds) must always equal the positions' realised today less the charges paid: two arms
 * holding one option, resting stops, a stop left behind that fills into a short and is bought back (the case where the
 * desktop's logic booked the P&L on the position but never on the funds).
 */
class SandboxPnlAgreementTest {
    private val ist = SandboxRules.IST
    private fun at(t: String) = ZonedDateTime.of(LocalDateTime.of(LocalDate.parse("2026-10-01"), LocalTime.parse(t)), ist)
    private val sym = "BANKNIFTY27OCT2654800PE"
    private val sb = Sandbox(SandboxConfig(startingCapital = BigDecimal("100000.00"), stopSlippageBps = BigDecimal("10"),
        spreadFallbackBps = BigDecimal("5"), chargesEnabled = true, pnlAlwaysToFunds = true), InstrumentMaster.of(listOf(Instrument(sym, "NFO", "OPTIDX", lotSize = 30))))
    private fun q(x: Double) = Quote(ltp = x)
    private fun req(a: String, type: String = "MARKET", trig: Double? = null) = OrderRequest(sym, "NFO", a, 30, type, "MIS", triggerPrice = trig)

    private fun agree(s: SandboxState, t: String, ltp: Double) {
        val book = sb.positionBook(s, at(t), mapOf("NFO:$sym" to q(ltp))).result
        val charges = s.trades.fold(BigDecimal.ZERO) { a, x -> a + x.charges }.toDouble()
        assertEquals(book.totalTodayRealizedPnl - charges, s.funds.todayRealizedPnl.toDouble(), 0.011, "funds today vs positions - charges at $t")
        assertEquals(s.funds.todayRealizedPnl.toDouble(), s.funds.realizedPnl.toDouble(), 0.011)
    }

    @Test fun withoutTheSwitchTheDesktopsBehaviourIsKept() {
        val desktop = Sandbox(sb.config.copy(pnlAlwaysToFunds = false), InstrumentMaster.of(listOf(Instrument(sym, "NFO", "OPTIDX", lotSize = 30))))
        var s = desktop.newState(at("09:00"))
        s = desktop.place(s, req("BUY"), q(700.0), at("13:00")).state
        s = desktop.place(s, req("SELL", "SL-M", 690.0), q(700.0), at("13:00")).state
        s = desktop.place(s, req("SELL"), q(750.0), at("13:05")).state
        s = desktop.onQuotes(s, mapOf("NFO:$sym" to q(689.0)), at("13:10")).state
        val before = s.funds.realizedPnl
        s = desktop.place(s, req("BUY"), q(660.0), at("13:12")).state
        val charge = s.trades.last().charges
        // The short's buy-back P&L stays on the position only: the funds move by its charge alone.
        assertEquals((before - charge).toDouble(), s.funds.realizedPnl.toDouble(), 0.011)
    }

    @Test fun theAccountsPnlTodayAlwaysMatchesThePositionsLessCharges() {
        var s = sb.newState(at("09:00"))
        s = sb.place(s, req("BUY"), q(700.0), at("10:00")).state
        val stopA = sb.place(s, req("SELL", "SL-M", 660.0), q(700.0), at("10:00")); s = stopA.state
        s = sb.place(s, req("BUY"), q(705.0), at("10:05")).state
        val stopB = sb.place(s, req("SELL", "SL-M", 665.0), q(705.0), at("10:05")); s = stopB.state
        s = sb.cancel(s, stopA.result.orderId!!, at("10:30")).state
        s = sb.place(s, req("SELL"), q(760.0), at("10:30")).state
        agree(s, "10:31", 760.0)
        s = sb.cancel(s, stopB.result.orderId!!, at("10:40")).state
        s = sb.place(s, req("SELL"), q(790.0), at("10:40")).state
        agree(s, "10:41", 790.0)
        // A stop fills while the other arm still holds.
        s = sb.place(s, req("BUY"), q(700.0), at("12:00")).state
        s = sb.place(s, req("SELL", "SL-M", 690.0), q(700.0), at("12:00")).state
        s = sb.place(s, req("BUY"), q(700.0), at("12:01")).state
        val sB = sb.place(s, req("SELL", "SL-M", 680.0), q(700.0), at("12:01")); s = sB.state
        s = sb.onQuotes(s, mapOf("NFO:$sym" to q(689.0)), at("12:05")).state
        agree(s, "12:06", 689.0)
        s = sb.cancel(s, sB.result.orderId!!, at("12:10")).state
        s = sb.place(s, req("SELL"), q(720.0), at("12:10")).state
        agree(s, "12:11", 720.0)
        // A stop left resting after its long was sold fills into a short (no margin on it), then it is bought back.
        s = sb.place(s, req("BUY"), q(700.0), at("13:00")).state
        s = sb.place(s, req("SELL", "SL-M", 690.0), q(700.0), at("13:00")).state
        s = sb.place(s, req("SELL"), q(750.0), at("13:05")).state
        s = sb.onQuotes(s, mapOf("NFO:$sym" to q(689.0)), at("13:10")).state
        assertEquals(-30, s.positions.single().quantity)
        s = sb.place(s, req("BUY"), q(660.0), at("13:12")).state
        assertEquals(0, s.positions.single().quantity)
        agree(s, "13:13", 660.0)
    }
}
