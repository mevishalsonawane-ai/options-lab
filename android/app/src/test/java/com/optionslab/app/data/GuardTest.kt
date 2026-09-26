package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.risk.AccountGuard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The account guard as the app applies it: the owner's limits from settings, paper and live limits
 * kept apart, the kill switch (which a live drawdown turns on by itself), exits never trapped, and
 * the equity peak it measures drawdown from.
 */
class GuardTest : RobolectricTest() {
    private val expiry = LocalDate.of(2026, 10, 27)
    private fun order(side: String = "BUY", qty: Int = 75, price: Double = 100.0, sym: String = "NIFTY26OCT24500PE") =
        AccountGuard.Order(sym, side, qty, 75, price, "NIFTY", expiry, "PE")
    private fun account(dayPnl: Double = 0.0, capital: Double = 100_000.0, equity: Double = capital, peak: Double = capital,
                        holdings: List<AccountGuard.Holding> = emptyList(), orders: Int = 0, minute: Int = 10 * 60) =
        AccountGuard.Account(capital, equity, peak, dayPnl, holdings, orders, minute)
    private fun limits(transform: (AppSettings) -> AppSettings) = AppSettings.save(transform(AppSettings()))

    @Test fun aSoundEntryPasses() {
        limits { it }
        assertEquals(emptyList<String>(), Guard.check(order(), account()))
        assertEquals(emptyList<String>(), Guard.check(order(), account(), paper = true))
    }

    @Test fun theKillSwitchRefusesEntriesButNeverTrapsAnExit() {
        limits { it.copy(guardKill = true) }
        assertTrue(Guard.check(order(), account()).single().startsWith("The kill switch is on"))
        assertTrue("even with no account to read", Guard.check(order(), null).single().startsWith("The kill switch is on"))
        val held = account(holdings = listOf(AccountGuard.Holding(order().symbol, 75, 75, "NIFTY", expiry, "PE")))
        assertEquals("an exit goes through the kill switch", emptyList<String>(), Guard.check(order(side = "SELL"), held, exit = true))
        assertEquals(emptyList<String>(), Guard.check(order(side = "SELL"), null, exit = true))
    }

    @Test fun withoutTheAccountEntriesWait() {
        limits { it }
        assertEquals(listOf("The account could not be read to check its limits; try again in a moment."), Guard.check(order(), null))
    }

    @Test fun dailyLossOrderCountAndCutOffRefuseEntries() {
        limits { it.copy(guardDailyLoss = 1_000.0, guardMaxTrades = 5, guardCutoff = 14 * 60 + 30) }
        assertTrue(Guard.check(order(), account(dayPnl = -1_000.0)).any { it.startsWith("Daily loss limit reached") })
        assertTrue(Guard.check(order(), account(orders = 5)).any { it.startsWith("Trades per day limit") })
        assertTrue(Guard.check(order(), account(minute = 14 * 60 + 30)).any { it == "No new entries after 14:30." })
        assertEquals(emptyList<String>(), Guard.check(order(), account(dayPnl = -999.0, orders = 4, minute = 14 * 60 + 29)))
        // The cut-off can be switched off.
        limits { it.copy(guardCutoff = -1) }
        assertEquals(emptyList<String>(), Guard.check(order(), account(minute = 15 * 60 + 20)))
    }

    @Test fun paperAndLiveKeepTheirOwnLimits() {
        limits { it.copy(guardMaxTrades = 10, guardPaperTrades = 30, guardDailyLoss = 1_000.0, guardPaperDailyLoss = 6_000.0) }
        val a = account(orders = 15, dayPnl = -2_000.0)
        assertTrue(Guard.check(order(), a).isNotEmpty())
        assertEquals(emptyList<String>(), Guard.check(order(), a, paper = true))
    }

    @Test fun aLiveDrawdownTurnsTheKillSwitchOnAPaperOneDoesNot() {
        limits { it.copy(guardDrawdownPct = 10.0, guardPaperDrawdownPct = 10.0, guardDailyLoss = 0.0, guardPaperDailyLoss = 0.0) }
        val down = account(capital = 100_000.0, equity = 85_000.0, peak = 100_000.0)
        assertTrue(Guard.check(order(), down, paper = true).any { it.startsWith("Drawdown limit") })
        assertFalse("a paper drawdown must not block live exits", AppSettings.load().guardKill)
        assertTrue(Guard.check(order(), down).any { it.startsWith("Drawdown limit") })
        assertTrue("a live drawdown engages the kill switch", AppSettings.load().guardKill)
        // Other refusals leave it alone.
        limits { it.copy(guardKill = false, guardDailyLoss = 1_000.0) }
        Guard.check(order(), account(dayPnl = -5_000.0))
        assertFalse(AppSettings.load().guardKill)
    }

    @Test fun anExitIsCheckedAgainstNothingButStillCountsAsAnOrder() {
        limits { it.copy(guardDailyLoss = 1_000.0, guardMaxTrades = 1) }
        val held = account(dayPnl = -9_000.0, orders = 3, holdings = listOf(AccountGuard.Holding(order().symbol, 150, 75, "NIFTY", expiry, "PE")))
        assertEquals(emptyList<String>(), Guard.check(order(side = "SELL"), held, exit = true))
        val after = Guard.after(held, order(side = "SELL"))
        assertEquals(4, after.ordersToday)
        assertEquals(75, after.holdings.single().qty)
        val flat = Guard.after(after, order(side = "SELL"))
        assertTrue("a position closed to zero is gone", flat.holdings.isEmpty())
    }

    @Test fun laterLegsOfABasketAreJudgedOnTheAccountAfterTheFirst() {
        limits { it.copy(guardMaxLots = 2, guardNakedShort = true) }
        val buyWing = order(sym = "NIFTY26OCT24000PE", side = "BUY")
        val a1 = Guard.after(account(), buyWing)
        assertEquals(1, a1.holdings.size)
        // The short leg is covered by the wing just bought: not naked.
        assertEquals(emptyList<String>(), Guard.check(order(side = "SELL"), a1))
        // Without the wing it is.
        assertTrue(Guard.check(order(side = "SELL"), account()).any { it.startsWith("Naked short") })
        // Three lots of one instrument is over a limit of two.
        assertTrue(Guard.check(order(qty = 225), account()).any { it.startsWith("Exposure limit") })
    }

    @Test fun thePaperOrderAndAccountAreBuiltFromThePaperBook() {
        val c = Paper.Contract("NIFTY26OCT2624500PE", "NIFTY", expiry, 24_500.0, Right.PE, 75, "NSE_FO|12345")
        val o = Guard.paperOrder(c, "buy", 2, 101.5)
        assertEquals(AccountGuard.Order("NIFTY26OCT2624500PE", "BUY", 150, 75, 101.5, "NIFTY", expiry, "PE"), o)
        val snap = runBlocking { Paper.snapshot() }
        val a = Guard.paperAccount(snap)
        assertEquals(Paper.capital.toDouble(), a.capital, 0.01)
        assertEquals(a.capital, a.equity, 0.01)
        assertTrue(a.holdings.isEmpty())
        assertEquals(0, a.ordersToday)
        // The peak is kept and only rises; resetPeak starts it again.
        assertEquals(a.equity, SecurePrefs.getDouble("guard.peak.paper", -1.0), 0.01)
        SecurePrefs.put("guard.peak.paper", a.equity + 50_000)
        assertEquals(a.equity + 50_000, Guard.paperAccount(snap).peakEquity, 0.01)
        Guard.resetPeak(false)
        assertFalse(SecurePrefs.snapshot().containsKey("guard.peak.paper"))
        assertEquals(a.equity, Guard.paperAccount(snap).peakEquity, 0.01)
    }
}
