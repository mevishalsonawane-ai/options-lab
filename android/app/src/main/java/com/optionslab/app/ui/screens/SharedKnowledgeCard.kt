package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.app.data.SharedKnowledgeHost
import com.optionslab.app.ui.components.LedgerCard
import com.optionslab.app.ui.components.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The card's title. */
internal const val SHARED_CARD_TITLE = "Shared knowledge"

/**
 * The shared knowledge's one switch (13 Oct, Bot settings): the shadow rule "the research says this strategy does not pay" - OFF
 * by default. On, it only COUNTS each night the trades that rule would have skipped (a log line and the lessons bundle); it never
 * skips, sizes or touches an order, and never Liquidity (frozen to 26 Oct). The switch is read and written off the main thread.
 */
@Composable
internal fun SharedKnowledgeCard() {
    if (!SharedKnowledgeHost.enabled()) return
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(SharedKnowledgeHost.shadowInMemory()) }
    LaunchedEffect(Unit) { on = withContext(Dispatchers.IO) { runCatching { SharedKnowledgeHost.shadowOn }.getOrDefault(false) } }
    SharedKnowledgeContent(on) { v -> on = v; scope.launch(Dispatchers.IO) { runCatching { SharedKnowledgeHost.shadowOn = v } } }
}

/** What the shared knowledge's name opens. */
internal const val SHARED_NOTE = "Every agent now reads one shared knowledge - the research, Jarvis's lessons and the findings - to explain and rank, never to trade."

/** The card from plain state (tests drive it). */
@Composable
internal fun SharedKnowledgeContent(on: Boolean, onChange: (Boolean) -> Unit) {
    LedgerCard {
        // COMPACT (15 Oct): what it is, in its name's dialog.
        ExplainedName(SHARED_CARD_TITLE, listOf(SHARED_NOTE), style = Type.title.copy(color = LocalPalette.current.ink, fontSize = 16.sp))
        ToggleRow("Shadow: count trades the research would skip", "Logs only, each night after the close. Nothing is skipped and no size changes; never Liquidity.",
            on, onChange = onChange)
        if (on) Note("On: the count is in Diagnostics and in the nightly lessons bundle.", Modifier.padding(top = 4.dp))
    }
}
