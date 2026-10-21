package com.optionslab.ira

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 21 Oct, enhancement round 3 (idea 1): the positions-lag guard ([ExitDesk.positionsLag], [ExitDesk.settleHeld]). */
class PositionsLagTest {
    private fun sell(id: String, filled: Int) = ExitDesk.Fill(id, "SELL", filled)
    private fun buy(id: String, filled: Int) = ExitDesk.Fill(id, "BUY", filled)

    @Test fun aLongWhosePositionsTrailTheExitsFillLagsByThatFill() {
        // Bought 150 today, our exit sold 150 (COMPLETE in the orders), the positions still show 150 held and no sell yet.
        assertEquals(150, ExitDesk.positionsLag(150, dayExitQty = 0, fills = listOf(buy("e", 150), sell("x", 150)), mine = setOf("x")))
        assertEquals(0, ExitDesk.heldLess(150, 150))
    }

    @Test fun positionsThatShowTheFillDoNotLag() {
        assertEquals(0, ExitDesk.positionsLag(75, dayExitQty = 75, fills = listOf(sell("x", 75)), mine = setOf("x")))
        // More sold by the positions than the orders show (a sale since the orders read): never negative.
        assertEquals(0, ExitDesk.positionsLag(75, dayExitQty = 150, fills = listOf(sell("x", 75)), mine = setOf("x")))
    }

    @Test fun onlyThisPhonesOwnRecentExitsWakeTheGuard() {
        // A sell the phone did not send just now (Boss's own on the web, a stop resting since the morning): not judged here.
        assertEquals(0, ExitDesk.positionsLag(150, dayExitQty = 0, fills = listOf(sell("web", 75)), mine = setOf("x")))
        assertEquals(0, ExitDesk.positionsLag(150, dayExitQty = 0, fills = listOf(sell("web", 75)), mine = emptySet()))
        // Ours not filled yet (still working): nothing to trail.
        assertEquals(0, ExitDesk.positionsLag(150, dayExitQty = 0, fills = listOf(sell("x", 0)), mine = setOf("x")))
    }

    @Test fun aPartFillLagsByWhatFilled() {
        assertEquals(75, ExitDesk.positionsLag(150, dayExitQty = 0, fills = listOf(sell("x", 75)), mine = setOf("x")))
        assertEquals(75, ExitDesk.heldLess(150, 75))
    }

    @Test fun twoSellsTodayOneShownCountsOnlyTheOtherAsTrailing() {
        // A morning sell of 75 already in the positions, ours of 75 now: only ours trails.
        assertEquals(75, ExitDesk.positionsLag(75, dayExitQty = 75, fills = listOf(sell("am", 75), sell("x", 75)), mine = setOf("x")))
    }

    @Test fun aShortLooksAtTheDaysBuys() {
        assertEquals(50, ExitDesk.positionsLag(-50, dayExitQty = 0, fills = listOf(buy("x", 50), sell("e", 50)), mine = setOf("x")))
        assertEquals(0, ExitDesk.heldLess(-50, 50))
        assertEquals(-25, ExitDesk.heldLess(-50, 25))
    }

    @Test fun neverMoreThanIsHeldAndUnknownDayQuantitiesAreNeverGuessed() {
        assertEquals(75, ExitDesk.positionsLag(75, dayExitQty = 0, fills = listOf(sell("x", 300)), mine = setOf("x")))
        assertEquals(0, ExitDesk.positionsLag(75, dayExitQty = null, fills = listOf(sell("x", 75)), mine = setOf("x")))
        assertEquals(0, ExitDesk.positionsLag(0, dayExitQty = 0, fills = listOf(sell("x", 75)), mine = setOf("x")))
        assertEquals(0, ExitDesk.heldLess(0, 10))
        assertEquals(0, ExitDesk.heldLess(75, 500), "never turned round")
    }

    @Test fun inShadowItReadsOnceAndOnlyNotes() = runBlocking {
        var reads = 0
        val notes = ArrayList<Pair<ExitDesk.Lag, Int>>()
        val (r, left) = ExitDesk.settleHeld(false, { ++reads }, { 75 }, longArrayOf(1, 1)) { l, n -> notes += l to n }
        assertEquals(1, r); assertEquals(1, reads); assertEquals(0, left, "shadow never changes what is sent")
        assertEquals(listOf(ExitDesk.Lag.SHADOW to 75), notes)
    }

    @Test fun onItReadsAgainUntilThePositionsShowTheFill() = runBlocking {
        var reads = 0
        val notes = ArrayList<ExitDesk.Lag>()
        val (r, left) = ExitDesk.settleHeld(true, { ++reads }, { n -> if (n < 3) 75 else 0 }, longArrayOf(1, 1, 1)) { l, _ -> notes += l }
        assertEquals(3, r); assertEquals(0, left)
        assertEquals(listOf(ExitDesk.Lag.READ_AGAIN), notes)
    }

    @Test fun onAndStillTrailingTheFillCountsAsSold() = runBlocking {
        var reads = 0
        val notes = ArrayList<Pair<ExitDesk.Lag, Int>>()
        val (r, left) = ExitDesk.settleHeld(true, { ++reads }, { 75 }, longArrayOf(1, 1, 1)) { l, n -> notes += l to n }
        assertEquals(4, reads, "the first read and three more")
        assertEquals(4, r); assertEquals(75, left)
        assertEquals(listOf(ExitDesk.Lag.SUBTRACTED to 75), notes)
    }

    @Test fun theReadsAgainStopAtTheBudgetSoTheTurnIsNeverHeldLong() = runBlocking {
        var now = 0L
        var reads = 0
        // Each read takes 700 ms of the clock: after the first wait (300) and its read the next wait would pass the 1.2 s budget.
        val (_, left) = ExitDesk.settleHeld(true, { reads++; now += 700; reads }, { 75 }, longArrayOf(300, 600, 900),
            budgetMs = 1_200, clock = { now }) { _, _ -> }
        assertEquals(2, reads)
        assertEquals(75, left, "still trailing at the budget: counted as sold")
        assertTrue(ExitDesk.LAG_BUDGET_MS + ExitDesk.REREAD_MS.sum() < ExitTurns.WAIT_MS, "both reads-again fit inside one turn's wait")
    }

    @Test fun aReadThatDoesNotTrailIsTakenAtOnce() = runBlocking {
        var reads = 0
        val notes = ArrayList<ExitDesk.Lag>()
        val (_, left) = ExitDesk.settleHeld(true, { ++reads }, { 0 }) { l, _ -> notes += l }
        assertEquals(1, reads); assertEquals(0, left); assertEquals(listOf(ExitDesk.Lag.NONE), notes)
    }

    @Test fun theWholeReadAgainStaysUnderTwoSeconds() {
        assertTrue(ExitDesk.REREAD_MS.sum() < 2_000)
    }

    @Test fun theRegistryGivesTheRecentExitsIdsOnly() {
        var now = 1_000_000L
        val reg = ExitRegistry(clock = { now })
        val k = ExitDesk.key("NFO", "X")
        val t = reg.sending(ExitRegistry.Kind.ORDER, k, "SELL", 75, "MARKET", "arm")
        assertTrue(reg.recentIds(ExitRegistry.Kind.ORDER, k).isEmpty(), "no id until Zerodha answers")
        reg.answered(t, "9")
        assertEquals(setOf("9"), reg.recentIds(ExitRegistry.Kind.ORDER, k))
        assertTrue(reg.recentIds(ExitRegistry.Kind.GTT, k).isEmpty())
        assertTrue(reg.recentIds(ExitRegistry.Kind.ORDER, ExitDesk.key("NFO", "Y")).isEmpty())
        // Shown by a read, it is still recent (a later sender's positions read may trail it).
        reg.seen(ExitRegistry.Kind.ORDER, listOf(ExitRegistry.Row("9", k, "SELL", 75, "MARKET")))
        assertEquals(setOf("9"), reg.recentIds(ExitRegistry.Kind.ORDER, k))
        now += ExitDesk.KEEP_MS + 1
        assertTrue(reg.recentIds(ExitRegistry.Kind.ORDER, k).isEmpty(), "let go after a minute")
    }

    @Test fun theLagLineSaysShadowAndOnPlainly() {
        val c = ExitDesk.Counts("2026-10-21", lagShadow = 2, lagReadAgain = 3, lagSubtracted = 1)
        assertTrue(c.lagLine(false).contains("shadow (logged only)") && c.lagLine(false).contains("fill 2"), c.lagLine(false))
        assertTrue(c.lagLine(true).contains("showed 3") && c.lagLine(true).contains("counted as sold 1"), c.lagLine(true))
    }
}
