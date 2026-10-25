package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 25 Oct (Boss's decisions on the app audit): the weekly loss stop, the 2% risk cap, opposite bets, the spread check, h14's cap, IV. */
class BossRiskTest {
    private val mon = LocalDate.of(2026, 10, 26)       // a Monday (the 26 Oct live decision day)
    private fun day(d: LocalDate, net: Double) = AccountStop.Day(d, net)

    // ---- weekly ----

    @Test fun theWeekStartsOnMondayAndASundaySessionCountsInItsOwnWeek() {
        assertEquals(mon, BossRisk.weekStart(mon))
        assertEquals(mon, BossRisk.weekStart(mon.plusDays(4)), "Friday")
        assertEquals(mon, BossRisk.weekStart(mon.plusDays(6)), "Sunday (a Muhurat-like session) is still this week")
        assertEquals(mon.plusDays(7), BossRisk.weekStart(mon.plusDays(7)))
    }

    @Test fun emptyDataIsNotReached() {
        val w = BossRisk.weekly(emptyList(), mon, 20_000.0)
        assertFalse(w.reached); assertEquals(0.0, w.net); assertEquals(0, w.days); assertNull(BossRisk.weeklyRefusal(w))
    }

    @Test fun reachedAtTheLimitAndOnlyThisWeeksDaysCount() {
        val days = listOf(day(mon.minusDays(3), -50_000.0),          // last Friday: another week
            day(mon, -8_000.0), day(mon.plusDays(1), -7_000.0), day(mon.plusDays(2), -4_999.0))
        assertFalse(BossRisk.weekly(days, mon.plusDays(2), 20_000.0).reached, "-19,999 is under the limit")
        val w = BossRisk.weekly(days + day(mon.plusDays(3), -1.0), mon.plusDays(3), 20_000.0)
        assertTrue(w.reached); assertEquals(-20_000.0, w.net); assertEquals(4, w.days)
        val why = BossRisk.weeklyRefusal(w)!!
        assertTrue(why.startsWith("weekly loss stop: this week -Rs 20,000 reached the -Rs 20,000 weekly limit"), why)
        assertTrue(why.contains("open positions keep their exits"), why)
    }

    @Test fun onceReachedItHoldsForTheWeekEvenAfterARecoveryAndClearsOnMonday() {
        val days = listOf(day(mon, -21_000.0), day(mon.plusDays(1), +5_000.0))
        assertFalse(BossRisk.weekly(days, mon.plusDays(1), 20_000.0).reached, "without the kept day the recovery un-reaches it")
        assertTrue(BossRisk.weekly(days, mon.plusDays(1), 20_000.0, reachedOn = mon).reached, "kept reached for the week")
        assertFalse(BossRisk.weekly(days, mon.plusDays(7), 20_000.0, reachedOn = mon).reached, "a new week starts clear")
    }

    @Test fun offNaNAndHolidayGapsAreSafe() {
        val days = listOf(day(mon, -30_000.0), day(mon.plusDays(1), Double.NaN))
        assertFalse(BossRisk.weekly(days, mon.plusDays(2), 0.0).reached, "0 is off")
        assertFalse(BossRisk.weekly(days, mon.plusDays(2), Double.NaN).reached, "not a number is off")
        val w = BossRisk.weekly(days, mon.plusDays(2), 20_000.0)
        assertEquals(1, w.days, "a day that is not a number is left out; a holiday with no figure is simply missing")
        assertTrue(w.reached)
    }

    // ---- 2% risk cap ----

    @Test fun oneBankniftyLotFitsAndThreeAreSizedDown() {
        // The audit's example: BANKNIFTY 30 x Rs 600, -15% stop = Rs 2,700 a lot (+ charges); 2% of Rs 4L = Rs 8,000.
        val one = BossRisk.riskCap(1, 30, 600.0, 0.15, 400_000.0)
        assertEquals(1, one.lots); assertNull(one.why); assertFalse(one.skipped); assertFalse(one.sizedDown)
        val three = BossRisk.riskCap(3, 30, 600.0, 0.15, 400_000.0)
        assertEquals(2, three.lots); assertTrue(three.sizedDown)
        assertTrue(three.why!!.contains("sized down to 2 lots"), three.why)
    }

    @Test fun oneLotOverTheCapIsSkippedNeverSizedUp() {
        val c = BossRisk.riskCap(1, 30, 2_000.0, 1.0, 400_000.0)
        assertEquals(0, c.lots); assertTrue(c.skipped)
        assertTrue(c.why!!.startsWith("2% risk cap: 1 lot risks Rs 60,060 to its stop"), c.why)
        assertEquals(2, BossRisk.riskCap(2, 1, 10.0, 0.15, 400_000.0).lots, "never more lots than asked")
    }

    @Test fun unknownInputsLetTheEntryGoAsAsked() {
        assertEquals(2, BossRisk.riskCap(2, 30, Double.NaN, 0.15, 400_000.0).lots)
        assertEquals(2, BossRisk.riskCap(2, 0, 600.0, 0.15, 400_000.0).lots)
        assertEquals(2, BossRisk.riskCap(2, 30, 600.0, 0.15, 0.0).lots)
        assertEquals(2, BossRisk.riskCap(2, 30, 600.0, Double.NaN, 400_000.0).lots)
        assertEquals(0, BossRisk.riskCap(0, 30, 600.0, 0.15, 400_000.0).lots)
        assertFalse(BossRisk.riskCap(0, 30, 600.0, 0.15, 400_000.0).skipped)
    }

    @Test fun anArmsOwnStopIsUsedAndTheOrdersStopWins() {
        assertEquals(1.0, BossRisk.stopShare("Night (R3)", 100.0))
        assertEquals(0.25, BossRisk.stopShare("Hero ₹1,000 ticket", 10.0))
        assertEquals(0.80, BossRisk.stopShare("FT3 near-ATM", 100.0))
        assertEquals(0.15, BossRisk.stopShare("Liquidity 15+5", 100.0))
        assertEquals(0.15, BossRisk.stopShare(null, 100.0))
        assertEquals(0.3, BossRisk.stopShare("Night (R3)", 100.0, stop = 70.0), 1e-9)
        assertEquals(0.15, BossRisk.stopShare("x", 100.0, stop = 120.0), "a stop above the entry is not a stop below it")
    }

    // ---- opposite bets ----

    private val bets = listOf(BossRisk.Bet("R52 Open outside value", "NIFTY", 1, "NIFTY26OCT25500CE"))

    @Test fun anotherArmsOppositeBetOnTheSameIndexIsRefused() {
        val why = BossRisk.opposite(bets, "Solo 2.0", "NIFTY", -1)
        assertNotNull(why)
        assertTrue(why.startsWith("opposite bets: R52 Open outside value already holds a call on NIFTY"), why)
        assertTrue(why.endsWith("this put would bet the other way"), why)
    }

    @Test fun theSameWayAnotherIndexOrTheSameArmIsNotRefused() {
        assertNull(BossRisk.opposite(bets, "Solo 2.0", "NIFTY", 1), "same way: the same-direction cap's business")
        assertNull(BossRisk.opposite(bets, "Solo 2.0", "MIDCPNIFTY", -1), "another index")
        assertNull(BossRisk.opposite(bets, "R52 Open outside value", "NIFTY", -1), "the arm's own sides")
        assertNull(BossRisk.opposite(emptyList(), "Solo 2.0", "NIFTY", -1), "nothing open")
        assertNull(BossRisk.opposite(bets, "Solo 2.0", "nifty", 0), "no way")
        assertNotNull(BossRisk.opposite(bets, "Solo 2.0", "nifty", -1), "the index name in any case")
    }

    @Test fun moreThanOneAgainstIsCounted() {
        val two = bets + BossRisk.Bet("R72 Breadth Donchian", "NIFTY", 1, "NIFTY26OCT25600CE")
        assertTrue(BossRisk.opposite(two, "Solo 2.0", "NIFTY", -1)!!.contains("and 1 more"))
    }

    // ---- spread (shadow) ----

    @Test fun theSpreadCheckSaysWhatItWouldDo() {
        assertNull(BossRisk.spread(99.8, 100.2), "0.2% half-spread passes")
        assertEquals(0.002, BossRisk.halfSpread(99.8, 100.2)!!, 1e-9)
        assertTrue(BossRisk.spread(99.0, 101.0)!!.startsWith("spread check: half-spread 1.00% over 0.30%"))
        assertTrue(BossRisk.spread(null, 101.0)!!.startsWith("spread check: no two-sided book"))
        assertTrue(BossRisk.spread(0.0, 101.0)!!.contains("no two-sided book"))
        assertTrue(BossRisk.spread(102.0, 101.0)!!.contains("no two-sided book"), "crossed")
        assertNull(BossRisk.halfSpread(Double.NaN, 1.0))
    }

    // ---- h14's capped limit ----

    @Test fun theCapIsHalfAPercentDownToTheTick() {
        assertEquals(201.0, BossRisk.cap(200.0))
        assertEquals(100.5, BossRisk.cap(100.0))
        assertEquals(10.05, BossRisk.cap(10.0), "10.05 exactly")
        assertEquals(1.0, BossRisk.cap(1.0), "1.005 rounds DOWN to the tick: never above the cap")
        assertNull(BossRisk.cap(null)); assertNull(BossRisk.cap(0.0)); assertNull(BossRisk.cap(Double.NaN))
        assertTrue(BossRisk.underCap(100.5, 100.5)); assertFalse(BossRisk.underCap(100.55, 100.5))
        assertFalse(BossRisk.underCap(null, 100.5)); assertFalse(BossRisk.underCap(0.0, 100.5))
    }

    // ---- IV at entry ----

    @Test fun theIvOfAnEntryIsSolvedAndNullWhenItCannotBe() {
        // An ATM call with a week left priced at 0.4 x sigma x sqrt(t) x F (about 15% vol).
        val t = 7.0 / 365
        val premium = 0.4 * 0.15 * Math.sqrt(t) * 25_000
        val iv = BossRisk.entryIv(premium, 25_000.0, 25_000.0, t, "CE")!!
        assertEquals(0.15, iv, 0.005)
        assertNotNull(BossRisk.entryIv(premium, 25_000.0, 25_000.0, t, "pe"))
        assertNull(BossRisk.entryIv(1.0, 25_000.0, 24_000.0, t, "CE"), "below intrinsic")
        assertNull(BossRisk.entryIv(premium, 25_000.0, 25_000.0, 0.0, "CE"), "expired: no time left")
        assertNull(BossRisk.entryIv(null, 25_000.0, 25_000.0, t, "CE"))
        assertNull(BossRisk.entryIv(premium, null, 25_000.0, t, "CE"), "no underlying price (stale or market closed)")
        assertNull(BossRisk.entryIv(premium, 25_000.0, 25_000.0, t, "FUT"))
    }

    // ---- the change timeline's entries (AutoChange) ----

    @Test fun eachPaperRuleIsAPaperFilterLoggedAsBosssDecisionAndUndoneInOneTap() {
        for (t in AutoChange.BOSS_PAPER_RULES) {
            val on = AutoChange.bossPaperRule(t, true)!!
            assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind); assertEquals(AutoChange.Scope.PAPER, on.scope)
            assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on), t)
            assertEquals(AutoChange.ON, on.to); assertEquals(AutoChange.SWITCHED_OFF, on.from)
            assertFalse(AutoChange.never(on), t)
            assertFalse(LiquidityFreeze.touches(t), "the Liquidity freeze never holds its Undo: $t")
            assertFalse(AutoChange.undoNeedsPin(on), "paper only: no live PIN")
            val off = AutoChange.bossPaperRule(t, false)!!
            assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(off), t)
            assertTrue(on.reason.contains("25 Oct") || on.reason.contains("R55"), on.reason)
        }
        assertNull(AutoChange.bossPaperRule("filter:something_else", true))
        assertTrue(AutoChange.bossPaperRule(AutoChange.WEEKLY_STOP_PAPER_TARGET, true, 30_000.0)!!.reason.contains("Rs 30,000"))
    }

    @Test fun undoingARiskLimitLoosensItAndTakesThePinTheOthersDoNot() {
        for (t in AutoChange.PIN_TO_LOOSEN) assertTrue(AutoChange.undoLoosensLimit(AutoChange.bossPaperRule(t, true)!!), t)
        for (t in AutoChange.BOSS_PAPER_RULES - AutoChange.PIN_TO_LOOSEN) assertFalse(AutoChange.undoLoosensLimit(AutoChange.bossPaperRule(t, true)!!), t)
        // Undoing a switch-off (back on) only tightens.
        assertFalse(AutoChange.undoLoosensLimit(AutoChange.bossPaperRule(AutoChange.WEEKLY_STOP_PAPER_TARGET, false)!!))
        assertEquals(setOf(AutoChange.ACCOUNT_STOP_PAPER_TARGET, AutoChange.WEEKLY_STOP_PAPER_TARGET, AutoChange.RISK_CAP_PAPER_TARGET,
            AutoChange.OPPOSITE_PAPER_TARGET), AutoChange.PIN_TO_LOOSEN)
    }

    @Test fun zerodhasWeeklyAndMonthlyOptionSymbolsAreRead() {
        val w = BossRisk.kiteOption("NIFTY26O0624500CE")!!
        assertEquals(BossRisk.KiteOption("NIFTY", LocalDate.of(2026, 10, 6), 24_500.0, "CE"), w)
        assertEquals(LocalDate.of(2026, 1, 6), BossRisk.kiteOption("NIFTY2610624500CE")!!.expiry)
        assertEquals(LocalDate.of(2026, 11, 3), BossRisk.kiteOption("NIFTY26N0325000PE")!!.expiry)
        assertEquals(LocalDate.of(2026, 12, 29), BossRisk.kiteOption("SENSEX26D2982000CE")!!.expiry)
        // The monthly form needs the day's list for its expiry day: without one it is not read (never guessed).
        assertNull(BossRisk.kiteOption("BANKNIFTY26OCT56000CE"))
        val m = BossRisk.kiteOption("BANKNIFTY26OCT56000CE") { u, y, mo -> if (u == "BANKNIFTY" && y == 2026 && mo == 10) LocalDate.of(2026, 10, 27) else null }!!
        assertEquals(BossRisk.KiteOption("BANKNIFTY", LocalDate.of(2026, 10, 27), 56_000.0, "CE"), m)
        // Not an option, or nonsense: null, never a throw.
        assertNull(BossRisk.kiteOption("BANKNIFTY26OCTFUT"))
        assertNull(BossRisk.kiteOption("RELIANCE"))
        assertNull(BossRisk.kiteOption("NIFTY2613224500CE"), "month 13 / day 32: not a date")
        assertNull(BossRisk.kiteOption(""))
    }
}
