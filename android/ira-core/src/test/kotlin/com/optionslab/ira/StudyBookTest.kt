package com.optionslab.ira

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The study pages ([StudyBook]) read from the app's bundled summary: every required page, the app's own numbers, search. */
class StudyBookTest {
    private val asset: File = listOf("../app/src/main/assets/study/pages.txt", "android/app/src/main/assets/study/pages.txt").map(::File).first { it.isFile }
    private val pages = StudyBook.parse(asset.readText())

    @Test fun everyPageTheRoadmapAsksForIsBundledWithTheResearchsOwnNumbers() {
        val ids = pages.map { it.id }.toSet()
        for (id in listOf("liquidity", "orb", "hero", "night", "vixdiv", "mcx", "solo", "why-fail", "orderflow", "vwap", "value-area", "oi", "vix",
            "trade-manager", "autopilot", "lab", "risk", "majority-rule")) assertTrue(id in ids, "missing page $id")
        assertTrue(pages.single { it.id == "liquidity" }.text.contains("₹167 a day"))
        assertTrue(pages.single { it.id == "why-fail" }.text.contains("215,280"))
        assertTrue(pages.all { it.paragraphs.size >= 2 && it.keywords.isNotEmpty() })
        // A summary, not the research files: small.
        assertTrue(asset.length() < 40_000, "the asset is ${asset.length()} bytes")
    }

    @Test fun searchAndJarvisFindTheRightPage() {
        assertEquals("vwap", StudyBook.search(pages, "vwap").first().id)
        assertEquals("risk", StudyBook.search(pages, "position sizing").first().id)
        assertEquals("why-fail", StudyBook.search(pages, "multiple testing").first().id)
        assertEquals(pages, StudyBook.search(pages, " "))
        assertEquals("vwap", StudyBook.find(pages, "vwap")?.id)
        assertEquals("value-area", StudyBook.find(pages, "the value area")?.id)
        assertEquals("orderflow", StudyBook.find(pages, "order flow")?.id)
        assertNull(StudyBook.find(pages, "cricket scores"))
        // Majority Rule 14 (R13): found by its names, with the research's numbers and "context only".
        assertEquals("majority-rule", StudyBook.find(pages, "majority rule")?.id)
        assertEquals("majority-rule", StudyBook.search(pages, "mr 14").first().id)
        val mr = pages.single { it.id == "majority-rule" }.text
        for (w in listOf("28.57", "13 times in 20 years", "+6.2% after 4 weeks", "33-50%", "36%", "-₹69k and +₹39k", "break-even to losing", "context only"))
            assertTrue(w in mr, w)
        assertTrue(StudyBook.say(assertNotNull(StudyBook.find(pages, "vwap")), "vwap").startsWith("Opening the study page \"VWAP\""))
        assertTrue(StudyBook.say(null, "cricket").contains("no study page"))
    }
}
