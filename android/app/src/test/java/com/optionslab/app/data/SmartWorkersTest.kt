package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Kite
import com.optionslab.ira.Findings
import com.optionslab.ira.MarketBrain
import com.optionslab.ira.Supervisor
import com.optionslab.ira.WorkerWake
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

/**
 * The smart workers in the app (10 Oct): the global pause integrates with the arms' entry check (FlowGate) and the entry door
 * (Broker) - a news window ON blocks a paper entry and a live entry, never an exit nor Boss's own order; SHADOW causes are
 * logged beside the signal and change nothing; the scheduler never skips while anything is held; a review's park only parks;
 * and none of it reads the settings on the order path or takes more than microseconds.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class SmartWorkersTest : RobolectricTest() {
    private lateinit var kite: FakeKite

    @Before fun up() { kite = FakeKite(); kite.login(); SmartWorkers.resetForTest() }
    @After fun down() { SmartWorkers.resetForTest(); kite.close(); assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun newsNow() {
        val now = System.currentTimeMillis()
        SmartWorkers.setBrainForTest(MarketBrain.Context(atMs = now, windows = listOf(MarketBrain.Window("RBI policy", now - 60_000L, now + 120_000L))))
    }

    private fun order(tag: String = "iraorb", side: Kite.Side = Kite.Side.BUY) =
        Kite.Order("BANKNIFTY26OCT52000CE", side, 30, 30, "MIS", "MARKET", null, 0.05, "NFO", tag)

    @Test fun aNewsWindowBlocksAPaperEntryAndALiveEntryButNeverAnExit() = runBlocking {
        newsNow()
        // The arms' entry check: paper and live alike are skipped, with the reason, and logged beside the signal.
        val paper = FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:01", what = "CE")
        assertTrue(paper.skip)
        assertTrue(paper.pausedWhy!!, paper.pausedWhy!!.contains("RBI policy"))
        val live = FlowGate.check("liquidity:BANKNIFTY", "BANKNIFTY", -1, live = true, at = "2026-10-12T10:01", what = "PE")
        assertTrue(live.skip)
        val logged = FlowGate.signalsForTest().associateBy { it.key }
        assertEquals("paused: RBI policy window", logged.getValue("orb").paused)
        assertEquals(com.optionslab.ira.FlowShadow.PAUSED, logged.getValue("orb").verdict)
        // Waiting for Boss's approval: logged, not decided (his approval decides it later).
        assertFalse(FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:02", what = "CE", decide = false).skip)
        // The entry door: a bot's live entry is refused inside it; nothing is sent.
        try { Broker.placeOrder(order()); fail("a bot's entry went through a news window") } catch (e: Broker.KiteError) {
            assertEquals("entry_gate", e.type)
            assertTrue(e.message, e.message!!.contains("RBI policy"))
        }
        assertTrue(kite.placed.isEmpty())
        // An exit always goes; Boss's own order is his call.
        Broker.placeOrder(order(side = Kite.Side.SELL), exit = true)
        Broker.placeOrder(order(tag = "iraalgo"))
        assertEquals(2, kite.placed.size)
        // News windows OFF: the bots' entries go again.
        SmartWorkers.setPause(MarketBrain.Cause.NEWS, MarketBrain.Mode.OFF)
        assertFalse(FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-12T10:03", what = "CE").skip)
        Broker.placeOrder(order())
        assertEquals(3, kite.placed.size)
    }

    @Test fun shadowCausesAreLoggedBesideTheSignalAndChangeNothing() {
        val now = System.currentTimeMillis()
        SmartWorkers.setBrainForTest(MarketBrain.Context(atMs = now, traps = mapOf("BANKNIFTY" to setOf("PULL_BID")),
            bigMove = MarketBrain.BigMove("BANKNIFTY", 1, now)))
        repeat(3) { SmartWorkers.found("w$it", Findings.Kind.BREAK, "BANKNIFTY", -1, null, 90, "down") }
        val t = FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-12T11:01", what = "CE")
        assertFalse("shadow only", t.skip)
        assertNull(t.pausedWhy)
        val s = FlowGate.signalsForTest().single { it.key == "orb" }
        assertEquals(listOf("traps", "bigmove", "consensus"), s.wouldPause)
        assertEquals(0 to 3, s.consensus)
        assertNull(s.paused)
        // Switched to ACT for this strategy, the opposite consensus skips it (and only it).
        SmartWorkers.setReaction("orb", Findings.Reaction.OPPOSITE_CONSENSUS, Findings.Mode.ACT)
        assertTrue(FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-12T11:02", what = "CE").skip)
        assertFalse(FlowGate.check("pine", "BANKNIFTY", 1, live = false, at = "2026-10-12T11:02", what = "CE").skip)
    }

    @Test fun unreliableDataStopsNewEntriesByDefaultTheRelayDownStopsLiveOnes() = runBlocking {
        SmartWorkers.bus.post(Findings.Finding("self-healing", Findings.Kind.DATA_UNRELIABLE, Findings.ALL, atMs = System.currentTimeMillis(), ttlMs = 60_000L))
        assertTrue(SmartWorkers.entry("vix_div", "NIFTY", 1).block)
        assertTrue(SmartWorkers.doorRefusal("NIFTY26OCT24500CE")!!.contains("unreliable"))
        SmartWorkers.resetForTest()
        SmartWorkers.relaySample(false, enabled = true); SmartWorkers.relaySample(false, enabled = true)
        assertTrue(SmartWorkers.doorRefusal("NIFTY26OCT24500CE")!!.contains("relay"))
        assertFalse("the relay only stops live entries (at the door), never a paper one", SmartWorkers.entry("vix_div", "NIFTY", 1).block)
        Broker.placeOrder(order(side = Kite.Side.SELL), exit = true)
        assertEquals("an exit still goes", 1, kite.placed.size)
    }

    @Test fun theEntryChecksReadNothingFromTheSettingsAndTakeMicroseconds() {
        newsNow()
        SmartWorkers.ensureLoaded()
        repeat(2_000) { SmartWorkers.entry("orb", "BANKNIFTY", 1); SmartWorkers.doorRefusal("BANKNIFTY26OCT52000CE") }   // warm
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        val t0 = System.nanoTime()
        repeat(2_000) { SmartWorkers.entry("orb", "BANKNIFTY", 1); SmartWorkers.doorRefusal("BANKNIFTY26OCT52000CE") }
        val perCallUs = (System.nanoTime() - t0) / 2_000.0 / 1_000.0
        SecurePrefs.countedThread = null
        assertEquals("settings reads on the entry checks", 0L, SecurePrefs.readsForTest.get())
        println("speed: entry brake + door check %.1f µs a call".format(perCallUs))
        assertTrue("entry brake + door check took $perCallUs µs a call", perCallUs < 500.0)
        // What the order path and the stream's thread now do on top (a health sample after an answer, an order update's wake).
        val t1 = System.nanoTime()
        repeat(20_000) { SmartWorkers.restSample(true, 120); SmartWorkers.event(WorkerWake.Cause.ORDER_UPDATE) }
        val hotUs = (System.nanoTime() - t1) / 20_000.0 / 1_000.0
        println("speed: health sample + order-update wake %.2f µs a call".format(hotUs))
        assertTrue("health sample + wake took $hotUs µs a call", hotUs < 50.0)
    }

    @Test fun theSchedulerNeverSkipsWhileAnythingIsHeldAndCountsItsSkips() {
        var runs = 0
        val steps = listOf(Supervisor.Step("Night (R3)") { runs++ }, Supervisor.Step("an unknown step") { runs++ })
        assertEquals(2, SmartWorkers.dueSteps(steps, holding = false, inHours = true).size)       // the first look
        // Again at once, nothing new: the known worker is skipped, the unknown one never.
        assertEquals(listOf("an unknown step"), SmartWorkers.dueSteps(steps, holding = false, inHours = true).map { it.name })
        assertEquals("holding: every step, every look", 2, SmartWorkers.dueSteps(steps, holding = true, inHours = true).size)
        assertEquals("holding: even out of hours", 2, SmartWorkers.dueSteps(steps, holding = true, inHours = false).size)
        // An order update wakes it after its minimum gap only (not at once).
        SmartWorkers.event(WorkerWake.Cause.ORDER_UPDATE)
        assertEquals(1, SmartWorkers.dueSteps(steps, holding = false, inHours = true).size)
        // Out of its hours a flat worker sleeps.
        assertEquals(listOf("an unknown step"), SmartWorkers.dueSteps(steps, holding = false, inHours = false).map { it.name })
        val c = SmartWorkers.sched.counts().getValue("Night (R3)")
        assertTrue(c.skips >= 2)
        assertTrue(c.asleep >= 1)
        assertTrue(SmartWorkers.speedLine(), SmartWorkers.speedLine().contains("Night (R3)"))
        assertTrue(SmartWorkers.diagLines().any { it.contains("Wakes and skips today") })
    }

    @Test fun aReviewsParkOnlyParksAndOnlyBossBringsItBack() {
        SmartWorkers.ensureLoaded()
        assertFalse(SmartWorkers.parkedToPaper("liquidity"))
        // A drifting paper strategy under PAUSE: its new paper entries are skipped, with the reason.
        SmartWorkers.setPaperDrift(com.optionslab.ira.DriftReview.PaperPolicy.PAUSE)
        val s = SmartWorkers.settings.value
        SmartWorkers.setPause(MarketBrain.Cause.NEWS, MarketBrain.Mode.ON)
        assertEquals(com.optionslab.ira.DriftReview.PaperPolicy.PAUSE, SmartWorkers.settings.value.paperDrift)
        assertTrue(s.parked.isEmpty())
        // Nothing in the smart workers can arm, size up or un-park: only Boss's own re-arm clears it.
        SmartWorkers.ownerRearmed("liquidity")
        assertFalse(SmartWorkers.parkedToPaper("liquidity"))
        assertTrue(SmartWorkers.reviewAnswer().contains("No review yet") || SmartWorkers.reviewAnswer().contains("Against their backtests"))
    }

    @Test fun theBrainsWorkerRunsWithoutTheMarketAndKeepsItsLog() = runBlocking {
        SmartWorkers.found("Liquidity 15+5", Findings.Kind.SWEEP, "BANKNIFTY", 1, 55_100.0, 70, "sweep taken at 55,100")
        SmartWorkers.found("Liquidity 15+5", Findings.Kind.SWEEP, "BANKNIFTY", 1, 55_100.0, 70, "sweep taken at 55,100")
        assertEquals("the same finding twice within 5 minutes is posted once", 1, SmartWorkers.bus.recent(System.currentTimeMillis()).size)
        SmartWorkers.refresh(context)
        assertTrue(SmartWorkers.brain.value.atMs > 0)
        assertTrue(SmartWorkers.answer("BANKNIFTY").contains("1 bullish, 0 bearish"))
        assertTrue(SmartWorkers.feed().any { it.contains("sweep taken at 55,100") })
    }
}
