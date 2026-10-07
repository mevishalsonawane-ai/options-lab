package com.optionslab.ira

import com.optionslab.engine.orb.SoloMidday
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoloProgressTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 6)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private val noon = at(12, 0)
    private val bn = SoloMidday.Signal("BANKNIFTY", 1, 54_000.0, 54_400.0, 54_420.0, 53_950.0, 500.0, 54_410.0, noon)

    @Test fun noTradesYet() {
        val g = SoloProgress.of(emptyList(), on = true, paused = null)
        assertEquals(0, g.trades)
        assertEquals(0f, g.progress)
        assertEquals("0 of 60 forward-test trades", g.progressLine)
        assertNull(g.winRate)
        assertNull(g.perTrade)
        assertTrue(g.netLine.startsWith("No closed forward-test trade yet"))
        assertEquals(25_000.0, g.room)
        assertEquals(0f, g.toLine)
        assertEquals("No drawdown since its start: ₹25,000 from the −₹25,000 switch-off line.", g.drawdownLine)
        assertNull(g.spark)
        assertEquals(listOf(0.0), g.totals)
        assertFalse(g.switchedOff)
        assertNull(g.offLine)
        assertNull(g.verdictLine)
        assertEquals(SoloDay.research(), g.research)
        assertTrue(g.research.contains("about break-even") && g.research.contains("profit factor 1.02"))
    }

    @Test fun progressNetWinRateAndDrawdown() {
        val nets = listOf(1_000.0, -400.0, -700.0, 1_500.0, -200.0)
        val g = SoloProgress.of(nets, on = true, paused = null)
        assertEquals(5, g.trades)
        assertEquals(5f / 60, g.progress, 1e-6f)
        assertEquals("5 of 60 forward-test trades", g.progressLine)
        assertEquals(1_200.0, g.net, 1e-9)
        assertEquals(1_200.0, g.perLot, 1e-9)
        assertEquals(0.4, g.winRate!!, 1e-9)
        assertEquals("Net so far after charges: +₹1,200 total, +₹1,200 per lot (1 lot a trade) · +₹240 a trade · won 2 of 5 (40%)", g.netLine)
        // Peak 1,400 after trade 4, now 1,200: −200 now, the worst −1,100.
        assertEquals(-200.0, g.drawdown, 1e-9)
        assertEquals(-1_100.0, g.worst, 1e-9)
        assertEquals(24_800.0, g.room, 1e-9)
        assertEquals(200f / 25_000f, g.toLine, 1e-6f)
        assertEquals("Drawdown −₹200 from its best since its start · ₹24,800 from the −₹25,000 switch-off line (worst −₹1,100).", g.drawdownLine)
        assertEquals(listOf(0.0, 1_000.0, 600.0, -100.0, 1_400.0, 1_200.0), g.totals)
    }

    @Test fun pastSixtyAndPassed() {
        val g = SoloProgress.of(List(64) { if (it % 2 == 0) 500.0 else -100.0 }, on = true, paused = null)
        assertEquals(1f, g.progress)
        assertEquals("60 of 60 forward-test trades (64 in all)", g.progressLine)
        assertEquals(SoloMidday.Verdict.PASSED, g.verdict)
        assertNotNull(g.verdictLine)
        assertEquals(1f, SoloProgress.progress(200))
        assertEquals(0f, SoloProgress.progress(-1))
    }

    @Test fun switchedItselfOffOnTheDrawdown() {
        val nets = listOf(2_000.0, -15_000.0, -13_000.0)
        val g = SoloProgress.of(nets, on = false, paused = "Boss, Solo (midday) is −₹28,000 below its best...")
        assertEquals(SoloMidday.Verdict.FAILED_DRAWDOWN, g.verdict)
        assertTrue(g.switchedOff)
        assertEquals(0.0, g.room)
        assertEquals(1f, g.toLine)
        assertEquals("Drawdown −₹28,000 since its start went past the −₹25,000 line it switches itself off beyond.", g.drawdownLine)
        assertEquals("Switched itself off: it went more than ₹25,000 below its best. Switching it back on is Boss's switch - the drawdown then counts from that moment.", g.offLine)
        // Off by Boss (no pause): not "switched itself off".
        assertFalse(SoloProgress.of(nets, on = false, paused = null).switchedOff)
        // Switched off for the net bar; and an old pause with no failed bar.
        val net = SoloProgress.of(List(60) { -10.0 }, on = false, paused = "x")
        assertEquals(SoloMidday.Verdict.FAILED_NET, net.verdict)
        assertTrue(net.offLine!!.contains("after 60 trades it made ₹0 or less a trade"))
        assertTrue(net.verdictLine!!.contains("the net bar failed"))
        assertTrue(SoloProgress.of(listOf(100.0), on = false, paused = "x").offLine!!.contains("the bar set in advance failed"))
    }

    @Test fun drawdownCountsFromTheBaselineAfterASwitchBackOn() {
        val nets = listOf(2_000.0, -15_000.0, -13_000.0, -1_000.0)
        val base = SoloMidday.baseline(nets.take(3))   // back on after trade 3: equity −26,000
        val g = SoloProgress.of(nets, base, on = true, paused = null)
        assertTrue(g.sinceOn)
        assertEquals(-1_000.0, g.drawdown, 1e-9)
        assertEquals(24_000.0, g.room, 1e-9)
        assertEquals(SoloMidday.Verdict.RUNNING, g.verdict)
        assertTrue(g.drawdownLine.contains("since Boss switched it back on"))
        assertEquals(0.0, SoloProgress.drawdownNow(listOf(100.0, 200.0), SoloMidday.Baseline()))
    }

    @Test fun sparklinePoints() {
        assertNull(SoloProgress.spark(listOf(0.0, 100.0)))   // one trade
        assertNull(SoloProgress.spark(listOf(0.0, Double.NaN, 1.0)))
        val s = SoloProgress.spark(listOf(0.0, 1_000.0, -1_000.0))!!
        assertEquals(listOf(SoloProgress.Point(0f, 0.5f), SoloProgress.Point(0.5f, 1f), SoloProgress.Point(1f, 0f)), s.points)
        assertEquals(0.5f, s.zero)
        val flat = SoloProgress.spark(listOf(0.0, 0.0, 0.0))!!
        assertTrue(flat.points.all { it.y == 0.5f })
        val up = SoloProgress.spark(listOf(0.0, 100.0, 300.0))!!
        assertEquals(0f, up.zero)
        assertEquals(1f, up.points.last().y)
        assertEquals("+₹1,200", SoloProgress.signed(1_200.4))
        assertEquals("₹0", SoloProgress.signed(-0.3))
        assertEquals("−₹420", SoloProgress.signed(-420.0))
    }

    @Test fun theCardLeavesOutWhatTheGlanceShows() {
        val card = SoloMidday.card(SoloMidday.record(listOf(100.0)), day)
        val kept = card.filter { !SoloProgress.shownAtAGlance(it) }
        assertEquals(listOf(card[0], card[1], card[3]), kept)
        assertTrue(kept[0].startsWith("Not proven"))
        assertTrue(kept[2].startsWith("The bar (set in advance)"))
    }

    @Test fun todayInOneLine() {
        val f = { now: LocalDateTime, on: Boolean, r: SoloDay.Record?, tr: TodayGlance.SoloTrade? -> SoloProgress.today(now, true, on, r, tr, at(9, 0)) }
        assertEquals("Decides at 12:00 today (orders only until 12:03).", f(at(11, 0), true, null, null))
        assertEquals("Off: it will not decide at 12:00 today.", f(at(11, 0), false, null, null))
        assertEquals("Deciding now (12:00-12:03).", f(at(12, 1), true, null, null))
        assertEquals("Off: no decision today.", f(at(12, 1), false, null, null))
        assertEquals("Off: no decision today.", f(at(13, 0), false, null, null))
        assertEquals("No session today - it decides only on a trading day, once, at 12:00.", SoloProgress.today(at(11, 0), false, true, null, null))
        assertEquals("Decided: traded BANKNIFTY26OCT54000CE - still open.", f(at(13, 0), true, null, TodayGlance.SoloTrade("BANKNIFTY26OCT54000CE", true, null)))
        assertEquals("Decided: traded X - net −₹1,250 after charges.", f(at(15, 0), false, null, TodayGlance.SoloTrade("X", false, -1_250.0)))
        assertEquals("Decided: traded X - closed.", f(at(15, 0), true, null, TodayGlance.SoloTrade("X", false, null)))
        val bought = SoloDay.bought(null, SoloDay.Bought(at(12, 1), "BANKNIFTY", "BN CE", 54_000, 4, 612.5))
        assertEquals("Decided: bought BN CE on paper at 12:01.", f(at(12, 2), true, bought, null))
        // No signal.
        val none = SoloDay.decided(null, noon, listOf(SoloMidday.Decision("NIFTY", null, "move_too_small", 0.3),
            SoloMidday.Decision("BANKNIFTY", null, "mid_range", 0.7, 0.4), SoloMidday.Decision("FINNIFTY", null, "move_too_small", 0.1)))
        assertEquals("Decided: no trade - none of NIFTY, BANKNIFTY and FINNIFTY met both rules (0.5 ATR from the open and a close in the outer quarter).",
            f(at(13, 0), true, none, null))
        // Within the window but already decided: the decision shows.
        assertTrue(f(at(12, 2), true, none, null).startsWith("Decided: no trade - none of"))
        // Set aside on its expiry day.
        val sig = SoloMidday.Decision("BANKNIFTY", bn, "signal", bn.strength, bn.position)
        val aside = SoloDay.passed(SoloDay.decided(null, noon, listOf(sig)), noon, "BANKNIFTY", "expiry_today")
        assertEquals("Decided: no trade - set aside: BANKNIFTY expires today (Solo never trades an index on its expiry day).", f(at(13, 0), true, aside, null))
        assertEquals("Decided: no trade - every index that signalled was set aside.", f(at(13, 0), true, SoloDay.decided(null, noon, listOf(sig)), null))
        assertEquals("Decided: no trade - the 12:00-12:03 window closed before the order could go in.",
            f(at(13, 0), true, SoloDay.late(SoloDay.decided(null, noon, listOf(sig)), at(12, 4)), null))
        assertEquals("No trade today - held back at 12:00: the kill switch is on.", f(at(13, 0), true, SoloDay.held(null, noon, SoloDay.KILL_SWITCH), null))
        assertEquals("No trade today - at 12:00 it was waiting for the 11:59 minute or the daily ATR of NIFTY and FINNIFTY.",
            f(at(13, 0), true, SoloDay.waited(null, noon, listOf("NIFTY", "FINNIFTY")), null))
        assertEquals("No trade today - no 12:00 decision is on record (it decides while the app's market watch runs 12:00-12:03).", f(at(13, 0), true, null, null))
        assertEquals("No trade today - the app started at 12:30, after the 12:00-12:03 window.",
            SoloProgress.today(at(13, 0), true, true, null, null, at(12, 30)))
        // An earlier day's record is not today's.
        assertEquals("No trade today - no 12:00 decision is on record (it decides while the app's market watch runs 12:00-12:03).",
            f(at(13, 0), true, SoloDay.held(null, noon.minusDays(1), SoloDay.KILL_SWITCH), null))
    }
}
