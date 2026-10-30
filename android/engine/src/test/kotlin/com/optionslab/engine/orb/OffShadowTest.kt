package com.optionslab.engine.orb

import com.optionslab.engine.orb.OffShadow.Exits
import com.optionslab.engine.orb.OffShadow.Look
import com.optionslab.engine.orb.OffShadow.Pos
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only"): the switched-off shadow's rules. */
class OffShadowTest {
    private val day = LocalDate.of(2026, 11, 2)
    private fun at(h: Int, m: Int, d: LocalDate = day): LocalDateTime = d.atTime(h, m)

    private fun pos(exits: Exits = Exits(), entry: Double = 100.0, side: Int = 1, view: Int = 1, qty: Int = 65, exchange: String = "NFO",
                    expiry: LocalDate? = day.plusDays(1), time: LocalDateTime = at(10, 0), mult: Int = 1) =
        Pos("k", "Arm", "k:1", "NIFTY", "NIFTY26N0324000CE", exchange, side, view, qty, 1, mult, 0.05, entry, time, expiry, exits, charges = 30.0)

    @Test fun heldWithNothingDue() {
        assertNull(OffShadow.decide(pos(Exits(stop = 80.0, target = 130.0)), Look(at(11, 0), ltp = 105.0, extreme = 95.0)))
    }

    @Test fun theStopIsJudgedOnTheExtremeToo() {
        val p = pos(Exits(stop = 80.0))
        assertEquals("stop", OffShadow.decide(p, Look(at(11, 0), ltp = 90.0, extreme = 79.5))?.why)
        assertTrue(OffShadow.decide(p, Look(at(11, 0), ltp = 79.0))!!.stop)
        // A short's stop is above.
        val s = pos(Exits(stop = 120.0), side = -1)
        assertEquals("stop", OffShadow.decide(s, Look(at(11, 0), ltp = 110.0, extreme = 121.0))?.why)
        assertNull(OffShadow.decide(s, Look(at(11, 0), ltp = 110.0, extreme = 119.0)))
    }

    @Test fun theIndexStopGoesByTheSignalsSide() {
        val call = pos(Exits(indexStop = 24_000.0), view = 1)
        assertEquals("index_stop", OffShadow.decide(call, Look(at(11, 0), ltp = 90.0, indexLtp = 24_010.0, indexExtreme = 23_999.0))?.why)
        assertNull(OffShadow.decide(call, Look(at(11, 0), ltp = 90.0, indexLtp = 24_010.0, indexExtreme = 24_001.0)))
        val put = pos(Exits(indexStop = 24_000.0), view = -1)
        assertEquals("index_stop", OffShadow.decide(put, Look(at(11, 0), ltp = 90.0, indexLtp = 24_001.0))?.why)
        // No option price: nothing is booked (never at the price paid).
        assertNull(OffShadow.decide(call, Look(at(11, 0), ltp = null, indexLtp = 23_000.0)))
    }

    @Test fun targetAndIndexTarget() {
        assertEquals("target", OffShadow.decide(pos(Exits(target = 130.0)), Look(at(11, 0), ltp = 130.0))?.why)
        assertEquals("index_target", OffShadow.decide(pos(Exits(indexTarget = 24_100.0)), Look(at(11, 0), ltp = 120.0, indexLtp = 24_100.0))?.why)
    }

    @Test fun theProfitLockKeepsSixtyPercentOfTheBestNet() {
        // 65 units: +Rs 1,000 net needs (px - 100) * 65 - 30 - exit cost >= 1000 -> about 115.9.
        val p = pos(Exits(lockAfterRs = 1_000.0, lockKeep = 0.6)).copy(best = 125.0)
        // Best net (125 - 100) * 65 - 30 = 1,595; 60% = 957 -> out when the net is at or under that.
        assertNull(OffShadow.decide(p, Look(at(11, 0), ltp = 120.0)))
        assertEquals("profit_lock", OffShadow.decide(p, Look(at(11, 0), ltp = 115.0))?.why)
        // Never armed below +Rs 1,000.
        val q = pos(Exits(lockAfterRs = 1_000.0)).copy(best = 110.0)
        assertNull(OffShadow.decide(q, Look(at(11, 0), ltp = 101.0)))
    }

    @Test fun theTrailOnlyTightens() {
        val p = pos(Exits(stop = 80.0, trailPct = 0.25))
        assertEquals(90.0, OffShadow.stopAfter(p, 120.0)!!, 1e-9)
        assertEquals(80.0, OffShadow.stopAfter(p, 100.0)!!, 1e-9)
        assertEquals(130.0, OffShadow.bestAfter(p.copy(best = 130.0), 120.0), 1e-9)
        assertEquals(100.0, OffShadow.bestAfter(p, null), 1e-9)
        val s = pos(Exits(stop = 120.0, trailPct = 0.2), side = -1)
        assertEquals(96.0, OffShadow.stopAfter(s, 80.0)!!, 1e-9)
    }

    @Test fun nseSquareOffAndExpiryDayAndTheArmsOwnTime() {
        val p = pos()
        assertNull(OffShadow.decide(p, Look(at(15, 14), ltp = 100.0)))
        assertEquals("square_off", OffShadow.decide(p, Look(at(15, 15), ltp = 100.0))?.why)
        val exp = pos(expiry = day)
        assertEquals("square_off", OffShadow.decide(exp, Look(at(15, 5), ltp = 100.0))?.why)
        assertNull(OffShadow.decide(exp, Look(at(15, 4), ltp = 100.0)))
        val own = pos(Exits(exitAt = LocalTime.of(10, 45)))
        assertEquals("time_exit", OffShadow.decide(own, Look(at(10, 45), ltp = 100.0))?.why)
        // A day later (a missed square-off): out at once.
        assertEquals("square_off", OffShadow.decide(p, Look(at(9, 16, day.plusDays(1)), ltp = 100.0))?.why)
    }

    @Test fun aCarriedTradeWaitsForItsOwnMorning() {
        val night = pos(Exits(carry = true, exitOn = day.plusDays(1), exitAt = LocalTime.of(9, 16)), time = at(15, 20), expiry = day.plusDays(7))
        assertNull(OffShadow.decide(night, Look(at(15, 30), ltp = 100.0)))
        assertNull(OffShadow.decide(night, Look(at(9, 15, day.plusDays(1)), ltp = 100.0)))
        assertEquals("square_off", OffShadow.decide(night, Look(at(9, 16, day.plusDays(1)), ltp = 100.0))?.why)
        // Never into settlement: its expiry day's 15:05.
        val exp = night.copy(expiry = day.plusDays(1), exits = night.exits.copy(exitOn = day.plusDays(3)))
        assertEquals("square_off", OffShadow.decide(exp, Look(at(15, 5, day.plusDays(1)), ltp = 100.0))?.why)
    }

    @Test fun mcxGoesByItsOwnTimeOrTwentyThreeTwenty() {
        val m = pos(exchange = "MCX", time = at(18, 0), expiry = day.plusDays(20))
        assertNull(OffShadow.decide(m, Look(at(23, 19), ltp = 100.0)))
        assertEquals("square_off", OffShadow.decide(m, Look(at(23, 20), ltp = 100.0))?.why)
        val own = pos(Exits(exitAt = LocalTime.of(22, 0)), exchange = "MCX", time = at(18, 0))
        assertEquals("time_exit", OffShadow.decide(own, Look(at(22, 0), ltp = 100.0))?.why)
    }

    @Test fun nothingIsJudgedOffSessionOrOnAClosedTrade() {
        assertNull(OffShadow.decide(pos(Exits(stop = 80.0)), Look(at(11, 0), ltp = 50.0, session = false)))
        // A square-off missed before the close is booked at that day's last price, the same day only (never a carried trade's).
        assertEquals("square_off", OffShadow.decide(pos(), Look(at(15, 45), ltp = 101.0, session = false))?.why)
        assertNull(OffShadow.decide(pos(), Look(at(9, 0, day.plusDays(1)), ltp = 101.0, session = false)))
        assertNull(OffShadow.decide(pos(Exits(carry = true, exitOn = day.plusDays(1))), Look(at(15, 45), ltp = 101.0, session = false)))
        assertNull(OffShadow.decide(pos(Exits(stop = 80.0)).copy(exit = 90.0), Look(at(11, 0), ltp = 50.0)))
    }

    @Test fun netAfterChargesAndTheMcxMultiplier() {
        val p = pos(qty = 65).copy(exit = 110.0, charges = 60.0)
        assertEquals(10.0 * 65 - 60.0, p.net!!, 1e-9)
        val m = pos(exchange = "MCX", qty = 1, mult = 5).copy(exit = 110.0, charges = 10.0)
        assertEquals(10.0 * 5 - 10.0, m.net!!, 1e-9)
        val s = pos(side = -1).copy(exit = 90.0, charges = 0.0)
        assertEquals(10.0 * 65, s.net!!, 1e-9)
    }

    @Test fun aRunawaySignalNeverFloodsTheBook() {
        val many = (1..OffShadow.PER_ARM_DAY).map { pos().copy(ref = "k:$it") }
        assertTrue(OffShadow.refuse("k", day, many)!!.contains("today already"))
        assertNull(OffShadow.refuse("other", day, many))
        assertNull(OffShadow.refuse("k", day.plusDays(1), many.map { it.copy(exit = 100.0) }))
        val open = (1..OffShadow.OPEN_MAX).map { pos().copy(key = "a$it", ref = "a:$it") }
        assertTrue(OffShadow.refuse("new", day, open)!!.contains("open"))
    }

    @Test fun theWordsSayShadowSwitchedOff() {
        assertEquals("shadow (switched off): no would-be trade yet", OffShadow.words(emptyList()))
        val w = OffShadow.words(listOf(pos().copy(exit = 120.0, charges = 40.0), pos().copy(exit = 90.0, charges = 40.0)), open = 1)
        assertTrue(w.startsWith("shadow (switched off): 2 trades"), w)
        assertTrue(w.endsWith("· 1 open"), w)
        assertFalse(OffShadow.OFF_WORDS.contains("paper only"))
    }
}
