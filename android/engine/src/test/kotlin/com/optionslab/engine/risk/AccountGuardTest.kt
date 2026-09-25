package com.optionslab.engine.risk

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountGuardTest {
    private val exp = LocalDate.of(2026, 9, 30)
    private val calm = AccountGuard.Account(capital = 1_000_000.0, equity = 1_000_000.0, peakEquity = 1_000_000.0,
        dayPnl = 0.0, holdings = emptyList(), ordersToday = 0, minuteOfDay = 10 * 60 + 30)
    private val buyCe = AccountGuard.Order("NIFTY24800CE", "BUY", 75, 75, 100.0, "NIFTY", exp, "CE")
    private val limits = AccountGuard.Limits()
    private fun reasons(o: AccountGuard.Order = buyCe, a: AccountGuard.Account = calm, l: AccountGuard.Limits = limits) = AccountGuard.refusals(o, a, l)

    @Test fun plainEntryPasses() = assertEquals(emptyList(), reasons())

    @Test fun killSwitchBlocksEvenExits() {
        val held = calm.copy(holdings = listOf(AccountGuard.Holding("NIFTY24800CE", 75, 75, "NIFTY", exp, "CE")))
        val exit = buyCe.copy(side = "SELL")
        assertTrue(AccountGuard.isExit(exit, held))
        assertEquals(1, reasons(exit, held, limits.copy(killSwitch = true)).size)
    }

    @Test fun exitsPassEveryOtherLimit() {
        val bad = calm.copy(dayPnl = -50_000.0, ordersToday = 99, minuteOfDay = 15 * 60, equity = 500_000.0,
            holdings = listOf(AccountGuard.Holding("NIFTY24800CE", 75, 75, "NIFTY", exp, "CE")))
        assertEquals(emptyList(), reasons(buyCe.copy(side = "SELL"), bad))
    }

    @Test fun dailyLoss() = assertTrue(reasons(a = calm.copy(dayPnl = -2_000.0)).any { "Daily loss" in it })

    @Test fun drawdownFromCapitalAndFromPeak() {
        assertTrue(reasons(a = calm.copy(equity = 899_000.0)).any { "below capital" in it })
        assertTrue(reasons(a = calm.copy(capital = 800_000.0, peakEquity = 1_200_000.0, equity = 1_050_000.0)).any { "below its peak" in it })
        assertEquals(emptyList(), reasons(a = calm.copy(equity = 950_000.0)))
    }

    @Test fun openPositionsCountsDistinctInstruments() {
        val three = calm.copy(holdings = listOf("A", "B", "C").map { AccountGuard.Holding(it, 75, 75) })
        assertTrue(reasons(a = three).any { "Open positions" in it })
        // Adding to one already held is not a new position.
        assertEquals(emptyList(), reasons(AccountGuard.Order("A", "BUY", 75, 75, 100.0), three))
    }

    @Test fun tradesPerDayValueExposureAndCutoff() {
        assertTrue(reasons(a = calm.copy(ordersToday = 10)).any { "Trades per day" in it })
        assertTrue(reasons(buyCe.copy(price = 7_000.0)).any { "Order value" in it })
        assertTrue(reasons(buyCe.copy(qty = 225)).any { "Exposure" in it })
        assertTrue(reasons(a = calm.copy(minuteOfDay = 14 * 60 + 30)).any { "No new entries" in it })
    }

    @Test fun nakedShortNeedsASameSeriesLong() {
        val sellPe = AccountGuard.Order("NIFTY24500PE", "SELL", 75, 75, 80.0, "NIFTY", exp, "PE")
        assertTrue(reasons(sellPe).any { "Naked short" in it })
        val hedged = calm.copy(holdings = listOf(AccountGuard.Holding("NIFTY24300PE", 75, 75, "NIFTY", exp, "PE")))
        assertEquals(emptyList(), reasons(sellPe, hedged))
        assertTrue(reasons(sellPe, calm, limits.copy(blockNakedShort = false)).isEmpty())
    }

    @Test fun zeroMeansOff() {
        val off = AccountGuard.Limits(maxDailyLoss = 0.0, maxDrawdownPct = 0.0, maxOpenPositions = 0, maxTradesPerDay = 0,
            maxOrderValue = 0.0, maxLotsPerSymbol = 0, entryCutoffMinute = null, blockNakedShort = false)
        val awful = calm.copy(dayPnl = -1e9, equity = 1.0, ordersToday = 1_000, minuteOfDay = 15 * 60)
        assertEquals(emptyList(), reasons(buyCe.copy(qty = 7500, price = 1e6), awful, off))
    }

    @Test fun peakOnlyRises() {
        assertEquals(110.0, AccountGuard.nextPeak(100.0, 110.0))
        assertEquals(110.0, AccountGuard.nextPeak(110.0, 90.0))
    }
}
