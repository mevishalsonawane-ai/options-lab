package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "R72 Breadth Donchian" ([BreadthDonchianArm], 16 Oct): Donchian's break, taken only when at least 4 of the five indices break
 * the same way on the same 5-minute bar - each index that broke gets the signal; 3 or fewer, nothing; a missing bar counts as not
 * breaking; breaks on different bars never add up. Against the research's own code (android/tools/breadth_donchian_fixture.py:
 * research/hunt/r72/r72rules.py `breadth_breakout`, M = 4) on five synthetic indices with feed gaps, then Donchian's rest
 * through its own helpers (window, strike, fill slack, caps).
 */
class BreadthDonchianArmTest {
    private val prev = LocalDate.of(2026, 10, 16)
    private val day = LocalDate.of(2026, 10, 19)
    private val five = BreadthDonchianArm.UNDERLYINGS
    private fun b5(d: LocalDate, b: Int, o: Double, h: Double, l: Double, c: Double) = PassedArms.Bar5(d, b, o, h, l, c)

    /**
     * Each index: the previous session's last 20 bars flat at [base] (high +1, low -1), then today's bars 0 .. [bars] - 1 flat
     * at [base], except the bars in [moves] (bar -> +1 a close above the channel, -1 below it). [missing]: today's bars it has not.
     */
    private fun index(base: Double, moves: Map<Int, Int> = emptyMap(), bars: Int = 10, missing: Set<Int> = emptySet()): List<PassedArms.Bar5> =
        (55 until 75).map { b5(prev, it, base, base + 1, base - 1, base) } +
            (0 until bars).filter { it !in missing }.map { b ->
                when (moves[b]) {
                    1 -> b5(day, b, base, base + 5, base - 0.5, base + 4)
                    -1 -> b5(day, b, base, base + 0.5, base - 5, base - 4)
                    else -> b5(day, b, base, base + 0.5, base - 0.5, base)
                }
            }

    private val base = mapOf("NIFTY" to 25_000.0, "BANKNIFTY" to 56_000.0, "FINNIFTY" to 26_500.0, "MIDCPNIFTY" to 13_000.0, "SENSEX" to 82_000.0)

    /** The five with [up] breaking up on bar [bar], [down] breaking down there. */
    private fun market(bar: Int, up: Set<String> = emptySet(), down: Set<String> = emptySet(), missing: Map<String, Set<Int>> = emptyMap()) =
        five.associateWith { u ->
            val m = when (u) { in up -> mapOf(bar to 1); in down -> mapOf(bar to -1); else -> emptyMap() }
            index(base.getValue(u), m, missing = missing[u].orEmpty())
        }

    private fun sig(m: Map<String, List<PassedArms.Bar5>>) = BreadthDonchianArm.signals(m, day).map { Triple(it.underlying, it.bar.b, it.side) }

    @Test fun fourOfFiveBreakingUpBuyTheFourCallsFiveBuyFiveThreeBuyNothing() {
        val four = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")
        assertEquals(listOf(Triple("NIFTY", 3, 1), Triple("BANKNIFTY", 3, 1), Triple("FINNIFTY", 3, 1), Triple("SENSEX", 3, 1)), sig(market(3, up = four)))
        assertEquals(five.map { Triple(it, 3, 1) }, sig(market(3, up = five.toSet())))
        assertEquals(emptyList(), sig(market(3, up = setOf("NIFTY", "BANKNIFTY", "FINNIFTY"))))
        // Plain Donchian would have traded each of the three: breadth is what stops it.
        assertEquals(1, DonchianArm.signals("NIFTY", market(3, up = setOf("NIFTY", "BANKNIFTY", "FINNIFTY")).getValue("NIFTY"), day).size)
    }

    @Test fun thePutMirrorAndAMixedBarIsNothing() {
        val m = market(4, down = setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"))
        assertEquals(listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX").map { Triple(it, 4, -1) }, sig(m))
        val s = BreadthDonchianArm.signals(m, day).first()
        // The 1-ITM put: BANKNIFTY closes 55,996 -> ATM 56,000 -> one strike in the money 56,100.
        assertEquals(56_100, s.strike)
        assertEquals(-1, BreadthDonchianArm.breadth(BreadthDonchianArm.sidesAt(m, day, 4)))
        // 3 up and 2 down: neither side has 4.
        val mixed = market(4, up = setOf("NIFTY", "BANKNIFTY", "FINNIFTY"), down = setOf("MIDCPNIFTY", "SENSEX"))
        assertEquals(emptyList(), sig(mixed))
        assertEquals(0, BreadthDonchianArm.breadth(BreadthDonchianArm.sidesAt(mixed, day, 4)))
    }

    @Test fun breaksOnDifferentBarsNeverAddUp() {
        // Three break on bar 3, the other two on bar 4 (a bar later): no bar has 4.
        val m = five.associateWith { u -> index(base.getValue(u), if (u in setOf("NIFTY", "BANKNIFTY", "FINNIFTY")) mapOf(3 to 1) else mapOf(4 to 1)) }
        assertEquals(emptyList(), sig(m))
    }

    @Test fun anIndexWithNoBarThenCountsAsNotBreaking() {
        // SENSEX has no bar 5 (its feed is down): the other four all break -> four signals; three of them -> none.
        val gap = mapOf("SENSEX" to setOf(5))
        val four = market(5, up = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), missing = gap)
        assertEquals(listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY").map { Triple(it, 5, 1) }, sig(four))
        assertEquals(0, BreadthDonchianArm.sidesAt(four, day, 5).getValue("SENSEX"))
        assertEquals(emptyList(), sig(market(5, up = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX"), missing = gap)))
        // An index with no series at all (never read): the same.
        assertEquals(emptyList(), sig(market(5, up = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")) - "SENSEX"))
        assertEquals(listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY").map { Triple(it, 5, 1) },
            sig(market(5, up = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")) - "SENSEX"))
        // Anything that is not one of the five never counts.
        assertEquals(emptyList(), sig(market(5, up = setOf("NIFTY", "BANKNIFTY", "FINNIFTY")) + ("OTHER" to index(100.0, mapOf(5 to 1)))))
    }

    @Test fun theWindowTheStrikeTheFillSlackAndTheCapsAreDonchians() {
        // Bar 0 (closes 09:20) is the first entry; bar 70 (15:10) the last; bar 71 (15:15) never; yesterday's bars never.
        fun at(bar: Int) = five.associateWith { u -> index(base.getValue(u), mapOf(bar to 1), bars = 75) }
        assertEquals(five.map { Triple(it, 0, 1) }, sig(at(0)))
        assertEquals(five.map { Triple(it, 70, 1) }, sig(at(70)))
        assertEquals(emptyList(), sig(at(71)))
        val s = BreadthDonchianArm.signals(at(0), day).first { it.underlying == "NIFTY" }
        assertEquals(day.atTime(9, 20), s.entryTime)
        // NIFTY closes 25,004 -> ATM 25,000 -> 1-ITM call 24,950.
        assertEquals(24_950, s.strike)
        assertTrue(DonchianArm.mayFill(s, day.atTime(9, 22, 59)) && !DonchianArm.mayFill(s, day.atTime(9, 23)))
        // One position a side, 2 a side a day: counted in the arm's own book (what the host passes).
        assertNull(DonchianArm.mayTake(s, emptySet(), emptyMap()))
        assertTrue(DonchianArm.mayTake(s, setOf(1), emptyMap())!!.contains("one position a side"))
        assertTrue(DonchianArm.mayTake(s, emptySet(), mapOf(1 to 2))!!.contains("2 entries a side"))
        assertEquals(emptyList(), BreadthDonchianArm.signals(at(0), prev))
    }

    @Test fun itsSettingsAndWords() {
        assertEquals("r72_breadth_donchian", BreadthDonchianArm.SOURCE)
        assertEquals("R72 Breadth Donchian", BreadthDonchianArm.LABEL)
        assertTrue(BreadthDonchianArm.ARM.paperOnly)
        assertEquals(4, BreadthDonchianArm.MIN_BREADTH)
        assertEquals(DonchianArm.UNDERLYINGS, BreadthDonchianArm.UNDERLYINGS)
        assertEquals(DonchianArm.EXITS, BreadthDonchianArm.EXITS)
        assertEquals(PassedArms.PremiumExits(0.15, 0.30, 30, emptyList(), LocalTime.of(15, 15)), BreadthDonchianArm.EXITS)
        for (w in listOf("at least 4 of", "previous 20 bars", "1-ITM", "fewer than 4", "09:20-15:10", "−15%", "+5% once +25%", "30/60/100",
            "30 minutes", "15:15", "2 entries a side", "expiry day", "1 lot", "paper only"))
            assertTrue(w in BreadthDonchianArm.RULES, w)
        assertTrue("1,202 trades" in BreadthDonchianArm.RECORD && "+₹1,71,366" in BreadthDonchianArm.RECORD && "a filter" in BreadthDonchianArm.RECORD)
        assertEquals("Backtest worst day −₹58,613, drawdown ₹1.99 lakh at 1 lot on five indices: beyond the −₹40k account stop; paper only",
            BreadthDonchianArm.RISK)
    }

    @Test fun theComparisonForBoss() {
        val c = BreadthDonchianArm.comparison(LocalDate.of(2026, 10, 16), listOf(1_000.0, -200.0), listOf(500.0, -900.0, 100.0), listOf(-900.0))
        assertTrue(c.startsWith("Breadth Donchian vs Donchian on paper since 16 Oct: R72 Breadth Donchian 2 trades, +₹800; R51 Donchian 20 3 trades, −₹300; " +
            "Donchian's trades breadth did not confirm: 1 trade, −₹900 (after charges;"), c)
        assertTrue("+₹1,37,635" in c && "−₹55,790" in c, c)
        val none = BreadthDonchianArm.comparison(null, emptyList(), emptyList(), null)
        assertTrue(none.contains("so far: R72 Breadth Donchian 0 trades, +₹0; R51 Donchian 20 0 trades, +₹0 (after charges"), none)
    }

    // ---- research parity -----------------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private val fx: Map<String, Any?> by lazy {
        MiniJson.parse(javaClass.getResourceAsStream("/passedarms/breadth_parity.json")!!.bufferedReader().readText()) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun theSignalsAreTheResearchsBreadthBreakoutOnFiveIndicesWithFeedGaps() {
        val d0 = LocalDate.of(2026, 9, 1)
        val warm = (fx["warm"] as Double).toInt()
        val series = (fx["bars"] as Map<String, List<List<Double>>>).mapValues { (_, rows) ->
            rows.map { r -> b5(d0.plusDays(r[0].toLong()), r[1].toInt(), r[2], r[3], r[4], r[5]) }
        }
        // The research's raw signals (every bar), from its WARM-th bar on and in the window (bar 70 the last), as (index, day, bar, side).
        val want = (fx["signals"] as List<List<Any?>>).map { s -> listOf(s[0] as String, (s[1] as Double).toInt(), (s[2] as Double).toInt(), (s[3] as Double).toInt()) }
        val days = series.values.flatten().map { it.date }.distinct().sorted()
        val got = days.flatMap { d -> BreadthDonchianArm.signals(series, d) }
            .filter { s -> series.getValue(s.underlying).indexOf(s.bar) >= warm }
            .map { listOf(it.underlying, (it.bar.date.toEpochDay() - d0.toEpochDay()).toInt(), it.bar.b, it.side) }
        val wantIn = want.filter { it[2] as Int <= 70 }
        assertTrue(wantIn.size >= 40, "the fixture has breadth bars to compare (${wantIn.size})")
        assertTrue(wantIn.groupBy { listOf(it[1], it[2]) }.values.any { it.size == 4 }, "a bar where exactly 4 broke")
        assertEquals(wantIn, got)
        // Every signal is a Donchian break of its own index: breadth only filters.
        for (d in days) for (s in BreadthDonchianArm.signals(series, d))
            assertTrue(DonchianArm.signals(s.underlying, series.getValue(s.underlying), d).any { it.bar == s.bar && it.side == s.side })
    }
}
