package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeekAheadTest {
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private val holidays = mapOf(d(10, 2) to "Mahatma Gandhi Jayanti", d(10, 20) to "Dussehra")
    private fun trading(x: LocalDate) = x.dayOfWeek != DayOfWeek.SATURDAY && x.dayOfWeek != DayOfWeek.SUNDAY && x !in holidays
    private fun holiday(x: LocalDate) = holidays[x]

    /** Nifty on Tuesdays (20 Oct a holiday, so Mon 19 Oct), BankNifty monthly only. */
    private val expiries = mapOf(
        Market.NIFTY to listOf(d(10, 6), d(10, 13), d(10, 19), d(10, 27), d(11, 3), d(11, 10)),
        Market.BANKNIFTY to listOf(d(10, 27), d(11, 24), d(12, 29)),
    )

    private fun say(today: LocalDate, which: WeekAhead.Which = WeekAhead.Which.THIS, afterClose: Boolean = false,
                    owner: List<Events.Event> = emptyList(), unlocked: Boolean = true, exp: Map<Market, List<LocalDate>> = expiries) =
        WeekAhead.answer(which, today, afterClose, ::trading, ::holiday, exp,
            Events.builtIn(today.minusDays(7), today.plusDays(21)), owner, unlocked)

    @Test fun asks() {
        listOf("what does this week look like", "week ahead", "what's the week ahead", "is this an expiry week",
            "is it expiry week", "how many trading days this week", "what's coming up this week", "expiries and holidays this week",
            "expiry week plan", "this week's calendar", "is hafte kya hai", "anything important this week",
            "how many sessions left this week", "weekly planner").forEach { assertEquals(WeekAhead.Which.THIS, WeekAhead.asked(it), it) }
        listOf("what does next week look like", "plan for next week", "next week's expiries", "agle hafte kya hai",
            "how many trading days next week").forEach { assertEquals(WeekAhead.Which.NEXT, WeekAhead.asked(it), it) }
        listOf("how did i do this week", "how was the market this week", "my p&l this week", "what did i say about expiry week",
            "when is the next expiry", "is tomorrow a holiday", "what day is expiry this week", "expected move this week",
            "how much did i make this week", "relative strength banknifty vs nifty this week", "what have you learned this week",
            "will nifty fall this week").forEach { assertNull(WeekAhead.asked(it), it) }
    }

    @Test fun weekendMeansTheComingWeek() {
        assertEquals(d(10, 19), WeekAhead.monday(WeekAhead.Which.THIS, d(10, 17)))
        assertEquals(d(10, 26), WeekAhead.monday(WeekAhead.Which.NEXT, d(10, 17)))
        assertEquals(d(10, 5), WeekAhead.monday(WeekAhead.Which.THIS, d(10, 9)))
    }

    @Test fun thisWeek() {
        val s = say(d(10, 5))
        assertTrue(s.startsWith("This week, Boss (Mon 5 Oct to Fri 9 Oct): 5 trading days."), s)
        assertTrue("Tue 6 Oct (tomorrow): Nifty weekly expiry." in s, s)
        assertTrue("No expiry that week for BankNifty (next Tue 27 Oct)." in s, s)
        assertTrue(s.endsWith("the plan is yours."), s)
        val mid = say(d(10, 7), afterClose = true)
        assertTrue("5 trading days, 2 sessions left." in mid, mid)
        val open = say(d(10, 7))
        assertTrue("3 sessions left counting today" in open, open)
    }

    @Test fun holidayMovesTheExpiry() {
        val s = say(d(10, 17))
        assertTrue(s.startsWith("The coming week, Boss (Mon 19 Oct to Fri 23 Oct): 4 trading days."), s)
        assertTrue("Mon 19 Oct: Nifty weekly expiry (not its usual Tue, Tue 20 Oct is a holiday)." in s, s)
        assertTrue("Tue 20 Oct: market closed (Dussehra)." in s, s)
    }

    @Test fun monthlyAndTheFed() {
        val s = say(d(10, 23), WeekAhead.Which.NEXT)
        assertTrue("Tue 27 Oct: Nifty monthly expiry; BankNifty monthly expiry." in s, s)
        assertTrue("Thu 29 Oct: US Fed decision overnight (FOMC)." in s, s)
    }

    @Test fun longBreak() {
        val s = say(d(9, 28))
        assertTrue("Thu 1 Oct: the last session before 3 days shut (next session Mon 5 Oct)." in s, s)
        assertTrue("Fri 2 Oct: market closed (Mahatma Gandhi Jayanti)." in s, s)
        assertFalse("Fri 25 Sep" in s)
    }

    @Test fun ownEventsOnlyUnlocked() {
        val mine = listOf(Events.Event(d(10, 8), "RBI policy", owner = true))
        assertTrue("Thu 8 Oct: your event: RBI policy." in say(d(10, 5), owner = mine))
        val locked = say(d(10, 5), owner = mine, unlocked = false)
        assertFalse("RBI" in locked, locked)
        assertTrue("1 event you added is said on an unlocked phone only." in locked, locked)
    }

    @Test fun noContracts() {
        val s = say(d(10, 5), exp = emptyMap())
        assertTrue("no expiry dates loaded yet" in s, s)
    }
}
