package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotHealthTest {
    private val today = LocalDate.of(2026, 10, 7)                 // a Wednesday
    private val now = LocalDateTime.of(2026, 10, 7, 13, 15)
    private fun t(day: LocalDate, h: Int, m: Int, net: Double) = BotHealth.Trade(day.atTime(h, m), day.atTime(h, m + 10), net)

    private val backtest = BotHealth.tested("its backtest over 100 BankNifty days", 100, List(50) { if (it % 2 == 0) 300.0 else -200.0 } + listOf(-200.0, -200.0, -200.0))

    @Test fun asked() {
        for (q in listOf("how are my bots doing?", "Jarvis, is the ORB arm behaving?", "which strategy is losing?", "how are my strategies performing",
                "are my arms ok", "bot health", "mere bots kaise chal rahe hain", "which of my arms are losing", "is my pine script working fine"))
            assertTrue(BotHealth.asked(q), q)
        for (q in listOf("stop the ORB arm", "start my bots", "which arms suit this market", "what held up", "how is solo doing", "how is nifty",
                "show my strategies", "backtest the orb strategy", "check my positions"))
            assertFalse(BotHealth.asked(q), q)
    }

    @Test fun routedToTheAccountAlone() {
        for (q in listOf("how are my bots doing?", "is the ORB arm behaving?", "which strategy is losing?")) {
            val p = Ask.parse(q)
            assertEquals(setOf(Topic.ACCOUNT), p.topics, q)
            assertNull(p.order, q); assertNull(p.command, q)
            assertEquals(setOf(Section.BOTS), AppAnswers.sections(q), q)
        }
        // Unchanged: the plain list of strategies.
        assertTrue(Section.STRATEGIES in AppAnswers.sections("show my strategies"))
        assertEquals(setOf(Section.HEALTH), AppAnswers.sections("check my positions"))
    }

    @Test fun stats() {
        val s = BotHealth.stats(listOf(t(today, 9, 30, 500.0), t(today, 10, 0, -200.0), t(today, 10, 30, -400.0), t(today, 11, 0, 100.0), t(today, 11, 30, -50.0)))
        assertEquals(5, s.trades); assertEquals(2, s.wins); assertEquals(-50.0, s.net, 1e-9)
        assertEquals(600.0, s.maxDrawdown, 1e-9); assertEquals(1, s.streak); assertEquals(2, s.worstStreak)
        assertNull(BotHealth.stats(emptyList()).winRate)
    }

    @Test fun testedRecords() {
        assertNotNull(backtest)
        assertEquals(0.53, backtest!!.perDay!!, 1e-9)
        assertEquals(4, backtest.worstStreak)            // the last -200 of the alternation, then three more
        assertNull(BotHealth.tested("x", 0, listOf(1.0)))
        // Its own record before this week: only with enough trades.
        val few = List(5) { t(today.minusDays(10), 10, it, 100.0) }
        assertNull(BotHealth.own(few, BotHealth.weekStart(today)))
        val many = List(20) { t(today.minusDays(10 + (it % 4).toLong()), 10, it, if (it % 4 == 0) -100.0 else 100.0) }
        val own = BotHealth.own(many, BotHealth.weekStart(today))!!
        assertEquals(5.0, own.perDay!!, 1e-9)
        assertEquals(0.75, own.winRate!!, 1e-9)
    }

    @Test fun busyAgainstItsBacktest() {
        val b = BotHealth.Bot("ORB", "ORB arm", "Paper", true, List(4) { t(today, 9 + it, 30, 100.0) }, tested = backtest)
        val odd = BotHealth.unusual(b, today)
        assertEquals(listOf(BotHealth.Flag.BUSY), odd.map { it.flag })
        assertTrue("four trades today" in odd[0].text && "0.5 a day in its backtest" in odd[0].text, odd[0].text)
        // Two trades a day is its usual: nothing unusual.
        assertTrue(BotHealth.unusual(b.copy(trades = b.trades.take(2)), today).isEmpty())
    }

    @Test fun losingRunBeyondItsTestedWorst() {
        val ts = listOf(t(today.minusDays(1), 10, 0, -100.0), t(today.minusDays(1), 11, 0, -100.0), t(today.minusDays(1), 12, 0, -100.0), t(today, 9, 30, -100.0), t(today, 10, 30, -100.0))
        val b = BotHealth.Bot("ORB Fresh", "ORB arm", "Paper", true, ts, tested = backtest)
        val odd = BotHealth.unusual(b, today)
        assertEquals(BotHealth.Flag.STREAK, odd.single().flag)
        assertTrue("lost five in a row, beyond the worst run of four in its backtest" in odd[0].text, odd[0].text)
        // The run ended yesterday: not today's news.
        assertTrue(BotHealth.unusual(b.copy(trades = ts.take(3)), today).isEmpty())
        // No record to compare with: never unusual.
        assertTrue(BotHealth.unusual(b.copy(tested = null), today).isEmpty())
    }

    @Test fun linesAndLimits() {
        val orb = BotHealth.Bot("ORB", "ORB arm", "Paper", true, List(4) { t(today, 9 + it, 30, -300.0) }, tested = backtest, lastSignal = now.minusMinutes(45))
        val pine = BotHealth.Bot("EMA cross", "Pine script", "Zerodha", true, listOf(t(today.minusDays(1), 10, 0, 800.0)), dayLossLimit = 1000.0)
        val off = BotHealth.Bot("ORB Sweep", "ORB arm", "Paper", false, emptyList())
        val l = BotHealth.lines(listOf(pine, orb, off), now, breakerTripped = true)
        assertTrue(l[0].startsWith("Your strategies' health, Boss, at 13:15") && "Mon 5 Oct" in l[0], l[0])
        // Worst week first.
        assertTrue(l[1].startsWith("ORB (ORB arm, paper, on): today 4 trades, 0 won (0%), net -Rs 1,200"), l[1])
        assertTrue("4 losses in a row now" in l[1] && "against its backtest over 100 BankNifty days" in l[1] && "won 47% (this week 0%)" in l[1], l[1])
        assertTrue("last signal 12:30 today" in l[1], l[1])
        assertTrue(l[2].startsWith("EMA cross (Pine script, Zerodha, on): today no trades") && "daily loss limit Rs 1,000: not hit today" in l[2], l[2])
        assertTrue("too few earlier trades" in l[2] && "last entry 10:00 Tue 6 Oct" in l[2], l[2])
        assertTrue(l.any { it == "1 more is off with no trades this week: ORB Sweep." }, l.toString())
        assertTrue(l.any { it.startsWith("Unusual: ORB has taken four trades today") }, l.toString())
        assertTrue(l.any { it == "Losing this week: ORB -Rs 1,200." }, l.toString())
        assertTrue(l.any { "breaker tripped" in it })
        assertTrue(l.last().endsWith("stopping one is your call, Boss (say \"stop\" and its name)."))
        // The limit hit today.
        val hit = BotHealth.lines(listOf(pine.copy(trades = listOf(t(today, 10, 0, -1000.0)))), now)
        assertTrue(hit.any { "daily loss limit Rs 1,000: hit today" in it }, hit.toString())
        assertEquals(listOf("You have no strategies, arms, Pine auto-trade scripts or Solo running or on record, Boss: nothing to check."), BotHealth.lines(emptyList(), now))
    }

    @Test fun aNamedArmAlone() {
        val names = listOf("ORB", "ORB Fresh", "Liquidity 15+5")
        assertEquals(setOf("ORB"), BotHealth.named("is the ORB arm behaving?", names))
        assertEquals(setOf("ORB Fresh"), BotHealth.named("is the orb fresh arm ok", names))
        assertTrue(BotHealth.named("how are my bots doing", names).isEmpty())
        val orb = BotHealth.Bot("ORB", "ORB arm", "Paper", true, emptyList())
        val fresh = BotHealth.Bot("ORB Fresh", "ORB arm", "Paper", true, emptyList())
        val l = BotHealth.lines(listOf(orb, fresh), now, "is the ORB arm behaving?")
        assertTrue(l.any { it.startsWith("ORB (") } && l.none { it.startsWith("ORB Fresh") }, l.toString())
    }

    @Test fun spokenWithoutAmounts() {
        val b = BotHealth.Bot("ORB", "ORB arm", "Paper", true, List(4) { t(today, 9 + it, 30, -12345.0) }, tested = backtest)
        val s = BotHealth.spoken(BotHealth.unusual(b, today))!!
        assertTrue(s.startsWith("Boss, a word on your strategies: ORB has taken four trades today"), s)
        assertFalse(Overheard.holdsAccount(s), s)
        assertEquals(s, Overheard.said(s, locked = true))
        assertNull(BotHealth.spoken(emptyList()))
    }
}
