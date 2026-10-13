package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.MemoryPlan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a memory trim lets go (memory audit, 14 Oct, item 9), piece by piece: the stream's paths and candles of instruments
 * nothing follows (never a followed one, however quiet), the diary's lines in memory (written first, read back whole), and
 * the trim's own line; one piece failing never stops the rest.
 */
class MemoryTrimTest : RobolectricTest() {
    @Before fun up() { MemoryTrim.resetForTest(); KiteStream.resetForTest() }
    @After fun down() { MemoryTrim.resetForTest(); KiteStream.want("test-owner", emptyList()); KiteStream.resetForTest() }

    private val stamp = System.currentTimeMillis() / 1000

    @Test fun theStreamLetsGoOnlyWhatNothingFollows() {
        val followed = 1_001L; val quietFollowed = 1_002L; val unfollowed = 1_003L; val unfollowedQuiet = 1_004L
        KiteStream.want("test-owner", listOf(followed, quietFollowed))
        val now = System.currentTimeMillis()
        val old = now - 3 * 3_600_000L
        KiteStream.feedForTest(listOf(KiteTicks.Tick(quietFollowed, 50.0, exchangeTime = stamp - 3 * 3600), KiteTicks.Tick(unfollowedQuiet, 60.0, exchangeTime = stamp - 3 * 3600)), old)
        KiteStream.feedForTest(listOf(KiteTicks.Tick(followed, 100.0, exchangeTime = stamp), KiteTicks.Tick(unfollowed, 70.0, exchangeTime = stamp)), now)
        assertEquals(4 to 4, KiteStream.keptCounts())
        val (paths, candles) = KiteStream.pruneIdle(now)
        // The unfollowed ones' paths go; only the unfollowed quiet one's candles go (the other ticked just now).
        assertEquals(2, paths)
        assertEquals(1, candles)
        assertNotNull(KiteStream.highSince(followed, now - 60_000))
        assertNotNull("followed, however quiet: kept", KiteStream.candles.latest(quietFollowed))
        assertNotNull("followed, however quiet: its path kept", KiteStream.highSince(quietFollowed, old - 1_000))
        assertNull(KiteStream.highSince(unfollowed, now - 60_000))
        assertNull(KiteStream.candles.latest(unfollowedQuiet))
        assertNotNull(KiteStream.candles.latest(unfollowed))
        // Their last prices stay (the quote's answer; unsubscribing is the stream's own business).
        assertNotNull(KiteStream.seen(unfollowed))
        // Nothing streamed: nothing to do.
        KiteStream.resetForTest()
        assertEquals(0 to 0, KiteStream.pruneIdle(now))
    }

    @Test fun theDiaryWritesItsLinesAndLetsThemGoThenReadsThemBackWhole() {
        Diag.record("test", "line one")
        Diag.record("test", "line two")
        Diag.flush()
        assertTrue(Diag.linesInMemory() >= 2)
        Diag.record("test", "line three, not yet written when the trim came")
        Diag.trimMemory()
        Diag.flush()
        assertEquals(0, Diag.linesInMemory())
        val lines = Diag.lines()
        for (w in listOf("line one", "line two", "line three")) assertTrue(w, lines.any { w in it })
    }

    @Test fun eachPieceIsLetGoAndTheLineSaysTheWatchWasNotTouched() {
        val all = MemoryPlan.releases(MemoryPlan.COMPLETE)
        val done = MemoryTrim.release(all, MemoryPlan.COMPLETE)
        assertEquals(all, done)
        val line = MemoryTrim.lastLine.orEmpty()
        assertTrue(line, line.startsWith("background trim (level 80): let go the language model, the research index"))
        assertTrue(line, line.endsWith("the position watch untouched"))
        // A level that lets nothing go: nothing runs, nothing counted.
        MemoryTrim.resetForTest()
        MemoryTrim.onTrim(0)
        assertNull(MemoryTrim.lastLine)
        assertEquals(0L, MemoryTrim.lastAt)
    }

    @Test fun theModelLetsGoSoonerAndARewriteNeverLoadsItWhenToldNot() {
        // 3 minutes idle (was 10), a minute in market hours or while Jarvis listens (ira-core's ModelMemory).
        assertEquals(3 * 60_000L, com.optionslab.app.ira.IraModel.IDLE_MS)
        // Nothing loaded (no model on this phone): the trim's release is a no-op and never throws.
        com.optionslab.app.ira.IraModel.releaseForMemory()
        assertEquals(false, com.optionslab.app.ira.IraModel.state.value.loaded)
        // A screen-only rewrite told not to load the model: the draft stands (null), nothing loaded.
        assertNull(kotlinx.coroutines.runBlocking { com.optionslab.app.ira.IraModel.rewrite("q", listOf("a fact"), "the draft", coldLoad = false) })
        assertNull(kotlinx.coroutines.runBlocking { com.optionslab.app.ira.IraModel.rewriteWhenFree("q", listOf("a fact"), "the draft") })
        assertEquals(false, com.optionslab.app.ira.IraModel.state.value.loaded)
    }
}
