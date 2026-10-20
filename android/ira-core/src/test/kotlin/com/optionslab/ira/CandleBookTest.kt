package com.optionslab.ira

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CandleBookTest {
    private val day = LocalDate.of(2026, 10, 19)

    private fun asset(): String? = File("../app/src/main/assets/knowledge/candle_seed.json").takeIf { it.isFile }?.readText()

    /** 30 calm 5-minute candles (ATR 4), then [rest]. */
    private fun calm(n: Int = 30) = (0 until n).map { CandleSynth.bar(day.atTime(9, 15).plusMinutes(it * 5L), 100.0, 102.0, 98.0, 100.0) }

    @Test fun anOutcomeWaitsForTenCandlesOrTheSessionsEnd() {
        val bars = calm() + (0 until 5).map { CandleSynth.bar(day.atTime(11, 45).plusMinutes(it * 5L), 100.0, 103.0, 99.0, 102.0) }
        val reads = CandleRead.readAll("NIFTY", 5, bars)
        val r = reads.first { it.bar.t == day.atTime(11, 40) }
        assertNull(CandleBook.outcome(r, bars, sessionOver = false), "only 5 candles after it, mid-session: not settled")
        val o = CandleBook.outcome(r, bars, sessionOver = true)!!
        assertEquals(5, o.seen)
        assertEquals((102.0 - 100.0) / 4.0, o.moves[5]!!, 1e-9)
        assertNull(o.moves[10], "a horizon past the end stays missing")
        assertNull(o.wickHeld, "held needs the full 10 candles")
        assertEquals(true, o.up5)
    }

    @Test fun aGapEndsTheCountAndANewDayIsTheSessionsEnd() {
        val gap = calm() + CandleSynth.bar(day.atTime(11, 45), 100.0, 101.0, 99.0, 100.5) + CandleSynth.bar(day.atTime(13, 0), 100.0, 104.0, 99.0, 103.0)
        val reads = CandleRead.readAll("NIFTY", 5, gap)
        val o = CandleBook.outcome(reads.first { it.bar.t == day.atTime(11, 40) }, gap, sessionOver = false)!!
        assertEquals(1, o.seen, "the 13:00 candle is after a gap: not counted")
        // A holiday or a weekend: the next candle is another day's, so the read settles with what its own day had.
        val nextDay = calm() + CandleSynth.bar(day.plusDays(3).atTime(9, 15), 120.0, 121.0, 119.0, 120.0)
        val r2 = CandleRead.readAll("NIFTY", 5, nextDay)
        val o2 = CandleBook.outcome(r2.first { it.bar.t == day.atTime(11, 40) }, nextDay, sessionOver = false)!!
        assertEquals(0, o2.seen); assertTrue(o2.moves.isEmpty(), "never measured across the holiday")
    }

    @Test fun theLongerWicksEndIsTestedForTenCandles() {
        val tail = CandleSynth.bar(day.atTime(11, 45), 100.0, 101.0, 92.0, 100.5)
        val held = calm() + tail + (0 until 10).map { CandleSynth.bar(day.atTime(11, 50).plusMinutes(it * 5L), 100.0, 101.0, 93.0, 100.0) }
        val r = CandleRead.readAll("NIFTY", 5, held).first { it.bar.t == tail.t }
        assertEquals(true, CandleBook.outcome(r, held, false)!!.wickHeld)
        val broken = calm() + tail + (0 until 10).map { CandleSynth.bar(day.atTime(11, 50).plusMinutes(it * 5L), 100.0, 101.0, if (it == 3) 91.0 else 95.0, 100.0) }
        val r2 = CandleRead.readAll("NIFTY", 5, broken).first { it.bar.t == tail.t }
        val o = CandleBook.outcome(r2, broken, false)!!
        assertEquals(false, o.wickHeld); assertEquals(1, o.wickSide)
        assertTrue(o.mae10!! >= (100.5 - 91.0) / 4.0 - 1e-9)
    }

    @Test fun noAtrMeansNothingToMeasureIn() {
        val bars = calm(5)
        val r = CandleRead.readAll("NIFTY", 5, bars).first()
        assertNull(CandleBook.outcome(r, bars, true))
    }

    @Test fun cellsCountAndSurviveTheirTextAndBrokenLinesAreDropped() {
        val s = CandleBook.Stats()
        val bars = CandleSynth.days(day, 2, 5, seed = 4)
        var n = 0
        for (r in CandleRead.readAll("NIFTY", 5, bars)) { val o = CandleBook.outcome(r, bars, true) ?: continue; CandleBook.count(s, r, o); n++ }
        assertTrue(n > 100)
        s.marks["NIFTY|5"] = bars.last().t
        val text = CandleBook.encode(s)
        val back = CandleBook.decode(text + "\nC~broken\nC~x~1~5~0~0~0~0~0~0~0~\nnot a line")
        assertEquals(s.cells, back.cells)
        assertEquals(bars.last().t, back.marks["NIFTY|5"])
        assertEquals(CandleBook.encode(s), CandleBook.encode(back))
        assertEquals(0, CandleBook.decode(null).size); assertEquals(0, CandleBook.decode("").size)
        // Every read counted once under "all" for its shape and for its colour's baseline.
        val any = s.cells.filter { it.key.startsWith("NIFTY|5|*") && it.key.endsWith("|all") }.values.sumOf { it.n5 }
        assertTrue(any > 0)
    }

    @Test fun theStatsStayBounded() {
        val s = CandleBook.Stats()
        for (i in 0 until 3_000) s.cells["NIFTY|5|shape$i|ctx"] = CandleBook.Cell(n5 = i % 7)
        s.cells["NIFTY|5|hammer|all"] = CandleBook.Cell(n5 = 0)
        CandleBook.trim(s)
        assertEquals(CandleBook.MAX_CELLS, s.size)
        assertNotNull(s["NIFTY|5|hammer|all"], "the \"all\" cells go last")
        assertTrue(CandleBook.encode(s).length < 200_000)
    }

    @Test fun theSeedIsTheResearchAndABrokenOneIsEmpty() {
        val seed = CandleBook.seed(asset() ?: return)
        assertEquals(43, seed.families.size)
        val r = seed.row("big_lower_wick", "NIFTY", 15)!!
        assertEquals(949, r.n); assertEquals(0, r.edge)
        assertEquals(2, seed.held); assertEquals(17, seed.findings)
        val line = CandleBook.researchLine(seed, "big_lower_wick", "NIFTY", 15)!!
        assertTrue("n 949" in line && "no edge" in line, line)
        assertTrue("low was broken" in line, line)
        val held = CandleBook.researchLine(seed, "fp_stop_run_down", "NIFTY", 1)!!
        assertTrue("held out of sample" in held && "below an option's cost" in held, held)
        assertTrue("R85" in CandleBook.researchLine(seed, "hammer", "CRUDEOIL", 5)!!)
        assertEquals(5, CandleBook.researchTf(3)); assertEquals(15, CandleBook.researchTf(30)); assertEquals(1, CandleBook.researchTf(1))
        assertTrue(CandleBook.seed("{broken").empty); assertTrue(CandleBook.seed(null).empty); assertTrue(CandleBook.seed("[]").empty)
    }

    @Test fun itsOwnCountSaysAnEdgeOnlyWithEnoughEvidence() {
        val base = CandleBook.Cell(n5 = 1_000, up5 = 500)
        assertEquals(CandleBook.Says.FEW, CandleBook.says(CandleBook.Cell(n5 = 20, up5 = 20), base))
        assertEquals(CandleBook.Says.UP, CandleBook.says(CandleBook.Cell(n5 = 100, up5 = 70), base))
        assertEquals(CandleBook.Says.DOWN, CandleBook.says(CandleBook.Cell(n5 = 100, up5 = 30), base))
        assertEquals(CandleBook.Says.NONE, CandleBook.says(CandleBook.Cell(n5 = 100, up5 = 52), base))
        assertEquals(CandleBook.Says.FEW, CandleBook.says(null, base))
    }

    @Test fun learningComparesWithTheResearchAndOnlyWritesNotes() {
        val seed = CandleBook.seed(asset() ?: return)
        val s = CandleBook.Stats()
        s.cells["NIFTY|15|big_lower_wick|all"] = CandleBook.Cell(n5 = 200, up5 = 102)
        s.cells["NIFTY|15|*G|all"] = CandleBook.Cell(n5 = 2_000, up5 = 1_000)
        s.cells["NIFTY|15|*R|all"] = CandleBook.Cell(n5 = 2_000, up5 = 1_000)
        s.cells["NIFTY|15|hammer|all"] = CandleBook.Cell(n5 = 200, up5 = 160)
        s.cells["NIFTY|15|doji|all"] = CandleBook.Cell(n5 = 5, up5 = 5)
        val cmp = CandleBook.compare(s, seed).associateBy { it.shape }
        assertEquals(true, cmp.getValue("big_lower_wick").agrees)
        assertEquals(false, cmp.getValue("hammer").agrees)
        assertNull(cmp.getValue("doji").agrees)
        val res = CandleBook.results(s, seed, day)
        assertEquals(setOf("nifty:15:big_lower_wick", "nifty:15:hammer"), res.map { it.key }.toSet())
        assertTrue(res.first { it.key.endsWith("hammer") }.agrees.not())
        val notes = CandleBook.notes(s, seed)
        assertEquals(1, notes.size)
        assertTrue("nothing in any arm, size, stop or order changes" in notes[0], notes[0])
        assertEquals(66, CandleBook.growth(s))
        assertTrue("1 agree" in CandleBook.growthWords(s, seed) && "1 differ" in CandleBook.growthWords(s, seed))
        assertEquals(0, CandleBook.growth(CandleBook.Stats()))
    }

    @Test fun mcxAndContextCellsAreJudgedAgainstNoEdge() {
        val seed = CandleBook.seed(asset() ?: return)
        val s = CandleBook.Stats()
        s.cells["CRUDEOIL|5|hammer|all"] = CandleBook.Cell(n5 = 100, up5 = 50)
        s.cells["CRUDEOIL|5|*G|all"] = CandleBook.Cell(n5 = 500, up5 = 250)
        s.cells["CRUDEOIL|5|*R|all"] = CandleBook.Cell(n5 = 500, up5 = 250)
        val c = CandleBook.compare(s, seed).single()
        assertNull(c.research); assertEquals(CandleBook.Says.NONE, c.researchSays); assertEquals(true, c.agrees)
        assertFalse(CandleBook.compare(s, CandleBook.EMPTY_SEED).single().agrees ?: false)
    }
}
