package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 6 Nov 2026: R151's R72 and R51 pickers in the change timeline ([AutoChange.passedPicker]) - Boss's exception, acting on paper and
 * live (on lowers risk; its Undo adds live trades back and takes the PIN) - and R150's W20 on R51, a log only (shadow either way).
 */
class PickerChangeTest {
    private val day = LocalDate.of(2026, 11, 6)

    /** 6 Nov (Boss's exception, CLAUDE.md: "i want live"): the pickers act on paper and live - on lowers risk; off adds live trades back (PIN). */
    @Test fun thePickersAreBossOwnLiveFiltersWhoseUndoTakesThePin() {
        val on = AutoChange.passedPicker(true)
        assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind)
        assertEquals(AutoChange.PICK_TARGET, on.target)
        assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
        assertEquals(AutoChange.Scope.LIVE, on.scope)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on))
        val back = AutoChange.inverse(on, "undone by you")
        assertEquals(AutoChange.SWITCHED_OFF, back.to); assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(back))
        assertTrue(AutoChange.undoNeedsPin(on), "off adds live trades back: the PIN")
        assertFalse(AutoChange.undoNeedsPin(AutoChange.passedPicker(false)), "switching them back on only skips")
        assertTrue(AutoChange.bossOwn(on.kind))
        for (w in listOf("i want live", "0.4656", "0.4469", "skipped on paper and live", "Failed gates, Boss's exception", "48.9%", "49.9%", "50%"))
            assertTrue(on.reason.contains(w), "$w: ${on.reason}")
        assertTrue(on.what.contains("failed gates, Boss's exception"))
        assertTrue(AutoChange.passedPicker(false).reason.contains("come back, live too"))
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
        assertEquals(AutoChange.SWITCHED_OFF, undo.inverse.to); assertTrue(undo.needsPin, "live scope: its Undo takes the PIN")
        assertNull(AutoChange.lastUndoable(b), "Boss's own: never \"undo the last change\"")
        val item = ChangeTimeline.fromAutopilot(b, day).single { it.key == e.id }
        assertEquals(ChangeTimeline.UndoState.NEEDS_PIN, item.undo, "it adds live trades back")
        assertTrue(item.own)
    }
}
