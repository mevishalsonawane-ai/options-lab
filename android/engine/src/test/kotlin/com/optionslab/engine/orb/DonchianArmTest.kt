package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "R51 Donchian 20" ([DonchianArm]): R44's `donchian` on the continuous 5-minute series (the previous session's bars count),
 * the 09:20-15:10 entry window, the 1-ITM strike, one position a side and 2 entries a side a day, the 2-minute fill slack,
 * and the exits as R51 tuned them.
 */
class DonchianArmTest {
    private val prev = LocalDate.of(2026, 10, 16)
    private val day = LocalDate.of(2026, 10, 19)
    private fun b5(d: LocalDate, b: Int, o: Double, h: Double, l: Double, c: Double) = PassedArms.Bar5(d, b, o, h, l, c)

    /** The previous session's last 20 bars flat at 100 (high 101, low 99), then [today]. */
    private fun series(today: List<PassedArms.Bar5>) = (55 until 75).map { b5(prev, it, 100.0, 101.0, 99.0, 100.0) } + today

    @Test fun aCloseAboveThePrevious20HighsBuysTheCallBelowTheLowsThePut() {
        val s = series(listOf(b5(day, 0, 100.0, 102.0, 99.5, 101.5), b5(day, 1, 101.0, 101.2, 98.0, 98.5), b5(day, 2, 99.0, 100.5, 99.0, 100.0)))
        assertEquals(1, DonchianArm.sideAt(s, 20))
        assertEquals(-1, DonchianArm.sideAt(s, 21))
        assertEquals(0, DonchianArm.sideAt(s, 22))
        // Fewer than 20 bars before, past the end, a missing price in the window or on the bar: nothing.
        assertEquals(0, DonchianArm.sideAt(s, 19)); assertEquals(0, DonchianArm.sideAt(s, 99))
        val nan = s.toMutableList().also { it[5] = b5(prev, 60, 100.0, Double.NaN, 99.0, 100.0) }
        assertEquals(0, DonchianArm.sideAt(nan, 20))
        val nanC = s.toMutableList().also { it[20] = b5(day, 0, 100.0, 102.0, 99.5, Double.NaN) }
        assertEquals(0, DonchianArm.sideAt(nanC, 20))
        // A close exactly at the high is not above it.
        assertEquals(0, DonchianArm.sideAt(series(listOf(b5(day, 0, 100.0, 101.0, 99.5, 101.0))), 20))
    }

    @Test fun todaysSignalsWithTheirEntryMinuteAndStrike() {
        val s = series(listOf(b5(day, 0, 25_000.0, 25_200.0, 24_990.0, 25_180.0)).map { it } + (1 until 75).map { b5(day, it, 25_100.0, 25_150.0, 25_050.0, 25_100.0) })
            .let { list -> list.map { if (it.date == prev) b5(prev, it.b, 25_000.0, 25_010.0, 24_990.0, 25_000.0) else it } }
        val sig = DonchianArm.signals("NIFTY", s, day)
        // 09:15-09:19 breaks up: the call, bought at 09:20, 1-ITM of its close (25,180 -> ATM 25,200 -> 25,150).
        assertEquals(1, sig.first().side)
        assertEquals(day.atTime(9, 20), sig.first().entryTime)
        assertEquals(25_150, sig.first().strike)
        assertTrue(sig.all { DonchianArm.inWindow(it.entryTime.toLocalTime()) })
        // Yesterday's bars are never today's signals.
        assertTrue(sig.all { it.bar.date == day })
    }

    @Test fun theWindowIsNineTwentyToThreeTen() {
        assertTrue(DonchianArm.inWindow(LocalTime.of(9, 20))); assertTrue(DonchianArm.inWindow(LocalTime.of(15, 10)))
        assertTrue(!DonchianArm.inWindow(LocalTime.of(9, 19)) && !DonchianArm.inWindow(LocalTime.of(15, 11)))
        // The bar closing 15:15 (b = 71) is never an entry; b = 70 (closing 15:10) is the last.
        val late = series(emptyList()) + (0 until 72).map { b -> when (b) { 70 -> b5(day, b, 100.0, 120.0, 99.0, 119.0); 71 -> b5(day, b, 100.0, 126.0, 99.0, 125.0); else -> b5(day, b, 100.0, 100.5, 99.5, 100.0) } }
        val sig = DonchianArm.signals("NIFTY", late, day)
        assertEquals(listOf(70), sig.map { it.bar.b })
        assertEquals(1, DonchianArm.sideAt(late, late.size - 1))
    }

    @Test fun onePositionASideAndTwoEntriesASideADay() {
        val s = DonchianArm.Signal("BANKNIFTY", 1, b5(day, 3, 1.0, 1.0, 1.0, 1.0), 53_400)
        assertNull(DonchianArm.mayTake(s, emptySet(), emptyMap()))
        assertTrue(DonchianArm.mayTake(s, setOf(1), emptyMap())!!.contains("one position a side"))
        // The put held does not stop the call.
        assertNull(DonchianArm.mayTake(s, setOf(-1), mapOf(-1 to 2, 1 to 1)))
        assertTrue(DonchianArm.mayTake(s, emptySet(), mapOf(1 to 2))!!.contains("2 entries a side"))
    }

    @Test fun theFillWaitsAtMostTwoMinutes() {
        val s = DonchianArm.Signal("NIFTY", -1, b5(day, 5, 1.0, 1.0, 1.0, 1.0), 25_000)
        assertEquals(day.atTime(9, 45), s.entryTime)
        assertTrue(DonchianArm.mayFill(s, day.atTime(9, 45)))
        assertTrue(DonchianArm.mayFill(s, day.atTime(9, 47, 59)))
        assertTrue(!DonchianArm.mayFill(s, day.atTime(9, 48)) && !DonchianArm.mayFill(s, day.atTime(9, 44, 59)))
    }

    /** 22 Oct: a break whose fill window passed with nothing bought says why, never only "missed". */
    @Test fun aMissedBreakSaysWhyItsFillWindowPassed() {
        // The 09:15 bar (row 0): it closes at 09:20, its last minute is 09:19, its window 09:20-09:22.
        val s = DonchianArm.Signal("FINNIFTY", -1, b5(day, 0, 1.0, 1.0, 1.0, 1.0), 24_550)
        fun look(h: Int, m: Int, sec: Int, last: LocalTime?) = DonchianArm.Look(day.atTime(h, m, sec), last)
        // Tried and refused (another arm held the option, a guard, no price): that reason, never "missed".
        val tried = DonchianArm.missedWhy(s, "refused: the kill switch is on", listOf(look(9, 21, 24, LocalTime.of(9, 20))))
        assertEquals("09:20 put break not bought: refused: the kill switch is on (tried until 09:22, the end of its fill window)", tried)
        // No entry check inside 09:20-09:22 (a check at 09:19 and one at 09:23 only): our own pace.
        assertEquals("09:20 put break missed: no entry check ran in its fill window (09:20-09:22)",
            DonchianArm.missedWhy(s, null, listOf(look(9, 19, 50, LocalTime.of(9, 18)), look(9, 23, 0, LocalTime.of(9, 22)))))
        assertEquals("09:20 put break missed: no entry check ran in its fill window (09:20-09:22)", DonchianArm.missedWhy(s, null, emptyList()), "no look at all")
        // Every check inside lacked 09:19 (the feed late): said with the last check's minutes; a read with no minute today too.
        val late = DonchianArm.missedWhy(s, null, listOf(look(9, 22, 40, LocalTime.of(9, 18)), look(9, 20, 3, null)))
        assertEquals("09:20 put break missed: the index's 09:19 minute had not reached the phone in its fill window (09:20-09:22; " +
            "the last check, 09:22:40, had minutes to 09:18)", late)
        assertTrue(DonchianArm.missedWhy(s, null, listOf(look(9, 20, 3, null))).endsWith("had minutes to none)"))
        // A check inside had the whole bar, yet the break showed only later: the minutes were corrected after.
        assertEquals("09:20 put break missed: the bar read in its fill window (09:20-09:22) did not break; the index's minutes were corrected after",
            DonchianArm.missedWhy(s, null, listOf(look(9, 20, 3, LocalTime.of(9, 18)), look(9, 21, 10, LocalTime.of(9, 20)))))
        // The window's edges: 09:22:59 is inside, 09:23:00 is not; a call says "call".
        val c = s.copy(side = 1)
        assertTrue(DonchianArm.missedWhy(c, null, listOf(look(9, 22, 59, LocalTime.of(9, 21)))).startsWith("09:20 call break missed: the bar read"))
        assertTrue(DonchianArm.missedWhy(c, null, listOf(look(9, 23, 0, LocalTime.of(9, 22)))).contains("no entry check ran"))
    }

    @Test fun itsSettingsAreR51s() {
        assertEquals(PassedArms.PremiumExits(0.15, 0.30, 30, emptyList(), LocalTime.of(15, 15)), DonchianArm.EXITS)
        assertEquals(listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"), DonchianArm.UNDERLYINGS)
        assertEquals("FINNIFTY", DonchianArm.REPORT_APART)
        assertTrue(DonchianArm.ARM.paperOnly)
        assertEquals(20, DonchianArm.LOOKBACK); assertEquals(1, DonchianArm.MONEY); assertEquals(1, DonchianArm.LOTS); assertEquals(2, DonchianArm.MAX_PER_SIDE)
        for (w in listOf("previous 20 bars", "1-ITM", "09:20-15:10", "−15%", "+30%", "30 minutes", "15:15", "2 entries a side", "expiry day", "paper only"))
            assertTrue(w in DonchianArm.RULES, w)
        assertTrue("1,787 trades" in DonchianArm.RECORD && "+₹55,439" in DonchianArm.RECORD && "FINNIFTY" in DonchianArm.RECORD)
    }
    // ---- R73 T7: Supertrend (10, 3) agrees with the break (16 Oct, Boss) -------------------------------------------------

    /** 300 bars of a deterministic wave over 4 days (the same formula as the R73 parity run in Python). */
    private fun wave(): List<PassedArms.Bar5> = (0 until 300).map { i ->
        val c = 100 + 5 * kotlin.math.sin(i / 7.0) + 3 * kotlin.math.sin(i / 3.1) + 0.02 * i
        b5(day.minusDays(4L - i / 75), i % 75, c, c + 1 + 0.5 * kotlin.math.abs(kotlin.math.sin(i.toDouble())),
            c - 1 - 0.5 * kotlin.math.abs(kotlin.math.cos(i.toDouble())), c)
    }

    @Test fun supertrendMatchesTheResearchLineForLine() {
        // research/hunt/r73/r73rules.py supertrend_dir on the same 300 bars (u up, d down, 0 not yet known).
        val want = "000000000uuuuuuuuuuuuuuuuuuuuddddddddddddduuuuuuuuuuuuuuuuuuuuuuuuuuuddddddddddddddddddddddddduuuuuuuuuuuuuuudddddddddddddddddddddddddddduuuuuuuuuuuuuudddddddddddddddddddddddddduuuuuuuuuuuuuuuuuuuuuuuuuuuddddddddddddduuuuuuuuuuuuuuuuuuuuuuuuuuuuddddddddddddddddddddddddduuuuuuuuuuuuuuuddddddddddddddd"
        val got = DonchianArm.supertrend(wave()).joinToString("") { when (it) { 1 -> "u"; -1 -> "d"; else -> "0" } }
        assertEquals(want, got)
        assertEquals(10, DonchianArm.ST_ATR); assertEquals(3.0, DonchianArm.ST_MULT)
    }

    @Test fun supertrendOnEmptyShortAndGappySeries() {
        assertEquals(0, DonchianArm.supertrend(emptyList()).size)
        assertTrue(DonchianArm.supertrend(wave().take(9)).all { it == 0 }, "no ATR before 10 bars")
        // A bar with a missing price is left out (0) and does not break the rest.
        val w = wave().toMutableList()
        w[150] = b5(w[150].date, w[150].b, 1.0, Double.NaN, Double.NaN, Double.NaN)
        val g = DonchianArm.supertrend(w)
        assertEquals(0, g[150]); assertTrue(g[299] != 0)
    }

    @Test fun supertrendAgreesReadsOnlyBarsUpToTheSignal() {
        val w = wave()
        val dirs = DonchianArm.supertrend(w)
        val i = 200
        val up = DonchianArm.Signal("NIFTY", 1, w[i], 25_000)
        val dn = DonchianArm.Signal("NIFTY", -1, w[i], 25_000)
        assertEquals(dirs[i] == 1, DonchianArm.supertrendAgrees(w, up))
        assertEquals(dirs[i] == -1, DonchianArm.supertrendAgrees(w, dn))
        // Look-ahead guard: scrambling every later bar changes nothing.
        val scrambled = w.take(i + 1) + w.drop(i + 1).map { b5(it.date, it.b, 1.0, 9_999.0, 0.5, if (it.b % 2 == 0) 9_000.0 else 1.0) }
        assertEquals(DonchianArm.supertrendAgrees(w, up), DonchianArm.supertrendAgrees(scrambled, up))
        assertEquals(DonchianArm.supertrendAgrees(w, dn), DonchianArm.supertrendAgrees(scrambled, dn))
    }

    @Test fun theRulesNameTheSupertrendFilterOnlyWhenItIsOn() {
        // 16 Oct (R73 review): the row and Jarvis say the filter (and what an unjudged break does) when it is on.
        assertEquals(DonchianArm.RULES, DonchianArm.rules(false))
        val on = DonchianArm.rules(true)
        assertTrue(on.startsWith(DonchianArm.RULES) && DonchianArm.ST_RULE in on && DonchianArm.ST_UNJUDGED_RULE in on, on)
        assertTrue("paper only" in on.substringAfter(DonchianArm.RULES))
    }

    @Test fun supertrendCannotBeJudgedWithoutAnEarlierSessionOrTheBar() {
        val w = wave()
        val todayOnly = w.filter { it.date == w[299].date }
        assertNull(DonchianArm.supertrendAgrees(todayOnly, DonchianArm.Signal("NIFTY", 1, todayOnly[40], 1)), "no earlier session")
        assertNull(DonchianArm.supertrendAgrees(w, DonchianArm.Signal("NIFTY", 1, b5(day.plusDays(9), 3, 1.0, 1.0, 1.0, 1.0), 1)), "bar not in the series")
        assertNull(DonchianArm.supertrendAgrees(w.take(5), DonchianArm.Signal("NIFTY", 1, w[4], 1)), "no earlier session in the first 5")
        // One earlier session's last bar, then 4 of today's: an earlier session is in, but under 10 bars, no ATR: no direction yet.
        val short = listOf(w[74]) + w.subList(75, 79)
        assertNull(DonchianArm.supertrendAgrees(short, DonchianArm.Signal("NIFTY", 1, short.last(), 1)), "no direction yet")
        assertTrue(DonchianArm.supertrend(short).all { it == 0 })
        assertTrue(DonchianArm.ST_RULE.contains("Supertrend (10, 3)") && DonchianArm.ST_RULE.contains("R73 T7"))
        assertTrue("1,670 trades" in DonchianArm.ST_RECORD && "+₹1,34,743" in DonchianArm.ST_RECORD && "+₹81,845" in DonchianArm.ST_RECORD)
    }
}
