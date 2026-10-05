package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayJournalTest {
    private val day = LocalDate.of(2026, 10, 5)
    private fun at(h: Int, m: Int) = LocalDateTime.of(day, java.time.LocalTime.of(h, m))

    /** A bought trade from [h1]:[m1] to [h2]:[m2] with [net]; replayed with no candles unless [after] (points further after the exit). */
    private fun r(sym: String, h1: Int, m1: Int, h2: Int, m2: Int, net: Double, after: Double? = null): TradeReplay.Replay {
        val t = TradeReplay.Trip(sym, 1, 75, 100.0, 100.0 + net / 75, at(h1, m1), at(h2, m2), net)
        return TradeReplay.Replay(t, null, null, null, null,
            afterBest = after?.let { t.exit + it }, afterBestAt = after?.let { at(15, 0) }, afterWorst = after?.let { t.exit }, afterUntil = after?.let { at(15, 30) })
    }

    @Test fun asked() {
        for (q in listOf("help me journal today", "Jarvis, help me journal today", "help me with my journal", "draft my journal", "write my journal for today",
                "journal my day", "let's journal", "can you help me with today's journal", "journal for today", "my journal entry"))
            assertTrue(DayJournal.asked(q), q)
        for (q in listOf("journal: I bought because of the hammer", "note: I sold since the trend broke", "show my journal tags", "how was my day",
                "what is a trading journal", "go over my trades today"))
            assertFalse(DayJournal.asked(q), q)
    }

    @Test fun aNoteStaysANoteAndTheAskIsNot() {
        assertEquals(Command.Kind.NOTE, Commands.parse("Jarvis, note: I bought because of the hammer at support")?.kind)
        assertEquals(Command.Kind.NOTE, Commands.parse("journal: I bought because of the hammer at support")?.kind)
        assertTrue(Commands.parse("journal my day")?.kind != Command.Kind.NOTE)
    }

    @Test fun heard() {
        assertEquals(DayJournal.Heard.SKIP, DayJournal.heard("skip"))
        assertEquals(DayJournal.Heard.SKIP, DayJournal.heard("next question please"))
        assertEquals(DayJournal.Heard.STOP, DayJournal.heard("that's all"))
        assertEquals(DayJournal.Heard.STOP, DayJournal.heard("Jarvis stop"))
        assertEquals(DayJournal.Heard.ANSWER, DayJournal.heard("I wanted to win it back, honestly"))
        assertEquals(DayJournal.Heard.ANSWER, DayJournal.heard("I should stop trading after two losses"))
    }

    @Test fun answersAreRedactedAndTrimmed() {
        val k = DayJournal.kept("  I was   angry, my pin is 4321  ")
        assertFalse(k.contains("4321"), k)
        assertTrue(k.startsWith("I was angry,"), k)
        assertTrue(DayJournal.kept("x".repeat(1000)).length <= DayJournal.MAX_ANSWER)
    }

    @Test fun noTradesNothingToAsk() {
        val d = DayJournal.draft("Paper", day, emptyList())
        assertEquals(0, d.trades)
        assertTrue(d.questions.isEmpty())
        assertTrue(d.lines.single().contains("no trades of your own"))
    }

    @Test fun draftFromTheFacts() {
        val trades = listOf(
            r("NIFTY25O0725000CE", 9, 30, 10, 0, 1500.0, after = 12.0),     // best, ran on after the exit
            r("NIFTY25O0725000PE", 10, 50, 11, 0, -900.0),                // a loss
            r("NIFTY25O0725100CE", 11, 5, 11, 40, -2100.0),              // right after the loss: the day's biggest loss
            r("BANKNIFTY25O0755000CE", 13, 0, 13, 20, 300.0),            // against "I don't trade BankNifty"
        )
        val notes = listOf(DayJournal.Noted(at(9, 31), "the hammer at support"))
        val rules = listOf("I don't trade BankNifty", "no trades after 2 pm")
        val shown = listOf(DayJournal.Shown(at(11, 4), listOf("Your last trade lost, 4 minutes ago.")),
            DayJournal.Shown(at(11, 4).plusSeconds(40), listOf("Your last trade lost, 4 minutes ago.")))   // the review reopened
        val goals = listOf(Goals.status(Goals.Goal(Goals.Kind.MAX_LOSS, Goals.Period.DAY, 1000.0), listOf(Goals.Closed(day, -1200.0)), 4, day))
        val d = DayJournal.draft("Paper", day, trades, notes, rules, shown, goals, lossLimit = 6000.0, tradeLimit = 10)

        assertEquals(4, d.trades)
        val text = d.lines.joinToString("\n")
        assertTrue(d.lines[0].contains("4 trades of your own, 2 won, 2 lost"), d.lines[0])
        assertTrue(text.contains("Trade 1: 09:30-10:00") && text.contains("Your reason: \"the hammer at support\""), text)
        assertTrue(text.contains("Trade 3: 11:05-11:40") && text.contains("opened right after a loss"), text)
        assertTrue(text.contains("Rule \"I don't trade BankNifty\": broken by the 13:00 trade."), text)
        assertTrue(text.contains("Rule \"no trades after 2 pm\": kept all day."), text)
        // The same word reshown within two minutes is one word; the 11:05 trade followed it and lost.
        assertEquals(1, d.lines.count { it.startsWith("A word before an order") }, text)
        assertTrue(text.contains("A word before an order at 11:04 (just after a loss): you sent it; that 11:05 trade lost"), text)
        assertTrue(text.contains("Goal: Loss limit today") && text.contains("BROKEN"), text)
        assertTrue(text.contains("Daily loss limit Rs 6,000") && text.contains("Trades: 4 of the 10"), text)

        // At most three questions, one a trade, rule first, then the word sent anyway; no amounts in any of them.
        assertEquals(3, d.questions.size)
        assertEquals("Your 13:00 trade went against a rule you gave me - what made you take it?", d.questions[0].text)
        assertEquals("I put a word in before your 11:05 trade and you sent it anyway - what made you go ahead?", d.questions[1].text)
        // (The 11:05 trade was the day's biggest loss too: one question a trade, so the winner that ran on comes next.)
        assertEquals("Your 09:30 trade kept going your way after you got out - what made you exit when you did?", d.questions[2].text)
        d.questions.forEach { assertFalse(Overheard.holdsAccount(it.text), it.text) }

        // Said aloud: counts only.
        assertFalse(Overheard.holdsAccount(d.spoken), d.spoken)
        assertTrue(d.spoken.startsWith("Boss,") && d.spoken.contains("1 rule of yours broken") && d.spoken.contains("3 short questions"), d.spoken)
    }

    @Test fun answeredQuestionsAreNotAskedAgain() {
        val trades = listOf(r("NIFTY25O0725000PE", 10, 50, 11, 0, -900.0), r("NIFTY25O0725100CE", 11, 5, 11, 40, -2100.0))
        val first = DayJournal.draft("Paper", day, trades)
        assertEquals("Your 11:05 trade came right after a loss - what were you thinking?", first.questions[0].text)
        val again = DayJournal.draft("Paper", day, trades, answered = setOf(first.questions[0].key))
        assertTrue(again.questions.none { it.key == first.questions[0].key })
    }

    @Test fun aWinnerThatRanOnIsAskedAbout() {
        val d = DayJournal.draft("Paper", day, listOf(r("NIFTY25O0725000CE", 9, 30, 10, 0, 1500.0, after = 12.0)))
        assertEquals("Your 09:30 trade kept going your way after you got out - what made you exit when you did?", d.questions.single().text)
    }

    @Test fun aWordWithNoTradeAfterIsHeldOff() {
        val d = DayJournal.draft("Paper", day, listOf(r("NIFTY25O0725000CE", 9, 30, 10, 0, 500.0)),
            shown = listOf(DayJournal.Shown(at(12, 0), listOf("It's the first five minutes of the session."))))
        assertTrue(d.lines.any { it == "A word before an order at 12:00 (the first five minutes): no trade of yours followed within 10 minutes." }, d.lines.toString())
    }

    @Test fun theQuestionComesFirstWhenSaid() {
        val q = DayJournal.Question("x", "Your 11:05 trade came right after a loss - what were you thinking?")
        assertTrue(DayJournal.first(q, 2).startsWith("For your journal, Boss: your 11:05 trade came right after a loss - what were you thinking?"))
        assertTrue(DayJournal.next(DayJournal.Heard.ANSWER, q).startsWith("Noted in your journal, Boss - next: your 11:05"))
        assertTrue(DayJournal.next(DayJournal.Heard.SKIP, null).contains("journal is done"))
        assertTrue(DayJournal.next(DayJournal.Heard.STOP, q).contains("help me journal today"))
    }

    @Test fun entryKeepsHisWords() {
        val e = DayJournal.entry(listOf("Paper journal...", "Questions for you (1): ..."),
            listOf(DayJournal.Answer("k", "What were you thinking?", "I wanted it back", at(15, 40))))
        assertEquals(listOf("Paper journal...", "Q: What were you thinking? You said: \"I wanted it back\""), e)
    }
}
