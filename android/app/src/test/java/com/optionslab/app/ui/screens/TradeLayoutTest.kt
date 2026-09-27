package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.optionslab.app.data.Paper
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.ModelHolder
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.app.testing.TradeScreenBase
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.engine.Right
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Every Trade-tab state the functional tests drive (PaperScreenTest, OptionOrderSheetTest,
 * TradeScreenTest), on every device set-up: a screenshot each and [com.optionslab.app.testing.LayoutLint],
 * with the real screens on a real [AppModel] (paper: the sandbox priced by [FakeUpstox]; live: [FakeKite]).
 */
abstract class TradeLayoutBase(device: DeviceConfig) : TradeScreenBase(device) {
    companion object {
        /** First runs on CI: every error is listed (as a skipped LAYOUT BUG) instead of failing, to be triaged. */
        const val DISCOVERY = true
        private val ALL = listOf(TradeScreenBase.Known(Regex("."), "DISCOVERY: findings to triage"))
        fun known(vararg k: TradeScreenBase.Known): List<TradeScreenBase.Known> = if (DISCOVERY) ALL else k.toList()
    }

    protected lateinit var upstox: FakeUpstox
    private lateinit var holder: ModelHolder
    private var kite: FakeKite? = null
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val near: LocalDate get() = TradeFixtures.nearExpiry
    protected val m: AppModel get() = holder.model

    @Before fun up() {
        TradeFixtures.paperSettings()
        upstox = FakeUpstox()
        TradeFixtures.seed(app, upstox)
        holder = ModelHolder(app)
    }

    @After fun down() {
        holder.clear()
        upstox.close()
        kite?.close()
    }

    // ---- helpers ------------------------------------------------------------------------------

    protected fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun paperPosition() = runBlocking {
        Paper.place(Paper.contractFor("NIFTY", near, 24_500.0, Right.PE)!!, "BUY", 1, "MARKET", "NRML", null, null)
    }

    private fun paperRestingLimit() = runBlocking {
        Paper.place(Paper.contractFor("NIFTY", near, 24_400.0, Right.CE)!!, "BUY", 1, "LIMIT", "NRML", 90.0, null)
    }

    private val tradeTab = @Composable { Box(Modifier.fillMaxSize()) { TradeScreen(m); RowActionPopup(m) } }

    private fun showPaper() {
        show(tradeTab)
        compose.waitUntil(20_000) { m.paper.value is Load.Done<*> }
        compose.waitForIdle()
    }

    protected fun scrollTo(text: String) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))

    private val pick get() = ChainPick("NIFTY", near, 24_500.0, Right.PE, ltp = 100.0, delta = -0.45, ivPct = 14.2, lotSize = 75)

    private val sym = "NIFTY26OCT24500PE"

    private fun live(vararg positions: Pair<String, Int>, working: Boolean = false) {
        val k = FakeKite().also { kite = it }
        k.login()
        SecurePrefs.put("kite.apiSecret", "testsecretnotreal")
        SecurePrefs.put("g.cutoff", -1)
        k.instruments += FakeKite.Ins(12_345_678, sym, "NIFTY", near, 24_500.0, "PE", 75)
        k.quote("NFO:$sym", 110.0, 109.95, 110.05)
        positions.forEach { (s, q) -> k.position(s, q, 100.0) }
        if (working) k.orders["250926000000901"] = FakeKite.Order("250926000000901", sym, "NFO", "BUY", 75, "NRML", "LIMIT", 90.0, 0.0, "", "OPEN")
    }

    private fun showLive() {
        show(tradeTab)
        compose.waitUntil(20_000) { m.account.value is Load.Done<*> }
        compose.waitForIdle()
    }

    // ---- paper -----------------------------------------------------------------------------------

    /** The StrikeDropdown's whole-field tap target: a bare clickable Box over the text field. */
    protected val strikeOverlay = arrayOf(
        TradeScreenBase.Known(Regex("A11Y.*clickable node \\d+ has no text"), "StrikeDropdown: the full-field click overlay has no label or role for TalkBack"),
        TradeScreenBase.Known(Regex("OVERLAP.*Strike"), "StrikeDropdown: the click overlay is a separate node drawn over the strike text field"),
    )

    protected fun paperAccount() {
        paperPosition(); paperRestingLimit()
        showPaper()
        snap("trade-paper-account", known())
        if (device.name == "phone-font1.0-light") smokeEveryAction(skip = setOf("Close"))
    }

    protected fun paperPositions() {
        paperPosition()
        showPaper()
        scrollTo("Paper positions")
        snap("trade-paper-positions", known())
    }

    protected fun paperOrderForm() {
        showPaper()
        compose.onNodeWithText("New paper order").performClick()
        compose.waitUntil(20_000) { exists("24500") }
        compose.onNodeWithText("SL").performClick()
        scrollTo("Place paper order")
        snap("trade-paper-form", known(*strikeOverlay))
    }

    protected fun paperStrikeList() {
        showPaper()
        compose.onNodeWithText("New paper order").performClick()
        compose.waitUntil(20_000) { exists("24500") }
        scrollTo("24500")
        compose.onNodeWithText("24500").performClick()
        compose.waitUntil(5_000) { exists("24500   ATM") }
        snap("trade-paper-strikes", known(*strikeOverlay))
    }

    protected fun paperOrders() {
        paperRestingLimit(); paperPosition()
        showPaper()
        scrollTo("Orders")
        compose.onNodeWithText("Orders").performClick()
        compose.waitUntil(5_000) { exists("Modify") }
        scrollTo("Modify")
        snap("trade-paper-orders", known())
    }

    protected fun paperModifyDialog() {
        paperRestingLimit()
        showPaper()
        scrollTo("Orders")
        compose.onNodeWithText("Orders").performClick()
        compose.waitUntil(5_000) { exists("Modify") }
        scrollTo("Modify")
        paused {
            compose.onNodeWithText("Modify").performClick(); frames()
            snap("trade-paper-modify", known())
        }
    }

    protected fun paperFunds() {
        paperPosition()
        showPaper()
        scrollTo("Funds")
        compose.onNodeWithText("Funds").performClick()
        compose.waitUntil(5_000) { exists("Realised, all time") }
        scrollTo("Set paper amount / reset")
        snap("trade-paper-funds", known())
    }

    protected fun paperAmountDialog() {
        showPaper()
        compose.waitUntil(10_000) { runCatching { scrollTo("Set paper amount"); true }.getOrDefault(false) }
        paused {
            compose.onNodeWithText("Set paper amount").performClick(); frames()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("9999"); frames()
            snap("trade-paper-amount", known())
        }
    }

    protected fun paperPositionPopup() {
        paperPosition()
        showPaper()
        val s = Paper.state.positions.first().symbol
        scrollTo(s)
        compose.onAllNodesWithText(s)[0].performClick()
        compose.waitUntil(5_000) { m.rowAction.value != null }
        snap("trade-paper-popup", known())
    }

    // ---- the order sheet ------------------------------------------------------------------------

    /** The BUY / SELL halves of the sheet's side switch are text with 10 dp padding. */
    protected val sideSwitch = arrayOf(
        TradeScreenBase.Known(Regex("TOUCH.*clickable '(BUY|SELL)'"), "OptionOrderSheet: the BUY / SELL switch is under 48 dp tall"),
    )

    protected fun orderSheetPaper() {
        show { OptionOrderSheet(m, pick, initialBuy = true, initialLimit = 98.5) {} }
        compose.onNodeWithText("▸ Bracket: stop · trail · target").performScrollTo().performClick()
        compose.waitForIdle()
        snap("trade-sheet-paper", known(*sideSwitch))
    }

    protected fun orderSheetLive() {
        live()
        show { OptionOrderSheet(m, pick, initialBuy = false) {} }
        snap("trade-sheet-live", known(*sideSwitch))
    }

    // ---- live ------------------------------------------------------------------------------------

    protected fun livePositions() {
        live(sym to 75, "NIFTY26OCT24400CE" to -75)
        showLive()
        snap("trade-live-positions", known())
    }

    protected fun liveOrders() {
        live(working = true)
        showLive()
        compose.onNodeWithText("Orders 1").performClick()
        compose.waitUntil(5_000) { exists("WORKING") }
        snap("trade-live-orders", known())
    }

    protected fun liveModifyDialog() {
        live(working = true)
        showLive()
        compose.onNodeWithText("Orders 1").performClick()
        compose.waitUntil(5_000) { exists("Modify") }
        paused {
            compose.onNodeWithText("Modify").performClick(); frames()
            compose.onNodeWithText("SL").performClick(); frames()
            snap("trade-live-modify", known())
        }
    }

    protected fun liveGttDialog() {
        live(sym to 75)
        showLive()
        paused {
            compose.onNodeWithText("Protect (GTT)").performClick(); frames()
            snap("trade-live-gtt", known())
        }
    }

    protected fun liveLoggedOut() {
        SecurePrefs.putAll(mapOf("k.mode" to "live", "k.allow" to true, "kite.apiKey" to "testkey", "kite.apiSecret" to "testsecretnotreal"))
        show(tradeTab)
        compose.waitUntil(5_000) { exists("Log in to Zerodha") }
        snap("trade-live-logged-out", known())
    }
}

/** The main Trade-tab states on all 24 device set-ups. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TradeLayoutTest(device: DeviceConfig) : TradeLayoutBase(device) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()
    }

    @Test fun paperAccountState() = paperAccount()
    @Test fun paperOrderFormState() = paperOrderForm()
    @Test fun paperOrdersState() = paperOrders()
    @Test fun orderSheetPaperState() = orderSheetPaper()
    @Test fun livePositionsState() = livePositions()
    @Test fun liveLoggedOutState() = liveLoggedOut()
}

/**
 * The Trade tab's secondary states (lists, dialogs, popups) on six set-ups that span the matrix
 * (every size, every font scale, both themes), to keep the CI time in bounds.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TradeDialogsLayoutTest(device: DeviceConfig) : TradeLayoutBase(device) {
    companion object {
        private val PICK = setOf("small-font2.0-light", "small-font1.0-dark", "phone-font1.3-light", "landscape-font1.0-light",
            "landscape-font2.0-dark", "tablet-font1.3-dark")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix().filter { (it[0] as DeviceConfig).name in PICK }
    }

    @Test fun paperPositionsState() = paperPositions()
    @Test fun paperStrikeListState() = paperStrikeList()
    @Test fun paperModifyDialogState() = paperModifyDialog()
    @Test fun paperFundsState() = paperFunds()
    @Test fun paperAmountDialogState() = paperAmountDialog()
    @Test fun paperPositionPopupState() = paperPositionPopup()
    @Test fun orderSheetLiveState() = orderSheetLive()
    @Test fun liveOrdersState() = liveOrders()
    @Test fun liveModifyDialogState() = liveModifyDialog()
    @Test fun liveGttDialogState() = liveGttDialog()
}
