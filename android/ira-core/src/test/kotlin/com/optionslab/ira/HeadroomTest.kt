package com.optionslab.ira

import com.optionslab.engine.risk.AccountGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadroomTest {
    private val limits = AccountGuard.Limits(maxDailyLoss = 2_000.0, maxDrawdownPct = 10.0, maxOpenPositions = 3, maxTradesPerDay = 10,
        entryCutoffMinute = 14 * 60 + 30)
    private fun acct(pnl: Double = -1_400.0, orders: Int = 4, held: List<String> = listOf("NIFTY24800CE"), minute: Int = 12 * 60 + 20) =
        AccountGuard.Account(capital = 100_000.0, equity = 100_000.0 + pnl, peakEquity = 100_000.0, dayPnl = pnl,
            holdings = held.map { AccountGuard.Holding(it, 75, 75) }, ordersToday = orders, minuteOfDay = minute)
    private fun zerodha(a: AccountGuard.Account = acct(), l: AccountGuard.Limits = limits) = Headroom.Book("Zerodha", a, l, enforced = true)

    @Test fun asked() {
        for (q in listOf("how close am I to my limits?", "Jarvis, how close am I to my limits", "am I near my limits", "how much headroom do I have left",
                "show me my headroom", "where do I stand on my limits", "limit se kitna door hoon", "am i within my risk limits"))
            assertEquals(Headroom.Asked.ALL, Headroom.asked(q), q)
        for (q in listOf("how much can I still lose today?", "how much more can I lose", "how close am i to my daily loss limit",
                "how much is left on my daily loss limit", "kitna aur loss le sakta hoon", "am i near my loss limit"))
            assertEquals(Headroom.Asked.LOSS, Headroom.asked(q), q)
        for (q in listOf("how many trades do I have left", "how many more trades can I take today", "how many orders can i place",
                "trades left today", "kitne trade bache hain", "how close am i to my trade limit"))
            assertEquals(Headroom.Asked.TRADES, Headroom.asked(q), q)
        for (q in listOf("set my daily loss limit to 3000", "what's my daily loss limit", "what are my risk limits", "raise my trade limit to 20",
                "how much did I lose today", "how much can nifty fall", "close all positions", "how is nifty", "how much did i lose this week",
                "increase the loss limit", "is the kill switch on"))
            assertNull(Headroom.asked(q), q)
    }

    @Test fun neverAnOrderOrCommandAndAnAccountQuestion() {
        for (q in listOf("how close am I to my limits?", "how much can I still lose today?", "how many trades do I have left", "kitna aur loss le sakta hoon")) {
            val p = Ask.parse(q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertFalse(Bundle.acts(q), q)
            assertEquals(setOf(Topic.ACCOUNT), p.topics, q)
            assertFalse(PreMarket.asked(q), q)
            assertFalse(DayJournal.asked(q), q)
            assertFalse(DataAge.asked(q), q)
            assertFalse(PatternCalls.asked(q), q)
            assertFalse(TaxRecords.exportAsked(q), q)
        }
    }

    @Test fun nearestLimitFirstInOneSentence() {
        val s = Headroom.say(listOf(zerodha()), Headroom.Asked.ALL)
        assertTrue(s.startsWith("On Zerodha the nearest limit is the daily loss: today's loss is Rs 1,400 of the Rs 2,000 daily loss limit (70%), Rs 600 left."), s)
        assertTrue("4 of 10 orders sent today, 6 left" in s, s)
        assertTrue("1 of 3 open positions" in s, s)
        assertTrue("drawdown 1.4% below" in s, s)
        assertTrue("New entries until 14:30 (2 h 10 min from now)." in s, s)
        assertTrue(s.endsWith("your call."), s)
    }

    @Test fun wellInsideAndAProfitDay() {
        val s = Headroom.say(listOf(zerodha(acct(pnl = 800.0, orders = 1, held = emptyList()))), Headroom.Asked.ALL)
        assertTrue(s.startsWith("On Zerodha you're well inside your limits; the nearest is the orders a day: 1 of 10 orders sent today, 9 left"), s)
        assertTrue("You're up Rs 800 today, Rs 2,800 from the Rs 2,000 daily loss limit" in s, s)
        assertTrue("no drawdown" in s, s)
    }

    @Test fun atALimitAndPastTheCutoff() {
        val s = Headroom.say(listOf(zerodha(acct(pnl = -2_100.0, minute = 14 * 60 + 45))), Headroom.Asked.ALL)
        assertTrue(s.startsWith("On Zerodha you're at a limit: today's loss Rs 2,100 has reached the Rs 2,000 daily loss limit - only exits are allowed for it."), s)
        assertTrue("Past the 14:30 cut-off: no new entries today." in s, s)
    }

    @Test fun scopedAsked() {
        val loss = Headroom.say(listOf(zerodha()), Headroom.Asked.LOSS)
        assertTrue("Rs 600 left" in loss && "orders" !in loss && "cut-off" !in loss, loss)
        val trades = Headroom.say(listOf(zerodha(acct(orders = 9))), Headroom.Asked.TRADES)
        assertTrue(trades.startsWith("On Zerodha the nearest limit is the orders a day: 9 of 10 orders sent today, 1 left"), trades)
        assertTrue("daily loss" !in trades, trades)
    }

    @Test fun offLimitsKillSwitchPaperAndBreaker() {
        val off = AccountGuard.Limits(maxDailyLoss = 0.0, maxDrawdownPct = 0.0, maxOpenPositions = 0, maxTradesPerDay = 0, entryCutoffMinute = null)
        assertTrue(Headroom.say(listOf(zerodha(l = off)), Headroom.Asked.ALL).startsWith("On Zerodha no limits are set."))
        val killed = Headroom.say(listOf(zerodha(l = limits.copy(killSwitch = true))), Headroom.Asked.ALL)
        assertTrue(killed.startsWith("On Zerodha the kill switch is on: no new positions until you clear it yourself; exits still go."), killed)
        val paper = Headroom.Book("Paper", acct(), limits.copy(killSwitch = true), enforced = false)
        val both = Headroom.say(listOf(zerodha(l = limits.copy(killSwitch = true)), paper), Headroom.Asked.ALL, breakerTripped = true)
        assertTrue("On Paper (practice: these limits do not refuse paper orders)" in both, both)
        assertEquals(1, Regex("kill switch").findAll(both).count(), "the kill switch guards Zerodha only")
        assertEquals(1, Regex("cut-off|New entries until").findAll(both).count(), "the cut-off refuses Zerodha entries only")
        assertTrue("The daily loss breaker tripped today" in both, both)
        assertTrue(Headroom.say(emptyList(), Headroom.Asked.ALL).startsWith("I couldn't read your accounts"))
    }

    @Test fun anUnknownPnlIsLeftOutNotGuessed() {
        val s = Headroom.say(listOf(zerodha(acct().copy(dayPnl = Double.NaN, equity = Double.NaN))), Headroom.Asked.LOSS)
        assertTrue(s.startsWith("On Zerodha no daily loss or drawdown limit is set (or today's P&L could not be read)."), s)
    }

    @Test fun aPositionsOrAWhatIfsLossIsNotTheDaysRoom() {
        for (q in listOf("how much can i lose if nifty falls 200 points", "how much can i lose on my 24500 put",
                "how much can i lose on my positions", "how much can i lose on this trade"))
            kotlin.test.assertNull(Headroom.asked(q), q)
        kotlin.test.assertEquals(Headroom.Asked.LOSS, Headroom.asked("how much can I still lose today?"))
    }
}
