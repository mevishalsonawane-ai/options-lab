package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NicknamesTest {
    private val t0 = LocalDateTime.of(2026, 10, 5, 10, 0)
    private val arms = listOf("ORB Nifty", "ORB BankNifty", "Range Fade")

    private fun learnedScalper(): Nicknames.Log {
        val asked = assertNotNull(Nicknames.asking(Command.Kind.STOP_ONE, "the scalper", arms, t0))
        val n = assertNotNull(Nicknames.picked(asked, Command.Kind.STOP_ONE, 1, arms, t0.plusMinutes(1)))
        return Nicknames.heard(Nicknames.Log(), n)
    }

    @Test fun pickAfterWhichOneTeachesTheWordsForThatOneArm() {
        val log = learnedScalper()
        val n = log.notes.single()
        assertEquals("scalper", n.words)
        assertEquals("ORB BankNifty", n.name)
        assertEquals(Nicknames.Family.ARM, n.family)
        assertEquals(1, Nicknames.resolve(log, Nicknames.Family.ARM, "scalper", arms))
        assertEquals(1, Nicknames.resolve(log, Nicknames.Family.ARM, "the scalper", arms.reversed().reversed()))
        // The order of the list may change: the name decides.
        assertEquals(0, Nicknames.resolve(log, Nicknames.Family.ARM, "scalper", listOf("ORB BankNifty", "ORB Nifty")))
        assertTrue(Nicknames.took(assertNotNull(Nicknames.note(log, Nicknames.Family.ARM, "scalper"))).contains("ORB BankNifty"))
        assertTrue(Nicknames.learned(n).contains("Boss"))
        assertTrue(Nicknames.learned(n).contains(Nicknames.UNDO))
    }

    @Test fun neverWiderThanOneAndNeverAcrossFamilies() {
        val log = learnedScalper()
        // The arm gone, or two with that name: Jarvis asks again.
        assertNull(Nicknames.resolve(log, Nicknames.Family.ARM, "scalper", listOf("ORB Nifty", "Range Fade")))
        assertNull(Nicknames.resolve(log, Nicknames.Family.ARM, "scalper", listOf("ORB BankNifty", "ORB BankNifty")))
        // An arm's nickname never names a position.
        assertNull(Nicknames.resolve(log, Nicknames.Family.POSITION, "scalper", listOf("ORB BankNifty")))
        // Words that would widen it are never a nickname.
        assertNull(Nicknames.key("all of them"))
        assertNull(Nicknames.key("both"))
        assertNull(Nicknames.key("everything"))
        assertNull(Nicknames.key("2"))
        assertNull(Nicknames.key("the strategy"))
        assertNull(Nicknames.key("one two three four five"))
        assertNull(Nicknames.key("pin 4321"))
        assertNull(Nicknames.asking(Command.Kind.STOP_ONE, "my password is abc", arms, t0))
    }

    @Test fun onlyAPickOfThatVeryListSoonAfter() {
        val asked = assertNotNull(Nicknames.asking(Command.Kind.STOP_ONE, "scalper", arms, t0))
        assertNull(Nicknames.picked(asked, Command.Kind.STOP_ONE, 1, arms, t0.plusMinutes(Nicknames.REACT_MINUTES + 1)))
        assertNull(Nicknames.picked(asked, Command.Kind.CLOSE_ONE, 1, arms, t0.plusMinutes(1)))
        assertNull(Nicknames.picked(asked, Command.Kind.STOP_ONE, 1, arms + "Gap Fill", t0.plusMinutes(1)))
        assertNull(Nicknames.picked(asked, Command.Kind.STOP_ONE, 9, arms, t0.plusMinutes(1)))
        assertNull(Nicknames.picked(null, Command.Kind.STOP_ONE, 1, arms, t0.plusMinutes(1)))
        // A start after "which one?" for a stop is another command, not his pick: nothing kept.
        assertNull(Nicknames.picked(asked, Command.Kind.START_ONE, 1, arms, t0.plusMinutes(1)))
        // Asked for a start, picked by a start: kept.
        val started = assertNotNull(Nicknames.asking(Command.Kind.START_ONE, "scalper", arms, t0))
        assertNotNull(Nicknames.picked(started, Command.Kind.START_ONE, 1, arms, t0.plusMinutes(1)))
        assertNull(Nicknames.picked(started, Command.Kind.STOP_ONE, 1, arms, t0.plusMinutes(1)))
        // A command that names no single arm or position is never asked for.
        assertNull(Nicknames.asking(Command.Kind.STOP_ALL, "scalper", arms, t0))
        // Never kept when Boss's words already are that name.
        val same = assertNotNull(Nicknames.asking(Command.Kind.STOP_ONE, "range fade", arms, t0))
        assertNull(Nicknames.picked(same, Command.Kind.STOP_ONE, 2, arms, t0.plusMinutes(1)))
    }

    @Test fun positionsKeyWithoutTheirQuantity() {
        val pos = listOf("paper NIFTY25O2124500PE (75)", "paper NIFTY25O2124600PE (75)")
        val asked = assertNotNull(Nicknames.asking(Command.Kind.CLOSE_ONE, "my hedge", pos, t0))
        val n = assertNotNull(Nicknames.picked(asked, Command.Kind.CLOSE_ONE, 1, pos, t0.plusMinutes(2)))
        assertEquals("hedge", n.words)
        assertEquals("paper NIFTY25O2124600PE", n.name)
        val log = Nicknames.heard(Nicknames.Log(), n)
        assertEquals(0, Nicknames.resolve(log, Nicknames.Family.POSITION, "the hedge", listOf("paper NIFTY25O2124600PE (150)", "paper X (1)")))
    }

    @Test fun theSameWordsKeepOnlyTheNewestPick() {
        val log = learnedScalper()
        val asked = assertNotNull(Nicknames.asking(Command.Kind.STOP_ONE, "scalper", arms, t0.plusHours(1)))
        val n = assertNotNull(Nicknames.picked(asked, Command.Kind.STOP_ONE, 2, arms, t0.plusHours(1)))
        val log2 = Nicknames.heard(log, n)
        assertEquals(1, log2.notes.size)
        assertEquals(2, Nicknames.resolve(log2, Nicknames.Family.ARM, "scalper", arms))
    }

    @Test fun askedAndUndo() {
        listOf("what nicknames do i use", "which nicknames do you know for my arms", "what do i call my bots", "show my nicknames",
            "mere bots ke nicknames kya hain", "jarvis what nicknames have you learned").forEach {
            assertEquals(Nicknames.Request.WHICH, Nicknames.asked(it), it)
        }
        listOf(Nicknames.UNDO, "forget my nicknames", "clear the nicknames", "forget the names i use for my positions", "mere nicknames bhool jao").forEach {
            assertEquals(Nicknames.Request.RESET, Nicknames.asked(it), it)
        }
        listOf("stop the scalper", "stop strategy 2", "what's the trend", "forget the word scalper", "show my positions", "close my hedge").forEach {
            assertNull(Nicknames.asked(it), it)
        }
        assertFalse(Nicknames.UNDO.startsWith("stop"))
        // The undo is never a command: no strategy called "my nicknames".
        assertNull(Ask.parse(Nicknames.UNDO).command)
        assertNull(Ask.parse("forget my nicknames").command)
        assertTrue(Nicknames.say(Nicknames.Log()).contains("Boss"))
        assertTrue(Nicknames.say(learnedScalper()).contains("ORB BankNifty"))
        assertTrue(Nicknames.sayReset(learnedScalper()).startsWith("Done, Boss"))
        assertTrue(Nicknames.forget().notes.isEmpty())
    }

    @Test fun ledgerAndTheWeeksUndo() {
        val log = learnedScalper()
        val now = t0.plusHours(2)
        val item = Learnings.items(Learnings.Inputs(nicknames = log), now).single { it.area == Learnings.Area.NICKNAMES }
        assertEquals(Nicknames.UNDO, item.undo)
        assertTrue(item.personal)
        val u = Learnings.undo(Learnings.Inputs(nicknames = log), now)
        assertEquals(1, u.nicknames.size)
        assertFalse(u.empty)
        assertTrue(Learnings.offer(u).contains("scalper"))
        assertTrue(Nicknames.forgetWeek(log, now.toLocalDate()).notes.isEmpty())
        // Learned more than a week ago: kept by the week's undo.
        val old = Nicknames.Log(listOf(log.notes.single().copy(at = t0.minusDays(10))))
        assertTrue(Learnings.undo(Learnings.Inputs(nicknames = old), now).nicknames.isEmpty())
        assertEquals(1, Nicknames.forgetWeek(old, now.toLocalDate()).notes.size)
    }
}
