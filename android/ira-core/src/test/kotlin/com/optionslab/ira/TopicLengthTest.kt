package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopicLengthTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val now = today.atTime(11, 0)
    private val levels = "what are the levels for nifty today"

    @Test fun theQuestionHasAKind() {
        assertNotNull(Clarity.kind(levels))
    }

    @Test fun readsBossWishes() {
        for (w in listOf("in short", "in short please", "short mein batao", "chhota karke batao", "just the gist", "briefly", "ek line mein batao", "tldr"))
            assertEquals(TopicLength.Dir.SHORT, TopicLength.wish(w), w)
        for (w in listOf("in detail", "detail mein batao", "thoda detail mein batao", "explain in detail", "elaborate", "elaborate on that please", "vistar se batao", "go deeper"))
            assertEquals(TopicLength.Dir.LONG, TopicLength.wish(w), w)
    }

    @Test fun commandsQuestionsAndOrdersAreNoWish() {
        // "shorter", "tell me more" and "full answers" stay Boss's own commands.
        for (w in listOf("shorter", "tell me more", "full answers", "in more detail", "longer", "how is nifty", "buy 1 lot of nifty call in short",
                "explain the option chain in detail", "what is short covering", "in short how is nifty", "detail mein batao nifty ka trend"))
            assertNull(TopicLength.wish(w), w)
        for (w in listOf("in short", "detail mein batao", "elaborate", "just the gist")) {
            val p = Ask.parse(w)
            assertNull(p.order, w); assertNull(p.command, w)
        }
    }

    @Test fun learnedOnlyWhenOneWayAtLeastTwiceMore() {
        val k = Clarity.kind(levels)!!
        var log = TopicLength.heard(TopicLength.Log(), k, TopicLength.Dir.SHORT, now.minusDays(3))
        assertTrue(TopicLength.learned(log, now).isEmpty())
        log = TopicLength.heard(log, k, TopicLength.Dir.SHORT, now.minusDays(1))
        val r = TopicLength.learned(log, now).single()
        assertEquals(TopicLength.Dir.SHORT, r.dir)
        assertEquals(1, TopicLength.sentences(levels, listOf(r)))
        // One the other way: mixed, so the usual length again.
        log = TopicLength.heard(log, k, TopicLength.Dir.LONG, now.minusHours(1))
        assertTrue(TopicLength.learned(log, now).isEmpty())
        assertNull(TopicLength.sentences(levels, TopicLength.learned(log, now)))
        // In detail three more times (two more than "in short"): said in full.
        log = TopicLength.heard(log, k, TopicLength.Dir.LONG, now.minusMinutes(40))
        log = TopicLength.heard(log, k, TopicLength.Dir.LONG, now.minusMinutes(30))
        log = TopicLength.heard(log, k, TopicLength.Dir.LONG, now.minusMinutes(10))
        val long = TopicLength.learned(log, now).single()
        assertEquals(TopicLength.Dir.LONG, long.dir)
        assertEquals(Aloud.Length.FULL.sentences, TopicLength.sentences(levels, listOf(long)))
    }

    @Test fun oldWishesAndTheResetCountForNothing() {
        val k = Clarity.kind(levels)!!
        var log = TopicLength.heard(TopicLength.Log(), k, TopicLength.Dir.SHORT, now.minusDays(40))
        log = TopicLength.heard(log, k, TopicLength.Dir.SHORT, now.minusDays(35))
        assertTrue(TopicLength.learned(log, now).isEmpty())
        log = TopicLength.heard(TopicLength.Log(), k, TopicLength.Dir.SHORT, now.minusDays(2))
        log = TopicLength.heard(log, k, TopicLength.Dir.SHORT, now.minusDays(1))
        assertEquals(1, TopicLength.learned(log, now).size)
        assertTrue(TopicLength.learned(TopicLength.reset(log, now.minusHours(1)), now).isEmpty())
    }

    @Test fun aCommandOrOrderIsNeverResized() {
        val k = Clarity.kind(levels)!!
        val r = TopicLength.Record(k, TopicLength.Dir.SHORT, 3, 0, now)
        assertNull(TopicLength.sentences("stop all strategies", listOf(r)))
        assertNull(TopicLength.sentences("buy 1 lot of nifty 25000 call", listOf(r)))
    }

    @Test fun freshWithinMinutesOnly() {
        assertTrue(TopicLength.fresh(now, now.plusMinutes(2)))
        assertFalse(TopicLength.fresh(now, now.plusMinutes(5)))
        assertFalse(TopicLength.fresh(now, now.minusMinutes(1)))
    }

    @Test fun repliesToTheWish() {
        val last = "Nifty is at 24,600. Support is 24,500 and resistance 24,750. The day is range-like."
        val short = TopicLength.reply(TopicLength.Dir.SHORT, last, null)
        assertEquals("In short, Boss: Nifty is at 24,600.", short)
        assertTrue(TopicLength.reply(TopicLength.Dir.LONG, last, null).endsWith("range-like."))
        val r = TopicLength.Record(Clarity.kind(levels)!!, TopicLength.Dir.SHORT, 2, 0, now)
        val learned = TopicLength.reply(TopicLength.Dir.SHORT, last, r)
        assertTrue(learned.contains("From now on") && learned.contains(TopicLength.UNDO), learned)
        assertTrue(TopicLength.reply(TopicLength.Dir.SHORT, null, null).contains("Boss"))
    }

    @Test fun askedAndItsUndo() {
        for (q in listOf("how long do i like your answers", "which topics do you keep short", "which topics do you say in detail for me",
                "do you know how short i like my answers", "kaun se topic short mein batate ho"))
            assertEquals(TopicLength.Request.WHICH, TopicLength.asked(q), q)
        for (q in listOf("say every topic at the usual length", "forget how long i like my answers", "stop shortening answers by topic",
                "stop saying topics in detail", "har topic normal length mein bolo"))
            assertEquals(TopicLength.Request.RESET, TopicLength.asked(q), q)
        assertNull(TopicLength.asked("which answers do you keep short"))
        assertNull(TopicLength.asked("in short"))
        // "Stop shortening answers by topic" is the undo, never a strategy called "answers by topic".
        assertNull(Ask.parse("stop shortening answers by topic").command)
        assertNull(Ask.parse("stop saying topics in detail").command)
    }

    @Test fun saysAndLedger() {
        val k = Clarity.kind(levels)!!
        var log = TopicLength.heard(TopicLength.Log(), k, TopicLength.Dir.LONG, now.minusDays(2))
        assertTrue(TopicLength.say(log, now).startsWith("I say every topic"))
        log = TopicLength.heard(log, k, TopicLength.Dir.LONG, now.minusDays(1))
        val said = TopicLength.say(log, now)
        assertTrue(said.contains("in full aloud") && said.contains(TopicLength.UNDO), said)
        assertTrue(TopicLength.sayReset(log, now).startsWith("Done, Boss"))
        val items = Learnings.items(Learnings.Inputs(lengths = log), now)
        val it = items.single { it.area == Learnings.Area.LENGTHS }
        assertEquals(TopicLength.UNDO, it.undo)
        assertFalse(it.personal)
        val u = Learnings.undo(Learnings.Inputs(lengths = log), now)
        assertEquals(1, u.lengths.size)
        assertFalse(u.empty)
        assertTrue(Learnings.offer(u).contains("at the usual length again"))
    }

    // ---- never short: the trade check, the account, what to do (review, 5 Oct) -----------------------------------------

    private val tradeNow = "should I trade now"

    @Test fun theTradeCheckIsNeverLearnedOrSaidShort() {
        val k = TopicLength.kindOf(tradeNow)
        assertEquals("topic:TRADE_CHECK", k)
        assertTrue(TopicLength.neverShort(k))
        var log = TopicLength.Log()
        for (i in 1..4) log = TopicLength.heard(log, k!!, TopicLength.Dir.SHORT, now.minusMinutes(i * 10L))
        assertTrue(TopicLength.learned(log, now).isEmpty(), "\"in short\" after the trade check is never learned")
        // Even a short learned under another kind never cuts a question that also asks the trade check.
        val lv = Clarity.kind(levels)!!
        var log2 = TopicLength.Log()
        for (i in 1..3) log2 = TopicLength.heard(log2, lv, TopicLength.Dir.SHORT, now.minusMinutes(i * 10L))
        val learned = TopicLength.learned(log2, now)
        assertEquals(1, TopicLength.sentences(levels, learned))
        assertTrue(TopicLength.asksNeverShort(tradeNow))
        assertFalse(TopicLength.asksNeverShort(levels))
        // Never one sentence for a question that asks the trade check, whatever was learned ([TopicLength.shortGuarded]).
        assertNull(TopicLength.shortGuarded(tradeNow, 1))
        assertEquals(Aloud.Length.FULL.sentences, TopicLength.shortGuarded(tradeNow, Aloud.Length.FULL.sentences))
        assertEquals(1, TopicLength.shortGuarded(levels, 1))
        // The same on the voice's one-pass path ([SpokenReply]), even with a short record for the trade check at hand.
        val forced = listOf(TopicLength.Record("topic:TRADE_CHECK", TopicLength.Dir.SHORT, 3, 0, now))
        val answer = "Nifty is bullish. Bank Nifty is flat. Don't trade now. Zerodha is not logged in today. Volume is light."
        val r = SpokenReply.said(tradeNow, answer, false, false, false, { emptyList() }, { forced }, { emptyList() })
        assertEquals(Aloud.say(answer, Aloud.Length.USUAL), r.spoken)
        assertTrue(r.spoken.contains("trade now") && r.spoken.contains("Zerodha is not logged in today."), r.spoken)
        assertEquals(Aloud.say("Support is 24,000. Resistance is 24,500. Volume is light.", 1),
            SpokenReply.said(levels, "Support is 24,000. Resistance is 24,500. Volume is light.", false, false, false, { emptyList() }, { learned }, { emptyList() }).spoken)
    }

    @Test fun inShortAfterTheTradeCheckSaysItWhole() {
        val answer = "Nifty is bullish. Don't trade now. Zerodha is not logged in today. That is how the market is moving now, not a forecast."
        val r = TopicLength.reply(TopicLength.Dir.SHORT, answer, null, "topic:TRADE_CHECK")
        assertTrue(r.contains("Don't trade now.") && r.contains("Zerodha is not logged in today."), r)
        assertTrue(r.contains("Boss"))
        // Any other answer in short keeps its warning too.
        val r2 = TopicLength.reply(TopicLength.Dir.SHORT, answer, null, Clarity.kind(levels))
        assertTrue(r2.startsWith("In short, Boss: Don't trade now."), r2)
        assertTrue(r2.contains("Zerodha is not logged in today."), r2)
    }
}
