package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArmWeekTest {
    private val sunday = LocalDate.of(2026, 10, 4)
    private val monday = LocalDate.of(2026, 9, 28)
    private fun t(day: LocalDate, net: Double) = ArmWeek.Trade(day, net)

    /** ORB: 15 earlier trades, 10 lost (-Rs 977), and 2 this week; Liquidity 15+5: 6 trades, up; ORB Fresh armed, none. */
    private val orb = ArmWeek.Arm("ORB", true, List(10) { t(monday.minusDays(10), -200.0) } + List(5) { t(monday.minusDays(9), 204.6) } +
        listOf(t(monday, -150.0), t(monday.plusDays(2), 90.0)))
    private val liq = ArmWeek.Arm("Liquidity 15+5", true, listOf(t(monday.minusDays(7), 900.0), t(monday.minusDays(6), -300.0),
        t(monday, 1_200.0), t(monday.plusDays(1), -200.0), t(monday.plusDays(3), 800.0), t(monday.plusDays(4), 400.0)))
    private val fresh = ArmWeek.Arm("ORB Fresh", true, emptyList())
    private val sweep = ArmWeek.Arm("ORB Sweep", false, emptyList())

    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|better switched off|consider)\\b")

    @Test fun theWeekIsMondayToSundayAndOnSundayItIsTheWeekJustEnded() {
        assertEquals(monday to sunday, ArmWeek.weekOf(sunday))
        assertEquals(monday to sunday, ArmWeek.weekOf(LocalDate.of(2026, 10, 2)))
    }

    @Test fun eachArmsWeekBesideItsTestAndItsPaperTest() {
        val s = ArmWeek.say(listOf(orb, liq, fresh, sweep), sunday)!!
        val c = s.chat
        assertTrue(c.startsWith("Boss, the arms' paper week (Mon 28 Sep to Sun 4 Oct):"), c)
        assertTrue(c.contains("- Liquidity 15+5 (armed): 4 trades, 3 won, +Rs 2,200; its two-year test averaged"), c)
        assertTrue(c.contains("(this week at or above it); paper test 6 of 15, +Rs 2,800 so far: on track to pass."), c)
        assertTrue(c.contains("- ORB (armed): 2 trades, 1 won, -Rs 60; its two-year test averaged -Rs 1,411 a week a lot (this week at or above it); it failed its paper test (17 trades, -Rs 1,037)."), c)
        assertTrue(c.contains("- ORB Fresh (armed): no paper trades this week"), c)
        assertTrue(c.contains("its paper test has not started (0 of 15 trades)"), c)
        // An arm that is off and never traded on paper is left out.
        assertFalse(c.contains("ORB Sweep"), c)
        // The best armed week first.
        assertTrue(c.indexOf("- Liquidity") < c.indexOf("- ORB (armed)"), c)
        assertTrue(c.contains("On track to pass its 15-trade paper test: Liquidity 15+5 (6 of 15)."), c)
        assertTrue(c.endsWith(ArmWeek.NOTE), c)
        assertFalse(ADVICE.containsMatchIn(c), c)
    }

    @Test fun aloudHasNoAmount() {
        val a = ArmWeek.say(listOf(orb, liq, fresh), sunday)!!.aloud
        assertTrue(a.startsWith("Boss, your arms' paper week. "), a)
        assertTrue(a.contains("Liquidity 15+5, 4 trades, 3 won, up for the week"), a)
        assertTrue(a.contains("ORB, 2 trades, 1 won, down for the week"), a)
        assertTrue(a.contains("ORB Fresh, no trades"), a)
        assertTrue(a.contains("On track for its paper test: Liquidity 15+5, 6 of 15."), a)
        assertFalse(a.contains("Rs"), a)
        assertTrue(a.endsWith("The numbers are in the chat."), a)
    }

    @Test fun tracks() {
        assertEquals(ArmWeek.Track.NO_TRADES, ArmWeek.track(fresh))
        assertEquals(ArmWeek.Track.FAILED, ArmWeek.track(orb))
        assertEquals(ArmWeek.Track.ON_TRACK, ArmWeek.track(liq))
        assertEquals(ArmWeek.Track.BEHIND, ArmWeek.track(ArmWeek.Arm("Range Fade", true, listOf(t(monday, -100.0), t(monday, 40.0)))))
        // Up, but the losses are too close to the gains: not convincingly.
        assertEquals(ArmWeek.Track.NOT_YET, ArmWeek.track(ArmWeek.Arm("Range Fade", true, listOf(t(monday, 100.0), t(monday, -95.0)))))
        val held = ArmWeek.Arm("Liquidity 15+5", true, List(12) { t(monday, 300.0) } + List(4) { t(monday, -100.0) })
        assertEquals(ArmWeek.Track.HELD_UP, ArmWeek.track(held))
    }

    @Test fun nothingArmedAndNoTradesSaysNothing() {
        assertNull(ArmWeek.say(listOf(sweep), sunday))
        assertNull(ArmWeek.say(emptyList(), sunday))
        val none = ArmWeek.say(listOf(fresh), sunday)!!
        assertTrue(none.chat.contains("No arm is on track to pass its 15-trade paper test now."), none.chat)
    }
}
