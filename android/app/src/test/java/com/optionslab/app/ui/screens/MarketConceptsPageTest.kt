package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ira.ConceptLibrary
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.Concepts
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Market concepts (15 Oct 2026; compact, Boss's UI rule): the page lists the concept names only; a tap on one opens a dialog
 * with just that concept (its meaning, signal source and research verdict) and OK closes it. While the book is read the page
 * says so; a book that cannot be read says that, never a crash.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class MarketConceptsPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val book: Concepts.Book by lazy { ConceptLibrary.load(ApplicationProvider.getApplicationContext()) }

    private fun show(b: Concepts.Book?) = compose.setContent { IraAlgoTheme("light") { MarketConceptsContent(b) } }

    @Test fun namesOnlyThenOneTapOpensJustThatConcept() {
        show(book)
        compose.waitForIdle()
        compose.onNodeWithText(MARKET_CONCEPTS_TITLE).assertExists()
        val trap = book.byId("traps")!!
        val gex = book.byId("gex")!!
        compose.onNodeWithText(trap.name).performScrollTo().assertExists()
        // Names only: no meaning, verdict or source on the list.
        compose.onNodeWithText(keepNumbersWhole(trap.what)).assertDoesNotExist()
        compose.onNodeWithText(keepNumbersWhole("Our research: ${trap.verdict}")).assertDoesNotExist()
        compose.onNodeWithContentDescription("About ${trap.name}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(trap.what)).assertExists()
        compose.onNodeWithText(keepNumbersWhole("Signal source: ${trap.sourceText}")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("Our research: ${trap.verdict}")).assertExists()
        // Just that concept: another's meaning is not in its dialog.
        compose.onNodeWithText(keepNumbersWhole(gex.what)).assertDoesNotExist()
        compose.onNodeWithText("OK").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(trap.what)).assertDoesNotExist()
        compose.onNodeWithText("OK").assertDoesNotExist()
    }

    @Test fun whileReading() {
        show(null)
        compose.waitForIdle()
        compose.onNodeWithText("Reading the concept book…").assertExists()
    }

    @Test fun whenTheBookCannotBeRead() {
        show(Concepts.Book.EMPTY)
        compose.waitForIdle()
        compose.onNodeWithText("The concept book could not be read.").assertExists()
    }

    // 16 Oct: waits for the name on screen, not for ConceptLibrary.cached(). The cache is filled on the IO thread a moment
    // before the page's coroutine hands the book back to Compose, and waitForIdle does not wait for that hand-back, so
    // the old wait could read the screen while it still said "Reading…" (failed once in CI, shard 3/4). Any other test's
    // background load (IraHub warms the book on IO) could also fill the cache before this page read it.
    @Test fun thePageReadsTheBookOffTheMainThreadAndListsIt() {
        val first = book.concepts.first().name
        ConceptLibrary.drop()
        compose.setContent { IraAlgoTheme("light") { MarketConceptsPage() } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(first).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(first).assertExists()
        compose.onNodeWithText("Reading the concept book…").assertDoesNotExist()
        assertNotNull(ConceptLibrary.cached())
    }
}
