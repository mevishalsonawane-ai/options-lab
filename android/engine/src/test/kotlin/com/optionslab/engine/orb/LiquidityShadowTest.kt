package com.optionslab.engine.orb

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiquidityShadowTest {
    @Test fun candidateAIsTheNextLevelWithinOneIndexStop() {
        // BANKNIFTY: 30 points.
        assertTrue(LiquidityShadow.nearLevel("BANKNIFTY", 1, 52_000.0, 52_029.0))
        assertFalse(LiquidityShadow.nearLevel("BANKNIFTY", 1, 52_000.0, 52_030.0), "exactly one unit is not closer")
        assertTrue(LiquidityShadow.nearLevel("BANKNIFTY", -1, 52_000.0, 51_980.0))
        assertFalse(LiquidityShadow.nearLevel("BANKNIFTY", -1, 52_000.0, 51_950.0))
        // FINNIFTY: 15 points.
        assertTrue(LiquidityShadow.nearLevel("FINNIFTY", 1, 24_000.0, 24_014.0))
        assertFalse(LiquidityShadow.nearLevel("FINNIFTY", 1, 24_000.0, 24_020.0))
        assertFalse(LiquidityShadow.nearLevel("BANKNIFTY", 1, 52_000.0, null), "no level ahead: never skipped")
    }

    @Test fun candidateBIsTheFinniftyThirtyMinuteBook() {
        assertTrue(LiquidityShadow.finThirty("liquidity30_fin"))
        LiquidityRules.BOOKS.filter { it != LiquidityRules.FIN30 }.forEach { assertFalse(LiquidityShadow.finThirty(it.source)) }
    }

    private val d = LiquidityShadow.SINCE
    private fun t(net: Double, near: Boolean?, book: String = "liquidity5", day: LocalDate = d) = LiquidityShadow.Trade(day, net, near, book)

    @Test fun theSummaryCountsFromTheSixthWithAndWithoutEachCandidate() {
        val s = LiquidityShadow.summarize(listOf(
            t(-500.0, true, day = d.minusDays(1)),          // before 06 Oct: not counted
            t(300.0, false), t(-200.0, true), t(-100.0, null, "liquidity30_fin"), t(400.0, false, "liquidity30_fin"),
        ))
        assertEquals(LiquidityShadow.Cut(4, 400.0), s.all)
        assertEquals(LiquidityShadow.Cut(3, 600.0), s.withoutNear)
        assertEquals(LiquidityShadow.Cut(2, 100.0), s.withoutFin30)
        assertEquals(1, s.untracked)
        assertFalse(s.enough)
        assertEquals(100.0, s.all.perTrade)
        assertNull(LiquidityShadow.Cut(0, 0.0).perTrade)
        assertEquals("Since 06 Oct: 4 paper trades, +₹400 net (+₹100 a trade) · (a) skip a level within one index stop: 3, +₹200 a trade · " +
            "(b) no FINNIFTY 30m: 2, +₹50 a trade · 1 from before (a) was recorded", LiquidityShadow.line(s))
        val one = LiquidityShadow.summarize(listOf(t(-50.0, true)))
        assertEquals("Since 06 Oct: 1 paper trade, −₹50 net (−₹50 a trade) · (a) skip a level within one index stop: 0, no trades · " +
            "(b) no FINNIFTY 30m: 1, −₹50 a trade", LiquidityShadow.line(one))
    }

    @Test fun belowFortyTradesNothingIsJudged() {
        val s = LiquidityShadow.summarize(List(39) { t(10.0, false) })
        val v = LiquidityShadow.verdict(s)
        assertTrue(v.startsWith("Liquidity 15+5 has 39 of 40 paper trades since 06 Oct; its two candidate rules are judged at 40. So far: Since 06 Oct: 39"), v)
    }

    @Test fun atFortyTradesJarvisSaysWhichCandidateHelped() {
        val base = List(30) { t(110.0, false) }
        // (a) helps: its skipped trades lost. (b) does not: FINNIFTY 30m made money.
        val a = LiquidityShadow.summarize(base + List(5) { t(-300.0, true) } + List(5) { t(200.0, false, "liquidity30_fin") })
        assertTrue(a.enough)
        val va = LiquidityShadow.verdict(a)
        assertTrue(va.startsWith("Candidate (a) helped, (b) did not, Boss. Liquidity 15+5 made +₹70 a trade over 40 paper trades since 06 Oct;"), va)
        assertTrue(va.endsWith("Nothing changes by itself: adopting one is yours."), va)
        val b = LiquidityShadow.summarize(base + List(5) { t(300.0, true) } + List(5) { t(-200.0, false, "liquidity30_fin") })
        assertTrue(LiquidityShadow.verdict(b).startsWith("Candidate (b) helped, (a) did not"))
        val both = LiquidityShadow.summarize(base + List(5) { t(-300.0, true) } + List(5) { t(-200.0, false, "liquidity30_fin") })
        assertTrue(LiquidityShadow.verdict(both).startsWith("Both helped"))
        val neither = LiquidityShadow.summarize(List(40) { t(100.0, false) })
        assertTrue(LiquidityShadow.verdict(neither).startsWith("Neither helped"), "a candidate that skips nothing did not help")
    }

    @Test fun aCandidateThatKeepsNothingOrNoTradesAtAllNeverHelped() {
        assertFalse(LiquidityShadow.helped(LiquidityShadow.Cut(0, 0.0), LiquidityShadow.Cut(0, 0.0)))
        assertFalse(LiquidityShadow.helped(LiquidityShadow.Cut(40, -400.0), LiquidityShadow.Cut(0, 0.0)))
        assertTrue(LiquidityShadow.helped(LiquidityShadow.Cut(40, -400.0), LiquidityShadow.Cut(39, 10.0)))
        assertFalse(LiquidityShadow.helped(LiquidityShadow.Cut(40, 400.0), LiquidityShadow.Cut(39, 300.0)))
    }
}
