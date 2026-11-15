package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 21 Oct, enhancement round 4 (idea 2): the positions-lag guard for a RESTING exit's fill (a stop or target sent hours ago that
 * filled a moment ago), judged by the order's exchange update time ([ExitDesk.filledRecently], [ExitDesk.positionsLag] with now).
 */
class PositionsLagRestingTest {
    private val now = 1_800_000_000_000L
    private fun sell(id: String, filled: Int, agoMs: Long?) = ExitDesk.Fill(id, "SELL", filled, agoMs?.let { now - it })
    private fun buy(id: String, filled: Int, agoMs: Long?) = ExitDesk.Fill(id, "BUY", filled, agoMs?.let { now - it })

    @Test fun aStopRestingSinceTheMorningThatFilledAMomentAgoLags() {
        // Bought 150 at 09:20, the stop rested all day and filled 3 s ago; the positions still show 150 and no sell.
        assertEquals(150, ExitDesk.positionsLag(150, 0, listOf(buy("e", 150, 6 * 3_600_000L), sell("stop", 150, 3_000)), emptySet(), now))
    }

    @Test fun withoutNowTheOldRuleStands() {
        // Round 3's callers that pass no clock: only this phone's own recent exits wake it, as before.
        assertEquals(0, ExitDesk.positionsLag(150, 0, listOf(sell("stop", 150, 3_000)), emptySet()))
    }

    @Test fun aFillOlderThanTheWindowIsNotJudged() {
        val old = ExitDesk.RECENT_FILL_MS + ExitRegistry.SKEW_MS + 1
        assertEquals(0, ExitDesk.positionsLag(150, 0, listOf(sell("stop", 150, old)), emptySet(), now))
        // At the edge it still counts.
        assertEquals(150, ExitDesk.positionsLag(150, 0, listOf(sell("stop", 150, old - 1)), emptySet(), now))
    }

    @Test fun noUpdateTimeIsNeverGuessed() {
        assertEquals(0, ExitDesk.positionsLag(150, 0, listOf(sell("stop", 150, null)), emptySet(), now))
        assertFalse(ExitDesk.filledRecently(sell("stop", 150, null), now))
    }

    @Test fun anUnfilledOrderJustChangedIsNotAFill() {
        // A stop modified (trailed) a moment ago, nothing filled: nothing trails.
        assertFalse(ExitDesk.filledRecently(sell("stop", 0, 1_000), now))
        assertEquals(0, ExitDesk.positionsLag(150, 0, listOf(sell("stop", 0, 1_000)), emptySet(), now))
    }

    @Test fun theExchangeClockAheadOfThePhoneStillCounts() {
        assertTrue(ExitDesk.filledRecently(sell("stop", 75, -4_000), now))
        assertEquals(75, ExitDesk.positionsLag(150, 0, listOf(sell("stop", 75, -4_000)), emptySet(), now))
    }

    @Test fun positionsThatAlreadyShowTheRestingFillDoNotLag() {
        assertEquals(0, ExitDesk.positionsLag(75, 75, listOf(sell("stop", 75, 2_000)), emptySet(), now))
    }

    @Test fun aRecentFillOnTheEntrySideNeverWakesIt() {
        // A long's buy that filled a moment ago is not an exit: the old sell (hours ago) is judged as before (not recent).
        assertEquals(0, ExitDesk.positionsLag(150, 0, listOf(buy("e", 150, 1_000), sell("am", 75, 5 * 3_600_000L)), emptySet(), now))
    }

    @Test fun aShortsRestingBuyStopCountsTheSameWay() {
        assertEquals(0, ExitDesk.positionsLag(-75, 75, listOf(buy("stop", 75, 2_000)), emptySet(), now))
        assertEquals(75, ExitDesk.positionsLag(-75, 0, listOf(buy("stop", 75, 2_000)), emptySet(), now))
        assertEquals(0, ExitDesk.heldLess(-75, 75))
    }

    @Test fun neverMoreThanHeldAndNothingWithUnknownDayQuantities() {
        assertEquals(75, ExitDesk.positionsLag(75, 0, listOf(sell("stop", 150, 2_000)), emptySet(), now))
        assertEquals(0, ExitDesk.positionsLag(75, null, listOf(sell("stop", 75, 2_000)), emptySet(), now))
        assertEquals(0, ExitDesk.positionsLag(0, 0, listOf(sell("stop", 75, 2_000)), emptySet(), now))
    }

    @Test fun aMorningSellShownAndARestingStopJustFilledCountOnlyTheStop() {
        assertEquals(75, ExitDesk.positionsLag(75, 75, listOf(sell("am", 75, 4 * 3_600_000L), sell("stop", 75, 1_500)), emptySet(), now))
    }

    /**
     * 15 Nov, review (R1): [anyAge] - a stop put back after Boss's exit, minutes on: every exit fill of today counts against Kite's
     * own day quantity however old it is, falls to 0 once the positions show it, and never turns the position round.
     */
    @Test fun anyAgeCountsEveryFillOfTodayAgainstTheDayQuantity() {
        val old = 10 * 60_000L
        val boss = listOf(sell("boss", 150, old))
        assertEquals(0, ExitDesk.positionsLag(150, 0, boss, emptySet(), now), "not recent, not this phone's: not judged")
        assertEquals(150, ExitDesk.positionsLag(150, 0, boss, emptySet(), now, anyAge = true), "any age: the positions still trail all of it")
        assertEquals(0, ExitDesk.positionsLag(150, 150, boss, emptySet(), now, anyAge = true), "shown in the positions: nothing trails")
        assertEquals(75, ExitDesk.positionsLag(150, 75, boss, emptySet(), now, anyAge = true), "part shown")
        assertEquals(0, ExitDesk.positionsLag(150, null, boss, emptySet(), now, anyAge = true), "no day quantity: never guessed")
        assertEquals(75, ExitDesk.positionsLag(75, 0, boss, emptySet(), now, anyAge = true), "at most what is held")
        assertEquals(50, ExitDesk.positionsLag(-50, 0, listOf(buy("b", 50, old), sell("s", 50, old)), emptySet(), now, anyAge = true),
            "a short's exits are buys")
        assertEquals(0, ExitDesk.positionsLagAcross(150, 0, boss, emptySet(), now, 150, boss, anyAge = true), "across products: the smaller")
    }
}
