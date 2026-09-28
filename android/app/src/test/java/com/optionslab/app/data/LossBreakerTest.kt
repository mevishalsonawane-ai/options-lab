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
}
