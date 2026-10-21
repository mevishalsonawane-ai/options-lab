package com.optionslab.app.ira

import com.optionslab.app.data.MemoryTrim
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.Concepts
import com.optionslab.ira.MemoryPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The concept book in the app ([ConceptLibrary], 15 Oct 2026): the bundled asset reads once and stays in memory, a trim lets
 * it go before anything position-related (and says the watch was untouched), and the next use reads it again. No context:
 * an empty book, never a crash.
 */
class ConceptLibraryTest : RobolectricTest() {
    @Test fun theBundledBookReadsOnceAndStays() {
        ConceptLibrary.drop()
        assertNull(ConceptLibrary.cached())
        val b = ConceptLibrary.load(context)
        assertEquals(40, b.concepts.size)
        assertSame(b, ConceptLibrary.cached())
        assertSame(b, ConceptLibrary.load(context))
        assertTrue(Concepts.answer(b, "what is a bull trap").orEmpty().startsWith("Bull trap and bear trap:"))
    }

    @Test fun aTrimLetsItGoAndTheNextUseReadsItAgain() {
        ConceptLibrary.load(context)
        MemoryTrim.resetForTest()
        val done = MemoryTrim.release(listOf(MemoryPlan.Item.CONCEPTS), MemoryPlan.COMPLETE)
        assertEquals(listOf(MemoryPlan.Item.CONCEPTS), done)
        assertNull(ConceptLibrary.cached())
        assertTrue(MemoryTrim.lastLine.orEmpty().contains("the concept book"))
        assertTrue(MemoryTrim.lastLine.orEmpty().endsWith("the position watch untouched"))
        // Every trim level that frees anything in the background frees the book too.
        assertTrue(MemoryPlan.Item.CONCEPTS in MemoryPlan.releases(MemoryPlan.BACKGROUND))
        assertEquals(40, ConceptLibrary.load(context).concepts.size)
    }

    @Test fun noContextIsAnEmptyBook() {
        ConceptLibrary.drop()
        assertEquals(Concepts.Book.EMPTY, ConceptLibrary.load(null))
        assertNull(ConceptLibrary.cached())
    }
}
