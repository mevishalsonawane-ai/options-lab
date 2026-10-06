package com.optionslab.ira

import com.optionslab.engine.orb.LiquidityShadow
import com.optionslab.engine.orb.ShadowRules
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForwardCheckTest {
    private val day0 = LocalDate.of(2026, 10, 6)
    private fun trades(vararg nets: Double) = nets.mapIndexed { i, x -> ForwardCheck.Trade(day0.plusDays(i.toLong()), x) }
    private fun same(n: Int, x: Double) = List(n) { ForwardCheck.Trade(day0.plusDays(it.toLong()), x) }
    private val liq = ForwardCheck.LIQUIDITY

    @Test fun bandIsMeanPlusMinusTwoSdOverRootN() {
        val b = ForwardCheck.band(liq, 25)!!
        assertEquals(2 * 2_064.33 / 5, b.half, 1e-9)
        assertEquals(227.66 - b.half, b.low, 1e-9)
        assertEquals(227.66 + b.half, b.high, 1e-9)
        assertNull(ForwardCheck.band(liq, 0))
        // Narrows as 1/√n.
        assertEquals(ForwardCheck.band(liq, 100)!!.half * 2, ForwardCheck.band(liq, 25)!!.half, 1e-9)
    }

    @Test fun tooFewTradesUnderTwenty() {
        val r = ForwardCheck.check(liq, same(19, 200.0))
        assertEquals(ForwardCheck.Verdict.TOO_FEW, r.verdict)
        assertEquals("too few trades (<20)", ForwardCheck.words(r))
        assertFalse(r.alarm)
        assertTrue(ForwardCheck.plain(r).contains("Too few to judge"), ForwardCheck.plain(r))
    }

    @Test fun inLineInsideTheBand() {
        // 20 trades averaging ₹100: the band at 20 is 227.66 ± 923.2.
        val r = ForwardCheck.check(liq, List(10) { ForwardCheck.Trade(day0.plusDays(it.toLong()), 1_100.0) } + List(10) { ForwardCheck.Trade(day0.plusDays(10L + it), -900.0) })
        assertEquals(20, r.trades)
        assertEquals(100.0, r.perTrade!!, 1e-9)
        assertEquals(0.5, r.winRate!!, 1e-9)
        assertEquals(ForwardCheck.Verdict.IN_LINE, r.verdict)
        assertEquals("in line", ForwardCheck.words(r))
        assertEquals("Live vs backtest: in line (₹100/trade vs expected ₹228 ± ₹923).", ForwardCheck.line(r))
        assertTrue(ForwardCheck.plain(r).startsWith("20 forward trades, ₹100 a trade per lot. Within what the backtest allows"), ForwardCheck.plain(r))
        // The losing run of ten after the winners: the drawdown.
        assertEquals(-9_000.0, r.drawdown, 1e-9)
        assertEquals(21, r.cumulative.size)
        assertEquals(0.0, r.cumulative.first()); assertEquals(2_000.0, r.cumulative.last(), 1e-9)
    }

    @Test fun belowAndAboveTheBand() {
        // Mean -₹800 over 400 trades with no long run (alternating): under 227.66 - 206.4, and the CUSUM stays quiet.
        val below = ForwardCheck.check(liq, (0 until 400).map { ForwardCheck.Trade(day0, if (it % 2 == 0) 1_300.0 else -2_900.0) })
        assertEquals(ForwardCheck.Verdict.BELOW, below.verdict)
        assertEquals("below expectation (400 trades)", ForwardCheck.words(below))
        assertTrue(ForwardCheck.plain(below).contains("worse than luck") || ForwardCheck.plain(below).contains("sustained"), ForwardCheck.plain(below))
        val noAlarm = ForwardCheck.check(liq, (0 until 100).map { ForwardCheck.Trade(day0, if (it % 2 == 0) 1_900.0 else -2_500.0) })
        assertFalse(noAlarm.alarm)
        assertEquals(ForwardCheck.Verdict.BELOW, noAlarm.verdict)
        assertTrue(ForwardCheck.plain(noAlarm).contains("worse than luck"), ForwardCheck.plain(noAlarm))
        val above = ForwardCheck.check(liq, same(30, 1_200.0))
        assertEquals(ForwardCheck.Verdict.ABOVE, above.verdict)
        assertEquals("above expectation", ForwardCheck.words(above))
        assertTrue(ForwardCheck.plain(above).contains("better than researched"))
    }

    @Test fun cusumFlagsASustainedShortfallEarly() {
        // Each trade a full spread below the research: z = -1, so S grows 0.5 a trade and alarms at the 10th (S = 5).
        val x = liq.mean - liq.sd
        val path = ForwardCheck.cusum(liq, List(12) { x })
        assertEquals(0.5, path[0], 1e-9)
        assertEquals(5.0, path[9], 1e-9)
        val r = ForwardCheck.check(liq, same(12, x))
        assertEquals(10, r.alarmAt)
        assertTrue(r.alarm)
        // Before 20 trades: the early flag overrides "too few".
        assertEquals(ForwardCheck.Verdict.BELOW, r.verdict)
        assertEquals("below expectation (12 trades)", ForwardCheck.words(r))
        assertTrue(ForwardCheck.line(r).endsWith("; a sustained run below the backtest since trade 10)."), ForwardCheck.line(r))
        assertTrue(ForwardCheck.plain(r).contains("since trade 10"))
        // Nine is not enough; a big winner in between takes it most of the way back.
        assertFalse(ForwardCheck.check(liq, same(9, x)).alarm)
        val reset = ForwardCheck.check(liq, trades(*(List(8) { x } + 10_000.0 + List(8) { x }).toDoubleArray()))
        assertFalse(reset.alarm)
        assertEquals(ForwardCheck.Verdict.TOO_FEW, reset.verdict)
        // One outsized loss counts as three spreads at most.
        assertEquals(2.5, ForwardCheck.cusum(liq, listOf(-1e7)).single(), 1e-9)
        // Trades at the research mean never move it.
        assertTrue(ForwardCheck.cusum(liq, List(50) { liq.mean }).all { it == 0.0 })
    }

    @Test fun cusumWithNoSpreadDoesNotMove() {
        val flat = liq.copy(sd = 0.0)
        assertTrue(ForwardCheck.cusum(flat, listOf(-1_000.0, -1_000.0)).all { it == 0.0 })
    }

    @Test fun noTradesYet() {
        val r = ForwardCheck.check(liq, emptyList())
        assertEquals(0, r.trades)
        assertNull(r.perTrade); assertNull(r.winRate); assertNull(r.band)
        assertEquals(ForwardCheck.Verdict.TOO_FEW, r.verdict)
        assertEquals(listOf(0.0), r.cumulative)
        assertEquals("Live vs backtest: no forward trades yet (expected ₹228/trade).", ForwardCheck.line(r))
        assertTrue(ForwardCheck.plain(r).startsWith("No forward trades yet. The backtest made ₹228 a trade per lot over 916 trades (win 41%"))
    }

    @Test fun tradesBeforeTheRulesStartedAreLeftOut() {
        val r = ForwardCheck.check(liq, listOf(ForwardCheck.Trade(day0.minusDays(1), -50_000.0), ForwardCheck.Trade(day0, 300.0)))
        assertEquals(1, r.trades)
        assertEquals(300.0, r.net)
        // No start day (Solo): every trade.
        assertEquals(2, ForwardCheck.check(ForwardCheck.SOLO, listOf(ForwardCheck.Trade(day0.minusDays(400), 1.0), ForwardCheck.Trade(day0, 2.0))).trades)
        // Taken by day whatever the order given.
        assertEquals(listOf(0.0, -100.0, 0.0), ForwardCheck.check(liq, listOf(ForwardCheck.Trade(day0.plusDays(1), 100.0), ForwardCheck.Trade(day0, -100.0))).cumulative)
    }

    @Test fun heroIsALotteryJudgedByDrawdown() {
        val hero = ForwardCheck.HERO
        assertTrue(hero.lottery)
        // 25 small losers: the mean is far under the band, but it is judged by drawdown, still within the research's.
        val small = ForwardCheck.check(hero, same(25, -2_000.0))
        assertEquals(ForwardCheck.Verdict.IN_LINE, small.verdict)
        assertFalse(small.alarm)
        assertEquals("Live vs backtest: in line (drawdown −₹50,000 vs the backtest's worst −₹53,035; a lottery: judged by drawdown, not mean).",
            ForwardCheck.line(small))
        assertTrue(ForwardCheck.plain(small).contains("still within the backtest's worst"))
        assertTrue(ForwardCheck.plain(small).contains("a trade (₹5,000 ticket)"))
        val few = ForwardCheck.check(hero, same(5, -2_000.0))
        assertEquals(ForwardCheck.Verdict.TOO_FEW, few.verdict)
        assertTrue(ForwardCheck.plain(few).contains("too few trades to say more"))
        // Past the research's worst drawdown, at any count: below.
        val deep = ForwardCheck.check(hero, same(4, -14_000.0))
        assertEquals(ForwardCheck.Verdict.BELOW, deep.verdict)
        assertTrue(ForwardCheck.plain(deep).contains("already worse than the backtest's worst"))
        assertEquals("Live vs backtest: no forward trades yet (expected ₹2,578/trade per ₹5,000 ticket).", ForwardCheck.line(ForwardCheck.check(hero, emptyList())))
    }

    @Test fun pinnedNumbersHoldTogether() {
        val all = listOf(ForwardCheck.LIQUIDITY, ForwardCheck.SOLO, ForwardCheck.HERO) + ForwardCheck.SHADOWS.values
        all.forEach { e ->
            assertTrue(abs(e.net / e.trades - e.mean) < 0.01, "${e.key}: net / trades = mean")
            assertTrue(e.sd > 0 && e.maxDrawdown < 0 && e.winRate in 0.0..1.0, e.key)
            assertTrue(e.source.isNotBlank())
        }
        assertEquals(916, ForwardCheck.LIQUIDITY.trades); assertEquals(228.0, Math.round(ForwardCheck.LIQUIDITY.mean).toDouble())
        assertEquals(196, ForwardCheck.SOLO.trades); assertEquals(160.0, Math.round(ForwardCheck.SOLO.mean).toDouble())
        assertEquals(49, ForwardCheck.HERO.trades)
        // Liquidity counts from the day its new rules went on (the shadow's own day).
        assertEquals(LiquidityShadow.SINCE, ForwardCheck.LIQUIDITY.since)
        // Every shadow expectation names a real shadow variant.
        val ids = ShadowRules.ALL.map { it.id }.toSet()
        assertTrue(ids.containsAll(ForwardCheck.SHADOWS.keys), "${ForwardCheck.SHADOWS.keys - ids}")
    }

    @Test fun theBestOfSeveralIsJudgedAgainstItsOwnResearch() {
        val v43 = ForwardCheck.SHADOWS.getValue("orb_v43")      // research −₹484 a trade, sd ₹1,811
        val p10 = ForwardCheck.SHADOWS.getValue("orb_p10")      // research −₹100 a trade, sd ₹533
        // −₹300 is better than V43's research; −₹200 is worse than OP10's: V43 holds up, though its raw net is lower.
        val a = ForwardCheck.check(v43, trades(-300.0))
        val b = ForwardCheck.check(p10, trades(-200.0))
        assertEquals(0, ForwardCheck.bestIndex(listOf(a, b)))
        assertEquals((-300.0 - v43.mean) / v43.sd, ForwardCheck.standing(a).second, 1e-9)
        // The verdict comes first: a strategy below expectation never beats one in line, however its mean compares.
        val inLine = ForwardCheck.check(liq, List(10) { ForwardCheck.Trade(day0.plusDays(it.toLong()), 1_100.0) } +
            List(10) { ForwardCheck.Trade(day0.plusDays(10L + it), -900.0) })
        val below = ForwardCheck.check(p10, same(25, -400.0))
        assertEquals(ForwardCheck.Verdict.BELOW, below.verdict)
        assertEquals(1, ForwardCheck.bestIndex(listOf(below, inLine)))
        // No trades: 0; a tie keeps the first; none: -1.
        assertEquals(1 to 0.0, ForwardCheck.standing(ForwardCheck.check(p10, emptyList())))
        assertEquals(0, ForwardCheck.bestIndex(listOf(ForwardCheck.check(p10, emptyList()), ForwardCheck.check(v43, emptyList()))))
        assertEquals(-1, ForwardCheck.bestIndex(emptyList()))
    }

    @Test fun expectedPathWidensAsRootN() {
        val p = ForwardCheck.expectedPath(liq, 4)
        assertEquals(5, p.size)
        assertEquals(Triple(0.0, 0.0, 0.0), p[0])
        assertEquals(4 * liq.mean, p[4].second, 1e-9)
        assertEquals(2 * liq.sd * sqrt(4.0), p[4].third - p[4].second, 1e-9)
    }

    @Test fun drawdownCountsFromZero() {
        assertEquals(0.0, ForwardCheck.drawdown(listOf(100.0, 50.0)))
        assertEquals(-300.0, ForwardCheck.drawdown(listOf(-300.0, 100.0)))
        assertEquals(-250.0, ForwardCheck.drawdown(listOf(200.0, -100.0, -150.0, 400.0)))
    }

    @Test fun rupees() {
        assertEquals("−₹1,235", ForwardCheck.rs(-1_234.6))
        assertEquals("₹0", ForwardCheck.rs(0.2))
    }
}
