package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.OrbArms
import com.optionslab.app.data.PaperOnly
import com.optionslab.app.ira.IraNewsTrades
import com.optionslab.app.security.PinLock
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.ModelHolder
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.Kite
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode

/**
 * ONE SWITCH (Boss, 18 Oct: "Live like paper, arms too"). Switching to Live with the PIN also turns "AI trades go live" on;
 * switching to Paper turns both off; a wrong PIN changes nothing. Each arm keeps its own live choice (an arm not cleared for
 * live, or paper only, stays on paper), Jarvis's own trades stay on paper, and the paper-only period still blocks entries.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OneSwitchTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var holder: ModelHolder
    private var kite: FakeKite? = null
    private val pin = "246813"

    @Before fun up() {
        holder = ModelHolder(app)
        AutomationSupport.liveSettings(live = false)
        IraNewsTrades.paperFirst = true
    }

    @After fun down() { holder.clear(); kite?.close() }

    private fun frames(n: Int = 12) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    private fun pump(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            frames(2)
            if (runCatching(cond).getOrDefault(false)) return
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in $timeoutMs ms")
            Thread.sleep(20)
        }
    }

    private fun exists(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun enterPin(value: String) {
        pump { exists("Confirm it is you") }
        compose.onNode(hasSetTextAction() and hasText("PIN") and hasAnyAncestor(isDialog())).performTextInput(value).also { frames() }
        compose.onNode(hasText("Confirm") and hasAnyAncestor(isDialog())).performClick().also { frames() }
    }

    @Test fun goingLiveTurnsAiTradesLiveOnAndPaperTurnsBothOff() {
        val m = holder.model
        m.goLive()
        pump { AppSettings.load().live && AppSettings.load().allowRealOrders && !IraNewsTrades.paperFirst }
        assertTrue(m.settings.value.live && m.settings.value.allowRealOrders)
        m.goPaper()
        // Back to paper is seen by every read at once (memory first), then written.
        assertTrue("AI trades go live is off at once", IraNewsTrades.paperFirst)
        pump { !AppSettings.load().live && !AppSettings.load().allowRealOrders }
        Thread.sleep(200)
        assertTrue("and stays off on disk", IraNewsTrades.paperFirst)
        assertFalse(m.settings.value.live)
        // Both changes are in the diagnostics' timeline.
        pump { com.optionslab.app.data.Diag.lines().joinToString("\n").let { d ->
            d.contains("AI trades go live ON, with the switch to Live") && d.contains("AI trades go live off, with the switch to Paper") } }
    }

    @Test fun theLivePinStepSwitchesBothOnlyWithTheRightPin() {
        PinLock.setPin(pin.toCharArray())
        val m = holder.model
        var asked by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { IraAlgoTheme("light") { if (asked) GoLivePin(m, com.optionslab.app.ui.LIVE_PIN_ASK) { asked = false } } }
        compose.waitForIdle(); frames()
        assertTrue(exists("\"AI trades go live\" turns on with it", substring = true))
        // A wrong PIN: nothing changes.
        enterPin("111111")
        pump { exists("Not the right PIN.") }
        Thread.sleep(200)
        assertFalse("still Paper", AppSettings.load().live)
        assertTrue("AI trades still on paper", IraNewsTrades.paperFirst)
        // Cancel: nothing changes either.
        compose.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick().also { frames() }
        pump { !asked }
        assertFalse(AppSettings.load().live); assertTrue(IraNewsTrades.paperFirst)
        // The right PIN: Live and AI trades go live, in one step.
        asked = true; frames()
        enterPin(pin)
        pump { AppSettings.load().live && AppSettings.load().allowRealOrders && !IraNewsTrades.paperFirst }
        assertFalse("the PIN step closed", asked)
    }

    @Test fun aiTradesGoLiveCanBeTurnedOffOnItsOwnBesideTheLiveSwitchWithoutAPin() {
        IraNewsTrades.paperFirst = false
        AutomationSupport.liveSettings(live = true)
        val m = holder.model
        compose.setContent { IraAlgoTheme("light") { LiveScopeNote(m, live = true) } }
        compose.waitForIdle()
        pump { exists("On (with Live)", substring = true) }
        compose.onNode(hasContentDescription("AI trades go live")).performSemanticsAction(SemanticsActions.OnClick).also { frames() }
        assertTrue("off at once, no PIN", IraNewsTrades.paperFirst)
        pump { exists("Off: Jarvis's ideas go on paper", substring = true) }
        assertTrue("the app stays Live", AppSettings.load().live)
    }

    @Test fun eachArmKeepsItsOwnLiveChoiceAndJarvissOwnTradesStayOnPaper() {
        // ORB cleared for live with its own PIN; ORB Fresh armed in Paper (not cleared): the switch changes neither.
        AutomationSupport.orbState(app, JSONObject()
            .put("armed", JSONObject().put("orb", true).put("orb_fresh", true))
            .put("auto", JSONObject().put("orb", true).put("orb_fresh", true))
            .put("liveOk", JSONObject().put("orb", true).put("orb_fresh", false)))
        fun liveOk() = runBlocking { OrbArms.view() }.arms.filter { it.armed }.associate { it.arm.source to it.liveOk }
        val before = liveOk()
        assertEquals("ORB was cleared for live with its own PIN", true, before["orb"])
        holder.model.goLive()
        pump { AppSettings.load().live && !IraNewsTrades.paperFirst }
        assertEquals("the switch changes no arm's own live choice", before, liveOk())
        // Paper-only arms (and paper changes) never trade live, whatever the switch.
        for (a in listOf(com.optionslab.engine.orb.HeroRules.ARM, com.optionslab.engine.orb.SweepRules.ARM, com.optionslab.engine.orb.BreadthDonchianArm.ARM,
            com.optionslab.engine.orb.Solo2.ARM)) assertNotNull("${a.label} stays paper only", OrbArms.liveRefusal(a.source))
        // Jarvis's own trades: on paper until their own record is proven, and never live by himself even then.
        assertFalse("Jarvis's trades stay on paper (no proven record)", IraNewsTrades.goesLive())
        val hub = listOf(java.io.File("src/main/java/com/optionslab/app/ira/IraHub.kt"), java.io.File("app/src/main/java/com/optionslab/app/ira/IraHub.kt"))
            .first { it.isFile }.readText()
        assertTrue("his own pick is placed paper-only", Regex("IraNewsTrades\\.place\\([^\\n]*paperOnly = true").containsMatchIn(hub))
    }

    @Test fun afterTheSwitchToLiveThePaperOnlyPeriodStillBlocksEntries() = runBlocking {
        val k = FakeKite().also { kite = it }
        k.login(live = false)
        SecurePrefs.put("g.cutoff", -1)
        val today = com.optionslab.app.data.Market.today()
        PaperOnly.resetForTest(com.optionslab.ira.PaperOnlyPeriod.Period(today.plusDays(8), "10 days on paper", today.minusDays(5)))
        holder.model.goLive()
        pump { AppSettings.load().live }
        try {
            Broker.placeOrder(Kite.Order("NIFTY26OCT24500CE", Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 100.0, 0.05, "NFO", "iraorb"))
            fail("an entry went in the paper-only period")
        } catch (e: Broker.KiteError) { assertEquals("paper_only", e.type) }
        assertTrue(k.placed.isEmpty())
    }
}
