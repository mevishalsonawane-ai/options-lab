package com.optionslab.engine.signals

import com.optionslab.engine.Right
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.Watchlist
import com.optionslab.engine.signals.CallEval.Setup
import com.optionslab.engine.signals.CallEval.Status
import com.optionslab.engine.signals.CallEval.Variant
import com.optionslab.engine.signals.CallParser.Trig
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shadow evaluation of a call ([CallEval]) on made-up minute bars: triggered and each exit variant (T1, T2, T3, as given,
 * with a stop - the given one and "our stop"), not triggered, already above the entry, the reaction delay, zone and market
 * entries, a missing stop, the expiry-day square-off, a holiday, a message after the square-off, no data, stale prices and a
 * gap, a restart mid-call, the pre-call move and the random-entry twins.
 */
class CallEvalTest {
    private val day = LocalDate.of(2026, 10, 15)
    private fun t(h: Int, m: Int, s: Int = 0): LocalDateTime = day.atTime(h, m, s)
    private val close = t(15, 45)

    /** Minute bars 09:15-15:29 from [px] (each minute's open, high, low, close). */
    private fun bars(px: (LocalTime) -> DoubleArray?): List<Bar> {
        val out = ArrayList<Bar>()
        var x = LocalTime.of(9, 15)
        while (x.isBefore(LocalTime.of(15, 30))) { px(x)?.let { out += Bar(day.atTime(x), it[0], it[1], it[2], it[3]) }; x = x.plusMinutes(1) }
        return out
    }
    private fun flat(p: Double) = doubleArrayOf(p, p, p, p)

    /** Flat 270 to 09:28; 09:29 crosses 280; then +6 a minute to 420, then flat. */
    private val rally = bars { x ->
        val m = x.hour * 60 + x.minute - (9 * 60 + 29)
        when {
            m < 0 -> flat(270.0)
            m == 0 -> doubleArrayOf(272.0, 285.0, 270.0, 283.0)
            else -> { val o = minOf(283.0 + 6 * (m - 1), 420.0); val c = minOf(o + 6, 420.0); doubleArrayOf(o, c + 1, o - 1, c) }
        }
    }
    private val idx = bars { x -> flat(73_000.0 + (x.hour * 60 + x.minute - 555) * 2.0) }

    private fun setup(trig: Trig = Trig.ABOVE, trigger: Double? = 280.0, high: Double? = null, stop: Double? = null,
                      targets: List<Double> = listOf(310.0, 345.0, 400.0), at: LocalDateTime = t(9, 28, 4), right: Right = Right.CE,
                      expiryDay: Boolean = false, tradingDay: Boolean = true) =
        Setup(at, "SENSEX", right, trig, trigger, high, stop, targets, 20, expiryDay, tradingDay)

    @Test fun theSampleTriggersAndEachVariantExitsAtItsOwnTarget() {
        val s = setup()
        val r = CallEval.evaluate(s, rally, idx, close)
        assertEquals(Status.DONE, r.status); assertTrue(r.triggered)
        assertEquals(t(9, 29), r.fillAt); assertEquals(280.0, r.fillRaw)
        val fill = Watchlist.paperBuy("SENSEX", 280.0)
        assertEquals(setOf(Variant.T1, Variant.T2, Variant.T3, Variant.GIVEN, Variant.STOPPED), r.legs.keys)
        val t1 = r.legs.getValue(Variant.T1)
        assertEquals(fill, t1.entryFill); assertEquals(310.0, t1.exitFill); assertEquals("target", t1.why)
        assertEquals(CallParser.round2(Watchlist.charge(true, fill, 20) + Watchlist.charge(false, 310.0, 20)), t1.charges)
        assertEquals((310.0 - fill) * 20 - t1.charges, t1.net, 1e-9)
        assertEquals(345.0, r.legs.getValue(Variant.T2).exitRaw)
        assertEquals(400.0, r.legs.getValue(Variant.T3).exitRaw)
        assertEquals(400.0, r.legs.getValue(Variant.GIVEN).exitRaw)
        assertEquals(400.0, r.legs.getValue(Variant.STOPPED).exitRaw)
        assertTrue(r.legs.getValue(Variant.T1).exitAt.isBefore(r.legs.getValue(Variant.T2).exitAt))
        // Before the call: 270, flat, the index rising 0.008% in 3 minutes; not above 280.
        val pre = r.pre!!
        assertEquals(270.0, pre.ltp); assertEquals(0.0, pre.optionMove3); assertFalse(pre.alreadyAbove); assertFalse(pre.movedBefore)
        assertTrue(pre.underlyingMove3!! > 0)
        assertNull(r.note)
        // Twins: the same day and contract at random minutes.
        val tw = CallEval.twins(s, r, rally, "k")
        assertEquals(CallEval.TWINS, tw.size)
        assertEquals(tw, CallEval.twins(s, r, rally, "k"))
    }

    @Test fun notTriggeredWaitingAndNoEntry() {
        val dull = bars { flat(270.0) }
        assertEquals(Status.NOT_TRIGGERED, CallEval.evaluate(setup(), dull, idx, close).status)
        assertEquals(Status.WAITING, CallEval.evaluate(setup(), dull, idx, t(9, 35)).status)
        // Past the window but the prices not in yet (stale): waiting; the day over with none: no data.
        assertEquals(Status.WAITING, CallEval.evaluate(setup(), dull.filter { it.start.isBefore(t(9, 30)) }, idx, t(9, 50)).status)
        val none = CallEval.evaluate(setup(), emptyList(), emptyList(), close)
        assertEquals(Status.NO_DATA, none.status); assertNull(none.pre!!.ltp)
        val ne = CallEval.evaluate(setup(trigger = null), rally, idx, close)
        assertEquals(Status.NO_DATA, ne.status); assertEquals("no entry price", ne.note)
        assertEquals(emptyList(), CallEval.twins(setup(), CallEval.evaluate(setup(), dull, idx, close), dull, "k"))
    }

    @Test fun alreadyAboveTheEntryFillsAtTheNextOpen() {
        val hot = bars { x -> if (x.isBefore(LocalTime.of(9, 25))) flat(270.0) else flat(300.0) }
        val r = CallEval.evaluate(setup(), hot, idx, close)
        assertTrue(r.pre!!.alreadyAbove); assertTrue(r.pre!!.movedBefore)
        assertEquals(11.11, r.pre!!.optionMove3)
        assertEquals(300.0, r.fillRaw); assertEquals(t(9, 29), r.fillAt)
        // Never reached a target: as given (no stop) holds to the 15:15 square-off at that minute's open.
        val g = r.legs.getValue(Variant.GIVEN)
        assertEquals("square-off", g.why); assertEquals(t(15, 15), g.exitAt); assertEquals(Watchlist.paperSell("SENSEX", 300.0), g.exitFill)
    }

    @Test fun theReactionDelayMovesTheFill() {
        val s = setup(at = t(9, 28, 40))
        assertEquals(t(9, 29), CallEval.evaluate(s, rally, idx, close, CallEval.Settings(delaySec = 0)).fillAt)
        assertEquals(t(9, 30), CallEval.evaluate(s, rally, idx, close, CallEval.Settings(delaySec = 60)).let { it.fillAt })
        assertEquals(283.0, CallEval.evaluate(s, rally, idx, close, CallEval.Settings(delaySec = 60)).fillRaw)
        // A window too short to see the cross: not triggered.
        assertEquals(Status.NOT_TRIGGERED, CallEval.evaluate(setup(trigger = 400.0), rally, idx, close, CallEval.Settings(validityMin = 5)).status)
        assertEquals(listOf(0, 30, 60), CallEval.Settings.DELAYS)
    }

    @Test fun stopsGivenAndOurs() {
        // Up to 285, then a slide of 5 a minute.
        val slide = bars { x ->
            val m = x.hour * 60 + x.minute - (9 * 60 + 29)
            when { m < 0 -> flat(270.0); m == 0 -> doubleArrayOf(272.0, 285.0, 270.0, 283.0); else -> { val o = 283.0 - 5 * (m - 1); doubleArrayOf(o, o + 1, o - 5, o - 5) } }
        }
        val r = CallEval.evaluate(setup(stop = 250.0), slide, idx, close)
        val g = r.legs.getValue(Variant.GIVEN)
        assertEquals("stop", g.why); assertEquals(250.0, g.exitRaw); assertTrue(g.net < 0)
        assertEquals(g.exitAt, r.legs.getValue(Variant.STOPPED).exitAt)
        // No stop given: "with a stop" uses ours, 20% under the fill; "as given" rides to the square-off.
        val o = CallEval.evaluate(setup(), slide, idx, close)
        val ours = o.legs.getValue(Variant.STOPPED)
        assertEquals("our stop", ours.why); assertEquals(CallParser.round2(Watchlist.paperBuy("SENSEX", 280.0) * 0.8), ours.exitRaw)
        assertEquals("square-off", o.legs.getValue(Variant.GIVEN).why)
        // A stop already under the open: out at the open (a gap through it).
        val gap = bars { x -> when { x.isBefore(LocalTime.of(9, 29)) -> flat(270.0); x == LocalTime.of(9, 29) -> doubleArrayOf(281.0, 282.0, 280.0, 281.0); else -> flat(200.0) } }
        assertEquals(200.0, CallEval.evaluate(setup(stop = 250.0), gap, idx, close).legs.getValue(Variant.GIVEN).exitRaw)
        // The fill minute itself reaching the stop: stopped there (the worse case first).
        val dip = bars { x -> when { x.isBefore(LocalTime.of(9, 29)) -> flat(270.0); x == LocalTime.of(9, 29) -> doubleArrayOf(270.0, 282.0, 240.0, 281.0); else -> flat(281.0) } }
        assertEquals(t(9, 29), CallEval.evaluate(setup(stop = 250.0), dip, idx, close).legs.getValue(Variant.GIVEN).exitAt)
        // A gap above a target: sold at the open.
        val jump = bars { x -> when { x.isBefore(LocalTime.of(9, 29)) -> flat(270.0); x == LocalTime.of(9, 29) -> doubleArrayOf(281.0, 282.0, 280.0, 281.0); else -> flat(500.0) } }
        assertEquals(500.0, CallEval.evaluate(setup(), jump, idx, close).legs.getValue(Variant.T1).exitRaw)
    }

    @Test fun theExpiryDaySquaresOffAt1505() {
        val flatDay = bars { x -> if (x.isBefore(LocalTime.of(9, 29))) flat(270.0) else flat(290.0) }
        val e = CallEval.evaluate(setup(expiryDay = true), flatDay, idx, close)
        assertEquals(t(15, 5), e.legs.getValue(Variant.GIVEN).exitAt)
        assertEquals(LocalTime.of(15, 5), CallEval.squareOff(setup(expiryDay = true)))
        assertEquals(t(15, 15), CallEval.evaluate(setup(), flatDay, idx, close).legs.getValue(Variant.GIVEN).exitAt)
        // A call after the square-off, on a holiday: not tested.
        assertEquals(Status.CLOSED, CallEval.evaluate(setup(at = t(15, 10), expiryDay = true), flatDay, idx, close).status)
        val h = CallEval.evaluate(setup(tradingDay = false), flatDay, idx, close)
        assertEquals(Status.CLOSED, h.status); assertEquals("the market was closed that day", h.note)
        // Before the open: from 09:15.
        assertEquals(t(9, 16), CallEval.evaluate(setup(at = t(8, 55), trigger = 285.0), flatDay.map { if (it.start.toLocalTime() < LocalTime.of(9, 29)) it.copy(high = 290.0) else it }, idx, close).fillAt)
    }

    @Test fun zoneAndMarketEntries() {
        val drop = bars { x -> val m = x.hour * 60 + x.minute - (9 * 60 + 29); if (m < 0) flat(320.0) else { val o = 320.0 - 5 * m; doubleArrayOf(o, o, o - 5, o - 5) } }
        // "near 280": falls into the zone (top 285.6).
        val z = CallEval.evaluate(setup(trig = Trig.ZONE), drop, idx, close)
        assertEquals(285.6, z.fillRaw!!, 1e-9); assertTrue(z.pre!!.alreadyAbove)
        // Range 300-305 entered from below.
        val up = bars { x -> val m = x.hour * 60 + x.minute - (9 * 60 + 29); if (m < 0) flat(280.0) else { val o = 280.0 + 5 * m; doubleArrayOf(o, o + 5, o, o + 5) } }
        assertEquals(294.0, CallEval.evaluate(setup(trig = Trig.ZONE, trigger = 300.0, high = 305.0), up, idx, close).fillRaw!!, 1e-9)
        // Opening inside the zone: at the open.
        assertEquals(280.0, CallEval.evaluate(setup(trig = Trig.ZONE, trigger = 280.0), bars { flat(280.0) }, idx, close).fillRaw)
        assertEquals(Status.NOT_TRIGGERED, CallEval.evaluate(setup(trig = Trig.ZONE, trigger = 100.0), bars { flat(280.0) }, idx, close).status)
        // At market: the next open, whatever it is.
        val c = CallEval.evaluate(setup(trig = Trig.CMP, trigger = null), rally, idx, close)
        assertEquals(272.0, c.fillRaw); assertFalse(c.pre!!.alreadyAbove)
    }

    @Test fun staleGapsAndARestartMidCall() {
        // At 09:45 only T1 and T2 are out: open.
        val mid = CallEval.evaluate(setup(), rally, idx, t(9, 45))
        assertEquals(Status.OPEN, mid.status); assertEquals(setOf(Variant.T1, Variant.T2), mid.legs.keys)
        // A restart reads the same bars again: the same legs, then the rest.
        val later = CallEval.evaluate(setup(), rally, idx, close)
        assertEquals(mid.legs.getValue(Variant.T1), later.legs.getValue(Variant.T1))
        assertEquals(Status.DONE, later.status)
        // Prices stop at 10:00 for the day: out at the last price, with the gap noted when bars are missing inside.
        val cut = rally.filter { it.start.isBefore(t(9, 40)) || (it.start.isAfter(t(10, 0)) && it.start.isBefore(t(10, 5))) }
        val r = CallEval.evaluate(setup(targets = listOf(1000.0)), cut, idx, close)
        val g = r.legs.getValue(Variant.GIVEN)
        assertEquals("last price", g.why); assertEquals(t(10, 5), g.exitAt)
        assertEquals("prices missing for 21 minutes inside the trade", r.note)
    }

    @Test fun thePreCallMoveIsInTheCallsDirection() {
        val falling = bars { x -> flat(73_000.0 - (x.hour * 60 + x.minute - 555) * 40.0) }
        val pe = CallEval.pre(setup(right = Right.PE), rally, falling)
        assertTrue(pe.underlyingMove3!! > CallEval.PUMP_UNDERLYING_PCT); assertTrue(pe.movedBefore)
        val ce = CallEval.pre(setup(), rally, falling)
        assertTrue(ce.underlyingMove3!! < 0); assertFalse(ce.movedBefore)
        assertNull(CallEval.pre(setup(at = t(9, 15, 30)), rally, falling).ltp)
        assertEquals(Status.DONE, Status.of("done")); assertNull(Status.of("x")); assertEquals(Variant.T2, Variant.of("t2")); assertNull(Variant.of("q"))
    }
}
