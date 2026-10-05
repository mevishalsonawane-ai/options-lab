package com.optionslab.ira

import com.optionslab.engine.risk.AccountGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtStopsTest {
    private val long = AtStops.Leg("Zerodha", "NIFTY24800CE", 75, 120.0, stop = 100.0, stopAtBroker = true)

    @Test fun eachLegsWorstCase() {
        assertEquals(1_500.0, AtStops.worst(long).loss)
        assertEquals(AtStops.How.TO_STOP, AtStops.worst(long).how)
        // A short to its stop above.
        assertEquals(600.0, AtStops.worst(AtStops.Leg("Paper", "NIFTY24500PE", -30, 80.0, stop = 100.0)).loss)
        // A bought leg with no stop: its whole value now.
        val bare = AtStops.worst(AtStops.Leg("Paper", "BANKNIFTY52000CE", 30, 200.0))
        assertEquals(6_000.0, bare.loss); assertEquals(AtStops.How.TO_ZERO, bare.how)
        // A short with no stop: no ceiling.
        val naked = AtStops.worst(AtStops.Leg("Paper", "NIFTY24000PE", -75, 50.0))
        assertNull(naked.loss); assertEquals(AtStops.How.NO_CEILING, naked.how)
        // A price already past the stop: nothing more to the stop.
        assertEquals(0.0, AtStops.worst(AtStops.Leg("Paper", "X", 10, 90.0, stop = 100.0)).loss)
        assertEquals(AtStops.How.PAST_STOP, AtStops.worst(AtStops.Leg("Paper", "Y", -10, 110.0, stop = 100.0)).how)
    }

    @Test fun againstTheLimitLeft() {
        assertEquals("On Zerodha, if every stop is hit from here: Rs 1,500 more - within the Rs 2,000 left of the daily loss limit " +
            "(NIFTY24800CE 75 Rs 1,500 to its 100 stop).", AtStops.account("Zerodha", listOf(long), 2_000.0))
        assertTrue(AtStops.account("Zerodha", listOf(long), 1_000.0)!!.contains("Rs 500 past the Rs 1,000 left of the daily loss limit"))
        assertTrue(AtStops.account("Zerodha", listOf(long), -50.0)!!.contains("the daily loss limit is already used up"))
        assertFalse(AtStops.account("Zerodha", listOf(long), null)!!.contains("limit"))
        val mixed = AtStops.account("Paper", listOf(AtStops.Leg("Paper", "A", 10, 50.0, stop = 40.0), AtStops.Leg("Paper", "B", -10, 50.0)), 5_000.0)!!
        assertTrue(mixed.startsWith("On Paper, if every stop is hit from here: at least Rs 100 more, and no ceiling - more than any limit"), mixed)
        assertTrue(mixed.contains("B short 10 is a short with no stop: its loss has no ceiling"), mixed)
        assertNull(AtStops.account("Paper", emptyList(), 1_000.0))
    }

    @Test fun theWholeAnswer() {
        assertNull(AtStops.say(emptyList(), emptyMap()))
        val paper = AtStops.Leg("Paper", "BANKNIFTY52000CE", 30, 200.0, stop = 180.0)
        val s = AtStops.say(listOf(paper, long), mapOf("Zerodha" to 2_000.0, "Paper" to 500.0))!!
        // Zerodha first, then paper; then what a stop is - and, an app-kept stop open, that it works only with the watch.
        assertTrue(s.indexOf("On Zerodha") < s.indexOf("On Paper"), s)
        assertTrue(s.contains("On Paper, if every stop is hit from here: Rs 600 more - Rs 100 past the Rs 500 left"), s)
        assertTrue(s.endsWith("works only while the order watch runs."), s)
        val atZerodha = AtStops.say(listOf(long), mapOf("Zerodha" to 2_000.0))!!
        assertTrue(atZerodha.endsWith("a gap or a fast market fills past it."), atZerodha)
        for (w in listOf("should", "I set", "I placed", "I closed", "close it")) assertFalse(s.contains(w), "$w: $s")
    }

    @Test fun leftOfTheDailyLimit() {
        val l = AccountGuard.Limits(maxDailyLoss = 2_000.0)
        fun book(pnl: Double, lim: AccountGuard.Limits = l) = Headroom.Book("Zerodha",
            AccountGuard.Account(capital = 100_000.0, equity = 100_000.0 + pnl, peakEquity = 100_000.0, dayPnl = pnl,
                holdings = emptyList(), ordersToday = 0, minuteOfDay = 600), lim, enforced = true)
        assertEquals(1_400.0, AtStops.left(book(-600.0)))
        assertEquals(2_500.0, AtStops.left(book(500.0)))
        assertNull(AtStops.left(book(-600.0, AccountGuard.Limits(maxDailyLoss = 0.0))))
        assertNull(AtStops.left(book(Double.NaN)))
    }

    @Test fun askedInItsOwnWordsIsTheLossQuestionAndNeverActs() {
        for (q in listOf("what's my worst case today?", "whats my worst case", "what if all my stops get hit", "how much do I lose if every stop is hit",
                "aaj kitna loss ho sakta hai", "sab stop hit ho gaye to kitna loss", "what is my worst possible loss today", "my worst case right now",
                "how much can I lose today?")) {
            assertEquals(Headroom.Asked.LOSS, Headroom.asked(q), q)
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertFalse(Bundle.acts(q), q)
            assertNull(Intents.quick(q), q)
        }
        // A setting of stops, a what-if on the market, a position's own case: not this.
        for (q in listOf("set all my stops to 100", "what if nifty falls 1%", "move my stops", "what's the worst case for my put",
                "remove all stops", "what if my stop gets hit on the put"))
            assertNull(Headroom.asked(q), q)
    }
}
