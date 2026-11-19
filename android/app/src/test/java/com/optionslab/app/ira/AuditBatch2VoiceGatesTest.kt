package com.optionslab.app.ira

import com.optionslab.app.data.Paper
import com.optionslab.app.data.Strategies
import com.optionslab.app.security.PinLock
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.sandbox.Quote
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/**
 * 19 Nov 2026 (audit batch 2, item 6): lifting "Stop for today" in Live takes the PIN on Home, and is refused by the quick-settings
 * tile and by voice (the timeline's Undo took its own PIN); "cancel all orders" by voice leaves the protective stop-loss orders
 * working; the voice service counts a lock it cannot read as locked.
 */
class AuditBatch2VoiceGatesTest : RobolectricTest() {
    @Before fun up() { Paper.reset(BigDecimal("400000")) }

    @After fun down() { AutomationSupport.liveSettings(live = false) }

    private fun stopped() = runBlocking { Strategies.stoppedToday() }

    @Test fun inLiveWithAPinLiftingStopForTodayTakesThePinOnlyTheTimelinesUndoHadItsOwn() {
        PinLock.setPin("482915".toCharArray())
        AutomationSupport.liveSettings()
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        assertTrue(stopped())
        assertTrue(Strategies.liftAsksPin())
        // The tile's call (no PIN): refused, said to open the app.
        assertEquals(Strategies.LIFT_PIN, runBlocking { Strategies.startAgain() })
        assertTrue("still stopped", stopped())
        // The tile tells Boss how (a notice), never a silent log line; any other answer posts nothing.
        assertEquals(com.optionslab.app.work.BotTile.TILE_PIN_TEXT, com.optionslab.app.work.BotTile.startNotice(Strategies.LIFT_PIN))
        assertNull(com.optionslab.app.work.BotTile.startNotice("Index bots running: armed strategies start at their times."))
        // Home, after the PIN.
        assertTrue(runBlocking { Strategies.startAgain(pinConfirmed = true) }.startsWith("Index bots running"))
        assertFalse(stopped())
        // The change timeline's Undo ([log] false) took the PIN itself.
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        assertTrue(runBlocking { Strategies.startAgain(log = false) }.startsWith("Index bots running"))
        assertFalse(stopped())
    }

    @Test fun onPaperOrWithNoPinSetLiftingStillGoesAtOnce() {
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        assertFalse("Paper: nothing to ask", Strategies.liftAsksPin())
        assertTrue(runBlocking { Strategies.startAgain() }.startsWith("Index bots running"))
        AutomationSupport.liveSettings()
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        assertFalse("Live with no app PIN: nothing to ask", Strategies.liftAsksPin())
        assertTrue(runBlocking { Strategies.startAgain() }.startsWith("Index bots running"))
        // Not stopped at all: nothing is asked either.
        PinLock.setPin("482915".toCharArray())
        assertTrue(runBlocking { Strategies.startAgain() }.startsWith("Index bots running"))
    }

    @Test fun byVoiceInLiveTheIndexStopIsNeverLifted() {
        PinLock.setPin("482915".toCharArray())
        AutomationSupport.liveSettings()
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        val (said, act) = runBlocking { IraActions.prepare(com.optionslab.ira.Ask.parse("start the bots again").command!!) }
        assertTrue(said, said == IraActions.LIFT_PIN || said.contains(IraActions.LIFT_PIN_SHORT))
        // At most the commodity side (paper) is offered; the index side stays stopped.
        act?.let { runBlocking { it() } }
        assertTrue("the index bots stay stopped", stopped())
    }

    @Test fun cancelAllOrdersByVoiceLeavesTheStopLossOrdersWorking() {
        val exp = com.optionslab.app.data.Market.today().plusDays(6)
        val sym = "NIFTY" + exp.format(java.time.format.DateTimeFormatter.ofPattern("ddMMMyy", java.util.Locale.ENGLISH)).uppercase(java.util.Locale.ENGLISH) + "25000CE"
        val c = Paper.Contract(sym, "NIFTY", exp, 25_000.0, Right.CE, 65, "NSE_FO|$sym", "NFO")
        val q = Quote(100.0, bid = 99.9, ask = 100.1)
        runBlocking {
            assertTrue(Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, q).ok)
            assertTrue(Paper.place(c, "SELL", 1, "SL-M", "MIS", null, 80.0, q).ok)        // the position's stop
            assertTrue(Paper.place(c, "BUY", 1, "LIMIT", "MIS", 50.0, null, q).ok)        // a plain working order
        }
        fun working() = runBlocking { Paper.snapshot() }.orders.orders.filter { com.optionslab.ira.AppFacts.isOpen(it.status) }
        assertEquals(2, working().size)
        val (said, act) = runBlocking { IraActions.prepare(com.optionslab.ira.Ask.parse("cancel all open orders").command!!) }
        assertEquals("cancel 1 open order (1 stop-loss order kept working)", said)
        val done = runBlocking { act!!() }
        assertTrue(done, done.contains(IraActions.CANCEL_KEEPS_STOPS))
        assertEquals("only the stop is left", listOf("SL-M"), working().map { it.priceType })
        // Nothing but stops left: nothing to cancel, and it says why.
        val (again, none) = runBlocking { IraActions.prepare(com.optionslab.ira.Ask.parse("cancel all open orders").command!!) }
        assertNull(none)
        assertTrue(again, again.contains("besides your stop-loss orders"))
    }

    @Test fun stopTypesAreTheProtectiveOnes() {
        for (t in listOf("SL", "SL-M", "sl-m", " SL ")) assertTrue(t, IraActions.isStopType(t))
        for (t in listOf("LIMIT", "MARKET", "", null)) assertFalse("$t", IraActions.isStopType(t))
        // Protective: a stop that would close what is held; a stop entry (nothing held, or adding to it) is not.
        assertTrue(IraActions.protectiveStop("SL-M", "SELL", 65))
        assertTrue(IraActions.protectiveStop("SL", "BUY", -50))
        assertFalse("a stop entry: nothing held", IraActions.protectiveStop("SL", "BUY", 0))
        assertFalse("it would add to the long", IraActions.protectiveStop("SL", "BUY", 65))
        assertFalse(IraActions.protectiveStop("LIMIT", "SELL", 65))
        assertTrue("the positions unread: a stop is never cancelled on a guess", IraActions.protectiveStop("SL-M", "BUY", null))
    }

    @Test fun aLockTheVoiceServiceCannotReadCountsAsLocked() {
        assertTrue(JarvisVoice.keyguardLocked { throw IllegalStateException("no keyguard") })
        assertTrue("no keyguard service", JarvisVoice.keyguardLocked { null })
        assertTrue(JarvisVoice.keyguardLocked { true })
        assertFalse(JarvisVoice.keyguardLocked { false })
    }
}
