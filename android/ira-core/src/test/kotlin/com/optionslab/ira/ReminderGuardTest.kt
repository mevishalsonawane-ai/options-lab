package com.optionslab.ira

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review, 5 Oct: Boss's reminders said with something else to do, "everything set for later" asked first, and two words. */
class ReminderGuardTest {
    private val now = LocalDateTime.of(2026, 10, 5, 10, 0)

    @Test fun reminderWordsWithAnActionAreNotTheRemindersAlone() {
        for (s in listOf("cancel the 3 pm reminder and close my nifty put", "cancel the 3 pm reminder close my nifty put",
            "cancel my reminders and stop orb", "what reminders do I have and close everything", "cancel the 2:30 reminder, then exit all",
            "list my reminders then kill switch on")) assertTrue(Bundle.reminderAndMore(s), s)
        // The reminder's own words are never the action; a question beside it is not one either.
        for (s in listOf("cancel the 3 pm reminder", "cancel the reminder about nifty", "delete the reminder to call the broker",
            "delete the reminder to buy nifty", "cancel the reminder to exit all trades", "cancel the reminder to stop orb",
            "what reminders do I have", "cancel my reminders", "3 baje wala reminder hata do", "delete the reminder about nifty and banknifty",
            "what reminders do I have and what's my pnl", "stop orb and close everything", "how is nifty",
            "set a reminder: exit all at 3", "set a reminder:exit all at 3", "set a reminder: to exit all at 3",
            "set a reminder \"close my nifty put\"")) assertFalse(Bundle.reminderAndMore(s), s)
        // A colon's reminder with something else to do after it is still both.
        assertTrue(Bundle.reminderAndMore("set a reminder: check nifty, and close my nifty put"))
        // Only a reminder being set takes the words after a colon or a quote as its own; cancelling one does not (review, 5 Oct).
        for (s in listOf("delete the reminder: exit all", "remove the reminder: square off everything",
            "cancel the 3 pm reminder: close my nifty put", "cancel the reminder \"exit all\"", "reminder hata do: exit all"))
            assertTrue(Bundle.reminderAndMore(s), s)
        for (s in listOf("set a reminder: exit all at 3", "delete the reminder: to exit all", "remind me at 3: close my nifty put"))
            assertFalse(Bundle.reminderAndMore(s), s)
        assertTrue(Bundle.REMINDER_AND_MORE.startsWith("Boss,") && "Nothing was done" in Bundle.REMINDER_AND_MORE)
    }

    @Test fun cancellingEverythingSetForLaterNamesEachFirst() {
        val rems = listOf(ReminderBook.Kept(1, "check Nifty", now.withHour(14).withMinute(30)))
        val cmds = listOf("start orb" to now.plusDays(1).withHour(9).withMinute(20), "stop all strategies" to now.withHour(15).withMinute(0))
        assertEquals("cancel your reminder today at 14:30: check Nifty, and cancel all 2 commands set for later - \"stop all strategies\" today at 15:00; " +
            "\"start orb\" tomorrow (Tue 6 Oct) at 09:20", Later.confirmClear(rems, cmds, now))
        assertEquals("cancel the command set for later - \"start orb\" tomorrow (Tue 6 Oct) at 09:20", Later.confirmClear(emptyList(), cmds.take(1), now))
        assertEquals("cancel your reminder today at 14:30: check Nifty", Later.confirmClear(rems, emptyList(), now))
        assertNull(Later.confirmClear(emptyList(), emptyList(), now))
        assertEquals("Done, Boss: 1 reminder and 2 commands set for later cancelled.", Later.clearedSaid(1, 2))
        assertEquals("Done, Boss: 3 reminders cancelled.", Later.clearedSaid(3, 0))
        assertTrue(Later.clearedSaid(0, 0).startsWith("Those are gone already, Boss"))
    }

    @Test fun everyReminderAndAReminderForAnHour() {
        assertTrue(Reminder.cancelAsked("cancel every reminder"))
        assertTrue(Reminder.cancelAsked("delete every reminder"))
        assertNull(ReminderBook.cancelOne("cancel every reminder"))
        assertEquals(ReminderBook.Pick(LocalTime.of(9, 0), null), ReminderBook.cancelOne("cancel reminder for 9"))
        assertEquals(ReminderBook.Pick(LocalTime.of(14, 0), null), ReminderBook.cancelOne("cancel the reminder for 2"))
        assertEquals(ReminderBook.Pick(LocalTime.of(14, 30), null), ReminderBook.cancelOne("cancel the reminder for 2:30 pm"))
    }
}
