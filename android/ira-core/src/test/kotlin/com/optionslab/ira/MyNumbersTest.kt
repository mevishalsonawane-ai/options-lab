package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MyNumbersTest {
    private val today = LocalDate.of(2026, 10, 5)            // a Monday

    private fun t(date: LocalDate, net: Double, held: Long = 10, owner: String = "Manual", sym: String = "NIFTY24500CE") =
        LocalDateTime.of(date, LocalTime.of(10, 0)).let { Insights.Trip(sym, it, it.plusMinutes(held), net, owner) }

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    @Test fun asked() {
        for (q in listOf("what's my average win and average loss?", "my average loss", "what is my risk reward on my trades", "my profit factor",
                "what's my expectancy", "how much do I make per trade", "do I hold my losers longer than my winners?", "do I cut my winners short?",
                "my trading stats", "Jarvis, show me my numbers", "mera average loss kitna hai", "how much do I lose per trade on average",
                "my win loss ratio", "what's my average profit per trade this month", "am I booking my profits too early"))
            assertTrue(MyNumbers.asked(q), q)
        for (q in listOf("what's my average price", "nifty average move", "what is the risk reward ratio", "how are my bots doing", "my losing streak",
                "what is a profit factor", "what's the ORB strategy's profit factor", "how was my month", "what is my p&l", "should I average down",
                "how much did I pay in charges", "your win rate on engulfing", "average premium of my position"))
            assertFalse(MyNumbers.asked(q), q)
    }

    @Test fun routedToTheAccount() {
        for (q in listOf("what's my average win and average loss?", "my profit factor", "do I hold my losers longer than my winners?", "my trading stats"))
            assertEquals(setOf(Section.NUMBERS), AppAnswers.sections(q), q)
        assertTrue(Topic.ACCOUNT in Ask.parse("what's my average loss").topics)
        assertTrue(Topic.ACCOUNT in Ask.parse("how much do I make per trade").topics)
    }

    @Test fun span() {
        assertEquals(MyNumbers.Span.ALL, MyNumbers.span("my profit factor"))
        assertEquals(MyNumbers.Span.MONTH, MyNumbers.span("my average loss this month"))
        assertEquals(MyNumbers.Span.LAST_WEEK, MyNumbers.span("my stats last week"))
        assertEquals(MyNumbers.Span.TODAY, MyNumbers.span("how much did I make per trade today"))
    }

    @Test fun numbers() {
        val n = MyNumbers.of(listOf(t(d(10, 1), 300.0), t(d(10, 1), 100.0), t(d(10, 2), -100.0), t(d(10, 2), -300.0), t(d(10, 2), 0.0)))
        assertEquals(5, n.trades); assertEquals(2, n.wins); assertEquals(2, n.losses)
        assertEquals(200.0, n.avgWin!!, 0.01); assertEquals(200.0, n.avgLoss!!, 0.01)
        assertEquals(1.0, n.payoff!!, 0.001); assertEquals(0.5, n.breakEven!!, 0.001); assertEquals(1.0, n.profitFactor!!, 0.001)
        assertEquals(0.5, n.winRate!!, 0.001)
    }

    @Test fun lines() {
        // Six own wins of Rs 200 held 5 minutes, four own losses of Rs 400 held 20 minutes, and a bot's trade left out.
        val trips = (1..6).map { t(d(9, 20 + it), 200.0, held = 5) } + (1..4).map { t(d(9, 20 + it), -400.0, held = 20) } +
            t(d(10, 2), 5000.0, owner = "ORB") + t(d(10, 1), 1000.0, sym = "BANKNIFTY52000PE")
        val l = MyNumbers.lines("Paper", trips, MyNumbers.Span.ALL, today)
        assertTrue(l[0].startsWith("Paper, your own trades on record (Mon 21 Sep to Thu 1 Oct): 11 trades, 7 won (64% of those decided), net +Rs 600"), l[0])
        assertTrue(l.any { it == "Your average win is Rs 314 and your average loss Rs 400: a loss is 1.3 times a win." }, l.joinToString("\n"))
        assertTrue(l.any { it.startsWith("At those sizes, breaking even takes winning about 56% of trades; you won 64%, 8 points above it.") }, l.joinToString("\n"))
        assertTrue(l.any { it == "Wins came to Rs 2,200 against losses of Rs 1,600: a profit factor of 1.38." }, l.joinToString("\n"))
        assertTrue(l.any { it == "Held (the middle trade): winners 5 minutes, losers 20 minutes - losers were held longer than winners." }, l.joinToString("\n"))
        assertTrue(l.last().contains("Biggest win +Rs 1,000 (BANKNIFTY52000PE, Thu 1 Oct)") && l.last().contains("without that one best trade the other 10 trades came to -Rs 400"), l.last())
        // Nothing to do, never a forecast.
        assertFalse(l.any { rx("(should|try|consider|next time|will )").containsMatchIn(it.lowercase()) }, l.joinToString("\n"))
    }

    @Test fun botsOnlyAndTooFew() {
        val l = MyNumbers.lines("Zerodha", listOf(t(d(10, 5), 100.0, owner = "ORB"), t(d(10, 5), -50.0, owner = "ORB")), MyNumbers.Span.TODAY, today)
        assertTrue(l[0].contains("all trades (you have none of your own, so these are the bots')") && l[0].contains(" today: 2 trades"), l[0])
        assertTrue(l.any { it.startsWith("Too few to compare sizes yet") }, l.joinToString("\n"))
        assertEquals(listOf("Paper: no closed trades last month, so no numbers to tell."),
            MyNumbers.lines("Paper", listOf(t(d(10, 1), 100.0)), MyNumbers.Span.LAST_MONTH, today))
    }
}
