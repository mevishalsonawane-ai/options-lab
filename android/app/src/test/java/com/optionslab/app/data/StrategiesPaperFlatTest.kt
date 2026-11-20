package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.strategy.OptionType
import com.optionslab.engine.strategy.Position
import com.optionslab.engine.strategy.Product
import com.optionslab.engine.strategy.RunMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalTime

/**
 * 20 Nov 2026 (audit, B5 item 4 and review P2): a Strategy Lab PAPER run whose leg the paper book no longer holds (the 15:15 paper
 * square-off, Boss's close):
 *  - its exit only ever reduces what the paper book holds (exit = true): it never opens a reverse paper position;
 *  - the leg is reconciled as closed (at the sale that closed it), so the run finishes and no longer reads as running.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class StrategiesPaperFlatTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox

    @Before fun up() {
        upstox = FakeUpstox()                                // (clears Market.testClock: set it after)
        TradeFixtures.paperSettings()
        TradeFixtures.killSwitch(false)
        val day = AutomationSupport.tradingDayAt(11, 0).toLocalDate()
        Market.testClock = Clock.fixed(day.atTime(LocalTime.of(11, 0)).atZone(IST).toInstant(), IST)
        TradeFixtures.seed(context, upstox)
        AutomationSupport.security(compromised = false)
        Paper.forgetElsewhereForTest()
        Strategies.forgetFlatTriedForTest()
    }

    @After fun down() {
        Market.testClock = null
        Paper.forgetElsewhereForTest()
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** A paper run holding one bought NIFTY CE leg (MIS); the leg's paper symbol. */
    private fun running(): Pair<Long, String> = runBlocking {
        assertEquals(null, Strategies.save(AutomationSupport.strategy("Paper flat test", Position.B to OptionType.CE, product = Product.MIS)))
        val id = Strategies.all().single().def.id
        Strategies.start(id, RunMode.SANDBOX, "manual", confirmedByOwner = false, compromised = false)
        val e = Strategies.all().single()
        assertTrue("running: ${e.run?.status}", e.running)
        val leg = e.run!!.legs.values.single()
        assertEquals("the entry filled on paper", "complete", leg.entryStatus)
        val held = Paper.state.positions.filter { it.symbol == leg.symbol }.sumOf { it.quantity }
        assertEquals(75, held)
        id to leg.symbol
    }

    @Test fun aLegFlattenedOnPaperIsClosedAtThatSaleAndTheRunFinishesWithNoReversePosition() {
        val (_, sym) = running()
        // (Review C1: a restore is refused through the real hint while the run is open, and not once it finished.)
        assertTrue(Backup.liveHeld().toString(), Backup.HELD_STRATEGY in Backup.liveHeld())
        // The paper square-off (or Boss) closes the leg's option in the paper book.
        assertTrue(runBlocking { Paper.close(sym, "MIS") }.ok)
        val sale = Paper.state.trades.last { it.symbol == sym && it.action == "SELL" }
        val sells = Paper.state.trades.count { it.symbol == sym && it.action == "SELL" }
        runBlocking { Strategies.tickAll(compromised = false) }
        val e = runBlocking { Strategies.all() }.single()
        assertFalse("the run no longer reads as running: ${e.run?.status}", e.running)
        assertNotNull(e.run!!.stoppedAt)
        val leg = e.run!!.legs.values.single()
        assertEquals("closed", leg.status)
        assertEquals("booked at the sale that closed it", sale.price.toDouble(), leg.exitAvg!!, 1e-9)
        // Its exit was an exit only: no new paper sale, and never a short.
        assertEquals(sells, Paper.state.trades.count { it.symbol == sym && it.action == "SELL" })
        assertTrue(Paper.state.positions.none { it.symbol == sym && it.quantity < 0 })
        assertFalse(Strategies.runningHint)
        assertFalse(Backup.liveHeld().toString(), Backup.HELD_STRATEGY in Backup.liveHeld())
    }

    @Test fun aLegStillHeldOnPaperIsLeftRunning() {
        val (_, sym) = running()
        runBlocking { Strategies.tickAll(compromised = false) }
        assertTrue(runBlocking { Strategies.all() }.single().running)
        assertEquals(75, Paper.state.positions.filter { it.symbol == sym }.sumOf { it.quantity })
    }

    /** Review: another owner's opening trade that nets the leg to nothing is not a close - the leg is left to its own exits. */
    @Test fun aLegNettedToNothingByAnotherOwnersTradeIsNotClosed() {
        val (_, sym) = running()
        val c = Paper.contractOf(sym)!!
        assertTrue(runBlocking { Paper.place(c, "SELL", 1, "MARKET", "MIS", null, null) }.ok)   // someone else's short, not a close
        assertEquals(0, Paper.state.positions.filter { it.symbol == sym }.sumOf { it.quantity })
        runBlocking { Strategies.tickAll(compromised = false) }
        assertTrue("still running: nothing plainly closed it", runBlocking { Strategies.all() }.single().running)
    }

    /** Review: after a paper reset nothing is held at all - the leg is booked closed at its last price and the run finishes. */
    @Test fun afterAPaperResetTheLegIsClosedAndTheRunFinishes() {
        running()
        Paper.reset(java.math.BigDecimal("1000000"))
        runBlocking { Strategies.tickAll(compromised = false) }
        val e = runBlocking { Strategies.all() }.single()
        assertFalse("finished: ${e.run?.status}", e.running)
        assertTrue(e.run!!.legs.values.single().exitAvg!! > 0)
    }
}
