package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Paper
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.ModelHolder
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.sandbox.SandboxCosts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The Trade tab in PAPER mode, end to end: the real [TradeScreen] (paper account, order form,
 * books, reset dialog) and [RowActionPopup] on a real [AppModel], with the real sandbox engine
 * ([Paper]) priced by [FakeUpstox] (localhost) and the contract list seeded for today.
 * Nothing reaches Zerodha or Upstox.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(qualifiers = "w411dp-h3200dp")
class PaperScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)
    @get:Rule val watchdog = com.optionslab.app.testing.TradeWatchdog()

    private lateinit var upstox: FakeUpstox
    private lateinit var holder: ModelHolder
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun up() {
        TradeFixtures.paperSettings()
        upstox = FakeUpstox()
        TradeFixtures.seed(app, upstox)
        holder = ModelHolder(app)
    }

    @After fun down() {
        holder.clear()
        upstox.close()
        assertTrue("the candle feed must go to the fake: ${NetworkGuard.blocked}", FakeUpstox.HOST !in NetworkGuard.blocked)
    }

    // ---- helpers ------------------------------------------------------------------------

    private val near get() = TradeFixtures.nearExpiry
    private fun sym(strike: Double, right: String = "PE", u: String = "NIFTY", e: LocalDate = near) =
        Paper.symbolOf(Upstox.Contract(u, e, strike, Right.valueOf(right), 75, TradeFixtures.key(u, e, strike, right), ""))

    private fun show(): AppModel {
        val m = holder.model
        compose.setContent { IraAlgoTheme("light") { Box(Modifier.fillMaxSize()) { TradeScreen(m); RowActionPopup(m) } } }
        compose.waitUntil(20_000) { m.paper.value is Load.Done<*> }
        return m
    }

    private fun AppModel.snap(): Paper.Snapshot? = (paper.value as? Load.Done<Paper.Snapshot>)?.value

    private fun waitSnap(m: AppModel, what: String, timeoutMs: Long = 20_000, ok: (Paper.Snapshot) -> Boolean): Paper.Snapshot {
        try {
            compose.waitUntil(timeoutMs) { m.snap()?.let(ok) == true }
        } catch (e: Throwable) {
            throw AssertionError("timed out waiting for $what; last message: ${m.message.value}; orders: ${m.snap()?.orders?.orders}", e)
        }
        return m.snap()!!
    }

    private fun waitMessage(m: AppModel, part: String): String {
        try {
            compose.waitUntil(20_000) { m.message.value?.contains(part) == true }
        } catch (e: Throwable) {
            throw AssertionError("expected a message with '$part'; last: ${m.message.value}", e)
        }
        return m.message.value!!
    }

    /**
     * Was: the clock paused around text-field dialogs. On a paused clock such a dialog re-measures for ever
     * (CI: the dialog window's layout never settles), so the clock now runs; kept as a named block for the
     * dialog steps.
     */
    private fun <T> paused(block: () -> T): T = try { block() } finally { frames() }

    private fun frames(n: Int = 12) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    private fun pump(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!runCatching(cond).getOrDefault(false)) {
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in $timeoutMs ms")
            frames(2); Thread.sleep(20)
        }
    }

    private fun exists(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun tap(text: String) = compose.onNodeWithText(text).performClick()

    /** Opens the form and waits until the listed expiries and the ATM strike are in. */
    private fun openForm(strike: String = "24500") {
        tap("New paper order")
        compose.waitUntil(20_000) { exists(near.toString().substring(5)) && exists(strike) }
    }

    /** The form's Close (above any position row's Close). */
    private fun closeForm() {
        compose.onAllNodesWithText("Close")[0].performClick()
        compose.waitUntil(5_000) { exists("New paper order") }
    }

    private fun placeButton(): SemanticsNodeInteraction = compose.onNodeWithText("Place paper order")

    private fun waitPlaceable() = compose.waitUntil(20_000) {
        runCatching { placeButton().assertIsEnabled(); true }.getOrDefault(false)
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label, substring = true))

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun buyMarket(m: AppModel, strike: Double = 24_500.0): Paper.Snapshot {
        openForm()
        tap("BUY")
        if (strike != 24_500.0) pickStrike(strike)
        waitPlaceable()
        placeButton().performClick()
        val s = waitSnap(m, "the buy to fill") { x -> x.positions.positions.any { it.symbol == sym(strike) && it.quantity == 75 } }
        closeForm()
        return s
    }

    private fun pickStrike(k: Double) {
        compose.onNodeWithText("24500").performClick()
        compose.waitUntil(5_000) { exists("${k.toInt()}") }
        compose.onNode(hasText("${k.toInt()}") and hasAnyAncestor(androidx.compose.ui.test.isPopup())).performClick()
        compose.waitUntil(5_000) { exists("${k.toInt()}") }
    }

    // ---- the whole paper round trip ------------------------------------------------------

    @Test fun pickPlaceSeePositionCloseAndTheBooksAndPnlAddUp() {
        val m = show()
        val start = m.snap()!!.funds.availableCash
        assertEquals("a fresh paper account holds its starting capital", 10_000_000.0, start, 0.001)
        // The screen's own first load may still be showing "Opening the paper account".
        compose.waitUntil(10_000) { exists("No paper positions.") }

        // Pick: NIFTY, nearest expiry, ATM PE (24,500 around a 24,512 index), BUY 1 lot, MARKET, NRML.
        openForm()
        compose.onNodeWithText("NIFTY").assertIsSelected()
        compose.onNodeWithText(near.toString().substring(5)).assertIsSelected()
        compose.onNodeWithText("PE").assertIsSelected()
        compose.onNodeWithText("SELL").assertIsSelected()
        tap("BUY")
        compose.onNodeWithText("BUY").assertIsSelected()
        compose.onNodeWithText("SELL").assertIsNotSelected()
        compose.onNodeWithText("MARKET").assertIsSelected()
        compose.onNodeWithText("NRML").assertIsSelected()
        assertTrue("the strike box opens on the at-the-money strike", exists("24500"))
        assertTrue(exists("Strike · NIFTY 24,512"))

        // Place: MARKET with no bid/ask fills at the last price plus 5 bps, and pays its charges.
        waitPlaceable()
        placeButton().performClick()
        val filled = waitMessage(m, "filled @")
        assertEquals("Paper BUY 75 ${sym(24_500.0)} filled @ 100.05", filled)
        var s = waitSnap(m, "the position") { x -> x.positions.positions.any { it.quantity == 75 } }
        val pos = s.positions.positions.single()
        assertEquals(sym(24_500.0), pos.symbol)
        assertEquals(100.05, pos.averagePrice, 1e-9)
        val buyCharge = SandboxCosts.charge("BUY", BigDecimal("100.05"), 75).toDouble()
        assertEquals(buyCharge, s.trades.single().charges, 0.001)

        // The position appears on the page.
        closeForm()
        compose.waitUntil(10_000) { exists(sym(24_500.0)) && exists("NRML · LONG 75", substring = true) }

        // The market moves up 10; the page shows the new mark.
        upstox.price(TradeFixtures.key("NIFTY", near, 24_500.0, "PE"), 110.0)
        m.loadPaper(quiet = true)
        s = waitSnap(m, "the new mark") { x -> x.positions.positions.single().ltp == 110.0 }
        assertEquals("unrealised = (110 - 100.05) x 75", 746.25, s.positions.positions.single().unrealizedPnl, 0.001)
        compose.waitUntil(10_000) { exists("avg 100.05 → 110.00", substring = true) }

        // Close from the row: a MARKET sell at 110 less 5 bps = 109.945 -> 109.94 (half-even).
        tap("Close")
        assertEquals("Paper SELL 75 ${sym(24_500.0)} filled @ 109.94", waitMessage(m, "SELL 75"))
        s = waitSnap(m, "the position to close") { x -> x.positions.positions.all { it.quantity == 0 } && x.trades.size == 2 }
        val sellCharge = SandboxCosts.charge("SELL", BigDecimal("109.94"), 75).toDouble()
        val gross = (109.94 - 100.05) * 75
        assertEquals(741.75, gross, 1e-9)
        assertEquals("the closed row shows the day's realised P&L", gross, s.positions.positions.single().todayRealizedPnl, 0.001)
        assertEquals(sellCharge, s.trades.first { it.action == "SELL" }.charges, 0.001)
        assertEquals("realised in funds is net of both legs' charges", gross - buyCharge - sellCharge, s.funds.todayRealizedPnl, 0.01)
        assertEquals("cash: start + realised - charges; no margin left blocked", start + gross - buyCharge - sellCharge, s.funds.availableCash, 0.01)
        assertEquals(0.0, s.funds.utilisedDebits, 0.001)
        compose.waitUntil(10_000) { exists("NRML · CLOSED 0") }
        assertTrue(exists(rs(s.positions.totalTodayRealizedPnl, true)))
        assertEquals("one day figure everywhere: the positions less today's charges = the funds' today", s.funds.todayRealizedPnl, s.dayPnl, 0.01)

        // Orders: two complete, nothing open.
        tap("Orders")
        compose.waitUntil(5_000) { exists("2 · 0 · 0 · 0") }
        assertTrue(exists("1 / 1"))
        assertTrue(exists("BUY ${sym(24_500.0)} ×75") && exists("SELL ${sym(24_500.0)} ×75"))
        assertEquals(2, compose.onAllNodesWithText("COMPLETE", substring = true).fetchSemanticsNodes().size)
        assertEquals("finished orders have no Modify / Cancel", 0, compose.onAllNodesWithText("Modify").fetchSemanticsNodes().size)
        // Trades: both legs, at their fill prices.
        tap("Trades")
        compose.waitUntil(5_000) { exists("75 @ 100.05") && exists("75 @ 109.94") }
        // Funds: the same figures as the account.
        tap("Funds")
        compose.waitUntil(5_000) { exists("Realised, all time") }
        assertTrue(exists(rs(s.funds.todayRealizedPnl, true)))
        assertTrue(exists(rs(s.funds.availableCash)))
        assertEquals("the order sources say it was placed by hand", 2, s.orders.orders.size)
    }

    @Test fun theRowPopupClosesAPositionWithItsSlideAction() {
        val m = show()
        buyMarket(m)
        compose.waitUntil(10_000) { exists(sym(24_500.0)) }
        compose.onAllNodesWithText(sym(24_500.0))[0].performClick()
        compose.waitUntil(5_000) { m.rowAction.value is RowTarget.PaperPosition }
        inDialog("Slide to close position  ›››").assertExists()
        assertTrue(compose.onAllNodes(hasText("Paper") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty())
        // TalkBack cannot drag: the slider exposes the same confirmation as its click action.
        compose.onNode(hasText("Slide to close position", substring = true) and hasAnyAncestor(isDialog()))
            .performSemanticsAction(SemanticsActions.OnClick)
        waitSnap(m, "the close") { s -> s.positions.positions.all { it.quantity == 0 } }
        compose.waitUntil(5_000) { m.rowAction.value == null }
    }

    @Test fun theRowPopupShowsThePositionAsPricedNowNotAsTapped() {
        val m = show()
        buyMarket(m)
        // The price moves after the row was drawn: the popup re-reads the account and shows the new LTP and P&L.
        upstox.price(TradeFixtures.key("NIFTY", near, 24_500.0, "PE"), 130.0)
        compose.onAllNodesWithText(sym(24_500.0))[0].performClick()
        compose.waitUntil(5_000) { m.rowAction.value is RowTarget.PaperPosition }
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasText("130.00") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        }
        val pos = m.snap()!!.positions.positions.single { it.symbol == sym(24_500.0) }
        assertTrue(compose.onAllNodes(hasText(rs(pos.unrealizedPnl, true)) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun theRowPopupClosesWithoutActingWhenDismissed() {
        val m = show()
        buyMarket(m)
        compose.onAllNodesWithText(sym(24_500.0))[0].performClick()
        compose.waitUntil(5_000) { m.rowAction.value != null }
        inDialog("Close").performClick()
        compose.waitUntil(5_000) { m.rowAction.value == null }
        assertEquals(75, m.snap()!!.positions.positions.single().quantity)
    }

    // ---- order types ----------------------------------------------------------------------

    @Test fun theFormShowsOnlyTheFieldsEachOrderTypeUses() {
        show()
        openForm()
        assertTrue("MARKET: no price, no trigger", !exists("Price") && !exists("Trigger"))
        tap("LIMIT"); compose.onNodeWithText("LIMIT").assertIsSelected()
        field("Price").assertExists(); assertTrue(!exists("Trigger"))
        tap("SL")
        field("Price").assertExists(); field("Trigger").assertExists()
        tap("SL-M")
        field("Trigger").assertExists(); assertTrue(!exists("Price"))
        // Only digits and a point get into a price box.
        field("Trigger").performTextInput("1a0,5.5x")
        compose.waitUntil(2_000) { exists("105.5") }
    }

    @Test fun aLimitAwayFromTheMarketRestsThenModifyThenCancelReleasesItsMargin() {
        val m = show()
        val cash0 = m.snap()!!.funds.availableCash
        openForm(); tap("BUY"); tap("LIMIT")
        field("Price").performTextInput("90")
        waitPlaceable(); placeButton().performClick()
        var s = waitSnap(m, "the resting limit") { x -> x.orders.orders.any { it.status == "open" } }
        val o = s.orders.orders.single()
        assertEquals(90.0, o.price, 1e-9); assertEquals(75, o.quantity); assertEquals("LIMIT", o.priceType)
        assertTrue("nothing filled", s.trades.isEmpty() && s.positions.positions.none { it.quantity != 0 })
        assertTrue("the resting order blocks margin", s.funds.availableCash < cash0)

        closeForm(); tap("Orders")
        compose.waitUntil(5_000) { exists("OPEN") && exists("Modify") }
        assertTrue(exists("0 · 1 · 0 · 0"))

        // Modify: the dialog starts from the order; a new price goes through.
        paused {
            tap("Modify"); frames()
            inDialog("Modify paper order").assertExists()
            compose.onNode(hasSetTextAction() and hasText("75") and hasAnyAncestor(isDialog())).assertExists()
            compose.onNode(hasSetTextAction() and hasText("90.00") and hasAnyAncestor(isDialog())).assertExists()
            // Addressed by its label: once cleared, the box no longer holds "90.00".
            val price = compose.onNode(hasSetTextAction() and hasText("Price") and hasAnyAncestor(isDialog()))
            price.performTextClearance(); frames(); price.performTextInput("95"); frames()
            inDialog("Modify").performClick(); frames()
        }
        s = waitSnap(m, "the modified price") { x -> x.orders.orders.single().price == 95.0 }
        assertEquals("open", s.orders.orders.single().status)
        waitMessage(m, "Order modified")

        // A quantity that is not whole lots is refused and changes nothing.
        paused {
            tap("Modify"); frames()
            compose.onNode(hasSetTextAction() and hasText("75") and hasAnyAncestor(isDialog())).assertExists()
            val qty = compose.onNode(hasSetTextAction() and hasText("Quantity (units)") and hasAnyAncestor(isDialog()))
            qty.performTextClearance(); frames(); qty.performTextInput("100"); frames()
            inDialog("Modify").performClick(); frames()
        }
        waitMessage(m, "multiples of lot size 75")
        assertEquals(75, m.snap()!!.orders.orders.single().quantity)

        // Keep / Close in the dialog changes nothing.
        paused {
            tap("Modify"); frames(); inDialog("Close").performClick(); frames()
            pump(2_000) { !exists("Modify paper order") }
        }

        // Cancel: the order goes and its margin comes back.
        tap("Cancel")
        s = waitSnap(m, "the cancel") { x -> x.orders.orders.single().status == "cancelled" }
        assertEquals(cash0, s.funds.availableCash, 0.01)
        compose.waitUntil(5_000) { exists("CANCELLED") }
        assertEquals(0, compose.onAllNodesWithText("Modify").fetchSemanticsNodes().size)
    }

    @Test fun aSlmBuyWaitsForItsTriggerThenFillsWithStopSlippage() {
        val m = show()
        openForm(); tap("BUY"); tap("SL-M")
        field("Trigger").performTextInput("105")
        waitPlaceable(); placeButton().performClick()
        var s = waitSnap(m, "trigger pending") { x -> x.orders.orders.any { it.status == "trigger pending" } }
        assertEquals(105.0, s.orders.orders.single().triggerPrice, 1e-9)
        assertTrue(s.positions.positions.none { it.quantity != 0 })

        // The market touches the trigger: the next tick fills at the LTP less nothing, plus 10 bps against.
        upstox.price(TradeFixtures.key("NIFTY", near, 24_500.0, "PE"), 106.0)
        m.loadPaper(quiet = true)
        s = waitSnap(m, "the stop to fill") { x -> x.orders.orders.single().status == "complete" }
        assertEquals("106 + 10 bps = 106.106 -> 106.11", 106.11, s.orders.orders.single().averagePrice, 1e-9)
        assertEquals(75, s.positions.positions.single().quantity)
    }

    @Test fun anSlBuyFillsInsideItsLimitOnceTriggered() {
        val m = show()
        openForm(); tap("BUY"); tap("SL")
        field("Price").performTextInput("107")
        field("Trigger").performTextInput("105")
        waitPlaceable(); placeButton().performClick()
        waitSnap(m, "trigger pending") { x -> x.orders.orders.any { it.status == "trigger pending" } }
        upstox.price(TradeFixtures.key("NIFTY", near, 24_500.0, "PE"), 106.0)
        m.loadPaper(quiet = true)
        val s = waitSnap(m, "the stop-limit to fill") { x -> x.orders.orders.single().status == "complete" }
        val px = s.orders.orders.single().averagePrice
        assertEquals(106.11, px, 1e-9)
        assertTrue("never above its limit", px <= 107.0)
    }

    @Test fun aMarketableLimitFillsAtOnce() {
        val m = show()
        openForm(); tap("BUY"); tap("LIMIT")
        field("Price").performTextInput("101")
        waitPlaceable(); placeButton().performClick()
        val s = waitSnap(m, "the fill") { x -> x.orders.orders.any { it.status == "complete" } }
        // A marketable LIMIT fills at the LTP (price improvement), with no slippage.
        assertEquals(100.0, s.orders.orders.single().averagePrice, 1e-9)
    }

    @Test fun aLimitWithNoPriceIsRefusedAndNothingIsPlaced() {
        val m = show()
        openForm(); tap("BUY")
        waitPlaceable()                                   // MARKET: ready, so only the missing price holds it back below
        tap("LIMIT"); compose.waitForIdle()
        // The button stays off with no price, and with a price of 0, as the chain's order sheet does.
        placeButton().assertIsNotEnabled()
        field("Price").performTextInput("0"); compose.waitForIdle()
        placeButton().assertIsNotEnabled()
        // An SL also needs its trigger.
        field("Price").performTextClearance(); field("Price").performTextInput("101")
        tap("SL"); compose.waitForIdle()
        placeButton().assertIsNotEnabled()
        field("Trigger").performTextInput("99"); compose.waitForIdle()
        placeButton().assertIsEnabled()
        m.loadPaper(quiet = true)
        compose.waitUntil(10_000) { m.snap() != null }
        assertTrue("nothing placed: ${m.snap()!!.orders.orders}", m.snap()!!.orders.orders.none { it.status != "rejected" })
    }

    // ---- guards -------------------------------------------------------------------------------

    @Test fun theKillSwitchNeverStopsAPaperOrderOfYourOwn() {
        val m = show()
        buyMarket(m)
        TradeFixtures.killSwitch(true)
        // Paper is practice: the kill switch guards Zerodha (and halts the bots), not the owner's paper orders.
        openForm(); tap("BUY"); tap("CE")
        waitPlaceable(); placeButton().performClick()
        waitSnap(m, "the second order under the kill switch") { s -> s.orders.orders.size == 2 }
        assertTrue(m.message.value?.contains("account guard") != true)
        // Closing goes through too.
        closeForm()
        compose.onAllNodesWithText("Close")[0].performClick()   // one of the two positions (each row has its Close)
        waitSnap(m, "the close under the kill switch") { s -> s.orders.orders.size == 3 }
    }

    @Test fun anUncoveredPaperSellGoesThrough() {
        val m = show()
        openForm()   // SELL is the default side
        waitPlaceable(); placeButton().performClick()
        val s = waitSnap(m, "the short") { x -> x.positions.positions.any { it.quantity == -75 } }
        assertEquals(1, s.orders.orders.size)
    }

    @Test fun paperLotsAreNotCappedByTheZerodhaLimits() {
        val m = show()
        openForm(); tap("BUY")
        tap("3"); compose.onNodeWithText("3").assertIsSelected()
        waitPlaceable(); placeButton().performClick()
        val s = waitSnap(m, "three lots") { x -> x.positions.positions.any { it.quantity == 225 } }
        assertEquals(225, s.orders.orders.single().quantity)
    }

    @Test fun aDoubleTapPlacesOneOrder() {
        val m = show()
        openForm(); tap("BUY")
        waitPlaceable()
        // Two taps inside one frame, as a fast double tap delivers them.
        val click = placeButton().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread { click(); click() }
        // And a third tap while the first is in flight.
        runCatching { placeButton().performClick() }
        waitSnap(m, "the fill") { s -> s.orders.orders.isNotEmpty() }
        Thread.sleep(1_500)
        m.loadPaper(quiet = true)
        compose.waitUntil(10_000) { m.paper.value is Load.Done<*> }
        assertEquals("one tap, one order", 1, m.snap()!!.orders.orders.size)
        assertEquals(75, m.snap()!!.positions.positions.single().quantity)
    }

    @Test fun thePlaceButtonIsBusyUntilTheBookReloads() {
        val m = show()
        openForm(); tap("BUY"); waitPlaceable()
        placeButton().performClick()
        waitSnap(m, "the fill") { s -> s.orders.orders.size == 1 }
        // Re-enabled once the paper book has reloaded after the order.
        waitPlaceable()
    }

    // ---- form choices -------------------------------------------------------------------------

    @Test fun switchingTheIndexReloadsItsExpiriesStrikesAndLot() {
        val m = show()
        openForm()
        tap("BANKNIFTY")
        compose.onNodeWithText("BANKNIFTY").assertIsSelected()
        compose.waitUntil(20_000) { exists("52000") }
        assertTrue("the far NIFTY expiry is not listed for BANKNIFTY", !exists(TradeFixtures.farExpiry.toString().substring(5)))
        assertTrue(exists("Strike · BANKNIFTY 52,040"))
        tap("BUY")
        waitPlaceable(); placeButton().performClick()
        val s = waitSnap(m, "the BANKNIFTY fill") { x -> x.positions.positions.any { it.quantity != 0 } }
        assertEquals("one BANKNIFTY lot is 35", 35, s.positions.positions.single().quantity)
        assertTrue(s.positions.positions.single().symbol.startsWith("BANKNIFTY"))
    }

    @Test fun theOtherExpiryAndCallCanBeChosen() {
        val m = show()
        openForm()
        tap(TradeFixtures.farExpiry.toString().substring(5))
        compose.onNodeWithText(TradeFixtures.farExpiry.toString().substring(5)).assertIsSelected()
        tap("CE"); compose.onNodeWithText("CE").assertIsSelected(); compose.onNodeWithText("PE").assertIsNotSelected()
        tap("BUY")
        compose.waitUntil(20_000) { exists("24500") }
        waitPlaceable(); placeButton().performClick()
        val s = waitSnap(m, "the far CE") { x -> x.positions.positions.any { it.quantity != 0 } }
        assertEquals(sym(24_500.0, "CE", e = TradeFixtures.farExpiry), s.positions.positions.single().symbol)
    }

    @Test fun theStrikeListHasOnlyListedStrikesMarksAtmAndPicks() {
        val m = show()
        openForm()
        compose.onNodeWithText("24500").performClick()
        compose.waitUntil(5_000) { exists("24500   ATM") }
        TradeFixtures.niftyStrikes.filter { it != 24_500.0 }.forEach { assertTrue("$it listed", exists("${it.toInt()}")) }
        assertTrue("no strike that is not listed", !exists("24550"))
        compose.onNodeWithText("24400").performClick()
        compose.waitUntil(5_000) { !exists("24500   ATM") && exists("24400") }
        tap("BUY")
        waitPlaceable(); placeButton().performClick()
        val s = waitSnap(m, "the 24400 fill") { x -> x.positions.positions.any { it.quantity != 0 } }
        assertEquals(sym(24_400.0), s.positions.positions.single().symbol)
    }

    @Test fun theProductChoiceToggles() {
        show()
        openForm()
        tap("MIS"); compose.onNodeWithText("MIS").assertIsSelected(); compose.onNodeWithText("NRML").assertIsNotSelected()
        tap("NRML"); compose.onNodeWithText("NRML").assertIsSelected()
    }

    @Test fun closingTheFormKeepsTheDraftForNextTime() {
        show()
        openForm()
        tap("BUY"); tap("LIMIT"); field("Price").performTextInput("88.5")
        closeForm()
        assertTrue(!exists("Place paper order"))
        tap("New paper order")
        compose.waitUntil(5_000) { exists("88.5") }
        compose.onNodeWithText("BUY").assertIsSelected()
        compose.onNodeWithText("LIMIT").assertIsSelected()
    }

    @Test fun nothingCanBePlacedUntilTheStrikesAreIn() {
        // Today's contract list is read but lists nothing (as while the master is still loading).
        java.io.File(app.filesDir, "contracts.json").writeText(
            org.json.JSONObject().put("day", com.optionslab.app.data.Market.today().toString()).put("c", org.json.JSONArray()).toString())
        val m = show()
        tap("New paper order")
        compose.waitUntil(5_000) { exists("Place paper order") }
        assertTrue(exists("Loading the listed expiries from Upstox…"))
        placeButton().assertIsNotEnabled()
        assertTrue(m.snap()!!.orders.orders.isEmpty())
    }

    // ---- empty / unpriced / books ----------------------------------------------------------------

    @Test fun emptyBooksSayTheyAreEmpty() {
        show()
        compose.waitUntil(5_000) { exists("No paper positions.") }
        tap("Orders"); compose.waitUntil(5_000) { exists("No paper orders this session.") }
        compose.onNodeWithText("Orders").assertIsSelected()
        tap("Trades"); compose.waitUntil(5_000) { exists("No paper trades this session.") }
        tap("Funds"); compose.waitUntil(5_000) { exists("Set paper amount / reset") }
        tap("Positions"); compose.waitUntil(5_000) { exists("No paper positions.") }
        assertTrue("the header says nothing reaches a broker", exists("SANDBOX · PAPER ACCOUNT") && exists("NO BROKER"))
        assertTrue(exists("The paper account · sandbox"))
    }

    @Test fun withoutFreshPricesTheBookSaysSoAndKeepsTheLastMark() {
        val m = show()
        buyMarket(m)
        upstox.down = true
        m.loadPaper(quiet = true)
        waitSnap(m, "an unpriced book", timeoutMs = 90_000) { s -> !s.priced }
        compose.waitUntil(20_000) { exists("No fresh prices from Upstox just now; resting orders wait and positions show their last mark.") }
        assertEquals(75, m.snap()!!.positions.positions.single().quantity)
    }

    // ---- set paper amount ------------------------------------------------------------------------

    @Test fun thePaperAmountDialogValidatesItsBoundsAndResets() {
        val m = show()
        buyMarket(m)
        paused {
            tap("Set paper amount"); frames()
            inDialog("Set paper amount").assertExists()
            inDialog("Start with ${rs(1_000_000.0)}").assertIsEnabled()     // the 10 lakh preset is picked
            inDialog(rs(500_000.0)).performClick().also { frames() }
            inDialog(rs(500_000.0)).assertIsSelected()
            inDialog("Start with ${rs(500_000.0)}").assertIsEnabled()
            val box = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
            box.performTextInput("9999").also { frames() }
            inDialog("Enter between Rs 10,000 and Rs 100,00,00,000.").assertExists()
            inDialog("Start with …").assertIsNotEnabled()
            box.performTextClearance().also { frames() }; box.performTextInput("1000000001").also { frames() }
            inDialog("Start with …").assertIsNotEnabled()
            box.performTextClearance().also { frames() }; box.performTextInput("1000000000").also { frames() }
            inDialog("Start with ${rs(1_000_000_000.0)}").assertIsEnabled()
            box.performTextClearance().also { frames() }; box.performTextInput("12x3y45").also { frames() }        // digits only
            pump(2_000) { exists("12345") }
            inDialog("Start with ${rs(12_345.0)}").assertIsEnabled()
            inDialog(rs(500_000.0)).assertIsNotSelected()     // a typed amount replaces the preset
            box.performTextClearance().also { frames() }; box.performTextInput("10000").also { frames() }
            inDialog("Start with ${rs(10_000.0)}").performClick().also { frames() }
            pump(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        }
        val s = waitSnap(m, "the reset account") { x -> x.funds.availableCash == 10_000.0 }
        assertTrue("every paper position is cleared", s.positions.positions.none { it.quantity != 0 })
        assertEquals(0, s.orders.orders.size)
        assertEquals(BigDecimal("10000.00"), Paper.capital)
        assertEquals("Paper account reset to ${rs(10_000.0)}.", waitMessage(m, "reset to"))
    }

    @Test fun keepLeavesThePaperAccountAlone() {
        val m = show()
        buyMarket(m)
        paused {
            tap("Set paper amount"); frames()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("50000"); frames()
            inDialog("Keep").performClick(); frames()
            pump(5_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        }
        assertEquals(0, BigDecimal("10000000").compareTo(Paper.capital))
        assertEquals(75, m.snap()!!.positions.positions.single().quantity)
    }
}
