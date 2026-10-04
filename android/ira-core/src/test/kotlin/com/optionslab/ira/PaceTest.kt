package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaceTest {
    private fun k(s: String) = Commands.parse(s)?.kind

    @Test fun pace() {
        for (s in listOf("speak slower", "Jarvis, slow down", "talk slowly", "thoda dheere bolo")) assertEquals(Command.Kind.PACE_SLOWER, k(s), s)
        for (s in listOf("speak faster", "speed up", "jaldi bolo")) assertEquals(Command.Kind.PACE_FASTER, k(s), s)
        assertEquals(Command.Kind.PACE_NORMAL, k("normal speed"))
        assertEquals(Command.Kind.PACE_NORMAL, k("speak normal"))
        // Only the voice: "slow down the bot" or a question is not a pace change.
        assertTrue(k("why is nifty slow") != Command.Kind.PACE_SLOWER)
        assertEquals(Toolbox.Need.CONFIRM, Toolbox.of(Command.Kind.PACE_SLOWER).need)
    }
}
