package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorrectionsTest {
    @Test fun learnsAQuestionFromARephrasing() {
        val l = Corrections.learn("how's the nifty boy doing", "how is nifty doing")!!
        assertEquals("how s the nifty boy doing", l.wrong)
        assertEquals("how is nifty doing", Corrections.apply("Jarvis, how's the nifty boy doing", listOf(l)))
        // Nearly the same words, again not understood (another mishearing): read the same way.
        val m = Corrections.learn("how is the nifti boi doing", "how is nifty doing")!!
        assertEquals("how is nifty doing", Corrections.apply("how is the nifti boi doin", listOf(m)))
        // Words already understood are left alone.
        assertNull(Corrections.apply("how is banknifty doing", listOf(l)))
    }

    @Test fun neverLearnsOrRewritesIntoAnAction() {
        assertNull(Corrections.learn("blah blah", "stop all strategies"), "a command is never learned")
        assertNull(Corrections.learn("buy nifty", "how is nifty"), "a misheard command is never turned into a question")
        assertNull(Corrections.learn("xyz", "buy 1 lot of nifty 24000 call"))
        val bad = Corrections.Learned("go live now please", "switch to live mode")
        assertNull(Corrections.apply("go live now please", listOf(bad)), "a learned wording that would act is never used")
        assertNull(Corrections.apply("stop all strategies", listOf(Corrections.Learned("stop all strategies", "how is nifty"))))
    }

    @Test fun commandsAndListing() {
        assertEquals(Command.Kind.LEARN_RESET, Commands.parse("Jarvis, forget what you learned")?.kind)
        assertTrue(Corrections.lines(listOf(Corrections.Learned("a b", "how is nifty"))).last().contains("\"a b\" means \"how is nifty\""))
        assertTrue(Corrections.similarity("abc", "abd") > 0.6 && Corrections.similarity("abc", "xyz") < 0.1)
    }
}
