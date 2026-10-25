package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.StopLane
import com.optionslab.engine.Right
import com.optionslab.engine.orb.HeroTicketRules
import com.optionslab.engine.sandbox.Quote
import com.optionslab.ira.AutoChange
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 25 Oct (Boss's decisions on the audits), in the app: the stop lane's quiet clock, the Muhurat Sunday, the Autopilot Undo of the
 * new paper rules, and the watch pass reading the paper days for the weekly and account stops.
 */
class BossDecisionsAppTest : RobolectricTest() {
    @After fun down() {
        Market.testClock = null
        BossRiskHost.resetForTest(paperOn = false)
        KiteStream.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    // ---- decision 4: faster stop looks while the held prices are quiet -----------------------------------------------------

    private fun noon() {
        val realNow = java.time.ZonedDateTime.now(com.optionslab.engine.IST)
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST),
            java.time.Duration.between(realNow, Market.today().atTime(12, 0).atZone(com.optionslab.engine.IST)))
    }

    private fun held(): Paper.Contract {
        val exp = Market.today().plusDays(6)
        val sym = "NIFTY" + exp.format(DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH)).uppercase(Locale.ENGLISH) + "25000CE"
        val c = Paper.Contract(sym, "NIFTY", exp, 25_000.0, Right.CE, 65, "NSE_FO|$sym", "NFO")
        assertTrue(runBlocking { Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, Quote(100.0, bid = 99.9, ask = 100.1)) }.ok)
        return c
    }

    @Test fun quietMeansAHeldPositionWithNoFreshStreamTickWhileTheStreamIsExpected() {
        noon()
        assertFalse("nothing held: never quiet", StopLane.quietFor(StopLane.Mode.NSE))
        val c = held()
        // Not logged in to Zerodha: no stream is expected (prices are the minute candles), so the 15 s clock stays.
        assertFalse(com.optionslab.app.data.Broker.loggedIn)
        assertFalse(StopLane.quietFor(StopLane.Mode.NSE))
        com.optionslab.app.testing.FakeKite().use { kite ->
            kite.login(live = false)
            assertTrue(com.optionslab.app.data.Broker.loggedIn)
            Paper.tokenForTest(c.symbol, 777L)
            assertTrue("held with no tick: quiet", StopLane.quietFor(StopLane.Mode.NSE))
            KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(777L, 101.0)))
            assertFalse("a fresh tick: not quiet", StopLane.quietFor(StopLane.Mode.NSE))
            // Its own side only: an NSE position never makes MCX's look quiet; OFF is never quiet.
            assertFalse(StopLane.quietFor(StopLane.Mode.MCX))
            assertFalse(StopLane.quietFor(StopLane.Mode.OFF))
            Paper.tokenForTest(c.symbol, null)
        }
        assertTrue(StopLane.quietLine(), StopLane.quietLine().startsWith("Quiet-stream stop checks:"))
    }

    // ---- decision 13: the Muhurat Sunday ------------------------------------------------------------------------------------

    @Test fun theMuhuratSundayIsASpecialSessionNeverARegularTradingDayEvenAddedByHand() {
        val muhurat = LocalDate.of(2026, 11, 8)
        assertNotNull(Market.specialSession(muhurat))
        assertEquals("Diwali Muhurat trading, 18:00-19:00 IST", Market.specialSession(muhurat)!!.text)
        assertFalse(Market.isTradingDay(muhurat))
        Holidays.addSession(muhurat)                          // Boss adds it by hand as a special session
        assertFalse("no arm's 09:15 clock runs that day", Market.isTradingDay(muhurat))
        assertTrue("the Monday after is ordinary", Market.isTradingDay(muhurat.plusDays(1)))
        Holidays.removeSession(muhurat)
        assertNull(Market.specialSession(muhurat.plusDays(1)))
    }

    // ---- the Autopilot Undo of the new paper rules -------------------------------------------------------------------------

    @Test fun theUndoSwitchesAPaperRuleOffOnlyWithThePinWhenItIsALimitAndBackOnFreely() {
        BossRiskHost.resetForTest(paperOn = true)
        com.optionslab.app.security.PinLock.setPin("482915".toCharArray())
        com.optionslab.app.testing.BrokerArea.saveKeys("482915")
        val weekly = AutoChange.bossPaperRule(AutoChange.WEEKLY_STOP_PAPER_TARGET, true)!!
        assertNull("loosening without the PIN: nothing changes", Autopilot.apply(AutoChange.inverse(weekly, "undone by you"), pinConfirmed = false))
        assertTrue(BossRiskHost.paperOn(BossRiskHost.Rule.WEEKLY))
        assertNotNull(Autopilot.apply(AutoChange.inverse(weekly, "undone by you"), pinConfirmed = true))
        assertFalse(BossRiskHost.paperOn(BossRiskHost.Rule.WEEKLY))
        assertNotNull("back on never waits", Autopilot.apply(weekly, pinConfirmed = false, byBoss = true))
        assertTrue(BossRiskHost.paperOn(BossRiskHost.Rule.WEEKLY))
        // h14's capped limit is not a limit: its Undo needs no PIN.
        val capped = AutoChange.bossPaperRule(AutoChange.CAPPED_LIMIT_PAPER_TARGET, true)!!
        assertNotNull(Autopilot.apply(AutoChange.inverse(capped, "undone by you"), pinConfirmed = false))
        assertFalse(BossRiskHost.cappedPaper())
    }

    @Test fun theTicketTrailsUndoPutsAfter2xBackForNewTickets() {
        val trail = AutoChange.bossPaperRule(AutoChange.TICKET_TRAIL_TARGET, true)!!
        assertNotNull(Autopilot.apply(AutoChange.inverse(trail, "undone by you"), pinConfirmed = false))
        assertEquals(HeroTicketRules.TrailStart.AFTER_2X, runBlocking { HeroTicketArm.refresh() }.trailStart)
        assertNotNull(Autopilot.apply(trail, pinConfirmed = false, byBoss = true))
        assertEquals(HeroTicketRules.TrailStart.AT_FILL, runBlocking { HeroTicketArm.refresh() }.trailStart)
    }

    // ---- the watch pass: the paper days ------------------------------------------------------------------------------------

    @Test fun theWatchPassReadsTodaysPaperFigureAndTheWeeklyStopIsReachedOnIt() {
        noon()
        BossRiskHost.resetForTest(paperOn = true)
        Paper.reset(BigDecimal("400000"))
        BossRiskHost.refresh(null, -21_000.0, AppSettings(), nowMs = System.currentTimeMillis())
        assertTrue(BossRiskHost.view.value.weekReached)
        assertTrue(BossRiskHost.lines()[1], BossRiskHost.lines()[1].contains("REACHED: no new paper entries until Monday"))
        assertTrue(Diag.lines().toString(), Diag.lines().any { it.contains("past the -Rs 20,000 weekly loss stop") })
        // A recovery later the same week does not let the bots back in.
        BossRiskHost.refresh(null, -5_000.0, AppSettings(), nowMs = System.currentTimeMillis() + 120_000)
        assertTrue(BossRiskHost.view.value.weekReached)
        // Off (0) in the settings: not reached.
        BossRiskHost.resetForTest(paperOn = true)
        BossRiskHost.refresh(null, -21_000.0, AppSettings(paperWeeklyLoss = 0.0))
        assertFalse(BossRiskHost.view.value.weekReached)
    }
}
