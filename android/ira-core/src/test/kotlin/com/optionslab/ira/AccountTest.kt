package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTest {
    private val v = AccountView("Paper", "Paper", dayPnl = 1_234.5, realized = 900.0, unrealized = 334.5,
        positions = listOf(AccountView.Held("NIFTY25O0724500CE", 75, 120.5, 125.0, 337.5)),
        orders = listOf(
            AccountView.OrderLine("09:31", "NIFTY25O0724500CE", "BUY", 75, "COMPLETE", 120.5, "Manual · Ira"),
            AccountView.OrderLine("10:02", "BANKNIFTY25O0752000PE", "BUY", 35, "REJECTED", 0.0, "Jarvis: breakout", "Insufficient funds"),
            AccountView.OrderLine("11:15", "BANKNIFTY25O0752000CE", "SELL", 35, "COMPLETE", 210.0, "Jarvis: breakout")),
        arms = listOf(
            AccountView.ArmLine("Jarvis: breakout", "Pine", true, "BANKNIFTY 15m", 562.5, null, 2),
            AccountView.ArmLine("ORB 15", "ORB", true, "BANKNIFTY", -120.0, "1 CE"),
            AccountView.ArmLine("Old test", "Pine", false, "NIFTY 5m", null, null)))

    @Test fun ownTradingIsReadAsTheAccountNotAnOrderOrABacktest() {
        for (text in listOf("can you analyze my strategies orders", "analyze my sell orders", "how are my strategies doing", "my positions",
                "what is my pnl today", "how am I doing", "how did my bots do", "show my studies")) {
            val q = Ask.parse(text)
            assertEquals(setOf(Topic.ACCOUNT), q.topics, text); assertNull(q.order, text)
        }
        assertTrue(Topic.BACKTEST in Ask.parse("backtest my strategy on nifty").topics)
        assertTrue(Topic.ORDER in Ask.parse("buy 1 lot nifty 24000 ce").topics)
    }

    @Test fun askingAboutIraItselfGetsHelp() {
        for (text in listOf("you can listen to me", "can you hear me?", "what can you do", "help")) assertEquals(setOf(Topic.HELP), Ask.parse(text).topics, text)
        val voice = Ira().answer("you can listen to me", emptyMap(), emptyList(), voice = true)
        assertTrue(voice.text.startsWith("Yes. Voice: switch on") && voice.text.contains("Jarvis, how is Nifty?"), voice.text)
        assertTrue(Ira().answer("what can you do", emptyMap(), emptyList()).text.contains("analyze my orders"))
        assertTrue(Ira().answer("can you hear me", emptyMap(), emptyList(), voice = false).text.startsWith("Voice is in JarvisAlgo only"))
        assertTrue(Topic.OVERVIEW in Ask.parse("help me with nifty").topics, "a market named is a market question")
    }

    @Test fun theAccountIsReportedAsFactsWithEveryNumberBacked() {
        val ira = Ira()
        val a = ira.answer("can you analyze my strategies orders", emptyMap(), emptyList(), account = v)
        assertTrue(a.text.contains("3 orders today: 2 filled, 0 open, 1 rejected or cancelled"), a.text)
        assertTrue(a.text.contains("2 of 3 strategy arms are switched on") && a.text.contains("Best today: Jarvis: breakout (+Rs 562.50)"), a.text)
        assertTrue(a.text.contains("The last rejection said: Insufficient funds") && a.text.endsWith("These are facts from the app, not advice."), a.text)
        assertTrue(ira.numbersBacked(a.text, a.facts), a.text)
        val pnl = ira.answer("what's my p&l today", emptyMap(), emptyList(), account = v)
        assertTrue(pnl.text.startsWith("Today's P&L on the paper account is +Rs 1,234.50 after charges: +Rs 900.00 booked"), pnl.text)
        assertTrue(ira.numbersBacked(pnl.text, pnl.facts))
        val all = ira.answer("how am i doing", emptyMap(), emptyList(), account = v)
        assertTrue(all.text.contains("1 open position: NIFTY25O0724500CE 75 at 120.50, now 125.00 (+Rs 337.50)"), all.text)
        assertTrue(ira.answer("my orders", emptyMap(), emptyList(), account = null).text.startsWith("I could not read your account"))
        val empty = AccountView("Live", "Paper", null, null, null, emptyList(), emptyList(), emptyList())
        val e = ira.answer("how am i doing", emptyMap(), emptyList(), account = empty).text
        assertTrue(e.contains("No open positions") && e.contains("No orders on the paper account today") && e.contains("You are in Live mode"), e)
    }
}
