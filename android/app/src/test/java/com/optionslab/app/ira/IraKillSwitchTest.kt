package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Guard
import com.optionslab.app.security.GuardPin
import com.optionslab.app.security.PinLock
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.Command
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 18 Oct, review: the kill switch is never cleared through Jarvis without the PIN rule the Account guard page uses
 * ([GuardPin]), and a Jarvis setting change with no screen open writes only what it changed, so the guard's kill switch is
 * never written back off.
 */
class IraKillSwitchTest : RobolectricTest() {
    private fun stored(f: (AppSettings) -> AppSettings) = AppSettings.save(f(AppSettings()))

    // The guard's kill switch writer (started by Guard.onRefusal below) is done before the next test begins.
    @org.junit.After fun drain() { AppSettings.awaitKillWriter() }

    @Test fun theRuleAsksOnlyToLoosenWithAPinInLiveOrWithZerodha() {
        assertTrue(GuardPin.asks(looser = true, pinSet = true, live = true, zerodha = false))
        assertTrue(GuardPin.asks(looser = true, pinSet = true, live = false, zerodha = true))
        assertFalse("paper, no Zerodha", GuardPin.asks(looser = true, pinSet = true, live = false, zerodha = false))
        assertFalse("no PIN: nothing to ask", GuardPin.asks(looser = true, pinSet = false, live = true, zerodha = true))
        assertFalse("tightening never asks", GuardPin.asks(looser = false, pinSet = true, live = true, zerodha = true))
    }

    @Test fun jarvisCannotClearTheKillSwitchWhenThePinIsNeeded() {
        stored { it.copy(mode = "live", guardKill = true) }
        PinLock.setPin("246813".toCharArray())
        assertTrue(GuardPin.clearKillAsks(AppSettings.load().live))
        val (said, act) = runBlocking { IraActions.prepare(Command(Command.Kind.KILL_OFF)) }
        assertEquals(IraActions.KILL_OFF_PIN, said)
        assertNull("nothing to confirm", act)
        assertTrue("still on", AppSettings.load().guardKill)
    }

    @Test fun aPinSetBetweenTheQuestionAndTheYesStillStopsIt() {
        stored { it.copy(mode = "live", guardKill = true) }
        val (_, act) = runBlocking { IraActions.prepare(Command(Command.Kind.KILL_OFF)) }
        assertNotNull(act)
        PinLock.setPin("246813".toCharArray())
        assertEquals(IraActions.KILL_OFF_PIN, runBlocking { act!!() })
        assertTrue(AppSettings.load().guardKill)
    }

    @Test fun withNoPinJarvisClearsItAndTurningItOnNeverAsks() {
        stored { it.copy(mode = "live", guardKill = true) }
        val (_, off) = runBlocking { IraActions.prepare(Command(Command.Kind.KILL_OFF)) }
        assertEquals("Kill switch off.", runBlocking { off!!() })
        assertFalse(AppSettings.load().guardKill)
        PinLock.setPin("246813".toCharArray())
        val (_, on) = runBlocking { IraActions.prepare(Command(Command.Kind.KILL_ON)) }
        assertNotNull("turning it on never needs the PIN", on)
        runBlocking { on!!() }
        assertTrue(AppSettings.load().guardKill)
    }

    @Test fun jarvisCannotLoosenTheZerodhaGuardWithoutThePin() {
        stored { it.copy(mode = "live", guardDailyLoss = 5_000.0, guardPaperDailyLoss = 5_000.0) }
        PinLock.setPin("246813".toCharArray())
        val raise = Command(Command.Kind.SET_LIMIT, target = "DAILY_LOSS", level = 0.0)
        val (said, act) = runBlocking { IraActions.prepare(raise) }
        assertEquals(IraActions.LIMIT_PIN, said)
        assertNull(act)
        assertEquals(5_000.0, AppSettings.load().guardDailyLoss, 0.0)
        // Tightening never asks.
        val (_, tighter) = runBlocking { IraActions.prepare(Command(Command.Kind.SET_LIMIT, target = "DAILY_LOSS", level = 3_000.0)) }
        assertNotNull(tighter)
        // 25 Oct (merge review): the paper bots' loss limit asks the PIN to loosen on the Account guard page, so by voice too.
        val (paperSaid, paper) = runBlocking { IraActions.prepare(Command(Command.Kind.SET_LIMIT, target = "PAPER_DAILY_LOSS", level = 8_000.0)) }
        assertEquals(IraActions.LIMIT_PIN, paperSaid)
        assertNull(paper)
        assertEquals(5_000.0, AppSettings.load().guardPaperDailyLoss, 0.0)
        // Tightening it never asks.
        val (_, paperTighter) = runBlocking { IraActions.prepare(Command(Command.Kind.SET_LIMIT, target = "PAPER_DAILY_LOSS", level = 3_000.0)) }
        assertNotNull(paperTighter)
    }

    @Test fun aChangeWithNoScreenOpenKeepsAKillSwitchTheGuardSetMeanwhile() {
        stored { it.copy(guardDrawdownPct = 10.0) }
        IraActions.setStored { s ->
            // The guard turns the kill switch on between the read and the write.
            Guard.onRefusal(listOf("Drawdown limit: 15.0% from the peak (limit 10%)"))
            s.copy(theme = "dark")
        }
        val s = AppSettings.load()
        assertEquals("dark", s.theme)
        assertTrue("the guard's kill switch is never written back off", s.guardKill)
    }
}
