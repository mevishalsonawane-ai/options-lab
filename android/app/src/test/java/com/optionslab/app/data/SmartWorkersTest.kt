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

    // ---- the line-by-line review's G8 fixes ------------------------------------------------------------------------------

    private val ist = java.time.ZoneId.of("Asia/Kolkata")
    private fun clockAt(t: java.time.LocalDateTime) { Market.testClock = java.time.Clock.fixed(t.atZone(ist).toInstant(), ist) }

    /** G8-4: before the start's read, an arm's entry checks read nothing from the vault, and a park is never missed. */
    @Test fun theEntryChecksNeverReadTheVaultEvenBeforeTheSettingsAreRead() {
        SecurePrefs.put("brain.parked", "liquidity")
        SmartWorkers.unloadForTest()
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        val parked = SmartWorkers.parkedToPaper("liquidity")
        SmartWorkers.entry("liquidity:BANKNIFTY", "BANKNIFTY", 1)
        SmartWorkers.doorRefusal("BANKNIFTY26OCT52000CE")
        SecurePrefs.countedThread = null
        assertEquals("settings reads on the entry path before the start's read", 0L, SecurePrefs.readsForTest.get())
        assertTrue("not read yet: the entry goes to paper, never live on a guess", parked)
        // The start reads them on an IO thread (as the app's onCreate does).
        SmartWorkers.init(context)
        val end = System.currentTimeMillis() + 10_000
        while (!SmartWorkers.loadedForTest && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue("read at the start", SmartWorkers.loadedForTest)
        assertTrue(SmartWorkers.parkedToPaper("liquidity"))
        assertFalse(SmartWorkers.parkedToPaper("hero"))
    }

    /** G8-4 (related): Boss re-arming before the settings were read clears the SAVED park, not the defaults. */
    @Test fun aRearmBeforeTheReadClearsTheSavedPark() {
        SecurePrefs.put("brain.parked", "liquidity")
        SmartWorkers.unloadForTest()
        SmartWorkers.ownerRearmed("liquidity")
        SmartWorkers.ensureLoaded()
        assertFalse("re-armed by Boss: no longer parked", SmartWorkers.parkedToPaper("liquidity"))
        assertFalse(SecurePrefs.getString("brain.parked").orEmpty().contains("liquidity"))
    }

    /** G8-2: changes from several threads at once (Autopilot's parks, Boss's sheet) never lose one another. */
    @Test fun changesAtOnceAreNeverLost() {
        SmartWorkers.ensureLoaded()
        val start = java.util.concurrent.CountDownLatch(1)
        val a = Thread { start.await(); repeat(200) { SmartWorkers.parkByAutopilot("k$it") } }
        val b = Thread { start.await(); repeat(200) { SmartWorkers.pausePaperByAutopilot("p$it") } }
        val c = Thread { start.await(); repeat(200) { SmartWorkers.setNetFlatSec(15 + it % 40) } }
        listOf(a, b, c).forEach { it.start() }
        start.countDown()
        listOf(a, b, c).forEach { it.join(60_000) }
        val s = SmartWorkers.settings.value
        assertEquals("every park kept", 200, s.parked.size)
        assertEquals("every pause kept", 200, s.paused.size)
        assertEquals(200, SecurePrefs.getString("brain.parked").orEmpty().split(',').count { it.isNotBlank() })
    }

    /** G8-1: a review whose books could not be read (cut at its time limit) leaves the day to the next try. */
    @Test fun aFailedReviewLeavesTheDayToTheNextTry() = runBlocking {
        clockAt(java.time.LocalDateTime.of(2026, 10, 14, 15, 50))
        try {
            SmartWorkers.ensureLoaded()
            SmartWorkers.testReadsFail = true
            SmartWorkers.reviewIfDue(context)
            assertTrue("not marked reviewed", SecurePrefs.getString("brain.reviewedOn") != "2026-10-14")
            SmartWorkers.testReadsFail = false
            SmartWorkers.reviewIfDue(context)
            assertEquals("reviewed on the next try", "2026-10-14", SecurePrefs.getString("brain.reviewedOn"))
        } finally { Market.testClock = null }
    }

    /** G8-3: the relay down, then switched off by Boss: live entries are no longer refused for it. G8-7: its breaker acts. */
    @Test fun theRelaySwitchedOffNoLongerRefusesAndAnOpenBreakerSkipsThePing() {
        com.optionslab.app.work.Tasks.resetRelayForTest()
        var pings = 0
        var t = 1_000_000_000L
        repeat(2) { com.optionslab.app.work.Tasks.relayLook(true, t) { pings++; false }; t += 3_600_000L }
        assertTrue(SmartWorkers.doorRefusal("NIFTY26OCT24500CE")!!.contains("relay"))
        com.optionslab.app.work.Tasks.relayLook(false, t) { pings++; true }
        assertNull("the relay is off: orders go direct", SmartWorkers.doorRefusal("NIFTY26OCT24500CE"))
        assertEquals("no ping while switched off", 2, pings)
        // On again and failing: once its breaker shuts, the warm-up waits for it (its pause), however long the gap.
        com.optionslab.app.work.Tasks.resetRelayForTest()
        repeat(5) { SmartWorkers.health.relay(false, true, t) }
        val before = pings
        com.optionslab.app.work.Tasks.relayLook(true, t + 1_000L) { pings++; false }
        assertEquals("the breaker is shut: no ping", before, pings)
        com.optionslab.app.work.Tasks.relayLook(true, t + 60 * 60_000L) { pings++; true }
        assertEquals("its pause over: one trial", before + 1, pings)
    }

    /** G8-8: an incident over yesterday is not listed under "none today". */
    @Test fun yesterdaysIncidentIsNotTodays() {
        val yday = java.time.LocalDateTime.of(2026, 10, 13, 14, 2).atZone(ist).toInstant().toEpochMilli()
        SmartWorkers.health.relay(false, true, yday); SmartWorkers.health.relay(false, true, yday)
        SmartWorkers.health.evaluate(yday)
        SmartWorkers.health.relay(true, true, yday)
        SmartWorkers.health.evaluate(yday + 1_000L)
        SmartWorkers.health.evaluate(yday + 90_000L)
        assertEquals(1, SmartWorkers.health.past().size)
        clockAt(java.time.LocalDateTime.of(2026, 10, 14, 11, 0))
        try {
            assertTrue(SmartWorkers.diagLines().joinToString("\n"), SmartWorkers.diagLines().any { it.trim() == "Health incidents: none today" })
        } finally { Market.testClock = null }
    }

    /** G9-6 (round 4): the event-day calendar is built before a nightly pass starts (the learning's event-day filter reads it). */
    @Test fun theEventCalendarIsBuiltBeforeTheNightlyPassStarts() {
        EventDays.resetForTest()
        val notYet = Any()
        val seen = java.util.concurrent.atomic.AtomicReference<Any?>(notYet)
        SmartWorkers.nightlyStartForTest = { seen.compareAndSet(notYet, EventDays.builtDayForTest) }
        try {
            runBlocking { SmartWorkers.refresh(context) }
            val end = System.currentTimeMillis() + 10_000
            while (seen.get() === notYet && System.currentTimeMillis() < end) Thread.sleep(10)
            assertTrue("a nightly pass started", seen.get() !== notYet)
            assertEquals(Market.today(), seen.get())
        } finally { SmartWorkers.nightlyStartForTest = null }
    }

    /** G8-6: a big-move alarm is kept through the brain's refresh. */
    @Test fun aBigMoveIsKeptThroughARefresh() = runBlocking {
        val at = System.currentTimeMillis()
        SmartWorkers.bigMove("BANKNIFTY", 1, at)
        SmartWorkers.refresh(context)
        assertEquals(at, SmartWorkers.brain.value.bigMove?.atMs)
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
