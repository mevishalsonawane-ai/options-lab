package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptionFactsTest {
    @Test fun asked() {
        assertEquals(OptionFacts.Asked.Quote(Market.BANKNIFTY, 52000, "PE"), OptionFacts.asked("price of banknifty 52000 pe"))
        assertEquals(OptionFacts.Asked.Quote(Market.NIFTY, 25000, "CE"), OptionFacts.asked("what's the nifty premium for 25000 call"))
        assertEquals(OptionFacts.Asked.Quote(Market.NIFTY, 25000, "CE"), OptionFacts.asked("nifty 25000 ce"))
        assertNull(OptionFacts.asked("buy 2 lots nifty 25000 ce"))
        assertNull(OptionFacts.asked("will nifty cross 25000"))
        assertEquals(OptionFacts.Asked.Atm(Market.NIFTY), OptionFacts.asked("which strike is atm for nifty"))
        assertEquals(OptionFacts.Asked.LotSize(Market.BANKNIFTY), OptionFacts.asked("what is the lot size of banknifty"))
        assertEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("how long until the market closes"))
    }

    @Test fun says() {
        assertTrue(OptionFacts.quote(Market.NIFTY, 25000, "CE", 120.5, 120.0, 121.0, 1_234_500, 75).contains("one lot of 75 is about Rs 9,038"))
        assertEquals("2 h 15 min left until the 15:30 close, Boss.", OptionFacts.timeLeft(13 * 60 + 15))
        assertTrue(OptionFacts.timeLeft(8 * 60).contains("opens in 75 minutes"))
    }
}

class OptionFactsHindiTest {
    @Test fun hinglish() {
        assertEquals(OptionFacts.Asked.Quote(Market.NIFTY, 25000, "CE"), OptionFacts.asked("nifty 25000 ce ka bhav kya hai"))
        assertEquals(OptionFacts.Asked.Atm(Market.NIFTY), OptionFacts.asked("atm strike kya hai"))
        assertEquals(OptionFacts.Asked.LotSize(Market.NIFTY), OptionFacts.asked("nifty ka lot size kitna hai"))
        assertEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("market band hone mein kitna time hai"))
        assertNull(OptionFacts.asked("nifty 25000 ce kharido"))
    }
}

class MarketOpenAskTest {
    @Test fun open() {
        assertEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("when does the market open"))
        assertEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("market kab khulega"))
        assertEquals("The market has closed for today (15:30), Boss. It opens next on Mon 6 Oct at 09:15.", OptionFacts.timeLeft(16 * 60, nextDay = "Mon 6 Oct"))
        assertEquals("The market is closed today, Boss. It opens next on Mon 6 Oct at 09:15.", OptionFacts.timeLeft(11 * 60, tradingDay = false, nextDay = "Mon 6 Oct"))
    }
}
