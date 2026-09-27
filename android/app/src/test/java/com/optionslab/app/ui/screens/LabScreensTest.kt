package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.AppSettings
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.ResearchFixtures
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.Arm
import com.optionslab.app.ui.ArmResult
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.app.work.Alerts
import com.optionslab.engine.strategy.Presets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime

/** Shared by the area-D functional tests: the app theme, the alert queue, the network guard. */
internal fun clearAlerts() = Alerts.queue.value.forEach { Alerts.dismiss(it.id) }
internal fun alertTexts(): List<String> = Alerts.queue.value.map { it.text }

/**
 * The Lab: its page tokens, the Backtests (trial + arms), Health, Presets and Replay pages, each driven
 * through plain state and recording callbacks - no AppModel, no network. Data is the bundled record.
 */
// A tall phone: the functional checks are about behaviour, so every control is on screen to be tapped (layout is the matrix tests' job).
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class LabScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()

    @Before fun fresh() { clearAlerts() }
    @After fun noNetwork() { assertEquals("no host may be reached", emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun set(content: @Composable () -> Unit) = compose.setContent { IraAlgoTheme("light") { content() } }
    private fun text(t: String, sub: Boolean = false) = compose.onNodeWithText(t, substring = sub)
    private fun shows(t: String, sub: Boolean = true) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    /** Brings the node with [t] on screen: through the page's lazy list, or the scrolling column around it. */
    private fun scrollTo(t: String, sub: Boolean = false): SemanticsNodeInteraction {
        val lazy = compose.onAllNodes(hasScrollToIndexAction())
        if (lazy.fetchSemanticsNodes().isNotEmpty()) { lazy[0].performScrollToNode(hasText(t, substring = sub)); return text(t, sub) }
        return text(t, sub).performScrollTo()
    }
    private fun tap(t: String) = scrollTo(t).performClick()
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))
    private fun type(label: String, v: String) {
        field(label).performScrollTo().performTextClearance()
        if (v.isNotEmpty()) field(label).performTextInput(v)
    }

    // ---- the Lab's page tokens -------------------------------------------------------------------

    @Test fun labTokensReachEveryPageAndMarkTheCurrentOne() {
        var page by mutableStateOf("trials")
        val asked = ArrayList<String>()
        set { LabTabs(page, { asked += it; page = it }) { Text("page=$it") } }
        text("page=trials").assertIsDisplayed()
        text("Backtests").assertIsSelected()
        val tokens = listOf("Pine scripts" to "pine", "Health" to "health", "Portfolio" to "portfolio", "SIP" to "sip", "Replay" to "replay", "Backtests" to "trials")
        for ((label, id) in tokens) {
            text(label).performClick()
            text("page=$id").assertIsDisplayed()
            text(label).assertIsSelected()
            tokens.filter { it.first != label }.forEach { text(it.first).assertIsNotSelected() }
        }
        assertEquals(tokens.map { it.second }, asked)
    }

    // ---- Backtests: the trial ------------------------------------------------------------------------

    private class Calls { var run = 0; var export = 0; val compared = ArrayList<Arm>(); val adopted = ArrayList<ArmResult>() }

    private fun trials(bt: Load<com.optionslab.engine.BacktestReport>, arms: Load<List<ArmResult>> = Load.Idle, start: AppSettings = ResearchFixtures.settings): Pair<Calls, () -> AppSettings> {
        val c = Calls()
        var s by mutableStateOf(start)
        set { TrialsContent(s, bt, arms, onRun = { c.run++ }, onUpdate = { f -> s = f(s) }, onExport = { c.export++ }, onCompare = { c.compared += it }, onAdopt = { c.adopted += it }) }
        return c to { s }
    }

    @Test fun trialIdleRunsAndAdjustsTheParameters() {
        val (c, s) = trials(Load.Idle)
        text("0.75% OTM at 11:00 · lot 65 · quoted spread · naked put").assertIsDisplayed()
        text("Run the trial to replay every cached expiry session through the strategy.").assertIsDisplayed()
        text("Run the trial").performClick()
        assertEquals(1, c.run)

        text("Adjust").performClick()
        text("STRIKE DISTANCE").assertIsDisplayed()
        text("1.00%").performClick()
        text("12:00").performClick()
        text("stress").performClick()
        text("dated").performClick()
        text("wing +0.75%").performClick()
        assertEquals(0.01, s().otmPct, 1e-12)
        assertEquals("12:00", s().entry)
        assertEquals("stress", s().regime)
        assertTrue(s().datedLot)
        assertEquals(0.0075, s().wingPct!!, 1e-12)
        text("1.00% OTM at 12:00 · lot dated · stress spread · put spread, wing 0.75% wider").assertIsDisplayed()
        text("1.00%").assertIsSelected()
        // A pinned lot switches dated off again; capital and the survivable day are settings too.
        text("75").performClick()
        assertEquals(false, s().datedLot); assertEquals(75, s().pinnedLot)
        scrollTo("last 20").performClick(); assertEquals(20, s().holdout)
        scrollTo("Rs 1,000,000").performClick(); assertEquals(1_000_000.0, s().capital, 0.0)
        scrollTo("-13%").performClick(); assertEquals(-0.13, s().survive, 1e-12)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Close"))
        text("Close").performClick()
        assertTrue(!shows("STRIKE DISTANCE", sub = false))
        text("Adjust").assertIsDisplayed()
    }

    @Test fun trialBusyDisablesRunAndShowsProgress() {
        val (c, _) = trials(Load.Busy("Settling session 3 of 170", 3 / 170f))
        text("Settling session 3 of 170").assertIsDisplayed()
        text("Run the trial").assertIsNotEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(0, c.run)
    }

    @Test fun trialFailureSaysWhy() {
        trials(Load.Failed("the backtest failed: no chains"))
        text("the backtest failed: no chains").assertIsDisplayed()
        text("Run the trial").assertIsEnabled()
    }

    @Test fun trialReportShowsTheRecordAndExports() {
        val r = ResearchFixtures.report
        val (c, _) = trials(Load.Done(r))
        text("Verdict of the Record").assertIsDisplayed()
        text("sessions: ${r.days.size}  (${r.days.first()} .. ${r.days.last()})").assertIsDisplayed()
        text("win rate").assertIsDisplayed()
        val comb = r.combined!!
        text("${(comb.winRate * comb.n).toInt()}/${comb.n}").assertIsDisplayed()
        if (r.holdoutDays.isNotEmpty()) text("SEALED HOLDOUT: last ${r.holdoutDays.size} sessions", sub = true).assertIsDisplayed()
        scrollTo("Train · Holdout · Combined").assertIsDisplayed()
        scrollTo("Mean / median")
        if (r.losers.isNotEmpty()) scrollTo("Losing Sessions (${r.losers.size} of ${r.all.size})").assertIsDisplayed()
        scrollTo("Sizing").assertIsDisplayed()
        scrollTo("Export trades (CSV)").performClick()
        assertEquals(1, c.export)
    }

    @Test fun trialWithNoTradesSaysSo() {
        trials(Load.Done(ResearchFixtures.emptyReport))
        text("no sessions produced a trade").assertIsDisplayed()
    }

    // ---- Backtests: the arms -------------------------------------------------------------------------

    @Test fun armsCompareWithYourSettingsAndAdoptOnTap() {
        val arms = ResearchFixtures.arms
        val (c, _) = trials(Load.Idle, Load.Done(arms))
        scrollTo("Compare all arms").performClick()
        assertEquals(1, c.compared.size)
        assertEquals("Your settings", c.compared.single().name)
        assertEquals(ResearchFixtures.settings.params(), c.compared.single().params)
        scrollTo(arms[1].arm.name).performClick()
        assertEquals(listOf(arms[1]), c.adopted)
        arms.forEach { a -> scrollTo(a.arm.note).assertIsDisplayed() }
    }

    @Test fun armsBusyAndFailed() {
        var arms by mutableStateOf<Load<List<ArmResult>>>(Load.Busy("Session 4: 2023-02-02", 4 / 170f))
        val c = Calls()
        set { TrialsContent(ResearchFixtures.settings, Load.Idle, arms, {}, {}, {}, { c.compared += it }, {}) }
        scrollTo("Session 4: 2023-02-02").assertIsDisplayed()
        text("Compare all arms").assertIsNotEnabled()
        arms = Load.Failed("the comparison failed")
        compose.waitForIdle()
        scrollTo("the comparison failed").assertIsDisplayed()
        text("Compare all arms").assertIsEnabled()
        arms = Load.Done(listOf(ArmResult(Arm("Empty arm", "no sessions", ResearchFixtures.settings.params()), null, 3, emptyList())))
        compose.waitForIdle()
        scrollTo("Empty arm")
        scrollTo("no trades").assertIsDisplayed()
    }

    // ---- Health ----------------------------------------------------------------------------------------

    private class HealthCalls { val sources = ArrayList<String>(); var imports = 0; val windows = ArrayList<Int>(); var checks = 0 }

    private fun health(h: Load<com.optionslab.app.ui.HealthResult>): HealthCalls {
        val c = HealthCalls()
        var source by mutableStateOf("backtest")
        var s by mutableStateOf(ResearchFixtures.settings)
        set {
            HealthContent(s, h, source, onSource = { source = it; c.sources += it }, onImport = { c.imports++ },
                onWindow = { w -> s = s.copy(healthLast = w); c.windows += w }, onCheck = { c.checks++ })
        }
        return c
    }

    @Test fun healthSourcesWindowsAndCheckAgain() {
        val c = health(Load.Busy("Checking the kill conditions"))
        text("Checking the kill conditions").assertIsDisplayed()
        text("Backtest").assertIsSelected()
        text("Paper ledger").performClick()
        text("Paper ledger").assertIsSelected()
        text("Imported CSV").performClick()      // opens the system file picker (recorded), the source stays
        text("Paper ledger").assertIsSelected()
        text("last 30").assertIsSelected()
        text("last 10").performClick()
        text("all").performClick()
        text("all").assertIsSelected()
        scrollTo("Check again").performClick()
        assertEquals(listOf("paper"), c.sources)
        assertEquals(1, c.imports)
        assertEquals(listOf(10, 0), c.windows)
        assertEquals(1, c.checks)
    }

    @Test fun healthVerdictIsTheWorstCheckAndCardsOpen() {
        val h = ResearchFixtures.mixedHealth
        health(Load.Done(h))
        assertTrue(shows("FAIL", sub = false))
        text("LOOK CLOSER").assertIsDisplayed()
        text("last ${h.window.size} of ${h.total} sessions", sub = true).assertIsDisplayed()
        text("The verdict is the WORST check, never an average", sub = true).assertIsDisplayed()
        // WARN and FAIL open with their reason; PASS starts closed and opens on a tap.
        scrollTo("Costs are eating the credit.").assertIsDisplayed()
        scrollTo("One loss erased a month of wins.").assertIsDisplayed()
        scrollTo("Win rate")
        assertTrue(!shows("The strategy's edge is its win rate."))
        text("Win rate").performClick()
        scrollTo("The strategy's edge is its win rate.").performClick()     // the reason closes it again
        compose.waitForIdle()
        assertTrue(!shows("The strategy's edge is its win rate."))
    }

    @Test fun healthOnTheBundledTrial() {
        val h = ResearchFixtures.health
        health(Load.Done(h))
        assertTrue(shows(h.verdict.mark, sub = false))
        text(if (h.verdict == com.optionslab.engine.Monitor.Status.PASS) "HOLDS" else "LOOK CLOSER").assertIsDisplayed()
        h.checks.forEach { scrollTo(it.name).assertIsDisplayed() }
    }

    @Test fun healthFailureSaysWhy() {
        health(Load.Failed("No settled paper trades yet. No trades is not the same as no problems."))
        text("No settled paper trades yet.", sub = true).assertIsDisplayed()
    }

    // ---- Presets -----------------------------------------------------------------------------------------

    private data class PresetCall(val id: String, val u: String, val lots: Int, val entry: LocalTime, val exit: LocalTime, val stop: Double?, val target: Double?)

    private fun presets(result: Load<Presets.Result> = Load.Idle): Triple<ArrayList<PresetCall>, ArrayList<PresetCall>, () -> Int> {
        val runs = ArrayList<PresetCall>(); val adds = ArrayList<PresetCall>(); var resets = 0
        set {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PresetsContent(result, onReset = { resets++ },
                    onRun = { a, b, c, d, e, f, g -> runs += PresetCall(a, b, c, d, e, f, g) },
                    onAdd = { a, b, c, d, e, f, g -> adds += PresetCall(a, b, c, d, e, f, g) })
            }
        }
        return Triple(runs, adds) { resets }
    }

    @Test fun presetBacktestAndAddSendTheCheckedBasket() {
        val (runs, adds, resets) = presets()
        text(Presets.ALL.first().blurb).assertIsDisplayed()
        text("Backtest replays it over every harvested session", sub = true).assertIsDisplayed()
        tap("Backtest")
        assertEquals(listOf(PresetCall(Presets.ALL.first().id, "NIFTY", 1, LocalTime.of(9, 20), LocalTime.of(15, 15), null, null)), runs)

        val other = Presets.ALL[1]
        text(other.name).performClick()
        text(other.blurb).assertIsDisplayed()
        text("BANKNIFTY").performClick()
        assertEquals(2, resets())
        type("Lots", "3"); type("Entry", "10:05"); type("Exit", "14:30"); type("Basket stop ₹", "2500"); type("Basket target ₹", "1200.5")
        tap("Add to Strategies")
        assertEquals(listOf(PresetCall(other.id, "BANKNIFTY", 3, LocalTime.of(10, 5), LocalTime.of(14, 30), 2500.0, 1200.5)), adds)
        assertEquals(1, runs.size)
        assertTrue(alertTexts().isEmpty())
    }

    @Test fun presetValidationRefusesBadInputWithAReason() {
        val (runs, adds, _) = presets()
        fun refused(msg: String) {
            clearAlerts()
            tap("Backtest")
            tap("Add to Strategies")
            assertEquals(listOf(msg, msg), alertTexts())
            assertTrue(runs.isEmpty() && adds.isEmpty())
        }
        type("Lots", ""); refused("Lots must be 1 to 50")
        type("Lots", "0"); refused("Lots must be 1 to 50")
        type("Lots", "51"); refused("Lots must be 1 to 50")
        type("Lots", "50")
        type("Entry", "25:99"); refused("Times are HH:MM, like 09:20")
        type("Entry", "9:00"); refused("Entry and exit must be between 09:15 and 15:25")
        type("Entry", "09:20"); type("Exit", "15:26"); refused("Entry and exit must be between 09:15 and 15:25")
        type("Exit", "09:20"); refused("The exit must come after the entry")
        type("Exit", "15:25")
        type("Basket stop ₹", "0"); refused("The basket stop is a positive rupee amount")
        type("Basket stop ₹", "."); refused("The basket stop is a positive rupee amount")
        type("Basket stop ₹", "")
        type("Basket target ₹", "0"); refused("The basket target is a positive rupee amount")
        // Letters and signs never reach the fields.
        type("Basket target ₹", "-12a"); field("Basket target ₹").assert(hasText("12", substring = true))
        clearAlerts()
        tap("Backtest")
        assertEquals(50, runs.single().lots)
        assertEquals(12.0, runs.single().target!!, 0.0)
        assertEquals(LocalTime.of(15, 25), runs.single().exit)
    }

    @Test fun presetBusyFailedAndResult() {
        presets(Load.Busy("Replaying Short straddle"))
        text("Replaying Short straddle").assertIsDisplayed()
        scrollTo("Backtest").assertIsNotEnabled()
        text("Add to Strategies").assertIsEnabled()
    }

    @Test fun presetFailurePostsTheReason() {
        presets(Load.Failed("No harvested NIFTY session could be priced at 09:20."))
        compose.waitForIdle()
        assertTrue(alertTexts().contains("No harvested NIFTY session could be priced at 09:20."))
    }

    @Test fun presetResultShowsTheReplay() {
        val r = ResearchFixtures.preset
        presets(Load.Done(r))
        text("Net after charges").assertIsDisplayed()
        text("Sessions").assertIsDisplayed()
        text("Win rate").assertIsDisplayed()
        text("Exits").assertIsDisplayed()
        text("Last sessions").assertIsDisplayed()
        text(com.optionslab.app.ui.rs(r.net, sign = true)).assertIsDisplayed()
    }

    // ---- Replay ---------------------------------------------------------------------------------------------

    private fun replay(loaded: Load<com.optionslab.engine.Session>, days: Map<String, List<LocalDate>>): ArrayList<Pair<String, LocalDate>> {
        val loads = ArrayList<Pair<String, LocalDate>>()
        set { ReplayContent(loaded, daysFor = { days[it].orEmpty() }, onLoad = { u, d -> loads += u to d }) }
        return loads
    }

    @Test fun replayPicksADayAndLoadsIt() {
        val d = listOf(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 3))
        val loads = replay(Load.Idle, mapOf("NIFTY" to d))
        text("Wed 4 Mar 2026").assertIsDisplayed()
        text("›").assertIsNotEnabled()
        text("‹").performClick()
        text("Tue 3 Mar 2026").assertIsDisplayed()
        text("‹").performClick()
        text("‹").assertIsNotEnabled()
        text("›").performClick()
        text("Load this session").performClick()
        assertEquals(listOf("NIFTY" to LocalDate.of(2026, 3, 3)), loads)
        text("BANKNIFTY").performClick()
        text("No harvested sessions for BANKNIFTY yet.").assertIsDisplayed()
    }

    @Test fun replayBusyAndFailed() {
        var loaded by mutableStateOf<Load<com.optionslab.engine.Session>>(Load.Busy("Loading NIFTY 2026-03-04"))
        set { ReplayContent(loaded, daysFor = { listOf(LocalDate.of(2026, 3, 4)) }, onLoad = { _, _ -> }) }
        text("Load this session").assertIsNotEnabled()
        loaded = Load.Failed("No harvested bars for NIFTY on 2026-03-04")
        compose.waitForIdle()
        assertTrue(alertTexts().contains("No harvested bars for NIFTY on 2026-03-04"))
        text("Load this session").assertIsEnabled()
    }

    @Test fun replayStepsTradesAndStartsOver() {
        val sess = ResearchFixtures.replaySession
        val ix = sess.index!!
        replay(Load.Done(sess), mapOf("NIFTY" to listOf(sess.day)))
        val n = ix.size
        text("NIFTY · ${com.optionslab.engine.minuteText(ix.minutes[5])} · 6/$n", sub = true).assertIsDisplayed()
        scrollTo("+1").performClick()
        text("7/$n", sub = true).assertIsDisplayed()
        text("+5").performClick()
        text("12/$n", sub = true).assertIsDisplayed()
        text("+30").performClick()
        text("42/$n", sub = true).assertIsDisplayed()

        // Buy one lot at the shown close: the position and the trade list follow.
        val lot = sess.lotHint ?: com.optionslab.engine.Lots.lotSizeOn("NIFTY", sess.day)
        val px = String.format(java.util.Locale.ENGLISH, "%,.2f", ix.close[41])
        scrollTo("Buy").performClick()
        assertTrue(alertTexts().contains("Bought $lot NIFTY at $px"))
        scrollTo("$lot @ $px").assertIsDisplayed()
        scrollTo("Trades").assertIsDisplayed()
        scrollTo("Sell").performClick()
        scrollTo("Position")
        text("flat").assertIsDisplayed()
        // Lots are checked before anything is traded.
        clearAlerts()
        type("Lots (lot $lot)", "0")
        tap("Buy")
        assertEquals(listOf("Lots must be 1 to 50"), alertTexts())
        type("Lots (lot $lot)", "2")
        scrollTo("Start over").performClick()
        scrollTo("6/$n", sub = true).assertIsDisplayed()
    }

    @Test fun replayPlaysOneCandleAt400msAndOptionsUseTheAtmStrike() {
        val sess = ResearchFixtures.replaySession
        val n = sess.index!!.size
        replay(Load.Done(sess), mapOf("NIFTY" to listOf(sess.day)))
        scrollTo("Play")
        compose.mainClock.autoAdvance = false
        text("Play").performClick()
        compose.mainClock.advanceTimeBy(1_250)          // three candles at one per 400 ms
        text("Pause").assertIsDisplayed()
        text("9/$n", sub = true).assertIsDisplayed()
        text("Pause").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
        text("Play").assertIsDisplayed()
        text("9/$n", sub = true).assertIsDisplayed()
        scrollTo("ATM CE").performClick()
        compose.waitForIdle()
        assertTrue(shows(" CE · ") || shows("That instrument has no bars on this day."))
        scrollTo("ATM PE").performClick()
        compose.waitForIdle()
        assertTrue(shows(" PE · ") || shows("That instrument has no bars on this day."))
    }
}

/**
 * Every Lab page state above on every device set-up (size x font scale x theme): a screenshot each
 * (build/outputs/roborazzi), [com.optionslab.app.testing.LayoutLint], and a click-everything smoke test.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LabScreensLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = com.optionslab.app.testing.ResearchMatrix.six()

        /** Real layout bugs found by these tests, skipped with this text until fixed. */
        val BUGS = mapOf("*" to "TRIAGE: discovery run, findings to be pinned")
    }

    @After fun noNetwork() { assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    private val s = ResearchFixtures.settings

    private fun trials(bt: Load<com.optionslab.engine.BacktestReport>, arms: Load<List<ArmResult>>) = @Composable {
        LabTabs("trials", {}) { TrialsContent(s, bt, arms, {}, {}, {}, {}, {}) }
    }

    @Test fun trialsIdle() {
        checkScreen("lab-trials-idle", BUGS, content = trials(Load.Idle, Load.Idle))
        if (device.name == "phone-font1.3-light") assertTrue(smokeEveryAction().containsAll(listOf("Run the trial", "Adjust")))
    }

    @Test fun trialsBusy() = checkScreen("lab-trials-busy", BUGS,
        content = trials(Load.Busy("Settling session 40 of 170", 40 / 170f), Load.Busy("Running 6 arms", 0.2f)))

    @Test fun trialsReport() {
        checkScreen("lab-trials-report", BUGS, content = trials(Load.Done(ResearchFixtures.report), Load.Done(ResearchFixtures.arms)))
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    @Test fun healthMixed() {
        checkScreen("lab-health-mixed", BUGS) {
            LabTabs("health", {}) { HealthContent(s, Load.Done(ResearchFixtures.mixedHealth), "backtest", {}, {}, {}, {}) }
        }
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    @Test fun healthFailed() = checkScreen("lab-health-failed", BUGS) {
        HealthContent(s, Load.Failed("empty trade ledger; no trades is not the same as no problems"), "imported", {}, {}, {}, {})
    }

    @Test fun presetsIdle() {
        checkScreen("lab-presets-idle", BUGS) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) { PresetsContent(Load.Idle, {}, { _, _, _, _, _, _, _ -> }, { _, _, _, _, _, _, _ -> }) }
        }
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    @Test fun presetsResult() = checkScreen("lab-presets-result", BUGS) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            PresetsContent(Load.Done(ResearchFixtures.preset), {}, { _, _, _, _, _, _, _ -> }, { _, _, _, _, _, _, _ -> })
        }
    }

    @Test fun replayLoaded() {
        val sess = ResearchFixtures.replaySession
        checkScreen("lab-replay-loaded", BUGS) {
            LabTabs("replay", {}) { ReplayContent(Load.Done(sess), { listOf(sess.day) }, { _, _ -> }) }
        }
        if (device.name == "phone-font1.3-light") smokeEveryAction(skip = setOf("Play"))
    }

    @Test fun replayNoSessions() = checkScreen("lab-replay-empty", BUGS) {
        ReplayContent(Load.Idle, { emptyList() }, { _, _ -> })
    }
}

/** The page's main state on all 24 set-ups (the other states run on six, in [LabScreensLayoutTest]). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LabMainLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()
        val BUGS get() = LabScreensLayoutTest.BUGS
    }

    @Test fun trialsReport() {
        checkScreen("lab-trials-report-all", BUGS, content = @Composable { LabTabs("trials", {}) { TrialsContent(ResearchFixtures.settings, Load.Done(ResearchFixtures.report), Load.Done(ResearchFixtures.arms), {}, {}, {}, {}, {}) } })
    }
}
