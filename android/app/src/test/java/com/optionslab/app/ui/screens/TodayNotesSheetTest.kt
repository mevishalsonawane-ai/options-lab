package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ira.Automations
import com.optionslab.app.ira.IraHub
import com.optionslab.app.ira.IraNotes
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.until
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.SettingFocus
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.TodayNotes
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
 * "Today's notes": what Jarvis posted by himself today, newest first with its time and category, three lines until
 * tapped; the filter chips; "Turn these off" only hands over the switch's row (nothing is switched); on the Ira page the
 * chip sits in the chat's header row, the question box and Ask still on screen on an ordinary phone.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class TodayNotesSheetTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val day = java.time.LocalDate.now(com.optionslab.engine.IST)

    @Before fun up() {
        AreaE.resetGlobals()
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(day.atTime(16, 0).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST)
        IraNotes.clear()
    }

    @After fun down() {
        com.optionslab.app.data.Market.testClock = null
        runBlocking { IraHub.forgetAll() }
        IraNotes.clear()
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private val long = "The wrap-up for today, Boss. " + "Each of your trades went as planned and the rules were kept all day long. ".repeat(6)

    private fun note(h: Int, m: Int, text: String, source: String? = null, kind: TodayNotes.Category? = null): TodayNotes.Note {
        val t = TodayNotes.tag(text, source, kind)
        return TodayNotes.Note(day.atTime(h, m), text, t.category, t.source)
    }

    private val notes = listOf(
        note(15, 45, "Tomorrow, a quiet session: no expiry.", "TOMORROW"),
        note(15, 35, long, "SUMMARY"),
        note(12, 1, "Solo (paper, not proven): bought a Nifty call.", TodayNotes.SOLO),
        note(10, 5, "BankNifty is near a Liquidity level.", "LIQUIDITY"),
        note(9, 20, "Opening read, Boss - a flat open."),
        note(9, 0, "Good morning. We are set for today's trading.", kind = TodayNotes.Category.PLANS),
    )

    private fun set(content: @Composable () -> Unit) { compose.setContent { IraAlgoTheme("light") { content() } }; compose.frames() }
    private fun tap(t: String) { compose.onNodeWithText(keepNumbersWhole(t), substring = true).performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle() }
    private fun shows(t: String) = compose.onAllNodesWithText(keepNumbersWhole(t), substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun lines(t: String): Int {
        val got = ArrayList<TextLayoutResult>()
        compose.onNodeWithText(keepNumbersWhole(t), substring = true, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(got) }
        return got.first().lineCount
    }

    @Test fun newestFirstWithTimeAndCategoryCollapsedUntilTapped() {
        set { TodayNotesSheet(notes, onTurnOff = {}, onClose = {}) }
        compose.onNodeWithText(TODAY_NOTES_TITLE).assertIsDisplayed()
        assertTrue(shows("Today only"))
        for (n in notes) assertTrue(n.text, shows(n.text.take(30)))
        // Newest first.
        val tops = notes.map { n -> compose.onAllNodesWithText(keepNumbersWhole(n.text.take(30)), substring = true, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        assertTrue(shows("15:45")); assertTrue(shows("09:00"))
        // Three lines until tapped, then whole; tapped again, three lines.
        assertEquals(TODAY_NOTES_LINES, lines("The wrap-up for today"))
        tap("The wrap-up for today")
        assertTrue(lines("The wrap-up for today") > TODAY_NOTES_LINES)
        tap("The wrap-up for today")
        assertEquals(TODAY_NOTES_LINES, lines("The wrap-up for today"))
    }

    @Test fun theChipsFilterAndTurnTheseOffOnlyHandsOverTheSwitch() {
        val asked = ArrayList<String>()
        val before = Automations.on(Automations.Group.MARKET) to Automations.on(Automations.Group.COACH)
        set { TodayNotesSheet(notes, onTurnOff = { asked += it }, onClose = null) }
        assertFalse("no Close in Settings", shows("Close"))
        for (c in TodayNotes.FILTERS) assertTrue(c.label, shows(c.label + " · "))
        assertFalse("no Other chip without such a note", shows("Other · "))
        // All: no shortcut.
        assertFalse(shows("Turn these off"))
        tap("Liquidity · 1")
        assertTrue(shows("BankNifty is near a Liquidity level"))
        assertFalse(shows("Opening read, Boss"))
        assertFalse(shows("Tomorrow, a quiet session"))
        tap("Turn these off: Market alerts")
        assertEquals(listOf("jarvis.switch.group.MARKET"), asked)
        tap("Market · 1")
        assertTrue(shows("Opening read, Boss"))
        tap("Turn these off: Opening read")
        assertEquals("jarvis.switch.sub.OPENING", asked.last())
        tap("Solo/Hero · 1")
        tap("Turn these off: Solo (midday)")
        assertEquals(IraNotes.SOLO_KEY, asked.last())
        tap("Plans · 2")
        assertTrue(shows("Good morning")); assertTrue(shows("Tomorrow, a quiet session"))
        tap("Turn these off: Coach me")
        assertEquals("jarvis.switch.group.COACH", asked.last())
        tap("News · 0")
        compose.waitForText("No News notes today.")
        // Tapped again: all.
        tap("News · 0")
        assertTrue(shows("Opening read, Boss"))
        // Nothing was switched.
        assertEquals(before, Automations.on(Automations.Group.MARKET) to Automations.on(Automations.Group.COACH))
    }

    @Test fun nothingYet() {
        set { TodayNotesSheet(emptyList(), onTurnOff = {}, onClose = null) }
        compose.waitForText("No notes from Jarvis yet today. When he posts one by himself in the chat, it is listed here.")
    }

    /** Settings → Today's notes: the notes Jarvis posted (as kept by the hub), and the shortcut handing over its row. */
    @Test fun theSettingsPageListsWhatThePagePosted() {
        val asked = ArrayList<String>()
        IraHub.note("BankNifty is near a Liquidity level.", from = Automations.Auto.LIQUIDITY)
        IraHub.note("Muted by voice, for today only.")
        set { TodayNotesPage(onTurnOff = { asked += it }) }
        compose.waitForText("BankNifty is near a Liquidity level.")
        assertTrue(shows("Muted by voice"))
        assertTrue("Other joins the chips", shows("Other · 1"))
        tap("Liquidity · 1")
        tap("Turn these off: Market alerts")
        assertEquals(listOf("jarvis.switch.group.MARKET"), asked)
        // A note posted while open shows at once.
        IraHub.note("Your day's target is reached.", from = Automations.Auto.TARGET)
        tap("Liquidity · 1")
        compose.waitForText("Your day's target is reached.")
    }

    /**
     * The Ira page on an ordinary phone: the chip in the chat's header row, the question box and Ask still on screen; a tap
     * opens the notes, "Turn these off" asks for Settings → Jarvis with the switch's row (navigation only), Close goes back.
     */
    @Test
    @org.robolectric.annotation.Config(qualifiers = "w411dp-h800dp")
    fun theIraPageChipOpensTheNotes() {
        IraHub.note("BankNifty is near a Liquidity level.", from = Automations.Auto.LIQUIDITY)
        set { IraPage(startInChat = true) }
        compose.until(20_000, "the data read") { !IraHub.state.value.loading }
        compose.waitForText("Ask Ira about the market")
        val rootBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val chip = compose.onNodeWithText(keepNumbersWhole("$TODAY_NOTES_TITLE · 1"))
        chip.assertIsDisplayed()
        for (t in listOf("Ask Ira about the market", "Ask")) {
            compose.onNodeWithText(t).assertIsDisplayed()
            val b = compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot
            assertTrue("$t on screen: ${b.bottom} of $rootBottom", b.bottom <= rootBottom + 0.5f)
        }
        chip.performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle()
        compose.waitForText("Today only", substring = true)
        assertTrue(shows("BankNifty is near a Liquidity level."))
        tap("Close")
        compose.waitForText("Ask Ira about the market")
        assertFalse(shows("Today only"))
        // Again, and the shortcut: Settings → Jarvis asked for with the row; the page goes back to the chat.
        compose.onNodeWithText(keepNumbersWhole("$TODAY_NOTES_TITLE · 1")).performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle()
        compose.waitForText("Today only", substring = true)
        tap("Liquidity · 1")
        tap("Turn these off: Market alerts")
        assertEquals("jarvis", SettingFocus.pageWanted.value)
        assertEquals("jarvis.switch.group.MARKET", SettingFocus.wanted.value)
        compose.waitForText("Ask Ira about the market")
    }
}
