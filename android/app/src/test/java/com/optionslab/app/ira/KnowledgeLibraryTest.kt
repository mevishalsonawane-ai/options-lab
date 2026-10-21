package com.optionslab.app.ira

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.Knowledge
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The knowledge pack in the app ([KnowledgeLibrary]): the bundled asset reads and indexes, a research question is answered
 * from its facts with them as the answer's facts, a locked phone gets none of Boss's own figures, and the model's notes come
 * only once the pack is in memory.
 */
class KnowledgeLibraryTest : RobolectricTest() {
    @Test fun theBundledPackAnswersAResearchQuestionFromItsFacts() {
        // Read directly first: a failure here shows its own exception (the library keeps going without the pack).
        val pack = context.assets.open(KnowledgeLibrary.ASSET).use { Knowledge.read(it) }
        assertTrue(pack.facts.size > 2_000)
        val ix = checkNotNull(KnowledgeLibrary.index(context))
        assertTrue(ix.size > 2_000)
        val (said, facts) = KnowledgeLibrary.answer(context, "what did research say about ORB", locked = false)
        assertTrue(said, said.startsWith("From the research, Boss:"))
        assertTrue(facts.isNotEmpty() && facts.all { said.contains(it) })
        val notes = KnowledgeLibrary.modelNotes("is liquidity profitable")
        assertTrue(notes.isNotEmpty())
        assertTrue(KnowledgeLibrary.modelNotes("nifty kaha hai").isEmpty())
    }

    @Test fun aLockedPhoneGetsNoneOfBossOwnFigures() {
        val ix = checkNotNull(KnowledgeLibrary.index(context))
        val own = ix.pack.facts.filter { it.personal }.map { Knowledge.line(it) }.toSet()
        for (q in listOf("which strategy made boss's 5k days according to research", "research h22 liquidity first day rules")) {
            val (_, facts) = KnowledgeLibrary.answer(context, q, locked = true)
            assertTrue(q, facts.none { it in own })
        }
    }

    // ---- memory audit (14 Oct): the prebuilt index, opened lazily, dropped on a trim ----

    @Test fun theApksPrebuiltIndexIsOpenedAndGivesTheSameAnswersAsTheOneBuiltFromThePack() {
        KnowledgeLibrary.drop()
        val ix = checkNotNull(KnowledgeLibrary.index(context))
        // The build put the index file beside the pack: it is what was opened (no unzip, no JSON parse at start).
        org.junit.Assert.assertEquals("the APK's prebuilt index", KnowledgeLibrary.madeFrom)
        val built = context.assets.open(KnowledgeLibrary.ASSET).use { Knowledge.Index(Knowledge.read(it)) }
        org.junit.Assert.assertEquals(built.size, ix.size)
        for (q in listOf("what did research say about ORB", "is liquidity profitable", "how does banknifty behave on expiry after a gap down", "research kya kehta hai liquidity ke baare me"))
            for (locked in listOf(false, true))
                org.junit.Assert.assertEquals(q, Knowledge.search(built, q, 3, locked).map { it.fact.id to it.score }, Knowledge.search(ix, q, 3, locked).map { it.fact.id to it.score })
    }

    @Test fun theSharedSectionIsReadWithoutOpeningTheIndexAndATrimDropsTheIndex() {
        KnowledgeLibrary.drop()
        org.junit.Assert.assertNull(KnowledgeLibrary.cached())
        val (research, version) = checkNotNull(KnowledgeLibrary.shared(context))
        assertTrue(checkNotNull(research).items.size > 100)
        assertTrue(version.isNotEmpty())
        org.junit.Assert.assertNull("the shared section never opens the index", KnowledgeLibrary.cached())
        // Before it is open: the model's notes are none and nothing waits for it.
        assertTrue(KnowledgeLibrary.modelNotes("is liquidity profitable").isEmpty())
        val ix = checkNotNull(KnowledgeLibrary.index(context))
        org.junit.Assert.assertSame(ix, KnowledgeLibrary.cached())
        org.junit.Assert.assertEquals(research.items, checkNotNull(KnowledgeLibrary.shared(context)).first!!.items)
        com.optionslab.app.data.MemoryTrim.release(listOf(com.optionslab.ira.MemoryPlan.Item.KNOWLEDGE_INDEX), com.optionslab.ira.MemoryPlan.BACKGROUND)
        org.junit.Assert.assertNull(KnowledgeLibrary.cached())
        org.junit.Assert.assertNull(KnowledgeLibrary.madeFrom)
        // The next question opens it again.
        val (said, _) = KnowledgeLibrary.answer(context, "what did research say about ORB", locked = false)
        assertTrue(said, said.startsWith("From the research, Boss:"))
        org.junit.Assert.assertNotNull(KnowledgeLibrary.cached())
    }

    @Test fun noContextNoPack() {
        KnowledgeLibrary.drop()
        org.junit.Assert.assertNull(KnowledgeLibrary.index(null))
        org.junit.Assert.assertNull(KnowledgeLibrary.shared(null))
        org.junit.Assert.assertEquals(Knowledge.NOT_LOADED, KnowledgeLibrary.answer(null, "is liquidity profitable", locked = false).first)
    }
}
