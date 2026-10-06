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
    private fun t(net: Double, near: Boolean?, book: String = "liquidity5", day: LocalDate = d, vol: Boolean? = null) =
        LiquidityShadow.Trade(day, net, near, book, vol)

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
        assertEquals("Since 06 Oct, per lot: 4 paper trades, +₹400 net (+₹100 a trade) · (a) skip a level within one index stop: 3, +₹200 a trade · " +
            "(b) no FINNIFTY 30m: 2, +₹50 a trade · (c) skip in high volatility: tracked from its first trade · (d) all out by 14:30: tracked " +
            "from its first trade · (e) 2 strikes in the money: tracked from its first trade · (f) only strong close + momentum: tracked " +
            "from its first trade · 1 from before (a) was recorded",
            LiquidityShadow.line(s))
        val one = LiquidityShadow.summarize(listOf(t(-50.0, true)))
        assertEquals("Since 06 Oct, per lot: 1 paper trade, −₹50 net (−₹50 a trade) · (a) skip a level within one index stop: 0, no trades · " +
            "(b) no FINNIFTY 30m: 1, −₹50 a trade · (c) skip in high volatility: tracked from its first trade · (d) all out by 14:30: tracked " +
            "from its first trade · (e) 2 strikes in the money: tracked from its first trade · (f) only strong close + momentum: tracked " +
            "from its first trade", LiquidityShadow.line(one))
    }

    @Test fun belowFortyTradesNothingIsJudged() {
        val s = LiquidityShadow.summarize(List(39) { t(10.0, false) })
        val v = LiquidityShadow.verdict(s)
        assertTrue(v.startsWith("Liquidity 15+5 has 39 of 40 paper trades since 06 Oct; its candidate rules are judged at 40. So far: Since 06 Oct, per lot: 39"), v)
    }

    @Test fun atFortyTradesJarvisSaysWhichCandidateHelped() {
        val base = List(30) { t(110.0, false) }
        // (a) helps: its skipped trades lost. (b) does not: FINNIFTY 30m made money.
        val a = LiquidityShadow.summarize(base + List(5) { t(-300.0, true) } + List(5) { t(200.0, false, "liquidity30_fin") })
        assertTrue(a.enough)
        val va = LiquidityShadow.verdict(a)
        assertTrue(va.startsWith("Candidate (a) helped, (b) did not, Boss. Per lot, Liquidity 15+5 made +₹70 a trade over 40 paper trades since 06 Oct;"), va)
        assertTrue(va.endsWith("(c) skipping signals in high volatility has 0 of 40 trades since it was added; it is judged at 40. " +
            "(d) selling everything at 14:30 has 0 of 40 trades priced; it is judged at 40. (e) buying 2 strikes in the money has 0 of 40 " +
            "trades priced; it is judged at 40. (f) only a strong close with premium momentum (a forward test) has 0 of 40 trades since it " +
            "was added; it is judged at 40. Nothing changes by itself: adopting one is yours."), va)
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

    @Test fun candidateCCountsOnlyTheTradesWithItsFlagWithAndWithoutTheSkips() {
        val s = LiquidityShadow.summarize(listOf(
            t(-500.0, false, day = d.minusDays(1), vol = true),   // before 06 Oct: not counted
            t(100.0, false),                                    // before (c) was recorded: not counted for it
            t(300.0, false, vol = false), t(-200.0, false, vol = true), t(1_100.0, false, vol = false),
        ))
        assertEquals(LiquidityShadow.Cut(4, 1_300.0), s.all)
        assertEquals(LiquidityShadow.Cut(3, 1_200.0), s.volAll)
        assertEquals(LiquidityShadow.Cut(2, 1_400.0), s.withoutVol)
        assertFalse(s.volEnough)
        assertTrue(LiquidityShadow.line(s).contains(" · (c) skip in high volatility: 2 of 3, +₹700 a trade against +₹400 a trade · (d)"), LiquidityShadow.line(s))
        // Wide figures stay whole numbers (the row keeps each figure on one line).
        val wide = LiquidityShadow.summarize(listOf(t(12_345.6, false, vol = true), t(-1_000.4, false, vol = false)))
        assertTrue(LiquidityShadow.line(wide).contains("(c) skip in high volatility: 1 of 2, −₹1,000 a trade against +₹5,673 a trade · "), LiquidityShadow.line(wide))
    }

    @Test fun atFortyTradesOfItsOwnJarvisSaysWhetherCandidateCHelped() {
        val base = List(30) { t(110.0, false, vol = false) }
        // The skipped high-volatility trades lost: (c) helped.
        val helped = LiquidityShadow.summarize(base + List(10) { t(-300.0, false, vol = true) })
        assertTrue(helped.volEnough)
        val v = LiquidityShadow.verdict(helped)
        assertTrue(v.contains("(c) skipping signals in high volatility helped: +₹110 a trade over 30 against +₹8 a trade over 40 since it was added. " +
            "(d) selling"), v)
        assertTrue(v.endsWith("Nothing changes by itself: adopting one is yours."), v)
        // They made money: it did not.
        val not = LiquidityShadow.verdict(LiquidityShadow.summarize(base + List(10) { t(300.0, false, vol = true) }))
        assertTrue(not.contains("(c) skipping signals in high volatility did not help: +₹110 a trade over 30 against +₹158 a trade over 40"), not)
        // Forty trades in all but fewer of (c)'s own: (c) is not judged yet; (a) and (b) are.
        val early = LiquidityShadow.verdict(LiquidityShadow.summarize(List(25) { t(10.0, false) } + List(15) { t(10.0, false, vol = true) }))
        assertTrue(early.startsWith("Neither helped"), early)
        assertTrue(early.contains("(c) skipping signals in high volatility has 15 of 40 trades since it was added; it is judged at 40."), early)
    }

    // ---- (d) all out by 14:30 and (e) 2 strikes in the money -----------------------------------------------------------

    @Test fun candidatesDAndEArePricedOnTheSameTrades() {
        assertEquals(51_800, LiquidityShadow.itm2Strike(1, 51_900, "BANKNIFTY"), "a CE one step lower")
        assertEquals(52_000, LiquidityShadow.itm2Strike(-1, 51_900, "BANKNIFTY"))
        assertEquals(23_950, LiquidityShadow.itm2Strike(-1, 23_900, "FINNIFTY"))
        assertFalse(LiquidityShadow.exitAllDue(d.atTime(14, 29, 59)))
        assertTrue(LiquidityShadow.exitAllDue(d.atTime(14, 30)))
        assertEquals((220.0 - 200.0) * 30 - ShadowRules.charges(200.0, 220.0, 30), LiquidityShadow.net(200.0, 220.0, 30), 1e-9)
        // Sold before 14:31: (d) is the trade as made; later, the price seen from 14:30; never seen: not counted.
        assertEquals(-123.0, LiquidityShadow.exitAllNet(-123.0, d.atTime(14, 30, 40), 200.0, 30, null))
        assertEquals(LiquidityShadow.net(200.0, 240.0, 30), LiquidityShadow.exitAllNet(-123.0, d.atTime(15, 0), 200.0, 30, 240.0)!!, 1e-9)
        assertNull(LiquidityShadow.exitAllNet(-123.0, d.atTime(15, 0), 200.0, 30, null))
    }

    @Test fun theRowAndJarvisCountDAndEOnTheTradesTheyPriced() {
        fun t(net: Double, d1430: Double?, itm2: Double?) = LiquidityShadow.Trade(d, net, false, "liquidity15", false, d1430, itm2)
        val s = LiquidityShadow.summarize(listOf(t(100.0, 100.0, 300.0), t(-200.0, 50.0, null), t(50.0, null, -10.0)))
        assertEquals(LiquidityShadow.Cut(2, -100.0), s.exitAll); assertEquals(LiquidityShadow.Cut(2, 150.0), s.at1430)
        assertEquals(LiquidityShadow.Cut(2, 150.0), s.itm1); assertEquals(LiquidityShadow.Cut(2, 290.0), s.itm2)
        assertTrue(LiquidityShadow.line(s).contains(" · (d) all out by 14:30: 2, +₹75 a trade against −₹50 a trade · " +
            "(e) 2 strikes in the money: 2, +₹145 a trade against +₹75 a trade · (f)"), LiquidityShadow.line(s))
        assertTrue(LiquidityShadow.better(s.exitAll, s.at1430)); assertFalse(LiquidityShadow.better(s.at1430, s.exitAll))
        assertFalse(LiquidityShadow.better(LiquidityShadow.Cut(0, 0.0), s.at1430)); assertFalse(LiquidityShadow.better(s.at1430, LiquidityShadow.Cut(0, 0.0)))
        // At 40 priced trades of their own Jarvis judges them: (d) helped, (e) did not.
        val many = LiquidityShadow.summarize(List(40) { t(100.0, 150.0, 90.0) })
        val v = LiquidityShadow.verdict(many)
        assertTrue(v.contains("(d) selling everything at 14:30 helped: +₹150 a trade against +₹100 a trade over the same 40 trades. " +
            "(e) buying 2 strikes in the money did not help: +₹90 a trade against +₹100 a trade over the same 40 trades. (f) only"), v)
    }

    // ---- (f) only a strong close with premium momentum (a pre-registered forward test) --------------------------------

    private fun m(h: Int, mm: Int, c: Double) = Bar(d.atTime(h, mm), c, c, c, c)

    @Test fun candidateFNeedsAStrongCloseAndPremiumMomentumBeforeTheEntry() {
        // 10.4 bp of 50,000 is 52 points: more than that, in the trade's direction.
        assertTrue(LiquidityShadow.strongClose(1, 50_052.1, 50_000.0))
        assertFalse(LiquidityShadow.strongClose(1, 50_052.0, 50_000.0), "exactly 10.4 bp is not more")
        assertTrue(LiquidityShadow.strongClose(-1, 49_947.9, 50_000.0))
        assertFalse(LiquidityShadow.strongClose(-1, 50_100.0, 50_000.0), "the wrong way")
        assertFalse(LiquidityShadow.strongClose(1, 1.0, 0.0))
        val at = d.atTime(10, 30)
        val up = listOf(m(10, 23, 200.0), m(10, 24, 205.0), m(10, 29, 210.0), m(10, 30, 1.0))   // 10:30 has not closed by 10:30
        val down = listOf(m(10, 24, 150.0), m(10, 29, 140.0))
        assertEquals(true, LiquidityShadow.premiumMomentum(up, down, at))
        assertEquals(false, LiquidityShadow.premiumMomentum(down, up, at))
        assertEquals(false, LiquidityShadow.premiumMomentum(up, up, at), "the opposite right rose too")
        assertNull(LiquidityShadow.premiumMomentum(up, listOf(m(10, 29, 140.0)), at), "no candle 5 minutes before")
        assertNull(LiquidityShadow.premiumMomentum(emptyList(), down, at))
        assertEquals(true, LiquidityShadow.strongMomentum(1, 50_100.0, 50_000.0, up, down, at))
        assertEquals(false, LiquidityShadow.strongMomentum(1, 50_010.0, 50_000.0, up, down, at))
        assertNull(LiquidityShadow.strongMomentum(1, 50_100.0, 50_000.0, up, emptyList(), at))
    }

    @Test fun candidateFCountsOnlyTheTradesWithItsFlagAndIsJudgedAtForty() {
        fun t(net: Double, strong: Boolean?) = LiquidityShadow.Trade(d, net, false, "liquidity15", strong = strong)
        val s = LiquidityShadow.summarize(listOf(t(100.0, null), t(300.0, true), t(-200.0, false), t(-100.0, false)))
        assertEquals(LiquidityShadow.Cut(3, 0.0), s.strongAll); assertEquals(LiquidityShadow.Cut(1, 300.0), s.withStrong)
        assertTrue(LiquidityShadow.line(s).endsWith(" · (f) only strong close + momentum: 1 of 3, +₹300 a trade vs +₹0 a trade"), LiquidityShadow.line(s))
        val helped = LiquidityShadow.verdict(LiquidityShadow.summarize(List(30) { t(-50.0, false) } + List(10) { t(200.0, true) }))
        assertTrue(helped.contains("(f) only a strong close with premium momentum (a forward test) helped: +₹200 a trade over 10 against " +
            "+₹13 a trade over 40 since it was added. Nothing changes"), helped)
        val not = LiquidityShadow.verdict(LiquidityShadow.summarize(List(30) { t(50.0, false) } + List(10) { t(-200.0, true) }))
        assertTrue(not.contains("(f) only a strong close with premium momentum (a forward test) did not help"), not)
    }
}
