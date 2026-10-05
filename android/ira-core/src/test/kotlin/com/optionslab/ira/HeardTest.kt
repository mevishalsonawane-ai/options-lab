package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Common questions in Hinglish and as the speech recognizer mis-hears them (2026-10-05) - and no action widened. */
class HeardTest {
    /** Every new phrasing below: a question, never a command, an order or an action line. */
    private val NEW = listOf(
        "nifty kya chal raha hai", "Nifty kya chal rha hai", "aaj nifty kya chal raha hai", "bank nifty ka kya haal hai",
        "sensex mein kya ho raha hai", "market kya chal raha hai", "aaj market kya chal raha hai", "bazaar ka kya haal hai",
        "market khula hai kya", "mera p&l kitna hai", "aaj ki kamai kitni hai", "aaj kitna kamaya", "aaj kitna kamaya maine",
        "kitna loss hua", "kitna profit hua aaj", "kitna gawaya", "main profit mein hoon kya", "kya main loss mein hu",
        "positions dikhao", "positions dikha do", "orders dikhao", "mere orders dikha do", "meri positions kya hain",
        "koi order pending hai kya", "kitne positions khule hain", "strategies kaise chal rahe hain", "mere arms kaise chal rahe hain",
        "nifty kitne pe hai", "banknifty ka rate kya hai", "nifty kahan hai", "aaj ki news kya hai", "koi khabar hai kya", "news sunao",
        "my p and l", "what's my p and l", "what is my pee and el", "what is my p n l today", "what's my profit and loss",
        "what is my mark to market", "how is niftee doing", "how is census doing", "what is the vicks", "how is fine nifty doing",
        "how is bank fifty today", "how much did i make today", "am i in profit", "jarvis positions dikhao na",
    )

    private fun ask(s: String) = Ask.parse(s).also { assertNull(it.command, s); assertNull(it.order, s) }

    @Test fun oneMarketInHinglish() {
        for ((s, m) in listOf("nifty kya chal raha hai" to Market.NIFTY, "Nifty kya chal rha hai" to Market.NIFTY,
            "aaj nifty kya chal raha hai" to Market.NIFTY, "bank nifty ka kya haal hai" to Market.BANKNIFTY,
            "sensex mein kya ho raha hai" to Market.SENSEX, "nifty kitne pe hai" to Market.NIFTY,
            "banknifty ka rate kya hai" to Market.BANKNIFTY, "nifty kahan hai" to Market.NIFTY)) {
            val q = ask(s)
            assertEquals(listOf(m), q.markets, s)
            assertEquals(setOf(Topic.OVERVIEW), q.topics, s)
        }
        assertEquals("how is nifty", Ask.reading("nifty kya chal raha hai"))
        assertEquals("today how is nifty", Ask.reading("aaj nifty kya chal raha hai"))
        assertEquals("what is banknifty price", Ask.reading("banknifty ka rate kya hai"))
        assertEquals("where is nifty trading", Ask.reading("nifty kahan hai"))
        // Only a market is asked about this way: "Boss kahan hai" is not read as a price.
        assertEquals(Hinglish.normalize("boss kahan hai"), Ask.reading("boss kahan hai"))
    }

    @Test fun theMarketInHinglishAsksAsItsEnglish() {
        for (s in listOf("market kya chal raha hai", "aaj market kya chal raha hai", "bazaar ka kya haal hai"))
            assertEquals(Ask.parse("how is the market").topics, ask(s).topics, s)
        assertEquals("today how is the market", Ask.reading("aaj market kya chal raha hai"))
        assertEquals(Ask.parse("is the market open today").topics, ask("market khula hai kya").topics)
        assertTrue(Section.STATUS in AppAnswers.sections("market khula hai kya"))
    }

    @Test fun pnlInHinglish() {
        for (s in listOf("mera p&l kitna hai", "aaj ki kamai kitni hai", "aaj kitna kamaya", "aaj kitna kamaya maine", "kitna loss hua",
            "kitna profit hua aaj", "kitna gawaya", "main profit mein hoon kya", "kya main loss mein hu")) {
            assertEquals(setOf(Topic.ACCOUNT), ask(s).topics, s)
            assertTrue(Section.PNL in AppAnswers.sections(s), s)
        }
        assertEquals("what is my p&l today", Ask.reading("aaj kitna kamaya"))
        assertEquals("am i in profit today", Ask.reading("main profit mein hoon kya"))
    }

    @Test fun positionsAndOrdersInHinglish() {
        for (s in listOf("positions dikhao", "positions dikha do", "meri positions kya hain", "kitne positions khule hain", "jarvis positions dikhao na")) {
            assertEquals(setOf(Topic.ACCOUNT), ask(s).topics, s)
            assertTrue(Section.POSITIONS in AppAnswers.sections(s), s)
        }
        for (s in listOf("orders dikhao", "mere orders dikha do", "koi order pending hai kya")) {
            assertEquals(setOf(Topic.ACCOUNT), ask(s).topics, s)
            assertTrue(Section.ORDERS in AppAnswers.sections(s), s)
        }
        assertEquals("show my positions", Ask.reading("positions dikha do"))
        for (s in listOf("strategies kaise chal rahe hain", "mere arms kaise chal rahe hain")) {
            assertEquals(setOf(Topic.ACCOUNT), ask(s).topics, s)
            assertTrue(Section.STRATEGIES in AppAnswers.sections(s), s)
        }
    }

    @Test fun newsInHinglish() {
        for (s in listOf("aaj ki news kya hai", "koi khabar hai kya", "news sunao")) assertTrue(Topic.NEWS in ask(s).topics, s)
    }

    @Test fun misheardPnlAndMtm() {
        for (s in listOf("my p and l", "what's my p and l", "what is my pee and el", "what is my p n l today", "what's my profit and loss",
            "what is my mark to market", "how much did i make today", "am i in profit")) {
            assertEquals(setOf(Topic.ACCOUNT), ask(s).topics, s)
            assertTrue(Section.PNL in AppAnswers.sections(s), s)
        }
        assertEquals("what is my p&l", Heard.fix("what is my pee and el"))
        // Words already right are never touched ("panel", "penal" are not P&L).
        assertEquals("the panel looks penal", Heard.fix("the panel looks penal"))
        assertEquals("How is Nifty doing", Heard.fix("How is Nifty doing"))
    }

    @Test fun misheardIndices() {
        for ((s, m) in listOf("how is niftee doing" to Market.NIFTY, "how is census doing" to Market.SENSEX, "what is the vicks" to Market.VIX,
            "how is fine nifty doing" to Market.FINNIFTY, "how is bank fifty today" to Market.BANKNIFTY))
            assertEquals(listOf(m), ask(s).markets, s)
    }

    // ---- No action widened: commands and orders are read from the words as heard, exactly as before. ----

    @Test fun noNewPhrasingActs() {
        val actions = setOf("stop all strategies", "close all positions", "cancel all orders", "turn the kill switch on", "switch to paper mode")
        for (s in NEW) {
            assertNull(Commands.parse(s), s)
            ask(s)
            assertFalse(Intents.quick(s) in actions, s)
            assertFalse(Topic.COMMAND in Ask.parse(s).topics || Topic.ORDER in Ask.parse(s).topics, s)
        }
        // The action reading of these words is as it was (only questions read them in Hinglish).
        assertEquals("positions dikha do", Hinglish.normalize("positions dikha do"))
        assertEquals("nifty kya chal raha hai", Hinglish.normalize("nifty kya chal raha hai"))
        assertEquals("my p and l", Hinglish.normalize("my p and l"))
        assertEquals("how is niftee doing", Hinglish.normalize("how is niftee doing"))
    }

    @Test fun actionsStayExactlyAsStrict() {
        val kept = listOf(
            "strategy 1 band karo" to Command.Kind.STOP_ONE, "nifty position band karo" to Command.Kind.CLOSE_ONE,
            "mere orders cancel karo" to Command.Kind.CANCEL_ALL, "sab positions band karo" to Command.Kind.CLOSE_ALL,
            "stop all strategies" to Command.Kind.STOP_ALL, "close all positions" to Command.Kind.CLOSE_ALL,
            "nifty 25000 pe alert lagao" to Command.Kind.ALARM_ADD, "turn the kill switch on" to Command.Kind.KILL_ON,
            "switch to live mode" to Command.Kind.MODE_LIVE, "start strategy 2" to Command.Kind.START_ONE,
        )
        for ((s, k) in kept) {
            assertEquals(k, Ask.parse(s).command?.kind, s)
            assertEquals(Commands.parse(Hinglish.normalize(s)), Ask.parse(s).command, s)
        }
        // A misheard index never fills in an order or an alarm (only a question reads it).
        val o = Ask.parse("buy 2 lots niftee 24500 ce")
        assertNull(o.order?.market); assertTrue(o.markets.isEmpty()); assertTrue("which index" in o.order!!.missing)
        assertNull(Ask.parse("set an alarm on niftee above 25000").command?.market)
        assertNull(Ask.parse("set an alarm on census above 80000").command?.market)
        // Misheard P&L or Hinglish question words next to an action verb do not make one.
        for (s in listOf("positions dikha do band karo", "p and l close karo", "kitna kamaya band karo", "niftee strategy chalu karo", "census start karo"))
            assertEquals(Commands.parse(Hinglish.normalize(s)), Ask.parse(s).command, s)
        // A question about an action is still never one.
        assertNull(Ask.parse("market kya chal raha hai, should I close all positions?").command)
    }
}
