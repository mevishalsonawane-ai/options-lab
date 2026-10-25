package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.ira.DayStop
import com.optionslab.ira.SegmentLock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate
import java.time.LocalTime

/**
 * Boss, 21 Oct: "Yes, give commodities their own day lock". The paper day lock and daily loss limit split by segment (NSE and
 * MCX), through the real [LossBreaker] pass, [DayLockGuard] and the bot's day stop ([Strategies]), on a pinned clock.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class SegmentLimitsTest : RobolectricTest() {
    private val day = LocalDate.of(2026, 10, 21)          // Wed: NSE and MCX both open

    private fun at(d: LocalDate, h: Int, m: Int) {
        Market.testClock = java.time.Clock.fixed(d.atTime(h, m).atZone(IST).toInstant(), IST)
    }

    /** The paper day: NSE's [nse] and MCX's [mcx], each before [charges] rupees of charges on that segment. */
    private fun paper(nse: Double, mcx: Double, charges: Double = 0.0) {
        LossBreaker.testPaperDay = {
            LossBreaker.PaperDay(nse + mcx - 2 * charges, listOf("NFO" to nse, "MCX" to mcx), listOf("NFO" to charges, "MCX" to charges))
        }
    }

    private fun check() = runBlocking { LossBreaker.check(context) }
    private fun mcxStopped() = runBlocking { LossBreaker.mcxStopped(Strategies.stoppedWhy()) }
    private fun nseStopped() = runBlocking { Strategies.stoppedToday() } || LossBreaker.trippedToday()

    @org.junit.Before fun up() { com.optionslab.app.testing.Background.clearAlerts() }   // and the banner's 20 s repeat filter
    @After fun down() { Market.testClock = null; LossBreaker.testPaperDay = null }

    @Test fun commoditiesKeepTheirOwnLimitsByDefaultWithThePaperAmounts() {
        val s = AppSettings.load()
        assertTrue(s.mcxOwnLimits)
        assertEquals(s.dayLock, s.mcxDayLock, 0.0)
        assertEquals(s.guardPaperDailyLoss, s.mcxPaperDailyLoss, 0.0)
        // Unset, they follow Boss's paper amounts; once set, their own.
        SecurePrefs.putAll(mapOf("g.dayLock" to 10_000.0, "g.pLoss" to 4_000.0))
        assertEquals(10_000.0, AppSettings.load().mcxDayLock, 0.0)
        assertEquals(4_000.0, AppSettings.load().mcxPaperDailyLoss, 0.0)
        SecurePrefs.putAll(mapOf("g.mcxDayLock" to 0.0, "g.mcxPLoss" to 2_000.0))
        assertEquals(0.0, AppSettings.load().mcxDayLock, 0.0)
        assertEquals(2_000.0, AppSettings.load().mcxPaperDailyLoss, 0.0)
    }

    /** The 21 Oct day: R52 MIDCPNIFTY +11,514 at 11:00 locks NSE paper; the MCX evening still trades. */
    @Test fun nseDayLockReachedWhileMcxStillTrades() {
        at(day, 11, 0)
        paper(nse = 11_514.0, mcx = 0.0)
        check()
        assertNotNull(DayLockGuard.active(false))
        assertNotNull("NSE paper entries are refused", DayLockGuard.refusal(false))
        assertNull("the MCX arms are not", DayLockGuard.mcxRefusal())
        assertNull(DayLockGuard.mcxActive())
        assertEquals("Day lock reached (paper NSE) +Rs 11,514 (limit +Rs 8,000) at 11:00: no new NSE entries today", DayLockGuard.statusLine())
        assertNull(DayLockGuard.mcxLine())
        assertTrue(DayLockGuard.diagLine().contains("Day lock reached (paper NSE)"))
        // Zerodha's lock is its own: untouched.
        assertNull(DayLockGuard.refusal(true))
        // 18:00: MCX is up by 9,000 too: now the commodities lock as well.
        at(day, 18, 5)
        paper(nse = 11_514.0, mcx = 9_000.0)
        check()
        assertEquals("Commodities day lock reached +Rs 9,000 (limit +Rs 8,000) at 18:05: no new MCX entries today", DayLockGuard.mcxLine())
        assertTrue(DayLockGuard.mcxRefusal()!!.startsWith("Commodities day lock reached"))
        assertEquals(2, DayLockGuard.statusLine()!!.lines().size)
    }

    @Test fun mcxDayLockReachedWhileNseStillTrades() {
        at(day, 19, 0)
        paper(nse = -500.0, mcx = 8_200.0, charges = 100.0)
        check()
        assertNotNull(DayLockGuard.mcxRefusal())
        assertNull(DayLockGuard.refusal(false))
        assertNull(DayLockGuard.active(false))
    }

    @Test fun aLossInNseStopsNseOnlyAndALaterLossInMcxStopsMcx() {
        at(day, 11, 30)
        paper(nse = -6_500.0, mcx = 300.0)
        check()
        assertTrue(LossBreaker.trippedToday())
        assertEquals(SegmentLock.Trip.PAPER, LossBreaker.tripToday())
        assertTrue(nseStopped())
        assertFalse("MCX trades on", mcxStopped())
        assertTrue("the live door never weaker", LossBreaker.liveDoorTripped())
        assertTrue(DayLockGuard.statusLine()!!.contains(SegmentLock.NSE_LOSS_ONLY))
        val said = AutomationSupport.alerts().first { it.startsWith("Daily loss limit") }
        assertTrue(said, said.contains("Today's Paper NSE P&L") && said.contains("The MCX paper arms keep their own limit"))
        // Evening: MCX loses past its own 6,000 too.
        at(day, 20, 10)
        paper(nse = -6_500.0, mcx = -6_100.0)
        check()
        assertTrue(LossBreaker.mcxTrippedToday())
        assertTrue(mcxStopped())
        assertTrue(DayLockGuard.statusLine()!!.contains("Commodities daily loss limit reached -Rs 6,100 (limit -Rs 6,000) at 20:10"))
    }

    @Test fun aLossInMcxStopsMcxOnlyAndNseTradesOn() {
        at(day, 19, 12)
        paper(nse = 2_000.0, mcx = -6_200.0)
        check()
        assertTrue(LossBreaker.mcxTrippedToday())
        assertFalse(LossBreaker.trippedToday())
        assertFalse("NSE trades on", nseStopped())
        assertTrue(mcxStopped())
        assertTrue(LossBreaker.liveDoorTripped())
        assertEquals("Commodities daily loss limit reached -Rs 6,200 (limit -Rs 6,000) at 19:12: no new MCX entries today; the MCX arms sold what they held", DayLockGuard.mcxLine())
        // MCX recovers: its stop holds for the day; a NSE gain never lifts it.
        paper(nse = 9_000.0, mcx = 1_000.0)
        check()
        assertTrue(mcxStopped())
        // "Start all" does not lift it either.
        runBlocking { Strategies.startAgain() }
        assertTrue(mcxStopped())
    }

    @Test fun bossStopStopsBothAndStartAgainLiftsBoth() {
        at(day, 10, 0)
        paper(nse = 0.0, mcx = 0.0)
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        assertTrue(nseStopped())
        assertTrue(mcxStopped())
        runBlocking { Strategies.startAgain() }
        assertFalse(nseStopped())
        assertFalse(mcxStopped())
        // The tile's stop too.
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false, why = DayStop.Why.TILE) }
        assertTrue(mcxStopped())
    }

    @Test fun bossStopAfterTheNsePaperLossStillStopsMcx() {
        at(day, 11, 0)
        paper(nse = -7_000.0, mcx = 0.0)
        check()
        assertFalse(mcxStopped())
        runBlocking { Strategies.stopForToday(stopRunning = true, compromised = false) }
        assertEquals("the day keeps the loss stop's reason", DayStop.Why.LOSS, runBlocking { Strategies.stoppedWhy() })
        assertTrue("but Boss's own stop stops the MCX arms too", mcxStopped())
    }

    @Test fun zerodhasLossLimitStopsBothEvenAfterTheNsePaperOne() {
        val kite = FakeKite()
        try {
            AutomationSupport.niftyOptions(kite)
            at(day, 11, 0)
            paper(nse = -7_000.0, mcx = 0.0)
            check()
            assertFalse(mcxStopped())
            // Zerodha loses past its 2,000 limit later: the MCX arms stop too.
            kite.login()
            AutomationSupport.liveSettings()
            val other = "NIFTY26OCT24000PE"
            kite.instruments += FakeKite.Ins(12_300_000, other, "NIFTY", AutomationSupport.expiryAfter(9), 24_000.0, "PE", 75)
            kite.position(other, 75, 100.0)
            kite.quote("NFO:$other", 100.0 - 3_000.0 / 75)
            check()
            assertEquals(SegmentLock.Trip.LIVE, LossBreaker.tripToday())
            assertTrue(mcxStopped())
        } finally { kite.close() }
    }

    @Test fun aRestartKeepsEachSegmentsStopsForTheDayAndTheNextDayForgetsThem() {
        at(day, 20, 0)
        paper(nse = 9_000.0, mcx = -6_500.0)
        check()
        LossBreaker.forgetForTest(); DayLockGuard.forgetForTest()
        assertTrue(LossBreaker.mcxTrippedToday())
        assertNotNull(DayLockGuard.active(false))
        assertTrue(mcxStopped())
        assertFalse(nseStopped())
        // The next day: nothing holds.
        at(day.plusDays(1), 9, 20)
        paper(nse = 0.0, mcx = 0.0)
        assertFalse(LossBreaker.mcxTrippedToday())
        assertFalse(mcxStopped())
        assertNull(DayLockGuard.active(false))
        assertNull(DayLockGuard.statusLine())
    }

    /** Tue 20 Oct (Dussehra): NSE shut, MCX's evening session open - the commodities' own limits work alone. */
    @Test fun anNseHolidayWithMcxOpen() {
        at(LocalDate.of(2026, 10, 20), 18, 30)
        paper(nse = 0.0, mcx = -6_000.0)
        check()
        assertTrue(LossBreaker.mcxTrippedToday())
        assertFalse(LossBreaker.trippedToday())
        assertFalse(nseStopped())
    }

    /** Tue 27 Oct, NIFTY's weekly expiry: the NSE paper lock is the NSE side's alone, as on any day. */
    @Test fun anExpiryDay() {
        at(LocalDate.of(2026, 10, 27), 15, 0)
        paper(nse = 8_100.0, mcx = -3_000.0)
        check()
        assertNotNull(DayLockGuard.active(false))
        assertNull(DayLockGuard.mcxRefusal())
        assertFalse(mcxStopped())
    }

    /** Undone in the timeline (back to the shared lock): one paper figure stops both, exactly as before. */
    @Test fun theSharedLockIsTheOldRule() {
        DayLockGuard.setSplit(false)
        assertFalse(AppSettings.load().mcxOwnLimits)
        at(day, 11, 0)
        paper(nse = 9_000.0, mcx = -1_500.0)          // the whole day +7,500: under the 8,000 lock
        check()
        assertNull(DayLockGuard.active(false))
        paper(nse = 3_000.0, mcx = -9_500.0)          // the whole day -6,500: the one paper limit
        check()
        assertTrue(LossBreaker.trippedToday())
        assertFalse(LossBreaker.mcxTrippedToday())
        assertTrue("one figure: MCX stops too", mcxStopped())
        val said = AutomationSupport.alerts().first { it.startsWith("Daily loss limit") }
        assertTrue(said, said.contains("Today's Paper P&L Rs -6,500") && !said.contains("MCX"))
    }

    /** Zerodha (live) is one account: its own lock reads Zerodha's P&L, never the paper split. */
    @Test fun liveIsUntouched() {
        val kite = FakeKite()
        try {
            AutomationSupport.niftyOptions(kite)
            kite.login()
            AutomationSupport.liveSettings()
            at(day, 11, 0)
            paper(nse = -100.0, mcx = 12_000.0)
            check()
            assertNull(DayLockGuard.refusal(true))
            assertNotNull(DayLockGuard.mcxRefusal())
            assertFalse(LossBreaker.liveDoorTripped())
        } finally { kite.close() }
    }

    /** Review, 21 Oct: while the app is in Live, the loss limits keep the one paper figure, as before: an MCX loss stops everything. */
    @Test fun inLiveTheLossLimitsKeepOnePaperFigure() {
        AutomationSupport.liveSettings()
        at(day, 19, 0)
        paper(nse = 0.0, mcx = -6_500.0)
        check()
        assertTrue(LossBreaker.trippedToday())
        assertEquals(SegmentLock.Trip.SHARED, LossBreaker.tripToday())
        assertTrue(nseStopped())
        assertTrue(mcxStopped())
        assertFalse("the commodities' own stop is not used then", LossBreaker.mcxTrippedToday())
    }

    /**
     * 21 Oct (merge review): a real position still open at Zerodha while the app is in Paper keeps the one paper figure, so a whole
     * paper day past the limit still stops the bot (which sells that live position), as before the split.
     */
    @Test fun anOpenLivePositionInPaperModeKeepsOnePaperFigure() {
        val kite = FakeKite()
        try {
            AutomationSupport.niftyOptions(kite)
            kite.login()
            val held = "NIFTY26OCT24000PE"
            kite.instruments += FakeKite.Ins(12_300_000, held, "NIFTY", AutomationSupport.expiryAfter(9), 24_000.0, "PE", 75)
            kite.position(held, 75, 100.0)
            kite.quote("NFO:$held", 100.0)          // flat at Zerodha: its own limit is not what stops it
            at(day, 13, 0)
            paper(nse = -3_500.0, mcx = -3_500.0)   // each part under its 6,000 limit; the whole paper day past it
            check()
            assertTrue(LossBreaker.trippedToday())
            assertEquals(SegmentLock.Trip.SHARED, LossBreaker.tripToday())
            assertTrue(nseStopped())
            assertTrue(mcxStopped())
        } finally { kite.close() }
    }

    /** Review: switching the split off never lifts a commodities lock reached today, nor does switching it on lift a shared stop. */
    @Test fun theSwitchNeverLiftsAStopMadeToday() {
        at(day, 18, 0)
        paper(nse = -3_000.0, mcx = 8_500.0)
        check()
        assertNotNull(DayLockGuard.mcxRefusal())
        DayLockGuard.setSplit(false)                     // the timeline's Undo: the shared figure (+5,500) is under the lock
        assertNotNull("the lock reached today still holds", DayLockGuard.mcxRefusal())
        paper(nse = 3_000.0, mcx = -9_500.0)             // shared: one figure, -6,500
        check()
        assertEquals(SegmentLock.Trip.SHARED, LossBreaker.tripToday())
        DayLockGuard.setSplit(true)
        assertTrue("a shared stop is never lifted by switching the split on", mcxStopped())
    }

    /** Review: after the NSE paper stop, Boss's own stop and then "start" lets the MCX arms trade again; NSE stays stopped. */
    @Test fun startAgainAfterTheNsePaperStopResumesOnlyMcx() {
        at(day, 11, 0)
        paper(nse = -7_000.0, mcx = 0.0)
        check()
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        assertTrue(mcxStopped())
        val said = runBlocking { Strategies.startAgain() }
        assertTrue(said, said.contains("The MCX paper bots trade again"))
        assertFalse(mcxStopped())
        assertTrue(nseStopped())
    }
}
