package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketDaysTest {
    private val sun = LocalDate.of(2026, 10, 4)
    private val hol = mapOf(LocalDate.of(2026, 10, 20) to "Diwali")

    @Test fun days() {
        assertEquals(MarketDays.Asked.Day(LocalDate.of(2026, 10, 5)), MarketDays.asked("is tomorrow a holiday", sun))
        assertEquals(MarketDays.Asked.Day(LocalDate.of(2026, 10, 9)), MarketDays.asked("is the market open on friday?", sun))
        assertEquals(MarketDays.Asked.Day(LocalDate.of(2026, 10, 5)), MarketDays.asked("kal market khulega", sun))
        assertEquals(MarketDays.Asked.Next, MarketDays.asked("when is the next holiday", sun))
        assertNull(MarketDays.asked("is the market open", sun))         // no day: the usual status answer
        assertNull(MarketDays.asked("how is nifty tomorrow", sun))
    }

    @Test fun answers() {
        val next = LocalDate.of(2026, 10, 20) to "Diwali"
        assertTrue(MarketDays.say(MarketDays.Asked.Day(LocalDate.of(2026, 10, 5)), sun, hol::get, next).startsWith("Boss, Tomorrow (Mon 5 Oct) is a trading day"))
        assertTrue(MarketDays.say(MarketDays.Asked.Day(sun), sun, hol::get, next).contains("weekend"))
        assertTrue(MarketDays.say(MarketDays.Asked.Day(LocalDate.of(2026, 10, 20)), sun, hol::get, next).contains("market holiday (Diwali)"))
        assertEquals("Boss, Next market holiday: Tue 20 Oct (Diwali).", MarketDays.say(MarketDays.Asked.Next, sun, hol::get, next))
    }
}

class MarketDaysReviewTest {
    private val fri = LocalDate.of(2026, 10, 9)

    @Test fun pastIsNotThisAnswer() {
        assertNull(MarketDays.asked("kal market band tha kya", fri))
        assertNull(MarketDays.asked("was the market open on friday", fri))
        assertEquals(MarketDays.Asked.Day(fri), MarketDays.asked("is the market open on friday", fri))
        assertEquals(MarketDays.Asked.Day(fri.plusDays(7)), MarketDays.asked("is the market open next friday", fri))
    }

    @Test fun weekendSession() {
        val sun = LocalDate.of(2027, 1, 31)
        assertTrue(MarketDays.say(MarketDays.Asked.Day(sun), fri, { null }, null) { it == sun }.contains("special trading session"))
    }
}

class ExpiryAskedTest {
    @Test fun asked() {
        for (s in listOf("when is the next expiry", "is today expiry", "expiry kab hai", "banknifty expiry", "what is this week's expiry date"))
            assertTrue(MarketDays.expiryAsked(s), s)
        for (s in listOf("square off my positions before expiry", "what is expiry", "how does expiry work")) assertTrue(!MarketDays.expiryAsked(s), s)
        val t = LocalDate.of(2026, 10, 5)
        assertEquals("Next expiry, Boss - Nifty: tomorrow (Tue 6 Oct); BankNifty: Tue 27 Oct.",
            MarketDays.expirySay(t, listOf(Market.NIFTY to t.plusDays(1), Market.BANKNIFTY to LocalDate.of(2026, 10, 27))))
    }
}
