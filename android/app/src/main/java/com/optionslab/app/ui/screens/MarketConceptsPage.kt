package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.optionslab.app.ira.ConceptLibrary
import com.optionslab.app.ui.Page
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.Concepts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MARKET_CONCEPTS_TITLE = "Market concepts"

/**
 * Market concepts (15 Oct 2026; Boss: Solo should "understand the concepts"): OI, gamma, traps, fake breakouts, order flow
 * and the rest of Jarvis's concept book ([ConceptLibrary]). COMPACT (Boss's UI rule): the page lists the names only; a tap on
 * a name opens a small dialog with just that concept - its meaning, how the app measures it, its signal source, our research
 * verdict with sample sizes - and one OK button. The book is read off the main thread (once; kept in memory). Words only.
 */
@Composable
internal fun MarketConceptsPage() {
    val context = LocalContext.current.applicationContext
    var book by remember { mutableStateOf(ConceptLibrary.cached()) }
    LaunchedEffect(Unit) { if (book == null) book = withContext(Dispatchers.IO) { runCatching { ConceptLibrary.load(context) }.getOrDefault(Concepts.Book.EMPTY) } }
    MarketConceptsContent(book)
}

/** The page from plain state: [book] null while it is read, empty when it could not be. */
@Composable
internal fun MarketConceptsContent(book: Concepts.Book?) {
    val p = LocalPalette.current
    Page {
        item { PageTitle(MARKET_CONCEPTS_TITLE, "Tap a name: what it means, where its signal comes from, what our research found") }
        when {
            book == null -> item { Text("Reading the concept book…", style = Type.bodySmall.copy(color = p.inkSoft)) }
            book.concepts.isEmpty() -> item { Text("The concept book could not be read.", style = Type.bodySmall.copy(color = p.inkSoft)) }
            else -> item {
                LedgerCard(Modifier.testTag("concepts-list")) {
                    book.concepts.forEachIndexed { i, c ->
                        if (i > 0) Rule()
                        ExplainedName(c.name, Concepts.lines(c), Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
