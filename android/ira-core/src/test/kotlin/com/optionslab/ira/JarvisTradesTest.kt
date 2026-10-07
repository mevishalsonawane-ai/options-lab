package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JarvisTradesTest {
    private val day = LocalDate.of(2026, 10, 1)
    private fun session(path: (Int) -> Double, strike: Double = 25_000.0, right: Right = Right.CE): Session {
        val mins = IntArray(376) { 555 + it }
        return Session(day, 75, listOf(Series(day.plusDays(6), strike, right, 75, mins, DoubleArray(mins.size) { path(mins[it]) }, null, null, null, null, LongArray(mins.size))))
    }

    @Test fun theOptionTradeHitsTargetStopLockOrTheEnd() {
        val at = LocalDateTime.of(day, java.time.LocalTime.of(11, 0))   // minute 660
        fun upThenDown(top: Int) = session({ m -> val k = m - 660; 100.0 + if (k <= 0) 0.0 else if (k <= top) k.toDouble() else 2.0 * top - k })
        // +1 a minute: the +40 target (Boss, 07 Oct: back to 15% / +40 on Jarvis's own trades).
        assertEquals(39.0, JarvisTrades.OptionSim.trade(session({ m -> 100.0 + maxOf(0, m - 660) }), at, true, 25_010.0, 50)!!, 1e-9)
        // -1 a minute: the stop 15% below 100.
        assertEquals(85.0 - 100 - 1, JarvisTrades.OptionSim.trade(session({ m -> 100.0 - maxOf(0, m - 660) }), at, true, 25_010.0, 50)!!, 1e-9)
        // A dear option risks 15% of its price: 45 points at 300.
        assertEquals(255.0 - 300 - 1, JarvisTrades.OptionSim.trade(session({ m -> 300.0 - maxOf(0, m - 660) }), at, true, 25_010.0, 50)!!, 1e-9)
        // The profit lock on the 40: up 10 (a quarter) then back -> breakeven after the costs; up 20 -> +10; up 30 -> +20.
        assertEquals(0.0, JarvisTrades.OptionSim.trade(upThenDown(15), at, true, 25_010.0, 50)!!, 1e-9)
        assertEquals(10.0 - 1, JarvisTrades.OptionSim.trade(upThenDown(25), at, true, 25_010.0, 50)!!, 1e-9)
        assertEquals(20.0 - 1, JarvisTrades.OptionSim.trade(upThenDown(35), at, true, 25_010.0, 50)!!, 1e-9)
        // Up 9 only: no rung yet, so back down to the 15% stop.
        assertEquals(-16.0, JarvisTrades.OptionSim.trade(upThenDown(9), at, true, 25_010.0, 50)!!, 1e-9)
        // Flat: out at 15:15 with only the costs.
        assertEquals(-1.0, JarvisTrades.OptionSim.trade(session({ 100.0 }), at, true, 25_010.0, 50)!!, 1e-9)
        // No premium floor: a cheap option is bought and scored too (the 35 floor was only for the 30-point stop).
        assertEquals(-1.0, JarvisTrades.OptionSim.trade(session({ 35.0 }), at, true, 25_010.0, 50)!!, 1e-9)
        // A price too small for a tick-rounded stop: 15% below it all the same.
        assertEquals(-1.0, JarvisTrades.OptionSim.trade(session({ 0.05 }), at, true, 25_010.0, 50)!!, 1e-9)
        // No such contract: nothing.
        assertNull(JarvisTrades.OptionSim.trade(session({ 100.0 }, right = Right.PE), at, true, 25_010.0, 50))
    }

    @Test fun fifteenPercentStopFortyTargetAndTheLadderOnIt() {
        assertEquals(0.15, JarvisTrades.STOP_SHARE, 0.0)
        assertEquals(40.0, JarvisTrades.TARGET_POINTS, 0.0)
        // The Liquidity arm's own stop and the ORB arms' target, unchanged.
        assertEquals(0.15, com.optionslab.engine.orb.LiquidityRules.PREMIUM_STOP, 0.0)
        assertEquals(40.0, com.optionslab.engine.orb.OrbRules.TARGET_POINTS, 0.0)
        // The stop: 15% below, down to the 0.05 tick; none with no room.
        assertEquals(85.0, JarvisTrades.stopFor(100.0)!!, 1e-9)
        assertEquals(85.05, JarvisTrades.stopFor(100.07)!!, 1e-9)
        assertEquals(29.75, JarvisTrades.stopFor(35.0)!!, 1e-9)
        assertNull(JarvisTrades.stopFor(0.05))
        assertNull(JarvisTrades.stopFor(0.0))
        assertEquals(140.0, JarvisTrades.targetFor(100.0), 1e-9)
        // The ladder's rungs on the 40: +10 -> breakeven after charges, +20 -> +10, +30 -> +20.
        assertNull(JarvisTrades.lockFor(100.0, 109.9, 1.5))
        assertEquals(101.5, JarvisTrades.lockFor(100.0, 110.0, 1.5)!!, 1e-9)
        assertEquals(110.0, JarvisTrades.lockFor(100.0, 120.0, 1.5)!!, 1e-9)
        assertEquals(120.0, JarvisTrades.lockFor(100.0, 130.0, 1.5)!!, 1e-9)
        assertEquals(120.0, JarvisTrades.lockFor(100.0, 139.0, 1.5)!!, 1e-9)
        assertEquals(110.0, JarvisTrades.lockFor(100.0, 120.0)!!, 1e-9)
    }

    @Test fun theRulesOfJarvisTradesAreSaidAsTheyTrade() {
        val r = JarvisTrades.rules()
        assertTrue(r.contains("with a stop 15% below the price paid and a +40 target."), r)
        assertTrue(r.contains("at +10 to breakeven after charges, at +20 to +10, at +30 to +20"), r)
        assertTrue(r.contains("A trade already open keeps the stop and target it was placed with."), r)
        assertTrue(!r.contains("30-point") && !r.contains("+60") && !r.contains("35 or less"), r)
        for (q in listOf("what is the stop loss for news trades", "What's the target on news trades?", "news trade ka stop loss kya hai",
                "what are the rules of your trades", "what stop do pattern ideas use", "Jarvis, what is the SL for AI trades")) {
            assertTrue(JarvisTrades.rulesAsked(q), q)
            assertEquals(r, Glossary.explain(q), q)
        }
        for (q in listOf("what is my stop loss", "set stop loss on news trades", "why did the news trade hit its stop", "how are my news trades doing",
                "what is a stop loss", "what is the target for nifty"))
            assertTrue(!JarvisTrades.rulesAsked(q), q)
        // The word itself is still explained, with Jarvis's own numbers.
        assertTrue(Glossary.explain("what is a stop loss")!!.contains("15% below the option's entry price, with a +40 target"))
    }

    @Test fun expiryAfterOneAndTheProvenRule() {
        assertNull(JarvisTrades.expiryBlock(true, LocalDateTime.of(day, java.time.LocalTime.of(12, 59))))
        assertNotNull(JarvisTrades.expiryBlock(true, LocalDateTime.of(day, java.time.LocalTime.of(13, 0))))
        assertNull(JarvisTrades.expiryBlock(false, LocalDateTime.of(day, java.time.LocalTime.of(14, 0))))
        val ten = List(10) { JarvisTrades.Closed(day, 500.0, false) }
        assertTrue(JarvisTrades.proven(ten)!!.startsWith("My trades stay on paper until 20 have closed there: 10 so far"))
        assertTrue(JarvisTrades.proven(ten + List(10) { JarvisTrades.Closed(day, -600.0, false) })!!.contains("not making money"))
        assertNull(JarvisTrades.proven(ten + ten))
        // Live trades never count toward it.
        assertNotNull(JarvisTrades.proven(ten + List(10) { JarvisTrades.Closed(day, 500.0, true) }))
    }

    @Test fun theScorecardJudgesEveryAnswer() {
        val t = LocalDateTime.of(day, java.time.LocalTime.of(10, 30))
        val s = listOf(
            JarvisTrades.Suggestion(t, Market.NIFTY, true, 25_000.0, "pattern: x", "approved", 12.0, 75),
            JarvisTrades.Suggestion(t.plusHours(1), Market.BANKNIFTY, false, 52_000.0, "news: y", "rejected", -9.0, 15),
            JarvisTrades.Suggestion(t.plusHours(2), Market.NIFTY, false, 25_000.0, "pattern: z", "lapsed", null),
        )
        val lines = JarvisTrades.scorecard(day, s)
        assertEquals("Today I suggested 3 trades; your answer was the better choice on 2 of 2.", lines[0])
        assertEquals("10:30 Nifty call (pattern): approved, made +12.0 points (+Rs 900.00 a lot).", lines[1])
        assertTrue(lines[2].startsWith("11:30 BankNifty put (news): rejected, would have made -9.0 points"))
        assertTrue(lines[3].endsWith("lapsed, no option prices to judge it."))
        assertEquals(listOf("No trades suggested today."), JarvisTrades.scorecard(day.plusDays(1), s))
    }
}
