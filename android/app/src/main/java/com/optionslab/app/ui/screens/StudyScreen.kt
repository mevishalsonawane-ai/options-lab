package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ira.StudyLibrary
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.StudyBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Research → Learn (S19): the study pages - each strategy explained, why most ideas fail, reading order flow, VWAP, the
 * value area, OI and VIX, how the trade manager, Autopilot and the lab decide, risk and sizing at ₹1 lakh - each with the
 * app's own research numbers, searchable. A tap opens the page over the app ([SelfSheetHost]); Jarvis opens one too
 * ("teach me about VWAP"). Read from the bundled summary off the main thread.
 */
@Composable
fun StudyScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pages by remember { mutableStateOf<List<StudyBook.Page>?>(null) }
    LaunchedEffect(Unit) { pages = withContext(Dispatchers.IO) { StudyLibrary.pages(ctx) } }
    StudyList(pages) { StudyLibrary.show(it.id) }
}

/** The list from plain state (tests drive it): the search box, then the pages by group, best match first when searching. */
@Composable
internal fun StudyList(pages: List<StudyBook.Page>?, onOpen: (StudyBook.Page) -> Unit) {
    val p = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(pages, query) { pages?.let { StudyBook.search(it, query) }.orEmpty() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
        item(key = "search") {
            OutlinedTextField(query, { query = it.take(60) }, label = { Text("Search the study pages") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("study-search"))
        }
        if (pages == null) item(key = "reading") { Note("Opening the study pages…") }
        else if (shown.isEmpty()) item(key = "none") { Note("No page on \"$query\" yet.") }
        items(shown, key = { it.id }) { page ->
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.card, RoundedCornerShape(14.dp))
                .border(1.dp, p.rule, RoundedCornerShape(14.dp)).clickable(onClickLabel = "Open the page") { onOpen(page) }
                .padding(horizontal = 14.dp, vertical = 10.dp).testTag("study-" + page.id)) {
                Text(page.title, style = Type.body.copy(color = p.ink, fontWeight = FontWeight.SemiBold))
                Text(page.group, style = Type.label.copy(color = p.inkSoft, fontSize = 11.sp))
                Text(page.paragraphs.firstOrNull().orEmpty().take(160).let { if (it.length == 160) "$it…" else it },
                    style = Type.bodySmall.copy(color = p.inkSoft), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
