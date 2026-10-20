package com.optionslab.ira

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 20 Oct, enhancement round 2: the Candles page's "as of" line and instrument chips ([CandlePage]), and the rule that only 5- and
 * 15-minute candles become lessons and notes ([CandleBook.countsAsLesson]); a 1-minute candle is explained only.
 */
class CandlePageTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val seed = CandleBook.seed(File("../app/src/main/assets/knowledge/candle_seed.json").takeIf { it.isFile }?.readText())
    private fun ms(t: LocalDateTime) = t.toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()

    // ---- "as of" ------------------------------------------------------------------------------------------------------------

    @Test fun nothingReadYetIsSaidPlainly() {
        val now = ms(day.atTime(10, 0))
        assertEquals("As of: nothing read yet (its first batch runs while NSE or MCX is open).", CandlePage.asOf(null, now, open = true))
        assertEquals("As of: nothing read yet (its first batch runs while NSE or MCX is open).", CandlePage.asOf(null, now, open = false))
        assertTrue("let go to free memory" in CandlePage.asOf(null, now, open = true, released = true))
    }

    @Test fun aFreshBatchWhileOpenIsJustItsTime() {
        assertEquals("As of 14:06.", CandlePage.asOf(ms(day.atTime(14, 6)), ms(day.atTime(14, 9)), open = true))
        // Exactly at the limit is not late yet.
        assertEquals("As of 14:06.", CandlePage.asOf(ms(day.atTime(14, 6)), ms(day.atTime(14, 6)) + CandlePage.STALE_MS, open = true))
        // A phone clock a little behind the batch's: no negative age, no "late".
        assertEquals("As of 14:06.", CandlePage.asOf(ms(day.atTime(14, 6)), ms(day.atTime(14, 5)), open = true))
    }

    @Test fun aLateBatchWhileOpenIsCalledStale() {
        val s = CandlePage.asOf(ms(day.atTime(11, 0)), ms(day.atTime(11, 25)), open = true)
        assertEquals("As of 11:00 (25 min ago: late, the next batch is overdue; what is shown may be stale).", s)
        val hours = CandlePage.asOf(ms(day.atTime(10, 0)), ms(day.atTime(13, 5)), open = true)
        assertTrue("3 h 5 min ago" in hours && "stale" in hours, hours)
    }

    @Test fun marketClosedSaysTheLastBatchAndAnOlderDayItsDate() {
        // After the close, an old batch is not "late": nothing newer exists to read.
        assertEquals("As of 15:45 (markets closed: the last batch's reads).", CandlePage.asOf(ms(day.atTime(15, 45)), ms(day.atTime(21, 0)), open = false))
        // A holiday or a weekend morning: the last batch was another day's, so its date is said too.
        val s = CandlePage.asOf(ms(day.atTime(15, 45)), ms(day.plusDays(3).atTime(9, 0)), open = false)
        assertEquals("As of 19 Oct 15:45 (markets closed: the last batch's reads).", s)
        // Open again on another day with nothing new yet: dated, and late.
        val late = CandlePage.asOf(ms(day.atTime(15, 45)), ms(day.plusDays(1).atTime(10, 0)), open = true)
        assertTrue(late.startsWith("As of 19 Oct 15:45 (") && "late" in late, late)
    }

    @Test fun agoIsInMinutesAndHours() {
        assertEquals("0 min", CandlePage.ago(-5_000))
        assertEquals("59 min", CandlePage.ago(59 * 60_000L + 59_000))
        assertEquals("1 h", CandlePage.ago(60 * 60_000L))
        assertEquals("2 h 1 min", CandlePage.ago(121 * 60_000L))
    }

    // ---- the chips -------------------------------------------------------------------------------------------------------------

    @Test fun theChipsAreAllThenWhatThePageShowsInOrder() {
        assertEquals(listOf(CandlePage.ALL), CandlePage.chips(emptyList()))
        assertEquals(listOf("All", "NIFTY", "BANKNIFTY", "CRUDEOIL", "SILVERM"),
            CandlePage.chips(listOf("SILVERM", "nifty", "CRUDEOIL", "BANKNIFTY", "NIFTY", " ", "")))
        // Something the page did not know of comes after, A to Z; "All" is never doubled.
        assertEquals(listOf("All", "SENSEX", "AAA", "ZINC"), CandlePage.chips(listOf("ZINC", "SENSEX", "AAA", "all")))
    }

    @Test fun aPickListsOnlyItsInstrumentAndFallsBackToAll() {
        val chips = CandlePage.chips(listOf("NIFTY", "BANKNIFTY"))
        assertEquals("NIFTY", CandlePage.picked("NIFTY", chips))
        assertEquals(CandlePage.ALL, CandlePage.picked("SENSEX", chips), "an instrument gone from the page: back to All")
        assertEquals(CandlePage.ALL, CandlePage.picked(null, chips))
        assertTrue(CandlePage.shows(CandlePage.ALL, "SENSEX") && CandlePage.shows(null, "SENSEX"))
        assertTrue(CandlePage.shows("NIFTY", "nifty"))
        assertFalse(CandlePage.shows("NIFTY", "BANKNIFTY"), "BANKNIFTY is not NIFTY")
    }

    @Test fun aLessonLineKeepsItsInstrument() {
        val l = CandlePage.lessonLine(AgentLessons.CandleResult(day, "banknifty:15:hammer", "Hammer on BANKNIFTY 15m", 120, agrees = false))
        assertEquals(CandlePage.Line("BANKNIFTY", "Hammer on BANKNIFTY 15m: differs from the research (120 counted)"), l)
    }

    // ---- lessons from 5- and 15-minute candles only --------------------------------------------------------------------------

    @Test fun theLessonTimeframesAreFiveAndFifteenMinutes() {
        assertFalse(CandleBook.countsAsLesson(1))
        assertTrue(CandleBook.countsAsLesson(5) && CandleBook.countsAsLesson(15))
        assertFalse(CandleBook.countsAsLesson(0) || CandleBook.countsAsLesson(3) || CandleBook.countsAsLesson(60))
    }

    /** One instrument x timeframe with a clear "differs" (hammer) and a plain "agrees" (big tail), as the agent counts them. */
    private fun cells(s: CandleBook.Stats, tf: Int, inst: String = "NIFTY") {
        s.cells["$inst|$tf|hammer|all"] = CandleBook.Cell(n5 = 300, up5 = 240)
        s.cells["$inst|$tf|*G|all"] = CandleBook.Cell(n5 = 3_000, up5 = 1_500)
        s.cells["$inst|$tf|*R|all"] = CandleBook.Cell(n5 = 3_000, up5 = 1_500)
        s.cells["$inst|$tf|big_lower_wick|all"] = CandleBook.Cell(n5 = 300, up5 = 150)
    }

    @Test fun emptyCountsGiveNoLessonAndNoNote() {
        assertTrue(CandleBook.results(CandleBook.Stats(), seed, day).isEmpty())
        assertTrue(CandleBook.notes(CandleBook.Stats(), seed).isEmpty() && CandleBook.noteRows(CandleBook.Stats(), seed).isEmpty())
    }

    @Test fun onlyOneMinuteCountsGiveNoLessonAndNoNote() {
        if (seed.empty) return
        val s = CandleBook.Stats()
        cells(s, 1)
        // The 1-minute cells are compared (the page's table shows them), so the rule, not a thin sample, keeps them out.
        assertTrue(CandleBook.compare(s, seed).any { it.tf == 1 && it.ctx == "all" && it.agrees != null })
        assertTrue(CandleBook.results(s, seed, day).isEmpty())
        assertTrue(CandleBook.notes(s, seed).isEmpty())
        assertTrue(CandleAgent(seed, s).lessons(day).isEmpty() && CandleAgent(seed, s).notes().isEmpty())
    }

    @Test fun mixedCountsGiveLessonsFromFiveAndFifteenMinutesOnly() {
        if (seed.empty) return
        val s = CandleBook.Stats()
        for (tf in listOf(1, 5, 15)) cells(s, tf)
        cells(s, 1, "CRUDEOIL")
        val res = CandleBook.results(s, seed, day)
        assertTrue(res.isNotEmpty())
        assertTrue(res.none { it.key.split(':')[1] == "1" }, res.map { it.key }.toString())
        assertEquals(setOf("5", "15"), res.map { it.key.split(':')[1] }.toSet())
        assertTrue(res.none { it.key.startsWith("crudeoil:") }, "CRUDEOIL has only 1-minute counts")
        val rows = CandleBook.noteRows(s, seed)
        assertTrue(rows.isNotEmpty() && rows.all { it.tf in CandleBook.LESSON_TFS && it.inst == "NIFTY" }, rows.map { it.words() }.toString())
        assertEquals(rows.map { CandleBook.noteWords(it) }, CandleBook.notes(s, seed))
        assertEquals(CandleBook.noteRows(s, seed).size, CandleAgent(seed, s).noteRows().size)
    }

    @Test fun aOneMinuteCandleIsExplainedButSaysItIsNeverALesson() {
        val a = CandleAgent(seed)
        val ones = CandleSynth.days(day, 1, 1, seed = 21, step = 3.0).filter { it.t.isBefore(day.atTime(14, 0)) }
        val fives = CandleSynth.days(day.minusDays(3), 2, 5, seed = 9, step = 4.0).filter { !it.t.isAfter(day.atTime(13, 55)) }
        // Market closed (the evening): both read and explained, nothing posted.
        val now = day.atTime(16, 0)
        val step = a.study(listOf(CandleAgent.Series("NIFTY", 1, ones, sessionOver = true), CandleAgent.Series("NIFTY", 5, fives, sessionOver = true)),
            emptyList(), ms(now), now, marketOpen = false)
        assertTrue(step.posts.isEmpty())
        val one = a.latest().filter { it.read.tf == 1 }
        val five = a.latest().filter { it.read.tf == 5 }
        assertTrue(one.isNotEmpty() && five.isNotEmpty(), "notable candles of both timeframes")
        assertTrue(one.all { e -> e.lines.any { CandleAgent.ONE_MINUTE_NOTE in it } }, one.first().lines.toString())
        assertTrue(five.none { e -> e.lines.any { CandleAgent.ONE_MINUTE_NOTE in it } })
        // Whatever it counted, no 1-minute lesson.
        assertTrue(a.lessons(day).none { it.key.split(':')[1] == "1" })
    }
}
