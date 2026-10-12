package com.optionslab.engine.orb

import com.optionslab.engine.NseHours
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R14's re-entry cap: fewer same-side entries a day, never a different exit. */
class ReentryCapTest {
    private val day = LocalDate.of(2026, 9, 1)
    private fun at(h: Int, m: Int) = LocalDateTime.of(day, LocalTime.of(h, m))

    private fun session(vararg after: Double): List<Bar> {
        val out = ArrayList<Bar>()
        var t = at(9, 15)
        while (!t.toLocalTime().isAfter(LocalTime.of(10, 0))) { out += Bar(t, 55100.0, 55200.0, 55000.0, 55100.0); t = t.plusMinutes(5) }
        for (c in after) { out += Bar(t, c, c, c, c); t = t.plusMinutes(5) }
        return out
    }

    @Test fun refusalTakenAndWords() {
        assertNull(ReentryCap.refusal(1, 5, 0), "0: no cap")
        assertNull(ReentryCap.refusal(1, 0, 1))
        assertNull(ReentryCap.refusal(0, 3, 1), "no direction, nothing to refuse")
        assertEquals("side_cap_reached:CE:1:1", ReentryCap.refusal(1, 1, 1))
        assertEquals("side_cap_reached:PE:2:2", ReentryCap.refusal(-1, 2, 2))
        assertEquals(mapOf(1 to 2, -1 to 1), ReentryCap.taken(listOf("CE", "PE", "CE", "XX")))
        assertTrue(ReentryCap.isCap("side_cap_reached:CE:1:1")); assertTrue(ReentryCap.isCap(ReentryCap.REASON)); assertFalse(ReentryCap.isCap("break"))
        assertEquals("Not re-entering CE: 1 of 1 entry on that side used today; saves charges (research R14: re-entries made about Rs 0 before charges).",
            ReentryCap.words("side_cap_reached:CE:1:1"))
        assertTrue(ReentryCap.words("side_cap_reached:PE:2:2").startsWith("Not re-entering PE: 2 of 2 entries on that side used today"))
        assertTrue(ReentryCap.words(ReentryCap.REASON).startsWith("Not re-entering this side: today's entries on that side used"))
        assertEquals("Charges ₹212 today · ₹4,310 this month (58 orders)", ReentryCap.chargesWords(212.0, 4310.0, 58))
        assertEquals("Charges ₹0 today · ₹75 this month (1 order)", ReentryCap.chargesWords(0.0, 75.0, 1))
        assertNull(ReentryCap.chargesWords(0.0, 0.0, 0))
        assertEquals(setOf("orb", "orb_fresh"), ReentryCap.ARMS)
        assertEquals(1, ReentryCap.PAPER_CAP)
        assertEquals("PE", ReentryCap.right(-1))
    }

    @Test fun theSignalRefusesAUsedSideOnly() {
        val rng = 55200.0 to 55000.0
        val up = session(55250.0)
        val capped = OrbParams.ORB.copy(sideCap = 1)
        assertEquals(1 to "break", OrbRules.entrySignal(up, rng, OrbRules.ORB, null, p = capped))
        assertEquals(0 to "side_cap_reached:CE:1:1", OrbRules.entrySignal(up, rng, OrbRules.ORB, null, p = capped, taken = mapOf(1 to 1)))
        // The other side is untouched.
        assertEquals(-1 to "break", OrbRules.entrySignal(session(54900.0), rng, OrbRules.ORB, null, p = capped, taken = mapOf(1 to 1)))
        // No cap (the default): as before, whatever was taken.
        assertEquals(1 to "break", OrbRules.entrySignal(up, rng, OrbRules.ORB, null, taken = mapOf(1 to 9)))
        assertEquals(1 to "break", OrbRules.entrySignal(up, rng, OrbRules.ORB, null, p = OrbParams.ORB.copy(sideCap = 2), taken = mapOf(1 to 1)))
        // Inside the range or not fresh: the old reasons win (the cap is only said for a real break).
        assertEquals(0 to "inside_range", OrbRules.entrySignal(session(55100.0), rng, OrbRules.ORB, null, p = capped, taken = mapOf(1 to 1)))
        assertEquals(0 to "not_a_fresh_break", OrbRules.entrySignal(session(55250.0, 55300.0), rng, OrbRules.ORB_FRESH, null,
            p = capped, taken = mapOf(1 to 1)))
    }

    // ---- the replays: a day that breaks up at 10:05 and stays up, whose CE runs +40 every 30 minutes --------------------

    private val all = 9 * 60 + 15..15 * 60 + 29
    private fun series(right: Right, strike: Double, px: (Int) -> Double, hi: (Int) -> Double = px): Series {
        val m = all.toList().toIntArray()
        return Series(if (right == Right.IX) null else day.plusDays(1), strike, right, 30, m, DoubleArray(m.size) { px(m[it]) },
            DoubleArray(m.size) { px(m[it]) }, DoubleArray(m.size) { hi(m[it]) }, DoubleArray(m.size) { px(m[it]) }, null, LongArray(m.size))
    }
    private val ix = series(Right.IX, 0.0, { m -> if (m < 605) 53_800.0 + (m % 3) * 100 else 54_200.0 })
    private val pe = series(Right.PE, 54_000.0, { 200.0 })
    /** The CE at 300, its minute high +45 on every minute :25 / :55 after 10:10 (a +40 target each time). */
    private val ce = series(Right.CE, 54_000.0, { 300.0 }, hi = { m -> if (m > 610 && m % 30 == 25) 345.0 else 300.0 })

    @Test fun theBacktestTakesOneEntryASideUnderTheCap() {
        val s = Session(day, 30, listOf(ix, pe, ce))
        val free = ArmsBacktest.arm(s, OrbRules.ORB, slip = 0.0, charges = 0.0)!!
        val one = ArmsBacktest.arm(s, OrbRules.ORB, OrbParams.ORB.copy(sideCap = 1), slip = 0.0, charges = 0.0)!!
        val two = ArmsBacktest.arm(s, OrbRules.ORB, OrbParams.ORB.copy(sideCap = 2), slip = 0.0, charges = 0.0)!!
        assertTrue(free.size > 2, "without a cap the ORB re-enters the CE after each target (${free.size})")
        assertTrue(free.all { it.right == "CE" })
        assertEquals(1, one.size); assertEquals(2, two.size)
        // The cap never changes an exit: the trades it keeps are the uncapped ones, exit for exit.
        assertEquals(free.first(), one.single())
        assertEquals(free.take(2), two)
        assertEquals("target", one.single().why)
    }

    @Test fun theCapKeepsThe1510SquareOffAndTheExpiryExit() {
        // A CE that drifts all day: one trade to the 15:10 square-off with or without the cap.
        val drift = series(Right.CE, 54_000.0, { m -> 300.0 + (m % 2) })
        val s = Session(day, 30, listOf(ix, pe, drift))
        val free = ArmsBacktest.arm(s, OrbRules.ORB, slip = 0.0, charges = 0.0)!!
        val one = ArmsBacktest.arm(s, OrbRules.ORB, OrbParams.ORB.copy(sideCap = 1), slip = 0.0, charges = 0.0)!!
        assertEquals(free, one)
        assertEquals("session_end", one.single().why)
        assertEquals(LocalTime.of(15, 10), one.single().exitTime!!.toLocalTime())
        // The expiry day's 15:05 exit is the session's own rule, untouched by the cap.
        assertEquals(15 * 60 + 5, NseHours.squareOffMinute(day, 15 * 60 + 10, true))
        assertEquals("session_end", OrbRules.exitReasonFor(OrbRules.ORB, 300.0, 301.0, at(15, 10), OrbParams.ORB.copy(sideCap = 1)))
    }

    @Test fun theReplayHonoursTheCap() {
        val index = session(55300.0, 55300.0, 55300.0, 55300.0, 55300.0, 55300.0, 55300.0, 55300.0)
        val flat = index.map { Bar(it.start, 300.0, 300.0, 300.0, 300.0) }
        // The CE reaches +40 on every other bar after 10:10: target, re-entry, target...
        val ce = index.mapIndexed { i, b -> if (i >= 11 && i % 2 == 0) Bar(b.start, 300.0, 345.0, 299.0, 340.0) else Bar(b.start, 300.0, 300.0, 300.0, 300.0) }
        val free = Replay.day(OrbRules.ORB, index, ce, flat)
        val one = Replay.day(OrbRules.ORB, index, ce, flat, sideCap = 1)
        assertTrue(free.size >= 2, "re-entries without the cap (${free.size})")
        assertEquals(listOf(free.first()), one)
        assertEquals("target", one.single().why)
    }
}
