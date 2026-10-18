package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speed, round 2: the four gold books are read off the main thread at the start, and a change waits for that read. */
class GoldBooksTest : RobolectricTest() {
    @After fun down() { runBlocking { GoldPaper.replaceForTest(GoldPaper.Book()); GoldTrendPaper.replaceForTest(GoldTrendPaper.Book()) } }

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

    /** 18 Oct, review: the gold service's start and its ensure never wait for the books on the main thread; off it they do. */
    @Test fun onTheMainThreadNothingWaitsForTheBooks() {
        assertTrue("Robolectric runs the test on the main thread", android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        GoldBooks.init(context)
        val t0 = System.nanoTime()
        val now = GoldBooks.awaitOffMain()
        assertTrue("no wait on the main thread", (System.nanoTime() - t0) / 1_000_000 < 500)
        if (now) assertTrue("it says only what is read already", GoldBooks.loadedNow())
        var off = false
        Thread { off = GoldBooks.awaitOffMain() }.apply { start(); join(10_000) }
        assertTrue("off the main thread it waits for the read", off)
        assertTrue(GoldBooks.loadedNow())
        assertTrue(GoldBooks.awaitOffMain())
    }
}
