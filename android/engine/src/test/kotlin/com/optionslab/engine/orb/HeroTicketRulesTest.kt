package com.optionslab.engine.orb

import com.optionslab.engine.orb.HeroRules.Candidate
import com.optionslab.engine.orb.HeroTicketRules.ExitKind
import com.optionslab.engine.orb.HeroTicketRules.Phase
import com.optionslab.engine.orb.HeroTicketRules.Target
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Hero ₹1,000 ticket" (15 Oct, Boss's choice; it did not pass research R50): its pure rules on synthetic data - the strike
 * within Rs 1,000 for NIFTY's and SENSEX's lots on both sides, a side with no fitting strike skipped, expiry days only (holidays
 * move them), the 12:30 window and its stale price, Boss's 25% trail exactly as R50 measured it, the 2x / 3x targets beside it
 * (a gap through one booked at the target), 15:05, and a restart in the middle of a trade finding the same trail.
 */
class HeroTicketRulesTest {
    private fun t(h: Int, m: Int): LocalTime = LocalTime.of(h, m)
    private fun c(strike: Double, ltp: Double, traded: Boolean = true, ask: Double? = ltp) = Candidate(strike, ltp, ask, traded)

    @Test fun itIsPaperOnlyItsOwnArmAndLabelledBossChoice() {
        val a = HeroTicketRules.ARM
        assertTrue(a.paperOnly)
        assertFalse(a.hero || a.sweep || a.fade || a.liquidity || a.freshOnly)
        assertTrue(a != HeroRules.ARM && a.source != HeroRules.ARM.source && a !in OrbRules.ARMS)
        assertEquals("hero_ticket", HeroTicketRules.SOURCE); assertEquals(HeroTicketRules.SOURCE, a.source)
        assertEquals("Hero ₹1,000 ticket", HeroTicketRules.LABEL); assertEquals(HeroTicketRules.LABEL, a.label)
        assertEquals("Boss's choice - did not pass research (R50): about -Rs 525 per Rs 1,000 on average; jackpots are rare", HeroTicketRules.RECORD)
        assertTrue(HeroTicketRules.RECORD.startsWith(HeroTicketRules.BOSS_CHOICE))
        for (w in listOf("252 tickets", "142 expiry days", "-Rs 14,351", "110 tickets", "+Rs 3,047", "without its best 5"))
            assertTrue(w in HeroTicketRules.MEASURED, w)
        assertTrue("clock only" in HeroTicketRules.SIGNAL_SOURCE && "no chart signal" in HeroTicketRules.SIGNAL_SOURCE)
        for (w in listOf("12:30", "Rs 5-30", "Rs 1,000", "25% trail", "15:05", "Rs 2,000 a day", "paper only")) assertTrue(w in HeroTicketRules.RULES, w)
        assertTrue("own record" in HeroTicketRules.SEPARATE && "26 Oct" in HeroTicketRules.SEPARATE)
        assertEquals(listOf("NIFTY", "SENSEX"), HeroTicketRules.UNDERLYINGS)
        assertEquals(50, HeroTicketRules.strikeStep("NIFTY")); assertEquals(100, HeroTicketRules.strikeStep("SENSEX"))
        assertEquals(1, HeroTicketRules.LOTS); assertEquals(0.25, HeroTicketRules.TRAIL)
        // 15:05 = the app's expiry square-off and the Hero arm's exit.
        assertEquals(HeroRules.EXIT_AT, HeroTicketRules.EXIT_AT)
    }

    // ---- days ------------------------------------------------------------------------------------------------------------

    @Test fun onlyTheIndexsOwnExpiryDayAndNeverAHoliday() {
        val tue = LocalDate.of(2026, 10, 20)
        val niftyExpiries = listOf(tue, tue.plusDays(7))
        assertTrue(HeroTicketRules.tradesToday(tue, true, niftyExpiries))
        // Not an expiry day: nothing (the day before, and a SENSEX-only day for NIFTY).
        assertFalse(HeroTicketRules.tradesToday(tue.minusDays(1), true, niftyExpiries))
        assertFalse(HeroTicketRules.tradesToday(LocalDate.of(2026, 10, 22), true, listOf(tue.plusDays(7))))
        // No master on the phone: never.
        assertFalse(HeroTicketRules.tradesToday(tue, true, emptyList()))
        // A holiday on the usual weekday: the exchange moved the expiry to the day before; the holiday itself never trades,
        // and the moved day does.
        val holiday = LocalDate.of(2026, 11, 10)
        val moved = listOf(holiday.minusDays(1), holiday.plusDays(7))
        assertFalse(HeroTicketRules.tradesToday(holiday, false, moved))
        assertFalse(HeroTicketRules.tradesToday(holiday, false, listOf(holiday)), "a holiday is never a ticket day, whatever a list says")
        assertTrue(HeroTicketRules.tradesToday(holiday.minusDays(1), true, moved))
        assertTrue(HeroTicketRules.isExpiryDay(tue, niftyExpiries))
    }

    @Test fun theWindowIs1230ToBefore1233() {
        assertEquals(Phase.BEFORE, HeroTicketRules.entryPhase(t(12, 29)))
        assertEquals(Phase.OPEN, HeroTicketRules.entryPhase(t(12, 30)))
        assertEquals(Phase.OPEN, HeroTicketRules.entryPhase(t(12, 32).plusSeconds(59)))
        assertEquals(Phase.LATE, HeroTicketRules.entryPhase(t(12, 33)))
        assertEquals(Phase.LATE, HeroTicketRules.entryPhase(t(15, 0)))
        assertEquals(t(12, 30), HeroTicketRules.ENTRY_BAR); assertEquals(t(12, 33), HeroTicketRules.ENTRY_UNTIL)
    }

    // ---- the strike within Rs 1,000 -----------------------------------------------------------------------------------------

    @Test fun niftysLotOf65FitsUpToRs15BothSides() {
        val spot = 25_010.0
        // Calls above spot, nearest first: 25,050 at Rs 28 (too dear for a lot of 65), 25,100 at Rs 16 (Rs 1,040: over),
        // 25,150 at Rs 12 (Rs 780: fits).
        val calls = listOf(c(25_050.0, 28.0), c(25_100.0, 16.0), c(25_150.0, 12.0), c(25_200.0, 9.0), c(25_250.0, 6.5))
        assertEquals(25_150.0, HeroTicketRules.pick(+1, spot, 65, calls)!!.strike)
        val puts = listOf(c(25_000.0, 31.0), c(24_950.0, 15.35), c(24_900.0, 11.0), c(24_850.0, 8.0))
        assertEquals(24_950.0, HeroTicketRules.pick(-1, spot, 65, puts)!!.strike, "Rs 15.35 x 65 = Rs 997.75")
        assertTrue(HeroTicketRules.fits(15.35, 65)); assertFalse(HeroTicketRules.fits(15.40, 65))
        // A call is never picked on the put side or vice versa.
        assertNull(HeroTicketRules.pick(-1, spot, 65, calls))
        assertNull(HeroTicketRules.pick(+1, spot, 65, puts))
    }

    @Test fun sensexsLotOf20FitsTheWholeBand() {
        val spot = 72_450.0
        // The 2 Apr 2026 kind of ticket: the 72,500 CE at Rs 24.91 (a lot of 20 = Rs 498).
        val calls = listOf(c(72_500.0, 24.91), c(72_600.0, 14.0))
        assertEquals(72_500.0, HeroTicketRules.pick(+1, spot, 20, calls)!!.strike)
        val puts = listOf(c(72_400.0, 30.0), c(72_300.0, 18.0))
        assertEquals(72_400.0, HeroTicketRules.pick(-1, spot, 20, puts)!!.strike, "Rs 30 x 20 = Rs 600")
        assertTrue(HeroTicketRules.fits(30.0, 20)); assertTrue(HeroTicketRules.fits(50.0, 20)); assertFalse(HeroTicketRules.fits(50.05, 20))
    }

    @Test fun noFittingStrikeSkipsThatSide() {
        val spot = 25_010.0
        // Every call over Rs 15.38 for NIFTY's 65: none fits (the side is skipped); the puts still have one.
        assertNull(HeroTicketRules.pick(+1, spot, 65, listOf(c(25_050.0, 29.0), c(25_100.0, 22.0), c(25_150.0, 16.0))))
        // Under Rs 5, over Rs 30, not traded lately, no ask, outside 5% of spot, an unknown lot or spot: none.
        assertNull(HeroTicketRules.pick(+1, spot, 65, listOf(c(25_050.0, 4.95), c(25_100.0, 3.0))))
        assertNull(HeroTicketRules.pick(+1, 72_450.0, 20, listOf(c(72_500.0, 30.10))))
        assertNull(HeroTicketRules.pick(+1, spot, 65, listOf(c(25_150.0, 12.0, traded = false))))
        assertNull(HeroTicketRules.pick(+1, spot, 65, listOf(c(25_150.0, 12.0, ask = null))))
        assertNull(HeroTicketRules.pick(+1, spot, 65, listOf(c(25_150.0, 12.0, ask = 0.0))))
        assertNull(HeroTicketRules.pick(+1, spot, 65, listOf(c(26_300.0, 12.0))), "more than 5% away")
        assertNull(HeroTicketRules.pick(+1, spot, 0, listOf(c(25_150.0, 12.0))))
        assertNull(HeroTicketRules.pick(+1, 0.0, 65, listOf(c(25_150.0, 12.0))))
        assertNull(HeroTicketRules.pick(+1, spot, 65, emptyList()))
        assertFalse(HeroTicketRules.fits(0.0, 65)); assertFalse(HeroTicketRules.fits(10.0, 0))
        // At the money is not OTM.
        assertNull(HeroTicketRules.pick(+1, 25_000.0, 65, listOf(c(25_000.0, 12.0))))
        assertNull(HeroTicketRules.pick(-1, 25_000.0, 65, listOf(c(25_000.0, 12.0))))
    }

    @Test fun aStalePriceIsNeverTheTicket() {
        val spot = 25_010.0
        // 25,150 at Rs 6 while the further 25,200 trades at Rs 9 (more than 6 + max(0.10, 1.5)): its Rs 6 is stale; 25,200 is taken.
        val chain = listOf(c(25_150.0, 6.0), c(25_200.0, 9.0), c(25_250.0, 7.6))
        assertEquals(25_200.0, HeroTicketRules.pick(+1, spot, 65, chain)!!.strike)
        // Within the slack it stays.
        assertEquals(25_150.0, HeroTicketRules.pick(+1, spot, 65, listOf(c(25_150.0, 6.0), c(25_200.0, 7.5)))!!.strike)
        // Not traded in the last 5 bars (a stale last price): skipped, the next one taken.
        assertEquals(25_200.0, HeroTicketRules.pick(+1, spot, 65, listOf(c(25_150.0, 12.0, traded = false), c(25_200.0, 9.0)))!!.strike)
    }

    @Test fun theLimitNeverLetsATicketCostMoreThanRs1000() {
        // Hero's limit (ask + 1 tick, capped at ref x 1.2 + 0.10), then Rs 1,000 / lot.
        assertEquals(12.05, HeroTicketRules.limitPrice(12.0, 12.0, 65))
        assertEquals(15.35, HeroTicketRules.limitPrice(15.35, 15.30, 65), "Rs 1,000 / 65 = 15.38 -> 15.35")
        assertTrue(HeroTicketRules.limitPrice(15.35, 15.30, 65) * 65 <= 1_000.0)
        assertEquals(25.0, HeroTicketRules.limitPrice(24.95, 24.91, 20))
        assertEquals(50.0, HeroTicketRules.limitPrice(60.0, 60.0, 20))
        assertEquals(1.95 + 0.10, HeroTicketRules.limitPrice(1.95, 5.0, 65), 1e-9)
        assertEquals(1_000.0, HeroTicketRules.limitPrice(1_500.0, 1_500.0, 0), "an unknown lot never divides by zero (the app skips it before)")
    }

    @Test fun atMostRs2000OfTicketsADay() {
        assertTrue(HeroTicketRules.dayBudgetOk(0.0, 1_000.0))
        assertTrue(HeroTicketRules.dayBudgetOk(1_000.0, 1_000.0))
        assertFalse(HeroTicketRules.dayBudgetOk(1_500.0, 600.0))
        assertFalse(HeroTicketRules.dayBudgetOk(2_000.0, 0.05))
    }

    // ---- exits --------------------------------------------------------------------------------------------------------------

    private fun bars(vararg pts: Pair<LocalTime, Double>): Map<LocalTime, Double> = mapOf(*pts)

    @Test fun boss25PercentTrailAsR50MeasuredIt() {
        val e = 10.0
        val at = t(12, 31).plusSeconds(20)   // filled in the bar 12:31 .. 12:32 (it closes 12:32)
        // The fill's own bar closing at 7.0 never sells (R50 checks from the bar after the fill's); it does not set the best.
        assertFalse(HeroTicketRules.trail(e, at, bars(t(12, 32) to 7.0)).hit)
        // A close 25% under the fill sells: the trail is also the stop (7.5 <= 0.75 x 10).
        val stop = HeroTicketRules.trail(e, at, bars(t(12, 32) to 9.8, t(12, 33) to 8.0, t(12, 34) to 7.5, t(12, 35) to 6.0))
        assertEquals(t(12, 34), stop.hitAt); assertEquals(10.0, stop.peak); assertEquals(7.5, stop.level)
        // 7.55 is not 25% under: held.
        assertFalse(HeroTicketRules.trail(e, at, bars(t(12, 33) to 7.55)).hit)
        // Up to 40, then a close at 30.0 (0.75 x 40) sells; 30.05 does not.
        val up = bars(t(12, 33) to 14.0, t(13, 0) to 25.0, t(13, 30) to 40.0, t(13, 31) to 30.05)
        assertFalse(HeroTicketRules.trail(e, at, up).hit)
        assertEquals(40.0, HeroTicketRules.trail(e, at, up).peak)
        assertEquals(t(13, 32), HeroTicketRules.trail(e, at, up + (t(13, 32) to 30.0)).hitAt)
        // The best counts from the fill's own bar: a fill bar closing at 20 makes 15 a trail sale on the next bar.
        assertEquals(t(12, 33), HeroTicketRules.trail(e, at, bars(t(12, 32) to 20.0, t(12, 33) to 15.0)).hitAt)
        // Bars before the fill are not read; bars closing at 15:05 or later never sell (15:05 does).
        assertFalse(HeroTicketRules.trail(e, at, bars(t(12, 20) to 50.0, t(12, 33) to 9.0)).hit)
        assertFalse(HeroTicketRules.trail(e, at, bars(t(15, 4) to 9.0, t(15, 5) to 1.0, t(15, 6) to 1.0)).hit)
        assertEquals(t(15, 4), HeroTicketRules.trail(e, at, bars(t(15, 4) to 7.0)).hitAt)
        // A zero close (no price) is never read as a crash.
        assertFalse(HeroTicketRules.trail(e, at, bars(t(12, 40) to 0.0)).hit)
    }

    @Test fun whereTheTrailStartsAtTheFillAfter2xOrAfter3x() {
        val e = 10.0
        val at = t(12, 31).plusSeconds(20)
        assertEquals(HeroTicketRules.TrailStart.AFTER_2X, HeroTicketRules.TrailStart.DEFAULT, "the placeholder until Boss chooses (R55)")
        assertEquals(HeroTicketRules.TrailStart.AFTER_2X, HeroTicketRules.TrailStart.of(null)); assertEquals(HeroTicketRules.TrailStart.AFTER_2X, HeroTicketRules.TrailStart.of("x")); assertEquals(HeroTicketRules.TrailStart.AT_FILL, HeroTicketRules.TrailStart.of("fill")); assertEquals(HeroTicketRules.TrailStart.AFTER_3X, HeroTicketRules.TrailStart.of("3x"))
        assertTrue("R55" in HeroTicketRules.TRAIL_START_NOTE && "after 2×" in HeroTicketRules.TRAIL_START_NOTE)
        // A ticket that never rises: the fill trail is also a 25% stop; after 2x / 3x there is no stop (held to 15:05).
        val fall = bars(t(12, 33) to 9.0, t(12, 34) to 7.0, t(12, 40) to 2.0)
        assertEquals(t(12, 34), HeroTicketRules.trail(e, at, fall, HeroTicketRules.TrailStart.AT_FILL).hitAt)
        assertFalse(HeroTicketRules.trail(e, at, fall, HeroTicketRules.TrailStart.AFTER_2X).hit)
        assertFalse(HeroTicketRules.trail(e, at, fall, HeroTicketRules.TrailStart.AFTER_3X).hit)
        // The 2 Apr kind of day: up to 1.9x, a 25% dip, then 27x. From the fill it sells the dip; after 2x it never armed there.
        val run = bars(t(12, 33) to 19.0, t(12, 34) to 14.0, t(13, 0) to 25.0, t(14, 0) to 270.0, t(14, 30) to 200.0)
        assertEquals(t(12, 34), HeroTicketRules.trail(e, at, run, HeroTicketRules.TrailStart.AT_FILL).hitAt)
        // After 2x: armed from 25 (2.5x); 200 is more than 25% under 270, so it sells there.
        val two = HeroTicketRules.trail(e, at, run, HeroTicketRules.TrailStart.AFTER_2X)
        assertEquals(t(14, 30), two.hitAt); assertEquals(270.0, two.peak)
        // After 3x: armed only once a close reaches 30 (here 270), then the same fall sells it.
        assertEquals(t(14, 30), HeroTicketRules.trail(e, at, run, HeroTicketRules.TrailStart.AFTER_3X).hitAt)
        // Armed exactly at 2x: a close of 20.0 arms it; the next close at 15.0 (25% off 20) sells.
        assertEquals(t(12, 34), HeroTicketRules.trail(e, at, bars(t(12, 33) to 20.0, t(12, 34) to 15.0), HeroTicketRules.TrailStart.AFTER_2X).hitAt)
        assertFalse(HeroTicketRules.trail(e, at, bars(t(12, 33) to 19.95, t(12, 34) to 10.0), HeroTicketRules.TrailStart.AFTER_2X).hit)
        // 2x reached but not 3x: the 3x trail never arms; the dip only sells under the 2x one.
        val mid = bars(t(12, 33) to 25.0, t(12, 34) to 18.0)
        assertEquals(t(12, 34), HeroTicketRules.trail(e, at, mid, HeroTicketRules.TrailStart.AFTER_2X).hitAt)
        assertFalse(HeroTicketRules.trail(e, at, mid, HeroTicketRules.TrailStart.AFTER_3X).hit)
        // With a 2x target beside an after-2x trail, the target is first (it sells at 2x).
        val armedNow = HeroTicketRules.trail(e, at, bars(t(12, 33) to 20.5), HeroTicketRules.TrailStart.AFTER_2X)
        assertEquals(ExitKind.TARGET, HeroTicketRules.exitStep(e, 20.5, armedNow, t(12, 34), Target.X2)!!.kind)
    }

    @Test fun aRestartInTheMiddleOfATradeFindsTheSameTrail() {
        val e = 12.0
        val at = t(12, 30).plusSeconds(40)
        val all = LinkedHashMap<LocalTime, Double>()
        var u = t(12, 31); var px = 12.0
        while (u.isBefore(t(14, 0))) { px = if (u.isBefore(t(13, 10))) px + 0.5 else px - 0.4; all[u] = px; u = u.plusMinutes(1) }
        // Run minute by minute (the phone that never stopped) against one read after a kill at 13:25 (the phone that restarted):
        // the trail is read from the bars alone, so the restarted watch sells on the same minute at the same level.
        var continuous: HeroTicketRules.Trail? = null
        for (m in all.keys) { val tr = HeroTicketRules.trail(e, at, all.filterKeys { !it.isAfter(m) }); if (tr.hit) { continuous = tr; break } }
        val restarted = HeroTicketRules.trail(e, at, all)
        assertNotNull(continuous)
        assertEquals(continuous, restarted)
        assertTrue(restarted.peak > 30.0 && restarted.hitAt!!.isAfter(t(13, 10)))
    }

    @Test fun targetsAt2xOr3xBesideTheTrailTheTrailAloneByDefault() {
        val e = 10.0
        val none = HeroTicketRules.Trail(e, null)
        assertEquals(Target.TRAIL_ONLY, Target.DEFAULT)
        assertEquals(Target.TRAIL_ONLY, Target.of(null)); assertEquals(Target.TRAIL_ONLY, Target.of("nonsense"))
        assertEquals(Target.X2, Target.of("2x")); assertEquals(Target.X3, Target.of("3x")); assertEquals(Target.TRAIL_ONLY, Target.of("trail"))
        // Trail only: no target, however high it goes.
        assertNull(HeroTicketRules.targetLevel(e, Target.TRAIL_ONLY))
        assertNull(HeroTicketRules.exitStep(e, 95.0, none, t(13, 0), Target.TRAIL_ONLY))
        // 2x: hit once the price trades a tick through 20.00.
        assertEquals(20.0, HeroTicketRules.targetLevel(e, Target.X2))
        assertNull(HeroTicketRules.exitStep(e, 20.0, none, t(13, 0), Target.X2))
        assertEquals(HeroTicketRules.ExitStep(ExitKind.TARGET, 20.0), HeroTicketRules.exitStep(e, 20.05, none, t(13, 0), Target.X2))
        // 3x: 2x is not enough; 30.05 is.
        assertNull(HeroTicketRules.exitStep(e, 25.0, none, t(13, 0), Target.X3))
        assertEquals(HeroTicketRules.ExitStep(ExitKind.TARGET, 30.0), HeroTicketRules.exitStep(e, 30.05, none, t(13, 0), Target.X3))
        assertEquals(30.85, HeroTicketRules.targetLevel(10.29, Target.X3), "down to the tick")
        // A gap through the target (the price leapt to 3.5x with a 2x target): booked at the target, never the gap's better price.
        assertEquals(HeroTicketRules.ExitStep(ExitKind.TARGET, 20.0), HeroTicketRules.exitStep(e, 35.0, none, t(13, 0), Target.X2))
        assertEquals(20.0, HeroTicketRules.bookedTarget(34.6, 20.0))
        assertEquals(19.95, HeroTicketRules.bookedTarget(19.95, 20.0))
        // Trail and target together: whichever comes first. The trail first (a fall after a run to 1.8x)...
        val fell = HeroTicketRules.Trail(18.0, t(13, 40))
        assertEquals(HeroTicketRules.ExitStep(ExitKind.TRAIL, null), HeroTicketRules.exitStep(e, 13.4, fell, t(13, 41), Target.X2))
        // ... or the target first, even on a pass where the trail would also sell.
        assertEquals(ExitKind.TARGET, HeroTicketRules.exitStep(e, 20.10, fell, t(13, 41), Target.X2)!!.kind)
        assertEquals(ExitKind.TRAIL, HeroTicketRules.exitStep(e, 6.0, HeroTicketRules.Trail(10.0, t(12, 40)), t(12, 41), Target.TRAIL_ONLY)!!.kind)
    }

    @Test fun fifteenOhFiveSquaresItOff() {
        val none = HeroTicketRules.Trail(10.0, null)
        assertNull(HeroTicketRules.exitStep(10.0, 12.0, none, t(15, 4).plusSeconds(59), Target.TRAIL_ONLY))
        assertEquals(HeroTicketRules.ExitStep(ExitKind.TIME, null), HeroTicketRules.exitStep(10.0, 12.0, none, t(15, 5), Target.TRAIL_ONLY))
        assertEquals(HeroTicketRules.ExitStep(ExitKind.TIME, null), HeroTicketRules.exitStep(10.0, null, none, t(15, 30), Target.X3))
        // From an earlier day (the phone was off at 15:05): sold at once.
        assertEquals(ExitKind.TIME, HeroTicketRules.exitStep(10.0, 12.0, none, t(9, 20), Target.TRAIL_ONLY, overnight = true)!!.kind)
        // A target hit at 15:06 still sells at its target first.
        assertEquals(ExitKind.TARGET, HeroTicketRules.exitStep(10.0, 21.0, none, t(15, 6), Target.X2)!!.kind)
        // No entry price: nothing to manage.
        assertNull(HeroTicketRules.exitStep(0.0, 12.0, none, t(15, 10), Target.TRAIL_ONLY))
        assertEquals(listOf("ticket_target", "ticket_1505", "ticket_trail"), ExitKind.entries.map { it.why })
    }
}
