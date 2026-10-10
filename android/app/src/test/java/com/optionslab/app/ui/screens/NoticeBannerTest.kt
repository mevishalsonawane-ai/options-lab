package com.optionslab.app.ui.screens

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ira.IraHub
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.has
import com.optionslab.app.testing.until
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.NavState
import com.optionslab.app.ui.SettingFocus
import com.optionslab.app.ui.Tab
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.app.work.NoticeCard
import com.optionslab.app.work.NoticeCards
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tapped notification opened over the app: the whole text; for one that asks something, the chat's own buttons
 * (pressing one is the chat's own handler); nothing to press once it is no longer waiting; a Settings notice opens its
 * page with the row brought into view and highlighted.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp")
@RunWith(AndroidJUnit4::class)
class NoticeBannerTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun up() { AreaE.resetGlobals(); SettingFocus.reset() }
    @After fun down() {
        runBlocking { IraHub.forgetAll() }
        SettingFocus.reset(); NoticeCards.forgetAll()
        AreaE.resetGlobals()
    }

    private fun card(text: String, action: Long? = null, close: String? = null, setting: String? = null) =
        NoticeCard(7401, "jarvis", "Boss, a goal is broken", text, 1_759_640_000_000L, tab = "almanac", action = action, close = close, setting = setting)

    private fun tap(text: String) { compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick); compose.frames() }

    @Test fun theBannerShowsTheWholeNotice() {
        var dismissed = false
        val long = "Today's Paper P&L Rs -5,200 reached the Rs 5,000 daily loss limit. The bot sold what it held and stopped for today. " +
            "Nothing new is opened until tomorrow; exits still go."
        compose.setContent { IraAlgoTheme("light") { NoticeBanner(card(long), onDismiss = { dismissed = true }, onClose = { _, _ -> }) } }
        compose.frames()
        compose.waitForText("Boss, a goal is broken")
        compose.waitForText(long)
        compose.onNodeWithTag("notice-banner", useUnmergedTree = true).assertExists()
        assertFalse("nothing asked: no Yes", compose.has("Yes"))
        tap("OK")
        assertTrue(dismissed)
    }

    @Test fun aWaitingRequestShowsTheChatsOwnButtonsAndCancelIsTheChatsCancel() = runBlocking {
        IraHub.ask("hit the panic button")
        compose.until(60_000, "the confirm") { IraHub.state.value.pending.isNotEmpty() }
        val id = IraHub.state.value.pending.single()
        compose.setContent { IraAlgoTheme("light") { NoticeBanner(card("Shall I switch the kill switch on?", action = id), onDismiss = {}, onClose = { _, _ -> }) } }
        compose.frames()
        compose.waitForText("Yes")
        compose.waitForText("No")
        assertFalse("nothing before Yes", com.optionslab.app.data.AppSettings.load().guardKill)
        tap("No")
        compose.until(10_000, "cancelled") { id !in IraHub.state.value.pending }
        assertEquals("Cancelled; nothing was done.", IraHub.state.value.messages.last().text)
        assertFalse("cancelled: nothing done", com.optionslab.app.data.AppSettings.load().guardKill)
        compose.waitForText("Done. Jarvis: Cancelled; nothing was done.")
        assertFalse("answered: no button left", compose.has("Yes"))
    }

    @Test fun aRequestNoLongerWaitingShowsNoButton() {
        compose.setContent { IraAlgoTheme("light") { NoticeBanner(card("Shall I close NIFTY?", action = 987_654_321L), onDismiss = {}, onClose = { _, _ -> }) } }
        compose.frames()
        compose.waitForText("This is no longer waiting", substring = true)
        assertFalse(compose.has("Confirm")); assertFalse(compose.has("Cancel"))
        assertFalse(compose.has("Approve with fingerprint"))
    }

    @Test fun aPositionsCloseOpensTheAppsOwnCloseAndAClosedOneShowsNone() {
        var asked: Pair<String, String>? = null
        var open by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            IraAlgoTheme("light") {
                androidx.compose.runtime.key(open) {
                    NoticeBanner(card("LONG 75 @ 100.00", close = "Paper|NIFTY25O2124500PE"), onDismiss = {}, onClose = { v, s -> asked = v to s },
                        stillOpen = { _, _ -> open })
                }
            }
        }
        compose.frames()
        compose.waitForText("Close position…")
        tap("Close position…")
        assertEquals("Paper" to "NIFTY25O2124500PE", asked)
        open = false
        compose.frames()
        compose.waitForText("NIFTY25O2124500PE is no longer open: nothing to close.")
        assertFalse(compose.has("Close position…"))
    }

    @Test fun aSettingsNoticeOpensItsPageWithTheRowHighlighted() {
        val c = card("Open IraAlgo to keep strategies and alerts checked.", setting = "schedule.permissions")
        val route = NoticeCards.route(c) as NoticeCards.Route.Setting
        assertEquals(NavState(tab = Tab.CABINET, cabinetPage = "schedule"), NavState(tab = Tab.TRADE).setting(route.page))
        val offline = OfflineModel(app)
        try {
            SettingFocus.ask(route.key)
            compose.setContent { IraAlgoTheme("light") { SchedulePage(offline.model) } }
            compose.frames()
            compose.until(20_000, "the row highlighted") { SettingFocus.lit.value == "schedule.permissions" }
            compose.onNodeWithTag("setting:schedule.permissions", useUnmergedTree = true).assertExists()
            compose.waitForText("Permissions")
            assertEquals("shown once", null, SettingFocus.wanted.value)
        } finally { offline.close() }
    }
}
