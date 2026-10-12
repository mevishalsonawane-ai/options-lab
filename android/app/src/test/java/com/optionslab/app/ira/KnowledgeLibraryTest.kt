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
}
