package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HabitsDueTest {
    @Test fun offeredOnlyWhenAStrongHabitAndOnce() {
        var c: HabitCounts = emptyMap()
        val k = Habits.key("what are the levels on banknifty")!!
        repeat(3) { c = Habits.add(c, k, 9) }
        assertNull(Habits.due(c, 9, emptySet()), "three times is not yet a habit")
        c = Habits.add(c, k, 9)
        assertEquals(k, Habits.due(c, 9, emptySet()))
        assertNull(Habits.due(c, 9, setOf(k)), "once a day")
        assertNull(Habits.due(c, 11, emptySet()), "another hour")
        // The account is never counted, so never offered.
        assertNull(Habits.key("what is my p&l"))
    }
}
