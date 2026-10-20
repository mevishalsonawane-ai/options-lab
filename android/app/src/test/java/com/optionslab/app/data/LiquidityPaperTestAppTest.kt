package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.orb.ArmPriority
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.AutoChange
import com.optionslab.ira.AutoSide
import com.optionslab.ira.FillQuality
import com.optionslab.ira.LiquidityFreeze
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.LocalDate

/**
 * Liquidity BANKNIFTY's paper test in the app (12 Oct): its small state and fill records kept off the order path and read back
 * after a restart; the freeze (by date, and Boss's unfreeze) reaching its books and Autopilot; and the one-index claims letting
 * it through while the other traders yield. The dates are fixed by the test clock (never the real date).
 */
class LiquidityPaperTestAppTest : RobolectricTest() {
    private val oct20 = LocalDate.of(2026, 10, 20)

    private fun at(d: LocalDate) { Market.testClock = Clock.fixed(d.atTime(11, 0).atZone(IST).toInstant(), IST) }

    @After fun tearDown() { Market.testClock = null; LiquidityTest.resetForTest() }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && !ok()) Thread.sleep(20)
        assertTrue("waited for $what", ok())
    }

    @Test fun fillRecordsAreKeptInMemoryAtOnceAndWrittenInTheBackground() {
        val close = oct20.atTime(10, 20).atZone(IST).toInstant().toEpochMilli()
        val sig = FillQuality.Rec(FillQuality.Kind.SIGNAL, close + 2_000, "liquidity5", "BANKNIFTY26OCT54000CE", false, 210.3,
            bid = 210.0, ask = 210.6, quoteAgeMs = 300, barCloseMs = close)
        LiquidityTest.record(sig)
        LiquidityTest.record(sig.copy(kind = FillQuality.Kind.ENTRY, atMs = close + 3_000, price = 210.6, signalPrice = 210.3, qty = 30))
        // At once in memory (no file read, no wait).
        assertEquals(2, LiquidityTest.fillsInMemory().size)
        waitFor("the fills file") { LiquidityTest.fillsWrittenForTest() }
        // As after a restart: read back from the encrypted file.
        LiquidityTest.forgetMemoryForTest()
        assertTrue(LiquidityTest.fillsInMemory().isEmpty())
        val back = LiquidityTest.fills()
        assertEquals(listOf(FillQuality.Kind.SIGNAL, FillQuality.Kind.ENTRY), back.map { it.kind })
        assertEquals(210.6, back[1].price!!, 1e-9)
        assertTrue(LiquidityTest.answer().startsWith("Boss, liquidity BANKNIFTY fills since 13 Oct: 1 signal, 1 entry"))
        assertTrue(LiquidityTest.diagLine().startsWith("Liquidity fill quality: 2 kept"))
        assertTrue(LiquidityTest.reviewNotes(oct20).first().startsWith("Liquidity fill quality today: 2 records"))
    }

    @Test fun theFreezeHoldsTheBankniftyBooksUntilTheTenthDayOrBossUnfreezes() {
        at(oct20)
        LiquidityTest.setStateForTest(LiquidityFreeze.State())
        assertTrue(LiquidityTest.frozen("liquidity15")); assertTrue(LiquidityTest.frozen("liquidity5"))
        assertFalse("MIDCPNIFTY's books are not frozen", LiquidityTest.frozen("liquidity15_mid"))
        assertTrue(LiquidityTest.frozenKey("liquidity:BANKNIFTY")); assertFalse(LiquidityTest.frozenKey("liquidity:MIDCPNIFTY"))
        assertTrue(LiquidityTest.note().startsWith("Frozen for the paper test until Mon 26 Oct"))
        // Boss unfreezes it from the Strategy lab card: kept across a restart.
        assertTrue(LiquidityTest.setUnfrozen(true).startsWith("Liquidity BANKNIFTY unfrozen"))
        assertFalse(LiquidityTest.frozen("liquidity15"))
        waitFor("the state file") { LiquidityTest.stateWrittenForTest()?.contains("unfrozen=true") == true }
        LiquidityTest.forgetMemoryForTest(); LiquidityTest.ensureLoaded()
        assertTrue(LiquidityTest.state.value.unfrozen)
        // After the 10th paper day the freeze ends by itself.
        LiquidityTest.setStateForTest(LiquidityFreeze.State())
        at(LocalDate.of(2026, 10, 27))
        assertFalse(LiquidityTest.frozen("liquidity15"))
    }

    /**
     * 20 Oct, review: a change made at start-up before the saved state was read (a stop told) left the state non-default, so the
     * file was ignored: Boss's unfreeze fell back to frozen and was written over. Now the saved state is read with the early
     * change applied on top, and a write never puts the defaults over the file.
     */
    @Test fun aChangeBeforeTheSavedStateIsReadNeverLosesIt() {
        at(oct20)
        LiquidityTest.setStateForTest(LiquidityFreeze.State())
        LiquidityTest.setUnfrozen(true)
        LiquidityTest.setTrail(true, log = false)
        waitFor("the state file") { LiquidityTest.stateWrittenForTest()?.let { it.contains("unfrozen=true") && it.contains("trail=true") } == true }
        // A restart: memory forgotten, the file kept; a stop is told before the background read.
        LiquidityTest.forgetMemoryForTest()
        assertTrue(LiquidityTest.markStopTold("liquidity15|2026-10-20"))
        LiquidityTest.ensureLoaded()
        val st = LiquidityTest.state.value
        assertTrue("the unfreeze kept", st.unfrozen)
        assertTrue("the trail kept", st.trail)
        assertEquals("the early change kept too", "liquidity15|2026-10-20", st.stopTold)
        waitFor("written merged") { LiquidityTest.stateWrittenForTest()?.let { it.contains("unfrozen=true") && it.contains("liquidity15|2026-10-20") } == true }
    }

    @Test fun autopilotAppliesNoChangeToLiquidityWhileFrozen() {
        at(oct20)
        LiquidityTest.setStateForTest(LiquidityFreeze.State())
        val pause = AutoChange.Change(AutoChange.Kind.PAPER_ENTRIES, "paper:liquidity", AutoChange.ON, AutoChange.PAUSED, "a losing stretch",
            AutoChange.Evidence(20, -6_000.0, AgentLessons.Confidence.STRONG, -1), AutoChange.Risk.LOWER, AutoChange.Scope.PAPER, null, -1, -1,
            what = "Liquidity 15+5's paper entries")
        assertNull(Autopilot.apply(pause, pinConfirmed = false))
        assertFalse("its paper entries were not paused", "liquidity" in SmartWorkers.settings.value.paused)
    }

    @Test fun liveEntriesAreReEnabledOnlyWithThePinAndNothingStoppedMeansNothingToDo() {
        kotlinx.coroutines.runBlocking {
            assertEquals("Re-enabling Liquidity BANKNIFTY's live entries takes your PIN.", OrbArms.reenableLiquidityLive(pinConfirmed = false))
            assertFalse(OrbArms.liquidityStopHint.stopped)
            assertTrue(OrbArms.reenableLiquidityLive(pinConfirmed = true).contains("is not reached"))
        }
    }

    @Test fun otherTradersYieldToLiquidityBankniftyAndItsBooksClaimApart() {
        AutoExposure.resetForTest()
        // Solo claims BANKNIFTY first: Liquidity 15m still goes, then 5m beside it.
        assertNull(AutoExposure.check(AutoExposure.Source.SOLO, "BANKNIFTY", 1, emptyList()))
        assertNull(AutoExposure.check(AutoExposure.Source.ORB, "BANKNIFTY", 1, emptyList(), ArmPriority.Rank.LIQUIDITY, book = "liquidity15"))
        val held15 = AutoSide.Held.option("Liquidity 15m", "BANKNIFTY26OCT54000CE", "BANKNIFTY", "CE", true, ArmPriority.Rank.LIQUIDITY, book = "liquidity15")
        assertNull(AutoExposure.check(AutoExposure.Source.ORB, "BANKNIFTY", 1, listOf(held15), ArmPriority.Rank.LIQUIDITY, book = "liquidity5"))
        // A Pine entry now is the one refused, and says why.
        val pine = AutoExposure.check(AutoExposure.Source.PINE, "BANKNIFTY", -1, emptyList())
        assertTrue("$pine", pine != null && pine.endsWith(AutoSide.YIELDED))
        assertTrue(AutoSide.describe(pine!!).contains("Yielded to Liquidity BANKNIFTY"))
        // Live: nothing is reserved while Liquidity BANKNIFTY is not armed live.
        assertNull(AutoExposure.liveYield("BANKNIFTY", live = true, ArmPriority.Rank.OTHER, null))
        assertNull(AutoExposure.liveYield("NIFTY", live = true, ArmPriority.Rank.OTHER, null))
        AutoExposure.resetForTest()
    }
}
