package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.mcx.McxContract
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 23 Nov 2026 (B6 follow-ups, item 1): the MCX expiry exit ([McxGuard]) against Kite's unsure answer ([Broker.KiteUnsure]: its
 * GeneralException with nothing in the order book after the door's ~4 s of looks). Before, the exit ran a second ~4 s search of its own in
 * the contract's exit turn; now it goes straight on - the contract's gap holds it ([SquareOffExit.GAP_MS]), and the next pass reads the
 * books again. Against the fake Kite: a GOLDM future 3 days from expiry (delivery-settled: due 5 trading days before), one lot held.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class McxGuardUnsureTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val sym = "GOLDM26NOVFUT"

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        Market.testClock = java.time.Clock.fixed(AutomationSupport.tradingDayAt(14, 0).toInstant(), com.optionslab.engine.IST)
        val today = Market.today()
        // The day's MCX contract list in memory, as the app's own read leaves it (never the network here).
        val gold = McxContract(44_000_001, 440_001, sym, "GOLDM", today.plusDays(3), 0.0, null, 1.0, 10)
        McxMarket::class.java.getDeclaredField("mem").apply { isAccessible = true }.set(McxMarket, today.toString() to listOf(gold))
        McxMarket.markLive()
        kite.position(sym, 1, 75_000.0, product = "NRML", exchange = "MCX")
        kite.quote("MCX:$sym", 75_000.0, 74_999.0, 75_001.0)
        kite.requests.clear()
    }

    @After fun down() {
        runCatching { McxMarket::class.java.getDeclaredField("mem").apply { isAccessible = true }.set(McxMarket, null) }
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun run() = runBlocking { McxGuard.maybeRun(context, AppSettings.load()) }
    private fun exits() = kite.placed.filter { it.form["tradingsymbol"] == sym && it.form["transaction_type"] == "SELL" }.map { it.form["quantity"] }

    @Test fun anUnsureAnswerToTheMcxExpiryExitKeepsItsGapWithoutASecondSearch() {
        assertTrue("open now", McxMarket.isOpen())
        SquareOffExit.resetForTest()                                       // the gap on, as it ships
        kite.nextPlace(reply = FakeKite.Reply.GENERAL_EXCEPTION)
        val searches = Broker.callerSearchesForTest.get()
        run()
        assertEquals("one exit sent", listOf("1"), exits())
        assertEquals("no second search of its own", searches, Broker.callerSearchesForTest.get())
        assertTrue("the contract's gap holds it", SquareOffExit.inGap("MCX", sym, "NRML"))
        run()
        assertEquals("nothing more within the gap", 1, exits().size)
    }

    /** A plain lost reply (Kite's NetworkException: never looked for in the door) is still looked for by the exit itself. */
    @Test fun aLostReplyIsStillLookedForByTheExit() {
        kite.nextPlace(reply = FakeKite.Reply.NETWORK_EXCEPTION)
        val searches = Broker.callerSearchesForTest.get()
        run()
        assertEquals(listOf("1"), exits())
        assertEquals("its own look", searches + 1, Broker.callerSearchesForTest.get())
    }
}
