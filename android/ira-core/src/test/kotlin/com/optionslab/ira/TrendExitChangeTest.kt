package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Trend watch's paper exit in Autopilot's change log ([AutoChange.Kind.TREND_EXIT], 16 Oct, research R63): the one-time
 * switch-on and each leg sold are Boss's (kept, never "undo everything"), on paper (no PIN either way), lowering risk; the Undo of
 * a sale switches the exit off ("held"), and only the newest entry of it undoes.
 */
class TrendExitChangeTest {
    private val day = LocalDate.of(2026, 10, 16)

    @Test fun onAndSoldAreBossOwnPaperAndLowerRisk() {
        val on = AutoChange.trendExitOn("hero_ticket", "Hero ₹1,000 ticket", "T3", "R63")
        val sold = AutoChange.trendExitSold("hero_ticket", "Hero ₹1,000 ticket", "Hero ₹1,000 ticket NIFTY..CE sold on paper on the trend turn (T3) @ 4.10")
        for (c in listOf(on, sold)) {
            assertEquals(AutoChange.Kind.TREND_EXIT, c.kind)
            assertEquals("trendexit:hero_ticket", c.target)
            assertEquals(AutoChange.Scope.PAPER, c.scope)
            assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(c))
            assertFalse(AutoChange.undoNeedsPin(c))
            assertFalse(AutoChange.never(c))
        }
        assertTrue(AutoChange.bossOwn(AutoChange.Kind.TREND_EXIT))
        assertEquals(AutoChange.ON, on.to); assertEquals(AutoChange.SWITCHED_OFF, on.from)
        assertEquals(AutoChange.HELD, sold.from); assertEquals(AutoChange.SOLD_ON_TURN, sold.to)
        assertTrue(sold.reason.contains("the leg sold stays sold"))
        // Undoing either takes the earlier exit away: that adds risk on paper only (no PIN).
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(AutoChange.inverse(sold, "undone")))
        assertEquals(AutoChange.HELD, AutoChange.inverse(sold, "undone").to)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(AutoChange.inverse(on, "undone")))
    }

    @Test fun onlyTheNewestEntryUndoesAndUndoEverythingSkipsIt() {
        var b = AutoChange.Book()
        b = AutoChange.record(b, AutoChange.trendExitOn("hero_ticket", "Hero", "T3", "R63"), AutoChange.Status.KEPT, 1L, day, by = "your choice").first
        b = AutoChange.record(b, AutoChange.trendExitSold("hero_ticket", "Hero", "sold"), AutoChange.Status.KEPT, 2L, day, by = "the Trend watch").first
        val (first, second) = b.entries
        assertTrue(AutoChange.superseded(b, first))
        assertFalse(AutoChange.superseded(b, second))
        assertEquals(null, AutoChange.lastUndoable(b))
        val u = AutoChange.undo(b, second.id)!!
        assertEquals(AutoChange.HELD, u.inverse.to)
        assertFalse(u.needsPin)
    }
}
