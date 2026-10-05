package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BreadthTest {
    /** Monday 5 October 2026; the week before it, Monday 28 September to Friday 2 October. */
    private val today = LocalDate.of(2026, 10, 5)
    private val lastWeek = (0L..4L).map { today.minusDays(7 - it) }

    /** A session of 1-minute candles from 09:15, [minutes] long, the price at minute i given by [px]. */
    private fun session(d: LocalDate, minutes: Int = 375, px: (Int) -> Double): List<Candle> {
        var prev = px(0)
        return (0 until minutes).map { i ->
            val c = px(i); val o = if (i == 0) c else prev; prev = c
            Candle(d.atTime(9, 15).plusMinutes(i.toLong()), o, maxOf(o, c) + 0.5, minOf(o, c) - 0.5, c)
        }
    }

    /** Last week flat at [base] (Friday's close = [base]); today [minutes] long, from [base] by [today] % at the end, linearly. */
    private fun index(base: Double, todayPct: Double, minutes: Int = 300, lastHourPct: Double? = null): List<Candle> {
        val past = lastWeek.flatMap { d -> session(d) { base } }
        val end = base * (1 + todayPct / 100)
        val now = session(today, minutes) { i ->
            if (lastHourPct == null || i < minutes - 60) base + (end - base) * i / (minutes - 1)
            else {
                // Up to the last hour on the line, then [lastHourPct] over the last hour.
                val at = base + (end - base) * (minutes - 61) / (minutes - 1)
                at * (1 + lastHourPct / 100 * (i - (minutes - 61)) / 60)
            }
        }
        return past + now
    }

    private fun asked(s: String) = Breadth.asked(s)

    @Test fun theQuestionsItAnswers() {
        for (s in listOf("Is the rally broad or narrow?", "is this fall broad based", "Jarvis, how broad is the move today?", "market breadth",
                "is the market rally narrow", "are all indices up?", "is it just banks?", "is the rally only bank nifty"))
            assertEquals(Breadth.Kind.BREADTH, asked(s)?.kind, s)
        for (s in listOf("which index is leading since the open?", "which index is lagging in the last hour", "who is leading the rally",
                "which index is strongest this week", "which index led since 11", "what's the leadership today"))
            assertEquals(Breadth.Kind.LEADERS, asked(s)?.kind, s)
        for (s in listOf("relative strength BankNifty vs Nifty this week", "BankNifty to Nifty ratio", "relative strength of bank nifty",
                "has BankNifty outperformed Nifty this month?", "BankNifty vs Nifty this week"))
            assertEquals(Breadth.Kind.RELATIVE, asked(s)?.kind, s)
        assertEquals(Breadth.Span.WEEK, asked("relative strength BankNifty vs Nifty this week")?.span)
        assertEquals(listOf(Market.BANKNIFTY, Market.NIFTY), asked("relative strength BankNifty vs Nifty this week")?.markets)
        assertEquals(Breadth.Span.MONTH, asked("has BankNifty outperformed Nifty this month?")?.span)
        assertEquals("in the last hour", asked("which index is lagging in the last hour")?.window?.label)
        assertTrue(asked("is the rally broad across sectors?")!!.sectors)
        assertTrue(asked("which sector is leading?")!!.sectors)
    }

    @Test fun compareAndTogetherKeepTheirOwn() {
        // Today's change with no stretch named is Compare's; moving with each other is Together's.
        for (s in listOf("is BankNifty stronger than Nifty?", "which index is strongest today?", "compare nifty and banknifty",
                "which index is the weakest", "Nifty vs Sensex", "is FinNifty outperforming BankNifty"))
            { assertNull(asked(s), s); assertTrue(Compare.asked(s), s) }
        for (s in listOf("is BankNifty moving with Nifty?", "are nifty and bank nifty moving together", "is banknifty diverging from nifty"))
            { assertNull(asked(s), s); assertTrue(Together.asked(s), s) }
    }

    @Test fun otherQuestionsAreLeftAlone() {
        for (s in listOf("is the range narrow today?", "narrow range day?", "what's the put call ratio", "why is the rally narrow",
                "will the rally stay broad", "any news on banks?", "my P&L today", "how did Nifty do this week", "what's the risk reward ratio",
                "buy nifty", "set an alert when bank nifty leads", "how much did Nifty move in the last hour", "gold vs nifty this week"))
            assertNull(asked(s), s)
    }

    @Test fun theHandlersBeforeItDoNotTakeItsQuestions() {
        for (s in listOf("Is the rally broad or narrow?", "which index is leading since the open?", "relative strength BankNifty vs Nifty this week",
                "which index is lagging in the last hour", "who is leading the rally", "is the rally broad across sectors?")) {
            val p = Ask.parse(s)
            assertNull(p.order, s); assertNull(p.command, s)
            assertFalse(Bundle.acts(s), s)
            assertNull(Honest.asked(s), s)
            assertNull(Structure.asked(s), s)
            assertNull(ChainIntel.asked(s), s)
            assertFalse(DataAge.asked(s), s)
            assertFalse(TradeCase.asked(s), s)
            assertNull(Scenarios.asked(s), s)
            assertNull(MarketStory.asked(s), s)
        }
    }

    @Test fun aBroadRallyIsSaidBroad() {
        val bars = mapOf(Market.NIFTY to index(25_000.0, 0.8), Market.BANKNIFTY to index(55_000.0, 0.7),
            Market.FINNIFTY to index(26_000.0, 0.6), Market.SENSEX to index(82_000.0, 0.9))
        val s = Breadth.answer(asked("is the rally broad or narrow")!!, bars, today)
        assertTrue("the move is broad across the indices: all 4 are up, and evenly (Sensex +0.90%, Nifty +0.80%, BankNifty +0.70%, FinNifty +0.60%)" in s, s)
        assertTrue("as of 14:14" in s && "no sector indices" in s, s)
        assertTrue("In the last hour 4 of 4 rose" in s, s)
    }

    @Test fun aNarrowRallyIsSaidNarrow() {
        val bars = mapOf(Market.NIFTY to index(25_000.0, 0.5), Market.BANKNIFTY to index(55_000.0, 1.5),
            Market.FINNIFTY to index(26_000.0, -0.3), Market.SENSEX to index(82_000.0, -0.2))
        val s = Breadth.breadth(bars, today)
        assertTrue("the rally is narrow: only 2 of 4 indices are up (BankNifty, Nifty)" in s, s)
        val three = bars + (Market.SENSEX to index(82_000.0, 0.4))
        assertTrue("the rally is mostly broad: 3 of 4 indices are up, but not FinNifty" in Breadth.breadth(three, today), Breadth.breadth(three, today))
        val uneven = mapOf(Market.NIFTY to index(25_000.0, 1.2), Market.BANKNIFTY to index(55_000.0, 0.2))
        assertTrue("all 2 indices are up, but unevenly: Nifty +1.20% carries it while BankNifty is only +0.20%" in Breadth.breadth(uneven, today), Breadth.breadth(uneven, today))
    }

    @Test fun sectorsAreSaidMissing() {
        val bars = mapOf(Market.NIFTY to index(25_000.0, 0.5), Market.BANKNIFTY to index(55_000.0, 0.4))
        val s = Breadth.answer(asked("is the rally broad across sectors?")!!, bars, today)
        assertTrue(s.startsWith(Breadth.NO_SECTORS), s)
        assertTrue(Breadth.answer(asked("which sector is leading?")!!, bars, today).startsWith(Breadth.NO_SECTORS))
    }

    @Test fun leadersSinceTheOpenAndInTheLastHour() {
        val bars = mapOf(Market.NIFTY to index(25_000.0, 0.5, lastHourPct = -0.2), Market.BANKNIFTY to index(55_000.0, 1.0, lastHourPct = 0.3),
            Market.FINNIFTY to index(26_000.0, 0.2, lastHourPct = 0.0), Market.SENSEX to index(82_000.0, 0.4, lastHourPct = -0.1))
        val s = Breadth.answer(asked("who is leading the rally")!!, bars, today)
        assertTrue(s.startsWith("As of 14:14, Boss. Since the open: BankNifty"), s)
        assertTrue("In the last hour: BankNifty +0.30%" in s && "Nifty -0.20% - BankNifty leads and Nifty lags, 0.50 points of percentage apart." in s, s)
        val h = Breadth.answer(asked("which index is lagging in the last hour")!!, bars, today)
        assertFalse("Since the open" in h, h)
        // An index whose feed stopped is left out, never ranked on old prices.
        val stopped = bars + (Market.FINNIFTY to index(26_000.0, 0.2, minutes = 200))
        val st = Breadth.answer(asked("which index is leading since the open?")!!, stopped, today)
        assertTrue("Left out: FinNifty (its candles stop at 12:34)." in st && "FinNifty +" !in st, st)
    }

    @Test fun leadersOverTheWeek() {
        val bars = mapOf(Market.NIFTY to index(25_000.0, 0.5), Market.BANKNIFTY to index(55_000.0, 1.0))
        val s = Breadth.answer(asked("which index is strongest this week")!!, bars, today)
        assertTrue(s.startsWith("This week, Boss (1 session, from the close before it to 14:14 on Mon 5 Oct): BankNifty +1.00%, Nifty +0.50%"), s)
    }

    @Test fun relativeStrengthIsTheRatioWithNumbers() {
        val bars = mapOf(Market.NIFTY to index(25_000.0, 0.5), Market.BANKNIFTY to index(50_000.0, 1.5))
        val s = Breadth.answer(asked("relative strength BankNifty vs Nifty this week")!!, bars, today)
        assertTrue(s.startsWith("BankNifty against Nifty this week (1 session, from the close before to 14:14), Boss: the BankNifty/Nifty ratio went from 2.0000 to 2.0199, +1.00%."), s)
        assertTrue("BankNifty +1.50% (50,000.00 to 50,750.00), Nifty +0.50% (25,000.00 to 25,125.00) - BankNifty has been the stronger of the two, by 1.00% on the ratio." in s, s)
        // Last week both were flat.
        val lw = Breadth.answer(Breadth.Asked(Breadth.Kind.RELATIVE, listOf(Market.BANKNIFTY, Market.NIFTY), span = Breadth.Span.LAST_WEEK), bars, today)
        assertTrue("5 sessions, from the first open" in lw && "the ratio barely moved (+0.00%): the two kept pace" in lw, lw)
        // Nothing yet this month before its first session... and no data at all said honestly.
        assertEquals("I don't have the indices' candles on the phone for that, Boss, so I can't say.", Breadth.answer(asked("BankNifty to Nifty ratio")!!, emptyMap(), today))
    }

    @Test fun anOldSessionIsDatedNotCalledToday() {
        val bars = mapOf(Market.NIFTY to index(25_000.0, 0.5), Market.BANKNIFTY to index(55_000.0, 1.0))
        val s = Breadth.answer(asked("who is leading the rally")!!, bars, today.plusDays(1))
        assertTrue(s.startsWith("As of 14:14 on Mon 5 Oct (the last session on the phone), Boss."), s)
    }

    @Test fun theWrapUpLeadershipFact() {
        assertEquals("Of the indices, BankNifty led (+1.10%) and Sensex lagged (+0.20%) on the day.",
            Breadth.leadership(listOf(Market.NIFTY to 0.5, Market.BANKNIFTY to 1.1, Market.SENSEX to 0.2)))
        assertNull(Breadth.leadership(listOf(Market.NIFTY to 0.5, Market.BANKNIFTY to 0.6)))
        assertNull(Breadth.leadership(listOf(Market.NIFTY to 0.5)))
        assertNotNull(Breadth.leadership(listOf(Market.NIFTY to -0.5, Market.BANKNIFTY to 0.0)))
    }
}
