package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.mcx.McxContract
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 23 Nov 2026 (B6-H round 2, WHO CANCELLED A STOP): the MCX expiry exit ([McxGuard]) cancels the resting orders on a contract before its
 * sale; those cancels are marked the app's own in the saved strategies' book first ([Strategies.markOwnCancels]), so a strategy's stop among
 * them is never read as Boss's, and a cancel Zerodha refused outright is taken back. (The saved strategies trade NFO contracts today - their
 * venue lists only NFO - so this guards the day they trade MCX.) Against the fake Kite: a GOLDM future due for its expiry exit, one lot held,
 * a strategy-tagged SL resting on it.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class McxGuardStrategyStopTest : RobolectricTest() {
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
        val gold = McxContract(44_000_001, 440_001, sym, "GOLDM", today.plusDays(3), 0.0, null, 1.0, 10)
        McxMarket::class.java.getDeclaredField("mem").apply { isAccessible = true }.set(McxMarket, today.toString() to listOf(gold))
        McxMarket.markLive()
        kite.position(sym, 1, 75_000.0, product = "NRML", exchange = "MCX")
        kite.quote("MCX:$sym", 75_000.0, 74_999.0, 75_001.0)
    }

    @After fun down() {
        runCatching { McxMarket::class.java.getDeclaredField("mem").apply { isAccessible = true }.set(McxMarket, null) }
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun run() = runBlocking { McxGuard.maybeRun(context, AppSettings.load()) }

    /** A strategy's SL resting on the contract (its book not read yet, so every id may be one of its stops). */
    private fun strategyStop(): String = kite.restingInKite(sym, "SELL", 1, 74_000.0, type = "SL", trigger = 74_050.0, tag = "iraalgostrat",
        product = "NRML", exchange = "MCX")

    @Test fun theMcxExpiryExitsCancelOfAStrategyStopIsMarkedTheAppsOwn() {
        assertTrue("open now", McxMarket.isOpen())
        val sl = strategyStop()
        run()
        assertEquals("taken out before the sale", "CANCELLED", kite.order(sl).status)
        assertTrue("marked the app's own, never Boss's", runBlocking { Strategies.ownCancelForTest(sl) })
    }

    @Test fun aCancelZerodhaRefusedOutrightIsTakenBack() {
        val sl = strategyStop()
        kite.beforeNext("/orders/regular/$sl") { kite.order(sl).status = "CANCELLED" }   // gone already: the cancel is refused
        run()
        assertFalse("not the app's cancel", runBlocking { Strategies.ownCancelForTest(sl) })
    }
}
