package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoldPassTest {
    @Test fun nothingArmedOrHeldIsSkipped() {
        assertFalse(GoldPass.due(booksRead = true, anyArmed = false, anyHeld = false, trendWaitsFlip = false))
    }

    @Test fun anythingToWatchKeepsThePass() {
        assertTrue(GoldPass.due(booksRead = true, anyArmed = true, anyHeld = false, trendWaitsFlip = false), "an arm armed")
        assertTrue(GoldPass.due(booksRead = true, anyArmed = false, anyHeld = true, trendWaitsFlip = false), "a trade held, even with its arm off")
        assertTrue(GoldPass.due(booksRead = true, anyArmed = true, anyHeld = true, trendWaitsFlip = false), "armed and held")
        assertTrue(GoldPass.due(booksRead = true, anyArmed = false, anyHeld = false, trendWaitsFlip = true), "the trend's flip wait kept current")
    }

    @Test fun booksNotReadYetFailOpen() {
        assertTrue(GoldPass.due(booksRead = false, anyArmed = false, anyHeld = false, trendWaitsFlip = false))
    }
}
