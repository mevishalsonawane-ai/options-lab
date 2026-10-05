package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverheardTest {
    @Test fun amountsPnlAndSymbolsAreAccount() {
        assertTrue(Overheard.holdsAccount("Zerodha today: +Rs 1,234.50."))
        assertTrue(Overheard.holdsAccount("Solo is down Rs 2,000 from its best"))
        assertTrue(Overheard.holdsAccount("You made ₹500 today"))
        assertTrue(Overheard.holdsAccount("down 1,250 rupees"))
        assertTrue(Overheard.holdsAccount("Your P&L is -3,400 so far"))
        assertTrue(Overheard.holdsAccount("Solo (paper): bought NIFTY25O0725000CE at 120.00"))
        assertTrue(Overheard.holdsAccount("At 15:05 the expiry square-off will close 1 position: Zerodha BANKNIFTY25OCT55000PE (35)."))
        assertTrue(Overheard.holdsAccount("NIFTY25OCTFUT is open"))
        // The arms' record in the gap plan.
        assertTrue(Overheard.holdsAccount(GapPlan.say(Market.NIFTY, 0.6, listOf("ORB +Rs 3,000 over 6 (4 won)"))))
        // The day's target, the line the target watch already keeps off a locked phone.
        assertTrue(Overheard.holdsAccount(DayTarget.say(5_000.0, 4_000.0)))
    }

    @Test fun marketWordsAndPlainCoachingAreNot() {
        assertFalse(Overheard.holdsAccount("NIFTY: the biggest call open interest moved from 25,000 to 25,200 - resistance moved up."))
        assertFalse(Overheard.holdsAccount("India VIX is up 12% on the day."))
        assertFalse(Overheard.holdsAccount(Overtrade.say(5)))
        assertFalse(Overheard.holdsAccount("Boss, the relay server is not answering."))
        assertFalse(Overheard.holdsAccount(GapPlan.say(Market.NIFTY, 0.6, emptyList())))
        assertFalse(Overheard.holdsAccount("Boss, your P&L is in the chat."))
        assertFalse(Overheard.holdsAccount(Overheard.SAID))
    }

    @Test fun lockedSwapsOnlyAccountLines() {
        val full = "Boss, you've hit today's target: +Rs 5,000 against Rs 4,000."
        assertEquals(Overheard.SAID, Overheard.said(full, locked = true))
        assertEquals(full, Overheard.said(full, locked = false))
        assertEquals("India VIX is up 12% on the day.", Overheard.said("India VIX is up 12% on the day.", locked = true))
        assertEquals("plain", Overheard.said(full, locked = true, plain = "plain"))
        assertEquals(Overheard.TITLE, Overheard.title("News trade closed: NIFTY25O0725000CE", locked = true))
        assertEquals("News trade closed: NIFTY25O0725000CE", Overheard.title("News trade closed: NIFTY25O0725000CE", locked = false))
        assertEquals("India VIX spiking", Overheard.title("India VIX spiking", locked = true))
        assertTrue(Overheard.SAID.startsWith("Boss"))
    }
}
