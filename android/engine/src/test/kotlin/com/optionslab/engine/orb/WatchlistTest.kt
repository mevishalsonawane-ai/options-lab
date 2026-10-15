package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shadow Watchlist ([Watchlist]): its written selection rule and labels, C4's PCR rank, R4-8's Gann swing against the
 * research's own code (android/tools/passed_arms_fixture.py: h37 `bars` and r4 `gann_swing`), H45-1 on the Liquidity levels,
 * the would-be P&L with the app's paper cost model (no fill, open, stop, target, time, the expiry-day square-off), the
 * random-minute twins, the scorecard and its words.
 */
class WatchlistTest {
    @Suppress("UNCHECKED_CAST")
    private val fx: Map<String, Any?> by lazy {
        MiniJson.parse(javaClass.getResourceAsStream("/passedarms/parity.json")!!.bufferedReader().readText()) as Map<String, Any?>
    }
    private fun arr(x: Any?): DoubleArray = (x as List<*>).map { (it as Double?) ?: Double.NaN }.toDoubleArray()
    @Suppress("UNCHECKED_CAST")
    private fun days(): List<PassedArms.DayBars> = (fx["days"] as List<Map<String, Any?>>).mapIndexed { i, d ->
        PassedArms.DayBars(LocalDate.of(2026, 9, 1).plusDays(i.toLong()), arr(d["o"]), arr(d["h"]), arr(d["l"]), arr(d["c"]))
    }

    @Test fun theSelectionRuleAndTheLabelsAreWrittenDown() {
        assertEquals("shadow, not traded; information only, not a trade signal", Watchlist.NOTE)
        assertTrue(Watchlist.SELECTION.contains("net > 0") && Watchlist.SELECTION.contains("p < 0.10") && Watchlist.SELECTION.contains("0.10 gate"))
        assertTrue(Watchlist.LEFT_OUT.contains("M4") && Watchlist.LEFT_OUT.contains("MA2"))
        assertEquals(listOf("r47_c4", "r52_r4_8", "r52_h45_1"), Watchlist.Rule.entries.map { it.key })
        // C4 reads the option chain: not the default, and labelled so; the others read the index.
        assertTrue(Watchlist.Rule.C4.nonDefault && Watchlist.Rule.C4.tag.startsWith("NON-DEFAULT"))
        assertTrue(Watchlist.Rule.R4_8.tag == "signal on the index" && !Watchlist.Rule.H45_1.nonDefault)
        assertEquals(Watchlist.Rule.H45_1, Watchlist.Rule.of("r52_h45_1")); assertNull(Watchlist.Rule.of("x"))
        assertEquals(PassedArms.PremiumExits(0.30, null, null, listOf(0.60 to 0.30), LocalTime.of(15, 15)), Watchlist.Rule.C4.exits)
        assertEquals(PassedArms.PremiumExits(0.10, 0.10, 30, emptyList(), LocalTime.of(15, 10)), Watchlist.Rule.H45_1.exits)
        assertEquals(30, Watchlist.Rule.R4_8.exits.tmaxMinutes)
    }

    // ---- C4 ------------------------------------------------------------------------------------------------------------------

    @Test fun c4RanksTheTenFifteenPcrAmongThePrevious60Sessions() {
        val hist = (1..60).map { it / 10.0 }                     // 0.1 .. 6.0
        assertEquals(1.0, Watchlist.prevRank(9.0, hist))
        assertEquals(0.0, Watchlist.prevRank(0.05, hist))
        assertEquals(0.5, Watchlist.prevRank(3.05, hist))
        // Fewer than 40 finite sessions: no rank; only the last 60 count.
        assertNull(Watchlist.prevRank(1.0, List(39) { 1.0 } + List(30) { Double.NaN }))
        assertEquals(1.0, Watchlist.prevRank(2.0, List(100) { 5.0 } + List(60) { 1.0 }))
        assertNull(Watchlist.prevRank(Double.NaN, hist))
        assertEquals(1, Watchlist.c4Side(0.8)); assertEquals(-1, Watchlist.c4Side(0.2))
        assertEquals(0, Watchlist.c4Side(0.5)); assertEquals(0, Watchlist.c4Side(null))
        // ATM-10..+10 of spot 53,420 (ATM 53,400): 52,400 .. 54,400; a strike outside is not counted.
        val oi = mapOf(53_400.0 to (1000L to 1500L), 52_400.0 to (100L to 200L), 54_500.0 to (9999L to 1L), 54_400.0 to (null to 300L))
        assertEquals(2000.0 / 1100.0, Watchlist.pcr(53_420.0, 100, oi)!!, 1e-12)
        assertNull(Watchlist.pcr(53_420.0, 100, mapOf(53_400.0 to (0L to 5L))))
        assertEquals(10, Watchlist.C4_STRIKES); assertEquals(3, Watchlist.C4_MONEY); assertEquals(LocalTime.of(10, 15), Watchlist.C4_AT)
    }

    // ---- R4-8 ----------------------------------------------------------------------------------------------------------------

    @Test fun h37sFifteenMinuteBarsAreTheResearchs() {
        @Suppress("UNCHECKED_CAST") val b = fx["bars15"] as Map<String, List<Double?>>
        val got = Watchlist.hbars(days(), 15)
        assertEquals(b["c"]!!.size, got.size)
        for (i in got.indices) {
            assertEquals(b["dpos"]!![i]!!.toInt(), days().indexOfFirst { it.date == got[i].date }, "bar $i day")
            assertEquals(b["col_end"]!![i]!!.toInt(), got[i].colEnd, "bar $i end")
            assertEquals(b["o"]!![i]!!, got[i].open, 1e-9); assertEquals(b["h"]!![i]!!, got[i].high, 1e-9)
            assertEquals(b["l"]!![i]!!, got[i].low, 1e-9); assertEquals(b["c"]!![i]!!, got[i].close, 1e-9)
        }
    }

    @Test fun theGannSwingBreaksAreTheResearchs() {
        val d = days()
        val b = Watchlist.hbars(d, 15)
        @Suppress("UNCHECKED_CAST") val want = (fx["gann"] as List<Map<String, Double?>>).map { Triple(it["di"]!!.toInt(), it["s"]!!.toInt(), it["side"]!!.toInt()) }
        assertTrue(want.size >= 4, "the fixture breaks both ways")
        val got = Watchlist.gannSwing(b).map { (t, side) -> Triple(d.indexOfFirst { it.date == b[t].date }, b[t].colEnd, side) }
        assertEquals(want, got)
        assertEquals(emptyList(), Watchlist.gannSwing(emptyList()))
    }

    @Test fun r48TradesOnlyTodaysBreaksInTheWindowOnceTheBarIsComplete() {
        val d = days()
        val di = 2                                                        // breaks at rows 44 and 239 that day
        val today = d[di].date
        val upTo = d.take(di + 1)
        val all = Watchlist.r48Signals("NIFTY", upTo, today)
        assertEquals(listOf(-1 to LocalTime.of(10, 0), 1 to LocalTime.of(13, 15)), all.map { it.side to it.entry.toLocalTime() })
        assertTrue(all.all { it.rule == Watchlist.Rule.R4_8 && it.book == "r4_NIFTY" })
        // At 09:59 (rows through 43) the 09:45 bar is not complete: nothing yet.
        assertEquals(emptyList(), Watchlist.r48Signals("NIFTY", d.take(di) + d[di].through(43), today))
        // Session 5 breaks on its first 15-minute bar (closing 09:30, inside the 09:20-14:45 window): bought at 09:30.
        assertEquals(listOf(-1 to LocalTime.of(9, 30)), Watchlist.r48Signals("NIFTY", d.take(6), d[5].date).map { it.side to it.entry.toLocalTime() })
        val s = all.first()
        assertEquals("PE", s.right)
        assertEquals(PassedArms.itmStrike("NIFTY", -1, Watchlist.hbars(upTo, 15).first { it.date == today && it.colEnd == 44 }.close, 1), s.strike)
        assertTrue(s.key.startsWith("r52_r4_8|r4_NIFTY|"))
    }

    /** One session from 25 fifteen-minute bars (o, h, l, c): each bar's range in its first minute, then flat at its close. */
    private fun fifteens(d: LocalDate, b: List<DoubleArray>): PassedArms.DayBars {
        val o = DoubleArray(375) { Double.NaN }; val h = o.copyOf(); val l = o.copyOf(); val c = o.copyOf()
        for ((k, x) in b.withIndex()) for (r in 15 * k until 15 * k + 15) {
            val first = r == 15 * k
            o[r] = if (first) x[0] else x[3]; h[r] = if (first) x[1] else x[3]; l[r] = if (first) x[2] else x[3]; c[r] = x[3]
        }
        return PassedArms.DayBars(d, o, h, l, c)
    }

    @Test fun r48sWindowEndsAtTwoFortyFive() {
        // Down two bars, up two (the swing bottom 97), down two (the swing top 100), quiet; then a close above 100.
        val swing = listOf(doubleArrayOf(100.0, 100.0, 99.0, 99.5), doubleArrayOf(99.5, 99.0, 98.0, 98.2), doubleArrayOf(98.2, 98.0, 97.0, 97.2),
            doubleArrayOf(97.2, 99.0, 97.5, 98.8), doubleArrayOf(98.8, 100.0, 98.5, 99.8), doubleArrayOf(99.8, 99.5, 98.0, 98.2),
            doubleArrayOf(98.2, 99.0, 97.5, 97.8))
        val quiet = doubleArrayOf(98.0, 99.0, 97.5, 98.0)
        val brk = doubleArrayOf(98.0, 101.0, 98.0, 100.5)
        val d = LocalDate.of(2026, 10, 21)
        // The break on the last bar (15:15-15:29): after 14:45, not traded.
        val late = fifteens(d, swing + List(17) { quiet } + listOf(brk))
        assertEquals(1, Watchlist.gannSwing(Watchlist.hbars(listOf(late), 15)).single().second)
        assertEquals(emptyList(), Watchlist.r48Signals("BANKNIFTY", listOf(late), d))
        // The same break on bar 10 (11:45-11:59): bought at 12:00, 1-ITM of its close.
        val early = fifteens(d, swing + List(3) { quiet } + listOf(brk) + List(14) { quiet })
        val s = Watchlist.r48Signals("BANKNIFTY", listOf(early), d).single()
        assertEquals(1, s.side); assertEquals(d.atTime(12, 0), s.entry); assertEquals(0, s.strike)
        assertEquals(5, Watchlist.R48_FROM); assertEquals(330, Watchlist.R48_TO)
    }

    // ---- H45-1 ---------------------------------------------------------------------------------------------------------------

    private val day = LocalDate.of(2026, 10, 1)

    /** LiquidityRulesTest's pool-on-a-swing-high, as 1-minute bars (each 5-minute bar: its range in the first minute, then flat). */
    private fun liquidityOnes(): List<Bar> {
        val n = 46; val j = 20
        val o = MutableList(n) { 54_000.0 }; val h = MutableList(n) { 54_010.0 }; val l = MutableList(n) { 53_990.0 }; val c = MutableList(n) { 54_000.0 }
        o[j] = 54_050.0; h[j] = 54_100.0; l[j] = 54_040.0; c[j] = 54_060.0
        o[j + 6] = 54_040.0; h[j + 6] = 54_070.0; l[j + 6] = 54_020.0; c[j + 6] = 54_030.0
        o[j + 25] = 54_020.0; h[j + 25] = 54_150.0; l[j + 25] = 54_015.0; c[j + 25] = 54_140.0
        val out = ArrayList<Bar>()
        for (k in 0 until n) {
            val t0 = day.atTime(9, 15).plusMinutes(5L * k)
            out += Bar(t0, o[k], h[k], l[k], c[k])
            for (m in 1..4) out += Bar(t0.plusMinutes(m.toLong()), c[k], c[k], c[k], c[k])
        }
        return out
    }

    @Test fun h45TakesTheLiquidityEntriesOnItsBooks() {
        val ones = liquidityOnes()
        val now = day.atTime(13, 5)
        val s = Watchlist.h45Signals("BANKNIFTY", ones, day, now).filter { it.book == "liq5_BANKNIFTY" }
        assertEquals(1, s.size)
        assertEquals(1, s[0].side); assertEquals(day.atTime(13, 5), s[0].entry); assertEquals(54_000, s[0].strike)
        assertTrue(s[0].why.contains("54100.00"))
        // A minute earlier the break bar has not closed; an index without books, or too few bars: nothing.
        assertTrue(Watchlist.h45Signals("BANKNIFTY", ones, day, day.atTime(13, 4)).none { it.book == "liq5_BANKNIFTY" })
        assertEquals(emptyList(), Watchlist.h45Signals("NIFTY", ones, day, now))
        assertEquals(emptyList(), Watchlist.h45Signals("BANKNIFTY", ones.take(100), day, now))
        // Another day's signal is not today's.
        assertTrue(Watchlist.h45Signals("BANKNIFTY", ones, day.plusDays(1), now).isEmpty())
        // Outside 09:20-14:00: not traded.
        val late = ones.map { Bar(it.start.plusMinutes(90), it.open, it.high, it.low, it.close) }
        assertTrue(Watchlist.h45Signals("BANKNIFTY", late, day, day.atTime(14, 35)).none { it.book == "liq5_BANKNIFTY" })
        assertEquals(listOf(15, 5), Watchlist.H45_BOOKS["BANKNIFTY"]); assertEquals(listOf(30, 5), Watchlist.H45_BOOKS["FINNIFTY"])
    }

    // ---- pricing -------------------------------------------------------------------------------------------------------------

    private fun sig(rule: Watchlist.Rule, at: LocalDateTime, und: String = "BANKNIFTY") = Watchlist.Signal(rule, und, "b", 1, at, 53_400, "test")
    private fun opt(from: LocalTime, n: Int, d: LocalDate = day, px: (Int) -> Double) =
        (0 until n).map { k -> val p = px(k); Bar(d.atTime(from).plusMinutes(k.toLong()), p, p + 0.5, p - 0.5, p) }

    @Test fun theWouldBePnlUsesThePaperCostModel() {
        // H45-1 at 10:00 (fill 100): +10% target touched at 10:05 (high 110.5 on 110): booked at 110, a resting limit (no spread).
        val s = sig(Watchlist.Rule.H45_1, day.atTime(10, 0))
        val bars = opt(LocalTime.of(10, 0), 10) { k -> if (k < 5) 100.0 else 110.0 }
        val p = assertIs<Watchlist.Outcome.Done>(Watchlist.price(s, bars, day.atTime(10, 20), 30)).p
        assertEquals("target", p.why)
        assertEquals(100.0, p.entryRaw); assertEquals(Watchlist.paperBuy("BANKNIFTY", 100.0), p.entryFill)
        assertEquals(100.2, p.entryFill)                                // 0.16% of 100 = 0.16, on the tick against the buy
        assertEquals(110.0, p.exitFill, 1e-9)
        assertEquals(Watchlist.charge(true, 100.2, 30) + Watchlist.charge(false, 110.0, 30), p.charges, 1e-9)
        assertEquals((110.0 - 100.2) * 30 - p.charges, p.net, 1e-9)
        assertEquals(p.net / 30, p.points, 1e-12)
        // The stop: -10% on the low, sold through the paper spread.
        val down = opt(LocalTime.of(10, 0), 10) { k -> if (k < 3) 100.0 else 89.0 }
        val q = assertIs<Watchlist.Outcome.Done>(Watchlist.price(s, down, day.atTime(10, 20), 30)).p
        assertEquals("stop", q.why); assertEquals(89.0, q.exitRaw); assertEquals(Watchlist.paperSell("BANKNIFTY", 89.0), q.exitFill)
        // Neither: out 30 minutes after the entry, at that minute's close.
        val flat = opt(LocalTime.of(10, 0), 40) { 100.0 + it * 0.01 }
        val r = assertIs<Watchlist.Outcome.Done>(Watchlist.price(s, flat, day.atTime(11, 0), 30)).p
        assertEquals("time", r.why); assertEquals(day.atTime(10, 30), r.exitAt)
    }

    @Test fun noFillOpenAndTheExpiryDaySquareOff() {
        val s = sig(Watchlist.Rule.C4, day.atTime(10, 15))
        // No traded minute within 2 minutes: no fill (once the 3 minutes have passed); before that, still open.
        assertIs<Watchlist.Outcome.NoFill>(Watchlist.price(s, opt(LocalTime.of(10, 18), 5) { 100.0 }, day.atTime(10, 30), 30))
        assertIs<Watchlist.Outcome.Open>(Watchlist.price(s, emptyList(), day.atTime(10, 16), 30))
        // The first traded minute up to 2 minutes later fills.
        val late = Watchlist.price(s, opt(LocalTime.of(10, 17), 5) { 100.0 }, day.atTime(10, 30), 30)
        assertEquals(day.atTime(10, 17), assertIs<Watchlist.Outcome.Open>(late).entryAt)
        // Held through the day: out at 15:15 - or 15:05 on its own expiry day - at that minute's open.
        val day1 = opt(LocalTime.of(10, 15), 320) { 100.0 + (it % 7) * 0.1 }
        val norm = assertIs<Watchlist.Outcome.Done>(Watchlist.price(s, day1, day.atTime(15, 20), 30)).p
        assertEquals("squareoff", norm.why); assertEquals(day1.first { it.start == day.atTime(15, 15) }.open, norm.exitRaw)
        val exp = assertIs<Watchlist.Outcome.Done>(Watchlist.price(s, day1, day.atTime(15, 20), 30, expiryDay = true)).p
        assertEquals(day1.first { it.start == day.atTime(15, 5) }.open, exp.exitRaw)
        // No bar at the square-off minute: the last close before it.
        val gap = day1.filter { it.start.isBefore(day.atTime(15, 0)) }
        val g = assertIs<Watchlist.Outcome.Done>(Watchlist.price(s, gap, day.atTime(15, 20), 30)).p
        assertEquals(gap.last().close, g.exitRaw)
        // Bars of another day are not read.
        assertIs<Watchlist.Outcome.NoFill>(Watchlist.price(s, opt(LocalTime.of(10, 15), 5, day.plusDays(1)) { 100.0 }, day.atTime(10, 30), 30))
        assertEquals(0.0, Watchlist.paperSell("BANKNIFTY", 0.0)); assertEquals(0.0, Watchlist.charge(true, 0.0, 10))
        assertEquals(0.0042, Watchlist.halfSpread("FINNIFTY")); assertEquals(0.003, Watchlist.halfSpread("XYZ"))
    }

    @Test fun twinsAreSeededAndThePAgainstRandomNeedsFortyTrades() {
        val s = sig(Watchlist.Rule.H45_1, day.atTime(10, 0))
        val bars = opt(LocalTime.of(9, 15), 360) { 100.0 + kotlin.math.sin(it / 7.0) * 8 }
        val a = Watchlist.twins(s, bars, day.atTime(15, 25), 30)
        assertEquals(Watchlist.TWINS, a.size)
        assertEquals(a, Watchlist.twins(s, bars, day.atTime(15, 25), 30))
        assertTrue(Watchlist.twins(sig(Watchlist.Rule.R4_8, day.atTime(10, 0), "NIFTY"), bars, day.atTime(15, 25), 65).isNotEmpty())
        assertTrue(Watchlist.twins(sig(Watchlist.Rule.C4, day.atTime(10, 15)), bars, day.atTime(15, 25), 30, expiryDay = true).isNotEmpty())
        assertEquals(LocalTime.of(9, 21) to LocalTime.of(14, 46), Watchlist.twinWindow(Watchlist.Rule.R4_8))
        // p: the real total against totals drawing one twin per trade.
        assertNull(Watchlist.pVsRandom(List(39) { 1.0 }, List(39) { listOf(0.0) }))
        assertNull(Watchlist.pVsRandom(List(40) { 1.0 }, List(40) { emptyList() }))
        assertNull(Watchlist.pVsRandom(List(40) { 1.0 }, List(39) { listOf(0.0) }))
        val better = Watchlist.pVsRandom(List(40) { 100.0 }, List(40) { listOf(-50.0, 0.0, 50.0) })!!
        assertTrue(better < 0.01, "$better")
        val worse = Watchlist.pVsRandom(List(40) { -100.0 }, List(40) { listOf(-50.0, 0.0, 50.0) })!!
        assertTrue(worse > 0.99, "$worse")
    }

    @Test fun theScorecardAndJarvisOneTimeWord() {
        val none = Watchlist.score(Watchlist.Rule.C4, emptyList(), emptyList())
        assertNull(none.winRate); assertFalse(none.passed)
        assertEquals("R47 C4 BANKNIFTY high PCR: 0 shadow trades, would-be net +₹0 (p from 40 trades)", Watchlist.line(none))
        val few = Watchlist.score(Watchlist.Rule.R4_8, listOf(500.0, -200.0, 300.0), List(3) { listOf(0.0) })
        assertEquals("R52 R4-8 Gann swing break: 3 shadow trades, 67% won, would-be net +₹600 (p from 40 trades)", Watchlist.line(few))
        val good = Watchlist.score(Watchlist.Rule.H45_1, List(40) { 100.0 }, List(40) { listOf(-50.0, 50.0) })
        assertTrue(good.passed)
        assertTrue(Watchlist.line(good).contains("p vs random 0.0"))
        val v = Watchlist.verdictLine(good)
        assertTrue(v.contains("passed on unseen data") && v.contains("stays in shadow") && v.contains("your call"))
        val bad = Watchlist.score(Watchlist.Rule.H45_1, List(40) { -100.0 }, List(40) { listOf(-50.0, 50.0) })
        assertFalse(bad.passed)
        assertTrue(Watchlist.verdictLine(bad).contains("I would drop it"))
        assertTrue(Watchlist.verdictLine(Watchlist.Card(Watchlist.Rule.C4, 40, 1, -5.0, null)).endsWith("I would drop it from the Watchlist."))
        val e = Watchlist.eveningLine(listOf(none, few))
        assertTrue(e.startsWith("Watchlist (shadow, not traded; information only, not a trade signal): ") && e.endsWith("."))
        assertEquals(1, Watchlist.score(Watchlist.Rule.C4, listOf(1.0), listOf(listOf(0.0))).trades)
        assertTrue(Watchlist.line(Watchlist.Card(Watchlist.Rule.C4, 41, 20, 10.0, null)).endsWith("+₹10"))
        assertNotNull(Watchlist.Rule.C4.record)
    }
}
