package com.optionslab.app.ira

import com.optionslab.app.data.ManagerSpecialists
import com.optionslab.app.data.Market
import com.optionslab.app.data.SmartWorkers
import com.optionslab.app.data.TradeManagerHost
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.AutoChange
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * Autopilot learning in the app ([Autopilot]): a paper change applied through its agent's setter and logged in
 * noBackupFilesDir; a live raise only ever offered (and not done on a plain yes); a live risk-lowering change done by itself,
 * whose Undo asks for the PIN; the setting (SAFE takes the PIN while live; OFF and SHADOW-ONLY never); "turn off autopilot
 * learning" by voice, and a voice raise refused; a paper probation that got worse reverting itself; Jarvis's answers.
 */
class AutopilotTest : RobolectricTest() {
    private lateinit var day: LocalDate

    private fun at(t: LocalTime) { Market.testClock = Clock.fixed(day.atTime(t).atZone(IST).toInstant(), IST) }
    private fun ms(d: LocalDate, h: Int = 11) = d.atTime(h, 0).atZone(IST).toInstant().toEpochMilli()

    /** A lesson with [n] cases over 30 days before [day], all of sign [sign]. */
    private fun lesson(id: String, name: String, sign: Int, n: Int = 60): AgentLessons.Lesson {
        val days = 30; val per = n / days
        val bs = (0 until days).map { AgentLessons.Bucket(day.minusDays((days - it).toLong()), per, sign * 100.0 * per, if (sign > 0) per else 0) }
        val e = AgentLessons.evidence(AgentLessons.Kind.RUPEES, id, bs)
        return AgentLessons.Lesson(id, AgentLessons.Topic.STRATEGY, AgentLessons.Kind.RUPEES, name, emptyList(), setOf("test"), bs, sign,
            AgentLessons.Status.CONFIRMED, AgentLessons.confidence(e), day.minusDays(30), day.minusDays(1), day)
    }

    /** A settled managed Pine trade at [atMs] on [account]: the OI specialist led an early exit worth [vs] against the original rules. */
    private fun settled(i: Int, atMs: Long, vs: Double, account: TradeManager.Account = TradeManager.Account.PAPER): TradeManager.Record {
        val trade = TradeManager.ManagedTrade("pine:x", "t$i", "Pine", "NIFTY", 1, "NIFTY25OCT25000CE", 100.0, 75, 70.0, 160.0,
            TradeManager.Caps(atMs + 3_600_000L), account, atMs, 1.0)
        val note = TradeManager.Note(atMs, TradeManager.EXIT_EARLY, "team", "out", 110.0, true, lead = "oi", voters = listOf("oi"))
        return TradeManager.Record(trade, TradeManager.Mode.ACT, listOf(note), actual = TradeManager.Exit(atMs, 110.0, "trade manager"),
            manager = TradeManager.Exit(atMs, 110.0, "trade manager"), original = TradeManager.Exit(atMs + 1, 110.0 - vs / 75, "STOP"), actedAtMs = atMs)
    }

    @Before fun up() {
        assumeTrue("Autopilot learning is in IraAlgo with Jarvis only", Autopilot.enabled())
        day = AutomationSupport.tradingDayAt(16, 0).toLocalDate()
        at(LocalTime.of(16, 0))
        Autopilot.resetForTest(); JarvisLearning.resetForTest(); SmartWorkers.resetForTest(); TradeManagerHost.resetForTest()
        Autopilot.init(context)
        Autopilot.testLive = false
        runBlocking { IraHub.forgetAll() }
    }

    @After fun down() {
        Autopilot.resetForTest(); JarvisLearning.resetForTest(); SmartWorkers.resetForTest(); TradeManagerHost.resetForTest()
        runBlocking { IraHub.forgetAll() }
        IraHub.clearRequests()
        Market.testClock = null
    }

    @Test fun aPaperChangeIsAppliedThroughItsAgentAndLogged() = runBlocking<Unit> {
        Autopilot.testInputs = { d -> AutoChange.Inputs(d, listOf(lesson("strat:night (r3)", "Night (R3)", -1, 30))) }
        assertTrue(Autopilot.runIfDue(context))
        // Done through the strategies' own pause (exits always go), and logged on probation.
        assertTrue("night_r3" in SmartWorkers.settings.value.paused)
        val e = Autopilot.book.value.entries.single()
        assertEquals("paper:night_r3", e.change.target); assertEquals(AutoChange.Status.PROBATION, e.status)
        assertEquals(AutoChange.Scope.PAPER, e.change.scope)
        // Once a day.
        assertFalse(Autopilot.runIfDue(context))
        // Kept in noBackupFilesDir and read back the same.
        val f = File(context.noBackupFilesDir, "autopilot/log.txt")
        assertTrue(f.isFile)
        assertEquals(Autopilot.book.value.entries.map { it.id }, AutoChange.decode(f.readText()).entries.map { it.id })
        // Jarvis's answers come from the log.
        assertTrue(Autopilot.answer(AutoChange.Ask(AutoChange.AskKind.TODAY)).contains("Night (R3)'s new paper entries: on → paused"))
        assertTrue(Autopilot.answer(AutoChange.Ask(AutoChange.AskKind.HOW)).contains("1 on probation"))
        // A paper undo needs nothing.
        assertFalse(Autopilot.undoNeedsPin(e.id))
        assertTrue(Autopilot.undo(e.id).startsWith("Undone"))
        assertFalse("night_r3" in SmartWorkers.settings.value.paused)
        assertEquals(AutoChange.Status.UNDONE, Autopilot.book.value.entries.single().status)
    }

    @Test fun offWithNothingDoneAndTheMiddayPassOnlyLowersRisk() = runBlocking<Unit> {
        Autopilot.testInputs = { d -> AutoChange.Inputs(d, listOf(lesson("strat:night (r3)", "Night (R3)", -1, 30))) }
        Autopilot.setMode(AutoChange.Mode.OFF)
        Autopilot.run(day, healthOnly = false)
        assertTrue(Autopilot.book.value.entries.isEmpty())
        assertFalse("night_r3" in SmartWorkers.settings.value.paused)
        Autopilot.setMode(AutoChange.Mode.SAFE)
        // 12:30: a shadow knob (not risk-lowering) waits for the evening.
        Autopilot.resetForTest(); Autopilot.testLive = false
        Autopilot.testInputs = { d -> AutoChange.Inputs(d, listOf(lesson("trap:stop_hunt", "Trades taken with the trap guard's \"possible stop hunt\" flag up", -1, 30))) }
        at(LocalTime.of(12, 35))
        assertTrue(Autopilot.runIfDue(context))
        assertTrue(Autopilot.book.value.entries.isEmpty())
        assertTrue(Autopilot.knobs.value.isEmpty())
    }

    @Test fun aLiveRaiseIsOnlyOffered() = runBlocking<Unit> {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        // Pine's manager would have beaten its original rules on live, 40 times: acting on live adds risk - asked, never done.
        TradeManagerHost.recordsForTest((0 until 40).map { settled(it, ms(day.minusDays(40L - it)), 300.0, TradeManager.Account.LIVE) })
        Autopilot.testInputs = { d -> AutoChange.Inputs(d, records = TradeManagerHost.records.value, policy = TradeManagerHost.policy.value) }
        Autopilot.run(day, healthOnly = false)
        assertEquals(TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode("pine", TradeManager.Account.LIVE))
        val e = Autopilot.book.value.entries.single()
        assertEquals(AutoChange.Status.OFFERED, e.status); assertEquals(AutoChange.Scope.LIVE, e.change.scope)
        val pending = IraHub.state.value.pending
        assertEquals(1, pending.size)
        // A plain yes does not do it: it adds live risk, so it takes the PIN in the change log.
        IraHub.confirm(pending.first())
        assertEquals(TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode("pine", TradeManager.Account.LIVE))
        assertTrue(Autopilot.acceptAsked(e.id, pinConfirmed = false).contains("PIN"))
        assertEquals(TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode("pine", TradeManager.Account.LIVE))
        // With the PIN (the change log's Apply): done, and on probation.
        Autopilot.acceptAsked(e.id, pinConfirmed = true)
        assertEquals(TradeManager.Mode.ACT, TradeManagerHost.policy.value.mode("pine", TradeManager.Account.LIVE))
        assertEquals(AutoChange.Status.PROBATION, Autopilot.book.value.entries.single().status)
    }

    @Test fun aLiveRiskLoweringChangeIsMadeAndItsUndoAsksForThePin() = runBlocking<Unit> {
        TradeManagerHost.setMode("pine", TradeManager.Account.LIVE, TradeManager.Mode.ACT, pinConfirmed = true)
        Autopilot.testInputs = { d -> AutoChange.Inputs(d, listOf(lesson("spec:pine:oi", "The OI specialist on Pine", -1)), policy = TradeManagerHost.policy.value) }
        Autopilot.run(day, healthOnly = false)
        assertTrue(ManagerSpecialists.book.value.frozen["pine"]?.muted?.containsKey("oi") == true)
        val e = Autopilot.book.value.entries.single()
        assertEquals(AutoChange.Scope.LIVE, e.change.scope); assertEquals(AutoChange.Status.PROBATION, e.status)
        // Undoing it raises live risk: the PIN, and nothing changes without it.
        assertTrue(Autopilot.undoNeedsPin(e.id))
        assertEquals(Autopilot.PIN_UNDO, Autopilot.undo(e.id))
        assertTrue(ManagerSpecialists.book.value.frozen["pine"]?.muted?.containsKey("oi") == true)
        assertTrue(Autopilot.undo(e.id, pinConfirmed = true).startsWith("Undone"))
        assertFalse(ManagerSpecialists.book.value.frozen["pine"]?.muted?.containsKey("oi") == true)
        assertEquals(AutoChange.Status.UNDONE, Autopilot.book.value.entries.single().status)
        // The live trade manager itself was never touched, and nothing was asked.
        assertEquals(TradeManager.Mode.ACT, TradeManagerHost.policy.value.mode("pine", TradeManager.Account.LIVE))
    }

    @Test fun aPaperProbationThatGotWorseRevertsItself() = runBlocking<Unit> {
        // Muted on paper 8 days ago; since then the OI specialist's votes would have helped every time.
        ManagerSpecialists.mute("pine", "oi", by = "Autopilot")
        val then = ms(day.minusDays(8))
        val c = AutoChange.Change(AutoChange.Kind.SPECIALIST_MUTE, "spec:pine:oi", AutoChange.COUNTS, AutoChange.MUTED, "hurt",
            AutoChange.Evidence(40, -4_000.0, AgentLessons.Confidence.STRONG, -1), AutoChange.Risk.LOWER, AutoChange.Scope.PAPER, "spec:pine:oi", -1, -1,
            what = "the OI specialist on Pine (paper)")
        Autopilot.setForTest(AutoChange.record(AutoChange.Book(), c, AutoChange.Status.PROBATION, then, day.minusDays(8)).first)
        TradeManagerHost.recordsForTest((0 until 20).map { settled(it, then + (it + 1) * 3_600_000L, 400.0 + it) })
        Autopilot.testInputs = { d -> AutoChange.Inputs(d) }
        Autopilot.run(day, healthOnly = false)
        val e = Autopilot.book.value.entries.single()
        assertEquals(AutoChange.Status.REVERTED, e.status)
        assertTrue(e.note, e.note.startsWith("reverted:"))
        assertFalse(ManagerSpecialists.book.value.tuned["pine"]?.muted?.containsKey("oi") == true)
    }

    @Test fun theSettingTakesThePinOnlyForSafeWhileLive() {
        assertEquals(AutoChange.Mode.SAFE, Autopilot.mode.value)
        Autopilot.setMode(AutoChange.Mode.OFF)
        assertEquals(AutoChange.Mode.OFF, Autopilot.mode.value)
        Autopilot.testLive = true
        assertFalse(Autopilot.needsPin(AutoChange.Mode.SHADOW_ONLY))
        assertTrue(Autopilot.needsPin(AutoChange.Mode.SAFE))
        assertEquals(Autopilot.PIN_NEEDED, Autopilot.setMode(AutoChange.Mode.SAFE))
        assertEquals(AutoChange.Mode.OFF, Autopilot.mode.value)
        Autopilot.setMode(AutoChange.Mode.SHADOW_ONLY)
        assertEquals(AutoChange.Mode.SHADOW_ONLY, Autopilot.mode.value)
        Autopilot.setMode(AutoChange.Mode.SAFE, pinConfirmed = true)
        assertEquals(AutoChange.Mode.SAFE, Autopilot.mode.value)
        // Paper only: SAFE needs nothing.
        Autopilot.testLive = false
        Autopilot.setMode(AutoChange.Mode.OFF)
        assertFalse(Autopilot.needsPin(AutoChange.Mode.SAFE))
        Autopilot.setMode(AutoChange.Mode.SAFE)
        assertEquals(AutoChange.Mode.SAFE, Autopilot.mode.value)
        // Never carried by a backup.
        assertFalse(com.optionslab.ira.Upkeep.carried(Autopilot.K_MODE))
    }

    @Test fun byVoiceOffWorksAndARaiseIsRefused() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        IraHub.ask("turn off autopilot learning")
        assertEquals(AutoChange.Mode.OFF, Autopilot.mode.value)
        assertTrue(IraHub.state.value.messages.last().text.startsWith("Autopilot learning is off"))
        IraHub.ask("turn on autopilot learning")
        assertEquals(AutoChange.Mode.OFF, Autopilot.mode.value)
        assertEquals(AutoChange.VOICE_RAISE, IraHub.state.value.messages.last().text)
        IraHub.ask("set autopilot to safe")
        assertEquals(AutoChange.Mode.OFF, Autopilot.mode.value)
        // Nothing waits for a yes: neither is a command.
        assertTrue(IraHub.state.value.pending.isEmpty())
        // Only ever lower by voice: SHADOW-ONLY to OFF yes, OFF to SHADOW-ONLY no.
        assertEquals(AutoChange.VOICE_RAISE, Autopilot.voiceSet(AutoChange.Mode.SHADOW_ONLY))
        Autopilot.setMode(AutoChange.Mode.SHADOW_ONLY)
        Autopilot.voiceSet(AutoChange.Mode.OFF)
        assertEquals(AutoChange.Mode.OFF, Autopilot.mode.value)
        assertNotEquals(AutoChange.Mode.SAFE, Autopilot.mode.value)
    }

    @Test fun jarvisAnswersWhatChangedAndUndoIsAsked() = runBlocking<Unit> {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        Autopilot.testInputs = { d -> AutoChange.Inputs(d, listOf(lesson("strat:night (r3)", "Night (R3)", -1, 30))) }
        Autopilot.run(day, healthOnly = false)
        IraHub.ask("what did you change today")
        assertTrue(IraHub.state.value.messages.last().text.startsWith("Today, Boss:"))
        IraHub.ask("why did you pause night")
        assertTrue(IraHub.state.value.messages.last().text.contains("because Night (R3) loses money on paper"))
        IraHub.ask("how is autopilot doing")
        assertTrue(IraHub.state.value.messages.last().text.startsWith("Autopilot learning is safe:"))
        // "Undo your last change": a request, not done until Boss says yes.
        IraHub.ask("undo your last change")
        assertEquals(1, IraHub.state.value.pending.size)
        assertTrue("night_r3" in SmartWorkers.settings.value.paused)
        IraHub.confirm(IraHub.state.value.pending.first())
        assertFalse("night_r3" in SmartWorkers.settings.value.paused)
    }
}
