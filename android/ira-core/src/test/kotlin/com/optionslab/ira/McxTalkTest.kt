package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jarvis reads MCX prices and positions; never an order or a command ([McxTalk]). */
class McxTalkTest {
    @Test fun theCommoditiesAreRead() {
        assertEquals(McxTalk.Asked(listOf("CRUDEOIL"), false), McxTalk.read("how is crude doing?"))
        assertEquals(McxTalk.Asked(listOf("NATURALGAS"), false), McxTalk.read("natural gas price"))
        assertEquals(McxTalk.Asked(listOf("SILVERM"), false), McxTalk.read("Silver?"))
        assertEquals(McxTalk.Asked(listOf("GOLDM"), false), McxTalk.read("what is MCX gold at"))
        assertEquals(McxTalk.Asked(listOf("GOLDM"), false), McxTalk.read("goldm price"))
        assertEquals(McxTalk.Asked(listOf("CRUDEOIL", "NATURALGAS"), false), McxTalk.read("crude and nat gas"))
        assertEquals(McxTalk.Asked(McxTalk.HEADLINE, false), McxTalk.read("how are commodities today"))
        assertEquals(McxTalk.Asked(emptyList(), true), McxTalk.read("my mcx positions"))
        assertEquals(McxTalk.Asked(listOf("CRUDEOIL"), true), McxTalk.read("what crude positions do I hold"))
    }

    @Test fun plainGoldAndAnythingThatActsIsLeftAlone() {
        assertNull(McxTalk.read("how is gold"), "a plain gold is the XAUUSD price Jarvis already reads")
        assertNull(McxTalk.read("backtest the hammer on gold"))
        assertNull(McxTalk.read("buy 1 lot crude"))
        assertNull(McxTalk.read("set an alarm on silver above 230000"))
        assertNull(McxTalk.read("how is nifty"))
        assertNull(McxTalk.read("show my positions"), "positions alone stay the usual answer")
    }

    @Test fun theAnswer() {
        val p = listOf(McxTalk.Price("CRUDEOIL", "CRUDEOIL 19 OCT FUT", 8552.0, 0.012, false),
            McxTalk.Price("SILVERM", "SILVERM 30 NOV FUT", 225_773.0, -0.005, false),
            McxTalk.Price("GOLDM", "GOLDM 5 NOV FUT", 147_897.0, null, false))
        val a = McxTalk.answer(McxTalk.Asked(listOf("CRUDEOIL", "SILVERM", "GOLDM", "NATURALGAS"), false), p, null, open = true, locked = false)
        assertEquals(listOf("Crude oil (CRUDEOIL 19 OCT FUT) Rs 8,552.00, up 1.20% today",
            "Silver mini (5 kg) (SILVERM 30 NOV FUT) Rs 225,773.00, down 0.50% today",
            "Gold mini (100 g) (GOLDM 5 NOV FUT) Rs 147,897.00, now",
            "Natural gas: no price just now"), a.lines())
        val shut = McxTalk.answer(McxTalk.Asked(listOf("CRUDEOIL"), false), listOf(p[0].copy(lastSession = true)), null, open = false, locked = false)
        assertTrue(shut.contains("at the last close") && shut.contains("MCX is shut now"), shut)
        val held = listOf(McxTalk.Held("CRUDEOIL 15 OCT 8550 CE", 100, 242.0, 1250.0, "Paper"), McxTalk.Held("GOLDM 5 NOV FUT", -10, 147_000.0, null, "Zerodha"))
        assertEquals("Your MCX positions: Paper: CRUDEOIL 15 OCT 8550 CE, long 100 at 242.00, P&L Rs +1,250; Zerodha: GOLDM 5 NOV FUT, short 10 at 147,000.00.",
            McxTalk.answer(McxTalk.Asked(emptyList(), true), emptyList(), held, open = true, locked = false))
        assertEquals("You hold no MCX positions.", McxTalk.answer(McxTalk.Asked(emptyList(), true), emptyList(), emptyList(), true, false))
        assertEquals("I could not read your MCX positions just now.", McxTalk.answer(McxTalk.Asked(emptyList(), true), emptyList(), null, true, false))
        assertEquals("Unlock the phone for your positions, Boss.", McxTalk.answer(McxTalk.Asked(emptyList(), true), emptyList(), held, true, true))
        assertEquals("Nothing to say about MCX just now.", McxTalk.answer(McxTalk.Asked(emptyList(), false), emptyList(), null, true, false))
        assertEquals("XYZ: no price just now", McxTalk.answer(McxTalk.Asked(listOf("XYZ"), false), emptyList(), null, true, false))
        assertTrue(McxTalk.priceLine(McxTalk.Price("XYZ", "X", 1.0, Double.NaN, false)).startsWith("XYZ (X) Rs 1.00, now"))
    }
}
