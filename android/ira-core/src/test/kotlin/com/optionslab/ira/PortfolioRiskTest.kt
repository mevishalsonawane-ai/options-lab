package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Risk across the arms (TODO S12): the correlations, the combined worst case, and the guard that only ever blocks. */
class PortfolioRiskTest {
    private val today = LocalDate.of(2026, 10, 9)

    /** 30 days: A and B move together, C opposite to A, D unrelated; each loses up to its own size. */
    private fun record(): List<PortfolioRisk.Trade> {
        val r = java.util.Random(11)
        val out = ArrayList<PortfolioRisk.Trade>()
        for (i in 0 until 30) {
            val day = today.minusDays(30L - i)
            val m = r.nextGaussian() * 1_000
            out += PortfolioRisk.Trade("orb", day, m + r.nextGaussian() * 200)
            out += PortfolioRisk.Trade("orb_fresh", day, m * 1.5 + r.nextGaussian() * 300, live = i % 2 == 0)
            out += PortfolioRisk.Trade("range_fade", day, -m + r.nextGaussian() * 200)
            out += PortfolioRisk.Trade("hero", day, r.nextGaussian() * 1_000)
        }
        return out
    }

    @Test fun pearsonNeedsEnoughDaysAndMovement() {
        val x = DoubleArray(12) { it.toDouble() }
        assertEquals(1.0, PortfolioRisk.pearson(x, DoubleArray(12) { 2.0 * it + 1 })!!, 1e-12)
        assertEquals(-1.0, PortfolioRisk.pearson(x, DoubleArray(12) { -it.toDouble() })!!, 1e-12)
        assertNull(PortfolioRisk.pearson(DoubleArray(9) { it.toDouble() }, DoubleArray(9) { it.toDouble() }))
        assertNull(PortfolioRisk.pearson(x, DoubleArray(12) { 5.0 }))
        assertNull(PortfolioRisk.pearson(x, DoubleArray(11)))
    }

    @Test fun theMatrixFindsTheArmsThatMoveTogether() {
        val m = PortfolioRisk.matrix(record(), today)
        assertEquals(listOf("hero", "orb", "orb_fresh", "range_fade"), m.arms)
        assertTrue(m.of("orb", "orb_fresh")!! > 0.8)
        assertTrue(m.of("orb", "range_fade")!! < -0.8)
        assertTrue(kotlin.math.abs(m.of("orb", "hero")!!) < 0.6)
        assertEquals(1.0, m.of("orb", "orb"))
        assertEquals(30, m.days("orb", "hero"))
        assertEquals(listOf(setOf("orb", "orb_fresh")), PortfolioRisk.groups(m))
        // The window: only the last 10 days traded - too few for a figure.
        assertTrue(PortfolioRisk.matrix(record(), today, window = 9).rho.isEmpty())
        // Days after today are not counted.
        assertEquals(m, PortfolioRisk.matrix(record() + PortfolioRisk.Trade("orb", today.plusDays(3), 1e6), today))
        assertTrue(PortfolioRisk.pairLines(m).first().contains("moves together"))
        assertTrue(PortfolioRisk.matrix(emptyList(), today).empty)
    }

    @Test fun theStopsAndTheCombinedWorstCase() {
        val t = listOf(PortfolioRisk.Trade("a", today, -100.0), PortfolioRisk.Trade("a", today.minusDays(1), -300.0),
            PortfolioRisk.Trade("a", today.minusDays(2), 500.0)) +
            (1..20).map { PortfolioRisk.Trade("b", today.minusDays(it.toLong()), -it * 10.0) }
        val s = PortfolioRisk.stops(t, today)
        assertEquals(300.0, s["a"]!!, 1e-9)                          // fewer than 10 losses: the largest
        assertEquals(180.0, s["b"]!!, 1e-9)                          // the 90th percentile of 10..200
        val m = PortfolioRisk.matrix(record(), today)
        val st = PortfolioRisk.stops(record(), today)
        val (worst, g) = PortfolioRisk.worstCase(m, st)
        assertEquals(setOf("orb", "orb_fresh"), g)
        assertEquals(st.getValue("orb") + st.getValue("orb_fresh"), worst, 1e-9)
        assertEquals(0.0 to emptySet(), PortfolioRisk.worstCase(PortfolioRisk.matrix(emptyList(), today), emptyMap()))
    }

    private fun guard(capital: Double = 400_000.0, capPct: Double = 2.0): PortfolioGuard {
        val g = PortfolioGuard()
        val m = PortfolioRisk.matrix(record(), today)
        // Stops of Rs 5,000 each: two together (10,000) pass a 2% cap of 4 lakh (8,000).
        g.update(m, mapOf("orb" to 5_000.0, "orb_fresh" to 5_000.0, "range_fade" to 5_000.0, "hero" to 5_000.0), PortfolioGuard.Config(capital, capPct))
        return g
    }

    @Test fun aLaterCorrelatedSameDirectionEntryOverTheCapIsBlocked() {
        val g = guard()
        assertFalse(g.check("orb", +1, live = false, today, 1_000).block)
        val d = g.check("orb_fresh", +1, live = false, today, 2_000)
        assertTrue(d.block)
        assertEquals(listOf("orb"), d.partners)
        assertEquals(10_000.0, d.combined, 1e-9)
        assertTrue(d.why!!.contains("blocked by the portfolio guard"), d.why)
        assertTrue(d.why!!.contains("Rs 8,000"), d.why)
        // Blocked for the rest of the day, each time it asks.
        assertTrue(g.check("orb_fresh", +1, live = false, today, 60_000).block)
        assertEquals(2, g.blocksToday(today).size)
        // The next day starts clean.
        assertFalse(g.check("orb_fresh", +1, live = false, today.plusDays(1), 3_000).block)
        assertTrue(g.blocksToday(today).isEmpty())
    }

    @Test fun theGuardOnlyBlocksWhenEveryConditionHolds() {
        // Opposite directions, unrelated or anti-correlated arms, the other account, under the cap, off, no capital, no direction.
        val g = guard()
        assertFalse(g.check("orb", +1, false, today, 1).block)
        assertFalse(g.check("orb_fresh", -1, false, today, 2).block)       // the other way
        assertFalse(g.check("hero", +1, false, today, 3).block)            // not correlated
        assertFalse(g.check("range_fade", +1, false, today, 4).block)      // moves against ORB
        assertFalse(g.check("orb_fresh", +1, true, today, 5).block)        // live: ORB opened on paper only
        assertFalse(g.check("orb", +1, false, today, 6).block)             // the same arm again
        val roomy = guard(capPct = 5.0)                                    // a 20,000 cap
        assertFalse(roomy.check("orb", +1, false, today, 1).block)
        val d = roomy.check("orb_fresh", +1, false, today, 2)
        assertFalse(d.block); assertEquals(listOf("orb"), d.partners)
        val off = guard().also { it.setConfig(it.config.copy(on = false)) }
        assertFalse(off.check("orb", +1, false, today, 1).block); assertFalse(off.check("orb_fresh", +1, false, today, 2).block)
        val poor = guard(capital = 0.0)
        assertFalse(poor.check("orb", +1, false, today, 1).block); assertFalse(poor.check("orb_fresh", +1, false, today, 2).block)
        val flat = guard()
        assertFalse(flat.check("orb", 0, false, today, 1).block)
        assertFalse(flat.check("orb_fresh", 0, false, today, 2).block)
        // Its decision is a yes or a no: it never names an order, a size or a direction to take.
        val fields = PortfolioGuard.Decision::class.java.declaredFields.map { it.name }.filter { !it.startsWith("$") }.toSet()
        assertEquals(setOf("block", "why", "partners", "combined"), fields)
    }

    @Test fun theDoorRefusesOnlyALiveOrderItsArmWasBlockedFrom() {
        val g = guard()
        assertNull(g.doorRefusal("orb_fresh", +1, today, 0))
        assertFalse(g.check("orb", +1, live = true, today, 1_000).block)
        assertNull(g.doorRefusal("orb", +1, today, 1_100))                 // let through: never refused at the door
        assertTrue(g.check("orb_fresh", +1, live = true, today, 2_000).block)
        assertNotNull(g.doorRefusal("orb_fresh", +1, today, 2_100))
        // Review R1: only the blocked arm - ORB itself, an uncorrelated arm, an order whose arm never asked - still goes.
        assertNull(g.doorRefusal("orb", +1, today, 2_100))
        assertNull(g.doorRefusal("hero", +1, today, 2_100))
        assertNull(g.doorRefusal("saved", +1, today, 2_100))
        assertNull(g.doorRefusal("orb_fresh", -1, today, 2_100))           // another direction
        assertNull(g.doorRefusal("orb_fresh", +1, today, 2_000 + 61_000))  // stale
        assertNull(g.doorRefusal("orb_fresh", +1, today.plusDays(1), 2_100)) // another day
        assertNull(g.doorRefusal("orb_fresh", +1, today, 1_000))           // before the block
        // Another arm let through does not clear it; the arm's own later let-through does.
        assertFalse(g.check("hero", +1, live = true, today, 3_000).block)
        assertNotNull(g.doorRefusal("orb_fresh", +1, today, 3_100))
        g.setConfig(g.config.copy(on = false))
        assertFalse(g.check("orb_fresh", +1, live = true, today, 4_000).block)
        assertNull(g.doorRefusal("orb_fresh", +1, today, 4_100))
        g.setConfig(g.config.copy(on = true))
        assertTrue(g.check("orb_fresh", +1, live = true, today, 5_000).block)
        g.clear()
        assertNull(g.doorRefusal("orb_fresh", +1, today, 5_100))
        // A paper block is never a door refusal (the door is for live orders).
        val p = guard()
        p.check("orb", +1, false, today, 1); assertTrue(p.check("orb_fresh", +1, false, today, 2).block)
        assertNull(p.doorRefusal("orb_fresh", +1, today, 3))
    }

    @Test fun anEntryLetThroughButNotTakenIsNeverCountedAsOpened() {
        // Review R1: the app asks with open = false and says [opened] only once the entry is taken (filled, or sent live).
        val g = guard()
        assertFalse(g.check("orb", +1, live = false, today, 1_000, open = false).block)
        // ORB was then skipped (the flow, the regime, the account guard, no fill): ORB Fresh is not blocked by it.
        assertFalse(g.check("orb_fresh", +1, live = false, today, 2_000, open = false).block)
        assertTrue(g.blocksToday(today).isEmpty())
        // ORB's entry taken: now the later correlated one is blocked.
        g.opened("orb", +1, live = false, today, 3_000)
        assertTrue(g.check("orb_fresh", +1, live = false, today, 4_000, open = false).block)
        // Opened on paper says nothing about live, nor about the other way.
        assertFalse(g.check("orb_fresh", +1, live = true, today, 5_000, open = false).block)
        assertFalse(g.check("orb_fresh", -1, live = false, today, 6_000, open = false).block)
        // No direction: nothing counted.
        val h = guard()
        h.opened("orb", 0, live = false, today, 1)
        assertFalse(h.check("orb_fresh", +1, live = false, today, 2, open = false).block)
    }

    @Test fun armNamesReadAsOneKey() {
        assertEquals("liquidity:BANKNIFTY", PortfolioRisk.canon("liquidity:BANKNIFTY"))
        assertEquals("liquidity:FINNIFTY", PortfolioRisk.canon("Liquidity 15+5", "FINNIFTY26OCT27000CE"))
        assertEquals("liquidity:FINNIFTY", PortfolioRisk.canon("Liquidity 5m FINNIFTY"))
        assertEquals("orb_fresh", PortfolioRisk.canon("ORB Fresh"))
        assertEquals("solo", PortfolioRisk.canon("Jarvis Solo"))
        assertEquals("night_r3", PortfolioRisk.canon("Night (R3)"))
        assertEquals("mcx_ng_eve", PortfolioRisk.canon("MCX natural gas evening"))
        for (s in FlowShadow.STRATEGIES) assertEquals(s.key, PortfolioRisk.canon(s.key), s.key)
        assertNull(PortfolioRisk.canonOrNull("Manual"))
        assertNull(PortfolioRisk.canonOrNull("Outside IraAlgo (Kite)"))
        assertEquals("orb", PortfolioRisk.canonOrNull("ORB"))
        assertEquals("Liquidity BANKNIFTY", PortfolioRisk.label("liquidity:BANKNIFTY"))
        assertEquals("ORB Fresh", PortfolioRisk.label("orb_fresh"))
        assertEquals("Something new", PortfolioRisk.label("something_new"))
    }

    @Test fun jarvisAnswersHowAlikeTheArmsAreAndWhyOneWasBlocked() {
        assertEquals(PortfolioRisk.Ask.CORRELATION, PortfolioRisk.asked("how correlated are my strategies?"))
        assertEquals(PortfolioRisk.Ask.CORRELATION, PortfolioRisk.asked("do my arms move together"))
        assertEquals(PortfolioRisk.Ask.WHY_BLOCKED, PortfolioRisk.asked("why was ORB Fresh blocked?"))
        assertEquals(PortfolioRisk.Ask.WHY_BLOCKED, PortfolioRisk.asked("why did the portfolio guard block it"))
        assertNull(PortfolioRisk.asked("why was my order blocked"))
        assertNull(PortfolioRisk.asked("what is the nifty doing"))
        val m = PortfolioRisk.matrix(record(), today)
        val st = PortfolioRisk.stops(record(), today)
        val a = PortfolioRisk.answerCorrelation(m, st, 8_000.0)
        assertTrue(a.contains("ORB, ORB Fresh move together") || a.contains("ORB Fresh, ORB move together"), a)
        assertTrue(a.contains("never add"), a)
        assertTrue(PortfolioRisk.answerCorrelation(PortfolioRisk.matrix(emptyList(), today), emptyMap(), 8_000.0).contains("at least two arms"))
        val g = guard()
        assertEquals("Boss, the portfolio guard has blocked nothing today.", PortfolioRisk.answerBlocked("why was orb fresh blocked", g.blocksToday(today)))
        g.check("orb", +1, false, today, 1); g.check("orb_fresh", +1, false, today, 2)
        val why = PortfolioRisk.answerBlocked("why was orb fresh blocked", g.blocksToday(today))
        assertTrue(why.startsWith("Boss, ORB Fresh's paper bullish entry was blocked"), why)
        val line = PortfolioRisk.line(m, st, 8_000.0, 1)
        assertTrue(line.startsWith("Portfolio: 2 arms move together") && line.endsWith("1 entry blocked today."), line)
        assertTrue(PortfolioRisk.line(PortfolioRisk.matrix(emptyList(), today), emptyMap(), 1.0, 0).contains("too few"))
        val solo = PortfolioRisk.matrix(record().filter { it.arm == "orb" || it.arm == "hero" }, today)
        assertTrue(PortfolioRisk.line(solo, st, 8_000.0, 0).startsWith("Portfolio: no two arms move together"))
    }
}
