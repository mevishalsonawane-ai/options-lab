package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitsTest {
    @Test fun onlyMarketQuestionsAreCounted() {
        assertEquals("BANKNIFTY|levels", Habits.key("what are the levels on banknifty"))
        assertEquals("NIFTY|overview", Habits.key("how is nifty doing"))
        assertNull(Habits.key("stop all strategies"))
        assertNull(Habits.key("buy 1 lot nifty 24000 ce"))
        assertNull(Habits.key("show my positions"))
        assertNull(Habits.key("how was your day"))
        assertEquals("what are the levels on BankNifty", Habits.question("BANKNIFTY|levels"))
        assertEquals("BANKNIFTY|levels", Habits.key(Habits.question("BANKNIFTY|levels")!!))
    }

    @Test fun theUsualIsWhatBossAsksAroundNow() {
        var c: HabitCounts = emptyMap()
        repeat(4) { c = Habits.add(c, "BANKNIFTY|levels", 9) }
        repeat(6) { c = Habits.add(c, "NIFTY|overview", 15) }
        assertEquals("BANKNIFTY|levels", Habits.usual(c, 10))
        assertEquals("NIFTY|overview", Habits.usual(c, 15))
        assertEquals("NIFTY|overview", Habits.usual(c, 20), "nothing near: the most asked overall")
        assertNull(Habits.usual(emptyMap(), 9))
        assertTrue(Habits.asked("Jarvis, the usual"))
        assertTrue(Habits.asked("my usual please"))
        assertTrue(!Habits.asked("the usual levels on nifty"))
    }
}
