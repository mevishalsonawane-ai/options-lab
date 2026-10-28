package com.optionslab.engine.signals

import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import com.optionslab.engine.signals.FlowRules.Setup
import com.optionslab.engine.signals.FlowRules.Stage
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IraThinker's flow, ported (28 Oct): the scanner's setups, the IraScore, the Time Machine's odds gate, the risk gate, the alert
 * gate (score, points, width, India VIX first), and the edge cases - empty data, gaps, holidays, expiry day, market closed, stale.
 */
class FlowRulesTest {
    private val day = LocalDate.of(2026, 10, 28)

    private fun sec(d: LocalDate, h: Int, m: Int): Long = d.atTime(h, m).atZone(IST).toEpochSecond()
    private fun bar(d: LocalDate, h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double, v: Long = 0) =
        Upstox.Bar(sec(d, h, m), o, hi, lo, c, v, 0)

    /** The opening range 24000-24100 (09:15, 09:20, 09:25), a 09:30 bar inside it, then [after] closes from 09:35 on. */
    private fun session(d: LocalDate, after: List<Double>, orLo: Double = 24_000.0, orHi: Double = 24_100.0): List<Upstox.Bar> {
        val mid = (orLo + orHi) / 2
        val w = orHi - orLo
        val out = arrayListOf(
            bar(d, 9, 15, mid, orHi, mid - 0.1 * w, mid + 0.1 * w),
            bar(d, 9, 20, mid + 0.1 * w, mid + 0.2 * w, orLo, mid - 0.1 * w),
            bar(d, 9, 25, mid - 0.1 * w, mid + 0.05 * w, mid - 0.2 * w, mid),
            // The 09:30 bar closes just under the midpoint (IraThinker's walk counts the break bar's own high against the stop).
            bar(d, 9, 30, mid, mid + 0.1 * w, mid - 0.1 * w, mid - 0.05 * w),
        )
        var prev = mid - 0.05 * w
        for ((i, c) in after.withIndex()) {
            val m = 35 + 5 * i
            out += bar(d, 9 + m / 60, m % 60, prev, maxOf(prev, c) + 2, minOf(prev, c) - 2, c)
            prev = c
        }
        return out
    }

    /** A session that breaks down at 09:35 and keeps falling 30 points a bar to 15:25 (a breakdown that reaches its target). */
    private fun falling(d: LocalDate) = session(d, (0 until 71).map { 23_980.0 - 30 * it })
    /** A session that breaks down at 09:35 and then rallies through its stop. */
    private fun rallying(d: LocalDate) = session(d, listOf(23_980.0) + (1 until 71).map { 23_980.0 + 30 * it })

    private fun pastDays(n: Int): List<LocalDate> = generateSequence(day.minusDays(1)) { it.minusDays(1) }
        .filter { it.dayOfWeek.value <= 5 }.take(n).toList().reversed()

    /** India VIX: its range 13.0-13.2, broken UP by the 09:30 bar's close. */
    private val vixUp = listOf(
        bar(day, 9, 15, 13.1, 13.2, 13.05, 13.1), bar(day, 9, 20, 13.1, 13.15, 13.0, 13.1), bar(day, 9, 25, 13.1, 13.18, 13.05, 13.15),
        bar(day, 9, 30, 13.15, 13.4, 13.1, 13.35), bar(day, 9, 35, 13.35, 13.5, 13.3, 13.45))

    private val todayDown = session(day, listOf(23_980.0))
    private val now = sec(day, 9, 41)

    // ---- the scanner -------------------------------------------------------------------------------------------------

    @Test fun scannerFindsTheBreakdownAndItsPlan() {
        val s = FlowRules.scan("NIFTY", todayDown, day, prevClose = 24_100.0)
        assertTrue(s.orReady)
        assertEquals(24_100.0, s.orHigh); assertEquals(24_000.0, s.orLow)
        assertEquals(Setup.ORB_DOWN, s.setup)
        assertEquals(23_980.0, s.entry); assertEquals(24_050.0, s.stop); assertEquals(23_910.0, s.target, 1e-9)
        assertEquals(sec(day, 9, 35), s.breakTs)
        assertEquals(23_840.0, FlowRules.alertTarget(s), 1e-9, "the alert trades 2R")
        assertEquals((23_980.0 / 24_100.0 - 1) * 100, s.dayPct, 1e-9)
        assertTrue(s.hasPlan()); assertFalse(s.up)
    }

    @Test fun scannerFindsTheBreakout() {
        val s = FlowRules.scan("BANKNIFTY", session(day, listOf(24_120.0)), day)
        assertEquals(Setup.ORB_UP, s.setup)
        assertEquals(24_120.0 + 70, s.target, 1e-9)
        assertTrue(s.dayPct.isNaN(), "no previous close known: no day move")
    }

    @Test fun insideTheRangeHasNoPlan_andDailyContextNamesTheTrend() {
        val inside = session(day, listOf(24_060.0, 24_070.0))
        val s = FlowRules.scan("NIFTY", inside, day)
        assertEquals(Setup.NONE, s.setup); assertFalse(s.hasPlan())
        val up = (1..25).map { day.minusDays(30L - it) to 23_000.0 + it }
        assertEquals(Setup.ABOVE_20DMA, FlowRules.scan("NIFTY", inside, day, daily = up).setup)
        val down = (1..25).map { day.minusDays(30L - it) to 26_000.0 - it }
        val s2 = FlowRules.scan("NIFTY", inside, day, daily = down)
        assertEquals(Setup.BELOW_20DMA, s2.setup)
        assertFalse(s2.dma20.isNaN()); assertFalse(s2.rsi.isNaN())
        assertEquals(Setup.NONE, FlowRules.scan("NIFTY", inside, day, daily = up.take(10)).setup, "under 21 closes: no daily context")
    }

    @Test fun oversoldBounceIsAWatchSetupWithNoPlan() {
        val inside = session(day, listOf(24_060.0))
        val falling = (1..25).map { day.minusDays(30L - it) to 26_000.0 - 50.0 * it }
        val s = FlowRules.scan("NIFTY", inside, day, prevClose = 23_800.0, daily = falling)
        assertEquals(Setup.OVERSOLD_BOUNCE, s.setup)
        assertFalse(s.hasPlan())
        val score = FlowRules.liteScore(s)
        assertTrue(score in 35..68)
        assertTrue(s.why.first().startsWith("Oversold bounce"))
        val v = FlowRules.judge("NIFTY", inside, emptyList(), vixUp, day, now, 1e6, 23_800.0, falling)
        assertEquals(Stage.SCANNER, v.failed); assertTrue(v.why.contains("oversold"))
    }

    @Test fun emptyAndGappyData() {
        assertEquals("no bars", FlowRules.scan("NIFTY", emptyList(), day).error)
        val one = listOf(bar(day, 9, 15, 1.0, 2.0, 0.5, 1.5), bar(day, 9, 30, 1.5, 3.0, 1.4, 2.9))
        val s = FlowRules.scan("NIFTY", one, day)
        assertFalse(s.orReady, "a single opening-range bar is not a range")
        assertEquals(Setup.NONE, s.setup)
        // A holiday: today has no bars; the scan reads the last session, and its break is never taken as today's.
        val yesterday = day.minusDays(1)
        val old = FlowRules.scan("NIFTY", session(yesterday, listOf(23_980.0)), day)
        assertEquals(Setup.ORB_DOWN, old.setup)
        val v = FlowRules.judge("NIFTY", session(yesterday, listOf(23_980.0)), emptyList(), vixUp, day, now, 1e6)
        assertEquals(Stage.SCANNER, v.failed); assertTrue(v.why.contains("earlier session"))
        // A NaN close is skipped (never a break on a missing price).
        val nan = session(day, emptyList()) + bar(day, 9, 35, Double.NaN, Double.NaN, Double.NaN, Double.NaN) + bar(day, 9, 40, 24_000.0, 24_001.0, 23_970.0, 23_975.0)
        assertEquals(sec(day, 9, 40), FlowRules.scan("NIFTY", nan, day).breakTs)
    }

    @Test fun priceActionFingerprint() {
        // A strong red engulfing break bar, with volume (VWAP side).
        val t = session(day, emptyList()).map { it.copy(volume = 100) } + bar(day, 9, 35, 24_060.0, 24_062.0, 23_950.0, 23_955.0, 500)
        val s = FlowRules.scan("NIFTY", t, day)
        assertTrue(s.paBody > 0.9); assertTrue(s.paEngulf); assertTrue(s.paThrust > 0)
        assertEquals(-1, s.paVwap)
        assertTrue(s.runMove > 0)
        // Swing structure: two rising pivot highs and lows, and two falling.
        val rising = (0 until 9).map { i -> val b = 100.0 + (if (i % 2 == 0) 0.0 else 5.0) + i * 2; bar(day, 10, i * 5, b, b + 1, b - 1, b) }
        assertEquals(1, FlowRules.dayStruct(rising, rising.size - 1))
        val falling = (0 until 9).map { i -> val b = 200.0 - (if (i % 2 == 0) 0.0 else 5.0) - i * 2; bar(day, 10, i * 5, b, b + 1, b - 1, b) }
        assertEquals(-1, FlowRules.dayStruct(falling, falling.size - 1))
        assertEquals(0, FlowRules.dayStruct(rising.take(3), 2))
        assertTrue(FlowRules.runMoveOf(t, 0, true).isNaN())
        val blank = FlowRules.Scan("NIFTY"); FlowRules.computePa(blank, t, 99, true); assertTrue(blank.paBody.isNaN())
    }

    @Test fun rsi() {
        assertTrue(FlowRules.rsi14(listOf(1.0, 2.0)).isNaN())
        assertEquals(100.0, FlowRules.rsi14((1..15).map { it.toDouble() }))
        assertEquals(0.0, FlowRules.rsi14((1..15).map { 100.0 - it }), 1e-9)
    }

    // ---- IraScore ------------------------------------------------------------------------------------------------------

    @Test fun liteScoreAddsItsParts() {
        val s = FlowRules.scan("NIFTY", todayDown, day)
        // 25 + strength 9 (0.2x the range beyond) + 10 (no 20DMA) + 8 (no RSI) + 12 (width 0.42%) + 8 (early break)
        assertEquals(72, FlowRules.liteScore(s))
        assertTrue(s.why.contains("Early break - full session ahead"))
        s.dma20 = 25_000.0; s.rsi = 40.0       // a breakdown below its average, RSI 60 the put's way: the power zone
        assertEquals(72 - 10 + 18 - 8 + 15, FlowRules.liteScore(s))
        assertTrue(s.why.contains("Trend agrees (20DMA)") && s.why.contains("RSI in the power zone"))
        s.dma20 = 20_000.0; s.rsi = 10.0       // against the trend, RSI stretched
        assertEquals(72 - 10 + 4 - 8 + 3, FlowRules.liteScore(s))
        s.rsi = 20.0; assertEquals(72 - 10 + 4 - 8 + 8, FlowRules.liteScore(s))
        s.rsi = 60.0; assertEquals(72 - 10 + 4 - 8 + 9, FlowRules.liteScore(s))
        // Width buckets and the break's time of day.
        val wide = FlowRules.scan("NIFTY", session(day, listOf(23_500.0), 23_600.0, 24_100.0), day)
        assertEquals(Setup.ORB_DOWN, wide.setup)
        val late = FlowRules.scan("NIFTY", session(day, List(30) { 24_050.0 } + 23_990.0), day)
        assertTrue(FlowRules.istTime(late.breakTs) >= LocalTime.of(12, 0))
        val later = FlowRules.scan("NIFTY", session(day, List(50) { 24_050.0 } + 23_990.0), day)
        assertTrue(FlowRules.istTime(later.breakTs) >= LocalTime.of(13, 0))
        assertTrue(FlowRules.liteScore(late) > FlowRules.liteScore(later))
        val narrow = FlowRules.scan("NIFTY", session(day, listOf(24_039.0), 24_040.0, 24_060.0), day)
        val mid = FlowRules.scan("NIFTY", todayDown, day)
        assertTrue(FlowRules.liteScore(mid) > FlowRules.liteScore(narrow))
        assertTrue(FlowRules.liteScore(mid) > FlowRules.liteScore(wide) - 20)
        // A strong break says so.
        val strong = FlowRules.scan("NIFTY", session(day, listOf(23_920.0)), day)
        FlowRules.liteScore(strong)
        assertTrue(strong.why.any { it.startsWith("Strong break") })
        // No plan.
        assertEquals(25, FlowRules.liteScore(FlowRules.Scan("NIFTY")))
        assertEquals(45, FlowRules.liteScore(FlowRules.Scan("NIFTY").apply { setup = Setup.ABOVE_20DMA }))
        assertEquals(35, FlowRules.liteScore(FlowRules.Scan("NIFTY").apply { setup = Setup.BELOW_20DMA }))
    }

    @Test fun fullScoreHalvesLiteAndOdds() {
        val s = FlowRules.scan("NIFTY", todayDown, day)
        assertEquals(72, FlowRules.fullScore(s, null))
        val o = FlowRules.Odds().apply { targetFirst = 60.0; analogFires = 4; analogTarget = 3; analogStop = 1 }
        assertEquals(Math.round(0.5 * 72 + 0.5 * (42 + 22.5)).toInt(), FlowRules.fullScore(s, o))
        o.analogTarget = 0; o.analogStop = 0
        assertEquals(Math.round(0.5 * 72 + 0.5 * (42 + 12)).toInt(), FlowRules.fullScore(s, o))
        o.analogFires = 2; o.analogTarget = 1; o.analogStop = 1
        assertEquals(Math.round(0.5 * 72 + 0.5 * (42 + 7.5 + 5)).toInt(), FlowRules.fullScore(s, o))
        o.analogTarget = 0; o.analogStop = 0
        assertEquals(Math.round(0.5 * 72 + 0.5 * (42 + 10)).toInt(), FlowRules.fullScore(s, o))
        o.analogFires = 0
        assertEquals(Math.round(0.5 * 72 + 0.5 * (42 + 12)).toInt(), FlowRules.fullScore(s, o))
    }

    // ---- the Time Machine ----------------------------------------------------------------------------------------------

    @Test fun timeMachineOddsFromTheIndexsOwnSessions() {
        val s = FlowRules.scan("NIFTY", todayDown, day)
        val history = pastDays(5).flatMap { falling(it) }
        val o = FlowRules.timeMachine(history + todayDown, s, 500)
        assertTrue(o.ran); assertTrue(o.returns >= 100)
        assertEquals(5, o.analogFires); assertEquals(5, o.analogTarget)
        assertTrue(o.targetFirst > o.stopFirst, "a falling tape replays to the target first")
        assertTrue(o.passes)
        assertEquals(100.0, o.targetFirst + o.stopFirst + o.neither, 1e-6)
        assertTrue(o.simTarget > 99)
        // The same input gives the same odds (a fixed seed, as IraThinker's).
        assertEquals(o.targetFirst, FlowRules.timeMachine(history + todayDown, s, 500).targetFirst)
        // Rallies through the stop: stop first, the odds gate fails.
        val bad = FlowRules.timeMachine(pastDays(5).flatMap { rallying(it) } + todayDown, s, 500)
        assertTrue(bad.ran); assertEquals(5, bad.analogStop)
        assertFalse(bad.passes)
    }

    @Test fun timeMachineNeedsHistoryAndAPlan() {
        val s = FlowRules.scan("NIFTY", todayDown, day)
        val o = FlowRules.timeMachine(todayDown, s, 100)
        assertFalse(o.ran); assertFalse(o.passes); assertEquals(0, o.analogFires)
        assertFalse(FlowRules.timeMachine(todayDown, FlowRules.Scan("NIFTY"), 100).ran)
        val flat = FlowRules.Scan("NIFTY").apply { entry = 1.0; stop = 1.0; setup = Setup.ORB_DOWN }
        assertFalse(FlowRules.timeMachine(todayDown, flat, 100).ran)
        // Past sessions too short, or with no break, or breaking the other way, add no analog.
        val other = pastDays(3).map { session(it, listOf(24_150.0) + List(10) { 24_150.0 }) }.flatten()
        val short = pastDays(1).map { session(it, emptyList()) }.flatten()
        val none = pastDays(2).map { session(it, List(10) { 24_050.0 }) }.flatten()
        assertEquals(0, FlowRules.timeMachine(other + short + none + todayDown, s, 100).analogFires)
        // A past breakdown that neither stops nor targets.
        val drift = session(day.minusDays(1), List(12) { 23_985.0 })
        val od = FlowRules.timeMachine(drift + todayDown, s, 100)
        assertEquals(1, od.analogNeither)
    }

    @Test fun featureDistance() {
        assertEquals(Double.POSITIVE_INFINITY, FlowRules.featDistance(FlowRules.Feat(), FlowRules.Feat()))
        val s = FlowRules.scan("NIFTY", todayDown, day)
        val f = FlowRules.featOf(s, 70)
        assertEquals(0.0, FlowRules.featDistance(f, f))
        val g = FlowRules.featOf(s, 70).apply { struct = 1; vwap = 1; engulf = 1 }
        val h = FlowRules.featOf(s, 70).apply { struct = -1; vwap = -1 }
        assertTrue(FlowRules.featDistance(g, h) > FlowRules.featDistance(f, g))
        assertEquals(1.0, FlowRules.kernelW(0.0))
    }

    // ---- the risk gate -------------------------------------------------------------------------------------------------

    @Test fun riskGate() {
        val s = FlowRules.scan("NIFTY", todayDown, day)
        val p = FlowRules.budget(s, 1_000_000.0, 1.0, LocalTime.of(9, 41))
        assertTrue(p.pass); assertEquals(142, p.qty); assertEquals(1.0, p.rr, 1e-9)
        assertFalse(FlowRules.budget(s, 1_000_000.0, 1.0, LocalTime.of(9, 30)).pass, "09:30 itself is outside (after 09:30)")
        assertFalse(FlowRules.budget(s, 1_000_000.0, 1.0, LocalTime.of(15, 0)).pass)
        assertFalse(FlowRules.budget(s, 1_000.0, 1.0, LocalTime.of(10, 0)).pass, "qty 0: the budget cannot carry the risk")
        assertFalse(FlowRules.budget(s, Double.NaN, 1.0, LocalTime.of(10, 0)).pass, "capital unknown")
        val thin = FlowRules.Scan("NIFTY").apply { setup = Setup.ORB_DOWN; entry = 100.0; stop = 110.0; target = 95.0 }
        val tp = FlowRules.budget(thin, 1e6, 1.0, LocalTime.of(10, 0))
        assertFalse(tp.pass); assertTrue(tp.checks.any { it.contains("below 1R") })
        assertFalse(FlowRules.budget(FlowRules.Scan("NIFTY"), 1e6, 1.0, LocalTime.of(10, 0)).pass)
        assertFalse(FlowRules.budget(FlowRules.Scan("NIFTY").apply { entry = 1.0; stop = 1.0 }, 1e6, 1.0, LocalTime.of(10, 0)).pass)
    }

    // ---- the alert gate ------------------------------------------------------------------------------------------------

    @Test fun alertGate() {
        val s = FlowRules.scan("NIFTY", todayDown, day)
        assertTrue(FlowRules.alertWorthy(s, 66)); assertFalse(FlowRules.alertWorthy(s, 65), "above 65, not at it")
        assertFalse(FlowRules.alertWorthy(FlowRules.Scan("NIFTY"), 99))
        assertFalse(FlowRules.alertWorthy(FlowRules.Scan("NIFTY").apply { setup = Setup.OVERSOLD_BOUNCE; entry = 1.0; stop = 0.5 }, 99))
        val small = FlowRules.scan("NIFTY", session(day, listOf(23_999.0), 24_000.0, 24_010.0), day)
        assertFalse(FlowRules.alertWorthy(small, 99), "under 10 points to its target")
        val wide = FlowRules.scan("NIFTY", session(day, listOf(23_500.0), 23_600.0, 24_100.0), day)
        assertFalse(FlowRules.rangeNarrow(wide)); assertFalse(FlowRules.alertWorthy(wide, 99))
        assertFalse(FlowRules.rangeNarrow(FlowRules.Scan("NIFTY")))
        // India VIX first, and breakdowns only.
        assertTrue(FlowRules.vixConfirms(Setup.ORB_UP, s.breakTs - 300, s))
        assertTrue(FlowRules.vixConfirms(Setup.ORB_UP, s.breakTs, s))
        assertFalse(FlowRules.vixConfirms(Setup.ORB_UP, s.breakTs + 300, s), "VIX broke after the index")
        assertFalse(FlowRules.vixConfirms(Setup.ORB_DOWN, s.breakTs - 300, s))
        assertFalse(FlowRules.vixConfirms(Setup.ORB_UP, 0, s))
        val up = FlowRules.scan("NIFTY", session(day, listOf(24_120.0)), day)
        assertFalse(FlowRules.vixConfirms(Setup.ORB_UP, up.breakTs - 300, up), "breakout buys never pass")
        // Closed bar, fresh signal.
        assertFalse(FlowRules.breakBarClosed(s, s.breakTs + 299)); assertTrue(FlowRules.breakBarClosed(s, s.breakTs + 300))
        assertTrue(FlowRules.fresh(s.breakTs, s.breakTs + 900)); assertFalse(FlowRules.fresh(s.breakTs, s.breakTs + 901))
        assertFalse(FlowRules.fresh(0, 1))
    }

    // ---- the whole flow -----------------------------------------------------------------------------------------------

    private val history = pastDays(5).flatMap { falling(it) }

    @Test fun aPlanThatPassesTheFlowIsTaken() {
        val v = FlowRules.judge("NIFTY", todayDown, history, vixUp, day, now, 1_000_000.0, 24_100.0)
        assertTrue(v.take, v.why); assertNull(v.failed); assertEquals(Stage.RISK_GATE, v.reached)
        assertTrue(v.put)
        assertEquals(23_980.0, v.entry); assertEquals(24_050.0, v.stop); assertEquals(23_840.0, v.target!!, 1e-9)
        assertTrue(v.score!! > FlowRules.MIN_SCORE); assertEquals(72, v.lite)
        assertNotNull(v.odds); assertNotNull(v.plan)
        assertTrue(v.why.startsWith("passed the flow"))
    }

    @Test fun eachStageRefuses() {
        fun j(today: List<Upstox.Bar> = todayDown, hist: List<Upstox.Bar> = history, vix: List<Upstox.Bar> = vixUp, at: Long = now,
              index: String = "NIFTY", capital: Double = 1e6) = FlowRules.judge(index, today, hist, vix, day, at, capital, paths = 300)
        for (u in listOf("RELIANCE", "CRUDEOIL", "BTCUSD", "SENSEX", "MIDCPNIFTY", "GOLDM")) {
            assertEquals(Stage.SCANNER, j(index = u).failed, u); assertTrue(j(index = u).why.contains("indices only"))
        }
        assertEquals("no Zerodha bars today", j(today = emptyList()).why)
        assertTrue(j(today = todayDown.take(1)).why.contains("not complete"))
        assertTrue(j(today = session(day, listOf(24_050.0))).why.contains("inside"))
        assertTrue(j(at = sec(day, 9, 39)).why.contains("not closed"))
        assertTrue(j(at = sec(day, 9, 51)).why.contains("too late"), "a stale signal is skipped")
        // IraScore: the gate.
        assertEquals(Stage.IRASCORE, j(today = session(day, listOf(23_999.0), 24_000.0, 24_010.0)).failed)
        assertTrue(j(today = session(day, listOf(23_500.0), 23_600.0, 24_100.0)).why.contains("wider"))
        val up = j(today = session(day, listOf(24_120.0)), hist = pastDays(5).flatMap { d -> session(d, (0 until 71).map { 24_120.0 + 30 * it }) })
        assertEquals(Stage.IRASCORE, up.failed); assertTrue(up.why.contains("breakdowns only"))
        assertTrue(j(vix = emptyList()).why.contains("no India VIX"))
        assertTrue(j(vix = vixUp.take(3)).why.contains("had not broken up"))
        // A weak score: a late, narrow-ish break against a rallying history.
        val weak = j(today = session(day, List(50) { 24_050.0 } + 23_990.0), at = sec(day, 13, 51), hist = pastDays(5).flatMap { rallying(it) })
        assertEquals(Stage.IRASCORE, weak.failed); assertTrue(weak.why.contains("IraScore"))
        // The Time Machine: no history yet, and odds against.
        val noHist = j(hist = emptyList())
        assertEquals(Stage.TIME_MACHINE, noHist.failed); assertTrue(noHist.why.contains("not enough Zerodha history"))
        // The risk gate: a break found at 14:55 and judged at 15:01.
        val lateDay = session(day, List(64) { 24_050.0 } + 23_990.0)
        val lateV = FlowRules.scan(FlowRules.VIX, vixUp, day)
        assertEquals(Setup.ORB_UP, lateV.setup)
        val risk = j(today = lateDay, at = sec(day, 15, 1) + 60, hist = pastDays(8).flatMap { falling(it) })
        assertTrue(risk.failed == Stage.RISK_GATE || risk.failed == Stage.IRASCORE, risk.why)
        val poor = j(capital = 100.0)
        assertEquals(Stage.RISK_GATE, poor.failed); assertTrue(poor.why.contains("qty 0"))
    }

    @Test fun oddsAgainstFailTheTimeMachine() {
        // Score high enough on its own, but the replays favour the stop: give the score room with daily context.
        val daily = (1..25).map { day.minusDays(40L - it) to 25_000.0 - 10.0 * it }
        val hist = pastDays(5).flatMap { rallying(it) }
        val v = FlowRules.judge("NIFTY", todayDown, hist, vixUp, day, now, 1e6, 24_100.0, daily, paths = 300)
        assertTrue(v.failed == Stage.TIME_MACHINE || v.failed == Stage.IRASCORE, v.why)
        if (v.failed == Stage.TIME_MACHINE) assertTrue(v.why.contains("target first"))
    }

    // ---- exits, guards, the record -------------------------------------------------------------------------------------

    @Test fun indexExits() {
        assertEquals("index stop", FlowRules.indexExit(true, 24_050.0, 24_050.0, 23_840.0))
        assertEquals("index target", FlowRules.indexExit(true, 23_840.0, 24_050.0, 23_840.0))
        assertNull(FlowRules.indexExit(true, 23_900.0, 24_050.0, 23_840.0))
        assertEquals("index stop", FlowRules.indexExit(false, 99.0, 100.0, 120.0))
        assertEquals("index target", FlowRules.indexExit(false, 121.0, 100.0, 120.0))
        assertNull(FlowRules.indexExit(false, 110.0, 100.0, 120.0))
        assertNull(FlowRules.indexExit(true, null, 1.0, 1.0), "a stale or missing price never exits")
        assertNull(FlowRules.indexExit(true, Double.NaN, 1.0, 1.0)); assertNull(FlowRules.indexExit(true, 0.0, 1.0, 1.0))
        assertEquals(80.0, FlowRules.premiumStop(100.0), 1e-9)
        assertEquals(0.05, FlowRules.premiumStop(0.05), 1e-9)
    }

    @Test fun entryGuards() {
        fun r(index: String = "NIFTY", trading: Boolean = true, at: LocalTime = LocalTime.of(10, 0), expiry: Boolean = false, on: Boolean = true,
              kill: Boolean = false, stopped: Boolean = false, lock: String? = null, live: Boolean = true, taken: Int = 0, done: Boolean = false) =
            FlowRules.entryRefusal(index, trading, at, expiry, on, kill, stopped, lock, live, taken, done)
        assertNull(r())
        assertEquals("not an index the flow trades", r(index = "CRUDEOIL"))
        assertEquals("the arm is off", r(on = false))
        assertTrue(r(trading = false)!!.contains("market closed"))
        assertTrue(r(at = LocalTime.of(9, 29))!!.contains("entry window"))
        assertTrue(r(at = LocalTime.of(15, 0))!!.contains("entry window"))
        assertTrue(r(at = LocalTime.of(15, 40))!!.contains("entry window"), "after the F&O close")
        assertTrue(r(expiry = true)!!.contains("expiry day"))
        assertEquals("the kill switch is on", r(kill = true))
        assertEquals("the bot is stopped for today", r(stopped = true))
        assertEquals("day lock", r(lock = "day lock"))
        assertEquals("Zerodha's stream is not live", r(live = false))
        assertTrue(r(taken = 3)!!.contains("signals are taken"))
        assertTrue(r(done = true)!!.contains("already traded"))
        assertTrue(FlowRules.tradable("nifty")); assertFalse(FlowRules.tradable("INDIAVIX"))
    }

    @Test fun sessionRecord() {
        val t = falling(day)
        val back = FlowRules.decode(FlowRules.encode(t))
        assertEquals(t.size, back.size)
        assertEquals(t.first().epochSecond, back.first().epochSecond); assertEquals(t.last().close, back.last().close, 0.005)
        assertEquals(1, FlowRules.decode("1,2,3,1,2;bad;1,x,3,1,2;5,1,0.5,1,1").size, "a damaged entry is skipped")
        var m = emptyMap<LocalDate, String>()
        for (i in 0 until 70) m = FlowRules.keep(m, day.minusDays(70L - i) to "x$i")
        assertEquals(FlowRules.SESSIONS_KEPT, m.size)
        assertEquals("x69", m[day.minusDays(1)])
        assertEquals(m, FlowRules.keep(m, null)); assertEquals(m, FlowRules.keep(m, day to ""))
    }

    @Test fun labelsSayNotProvenAndTheSignalSource() {
        assertTrue(FlowRules.NOT_PROVEN.contains("not proven") && FlowRules.NOT_PROVEN.contains("R110"))
        assertTrue(FlowRules.SIGNAL.contains("index") && FlowRules.SIGNAL.contains("Zerodha"))
        assertEquals(listOf("Scanner", "IraScore", "Time Machine", "Risk Gate"), Stage.entries.map { it.words })
    }
}
