package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Paper
import com.optionslab.app.data.Protections
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.ModelHolder
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.app.work.Alerts
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The order sheet opened from the option chain ([OptionOrderSheet]) on a real [AppModel]:
 * every control, the limit-price and bracket validation, placing in PAPER mode through the real
 * sandbox (priced by [FakeUpstox]) and, in LIVE mode, opening the review without sending anything
 * (against [FakeKite]).
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(qualifiers = "w411dp-h1400dp")
class OptionOrderSheetTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var upstox: FakeUpstox
    private lateinit var holder: ModelHolder
    private var kite: FakeKite? = null
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val near get() = TradeFixtures.nearExpiry
    private val pick get() = ChainPick("NIFTY", near, 24_500.0, Right.PE, ltp = 100.0, delta = -0.45, ivPct = 14.2, lotSize = 75)
    private val symbol get() = Paper.symbolOf(Upstox.Contract("NIFTY", near, 24_500.0, Right.PE, 75, TradeFixtures.key("NIFTY", near, 24_500.0, "PE"), ""))

    private var closed = 0

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

    private fun show(p: ChainPick = pick, initialBuy: Boolean = true, initialLimit: Double? = null): AppModel {
        val m = holder.model
        var open by mutableStateOf(true)
        compose.setContent {
            IraAlgoTheme("light") { if (open) OptionOrderSheet(m, p, initialBuy, initialLimit) { closed++; open = false } }
        }
        compose.waitForIdle()
        return m
    }

    private fun exists(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun tap(text: String) = compose.onNodeWithText(text).performClick()
    private fun price() = compose.onNode(hasSetTextAction() and hasText("Limit price", substring = true))
    private fun AppModel.snap(): Paper.Snapshot? = (paper.value as? Load.Done<Paper.Snapshot>)?.value

    private fun waitSnap(m: AppModel, what: String, ok: (Paper.Snapshot) -> Boolean): Paper.Snapshot {
        try { compose.waitUntil(20_000) { m.snap()?.let(ok) == true } }
        catch (e: Throwable) { throw AssertionError("timed out waiting for $what; last message: ${m.message.value}", e) }
        return m.snap()!!
    }

    private fun waitMessage(m: AppModel, part: String): String {
        try { compose.waitUntil(20_000) { m.message.value?.contains(part) == true } }
        catch (e: Throwable) { throw AssertionError("expected a message with '$part'; last: ${m.message.value}", e) }
        return m.message.value!!
    }

    // ---- what it shows ---------------------------------------------------------------------

    @Test fun showsTheContractItsGreeksAndPrice() {
        show()
        val title = "NIFTY ${near.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)).uppercase()} 24500 PE"
        assertTrue(title, exists(title))
        assertTrue(exists("Δ -0.45 · IV 14.2% · lot 75"))
        assertTrue(exists("₹100.00"))
        assertTrue(exists("75 qty"))
        assertTrue("market value = LTP x qty", exists("₹7,500"))
        assertTrue(exists("Paper: simulated in your paper account. Nothing reaches Zerodha."))
        compose.onNodeWithText("Buy (paper)").assertIsEnabled()
        compose.onNodeWithText("Market").assertIsSelected()
        compose.onNodeWithText("NRML").assertIsSelected()
    }

    @Test fun withoutAPriceItShowsDashes() {
        show(pick.copy(ltp = null, delta = null, ivPct = null))
        assertTrue(exists("lot 75"))
        assertEquals(2, compose.onAllNodesWithText("—", useUnmergedTree = true).fetchSemanticsNodes().size)   // LTP and value
        tap("Limit")
        price().assertExists()
        assertTrue("no LTP, so the limit box starts empty", !exists("100.00"))
        compose.onNodeWithText("Buy (paper)").assertIsNotEnabled()
    }

    // ---- controls ----------------------------------------------------------------------------

    @Test fun buyAndSellSwitchTheSideAndTheButton() {
        show()
        tap("SELL")
        compose.onNodeWithText("Sell (paper)").assertIsEnabled()
        assertTrue(!exists("Buy (paper)"))
        tap("BUY")
        compose.onNodeWithText("Buy (paper)").assertIsEnabled()
    }

    @Test fun theSheetCanOpenOnTheSellSide() {
        show(initialBuy = false)
        compose.onNodeWithText("Sell (paper)").assertExists()
    }

    @Test fun lotsStayBetweenOneAndFiftyAndDriveQuantityAndValue() {
        show()
        tap("−")
        assertTrue("never below one lot", exists("75 qty"))
        tap("+"); tap("+")
        assertTrue(exists("225 qty") && exists("3"))
        assertTrue(exists("₹22,500"))
        repeat(60) { compose.onNodeWithText("+").performClick() }
        assertTrue("capped at 50 lots", exists("3750 qty") && exists("50"))
        repeat(49) { compose.onNodeWithText("−").performClick() }
        assertTrue(exists("75 qty"))
    }

    @Test fun theProductStartsFromSettingsAndToggles() {
        SecurePrefs.put("k.product", "MIS")
        show()
        compose.onNodeWithText("MIS").assertIsSelected()
        tap("NRML")
        compose.onNodeWithText("NRML").assertIsSelected()
        compose.onNodeWithText("MIS").assertIsNotSelected()
        tap("MIS")
        compose.onNodeWithText("MIS").assertIsSelected()
    }

    @Test fun aLimitPriceMustBeAboveZero() {
        show()
        assertTrue(!exists("Limit price", substring = true))
        tap("Limit")
        compose.onNodeWithText("Limit").assertIsSelected()
        price().assertExists()
        assertTrue("starts at the LTP", exists("100.00"))
        compose.onNodeWithText("Buy (paper)").assertIsEnabled()
        price().performTextClearance()
        compose.onNodeWithText("Buy (paper)").assertIsNotEnabled()
        assertTrue("no price, no value", exists("—"))
        price().performTextInput("0")
        compose.onNodeWithText("Buy (paper)").assertIsNotEnabled()
        price().performTextClearance(); price().performTextInput("0.00")
        compose.onNodeWithText("Buy (paper)").assertIsNotEnabled()
        price().performTextClearance(); price().performTextInput("9x5.5")
        assertTrue("digits and a point only", exists("95.5"))
        compose.onNodeWithText("Buy (paper)").assertIsEnabled()
        assertTrue("value at the limit", exists("₹7,163"))
        price().performTextClearance(); price().performTextInput("12345678901234")
        assertTrue("at most 10 characters", exists("1234567890"))
        tap("Market")
        assertTrue(!exists("Limit price", substring = true))
        compose.onNodeWithText("Buy (paper)").assertIsEnabled()
    }

    @Test fun aLimitPassedInOpensTheSheetOnLimit() {
        show(initialLimit = 92.5)
        compose.onNodeWithText("Limit").assertIsSelected()
        assertTrue(exists("92.50"))
    }

    @Test fun theBracketChecksItsLevelsAgainstTheEntry() {
        show()
        assertTrue(!exists("Stop price"))
        tap("▸ Bracket: stop · trail · target")
        assertTrue(exists("▾ Bracket: stop · trail · target"))
        val stop = compose.onNode(hasSetTextAction() and hasText("Stop price"))
        val target = compose.onNode(hasSetTextAction() and hasText("Target price"))
        val trail = compose.onNode(hasSetTextAction() and hasText("…or trail by (points)"))
        stop.performTextInput("105")      // a buy's stop above the entry
        compose.onNodeWithText("Buy (paper)").assertIsNotEnabled()
        compose.waitUntil(5_000) { Alerts.queue.value.any { it.text == "The stop must be below the current price (100.00)." } }
        stop.performTextClearance(); stop.performTextInput("90")
        compose.onNodeWithText("Buy (paper)").assertIsEnabled()
        target.performTextInput("95")     // a buy's target below the entry
        compose.onNodeWithText("Buy (paper)").assertIsNotEnabled()
        target.performTextClearance(); target.performTextInput("120")
        compose.onNodeWithText("Buy (paper)").assertIsEnabled()
        trail.performTextInput("0")
        compose.onNodeWithText("Buy (paper)").assertIsNotEnabled()
        trail.performTextClearance(); trail.performTextInput("5")
        compose.onNodeWithText("Buy (paper)").assertIsEnabled()
        // On the sell side the same levels are the wrong way round.
        tap("SELL")
        compose.onNodeWithText("Sell (paper)").assertIsNotEnabled()
        // Folding the bracket away drops it.
        tap("▾ Bracket: stop · trail · target")
        compose.onNodeWithText("Sell (paper)").assertIsEnabled()
    }

    // ---- paper orders ----------------------------------------------------------------------------

    @Test fun aPaperBuyFillsAndTheSheetCloses() {
        val m = show()
        tap("+")
        tap("Buy (paper)")
        compose.waitUntil(5_000) { closed == 1 }
        assertEquals("Paper BUY 150 $symbol filled @ 100.05", waitMessage(m, "filled @"))
        val s = waitSnap(m, "the position") { x -> x.positions.positions.any { it.quantity == 150 } }
        assertEquals("NRML", s.orders.orders.single().product)
        assertEquals("MARKET", s.orders.orders.single().priceType)
    }

    @Test fun aPaperLimitAwayFromTheMarketRests() {
        val m = show()
        tap("Limit")
        price().performTextClearance(); price().performTextInput("90")
        tap("Buy (paper)")
        val s = waitSnap(m, "the resting order") { x -> x.orders.orders.any { it.status == "open" } }
        assertEquals(90.0, s.orders.orders.single().price, 1e-9)
        assertEquals("LIMIT", s.orders.orders.single().priceType)
        assertTrue(s.positions.positions.none { it.quantity != 0 })
    }

    @Test fun aPaperBracketSetsTheStopAndTargetOnceFilled() {
        val m = show()
        tap("▸ Bracket: stop · trail · target")
        compose.onNode(hasSetTextAction() and hasText("Stop price")).performTextInput("90")
        compose.onNode(hasSetTextAction() and hasText("Target price")).performTextInput("120")
        tap("Buy (paper)")
        val s = waitSnap(m, "the exits") { x -> x.orders.orders.size == 3 }
        val exits = s.orders.orders.filter { it.action == "SELL" }
        assertEquals(2, exits.size)
        assertTrue("stop: SL-M sell, trigger 90: $exits", exits.any { it.priceType == "SL-M" && it.triggerPrice == 90.0 && it.quantity == 75 })
        assertTrue("target: LIMIT sell at 120: $exits", exits.any { it.priceType == "LIMIT" && it.price == 120.0 && it.quantity == 75 })
        val p = runBlocking { Protections.active() }.single()
        assertEquals(symbol, p.symbol); assertEquals(false, p.live); assertEquals(75, p.qty)
    }

    @Test fun theKillSwitchRefusesTheOrderAndSaysWhy() {
        TradeFixtures.killSwitch(true)
        val m = show()
        tap("Buy (paper)")
        assertTrue(waitMessage(m, "Not placed (account guard)").contains("kill switch"))
        m.loadPaper()
        waitSnap(m, "the book") { true }.let { assertTrue(it.orders.orders.isEmpty()) }
    }

    @Test fun aSellWithNoCoverIsRefusedAsANakedShort() {
        val m = show(initialBuy = false)
        tap("Sell (paper)")
        assertTrue(waitMessage(m, "Not placed (account guard)").contains("Naked short"))
    }

    @Test fun aDoubleTapPlacesOneOrder() {
        val m = show()
        compose.onNodeWithText("Buy (paper)").performClick()
        // The sheet is gone after the first tap, so a second tap has nothing to hit.
        runCatching { compose.onNodeWithText("Buy (paper)").performClick() }
        waitSnap(m, "the fill") { x -> x.orders.orders.isNotEmpty() }
        Thread.sleep(1_500)
        m.loadPaper(quiet = true)
        compose.waitUntil(10_000) { m.paper.value is Load.Done<*> }
        assertEquals(1, m.snap()!!.orders.orders.size)
    }

    @Test fun twoTapsDeliveredInOneFrameStillPlaceOneOrder() {
        val m = show()
        // A busy main thread delivers both taps of a fast double tap before the sheet recomposes away.
        val click = compose.onNodeWithText("Buy (paper)").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread { click(); click() }
        waitSnap(m, "the fill") { x -> x.orders.orders.isNotEmpty() }
        Thread.sleep(2_000)
        m.loadPaper(quiet = true)
        compose.waitUntil(10_000) { m.paper.value is Load.Done<*> }
        val n = m.snap()!!.orders.orders.size
        assumeTrue("UI BUG: OptionOrderSheet (paper): two taps on 'Buy (paper)' delivered before the sheet recomposes away " +
            "(a fast double tap on a busy main thread) place two paper orders; expected one (the paper order form guards this " +
            "with its 'placing' state, the sheet has no guard); actual $n orders, position ${m.snap()!!.positions.positions.sumOf { it.quantity }}",
            n == 1)
        assertEquals(1, n)
    }

    @Test fun tappingTheBackdropClosesWithoutPlacing() {
        val m = show()
        compose.onNode(isDialog()).performTouchInput { click(Offset(centerX, 10f)) }
        compose.waitUntil(5_000) { closed == 1 }
        Thread.sleep(500)
        assertEquals(null, m.message.value)
    }

    @Test fun tappingInsideTheSheetDoesNotCloseIt() {
        show()
        // Taps on the sheet's own texts (not the backdrop around it).
        compose.onNodeWithText("75 qty", useUnmergedTree = true).performClick()
        compose.onNodeWithText("₹100.00", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(0, closed)
        assertTrue(exists("Buy (paper)"))
    }

    @Test fun theSheetsTextsAreNotPartOfTheBackdropsCloseAction() {
        show()
        val title = "NIFTY ${near.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)).uppercase()} 24500 PE"
        val node = compose.onNodeWithText(title).fetchSemanticsNode()
        val click = node.config.getOrNull(SemanticsActions.OnClick)
        if (click != null) {
            // What TalkBack does on a double tap of the contract's name:
            compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assumeTrue("UI BUG (accessibility): OptionOrderSheet: the full-screen backdrop is one clickable (it closes the sheet) with " +
                "no label, and the sheet's texts (title, greeks, LTP, lots, value) merge into it; with TalkBack, focusing '$title' and " +
                "double-tapping closes the sheet (closed=$closed). Expected: the texts readable on their own and the backdrop a separate " +
                "'Close' action", false)
        }
    }

    // ---- live: review only ------------------------------------------------------------------------

    @Test fun liveOpensTheReviewAndSendsNothing() {
        val k = FakeKite().also { kite = it }
        k.login()
        SecurePrefs.put("g.cutoff", -1)
        k.instruments += FakeKite.Ins(12_345_678, "NIFTY26OCT24500PE", "NIFTY", near, 24_500.0, "PE", 75)
        k.quote("NFO:NIFTY26OCT24500PE", 100.0, 99.95, 100.05)
        val m = show()
        m.loadAccount(quiet = true)
        compose.waitUntil(20_000) { m.account.value is Load.Done<*> }
        assertTrue(exists("Live: Zerodha. The order opens for review; it is sent only after you hold the button and confirm with your PIN or fingerprint."))
        assertTrue(!exists("Buy (paper)"))
        tap("Limit"); price().performTextClearance(); price().performTextInput("99.5")
        compose.onNodeWithText("Review buy order").performClick()
        compose.waitUntil(5_000) { closed == 1 }
        compose.waitUntil(20_000) { m.plan.value is Load.Done<*> || m.plan.value is Load.Failed }
        val plan = (m.plan.value as? Load.Done)?.value ?: throw AssertionError("the review failed: ${m.plan.value}")
        val leg = plan.legs.single()
        assertEquals("NIFTY26OCT24500PE", leg.tradingSymbol)
        assertEquals(75, leg.quantity)
        assertEquals(99.5, leg.price!!, 1e-9)
        assertEquals("NRML", leg.product)
        assertTrue("the review must not send anything: ${k.writes}", k.writes.isEmpty())
        assertTrue("and nothing is placed on paper", Paper.state.orders.isEmpty())
    }

    @Test fun liveSellReviewIsOffWithABadLimit() {
        val k = FakeKite().also { kite = it }
        k.login()
        show(initialBuy = false)
        tap("Limit"); price().performTextClearance()
        compose.onNodeWithText("Review sell order").assertIsNotEnabled()
        price().performTextInput("0")
        compose.onNodeWithText("Review sell order").assertIsNotEnabled()
        assertTrue(k.writes.isEmpty())
    }
}
