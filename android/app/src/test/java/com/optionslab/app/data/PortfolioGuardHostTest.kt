package com.optionslab.app.data

import com.optionslab.app.testing.Cleared
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.ira.EventCalendar
import com.optionslab.ira.LearningFilter
import com.optionslab.ira.PortfolioGuard
import com.optionslab.ira.PortfolioRisk
import com.optionslab.ira.RegimeParams
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/**
 * TODO S5, S8, S11, S12 in the app: the portfolio guard at the arms' entry check (FlowGate, paper and live) and at the entry
 * door (Broker) - it only ever skips or refuses a NEW entry, never an exit or Boss's own order, and reads no setting on the
 * order path; the regime weights reach the trade manager's config only out of shadow; the event calendar reaches the
 * learning filter; the history vault's read API and cap.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PortfolioGuardHostTest : RobolectricTest() {
    private lateinit var kite: FakeKite
    private val today: LocalDate get() = Market.today()

    @Before fun up() {
        kite = FakeKite(); kite.login()
        SmartWorkers.resetForTest(); PortfolioHost.resetForTest(); RegimeHost.resetForTest(); EventDays.resetForTest(); HistoryKeeper.resetForTest()
    }

    @After fun down() {
        SecurePrefs.countedThread = null
        PortfolioHost.resetForTest(); RegimeHost.resetForTest(); EventDays.resetForTest(); SmartWorkers.resetForTest(); HistoryKeeper.resetForTest()
        kite.close()
        assertEquals(emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** ORB and ORB Fresh moving together for 30 days; Hero on its own. Stops of Rs 5,000 each against a 2% cap of 4 lakh. */
    private fun correlated() {
        val r = java.util.Random(5)
        val t = ArrayList<PortfolioRisk.Trade>()
        for (i in 0 until 30) {
            val d = today.minusDays(30L - i)
            val m = r.nextGaussian() * 1_000
            t += PortfolioRisk.Trade("orb", d, m); t += PortfolioRisk.Trade("orb_fresh", d, m * 1.2 + r.nextGaussian() * 100)
            t += PortfolioRisk.Trade("hero", d, r.nextGaussian() * 1_000)
        }
        PortfolioHost.guard.update(PortfolioRisk.matrix(t, today), mapOf("orb" to 5_000.0, "orb_fresh" to 5_000.0, "hero" to 5_000.0),
            PortfolioGuard.Config(capital = 400_000.0, capPct = 2.0))
    }

    private fun order(tag: String = "iraorb", side: Kite.Side = Kite.Side.BUY) =
        Kite.Order("BANKNIFTY26OCT52000CE", side, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", tag)

    @Test fun aLaterCorrelatedPaperEntryIsSkippedWithItsReasonAndNoSettingIsRead() {
        correlated()
        // Warm what the entry check keeps in memory (the brain's settings, the flow's modes) with an unrelated arm.
        assertFalse(FlowGate.check("hero", "NIFTY", -1, live = false, at = "2026-10-12T09:30", what = "PE").skip)
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        val first = FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:01", what = "CE")
        FlowGate.taken(first, "P1")      // ORB's entry filled: only now does it count as opened (review R1)
        val later = FlowGate.check("orb_fresh", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:05", what = "CE")
        val other = FlowGate.check("orb_fresh", "BANKNIFTY", -1, live = false, at = "2026-10-12T10:06", what = "PE")
        SecurePrefs.countedThread = null
        assertEquals("settings reads on the entry checks", 0L, SecurePrefs.readsForTest.get())
        assertFalse(first.skip)
        assertTrue(later.skip)
        assertTrue(later.pausedWhy!!, later.pausedWhy!!.contains("portfolio guard"))
        assertFalse("the other direction is not blocked", other.skip)
        assertTrue(PortfolioHost.view.value.line.contains("1 entry blocked today"))
        assertTrue(SmartWorkers.feed(10).any { it.contains("portfolio guard") })
        // Waiting for Boss's approval: logged only, never blocked (and not counted as opened).
        assertFalse(FlowGate.check("orb_fresh", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:07", what = "CE", decide = false).skip)
    }

    @Test fun aLiveEntryIsRefusedAtTheDoorButNeverAnExitOrBossesOwnOrder() = runBlocking {
        // 30 Oct: the ORB family's and Pine's live entries need Live and "AI trades go live" at the door ([Broker.ARM_TAGS]).
        com.optionslab.app.testing.AutomationSupport.liveSettings()
        correlated()
        val orb = FlowGate.check("orb", "BANKNIFTY", 1, live = true, at = "2026-10-12T10:01", what = "CE")
        assertFalse(orb.skip)
        FlowGate.outcome(orb, "entered_live")      // sent to Zerodha: counted as opened
        assertTrue(FlowGate.check("orb_fresh", "BANKNIFTY", 1, live = true, at = "2026-10-12T10:02", what = "CE").skip)
        // The door's own check reads memory only.
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        val why = PortfolioHost.doorRefusal("orb_fresh", "BANKNIFTY26OCT52000CE", buy = true)
        SecurePrefs.countedThread = null
        assertEquals(0L, SecurePrefs.readsForTest.get())
        assertNotNull(why)
        try { Broker.placeOrder(order(), arm = "orb_fresh", verdict = Cleared.live(order(), "orb_fresh")); fail("a bot's entry its arm was blocked from went through") } catch (e: Broker.KiteError) {
            assertEquals("entry_gate", e.type)
            assertTrue(e.message, e.message!!.contains("portfolio guard"))
        }
        assertTrue(kite.placed.isEmpty())
        // A put (the other direction), an exit and Boss's own order all go.
        assertNull(PortfolioHost.doorRefusal("orb_fresh", "BANKNIFTY26OCT52000PE", buy = true))
        Broker.placeOrder(order(side = Kite.Side.SELL), exit = true)
        Broker.placeOrder(order(tag = "iraalgo"), verdict = Cleared.live(order(tag = "iraalgo")))
        assertEquals(2, kite.placed.size)
        // Review R1: only the blocked arm is refused - ORB's own entry, an uncorrelated arm's, an order with no arm, and every
        // leg of a saved strategy's basket go through the door.
        assertNull(PortfolioHost.doorRefusal("orb", "BANKNIFTY26OCT52000CE", buy = true))
        assertNull(PortfolioHost.doorRefusal(null, "BANKNIFTY26OCT52000CE", buy = true))
        Broker.placeOrder(order(), arm = "orb", verdict = Cleared.live(order(), "orb"))
        Broker.placeOrder(order(), arm = "hero", verdict = Cleared.live(order(), "hero"))
        Broker.placeOrder(order(), verdict = Cleared.live(order()))
        Broker.placeOrder(order(tag = "iraalgostrat"), arm = "orb_fresh", verdict = Cleared.live(order(tag = "iraalgostrat"), "orb_fresh"))
        Broker.placeOrder(order(tag = "iraalgostrat"), verdict = Cleared.live(order(tag = "iraalgostrat")))
        assertEquals(7, kite.placed.size)
    }

    @Test fun offOrUncorrelatedTheGuardNeverBlocks() {
        correlated()
        PortfolioHost.setOn(false)
        assertFalse(FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:01", what = "CE").skip)
        assertFalse(FlowGate.check("orb_fresh", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:02", what = "CE").skip)
        PortfolioHost.setOn(true)
        assertFalse(FlowGate.check("hero", "NIFTY", 1, live = false, at = "2026-10-12T10:03", what = "CE").skip)
        assertTrue(PortfolioHost.setCapPct(50.0).contains("10.0%"))
        assertFalse("under a 10% cap two stops fit", FlowGate.check("orb_fresh", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:04", what = "CE").skip)
    }

    @Test fun jarvisSaysHowAlikeTheArmsAreAndWhyOneWasBlocked() = runBlocking {
        correlated()
        FlowGate.taken(FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:01", what = "CE"), "P1")
        FlowGate.check("orb_fresh", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:02", what = "CE")
        // A refresh from the (empty) records would replace the test's matrix: the answers read memory when fresh.
        val m = PortfolioHost.guard.matrix
        assertTrue(m.of("orb", "orb_fresh")!! > 0.6)
        val why = PortfolioRisk.answerBlocked("why was orb fresh blocked?", PortfolioHost.guard.blocksToday(today))
        assertTrue(why, why.contains("ORB Fresh's paper bullish entry was blocked"))
    }

    @Test fun aSkippedEntryIsNeverCountedAsOpenedAndTheRegimeSitOutComesFirst() {
        correlated()
        // ORB let through but never taken (the flow, the account guard, no fill): ORB Fresh is not blocked by it.
        assertFalse(FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:01", what = "CE").skip)
        assertFalse(FlowGate.check("orb_fresh", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:02", what = "CE").skip)
        assertTrue(PortfolioHost.guard.blocksToday(today).isEmpty())
        // A day ORB sits out by a proven regime lesson: skipped by the regime, never asked by the portfolio guard, never opened.
        PortfolioHost.resetForTest(); correlated()
        val sitOut = RegimeParams.Override("arm:orb", RegimeParams.Regime.BUSY, mapOf(RegimeParams.SIZE_KEY to 0.0), "strat:ORB", 0.02, "orb busy",
            (0 until 10).map { today.minusDays(20L - it) })
        RegimeParams.use(RegimeParams.Book(listOf(sitOut), RegimeParams.Day(today, RegimeParams.Regime.BUSY, false, false, 585)))
        val t = FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:03", what = "CE")
        assertTrue(t.skip)
        assertTrue(t.pausedWhy!!, t.pausedWhy!!.contains("sits out"))
        assertEquals(0, t.opens)
        FlowGate.taken(t, "P2")       // even if a caller said so, a skipped ticket counts nothing
        assertFalse(FlowGate.check("orb_fresh", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:04", what = "CE").skip)
        // Yesterday's frozen day never applies today.
        RegimeParams.use(RegimeParams.Book(listOf(sitOut), RegimeParams.Day(today.minusDays(1), RegimeParams.Regime.BUSY, false, false, 585)))
        assertFalse(FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:05", what = "CE").skip)
    }

    @Test fun regimeWeightsReachTheManagerOnlyOutOfShadowAndNeverOnAnEventDay() {
        val o = RegimeParams.Override("spec:pine:news", RegimeParams.Regime.BUSY, mapOf("weight" to 0.5), "spec:pine:news", 0.02, "news on pine")
        val cfg = com.optionslab.ira.ManagerConfig("pine")
        val busy = RegimeParams.Day(today, RegimeParams.Regime.BUSY, false, false, 585)
        RegimeParams.use(RegimeParams.Book(listOf(o), busy))
        assertEquals(cfg, RegimeHost.managerConfig(cfg, live = false))
        RegimeParams.use(RegimeParams.Book(listOf(o.copy(shadowDays = (0 until 10).map { today.minusDays(20L - it) })), busy))
        assertEquals(0.5, RegimeHost.managerConfig(cfg, live = false).spec("news").weight, 1e-9)
        assertEquals("live waits for Boss's PIN", cfg, RegimeHost.managerConfig(cfg, live = true))
        assertEquals("Live regime settings need your PIN.", RegimeHost.approveLive("spec:pine:news|BUSY", pinConfirmed = false))
        RegimeHost.approveLive("spec:pine:news|BUSY", pinConfirmed = true)
        assertEquals(0.5, RegimeHost.managerConfig(cfg, live = true).spec("news").weight, 1e-9)
        RegimeParams.use(RegimeParams.book.copy(day = busy.copy(event = true)))
        assertEquals(cfg, RegimeHost.managerConfig(cfg, live = false))
    }

    @Test fun theEventCalendarReachesTheLearningFilterWithBossesOwnDays() {
        EventDays.refresh()
        assertFalse(LearningFilter.include(LocalDate.of(2026, 2, 1)))
        val d = today.plusDays(3)
        EventDays.add(d, "State election results")
        assertFalse(LearningFilter.include(d))
        assertTrue(EventDays.upcoming(10).any { it.day == d && it.kind == EventCalendar.Kind.OWNER })
        EventDays.remove(d)
        assertTrue(LearningFilter.include(d))
        assertTrue(EventDays.isEventDay(LocalDate.of(2026, 2, 1)))
    }

    @Test fun theHistoryVaultKeepsAndReadsADayUnderItsCap() {
        HistoryKeeper.init(context)
        val v = HistoryKeeper.vault()!!
        val day = LocalDate.of(2026, 10, 9)
        v.putBars(com.optionslab.ira.HistoryVault.Family.INDEX, day, listOf(com.optionslab.ira.HistoryCodec.Series("NIFTY",
            listOf(com.optionslab.ira.HistoryCodec.Bar(1_791_517_500, 25_000.0, 25_010.0, 24_990.0, 25_005.5, 0, 0)))))
        assertEquals(25_005.5, HistoryKeeper.bars("NIFTY", day).single().close, 0.0)
        assertTrue(HistoryKeeper.bars("NIFTY", day.minusDays(1)).isEmpty())
        assertNull(HistoryKeeper.chain("NIFTY", day))
        assertTrue(HistoryKeeper.setCapMb(50).contains("100 MB"))
        assertEquals(100, v.capMb)
        assertTrue(HistoryKeeper.usageLine().contains("of 100 MB"))
        assertTrue(v.root.path.contains("no_backup") || v.root.path.contains("noBackup") || v.root.path.endsWith("history"))
    }

    /** G9-3: a read that fails or is cancelled keeps the guard's last matrix and stops; never "no trades" from a failure. */
    @Test fun aFailedOrCancelledReadKeepsTheLastMatrix() = runBlocking<Unit> {
        correlated()
        val before = PortfolioHost.guard.matrix
        val stops = PortfolioHost.guard.stops
        PortfolioHost.testOwners = { throw java.io.IOException("the book could not be read") }
        PortfolioHost.refresh()
        assertTrue(PortfolioHost.guard.matrix === before)
        assertEquals(stops, PortfolioHost.guard.stops)
        PortfolioHost.testOwners = { throw kotlinx.coroutines.CancellationException("the brain worker's deadline") }
        var cancelled = false
        try { PortfolioHost.refresh() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true }
        assertTrue("a cancellation goes on up", cancelled)
        assertTrue(PortfolioHost.guard.matrix === before)
    }
}
