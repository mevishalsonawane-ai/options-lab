package com.optionslab.app.ira

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.until
import com.optionslab.app.ui.theme.IraAlgoTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "What is gamma exposure?", "bull trap kya hai", "what is a fake breakout?" (15 Oct 2026): Jarvis answers from the concept
 * book - the meaning, the signal source and the honest research verdict - never an order or a command. A glossary word keeps
 * its meaning with the research note added. With the book let go by a trim, the glossary still answers, and the book is read
 * again for the next question.
 */
@RunWith(AndroidJUnit4::class)
class ConceptAskRoutingTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()

    @Before fun up() { AreaE.resetGlobals() }
    @After fun down() {
        runBlocking { IraHub.forgetAll() }
        AreaE.resetGlobals()
    }

    private fun lastReply(): String? = IraHub.state.value.messages.lastOrNull()?.takeIf { it.fromIra }?.text

    @Test fun aConceptIsAnsweredWithItsSourceAndVerdict() {
        ConceptLibrary.load(ApplicationProvider.getApplicationContext())
        compose.setContent { IraAlgoTheme("light") { Text("Jarvis") } }
        IraHub.ask("what is gamma exposure")
        compose.until(30_000, "the concept answer") { lastReply()?.startsWith("Gamma exposure (GEX)") == true }
        assertTrue(lastReply()!!, "Our research: Size only" in lastReply()!!)
        assertTrue(IraHub.state.value.pending.isEmpty())
        IraHub.ask("bull trap kya hai")
        compose.until(30_000, "the Hinglish concept answer") { lastReply()?.startsWith("Bull trap and bear trap:") == true }
        IraHub.ask("what is a fake breakout?")
        compose.until(30_000, "the fake-breakout answer") { lastReply()?.startsWith("Fake (failed) breakout:") == true }
        // A glossary word keeps the glossary's meaning, with what our research found added.
        IraHub.ask("what is theta")
        compose.until(30_000, "the glossary answer with the research note") { lastReply()?.let { it.startsWith("Theta is how much") && "Our research: Describes the option only" in it } == true }
        assertTrue(IraHub.state.value.pending.isEmpty())
    }

    @Test fun withTheBookLetGoTheGlossaryStillAnswersAndTheBookIsReadAgain() {
        compose.setContent { IraAlgoTheme("light") { Text("Jarvis") } }
        // The hub needs the app's context to read the book again (the tests' app does not start it).
        IraHub.init(ApplicationProvider.getApplicationContext()); IraHub.awaitLoadedBlocking()
        // The start-up's own read finishes first, so the read after the drop below can only be the question's.
        compose.until(30_000, "the start-up's read") { ConceptLibrary.cached() != null }
        ConceptLibrary.drop()
        IraHub.ask("what is theta")
        compose.until(30_000, "the glossary answer") { lastReply()?.startsWith("Theta is how much") == true }
        compose.until(30_000, "the book read again") { ConceptLibrary.cached() != null }
    }
}
