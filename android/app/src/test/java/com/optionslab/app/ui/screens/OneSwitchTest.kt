package com.optionslab.app.ui.screens

import com.optionslab.app.testing.Cleared
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
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

    private fun exists(text: String, substring: Boolean = false, desc: Boolean = false) =
        (if (desc) compose.onAllNodes(hasContentDescription(text)) else compose.onAllNodesWithText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

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

    /**
     * 18 Oct (Boss: every paper strategy present for live too, "i will switch it off, if i need it"): beside the Live switch, each paper
     * strategy's own "Trade live" switch - on by default, off with one tap and no PIN, on again only with the PIN; the change is
     * logged. Jarvis's own ideas are not in the list.
     */
    @Test fun eachPaperStrategyHasItsOwnTradeLiveSwitchOffFreeOnWithThePin() {
        com.optionslab.app.data.ArmLive.testDefaultOff = false
        PinLock.setPin(pin.toCharArray())
        AutomationSupport.liveSettings(live = true)
        val m = holder.model
        compose.setContent { IraAlgoTheme("light") { ArmsLiveList(m) } }
        compose.waitForIdle()
        val label = com.optionslab.engine.orb.DonchianArm.LABEL
        pump { exists(label) }
        assertTrue("on by default", com.optionslab.app.data.ArmLive.view.value.on(com.optionslab.engine.orb.DonchianArm.SOURCE))
        assertFalse("Jarvis's own ideas are not listed", exists("Forge ideas", substring = true))
        assertTrue(exists("ntfy alerts"))
        // 30 Oct (Boss: "make all arms as live"): ntfy alerts' Trade live starts on like every arm's.
        assertTrue("ntfy alerts starts on too", com.optionslab.app.data.ArmLive.view.value.on(com.optionslab.engine.orb.LiveMirror.NTFY_ALERTS))
        // Every arm's row renders with both switches (Solo (midday) only in Jarvis builds).
        for (spec in com.optionslab.engine.orb.LiveMirror.ARMS) {
            if (spec.key == com.optionslab.engine.orb.LiveMirror.SOLO_MIDDAY && !com.optionslab.app.BuildConfig.JARVIS) {
                assertFalse("Solo (midday) is Jarvis's", exists("Trade live: ${spec.label}", desc = true)); continue
            }
            assertTrue(spec.label, exists("Trade live: ${spec.label}", desc = true))
            assertTrue(spec.label, exists("Shadow: ${spec.label}", desc = true))
        }
        compose.onNode(hasContentDescription("Trade live: $label")).performSemanticsAction(SemanticsActions.OnClick).also { frames() }
        pump { !com.optionslab.app.data.ArmLive.view.value.on(com.optionslab.engine.orb.DonchianArm.SOURCE) }
        assertFalse("off with no PIN", exists("Confirm it is you"))
        compose.onNode(hasContentDescription("Trade live: $label")).performSemanticsAction(SemanticsActions.OnClick).also { frames() }
        enterPin(pin)
        pump { com.optionslab.app.data.ArmLive.view.value.on(com.optionslab.engine.orb.DonchianArm.SOURCE) }
        val log = com.optionslab.app.data.ArmLive.view.value.log.joinToString("\n")
        assertTrue(log, log.contains("Trade live OFF (you)") && log.contains("Trade live ON (you)"))
    }

    /**
     * 18 Oct (Boss: "add a shadow button, so that even i move to live, until is on, it will not trade, but when i see thats making money,
     * i will make it off"): each arm's Shadow switch - on by default, off only with the PIN, on again with one tap; its record shown.
     */
    @Test fun eachArmHasItsShadowSwitchOnByDefaultOffWithThePin() {
        com.optionslab.app.data.ArmLive.testDefaultOff = false
        PinLock.setPin(pin.toCharArray())
        AutomationSupport.liveSettings(live = true)
        val m = holder.model
        compose.setContent { IraAlgoTheme("light") { ArmsLiveList(m) } }
        compose.waitForIdle()
        val key = com.optionslab.engine.orb.DonchianArm.SOURCE
        val label = com.optionslab.engine.orb.DonchianArm.LABEL
        pump { exists("Shadow: $label", desc = true) }
        // 30 Oct (Boss: "make all live if i move to live market"): out of shadow by default; on is one tap, off again asks the PIN.
        assertFalse("out of shadow by default", com.optionslab.app.data.ArmLive.view.value.shadowOn(key))
        compose.onNode(hasContentDescription("Shadow: $label")).performSemanticsAction(SemanticsActions.OnClick).also { frames() }
        pump { com.optionslab.app.data.ArmLive.view.value.shadowOn(key) }    // on: no PIN
        assertTrue(exists("no shadow trade yet", substring = true))
        compose.onNode(hasContentDescription("Shadow: $label")).performSemanticsAction(SemanticsActions.OnClick).also { frames() }
        enterPin(pin)                                              // off asks the PIN
        pump { !com.optionslab.app.data.ArmLive.view.value.shadowOn(key) }
        val log = com.optionslab.app.data.ArmLive.view.value.log.joinToString("\n")
        assertTrue(log, log.contains("Shadow OFF (you)") && log.contains("Shadow ON (you)"))
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
            Broker.placeOrder(Kite.Order("NIFTY26OCT24500CE", Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 100.0, 0.05, "NFO", "iraorb"), verdict = Cleared.live(Kite.Order("NIFTY26OCT24500CE", Kite.Side.BUY, 75, 75, "NRML", "LIMIT", 100.0, 0.05, "NFO", "iraorb")))
            fail("an entry went in the paper-only period")
        } catch (e: Broker.KiteError) { assertEquals("paper_only", e.type) }
        assertTrue(k.placed.isEmpty())
    }
}
