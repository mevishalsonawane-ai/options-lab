package com.optionslab.ira

import com.optionslab.engine.mcx.McxMorningPark
import com.optionslab.engine.mcx.McxMorningRules
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 14 Oct (research R42, Boss's OK): the SILVERM morning call (an MCX PAPER arm) switched off, as the Autopilot log keeps it
 * ([AutoChange.mcxArmPark]): Boss's change in the timeline, lowering paper risk, undone in one tap without the PIN (back on,
 * paper only), never Autopilot's own change, and its reason (with R42's sample sizes) survives the log's file.
 */
class McxArmParkChangeTest {
    private val day = LocalDate.of(2026, 10, 14)
    private fun change() = AutoChange.mcxArmPark(McxMorningRules.SOURCE, McxMorningRules.LABEL, McxMorningPark.REASON,
        McxMorningPark.WALK_FORWARD_TRADES, McxMorningPark.WALK_FORWARD_RS.toDouble())

    @Test fun itLowersPaperRiskAndItsUndoPutsTheArmBackOnWithoutThePin() {
        val c = change()
        assertEquals("mcx:mcx_silverm_am", c.target)
        assertTrue(c.target.startsWith(AutoChange.MCX_ARM))
        assertEquals(AutoChange.Kind.ARM_PARK, c.kind)
        assertEquals(AutoChange.ON, c.from); assertEquals(AutoChange.PARKED, c.to)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(c))
        assertEquals(AutoChange.Scope.PAPER, c.scope)
        assertFalse(AutoChange.never(c), "not a safety setting")
        assertFalse(AutoChange.undoNeedsPin(c), "paper only: no PIN to undo")
        val back = AutoChange.inverse(c, "undone by you")
        assertEquals(AutoChange.ON, back.to)
        assertEquals("SILVERM morning call's switch", c.what)
        for (w in listOf("R42", "53 trades", "p 0.079", "129 trades", "95 trades")) assertTrue(w in c.reason, w)
        // The evidence is the losing walk-forward it avoids (129 trades, -Rs 44.6k), as the ORB parks keep their losses.
        assertEquals(129, c.evidence.n); assertTrue(c.evidence.rs < 0); assertEquals(-1, c.evidence.sign)
    }

    @Test fun itIsBosssInTheTimelineAndOnlyItsOwnTapUndoesIt() {
        val (b, park) = AutoChange.record(AutoChange.Book(), change(), AutoChange.Status.KEPT, 1_000L, day, by = "your choice")
        val item = ChangeTimeline.fromAutopilot(b, day).single { it.key == park.id }
        assertTrue(item.own)
        assertEquals("SILVERM morning call's switch: on → parked", item.what)
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, item.undo)
        // "Undo everything today" and "undo the last change" leave it alone; its own Undo works.
        val plan = ChangeTimeline.undoPlan(listOf(item))
        assertTrue(plan.now.isEmpty() && plan.needPin.isEmpty())
        assertNull(AutoChange.lastUndoable(b))
        assertEquals(AutoChange.ON, AutoChange.undo(b, park.id)!!.inverse.to)
        // Never counted as Autopilot's own change.
        assertNull(AutoChange.notice(AutoChange.summary(b, day)))
        assertEquals("I changed nothing by myself today, Boss.", AutoChange.sayToday(b, day))
    }

    @Test fun itsReasonSurvivesTheLogsFile() {
        val (b, park) = AutoChange.record(AutoChange.Book(), change(), AutoChange.Status.KEPT, 1_000L, day, by = "your choice")
        val read = AutoChange.decode(AutoChange.encode(b))
        val e = read.entries.single { it.id == park.id }
        assertEquals(change().target, e.change.target)
        assertEquals(McxMorningPark.REASON, e.change.reason)
        assertEquals(AutoChange.Status.KEPT, e.status)
    }
}
