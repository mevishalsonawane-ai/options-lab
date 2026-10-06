package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Solo (midday) ([SoloMidday]): the research's own trades replayed (golden days from solo2's riskreduced_trades.csv: the
 * same index, side, strike, entry minute and exit reason), then each rule on synthetic bars - the signal and its pins, the
 * strongest of several, the expiry-day and Liquidity skips, the stop on a 1-minute close, the breakeven lock, 14:30, the
 * forward test's switch-off and the words.
 */
class SoloMiddayTest {
    private val day = LocalDate.of(2026, 10, 7)
    private fun at(h: Int, m: Int, s: Int = 0): LocalDateTime = day.atTime(h, m, s)
    private fun minute(m: Int): LocalDateTime = day.atTime(9, 15).plusMinutes(m.toLong())
    private fun flat(t: LocalDateTime, c: Double) = Bar(t, c, c, c, c)

    /** A morning: 09:15 flat at [o], 09:16 reaching [hi] and [lo], then flat at [c] to 11:59 ([from]: the first minute). */
    private fun morning(o: Double, c: Double, hi: Double, lo: Double, from: Int = 0): List<Bar> = (from until 165).map { m ->
        when (m) {
            from -> flat(minute(m), o)
            from + 1 -> Bar(minute(m), o, hi, lo, c)
            else -> flat(minute(m), c)
        }
    }

    // ---- golden days: the research's trades -----------------------------------------------------------------------------

    private class Golden(val day: LocalDate, val atr: Map<String, Double>, val bars: Map<String, List<Bar>>,
                         val u: String, val side: Int, val strike: Int, val em: Int, val why: String, val xm: Int)

    private val golden: List<Golden> by lazy {
        val text = GZIPInputStream(javaClass.getResourceAsStream("/solo_midday_golden.csv.gz")!!).bufferedReader().readText()
        val atr = HashMap<String, HashMap<String, Double>>()
        val bars = HashMap<String, HashMap<String, ArrayList<Bar>>>()
        val out = ArrayList<Golden>()
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split(',')
            when (f[0]) {
                "A" -> atr.getOrPut(f[1]) { HashMap() }[f[2]] = f[3].toDouble()
                "B" -> bars.getOrPut(f[1]) { HashMap() }.getOrPut(f[2]) { ArrayList() } +=
                    Bar(LocalDate.parse(f[1]).atTime(9, 15).plusMinutes(f[3].toLong()), f[4].toDouble(), f[5].toDouble(), f[6].toDouble(), f[7].toDouble())
                "E" -> out += Golden(LocalDate.parse(f[1]), atr.getValue(f[1]), bars.getValue(f[1]), f[2], f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6], f[7].toInt())
            }
        }
        out
    }

    @Test fun theResearchTradesAreTakenTheSameWay() {
        assertEquals(7, golden.size)
        val reasons = HashSet<String>()
        for (g in golden) {
            val noon = g.day.atTime(12, 0, 30)
            // At 12:00:30 the feed holds the bars to the forming 12:00 minute.
            val decisions = SoloMidday.UNDERLYINGS.map { u -> SoloMidday.signal(u, g.bars.getValue(u).filter { !it.start.isAfter(noon) }, g.atr[u], noon) }
            val signals = decisions.mapNotNull { it.signal }
            val c = SoloMidday.pick(signals) { SoloMidday.gate(false, null) }
            val s = c.taken
            assertNotNull(s, "${g.day}: a trade")
            assertEquals(g.u, s.underlying, "${g.day}: the index")
            assertEquals(g.side, s.side, "${g.day}: the side")
            assertEquals(g.strike, s.strike, "${g.day}: the strike")
            assertEquals(g.day.atTime(9, 15).plusMinutes(g.em.toLong()), s.at, "${g.day}: the entry minute")
            assertTrue(c.skipped.isEmpty())
            assertEquals(s, SoloMidday.rank(signals).first())
            // The exit, walked minute by minute as the app would: nothing before the deciding bar has closed, then the reason.
            val p = SoloMidday.Open(s.underlying, s.side, s.index, s.atr, s.at)
            val all = g.bars.getValue(s.underlying)
            val decided = if (g.why == SoloMidday.TIME) g.day.atTime(14, 30) else g.day.atTime(9, 15).plusMinutes(g.xm - 1L)
            val after = if (g.why == SoloMidday.TIME) decided.plusSeconds(5) else decided.plusMinutes(1).plusSeconds(5)
            assertNull(SoloMidday.walk(p, all, after.minusMinutes(1)).exit, "${g.day}: held a minute before")
            val w = SoloMidday.walk(p, all, after)
            assertEquals(g.why, w.exit, "${g.day}: the exit reason")
            assertEquals(decided, w.decidedOn, "${g.day}: the deciding bar")
            reasons += g.why
        }
        assertEquals(setOf(SoloMidday.INDEX_STOP, SoloMidday.LOCK, SoloMidday.TIME), reasons)
        // The strongest of several: 17 Oct 2025, all four signalled; SENSEX (1.09 ATR) over NIFTY (1.03).
        val g = golden.single { it.day == LocalDate.of(2025, 10, 17) }
        val noon = g.day.atTime(12, 0, 30)
        val sig = SoloMidday.UNDERLYINGS.mapNotNull { u -> SoloMidday.signal(u, g.bars.getValue(u), g.atr[u], noon).signal }
        assertEquals(listOf("SENSEX", "NIFTY", "FINNIFTY", "BANKNIFTY"), SoloMidday.rank(sig).map { it.underlying })
        // SENSEX expiring that day: NIFTY, the next strongest, with the research's own NIFTY exit (index stop, 12:38 bar).
        val c = SoloMidday.pick(sig) { s -> SoloMidday.gate(s.underlying == "SENSEX", null) }
        assertEquals("NIFTY", c.taken!!.underlying)
        assertEquals(listOf("SENSEX" to "expiry_today"), c.skipped.map { it.first.underlying to it.second })
        val n = c.taken!!
        val w = SoloMidday.walk(SoloMidday.Open("NIFTY", n.side, n.index, n.atr, n.at), g.bars.getValue("NIFTY"), g.day.atTime(14, 30, 5))
        assertEquals(SoloMidday.INDEX_STOP to g.day.atTime(9, 15).plusMinutes(218), w.exit to w.decidedOn)
    }

    // ---- the signal ---------------------------------------------------------------------------------------------------

    @Test fun anUpMoveOfAnAtrClosingNearTheHighBuysTheCallFourStrikesIn() {
        val bars = morning(24_000.0, 24_100.0, 24_110.0, 23_990.0) + Bar(at(12, 0), 24_105.0, 24_106.0, 24_104.0, 24_105.0)
        val d = SoloMidday.signal("NIFTY", bars, 100.0, at(12, 0, 20))
        val s = d.signal!!
        assertEquals("signal", d.why)
        assertEquals(1.0, d.strength!!, 1e-9)
        assertEquals(10.0 / 120.0, d.position!!, 1e-9)
        assertEquals(1, s.side); assertTrue(s.call)
        assertEquals(100.0, s.move, 1e-9); assertEquals(1.0, s.strength, 1e-9); assertEquals(10.0 / 120.0, s.position, 1e-9)
        assertEquals(24_105.0, s.index, 1e-9, "the 12:00 minute's open")
        assertEquals(at(12, 0), s.at)
        assertEquals(23_900, s.strike)
        assertEquals(24_075.0, s.stop, 1e-9)
        assertEquals(24_165.0, s.target, 1e-9)
        assertEquals(24_150.0, s.lockAt, 1e-9)
        assertEquals(24_105.0, s.lockLevel, 1e-9)
        // Up to 12:03 (3 minutes' slack); a bar from another day is never read.
        assertNotNull(SoloMidday.signal("NIFTY", bars + flat(day.minusDays(1).atTime(9, 15), 1.0), 100.0, at(12, 3, 59)).signal)
        assertEquals("missed_12", SoloMidday.signal("NIFTY", bars, 100.0, at(12, 4)).why)
        assertEquals("before_12", SoloMidday.signal("NIFTY", bars, 100.0, at(11, 59, 59)).why)
    }

    @Test fun aDownMoveBuysThePutAndWithoutThe1200MinuteThe1159CloseIsThePrice() {
        val d = SoloMidday.signal("NIFTY", morning(24_000.0, 23_900.0, 24_010.0, 23_890.0), 100.0, at(12, 0, 5))
        val s = d.signal!!
        assertEquals(-1, s.side); assertFalse(s.call)
        assertEquals(23_900.0, s.index, 1e-9)
        assertEquals(24_100, s.strike)
        assertEquals(23_930.0, s.stop, 1e-9)
        assertEquals(23_855.0, s.lockAt, 1e-9)
        assertEquals(10.0 / 120.0, s.position, 1e-9)
    }

    @Test fun theSignalsPinsAreTheResearchs() {
        val now = at(12, 0, 10)
        fun why(bars: List<Bar>, atr: Double? = 100.0) = SoloMidday.signal("NIFTY", bars, atr, now).why
        val ok = morning(24_000.0, 24_100.0, 24_110.0, 23_990.0)
        assertEquals("no_atr", why(ok, null)); assertEquals("no_atr", why(ok, 0.0)); assertEquals("no_atr", why(ok, Double.NaN))
        assertEquals("waiting_for_1159", why(ok.dropLast(1)))
        assertEquals("waiting_for_1159", why(emptyList()))
        assertEquals("late_first_minute", why(morning(24_000.0, 24_100.0, 24_110.0, 23_990.0, from = 2)))
        assertEquals("too_few_minutes", why(ok.take(2) + ok.takeLast(50)))
        // Exactly 0.5 ATR is enough; under it is not (and the decision keeps how far it moved).
        assertEquals("signal", why(morning(24_000.0, 24_050.0, 24_050.0, 23_990.0)))
        val small = SoloMidday.signal("NIFTY", morning(24_000.0, 24_049.0, 24_110.0, 23_990.0), 100.0, now)
        assertEquals("move_too_small", small.why); assertEquals(0.49, small.strength!!, 1e-9); assertNull(small.position)
        // The outer quarter, with no tolerance: 0.25 is in, mid-range is out.
        assertEquals("signal", why(morning(24_000.0, 24_100.0, 24_140.0, 23_980.0)))
        val mid = SoloMidday.signal("NIFTY", morning(24_000.0, 24_060.0, 24_110.0, 23_990.0), 100.0, now)
        assertEquals("mid_range", mid.why); assertEquals(50.0 / 120.0, mid.position!!, 1e-9)
        val down = SoloMidday.signal("NIFTY", morning(24_000.0, 23_940.0, 24_010.0, 23_890.0), 100.0, now)
        assertEquals("mid_range", down.why)
    }

    @Test fun eachIndexHasItsStrikeStep() {
        assertEquals(listOf(50, 100, 50, 100), SoloMidday.UNDERLYINGS.map { SoloMidday.step(it) })
        assertEquals(24_150, SoloMidday.atm("NIFTY", 24_125.0))
        assertEquals(54_600, SoloMidday.strikeFor("BANKNIFTY", 1, 55_010.0))
        assertEquals(73_700, SoloMidday.strikeFor("SENSEX", -1, 73_260.0))
        assertEquals(25_250, SoloMidday.strikeFor("FINNIFTY", -1, 25_040.0))
        // 4 ITM, then one step either side - the less in the money first.
        assertEquals(listOf(54_600, 54_700, 54_500), SoloMidday.strikesToTry("BANKNIFTY", 1, 55_010.0))
        assertEquals(listOf(25_250, 25_200, 25_300), SoloMidday.strikesToTry("FINNIFTY", -1, 25_040.0))
    }

    // ---- one of several ------------------------------------------------------------------------------------------------

    private fun sig(u: String, move: Double, atr: Double = 100.0) =
        SoloMidday.Signal(u, if (move > 0) 1 else -1, 1_000.0, 1_000.0 + move, maxOf(1_000.0, 1_000.0 + move), minOf(1_000.0, 1_000.0 + move), atr, 1_000.0 + move, at(12, 0))

    @Test fun theStrongestIsTakenAndABlockedOnePassesTheTurn() {
        val n = sig("NIFTY", 60.0); val b = sig("BANKNIFTY", -300.0, 400.0); val f = sig("FINNIFTY", 75.0); val x = sig("SENSEX", 70.0)
        assertEquals(listOf("BANKNIFTY", "FINNIFTY", "SENSEX", "NIFTY"), SoloMidday.rank(listOf(n, b, f, x)).map { it.underlying })
        // A tie goes to the name, A-Z.
        assertEquals(listOf("FINNIFTY", "NIFTY"), SoloMidday.rank(listOf(sig("NIFTY", 75.0), f)).map { it.underlying })
        assertEquals(b, SoloMidday.pick(listOf(n, b, f, x)) { null }.taken)
        // BANKNIFTY's expiry day: FINNIFTY. FINNIFTY held by the Liquidity arm too: SENSEX.
        val liq = "same_side_already_held: Liquidity 15+5 holds FINNIFTY26OCT25000CE"
        val c = SoloMidday.pick(listOf(n, b, f, x)) { s ->
            SoloMidday.gate(s.underlying == "BANKNIFTY", if (s.underlying == "FINNIFTY") liq else null)
        }
        assertEquals(x, c.taken)
        assertEquals(listOf("BANKNIFTY" to "expiry_today", "FINNIFTY" to liq), c.skipped.map { it.first.underlying to it.second })
        // Nothing allowed: none taken, every one said.
        val none = SoloMidday.pick(listOf(n, f)) { "expiry_unknown" }
        assertNull(none.taken); assertEquals(2, none.skipped.size)
        assertNull(SoloMidday.pick(emptyList()) { null }.taken)
    }

    @Test fun theGateSkipsAnExpiryDayAnUnreadableMasterAndAnotherAutomaticPosition() {
        assertNull(SoloMidday.gate(false, null))
        assertEquals("expiry_today", SoloMidday.gate(true, null))
        assertEquals("expiry_unknown", SoloMidday.gate(null, null))
        assertEquals("opposite_position_open: x", SoloMidday.gate(false, "opposite_position_open: x"))
        assertEquals("expiry_today", SoloMidday.gate(true, "opposite_position_open: x"))
    }

    // ---- the exit -------------------------------------------------------------------------------------------------------

    private val call = SoloMidday.Open("NIFTY", 1, 24_105.0, 100.0, at(12, 0, 12))
    private fun b(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Bar(at(h, m), o, hi, lo, c)

    @Test fun theIndexStopIsAOneMinuteCloseNotATouch() {
        val bars = listOf(
            b(11, 59, 24_100.0, 24_100.0, 24_000.0, 24_000.0),          // before the entry: never read
            b(12, 0, 24_105.0, 24_110.0, 24_060.0, 24_080.0),           // through the stop (24,075) but closed above: held
            b(12, 1, 24_080.0, 24_090.0, 24_070.0, 24_075.0),           // closed at the stop: out
        )
        assertNull(SoloMidday.walk(call, bars, at(12, 1, 30)).exit, "the 12:01 bar is still forming")
        val w = SoloMidday.walk(call, bars, at(12, 2, 1))
        assertEquals(SoloMidday.INDEX_STOP, w.exit); assertEquals(at(12, 1), w.decidedOn)
        assertEquals(5.0, w.best, 1e-9); assertFalse(w.locked)
        // A put: a close at or above its stop.
        val put = SoloMidday.Open("NIFTY", -1, 23_900.0, 100.0, at(12, 0))
        val pw = SoloMidday.walk(put, listOf(b(12, 0, 23_900.0, 23_940.0, 23_890.0, 23_920.0), b(12, 1, 23_920.0, 23_935.0, 23_920.0, 23_930.0)), at(12, 5))
        assertEquals(SoloMidday.INDEX_STOP to at(12, 1), pw.exit to pw.decidedOn)
    }

    @Test fun theLockMovesTheStopToBreakevenAt75PercentOf2R() {
        val bars = listOf(
            b(12, 0, 24_105.0, 24_150.0, 24_100.0, 24_140.0),           // 45 points (0.45 ATR) earns it; its own low is not an exit
            b(12, 1, 24_140.0, 24_145.0, 24_106.0, 24_110.0),
            b(12, 2, 24_110.0, 24_112.0, 24_105.0, 24_108.0),           // back to the entry level: out
        )
        val held = SoloMidday.walk(call, bars, at(12, 2, 30))
        assertNull(held.exit); assertTrue(held.locked); assertEquals(45.0, held.best, 1e-9)
        val w = SoloMidday.walk(call, bars, at(12, 3))
        assertEquals(SoloMidday.LOCK to at(12, 2), w.exit to w.decidedOn)
        assertTrue(w.locked)
        // Not earned (44 points): the same return is held.
        val short = listOf(b(12, 0, 24_105.0, 24_149.0, 24_100.0, 24_140.0)) + bars.drop(1)
        assertNull(SoloMidday.walk(call, short, at(12, 3)).exit)
        // The index stop comes first on a bar that does both.
        val both = bars.take(1) + b(12, 1, 24_140.0, 24_140.0, 24_000.0, 24_070.0)
        assertEquals(SoloMidday.INDEX_STOP, SoloMidday.walk(call, both, at(12, 3)).exit)
        // A put's lock: its low 45 under, then a high back at the entry.
        val put = SoloMidday.Open("BANKNIFTY", -1, 50_000.0, 100.0, at(12, 0))
        val pb = listOf(b(12, 0, 50_000.0, 50_010.0, 49_955.0, 49_960.0), b(12, 1, 49_960.0, 50_000.0, 49_950.0, 49_990.0))
        assertEquals(SoloMidday.LOCK, SoloMidday.walk(put, pb, at(12, 2)).exit)
    }

    @Test fun outAt1430() {
        val bars = (0 until 160).map { flat(at(12, 0).plusMinutes(it.toLong()), 24_110.0) }   // to 14:39: the 14:30 bar on is never read
        assertNull(SoloMidday.walk(call, bars, at(14, 29, 59)).exit)
        val w = SoloMidday.walk(call, bars, at(14, 30, 2))
        assertEquals(SoloMidday.TIME to at(14, 30), w.exit to w.decidedOn)
        assertEquals(5.0, w.best, 1e-9)
    }

    // ---- the forward test -----------------------------------------------------------------------------------------------

    @Test fun theForwardTestSwitchesSoloOffOnADrawdownOrALosingSixty() {
        val r = SoloMidday.record(listOf(1_000.0, -3_000.0, 500.0, 2_000.0))
        assertEquals(4, r.trades); assertEquals(500.0, r.net, 1e-9); assertEquals(3, r.wins)
        assertEquals(-500.0, r.drawdown, 1e-9); assertEquals(-3_000.0, r.worstDrawdown, 1e-9); assertEquals(125.0, r.perTrade!!, 1e-9)
        assertEquals(SoloMidday.Verdict.RUNNING, SoloMidday.verdict(r))
        assertNull(SoloMidday.record(emptyList()).perTrade)
        // A drawdown of exactly 25,000 is not beyond it; one rupee more switches it off, even before 60 trades.
        assertEquals(SoloMidday.Verdict.RUNNING, SoloMidday.verdict(SoloMidday.record(listOf(5_000.0, -25_000.0))))
        val dd = SoloMidday.verdict(SoloMidday.record(listOf(5_000.0, -25_001.0, 40_000.0)))
        assertEquals(SoloMidday.Verdict.FAILED_DRAWDOWN, dd); assertTrue(SoloMidday.switchOff(dd))
        // At 60 trades: net per trade at or below 0 fails, above 0 passes.
        val flat60 = List(60) { if (it % 2 == 0) 100.0 else -100.0 }
        assertEquals(SoloMidday.Verdict.RUNNING, SoloMidday.verdict(SoloMidday.record(flat60.take(59))))
        assertEquals(SoloMidday.Verdict.FAILED_NET, SoloMidday.verdict(SoloMidday.record(flat60)))
        assertTrue(SoloMidday.switchOff(SoloMidday.Verdict.FAILED_NET))
        val good = SoloMidday.verdict(SoloMidday.record(flat60.dropLast(1) + 1.0))
        assertEquals(SoloMidday.Verdict.PASSED, good); assertFalse(SoloMidday.switchOff(good))
        assertFalse(SoloMidday.switchOff(SoloMidday.Verdict.RUNNING))
    }

    // ---- words ------------------------------------------------------------------------------------------------------------

    @Test fun jarvisSaysWhatItSawAndItsPlan() {
        val s = SoloMidday.signal("NIFTY", morning(24_000.0, 24_100.0, 24_110.0, 23_990.0) + Bar(at(12, 0), 24_105.0, 24_106.0, 24_104.0, 24_105.0),
            100.0, at(12, 0, 20)).signal!!
        assertEquals("NIFTY is up 100 points from the open, 1.00 x its daily ATR (100), and it closed in the top 8% of the morning's range: " +
            "a midday trend. Of the 3 indices that signalled, it was the strongest.", SoloMidday.why(s, 3))
        assertTrue(SoloMidday.why(s, 1).endsWith("It was the only index that signalled."))
        val put = SoloMidday.signal("NIFTY", morning(24_000.0, 23_900.0, 24_010.0, 23_890.0), 100.0, at(12, 0, 5)).signal!!
        assertTrue(SoloMidday.why(put, 1).startsWith("NIFTY is down 100 points from the open, 1.00 x its daily ATR (100), and it closed in the bottom 8%"))
        assertEquals("Out if NIFTY closes at or below 24,075 (0.3 ATR); the stop moves to breakeven (24,105) once NIFTY reaches 24,150; out at " +
            "14:30 at the latest. Record: this setup made money before Jul 2024 and was about flat since. It is a test, not an edge.", SoloMidday.plan(s))
        assertTrue(SoloMidday.plan(put).startsWith("Out if NIFTY closes at or above 23,930 (0.3 ATR)"))
    }

    @Test fun jarvisSaysWhyNoTradeAndWhyOut() {
        fun d(why: String, k: Double? = null, q: Double? = null) = SoloMidday.skip(SoloMidday.Decision("BANKNIFTY", null, why, k, q))
        assertEquals("BANKNIFTY moved only 0.32 ATR from the open (it needs 0.5)", d("move_too_small", 0.321))
        assertEquals("BANKNIFTY moved only 0.00 ATR from the open (it needs 0.5)", d("move_too_small"))
        assertEquals("BANKNIFTY's close was mid-range (40% from its edge; it needs the outer 25%)", d("mid_range", 0.7, 0.4))
        assertEquals("BANKNIFTY's close was mid-range (0% from its edge; it needs the outer 25%)", d("mid_range"))
        assertEquals("BANKNIFTY's daily ATR could not be read", d("no_atr"))
        assertEquals("BANKNIFTY: the 12:00 entry was missed (12:00-12:03 only)", d("missed_12"))
        assertEquals("BANKNIFTY: waiting for the 11:59 minute", d("waiting_for_1159"))
        assertEquals("BANKNIFTY: waiting for the 11:59 minute", d("before_12"))
        assertEquals("BANKNIFTY: the morning's minutes were incomplete", d("too_few_minutes"))
        val s = sig("FINNIFTY", 60.0); val big = sig("SENSEX", 90.0)
        assertEquals("FINNIFTY expires today", SoloMidday.passedOver(s, "expiry_today", big))
        assertEquals("FINNIFTY's expiry could not be read", SoloMidday.passedOver(s, "expiry_unknown", null))
        assertEquals("I already hold SENSEX, the stronger move (FINNIFTY was 0.60 ATR)", SoloMidday.passedOver(s, "stronger", big))
        assertEquals("FINNIFTY: the Liquidity arm holds it", SoloMidday.passedOver(s, "the Liquidity arm holds it", big))

        val stop = SoloMidday.walk(call, listOf(b(12, 0, 24_105.0, 24_120.0, 24_060.0, 24_070.0)), at(12, 1))
        assertEquals("Out by the index stop (NIFTY closed through 24,075). At best NIFTY went 15 points its way (25% of 2R).",
            SoloMidday.exitSay(call, stop, 24_070.0, -1_500.0))
        val lock = SoloMidday.Walk(SoloMidday.LOCK, at(12, 30), 48.0, true)
        assertEquals("Out by breakeven after reaching 80% of 2R (NIFTY came back to 24,105). At best NIFTY went 48 points its way (80% of 2R). " +
            "The option lost although the index gained (time value).", SoloMidday.exitSay(call, lock, 24_106.0, -300.0))
        val time = SoloMidday.Walk(SoloMidday.TIME, at(14, 30), 30.0, false)
        assertEquals("Out by 14:30. At best NIFTY went 30 points its way (50% of 2R).", SoloMidday.exitSay(call, time, 24_120.0, 900.0))
        assertEquals("Out by 14:30. At best NIFTY went 30 points its way (50% of 2R).", SoloMidday.exitSay(call, time, null, null))
        assertEquals("Out by 14:30. At best NIFTY went 30 points its way (50% of 2R).", SoloMidday.exitSay(call, time, 24_120.0, null))
    }

    @Test fun theCardShowsTheForwardTestAndTheResearchLine() {
        val r = SoloMidday.record(listOf(1_200.0, -2_000.0, 500.0))
        val lines = SoloMidday.card(r, LocalDate.of(2026, 10, 7))
        assertEquals("Not proven: paper only, never Zerodha.", lines[0])
        assertEquals("Trades since start (07 Oct): 3, net −₹300, −₹100 a trade, drawdown −₹1,500.", lines[1])
        assertEquals("3 of 60 trades of the forward test.", lines[2])
        assertTrue(lines[3].contains("more than ₹25,000 below its best"))
        assertEquals("Research, unseen period (Jul 2024 - Oct 2026): +₹31k on 196 trades, profit factor 1.14 - not proven.", lines[4])
        assertEquals("Trades since start: 0, net ₹0, no ₹/trade yet, drawdown ₹0.", SoloMidday.card(SoloMidday.record(emptyList()), null)[1])
        assertEquals("Forward test: 3 of 60 trades, net −₹300 (−₹100 a trade), drawdown −₹1,500 (worst −₹2,000).", SoloMidday.forwardLine(r))
        assertEquals("Forward test: 0 of 60 trades, net ₹0, drawdown ₹0 (worst ₹0).", SoloMidday.forwardLine(SoloMidday.record(emptyList())))
        val many = SoloMidday.record(List(61) { 100.0 })
        assertTrue(SoloMidday.forwardLine(many).startsWith("Forward test: 60 of 60 trades (61 in all), net ₹6,100"))
        assertEquals("60 of 60 trades of the forward test.", SoloMidday.card(many, null)[2])
    }

    @Test fun jarvisReportsTheForwardTest() {
        val dd = SoloMidday.record(listOf(-26_000.0))
        assertTrue(SoloMidday.verdictSay(dd, SoloMidday.Verdict.FAILED_DRAWDOWN).startsWith("Boss, Solo (midday) is −₹26,000 below its best - beyond the ₹25,000"))
        val flat = SoloMidday.record(List(60) { if (it % 2 == 0) 100.0 else -100.0 })
        assertTrue(SoloMidday.verdictSay(flat, SoloMidday.Verdict.FAILED_NET).startsWith("Boss, Solo (midday) has 60 closed paper trades and makes ₹0 a trade"))
        val good = SoloMidday.record(List(60) { 100.0 })
        assertTrue(SoloMidday.verdictSay(good, SoloMidday.Verdict.PASSED).contains("it passed the bar set in advance and stays on paper. Still not proven"))
        assertEquals(SoloMidday.forwardLine(good), SoloMidday.verdictSay(good, SoloMidday.Verdict.RUNNING))
        val empty = SoloMidday.record(emptyList())
        assertTrue(SoloMidday.verdictSay(empty, SoloMidday.Verdict.FAILED_NET).contains("makes ₹0 a trade"))
        assertTrue(SoloMidday.verdictSay(empty, SoloMidday.Verdict.PASSED).contains("₹0 a trade"))
        assertEquals("−₹1,234", SoloMidday.rs(-1_234.4)); assertEquals("₹0", SoloMidday.rs(0.0))
    }
}
