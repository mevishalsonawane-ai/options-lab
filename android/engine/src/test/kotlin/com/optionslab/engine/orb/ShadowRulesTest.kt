package com.optionslab.engine.orb

import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Each shadow rule ([ShadowRules]) on synthetic bars: its entry, its filters and every exit, with the paper fills and charges.
 * The day: a BANKNIFTY opening range 51,800 - 52,000 (09:15 .. 10:00 bars), the 09:20 bar closing at 51,940 (ATM 51,900).
 */
class ShadowRulesTest {
    private val day = LocalDate.of(2026, 10, 7)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private fun bar(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Bar(at(h, m), o, hi, lo, c)
    private fun flat(h: Int, m: Int, c: Double) = bar(h, m, c, c, c, c)

    /** 09:15 .. 10:00: the range 51,800 - 52,000; the 09:20 bar closes at 51,940. */
    private val range: List<Bar> = (0..9).map { i ->
        val m = 15 + i * 5
        when (i) {
            0 -> bar(9, 15, 51_900.0, 52_000.0, 51_850.0, 51_900.0)
            1 -> bar(9, 20, 51_900.0, 51_950.0, 51_800.0, 51_940.0)
            else -> bar(9 + m / 60, m % 60, 51_900.0, 51_950.0, 51_850.0, 51_900.0)
        }
    }
    private val rng = 52_000.0 to 51_800.0

    // ---- V43 (ORB / ORB Fresh) ------------------------------------------------------------------------------------------

    @Test fun orbV43TakesTheFirstFreshBreakThePcrAgreesWithOnceADay() {
        assertEquals("waiting_for_opening_range", ShadowRules.orbEntry(range.dropLast(1), 0, 1.0).why)
        val up = range + flat(10, 5, 52_050.0)
        val e = ShadowRules.orbEntry(up, 0, 0.95)
        assertEquals(ShadowRules.Entry(1, 51_900, at(10, 5)), e.entry)
        assertEquals("break", e.why)
        assertEquals("done_for_today", ShadowRules.orbEntry(up, 1, 0.95).why)
        assertEquals("pcr_against", ShadowRules.orbEntry(up, 0, 0.94).why, "a CE needs PCR 0.95 or more")
        assertEquals("no_pcr", ShadowRules.orbEntry(up, 0, null).why)
        // Below the range: a PE, PCR 0.85 or less.
        val down = range + flat(10, 5, 51_750.0)
        assertEquals(-1, ShadowRules.orbEntry(down, 0, 0.85).entry!!.side)
        assertEquals("pcr_against", ShadowRules.orbEntry(down, 0, 0.86).why)
        // The next bar still above is not a fresh break; inside the range is no signal.
        assertEquals("not_a_fresh_break", ShadowRules.orbEntry(up + flat(10, 10, 52_060.0), 0, 1.0).why)
        assertEquals("inside_range", ShadowRules.orbEntry(range + flat(10, 5, 51_900.0), 0, 1.0).why)
        // The 10:00 bar is not a decision bar, nor is 14:00.
        assertEquals("no_decision_bar", ShadowRules.orbEntry(range, 0, 1.0).why)
        assertEquals("no_decision_bar", ShadowRules.orbEntry(range + flat(14, 0, 52_100.0), 0, 1.0).why)
        assertEquals(1, ShadowRules.orbEntry(range + flat(13, 55, 52_100.0), 0, 1.0).entry!!.side)
    }

    @Test fun thePcrIsPutOverCallOiOnTwentyOneStrikesBeforeTen() {
        assertEquals((50_900..52_900 step 100).toList(), ShadowRules.pcrStrikes(51_900))
        assertEquals(21, ShadowRules.pcrStrikes(51_900).size)
        assertEquals(1.5, ShadowRules.pcr(mapOf(51_900 to 100L, 52_000 to 100L), mapOf(51_900 to 300L)))
        assertNull(ShadowRules.pcr(emptyMap(), mapOf(51_900 to 300L)))
        assertNull(ShadowRules.pcr(mapOf(51_900 to 0L), mapOf(51_900 to 300L)))
        assertTrue(ShadowRules.pcrConfirms(1, 0.95)); assertFalse(ShadowRules.pcrConfirms(1, 0.9))
        assertTrue(ShadowRules.pcrConfirms(-1, 0.85)); assertFalse(ShadowRules.pcrConfirms(-1, 0.9))
        val oi = listOf(at(9, 58) to 10L, at(9, 59) to 20L, at(10, 0) to 30L)
        assertEquals(20L, ShadowRules.oiBefore(oi, at(10, 0)))
        assertEquals(20L, ShadowRules.oiBefore(oi.reversed(), at(10, 0)), "any order")
        assertNull(ShadowRules.oiBefore(oi, at(9, 58)))
    }

    // ---- S17 (ORB Sweep) and R20 (Range Fade) ------------------------------------------------------------------------

    @Test fun s17IsTheSweepSignalWithItsExtremeAndTheRangeMid() {
        assertEquals("waiting_for_opening_range", ShadowRules.edgeSignal(true, range.take(3), 0, null).why)
        val high = range + bar(10, 5, 51_980.0, 52_050.0, 51_940.0, 51_950.0)
        val e = ShadowRules.edgeSignal(true, high, 0, null)
        assertEquals(ShadowRules.Entry(-1, 51_900, at(10, 5), extreme = 52_050.0, mid = 51_900.0), e.entry)
        assertEquals("sweep_of_high", e.why)
        val low = range + bar(10, 5, 51_850.0, 51_860.0, 51_760.0, 51_820.0)
        assertEquals(51_760.0, ShadowRules.edgeSignal(true, low, 0, null).entry!!.extreme)
        assertEquals(1, ShadowRules.edgeSignal(true, low, 0, null).entry!!.side)
        assertEquals("day_limit_reached", ShadowRules.edgeSignal(true, high, 2, null).why)
        assertEquals("no_sweep", ShadowRules.edgeSignal(true, range + flat(10, 5, 51_900.0), 0, null).why)
    }

    @Test fun r20IsRangeFadeFromNoonOnly() {
        val edge = bar(11, 0, 51_960.0, 51_990.0, 51_940.0, 51_950.0)
        assertEquals("no_decision_bar", ShadowRules.edgeSignal(false, range + edge, 0, null).why, "10:30 - 11:55 is not R20's window")
        val noon = range + bar(12, 0, 51_960.0, 51_990.0, 51_940.0, 51_950.0)
        val e = ShadowRules.edgeSignal(false, noon, 0, null)
        assertEquals(-1, e.entry!!.side); assertEquals("fade_of_high", e.why); assertEquals(51_990.0, e.entry!!.extreme)
        assertEquals("fade_of_low", ShadowRules.edgeSignal(false, range + bar(12, 5, 51_830.0, 51_840.0, 51_810.0, 51_830.0), 0, null).why)
        assertEquals("not_at_the_edge", ShadowRules.edgeSignal(false, range + flat(12, 0, 51_900.0), 0, null).why)
        assertEquals("cooling_down_after_exit", ShadowRules.edgeSignal(false, noon, 1, at(12, 3)).why)
        assertEquals("no_decision_bar", ShadowRules.edgeSignal(false, range + flat(14, 0, 51_990.0), 0, null).why)
    }

    @Test fun theOiWallIsTheEdgeStrikeHoldingTheMostOi() {
        assertEquals("CE", ShadowRules.wallRight(-1)); assertEquals("PE", ShadowRules.wallRight(1))
        assertEquals((51_900..52_900 step 100).toList(), ShadowRules.wallStrikes(-1, 51_900))
        assertEquals((50_900..51_900 step 100).toList().reversed(), ShadowRules.wallStrikes(1, 51_900))
        assertEquals(52_000, ShadowRules.edgeStrike(-1, rng))
        assertEquals(52_100, ShadowRules.edgeStrike(-1, 52_010.0 to 51_800.0), "the high rounded up")
        assertEquals(51_800, ShadowRules.edgeStrike(1, rng))
        assertEquals(51_700, ShadowRules.edgeStrike(1, 52_000.0 to 51_790.0), "the low rounded down")
        assertTrue(ShadowRules.oiWall(-1, rng, mapOf(51_900 to 50L, 52_000 to 90L, 52_100 to 90L)), "a tie at the top counts")
        assertFalse(ShadowRules.oiWall(-1, rng, mapOf(51_900 to 50L, 52_000 to 80L, 52_100 to 90L)))
        assertFalse(ShadowRules.oiWall(-1, rng, mapOf(51_900 to 50L)), "the edge strike not read")
        assertFalse(ShadowRules.oiWall(1, rng, mapOf(51_800 to 0L, 51_700 to 0L)), "no OI at all")
        assertTrue(ShadowRules.oiWall(1, rng, mapOf(51_800 to 70L, 51_700 to 10L)))
    }

    // ---- O08 (Midday momentum, NIFTY) --------------------------------------------------------------------------------

    private fun daily(vararg hlc: Triple<Double, Double, Double>, from: LocalDate = day.minusDays(hlc.size.toLong())): List<Bar> =
        hlc.mapIndexed { i, (h, l, c) -> Bar(from.plusDays(i.toLong()).atStartOfDay(), c, h, l, c) }

    @Test fun atr14IsTheMeanTrueRangeOfTheFourteenSessionsBefore() {
        val quiet = daily(*Array(15) { Triple(24_100.0, 24_000.0, 24_050.0) })
        assertEquals(100.0, ShadowRules.atr14(quiet, day)!!, 1e-9)
        // A gap: the previous close is further than the day's own range.
        val gap = daily(*(Array(14) { Triple(24_100.0, 24_000.0, 24_050.0) } + Triple(24_400.0, 24_350.0, 24_380.0)))
        assertEquals((13 * 100.0 + 350.0) / 14, ShadowRules.atr14(gap, day)!!, 1e-9)
        assertNull(ShadowRules.atr14(quiet.drop(1), day), "15 sessions are needed")
        assertNull(ShadowRules.atr14(daily(*Array(15) { Triple(1.0, 1.0, 1.0) }, from = day), day), "today and later do not count")
    }

    /** NIFTY minutes from 09:15 to [until] (exclusive): from 24,000 straight up to [close] at 11:59, the day's high. */
    private fun climb(close: Double, until: LocalDateTime = at(12, 0), first: LocalDateTime = at(9, 15), lowDip: Double = 0.0): List<Bar> {
        val n = java.time.Duration.between(first, until).toMinutes().toInt()
        return (0 until n).map { i ->
            val p = 24_000.0 + (close - 24_000.0) * i / (n - 1)
            Bar(first.plusMinutes(i.toLong()), p, p, if (i == 1) p - lowDip else p, p)
        }
    }

    @Test fun o08BuysFourStrikesInTheMoneyAtNoonOnAStrongMoveNearTheExtreme() {
        assertEquals(24_400, ShadowRules.momoStrike(1, 24_612.0))
        assertEquals(24_800, ShadowRules.momoStrike(-1, 24_612.0))
        val ones = climb(24_100.0)
        val up = ShadowRules.momoEntry(ones + Bar(at(12, 0), 24_110.0, 24_120.0, 24_100.0, 24_115.0), 200.0, at(12, 0).plusSeconds(10))
        assertEquals("momentum", up.why)
        // The 12:00 open 24,110 -> ATM 24,100, 4 strikes ITM 23,900; stop 0.3 x 200 below the 12:00 price.
        assertEquals(ShadowRules.Entry(1, 23_900, at(12, 0), indexStop = 24_110.0 - 60.0), up.entry)
        // Down, before the 12:00 minute prints: the 11:59 close stands in.
        val down = ShadowRules.momoEntry(climb(23_880.0), 200.0, at(12, 1))
        assertEquals(ShadowRules.Entry(-1, 24_100, at(12, 0), indexStop = 23_880.0 + 60.0), down.entry)
    }

    @Test fun o08WaitsSkipsAndRefusesAsPinned() {
        val ones = climb(24_100.0)
        assertEquals("before_12", ShadowRules.momoEntry(ones, 200.0, at(11, 59)).why)
        assertEquals("missed_12", ShadowRules.momoEntry(ones, 200.0, at(12, 4)).why)
        assertEquals("momentum", ShadowRules.momoEntry(ones, 200.0, at(12, 3).plusSeconds(59)).why, "12:03 is the last minute")
        assertEquals("no_atr", ShadowRules.momoEntry(ones, null, at(12, 0)).why)
        assertEquals("no_atr", ShadowRules.momoEntry(ones, 0.0, at(12, 0)).why)
        assertEquals("waiting_for_1159", ShadowRules.momoEntry(emptyList(), 200.0, at(12, 0)).why)
        assertEquals("waiting_for_1159", ShadowRules.momoEntry(ones.dropLast(1), 200.0, at(12, 0)).why)
        assertEquals("late_first_minute", ShadowRules.momoEntry(climb(24_100.0, first = at(9, 17)), 200.0, at(12, 0)).why)
        assertEquals("too_few_minutes", ShadowRules.momoEntry(ones.filterIndexed { i, _ -> i % 2 == 0 || i == ones.size - 1 }, 200.0, at(12, 0)).why)
        assertEquals("move_too_small", ShadowRules.momoEntry(ones, 201.0, at(12, 0)).why, "100 points < 0.5 x 201")
        // The close back in the middle of the day's range: not in the outer quarter.
        assertEquals("not_in_outer_quarter", ShadowRules.momoEntry(climb(24_100.0, lowDip = 400.0) +
            Bar(at(11, 59), 23_990.0, 24_500.0, 23_990.0, 24_100.0), 100.0, at(12, 0)).why)
    }

    // ---- exits ------------------------------------------------------------------------------------------------------

    private fun open(v: ShadowRules.Variant, side: Int, entry: Double = 300.0, time: LocalDateTime = at(10, 6), peak: Double = entry,
                     extreme: Double? = null, mid: Double? = null, stop: Double? = null) =
        ShadowRules.Open(v.id, side, entry, time, peak, extreme, mid, stop)

    @Test fun v43ExitsOnItsStopOrAt1430Only() {
        val p = open(ShadowRules.ORB_V43, 1)
        assertNull(ShadowRules.exitReason(p, 600.0, at(14, 29), emptyList()), "no target")
        assertNull(ShadowRules.exitReason(p, null, at(11, 0), emptyList()))
        assertEquals("stop", ShadowRules.exitReason(p, 260.0, at(11, 0), emptyList()))
        assertEquals("time_exit", ShadowRules.exitReason(p, 300.0, at(14, 30), emptyList()))
        assertNull(ShadowRules.exitReason(open(ShadowRules.ORB_V43, 1, entry = 30.0), 1.0, at(11, 0), emptyList()), "no stop under 40")
    }

    @Test fun s17ExitsOnTheIndexStructureThenItsStop() {
        val pe = open(ShadowRules.SWEEP_S17, -1, extreme = 52_050.0, mid = 51_900.0)
        val ok = listOf(flat(10, 6, 52_000.0))
        assertNull(ShadowRules.exitReason(pe, 300.0, at(10, 7), ok))
        assertEquals("index_stop", ShadowRules.exitReason(pe, 300.0, at(10, 8), ok + flat(10, 7, 52_051.0)))
        assertEquals("index_target", ShadowRules.exitReason(pe, 300.0, at(10, 8), ok + flat(10, 7, 51_900.0)))
        assertNull(ShadowRules.exitReason(pe, 300.0, at(10, 7).plusSeconds(30), ok + flat(10, 7, 51_900.0)), "10:07 has not closed")
        assertNull(ShadowRules.exitReason(pe, 300.0, at(10, 8), listOf(flat(10, 5, 51_800.0)) + ok), "before the entry minute")
        assertEquals("stop", ShadowRules.exitReason(pe, 259.0, at(10, 8), ok))
        assertEquals("session_end", ShadowRules.exitReason(pe, 300.0, at(15, 10), ok))
        val ce = open(ShadowRules.SWEEP_S17, 1, extreme = 51_760.0, mid = 51_900.0)
        assertEquals("index_stop", ShadowRules.exitReason(ce, 300.0, at(10, 8), listOf(flat(10, 7, 51_759.0))))
        assertEquals("index_target", ShadowRules.exitReason(ce, 300.0, at(10, 8), listOf(flat(10, 7, 51_900.0))))
        assertNull(ShadowRules.exitReason(ce, 300.0, at(10, 8), listOf(flat(10, 7, 51_800.0))))
        assertNull(ShadowRules.exitReason(open(ShadowRules.SWEEP_S17, 1), 300.0, at(10, 8), listOf(flat(10, 7, 1.0))), "no levels: only the stop")
    }

    @Test fun r20ExitsOnStopTargetAndTheLadder() {
        val p = open(ShadowRules.FADE_R20, 1)
        assertNull(ShadowRules.exitReason(p, 320.0, at(12, 30), emptyList()))
        assertNull(ShadowRules.exitReason(p, null, at(12, 30), emptyList()))
        assertEquals("target", ShadowRules.exitReason(p, 340.0, at(12, 30), emptyList()))
        assertEquals("stop", ShadowRules.exitReason(p, 260.0, at(12, 30), emptyList()))
        // Peak +20 (half the target) locks +10: back to 310 sells.
        assertEquals("profit_lock", ShadowRules.exitReason(p.copy(peak = 320.0), 310.0, at(12, 30), emptyList()))
        assertNull(ShadowRules.exitReason(p.copy(peak = 320.0), 311.0, at(12, 30), emptyList()))
        assertEquals("session_end", ShadowRules.exitReason(p, 300.0, at(15, 10), emptyList()))
    }

    @Test fun o08ExitsOnAnIndexCloseThroughItsStopOrAt1430() {
        val up = open(ShadowRules.MOMO_O08, 1, time = at(12, 0), stop = 24_050.0)
        assertNull(ShadowRules.exitReason(up, 1.0, at(12, 30), listOf(flat(12, 10, 24_051.0))), "no premium stop")
        assertEquals("index_stop", ShadowRules.exitReason(up, 300.0, at(12, 30), listOf(flat(12, 10, 24_050.0))))
        assertEquals("time_exit", ShadowRules.exitReason(up, 300.0, at(14, 30), emptyList()))
        val down = open(ShadowRules.MOMO_O08, -1, time = at(12, 0), stop = 24_150.0)
        assertEquals("index_stop", ShadowRules.exitReason(down, 300.0, at(12, 30), listOf(flat(12, 10, 24_160.0))))
        assertNull(ShadowRules.exitReason(down, 300.0, at(12, 30), listOf(flat(12, 10, 24_140.0))))
        assertNull(ShadowRules.exitReason(up.copy(indexStop = null), 300.0, at(12, 30), listOf(flat(12, 10, 1.0))))
        assertEquals("unknown_variant", ShadowRules.exitReason(up.copy(variant = "gone"), 300.0, at(12, 30), emptyList()))
    }

    // ---- fills, charges, the record ------------------------------------------------------------------------------

    @Test fun fillsAreThePaperAccountsAndChargesTheRealOnes() {
        assertEquals(300.15, ShadowRules.entryFill(300.0), 1e-9)                    // +5 bps
        assertTrue(ShadowRules.refused(40.0)); assertFalse(ShadowRules.refused(40.1))
        assertEquals(259.74, ShadowRules.exitFill("stop", 300.0, 265.0), 1e-9, "never above its trigger (260), -10 bps")
        assertEquals(254.74, ShadowRules.exitFill("stop", 300.0, 255.0), 1e-9, "gapped through: at the LTP, -10 bps (half-even)")
        assertEquals(30.0, ShadowRules.exitFill("stop", 30.0, 30.015), 1e-9, "no trigger under 40: a market sell")
        assertEquals(339.83, ShadowRules.exitFill("target", 300.0, 340.0), 1e-9)   // -5 bps
        val c = SandboxCosts.charge("BUY", BigDecimal("300.15"), 30).toDouble() + SandboxCosts.charge("SELL", BigDecimal("339.83"), 30).toDouble()
        assertEquals(c, ShadowRules.charges(300.15, 339.83, 30), 1e-9)
    }

    @Test fun theSummaryAndThePromotionBar() {
        val s = ShadowRules.summarize(listOf(100.0, -50.0, 250.0))
        assertEquals(3, s.trades); assertEquals(300.0, s.net); assertEquals(100.0, s.perTrade); assertEquals(7.0, s.profitFactor)
        assertNull(ShadowRules.summarize(emptyList()).perTrade)
        assertNull(ShadowRules.summarize(listOf(10.0)).profitFactor)
        // 60 trades, net above zero, PF 1.2 or more.
        val due = ShadowRules.summarize(List(30) { 120.0 } + List(30) { -100.0 })
        assertEquals(1.2, due.profitFactor!!, 1e-9)
        assertTrue(ShadowRules.promotionDue(due))
        assertFalse(ShadowRules.promotionDue(ShadowRules.summarize(List(30) { 119.0 } + List(30) { -100.0 })), "PF 1.19")
        assertFalse(ShadowRules.promotionDue(ShadowRules.summarize(List(29) { 120.0 } + List(30) { -100.0 })), "59 trades")
        assertFalse(ShadowRules.promotionDue(ShadowRules.summarize(List(60) { 0.0 })), "net 0")
        assertTrue(ShadowRules.promotionDue(ShadowRules.summarize(List(60) { 5.0 })), "no losing trade")
    }

    @Test fun theLinesAndJarvisWords() {
        val since = LocalDate.of(2026, 10, 6)
        val v = ShadowRules.ORB_V43
        assertEquals("Shadow (V43, no orders): 12 trades since 06 Oct, net −₹3,400 (−₹283 a trade)",
            ShadowRules.line(v, ShadowRules.summarize(List(11) { -300.0 } + -100.0), since))
        assertEquals("Shadow (V43, no orders): 1 trade since 06 Oct, net ₹50 (₹50 a trade)", ShadowRules.line(v, ShadowRules.summarize(listOf(50.0)), since))
        assertEquals("Shadow (V43, no orders): 0 trades since 06 Oct, net ₹0", ShadowRules.line(v, ShadowRules.summarize(emptyList()), since))
        assertEquals("ORB", ShadowRules.armName(v))
        assertEquals("Midday momentum (NIFTY)", ShadowRules.armName(ShadowRules.MOMO_O08))
        assertEquals("re-arm ORB Sweep with S17 on paper?", ShadowRules.askTitle(ShadowRules.SWEEP_S17))
        val due = ShadowRules.summarize(List(30) { 120.0 } + List(30) { -100.0 })
        val ask = ShadowRules.ask(v, due)
        assertTrue(ask.contains("60 closed trades, net ₹600 after charges, profit factor 1.20") && ask.contains("Re-arm ORB with V43 on paper?"), ask)
        assertTrue(ShadowRules.ask(v, ShadowRules.summarize(List(60) { 5.0 })).contains("profit factor no losing trade"))
        assertTrue(ShadowRules.answer(emptyList()).startsWith("No shadow has run yet"))
        val a = ShadowRules.answer(listOf(Triple(v, due, since), Triple(ShadowRules.FADE_R20, ShadowRules.summarize(listOf(-10.0)), since),
            Triple(ShadowRules.SWEEP_S17, due, since)), promoted = setOf(ShadowRules.SWEEP_S17.id))
        assertTrue(a.contains("ORB / ORB Fresh: Shadow (V43, no orders): 60 trades since 06 Oct, net ₹600 (₹10 a trade) - it has met the bar"), a)
        assertTrue(a.contains("Range Fade: Shadow (R20, no orders): 1 trade since 06 Oct, net −₹10 (−₹10 a trade)."), a)
        assertTrue(a.contains("ORB Sweep: Shadow (S17, no orders): 60 trades since 06 Oct, net ₹600 (₹10 a trade) - re-armed on paper with S17"), a)
    }

    @Test fun eachRetiredArmHasItsShadow() {
        assertEquals(ShadowRules.ORB_V43, ShadowRules.forArm(OrbRules.ORB.source))
        assertEquals(ShadowRules.ORB_V43, ShadowRules.forArm(OrbRules.ORB_FRESH.source))
        assertEquals(ShadowRules.SWEEP_S17, ShadowRules.forArm(SweepRules.ARM.source))
        assertEquals(ShadowRules.FADE_R20, ShadowRules.forArm(RangeFadeRules.ARM.source))
        assertNull(ShadowRules.forArm(LiquidityRules.ARM.source))
        RetiredArms.ALL.forEach { assertTrue(ShadowRules.forArm(it.arm.source) != null, it.arm.label) }
        assertEquals(ShadowRules.MOMO_O08, ShadowRules.of("momo_o08"))
        assertNull(ShadowRules.of("nope"))
        assertEquals(9, ShadowRules.ALL.map { it.id }.distinct().size)
        assertEquals(9, ShadowRules.ALL.map { it.label + it.name }.distinct().size, "each shadow's label and name tell it apart")
        assertTrue(ShadowRules.ALL.all { it.description.isNotBlank() && it.underlying.isNotBlank() })
        // The losing-trades study's shadows: two or three a retired arm, the research's own first.
        assertEquals(listOf(ShadowRules.ORB_V43, ShadowRules.ORB_P10), ShadowRules.allForArm(OrbRules.ORB.source))
        assertEquals(listOf(ShadowRules.ORB_V43, ShadowRules.ORBF_P10), ShadowRules.allForArm(OrbRules.ORB_FRESH.source))
        assertEquals(listOf(ShadowRules.SWEEP_S17, ShadowRules.SWEEP_H1, ShadowRules.SWEEP_14), ShadowRules.allForArm(SweepRules.ARM.source))
        assertEquals(listOf(ShadowRules.FADE_R20, ShadowRules.FADE_P10), ShadowRules.allForArm(RangeFadeRules.ARM.source))
        assertTrue(ShadowRules.allForArm(LiquidityRules.ARM.source).isEmpty())
        assertEquals("re-arm ORB Sweep with S14 on paper?", ShadowRules.askTitle(ShadowRules.SWEEP_14))
    }

    @Test fun theRetiredSectionShowsTheBestOfAnArmsShadows() {
        assertEquals(-1, ShadowRules.bestIndex(emptyList()))
        assertEquals(1, ShadowRules.bestIndex(listOf(-500.0, 200.0, 100.0)))
        assertEquals(0, ShadowRules.bestIndex(listOf(0.0, 0.0, 0.0)), "a tie keeps the research's own")
        assertEquals(2, ShadowRules.bestIndex(listOf(-3.0, -2.0, -1.0)))
        assertEquals("Shadow (V43)", ShadowRules.bestOf(1, "Shadow (V43)"))
        assertEquals("Best of 3 shadows · Shadow (S17)", ShadowRules.bestOf(3, "Shadow (S17)"))
    }

    @Test fun theStudysShadowsExitOnTheirCandlesAndOnTheLtpOnlyAtTheClose() {
        val p = open(ShadowRules.SWEEP_H1, 1)
        assertNull(ShadowRules.exitReason(p, 1.0, at(14, 0), emptyList()), "the walk on the candles decides, not the LTP")
        assertEquals("session_end", ShadowRules.exitReason(p, 300.0, at(15, 10), emptyList()))
        assertEquals("session_end", ShadowRules.exitReason(open(ShadowRules.ORB_P10, 1), null, at(15, 11), emptyList()))
    }

    @Test fun jarvisNamesEachShadowsTopLossReason() {
        val since = LocalDate.of(2026, 10, 6)
        val g = ShadowStudy.LossGroup.entries
        val a = ShadowRules.answer(listOf(Triple(ShadowRules.SWEEP_H1, ShadowRules.summarize(listOf(-10.0, -20.0, 5.0)), since),
            Triple(ShadowRules.ORB_P10, ShadowRules.summarize(emptyList()), since)),
            reasons = mapOf(ShadowRules.SWEEP_H1.id to listOf(ShadowStudy.LossGroup.WRONG_WAY, ShadowStudy.LossGroup.WRONG_WAY), "gone" to g))
        assertTrue(a.contains("ORB Sweep: Shadow (SH1, no orders): 3 trades since 06 Oct, net −₹25 (−₹8 a trade). Its losers: mostly wrong way " +
            "from the start (2 of 2 losers)."), a)
        assertTrue(a.contains("ORB: Shadow (OP10, no orders): 0 trades since 06 Oct, net ₹0. A shadow"), a)
    }
}
