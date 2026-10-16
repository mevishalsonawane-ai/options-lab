package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
 * its switch (off by default); the name's dialog with the explanation and each followed chat as a row (name, one status line,
 * its "paper-trade its calls" switch; the name: its scorecard and calls), the chat picker (titles only; "Check this chat" on a
 * tap), Needs review with the fix dialog (OK confirms), the add-by-name editor, the paste box and the settings - each with OK.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class SignalTesterCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val t0 = LocalDateTime.of(2026, 10, 15, 9, 28, 4)
    private val draft = CallParser.Draft("SENSEX", 73100.0, Right.CE, null, CallParser.Trig.ABOVE, 280.0, null, null, null, listOf(310.0, 345.0, 400.0))
    private val call = SignalTesterHost.Rec("s1", "Sensex Kings", t0, SignalTesterHost.PASTED, kind = SignalTesterHost.CALL, text = "#sensex 73100 CE", draft = draft)
    private val review = SignalTesterHost.Rec("s2", "Sensex Kings", t0.plusMinutes(5), SignalTesterHost.PASTED, kind = SignalTesterHost.REVIEW,
        text = "73200 CE above 220", unsure = listOf("no index or stock named"), draft = CallParser.Draft(null, 73200.0, Right.CE, trigger = 220.0))
    private val view = SignalTesterHost.View(on = true, allow = listOf("Sensex Kings"),
        cards = listOf(ChannelScore.card("Sensex Kings", listOf(ChannelScore.Item(ChannelScore.Kind.CALL), ChannelScore.Item(ChannelScore.Kind.REVIEW)))),
        recs = listOf(call, review), review = listOf(review))

    private class Calls { val log = ArrayList<String>(); var confirmed: CallParser.Draft? = null }

    private fun show(v: SignalTesterHost.View, c: Calls = Calls()) = compose.setContent {
        IraAlgoTheme("light") {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SignalTesterCardContent(v, com.optionslab.app.data.SignalPaperArm.View(), said = null, a = SignalActions(
                    onSwitch = { c.log += "switch $it" }, paper = { ch, on -> c.log += "paper $ch $on" },
                    paperExit = { c.log += "exit ${it.key}" },
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
        compose.onNodeWithText("Signal tester").assertExists()
        compose.onNodeWithText("Off · shadow — never trades").assertExists()
        compose.onNodeWithContentDescription("Switch the Signal tester").assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf("switch true"), c.log)
        // No explanation on the row itself.
        compose.onNodeWithText(signalExplanation().first()).assertDoesNotExist()
    }

    @Test fun theNamesDialogChannelsAndTheCallsList() {
        show(view)
        compose.onNodeWithText(keepNumbersWhole("On · shadow — never trades · 1 chat · 1 calls · 1 to review")).assertExists()
        tap("About Signal tester")
        compose.onNodeWithText(keepNumbersWhole(signalExplanation().first())).assertExists()
        compose.onNodeWithText(keepNumbersWhole(chatStatusLine(view.cards.single(), com.optionslab.app.data.SignalPaperArm.View()))).assertExists()
        // The chat's own paper switch (off by default) on its row.
        tap("Paper-trade the calls of Sensex Kings")
        tap("Chat Sensex Kings")
        compose.onNodeWithText(keepNumbersWhole(callLine(call))).assertExists()
        assertTrue(callLine(call).contains("SENSEX 73100 CE above 280, stop none given, targets 310 / 345 / 400 · waiting to be priced · pasted"))
    }

    @Test fun needsReviewFixAndConfirmWithOk() {
        val c = Calls()
        show(view, c)
        tap("About Signal tester")
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
        tap("About Signal tester")
        tapText("Add or remove a chat by name")
        compose.onNodeWithText("Channel name").performTextInput("BN Pro")
        tapText("Add")
        tap("Remove Sensex Kings")
        tapText("Paste a message")
        compose.onNodeWithText("Message").performTextInput("SENSEX 73100 CE above 280")
        compose.onNodeWithText("Sent at (today, HH:MM:SS)").performTextReplacement("09:27:30")
        tap("Record this message")
        tapText("Settings")
        compose.onNodeWithText(READS_ONLY).assertExists()
        compose.onNodeWithText("60 s").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        compose.onNodeWithText("half at T1, then the stop to cost").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        assertEquals(listOf("allow BN Pro", "disallow Sensex Kings", "paste Sensex Kings|SENSEX 73100 CE above 280|09:27:30|false", "delay 60", "exit half"), c.log)
    }

    @Test fun theChatPickerShowsTitlesOnlyAndChecksAChatOnlyOnATap() {
        val checked = ArrayList<String>(); var chosen: Set<String>? = null
        val seen = listOf(SignalTesterHost.Seen("Sensex Kings", null, "export"), SignalTesterHost.Seen("Mom", null, "export"),
            SignalTesterHost.Seen("BN Pro", com.optionslab.engine.signals.ChatScan.Stats(20, 9, 2), "export"))
        compose.setContent { IraAlgoTheme("light") { ChatPickerDialog(seen, emptyList(), onCheck = { checked += it }, onOk = { chosen = it }) {} } }
        compose.waitForIdle()
        compose.onNodeWithText(READS_ONLY).assertExists()
        // Titles only until Boss checks one; the checked one shows its counts and the badge.
        compose.onNodeWithText(keepNumbersWhole("9 of 20 recent messages looked like calls (2 follow-ups) · looks like a trade-call channel")).assertExists()
        tap("Check Sensex Kings")
        assertEquals(listOf("Sensex Kings"), checked)
        // Nothing ticked at first; Boss ticks one channel.
        tap("Follow Sensex Kings")
        tap("Follow the ticked chats")
        assertEquals(setOf("Sensex Kings"), chosen)
    }

    private fun showTelegram(tv: com.optionslab.app.data.TelegramCapture.View, v: SignalTesterHost.View, log: ArrayList<String>) = compose.setContent {
        IraAlgoTheme("light") {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TelegramRow(tv, v, TelegramActions(keys = { id, h -> log += "keys $id ${h.length}" }, login = { log += "login" },
                    phone = { log += "phone $it" }, code = { log += "code $it" }, password = { log += "password" },
                    capture = { log += "capture $it" }, channels = { log += "channels" }, history = { log += "history $it" },
                    window = { f, t -> log += "window $f-$t" }, logOut = { log += "logout" }))
            }
        }
    }

    @Test fun theTelegramRowIsCompactAndItsDialogWalksTheLogin() {
        val log = ArrayList<String>()
        val tv = com.optionslab.app.data.TelegramCapture.View(available = true)
        showTelegram(tv, SignalTesterHost.View(on = true), log)
        // Compact: the name, one status line, the capture switch (off; it needs a login first).
        compose.onNodeWithText("Telegram login").assertExists()
        compose.onNodeWithText("Not set up: enter your api_id and api_hash").assertExists()
        compose.onNodeWithContentDescription("Capture the selected Telegram channels").assertIsOff()
        // The name: the dialog, which says what it reads, and asks for the keys (the hash masked).
        tap("Telegram login")
        compose.onNodeWithText(keepNumbersWhole(READS_ONLY)).assertExists()
        compose.onNodeWithContentDescription("api_id").performTextInput("12345")
        compose.onNodeWithContentDescription("api_hash").performTextInput("0123456789abcdef0123456789abcdef")
        compose.onAllNodesWithText("0123456789abcdef0123456789abcdef").fetchSemanticsNodes().let { assertTrue("the hash is masked", it.isEmpty()) }
        tapText("Save keys")
        assertEquals(listOf("keys 12345 32"), log)
        tapText("OK")
    }

    @Test fun theLoginStepsAndTheChannelsInTheDialog() {
        val log = ArrayList<String>()
        val step = androidx.compose.runtime.mutableStateOf(com.optionslab.app.data.TelegramCapture.View(available = true, keys = true,
            auth = com.optionslab.engine.signals.TdSession.Auth.NEED_CODE, hint = "in the Telegram app"))
        val v = SignalTesterHost.View(on = true, tg = mapOf(-1001L to "Sensex Kings"))
        compose.setContent { IraAlgoTheme("light") { TelegramDialog(step.value, v, TelegramActions(code = { log += "code $it" }, history = { log += "history $it" },
            window = { f, t -> log += "window $f-$t" }, logOut = { log += "logout" }, channels = { log += "channels" })) { log += "closed" } } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Login code").performTextInput("12345")
        tapText("Send")
        assertEquals("code 12345", log.last())
        // Logged in: the channels (one selected), its history, the capture window, log out - and a single OK.
        step.value = com.optionslab.app.data.TelegramCapture.View(available = true, keys = true, loggedIn = true, capture = true,
            auth = com.optionslab.engine.signals.TdSession.Auth.READY)
        compose.waitForIdle()
        compose.onNodeWithText("Choose channels (1 selected)").assertExists()
        tap("History of Sensex Kings")
        assertEquals("history -1001", log.last())
        tapText(keepNumbersWhole("08:30-15:40"))
        assertEquals("window 08:30-15:40", log.last())
        tap("Log out of Telegram")
        assertEquals("logout", log.last())
        tapText("OK")
        assertEquals("closed", log.last())
    }

    @Test fun aChatsDialogShowsItsMonthCheckAndItsClaims() {
        val pv = com.optionslab.app.data.SignalPaperArm.View(on = mapOf(com.optionslab.app.data.SignalPaperArm.chatId("Sensex Kings") to true),
            since = mapOf(com.optionslab.app.data.SignalPaperArm.chatId("Sensex Kings") to java.time.LocalDate.of(2026, 10, 15)))
        val lines = monthLines(view, "Sensex Kings", pv, java.time.LocalDate.of(2026, 10, 20))
        assertTrue(lines.first(), lines.first().startsWith("Month check (paper since 2026-10-15, day 6 of 30): did not pass: at least 40 triggered calls (0 paper trades)"))
        assertTrue(lines.any { it.startsWith("✗ at least 40 triggered calls") })
        assertTrue(channelLines(view, "Sensex Kings", pv).any { it.startsWith("Month check") })
        assertTrue(channelLines(view, "Sensex Kings").any { it.startsWith("Claims accuracy:") })
        val f = SignalTesterHost.Rec("s3", "Sensex Kings", t0.plusMinutes(9), SignalTesterHost.TELEGRAM, kind = SignalTesterHost.FOLLOW, text = "T1 done",
            follow = CallParser.Reading.FollowUp(CallParser.Follow.TARGET, 1), link = "s1", verdict = com.optionslab.engine.signals.CallReader.Verdict.AFTER_STOP)
        assertTrue(callLine(f).contains("follow-up: target hit (T1) - reached only after our stop · Telegram (read-only login)"))
    }

    // ---- 16 Oct, Boss: "Telegram login button not working" (its results were shown only behind the dialog) -------------------

    @Test fun theLoginsResultsShowInsideTheDialogWithABusyStateAndTryAgain() {
        val log = ArrayList<String>()
        val step = androidx.compose.runtime.mutableStateOf(com.optionslab.app.data.TelegramCapture.View(available = true, keys = true))
        compose.setContent { IraAlgoTheme("light") { TelegramDialog(step.value, SignalTesterHost.View(on = true), TelegramActions(login = { log += "login" },
            phone = { log += "phone $it" }, password = { log += "password ${it.length}" }, retry = { log += "retry" })) { log += "closed" } } }
        compose.waitForIdle()
        tapText("Log in to Telegram")
        assertEquals("login", log.last())
        // Starting: a spinner with its words, the "Saved…" said kept, no second "Log in" to tap.
        step.value = step.value.copy(running = true, auth = com.optionslab.engine.signals.TdSession.Auth.STARTING, said = "Saved on this phone only (encrypted). Starting Telegram…")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Telegram is starting").assertExists()
        compose.onNodeWithText("Starting Telegram…").assertExists()
        compose.onNodeWithText(keepNumbersWhole("Saved on this phone only (encrypted).")).assertExists()
        assertTrue(compose.onAllNodesWithText("Log in to Telegram").fetchSemanticsNodes().isEmpty())
        // Stuck: what to check, and "Try again".
        step.value = step.value.copy(stuck = true, said = com.optionslab.app.data.TelegramCapture.STUCK_WORDS)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(com.optionslab.app.data.TelegramCapture.STUCK_WORDS)).assertExists()
        tapText("Try again")
        assertEquals("retry", log.last())
        // The phone step: its field, and what TDLib said, in the dialog.
        step.value = step.value.copy(stuck = false, auth = com.optionslab.engine.signals.TdSession.Auth.NEED_PHONE, said = "Telegram started. Enter your phone number with the country code (+91…), then Send.")
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithContentDescription("Telegram is starting").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(keepNumbersWhole("Telegram started. Enter your phone number with the country code (+91…), then Send.")).assertExists()
        compose.onNodeWithContentDescription("Phone number (+91…)").performTextInput("+91 99999 00000")
        tapText("Send")
        assertEquals("phone +91 99999 00000", log.last())
        // TDLib's error in plain words; then the 2-step password, masked.
        step.value = step.value.copy(said = "Telegram says that phone number is not valid: enter it with the country code, e.g. +91 98…")
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("Telegram says that phone number is not valid: enter it with the country code, e.g. +91 98…")).assertExists()
        step.value = step.value.copy(auth = com.optionslab.engine.signals.TdSession.Auth.NEED_PASSWORD, hint = "pet", said = "Your account has 2-step verification: enter its password, then Send.")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("2-step verification password").performTextInput("secret77")
        assertTrue("the password is masked", compose.onAllNodesWithText("secret77").fetchSemanticsNodes().isEmpty())
        tapText("Send")
        assertEquals("password 8", log.last())
        tapText("OK")
        assertEquals("closed", log.last())
    }

    @Test fun aPhoneWithoutTheLibrarySaysWhy() {
        val log = ArrayList<String>()
        val why = "Not on this phone: the Telegram login needs a 64-bit ARM phone (arm64-v8a); this one is armeabi-v7a"
        val tv = com.optionslab.app.data.TelegramCapture.View(available = false, why = why)
        compose.setContent { IraAlgoTheme("light") { Column { SignalPanel(SignalTesterHost.View(on = true), com.optionslab.app.data.SignalPaperArm.View(), null,
            SignalActions(tg = TelegramActions(login = { log += "login" })), tv) } } }
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(why)).assertExists()
        tap("Telegram login")
        // The dialog says it too, and offers no step that cannot work.
        assertTrue(compose.onAllNodesWithText("Log in to Telegram").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("api_id").fetchSemanticsNodes().isEmpty())
        assertTrue(log.isEmpty())
    }
}
