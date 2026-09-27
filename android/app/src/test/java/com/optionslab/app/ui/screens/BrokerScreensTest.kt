package com.optionslab.app.ui.screens

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Broker
import com.optionslab.app.data.LiveSelfTest
import com.optionslab.app.data.Relay
import com.optionslab.app.data.StaticIp
import com.optionslab.app.security.PinLock
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.BrokerArea
import com.optionslab.app.testing.BrokerScreenBase
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.OrderPlan
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The Zerodha pages, driven as the owner drives them, over a real [AppModel] and the fake Kite
 * ([FakeKite], localhost): the setup guide and the keys form, the login PIN and Kite's login page, the
 * Zerodha page (connection, mode, real orders, erase), the static-IP card with its relay controls, the
 * live self-test, the hand-typed order, the hold-to-send control, the PIN re-check and the stuck-leg card.
 * The API secret is never on screen and never on the wire.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class BrokerScreensTest {
    @get:Rule val watchdog = com.optionslab.app.testing.Watchdog()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var kite: FakeKite
    private val store = androidx.lifecycle.ViewModelStore()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val e1 = LocalDate.now().plusDays(9)
    private val e2 = LocalDate.now().plusDays(16)

    @Before fun up() {
        kite = FakeKite()
        BrokerArea.clearAlerts()
        BrokerArea.phoneIp(null)
        SecurePrefs.put("g.cutoff", -1)
    }

    @After fun down() {
        store.clear()
        // Account reads already under way when the model is cleared still finish (they swallow the cancellation):
        // let them end against this fake before it closes, so none reaches the real host or the next test's fake.
        BrokerArea.settle(1_500)
        kite.close()
        BrokerArea.phoneIp(null)
        assertFalse("Kite REST calls must all go to the fake", "api.kite.trade" in NetworkGuard.blocked)
        assertFalse("the static-IP check must not reach the internet here", "api.ipify.org" in NetworkGuard.blocked)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun model(): AppModel = BrokerArea.model(app, store)

    /** Everything in one scrolling column, as on a page (so performScrollTo reaches every control). */
    private fun show(content: @Composable () -> Unit) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { content() } }
    }

    /** A screen that scrolls itself (it must not sit inside another vertical scroll). */
    private fun plain(content: @Composable () -> Unit) = compose.setContent { IraAlgoTheme("light") { content() } }

    private fun until(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) = try {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
            cond()
        }
    } catch (e: Throwable) { throw AssertionError("timed out waiting for $what", e) }

    private fun frames(n: Int = 12) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    /**
     * A PIN dialog (a dialog holding a text field) never reports idle under Robolectric while the clock runs
     * by itself: the clock is stopped for the rest of the test and moved by [frames] and [until].
     */
    private fun pausedShow(content: @Composable () -> Unit) = show(content)

    private fun shown(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun alerted(text: String) = until("the alert '$text'") { BrokerArea.alerted(text) }

    /** The node with [text], brought into view first (a lazy page scrolls to it; a column scrolls it into view). */
    private fun node(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        if (compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty())
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = substring))
        val n = compose.onNodeWithText(text, substring = substring)
        runCatching { n.performScrollTo() }
        return n
    }

    private fun click(text: String, substring: Boolean = false) { node(text, substring).performClick(); compose.waitForIdle() }

    private fun field(label: String): SemanticsNodeInteraction =
        compose.onNode(hasSetTextAction() and hasText(label)).also { runCatching { it.performScrollTo() } }

    private fun editable(label: String) = field(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** The strike field is read-only (chosen from a list), so it has no SetText action: found by its label alone. */
    private fun strike(label: String) = compose.onNode(hasText(label)).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** No visible text anywhere (dialogs included) carries the secret or the day's token. */
    private fun assertNoSecretShown() {
        val texts = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { r ->
            generateSequence(listOf(r)) { l -> l.flatMap { it.children }.ifEmpty { null } }.flatten()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { t -> t.text } }
        }
        for (s in listOf(BrokerArea.SECRET, "test-token-not-real")) assertTrue("'$s' is on screen", texts.none { it.contains(s) })
    }

    private fun clipboard(): String? =
        app.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    private fun lastLink(): String? = shadowOf(app).nextStartedActivity?.let { it.action + " " + (it.dataString ?: "") }

    private fun keysAndLogin(live: Boolean = true) { BrokerArea.saveKeys(); kite.login(live) }

    // ---- hold to send ----------------------------------------------------------------------------

    @Test fun holdToSendIgnoresATapAndOffersOneAccessibleAction() {
        var sent = 0
        show { HoldToSend("Hold to send to Zerodha", true) { sent++ } }
        compose.onNodeWithText("Hold to send to Zerodha").performClick()
        compose.waitForIdle()
        assertEquals("a tap is not a hold", 0, sent)
        // TalkBack cannot hold: the same action as a button, once per activation.
        compose.onNodeWithText("Hold to send to Zerodha").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, sent)
    }

    @Test fun holdToSendCompletesOnlyAfterAFullHold() {
        var sent = 0
        show { HoldToSend("Hold to send to Zerodha", true) { sent++ } }
        compose.mainClock.autoAdvance = false
        val button = compose.onNodeWithText("Hold to send to Zerodha")
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        button.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals("released at 0.7 s: nothing", 0, sent)
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_800)
        assertEquals("held 1.5 s: sent once, before the finger lifts", 1, sent)
        button.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, sent)
    }

    @Test fun aDisabledHoldToSendDoesNothing() {
        var sent = 0
        show { HoldToSend("Hold to send to Zerodha", false) { sent++ } }
        val button = compose.onNodeWithText("Hold to send to Zerodha").assertIsNotEnabled()
        compose.mainClock.autoAdvance = false
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(2_000)
        button.performTouchInput { up() }
        assertEquals(0, sent)
    }

    // ---- the PIN re-check before a real order ------------------------------------------------------

    @Ignore(com.optionslab.app.ui.OrderReviewLiveGateTest.PIN_DIALOG)
    @Test fun reauthWrongPinThenRightPin() {
        PinLock.setPin(BrokerArea.PIN.toCharArray())
        val m = model()
        var ok = 0; var cancelled = 0
        pausedShow { Reauth(m, onOk = { ok++ }, onCancel = { cancelled++ }) }
        assertTrue(shown("Confirm it is you"))
        assertTrue(shown("Enter your app PIN to send this order to Zerodha."))
        field("PIN").performTextInput(BrokerArea.WRONG_PIN)
        compose.onNodeWithText("Confirm").performClick()
        until("the refusal") { shown("Not the right PIN.") }
        assertEquals(0, ok)
        assertEquals(1, PinLock.failures())
        field("PIN").performTextInput(BrokerArea.PIN)
        compose.onNodeWithText("Confirm").performClick()
        until("the confirmation") { ok == 1 }
        assertEquals(0, cancelled)
        assertEquals("a right PIN clears the count", 0, PinLock.failures())
    }

    @Ignore(com.optionslab.app.ui.OrderReviewLiveGateTest.PIN_DIALOG)
    @Test fun reauthCancelAndACustomReason() {
        PinLock.setPin(BrokerArea.PIN.toCharArray())
        val m = model()
        var ok = 0; var cancelled = 0
        pausedShow { Reauth(m, onOk = { ok++ }, onCancel = { cancelled++ }, pinOnly = true, why = "Enter your app PIN to delete this GTT.") }
        assertTrue(shown("Enter your app PIN to delete this GTT."))
        compose.onNodeWithText("Cancel").performClick()
        frames()
        assertEquals(1, cancelled); assertEquals(0, ok)
    }

    @Ignore(com.optionslab.app.ui.OrderReviewLiveGateTest.PIN_DIALOG)
    @Test fun reauthLockedOut() {
        PinLock.setPin(BrokerArea.PIN.toCharArray())
        repeat(4) { PinLock.verify(BrokerArea.WRONG_PIN.toCharArray(), false) }
        val m = model()
        var ok = 0
        pausedShow { Reauth(m, onOk = { ok++ }, onCancel = {}) }
        field("PIN").performTextInput(BrokerArea.WRONG_PIN)
        compose.onNodeWithText("Confirm").performClick()
        until("the lockout") { shown("Locked for 30 s.") }
        field("PIN").performTextClearance()
        field("PIN").performTextInput(BrokerArea.PIN)
        compose.onNodeWithText("Confirm").performClick()
        until("the check") { shown("Confirm") }
        BrokerArea.settle(300)
        assertEquals("the right PIN does not pass while locked out", 0, ok)
    }

    // ---- the login PIN and Kite's login page ---------------------------------------------------------

    private fun loginPin(m: AppModel) = pausedShow {
        val ask by m.askLoginPin.collectAsState()
        if (ask) LoginPinDialog(m)
    }

    @Ignore(com.optionslab.app.ui.OrderReviewLiveGateTest.PIN_DIALOG)
    @Test fun theLoginPinOpensTheSecretForKitesPageOnly() {
        BrokerArea.saveKeys()
        val m = model()
        m.startKiteLogin()
        assertTrue(m.askLoginPin.value)
        loginPin(m)
        assertTrue(shown("Log in to Zerodha"))
        assertTrue(shown("Enter your app PIN. It unseals the API secret for this login only."))
        field("PIN").performTextInput(BrokerArea.PIN)
        compose.onNodeWithText("Continue").performClick()
        until("Kite's login page") { m.showKiteLogin.value }
        assertFalse(m.askLoginPin.value)
        assertNoSecretShown()
        assertTrue("nothing asked of Zerodha yet", kite.requests.isEmpty())
    }

    @Ignore(com.optionslab.app.ui.OrderReviewLiveGateTest.PIN_DIALOG)
    @Test fun aWrongLoginPinKeepsThePromptAndCancelCloses() {
        BrokerArea.saveKeys()
        val m = model()
        m.startKiteLogin()
        loginPin(m)
        field("PIN").performTextInput(BrokerArea.WRONG_PIN)
        compose.onNodeWithText("Continue").performClick()
        until("the refusal") { shown("Not the right PIN.") }
        assertFalse(m.showKiteLogin.value)
        assertTrue(m.askLoginPin.value)
        compose.onNodeWithText("Cancel").performClick()
        until("closed") { !m.askLoginPin.value }
        assertFalse(m.showKiteLogin.value)
    }

    @Ignore(com.optionslab.app.ui.OrderReviewLiveGateTest.PIN_DIALOG)
    @Test fun aLockedLoginPinSaysForHowLong() {
        BrokerArea.saveKeys()
        repeat(4) { PinLock.verify(BrokerArea.WRONG_PIN.toCharArray(), false) }
        val m = model()
        m.startKiteLogin()
        loginPin(m)
        field("PIN").performTextInput(BrokerArea.WRONG_PIN)
        compose.onNodeWithText("Continue").performClick()
        until("the lockout") { shown("Locked for 30 s.") }
        assertFalse(m.showKiteLogin.value)
    }

    @Ignore(com.optionslab.app.ui.OrderReviewLiveGateTest.PIN_DIALOG)
    @Test fun aFingerprintOnlySecretCannotBeOpenedWithThePin() {
        PinLock.setPin(BrokerArea.PIN.toCharArray())
        Broker.saveCredentials(BrokerArea.KEY, BrokerArea.SECRET, null, "bio-sealed-test-not-real")
        val m = model()
        m.startKiteLogin()
        loginPin(m)
        field("PIN").performTextInput(BrokerArea.PIN)
        compose.onNodeWithText("Continue").performClick()
        until("the reason") { shown("Your API secret was sealed with your fingerprint only: use the fingerprint, or set up the keys again.") }
        assertFalse(m.showKiteLogin.value)
    }

    @Test fun loginWithoutKeysAsksForThemFirst() {
        val m = model()
        m.startKiteLogin()
        assertFalse(m.askLoginPin.value)
        assertTrue(BrokerArea.alerted("Add your Kite API key and secret first."))
    }

    private fun kitePage(m: AppModel) = compose.setContent {
        IraAlgoTheme("light") {
            val open by m.showKiteLogin.collectAsState()
            if (open) KiteLoginPage(m)
        }
    }

    private fun webView(): WebView {
        fun find(v: View): WebView? = v as? WebView ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return find(compose.activity.window.decorView) ?: throw AssertionError("no WebView on the login page")
    }

    private fun navigate(url: String) {
        val w = webView()
        compose.runOnUiThread { shadowOf(w).webViewClient.onPageStarted(w, url, null) }
        compose.waitForIdle()
    }

    @Test fun kitesRedirectCompletesTheLoginAndTheSecretNeverLeaves() {
        BrokerArea.saveKeys()
        val m = model()
        m.loginWithSecret(BrokerArea.SECRET)
        kitePage(m)
        assertTrue(shown("ZERODHA LOGIN"))
        assertNoSecretShown()
        navigate("http://127.0.0.1/iraalgo?action=login&type=login&status=success&request_token=reqTok123")
        until("the page closed") { !m.showKiteLogin.value }
        until("the session") { Broker.loggedIn }
        val post = kite.requests.first { it.method == "POST" && it.path == "/session/token" }
        assertEquals(mapOf("api_key" to BrokerArea.KEY, "request_token" to "reqTok123",
            "checksum" to Kite.checksum(BrokerArea.KEY, "reqTok123", BrokerArea.SECRET)), post.form)
        assertTrue("the secret never goes over the wire", kite.requests.none { it.body.contains(BrokerArea.SECRET) || (it.auth ?: "").contains(BrokerArea.SECRET) })
        alerted("Logged in to Zerodha as Test User until 06:00 tomorrow.")
        until("the broker state") { m.broker.value.loggedIn }
    }

    @Test fun kitesPageShowsOnlyZerodhaOverHttps() {
        BrokerArea.saveKeys()
        val m = model()
        m.loginWithSecret(BrokerArea.SECRET)
        kitePage(m)
        navigate("https://kite.zerodha.com/connect/login?v=3&api_key=testkey")
        assertTrue(shown("Loading kite.zerodha.com…"))
        compose.runOnUiThread { val w = webView(); shadowOf(w).webViewClient.onPageFinished(w, "https://kite.zerodha.com/connect/login") }
        until("loaded") { shown("Log in with your Zerodha ID, password and TOTP") }
        navigate("http://kite.zerodha.com/connect/login")
        until("blocked") { shown("Blocked a page outside zerodha.com") }
        navigate("https://kite.zerodha.com.evil.example/")
        assertTrue(shown("Blocked a page outside zerodha.com"))
        assertTrue("still open: the owner can go back to Zerodha", m.showKiteLogin.value)
        assertTrue(kite.requests.isEmpty())
    }

    @Test fun aRefusedLoginClosesThePageAndSaysWhy() {
        BrokerArea.saveKeys()
        val m = model()
        m.loginWithSecret(BrokerArea.SECRET)
        kitePage(m)
        navigate("http://127.0.0.1/iraalgo?status=cancelled")
        until("closed") { !m.showKiteLogin.value }
        alerted("Zerodha login did not complete: Zerodha returned status 'cancelled'")
        assertTrue(kite.requests.isEmpty())
        assertFalse(Broker.loggedIn)
    }

    @Test fun closeAndBackLeaveKitesPage() {
        BrokerArea.saveKeys()
        val m = model()
        m.loginWithSecret(BrokerArea.SECRET)
        kitePage(m)
        compose.onNodeWithText("Close").performClick()
        until("closed") { !m.showKiteLogin.value }
        m.loginWithSecret(BrokerArea.SECRET)
        until("open again") { shown("ZERODHA LOGIN") }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        until("closed by back") { !m.showKiteLogin.value }
    }

    // ---- first-time setup: the guide and the keys form ------------------------------------------------

    @Test fun theSetupGuideWalksEveryStepAndItsLinksOpen() {
        val m = model()
        plain { ConnectZerodhaScreen(m) }
        assertTrue(shown("Connect to Zerodha"))
        assertTrue(shown("Step 1 of 5")); assertTrue(shown("What you need"))
        click("Open one", substring = true)
        assertEquals("${Intent.ACTION_VIEW} https://zerodha.com/open-account", lastLink())
        click("Start")
        assertTrue(shown("Step 2 of 5")); assertTrue(shown("Open the Kite developer site"))
        click("Open developers.kite.trade")
        assertEquals("${Intent.ACTION_VIEW} https://developers.kite.trade/", lastLink())
        click("Sign up on the developer site", substring = true)
        assertEquals("${Intent.ACTION_VIEW} https://developers.kite.trade/signup", lastLink())
        click("Next")
        assertTrue(shown("Step 3 of 5")); assertTrue(shown("Create your app"))
        assertTrue(shown(Broker.REDIRECT))
        click("Copy")
        assertEquals(Broker.REDIRECT, clipboard())
        alerted("Redirect URL copied")
        click("Back")
        assertTrue(shown("Step 2 of 5"))
        click("Next"); click("Next")
        assertTrue(shown("Step 4 of 5")); assertTrue(shown("Copy the API key and secret"))
        click("Next")
        assertTrue(shown("Step 5 of 5")); assertTrue(shown("Save them in IraAlgo"))
        assertTrue("the keys form is the last step", shown("2. Paste the app's key and secret"))
        assertFalse("no Next on the last step", shown("Next"))
        assertFalse(shown("I already have my API key and secret"))
    }

    @Test fun someoneWithKeysSkipsStraightToTheForm() {
        val m = model()
        plain { ConnectZerodhaScreen(m) }
        click("I already have my API key and secret")
        assertTrue(shown("Step 5 of 5"))
        click("Back")
        assertTrue(shown("Step 4 of 5"))
    }

    private fun fillForm(key: String, secret: String, pin: String) {
        if (key.isNotEmpty()) field("API key").performTextInput(key)
        if (secret.isNotEmpty()) field("API secret").performTextInput(secret)
        field("Your app PIN (seals the secret)").performTextInput(pin)
        click("Save to the vault")
    }

    @Test fun savingTheKeysSealsTheSecretAndMovesToTheLogin() {
        PinLock.setPin(BrokerArea.PIN.toCharArray())
        val m = model()
        plain { ConnectZerodhaScreen(m) }
        click("I already have my API key and secret")
        fillForm(BrokerArea.KEY, BrokerArea.SECRET, BrokerArea.PIN)
        until("saved") { m.broker.value.configured }
        alerted("Saved, the secret sealed with your PIN. Now log in to Zerodha.")
        until("the login step") { shown("Keys saved ✓") }
        assertTrue(shown("3. Log in to Zerodha"))
        assertNoSecretShown()
        assertEquals("the secret opens with the PIN only", BrokerArea.SECRET, Broker.unsealSecret(BrokerArea.PIN.toCharArray()))
        assertNull("never stored readable", SecurePrefs.getString("kite.apiSecret"))
        assertEquals("••••tkey", Broker.maskedKey())
        assertNull("the typed key's draft is cleared", SecurePrefs.getString("draft.kite.key"))
        click("Log in to Zerodha")
        assertTrue(m.askLoginPin.value)
    }

    @Test fun aWrongPinSavesNothing() {
        PinLock.setPin(BrokerArea.PIN.toCharArray())
        val m = model()
        show { CredentialsForm(m) {} }
        fillForm(BrokerArea.KEY, BrokerArea.SECRET, BrokerArea.WRONG_PIN)
        alerted("That is not your app PIN.")
        assertFalse(Broker.configured)
        assertEquals("the PIN box is emptied", 0, editable("Your app PIN (seals the secret)").length)
    }

    @Test fun aKeyOrSecretThatIsNotLettersAndDigitsIsRefused() {
        PinLock.setPin(BrokerArea.PIN.toCharArray())
        val m = model()
        show { CredentialsForm(m) {} }
        fillForm("test key!", BrokerArea.SECRET, BrokerArea.PIN)
        alerted("The API key should be letters and digits only")
        assertFalse(Broker.configured)
        field("API key").performTextClearance(); field("API secret").performTextClearance()
        fillForm(BrokerArea.KEY, "", BrokerArea.PIN)
        alerted("The API secret should be letters and digits only")
        assertFalse(Broker.configured)
    }

    @Test fun pastingTheSecretTakesItOffTheClipboard() {
        PinLock.setPin(BrokerArea.PIN.toCharArray())
        val m = model()
        show { CredentialsForm(m) {} }
        val cm = app.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("k", "  ${BrokerArea.KEY}  "))
        compose.onAllNodesWithText("Paste")[0].performClick()
        compose.waitForIdle()
        assertEquals("the key may stay on the clipboard", "  ${BrokerArea.KEY}  ", clipboard())
        cm.setPrimaryClip(ClipData.newPlainText("s", BrokerArea.SECRET))
        compose.onAllNodesWithText("Paste")[1].performClick()
        compose.waitForIdle()
        assertEquals("the secret must not linger on the clipboard", "", clipboard() ?: "")
        assertTrue(shown("Kept on this phone (encrypted) until you save, so you can switch to the Kite site and back."))
        field("Your app PIN (seals the secret)").performTextInput(BrokerArea.PIN)
        click("Save to the vault")
        until("saved") { Broker.configured }
        assertEquals("the pasted key, trimmed", "••••tkey", Broker.maskedKey())
        assertEquals(BrokerArea.SECRET, Broker.unsealSecret(BrokerArea.PIN.toCharArray()))
    }

    @Test fun theTypedKeyIsKeptAsADraftButTheSecretNever() {
        SecurePrefs.put("draft.kite.secret", "oldsecretdraft")          // left by an older version
        val m = model()
        show { CredentialsForm(m) {} }
        until("the old secret draft erased") { SecurePrefs.getString("draft.kite.secret") == null }
        field("API key").performTextInput(BrokerArea.KEY)
        field("API secret").performTextInput(BrokerArea.SECRET)
        compose.mainClock.advanceTimeBy(1_000)
        until("the key draft written") { SecurePrefs.getString("draft.kite.key") == BrokerArea.KEY }
        BrokerArea.settle(300)
        assertNull("the secret is only ever in memory", SecurePrefs.getString("draft.kite.secret"))
    }

    @Test fun aKeyDraftComesBack() {
        SecurePrefs.put("draft.kite.key", "draftkey1")
        val m = model()
        show { CredentialsForm(m) {} }
        until("the draft restored") { editable("API key").length == "draftkey1".length }
        assertTrue(shown("Kept on this phone (encrypted) until you save, so you can switch to the Kite site and back."))
    }

    @Test fun keysSavedOffersTheLoginAndChangingThem() {
        BrokerArea.saveKeys()
        val m = model()
        plain { ConnectZerodhaScreen(m) }
        assertTrue(shown("Keys saved ✓"))
        click("Change keys")
        until("the keys forgotten") { !m.broker.value.configured }
        assertFalse(Broker.configured)
        alerted("Zerodha credentials erased from this phone.")
        until("back to the guide") { shown("Step 1 of 5") }
    }

    // ---- the Zerodha page ------------------------------------------------------------------------------

    private var ipReads = 0
    private var ipStatus = StaticIp.Status(null, "49.36.1.2", false)

    private fun page(m: AppModel) = compose.setContent {
        IraAlgoTheme("light") { BrokerPage(m, staticIpStatus = { ipReads++; ipStatus }) }
    }

    @Test fun beforeSetUpThePageOpensOnTheForm() {
        val m = model()
        page(m)
        assertTrue(shown("Zerodha"))
        node("Connection")
        assertTrue(shown("not set")); assertTrue(shown("not logged in today"))
        assertTrue("the keys form is open", shown("1. Create a Kite Connect app"))
        assertFalse(shown("Log in to Zerodha")); assertFalse(shown("Erase Zerodha keys from this phone")); assertFalse(shown("Live self-test"))
        click("Set up")
        assertFalse("Set up toggles the form", shown("1. Create a Kite Connect app"))
        click("Live · Zerodha")
        alerted("Set up Zerodha first.")
        assertFalse("still paper", m.settings.value.live)
        assertTrue(kite.requests.isEmpty())
        assertNoSecretShown()
    }

    @Test fun configuredNotLoggedIn() {
        BrokerArea.saveKeys()
        val m = model()
        page(m)
        node("••••tkey")
        assertTrue(shown("not logged in today"))
        assertFalse("the full key is never shown", shown(BrokerArea.KEY))
        assertFalse(shown("1. Create a Kite Connect app"))
        click("Change keys")
        assertTrue(shown("1. Create a Kite Connect app"))
        click("Change keys")
        click("Log in to Zerodha")
        assertTrue(m.askLoginPin.value)
        assertNoSecretShown()
    }

    @Test fun eraseTheKeysAsksFirst() {
        BrokerArea.saveKeys()
        val m = model()
        page(m)
        click("Erase Zerodha keys from this phone")
        assertTrue(shown("Erase the Zerodha keys?"))
        compose.onNodeWithText("Keep").performClick()
        compose.waitForIdle()
        assertFalse(shown("Erase the Zerodha keys?"))
        assertTrue(Broker.configured)
        click("Erase Zerodha keys from this phone")
        compose.onNodeWithText("Erase").performClick()
        until("erased") { !Broker.configured }
        alerted("Zerodha credentials erased from this phone.")
        until("the page follows") { !m.broker.value.configured }
    }

    @Test fun loggedInShowsTheSessionTheOrderCardAndLogsOut() {
        keysAndLogin(live = false)
        BrokerArea.listNifty(kite, listOf(e1))
        kite.quote("NSE:NIFTY 50", 24_480.0)
        val m = model()
        page(m)
        until("the session") { m.broker.value.loggedIn }
        node("Test User · until", substring = true)
        node("Live self-test"); node("Place an order"); node("Your account")
        assertNoSecretShown()
        click("Log out")
        until("logged out") { !Broker.loggedIn }
        assertTrue(kite.requests.any { it.method == "DELETE" && it.path == "/session/token" })
        alerted("Logged out of Zerodha.")
        until("the page follows") { shown("not logged in today") }
    }

    @Test fun modeProductAndThePreparedOrder() {
        BrokerArea.saveKeys()
        val m = model()
        page(m)
        click("Live · Zerodha")
        until("live") { m.settings.value.live }
        assertTrue(m.settings.value.allowRealOrders)
        node("Live · Zerodha").assertIsSelected()
        click("Paper · simulated")
        until("paper") { !m.settings.value.live }
        assertFalse(m.settings.value.allowRealOrders)
        click("MIS")
        until("MIS") { m.settings.value.orderProduct == "MIS" }
        node("MIS positions are squared off by Zerodha", substring = true)
        click("NRML")
        until("NRML") { m.settings.value.orderProduct == "NRML" }
        assertFalse(shown("MIS positions are squared off by Zerodha", substring = true))
        val before = m.settings.value.prepareRealOrder
        node("Prepare the expiry order at 11:01")
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        until("toggled") { m.settings.value.prepareRealOrder != before }
        node("Sent today")
        assertTrue(shown("0 of 10"))
    }

    // ---- static IP and the relay ------------------------------------------------------------------------

    private fun ipCard(m: AppModel) = show { StaticIpCard(m) { ipReads++; ipStatus } }

    @Test fun theStaticIpCardSaysWhereOrdersWouldComeFrom() {
        val m = model()
        ipStatus = StaticIp.Status("13.235.10.20", "13.235.10.20", false)
        ipCard(m)
        until("checked") { shown("✓ This phone is on your registered IP 13.235.10.20. Live orders can go.") }
        assertTrue(shown("This phone's IP now"))
        ipStatus = StaticIp.Status("13.235.10.20", "49.36.1.2", false)
        click("Check now")
        until("the mismatch") { shown("✗ This phone is on 49.36.1.2, not your registered 13.235.10.20. New live positions are refused until the relay below is connected (exits still go).") }
        ipStatus = StaticIp.Status(null, "49.36.1.2", false)
        click("Check now")
        until("not set") { shown("Not set up yet. SEBI requires API orders to come from an IP you have registered with Zerodha.") }
        ipStatus = StaticIp.Status("13.235.10.20", null, true)
        click("Check now")
        until("unreadable") { shown("Could not read this phone's IP just now.") }
        assertTrue(shown("VPN on"))
        assertEquals(4, ipReads)
    }

    @Test fun savingTheRegisteredIpValidatesIt() {
        val m = model()
        ipCard(m)
        val box = "Your registered static IP (your server's IP)"
        field(box).performTextInput("13.235.ab.10x")
        assertEquals("digits and dots only", "13.235..10", editable(box))
        field(box).performTextClearance()
        field(box).performTextInput("999.1.1.1")
        click("Save IP")
        alerted("That is not an IP address like 13.235.10.20.")
        assertNull(StaticIp.registered)
        field(box).performTextClearance()
        field(box).performTextInput("13.235.10.20")
        val reads = ipReads
        click("Save IP")
        alerted("Static IP saved.")
        assertEquals("13.235.10.20", StaticIp.registered)
        until("checked again") { ipReads > reads }
        field(box).performTextClearance()
        click("Save IP")
        alerted("Static IP cleared: orders are no longer checked against it.")
        assertNull(StaticIp.registered)
    }

    @Test fun theRelayNeedsAKeyThenAServer() {
        val m = model()
        ipCard(m)
        node("Connect & test").assertIsNotEnabled()
        assertFalse("no relay switch before a key", shown("Relay off: orders go direct"))
        click("Create key")
        until("the key", 60_000) { shown("…", substring = true) && Relay.publicKey != null }
        val pub = Relay.publicKey!!
        assertTrue(pub.startsWith("ssh-rsa "))
        node("Connect & test").assertIsEnabled()
        // Connect & test with no server IP: refused here, nothing leaves the phone.
        click("Connect & test")
        alerted("Enter the server's IP, like 144.24.125.164.")
        assertFalse(Relay.enabled)
        // The switch cannot turn the relay on before a server was connected.
        node("Relay off: orders go direct")
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff().performClick()
        alerted("Connect & test first.")
        assertFalse(Relay.enabled)
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
        // Copy the key for the server.
        compose.onAllNodesWithText("Copy")[0].performScrollTo().performClick()
        assertEquals(pub, clipboard())
        alerted("Key copied: paste it in Oracle's SSH keys box")
        click("Forget server")
        alerted("Server identity forgotten; the next connect trusts it anew")
        click("New key")
        until("a new key", 60_000) { Relay.publicKey != null && Relay.publicKey != pub }
        alerted("New key made: paste it into the server again")
    }

    @Test fun theRelaySwitchOnceAServerIsKnown() {
        SecurePrefs.put("relay.pub", "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQtestkeynotreal iraalgo-relay")
        Relay.host = "144.24.125.164"
        val m = model()
        ipCard(m)
        node("Relay off: orders go direct")
        val reads = ipReads
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        until("on") { shown("Orders go through your server") }
        assertTrue(Relay.enabled)
        compose.onAllNodes(isToggleable()).onFirst().assertIsOn()
        until("the IP read again") { ipReads > reads }
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        until("off") { shown("Relay off: orders go direct") }
        assertFalse(Relay.enabled)
    }

    @Test fun theVpnGuideOpensWithItsCommandAndLinks() {
        val m = model()
        ipCard(m)
        click("Advanced: use a WireGuard VPN instead ▼")
        node("Install the VPN relay on it (one command)", substring = true)
        click("Copy")
        assertEquals("sudo bash wg-relay-setup.sh", clipboard())
        alerted("Command copied")
        click("Open the phone's VPN settings")
        assertEquals("android.settings.VPN_SETTINGS", shadowOf(app).nextStartedActivity?.action)
        click("WireGuard on the Play Store", substring = true)
        assertEquals("${Intent.ACTION_VIEW} https://play.google.com/store/apps/details?id=com.wireguard.android", lastLink())
        click("Hide the VPN steps ▲")
        assertFalse(shown("Install the VPN relay on it (one command)", substring = true))
        click("Open the Oracle console", substring = true)
        assertEquals("${Intent.ACTION_VIEW} https://cloud.oracle.com/compute/instances", lastLink())
    }

    // ---- the live self-test --------------------------------------------------------------------------------

    @Test fun theSelfTestShowsEveryStepAndOnlyReads() {
        keysAndLogin()
        BrokerArea.listNifty(kite, listOf(e1))
        kite.quote("NSE:NIFTY 50", 24_480.0)
        StaticIp.registered = "13.235.10.20"
        BrokerArea.phoneIp("13.235.10.20")
        show { SelfTestCard() }
        click("Run the self-test")
        // A second tap while it runs does not start another.
        compose.onAllNodes(hasText("Checking…") or hasText("Run the self-test")).onFirst().performClick()
        until("the verdict", 60_000) { shown("All 9 checks passed.") }
        for (s in listOf("✓ Session", "✓ Funds", "✓ Positions", "✓ Order book", "✓ Holdings", "✓ GTT triggers", "✓ Quote (NIFTY 50)", "✓ Contract list", "✓ Static IP for orders"))
            assertTrue(s, shown(s))
        assertEquals("one run", 1, kite.requests.count { it.path == "/user/margins" })
        assertTrue("reads only", kite.requests.all { it.method == "GET" })
        assertTrue(shown("Run the self-test"))
    }

    @Test fun theSelfTestShowsWhatFailed() {
        show { SelfTestCard() }
        click("Run the self-test")
        until("the verdict") { shown("2 of 2 checks failed.") }
        assertTrue(shown("✗ Session")); assertTrue(shown("✗ Static IP for orders"))
        assertTrue(shown("Zerodha is not set up on this phone", substring = true))
        assertTrue(kite.requests.isEmpty())
    }

    // ---- the hand-typed order ---------------------------------------------------------------------------------

    private fun orderCard(): AppModel {
        keysAndLogin()
        BrokerArea.listNifty(kite, listOf(e1, e2))
        BrokerArea.listNifty(kite, listOf(e1), strikes = listOf(52_000.0, 52_100.0, 52_200.0), lot = 35, name = "BANKNIFTY")
        kite.quote("NSE:NIFTY 50", 24_480.0)
        kite.quote("NSE:NIFTY BANK", 52_120.0)
        for (i in kite.instruments) kite.quote("NFO:${i.symbol}", 100.0, 99.95, 100.05)
        val m = model()
        show { ManualOrder(m) }
        until("the contracts") { runCatching { strike("Strike · NIFTY 24,480") == "24500" }.getOrDefault(false) }
        return m
    }

    private fun reviewed(m: AppModel): OrderPlan {
        click("Review the order")
        return BrokerArea.await("the plan") { (m.plan.value as? Load.Done)?.value ?: (m.plan.value as? Load.Failed)?.let { throw AssertionError(it.why) } }
    }

    @Test fun theOrderCardReviewsWithoutSending() {
        val m = orderCard()
        node("Review the order").assertIsEnabled()
        click("CE"); click("BUY"); click("2")
        node("CE").assertIsSelected(); node("BUY").assertIsSelected(); node("2").assertIsSelected()
        field("Limit price (blank = best bid/offer)").performTextInput("1a01.5")
        assertEquals("digits and a point only", "101.5", editable("Limit price (blank = best bid/offer)"))
        val plan = reviewed(m)
        val leg = plan.legs.single()
        assertEquals(BrokerArea.symbol("NIFTY", e1, 24_500.0, "CE"), leg.tradingSymbol)
        assertEquals(Kite.Side.BUY, leg.side); assertEquals(150, leg.quantity); assertEquals(101.5, leg.price!!, 0.0)
        assertEquals("NRML", leg.product)
        assertTrue("the review sends nothing: ${kite.writes}", kite.writes.isEmpty())
        assertTrue(shown("Nothing is sent from here: the order opens for review over this page."))
    }

    @Test fun aBlankPriceSellsAtTheBidOnTheChosenExpiry() {
        val m = orderCard()
        click(e2.toString().substring(5))
        node(e2.toString().substring(5)).assertIsSelected()
        until("the strikes of that expiry") { runCatching { strike("Strike · NIFTY 24,480") == "24500" }.getOrDefault(false) }
        val leg = reviewed(m).legs.single()
        assertEquals("the default is SELL PE", BrokerArea.symbol("NIFTY", e2, 24_500.0, "PE"), leg.tradingSymbol)
        assertEquals(Kite.Side.SELL, leg.side); assertEquals(75, leg.quantity)
        assertEquals("a sell at the best bid", 99.95, leg.price!!, 1e-9)
    }

    @Test fun aStrikeIsPickedFromTheListedOnes() {
        val m = orderCard()
        compose.onNode(hasText("Strike · NIFTY 24,480")).performScrollTo().performClick()
        until("the list") { shown("24600") }
        assertTrue("the ATM strike is marked", shown("24500   ATM"))
        compose.onNodeWithText("24600").performClick()
        until("picked") { runCatching { strike("Strike · NIFTY 24,480") == "24600" }.getOrDefault(false) }
        assertEquals(24_600.0, reviewed(m).legs.single().tradingSymbol.let { s -> kite.instruments.first { it.symbol == s }.strike }, 0.0)
    }

    @Test fun switchingTheIndexLoadsItsOwnContracts() {
        val m = orderCard()
        click("BANKNIFTY")
        until("BANKNIFTY's strikes") { runCatching { strike("Strike · BANKNIFTY 52,120") == "52100" }.getOrDefault(false) }
        val leg = reviewed(m).legs.single()
        assertEquals(BrokerArea.symbol("BANKNIFTY", e1, 52_100.0, "PE"), leg.tradingSymbol)
        assertEquals("one BANKNIFTY lot", 35, leg.quantity)
    }

    @Test fun theLotsOfferedStopAtTheCap() {
        orderCard()
        assertTrue(shown("1")); assertTrue(shown("2"))
        assertFalse("the default cap is 2 lots", shown("3"))
    }

    @Test fun noContractListMeansNoReview() {
        keysAndLogin()
        kite.down += "/instruments/NFO"
        val m = model()
        show { ManualOrder(m) }
        until("the reason") { shown("Could not load Zerodha's contract list: Gateway timed out") }
        node("Review the order").assertIsNotEnabled()
        assertEquals(Load.Idle, m.plan.value)
    }

    // ---- the review dialog's own states and the stuck-leg card ------------------------------------------------

    private val leg = Kite.Order("NIFTY26OCT24500CE", Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 100.05)
    private fun twoLegs() = OrderPlan("Strangle", null, listOf(leg, leg.copy(tradingSymbol = "NIFTY26OCT24500PE")), emptyMap(), listOf(emptyList(), emptyList()), false)

    @Test fun theStuckCardOffersEachChoice() {
        val done = ArrayList<String>()
        var st by mutableStateOf(AppModel.StuckLeg(twoLegs(), 0, "250926000000123456", emptyList(), "OPEN"))
        show { StuckCard(st) { done += it } }
        assertTrue(shown("Leg 1 is still working"))
        assertTrue(shown("BUY NIFTY26OCT24500CE ×75 · open · order …123456"))
        assertTrue(shown("1 more leg held back: they go only after this one has completely filled."))
        click("Move to the best price"); click("It filled: send the remaining legs"); click("Cancel this leg (send nothing more)")
        assertEquals(listOf("reprice", "continue", "cancel"), done)
        val three = twoLegs().let { it.copy(legs = it.legs + leg.copy(tradingSymbol = "NIFTY26OCT24600CE"), refusals = it.refusals + listOf(emptyList())) }
        st = AppModel.StuckLeg(three, 0, "250926000000123456", emptyList(), "TRIGGER PENDING")
        compose.waitForIdle()
        assertTrue(shown("2 more legs held back: they go only after this one has completely filled."))
        assertTrue(shown("trigger pending", substring = true))
        st = AppModel.StuckLeg(twoLegs(), 1, "250926000000123456", emptyList(), "OPEN")
        compose.waitForIdle()
        assertTrue(shown("Leg 2 is still working"))
        assertTrue(shown("This was the last leg."))
        assertFalse(shown("It filled: send the remaining legs"))
    }

    @Test fun aPlanThatFailedClosesTheReviewWithItsReason() {
        val m = model()
        compose.setContent { IraAlgoTheme("light") { OrderReviewDialog(m) } }
        m.plan.value = Load.Failed("that contract is not listed")
        until("closed") { m.plan.value == Load.Idle }
        assertTrue(BrokerArea.alerted("that contract is not listed"))
    }

    @Test fun aPlanBeingPreparedShowsWhatItIsDoing() {
        val m = model()
        compose.setContent { IraAlgoTheme("light") { OrderReviewDialog(m) } }
        m.plan.value = Load.Busy("Looking up the contract")
        until("the spinner") { shown("Looking up the contract") }
        m.plan.value = Load.Idle
        until("gone") { !shown("Looking up the contract") }
    }

    @Test fun inPaperModeTheReviewCannotSend() {
        val m = model()
        compose.setContent { IraAlgoTheme("light") { OrderReviewDialog(m) } }
        m.plan.value = Load.Done(OrderPlan("Test order", null, listOf(leg), emptyMap(), listOf(emptyList()), false))
        until("the review") { shown("Hold to send to Zerodha") }
        node("Hold to send to Zerodha").assertIsNotEnabled()
        assertTrue(shown("This is Paper mode. To send real orders, tap the PAPER TRADING badge at the top and switch to Live."))
        click("Close")
        until("closed") { m.plan.value == Load.Idle }
    }
}

/** Shared set-up and states for the two Zerodha screen-matrix classes below. */
abstract class BrokerLayoutBase(device: DeviceConfig) : BrokerScreenBase(device) {
    companion object {
        /** Controls the smoke must not press: they would try to reach a real server (the relay) or take seconds (RSA keys). */
        val NO_SMOKE = setOf("Connect & test", "New key", "Create key")

        /** Real layout and accessibility bugs these screens show, matched per finding (see [BrokerScreenBase.lintKnown]). */
        val KNOWN = listOf(
            BrokerScreenBase.Known(Regex("""'ssh-rsa .*' has more lines than it may show"""),
                "Static IP card: the relay key row (Text maxLines = 1 with no overflow) is cut mid-line instead of ending in an ellipsis"),
            BrokerScreenBase.Known(Regex("""A11Y\] clickable node \d+ has no text"""),
                "unlabelled controls: the relay Switch (Static IP card), ToggleRow's Switch and the strike field's tap overlay (StrikeDropdown) have no text or description, so TalkBack announces an unnamed control"),
            BrokerScreenBase.Known(Regex("""TEXT\] '.*' is cut off at the side: [\d.]+ dp of text in 0\.0 dp"""),
                "LedgerLine: a long value takes the whole row and squeezes the label (weight 1f) to 0 dp, so it disappears on small phones and large fonts (self-test step names; 'Bid / offer / last' in the order review)"),
            BrokerScreenBase.Known(Regex("""(TOUCH|EMPTY|TEXT)\] (clickable )?'(SELL|BUY)'"""),
                "ParamTokens' Token: the selectable node is 34-43 dp tall (minimumInteractiveComponentSize sits outside .selectable), under 48 dp for the order's SELL/BUY choice"),
            BrokerScreenBase.Known(Regex("""TEXT\] '.*' is ellipsized"""),
                "BrassButton labels (e.g. 'Check now', 'Open the phone's VPN settings') are ellipsized at large font scales on narrow screens"),
        )
    }

    private lateinit var kite: FakeKite
    private val store = androidx.lifecycle.ViewModelStore()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val e1 = LocalDate.now().plusDays(9)

    @Before fun up() {
        kite = FakeKite()
        BrokerArea.clearAlerts()
        BrokerArea.phoneIp(null)
        SecurePrefs.put("g.cutoff", -1)
        BrokerArea.listNifty(kite, listOf(e1))
        kite.quote("NSE:NIFTY 50", 24_480.0)
        for (i in kite.instruments) kite.quote("NFO:${i.symbol}", 100.0, 99.95, 100.05)
    }

    @After fun down() {
        store.clear()
        BrokerArea.settle(800)
        kite.close()
        BrokerArea.phoneIp(null)
    }

    private fun model() = BrokerArea.model(app, store)
    private val ip: suspend () -> StaticIp.Status = { StaticIp.Status("13.235.10.20", "49.36.1.2", false) }

    private fun until(timeoutMs: Long = 20_000, cond: () -> Boolean) = compose.waitUntil(timeoutMs) {
        shadowOf(Looper.getMainLooper()).idle()
        if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
        cond()
    }

    protected fun pageBeforeSetUp() {
        val m = model()
        show { BrokerPage(m, ip) }
        snap("zerodha-page-setup", KNOWN)
    }

    protected fun pageLoggedIn() {
        BrokerArea.saveKeys(); kite.login()
        val m = model()
        show { BrokerPage(m, ip) }
        until { m.broker.value.loggedIn }
        BrokerArea.settle(300)
        snap("zerodha-page-live", KNOWN)
    }

    protected fun cards() {
        BrokerArea.saveKeys(); kite.login()
        SecurePrefs.put("relay.pub", "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQtestkeynotreal iraalgo-relay")
        StaticIp.registered = "13.235.10.20"
        BrokerArea.phoneIp("13.235.10.20")
        val m = model()
        show { Column(Modifier.verticalScroll(rememberScrollState())) { StaticIpCard(m, ip); SelfTestCard(); ManualOrder(m) } }
        compose.onNodeWithText("Advanced: use a WireGuard VPN instead ▼").performScrollTo().performClick()
        compose.onNodeWithText("Run the self-test").performScrollTo().performClick()
        until(60_000) { compose.onAllNodesWithText("All 9 checks passed.").fetchSemanticsNodes().isNotEmpty() }
        until { runCatching { compose.onNodeWithText("Review the order").assertIsEnabled(); true }.getOrDefault(false) }
        snap("zerodha-cards", KNOWN)
        smokeOnce(NO_SMOKE)
    }

    protected fun connectStepOne() {
        val m = model()
        show { ConnectZerodhaScreen(m) }
        snap("connect-zerodha-guide", KNOWN)
        smokeOnce(NO_SMOKE + "Save to the vault")
    }

    protected fun connectForm() {
        val m = model()
        show { ConnectZerodhaScreen(m) }
        compose.onNodeWithText("I already have my API key and secret").performScrollTo().performClick()
        compose.waitForIdle()
        snap("connect-zerodha-form", KNOWN)
    }

    protected fun connectKeysSaved() {
        BrokerArea.saveKeys()
        val m = model()
        show { ConnectZerodhaScreen(m) }
        snap("connect-zerodha-login", KNOWN)
    }

    protected fun reviewWithAStuckLeg() {
        PinLock.setPin(BrokerArea.PIN.toCharArray())
        kite.login()
        val m = model()
        m.loadAccount(quiet = true)
        BrokerArea.await("the account") { m.account.value as? Load.Done }
        m.planManual("NIFTY", e1, 24_500.0, Right.CE, Kite.Side.BUY, 1, "NRML", null)
        val plan = BrokerArea.await("the plan") { (m.plan.value as? Load.Done)?.value }
        m.stuck.value = AppModel.StuckLeg(plan, 0, "250926000000123456", emptyList(), "OPEN")
        m.sending.value = Load.Failed("Leg 1 open. It is still working at Zerodha; decide below.")
        show { OrderReviewDialog(m) }
        snap("order-review-stuck", KNOWN)
    }
}

/**
 * The main Zerodha screen states on every device set-up (4 sizes x 3 font scales x light/dark): every
 * window saved as a screenshot, [com.optionslab.app.testing.LayoutLint], and a click smoke on the busiest.
 * Real AppModel, fake Kite, no network.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class BrokerScreensLayoutTest(device: DeviceConfig) : BrokerLayoutBase(device) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()
    }

    @Test fun pageBeforeSetUpState() = pageBeforeSetUp()
    @Test fun cardsState() = cards()
    @Test fun connectStepOneState() = connectStepOne()
    @Test fun connectKeysSavedState() = connectKeysSaved()
}

/** The Zerodha page's secondary states (dialogs, the form, the live page) on six set-ups spanning the matrix, to keep CI time in bounds. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class BrokerDialogsLayoutTest(device: DeviceConfig) : BrokerLayoutBase(device) {
    companion object {
        private val PICK = setOf("small-font2.0-light", "small-font1.0-dark", "phone-font1.3-light", "landscape-font1.0-light",
            "landscape-font2.0-dark", "tablet-font1.3-dark")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix().filter { (it[0] as DeviceConfig).name in PICK }
    }

    @Test fun pageLoggedInState() = pageLoggedIn()
    @Test fun connectFormState() = connectForm()
    @Test fun reviewWithAStuckLegState() = reviewWithAStuckLeg()
}
