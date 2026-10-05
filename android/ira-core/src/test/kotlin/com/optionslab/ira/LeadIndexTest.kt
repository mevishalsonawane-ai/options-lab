package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeadIndexTest {
    private val today = LocalDate.of(2026, 10, 5)

    /** The kinds tally ([SelfDoubt.count]) with [bank] BankNifty and [nifty] Nifty questions a day on each of [days] days before today. */
    private fun tally(days: Int, bank: Int, nifty: Int, from: Int = 1): DoubtTally =
        (from until from + days).associate { d ->
            today.minusDays(d.toLong()) to buildMap {
                if (bank > 0) put("market:BANKNIFTY", bank)
                if (nifty > 0) put("market:NIFTY", nifty)
                put("topic:LEVELS", bank + nifty)
            }
        }

    private fun snap(m: Market, price: Double) = Snapshot(m, today.atTime(10, 0), true, price, price * 0.99, price, price, price, null, null,
        emptyList(), null, null, emptyList(), emptyList(), emptyList())

    @Test fun learnedFromTheTallyAlreadyKept() {
        // 4 days x 3 BankNifty, 1 Nifty: 12 of 16, on 4 days.
        val r = assertNotNull(LeadIndex.learned(tally(4, 3, 1), LeadIndex.Log(), today))
        assertEquals(Market.BANKNIFTY, r.market); assertEquals(12, r.times); assertEquals(4, r.others); assertEquals(4, r.days)
        assertEquals(today.minusDays(1), r.newest)
        assertTrue(r.say().contains("12 of your 16 mentions"), r.say())
        // Counted by mention (a question naming both counts for each): never called "questions".
        assertFalse(r.say().contains("questions"), r.say()); assertFalse(LeadIndex.say(r).contains("questions"), LeadIndex.say(r))
        // Built the way the app builds it: one question at a time, counted on its day.
        var t: DoubtTally = emptyMap()
        for (d in 1..4) repeat(3) { t = SelfDoubt.count(t, today.minusDays(d.toLong()), "what are the levels on banknifty") }
        assertEquals(Market.BANKNIFTY, LeadIndex.learned(t, LeadIndex.Log(), today)?.market)
    }

    @Test fun tooFewTooSpreadOrNiftyLearnsNothing() {
        assertNull(LeadIndex.learned(emptyMap(), LeadIndex.Log(), today))
        // Under the count (9), on too few days (2), or under two in three (12 of 20).
        assertNull(LeadIndex.learned(tally(3, 3, 0), LeadIndex.Log(), today))
        assertNull(LeadIndex.learned(tally(2, 6, 0), LeadIndex.Log(), today))
        assertNull(LeadIndex.learned(tally(4, 3, 2), LeadIndex.Log(), today))
        // Nifty asked most: Nifty first, as always - nothing learned.
        assertNull(LeadIndex.learned(tally(10, 0, 5), LeadIndex.Log(), today))
        // Older than the window: not counted.
        assertNull(LeadIndex.learned(tally(4, 3, 0, from = 31), LeadIndex.Log(), today))
        // Questions naming neither (the market, Sensex) never count either way.
        val other = tally(4, 3, 0).mapValues { it.value + ("market:SENSEX" to 20) + ("phrasing:NO_INDEX" to 30) }
        assertEquals(Market.BANKNIFTY, LeadIndex.learned(other, LeadIndex.Log(), today)?.market)
    }

    @Test fun theUndoStartsTheCountAfresh() {
        val t = tally(6, 3, 0)
        assertNotNull(LeadIndex.learned(t, LeadIndex.Log(), today))
        val log = LeadIndex.reset(today)
        assertNull(LeadIndex.learned(t, log, today))
        // Today's questions after the undo do not count either (afresh from tomorrow); later days do.
        val later = t + mapOf(today to mapOf("market:BANKNIFTY" to 20))
        assertNull(LeadIndex.learned(later, log, today))
        val next = (1..4).associate { today.plusDays(it.toLong()) to mapOf("market:BANKNIFTY" to 3) }
        assertEquals(Market.BANKNIFTY, LeadIndex.learned(t + next, log, today.plusDays(4))?.market)
    }

    @Test fun onlyTheOrderChanges() {
        val both = listOf(Market.NIFTY, Market.BANKNIFTY)
        assertEquals(both, LeadIndex.order(both, null))
        assertEquals(both, LeadIndex.order(both, Market.NIFTY))
        assertEquals(listOf(Market.BANKNIFTY, Market.NIFTY), LeadIndex.order(both, Market.BANKNIFTY))
        assertEquals(listOf(Market.NIFTY), LeadIndex.order(listOf(Market.NIFTY), Market.BANKNIFTY))
        // Same members, same count: nothing added or dropped.
        assertEquals(both.toSet(), LeadIndex.order(both, Market.BANKNIFTY).toSet())
    }

    @Test fun theGreetingNamesBossIndexFirstWithTheSameWords() {
        val now = LocalDateTime.of(2026, 10, 5, 10, 0)
        val snaps = mapOf(Market.NIFTY to snap(Market.NIFTY, 24600.0), Market.BANKNIFTY to snap(Market.BANKNIFTY, 55100.0))
        val usual = Ira().answer("good morning", snaps, emptyList(), now = now).text
        val led = Ira(lead = Market.BANKNIFTY).answer("good morning", snaps, emptyList(), now = now).text
        assertTrue(usual.indexOf("Nifty is at") < usual.indexOf("BankNifty is at"), usual)
        assertTrue(led.indexOf("BankNifty is at") < led.indexOf("Nifty is at 24"), led)
        // The same words, only the two indices' places swapped.
        val parts = usual.substringAfter("The market is open. ").substringBefore(". Ask").split("; ")
        assertEquals(2, parts.size, usual)
        assertEquals(usual.replace(parts.joinToString("; "), parts.reversed().joinToString("; ")), led)
        // Nifty or nothing learned: as always.
        assertEquals(usual, Ira(lead = Market.NIFTY).answer("good morning", snaps, emptyList(), now = now).text)
        // A market question is never touched by it (which index is answered is UsualIndex's).
        assertEquals(Ira().answer("what are the levels", snaps, emptyList(), now = now).text,
            Ira(lead = Market.BANKNIFTY).answer("what are the levels", snaps, emptyList(), now = now).text)
    }

    /** Market questions that put Nifty first in their words - each was a Nifty answer, and must stay one. */
    private val NOT_UNDO = listOf("nifty pehle batao", "nifty ko pehle lo", "give nifty first", "say nifty first")

    @Test fun bossCanAskAndUndo() {
        for (q in listOf("which index do you mention first", "Jarvis, why do you say BankNifty first?", "why is bank nifty first",
                "which index do I ask about most", "what index do i ask you about the most", "kaunsa index pehle bolte ho",
                "banknifty pehle kyun bolte ho", "main kaunsa index sabse zyada puchta hoon"))
            assertEquals(LeadIndex.Request.WHICH, LeadIndex.asked(q), q)
        for (q in listOf(LeadIndex.UNDO, "say Nifty first again please", "name nifty first again", "go back to naming nifty first",
                "don't say bank nifty first", "stop saying banknifty first", "nifty pehle bolo phir se", "banknifty pehle mat bolo"))
            assertEquals(LeadIndex.Request.RESET, LeadIndex.asked(q), q)
        // "Stop saying BankNifty first" is that undo, never a stop of an arm by that name.
        assertNull(Ask.parse("stop saying banknifty first").command); assertNull(Ask.parse("stop saying banknifty first").order)
        // A market question that merely puts Nifty first is never the undo: it keeps its market route.
        for (q in NOT_UNDO) {
            assertNull(LeadIndex.asked(q), q)
            assertEquals("Market", CoverageTest().feature(q), q)
        }
        // Not his market questions, not UsualIndex's, never an order or a command.
        for (q in listOf("how is banknifty", "what are the levels on nifty", "which index do i usually mean", "buy banknifty first",
                "first target on nifty", "use nifty when i dont name an index", "say that again"))
            assertNull(LeadIndex.asked(q), q)
        for (q in listOf("which index do you mention first", LeadIndex.UNDO, "nifty pehle bolo phir se", "say nifty first again",
                "stop saying banknifty first") + NOT_UNDO) {
            val p = Ask.parse(q); assertNull(p.order, q); assertNull(p.command, q); assertFalse(Bundle.acts(q), q)
            assertNull(UsualIndex.asked(q), q)
        }
    }

    @Test fun whatIsSaid() {
        val r = assertNotNull(LeadIndex.learned(tally(4, 3, 1), LeadIndex.Log(), today))
        val said = LeadIndex.say(r)
        assertTrue(said.contains("BankNifty first") && said.contains(LeadIndex.UNDO) && said.contains(LeadIndex.ONLY_ORDER), said)
        assertTrue(LeadIndex.say(null).startsWith("I name Nifty first"))
        assertTrue(LeadIndex.sayReset(r).startsWith("Done, Boss: Nifty first again"))
        // Locked: one neutral reply, the index learned (or whether one was) never named.
        assertFalse(LeadIndex.RESET_LOCKED.contains("BankNifty") || LeadIndex.RESET_LOCKED.contains("already"), LeadIndex.RESET_LOCKED)
        assertTrue(LeadIndex.ledgerWhat(r).startsWith("BankNifty named first"))
        // In the ledger, with its undo; reset by "undo everything you learned this week"; never on a locked phone.
        val inputs = Learnings.Inputs(tally = tally(4, 3, 1))
        val item = Learnings.items(inputs, today.atTime(12, 0)).single { it.area == Learnings.Area.LEAD_INDEX }
        assertEquals(LeadIndex.UNDO, item.undo)
        val u = Learnings.undo(inputs, today.atTime(12, 0))
        assertEquals(listOf(Market.BANKNIFTY), u.leadIndex.map { it.market })
        assertTrue(Learnings.offer(u).contains("Nifty first again"))
        assertFalse(Learnings.say(Learnings.items(inputs, today.atTime(12, 0)), Learnings.Ask.ALL, today, locked = true).contains("named first"))
        // Undone: gone from the ledger.
        val after = Learnings.Inputs(tally = tally(4, 3, 1), leadIndex = LeadIndex.reset(today))
        assertTrue(Learnings.items(after, today.atTime(12, 0)).none { it.area == Learnings.Area.LEAD_INDEX })
    }
}
