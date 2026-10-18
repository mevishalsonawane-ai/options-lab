package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 18 Oct, review: a followed trade whose bars can never be read (a live trade whose contract is not in the paper book) is let
 * go once it is closed and its square-off is 30 minutes past - it used to stay followed, and its record kept, for good. An
 * open one stays followed.
 */
class TradeManagerNoBarsTest : RobolectricTest() {
    private val t0 = 1_760_000_000_000L
    private val squareOff = t0 + 3 * 3_600_000L

    private fun trade(id: String) = TradeManager.ManagedTrade("pine:1", id, "Pine · Level", "NIFTY", 1, "NIFTY25OCT25000CE",
        100.0, 75, 70.0, 160.0, TradeManager.Caps(squareOff), TradeManager.Account.PAPER, t0, 1.0)

    @Before fun up() {
        SmartWorkers.resetForTest()
        TradeManagerHost.resetForTest()
        TradeManagerHost.testNoWake = true
        TradeManagerHost.testBars = null
    }

    @After fun down() { TradeManagerHost.resetForTest() }

    private fun follow(id: String) =
        assertTrue("followed", TradeManagerHost.attach(TradeManagerHost.Registration(trade(id), premium = { 120.0 }, bars = { null })) != TradeManager.Mode.OFF)

    @Test fun aClosedTradeWithNoBarsIsLetGoAfterItsSquareOff() {
        follow("pine:1:closed")
        TradeManagerHost.closed("pine:1:closed", 130.0, "target", t0 + 60_000L)
        assertEquals(120.0, TradeManagerHost.premiumForTest("pine:1:closed")!!, 1e-9)
        // Before the square-off + 30 min: still followed (its bars may come).
        runBlocking { TradeManagerHost.settleNow(squareOff + 29 * 60_000L) }
        assertEquals(120.0, TradeManagerHost.premiumForTest("pine:1:closed")!!, 1e-9)
        // After it: let go; its record is kept (closed, with its result).
        runBlocking { TradeManagerHost.settleNow(squareOff + 31 * 60_000L) }
        assertNull(TradeManagerHost.premiumForTest("pine:1:closed"))
        val r = TradeManagerHost.records.value.single { it.trade.tradeId == "pine:1:closed" }
        assertTrue(r.closed)
    }

    @Test fun anOpenTradeWithNoBarsStaysFollowed() {
        follow("pine:1:open")
        runBlocking { TradeManagerHost.settleNow(squareOff + 31 * 60_000L) }
        assertEquals(120.0, TradeManagerHost.premiumForTest("pine:1:open")!!, 1e-9)
    }
}
