package com.optionslab.engine.orb

import com.optionslab.engine.Olx
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import java.time.LocalDate

class ArmsBacktestTest {
    private val assets = System.getProperty("olx.assets") ?: "../app/src/main/assets"

    @Test fun theBundledMonthRanksTheArms() {
        val r = File(assets, "bars_banknifty.olx").inputStream().use { ArmsBacktest.run(Olx.read(it).asSequence()) }
        println("days ${r.days} ${r.firstDay}..${r.lastDay}")
        r.rows.forEach { println("%-10s n=%3d win=%3d%% net=%9.0f avg=%6.0f t=%s  halves %8.0f / %8.0f"
            .format(it.arm.label, it.trades, it.winPct, it.net, it.avg, it.t?.let { t -> "%.2f".format(t) }, it.firstHalf, it.secondHalf)) }
        assertTrue(r.days >= 20)
        assertEquals(ArmsBacktest.ARMS.map { it.source }, r.rows.map { it.arm.source })
        val row = r.rows.associateBy { it.arm.source }
        // The month the arms were chosen on: the range fades earned, the breakouts lost.
        assertTrue(row.getValue("range_fade").net > 0)
        assertTrue(row.getValue("orb_sweep").net > 0)
        assertTrue(row.getValue("orb").net < 0)
        r.rows.forEach { assertEquals(it.net, it.firstHalf + it.secondHalf, 1e-6) }
        assertTrue(r.trades.all { it.why in setOf("stop", "profit_lock", "target", "session_end", "last_bar") })
        assertTrue(r.trades.filter { it.arm != "orb" && it.arm != "orb_fresh" }
            .groupBy { it.day to it.arm }.values.all { it.size <= 2 }, "no arm beyond its daily cap")
    }

    private val day = LocalDate.of(2026, 9, 1)
    private fun series(right: Right, strike: Double, minutes: IntRange, px: (Int) -> Double, lo: (Int) -> Double = px, hi: (Int) -> Double = px): Series {
        val m = minutes.toList().toIntArray()
        return Series(if (right == Right.IX) null else day.plusDays(1), strike, right, 30, m, DoubleArray(m.size) { px(m[it]) },
            DoubleArray(m.size) { px(m[it]) }, DoubleArray(m.size) { hi(m[it]) }, DoubleArray(m.size) { lo(m[it]) }, null, LongArray(m.size))
    }
    private val all = 9 * 60 + 15..15 * 60 + 29

    @Test fun aTouchOfTheRangeTopIsFadedWithThePutAndHitsItsTarget() {
        // Range 53,800..54,000 by 10:00; the 10:30 bar wicks to 54,060 and closes 53,950: Range Fade and Sweep buy the PE.
        val ix = series(Right.IX, 0.0, all, { m -> if (m < 600) 53_800.0 + (m % 3) * 100 else 53_950.0 }, hi = { m -> if (m == 631) 54_060.0 else if (m < 600) 53_800.0 + (m % 3) * 100 else 53_950.0 })
        val pe = series(Right.PE, 53_900.0, all, { m -> if (m < 640) 300.0 else 400.0 })
        val ce = series(Right.CE, 53_900.0, all, { 300.0 })
        val trades = ArmsBacktest.day(Session(day, 30, listOf(ix, pe, ce)), slip = 0.0, charges = 0.0)!!
        val fade = trades.single { it.arm == "range_fade" }
        assertEquals("PE", fade.right); assertEquals("10:30", fade.signalBar); assertEquals("target", fade.why)
        assertEquals(40.0 * 30, fade.net, 1e-9)
        val sweep = trades.single { it.arm == "orb_sweep" }
        assertEquals("target", sweep.why); assertEquals(80.0 * 30, sweep.net, 1e-9)
        assertTrue(trades.none { it.arm == "orb" })
    }

    @Test fun stopsAndTheSquareOffCloseTrades() {
        // A clean break up at 10:05: the ORB buys the CE; one day it falls 50 (stop), else it drifts to 15:10.
        val ix = series(Right.IX, 0.0, all, { m -> if (m < 605) 53_800.0 + (m % 3) * 100 else 54_200.0 })
        val pe = series(Right.PE, 54_000.0, all, { 200.0 })
        val fall = series(Right.CE, 54_000.0, all, { m -> if (m < 620) 300.0 else 250.0 })
        val t1 = ArmsBacktest.day(Session(day, 30, listOf(ix, pe, fall)), 0.0, 0.0)!!.first { it.arm == "orb" }
        // The minute opened at 250, under the 260 stop: a resting SL-M fills at that open, not at its trigger.
        assertEquals("stop", t1.why); assertEquals(-50.0 * 30, t1.net, 1e-9)
        val touch = series(Right.CE, 54_000.0, all, { 300.0 }, lo = { m -> if (m == 620) 255.0 else 300.0 })
        val t1b = ArmsBacktest.day(Session(day, 30, listOf(ix, pe, touch)), 0.0, 0.0)!!.first { it.arm == "orb" }
        assertEquals("stop", t1b.why); assertEquals(-40.0 * 30, t1b.net, 1e-9)
        val flat = series(Right.CE, 54_000.0, all, { m -> 300.0 + (m % 2) })
        val t2 = ArmsBacktest.day(Session(day, 30, listOf(ix, pe, flat)), 0.0, 0.0)!!.first { it.arm == "orb" }
        assertEquals("session_end", t2.why)
        val short = series(Right.CE, 54_000.0, 9 * 60 + 15..10 * 60 + 20, { 300.0 })
        val t3 = ArmsBacktest.day(Session(day, 30, listOf(ix, pe, short)), 0.0, 0.0)!!.first { it.arm == "orb" }
        assertEquals("last_bar", t3.why)
    }

    @Test fun aTradeThatGotHalfwayAndTurnedBackLeavesAtItsLockAndCheapOptionsAreNotBought() {
        // ORB buys the CE at 300 after the 10:05 break; it trades to 322 (past 50% of the +40 target) then falls back.
        val ix = series(Right.IX, 0.0, all, { m -> if (m < 605) 53_800.0 + (m % 3) * 100 else 54_200.0 })
        val pe = series(Right.PE, 54_000.0, all, { 200.0 })
        val ce = series(Right.CE, 54_000.0, all, { m -> if (m < 620) 300.0 else if (m < 630) 322.0 else 290.0 })
        val t = ArmsBacktest.day(Session(day, 30, listOf(ix, pe, ce)), 0.0, 0.0)!!.first { it.arm == "orb" }
        // The stop rests at 310 (+10 locked); the 10:30 minute opened at 290, under it: filled at that open, as an SL-M.
        assertEquals("profit_lock", t.why); assertEquals(-10.0 * 30, t.net, 1e-9)
        // Touched inside a minute that opened above it: out at the lock itself.
        val turn = series(Right.CE, 54_000.0, all, { m -> if (m < 620) 300.0 else if (m < 630) 322.0 else 315.0 },
            lo = { m -> if (m < 620) 300.0 else if (m < 630) 322.0 else 305.0 })
        val tl = ArmsBacktest.day(Session(day, 30, listOf(ix, pe, turn)), 0.0, 0.0)!!.first { it.arm == "orb" }
        assertEquals("profit_lock", tl.why); assertEquals(10.0 * 30, tl.net, 1e-9)              // 25% of 40 locked
        // With charges, the breakeven rung is floored at entry + the round trip per unit (Rs 60 / 30 = 2 points).
        val be = series(Right.CE, 54_000.0, all, { m -> if (m < 620) 300.0 else if (m < 630) 312.0 else 303.0 },
            lo = { m -> if (m < 620) 300.0 else if (m < 630) 312.0 else 299.0 })
        val tb = ArmsBacktest.day(Session(day, 30, listOf(ix, pe, be)), 0.0, 60.0)!!.first { it.arm == "orb" }
        assertEquals("profit_lock", tb.why); assertEquals(302.0, tb.exit, 1e-9)
        // A premium of 40 or less has no 40-point stop: the arms refuse it, so the backtest does too.
        val cheap = series(Right.CE, 54_000.0, all, { 35.0 })
        assertTrue(ArmsBacktest.day(Session(day, 30, listOf(ix, pe, cheap)), 0.0, 0.0)!!.none { it.arm == "orb" })
    }

    @Test fun onAnExpiryDayTheBacktestBuysTheOptionExpiringThatDay() {
        val ix = series(Right.IX, 0.0, all, { m -> if (m < 605) 53_800.0 + (m % 3) * 100 else 54_200.0 })
        val m = all.toList().toIntArray()
        fun leg(r: Right, exp: LocalDate, px: Double) = Series(exp, 54_000.0, r, 30, m, DoubleArray(m.size) { px }, DoubleArray(m.size) { px },
            DoubleArray(m.size) { px }, DoubleArray(m.size) { px }, null, LongArray(m.size))
        val today = listOf(leg(Right.CE, day, 150.0), leg(Right.PE, day, 150.0))
        val next = listOf(leg(Right.CE, day.plusDays(7), 300.0), leg(Right.PE, day.plusDays(7), 300.0))
        val t = ArmsBacktest.day(Session(day, 30, listOf(ix) + today + next), 0.0, 0.0)!!.first { it.arm == "orb" }
        assertEquals(150.0, t.entry, 1e-9)
    }

    @Test fun theReplayGivesLiquidityPriorityOverTheOrbArms() {
        // ORB buys the CE after the 10:05 break and holds it to 15:10; Liquidity 15+5 breaks a level at 11:00 the same way.
        val ix = series(Right.IX, 0.0, all, { m -> if (m < 605) 53_800.0 + (m % 3) * 100 else 54_200.0 })
        val pe = series(Right.PE, 54_000.0, all, { 200.0 })
        val ce = series(Right.CE, 54_000.0, all, { m -> 300.0 + (m % 2) })
        val orb = ArmsBacktest.day(Session(day, 30, listOf(ix, pe, ce)), 0.0, 0.0)!!.first { it.arm == "orb" }
        assertEquals(day.atTime(10, 10), orb.entryTime); assertEquals(day.atTime(15, 10), orb.exitTime)
        val liq = ArmPriority.Leg("liquidity15", ArmPriority.Rank.LIQUIDITY, "BANKNIFTY", 1, day.atTime(11, 0), day.atTime(11, 40))
        val g = ArmsBacktest.guarded(listOf(orb), listOf(liq))
        assertTrue(liq in g.taken, "a Liquidity entry while ORB holds BANKNIFTY is taken")
        assertTrue(g.taken.any { it.who == "orb" }); assertTrue(g.refused.isEmpty())
        // ORB's signal while Liquidity holds the index: refused, and the reason says Liquidity has priority.
        val early = liq.copy(entry = day.atTime(9, 30), exit = day.atTime(10, 30))
        val h = ArmsBacktest.guarded(listOf(orb), listOf(early))
        assertEquals(listOf(early), h.taken)
        assertEquals("orb refused: liquidity15 holds BANKNIFTY (Liquidity has priority over ORB arms)", h.refused.single().words)
        // Trades made by hand (no times) stay out of the guard; an unknown arm is not one of the ORB family.
        val hand = ArmsBacktest.Trade(day, "orb", "PE", "10:05", 1.0, 2.0, "target", 1.0)
        assertTrue(ArmsBacktest.guarded(listOf(hand)).taken.isEmpty())
        val odd = orb.copy(arm = "mystery", right = "PE")
        assertEquals(ArmPriority.Rank.OTHER, ArmsBacktest.guarded(listOf(odd)).taken.single().rank)
        assertEquals(-1, ArmsBacktest.guarded(listOf(odd)).taken.single().side)
    }

    @Test fun aDayWithoutItsIndexOrOptionsIsSkipped() {
        val ix = series(Right.IX, 0.0, all, { 53_900.0 })
        assertNull(ArmsBacktest.day(Session(day, 30, listOf(series(Right.CE, 1.0, all, { 1.0 })))))
        assertNull(ArmsBacktest.day(Session(day, 30, listOf(ix))))
        assertNull(ArmsBacktest.day(Session(day, 30, listOf(ix, series(Right.CE, 53_900.0, all, { 1.0 })))))
        assertNull(ArmsBacktest.day(Session(day, 30, listOf(series(Right.IX, 0.0, 9 * 60 + 15..9 * 60 + 40, { 1.0 })))))
        val lateIx = series(Right.IX, 0.0, all, { m -> if (m < 605) 53_800.0 + (m % 3) * 100 else 54_200.0 })
        val empty = ArmsBacktest.run(sequenceOf(Session(day, 30, listOf(lateIx, series(Right.CE, 54_000.0, 15 * 60 + 10..15 * 60 + 29, { 1.0 }),
            series(Right.PE, 54_000.0, all, { 1.0 })))))
        assertEquals(1, empty.days)
        assertTrue(empty.rows.first().trades == 0 && empty.rows.first().winPct == 0 && empty.rows.first().t == null)
    }
}
