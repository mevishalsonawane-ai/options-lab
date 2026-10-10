package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The losing-trades study's shadows ([ShadowStudy]) on synthetic bars: each variant's entry filters, the pullback LIMIT, the
 * candle-by-candle exits, the excursion and the loss groups. The day: a BANKNIFTY opening range 51,800 - 52,000 (09:15 .. 10:00
 * bars), the 09:20 bar closing at 51,940 (ATM 51,900).
 */
class ShadowStudyTest {
    private val day = LocalDate.of(2026, 10, 7)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private fun bar(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double) = Bar(at(h, m), o, hi, lo, c)
    private fun flat(h: Int, m: Int, c: Double) = bar(h, m, c, c, c, c)

    private val range: List<Bar> = (0..9).map { i ->
        val m = 15 + i * 5
        when (i) {
            0 -> bar(9, 15, 51_900.0, 52_000.0, 51_850.0, 51_900.0)
            1 -> bar(9, 20, 51_900.0, 51_950.0, 51_800.0, 51_940.0)
            else -> bar(9 + m / 60, m % 60, 51_900.0, 51_950.0, 51_850.0, 51_900.0)
        }
    }

    /** Index minutes from [from] (inclusive) to [until] (exclusive): alternating +/-5 bp ([lively]) or flat. */
    private fun ones(until: LocalDateTime, lively: Boolean = true, from: LocalDateTime = at(9, 15)): List<Bar> {
        val n = java.time.Duration.between(from, until).toMinutes().toInt()
        return (0 until n).map { i -> val c = if (lively && i % 2 == 1) 52_000.0 * 1.0005 else 52_000.0; Bar(from.plusMinutes(i.toLong()), c, c, c, c) }
    }

    private val p10 = ShadowStudy.ruleOf("orb_p10")!!
    private val fresh = ShadowStudy.ruleOf("orbf_p10")!!
    private val fade = ShadowStudy.ruleOf("fade_p10")!!
    private val h1 = ShadowStudy.ruleOf("sweep_h1")!!
    private val s14 = ShadowStudy.ruleOf("sweep_14")!!

    @Test fun theRulesArePinnedAsTheStudyRanThem() {
        assertEquals(ShadowStudy.Rule(ShadowStudy.Signal.ORB, true, 2.73, 1, 40.0, 40.0), p10)
        assertEquals(ShadowStudy.Signal.ORB_FRESH, fresh.signal); assertEquals(p10.copy(signal = ShadowStudy.Signal.ORB_FRESH), fresh)
        assertEquals(p10.copy(signal = ShadowStudy.Signal.FADE), fade)
        assertEquals(ShadowStudy.Rule(ShadowStudy.Signal.SWEEP, false, null, 1, Double.POSITIVE_INFINITY, null), h1)
        assertEquals(ShadowStudy.Rule(ShadowStudy.Signal.SWEEP, false, null, 2, 80.0, 80.0, java.time.LocalTime.of(13, 55)), s14)
        assertNull(ShadowStudy.ruleOf("orb_v43"), "the research's own shadows are not the study's")
        listOf(ShadowRules.ORB_P10, ShadowRules.ORBF_P10, ShadowRules.SWEEP_H1, ShadowRules.SWEEP_14, ShadowRules.FADE_P10)
            .forEach { assertTrue(ShadowStudy.ruleOf(it.id) != null, it.id) }
        assertEquals(10.0, ShadowStudy.PULL_POINTS); assertEquals(15L, ShadowStudy.PULL_WAIT)
    }

    @Test fun rv30IsTheStdevOfTheLastHalfHoursOneMinuteReturnsInBasisPoints() {
        val lively = ones(at(10, 10))
        val r = ln(1.0005) * 1e4
        // 30 returns, 15 up and 15 down: sample stdev = r * sqrt(30 / 29).
        assertEquals(r * kotlin.math.sqrt(30.0 / 29.0), ShadowStudy.rv30(lively, at(10, 10))!!, 1e-9)
        assertEquals(0.0, ShadowStudy.rv30(ones(at(10, 10), lively = false), at(10, 10))!!, 1e-12)
        assertNull(ShadowStudy.rv30(ones(at(10, 10), from = at(10, 0)), at(10, 10)), "9 returns are too few")
        assertEquals(10, ShadowStudy.RV_MIN_RETURNS)
        // Only what had closed before the decision minute, and today's only.
        val later = lively + Bar(at(10, 10), 1.0, 1.0, 1.0, 1.0)
        assertEquals(ShadowStudy.rv30(lively, at(10, 10)), ShadowStudy.rv30(later, at(10, 10)))
        val yesterday = lively.map { it.copy(start = it.start.minusDays(1)) }
        assertNull(ShadowStudy.rv30(yesterday, at(10, 10)))
    }

    @Test fun orbP10TakesOrbsBreakOnceWhenVolatilityIsNotTheQuietest() {
        val up = range + flat(10, 5, 52_050.0)
        assertEquals("waiting_for_opening_range", ShadowStudy.decide(p10, range.dropLast(1), ones(at(10, 10)), 0, null).why)
        val e = ShadowStudy.decide(p10, up, ones(at(10, 10)), 0, null)
        assertEquals(ShadowRules.Entry(1, 51_900, at(10, 5)), e.entry)
        assertEquals("quiet_volatility", ShadowStudy.decide(p10, up, ones(at(10, 10), lively = false), 0, null).why)
        assertEquals("no_volatility_reading", ShadowStudy.decide(p10, up, emptyList(), 0, null).why)
        assertEquals("done_for_today", ShadowStudy.decide(p10, up, ones(at(10, 10)), 1, null).why, "first entry only")
        // ORB takes a break already under way; ORB Fresh does not.
        val still = up + flat(10, 10, 52_060.0)
        assertEquals(1, ShadowStudy.decide(p10, still, ones(at(10, 15)), 0, null).entry!!.side)
        assertEquals("not_a_fresh_break", ShadowStudy.decide(fresh, still, ones(at(10, 15)), 0, null).why)
        assertEquals(-1, ShadowStudy.decide(fresh, range + flat(10, 5, 51_700.0), ones(at(10, 10)), 0, null).entry!!.side)
        assertEquals("inside_range", ShadowStudy.decide(p10, range + flat(10, 5, 51_900.0), ones(at(10, 10)), 0, null).why)
        assertEquals("cooling_down_after_exit", ShadowStudy.decide(p10, still, ones(at(10, 15)), 0, at(10, 12)).why)
    }

    @Test fun fadeP10IsRangeFadeFromTenThirtyWithTheSameFilters() {
        val edge = range + bar(10, 30, 51_960.0, 51_990.0, 51_940.0, 51_950.0)
        assertEquals("no_decision_bar", ShadowStudy.decide(fade, range + bar(10, 25, 51_960.0, 51_990.0, 51_940.0, 51_950.0), ones(at(10, 30)), 0, null).why)
        assertEquals(ShadowRules.Entry(-1, 51_900, at(10, 30)), ShadowStudy.decide(fade, edge, ones(at(10, 35)), 0, null).entry)
        assertEquals("quiet_volatility", ShadowStudy.decide(fade, edge, ones(at(10, 35), lively = false), 0, null).why)
        assertEquals("done_for_today", ShadowStudy.decide(fade, edge, ones(at(10, 35)), 1, null).why)
    }

    @Test fun sweepH1TakesTheFirstSweepOnlyWithNoVolatilityFloor() {
        val high = range + bar(10, 5, 51_980.0, 52_050.0, 51_940.0, 51_950.0)
        assertEquals(ShadowRules.Entry(-1, 51_900, at(10, 5)), ShadowStudy.decide(h1, high, emptyList(), 0, null).entry)
        assertEquals("done_for_today", ShadowStudy.decide(h1, high, emptyList(), 1, null).why)
        assertEquals("no_sweep", ShadowStudy.decide(h1, range + flat(10, 5, 51_900.0), emptyList(), 0, null).why)
    }

    @Test fun sweep14DecidesOnlyFromFourteenHundred() {
        fun sweepAt(h: Int, m: Int) = range + bar(h, m, 51_980.0, 52_050.0, 51_940.0, 51_950.0)
        assertEquals("before_14", ShadowStudy.decide(s14, sweepAt(13, 50), emptyList(), 0, null).why)
        assertEquals(ShadowRules.Entry(-1, 51_900, at(13, 55)), ShadowStudy.decide(s14, sweepAt(13, 55), emptyList(), 0, null).entry)
        assertEquals(-1, ShadowStudy.decide(s14, sweepAt(14, 25), emptyList(), 1, null).entry!!.side, "two a day, as the arm")
        assertEquals("day_limit_reached", ShadowStudy.decide(s14, sweepAt(14, 25), emptyList(), 2, null).why)
        assertEquals("no_decision_bar", ShadowStudy.decide(s14, sweepAt(14, 30), emptyList(), 0, null).why)
    }

    // ---- pricing the entry -----------------------------------------------------------------------------------------

    private fun opt(h: Int, m: Int, o: Double, hi: Double = o, lo: Double = o, c: Double = o) = bar(h, m, o, hi, lo, c)

    @Test fun aMarketEntryIsTheFirstCandleFromTheDecisionMinute() {
        val from = at(10, 10)
        val c = listOf(opt(10, 9, 150.0), opt(10, 10, 200.0), opt(10, 11, 210.0))
        assertEquals("waiting", ShadowStudy.enter(h1, c, from, from.plusSeconds(30)).state, "10:10 has not closed")
        val p = ShadowStudy.enter(h1, c, from, at(10, 11))
        assertEquals(ShadowStudy.Priced("filled", "market", at(10, 10), ShadowRules.entryFill(200.0)), p)
        assertEquals(200.10, p.price!!, 1e-9)
        assertEquals("refused", ShadowStudy.enter(h1, listOf(opt(10, 10, 30.0)), from, at(10, 12)).state)
        assertEquals("waiting", ShadowStudy.enter(h1, emptyList(), from, at(10, 26)).state)
        assertEquals(ShadowStudy.Priced("unfilled", "no_option_minutes"), ShadowStudy.enter(h1, emptyList(), from, at(10, 27)))
        assertEquals("no_option_minutes", ShadowStudy.enter(h1, listOf(opt(15, 10, 200.0)), at(15, 5), at(15, 12)).why, "nothing before 15:10")
        // Another day's candle never prices today's entry.
        assertEquals("waiting", ShadowStudy.enter(h1, listOf(opt(10, 10, 200.0).copy(start = at(10, 10).minusDays(1))), from, at(10, 12)).state)
    }

    @Test fun thePullbackLimitWaitsFifteenMinutesForALowAtOrUnderIt() {
        assertEquals(190.0, ShadowStudy.pullLimit(200.0), 1e-9)
        assertEquals(190.05, ShadowStudy.pullLimit(200.03), 1e-9)
        val from = at(10, 10)
        val c = listOf(opt(10, 10, 200.0, lo = 195.0), opt(10, 11, 196.0, lo = 192.0), opt(10, 12, 191.0, lo = 189.5))
        assertEquals("waiting", ShadowStudy.enter(p10, c.take(2), from, at(10, 12)).state)
        assertEquals(ShadowStudy.Priced("filled", "pullback", at(10, 12), 190.0), ShadowStudy.enter(p10, c, from, at(10, 13)))
        // Opened under the limit: filled at that open.
        assertEquals(188.0, ShadowStudy.enter(p10, c.take(2) + opt(10, 12, 188.0, lo = 187.0), from, at(10, 13)).price!!, 1e-9)
        // The whole window above it: no trade once its last candle (10:24) is in, or two minutes after it ends.
        val above = (10..24).map { opt(10, it, 200.0, lo = 191.0) }
        assertEquals(ShadowStudy.Priced("unfilled", "pullback_unfilled"), ShadowStudy.enter(p10, above, from, at(10, 25)))
        assertEquals("waiting", ShadowStudy.enter(p10, above.dropLast(1), from, at(10, 26)).state)
        assertEquals("unfilled", ShadowStudy.enter(p10, above.dropLast(1), from, at(10, 27)).state)
        // A low after the window does not fill it.
        assertEquals("unfilled", ShadowStudy.enter(p10, above + opt(10, 25, 180.0, lo = 170.0), from, at(10, 26)).state)
        // Near the close the window ends at 15:10.
        assertEquals("unfilled", ShadowStudy.enter(p10, listOf(opt(15, 5, 200.0, lo = 195.0), opt(15, 9, 200.0, lo = 195.0)), at(15, 5), at(15, 10)).state)
        assertEquals("refused", ShadowStudy.enter(p10, listOf(opt(10, 10, 39.0)), from, at(10, 11)).state)
    }

    // ---- the walk -----------------------------------------------------------------------------------------------

    @Test fun theWalkSellsOnTheStopAtItsTriggerOrTheOpenBelow() {
        val w = ShadowStudy.Walk(190.0, at(10, 12))
        val s = ShadowStudy.walk(p10, w, listOf(opt(10, 12, 190.0, hi = 195.0, lo = 189.0), opt(10, 13, 170.0, hi = 171.0, lo = 149.0)), at(10, 14))
        assertEquals("stop", s.why); assertEquals(at(10, 13), s.exitAt)
        assertEquals(ShadowRules.exitFill("stop", 190.0, 170.0), s.exit!!, 1e-9)
        assertEquals(149.85, s.exit!!, 1e-9, "the trigger 150, -10 bps")
        assertEquals(5.0, s.walk.mfe!!, 1e-9); assertEquals(41.0, s.walk.mae!!, 1e-9)
        val gap = ShadowStudy.walk(p10, w, listOf(opt(10, 12, 140.0, lo = 130.0)), at(10, 13))
        assertEquals(ShadowRules.exitFill("stop", 190.0, 140.0), gap.exit!!, 1e-9)
    }

    @Test fun theLadderLocksFromTheNextCandleAndTheTargetSells() {
        val w = ShadowStudy.Walk(190.0, at(10, 12))
        val c = listOf(opt(10, 12, 190.0, hi = 201.0, lo = 189.0), opt(10, 13, 195.0, hi = 196.0, lo = 189.5))
        val lock = ShadowStudy.walk(p10, w, c, at(10, 14))
        assertEquals("profit_lock", lock.why)
        assertEquals(ShadowRules.exitFill("profit_lock", 190.0, 190.0), lock.exit!!, 1e-9)
        // Same candles with no ladder (SH1): held.
        assertNull(ShadowStudy.walk(h1, w, c, at(10, 14)).why)
        val tgt = ShadowStudy.walk(p10, w, listOf(opt(10, 12, 190.0, hi = 231.0, lo = 190.0)), at(10, 13))
        assertEquals("target", tgt.why); assertEquals(ShadowRules.exitFill("target", 190.0, 230.0), tgt.exit!!, 1e-9)
        val above = ShadowStudy.walk(p10, w.copy(last = at(10, 12)), listOf(opt(10, 13, 240.0, hi = 241.0, lo = 239.0)), at(10, 14))
        assertEquals(ShadowRules.exitFill("target", 190.0, 240.0), above.exit!!, 1e-9, "opened above the target: its open")
        // SH1 has no target: a big candle is held. S14's target is +80.
        assertNull(ShadowStudy.walk(h1, w, listOf(opt(10, 12, 190.0, hi = 400.0, lo = 190.0)), at(10, 13)).why)
        assertNull(ShadowStudy.walk(s14, w, listOf(opt(10, 12, 190.0, hi = 269.0, lo = 190.0)), at(10, 13)).why)
        assertEquals("target", ShadowStudy.walk(s14, w, listOf(opt(10, 12, 190.0, hi = 270.0, lo = 190.0)), at(10, 13)).why)
    }

    @Test fun theWalkResumesWhereItLeftAndSellsAtTheCloseMinute() {
        val w = ShadowStudy.Walk(190.0, at(15, 7))
        val c = listOf(opt(15, 7, 190.0, hi = 192.0, lo = 188.0), opt(15, 8, 191.0, hi = 199.0, lo = 190.0), opt(15, 9, 195.0), opt(15, 10, 196.0, hi = 300.0))
        val first = ShadowStudy.walk(h1, w, c, at(15, 8))
        assertNull(first.why); assertEquals(at(15, 7), first.walk.last); assertEquals(192.0, first.walk.peak)
        val again = ShadowStudy.walk(h1, first.walk, c, at(15, 8).plusSeconds(40))
        assertEquals(first, again, "nothing new closed")
        val end = ShadowStudy.walk(h1, first.walk, c, at(15, 11))
        assertEquals("session_end", end.why); assertEquals(at(15, 10), end.exitAt)
        assertEquals(ShadowRules.exitFill("session_end", 190.0, 196.0), end.exit!!, 1e-9)
        assertEquals(9.0, end.walk.mfe!!, 1e-9, "the 15:10 candle is not part of the trade")
        assertEquals(2.0, end.walk.mae!!, 1e-9)
        // Candles from before the entry never count.
        assertNull(ShadowStudy.walk(h1, ShadowStudy.Walk(190.0, at(15, 9)), listOf(opt(15, 8, 1.0)), at(15, 10)).walk.mfe)
    }

    // ---- why a shadow loses -------------------------------------------------------------------------------------

    @Test fun theExcursionReadsTheCandlesWhileHeldAndToTheClose() {
        val c = listOf(opt(10, 11, 1.0, hi = 500.0, lo = 1.0), opt(10, 12, 200.0, hi = 205.0, lo = 190.0), opt(10, 13, 195.0, hi = 198.0, lo = 180.0),
            opt(10, 14, 190.0, hi = 260.0, lo = 189.0), opt(15, 10, 300.0, hi = 400.0, lo = 300.0))
        val x = ShadowStudy.excursion(200.0, at(10, 12).plusSeconds(20), at(10, 13).plusSeconds(5), c)
        assertEquals(ShadowStudy.Excursion(5.0, 20.0, 60.0), x)
        assertEquals(ShadowStudy.Excursion(null, null, null), ShadowStudy.excursion(200.0, at(11, 0), at(11, 5), c))
    }

    @Test fun theLossGroupsAreTheStudys() {
        val q = 30
        fun net(e: Double, x: Double) = (x - e) * q - ShadowRules.charges(e, x, q)
        assertNull(ShadowStudy.lossGroup(200.0, 260.0, q, ShadowRules.charges(200.0, 260.0, q), "target", 60.0, 60.0), "a winner")
        assertEquals(ShadowStudy.LossGroup.FAILED_LOCK, ShadowStudy.lossGroup(200.0, 200.0, q, ShadowRules.charges(200.0, 200.0, q), "profit_lock", 12.0, 12.0))
        assertEquals(ShadowStudy.LossGroup.CHARGES, ShadowStudy.lossGroup(200.0, 200.5, q, ShadowRules.charges(200.0, 200.5, q), "time_exit", 1.0, 1.0))
        assertTrue(net(200.0, 200.5) < 0)
        // Never green after charges while held (best +1 point), nor later: wrong way. Green later (+30): stopped, then it turned.
        assertEquals(ShadowStudy.LossGroup.WRONG_WAY, ShadowStudy.lossGroup(200.0, 160.0, q, ShadowRules.charges(200.0, 160.0, q), "stop", 1.0, 2.0))
        assertEquals(ShadowStudy.LossGroup.STOP_THEN_TURNED, ShadowStudy.lossGroup(200.0, 160.0, q, ShadowRules.charges(200.0, 160.0, q), "stop", 1.0, 30.0))
        assertEquals(ShadowStudy.LossGroup.WRONG_WAY, ShadowStudy.lossGroup(200.0, 160.0, q, ShadowRules.charges(200.0, 160.0, q), "stop", null, null), "never seen")
        assertEquals(ShadowStudy.LossGroup.WRONG_WAY, ShadowStudy.lossGroup(200.0, 160.0, q, ShadowRules.charges(200.0, 160.0, q), "stop", 1.0, null), "the close unknown")
        // Green while held (+15 after charges), then lost: gave it back.
        assertEquals(ShadowStudy.LossGroup.GAVE_BACK, ShadowStudy.lossGroup(200.0, 160.0, q, ShadowRules.charges(200.0, 160.0, q), "stop", 15.0, 15.0))
    }

    @Test fun theTopReasonIsTheMostCommonGroup() {
        val g = ShadowStudy.LossGroup.entries
        assertNull(ShadowStudy.topReason(emptyList())); assertNull(ShadowStudy.reasonLine(emptyList()))
        val l = listOf(g[4], g[2], g[4], g[2], g[0])
        assertEquals(ShadowStudy.LossGroup.WRONG_WAY to 2, ShadowStudy.topReason(l), "a tie: the study's order")
        assertEquals("mostly green, then gave it back (2 of 3 losers)", ShadowStudy.reasonLine(listOf(g[4], g[4], g[1])))
        assertEquals("mostly charges ate it (1 of 1 loser)", ShadowStudy.reasonLine(listOf(g[1])))
        assertEquals(listOf("failed lock (back to the price paid)", "charges ate it", "wrong way from the start", "stopped, then it turned",
            "green, then gave it back"), g.map { it.words })
    }
}
