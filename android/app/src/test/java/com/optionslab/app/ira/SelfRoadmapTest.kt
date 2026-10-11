package com.optionslab.app.ira

import com.optionslab.app.data.AppHealth
import com.optionslab.app.data.Market
import com.optionslab.app.data.SmartWorkers
import com.optionslab.app.data.TradeManagerHost
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.BackgroundBudget
import com.optionslab.engine.IST
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.AutoChange
import com.optionslab.ira.BossTrades
import com.optionslab.ira.BudgetRule
import com.optionslab.ira.ChangeTimeline
import com.optionslab.ira.FreezeRule
import com.optionslab.ira.HealthBook
import com.optionslab.ira.SelfTalk
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/**
 * The roadmap in the app (S10-S21): the learning freeze gate stopping Autopilot and the health tuner while recording goes
 * on, and its unfreeze (PIN when live); "Undo everything today" through each change's own undo, newest first, live raises
 * left for the PIN; the monthly scorecard; Boss's hand trades recorded with ZERO settings reads on the order path; the
 * battery budget with a fake phone; the redacted health report; the study pages from the bundled asset; today's lesson and
 * a bot trade's replay from today's records; Jarvis's answers.
 */
class SelfRoadmapTest : RobolectricTest() {
    private lateinit var day: LocalDate

    private fun at(t: LocalTime) { Market.testClock = Clock.fixed(day.atTime(t).atZone(IST).toInstant(), IST) }
    private fun ms(h: Int, m: Int = 0) = day.atTime(h, m).atZone(IST).toInstant().toEpochMilli()

    private val ev = AutoChange.Evidence(30, -3_000.0, AgentLessons.Confidence.STRONG, -1)
    private fun pause(key: String) = AutoChange.Change(AutoChange.Kind.PAPER_ENTRIES, "paper:$key", AutoChange.ON, AutoChange.PAUSED, "it loses", ev,
        AutoChange.Risk.LOWER, AutoChange.Scope.PAPER, expect = -1, effect = -1, what = "$key's new paper entries")
    private fun park(key: String) = AutoChange.Change(AutoChange.Kind.LIVE_ENTRIES, "live:$key", AutoChange.LIVE, AutoChange.PARKED, "drifting", ev,
        AutoChange.Risk.LOWER, AutoChange.Scope.LIVE, expect = -1, effect = -1, what = "$key's new live entries")

    @Before fun up() {
        AreaE.resetGlobals()
        day = AutomationSupport.tradingDayAt(16, 0).toLocalDate()
        at(LocalTime.of(16, 0))
        SmartWorkers.resetForTest(); TradeManagerHost.resetForTest()
        Autopilot.init(context); SelfJobs.init(context)
        Autopilot.testLive = false
        LearningFreeze.resetForTest(); BossTradeLog.resetForTest(); SelfJobs.resetForTest(); BackgroundBudget.resetForTest()
    }

    @After fun down() {
        AreaE.resetGlobals(); SmartWorkers.resetForTest(); TradeManagerHost.resetForTest()
        SecurePrefs.countedThread = null
        Market.testClock = null
    }

    // ---- S14: the learning freeze --------------------------------------------------------------------------------------------

    @Test fun whileFrozenAutopilotRecordsButAppliesNothing() = runBlocking<Unit> {
        assumeTrue("the learning freeze and Boss's trades are in IraAlgo with Jarvis only", Autopilot.enabled())
        LearningFreeze.setForTest(FreezeRule.State(true, setOf(FreezeRule.Account.PAPER), day, "paper fell"))
        assertTrue(LearningFreeze.holds())
        Autopilot.testInputs = { d -> AutoChange.Inputs(d, listOf(lesson("strat:night (r3)", "Night (R3)", -1, 30))) }
        Autopilot.run(day, healthOnly = false)
        assertFalse("nothing applied while frozen", "night_r3" in SmartWorkers.settings.value.paused)
        val e = Autopilot.book.value.entries.single()
        assertEquals(AutoChange.Status.SKIPPED, e.status)
        assertEquals(LearningFreeze.NOTE, e.note)
    }

    @Test fun whileFrozenTheHealthTunerKeepsSamplingButDoesNotRetune() {
        assumeTrue("the learning freeze and Boss's trades are in IraAlgo with Jarvis only", Autopilot.enabled())
        LearningFreeze.setForTest(FreezeRule.State(true, setOf(FreezeRule.Account.PAPER), day, "paper fell"))
        var b = HealthBook.Book()
        repeat(5) { b = HealthBook.stall(b, ms(10, it), 900, "Choreographer.doFrame in BrainSection.invoke") }
        AppHealth.setForTest(b)
        assertTrue(AppHealth.tick(context, force = true).isEmpty())
        assertEquals(healthDefault(), AppHealth.cadenceMs("brain.consensus"))
        LearningFreeze.setForTest(FreezeRule.State())
        assertTrue(AppHealth.tick(context, force = true).isNotEmpty())
    }

    private fun healthDefault() = com.optionslab.ira.HealthTuner.defaultMs("brain.consensus")

    @Test fun aFallAfterTheAgentsChangesFreezesAndOnlyBossUnfreezesWithThePinWhenLive() {
        assumeTrue("the learning freeze and Boss's trades are in IraAlgo with Jarvis only", Autopilot.enabled())
        Autopilot.setForTest(AutoChange.record(AutoChange.Book(), pause("night_r3"), AutoChange.Status.PROBATION, ms(15, 50), day).first)
        LearningFreeze.testDaily = { live -> if (live) emptyMap() else mapOf(day to -5_000.0) }
        LearningFreeze.refresh(day)
        assertTrue(LearningFreeze.state.value.frozen)
        assertTrue(LearningFreeze.answer().startsWith("Yes, Boss"))
        LearningFreeze.testLive = true
        assertEquals(FreezeRule.PIN_NEEDED, LearningFreeze.unfreeze(pinConfirmed = false))
        assertTrue(LearningFreeze.state.value.frozen)
        assertTrue(LearningFreeze.unfreeze(pinConfirmed = true).startsWith("Unfrozen"))
        assertFalse(LearningFreeze.holds())
        // The same fall is not counted again after the unfreeze.
        LearningFreeze.refresh(day)
        assertFalse(LearningFreeze.state.value.frozen)
    }

    // ---- S13: today's changes and undo everything ----------------------------------------------------------------------------

    @Test fun undoEverythingTodayUndoesEachThroughItsOwnPathAndLeavesLiveRaisesForThePin() {
        SmartWorkers.pausePaperByAutopilot("night_r3")
        SmartWorkers.parkByAutopilot("liquidity")
        var b = AutoChange.record(AutoChange.Book(), pause("night_r3"), AutoChange.Status.PROBATION, ms(15, 50), day).first
        b = AutoChange.record(b, park("liquidity"), AutoChange.Status.PROBATION, ms(15, 51), day).first
        Autopilot.setForTest(b)
        var hb = HealthBook.Book(tuned = mapOf("brain.consensus" to HealthBook.Tuned("brain.consensus", 30_000L, day, day, "3 stalls there")))
        AppHealth.setForTest(hb)
        val items = ChangeTimelineHost.items(day)
        assertEquals(3, items.size)
        assertEquals(ChangeTimeline.UndoState.NEEDS_PIN, items.single { it.what.startsWith("liquidity") }.undo)
        val said = ChangeTimelineHost.undoAll(pinConfirmed = false)
        assertTrue(said, said.contains("Needs your PIN"))
        assertFalse("the paper pause is undone", "night_r3" in SmartWorkers.settings.value.paused)
        assertTrue("the live park is left for the PIN", "liquidity" in SmartWorkers.settings.value.parked)
        assertEquals(com.optionslab.ira.HealthTuner.defaultMs("brain.consensus"), AppHealth.cadenceMs("brain.consensus"))
        // With the PIN, the rest goes too.
        ChangeTimelineHost.undoAll(pinConfirmed = true)
        assertFalse("liquidity" in SmartWorkers.settings.value.parked)
        assertTrue(Autopilot.book.value.entries.all { it.status == AutoChange.Status.UNDONE })
    }

    @Test fun jarvisPutsUndoEverythingAsARequestAndSaysWhatChanged() {
        Autopilot.setForTest(AutoChange.record(AutoChange.Book(), pause("night_r3"), AutoChange.Status.PROBATION, ms(15, 50), day).first)
        val said = SelfAnswers.answer(SelfTalk.Ask(SelfTalk.Kind.TIMELINE), locked = false)
        assertTrue(said, said.startsWith("Today 1 automatic change"))
        val asked = SelfAnswers.answer(SelfTalk.Ask(SelfTalk.Kind.UNDO_ALL), locked = false)
        assertTrue(asked, asked.startsWith("Asked: undo 1"))
        // Only asked: nothing undone yet.
        assertEquals(AutoChange.Status.PROBATION, Autopilot.book.value.entries.single().status)
    }

    // ---- S15: the scorecard --------------------------------------------------------------------------------------------------

    @Test fun theMonthlyScorecardScoresLastMonthsChanges() {
        val month = YearMonth.from(day).minusMonths(1)
        val inMonth = month.atDay(10)
        val (b, e) = AutoChange.record(AutoChange.Book(), pause("night_r3"), AutoChange.Status.PROBATION, 0L, inMonth)
        Autopilot.setForTest(b.copy(entries = b.entries.map { if (it.id == e.id) it.copy(status = AutoChange.Status.KEPT, effect = 1_500.0) else it }))
        val c = MonthlyScorecard.make(month)
        assertEquals(1, c.changes)
        assertEquals(1_500.0, c.total, 1e-9)
        assertTrue(SelfAnswers.answer(SelfTalk.Ask(SelfTalk.Kind.SCORECARD), locked = false).contains("+₹1,500"))
    }

    // ---- S10: Boss's hand trades -----------------------------------------------------------------------------------------

    @Test fun theOrderPathsHookReadsNothingFromTheSettingsVault() {
        BossTradeLog.filled("paper:warm", "NIFTY26OCT25000CE", buy = true, live = false, entry = 100.0)
        LearningFreeze.holds()
        SecurePrefs.readsForTest.set(0)
        SecurePrefs.countedThread = Thread.currentThread()
        repeat(200) { i ->
            BossTradeLog.filled("paper:$i", "NIFTY26OCT25000CE", buy = i % 2 == 0, live = false, entry = 100.0)
            LearningFreeze.holds()
        }
        SecurePrefs.countedThread = null
        assertEquals("settings reads on the order path's hook", 0L, SecurePrefs.readsForTest.get())
        // The context is recorded off the order path; nothing is left running into the next test.
        BossTradeLog.drain()
        com.optionslab.app.testing.Background.await("the fills recorded") { BossTradeLog.pending() == 0 }
    }

    @Test fun aHandTradeIsKeptWithItsReasonAndJarvisFormsHisRules() {
        assumeTrue("the learning freeze and Boss's trades are in IraAlgo with Jarvis only", Autopilot.enabled())
        BossTradeLog.filled("paper:1", "NIFTY26OCT25000CE", buy = true, live = false, entry = 100.0, stop = 90.0, target = 120.0, closing = false)
        val p = checkNotNull(BossTradeLog.prompt.value)
        assertFalse(p.exit)
        BossTradeLog.drain()
        BossTradeLog.answer(p, why = BossTrades.Why.LEVEL, note = "held the level")
        com.optionslab.app.testing.Background.await("the reason kept") { BossTradeLog.all().singleOrNull()?.why == BossTrades.Why.LEVEL }
        val t = BossTradeLog.all().single()
        assertEquals(2.0, t.rr!!, 1e-9)
        assertEquals("held the level", t.note)
        // His rules from his own record; and the soft warning before an order at a losing time.
        val ts = (0 until 11).map { i -> BossTrades.Trade("paper:g$i", "NIFTY", false, ms(14, 20) - i * 86_400_000L, BossTrades.Why.GUT,
            closedMs = ms(14, 50), net = if (i < 2) 300.0 else -500.0, exitWhy = BossTrades.ExitWhy.FEAR) }
        BossTradeLog.setForTest(ts)
        assertNotNull(BossTradeLog.warning(14 * 60 + 30))
        assertEquals(SelfAnswers.LOCKED, SelfAnswers.answer(SelfTalk.Ask(SelfTalk.Kind.HABITS), locked = true))
        assertTrue(SelfAnswers.answer(SelfTalk.Ask(SelfTalk.Kind.HABITS), locked = false).contains("gut (11)"))
    }

    // ---- S18: the battery and heat budget ----------------------------------------------------------------------------------

    @Test fun theBudgetHoldsBackgroundJobsOnALowHotOrSavingPhoneAndCapsTheNight() {
        BackgroundBudget.testNow = LocalDateTime.of(2026, 10, 12, 21, 0)
        BackgroundBudget.testPhone = BudgetRule.Phone(80, false, 33.0, false)
        var cpu = 0L
        BackgroundBudget.testCpuMs = { cpu }
        assertTrue(BackgroundBudget.allow(BudgetRule.Job.FORGE))
        BackgroundBudget.testPhone = BudgetRule.Phone(20, false, 33.0, false)
        assertFalse(BackgroundBudget.allow(BudgetRule.Job.FORGE))
        BackgroundBudget.testPhone = BudgetRule.Phone(80, false, 42.0, false)
        assertFalse(BackgroundBudget.allow(BudgetRule.Job.HISTORY))
        BackgroundBudget.testPhone = BudgetRule.Phone(80, false, 33.0, true)
        assertFalse(BackgroundBudget.allow(BudgetRule.Job.LEARNING))
        BackgroundBudget.testPhone = BudgetRule.Phone(80, true, 33.0, false)
        // A forge run that burns the night's CPU: held once the cap is spent.
        assertTrue(BackgroundBudget.allow(BudgetRule.Job.FORGE))
        cpu += BudgetRule.NIGHT_CPU_MS + 1
        assertFalse(BackgroundBudget.allow(BudgetRule.Job.FORGE))
        // The next night starts afresh.
        BackgroundBudget.testNow = LocalDateTime.of(2026, 10, 13, 21, 0)
        assertTrue(BackgroundBudget.allow(BudgetRule.Job.FORGE))
    }

    // ---- S17: the health report ----------------------------------------------------------------------------------------------

    @Test fun theHealthReportCarriesStallsAndNoSecret() {
        SecurePrefs.putAll(mapOf("kite.apiKey" to "plantedapikey1234", "kite.userId" to "QX4321"))
        var b = HealthBook.Book()
        b = HealthBook.stall(b, ms(10), 1_400, "Choreographer.doFrame in BrainSection.invoke")
        AppHealth.setForTest(b)
        val text = com.optionslab.app.data.HealthReport.text(context)
        assertTrue(text, text.contains("BrainSection.invoke"))
        assertFalse(text.contains("plantedapikey1234"))
        assertFalse(text.contains("QX4321"))
        val zip = com.optionslab.app.data.HealthReport.write(context)
        assertTrue(zip.isFile && zip.name.endsWith(".zip"))
        assertTrue(zip.parentFile!!.path.contains("exports"))
    }

    // ---- S19-S21: study, lesson, replay -----------------------------------------------------------------------------------

    @Test fun theStudyPagesAreBundledAndJarvisOpensOne() {
        val pages = StudyLibrary.pages(context)
        assertTrue(pages.size >= 17)
        val said = SelfAnswers.answer(SelfTalk.Ask(SelfTalk.Kind.STUDY, "vwap"), locked = false, context = context)
        assertTrue(said, said.startsWith("Opening the study page \"VWAP\""))
        assertEquals("vwap", StudyLibrary.open.value)
    }

    private fun closed(id: String, family: String, label: String, exitAt: Long, exit: Double, original: Double): TradeManager.Record {
        val t = TradeManager.ManagedTrade("$family:x", id, label, "NIFTY", 1, "NIFTY26OCT25000CE", 100.0, 75, 85.0, 140.0,
            TradeManager.Caps(ms(15, 10)), TradeManager.Account.PAPER, ms(10))
        return TradeManager.Record(t, TradeManager.Mode.ACT, listOf(TradeManager.Note(exitAt - 60_000, TradeManager.EXIT_EARLY, "FLOW", "the flow turned", exit, true, lead = "flow")),
            actual = TradeManager.Exit(exitAt, exit, "EARLY"), manager = TradeManager.Exit(exitAt, exit, "EARLY"), original = TradeManager.Exit(exitAt + 600_000, original, "TARGET"),
            actedAtMs = ms(10))
    }

    @Test fun todaysLessonComesFromTodaysRecordsAndAReplayOpensFromJarvis() = runBlocking<Unit> {
        TradeManagerHost.recordsForTest(listOf(closed("liq-1", "liquidity", "Liquidity 15+5", ms(10, 30), 110.0, 130.0)))
        val l = checkNotNull(SelfJobs.buildLesson(day))
        assertTrue(l.text, l.text.contains("trailed the original rules by ₹1,500"))
        assertTrue(SelfJobs.answerLesson().startsWith("Today's lesson"))
        val said = SelfAnswers.answer(SelfTalk.Ask(SelfTalk.Kind.REPLAY, "liquidity"), locked = false)
        assertTrue(said, said.startsWith("Opening the replay of Liquidity 15+5"))
        assertEquals("liq-1", BotReplayStore.open.value)
        BotReplayStore.testBars = { emptyList() }
        val r = checkNotNull(BotReplayStore.load("liq-1"))
        assertTrue(r.counterfactual!!.startsWith("The original rules would have exited at 130"))
        assertNull(BotReplayStore.load("gone"))
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------

    private fun lesson(id: String, name: String, sign: Int, n: Int = 60): AgentLessons.Lesson {
        val days = 30; val per = n / days
        val bs = (0 until days).map { AgentLessons.Bucket(day.minusDays((days - it).toLong()), per, sign * 100.0 * per, if (sign > 0) per else 0) }
        val e = AgentLessons.evidence(AgentLessons.Kind.RUPEES, id, bs)
        return AgentLessons.Lesson(id, AgentLessons.Topic.STRATEGY, AgentLessons.Kind.RUPEES, name, emptyList(), setOf("test"), bs, sign,
            AgentLessons.Status.CONFIRMED, AgentLessons.confidence(e), day.minusDays(30), day.minusDays(1), day)
    }
}
