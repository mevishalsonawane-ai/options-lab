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

    @Test fun plainAmountsInTheAccountsWordsAreAccount() {
        assertTrue(Overheard.holdsAccount("Boss, you're down 2,500 today."))
        assertTrue(Overheard.holdsAccount("You’re up 1,240.50 so far"))
        assertTrue(Overheard.holdsAccount("-3,400.50"))
        assertTrue(Overheard.holdsAccount("Today: +12,000."))
        assertTrue(Overheard.holdsAccount("Net 840 after the last trade."))
        assertTrue(Overheard.holdsAccount("Solo made 1,500 on its paper trade."))
        assertTrue(Overheard.holdsAccount("Profit so far 3,000, Boss."))
        assertTrue(Overheard.holdsAccount("You lost 1,20,000 this month."))
        assertTrue(Overheard.holdsAccount("Charges this week came to 2,340.60."))
        // Beside a market's name, still the account's when it speaks of Boss's own money.
        assertTrue(Overheard.holdsAccount("Your position in Nifty is down 1,200."))
        assertTrue(Overheard.holdsAccount("Nifty fell and you're down 4,000."))
        // A second sentence holds it.
        assertTrue(Overheard.holdsAccount("Nifty at 24,612. You're down 2,500 today."))
    }

    @Test fun marketFiguresCountsAndTimesAreNot() {
        for (s in listOf("Nifty at 24,612", "Boss, Nifty is at 24,612.", "VIX up 8%", "India VIX up 8.5% today.",
                "BankNifty is down 180 points from the open.", "Nifty up 120 to 24,612.", "Nifty -120.50 since the open.",
                "Sensex down 1.2% in the first hour.", "Boss, your alarm: Nifty is up at 24,612.", "Nifty broke support at 24,500-24,450.",
                "You're down 3 in a row, Boss.", "Up 2 trades out of 3 today.", "You lost 2 of your last 3 trades.",
                "The expiry square-off is at 15:05.", "Nifty net open interest up 12,00,000 contracts.",
                "Gap up 150 points on Nifty.", "Trade 2 of 5 today.", "Boss, the 2026-10-05 note is saved."))
            assertFalse(Overheard.holdsAccount(s), s)
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
        assertEquals(Overheard.SAID, Overheard.said("Boss, you're down 2,500 today.", locked = true))
        assertEquals("Nifty at 24,612", Overheard.said("Nifty at 24,612", locked = true))
        assertEquals("VIX up 8%", Overheard.said("VIX up 8%", locked = true))
    }
}
