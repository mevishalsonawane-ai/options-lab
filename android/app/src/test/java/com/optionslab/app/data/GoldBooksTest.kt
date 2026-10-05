package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speed, round 2: the four gold books are read off the main thread at the start, and a change waits for that read. */
class GoldBooksTest : RobolectricTest() {
    @After fun down() = runBlocking { GoldPaper.replaceForTest(GoldPaper.Book()); GoldTrendPaper.replaceForTest(GoldTrendPaper.Book()) }

    @Test fun theSavedBooksComeBackAndAChangeIsMadeOnThem() = runBlocking {
        GoldBooks.init(context)
        GoldBooks.awaitLoaded()
        assertTrue(GoldBooks.ready.value)
        GoldPaper.replaceForTest(GoldPaper.Book(armed = true, lots = 0.05, status = GoldPaper.WAITING))
        GoldTrendPaper.setArmed(true)
        GoldBooks.init(context)                         // the app starts again: the books are read in the background
        GoldPaper.setLots(0.10)                          // waits for the read, then changes the saved book
        assertTrue(GoldBooks.ready.value)
        GoldBooks.init(context)
        GoldBooks.awaitBlocking()
        val b = GoldPaper.book.value
        assertTrue("the switch is kept", b.armed)
        assertEquals(0.10, b.lots, 1e-9)
        assertTrue(GoldTrendPaper.book.value.armed)
    }
}
