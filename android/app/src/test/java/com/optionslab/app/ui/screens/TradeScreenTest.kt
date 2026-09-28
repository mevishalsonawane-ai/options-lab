package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.security.PinLock
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.ModelHolder
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.OrderPlan
import com.optionslab.app.ui.rs
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.Kite
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/**
 * The Trade tab in LIVE mode ([TradeScreen], [TradeHub], the row popup) on a real [AppModel] against
 * [FakeKite]: every book, square-off and GTT, modify and cancel with their confirmation and PIN gates,
 * and the error / logged-out states. Nothing leaves the machine; anything that would change something
 * at Zerodha is checked in [FakeKite.writes] (only after the PIN).
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(qualifiers = "w411dp-h2400dp")
class TradeScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)
    @get:Rule val watchdog = com.optionslab.app.testing.TradeWatchdog()

    private lateinit var kite: FakeKite
    private lateinit var holder: ModelHolder
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val sym = "NIFTY26OCT24500PE"
    private val sym2 = "NIFTY26OCT24400CE"
    private val expiry: LocalDate = LocalDate.now().plusDays(9)
    private val pin = "246813"

    @Before fun up() {
        kite = FakeKite()
        SecurePrefs.put("g.cutoff", -1)
        kite.instruments += FakeKite.Ins(12_345_678, sym, "NIFTY", expiry, 24_500.0, "PE", 75)
        kite.instruments += FakeKite.Ins(12_345_679, sym2, "NIFTY", expiry, 24_400.0, "CE", 75)
        kite.quote("NFO:$sym", 110.0, 109.95, 110.05)
        kite.quote("NFO:$sym2", 50.0, 49.95, 50.05)
        holder = ModelHolder(app)
    }

    @After fun down() {
        holder.clear()
        kite.close()
        assertFalse("Kite REST calls must all go to the fake", "api.kite.trade" in NetworkGuard.blocked)
    }

    /** Logged in to the fake, with an API key and a (made-up) secret saved, so the page counts as set up. */
    private fun loginConfigured() {
        kite.login()
        SecurePrefs.put("kite.apiSecret", "testsecretnotreal")
    }

    private fun show(page: String = "account"): AppModel {
        val m = holder.model
        var p by mutableStateOf(page)
        compose.setContent { IraAlgoTheme("light") { Box(Modifier.fillMaxSize()) { TradeHub(m, p) { p = it }; RowActionPopup(m) } } }
        compose.waitForIdle()
        // The clock keeps running: text-field dialogs settle since the app's AlertDialog has one fixed width.
        frames()
        return m
    }

    private fun showAccount(): AppModel {
        val m = show()
        pump(20_000) { m.account.value is Load.Done<*> }
        return m
    }

    private fun frames(n: Int = 12) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    /** Waits (real time) for [cond], moving the paused clock a couple of frames per look. */
    private fun pump(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            frames(2)
            if (runCatching(cond).getOrDefault(false)) return
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in $timeoutMs ms")
            Thread.sleep(20)
        }
    }

    private fun exists(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun waitText(text: String, substring: Boolean = false, timeoutMs: Long = 20_000) = try {
        pump(timeoutMs) { exists(text, substring) }
    } catch (e: Throwable) { throw AssertionError("'$text' never appeared", e) }

    private fun tap(text: String) = compose.onNodeWithText(text).performClick().also { frames() }
    private fun tab(name: String) = compose.onNode(isSelectable() and hasText(name, substring = true)).performClick().also { frames() }
    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun waitMessage(m: AppModel, part: String): String {
        try { pump(20_000) { m.message.value?.contains(part) == true } }
        catch (e: Throwable) { throw AssertionError("expected a message with '$part'; last: ${m.message.value}", e) }
        return m.message.value!!
    }

    private fun waitPlan(m: AppModel): OrderPlan {
        pump(20_000) { m.plan.value is Load.Done<*> || m.plan.value is Load.Failed }
        return (m.plan.value as? Load.Done<OrderPlan>)?.value ?: throw AssertionError("no plan: ${m.plan.value}")
    }

    /** The PIN dialog every change at Zerodha goes through. */
    private fun enterPin(value: String = pin) {
        waitText("Confirm it is you")
        compose.onNode(hasSetTextAction() and hasText("PIN") and hasAnyAncestor(isDialog())).performTextInput(value).also { frames() }
        inDialog("Confirm").performClick().also { frames() }
    }

    private fun workingOrder(id: String = "250926000000901", type: String = "LIMIT", price: Double = 90.0, trigger: Double = 0.0) {
        kite.orders[id] = FakeKite.Order(id, sym, "NFO", "BUY", 75, "NRML", type, price, trigger, "iraalgo", if (type.startsWith("SL")) "TRIGGER PENDING" else "OPEN")
    }

    // ---- set-up and session states --------------------------------------------------------------

    @Test fun notSetUpSaysWhereToAddTheKeys() {
        SecurePrefs.putAll(mapOf("k.mode" to "live", "k.allow" to true))
        show()
        waitText("Set up your Kite API key and secret first (More → Zerodha).")
        assertTrue(!exists("Log in to Zerodha"))
        assertTrue(exists("Your Zerodha account, live"))
        assertTrue("nothing is asked of Zerodha", kite.requests.isEmpty())
    }

    @Test fun loggedOutOffersTheLogin() {
        SecurePrefs.putAll(mapOf("k.mode" to "live", "k.allow" to true, "kite.apiKey" to "testkey", "kite.apiSecret" to "testsecretnotreal"))
        val m = show()
        waitText("Log in to Zerodha for today to see your account.")
        tap("Log in to Zerodha")
        pump(5_000) { m.askLoginPin.value }
        assertTrue(kite.requests.isEmpty())
    }

    @Test fun theHubSwitchesBetweenAccountAndStrategies() {
        SecurePrefs.putAll(mapOf("k.mode" to "live", "k.allow" to true))
        show()
        compose.onNode(isSelectable() and hasText("Account")).assertIsSelected()
        tab("Strategies")
        compose.onNode(isSelectable() and hasText("Strategies")).assertIsSelected()
        compose.waitForIdle()
        assertTrue("the account page is gone", !exists("Set up your Kite API key and secret first (More → Zerodha)."))
        tab("Account")
        waitText("Set up your Kite API key and secret first (More → Zerodha).")
    }

    @Test fun anUnreadableAccountSaysSoAndTryAgainReads() {
        loginConfigured()
        kite.down += "/portfolio/positions"
        val m = show()
        pump(20_000) { m.account.value is Load.Failed }
        waitText("Try again")
        kite.down.clear()
        tap("Try again")
        pump(20_000) { m.account.value is Load.Done<*> }
        assertTrue(exists("Nothing open."))
    }

    @Test fun anEndedSessionShowsTheLoginAgain() {
        loginConfigured()
        kite.sessionExpired = true
        val m = show()
        pump(20_000) { m.account.value is Load.Failed }
        assertTrue((m.account.value as Load.Failed).why.contains("session ended"))
        waitText("Log in to Zerodha for today to see your account.")
    }

    // ---- positions ------------------------------------------------------------------------------------

    @Test fun anOpenPositionShowsItsFiguresAndSquareOffOnlyReviews() {
        loginConfigured()
        kite.position(sym, 75, 100.0)
        val m = showAccount()
        waitText(sym)
        assertTrue(exists("NFO · NRML · LONG 75"))
        assertTrue(exists("avg 100.00 → ltp 110.00"))
        assertTrue("P&L = (110 - 100) x 75", exists(rs(750.0, true)))
        compose.onNode(isSelectable() and hasText("Positions 1")).assertIsSelected()
        assertTrue("one position: no square-off-all", !exists("Square off all", substring = true))
        tap("Square off")
        val plan = waitPlan(m)
        assertTrue(plan.exit)
        val leg = plan.legs.single()
        assertEquals(sym, leg.tradingSymbol); assertEquals(Kite.Side.SELL, leg.side); assertEquals(75, leg.quantity)
        assertTrue("a square-off review sends nothing: ${kite.writes}", kite.writes.isEmpty())
    }

    @Test fun squareOffAllBuysBackShortsFirst() {
        loginConfigured()
        kite.position(sym, 75, 100.0)
        kite.position(sym2, -75, 60.0)
        val m = showAccount()
        waitText("Square off all 2")
        assertTrue(exists("NFO · NRML · SHORT 75"))
        tap("Square off all 2")
        val plan = waitPlan(m)
        assertEquals(listOf(sym2 to Kite.Side.BUY, sym to Kite.Side.SELL), plan.legs.map { it.tradingSymbol to it.side })
        assertTrue(kite.writes.isEmpty())
    }

    @Test fun theRowPopupShowsTheLivePositionAndItsSlideOnlyReviews() {
        loginConfigured()
        kite.position(sym, 75, 100.0)
        val m = showAccount()
        waitText(sym)
        compose.onAllNodesWithText(sym)[0].performClick().also { frames() }
        pump(5_000) { m.rowAction.value is RowTarget.LivePosition }
        inDialog("Zerodha (live)").assertExists()
        inDialog("LONG 75").assertExists()
        compose.onNode(hasText("Slide to close position", substring = true) and hasAnyAncestor(isDialog()))
            .performSemanticsAction(SemanticsActions.OnClick).also { frames() }
        val plan = waitPlan(m)
        assertEquals(Kite.Side.SELL, plan.legs.single().side)
        pump(5_000) { m.rowAction.value == null }
        assertTrue(kite.writes.isEmpty())
    }

    @Test fun noPositionsSaysNothingOpen() {
        loginConfigured()
        showAccount()
        waitText("Nothing open.")
        assertTrue(!exists("Square off"))
    }

    // ---- orders: cancel and modify go through the PIN ---------------------------------------------

    @Test fun cancelAsksThenNeedsThePinThenSendsExactlyOneCancel() {
        loginConfigured()
        PinLock.setPin(pin.toCharArray())
        workingOrder()
        val m = showAccount()
        tab("Orders")
        waitText("WORKING")
        assertTrue(exists("BUY $sym ×75"))
        assertTrue(exists("OPEN · filled 0"))
        assertTrue(exists("Manual"))
        // Keep: nothing happens.
        tap("Cancel")
        inDialog("Cancel this order?").assertExists()
        inDialog("BUY $sym ×75 (LIMIT @ 90.00). Filled so far: 0.").assertExists()
        inDialog("Keep").performClick().also { frames() }
        pump(5_000) { !exists("Cancel this order?") }
        // Cancel order -> PIN; cancelling the PIN sends nothing.
        tap("Cancel")
        inDialog("Cancel order").performClick().also { frames() }
        waitText("Confirm it is you")
        inDialog("Cancel").performClick().also { frames() }
        pump(5_000) { !exists("Confirm it is you") }
        assertTrue(kite.writes.isEmpty())
        // A wrong PIN sends nothing either.
        tap("Cancel"); inDialog("Cancel order").performClick().also { frames() }
        enterPin("111111")
        waitText("Not the right PIN.")
        assertTrue(kite.writes.isEmpty())
        inDialog("Cancel").performClick().also { frames() }
        // The right PIN: one DELETE for that order.
        tap("Cancel"); inDialog("Cancel order").performClick().also { frames() }
        enterPin()
        assertTrue(waitMessage(m, "Cancel requested").contains("000901"))
        assertEquals(listOf("DELETE /orders/regular/250926000000901"), kite.writes.map { "${it.method} ${it.path}" })
        waitText("CANCELLED · filled 0")
    }

    @Test fun modifyShowsTheFieldsOfEachTypeAndSendsTheChangeAfterThePin() {
        loginConfigured()
        PinLock.setPin(pin.toCharArray())
        workingOrder()
        val m = showAccount()
        tab("Orders"); waitText("WORKING")
        tap("Modify")
        inDialog("Modify $sym").assertExists()
        inDialog("BUY · NRML · filled 0 of 75").assertExists()
        compose.onNode(isSelectable() and hasText("LIMIT") and hasAnyAncestor(isDialog())).assertIsSelected()
        val price = compose.onNode(hasSetTextAction() and hasText("Price") and hasAnyAncestor(isDialog()))
        price.assertExists()
        assertTrue(!exists("Trigger"))
        compose.onNode(isSelectable() and hasText("SL-M") and hasAnyAncestor(isDialog())).performClick().also { frames() }
        assertTrue(!exists("Price")); compose.onNode(hasSetTextAction() and hasText("Trigger")).assertExists()
        compose.onNode(isSelectable() and hasText("MARKET") and hasAnyAncestor(isDialog())).performClick().also { frames() }
        assertTrue(!exists("Price") && !exists("Trigger"))
        compose.onNode(isSelectable() and hasText("SL") and hasAnyAncestor(isDialog())).performClick().also { frames() }
        compose.onNode(hasSetTextAction() and hasText("Price")).assertExists(); compose.onNode(hasSetTextAction() and hasText("Trigger")).assertExists()
        // Close: nothing sent.
        inDialog("Close").performClick().also { frames() }
        pump(5_000) { !exists("Modify $sym") }
        assertTrue(kite.writes.isEmpty())

        // LIMIT 90 -> 95, after the PIN.
        tap("Modify")
        compose.onNode(hasSetTextAction() and hasText("90.00") and hasAnyAncestor(isDialog())).assertExists()
        // Addressed by its label: once cleared, the box no longer holds "90.00".
        val box = compose.onNode(hasSetTextAction() and hasText("Price") and hasAnyAncestor(isDialog()))
        box.performTextClearance().also { frames() }; box.performTextInput("95").also { frames() }
        inDialog("Confirm change").performClick().also { frames() }
        enterPin()
        waitMessage(m, "Modify sent")
        val put = kite.writes.single()
        assertEquals("PUT", put.method)
        assertEquals("95.0", put.form["price"]?.let { it.toDouble().toString() })
        assertEquals("75", put.form["quantity"])
        assertEquals(95.0, kite.order("250926000000901").price, 1e-9)
    }

    @Test fun aModifyThePinWasCancelledForSendsNothing() {
        loginConfigured()
        PinLock.setPin(pin.toCharArray())
        workingOrder()
        showAccount()
        tab("Orders"); waitText("WORKING")
        tap("Modify")
        inDialog("Confirm change").performClick().also { frames() }
        waitText("Confirm it is you")
        inDialog("Cancel").performClick().also { frames() }
        pump(5_000) { !exists("Confirm it is you") }
        assertTrue("the modify dialog stays for another go", exists("Modify $sym"))
        assertTrue(kite.writes.isEmpty())
    }

    @Test fun modifyWithAnEmptyLimitPriceCannotBeConfirmed() {
        loginConfigured()
        PinLock.setPin(pin.toCharArray())
        workingOrder()
        showAccount()
        tab("Orders"); waitText("WORKING")
        tap("Modify")
        compose.onNode(hasSetTextAction() and hasText("90.00") and hasAnyAncestor(isDialog())).performTextClearance().also { frames() }
        inDialog("Confirm change").assertIsNotEnabled()
        // A price again turns it back on.
        compose.onNode(hasSetTextAction() and hasText("Price") and hasAnyAncestor(isDialog())).performTextInput("91").also { frames() }
        inDialog("Confirm change").assertIsEnabled()
        assertTrue("nothing sent: ${kite.writes}", kite.writes.isEmpty())
    }

    @Test fun finishedOrdersHaveNoActionsAndTheRowOpensThePopup() {
        loginConfigured()
        kite.orders["250926000000902"] = FakeKite.Order("250926000000902", sym, "NFO", "SELL", 75, "NRML", "LIMIT", 120.0, 0.0,
            "iraalgostrat", "REJECTED", message = "RMS:Margin Exceeds")
        val m = showAccount()
        tab("Orders")
        waitText("FINISHED")
        assertTrue(!exists("Modify") && !exists("WORKING"))
        assertTrue(exists("REJECTED · filled 0 · RMS:Margin Exceeds"))
        assertTrue("an old strategy order is marked by its tag", exists("Strategy order"))
        compose.onAllNodesWithText("SELL $sym ×75")[0].performClick().also { frames() }
        pump(5_000) { m.rowAction.value is RowTarget.LiveOrder }
        inDialog("Nothing to close or cancel: this order is finished and no position is open from it.").assertExists()
        inDialog("Close").performClick().also { frames() }
        pump(5_000) { m.rowAction.value == null }
    }

    @Test fun emptyBooksSayTheyAreEmpty() {
        loginConfigured()
        showAccount()
        tab("Orders"); waitText("No orders today.")
        waitText("No GTTs.", substring = true)
        tab("Trades"); waitText("No trades today.")
        tab("Holdings"); waitText("No delivery holdings.")
        tab("Funds"); waitText("Today's P&L")
        assertTrue(exists("Funds · equity segment"))
        assertTrue(exists(rs(500_000.0)))
        assertTrue(exists("refreshes every", substring = true))
    }

    // ---- GTT ------------------------------------------------------------------------------------------

    @Test fun aGttIsCheckedThenPlacedAfterThePinThenDeleted() {
        loginConfigured()
        PinLock.setPin(pin.toCharArray())
        kite.position(sym, 75, 100.0)
        val m = showAccount()
        waitText("Protect (GTT)")
        tap("Protect (GTT)")
        inDialog("Protect $sym").assertExists()
        inDialog("Place GTT").assertIsNotEnabled()
        // A stop above the price is refused with the reason.
        val stop = compose.onNode(hasSetTextAction() and hasText("Stop-loss trigger (below the price)"))
        stop.performTextInput("115").also { frames() }
        inDialog("Check").performClick().also { frames() }
        pump(20_000) { m.gttPlan.value is Load.Done<*> }
        inDialog("Place GTT").assertIsNotEnabled()
        // A stop below: priced and placeable.
        stop.performTextClearance().also { frames() }; stop.performTextInput("95").also { frames() }
        assertEquals("typing again drops the old check", Load.Idle, m.gttPlan.value)
        inDialog("Check").performClick().also { frames() }
        pump(20_000) { (m.gttPlan.value as? Load.Done<AppModel.GttPlan>)?.value?.gtt != null }
        waitText("at 95.00 → SELL 75 LIMIT 90.25")
        assertTrue(kite.writes.isEmpty())
        inDialog("Place GTT").assertIsEnabled().performClick().also { frames() }
        enterPin()
        waitMessage(m, "GTT placed")
        assertEquals(1, kite.writes.count { it.method == "POST" && it.path == "/gtt/triggers" })
        pump(10_000) { m.gtts.value.isNotEmpty() }
        val id = m.gtts.value.single().id

        tab("Orders")
        waitText("$sym · single · active")
        tap("Delete")
        inDialog("Delete GTT #$id?").assertExists()
        inDialog("Keep").performClick().also { frames() }
        pump(5_000) { !exists("Delete GTT #$id?") }
        tap("Delete"); inDialog("Delete").performClick().also { frames() }
        enterPin()
        waitMessage(m, "GTT #$id deleted.")
        assertEquals(1, kite.writes.count { it.method == "DELETE" && it.path == "/gtt/triggers/$id" })
    }

    @Test fun closingTheGttDialogSendsNothing() {
        loginConfigured()
        kite.position(sym, 75, 100.0)
        val m = showAccount()
        waitText("Protect (GTT)"); tap("Protect (GTT)")
        compose.onNode(hasSetTextAction() and hasText("Stop-loss trigger (below the price)")).performTextInput("95").also { frames() }
        inDialog("Check").performClick().also { frames() }
        pump(20_000) { m.gttPlan.value is Load.Done<*> }
        inDialog("Close").performClick().also { frames() }
        pump(5_000) { !exists("Protect $sym") }
        assertEquals(Load.Idle, m.gttPlan.value)
        assertTrue(kite.writes.isEmpty())
    }
}
