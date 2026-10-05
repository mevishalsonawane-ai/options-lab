package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FigureFirstTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val now: LocalDateTime = today.atTime(15, 0)
    private val trend = "topic:TREND"

    private fun log(kind: String, figure: Int, plain: Int = 0, from: LocalDateTime = now.minusDays(1)): FigureFirst.Log {
        var l = FigureFirst.Log()
        repeat(figure) { l = FigureFirst.heard(l, kind, true, from.plusMinutes(it.toLong())) }
        repeat(plain) { l = FigureFirst.heard(l, kind, false, from.plusMinutes(100L + it)) }
        return l
    }

    @Test fun aReAskIsAfterAFigureByItsWordsOrForTheLevels() {
        assertTrue(FigureFirst.wantsFigure(trend, "where exactly is nifty headed"))
        assertTrue(FigureFirst.wantsFigure(trend, "how many points is the trend"))
        assertTrue(FigureFirst.wantsFigure(trend, "nifty trend kitna strong hai"))
        assertFalse(FigureFirst.wantsFigure(trend, "what is the trend on nifty"))
        assertTrue(FigureFirst.wantsFigure("topic:LEVELS", "banknifty levels please"))
    }

    @Test fun aKindLeadsOnlyWhenAskedAgainForItsFigureOftenEnough() {
        assertTrue(FigureFirst.leading(log(trend, 2), now).isEmpty(), "twice may be chance")
        assertEquals(listOf(trend), FigureFirst.leading(log(trend, 3), now).map { it.kind })
        assertTrue(FigureFirst.leading(log(trend, 3, plain = 4), now).isEmpty(), "under half its re-asks")
        // Older than the window, or before Boss asked for the usual order: not counted.
        assertTrue(FigureFirst.leading(log(trend, 3, from = now.minusDays(FigureFirst.WINDOW_DAYS + 1)), now).isEmpty())
        assertTrue(FigureFirst.leading(FigureFirst.reset(log(trend, 5), now.minusHours(1)), now).isEmpty())
    }

    @Test fun theFigureSentenceIsMovedFirstWithEveryWordKept() {
        val text = "Boss, Nifty is trading in a range today. The buyers and sellers look balanced. Support is 24,500 and resistance 24,700. Watch the open."
        val r = FigureFirst.reorder(text)
        assertTrue(r.startsWith("Support is 24,500 and resistance 24,700."), r)
        assertEquals(text.split(" ").sorted(), r.split(" ").sorted())
        // Already first, none, a sentence leaning on the one before, lines or Hindi: as it is.
        assertEquals("Nifty is at 24,612. It is up.", FigureFirst.reorder("Nifty is at 24,612. It is up."))
        assertEquals("Nifty is calm. Nothing moved.", FigureFirst.reorder("Nifty is calm. Nothing moved."))
        assertEquals("Nifty is weak. It fell 0.8% since the open.", FigureFirst.reorder("Nifty is weak. It fell 0.8% since the open."))
        assertEquals("Levels:\nSupport 24,500.", FigureFirst.reorder("Levels:\nSupport 24,500."))
        // A small count or a date is not a figure to lead with.
        assertEquals("Nifty is trending up. On 3 Oct it did too.", FigureFirst.reorder("Nifty is trending up. On 3 Oct it did too."))
        assertEquals("The day's move is 0.6 percent. Nifty is trending up.",
            FigureFirst.reorder("Nifty is trending up. The day's move is 0.6 percent."))
    }

    @Test fun nothingIsMovedAheadOfAnAgeDoubtOrClosedNote() {
        val leading = FigureFirst.leading(log("topic:LEVELS", 3), now)
        val notes = listOf(
            "My prices are 7 minutes old, Boss. Nifty is in a range. Support is 24,500.",
            "My last Nifty candle closed at 10:48, Boss, 12 minutes ago - the chart may have moved since. Nifty is in a range. Support is 24,500.",
            "Did you mean the Nifty levels, Boss? Taking it that way. Nifty is in a range. Support is 24,500. Check me on this, Boss - you marked 3 of my levels wrong lately.",
            "The market is closed; these are the last prices. Nifty is in a range. Support is 24,500.",
            "Careful, Boss: the last Nifty price I have is 12 minutes old - the live feed is behind. Nifty is in a range. Support is 24,500.")
        for (text in notes) {
            assertTrue(FigureFirst.hasNote(text), text)
            assertEquals(text, FigureFirst.reorder(text), text)
            assertEquals(text, FigureFirst.lead("what are the levels on nifty", text, leading), text)
            // Said SHORT (one sentence): the note is what is said, never dropped for the figure.
            val short = Aloud.say(FigureFirst.lead("what are the levels on nifty", text, leading), Aloud.Length.SHORT)
            assertFalse(short.startsWith("Support is 24,500"), short)
        }
        val aged = "My prices are 7 minutes old, Boss. Nifty is in a range. Support is 24,500."
        assertTrue(Aloud.say(FigureFirst.lead("what are the levels on nifty", aged, leading), Aloud.Length.SHORT).contains("7 minutes old"))
        // Without a note it is still reordered.
        assertFalse(FigureFirst.hasNote("Nifty is in a range. Support is 24,500."))
        assertEquals("Support is 24,500. Nifty is in a range.", FigureFirst.reorder("Nifty is in a range. Support is 24,500."))
    }

    @Test fun onlyAMarketReadOfALeadingKindIsReordered() {
        val leading = FigureFirst.leading(log("topic:LEVELS", 3), now)
        val text = "Nifty is in a range. Support is 24,500."
        assertEquals("Support is 24,500. Nifty is in a range.", FigureFirst.lead("what are the levels on nifty", text, leading))
        assertEquals(text, FigureFirst.lead("what is the trend on nifty", text, leading))
        assertEquals(text, FigureFirst.lead("stop all strategies", text, leading))
        assertEquals(text, FigureFirst.lead("buy 1 lot nifty 25000 ce", text, leading))
        assertEquals(text, FigureFirst.lead("what are the levels on nifty", text, emptyList()))
    }

    @Test fun askedAndUndone() {
        for (s in listOf("which reads do you start with the number", "why do you start with the number", "why are you saying the figure first",
                "which answers do you put the number first", "kaun se jawab mein number pehle bolte ho"))
            assertEquals(FigureFirst.Request.WHICH, FigureFirst.asked(s), s)
        for (s in listOf("say your market reads in the usual order", "don't start with the number", "don't put the figure first",
                "number pehle mat bolo"))
            assertEquals(FigureFirst.Request.RESET, FigureFirst.asked(s), s)
        for (s in listOf("what is the number of lots", "what are the levels on nifty", "start with the levels", "what is nifty at"))
            assertNull(FigureFirst.asked(s), s)
        for (s in listOf("which reads do you start with the number", "say your market reads in the usual order"))
            assertTrue(Ask.parse(s).command == null && Ask.parse(s).order == null, s)
    }

    @Test fun saidAndInTheLedgerWithItsUndo() {
        val l = log(trend, 3, plain = 1)
        val said = FigureFirst.say(l, now)
        assertTrue(said.contains("3 of the 4") && said.contains(FigureFirst.UNDO) && said.contains("nothing I learn acts"), said)
        assertTrue(FigureFirst.say(FigureFirst.Log(), now).contains("usual order"))
        val items = Learnings.items(Learnings.Inputs(figure = l), now).filter { it.area == Learnings.Area.FIGURE_FIRST }
        assertEquals(1, items.size)
        assertEquals(FigureFirst.UNDO, items[0].undo)
        // Undoing the week resets it.
        val u = Learnings.undo(Learnings.Inputs(figure = l), now)
        assertEquals(1, u.figure.size)
        assertTrue(Learnings.offer(u).contains("figure aloud"))
        assertTrue(FigureFirst.sayReset(l, now).startsWith("Done"))
    }
}
