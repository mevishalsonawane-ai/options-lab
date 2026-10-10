package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoloCalibrationTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val lot = 75

    /** A Solo trade's conditions [daysAgo] days back at session minute [minute] (0 = 09:15). */
    private fun c(daysAgo: Long, minute: Int = 120, market: Market = Market.NIFTY, call: Boolean = true, regime: Regime.Kind? = null) =
        SoloCalibration.conditions(today.minusDays(daysAgo), minute, market, call, regime)

    /** [n] closed Solo trades (one a day back from [from]): the first [wins] made [win] rupees, the rest lost [loss]. */
    private fun trades(n: Int, wins: Int, cond: (Long) -> SelfCalibration.Conditions, from: Long = 1, win: Double = 1_500.0, loss: Double = -1_200.0) =
        (0 until n).mapNotNull { i -> SoloCalibration.outcome(cond(from + i), if (i < wins) win else loss, lot) }

    @Test fun conditionsAreSolosOwnAndTimedFromTheOpen() {
        val k = c(0, 30, Market.BANKNIFTY, call = false, regime = Regime.Kind.UP)
        assertEquals(SoloCalibration.KIND, k.kind)
        assertEquals(9, k.at.hour); assertEquals(45, k.at.minute)
        val t = SelfCalibration.tags(k)
        assertEquals(listOf("Solo", "BANKNIFTY", "OPEN", "AGAINST", "PUT"), t.map { it.key })
        assertEquals("my Solo trades", t.first().phrase)
        // A minute out of the session is held to it.
        assertEquals(15, SoloCalibration.conditions(today, 9_999, Market.NIFTY, true).at.hour)
    }

    @Test fun anOutcomeIsRupeesAUnitAfterCosts() {
        val o = SoloCalibration.outcome(c(1), -1_500.0, lot)!!
        assertEquals(-20.0, o.points, 1e-9)
        assertNull(SoloCalibration.outcome(c(1), null, lot))
        assertNull(SoloCalibration.outcome(c(1), Double.NaN, lot))
        assertNull(SoloCalibration.outcome(c(1), 100.0, 0))
    }

    @Test fun noRecordTakesAsBefore() {
        val d = SoloCalibration.decide(emptyList(), c(0))
        assertTrue(d.take)
        assertEquals(SelfCalibration.Action.NORMAL, d.action)
        assertNull(d.text())
        assertEquals(1, SoloCalibration.lots(1, d))
        assertNull(SoloCalibration.say(emptyList(), today))
    }

    @Test fun aClearlyBadConditionIsSatOutAndShadowed() {
        // Afternoon Nifty calls: 1 of 12 worked. Morning ones do fine.
        val o = trades(12, 1, { c(it, minute = 300) }) + trades(12, 9, { c(it, minute = 60) })
        val d = SoloCalibration.decide(o, c(0, minute = 300))
        assertFalse(d.take)
        assertEquals(SelfCalibration.Action.SIT_OUT, d.action)
        assertEquals(0, SoloCalibration.lots(1, d))
        assertTrue(d.text()!!.startsWith("I'm weak on my Solo trades in the afternoon (12 trades, 1 worked"), d.text())
        assertTrue(d.text()!!.contains("no order"))
        // The good condition is left alone.
        assertTrue(SoloCalibration.decide(o, c(0, minute = 60)).take)
    }

    @Test fun aSoftConditionAllowsOneTradeADay() {
        // Losing, but not clearly bad: 5 of 12 worked, losing money.
        val o = trades(12, 5, { c(it, call = false) }, loss = -2_000.0)
        val first = SoloCalibration.decide(o, c(0, call = false))
        assertEquals(SelfCalibration.Action.SHRINK, first.action)
        assertTrue(first.take)
        assertEquals(1, SoloCalibration.lots(1, first))
        assertTrue(first.text()!!.contains("one lot and no more of those today"), first.text())
        // One already taken there today: the next is a shadow.
        val second = SoloCalibration.decide(o, c(0, minute = 200, call = false), today = listOf(c(0, call = false)))
        assertFalse(second.take)
        assertEquals(0, SoloCalibration.lots(1, second))
        assertTrue(second.text()!!.contains("already took one"))
        // Yesterday's trade there does not count against today.
        assertTrue(SoloCalibration.decide(o, c(0, call = false), today = listOf(c(1, call = false))).take)
    }

    @Test fun learningNeverRaisesSize() {
        val great = trades(30, 28, { c(it) })
        val d = SoloCalibration.decide(great, c(0))
        assertTrue(d.take)
        for (planned in 0..5) {
            assertEquals(planned, SoloCalibration.lots(planned, d))
            for (a in SelfCalibration.Action.values()) for (take in listOf(true, false))
                assertTrue(SoloCalibration.lots(planned, SoloCalibration.Decision(take, a, null)) <= planned)
        }
        assertTrue(SoloCalibration.say(great, today)!!.contains("never makes Solo take more"))
    }

    @Test fun solosRecordIsKeptApartFromTheIdeas() {
        // News ideas on Nifty in the afternoon are clearly bad; Solo's own record there is clean.
        val news = (1..12L).map { SelfCalibration.Outcome(SelfCalibration.Conditions(today.minusDays(it).atTime(14, 0), Market.NIFTY, true, "news"), -20.0) }
        assertEquals(SelfCalibration.Action.SIT_OUT, SelfCalibration.judge(news, SelfCalibration.Conditions(today.atTime(14, 0), Market.NIFTY, true, "news")).action)
        // Solo is judged by its own outcomes only: with none, it takes.
        assertTrue(SoloCalibration.decide(SoloCalibration.outcomes(emptyList(), emptyList()), c(0, minute = 300)).take)
        // And whatever kind an outcome came with, as Solo's it is judged as Solo's.
        assertTrue(SoloCalibration.outcomes(news, emptyList()).all { it.c.kind == SoloCalibration.KIND })
    }

    @Test fun shadowsAreScoredLikeTradesAndOnlyOnceSettled() {
        val s = SoloCalibration.shadow(c(0, 100), "NIFTY25OCT25000CE", lot, ask = 100.0, last = 99.0, entryMinute = 100, horizon = 30)!!
        assertEquals(100.0, s.entry)
        assertEquals(130, s.due)
        assertFalse(s.settled)
        assertNull(s.points())
        val done = s.copy(exit = 90.0)
        assertEquals(90.0 - 100.0 - SoloCalibration.COSTS / lot, done.points()!!, 1e-9)
        // No ask: the last price; no price at all: no shadow.
        assertEquals(99.0, SoloCalibration.shadow(c(0), "X", lot, 0.0, 99.0, 100, 30)!!.entry)
        assertNull(SoloCalibration.shadow(c(0), "X", lot, 0.0, 0.0, 100, 30))
        assertNull(SoloCalibration.shadow(c(0), "X", 0, 100.0, 100.0, 100, 30))
        assertEquals(80.0, SoloCalibration.sellPrice(0.0, 80.0))
        assertEquals(81.0, SoloCalibration.sellPrice(81.0, 80.0))
        assertNull(SoloCalibration.sellPrice(Double.NaN, Double.NaN))
        // Never scored past 15:10.
        assertEquals(SoloCalibration.CUT, SoloCalibration.dueAt(340, 60))
        assertEquals(101, SoloCalibration.dueAt(100, 0))
        // Scored shadows join the record; waiting ones do not.
        val o = SoloCalibration.outcomes(emptyList(), listOf(s, done))
        assertEquals(1, o.size)
    }

    @Test fun shadowsComeDueOneAtATimeAndStaleOnesAreDropped() {
        val a = SoloCalibration.shadow(c(0, 100), "A", lot, 100.0, 100.0, 100, 15)!!
        val b = SoloCalibration.shadow(c(0, 100, Market.BANKNIFTY), "B", 30, 200.0, 200.0, 100, 30)!!
        val old = SoloCalibration.shadow(c(1, 100), "C", lot, 100.0, 100.0, 100, 15)!!
        val oldDone = old.copy(exit = 120.0)
        assertFalse(SoloCalibration.mayShadow(listOf(a), Market.NIFTY))
        assertTrue(SoloCalibration.mayShadow(listOf(a), Market.BANKNIFTY))
        assertTrue(SoloCalibration.mayShadow(listOf(a.copy(exit = 1.0)), Market.NIFTY))
        assertEquals(listOf(a), SoloCalibration.dueNow(listOf(a, b, old), today, 115))
        assertEquals(listOf(a, b), SoloCalibration.dueNow(listOf(a, b, old), today, 200))
        assertEquals(listOf(oldDone, a), SoloCalibration.prune(listOf(old, oldDone, a), today))
        assertEquals(SoloCalibration.KEEP, SoloCalibration.prune(List(SoloCalibration.KEEP + 20) { oldDone }, today).size)
    }

    @Test fun aSatOutConditionCanEarnItsWayBackThroughShadows() {
        val bad = trades(10, 1, { c(it + 20, minute = 300) })
        assertFalse(SoloCalibration.decide(bad, c(0, minute = 300)).take)
        val before = SelfCalibration.sitOutKeys(bad, today)
        assertTrue(before.isNotEmpty())
        // Twenty sat-out afternoons, followed on paper, that would have worked.
        val shadows = (1..20L).map { d -> SoloCalibration.shadow(c(d, minute = 300), "S", lot, 100.0, 100.0, 300, 15)!!.copy(exit = 125.0) }
        val o = SoloCalibration.outcomes(bad, shadows)
        assertTrue(SoloCalibration.decide(o, c(0, minute = 300)).take)
        val r = SoloCalibration.review(o, today, before)
        assertTrue(r.any { it.contains("no longer clearly bad") }, r.toString())
    }

    @Test fun reviewAndSayNameSolosWeakSpots() {
        val o = trades(12, 1, { c(it, minute = 300) }) + trades(12, 9, { c(it, minute = 60) })
        val r = SoloCalibration.review(o, today)
        assertTrue(r.first().startsWith("I'm worse at my Solo trades") && r.first().contains("in the afternoon"), r.toString())
        assertTrue(r.first().contains("Solo sits those out"))
        val s = SoloCalibration.say(o, today)!!
        assertTrue(s.startsWith("From 24 of my Solo trades on paper"), s)
        assertTrue(s.contains("weakest: my Solo trades") && s.contains("in the afternoon"), s)
        assertNotNull(SoloCalibration.say(trades(3, 0, { c(it) }), today)!!.let { if (it.contains("too few")) it else null })
        // Old trades no longer count.
        assertNull(SoloCalibration.say(trades(5, 0, { c(it) }, from = SelfCalibration.WINDOW_DAYS + 1), today))
    }

    @Test fun theIdeasSayingIsUnchanged() {
        val s = SelfCalibration.Slice(listOf(SelfCalibration.Tag(SelfCalibration.Dim.INDEX, "NIFTY", "on Nifty")), 9, 2, -38.0)
        assertEquals("my ideas on Nifty", s.what())
        assertEquals("9 ideas, 2 worked, -38.0 points a unit", s.record())
        assertEquals("my Solo trades on Nifty", s.what(SoloCalibration.WHO))
    }
}
