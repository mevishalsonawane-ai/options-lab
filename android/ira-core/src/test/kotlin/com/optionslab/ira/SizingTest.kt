package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SizingTest {
    @Test fun asked() {
        assertEquals(Sizing.Asked(20_000.0, Market.NIFTY, null), Sizing.asked("how many lots of nifty can i buy with 20000"))
        assertEquals(Sizing.Asked(50_000.0, Market.BANKNIFTY, "PE"), Sizing.asked("how many lots banknifty puts with 50k"))
        assertEquals(100_000.0, Sizing.asked("kitne lots 1 lakh mein")!!.budget)
        assertNull(Sizing.asked("how many lots did i trade today"))      // no amount: not this question
        assertNull(Sizing.asked("buy 2 lots nifty 25000 ce"))
    }

    @Test fun says() {
        val s = Sizing.say(Sizing.Asked(20_000.0, Market.NIFTY, null), Market.NIFTY, "CE", 25_000.0, 120.0, 75)
        assertTrue(s.contains("Rs 9,000 a lot") && s.contains("covers 2 lots (Rs 18,000)") && s.contains("Rs 2,700"), s)
        assertTrue(Sizing.say(Sizing.Asked(5_000.0, null, null), Market.NIFTY, "CE", 25_000.0, 120.0, 75).contains("does not cover one lot"))
    }
}

class SizingReviewTest {
    @Test fun theStrikeIsNotTheBudget() {
        assertEquals(20_000.0, Sizing.asked("how many lots of banknifty 52000 ce can I buy with 20000")!!.budget)
        assertEquals(30_000.0, Sizing.asked("how many lots nifty 25000 call for rs 30000")!!.budget)
        assertNull(Sizing.asked("how many lots of nifty 25000 ce do i hold"))
    }

    @Test fun wrapUpIsTheAccount() {
        assertTrue(Topic.ACCOUNT in Ask.parse("wrap up my day").topics)
        assertTrue(Topic.ACCOUNT in Ask.parse("aaj ka summary").topics)
    }
}
