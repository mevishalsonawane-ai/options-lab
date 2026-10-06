package com.optionslab.ira

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.LiquidityRules.Zone
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The opening read ([OpeningRead]): when it is due, the gap and the first candle from the 1-minute candles, what GIFT Nifty
 * pointed to (only the morning's reading), the open against Liquidity 15+5's levels (and what the gap took), the arm's line,
 * the whole note (never past six lines; the brief form two), the states with nothing to read, and the question.
 */
class OpeningReadTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 6)                          // a Tuesday
    private val prev: LocalDate = LocalDate.of(2026, 10, 5)
    private fun at(h: Int, m: Int, d: LocalDate = day): LocalDateTime = d.atTime(h, m)

    /** The weekdays before [day], oldest first. */
    private fun pastDays(n: Int): List<LocalDate> =
        generateSequence(day.minusDays(1)) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(n).toList().reversed()

    /**
     * One session's 1-minute candles: the opening five (09:15-09:19) from [open], ranging [range] points, and - unless
     * [today] - a last minute (15:29) closing at [close].
     */
    private fun session(d: LocalDate, open: Double, range: Double, close: Double, today: Boolean = false, upTo: Int = 4): List<Candle> {
        val ones = (0..upTo).map { k ->
            val o = open + k
            Candle(at(9, 15 + k, d), o, if (k == 2) open + range / 2 else o + 1, if (k == 3) open - range / 2 else o - 1, o + 1)
        }
        return if (today) ones else ones + Candle(at(15, 29, d), close, close + 1, close - 1, close)
    }

    /** [days] earlier sessions, each opening candle ranging [usual], the last closing at [prevClose]; then today's opening. */
    private fun bars(prevClose: Double, open: Double, firstRange: Double, usual: Double, days: Int = 6, upTo: Int = 4): List<Candle> {
        val past = pastDays(days)
        return past.flatMap { d -> session(d, prevClose - 30, usual, if (d == past.last()) prevClose else prevClose - 20) } +
            session(day, open, firstRange, 0.0, today = true, upTo = upTo)
    }

    // ---- when -----------------------------------------------------------------------------------------------------

    @Test fun dueJustAfterTheFirstCandleOnceATradingDay() {
        assertFalse(OpeningRead.due(at(9, 19), true, null))
        assertTrue(OpeningRead.due(at(9, 20), true, null))
        assertTrue(OpeningRead.due(day.atTime(9, 25, 59), true, null))
        assertFalse(OpeningRead.due(at(9, 26), true, null))
        assertFalse(OpeningRead.due(at(9, 21), false, null))                          // a holiday or a weekend
        assertFalse(OpeningRead.due(at(9, 21), true, day))                            // already put today
        assertTrue(OpeningRead.due(at(9, 21), true, prev))
    }

    // ---- the candles ----------------------------------------------------------------------------------------------

    @Test fun theGapAgainstThePreviousClose() {
        val b = bars(25_000.0, 25_062.0, 48.0, 40.0)
        val g = OpeningRead.gap(Market.NIFTY, b, day)!!
        assertEquals(prev, g.prevDay); assertEquals(25_000.0, g.prevClose); assertEquals(25_062.0, g.open)
        assertEquals(62.0, g.points, 1e-9); assertEquals(0.248, g.pct, 1e-9)
        assertEquals("Nifty +62 pts (+0.25%)", OpeningRead.gapWords(g, null))
        assertEquals("Nifty -1,234 pts (-4.94%; GIFT Nifty pointed to -1,100)", OpeningRead.gapWords(g.copy(open = 23_766.0), -1_100.0))
        // No earlier session, or no 09:15 minute today: no gap.
        assertNull(OpeningRead.gap(Market.NIFTY, session(day, 25_062.0, 48.0, 0.0, today = true), day))
        assertNull(OpeningRead.gap(Market.NIFTY, b.filter { it.t != at(9, 15) }, day))
        assertNull(OpeningRead.gap(Market.NIFTY, b.filter { it.t.toLocalDate() != day }, day))
    }

    @Test fun theOpeningIsThereOnlyWithItsFirstMinuteAndOnceClosedItsLast() {
        val b = bars(25_000.0, 25_062.0, 48.0, 40.0)
        assertTrue(OpeningRead.hasOpening(b, day, at(9, 21)))
        assertFalse(OpeningRead.hasOpening(b.filter { it.t != at(9, 19) }, day, at(9, 21)))   // the feed is a minute behind
        assertTrue(OpeningRead.hasOpening(b.filter { it.t != at(9, 19) }, day, at(9, 18)))
        assertFalse(OpeningRead.hasOpening(b.filter { it.t != at(9, 15) }, day, at(9, 21)))
        assertFalse(OpeningRead.hasOpening(b.filter { it.t.toLocalDate() == day }, day, at(9, 21)))   // nothing to set it against
        assertFalse(OpeningRead.hasOpening(b.filter { it.t.toLocalDate() != day }, day, at(9, 21)))
    }

    @Test fun theFirstCandleAgainstTheUsualOpeningCandle() {
        val f = OpeningRead.first(Market.NIFTY, bars(25_000.0, 25_062.0, 48.0, 40.0), at(9, 21))!!
        assertEquals(48.0, f.range, 1e-9); assertEquals(40.0, f.usual!!, 1e-9); assertEquals(6, f.days)
        // Not yet closed, or its last minute missing: not read.
        assertNull(OpeningRead.first(Market.NIFTY, bars(25_000.0, 25_062.0, 48.0, 40.0), at(9, 19)))
        assertNull(OpeningRead.first(Market.NIFTY, bars(25_000.0, 25_062.0, 48.0, 40.0, upTo = 3), at(9, 21)))
        // Fewer than five earlier sessions: the usual is not known (and the line leaves the index out).
        val few = OpeningRead.first(Market.NIFTY, bars(25_000.0, 25_062.0, 48.0, 40.0, days = 4), at(9, 21))!!
        assertNull(few.usual); assertEquals(4, few.days)
        // Only the last twenty sessions count.
        val many = OpeningRead.first(Market.NIFTY, bars(25_000.0, 25_062.0, 48.0, 40.0, days = 25), at(9, 21))!!
        assertEquals(OpeningRead.USUAL_DAYS, many.days)
        val idx = OpeningRead.index(Market.NIFTY, bars(25_000.0, 25_062.0, 48.0, 40.0), at(9, 21))
        assertNotNull(idx.gap); assertNotNull(idx.first)
    }

    // ---- GIFT Nifty -----------------------------------------------------------------------------------------------

    @Test fun giftNiftyOnlyFromTheMorningsReading() {
        val g = OpeningRead.gap(Market.NIFTY, bars(25_000.0, 25_062.0, 48.0, 40.0), day)!!
        val now = at(9, 21)
        assertEquals(55.0, OpeningRead.giftPointed(OpeningRead.Gift(25_055.0, at(8, 45)), g, now)!!, 1e-9)
        assertEquals(-20.0, OpeningRead.giftPointed(OpeningRead.Gift(24_980.0, at(23, 30, prev)), g, now)!!, 1e-9)   // last night's
        assertNull(OpeningRead.giftPointed(null, g, now))
        assertNull(OpeningRead.giftPointed(OpeningRead.Gift(25_055.0, at(8, 45)), null, now))
        assertNull(OpeningRead.giftPointed(OpeningRead.Gift(25_055.0, at(14, 0, prev)), g, now))       // before the close it is set against
        assertNull(OpeningRead.giftPointed(OpeningRead.Gift(25_055.0, at(9, 16)), g, now))             // in market hours: a level, not a gap
        assertNull(OpeningRead.giftPointed(OpeningRead.Gift(25_055.0, at(9, 30)), g, now))             // after now
        val friday = OpeningRead.Gap(Market.NIFTY, LocalDate.of(2026, 10, 2), 25_000.0, 25_062.0)
        assertNull(OpeningRead.giftPointed(OpeningRead.Gift(25_055.0, at(15, 40, LocalDate.of(2026, 10, 2))), friday,
            at(9, 21, LocalDate.of(2026, 10, 5))))                                                       // too old (18 h)
    }

    // ---- the levels -----------------------------------------------------------------------------------------------

    private val closed: List<Bar> = (0 until 50).map { Bar(at(9, 15, prev).plusMinutes(5L * it), 54_100.0, 54_130.0, 54_090.0, 54_110.0) }

    private fun z(kind: String, side: Int, top: Double, bottom: Double, known: Int = 10, broken: Int = -1) =
        Zone(kind, side, top, bottom, known - 1, known).also { it.broken = broken }

    private val zones = listOf(
        z("swing", 1, 54_180.0, 54_130.0), z("pool", 1, 54_180.0, 54_165.0), z("pool", 1, 54_050.0, 54_040.0), z("pool", 1, 54_300.0, 54_290.0),
        z("swing", -1, 53_960.0, 53_900.0), z("pool", -1, 53_990.0, 53_980.0),
        // Taken, or not known yet at the last bar: never read.
        z("pool", 1, 54_150.0, 54_140.0, broken = 30), z("swing", 1, 54_140.0, 54_120.0, known = 60),
    )

    @Test fun theUntakenLevelsOfABookNamedAsTheMapNamesThem() {
        val ls = OpeningRead.levelsOf(closed, zones, "BANKNIFTY", 5)
        // The swing high and the pool on it share 54,180: each kept under its own name.
        assertEquals(listOf(54_180.0, 54_180.0, 54_050.0, 54_300.0, 53_900.0, 53_980.0), ls.map { it.edge })
        assertEquals(listOf("swing high", "pool on a swing high"), ls.filter { it.edge == 54_180.0 }.map { it.name })
        assertEquals("pool", ls.first { it.edge == 54_050.0 }.name)
        assertEquals("swing low", ls.first { it.edge == 53_900.0 }.name)
        assertTrue(ls.none { it.edge == 54_150.0 || it.edge == 54_140.0 })
        assertTrue(ls.all { it.underlying == "BANKNIFTY" && it.minutes == 5 })
    }

    /** Three sessions of 1-minute bars on a wavy series (as Liquidity's map is tested), today's first minutes last. */
    private fun ones(): List<Bar> {
        val days = listOf(day.minusDays(3), day.minusDays(2), prev)
        val n = days.size * 375
        val c = (0 until n).map { 54_000 + 120 * sin(it / 45.0) + 60 * sin(it / 17.0) + 20 * sin(it / 6.0) }
        val past = (0 until n).map {
            val o = if (it == 0) c[0] else c[it - 1]
            Bar(days[it / 375].atTime(9, 15).plusMinutes((it % 375).toLong()), o, maxOf(o, c[it]) + 4 + 6 * abs(sin(it / 2.3)),
                minOf(o, c[it]) - 4 - 6 * abs(cos(it / 2.9)), c[it])
        }
        return past + (0 until 6).map { Bar(at(9, 15 + it), 55_000.0, 55_010.0, 54_990.0, 55_000.0) }
    }

    @Test fun theLevelsAsTheyStoodAtTheOpen() {
        val all = ones()
        val ls = OpeningRead.levelsAtOpen(all, "BANKNIFTY", 15, day)!!
        assertTrue(ls.isNotEmpty())
        // Today's minutes change nothing: the books' closed bars up to the previous close, as the arm folds them.
        assertEquals(ls, OpeningRead.levelsAtOpen(all.filter { it.start.toLocalDate() != day }, "BANKNIFTY", 15, day))
        val bars15 = com.optionslab.engine.orb.LiquidityOverlay.closedBars(all.filter { it.start.isBefore(at(9, 15)) }, 15, at(9, 15), inputMinutes = 1)
        assertEquals(OpeningRead.levelsOf(bars15, LiquidityRules.zones(bars15), "BANKNIFTY", 15), ls)
        // Too few closed bars: not read.
        assertNull(OpeningRead.levelsAtOpen(all.takeLast(200), "BANKNIFTY", 15, day))
    }

    private fun lvl(side: Int, edge: Double, minutes: Int = 5, name: String = if (side > 0) "swing high" else "swing low", und: String = "BANKNIFTY") =
        OpeningRead.Lvl(und, minutes, side, edge, name)

    private val bnLevels = listOf(
        lvl(1, 54_180.0, 15, "pool on a swing high"), lvl(1, 54_180.0, 5), lvl(1, 54_050.0, 5, "pool"), lvl(1, 54_080.0, 15),
        lvl(-1, 53_900.0, 5), lvl(-1, 53_800.0, 15), lvl(1, 26_100.0, 30, und = "FINNIFTY"),
    )

    @Test fun whereTheOpenSitsAndWhatTheGapTook() {
        val p = OpeningRead.place("BANKNIFTY", 54_120.0, 54_000.0, bnLevels)
        assertEquals(54_180.0, p.above!!.edge); assertEquals(5, p.above!!.minutes)                   // a tie: the smaller book first
        assertEquals(53_900.0, p.below!!.edge)
        assertEquals(listOf(54_050.0, 54_080.0), p.took.map { it.edge })
        assertEquals("BankNifty opened 54,120: above 54,180 (swing high, 5-min) 60 pts away; below 53,900 (swing low, 5-min) 220 pts away; " +
            "the gap took 54,050 (pool, 5-min) and 54,080 (swing high, 15-min) before the first candle.", OpeningRead.placeLine(p))
        // A gap down takes the lows between the close and the open.
        val down = OpeningRead.place("BANKNIFTY", 53_850.0, 54_000.0, bnLevels)
        assertEquals(listOf(53_900.0), down.took.map { it.edge }); assertEquals(53_800.0, down.below!!.edge); assertEquals(54_050.0, down.above!!.edge)
        // A gap that took nothing, no gap, no previous close; nothing on a side.
        assertTrue(OpeningRead.placeLine(OpeningRead.place("BANKNIFTY", 54_020.0, 54_000.0, bnLevels)).endsWith("; the gap took no level."))
        assertTrue(OpeningRead.placeLine(OpeningRead.place("BANKNIFTY", 54_000.0, 54_000.0, bnLevels)).endsWith("below 53,900 (swing low, 5-min) 100 pts away."))
        assertTrue(OpeningRead.place("BANKNIFTY", 54_120.0, null, bnLevels).took.isEmpty())
        assertEquals("FinNifty opened 26,000: above 26,100 (swing high, 30-min) 100 pts away; nothing untaken below; the gap took no level.",
            OpeningRead.placeLine(OpeningRead.place("FINNIFTY", 26_000.0, 25_990.0, bnLevels)))
        // More than two taken: two named, the rest counted.
        val many = OpeningRead.place("BANKNIFTY", 54_200.0, 54_000.0, bnLevels)
        assertTrue(OpeningRead.placeLine(many).contains("the gap took 54,050 (pool, 5-min) and 54,080 (swing high, 15-min) and 1 more before the first candle."), OpeningRead.placeLine(many))
    }

    // ---- the arm --------------------------------------------------------------------------------------------------

    private fun read(und: String, minutes: Int, price: Double, up: Double?, down: Double?): LiquidityMap.Read {
        fun side(s: Int, edge: Double?): LiquidityMap.Side {
            if (edge == null) return LiquidityMap.Side(s, null, null, null, null, false)
            val l = LiquidityMap.Level("pool", s, edge, true)
            return LiquidityMap.Side(s, l, l, null, null, true)
        }
        return LiquidityMap.Read(und, minutes, LiquidityMap.State.OK, price, at(9, 20), at(9, 15), side(1, up), side(-1, down), entryOpen = true, bars = 60)
    }

    @Test fun theArmsLineWithItsFirstTrigger() {
        val trigger = LiquidityWhyNot.nearestWords(listOf(read("BANKNIFTY", 5, 54_120.0, 54_180.0, 53_900.0), read("FINNIFTY", 5, 26_000.0, 26_100.0, 25_960.0)))
        assertEquals("FinNifty 5-min, a close below 25,960 - 40 pts away (it would buy a put)", trigger)
        assertNull(LiquidityWhyNot.nearestWords(emptyList()))
        assertEquals("Liquidity 15+5: armed, 2 lots a trade - first trigger: $trigger.", OpeningRead.armLine(OpeningRead.Arm(true, 2, trigger)))
        assertEquals("Liquidity 15+5: switched off (1 lot a trade when on) - were it on, its first trigger: $trigger.",
            OpeningRead.armLine(OpeningRead.Arm(false, 1, trigger)))
        assertEquals("Liquidity 15+5: switch not read - no break with room ahead just now.", OpeningRead.armLine(OpeningRead.Arm(null, null, null)))
        assertEquals("Liquidity 15+5: armed - its levels not read just now.", OpeningRead.armLine(OpeningRead.Arm(true, null, null, levelsRead = false)))
    }

    // ---- the note -------------------------------------------------------------------------------------------------

    private val now = at(9, 21)

    private fun facts(t: LocalDateTime = now) = OpeningRead.Facts(
        now = t,
        indices = listOf(
            OpeningRead.index(Market.NIFTY, bars(25_000.0, 25_062.0, 48.0, 40.0), t),
            OpeningRead.index(Market.BANKNIFTY, bars(54_000.0, 54_120.0, 160.0, 145.0), t),
            OpeningRead.index(Market.FINNIFTY, bars(26_000.0, 25_990.0, 40.0, 50.0, days = 3), t),
        ),
        gift = OpeningRead.Gift(25_055.0, at(8, 45)),
        places = listOf(OpeningRead.place("BANKNIFTY", 54_120.0, 54_000.0, bnLevels), OpeningRead.place("FINNIFTY", 25_990.0, 26_000.0, bnLevels)),
        arm = OpeningRead.Arm(true, 2, "BankNifty 5-min, a close above 54,180 - 60 pts away (it would buy a call)"),
        bigMove = BigMoveRisk.Read(Market.NIFTY, BigMoveRisk.Level.HIGH, 2.5, listOf("it's the first 15 minutes")),
    )

    @Test fun theWholeNoteInSixLinesAtMost() {
        val lines = OpeningRead.lines(facts())
        assertEquals(listOf(
            "Opening read, Boss (Tue 6 Oct) - the open against yesterday's close: Nifty +62 pts (+0.25%; GIFT Nifty pointed to +55), BankNifty +120 pts (+0.22%), FinNifty -10 pts (-0.04%).",
            "BankNifty opened 54,120: above 54,180 (swing high, 5-min) 60 pts away; below 53,900 (swing low, 5-min) 220 pts away; " +
                "the gap took 54,050 (pool, 5-min) and 54,080 (swing high, 15-min) before the first candle.",
            "FinNifty opened 25,990: above 26,100 (swing high, 30-min) 110 pts away; nothing untaken below; the gap took no level.",
            "First 5-minute candle's range: Nifty 48 pts (1.2x its usual 40), BankNifty 160 pts (1.1x its usual 145).",
            "Liquidity 15+5: armed, 2 lots a trade - first trigger: BankNifty 5-min, a close above 54,180 - 60 pts away (it would buy a call).",
            "Big-move risk for Nifty now: high, about 2.5x the usual - direction can't be told from this.",
        ), lines)
        assertTrue(lines.size <= OpeningRead.MAX_LINES)
        assertEquals(lines.joinToString("\n"), OpeningRead.say(facts()))
        // Facts only: no advice, nothing done.
        val text = OpeningRead.say(facts()).lowercase()
        for (w in listOf("you should", "consider", "buy now", "sell now", "i'd ", "i would", "recommend", "suggest")) assertFalse(text.contains(w), w)
        // The brief form (saving battery, short answers): the first two lines.
        assertEquals(lines.take(2), OpeningRead.lines(facts(), brief = true))
    }

    @Test fun eachLineOnlyWhenItsDataIsThere() {
        val bare = OpeningRead.Facts(now = now, indices = listOf(OpeningRead.Index(Market.NIFTY, OpeningRead.Gap(Market.NIFTY, prev, 25_000.0, 25_010.0), null)))
        assertEquals(listOf("Opening read, Boss (Tue 6 Oct) - the open against yesterday's close: Nifty +10 pts (+0.04%)."), OpeningRead.lines(bare))
        // An unknown big-move read is no line; nor is a GIFT reading from market hours.
        assertNull(OpeningRead.bigMoveLine(BigMoveRisk.Read(Market.NIFTY, null, null, emptyList(), unknown = "too few")))
        assertNull(OpeningRead.bigMoveLine(null))
        assertFalse(OpeningRead.say(facts().copy(gift = OpeningRead.Gift(25_055.0, at(9, 16)))).contains("GIFT"))
        // After a weekend: against Friday's close, by name.
        val monday = OpeningRead.Facts(now = at(9, 21, prev), indices = listOf(OpeningRead.Index(Market.NIFTY,
            OpeningRead.Gap(Market.NIFTY, LocalDate.of(2026, 10, 2), 25_000.0, 24_950.0), null)))
        assertEquals("Opening read, Boss (Mon 5 Oct) - the open against Fri 2 Oct's close: Nifty -50 pts (-0.20%).", OpeningRead.lines(monday).single())
    }

    @Test fun theStatesWithNothingToRead() {
        assertEquals(listOf(OpeningRead.CLOSED), OpeningRead.lines(facts().copy(tradingDay = false)))
        assertEquals(listOf(OpeningRead.NOT_YET), OpeningRead.lines(facts(at(9, 5))))
        assertEquals(listOf(OpeningRead.NO_CANDLES), OpeningRead.lines(OpeningRead.Facts(now = now)))
        assertFalse(OpeningRead.ready(OpeningRead.Facts(now = now, indices = listOf(OpeningRead.Index(Market.NIFTY, null, null)))))
        assertTrue(OpeningRead.ready(facts()))
        // Asked between 09:15 and 09:20: the gaps and levels, and the first candle said as not yet closed.
        val early = OpeningRead.lines(facts(at(9, 17)))
        assertTrue(early.contains("First 5-minute candle: it closes at 09:20."), early.toString())
        assertTrue(early.size <= OpeningRead.MAX_LINES)
    }

    // ---- asked ----------------------------------------------------------------------------------------------------

    @Test fun theQuestion() {
        for (s in listOf("how did the market open", "How did the market open?", "how did the market open today", "how did we open", "how has the market opened",
            "how did the market open vs the levels", "where did we open vs the liquidity levels", "where did we open", "where did the market open",
            "how was the open", "how was today's opening", "how did the open go", "opening read", "the opening read", "give me the opening read",
            "what's the opening read", "opening summary", "opening report", "market kaisa khula", "aaj market kaisa khula", "market kaise khula aaj",
            "market kahan khula", "open kaisa tha", "aaj ki opening kaisi rahi", "jarvis how did the market open", "how did the market open boss"))
            assertTrue(OpeningRead.asked(s), s)
        for (s in listOf("how did nifty open", "how did banknifty open", "nifty kaisa khula", "how did the market open yesterday", "how did we open last week",
            "how often does the market open flat", "will the market open higher", "when does the market open", "what time does the market open",
            "is the market open", "did the market open", "opening range", "gap plan", "opening gap", "open a trade", "how did my bots do at the open",
            "how did the market open on friday", "orb", "how did the market close", "kal market kaisa khulega", "what's the plan for tomorrow"))
            assertFalse(OpeningRead.asked(s), s)
    }
}
