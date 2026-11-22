package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.Cleared
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.ira.Findings
import com.optionslab.ira.MarketBrain
import com.optionslab.ira.RateGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode

/**
 * 22 Nov 2026 (B6 review): the entry door's own checks.
 *
 *  - Item 4 ("entry checks at the door fail open"): the smart workers, the portfolio guard, the same-direction cap, the account stop rule
 *    and Boss's risk rules were each asked inside runCatching and a check that THREW let the entry through. Now one that throws refuses the
 *    entry while its live switch is on (EntryGate's rule: an entry never goes on a guess); in shadow (the switch off) the entry goes as
 *    before. Exits never come here.
 *  - Item 8 ("ENTRY writes have no limit on their wait at the RateGate"): a new entry's order waits for its turn in the one request budget
 *    [Broker.ENTRY_TURN_WAIT_MS] at most, then is refused at the app's own door with nothing sent; every other write waits as before.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class EntryDoorChecksTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private fun order(tag: String = "iraorb", side: Kite.Side = Kite.Side.BUY) =
        Kite.Order("NIFTY26OCT24500CE", side, 75, 75, "MIS", "MARKET", null, 0.05, "NFO", tag)
    private val boom = IllegalStateException("not readable")

    @Before fun up() { kite = FakeKite(); kite.login(); AutomationSupport.liveSettings() }

    @After fun down() {
        SmartWorkers.testDoorThrows = null; PortfolioHost.testDoorThrows = null; ExposureCapHost.testDoorThrows = null
        AccountStopHost.testDoorThrows = null; BossRiskHost.testDoorThrows = null
        kite.close()
        assertEquals(emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun recheck() = Broker.entryRecheck(order(), arm = "orb", intended = 100.0, stop = 70.0)

    private fun assertRefusedFor(what: String) {
        val why = recheck()
        assertTrue("refused while its switch is on: $why", why != null && why.startsWith("refused: $what could not be checked (IllegalStateException)"))
        // Through the door itself: refused at the app's own door, definite, nothing reaches Zerodha; an exit still goes.
        try { runBlocking { Broker.placeOrder(order(), arm = "orb", intended = 100.0, verdict = Cleared.live(order(), arm = "orb", stop = 70.0), stop = 70.0) }; fail("an entry went on a check that could not be read") }
        catch (e: Broker.KiteError) {
            assertEquals("entry_gate", e.type)
            assertTrue(e.message, e.message!!.contains("$what could not be checked"))
            assertTrue(Broker.definite(e))
        }
        assertTrue("nothing sent", kite.placed.isEmpty())
        runBlocking { Broker.placeOrder(order(side = Kite.Side.SELL), exit = true) }
        assertEquals("an exit never asks the door", 1, kite.placed.size)
        kite.requests.clear()
    }

    @Test fun withNothingThrowingTheDoorLetsTheEntryGo() {
        assertNull(recheck())
    }

    @Test fun theSmartWorkersThatCannotBeReadRefuseWhileTheyActAndLetGoInShadow() {
        SmartWorkers.testDoorThrows = boom
        assertTrue("news pauses and the unreliable-data reaction act by default", SmartWorkers.doorActs())
        assertRefusedFor("the smart workers")
        SmartWorkers.setPause(MarketBrain.Cause.NEWS, MarketBrain.Mode.SHADOW)
        SmartWorkers.setReaction(null, Findings.Reaction.DATA_UNRELIABLE, Findings.Mode.SHADOW)
        assertFalse(SmartWorkers.doorActs())
        assertNull("in shadow the entry goes as before", recheck())
    }

    @Test fun thePortfolioGuardThatCannotBeReadRefusesWhileOnAndLetsGoWhenOff() {
        PortfolioHost.setOn(true)
        PortfolioHost.testDoorThrows = boom
        assertRefusedFor("the portfolio guard")
        PortfolioHost.setOn(false, pinConfirmed = true)
        assertNull(recheck())
        PortfolioHost.setOn(true)
    }

    @Test fun theSameDirectionCapThatCannotBeReadRefusesWhileLiveAndLetsGoInShadow() {
        ExposureCapHost.testDoorThrows = boom
        assertNull("in shadow (the default) the entry goes as before", recheck())
        ExposureCapHost.setLiveOn(true)
        assertRefusedFor("the same-direction cap")
        ExposureCapHost.setLiveOn(false, pinConfirmed = true)
        assertNull(recheck())
    }

    @Test fun theAccountStopRuleThatCannotBeReadRefusesWhileOnAndLetsGoInShadow() {
        AccountStopHost.testDoorThrows = boom
        assertNull("in shadow (the default) the entry goes as before", recheck())
        AccountStopHost.setOn(true)
        assertRefusedFor("the account stop rule")
        AccountStopHost.setOn(false, pinConfirmed = true)
        assertNull(recheck())
    }

    @Test fun bossesRiskRulesThatCannotBeReadRefuseWhileAnyIsLiveAndLetGoInShadow() {
        BossRiskHost.testDoorThrows = boom
        assertNull("all in shadow (the default) the entry goes as before", recheck())
        BossRiskHost.setLive(BossRiskHost.Rule.WEEKLY, true)
        assertTrue(BossRiskHost.liveActs())
        assertRefusedFor("your risk rules")
        BossRiskHost.setLive(BossRiskHost.Rule.WEEKLY, false, pinConfirmed = true)
        assertNull(recheck())
    }

    /** The door's own wrapper: a check that throws outside its host's own guard (its object not loading), its switch unreadable too. */
    @Test fun aCheckThatThrowsWithItsSwitchUnreadableRefuses() {
        assertEquals("refused: x could not be checked (ExceptionInInitializerError); the entry was not sent",
            Broker.doorPart("x", { throw ExceptionInInitializerError() }) { throw ExceptionInInitializerError() })
        assertNull("its switch off: in shadow", Broker.doorPart("x", { false }) { throw IllegalStateException() })
        assertEquals("refused: x could not be checked (IllegalStateException); the entry was not sent",
            Broker.doorPart("x", { true }) { throw IllegalStateException() })
        assertEquals("a check's own refusal is passed on", "refused: no", Broker.doorPart("x", { true }) { "refused: no" })
        assertNull(Broker.doorPart("x", { true }) { null })
    }

    // ---- item 8: a new entry's turn in the request budget --------------------------------------------------------------------------

    @Test fun aNewEntryWaitsForItsTurnAtMostThreeSecondsThenIsRefusedWithNothingSent() = runTest {
        val gate = RateGate(perSecond = 8, clock = { testScheduler.currentTime })
        // Exits keep the budget full the whole time (a busy minute): the entries' lane never finds room.
        val busy = launch { while (isActive) { gate.tryTake(RateGate.Priority.EXIT); delay(10) } }
        delay(100)
        val t0 = testScheduler.currentTime
        val e = try { Broker.writeTurn(gate, RateGate.Priority.ENTRY, entry = true); null } catch (e: Broker.KiteError) { e }
        val waited = testScheduler.currentTime - t0
        assertEquals("entry_gate", e!!.type)
        assertTrue(e.message, e.message!!.contains("the entry was not sent"))
        assertTrue("refused at the app's own door: definite, nothing reached Zerodha", Broker.definite(e))
        assertTrue(CheaperStrike.appRefused(e))
        assertFalse("never Zerodha's refusal", ArmOrderLabels.zerodhaRefused(e))
        assertTrue("waited $waited ms", waited in Broker.ENTRY_TURN_WAIT_MS..Broker.ENTRY_TURN_WAIT_MS + 50)
        // An exit (or a stop, a cancel) still waits as long as it takes - never refused for the wait.
        var exitIn = -1L
        val exit = launch { Broker.writeTurn(gate, RateGate.Priority.EXIT, entry = false); exitIn = testScheduler.currentTime - t0 }
        delay(20_000)
        busy.cancel()
        exit.join()
        assertTrue("the exit got its turn ($exitIn ms)", exitIn >= 0)
        // A gate with room: the entry goes at once.
        val free = RateGate(perSecond = 8, clock = { testScheduler.currentTime })
        val t1 = testScheduler.currentTime
        Broker.writeTurn(free, RateGate.Priority.ENTRY, entry = true)
        assertEquals(0L, testScheduler.currentTime - t1)
    }

    @Test fun anEntryThatGetsItsTurnWithinTheWaitGoes() = runTest {
        val gate = RateGate(perSecond = 8, clock = { testScheduler.currentTime })
        repeat(8) { gate.tryTake(RateGate.Priority.EXIT) }                  // the second's whole budget just used
        val t0 = testScheduler.currentTime
        Broker.writeTurn(gate, RateGate.Priority.ENTRY, entry = true)        // room again once the second rolls on
        assertTrue(testScheduler.currentTime - t0 in 1..Broker.ENTRY_TURN_WAIT_MS)
    }
}
