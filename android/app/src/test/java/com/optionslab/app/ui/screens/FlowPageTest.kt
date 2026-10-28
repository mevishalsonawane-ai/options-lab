package com.optionslab.app.ui.screens

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.FlowArm
import com.optionslab.app.data.KiteStream
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.signals.FlowRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Cabinet → Automation → IraThinker's flow (28 Oct): the not-proven label, the diagram for the indices only (no MCX, no Global, no
 * Alerts node), the sources dimmed with a plain reason (logged out, market closed, stale ticks), the engine's live counts, the
 * results and the candidates' cards; no rupee figure while the app is locked; still under the battery saver.
 */
@RunWith(AndroidJUnit4::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FlowPageTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 10, 28)
    private val calls = ArrayList<String>()

    private val live = FlowStreamFacts(loggedIn = true, status = KiteStream.Status.LIVE, instruments = 9, lastTickAgoS = 1, reconnects = 2,
        marketOpen = true, prices = mapOf("NIFTY" to (24_980.0 to -0.47)), vix = 13.4 to 3.9, options = 5)

    private val taken = FlowArm.Candidate(day, "NIFTY", day.atTime(9, 41), FlowArm.TAKEN, "ORB_DOWN", 79, 72, 81.0, 12.0, 5,
        "taken: NIFTY 25000 PE 65 @ 100 · paper", plan = true, final = true)
    private val skipped = FlowArm.Candidate(day, "BANKNIFTY", day.atTime(9, 41), "Time Machine", "ORB_DOWN", 70, 70, 30.0, 60.0, 3,
        "target first 30% below stop first 60%", plan = true, final = true)
    private val pos = FlowArm.Pos("NIFTY", "NIFTY25000PE", "PE", 1, 65, 100.0, LocalDateTime.of(day, java.time.LocalTime.of(9, 41)), "p1", 80.0,
        "s1", 25_050.0, 24_840.0, 79, 81.0, exit = 120.0, exitTime = LocalDateTime.of(day, java.time.LocalTime.of(10, 5)), why = "index target", charges = 40.0)
    private val view = FlowArm.View(armed = true, today = listOf(pos), closed = listOf(pos), candidates = listOf(taken, skipped), nets = listOf(1260.0), sessions = 6)

    private fun show(s: FlowPageState, animate: Boolean = true) {
        compose.setContent { IraAlgoTheme("dark") { FlowContent(s, animate, null, onArm = { calls += "arm:$it" }) } }
        compose.waitForIdle()
    }

    private fun exists(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(t: String) = compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()

    @Test fun thePageSaysNotProvenShowsTheFlowAndTheCandidates() {
        show(flowPageState(view, live, locked = false))
        assertTrue(exists(FlowRules.NOT_PROVEN))
        assertTrue(tagged("flow-diagram-live")); assertTrue(tagged("flow-moving"))
        assertTrue(exists("Flow orders today: 1 · open Flow positions: 0 · skipped: 1"))
        assertTrue(exists("NIFTY · TAKEN (paper)")); assertTrue(exists("BANKNIFTY · Time Machine"))
        assertTrue(exists("Skipped: target first 30% below stop first 60%"))
        assertTrue(exists("IraScore 79")); assertTrue(exists("target first 81% vs stop first 12%"))
        assertTrue("today's paper result while unlocked", exists("+₹1,260"))
        // Indices only: no MCX or Global tile, no Alerts node.
        for (w in listOf("MCX", "Global", "Alerts", "ntfy")) assertFalse(w, exists(w))
        compose.onNodeWithTag("flow-switch").performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals(listOf("arm:false"), calls)
    }

    @Test fun noRupeesWhileTheAppIsLocked() {
        val s = flowPageState(view, live, locked = true)
        assertNull(s.netToday)
        show(s)
        assertFalse(exists("₹"))
    }

    @Test fun stillUnderTheBatterySaverOrALowBattery() {
        show(flowPageState(view, live, locked = false), animate = false)
        assertTrue(tagged("flow-diagram-static")); assertTrue(tagged("flow-still")); assertFalse(tagged("flow-diagram-live"))
        assertFalse("the saver", flowAnimates(resumed = true, saver = true, percent = 80, charging = false))
        assertFalse("30%, not charging", flowAnimates(resumed = true, saver = false, percent = 30, charging = false))
        assertTrue("30% while charging", flowAnimates(resumed = true, saver = false, percent = 30, charging = true))
        assertFalse("in the background", flowAnimates(resumed = false, saver = false, percent = 90, charging = false))
        assertTrue(flowAnimates(resumed = true, saver = false, percent = null, charging = false))
    }

    @Test fun aSourceOffDimsItsStageWithAPlainReason() {
        show(flowPageState(view, live.copy(loggedIn = false), locked = false))
        assertTrue(exists("Zerodha stream: logged out of Zerodha"))
        assertTrue(exists("Engine: paused: logged out of Zerodha"))
        assertEquals("market closed", flowStreamDim(live.copy(marketOpen = false)))
        assertEquals("stale ticks (75 s since the last)", flowStreamDim(live.copy(lastTickAgoS = 75)))
        assertEquals("Zerodha's stream is reconnecting", flowStreamDim(live.copy(status = KiteStream.Status.RETRYING)))
        assertEquals("Zerodha's stream is off", flowStreamDim(live.copy(status = KiteStream.Status.OFF)))
        assertEquals("no tick yet", flowStreamDim(live.copy(lastTickAgoS = null)))
        assertNull(flowStreamDim(live))
        // The arm off dims the engine too.
        val off = flowPageState(view.copy(armed = false), live, locked = false)
        assertEquals("the Flow arm is off", off.engineDim)
        assertTrue(off.stages.all { it.dim == "the Flow arm is off" })
    }

    @Test fun theEnginesCountsComeFromTheArm() {
        val s = flowPageState(view, live, locked = false)
        assertEquals(listOf("Scanner", "IraScore", "Time Machine", "Risk Gate"), s.stages.map { it.stage.words })
        // Both plans passed the Scanner and IraScore; one passed the Time Machine and the Risk Gate (and was taken).
        assertEquals(listOf(2, 2, 1, 1), s.stages.map { it.passed })
        assertEquals(listOf("Zerodha stream", "Index prices", "India VIX", "Option chain"), s.sources.map { it.title })
        assertTrue(s.sources[1].lines.first().startsWith("NIFTY 24,980.00 -0.47%"))
        assertTrue(s.sources[1].lines.any { it == "FINNIFTY —" })
    }
}
