package com.optionslab.ira

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderBookTest {
    private val now = LocalDateTime.of(2026, 10, 5, 10, 0)
    private val kept = listOf(
        ReminderBook.Kept(1, "check Nifty", now.withHour(14).withMinute(30)),
        ReminderBook.Kept(2, "call the broker", now.withHour(15).withMinute(0)),
        ReminderBook.Kept(3, "log in to Zerodha", now.plusDays(1).withHour(9).withMinute(5), daily = true),
        ReminderBook.Kept(4, "check BankNifty", now.withHour(11).withMinute(0)),
    )

    @Test fun listIsAskedInPlainWords() {
        for (s in listOf("what reminders do I have", "Jarvis, which reminders are set?", "list my reminders", "show me my reminders",
            "any reminders?", "do I have any reminders today", "mere reminders kya hain", "my reminders")) assertTrue(ReminderBook.listAsked(s), s)
        for (s in listOf("remind me at 3 pm to check nifty", "cancel my reminders", "what are my alarms", "what's set for later",
            "what did I say about reminders", "how is nifty")) assertFalse(ReminderBook.listAsked(s), s)
    }

    @Test fun oneReminderIsNamedByTimeOrWords() {
        assertEquals(ReminderBook.Pick(LocalTime.of(14, 30), null), ReminderBook.cancelOne("cancel the 14:30 reminder"))
        assertEquals(ReminderBook.Pick(LocalTime.of(14, 30), null), ReminderBook.cancelOne("Jarvis, delete the reminder at 2:30 pm"))
        assertEquals(ReminderBook.Pick(LocalTime.of(15, 0), null), ReminderBook.cancelOne("remove the 3 pm reminder"))
        assertEquals(ReminderBook.Pick(LocalTime.of(15, 0), null), ReminderBook.cancelOne("3 baje wala reminder hata do"))
        assertEquals(ReminderBook.Pick(null, "nifty"), ReminderBook.cancelOne("cancel the reminder about Nifty"))
        assertEquals(ReminderBook.Pick(null, "call broker"), ReminderBook.cancelOne("delete the reminder to call the broker"))
        // All of them is Reminder.cancelAsked's; nothing named, or no reminder: not this.
        for (s in listOf("cancel my reminders", "cancel the reminder", "delete all reminders", "cancel all orders", "cancel the 2:30 order",
            "remind me at 3 pm to check nifty", "stop orb", "what reminders do I have")) assertNull(ReminderBook.cancelOne(s), s)
        assertFalse(Reminder.cancelAsked("cancel the 14:30 reminder"))
        assertFalse(Reminder.asked("cancel the 14:30 reminder"))
    }

    @Test fun theOneMeantIsFoundAndNamed() {
        assertEquals(listOf(1L), ReminderBook.matches(kept, ReminderBook.cancelOne("cancel the 14:30 reminder")!!).map { it.id })
        assertEquals(listOf(2L), ReminderBook.matches(kept, ReminderBook.cancelOne("delete the reminder to call the broker")!!).map { it.id })
        assertEquals(listOf(3L), ReminderBook.matches(kept, ReminderBook.cancelOne("cancel the 9:05 reminder")!!).map { it.id })
        // "Nifty" is a word in one only ("BankNifty" is not "Nifty").
        assertEquals(listOf(1L), ReminderBook.matches(kept, ReminderBook.cancelOne("cancel the reminder about nifty")!!).map { it.id })
        val two = ReminderBook.matches(kept, ReminderBook.Pick(null, "check"))
        assertEquals(listOf(4L, 1L), two.map { it.id })
        val said = ReminderBook.notOne(two, kept, now)
        assertTrue(said.startsWith("Boss, 2 reminders match - nothing was cancelled.") && "today at 11:00: check BankNifty" in said, said)
        assertTrue(ReminderBook.notOne(emptyList(), kept, now).startsWith("Boss, I found no reminder like that - nothing was cancelled."))
        assertEquals("You have no reminders set, Boss.", ReminderBook.notOne(emptyList(), emptyList(), now))
        assertEquals("cancel your reminder today at 14:30: check Nifty", ReminderBook.confirm(kept[0], now))
        assertEquals("Done, Boss: your reminder today at 14:30: check Nifty is cancelled.", ReminderBook.cancelled(kept[0], now))
    }

    /** Usefulness round 28: "cancel my reminders" asks first too, each named; the words after the Confirm say how many went. */
    @Test fun cancellingAllAsksFirstNamingEach() {
        assertEquals("cancel all 4 of your reminders - today at 11:00: check BankNifty; today at 14:30: check Nifty; today at 15:00: call the broker; " +
            "every trading day at 09:05: log in to Zerodha", ReminderBook.confirmAll(kept, now))
        assertEquals("cancel your reminder today at 15:00: call the broker", ReminderBook.confirmAll(listOf(kept[1]), now))
        assertEquals("Done, Boss: 4 reminders cancelled.", ReminderBook.cancelledAll(4))
        assertEquals("Done, Boss: 1 reminder cancelled.", ReminderBook.cancelledAll(1))
        assertEquals("Those reminders are gone already, Boss - nothing to cancel.", ReminderBook.cancelledAll(0))
        // Still the same words that ask it (the action reads as before): all of them, never one.
        for (s in listOf("cancel my reminders", "clear all reminders", "reminder hata do")) { assertTrue(Reminder.cancelAsked(s), s); assertNull(ReminderBook.cancelOne(s), s) }
    }

    @Test fun theListSaysEachWithItsTime() {
        assertEquals("You have no reminders set, Boss.", ReminderBook.list(emptyList(), now))
        assertEquals("One reminder, Boss: today at 15:00: call the broker.", ReminderBook.list(listOf(kept[1]), now))
        assertEquals("4 reminders, Boss: 1) today at 11:00: check BankNifty; 2) today at 14:30: check Nifty; 3) today at 15:00: call the broker; " +
            "4) every trading day at 09:05: log in to Zerodha.", ReminderBook.list(kept, now))
    }

    @Test fun theHubReachesThem() {
        val audit = CoverageTest()
        for (s in listOf("what reminders do I have", "list my reminders", "any reminders?", "mere reminders kya hain"))
            assertEquals("ReminderBook", audit.feature(s), s)
        for (s in listOf("cancel the 14:30 reminder", "delete the reminder about Nifty", "3 baje wala reminder hata do"))
            assertEquals("ReminderBook", audit.feature(s), s)
        assertEquals("Reminder", audit.feature("cancel my reminders"))
        assertEquals("Reminder", audit.feature("remind me at 3 pm to check nifty"))
    }
}
