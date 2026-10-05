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

class HabitsFadeTest {
    @Test fun aHabitNotAskedForAWeekIsNotOffered() {
        var c: HabitCounts = emptyMap()
        val k = Habits.key("how is nifty doing")!!
        repeat(5) { c = Habits.add(c, k, 10) }
        val today = java.time.LocalDate.of(2026, 10, 5)
        assertEquals(k, Habits.due(c, 10, emptySet(), mapOf(k to today.minusDays(2)), today))
        assertNull(Habits.due(c, 10, emptySet(), mapOf(k to today.minusDays(9)), today))
        assertNull(Habits.due(c, 10, emptySet(), emptyMap(), today))          // never dated: not offered
    }
}
