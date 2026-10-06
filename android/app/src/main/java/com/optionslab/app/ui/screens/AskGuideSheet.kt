package com.optionslab.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.optionslab.app.ira.IraHub
import com.optionslab.app.ui.components.BrassButton
import com.optionslab.app.ui.components.Note
import com.optionslab.app.ui.components.Rule
import com.optionslab.app.ui.theme.LocalPalette
import com.optionslab.app.ui.theme.Type
import com.optionslab.ira.AskGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** This build's question guide: everything in IraAlgo; in IraGoldAlgo (which only talks, about gold) only the gold examples. */
internal fun askGuideGroups(): List<AskGuide.Group> = AskGuide.forBuild(com.optionslab.app.BuildConfig.GOLD)

/**
 * A question asked exactly as the Ira page's question box asks a typed one: through [IraHub.askSoon] (off the screen's
 * thread, in the order asked), then the reply said aloud when Boss has spoken replies on ([com.optionslab.app.ira.JarvisSpeaker.replyTo]).
 * The page's own send and the question guide both come here. Returns the job that ends when the question is taken in.
 */
internal fun askAsTyped(context: android.content.Context, scope: CoroutineScope, q: String): Job {
    val asked = IraHub.askSoon(q)
    scope.launch { asked.join(); com.optionslab.app.ira.JarvisSpeaker.replyTo(context, q) }
    return asked
}

/** The guide's tap from Settings outlives the page (it closes as the chat opens): the spoken reply waits here. */
private val guideScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

/** The Ira page's small "?" beside the question box: opens the guide. 48dp, so it is an easy tap and never crowds the box. */
@Composable
internal fun AskGuideChip(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.size(48.dp).clickable(role = Role.Button, onClick = onOpen).semantics { contentDescription = ASK_GUIDE_TITLE },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).background(p.chip, CircleShape).border(1.dp, p.rule, CircleShape), contentAlignment = Alignment.Center) {
            Text("?", style = Type.title.copy(color = p.ink, fontSize = 16.sp))
        }
    }
}

/** The guide's title (also the chip's spoken label and the Settings row). */
internal const val ASK_GUIDE_TITLE = "What can I ask?"

/**
 * "What can I ask?": every group of questions Jarvis answers ([groups], [AskGuide]), each header folding its examples (all
 * folded at first, so the groups read at a glance), and a search box that shows the examples holding its words (every
 * matching group open while searching). A tap on an example hands it to [onAsk] - the caller asks it exactly as typed and
 * closes the guide. [onClose] null: no Close (Settings has its own way back). Plain state only: what is open and the search
 * change on a tap or a keystroke, never during composition.
 */
@Composable
internal fun AskGuideSheet(onAsk: (String) -> Unit, onClose: (() -> Unit)?, groups: List<AskGuide.Group> = askGuideGroups(),
                           modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    // The open groups' ids, joined (a plain string is saved with the screen as it is).
    var openIds by rememberSaveable { mutableStateOf("") }
    val open = remember(openIds) { openIds.split(',').filter { it.isNotEmpty() }.toSet() }
    val shown = remember(groups, query) { AskGuide.filter(groups, query) }
    val searching = query.isNotBlank()
    Column(modifier.fillMaxSize().background(p.paper)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ASK_GUIDE_TITLE, style = Type.masthead.copy(color = p.ink, fontSize = 22.sp), modifier = Modifier.weight(1f))
            if (onClose != null) BrassButton("Close", tone = p.inkSoft, onClick = onClose)
        }
        Note("Tap a question to ask it, just as if you typed it. These are questions only: nothing here changes anything.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        OutlinedTextField(query, { query = it.take(60) }, placeholder = { Text("Search questions") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown.isEmpty()) item(key = "none") {
                Note("No question holds those words. Try fewer words, or clear the search.", Modifier.padding(vertical = 8.dp))
            }
            shown.forEach { g ->
                val isOpen = searching || g.id in open
                item(key = "g-" + g.id) {
                    AskGuideHeader(g, isOpen) {
                        openIds = (if (g.id in open) open - g.id else open + g.id).joinToString(",")
                    }
                }
                if (isOpen) items(g.examples, key = { e -> "q-" + g.id + "-" + e.q }) { e ->
                    Text(keepNumbersWhole(e.q), style = Type.body.copy(color = p.ink, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.card, RoundedCornerShape(14.dp))
                            .border(1.dp, p.rule, RoundedCornerShape(14.dp)).clickable(role = Role.Button) { onAsk(e.q) }
                            .padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
        }
    }
}

/** A group's header: its title, how many questions and what they are about; a tap folds or opens it. */
@Composable
private fun AskGuideHeader(g: AskGuide.Group, open: Boolean, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle).padding(top = 8.dp, bottom = 2.dp)) {
        Rule()
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(keepNumbersWhole(g.title), style = Type.title.copy(color = p.ink, fontSize = 16.sp), modifier = Modifier.weight(1f))
            Text(keepNumbersWhole("${g.examples.size} " + if (open) "▾" else "▸"),
                style = Type.bodySmall.copy(color = p.inkSoft, fontWeight = FontWeight.SemiBold))
        }
        Text(keepNumbersWhole(g.blurb), style = Type.bodySmall.copy(color = p.inkSoft, fontSize = 12.sp))
    }
}

/**
 * Settings → App → "What can I ask?": the same guide as a page. A tap asks the question exactly as typed and then [onAsked]
 * (the caller opens the chat, where the reply is; this page closes with it).
 */
@Composable
internal fun AskGuidePage(onAsked: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    AskGuideSheet(onAsk = { q -> askAsTyped(ctx, guideScope, q); onAsked(q) }, onClose = null)
}
