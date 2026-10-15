package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.SignalTesterHost
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.Right
import com.optionslab.engine.signals.CallParser
import com.optionslab.engine.signals.ChannelScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * The Signal tester's card (15 Oct; compact like every Strategies row): its name, ONE status line ("shadow — never trades") and
 * its switch (off by default); the name's dialog with the explanation, the channels and their scorecards (a channel: its calls),
 * Needs review with the fix dialog (OK confirms), the allow-list editor, the paste box and the settings - each closed by OK.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class SignalTesterCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val t0 = LocalDateTime.of(2026, 10, 15, 9, 28, 4)
    private val draft = CallParser.Draft("SENSEX", 73100.0, Right.CE, null, CallParser.Trig.ABOVE, 280.0, null, null, null, listOf(310.0, 345.0, 400.0))
    private val call = SignalTesterHost.Rec("s1", "Sensex Kings", t0, SignalTesterHost.NOTIFICATION, kind = SignalTesterHost.CALL, text = "#sensex 73100 CE", draft = draft)
    private val review = SignalTesterHost.Rec("s2", "Sensex Kings", t0.plusMinutes(5), SignalTesterHost.PASTED, kind = SignalTesterHost.REVIEW,
        text = "73200 CE above 220", unsure = listOf("no index or stock named"), draft = CallParser.Draft(null, 73200.0, Right.CE, trigger = 220.0))
    private val view = SignalTesterHost.View(on = true, allow = listOf("Sensex Kings"),
        cards = listOf(ChannelScore.card("Sensex Kings", listOf(ChannelScore.Item(ChannelScore.Kind.CALL), ChannelScore.Item(ChannelScore.Kind.REVIEW)))),
        recs = listOf(call, review), review = listOf(review))

    private class Calls { val log = ArrayList<String>(); var confirmed: CallParser.Draft? = null }

    private fun show(v: SignalTesterHost.View, c: Calls = Calls()) = compose.setContent {
        IraAlgoTheme("light") {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SignalTesterCardContent(v, access = false, said = null, a = SignalActions(
                    onSwitch = { c.log += "switch $it" }, onListen = { c.log += "listen $it" }, openAccess = { c.log += "access" },
                    allow = { c.log += "allow $it" }, disallow = { c.log += "disallow $it" },
                    paste = { ch, text, at, approx -> c.log += "paste $ch|$text|${at.toLocalTime()}|$approx" },
                    confirm = { id, d -> c.log += "confirm $id"; c.confirmed = d }, delay = { c.log += "delay $it" }, now = { t0 }))
            }
        }
    }

    private fun tap(desc: String) { compose.onNodeWithContentDescription(desc).performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }
    private fun tapText(t: String) { compose.onNodeWithText(t).performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }

    @Test fun offByDefaultTheRowIsCompact() {
        val c = Calls()
        show(SignalTesterHost.View(), c)
        compose.onNodeWithText("Signal tester (shadow)").assertExists()
        compose.onNodeWithText("Off · shadow — never trades").assertExists()
        compose.onNodeWithContentDescription("Shadow only: switch the Signal tester").assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf("switch true"), c.log)
        // No explanation on the row itself.
        compose.onNodeWithText(signalExplanation().first()).assertDoesNotExist()
    }

    @Test fun theNamesDialogChannelsAndTheCallsList() {
        show(view)
        compose.onNodeWithText(keepNumbersWhole("On · shadow — never trades · 1 channel · 1 calls · 1 to review")).assertExists()
        tap("About Signal tester (shadow)")
        compose.onNodeWithText(keepNumbersWhole(signalExplanation().first())).assertExists()
        compose.onNodeWithText(keepNumbersWhole(ChannelScore.line(view.cards.single()))).assertExists()
        tap("Channel Sensex Kings")
        compose.onNodeWithText(keepNumbersWhole(callLine(call))).assertExists()
        assertTrue(callLine(call).contains("SENSEX 73100 CE above 280, stop none given, targets 310 / 345 / 400 · waiting to be priced · notification"))
    }

    @Test fun needsReviewFixAndConfirmWithOk() {
        val c = Calls()
        show(view, c)
        tap("About Signal tester (shadow)")
        tapText(keepNumbersWhole("Needs review (1)"))
        tap("Review s2")
        compose.onNodeWithText("Fix or confirm").assertExists()
        compose.onNodeWithText("Unsure: no index or stock named").assertExists()
        compose.onNodeWithText("Index or stock").performTextInput("sensex")
        compose.onNodeWithText("Targets (310 / 345 / 400)").performTextReplacement("250 / 280")
        tap("Confirm this call")
        assertEquals(listOf("confirm s2"), c.log)
        assertEquals(CallParser.Draft("SENSEX", 73200.0, Right.CE, null, CallParser.Trig.ABOVE, 220.0, null, null, null, listOf(250.0, 280.0)), c.confirmed)
    }

    @Test fun aSharedMessageIsRecordedOnlyAfterOk() {
        SignalTesterHost.setOn(true); SignalTesterHost.allow("Sensex Kings")
        var closed = false
        compose.setContent { IraAlgoTheme("light") { com.optionslab.app.data.ShareDialog("#sensex 73100 CE\nAbove 280", t0) { closed = true } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sensex Kings").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(SignalTesterHost.view.value.recs.isEmpty())
        compose.onNodeWithText("OK").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Recorded (shadow — never trades).").fetchSemanticsNodes().isNotEmpty() }
        val r = SignalTesterHost.view.value.recs.single()
        assertEquals(SignalTesterHost.SHARED, r.source); assertTrue(r.approx); assertEquals(t0, r.at)
        compose.onNodeWithText("OK").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        assertTrue(closed)
    }

    @Test fun theAllowListPasteAndSettings() {
        val c = Calls()
        show(view, c)
        tap("About Signal tester (shadow)")
        tapText("Allow-list")
        compose.onNodeWithText("Channel name").performTextInput("BN Pro")
        tapText("Add")
        tap("Remove Sensex Kings")
        tapText("Paste a message")
        compose.onNodeWithText("Message").performTextInput("SENSEX 73100 CE above 280")
        compose.onNodeWithText("Sent at (today, HH:MM:SS)").performTextReplacement("09:27:30")
        tap("Record this message")
        tapText("Settings")
        tap("Read Telegram notifications")
        compose.onNodeWithText("60 s").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        assertEquals(listOf("allow BN Pro", "disallow Sensex Kings", "paste Sensex Kings|SENSEX 73100 CE above 280|09:27:30|false", "listen true", "access", "delay 60"), c.log)
    }
}
