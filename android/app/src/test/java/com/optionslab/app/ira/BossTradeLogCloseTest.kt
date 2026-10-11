package com.optionslab.app.ira

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.BossTrades
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * A live hand exit's net (review G6-4): recorded right after the fill, before the live trade book holds its round trip, it
 * stays pending - never 0 and never an earlier trip's on the same symbol - and is kept with its closing order's id so the
 * next account load fills it in ([BossTradeLog.settleSoon]).
 */
class BossTradeLogCloseTest : RobolectricTest() {
    @Before fun up() {
        assumeTrue("Boss's own trades are learned in IraAlgo with Jarvis only", BossTradeLog.enabled())
        BossTradeLog.init(context)
        BossTradeLog.resetForTest()
    }

    @After fun down() { BossTradeLog.resetForTest() }

    @Test fun aLiveExitWaitsForItsRoundTripInsteadOfAGuessedNet() {
        val open = BossTrades.Trade("kite:1", "NIFTY26OCT25000CE", true, 1_760_000_000_000L, BossTrades.Why.LEVEL)
        BossTradeLog.setForTest(listOf(open))
        BossTradeLog.filled("kite:2", "NIFTY26OCT25000CE", buy = false, live = true, closing = true)
        BossTradeLog.drain()
        com.optionslab.app.testing.Background.await("the exit recorded") { BossTradeLog.pending() == 0 }
        val t = BossTradeLog.all().single()
        assertNull("no net guessed before the trade book holds this exit", t.net)
        assertTrue(t.pending)
        assertFalse(t.closed)
        assertEquals("kite:2", t.closeId)
        assertTrue("a pending exit is in no rule", BossTradeLog.rules().isEmpty())
        // A second sell of the symbol does not close the same trade again.
        BossTradeLog.filled("kite:3", "NIFTY26OCT25000CE", buy = false, live = true)
        BossTradeLog.drain()
        com.optionslab.app.testing.Background.await("the second fill recorded") { BossTradeLog.pending() == 0 }
        assertEquals("kite:2", BossTradeLog.all().single { it.id == "kite:1" }.closeId)
    }
}
