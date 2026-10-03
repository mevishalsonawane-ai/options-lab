package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val slow = Solo.Rules(slowMinutes = 20, profitLock = false)
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

class SoloWatchTest {
    private val d = LocalDate.of(2026, 10, 1)
    private fun bar(m: Int, o: Double, h: Double, l: Double, c: Double) = Candle(d.atTime(9, 15).plusMinutes(m.toLong()), o, h, l, c)

    @Test fun solosWatchAndReadAreSaid() {
        // A big green candle 09:45-10:00 (24000 -> 24100), then flat at 24095.
        val day = (0 until 60).map { m ->
            when {
                m < 30 -> bar(m, 24_000.0, 24_005.0, 23_995.0, 24_000.0)
                m < 45 -> { val o = 24_000.0 + (m - 30) * 100.0 / 15; bar(m, o, o + 100.0 / 15, o, o + 100.0 / 15) }
                else -> bar(m, 24_095.0, 24_097.0, 24_093.0, 24_095.0)
            }
        }
        val w = Solo.watching(day, 59, 50.0, "Nifty")
        assertEquals(1, w.size, w.toString())
        assertTrue(w[0].contains("a big green 15-minute candle at 09:45: I buy a call if Nifty comes back to about 24,060 without falling below 24,000 (until 11:00)"), w[0])
        assertTrue(Solo.watching(day, 59, 150.0, "Nifty").isEmpty())
        assertTrue(Solo.watching(day.take(44), 43, 50.0, "Nifty").isEmpty(), "the candle is not complete yet")
        val r = Solo.read(day, 200.0, "Nifty")
        assertTrue(r.startsWith("Nifty is up 95 points from the open, near the day's high."), r)
        assertTrue(r.contains("used 53% of a normal day's range"), r)
    }
}

class SoloLearnTest {
    @Test fun theSetupIsTradedOnlyWhileItWorks() {
        val r = Solo.Rules(recentN = 3)
        assertTrue(Solo.working(listOf(-1.0, -1.0), r), "too few yet")
        assertTrue(Solo.working(listOf(-1.0, 2.0, -1.0, 2.0), r))
        assertFalse(Solo.working(listOf(2.0, -1.0, -1.0, -1.0), r))
        assertTrue(Solo.working(listOf(-1.0, -1.0, -1.0), Solo.Rules()), "no learning: always")
        // The shadow record of a day: one signal that reaches its target is +2 R.
        val d = LocalDate.of(2026, 10, 1)
        fun bar(m: Int, o: Double, h: Double, l: Double, c: Double) = Candle(d.atTime(9, 15).plusMinutes(m.toLong()), o, h, l, c)
        val day = (0 until 375).map { m ->
            when {
                m < 30 -> bar(m, 24_000.0, 24_005.0, 23_995.0, 24_000.0)
                m < 45 -> { val o = 24_000.0 + (m - 30) * 100.0 / 15; bar(m, o, o + 100.0 / 15, o, o + 100.0 / 15) }
                m < 60 -> { val c = 24_100.0 - (m - 44) * 45.0 / 15; bar(m, c + 2, c + 3, c - 1, c) }
                else -> bar(m, 24_150.0, 24_300.0, 24_140.0, 24_250.0)
            }
        }
        assertEquals(listOf(2.0), Solo.shadow(day, 50.0, Solo.Rules(maxPerDay = 1)))
    }
}

class SoloWatchReviewTest {
    @Test fun aPassedChanceIsNotWatchedAndTheDeadlineIs1430() {
        val d = LocalDate.of(2026, 10, 1)
        fun bar(m: Int, o: Double, h: Double, l: Double, c: Double) = Candle(d.atTime(9, 15).plusMinutes(m.toLong()), o, h, l, c)
        // A big green candle 09:45-10:00 (24000 -> 24100), a pullback close at 10:04 (24055), then back up.
        val day = (0 until 80).map { m ->
            when {
                m < 30 -> bar(m, 24_000.0, 24_005.0, 23_995.0, 24_000.0)
                m < 45 -> { val o = 24_000.0 + (m - 30) * 100.0 / 15; bar(m, o, o + 100.0 / 15, o, o + 100.0 / 15) }
                m < 50 -> bar(m, 24_060.0, 24_062.0, 24_050.0, 24_055.0)
                else -> bar(m, 24_090.0, 24_095.0, 24_085.0, 24_090.0)
            }
        }
        assertTrue(Solo.watching(day, 79, 50.0, "Nifty").isEmpty(), "the pullback came at 10:04: its chance has passed")
        // A big candle at 13:45: watched until 14:30, not 15:00.
        val late = (0 until 300).map { m -> if (m in 270 until 285) { val o = 24_000.0 + (m - 270) * 100.0 / 15; bar(m, o, o + 100.0 / 15, o, o + 100.0 / 15) }
            else if (m < 270) bar(m, 24_000.0, 24_005.0, 23_995.0, 24_000.0) else bar(m, 24_095.0, 24_097.0, 24_093.0, 24_095.0) }
        val w = Solo.watching(late, 299, 50.0, "Nifty")
        assertEquals(1, w.size, w.toString()); assertTrue(w[0].contains("at 13:45") && w[0].contains("(until 14:30)"), w[0])
    }
}

class SoloLockTest {
    @Test fun theProfitLockHoldsTheEntryOnceThreeQuartersAreDone() {
        val d = LocalDate.of(2026, 10, 1)
        fun bar(m: Int, h: Double, l: Double) = Candle(d.atTime(9, 15).plusMinutes(m.toLong()), l, h, l, h)
        val s = Solo.Signal(true, 50, 24_050.0, 24_000.0, 24_150.0, 30, "")      // target 100 points away
        val r = Solo.Rules(profitLock = true, ladder = listOf(0.75 to 0.0))
        assertNull(Solo.lock(s, 70.0, r.ladder), "under three quarters: no lock")
        assertEquals(24_050.0, Solo.lock(s, 75.0, r.ladder)!!, 0.01)
        // Best so far +80: a minute that comes back to the entry is closed by the lock, not left to the stop.
        assertEquals(Solo.Exit.LOCK, Solo.exit(s, bar(70, 24_060.0, 24_049.0), 70, best = 80.0, r = r))
        assertNull(Solo.exit(s, bar(70, 24_060.0, 24_049.0), 70, best = 60.0, r = r))
        assertNull(Solo.exit(s, bar(70, 24_060.0, 24_049.0), 70, best = 80.0, r = r.copy(profitLock = false)))
        // The app's full ladder: half way locks a quarter.
        assertEquals(24_075.0, Solo.lock(s, 50.0)!!, 0.01)
    }
}

class SoloStopLossTest {
    @Test fun theOptionsStopLossIsFromTheRules() {
        val s = Solo.Signal(true, 50, 24_050.0, 24_000.0, 24_150.0, 30, "")
        assertEquals(70.0, Solo.premiumStop(s, 100.0, Solo.Rules(premiumStop = 0.30))!!, 1e-9)
        assertEquals(65.0, Solo.premiumStop(s, 100.0, Solo.Rules(stopDelta = 0.7))!!, 1e-9)
        assertNull(Solo.premiumStop(s, 100.0, Solo.Rules()))
    }
}

class SoloReviewTest {
    @Test fun aClosedTradeIsExplained() {
        val d = LocalDate.of(2026, 10, 1)
        fun bar(m: Int, h: Double, l: Double, c: Double) = Candle(d.atTime(9, 15).plusMinutes(m.toLong()), c, h, l, c)
        val s = Solo.Signal(true, 50, 24_050.0, 24_000.0, 24_150.0, 30, "")
        // Up 10 points at best, then through the low at minute 62.
        val day = (0 until 70).map { m -> if (m in 50..55) bar(m, 24_060.0, 24_045.0, 24_055.0) else bar(m, 24_050.0, 23_990.0, 23_995.0) }
        val r = Solo.review(s, day, 56, Solo.Exit.STOP, 100.0, 70.0, "Nifty")
        assertTrue(r.startsWith("Nifty went back through the candle's low 6 minutes after entry; at best it went 10 points our way (10% of the way to the target): the pullback was not over"), r)
        val up = (0 until 70).map { m -> bar(m, 24_080.0, 24_040.0, 24_070.0) }
        val t = Solo.review(s, up, 69, Solo.Exit.TIME, 100.0, 90.0, "Nifty")
        assertTrue(t.contains("By 15:10 Nifty had moved +20 points our way") && t.contains("the option lost 10%: time decay"), t)
        assertTrue(Solo.review(s, up, 60, Solo.Exit.TARGET, 100.0, 150.0, "Nifty").startsWith("Nifty reached the target in 10 minutes."))
    }
}

class SoloFormTest {
    @Test fun recentFormIsSaid() {
        val r = Solo.Rules(recentN = 4)
        assertEquals("Nifty: only 2 of the 4 signals it learns from studied yet - trading", Solo.form(listOf(2.0, -1.0), r, "Nifty"))
        assertEquals("Nifty: its last 4 signals averaged +0.25 R (1 reached the target) - trading", Solo.form(listOf(2.0, -1.0, 1.0, -1.0), r, "Nifty"))
        assertTrue(Solo.form(listOf(-1.0, -1.0, 2.0, -1.0), r, "BankNifty").endsWith("standing aside"))
    }
}

class SoloDayTest {
    @Test fun solosDayIsToldInTheWrapUp() {
        assertNull(Solo.daySay(emptyList(), on = false, paused = false))
        assertTrue(Solo.daySay(emptyList(), on = true, paused = false)!!.contains("found no setup"))
        assertEquals("Solo stayed paused today.", Solo.daySay(emptyList(), on = true, paused = true))
        assertNull(Solo.daySay(emptyList(), on = false, paused = false))
        assertEquals("Solo today on paper: 1 trade - +Rs 1,250 (target reached).", Solo.daySay(listOf(1250.0 to "target reached"), on = true, paused = false))
        assertEquals("Solo today on paper: 2 trades - -Rs 900 (stop: Nifty through 24,000); +Rs 400 (15:10); net -Rs 500.",
            Solo.daySay(listOf(-900.0 to "stop: Nifty through 24,000", 400.0 to "15:10"), on = true, paused = false))
    }
}

class SoloStoryTest {
    private val d = LocalDate.of(2026, 10, 1)
    private fun bar(m: Int, o: Double, h: Double, l: Double, c: Double) = Candle(d.atTime(9, 15).plusMinutes(m.toLong()), o, h, l, c)

    /** Flat, a big green candle 09:45-10:00 (24000 -> 24100), then [after] for each later minute. */
    private fun day(n: Int, after: (Int) -> Candle) = (0 until n).map { m ->
        when {
            m < 30 -> bar(m, 24_000.0, 24_005.0, 23_995.0, 24_000.0)
            m < 45 -> { val o = 24_000.0 + (m - 30) * 100.0 / 15; bar(m, o, o + 100.0 / 15, o, o + 100.0 / 15) }
            else -> after(m)
        }
    }

    @Test fun whyNothingIsSetUpIsSaid() {
        // No big candle: how far the biggest was.
        val flat = (0 until 60).map { bar(it, 24_000.0, 24_005.0, 23_995.0, 24_000.0) }
        assertEquals("Nifty: no 15-minute candle has been big enough yet (the biggest body was 0 points; big is 50)", Solo.story(flat, 59, 50.0, "Nifty"))
        // Still inside its hour, no pullback yet: nothing to explain (the watch says it).
        val waiting = day(70) { bar(it, 24_095.0, 24_097.0, 24_093.0, 24_095.0) }
        assertNull(Solo.story(waiting, 69, 50.0, "Nifty"))
        // Its hour passed with no pullback.
        val never = day(110) { bar(it, 24_095.0, 24_097.0, 24_093.0, 24_095.0) }
        assertEquals("Nifty: the big green candle at 09:45 never pulled back 40% within its hour", Solo.story(never, 109, 50.0, "Nifty"))
        // The low broke first (a sharp drop at 10:10, minute 55).
        val broke = day(110) { m -> if (m == 55) bar(m, 24_095.0, 24_095.0, 23_990.0, 24_080.0) else bar(m, 24_095.0, 24_097.0, 24_093.0, 24_095.0) }
        assertEquals("Nifty: the big green candle at 09:45 broke its low at 10:10 before any pullback", Solo.story(broke, 109, 50.0, "Nifty"))
        // The pullback came on the 10:05 close (minute 49), the same minute signal() fires on.
        val pulled = day(110) { bar(it, 24_055.0, 24_057.0, 24_053.0, 24_055.0) }
        val sig = (0 until 110).firstNotNullOfOrNull { Solo.signal(pulled, it, 50.0) }
        assertNotNull(sig)
        assertEquals(50, sig.entryMinute)
        assertEquals("Nifty: the big green candle at 09:45 pulled back at 10:05 - that was the setup", Solo.story(pulled, 109, 50.0, "Nifty"))
    }
}
