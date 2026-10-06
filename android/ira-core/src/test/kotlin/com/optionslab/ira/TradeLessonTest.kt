package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TradeLessonTest {
    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|consider|switch (it )?off|turn (it )?off|reduce|increase|fewer lots|more lots|change (the |its )?rules?)\\b")

    /** A BANKNIFTY 15-min trade of one lot of 35, Rs 60 charges, entered at 300. */
    private fun closed(exit: Double, why: String, held: Long = 14, best: Double? = null, worst: Double? = null): TradeLesson.Closed {
        val net = (exit - 300.0) * 35 - 60.0
        return TradeLesson.Closed("BankNifty 15-min", "CE", 300.0, exit, why, held, net, 60.0, 35, best, worst)
    }

    @Test fun eachExitReasonIsReadAgainstItsShare() {
        val cases = mapOf(
            "index_stop" to "the index stop hit after 14 min - 30% of Liquidity's losers end this way",
            "failed_break" to "a failed break closed it after 14 min - 25% of Liquidity's losers",
            "time_stop" to "the 20-minute time stop closed it after 14 min - 24% of Liquidity's losers",
            "stop" to "the 15% premium stop hit after 14 min - 17% of Liquidity's losers",
            "next_liquidity" to "it reached the next liquidity level after 14 min - 2% of Liquidity's losers",
            "new_liquidity" to "new liquidity formed on its side after 14 min - 1% of Liquidity's losers",
            "session_end" to "the 15:10 square-off closed it after 14 min - under 1% of Liquidity's losers",
        )
        for ((why, said) in cases) {
            val l = TradeLesson.of(closed(285.0, why))
            assertTrue(l.text.contains(said), "$why: ${l.text}")
            assertTrue(!ADVICE.containsMatchIn(l.text), l.text)
        }
        // A loss by the next liquidity level is rare for the losers (2%): unusual. By the index stop it is not.
        assertNotNull(TradeLesson.of(closed(285.0, "next_liquidity")).unusual)
        assertNull(TradeLesson.of(closed(285.0, "index_stop")).unusual)
        // The winners' shares: the next level ends 52% of them.
        val w = TradeLesson.of(closed(330.0, "next_liquidity", held = 22))
        assertTrue(w.text.contains("It reached the next liquidity level after 22 min - 52% of Liquidity's winners end this way (their middle hold is 20 min)"), w.text)
        // Boss's own close: no research to compare, said so and unusual.
        val mine = TradeLesson.of(closed(310.0, "closed_by_you"))
        assertTrue(mine.text.contains("You closed it yourself after 14 min - the research has no such exits to compare"), mine.text)
        assertEquals("closed outside the arm's own exits", mine.unusual)
    }

    @Test fun theSizeBuckets() {
        assertEquals(TradeLesson.Size.BIG_WIN, TradeLesson.size(4_100.0))
        assertEquals(TradeLesson.Size.GOOD_WIN, TradeLesson.size(2_000.0))
        assertEquals(TradeLesson.Size.NORMAL_WIN, TradeLesson.size(689.0))
        assertEquals(TradeLesson.Size.SMALL_WIN, TradeLesson.size(120.0))
        assertEquals(TradeLesson.Size.SMALL_LOSS, TradeLesson.size(-200.0))
        assertEquals(TradeLesson.Size.NORMAL_LOSS, TradeLesson.size(-554.0))
        assertEquals(TradeLesson.Size.LARGE_LOSS, TradeLesson.size(-1_000.0))
        assertEquals(TradeLesson.Size.BIG_LOSS, TradeLesson.size(-1_600.0))
        // +Rs 4,140 a lot: the top 10% of its wins, and unusual.
        val big = TradeLesson.of(closed(420.0, "next_liquidity"))
        assertTrue(big.text.startsWith("A big win: +Rs 4,140 a lot is in the top 10% of its wins."), big.text)
        assertEquals("a big win, top 10% of its wins", big.unusual)
        val normal = TradeLesson.of(closed(320.0, "time_stop", held = 20))
        assertTrue(normal.text.startsWith("A normal win: +Rs 640 a lot is near its usual win (the middle win is +Rs 689)."), normal.text)
        assertNull(normal.unusual)
        val bad = TradeLesson.of(closed(250.0, "stop", held = 6))
        assertTrue(bad.text.startsWith("A big loss: the 15% premium stop hit after 6 min") && bad.text.contains("among the worst 10% of its losses"), bad.text)
        assertEquals(TradeLesson.Size.SMALL_LOSS, TradeLesson.of(closed(295.0, "time_stop")).size)
    }

    @Test fun theLoserGroups() {
        // Up before charges (gross +35), net -25: the charges ate it.
        assertEquals(TradeLesson.Group.CHARGES_ATE_WIN, TradeLesson.of(closed(301.0, "time_stop", best = 303.0)).group)
        // Never above 300.5 (best net 17.5 - 60 < 0): never green after charges.
        val never = TradeLesson.of(closed(285.0, "index_stop", best = 300.5, worst = 284.0))
        assertEquals(TradeLesson.Group.NEVER_GREEN, never.group)
        assertTrue(never.text.contains("it was never green after charges (the 'wrong way from the start' kind, 23% of losers"), never.text)
        // Up 20 points at best (Rs 640 a lot after charges): green, then gave it back.
        val gave = TradeLesson.of(closed(285.0, "failed_break", best = 320.0))
        assertEquals(TradeLesson.Group.GAVE_BACK, gave.group)
        assertTrue(gave.text.contains("26% of losers are this kind"), gave.text)
        // Up 5 points at best (Rs 115 a lot): only briefly green.
        val brief = TradeLesson.of(closed(285.0, "index_stop", best = 305.0))
        assertEquals(TradeLesson.Group.BRIEFLY_GREEN, brief.group)
        assertTrue(brief.text.contains("48% of losers, the most common kind"), brief.text)
        // A winner has no loser group.
        assertNull(TradeLesson.of(closed(330.0, "next_liquidity", best = 340.0)).group)
    }

    @Test fun missingExcursionsAreSaidNotGuessed() {
        val l = TradeLesson.of(closed(285.0, "index_stop"))
        assertNull(l.group)
        assertTrue(l.text.endsWith("how far it went our way while held was not recorded."), l.text)
        val c = closed(285.0, "index_stop")
        assertNull(c.mfe); assertNull(c.mae)
        // Recorded: the exit counts as seen.
        val k = closed(285.0, "index_stop", best = 310.0, worst = 290.0)
        assertEquals(10.0, k.mfe!!, 1e-9); assertEquals(15.0, k.mae!!, 1e-9)
        // A winner's best is said when it was clearly above the exit.
        val w = TradeLesson.of(closed(330.0, "next_liquidity", best = 350.0))
        assertTrue(w.text.endsWith("; at its best it was +Rs 1,690 a lot."), w.text)
        assertTrue(!TradeLesson.of(closed(330.0, "next_liquidity")).text.contains("at its best"))
    }

    @Test fun oneOrTwoSentences() {
        for (why in listOf("index_stop", "next_liquidity", "time_stop", "closed_by_you")) for (exit in listOf(250.0, 285.0, 330.0, 420.0)) {
            val l = TradeLesson.of(closed(exit, why, best = exit + 5)); val t = l.text
            val sentences = t.split(Regex("(?<=[.])\\s+(?=[A-Z])")).size
            assertTrue(sentences in 1..2, t)
            // The short form (brief answers, saving battery) is its first sentence.
            assertTrue(t.startsWith(l.short + " ") && l.short.endsWith("."), l.short)
        }
    }

    @Test fun theDayLine() {
        assertNull(TradeLesson.day(emptyList()))
        val a = TradeLesson.of(closed(320.0, "time_stop")); val b = TradeLesson.of(closed(285.0, "index_stop"))
        assertEquals("Liquidity 15+5 in research terms: 2 trades (1 won, 1 lost), both normal; nothing unusual.", TradeLesson.day(listOf(a, b)))
        assertEquals("Liquidity 15+5 in research terms: 1 trade (1 won, 0 lost), normal for it; nothing unusual.", TradeLesson.day(listOf(a)))
        val big = TradeLesson.of(closed(420.0, "next_liquidity"))
        assertEquals("Liquidity 15+5 in research terms: 3 trades (2 won, 1 lost): two normal, one unusual (a big win, top 10% of its wins).",
            TradeLesson.day(listOf(a, b, big)))
        assertEquals("Liquidity 15+5 in research terms: 1 trade (1 won, 0 lost), unusual: a big win, top 10% of its wins.", TradeLesson.day(listOf(big)))
    }

    @Test fun theBotTradesAnswerCarriesTheLesson() {
        val day = LocalDate.of(2026, 10, 6)
        fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
        val liq = BotTrades.Trade("liquidity15", "BANKNIFTY14OCT2654300CE", "CE", 70, 300.0, at(12, 0), at(11, 45),
            exit = 285.0, exitTime = at(12, 14), why = "index_stop", charges = 120.0, level = 54_300.0, target = 54_400.0,
            peak = 300.5, trough = 284.0, lot = 35)
        val l = BotTrades.lesson(liq)!!
        // Two lots: -Rs 585 a lot after Rs 60 a lot of charges.
        assertTrue(l.text.startsWith("A normal loss: the index stop hit after 14 min - 30% of Liquidity's losers end this way") &&
            l.text.contains("At -Rs 585 a lot"), l.text)
        assertEquals(TradeLesson.Group.NEVER_GREEN, l.group)
        val s = BotTrades.answer(BotTrades.Q("liquidity"), listOf(liq), emptyList(), null, emptyList(), at(15, 20))
        assertTrue(s.contains("Against the research: ${l.text}"), s)
        assertEquals(1, Regex("Against the research").findAll(s).count())
        // Open, or another arm: no lesson.
        assertNull(BotTrades.lesson(liq.copy(exit = null, exitTime = null, why = null)))
        assertNull(BotTrades.lesson(liq.copy(source = "orb")))
    }
}
