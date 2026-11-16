package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 15 Nov 2026 (Boss's rule, third version: breakeven at +10% (V4); 16 Nov, fourth version: the step lock and breakeven at +₹1,000;
 * CLAUDE.md's fifth exception, failed gates): its
 * change-timeline entry ([AutoChange.stepTrail]) -
 * Boss's own, live scope: on only raises stops (lowers risk); its Undo puts the arms' own exits back alone (adds live risk) and takes
 * the PIN.
 */
class StepTrailChangeTest {
    private val day = LocalDate.of(2026, 11, 15)

    @Test fun onLowersRiskAndItsUndoTakesThePin() {
        val on = AutoChange.stepTrail(true)
        assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind)
        assertEquals(AutoChange.STEP_LOCK_TARGET, on.target, "its own target, so the 16 Nov update is logged once")
        assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
        assertEquals(AutoChange.Scope.LIVE, on.scope)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on))
        val back = AutoChange.inverse(on, "undone by you")
        assertEquals(AutoChange.SWITCHED_OFF, back.to); assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(back))
        assertTrue(AutoChange.undoNeedsPin(on), "off puts the wider exits back on live: the PIN")
        assertFalse(AutoChange.undoNeedsPin(AutoChange.stepTrail(false)), "switching it back on only adds earlier exits")
        assertFalse(AutoChange.never(on)); assertFalse(AutoChange.never(AutoChange.stepTrail(false)))
        assertTrue(AutoChange.bossOwn(on.kind))
        for (w in listOf("For r51/r72 use Step lock (+₹500 at +₹1,000, and so on) And for liquidity use Breakeven at +₹1,000",
                "keeps its arm's own stop", "breakeven at +10%", "+₹500 a lot", "+₹1,000 a lot", "+1,500 locks +1,000", "only rises",
                "R151 picker", "paper and live", "first exit wins", "Failed gates, Boss's exception", "R186", "1,097 trades", "₹98,239",
                "R179", "V4"))
            assertTrue(on.reason.contains(w), "$w: ${on.reason}")
        assertTrue(on.what.contains("failed gates, Boss's exception"))
        assertTrue(AutoChange.stepTrail(false).reason.contains("own exits"))
    }

    @Test fun itsTargetIsItsOwnAndItsUndoIsItsOwnTap() {
        val others = listOf(AutoChange.ST_TARGET, AutoChange.LOCK_TARGET, AutoChange.L25_TARGET, AutoChange.SAME_DIR_TARGET, AutoChange.NO_TWIN_TARGET,
            AutoChange.BOOK_PRICE_TARGET, AutoChange.WIDE_TARGET, AutoChange.NO_PROGRESS_TARGET, AutoChange.W20_TARGET, AutoChange.PICK_TARGET) +
            AutoChange.BOSS_PAPER_RULES
        for (t in listOf(AutoChange.STEP_TRAIL_TARGET, AutoChange.BE10_TARGET, AutoChange.STEP_LOCK_TARGET)) {
            assertFalse(t in others, t); assertFalse("liquidity" in t, t); assertTrue(AutoChange.isStepTrailTarget(t), t)
        }
        assertEquals(3, setOf(AutoChange.STEP_TRAIL_TARGET, AutoChange.BE10_TARGET, AutoChange.STEP_LOCK_TARGET).size)
        assertFalse(AutoChange.isStepTrailTarget(AutoChange.LOCK_TARGET), "R64's paper lock is another switch")
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

    @Test fun theMorningsStepTrailEntryIsSupersededByANewerFlipOfTheSameSwitch() {
        // An entry the first version wrote this morning, under its own target.
        val old = AutoChange.stepTrail(true).copy(target = AutoChange.STEP_TRAIL_TARGET)
        val (b, e) = AutoChange.record(AutoChange.Book(), old, AutoChange.Status.KEPT, 1_000L, day, by = "your ask")
        assertFalse(AutoChange.superseded(b, e), "nothing newer yet: it undoes")
        val (b2, newer) = AutoChange.record(b, AutoChange.stepTrail(false), AutoChange.Status.KEPT, 2_000L, day, by = "your choice")
        assertTrue(AutoChange.superseded(b2, e), "the step lock version's flip is the same switch: only the newest undoes")
        assertFalse(AutoChange.superseded(b2, newer))
        // 16 Nov: the third version's (V4) entry likewise.
        val v4 = AutoChange.stepTrail(true).copy(target = AutoChange.BE10_TARGET)
        val (c, ce) = AutoChange.record(AutoChange.Book(), v4, AutoChange.Status.KEPT, 1_000L, day, by = "your ask")
        assertFalse(AutoChange.superseded(c, ce))
        assertTrue(AutoChange.superseded(AutoChange.record(c, AutoChange.stepTrail(false), AutoChange.Status.KEPT, 2_000L, day, by = "you").first, ce))
        // Another switch's entry is not affected.
        val (b3, other) = AutoChange.record(AutoChange.Book(), AutoChange.stepTrail(true).copy(target = AutoChange.PICK_TARGET),
            AutoChange.Status.KEPT, 1_000L, day, by = "x")
        val (b4, _) = AutoChange.record(b3, AutoChange.stepTrail(false), AutoChange.Status.KEPT, 2_000L, day, by = "y")
        assertFalse(AutoChange.superseded(b4, other))
    }
}
