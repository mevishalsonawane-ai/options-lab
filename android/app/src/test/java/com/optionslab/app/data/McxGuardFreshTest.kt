package com.optionslab.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 18 Oct, review: the MCX expiry exit is found on the pass's shared positions read (up to 20 s old, words only) and then
 * sized from a fresh read ([McxGuard.freshDue]): a resting stop that filled since never turns the exit into a new position.
 */
class McxGuardFreshTest {
    private fun pos(symbol: String, qty: Int, product: String = "NRML", exchange: String = "MCX") =
        Broker.Position(symbol, product, qty, 100.0, 100.0, 0.0, exchange = exchange)

    private val opt = "CRUDEOIL26OCT8550CE"
    private val fut = "GOLDM26NOVFUT"

    @Test fun aPositionFlatOnTheFreshReadIsNotClosedAgain() {
        // The stale read showed a short of 1 lot; its resting SL filled at MCX since: flat now. Nothing is sent.
        val due = listOf(Triple(pos(opt, -1), "c", "due"))
        assertTrue(McxGuard.freshDue(due, listOf(pos(opt, 0))).isEmpty())
        assertTrue("gone from the book entirely", McxGuard.freshDue(due, emptyList()).isEmpty())
    }

    @Test fun theFreshQuantityAndSideAreWhatIsClosed() {
        val due = listOf(Triple(pos(opt, -2), "c", "due"))
        assertEquals(-1, McxGuard.freshDue(due, listOf(pos(opt, -1))).single().first.qty)
        // Turned long since (bought back and more, from Kite web): the fresh long is what is sold.
        assertEquals(1, McxGuard.freshDue(due, listOf(pos(opt, 1))).single().first.qty)
    }

    @Test fun onlyTheSameSymbolProductAndExchangeCount() {
        val due = listOf(Triple(pos(opt, 1), "c", "due"))
        assertTrue("another product is another position", McxGuard.freshDue(due, listOf(pos(opt, 1, product = "MIS"))).isEmpty())
        assertTrue("another exchange", McxGuard.freshDue(due, listOf(pos(opt, 1, exchange = "NFO"))).isEmpty())
        assertTrue(McxGuard.freshDue(emptyList<Triple<Broker.Position, String, String>>(), listOf(pos(opt, 1))).isEmpty())
    }

    @Test fun shortsStillGoFirstAndTheContractAndReasonAreKept() {
        val due = listOf(Triple(pos(fut, 1), "gold", "5 days"), Triple(pos(opt, -1), "crude", "23:00"))
        val out = McxGuard.freshDue(due, listOf(pos(fut, 1), pos(opt, -1)))
        assertEquals(listOf(opt, fut), out.map { it.first.symbol })
        assertEquals(listOf("crude", "gold"), out.map { it.second })
        assertEquals(listOf("23:00", "5 days"), out.map { it.third })
    }
}
