package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.AutomationSupport.NIFTY_CE
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.strategy.OptionType
import com.optionslab.engine.strategy.Position
import com.optionslab.engine.strategy.RunMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * The daily loss limit over everything the bots trade ([LossBreaker]), against the fake Kite: it trips on the
 * live account's P&L, stops the bot for the day, sells what running strategies hold, and says so once.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LossBreakerTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val other = "NIFTY26OCT24000PE"

    @Before fun up() {
        kite = FakeKite()
        AutomationSupport.niftyOptions(kite)
        kite.instruments += FakeKite.Ins(12_300_000, other, "NIFTY", AutomationSupport.expiryAfter(9), 24_000.0, "PE", 75)
        kite.quote("NFO:$NIFTY_CE", 100.0, 99.95, 100.05)
        com.optionslab.app.testing.Background.clearAlerts()            // and the banner's 20 s repeat filter
    }

    @After fun down() {
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun check() = runBlocking { LossBreaker.check(context) }

    /** A hand-made position losing [loss] rupees today (75 units bought at 100, now lower). */
    private fun losing(loss: Double) {
        kite.position(other, 75, 100.0)
        kite.quote("NFO:$other", 100.0 - loss / 75)
    }

    @Test fun aLiveLossPastTheLimitStopsTheBotForToday() {
        kite.login()
        AutomationSupport.liveSettings()
        losing(3_000.0)                                       // the default limit is Rs 2,000
        check()
        assertTrue(LossBreaker.trippedToday())
        assertTrue(runBlocking { Strategies.stoppedToday() })
        val said = AutomationSupport.alerts().first { it.startsWith("Daily loss limit") }
        assertTrue(said, said.contains("Today's Live P&L Rs -3,000 reached the Rs 2,000 daily loss limit"))
        assertTrue("the breaker only stops the bots: it sends nothing for a hand-made position", kite.writes.isEmpty())
    }

    /**
     * 16 Oct (Boss's diagnostics: "GET /portfolio/positions -> FAILED ... (105388 ms)"): the breaker runs before the arms' exits
     * and entries in every pass. A hung read of Zerodha's positions holds it 5 s at most, and the read itself gives up within
     * its budget; the paper limit is still checked meanwhile.
     */
    @Test fun aHungPositionsReadHoldsThePassFiveSecondsAtMost() {
        kite.login()
        kite.hang += "/portfolio/positions"
        val t0 = System.currentTimeMillis()
        check()
        val held = System.currentTimeMillis() - t0
        assertTrue("held $held ms", held < Broker.READ_BUDGET_MS + 2_500)
        assertFalse(LossBreaker.trippedToday())
        // The read on its own: cut at its budget, never the old 20 s + 60 s per try.
        val t1 = System.currentTimeMillis()
        val r = runCatching { runBlocking { Broker.positionBook() } }
        assertTrue(r.isFailure)
        assertTrue("took ${System.currentTimeMillis() - t1} ms", System.currentTimeMillis() - t1 < Broker.READ_BUDGET_MS + 2_500)
        // An order is never cut by the read budget; the big downloads keep their limits; a 429 retry only within the budget.
        assertEquals(null, Broker.readBudgetMs("POST", "/orders/regular", readOnly = false))
        assertEquals(null, Broker.readBudgetMs("GET", "/instruments", readOnly = false))
        assertEquals(null, Broker.readBudgetMs("GET", "/instruments/historical/256265/minute", readOnly = false))
        assertEquals(Broker.READ_BUDGET_MS, Broker.readBudgetMs("GET", "/portfolio/positions", readOnly = false))
        assertEquals(Broker.READ_BUDGET_MS, Broker.readBudgetMs("POST", "/charges/orders", readOnly = true))
        assertEquals(null, Broker.readBudgetMs("GET", "/user/profile", readOnly = false, viaRelay = true))
        assertTrue(Broker.retryFits(Broker.READ_BUDGET_MS, 1_000, 1_000))
        assertFalse(Broker.retryFits(Broker.READ_BUDGET_MS, 3_500, 2_000))
        assertTrue(Broker.retryFits(null, 60_000, 3_000))
    }

    @Test fun aLossShortOfTheLimitChangesNothing() {
        kite.login()
        AutomationSupport.liveSettings()
        losing(1_500.0)
        check()
        assertFalse(LossBreaker.trippedToday())
        assertFalse(runBlocking { Strategies.stoppedToday() })
        assertTrue(AutomationSupport.alerts().isEmpty())
    }

    @Test fun exactlyAtTheLimitTrips() {
        kite.login()
        AutomationSupport.liveSettings()
        losing(2_000.0)
        check()
        assertTrue(LossBreaker.trippedToday())
    }

    @Test fun aLimitOfZeroIsOff() {
        kite.login()
        AutomationSupport.liveSettings()
        SecurePrefs.put("g.loss", 0.0)
        losing(50_000.0)
        check()
        assertFalse(LossBreaker.trippedToday())
    }

    @Test fun theLiveAccountIsWatchedInPaperModeToo() {
        kite.login(live = false)                              // the app shows Paper; the Zerodha position is still real
        losing(3_000.0)
        check()
        assertTrue(LossBreaker.trippedToday())
    }

    @Test fun loggedOutOnlyThePaperAccountIsWatched() {
        losing(3_000.0)                                       // at Zerodha, but no session to read it with
        check()
        assertFalse(LossBreaker.trippedToday())
        assertTrue("nothing is read without a session", kite.requests.isEmpty())
    }

    @Test fun aFlatPaperAccountDoesNotTrip() {
        SecurePrefs.put("g.pLoss", 1.0)
        check()
        assertFalse(LossBreaker.trippedToday())
    }

    @Test fun trippingSellsWhatARunningLiveStrategyHoldsAndTellsOnce() {
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        val id = runBlocking {
            assertEquals(null, Strategies.save(AutomationSupport.strategy("Breaker test", Position.B to OptionType.CE)))
            val id = Strategies.all().single().def.id
            Strategies.setLiveEnabled(id, true)
            Strategies.start(id, RunMode.LIVE, "manual", confirmedByOwner = true, compromised = false)
            id
        }
        assertEquals("BUY", kite.placed.single().form["transaction_type"])
        losing(3_000.0)
        check()
        val sells = kite.placed.filter { it.form["transaction_type"] == "SELL" }
        assertEquals("the run's leg is sold once", 1, sells.size)
        assertEquals(mapOf("tradingsymbol" to NIFTY_CE, "quantity" to "75", "order_type" to "MARKET", "product" to "NRML", "tag" to "iraalgostrat"),
            sells.single().form.filterKeys { it in setOf("tradingsymbol", "quantity", "order_type", "product", "tag") })
        assertFalse(runBlocking { Strategies.all().single { it.def.id == id }.running })
        // The next pass (still past the limit) neither sells again nor repeats the alert.
        check()
        assertEquals(1, kite.placed.count { it.form["transaction_type"] == "SELL" })
        // Said once: one banner.
        assertEquals(1, com.optionslab.app.work.Alerts.queue.value.count { it.text.startsWith("Today's Live P&L") })
    }

    @Test fun onceTrippedAScheduledRunStillStartsNothingAndStaysStopped() {
        kite.login()
        AutomationSupport.liveSettings()
        losing(3_000.0)
        check()
        // The loss recovers during the day: the bot stays stopped until tomorrow.
        kite.quote("NFO:$other", 150.0)
        check()
        assertTrue(LossBreaker.trippedToday())
        assertTrue(runBlocking { Strategies.stoppedToday() })
    }

    // ---- 19 Nov 2026 (Boss's choice, "Go"): a loss limit switched off or raised with the PIN lifts the stop it made today ----

    private fun limit(rupees: Double) = SecurePrefs.put("g.loss", rupees)
    private fun lift(old: Double, new: Double, pin: Boolean) = runBlocking { LossBreaker.liftForLoosened(LossBreaker.Limit.ZERODHA, old, new, pin) } == LossBreaker.Lift.LIFTED
    private fun startAgain() = runBlocking { Strategies.startAgain() }
    private fun trippedAt5k(loss: Double) {
        kite.login()
        AutomationSupport.liveSettings()
        limit(5_000.0)
        losing(loss)
        check()
        assertTrue(LossBreaker.trippedToday())
        val said = startAgain()
        assertTrue(said, said.startsWith("Not started"))
    }

    @Test fun switchedOffWithThePinTodaysStopIsLiftedAndStartWorks() {
        trippedAt5k(6_000.0)
        limit(0.0)                                              // the switch, then the lift (the settings page's order)
        assertTrue(lift(5_000.0, 0.0, pin = true))
        assertFalse(LossBreaker.trippedToday())
        val said = startAgain()
        assertTrue(said, said.contains("running"))
        check()
        assertFalse("off: the next look never stops them again", LossBreaker.trippedToday())
    }

    @Test fun withoutThePinNothingIsLifted() {
        trippedAt5k(6_000.0)
        assertEquals(LossBreaker.Lift.NO_PIN, runBlocking { LossBreaker.liftForLoosened(LossBreaker.Limit.ZERODHA, 5_000.0, 0.0, false) })
        assertTrue(LossBreaker.trippedToday())
        assertTrue(startAgain().startsWith("Not started"))
    }

    @Test fun raisedPastTodaysLossItIsLifted() {
        trippedAt5k(9_000.0)
        limit(10_000.0)
        assertTrue(lift(5_000.0, 10_000.0, pin = true))
        val said = startAgain()
        assertTrue(said, said.contains("running"))
        check()
        assertFalse("inside the new limit: not stopped again", LossBreaker.trippedToday())
    }

    @Test fun raisedButTodaysLossIsStillPastItTheStopStays() {
        trippedAt5k(12_000.0)
        limit(10_000.0)
        assertEquals(LossBreaker.Lift.STILL_PAST, runBlocking { LossBreaker.liftForLoosened(LossBreaker.Limit.ZERODHA, 5_000.0, 10_000.0, true) })
        assertTrue(LossBreaker.trippedToday())
        assertTrue(startAgain().startsWith("Not started"))
    }

    @Test fun loweredOrSwitchedOnNeverLiftsAnything() {
        trippedAt5k(6_000.0)
        assertFalse(lift(5_000.0, 2_000.0, pin = true))
        assertFalse(lift(0.0, 10_000.0, pin = true))
        // Another limit's change (the paper bots') never lifts Zerodha's stop.
        assertEquals(LossBreaker.Lift.OTHER_LIMIT, runBlocking { LossBreaker.liftForLoosened(LossBreaker.Limit.PAPER, 10_000.0, 0.0, true) })
        // The settings page's memory-only look: may lift when this limit is loosened, never when lowered or another limit.
        assertTrue(LossBreaker.mayLift(LossBreaker.Limit.ZERODHA, 5_000.0, 0.0))
        assertTrue(LossBreaker.mayLift(LossBreaker.Limit.ZERODHA, 5_000.0, 10_000.0))
        assertFalse(LossBreaker.mayLift(LossBreaker.Limit.ZERODHA, 5_000.0, 2_000.0))
        assertFalse(LossBreaker.mayLift(LossBreaker.Limit.PAPER, 10_000.0, 0.0))
        assertTrue(LossBreaker.trippedToday())
    }

    /**
     * The paper bots' limit: a stop made on the NSE paper day (commodities on their own limits) lifts when the limit is raised past
     * that day, not when it is raised short of it.
     */
    @Test fun thePaperBotsLimitRaisedPastTheNsePaperDayLiftsItsStop() {
        LossBreaker.testPaperDay = { LossBreaker.PaperDay(-12_000.0, listOf("NFO" to -12_000.0), emptyList(), listOf(other to -12_000.0)) }
        LossBreaker.testDayFigure = { if (it == LossBreaker.Limit.PAPER) -12_000.0 else null }
        try {
            check()
            assertTrue(LossBreaker.trippedToday())
            assertTrue("${LossBreaker.tripToday()}", LossBreaker.tripToday() in setOf(com.optionslab.ira.SegmentLock.Trip.PAPER, com.optionslab.ira.SegmentLock.Trip.SHARED))
            assertEquals("-12k is still past 11k", LossBreaker.Lift.STILL_PAST, runBlocking { LossBreaker.liftForLoosened(LossBreaker.Limit.PAPER, 10_000.0, 11_000.0, true) })
            assertFalse("Zerodha's limit is not the one that made it", lift(2_000.0, 0.0, pin = true))
            SecurePrefs.put("g.pLoss", 20_000.0)
            assertEquals(LossBreaker.Lift.LIFTED, runBlocking { LossBreaker.liftForLoosened(LossBreaker.Limit.PAPER, 10_000.0, 20_000.0, true) })
            val said = startAgain()
            assertTrue(said, said.contains("running"))
        } finally {
            LossBreaker.testPaperDay = null
            LossBreaker.testDayFigure = null
        }
    }

    /**
     * 20 Nov 2026 (final review, items 2 and 3): a Zerodha stop whose stop of the bots never reached their book (its write failed) - the
     * lift writes Boss's own stop, so nothing runs before he starts it; the commodities, which that stop reached, stay stopped too.
     */
    @Test fun aLiftWithNoLossStopInTheBookStillLeavesTheBotsStoppedAsBosss() {
        kite.login()
        AutomationSupport.liveSettings()
        limit(5_000.0)
        val today = com.optionslab.app.data.Market.today()
        SecurePrefs.put("breaker.day.kind", com.optionslab.ira.SegmentLock.encodeTrip(today, com.optionslab.ira.SegmentLock.Trip.LIVE))
        SecurePrefs.put("breaker.day", today.toString())
        LossBreaker.forgetForTest()
        assertEquals(null, runBlocking { Strategies.stoppedWhy() })
        limit(0.0)
        assertTrue(lift(5_000.0, 0.0, pin = true))
        assertEquals("stopped as yours", com.optionslab.ira.DayStop.Why.BOSS, runBlocking { Strategies.stoppedWhy() })
        assertEquals("the commodities stay stopped", com.optionslab.ira.DayStop.Why.BOSS, runBlocking { Strategies.mcxStoppedWhy() })
        val said = startAgain()
        assertTrue(said, said.contains("running"))
    }

    /** Item 3: a commodity stop already there (the tile's, Jarvis's) keeps its reason and its selling after a lift. */
    @Test fun anEarlierCommodityStopKeepsItsReasonAndSelling() {
        trippedAt5k(6_000.0)
        runBlocking { Strategies.stopCommodities(sell = true, why = com.optionslab.ira.DayStop.Why.TILE, log = false) }
        limit(0.0)
        assertTrue(lift(5_000.0, 0.0, pin = true))
        assertEquals(com.optionslab.ira.DayStop.Why.TILE, runBlocking { Strategies.mcxStoppedWhy() })
        assertTrue("still selling", runBlocking { Strategies.mcxStopSells() })
    }

    /** Item 4: today past the OTHER limit too (the paper bots'): not lifted - the next look would stop the bots again at once. */
    @Test fun noLiftWhileTheOtherLimitIsPastToo() {
        LossBreaker.testPaperDay = { LossBreaker.PaperDay(-12_000.0, listOf("NFO" to -12_000.0), emptyList(), emptyList()) }
        try {
            trippedAt5k(6_000.0)
            limit(0.0)
            assertEquals(LossBreaker.Lift.OTHER_PAST, runBlocking { LossBreaker.liftForLoosened(LossBreaker.Limit.ZERODHA, 5_000.0, 0.0, true) })
            assertTrue(LossBreaker.trippedToday())
        } finally {
            LossBreaker.testPaperDay = null
        }
    }
}
