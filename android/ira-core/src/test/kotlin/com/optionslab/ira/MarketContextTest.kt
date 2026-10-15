package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TODO I6, I10, I11, I12, I13 (13 Oct): the event shield, the expiry card, days like today, the level map, explain a candle. */
class MarketContextTest {
    // ---- I6 event shield ------------------------------------------------------------------------------------------------

    @Test fun eventGroupsByName() {
        assertEquals(EventShield.Group.INDIA_POLICY, EventShield.group("RBI policy"))
        assertEquals(EventShield.Group.INDIA_POLICY, EventShield.group("Union Budget"))
        assertEquals(EventShield.Group.US_MACRO, EventShield.group("US Fed decision overnight (FOMC)"))
        assertEquals(EventShield.Group.US_MACRO, EventShield.group("US CPI"))
        assertEquals(EventShield.Group.RESULTS, EventShield.group("HDFC Bank results"))
        assertEquals(EventShield.Group.OTHER, EventShield.group("Diwali Muhurat session"))
    }

    @Test fun expiriesAndRebalancesAreNotShieldEvents() {
        assertFalse(EventShield.counts("NIFTY expiry"))
        assertFalse(EventShield.counts("NSE index rebalance"))
        assertFalse(EventShield.counts("NIFTY expiry moved to this day (Tuesday 6 is a holiday)"))
        assertTrue(EventShield.counts("RBI policy"))
    }

    @Test fun warningCarriesNumbersAndSampleSizes() {
        val d = LocalDate.of(2026, 12, 4)
        val w = EventShield.warning(listOf("RBI policy"), d, d.minusDays(1))!!
        assertTrue(w.startsWith("Fri 4 Dec (next session) is an event day: RBI policy."), w)
        assertTrue("BANKNIFTY moved 1.5x a normal day" in w && "15 such days" in w && "a tested difference" in w, w)
        assertTrue("not a forecast" in w, w)
        val us = EventShield.warning(listOf("US Fed decision overnight (FOMC)"), d, d)!!
        assertTrue(us.startsWith("Today is an event day"), us)
        assertTrue("bigger gap" in us && "39 such days" in us, us)
        assertNull(EventShield.warning(listOf("NIFTY expiry"), d, d))
    }

    @Test fun pauseIsOffByDefaultPaperOnlyAndNeverTheFrozenArm() {
        val ev = listOf("RBI policy")
        assertEquals(EventShield.Mode.OFF, EventShield.DEFAULT_MODE)
        assertEquals(EventShield.Mode.OFF, EventShield.mode(null))
        assertEquals(EventShield.Mode.OFF, EventShield.mode("nonsense"))
        assertEquals(EventShield.GO, EventShield.decide(EventShield.Mode.OFF, ev, live = false, frozen = false))
        // SHADOW: logged, never skipped
        val sh = EventShield.decide(EventShield.Mode.SHADOW, ev, live = false, frozen = false)
        assertFalse(sh.block); assertNotNull(sh.shadow)
        // ON: paper skipped, live never, the frozen arm never (not even logged)
        assertTrue(EventShield.decide(EventShield.Mode.ON, ev, live = false, frozen = false).block)
        assertEquals(EventShield.GO, EventShield.decide(EventShield.Mode.ON, ev, live = true, frozen = false))
        assertEquals(EventShield.GO, EventShield.decide(EventShield.Mode.ON, ev, live = false, frozen = true))
        assertEquals(EventShield.GO, EventShield.decide(EventShield.Mode.SHADOW, ev, live = false, frozen = true))
        // no counting event: nothing
        assertEquals(EventShield.GO, EventShield.decide(EventShield.Mode.ON, listOf("NIFTY expiry"), live = false, frozen = false))
    }

    // ---- I10 expiry card ------------------------------------------------------------------------------------------------

    @Test fun expiryCardReadsTheCurveAndSaysMaxPainIsNotAForecast() {
        assertNull(ExpiryCard.leftAt(ExpiryCard.NIFTY, LocalTime.of(9, 16)))
        assertEquals(61.0, ExpiryCard.leftAt(ExpiryCard.NIFTY, LocalTime.of(10, 0))!!, 1e-9)
        assertEquals(55.0, ExpiryCard.leftAt(ExpiryCard.NIFTY, LocalTime.of(10, 30))!!, 1e-9)
        assertEquals(0.0, ExpiryCard.leftAt(ExpiryCard.NIFTY, LocalTime.of(15, 28))!!, 1e-9)
        val l = ExpiryCard.lines("SENSEX", LocalTime.of(13, 0))
        assertTrue(l[0].contains("about 24%") && l[0].contains("53 NIFTY expiries") && l[0].contains("measured on NIFTY"), l[0])
        assertTrue(l.any { "Max pain is not a forecast" in it })
        assertTrue(ExpiryCard.lines("BANKNIFTY", LocalTime.of(9, 0))[0].contains("11 BANKNIFTY expiries"))
    }

    // ---- I11 days like today ---------------------------------------------------------------------------------------------

    private val csv = buildString {
        appendLine("day,gap_pct,vix_0920,prev_range_pct,is_expiry,event,rest_range_pct,rest_ret_pct,range_pct,cc_pct,net_ORB,net_ORB_Fresh")
        var d = LocalDate.of(2025, 1, 1)
        for (i in 0 until 60) {
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
            val gap = (i % 10 - 5) / 10.0
            appendLine("$d,$gap,${12 + i % 7},${0.8 + (i % 5) / 10.0},${if (i % 7 == 0) 1.0 else 0.0},0.0,0.9,${if (gap > 0) 0.3 else -0.2},1.0,0.1,${if (i % 3 == 0) -500.0 else 0.0},0.0")
            d = d.plusDays(1)
        }
    }

    @Test fun csvParsesWithArms() {
        val rows = DaysLikeToday.parseCsv(csv)
        assertEquals(60, rows.size)
        assertEquals(setOf("ORB", "ORB Fresh"), rows[0].arms.keys)
        assertEquals(DaysLikeToday.RESEARCH, rows[0].armsFrom)
        assertTrue(rows.any { it.expiry })
    }

    @Test fun nearestUsesOnlyEarlierDaysAndBriefSaysContext() {
        val rows = DaysLikeToday.parseCsv(csv)
        val today = DaysLikeToday.Row(LocalDate.of(2025, 6, 2), 0.4, 13.0, 0.9, expiry = false, event = false)
        val nb = DaysLikeToday.nearest(rows, today)
        assertEquals(DaysLikeToday.K, nb.size)
        assertTrue(nb.all { it.first.day < today.day })
        assertTrue(nb.zipWithNext().all { it.first.second <= it.second.second })
        val b = DaysLikeToday.brief("BANKNIFTY", today, rows)!!
        assertTrue(b[0].contains("context, not a forecast") && b[0].contains("10 nearest of 60"), b[0])
        assertTrue(b.any { it.startsWith("ORB on those days") && "research replay after costs" in it }, b.toString())
        assertTrue(b.last().contains("did not forecast the direction"))
        // a day in the middle of the table: later rows are never neighbours
        val mid = rows[45].copy(restRangePct = null, restRetPct = null)
        assertTrue(DaysLikeToday.nearest(rows, mid).all { it.first.day < mid.day })
        assertNull(DaysLikeToday.brief("BANKNIFTY", rows[10], rows), "too few past sessions")
    }

    private fun session(d: LocalDate, open: Double, drift: Double, wiggle: Double = 20.0): List<Candle> = (0 until 375).map { m ->
        val px = open + drift * m / 374.0 + (if (m % 7 < 3) wiggle else -wiggle) * ((m % 5) / 5.0)
        Candle(d.atTime(9, 15).plusMinutes(m.toLong()), px, px + 5, px - 5, px)
    }

    @Test fun phoneRowsFromCandles() {
        val days = listOf(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9))
        val bars = session(days[0], 50_000.0, 100.0) + session(days[1], 50_200.0, -50.0) + session(days[2], 50_000.0, 0.0).take(30)
        val vix = days.map { Candle(it.atTime(9, 20), 13.0, 13.0, 13.0, 13.0) }
        val rows = DaysLikeToday.fromCandles(bars, vix, setOf(days[1]), emptySet(), days[2], mapOf(days[1] to mapOf("ORB" to 250.0)))
        assertEquals(listOf(days[1], days[2]), rows.map { it.day })
        assertTrue(rows[0].expiry); assertEquals("your paper account", rows[0].armsFrom)
        assertNotNull(rows[0].restRetPct)
        assertNull(rows[1].restRetPct, "today has no outcome yet")
        val pc = bars.last { it.t.toLocalDate() == days[0] }.c
        val op = bars.first { it.t.toLocalDate() == days[1] }.o
        assertEquals((op - pc) / pc * 100, rows[0].gapPct, 1e-9)
        val merged = DaysLikeToday.merge(DaysLikeToday.parseCsv(csv), rows)
        assertEquals(62, merged.size)
    }

    // ---- I12 level map ---------------------------------------------------------------------------------------------------

    private fun b(o: Double, h: Double, l: Double, c: Double) = LevelMap.Bar5(o, h, l, c)

    @Test fun levelTestMatchesTheResearchRules() {
        assertEquals(true to true, LevelMap.test(listOf(b(98.0, 99.0, 97.0, 98.5), b(98.5, 100.02, 98.0, 99.0), b(99.0, 99.5, 98.0, 98.2)), 100.0))
        assertEquals(true to false, LevelMap.test(listOf(b(98.0, 99.0, 97.0, 98.5), b(98.5, 100.02, 98.0, 99.0), b(99.0, 101.0, 98.0, 100.6)), 100.0))
        assertEquals(false to false, LevelMap.test(listOf(b(98.0, 99.0, 97.0, 98.5), b(98.5, 99.0, 98.0, 99.0)), 100.0))
        assertEquals(true to true, LevelMap.test(listOf(b(102.0, 103.0, 101.0, 102.0), b(102.0, 102.0, 99.98, 100.5), b(100.5, 101.0, 100.0, 100.8)), 100.0))
        assertEquals(false to false, LevelMap.test(listOf(null, null), 100.0))
    }

    @Test fun levelsComeFromEarlierSessionsOnly() {
        var d = LocalDate.of(2026, 9, 1)
        val days = ArrayList<LocalDate>()
        while (days.size < 12) { if (d.dayOfWeek.value <= 5) days += d; d = d.plusDays(1) }
        val bars = days.flatMapIndexed { i, x -> session(x, 50_000.0 + i * 30, if (i % 2 == 0) 150.0 else -120.0) }
        val today = days.last()
        val map = LevelMap.map(bars, today, maxOiCall = 50_500.0, maxOiPut = 49_500.0, vwap = listOf("VWAP" to 50_100.0, "+1σ" to 50_200.0))
        val y = bars.filter { it.t.toLocalDate() == days[days.size - 2] }
        assertEquals(y.maxOf { it.h }, map.first { it.type == LevelMap.Type.PDH }.price, 1e-9)
        assertEquals(y.last().c, map.first { it.type == LevelMap.Type.PDC }.price, 1e-9)
        // today's bars change nothing in the levels or the counts
        val fixed = setOf(LevelMap.Type.PDH, LevelMap.Type.PDL, LevelMap.Type.PDC, LevelMap.Type.P, LevelMap.Type.R1, LevelMap.Type.S1,
            LevelMap.Type.R2, LevelMap.Type.S2)
        val without = LevelMap.map(bars.filter { it.t.toLocalDate() < today }, today)
        assertEquals(without.filter { it.type in fixed }.map { Triple(it.type, it.price, it.tested) },
            map.filter { it.type in fixed }.map { Triple(it.type, it.price, it.tested) })
        val pdh = map.first { it.type == LevelMap.Type.PDH }
        assertTrue(pdh.tested!! <= 12 - LevelMap.SWING_DAYS)
        assertTrue(pdh.label().startsWith("PDH ") && (" of " in pdh.label() || "2024-26" in pdh.label()), pdh.label())
        assertTrue(map.first { it.type == LevelMap.Type.MAX_OI_CALL }.label().contains("2024-26: held"))
        assertEquals("+1σ", map.first { it.type == LevelMap.Type.VWAP_BAND }.name)
        assertEquals(12 - 1 - LevelMap.SWING_DAYS, LevelMap.counted(bars, today))
        assertTrue("not places that hold" in LevelMap.note(6))
    }

    // ---- I13 explain a candle -------------------------------------------------------------------------------------------

    private val t0 = LocalDateTime.of(2026, 10, 12, 10, 35)

    @Test fun explainSaysPlainlyWhatIsMissing() {
        val l = CandleExplain.explain(CandleExplain.Inputs("BANKNIFTY", t0, 5, 52_100.0, 52_180.0, 52_060.0, 52_150.0, null))
        val all = l.joinToString(" ")
        assertTrue(l[0].startsWith("BANKNIFTY 5-minute candle of 2026-10-12 10:35-10:40"), l[0])
        assertTrue("an index has no volume" in all)
        assertTrue("India VIX: missing" in all)
        assertTrue("not recorded" in all && "were not recorded" in all, all)
        assertTrue("no headline on the phone" in all)
        assertEquals(CandleExplain.END, l.last())
    }

    @Test fun explainUsesTheRecordingsFlowAndNews() {
        val chain0 = listOf(CandleExplain.Leg(52_100.0, "CE", 210.0, 1_200_000, null, 0.131), CandleExplain.Leg(52_100.0, "PE", 190.0, 900_000, null, 0.128),
            CandleExplain.Leg(52_500.0, "CE", 60.0, 2_500_000, null, 0.12), CandleExplain.Leg(51_500.0, "PE", 40.0, 2_000_000, null, 0.14))
        val chain1 = chain0.map { if (it.strike == 52_100.0 && it.right == "CE") it.copy(oi = 1_243_000) else it }
        val l = CandleExplain.explain(CandleExplain.Inputs("BANKNIFTY", t0, 5, 52_100.0, 52_180.0, 52_060.0, 52_170.0, null,
            dayRanges = List(20) { 60.0 }, vixStart = 13.2, vixEnd = 13.4,
            recStart = CandleExplain.Recorded(52_100.0, 52_190.0, 90.0, 13.2, chain0),
            recEnd = CandleExplain.Recorded(52_150.0, 52_250.0, 100.0, 13.4, chain1),
            flow = listOf(CandleExplain.Flow(1200, 300, null), CandleExplain.Flow(800, -100, null)),
            news = listOf(t0.minusMinutes(4) to "RBI keeps the repo rate unchanged", t0.minusMinutes(40) to "Old story")))
        val all = l.joinToString(" ")
        assertTrue("2.0x the median" in all, all)
        assertTrue("closed near its high" in all)
        assertTrue("India VIX 13.40 (from 13.20 at the start)" in all, all)
        assertTrue("basis +100 points" in all && "(from +90)" in all, all)
        assertTrue("52,100 CE OI 12.4 lakh (+43,000 over the candle), IV 13.1%" in all, all)
        assertTrue("Largest OI then: 52,500 CE and 51,500 PE" in all, all)
        assertTrue("delta +200" in all && "2 recorded minutes" in all, all)
        assertTrue("10:31 \"RBI keeps" in all && "Old story" !in all, all)
        val stock = CandleExplain.explain(CandleExplain.Inputs("HDFCBANK", t0, 1, 1.0, 1.0, 1.0, 1.0, 5_000.0, recordable = false))
        assertTrue(stock.any { "NIFTY and BANKNIFTY (and their options) only" in it })
        assertTrue(stock.none { "order flow" in it })
    }
}
