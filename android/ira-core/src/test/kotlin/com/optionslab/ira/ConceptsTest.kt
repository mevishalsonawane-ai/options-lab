package com.optionslab.ira

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The concept book ([Concepts], 15 Oct 2026): the shipped asset reads whole (40 concepts, each with a source and an honest
 * verdict); an empty, broken or foreign file reads as empty; Boss's ways of asking ("what is gamma exposure", "bull trap kya
 * hai", "is this a fake breakout", one letter off) find the right concept; a figure wanted now or his own account does not.
 */
class ConceptsTest {
    private val book: Concepts.Book by lazy {
        val f = listOf(File("../app/src/main/assets/${Concepts.ASSET}"), File("android/app/src/main/assets/${Concepts.ASSET}")).first { it.isFile }
        Concepts.parse(f.readText())
    }

    private fun id(q: String): String? = Concepts.asked(q)?.let { Concepts.find(book, it) }?.id

    @Test fun theShippedBookIsWhole() {
        assertEquals(40, book.concepts.size)
        assertEquals("2026-10-15", book.asOf)
        assertTrue("underlying" in book.houseRule.lowercase() || "index" in book.houseRule.lowercase())
        for (c in book.concepts) {
            assertTrue(c.what.isNotBlank() && c.verdict.isNotBlank() && c.sourceText.isNotBlank(), c.id)
            assertTrue(c.source in setOf("underlying", "chain", "contract", "market", "orderflow"), c.id)
            assertTrue(c.evidence.isNotEmpty() && c.evidence.all { it.sample.isNotBlank() }, c.id)
        }
        // Honest: chain concepts are never the default signal, and none claims to beat Solo 2.0.
        book.concepts.filter { it.source == "chain" }.forEach { assertTrue("never the signal by default" in it.sourceText, it.id) }
        assertTrue(book.concepts.none { "beats solo" in it.verdict.lowercase() })
        assertTrue("nothing that beats it" in book.summary)
    }

    @Test fun anEmptyOrBrokenFileIsAnEmptyBook() {
        for (bad in listOf("", "   ", "{", "[]", "null", "{\"schema\":2,\"concepts\":[]}", "{\"concepts\":[{\"id\":\"x\",\"name\":\"X\",\"what\":\"w\"}]}"))
            assertEquals(Concepts.Book.EMPTY, Concepts.parse(bad), bad)
        assertNull(Concepts.find(Concepts.Book.EMPTY, "what is gamma"))
        assertNull(Concepts.answer(Concepts.Book.EMPTY, "what is gamma"))
    }

    @Test fun aBrokenEntryIsLeftOutAndMissingFieldsAreEmpty() {
        val b = Concepts.parse("""{"schema":1,"asOf":"d","concepts":[1,{"id":"","name":"A","what":"w"},{"id":"a","name":"A"},
            {"id":"b","name":"B thing","what":"w","evidence":[1,{"study":"S"}],"aka":["bee",2]}]}""")
        assertEquals(listOf("b"), b.concepts.map { it.id })
        val c = b.concepts.single()
        assertEquals(listOf("bee"), c.aka)
        assertEquals(listOf(Concepts.Evidence("S", "", "", "")), c.evidence)
        assertEquals("", c.verdict)
        assertEquals("B thing: w.", Concepts.say(c))
        assertEquals(listOf("w", "S"), Concepts.lines(c))
        assertEquals(c, b.byId("b")); assertNull(b.byId("zz"))
    }

    @Test fun theQuestionsBossAsks() {
        assertEquals("gex", id("What is gamma exposure?"))
        assertEquals("gamma", id("what is gamma"))
        assertEquals("traps", id("What is a bull trap?"))
        assertEquals("traps", id("explain the bear trap"))
        assertEquals("fake_breakout", id("what is a fake breakout?"))
        assertEquals("fake_breakout", id("what is a fakeout"))
        assertEquals("stop_hunt", id("what is stop loss hunting"))
        assertEquals("delta_cvd", id("what is order flow"))
        assertEquals("oi", id("what is open interest"))
        assertEquals("pcr", id("what does PCR mean"))
        assertEquals("max_pain", id("explain max pain"))
        assertEquals("order_blocks", id("what is an order block"))
        assertEquals("bos_choch", id("what is ChoCH"))
        assertEquals("msb", id("what is a market structure break"))
        assertEquals("fvg", id("what is a fair value gap"))
        assertEquals("vix", id("Jarvis, what is India VIX"))
    }

    @Test fun hinglishAndOddSpellings() {
        assertEquals("traps", id("bull trap kya hai"))
        assertEquals("fake_breakout", id("fake breakout kya hota hai"))
        assertEquals("fake_breakout", id("nakli breakout ka matlab"))
        assertEquals("gex", id("gamma exposure samjhao"))
        assertEquals("fake_breakout", id("what is a fake brekout"))
        assertEquals("gex", id("what is gamma exposre"))
        assertEquals("absorption", id("what is absorbtion"))
    }

    @Test fun aFigureWantedNowOrHisOwnAccountIsNotAMeaning() {
        assertNull(Concepts.asked("what is the Nifty PCR"))
        assertNull(Concepts.asked("what is the OI today"))
        assertNull(Concepts.asked("what is my delta"))
        assertNull(Concepts.asked("mera stop loss kya hai"))
        assertNull(Concepts.asked("what is the max pain at 25000"))
        assertNull(Concepts.asked("buy nifty call"))
        assertNull(Concepts.asked("what's gamma saying")) // GammaMorning's reading, not the word
        assertNull(Concepts.asked(""))
        // A meaning asked outright still counts with a market named.
        assertEquals("pcr", id("what does PCR mean for Nifty"))
    }

    @Test fun anUnknownConceptHasNoAnswer() {
        assertNull(id("what is a straddle"))
        assertNull(id("what is the weather"))
        assertNull(Concepts.answer(book, "explain the moon"))
        assertNull(Concepts.find(book, "zzzz qqqq"))
    }

    @Test fun theAnswerSaysMeaningSourceAndVerdict() {
        val a = assertNotNull(Concepts.answer(book, "what is gamma exposure"))
        assertTrue(a.startsWith("Gamma exposure (GEX)"), a)
        assertTrue("Signal source: Option chain: context or filter only" in a, a)
        assertTrue("Our research: Size only" in a, a)
        val t = assertNotNull(Concepts.answer(book, "what is a fake breakout"))
        assertTrue(t.startsWith("Fake (failed) breakout:"), t)
        assertTrue("cannot be told apart in advance" in t, t)
    }

    @Test fun isThisATrapStaysTheMarketsOwnRead() {
        // "Is this a bull trap?" asks about the chart now ([Ask]'s trend read), never the concept's meaning.
        assertNull(Concepts.asked("is this a bull trap"))
        assertNull(Concepts.asked("is it a fake breakout"))
    }

    @Test fun aFigureConceptNeedsItsMeaningAskedOutright() {
        // As the glossary: "what's the vix" / "what is the pcr" / "what's the news" want the figure now.
        for (q in listOf("what's the vix", "what is the pcr", "what's the news", "what is max pain", "what's the opening range"))
            assertNull(Concepts.answer(book, q), q)
        assertTrue(assertNotNull(Concepts.answer(book, "what does skew mean")).startsWith("IV skew and smile:"))
        assertTrue(assertNotNull(Concepts.answer(book, "vwap ka matlab")).startsWith("VWAP:"))
        // Not a figure: the plain "what is" is enough.
        assertTrue(assertNotNull(Concepts.answer(book, "what is gamma exposure")).startsWith("Gamma exposure"))
    }

    @Test fun theGlossarysOwnWordsStayItsAnswers() {
        // The glossary explains these; the book adds only its research note ([Concepts.researchNote]).
        for (q in listOf("what is theta", "what is gamma", "what is a call wall", "what does pcr mean", "explain max pain", "what is open interest"))
            assertNull(Concepts.answer(book, q), q)
        // A longer name the glossary does not know is the book's, even with a glossary word inside it.
        assertTrue(assertNotNull(Concepts.answer(book, "what is gamma exposure")).startsWith("Gamma exposure"))
    }

    @Test fun theResearchNoteForAGlossaryWord() {
        assertTrue(assertNotNull(Concepts.researchNote(book, "what is theta")).startsWith("Our research: Describes the option only"))
        assertTrue(assertNotNull(Concepts.researchNote(book, "explain max pain")).startsWith("Our research: Failed"))
        assertNull(Concepts.researchNote(book, "what is a straddle"))
        assertNull(Concepts.researchNote(book, "what is the stop loss for news trades"))
        assertNull(Concepts.researchNote(Concepts.Book.EMPTY, "what is theta"))
    }

    @Test fun theDialogLinesCarryEveryStudyWithItsSample() {
        val c = assertNotNull(book.byId("traps"))
        val l = Concepts.lines(c)
        assertEquals(c.what, l.first())
        assertTrue(l.any { it.startsWith("Signal source: Index chart") })
        assertTrue(l.any { it.startsWith("R1 N7 (NIFTY 777, BANKNIFTY 657 design trades)") })
        assertTrue(l.last().startsWith("Latest test: "))
        assertTrue(l.count { it.startsWith("Pitfall: ") } == c.pitfalls.size)
    }

    @Test fun theLongestPhraseWins() {
        val c = assertNotNull(book.byId("gex"))
        assertTrue("gamma exposure" in Concepts.phrases(c))
        assertEquals(" gamma exposure ", Concepts.plain("Gamma-exposure!"))
        assertEquals("gex", Concepts.find(book, "tell me about gamma exposure and gamma")?.id)
    }
}
