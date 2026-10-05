package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PromptSlotsTest {
    private val now = LocalDateTime.of(2026, 10, 5, 10, 0)

    @Test fun eachKindOfPromptKeepsItsOwnSlot() {
        val s = PromptSlots()
        val intents = s.pick(Intents.prompt("pause the bots"))
        val chat = s.pick(Chat.prompt("how was your day", now))
        val writer = s.pick(Writer.prompt("how is nifty", listOf("Nifty 25,100"), "Nifty is at 25,100."))
        assertEquals(3, setOf(intents, chat, writer).size)
        // The next question: each prompt finds the slot holding its own instructions, whatever ran in between.
        assertEquals(intents, s.pick(Intents.prompt("what did the fiis do")))
        assertEquals(chat, s.pick(Chat.prompt("do you like music", now, listOf("how was your day" to "Good, Boss."))))
        assertEquals(writer, s.pick(Writer.prompt("banknifty levels", listOf("BankNifty 56,000"), "BankNifty is at 56,000.")))
        assertEquals(intents, s.pick(Intents.prompt("close everything")))
    }

    @Test fun aNewKindTakesAnEmptySlotThenTheOneUnusedLongest() {
        val s = PromptSlots(size = 2, minShared = 10)
        val a = s.pick("A".repeat(40) + " first")
        val b = s.pick("B".repeat(40) + " first")
        assertNotEquals(a, b)
        assertEquals(a, s.pick("A".repeat(40) + " again"))      // a used last: b is the oldest now
        assertEquals(b, s.pick("C".repeat(40)))
        assertEquals(a, s.pick("D".repeat(40)))                 // a is now the oldest
    }

    @Test fun aShortSharedBeginningIsNotAMatch() {
        val s = PromptSlots(size = 3)
        val w = s.pick(Writer.prompt("q", listOf("x 1"), "d 1."))
        // Writer and Intents both start "<|im_start|>system\nYou ": too little to share a slot.
        assertNotEquals(w, s.pick(Intents.prompt("stop strategy 2")))
    }

    @Test fun clearedAfterUnload() {
        val s = PromptSlots(size = 2)
        s.pick("x".repeat(100)); s.pick("y".repeat(100))
        s.clear()
        assertEquals(0, s.pick("z".repeat(100)))
    }

    @Test fun warmedBeginningsAreWhatTheQuestionsStartWith() {
        val (intents, chat) = PromptWarm.prefixes(now)
        assertTrue(Intents.prompt("pause the bots").startsWith(intents))
        assertTrue(Chat.prompt("how was your day", now).startsWith(chat))
        assertTrue(Chat.prompt("do you like music", now, listOf("how was your day" to "Good, Boss.")).startsWith(chat))
        // Each holds the whole fixed part: the list of lines and the instructions.
        assertTrue(intents.contains(Intents.LINES.last()) && intents.endsWith("REQUEST:"))
        assertTrue(chat.contains("Boss") && chat.endsWith("Boss:"))
    }

    @Test fun warmedSlotsAreTheOnesTheQuestionsFind() {
        val s = PromptSlots(size = 4)
        val warm = PromptWarm.prefixes(now).map { p -> assertFalse(s.holds(p)); s.pick(p).also { assertTrue(s.holds(p)) } }
        assertEquals(2, warm.toSet().size)
        assertEquals(warm[0], s.pick(Intents.prompt("what did the fiis do")))
        assertEquals(warm[1], s.pick(Chat.prompt("how was your day", now)))
        // Still held after the questions (each slot's last prompt begins with its warmed part): not warmed twice.
        PromptWarm.prefixes(now).forEach { assertTrue(s.holds(it)) }
        s.clear()
        assertFalse(s.holds(PromptWarm.prefixes(now)[0]))
    }
}
