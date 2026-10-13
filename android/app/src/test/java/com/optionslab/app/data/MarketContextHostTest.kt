package com.optionslab.app.data

import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.EventShield
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The market context host (TODO I6, I12; 13 Oct): "pause paper arms on event days" is OFF by default, ON only after Shadow ran
 * through an event day, pauses PAPER entries only (never live, never Liquidity BANKNIFTY while frozen), and FlowGate skips
 * through it; the level map never computes on the caller's thread.
 *
 * 13 Oct review: the market clock is fixed (Tue 13 Oct 2026 10:00 IST, inside Liquidity BANKNIFTY's freeze) and the gates the
 * arms' check also asks (FlowGate, PortfolioHost, SmartWorkers) start empty, so nothing here depends on the day it runs.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class MarketContextHostTest : RobolectricTest() {
    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    private val day: LocalDate = LocalDate.of(2026, 10, 13)

    private fun reset() {
        MarketContext.resetForTest(); SmartWorkers.resetForTest(); FlowGate.resetForTest(); PortfolioHost.resetForTest()
        LiquidityTest.resetForTest()
    }

    @Before fun up() {
        Market.testClock = Clock.fixed(day.atTime(10, 0).atZone(ist).toInstant(), ist)
        reset()
    }

    @After fun down() {
        reset()
        Market.testClock = null
        assertEquals(emptyList<String>(), NetworkGuard.blocked.toList())
    }

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
        // 13 Oct is inside the freeze (until 26 Oct): Liquidity BANKNIFTY is never paused.
        assertTrue(LiquidityTest.frozenKey("liquidity:BANKNIFTY"))
        assertEquals(EventShield.GO, MarketContext.entry("liquidity:BANKNIFTY", live = false))
        // Through the arms' gate: the paper entry is skipped with the event's words; the live one goes.
        val paper = FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-13T10:01", what = "CE")
        assertTrue(paper.skip); assertTrue(paper.pausedWhy.orEmpty(), paper.pausedWhy.orEmpty().contains("event day"))
        assertFalse(FlowGate.check("orb", "BANKNIFTY", 1, live = true, at = "2026-10-13T10:02", what = "CE").skip)
        // Not an event day: nothing.
        MarketContext.setTodayForTest(Market.today(), emptyList())
        assertEquals(EventShield.GO, MarketContext.entry("orb", live = false))
    }

    @Test fun aShadowDayCountsOnlyOnceShadowWatchedIt() = runBlocking {
        MarketContext.rawEventsForTest = { d -> if (d == day) listOf("RBI policy") else emptyList() }
        MarketContext.setMode(EventShield.Mode.SHADOW)       // switched on at 10:00: not watched from 09:15
        // No verdict yet: the evening refresh does not count the day.
        MarketContext.refresh(day.atTime(15, 45))
        assertTrue(MarketContext.shadowDays.value.isEmpty())
        // A real shadow verdict in market hours (the clock says 10:00): now it counts.
        assertTrue(MarketContext.entry("orb", live = false).shadow != null)
        MarketContext.refresh(day.atTime(15, 46))
        assertEquals(setOf("2026-10-13"), MarketContext.shadowDays.value)
    }

    @Test fun fedNightsCountOnTheNextIndianSession() {
        assertTrue(MarketContext.eventsOn(LocalDate.of(2026, 10, 29)).any { "FOMC" in it })
        assertTrue(MarketContext.eventsOn(LocalDate.of(2026, 10, 15)).any { "CPI" in it })
    }

    @Test fun theLevelMapAnswersFromTheCacheAndComputesElsewhere() = runBlocking {
        // A dispatcher that counts what is handed to it: the computation goes there, never runs on the caller's thread.
        val handed = java.util.concurrent.atomic.AtomicInteger()
        MarketContext.levelDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                handed.incrementAndGet(); kotlinx.coroutines.Dispatchers.Default.dispatch(context, block)
            }
        }
        // The first ask returns the empty map at once (nothing cached) and starts the computation on that dispatcher.
        assertEquals("{\"levels\":[]}", MarketContext.levelJson("BANKNIFTY"))
        MarketContext.awaitLevelsForTest()
        assertTrue(handed.get() >= 1)
        val j = MarketContext.levelJson("BANKNIFTY")
        assertTrue(j, j.startsWith("{\"levels\""))
        assertTrue(j, j.contains("\"note\""))
        assertEquals("{\"levels\":[]}", MarketContext.levelJson("GOLD"))
    }

    @Test fun theBriefIsNotRebuiltEveryMinute() = runBlocking {
        // Nothing on the phone: the brief cannot be built. It is tried at 09:21, then not again until its backoff.
        MarketContext.rawEventsForTest = { emptyList() }
        MarketContext.refresh(LocalDateTime.of(day, java.time.LocalTime.of(9, 21)))
        assertEquals(1, MarketContext.briefTriesForTest().tries)
        MarketContext.refresh(LocalDateTime.of(day, java.time.LocalTime.of(9, 22)))
        assertEquals(1, MarketContext.briefTriesForTest().tries)
        MarketContext.refresh(LocalDateTime.of(day, java.time.LocalTime.of(9, 26)))
        assertEquals(2, MarketContext.briefTriesForTest().tries)
        assertTrue(MarketContext.card.value.days.isEmpty())
    }
}
