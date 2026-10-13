package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boss's 13 Oct park (ORB, ORB Fresh, ORB Sweep, Range Fade) as the Autopilot log keeps it ([AutoChange.Kind.ARM_PARK]):
 * shown in the change timeline as Boss's, undone only by its own tap - never by "undo everything today" or "undo the last
 * change" - and never counted as Autopilot's own change.
 */
class ArmParkChangeTest {
    private val day = LocalDate.of(2026, 10, 13)

    @Test fun theChangeLowersRiskOnPaperAndItsUndoPutsTheArmBackAsItWas() {
        val c = AutoChange.armPark("orb_fresh", "ORB Fresh", "−₹16,838 and −₹6,361 a lot", -23_199.0, automatic = false)
        assertEquals("arm:orb_fresh", c.target)
        assertEquals(AutoChange.ON_APPROVE, c.from); assertEquals(AutoChange.PARKED, c.to)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(c))
        assertEquals(AutoChange.Scope.PAPER, c.scope)
        assertFalse(AutoChange.undoNeedsPin(c), "undone without the PIN: back on paper only")
        assertFalse(AutoChange.never(c))
        val back = AutoChange.inverse(c, "undone by you")
        assertEquals(AutoChange.ON_APPROVE, back.to)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(back))
        assertEquals(AutoChange.ON, AutoChange.armPark("orb", "ORB", "x", 0.0).from)
        assertTrue(c.reason.startsWith("your choice (13 Oct): ORB Fresh lost money after costs in every tested year"))
    }

    private fun book(): Pair<AutoChange.Book, String> {
        var b = AutoChange.Book()
        val (b1, park) = AutoChange.record(b, AutoChange.armPark("orb", "ORB", "x", 0.0), AutoChange.Status.KEPT, 1_000L, day, by = "your choice")
        b = b1
        val auto = AutoChange.Change(AutoChange.Kind.PAPER_ENTRIES, "paper:night_r3", AutoChange.ON, AutoChange.PAUSED, "loses on paper",
            AutoChange.Evidence(30, -3_000.0, AgentLessons.Confidence.STRONG), AutoChange.Risk.LOWER, AutoChange.Scope.PAPER, what = "Night (R3)'s new paper entries")
        b = AutoChange.record(b, auto, AutoChange.Status.PROBATION, 500L, day).first
        return b to park.id
    }

    @Test fun itIsBosssInTheTimelineAndOnlyItsOwnTapUndoesIt() {
        val (b, parkId) = book()
        val items = ChangeTimeline.fromAutopilot(b, day)
        val park = items.single { it.key == parkId }
        assertTrue(park.own); assertEquals("You (logged by Autopilot)", park.agent)
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, park.undo)
        assertEquals("ORB's switch: on → parked", park.what)
        // "Undo everything today" leaves it alone; Autopilot's own change is still undone.
        val plan = ChangeTimeline.undoPlan(items)
        assertTrue(plan.now.none { it.own } && plan.left.none { it.own } && plan.needPin.none { it.own })
        assertEquals(1, plan.now.size)
        // "Undo the last change" picks Autopilot's, though the park was logged after it.
        assertEquals("paper:night_r3", AutoChange.lastUndoable(b)!!.change.target)
        assertNull(AutoChange.lastUndoable(AutoChange.record(AutoChange.Book(), AutoChange.armPark("orb", "ORB", "x", 0.0),
            AutoChange.Status.KEPT, 1L, day).first))
        // Its own Undo still works.
        assertEquals(AutoChange.ON, AutoChange.undo(b, parkId)!!.inverse.to)
    }

    @Test fun itIsNeverCountedAsAutopilotsChange() {
        val (b, _) = book()
        val s = AutoChange.summary(b, day)
        assertEquals(1, s.today); assertEquals(1, s.todayPaper); assertEquals(0, s.kept)
        assertEquals("Autopilot made 1 change (1 paper). Tap to review.", AutoChange.notice(s))
        assertFalse("ORB's switch" in AutoChange.sayToday(b, day))
        val onlyPark = AutoChange.record(AutoChange.Book(), AutoChange.armPark("orb", "ORB", "x", 0.0), AutoChange.Status.KEPT, 1L, day).first
        assertNull(AutoChange.notice(AutoChange.summary(onlyPark, day)))
        assertEquals("I changed nothing by myself today, Boss.", AutoChange.sayToday(onlyPark, day))
    }
}
