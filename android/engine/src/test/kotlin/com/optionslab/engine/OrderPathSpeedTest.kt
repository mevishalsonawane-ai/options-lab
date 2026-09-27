package com.optionslab.engine

import com.optionslab.engine.risk.AccountGuard
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Micro-benchmark of the engine code on every order's send path: the order gates ([Kite.refusals]), the
 * account-wide guard ([AccountGuard.refusals]) and the order's form body. They run on the phone between the
 * owner's confirmation and the POST (and on the main thread when the review re-gates a price edit), so they
 * must cost microseconds. The budget is ~100x what they take, so only a real regression (a scan, a parse,
 * I/O) trips it, never a slow CI machine.
 */
class OrderPathSpeedTest {
    private val order = Kite.Order("NIFTY26OCT24500CE", Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 100.05, 0.05, "NFO", "iraalgo")
    private val account = AccountGuard.Account(
        capital = 500_000.0, equity = 505_000.0, peakEquity = 510_000.0, dayPnl = 5_000.0,
        holdings = (0 until 20).map { AccountGuard.Holding("NIFTY26OCT${24_000 + it * 50}PE", -75, 75, "NIFTY", LocalDate.now().plusDays(9), "PE") },
        ordersToday = 6, minuteOfDay = 10 * 60)
    private val guardOrder = AccountGuard.Order(order.tradingSymbol, "BUY", 75, 75, 100.05, "NIFTY", LocalDate.now().plusDays(9), "CE")

    private fun perCallMicros(n: Int, block: () -> Unit): Double {
        repeat(n) { block() }                        // warm the JIT
        val t0 = System.nanoTime()
        repeat(n) { block() }
        return (System.nanoTime() - t0) / 1_000.0 / n
    }

    @Test fun `the order gates cost microseconds`() {
        var sink = 0
        val us = perCallMicros(20_000) {
            sink += Kite.refusals(order, Kite.Limits(), 3, false).size
            sink += AccountGuard.refusals(guardOrder, account, AccountGuard.Limits()).size
            sink += order.formBody().length
        }
        println("LATENCY engine gates + guard + form body: %.2f us per order".format(us))
        assertTrue(sink >= 0)
        assertTrue(us < 200.0, "the send-path gates took $us us per order (budget 200 us)")
    }
}
