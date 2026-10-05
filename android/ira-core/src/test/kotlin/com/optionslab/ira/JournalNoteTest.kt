package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JournalNoteTest {
    @Test fun tagsThenNote() {
        assertEquals("Breakout, FOMO · chased the open", JournalNote.line(linkedSetOf("Breakout", "FOMO"), "chased the open"))
    }

    @Test fun tagsAlone() {
        assertEquals("Calm", JournalNote.line(setOf("Calm"), ""))
        assertEquals("Calm", JournalNote.line(setOf("Calm"), "   "))
    }

    @Test fun noteAloneAsBefore() {
        assertEquals(" · held too long", JournalNote.line(emptySet(), "held too long"))
    }

    @Test fun nothingToShow() {
        assertNull(JournalNote.line(emptySet(), ""))
        assertNull(JournalNote.line(emptyList(), " "))
    }
}
