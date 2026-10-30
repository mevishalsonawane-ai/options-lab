package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 30 Oct (Boss: "Make stop bot different for index and comodities"): the day stop split into an INDEX side (the main button, the
 * tile) and a COMMODITY side (the MCX card's own control); Jarvis's "stop all" stops both. The pure parts: the words, the migration
 * of an older build's one stop, Jarvis's phrases and the change timeline's entry.
 */
class SplitStopTest {
    // ---- words -----------------------------------------------------------------------------------------------------------------

    @Test fun eachSideSaysItsStateAndReason() {
        assertEquals("running", DayStop.state(null))
        assertEquals("stopped by you 10:42", DayStop.state(DayStop.Kept(DayStop.Why.BOSS, "10:42")))
        assertEquals("stopped from the quick-settings tile 09:05", DayStop.state(DayStop.Kept(DayStop.Why.TILE, "09:05")))
        assertEquals("stopped by you", DayStop.state(DayStop.Kept(DayStop.Why.BOSS)), "an older build's stop has no time")
        assertEquals("stopped by the daily loss limit 11:00", DayStop.state(DayStop.Kept(DayStop.Why.LOSS, "11:00")))
        // A loss stop is said first, even over Boss's own stop on the same side (it is the one that holds until tomorrow).
        assertEquals("stopped by the daily loss limit", DayStop.state(DayStop.Kept(DayStop.Why.BOSS, "10:42"), loss = true))
        assertEquals("stopped by the daily loss limit", DayStop.state(null, loss = true))
        assertEquals("Commodity bots: stopped by you 10:42", DayStop.sideLine(DayStop.Side.MCX, DayStop.Kept(DayStop.Why.BOSS, "10:42")))
        assertEquals("Index bots: running", DayStop.sideLine(DayStop.Side.INDEX, null))
        assertEquals("09:05", DayStop.hhmm(LocalTime.of(9, 5, 59)))
    }

    @Test fun theMainBarSaysTheIndexSideOnly() {
        val bar = DayStop.bar(DayStop.Why.BOSS, "10:42")
        assertTrue(bar.startsWith("Index bots stopped for today by you 10:42"), bar)
        assertTrue(bar.contains("the commodity bots keep trading"), bar)
        assertEquals(DayStop.bar(DayStop.Why.BOSS).replace(" 10:42", ""), DayStop.bar(DayStop.Why.BOSS), "no time: none said")
        assertTrue(DayStop.bar(DayStop.Why.LOSS, "11:00").contains("by the daily loss limit"))
        assertTrue(DayStop.line(DayStop.Why.BOSS).contains("Start index bots"))
    }

    @Test fun startAgainIsRefusedAfterALossStopOrWhenNothingIsStopped() {
        for (side in DayStop.Side.entries) {
            assertNotNull(DayStop.startRefusal(side, DayStop.Kept(DayStop.Why.BOSS), loss = true)).let { assertTrue(it.contains("daily loss limit"), it) }
            assertNotNull(DayStop.startRefusal(side, DayStop.Kept(DayStop.Why.LOSS), loss = false)).let { assertTrue(it.contains("daily loss limit"), it) }
            assertNotNull(DayStop.startRefusal(side, null, loss = true))
            assertEquals("The ${side.words} are not stopped.", DayStop.startRefusal(side, null, loss = false))
            assertNull(DayStop.startRefusal(side, DayStop.Kept(DayStop.Why.BOSS, "10:42"), loss = false))
            assertNull(DayStop.startRefusal(side, DayStop.Kept(DayStop.Why.TILE), loss = false))
        }
    }

    // ---- migration -------------------------------------------------------------------------------------------------------------

    @Test fun anOlderBuildsStopMigratesAsBothStopped() {
        // An older build's book: one stop, no side of its own. Boss's or the tile's stop: the commodity side is stopped too.
        assertEquals("2026-10-30" to "boss", DayStop.migratedMcx(false, "2026-10-30", "boss", null, null))
        assertEquals("2026-10-30" to "tile", DayStop.migratedMcx(false, "2026-10-30", "tile", null, null))
        assertEquals("2026-10-30" to "boss", DayStop.migratedMcx(false, "2026-10-30", null, null, null), "kept without a reason: Boss's")
        // A loss stop is not copied: the loss rules decide the MCX side, exactly as the older build did.
        assertNull(DayStop.migratedMcx(false, "2026-10-30", "loss", null, null))
        // No stop: none.
        assertNull(DayStop.migratedMcx(false, null, null, null, null))
        // A book kept by this build: the commodity side as stored, whatever the index side holds.
        assertNull(DayStop.migratedMcx(true, "2026-10-30", "boss", null, null))
        assertEquals("2026-10-30" to "tile", DayStop.migratedMcx(true, null, null, "2026-10-30", "tile"))
        assertEquals("2026-10-29" to "boss", DayStop.migratedMcx(true, null, null, "2026-10-29", null))
    }

    // ---- Jarvis ----------------------------------------------------------------------------------------------------------------

    private fun cmd(t: String) = Commands.parse(t)

    @Test fun jarvisReadsEachSidesPhrases() {
        for (t in listOf("stop the index bots", "Stop index bots", "stop the nse bots", "stop index trading for today", "pause the index arms",
                "stop all the index bots")) {
            val c = cmd(t)
            assertEquals(Command.Kind.STOP_ALL, c?.kind, t)
            assertEquals(DayStop.Side.INDEX, c?.side, t)
        }
        for (t in listOf("stop the commodity bots", "stop commodities", "stop mcx", "Stop the MCX bots for today", "stop the commodity arms",
                "stop commodity trading")) {
            val c = cmd(t)
            assertEquals(Command.Kind.STOP_ALL, c?.kind, t)
            assertEquals(DayStop.Side.MCX, c?.side, t)
        }
        for (t in listOf("start the commodity bots again", "resume commodities", "start mcx again", "restart the commodity bots", "start commodities")) {
            val c = cmd(t)
            assertEquals(Command.Kind.START_ALL, c?.kind, t)
            assertEquals(DayStop.Side.MCX, c?.side, t)
        }
        for (t in listOf("start the index bots again", "resume the index bots", "start index bots")) {
            val c = cmd(t)
            assertEquals(Command.Kind.START_ALL, c?.kind, t)
            assertEquals(DayStop.Side.INDEX, c?.side, t)
        }
    }

    /** The hub reads Boss's words through [Ask.parse]: the side is kept there too (and in a timed "later", which reads them again). */
    @Test fun theSideIsKeptThroughAsk() {
        assertEquals(DayStop.Side.MCX, Ask.parse("stop the commodity bots").command?.side)
        assertEquals(DayStop.Side.INDEX, Ask.parse("stop the index bots").command?.side)
        assertEquals(DayStop.Side.MCX, Ask.parse("start the commodity bots again").command?.side)
        assertEquals(Command.Kind.STOP_ALL, Ask.parse("stop all").command?.kind)
        assertNull(Ask.parse("stop all").command?.side)
    }

    @Test fun stopAllStillStopsBothAndKeepsItsReading() {
        for (t in listOf("stop all", "stop everything", "stop the bots", "stop trading")) {
            val c = cmd(t)
            assertEquals(Command.Kind.STOP_ALL, c?.kind, t)
            assertNull(c?.side, t)
        }
        assertEquals("Command(kind=STOP_ALL, target=null, number=null, market=null, above=null, level=null, day=null, pct=null, lots=null)", cmd("stop all").toString())
        assertTrue(cmd("stop the commodity bots").toString().endsWith(", side=MCX)"))
        assertEquals(Command.Kind.START_ALL, cmd("start all")?.kind)
        assertNull(cmd("start all")?.side)
    }

    @Test fun aSideIsNeverReadFromAQuestionANegationOrAnArmsName() {
        assertNull(cmd("how do I stop the commodity bots"))
        assertNull(cmd("don't stop the index bots"))
        assertNull(cmd("why are the commodity bots stopped"))
        // "stop nifty" alone is not the index side (it needs its group word).
        assertNull(cmd("stop nifty")?.side)
        // One arm by name stays one arm.
        assertEquals(Command.Kind.STOP_ONE, cmd("stop orb")?.kind)
    }

    @Test fun theSidesUseTheSameConfirmAsStopAll() {
        // Same kinds: stopping lowers risk (waits for the confirm tap), starting needs the confirm, as "start all" did.
        assertTrue(Command.Kind.STOP_ALL.reduces)
        assertFalse(Command.Kind.START_ALL.reduces)
        assertEquals(Toolbox.of(Command.Kind.STOP_ALL).need, Toolbox.of(cmd("stop the commodity bots")!!.kind).need)
        assertEquals(Toolbox.of(Command.Kind.START_ALL).need, Toolbox.of(cmd("start the commodity bots again")!!.kind).need)
    }

    @Test fun eachSideIsDescribedForTheConfirm() {
        val i = Commands.describe(Command(Command.Kind.STOP_ALL, side = DayStop.Side.INDEX))
        assertTrue(i.startsWith("stop the index bots for today") && i.contains("the commodity bots keep trading"), i)
        val m = Commands.describe(Command(Command.Kind.STOP_ALL, side = DayStop.Side.MCX))
        assertTrue(m.startsWith("stop the commodity bots for today") && m.contains("the index bots keep trading"), m)
        assertEquals("stop every strategy and arm for today (and close what they hold)", Commands.describe(Command(Command.Kind.STOP_ALL)))
        assertTrue(Commands.describe(Command(Command.Kind.START_ALL, side = DayStop.Side.MCX)).startsWith("start the commodity bots again"))
        assertTrue(Commands.describe(Command(Command.Kind.START_ALL, side = DayStop.Side.INDEX)).startsWith("start the index bots again"))
    }

    // ---- the change timeline ---------------------------------------------------------------------------------------------------

    @Test fun eachStopAndStartIsATimelineEntryUndoneByTheOpposite() {
        val day = LocalDate.of(2026, 10, 30)
        val stop = AutoChange.dayStop(DayStop.Side.MCX, stopped = true, by = "by you", at = "10:42")
        assertEquals(AutoChange.Kind.DAY_STOP, stop.kind)
        assertEquals("daystop:mcx", stop.target)
        assertEquals(DayStop.Side.MCX, AutoChange.dayStopSide(stop.target))
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(stop))
        assertEquals(AutoChange.Scope.PAPER, stop.scope)
        assertTrue(stop.reason.contains("at 10:42") && stop.reason.contains("the index bots keep trading"), stop.reason)
        assertFalse(AutoChange.never(stop))
        assertTrue(AutoChange.bossOwn(AutoChange.Kind.DAY_STOP), "Boss's own: never undone by \"undo the last change\"")
        val (b1, e1) = AutoChange.record(AutoChange.Book(), stop, AutoChange.Status.KEPT, 1L, day, by = "your choice")
        val u = AutoChange.undo(b1, e1.id)!!
        assertEquals(AutoChange.RUNNING, u.inverse.to)
        assertFalse(u.needsPin, "the commodity bots are paper: no PIN")
        // The index side governs live entries: an Undo that lifts its stop asks the PIN; one that stops it again never does.
        val idx = AutoChange.dayStop(DayStop.Side.INDEX, stopped = true, by = "by you")
        assertEquals(AutoChange.Scope.LIVE, idx.scope)
        assertTrue(AutoChange.undoNeedsPin(idx))
        assertFalse(AutoChange.undoNeedsPin(AutoChange.dayStop(DayStop.Side.INDEX, stopped = false, by = "by you")))
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(AutoChange.dayStop(DayStop.Side.MCX, stopped = false, by = "by you")))
        // Stopped again since: only the newest entry undoes.
        val (b2, _) = AutoChange.record(b1, AutoChange.dayStop(DayStop.Side.MCX, stopped = false, by = "by you"), AutoChange.Status.KEPT, 2L, day, by = "your choice")
        assertNull(AutoChange.undo(b2, e1.id))
        // A different side's entry never supersedes it.
        val (b3, _) = AutoChange.record(b1, idx, AutoChange.Status.KEPT, 3L, day, by = "your choice")
        assertNotNull(AutoChange.undo(b3, e1.id))
        assertNull(AutoChange.dayStopSide("daystop:gold"))
        assertNull(AutoChange.dayStopSide("arm:orb"))
    }
}
