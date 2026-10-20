package com.optionslab.ira

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Candle agent's 20 Oct review fixes (pure): candles closed by where the data ends (never the clock alone); the chain,
 * gamma and an outside VWAP read at the batch never counted (look-ahead); "differs from the research" only past
 * Benjamini-Hochberg; the latest reads newest first across series; heard findings never heard twice.
 */
class CandleReviewFixesTest {
    private val day = LocalDate.of(2026, 10, 19)
    private val seed = CandleBook.seed(File("../app/src/main/assets/knowledge/candle_seed.json").takeIf { it.isFile }?.readText())
    private fun ms(t: LocalDateTime) = t.toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli()
    private fun minutes(from: LocalDateTime, n: Int) = (0 until n).map { from.plusMinutes(it.toLong()) }

    // ---- closed by the data ----------------------------------------------------------------------------------------------

    @Test fun aFiveMinuteCandleFromTwoOfItsMinutesIsNeverClosedWhateverTheClock() {
        // MCX minutes fetched at 10:02:30 hold 10:00 and 10:01; the batch runs at 10:07 on that cache.
        val fetched = day.atTime(10, 2, 30)
        val mins = minutes(day.atTime(9, 50), 12)                     // 09:50 .. 10:01
        val end = Candles.dataEnd(mins, fetched, complete = true)
        assertEquals(day.atTime(10, 2), end)
        val starts = listOf(day.atTime(9, 50), day.atTime(9, 55), day.atTime(10, 0))
        assertEquals(listOf(day.atTime(9, 50), day.atTime(9, 55)), Candles.closedByData(starts, 5, end) { it })
        // The clock alone (the old rule) called 10:00 closed at 10:07.
        assertEquals(3, Candles.closed(starts.map { Candle(it, 1.0, 1.0, 1.0, 1.0) }, 5, day.atTime(10, 7)).size)
        // Once its minutes up to 10:04 are fetched, it closes.
        val later = Candles.dataEnd(minutes(day.atTime(9, 50), 15), day.atTime(10, 5, 10), complete = true)
        assertEquals(3, Candles.closedByData(starts, 5, later) { it }.size)
    }

    @Test fun theNewestMinuteOfARunningSessionIsNotTrustedAndAnEarlierDayIsComplete() {
        // An index history refreshed at 10:02:30 that may hold the forming 10:02 minute; read at 10:07 by the clock.
        val mins = minutes(day.atTime(9, 15), 48)                     // 09:15 .. 10:02
        val end = Candles.dataEnd(mins, day.atTime(10, 7), complete = false)
        assertEquals(day.atTime(10, 2), end, "10:02 may have been forming: not trusted")
        assertEquals(mins.dropLast(1), Candles.closedByData(mins, 1, end) { it })
        // After the close (complete), the last minute counts, and the session's last candle closes even if cut short.
        val done = Candles.dataEnd(mins, day.atTime(16, 0), complete = true)
        assertEquals(mins, Candles.closedByData(mins, 1, done) { it })
        assertEquals(listOf(day.atTime(10, 0)), Candles.closedByData(listOf(day.atTime(10, 0)), 15, done, sessionOver = true) { it })
        assertTrue(Candles.closedByData(listOf(day.atTime(10, 0)), 15, done) { it }.isEmpty())
        // Yesterday's data read this morning: complete.
        assertEquals(day.atTime(10, 3), Candles.dataEnd(mins, day.plusDays(1).atTime(8, 0), complete = false))
        // Empty data, and minutes all after the read: nothing.
        assertNull(Candles.dataEnd(emptyList(), day.atTime(10, 0), true))
        assertNull(Candles.dataEnd(mins, day.atTime(9, 15), true))
        assertTrue(Candles.closedByData(mins, 5, null) { it }.isEmpty())
        // A gap (an illiquid future with no trade at 10:03 and 10:04) closes on the next minute that exists.
        val gappy = minutes(day.atTime(10, 0), 3) + day.atTime(10, 5)
        assertEquals(1, Candles.closedByData(listOf(day.atTime(10, 0)), 5, Candles.dataEnd(gappy, day.atTime(10, 6), true)) { it }.size)
    }

    // ---- no look-ahead in the counts -------------------------------------------------------------------------------------

    @Test fun theChainGammaAndAnOutsideVwapReadAtTheBatchAreExplainedButNeverCounted() {
        val bars = CandleSynth.days(day.minusDays(3), 2, 5, start = 24_010.0)
        val strikes = listOf(CandleRead.Strike(23_900.0, 100, 9_000, ceOiChg = -50, peOiChg = 500), CandleRead.Strike(24_000.0, 3_000, 4_000, ceOiChg = 40, peOiChg = 30),
            CandleRead.Strike(24_300.0, 12_000, 200, ceOiChg = -10, peOiChg = 5))
        val around: (LocalDateTime) -> CandleRead.Around = { t ->
            if (t.toLocalDate() == day) CandleRead.Around(strikes, gamma = "short", vwap = bars.last().c - 500, expiry = true, vwapFrom = "the future's flow")
            else CandleRead.Around()
        }
        val reads = CandleRead.readAll("NIFTY", 5, bars, around = around)
        val live = setOf("short_gamma", "long_gamma", "bull_oi_shift", "bear_oi_shift", "call_writing", "put_writing", "far_above_vwap", "far_below_vwap")
        val r = reads.last()
        assertTrue(r.context.tags.any { it in live }, "${r.context.tags}")
        val c = CandleRead.countable(r)
        assertTrue(c.context.tags.none { it in live }, "${c.context.tags}")
        assertTrue("expiry_day" in c.context.tags, "a calendar fact stays")
        assertEquals(r.context.tags.filter { it !in live && it != "at_support" && it != "at_resistance" }, c.context.tags.filter { it != "at_support" && it != "at_resistance" })
        // A support from a put wall or the outside VWAP is the chain's NOW: at_support goes too.
        if (r.context.support?.kind == CandleRead.LevelKind.PUT_WALL && "at_support" in r.context.tags) assertTrue("at_support" !in c.context.tags)
        // End to end: a batch with today's chain counts no cell under those words.
        val a = CandleAgent(seed)
        val now = day.atTime(15, 45)
        a.study(listOf(CandleAgent.Series("NIFTY", 5, bars, sessionOver = true, around = around)), emptyList(), ms(now), now, false)
        val cells = a.table().map { it.ctx }.toSet()
        assertTrue(cells.none { it in live }, "$cells")
        // Its explanation still says them.
        val todays = a.latest().filter { it.read.bar.t.toLocalDate() == day }
        assertTrue(todays.isNotEmpty())
        assertTrue(todays.all { e -> e.lines.any { "dealers short gamma" in it } }, todays.first().lines.joinToString("\n"))
    }

    // ---- many cells: Benjamini-Hochberg ----------------------------------------------------------------------------------

    @Test fun thousandsOfCoinFlipCellsWriteNoNoteAndARealOneStillDoes() {
        val s = CandleBook.Stats()
        val r = java.util.Random(11)
        // 1,500 context cells of pure coin flips (n 60 each) against a fair baseline: |z| >= 2 alone flagged about 70.
        for (i in 0 until 1_500) {
            val n = 60
            val up = (0 until n).count { r.nextBoolean() }
            s.cells["NIFTY|5|hammer|ctx$i"] = CandleBook.Cell(n5 = n, up5 = up)
            s.cells["NIFTY|5|*G|ctx$i"] = CandleBook.Cell(n5 = 10_000, up5 = 5_000)
        }
        val raw = s.cells.entries.count { (k, c) -> !k.contains("|*") && kotlin.math.abs(CandleBook.z(c, s["NIFTY|5|*G|" + k.substringAfterLast('|')]) ?: 0.0) >= 2 }
        assertTrue(raw > 20, "the old rule's false alarms: $raw")
        assertTrue(CandleBook.notes(s, seed).isEmpty(), "${CandleBook.notes(s, seed)}")
        assertTrue(CandleBook.compare(s, seed).none { it.own == CandleBook.Says.UP || it.own == CandleBook.Says.DOWN })
        // A real difference survives the correction.
        s.cells["NIFTY|5|hammer|real"] = CandleBook.Cell(n5 = 400, up5 = 320)
        s.cells["NIFTY|5|*G|real"] = CandleBook.Cell(n5 = 10_000, up5 = 5_000)
        val real = CandleBook.compare(s, seed).single { it.ctx == "real" }
        assertEquals(CandleBook.Says.UP, real.own)
        if (!seed.empty) assertEquals(false, real.agrees)
    }

    @Test fun aResearchEdgeItsOwnCountDoesNotShowYetIsTooFewNotADifference() {
        if (seed.empty) return
        val row = seed.row("fp_stop_run_down", "NIFTY", 1)!!
        assertEquals(1, row.edge)
        val s = CandleBook.Stats()
        s.cells["NIFTY|1|fp_stop_run_down|all"] = CandleBook.Cell(n5 = 40, up5 = 21)
        s.cells["NIFTY|1|*G|all"] = CandleBook.Cell(n5 = 5_000, up5 = 2_500)
        s.cells["NIFTY|1|*R|all"] = CandleBook.Cell(n5 = 5_000, up5 = 2_500)
        val c = CandleBook.compare(s, seed).single()
        assertEquals(CandleBook.Says.NONE, c.own)
        assertNull(c.agrees, "n 40 cannot see a small edge: too few to compare, never 'differs'")
        assertTrue(CandleBook.notes(s, seed).isEmpty())
        assertTrue(CandleBook.results(s, seed, day).isEmpty())
    }

    // ---- the latest reads, newest first ----------------------------------------------------------------------------------

    @Test fun theLatestReadsAreNewestFirstWhateverSeriesTheyCameFrom() {
        val a = CandleAgent(seed)
        val now = day.atTime(14, 6)
        // NIFTY 5m ends with a fresh big tail at 14:00; then days of MCX 15m history arrive in the same first batch.
        val nifty = CandleSynth.days(day.minusDays(3), 2, 5, seed = 9, step = 4.0).filter { !it.t.isAfter(day.atTime(13, 55)) }.let { b ->
            val c = b.last().c; b + CandleSynth.bar(day.atTime(14, 0), c, c + 12.0, c - 30.0, c + 5.0)
        }
        val mcx = CandleSynth.days(day.minusDays(10), 8, 15, start = 5_600.0, step = 30.0, seed = 5).filter { it.t.isBefore(day.atTime(9, 0)) }
        a.study(listOf(CandleAgent.Series("NIFTY", 5, nifty), CandleAgent.Series("CRUDEOIL", 15, mcx, sessionOpenMin = 9 * 60)),
            emptyList(), ms(now), now, true)
        val latest = a.latest()
        assertTrue(latest.size <= CandleAgent.KEEP_EXPLAINED)
        val ends = latest.map { it.read.bar.t.plusMinutes(it.read.tf.toLong()) }
        assertEquals(ends.sortedDescending(), ends, "newest first")
        assertEquals(day.atTime(14, 0), latest.first().read.bar.t, latest.first().title)
        assertEquals("NIFTY", latest.first().inst)
        // Jarvis's default answer (no instrument named) is about that newest candle.
        val why = a.answer(CandleAgent.Question.WHY_COLOUR, null, false, ms(now))
        assertTrue("NIFTY" in why, why)
    }

    @Test fun aFindingIsNeverHeardTwiceEvenPastTheMemorysBound() {
        val a = CandleAgent(seed)
        val now = day.atTime(14, 6)
        val t = ms(now)
        val alive = Findings.Finding("the market brain", Findings.Kind.OTHER, Findings.ALL, atMs = t, words = "still alive", seq = 4_000)
        // 4,001 findings heard over the day (only the last still alive on the bus).
        val many = (0L..4_000L).map { Findings.Finding("the market brain", Findings.Kind.OTHER, Findings.ALL, atMs = t, words = "w$it", seq = it) }
        a.study(emptyList(), many, t, now, true)
        val heard0 = a.exchanges().count { it.way == CandleAgent.Way.HEARD }
        // The next batch hears the bus again: the live finding is not heard twice.
        a.study(emptyList(), listOf(alive), t + 60_000, now.plusMinutes(1), true)
        assertEquals(heard0, a.exchanges().count { it.way == CandleAgent.Way.HEARD })
        assertEquals(1, a.exchanges().count { it.words == "still alive" || it.words == "w4000" })
        // A new one is.
        a.study(emptyList(), listOf(alive.copy(seq = 4_001, words = "new")), t + 120_000, now.plusMinutes(2), true)
        assertTrue(a.exchanges().any { it.words == "new" })
    }
}
