package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoloTest {
    private val d = LocalDate.of(2026, 10, 1)
    private fun bar(m: Int, o: Double, h: Double, l: Double, c: Double) = Candle(d.atTime(9, 15).plusMinutes(m.toLong()), o, h, l, c)

    /** A flat day with a big green 15-minute candle at 09:45 (minutes 30-44: 24000 -> 24100), then a pullback. */
    private fun day(pullTo: Double, breakLow: Boolean = false): List<Candle> = (0 until 375).map { m ->
        when {
            m < 30 -> bar(m, 24_000.0, 24_005.0, 23_995.0, 24_000.0)
            m < 45 -> { val o = 24_000.0 + (m - 30) * 100.0 / 15; bar(m, o, o + 100.0 / 15, o, o + 100.0 / 15) }
            m < 60 -> { val c = 24_100.0 - (m - 44) * (24_100.0 - pullTo) / 15; bar(m, c + 2, c + 3, if (breakLow && m == 50) 23_990.0 else c - 1, c) }
            else -> bar(m, pullTo, pullTo + 2, pullTo - 2, pullTo)
        }
    }

    @Test fun aBigCandleThenAPullbackIsATrade() {
        val day = day(pullTo = 24_055.0)
        val s = (0 until 120).firstNotNullOfOrNull { Solo.signal(day, it, big = 50.0) }
        assertNotNull(s); assertTrue(s.call)
        assertEquals(24_000.0, s.level, 0.01)
        assertEquals(s.index + 2 * (s.index - s.level), s.target, 0.01)
        assertTrue(s.entryMinute in 46..105, "${s.entryMinute}")
        assertTrue(s.why.contains("big green 15-minute candle at 09:45"), s.why)
        // A small candle is not big; a pullback too shallow is no entry; a broken level is no entry.
        assertNull((0 until 120).firstNotNullOfOrNull { Solo.signal(day, it, big = 150.0) })
        assertNull((0 until 120).firstNotNullOfOrNull { Solo.signal(day(pullTo = 24_090.0), it, big = 50.0) })
        assertNull((0 until 120).firstNotNullOfOrNull { Solo.signal(day(pullTo = 24_055.0, breakLow = true), it, big = 50.0) })
    }

    @Test fun exitsAndTheRiskBook() {
        val s = Solo.Signal(true, 50, 24_050.0, 24_000.0, 24_150.0, 30, "")
        assertEquals(Solo.Exit.STOP, Solo.exit(s, bar(60, 24_010.0, 24_200.0, 23_999.0, 24_100.0), 60), "the stop counts first")
        assertEquals(Solo.Exit.TARGET, Solo.exit(s, bar(60, 24_100.0, 24_151.0, 24_090.0, 24_140.0), 60))
        assertEquals(Solo.Exit.TIME, Solo.exit(s, bar(Solo.CUT, 24_060.0, 24_070.0, 24_050.0, 24_060.0), Solo.CUT))
        assertNull(Solo.exit(s, bar(60, 24_060.0, 24_070.0, 24_050.0, 24_060.0), 60))
        val slow = Solo.Rules(slowMinutes = 20)
        assertEquals(Solo.Exit.SLOW, Solo.exit(s, bar(70, 24_050.0, 24_055.0, 24_045.0, 24_050.0), 70, best = 10.0, r = slow))
        assertNull(Solo.exit(s, bar(70, 24_050.0, 24_055.0, 24_045.0, 24_050.0), 70, best = 30.0, r = slow))
        val r = Solo.Rules(maxPerDay = 2)
        assertNull(Solo.Day().canTrade(r, 5_000.0))
        assertTrue(Solo.Day().after(-100.0).after(-100.0).canTrade(Solo.Rules(maxPerDay = 3), 5_000.0)!!.contains("2 losses"))
        assertTrue(Solo.Day().after(-6_000.0).canTrade(Solo.Rules(maxPerDay = 5), 5_000.0)!!.contains("loss limit"))
        assertTrue(Solo.Day().after(100.0).canTrade(Solo.Rules(maxPerDay = 1), 5_000.0)!!.contains("1 trades"))
        assertEquals(24_500, Solo.strike(24_512.0, 50, true))
        assertEquals(24_450, Solo.strike(24_512.0, 50, true, itm = 1))
        assertEquals(24_550, Solo.strike(24_512.0, 50, false, itm = 1))
    }

    @Test fun theBacktestPlaysTheRulesWithOptionPrices() {
        // 6 flat days to learn "big", then the day above with a call priced at 100 + (index - 24000) / 2.
        val flat = (0 until 375).map { bar(it, 24_000.0, 24_010.0, 23_990.0, 24_000.0 + if (it % 2 == 0) 3 else -3) }
        val days = (0 until 7).map { i ->
            val dd = d.plusDays(i.toLong())
            val ix = (if (i < 6) flat else day(pullTo = 24_055.0, breakLow = false).mapIndexed { m, b ->
                if (m > 100) b.copy(h = 24_200.0, c = 24_160.0) else b }).map { it.copy(t = dd.atTime(it.t.toLocalTime())) }
            val opt = ix.map { it.copy(o = 100 + (it.o - 24_000) / 2, h = 100 + (it.h - 24_000) / 2, l = 100 + (it.l - 24_000) / 2, c = 100 + (it.c - 24_000) / 2) }
            Solo.HistDay(dd, dd.plusDays(3), ix, mapOf((24_050 to true) to opt.toTypedArray<Candle?>(), (24_100 to true) to opt.toTypedArray<Candle?>()))
        }
        val rep = Solo.backtest(days.asSequence(), step = 50, lot = 75, r = Solo.Rules(), costs = 60.0)
        assertEquals(7, rep.days)
        assertEquals(1, rep.trades.size, rep.trades.toString())
        val t = rep.trades.single()
        assertEquals(Solo.Exit.TARGET, t.exit); assertTrue(t.net > 0, "$t")
        assertTrue(Solo.say("test", rep).contains("1 trades in 7 days, 100% winners"), Solo.say("test", rep))
    }
}
