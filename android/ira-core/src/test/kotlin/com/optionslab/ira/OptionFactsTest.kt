package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
        // F&O trades to 15:40 since 3 Aug 2026: the caller passes that close.
        assertEquals("5 minutes left until the 15:40 close, Boss.", OptionFacts.timeLeft(15 * 60 + 35, close = 15 * 60 + 40))
        assertTrue(OptionFacts.timeLeft(15 * 60 + 40, close = 15 * 60 + 40).startsWith("The market has closed for today (15:40), Boss."))
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
        assertEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("when does the market open today"))
        // A named day is the calendar's question.
        assertNotEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("when does the market open tomorrow"))
        assertNotEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("will the market open on monday"))
        assertNotEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("market kal kab khulega"))
        assertEquals(MarketDays.Asked.Day(java.time.LocalDate.of(2026, 10, 6)), MarketDays.asked("kal market kab khulega", java.time.LocalDate.of(2026, 10, 5)))
        assertEquals(MarketDays.Asked.Day(java.time.LocalDate.of(2026, 10, 7)), MarketDays.asked("market parso khulega", java.time.LocalDate.of(2026, 10, 5)))
        assertEquals(MarketDays.Asked.Day(java.time.LocalDate.of(2026, 10, 12)), MarketDays.asked("somvar ko market khulega", java.time.LocalDate.of(2026, 10, 6)))
        assertNotEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("what time does the market close tomorrow"))
        assertNotEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("kal market kab open hoga"))
        // "Next" alone is today's clock answer ("it opens next on ..."), as before.
        assertEquals(OptionFacts.Asked.TimeLeft, OptionFacts.asked("when does the market open next"))
        assertEquals("The market has closed for today (15:30), Boss. It opens next on Mon 6 Oct at 09:15.", OptionFacts.timeLeft(16 * 60, nextDay = "Mon 6 Oct"))
        assertEquals("The market is closed today, Boss. It opens next on Mon 6 Oct at 09:15.", OptionFacts.timeLeft(11 * 60, tradingDay = false, nextDay = "Mon 6 Oct"))
    }
}

/** Voice, round 27: an option's strike and side as the recognizer writes them - read as meant, for the quote only. */
class OptionHeardTest {
    @Test fun slipsOfTheStrikeAndSideAreQuoted() {
        for ((s, want) in listOf(
            "price of banknifty 52,000 pe" to OptionFacts.Asked.Quote(Market.BANKNIFTY, 52000, "PE"),
            "what's the nifty 25,000 call premium" to OptionFacts.Asked.Quote(Market.NIFTY, 25000, "CE"),
            "nifty 25000 p e" to OptionFacts.Asked.Quote(Market.NIFTY, 25000, "PE"),
            "nifty 25000 c e" to OptionFacts.Asked.Quote(Market.NIFTY, 25000, "CE"),
            "what is nifty 25000 p.e. price" to OptionFacts.Asked.Quote(Market.NIFTY, 25000, "PE"),
            "what is nifty 25000 pee premium" to OptionFacts.Asked.Quote(Market.NIFTY, 25000, "PE"),
            "what is nifty 25000 see premium" to OptionFacts.Asked.Quote(Market.NIFTY, 25000, "CE"),
            "nifty 25000 foot" to OptionFacts.Asked.Quote(Market.NIFTY, 25000, "PE"),
            "what's the nifty 24500 putt price" to OptionFacts.Asked.Quote(Market.NIFTY, 24500, "PE"),
            "premium of nifty 25000 strike call" to OptionFacts.Asked.Quote(Market.NIFTY, 25000, "CE"),
            "bank nifty 52000 pe" to OptionFacts.Asked.Quote(Market.BANKNIFTY, 52000, "PE"),
            "bank nifty 52,000 pee" to OptionFacts.Asked.Quote(Market.BANKNIFTY, 52000, "PE"),
            "what is the bank fifty 52000 ce price" to OptionFacts.Asked.Quote(Market.BANKNIFTY, 52000, "CE"),
            "what is niftee 25000 ce price" to OptionFacts.Asked.Quote(Market.NIFTY, 25000, "CE"),
            "what is fine nifty 24000 pe price" to OptionFacts.Asked.Quote(Market.FINNIFTY, 24000, "PE"),
        )) assertEquals(want, OptionFacts.asked(s), s)
        assertEquals("nifty 25000 pe", Heard.option("nifty 25,000 p e"))
        assertEquals("nifty 125000 ce", Heard.option("nifty 1,25,000 see"))
        assertEquals("25000 call", Heard.option("25000 strike call"))
    }

    @Test fun onlyRightAfterAStrike() {
        // Words without a strike before them, a price with paise, a figure with lakh grouping in rupees: untouched.
        for (s in listOf("see you boss", "my foot hurts", "pee", "yes", "no", "nahi", "haan", "no thanks", "yes go ahead", "nifty at 25000 see you",
            "my p&l is 1,23,456.50", "nifty is at 25,000.50", "ce pe explained", "what is a put", "is the call good", "nifty 250 see", "rs 1,200 see"))
            assertEquals(s, Heard.option(s), s)
    }

    @Test fun neverAnOrderNeverAYes() {
        // An order said with a slip is still no quote - and still read as heard (the slip never makes it an order).
        for (s in listOf("buy 2 lots nifty 25,000 see", "sell bank nifty 52,000 pee", "buy nifty 25000 foot", "exit nifty 25000 p e",
            "place order nifty 25000 strike call", "nifty 25000 ce kharido"))
            assertNull(OptionFacts.asked(s), s)
        // What it reads holds no word that acts, nor a yes or a no, that the words as heard did not.
        val acts = Regex("(?i)\\b(buy|sell|order|place|exit|close|square|cancel|stop|start|kill|approve|confirm|yes|no|haan|nahi)\\b")
        for (s in listOf("nifty 25,000 see", "nifty 25000 foot", "nifty 25000 p e", "bank nifty 52,000 pee", "nifty 25000 strike call",
            "nifty 25000 cal", "nifty 25000 col", "nifty 24500 poot", "nifty 25000 pea", "nifty 25000 sea", "nifty 25000 si", "nifty 25000 cee")) {
            val r = Heard.option(s)
            assertTrue(r != s, s)
            assertEquals(acts.findAll(s).map { it.value }.toList(), acts.findAll(r).map { it.value }.toList(), s)
            val q = Ask.parse(r)
            assertNull(q.command, s); assertNull(q.order, s)
            assertNull(Wake.yesNo(r), s)
            assertEquals(Ask.parse(s).order, q.order, s)
        }
        // A yes or a no is never changed, nor read any differently.
        for (s in listOf("yes", "no", "yes 25000 pe", "no 25000 pe", "no nifty 25000 foot"))
            assertEquals(Wake.yesNo(s), Wake.yesNo(Heard.option(s)), s)
    }
}
