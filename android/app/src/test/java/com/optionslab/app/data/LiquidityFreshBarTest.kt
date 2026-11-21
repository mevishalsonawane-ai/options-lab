package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 20 Nov 2026 (audit): Liquidity 15+5's fresh-bar check ([LiquidityFreshBar], [OrbArms.staleBar]) and a signal kept when today's
 * instrument list is late ([OrbArms.LIST_WAITING]), over the synthetic BANKNIFTY day of [LiquidityArmTest]: the 13:00 5-minute bar takes
 * the pool on the swing zone, so the 5-minute book buys the 54,000 CE (paper) or asks for its approval (Live, approve mode).
 *
 *  - Shadow (the default) and paper: the entry goes exactly as before; the stale bar is logged and counted.
 *  - The switch on, a LIVE entry on a stale bar is not made and its bar is not spent: decided again once fresh candles land.
 *  - The switch itself: on with no PIN, off only with it.
 *  - The instrument list not loaded: the bar is not spent; decided again when it lands within the fill window, never late.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiquidityFreshBarTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var day: LocalDate
    private val ceKey = "NSE_FO|FRESHCE"
    /** Minutes the index feed does not have (yet). */
    private val missing = HashSet<LocalTime>()
    private lateinit var contracts: List<Upstox.Contract>

    private fun bar(k: Int): DoubleArray = when {
        k == 20 -> doubleArrayOf(54_050.0, 54_100.0, 54_040.0, 54_060.0)
        k == 26 -> doubleArrayOf(54_040.0, 54_070.0, 54_020.0, 54_030.0)
        k == 45 -> doubleArrayOf(54_020.0, 54_150.0, 54_015.0, 54_140.0)
        k >= 46 -> doubleArrayOf(54_150.0, 54_150.0, 54_145.0, 54_150.0)
        else -> doubleArrayOf(54_000.0, 54_010.0, 53_990.0, 54_000.0)
    }

    private fun feed(t: LocalDateTime): List<Upstox.Bar> = (9 * 60 + 15 until 15 * 60 + 30)
        .map { day.atTime(it / 60, it % 60) }
        .filter { !it.plusMinutes(1).isAfter(t) && it.toLocalTime() !in missing }
        .map { start ->
            val b = bar((start.hour * 60 + start.minute - (9 * 60 + 15)) / 5)
            Upstox.Bar(start.atZone(IST).toEpochSecond(), b[0], b[1], b[2], b[3], 1000, 0)
        }

    @Before fun up() {
        upstox = FakeUpstox()
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        day = AutomationSupport.tradingDayAt(12, 50).toLocalDate()
        at(LocalTime.of(12, 50))
        val expiry = day.plusDays(7)
        contracts = listOf(
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.CE, 30, ceKey, "BANKNIFTY-FRESH-54000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_200.0, Right.PE, 30, "NSE_FO|FRESHPE", "BANKNIFTY-FRESH-54200PE"),
            Upstox.Contract("BANKNIFTY", expiry, 54_000.0, Right.PE, 30, "NSE_FO|FRESHPE0", "BANKNIFTY-FRESH-54000PE"))
        AutomationSupport.contracts(context, contracts)
        upstox.price(ceKey, 300.0)
        OrbArms.testIndexBars = { t -> feed(t) }
        OrbArms.testHistoryBars = { emptyList() }
        runBlocking { OrbArms.setLiquidityLots(1, "test") }
    }

    @After fun down() {
        com.optionslab.ira.ArmExits.clear()
        LiquidityFreshBar.resetForTest()
        AutomationSupport.realClocks()
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(t: LocalTime) {
        val z = day.atTime(t).atZone(IST)
        OrbArms.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    private fun tick(t: LocalTime) { at(t); runBlocking { OrbArms.tick() } }
    private fun passes(from: LocalTime, until: LocalTime) { var t = from; while (!t.isAfter(until)) { tick(t); t = t.plusMinutes(1) } }
    private fun row() = runBlocking { OrbArms.view() }.arms.single { it.arm.source == "liquidity" }
    private fun armPaper() { assertTrue(runBlocking { OrbArms.setArmed("liquidity", true, automatic = true) }.startsWith("Liquidity 15+5 armed on paper")) }
    private fun diagHas(t: String): Boolean {
        val end = System.currentTimeMillis() + 10_000
        while (Diag.lines().none { it.contains(t) } && System.currentTimeMillis() < end) Thread.sleep(20)
        return Diag.lines().any { it.contains(t) }
    }

    // ---- the pure check ------------------------------------------------------------------------------------------------------------

    private fun ones(from: LocalTime, until: LocalTime, skip: Set<LocalTime> = emptySet()): List<Bar> {
        val out = ArrayList<Bar>(); var m = from
        while (!m.isAfter(until)) { if (m !in skip) out += Bar(day.atTime(m), 1.0, 1.0, 1.0, 1.0); m = m.plusMinutes(1) }
        return out
    }

    @Test fun aFreshBarIsNotStale() {
        val last = Bar(day.atTime(13, 0), 1.0, 1.0, 1.0, 1.0)
        assertNull(OrbArms.staleBar(ones(LocalTime.of(12, 0), LocalTime.of(13, 4)), last, 5, day.atTime(13, 5, 2)))
        assertNull("the last moment of its fill window", OrbArms.staleBar(ones(LocalTime.of(12, 0), LocalTime.of(13, 6)), last, 5, day.atTime(13, 7, 59)))
    }

    @Test fun aBarSeenPastItsFillWindowIsStale() {
        val last = Bar(day.atTime(13, 0), 1.0, 1.0, 1.0, 1.0)
        val why = OrbArms.staleBar(ones(LocalTime.of(12, 0), LocalTime.of(13, 7)), last, 5, day.atTime(13, 8))!!
        assertTrue(why, why.contains("past its fill window"))
        // A 15-minute bar's window is its own close plus 2 minutes.
        val q = Bar(day.atTime(13, 0), 1.0, 1.0, 1.0, 1.0)
        assertNull(OrbArms.staleBar(ones(LocalTime.of(12, 0), LocalTime.of(13, 16)), q, 15, day.atTime(13, 17, 30)))
        assertNotNull(OrbArms.staleBar(ones(LocalTime.of(12, 0), LocalTime.of(13, 17)), q, 15, day.atTime(13, 18)))
    }

    @Test fun aBarMissingItsLastMinuteOrAFeedBehindIsStale() {
        val last = Bar(day.atTime(13, 0), 1.0, 1.0, 1.0, 1.0)
        val part = OrbArms.staleBar(ones(LocalTime.of(12, 0), LocalTime.of(13, 6), skip = setOf(LocalTime.of(13, 4))), last, 5, day.atTime(13, 7))!!
        assertTrue(part, part.contains("last minute (13:04) is missing"))
        val behind = OrbArms.staleBar(ones(LocalTime.of(12, 0), LocalTime.of(13, 4)), last, 5, day.atTime(13, 7, 30))!!
        assertTrue(behind, behind.contains("the index's newest minute is 13:04"))
        assertNotNull("no candles at all", OrbArms.staleBar(emptyList(), last, 5, day.atTime(13, 5)))
    }

    // ---- 20 Nov 2026 (audit: heavy maths under the Liquidity lock): the fold made once a minute gives the same lists --------------------

    private fun dayMinutes(until: LocalTime): List<Bar> = feed(day.atTime(until)).map {
        Bar(java.time.Instant.ofEpochSecond(it.epochSecond).atZone(IST).toLocalDateTime(), it.open, it.high, it.low, it.close)
    }

    @Test fun theFoldMadeOnceAMinuteIsTheSameAsWorkingItOutOnEachLook() {
        val src = dayMinutes(LocalTime.of(13, 10))
        for (tf in listOf(5, 15, 30)) {
            val (same, reused) = OrbArms.liqFoldForTest(src, tf, day.atTime(13, 7, 1), day.atTime(13, 7, 59, 999_000_000))
            assertTrue("tf $tf: the same lists at any moment of the minute", same)
            assertTrue("tf $tf: reused within the minute", reused)
        }
        // The next minute: never reused (its bars may differ).
        assertTrue(OrbArms.liqFoldForTest(src, 5, day.atTime(13, 8, 0), day.atTime(13, 8, 0)).first)
        assertFalse("a new minute is folded again", OrbArms.liqFoldForTest(src, 5, day.atTime(13, 9, 10), day.atTime(13, 10, 0)).second)
        // A candle not on a whole minute: no fold kept (the look works it out itself, as before).
        val odd = src + Bar(day.atTime(13, 10, 30), 1.0, 1.0, 1.0, 1.0)
        assertEquals(false to false, OrbArms.liqFoldForTest(odd, 5, day.atTime(13, 11), day.atTime(13, 11)))
    }

    // ---- the switch ------------------------------------------------------------------------------------------------------------------

    @Test fun offByDefaultOnWithNoPinOffOnlyWithThePin() {
        assertFalse("shadow by default", LiquidityFreshBar.isOn())
        assertTrue(LiquidityFreshBar.set(true).startsWith("On:"))
        assertTrue(LiquidityFreshBar.isOn())
        val refused = LiquidityFreshBar.set(false)
        assertTrue(refused, refused.contains("takes your PIN"))
        assertTrue("still on without the PIN", LiquidityFreshBar.isOn())
        assertTrue(LiquidityFreshBar.set(false, pinConfirmed = true).startsWith("Off:"))
        assertFalse(LiquidityFreshBar.isOn())
        assertTrue(diagHas("${LiquidityFreshBar.LABEL}: on → shadow (by you)"))
    }

    @Test fun onlyALiveEntryWithTheSwitchOnIsSkippedEachSaidOnce() {
        val bar = day.atTime(13, 0)
        assertFalse("shadow: never skipped", LiquidityFreshBar.judge("Liquidity 15+5", bar, "late", live = true))
        assertFalse("paper: never skipped", LiquidityFreshBar.judge("Liquidity 15+5", bar.plusMinutes(5), "late", live = false))
        assertEquals(2, LiquidityFreshBar.view.value.wouldSkipToday)
        LiquidityFreshBar.set(true)
        assertFalse("paper: never skipped, on or off", LiquidityFreshBar.judge("Liquidity 15+5", bar.plusMinutes(10), "late", live = false))
        assertTrue(LiquidityFreshBar.judge("Liquidity 15+5", bar.plusMinutes(15), "late", live = true))
        assertTrue("the same bar again: still skipped", LiquidityFreshBar.judge("Liquidity 15+5", bar.plusMinutes(15), "late", live = true))
        assertEquals(1, LiquidityFreshBar.view.value.skippedToday)
        assertEquals(3, LiquidityFreshBar.view.value.wouldSkipToday)
        assertTrue(diagHas("13:00 bar: ${LiquidityFreshBar.WOULD} - shadow: the live entry goes as before: late"))
    }

    // ---- the arm -----------------------------------------------------------------------------------------------------------------------

    /** The 13:00 bar's last minute (13:04) is late: from 13:07 the bar is decided on its first four minutes - a stale bar. */
    private fun lateMinute() { missing += LocalTime.of(13, 4) }

    @Test fun inShadowAPaperEntryOnAStaleBarGoesAsBeforeAndIsLogged() {
        lateMinute()
        armPaper()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 6))
        assertTrue("the bar waits for its last minute until 13:07", row().today.isEmpty())
        tick(LocalTime.of(13, 7))
        val p = row().today.single()
        assertEquals(day.atTime(13, 0), p.signalBar)
        assertFalse(p.live)
        assertEquals(1, LiquidityFreshBar.view.value.wouldSkipToday)
        assertTrue(diagHas(LiquidityFreshBar.WOULD + " - paper: entered as before"))
    }

    @Test fun withTheSwitchOnPaperIsStillNeverSkipped() {
        LiquidityFreshBar.set(true)
        lateMinute()
        armPaper()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 7))
        assertEquals(day.atTime(13, 0), row().today.single().signalBar)
        assertEquals(0, LiquidityFreshBar.view.value.skippedToday)
    }

    /** Both books armed in Live in approve mode, cleared for Zerodha by Boss's switch: a signal asks for its LIVE approval. */
    private fun liveApproveMode() {
        AutomationSupport.liveSettings()
        val books = listOf("liquidity15", "liquidity5")
        fun flags(v: Boolean) = JSONObject().apply { books.forEach { put(it, v) } }
        AutomationSupport.orbState(context, JSONObject().put("armed", flags(true)).put("auto", flags(false)).put("liveOk", flags(true))
            .put("positions", JSONArray()).put("liqLots", 1).put("liqLotsChosen", true))
    }

    @Test fun inShadowALiveSignalOnAStaleBarIsAskedForAsBefore() {
        liveApproveMode()
        lateMinute()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 7))
        val pd = row().pending
        assertNotNull("asked for as before", pd)
        assertEquals(true, pd!!.live)
        assertEquals(day.atTime(13, 0), pd.signalBar)
        assertTrue(diagHas(LiquidityFreshBar.WOULD + " - shadow: the live entry goes as before"))
    }

    @Test fun withTheSwitchOnALiveSignalOnAStaleBarWaitsForFreshCandlesThenGoes() {
        LiquidityFreshBar.set(true)
        liveApproveMode()
        lateMinute()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 7))
        assertNull("not asked for on the stale bar", row().pending)
        assertTrue(diagHas("13:00 bar: live entry skipped (stale bar, fresh-bar check on"))
        assertEquals(1, LiquidityFreshBar.view.value.skippedToday)
        // The late minute lands within the bar's fill window: the bar was not spent - decided again, on the whole bar.
        missing.clear()
        at(LocalTime.of(13, 7, 30)); runBlocking { OrbArms.tick() }
        val pd = row().pending
        assertNotNull("decided again once fresh", pd)
        assertEquals(day.atTime(13, 0), pd!!.signalBar)
        assertEquals(true, pd.live)
    }

    /**
     * 20 Nov 2026 (review): a stale bar with no signal on it is decided (spent) as before, even with the switch on in Live - the check only
     * holds back a signal, so it can never add an entry the old way would not have made.
     */
    @Test fun withTheSwitchOnAStaleBarWithNoSignalIsSpentAsBefore() {
        LiquidityFreshBar.set(true)
        liveApproveMode()
        missing += LocalTime.of(12, 54)                                    // the 12:50 bar's last minute is late; no break on it either way
        passes(LocalTime.of(12, 50), LocalTime.of(12, 57))
        assertFalse(row().status, row().status.contains("fresh-bar check on"))
        assertNull(row().pending)
        assertEquals(0, LiquidityFreshBar.view.value.skippedToday)
        assertEquals(0, LiquidityFreshBar.view.value.wouldSkipToday)
    }

    @Test fun withTheSwitchOnALiveSignalWhoseCandlesNeverCatchUpIsNeverTakenLate() {
        LiquidityFreshBar.set(true)
        liveApproveMode()
        lateMinute()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 7))
        assertNull(row().pending)
        missing.clear()
        tick(LocalTime.of(13, 8))                                          // the minute lands after the fill window
        assertNull("never bought late", row().pending)
        assertTrue(diagHas("refused: stale bar"))
        passes(LocalTime.of(13, 9), LocalTime.of(13, 12))
        assertNull("the bar is spent", row().pending)
    }

    // ---- the instrument list late ------------------------------------------------------------------------------------------------------

    private fun listGone() { File(context.filesDir, "contracts.json").delete() }

    /** 20 Nov 2026 (coordinator): the list wait off (its default): the signal is lost as before (no contract), logged and counted. */
    @Test fun withTheListWaitOffASignalIsLostAsBeforeAndLogged() {
        armPaper()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 4))
        listGone()
        tick(LocalTime.of(13, 5))
        assertTrue(row().status, row().status.contains(OrbArms.describe("no_contract")))
        assertTrue(diagHas(LiquidityFreshBar.WOULD_WAIT))
        assertEquals(1, LiquidityFreshBar.view.value.listWouldWaitToday)
        AutomationSupport.contracts(context, contracts)                    // the list lands in the fill window: the bar is spent, as on main
        passes(LocalTime.of(13, 6), LocalTime.of(13, 7))
        assertTrue("never entered", row().today.isEmpty())
    }

    @Test fun theListWaitOnTakesThePinOffDoesNot() {
        assertFalse("off by default", LiquidityFreshBar.listWaitOn())
        val refused = LiquidityFreshBar.setListWait(true)
        assertTrue(refused, refused.contains("takes your PIN"))
        assertFalse(LiquidityFreshBar.listWaitOn())
        assertTrue(LiquidityFreshBar.setListWait(true, pinConfirmed = true).startsWith("On:"))
        assertTrue(LiquidityFreshBar.listWaitOn())
        assertTrue(LiquidityFreshBar.setListWait(false).startsWith("Off:"))
        assertFalse(LiquidityFreshBar.listWaitOn())
    }

    /** 20 Nov 2026: the list wait's saved choice is read back at the start; a late start read never overrides Boss's switch; erase clears it. */
    @Test fun theListWaitIsReadBackNeverOverriddenAndErased() {
        com.optionslab.app.security.SecurePrefs.put(LiquidityFreshBar.K_LIST, true)
        LiquidityFreshBar.resetForTest(loaded = false)
        LiquidityFreshBar.load()
        assertTrue("read back", LiquidityFreshBar.listWaitOn())
        LiquidityFreshBar.setListWait(false)
        LiquidityFreshBar.load()                                           // a late start read
        assertFalse("Boss's switch stands", LiquidityFreshBar.listWaitOn())
        LiquidityFreshBar.setListWait(true, pinConfirmed = true)
        LiquidityFreshBar.listWouldWait("Liquidity 5m", day.atTime(13, 0))
        assertTrue(LiquidityFreshBar.holdsForErase())
        LiquidityFreshBar.wipe()
        assertFalse(LiquidityFreshBar.listWaitOn())
        assertFalse(LiquidityFreshBar.holdsForErase())
        assertEquals(0, LiquidityFreshBar.view.value.listWouldWaitToday)
    }

    @Test fun aSignalWaitsForALateInstrumentListAndIsEnteredWhenItLandsInTime() {
        LiquidityFreshBar.setListWait(true, pinConfirmed = true)
        armPaper()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 4))
        listGone()
        tick(LocalTime.of(13, 5))
        assertTrue("nothing bought without the list", row().today.isEmpty())
        assertTrue(diagHas("the 13:00 bar's signal waits for today's instrument list"))
        AutomationSupport.contracts(context, contracts)                    // the list lands
        tick(LocalTime.of(13, 6))
        val p = row().today.single()
        assertEquals("the same bar's signal", day.atTime(13, 0), p.signalBar)
    }

    @Test fun aSignalWhoseListLandsAfterItsFillWindowIsNeverBoughtLate() {
        LiquidityFreshBar.setListWait(true, pinConfirmed = true)
        armPaper()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 4))
        listGone()
        passes(LocalTime.of(13, 5), LocalTime.of(13, 7))
        assertTrue(row().today.isEmpty())
        AutomationSupport.contracts(context, contracts)
        passes(LocalTime.of(13, 8), LocalTime.of(13, 12))
        assertTrue("never bought late", row().today.isEmpty())
    }

    /**
     * 20 Nov 2026 (audit: warmContracts' read-back step had no test): the list loaded off the arms' lock is also read back into memory there,
     * so the look under the lock ([OrbArms.liquidityCycle]'s list check) never parses the tens-of-megabytes file itself; once loaded today,
     * nothing is downloaded again.
     */
    @Test fun warmingTheListReadsItBackOffTheLockAndDownloadsItOnce() {
        listGone()
        var downloads = 0
        OrbArms.testWarmDownload = {
            downloads++
            AutomationSupport.contracts(context, contracts)
            File(context.filesDir, "contracts.json").setLastModified(System.currentTimeMillis() + 60_000)   // a file the memory has not seen
        }
        try {
            runBlocking { OrbArms.warmContractsForTest(5_000) }
            assertEquals(1, downloads)
            assertTrue("read back into memory by the warm itself", Market.contractsInMemoryForTest())
            runBlocking { OrbArms.warmContractsForTest(5_000) }
            assertEquals("loaded today: never downloaded again", 1, downloads)
        } finally { OrbArms.testWarmDownload = null }
    }

    @Test fun withTheListLoadedTheEntryIsAsBefore() {
        armPaper()
        passes(LocalTime.of(12, 50), LocalTime.of(13, 5))
        assertEquals(day.atTime(13, 0), row().today.single().signalBar)
        assertEquals(0, LiquidityFreshBar.view.value.wouldSkipToday)
    }
}
