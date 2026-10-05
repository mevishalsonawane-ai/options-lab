package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsualIndexTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val now = today.atTime(11, 0)

    private val UNNAMED = listOf("what's the trend", "what are the levels today", "what are the support and resistance levels",
        "is it going up", "how is it looking", "what is the support", "why is it falling", "trend kya hai", "levels batao", "any patterns on the 15 minute chart")
    private val NOT_UNNAMED = listOf("what's the trend on nifty", "banknifty levels", "how is the market", "how are all the indices",
        "what's my p&l", "how are my positions", "buy 1 lot nifty 24500 ce", "stop all strategies", "what is vix", "how is gold",
        "what's the news", "should i buy", "what is a doji", "hello", "is the market bullish", "which index is strongest")

    @Test fun readsTheUnnamedMarketQuestionOnly() {
        for (s in UNNAMED) assertTrue(UsualIndex.unnamed(s), s)
        for (s in NOT_UNNAMED) assertFalse(UsualIndex.unnamed(s), s)
    }

    @Test fun theReadingNamesTheIndexAndKeepsTheQuestion() {
        for (s in UNNAMED) {
            val r = UsualIndex.reading(s, Market.BANKNIFTY)
            assertNotNull(r, s)
            val p = Ask.parse(r)
            assertEquals(listOf(Market.BANKNIFTY), p.markets, r)
            assertEquals(Ask.parse(s).topics, p.topics, r)
            assertNull(p.order, r); assertNull(p.command, r)
        }
        // A named index always wins: nothing to read.
        for (s in NOT_UNNAMED) assertNull(UsualIndex.reading(s, Market.BANKNIFTY), s)
        // Never Gold or the VIX.
        assertNull(UsualIndex.reading("what's the trend", Market.GOLD))
        assertNull(UsualIndex.reading("what's the trend", Market.VIX))
    }

    @Test fun readsBossCorrections() {
        val bn = listOf("no banknifty", "no, BankNifty", "no bank nifty", "nope banknifty", "no i meant banknifty", "I meant BankNifty",
            "i was asking about bank nifty", "nahi banknifty", "nahi banknifty ka", "banknifty ka batao", "not nifty, banknifty",
            "no, BankNifty please", "arre nahi bank nifty ka", "mera matlab banknifty se tha", "no bnf", "not that one, banknifty")
        for (s in bn) assertEquals(Market.BANKNIFTY, UsualIndex.correction(s), s)
        assertEquals(Market.FINNIFTY, UsualIndex.correction("no finnifty"))
        assertEquals(Market.SENSEX, UsualIndex.correction("i meant sensex"))
        assertEquals(Market.NIFTY, UsualIndex.correction("no, Nifty"))
        for (s in listOf("no", "yes", "no gold", "no vix", "banknifty", "how is banknifty", "what about banknifty", "no the trend",
                "no banknifty is falling", "buy banknifty", "no buy 1 lot banknifty 52000 ce", "stop banknifty strategy", "no thanks"))
            assertNull(UsualIndex.correction(s), s)
        for (s in bn) { val p = Ask.parse(s); assertNull(p.order, s); assertNull(p.command, s) }
    }

    @Test fun learnedOnlyAfterThreeCorrectionsMoreThanTheRest() {
        var log = UsualIndex.Log()
        log = UsualIndex.heard(log, Market.BANKNIFTY, now.minusDays(5))
        log = UsualIndex.heard(log, Market.BANKNIFTY, now.minusDays(2))
        assertNull(UsualIndex.learned(log, now))
        log = UsualIndex.heard(log, Market.BANKNIFTY, now.minusHours(1))
        val r = UsualIndex.learned(log, now)!!
        assertEquals(Market.BANKNIFTY, r.market); assertEquals(3, r.times)
        assertTrue(UsualIndex.took(r).startsWith("BankNifty, as you usually mean, Boss"))
        // Corrected back to Nifty as often: no longer learned.
        repeat(3) { log = UsualIndex.heard(log, Market.NIFTY, now.minusMinutes(30L - it)) }
        assertNull(UsualIndex.learned(log, now))
        // Nifty itself is never "learned": it is already the default.
        var n = UsualIndex.Log()
        repeat(4) { n = UsualIndex.heard(n, Market.NIFTY, now.minusHours(it.toLong() + 1)) }
        assertNull(UsualIndex.learned(n, now))
    }

    @Test fun oldCorrectionsAndTheResetCountForNothing() {
        var log = UsualIndex.Log()
        repeat(3) { log = UsualIndex.heard(log, Market.BANKNIFTY, now.minusDays(UsualIndex.WINDOW_DAYS + 1 + it)) }
        assertNull(UsualIndex.learned(log, now))
        repeat(3) { log = UsualIndex.heard(log, Market.BANKNIFTY, now.minusHours(it.toLong() + 1)) }
        assertNotNull(UsualIndex.learned(log, now))
        val reset = UsualIndex.reset(log, now)
        assertNull(UsualIndex.learned(reset, now.plusMinutes(1)))
        assertTrue(UsualIndex.sayReset(log, now).startsWith("Done, Boss"))
    }

    @Test fun aCorrectionCountsOnlyJustAfter() {
        assertTrue(UsualIndex.fresh(now, now.plusMinutes(2)))
        assertFalse(UsualIndex.fresh(now, now.plusMinutes(UsualIndex.REACT_MINUTES + 1)))
        assertFalse(UsualIndex.fresh(now, now.minusMinutes(1)))
    }

    @Test fun answersAndUndoAreNeverActions() {
        val which = listOf("which index do i usually mean", "what index do you assume", "which index do you take when i dont name one",
            "what's my usual index", "do you know which index i usually mean", "mera usual index kaunsa hai", "main kaunsa index usually poochta hoon")
        val reset = listOf("use Nifty when I don't name an index", "reset my default index", "don't assume my index", "use nifty when i don't name one",
            "mera usual index bhool jao")
        for (s in which) assertEquals(UsualIndex.Request.WHICH, UsualIndex.asked(s), s)
        for (s in reset) assertEquals(UsualIndex.Request.RESET, UsualIndex.asked(s), s)
        assertEquals(UsualIndex.Request.RESET, UsualIndex.asked(UsualIndex.UNDO))
        for (s in which + reset) { val p = Ask.parse(s); assertNull(p.order, s); assertNull(p.command, s) }
        for (s in listOf("which index is strongest", "what is nifty at", "no banknifty", "what's the trend")) assertNull(UsualIndex.asked(s), s)
        assertTrue(UsualIndex.say(UsualIndex.Log(), now).contains("Nifty"))
    }

    @Test fun inTheLedgerWithItsUndoAndResetByTheWeeksUndo() {
        var log = UsualIndex.Log()
        repeat(3) { log = UsualIndex.heard(log, Market.BANKNIFTY, now.minusHours(it.toLong() + 1)) }
        val i = Learnings.Inputs(usualIndex = log)
        val item = Learnings.items(i, now).single { it.area == Learnings.Area.USUAL_INDEX }
        assertEquals(UsualIndex.UNDO, item.undo)
        assertTrue(item.personal)
        val u = Learnings.undo(i, now)
        assertEquals(Market.BANKNIFTY, u.usualIndex.single().market)
        assertTrue(Learnings.offer(u).contains("BankNifty"))
        // Locked: not named.
        assertFalse(Learnings.say(Learnings.items(i, now), Learnings.Ask.WEEK, today, locked = true).contains("BankNifty"))
    }
}
