package com.optionslab.ira

import com.optionslab.ira.LiquidityInsight.Dim
import com.optionslab.ira.LiquidityInsight.Focus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiquidityInsightTest {
    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|consider (switching|changing|adding|trading)|switch (it )?off|turn (it )?off|" +
        "reduce|increase|fewer lots|more lots|bigger|trade more|size up|add lots|change (the |its )?rules? (to|now)|skip (the )?(mornings?|afternoons?))\\b")

    private val MON: LocalDate = LocalDate.of(2026, 10, 5)   // a Monday

    /** One closed paper trade of the arm: [perLot] net a lot after charges (one lot), entered at [h]:[m] on [day]. */
    private fun row(perLot: Double, h: Int = 10, m: Int = 0, day: LocalDate = MON, book: String = "BankNifty 5-min", right: String = "CE",
                    why: String = if (perLot > 0) "next_liquidity" else "index_stop", level: Double? = null, target: Double? = null,
                    index: String = if (book.startsWith("Fin")) "FINNIFTY" else "BANKNIFTY", seq: Int = 0): LiquidityRecord.Row {
        val at = LocalDateTime.of(day, LocalTime.of(h, m)).plusSeconds(seq.toLong())
        val tr = BotTrades.Trade("liquidity5", "BANKNIFTY26OCT56000CE", right, 35, 100.0, at, at.minusMinutes(5), 100.0 + perLot / 35, at.plusMinutes(15),
            why, 0.0, false, level, target, lot = 35)
        return LiquidityRecord.Row(tr, index, book, 1.0, perLot, perLot)
    }

    /** [xs] as trades entered in the [bucket] window (10:00, 11:00, 12:30 or 13:45), spread over the week's days. */
    private fun at(h: Int, m: Int, xs: List<Double>, start: Int = 0) = xs.mapIndexed { i, x -> row(x, h, m, MON.plusDays(((start + i) % 5).toLong()), seq = start + i) }

    // ---- the arithmetic ------------------------------------------------------------------------------------------------

    @Test fun meanSampleSdAndTheWelchStandardError() {
        val a = LiquidityInsight.stats(listOf(1.0, 2.0, 3.0, 4.0))
        assertEquals(4, a.n); assertEquals(2.5, a.mean)
        assertEquals(sqrt(5.0 / 3.0), a.sd!!, 1e-12)                          // sum of squares 5, over n - 1
        val b = LiquidityInsight.stats(listOf(10.0, 20.0, 30.0))
        assertEquals(20.0, b.mean); assertEquals(10.0, b.sd!!, 1e-12)
        assertEquals(sqrt(5.0 / 3.0 / 4 + 100.0 / 3), LiquidityInsight.standardError(a, b)!!, 1e-12)
        assertNull(LiquidityInsight.stats(listOf(7.0)).sd)
        assertNull(LiquidityInsight.standardError(LiquidityInsight.stats(listOf(7.0)), b))
        assertEquals(0, LiquidityInsight.stats(emptyList()).n)
    }

    @Test fun theBucketsOfEachCut() {
        assertEquals("09:20-10:30", LiquidityInsight.timeBucket(LocalTime.of(9, 20)))
        assertEquals("09:20-10:30", LiquidityInsight.timeBucket(LocalTime.of(10, 29)))
        assertEquals("10:30-12:00", LiquidityInsight.timeBucket(LocalTime.of(10, 30)))
        assertEquals("10:30-12:00", LiquidityInsight.timeBucket(LocalTime.of(11, 59)))
        assertEquals("12:00-13:30", LiquidityInsight.timeBucket(LocalTime.of(12, 0)))
        assertEquals("13:30-14:00", LiquidityInsight.timeBucket(LocalTime.of(13, 30)))
        assertEquals("13:30-14:00", LiquidityInsight.timeBucket(LocalTime.of(14, 0)))
        // Room in index-stop units: BankNifty 30 points, FinNifty 15; the trade's own direction.
        assertEquals(100.0 / 30, LiquidityInsight.roomStops(row(1.0, level = 54_000.0, target = 54_100.0))!!, 1e-9)
        assertEquals(50.0 / 30, LiquidityInsight.roomStops(row(1.0, right = "PE", level = 54_000.0, target = 53_950.0))!!, 1e-9)
        assertEquals(4.0, LiquidityInsight.roomStops(row(1.0, book = "FinNifty 30-min", level = 25_000.0, target = 25_060.0))!!, 1e-9)
        assertTrue(LiquidityInsight.roomStops(row(1.0, level = 54_000.0))!!.isInfinite())
        assertNull(LiquidityInsight.roomStops(row(1.0)))
        assertEquals("under 2 index stops", LiquidityInsight.roomBucket(1.5))
        assertEquals("2-4 index stops", LiquidityInsight.roomBucket(2.0))
        assertEquals("4+ index stops", LiquidityInsight.roomBucket(4.0))
        assertEquals("no level ahead", LiquidityInsight.roomBucket(Double.POSITIVE_INFINITY))
        assertNull(LiquidityInsight.roomBucket(null))
        // The other cuts' keys.
        val r = row(-100.0, day = LocalDate.of(2026, 10, 8), right = "PE", why = "failed_break", book = "FinNifty 30-min")
        assertEquals("Thursday", LiquidityInsight.keyOf(Dim.WEEKDAY, r))
        assertEquals("PE", LiquidityInsight.keyOf(Dim.SIDE, r))
        assertEquals("FinNifty 30-min", LiquidityInsight.keyOf(Dim.BOOK, r))
        assertEquals("failed_break", LiquidityInsight.keyOf(Dim.EXIT, r))
        assertNull(LiquidityInsight.keyOf(Dim.ROOM, r))
    }

    @Test fun aGroupNeedsFiveTradesAndARestOfFiveToBeTested() {
        val rows = at(10, 0, List(6) { 100.0 }) + at(11, 0, List(4) { 200.0 }, 6)
        val cuts = LiquidityInsight.cuts(Dim.TIME, rows)
        assertEquals(listOf("09:20-10:30"), cuts.map { it.key })                 // 4 trades: left out
        assertEquals(1, LiquidityInsight.smallGroups(Dim.TIME, rows))
        val c = cuts.single()
        assertEquals(6, c.n); assertEquals(4, c.restN)
        assertNull(c.restMean); assertNull(c.se); assertNull(c.z)                 // a rest of 4: nothing to set it against
        assertFalse(c.tested); assertFalse(c.standsOut)
        // Every trade in one group: no rest at all.
        val one = LiquidityInsight.cuts(Dim.SIDE, rows).single()
        assertEquals(10, one.n); assertEquals(0, one.restN); assertFalse(one.tested)
        // Unrecorded room is left out of the room cut (its rest is only the recorded trades).
        assertTrue(LiquidityInsight.cuts(Dim.ROOM, rows).isEmpty())
    }

    // ---- the significance guard on synthetic data ----------------------------------------------------------------------

    @Test fun aRealDifferenceStandsOutAndItsZIsComputedProperly() {
        val good = listOf(1000.0, 1200.0, 1100.0, 900.0, 1300.0, 1050.0)
        val rest = listOf(-200.0, 0.0, -100.0, 100.0, -300.0, -50.0, 50.0, -150.0)
        val rows = at(10, 0, rest) + at(11, 0, good, rest.size)
        val c = LiquidityInsight.cuts(Dim.TIME, rows).first { it.key == "10:30-12:00" }
        // By hand: the means, the sample variances, Welch's standard error.
        fun mean(x: List<Double>) = x.sum() / x.size
        fun v(x: List<Double>) = x.sumOf { (it - mean(x)) * (it - mean(x)) } / (x.size - 1)
        val se = sqrt(v(good) / good.size + v(rest) / rest.size)
        assertEquals(mean(good), c.mean, 1e-9); assertEquals(mean(rest), c.restMean!!, 1e-9)
        assertEquals(se, c.se!!, 1e-9)
        assertEquals((mean(good) - mean(rest)) / se, c.z!!, 1e-9)
        assertTrue(c.standsOut)
        val read = LiquidityInsight.read(rows)
        // Both time groups stand out, but they are each other's rest: one finding, told once (the better side).
        assertEquals(listOf("10:30-12:00"), read.standing.filter { it.dim == Dim.TIME }.map { it.key })
        val a = LiquidityInsight.answer(Focus.WORKING, rows)
        assertTrue("Stands out on the good side: entries 10:30-12:00 - 6 trades, 100% won, +Rs 1,092 a lot a trade vs -Rs 81 for the other 8" in a, a)
        assertTrue("standard errors better)" in a, a)
        assertTrue("Worth watching, not proof" in a, a)
        assertFalse(LiquidityInsight.NONE in a, a)
    }

    @Test fun differencesWithinNoiseNeverStandOut() {
        // Two groups of five drawn from the same wide spread, means 160 and 140: far inside 2 standard errors.
        val a = listOf(100.0, -500.0, 900.0, -300.0, 600.0)
        val b = listOf(-100.0, 400.0, -600.0, 800.0, 200.0)
        val rows = at(10, 0, a) + at(13, 45, b, 5)
        val cuts = LiquidityInsight.cuts(Dim.TIME, rows)
        assertTrue(cuts.all { it.tested && abs(it.z!!) < 1 }, cuts.toString())
        assertTrue(LiquidityInsight.read(rows).standing.isEmpty())
        val text = LiquidityInsight.answer(Focus.PATTERNS, rows)
        assertTrue(LiquidityInsight.NONE in text, text)
        assertFalse("Worth watching" in text, text)
        // A big gap in the means with a bigger spread is still noise: means +1,000 vs -1,000, but spread +-6,000.
        val wild = at(10, 0, listOf(7000.0, -5000.0, 6000.0, -4000.0, 1000.0)) + at(11, 0, listOf(5000.0, -7000.0, 4000.0, -6000.0, -1000.0), 5)
        val w = LiquidityInsight.cuts(Dim.TIME, wild).first()
        assertEquals(2000.0, w.diff!!, 1e-9)
        assertFalse(w.standsOut, "z ${w.z}")
        // Identical trades: no spread to measure noise by - never said to stand out.
        val flat = at(10, 0, List(5) { 100.0 }) + at(11, 0, List(5) { 300.0 }, 5)
        assertNull(LiquidityInsight.cuts(Dim.TIME, flat).first().z)
        assertTrue(LiquidityInsight.read(flat).standing.isEmpty())
    }

    @Test fun exitsAreOutcomesSetAgainstTheResearchsMixNeverStandingOut() {
        val rows = at(10, 0, List(6) { 1500.0 + it }) + at(11, 0, List(6) { -600.0 - it }, 6)
        val exits = LiquidityInsight.cuts(Dim.EXIT, rows)
        assertEquals(setOf("next_liquidity", "index_stop"), exits.map { it.key }.toSet())
        assertTrue(exits.none { it.tested || it.standsOut })
        assertTrue(exits.all { abs(it.z!!) > 2 })                                   // the gap is huge - and still not "standing out"
        // The research's share of all its trades: (wins share x 600 + losses share x 1,051) / 1,651.
        assertEquals((2.0 * 600 + 30.4 * 1051) / (100.0 * 1651), LiquidityInsight.researchExitShare("index_stop")!!, 1e-12)
        assertEquals((52.0 * 600 + 2.0 * 1051) / (100.0 * 1651), LiquidityInsight.researchExitShare("next_liquidity")!!, 1e-12)
        assertNull(LiquidityInsight.researchExitShare("closed_by_you"))
        // The binomial guard: 6 of 12 index stops against about 20% is beyond 2 standard errors (sqrt(.2 x .8 / 12) = 0.115).
        assertEquals(1, LiquidityInsight.shareVs(6, 12, 0.2008))
        assertEquals(0, LiquidityInsight.shareVs(3, 12, 0.2008))
        assertEquals(-1, LiquidityInsight.shareVs(0, 100, 0.2008))
        val text = LiquidityInsight.answer(Focus.PATTERNS, rows)
        assertTrue("By exit: " in text && "index stop 6 trades (0 won)" in text && "50% of trades vs 20% in the research, more often than noise explains" in text, text)
    }

    // ---- the wording ---------------------------------------------------------------------------------------------------

    private fun book(): List<LiquidityRecord.Row> {
        // 24 trades over two weeks: mornings strong, the 13:30-14:00 entries weak; mixed books, sides and room.
        val out = ArrayList<LiquidityRecord.Row>()
        var i = 0
        fun add(x: Double, h: Int, m: Int, book: String, right: String, level: Double?, target: Double?) {
            out += row(x, h, m, MON.plusDays((i % 5 + 7 * (i / 12)).toLong()), book, right, level = level, target = target, seq = i); i++
        }
        val morning = listOf(900.0, 1400.0, 700.0, 1100.0, 1250.0, 800.0)
        val late = listOf(-700.0, -500.0, -650.0, -800.0, -550.0, -600.0)
        val mid = listOf(200.0, -300.0, 450.0, -150.0, 100.0, -250.0, 300.0, -400.0, 50.0, 150.0, -100.0, 250.0)
        morning.forEachIndexed { k, x -> add(x, 9, 40, if (k % 2 == 0) "BankNifty 5-min" else "FinNifty 5-min", if (k % 2 == 0) "CE" else "PE", 54_000.0, 54_150.0) }
        late.forEachIndexed { k, x -> add(x, 13, 40, if (k % 2 == 0) "BankNifty 15-min" else "FinNifty 30-min", if (k % 2 == 0) "PE" else "CE", 54_000.0, if (k % 2 == 0) 53_960.0 else 54_040.0) }
        mid.forEachIndexed { k, x -> add(x, if (k < 6) 11 else 12, 15, if (k % 2 == 0) "BankNifty 5-min" else "BankNifty 15-min", if (k % 3 == 0) "PE" else "CE", null, null) }
        return out.sortedBy { it.trade.exitTime }
    }

    @Test fun theAnswerSaysWhatStandsOutTheCutsAndTheResearchAndNeverAdvises() {
        val rows = book()
        val a = LiquidityInsight.answer(Focus.PATTERNS, rows)
        val lines = a.lines()
        assertTrue(lines[0].startsWith("Boss, Liquidity 15+5's patterns - its 24 closed paper trades ("), lines[0])
        assertTrue(lines[0].contains(" won (") && lines[0].endsWith("a lot a trade after charges."), lines[0])
        assertTrue(a.contains("Stands out on the good side: entries 09:20-10:30"), a)
        assertTrue(a.contains("Stands out on the weak side: entries 13:30-14:00"), a)
        assertTrue(lines.any { it.startsWith("By entry time: 09:20-10:30 6 trades, 100% won, +Rs 1,025;") }, a)
        assertTrue(lines.any { it.startsWith("By index and book: ") }, a)
        assertTrue(lines.any { it.startsWith("By index and book: ") && it.endsWith("(a lot a trade; 2 smaller groups under 5 trades left out).") }, a)
        assertTrue(lines.any { it.startsWith("By side: CE ") }, a)
        assertTrue(lines.any { it.startsWith("By day of the week: Monday ") }, a)
        assertTrue(lines.any { it.startsWith("By room to the next level at entry: ") }, a)
        // The research beside it, said plainly: the late entries point the same way as the last-entry study.
        assertTrue(a.contains("entries after 14:00 lost in both BankNifty years (74 trades, -Rs 10,652 a lot in all"), a)
        assertTrue(a.contains("the 13:30-14:00 entries do worse than the rest beyond noise - the same direction as that finding"), a)
        assertTrue(a.contains("The research pins no split by book, side or day of the week"), a)
        assertTrue(a.contains("Against the backtest (+Rs 228 a lot a trade, 41% won):"), a)
        assertTrue(a.endsWith(LiquidityInsight.END), a)
        assertFalse(ADVICE.containsMatchIn(a), ADVICE.find(a)?.value)
        // What is working leads with the good side; where it loses with the weak side and the losses' exits.
        val w = LiquidityInsight.answer(Focus.WORKING, rows)
        assertTrue(w.lines()[0].startsWith("Boss, what's working for Liquidity 15+5 - its 24 closed paper trades"), w)
        assertTrue(w.lines()[1].startsWith("Stands out on the good side:"), w)
        val l = LiquidityInsight.answer(Focus.LOSING, rows)
        assertTrue(l.lines()[0].startsWith("Boss, where Liquidity 15+5 loses - "), l)
        assertTrue(l.lines()[1].startsWith("Stands out on the weak side: entries 13:30-14:00"), l)
        assertTrue(l.lines().any { it.startsWith("Its 11 losses ended: 11 index stop (30% in the research).") }, l)
        for (x in listOf(w, l)) assertFalse(ADVICE.containsMatchIn(x), ADVICE.find(x)?.value)
    }

    @Test fun theResearchLinesSayAgreeDisagreeOrCannotTell() {
        // The latest entries doing better: against the direction of the late-entry finding.
        val better = at(13, 45, listOf(1000.0, 1200.0, 1100.0, 900.0, 1300.0)) + at(10, 0, listOf(-200.0, 0.0, -100.0, 100.0, -300.0), 5)
        assertTrue(LiquidityInsight.timeResearchLine(LiquidityInsight.read(better)).endsWith("against the direction of that finding."))
        // Too few late entries to tell.
        val few = at(10, 0, List(10) { it * 10.0 })
        assertTrue(LiquidityInsight.timeResearchLine(LiquidityInsight.read(few)).endsWith("too few 13:30-14:00 entries (0) to tell whether that weakness reaches back before 14:00."))
        // Room: not recorded; tight room worse (agrees); within noise.
        assertTrue(LiquidityInsight.roomResearchLine(LiquidityInsight.read(few)).endsWith("The room is not recorded on these trades, so the paper record cannot speak to it."))
        val tight = (0 until 5).map { row(-500.0 - it * 10, level = 54_000.0, target = 54_040.0, seq = it) } +
            (0 until 5).map { row(800.0 + it * 10, level = 54_000.0, target = 54_200.0, seq = 5 + it) }
        val r = LiquidityInsight.roomResearchLine(LiquidityInsight.read(tight))
        assertTrue(r.startsWith("The research found breaks with under 1 index stop of room to the next level did worse, so the arm skips them."), r)
        assertTrue(r.endsWith("room under 2 stops does worse than the rest beyond noise - the same direction as the research."), r)
        // The backtest: too few under 20, else inside or outside its band.
        assertTrue(LiquidityInsight.answer(Focus.PATTERNS, few).contains("10 trades are too few to judge (it takes 20)."))
        val inLine = (0 until 20).map { row(if (it % 2 == 0) 2300.0 else -1850.0, seq = it, day = MON.plusDays((it % 5).toLong())) }
        assertTrue(LiquidityInsight.answer(Focus.PATTERNS, inLine).contains("is within what it allows for 20 trades") &&
            LiquidityInsight.answer(Focus.PATTERNS, inLine).contains("- it agrees."))
        val below = (0 until 20).map { row(-1500.0 + (it % 3) * 10, seq = it, day = MON.plusDays((it % 5).toLong())) }
        assertTrue(LiquidityInsight.answer(Focus.PATTERNS, below).contains("is below what it allows for 20 trades"))
    }

    @Test fun tooFewTradesAreSaidPlainly() {
        val none = LiquidityInsight.answer(Focus.WORKING, emptyList())
        assertTrue(none.startsWith("Liquidity 15+5 has no closed paper trade in its book yet, Boss"), none)
        assertTrue(none.endsWith(LiquidityInsight.END))
        val nine = LiquidityInsight.answer(Focus.LOSING, at(10, 0, List(9) { 100.0 * it - 300 }))
        assertTrue(nine.startsWith("Liquidity 15+5 has only 9 closed paper trades so far, Boss - too few to cut into patterns"), nine)
        assertTrue(nine.contains("No cut stands out yet - too few trades."), nine)
        assertTrue(LiquidityInsight.answer(Focus.PATTERNS, at(10, 0, listOf(1.0))).contains("only 1 closed paper trade so far"))
        // Ten trades all in one group of each cut: the cuts that have no second group are named as too few.
        val ten = LiquidityInsight.answer(Focus.PATTERNS, (0 until 10).map { row(it * 50.0 - 200, seq = it) })
        assertTrue(ten.contains(LiquidityInsight.NONE), ten)
        assertTrue(ten.contains("Too few trades yet to cut by room to the next level at entry (each group needs 5)."), ten)
    }

    // ---- the question --------------------------------------------------------------------------------------------------

    @Test fun theQuestionAndWhatItNeverTakes() {
        for ((s, f) in listOf("what's working for liquidity" to Focus.WORKING, "what is working for liquidity" to Focus.WORKING,
            "when does liquidity win" to Focus.WORKING, "liquidity kahan jeetta hai" to Focus.WORKING, "where does liquidity make money" to Focus.WORKING,
            "where does liquidity lose" to Focus.LOSING, "where is liquidity losing" to Focus.LOSING, "what's not working for liquidity" to Focus.LOSING,
            "liquidity kahan loss karta hai" to Focus.LOSING, "liquidity kab loss karta hai" to Focus.LOSING, "liquidity weak spots" to Focus.LOSING,
            "liquidity patterns" to Focus.PATTERNS, "liquidity insights" to Focus.PATTERNS, "liquidity ka pattern kya hai" to Focus.PATTERNS,
            "what works and what doesn't for liquidity" to Focus.PATTERNS))
            assertEquals(f, LiquidityInsight.asked(s), s)
        for (s in listOf("what's working", "where do i lose", "nifty patterns", "where does hero lose", "what's working for solo",
            "should liquidity stop trading in the afternoon", "change liquidity's rules", "set liquidity to 2 lots", "switch off liquidity",
            "what's working for liquidity today", "liquidity backtest patterns", "liquidity shadow patterns", "liquidity levels",
            "why did liquidity lose", "what is a liquidity pattern", "how did liquidity do this week", "liquidity win rate",
            "which index works best for liquidity", "what's working for liquidity and what is my pnl", "liquidity patterns kar do"))
            assertNull(LiquidityInsight.asked(s), s)
    }

    // ---- the weekly note -----------------------------------------------------------------------------------------------

    @Test fun theWeeklyNoteIsSaturdayMorningOnceWithTenNewTrades() {
        val sat = LocalDate.of(2026, 10, 10)
        assertTrue(LiquidityInsight.due(sat.atTime(9, 0), null))
        assertTrue(LiquidityInsight.due(sat.atTime(8, 30), sat.minusDays(7)))
        assertFalse(LiquidityInsight.due(sat.atTime(8, 29), null))
        assertFalse(LiquidityInsight.due(sat.atTime(12, 0), null))
        assertFalse(LiquidityInsight.due(sat.atTime(10, 0), sat))                     // once that Saturday
        assertFalse(LiquidityInsight.due(sat.minusDays(1).atTime(10, 0), null))       // a Friday
        assertFalse(LiquidityInsight.due(sat.plusDays(1).atTime(10, 0), null))        // a Sunday

        val rows = book()
        assertEquals(24, LiquidityInsight.newSince(rows, null))
        val mark = rows[13].trade.exitTime
        assertEquals(10, LiquidityInsight.newSince(rows, mark))
        assertEquals(9, LiquidityInsight.newSince(rows, rows[14].trade.exitTime))
        assertEquals(rows.last().trade.exitTime, LiquidityInsight.markOf(rows))
        assertNull(LiquidityInsight.markOf(emptyList()))
        // Under ten new: nothing said.
        assertNull(LiquidityInsight.weekly(rows, rows[14].trade.exitTime))
        assertNull(LiquidityInsight.weekly(at(10, 0, List(9) { 100.0 }), null))
        // Ten new: the note.
        val n = LiquidityInsight.weekly(rows, mark)
        assertNotNull(n)
        assertTrue(n.startsWith("What's working - Liquidity 15+5's week in patterns, Boss: 24 closed paper trades, 10 new since the last look;"), n)
        assertTrue(n.contains("Stands out on the good side:") && n.contains("Stands out on the weak side:"), n)
        assertTrue(n.contains("Ask \"liquidity patterns\" for every cut."), n)
        assertTrue(n.endsWith(LiquidityInsight.END), n)
        assertFalse(ADVICE.containsMatchIn(n), ADVICE.find(n)?.value)
        // Its keys are the app's.
        assertEquals("jarvis.liqinsight.done", LiquidityInsight.KEY_DONE)
        assertEquals("jarvis.liqinsight.mark", LiquidityInsight.KEY_MARK)
    }
}
