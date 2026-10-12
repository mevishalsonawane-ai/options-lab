package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArmWeekTest {
    private val sunday = LocalDate.of(2026, 10, 25)
    private val monday = LocalDate.of(2026, 10, 19)
    private fun t(day: LocalDate, net: Double) = ArmWeek.Trade(day, net)

    /** ORB: 15 trades last week, 10 lost, and 2 this week; Liquidity BANKNIFTY: 6 trades, up; ORB Fresh armed, none. */
    private val orb = ArmWeek.Arm("ORB", true, List(10) { t(monday.minusDays(6), -200.0) } + List(5) { t(monday.minusDays(5), 204.6) } +
        listOf(t(monday, -150.0), t(monday.plusDays(2), 90.0)))
    private val liq = ArmWeek.Arm("Liquidity 15+5 BANKNIFTY", true, listOf(t(monday.minusDays(6), 900.0), t(monday.minusDays(5), -300.0),
        t(monday, 1_200.0), t(monday.plusDays(1), -200.0), t(monday.plusDays(3), 800.0), t(monday.plusDays(4), 400.0)))
    private val fresh = ArmWeek.Arm("ORB Fresh", true, emptyList())
    private val sweep = ArmWeek.Arm("ORB Sweep", false, emptyList())

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|better switched off|consider)\\b")

    @Test fun theWeekIsMondayToSundayAndOnSundayItIsTheWeekJustEnded() {
        assertEquals(monday to sunday, ArmWeek.weekOf(sunday))
        assertEquals(monday to sunday, ArmWeek.weekOf(LocalDate.of(2026, 10, 22)))
    }

    @Test fun eachArmsWeekBesideItsTestAndItsPaperTest() {
        val s = ArmWeek.say(listOf(orb, liq, fresh, sweep), sunday)!!
        val c = s.chat
        assertTrue(c.startsWith("Boss, the arms' paper week (Mon 19 Oct to Sun 25 Oct):"), c)
        assertTrue(c.contains("- Liquidity 15+5 BANKNIFTY (armed): 4 trades, 3 won, +Rs 2,200"), c)
        assertTrue(c.contains("paper test: too early (6/60, 9/40), 6 trades since 13 Oct, +Rs 2,800, within its backtest's range so far."), c)
        assertTrue(c.contains("- ORB (armed): 2 trades, 1 won, -Rs 60; its two-year test averaged -Rs 1,411 a week a lot (this week at or above it); paper test: too early (17/60, 9/40)"), c)
        assertTrue(c.contains("- ORB Fresh (armed): no paper trades this week"), c)
        assertTrue(c.contains("its paper test has no trades since 13 Oct yet"), c)
        assertFalse(c.contains("ORB Sweep"), c)
        assertTrue(c.indexOf("- Liquidity") < c.indexOf("- ORB (armed)"), c)
        assertTrue(c.contains("No arm has passed its paper test. Paper tests count from 13 Oct and need 60 trades and 40 trading days"), c)
        assertFalse(c.contains("15-trade"), c)
        assertTrue(c.endsWith(ArmWeek.NOTE), c)
        assertFalse(ADVICE.containsMatchIn(c), c)
    }

    @Test fun aloudHasNoAmount() {
        val a = ArmWeek.say(listOf(orb, liq, fresh), sunday)!!.aloud
        assertTrue(a.startsWith("Boss, your arms' paper week. "), a)
        assertTrue(a.contains("Liquidity 15+5 BANKNIFTY, 4 trades, 3 won, up for the week"), a)
        assertTrue(a.contains("ORB, 2 trades, 1 won, down for the week"), a)
        assertTrue(a.contains("ORB Fresh, no trades"), a)
        assertTrue(a.contains("No arm has passed its paper test."), a)
        assertFalse(a.contains("Rs"), a)
        assertTrue(a.endsWith("The numbers are in the chat."), a)
    }

    @Test fun tracks() {
        assertEquals(ArmWeek.Track.NO_TRADES, ArmWeek.track(fresh, sunday))
        assertEquals(ArmWeek.Track.TOO_EARLY, ArmWeek.track(liq, sunday))
        assertEquals(ArmWeek.Track.TOO_EARLY, ArmWeek.track(orb, sunday))
        // Trades before 13 Oct never count.
        assertEquals(ArmWeek.Track.NO_TRADES, ArmWeek.track(ArmWeek.Arm("ORB", true, List(80) { t(LocalDate.of(2026, 10, 9), 500.0) }), sunday))
        // ORB far above its losing backtest after 30 trades: check the fills.
        assertEquals(ArmWeek.Track.ABOVE, ArmWeek.track(ArmWeek.Arm("ORB", true, List(30) { t(monday, 500.0) }), sunday))
        val late = LocalDate.of(2027, 1, 29)
        assertEquals(ArmWeek.Track.PASSED, ArmWeek.track(ArmWeek.Arm("Liquidity 15+5 BANKNIFTY", true, List(60) { t(monday, if (it % 2 == 0) 1_000.0 else -500.0) }), late))
        assertEquals(ArmWeek.Track.WITHIN, ArmWeek.track(ArmWeek.Arm("ORB", true, List(60) { t(monday, if (it % 2 == 0) 300.0 else -400.0) }), late))
        assertEquals(ArmWeek.Track.NO_BACKTEST, ArmWeek.track(ArmWeek.Arm("Pine #3", true, List(60) { t(monday, 300.0) }), late))
    }

    @Test fun nothingArmedAndNoTradesSaysNothing() {
        assertNull(ArmWeek.say(listOf(sweep), sunday))
        assertNull(ArmWeek.say(emptyList(), sunday))
        val none = ArmWeek.say(listOf(fresh), sunday)!!
        assertTrue(none.chat.contains("No arm has passed its paper test."), none.chat)
    }
}
