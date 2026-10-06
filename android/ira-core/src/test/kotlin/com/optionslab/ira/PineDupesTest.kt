package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PineDupesTest {
    private val engulfing = """
        //@version=5
        // Written by Jarvis from the bullish engulfing pattern
        strategy("Jarvis: bullish engulfing Nifty 15m", overlay = true)
        if close > open and close[1] < open[1]
            strategy.entry("Long", strategy.long)
    """.trimIndent()

    /** The same strategy added again at 09:50: another header comment, another layout. */
    private val again = """
        //@version=5
        // Written by Jarvis from the bullish engulfing pattern (09:50)
        strategy("Jarvis: bullish engulfing Nifty 15m",   overlay=true)   // re-added
            if close > open and close[1] < open[1]
                    strategy.entry("Long", strategy.long)
    """.trimIndent()

    @Test fun commentsAndWhitespaceDoNotMakeAScriptNew() {
        assertEquals(PineDupes.normalize(engulfing), PineDupes.normalize(again))
        assertEquals(PineDupes.key(engulfing, "NIFTY", "15m"), PineDupes.key(again, " nifty ", "15M"))
        assertNotEquals(PineDupes.key(engulfing, "NIFTY", "15m"), PineDupes.key(engulfing, "NIFTY", "5m"), "another chart is another script")
        assertNotEquals(PineDupes.key(engulfing, "NIFTY", "15m"), PineDupes.key(engulfing, "BANKNIFTY", "15m"), "another symbol too")
        assertNotEquals(PineDupes.normalize(engulfing), PineDupes.normalize(engulfing.replace("close[1]", "close[2]")))
    }

    @Test fun slashesInsideAStringAreNotAComment() {
        assertEquals("""a="http://x"b='//y'""", PineDupes.normalize("a = \"http://x\"  // gone\nb = '//y'"))
        assertEquals("""s="a\"//b"""", PineDupes.normalize("s = \"a\\\"//b\""))
        assertEquals("x", PineDupes.normalize("x // trailing"))
        assertEquals("", PineDupes.normalize("//only a comment"))
    }

    @Test fun theLabRefusesAScriptAlreadyArmed() {
        val saved = listOf(PineDupes.Script(1, "plot(close)", "NIFTY", "5m", true),
            PineDupes.Script(3, engulfing, "NIFTY", "15m", true))
        val d = PineDupes.duplicateOf(again, "NIFTY", "15m", saved)!!
        assertEquals(3, d.id)
        assertEquals("already armed as #3", PineDupes.refusal(d))
        assertNull(PineDupes.duplicateOf(again, "NIFTY", "5m", saved))
        // Saved but switched off: still the same script.
        val off = PineDupes.duplicateOf(again, "NIFTY", "15m", listOf(PineDupes.Script(2, engulfing, "NIFTY", "15m", false)))!!
        assertEquals("already saved as #2 (switched off)", PineDupes.refusal(off))
        // An armed one is named before an older one switched off.
        assertEquals(5, PineDupes.duplicateOf(again, "NIFTY", "15m", listOf(PineDupes.Script(2, engulfing, "NIFTY", "15m", false),
            PineDupes.Script(5, engulfing, "NIFTY", "15m", true)))!!.id)
    }

    @Test fun theOldestArmedIsKeptAndTheArmedRepeatsAreDisarmed() {
        val saved = listOf(
            PineDupes.Script(3, engulfing, "NIFTY", "15m", true),
            PineDupes.Script(4, again, "NIFTY", "15m", true),
            PineDupes.Script(5, again, "NIFTY", "15m", false),            // off already: left as it is
            PineDupes.Script(6, engulfing, "NIFTY", "5m", true),          // another chart
            PineDupes.Script(7, "plot(close)", "BANKNIFTY", "5m", true),
            PineDupes.Script(9, "plot( close ) // same", "BANKNIFTY", "5m", true),
            PineDupes.Script(8, "plot(close)", "BANKNIFTY", "5m", true),
        )
        val extra = PineDupes.extraArmed(saved)
        assertEquals(listOf(4L to 3L, 8L to 7L, 9L to 7L), extra.map { it.first.id to it.second }.sortedBy { it.first })
        assertTrue(PineDupes.extraArmed(saved.take(1) + saved.drop(5).take(0)).isEmpty())
        assertTrue(PineDupes.note(3).startsWith("Switched off: the same strategy as #3"))
        assertTrue(PineDupes.note(3).contains("managed to its exit"))
    }
}
