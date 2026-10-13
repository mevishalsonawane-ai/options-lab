package com.optionslab.app.data

import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.EventShield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/**
 * The market context host (TODO I6, I12; 13 Oct): "pause paper arms on event days" is OFF by default, ON only after Shadow ran
 * through an event day, pauses PAPER entries only (never live, never Liquidity BANKNIFTY while frozen), and FlowGate skips
 * through it; the level map never computes on the caller's thread.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class MarketContextHostTest : RobolectricTest() {
    @Before fun up() { MarketContext.resetForTest(); SmartWorkers.resetForTest() }
    @After fun down() { MarketContext.resetForTest(); SmartWorkers.resetForTest(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    @Test fun offByDefaultAndShadowFirst() {
        assertEquals(EventShield.Mode.OFF, MarketContext.mode.value)
        MarketContext.setTodayForTest(Market.today(), listOf("RBI policy"))
        assertEquals(EventShield.GO, MarketContext.entry("orb", live = false))
        val said = MarketContext.setMode(EventShield.Mode.ON)
        assertTrue(said, said.startsWith("Shadow first"))
        assertEquals(EventShield.Mode.OFF, MarketContext.mode.value)
        MarketContext.setMode(EventShield.Mode.SHADOW)
        val sh = MarketContext.entry("orb", live = false)
        assertFalse(sh.block); assertTrue(sh.shadow != null)
    }

    @Test fun onPausesPaperOnlyAndNeverTheFrozenArm() {
        MarketContext.setShadowDaysForTest(setOf("2026-10-01"))
        MarketContext.setMode(EventShield.Mode.ON)
        MarketContext.setTodayForTest(Market.today(), listOf("RBI policy"))
        assertTrue(MarketContext.entry("orb", live = false).block)
        assertFalse(MarketContext.entry("orb", live = true).block)
        val frozen = LiquidityTest.frozenKey("liquidity:BANKNIFTY")
        if (frozen) assertEquals(EventShield.GO, MarketContext.entry("liquidity:BANKNIFTY", live = false))
        // Through the arms' gate: the paper entry is skipped with the event's words; the live one goes.
        val paper = FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-13T10:01", what = "CE")
        assertTrue(paper.skip); assertTrue(paper.pausedWhy.orEmpty(), paper.pausedWhy.orEmpty().contains("event day"))
        assertFalse(FlowGate.check("orb", "BANKNIFTY", 1, live = true, at = "2026-10-13T10:02", what = "CE").skip)
        // Not an event day: nothing.
        MarketContext.setTodayForTest(Market.today(), emptyList())
        assertEquals(EventShield.GO, MarketContext.entry("orb", live = false))
    }

    @Test fun fedNightsCountOnTheNextIndianSession() {
        assertTrue(MarketContext.eventsOn(LocalDate.of(2026, 10, 29)).any { "FOMC" in it })
        assertTrue(MarketContext.eventsOn(LocalDate.of(2026, 10, 15)).any { "CPI" in it })
    }

    @Test fun theLevelMapAnswersFromTheCacheAtOnce() {
        val t0 = System.nanoTime()
        val j = MarketContext.levelJson("BANKNIFTY")
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 200)
        assertTrue(j, j.startsWith("{\"levels\""))
        assertEquals("{\"levels\":[]}", MarketContext.levelJson("GOLD"))
    }
}
