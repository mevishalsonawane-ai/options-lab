package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 6 Nov 2026 (Boss: "R72 picker (shadow log)"): R151's R72 picker in the change timeline ([AutoChange.passedPicker]). A log only - it
 * never skips or changes a trade - so it is shadow either way (never "lowers" or "adds" risk), no PIN, logged as Boss's own choice
 * (undone by its own tap, never by "undo everything"), and its words carry the failed gate.
 */
class PickerChangeTest {
    private val day = LocalDate.of(2026, 11, 6)

    @Test fun itIsABossOwnShadowChangeEitherWay() {
        val on = AutoChange.passedPicker(true)
        assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind)
        assertEquals(AutoChange.PICK_TARGET, on.target)
        assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
        assertEquals(AutoChange.Scope.SHADOW, on.scope)
        assertEquals(AutoChange.Risk.NEUTRAL_SHADOW, AutoChange.riskOf(on))
        val back = AutoChange.inverse(on, "undone by you")
        assertEquals(AutoChange.SWITCHED_OFF, back.to); assertEquals(AutoChange.Risk.NEUTRAL_SHADOW, AutoChange.riskOf(back))
        assertFalse(AutoChange.never(on)); assertFalse(AutoChange.undoNeedsPin(on)); assertFalse(AutoChange.undoNeedsPin(AutoChange.passedPicker(false)))
        assertTrue(AutoChange.bossOwn(on.kind))
        for (w in listOf("R72 picker (shadow log)", "nothing is skipped or changed", "Failed gates, so a shadow log only", "48.9%", "50%", "0.4656", "about 100"))
            assertTrue(on.reason.contains(w), "$w: ${on.reason}")
        assertTrue(on.what.contains("failed gates; a shadow log only"))
        assertTrue(AutoChange.passedPicker(false).reason.contains("none was ever skipped"))
    }

    /** 6 Nov (Boss: "Do whatever you recommend", on R51): R150 B's W20 label on R51 is the same kind of log only. */
    @Test fun w20IsABossOwnShadowChangeEitherWayToo() {
        val on = AutoChange.passedW20(true)
        assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind); assertEquals(AutoChange.W20_TARGET, on.target)
        assertEquals(AutoChange.Scope.SHADOW, on.scope)
        assertEquals(AutoChange.Risk.NEUTRAL_SHADOW, AutoChange.riskOf(on))
        assertEquals(AutoChange.Risk.NEUTRAL_SHADOW, AutoChange.riskOf(AutoChange.inverse(on, "undone by you")))
        assertFalse(AutoChange.undoNeedsPin(on)); assertFalse(AutoChange.undoNeedsPin(AutoChange.passedW20(false)))
        for (w in listOf("Do whatever you recommend", "0.25%", "nothing is skipped or changed", "Failed gates, so a shadow log only", "p < 0.001",
                "R51 picker", "0.4469", "49.9%", "about 100"))
            assertTrue(on.reason.contains(w), "$w: ${on.reason}")
        assertTrue(AutoChange.passedW20(false).reason.contains("none was ever skipped"))
        assertTrue(AutoChange.W20_TARGET != AutoChange.PICK_TARGET && "liquidity" !in AutoChange.W20_TARGET)
        val (b, e) = AutoChange.record(AutoChange.Book(), on, AutoChange.Status.KEPT, 1_000L, day, by = "your ask")
        assertFalse(AutoChange.undo(b, e.id)!!.needsPin)
        assertNull(AutoChange.lastUndoable(b))
    }

    @Test fun itsTargetIsItsOwnAndItsUndoIsItsOwnTap() {
        val others = listOf(AutoChange.ST_TARGET, AutoChange.LOCK_TARGET, AutoChange.L25_TARGET, AutoChange.SAME_DIR_TARGET, AutoChange.NO_TWIN_TARGET,
            AutoChange.BOOK_PRICE_TARGET, AutoChange.WIDE_TARGET, AutoChange.NO_PROGRESS_TARGET, AutoChange.W20_TARGET) + AutoChange.BOSS_PAPER_RULES
        assertFalse(AutoChange.PICK_TARGET in others)
        assertFalse("liquidity" in AutoChange.PICK_TARGET)
        val (b, e) = AutoChange.record(AutoChange.Book(), AutoChange.passedPicker(true), AutoChange.Status.KEPT, 1_000L, day, by = "your ask")
        val undo = AutoChange.undo(b, e.id)!!
        assertEquals(AutoChange.SWITCHED_OFF, undo.inverse.to); assertFalse(undo.needsPin)
        assertNull(AutoChange.lastUndoable(b), "Boss's own: never \"undo the last change\"")
        val item = ChangeTimeline.fromAutopilot(b, day).single { it.key == e.id }
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, item.undo)
        assertTrue(item.own)
    }
}
