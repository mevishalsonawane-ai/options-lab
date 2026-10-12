package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TickZoneTest {
    /** 12 Oct 2026 10:00:00 IST, a minute's start. */
    private val t0 = java.time.LocalDateTime.of(2026, 10, 12, 10, 0).atZone(MarketRecord.IST).toEpochSecond()

    private fun snap(ltp: Double, vol: Long, tbq: Long = 1_000, tsq: Long = 1_000, bid: Double = ltp - 0.05, ask: Double = ltp + 0.05,
                     atp: Double? = ltp, token: Long = 7L) = TickZone.Snap(
        token, ltp, vol, atp, 5_000L, tbq, tsq, 15L, null, null,
        listOf(TickZone.Level(bid, 300, 3), TickZone.Level(bid - 0.05, 300, 2)),
        listOf(TickZone.Level(ask, 100, 1), TickZone.Level(ask + 0.05, 100, 1)), 200L,
    )

    // ---- the formulas -------------------------------------------------------------------------------------------------

    @Test fun theTickRuleSignsUpDownAndCarriesTheSideOnUnchanged() {
        assertEquals(0, TickZone.side(100.0, null, 0))
        assertEquals(1, TickZone.side(100.5, 100.0, -1))
        assertEquals(-1, TickZone.side(99.5, 100.0, 1))
        assertEquals(1, TickZone.side(100.0, 100.0, 1))
        assertEquals(-1, TickZone.side(100.0, 100.0, -1))
        assertEquals(0, TickZone.side(100.0, 100.0, 0))
    }

    @Test fun imbalanceAndSpread() {
        assertEquals(0.5, TickZone.imbalance(300, 100)!!, 1e-12)
        assertEquals(-1.0, TickZone.imbalance(0, 50)!!, 1e-12)
        assertNull(TickZone.imbalance(0, 0))
        assertNull(TickZone.imbalance(null, 5))
        assertEquals(0.1, TickZone.spread(listOf(TickZone.Level(100.0, 1, 1)), listOf(TickZone.Level(100.1, 1, 1)))!!, 1e-9)
        assertNull(TickZone.spread(emptyList(), listOf(TickZone.Level(100.1, 1, 1))))
        assertNull(TickZone.spread(listOf(TickZone.Level(101.0, 1, 1)), listOf(TickZone.Level(100.0, 1, 1))), "crossed")
    }

    @Test fun theQuantileIsNearestRank() {
        val h = java.util.TreeMap<Long, Int>()
        (1L..100L).forEach { h[it] = 1 }
        assertEquals(99L, TickZone.quantile(h, 100, 0.99))
        assertEquals(50L, TickZone.quantile(h, 100, 0.5))
        assertEquals(1L, TickZone.quantile(h, 100, 0.0))
        assertNull(TickZone.quantile(java.util.TreeMap(), 0, 0.99))
        // Repeated sizes: 90 of 10 and 10 of 500 -> the 99th percentile is 500.
        val r = java.util.TreeMap(mapOf(10L to 90, 500L to 10))
        assertEquals(500L, TickZone.quantile(r, 100, 0.99))
        assertEquals(10L, TickZone.quantile(r, 100, 0.9))
    }

    @Test fun absorptionNeedsBigVolumeAtOnePriceWithThePriceStill() {
        // 3x the average, 80% at one price, range 0.05 on a 200 option.
        assertTrue(TickZone.absorbed(3_000, 2_400, 1_000.0, 10, 200.05, 200.0, 200.0))
        assertFalse(TickZone.absorbed(1_500, 1_400, 1_000.0, 10, 200.05, 200.0, 200.0), "not big enough")
        assertFalse(TickZone.absorbed(3_000, 1_000, 1_000.0, 10, 200.05, 200.0, 200.0), "spread over prices")
        assertFalse(TickZone.absorbed(3_000, 2_400, 1_000.0, 10, 201.0, 200.0, 200.0), "the price moved")
        assertFalse(TickZone.absorbed(3_000, 2_400, 1_000.0, 3, 200.05, 200.0, 200.0), "too few minutes to average")
        assertFalse(TickZone.absorbed(3_000, 2_400, null, 10, 200.05, 200.0, 200.0))
        // A future at 52000: up to 10.4 points of range is "still".
        assertTrue(TickZone.absorbed(30_000, 20_000, 5_000.0, 30, 52_010.0, 52_000.0, 52_005.0))
        assertFalse(TickZone.absorbed(30_000, 20_000, 5_000.0, 30, 52_020.0, 52_000.0, 52_005.0))
    }

    // ---- one instrument's minute ----------------------------------------------------------------------------------------

    @Test fun aMinutesDeltaCvdImbalanceSpreadAndVwap() {
        val f = TickZone.Flow(7L)
        // 10:00:00 first second (no volume known before it), then up (buy 50), unchanged (buy 20), down (sell 30), unchanged (sell 10).
        assertNull(f.add(t0, snap(100.0, 1_000, tbq = 3_000, tsq = 1_000)))
        f.add(t0 + 1, snap(100.5, 1_050))
        f.add(t0 + 2, snap(100.5, 1_070))
        f.add(t0 + 3, snap(100.0, 1_100))
        f.add(t0 + 4, snap(100.0, 1_110, tbq = 1_000, tsq = 3_000, atp = 100.2))
        f.miss(t0 + 5)
        // The next minute's first second closes 10:00.
        val a = assertNotNull(f.add(t0 + 60, snap(100.0, 1_110)))
        assertEquals(t0, a.minuteSec)
        assertEquals(110L, a.volume)
        assertEquals(70L, a.buy)
        assertEquals(40L, a.sell)
        assertEquals(0L, a.unsigned)
        assertEquals(30L, a.delta)
        assertEquals(30L, a.cvd)
        assertEquals(t0, a.cvdFromSec)
        assertEquals(100.0, a.open); assertEquals(100.5, a.high); assertEquals(100.0, a.low); assertEquals(100.0, a.close)
        assertEquals(100.2, a.vwap)
        assertEquals(5_000L, a.oi)
        // OBI: 0.5, 0, 0, 0, -0.5 -> average 0, close -0.5.
        assertEquals(0.0, a.obiAvg!!, 1e-12)
        assertEquals(-0.5, a.obiClose!!, 1e-12)
        // Top-5 depth: 600 bid vs 200 ask every second.
        assertEquals(0.5, a.depthObiAvg!!, 1e-12)
        assertEquals(0.1, a.spreadAvg!!, 1e-9)
        assertEquals(5, a.seconds)
        assertEquals(1, a.missing)
        assertNull(a.largeThreshold, "too few sizes today")
        assertFalse(a.absorb)
        // CVD runs on.
        f.add(t0 + 61, snap(99.0, 1_210))
        val b = assertNotNull(f.close())
        assertEquals(-100L, b.delta)
        assertEquals(-70L, b.cvd)
    }

    @Test fun aRiseAfterAGapCountsInTheDeltaButIsNoTradeSize() {
        val f = TickZone.Flow(7L)
        f.add(t0, snap(100.0, 0))
        // Warm the size history with 300 one-second sizes of 10 (5 minutes).
        var v = 0L
        var px = 100.0
        for (s in 1..300) { v += 10; px += if (s % 2 == 0) 0.05 else -0.05; f.add(t0 + s, snap(px, v)) }
        // A big second (500) and a rise after a 10-second gap (also 500).
        v += 500; f.add(t0 + 301, snap(px + 0.05, v))
        for (s in 302..311) f.miss(t0 + s)
        v += 500; f.add(t0 + 312, snap(px + 0.10, v))
        val a = assertNotNull(f.close())
        assertEquals(10L, a.largeThreshold)
        assertEquals(1, a.largeCount, "the gap's rise is not a print")
        assertEquals(500L, a.largeVolume)
        assertEquals(1_010L, a.volume, "the gap's rise still counts in the volume and the delta")
        assertTrue(a.buy >= 1_000L, "both rises were upticks: buys")
        assertEquals(10, a.missing)
    }

    @Test fun absorptionIsFlaggedWithTheSideAbsorbing() {
        val f = TickZone.Flow(7L)
        var v = 0L
        f.add(t0, snap(200.0, v))
        // Six quiet minutes: 10 a second, the price swinging a point.
        for (s in 1 until 360) { v += 10; f.add(t0 + s, snap(if (s % 2 == 0) 200.0 else 201.0, v)) }
        // The seventh minute: 100 a second sold into 200.00 (the price ticks down once, then holds).
        f.add(t0 + 360, snap(200.05, v))
        for (s in 361 until 420) { v += 100; f.add(t0 + s, snap(200.0, v)) }
        val a = assertNotNull(f.add(t0 + 420, snap(200.0, v)))
        assertEquals(t0 + 360, a.minuteSec)
        assertTrue(a.absorb, "$a")
        assertEquals(200.0, a.absorbPrice)
        assertEquals("bid", a.absorbSide, "sellers hit a bid that held")
    }

    @Test fun aVolumeResetIsNotATrade() {
        val f = TickZone.Flow(7L)
        f.add(t0, snap(100.0, 5_000))
        f.add(t0 + 1, snap(101.0, 10))         // a new session's volume
        f.add(t0 + 2, snap(102.0, 40))
        val a = assertNotNull(f.close())
        assertEquals(30L, a.volume)
        assertEquals(30L, a.buy)
    }

    @Test fun anIndexWithoutVolumeOrDepthStillHasItsBar() {
        val f = TickZone.Flow(9L)
        val vix = TickZone.Snap(9L, 13.2, null, null, null, null, null, null, null, 1L, emptyList(), emptyList(), 100)
        f.add(t0, vix)
        f.add(t0 + 1, vix.copy(ltp = 13.4))
        val a = assertNotNull(f.close())
        assertEquals(13.4, a.close)
        assertEquals(0L, a.volume)
        assertNull(a.obiAvg); assertNull(a.spreadAvg); assertNull(a.vwap)
        assertTrue(TickZone.agg(a, TickZone.Inst(9L, "INDIA VIX", "INDIAVIX", TickZone.Kind.VIX)).contains("\"precision\":\"exact\""))
        // A minute with nothing at all is no row.
        assertNull(TickZone.Flow(1L).close())
    }

    // ---- gaps and the queue ---------------------------------------------------------------------------------------------

    @Test fun gapsJoinIntoRuns() {
        val g = TickZone.Gaps()
        assertNull(g.miss(7L, 100, "stale"))
        assertNull(g.miss(7L, 101, "stale"))
        assertNull(g.miss(7L, 102, "stale"))
        // Another reason closes the run.
        assertEquals(TickZone.Run(7L, 100, 102, "stale"), g.miss(7L, 103, "stream down"))
        assertEquals(TickZone.Run(7L, 103, 103, "stream down"), g.ok(7L))
        assertNull(g.ok(7L))
        g.miss(8L, 200, "no tick"); g.miss(0L, 150, "queue full")
        assertEquals(listOf(150L, 200L), g.closeAll().map { it.from })
        assertTrue(g.closeAll().isEmpty())
        assertTrue(TickZone.gap(TickZone.Run(0L, t0, t0 + 4, "queue full")).let { it.contains("\"n\":5") && !it.contains("\"k\"") })
    }

    @Test fun theQueueDropsTheOldestSecondAndRemembersIt() {
        val q = TickZone.SecondQueue(cap = 3)
        for (s in 0L until 7L) q.offer(TickZone.Second(t0 + s, null, emptyList(), emptyList(), emptyList()))
        assertEquals(4L, q.dropped)
        val (items, runs) = q.drain()
        assertEquals(listOf(t0 + 4, t0 + 5, t0 + 6), items.map { it.sec })
        assertEquals(listOf(t0..t0 + 3), runs)
        assertEquals(0, q.size())
        assertTrue(q.drain().second.isEmpty())
    }

    // ---- the watch-list and the rows -------------------------------------------------------------------------------------

    @Test fun theWatchListIsBothFuturesAtmPlusMinusThreeOfTheCurrentExpiryAndVix() {
        val near = LocalDate.of(2026, 10, 14)
        val far = LocalDate.of(2026, 10, 28)
        var tok = 1L
        fun opts(und: String, centre: Double, step: Double) = listOf(near, far).flatMap { e ->
            (-8..8).flatMap { i -> listOf(TickZone.Kind.CE, TickZone.Kind.PE).map { k ->
                TickZone.Inst(tok++, "$und-${e}-${centre + i * step}$k", und, k, centre + i * step, e) } }
        }
        val futs = mapOf("BANKNIFTY" to TickZone.Inst(900L, "BANKNIFTY26OCTFUT", "BANKNIFTY", TickZone.Kind.FUT),
            "NIFTY" to TickZone.Inst(901L, "NIFTY26OCTFUT", "NIFTY", TickZone.Kind.FUT))
        val vix = TickZone.Inst(264_969L, "INDIA VIX", "INDIAVIX", TickZone.Kind.VIX)
        val list = TickZone.watchList(futs, mapOf("BANKNIFTY" to opts("BANKNIFTY", 52_000.0, 100.0), "NIFTY" to opts("NIFTY", 25_000.0, 50.0)),
            mapOf("BANKNIFTY" to 52_030.0, "NIFTY" to 24_990.0), vix)
        assertEquals(2 + 2 * 7 * 2 + 1, list.size)
        val bn = list.filter { it.und == "BANKNIFTY" && it.kind != TickZone.Kind.FUT }
        assertEquals((-3..3).map { 52_000.0 + it * 100 }, bn.mapNotNull { it.strike }.distinct())
        assertTrue(bn.all { it.expiry == near })
        assertEquals(TickZone.Kind.VIX, list.last().kind)
        assertEquals(900L, list.first().token)
        // No spot: the options wait, the future and VIX are followed.
        assertEquals(listOf(900L, 901L, 264_969L), TickZone.watchList(futs, emptyMap(), emptyMap(), vix).map { it.token })
    }

    @Test fun theRowsCarryPrecisionAndTheBook() {
        val t = TickZone.tick(t0, snap(100.0, 1_000))
        assertTrue(t.startsWith("{\"t\":\"T\",\"s\":$t0,\"k\":7,\"ltp\":100,\"v\":1000,\"atp\":100,\"oi\":5000,\"tbq\":1000,\"tsq\":1000"), t)
        assertTrue(t.contains("\"b\":[[99.95,300,3],[99.9,300,2]]") && t.contains("\"a\":[[100.05,100,1],[100.1,100,1]]"), t)
        assertTrue(t.endsWith("\"precision\":\"exact\"}"), t)
        val i = TickZone.instruments(t0, listOf(TickZone.Inst(5L, "NIFTY26OCT25000CE", "NIFTY", TickZone.Kind.CE, 25_000.0, LocalDate.of(2026, 10, 14))))
        assertEquals("{\"t\":\"I\",\"s\":$t0,\"list\":[[5,\"NIFTY26OCT25000CE\",\"NIFTY\",\"CE\",25000,\"2026-10-14\"]]}", i)
        assertTrue(TickZone.header(TickZone.TICKS_FILE, LocalDate.of(2026, 10, 12)).contains("\"schema\":1"))
        assertTrue(TickZone.note(t0, "ticks", "day budget full").contains("\"at\":\"10:00:00\""))
        assertEquals(LocalDate.of(2026, 10, 12), TickZone.dayOf(t0))
        assertEquals(java.time.LocalTime.of(10, 0), TickZone.timeOf(t0))
    }

    @Test fun rawTickFilesGoAfterTenDaysOrOverTheCapNeverToday() {
        val today = LocalDate.of(2026, 10, 30)
        val days = (0L..14L).map { LiveRecord.DayDir(today.minusDays(it), 50_000_000L) }
        val gone = TickZone.pruneTicks(days, today)
        assertEquals((10L..14L).map { today.minusDays(it) }.sorted(), gone)
        val capped = TickZone.pruneTicks(days, today, cap = 200_000_000L)
        assertEquals(11, capped.size)
        assertFalse(today in capped)
        assertTrue(TickZone.overTickBudget(TickZone.TICK_DAY_BUDGET_BYTES))
        assertFalse(TickZone.overTickBudget(1))
    }

    @Test fun theCardSaysWhatIsRecorded() {
        val now = t0 * 1000
        val live = TickZone.card(TickZone.Status(31, 12_000_000, 3, 0, t0 - 5), now).toMap()
        assertEquals("31 instruments, every second", live["Tick zone"])
        assertEquals("3 (raw seconds kept 10 days)", live["Tick days kept"])
        assertFalse("Seconds dropped (writer behind)" in live)
        val idle = TickZone.card(TickZone.Status(0, 0, 0, 4, null, full = true), now).toMap()
        assertEquals("waiting for the Zerodha price stream", idle["Tick zone"])
        assertEquals("4", idle["Seconds dropped (writer behind)"])
        assertTrue(idle["Ticks today"]!!.endsWith("(full for today)"))
        assertTrue(TickZone.card(TickZone.Status(31, 0, 1, 0, t0 - 600), now).toMap()["Tick zone"]!!.contains("while the market is open"))
        assertTrue(TickZone.README.contains("ticks.jsonl.gz") && TickZone.README.contains("orderflow_1m.jsonl.gz"))
    }
}
