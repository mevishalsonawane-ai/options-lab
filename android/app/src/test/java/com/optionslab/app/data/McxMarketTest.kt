package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.mcx.McxSession
import com.optionslab.engine.sandbox.Quote
import com.optionslab.engine.sandbox.SandboxEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** MCX on the phone (9 Oct): its calendar, paper fills on MCX's clock, spread and charges, and the expiry refusals. */
class McxMarketTest : RobolectricTest() {
    private fun at(day: String, time: String) {
        Market.testClock = java.time.Clock.fixed(LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(com.optionslab.engine.IST).toInstant(),
            com.optionslab.engine.IST)
    }

    @After fun clock() { Market.testClock = null }

    private val option = McxContract(148600327L, 580470L, "CRUDEOIL26OCT8550CE", "CRUDEOIL", LocalDate.of(2026, 10, 15), 8550.0, Right.CE, 0.1, 100)
    private val future = McxContract(111L, 570001L, "GOLDM26NOVFUT", "GOLDM", LocalDate.of(2026, 11, 5), 0.0, null, 1.0, 10)

    @Test fun mcxsOwnHoursAndTheHolidayListKept() {
        // A Thursday evening: NSE is shut, MCX trades to 23:30.
        at("2026-10-08", "20:00")
        assertTrue(McxMarket.isOpen())
        assertFalse(Market.isOpen())
        assertEquals(23 * 60, McxMarket.entryCutoffMinute())
        at("2026-10-08", "23:31")
        assertFalse(McxMarket.isOpen())
        // Dussehra from the built-in list: shut in the morning, open from 17:00.
        at("2026-10-20", "11:00")
        assertFalse(McxMarket.isOpen())
        at("2026-10-20", "18:00")
        assertTrue(McxMarket.isOpen())
        // A fetched list replaces the built-in one for its year.
        McxMarket.keepHolidays(mapOf(LocalDate.of(2026, 10, 20) to null))
        assertFalse(McxMarket.isOpen())
        assertNull(McxMarket.todayWindow())
        McxMarket.keepHolidays(emptyMap())
        assertNull("an empty read keeps the list", McxMarket.todayWindow())
        assertEquals(McxSession.Window(LocalTime.of(9, 0), LocalTime.of(23, 30)), McxMarket.calendar().window(LocalDate.of(2026, 10, 21)))
    }

    @Test fun aPaperMcxOptionFillsWithMcxsSpreadAndChargesAndKeepsTheWatchUp() = runBlocking {
        at("2026-10-08", "20:00")
        assertFalse(McxMarket.exposure())
        val c = McxMarket.paperContract(option)
        assertEquals("CRUDEOIL15OCT268550CE", c.symbol)
        assertEquals("MCX", c.exchange)
        assertTrue(c.isMcx)
        assertEquals(100, c.lotSize)
        val r = Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, Quote(242.0))
        assertTrue(r.message, r.ok)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().single()
        // 242 x 1.003 (crude options after 17:00) = 242.726, up to 242.80 on the 0.10 tick; 100 units (1 lot).
        assertEquals(242.80, fill.price, 1e-9)
        assertEquals(100, fill.quantity)
        val trade = Paper.state.trades.single()
        assertEquals(com.optionslab.engine.mcx.McxCosts.charge("BUY", 242.8 * 100, true).toDouble(), trade.charges.toDouble(), 0.011)
        assertEquals("MCX", Paper.state.positions.single().exchange)
        assertTrue(McxMarket.paperExposure())
        assertTrue(McxMarket.watchDue())
        // Remembered with its exchange: read back after a restart of the book.
        assertEquals("MCX", Paper.contractOf(c.symbol)?.exchange)
    }

    @Test fun aPaperMcxFutureBlocksZerodhasMarginPerLot() = runBlocking {
        at("2026-10-08", "11:00")
        val c = McxMarket.paperContract(future)
        assertEquals(Right.IX, c.right)
        val before = Paper.state.funds.availableBalance
        val r = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, Quote(147_897.0))
        assertTrue(r.message, r.ok)
        // GOLDM's margin per lot from the table (Zerodha's list is not read in a test): Rs 1,36,807, MIS = NRML on MCX.
        assertEquals(136_807.0, Paper.state.positions.single().marginBlocked.toDouble(), 0.01)
        assertTrue(Paper.state.funds.availableBalance < before)
    }

    @Test fun newOptionBuysStopTheDayBeforeExpiryAfter1500() {
        at("2026-10-14", "14:00")
        assertNull(McxGuard.entryRefusal(option, buy = true))
        at("2026-10-14", "15:30")
        assertNotNull(McxGuard.entryRefusal(option, buy = true))
        assertNull("a sell is not refused here", McxGuard.entryRefusal(option, buy = false))
        at("2026-10-15", "10:00")
        assertTrue(McxGuard.entryRefusal(McxMarket.paperContract(option), buy = true)!!.contains("expiry day"))
        // GOLDM's November future: no new one from Tue 3 Nov (2 trading days before its 5 Nov expiry).
        at("2026-11-03", "10:00")
        assertNotNull(McxGuard.entryRefusal(future, buy = true))
        // An NFO contract is never refused by MCX's rules.
        assertNull(McxGuard.entryRefusal(Paper.Contract("NIFTY20OCT2625000CE", "NIFTY", LocalDate.of(2026, 10, 20), 25_000.0, Right.CE, 75, "k"), buy = true))
    }
}
