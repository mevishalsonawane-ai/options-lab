package com.optionslab.app.data

import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.UpstoxStub
import com.optionslab.app.testing.UpstoxStub.Companion.isHistory
import com.optionslab.app.testing.UpstoxStub.Companion.isIntraday
import com.optionslab.app.testing.UpstoxStub.Companion.status
import com.optionslab.engine.Manifest
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalTime

/**
 * The nightly harvest against a local Upstox stub: a stopped or partly failed run resumes from
 * its done-markers instead of starting over, and the expiring chain is held only once it was
 * captured with no contract missing.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class HarvesterTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.BackgroundWatchdog()
    private lateinit var upstox: UpstoxStub
    private val tue = WED.minusDays(1)
    private val next = WED.plusDays(6)

    private val n1 = Upstox.Contract("NIFTY", next, 24_500.0, Right.PE, 75, "NSE_FO|11", "NIFTY25O2124500PE")
    private val n2 = Upstox.Contract("NIFTY", next, 24_600.0, Right.PE, 75, "NSE_FO|12", "NIFTY25O2124600PE")
    private val n3 = Upstox.Contract("NIFTY", next, 24_500.0, Right.CE, 75, "NSE_FO|13", "NIFTY25O2124500CE")
    private val e1 = Upstox.Contract("NIFTY", WED, 24_500.0, Right.PE, 75, "NSE_FO|21", "NIFTY25O1524500PE")
    private val e2 = Upstox.Contract("NIFTY", WED, 24_400.0, Right.PE, 75, "NSE_FO|22", "NIFTY25O1524400PE")

    @Volatile private var failing: Set<String> = emptySet()
    @Volatile private var failCode = 404

    @Before fun up() {
        upstox = UpstoxStub()
        Background.calendar(WED)
        Background.at(WED, 16, 0)
        upstox.reply = { p ->
            when {
                failing.any { p.contains("/$it/") } -> status(failCode)
                isIntraday(p) -> UpstoxStub.candles(UpstoxStub.minutes(WED, LocalTime.of(9, 15), 20, 100.0))
                isHistory(p) -> UpstoxStub.candles(UpstoxStub.minutes(tue, LocalTime.of(15, 0), 10, 90.0))
                else -> status(404)
            }
        }
    }

    @After fun down() {
        upstox.close(); Background.reset()
        assertEquals("a test reached the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun asked(c: Upstox.Contract) = upstox.count { it.contains("/${c.instrumentKey}/") }
    private suspend fun harvest() = Harvester.run(underlyings = listOf("NIFTY"), indices = false, session = WED)
    private fun manifestFor(day: java.time.LocalDate) = Store.manifest("NIFTY").single { it.session == day }

    @Test fun aPartlyFailedRunResumesOnlyWhatIsMissing() = runBlocking {
        Background.contracts(context, WED, listOf(n1, n2, n3))
        failing = setOf(n2.instrumentKey)
        val first = harvest()
        assertEquals(listOf(n2.tradingSymbol), first.failures)
        assertEquals(setOf(n1.instrumentKey, n3.instrumentKey), Store.harvested("NIFTY", WED))
        assertEquals("a chain with a gap is not same_day", Manifest.BACKFILL, manifestFor(WED).scope)
        assertFalse(first.sameDay.containsKey("NIFTY"))
        assertTrue(first.summary().contains("1 contract(s) failed"))
        assertEquals(2, Store.barSession("NIFTY", WED)!!.options.size)

        failing = emptySet()
        upstox.paths.clear()
        val second = harvest()
        assertTrue(second.failures.isEmpty())
        assertEquals("the stored contracts are not fetched again", 0, asked(n1) + asked(n3))
        assertEquals("history and today's session for the missing one", 2, asked(n2))
        assertEquals(setOf(n1, n2, n3).map { it.instrumentKey }.toSet(), Store.harvested("NIFTY", WED))
        assertEquals(3, Store.barSession("NIFTY", WED)!!.options.size)
        assertEquals(3, Store.barSession("NIFTY", tue)!!.options.size)
        assertEquals(Manifest.SAME_DAY, manifestFor(WED).scope)
        assertEquals(3, manifestFor(WED).nContracts)
        assertEquals(3, second.sameDay["NIFTY"])
        assertEquals(Manifest.BACKFILL, manifestFor(tue).scope)
    }

    @Test fun beforeTheSessionIsFinalNothingIsMarkedDone() = runBlocking {
        Background.at(WED, 15, 0)
        Background.contracts(context, WED, listOf(n1, n2))
        harvest()
        assertTrue("bars still to come today: nothing may be skipped later", Store.harvested("NIFTY", WED).isEmpty())
        upstox.paths.clear()
        harvest()
        assertEquals(2, asked(n1)); assertEquals(2, asked(n2))
        assertEquals("upserted, not doubled", 20, Store.barSession("NIFTY", WED)!!.options.first().size)
    }

    @Test fun theExpiringChainIsHeldOnlyOnceCompleteAndAGappyCaptureNeverShrinksOne() = runBlocking {
        Background.contracts(context, WED, listOf(e1, e2, n1))
        failing = setOf(e2.instrumentKey)
        val gappy = harvest()
        assertEquals("a gappy capture is written when nothing better is on disk", WED, gappy.capturedExpiry)
        assertEquals(1, Store.deviceExpirySize(WED))
        assertFalse(Store.harvested("NIFTY", WED).contains("expiry-chain-complete"))

        // The next run fetches the whole expiring chain again (even the contract it stored), not the rest.
        failing = emptySet()
        upstox.paths.clear()
        val whole = harvest()
        assertEquals(WED, whole.capturedExpiry)
        assertEquals(2, asked(e1)); assertEquals(2, asked(e2)); assertEquals(0, asked(n1))
        assertEquals(2, Store.deviceExpirySize(WED))
        assertTrue(Store.harvested("NIFTY", WED).contains("expiry-chain-complete"))
        assertTrue(whole.summary().contains("expiry chain $WED captured"))

        // Held: a third run fetches nothing and writes nothing.
        upstox.paths.clear()
        val again = harvest()
        assertNull(again.capturedExpiry)
        assertEquals(0, upstox.paths.size)
        assertEquals(2, Store.deviceExpirySize(WED))
        assertTrue(WED in Store.deviceExpiryDays())
    }

    @Test fun aGappyCaptureDoesNotReplaceAnEquallyBigOne() = runBlocking {
        Background.contracts(context, WED, listOf(e1, e2))
        failing = setOf(e2.instrumentKey)
        harvest()
        assertEquals(1, Store.deviceExpirySize(WED))
        failing = setOf(e1.instrumentKey)
        val r = harvest()
        assertNull("one contract with a gap does not replace one contract with a gap", r.capturedExpiry)
        assertEquals(1, Store.deviceExpirySize(WED))
    }

    @Test fun noExpiryCaptureBeforeTheClose() = runBlocking {
        Background.at(WED, 14, 0)
        Background.contracts(context, WED, listOf(e1))
        val r = harvest()
        assertNull(r.capturedExpiry)
        assertTrue(Store.deviceExpiryDays().isEmpty())
    }

    @Test fun aRetryAfterMidnightStillHarvestsItsOwnSession() = runBlocking {
        Background.contracts(context, WED.plusDays(1), listOf(n1))
        Background.at(WED.plusDays(1), 0, 30)
        val r = harvest()
        assertTrue(r.failures.isEmpty())
        assertEquals("marked for the session it collected", setOf(n1.instrumentKey), Store.harvested("NIFTY", WED))
        assertEquals("collected the next day: backfill", Manifest.BACKFILL, manifestFor(WED).scope)
        assertEquals(WED.plusDays(1), manifestFor(WED).collectedOn)
    }

    @Test fun lostAuthenticationStopsTheWholeRun() = runBlocking {
        Background.contracts(context, WED, listOf(n1, n2))
        failing = setOf(n1.instrumentKey); failCode = 401
        try { harvest(); fail() } catch (e: Upstox.UpstoxError) { assertTrue(e.message!!.contains("authentication")) }
    }

    @Test fun indexBarsAreStoredWithTheChainsAndProgressIsReported() = runBlocking {
        Background.contracts(context, WED, listOf(n1))
        val stages = ArrayList<String>()
        val r = Harvester.run(underlyings = listOf("NIFTY"), indices = true, session = WED, onProgress = { synchronized(stages) { stages += it.stage } })
        for (u in Upstox.INDEX_KEYS.keys) assertTrue(u, Store.barSession(u, WED)!!.index != null)
        assertEquals(stages.first(), "Reading the instrument master")
        assertEquals("Done", stages.last())
        assertTrue(stages.contains("NIFTY chain"))
        assertEquals((20 + 10) * (Upstox.INDEX_KEYS.size + 1), r.bars)
    }
}
