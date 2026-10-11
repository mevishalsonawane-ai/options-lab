package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The learning freeze ([FreezeRule]): the threshold, the window, paper and live apart, the unfreeze and its PIN. */
class FreezeRuleTest {
    private val fri: LocalDate = LocalDate.of(2026, 10, 16)

    @Test fun theThresholdIsTwiceTheAverageLosingDayAtLeastThreeThousand() {
        assertEquals(3_000.0, FreezeRule.threshold(emptyMap(), fri))
        val small = (1..30).associate { fri.minusDays(it.toLong()) to if (it % 2 == 0) -500.0 else 800.0 }
        assertEquals(3_000.0, FreezeRule.threshold(small, fri), "2 x 500 is under the floor")
        val big = (1..30).associate { fri.minusDays(it.toLong()) to if (it % 2 == 0) -2_500.0 else 4_000.0 }
        assertEquals(5_000.0, FreezeRule.threshold(big, fri))
        val old = mapOf(fri.minusDays(90) to -50_000.0)
        assertEquals(3_000.0, FreezeRule.threshold(old, fri), "older than 60 days is not counted")
    }

    @Test fun aFallSinceTheFirstChangeOfTheLastFiveTradingDaysFreezes() {
        assertEquals(listOf(12, 13, 14, 15, 16), FreezeRule.lastTradingDays(fri).map { it.dayOfMonth })
        val daily = mapOf(fri.minusDays(3) to -2_000.0, fri.minusDays(1) to -1_500.0, fri to -200.0)
        // The first change was on Tuesday (the 13th): the fall since then is 3,700 > 3,000.
        val v = FreezeRule.check(FreezeRule.Account.PAPER, daily, listOf(fri.minusDays(3), fri), fri)
        assertTrue(v.frozen, v.words)
        assertEquals(fri.minusDays(3), v.from)
        assertEquals(3_700.0, v.fall, 1e-9)
        // No change in the window: nothing to freeze, whatever the P&L.
        assertFalse(FreezeRule.check(FreezeRule.Account.PAPER, daily, listOf(fri.minusDays(10)), fri).frozen)
        // Boss's own threshold wins.
        assertFalse(FreezeRule.check(FreezeRule.Account.PAPER, daily, listOf(fri.minusDays(3)), fri, override = 5_000.0).frozen)
        // A gain is no fall.
        assertFalse(FreezeRule.check(FreezeRule.Account.LIVE, mapOf(fri to 10_000.0), listOf(fri), fri).frozen)
    }

    @Test fun itStaysFrozenUntilBossUnfreezesAndThenCountsOnlyNewDays() {
        val daily = mapOf(fri.minusDays(1) to -4_000.0)
        // One loss of 4,000 alone sets the default line at 8,000 (twice the average losing day): Boss's own line is 3,000 here.
        assertEquals(8_000.0, FreezeRule.threshold(daily, fri))
        val v = FreezeRule.check(FreezeRule.Account.LIVE, daily, listOf(fri.minusDays(1)), fri, override = 3_000.0)
        var s = FreezeRule.next(FreezeRule.State(), listOf(v, FreezeRule.check(FreezeRule.Account.PAPER, emptyMap(), emptyList(), fri)), fri)
        assertTrue(s.frozen); assertEquals(setOf(FreezeRule.Account.LIVE), s.accounts)
        // Better the next day: still frozen (only Boss unfreezes).
        s = FreezeRule.next(s, emptyList(), fri.plusDays(3))
        assertTrue(s.frozen)
        // Live on: the PIN is needed.
        assertNull(FreezeRule.unfreeze(s, fri.plusDays(3), liveOn = true, pinConfirmed = false))
        val un = assertNotNull(FreezeRule.unfreeze(s, fri.plusDays(3), liveOn = true, pinConfirmed = true))
        assertFalse(un.frozen)
        // Paper only: no PIN.
        assertNotNull(FreezeRule.unfreeze(s, fri.plusDays(3), liveOn = false, pinConfirmed = false))
        // The same fall does not freeze it again after the unfreeze.
        val again = FreezeRule.check(FreezeRule.Account.LIVE, daily, listOf(fri.minusDays(1)), fri.plusDays(3), override = 3_000.0, since = un.unfrozenOn)
        assertFalse(again.frozen)
        assertEquals(un, FreezeRule.decode(FreezeRule.encode(un)))
        assertEquals(s, FreezeRule.decode(FreezeRule.encode(s)))
        assertTrue(FreezeRule.say(s, emptyList()).startsWith("Yes, Boss"))
        assertTrue(FreezeRule.line(s).contains("FROZEN"))
    }
}
