package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * 20 Oct, enhancement round 1: the account stop rule at the entry door (idea 4), the 15-second stop check's drift guard
 * (idea 6), the one shared "is anything held?" answer (idea 7) and the cap's "only logged" count (idea 9).
 */
class EnhanceRound1Test : RobolectricTest() {
    @Before fun up() { AccountStopHost.resetForTest(); WatchClock.resetForTest() }
    @After fun down() { Market.testClock = null; AccountStopHost.resetForTest(); WatchClock.resetForTest() }

    /** The clock at [h]:[m] IST on the next trading day (still ticking). */
    private fun session(h: Int, m: Int) {
        var d = LocalDate.now(com.optionslab.engine.IST).plusDays(1)
        while (!Market.isTradingDay(d)) d = d.plusDays(1)
        val at = d.atTime(h, m).atZone(com.optionslab.engine.IST).toInstant()
        Market.testClock = java.time.Clock.offset(java.time.Clock.system(com.optionslab.engine.IST), java.time.Duration.between(java.time.Instant.now(), at))
    }

    private fun order(tag: String, side: Kite.Side = Kite.Side.BUY) =
        Kite.Order("NIFTY26OCT24500CE", side, 75, 75, "MIS", "MARKET", null, 0.05, "NFO", tag)

    // ---- idea 4: the account stop rule ----------------------------------------------------------------------------------

    @Test fun beforeTheRuleIsReachedNothingIsRefused() {
        session(10, 0)
        AccountStopHost.dayRead(-5_000.0)
        AccountStopHost.setOn(true)
        assertNull(Broker.entryRecheck(order("iraorb"), "orb"))
    }

    @Test fun inShadowABotsEntryIsOnlyLoggedAndOnItIsRefused() {
        session(10, 0)
        AccountStopHost.dayRead(-13_500.0)
        assertNull("shadow (the default): nothing changes", Broker.entryRecheck(order("iraorb"), "orb"))
        assertTrue(AccountStopHost.line(), AccountStopHost.line().contains("1 live entry would have been refused today"))
        // The same owner and contract again: recorded once.
        Broker.entryRecheck(order("iraorb"), "orb")
        assertTrue(AccountStopHost.line().contains("1 live entry would have been refused today"))
        AccountStopHost.setOn(true)
        val why = Broker.entryRecheck(order("irapine"), "pine")
        assertNotNull(why)
        assertTrue(why!!, why.startsWith("refused: the account stop rule (") && why.contains("net -Rs 13,500 (limit -Rs 13,000 a month)"))
        assertTrue(AccountStopHost.view.value.reached)
    }

    /** 20 Oct, enhancement round 2 (idea 6): the day's figure is read less the day's charges, so it is reached on the net figure. */
    @Test fun theRuleReadsTheDayLessItsCharges() {
        session(10, 0)
        AccountStopHost.dayRead(-12_600.0, charges = 500.0)
        AccountStopHost.setOn(true)
        val why = Broker.entryRecheck(order("iraorb"), "orb")
        assertNotNull("-Rs 13,100 after charges: reached", why)
        assertTrue(AccountStopHost.line(), AccountStopHost.line().contains("after charges"))
    }

    /** Boss's own orders, and a saved strategy's basket leg (its start is asked instead), are never refused at the door. */
    @Test fun bosssOwnOrderAndABasketLegAreNeverRefused() {
        session(10, 0)
        AccountStopHost.dayRead(-20_000.0)
        AccountStopHost.setOn(true)
        assertNull(Broker.entryRecheck(order("iraalgo"), null))
        assertNull(Broker.entryRecheck(order("iraalgostrat"), null))
        assertTrue(Broker.entryRecheck(order("iraorb"), "orb")!!.startsWith("refused: the account stop rule"))
    }

    /** Only a trading day's figure from the open on is kept (a holiday's or a pre-open 0 never counts); not-a-number never. */
    @Test fun onlyATradingDaysFigureAfterTheOpenIsRecorded() {
        session(9, 0)
        AccountStopHost.dayRead(-20_000.0)
        assertFalse("before the open: not recorded", AccountStopHost.view.value.reached || AccountStopHost.line().contains("REACHED"))
        session(10, 0)
        AccountStopHost.dayRead(Double.NaN)
        assertTrue(AccountStopHost.line(), AccountStopHost.line().contains("no Zerodha day recorded yet"))
        AccountStopHost.dayRead(-1_000.0); AccountStopHost.dayRead(-2_000.0)
        assertTrue(AccountStopHost.line(), AccountStopHost.line().contains("this month -Rs 2,000 of -Rs 13,000 (1 day,"))
    }

    @Test fun restartingTheCountTakesThePinAndCountsFromTheNextDay() {
        session(10, 0)
        AccountStopHost.dayRead(-14_000.0)
        AccountStopHost.setOn(true)
        assertEquals("Restarting the account stop rule's count takes your PIN.", AccountStopHost.restart(pinConfirmed = false))
        assertNotNull(Broker.entryRecheck(order("iraorb"), "orb"))
        val said = AccountStopHost.restart(pinConfirmed = true)
        assertTrue(said, said.startsWith("The account stop rule counts again from"))
        assertNull("counts again from the next day", Broker.entryRecheck(order("iraorb"), "orb"))
        assertEquals("The account stop rule is not reached: nothing to restart.", AccountStopHost.restart(pinConfirmed = true))
    }

    @Test fun theSwitchAndTheDaysSurviveARestart() {
        session(10, 0)
        AccountStopHost.dayRead(-14_000.0)
        AccountStopHost.setOn(true)
        // The days are put in the background: waited for (the settings' memory copy; the file follows).
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && com.optionslab.app.security.SecurePrefs.getString("accountStop.days") == null) Thread.sleep(20)
        // As after a restart: memory gone, read again from the settings.
        AccountStopHost.resetForTest()
        AccountStopHost.load()
        assertTrue(AccountStopHost.view.value.on)
        assertTrue(AccountStopHost.view.value.reached)
    }

    /** Review: a later read the same day (a recovery) never "un-reaches" the rule; only Boss's restart does. */
    @Test fun onceReachedItStaysReachedThroughARecovery() {
        session(10, 0)
        AccountStopHost.setOn(true)
        AccountStopHost.dayRead(-13_500.0)
        assertNotNull(Broker.entryRecheck(order("iraorb"), "orb"))
        AccountStopHost.dayRead(-5_000.0)
        assertNotNull("still refused after the recovery", Broker.entryRecheck(order("iraorb"), "orb"))
        assertTrue(AccountStopHost.line(), AccountStopHost.line().contains("REACHED"))
        assertTrue(AccountStopHost.restart(pinConfirmed = true).startsWith("The account stop rule counts again"))
        assertNull(Broker.entryRecheck(order("iraorb"), "orb"))
    }

    /** Review: a figure read before the saved days were read never writes over them: they are read first and kept. */
    @Test fun aReadBeforeTheSavedDaysAreReadKeepsThem() {
        session(10, 0)
        val yesterday = Market.today().minusDays(1)
        com.optionslab.app.security.SecurePrefs.put("accountStop.days", com.optionslab.ira.AccountStop.encode(listOf(com.optionslab.ira.AccountStop.Day(yesterday, -10_000.0))))
        AccountStopHost.unloadForTest()
        AccountStopHost.dayRead(-4_000.0)
        assertTrue(AccountStopHost.line(), AccountStopHost.line().contains("(2 days,"))
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && com.optionslab.app.security.SecurePrefs.getString("accountStop.days")?.contains(Market.today().toString()) != true) Thread.sleep(20)
        assertTrue(com.optionslab.app.security.SecurePrefs.getString("accountStop.days")!!.contains(yesterday.toString()))
    }

    // ---- idea 6: the drift guard --------------------------------------------------------------------------------------

    @Test fun aGapOver16sIsLoggedAndOver30sAlsoShown() {
        session(10, 0)
        val t0 = 1_000_000L
        assertNull(WatchClock.looked(true, t0))
        assertNull(WatchClock.looked(true, t0 + 15_000L))
        val g = WatchClock.looked(true, t0 + 33_000L)
        assertNotNull(g); assertEquals(18_000L, g!!.gapMs)
        val big = WatchClock.looked(true, t0 + 73_000L)
        assertTrue(big!!.alert)
        assertTrue(com.optionslab.app.work.Alerts.queue.value.any { it.text == com.optionslab.ira.StopCadence.ALERT_WORDS })
        assertTrue(WatchClock.driftLine(), WatchClock.driftLine().contains("2 of 3 gaps while holding were over 16 s today (worst 40 s)"))
        // The next look is due 15 s after the last one STARTED.
        assertEquals(t0 + 88_000L, WatchClock.windowEnd(t0 + 76_000L))
    }

    @Test fun nothingHeldIsNeverADrift() {
        session(10, 0)
        WatchClock.looked(false, 1_000L)
        assertNull(WatchClock.looked(false, 100_000L))
        assertNull(WatchClock.looked(true, 200_000L))
        assertTrue(WatchClock.driftLine().contains("none measured today"))
    }

    // ---- idea 7: the shared held answer -------------------------------------------------------------------------------

    @Test fun thePaperBookLoadedWithNothingOpenAndNoProtectionIsNotHeld() {
        kotlinx.coroutines.runBlocking { Paper.snapshot(); Protections.active() }
        val s = HeldNow.sources()
        assertEquals("loaded, nothing open or working", false, s.paperBook); assertEquals("loaded, none active", false, s.protections)
        assertFalse(s.cards || s.orb || s.pine || s.strategies || s.armLive || s.passed || s.hero || s.signal || s.night || s.vixDiv || s.mcxArms)
    }

    /** The paper book not read yet (a fresh start): held for every worker, so a slow start never slows a stop. */
    @Test fun aBookNotReadYetCountsAsHeldEverywhere() {
        Paper.wipe()
        assertNull(HeldNow.sources().paperBook)
        assertTrue(HeldNow.any())
        assertTrue(SmartWorkers.holdingAny())
        assertTrue(com.optionslab.app.work.WatchWorkers.heldQuick())
        assertTrue(com.optionslab.app.work.HeldBooks.paperOrMinuteArms())
        assertTrue(com.optionslab.ira.Held.line(HeldNow.sources()).contains("paper book"))
    }

    // ---- idea 9: the cap's "only logged" count ------------------------------------------------------------------------

    @Test fun theCapsOnlyLoggedEntriesAreCountedForTheDiagnostics() {
        ExposureCapHost.resetForTest(paperOn = true)
        assertTrue(ExposureCapHost.onlyLoggedLine(), ExposureCapHost.onlyLoggedLine().contains("0 paper entries (they could go live), 0 live entries (shadow)"))
        ExposureCapHost.refused("R51 Donchian 20", "NIFTY1", -1, 100.0, live = false, applied = false, why = "cap")
        ExposureCapHost.refused("R51 Donchian 20", "NIFTY2", -1, 100.0, live = true, applied = false, why = "cap")
        ExposureCapHost.refused("Liquidity 15+5", "NIFTY3", -1, 100.0, live = false, applied = false, why = "cap")
        assertTrue(ExposureCapHost.onlyLoggedLine(), ExposureCapHost.onlyLoggedLine().contains("2 paper entries (they could go live), 1 live entry (shadow)"))
        assertEquals("a Liquidity entry only logged was bought: not counted apart", 0, ExposureCapHost.liquidityToday())
        ExposureCapHost.resetForTest(paperOn = false)
    }
}
