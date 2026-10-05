package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals

class PausesTest {
    private fun words(s: String) = s.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    @Test fun bracketsBecomeAShortAside() {
        assertEquals("Today's loss is Rs 1,400, 70 percent, of the limit.", Pauses.shape("Today's loss is Rs 1,400 (70 percent) of the limit."))
        assertEquals("Nifty is at 24,612, plus 0.21 percent.", Pauses.shape("Nifty is at 24,612 (plus 0.21 percent)."))
        assertEquals("Down Rs 900, on Zerodha", Pauses.shape("Down Rs 900 (on Zerodha)"))
        // A word's own bracket, and an aside holding a whole sentence, stay.
        assertEquals("Close position(s) now.", Pauses.shape("Close position(s) now."))
        assertEquals("It fell (see the chat. It is long) today.", Pauses.shape("It fell (see the chat. It is long) today."))
    }

    @Test fun dashesSeparatorsAndLinesBecomePauses() {
        assertEquals("Trades: 4 of the 3 your limit allows, reached.", Pauses.shape("Trades: 4 of the 3 your limit allows - reached."))
        assertEquals("No reason noted, what would you do differently?", Pauses.shape("No reason noted — what would you do differently?"))
        assertEquals("Relay up, prices flowing, guards on", Pauses.shape("Relay up · prices flowing | guards on"))
        assertEquals("Ready: Zerodha logged in, relay up. Kill switch off", Pauses.shape("Ready:\n- Zerodha logged in\n- relay up.\n• Kill switch off"))
        // A spaced dash before a figure may be a minus: left.
        assertEquals("Change - 5 points", Pauses.shape("Change - 5 points"))
    }

    @Test fun figuresSideBySideAndTheNextItemGetABreath() {
        assertEquals("Walls at 24,500, 24,600 and 24,700.", Pauses.shape("Walls at 24,500 24,600 and 24,700."))
        assertEquals("Nifty up 0.4 percent, Bank Nifty down 0.2 percent, Sensex flat.",
            Pauses.shape("Nifty up 0.4 percent Bank Nifty down 0.2 percent Sensex flat."))
        assertEquals("Up 120 points, Bank Nifty up 300 points", Pauses.shape("Up 120 points Bank Nifty up 300 points"))
        assertEquals("Margin 1.23 lakh rupees, Zerodha", Pauses.shape("Margin 1.23 lakh rupees Zerodha"))
    }

    @Test fun datesQuantitiesTimesAndPlainTextStay() {
        val same = listOf("Nifty 7 October 24,500 call at 120.", "Bought 2 Bank Nifty lots.", "At 9:15 on 05.10.2026.",
            "Order 250930000123456 placed.", "Nifty is at 24,612.", "Boss, Nifty is up 0.4 percent. Bank Nifty is down.",
            "minus 45,000 rupees", "Up 0.5 percent on Monday", "The PE ratio is 22.", "")
        same.forEach { assertEquals(it, Pauses.shape(it)) }
    }

    @Test fun neverAWordOrASentenceChanged() {
        val texts = listOf(
            "Boss, today's loss is Rs 1,400 (70%) of the Rs 2,000 limit - Rs 600 left. Trades: 4 of 5 · kill switch off.\nYour call.",
            "Walls 24,500 24,600. Nifty up 0.4 percent Bank Nifty down. (Paper is practice.) Done!",
            "बॉस, निफ्टी 0.4 प्रतिशत ऊपर है (आज)। बाकी चैट में है।")
        texts.forEach { t ->
            val s = Pauses.shape(t)
            assertEquals(words(t), words(s))
            assertEquals(BargeIn.sentences(t).size, BargeIn.sentences(s).size)
            assertEquals(s, Pauses.shape(s), "twice changes nothing")
        }
    }

    @Test fun aloudSaysItWithPausesAndLakhStillWorks() {
        assertEquals("Boss, margin is 1.23 lakh rupees, 40 percent, of the usual.",
            Aloud.say("Boss, margin is Rs 1,23,456 (40%) of the usual."))
        assertEquals("Boss, resistance at 24,500, 24,600.", Aloud.say("Resistance at 24,500 24,600."))
    }
}
