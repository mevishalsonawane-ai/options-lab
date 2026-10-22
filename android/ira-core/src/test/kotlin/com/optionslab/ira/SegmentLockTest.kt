package com.optionslab.ira

import com.optionslab.engine.risk.DayLock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import java.time.LocalDate
import java.time.LocalTime

/** Boss, 21 Oct: "Yes, give commodities their own day lock" ([SegmentLock]): the paper split, its stops and its words. */
class SegmentLockTest {
    private val day = LocalDate.of(2026, 10, 21)
    private val seg = SegmentLock

    @Test fun thePaperDayIsSplitByExchangeAfterCharges() {
        // NSE +11,514 (R52 MIDCPNIFTY), MCX -1,200 open: the whole paper day is +10,054 after 260 of charges.
        val rows = listOf("NFO" to 11_700.0, "MCX" to -1_100.0, "mcx " to -50.0)
        val charges = listOf("NFO" to 186.0, "MCX" to 50.0, "MCX" to 24.0)
        val total = 11_700.0 - 1_150.0 - 260.0
        val s = seg.split(total, rows, charges)!!
        assertEquals(-1_224.0, s.mcx, 1e-9)
        assertEquals(11_514.0, s.nse, 1e-9)
        assertEquals(total, s.nse + s.mcx, 1e-9)
        // Nothing on MCX: all NSE. Unknown: none.
        assertEquals(0.0, seg.split(500.0, listOf("NSE" to 500.0), emptyList())!!.mcx, 0.0)
        assertNull(seg.split(null, rows, charges))
        assertNull(seg.split(Double.NaN, rows, charges))
        assertNull(seg.split(1.0, listOf("MCX" to Double.POSITIVE_INFINITY), emptyList()))
        assertTrue(seg.isMcx("MCX")); assertFalse(seg.isMcx("NFO")); assertFalse(seg.isMcx(null))
    }

    @Test fun nseLockReachedWhileMcxStillTradesAndTheReverse() {
        // The 21 Oct day: NSE +11,514 locks the NSE paper side only; the MCX lock reads MCX's own -1,224.
        val (nse, mcx) = seg.lockFigures(true, 10_290.0, SegmentLock.Split(11_514.0, -1_224.0))
        assertEquals(11_514.0, nse!!, 0.0)
        assertEquals(-1_224.0, mcx!!, 0.0)
        assertTrue(DayLock.next(null, day, LocalTime.of(11, 0), 8_000.0, nse) != null)
        assertNull(DayLock.next(null, day, LocalTime.of(11, 0), 8_000.0, mcx))
        // The reverse: MCX +9,000 in the evening locks MCX, NSE (-500) trades on.
        val (n2, m2) = seg.lockFigures(true, 8_500.0, SegmentLock.Split(-500.0, 9_000.0))
        assertNull(DayLock.next(null, day, LocalTime.of(19, 0), 8_000.0, n2))
        assertTrue(DayLock.next(null, day, LocalTime.of(19, 0), 8_000.0, m2) != null)
        // The split off: the one whole figure, as before, and no MCX lock.
        assertEquals(10_290.0 to null, seg.lockFigures(false, 10_290.0, SegmentLock.Split(11_514.0, -1_224.0)))
        // The split not read (no snapshot): the whole figure is the safer NSE figure, and the MCX lock is not checked.
        assertEquals(10_290.0 to null, seg.lockFigures(true, 10_290.0, null))
    }

    @Test fun aLossInOneSegmentLeavesTheOtherTrading() {
        // NSE -7,000 past the 6,000 paper limit; MCX +500: NSE stops, MCX does not.
        val l = seg.losses(true, null, 2_000.0, -6_500.0, 6_000.0, SegmentLock.Split(-7_000.0, 500.0), 6_000.0)
        assertEquals(SegmentLock.Trip.PAPER, l.nse!!.trip)
        assertEquals("Paper NSE", l.nse!!.who)
        assertNull(l.mcx)
        assertTrue(l.wholePaper)
        assertFalse(seg.mcxStopped(true, DayStop.Why.LOSS, false, true, SegmentLock.Trip.PAPER, false))
        // MCX -6,200; NSE +3,000: MCX stops alone; the NSE side is not hit and nothing stops NSE arms.
        val r = seg.losses(true, null, 2_000.0, -3_200.0, 6_000.0, SegmentLock.Split(3_000.0, -6_200.0), 6_000.0)
        assertNull(r.nse)
        assertEquals("Commodities paper", r.mcx!!.who)
        assertFalse(r.wholePaper)
        assertTrue(seg.mcxStopped(true, null, false, false, null, mcxTripped = true))
        // MCX's own limit off (0): never hit.
        assertNull(seg.losses(true, null, 2_000.0, -3_200.0, 6_000.0, SegmentLock.Split(3_000.0, -60_000.0), 0.0).mcx)
    }

    @Test fun theSplitOffIsExactlyTheSharedRule() {
        val l = seg.losses(false, null, 2_000.0, -6_100.0, 6_000.0, SegmentLock.Split(500.0, -6_600.0), 6_000.0)
        assertEquals("Paper", l.nse!!.who)
        assertEquals(-6_100.0, l.nse!!.pnl, 0.0)
        assertNull(l.mcx)
        assertFalse(l.wholePaper)
        // Any day stop or loss stop stops MCX too.
        assertTrue(seg.mcxStopped(false, DayStop.Why.LOSS, false, true, SegmentLock.Trip.PAPER, false))
        assertTrue(seg.mcxStopped(false, null, false, true, null, false))
        assertFalse(seg.mcxStopped(false, null, false, false, null, false))
        // An MCX loss stop made while the split was on still holds after it is switched off (never lifted by the switch).
        assertTrue(seg.mcxStopped(false, null, false, false, null, mcxTripped = true))
    }

    @Test fun bossStopAndTheTileStopBoth() {
        for (why in listOf(DayStop.Why.BOSS, DayStop.Why.TILE)) assertTrue(seg.mcxStopped(true, why, false, false, null, false))
        // Boss stopped after the NSE paper loss stop (the day keeps LOSS as its reason): his own stop is kept apart and holds.
        assertTrue(seg.mcxStopped(true, DayStop.Why.LOSS, bossStop = true, nseTripped = true, nseTrip = SegmentLock.Trip.PAPER, mcxTripped = false))
    }

    @Test fun zerodhasLossLimitStopsBothAndAnUnknownLossStopIsTheSafeSide() {
        val l = seg.losses(true, -2_500.0, 2_000.0, 4_000.0, 6_000.0, SegmentLock.Split(3_000.0, 1_000.0), 6_000.0)
        assertEquals(SegmentLock.Trip.LIVE, l.nse!!.trip)
        assertTrue(seg.mcxStopped(true, DayStop.Why.LOSS, false, true, SegmentLock.Trip.LIVE, false))
        // A loss stop whose account was not kept (an older build, a crash between writes): both stop.
        assertTrue(seg.mcxStopped(true, DayStop.Why.LOSS, false, true, null, false))
        assertTrue(seg.mcxStopped(true, DayStop.Why.LOSS, false, false, null, false))
        assertTrue(seg.mcxStopped(true, null, false, true, null, false))
    }

    @Test fun theLiveDoorIsNeverWeakerThanOnePaperFigure() {
        assertTrue(seg.liveDoorTripped(nseTripped = true, mcxTripped = false, wholePaper = false))
        assertTrue(seg.liveDoorTripped(false, true, false))
        // NSE -3,500 and MCX -3,000: neither side's 6,000 limit, but the whole paper day (-6,500) is: the live door still refuses.
        val l = seg.losses(true, null, 2_000.0, -6_500.0, 6_000.0, SegmentLock.Split(-3_500.0, -3_000.0), 6_000.0)
        assertNull(l.nse); assertNull(l.mcx); assertTrue(l.wholePaper)
        assertTrue(seg.liveDoorTripped(false, false, l.wholePaper))
        assertFalse(seg.liveDoorTripped(false, false, false))
    }

    @Test fun theTripIsKeptForItsDayOnly() {
        val kept = seg.encodeTrip(day, SegmentLock.Trip.PAPER)
        assertEquals("2026-10-21|paper", kept)
        assertEquals(SegmentLock.Trip.PAPER, seg.decodeTrip(kept, day))
        assertNull(seg.decodeTrip(kept, day.plusDays(1)), "the next day forgets it")
        assertNull(seg.decodeTrip("2026-10-21|bogus", day))
        assertNull(seg.decodeTrip("", day)); assertNull(seg.decodeTrip(null, day))
        assertEquals(SegmentLock.Trip.LIVE, seg.decodeTrip(seg.encodeTrip(day, SegmentLock.Trip.LIVE), day))
        // The MCX loss stop is kept in the day lock's own shape (a negative P&L, a positive limit) and read back.
        val r = seg.lossReached(day, LocalTime.of(19, 12, 40), -6_210.0, 6_000.0)
        assertEquals(r, DayLock.decode(DayLock.encode(r)))
    }

    @Test fun theLinesSayEachSegmentPlainlyWithTheAmountAndTheTime() {
        // 22 Oct audit: each line names the P&L that reached the limit and the limit, signed (before: the limit alone, as if the P&L).
        val r = DayLock.Reached(day, LocalTime.of(18, 5), 8_400.0, 8_000.0)
        assertEquals("Commodities day lock reached +Rs 8,400 (limit +Rs 8,000) at 18:05: no new MCX entries today", seg.lockLine(r, SegmentLock.Side.MCX))
        assertEquals("Day lock reached (paper NSE) +Rs 8,400 (limit +Rs 8,000) at 18:05: no new NSE entries today", seg.lockLine(r, SegmentLock.Side.PAPER_NSE))
        assertEquals("Day lock reached (paper) +Rs 8,400 (limit +Rs 8,000) at 18:05: no new entries today", seg.lockLine(r, SegmentLock.Side.PAPER))
        assertEquals("Day lock reached (Zerodha) +Rs 8,400 (limit +Rs 8,000) at 18:05: no new entries today", seg.lockLine(r, SegmentLock.Side.ZERODHA))
        assertTrue(seg.mcxLockRefusal(r).startsWith("Commodities day lock reached +Rs 8,400 (limit +Rs 8,000) at 18:05"))
        assertTrue(seg.mcxLockRefusal(r).endsWith("(open positions keep their own exits)"))
        // The 21 Oct day: R52 MIDCPNIFTY +11,514 at 11:00 reached the paper NSE lock of 8,000.
        val oct21 = DayLock.Reached(day, LocalTime.of(11, 0), 11_514.0, 8_000.0)
        assertEquals("Day lock reached (paper NSE) +Rs 11,514 (limit +Rs 8,000) at 11:00: no new NSE entries today", seg.lockLine(oct21, SegmentLock.Side.PAPER_NSE))
        val loss = seg.lossReached(day, LocalTime.of(19, 12), -6_210.0, 6_000.0)
        assertEquals("Commodities daily loss limit reached -Rs 6,210 (limit -Rs 6,000) at 19:12: no new MCX entries today; the MCX arms sold what they held", seg.mcxLossLine(loss))
        // The loss line reads the same from the kept record (a restart between the stop and Home's next look).
        assertEquals(seg.mcxLossLine(loss), seg.mcxLossLine(DayLock.decode(DayLock.encode(loss))!!))
        assertEquals("Commodities keep their own paper day lock (+Rs 8,000) and daily loss limit (-Rs 6,000), on the MCX paper day only.",
            seg.idleLine(true, 8_000.0, 6_000.0))
        assertTrue(seg.idleLine(true, 0.0, 0.0).contains("day lock (off) and daily loss limit (off)"))
        assertTrue(seg.idleLine(false, 8_000.0, 6_000.0).startsWith("Commodities share"))
    }

    /** Review, 21 Oct: the shared rule's stop stops both, even if the split is switched on later; live keeps one figure. */
    @Test fun theSharedStopStopsBothAndLiveKeepsOneFigure() {
        val l = seg.losses(false, null, 2_000.0, -6_100.0, 6_000.0, SegmentLock.Split(500.0, -6_600.0), 6_000.0)
        assertEquals(SegmentLock.Trip.SHARED, l.nse!!.trip)
        // Switched on after a shared stop: MCX stays stopped.
        assertTrue(seg.mcxStopped(true, DayStop.Why.LOSS, false, true, SegmentLock.Trip.SHARED, false))
        // While anything trades live the loss limits keep the one figure.
        assertTrue(seg.lossSplit(splitOn = true, liveActive = false))
        assertFalse(seg.lossSplit(splitOn = true, liveActive = true))
        assertFalse(seg.lossSplit(splitOn = false, liveActive = false))
        // A paper NSE stop is upgraded by a later Zerodha or shared hit; nothing else is.
        assertTrue(seg.upgrade(SegmentLock.Trip.PAPER, SegmentLock.Trip.LIVE))
        assertTrue(seg.upgrade(SegmentLock.Trip.PAPER, SegmentLock.Trip.SHARED))
        assertFalse(seg.upgrade(SegmentLock.Trip.PAPER, SegmentLock.Trip.PAPER))
        assertFalse(seg.upgrade(SegmentLock.Trip.LIVE, SegmentLock.Trip.SHARED))
        assertEquals(SegmentLock.Trip.SHARED, seg.decodeTrip(seg.encodeTrip(day, SegmentLock.Trip.SHARED), day))
    }
}
