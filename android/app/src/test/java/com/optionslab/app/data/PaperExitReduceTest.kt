package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.KiteTicks
import com.optionslab.engine.Right
import com.optionslab.engine.sandbox.Quote
import com.optionslab.engine.sandbox.SandboxEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 22 Oct (arms audit): an arm's EXIT only ever reduces what the paper book holds. The Hero tickets' and ORB's 15:05 sales
 * raced the expiry square-off: the arm read "held", the square-off closed the option, and the arm's plain SELL then opened
 * a paper SHORT nothing managed. Now [Paper.place] with `exit = true` refuses an exit of a flat position as "already
 * closed", sells no more than is held, and [Paper.close] takes the position's resting stop out of the book before it
 * closes (a stop left resting after the close filled as a short). A short's resting BUY stop fills on a high between looks.
 */
// NRML throughout, as PaperFreshFillTest: the tests run at any hour, and an MIS position is squared off from 15:15.
@ConscryptMode(ConscryptMode.Mode.OFF)
class PaperExitReduceTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private lateinit var upstox: FakeUpstox
    private val token = 31_000_002L
    private val sym = "NIFTYTEST24400PE"
    private lateinit var c: Paper.Contract

    @Before fun up() {
        TradeFixtures.paperSettings()
        upstox = FakeUpstox()
        TradeFixtures.seed(context, upstox)
        kite = FakeKite()
        kite.keepAlive = true
        kite.login(live = false)
        kite.instruments += FakeKite.Ins(token, sym, "NIFTY", TradeFixtures.nearExpiry, 24_400.0, "PE", 75)
        runBlocking { Broker.instruments() }
        c = Paper.contractFor("NIFTY", TradeFixtures.nearExpiry, 24_400.0, Right.PE) ?: throw AssertionError("contract not listed")
        Paper.testSkipFeedChecks = true
        KiteStream.resetForTest()
    }

    @After fun down() {
        Paper.testSkipFeedChecks = false
        KiteStream.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun held() = Paper.state.positions.filter { it.symbol == sym }.sumOf { it.quantity }

    private fun buy(lots: Int = 1): Paper.Result = runBlocking { Paper.place(c, "BUY", lots, "MARKET", "NRML", null, null, Quote(100.0, bid = 99.95, ask = 100.05)) }

    private fun exitSell(lots: Int = 1): Paper.Result =
        runBlocking { Paper.place(c, "SELL", lots, "MARKET", "NRML", null, null, Quote(100.0, bid = 99.95, ask = 100.05), exit = true) }

    @Test fun anExitSellsWhatIsHeldAndTheBookIsFlat() {
        assertTrue(buy().ok)
        assertEquals(75, held())
        val r = exitSell()
        assertTrue(r.message, r.ok)
        assertEquals(75, r.events.filterIsInstance<SandboxEvent.Fill>().single().quantity)
        assertEquals(0, held())
    }

    /** The square-off (or Boss) closed it first: the arm's exit is refused as already closed, and nothing goes short. */
    @Test fun anExitOfAFlatPositionIsRefusedNeverAShort() {
        assertTrue(buy().ok)
        assertTrue(runBlocking { Paper.close(sym, "NRML") }.ok)
        assertEquals(0, held())
        val orders = Paper.state.orders.size
        val r = exitSell()
        assertFalse(r.ok)
        assertTrue(r.message, "already closed" in r.message)
        assertEquals("no order placed", orders, Paper.state.orders.size)
        assertEquals("flat, never short", 0, held())
    }

    /** An exit for more than is held (a stale record) sells only what is held. */
    @Test fun anExitForMoreThanHeldSellsOnlyWhatIsHeld() {
        assertTrue(buy(1).ok)
        val r = exitSell(lots = 3)
        assertTrue(r.message, r.ok)
        assertEquals(75, r.events.filterIsInstance<SandboxEvent.Fill>().single().quantity)
        assertEquals(0, held())
    }

    /** A BUY marked as an exit on a long is no exit: refused, the long untouched. */
    @Test fun anExitOnTheWrongSideIsRefused() {
        assertTrue(buy().ok)
        val r = runBlocking { Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, Quote(100.0), exit = true) }
        assertFalse(r.ok)
        assertEquals(75, held())
    }

    /** Boss's own orders are not exits: a plain SELL on a flat book still opens a (paper) short, as before. */
    @Test fun aPlainSellStaysAnEntryForBossesOwnOrders() {
        val r = runBlocking { Paper.place(c, "SELL", 1, "MARKET", "NRML", null, null, Quote(100.0, bid = 99.95, ask = 100.05)) }
        assertTrue(r.message, r.ok)
        assertEquals(-75, held())
    }

    /** The pure rule behind it. */
    @Test fun theReduceRuleIsPure() {
        assertTrue(buy(2).ok)
        assertEquals(150, Paper.reduceQtyNow(sym, "NRML", "SELL", 300))
        assertEquals(75, Paper.reduceQtyNow(sym, "NRML", "SELL", 75))
        assertNull(Paper.reduceQtyNow(sym, "NRML", "BUY", 75))
        assertNull(Paper.reduceQtyNow(sym, "MIS", "SELL", 75))
        assertNull(Paper.reduceQtyNow("NOTHELD", "NRML", "SELL", 75))
    }

    /** [Paper.close] takes the position's resting stop (and a resting target) out first: nothing is left to fill as a short. */
    @Test fun aCloseCancelsTheRestingStopAndTargetFirst() {
        assertTrue(buy().ok)
        val stop = runBlocking { Paper.place(c, "SELL", 1, "SL-M", "NRML", null, 85.0, Quote(100.0)) }
        val target = runBlocking { Paper.place(c, "SELL", 1, "LIMIT", "NRML", 130.0, null, Quote(100.0)) }
        assertTrue(stop.ok && target.ok)
        assertEquals(2, Paper.state.orders.count { it.symbol == sym && (it.status == "trigger pending" || it.status == "open") })
        val r = runBlocking { Paper.close(sym, "NRML") }
        assertTrue(r.message, r.ok)
        assertEquals(0, held())
        assertEquals("no resting exit left", 0, Paper.state.orders.count { it.symbol == sym && (it.status == "trigger pending" || it.status == "open") })
        assertEquals("cancelled", Paper.state.orders.single { it.orderId == stop.orderId }.status)
        assertEquals("position_closed", Paper.cancelReason(stop.orderId!!))
        // A stop fill after the close finds nothing to fill: still flat.
        runBlocking { Paper.tick() }
        assertEquals(0, held())
    }

    /** A short's stop is a resting BUY SL-M: it fills on a high touched between two looks, at the trigger. */
    @Test fun aShortsRestingBuyStopFillsOnAHighBetweenLooks() {
        val short = runBlocking { Paper.place(c, "SELL", 1, "MARKET", "NRML", null, null, Quote(100.0, bid = 99.95, ask = 100.05)) }
        assertTrue(short.message, short.ok)
        assertEquals(-75, held())
        val stop = runBlocking { Paper.place(c, "BUY", 1, "SL-M", "NRML", null, 110.0, Quote(100.0), exit = true) }
        assertTrue(stop.message, stop.ok)
        assertEquals("trigger pending", Paper.state.orders.single { it.orderId == stop.orderId }.status)
        // After the stop rested (the stream keeps its prices in one-second buckets, so the spike is in the NEXT second),
        // the stream traded through 110 and came back under it before this look.
        Thread.sleep(1_010 - System.currentTimeMillis() % 1_000)
        val spike = System.currentTimeMillis()
        KiteStream.feedForTest(listOf(KiteTicks.Tick(token, 112.0, bid = 111.95, ask = 112.05, exchangeTime = spike / 1000)), spike)
        KiteStream.feedForTest(listOf(KiteTicks.Tick(token, 104.0, bid = 103.95, ask = 104.05, exchangeTime = (spike + 2) / 1000)), spike + 2)
        runBlocking { Paper.tick() }
        val o = Paper.state.orders.single { it.orderId == stop.orderId }
        assertEquals("complete", o.status)
        assertTrue("at or over the trigger, not the price now: ${o.averagePrice}", (o.averagePrice?.toDouble() ?: 0.0) >= 110.0)
        assertEquals("flat", 0, held())
    }
}
