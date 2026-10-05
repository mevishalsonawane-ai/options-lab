package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeadPartTest {
    private val today = LocalDate.of(2026, 10, 5)

    /** The kinds tally ([SelfDoubt.count]) with [levels], [trend] and [patterns] questions a day on each of [days] days before today. */
    private fun tally(days: Int, levels: Int, trend: Int, patterns: Int = 0, from: Int = 1): DoubtTally =
        (from until from + days).associate { d ->
            today.minusDays(d.toLong()) to buildMap {
                if (levels > 0) put("topic:LEVELS", levels)
                if (trend > 0) put("topic:TREND", trend)
                if (patterns > 0) put("topic:PATTERNS", patterns)
                put("market:NIFTY", levels + trend + patterns)
            }
        }

    private val now = LocalDateTime.of(2026, 10, 5, 11, 0)

    private fun snap(m: Market, price: Double) = Snapshot(m, now, true, price, price * 0.99, price - 40, price + 30, price - 60, null, null,
        listOf(TrendRead(15, true, price - 20, now.minusMinutes(50))),
        null, null,
        listOf(Level("Day high", price + 30)), listOf(Level("Day low", price - 60)), emptyList())

    @Test fun learnedFromTheTallyAlreadyKept() {
        // 4 days x 3 levels, 1 trend: 12 of 16, on 4 days.
        val r = assertNotNull(LeadPart.learned(tally(4, 3, 1), LeadPart.Log(), today))
        assertEquals(Topic.LEVELS, r.part); assertEquals(12, r.times); assertEquals(4, r.trend); assertEquals(0, r.other); assertEquals(4, r.days)
        assertEquals(today.minusDays(1), r.newest)
        assertTrue(r.say().contains("the levels 12 times on 4 days"), r.say())
        // Built the way the app builds it: one question at a time, counted on its day.
        var t: DoubtTally = emptyMap()
        for (d in 1..4) repeat(3) { t = SelfDoubt.count(t, today.minusDays(d.toLong()), "what are the levels on nifty") }
        assertEquals(Topic.LEVELS, LeadPart.learned(t, LeadPart.Log(), today)?.part)
        // Patterns, likewise.
        assertEquals(Topic.PATTERNS, LeadPart.learned(tally(4, 0, 1, 3), LeadPart.Log(), today)?.part)
    }

    @Test fun tooFewTooSpreadOrTheTrendLearnsNothing() {
        assertNull(LeadPart.learned(emptyMap(), LeadPart.Log(), today))
        // Under the count (9), on too few days (2), or under half (12 of 28).
        assertNull(LeadPart.learned(tally(3, 3, 0), LeadPart.Log(), today))
        assertNull(LeadPart.learned(tally(2, 6, 0), LeadPart.Log(), today))
        assertNull(LeadPart.learned(tally(4, 3, 4), LeadPart.Log(), today))
        // The trend asked as much: it leads already - nothing learned.
        assertNull(LeadPart.learned(tally(4, 3, 3), LeadPart.Log(), today))
        assertNull(LeadPart.learned(tally(10, 0, 5), LeadPart.Log(), today))
        // Levels and patterns tied: no clear part.
        assertNull(LeadPart.learned(tally(5, 2, 0, 2), LeadPart.Log(), today))
        // Older than the window: not counted.
        assertNull(LeadPart.learned(tally(4, 3, 0, from = 31), LeadPart.Log(), today))
        // Other kinds (the news, the account, how the market is doing) never count either way.
        val other = tally(4, 3, 0).mapValues { it.value + ("topic:NEWS" to 20) + ("topic:OVERVIEW" to 30) }
        assertEquals(Topic.LEVELS, LeadPart.learned(other, LeadPart.Log(), today)?.part)
    }

    @Test fun theUndoStartsTheCountAfresh() {
        val t = tally(6, 3, 0)
        assertNotNull(LeadPart.learned(t, LeadPart.Log(), today))
        val log = LeadPart.reset(today)
        assertNull(LeadPart.learned(t, log, today))
        val later = t + mapOf(today to mapOf("topic:LEVELS" to 20))
        assertNull(LeadPart.learned(later, log, today))
        val next = (1..4).associate { today.plusDays(it.toLong()) to mapOf("topic:LEVELS" to 3) }
        assertEquals(Topic.LEVELS, LeadPart.learned(t + next, log, today.plusDays(4))?.part)
    }

    @Test fun onlyTheOrderChanges() {
        val parts = listOf(Topic.TREND to "t", Topic.LEVELS to "l", Topic.PATTERNS to "p")
        assertEquals(listOf("t", "l", "p"), LeadPart.order(parts, null))
        assertEquals(listOf("t", "l", "p"), LeadPart.order(parts, Topic.TREND))
        assertEquals(listOf("l", "t", "p"), LeadPart.order(parts, Topic.LEVELS))
        assertEquals(listOf("p", "t", "l"), LeadPart.order(parts, Topic.PATTERNS))
        assertEquals(listOf("t", "l", "p"), LeadPart.order(parts, Topic.NEWS))
        assertTrue(LeadPart.reorders(setOf(Topic.OVERVIEW)))
        assertFalse(LeadPart.reorders(setOf(Topic.OVERVIEW, Topic.TREND))); assertFalse(LeadPart.reorders(setOf(Topic.WHY)))
    }

    @Test fun aPlainOverviewSaysThePartRightAfterThePriceWithTheSameWords() {
        val snaps = mapOf(Market.NIFTY to snap(Market.NIFTY, 24600.0), Market.BANKNIFTY to snap(Market.BANKNIFTY, 55100.0))
        for (q in listOf("how is nifty", "how is banknifty doing", "how are nifty and banknifty")) {
            assertTrue(LeadPart.reorders(Ask.parse(q).topics), "$q: ${Ask.parse(q).topics}")
            val usual = Ira().answer(q, snaps, emptyList(), now = now)
            val led = Ira(leadPart = Topic.LEVELS).answer(q, snaps, emptyList(), now = now)
            assertTrue(usual.text.indexOf("trend is up") < usual.text.indexOf("Nearest level above"), usual.text)
            assertTrue(led.text.indexOf("Nearest level above") < led.text.indexOf("trend is up"), led.text)
            assertTrue(led.text.indexOf("is at") < led.text.indexOf("Nearest level above"), led.text)
            // The same sentences and facts, only their order changed.
            assertEquals(BargeIn.sentences(usual.text).sorted(), BargeIn.sentences(led.text).sorted(), q)
            assertEquals(usual.text.length, led.text.length)
            assertEquals(usual.facts, led.facts)
            // Nothing learned (or the trend): as always.
            assertEquals(usual.text, Ira(leadPart = null).answer(q, snaps, emptyList(), now = now).text)
            assertEquals(usual.text, Ira(leadPart = Topic.TREND).answer(q, snaps, emptyList(), now = now).text)
        }
        // Any other question is never touched by it.
        for (q in listOf("what are the levels", "what is the trend on nifty", "why is nifty up", "any patterns on nifty", "good morning"))
            assertEquals(Ira().answer(q, snaps, emptyList(), now = now).text, Ira(leadPart = Topic.LEVELS).answer(q, snaps, emptyList(), now = now).text, q)
    }

    /** Market questions that put a part first in their own words - each keeps its market route, and is never the undo. */
    private val NOT_UNDO = listOf("levels first", "give me the overview", "levels pehle batao", "give me the levels first",
        "tell me the levels first then the trend", "say the levels", "patterns batao", "what is the usual range")

    @Test fun bossCanAskAndUndo() {
        for (q in listOf("what do you say first in an overview", "Jarvis, why do you give the levels first?", "why are the levels first",
                "why do you start with patterns", "what comes first in your overview", "which part do i ask about most",
                "overview mein pehle kya batate ho", "levels pehle kyun bolte ho", "why do you put patterns first in the overview"))
            assertEquals(LeadPart.Request.WHICH, LeadPart.asked(q), q)
        for (q in listOf(LeadPart.UNDO, "give your overviews in the usual order again please", "stop putting the levels first",
                "stop starting with the levels", "don't put the patterns first", "do not say the patterns first in your overview",
                "go back to the usual order in your overviews", "levels pehle mat batao", "patterns pehle mat bolo"))
            assertEquals(LeadPart.Request.RESET, LeadPart.asked(q), q)
        // "Stop putting the levels first" is that undo, never a stop of an arm.
        for (q in listOf("stop putting the levels first", "stop starting with the levels first")) {
            assertNull(Ask.parse(q).command, q); assertNull(Ask.parse(q).order, q)
        }
        for (q in NOT_UNDO) assertNull(LeadPart.asked(q), q)
        // FigureFirst's own words (the figure said first) stay its own.
        for (q in listOf("why do you say the levels first", "don't put the levels first")) {
            assertNotNull(FigureFirst.asked(q), q); assertNull(LeadPart.asked(q), q)
        }
        // Not his market questions, not LeadIndex's or FigureFirst's, never an order or a command.
        for (q in listOf("how is nifty", "what are the levels on nifty", "which index do you mention first", "mention nifty first again",
                "say your market reads in the usual order", "buy nifty first", "say that again", "stop orb"))
            assertNull(LeadPart.asked(q), q)
        for (q in listOf("what do you say first in an overview", LeadPart.UNDO, "stop putting the levels first", "don't put the patterns first",
                "levels pehle mat batao", "why are the levels first")) {
            val p = Ask.parse(q); assertNull(p.order, q); assertNull(p.command, q); assertFalse(Bundle.acts(q), q)
            assertNull(LeadIndex.asked(q), q); assertNull(FigureFirst.asked(q), q); assertNull(TopicLength.asked(q), q)
        }
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("stop orb").command?.kind)
    }

    @Test fun whatIsSaid() {
        val r = assertNotNull(LeadPart.learned(tally(4, 3, 1), LeadPart.Log(), today))
        val said = LeadPart.say(r)
        assertTrue(said.contains("the levels right after the price") && said.contains(LeadPart.UNDO) && said.contains(LeadPart.ONLY_ORDER), said)
        assertTrue(LeadPart.say(null).startsWith("In an overview I say the price"))
        assertTrue(LeadPart.sayReset(r).startsWith("Done, Boss"))
        assertFalse(LeadPart.RESET_LOCKED.contains("levels") || LeadPart.RESET_LOCKED.contains("already"), LeadPart.RESET_LOCKED)
        assertTrue(LeadPart.ledgerWhat(r).startsWith("The levels said right after the price"))
        // In the ledger, with its undo; reset by "undo everything you learned this week"; never on a locked phone.
        val inputs = Learnings.Inputs(tally = tally(4, 3, 1))
        val item = Learnings.items(inputs, today.atTime(12, 0)).single { it.area == Learnings.Area.LEAD_PART }
        assertEquals(LeadPart.UNDO, item.undo)
        val u = Learnings.undo(inputs, today.atTime(12, 0))
        assertEquals(listOf(Topic.LEVELS), u.leadPart.map { it.part })
        assertTrue(Learnings.offer(u).contains("the usual order again"))
        assertFalse(Learnings.say(Learnings.items(inputs, today.atTime(12, 0)), Learnings.Ask.ALL, today, locked = true).contains("right after the price"))
        // Undone: gone from the ledger.
        val after = Learnings.Inputs(tally = tally(4, 3, 1), leadPart = LeadPart.reset(today))
        assertTrue(Learnings.items(after, today.atTime(12, 0)).none { it.area == Learnings.Area.LEAD_PART })
    }
}
