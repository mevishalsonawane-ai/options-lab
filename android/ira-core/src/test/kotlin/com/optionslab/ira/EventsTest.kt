package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventsTest {
    private val today = LocalDate.of(2026, 10, 2)

    @Test fun builtInExpiriesAndTheOwners() {
        val e = Events.between(today, today.plusDays(30), mapOf("NIFTY" to listOf(LocalDate.of(2026, 10, 7))),
            listOf(Events.Event(LocalDate.of(2026, 10, 9), "RBI policy", owner = true)))
        assertEquals(listOf("NIFTY expiry", "RBI policy", "US Fed decision overnight (FOMC)"), e.map { it.name })
        assertEquals(LocalDate.of(2026, 10, 29), e.last().day)
        assertEquals("Event tomorrow: NIFTY expiry.", Events.line(Events.Event(today.plusDays(1), "NIFTY expiry"), today))
        assertEquals("Event Fri 9 Oct: RBI policy (added by you).", Events.line(e[1], today))
        assertTrue(Events.builtIn(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 1)).any { it.name == "Union Budget" })
    }

    @Test fun datesAreRead() {
        assertEquals(LocalDate.of(2026, 12, 5), Events.date("5 dec", today))
        assertEquals(LocalDate.of(2026, 12, 5), Events.date("december 5th", today))
        assertEquals(LocalDate.of(2027, 2, 1), Events.date("1 feb", today))
        assertEquals(LocalDate.of(2026, 10, 3), Events.date("tomorrow", today))
        assertEquals(LocalDate.of(2026, 11, 20), Events.date("2026-11-20", today))
        assertEquals(null, Events.date("someday", today))
        val c = Commands.parse("add event RBI policy on 5 Dec")!!
        assertEquals(Command.Kind.EVENT_ADD, c.kind); assertEquals("rbi policy", c.target)
        assertEquals(Command.Kind.EVENT_REMOVE, Commands.parse("remove event 2")!!.kind)
        assertEquals(setOf(Section.EVENTS), AppAnswers.sections("any events this week?"))
        assertEquals(setOf(Topic.ACCOUNT), Ask.parse("any events this week?").topics)
    }
}
