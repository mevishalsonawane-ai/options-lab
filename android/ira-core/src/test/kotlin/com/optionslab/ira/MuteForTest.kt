package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MuteForTest {
    private fun c(s: String) = Commands.parse(s)

    @Test fun muteFor() {
        assertEquals(30, c("be quiet for 30 minutes")?.takeIf { it.kind == Command.Kind.MUTE_FOR }?.number)
        assertEquals(60, c("Jarvis, mute for an hour")?.number)
        assertEquals(30, c("stay quiet for half an hour")?.number)
        assertEquals(20, c("20 minute chup raho")?.number)
        assertEquals(Command.Kind.MUTE, c("be quiet")?.kind)
        assertTrue(c("be quiet for 900 minutes")?.kind != Command.Kind.MUTE_FOR)
    }
}
