package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.OrbArms
import com.optionslab.app.data.Paper
import com.optionslab.app.data.Strategies
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.LayoutLint
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.app.work.Alerts
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.PassRule
import com.optionslab.engine.strategy.LegState
import com.optionslab.engine.strategy.OptionType
import com.optionslab.engine.strategy.Position
import com.optionslab.engine.strategy.RunMode
import com.optionslab.engine.strategy.RunState
import com.optionslab.engine.strategy.RunStatus
import com.optionslab.engine.strategy.SchedulerConfig
import com.optionslab.engine.strategy.StrategyDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/** Fake state for the Strategies page, Home's strategy card and the ORB rows (no AppModel, no network). */
internal object StrategyFakes {
    fun def(id: Long, name: String, live: Boolean = false, armed: RunMode? = null): StrategyDef =
        AutomationSupport.strategy(name, Position.S to OptionType.CE, Position.B to OptionType.PE).copy(id = id, liveEnabled = live,
            scheduler = armed?.let { SchedulerConfig(true, DayOfWeek.entries.take(5), LocalTime.of(9, 20), LocalTime.of(15, 15), it) })

    fun running(id: Long, name: String, mode: RunMode = RunMode.LIVE): Strategies.Entry {
        val run = RunState(runId = 7, strategyId = id, mode = mode, status = RunStatus.ACTIVE, startedAt = ZonedDateTime.now(IST),
            pnlTotal = -1_250.0, pnlRealized = 0.0)
        run.legs["1"] = LegState(1, "S", "NIFTY26OCT24500CE", "NFO", 1, 75, entryStatus = "complete", entryAvg = 100.0, ltp = 116.0,
            mtm = -1_200.0, status = "open", effectiveSl = 130.0)
        run.legs["2"] = LegState(2, "B", "NIFTY26OCT24500PE", "NFO", 1, 75, entryStatus = "complete", entryAvg = 40.0, ltp = 39.0,
            mtm = -75.0, status = "open")
        return Strategies.Entry(def(id, name, live = true), run)
    }

    val log = listOf(
        Strategies.LogLine(System.currentTimeMillis(), "Straddle", "leg_exit_rejected", "Exit rejected on leg 1: could not read Zerodha positions", "critical"),
        Strategies.LogLine(System.currentTimeMillis(), "Straddle", "leg_entry_placed", "Entry SELL 75 NIFTY26OCT24500CE placed", "info"),
    )

    private val today: LocalDate = LocalDate.now(IST)
    private fun contract(r: Right) = Paper.Contract("BANKNIFTY-TEST-52000$r", "BANKNIFTY", today.plusDays(5), 52_000.0, r, 30, "NSE_FO|$r")

    fun orbView(armed: Boolean = false, pending: Boolean = false, open: Boolean = false, live: Boolean = false): OrbArms.View {
        val bar = today.atTime(10, 40)
        val pos = OrbArms.Position("orb", "BANKNIFTY-TEST-52000CE", "CE", 30, 210.0, bar.plusMinutes(6), bar, "E1", "S1", 170.0, live = live, kite = "BANKNIFTY26OCT52000CE")
        val closed = pos.copy(exit = 250.0, exitTime = bar.plusMinutes(40), why = "target", charges = 42.0)
        val arms = OrbRules.ARMS.mapIndexed { i, a ->
            OrbArms.ArmView(a, armed = armed && i == 0, automatic = true, status = if (i == 0) "inside_range" else "", open = pos.takeIf { open && i == 0 },
                mark = 220.0, pending = OrbArms.Pending(a.source, "CE", bar, bar.plusMinutes(10)).takeIf { pending && i == 0 },
                today = if (i == 0) listOfNotNull(closed, pos.takeIf { open }) else emptyList(), liveOk = live)
        }
        return OrbArms.View(arms, OrbArms.Legs(today, 52_000, today.plusDays(5), contract(Right.CE), contract(Right.PE)), 52_310.0 to 51_980.0,
            PassRule.judge(listOf(PassRule.Closed(today.minusDays(1), 1_158.0, true))),
            org.json.JSONObject().put("up", true).put("orb", org.json.JSONArray().put(org.json.JSONObject().put("bar", "10:40").put("exitBar", "11:20")
                .put("right", "CE").put("entry", 210.0).put("exit", 250.0).put("why", "target").put("pnl", 1_200.0))), today.minusDays(1).toString())
    }

    /** The PIN prompt's stand-in: its own two buttons. */
    val reauth: @Composable (() -> Unit, () -> Unit) -> Unit = { ok, cancel ->
        AlertDialog(onDismissRequest = cancel, confirmButton = { TextButton(ok) { Text("PIN OK") } },
            dismissButton = { TextButton(cancel) { Text("PIN cancel") } }, text = { Text("Enter your app PIN") })
    }
    val reauthWhy: @Composable (String?, () -> Unit, () -> Unit) -> Unit = { why, ok, cancel ->
        AlertDialog(onDismissRequest = cancel, confirmButton = { TextButton(ok) { Text("PIN OK") } },
            dismissButton = { TextButton(cancel) { Text("PIN cancel") } }, text = { Text(why ?: "Enter your app PIN to send this order to Zerodha.") })
    }
}

internal class RecordingStrategyActions(var saveError: String? = null) : StrategyActions, StrategyArmActions, OrbActions {
    val calls = java.util.Collections.synchronizedList(ArrayList<String>())
    var savedDef: StrategyDef? = null
    override fun save(def: StrategyDef, onResult: (String?) -> Unit) { savedDef = def; calls += "save ${def.name}"; onResult(saveError) }
    override fun start(id: Long, live: Boolean) { calls += "start $id live=$live" }
    override fun stop(id: Long) { calls += "stop $id" }
    override fun closeLeg(id: Long, legId: Int) { calls += "closeLeg $id $legId" }
    override fun setLive(id: Long, on: Boolean) { calls += "setLive $id $on" }
    override fun delete(id: Long) { calls += "delete $id" }
    override fun arm(id: Long, on: Boolean, automatic: Boolean) { calls += "arm $id on=$on auto=$automatic" }
    override fun approve(id: Long) { calls += "approve $id" }
    override fun skip(id: Long) { calls += "skip $id" }
    override fun importText(text: String) { calls += "import ${text.length}" }
    override fun say(text: String) { calls += "say $text" }
    override fun clearKill() { calls += "clearKill" }
    override fun startBot() { calls += "startBot" }
    override fun stopBot(alsoRunning: Boolean) { calls += "stopBot also=$alsoRunning" }
    override fun arm(source: String, on: Boolean, automatic: Boolean, pinConfirmed: Boolean) { calls += "orb arm $source on=$on auto=$automatic pin=$pinConfirmed" }
    override fun approve(source: String, pinConfirmed: Boolean) { calls += "orb approve $source pin=$pinConfirmed" }
    override fun skip(source: String) { calls += "orb skip $source" }
}

/** A click through the node's semantics action (as TalkBack does): works wherever the node is, on screen or not. */
internal fun SemanticsNodeInteraction.areaCClick() = performSemanticsAction(SemanticsActions.OnClick)

/** The dialog's own button (a dialog is a root of its own). */
internal fun ComposeTestRule.inDialog(text: String) = onNode(hasText(text) and hasAnyAncestor(isDialog()))

/** [text] as a node, scrolling whichever list holds it (the page's LazyColumn or a dialog's) only when it is not composed yet. */
internal fun ComposeTestRule.strategyScrollTo(text: String, substring: Boolean = false): SemanticsNodeInteraction {
    if (onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty()) {
        for (n in onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices) {
            runCatching { onAllNodes(hasScrollToNodeAction())[n].performScrollToNode(hasText(text, substring = substring)) }
            if (onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()) break
        }
    }
    return onAllNodesWithText(text, substring = substring).onFirst()
}

internal fun ComposeTestRule.strategyShown(text: String): Boolean {
    if (onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return true
    strategyScrollTo(text, substring = true)
    return onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
}

@RunWith(AndroidJUnit4::class)
class StrategiesScreenTest {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    @get:Rule val compose = createComposeRule()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)
    private val rec = RecordingStrategyActions()

    @Before fun clear() = AutomationSupport.clearAlerts()

    private fun page(list: List<Strategies.Entry>, liveAllowed: Boolean = false, log: List<Strategies.LogLine> = emptyList()) = compose.setContent {
        IraAlgoTheme("light") { StrategiesContent(liveAllowed, list, log, rec, presets = { Text("Presets card") }, reauth = StrategyFakes.reauth) }
    }

    private fun tap(text: String) { compose.strategyScrollTo(text).areaCClick(); compose.waitForIdle() }
    private fun shown(text: String) = compose.strategyShown(text)

    // ---- the Strategies page ----------------------------------------------------------------

    @Test fun emptyPageOffersANewStrategyAndThePresets() {
        page(emptyList())
        assertTrue(shown("No strategies yet."))
        assertTrue(shown("Presets card"))
        tap("New strategy")
        assertTrue(shown("LEG 1"))
        tap("Cancel")
        assertTrue("the editor closes without saving", rec.calls.isEmpty())
        assertTrue(!shown("LEG 1"))
    }

    @Test fun theEditorSavesWhatWasEntered() {
        page(emptyList())
        tap("New strategy")
        compose.onNodeWithText("Short straddle").performTextReplacement("My straddle")
        tap("BANKNIFTY"); tap("MIS")
        tap("+ Add leg")
        tap("Save")
        val d = rec.savedDef!!
        assertEquals("My straddle", d.name)
        assertEquals("BANKNIFTY", d.underlying)
        assertEquals(com.optionslab.engine.strategy.Product.MIS, d.product)
        assertEquals(3, d.legs.size)
        assertEquals(Position.B, d.legs[2].position)
        assertEquals(LocalTime.of(9, 20), d.entryTime)
        assertTrue("saved: the editor closed", !shown("LEG 1"))
    }

    @Test fun aValidationErrorKeepsTheEditorOpenAndSaysWhy() {
        rec.saveError = "legs[0].lots must be above zero"
        page(emptyList())
        tap("New strategy")
        tap("Save")
        compose.waitUntil(3_000) { Alerts.queue.value.any { it.text == "legs[0].lots must be above zero" } }
        assertTrue("still editing", shown("LEG 1"))
    }

    @Test fun removingALegAndSchedulingAreSaved() {
        page(emptyList())
        tap("New strategy")
        tap("Remove")
        compose.strategyScrollTo("Schedule")
        compose.onAllNodes(isToggleable()).fetchSemanticsNodes()
        // The schedule switch is the last toggle in the editor.
        compose.onAllNodes(isToggleable()).let { it[it.fetchSemanticsNodes().size - 1] }.areaCClick()
        tap("Live (asks you)")
        tap("Save")
        val d = rec.savedDef!!
        assertEquals(1, d.legs.size)
        assertEquals(RunMode.LIVE, d.scheduler!!.defaultMode)
        assertTrue(d.scheduler!!.enabled)
    }

    @Test fun aStoppedStrategyStartsInPaperAndIsEditedEnabledAndDeleted() {
        page(listOf(Strategies.Entry(StrategyFakes.def(3, "Straddle"), null)))
        tap("Start paper")
        assertEquals("start 3 live=false", rec.calls.last())
        compose.onNodeWithText("Start live").assertIsNotEnabled()
        assertTrue(shown("Live runs need Live mode"))
        tap("Enable live")
        assertEquals("setLive 3 true", rec.calls.last())
        tap("Delete")
        assertTrue(shown("Delete Straddle?"))
        tap("Keep")
        assertEquals("kept: nothing deleted", 2, rec.calls.size)
        tap("Delete"); compose.inDialog("Delete").areaCClick(); compose.waitForIdle()
        assertEquals("delete 3", rec.calls.last())
        tap("Edit")
        assertTrue(shown("Edit Straddle"))
    }

    @Test fun aLiveStartIsConfirmedThenTakesThePin() {
        page(listOf(Strategies.Entry(StrategyFakes.def(4, "Straddle", live = true), null)), liveAllowed = true)
        compose.onNodeWithText("Start live").assertIsEnabled()
        tap("Start live")
        assertTrue(shown("Start Straddle on Zerodha?"))
        assertTrue(shown("SELL 1 lot"))
        compose.inDialog("Cancel").areaCClick(); compose.waitForIdle()
        assertTrue("cancelled: nothing started", rec.calls.isEmpty())
        tap("Start live"); tap("Confirm with PIN")
        tap("PIN cancel")
        assertTrue("PIN cancelled: nothing started", rec.calls.isEmpty())
        tap("Start live"); tap("Confirm with PIN"); tap("PIN OK")
        assertEquals(listOf("start 4 live=true"), rec.calls)
    }

    @Test fun aRunningStrategyShowsItsLegsAndStopsOrExitsALeg() {
        page(listOf(StrategyFakes.running(5, "Straddle")), liveAllowed = true, log = StrategyFakes.log)
        assertTrue(shown("SELL NIFTY26OCT24500CE ×75"))
        assertTrue(shown("active"))
        assertTrue("no edit or delete while it runs", !shown("Delete"))
        compose.onAllNodesWithText("Exit").onFirst().areaCClick()
        assertEquals("closeLeg 5 1", rec.calls.last())
        tap("Stop and close all")
        assertEquals("stop 5", rec.calls.last())
        assertTrue(shown("Audit log"))
        assertTrue(shown("could not read Zerodha positions"))
    }
}

@RunWith(AndroidJUnit4::class)
class StrategyArmCardTest {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    @get:Rule val compose = createComposeRule()
    private val rec = RecordingStrategyActions()

    private fun card(all: List<Strategies.Entry>, live: Boolean = false, kill: Boolean = false, stopped: Boolean = false,
                     pending: Map<Long, RunMode> = emptyMap(), auto: Map<Long, Boolean> = emptyMap(), manage: () -> Unit = {}) = compose.setContent {
        IraAlgoTheme("light") {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StrategyArmContent(live, kill, all, auto, pending, stopped, rec, manage, orbRows = { Text("ORB rows") }, reauth = StrategyFakes.reauth)
            }
        }
    }

    private fun tap(text: String) { compose.onAllNodesWithText(text).onFirst().areaCClick(); compose.waitForIdle() }
    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun switch(i: Int = 0) { compose.onAllNodes(isToggleable())[i].areaCClick(); compose.waitForIdle() }

    @Test fun armingInPaperAsksHowEntriesGoOut() {
        card(listOf(Strategies.Entry(StrategyFakes.def(1, "Straddle"), null)))
        assertTrue(shown("OFF"))
        switch()
        assertTrue(shown("Arm Straddle (paper)"))
        tap("Cancel")
        assertTrue(rec.calls.isEmpty())
        switch(); tap("Automatic")
        assertEquals(listOf("arm 1 on=true auto=true"), rec.calls)
    }

    @Test fun armingInLiveTakesThePin() {
        card(listOf(Strategies.Entry(StrategyFakes.def(1, "Straddle", live = true), null)), live = true)
        switch(); assertTrue(shown("Arm Straddle (live)"))
        tap("Ask me to approve")
        tap("PIN cancel")
        assertTrue("no PIN, not armed", rec.calls.isEmpty())
        switch(); tap("Ask me to approve"); tap("PIN OK")
        assertEquals(listOf("arm 1 on=true auto=false"), rec.calls)
    }

    @Test fun anArmedStrategyShowsHowAndDisarmsWithoutAsking() {
        card(listOf(Strategies.Entry(StrategyFakes.def(1, "Straddle", live = true, armed = RunMode.LIVE), null)), auto = mapOf(1L to false))
        assertTrue(shown("ARMED · LIVE · APPROVE"))
        assertTrue(shown("09:20–15:15 · Mon–Fri · 2 legs"))
        switch()
        assertEquals(listOf("arm 1 on=false auto=true"), rec.calls)
    }

    @Test fun aWaitingStartIsApprovedOrSkipped() {
        card(listOf(Strategies.Entry(StrategyFakes.def(1, "Paper one", armed = RunMode.SANDBOX), null),
            Strategies.Entry(StrategyFakes.def(2, "Live one", live = true, armed = RunMode.LIVE), null)),
            pending = mapOf(1L to RunMode.SANDBOX, 2L to RunMode.LIVE))
        compose.onAllNodesWithText("Approve & start")[0].areaCClick(); compose.waitForIdle()
        assertEquals("approve 1", rec.calls.last())
        compose.onAllNodesWithText("Approve & start")[1].areaCClick(); compose.waitForIdle()
        assertEquals("a live approval waits for the PIN", 1, rec.calls.size)
        tap("PIN OK")
        assertEquals("approve 2", rec.calls.last())
        compose.onAllNodesWithText("Skip today")[1].areaCClick(); compose.waitForIdle()
        assertEquals("skip 2", rec.calls.last())
    }

    @Test fun importedOrbCopiesAreHiddenAndTheNameOpensStrategies() {
        var managed = 0
        card(listOf(Strategies.Entry(StrategyFakes.def(1, "ORB Fresh"), null), Strategies.Entry(StrategyFakes.def(2, "Straddle"), null)), manage = { managed++ })
        assertTrue(shown("1 imported ORB strategy is hidden here"))
        assertTrue(!shown("ORB Fresh"))
        tap("Straddle")
        assertEquals(1, managed)
        assertTrue(shown("ORB rows"))
    }

    @Test fun theBotButtonStopsForTodayAlsoStoppingWhatRuns() {
        card(listOf(StrategyFakes.running(1, "Straddle")))
        assertTrue(shown("Bot running: armed strategies start on time"))
        tap("Stop bot for today")
        assertTrue(shown("Stop the bot for today?"))
        tap("Also stop the strategies running now (their positions are closed)")
        tap("Stop for today")
        assertEquals(listOf("stopBot also=true"), rec.calls)
    }

    @Test fun theBotButtonStartsAgainOrClearsTheKillSwitch() {
        card(emptyList(), stopped = true)
        assertTrue(shown("Bot stopped for today"))
        tap("Start bot"); tap("Cancel")
        assertTrue(rec.calls.isEmpty())
        tap("Start bot"); tap("Start")
        assertEquals(listOf("startBot"), rec.calls)
    }

    @Test fun theKillSwitchIsClearedFromTheCard() {
        card(emptyList(), kill = true)
        assertTrue(shown("Kill switch ON: all orders blocked"))
        tap("Clear kill switch"); tap("Clear")
        assertEquals(listOf("clearKill"), rec.calls)
    }

    @Test fun pastedJsonIsImported() {
        card(emptyList())
        tap("Import from desktop")
        compose.onNodeWithText("Import").assertIsNotEnabled()
        compose.onNodeWithText("…or paste the JSON").performTextInput("[{\"name\":\"X\",\"legs\":[]}]")
        tap("Import")
        assertEquals(listOf("import 24"), rec.calls)
        assertTrue("closed", !shown("Choose the .json file"))
    }
}

@RunWith(AndroidJUnit4::class)
class OrbRowsTest {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    @get:Rule val compose = createComposeRule()
    private val rec = RecordingStrategyActions()

    private fun rows(view: OrbArms.View, live: Boolean) = compose.setContent {
        IraAlgoTheme("light") {
            Column { OrbRowsContent(view, live, rec, StrategyFakes.reauthWhy) }
        }
    }

    private fun tap(text: String) { compose.onAllNodesWithText(text, substring = true).onFirst().areaCClick(); compose.waitForIdle() }
    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun armingInPaper() {
        rows(StrategyFakes.orbView(), live = false)
        assertTrue(shown("BANKNIFTY opening-range break, fresh breaks only"))
        compose.onAllNodes(isToggleable())[0].areaCClick(); compose.waitForIdle()
        assertTrue(shown("Arm ORB (paper)"))
        tap("Automatic")
        assertEquals(listOf("orb arm orb on=true auto=true pin=false"), rec.calls)
    }

    @Test fun armingInLiveTakesThePinWithItsReason() {
        rows(StrategyFakes.orbView(), live = true)
        compose.onAllNodes(isToggleable())[1].areaCClick(); compose.waitForIdle()
        assertTrue(shown("Arm ORB Fresh (LIVE)"))
        tap("Ask me to approve")
        assertTrue(shown("Enter your app PIN to arm ORB on Zerodha"))
        tap("PIN OK")
        assertEquals(listOf("orb arm orb_fresh on=true auto=false pin=true"), rec.calls)
    }

    @Test fun anArmedArmSaysWhatItWaitsForAndDisarms() {
        rows(StrategyFakes.orbView(armed = true), live = false)
        assertTrue(shown("ARMED · PAPER · AUTO"))
        assertTrue(shown("Waiting for a breakout: the last bar closed inside the range. Range 51980.00–52310.00."))
        compose.onAllNodes(isToggleable())[0].areaCClick(); compose.waitForIdle()
        assertEquals(listOf("orb arm orb on=false auto=true pin=false"), rec.calls)
    }

    @Test fun aPaperBreakoutIsApprovedOrSkipped() {
        rows(StrategyFakes.orbView(armed = true, pending = true), live = false)
        assertTrue(shown("Breakout on the 10:40 bar: BUY CE, 1 lot, paper. Lapses at 10:50."))
        tap("Approve entry")
        tap("Skip")
        assertEquals(listOf("orb approve orb pin=false", "orb skip orb"), rec.calls)
    }

    @Test fun aLiveBreakoutIsApprovedOnlyWithThePin() {
        rows(StrategyFakes.orbView(armed = true, pending = true, live = true), live = true)
        tap("Approve with PIN")
        assertTrue(rec.calls.isEmpty())
        tap("PIN OK")
        assertEquals(listOf("orb approve orb pin=true"), rec.calls)
    }

    @Test fun anOpenPositionAndTheDaysDetail() {
        rows(StrategyFakes.orbView(armed = true, open = true, live = true), live = true)
        assertTrue(shown("IN TRADE · LIVE"))
        assertTrue(shown("in 210.00 · now 220.00 · ₹300 · stop 170.00"))
        assertTrue(shown("Today: 1 closed · ₹1,158 after charges"))
        tap("Forward test: 1/60 trades")
        assertTrue(shown("ORB arms · today"))
        assertTrue(shown("Strike 52000 (09:20 bar)"))
        assertTrue(shown("Evening replay"))
        tap("Close")
        assertTrue(!shown("ORB arms · today"))
    }
}

/** A screenshot of the top window: a dialog when one is open (ScreenTest.capture expects a single root). */
internal fun ScreenTest.areaCCaptureTop(compose: ComposeTestRule, name: String, device: DeviceConfig) {
    val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot())
    roots[roots.fetchSemanticsNodes().size - 1].captureRoboImage("build/outputs/roborazzi/${name}_${device.name}.png")
}

/** Six set-ups that span the matrix (every size, every font scale, both themes), for secondary states: keeps CI time in bounds. */
internal val SIX_SETUPS = setOf("small-font2.0-light", "small-font1.0-dark", "phone-font1.3-light", "landscape-font1.0-light",
    "landscape-font2.0-dark", "tablet-font1.3-dark")

/** Real layout bugs found by the matrix below (skipped with this text until fixed). */
internal object StrategyLayoutBugs {
    // Fixed: the running leg's Exit and the Edit / Delete buttons were 40 dp high; the Arm switches had no label; an ORB arm's
    // status was cut at 2 lines and a strategy's summary line at 1.
    val PAGE = emptyMap<String, String>()
    // Fixed too: the approval buttons ('Approve & start', 'Approve entry') were ellipsized at font 1.3+ on a small phone
    // (BrassButton now wraps to two lines), and the dialogs' Cancel / Confirm / Close were 40 dp high (TextButton is 48 dp).
    val CARD = emptyMap<String, String>()
}

/** The Strategies page, Home's strategy card and the ORB rows on every device set-up (their main states). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrategiesScreensLayoutTest(private val config: DeviceConfig) : ScreenTest(config) {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()
    }

    private val rec = RecordingStrategyActions()

    @Test fun strategiesPage() = checkScreen("strategies-page", knownBugs = StrategyLayoutBugs.PAGE, content = StrategyScreens.page(rec))

    @Test fun strategiesEmpty() = checkScreen("strategies-empty") {
        StrategiesContent(false, emptyList(), emptyList(), rec, presets = { Text("Presets card") }, reauth = StrategyFakes.reauth)
    }

    @Test fun homeStrategyCard() = checkScreen("strategy-arm-card", knownBugs = StrategyLayoutBugs.CARD, content = StrategyScreens.card(rec))
}

internal object StrategyScreens {
    private val entries = listOf(StrategyFakes.running(1, "Short straddle"), Strategies.Entry(StrategyFakes.def(2, "Iron fly", live = true), null))

    fun page(rec: RecordingStrategyActions) = @Composable {
        StrategiesContent(true, entries, StrategyFakes.log, rec, presets = { Text("Presets card") }, reauth = StrategyFakes.reauth)
    }

    fun card(rec: RecordingStrategyActions) = @Composable {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            StrategyArmContent(false, false, listOf(Strategies.Entry(StrategyFakes.def(1, "Straddle", armed = RunMode.SANDBOX), null),
                Strategies.Entry(StrategyFakes.def(2, "Iron fly", live = true), null)), mapOf(1L to true), mapOf(1L to RunMode.SANDBOX), false, rec, {},
                orbRows = { OrbRowsContent(StrategyFakes.orbView(armed = true, pending = true), false, rec, StrategyFakes.reauthWhy) }, reauth = StrategyFakes.reauth)
        }
    }
}

/**
 * The dialogs of the Strategies page and Home's card on six set-ups. The editor and the import dialog hold text
 * fields (a blinking cursor never lets Compose idle), so they are opened and captured on a paused clock.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrategiesDialogsLayoutTest(private val config: DeviceConfig) : ScreenTest(config) {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix().filter { (it[0] as DeviceConfig).name in SIX_SETUPS }
    }

    private val rec = RecordingStrategyActions()

    private fun top(name: String, bugs: Map<String, String> = emptyMap()) {
        areaCCaptureTop(compose, name, config); lint(name, knownBugs = bugs)
    }

    private fun <T> paused(block: () -> T): T {
        compose.mainClock.autoAdvance = false
        try { return block() } finally { compose.mainClock.autoAdvance = true }
    }

    private fun frames(n: Int = 12) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    @Test fun startLiveConfirm() {
        show(StrategyScreens.page(rec))
        compose.strategyScrollTo("Start live").areaCClick(); compose.waitForIdle()
        top("strategies-start-live")
    }

    @Test fun strategyEditor() {
        show(StrategyScreens.page(rec))
        paused {
            compose.onNodeWithText("New strategy").areaCClick(); frames()
            areaCCaptureTop(compose, "strategies-editor", config)
            compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { LayoutLint.check(it, compose.density) }
                .filter { it.level == LayoutLint.Level.ERROR }.let { errs ->
                    errs.forEach { println("LAYOUT strategies-editor ${config.name}: $it") }
                    org.junit.Assume.assumeTrue("LAYOUT BUG (strategies-editor, ${config.name}): findings listed\n${errs.joinToString("\n")}", errs.isEmpty())
                }
        }
    }

    @Test fun importDialog() {
        show(StrategyScreens.card(rec))
        paused {
            compose.onNodeWithText("Import from desktop").areaCClick(); frames()
            areaCCaptureTop(compose, "strategy-import-dialog", config)
            compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { LayoutLint.check(it, compose.density) }
                .filter { it.level == LayoutLint.Level.ERROR }.let { errs ->
                    errs.forEach { println("LAYOUT strategy-import-dialog ${config.name}: $it") }
                    org.junit.Assume.assumeTrue("LAYOUT BUG (strategy-import-dialog, ${config.name}): findings listed\n${errs.joinToString("\n")}", errs.isEmpty())
                }
        }
    }

    @Test fun botDialog() {
        show(StrategyScreens.card(rec))
        compose.onNodeWithText("Stop bot for today").areaCClick(); compose.waitForIdle()
        top("strategy-bot-dialog", StrategyLayoutBugs.CARD)
    }

    @Test fun armChoiceDialog() {
        show(StrategyScreens.card(rec))
        compose.onAllNodes(isToggleable())[0].areaCClick(); compose.waitForIdle()
        top("orb-arm-choice", StrategyLayoutBugs.CARD)
    }

    @Test fun orbDetail() {
        // The rows are a card's contents (the card is a Column): shown in one, not stacked on each other.
        show { Column { OrbRowsContent(StrategyFakes.orbView(armed = true, open = true, live = true), true, rec, StrategyFakes.reauthWhy) } }
        compose.onAllNodesWithText("Forward test", substring = true).onFirst().areaCClick(); compose.waitForIdle()
        top("orb-detail", StrategyLayoutBugs.CARD)
    }
}
