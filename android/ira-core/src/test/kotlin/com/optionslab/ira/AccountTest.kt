package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTest {
    private val orders = listOf(
        AppFacts.OrderLine("09:31", "NIFTY25O0724500CE", "BUY", 75, "COMPLETE", 120.5, "Manual · Ira"),
        AppFacts.OrderLine("10:02", "BANKNIFTY25O0752000PE", "BUY", 35, "REJECTED", 0.0, "Jarvis: breakout", "Insufficient funds"),
        AppFacts.OrderLine("11:15", "BANKNIFTY25O0752000CE", "SELL", 35, "COMPLETE", 210.0, "Jarvis: breakout"))
    private val arms = listOf(
        AppFacts.ArmLine("Jarvis: breakout", "Pine", true, "BANKNIFTY 15m", 562.5, null, 2),
        AppFacts.ArmLine("ORB 15", "ORB", true, "armed", -120.0, "75 NIFTY25O0724500CE"),
        AppFacts.ArmLine("Old test", "Pine", false, "NIFTY 5m", null, null))
    private val today = LocalDate.of(2026, 10, 2)
    private val view = AppView("Paper", mapOf(
        Section.ORDERS to AppFacts.orders("Paper", orders, byWho = true),
        Section.POSITIONS to AppFacts.positions("Paper", listOf(AppFacts.Held("NIFTY25O0724500CE", 75, 120.5, 125.0, 337.5))),
        Section.PNL to listOf(AppFacts.pnl("Paper", 1_234.5, 900.0, 334.5)),
        Section.STRATEGIES to AppFacts.arms(arms, rank = true),
        Section.HISTORY to AppFacts.history("Paper", mapOf(LocalDate.of(2026, 9, 30) to (500.0 to 3), LocalDate.of(2026, 10, 1) to (-200.0 to 2),
            today to (1_234.5 to 3), LocalDate.of(2026, 9, 15) to (900.0 to 1)), today),
        Section.RISK to listOf("Kill switch: off.", "Daily loss limit (paper): Rs 6,000.00."),
        Section.STATUS to listOf("Mode: Paper.", "Market: closed now."),
    ))

    @Test fun questionsAboutTheAppAreNotMarketQuestionsOrOrders() {
        for (text in listOf("can you analyze my strategies orders", "analyze my sell orders", "how are my strategies doing", "my positions",
                "what is my pnl today", "how am I doing", "how did my bots do", "show my studies", "where is the kill switch",
                "what is the daily loss limit", "which strategies are running", "show sell orders", "any rejected orders today")) {
            val q = Ask.parse(text)
            assertEquals(setOf(Topic.ACCOUNT), q.topics, text); assertNull(q.order, text)
        }
        assertTrue(Topic.BACKTEST in Ask.parse("backtest my strategy on nifty").topics)
        assertTrue(Topic.BACKTEST in Ask.parse("make a strategy from the hammer on nifty").topics)
        assertTrue(Topic.ORDER in Ask.parse("buy 1 lot nifty 24000 ce").topics)
        for (text in listOf("What is BankNifty doing today?", "Nifty levels", "How volatile is the market?", "any news on banks", "should i buy nifty"))
            assertTrue(Topic.ACCOUNT !in Ask.parse(text).topics, text)
    }

    @Test fun askingAboutIraItselfGetsHelp() {
        for (text in listOf("you can listen to me", "can you hear me?", "what can you do", "help")) assertEquals(setOf(Topic.HELP), Ask.parse(text).topics, text)
        val voice = Ira().answer("you can listen to me", emptyMap(), emptyList(), voice = true)
        assertTrue(voice.text.startsWith("Yes. Voice: switch on") && voice.text.contains("Jarvis, how is Nifty?"), voice.text)
        assertTrue(Ira().answer("what can you do", emptyMap(), emptyList()).text.contains("analyze my orders"))
        assertTrue(Ira().answer("can you hear me", emptyMap(), emptyList(), voice = false).text.startsWith("Voice is in JarvisAlgo only"))
    }

    @Test fun eachQuestionGetsItsSectionsAsFacts() {
        val ira = Ira()
        val a = ira.answer("can you analyze my strategies orders", emptyMap(), emptyList(), app = view)
        assertTrue(a.text.contains("Paper: 3 orders today, 2 filled, 0 open, 1 rejected or cancelled.") && a.text.contains("Paper's last rejection: Insufficient funds."), a.text)
        assertTrue(a.text.contains("2 of 3 strategies and arms are switched on.") && a.text.contains("Best today: Jarvis: breakout (+Rs 562.50)"), a.text)
        assertTrue(a.text.endsWith("These are facts from the app, not advice.") && ira.numbersBacked(a.text, a.facts), a.text)
        val pnl = ira.answer("what's my p&l today", emptyMap(), emptyList(), app = view)
        assertEquals("Paper P&L today +Rs 1,234.50 after charges: +Rs 900.00 booked, +Rs 334.50 open.", pnl.text)
        val month = ira.answer("how much did I make this month", emptyMap(), emptyList(), app = view)
        assertTrue(month.text.contains("Paper this month: +Rs 1,034.50 over 2 days, 1 of them up.") && !month.text.contains("P&L today"), month.text)
        assertTrue(ira.numbersBacked(month.text, month.facts), month.text)
        assertEquals("Kill switch: off. Daily loss limit (paper): Rs 6,000.00.", ira.answer("what is the daily loss limit", emptyMap(), emptyList(), app = view).text)
        val where = ira.answer("where is the kill switch", emptyMap(), emptyList(), app = view).text
        assertTrue(where.contains("More, then Bot settings"), where)
        assertTrue(ira.answer("my alarms", emptyMap(), emptyList(), app = view).text.startsWith("Nothing to show for alarms"))
        assertTrue(ira.answer("my orders", emptyMap(), emptyList(), app = null).text.startsWith("I could not read the app"))
        val all = ira.answer("how am i doing", emptyMap(), emptyList(), app = view).text
        assertTrue(all.contains("1. Paper position NIFTY25O0724500CE: 75 at 120.50, now 125.00, +Rs 337.50."), all)
    }

    @Test fun factBuilders() {
        assertEquals(listOf("No orders on Paper today."), AppFacts.orders("Paper", emptyList(), false))
        assertEquals(listOf("No open positions on Zerodha."), AppFacts.positions("Zerodha", emptyList()))
        assertEquals("No P&L on Paper today.", AppFacts.pnl("Paper", null, null, null))
        assertEquals(listOf("No strategies or arms are set up."), AppFacts.arms(emptyList(), true))
        assertEquals(listOf("No P&L days recorded on Paper yet."), AppFacts.history("Paper", emptyMap(), today))
        val h = AppFacts.history("Paper", mapOf(LocalDate.of(2026, 9, 30) to (500.0 to 3), LocalDate.of(2026, 10, 1) to (-200.0 to 2)), today)
        assertEquals("Paper last session before today (2026-10-01): -Rs 200.00.", h[0])
        assertEquals("Paper this week: +Rs 300.00 over 2 days.", h[1])
        assertTrue(h.last().startsWith("Paper best day 2026-09-30 +Rs 500.00; worst day 2026-10-01 -Rs 200.00; 2 days recorded"))
    }

    @Test fun newsTradesAreAskedAsTheReview() {
        assertTrue(Section.REVIEW in AppAnswers.sections("how are my news trades doing"))
        assertEquals(Topic.ACCOUNT, Ask.parse("how are the news trades doing?").topics.first())
    }

    @Test fun theStudyIsAskedInPlainWords() {
        assertEquals(setOf(Section.STUDY), AppAnswers.sections("what did you study last night?"))
        assertEquals(setOf(Section.STUDY), AppAnswers.sections("how will the market work today"))
        assertEquals(Topic.ACCOUNT, Ask.parse("what does history say about gaps?").topics.first())
    }
}
