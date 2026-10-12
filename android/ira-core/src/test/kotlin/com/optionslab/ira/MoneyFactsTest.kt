package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 12 Oct: "4k in the morning, negative in the evening" - Zerodha's real money and the paper account's day read as one. */
class MoneyFactsTest {
    @Test fun everyFigureSaysWhoseMoneyItIs() {
        assertEquals("Capital · Zerodha (LIVE)", MoneyFacts.cardTitle(true))
        assertEquals("Capital · PAPER (practice)", MoneyFacts.cardTitle(false))
        assertTrue("not your Zerodha money" in MoneyFacts.cardNote(false))
        assertTrue("Paper trades are not in these figures" in MoneyFacts.cardNote(true))
        assertTrue(MoneyFacts.venue(true).startsWith("Zerodha (LIVE"))
        assertTrue(MoneyFacts.venue(false).startsWith("Paper"))
    }

    @Test fun theDayReportNeverAddsPaperToZerodha() {
        // Boss's day: paper −1,374, no Zerodha trades - the title was "· −₹1,374" with no word of which account.
        assertEquals("Day report · 12 Oct · Paper −₹1,374", MoneyFacts.dayTitle("Day report · 12 Oct", -1374.0, null))
        assertEquals("Day report · Paper −₹1,374 · Zerodha +₹250", MoneyFacts.dayTitle("Day report", -1374.0, 250.0))
        assertEquals("Day report", MoneyFacts.dayTitle("Day report", null, null))
    }

    @Test fun aNegativeZerodhaBalanceIsExplainedFromWhatWasRead() {
        assertNull(MoneyFacts.negativeWhy(MoneyFacts.Funds(4674.0, 0.0), 0, null))
        assertNull(MoneyFacts.negativeWhy(MoneyFacts.Funds(0.0, 0.0), 0, null))
        val w = MoneyFacts.negativeWhy(MoneyFacts.Funds(-1200.0, 5874.0, m2m = -900.0, realised = -200.0, span = 4000.0, exposure = 1000.0,
            optionPremium = 874.0), 2, 60.0)!!
        assertTrue(w.startsWith("Zerodha's available money is below zero (−₹1,200): "), w)
        assertTrue("your open positions are down ₹900" in w, w)
        assertTrue("trades closed today lost ₹200" in w, w)
        assertTrue("₹5,000 is blocked as margin" in w, w)
        assertTrue("₹874 was paid for options bought today" in w, w)
        assertTrue("today's charges are about ₹60" in w, w)
        assertTrue(w.endsWith("; paper trades never touch it."), w)
        assertEquals(1, w.split(". ").size, "one sentence: a short answer keeps it whole")
        // Nothing in the books explains it: a debit at Zerodha, said so.
        val d = MoneyFacts.negativeWhy(MoneyFacts.Funds(-35.0, 0.0), 0, null)!!
        assertTrue("debit Zerodha booked" in d, d)
        // Open positions with no M2M figure are named.
        val o = MoneyFacts.negativeWhy(MoneyFacts.Funds(-10.0, 0.0), 1, 0.0)!!
        assertTrue("1 position is open" in o, o)
        assertFalse("charges" in o, o)
    }
}
