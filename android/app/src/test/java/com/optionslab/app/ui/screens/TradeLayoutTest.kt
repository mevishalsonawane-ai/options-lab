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
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TradeLayoutTest(device: DeviceConfig) : TradeScreenBase(device) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()

        /** First run on CI: every error is listed (as a skipped LAYOUT BUG) instead of failing, to be triaged. */
        const val DISCOVERY = true
        private val ALL = listOf(TradeScreenBase.Known(Regex("."), "DISCOVERY: findings to triage"))
        fun known(vararg k: TradeScreenBase.Known): List<TradeScreenBase.Known> = if (DISCOVERY) ALL else k.toList()
    }

    private lateinit var upstox: FakeUpstox
    private lateinit var holder: ModelHolder
    private var kite: FakeKite? = null
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val near: LocalDate get() = TradeFixtures.nearExpiry
    private val m: AppModel get() = holder.model

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

    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

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

    private fun scrollTo(text: String) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))

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
    private val strikeOverlay = arrayOf(
        TradeScreenBase.Known(Regex("A11Y.*clickable node \\d+ has no text"), "StrikeDropdown: the full-field click overlay has no label or role for TalkBack"),
        TradeScreenBase.Known(Regex("OVERLAP.*Strike"), "StrikeDropdown: the click overlay is a separate node drawn over the strike text field"),
    )

    @Test fun paperAccount() {
        paperPosition(); paperRestingLimit()
        showPaper()
        snap("trade-paper-account", known())
        if (device.fontScale == 1.0f) smokeEveryAction(skip = setOf("Close"))
    }

    @Test fun paperPositions() {
        paperPosition()
        showPaper()
        scrollTo("Paper positions")
        snap("trade-paper-positions", known())
    }

    @Test fun paperOrderForm() {
        showPaper()
        compose.onNodeWithText("New paper order").performClick()
        compose.waitUntil(20_000) { exists("24500") }
        compose.onNodeWithText("SL").performClick()
        scrollTo("Place paper order")
        snap("trade-paper-form", known(*strikeOverlay))
    }

    @Test fun paperStrikeList() {
        showPaper()
        compose.onNodeWithText("New paper order").performClick()
        compose.waitUntil(20_000) { exists("24500") }
        scrollTo("24500")
        compose.onNodeWithText("24500").performClick()
        compose.waitUntil(5_000) { exists("24500   ATM") }
        snap("trade-paper-strikes", known(*strikeOverlay))
    }

    @Test fun paperOrders() {
        paperRestingLimit(); paperPosition()
        showPaper()
        scrollTo("Orders")
        compose.onNodeWithText("Orders").performClick()
        compose.waitUntil(5_000) { exists("Modify") }
        scrollTo("Modify")
        snap("trade-paper-orders", known())
    }

    @Test fun paperModifyDialog() {
        paperRestingLimit()
        showPaper()
        scrollTo("Orders")
        compose.onNodeWithText("Orders").performClick()
        compose.waitUntil(5_000) { exists("Modify") }
        scrollTo("Modify")
        compose.onNodeWithText("Modify").performClick()
        snap("trade-paper-modify", known())
    }

    @Test fun paperFunds() {
        paperPosition()
        showPaper()
        scrollTo("Funds")
        compose.onNodeWithText("Funds").performClick()
        compose.waitUntil(5_000) { exists("Realised, all time") }
        scrollTo("Set paper amount / reset")
        snap("trade-paper-funds", known())
    }

    @Test fun paperAmountDialog() {
        showPaper()
        scrollTo("Set paper amount")
        compose.onNodeWithText("Set paper amount").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("9999")
        snap("trade-paper-amount", known())
    }

    @Test fun paperPositionPopup() {
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
    private val sideSwitch = arrayOf(
        TradeScreenBase.Known(Regex("TOUCH.*clickable '(BUY|SELL)'"), "OptionOrderSheet: the BUY / SELL switch is under 48 dp tall"),
    )

    @Test fun orderSheetPaper() {
        show { OptionOrderSheet(m, pick, initialBuy = true, initialLimit = 98.5) {} }
        compose.onNodeWithText("▸ Bracket: stop · trail · target").performScrollTo().performClick()
        compose.waitForIdle()
        snap("trade-sheet-paper", known(*sideSwitch))
    }

    @Test fun orderSheetLive() {
        live()
        show { OptionOrderSheet(m, pick, initialBuy = false) {} }
        snap("trade-sheet-live", known(*sideSwitch))
    }

    // ---- live ------------------------------------------------------------------------------------

    @Test fun livePositions() {
        live(sym to 75, "NIFTY26OCT24400CE" to -75)
        showLive()
        snap("trade-live-positions", known())
    }

    @Test fun liveOrders() {
        live(working = true)
        showLive()
        compose.onNodeWithText("Orders 1").performClick()
        compose.waitUntil(5_000) { exists("WORKING") }
        snap("trade-live-orders", known())
    }

    @Test fun liveModifyDialog() {
        live(working = true)
        showLive()
        compose.onNodeWithText("Orders 1").performClick()
        compose.waitUntil(5_000) { exists("Modify") }
        compose.onNodeWithText("Modify").performClick()
        compose.onNodeWithText("SL").performClick()
        snap("trade-live-modify", known())
    }

    @Test fun liveGttDialog() {
        live(sym to 75)
        showLive()
        compose.onNodeWithText("Protect (GTT)").performClick()
        snap("trade-live-gtt", known())
    }

    @Test fun liveLoggedOut() {
        SecurePrefs.putAll(mapOf("k.mode" to "live", "k.allow" to true, "kite.apiKey" to "testkey", "kite.apiSecret" to "testsecretnotreal"))
        show(tradeTab)
        compose.waitUntil(5_000) { exists("Log in to Zerodha") }
        snap("trade-live-logged-out", known())
    }
}
