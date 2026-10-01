package com.optionslab.engine.orb

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

class LiquidityRulesTest {
    private val day = LocalDate.of(2026, 10, 1)

    private fun bars(o: List<Double>, h: List<Double>, l: List<Double>, c: List<Double>, minutes: Long = 5) =
        o.indices.map { Bar(day.atTime(9, 15).plusMinutes(minutes * it), o[it], h[it], l[it], c[it]) }

    /** The synthetic series research/indicator/liquidity.py was run on (see the numbers below). */
    private fun wave(n: Int = 400): List<Bar> {
        val c = (0 until n).map { 1000 + 40 * sin(it / 9.0) + 25 * sin(it / 3.7) + 10 * sin(it / 1.3) }
        val o = (0 until n).map { if (it == 0) c[0] else c[it - 1] }
        val h = (0 until n).map { maxOf(o[it], c[it]) + 3 + 8 * abs(sin(it / 2.3)) }
        val l = (0 until n).map { minOf(o[it], c[it]) - 3 - 8 * abs(cos(it / 2.9)) }
        return (0 until n).map { Bar(LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(5L * it), o[it], h[it], l[it], c[it]) }
    }

    @Test fun theArmIsOneSwitchOverTwoBooks() {
        assertTrue(LiquidityRules.ARM.liquidity)
        assertFalse(LiquidityRules.ARM.paperOnly, "it follows the Paper / Live switch like the ORB")
        assertEquals(listOf("liquidity15", "liquidity5", "liquidity30_fin", "liquidity5_fin"), LiquidityRules.BOOKS.map { it.source })
        assertEquals(listOf(15, 5, 30, 5), LiquidityRules.BOOKS.map { LiquidityRules.minutesOf(it) })
        assertEquals(listOf("BANKNIFTY", "BANKNIFTY", "FINNIFTY", "FINNIFTY"), LiquidityRules.BOOKS.map { LiquidityRules.underlyingOf(it) })
        assertEquals(100, LiquidityRules.strikeStep("BANKNIFTY")); assertEquals(50, LiquidityRules.strikeStep("FINNIFTY"))
        assertEquals(24_050, OrbRules.atmStrike(24_070.0, LiquidityRules.strikeStep("FINNIFTY")))
        assertEquals(LiquidityRules.UNDERLYINGS.toSet(), LiquidityRules.INDEX_KEYS.keys)
        assertTrue(LiquidityRules.BOOKS.all { !it.paperOnly && it.liquidity })
        assertEquals(15, LiquidityRules.minutesOf(LiquidityRules.ARM15))
        assertEquals(5, LiquidityRules.minutesOf(LiquidityRules.ARM5))
        assertEquals(30, LiquidityRules.minutesOf(LiquidityRules.FIN30))
        assertEquals(mapOf("liquidity15_fin" to "liquidity30_fin"), LiquidityRules.RENAMED)
        assertFalse(OrbRules.ORB.liquidity)                              // the ORB's arms are unchanged
        assertTrue(LiquidityRules.ARM !in OrbRules.ARMS)
    }

    @Test fun sameLevelsAndSignalsAsTheResearchVersion() {
        val b = wave()
        val sw = LiquidityRules.swingZones(b)
        val pl = LiquidityRules.poolZones(b)
        // research/indicator/liquidity.py on the same series: 12 swings, 17 pools, these sums, signals at 153 / 239 / 352.
        assertEquals(12, sw.size)
        assertEquals(listOf(-1 to 39, 1 to 76, -1 to 89), sw.take(3).map { it.side to it.origin })
        assertEquals(0, sw.sumOf { it.side }); assertEquals(2612, sw.sumOf { it.known }); assertEquals(735, sw.sumOf { it.broken })
        assertEquals(17, pl.size)
        assertEquals(1, pl.sumOf { it.side }); assertEquals(2297, pl.sumOf { it.known }); assertEquals(2301, pl.sumOf { it.broken })
        assertEquals(1739, pl.sumOf { it.origin })
        // Deciding bar by bar on what was known then gives the same signals.
        val signals = (60 until b.size).mapNotNull { i ->
            val seen = b.subList(0, i + 1)
            LiquidityRules.signal(seen, LiquidityRules.zones(seen))?.let { i to it.side }
        }
        assertEquals(listOf(153 to -1, 239 to 1, 352 to 1), signals)
    }

    /** Flat bars at 54,000 (high 54,010, low 53,990) with one swing high / pool at 54,100 built in at bar [j]. */
    private fun setup(j: Int = 20, n: Int = 47): MutableList<Bar> {
        val o = MutableList(n) { 54_000.0 }; val h = MutableList(n) { 54_010.0 }
        val l = MutableList(n) { 53_990.0 }; val c = MutableList(n) { 54_000.0 }
        o[j] = 54_050.0; h[j] = 54_100.0; l[j] = 54_040.0; c[j] = 54_060.0            // the swing high; its wick 54,060-54,100
        o[j + 6] = 54_040.0; h[j + 6] = 54_070.0; l[j + 6] = 54_020.0; c[j + 6] = 54_030.0   // the second rejection
        o[j + 25] = 54_020.0; h[j + 25] = 54_150.0; l[j + 25] = 54_015.0; c[j + 25] = 54_140.0 // the break
        return bars(o, h, l, c).toMutableList()
    }

    @Test fun aPoolOnASwingHighTakenByACloseBuysTheCall() {
        val b = setup().subList(0, 46)
        val z = LiquidityRules.zones(b)
        val swing = z.single { it.kind == "swing" && it.side > 0 && it.top == 54_100.0 }
        assertEquals(20, swing.origin); assertEquals(40, swing.known); assertEquals(45, swing.broken)
        val pool = z.first { it.kind == "pool" && it.top == 54_100.0 }
        assertEquals(20, pool.origin); assertEquals(36, pool.known); assertEquals(45, pool.broken)
        val s = assertNotNull(LiquidityRules.signal(b, z))
        assertEquals(1, s.side); assertEquals(54_100.0, s.level); assertNull(s.target)
        // One bar earlier nothing is taken yet.
        val before = b.subList(0, 45)
        assertNull(LiquidityRules.signal(before, LiquidityRules.zones(before)))
    }

    @Test fun exitsOnAFailedBreakTheNextLiquidityOrNewLiquidity() {
        val all = setup()
        val sig = all[45].start
        // The bar after the break closes back under 54,100: the break failed.
        all[46] = Bar(all[46].start, 54_140.0, 54_145.0, 54_080.0, 54_090.0)
        val z = LiquidityRules.zones(all)
        assertEquals("failed_break", LiquidityRules.exitReason(1, 54_100.0, null, sig, all, z, emptyList()))
        // Holding above it: no reason yet.
        all[46] = Bar(all[46].start, 54_140.0, 54_160.0, 54_130.0, 54_150.0)
        assertNull(LiquidityRules.exitReason(1, 54_100.0, null, sig, all, LiquidityRules.zones(all), emptyList()))
        // A target touched by a 1-minute bar since the entry.
        val minute = Bar(all[46].start.plusMinutes(1), 54_150.0, 54_210.0, 54_140.0, 54_200.0)
        assertEquals("next_liquidity", LiquidityRules.exitReason(1, 54_100.0, 54_200.0, sig, all, LiquidityRules.zones(all), listOf(minute)))
        // A new level on the trade's side known after the entry bar.
        val newer = listOf(LiquidityRules.Zone("pool", 1, 54_170.0, 54_160.0, 46, 46))
        assertEquals("new_liquidity", LiquidityRules.exitReason(1, 54_100.0, null, sig, all, newer, emptyList()))
        // For a put the same logic mirrors.
        assertEquals("failed_break", LiquidityRules.exitReason(-1, 54_100.0, null, sig, all, emptyList(), emptyList()))   // closed back above
    }

    @Test fun foldsMinutesIntoChartBarsPerDay() {
        val ones = (0 until 30).map { k -> Bar(day.atTime(9, 15).plusMinutes(k.toLong()), 100.0 + k, 101.0 + k, 99.0 + k, 100.5 + k) } +
            listOf(Bar(day.plusDays(1).atTime(9, 15), 200.0, 202.0, 198.0, 201.0))
        val fifteen = LiquidityRules.fold(ones, 15)
        assertEquals(3, fifteen.size)
        assertEquals(Bar(day.atTime(9, 15), 100.0, 115.0, 99.0, 114.5), fifteen[0])
        assertEquals(day.plusDays(1).atTime(9, 15), fifteen[2].start)
        assertEquals(2, LiquidityRules.completed(fifteen, 15, day.atTime(10, 0)).size)
        assertEquals(1, LiquidityRules.completed(fifteen, 15, day.atTime(9, 44)).size)
        assertEquals(7, LiquidityRules.fold(ones, 5).size)            // 6 today + 1 tomorrow
        val thirty = LiquidityRules.fold(ones, 30)
        assertEquals(Bar(day.atTime(9, 15), 100.0, 130.0, 99.0, 129.5), thirty[0])     // 09:15-09:44 in one bar
        assertEquals(2, thirty.size)
    }

    @Test fun theStopIs15PercentBelowTheFillOnTheTick() {
        assertEquals(255.0, LiquidityRules.stopTrigger(300.0)!!, 0.0)
        assertEquals(170.0, LiquidityRules.stopTrigger(200.03)!!, 0.0)      // 170.0255 rounded down to the tick
        assertEquals(0.85, LiquidityRules.stopTrigger(1.0)!!, 0.0)
        assertNull(LiquidityRules.stopTrigger(0.05))                          // no room below the smallest tick
    }

    @Test fun theTurnExitsIndexStopAndTimeStop() {
        assertEquals(30.0, LiquidityRules.indexStopPoints("BANKNIFTY")); assertEquals(15.0, LiquidityRules.indexStopPoints("FINNIFTY"))
        val t = day.atTime(13, 5)
        val dip = listOf(Bar(t, 54_150.0, 54_150.0, 54_071.0, 54_140.0))
        assertFalse(LiquidityRules.indexStopHit(1, 54_100.0, 30.0, dip), "29 points back: held")
        assertTrue(LiquidityRules.indexStopHit(1, 54_100.0, 30.0, dip + Bar(t.plusMinutes(1), 54_100.0, 54_100.0, 54_069.0, 54_090.0)))
        assertTrue(LiquidityRules.indexStopHit(-1, 54_100.0, 30.0, listOf(Bar(t, 54_050.0, 54_131.0, 54_040.0, 54_060.0))), "a put: above")
        assertFalse(LiquidityRules.indexStopHit(-1, 54_100.0, 30.0, emptyList()))
        assertFalse(LiquidityRules.timeStopDue(t, t.plusMinutes(19)))
        assertTrue(LiquidityRules.timeStopDue(t, t.plusMinutes(20)))
        assertTrue(LiquidityRules.timeStopFails(200.0, 209.9))
        assertFalse(LiquidityRules.timeStopFails(200.0, 210.0))
    }

    @Test fun entriesOnlyFrom0920To1430() {
        assertFalse(LiquidityRules.mayEnterAt(day.atTime(9, 15)))
        assertTrue(LiquidityRules.mayEnterAt(day.atTime(9, 20)))
        assertTrue(LiquidityRules.mayEnterAt(day.atTime(14, 30)))
        assertFalse(LiquidityRules.mayEnterAt(day.atTime(14, 35)))
    }

    @Test fun poolsBrokenBeforeConfirmationAreDiscardedAndNothingIsAnEmptySeries() {
        assertTrue(LiquidityRules.zones(emptyList()).isEmpty())
        assertNull(LiquidityRules.signal(emptyList(), emptyList()))
        // A wick rejected twice, then closed through before its 10 confirmation bars: no pool.
        val n = 20
        val o = MutableList(n) { 100.0 }; val h = MutableList(n) { 100.5 }; val l = MutableList(n) { 99.5 }; val c = MutableList(n) { 100.0 }
        h[2] = 110.0; h[8] = 109.0; c[12] = 111.0; o[12] = 100.0; h[12] = 111.5
        val pools = LiquidityRules.poolZones(bars(o, h, l, c))
        assertTrue(pools.none { it.side > 0 && it.top == 110.0 })
    }
}
