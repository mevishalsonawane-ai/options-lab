package com.optionslab.engine.orb

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CisdNet] on synthetic sessions: the CISD setup bullish and bearish, its 12-bar window and session limit, gaps and missing bars,
 * missing index data, R131's entry filters, the network's view row and EV filter, R131's expiry choice, the 60% lock after ₹1,000,
 * the index stop and the research exit simulator. (The recorded-day parity is in [CisdNetParityTest].)
 */
class CisdNetTest {
    private val yday = LocalDate.of(2026, 10, 28)
    private val today = LocalDate.of(2026, 10, 29)
    private val n = PassedArms.Session.N

    private fun flat(p: Double) = doubleArrayOf(p, p + 5, p - 5, p)

    /** A session from 5-minute bars (open, high, low, close) starting at bar [from]; null = a bar with no minute. */
    private fun day(date: LocalDate, bars: List<DoubleArray?>, from: Int = 0): PassedArms.DayBars {
        val o = DoubleArray(n) { Double.NaN }; val h = o.copyOf(); val l = o.copyOf(); val c = o.copyOf()
        for ((i, b) in bars.withIndex()) {
            if (b == null) continue
            val r0 = (from + i) * 5
            val mid = (b[0] + b[3]) / 2
            for (k in 0 until 5) {
                o[r0 + k] = if (k == 0) b[0] else mid
                c[r0 + k] = if (k == 4) b[3] else mid
                h[r0 + k] = b[1]; l[r0 + k] = b[2]
            }
        }
        return PassedArms.DayBars(date, o, h, l, c)
    }

    /** Yesterday: flat at 1,000 with one swing high (1,050) at bar 40. */
    private fun prior(peak: Boolean = true) = day(yday, (0 until 75).map { if (peak && it == 40) doubleArrayOf(1000.0, 1050.0, 995.0, 1000.0) else flat(1000.0) })

    /** Today: flat, then two red bars dropping 45 (2 ATR = 20), a green bar, then [conf] closing above the run's open (1,000). */
    private fun setupBars(conf: DoubleArray = doubleArrayOf(990.0, 1008.0, 988.0, 1005.0)): List<DoubleArray?> =
        (0 until 10).map { flat(1000.0) } + listOf(doubleArrayOf(1000.0, 1001.0, 975.0, 980.0), doubleArrayOf(980.0, 981.0, 955.0, 960.0),
            doubleArrayOf(960.0, 992.0, 958.0, 990.0), conf)

    private fun mirror(b: List<DoubleArray?>) = b.map { x -> x?.let { doubleArrayOf(2000 - it[0], 2000 - it[2], 2000 - it[1], 2000 - it[3]) } }

    private fun sides(vararg d: PassedArms.DayBars) = CisdNet.Sides(d.toList())

    // ---- the setup --------------------------------------------------------------------------------------------------------

    @Test fun aBullishCisdIsFoundWithItsStopAndTarget() {
        val s = sides(prior(), day(today, setupBars()))
        val all = CisdNet.candidates(s)
        val c = all.single()
        assertEquals(1, c.side); assertTrue(c.call); assertEquals(1, c.d)
        assertEquals(70, c.r0, "the minute after bar 13 (09:20 + 65 = 10:25)")
        assertEquals(955.0 - 0.0002 * 955.0, c.stop, 1e-9)
        assertEquals(1000.0, c.level, 1e-9)
        assertNull(CisdNet.check(s, c, all, 1005.0))
        assertEquals(1050.0, CisdNet.structTarget(s, 1, c.d, c.r0, 1005.0))
    }

    @Test fun aBearishCisdIsTheMirror() {
        val p = prior()
        val s = sides(day(yday, mirror((0 until 75).map { if (it == 40) doubleArrayOf(1000.0, 1050.0, 995.0, 1000.0) else flat(1000.0) })),
            day(today, mirror(setupBars())))
        val all = CisdNet.candidates(s)
        val c = all.single()
        assertEquals(-1, c.side); assertFalse(c.call); assertEquals(70, c.r0)
        assertEquals(1045.0 + 0.0002 * 1045.0, c.stop, 1e-9, "a put's stop is above the rally's high")
        assertEquals(1000.0, c.level, 1e-9)
        assertNull(CisdNet.check(s, c, all, 995.0))
        assertEquals(-950.0, CisdNet.structTarget(s, -1, c.d, c.r0, -995.0), "the swing low at 950, in the put side's coordinates")
        assertEquals(75 * 5, p.count)
    }

    @Test fun noCloseBeyondTheLevelWithin12BarsIsNoSetup() {
        // The run is bars 10-11; a confirming close may come up to bar 23 (12 bars after the run's last bar).
        val weak = setupBars(doubleArrayOf(990.0, 998.0, 985.0, 995.0))
        val late = weak + (0 until 10).map { flat(995.0) } + listOf(doubleArrayOf(995.0, 1012.0, 994.0, 1010.0))
        assertTrue(CisdNet.candidates(sides(prior(), day(today, late))).isEmpty(), "bar 24: too late")
        val inTime = weak + (0 until 9).map { flat(995.0) } + listOf(doubleArrayOf(995.0, 1012.0, 994.0, 1010.0))
        assertEquals(listOf(24 * 5), CisdNet.candidates(sides(prior(), day(today, inTime))).map { it.r0 }, "bar 23, the 12th after the run")
    }

    @Test fun aRunNeedsTwoRedBarsAndTwoAtrAndOneSession() {
        val oneRed = (0 until 10).map { flat(1000.0) } + listOf(doubleArrayOf(1000.0, 1001.0, 950.0, 960.0), doubleArrayOf(960.0, 1010.0, 958.0, 1005.0))
        assertTrue(CisdNet.candidates(sides(prior(), day(today, oneRed))).none { it.side == 1 })
        val small = (0 until 10).map { flat(1000.0) } + listOf(doubleArrayOf(1000.0, 1001.0, 992.0, 995.0), doubleArrayOf(995.0, 996.0, 985.0, 990.0),
            doubleArrayOf(990.0, 1004.0, 989.0, 1002.0))
        assertTrue(CisdNet.candidates(sides(prior(), day(today, small))).none { it.side == 1 }, "a drop of 15 is under 2 ATR (20)")
        // The run ends yesterday; today's first bar closes above it: a new session drops the setup.
        val yBars = (0 until 73).map { flat(1000.0) } + listOf(doubleArrayOf(1000.0, 1001.0, 975.0, 980.0), doubleArrayOf(980.0, 981.0, 955.0, 960.0))
        val tBars = listOf(doubleArrayOf(990.0, 1010.0, 988.0, 1005.0)) + (1 until 20).map { flat(1005.0) }
        assertTrue(CisdNet.candidates(sides(day(yday, yBars), day(today, tBars))).none { it.side == 1 })
    }

    @Test fun gapsAndMissingDataAreHandled() {
        // A minute missing inside a bar keeps the bar; a whole bar missing is left out and the chart runs on.
        val bars = setupBars().toMutableList<DoubleArray?>()
        bars.add(12, null)                                            // no minute at all in bar 12; the confirming bar is now bar 14
        val d = day(today, bars)
        d.c[13 * 5 + 2] = Double.NaN; d.o[13 * 5 + 2] = Double.NaN; d.h[13 * 5 + 2] = Double.NaN; d.l[13 * 5 + 2] = Double.NaN
        val c = CisdNet.candidates(sides(prior(), d)).single()
        assertEquals(75, c.r0)
        val ch = CisdNet.chart(listOf(CisdNet.sideDay(d, 1)))
        assertEquals(14, ch.size, "13 bars of today plus the confirming one; the empty bar is skipped")
        assertFalse(ch.same(-1, 0)); assertFalse(ch.same(0, ch.size))
        // No index data today, or too few bars for the ATR: nothing.
        assertTrue(CisdNet.candidates(sides(prior(), day(today, emptyList()))).isEmpty())
        assertTrue(CisdNet.candidates(sides(day(today, setupBars()))).isEmpty(), "10 bars before the run: no 14-bar ATR yet")
        assertTrue(CisdNet.candidates(sides(day(today, emptyList()))).isEmpty())
        assertTrue(CisdNet.swingHighs(doubleArrayOf(1.0, 2.0, 3.0), 3).isEmpty())
    }

    @Test fun stalePricesOnlyRaiseNothing() {
        // A stale feed: the same price minute after minute (no red, no green bars) never makes a setup.
        val stale = (0 until 75).map { doubleArrayOf(1000.0, 1000.0, 1000.0, 1000.0) }
        assertTrue(CisdNet.candidates(sides(prior(), day(today, stale))).isEmpty())
    }

    // ---- R131's entry filters ------------------------------------------------------------------------------------------------

    @Test fun theEntryFiltersRefuseInR131sOrder() {
        val s = sides(prior(), day(today, setupBars()))
        val all = CisdNet.candidates(s)
        val c = all.single()
        assertEquals("both sides set up at one minute", CisdNet.check(s, c, all + c.copy(side = -1), 1005.0))
        assertEquals("after 15:00", CisdNet.check(s, c.copy(r0 = CisdNet.LAST_ENTRY + 1), all, 1005.0))
        assertNull(CisdNet.check(s, c.copy(r0 = CisdNet.LAST_ENTRY), listOf(c.copy(r0 = CisdNet.LAST_ENTRY)), 1005.0))
        assertEquals("no index price at the entry", CisdNet.check(s, c, all, Double.NaN))
        assertEquals("the index is already past the stop", CisdNet.check(s, c, all, 950.0))
        assertEquals("no swing target beyond the entry", CisdNet.check(s, c, all, 1060.0))
        val noPeak = sides(prior(peak = false), day(today, setupBars()))
        assertEquals("no swing target beyond the entry", CisdNet.check(noPeak, CisdNet.candidates(noPeak).single(), CisdNet.candidates(noPeak), 1005.0))
    }

    @Test fun aSwingAlreadyTradedThroughIsNoTarget() {
        val bars = (0 until 75).map { when (it) { 40 -> doubleArrayOf(1000.0, 1050.0, 995.0, 1000.0); 50 -> doubleArrayOf(1000.0, 1040.0, 995.0, 1000.0)
            60 -> doubleArrayOf(1000.0, 1051.0, 995.0, 1000.0); else -> flat(1000.0) } }
        val s = sides(day(yday, bars), day(today, setupBars()))
        val c = CisdNet.candidates(s).single()
        // 1,040 (bar 50) and 1,050 (bar 40) were traded above by bar 60's 1,051: the target is 1,051 itself.
        assertEquals(1051.0, CisdNet.structTarget(s, 1, c.d, c.r0, 1005.0))
        assertNull(CisdNet.structTarget(s, 1, c.d, c.r0, 1051.0), "nothing above 1,051")
        // Sessions older than 4 back are not read.
        val old = (1..5).map { k -> day(yday.minusDays(k.toLong()), (0 until 75).map { flat(1000.0) }) }.reversed()
        val s2 = CisdNet.Sides(listOf(day(yday.minusDays(6), (0 until 75).map { if (it == 40) doubleArrayOf(1000.0, 1050.0, 995.0, 1000.0) else flat(1000.0) })) +
            old + day(today, setupBars()))
        val c2 = CisdNet.candidates(s2).single()
        assertNull(CisdNet.structTarget(s2, 1, c2.d, c2.r0, 1005.0), "the swing is 6 sessions back")
    }

    // ---- the network's filter ------------------------------------------------------------------------------------------------

    @Test fun theViewIsTheLatestFiveMinuteRowAtMostFiveMinutesOld() {
        assertNull(CisdNet.viewRow(4)); assertEquals(4, CisdNet.viewRow(5)); assertEquals(4, CisdNet.viewRow(9)); assertEquals(9, CisdNet.viewRow(10))
        assertEquals(69, CisdNet.viewRow(70))
        assertEquals(339, CisdNet.viewRow(340)); assertEquals(339, CisdNet.viewRow(345), "15:00's entry uses 14:55's view")
        assertNull(CisdNet.viewRow(346))
    }

    @Test fun theFilterTakesOnlyAPositiveSideEv() {
        val ev = DoubleArray(16) { j -> if (j % 2 == 0) 50.0 else -50.0 }
        assertEquals(50.0, CisdNet.evSide(ev, true)); assertEquals(-50.0, CisdNet.evSide(ev, false))
        assertTrue(CisdNet.keep(ev, true)); assertFalse(CisdNet.keep(ev, false))
        ev[0] = -400.0 + 50.0 * 7 - 50.0 * 7                         // the call's mean: (-400 + 7 x 50) / 8 = -6.25
        assertFalse(CisdNet.keep(ev, true), "refused at a mean EV under 0")
        assertFalse(CisdNet.keep(DoubleArray(16) { 0.0 }, true), "0 is not above 0")
        assertFalse(CisdNet.keep(DoubleArray(16) { Double.NaN }, true), "a missing EV never passes")
        assertFailsWith<IllegalArgumentException> { CisdNet.evSide(DoubleArray(3), true) }
    }

    // ---- the contract ----------------------------------------------------------------------------------------------------------

    @Test fun theExpiryFollowsR131sSeriesRule() {
        val tue = LocalDate.of(2026, 10, 20); val monthEnd = LocalDate.of(2026, 10, 27); val nov3 = LocalDate.of(2026, 11, 3)
        val nov24 = LocalDate.of(2026, 11, 24)
        val list = listOf(tue, monthEnd, nov3, nov24)
        assertEquals(tue, CisdNet.expiryFor(LocalDate.of(2026, 10, 19), list), "the nearest")
        assertEquals(monthEnd, CisdNet.expiryFor(tue, list), "a weekly expiry day: the monthly")
        assertNull(CisdNet.expiryFor(monthEnd, list), "the monthly expires today too: no trade")
        assertEquals(nov3, CisdNet.expiryFor(LocalDate.of(2026, 10, 28), list))
        assertNull(CisdNet.expiryFor(today, emptyList())); assertNull(CisdNet.expiryFor(today, listOf(tue)))
        assertNull(CisdNet.expiryFor(nov24, listOf(nov24)), "a monthly-only index on its expiry day: no trade")
        assertEquals(25_000, CisdNet.strike("NIFTY", 25_024.9)); assertEquals(25_050, CisdNet.strike("NIFTY", 25_025.0))
        assertEquals(56_100, CisdNet.strike("BANKNIFTY", 56_050.0)); assertEquals(13_025, CisdNet.strike("MIDCPNIFTY", 13_012.5))
    }

    // ---- the exits -----------------------------------------------------------------------------------------------------------

    @Test fun theLockKeeps60PercentOnceUp1000() {
        assertNull(CisdNet.lockLevel(100.0, 65, 115.0), "up ₹975: not yet")
        assertEquals(100.0 + 0.6 * (1000.0 / 65), CisdNet.lockLevel(100.0, 65, 100.0 + 1000.0 / 65)!!, 1e-9)
        assertEquals(124.0, CisdNet.lockLevel(100.0, 65, 140.0)!!, 1e-9)
        assertNull(CisdNet.lockLevel(100.0, 65, Double.NEGATIVE_INFINITY)); assertNull(CisdNet.lockLevel(Double.NaN, 65, 140.0))
        assertNull(CisdNet.lockLevel(100.0, 0, 140.0))
    }

    @Test fun theIndexStopIsOnTheIndex() {
        assertTrue(CisdNet.indexStopHit(true, 954.8, 954.8, 960.0)); assertFalse(CisdNet.indexStopHit(true, 954.8, 954.9, 960.0))
        assertTrue(CisdNet.indexStopHit(false, 1045.2, 1040.0, 1045.2)); assertFalse(CisdNet.indexStopHit(false, 1045.2, 1040.0, 1045.1))
        assertFalse(CisdNet.indexStopHit(true, 954.8, Double.NaN, Double.NaN)); assertFalse(CisdNet.indexStopHit(false, 1045.2, Double.NaN, Double.NaN))
    }

    private fun opt(vararg rows: Pair<Int, DoubleArray>): Array<DoubleArray> {
        val a = Array(n) { DoubleArray(4) { Double.NaN } }
        for ((r, v) in rows) a[r] = v
        return a
    }

    private val idxH = DoubleArray(n) { 1010.0 }
    private val idxL = DoubleArray(n) { 990.0 }

    @Test fun theResearchExitSimulator() {
        // The lock: up ₹1,300 at minute 101's high (100 -> 120 on 65), the level from minute 102 is 112; minute 103's low 111 hits it.
        val a = opt(100 to doubleArrayOf(100.0, 105.0, 99.0, 104.0), 101 to doubleArrayOf(104.0, 120.0, 103.0, 118.0),
            102 to doubleArrayOf(118.0, 119.0, 113.0, 114.0), 103 to doubleArrayOf(113.0, 114.0, 111.0, 112.0))
        val lock = CisdNet.simExit(a, idxH, idxL, true, 100, 100.0, 65, 950.0)!!
        assertEquals(CisdNet.Exit(103, "lock", 112.0), lock)
        // A minute whose open is already under the level fills at the open.
        a[103] = doubleArrayOf(108.0, 109.0, 107.0, 108.0)
        assertEquals(CisdNet.Exit(103, "lock", 108.0), CisdNet.simExit(a, idxH, idxL, true, 100, 100.0, 65, 950.0))
        // The index stop: sold at the option's last close at or before that minute.
        val l2 = idxL.copyOf().also { it[102] = 949.0 }
        assertEquals(CisdNet.Exit(102, "istop", 114.0), CisdNet.simExit(a, idxH, l2, true, 100, 100.0, 65, 950.0))
        // Both in one minute: the lower price counts.
        val l3 = idxL.copyOf().also { it[103] = 949.0 }
        assertEquals(CisdNet.Exit(103, "lock", 108.0), CisdNet.simExit(a, idxH, l3, true, 100, 100.0, 65, 950.0))
        a[103] = doubleArrayOf(113.0, 114.0, 111.0, 112.5)
        assertEquals(CisdNet.Exit(103, "lock", 112.0), CisdNet.simExit(a, idxH, l3, true, 100, 100.0, 65, 950.0))
        a[103] = doubleArrayOf(113.0, 114.0, 111.0, 111.5)
        assertEquals(CisdNet.Exit(103, "istop", 111.5), CisdNet.simExit(a, idxH, l3, true, 100, 100.0, 65, 950.0))
        // A put's stop is above: the index high.
        val h4 = idxH.copyOf().also { it[101] = 1046.0 }
        assertEquals(CisdNet.Exit(101, "istop", 118.0), CisdNet.simExit(a, h4, idxL, false, 100, 100.0, 65, 1045.0))
        // Neither: out at 15:20 at the last close.
        val quiet = opt(100 to doubleArrayOf(100.0, 101.0, 99.0, 100.0), 200 to doubleArrayOf(100.0, 102.0, 98.0, 101.0))
        assertEquals(CisdNet.Exit(200, "time", 101.0), CisdNet.simExit(quiet, idxH, idxL, true, 100, 100.0, 65, 950.0))
        // No option price at the entry minute: no trade. The index stop with no option close yet: held to the time exit.
        assertNull(CisdNet.simExit(opt(101 to doubleArrayOf(100.0, 101.0, 99.0, 100.0)), idxH, idxL, true, 100, 100.0, 65, 950.0))
        val noClose = opt(100 to doubleArrayOf(100.0, 101.0, 99.0, Double.NaN), 150 to doubleArrayOf(100.0, 101.0, 99.0, 97.0))
        val l5 = idxL.copyOf().also { it[100] = 949.0 }
        assertEquals(CisdNet.Exit(150, "time", 97.0), CisdNet.simExit(noClose, idxH, l5, true, 100, 100.0, 65, 950.0))
        assertNull(CisdNet.simExit(opt(100 to doubleArrayOf(100.0, 101.0, 99.0, Double.NaN)), idxH, idxL, true, 100, 100.0, 65, 950.0))
        // The lock level from earlier minutes only: a spike and fall inside one minute does not exit in that minute.
        val spike = opt(100 to doubleArrayOf(100.0, 130.0, 99.0, 101.0), 101 to doubleArrayOf(101.0, 102.0, 100.0, 101.0))
        assertEquals(CisdNet.Exit(101, "lock", 101.0), CisdNet.simExit(spike, idxH, idxL, true, 100, 100.0, 65, 950.0))
        // A lock level set but the minute has no low: not hit there.
        val gap = opt(100 to doubleArrayOf(100.0, 130.0, 99.0, 125.0), 101 to doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN),
            102 to doubleArrayOf(125.0, 126.0, 110.0, 112.0))
        assertEquals(CisdNet.Exit(102, "lock", 118.0), CisdNet.simExit(gap, idxH, idxL, true, 100, 100.0, 65, 950.0))
    }

    @Test fun itIsLabelledAndPaperOnly() {
        assertTrue(CisdNet.ARM.paperOnly)
        assertEquals("CISD + network (failed gates, Boss's exception)", CisdNet.SHOWN)
        assertEquals(CisdNet.SHOWN, CisdNet.ARM.label)
        assertTrue(CisdNet.NOT_PROVEN.contains("never live"))
        assertTrue(CisdNet.SIGNAL.contains("index"))
        assertTrue(CisdNet.IV_GAP.contains("no Dhan IV") && CisdNet.IV_GAP.contains("2 of the research's 4"))
        assertEquals(PassedArms.Session.row(15, 0), CisdNet.LAST_ENTRY); assertEquals(364, CisdNet.EXIT_LAST)
        assertEquals(S5Trail.UNDERLYINGS, CisdNet.UNDERLYINGS)
    }
}
