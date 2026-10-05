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
}
