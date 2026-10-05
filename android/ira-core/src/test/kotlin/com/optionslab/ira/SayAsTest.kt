package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SayAsTest {
    @Test fun amountsOfALakhOrMoreAreSaidInLakhAndCrore() {
        assertEquals("Margin is 1.23 lakh rupees.", SayAs.figures("Margin is 1,23,456 rupees."))
        assertEquals("Margin is 1.23 lakh rupees.", SayAs.figures("Margin is Rs 1,23,456."))
        assertEquals("Margin is 1.23 lakh rupees.", SayAs.figures("Margin is ₹1,23,456.40."))
        assertEquals("up plus 1.5 lakh rupees", SayAs.figures("up Rs +1,50,000"))
        assertEquals("1 lakh and 12.5 lakh and 2.5 crore", SayAs.figures("1,00,000 and 12,50,000 and 2,50,00,000"))
        // Western grouping and plain digits said as rupees too.
        assertEquals("1.23 lakh rupees and 12.35 lakh", SayAs.figures("123456 rupees and 1,234,567"))
        // 99,99,999 rounds to 100 lakh: said as 1 crore.
        assertEquals("1 crore", SayAs.figures("99,99,999"))
        assertEquals("123 crore and 1,235 crore", SayAs.figures("1,23,00,00,000 and 12,34,56,78,901"))
    }

    @Test fun figuresUnderALakhAndOtherNumbersStayAsWritten() {
        val same = listOf("Nifty is at 24,612.", "Sensex at 81,523 and 99,999 rupees.", "At 9:15 on 05.10.2026.",
            "Order 250930000123456 placed.", "The PE ratio is 22.", "1.23 lakh rupees", "v1.2.3", "minus 45,000 rupees")
        same.forEach { assertEquals(it, SayAs.figures(it)) }
    }

    @Test fun croreFiguresAreSaidInTheirOrder() {
        // "Rs 12,345 crore" read as rupees by Wake.spoken comes out "12,345 rupees crore".
        assertEquals("FIIs sold 12,345 crore rupees.", SayAs.figures(Wake.spoken("FIIs sold Rs 12,345 crore.")))
        assertEquals("1.23 lakh crore", SayAs.figures("1,23,456 crore"))
        assertEquals("1.23 lakh crore rupees", SayAs.figures("Rs 1,23,456 crore"))
    }

    @Test fun optionSymbolsAreReadAsWords() {
        assertEquals("Bought Nifty 7 October 24,500 call at 120.", SayAs.figures("Bought NIFTY25O0724500CE at 120."))
        assertEquals("Bank Nifty October 52,000 put", SayAs.figures("BANKNIFTY26OCT52000PE"))
        assertEquals("Nifty 14 October 25,000 put and Fin Nifty October 24,000 call", SayAs.figures("NIFTY25O1425000PE and FINNIFTY25OCT24000CE"))
        assertEquals("Midcap Nifty October 13,000 call", SayAs.figures("MIDCPNIFTY25OCT13000CE"))
        assertEquals("Nifty 25,000 call", SayAs.figures("NIFTY25000CE"))
        assertEquals("Reliance October 2,800 call", SayAs.figures("RELIANCE25OCT2800CE"))
        assertEquals("Nifty October future", SayAs.figures("NIFTY26OCTFUT"))
        assertEquals("Nifty 1 December 26,000 call", SayAs.figures("NIFTY25D0126000CE"))
        // Not a real month or day: left as written.
        assertEquals("NIFTY26XYZ24500CE", SayAs.figures("NIFTY26XYZ24500CE"))
        assertEquals("NIFTY25O4024500CE", SayAs.figures("NIFTY25O4024500CE"))
    }

    @Test fun ceAndPeAfterAStrikeAreCallAndPut() {
        assertEquals("the 24500 call and 24,600 put", SayAs.figures("the 24500 CE and 24,600PE"))
        assertEquals("CE writers at the top", SayAs.figures("CE writers at the top"))
    }

    @Test fun hindiUnits() {
        assertEquals("1.23 लाख रुपये", SayAs.figures("Rs 1,23,456", hindi = true))
        assertEquals("2.5 करोड़ और 24,500 कॉल", SayAs.figures("2,50,00,000 और 24,500 CE", hindi = true))
    }

    @Test fun twiceChangesNothingAndSentencesStayTheSame() {
        val text = "Boss, your margin is Rs 1,23,456. You hold NIFTY25O0724500CE. FIIs sold Rs 12,345 crore."
        val once = SayAs.figures(Wake.spoken(text, 5))
        assertEquals(once, SayAs.figures(once))
        assertEquals(BargeIn.sentences(text).size, BargeIn.sentences(once).size)
    }

    @Test fun aloudSaysAmountsInLakh() {
        assertEquals("Boss, your margin is 1.23 lakh rupees.", Aloud.say("Boss, your margin is Rs 1,23,456.40."))
    }

    @Test fun aPlusBetweenFiguresIsSaidAndTheNewsWordToneIsNot() {
        val line = "BusinessLine: \"RBI holds rate\". It reads market-wide policy news (word tone +0.0). Policy news moves the whole " +
            "market: expect bigger swings for 15 to 30 minutes, so wait before new entries. ORB, Liquidity 15+5 trade BankNifty: run the trade check."
        val said = SayAs.figures(line)
        assertEquals("BusinessLine: \"RBI holds rate\". It reads market-wide policy news. Policy news moves the whole " +
            "market: expect bigger swings for 15 to 30 minutes, so wait before new entries. ORB, Liquidity 15 plus 5 trade BankNifty: run the trade check.", said)
        // Same sentence ends (a cut-in's "go on" finds them), and twice is once.
        assertEquals(Regex("[.!?](\\s|$)").findAll(line).count(), Regex("[.!?](\\s|$)").findAll(said).count())
        assertEquals(said, SayAs.figures(said))
        assertEquals("It reads bad for Nifty. Next.", SayAs.figures("It reads bad for Nifty (word tone -1.5). Next."))
        assertEquals("Liquidity 15 plus 5 and 2 plus 3", SayAs.figures("Liquidity 15+5 and 2+ 3"))
        assertEquals("लिक्विडिटी 15 प्लस 5", SayAs.figures("लिक्विडिटी 15+5", hindi = true))
        // A sign before a lone figure, a phone number and other brackets stay as written.
        listOf("Nifty +0.4 percent", "Call +91 98765", "Nifty (tone +0.5) up").forEach { assertEquals(it, SayAs.figures(it)) }
        assertEquals("up plus 1.5 lakh rupees", SayAs.figures("up Rs +1,50,000"))
    }

    @Test fun aLevelAndItsChangeAreNeverASum() {
        // A space before the plus, a comma or a long figure on its left: a level and its change, never "plus" between them.
        for (s in listOf("Nifty 24,512 +85 today", "Nifty 24512 +85", "Nifty 24512+85", "24,512+85", "Bank Nifty 51,200 + 120",
            "15 + 5", "1.5+2"))
            assertFalse(SayAs.figures(s).contains(" plus "), s)
        assertTrue(SayAs.figures("Nifty 24,512 +85 today").contains("+85"))
        // A short whole number joined to the next figure is still a sum.
        assertEquals("ORB, Liquidity 15 plus 5", SayAs.figures("ORB, Liquidity 15+5"))
        assertEquals("(2 plus 3)", SayAs.figures("(2+3)"))
    }

    @Test fun thePaperAccountsDayMonthYearSymbolsAreSaidAsWords() {
        assertEquals("Bank Nifty 27 October 55,100 call", SayAs.figures("BANKNIFTY27OCT2655100CE"))
        assertEquals("You hold Nifty 29 September 24,500 put at 120.", SayAs.figures("You hold NIFTY29SEP2624500PE at 120."))
        assertEquals("Sensex 3 November 82,000 call", SayAs.figures("SENSEX3NOV2682000CE"))
        assertEquals("Fin Nifty 28 October 23,450.5 put", SayAs.figures("FINNIFTY28OCT2623450.5PE"))
        assertTrue(SayAs.figures("BANKNIFTY27OCT2655100CE", hindi = true).endsWith("55,100 कॉल"))
        // Zerodha's own symbols are read as before (year, month, strike).
        assertEquals("Bank Nifty October 52,000 put", SayAs.figures("BANKNIFTY26OCT52000PE"))
        assertEquals("Reliance October 2,900 call", SayAs.figures("RELIANCE26OCT2900CE"))
        assertEquals("Bought Nifty 7 October 24,500 call at 120.", SayAs.figures("Bought NIFTY25O0724500CE at 120."))
        // Not a day or not a month: as written.
        assertEquals("NIFTY45OCT2624500CE", SayAs.figures("NIFTY45OCT2624500CE"))
        assertEquals("NIFTY29XYZ2624500CE", SayAs.figures("NIFTY29XYZ2624500CE"))
        // Applying it twice changes nothing.
        val once = SayAs.figures("Exit BANKNIFTY27OCT2655100CE now.")
        assertEquals(once, SayAs.figures(once))
    }

    @Test fun aRangeWithADashIsSaidWithTo() {
        assertEquals("a usual day spans about 24,300 to 24,700", SayAs.figures("a usual day spans about 24,300-24,700"))
        assertEquals("the opening range 24,700 to 24,500 (9:15 to 10:00)", SayAs.figures("the opening range 24,700-24,500 (9:15-10:00)"))
        assertEquals("days it gapped 0.5 to 1 percent", SayAs.figures("days it gapped 0.5-1 percent"))
        assertEquals("leave 13:00 to 14:00 alone.", SayAs.figures("leave 13:00\u201314:00 alone."))
        assertEquals("between 2 to 3 pm", SayAs.figures("between 2-3 pm"))
        assertEquals("expect 15 to 30 minutes", SayAs.figures("expect 15-30 minutes"))
        assertEquals("24,300 से 24,700", SayAs.figures("24,300-24,700", hindi = true))
        // A lakh-sized range is still said in lakh.
        assertEquals("1 lakh rupees to 2 lakh", SayAs.figures("Rs 1,00,000-2,00,000"))
        // Up to six digits a side, commas not counted; short plain figures still a range.
        assertEquals("strikes 24,500 to 24,600", SayAs.figures("strikes 24,500-24,600"))
        assertEquals("lots 1200 to 1500", SayAs.figures("lots 1200-1500"))
        // Seven digits a side is no range (each figure is still said in lakh on its own).
        assertFalse(SayAs.figures("ref 12,34,567-12,34,999").contains(" to "))
        // Dates, phone numbers, year spans, symbols, a spaced minus, a sign and a hyphenated word stay as written.
        listOf("On 2026-10-05.", "On 05-10-2026.", "Call 1800-123-4567", "FY2025-26 taxes", "trades-FY2025-26-all.csv",
            "Nifty 24,512 - 85", "down -0.4 percent", "a 5-minute chart", "M&M-EQ", "10-15-minute window", "-5-10",
            "it's 3-30 pm", "at 9-15 am", "call 98765-43210", "Call 98765\u201343210 now", "order 2410050000123-2")
            .forEach { assertEquals(it, SayAs.figures(it)) }
        // Through Aloud: the times rounded, then the range said; twice is once and the sentences stay the same.
        val text = "Boss, a usual day spans about 24,300-24,700. The opening range was 09:15-10:00."
        val said = Aloud.say(text)
        assertEquals("Boss, a usual day spans about 24,300 to 24,700. The opening range was 9:15 to 10:00.", said)
        assertEquals(said, Pauses.shape(SayAs.figures(said)))
        assertEquals(BargeIn.sentences(text).size, BargeIn.sentences(said).size)
    }
}
