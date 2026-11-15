package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 15 Nov 2026 (Boss's ₹500 step trail per lot; CLAUDE.md's fifth exception, not tested): its change-timeline entry ([AutoChange.stepTrail]) -
 * Boss's own, live scope: on only tightens stops (lowers risk); its Undo puts the arms' own wider exits back (adds live risk) and takes
 * the PIN.
 */
class StepTrailChangeTest {
    private val day = LocalDate.of(2026, 11, 15)

    @Test fun onLowersRiskAndItsUndoTakesThePin() {
        val on = AutoChange.stepTrail(true)
        assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind)
        assertEquals(AutoChange.STEP_TRAIL_TARGET, on.target)
        assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
        assertEquals(AutoChange.Scope.LIVE, on.scope)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on))
        val back = AutoChange.inverse(on, "undone by you")
        assertEquals(AutoChange.SWITCHED_OFF, back.to); assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(back))
        assertTrue(AutoChange.undoNeedsPin(on), "off puts the wider exits back on live: the PIN")
        assertFalse(AutoChange.undoNeedsPin(AutoChange.stepTrail(false)), "switching it back on only adds earlier exits")
        assertFalse(AutoChange.never(on)); assertFalse(AutoChange.never(AutoChange.stepTrail(false)))
        assertTrue(AutoChange.bossOwn(on.kind))
        for (w in listOf("stop loss should be only 500 rs", "-₹500 a lot", "+₹1,000 a lot", "+₹500 a lot", "never down", "paper and live",
                "first one wins", "tighter", "Not tested, Boss's exception", "R152", "R167", "R172"))
            assertTrue(on.reason.contains(w), "$w: ${on.reason}")
        assertTrue(on.what.contains("not tested, Boss's exception"))
        assertTrue(AutoChange.stepTrail(false).reason.contains("own exits"))
    }

    @Test fun itsTargetIsItsOwnAndItsUndoIsItsOwnTap() {
        val others = listOf(AutoChange.ST_TARGET, AutoChange.LOCK_TARGET, AutoChange.L25_TARGET, AutoChange.SAME_DIR_TARGET, AutoChange.NO_TWIN_TARGET,
            AutoChange.BOOK_PRICE_TARGET, AutoChange.WIDE_TARGET, AutoChange.NO_PROGRESS_TARGET, AutoChange.W20_TARGET, AutoChange.PICK_TARGET) +
            AutoChange.BOSS_PAPER_RULES
        assertFalse(AutoChange.STEP_TRAIL_TARGET in others)
        assertFalse("liquidity" in AutoChange.STEP_TRAIL_TARGET)
        val (b, e) = AutoChange.record(AutoChange.Book(), AutoChange.stepTrail(true), AutoChange.Status.KEPT, 1_000L, day, by = "your ask")
        val undo = AutoChange.undo(b, e.id)!!
        assertEquals(AutoChange.SWITCHED_OFF, undo.inverse.to); assertTrue(undo.needsPin)
        assertNull(AutoChange.lastUndoable(b), "Boss's own: never \"undo the last change\"")
        val item = ChangeTimeline.fromAutopilot(b, day).single { it.key == e.id }
        assertEquals(ChangeTimeline.UndoState.NEEDS_PIN, item.undo)
        assertTrue(item.own)
        // A newer flip of the same switch supersedes the older entry (only the newest undoes).
        val (b2, _) = AutoChange.record(b, AutoChange.stepTrail(false), AutoChange.Status.KEPT, 2_000L, day, by = "your choice")
        assertTrue(AutoChange.superseded(b2, e))
    }
}
