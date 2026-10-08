package com.optionslab.engine.mcx

import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The three MCX paper arms' rules (research M3 EVE, M4 Q4, M2 TSM252): ranges, breaks, windows, strikes, exits, signals. */
class McxArmRulesTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val day = d("2026-10-08")
    private val cal = McxSession.DEFAULT

    /** A 1-minute bar of [day] starting at [minute] (minute of the day, IST). */
    private fun bar(minute: Int, close: Double, low: Double = close, high: Double = close, on: LocalDate = day) =
        Upstox.Bar(on.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(IST).toEpochSecond(), close, high, low, close, 10, 0)

    private fun m(h: Int, mm: Int) = h * 60 + mm

    // ---- shared ---------------------------------------------------------------------------------------------------------

    @Test fun strikesAtmAndSteps() {
        val k = listOf(300.0, 290.0, 310.0, 300.0, Double.NaN, -5.0, 320.0)
        assertEquals(listOf(290.0, 300.0, 310.0, 320.0), McxArmRules.strikes(k))
        assertEquals(1, McxArmRules.atmIndex(McxArmRules.strikes(k), 302.0))
        // A tie goes to the lower strike.
        assertEquals(1, McxArmRules.atmIndex(McxArmRules.strikes(k), 305.0))
        assertEquals(3, McxArmRules.atmIndex(McxArmRules.strikes(k), 999.0))
        assertNull(McxArmRules.atmIndex(emptyList(), 300.0))
        assertNull(McxArmRules.atmIndex(listOf(300.0), Double.NaN))
        assertEquals(320.0, McxArmRules.stepFromAtm(k, 302.0, 2))
        assertEquals(290.0, McxArmRules.stepFromAtm(k, 302.0, -1))
        assertNull(McxArmRules.stepFromAtm(k, 302.0, 3), "off the chain")
        assertNull(McxArmRules.stepFromAtm(emptyList(), 302.0, 0))
    }

    @Test fun freshnessAndTradingDaysLeft() {
        assertTrue(McxArmRules.fresh(1_000, 1_000 + 60 + 60))
        assertFalse(McxArmRules.fresh(1_000, 1_000 + 60 + 61))
        // Thu 8 Oct to Tue 27 Oct (SILVERM's October options): 13 trading days (Fri 9 .. Tue 27, Dussehra 20 Oct has an evening).
        assertEquals(13, McxArmRules.tradingDaysLeft(day, d("2026-10-27"), cal))
        assertEquals(0, McxArmRules.tradingDaysLeft(d("2026-10-27"), d("2026-10-27"), cal), "expiry day")
        assertEquals(0, McxArmRules.tradingDaysLeft(d("2026-10-28"), d("2026-10-27"), cal), "past")
        // Gandhi Jayanti (2 Oct, shut) and the weekend do not count.
        assertEquals(0, McxArmRules.tradingDaysLeft(d("2026-10-01"), d("2026-10-03"), cal))
        assertEquals(1, McxArmRules.tradingDaysLeft(d("2026-10-01"), d("2026-10-05"), cal))
    }

    @Test fun refusalsComeInOrder() {
        assertEquals("mcx_shut", McxArmRules.entryRefusal(false, true, true, "x", "y", false))
        assertEquals("kill_switch", McxArmRules.entryRefusal(true, true, true, "x", "y", false))
        assertEquals("stopped_for_today", McxArmRules.entryRefusal(true, false, true, "x", "y", false))
        assertEquals("day_lock: x", McxArmRules.entryRefusal(true, false, false, "x", "y", false))
        assertEquals("expiry_rule: y", McxArmRules.entryRefusal(true, false, false, null, "y", false))
        assertEquals("stale_price", McxArmRules.entryRefusal(true, false, false, null, null, false))
        assertNull(McxArmRules.entryRefusal(true, false, false, null, null, true))
        assertEquals("+Rs 41", McxArmRules.rs(41)); assertEquals("-Rs 1,439", McxArmRules.rs(-1439))
        assertEquals("Not proven — paper only", McxArmRules.NOT_PROVEN)
        assertEquals(1, McxArmRules.LOTS)
    }

    // ---- NATURALGAS evening breakout (M3 EVE) ---------------------------------------------------------------------------

    /** 17:00-18:59: closes between 300 and 310 (high 310 at 17:30, low 300 at 18:10), 120 minutes. */
    private fun rangeBars(): List<Upstox.Bar> = (m(17, 0) until m(19, 0)).map { t ->
        bar(t, when (t) { m(17, 30) -> 310.0; m(18, 10) -> 300.0; else -> 305.0 }, low = 290.0, high = 330.0)
    }

    @Test fun theRangeIsTheCloses1700To1859() {
        val r = McxEveRules.range(rangeBars() + bar(m(16, 59), 400.0) + bar(m(19, 0), 200.0))!!
        // Wicks (290 / 330) and minutes outside 17:00-18:59 do not count.
        assertEquals(310.0, r.high); assertEquals(300.0, r.low)
        assertEquals(10.0, r.width); assertEquals(305.0, r.mid)
        // Fewer than 60 minutes (a Muhurat hour, a late start): no range. A flat range: none either.
        assertNull(McxEveRules.range(rangeBars().take(59)))
        assertEquals(305.0, McxEveRules.range(rangeBars().drop(60))!!.high, "the last 60 minutes are enough")
        assertNull(McxEveRules.range((m(17, 0) until m(19, 0)).map { bar(it, 305.0) }))
    }

    @Test fun theFirstBreakTo2200IsTheSignal() {
        val r = McxEveRules.range(rangeBars())!!
        val inside = (m(19, 0)..m(19, 30)).map { bar(it, 306.0, high = 340.0, low = 280.0) }
        // A wick beyond the range is no break: the close is.
        assertNull(McxEveRules.signal(r, inside))
        val up = McxEveRules.signal(r, inside + bar(m(19, 31), 311.0) + bar(m(19, 40), 299.0))!!
        assertEquals(1, up.side); assertEquals(m(19, 31), up.minute); assertEquals(m(19, 32), up.entryMinute)
        assertEquals(310.0, up.edge); assertEquals(305.0, up.stop); assertEquals(320.0, up.target)
        val down = McxEveRules.signal(r, inside + bar(m(20, 5), 299.5))!!
        assertEquals(-1, down.side); assertEquals(300.0, down.edge); assertEquals(290.0, down.target); assertEquals(305.0, down.stop)
        // A close at 22:00 still counts; 22:01 or before 19:00 does not.
        assertEquals(m(22, 0), McxEveRules.signal(r, listOf(bar(m(22, 0), 320.0)))?.minute)
        assertNull(McxEveRules.signal(r, listOf(bar(m(22, 1), 320.0), bar(m(18, 59), 320.0))))
        // Out of order in the feed: still the first by time.
        assertEquals(m(19, 10), McxEveRules.signal(r, listOf(bar(m(19, 20), 280.0), bar(m(19, 10), 330.0)))?.minute)
    }

    @Test fun theEntryWindowStrikeAndFlatTime() {
        val s = McxEveRules.Signal(1, m(19, 31), 311.0, 310.0, 305.0, 320.0)
        assertFalse(McxEveRules.entryWindow(s, m(19, 31)), "the signal minute's close is not known yet")
        assertTrue(McxEveRules.entryWindow(s, m(19, 32)))
        assertTrue(McxEveRules.entryWindow(s, m(19, 34)))
        assertFalse(McxEveRules.entryWindow(s, m(19, 35)), "too late: the day goes")
        assertFalse(McxEveRules.entryWindow(s, m(19, 32), flatBy = m(19, 32)))
        // 1-ITM: the call one strike below the ATM, the put one above.
        val k = listOf(290.0, 295.0, 300.0, 305.0, 310.0, 315.0)
        assertEquals(305.0, McxEveRules.strike(k, 311.0, 1))
        assertEquals(315.0, McxEveRules.strike(k, 311.0, -1))
        assertNull(McxEveRules.strike(k, 280.0, 1), "no strike below the lowest")
        // Flat by 23:15, or 15 minutes before an earlier end (Muhurat 18:00-19:00).
        assertEquals(m(23, 15), McxEveRules.flatBy(null))
        assertEquals(m(23, 15), McxEveRules.flatBy(McxSession.Window(LocalTime.of(9, 0), LocalTime.of(23, 30))))
        assertEquals(m(18, 45), McxEveRules.flatBy(McxSession.Window(LocalTime.of(18, 0), LocalTime.of(19, 0))))
        assertEquals(85.0, McxEveRules.optionStop(100.0), 1e-9)
    }

    @Test fun exitsOnTheOptionsWickTheFuturesStopAndTargetTimeAndFlat() {
        val call = McxEveRules.Held(1, 100.0, m(19, 32), 305.0, 320.0)
        assertNull(McxEveRules.exit(call, 90.0, 312.0, m(19, 40)))
        // The minute's LOW at the stop exits, whatever the close (the wick).
        assertEquals("option_stop", McxEveRules.exit(call, 85.0, 312.0, m(19, 41)))
        // On a tie with the future's stop, the option's stop goes first.
        assertEquals("option_stop", McxEveRules.exit(call, 84.0, 304.0, m(19, 41)))
        assertEquals("range_mid_stop", McxEveRules.exit(call, 90.0, 305.0, m(19, 41)))
        assertEquals("target", McxEveRules.exit(call, 120.0, 320.0, m(19, 41)))
        assertEquals("target", McxEveRules.exit(call, null, 321.0, m(19, 41)))
        assertNull(McxEveRules.exit(call, null, null, m(21, 31)))
        assertEquals("two_hours", McxEveRules.exit(call, null, null, m(21, 32)))
        val put = McxEveRules.Held(-1, 100.0, m(21, 50), 305.0, 290.0)
        assertNull(McxEveRules.exit(put, 95.0, 300.0, m(22, 0)))
        assertEquals("range_mid_stop", McxEveRules.exit(put, 95.0, 306.0, m(22, 0)))
        assertEquals("target", McxEveRules.exit(put, 95.0, 289.0, m(22, 0)))
        assertEquals("flat_2315", McxEveRules.exit(put, null, null, m(23, 15)))
        assertNull(McxEveRules.exit(put, null, null, m(23, 14)))
        assertTrue(McxEveRules.RECORD.contains("+Rs 41/day") && McxEveRules.RECORD.contains("+Rs 83/day") &&
            McxEveRules.RECORD.contains("not statistically significant") && McxEveRules.RECORD.contains("40,000"))
        assertEquals(listOf(41, 83, 47, 93, 40_000), listOf(McxEveRules.DESIGN_PER_DAY, McxEveRules.HOLDOUT_PER_DAY,
            McxEveRules.DESIGN_PER_TRADE, McxEveRules.HOLDOUT_PER_TRADE, McxEveRules.DRAWDOWN))
    }

    // ---- SILVERM morning call (M4 Q4) -----------------------------------------------------------------------------------

    @Test fun theMorningGridAndOnePositionAtATime() {
        assertNull(McxMorningRules.gridSlot(m(9, 14)))
        assertEquals(m(9, 15), McxMorningRules.gridSlot(m(9, 15)))
        assertEquals(m(9, 15), McxMorningRules.gridSlot(m(9, 17)))
        assertNull(McxMorningRules.gridSlot(m(9, 18)), "past the grace")
        assertEquals(m(13, 45), McxMorningRules.gridSlot(m(13, 46)))
        assertNull(McxMorningRules.gridSlot(m(14, 0)), "the morning is over")
        assertTrue(McxMorningRules.mayEnter(m(9, 15), null))
        assertFalse(McxMorningRules.mayEnter(m(13, 0), m(9, 15)))
        // The research's trades_for: the next entry at the first grid time at or after the previous exit.
        assertTrue(McxMorningRules.mayEnter(m(13, 15), m(9, 15)))
        assertTrue(McxMorningRules.mayEnter(m(13, 45), m(9, 30)))
    }

    @Test fun elevenToTwentyDaysAnOtmTwoCallAndTheLotRule() {
        // SILVERM's November options expire Thu 26 Nov.
        assertTrue(McxMorningRules.dteOk(d("2026-11-06"), d("2026-11-26"), cal))   // 15 left (the Sunday Muhurat hour counts)
        assertFalse(McxMorningRules.dteOk(d("2026-11-12"), d("2026-11-26"), cal))  // 10 left (Guru Nanak 24 Nov trades in the evening)
        assertTrue(McxMorningRules.dteOk(d("2026-11-11"), d("2026-11-26"), cal))   // 11 left
        assertFalse(McxMorningRules.dteOk(d("2026-10-26"), d("2026-11-26"), cal))  // 24 left
        val k = listOf(70_000.0, 70_250.0, 70_500.0, 70_750.0, 71_000.0, 71_250.0)
        assertEquals(70_750.0, McxMorningRules.strike(k, 70_260.0, 2))
        assertEquals(71_000.0, McxMorningRules.strike(k, 70_260.0, 3))
        assertNull(McxMorningRules.strike(k, 71_200.0, 2))
        assertEquals(listOf(2, 3), McxMorningRules.OTM_STEPS)
        // 1 lot (5 kg): Rs 500 to Rs 1 lakh.
        assertTrue(McxMorningRules.premiumOk(100.0, 5))
        assertFalse(McxMorningRules.premiumOk(99.0, 5))
        assertTrue(McxMorningRules.premiumOk(20_000.0, 5))
        assertFalse(McxMorningRules.premiumOk(20_001.0, 5))
        assertFalse(McxMorningRules.premiumOk(Double.NaN, 5))
    }

    @Test fun soldFourHoursLaterOrBeforeAnEarlyClose() {
        assertEquals(m(13, 15), McxMorningRules.exitMinute(m(9, 15), null))
        assertEquals(m(13, 15), McxMorningRules.exitMinute(m(9, 15), McxSession.Window(LocalTime.of(9, 0), LocalTime.of(23, 30))))
        // Budget-day Sunday session 09:00-17:00: a 13:30 entry goes at 16:50.
        assertEquals(m(16, 50), McxMorningRules.exitMinute(m(13, 30), McxSession.Window(LocalTime.of(9, 0), LocalTime.of(17, 0))))
        assertNull(McxMorningRules.exit(m(9, 15), m(13, 14), null))
        assertEquals("four_hours", McxMorningRules.exit(m(9, 15), m(13, 15), null))
        assertTrue(McxMorningRules.RECORD.contains("+Rs 454/trade over 51 trades") && McxMorningRules.RECORD.contains("failed 2 of 4 gates"))
        assertEquals(listOf(454, 51, 2, 4), listOf(McxMorningRules.HOLDOUT_PER_TRADE, McxMorningRules.HOLDOUT_TRADES,
            McxMorningRules.GATES_FAILED, McxMorningRules.GATES))
    }

    // ---- 12-month trend (M2 TSM252) -------------------------------------------------------------------------------------

    /** [n] weekday closes ending [end], rising by [slope] a day from [start]. */
    private fun closes(n: Int, end: LocalDate, start: Double, slope: Double): List<Pair<LocalDate, Double>> {
        val days = ArrayList<LocalDate>()
        var x = end
        while (days.size < n) { if (x.dayOfWeek.value <= 5) days += x; x = x.minusDays(1) }
        return days.reversed().mapIndexed { i, dd -> dd to start + slope * i }
    }

    @Test fun theMonthlySignalIsTheSignOfThe252DayChange() {
        val end = d("2026-09-30")
        assertEquals(1, McxTrendRules.signal(closes(253, end, 100.0, 1.0), end))
        assertEquals(-1, McxTrendRules.signal(closes(300, end, 500.0, -1.0), end))
        assertEquals(0, McxTrendRules.signal(closes(252, end, 100.0, 1.0), end), "too little history")
        assertEquals(0, McxTrendRules.signal(closes(253, end, 100.0, 0.0), end), "no change")
        // Closes after the month end are not seen (the signal is the month end's).
        val rising = closes(253, end, 100.0, 1.0)
        assertEquals(1, McxTrendRules.signal(rising + (d("2026-10-05") to 1.0), end))
        // Duplicate days and unpriced closes are dropped.
        assertEquals(0, McxTrendRules.signal(rising.drop(1) + rising.last() + (d("2026-09-29") to Double.NaN), end))
        // Only the close 252 trading days back counts, not the path: up 12 months although down this month.
        val path = closes(253, end, 100.0, 1.0).toMutableList()
        path[path.size - 1] = path.last().first to 101.0
        assertEquals(1, McxTrendRules.signal(path, end))
        assertEquals("2026-10", McxTrendRules.month(d("2026-10-31")))
    }

    @Test fun theSignalDayIsTheLastTradingDayOfTheMonthBefore() {
        assertEquals(d("2026-09-30"), McxTrendRules.signalDay(d("2026-10-05"), cal))
        // October 2026 ends on a Saturday: Friday 30 Oct.
        assertEquals(d("2026-10-30"), McxTrendRules.signalDay(d("2026-11-02"), cal))
        // December 2026: 31 Dec is a Thursday.
        assertEquals(d("2026-12-31"), McxTrendRules.signalDay(d("2027-01-04"), cal))
    }

    @Test fun rollsFiveTradingDaysBeforeExpiry() {
        val nov = d("2026-11-30")   // a base-metal mini's November future (last business day)
        val dec = d("2026-12-31")
        // Five trading days before Mon 30 Nov: Mon 23 Nov (27, 26, 25, 24 evening, 23).
        assertEquals(d("2026-11-23"), McxTrendRules.rollDay(nov, cal))
        assertFalse(McxTrendRules.rollDue(nov, d("2026-11-20"), cal))
        assertTrue(McxTrendRules.rollDue(nov, d("2026-11-23"), cal))
        assertEquals(nov, McxTrendRules.expiryToHold(listOf(dec, nov), d("2026-11-20"), cal))
        assertEquals(dec, McxTrendRules.expiryToHold(listOf(dec, nov), d("2026-11-23"), cal))
        assertNull(McxTrendRules.expiryToHold(listOf(nov), d("2026-11-23"), cal))
        // A shorter configured roll is respected (the setting), and 0 reads as 1.
        assertEquals(d("2026-11-27"), McxTrendRules.rollDay(nov, cal, McxExpiry.Config(futureExitTradingDays = 0)))
    }

    @Test fun eachLegsStep() {
        val nov = d("2026-11-30"); val dec = d("2026-12-31")
        assertEquals(McxTrendRules.Step(false, 1, "trend_long"), McxTrendRules.step(0, null, 1, nov))
        assertEquals(McxTrendRules.Step(false, -1, "trend_short"), McxTrendRules.step(0, null, -1, nov))
        assertEquals(McxTrendRules.Step(false, 0, "trend_no_signal"), McxTrendRules.step(0, null, 0, nov))
        assertEquals(McxTrendRules.Step(false, 0, "trend_no_contract"), McxTrendRules.step(0, null, 1, null))
        assertEquals(McxTrendRules.Step(false, 0, "trend_hold"), McxTrendRules.step(1, nov, 1, nov))
        assertEquals(McxTrendRules.Step(true, -1, "trend_flip"), McxTrendRules.step(1, nov, -1, nov))
        assertEquals(McxTrendRules.Step(true, 0, "trend_flip"), McxTrendRules.step(1, nov, -1, null))
        assertEquals(McxTrendRules.Step(true, 0, "trend_flat"), McxTrendRules.step(-1, nov, 0, nov))
        assertEquals(McxTrendRules.Step(true, 1, "trend_roll"), McxTrendRules.step(1, nov, 1, dec))
        assertEquals(McxTrendRules.Step(true, 0, "trend_roll"), McxTrendRules.step(-1, nov, -1, null))
        assertEquals(7, McxTrendRules.LEGS.size)
        assertTrue(McxTrendRules.RECORD.contains("needs ₹8–10 lakh; at ₹1 lakh often ruined"))
        assertEquals("needs ₹8–10 lakh; at ₹1 lakh often ruined", McxTrendRules.CAPITAL_WARNING)
        // Every leg is an MCX mini the app lists.
        assertTrue(McxTrendRules.LEGS.all { Mcx.isMcxName(it) })
    }
}
