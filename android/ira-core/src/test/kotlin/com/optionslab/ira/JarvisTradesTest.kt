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
        // +1 a minute: the +40 target.
        assertEquals(39.0, JarvisTrades.OptionSim.trade(session({ m -> 100.0 + maxOf(0, m - 660) }), at, true, 25_010.0, 50)!!, 1e-9)
        // -1 a minute: the stop 15% below 100.
        assertEquals(85.0 - 100 - 1, JarvisTrades.OptionSim.trade(session({ m -> 100.0 - maxOf(0, m - 660) }), at, true, 25_010.0, 50)!!, 1e-9)
        // Up 25 then back down: the profit lock keeps +10 (half the target reached locks a quarter of it).
        val r = JarvisTrades.OptionSim.trade(session({ m -> val k = m - 660; 100.0 + if (k <= 0) 0.0 else if (k <= 25) k.toDouble() else 50.0 - k }), at, true, 25_010.0, 50)!!
        assertEquals(10.0 - 1, r, 1e-9)
        // Flat: out at 15:15 with only the costs.
        assertEquals(-1.0, JarvisTrades.OptionSim.trade(session({ 100.0 }), at, true, 25_010.0, 50)!!, 1e-9)
        // No such contract: nothing.
        assertNull(JarvisTrades.OptionSim.trade(session({ 100.0 }, right = Right.PE), at, true, 25_010.0, 50))
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
