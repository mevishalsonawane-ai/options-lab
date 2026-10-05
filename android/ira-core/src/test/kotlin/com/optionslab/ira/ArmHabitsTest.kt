package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArmHabitsTest {
    private val d0 = LocalDate.of(2026, 9, 1)
    private val fade = "Range Fade"
    private val orb = "ORB"

    /** One day per entry: (armed?, sign: -1 lost, 1 won, 0 no trade) for each bot, on consecutive days from [d0]. */
    private fun log(vararg days: Map<String, Pair<Boolean, Int>>): ArmHabits.Log =
        days.foldIndexed(ArmHabits.Log()) { i, l, m ->
            ArmHabits.note(l, d0.plusDays(i.toLong()), m.mapValues { it.value.first },
                m.filterValues { it.second < 0 }.keys, m.filterValues { it.second > 0 }.keys)
        }

    private fun a(sign: Int) = true to sign
    private val off = false to 0

    /** Range Fade: kept after one loss, switched off after two in a row - three times over. */
    private fun fadeTwice(): ArmHabits.Log {
        val cycle = listOf(a(-1), a(-1), off, a(1))
        return log(*(cycle + cycle + cycle).map { mapOf(fade to it) }.toTypedArray())
    }

    @Test fun switchedOffAfterTwoLosingDaysIsAHabit() {
        val h = ArmHabits.habits(fadeTwice()).single()
        assertEquals(ArmHabits.Kind.DISARMS, h.kind)
        assertEquals(2, h.at?.run)
        assertEquals(3, h.at?.off)
        val line = ArmHabits.line(h)
        assertEquals("you usually switch Range Fade off after two losing days in a row (3 of 3 times), and keep it armed before that (3 of 3 times)", line)
    }

    @Test fun keptThroughLossesIsAHabitToo() {
        val l = log(mapOf(orb to a(-1)), mapOf(orb to a(-1)), mapOf(orb to a(-1)), mapOf(orb to a(1)), mapOf(orb to a(-1)), mapOf(orb to a(0)))
        val h = ArmHabits.habits(l).single()
        assertEquals(ArmHabits.Kind.KEEPS, h.kind)
        assertEquals(4, h.times); assertEquals(0, h.off)
        assertTrue(ArmHabits.line(h).startsWith("you keep ORB armed through losing days (kept 4 of 4 times, even after three or more"), ArmHabits.line(h))
    }

    @Test fun tooFewOrMixedIsNotAHabit() {
        val few = log(mapOf(orb to a(-1)), mapOf(orb to off))
        assertEquals(ArmHabits.Kind.FEW, ArmHabits.habits(few).single().kind)
        assertTrue(ArmHabits.shown(few).isEmpty())
        val mixed = log(mapOf(orb to a(-1)), mapOf(orb to off), mapOf(orb to a(-1)), mapOf(orb to a(-1)), mapOf(orb to off), mapOf(orb to a(-1)), mapOf(orb to a(1)))
        // run 1: off, kept, kept(after run 1 again)... nothing at one length two-thirds off.
        val h = ArmHabits.habits(mixed).single()
        assertTrue(h.kind == ArmHabits.Kind.MIXED || h.kind == ArmHabits.Kind.FEW, "$h")
    }

    @Test fun aGapOrAnUnknownNextDayIsNoOutcome() {
        var l = ArmHabits.note(ArmHabits.Log(), d0, mapOf(orb to true), setOf(orb), emptySet())
        l = ArmHabits.note(l, d0.plusDays(10), mapOf(orb to false), emptySet(), emptySet())
        assertTrue(ArmHabits.events(l).isEmpty())
        var m = ArmHabits.note(ArmHabits.Log(), d0, mapOf(orb to true), setOf(orb), emptySet())
        m = ArmHabits.note(m, d0.plusDays(1), mapOf(fade to true), emptySet(), emptySet())
        assertTrue(ArmHabits.events(m).isEmpty())
    }

    @Test fun armedAtAnyTimeInTheDayStaysArmedAndTheSignIsTheLatest() {
        var l = ArmHabits.note(ArmHabits.Log(), d0, mapOf(orb to false), emptySet(), emptySet())
        l = ArmHabits.note(l, d0, mapOf(orb to true), setOf(orb), emptySet())
        l = ArmHabits.note(l, d0, mapOf(orb to false), emptySet(), setOf(orb))
        val d = l.days.single()
        assertEquals(setOf(orb), d.armed); assertEquals(setOf(orb), d.won); assertTrue(d.lost.isEmpty())
        // Nothing changed: the same log back (nothing to save).
        assertTrue(ArmHabits.note(l, d0, mapOf(orb to false), emptySet(), setOf(orb)) === l)
    }

    @Test fun theAnswerIsARecordAndNamesOneBotWhenAsked() {
        val l = fadeTwice()
        val all = ArmHabits.say(l, "do I usually disarm my bots after losses")
        assertTrue(all.startsWith("Boss, from the days this phone saw: you usually switch Range Fade off"), all)
        assertTrue(all.endsWith(ArmHabits.ONLY_RECORD))
        val none = ArmHabits.say(ArmHabits.Log(), "what do I do after a losing day")
        assertTrue(none.contains("never an amount") && none.endsWith(ArmHabits.ONLY_RECORD), none)
        assertFalse(Regex("Rs|₹|\\d{3,}").containsMatchIn(all), all)
    }

    @Test fun theLedgerShowsItAsARecordWithNoUndo() {
        val items = Learnings.items(Learnings.Inputs(arms = fadeTwice()), LocalDateTime.of(2026, 9, 13, 16, 0))
        val it = items.single { it.area == Learnings.Area.ARM_HABITS }
        assertNull(it.undo)
        assertTrue(it.personal)
        assertTrue(it.what.startsWith("You usually switch Range Fade off"), it.what)
        // Not something that changed in how Jarvis works.
        val changed = Learnings.say(items, Learnings.Ask.CHANGED, LocalDate.of(2026, 9, 13), false)
        assertFalse(changed.contains("Range Fade"), changed)
    }

    private val ASKS = listOf(
        "do I usually disarm my bots after losses", "do i usually disarm range fade after two losing days",
        "do I switch off ORB after a losing day", "do I keep ORB armed after losses", "do i keep my bots on through losing days",
        "when do I usually disarm range fade", "after how many losing days do I switch off my bots",
        "what do I do with my bots after a losing day", "what do I usually do after a loss", "which bots do I keep armed",
        "which bots do I usually disarm after losses", "my arming habits", "what are my bot habits", "show me my disarming record",
        "how do I handle my bots after losses", "do I give up on my bots too fast", "loss ke baad main bot band karta hoon kya",
        "loss ke baad bots ke saath kya karta hoon")

    @Test fun askedInTheWaysBossSaysItAndNeverAnAction() {
        for (s in ASKS) {
            assertTrue(ArmHabits.asked(s), s)
            val p = Ask.parse(s)
            assertNull(p.command, s); assertNull(p.order, s)
            assertFalse(Bundle.acts(s), s)
        }
        for (s in listOf("disarm range fade", "switch off orb", "stop all bots", "how are my bots doing", "explain my bots trades today",
            "arm orb", "what did my bots do today", "turn off range fade after a loss"))
            assertFalse(ArmHabits.asked(s), s)
    }
}
